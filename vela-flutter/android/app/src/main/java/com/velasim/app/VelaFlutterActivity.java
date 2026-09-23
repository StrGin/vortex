package com.velasim.app;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;

/**
 * Launcher activity. Registers the {@code velasim/control} MethodChannel and
 * the {@code velasim/events} EventChannel on the Flutter engine.
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

    @Override
    public void configureFlutterEngine(FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);
        VelaChannel.attach(getApplicationContext())
                .register(flutterEngine.getDartExecutor());
        VelaLog.i("activity", "flutter engine attached for " + getPackageName());
    }
}
