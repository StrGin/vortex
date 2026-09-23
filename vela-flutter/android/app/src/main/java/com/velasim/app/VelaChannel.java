package com.velasim.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * The Dart <-> Java surface.
 *
 * <pre>
 *   MethodChannel  velasim/control
 *     listDevices  listImages  downloadImage  deleteImage
 *     listAvds  createAvd  deleteAvd
 *     startEngine  stopEngine  engineStatus  systemAccent
 *     getPaths  getLogs  getSerialLog  clearLogs  openSettings

 *     toolchainStatus  installToolchain
 *     listProjects  createProject  deleteProject  projectFiles
 *     readProjectFile  writeProjectFile  buildProject  watchProject  devStatus
 *     installRpk  importRpkFromStorage  launchApp  stopApp  uninstallApp
 *     listInstalledApps
 *   EventChannel   velasim/events   (JSON-friendly maps)
 *     {type:status, running, avd, pid, grpcPort}
 *     {type:log, line}
 *     {type:download, imageType, done, total, phase}   (emit() renames the payload's own "type")
 *     {type:devices} | {type:images} | {type:toolchain}
 *     {type:build, ...} | {type:buildDone, ok, msg} | {type:installed} | {type:watch}
 * </pre>
 *
 * <p>Heavy calls (extraction, AVD authoring, engine start, downloads) run on a
 * worker thread and reply on the platform thread, so the UI never blocks. The
 * engine's own logs stream into a bounded ring that {@code getLogs} reads and
 * the {@code log} event mirrors.
 */
public final class VelaChannel {

    public static final String CONTROL = "velasim/control";
    public static final String EVENTS = "velasim/events";

    private static final String TAG = "channel";
    private static final int RING_MAX = 2000;

    private static volatile VelaChannel instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> ring = new ArrayDeque<>(RING_MAX);
    private final AtomicBoolean statusMonitorOn = new AtomicBoolean(false);

    private volatile VelaEngine engine;
    private volatile VelaImageStore images;
    private volatile VelaDevices devices;
    private volatile EventChannel.EventSink sink;

    // ------------------------------------------------------------------ init

    public static VelaChannel attach(Context ctx) {
        VelaChannel c = instance;
        if (c == null) {
            synchronized (VelaChannel.class) {
                c = instance;
                if (c == null) {
                    c = new VelaChannel(ctx.getApplicationContext());
                    instance = c;
                }
            }
        }
        return c;
    }

    /** Null until {@link #attach} has run once. */
    public static VelaChannel get() {
        return instance;
    }

    private VelaChannel(Context ctx) {
        this.ctx = ctx;
        // 启动自检：确认应用域能执行 jniLibs 里的 loader（结果进 logcat，tag vela-native）。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VelaNative.probe(VelaChannel.this.ctx);
                } catch (Throwable t) {
                    VelaLog.w(VelaNative.PROBE_TAG, "probe crashed", t);
                }
                // 改名遗留：/sdcard/SimpSimVela → /sdcard/Vortex（幂等，失败只记日志）。
                VelaProjects.migratePublicRootIfNeeded();
            }
        }, "vela-native-probe").start();
    }

    /** 界面偏好（引导是否看过之类），与引擎偏好共用同一个 prefs 文件。 */
    private android.content.SharedPreferences uiPrefs() {
        return ctx.getSharedPreferences("velasim_engine", Context.MODE_PRIVATE);
    }

    /** 可执行负载状态：loader 与 exec stub 是否都在 jniLibs 里。 */
    public Map<String, Object> nativeStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nativeDir", VelaNative.nativeDir(ctx).getAbsolutePath());
        m.put("loaderReady", VelaNative.ready(ctx, VelaNative.LD));
        m.put("stubReady", VelaNative.ready(ctx, VelaNative.STUB));
        return m;
    }

    /** Emits the runtime-payload state to Dart. */
    public void pushNative() {
        emit("native", nativeStatus());
    }

    /** Pushes node/toolkit availability (after a toolchain install, say). */
    private void pushToolchainState() {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            p.putAll(toolchain().status(projects().rootDir()));
        } catch (Throwable t) {
            p.put("error", describe(t));
        }
        emit("toolchain", p);
    }

    private VelaEngine engine() {
        VelaEngine e = engine;
        if (e == null) {
            synchronized (this) {
                e = engine;
                if (e == null) {
                    e = new VelaEngine(ctx);
                    engine = e;
                }
            }
        }
        return e;
    }

    private VelaDevices devices() throws Exception {
        VelaDevices d = devices;
        if (d == null) {
            d = VelaDevices.load(ctx);
            devices = d;
        }
        return d;
    }

    private volatile VelaProjects projects;
    private volatile VelaToolchain toolchain;
    private volatile VelaDevServer devServer;

    private VelaProjects projects() {
        VelaProjects p = projects;
        if (p == null) {
            synchronized (this) {
                p = projects;
                if (p == null) {
                    p = new VelaProjects(ctx);
                    projects = p;
                }
            }
        }
        return p;
    }

    private VelaToolchain toolchain() {
        VelaToolchain t = toolchain;
        if (t == null) {
            synchronized (this) {
                t = toolchain;
                if (t == null) {
                    t = new VelaToolchain(ctx, projects());
                    toolchain = t;
                }
            }
        }
        return t;
    }

    private static int intOrZero(Object args, String key) {
        Integer v = intOrNull(args, key);
        return v == null ? 0 : v;
    }

    private static void copyFile(File from, File to) throws IOException {
        java.io.InputStream in = new java.io.FileInputStream(from);
        java.io.OutputStream out = new java.io.FileOutputStream(to);
        try {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            VelaUtil.closeQuietly(in);
            VelaUtil.closeQuietly(out);
        }
    }

    /** Guest adb transport: the odd port of the emulator's pair, probed. */
    private int adbPort() {
        for (int port : new int[]{5555, 5557, 5559, 5561}) {
            if (VelaAdb.probe(port, 700)) {
                return port;
            }
        }
        return 5555;
    }

    private VelaDevServer dev() {
        VelaDevServer d = devServer;
        if (d == null) {
            synchronized (this) {
                d = devServer;
                if (d == null) {
                    d = new VelaDevServer(ctx, projects(), toolchain(), this);
                    devServer = d;
                }
            }
        }
        return d;
    }

    private VelaImageStore images() throws Exception {
        VelaImageStore s = images;
        if (s == null) {
            synchronized (this) {
                s = images;
                if (s == null) {
                    s = new VelaImageStore(ctx, devices());
                    images = s;
                }
            }
        }
        return s;
    }

    // -------------------------------------------------------------- plumbing

    /** Registers both channels on {@code messenger}. Idempotent per engine. */
    public void register(BinaryMessenger messenger) {
        MethodChannel control = new MethodChannel(messenger, CONTROL);
        control.setMethodCallHandler(new MethodChannel.MethodCallHandler() {
            @Override
            public void onMethodCall(MethodCall call, MethodChannel.Result result) {
                dispatch(call, result);
            }
        });
        EventChannel events = new EventChannel(messenger, EVENTS);
        events.setStreamHandler(new EventChannel.StreamHandler() {
            @Override
            public void onListen(Object arguments, EventChannel.EventSink sink) {
                VelaChannel.this.sink = sink;
                startStatusMonitor();
                pushStatus();
                pushDevices();
                pushImages();
                pushNative();
            }

            @Override
            public void onCancel(Object arguments) {
                VelaChannel.this.sink = null;
                statusMonitorOn.set(false);
            }
        });
        VelaLog.i(TAG, "registered " + CONTROL + " + " + EVENTS);
    }

    public void detach() {
        this.sink = null;
        statusMonitorOn.set(false);
    }

    // ------------------------------------------------------------- dispatch

    private void dispatch(final MethodCall call, final MethodChannel.Result result) {
        final String method = call.method;
        final Object args = call.arguments;
        VelaLog.d(TAG, "-> " + method + " " + summarize(args));
        // Only the intent hop stays on the platform thread; even getPaths walks
        // the tree and engineStatus connects to 127.0.0.1, so both go to a worker.
        if ("openSettings".equals(method)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        Object out = openAppSettings();
                        result.success(out);
                    } catch (Throwable t) {
                        fail(result, method, t);
                    }
                }
            });
            return;
        }
        Worker w = workerFor(method);
        if (w == null) {
            result.notImplemented();
            return;
        }
        final Worker fw = w;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Object out = fw.run(method, args);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            result.success(out);
                        }
                    });
                } catch (final Throwable t) {
                    VelaLog.e(TAG, method + " failed", t);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            fail(result, method, t);
                        }
                    });
                }
            }
        }, "vela-" + method).start();
    }

    private interface Worker {
        Object run(String method, Object args) throws Exception;
    }

    private Worker workerFor(final String method) {
        switch (method) {
            case "getPaths":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return paths();
                    }
                };
            case "engineStatus":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return statusWithPaths();
                    }
                };
            case "clearLogs":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return clearLogs();
                    }
                };
            case "systemAccent":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return systemAccent();
                    }
                };
            case "listDevices":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return deviceList();
                    }
                };
            case "listImages":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return images().list();
                    }
                };
            case "downloadImage":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return startDownload(args);
                    }
                };
            case "deleteImage":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        String type = str(args, "type");
                        boolean ok = images().remove(type);
                        pushImages();
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("type", type);
                        r.put("deleted", ok);
                        return r;
                    }
                };
            case "listAvds":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return VelaAvd.listMaps(ctx);
                    }
                };
            case "createAvd":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return createAvd(str(args, "avdId"));
                    }
                };
            case "deleteAvd":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        String avdId = str(args, "avdId");
                        VelaEngine e = engine();
                        Map<String, Object> st = e.status();
                        if (Boolean.TRUE.equals(st.get("running")) && avdId.equals(st.get("avd"))) {
                            e.stop();
                        }
                        boolean ok = VelaAvd.delete(ctx, avdId);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("avdId", avdId);
                        r.put("deleted", ok);
                        pushDevices();
                        return r;
                    }
                };
            case "startEngine":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return startEngine(args);
                    }
                };
            case "stopEngine":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        boolean stopped = engine().stop();
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("stopped", stopped);
                        r.put("status", engine().status());
                        pushStatus();
                        return r;
                    }
                };
            case "getLogs":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return VelaLog.tail(intArg(args, "limit", 400));
                    }
                };
            case "getSerialLog":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        int limit = intArg(args, "limit", 400);
                        String text = engine().tail(limit);
                        List<String> out = new ArrayList<>();
                        for (String line : text.split("\r?\n")) {
                            if (!line.isEmpty()) {
                                out.add(line);
                            }
                        }
                        return out;
                    }
                };
            // ------------------------------------------------ 工程 / 构建 / 安装
            case "listProjects":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return projects().list();
                    }
                };
            case "projectsImportable":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return projects().importable();
                    }
                };
            case "importProject":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return projects().importFromPublic(str(args, "name"));
                    }
                };
            case "createProject":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        Map<String, Object> r = new LinkedHashMap<>();
                        String name = str(args, "name");
                        File dir = projects().create(name, str(args, "package"),
                                str(args, "deviceType"), str(args, "minPlatformVersion"));
                        r.put("name", name);
                        r.put("path", dir.getAbsolutePath());
                        return r;
                    }
                };
            case "deleteProject":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return projects().delete(str(args, "name"));
                    }
                };
            case "projectFiles":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return projects().files(str(args, "name"));
                    }
                };
            case "readProjectFile":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return projects().read(str(args, "name"), str(args, "path"));
                    }
                };
            case "writeProjectFile":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return projects().write(str(args, "name"), str(args, "path"),
                                str(args, "content"));
                    }
                };
            case "buildProject":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return dev().build(str(args, "name"));
                    }
                };
            case "installRpk":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        String name = str(args, "name");
                        String rpk = str(args, "rpkPath");
                        int port = intOrZero(args, "adbPort");
                        if (port <= 0) {
                            port = adbPort();
                        }
                        if (name == null || name.isEmpty()) {
                            Map<String, Object> r = new LinkedHashMap<>();
                            r.put("ok", false);
                            r.put("msg", "缺少工程名");
                            return r;
                        }
                        // 4.0 之前的镜像（手环那类）装机/启动是另一套，必须先分辨。
                        String image = resolveImageType(str(args, "imageType"));
                        if (boolOr(args, "launch", true)) {
                            return dev().buildInstallLaunch(name, port, image);
                        }
                        return dev().install(name, port, rpk, image);
                    }
                };
            case "launchApp":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return VelaDeploy.launch(str(args, "package"), adbPort(),
                                isPreImage(str(args, "imageType")));
                    }
                };
            case "stopApp":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return VelaDeploy.stopApp(str(args, "package"), adbPort(),
                                isPreImage(str(args, "imageType")));
                    }
                };
            case "uninstallApp":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return VelaDeploy.uninstall(str(args, "package"), adbPort());
                    }
                };
            case "listInstalledApps":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return dev().installedApps(adbPort());
                    }
                };
            case "watchProject":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return dev().watch(str(args, "name"));
                    }
                };
            case "devStatus":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return dev().devStatus(adbPort());
                    }
                };
            case "importRpkFromStorage":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        String src = str(args, "srcPath");
                        String name = str(args, "name");
                        File from = new File(src);
                        if (!from.isFile()) {
                            throw new IOException("文件不存在: " + src);
                        }
                        File dir = projects().create(name == null || name.isEmpty()
                                ? from.getName().replace(".rpk", "") : name,
                                name == null ? from.getName().replace(".rpk", "") : name,
                                "watch", null);
                        File to = new File(dir, "imported.rpk");
                        copyFile(from, to);
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("ok", true);
                        r.put("rpkPath", to.getAbsolutePath());
                        r.put("name", dir.getName());
                        return r;
                    }
                };
            case "installToolchain":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        // The full installer, not just the tar: it short-circuits when the
                        // toolkit is already there and falls back to npm when the asset is
                        // missing, reporting why in both failure cases.
                        Map<String, Object> r = toolchain().installToolkit(null,
                                new VelaToolchain.Listener() {
                                    @Override
                                    public void onProgress(String phase, int percent, String detail) {
                                        Map<String, Object> ev = new LinkedHashMap<>();
                                        ev.put("type", "toolchain");
                                        ev.put("phase", phase);
                                        ev.put("percent", percent);
                                        ev.put("detail", detail);
                                        if ("error".equals(phase)) {
                                            // ToolchainStatus.error is what the cards render;
                                            // without this a failed install stays silent.
                                            ev.put("error", detail);
                                        }
                                        ev.put("nodeAvailable", toolchain().isNodeAvailable());
                                        ev.put("toolkitInstalled",
                                                toolchain().isToolkitInstalled(projects().rootDir()));
                                        ev.put("execMode", VelaToolchain.execMode());
                                        emit("toolchain", ev);
                                    }
                                });
                        r.put("status", toolchain().status(projects().rootDir()));
                        return r;
                    }
                };
            case "uiPrefs":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("onboardingDone", uiPrefs().getBoolean("onboarding_done", false));
                        for (String k : new String[]{"glass_all", "glass_nav",
                                "glass_header", "glass_cards", "stream_frames"}) {
                            r.put(k, uiPrefs().getString(k, "0"));
                        }
                        return r;
                    }
                };
            case "setUiPref":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        String key = str(args, "key");
                        Object value = strOrObj(args, "value");
                        if (key == null || key.isEmpty()) {
                            return false;
                        }
                        uiPrefs().edit().putString(key, value == null ? "" : String.valueOf(value)).apply();
                        return true;
                    }
                };
            case "listLocalZips":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        // images() 会抛受检异常，这里兜住：扫不出来就是空列表。
                        try {
                            return images().localZips();
                        } catch (Exception e) {
                            VelaLog.w("image", "listLocalZips: " + e);
                            return new ArrayList<Map<String, Object>>();
                        }
                    }
                };
            case "importImage":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        final String path = str(args, "path");
                        VelaImageStore store;
                        try {
                            store = images();
                        } catch (Exception e) {
                            Map<String, Object> err = new LinkedHashMap<>();
                            err.put("ok", false);
                            err.put("error", String.valueOf(e));
                            return err;
                        }
                        Map<String, Object> r = store.importZip(path,
                                new VelaImageStore.Listener() {
                                    @Override
                                    public void onProgress(String type, long done, long total, String phase) {
                                        pushImageProgress(type, done, total, phase);
                                    }

                                    @Override
                                    public void onDone(String type, java.io.File dir, long bytes) {
                                        pushImageProgress(type, bytes, bytes, "done");
                                    }

                                    @Override
                                    public void onError(String type, String message, Throwable err) {
                                        Map<String, Object> ev = new LinkedHashMap<>();
                                        ev.put("type", type);
                                        ev.put("phase", "error");
                                        ev.put("error", message);
                                        emit("download", ev);
                                        pushImages();
                                    }
                                });
                        pushImages();
                        return r;
                    }
                };
            case "nativeStatus":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return nativeStatus();
                    }
                };
            case "toolchainStatus":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        Map<String, Object> r = new LinkedHashMap<>(
                                toolchain().status(projects().rootDir()));
                        r.put("allFiles", allFilesGranted());
                        r.put("publicDir", VelaProjects.publicRoot().getAbsolutePath());
                        return r;
                    }
                };
            default:
                return null;
        }
    }

    /** 参数里没带就用运行中的 AVD config.ini 的 ide.image.type 兜底。 */
    private String resolveImageType(String fromArgs) {
        if (fromArgs != null && !fromArgs.isEmpty()) {
            return fromArgs;
        }
        return VelaAvd.imageType(ctx, str(engine().status(), "avd"));
    }

    /** 4.0 之前那套镜像（vela-pre-*）：装机/启动流程与 5.0 不同。 */
    private static boolean isPreImage(String imageType) {
        return imageType != null && imageType.startsWith("vela-pre");
    }

    private void fail(MethodChannel.Result result, String method, Throwable t) {
        result.error(method, describe(t), rootCauseOf(t));
    }

    // ------------------------------------------------------- quick handlers

    /** Empties the in-memory ring and deletes finished engine logs. */
    private Map<String, Object> clearLogs() {
        VelaLog.clear();
        clearLogRing();
        File logs = logDir();
        int removed = 0;
        File[] kids = logs.listFiles();
        if (kids != null) {
            File keep = engine().currentLogFile();
            for (File k : kids) {
                if (k.isFile() && k.getName().startsWith("engine-") && !k.equals(keep) && k.delete()) {
                    removed++;
                }
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cleared", true);
        r.put("filesRemoved", removed);
        r.put("dir", logs.getAbsolutePath());
        return r;
    }

    private File logDir() {
        File cur = engine().currentLogFile();
        return cur == null ? VelaPaths.runtimeDir(ctx) : cur.getParentFile();
    }

    /**
     * Android 12+ wallpaper accent, resolved through the hidden
     * {@code android.R.color.system_accent1_500} entry. Returns null on older releases
     * or when the resource is absent, which just leaves the Flutter fallback seed.
     */
    private Map<String, Object> systemAccent() {
        if (android.os.Build.VERSION.SDK_INT < 31) return null;
        try {
            int id = android.R.color.class.getField("system_accent1_500").getInt(null);
            int color = ctx.getColor(id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("r", (color >> 16) & 0xFF);
            m.put("g", (color >> 8) & 0xFF);
            m.put("b", color & 0xFF);
            return m;
        } catch (Throwable t) {
            VelaLog.w(TAG, "system accent unavailable: " + t);
            return null;
        }
    }

    private Map<String, Object> statusWithPaths() {
        Map<String, Object> st = new LinkedHashMap<>(engine().status());
        st.put("paths", paths());
        st.put("busy", false);
        st.put("downloading", downloadingType());
        return st;
    }

    /** Type of the image transfer in flight, from the already-built store. */
    private String downloadingType() {
        VelaImageStore s = images;
        return s == null ? null : s.downloadingType();
    }

    private Map<String, Object> paths() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package", ctx.getPackageName());
        m.put("files", ctx.getFilesDir().getAbsolutePath());
        m.put("cache", ctx.getCacheDir().getAbsolutePath());
        File ext = VelaPaths.externalDirOf(ctx);
        m.put("externalFiles", ext == null ? null : ext.getAbsolutePath());
        m.put("engine", VelaPaths.engineDir(ctx).getAbsolutePath());
        m.put("engineOverride", VelaPaths.overrideDir(ctx).getAbsolutePath());
        m.put("velaHome", VelaPaths.velaHome(ctx).getAbsolutePath());
        m.put("sdk", VelaPaths.velaSdkDir(ctx).getAbsolutePath());
        m.put("systemImages", new File(VelaPaths.velaSdkDir(ctx), "system-images").getAbsolutePath());
        m.put("skins", new File(VelaPaths.velaSdkDir(ctx), "skins").getAbsolutePath());
        m.put("avdHome", VelaPaths.velaVvdDir(ctx).getAbsolutePath());
        m.put("logs", logDir().getAbsolutePath());
        m.put("engineLog", engine().currentLogFile() == null ? null
                : engine().currentLogFile().getAbsolutePath());
        m.put("extracted", new File(VelaPaths.engineDir(ctx), "asset-extract.ok").isFile());
        m.put("assetStamp", VelaUtil.assetVersionStamp(ctx));
        m.put("engineBytes", VelaImageStore.dirSize(VelaPaths.engineDir(ctx)));
        m.put("homeIsExternal", VelaPaths.homeIsExternal(ctx));
        m.put("freeBytes", VelaUtil.freeBytes(VelaPaths.runtimeDir(ctx)));
        m.put("totalBytes", VelaUtil.totalBytes(VelaPaths.runtimeDir(ctx)));
        m.put("externalFreeBytes", ext == null ? 0L : VelaUtil.freeBytes(ext));
        m.put("imagesBytes", imagesSize());
        m.put("hostAbi", VelaPaths.hostAbi(ctx));
        m.put("guestAbi", VelaPaths.guestAbi());
        m.put("is64Bit", VelaUtil.is64BitDevice());
        m.put("host", safe(VelaUtil::hostInfo));
        m.put("kernel", safe(VelaUtil::osReleaseVersion));
        m.put("versionName", versionName());
        return m;
    }

    private interface Supplied {
        String get() throws Exception;
    }

    private static String safe(Supplied s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    private long imagesSize() {
        try {
            return images().installedBytes();
        } catch (Exception e) {
            return 0L;
        }
    }

    private String versionName() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private Map<String, Object> openAppSettings() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opened", false);
        r.put("allFiles", allFilesGranted());
        try {
            // The public edit surface (/sdcard/Vortex/projects) needs
            // "All files access" on Android 11+; jump straight at that page when
            // it is still missing, otherwise to the app details page.
            Intent i;
            if (!allFilesGranted()) {
                i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + ctx.getPackageName()));
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + ctx.getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            r.put("opened", true);
            r.put("target", allFilesGranted() ? "application-details" : "all-files-access");
        } catch (Throwable t) {
            r.put("error", describe(t));
            r.put("hint", "open Settings -> Apps -> " + versionName() + " manually");
        }
        return r;
    }

    /** {@code MANAGE_EXTERNAL_STORAGE} state; true on releases without scoped storage. */
    static boolean allFilesGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------- workflows

    private List<Map<String, Object>> deviceList() throws Exception {
        VelaDevices d = devices();
        String sdk = VelaPaths.velaSdkDir(ctx).getAbsolutePath();
        String avdHome = VelaPaths.velaVvdDir(ctx).getAbsolutePath();
        List<Map<String, Object>> out = new ArrayList<>();
        for (VelaDevices.VelaDevice dev : d.all()) {
            Map<String, Object> m = dev.toMap(sdk, avdHome);
            m.put("avdExists", VelaAvd.directoryFor(ctx, dev.avdId).isDirectory());
            m.put("imageInstalled", new File(new File(m.get("imageDir").toString()), "nuttx").isFile());
            out.add(m);
        }
        return out;
    }

    private Object createAvd(String avdIdRaw) throws Exception {
        VelaDevices d = devices();
        VelaDevices.VelaDevice dev = d.byId(avdIdRaw);
        if (dev == null) {
            throw new IllegalArgumentException("unknown avdId: " + avdIdRaw);
        }
        File dir = VelaAvd.create(ctx, dev);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("avdId", dev.avdId);
        r.put("path", dir.getAbsolutePath());
        r.put("config", VelaAvd.readIni(new File(dir, VelaAvd.CONFIG_INI)));
        r.put("problems", VelaAvd.validate(ctx, dev.avdId));
        pushDevices();
        return r;
    }

    private Object startEngine(Object args) throws Exception {
        String avdId = str(args, "avdId");
        if (avdId == null || avdId.isEmpty()) {
            throw new IllegalArgumentException("startEngine needs {avdId}");
        }
        VelaEngine.Options o = new VelaEngine.Options();
        Integer grpc = intOrNull(args, "grpcPort");
        Integer debug = intOrNull(args, "debugPort");
        o.grpcPort = grpc;
        o.debugPort = debug;
        Boolean verbose = boolOrNull(args, "verbose");
        o.verbose = verbose != null && verbose;
        o.noWindow = boolOr(args, "noWindow", true);
        o.noAudio = boolOr(args, "noAudio", false);
        o.readOnly = boolOr(args, "readOnly", false);
        o.extraArgs = str(args, "extraArgs");
        long timeout = longOr(args, "timeoutMs", 90_000);

        VelaEngine e = engine();
        VelaEngine.LaunchResult res = e.start(avdId, o, timeout);
        Map<String, Object> out = new LinkedHashMap<>(res.toMap());
        if (!res.running || res.error != null) {
            // A launch that failed on a half-created AVD (image not downloaded, skin
            // missing) is best explained by the AVD's own validation problems.
            List<String> avdProblems = VelaAvd.validate(ctx, avdId);
            if (!avdProblems.isEmpty()) {
                out.put("avdProblems", avdProblems);
            }
        }
        out.put("status", e.status());
        pushStatus();
        return out;
    }

    private Object startDownload(Object args) throws Exception {
        String type = str(args, "type");
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("downloadImage needs {type}");
        }
        final VelaImageStore store = images();
        String url = str(args, "url");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type);
        r.put("url", url == null || url.isEmpty() ? devices().imageUrl(type) : url);
        r.put("started", true);
        r.put("dir", store.imageDir(type).getAbsolutePath());
        store.download(type, url, new VelaImageStore.Listener() {
            @Override
            public void onProgress(String t, long done, long total, String phase) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("type", t);
                ev.put("done", done);
                ev.put("total", total);
                ev.put("phase", phase);
                emit("download", ev);
            }

            @Override
            public void onDone(String t, File dir, long bytes) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("type", t);
                ev.put("done", bytes);
                ev.put("total", bytes);
                ev.put("phase", "done");
                ev.put("dir", dir.getAbsolutePath());
                emit("download", ev);
                pushImages();
            }

            @Override
            public void onError(String t, String message, Throwable err) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("type", t);
                ev.put("phase", "error");
                ev.put("error", message);
                emit("download", ev);
                pushImages();
            }
        });
        return r;
    }

    // ---------------------------------------------------------------- events

    /** Emits {@code {type: kind, ...payload}} to Dart. */
    public void emit(String kind, Map<String, Object> payload) {
        final EventChannel.EventSink s = sink;
        if (s == null) {
            return;
        }
        final Map<String, Object> ev = new LinkedHashMap<>();
        if (payload != null) {
            ev.putAll(payload);
        }
        // The discriminator always wins: a download payload carries its own
        // "type" (the image type), which is re-exposed as "imageType".
        Object payloadType = ev.remove("type");
        ev.put("type", kind);
        if (payloadType != null && !kind.equals(payloadType)) {
            ev.put("imageType", payloadType);
        }
        Runnable send = new Runnable() {
            @Override
            public void run() {
                try {
                    s.success(ev);
                } catch (Throwable t) {
                    VelaLog.d(TAG, "event send failed: " + t);
                    sink = null;
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            send.run();
        } else {
            main.post(send);
        }
    }

    public void pushStatus() {
        Map<String, Object> st = engine().status();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("running", st.get("running"));
        p.put("ready", st.get("ready"));
        p.put("avd", st.get("avd"));
        p.put("pid", st.get("pid"));
        p.put("grpcPort", st.get("grpcPort"));
        p.put("debugPort", st.get("debugPort"));
        p.put("mode", st.get("mode"));
        p.put("error", st.get("error"));
        emit("status", p);
    }

    public void pushDevices() {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            p.put("devices", deviceList());
        } catch (Exception e) {
            p.put("error", describe(e));
        }
        emit("devices", p);
    }

    /** 镜像下载/导入的进度事件：Dart 侧按 type/phase 统一处理（导入复用下载的 UI）。 */
    private void pushImageProgress(String type, long done, long total, String phase) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", type);
        ev.put("done", done);
        ev.put("total", total);
        ev.put("phase", phase);
        emit("download", ev);
        if ("done".equals(phase) || "error".equals(phase)) {
            pushImages();
        }
    }

    public void pushImages() {
        Map<String, Object> p = new LinkedHashMap<>();
        try {
            p.put("images", images().list());
        } catch (Exception e) {
            p.put("error", describe(e));
        }
        emit("images", p);
    }

    /** Periodic status push while the engine is up, so Dart can drop a poller. */
    private void startStatusMonitor() {
        if (!statusMonitorOn.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Object last = null;
                while (statusMonitorOn.get() && sink != null) {
                    Map<String, Object> st = engine().status();
                    Object sig = new StringBuilder().append(st.get("running")).append('/')
                            .append(st.get("pid")).append('/').append(st.get("ready")).toString();
                    if (!sig.equals(last)) {
                        last = sig;
                        pushStatus();
                    }
                    try {
                        TimeUnit.MILLISECONDS.sleep(Boolean.TRUE.equals(st.get("running")) ? 2000 : 5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "vela-status-monitor");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------- log ring

    /** Mirrors an engine line to Dart; safe from any thread. */
    public void logLine(String line) {
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > RING_MAX) {
                ring.pollFirst();
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("line", line);
        emit("log", p);
    }

    public List<String> logTail(int max) {
        synchronized (ring) {
            List<String> out = new ArrayList<>();
            int skip = Math.max(0, ring.size() - Math.max(0, max));
            int i = 0;
            for (Iterator<String> it = ring.iterator(); it.hasNext(); i++) {
                if (i >= skip) {
                    out.add(it.next());
                }
            }
            return out;
        }
    }

    public void clearLogRing() {
        synchronized (ring) {
            ring.clear();
        }
    }

    // ---------------------------------------------------------------- parsing

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object args) {
        if (args instanceof Map) {
            return (Map<String, Object>) args;
        }
        return null;
    }

    private static String str(Object args, String key) {
        Object v = strOrObj(args, key);
        return v == null ? null : String.valueOf(v);
    }

    private static Object strOrObj(Object args, String key) {
        Map<String, Object> m = map(args);
        if (m == null) {
            if (args instanceof String && "type".equals(key)) {
                return args;
            }
            return null;
        }
        return m.get(key);
    }

    private static Integer intOrNull(Object args, String key) {
        Object v = strOrObj(args, key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int intArg(Object args, String key, int def) {
        Integer v = intOrNull(args, key);
        return v == null ? def : v;
    }

    private static long longOr(Object args, String key, long def) {
        Object v = strOrObj(args, key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static Boolean boolOrNull(Object args, String key) {
        Object v = strOrObj(args, key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.US);
            if (s.equals("true") || s.equals("1") || s.equals("yes")) {
                return Boolean.TRUE;
            }
            if (s.equals("false") || s.equals("0") || s.equals("no")) {
                return Boolean.FALSE;
            }
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        return null;
    }

    private static boolean boolOr(Object args, String key, boolean def) {
        Boolean v = boolOrNull(args, key);
        return v == null ? def : v;
    }

    private static String summarize(Object args) {
        Map<String, Object> m = map(args);
        if (m == null) {
            return args == null ? "{}" : args.getClass().getSimpleName();
        }
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(VelaUtil.ellipsize(String.valueOf(e.getValue()), 80));
        }
        return sb.append('}').toString();
    }

    static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getName() : t.getClass().getSimpleName() + ": " + m;
    }

    private static String rootCauseOf(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c == t ? null : c.getClass().getName() + ": " + c.getMessage();
    }
}
