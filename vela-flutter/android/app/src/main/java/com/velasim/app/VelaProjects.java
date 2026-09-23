package com.velasim.app;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 快应用工程仓库：{@code <files>/vela/projects/<name>/}。
 *
 * <pre>
 *   create()  从 assets/vela/templates/project-app 拷出官方模板，
 *             替换 {{name}}/{{package}}/{{description}}/{{minPlatformVersion}}
 *             （IDE 也只渲染 src/manifest.json 与 package.json 这两个文件），
 *             再按 deviceType 改 manifest 的 deviceTypeList
 *   files()   相对路径 + size + isDir，跳过 node_modules / build / .git
 *   read/write 只允许落在工程目录内（canonical path 比对，防 ../ 与软链外越）
 * </pre>
 *
 * <p>另外把每个工程best-effort 镜像一份到 {@code /sdcard/Vortex/projects/<name>/}，
 * 手机自带文件管理器就能改源码；但真身永远在私有目录 —— 构建要在工作区里跑，
 * 公共目录是 sdcardfs/FUSE，both 慢又丢事件。镜像只做「有则同步、无则忽略」。</p>
 */
public final class VelaProjects {

    /** 随 APK 打包的官方工程模板（保留 {{...}} 占位符）。 */
    public static final String ASSET_TEMPLATE = "vela/templates/project-app";
    /** 离线工具链包，见 {@link VelaToolchain}。 */
    public static final String ASSET_TOOLKIT_TAR = "vela/toolkit.tar";
    /** 用户可投放 node/libnode 的目录（相对 {@code /sdcard/Vortex}）。 */
    public static final String DROP_DIR = "Vortex/toolchain";
    private static final String PROJECTS_DIR_NAME = "projects";
    /** 公共编辑面（/sdcard/Vortex/projects）；镜像导入也扫它的父目录。 */
    public static final String PUBLIC_SUBDIR = "Vortex/projects";

    private static final String TAG = "projects";
    private static final long MAX_TEXT_READ = 2L * 1024 * 1024;
    private static final long MAX_TEXT_WRITE = 4L * 1024 * 1024;
    /** 镜像进公共目录的单文件上限：比这大的不是源码。 */
    private static final long MIRROR_FILE_LIMIT = 4L * 1024 * 1024;

    /** 不参与列目录/镜像的目录名。 */
    private static final List<String> SKIP_DIRS =
            Collections.unmodifiableList(Arrays.asList("node_modules", "build", ".git", "dist",
                    ".husky", ".idea", ".vscode", "coverage", "logs"));

    private static final String DEFAULT_PACKAGE_PREFIX = "xiaomi.mobile.mina.";
    private static final String DEFAULT_MIN_PLATFORM = "1070";

    private final Context ctx;

    public VelaProjects(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public Context context() {
        return ctx;
    }

    // ------------------------------------------------------------------ paths

    public static File rootDir(Context ctx) {
        return new File(VelaPaths.runtimeDir(ctx), PROJECTS_DIR_NAME);
    }

    public File rootDir() {
        return rootDir(ctx);
    }

    /** 公共编辑面根目录（可能因权限不可写）。 */
    public static File publicRoot() {
        return new File(Environment.getExternalStorageDirectory(), PUBLIC_SUBDIR);
    }

    /**
     * 一次性迁移：产品从 SimpSim Vela 改名为 Vortex，公共编辑面也跟着换成
     * {@code /sdcard/Vortex}。旧目录还在、新目录还没建时把它整体改名过去，
     * 让用户原来的工程继续出现在「工程」页；改不动就只记日志，绝不删数据。
     */
    public static void migratePublicRootIfNeeded() {
        try {
            File root = Environment.getExternalStorageDirectory();
            File old = new File(root, "SimpSimVela");
            File now = new File(root, PUBLIC_SUBDIR);
            if (!old.isDirectory() || now.exists()) {
                return;
            }
            if (old.renameTo(now)) {
                VelaLog.i("projects", "公共目录已迁移: " + old + " -> " + now);
            } else {
                VelaLog.w("projects", "公共目录迁移失败（保留旧目录）: " + old);
            }
        } catch (Throwable t) {
            VelaLog.w("projects", "公共目录迁移异常: " + t);
        }
    }

    /** 工程真身；名字会被规范化，但绝不创建目录。 */
    public File dirFor(String name) {
        return new File(rootDir(ctx), sanitize(name));
    }

    public File publicDirFor(String name) {
        return new File(publicRoot(), sanitize(name));
    }

    /** 必须已存在的工程目录，否则抛中文原因。 */
    public File require(String name) throws IOException {
        File dir = dirFor(name);
        if (!dir.isDirectory()) {
            throw new IOException("工程不存在: " + name + "（" + dir.getAbsolutePath() + "）");
        }
        return dir;
    }

    /**
     * 相对路径 -&gt; 工程内绝对路径。
     *
     * <p>用 canonical path 而不是字符串前缀比对：{@code ../} 会被 File 规范化掉，
     * 软链也只在 canonical 阶段暴露。</p>
     */
    public File resolve(String name, String relPath) throws IOException {
        File base = require(name);
        String root = base.getCanonicalPath();
        String r = relPath == null ? "" : relPath.replace('\\', '/');
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        File f = r.isEmpty() ? base : new File(base, r);
        String canon = f.getCanonicalPath();
        if (!canon.equals(root) && !canon.startsWith(root + File.separator)) {
            throw new IOException("路径越界，只能读写工程目录内的文件: " + relPath);
        }
        return f;
    }

    // ---------------------------------------------------------------- create

    /**
     * 建工程。
     *
     * @param deviceType manifest 的 deviceTypeList 值（watch/phone/tv/aiot/car…）
     * @return 工程目录
     */
    public File create(String name, String pkg, String deviceType, String minPlatformVersion)
            throws IOException {
        String safe = sanitize(name);
        if (safe.isEmpty()) {
            throw new IOException("工程名不合法（只允许字母、数字、. _ -）");
        }
        File dir = new File(rootDir(ctx), safe);
        if (dir.isDirectory()) {
            throw new IOException("同名工程已存在: " + safe);
        }
        String package_ = pkg == null || pkg.trim().isEmpty()
                ? DEFAULT_PACKAGE_PREFIX + safe.toLowerCase(Locale.US) : pkg.trim();
        String type = deviceTypeFor(deviceType);
        String minPlatform = minPlatformVersion == null || minPlatformVersion.trim().isEmpty()
                ? DEFAULT_MIN_PLATFORM : minPlatformVersion.trim();

        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建工程目录 " + dir);
        }
        try {
            copyAssetTree(ctx.getAssets(), ASSET_TEMPLATE, dir, "");
            LinkedHashMap<String, String> tokens = new LinkedHashMap<>();
            tokens.put("{{name}}", safe);
            tokens.put("{{package}}", package_);
            tokens.put("{{description}}", "Vortex 工程 " + safe);
            tokens.put("{{minPlatformVersion}}", minPlatform);
            // IDE 的 project-app/index.js 里可渲染的就这两个文件。
            for (String rel : new String[]{"src/manifest.json", "package.json"}) {
                File f = new File(dir, rel);
                if (!f.isFile()) {
                    continue;
                }
                String text = VelaUtil.slurp(f);
                VelaUtil.writeText(f, replace(text, tokens));
            }
            patchManifest(new File(dir, "src/manifest.json"), safe, package_, type, minPlatform);
            patchPackageJson(new File(dir, "package.json"), safe);
        } catch (IOException e) {
            VelaUtil.deleteRecursive(dir);
            throw e;
        } catch (RuntimeException e) {
            VelaUtil.deleteRecursive(dir);
            throw new IOException("创建工程失败: " + e, e);
        }
        VelaLog.i(TAG, "created project " + safe + " pkg=" + package_ + " type=" + type);
        mirrorToPublic(safe);
        return dir;
    }

    /** manifest.json 是构建与安装的元数据来源，用 org.json 正规改。 */
    private void patchManifest(File manifest, String name, String pkg, String deviceType,
                               String minPlatform) throws IOException {
        if (!manifest.isFile()) {
            throw new IOException("模板缺少 src/manifest.json，请检查 assets/" + ASSET_TEMPLATE);
        }
        try {
            JSONObject m = new JSONObject(VelaUtil.slurp(manifest));
            m.put("package", pkg);
            m.put("name", name);
            m.put("versionName", "1.0.0");
            m.put("versionCode", 1);
            JSONArray types = new JSONArray();
            types.put(deviceType);
            m.put("deviceTypeList", types);
            m.put("minPlatformVersion", asNumberOrString(minPlatform));
            VelaUtil.writeText(manifest, m.toString(2) + "\n");
        } catch (Exception e) {
            throw new IOException("写入 src/manifest.json 失败: " + e, e);
        }
    }

    private static Object asNumberOrString(String v) {
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** 把 aiot-toolkit / ux-types 记进 devDependencies，供后续 npm install 使用。 */
    private void patchPackageJson(File pkgJson, String name) {
        if (!pkgJson.isFile()) {
            return;
        }
        try {
            JSONObject p = new JSONObject(VelaUtil.slurp(pkgJson));
            p.put("name", name.toLowerCase(Locale.US));
            JSONObject dev = p.optJSONObject("devDependencies");
            if (dev == null) {
                dev = new JSONObject();
            }
            dev.put(VelaToolchain.TOOLKIT_PACKAGE, VelaToolchain.TOOLKIT_RANGE);
            dev.put(VelaToolchain.UX_TYPES, VelaToolchain.UX_TYPES_RANGE);
            p.put("devDependencies", dev);
            VelaUtil.writeText(pkgJson, p.toString(2) + "\n");
        } catch (Exception e) {
            VelaLog.w(TAG, "package.json 补丁失败（不影响工程）: " + e);
        }
    }

    /** watch/phone/tv/… —— 允许传 flavor、imageType 或设备名，取其中的已知类型。 */
    public static String deviceTypeFor(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.US);
        String[] known = {"watch", "phone", "tv", "car", "aiot", "wearable", "desktop"};
        for (String k : known) {
            if (s.contains(k)) {
                return k;
            }
        }
        return "watch";
    }

    // ------------------------------------------------------------------ list

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        File[] kids = rootDir(ctx).listFiles();
        if (kids == null) {
            return out;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (!k.isDirectory() || !isProject(k)) {
                continue;
            }
            out.add(info(k));
        }
        return out;
    }

    private static boolean isProject(File dir) {
        return new File(dir, "src/manifest.json").isFile() || new File(dir, "package.json").isFile();
    }

    /**
     * 编辑面（{@code /sdcard/Vortex/projects}）里有、私有工作区还没有的工程。
     * 给「把 PC 上的工程文件夹丢进手机公共目录，再在 App 里导入」这条路用。
     */
    public List<Map<String, Object>> importable() {
        List<Map<String, Object>> out = new ArrayList<>();
        File[] kids = publicRoot().listFiles();
        if (kids == null) {
            return out;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            if (!k.isDirectory() || !isProject(k)) {
                continue;
            }
            if (dirFor(k.getName()).isDirectory()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", k.getName());
            m.put("path", k.getAbsolutePath());
            m.put("publicPath", k.getAbsolutePath());
            m.put("bytes", VelaImageStore.dirSize(k));
            m.put("sourceFiles", countSourceFiles(k, 0));
            File manifest = new File(k, "src/manifest.json");
            if (manifest.isFile()) {
                try {
                    JSONObject j = new JSONObject(VelaUtil.slurp(manifest));
                    m.put("package", j.optString("package", ""));
                    m.put("title", j.optString("name", k.getName()));
                    m.put("versionName", j.optString("versionName", ""));
                } catch (Exception ignored) {
                }
            }
            out.add(m);
        }
        return out;
    }

    /**
     * 公共 -&gt; 私有 的整树导入；工程名冲突时覆盖私有侧的源码文件（node_modules 等
     * 依赖/产物目录按 SKIP_DIRS 跳过，重新构建时工具链会自己装）。
     */
    public Map<String, Object> importFromPublic(String name) throws IOException {
        File pub = publicDirFor(name);
        if (!pub.isDirectory()) {
            throw new IOException("编辑面没有这个工程目录：" + pub.getAbsolutePath());
        }
        if (!isProject(pub)) {
            throw new IOException("不是快应用工程（缺 src/manifest.json 或 package.json）："
                    + pub.getAbsolutePath());
        }
        File priv = dirFor(name);
        if (!priv.isDirectory() && !priv.mkdirs() && !priv.isDirectory()) {
            throw new IOException("无法创建工程目录 " + priv.getAbsolutePath());
        }
        int[] copied = {0};
        pullTree(pub, priv, copied);
        VelaLog.i(TAG, "从编辑面导入 " + name + "（" + copied[0] + " 个文件）");
        Map<String, Object> m = info(priv);
        m.put("importedFiles", copied[0]);
        return m;
    }

    public Map<String, Object> info(File dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        String name = dir.getName();
        m.put("name", name);
        m.put("dir", dir.getAbsolutePath());
        m.put("path", "projects/" + name);
        File manifest = new File(dir, "src/manifest.json");
        String pkg = "";
        String title = name;
        String versionName = "";
        String deviceType = "";
        String minPlatform = "";
        if (manifest.isFile()) {
            try {
                JSONObject j = new JSONObject(VelaUtil.slurp(manifest));
                pkg = j.optString("package", "");
                title = j.optString("name", name);
                versionName = j.optString("versionName", "");
                minPlatform = j.optString("minPlatformVersion", "");
                JSONArray types = j.optJSONArray("deviceTypeList");
                if (types != null && types.length() > 0) {
                    deviceType = String.valueOf(types.opt(0));
                }
            } catch (Exception e) {
                m.put("manifestError", VelaChannel.describe(e));
            }
        } else {
            m.put("manifestMissing", true);
        }
        m.put("package", pkg);
        m.put("title", title);
        m.put("versionName", versionName);
        m.put("deviceType", deviceType);
        m.put("minPlatformVersion", minPlatform);
        m.put("entry", entryOf(manifest));
        m.put("bytes", VelaImageStore.dirSize(dir));
        m.put("sourceFiles", countSourceFiles(dir, 0));
        m.put("hasNodeModules", new File(dir, "node_modules").isDirectory());
        m.put("hasToolkit", VelaToolchain.hasLocalToolkit(dir));
        m.put("hasBuild", new File(dir, "build").isDirectory());
        m.put("updatedAt", newestMtime(dir, 0));
        File rpk = newestArtifact(dir);
        m.put("rpkPath", rpk == null ? null : rpk.getAbsolutePath());
        m.put("rpkBytes", rpk == null ? 0L : rpk.length());
        File pub = new File(publicRoot(), name);
        m.put("publicPath", pub.getAbsolutePath());
        m.put("publicExists", pub.isDirectory());
        return m;
    }

    /** 首页路由，用来给 UI 显示入口页。 */
    private static String entryOf(File manifest) {
        if (!manifest.isFile()) {
            return "";
        }
        try {
            JSONObject j = new JSONObject(VelaUtil.slurp(manifest));
            JSONObject router = j.optJSONObject("router");
            return router == null ? "" : router.optString("entry", "");
        } catch (Exception e) {
            return "";
        }
    }

    private int countSourceFiles(File dir, int depth) {
        if (dir == null || depth > 12) {
            return 0;
        }
        int n = 0;
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                if (!SKIP_DIRS.contains(k.getName())) {
                    n += countSourceFiles(k, depth + 1);
                }
            } else if (k.isFile() && !k.getName().startsWith(".")) {
                n++;
            }
        }
        return n;
    }

    private long newestMtime(File dir, int depth) {
        long newest = dir.lastModified();
        if (depth > 8) {
            return newest;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return newest;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                if (!SKIP_DIRS.contains(k.getName())) {
                    newest = Math.max(newest, newestMtime(k, depth + 1));
                }
            } else {
                newest = Math.max(newest, k.lastModified());
            }
        }
        return newest;
    }

    /**
     * Newest {@code *.rpk} produced by a build. The toolkit writes to
     * {@code dist/<package>.debug.<versionName>.rpk} (verified by running it);
     * {@code build/} is kept as a fallback for the older hap-toolkit v1 layout.
     */
    public static File newestArtifact(File projectDir) {
        for (String dir : new String[]{"dist", "build"}) {
            File d = new File(projectDir, dir);
            File best = pickNewest(d, ".rpk");
            if (best != null) {
                return best;
            }
        }
        return pickNewest(new File(projectDir, "build"), null);
    }

    private static File pickNewest(File root, String suffix) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        File[] kids = root.listFiles();
        if (kids == null) {
            return null;
        }
        File best = null;
        long bestTime = -1;
        for (File k : kids) {
            if (k.isDirectory()) {
                File deep = pickNewest(k, suffix);
                if (deep != null && deep.lastModified() > bestTime) {
                    best = deep;
                    bestTime = deep.lastModified();
                }
                continue;
            }
            if (!k.isFile() || (suffix != null && !k.getName().toLowerCase(Locale.US).endsWith(suffix))) {
                continue;
            }
            if (k.lastModified() > bestTime) {
                best = k;
                bestTime = k.lastModified();
            }
        }
        return best;
    }

    public Map<String, Object> delete(String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        File dir = dirFor(name);
        r.put("name", sanitize(name));
        boolean gone = !dir.exists();
        if (!gone) {
            gone = VelaUtil.deleteRecursive(dir);
        }
        r.put("deleted", gone);
        File pub = new File(publicRoot(), sanitize(name));
        if (pub.isDirectory()) {
            // 编辑面镜像跟着删，避免「工程没了镜像还在」的幽灵目录
            r.put("publicDeleted", VelaUtil.deleteRecursive(pub));
        }
        return r;
    }

    // ----------------------------------------------------------------- files

    /** 工程内源码清单（跳过 node_modules/build/.git），路径用 / 分隔。 */
    public List<Map<String, Object>> files(String name) throws IOException {
        File base = require(name);
        List<Map<String, Object>> out = new ArrayList<>();
        walk(base, base, out, 0);
        Collections.sort(out, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return String.valueOf(a.get("path")).compareTo(String.valueOf(b.get("path")));
            }
        });
        return out;
    }

    private void walk(File base, File dir, List<Map<String, Object>> out, int depth) {
        if (depth > 16) {
            return;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                if (SKIP_DIRS.contains(k.getName())) {
                    continue;
                }
                Map<String, Object> m = entryOf(base, k);
                out.add(m);
                walk(base, k, out, depth + 1);
                continue;
            }
            if (!k.isFile()) {
                continue;
            }
            out.add(entryOf(base, k));
        }
    }

    private Map<String, Object> entryOf(File base, File f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", relative(base, f));
        m.put("name", f.getName());
        m.put("size", f.length());
        m.put("isDir", f.isDirectory());
        m.put("mtime", f.lastModified());
        return m;
    }

    public static String relative(File base, File f) {
        String b = base.getAbsolutePath();
        String p = f.getAbsolutePath();
        String rel = p.startsWith(b) ? p.substring(b.length()) : p;
        while (rel.startsWith(File.separator)) {
            rel = rel.substring(File.separator.length());
        }
        return rel.replace(File.separatorChar, '/');
    }

    // ------------------------------------------------------------ read/write

    public String read(String name, String relPath) throws IOException {
        File f = resolve(name, relPath);
        if (!f.isFile()) {
            throw new IOException("文件不存在: " + relPath);
        }
        if (f.length() > MAX_TEXT_READ) {
            throw new IOException("文件过大（" + VelaUtil.formatBytes(f.length())
                    + "），只支持编辑 " + VelaUtil.formatBytes(MAX_TEXT_READ) + " 以内的文本");
        }
        byte[] raw = VelaUtil.readBytes(new FileInputStream(f));
        if (looksBinary(raw)) {
            throw new IOException("这是二进制文件（如 png/字体），暂不支持在线编辑");
        }
        return new String(raw, "UTF-8");
    }

    /** 写文件：越界检查在 {@link #resolve} 里；顺带刷一次公共镜像。 */
    public Map<String, Object> write(String name, String relPath, String content)
            throws IOException {
        File f = resolve(name, relPath);
        String text = content == null ? "" : content;
        byte[] raw = text.getBytes("UTF-8");
        if (raw.length > MAX_TEXT_WRITE) {
            throw new IOException("内容过大（" + VelaUtil.formatBytes(raw.length) + "），上限 "
                    + VelaUtil.formatBytes(MAX_TEXT_WRITE));
        }
        if (f.isDirectory()) {
            throw new IOException("目标是目录: " + relPath);
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录 " + parent);
        }
        OutputStream os = new FileOutputStream(f);
        try {
            os.write(raw);
        } finally {
            VelaUtil.closeQuietly(os);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", sanitize(name));
        r.put("path", relPath == null ? "" : relPath.replace('\\', '/'));
        r.put("bytes", raw.length);
        r.put("ok", f.isFile());
        r.put("mtime", f.lastModified());
        return r;
    }

    private static boolean looksBinary(byte[] raw) {
        int n = Math.min(raw.length, 4096);
        for (int i = 0; i < n; i++) {
            if (raw[i] == 0) {
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------- public mirror

    /** 公共编辑面是否可用（能建目录能写文件）。 */
    public boolean publicWritable() {
        File root = publicRoot();
        try {
            if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) {
                return false;
            }
            File probe = new File(root, ".vela-probe-" + System.currentTimeMillis());
            if (!VelaUtil.writeText(probe, "x")) {
                return false;
            }
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 私有 -&gt; 公共：只镜像源码，产物与依赖不铺出去。 */
    public int mirrorToPublic(String name) {
        try {
            File src = require(name);
            File dst = publicDirFor(name);
            if (!publicWritable()) {
                VelaLog.d(TAG, "编辑面不可写，跳过镜像（授予「所有文件访问」后可用）");
                return -1;
            }
            int n = copyForMirror(src, dst, 0);
            VelaLog.i(TAG, "镜像到编辑面 " + dst + "（" + n + " 个文件）");
            return n;
        } catch (Throwable t) {
            VelaLog.w(TAG, "镜像失败: " + t);
            return -1;
        }
    }

    private int copyForMirror(File src, File dst, int depth) throws IOException {
        if (depth > 16) {
            return 0;
        }
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs() && !dst.isDirectory()) {
                throw new IOException("无法创建 " + dst);
            }
            int n = 0;
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.isDirectory() && SKIP_DIRS.contains(k.getName())) {
                        continue;
                    }
                    n += copyForMirror(k, new File(dst, k.getName()), depth + 1);
                }
            }
            return n;
        }
        if (!src.isFile() || src.length() > MIRROR_FILE_LIMIT) {
            return 0;
        }
        if (dst.isFile() && dst.length() == src.length()
                && dst.lastModified() == src.lastModified()) {
            return 0;
        }
        VelaUtil.copyFile(src, dst);
        dst.setLastModified(src.lastModified());
        return 1;
    }

    /**
     * 公共 -&gt; 私有的增量并入（外部编辑器改完源码后调用）。
     *
     * <p>以 (mtime, size) 指纹逐文件比对；公共侧独有的目录不会反向删私有侧的
     * node_modules/build，因为那些名字直接跳过。</p>
     */
    public int syncFromPublic(String name) {
        File priv = dirFor(name);
        File pub = publicDirFor(name);
        if (!priv.isDirectory() || !pub.isDirectory()) {
            return 0;
        }
        int[] n = {0};
        try {
            pullTree(pub, priv, n);
        } catch (Throwable t) {
            VelaLog.w(TAG, "编辑面并入失败: " + t);
        }
        return n[0];
    }

    private void pullTree(File pub, File priv, int[] copied) throws IOException {
        File[] kids = pub.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory() && SKIP_DIRS.contains(k.getName())) {
                continue;
            }
            File target = new File(priv, k.getName());
            if (k.isDirectory()) {
                if (!target.isDirectory()) {
                    target.mkdirs();
                }
                pullTree(k, target, copied);
                continue;
            }
            if (!k.isFile() || k.length() > MIRROR_FILE_LIMIT) {
                continue;
            }
            boolean stale = !target.isFile() || target.length() != k.length()
                    || target.lastModified() != k.lastModified();
            if (!stale) {
                continue;
            }
            if (target.getParentFile() != null) {
                target.getParentFile().mkdirs();
            }
            VelaUtil.copyFile(k, target);
            target.setLastModified(k.lastModified());
            copied[0]++;
        }
    }

    // ----------------------------------------------------------------- misc

    /** 与 {@link VelaAvd} 的命名规则一致：落到文件名安全的字符集。 */
    public static String sanitize(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("[^A-Za-z0-9._-]", "-");
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        s = s.replace("..", "-");
        if (s.isEmpty()) {
            s = "vela-app";
        }
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    /** 名字里能不能出现分隔符/点号上溯（外部传参的第一道闸）。 */
    public static boolean nameIsSafe(String raw) {
        return raw != null && !raw.isEmpty() && !raw.contains("/") && !raw.contains("\\")
                && !raw.contains("..") && sanitize(raw).equals(raw);
    }

    /** 导入一个外部 rpk（文件管理器/下载目录）到工程旁的 {@code imports/}。 */
    public File importArtifact(String srcPath, String name) throws IOException {
        File src = new File(srcPath);
        if (!src.isFile()) {
            throw new IOException("文件不存在或不可读: " + srcPath);
        }
        if (!src.canRead()) {
            throw new IOException("没有读权限：" + srcPath + "（私有目录里的文件要用「导入工程」而不是路径）");
        }
        String base = sanitize(name == null || name.isEmpty()
                ? src.getName().replaceAll("\\.[A-Za-z0-9]+$", "") : name);
        File dir = new File(new File(VelaPaths.runtimeDir(ctx), "imports"), base);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建 " + dir);
        }
        File dst = new File(dir, src.getName());
        VelaUtil.copyFile(src, dst);
        VelaUtil.chmod755(dst);
        return dst;
    }

    /** 供 {@link VelaDevServer} 用：工程名 -&gt; src 目录（可能不存在）。 */
    public File srcDir(String name) throws IOException {
        return new File(require(name), "src");
    }

    // ------------------------------------------------------- asset extraction

    /**
     * 逐文件释放 assets 子树，保持目录结构。
     *
     * <p>不复用 {@link VelaUtil#listAssetsRecursive}：那个 helper 只回叶子名，
     * 二级以下的相对路径会丢，模板的 {@code src/pages/home/index.ux} 就没法还原。</p>
     */
    private void copyAssetTree(AssetManager am, String assetPath, File dest, String rel)
            throws IOException {
        String[] kids = am.list(assetPath);
        if (kids == null || kids.length == 0) {
            File out = new File(dest, rel.isEmpty() ? new File(assetPath).getName() : rel);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建 " + parent);
            }
            InputStream in = am.open(assetPath);
            OutputStream os = new FileOutputStream(out);
            try {
                VelaUtil.copy(in, os);
            } finally {
                VelaUtil.closeQuietly(in);
                VelaUtil.closeQuietly(os);
            }
            return;
        }
        for (String kid : kids) {
            if (kid.startsWith(".DS_Store") || kid.equals("Thumbs.db")) {
                continue;
            }
            copyAssetTree(am, assetPath + "/" + kid, dest,
                    rel.isEmpty() ? kid : rel + "/" + kid);
        }
    }

    static String replace(String text, LinkedHashMap<String, String> tokens) {
        String out = text == null ? "" : text;
        for (java.util.Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /** 读一个可能很大的文本到字符串，只在 {@link #read} 之外用（如日志尾巴）。 */
    public static String readCap(File f, long cap) {
        try {
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(cap, 64 * 1024));
                byte[] buf = new byte[8192];
                long left = cap;
                int n;
                while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                    out.write(buf, 0, n);
                    left -= n;
                }
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                VelaUtil.closeQuietly(in);
            }
        } catch (IOException e) {
            return "";
        }
    }
}
