package com.velasim.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Owns the Vela (NuttX) emulator process.
 *
 * <p>The bundled engine is a rebuild of Xiaomi's fork of the Android emulator
 * for <em>linux-aarch64 glibc</em>, whereas the app itself is bionic. Three
 * problems follow, and this class solves all of them:
 *
 * <ol>
 *   <li><b>libc mismatch</b> -- every asset is unpacked once into a
 *       version-stamped directory (so a re-extract never fights a running
 *       {@code qemu}) and the PIE loader is invoked explicitly:
 *       <pre>glibc/lib/ld-linux-aarch64.so.1 \
 *   --library-path glibc/lib:engine/lib64:engine \
 *   engine/emulator -vela -avd &lt;id&gt; ...</pre>
 *       RPATH {@code $ORIGIN/lib64} can't see the bionic-free libs on its own.</li>
 *   <li><b>SELinux</b> -- an app domain cannot {@code execve} {@code app_process}
 *       and cannot bind {@code name_service_port} / create {@code udp_socket}
 *       (the emulator's mDNS stack aborts without them). The proven sibling
 *       answer is to ship the executables as jniLibs: the installer extracts them to
 *       {@code /data/app/<pkg>/lib/<abi>/} (label {@code apk_data_file}), which our
 *       own process may exec, while the private dir may only be read. So the loader
 *       lives in jniLibs and the engine itself stays in the private dir.</li>
 *   <li><b>PT_INTERP</b> -- the launcher execve()s qemu by name and every engine
 *       binary asks for {@code /lib/ld-linux-aarch64.so.1}, which Android does not
 *       have. {@link #installShims} moves those binaries aside and puts a shell
 *       shim in their place that re-enters the bundled loader.</li>
 * </ol>
 *
 * <p>Everything is synchronous on a caller-supplied thread except the two
 * output pumps and the pid-exit waiters, which run on daemon threads.
 */
public final class VelaEngine {

    /** Default gRPC port the emulator listens on -- cleartext HTTP/2, no auth. */
    public static final int GRPC_PORT = 8554;
    /** Emulator adb ports (console is GRPC+1... conventionally 5554/5555 pair). */
    public static final int ADB_PORT_EVEN = 5554;
    public static final int ADB_PORT_ODD = 5555;
    /** First port used for the guest debug channel (see -network-user-mode-options). */
    public static final int DEFAULT_DEBUG_PORT = 5556;

    private static final String TAG = "engine";
    private static final String PREF_KEY_EXTERNAL_HOME = "vela_external_home";
    private static final int WAIT_PORT_QUIET_RETRIES = 2;

    /** Engine launch knobs, all optional. */
    public static final class Options {
        public Integer grpcPort;
        public Integer debugPort;
        public boolean verbose;
        public boolean noWindow = true;
        public boolean headlessBinary = true;
        public boolean noAudio;
        public boolean readOnly;
        public boolean skipAdbPorts;
        public String extraArgs;
    }

    /** One launch attempt; emitted into the {@code status} event stream. */
    public static final class LaunchResult {
        public boolean running;
        public boolean ready;
        public String mode;
        public String avdId;
        public int pid = -1;
        public int grpcPort = GRPC_PORT;
        public int debugPort = DEFAULT_DEBUG_PORT;
        public String commandLine;
        public String scriptPath;
        public String logPath;
        public String error;
        public List<String> problems = new ArrayList<>();

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("running", running);
            m.put("ready", ready);
            m.put("mode", mode);
            m.put("avd", avdId);
            m.put("pid", pid);
            m.put("grpcPort", grpcPort);
            m.put("debugPort", debugPort);
            m.put("commandLine", commandLine);
            m.put("script", scriptPath);
            m.put("log", logPath);
            m.put("error", error);
            m.put("problems", problems);
            return m;
        }
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Object lifecycle = new Object();

    private volatile LaunchResult state = new LaunchResult();
    private volatile java.lang.Process fallbackProcess;
    private volatile int fallbackPid = -1;
    private volatile File currentLog;

    /**
     * 最近一次启动/接管的引擎日志。装机靠尾随它等客机的安装完成行
     * （官方实现也是读模拟器 stdout），所以没走 Context 的 VelaDeploy 要拿得到。
     */
    private static volatile File lastLog;

    public static File lastLogFile() {
        return lastLog;
    }
    private final AtomicBoolean readyLatch = new AtomicBoolean(false);
    private final CountDownLatch exitLatch = new CountDownLatch(1);
    private volatile long exitWaitDeadlineMs;

    // ------------------------------------------------------------------ init

    public VelaEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = this.ctx.getSharedPreferences("velasim_engine", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- extraction

    /**
     * Unpacks {@code assets/vela/{engine,glibc,skins}} once per app version.
     * Returns the extraction root (null when everything is already in place).
     */
    public File ensureExtracted() throws IOException {
        File marker = new File(VelaPaths.engineDir(ctx), "asset-extract.ok");
        String stamp = VelaUtil.assetVersionStamp(ctx);
        if (marker.isFile() && stamp.equals(VelaUtil.slurp(marker).trim())) {
            return null;
        }
        File root = VelaPaths.engineDir(ctx);
        root.mkdirs();
        VelaLog.i(TAG, "extracting engine assets into " + root);
        // 上一版留下的 exec 软链接与 <name>.elf 备份必须清掉：更新后它们指向旧
        // nativeLibraryDir（失效链接），或把上一版的名字/真身配错（非 headless 的
        // Qt 版盖到 headless 真身上）。清掉后由 installExecLinks 按新资产重建。
        for (String[] pair : SHIM_BINARIES) {
            File link = new File(new File(root, "engine"), pair[0]);
            if (VelaUtil.isLink(link)) {
                link.delete();
            }
            File backing = new File(new File(root, "engine"), pair[0] + ".elf");
            if (backing.isFile()) {
                backing.delete();
            }
        }
        long t0 = System.currentTimeMillis();
        for (String sub : new String[]{"engine", "glibc", "skins"}) {
            // glibc is version-sensitive and the bundle shrinks between releases
            // (e.g. the old one carried libstdc++.so.6, the new one must not --
            // it would shadow the engine's own 6.0.30 and every qemu start would
            // die with "GLIBCXX_3.4.29 not found"). Merge-extract keeps stale
            // files, so wipe that subtree first.
            if ("glibc".equals(sub)) {
                VelaUtil.deleteRecursive(new File(root, sub));
            }
            extractTree(sub, root);
        }
        if (binaryFor(root, "engine") == null) {
            throw new IOException("assets/vela/engine/emulator is missing -- rebuild the asset tree");
        }
        if (!new File(root, "glibc/lib/" + LOADER_NAME).isFile()) {
            throw new IOException("assets/vela/glibc/lib/" + LOADER_NAME + " is missing");
        }
        if (!VelaUtil.writeText(marker, stamp)) {
            VelaLog.w(TAG, "cannot write the extraction marker " + marker);
        }
        VelaLog.i(TAG, "extraction done in " + (System.currentTimeMillis() - t0) + " ms");
        return root;
    }

    private void extractTree(String assetPrefix, File destRoot) throws IOException {
        String prefix = VelaPaths.ASSET_PREFIX + "/" + assetPrefix;
        List<String> files = VelaUtil.listAssetsRecursive(ctx.getAssets(), prefix);
        int count = 0;
        long bytes = 0;
        for (String rel : files) {
            String assetPath = prefix + "/" + rel;
            File out = new File(destRoot, assetPrefix + "/" + rel);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create " + parent);
            }
            // 上一版 APK 留下的符号链接（指向旧 nativeLibraryDir）在更新后是失效链接：
            // exists() 为 false，但写它会让内核去解析那条死路径 → ENOENT。先删。
            if (VelaUtil.isLink(out)) {
                out.delete();
            }
            InputStream in = ctx.getAssets().open(assetPath);
            OutputStream os = new FileOutputStream(out);
            try {
                bytes += VelaUtil.copy(in, os);
            } finally {
                VelaUtil.closeQuietly(in);
                VelaUtil.closeQuietly(os);
            }
            // Zip entries lost their mode bits when the assets were packed on
            // Windows; the engine checks canExecute() before exec'ing itself.
            out.setExecutable(true, false);
            count++;
            if (count % 40 == 0) {
                VelaLog.i(TAG, "  extracted " + count + "/" + files.size());
            }
        }
        chmodTree(new File(destRoot, assetPrefix));
        VelaLog.i(TAG, assetPrefix + ": " + count + " files, " + VelaUtil.formatBytes(bytes));
    }

    /** Extracts an official AIoT-IDE engine zip over the asset tree, if present. */
    public File ensureOverrideApplied() throws IOException {
        File dir = VelaPaths.overrideDir(ctx);
        File zip = null;
        File[] kids = dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids);
            for (File f : kids) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".zip")) {
                    zip = f;
                    break;
                }
            }
        }
        if (zip == null) {
            return null;
        }
        File marker = new File(dir, "override-" + zip.length() + ".ok");
        if (marker.isFile()) {
            return null;
        }
        VelaLog.i(TAG, "applying engine override " + zip.getName());
        unzipOver(zip, VelaPaths.engineDir(ctx));
        try {
            marker.createNewFile();
        } catch (IOException ignored) {
        }
        return dir;
    }

    /**
     * Unpacks a Xiaomi distribution zip, dropping the {@code linux-aarch64}
     * folder name so the result matches the asset layout.
     */
    private void unzipOver(File zip, File destRoot) throws IOException {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.contains("/__MACOSX/") || name.startsWith("__MACOSX/")) {
                    continue;
                }
                if (name.endsWith("/")) {
                    new File(destRoot, name).mkdirs();
                    continue;
                }
                String rel = stripCommonDirs(name);
                File out = new File(destRoot, rel);
                if (!out.getCanonicalPath().startsWith(destRoot.getCanonicalPath() + File.separator)) {
                    VelaLog.w(TAG, "skipping escaping entry " + name);
                    continue;
                }
                out.getParentFile().mkdirs();
                OutputStream os = new FileOutputStream(out);
                try {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                } finally {
                    VelaUtil.closeQuietly(os);
                }
                out.setExecutable(true, false);
            }
        } finally {
            VelaUtil.closeQuietly(zin);
        }
        chmodTree(destRoot);
    }

    /**
     * {@code android-emulator-linux-aarch64/engine/...} -> {@code engine/...};
     * {@code skins/wear-5.0/...} -> {@code skins/...} is NOT done (already fine).
     */
    private static String stripCommonDirs(String name) {
        String n = name;
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        String[] knownRoots = {"android-emulator-linux-aarch64/", "linux-aarch64/", "emulator-linux-aarch64/"};
        for (String r : knownRoots) {
            if (n.startsWith(r)) {
                return n.substring(r.length());
            }
        }
        return n;
    }

    private static void chmodTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        VelaUtil.chmod755(f);
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    chmodTree(k);
                }
            }
        }
    }

    // ------------------------------------------------------------------ env

    /**
     * Creates the tree the engine expects under {@code HOME} and returns the
     * environment to run it with.
     */
    public Map<String, String> buildEnv(File engineDir) throws IOException {
        File home = ensureDirs(engineDir);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", home.getAbsolutePath());
        env.put("ANDROID_SDK_ROOT", VelaPaths.velaSdkDir(ctx).getAbsolutePath());
        env.put("ANDROID_EMULATOR_HOME", VelaPaths.velaSdkDir(ctx).getAbsolutePath());
        env.put("ANDROID_AVD_HOME", VelaPaths.velaVvdDir(ctx).getAbsolutePath());
        env.put("ANDROID_HOME", VelaPaths.velaSdkDir(ctx).getAbsolutePath());
        env.put("TMPDIR", new File(home, "tmp").getAbsolutePath());
        // System::getTempDir() only honors ANDROID_TMP and otherwise falls back
        // to /tmp/android-$USER, which does not exist on Android -- the cache
        // partition tempfile then fails and the launcher dereferences the NULL
        // path (make_ext4fs_from_dir: open: Bad address, then SIGSEGV).
        env.put("ANDROID_TMP", new File(home, "tmp").getAbsolutePath());
        env.put("VELA_HOME", new File(home, ".vela").getAbsolutePath());
        // The guest is arm32; the launcher picks qemu-system-armel accordingly.
        env.put("QEMU_DISABLE_SNAPSHOT", "1");
        env.put("ANDROID_EMULATOR_SKIP_ADB_CHECKS", "1");
        env.put("EMULATOR_DISABLE_GPU", "1");
        env.put("LC_ALL", "C");
        // jniLibs 里的静态 stub 靠它拼出私有目录里 qemu/bin64 的绝对路径
        // （launcher exec qemu 时会把它继承下去）。
        env.put(VelaNative.RUNTIME_ENV, VelaPaths.runtimeDir(ctx).getAbsolutePath());
        // No LD_LIBRARY_PATH on purpose: the glibc runtime is reached only through
        // the loader's --library-path, and an exported one would make every bionic
        // child of the launcher load the glibc libc.so instead of the platform one
        // and die with "bad ELF magic".
        return env;
    }

    /**
     * Lays out <code>HOME/.vela/{sdk/{emulator,system-images,skins,tools},vvd}</code>
     * and the sdk-level mirrors. Symlinks are used when the filesystem allows
     * it (every Android file we touch does), hard links next, plain copies last.
     */
    public File ensureDirs(File engineDir) throws IOException {
        File home = writableHome();
        File vela = new File(home, ".vela");
        File sdk = new File(vela, "sdk");
        File vvd = new File(vela, "vvd");
        for (File d : new File[]{home, new File(home, "tmp"), vela, sdk, vvd,
                new File(sdk, "system-images"), new File(sdk, "skins"),
                VelaPaths.avdHomeFromSdk(sdk)}) {
            if (!d.isDirectory() && !d.mkdirs() && !d.isDirectory()) {
                throw new IOException("cannot create " + d);
            }
        }
        linkInto(new File(sdk, "emulator"), new File(engineDir, "engine"));
        linkInto(new File(sdk, "skins"), new File(engineDir, "skins"));
        linkInto(new File(sdk, "tools"), new File(engineDir, "engine/bin64"));
        linkInto(new File(home, "emulator"), new File(engineDir, "engine"));
        linkInto(new File(home, "skins"), new File(engineDir, "skins"));
        return home;
    }

    /**
     * Where {@code HOME} points. Files created there must be reachable by
     * {@code run-as}, which only runs inside the app data dirs, so the
     * sdcardfs/FUSE option is opt-in and defaults to app-private storage.
     */
    public File writableHome() throws IOException {
        File ext = VelaPaths.externalRuntimeDir(ctx);
        if (ext != null && ext.isDirectory() && canWriteAndExecUnder(ext)) {
            return ext;
        }
        File in = VelaPaths.runtimeDir(ctx);
        if (!in.isDirectory() && !in.mkdirs() && !in.isDirectory()) {
            throw new IOException("cannot create runtime dir " + in);
        }
        return in;
    }

    private static boolean canWriteAndExecUnder(File dir) {
        try {
            File probe = new File(dir, ".vela-probe-" + System.currentTimeMillis());
            OutputStream os = new FileOutputStream(probe);
            os.write('x');
            os.close();
            boolean ok = probe.isFile() && probe.length() == 1;
            probe.delete();
            return ok;
        } catch (IOException e) {
            return false;
        }
    }

    /** Chooses an external dir for HOME (needs the app's external files dir). */
    public boolean preferExternalHome(boolean enable) throws IOException {
        SharedPreferences.Editor ed = prefs.edit();
        if (enable) {
            File ext = VelaPaths.externalDirOf(ctx);
            if (ext == null || !ext.isDirectory()) {
                return false;
            }
            ed.putString(PREF_KEY_EXTERNAL_HOME, ext.getAbsolutePath());
        } else {
            ed.remove(PREF_KEY_EXTERNAL_HOME);
        }
        ed.apply();
        return !enable || writableHome().equals(ext2());
    }

    private File ext2() {
        return VelaPaths.externalRuntimeDir(ctx);
    }

    /** True when {@code HOME} resolved to shared storage. */
    public boolean usingExternalHome() {
        File ext = VelaPaths.externalRuntimeDir(ctx);
        return ext != null && ext.equals(safeWritableHome());
    }

    private File safeWritableHome() {
        try {
            return writableHome();
        } catch (IOException e) {
            return VelaPaths.runtimeDir(ctx);
        }
    }

    private static void linkInto(File link, File target) {
        if (!target.exists()) {
            return;
        }
        if (link.exists()) {
            if (VelaUtil.symlinkPointsTo(link, target)) {
                return;
            }
            if (!VelaUtil.deleteLinkOrDir(link)) {
                VelaLog.w(TAG, "stale link " + link + " not removable, leaving it");
                return;
            }
        }
        if (VelaUtil.symlink(link, target)) {
            return;
        }
        if (link.getParentFile() != null) {
            link.getParentFile().mkdirs();
        }
        if (VelaUtil.hardlinkAll(target, link)) {
            return;
        }
        try {
            if (target.isDirectory()) {
                VelaUtil.copyTree(target, link);
            } else {
                VelaUtil.copyFile(target, link);
                VelaUtil.chmod755(link);
            }
        } catch (IOException e) {
            VelaLog.w(TAG, "cannot mirror " + target + " to " + link + ": " + e);
        }
    }

    // ----------------------------------------------------------- command line

    private static final String LOADER_NAME = "ld-linux-aarch64.so.1";

    /**
     * {@code glibc/lib:engine/lib64:engine:engine/lib64/gles_swiftshader} -- the
     * RPATH the binaries were linked with, plus the SwiftShader backend that
     * libOpenglRender dlopen()s by bare name.
     */
    private static String libraryPath(File engineDir) {
        return VelaNative.libPath(engineDir.getParentFile() == null ? engineDir : engineDir.getParentFile());
    }

    /** Full argv: loader, --library-path, then the emulator binary and its args. */
    public List<String> buildArgv(File engineDir, String avdId, Options opt) throws IOException {
        Options o = opt == null ? new Options() : opt;
        File bin = binaryFor(engineDir, "engine");
        if (bin == null) {
            throw new IOException("engine/emulator missing under " + engineDir);
        }
        List<String> argv = new ArrayList<>();
        if (!VelaUtil.isNativeLauncher(bin)) {
            File loader = loaderFile(engineDir);
            if (loader == null) {
                throw new IOException("glibc loader missing: " + loaderFile(engineDir));
            }
            argv.add(loader.getAbsolutePath());
            argv.add("--library-path");
            argv.add(libraryPath(engineDir));
        }
        argv.add(bin.getAbsolutePath());
        argv.addAll(engineArgs(avdId, o));
        return argv;
    }

    /**
     * 内核真正要 exec 的那个文件：jniLibs 里的 glibc loader（标签 {@code apk_data_file}，
     * 应用域允许执行）。私有目录里那份只作为兜底——它在本进程里 exec 会被 SELinux 拒绝，
     * 但在目标 SDK &lt; 29 或老系统上仍然可用。
     */
    public File loaderFile(File engineDir) {
        File nativeLoader = VelaNative.payload(ctx, VelaNative.LD);
        if (nativeLoader.isFile()) {
            return nativeLoader;
        }
        File bundled = new File(engineDir, "glibc/lib/" + LOADER_NAME);
        return bundled.isFile() ? bundled : null;
    }

    /** Same argv but the interpreter prefix resolved against {@code bin}. */
    public List<String> buildEngineArgs(File engineDir, String avdId, Options o) throws IOException {
        File bin = binaryFor(engineDir, "engine");
        if (bin == null) {
            throw new IOException("engine/emulator missing under " + engineDir);
        }
        List<String> argv = new ArrayList<>();
        argv.add(bin.getAbsolutePath());
        argv.addAll(engineArgs(avdId, o == null ? new Options() : o));
        return argv;
    }

    private static boolean hasArg(List<String> argv, String flag) {
        for (String s : argv) {
            if (flag.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 手机当前在用的 DNS。优先问 ConnectivityManager；拿不到（私有 DNS、权限、
     * 没网）就退回公共 DNS，否则客机一个域名都解析不了。
     * 末尾再挂两个公共 DNS 兜底：客机的 DNS 解析偶发丢包（实测同一个域名
     * 有时 1s 内回来、有时 30s 超时），多一路能少踩点。
     */
    private String hostDnsServers() {
        StringBuilder sb = new StringBuilder();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network net = cm == null ? null : cm.getActiveNetwork();
            android.net.LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (java.net.InetAddress addr : lp.getDnsServers()) {
                    String host = addr == null ? null : addr.getHostAddress();
                    if (host == null || host.indexOf(':') >= 0) {
                        continue; // 只喂 IPv4：客机的解析器不一定认 v6
                    }
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(host);
                }
            }
        } catch (Throwable t) {
            VelaLog.w(TAG, "读系统 DNS 失败: " + t);
        }
        for (String fallback : new String[]{"223.5.5.5", "119.29.29.29"}) {
            if (sb.indexOf(fallback) < 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(fallback);
            }
        }
        String out = sb.toString();
        VelaLog.i(TAG, "客机 DNS <- " + out);
        return out;
    }

    private List<String> engineArgs(String avdId, Options o) {
        List<String> a = new ArrayList<>();
        int grpc = o.grpcPort != null ? o.grpcPort : GRPC_PORT;
        int debug = o.debugPort != null ? o.debugPort : DEFAULT_DEBUG_PORT;

        a.add("-vela");
        a.add("-avd");
        a.add(avdId);
        a.add("-show-kernel");
        a.add("-ports");
        a.add(ADB_PORT_EVEN + "," + ADB_PORT_ODD);
        a.add("-grpc");
        a.add(String.valueOf(grpc));
        a.add("-network-user-mode-options");
        // 别加 ipv6=off：实测会让客机的请求全部超时（连二维码都拿不到），
        // 原因是镜像 IPv6 栈的 RS/NS 无人应答，关掉 v6 反而把可用路径也堵了。
        a.add("hostfwd=tcp:127.0.0.1:" + debug + "-10.0.2.15:101");
        // Android has no desktop GL, so the engine must render through the
        // bundled SwiftShader backend (lib64/gles_swiftshader); with any other
        // mode it aborts at startup: "OpenGLES emulation failed to initialize".
        a.add("-gpu");
        a.add("swiftshader_indirect");
        // Headless: the -qt-hide-window variant needs the Qt build of the
        // binary; -no-window is the one that works with qemu-*-headless.
        a.add(o.noWindow ? "-no-window" : "-qt-hide-window");
        if (o.noAudio) {
            a.add("-no-audio");
        }
        if (o.readOnly) {
            a.add("-read-only");
        }
        if (o.verbose) {
            a.add("-verbose");
        }
        if (o.extraArgs != null && !o.extraArgs.trim().isEmpty()) {
            a.addAll(VelaUtil.splitArgs(o.extraArgs));
        }
        // 桌面宿主的引擎自己能从系统解析器读 DNS，Android 上没这条路（没有
        // /etc/resolv.conf，net.dns1 也是空的），不喂给它客机就解析不了域名。
        if (!hasArg(a, "-dns-server")) {
            a.add("-dns-server");
            a.add(hostDnsServers());
        }
        // -qemu starts the passthrough block consumed by qemu-system-armel.
        a.add("-qemu");
        a.add("-device");
        a.add("virtio-snd,bus=virtio-mmio-bus.2");
        a.add("-allow-host-audio");
        a.add("-semihosting");
        a.add("-smp");
        a.add(String.valueOf(smpFor(avdId)));
        return a;
    }

    private int smpFor(String avdId) {
        try {
            VelaDevices ds = VelaDevices.load(ctx);
            VelaDevices.VelaDevice d = ds.byId(avdId);
            if (d != null && d.ncore > 0) {
                return Math.min(d.ncore, 4);
            }
        } catch (Exception e) {
            VelaLog.w(TAG, "no catalogue entry for " + avdId + ": " + e);
        }
        return 2;
    }

    /** The {@code $BIN} used by the launch script: the engine directory. */
    private String binPathLiteral(File engineDir) {
        return new File(engineDir, "engine").getAbsolutePath();
    }

    /** Shell-quoted argv tail after {@code $BIN}: loader plus emulator args. */
    private String buildEngineInvocation(File engineDir, String avdId, Options o) {
        File bin = binaryFor(engineDir, "engine");
        StringBuilder sb = new StringBuilder();
        File loader = loaderFile(engineDir);
        if (bin != null && !VelaUtil.isNativeLauncher(bin) && loader != null) {
            sb.append(shellQuote(loader.getAbsolutePath())).append(' ');
            sb.append("--library-path ").append(shellQuote(libraryPath(engineDir))).append(' ');
        }
        sb.append(shellQuote(bin == null ? new File(engineDir, "engine/emulator").getAbsolutePath()
                : bin.getAbsolutePath()));
        for (String a : engineArgs(avdId, o)) {
            sb.append(' ').append(shellQuote(a));
        }
        return sb.toString();
    }

    /**
     * The launcher binary. {@code qemu-system-armel} (Qt linked) only exists in
     * the full build; the headless twin is what we normally ship, and
     * {@link #installExecLinks} points it at the static exec stub.
     */
    static File binaryFor(File engineDir, String sub) {
        File emulator = new File(new File(engineDir, sub), "emulator");
        return emulator.isFile() ? emulator : null;
    }

    /** Twin the launcher execs, relative to the engine dir. */
    private static String headlessTwinOf(File engineDir) {
        return "qemu/linux-aarch64/qemu-system-armel-headless";
    }

    // ----------------------------------------------------------------- shims

    /**
     * 名字 → 真身相对路径（{@code engine/} 下）。stub 的表按真身启动，所以这里必须与
     * {@code tools/native/vela-exec-stub.c} 的 TABLE 一致；{@code keep=false} 的那几行
     * 是别名（非 headless 的 qemu 链 Qt、在 Android 上起不来），真身直接删掉省空间。
     */
    private static final String[][] SHIM_BINARIES = {
            {"qemu/linux-aarch64/qemu-system-armel", "qemu/linux-aarch64/qemu-system-armel-headless", "drop"},
            {"qemu/linux-aarch64/qemu-system-armel-headless", "qemu/linux-aarch64/qemu-system-armel-headless", "keep"},
            {"qemu/linux-aarch64/qemu-system-aarch64", "qemu/linux-aarch64/qemu-system-aarch64-headless", "drop"},
            {"qemu/linux-aarch64/qemu-system-aarch64-headless", "qemu/linux-aarch64/qemu-system-aarch64-headless", "keep"},
            {"bin64/e2fsck", "bin64/e2fsck", "keep"},
            {"bin64/fsck.ext4", "bin64/fsck.ext4", "keep"},
            {"bin64/mkfs.ext4", "bin64/mkfs.ext4", "keep"},
            {"bin64/resize2fs", "bin64/resize2fs", "keep"},
            {"bin64/tune2fs", "bin64/tune2fs", "keep"},
    };

    /**
     * 把 launcher 会 exec 的每个名字做成指向 jniLibs 里那个静态 stub 的软链接。
     *
     * <p>为什么不是脚本 shim：应用域连私有目录里的 shell 脚本都不能 exec（实测
     * {@code error=13}），而每个负载的 PT_INTERP 又指向 Android 不存在的
     * {@code /lib/ld-linux-aarch64.so.1}。stub 无 PT_INTERP、是 apk_data_file，
     * 内核能直接跑；它再用 --library-path 把真负载交给 jniLibs 里的 loader 起。</p>
     *
     * <p>幂等：每次 start 都会重建，旧的 {@code .elf} 备份与脚本 shim 一并清掉。</p>
     */
    private void installExecLinks(File root) {
        File engine = new File(root, "engine");
        File stub = VelaNative.payload(ctx, VelaNative.STUB);
        if (!stub.isFile()) {
            VelaLog.w(TAG, "exec stub missing: " + stub + " -- qemu/bin64 将无法启动");
            return;
        }
        int linked = 0;
        for (String[] pair : SHIM_BINARIES) {
            File link = new File(engine, pair[0]);
            // 真身按**自己的名字**改名成 <name>.elf——按 pair[1] 改名会把非 headless
            // 的 Qt 版二进制盖到 headless 真身上（启动时 "libQt5SvgAndroidEmu.so.5
            // cannot open shared object file" 就是这么来的）。
            File backing = new File(engine, pair[0] + ".elf");
            if (link.isFile() && !VelaUtil.symlinkPointsTo(link, stub)) {
                // 覆盖式改名：资产刚解出来的真身永远优先于上一版的备份。
                if (link.renameTo(backing)) {
                    VelaLog.i(TAG, "exec link: " + pair[0] + " -> " + backing.getName());
                } else {
                    VelaLog.w(TAG, "exec link: cannot move " + link + " aside");
                }
            }
            if ("drop".equals(pair[2])) {
                // 别名：真身不用留（stub 把它映射到 headless 真身）。
                File drop = new File(engine, pair[0] + ".elf");
                if (drop.isFile()) {
                    drop.delete();
                }
            }
            if (link.exists() && !VelaUtil.symlinkPointsTo(link, stub)) {
                link.delete();
            }
            if (VelaUtil.symlinkPointsTo(link, stub) || VelaUtil.symlink(stub, link)) {
                linked++;
            } else {
                VelaLog.w(TAG, "exec link failed: " + link + " -> " + stub);
            }
        }
        VelaLog.i(TAG, "exec links ready (" + linked + "/" + SHIM_BINARIES.length + ")");
    }

    // ------------------------------------------------------------------ start

    /** Extracts, prepares HOME, launches and waits until 8554 answers. */
    public LaunchResult start(String avdId, Options opt, long timeoutMs) {
        synchronized (lifecycle) {
            LaunchResult r = startLocked(avdId, opt);
            if (r.running && timeoutMs > 0) {
                r.ready = awaitReady(timeoutMs);
                if (!r.ready) {
                    r.problems.add("gRPC port " + state.grpcPort
                            + " never accepted connections within " + timeoutMs + " ms");
                    VelaLog.e(TAG, "engine not ready after " + timeoutMs + " ms -- tail of log:\n"
                            + VelaUtil.ellipsize(tailOf(currentLog, 40), 4000));
                }
            }
            return r;
        }
    }

    private LaunchResult startLocked(String avdId, Options opt) {
        Options o = opt == null ? new Options() : opt;
        LaunchResult r = new LaunchResult();
        r.avdId = avdId;
        r.grpcPort = o.grpcPort != null ? o.grpcPort : GRPC_PORT;
        r.debugPort = o.debugPort != null ? o.debugPort : DEFAULT_DEBUG_PORT;

        VelaLog.i(TAG, "start avd=" + avdId + " grpc=" + r.grpcPort + " debug=" + r.debugPort);
        try {
            File homeForPid = safeWritableHome();
            File already = new File(homeForPid, "engine.pid");
            int livePid = readPidFile(already);
            if (livePid > 0 && isEngineAlive(livePid, r.grpcPort)) {
                String runningAvd = state.avdId;
                boolean sameAvd = avdId == null || avdId.equals(runningAvd);
                if (!sameAvd) {
                    // 模拟器一次只能跑一个客机：想换设备就得先把旧的停掉，
                    // 否则点"启动 xiaomi_band"会静默复用 watch 的旧引擎
                    // （画面还是旧镜像的表盘，看着像"启动错设备"）。
                    VelaLog.i(TAG, "运行中的引擎是 " + runningAvd + "，请求的是 " + avdId
                            + " → 先停旧引擎");
                    stop();
                } else {
                    r.running = true;
                    r.pid = livePid;
                    r.mode = state.mode == null ? "already-running" : state.mode;
                    r.commandLine = state.commandLine;
                    r.logPath = state.logPath;
                    if (state.logPath != null) {
                        lastLog = new File(state.logPath);
                    }
                    r.error = "engine already running as pid " + livePid + "; call stopEngine first";
                    state = r;
                    return r;
                }
            }

            ensureExtracted();
            ensureOverrideApplied();
            File engineDir = VelaPaths.engineDir(ctx);
            installExecLinks(engineDir);
            File home = ensureDirs(engineDir);
            Map<String, String> env = buildEnv(engineDir);

            File avdDir = VelaAvd.directoryFor(ctx, avdId);
            if (!avdDir.isDirectory()) {
                throw new IOException("AVD " + avdId + " does not exist -- create it first (createAvd)");
            }
            // An AVD made by an older build has no pointer ini; the engine exits
            // with "no file <id>.ini in $ANDROID_AVD_HOME" without one.
            VelaAvd.writePointerIni(avdDir, avdId);
            List<String> avdProblems = VelaAvd.validate(ctx, avdId);
            if (!avdProblems.isEmpty()) {
                VelaLog.w(TAG, "AVD preflight warnings: " + avdProblems);
            }
            r.problems.addAll(avdProblems);

            if (!o.skipAdbPorts) {
                List<String> busy = awaitAdbPorts(o.verbose ? 3_000 : 1_500);
                r.problems.addAll(busy);
            }
            if (!portFree(r.grpcPort)) {
                VelaLog.w(TAG, "gRPC port " + r.grpcPort + " is already bound; the engine may fail to start");
                r.problems.add("TCP " + r.grpcPort + " already in use");
            }

            int pid = android.os.Process.myPid();
            File logs = new File(home, "logs");
            logs.mkdirs();
            File logFile = new File(logs, "engine-" + System.currentTimeMillis() + ".log");
            currentLog = logFile;
            lastLog = logFile;
            readyLatch.set(false);
            VelaUtil.writeText(logFile, "# Vortex engine log\n"
                    + "# avd=" + avdId + "\n# pid=" + pid + "\n"
                    + "# home=" + home.getAbsolutePath() + "\n");
            r.logPath = logFile.getAbsolutePath();
            VelaUtil.clear(new File(home, "engine.pid"));
            VelaUtil.clear(new File(home, "engine.ready"));

            boolean useHeadless = o.headlessBinary;
            File bin = binaryFor(engineDir, "engine");
            if (bin == null) {
                throw new IOException("engine/emulator missing under " + engineDir);
            }
            r.commandLine = describeCommandLine(engineDir, home, env, bin, avdId, o, useHeadless);

            // 本进程直接起：loader 在 jniLibs（apk_data_file），内核允许 exec；
            // 引擎与工具链本体仍在私有目录，由 loader 用 mmap 读起来。
            // Shizuku 不再需要（也不再需要 debuggable）。
            r.mode = "local";
            VelaLog.i(TAG, "local launch as pid " + pid);
            startLocal(engineDir, home, env, bin, avdId, o, logFile, useHeadless, r);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    waitPidFile(new File(home, "engine.pid"), new File(home, "engine.ready"));
                }
            }, "vela-pid-wait").start();

            r.running = true;
            state = r;
            VelaLog.i(TAG, "mode=" + r.mode + " script=" + r.scriptPath);
            return r;
        } catch (Throwable t) {
            r.mode = r.mode == null ? "error" : r.mode;
            r.running = false;
            r.ready = false;
            r.error = describeError(t);
            state = r;
            VelaLog.e(TAG, "start failed", t);
            return r;
        }
    }

    private String describeCommandLine(File engineDir, File home, Map<String, String> env,
                                       File bin, String avdId, Options o, boolean useHeadless) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            sb.append(e.getKey()).append('=').append(shellQuote(e.getValue())).append(' ');
        }
        if (bin != null && !VelaUtil.isNativeLauncher(bin)) {
            sb.append(shellQuote(new File(engineDir, "glibc/lib/" + LOADER_NAME).getAbsolutePath()))
                    .append(" --library-path ")
                    .append(shellQuote(libraryPath(engineDir))).append(' ');
        }
        sb.append(shellQuote(bin.getAbsolutePath()));
        for (String a : engineArgs(avdId, o)) {
            sb.append(' ').append(shellQuote(a));
        }
        return sb.toString();
    }

    /**
     * 本进程直接 spawn 引擎（Application 域，uid 就是本应用）。
     *
     * <p>不带 {@code setsid}：进程组要留给 pid 跟踪——launcher 之后是 execve()
     * 而不是 fork+exec，所以 pid 全程不变，{@code engine.pid} 记的就是 qemu 本身；
     * 应用进程被杀时这个子进程会被 init 收养，不会跟着死。</p>
     */
    private void startLocal(File engineDir, File home, Map<String, String> env, File bin,
                           String avdId, Options o, File logFile, boolean useHeadless,
                           LaunchResult r) {
        try {
            List<String> argv = buildArgv(engineDir, avdId, o);
            VelaLog.i(TAG, "local exec: " + VelaUtil.ellipsize(VelaUtil.join(" ", argv), 900));
            // 借 /system/bin/sh 记 pid：`exec` 之后 shell 的进程号就是引擎的进程号，
            // 而 Android 的 java.lang.Process 没有 pid()（反射拿不到）。
            File pidFile = new File(home, "engine.pid");
            StringBuilder cmd = new StringBuilder();
            cmd.append("echo $$ > ").append(shellQuote(pidFile.getAbsolutePath())).append("; exec");
            for (String a : argv) {
                cmd.append(' ').append(shellQuote(a));
            }
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd.toString());
            Map<String, String> childEnv = pb.environment();
            childEnv.putAll(env);
            childEnv.put(VelaNative.RUNTIME_ENV, VelaPaths.runtimeDir(ctx).getAbsolutePath());
            childEnv.put("TMPDIR", new File(home, "tmp").getAbsolutePath());
            childEnv.put("SHELL", "/system/bin/sh");
            pb.redirectErrorStream(true);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            // 与旧启动脚本的 `>>$LOG 2>&1` 等价：引擎的串口/错误全都进同一个日志。
            if (logFile != null && logFile.getParentFile() != null) {
                logFile.getParentFile().mkdirs();
            }
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            fallbackProcess = pb.start();
            fallbackPid = awaitPidFile(pidFile, 5_000);
            if (fallbackPid <= 0) {
                VelaLog.w(TAG, "cannot read the engine pid; stop() will fall back to the port sweep");
            }
            r.pid = fallbackPid;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    java.lang.Process p = fallbackProcess;
                    if (p == null) {
                        return;
                    }
                    try {
                        int code = p.waitFor();
                        if (code == 0) {
                            VelaLog.i(TAG, "engine exited with 0");
                        } else {
                            // 引擎的输出进的是日志文件，而应用私有目录在非 debuggable
                            // 之后连 adb 也读不到——异常退出时把尾巴抄进 logcat。
                            VelaLog.e(TAG, "engine exited with " + code + ", log tail:");
                                    VelaLog.e(TAG, VelaUtil.ellipsize(tailOf(logFile, 25), 2500));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    fallbackProcess = null;
                    fallbackPid = -1;
                    readyLatch.set(false);
                }
            }, "vela-exit-wait").start();
        } catch (Throwable t) {
            r.error = "本地启动失败: " + describeError(t);
            r.running = false;
            VelaLog.e(TAG, "local start failed", t);
        }
    }

    /**
     * {@code Os.setenv} exists from API 26; it matters because
     * {@link ProcessBuilder} inherits it, and it is the only way the glibc
     * loader sees HOME. When it is missing we lose nothing: the child env map
     * below carries the same pairs, and {@code --library-path} covers the
     * libraries.
     */
    @android.annotation.SuppressLint("NewApi")

    /** Best-effort pid of a freshly started java.lang.Process (hidden on older Android). */
    private static int readPidOf(java.lang.Process p) {
        if (p == null) {
            return -1;
        }
        try {
            Method m = p.getClass().getMethod("pid");
            Object v = m.invoke(p);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** 等 {@code engine.pid} 出现（sh 写它 → exec 之后 pid 不变，就是引擎的）。 */
    private static int awaitPidFile(File pidFile, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int pid = readPidFile(pidFile);
            if (pid > 0) {
                return pid;
            }
            sleep(100);
        }
        return -1;
    }

    // ------------------------------------------------------------------ logs

    /** Pump {@code stream} line-by-line into the ring buffer and engine log. */
    private void serialStderr(final InputStream stream, final String tag, final int pid) {
        if (stream == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(stream, "UTF-8"), 8 * 1024);
                    String line;
                    while ((line = br.readLine()) != null) {
                        VelaLog.i(tag, line);
                        if (line.contains("QEMU started") || line.contains("grpc port")) {
                            readyLatch.set(true);
                        }
                    }
                } catch (IOException e) {
                    VelaLog.d(TAG, tag + " stream closed: " + e);
                } finally {
                    VelaUtil.closeQuietly(br);
                }
            }
        }, "vela-" + tag + "-" + pid);
        t.setDaemon(true);
        t.start();
    }

    private void serialStderr(byte[] buf, int n, String ignoredTag) {
        String s = new String(buf, 0, n);
        for (String line : s.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) {
                VelaLog.i("qemu", line);
            }
        }
    }

    public String tailOf(File f, int lines) {
        return VelaUtil.tail(f, lines);
    }

    /** Tails the live engine log (kernel output, qemu errors, gRPC banner). */
    public String tail(int lines) {
        File f = currentLog;
        if (f != null && f.isFile()) {
            return VelaUtil.tail(f, lines);
        }
        File dir = new File(safeWritableHome(), "logs");
        File[] kids = dir.listFiles();
        File newest = null;
        if (kids != null) {
            for (File k : kids) {
                if (k.isFile() && (newest == null || k.lastModified() > newest.lastModified())) {
                    newest = k;
                }
            }
        }
        return newest == null ? "" : VelaUtil.tail(newest, lines);
    }

    /** {@code <home>/logs/engine-*.log} newest first. */
    public List<File> logFiles() {
        List<File> out = new ArrayList<>();
        File[] kids = new File(safeWritableHome(), "logs").listFiles();
        if (kids != null) {
            Arrays.sort(kids);
            for (File f : kids) {
                if (f.isFile() && f.getName().startsWith("engine-")) {
                    out.add(f);
                }
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    public File currentLogFile() {
        return currentLog;
    }

    // ------------------------------------------------------------------ stop

    /**
     * Kills the engine: the pid file first, then a {@code pkill -f} scoped to
     * this package's path so sibling apps' qemu processes are untouched.
     */
    public boolean stop() {
        synchronized (lifecycle) {
            boolean any = false;
            File home = safeWritableHome();
            File pidFile = new File(home, "engine.pid");
            int pid = readPidFile(pidFile);
            if (pid > 0) {
                any |= killProcess(pid);
            }
            java.lang.Process p = fallbackProcess;
            if (p != null) {
                try {
                    p.destroy();
                    any = true;
                } catch (Throwable ignored) {
                }
                fallbackProcess = null;
            }
            if (fallbackPid > 0) {
                any |= killProcess(fallbackPid);
            }
            LaunchResult st = state;
            int port = st.grpcPort > 0 ? st.grpcPort : GRPC_PORT;
            for (int holder : pidOfPort(port)) {
                if (holder > 0 && holder != android.os.Process.myPid()) {
                    any |= killProcess(holder);
                }
            }
            VelaUtil.clear(pidFile);
            new File(home, "engine.ready").delete();
            readyLatch.set(false);
            if (any) {
                for (int i = 0; i < 25; i++) {
                    if (portFree(port)) {
                        break;
                    }
                    sleep(100);
                }
            }
            LaunchResult after = new LaunchResult();
            after.avdId = st.avdId;
            after.mode = st.mode;
            after.grpcPort = st.grpcPort;
            after.debugPort = st.debugPort;
            after.running = false;
            after.ready = false;
            state = after;
            VelaLog.i(TAG, "engine stopped (pid " + pid + ", port " + port + " "
                    + (portFree(port) ? "free" : "still busy") + ")");
            return any;
        }
    }

    private static boolean killProcess(int pid) {
        try {
            android.system.Os.kill(pid, android.system.OsConstants.SIGTERM);
        } catch (Throwable e) {
            VelaLog.w(TAG, "SIGTERM " + pid + " refused: " + e);
            return false;
        }
        sleep(400);
        if (!isProcessAlive(pid)) {
            return true;
        }
        try {
            android.system.Os.kill(pid, android.system.OsConstants.SIGKILL);
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** SIGKILL whoever holds our ports; last-resort cleanup after a crash. */
    public int killPortHolders(int... ports) {
        int killed = 0;
        for (int port : ports) {
            for (int pid : pidOfPort(port)) {
                if (pid > 0 && pid != android.os.Process.myPid() && killProcess(pid)) {
                    killed++;
                }
            }
        }
        return killed;
    }

    // ---------------------------------------------------------------- status

    /** Snapshot for the {@code status} event and {@code engineStatus}. */
    public Map<String, Object> status() {
        LaunchResult s = state;
        Map<String, Object> m = new LinkedHashMap<>();
        boolean alive = s.running && modeAlive(s);
        m.put("running", alive);
        m.put("ready", alive && (readyLatch.get() || isGrpcPortListening(s.grpcPort)));
        m.put("mode", s.mode);
        m.put("avd", s.avdId);
        m.put("pid", s.pid);
        m.put("grpcPort", s.grpcPort);
        m.put("debugPort", s.debugPort);
        m.put("engineDir", VelaPaths.engineDir(ctx).getAbsolutePath());
        m.put("home", safeWritableHome().getAbsolutePath());
        m.put("avdHome", VelaPaths.velaVvdDir(ctx).getAbsolutePath());
        m.put("log", s.logPath);
        if (s.logPath != null) {
            // 装机要尾随这份日志等客机的安装完成行（见 VelaDeploy.waitForInstall），
            // 所以只要还在跑就记住它，别等 startEngine 才设。
            lastLog = new File(s.logPath);
        }
        m.put("commandLine", s.commandLine);
        m.put("error", s.error);
        m.put("problems", s.problems);
        m.put("extracted", new File(VelaPaths.engineDir(ctx), "asset-extract.ok").isFile());
        return m;
    }

    private boolean modeAlive(LaunchResult s) {
        if ("already-running".equals(s.mode)) {
            int pid = readPidFile(new File(safeWritableHome(), "engine.pid"));
            return isEngineAlive(pid, s.grpcPort);
        }
        if (s.pid > 0 && isProcessAlive(s.pid)) {
            return true;
        }
        return s.grpcPort > 0 && isGrpcPortListening(s.grpcPort);
    }

    /** True while something accepts TCP on the gRPC port. Liveness by port. */
    public boolean isGrpcPortListening(int port) {
        return !portFree(port);
    }

    /**
     * True when nothing listens on {@code port}. Probed by connecting rather
     * than binding: the app domain may be denied {@code name_service_port}
     * binds, and a bind test would then report every port as busy.
     */
    public boolean portFree(int port) {
        if (port <= 0 || port > 65535) {
            return true;
        }
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return false;
        } catch (IOException e) {
            return true;
        } finally {
            VelaUtil.closeQuietly(s);
        }
    }

    /**
     * Blocks until {@code grpcPort} answers.
     *
     * <p>The real readiness marker is {@code <home>/engine.ready}, written by
     * a future init hook; today we poll the port, which is what the Dart gRPC
     * client needs anyway.
     */
    public boolean awaitReady(long timeoutMs) {
        if (state.pid > 0 || state.running) {
            exitWaitDeadlineMs = System.currentTimeMillis() + timeoutMs;
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        int port = state.grpcPort > 0 ? state.grpcPort : GRPC_PORT;
        while (System.currentTimeMillis() < deadline) {
            if (new File(safeWritableHome(), "engine.ready").isFile()) {
                readyLatch.set(true);
                return true;
            }
            if (isGrpcPortListening(port)) {
                readyLatch.set(true);
                VelaLog.i(TAG, "engine ready on 127.0.0.1:" + port);
                return true;
            }
            if (!state.running) {
                VelaLog.w(TAG, "engine stopped while waiting for readiness");
                return false;
            }
            sleep(250);
        }
        VelaLog.e(TAG, "timed out waiting for 127.0.0.1:" + port);
        return false;
    }

    /**
     * adb bridge ports must be free: the engine binds 5554/5555 and aborts
     * otherwise. Retries tolerate a dying previous instance.
     */
    public List<String> awaitAdbPorts(long timeoutMs) {
        List<String> problems = new ArrayList<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        int quiet = 0;
        while (System.currentTimeMillis() < deadline) {
            problems.clear();
            if (!portFree(ADB_PORT_EVEN)) {
                problems.add("TCP " + ADB_PORT_EVEN + " is in use by another process");
            }
            if (!portFree(ADB_PORT_ODD)) {
                problems.add("TCP " + ADB_PORT_ODD + " is in use by another process");
            }
            if (problems.isEmpty()) {
                if (++quiet >= WAIT_PORT_QUIET_RETRIES) {
                    return problems;
                }
            } else {
                quiet = 0;
                VelaLog.w(TAG, "waiting for adb bridge ports: " + problems);
                killPortHolders(ADB_PORT_EVEN, ADB_PORT_ODD);
            }
            sleep(400);
        }
        return problems;
    }

    /** Ports currently bound, for the diagnostics sheet. */
    public Map<String, Boolean> portsInUse(int... ports) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (int p : ports) {
            m.put(String.valueOf(p), !portFree(p));
        }
        return m;
    }

    /** PIDs bound to the port, read from /proc/net/tcp{,6}. */
    public List<Integer> pidOfPort(int port) {
        List<Integer> out = new ArrayList<>();
        String hex = String.format(java.util.Locale.US, "%04X", port);
        for (String file : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            String text = VelaUtil.slurpQuietly(new File(file));
            if (text == null) {
                continue;
            }
            for (String line : text.split("\n")) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 10 || !f[0].endsWith(":0000".substring(0, 0) + hex)) {
                    continue;
                }
                if (!f[0].contains(":" + hex)) {
                    continue;
                }
                if (!"0A".equals(f[3])) {
                    continue;
                }
                int inode;
                try {
                    inode = Integer.parseInt(f[9]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (inode == 0) {
                    continue;
                }
                for (int pid : socketInodePids(inode)) {
                    if (!out.contains(pid)) {
                        out.add(pid);
                    }
                }
            }
        }
        return out;
    }

    private List<Integer> socketInodePids(int inode) {
        List<Integer> out = new ArrayList<>();
        File proc = new File("/proc");
        String[] kids = proc.list();
        if (kids == null) {
            return out;
        }
        String target = "socket:[" + inode + "]";
        for (String name : kids) {
            if (name.isEmpty() || (name.charAt(0) < '0' || name.charAt(0) > '9')) {
                continue;
            }
            File fdDir = new File(new File(proc, name), "fd");
            String[] fds = fdDir.list();
            if (fds == null) {
                continue;
            }
            for (String fd : fds) {
                try {
                    if (target.equals(android.system.Os.readlink(new File(fdDir, fd).getAbsolutePath()))) {
                        out.add(Integer.parseInt(name));
                        break;
                    }
                } catch (Throwable ignored) {
                    // EACCES on other apps' fds is the normal case.
                }
            }
        }
        return out;
    }

    public static boolean isProcessAlive(int pid) {
        if (pid <= 0) {
            return false;
        }
        return new File("/proc/" + pid).isDirectory();
    }

    /**
     * Liveness of a started engine. The port probe is the second signal because
     * {@code /proc/<pid>} can answer "gone" for a process we can still not stat
     * (it was the norm while the engine ran in the {@code runas_app} domain).
     *
     * <p>The emulator's running-registration file is deliberately NOT used here:
     * it survives a crash or a reboot, and a stale one made start() refuse to
     * launch anything ("engine already running") with a black screen as the only
     * symptom.</p>
     */
    private boolean isEngineAlive(int pid, int grpcPort) {
        if (pid <= 0) {
            return false;
        }
        if (isProcessAlive(pid)) {
            return true;
        }
        return grpcPort > 0 && !portFree(grpcPort);
    }

    /** Waits for the child to exit (or the deadline); used around restarts. */
    public boolean awaitExit(long timeoutMs) {
        exitWaitDeadlineMs = System.currentTimeMillis() + timeoutMs;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && modeAlive(state)) {
            sleep(150);
        }
        return !modeAlive(state);
    }

    /** Blocks until a running engine stops; the caller owns stopEngine. */
    public boolean awaitStopped(long timeoutMs) {
        return awaitExit(timeoutMs);
    }

    private void waitPidFile(File pidFile, File readyFile) {
        long deadline = System.currentTimeMillis() + 30_000;
        int pid = -1;
        while (System.currentTimeMillis() < deadline) {
            pid = readPidFile(pidFile);
            if (pid > 0) {
                break;
            }
            sleep(150);
        }
        if (pid > 0) {
            state.pid = pid;
            VelaLog.i(TAG, "engine child pid " + pid);
            // /proc is unreadable across the runas_app boundary, so liveness comes
            // from the engine's registration file / bound port instead; stopEngine
            // clears the pid file, which also ends this loop.
            while (isEngineAlive(pid, state.grpcPort) && readPidFile(pidFile) == pid) {
                sleep(500);
            }
            VelaLog.i(TAG, "engine child pid " + pid + " exited");
            readyLatch.set(false);
            state.running = false;
        } else {
            VelaLog.w(TAG, "no pid file after 30 s -- the launcher script never reported a child");
        }
        exitLatch.countDown();
    }

    private static int readPidFile(File f) {
        String s = VelaUtil.slurpQuietly(f);
        if (s == null) {
            return -1;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Last error string from the most recent start, or null. */
    public String lastError() {
        return state.error;
    }

    public LaunchResult currentState() {
        return state;
    }

    /** Serial console log: the guest kernel output ends up in the engine log. */
    public String serialLogTail(int lines) {
        return tail(lines);
    }

    // ---------------------------------------------------------------- helpers

    static String shellQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        boolean safe = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c == '-' || c == '_' || c == '.' || c == '/' || c == ':' || c == ','
                    || c == '+' || c == '%' || c == '@' || c == '=' || c == '~'
                    || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
                safe = false;
                break;
            }
        }
        if (safe) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String describeError(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String m = t.getMessage();
        String cls = t.getClass().getSimpleName();
        return m == null || m.isEmpty() ? cls + " (no message)" : cls + ": " + m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
