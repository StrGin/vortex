package com.velasim.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.os.StatFs;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small io/asset/elf helpers shared by the rest of the package. */
public final class VelaUtil {

    private static final int COPY_BUF = 64 * 1024;

    private VelaUtil() {
    }

    // ------------------------------------------------------------------- io

    public static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[COPY_BUF];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        out.flush();
        return total;
    }

    public static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, in.available()));
        copy(in, out);
        return out.toByteArray();
    }

    public static String readStream(InputStream in, int cap) {
        if (in == null) {
            return "";
        }
        try {
            byte[] all = readBytes(in);
            int n = Math.min(all.length, cap);
            return new String(all, 0, n, "UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    /** Never throws; empty string when unreadable. */
    public static String slurp(File f) {
        String s = slurpQuietly(f);
        return s == null ? "" : s;
    }

    /** Null when the file is missing or unreadable. */
    public static String slurpQuietly(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] all = readBytes(in);
            return new String(all, 0, Math.min(all.length, 4 * 1024 * 1024), "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    public static String firstLine(File f) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String s = r.readLine();
            return s == null ? "" : s;
        } finally {
            r.close();
        }
    }

    public static boolean writeText(File f, String content) {
        Writer w = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(content);
            w.flush();
            return true;
        } catch (IOException e) {
            VelaLog.w("io", "cannot write " + f + ": " + e);
            return false;
        } finally {
            closeQuietly(w);
        }
    }

    /** Appends, creating the file and parents; used for the channel log. */
    public static boolean writeTextAppend(File f, String content) {
        Writer w = null;
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            w.write(content);
            w.flush();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(w);
        }
    }

    /** Truncates (or creates) a file. */
    public static boolean clear(File f) {
        if (f == null) {
            return false;
        }
        if (!f.exists()) {
            try {
                return f.createNewFile();
            } catch (IOException e) {
                return false;
            }
        }
        OutputStream os = null;
        try {
            os = new FileOutputStream(f);
            os.write(0);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            closeQuietly(os);
        }
    }

    public static boolean copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), COPY_BUF);
        try {
            copy(in, out);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        return dst.isFile();
    }

    public static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) {
                throw new IOException("cannot create " + dst);
            }
            File[] kids = src.listFiles();
            if (kids == null) {
                return;
            }
            for (File k : kids) {
                copyTree(k, new File(dst, k.getName()));
            }
            return;
        }
        copyFile(src, dst);
    }

    public static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return true;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        return f.delete();
    }

    /** Deletes a symlink or an empty directory; refuses to follow links. */
    public static boolean deleteLinkOrDir(File f) {
        if (f == null || !f.exists() && !isLink(f)) {
            return true;
        }
        if (isLink(f) || f.isFile()) {
            return f.delete();
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteLinkOrDir(k);
                }
            }
            return f.delete();
        }
        return false;
    }

    /** 是否为（可能已失效的）符号链接——失效链接的 {@code exists()} 是 false。 */
    public static boolean isLink(File f) {
        try {
            String s = android.system.Os.readlink(f.getAbsolutePath());
            return s != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /** chmod 0755 -- zip/sdcard round-trips drop the exec bits we need. */
    public static void chmod755(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        try {
            android.system.Os.chmod(f.getAbsolutePath(),
                    android.system.OsConstants.S_IRUSR | android.system.OsConstants.S_IWUSR
                            | android.system.OsConstants.S_IXUSR
                            | android.system.OsConstants.S_IRGRP | android.system.OsConstants.S_IXGRP
                            | android.system.OsConstants.S_IROTH | android.system.OsConstants.S_IXOTH);
        } catch (Throwable t) {
            f.setExecutable(true, false);
            f.setReadable(true, false);
        }
    }

    /** Last {@code lines} lines, or the whole file when shorter. */
    public static String tail(File f, int lines) {
        String text = slurpQuietly(f);
        if (text == null) {
            return "";
        }
        String[] all = text.split("\r?\n");
        int from = Math.max(0, all.length - Math.max(1, lines));
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < all.length; i++) {
            sb.append(all[i]).append('\n');
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------- text

    public static String ellipsize(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...(+" + (s.length() - max) + " B)";
    }

    public static String join(String sep, List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Shell-ish word split for a free-form "extra args" field. */
    public static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    public static String shellQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                sb.append("'\\''");
            } else {
                sb.append(c);
            }
        }
        return sb.append('\'').toString();
    }

    public static String formatBytes(long b) {
        if (b < 0) {
            return "?";
        }
        if (b < 1024) {
            return b + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b;
        int u = -1;
        while (v >= 1024 && u + 1 < units.length) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.US, u < 0 ? "%d B" : "%.1f %s", v, units[u]);
    }

    // --------------------------------------------------------------- assets

    /** Relative paths below {@code prefix}, recursing through directories. */
    public static List<String> listAssetsRecursive(AssetManager am, String prefix) throws IOException {
        List<String> out = new ArrayList<>();
        collectAssets(am, prefix, "", out);
        return out;
    }

    private static void collectAssets(AssetManager am, String prefix, String rel, List<String> out)
            throws IOException {
        String[] kids = am.list(prefix);
        if (kids == null || kids.length == 0) {
            if (!rel.isEmpty()) {
                out.add(rel);
            }
            return;
        }
        for (String kid : kids) {
            if (kid.startsWith(".") || kid.equals("..")) {
                continue;
            }
            String child = prefix + "/" + kid;
            collectAssets(am, child, rel.isEmpty() ? kid : rel + "/" + kid, out);
        }
    }

    /**
     * Change key for extraction: apk path mtime + versionName. A reinstalled
     * APK always produces a different stamp, so assets are refreshed.
     */
    /**
     * 负载戳：只看这些 asset 的**未压缩长度**，不看 APK 版本/时间。
     *
     * <p>原先 node / toolkit / jsc 的"已解包"标记都用 {@link #assetVersionStamp}
     * （versionName + APK mtime），于是**每次更新 APK 都判定成过期**：工具链卡片
     * 显示「aiot-toolkit 未装」、点一次安装就要重解 280MB 的 tar。改用负载自身的
     * 长度后，只有真正换了包才会重解。tar 是 stored（noCompress），{@code openFd}
     * 能给出真实长度；读不到就退回版本戳。</p>
     */
    public static String assetPayloadStamp(Context ctx, String... assets) {
        StringBuilder sb = new StringBuilder();
        for (String a : assets) {
            sb.append(a).append(':');
            try {
                long len = ctx.getAssets().openFd(a).getLength();
                sb.append(len);
            } catch (Throwable t) {
                // 压缩存放的 asset 拿不到长度，只能退回到"整个 APK 变了就重来"
                sb.setLength(0);
                return assetVersionStamp(ctx) + "-fallback";
            }
            sb.append(';');
        }
        return sb.toString();
    }

    public static String assetVersionStamp(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            sb.append(pi.versionName == null ? "?" : pi.versionName).append('-');
        } catch (Exception e) {
            sb.append("apk-");
        }
        try {
            String apk = ctx.getPackageCodePath();
            if (apk != null) {
                sb.append(new File(apk).lastModified());
            }
        } catch (Exception e) {
            sb.append(System.currentTimeMillis());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- storage

    public static long freeBytes(File path) {
        try {
            File probe = path;
            while (probe != null && !probe.exists()) {
                probe = probe.getParentFile();
            }
            if (probe == null) {
                return 0;
            }
            StatFs st = new StatFs(probe.getAbsolutePath());
            return st.getBlockSizeLong() * st.getAvailableBlocksLong();
        } catch (Exception e) {
            return 0;
        }
    }

    public static long totalBytes(File path) {
        try {
            StatFs st = new StatFs(path.getAbsolutePath());
            return st.getBlockSizeLong() * st.getBlockCountLong();
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------------------------------------------------- elf helpers

    /**
     * True when {@code bin} runs natively on this device, i.e. it is not an
     * aarch64 Linux (glibc) ELF. Used to decide whether the invocation needs
     * <code>ld-linux-aarch64.so.1 --library-path ...</code> in front of it.
     */
    public static boolean isNativeLauncher(File bin) {
        byte[] head = readHead(bin, 4096);
        if (head == null || head.length < 20
                || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') {
            // Not an ELF at all (script, jar, missing): treat as native.
            return true;
        }
        boolean bionic = false;
        try {
            bionic = new String(head, "ISO-8859-1").contains("/system/bin/linker");
        } catch (IOException impossible) {
        }
        int machine = (head[18] & 0xff) | ((head[19] & 0xff) << 8);
        // 183 = EM_AARCH64, 40 = EM_ARM; anything else is not our engine build.
        return bionic || (machine != 183 && machine != 40);
    }

    /** Host must be 64-bit arm: the engine binaries are aarch64. */
    public static boolean is64BitDevice() {
        if (android.os.Build.SUPPORTED_ABIS != null) {
            for (String abi : android.os.Build.SUPPORTED_ABIS) {
                if (abi != null && (abi.contains("aarch64") || abi.contains("x86_64")
                        || abi.contains("arm64"))) {
                    return true;
                }
            }
        }
        return android.os.Build.SUPPORTED_64_BIT_ABIS != null
                && android.os.Build.SUPPORTED_64_BIT_ABIS.length > 0;
    }

    private static byte[] readHead(File f, int n) {
        if (f == null || !f.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[n];
            int got = 0;
            while (got < n) {
                int r = in.read(buf, got, n - got);
                if (r <= 0) {
                    break;
                }
                got += r;
            }
            if (got == n) {
                return buf;
            }
            byte[] out = new byte[Math.max(got, 0)];
            System.arraycopy(buf, 0, out, 0, got);
            return out;
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    // -------------------------------------------------------- symlinks/exec

    /** {@code symlink(2)} via Os; false when unavailable or refused. */
    public static boolean symlink(File target, File link) {
        try {
            if (link.exists() || isLink(link)) {
                link.delete();
            }
            android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            return true;
        } catch (Throwable e) {
            VelaLog.d("io", "symlink " + link + " -> " + target + " failed: " + e);
            return false;
        }
    }

    public static boolean symlinkPointsTo(File link, File target) {
        try {
            return target.getAbsolutePath().equals(android.system.Os.readlink(link.getAbsolutePath()));
        } catch (Throwable e) {
            return false;
        }
    }

    /** Hard-link mirror of a tree; false on the first refusal (cross-device). */
    public static boolean hardlinkAll(File srcTree, File dstTree) {
        if (!srcTree.isDirectory()) {
            return false;
        }
        try {
            if (!dstTree.isDirectory() && !dstTree.mkdirs()) {
                return false;
            }
            File[] kids = srcTree.listFiles();
            if (kids == null) {
                return false;
            }
            for (File k : kids) {
                File d = new File(dstTree, k.getName());
                if (k.isDirectory()) {
                    if (!hardlinkAll(k, d)) {
                        return false;
                    }
                } else {
                    if (d.exists() && d.length() == k.length()) {
                        continue;
                    }
                    d.delete();
                    android.system.Os.link(k.getAbsolutePath(), d.getAbsolutePath());
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Runs argv in a fresh process; returns the exit code, output to the log. */
    public static int run(final List<String> argv, final String tag, File cwd, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            if (cwd != null) {
                pb.directory(cwd);
            }
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            final StringBuilder captured = new StringBuilder();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] all = readBytes(p.getInputStream());
                        synchronized (captured) {
                            captured.append(new String(all, "UTF-8"));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }, "vela-run-reader");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                VelaLog.w(tag, "timeout after " + timeoutMs + " ms: " + join(" ", argv));
                return -1;
            }
            reader.join(1000);
            String out;
            synchronized (captured) {
                out = captured.toString();
            }
            for (String line : out.split("\r?\n")) {
                if (!line.trim().isEmpty()) {
                    VelaLog.d(tag, line);
                }
            }
            return p.exitValue();
        } catch (Throwable t) {
            VelaLog.w(tag, "run " + join(" ", argv) + " failed: " + t);
            return -1;
        }
    }

    /** {@code run-as <pkg> <argv...>} output -- our own private dirs, app domain. */
    public static String runAsPackage(Context ctx, List<String> argv, long timeoutMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("run-as");
        cmd.add(ctx.getPackageName());
        cmd.addAll(argv);
        final List<String> out = new ArrayList<>();
        int code = captureRun(cmd, out, timeoutMs);
        if (code != 0) {
            VelaLog.d("paths", "run-as " + join(" ", argv) + " -> exit " + code + " " + join(" | ", out));
        }
        return join("\n", out);
    }

    private static int captureRun(final List<String> argv, final List<String> sink, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                sink.add(line);
            }
            boolean done = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                return -1;
            }
            return p.exitValue();
        } catch (Throwable t) {
            sink.add(String.valueOf(t));
            return -1;
        }
    }

    // ------------------------------------------------------------- host info

    public static String deviceAbi(Context ctx) {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            return join(",", java.util.Arrays.asList(abis));
        }
        return "unknown";
    }

    public static String osReleaseVersion() throws IOException {
        String r = firstLine(new File("/proc/sys/kernel/osrelease"));
        if (r.isEmpty()) {
            throw new IOException("no osrelease");
        }
        return r;
    }

    /** One-line host description for logs and the diagnostics sheet. */
    public static String hostInfo() {
        String release;
        try {
            release = osReleaseVersion();
        } catch (IOException e) {
            release = "unknown";
        }
        return String.format(Locale.US, "%s %s (android %s, sdk %d, kernel %s, abi %s)",
                android.os.Build.MANUFACTURER, android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT,
                release, deviceAbi(null));
    }

    /** Last {@code lines} logcat entries, optionally filtered by substring. */
    public static List<String> logcatTail(int lines, String filter) {
        List<String> out = new ArrayList<>();
        List<String> cmd = new ArrayList<>();
        cmd.add("logcat");
        cmd.add("-d");
        cmd.add("-t");
        cmd.add(String.valueOf(Math.max(1, Math.min(lines, 2000))));
        cmd.add("-v");
        cmd.add("brief");
        List<String> captured = new ArrayList<>();
        captureRun(cmd, captured, 4000);
        for (String line : captured) {
            if (filter == null || filter.isEmpty() || line.contains(filter)) {
                out.add(line);
            }
        }
        return out;
    }

    static String firstNonEmpty(String... xs) {
        if (xs != null) {
            for (String x : xs) {
                if (x != null && !x.isEmpty()) {
                    return x;
                }
            }
        }
        return "";
    }
}
