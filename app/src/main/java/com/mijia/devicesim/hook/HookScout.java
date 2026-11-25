/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 17:57:47 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 侦察兵：盯住米家把类加载进来的那一刻，只做「发现」，不做「挂载」。
 *
 * <p>为什么需要它 —— 米家升级一次，混淆名就换一批，写死的类名/方法名必然失效；而且不少页面类
 * （比如 {@code TcaDetailSelectViewModel}）是点进页面那一秒才加载的，安装钩子的时候
 * {@code Class.forName} 根本拿不到。以前的做法是「安装时找不到 → 记一条 SKIP 就完事」，
 * 结果就是升级后能力页静默变空。
 *
 * <p>这里挂在 {@code ClassLoader.loadClass} 的 after 上，每个新类只做一次字符串前缀判断：
 * <ul>
 *   <li><b>待挂名单</b>：{@link HookSniper} 发现类还没加载时会登记到这里，类一被加载就回调，
 *       由狙击手按「名字 → 形状」重新解析并挂上 —— 这就是「延迟挂载」；</li>
 *   <li><b>形状侦察</b>：对 specscene / scene 包下的顶层类做一次轻量反射，发现同时具备
 *       {@code (String, Continuation)} 与 {@code (String, String, Continuation)} 的类，
 *       就记一条「疑似 TCA 仓库」。下次整包改名时，界面报告里直接能看到新的嫌疑人，
 *       不用再翻 60MB 的反汇编 dump。</li>
 * </ul>
 *
 * <p>开销控制：前缀判断全是字符串操作；形状侦察只对两个包、只对顶层类、上限 {@link #DEEP_MAX}
 * 个；所有反射都套在 {@link #IN_PROBE} 里，防止「反射触发类加载 → 又进回调」的无限递归。
 */
public final class HookScout {

    private static final String GROUP = "scout";

    /** 只关心这些包：TCA / 场景 / spec / 设备层。*/
    private static final String[] WATCH = {
            "com.xiaomi.smarthome.specscene.",
            "com.xiaomi.smarthome.scene.",
            "com.xiaomi.smarthome.spec.",
            "com.xiaomi.smarthome.device.",
    };

    /** 这两个包才做反射形状侦察（TCA 能力页全在这两块里）。*/
    private static final String[] DEEP = {
            "com.xiaomi.smarthome.specscene.",
            "com.xiaomi.smarthome.scene.",
    };

    /** 系统/三方库直接跳过，别给 ClassLoader 添堵。*/
    private static final String[] SKIP = {
            "java.", "javax.", "android.", "androidx.", "dalvik.", "sun.", "libcore.",
            "kotlin.", "kotlinx.", "org.", "okhttp3.", "okio.", "retrofit2.",
            "com.google.", "com.facebook.", "com.bumptech.", "com.squareup.",
            "io.reactivex.", "rx.", "net.", "cn.",
    };

    /** 名字最多记这么多个，超出就不再统计（防内存被刷爆）。*/
    private static final int MAX_PROBE = 20000;
    /** 反射形状侦察最多看这么多个类。*/
    private static final int DEEP_MAX = 600;

    /**
     * 待挂名单：类名 → 回调。
     * 类一加载就整批执行并移除；同一个类名可以被登记多次（不同钩子等同一个类）。
     */
    private static final Map<String, List<Watcher>> sPending = new ConcurrentHashMap<>();
    /** 已经见过的类名（只为统计和去重）。*/
    private static final Set<String> sSeen = ConcurrentHashMap.newKeySet();
    /** 已经做过形状侦察的类。*/
    private static final Set<String> sDeepSeen = ConcurrentHashMap.newKeySet();
    /** 已经报过的「疑似 TCA 仓库」，避免同一页反复刷报告。*/
    private static final Set<String> sRepoReported = ConcurrentHashMap.newKeySet();

    private static final ThreadLocal<Boolean> IN_PROBE = new ThreadLocal<>();
    private static volatile Set<XC_MethodHook.Unhook> sUnhooks;
    private static volatile int sProbes;
    private static volatile int sDelivered;

    /** 类加载后的回调。故意不用 Consumer —— 模块里已有自己的小接口更省心。*/
    public interface Watcher {
        void onLoaded(Class<?> clazz);
    }

    private HookScout() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sUnhooks != null) return;
        Set<XC_MethodHook.Unhook> all = new HashSet<>();
        try {
            all.addAll(XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", PROBE));
        } catch (Throwable t) {
            HookReport.fail(GROUP, "ClassLoader#loadClass", HookUtil.shortMsg(t));
        }
        // findClass 是 BaseDexClassLoader 直接加载 dex 的入口，部分反射路径不经过 loadClass
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            all.addAll(XposedBridge.hookAllMethods(base, "findClass", PROBE));
        } catch (Throwable ignored) {
            // 老系统/非 ART 没有这个类，loadClass 已经够用
        }
        sUnhooks = all;
        if (all.isEmpty()) {
            HookReport.fail(GROUP, "ClassLoader", "一个挂点都没拿到，延迟挂载不可用");
            return;
        }
        HookReport.ok(GROUP, "ClassLoader", "侦察兵上线，" + all.size() + " 个挂点，关注 "
                + WATCH.length + " 个包");
        Log.d("侦察兵上线: 挂点=" + all.size() + " deltaPending=" + sPending.size());
    }

    /**
     * 登记「这个类加载时叫我」。类可能马上加载、也可能几十秒后才加载。
     *
     * <p>注意：调用方应该已经自己试过 {@code findClassQuiet} 并失败，否则会漏掉已加载的情况。
     */
    public static void await(String className, Watcher watcher) {
        if (className == null || watcher == null) return;
        sPending.computeIfAbsent(className, k -> new ArrayList<>()).add(watcher);
        Log.v("侦察兵登记待挂: " + className + "（当前 " + sPending.size() + " 个）");
    }

    /** 这个类是否已经被侦察兵看到过。*/
    public static boolean seen(String className) {
        return className != null && sSeen.contains(className);
    }

    public static int probes() {
        return sProbes;
    }

    public static int pendingCount() {
        return sPending.size();
    }

    private static final XC_MethodHook PROBE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(IN_PROBE.get())) return;
            Object result = param.getResult();
            if (!(result instanceof Class)) return;
            Class<?> clazz = (Class<?>) result;
            String name = clazz.getName();
            if (name == null || name.isEmpty()) return;

            // 待挂名单优先：哪怕不在关注包里也要回调（调用方点名要的类）
            List<Watcher> waiters = sPending.isEmpty() ? null : sPending.remove(name);
            if (waiters != null && !waiters.isEmpty()) {
                deliver(clazz, waiters);
                // 继续往下走，形状侦察照样做
            }

            if (!watched(name)) return;
            if (!sSeen.add(name)) return;
            int n = sProbes + 1;
            sProbes = n;
            if (n > MAX_PROBE) return;
            if (n % 200 == 0) Log.d("侦察兵进度: 已看 " + n + " 个类，待挂 " + sPending.size());

            if (deepWanted(name)) {
                IN_PROBE.set(Boolean.TRUE);
                try {
                    deepProbe(clazz);
                } catch (Throwable t) {
                    Log.v("侦察形状失败 " + name + ": " + HookUtil.shortMsg(t));
                } finally {
                    IN_PROBE.remove();
                }
            }
        }
    };

    private static void deliver(Class<?> clazz, List<Watcher> waiters) {
        sDelivered += waiters.size();
        for (Watcher w : waiters) {
            try {
                w.onLoaded(clazz);
            } catch (Throwable t) {
                Log.w("侦察兵回调异常 " + clazz.getName() + ": " + HookUtil.shortMsg(t));
            }
        }
        Log.d("侦察兵交付 " + clazz.getName() + " 给 " + waiters.size() + " 个等待者"
                + "（累计 " + sDelivered + "）");
    }

    private static boolean watched(String name) {
        for (String s : SKIP) if (name.startsWith(s)) return false;
        for (String w : WATCH) if (name.startsWith(w)) return true;
        return false;
    }

    private static boolean deepWanted(String name) {
        if (name.indexOf('$') >= 0) return false;      // 只看顶层类，省一半反射
        for (String w : DEEP) if (name.startsWith(w)) return true;
        return false;
    }

    /**
     * 形状侦察：这个类像不像 TCA 配置仓库？
     *
     * <p>TCA 仓库的特征非常固定：三路缓存都是 {@code (String did, Continuation)}，
     * 三路远端都是 {@code (String did, String model, Continuation)}。一个类同时具备这两种形状，
     * 基本就是它 —— 名字改了也能认出来，报告里直接写名字，下次直接按新名字挂。
     */
    private static void deepProbe(Class<?> clazz) {
        if (!sDeepSeen.add(clazz.getName())) return;
        if (sDeepSeen.size() > DEEP_MAX) return;
        boolean has2 = false;
        boolean has3 = false;
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        for (Method m : methods) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == String.class && isContinuation(p[1])) has2 = true;
            else if (p.length == 3 && p[0] == String.class && p[1] == String.class
                    && isContinuation(p[2])) has3 = true;
            if (has2 && has3) break;
        }
        if (has2 && has3 && sRepoReported.add(clazz.getName())) {
            HookReport.ok(GROUP, clazz.getName(), "疑似 TCA 配置仓库（2 参 + 3 参 Continuation 形状）");
            Log.d("侦察兵发现疑似 TCA 仓库: " + clazz.getName());
        }
    }

    private static boolean isContinuation(Class<?> c) {
        return c != null && "Continuation".equals(c.getSimpleName());
    }

    /** 摘掉侦察钩子（一般不用，留着给以后收尾）。*/
    public static void stop() {
        Set<XC_MethodHook.Unhook> unhooks = sUnhooks;
        sUnhooks = null;
        if (unhooks == null) return;
        for (XC_MethodHook.Unhook u : unhooks) {
            try {
                u.unhook();
            } catch (Throwable ignored) {
            }
        }
        Log.d("侦察兵收工: 看过 " + sProbes + " 个类，交付 " + sDelivered + " 次");
    }
}