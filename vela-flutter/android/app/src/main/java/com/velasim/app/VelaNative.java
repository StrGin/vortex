package com.velasim.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 可执行负载的落点：APK 的 native 库目录。
 *
 * <p>背景：本应用 targetSdk 36，SELinux 的 {@code untrusted_app} 域禁止对本应用私有目录
 * （标签 {@code app_data_file}）里的文件做 {@code execve} / {@code mmap(PROT_EXEC)}，
 * 所以引擎与工具链不能放在 {@code <files>/vela} 下直接执行。而安装器解出来的
 * {@code /data/app/&lt;pkg&gt;/lib/&lt;abi&gt;/}（标签
 * {@code apk_data_file}）本来就允许本进程执行——这是 Android 给「App 跑自己的原生库」留的正路。
 *
 * <p>因此：所有需要执行或 mmap 成可执行的 ELF 都随 APK 走 {@code jniLibs}，命名
 * {@code libvela_<slug>.so}；私有目录里只放数据（node_modules、皮肤、镜像…），
 * 代码路径则用软链接指回本目录，保持引擎自己的相对路径查找不变。
 */
public final class VelaNative {

    public static final String PROBE_TAG = "vela-native";

    /** 所有负载都以这个前缀命名，便于 AGP 的 strip 白名单与自检。 */
    public static final String PREFIX = "libvela_";

    /** 内置 glibc loader 的负载名（无 PT_INTERP，可直接 exec）。 */
    public static final String LD = "glibc_lib_ld_linux_aarch64_so_1";

    /** 私有目录里那份 loader 的文件名（{@link VelaEngine#LOADER_NAME} 的副本）。 */
    public static final String LOADER_NAME = "ld-linux-aarch64.so.1";

    /** 内核直接 exec 的 trampoline（静态、无 PT_INTERP），见 {@code tools/native/vela-exec-stub.c}。 */
    public static final String STUB = "vela_exec_stub";

    /** 传给 stub 的运行时根目录（它据此拼出 app 私有目录里的负载路径）。 */
    public static final String RUNTIME_ENV = "VELA_RUNTIME_DIR";

    /** loader 与引擎/工具链一致的库搜索路径（都在私有目录，stub 与 Java 共用同一份）。 */
    public static String libPath(File runtimeDir) {
        File e = new File(runtimeDir, "engine");
        return new File(e, "glibc/lib").getAbsolutePath()
                + ":" + new File(e, "engine/lib64").getAbsolutePath()
                + ":" + new File(e, "engine").getAbsolutePath()
                + ":" + new File(e, "engine/lib64/gles_swiftshader").getAbsolutePath()
                // Qt 版二进制只在 -qt-hide-window 下会被 launcher 选中；带上它的目录，
                // 免得那条路径报 "libQt5SvgAndroidEmu.so.5: cannot open shared object file"。
                + ":" + new File(e, "engine/lib64/qt/lib").getAbsolutePath();
    }

    private VelaNative() {
    }

    /** {@code /data/app/&lt;pkg&gt;/lib/&lt;abi&gt;}。 */
    public static File nativeDir(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    /** 负载文件：{@code nativeDir/libvela_<slug>.so}。 */
    public static File payload(Context ctx, String slug) {
        return new File(nativeDir(ctx), PREFIX + slug + ".so");
    }

    /** 负载是否已就位且可执行。 */
    public static boolean ready(Context ctx, String slug) {
        File f = payload(ctx, slug);
        return f.isFile() && f.canExecute();
    }

    // --------------------------------------------------------------- 自检探针

    /**
     * 启动自检：jniLibs 里的 loader 能不能被本进程执行。
     *
     * <p>这是整套方案的前提——应用域只允许执行 {@code apk_data_file}（APK 的 lib 目录），
     * 私有目录里的文件一律 EACCES。结果进 logcat（tag {@link #PROBE_TAG}）。</p>
     */
    public static List<String> probe(Context ctx) {
        List<String> out = new ArrayList<>();
        File nd = nativeDir(ctx);
        File ld = payload(ctx, LD);
        out.add("nativeDir=" + nd.getAbsolutePath() + " exists=" + nd.isDirectory());
        out.add("loader=" + ld.getAbsolutePath() + " ok=" + (ld.isFile() && ld.canExecute()));
        out.add("stub=" + payload(ctx, STUB).getAbsolutePath()
                + " ok=" + ready(ctx, STUB));
        if (ld.isFile() && ld.canExecute()) {
            out.addAll(exec("loader-exec", Arrays.asList(ld.getAbsolutePath(), "--version"), 8000));
        }
        for (String line : out) {
            VelaLog.i(PROBE_TAG, line);
        }
        return out;
    }

    /** 探针结果会不会太长：只留前若干字符，避免日志环被刷爆。 */
    private static List<String> exec(String name, List<String> argv, long timeoutMs) {
        return exec(name, argv, timeoutMs, null);
    }

    private static List<String> exec(String name, List<String> argv, long timeoutMs,
                                     Map<String, String> extraEnv) {
        List<String> out = new ArrayList<>();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            if (extraEnv != null) {
                pb.environment().putAll(extraEnv);
            }
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() < 1200) {
                        sb.append(line).append(" | ");
                    }
                }
            }
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            String head = sb.toString().trim();
            if (head.length() > 700) {
                head = head.substring(0, 700) + "…";
            }
            out.add(name + ": exit=" + (done ? p.exitValue() : "timeout")
                    + " out=[" + head + "]");
        } catch (Exception e) {
            out.add(name + ": FAILED " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return out;
    }
}
