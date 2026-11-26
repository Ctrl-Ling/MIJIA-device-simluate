/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 14:47:22 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 狙击手：把一个「目标」解析成真的 {@link Method} 并挂上，且能说清楚是靠什么命中的。
 *
 * <p>三级解析链，一级不行退一级：
 * <ol>
 *   <li><b>名字 + 形状</b>：类名对、方法名对，参数/返回类型也符合 {@link Shape} —— 最稳；</li>
 *   <li><b>名字</b>：方法名还在但参数变了（米家给某个方法多加了个参数），全挂上，
 *       让回调自己去按运行时参数分派 —— 和旧的 {@code hookAllByName} 等价；</li>
 *   <li><b>形状</b>：方法连名字都换了，就在类里按「参数个数 + 参数类型 + 可选的混淆名模式」
 *       扫一遍，把形状对上的都挂上；调用方一般会给个 {@code [oO0]{6,9}} 之类的模式，
 *       避免把整个仓库的所有 {@code (String, Continuation)} 查询都误挂。</li>
 * </ol>
 *
 * <p>类还没加载（页面类常见）时，不在安装阶段硬失败，而是交给 {@link HookScout#await}：
 * 类一被加载，同一个挂载流程重跑一次，命中/失败照样写进 {@link HookReport}。
 *
 * <p>命中方式会写进报告（{@code 名字+形状 / 名字 / 形状+模式}），升级后一眼就能看出
 * 「这次是靠名字还是靠形状活下来的」。
 */
public final class HookSniper {

    private HookSniper() {
    }

    /** 已挂过的方法，防重复回调（多进程/延迟挂载 + 直接挂载可能撞车）。*/
    private static final Set<String> sHooked = ConcurrentHashMap.newKeySet();

    /** 方法形状描述：参数按「简单类名」比，{@code null} 表示任意。*/
    public static final class Shape {
        private final String[] params;
        private final String ret;

        private Shape(String ret, String[] params) {
            this.ret = ret;
            this.params = params;
        }

        /**
         * {@code Shape.of("Object", "String", "Continuation")}；ret 传 null 表示不看返回类型。
         * 不传参数 = 无参方法；要「任意参数」用 {@link #anyArgs(String)}。
         */
        public static Shape of(String ret, String... params) {
            return new Shape(ret, params == null ? new String[0] : params);
        }

        /** 只看返回类型/任意参数。*/
        public static Shape anyArgs(String ret) {
            return new Shape(ret, null);
        }

        /** 无参。*/
        public static Shape noArgs(String ret) {
            return new Shape(ret, new String[0]);
        }

        boolean matches(Method m) {
            if (params != null) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length != params.length) return false;
                for (int i = 0; i < p.length; i++) {
                    String want = params[i];
                    if (want == null) continue;
                    if ("Continuation".equals(want)) {
                        // R8 会把 Continuation 的具体实现类也混淆掉（实测 cy5），
                        // 所以协程挂起参数不能按类名死比，认「实现了 Continuation」就行
                        if (!isContinuation(p[i])) return false;
                        continue;
                    }
                    if (!want.equals(p[i].getSimpleName())) return false;
                }
            }
            if (ret != null) {
                String got = m.getReturnType().getSimpleName();
                if (!ret.equals(got) && !"Object".equals(ret)) return false;
            }
            return true;
        }

        /**
         * 这个类型是不是 Kotlin 协程的 Continuation。
         *
         * <p>实测 11.10.623 的参数类型是 {@code cy5} —— 米家把 kotlin 协程类也一起混淆了，
         * 光比类名认不出来。所以除了名字，还按结构认：Continuation 必然带
         * {@code resumeWith(Object)}，顺着接口/父类递归找。
         */
        private static boolean isContinuation(Class<?> c) {
            return shapeWalk(c, 0);
        }

        private static boolean shapeWalk(Class<?> c, int depth) {
            if (c == null || c == Object.class || depth > 6) return false;
            String simple = c.getSimpleName();
            if ("Continuation".equals(simple) || "ContinuationImpl".equals(simple)) return true;
            if (c.isInterface() && hasResumeWith(c)) return true;
            for (Class<?> i : c.getInterfaces()) {
                if (shapeWalk(i, depth + 1)) return true;
            }
            return shapeWalk(c.getSuperclass(), depth + 1);
        }

        private static boolean hasResumeWith(Class<?> c) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if ("resumeWith".equals(m.getName()) && m.getParameterCount() == 1) return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    /**
     * 解析并挂载一个类的若干方法。
     *
     * @param loader    米家的 ClassLoader
     * @param group     报告分组（和 {@link HookReport} 里看到的 group 一致）
     * @param className 目标类名（可能已改名）
     * @param names     已知的方法名（多个重载名），null/空表示直接按形状扫
     * @param shape     期望形状，null 表示不看形状
     * @param nameRegex 形状兜底时的方法名过滤（可空），比如 {@code "[oO0]{6,9}"}
     * @param callback  命中后挂的回调
     * @return 实际新挂上的方法个数
     */
    public static int hook(ClassLoader loader, String group, String className, String[] names,
                           Shape shape, String nameRegex, XC_MethodHook callback) {
        if (loader == null || className == null || callback == null) return 0;
        Class<?> clazz = HookUtil.findClassQuiet(className, loader);
        if (clazz == null) {
            // 页面类/插件类经常是点进去才加载 —— 交给侦察兵，类到了自动重跑这一套
            HookScout.await(className, c -> {
                int n = mount(group, c, names, shape, nameRegex, callback);
                if (n > 0) Log.d("狙击手延迟命中 " + className + " → " + n + " 个挂点");
            });
            HookReport.skip(group, className, "类尚未加载，已交侦察兵等待（加载后自动挂）");
            return 0;
        }
        return mount(group, clazz, names, shape, nameRegex, callback);
    }

    /** 不带形状兜底的简便版。*/
    public static int hook(ClassLoader loader, String group, String className, String[] names,
                           Shape shape, XC_MethodHook callback) {
        return hook(loader, group, className, names, shape, null, callback);
    }

    /**
     * 候选类名版：一个逻辑目标在版本间换过好几个类名时，逐个试；没加载的全部交侦察兵，
     * 谁先到就挂谁。去重键按「声明类#方法/参数数」，别名类同时存在也不会重复回调。
     */
    public static int hook(ClassLoader loader, String group, String[] classNames, String[] names,
                           Shape shape, String nameRegex, XC_MethodHook callback) {
        if (loader == null || classNames == null || classNames.length == 0 || callback == null) return 0;
        int total = 0;
        int loaded = 0;
        for (String cn : classNames) {
            if (cn == null || cn.isEmpty()) continue;
            Class<?> clazz = HookUtil.findClassQuiet(cn, loader);
            if (clazz != null) {
                loaded++;
                total += mount(group, clazz, names, shape, nameRegex, callback);
            } else {
                HookScout.await(cn, c -> {
                    int n = mount(group, c, names, shape, nameRegex, callback);
                    if (n > 0) Log.d("狙击手延迟命中 " + cn + " → " + n + " 个挂点");
                });
            }
        }
        if (loaded == 0) {
            HookReport.skip(group, classNames[0],
                    "候选类都尚未加载（" + classNames.length + " 个），已交侦察兵");
        }
        return total;
    }

    /**
     * 已经拿到 {@link Class} 的场景（实现类是从字段/单例反出来的，类名运行时才知道）：
     * 不走类名等待，直接跑同一套三级解析。这样「仓库出口」这类晚绑定目标也归狙击手管。
     */
    public static int hookOnClass(Class<?> clazz, String group, String[] names, Shape shape,
                                  String nameRegex, XC_MethodHook callback) {
        if (clazz == null || callback == null) return 0;
        return mount(group, clazz, names, shape, nameRegex, callback);
    }

    /** {@link #hookOnClass(Class, String, String[], Shape, String, XC_MethodHook)} 的无模式版。*/
    public static int hookOnClass(Class<?> clazz, String group, String[] names, Shape shape,
                                  XC_MethodHook callback) {
        return hookOnClass(clazz, group, names, shape, null, callback);
    }

    /**
     * 调用方已经按形状选好了 {@link Method}（比如设备管理器那种「一类的出口都扫一遍」）：
     * 统一走狙击手的去重/挂载口，报告由调用方按自己的节奏写，避免一条方法一条报告刷屏。
     */
    public static boolean hookResolved(Method m, XC_MethodHook callback) {
        return m != null && callback != null && hookOnce(m, callback);
    }

    private static int mount(String group, Class<?> clazz, String[] names, Shape shape,
                             String nameRegex, XC_MethodHook callback) {
        List<Method> hits = new ArrayList<>();
        String how;
        try {
            List<Method> byName = names == null ? new ArrayList<>() : byName(clazz, names);
            if (!byName.isEmpty()) {
                if (shape == null) {
                    hits.addAll(byName);
                    how = "名字";
                } else {
                    for (Method m : byName) if (shape.matches(m)) hits.add(m);
                    if (!hits.isEmpty()) {
                        how = hits.size() == byName.size() ? "名字+形状" : "名字+形状（部分重载）";
                    } else {
                        // 名字在、形状全变（米家加参数了）——退回旧的「按名全挂」，由回调自己分派；
                        // 实际形状写进报告，下次直接照它改 Shape 常量
                        hits.addAll(byName);
                        how = "名字（形状已变，全挂；实际 " + describe(byName) + "）";
                    }
                }
            } else if (shape != null) {
                Pattern p = null;
                if (nameRegex != null && !nameRegex.isEmpty()) {
                    try {
                        p = Pattern.compile(nameRegex);
                    } catch (Throwable ignored) {
                    }
                }
                for (Method m : allDeclared(clazz)) {
                    if (p != null && !p.matcher(m.getName()).matches()) continue;
                    if (shape.matches(m)) hits.add(m);
                }
                how = p == null ? "形状" : "形状+模式";
            } else {
                HookReport.fail(group, clazz.getName(), "方法名不存在且没有形状兜底；本类方法形状: "
                        + describe(clazz));
                return 0;
            }
        } catch (Throwable t) {
            HookReport.fail(group, clazz.getName(), "解析异常: " + HookUtil.shortMsg(t));
            return 0;
        }

        int n = 0;
        for (Method m : hits) {
            if (hookOnce(m, callback)) n++;
        }
        if (n > 0) {
            HookReport.ok(group, clazz.getName(), n + " 个方法（" + how + "）");
            Log.d("狙击手命中 " + clazz.getName() + " → " + n + " 个（" + how + "）");
        } else if (!hits.isEmpty()) {
            HookReport.skip(group, clazz.getName(), "命中 " + hits.size() + " 个但都已经挂过");
        } else {
            HookReport.fail(group, clazz.getName(), "没有方法符合形状；本类方法形状: " + describe(clazz));
        }
        return n;
    }

    private static boolean hookOnce(Method m, XC_MethodHook callback) {
        // 去重键必须带上「回调身份」：同一个方法经常被两个不同用途的挂点盯上
        // （比如 RnBridgeProbe 把桥上的方法全挂一遍做观测，RnCloudStub 还要真正应答）。
        // 只按「声明类#方法/参数数」去重的话，后装的应答层会被静默丢掉 —— v139 的
        // callSmartHomeAPI=0 就是撞在这里。匿名回调类名按声明处唯一，重复挂载同一声明仍会去重。
        String key = m.getDeclaringClass().getName() + "#" + m.getName() + "/" + m.getParameterCount()
                + "@" + callback.getClass().getName();
        if (!sHooked.add(key)) return false;
        try {
            XposedBridge.hookMethod(m, callback);
            return true;
        } catch (Throwable t) {
            sHooked.remove(key);
            Log.w("狙击手挂载失败 " + key + ": " + HookUtil.shortMsg(t));
            return false;
        }
    }

    private static List<Method> byName(Class<?> clazz, String[] names) {
        List<Method> out = new ArrayList<>();
        for (Method m : allDeclared(clazz)) {
            for (String n : names) {
                if (m.getName().equals(n)) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }

    /** 沿继承链取全部声明方法，跳过桥接/合成和抽象（抽象方法挂了也不会被调用）。*/
    private static List<Method> allDeclared(Class<?> clazz) {
        List<Method> out = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : methods) {
                if (m.isBridge() || m.isSynthetic()) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                out.add(m);
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /** 失败时把类里「长什么样」写进报告，方便下次直接照着改常量。最多列 12 条。*/
    private static String describe(Class<?> clazz) {
        return describe(allDeclared(clazz));
    }

    /** 指定方法列表的形状摘要，同样最多 12 条。*/
    private static String describe(List<Method> methods) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Method m : methods) {
            if (shown >= 12) {
                sb.append(" …");
                break;
            }
            if (shown > 0) sb.append("; ");
            sb.append(m.getName()).append('(');
            Class<?>[] p = m.getParameterTypes();
            for (int i = 0; i < p.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(p[i].getSimpleName());
            }
            sb.append(")->").append(m.getReturnType().getSimpleName());
            shown++;
        }
        return sb.length() == 0 ? "空" : sb.toString();
    }
}