package com.velasim.app;

import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Two-sink logger: logcat plus a bounded in-memory ring that Dart drains with
 * {@code getLogs}. Anything that fails does not stop the caller -- logging must
 * never break an engine start.
 */
public final class VelaLog {

    public static final String LOGCAT_TAG = "Vortex";
    private static final int RING_MAX = 2000;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> RING = new ArrayDeque<>(RING_MAX);
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile File logFile;

    private VelaLog() {
    }

    /** Mirrors every line into {@code file} until the next call. Null disables it. */
    public static void setLogFile(File file) {
        logFile = file;
    }

    public static void v(String tag, String msg) {
        emit("V", tag, msg, null);
    }

    public static void d(String tag, String msg) {
        emit("D", tag, msg, null);
    }

    public static void i(String tag, String msg) {
        emit("I", tag, msg, null);
    }

    public static void w(String tag, String msg) {
        emit("W", tag, msg, null);
    }

    public static void w(String tag, String msg, Throwable t) {
        emit("W", tag, msg, t);
    }

    public static void e(String tag, String msg) {
        emit("E", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        emit("E", tag, msg, t);
    }

    private static void emit(String level, String tag, String msg, Throwable t) {
        String line = TS.format(new Date()) + " " + level + "/" + tag + ": " + msg;
        String full = t == null ? line : line + "\n" + android.util.Log.getStackTraceString(t);
        synchronized (LOCK) {
            RING.addLast(full);
            while (RING.size() > RING_MAX) {
                RING.pollFirst();
            }
        }
        try {
            int prio = level.equals("E") ? Log.ERROR : level.equals("W") ? Log.WARN
                    : level.equals("V") ? Log.VERBOSE : level.equals("D") ? Log.DEBUG : Log.INFO;
            Log.println(prio, LOGCAT_TAG, full);
        } catch (Throwable ignored) {
            // logcat can be unavailable in unusual processes (e.g. gRPC binder threads)
        }
        File f = logFile;
        if (f != null) {
            VelaUtil.writeTextAppend(f, full + "\n");
        }
    }

    /** Newest {@code max} lines, oldest first within the returned slice. */
    public static List<String> tail(int max) {
        List<String> out = new ArrayList<>();
        synchronized (LOCK) {
            int skip = Math.max(0, RING.size() - Math.max(0, max));
            int i = 0;
            for (String s : RING) {
                if (i++ >= skip) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** All buffered lines, oldest first. */
    public static List<String> all() {
        return tail(RING_MAX);
    }

    /** Newest line matching {@code needle} (substring, case-insensitive), or null. */
    public static String find(String needle) {
        String n = needle == null ? "" : needle.toLowerCase(Locale.US);
        synchronized (LOCK) {
            java.util.Iterator<String> it = RING.descendingIterator();
            while (it.hasNext()) {
                String s = it.next();
                if (s.toLowerCase(Locale.US).contains(n)) {
                    return s;
                }
            }
        }
        return null;
    }

    public static void clear() {
        synchronized (LOCK) {
            RING.clear();
        }
    }

    /** Dumps the ring to a file -- used when the UI asks for a bug report. */
    public static boolean dump(File to) {
        StringBuilder sb = new StringBuilder();
        for (String s : all()) {
            sb.append(s).append('\n');
        }
        return VelaUtil.writeText(to, sb.toString());
    }
}
