package com.velasim.app;

import android.content.Context;
import android.os.FileObserver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Build a Vela quick-app project with the bundled aiot-toolkit, then install and
 * start it in the running guest.
 *
 * Verified against a live install:
 *   argv  : node .../aiot-toolkit/lib/bin.js build          (≈1s for the demo project)
 *   output: <project>/dist/<package>.debug.<versionName>.rpk
 *   ok    : a line containing "build success"
 *   fail  : a line containing "ERROR:  build error"
 * The toolkit also logs a `read quickapp.config.js error: Cannot find module
 * 'mini-css-extract-plugin'` line on projects that do not install the optional CSS
 * loaders — that is NOT a failure, so success is decided by the markers above rather
 * than by the exit code alone.
 *
 * Install/launch is delegated to {@link VelaDeploy} because the guest's adbd cannot
 * take an adb sync push.
 */
public final class VelaDevServer {

    private static final String TAG = "devserver";

    private final Context ctx;
    private final VelaProjects projects;
    private final VelaToolchain toolchain;
    private final VelaChannel channel;

    private final AtomicBoolean building = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    private volatile long lastBuildMs = 0;
    private volatile File lastRpk = null;

    private Watcher watcher;
    private String watchName;

    public VelaDevServer(Context ctx, VelaProjects projects, VelaToolchain toolchain,
                         VelaChannel channel) {
        this.ctx = ctx.getApplicationContext();
        this.projects = projects;
        this.toolchain = toolchain;
        this.channel = channel;
    }

    // ------------------------------------------------------------------- build

    /** Blocking build. Callers from the channel run this on the worker thread. */
    public Map<String, Object> build(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", name);
        if (!building.compareAndSet(false, true)) {
            out.put("ok", false);
            out.put("msg", "已有构建在跑，请等它结束");
            return out;
        }
        long t0 = System.currentTimeMillis();
        try {
            File dir = projects.require(name);
            List<String> argv = toolchain.buildArgv(dir, "build");
            if (argv == null) {
                out.put("ok", false);
                out.put("msg", toolchain.missingWhy(dir));
                lastError.set((String) out.get("msg"));
                return out;
            }
            emit("build", name, "start", String.join(" ", argv));

            final AtomicBoolean sawSuccess = new AtomicBoolean(false);
            final AtomicBoolean sawError = new AtomicBoolean(false);
            final StringBuilder errLine = new StringBuilder();
            int code = runTool(argv, dir, toolchain.envFor(dir.getAbsolutePath()),
                    new Line() {
                        @Override
                        public void on(String line, boolean stderr) {
                            String l = stripAnsi(line);
                            if (l.isEmpty()) {
                                return;
                            }
                            emit("build", name, "out", l);
                            if (l.contains("build success")) {
                                sawSuccess.set(true);
                            } else if (l.contains("build error") || l.contains("ERROR:")) {
                                sawError.set(true);
                                if (errLine.length() == 0) {
                                    errLine.append(l);
                                }
                            }
                        }
                    });

            lastBuildMs = System.currentTimeMillis() - t0;
            File rpk = VelaProjects.newestArtifact(dir);
            boolean ok = sawSuccess.get() || (code == 0 && rpk != null && !sawError.get());
            out.put("ok", ok);
            out.put("ms", lastBuildMs);
            out.put("exit", code);
            out.put("rpkPath", rpk == null ? null : rpk.getAbsolutePath());
            lastRpk = ok && rpk != null ? rpk : null;
            lastError.set(ok ? null : (errLine.length() > 0 ? errLine.toString()
                    : "构建失败（退出码 " + code + "）"));
            if (ok && rpk != null) {
                try {
                    rpk = rpk.getCanonicalFile();
                } catch (IOException ignored) {
                }
            }
            return out;
        } catch (Throwable t) {
            VelaLog.e(TAG, "build " + name, t);
            out.put("ok", false);
            out.put("msg", String.valueOf(t.getMessage()));
            lastError.set(t.getMessage());
            return out;
        } finally {
            building.set(false);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "buildDone");
            ev.put("project", name);
            ev.put("ok", Boolean.TRUE.equals(out.get("ok")));
            ev.put("rpkPath", out.get("rpkPath"));
            ev.put("ms", lastBuildMs);
            // The reason a build failed (missing toolchain, compiler error) is the only
            // actionable part of the event -- "构建失败" alone leaves the user stuck.
            ev.put("msg", out.get("msg"));
            ev.put("error", lastError.get());
            channel.emit("buildDone", ev);
        }
    }

    // ------------------------------------------------------------- build+deploy

    /** Build, hand the rpk to the guest, then start it. No user interaction. */
    public Map<String, Object> buildInstallLaunch(String name, int adbPort) {
        return buildInstallLaunch(name, adbPort, null);
    }

    /** {@code imageType} 决定装机走 5.0（pm install）还是 pre-4.0（解压 + vapp）那套。 */
    public Map<String, Object> buildInstallLaunch(String name, int adbPort, String imageType) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> b = build(name);
        out.putAll(b);
        if (!Boolean.TRUE.equals(b.get("ok"))) {
            return out;
        }
        out.putAll(install(name, adbPort, (String) b.get("rpkPath"), imageType));
        return out;
    }

    public Map<String, Object> install(String name, int adbPort, String rpkPath) {
        return install(name, adbPort, rpkPath, null);
    }

    public Map<String, Object> install(String name, int adbPort, String rpkPath, String imageType) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            File dir = projects.require(name);
            File rpk = rpkPath != null ? new File(rpkPath) : lastRpk;
            if (rpk == null || !rpk.isFile()) {
                rpk = VelaProjects.newestArtifact(dir);
            }
            String pkg = manifestPackage(dir);
            if (rpk == null || !rpk.isFile()) {
                out.put("ok", false);
                out.put("msg", "没有可安装的产物，先构建");
                return out;
            }
            boolean preImage = imageType != null && imageType.startsWith("vela-pre");
            if (preImage) {
                emit("build", name, "deploy", "目标镜像 " + imageType + "：走解压 + vapp 那套");
            }
            Map<String, Object> r = VelaDeploy.installAndLaunch(rpk, pkg, adbPort,
                    VelaDeploy.freePort(), true, preImage, new VelaDeploy.Log() {
                        @Override
                        public void line(String s) {
                            emit("build", name, "deploy", s);
                        }
                    });
            out.putAll(r);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("type", "installed");
            ev.put("package", pkg);
            ev.put("ok", r.get("ok"));
            ev.put("msg", r.get("msg"));
            channel.emit("installed", ev);
            return out;
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("msg", String.valueOf(t.getMessage()));
            return out;
        }
    }

    public List<String> installedApps(int adbPort) {
        return VelaDeploy.listApps(adbPort, new VelaDeploy.Log() {
            @Override
            public void line(String s) {
                VelaLog.i(TAG, s);
            }
        });
    }

    // ------------------------------------------------------------------- watch

    /** {@code name == null} stops watching. */
    public boolean watch(String name) {
        if (watcher != null) {
            try {
                watcher.stopWatching();
            } catch (Throwable ignored) {
            }
            watcher = null;
        }
        watchName = null;
        if (name == null) {
            return false;
        }
        try {
            final File src = new File(projects.require(name), "src");
            if (!src.isDirectory()) {
                return false;
            }
            watcher = new Watcher(src.getAbsolutePath());
            watcher.startWatching();
            watchName = name;
            // A poll sweep covers the FileObserver gaps on FUSE-backed trees.
            sweep = new Thread(new Runnable() {
                @Override
                public void run() {
                    long last = 0;
                    while (watcher != null) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        long m = newestMtime(src);
                        if (m > last) {
                            last = m;
                            onDirty(name);
                        }
                    }
                }
            }, "vela-watch-sweep");
            sweep.setDaemon(true);
            sweep.start();
            return true;
        } catch (Throwable t) {
            VelaLog.e(TAG, "watch " + name, t);
            return false;
        }
    }

    private Thread sweep;
    private final AtomicBoolean rebuildQueued = new AtomicBoolean(false);
    private volatile long lastWatchFire = 0;

    private void onDirty(String name) {
        long now = System.currentTimeMillis();
        // 300 ms debounce, and never two builds at once
        if (now - lastWatchFire < 300 || building.get()) {
            if (!rebuildQueued.getAndSet(true)) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(600);
                        } catch (InterruptedException ignored) {
                        }
                        rebuildQueued.set(false);
                        if (watchName != null) {
                            onDirty(watchName);
                        }
                    }
                }, "vela-watch-queue").start();
            }
            return;
        }
        lastWatchFire = now;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "watch");
        ev.put("project", name);
        ev.put("file", "src");
        channel.emit("watch", ev);
        buildInstallLaunch(name, currentAdbPort());
    }

    private static long newestMtime(File dir) {
        long best = 0;
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                best = Math.max(best, newestMtime(f));
            } else {
                best = Math.max(best, f.lastModified());
            }
        }
        return best;
    }

    /**
     * The guest's adb transport is the odd port of the emulator's pair (5555 for the
     * first instance, 5557 for the second, ...). Probe instead of assuming, because
     * nothing records it once several instances can coexist.
     */
    private int currentAdbPort() {
        for (int port : new int[]{5555, 5557, 5559, 5561}) {
            if (VelaAdb.probe(port, 700)) {
                return port;
            }
        }
        return 5555;
    }

    // ----------------------------------------------------------------- helpers

    private interface Line {
        void on(String line, boolean stderr);
    }

    private static int runTool(List<String> argv, File cwd, Map<String, String> env, Line sink) {
        // The toolchain lives under files/vela, and SELinux forbids our own domain
        // (untrusted_app) from exec'ing app_data_file, so a plain ProcessBuilder
        // here dies with "Cannot run program .../node: error=13, Permission denied".
        // Everything goes through VelaToolchain.exec, which runs the loader from jniLibs
        // run-as (shell -> runas_app) the same way the engine does.
        return VelaToolchain.exec(argv, cwd, env, new VelaToolchain.LineSink() {
            @Override
            public void onLine(String line, boolean stderr) {
                sink.on(line, stderr);
            }
        }, 600_000);
    }

    /** The toolkit colour-codes its output; strip escapes so markers stay matchable. */
    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*m", "").trim();
    }

    private static String readFile(File f) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private String manifestPackage(File projectDir) {
        try {
            String json = readFile(new File(projectDir, "src/manifest.json"));
            int i = json.indexOf("\"package\"");
            if (i < 0) {
                return projectDir.getName();
            }
            int q1 = json.indexOf('"', json.indexOf(':', i) + 1);
            int q2 = json.indexOf('"', q1 + 1);
            return json.substring(q1 + 1, q2);
        } catch (Throwable t) {
            return projectDir.getName();
        }
    }

    public Map<String, Object> devStatus(int adbPort) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("watching", watchName != null);
        m.put("project", watchName);
        m.put("nodeAvailable", toolchain.isNodeAvailable());
        m.put("toolkitInstalled", toolchain.isToolkitInstalled(projects.rootDir()));
        m.put("lastBuildMs", lastBuildMs);
        m.put("lastError", lastError.get());
        m.put("adbPort", adbPort);
        m.put("building", building.get());
        // Drives the "grant all-files access" hint next to the public edit
        // surface on the projects page.
        m.put("allFiles", VelaChannel.allFilesGranted());
        m.put("publicDir", VelaProjects.publicRoot().getAbsolutePath());
        return m;
    }

    private void emit(String kind, String project, String phase, String line) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", kind);
        ev.put("project", project);
        ev.put("phase", phase);
        ev.put("line", line);
        channel.emit(kind, ev);
    }

    /** FileObserver wrapper kept separate so the deprecated-constructor noise is local. */
    private static final class Watcher extends FileObserver {
        Watcher(String path) {
            super(path, ALL_EVENTS);
        }

        @Override
        public void onEvent(int event, String path) {
            // The sweep thread is the authority; this only exists to catch bursts.
        }
    }

    static List<String> emptyArgs() {
        return new ArrayList<>();
    }
}
