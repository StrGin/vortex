package com.velasim.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;

/**
 * System images are 0.9-1.2 GB, so they are never bundled: this class
 * downloads one zip per image type into
 * <code>&lt;home&gt;/.vela/sdk/system-images/&lt;flavor&gt;/&lt;type&gt;/</code>
 * -- exactly the tree the official emulator scans under
 * <code>$HOME/.vela/sdk</code>.
 *
 * <p>Layout produced for {@code vela-miwear-watch-5.0}:
 * <pre>
 *   system-images/vela-miwear-watch-5.0/
 *     nuttx               guest kernel   (required)
 *     vela_system.bin     system flash   (required)
 *     vela_data.bin       userdata       (required)
 *     advancedFeatures.ini
 *     .vela-image.json    written by us; probe + provenance
 * </pre>
 *
 * <p>The zip also carries <code>coredump.core</code> (a several-hundred-MB
 * host-side core dump) which is skipped, plus the AVD's <code>vela_system.bin</code>
 * / <code>vela_data.bin</code> which the launcher expects in the AVD folder --
 * {@link VelaAvd#ensureImageFiles} copies them there.
 */
public final class VelaImageStore {

    /** Sub-directory under <code>&lt;home&gt;/.vela/sdk</code>. */
    public static final String SDK_IMAGES_DIRNAME = "system-images";

    private static final String PROBE = ".vela-image.json";
    private static final String STAGING_SUFFIX = ".pkg";
    private static final String DOWNLOAD_SUFFIX = ".zip.part";
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /** Below this the server almost never honours Range; restart the fetch. */
    private static final long MIN_RESUME_BYTES = 256 * 1024;

    /** Zip entries we never write to disk. */
    private static boolean skipEntry(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("__MACOSX/") || n.contains("/__MACOSX/")) {
            return true;
        }
        if (n.endsWith(".core") || n.contains("coredump") || n.endsWith("/")) {
            return true;
        }
        String base = n.substring(n.lastIndexOf('/') + 1);
        return base.startsWith(".") || base.equals("Thumbs.db");
    }

    /** Progress/terminal callbacks. Delivered on the main looper. */
    public interface Listener {
        /** phase  in  queued | connecting | downloading | extracting | verifying | done | error */
        void onProgress(String type, long done, long total, String phase);

        void onDone(String type, File dir, long bytes);

        void onError(String type, String message, Throwable t);
    }

    /** Returned by {@link VelaImageStore#download}: cancel handle + target type. */
    public interface DownloadHandle {
        String type();

        void cancel();

        boolean cancelled();
    }

    private final Context ctx;
    private final VelaDevices devices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile DownloadHandle current;

    public VelaImageStore(Context ctx, VelaDevices devices) {
        this.ctx = ctx.getApplicationContext();
        this.devices = devices;
    }

    // ---------------------------------------------------------------- paths

    private File imagesRoot() {
        return new File(VelaPaths.velaSdkDir(ctx), SDK_IMAGES_DIRNAME);
    }

    /** Catalog flavor if known, else a sanitized type -- keeps dirs stable. */
    public String flavorFor(String type) {
        VelaDevices.VelaImage img = devices.image(type);
        if (img != null && !img.flavor.isEmpty()) {
            return img.flavor;
        }
        for (VelaDevices.VelaDevice d : devices.all()) {
            if (type != null && type.equals(d.imageType) && !d.flavor.isEmpty()) {
                return d.flavor;
            }
        }
        return sanitize(type);
    }

    private File dirFor(String type) {
        return new File(imagesRoot(), flavorFor(type) + "/" + sanitize(type));
    }

    static String sanitize(String s) {
        if (s == null || s.isEmpty()) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            sb.append(ok ? c : '-');
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }

    /** Installed image directories, any flavor, probe marked complete. */
    public List<Map<String, Object>> installedDirs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (File flavorDir : subDirs(imagesRoot())) {
            for (File typeDir : subDirs(flavorDir)) {
                if (isComplete(typeDir)) {
                    out.add(describe(typeDir, false));
                }
            }
        }
        return out;
    }

    /** Where the launcher must find the triple for a device's image type. */
    public static File imageDir(File sdkDir, String flavor, String type) {
        return new File(new File(new File(sdkDir, SDK_IMAGES_DIRNAME), flavor), sanitize(type));
    }

    /**
     * 按类型在任意 flavor 目录下找一份完整镜像。flavor（watch/band）只是 IDE 的分目录
     * 习惯，同一种 imageType 的 zip 内容是同一份，所以建 AVD 时值得兜底找一遍。
     */
    public static File findImageElsewhere(File sdkDir, String type) {
        File root = new File(sdkDir, SDK_IMAGES_DIRNAME);
        File[] flavors = root.listFiles();
        if (flavors == null) {
            return null;
        }
        String want = sanitize(type);
        for (File f : flavors) {
            if (!f.isDirectory() || f.getName().startsWith(".")) {
                continue;
            }
            File cand = new File(f, want);
            if (hasRequiredFiles(cand) || cand.isDirectory()) {
                return cand;
            }
        }
        return null;
    }

    public File imageDir(String type) {
        return dirFor(type);
    }

    public boolean isInstalled(String type) {
        return isComplete(dirFor(type));
    }

    /** True when the mandatory guest files are present (probe optional). */
    public static boolean isComplete(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        return hasRequiredFiles(dir) && new File(dir, PROBE).isFile();
    }

    private static boolean hasRequiredFiles(File dir) {
        return new File(dir, "nuttx").isFile()
                && new File(dir, "vela_system.bin").isFile()
                && new File(dir, "vela_data.bin").isFile();
    }

    /** Missing mandatory files, empty when the dir is good to boot. */
    public List<String> missingFiles(File dir) {
        List<String> out = new ArrayList<>();
        for (String n : new String[]{"nuttx", "vela_system.bin", "vela_data.bin"}) {
            if (dir == null || !new File(dir, n).isFile()) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Images to show in the UI: installed first (with size/state), then the
     * catalog types still missing.
     */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 同一个镜像可能同时躺在 watch/ 与 band/ 下（两种设备档案各自需要一份），
        // 但对用户来说它就是同一份镜像——按 type 去重，保留"完整且更大"的那份，
        // 否则列表会出现两条一模一样的行（实测踩过）。
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        for (File flavorDir : subDirs(imagesRoot())) {
            for (File typeDir : subDirs(flavorDir)) {
                boolean complete = isComplete(typeDir);
                Map<String, Object> m = describe(typeDir, !complete);
                String type = String.valueOf(m.get("type"));
                Map<String, Object> prev = byType.get(type);
                if (prev == null || betterInstance(m, prev)) {
                    byType.put(type, m);
                }
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : byType.entrySet()) {
            out.add(e.getValue());
            seen.add(e.getKey());
        }
        for (VelaDevices.VelaImage img : devices.images()) {
            if (seen.add(img.type)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", img.type);
                m.put("label", img.label);
                m.put("flavor", img.flavor);
                m.put("time", img.time);
                m.put("installed", false);
                m.put("complete", false);
                m.put("dir", dirFor(img.type).getAbsolutePath());
                m.put("sizeBytes", 0L);
                m.put("state", "not-installed");
                out.add(m);
            }
        }
        return out;
    }

    /** 同一镜像的多个副本里挑一个展示：完整优先，其次体积更大。 */
    private static boolean betterInstance(Map<String, Object> candidate,
                                          Map<String, Object> current) {
        boolean cComplete = Boolean.TRUE.equals(candidate.get("complete"));
        boolean kComplete = Boolean.TRUE.equals(current.get("complete"));
        if (cComplete != kComplete) {
            return cComplete;
        }
        long cSize = candidate.get("sizeBytes") instanceof Number
                ? ((Number) candidate.get("sizeBytes")).longValue() : 0L;
        long kSize = current.get("sizeBytes") instanceof Number
                ? ((Number) current.get("sizeBytes")).longValue() : 0L;
        return cSize > kSize;
    }

    /**
     * 扫描手机上可能的镜像 zip（小米 CDN 下发的包就是 zip）。
     *
     * <p>官方 IDE 的镜像走 CDN，我们只支持"从 App 里下载"；离线/别人拷来的包
     * 以前没有入口。这里只扫几个常见目录，不递归、不弹系统选择器（省一个依赖）。</p>
     */
    public List<Map<String, Object>> localZips() {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        File root = android.os.Environment.getExternalStorageDirectory();
        File[] dirs = {
                new File(root, "Download"),
                new File(root, VelaProjects.PUBLIC_SUBDIR.split("/")[0]),
                root,
        };
        for (File dir : dirs) {
            File[] kids = dir.listFiles();
            if (kids == null) {
                continue;
            }
            Arrays.sort(kids);
            for (File f : kids) {
                if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    continue;
                }
                if (seen.add(f.getAbsolutePath())) {
                    out.add(describeZip(f));
                }
            }
        }
        return out;
    }

    private Map<String, Object> describeZip(File f) {
        Map<String, Object> m = new LinkedHashMap<>();
        String name = f.getName();
        String type = name.substring(0, name.length() - 4);
        VelaDevices.VelaImage img = devices.image(type);
        m.put("path", f.getAbsolutePath());
        m.put("name", name);
        m.put("type", type);
        m.put("known", img != null);
        m.put("label", img == null ? type : img.label);
        m.put("sizeBytes", f.length());
        return m;
    }

    /**
     * 用本地 zip 装一个镜像：类型先按文件名认，认不出来就取 zip 里的顶层目录名；
     * 解包与"已安装"标记复用下载路径的那套（{@link #unpack} + {@link #writeProbe}）。
     */
    public Map<String, Object> importZip(final String path, final Listener cb) {
        Map<String, Object> r = new LinkedHashMap<>();
        File zip = path == null ? null : new File(path);
        if (zip == null || !zip.isFile()) {
            r.put("ok", false);
            r.put("error", "找不到文件: " + path);
            return r;
        }
        String type = null;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            String name = zip.getName();
            type = name.substring(0, name.length() - 4).trim();
            if (devices.image(type) == null) {
                // 文件名认不出来时，用 zip 里的顶层目录名（官方包的布局）
                java.util.Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    int slash = n.indexOf('/');
                    if (slash > 0) {
                        type = n.substring(0, slash);
                        break;
                    }
                }
            }
            final String finalType = sanitize(type == null || type.isEmpty() ? "imported" : type);
            final File dir = dirFor(finalType);
            emit(cb, finalType, 0, zip.length(), "importing");
            int entries = unpack(zip, dir, null, new AtomicBoolean(false));
            long bytes = dirSize(dir);
            writeProbe(dir, finalType, "file://" + zip.getAbsolutePath(), entries, zip.length());
            emit(cb, finalType, bytes, bytes, "done");
            r.put("ok", true);
            r.put("type", finalType);
            r.put("dir", dir.getAbsolutePath());
            r.put("entries", entries);
            r.put("bytes", bytes);
            r.put("complete", isComplete(dir));
            VelaLog.i("image", "imported " + finalType + " from " + zip + " (" + entries + " entries)");
        } catch (Throwable t) {
            VelaLog.w("image", "import failed: " + t);
            r.put("ok", false);
            r.put("error", String.valueOf(t));
        }
        return r;
    }

    /** Compact JSON list, for logging. */
    public JSONArray listJson() {
        JSONArray a = new JSONArray();
        for (Map<String, Object> m : list()) {
            a.put(new JSONObject(m));
        }
        return a;
    }

    private Map<String, Object> describe(File dir, boolean partial) {
        Map<String, Object> m = new LinkedHashMap<>();
        JSONObject probe = readProbe(dir);
        String type = probe.optString("type", dir.getName());
        m.put("type", type);
        m.put("label", probe.optString("label", type));
        m.put("flavor", probe.optString("flavor", dir.getParentFile().getName()));
        m.put("time", probe.optString("time", ""));
        m.put("installed", true);
        m.put("complete", !partial && isComplete(dir));
        m.put("dir", dir.getAbsolutePath());
        m.put("sizeBytes", dirSize(dir));
        m.put("state", partial ? "partial" : "installed");
        if (probe.has("url")) {
            m.put("url", probe.optString("url"));
        }
        m.put("missing", missingFiles(dir));
        return m;
    }

    private static List<File> subDirs(File dir) {
        List<File> out = new ArrayList<>();
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids != null) {
            Arrays.sort(kids);
            for (File f : kids) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------- download

    public DownloadHandle download(String type, Listener cb) {
        return download(type, devices.imageUrl(type), cb);
    }

    /**
     * Fetches {@code url} and unpacks it into the sdk tree for {@code type}.
     * Any previous transfer is cancelled: one image at a time keeps the peak
     * disk usage at zip + extracted tree.
     */
    public DownloadHandle download(String type, String url, Listener cb) {
        final String fType = type;
        final String fUrl = (url == null || url.isEmpty()) ? devices.imageUrl(type) : url;
        cancelCurrent();

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final DownloadHandle handle = new DownloadHandle() {
            @Override
            public String type() {
                return fType;
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }
        };
        current = handle;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runDownload(fType, fUrl, cancelled, cb);
                } catch (Throwable t) {
                    if (cancelled.get()) {
                        emitError(cb, fType, "cancelled", t);
                    } else {
                        emitError(cb, fType, describeError(t), t);
                    }
                } finally {
                    synchronized (VelaImageStore.this) {
                        if (current == handle) {
                            current = null;
                        }
                    }
                }
            }
        }, "vela-image-" + type).start();
        return handle;
    }

    private void runDownload(final String type, String url, AtomicBoolean cancelled, Listener cb)
            throws IOException {
        final File dir = dirFor(type);
        final File partFile = new File(dir.getParentFile(), sanitize(type) + DOWNLOAD_SUFFIX);
        final File staging = new File(dir.getParentFile(), sanitize(type) + STAGING_SUFFIX);

        emit(cb, type, 0, 0, "queued");
        mkdirs(dir.getParentFile());

        emit(cb, type, 0, 0, "connecting");
        long remoteSize = probeRemoteSize(url);
        if (cancelled.get()) {
            throw new IOException("cancelled");
        }
        fetchInto(url, partFile, remoteSize, cancelled, cb, type);

        if (cancelled.get()) {
            throw new IOException("cancelled");
        }
        if (remoteSize > 0 && partFile.length() != remoteSize) {
            throw new IOException("download truncated: got " + partFile.length() + " of " + remoteSize + " bytes");
        }

        // Rename to .pkg so a crash can never leave a half-written dir.
        deleteRecursive(staging);
        if (!partFile.renameTo(staging)) {
            throw new IOException("cannot stage " + partFile.getAbsolutePath() + " -> " + staging.getAbsolutePath());
        }
        emit(cb, type, 0, 0, "extracting");
        final long total = staging.length();
        int entries = unpack(staging, dir, new ZipProgress() {
            @Override
            public void onBytes(long done) {
                emit(cb, type, done, total, "extracting");
            }
        }, cancelled);
        deleteRecursive(staging);

        emit(cb, type, 0, 0, "verifying");
        List<String> missing = missingFiles(dir);
        if (!missing.isEmpty()) {
            throw new IOException("image incomplete, missing: " + missing);
        }
        writeProbe(dir, type, url, entries, total);
        emit(cb, type, total, total, "done");
        if (cb != null) {
            final File f = dir;
            main.post(new Runnable() {
                @Override
                public void run() {
                    cb.onDone(type, f, total);
                }
            });
        }
    }

    private interface ZipProgress {
        void onBytes(long done);
    }

    /**
     * Fetches {@code url} into {@code target}. Up to five attempts; each
     * attempt resumes from the bytes already on disk when the server honors
     * Range, and restarts from zero when it does not.
     */
    private void fetchInto(String url, File target, long remoteSize,
                           AtomicBoolean cancelled, Listener cb, String type) throws IOException {
        for (int attempt = 0; attempt < 5; attempt++) {
            if (cancelled.get()) {
                throw new IOException("cancelled");
            }
            long onDisk = target.exists() ? target.length() : 0;
            boolean usableRange = onDisk >= MIN_RESUME_BYTES
                    && (remoteSize <= 0 || onDisk < remoteSize);
            if (onDisk > 0 && !usableRange) {
                target.delete();
                onDisk = 0;
            }
            try {
                transfer(url, target, usableRange ? onDisk : 0, cancelled, cb, type);
                return;
            } catch (IOException expectedOnce) {
                if (cancelled.get() || attempt == 4) {
                    throw expectedOnce;
                }
                VelaLog.w("image", "transfer failed (" + describeError(expectedOnce)
                        + "), retry " + (attempt + 1) + " from "
                        + (target.exists() ? target.length() : 0) + " bytes");
                sleep(1000L * Math.min(8, attempt + 1));
            }
        }
    }

    private void transfer(String url, File target, long from,
                          AtomicBoolean cancelled, Listener cb, String type) throws IOException {
        HttpURLConnection conn = open(url);
        boolean resuming;
        long start;
        long expected;
        try {
            if (from > 0) {
                conn.setRequestProperty("Range", "bytes=" + from + "-");
            }
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                String body = VelaUtil.readStream(conn.getErrorStream(), 4096);
                throw new IOException("HTTP " + code + " for " + url
                        + (body.isEmpty() ? "" : " -- " + VelaUtil.ellipsize(body.trim(), 200)));
            }
            // HTTP 206 means our prefix is valid; 200 means the server ignored
            // Range, so the body is the whole file and we must truncate.
            resuming = code == HttpURLConnection.HTTP_PARTIAL;
            start = resuming ? from : 0;
            // API 21-safe: getContentLengthLong() is only API 24, and these
            // zips stay well below 2 GiB anyway.
            long bodyLen = conn.getContentLength();
            expected = bodyLen > 0 ? start + bodyLen : 0;
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }

        OutputStream out = new FileOutputStream(target, resuming);
        InputStream in = null;
        long done = start;
        long lastReport = 0;
        try {
            in = new java.io.BufferedInputStream(conn.getInputStream(), 64 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.get()) {
                    throw new IOException("cancelled");
                }
                out.write(buf, 0, n);
                done += n;
                if (expected > 0 && done - lastReport >= 2L * 1024 * 1024) {
                    lastReport = done;
                    emit(cb, type, done, expected, "downloading");
                }
            }
            out.flush();
        } finally {
            VelaUtil.closeQuietly(in);
            VelaUtil.closeQuietly(out);
            conn.disconnect();
        }
        if (expected > 0 && done != expected) {
            throw new IOException("connection closed at " + done + " of " + expected + " bytes");
        }
        emit(cb, type, done, Math.max(done, expected), "downloading");
    }

    private long probeRemoteSize(String url) {
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                long len = conn.getContentLength();
                return len > 0 ? len : 0;
            }
            VelaLog.w("image", "HEAD " + url + " -> HTTP " + code + ", continuing without a size");
            return 0;
        } catch (IOException e) {
            VelaLog.w("image", "HEAD failed for " + url + ": " + describeError(e));
            return 0;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("User-Agent", "Vortex/1.0 (Android)");
        return conn;
    }

    /**
     * Unpacks the interesting entries of {@code zip} into {@code dest}. The
     * official zip nests everything under a single top folder, so entries are
     * written relative to its first path segment.
     */
    private int unpack(File zip, File dest, ZipProgress progress, AtomicBoolean cancelled)
            throws IOException {
        deleteRecursive(dest);
        mkdirs(dest);
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip);
        long done = 0;
        int written = 0;
        try {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (cancelled.get()) {
                    throw new IOException("cancelled");
                }
                if (e.isDirectory() || skipEntry(e.getName())) {
                    continue;
                }
                String rel = stripTopDir(e.getName());
                if (rel == null || rel.isEmpty()) {
                    continue;
                }
                File out = new File(dest, rel);
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)) {
                    VelaLog.w("image", "skipping escaping zip entry: " + e.getName());
                    continue;
                }
                mkdirs(out.getParentFile());
                InputStream in = zf.getInputStream(e);
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                    }
                } finally {
                    VelaUtil.closeQuietly(in);
                    VelaUtil.closeQuietly(os);
                }
                VelaUtil.chmod755(out);
                written++;
                if (progress != null) {
                    progress.onBytes(done);
                }
            }
        } finally {
            zf.close();
        }
        VelaUtil.chmod755(dest);
        return written;
    }

    private static String stripTopDir(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./") || n.startsWith("/")) {
            n = n.substring(n.startsWith("./") ? 2 : 1);
        }
        if (n.contains("../")) {
            return null;
        }
        int slash = n.indexOf('/');
        int rest = n.indexOf('/', slash + 1);
        // Keep the path below the single wrapping folder, if there is one.
        return (slash > 0 && rest > 0) ? n.substring(slash + 1) : n;
    }

    // ---------------------------------------------------------------- misc

    /** Deletes an installed (or half-installed) image plus its leftovers. */
    public boolean remove(String type) {
        File dir = dirFor(type);
        boolean ok = deleteRecursive(dir);
        new File(dir.getParentFile(), sanitize(type) + DOWNLOAD_SUFFIX).delete();
        new File(dir.getParentFile(), sanitize(type) + STAGING_SUFFIX).delete();
        File flavor = dir.getParentFile();
        String[] kids = flavor.list();
        if (kids != null && kids.length == 0) {
            flavor.delete();
        }
        return ok;
    }

    public void cancelCurrent() {
        DownloadHandle h = current;
        if (h != null) {
            h.cancel();
        }
    }

    public boolean isDownloading() {
        return current != null;
    }

    public String downloadingType() {
        DownloadHandle h = current;
        return h == null ? null : h.type();
    }

    /** Total bytes used by installed images. */
    public long installedBytes() {
        long total = 0;
        for (Map<String, Object> m : installedDirs()) {
            Object v = m.get("sizeBytes");
            if (v instanceof Long) {
                total += (Long) v;
            }
        }
        return total;
    }

    /**
     * Free space needed before starting a download: the zip plus its expanded
     * tree, so roughly twice the remote size.
     */
    public long headroomBytes(String type) {
        long remote = 0;
        try {
            remote = probeRemoteSize(devices.imageUrl(type));
        } catch (Exception ignored) {
        }
        return remote > 0 ? remote * 2 + (16L * 1024 * 1024) : 3L * 1024 * 1024 * 1024;
    }

    public File freeCheck(String type) throws IOException {
        File parent = dirFor(type).getParentFile();
        mkdirs(parent);
        return parent;
    }

    private static JSONObject readProbe(File dir) {
        File probe = new File(dir, PROBE);
        if (!probe.isFile()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(VelaUtil.slurp(probe));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeProbe(File dir, String type, String url, int entries, long bytes) {
        JSONObject o = new JSONObject();
        try {
            VelaDevices.VelaImage img = devices.image(type);
            o.put("type", type);
            o.put("label", img == null ? type : img.label);
            o.put("flavor", flavorFor(type));
            o.put("time", img == null ? "" : img.time);
            o.put("url", url == null ? "" : url);
            o.put("entries", entries);
            o.put("zipBytes", bytes);
            o.put("installedAt", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        Writer w = null;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(dir, PROBE)), "UTF-8"));
            w.write(o.toString(2));
        } catch (Exception e) {
            VelaLog.w("image", "cannot write probe in " + dir + ": " + e);
        } finally {
            VelaUtil.closeQuietly(w);
        }
    }

    // -------------------------------------------------------------- helpers

    private void emit(final Listener cb, final String type, final long done, final long total,
                      final String phase) {
        if (cb == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onProgress(type, done, total, phase);
            }
        });
    }

    private void emitError(final Listener cb, final String type, final String msg, final Throwable t) {
        if (cb == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(type, msg, t);
            }
        });
    }

    private static String describeError(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String m = t.getMessage();
        String cls = t.getClass().getSimpleName();
        return m == null || m.isEmpty() ? cls : cls + ": " + m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir == null) {
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create directory " + dir.getAbsolutePath());
        }
    }

    private static boolean deleteRecursive(File f) {
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

    static long dirSize(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return 0;
        }
        long sum = 0;
        for (File k : kids) {
            sum += dirSize(k);
        }
        return sum;
    }
}
