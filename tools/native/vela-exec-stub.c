/*
 * vela-exec-stub.c —— 引擎私有目录里的「可执行 trampoline」。
 *
 * 为什么需要它：本应用 targetSdk 36，SELinux 的 untrusted_app 域禁止 exec 私有目录
 * （app_data_file）里的文件——**包括 shell 脚本**（execve 是对脚本 inode 判的）。
 * 引擎（小米改过的 emulator launcher）会按相对路径 exec qemu 与 bin64 里的工具，
 * 而每个负载的 PT_INTERP 都是 /lib/ld-linux-aarch64.so.1（Android 上不存在），
 * 所以既不能直接用真 ELF，也不能用脚本 shim。
 *
 * 做法：编译成一个**静态、无 PT_INTERP、无 libc** 的极简 ELF，放进 jniLibs
 * （安装后位于 /data/app/<pkg>/lib/arm64/，标签 apk_data_file，应用域可执行），
 * 再软链到引擎会 exec 的每个路径上。运行时：
 *
 *   1. readlink("/proc/self/exe") 得到 nativeLibraryDir（loader 就在这里）；
 *   2. 用 argv[0] 的 basename 查表得到负载在私有目录里的相对路径；
 *   3. execve(nativeDir/libvela_glibc_lib_ld_linux_aarch64_so_1.so,
 *             [loader, "--library-path", <私有目录里的各 lib 目录>,
 *              <私有目录>/<rel>, argv[1..], NULL], envp)
 *
 * 也就是说：**只有 loader 与这个 stub 需要待在 jniLibs**，qemu 与 glibc 仍留在
 * 私有目录——实测 app_data_file 只是不能 execve，mmap(PROT_EXEC) 是允许的，
 * 而 loader 正是用 mmap 把程序读起来的。
 *
 * 私有目录来自环境变量 VELA_RUNTIME_DIR（Java 侧 VelaNative.RUNTIME_ENV 传入，
 * 引擎 exec qemu 时会把它继承下去）。
 *
 * 构建（NDK，产出非 PIE 静态 ELF，无 PT_INTERP）：
 *   tools/build-exec-stub.sh
 */

typedef long i64;

#define SYS_readlinkat 78
#define SYS_execve 221
#define SYS_exit 93
#define SYS_write 64
#define AT_FDCWD (-100)

/* _start：把内核给的原始 sp 存下来再跳进 C。 */
volatile long *g_sp;

__asm__(
    ".text\n"
    ".global _start\n"
    ".type _start, %function\n"
    "_start:\n"
    "  mov  x1, sp\n"
    "  adrp x0, g_sp\n"
    "  add  x0, x0, :lo12:g_sp\n"
    "  str  x1, [x0]\n"
    "  bl   vela_start\n"
    "  mov  x0, #93\n"   /* exit(127)：走到这儿说明 execve 失败了 */
    "  mov  x1, #127\n"
    "  svc  #0\n"
);

static i64 sys4(i64 n, i64 a, i64 b, i64 c, i64 d) {
    register i64 x0 __asm__("x0") = a;
    register i64 x1 __asm__("x1") = b;
    register i64 x2 __asm__("x2") = c;
    register i64 x3 __asm__("x3") = d;
    register i64 x8 __asm__("x8") = n;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x8) : "memory");
    return x0;
}

static i64 sys3(i64 n, i64 a, i64 b, i64 c) {
    return sys4(n, a, b, c, 0);
}

static long slen(const char *s) {
    long n = 0;
    while (s && s[n]) {
        n++;
    }
    return n;
}

/* 诊断：stderr 会被引擎日志收走（VelaEngine 把子进程输出重定向到 engine-*.log）。 */
static void say(const char *msg) {
    sys3(SYS_write, 2, (i64)msg, slen(msg));
}

static int seq(const char *a, const char *b) {
    while (*a && *b && *a == *b) {
        a++;
        b++;
    }
    return *a == *b;
}

static int seq_prefix(const char *s, const char *prefix) {
    while (*prefix) {
        if (*s++ != *prefix++) {
            return 0;
        }
    }
    return 1;
}

static const char *base(const char *p) {
    const char *s = p;
    for (const char *q = p; *q; q++) {
        if (*q == '/') {
            s = q + 1;
        }
    }
    return s;
}

static void append(char *dst, long cap, long *len, const char *src) {
    for (long i = 0; src[i] && *len < cap - 1; i++) {
        dst[(*len)++] = src[i];
    }
    dst[*len] = 0;
}

/* 引擎/工具链会 exec 的每个名字 → 私有目录下的相对路径（+ 可选固定前缀参数）。
   a1..a3 以 '-' 开头按字面量透传，否则当作私有目录下的相对路径展开。 */
struct entry {
    const char *name;
    const char *prog;
    const char *a1;
    const char *a2;
    const char *a3;
    int bionic;   /* 1 = bionic 二进制（Termux 构建），要用系统 linker64 起 */
};

static const struct entry TABLE[] = {
    /* 真身被 installExecLinks() 改名成 <name>.elf，原路径留给指向本 stub 的软链接；
       非 headless 的 qemu 链 Qt、在 Android 上起不来，两个名字都指 headless 真身。 */
    {"qemu-system-armel-headless", "engine/engine/qemu/linux-aarch64/qemu-system-armel-headless.elf", 0, 0, 0, 0},
    {"qemu-system-armel", "engine/engine/qemu/linux-aarch64/qemu-system-armel-headless.elf", 0, 0, 0, 0},
    {"qemu-system-aarch64-headless", "engine/engine/qemu/linux-aarch64/qemu-system-aarch64-headless.elf", 0, 0, 0, 0},
    {"qemu-system-aarch64", "engine/engine/qemu/linux-aarch64/qemu-system-aarch64-headless.elf", 0, 0, 0, 0},
    {"e2fsck", "engine/engine/bin64/e2fsck.elf", 0, 0, 0, 0},
    {"fsck.ext4", "engine/engine/bin64/fsck.ext4.elf", 0, 0, 0, 0},
    {"mkfs.ext4", "engine/engine/bin64/mkfs.ext4.elf", 0, 0, 0, 0},
    {"resize2fs", "engine/engine/bin64/resize2fs.elf", 0, 0, 0, 0},
    {"tune2fs", "engine/engine/bin64/tune2fs.elf", 0, 0, 0, 0},
    /* aiot-toolkit 直接 exec 的字节码编译器：x86_64 的 qemu-user 借壳跑 aiotjsc。
       迁移前这是一段 shell 脚本，现在换成 stub（脚本在应用域 exec 不了）。
       qemu-x86_64 与 aiotjsc 都不在引擎的 exec 名单里，保持原文件名。 */
    {"linux_aiotjsc", "toolchain/jsc/qemu/bin/qemu-x86_64",
     "-L", "toolchain/jsc/qemu/sysroot", "toolchain/jsc/qemu/opt/linux_aiotjsc", 1},
};


/* 在 envp 副本里塞一条 LD_LIBRARY_PATH（bionic 的 linker64 靠它找 qemu 自己的库；
   那个二进制编译时把 RUNPATH 写死成了 Termux 的 /data/data/com.termux/files/usr/lib）。 */
#define ENV_MAX 96
static char *envbuf[ENV_MAX + 2];
static const char *env_prefix = "LD_LIBRARY_PATH=";

static char **env_with_ld(char **envp, const char *ldpath) {
    long n = 0;
    for (long i = 0; envp[i] && n < ENV_MAX; i++) {
        if (seq_prefix(envp[i], env_prefix)) {
            continue;
        }
        envbuf[n++] = envp[i];
    }
    envbuf[n++] = (char *)ldpath;
    envbuf[n] = 0;
    return envbuf;
}

#define LOADER_NAME "libvela_glibc_lib_ld_linux_aarch64_so_1.so"
#define RUNTIME_ENV "VELA_RUNTIME_DIR="
/* 兜底：万一 launcher 清洗了环境变量（正常情况下 VELA_RUNTIME_DIR 会一路继承）。 */
#define RUNTIME_DEFAULT "/data/data/com.velasim.app/files/vela"

static char dir[1024];
static char payload[1600];
static char loader[1280];
static char libs[1600];
static long dirlen;

/* 在 environ 里找 VELA_RUNTIME_DIR（引擎与工具链都在私有目录里）。 */
static const char *runtime_env(char **envp) {
    for (long i = 0; envp[i]; i++) {
        if (!seq_prefix(envp[i], RUNTIME_ENV)) {
            continue;
        }
        return envp[i] + sizeof(RUNTIME_ENV) - 1;
    }
    return 0;
}

void vela_start(void) {
    long *sp = (long *)g_sp;
    long argc = sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;

    if (argc < 1 || !argv[0]) {
        say("vela-stub: no argv\n");
        sys3(SYS_exit, 127, 0, 0);
    }

    /* nativeLibraryDir = dirname(/proc/self/exe)。执行的虽是私有目录里的软链接，
       内核给的是解析后的真身（jniLibs 那份），所以这里拿到的是 /data/app/.../lib/arm64。 */
    i64 n = sys4(SYS_readlinkat, AT_FDCWD, (i64)"/proc/self/exe", (i64)dir, sizeof(dir) - 1);
    if (n <= 0) {
        say("vela-stub: readlink(/proc/self/exe) failed\n");
        sys3(SYS_exit, 126, 0, 0);
    }
    dir[n] = 0;
    dirlen = 0;
    for (long i = 0; i < n; i++) {
        if (dir[i] == '/') {
            dirlen = i;
        }
    }
    if (dirlen == 0) {
        say("vela-stub: no dir in /proc/self/exe\n");
        sys3(SYS_exit, 126, 0, 0);
    }
    dir[dirlen] = 0;

    const char *me = base(argv[0]);
    const struct entry *hit = 0;
    for (unsigned long i = 0; i < sizeof(TABLE) / sizeof(TABLE[0]); i++) {
        if (seq(me, TABLE[i].name)) {
            hit = &TABLE[i];
            break;
        }
    }
    const char *rt = runtime_env(envp);
    if (!rt) {
        rt = RUNTIME_DEFAULT;
    }
    if (!hit) {
        say("vela-stub: unknown target ");
        say(me);
        say("\n");
        sys3(SYS_exit, 127, 0, 0);
    }
    if (!runtime_env(envp)) {
        say("vela-stub: VELA_RUNTIME_DIR missing, using default\n");
    }

    long len = 0;
    append(loader, sizeof(loader), &len, dir);
    append(loader, sizeof(loader), &len, "/" LOADER_NAME);

    /* 库搜索路径与 Java 侧 VelaNative.libPath() 保持一致，末尾再补 jsc 的宿主库目录
       （迁移前那段的 LD_LIBRARY_PATH="$D/qemu/lib"）。 */
    len = 0;
    append(libs, sizeof(libs), &len, rt);
    append(libs, sizeof(libs), &len, "/engine/glibc/lib:");
    append(libs, sizeof(libs), &len, rt);
    append(libs, sizeof(libs), &len, "/engine/engine/lib64:");
    append(libs, sizeof(libs), &len, rt);
    append(libs, sizeof(libs), &len, "/engine/engine:");
    append(libs, sizeof(libs), &len, rt);
    append(libs, sizeof(libs), &len, "/engine/engine/lib64/gles_swiftshader:");
    append(libs, sizeof(libs), &len, rt);
    append(libs, sizeof(libs), &len, "/engine/engine/lib64/qt/lib:");
    append(libs, sizeof(libs), &len, rt);
    append(libs, sizeof(libs), &len, "/toolchain/jsc/qemu/lib");

    len = 0;
    append(payload, sizeof(payload), &len, rt);
    append(payload, sizeof(payload), &len, "/");
    append(payload, sizeof(payload), &len, hit->prog);

    /* newargv = [loader, "--library-path", libs, payload, <固定参数>, argv[1..], NULL] */
    static char arg1[1600];
    static char arg2[1600];
    static char arg3[1600];
    char *fixed[3];
    long nfixed = 0;
    const char *src[3];
    char *dst[3];
    src[0] = hit->a1;
    src[1] = hit->a2;
    src[2] = hit->a3;
    dst[0] = arg1;
    dst[1] = arg2;
    dst[2] = arg3;
    for (int i = 0; i < 3; i++) {
        if (!src[i]) {
            break;
        }
        if (src[i][0] == '-') {
            fixed[nfixed++] = (char *)src[i];
        } else {
            long l = 0;
            append(dst[i], 1600, &l, rt);
            append(dst[i], 1600, &l, "/");
            append(dst[i], 1600, &l, src[i]);
            fixed[nfixed++] = dst[i];
        }
    }

    static char ldpath[1600];
    char *newargv[64];
    long k = 0;
    char **env = envp;
    char *prog = loader;
    if (hit->bionic) {
        /* Termux 的 bionic 二进制：内核按 PT_INTERP 走 /system/bin/linker64，
           但 app 私有目录不能 execve，所以显式用系统 linker64 起它，
           并把它自己的库目录塞进 LD_LIBRARY_PATH。 */
        long l = 0;
        append(ldpath, sizeof(ldpath), &l, env_prefix);
        append(ldpath, sizeof(ldpath), &l, rt);
        append(ldpath, sizeof(ldpath), &l, "/toolchain/jsc/qemu/lib");
        env = env_with_ld(envp, ldpath);
        prog = (char *)"/system/bin/linker64";
        newargv[k++] = prog;
        newargv[k++] = payload;
    } else {
        newargv[k++] = loader;
        newargv[k++] = (char *)"--library-path";
        newargv[k++] = libs;
        newargv[k++] = payload;
    }
    for (long i = 0; i < nfixed && k < 60; i++) {
        newargv[k++] = fixed[i];
    }
    for (long i = 1; i < argc && k < 62; i++) {
        newargv[k++] = argv[i];
    }
    newargv[k] = 0;

    sys3(SYS_execve, (i64)prog, (i64)newargv, (i64)env);
    say("vela-stub: execve failed: ");
    say(prog);
    say("\n");
    sys3(SYS_exit, 127, 0, 0);
}
