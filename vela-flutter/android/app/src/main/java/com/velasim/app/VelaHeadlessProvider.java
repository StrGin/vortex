package com.velasim.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本/AI 用的无界面入口，走 {@code content call}：
 *
 * <pre>
 *   adb shell content call --uri content://com.velasim.app.headless \
 *       --method start --extra avdId:s:xiaomi_s4
 *   adb shell content call --uri content://com.velasim.app.headless --method status
 *   adb shell content call --uri content://com.velasim.app.headless \
 *       --method install --extra rpkPath:s:/sdcard/x.rpk
 *   adb shell content call --uri content://com.velasim.app.headless --method logs
 * </pre>
 *
 * 为什么不只用 Service：后台起前台服务会被系统延迟派发（实测有时候几十秒甚至不派发），
 * 这里跑在 binder 线程上，跟主线程和后台服务限制都无关。
 */
public class VelaHeadlessProvider extends ContentProvider {

    public static final String AUTHORITY = "com.velasim.app.headless";
    private static final String TAG = "headless";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (extras != null) {
            for (String k : extras.keySet()) {
                Object v = extras.get(k);
                if (v != null) {
                    args.put(k, v);
                }
            }
        }
        // 兼容 `--method start xiaomi_s4`（arg 当 avdId）和 `--method install <path>`
        if (arg != null && !arg.isEmpty() && !args.containsKey("avdId")) {
            if ("start".equals(method)) {
                args.put("avdId", arg);
            } else if ("install".equals(method)) {
                args.put("rpkPath", arg);
            } else {
                args.put("arg", arg);
            }
        }
        String op = method == null || method.isEmpty() ? "status" : method;
        VelaLog.i(TAG, "provider " + op + " args=" + args);
        Map<String, Object> result;
        try {
            result = VelaChannel.attach(getContext()).headless(op, args);
        } catch (Throwable t) {
            result = new LinkedHashMap<>();
            result.put("ok", false);
            result.put("error", VelaChannel.describe(t));
            VelaLog.e(TAG, "provider " + op + " 失败: " + t);
        }
        Bundle out = new Bundle();
        out.putString("result", String.valueOf(result));
        out.putBoolean("ok", !Boolean.FALSE.equals(result.get("ok")));
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
