/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 11:21:54 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 所有钩子动作的安全壳：任何一步失败都只记一条报告，绝不把异常抛回米家。
 *
 * 为什么要这层 —— plan Phase 6 的降级要求：米家升级把某个类改名了，正确表现是
 * 「这一条钩子黄掉、假设备暂时不出现」，而不是「米家打不开」。
 */
public final class HookUtil {

    private HookUtil() {
    }

    /** 已挂过的 类名#方法名(参数) ，避免多进程/重复安装时挂两遍。*/
    private static final Set<String> sInstalled = ConcurrentHashMap.newKeySet();
    private static final Map<String, Class<?>> sClassCache = new ConcurrentHashMap<>();

    public static Class<?> findClassQuiet(String name, ClassLoader loader) {
        if (name == null || loader == null) return null;
        Class<?> cached = sClassCache.get(name);
        if (cached != null) return cached;
        try {
            Class<?> c = XposedHelpers.findClass(name, loader);
            sClassCache.put(name, c);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 类名只要不是米家的（比如 framework），失败也算正常，不进报告。*/
    public static boolean hookMethod(String group, Class<?> clazz, String methodName,
                                     XC_MethodHook callback, Object... paramTypes) {
        if (clazz == null || methodName == null || callback == null) return false;
        String sig = clazz.getName() + "#" + methodName + typesOf(paramTypes);
        if (!sInstalled.add(sig)) {
            Log.v("跳过重复安装: " + sig);
            return false;
        }
        try {
            if (paramTypes == null || paramTypes.length == 0) {
                XposedHelpers.findAndHookMethod(clazz, methodName, callback);
            } else {
                Object[] args = new Object[paramTypes.length + 1];
                System.arraycopy(paramTypes, 0, args, 0, paramTypes.length);
                args[paramTypes.length] = callback;
                XposedHelpers.findAndHookMethod(clazz, methodName, args);
            }
            HookReport.ok(group, sig, "精确签名");
            return true;
        } catch (Throwable exact) {
            // 参数类型写死会随版本变；退一步按方法名全挂，再不行才算失败
            int n = hookAllByName(group, clazz, methodName, callback);
            if (n > 0) {
                HookReport.ok(group, sig, "按名全挂 " + n + " 个重载（原精确签名失败: " + shortMsg(exact) + "）");
                return true;
            }
            sInstalled.remove(sig);
            HookReport.fail(group, sig, shortMsg(exact));
            return false;
        }
    }

    /** 沿继承链把同名方法全挂上，返回成功个数。*/
    public static int hookAllByName(String group, Class<?> clazz, String methodName, XC_MethodHook callback) {
        int count = 0;
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : methods) {
                if (!m.getName().equals(methodName)) continue;
                String sig = c.getName() + "#" + m.getName() + "(" + m.getParameterCount() + ")";
                if (!sInstalled.add(c.getName() + "#" + methodName + "/all:" + m.getParameterCount())) continue;
                try {
                    XposedBridge.hookMethod(m, callback);
                    count++;
                } catch (Throwable t) {
                    HookReport.fail(group, sig, shortMsg(t));
                }
            }
            c = c.getSuperclass();
        }
        return count;
    }

    /** 类名版本：找不到类只记 SKIP，不记 FAIL —— 米家不同版本类可能整体不存在。*/
    public static boolean hookByClassName(String group, ClassLoader loader, String className,
                                          String methodName, XC_MethodHook callback, Object... paramTypes) {
        Class<?> clazz = findClassQuiet(className, loader);
        if (clazz == null) {
            HookReport.skip(group, className + "#" + methodName, "类不存在");
            return false;
        }
        return hookMethod(group, clazz, methodName, callback, paramTypes);
    }

    public static Object readFieldQuiet(Object target, String name) {
        if (target == null) return null;
        try {
            return XposedHelpers.getObjectField(target, name);
        } catch (Throwable t) {
            Field f = findField(target.getClass(), name);
            if (f == null) return null;
            try {
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    public static boolean setFieldQuiet(Object target, String name, Object value) {
        if (target == null) return false;
        try {
            XposedHelpers.setObjectField(target, name, value);
            return true;
        } catch (Throwable t) {
            Field f = findField(target.getClass(), name);
            if (f == null) return false;
            try {
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    public static Field findField(Class<?> c, String name) {
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    public static Integer readIntField(Object target, String name, Integer fallback) {
        Object v = readFieldQuiet(target, name);
        if (v instanceof Number) return ((Number) v).intValue();
        return fallback;
    }

    /** 反射调用一个静态/实例方法，失败返回 null。*/
    public static Object invokeQuiet(Object receiver, Class<?> clazz, String methodName, Class<?>[] paramTypes, Object... args) {
        try {
            Method m = clazz.getDeclaredMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m.invoke(receiver, args);
        } catch (Throwable t) {
            Log.v("反射调用失败 " + clazz.getName() + "#" + methodName + ": " + shortMsg(t));
            return null;
        }
    }

    /** 系统 API（Instrumentation / Application）挂不上时单独记一条，便于定位。*/
    public static void hookFailed(String group, String sig, Throwable t) {
        HookReport.fail(group, sig, shortMsg(t));
    }

    public static String shortMsg(Throwable t) {
        if (t == null) return "";
        String m = t.getClass().getSimpleName() + ": " + t.getMessage();
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private static String typesOf(Object[] paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(',');
            Object p = paramTypes[i];
            sb.append(p instanceof Class ? ((Class<?>) p).getSimpleName() : String.valueOf(p));
        }
        return sb.append(')').toString();
    }

    public static void forget(String signature) {
        sInstalled.remove(signature);
    }

    /** 这条签名是否已经挂过（跨进程/跨入口共用一个安装表，用来防止同一份结果注入两遍）。*/
    public static boolean isInstalled(String signature) {
        return sInstalled.contains(signature);
    }

    public static int installedCount() {
        return sInstalled.size();
    }
}
