package com.velasim.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;

/**
 * Launcher activity. Registers the {@code velasim/control} MethodChannel and
 * the {@code velasim/events} EventChannel on the Flutter engine, and hosts the
 * system folder picker used by the project import flow.
 *
 * <p>Declared in the manifest as {@code com.velasim.app.VelaFlutterActivity}
 * because the channel glue is Java; the Dart side only needs to know the
 * channel names. The Kotlin scaffold at
 * {@code kotlin/com/velasim/velasim_app/MainActivity.kt} is no longer the
 * launcher -- if that changes, call
 * {@code VelaChannel.attach(getApplicationContext()).register(flutterEngine.getDartExecutor())}
 * from its {@code configureFlutterEngine} instead.
 */
public class VelaFlutterActivity extends FlutterActivity {

    private static final int REQ_FOLDER = 4711;
    private static final int REQ_ZIP = 4712;
    private static VelaFlutterActivity current;
    private static VelaChannel.UriPick pending;
    private static int pendingReq = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
    }

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        VelaChannel.attach(getApplicationContext())
                .register(flutterEngine.getDartExecutor());
        VelaLog.i("activity", "flutter engine attached for " + getPackageName());
    }

    @Override
    protected void onDestroy() {
        if (current == this) {
            current = null;
        }
        super.onDestroy();
    }

    /** 拉起系统文件夹选择器；没有前台 Activity 时返回 false。 */
    static boolean pickFolder(VelaChannel.UriPick cb) {
        return pick(REQ_FOLDER, new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), cb);
    }

    /**
     * 拉起文件选择器挑一个 zip。type 用通配符再加 MIME 白名单：部分 ROM 会把
     * zip 判成 octet-stream，只写 application/zip 会让文件变灰选不中。
     */
    static boolean pickZip(VelaChannel.UriPick cb) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        return pick(REQ_ZIP, i, cb);
    }

    private static boolean pick(int req, Intent intent, VelaChannel.UriPick cb) {
        VelaFlutterActivity a = current;
        if (a == null || a.isFinishing()) {
            return false;
        }
        pending = cb;
        pendingReq = req;
        try {
            a.startActivityForResult(intent, req);
            return true;
        } catch (Throwable t) {
            pending = null;
            pendingReq = -1;
            VelaLog.w("activity", "打不开选择器: " + t);
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOLDER && requestCode != REQ_ZIP) {
            return;
        }
        VelaChannel.UriPick cb = pending;
        pending = null;
        pendingReq = -1;
        if (cb != null) {
            Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
            cb.onPicked(uri);
        }
    }
}
