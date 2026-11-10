/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 11:21:54 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 钩子点调度（plan Phase 6「版本自适应」的实现处）。
 *
 * <p>问题：米家把设备管理器改名成 {@code _m_j.zxf} 这种，下次版本可能又变。写死类名 = 一次升级全线失效。
 *
 * <p>做法是两级：
 * <ol>
 *   <li><b>加速路径</b>：先按 11.9.705 实测到的类名直接找、直接挂（大部分情况一次命中，零开销）；</li>
 *   <li><b>指纹路径</b>：同时挂 {@code ClassLoader.loadClass}，每个新载入的类先用字符串规则粗筛
 *       （只看不反射），过筛的才取 {@code getDeclaredMethods()} 比对形状。命中就现场挂，
 *       挂满即摘掉这个钩子，不给米家留常驻开销。</li>
 * </ol>
 *
 * <p>结果都进 {@link HookReport}，界面上「钩子命中」页能直接看到哪条黄了。
 */
public final class HookRegistry {

    private static final String GROUP = "registry";

    /** 已知实现类的直接路径（11.9.705 实测，见 docs/HOOKS.md）。*/
    private static final String[] KNOWN_MANAGERS = {
            "_m_j.zxf",
            "_m_j.ob7",
            "com.xiaomi.smarthome.device.impl.OooO0O0",
    };

    /** 达到这个数就认为设备层挂全了，摘掉 loadClass 钩子。*/
    private static final int ENOUGH = 3;

    private static final Set<String> sChecked = ConcurrentHashMap.newKeySet();
    private static volatile int sManagersHooked;
    private static volatile Set<XC_MethodHook.Unhook> sLoaderUnhooks;
    /** 反射读取形状本身会触发新的类加载，必须防住回调套回调。*/
    private static final ThreadLocal<Boolean> sBusy = new ThreadLocal<>();

    private HookRegistry() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        for (String name : KNOWN_MANAGERS) {
            Class<?> c = HookUtil.findClassQuiet(name, lpparam.classLoader);
            if (c == null) continue;
            if (!DeviceListHook.looksLikeDeviceManager(c)) {
                HookReport.skip(GROUP, name, "形状不匹配（可能已改名，交给指纹路径）");
                continue;
            }
            sManagersHooked += DeviceListHook.hookDeviceManager(c);
        }
        HookReport.ok(GROUP, "known-managers", sManagersHooked + " 个方法来自 " + KNOWN_MANAGERS.length + " 个候选类");
        startFingerprinting();
    }

    private static void startFingerprinting() {
        if (sManagersHooked >= ENOUGH) {
            Log.d("设备管理器已由已知类名全部挂上，跳过类加载指纹");
            return;
        }
        try {
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", FINGERPRINT);
            if (unhooks.isEmpty()) {
                HookReport.fail(GROUP, "ClassLoader#loadClass", "框架未返回任何挂点");
                return;
            }
            sLoaderUnhooks = unhooks;
            HookReport.ok(GROUP, "ClassLoader#loadClass", "指纹观察中");
        } catch (Throwable t) {
            HookUtil.hookFailed(GROUP, "ClassLoader#loadClass", t);
        }
    }

    private static final XC_MethodHook FINGERPRINT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (Boolean.TRUE.equals(sBusy.get())) return;
            Object result = param.getResult();
            if (!(result instanceof Class)) return;
            Class<?> clazz = (Class<?>) result;
            String name = clazz.getName();
            if (!worthChecking(name)) return;
            if (!sChecked.add(name)) return;
            sBusy.set(Boolean.TRUE);
            try {
                if (DeviceListHook.looksLikeDeviceManager(clazz)) {
                    int n = DeviceListHook.hookDeviceManager(clazz);
                    sManagersHooked += n;
                    if (sManagersHooked >= ENOUGH) stopFingerprinting();
                } else {
                    // 后路：扫描管理器形状变了时，靠这里撞出 onScan(List) 实现类；
                    // 正常命中已知类名时，扫描注入走 AddFlowHook.install 里的 dispatchOnScan。
                    AddFlowHook.probe(clazz);
                }
            } catch (Throwable t) {
                Log.v("指纹检查 " + name + " 失败: " + HookUtil.shortMsg(t));
            } finally {
                sBusy.remove();
            }
        }
    };

    /**
     * 粗筛：纯字符串操作，不做任何反射，代价必须足够低。
     *
     * <p>放进来的是「可能是米家业务类」的：{@code com.xiaomi.smarthome.*}、{@code com.miot.*}，
     * 以及 R8 把包名也改掉的两段式短包（{@code _m_j.zxf} 这种）。内部类、框架类、三方库直接跳过。
     */
    private static boolean worthChecking(String rawName) {
        if (rawName == null) return false;
        // 内部类/匿名类要一起看：扫描回调 gua 的实现全是 DeviceScanManager$1 这种
        String name = rawName;
        int dollar = name.indexOf('$');
        if (dollar > 0) name = name.substring(0, dollar);
        if (name.startsWith("com.xiaomi.smarthome") || name.startsWith("com.miot")) return true;
        if (name.startsWith("android.") || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("kotlin.") || name.startsWith("k.") || name.startsWith("org.")
                || name.startsWith("com.google.") || name.startsWith("rx.") || name.startsWith("io.")
                || name.startsWith("okhttp") || name.startsWith("okio") || name.startsWith("com.xiaomi.")
                || name.startsWith("retrofit2.") || name.startsWith("cn.") || name.startsWith("net.")
                || name.startsWith("dalvik.") || name.startsWith("sun.")) {
            return false;
        }
        int last = name.lastIndexOf('.');
        if (last <= 0) return false;
        String pkg = name.substring(0, last);
        // 混淆包特征：整条包路径很短（1~2 段），比如 _m_j
        return pkg.indexOf('.') < 0 ? pkg.length() <= 6 : pkg.lastIndexOf('.') < 0 && pkg.length() <= 12;
    }

    private static void stopFingerprinting() {
        try {
            Set<XC_MethodHook.Unhook> unhooks = sLoaderUnhooks;
            if (unhooks != null) {
                for (XC_MethodHook.Unhook u : unhooks) u.unhook();
                sLoaderUnhooks = null;
            }
        } catch (Throwable ignored) {
        }
        Log.d("设备管理器挂满 " + sManagersHooked + " 个，类加载指纹已摘除");
    }

    public static int managersHooked() {
        return sManagersHooked;
    }

    public static boolean fingerprinting() {
        return sLoaderUnhooks != null;
    }
}
