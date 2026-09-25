package com.velasim.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 *     listInstalledApps  openArtifact
 *
 * <p>{@code buildProject} 的 {@code task}：{@code release}（工程页「构建」，产物
 * {@code dist/<包名>.release.<版本>.rpk}）或 {@code build}（调试构建，工程页「推送」和
 * 热更新都走这条）。{@code installRpk} 带 {@code rpkPath} 就装指定的那份，
 * 不带则用最近一次构建的产物、再退到工程里最新的 rpk。</p>
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
        // 系统文件夹选择器：结果要等用户选完，MethodChannel 的 result 一直挂着。
        if ("pickProjectFolder".equals(method)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    boolean started = VelaFlutterActivity.pickFolder(new UriPick() {
                        @Override
                        public void onPicked(Uri uri) {
                            final String path = uri == null ? null : resolveTreePath(uri);
                            // inspect 会走整棵目录树，别压在主线程上。
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        File dir = path == null ? null : new File(path);
                                        final Object out =
                                                (dir == null || !dir.isDirectory())
                                                        ? null
                                                        : projects().inspect(dir);
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                result.success(out);
                                            }
                                        });
                                    } catch (Throwable t) {
                                        failLater(result, method, t);
                                    }
                                }
                            }).start();
                        }
                    });
                    if (!started) {
                        result.success(null); // 没有前台 Activity（桌面端）就当取消
                    }
                }
            });
            return;
        }
        // 选一个 zip 导入工程：拷进私有目录先看一眼，真正解包走 importPickedZip。
        // 全程用选择器给的读权限，所以不需要「所有文件访问」。
        if ("pickProjectZip".equals(method)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    boolean started = VelaFlutterActivity.pickZip(new UriPick() {
                        @Override
                        public void onPicked(Uri uri) {
                            if (uri == null) {
                                result.success(null);
                                return;
                            }
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        File tmp = new File(ctx.getCacheDir(),
                                                "import-" + System.currentTimeMillis() + ".zip");
                                        InputStream in = ctx.getContentResolver().openInputStream(uri);
                                        if (in == null) {
                                            throw new IOException("读不到选中的文件");
                                        }
                                        OutputStream os = new FileOutputStream(tmp);
                                        try {
                                            byte[] buf = new byte[128 * 1024];
                                            int n;
                                            while ((n = in.read(buf)) > 0) {
                                                os.write(buf, 0, n);
                                            }
                                        } finally {
                                            VelaUtil.closeQuietly(in);
                                            VelaUtil.closeQuietly(os);
                                        }
                                        final Object out = projects().peekZip(tmp);
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                result.success(out);
                                            }
                                        });
                                    } catch (Throwable t) {
                                        failLater(result, method, t);
                                    }
                                }
                            }).start();
                        }
                    });
                    if (!started) {
                        result.success(null);
                    }
                }
            });
            return;
        }
        // 解包 pickProjectZip 看过的那只 zip（token 就是临时文件路径）
        if ("importPickedZip".equals(method)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            File tmp = null;
                            try {
                                tmp = new File(str(args, "token"));
                                final Object out = projects().importZip(tmp);
                                main.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        result.success(out);
                                    }
                                });
                            } catch (Throwable t) {
                                failLater(result, method, t);
                            } finally {
                                if (tmp != null) {
                                    //noinspection ResultOfMethodCallIgnored
                                    tmp.delete();
                                }
                            }
                        }
                    }).start();
                }
            });
            return;
        }
        // 用其他应用打开构建产物：拷贝走 worker 线程，跳到别的应用必须走主线程。
        if ("openArtifact".equals(method)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Map<String, Object> prepared;
                            try {
                                prepared = prepareArtifact(args);
                            } catch (final Throwable t) {
                                prepared = new LinkedHashMap<>();
                                prepared.put("ok", false);
                                prepared.put("msg", describe(t));
                            }
                            final Map<String, Object> r = prepared;
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    result.success(openWithOtherApp(r));
                                }
                            });
                        }
                    }, "vela-openArtifact").start();
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
            case "wipeAvd":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return wipeAvd(str(args, "avdId"));
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
            case "importProjectAt":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) throws Exception {
                        return projects().importFrom(new File(str(args, "path")), str(args, "name"));
                    }
                };
            case "inspectProjectDir":
                return new Worker() {
                    @Override
                    public Object run(String m, Object args) {
                        return projects().inspect(new File(str(args, "path")));
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
                        // task: release（工程页「构建」默认）或 build（调试构建）
                        return dev().build(str(args, "name"), str(args, "task"));
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

    /** 后台线程里出错时用：result.error 只能在主线程调。 */
    private void failLater(final MethodChannel.Result result, final String method,
                           final Throwable t) {
        main.post(new Runnable() {
            @Override
            public void run() {
                fail(result, method, t);
            }
        });
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

    /** 文件夹/文件选择器的回调，在 main 线程上被调用；取消时 uri 为 null。 */
    public interface UriPick {
        void onPicked(Uri uri);
    }

    /** 清空某台虚拟设备的数据分区（装机记录、系统设置全没），并从镜像重新铺一份。 */
    public Map<String, Object> wipeAvd(String avdId) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (avdId == null || avdId.isEmpty()) {
            r.put("ok", false);
            r.put("error", "缺少 avdId");
            return r;
        }
        VelaEngine e = engine();
        Map<String, Object> st = e.status();
        if (Boolean.TRUE.equals(st.get("running")) && avdId.equals(st.get("avd"))) {
            e.stop();
        }
        r.put("ok", VelaAvd.wipeData(ctx, avdId));
        r.put("avdId", avdId);
        pushDevices();
        pushStatus();
        return r;
    }

    /**
     * 无界面入口：不起 UI 也能驱动引擎、装 rpk、拉起客机应用，给 adb / 脚本 / AI 用。
     * op = start | stop | status | install | launch | apps | logs | wipe。
     */
    public Map<String, Object> headless(String op, Map<String, Object> args) {
        Map<String, Object> out = new LinkedHashMap<>();
        String what = op == null || op.isEmpty() ? "status" : op;
        try {
            switch (what) {
                case "start": {
                    Map<String, Object> a = new LinkedHashMap<>(args == null ? java.util.Collections.emptyMap() : args);
                    if (str(a, "avdId") == null) {
                        List<VelaAvd.Entry> avds = VelaAvd.listAll(ctx);
                        if (avds.isEmpty()) {
                            throw new IllegalStateException("一台虚拟设备都没有");
                        }
                        a.put("avdId", avds.get(0).avdId);
                    }
                    a.putIfAbsent("noAudio", Boolean.TRUE); // 无界面跑就别出声
                    out.putAll((Map<String, Object>) startEngine(a));
                    // 引擎是 App 的子进程：让 App 以前台服务活着，否则会被系统回收。
                    VelaHeadlessService.keepAlive(ctx);
                    break;
                }
                case "stop": {
                    out.put("stopped", engine().stop());
                    pushStatus();
                    break;
                }
                case "install": {
                    String rpkPath = str(args, "rpkPath");
                    if (rpkPath == null) {
                        throw new IllegalArgumentException("install 需要 rpkPath");
                    }
                    File rpk = new File(rpkPath);
                    String pkg = str(args, "package");
                    if (pkg == null || pkg.trim().isEmpty()) {
                        pkg = packageFromRpkName(rpk);
                    }
                    String image = str(args, "imageType");
                    boolean preImage = image != null && image.startsWith("vela-pre");
                    final int port = intOrZero(args, "adbPort") > 0 ? intOrZero(args, "adbPort") : adbPort();
                    out.putAll(VelaDeploy.installAndLaunch(rpk, pkg, port, VelaDeploy.freePort(),
                            boolOr(args, "launch", true), preImage, new VelaDeploy.Log() {
                                @Override
                                public void line(String s) {
                                    VelaLog.i("headless", s);
                                }
                            }));
                    out.put("package", pkg);
                    out.put("adbPort", port);
                    pushInstalled(pkg, out.get("ok"));
                    break;
                }
                case "launch": {
                    String pkg = str(args, "package");
                    if (pkg == null || pkg.trim().isEmpty()) {
                        throw new IllegalArgumentException("launch 需要 package");
                    }
                    String image = str(args, "imageType");
                    out.putAll(VelaDeploy.launch(pkg, adbPort(),
                            image != null && image.startsWith("vela-pre")));
                    break;
                }
                case "apps": {
                    out.put("apps", VelaDeploy.listApps(adbPort(), new VelaDeploy.Log() {
                        @Override
                        public void line(String s) {
                            VelaLog.i("headless", s);
                        }
                    }));
                    break;
                }
                case "logs": {                    int lines = intArg(args, "lines", 0) > 0 ? intArg(args, "lines", 0) : 400;
                    StringBuilder sb = new StringBuilder();
                    sb.append("===== 引擎/串口日志（尾部 ").append(lines).append(" 行）=====\n");
                    sb.append(engine().tail(lines)).append('\n');
                    sb.append("\n===== App 日志 =====\n");
                    sb.append(VelaLog.tail(lines)).append('\n');
                    File dump = new File(Environment.getExternalStorageDirectory(),
                            "Vortex/headless-log.txt");
                    File parent = dump.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream os = new FileOutputStream(dump)) {
                        os.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    out.put("logFile", dump.getAbsolutePath());
                    out.put("bytes", dump.length());
                    break;
                }
                case "wipe": {
                    out.putAll(wipeAvd(str(args, "avdId")));
                    break;
                }
                case "projects": {
                    List<Map<String, Object>> list = new ArrayList<>();
                    File root = projects().rootDir();
                    File[] kids = root.listFiles();
                    if (kids != null) {
                        java.util.Arrays.sort(kids);
                        for (File k : kids) {
                            if (k.isDirectory()) {
                                list.add(projects().info(k));
                            }
                        }
                    }
                    out.put("projects", list);
                    out.put("importable", projects().importable());
                    break;
                }
                case "import": {
                    String name = str(args, "name");
                    String path = str(args, "path");
                    if (path != null && !path.isEmpty()) {
                        out.putAll(projects().importFrom(new File(path), name));
                    } else if (name != null && !name.isEmpty()) {
                        out.putAll(projects().importFromPublic(name));
                    } else {
                        throw new IllegalArgumentException("import 需要 name（编辑面里的目录名）或 path");
                    }
                    break;
                }
                case "zip": {
                    String path = str(args, "path");
                    if (path == null || path.isEmpty()) {
                        throw new IllegalArgumentException("zip 需要 path");
                    }
                    File z = new File(path);
                    out.putAll(projects().importZip(z));
                    break;
                }
                case "deploy": {
                    String name = str(args, "name");
                    if (name == null || name.isEmpty()) {
                        throw new IllegalArgumentException("deploy 需要 name（工作区里的工程名）");
                    }
                    out.putAll(dev().buildInstallLaunch(name, adbPort(), str(args, "imageType")));
                    break;
                }
                case "rm": {
                    String name = str(args, "name");
                    if (name == null || name.isEmpty()) {
                        throw new IllegalArgumentException("rm 需要 name");
                    }
                    projects().delete(name);
                    out.put("deleted", name);
                    break;
                }
                case "toolchain": {
                    // force=1：把已装的 node_modules 删掉再重装。装的时候是"存在就跳过"，
                    // 缺文件（例如 @aiot-toolkit/jsc 没落位）时只能这样补。
                    // 注意只删 node_modules：node/ 也在工具链目录里，删掉它构建会因为
                    // 找不到 node 直接 exit=-1。
                    if (boolOr(args, "force", false)) {
                        File modules = new File(VelaToolchain.toolchainDir(ctx), "node_modules");
                        out.put("removed", VelaUtil.deleteRecursive(modules));
                    }
                    out.putAll(toolchain().installToolkit(null, new VelaToolchain.Listener() {
                        @Override
                        public void onProgress(String phase, int percent, String detail) {
                            VelaLog.i("toolchain", phase + " " + percent + "% " + detail);
                        }
                    }));
                    break;
                }
                case "status":
                default:
                    break;
            }
            out.put("status", engine().status());
            out.put("ok", !Boolean.FALSE.equals(out.get("ok")));
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", describe(t));
        }
        VelaLog.i("headless", what + " -> " + out);
        return out;
    }

    /** {@code com.hyper.box.debug.1.0.0.rpk} → {@code com.hyper.box.debug}。 */
    static String packageFromRpkName(File rpk) {
        String n = rpk.getName();
        if (n.endsWith(".rpk")) {
            n = n.substring(0, n.length() - 4);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+)")
                .matcher(n);
        return m.find() ? m.group(1) : n;
    }

    private void pushInstalled(String pkg, Object ok) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("type", "installed");
        ev.put("package", pkg);
        ev.put("ok", ok);
        emit("installed", ev);
    }

    /**
     * 把 SAF 的 tree uri 还原成文件系统路径。primary 卷是 {@code primary:<相对路径>}，
     * 可移动存储是 {@code <卷号>:<相对路径>}；配合「所有文件访问」直接按路径读写。
     */
    static String resolveTreePath(Uri uri) {
        if (uri == null) {
            return null;
        }
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(uri);
        } catch (Throwable t) {
            id = uri.getLastPathSegment();
        }
        if (id == null) {
            return null;
        }
        if (id.startsWith("raw:")) {
            return id.substring(4);
        }
        int c = id.indexOf(':');
        String vol = c < 0 ? "primary" : id.substring(0, c);
        String rel = c < 0 ? "" : id.substring(c + 1);
        File base = "primary".equalsIgnoreCase(vol)
                ? Environment.getExternalStorageDirectory()
                : new File("/storage", vol);
        return new File(base, rel).getAbsolutePath();
    }

    // ------------------------------------------------- 用其他应用打开产物

    /**
     * 找工程最新的 rpk，能写公共目录就顺手导出一份到 `/sdcard/Vortex/rpk`，
     * 并算出可分享的 content URI。
     */
    private Map<String, Object> prepareArtifact(Object args) throws IOException {
        Map<String, Object> r = new LinkedHashMap<>();
        String name = str(args, "name");
        if (name == null || name.isEmpty()) {
            r.put("ok", false);
            r.put("msg", "需要 name");
            return r;
        }
        File dir = projects().require(name);
        File rpk = VelaProjects.newestArtifact(dir);
        if (rpk == null || !rpk.isFile()) {
            r.put("ok", false);
            r.put("msg", "工程里还没有 rpk 产物，先点「构建」");
            return r;
        }
        File share = rpk;
        File exported = null;
        if (allFilesGranted()) {
            // 导出到公共目录：文件管理器里也能直接看到，方便自己再传给别人。
            File pub = new File(Environment.getExternalStorageDirectory(),
                    VelaProjects.RPK_SUBDIR);
            if (pub.isDirectory() || pub.mkdirs()) {
                File dst = new File(pub, rpk.getName());
                copyFile(rpk, dst);
                exported = dst;
                share = dst;
            }
        }
        Uri uri = VelaFileProvider.uriFor(ctx, share);
        if (uri == null) {
            r.put("ok", false);
            r.put("msg", "产物在可分享目录之外：" + share.getAbsolutePath());
            return r;
        }
        r.put("ok", true);
        r.put("file", rpk.getName());
        r.put("source", rpk.getAbsolutePath());
        r.put("exported", exported == null ? null : exported.getAbsolutePath());
        r.put("uri", uri.toString());
        r.put("bytes", share.length());
        return r;
    }

    /** 交给系统「打开方式」选择器；没有应用能 VIEW 就退到分享。 */
    private Object openWithOtherApp(Map<String, Object> r) {
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            return r;
        }
        Uri uri = Uri.parse(String.valueOf(r.get("uri")));
        String mime = "application/octet-stream";
        int grant = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(grant);
            boolean canView = !ctx.getPackageManager()
                    .queryIntentActivities(view, 0).isEmpty();
            Intent target;
            if (canView) {
                target = view;
            } else {
                target = new Intent(Intent.ACTION_SEND)
                        .setType(mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(grant);
            }
            Intent chooser = Intent.createChooser(target, canView ? "用其他应用打开" : "发送 rpk 到");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | grant);
            ctx.startActivity(chooser);
            r.put("opened", true);
            r.put("via", canView ? "view" : "send");
        } catch (Throwable t) {
            r.put("ok", false);
            r.put("msg", "打不开选择器：" + describe(t)
                    + (r.get("exported") == null ? "" : "；文件已导出到 " + r.get("exported")));
        }
        return r;
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
        // 构建/装机这类长流程的输出也写一份进日志：界面上有「构建输出」面板，
        // 但无界面跑（脚本/CI）时只有日志能看到它。
        if (payload != null && ("build".equals(kind) || "buildDone".equals(kind)
                || "installed".equals(kind))) {
            Object line = payload.get("line");
            if (line == null) {
                line = payload.get("msg");
            }
            if (line != null) {
                VelaLog.i("build", String.valueOf(line));
            }
        }
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
