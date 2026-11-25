/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 21:28:25 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceStateStore;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RN 插件页（{@code PluginRNActivity}）的数据闸口：{@code MIOTSpec} 这个 NativeModule。
 *
 * <p>实测链路（v95/v96 打点坐实）：米家把 nativemodule 做成 JSI 直调，JS 一叫就进 Java，
 * 普通 RN 桥（{@code jniCallJSFunction}）里看不到；页面打开时真正被调到的只有
 * {@code MIOTSpec.getSpecString(did, cb)}，而 did 是 <b>null</b>，落到
 * {@code XmPluginHostApi.getDeviceByDid(null)} → null → 回调 {@code (false, "not find device")}。
 * spec 拿不到，页面就一直「加载中」，属性一个都不去读，控件整片置灰、开关点了没反应 ——
 * 跟用户看到的现象完全对上。
 *
 * <p>做法：
 * <ul>
 *   <li>{@code getSpecString}：did 为空时退回本模块进程里的「当前设备」（{@code getDevice()}），
 *       只要是假设备，就把本地 spec（{@link MiotSpec#raw()}，本来就是 miot-spec 的 instance JSON）
 *       直接回给 JS，原方法不再执行；</li>
 *   <li>{@code getProperty}：按 did + siid/piid 从 {@link DeviceStateStore} 取值回
 *       {@code (true, "值")}，不走 {@code DeviceController}（假设备没有真的控制器，一走就 NPE）；</li>
 *   <li>{@code setProperty}：写进本地状态机再返回，页面立刻回显，原方法跳过。</li>
 * </ul>
 *
 * <p>真设备一律放行，一个字节都不改。
 */
public final class RnSpecHook {

    private static final String GROUP = "rnspec";
    private static final String CL_SPEC = "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTSpecModule";

    private static volatile boolean sInstalled;

    private RnSpecHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstalled) return;
        sInstalled = true;

        // RN 模块名/方法名每个大版本都可能动：全部走「名字+形状」，类晚加载交给侦察兵
        ClassLoader loader = lpparam.classLoader;
        int a = HookSniper.hook(loader, GROUP, CL_SPEC, new String[]{"getSpecString"},
                HookSniper.Shape.of(null, "String", "Callback"), "getSpecString", GET_SPEC_STRING);
        int b = HookSniper.hook(loader, GROUP, CL_SPEC, new String[]{"getProperty"},
                HookSniper.Shape.of(null, "String", "int", "int", "Callback"), "getProperty", GET_PROPERTY);
        int c = HookSniper.hook(loader, GROUP, CL_SPEC, new String[]{"setProperty"},
                HookSniper.Shape.of(null, "String", "int", "int", "int", "String"), "setProperty", SET_PROPERTY);
        Log.d("RN spec 闸口: getSpecString=" + a + " getProperty=" + b + " setProperty=" + c);
    }

    /* ---------- spec 下发 ---------- */

    private static final XC_MethodHook GET_SPEC_STRING = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = resolveDid(param);
                if (REAL.equals(did)) return;
                if (did == null) {
                    // 页面刚拉起，RN 运行时还没把 DeviceStat 绑进来 —— 回调先扣住，
                    // 等任何一个钩子认出设备（remember）再回 spec，别让 JS 收到失败
                    holdCallback(param);
                    return;
                }
                String json = specJsonOf(did);
                if (json == null) return;
                if (!invokeCallback(param.args[1], Boolean.TRUE, json)) {
                    Log.w("RN 拿 spec 失败：回调回不去");
                    return;
                }
                param.setResult(null);
                Context ctx = DeviceListHook.context();
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                Log.d("详情页拿 spec(RN): " + (dev == null ? "?" : dev.model) + " " + json.length() + " 字节");
                CallLog.record(ctx, CallLog.OP_GET, did, dev == null ? "" : dev.model, "spec", 1, 1, 0, "getSpecString");
            } catch (Throwable t) {
                Log.w("RN getSpecString 异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 单属性读 ---------- */

    private static final XC_MethodHook GET_PROPERTY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = resolveDid(param);
                if (did == null) return;
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                if (dev == null) return;
                MiotSpec spec = DeviceListHook.specOf(dev);
                if (spec == null) return;
                int siid = (Integer) param.args[1];
                int piid = (Integer) param.args[2];
                Context ctx = DeviceListHook.context();
                Object value = DeviceStateStore.read(ctx, dev, spec, siid, piid);
                String text = value == null ? "" : String.valueOf(value);
                if (!invokeCallback(param.args[3], Boolean.TRUE, text)) return;
                param.setResult(null);
                Log.d("详情页读属性(RN): " + dev.model + " " + siid + "." + piid + "=" + text);
                CallLog.record(ctx, CallLog.OP_GET, did, dev.model, Consts.propKey(siid, piid),
                        1, 1, 0, "MIOTSpec.getProperty");
            } catch (Throwable t) {
                Log.w("RN getProperty 异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 单属性写 ---------- */

    private static final XC_MethodHook SET_PROPERTY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = resolveDid(param);
                if (did == null) return;
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                if (dev == null) return;
                MiotSpec spec = DeviceListHook.specOf(dev);
                if (spec == null) return;
                int siid = (Integer) param.args[1];
                int piid = (Integer) param.args[2];
                String raw = (String) param.args[4];
                MiotSpec.Prop prop = spec.findProp(siid, piid);
                Object value = prop == null ? raw : spec.coerce(prop, raw);
                Context ctx = DeviceListHook.context();
                DeviceStateStore.write(ctx, dev, spec, siid, piid, value);
                param.setResult(null);
                Log.d("详情页写属性(RN): " + dev.model + " " + siid + "." + piid + "=" + value);
                CallLog.record(ctx, CallLog.OP_SET, did, dev.model, Consts.propKey(siid, piid),
                        1, 1, 0, "MIOTSpec.setProperty");
            } catch (Throwable t) {
                Log.w("RN setProperty 异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 小工具 ---------- */

    /**
     * 拿到这次调用对应的假 did。
     * 参数里的 did 是假设备就用它；为空（实测就是空）就退回本模块进程的「当前设备」。
     * 真设备返回 null，调用方直接放行。
     */
    /** resolveDid 的「这是真设备」标记：跟 null（还不知道）区分开，真设备必须原样放行。*/
    private static final String REAL = "\u0000real";

    private static String resolveDid(XC_MethodHook.MethodHookParam param) {
        Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
        if (arg instanceof String) {
            String did = (String) arg;
            if (!did.isEmpty()) return Consts.isSimulatedDid(did) ? did : REAL;
        }
        // did 为空：问一下 NativeModule 自己，当前跑的是哪台设备。
        // 注意时机 —— 页面刚拉起时 RN 运行时还没把 DeviceStat 绑进来，getDevice() 会回 null
        // （实测 09:03:16 取 spec 时为 null，09:03:32 同一进程就有值了），所以还要有备胎。
        try {
            Object stat = XposedHelpers.callMethod(param.thisObject, "getDevice");
            String did = didOf(stat);
            if (did != null) {
                remember(did);
                return Consts.isSimulatedDid(did) ? did : REAL;
            }
        } catch (Throwable t) {
            Log.w("取当前设备失败: " + HookUtil.shortMsg(t));
        }
        // 备胎一：插件页 Activity 的启动 Intent 里就带着 did / extra_device_did
        String fromActivity = didFromActivity(param.thisObject);
        if (fromActivity != null) {
            Log.d("RN spec 用 Activity Intent 认出设备: " + fromActivity);
            return Consts.isSimulatedDid(fromActivity) ? fromActivity : REAL;
        }
        // 备胎二：本进程里先前由别的 NativeModule 认出来过的假 did
        if (sLastSimDid != null) {
            Log.d("RN spec 用本进程缓存认出设备: " + sLastSimDid);
            return sLastSimDid;
        }
        logMissOnce(null);
        return null;
    }

    /** 本进程最近一次认出的假设备 did（原生模块别处调用 getDevice 成功时顺手记下）。*/
    private static volatile String sLastSimDid;

    /** 从当前 RN 插件页的 Intent 里取 did。*/
    private static String didFromActivity(Object module) {
        try {
            Object rac = XposedHelpers.callMethod(module, "getReactApplicationContext");
            if (rac == null) {
                diag("ReactContext 为 null");
                return null;
            }
            Object act = XposedHelpers.callMethod(rac, "getCurrentActivity");
            if (act == null) {
                diag("getCurrentActivity 为 null");
                return null;
            }
            Object intent = XposedHelpers.callMethod(act, "getIntent");
            if (intent == null) {
                diag("Intent 为 null");
                return null;
            }
            for (String key : new String[]{"did", "extra_device_did"}) {
                Object d = XposedHelpers.callMethod(intent, "getStringExtra", key);
                if (d instanceof String && !((String) d).isEmpty()) return (String) d;
            }
            Object extras = XposedHelpers.callMethod(intent, "getExtras");
            String text = String.valueOf(extras);
            diag("Intent 里没 did，extras=" + (text.length() > 400 ? text.substring(0, 400) : text));
        } catch (Throwable t) {
            Log.w("从 Activity 取 did 失败: " + HookUtil.shortMsg(t));
        }
        return null;
    }

    /** 认定设备的那几条路都断了才报，一次进程只报一次。*/
    private static volatile boolean sDiagLogged;

    private static void diag(String msg) {
        if (sDiagLogged) return;
        sDiagLogged = true;
        Log.w("RN spec 取 did 兜底诊断: " + msg);
    }

    /** 记下见过的假 did，给后面的调用兜底；顺手把扣着的 spec 回调放掉。*/
    static void remember(String did) {
        if (did == null) return;
        if (Consts.isSimulatedDid(did)) sLastSimDid = did;
        flushPending(Consts.isSimulatedDid(did) ? did : null);
    }

    /* ---------- 扣住 spec 回调，等认出设备再回 ---------- */

    private static final Object CB_LOCK = new Object();
    private static Object sPendingCb;

    private static void holdCallback(XC_MethodHook.MethodHookParam param) {
        synchronized (CB_LOCK) {
            sPendingCb = param.args[1];
        }
        param.setResult(null);
        Log.d("RN spec 还没认出设备，先扣住回调");
    }

    /** did 为 null 表示认出来的也是真设备：按原逻辑回失败，行为不变。*/
    private static void flushPending(String did) {
        Object cb;
        synchronized (CB_LOCK) {
            cb = sPendingCb;
            sPendingCb = null;
        }
        if (cb == null) return;
        String json = did == null ? null : specJsonOf(did);
        if (json != null) {
            if (invokeCallback(cb, Boolean.TRUE, json)) {
                Log.d("详情页拿 spec(RN·补发): " + json.length() + " 字节");
                return;
            }
        }
        invokeCallback(cb, Boolean.FALSE, "not find device");
    }

    /** did → 本地 spec 的 miot-spec JSON；找不到返回 null。*/
    private static String specJsonOf(String did) {
        SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
        if (dev == null) {
            Log.w("RN 拿 spec 失败：did " + did + " 不在启用列表");
            return null;
        }
        MiotSpec spec = DeviceListHook.specOf(dev);
        if (spec == null) {
            Log.w("RN 拿 spec 失败：型号没 spec " + dev.model);
            return null;
        }
        return spec.raw().toString();
    }

    /** DeviceStat 里 did 的取法有三条：getter → 字段 → toString 文本，哪条通算哪条。*/
    static String didOf(Object stat) {
        if (stat == null) return null;
        try {
            Object d = XposedHelpers.callMethod(stat, "getDid");
            if (d instanceof String && !((String) d).isEmpty()) return (String) d;
        } catch (Throwable ignored) {
            // 没这个 getter 很正常
        }
        for (String name : new String[]{"did", "mDid"}) {
            try {
                Object d = XposedHelpers.getObjectField(stat, name);
                if (d instanceof String && !((String) d).isEmpty()) return (String) d;
            } catch (Throwable ignored) {
                // 字段被混淆/改名，换下一个
            }
        }
        String text = String.valueOf(stat);
        int at = text.indexOf("did:");
        if (at >= 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = at + 4; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch < '0' || ch > '9') break;
                sb.append(ch);
            }
            if (sb.length() > 0) return sb.toString();
        }
        return null;
    }

    /** 认不出当前设备只报一次，别把日志刷爆。*/
    private static volatile boolean sMissLogged;

    private static void logMissOnce(Object stat) {
        if (sMissLogged) return;
        sMissLogged = true;
        Log.w("RN spec 认不出当前设备: stat=" + (stat == null ? "null" : stat.getClass().getName()));
    }

    /** 按 RN 回调约定 {@code invoke(Object[])} 回两个值；回不去就返回 false 让原方法继续跑。*/
    private static boolean invokeCallback(Object callback, Object... values) {
        if (callback == null) return false;
        try {
            Method m = callback.getClass().getMethod("invoke", Object[].class);
            m.setAccessible(true);
            m.invoke(callback, new Object[]{values});
            return true;
        } catch (Throwable t) {
            Log.v("回 RN 回调失败: " + HookUtil.shortMsg(t));
            return false;
        }
    }
}
