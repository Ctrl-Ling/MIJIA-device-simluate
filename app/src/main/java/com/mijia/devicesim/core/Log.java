/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.core;

/**
 * 日志出口。米家进程里只有 XposedBridge.log 可读（LSPosed 会落到 modules_*.log），
 * 管理类进程里 XposedBridge 这个类根本不存在 —— 所以每一句都必须经过 tryReflect 这层壳，
 * 否则 UI 一调就 NoSuchClassDefFoundError。
 */
public final class Log {

    private Log() {
    }

    private static final String TAG = "MijiaSim";
    private static volatile Boolean sXposedAvailable;

    public static void d(String msg) {
        out(msg);
    }

    public static void w(String msg) {
        out("W " + msg);
    }

    public static void e(String msg, Throwable t) {
        out("E " + msg + (t == null ? "" : " :: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
        if (t != null && verboseEnabled()) {
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 12); i++) {
                out("    at " + st[i]);
            }
        }
    }

    public static void e(String msg) {
        out("E " + msg);
    }

    /** 详细日志开关：由配置里的 settings.verboseLog 决定，出问题时打开定位。*/
    private static volatile boolean sVerbose = false;

    public static void setVerbose(boolean verbose) {
        sVerbose = verbose;
    }

    public static boolean verboseEnabled() {
        return sVerbose;
    }

    public static void v(String msg) {
        if (sVerbose) out("V " + msg);
    }

    private static void out(String msg) {
        String line = "[" + Consts.MODULE_PACKAGE + "] " + msg;
        if (xposedAvailable()) {
            try {
                de.robv.android.xposed.XposedBridge.log(line);
                return;
            } catch (Throwable ignored) {
                // 框架没给就用 logcat 兜底
            }
        }
        try {
            android.util.Log.i(TAG, line);
        } catch (Throwable ignored) {
            // 非 Android 环境（单测）直接丢掉
        }
    }

    private static boolean xposedAvailable() {
        Boolean known = sXposedAvailable;
        if (known != null) return known;
        boolean ok;
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        sXposedAvailable = ok;
        return ok;
    }
}
