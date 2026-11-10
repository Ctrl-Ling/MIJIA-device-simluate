/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.Log;

import java.lang.reflect.Field;

/**
 * 字段写入的类型对齐层。
 *
 * 为什么不能直接 {@code Field.set}：米家 bean 里的 sort/pid 是基本类型 int，
 * 而我们传的是装箱 Integer，反射在个别 ART 版本上会因包装类型不匹配抛 IllegalArgumentException；
 * 更常见的是同一个语义在不同版本从 int 变成 long。这里统一按目标字段类型换算。
 */
final class XposedFieldSetter {

    private XposedFieldSetter() {
    }

    /** 字段存在才写，不存在静默跳过：跨版本 bean 字段名会变，这比抛异常好用。*/
    static boolean setIfPresent(Object target, String name, Object value) {
        if (target == null) return false;
        try {
            set(target, name, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void set(Object target, String name, Object value) throws Exception {
        Field f = HookUtil.findField(target.getClass(), name);
        if (f == null) throw new NoSuchFieldException(name);
        f.setAccessible(true);
        Class<?> t = f.getType();

        if (t == int.class || t == Integer.class) {
            f.set(target, toNumber(value, name).intValue());
        } else if (t == long.class || t == Long.class) {
            if (value instanceof Number || value instanceof Boolean) {
                f.set(target, value instanceof Boolean ? (((Boolean) value) ? 1L : 0L) : ((Number) value).longValue());
            } else {
                f.set(target, Long.parseLong(String.valueOf(value)));
            }
        } else if (t == double.class || t == Double.class) {
            f.set(target, toNumber(value, name).doubleValue());
        } else if (t == float.class || t == Float.class) {
            f.set(target, toNumber(value, name).floatValue());
        } else if (t == short.class || t == Short.class) {
            f.set(target, toNumber(value, name).shortValue());
        } else if (t == byte.class || t == Byte.class) {
            f.set(target, toNumber(value, name).byteValue());
        } else if (t == boolean.class || t == Boolean.class) {
            f.setBoolean(target, toBool(value));
        } else if (t == String.class) {
            f.set(target, value instanceof String ? value : String.valueOf(value));
        } else if (t.isInstance(value)) {
            f.set(target, value);
        } else {
            // 字段类型是我们不认识的对象（版本改了结构），跳过而不是硬塞
            throw new IllegalArgumentException("字段类型不匹配 " + name + " 需要 " + t.getSimpleName()
                    + " 提供 " + value.getClass().getSimpleName());
        }
    }

    private static Number toNumber(Object value, String name) {
        if (value instanceof Number) return (Number) value;
        if (value instanceof Boolean) return ((Boolean) value) ? 1 : 0;
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            Log.v("字段 " + name + " 不是数字: " + value);
            return 0;
        }
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        String s = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("on");
    }
}
