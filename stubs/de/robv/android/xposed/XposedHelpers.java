/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-09 22:05:00 +08:00
 */
package de.robv.android.xposed;

import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook.MethodHookParam;

public class XposedHelpers {

    protected XposedHelpers() {
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader,
                                                         String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        return null;
    }

    public static Class<?> findClassIfExists(String className, ClassLoader classLoader) {
        return null;
    }

    public static Member findMethodBySignature(Class<?> theClass, String shortSignature) {
        return null;
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        return null;
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return null;
    }

    public static Object getObjectField(Object obj, String fieldName) {
        return null;
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
    }

    public static int getIntField(Object obj, String fieldName) {
        return 0;
    }

    public static boolean getBooleanField(Object obj, String fieldName) {
        return false;
    }

    public static long getLongField(Object obj, String fieldName) {
        return 0L;
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        return null;
    }

    public static Object newInstanceAndConstructor(Class<?> clazz, MethodHookParam param) {
        return null;
    }

    public static java.lang.reflect.Field findFieldIfExists(Class<?> clazz, String fieldName) {
        return null;
    }

    public static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        return null;
    }

    public static java.lang.reflect.Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        return null;
    }
}
