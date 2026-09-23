package com.velasim.app;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog shipped as <code>assets/vela/devices.json</code>.
 *
 * <p>Two sections live there: the device list (one entry per Xiaomi Vela
 * profile: AVD id, skin, panel geometry, guest cpu arch, ram) and the system
 * image list. Both are read-only here -- images are downloaded by
 * {@link VelaImageStore}, AVDs are authored by {@link VelaAvd}.
 *
 * <p>Air-gapped smoke test: call {@link #parse(String, String)} with your own
 * json and pass the sdk root to {@link VelaDevice#toMap(String, String)}.
 */
public final class VelaDevices {

    /** Asset-relative directory holding {@code devices.json}. */
    public static final String ASSET_DIR = "vela";
    public static final String ASSET_CATALOG = ASSET_DIR + "/devices.json";

    /** A device profile from {@code devices.json.devices[]}. */
    public static final class VelaDevice {
        public final String avdId;
        public final String skin;
        public final int width;
        public final int height;
        public final int corner_radius;
        public final int canvas_width;
        public final int canvas_height;
        public final int device_x;
        public final int device_y;
        /** Numeric form written to {@code config.ini} as hw.lcd.shape. */
        public final int shape;
        /** Raw catalogue value: circle | rect | pill-shaped. */
        public final String shapeName;
        public final int density;
        public final String flavor;
        public final String cpuArch;
        public final String abi;
        public final int ncore;
        public final int ramSizeMb;
        public final String imageType;

        VelaDevice(JSONObject o) {
            avdId = o.optString("avdId");
            skin = o.optString("skin");
            width = o.optInt("width", 0);
            height = o.optInt("height", 0);
            corner_radius = o.optInt("corner_radius", 0);
            canvas_width = o.optInt("canvas_width", width);
            canvas_height = o.optInt("canvas_height", height);
            device_x = o.optInt("device_x", 0);
            device_y = o.optInt("device_y", 0);
            shapeName = o.optString("shape", "");
            shape = shapeCode(shapeName, o.optInt("shape", -1));
            density = o.optInt("density", 160);
            flavor = o.optString("flavor");
            cpuArch = o.optString("cpuArch", "arm");
            abi = o.optString("abi", "armeabi-v7a");
            ncore = o.optInt("ncore", 2);
            ramSizeMb = o.optInt("ramSizeMb", 1024);
            imageType = o.optString("imageType");
        }

        public String id() {
            return avdId;
        }

        /**
         * Dart-side shape. {@code sdkRoot}/{@code avdHome} are only used to
         * resolve the absolute skin path, exactly like {@link VelaAvd#avdDir}.
         */
        public Map<String, Object> toMap(String sdkRoot, String avdHome) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("avdId", avdId);
            m.put("skin", skin);
            m.put("width", width);
            m.put("height", height);
            m.put("cornerRadius", corner_radius);
            m.put("canvasWidth", canvas_width);
            m.put("canvasHeight", canvas_height);
            m.put("deviceX", device_x);
            m.put("deviceY", device_y);
            m.put("shape", shape);
            m.put("shapeName", shapeName);
            m.put("density", density);
            m.put("flavor", flavor);
            m.put("cpuArch", cpuArch);
            m.put("abi", abi);
            m.put("ncore", ncore);
            m.put("ramSizeMb", ramSizeMb);
            m.put("imageType", imageType);
            m.put("skinDir", "skins/" + skin);
            m.put("backgroundAsset", "vela/skins/" + skin + "/background.png");
            m.put("foregroundAsset", "vela/skins/" + skin + "/foreground.png");
            m.put("skinPath", VelaAvd.skinPath(sdkRoot, skin));
            m.put("avdPath", VelaAvd.directoryFor(sdkRoot, avdHome, avdId).getAbsolutePath());
            m.put("imageDir", VelaAvd.nuttxImageDir(sdkRoot, flavor, imageType).getAbsolutePath());
            return m;
        }

        /** Same as {@link #toMap(String, String)} without any path resolution. */
        public Map<String, Object> toMap() {
            return toMap(null, null);
        }

        /** Raw catalog entry, useful for tests that diff against devices.json. */
        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("avdId", avdId).put("skin", skin).put("width", width).put("height", height)
                        .put("corner_radius", corner_radius).put("canvas_width", canvas_width)
                        .put("canvas_height", canvas_height).put("device_x", device_x)
                        .put("device_y", device_y).put("shape", shapeName).put("density", density)
                        .put("flavor", flavor).put("cpuArch", cpuArch).put("abi", abi)
                        .put("ncore", ncore).put("ramSizeMb", ramSizeMb)
                        .put("imageType", imageType);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            return o;
        }

        @Override
        public String toString() {
            return "VelaDevice{" + avdId + " skin=" + skin + " " + width + "x" + height
                    + " density=" + density + " image=" + imageType + "}";
        }
    }

    /** One entry of {@code devices.json.images[]}. */
    public static final class VelaImage {
        public final String type;
        public final String time;
        public final String label;
        public final String flavor;

        VelaImage(JSONObject o) {
            type = o.optString("type");
            time = o.optString("time");
            label = o.optString("label", type);
            flavor = o.optString("flavor");
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("time", time);
            m.put("label", label);
            m.put("flavor", flavor);
            return m;
        }

        @Override
        public String toString() {
            return "VelaImage{" + type + " time=" + time + " flavor=" + flavor + "}";
        }
    }

    private final Map<String, VelaDevice> devices = new LinkedHashMap<>();
    private final Map<String, VelaImage> images = new LinkedHashMap<>();
    private final String imageUrlTemplate;

    private VelaDevices() {
        imageUrlTemplate = null;
    }

    private VelaDevices(JSONObject root) {
        String template = root.optString("imageUrlTemplate", "");
        imageUrlTemplate = template.isEmpty()
                ? "https://vela-ide.cnbj3-fusion.mi-fds.com/vela-ide/system-images/{type}/{time}/{type}.zip"
                : template;

        JSONArray deviceArr = root.optJSONArray("devices");
        if (deviceArr != null) {
            for (int i = 0; i < deviceArr.length(); i++) {
                JSONObject o = deviceArr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                VelaDevice d = new VelaDevice(o);
                if (!d.avdId.isEmpty()) {
                    devices.put(d.avdId, d);
                }
            }
        }
        JSONArray imageArr = root.optJSONArray("images");
        if (imageArr != null) {
            for (int i = 0; i < imageArr.length(); i++) {
                JSONObject o = imageArr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                VelaImage img = new VelaImage(o);
                if (!img.type.isEmpty()) {
                    images.put(img.type, img);
                }
            }
        }
    }

    /** Parse a catalog held outside the assets dir (tests, local overrides). */
    public static VelaDevices parse(String json) throws JSONException {
        return new VelaDevices(new JSONObject(json));
    }

    /** Parse with an explicit fallback template -- used when images live on a LAN mirror. */
    public static VelaDevices parse(String json, String imageUrlTemplateOverride) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (imageUrlTemplateOverride != null && !imageUrlTemplateOverride.isEmpty()) {
            root.put("imageUrlTemplate", imageUrlTemplateOverride);
        }
        return new VelaDevices(root);
    }

    /** Loads the bundled catalog; memoised because assets are read as text. */
    public static synchronized VelaDevices load(Context ctx) throws IOException, JSONException {
        VelaDevices cached = cache;
        if (cached == null) {
            cached = new VelaDevices(new JSONObject(readAsset(ctx.getAssets(), ASSET_CATALOG)));
            cache = cached;
        }
        return cached;
    }

    /** Drops the memoised catalog (call after replacing the asset at runtime). */
    public static synchronized void invalidate() {
        cache = null;
    }

    private static volatile VelaDevices cache;

    public VelaDevice byId(String avdId) {
        return avdId == null ? null : devices.get(avdId);
    }

    /** Catalog entry whose skin matches {@code skinName}, or null. */
    public VelaDevice bySkin(String skinName) {
        for (VelaDevice d : devices.values()) {
            if (d.skin.equals(skinName)) {
                return d;
            }
        }
        return null;
    }

    public List<VelaDevice> all() {
        return new ArrayList<>(devices.values());
    }

    public List<String> ids() {
        return new ArrayList<>(devices.keySet());
    }

    /** Catalog images keyed nothing -- insertion order, for the download sheet. */
    public List<VelaImage> images() {
        return new ArrayList<>(images.values());
    }

    public VelaImage image(String type) {
        return type == null ? null : images.get(type);
    }

    /** Device list in Dart form; {@code sdkRoot}/{@code avdHome} may be null. */
    public List<Map<String, Object>> deviceMaps(String sdkRoot, String avdHome) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VelaDevice d : devices.values()) {
            out.add(d.toMap(sdkRoot, avdHome));
        }
        return out;
    }

    /** Catalog image list -- install state is added by {@link VelaImageStore#list()}. */
    public List<Map<String, Object>> imageMaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (VelaImage i : images.values()) {
            out.add(i.toMap());
        }
        return out;
    }

    /**
     * Fills <code>{type}</code> and <code>{time}</code> in the catalog template.
     * Unknown types still resolve, using the type itself as the release stamp,
     * so hand-typed types work against a local mirror.
     */
    public String imageUrl(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        VelaImage img = images.get(type);
        String time = img == null || img.time.isEmpty() ? type : img.time;
        return imageUrlTemplate
                .replace("{type}", type)
                .replace("{time}", time);
    }

    /**
     * Catalogue shapes are strings ("circle", "rect", "pill-shaped") while
     * config.ini wants a number: 0 = rect, 1 = round, 2 = pill. A plain
     * integer in the json passes through unchanged.
     */
    static int shapeCode(String name, int numeric) {
        if (name != null && !name.isEmpty()) {
            String n = name.toLowerCase(java.util.Locale.US).replace("-", "").replace("_", "");
            if (n.startsWith("circle") || n.startsWith("round")) {
                return 1;
            }
            if (n.startsWith("pill")) {
                return 2;
            }
            if (n.startsWith("rect")) {
                return 0;
            }
        }
        return numeric >= 0 ? numeric : 0;
    }

    /** Flavor a system image declares (nuttx variant) -- falls back to the device's own. */
    public String imageFlavor(String type) {
        VelaImage img = images.get(type);
        return img == null ? null : img.flavor;
    }

    /** Types referenced by at least one device -- what the setup screen offers. */
    public List<String> referencedImageTypes() {
        List<String> out = new ArrayList<>();
        for (VelaDevice d : devices.values()) {
            if (!d.imageType.isEmpty() && !out.contains(d.imageType)) {
                out.add(d.imageType);
            }
        }
        Collections.sort(out);
        return out;
    }

    static String readAsset(AssetManager am, String path) throws IOException {
        InputStream in = am.open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16 * 1024, in.available()));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
