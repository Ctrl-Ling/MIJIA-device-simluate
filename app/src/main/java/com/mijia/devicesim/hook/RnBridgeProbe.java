/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 21:28:25 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 设备详情页是 RN 插件页（{@code PluginRNActivity}，跑在 {@code :plugin0}），
 * 它的数据不走 {@code XmPluginHostApi} 的属性方法（v94 挂满 8 个点，页面打开后一条命中都没有），
 * 也不走普通 RN 桥（`jniCallJSFunction` 里只有 JSTimers / RCTEventEmitter——米家把 nativemodule
 * 做成 JSI 直调，JS 一叫就到 Java 方法，桥上不留痕）。
 *
 * <p>所以这一版改成<b>铺开打点</b>：把 RN 插件用到的三个 NativeModule
 * （{@code MIOTSpecModule} / {@code MIOTDeviceModuleCore} / {@code MIOTHostModuleCore}）
 * 的每个 public 方法都挂上，把方法名 + 参数打出来；再把
 * {@code PluginHostApiImpl.getDeviceByDid / getSpecDeviceController} 的结果也打出来
 * （假设备在 RN 这层能不能被认出来，一眼就知道）。
 *
 * <p>纯观测：不拦不改。拿到这张调用单之后，才知道该在哪个方法上接住假设备。
 */
public final class RnBridgeProbe {

    private static final String GROUP = "rnprobe";

    private static final String CL_CATALYST = "com.facebook.react.bridge.CatalystInstanceImpl";
    private static final String CL_BASE = "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTBaseJavaModule";
    private static final String CL_SPEC = "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTSpecModule";
    private static final String CL_DEVICE = "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTDeviceModuleCore";
    private static final String CL_HOST = "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTHostModuleCore";
    private static final String CL_HOSTAPI = "com.xiaomi.smarthome.framework.plugin.mpk.PluginHostApiImpl";
    private static final String CL_RUNTIME = "com.xiaomi.smarthome.framework.api.provider.core.RnRuntimeProviderImpl";

    private static final String PKG = "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.";
    /** 同包其余 NativeModule：属性之外，JS 还可能先问服务/房间/包信息。*/
    private static final String[] MORE_MODULES = {
            PKG + "MIOTServiceModuleCore",
            PKG + "MIOTSceneModule",
            PKG + "MIOTSystemModuleCore",
            PKG + "MIOTSystemModule",
            PKG + "MIOTRoomManageModuleCore",
            PKG + "MIOTPersistModuleCore",
            PKG + "MIOTPersistModule",
            PKG + "MIOTPackageModuleCore",
            PKG + "MIOTPackageModule",
            PKG + "MIOTAudioModuleCore",
            PKG + "MIOTMapSearchModule",
    };

    /** 参数串太长会把日志刷爆，够看清 did/siid/piid 就行。*/
    private static final int MAX_LEN = 260;

    private static final Set<String> sHooked = new LinkedHashSet<>();
    private static volatile boolean sInstalled;

    private RnBridgeProbe() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstalled) return;
        sInstalled = true;

        ClassLoader loader = lpparam.classLoader;
        HookSniper.hook(loader, GROUP, CL_CATALYST, new String[]{"jniCallJSFunction"},
                HookSniper.Shape.of(null, "String", "String", "NativeArray"),
                "jniCallJSFunction", BRIDGE);

        // 这些类的继承链上一路铺开（MIOTHostModule 继承 MIOTHostModuleCore）
        hookAll(loader, CL_BASE);
        hookAll(loader, CL_SPEC);
        hookAll(loader, CL_DEVICE);
        hookAll(loader, CL_HOST);
        // 详情页卡在「加载中」时属性请求一个都没发出，说明它还堵在别处 ——
        // 把同包下其余的 NativeModule 也铺开，看 JS 到底先问了谁
        for (String cls : MORE_MODULES) hookAll(loader, cls);

        // RN 这层认不认识假设备：did → DeviceStat 的结果直接打出来
        hookResult(loader, CL_HOSTAPI, "getDeviceByDid", "HostApi.getDeviceByDid");
        hookResult(loader, CL_HOSTAPI, "getSpecDeviceController", "HostApi.getSpecDeviceController");

        // 当前设备是怎么来的：JS 拿到的 currentDevice 是不是空的，就看这两处
        hookResult(loader, CL_BASE, "getDevice", "Base.getDevice");
        hookResult(loader, CL_DEVICE, "doGetConstants", "Device.doGetConstants");
        hookResult(loader, CL_RUNTIME, "getDeviceStat", "Runtime.getDeviceStat");

        Log.d("RN 打点安装完成: " + lpparam.processName + " / " + sHooked.size() + " 个方法");
    }

    /* ---------- 铺开挂 ---------- */

    private static void hookAll(ClassLoader loader, String className) {
        Class<?> top = HookUtil.findClassQuiet(className, loader);
        if (top == null) {
            // RN 模块类可能晚加载：交给侦察兵，类到了再铺
            HookScout.await(className, c -> hookHierarchy(c, className));
            HookReport.skip(GROUP, className, "类尚未加载，已交侦察兵（加载后铺开）");
            return;
        }
        hookHierarchy(top, className);
    }

    private static void hookHierarchy(Class<?> top, String className) {
        int n = 0;
        for (Class<?> c = top; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : methods) {
                if (!Modifier.isPublic(m.getModifiers())) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (m.isSynthetic()) continue;
                // 基类（ReactContextBaseJavaModule）的访问器每帧都被调，日志会被刷爆，
                // 跟详情页数据也无关，整段跳过；getConstants 另有 doGetConstants 专门打点
                if (c.getName().startsWith("com.facebook.react")) continue;
                if ("getConstants".equals(m.getName())) continue;
                if ("getName".equals(m.getName())) continue;
                String sig = c.getName() + "#" + m.getName() + "/" + m.getParameterCount();
                synchronized (sHooked) {
                    if (!sHooked.add(sig)) continue;
                }
                // 方法已按 public/继承链条件选好，挂载统一走狙击手去重口
                if (HookSniper.hookResolved(m, NAMED(shortName(c) + "." + m.getName()))) n++;
            }
        }
        HookReport.ok(GROUP, className, "铺开 " + n + " 个方法");
    }

    /** 返回对象的方法：把结果也打出来（null 和真对象一眼分得清）。*/
    private static void hookResult(ClassLoader loader, String className, String name, final String label) {
        // 只按方法名挂、不看形状（打点要覆盖所有重载）；类晚加载同样交侦察兵
        HookSniper.hook(loader, GROUP, className, new String[]{name},
                HookSniper.Shape.anyArgs(null), name, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        // 顺手记下认出来的假设备：spec 闸口在页面刚拉起时可能还拿不到 DeviceStat
                        if (r != null) RnSpecHook.remember(RnSpecHook.didOf(r));
                        Log.d("RN " + label + "(" + brief(param.args != null && param.args.length > 0 ? param.args[0] : null)
                                + ") -> " + (r == null ? "null" : shortName(r.getClass()) + " " + brief(r)));
                    }
                });
    }

    /* ---------- 钩子 ---------- */

    /** 桥的总入口：module + method + 参数数组。*/
    private static final XC_MethodHook BRIDGE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String module = str(param.args[0]);
            String method = str(param.args[1]);
            Log.d("RN桥 " + module + "." + method + " " + brief(param.args.length > 2 ? param.args[2] : null));
        }
    };

    /** 具体方法：把每个参数按位置写出来。*/
    private static XC_MethodHook NAMED(final String label) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                StringBuilder sb = new StringBuilder("RN native ").append(label).append('(');
                int n = param.args == null ? 0 : param.args.length;
                for (int i = 0; i < n; i++) {
                    if (i > 0) sb.append(", ");
                    Object a = param.args[i];
                    // 回调/Promise 只写类型，不然一串 Object@hash 没信息量
                    if (a instanceof String || a instanceof Number || a instanceof Boolean || a == null) {
                        sb.append(brief(a));
                    } else {
                        sb.append(a.getClass().getSimpleName());
                    }
                }
                sb.append(')');
                Log.d(sb.toString());
            }
        };
    }

    /* ---------- 小工具 ---------- */

    private static String shortName(Class<?> c) {
        String n = c.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }

    private static String str(Object o) {
        return o == null ? "null" : String.valueOf(o);
    }

    /** 把 NativeArray 翻成可读串；翻不动就退化成 toString。*/
    private static String brief(Object o) {
        if (o == null) return "null";
        String text;
        try {
            Object list = XposedHelpers.callMethod(o, "toArrayList");
            text = list instanceof ArrayList ? String.valueOf(list) : str(o);
        } catch (Throwable t) {
            text = str(o);
        }
        return text.length() > MAX_LEN ? text.substring(0, MAX_LEN) + "…" : text;
    }
}
