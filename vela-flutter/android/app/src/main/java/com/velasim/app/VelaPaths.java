package com.velasim.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * Where things live.
 *
 * <pre>
 *   &lt;files&gt;/vela                    runtime root -- everything we create
 *     engine/                       extracted engine (version-stamped)
 *     engine/engine/                emulator binary + qemu/ + lib64/ + lib/
 *     engine/glibc/                 the aarch64 glibc the engine needs
 *     engine/skins/                 device skins
 *     engine-override/*.zip         optional user-supplied engine build
 *     .vela/                        HOME of the engine
 *       sdk/{emulator,system-images,skins,tools}
 *       vvd/&lt;avd&gt;.avd/               the AVDs
 *       logs/engine-&lt;ts&gt;.log         serial + stderr of the engine
 *       engine.pid, engine.ready    lifecycle markers
 *   &lt;external files&gt;/vela           same, only when explicitly enabled
 * </pre>
 *
 * <p>The engine resolves {@code HOME/.vela/sdk} by itself, so
 * {@link #velaSdkDir} is the single root it sees; {@code tools/} is a mirror of
 * {@code engine/bin64} because the AIoT IDE keeps its helpers there.
 */
public final class VelaPaths {

    /** Everything we ship lives under this asset prefix. */
    public static final String ASSET_PREFIX = "vela";

    private static final String PREF_RUNTIME_ON_EXTERNAL = "runtime_on_external";
    private static final String RUNTIME_DIR_NAME = "vela";
    private static final String ENGINE_DIR_NAME = "engine";
    private static final String OVERRIDE_DIR_NAME = "engine-override";

    private VelaPaths() {
    }

    // ------------------------------------------------------------- app storage

    /**
     * Engine extraction root: {@code <files>/vela/engine}. Version-stamped by
     * {@link VelaUtil#assetVersionStamp}, whose marker file sits inside it, so
     * a new APK re-extracts without disturbing a running qemu.
     */
    public static File engineDir(Context ctx) {
        return new File(runtimeDir(ctx), ENGINE_DIR_NAME);
    }

    /** Root of everything the app creates (engine + HOME + logs). */
    public static File runtimeDir(Context ctx) {
        File base = ctx.getFilesDir();
        if (base == null) {
            base = new File(ctx.getCacheDir(), "internal");
        }
        return new File(base, RUNTIME_DIR_NAME);
    }

    /** Drop {@code *.zip} here to replace the bundled engine build. */
    public static File overrideDir(Context ctx) {
        return new File(runtimeDir(ctx), OVERRIDE_DIR_NAME);
    }

    /**
     * {@code <home>/.vela} -- the engine's HOME subtree. {@code home} is chosen
     * by {@link VelaEngine#writableHome()} and cached in prefs so the
     * {@code run-as} side resolves the exact same path.
     */
    public static File velaHome(Context ctx) {
        return new File(homeDir(ctx), ".vela");
    }

    /** {@code <home>/.vela/sdk} -- what {@code $HOME/.vela/sdk} resolves to. */
    public static File velaSdkDir(Context ctx) {
        return new File(velaHome(ctx), "sdk");
    }

    /** {@code <home>/.vela/sdk}; the AIoT IDE uses the same layout. */
    public static File sdkDir(Context ctx) {
        return velaSdkDir(ctx);
    }

    /** {@code <home>/.vela/vvd} -- where {@code <avd>.avd} directories live. */
    public static File velaVvdDir(Context ctx) {
        return new File(velaHome(ctx), "vvd");
    }

    /** Launcher fallback when {@code ANDROID_AVD_HOME} is unset. */
    public static File avdHomeFromSdk(File sdk) {
        return new File(sdk.getParentFile(), "vvd");
    }

    public static File avdDirFor(Context ctx, String avdId) {
        return new File(velaVvdDir(ctx), VelaAvd.sanitize(avdId) + VelaAvd.AVD_SUFFIX);
    }

    /** Legacy alias kept for the log/diag helpers. */
    public static File runDir(Context ctx) {
        return new File(velaHome(ctx), "run");
    }

    private static File homeDir(Context ctx) {
        String name = prefs(ctx).getString(PREF_RUNTIME_ON_EXTERNAL, null);
        if (name != null && !name.isEmpty()) {
            File f = externalDirOf(ctx);
            if (f != null) {
                return new File(f, name);
            }
        }
        return runtimeDir(ctx);
    }

    /** Remembers which home the engine uses (app-private by default). */
    public static void setHomeMarker(Context ctx, String relativeName) {
        prefs(ctx).edit()
                .putString(PREF_RUNTIME_ON_EXTERNAL, relativeName == null ? "" : relativeName)
                .apply();
    }

    public static boolean homeIsExternal(Context ctx) {
        String n = prefs(ctx).getString(PREF_RUNTIME_ON_EXTERNAL, null);
        return n != null && !n.isEmpty() && externalDirOf(ctx) != null;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences("velasim_engine", Context.MODE_PRIVATE);
    }

    // -------------------------------------------------------- shared storage

    /** {@code /storage/emulated/0/Android/data/<pkg>/files}. */
    public static File externalDirOf(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null || !dir.exists() ? null : dir;
    }

    /** Shared-storage runtime dir, or null when it is unavailable. */
    public static File externalRuntimeDir(Context ctx) {
        File ext = externalDirOf(ctx);
        return ext == null ? null : new File(ext, RUNTIME_DIR_NAME);
    }

    /**
     * {@code /data/data/<pkg>} via {@code run-as}, so the launcher can resolve
     * an absolute path that the app domain can also reach. Null on failure.
     */
    public static File pkgDataParent(Context ctx) {
        try {
            String out = VelaUtil.runAsPackage(ctx,
                    java.util.Arrays.asList("sh", "-c", "pwd"), 3000);
            String line = out == null ? "" : firstLine(out);
            File probe = new File(line.trim());
            return probe.isDirectory() ? probe : null;
        } catch (Exception e) {
            VelaLog.d("paths", "pkgDataParent failed: " + e);
            return null;
        }
    }

    /** {@code /data/data/<pkg>/data} -- where our own File objects actually live. */
    public static File pkgDataRoot(Context ctx) {
        File parent = pkgDataParent(ctx);
        if (parent == null) {
            return null;
        }
        File data = new File(parent, "data");
        return data.isDirectory() ? data : parent;
    }

    // ----------------------------------------------------------------- misc

    public static File deviceMarker(Context ctx) {
        return new File(runtimeDir(ctx), "device.txt");
    }

    /** Writes only when the content differs, so mtime stays meaningful. */
    public static boolean writeIfChanged(File f, String content) {
        try {
            if (f.isFile() && content.equals(VelaUtil.slurp(f))) {
                return true;
            }
            return VelaUtil.writeText(f, content);
        } catch (Exception e) {
            return false;
        }
    }

    static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** ABI used for the guest: always arm32 (cortex-a8), see {@code hw.cpu.arch}. */
    public static String guestAbi() {
        return "armeabi-v7a";
    }

    /** Host ABI we run the engine with -- aarch64 is the only supported one. */
    public static String hostAbi(Context ctx) {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0 && abis[0] != null && !abis[0].isEmpty()) {
            return abis[0];
        }
        String marker = VelaUtil.slurp(deviceMarker(ctx)).trim();
        if (!marker.isEmpty()) {
            return marker;
        }
        String osArch = System.getProperty("os.arch");
        return osArch == null ? "unknown" : osArch;
    }

    public static String readDeviceMarker(Context ctx) {
        return VelaUtil.slurp(deviceMarker(ctx));
    }
}
