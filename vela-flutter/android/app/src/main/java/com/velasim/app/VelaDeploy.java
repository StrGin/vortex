package com.velasim.app;



import java.io.File;

import java.io.FileInputStream;

import java.io.IOException;

import java.io.InputStream;

import java.io.OutputStream;

import java.net.InetSocketAddress;

import java.net.ServerSocket;

import java.net.Socket;

import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.concurrent.atomic.AtomicBoolean;



/**

 * Installs a built quick app into the running guest and starts it — no user tapping.

 *

 * Why not {@code adb push}: the NuttX adbd in this image accepts the sync: transfer

 * then dies ("failed to read copy response: EOF") and nothing lands, with both

 * platform-tools 37.0.1 and the IDE's own 36.0.0. Verified working alternative: the

 * guest pulls the file itself over slirp, because {@code /bin/curl} exists in the

 * image and the host is reachable from the guest at 10.0.2.2.

 *

 * The HTTP responder must keep the connection open until the client is done: closing

 * right after write() truncates the body at ~8 KB on the guest's curl (observed with

 * both python's HTTP/1.0 server and an explicit {@code Connection: close}).

 *

 * Command sequence mirrors Xiaomi's own toolkit

 * (@aiot-toolkit/emulator/lib/instance/miwear.js, called from

 * aiot-toolkit/lib/starter/VelaUxStarter.js:126-147):

 *   push rpk -> /data/quickapp/app/<pkg>.rpk

 *   pm install <that path>

 *   am start <pkg>

 */

public final class VelaDeploy {



    public static final String TAG = "VelaDeploy";

    /** MiwearInstance.appDir — where installed quick apps live in the guest. */

    public static final String APP_DIR = "/data/quickapp/app";

    private static final String GUEST_HOST = "10.0.2.2";



    public interface Log {

        void line(String s);

    }



    private VelaDeploy() {

    }



    // ---------------------------------------------------------------- download



    /**

     * Serves one file on a loopback port until the guest has pulled it.

     * Returns false if the guest could not be reached at all.

     */

    private static boolean serveTo(File file, int port, AtomicBoolean stop, Log log) {

        try (ServerSocket ss = new ServerSocket()) {

            ss.setReuseAddress(true);

            ss.bind(new InetSocketAddress("0.0.0.0", port), 8);

            byte[] body = readAll(file);

            long deadline = System.currentTimeMillis() + 120_000;

            while (!stop.get() && System.currentTimeMillis() < deadline) {

                Socket s = ss.accept();

                try {

                    s.setSoTimeout(15_000);

                    InputStream in = s.getInputStream();

                    OutputStream out = s.getOutputStream();

                    String req = readLine(in);

                    if (req == null || !req.startsWith("GET")) {

                        s.close();

                        continue;

                    }

                    while (true) {

                        String h = readLine(in);

                        if (h == null || h.isEmpty()) break;

                    }

                    out.write(("HTTP/1.1 200 OK\r\n"

                            + "Content-Type: application/octet-stream\r\n"

                            + "Content-Length: " + body.length + "\r\n"

                            + "Connection: keep-alive\r\n\r\n").getBytes("UTF-8"));

                    out.write(body);

                    out.flush();

                    // Let the peer drain and close; closing here is what truncated

                    // the transfer at 8 KB.

                    long until = System.currentTimeMillis() + 8_000;

                    try {

                        s.setSoTimeout(2_000);

                        while (System.currentTimeMillis() < until && in.read() >= 0) {

                            /* drain */

                        }

                    } catch (IOException ignored) {

                        /* client closed */

                    }

                    log.line("http: served " + body.length + " B");

                    return true;

                } finally {

                    try {

                        s.close();

                    } catch (IOException ignored) {

                    }

                }

            }

        } catch (IOException e) {

            log.line("http server failed: " + e);

            return false;

        }

        return false;

    }



    /** Pull {@code local} into the guest at {@code remotePath}; verifies the byte count. */

    public static boolean download(File local, String remotePath, int httpPort, int adbPort, Log log) {

        long want = local.length();

        AtomicBoolean stop = new AtomicBoolean(false);

        final String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);

        Thread server = new Thread(() -> serveTo(local, httpPort, stop, log), "vela-http");

        server.setDaemon(true);

        server.start();



        VelaAdb adb = new VelaAdb(adbPort);

        try {

            adb.connect();

            adb.shell("mkdir -p " + APP_DIR, 8_000);

            adb.shell("rm " + remotePath, 8_000);

            String url = "http://" + GUEST_HOST + ":" + httpPort + "/" + name;

            for (int attempt = 0; attempt < 3; attempt++) {

                // -s keeps nsh quiet; the guest's curl prints a progress table otherwise

                adb.shell("curl -s -o " + remotePath + " " + url, 90_000);

                long got = remoteSize(adb, remotePath);

                log.line("pull attempt " + (attempt + 1) + ": " + got + "/" + want + " B");

                if (got == want && want > 0) return true;

                try {

                    Thread.sleep(600);

                } catch (InterruptedException ignored) {

                    Thread.currentThread().interrupt();

                    return false;

                }

            }

            return false;

        } catch (IOException e) {

            log.line("adb connect failed: " + e);

            return false;

        } finally {

            stop.set(true);

            adb.close();

        }

    }



    private static long remoteSize(VelaAdb adb, String path) {

        VelaAdb.Reply r = adb.shell("ls -l " + path, 10_000);

        if (!r.isOk()) return -1;

        // nsh prints:  -rwxrwxrwx   16893 /data/.../x.rpk

        for (String tok : r.firstLine().split("\\s+")) {

            if (tok.matches("\\d+")) return Long.parseLong(tok);

        }

        return -1;

    }



    /**

     * 尾随引擎日志等客机的安装完成行（{@code executeInstall: rpk install: <rpk> … success}）。

     * 官方 {@code MiwearInstance.isAppInstalled} 用的同一条判据——shell 流里看不到任何回显。

     */

    private static boolean waitForInstall(File logFile, long from, String remoteRpk,

                                          long timeoutMs, Log log) {

        if (logFile == null || !logFile.isFile()) {

            return false;

        }

        String needle = "rpk install: " + remoteRpk;

        long pos = from;

        long start = System.currentTimeMillis();

        long lastNote = 0;

        while (System.currentTimeMillis() - start < timeoutMs) {

            long len = logFile.length();

            if (len > pos) {

                String chunk = readRange(logFile, pos, len);

                pos = len;

                for (String line : chunk.split("\r?\n")) {

                    int i = line.indexOf(needle);

                    if (i < 0) {

                        continue;

                    }

                    String tail = line.substring(i + needle.length());

                    if (tail.contains("success")) {

                        log.line("客机安装完成: " + line.trim());

                        return true;

                    }

                    if (tail.contains("fail") || tail.contains("error")) {

                        log.line("客机安装失败: " + line.trim());

                        return false;

                    }

                }

            }

            long waited = System.currentTimeMillis() - start;

            if (waited - lastNote >= 15_000) {

                lastNote = waited;

                log.line("客机安装中… 已等 " + (waited / 1000) + "s");

            }

            sleep(500);

        }

        return false;

    }



    private static String readRange(File f, long from, long to) {

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {

            raf.seek(from);

            int n = (int) Math.min(to - from, 1 << 20);

            byte[] buf = new byte[n];

            int got = raf.read(buf);

            return new String(buf, 0, Math.max(0, got), "UTF-8");

        } catch (Throwable t) {

            return "";

        }

    }



    // ---------------------------------------------------------------- install



    /**

     * Full chain: pull the rpk, {@code pm install}, then {@code am start}.

     * Returns a map shaped for the Dart side: {ok, stage, msg, package}.

     */

    public static Map<String, Object> installAndLaunch(File rpk, String pkg, int adbPort,

                                                       int httpPort, boolean launch, Log log) {

        return installAndLaunch(rpk, pkg, adbPort, httpPort, launch, false, log);

    }



    /**

     * {@code preImage=true} 走 4.0 之前那套镜像（vela-pre-*）：那套没有 {@code pm install}

     * 那一路（官方 PreInstance 是"rpk 解压到 /data/quickapp/app/&lt;pkg&gt; + vapp 启动"）。

     */

    public static Map<String, Object> installAndLaunch(File rpk, String pkg, int adbPort,

                                                       int httpPort, boolean launch,

                                                       boolean preImage, Log log) {

        if (preImage) {

            return installPreImage(rpk, pkg, adbPort, httpPort, launch, log);

        }

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("package", pkg);

        if (rpk == null || !rpk.isFile()) {

            out.put("ok", false);

            out.put("stage", "rpk");

            out.put("msg", "找不到 rpk 文件，先构建");

            return out;

        }

        String remote = APP_DIR + "/" + pkg + ".rpk";



        if (!download(rpk, remote, httpPort, adbPort, log)) {

            out.put("ok", false);

            out.put("stage", "push");

            out.put("msg", "客机没能拉到文件（检查引擎是否在跑、adb 端口、客机 curl 是否可用）");

            return out;

        }



        VelaAdb adb = new VelaAdb(adbPort);

        try {

            adb.connect();

            // common.js starts the guest service manager before installing

            adb.shell("systemd &", 8_000);

            log.line("pm install " + remote);

            File logFile = VelaEngine.lastLogFile();

            long logFrom = logFile != null && logFile.isFile() ? logFile.length() : 0;

            VelaAdb.Reply ins = adb.shell("pm install " + remote, 150_000);

            log.line("pm install -> " + (ins.isOk() ? ins.firstLine() : ins.transportError));



            // pm 的应答走客机串口，shell 流里什么都没有；客机装完会往串口打

            // "executeInstall: rpk install: <rpk> <pkg> success"（官方实现也是读这行）。

            // 实测这行可能要等好几分钟（客机安装期间会狂刷日志把 CPU 吃满），

            // 所以这里要耐心等，别像以前那样 12 秒就判失败。

            boolean installed = waitForInstall(logFile, logFrom, remote, 10 * 60_000, log);

            if (!installed) {

                // 老镜像可能不打那行：退回目录探测，给足时间。

                for (int i = 0; i < 20 && !installed; i++) {

                    installed = adb.pathExists(APP_DIR + "/" + pkg)

                            || adb.pathExists(APP_DIR + "/" + pkg + "/manifest.json");

                    if (!installed) {

                        sleep(2_000);

                    }

                }

            }

            if (!installed) {

                out.put("ok", false);

                out.put("stage", "install");

                out.put("msg", "等了 10 分钟也没看到客机报安装完成；看引擎日志里有没有 "

                        + "executeInstall: rpk install … success");

                return out;

            }



            if (launch) {

                log.line("am start " + pkg);

                VelaAdb.Reply st = adb.shell("am start " + pkg, 60_000);

                log.line("am start -> " + (st.isOk() ? st.firstLine() : st.transportError));

            }

            out.put("ok", true);

            out.put("stage", launch ? "launched" : "installed");

            out.put("msg", installed ? "已安装" + (launch ? "并启动" : "") : "已安装");

            return out;

        } catch (IOException e) {

            out.put("ok", false);

            out.put("stage", "adb");

            out.put("msg", "adb 连接失败: " + e.getMessage());

            return out;

        } finally {

            adb.close();

        }

    }



    // ------------------------------------------------------------- pre-4.0 镜像



    /**

     * 4.0 之前的镜像（vela-pre-4.0，手环那类设备在用）：**没有 pm install**，

     * 官方 {@code PreInstance} 的做法是

     * <pre>

     *   mkdir /data/quickapp/app/&lt;pkg&gt;

     *   unzip -o &lt;rpk&gt; -d /data/quickapp/app/&lt;pkg&gt;

     *   vapp &lt;pkg&gt; &amp;            # 相对 /data/quickapp（运行时自己拼 app/ 前缀）

     * </pre>

     * 另外两处坑（实测）：① 启动时 AIOTJS 会读 {@code META-INF/build.txt}，缺了直接

     * {@code Abort App}——新工具链不生成它，得补；② 这类镜像启动后**屏幕是熄的**，

     * 画了也看不见，装完要让 UI 发一次电源键（见 Dart 侧 installAndLaunch）。

     */

    private static Map<String, Object> installPreImage(File rpk, String pkg, int adbPort,

                                                       int httpPort, boolean launch, Log log) {

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("package", pkg);

        out.put("preImage", true);

        if (rpk == null || !rpk.isFile()) {

            out.put("ok", false);

            out.put("stage", "rpk");

            out.put("msg", "找不到 rpk 文件，先构建");

            return out;

        }

        String remote = APP_DIR + "/" + pkg + ".rpk";

        if (!download(rpk, remote, httpPort, adbPort, log)) {

            out.put("ok", false);

            out.put("stage", "push");

            out.put("msg", "客机没能拉到文件（检查引擎是否在跑、adb 端口、客机 curl 是否可用）");

            return out;

        }

        String appDir = APP_DIR + "/" + pkg;

        VelaAdb adb = new VelaAdb(adbPort);

        try {

            adb.connect();

            // 客机的 mkdir 没有 -p，已存在会报错；目录只在第一次建，忽略回显。

            adb.shell("mkdir " + appDir, 15_000);

            log.line("unzip " + remote + " -> " + appDir);

            VelaAdb.Reply uz = adb.shell("unzip -o " + remote + " -d " + appDir, 300_000);

            log.line("unzip -> " + VelaUtil.ellipsize(uz.stdout.trim(), 100));

            writeBuildTxt(adb, appDir, log);

            if (!launch) {

                out.put("ok", true);

                out.put("stage", "installed");

                out.put("msg", "已安装（pre-4.0 镜像：解压到 " + appDir + "）");

                return out;

            }

            log.line("vapp " + pkg);

            // 后台跑：前台跑会把 shell 流一直挂着（vapp 不退出）。

            adb.shell("sh -c 'vapp " + pkg + " > /data/tmp/" + pkg + ".log 2>&1 &'", 30_000);

            out.put("ok", true);

            out.put("stage", "launched");

            out.put("msg", "已安装并启动（pre-4.0 镜像）");

            return out;

        } catch (IOException e) {

            out.put("ok", false);

            out.put("stage", "adb");

            out.put("msg", "adb 连接失败: " + e.getMessage());

            return out;

        } finally {

            adb.close();

        }

    }



    /**

     * 补 {@code META-INF/build.txt}：4.0 之前的 AIOTJS 启动时读它，缺了就 {@code Abort App}

     * （老工具链会写，2.x 工具链改成往 manifest 里塞 packageInfo，不再生成这个文件）。

     */

    private static void writeBuildTxt(VelaAdb adb, String appDir, Log log) {

        String dir = appDir + "/META-INF";

        String path = dir + "/build.txt";

        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",

                java.util.Locale.US).format(new java.util.Date());

        String[] lines = {

                "originType=cmd",

                "toolkit=2.0.5",

                "timeStamp=" + stamp,

                "node=v22.16.0",

                "platform=android",

                "arch=aarch64",

                "component=false",

        };

        StringBuilder sb = new StringBuilder("mkdir ").append(dir).append(';');

        for (int i = 0; i < lines.length; i++) {

            sb.append("echo ").append(lines[i]).append(i == 0 ? " > " : " >> ").append(path).append(';');

        }

        VelaAdb.Reply r = adb.shell(sb.toString(), 30_000);

        log.line("META-INF/build.txt -> " + (r.isOk() ? "ok" : r.transportError));

    }



    // ------------------------------------------------------------------ query



    /** 客机装完会把它登记进这个 JSON（package/version/installedPath…）。 */

    public static final String REGISTRY = "/data/app/packages.list";



    /**

     * Installed quick-app packages.

     *

     * <p>优先读客机的包注册表 {@code /data/app/packages.list}：rpk 安装后落在

     * {@code /data/app/<pkg>/}，而 {@code /data/quickapp/app} 只留安装源（一个 .rpk 文件），

     * 所以只列后者会出现"装好了却看不到"。</p>

     */

    public static List<String> listApps(int adbPort, Log log) {

        List<String> out = new ArrayList<>();

        VelaAdb adb = new VelaAdb(adbPort);

        try {

            adb.connect();

            VelaAdb.Reply reg = adb.shell("cat " + REGISTRY, 20_000);

            if (reg.isOk() && reg.stdout.contains("\"package\"")) {

                java.util.regex.Matcher m = java.util.regex.Pattern

                        .compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(reg.stdout);

                while (m.find()) {

                    String pkg = m.group(1).trim();

                    if (!pkg.isEmpty() && !out.contains(pkg)) {

                        out.add(pkg);

                    }

                }

                if (!out.isEmpty()) {

                    return out;

                }

            }

            VelaAdb.Reply r = adb.shell("ls " + APP_DIR, 20_000);

            if (!r.isOk()) return out;

            for (String line : r.stdout.split("\n")) {

                String s = line.trim();

                if (s.isEmpty()) continue;

                // nsh lists dirs with a trailing '/', files with sizes on other lines

                if (s.endsWith("/")) s = s.substring(0, s.length() - 1);

                if (s.endsWith(".rpk")) continue;

                if (s.startsWith("com.") || s.contains(".")) {

                    if (!out.contains(s)) out.add(s);

                }

            }

        } catch (IOException e) {

            log.line("listApps: " + e);

        } finally {

            adb.close();

        }

        return out;

    }



    public static Map<String, Object> stopApp(String pkg, int adbPort) {

        return stopApp(pkg, adbPort, false);

    }



    /**

     * pre-4.0 客机里既没有框架的 mq（{@code am stop} 没用），nsh 也没有 {@code pkill}，

     * 只能按 pid 杀；实测客机的 kill 对 vapp 常常无效（返回 0 进程还在），

     * 所以这里杀完再查一次，如实回报，别让 UI 误报成功。

     */

    public static Map<String, Object> stopApp(String pkg, int adbPort, boolean preImage) {

        if (!preImage) {

            return simple(pkg, adbPort, "am stop " + pkg, "stopApp");

        }

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("package", pkg);

        out.put("stage", "stopApp");

        VelaAdb adb = new VelaAdb(adbPort);

        try {

            adb.connect();

            int pid = vappPid(adb);

            if (pid <= 0) {

                out.put("ok", true);

                out.put("msg", "客机里没有在跑的 vapp 应用");

                return out;

            }

            adb.shell("kill -9 " + pid, 10_000);

            boolean gone = vappPid(adb) != pid;

            out.put("ok", gone);

            out.put("msg", gone ? "已停止（pid " + pid + "）"

                    : "客机的 kill 对这个应用无效（pre-4.0 镜像的已知限制）");

            return out;

        } catch (IOException e) {

            out.put("ok", false);

            out.put("msg", "adb 连接失败: " + e.getMessage());

            return out;

        } finally {

            adb.close();

        }

    }



    /** 客机里 vapp 应用的 PID；没有返回 -1（客机的 nsh 没有 pkill，只能自己解析 ps）。 */

    private static int vappPid(VelaAdb adb) {
        VelaAdb.Reply ps = adb.shell("ps", 15_000);
        for (String line : ps.stdout.split("\r?\n")) {
            String t = line.trim();
            if (t.indexOf(" vapp") < 0) {
                continue;
            }
            String first = t.split("\s+")[0];
            try {
                return Integer.parseInt(first);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }



    /** 卸载：先走 5.0 的 {@code pm uninstall}，再按 pre-4.0 的目录删法兜底（谁在就删谁）。 */

    public static Map<String, Object> uninstall(String pkg, int adbPort) {

        Map<String, Object> out = simple(pkg, adbPort, "pm uninstall " + pkg, "uninstallApp");

        VelaAdb adb = new VelaAdb(adbPort);

        try {

            adb.connect();

            adb.shell("rm -r " + APP_DIR + "/" + pkg, 30_000);

        } catch (IOException ignored) {

        } finally {

            adb.close();

        }

        return out;

    }



    public static Map<String, Object> launch(String pkg, int adbPort) {

        return launch(pkg, adbPort, false);

    }



    /** 启动已装 app：5.0 走 {@code am start}，pre-4.0 走 {@code vapp <pkg>}（后台）。 */

    public static Map<String, Object> launch(String pkg, int adbPort, boolean preImage) {

        if (preImage) {

            return simple(pkg, adbPort,

                    "sh -c 'vapp " + pkg + " > /data/tmp/" + pkg + ".log 2>&1 &'", "launchApp");

        }

        return simple(pkg, adbPort, "am start " + pkg, "launchApp");

    }



    private static Map<String, Object> simple(String pkg, int adbPort, String cmd, String stage) {

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("package", pkg);

        VelaAdb adb = new VelaAdb(adbPort);

        try {

            adb.connect();

            VelaAdb.Reply r = adb.shell(cmd, 60_000);

            out.put("ok", r.isOk());

            out.put("stage", stage);

            out.put("msg", r.isOk() ? r.firstLine() : r.transportError);

        } catch (IOException e) {

            out.put("ok", false);

            out.put("stage", stage);

            out.put("msg", "adb 连接失败: " + e.getMessage());

        } finally {

            adb.close();

        }

        return out;

    }



    /** Pick a free loopback port to serve artifacts from. */

    public static int freePort() {

        try (ServerSocket s = new ServerSocket(0)) {

            return s.getLocalPort();

        } catch (IOException e) {

            return 39517;

        }

    }



    // ----------------------------------------------------------------- helpers



    private static void sleep(long ms) {

        try {

            Thread.sleep(ms);

        } catch (InterruptedException ignored) {

            Thread.currentThread().interrupt();

        }

    }



    private static String readLine(InputStream in) throws IOException {

        StringBuilder sb = new StringBuilder();

        int c;

        while ((c = in.read()) != -1) {

            if (c == '\n') break;

            if (c != '\r') sb.append((char) c);

        }

        return c == -1 && sb.length() == 0 ? null : sb.toString();

    }



    private static byte[] readAll(File f) throws IOException {

        byte[] buf = new byte[(int) f.length()];

        try (InputStream in = new FileInputStream(f)) {

            int off = 0;

            while (off < buf.length) {

                int n = in.read(buf, off, buf.length - off);

                if (n <= 0) break;

                off += n;

            }

        }

        return buf;

    }

}

