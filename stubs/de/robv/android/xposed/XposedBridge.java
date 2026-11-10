/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-09 22:05:00 +08:00
 */
package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook.Unhook;

public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(Throwable throwable) {
    }

    public static void log(String message) {
    }

    public static Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName,
                                                           XC_MethodHook callback) {
        return null;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
        return null;
    }

    /** LSPosed 特有：判断某个 Member 是否已被挂过。*/
    public static boolean isMethodHooked(Member method) {
        return false;
    }

    public static ClassLoader getClassLoader() {
        return null;
    }
}
