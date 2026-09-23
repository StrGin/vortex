package com.velasim.app;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * 构建工具链：内置 node 运行时 + npm 包 {@code aiot-toolkit}（bin 名 {@code aiot}）。
 *
 * <pre>
 *   &lt;files&gt;/vela/toolchain/
 *     node/bin/node          可执行 node（用户投放 / 由 libnode.so 拷来改名）
 *     node/libnode.so        nodejs-mobile 的 libnode：它带 main，chmod 后可直接当 node 用
 *     node/lib/…             libc++_shared.so 之类的伴生库
 *     node_modules/          共享 toolkit（npm --prefix 或 toolkit.tar 解出来的）
 *     toolkit.ok             离线包解完成的标记（内容 = asset 版本戳）
 * </pre>
 *
 * <p>三条路都留了口子，按可用性依次退化：APK 里带 {@code assets/vela/toolkit.tar}
 * （含 node_modules）就离线解包；否则有 node+npm 就在线 {@code npm install}；
 * 两个都没有就老老实实报「设备上不可用」，绝不假装构建成功 ——
 * {@link #buildArgv} 返回 null，{@link #missingWhy} 给出中文原因。</p>
 *
 * <p>{@code libnode.so} 只在 nativeLibraryDir 里躺着时不能拿来跑 npm：nodejs-mobile 的
 * {@code node::Start} 在进程内只能跑一次（第二次 SIGTRAP），所以这里一律走「拷出来
 * chmod +x 当独立进程用」这条路。</p>
 */
public final class VelaToolchain {

    public static final String TOOLKIT_PACKAGE = "aiot-toolkit";
    public static final String TOOLKIT_RANGE = "^2.0.5";
    public static final String TOOLKIT_VERSION = "2.0.5";
    public static final String UX_TYPES = "ux-types";
    public static final String UX_TYPES_RANGE = "*";

    /** 离线工具链包（可选）：{@code assets/vela/toolkit.tar[.gz]}。 */
    public static final String ASSET_TOOLKIT_TAR = "vela/toolkit.tar";
    public static final String ASSET_TOOLKIT_TGZ = "vela/toolkit.tar.gz";

    /** qemu-user + x86_64 glibc + aiotjsc（客机只吃 .jsc 字节码，见 unpackJscBundle）。 */
    public static final String ASSET_JSC_TAR = "vela/jsc.tar";
    private static final String JSC_STAMP = ".vela-jsc.stamp";
    /** 可选的内置 node：{@code assets/vela/node/libnode.so}。 */
    public static final String ASSET_NODE_PREFIX = "vela/node";

    private static final String TAG = "toolchain";
    private static final String LOADER_NAME = "ld-linux-aarch64.so.1";
    private static final int ASSET_PROBE_CHUNK = 8;
    /** npm install 的耐心值：依赖树几百个包，15 分钟起。 */
    private static final long NPM_TIMEOUT_MS = 15 * 60 * 1000L;

    /** 安装进度。{@code percent} 只在能算准的时候才动。 */
    public interface Listener {
        /** phase in probe | tar | npm | verify | done | error */
        void onProgress(String phase, int percent, String detail);
    }

    /** 流式跑一条命令；每行输出回调一次。 */
    public interface LineSink {
        void onLine(String line, boolean stderr);
    }

    private final Context ctx;
    private final VelaProjects projects;

    /** {@link #exec} 是静态方法，靠这份引用拿到私有目录（构造时写入）。 */
    private static volatile Context appCtx;

    private volatile File cachedNode;
    private volatile boolean nodeProbed;
    private volatile boolean nodeProbeAuthorized;
    private volatile String nodeVersion = "";
    private volatile String npmEntry;
    private volatile boolean checkedSystemNode;
    private volatile String systemNode;
    private volatile String missingWhy;

    public VelaToolchain(Context ctx, VelaProjects projects) {
        this.ctx = ctx.getApplicationContext();
        this.projects = projects;
        appCtx = this.ctx;
    }

    // ------------------------------------------------------------------ paths

    public static File toolchainDir(Context ctx) {
        return new File(VelaPaths.runtimeDir(ctx), "toolchain");
    }

    public File dir() {
        return toolchainDir(ctx);
    }

    public File nodeDir() {
        return new File(toolchainDir(ctx), "node");
    }

    public File nodeBin() {
        return new File(nodeDir(), "bin/node");
    }

    /** 共享 node_modules：工程内没有 aiot-toolkit 时的兜底解析位置。 */
    public File sharedModules() {
        return new File(toolchainDir(ctx), "node_modules");
    }

    public File sharedBin() {
        return new File(toolchainDir(ctx), "node_modules/.bin");
    }

    /** 用户可投放 node/libnode/toolkit 的外部目录。 */
    public File dropDir() {
        return new File(android.os.Environment.getExternalStorageDirectory(), VelaProjects.DROP_DIR);
    }

    // ------------------------------------------------------------------ node

    /**
     * 可用的 node 可执行文件；没有返回 null。
     *
     * <p>探测顺序：已存在的 {@code toolchain/node/bin/node} → 从 nativeLibraryDir /
     * assets / 投放目录拷一份 libnode.so 过来 → PATH 上的 node（Termux 之类）。</p>
     */
    public synchronized File node() {
        if (nodeProbed && cachedNode != null) {
            return cachedNode;
        }
        nodeProbed = true;
        File bin = nodeBin();
        if (!bin.isFile() || !nodeBundleCurrent()) {
            // Preferred: the self-contained Termux runtime packaged under
            // assets/vela/node — a real bionic executable (PT_INTERP
            // = /system/bin/linker64) plus the sonames it needs. Re-imported
            // whenever the shipped bundle changes (stamp mismatch).
            if (!importBundledNode(bin)) {
                importLibnode(bin);
            }
        }
        if (bin.isFile() && probeNode(bin)) {
            cachedNode = bin;
            return bin;
        }
        String sys = systemNodeOnPath();
        if (sys != null) {
            File f = new File(sys);
            if (probeNode(f)) {
                cachedNode = f;
                return f;
            }
        }
        missingWhy = "设备上没有可用的 node：APK 里应带 assets/vela/node/{bin/node,lib/*.so}"
                + "（由 re/build-node-bundle.py 生成）；也可把 bionic 原生 node 放到 "
                + dropDir().getAbsolutePath() + " 下文件名 node；注意 libnode.so 是库，"
                + "不能当程序跑";
        VelaLog.w(TAG, missingWhy);
        return null;
    }

    /**
     * {@code local} —— 工具链永远由本进程直接 spawn：argv[0] 是 jniLibs 里的 glibc loader
     * （{@code apk_data_file}），node/npm/aiot 由它 mmap 私有目录里的真身跑起来，
     * 不再需要 Shizuku，也不再需要 debuggable。
     */
    public static String execMode() {
        return "local";
    }

    /**
     * Looks for a node binary on PATH (Termux, or a ROM that ships one). On a plain
     * Android install there is none, so this normally returns null and the bundled
     * nodejs-mobile binary is the only option.
     */
    private String systemNodeOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String dir : path.split(":")) {
            if (dir.isEmpty()) {
                continue;
            }
            File cand = new File(dir, "node");
            if (cand.isFile() && cand.canExecute()) {
                return cand.getAbsolutePath();
            }
        }
        return null;
    }

    public boolean isNodeAvailable() {
        return node() != null;
    }

    /** Drops the node/npm probe caches so the next status() re-detects everything. */
    public synchronized void resetProbe() {
        nodeProbed = false;
        cachedNode = null;
        npmEntry = null;
        checkedSystemNode = false;
        systemNode = null;
        missingWhy = null;
        VelaLog.i(TAG, "probe caches cleared");
    }

    /** node --version 跑通才算可用；顺带缓存版本号给 UI 显示。 */
    private boolean probeNode(File bin) {
        List<String> argv = withLoader(bin, "--version");
        StringBuilder out = new StringBuilder();
        int code = exec(argv, dir(), envFor(null), new LineSink() {
            @Override
            public void onLine(String line, boolean stderr) {
                out.append(line).append(' ');
            }
        }, 20000);
        if (code == 0 && out.length() > 0) {
            nodeVersion = out.toString().trim();
            VelaLog.i(TAG, "node ok: " + bin + " " + nodeVersion);
            return true;
        }
        VelaLog.w(TAG, "node probe failed " + bin + " exit=" + code + " out=" + out);
        return false;
    }

    /**
     * glibc 版 node 需要显式解释器（和引擎一样的 {@code --library-path} 借壳跑法）。
     * bionic 原生 ELF 直接跑。
     */
    private List<String> withLoader(File bin, String... args) {
        List<String> argv = new ArrayList<>();
        File loader = glibcLoader();
        if (loader != null && !VelaUtil.isNativeLauncher(bin)) {
            argv.add(loader.getAbsolutePath());
            argv.add("--library-path");
            argv.add(glibcLib());
        }
        argv.add(bin.getAbsolutePath());
        for (String a : args) {
            argv.add(a);
        }
        return argv;
    }

    private File glibcLoader() {
        // jniLibs 里那份（apk_data_file）：只有它能被本进程 exec。私有目录里那份
        // 在 targetSdk >= 29 的设备上会被 SELinux 拒绝，留着只作老系统兜底。
        File nativeLoader = VelaNative.payload(ctx, VelaNative.LD);
        if (nativeLoader.isFile()) {
            return nativeLoader;
        }
        File f = new File(VelaPaths.engineDir(ctx), "glibc/lib/" + LOADER_NAME);
        return f.isFile() ? f : null;
    }

    private String glibcLib() {
        File engine = VelaPaths.engineDir(ctx);
        return new File(engine, "glibc/lib").getAbsolutePath() + ":"
                + new File(engine, "engine/lib64").getAbsolutePath() + ":"
                + nodeLibDir().getAbsolutePath();
    }

    /** libnode 的伴生库（libc++_shared.so）放这儿，LD_LIBRARY_PATH 里带上。 */
    public File nodeLibDir() {
        return new File(nodeDir(), "lib");
    }

    /**
     * 把 libnode.so 拷成可执行的 node。
     *
     * <p>nodejs-mobile 的 libnode.so 里带着 main，改名 + chmod 就能独立跑；
     * 但同目录必须有 libc++_shared.so，否则 loader 直接拒。</p>
     */
    private boolean importLibnode(File target) {
        File src = findLibnode();
        if (src == null) {
            return false;
        }
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            VelaUtil.copyFile(src, target);
            VelaUtil.chmod755(target);
            copyCompanions(src);
            VelaLog.i(TAG, "libnode 拷成 node: " + src + " -> " + target
                    + " (" + VelaUtil.formatBytes(target.length()) + ")");
            return true;
        } catch (Throwable t) {
            VelaLog.w(TAG, "拷贝 libnode 失败: " + t);
            return false;
        }
    }

    /**
     * 释放 assets/vela/node/ 下的自足 node 运行时：bin/node + lib/*.so。
     *
     * <p>为什么不能用 nodejs-mobile 的 libnode.so：readelf 实测它是
     * {@code DYN (Shared object file)}，没有 main/_start、没有 PT_INTERP，是库不是程序，
     * exec 不了。Termux 的 node 才是 bionic 原生可执行文件，代价是要把它的
     * NEEDED soname 一起带上（Termux rootfs 在 Windows 上解包会丢符号链接，
     * 所以打包时已按 soname 重命名，见 re/build-node-bundle.py）。</p>
     */
    private boolean importBundledNode(File target) {
        try {
            String[] bins = ctx.getAssets().list(ASSET_NODE_PREFIX + "/bin");
            if (bins == null || bins.length == 0) {
                return false;
            }
            String node = null;
            for (String b : bins) {
                if ("node".equals(b)) {
                    node = b;
                }
            }
            if (node == null) {
                return false;
            }
            // Wipe first: a stale extraction used to survive (the lib copy below
            // skipped files that already existed), so an older bundle's x86_64
            // .so files kept breaking the aarch64 node with "is for EM_X86_64
            // (62) instead of EM_AARCH64 (183)".
            VelaUtil.deleteRecursive(nodeDir());
            target.getParentFile().mkdirs();
            InputStream in = ctx.getAssets().open(ASSET_NODE_PREFIX + "/bin/" + node);
            OutputStream os = new FileOutputStream(target);
            try {
                VelaUtil.copy(in, os);
            } finally {
                VelaUtil.closeQuietly(in);
                VelaUtil.closeQuietly(os);
            }
            VelaUtil.chmod755(target);

            File libDir = nodeLibDir();
            libDir.mkdirs();
            int libs = 0;
            String[] names = ctx.getAssets().list(ASSET_NODE_PREFIX + "/lib");
            if (names != null) {
                for (String n : names) {
                    File out = new File(libDir, n);
                    InputStream li = ctx.getAssets().open(ASSET_NODE_PREFIX + "/lib/" + n);
                    OutputStream lo = new FileOutputStream(out);
                    try {
                        VelaUtil.copy(li, lo);
                    } finally {
                        VelaUtil.closeQuietly(li);
                        VelaUtil.closeQuietly(lo);
                    }
                    libs++;
                }
            }
            VelaUtil.writeText(new File(nodeDir(), NODE_STAMP), nodeBundleStamp());
            VelaLog.i(TAG, "bundled node 释放完成: " + target + " + " + libs + " 个库");
            return true;
        } catch (Throwable t) {
            VelaLog.w(TAG, "释放 bundled node 失败: " + t);
            return false;
        }
    }

    private static final String NODE_STAMP = ".vela-node.stamp";

    private String nodeBundleStamp() {
        return VelaUtil.assetPayloadStamp(ctx, ASSET_NODE_PREFIX + "/bin/node") + "-node";
    }

    /**
     * True when the extraction on disk came from the bundle currently shipped in
     * the APK. Without this, a bundle fix (e.g. the aarch64 node libs) never
     * reached an install that already had an older extraction.
     */
    private boolean nodeBundleCurrent() {
        File stamp = new File(nodeDir(), NODE_STAMP);
        if (!stamp.isFile()) {
            return false;
        }
        String want = nodeBundleStamp();
        String have = VelaUtil.slurpQuietly(stamp);
        return have != null && want.equals(have.trim());
    }

    // ------------------------------------------------------------- jsc 字节码

    /** 客机 JS 只吃 QuickJS 字节码，而 aiotjsc 只有 x86_64 版；这份目录装 qemu + sysroot 借壳跑。 */
    public File jscDir() {
        return new File(toolchainDir(ctx), "jsc");
    }

    /** 工具链里 aiotjsc 包的解出位置（tar 里直接落位到 node_modules）。 */
    public File jscPackageDir() {
        return new File(sharedModules(), "@aiot-toolkit/jsc");
    }

    private boolean jscBundleCurrent() {
        File stamp = new File(jscDir(), JSC_STAMP);
        if (!stamp.isFile()) {
            return false;
        }
        String want = VelaUtil.assetPayloadStamp(ctx, ASSET_JSC_TAR) + "-jsc";
        String have = VelaUtil.slurpQuietly(stamp);
        return have != null && want.equals(have.trim());
    }

    /**
     * 解 assets/vela/jsc.tar 到 {@code toolchain/jsc}（qemu-x86_64 + Termux 依赖库 +
     * x86_64 glibc sysroot + aiotjsc 真身）。
     *
     * <p>为什么非要有它：工具链的 "Generate jsc bytecode" 要 {@code @aiot-toolkit/jsc}，
     * 而该包自带的 linux 版是 **x86_64 ELF**，arm64 手机上 exec 不了（execSync 抛错被
     * 步骤链吞掉）。缺了它 rpk 里只剩 .js，客机 AIOTJS 加载时报
     * {@code TypeError: bytecode function expected} 然后立刻退出——现象就是"装成功、没画面"。</p>
     */
    public boolean unpackJscBundle(Listener cb) {
        Listener log = cb == null ? new Listener() {
            @Override
            public void onProgress(String phase, int percent, String detail) {
            }
        } : cb;
        if (jscBundleCurrent()) {
            VelaLog.i(TAG, "jsc 工具链已就绪（stamp 匹配）");
            return true;
        }
        if (!hasAsset(ctx, ASSET_JSC_TAR)) {
            VelaLog.w(TAG, "assets/" + ASSET_JSC_TAR + " 不存在，构建会产出没有字节码的 rpk");
            return false;
        }
        log.onProgress("jsc", 2, "解 " + ASSET_JSC_TAR + " …");
        VelaUtil.deleteRecursive(jscDir());
        InputStream in = null;
        try {
            in = ctx.getAssets().open(ASSET_JSC_TAR);
            if (looksGzip(in)) {
                in = new GZIPInputStream(in);
            }
            int n = extractTar(in, jscDir(), log);
            VelaUtil.chmod755(new File(jscDir(), "qemu/bin/qemu-x86_64"));
            VelaUtil.chmod755(new File(jscDir(), "qemu/opt/linux_aiotjsc"));
            VelaUtil.writeText(new File(jscDir(), JSC_STAMP),
                    VelaUtil.assetPayloadStamp(ctx, ASSET_JSC_TAR) + "-jsc");
            log.onProgress("jsc", 95, "解出 " + n + " 个条目");
            VelaLog.i(TAG, "jsc.tar -> " + n + " entries under " + jscDir());
            return n > 0;
        } catch (Throwable t) {
            log.onProgress("error", 0, "解 jsc.tar 失败: " + t);
            VelaLog.w(TAG, "unpack " + ASSET_JSC_TAR + " failed: " + t);
            return false;
        } finally {
            VelaUtil.closeQuietly(in);
        }
    }

    /**
     * 工具链里 {@code @aiot-toolkit/jsc} 的 {@code linux_aiotjsc} 只有一个 x86_64 版，
     * 要用 qemu-user 借壳跑：把它做成指向 jniLibs 静态 stub 的软链接，stub 里写死了
     * {@code <runtime>/toolchain/jsc/...} 这条命令行（见 tools/native/vela-exec-stub.c）。
     *
     * <p>迁移前这里写的是 shell 脚本——应用域连脚本都 exec 不了，所以换成符号链接。</p>
     */
    public boolean patchJscWrapper() {
        File binDir = new File(jscPackageDir(), "lib/jsc");
        File stub = VelaNative.payload(ctx, VelaNative.STUB);
        if (!stub.isFile()) {
            VelaLog.w(TAG, "exec stub 缺失，跳过 jsc wrapper: " + stub);
            return false;
        }
        File wrapper = new File(binDir, "linux_aiotjsc");
        // 老版本写的脚本（真身是 linux_aiotjsc.x86_64）直接清掉；真身不再需要，
        // qemu 跑的是 qemu/opt/linux_aiotjsc。
        File legacy = new File(binDir, "linux_aiotjsc.x86_64");
        if (legacy.isFile()) {
            legacy.delete();
        }
        if (VelaUtil.symlinkPointsTo(wrapper, stub)) {
            return true;
        }
        if (wrapper.exists()) {
            wrapper.delete();
        }
        if (VelaUtil.symlink(stub, wrapper)) {
            VelaLog.i(TAG, "jsc wrapper 就位: " + wrapper + " -> " + stub);
            return true;
        }
        VelaLog.w(TAG, "jsc wrapper 软链接失败: " + wrapper);
        return false;
    }

    /**
     * 工具链要求 {@code <工程>/node_modules/@aiot-toolkit/jsc} 存在（Jsc.js 用
     * requireNodeModule(projectPath, …) 从工程目录加载）。工程导入时跳过 node_modules，
     * 所以这里补一个指向共享工具链的符号链接。
     */
    public void ensureJscModule(File projectDir) {
        if (projectDir == null || jscPackageDir() == null) {
            return;
        }
        File pkgParent = new File(projectDir, "node_modules/@aiot-toolkit");
        File link = new File(pkgParent, "jsc");
        if (link.exists()) {
            return;
        }
        pkgParent.mkdirs();
        if (VelaUtil.symlink(jscPackageDir(), link)) {
            VelaLog.i(TAG, "已链接 " + link + " -> " + jscPackageDir());
        } else {
            VelaLog.w(TAG, "链不上 jsc 包，工具链会报找不到 @aiot-toolkit/jsc");
        }
    }

    /** 找 libnode.so：jniLibs（nativeLibraryDir）→ assets → 投放目录 → 工具链目录。 */
    public File findLibnode() {
        File nativeLib = new File(ctx.getApplicationInfo().nativeLibraryDir, "libnode.so");
        if (nativeLib.isFile()) {
            return nativeLib;
        }
        File[] drop = {new File(dropDir(), "libnode.so"), new File(dropDir(), "node")};
        for (File f : drop) {
            if (f.isFile()) {
                return f;
            }
        }
        File local = new File(nodeLibDir(), "libnode.so");
        if (local.isFile()) {
            return local;
        }
        if (hasAsset(ctx, ASSET_NODE_PREFIX + "/libnode.so")) {
            File out = new File(nodeDir(), "bin/libnode.so");
            try {
                out.getParentFile().mkdirs();
                InputStream in = ctx.getAssets().open(ASSET_NODE_PREFIX + "/libnode.so");
                OutputStream os = new FileOutputStream(out);
                try {
                    VelaUtil.copy(in, os);
                } finally {
                    VelaUtil.closeQuietly(in);
                    VelaUtil.closeQuietly(os);
                }
                return out;
            } catch (IOException e) {
                VelaLog.w(TAG, "释放 assets/" + ASSET_NODE_PREFIX + " 失败: " + e);
            }
        }
        return null;
    }

    /**
     * libnode 的伴生库只要 {@code libc++_shared.so}：从 nativeLibraryDir 拷时把整个
     * 目录搬过来会把 libflutter.so 也拖进来，白占上百 MB。
     */
    private void copyCompanions(File libnode) {
        File parent = libnode.getParentFile();
        File dest = nodeLibDir();
        if (parent == null || parent.equals(dest)) {
            return;
        }
        dest.mkdirs();
        File[] kids = parent.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (!k.isFile() || !k.getName().startsWith("libc++")) {
                continue;
            }
            File out = new File(dest, k.getName());
            if (out.isFile() && out.length() == k.length()) {
                continue;
            }
            try {
                VelaUtil.copyFile(k, out);
                VelaUtil.chmod755(out);
            } catch (IOException e) {
                VelaLog.d(TAG, "伴生库 " + k.getName() + " 没拷动: " + e);
            }
        }
    }

    // ------------------------------------------------------------------- npm

    /** npm 的入口 js（node 自带），或 PATH 上的 npm；都没有返回 null。 */
    public String npmEntry() {
        if (npmEntry != null) {
            return npmEntry.isEmpty() ? null : npmEntry;
        }
        File[] candidates = {
                new File(nodeDir(), "lib/node_modules/npm/bin/npm-cli.js"),
                new File(nodeDir(), "lib_node_modules/npm/bin/npm-cli.js"),
                new File(dir(), "npm-tools/node_modules/npm/bin/npm-cli.js"),
        };
        for (File c : candidates) {
            if (c.isFile()) {
                npmEntry = c.getAbsolutePath();
                return npmEntry;
            }
        }
        String npm = which("npm");
        npmEntry = npm == null ? "" : npm;
        return npm;
    }

    public boolean isNpmAvailable() {
        return node() != null && npmEntry() != null;
    }

    /** 已就绪的 toolkit：工程内或共享目录里能找到 aiot-toolkit 的 package.json。 */
    public boolean isToolkitInstalled(File projectDir) {
        if (toolkitRoot(projectDir) == null) {
            return false;
        }
        // Only the shared offline copy carries a marker, and it is only current
        // while its content matches the stamp of the APK we ship. Without this,
        // an extraction from an older toolkit.tar kept reporting "已装" and the
        // build failed on a module that the new tar had added.
        File marker = new File(toolchainDir(ctx), "toolkit.ok");
        if (!marker.isFile()) {
            return true;
        }
        String want = toolkitStamp();
        String have = VelaUtil.slurpQuietly(marker);
        return have != null && want.equals(have.trim());
    }

    /**
     * 离线工具链的戳：只看 toolkit.tar 的长度（jsc 一起带上，它和工具链同步换）。
     * 旧标记（带 APK 时间的那种）会在这次改动后失效一次 → 重解一遍，之后稳定。
     */
    private String toolkitStamp() {
        return VelaUtil.assetPayloadStamp(ctx, ASSET_TOOLKIT_TAR, ASSET_JSC_TAR);
    }

    /** toolkit 包根目录（含 package.json），找不到返回 null。 */
    public File toolkitRoot(File projectDir) {
        File[] roots = projectDir == null
                ? new File[]{sharedModules()}
                : new File[]{new File(projectDir, "node_modules"), sharedModules()};
        for (File root : roots) {
            File pkg = new File(root, TOOLKIT_PACKAGE + "/package.json");
            if (pkg.isFile()) {
                return pkg.getParentFile();
            }
        }
        return null;
    }

    public static boolean hasLocalToolkit(File projectDir) {
        return new File(projectDir, "node_modules/" + TOOLKIT_PACKAGE + "/package.json").isFile();
    }

    public String toolkitVersion(File projectDir) {
        File root = toolkitRoot(projectDir);
        if (root == null) {
            return "";
        }
        try {
            return new JSONObject(VelaUtil.slurp(new File(root, "package.json"))).optString("version", "");
        } catch (Exception e) {
            return "?";
        }
    }

    // -------------------------------------------------------------- install

    /**
     * 装 toolkit：离线包优先，其次在线 npm，都没有就如实报告。
     *
     * @param projectDir 传 null 表示装到共享工具链目录
     */
    public Map<String, Object> installToolkit(File projectDir, Listener cb) {
        Map<String, Object> r = new LinkedHashMap<>();
        String target = projectDir == null ? toolchainDir(ctx).getAbsolutePath()
                : projectDir.getAbsolutePath();
        r.put("target", target);
        r.put("project", projectDir == null ? null : projectDir.getName());
        Listener log = cb == null ? new Listener() {
            @Override
            public void onProgress(String phase, int percent, String detail) {
            }
        } : cb;

        if (isToolkitInstalled(projectDir)) {
            // Same predicate as the status card: a tree whose marker no longer
            // matches this APK is not "already there" -- it has to be re-extracted
            // from the current toolkit.tar.
            log.onProgress("verify", 100, "已存在 " + TOOLKIT_PACKAGE + " "
                    + toolkitVersion(projectDir));
            r.put("ok", true);
            r.put("installed", true);
            r.put("alreadyPresent", true);
            r.put("toolkitVersion", toolkitVersion(projectDir));
            r.put("via", "already");
            return r;
        }
        if (!isNodeAvailable()) {
            log.onProgress("error", 0, missingWhy);
            r.put("ok", false);
            r.put("installed", false);
            r.put("nodeAvailable", false);
            r.put("toolkitInstalled", false);
            r.put("error", missingWhy);
            return r;
        }

        File marker = new File(toolchainDir(ctx), "toolkit.ok");
        String stamp = toolkitStamp();
        if (marker.isFile() && stamp.trim().equals(VelaUtil.slurp(marker).trim())
                && toolkitRoot(null) != null) {
            log.onProgress("verify", 100, "离线工具链已就绪");
            r.put("ok", true);
            r.put("installed", true);
            r.put("via", "offline-marker");
            return r;
        }

        if (unpackBundledToolkit(log)) {
            VelaUtil.writeText(marker, stamp);
            // 字节码编译器：没有它 rpk 里只有 .js，客机会装得上、起不来。
            unpackJscBundle(log);
            patchJscWrapper();
            boolean ok = toolkitRoot(projectDir) != null;
            log.onProgress("done", 100, "离线包解出完成，" + TOOLKIT_PACKAGE + " "
                    + toolkitVersion(projectDir));
            r.put("ok", ok);
            r.put("installed", ok);
            r.put("via", "asset-tar");
            r.put("toolkitVersion", toolkitVersion(projectDir));
            if (!ok) {
                r.put("error", "toolkit.tar 解完了，但里面没有 " + TOOLKIT_PACKAGE
                        + "/package.json —— 打包时请把 node_modules 放进 tar 根目录");
            }
            return r;
        }

        String npm = npmEntry();
        if (npm == null) {
            String why = "设备上既没有离线包 assets/" + ASSET_TOOLKIT_TAR + "，也没有 npm："
                    + "请把 aiot-toolkit 的 node_modules 打成 toolkit.tar 放进 APK，"
                    + "或在 " + dropDir().getAbsolutePath() + " 放一份 node+npm";
            log.onProgress("error", 0, why);
            r.put("ok", false);
            r.put("installed", false);
            r.put("npmAvailable", false);
            r.put("error", why);
            return r;
        }

        log.onProgress("npm", 5, "npm install " + TOOLKIT_PACKAGE + " " + UX_TYPES + " …");
        List<String> argv = new ArrayList<>();
        File node = node();
        argv.addAll(npmArgv(node, npm));
        argv.add("install");
        argv.add("--no-audit");
        argv.add("--no-fund");
        argv.add("--loglevel=warn");
        if (projectDir == null) {
            argv.add("--prefix");
            argv.add(toolchainDir(ctx).getAbsolutePath());
        } else {
            argv.add("--save-dev");
        }
        argv.add(TOOLKIT_PACKAGE + "@" + TOOLKIT_RANGE);
        argv.add(UX_TYPES);
        final int[] lines = {0};
        final Listener flog = log;
        int code = exec(argv, new File(target), envFor(npm), new LineSink() {
            @Override
            public void onLine(String line, boolean stderr) {
                lines[0]++;
                flog.onProgress("npm", Math.min(95, 5 + lines[0] / 3), line);
            }
        }, NPM_TIMEOUT_MS);
        boolean ok = code == 0 && toolkitRoot(projectDir) != null;
        r.put("ok", ok);
        r.put("installed", ok);
        r.put("via", "npm");
        r.put("exitCode", code);
        r.put("npmLines", lines[0]);
        r.put("toolkitVersion", toolkitVersion(projectDir));
        if (ok) {
            log.onProgress("done", 100, "安装完成 " + TOOLKIT_PACKAGE + " "
                    + toolkitVersion(projectDir));
        } else {
            String why = code == 0
                    ? "npm 跑完了但找不到 " + TOOLKIT_PACKAGE + "/package.json（网络或 registry 问题）"
                    : "npm install 失败（退出码 " + code + "）。多半是设备无法访问 registry.npmjs.org；"
                            + "可改用离线包 assets/" + ASSET_TOOLKIT_TAR;
            log.onProgress("error", 0, why);
            r.put("error", why);
        }
        return r;
    }

    /** npm 可以是一个 js（用 node 跑）也可以是 PATH 上的脚本。 */
    private List<String> npmArgv(File node, String npm) {
        List<String> argv = new ArrayList<>();
        if (npm.endsWith(".js")) {
            argv.addAll(withLoader(node, npm));
            return argv;
        }
        argv.add(npm);
        return argv;
    }

    /**
     * 解离线工具链包到工具链目录（tar 根里的 {@code node_modules/…} 直接落位）。
     *
     * @return 资产里没有包时 false
     */
    public boolean unpackBundledToolkit(Listener cb) {
        String asset = hasAsset(ctx, ASSET_TOOLKIT_TAR) ? ASSET_TOOLKIT_TAR
                : hasAsset(ctx, ASSET_TOOLKIT_TGZ) ? ASSET_TOOLKIT_TGZ : null;
        if (asset == null) {
            VelaLog.i(TAG, "assets/" + ASSET_TOOLKIT_TAR + " 不存在，跳过离线解包");
            return false;
        }
        Listener log = cb == null ? new Listener() {
            @Override
            public void onProgress(String phase, int percent, String detail) {
            }
        } : cb;
        log.onProgress("tar", 2, "解 " + asset + " …");
        InputStream in = null;
        try {
            in = ctx.getAssets().open(asset);
            if (looksGzip(in)) {
                in = new GZIPInputStream(in);
            }
            int n = extractTar(in, toolchainDir(ctx), log);
            log.onProgress("tar", 90, "解出 " + n + " 个条目");
            VelaLog.i(TAG, "toolkit.tar -> " + n + " entries under " + toolchainDir(ctx));
            return n > 0;
        } catch (Throwable t) {
            log.onProgress("error", 0, "解 toolkit.tar 失败: " + t);
            VelaLog.w(TAG, "unpack " + asset + " failed: " + t);
            return false;
        } finally {
            VelaUtil.closeQuietly(in);
        }
    }

    private static boolean looksGzip(InputStream in) throws IOException {
        if (!in.markSupported()) {
            return false;
        }
        in.mark(ASSET_PROBE_CHUNK);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        return b0 == 0x1f && b1 == 0x8b;
    }

    // ------------------------------------------------------------ process exec

    /** 给 node/npm/aiot 用的环境：HOME 必须是可写的私有目录。 */
    public Map<String, String> envFor(String extraPathDir) {
        Map<String, String> env = new LinkedHashMap<>();
        File home = toolchainDir(ctx);
        home.mkdirs();
        List<String> path = new ArrayList<>();
        addPath(path, nodeDir());
        addPath(path, new File(nodeDir(), "bin"));
        addPath(path, sharedBin());
        addPath(path, new File(home, "node_modules/.bin"));
        addPath(path, new File(home, "bin"));
        if (extraPathDir != null) {
            File parent = new File(extraPathDir).getParentFile();
            addPath(path, parent);
        }
        addPath(path, new File("/system/bin"));
        addPath(path, new File("/system/xbin"));
        addPath(path, new File("/vendor/bin"));
        env.put("HOME", home.getAbsolutePath());
        env.put("PREFIX", home.getAbsolutePath());
        env.put("PATH", VelaUtil.join(":", path));
        env.put("TMPDIR", new File(ctx.getCacheDir(), "vela-toolchain").getAbsolutePath());
        env.put("npm_config_cache", new File(home, ".npm").getAbsolutePath());
        env.put("npm_config_update_notifier", "false");
        env.put("NODE_PATH", sharedModules().getAbsolutePath());
        // Only the node bundle's own sonames: the bundled node is bionic, and the
        // glibc dir holds a text-format `libc.so` linker script -- putting it on
        // LD_LIBRARY_PATH made bionic resolve node's own NEEDED libc.so to that
        // script and die with "has bad ELF magic: 2f2a2047" ("/* G"). Glibc
        // binaries are started through the loader with an explicit
        // --library-path instead (withLoader), so nothing needs it here.
        env.put("LD_LIBRARY_PATH", nodeLibDir().getAbsolutePath());
        env.put("LC_ALL", "C");
        return env;
    }

    private static void addPath(List<String> path, File dir) {
        if (dir != null) {
            String s = dir.getAbsolutePath();
            if (!path.contains(s)) {
                path.add(s);
            }
        }
    }

    /**
     * 跑一条命令并把输出逐行交出去；返回退出码，超时返回 -1。
     *
     * <p>和 {@link VelaUtil#run} 的区别就是这条要边跑边喂 UI（构建日志、npm 进度），
     * 所以两路输出各起一个泵线程。</p>
     */
    public static int exec(List<String> argv, File cwd, Map<String, String> env,
                           LineSink sink, long timeoutMs) {
        Process p = null;
        try {
            // 直接在本进程起：argv[0] 是 jniLibs 里的 glibc loader（apk_data_file，
            // 应用域允许 exec），node/npm/aiot 由它 mmap 私有目录里的真身跑起来。
            ProcessBuilder pb = new ProcessBuilder(argv);
            if (cwd != null && cwd.isDirectory()) {
                pb.directory(cwd);
            }
            if (env != null) {
                pb.environment().clear();
                for (Map.Entry<String, String> e : env.entrySet()) {
                    if (e.getValue() != null && !e.getValue().isEmpty()) {
                        pb.environment().put(e.getKey(), e.getValue());
                    }
                }
            }
            Context c = appCtx;
            if (c != null) {
                pb.environment().put(VelaNative.RUNTIME_ENV,
                        VelaPaths.runtimeDir(c).getAbsolutePath());
            }
            p = pb.start();
            VelaLog.i(TAG, "exec " + VelaUtil.join(" ", argv) + " cwd=" + cwd);
            pump(p.getInputStream(), false, sink);
            pump(p.getErrorStream(), true, sink);
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy();
                p.waitFor(2, TimeUnit.SECONDS);
                if (sink != null) {
                    sink.onLine("!! 超时 " + (timeoutMs / 1000) + "s，已终止", true);
                }
                return -1;
            }
            return p.exitValue();
        } catch (Throwable t) {
            if (sink != null) {
                sink.onLine("!! 启动失败: " + t, true);
            }
            VelaLog.w(TAG, "exec failed: " + t);
            return -1;
        } finally {
            if (p != null) {
                VelaUtil.closeQuietly(p.getOutputStream());
                VelaUtil.closeQuietly(p.getInputStream());
                VelaUtil.closeQuietly(p.getErrorStream());
            }
        }
    }

    /** targetSdk of this build（诊断用）。 */
    public static int targetSdk() {
        try {
            return appCtx.getApplicationInfo().targetSdkVersion;
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void pump(final InputStream stream, final boolean stderr, final LineSink sink) {
        if (stream == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int n;
                try {
                    while ((n = stream.read(buf)) > 0) {
                        for (int i = 0; i < n; i++) {
                            byte b = buf[i];
                            if (b == '\n' || b == '\r') {
                                flush(line, stderr, sink);
                            } else {
                                line.write(b);
                            }
                        }
                    }
                    flush(line, stderr, sink);
                } catch (IOException e) {
                    VelaLog.d(TAG, "output pump ended: " + e);
                }
            }
        }, "vela-exec-pump");
        t.setDaemon(true);
        t.start();
    }

    private static void flush(ByteArrayOutputStream line, boolean stderr, LineSink sink) {
        if (line.size() == 0) {
            return;
        }
        String s = new String(line.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
        line.reset();
        if (sink != null && !s.trim().isEmpty()) {
            sink.onLine(s, stderr);
        }
    }

    // ---------------------------------------------------------- build argv

    /**
     * 解析构建命令：优先工程内 {@code aiot}，其次共享 toolkit 的 {@code aiot}，
     * 再退到 {@code hap}（模板 package.json 的 script 用的是 hap）。
     *
     * @return argv，或 null（不可用）
     */
    public List<String> buildArgv(File projectDir, String task) {
        File node = node();
        if (node == null) {
            return null;
        }
        String t = task == null || task.isEmpty() ? "build" : task;
        // 客机 JS 运行时只认 QuickJS 字节码：不带 --enable-jsc 打出来的 rpk 装得上、
        // 一启动就 "bytecode function expected" 自杀（详见 unpackJscBundle）。
        boolean wantJsc = "build".equals(t) || "release".equals(t);
        if (wantJsc) {
            unpackJscBundle(null);
            patchJscWrapper();
            ensureJscModule(projectDir);
        }
        File[] roots = {new File(projectDir, "node_modules"), sharedModules()};
        String[] bins = {TOOLKIT_PACKAGE, "hap-toolkit"};
        for (File root : roots) {
            for (String bin : bins) {
                File pkgDir = new File(root, bin);
                File entry = binEntry(pkgDir);
                if (entry != null) {
                    List<String> toolArgs = new ArrayList<>();
                    toolArgs.add(entry.getAbsolutePath());
                    toolArgs.add(t);
                    if (wantJsc) {
                        toolArgs.add("--enable-jsc");
                    }
                    return new ArrayList<>(withLoader(node, toolArgs.toArray(new String[0])));
                }
            }
        }
        // 都没有 node_modules，但 PATH 上有 aiot/hap 的 shell wrapper（Termux）
        String onPath = which("aiot");
        if (onPath == null) {
            onPath = which("hap");
        }
        if (onPath != null) {
            List<String> argv = new ArrayList<>();
            argv.add("/system/bin/sh");
            argv.add("-c");
            argv.add(onPath + " " + t);
            return argv;
        }
        return null;
    }

    /** package.json 的 bin 字段 -&gt; 入口 js（bin 名 aiot / hap）。 */
    private File binEntry(File pkgDir) {
        File manifest = new File(pkgDir, "package.json");
        if (!manifest.isFile()) {
            return null;
        }
        try {
            JSONObject pkg = new JSONObject(VelaUtil.slurp(manifest));
            Object bin = pkg.opt("bin");
            String rel = null;
            if (bin instanceof JSONObject) {
                JSONObject b = (JSONObject) bin;
                rel = b.optString(pkgDir.getName(), null);
                if (rel == null && b.length() > 0) {
                    rel = b.optString(b.keys().next(), null);
                }
            } else if (bin instanceof String) {
                rel = (String) bin;
            }
            if (rel == null) {
                File guess = new File(pkgDir, "bin/" + pkgDir.getName() + ".js");
                return guess.isFile() ? guess : null;
            }
            File entry = new File(pkgDir, rel.replace('\\', '/'));
            if (entry.isFile()) {
                return entry;
            }
            VelaLog.w(TAG, "bin 入口不存在: " + entry);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 有没有 node 但没 toolkit？给出中文原因，供 buildProject 直接回给 UI。 */
    public String missingWhy(File projectDir) {
        if (projectDir == null) {
            projectDir = toolchainDir(ctx);
        }
        if (node() == null) {
            return missingWhy == null ? "设备上没有可用的 node" : missingWhy;
        }
        if (toolkitRoot(projectDir) == null) {
            return "aiot-toolkit 还没装：在工程 " + projectDir.getName() + " 里执行过安装工具链了吗？"
                    + "（有 npm 走 npm install，没 npm 就把 assets/" + ASSET_TOOLKIT_TAR
                    + " 打进 APK；也可以手动把 node_modules 放到 " + sharedModules() + "）";
        }
        return "找不到可用的 aiot/hap 入口脚本（node_modules/" + TOOLKIT_PACKAGE
                + "/package.json 的 bin 字段指向的文件不存在）";
    }

    /** {@code command -v}：只用来发现 PATH 上已有的 node/npm/aiot。 */
    public String which(String name) {
        if (checkedSystemNode && "node".equals(name)) {
            return systemNode;
        }
        String out = "";
        List<String> argv = new ArrayList<>();
        argv.add("/system/bin/sh");
        argv.add("-c");
        argv.add("command -v " + name + " 2>/dev/null");
        final StringBuilder sb = new StringBuilder();
        exec(argv, toolchainDir(ctx), envFor(null), new LineSink() {
            @Override
            public void onLine(String line, boolean stderr) {
                if (line.startsWith("/")) {
                    sb.append(line);
                }
            }
        }, 8000);
        out = sb.toString().trim();
        String found = out.isEmpty() ? null : out;
        if ("node".equals(name)) {
            checkedSystemNode = true;
            systemNode = found;
        }
        return found;
    }

    // ---------------------------------------------------------------- status

    /** {@code toolchain} 事件与 {@code devStatus} 的数据源。 */
    public Map<String, Object> status(File projectDir) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean nodeOk = isNodeAvailable();
        File bin = node();
        m.put("nodeAvailable", nodeOk);
        m.put("nodePath", bin == null ? null : bin.getAbsolutePath());
        m.put("nodeVersion", nodeVersion);
        m.put("nodeIsGlibc", bin != null && glibcLoader() != null && !VelaUtil.isNativeLauncher(bin));
        m.put("libnodePath", findLibnodePathForStatus());
        m.put("npmAvailable", nodeOk && npmEntry() != null);
        m.put("npmEntry", npmEntry());
        boolean toolkit = toolkitRoot(projectDir) != null;
        m.put("toolkitInstalled", toolkit);
        m.put("toolkitVersion", toolkitVersion(projectDir));
        m.put("toolkitRoot", toolkit ? toolkitRoot(projectDir).getAbsolutePath() : null);
        m.put("toolkitPackage", TOOLKIT_PACKAGE + "@" + TOOLKIT_VERSION);
        m.put("offlineTar", hasAsset(ctx, ASSET_TOOLKIT_TAR) || hasAsset(ctx, ASSET_TOOLKIT_TGZ));
        m.put("toolchainDir", toolchainDir(ctx).getAbsolutePath());
        m.put("dropDir", dropDir().getAbsolutePath());
        m.put("dropDirExists", dropDir().isDirectory());
        m.put("bytes", VelaImageStore.dirSize(toolchainDir(ctx)));
        m.put("execMode", execMode());
        m.put("targetSdk", targetSdk());
        m.put("error", nodeOk && toolkit ? null : (toolkit ? null : missingWhy(projectDir)));
        return m;
    }

    private String findLibnodePathForStatus() {
        File nativeLib = new File(ctx.getApplicationInfo().nativeLibraryDir, "libnode.so");
        if (nativeLib.isFile()) {
            return nativeLib.getAbsolutePath();
        }
        return hasAsset(ctx, ASSET_NODE_PREFIX + "/libnode.so") ? "assets/" + ASSET_NODE_PREFIX : null;
    }

    /** assets 里有没有某个文件（{@code open} 比 {@code list} 可靠）。 */
    public static boolean hasAsset(Context ctx, String path) {
        AssetManager am = ctx.getAssets();
        InputStream in = null;
        try {
            in = am.open(path);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            VelaUtil.closeQuietly(in);
        }
    }

    // ------------------------------------------------------------ tiny tar io

    /**
     * 解 ustar/gnu tar（可 gzip），支持目录、长名（{@code 'L'}）、软链。
     *
     * <p>npm 的 node_modules 里路径轻松超 100 字符，所以 GNU longname 必须处理；
     * 每个条目的落地路径都要过一遍 canonical 检查，防止 {@code ../} 出目录。</p>
     */
    public static int extractTar(InputStream raw, File destRoot, Listener cb) throws IOException {
        TarInput blockIn = new TarInput(raw);
        byte[] header = new byte[512];
        String longName = null;
        Map<String, String> paxNext = null;
        Map<String, String> paxDefaults = null;
        int count = 0;
        File dest = destRoot.getCanonicalFile();
        dest.mkdirs();
        while (true) {
            if (!blockIn.readFully(header)) {
                break;
            }
            if (isZeroBlock(header)) {
                continue;
            }
            String name = tarString(header, 0, 100);
            if (name.isEmpty()) {
                break;
            }
            long size = tarOctal(header, 124, 12);
            char type = (char) (header[156] & 0xff);
            int mode = (int) tarOctal(header, 100, 8);
            String link = tarString(header, 157, 100);
            String prefix = tarString(header, 345, 155);
            if (type == 'L') {
                byte[] body = blockIn.readBytes((int) roundUp(size));
                longName = new String(body, 0, (int) Math.min(size, body.length), "UTF-8").trim();
                continue;
            }
            if (type == 'x' || type == 'g') {
                // PAX records ('x' for the next entry, 'g' for all of them). Python's
                // tarfile stores long paths here and truncates the ustar name field,
                // so ignoring them silently dropped every deep file: the bundled
                // toolkit lost e.g.
                // @babel/plugin-bugfix-safari-id-destructuring-collision-in-function-expression/*
                // and the build died with MODULE_NOT_FOUND.
                byte[] body = blockIn.readBytes((int) roundUp(size));
                Map<String, String> rec = parsePax(body, (int) Math.min(size, body.length));
                if (type == 'g') {
                    if (paxDefaults == null) {
                        paxDefaults = new LinkedHashMap<>();
                    }
                    paxDefaults.putAll(rec);
                } else {
                    paxNext = rec;
                }
                continue;
            }
            if (type == 'X') {
                blockIn.readBytes((int) roundUp(size));
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            } else if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            String paxPath = null;
            long paxSize = -1;
            for (Map<String, String> rec : new Map[]{paxDefaults, paxNext}) {
                if (rec == null) {
                    continue;
                }
                if (rec.containsKey("path")) {
                    paxPath = rec.get("path");
                }
                if (rec.containsKey("linkpath")) {
                    link = rec.get("linkpath");
                }
                if (rec.containsKey("size")) {
                    try {
                        paxSize = Long.parseLong(rec.get("size").trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            paxNext = null;
            if (paxPath != null) {
                name = paxPath;
            }
            if (paxSize >= 0) {
                size = paxSize;
            }
            File out = new File(dest, name.replace('\\', '/'));
            String outPath = out.getCanonicalPath();
            if (!outPath.equals(dest.getPath()) && !outPath.startsWith(dest.getPath() + File.separator)) {
                if (cb != null) {
                    cb.onProgress("tar", 40, "跳过越界条目 " + name);
                }
                blockIn.readBytes((int) roundUp(size));
                continue;
            }
            if (type == '5' || name.endsWith("/")) {
                out.mkdirs();
                count++;
                continue;
            }
            if (type == '1' || type == '2') {
                out.getParentFile().mkdirs();
                File target = type == '1' ? new File(out.getParentFile(), link) : new File(link);
                if (!VelaUtil.symlink(target, out)) {
                    blockIn.readBytes((int) roundUp(size));
                    continue;
                }
                count++;
                continue;
            }
            if (type != '0' && type != 0 && type != '7') {
                blockIn.readBytes((int) roundUp(size));
                continue;
            }
            out.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(out);
            try {
                long left = size;
                byte[] buf = new byte[64 * 1024];
                while (left > 0) {
                    int n = blockIn.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) {
                        break;
                    }
                    os.write(buf, 0, n);
                    left -= n;
                }
                long pad = roundUp(size) - size;
                if (pad > 0) {
                    blockIn.readBytes((int) pad);
                }
            } finally {
                VelaUtil.closeQuietly(os);
            }
            // tar 里的 mode 决定要不要留执行位（.bin 与 node 二进制靠它）
            if ((mode & 0100) != 0) {
                VelaUtil.chmod755(out);
            }
            count++;
            if (cb != null && count % 200 == 0) {
                cb.onProgress("tar", 40, "已解 " + count + " 个文件");
            }
        }
        return count;
    }

    /**
     * PAX record body: {@code "<len> <key>=<value>\n"} repeated, where {@code len}
     * counts the whole record including its own digits and the newline.
     */
    private static Map<String, String> parsePax(byte[] body, int len) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 0;
        while (i < len) {
            int sp = -1;
            for (int j = i; j < len; j++) {
                if (body[j] == ' ') {
                    sp = j;
                    break;
                }
            }
            if (sp < 0) {
                break;
            }
            int recordLen;
            try {
                recordLen = Integer.parseInt(
                        new String(body, i, sp - i, java.nio.charset.StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (recordLen <= 0 || i + recordLen > len) {
                break;
            }
            int valueStart = sp + 1;
            int valueEnd = i + recordLen - 1; // drop the trailing newline
            if (valueEnd > valueStart) {
                String kv = new String(body, valueStart, valueEnd - valueStart,
                        java.nio.charset.StandardCharsets.UTF_8);
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    out.put(kv.substring(0, eq), kv.substring(eq + 1));
                }
            }
            i += recordLen;
        }
        return out;
    }

    private static long roundUp(long size) {
        long rem = size % 512;
        return rem == 0 ? size : size + (512 - rem);
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String tarString(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) {
            end++;
        }
        return new String(h, off, end - off, java.nio.charset.Charset.forName("UTF-8")).trim();
    }

    private static long tarOctal(byte[] h, int off, int len) {
        String s = tarString(h, off, len);
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s.trim(), 8);
        } catch (NumberFormatException e) {
            // GNU base-256：最高位为 1
            if ((h[off] & 0x80) != 0) {
                long v = 0;
                for (int i = 0; i < len; i++) {
                    v = (v << 8) | (h[off + i] & 0xff);
                }
                return v;
            }
            return 0;
        }
    }

    /** 块对齐读取：tar 的所有内容都以 512 字节块推进。 */
    private static final class Buffered extends Filter {
        Buffered(InputStream in) {
            super(in);
        }
    }

    private static class Filter extends InputStream {
        final InputStream src;

        Filter(InputStream src) {
            this.src = src;
        }

        @Override
        public int read() throws IOException {
            return src.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return src.read(b, off, len);
        }

        boolean readFully(byte[] b) throws IOException {
            int got = 0;
            while (got < b.length) {
                int n = read(b, got, b.length - got);
                if (n < 0) {
                    return false;
                }
                got += n;
            }
            return true;
        }

        byte[] readBytes(int n) throws IOException {
            byte[] out = new byte[Math.max(0, n)];
            int got = 0;
            while (got < out.length) {
                int r = read(out, got, out.length - got);
                if (r < 0) {
                    break;
                }
                got += r;
            }
            return out;
        }
    }
}
