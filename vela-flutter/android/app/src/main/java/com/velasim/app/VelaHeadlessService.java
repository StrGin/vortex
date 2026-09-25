package com.velasim.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 无界面入口：让 adb / 脚本 / AI 不起 UI 就能开引擎、装 rpk、拉起客机应用。
 *
 * <pre>
 *   adb shell am start-foreground-service -n com.velasim.app/.VelaHeadlessService \
 *       -a com.velasim.app.HEADLESS --es op start --es avdId xiaomi_s4
 *   adb shell am start-foreground-service ... --es op install \
 *       --es rpkPath /sdcard/tyloo/com.hyper.box.debug.1.0.0.rpk --es package com.hyper.box.debug
 *   adb shell am start-foreground-service ... --es op status
 *   adb shell am stopservice -n com.velasim.app/.VelaHeadlessService
 * </pre>
 *
 * 结果与日志都走 {@link VelaLog}（logcat 标签 {@code Vortex}）。服务用
 * {@code android.permission.DUMP} 保护：adb shell 持有该权限，普通应用没有。
 */
public class VelaHeadlessService extends Service {

    public static final String ACTION = "com.velasim.app.HEADLESS";
    private static final String TAG = "headless";
    private static final String CHANNEL_ID = "vela_headless";
    private static final int NOTIFICATION_ID = 0x5E1A;
    private static volatile Thread watchdog;

    /**
     * 让 App 以前台服务的形式活着（引擎是它的子进程，进程被回收引擎就没了）。
     * 从 provider 起引擎之后调一次；系统可能延迟派发，但最终会到。
     */
    public static void keepAlive(android.content.Context ctx) {
        try {
            android.content.Intent i = new android.content.Intent(ctx, VelaHeadlessService.class);
            i.setAction(ACTION);
            i.putExtra("op", "keepalive");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            VelaLog.w(TAG, "拉前台服务失败（不影响引擎）: " + t);
        }
    }

    /**
     * 主线程心跳：有人把 UI 线程堵住时，把它的调用栈写进日志。
     * （实测 service 回调会因此不再执行，服务就"哑"了。）
     */
    private static void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    final int[] beat = {0};
                    final android.os.Handler h = new android.os.Handler(
                            android.os.Looper.getMainLooper());
                    h.post(new Runnable() {
                        @Override
                        public void run() {
                            beat[0]++;
                        }
                    });
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (beat[0] == 0) {
                        Thread main = android.os.Looper.getMainLooper().getThread();
                        StackTraceElement[] st = Thread.getAllStackTraces().get(main);
                        StringBuilder sb = new StringBuilder("主线程卡住了，栈：");
                        if (st != null) {
                            for (StackTraceElement e : st) {
                                sb.append("\n    at ").append(e);
                            }
                        }
                        VelaLog.e(TAG, sb.toString());
                    }
                }
            }
        }, "vela-main-watchdog");
        t.setDaemon(true);
        t.start();
        watchdog = t;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String op = intent == null ? "status" : intent.getStringExtra("op");
        final Map<String, Object> args = new LinkedHashMap<>();
        if (intent != null) {
            for (String key : new String[]{"avdId", "rpkPath", "name", "package", "imageType",
                    "grpcPort", "debugPort", "extraArgs", "timeoutMs"}) {
                String v = intent.getStringExtra(key);
                if (v != null && !v.isEmpty()) {
                    args.put(key, v);
                }
            }
            if (intent.hasExtra("noAudio")) {
                args.put("noAudio", intent.getBooleanExtra("noAudio", true));
            }
            if (intent.hasExtra("launch")) {
                args.put("launch", intent.getBooleanExtra("launch", true));
            }
        }
        goForeground(op);
        startWatchdog();
        new Thread(new Runnable() {
            @Override
            public void run() {
                VelaChannel.attach(getApplicationContext()).headless(op, args);
            }
        }, "vela-headless").start();
        return START_NOT_STICKY;
    }

    private void goForeground(String op) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "后台引擎", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("脚本/无界面方式驱动模拟器引擎时显示");
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Vortex 后台运行中")
                .setContentText("无界面操作：" + (op == null ? "status" : op))
                .setOngoing(true);
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            VelaLog.w(TAG, "前台服务没起来（继续跑）: " + t);
        }
    }
}
