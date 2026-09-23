package com.velasim.app;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 最小 adb 客户端（只用 java.net / java.io），走模拟器在 {@code 127.0.0.1:<adbPort>}
 * 上暴露的 adb 桥，直连 guest 里的 adbd。
 *
 * <pre>
 *   帧     24 字节小端头 command, arg0, arg1, dataLength, dataChecksum, magic
 *          magic = command ^ 0xffffffff（adbd 会校验）
 *          dataChecksum = CRC-32(载荷)（protocol ≥ 1；和平台工具 adb 一致）
 *   握手   CNXN(0x4e584e43) arg0=version 0x01000001 arg1=maxdata 262144
 *          载荷 host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push
 *   流     OPEN(0x4e45504f) + \0 结尾的服务串，随后 OKAY(0x59414b4f) /
 *          WRTE(0x45545257) / CLSE(0x45534c43) 逐条流控
 *   推文件 服务 "sync:"，内部是 4 字节 ASCII 标签 + 4 字节小端长度 + 载荷
 *          （STAT / SEND / DATA / DONE / OKAY / FAIL / QUIT）
 * </pre>
 *
 * <p>同一时刻只允许一条命令在飞（{@link #transport} 上的锁）：adbd 的流控是按
 * WRTE 串行回 OKAY 的，交错写会把 ACK 对上号。所有公开方法自带重连，失败时
 * 回一个带中文原因的 {@link Reply}/false，而不是把异常抛给 UI。</p>
 */
public final class VelaAdb {

    /** 引擎固定 {@code -ports 5554,5555}，5555 是 adb 桥。 */
    public static final int DEFAULT_PORT = VelaEngine.ADB_PORT_ODD;

    // adb 的每个命令都是 4 字节 ASCII 小端整数：CNXN/OPEN/OKAY/CLSE/WRTE/AUTH。
    // （旧版这里写成 0x01000001…0x01000006 —— 那是"版本号"那一位的取值，客机 adbd
    // 收到的命令无法识别，握手时直接断连。）
    static final int A_CNXN = 0x4e584e43; // "CNXN"
    static final int A_OPEN = 0x4e45504f; // "OPEN"
    static final int A_OKAY = 0x59414b4f; // "OKAY"
    static final int A_CLSE = 0x45534c43; // "CLSE"
    static final int A_WRTE = 0x45545257; // "WRTE"
    static final int A_AUTH = 0x48545541; // "AUTH"

    private static final int VERSION = 0x01000001;
    private static final int MAXDATA = 0x4000;
    /** 与平台 adb 发给同一客机的 banner 一致（客机按 features= 解析，缺项会被拒）。 */
    private static final String BANNER =
            "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,"
                    + "fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,"
                    + "sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,"
                    + "sendrecv_v2_dry_run_send,devicetracker_proto_format,devraw,app_info,"
                    + "server_status,track_mdns";
    private static final int HEADER_LEN = 24;
    /** sync 的 DATA 块上限；取小值兼容 maxdata 更小的 NuttX adbd。 */
    private static final int SYNC_CHUNK = 64 * 1024;

    private static final int TAG_STAT = fourCC("STAT");
    private static final int TAG_SEND = fourCC("SEND");
    private static final int TAG_DATA = fourCC("DATA");
    private static final int TAG_DONE = fourCC("DONE");
    private static final int TAG_OKAY = fourCC("OKAY");
    private static final int TAG_FAIL = fourCC("FAIL");
    private static final int TAG_READ = fourCC("READ");
    private static final int TAG_QUIT = fourCC("QUIT");
    /** Android 2.x 时代的 sync 用数字 id，回复 id = 请求 id ^ 0x00020202。 */
    private static final int SYNC_MAPPING_XOR = 0x00020202;

    private static final String TAG = "adb";
    /** guest 侧快应用安装根（IDE 的 build:push 也推到这里）。 */
    public static final String QUICKAPP_ROOT = "/syscore/mina/sys/app";

    /** 一次 shell 的结果。{@code transportError} 非空表示连接层面的失败。 */
    public static final class Reply {
        public final String stdout;
        public final String transportError;

        Reply(String stdout, String transportError) {
            this.stdout = stdout == null ? "" : stdout;
            this.transportError = transportError;
        }

        public boolean isOk() {
            return transportError == null;
        }

        /** 首行非空回显，便于判断 nsh 的提示。 */
        public String firstLine() {
            for (String line : stdout.split("\r?\n")) {
                if (!line.trim().isEmpty()) {
                    return line.trim();
                }
            }
            return "";
        }

        public static Reply error(String why) {
            return new Reply("", why);
        }
    }

    public static final class FileStat {
        public final int mode;
        public final long size;
        public final long mtime;

        FileStat(int mode, long size, long mtime) {
            this.mode = mode;
            this.size = size;
            this.mtime = mtime;
        }
    }

    public interface Progress {
        void onPush(String remotePath, long done, long total);
    }

    private final Object transport = new Object();
    private final int port;
    private final int connectTimeoutMs;
    private final int ioTimeoutMs;

    private Socket socket;
    private InputStream input;
    private OutputStream output;
    private int peerMaxdata = MAXDATA;
    private int nextLocalId = 1;
    private String peerBanner;

    public VelaAdb() {
        this(DEFAULT_PORT, 3000, 30000);
    }

    public VelaAdb(int port) {
        this(port, 3000, 30000);
    }

    public VelaAdb(int port, int connectTimeoutMs, int ioTimeoutMs) {
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.ioTimeoutMs = ioTimeoutMs;
    }

    public int port() {
        return port;
    }

    /** adbd 在 CNXN 里报的 {@code device::...} 串，未连接时为 null。 */
    public String peerBanner() {
        return peerBanner;
    }

    // ------------------------------------------------------------- lifecycle

    /** 只看 {@code port} 上有没有 adbd 应答，不下发命令。 */
    public static boolean probe(int port, int timeoutMs) {
        VelaAdb adb = new VelaAdb(port, Math.max(200, Math.min(timeoutMs, 3000)), timeoutMs);
        try {
            adb.connect();
            return true;
        } catch (Throwable t) {
            VelaLog.d(TAG, "probe " + port + " failed: " + t);
            return false;
        } finally {
            adb.close();
        }
    }

    /** 诊断页用：guest 身份一行；拿不到返回 null。 */
    public static String describeGuest(int port) {
        VelaAdb adb = new VelaAdb(port, 1500, 5000);
        try {
            adb.connect();
            String banner = adb.peerBanner();
            String uname = adb.shell("uname -a").stdout.trim();
            return VelaUtil.firstNonEmpty(banner, uname);
        } catch (Throwable t) {
            VelaLog.d(TAG, "describeGuest failed: " + t);
            return null;
        } finally {
            adb.close();
        }
    }

    public void close() {
        synchronized (transport) {
            VelaUtil.closeQuietly(input);
            VelaUtil.closeQuietly(output);
            if (socket != null && !socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            socket = null;
            input = null;
            output = null;
            peerBanner = null;
        }
    }

    /** CNXN 握手，幂等。收到 A_AUTH 视为不可用（guest 开了 ro.adb.secure）。 */
    public void connect() throws IOException {
        synchronized (transport) {
            if (socket != null && socket.isConnected() && !socket.isClosed()) {
                return;
            }
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), connectTimeoutMs);
                s.setTcpNoDelay(true);
                s.setSoTimeout(ioTimeoutMs);
                input = s.getInputStream();
                output = s.getOutputStream();
                socket = s;
                handshake();
                VelaLog.i(TAG, "connected 127.0.0.1:" + port + " banner="
                        + VelaUtil.ellipsize(String.valueOf(peerBanner), 96));
            } catch (IOException e) {
                VelaUtil.closeQuietly(s);
                socket = null;
                input = null;
                output = null;
                throw new IOException("连不上 127.0.0.1:" + port + " 的 adb 桥（模拟器未启动或端口被占）："
                                + e, e);
            }
        }
    }

    private void handshake() throws IOException {
        Message cnxn = new Message();
        cnxn.command = A_CNXN;
        cnxn.arg0 = VERSION;
        cnxn.arg1 = MAXDATA;
        cnxn.data = utf8(BANNER);
        write(cnxn);
        for (int i = 0; i < 8; i++) {
            Message m = read();
            if (m == null) {
                throw new IOException("adb 桥在握手时关掉了连接");
            }
            if (m.command == A_CNXN) {
                peerBanner = new String(m.data, "UTF-8").trim();
                if (m.arg1 > 0 && m.arg1 <= MAXDATA) {
                    peerMaxdata = m.arg1;
                }
                return;
            }
            if (m.command == A_AUTH) {
                throw new IOException("guest adbd 要求授权认证（A_AUTH），当前实现不带 RSA 私钥");
            }
            VelaLog.d(TAG, "ignoring pre-CNXN frame " + name(m.command));
        }
        throw new IOException("等了 8 帧还没等到 CNXN");
    }

    // ----------------------------------------------------------------- shell

    public Reply shell(String cmd) {
        return shell(cmd, ioTimeoutMs);
    }

    /** {@code shell:<cmd>}，一次性取全部回显（plain shell 已合并 stdout/stderr）。 */
    public Reply shell(final String cmd, final int timeoutMs) {
        synchronized (transport) {
            try {
                connect();
                return new Reply(runShell(cmd, timeoutMs), null);
            } catch (Throwable t) {
                close();
                VelaLog.w(TAG, "shell " + VelaUtil.ellipsize(cmd, 120) + " failed: " + t);
                return Reply.error(describe(t));
            }
        }
    }

    private String runShell(String cmd, int timeoutMs) throws IOException {
        int local = nextLocalId++;
        int remote = open(local, "shell:" + cmd);
        StringBuilder out = new StringBuilder();
        int saved = socket.getSoTimeout();
        socket.setSoTimeout(Math.max(1000, timeoutMs));
        try {
            while (true) {
                Message m = read();
                if (m == null || (m.command == A_CLSE && (m.arg1 == local || m.arg0 == local))) {
                    break;
                }
                if (m.command == A_WRTE && m.arg1 == local) {
                    out.append(new String(m.data, "UTF-8"));
                    ack(m.arg1, m.arg0);
                }
            }
        } finally {
            socket.setSoTimeout(saved);
            closeStream(local, remote);
        }
        return out.toString();
    }

    /**
     * 逐条试候选命令，返回第一条看起来成功的。
     *
     * <p>nsh 的 {@code ||} 支持度不确定，所以逻辑或留在 Java 这边；
     * adbd 也不回传退出码，只能靠回显里有没有 {@code command not found}。</p>
     */
    public Reply shellFirst(List<String> cmds, int timeoutMs) {
        Reply last = null;
        for (String c : cmds) {
            Reply r = shell(c, timeoutMs);
            if (r.isOk() && !looksFailed(r.stdout)) {
                return new Reply(c + " -> " + r.stdout, null);
            }
            last = r;
        }
        return last == null ? Reply.error("没有可试的命令") : last;
    }

    private static boolean looksFailed(String out) {
        if (out == null) {
            return false;
        }
        String s = out.toLowerCase(Locale.US);
        return s.contains("command not found") || s.contains("unknown command")
                || s.contains("no such file") || s.contains("invalid argument");
    }

    // ----------------------------------------------------------- file layout

    /** 单个文件推送；sync: 不可用时退回 base64 分块（只适合小文件）。 */
    public boolean push(File local, String remotePath, int mode, Progress cb) {
        synchronized (transport) {
            try {
                connect();
            } catch (Throwable t) {
                close();
                VelaLog.w(TAG, "push: " + t);
                return false;
            }
            try {
                if (syncPush(local, remotePath, mode, cb)) {
                    return true;
                }
            } catch (Throwable t) {
                VelaLog.w(TAG, "sync push " + remotePath + " failed: " + t);
                close();
                try {
                    connect();
                } catch (Throwable again) {
                    return false;
                }
            }
            return pushViaShellBase64(local, remotePath, mode);
        }
    }

    /** 目录树递归推送；返回文件数，负数表示中途失败。 */
    public int pushTree(File localDir, String remoteDir, Progress cb, List<String> skip) {
        List<String> paths = new ArrayList<>();
        List<File> files = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        collect(localDir, "", dirs, files, paths, skip);
        for (String rel : dirs) {
            if (!mkdirs(remoteDir + "/" + rel)) {
                VelaLog.w(TAG, "mkdir -p refused for " + remoteDir + "/" + rel);
            }
        }
        int pushed = 0;
        for (int i = 0; i < files.size(); i++) {
            String remote = remoteDir + "/" + paths.get(i);
            if (!push(files.get(i), remote, 0644, cb)) {
                VelaLog.w(TAG, "push refused: " + remote);
                return -1;
            }
            pushed++;
        }
        return pushed;
    }

    private static void collect(File dir, String rel, List<String> dirs, List<File> files,
                               List<String> paths, List<String> skip) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        Arrays.sort(kids);
        for (File k : kids) {
            String name = k.getName();
            if (name.startsWith(".") || (skip != null && skip.contains(name))) {
                continue;
            }
            String childRel = rel.isEmpty() ? name : rel + "/" + name;
            if (k.isDirectory()) {
                dirs.add(childRel);
                collect(k, childRel, dirs, files, paths, skip);
            } else if (k.isFile()) {
                files.add(k);
                paths.add(childRel);
            }
        }
    }

    public boolean mkdirs(String remoteDir) {
        return shell("mkdir -p " + VelaUtil.shellQuote(remoteDir), 8000).isOk();
    }

    /** 删除后回读确认：nsh 的退出码不可靠。 */
    public boolean remove(String remotePath, boolean recursive) {
        shell("rm " + (recursive ? "-r" : "") + " " + VelaUtil.shellQuote(remotePath), 15000);
        return !pathExists(remotePath);
    }

    /** {@code ls <path>} 的回显是否表明路径存在（空目录也算存在）。 */
    public boolean pathExists(String remotePath) {
        Reply r = shell("ls " + VelaUtil.shellQuote(remotePath), 8000);
        if (!r.isOk()) {
            return false;
        }
        String s = r.stdout.trim();
        if (!s.isEmpty()) {
            String low = s.toLowerCase(Locale.US);
            if (low.contains("no such file") || low.contains("does not exist")
                    || low.contains("permission denied")) {
                return false;
            }
            return true;
        }
        // 空目录 ls 无回显：退回 sync STAT（mode=0 才视为不存在）。
        FileStat st = stat(remotePath);
        return st != null && st.mode != 0;
    }

    /** {@code ls <dir>} 的条目名（nsh 是每行一条，也兼容空格分隔）。 */
    public List<String> listRemote(String remoteDir) {
        List<String> out = new ArrayList<>();
        Reply r = shell("ls " + VelaUtil.shellQuote(remoteDir), 10000);
        if (!r.isOk()) {
            return out;
        }
        for (String line : r.stdout.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.toLowerCase(Locale.US).contains("nsh:")
                    || t.toLowerCase(Locale.US).contains("no such file")) {
                continue;
            }
            for (String token : t.split("\\s+")) {
                if (!token.isEmpty() && !token.contains("/") && !out.contains(token)) {
                    out.add(token);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ sync wire

    private boolean syncPush(File local, String remotePath, int mode, Progress cb)
            throws IOException {
        byte[] payload = utf8("SEND," + remotePath + "," + Integer.toString(mode, 8));
        int streamId = nextLocalId++;
        SyncStream s = new SyncStream(streamId, open(streamId, "sync:"));
        try {
            s.request(payload);
            long total = local.length();
            long done = 0;
            byte[] buf = new byte[chunkSize()];
            InputStream in = new FileInputStream(local);
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    ByteArrayOutputStream req = new ByteArrayOutputStream(n + 8);
                    writeTag(req, TAG_DATA);
                    writeLen(req, n);
                    req.write(buf, 0, n);
                    s.request(req.toByteArray());
                    done += n;
                    if (cb != null) {
                        cb.onPush(remotePath, done, total);
                    }
                }
            } finally {
                VelaUtil.closeQuietly(in);
            }
            ByteArrayOutputStream end = new ByteArrayOutputStream(8);
            writeTag(end, TAG_DONE);
            writeLen(end, 0);
            s.request(end.toByteArray());
            int tag = s.replyTag();
            if (tag == TAG_OKAY || tag == TAG_QUIT) {
                return true;
            }
            if (tag == TAG_FAIL) {
                VelaLog.w(TAG, "sync SEND refused: " + s.replyText());
            } else {
                VelaLog.w(TAG, "sync SEND got tag " + tagToString(tag));
            }
            return false;
        } finally {
            s.close();
        }
    }

    /** sync: 建目录（部分 adbd 支持以 / 结尾的 SEND）。 */
    public boolean syncMkdir(String remoteDir, int mode) {
        synchronized (transport) {
            try {
                connect();
                int streamId = nextLocalId++;
                SyncStream s = new SyncStream(streamId, open(streamId, "sync:"));
                try {
                    s.request(utf8("SEND," + remoteDir + "/" + "," + Integer.toString(mode, 8)));
                    ByteArrayOutputStream end = new ByteArrayOutputStream(8);
                    writeTag(end, TAG_DONE);
                    writeLen(end, 0);
                    s.request(end.toByteArray());
                    return s.replyTag() == TAG_OKAY;
                } finally {
                    s.close();
                }
            } catch (Throwable t) {
                close();
                VelaLog.d(TAG, "syncMkdir " + remoteDir + ": " + t);
                return false;
            }
        }
    }

    /** STAT 一个远端路径；不存在或 adbd 不支持时返回 null。 */
    public FileStat stat(String remotePath) {
        synchronized (transport) {
            try {
                connect();
                int streamId = nextLocalId++;
                SyncStream s = new SyncStream(streamId, open(streamId, "sync:"));
                try {
                    ByteArrayOutputStream req = new ByteArrayOutputStream(8 + remotePath.length());
                    writeTag(req, TAG_STAT);
                    byte[] path = utf8(remotePath);
                    writeLen(req, path.length);
                    req.write(path, 0, path.length);
                    s.request(req.toByteArray());
                    if (s.replyTag() != TAG_STAT) {
                        return null;
                    }
                    byte[] body = s.replyBody();
                    if (body.length < 12) {
                        return null;
                    }
                    return new FileStat(get32(body, 0), get32(body, 4) & 0xffffffffL,
                            get32(body, 8) & 0xffffffffL);
                } finally {
                    s.close();
                }
            } catch (Throwable t) {
                close();
                VelaLog.d(TAG, "stat " + remotePath + ": " + t);
                return null;
            }
        }
    }

    private int chunkSize() {
        int n = Math.min(SYNC_CHUNK, peerMaxdata - 8);
        return n < 4096 ? 4096 : n;
    }

    /**
     * sync 服务的一条流。
     *
     * <p>请求写完后必须等本流的 OKAY；adbd 也可能先塞来一个 WRTE（例如 FAIL），
     * 所以等 ACK 的过程中收到的数据要暂存，别让 socket 缓冲塞住。</p>
     */
    private final class SyncStream {
        private final int local;
        private final int remote;
        private final ByteArrayOutputStream carried = new ByteArrayOutputStream();
        private int cursor;
        private byte[] lastBody = new byte[0];

        SyncStream(int localId, int remoteId) {
            this.local = localId;
            this.remote = remoteId;
        }

        void request(byte[] payload) throws IOException {
            writeWrote(local, remote, payload);
            int guard = 0;
            while (guard++ < 65536) {
                Message m = read();
                if (m == null) {
                    throw new IOException("adbd 在 sync 传输中断开了连接");
                }
                if (m.command == A_WRTE && m.arg1 == local) {
                    carried.write(m.data, 0, m.data.length);
                    ack(m.arg1, m.arg0);
                    continue;
                }
                if (m.command == A_CLSE) {
                    throw new IOException("adbd 关闭了 sync 流");
                }
                if (m.command == A_OKAY && m.arg0 == remote && m.arg1 == local) {
                    return;
                }
            }
            throw new IOException("sync 流控循环超限");
        }

        /** 读一个回复的标签（ASCII 4CC 或旧版数字 id 都归一到 4CC）。 */
        int replyTag() throws IOException {
            byte[] head = need(8);
            int tag = mapSyncTag(get32(head, 0));
            int len = get32(head, 4);
            lastBody = len > 0 ? need(len) : new byte[0];
            return tag;
        }

        byte[] replyBody() {
            return lastBody;
        }

        String replyText() {
            return new String(lastBody, java.nio.charset.Charset.forName("UTF-8")).trim();
        }

        private byte[] need(int n) throws IOException {
            while (carried.size() - cursor < n) {
                Message m = read();
                if (m == null) {
                    throw new EOFException("sync 回复被截断");
                }
                if (m.command == A_WRTE && m.arg1 == local) {
                    carried.write(m.data, 0, m.data.length);
                    ack(m.arg1, m.arg0);
                } else if (m.command == A_CLSE) {
                    throw new EOFException("adbd 关闭了 sync 流");
                }
            }
            byte[] all = carried.toByteArray();
            byte[] out = new byte[n];
            System.arraycopy(all, cursor, out, 0, n);
            cursor += n;
            if (cursor == all.length) {
                carried.reset();
                cursor = 0;
            }
            return out;
        }

        void close() {
            closeStream(local, remote);
        }
    }

    private static int mapSyncTag(int word) {
        if (word == TAG_STAT || word == TAG_SEND || word == TAG_DATA || word == TAG_DONE
                || word == TAG_OKAY || word == TAG_FAIL || word == TAG_READ || word == TAG_QUIT) {
            return word;
        }
        // 旧版：回复 id = 请求 id ^ 0x00020202，反向映射回 4CC。
        int back = word ^ SYNC_MAPPING_XOR;
        switch (back) {
            case 0: return TAG_OKAY;
            case 1: return TAG_STAT;
            case 2: return TAG_FAIL;
            case 3: return TAG_DATA;
            case 4: return TAG_DONE;
            case 5: return TAG_READ;
            default: return word;
        }
    }

    private void writeTag(ByteArrayOutputStream out, int tag) {
        byte[] b = new byte[4];
        put32(b, 0, tag);
        out.write(b, 0, 4);
    }

    private void writeLen(ByteArrayOutputStream out, int len) {
        byte[] b = new byte[4];
        put32(b, 0, len);
        out.write(b, 0, 4);
    }

    /** adbd 没实现 sync: 时的兜底：base64 分块（只在 <= 48 KB 时够用）。 */
    private boolean pushViaShellBase64(File local, String remotePath, int mode) {
        if (local.length() > 48 * 1024) {
            VelaLog.w(TAG, "sync: 不可用，且文件过大无法走 base64 兜底: " + local.length() + " B");
            return false;
        }
        byte[] raw;
        try {
            raw = VelaUtil.readBytes(new FileInputStream(local));
        } catch (IOException e) {
            return false;
        }
        mkdirs(parentOf(remotePath));
        shell("rm " + VelaUtil.shellQuote(remotePath), 8000);
        // 分块 append：一条 echo 太长会被 nsh 的行缓冲截断。
        int chunk = 3072;
        String b64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
        String target = VelaUtil.shellQuote(remotePath);
        for (int i = 0; i < b64.length(); i += chunk) {
            String part = b64.substring(i, Math.min(b64.length(), i + chunk));
            Reply r = shell("echo " + VelaUtil.shellQuote(part)
                    + (i == 0 ? " > " : " >> ") + target, 15000);
            if (!r.isOk()) {
                return false;
            }
        }
        Reply decode = shell("base64 -d " + target + " > " + target + ".b && mv "
                + target + ".b " + target, 15000);
        if (!decode.isOk() || !pathExists(remotePath)) {
            VelaLog.w(TAG, "base64 兜底没落盘: " + remotePath);
            return false;
        }
        shell("chmod " + Integer.toString(mode, 8) + " " + target, 8000);
        return true;
    }

    private static String parentOf(String remotePath) {
        int slash = remotePath.lastIndexOf('/');
        return slash <= 0 ? "/" : remotePath.substring(0, slash);
    }

    // ------------------------------------------------------------- transport

    private static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] data = new byte[0];
    }

    /** OPEN 一个服务，返回 adbd 侧的 remoteId。 */
    private int open(int localId, String service) throws IOException {
        Message m = new Message();
        m.command = A_OPEN;
        m.arg0 = localId;
        m.arg1 = 0;
        // NuttX 侧 adbd 用 strcmp 解析服务串，所以带上结尾的 \0。
        m.data = utf8(service + '\0');
        write(m);
        while (true) {
            Message r = read();
            if (r == null) {
                throw new IOException("OPEN 没有应答");
            }
            if (r.command == A_OKAY && r.arg1 == localId) {
                return r.arg0;
            }
            if (r.command == A_CLSE) {
                throw new IOException("服务被拒绝: " + service);
            }
            if (r.command == A_WRTE) {
                ack(r.arg1, r.arg0);
            }
        }
    }

    private void ack(int localId, int remoteId) throws IOException {
        Message m = new Message();
        m.command = A_OKAY;
        m.arg0 = localId;
        m.arg1 = remoteId;
        write(m);
    }

    private void writeWrote(int localId, int remoteId, byte[] data) throws IOException {
        Message m = new Message();
        m.command = A_WRTE;
        m.arg0 = localId;
        m.arg1 = remoteId;
        m.data = data;
        write(m);
    }

    private void closeStream(int localId, int remoteId) {
        try {
            Message m = new Message();
            m.command = A_CLSE;
            m.arg0 = localId;
            m.arg1 = remoteId;
            write(m);
        } catch (IOException e) {
            VelaLog.d(TAG, "CLSE lost: " + e);
        }
    }

    private void write(Message m) throws IOException {
        OutputStream out = output;
        if (out == null) {
            throw new IOException("adb 未连接");
        }
        byte[] head = new byte[HEADER_LEN];
        put32(head, 0, m.command);
        put32(head, 4, m.arg0);
        put32(head, 8, m.arg1);
        put32(head, 12, m.data.length);
        put32(head, 16, checksum(m.data));
        // 第 6 个 u32 是 magic = command ^ 0xffffffff：adbd 会校验它，写 0 会被当坏帧丢弃。
        put32(head, 20, m.command ^ 0xffffffff);
        synchronized (out) {
            out.write(head);
            if (m.data.length > 0) {
                out.write(m.data);
            }
            out.flush();
        }
    }

    /** 一帧；对端在半帧处关闭时返回 null。 */
    private Message read() throws IOException {
        InputStream in = input;
        if (in == null) {
            throw new IOException("adb 未连接");
        }
        byte[] head = new byte[HEADER_LEN];
        if (!readFully(in, head)) {
            return null;
        }
        Message m = new Message();
        m.command = get32(head, 0);
        m.arg0 = get32(head, 4);
        m.arg1 = get32(head, 8);
        int len = get32(head, 12);
        if (len < 0 || len > MAXDATA) {
            throw new IOException("adb 帧长度不合法: " + len);
        }
        m.data = new byte[len];
        if (len > 0 && !readFully(in, m.data)) {
            throw new IOException("adb 帧载荷被截断");
        }
        return m;
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int r = in.read(buf, got, buf.length - got);
            if (r < 0) {
                return false;
            }
            got += r;
        }
        return true;
    }

    /**
     * 数据校验和：**字节相加 mod 2^32**。客机 adbd 严格校验这个字段（用 CRC-32 会被
     * 判成坏帧直接断连）—— 实测平台 adb 对同一客机发的 CNXN 里就是字节和，
     * 换成 crc32 立刻 `bad data: terminated`。
     */
    private static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += b & 0xff;
        }
        return sum;
    }

    static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >>> 8) & 0xff);
        b[off + 2] = (byte) ((v >>> 16) & 0xff);
        b[off + 3] = (byte) ((v >>> 24) & 0xff);
    }

    static int get32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int fourCC(String s) {
        return get32(utf8(s), 0);
    }

    private static String tagToString(int tag) {
        byte[] b = new byte[4];
        put32(b, 0, tag);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int c = b[i] & 0xff;
            sb.append(c >= 32 && c < 127 ? (char) c : '.');
        }
        return sb.toString();
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }

    private static String name(int command) {
        switch (command) {
            case A_CNXN: return "CNXN";
            case A_OPEN: return "OPEN";
            case A_OKAY: return "OKAY";
            case A_CLSE: return "CLSE";
            case A_WRTE: return "WRTE";
            case A_AUTH: return "AUTH";
            default: return "0x" + Integer.toHexString(command);
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
