/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-13 01:53:18 +08:00
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 设备详情页的数据层：插件 Host API（{@code XmPluginHostApi}）这一层。
 *
 * <p>为什么还要多这一层：{@link SpecPanelHook} 挂的是 {@code DeviceController}（面向插件作者的
 * 便利类），它内部再转调 Host API。实测设备详情页并不总是走便利类 —— 有的页面直接拿
 * {@code XmPluginHostApi.instance()} 调 {@code getPropertyValues / setPropertyValue / doAction}，
 * 也有页面先读同步缓存 {@code getDeviceProp(did)}。这些请求对假设备发出去只会失败：
 * 云里没有这台设备 → 页面弹「数据获取异常」、控件整片置灰、开关点了没反应。
 *
 * <p>做法：在真正干活的那个实现类上挂一层。实现类名各版本不同（11.9.705 是
 * {@code com.xiaomi.smarthome.framework.plugin.mpk.PluginHostApiImpl}），所以不写死：
 * 先试着拿 {@code XmPluginHostApi.instance()} 的实例，拿不到就挂 {@code instance()} 本身，
 * 等它一返回真身，立刻把真身的类（和它的父类链）挂上。一次进程只挂一遍。
 *
 * <p>命中假设备时：读 → 直接从 {@link DeviceStateStore} 取值按原回调交回去；
 * 写 → 落本地状态机后回成功；动作 → 推一下可见副作用再回成功。
 * 非假设备一律原样放行，绝不影响真设备。
 */
public final class HostApiPanelHook {

    private static final String GROUP = "hostapi";
    private static final String CL_XPHA = "com.xiaomi.smarthome.device.api.XmPluginHostApi";
    /** 已知实现类（加速路径，找不到就靠 instance() 现场发现）。*/
    private static final String CL_IMPL = "com.xiaomi.smarthome.framework.plugin.mpk.PluginHostApiImpl";

    /** 已经挂过的实现类名（同一个类可能被 instance() 反复返回）。*/
    private static final Set<String> sHooked = new LinkedHashSet<>();
    private static volatile ClassLoader sLoader;

    private HostApiPanelHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        sLoader = lpparam.classLoader;
        Class<?> xpha = HookUtil.findClassQuiet(CL_XPHA, lpparam.classLoader);
        if (xpha == null) {
            HookReport.skip(GROUP, CL_XPHA, "类不存在");
            return;
        }
        // 加速路径：已知实现类直接挂
        Class<?> known = HookUtil.findClassQuiet(CL_IMPL, lpparam.classLoader);
        if (known != null) hookClass(known);

        // 通用路径：拿到单例的真身类。此刻可能还没初始化，所以两条腿都上
        Object api = callInstance(xpha);
        if (api != null) hookClass(api.getClass());
        hookMethodSafe(xpha, "instance", INSTANCE_HOOK);
    }

    /** 可能是静态方法也可能被 R8 换过名字，失败就只记一条。*/
    private static Object callInstance(Class<?> xpha) {
        try {
            Method m = xpha.getDeclaredMethod("instance");
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void hookMethodSafe(Class<?> clazz, String name, XC_MethodHook callback) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != 0) continue;
                if (Modifier.isAbstract(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
                XposedBridge.hookMethod(m, callback);
            }
        } catch (Throwable t) {
            Log.v("挂 " + clazz.getName() + "#" + name + " 失败: " + HookUtil.shortMsg(t));
        }
    }

    /** {@code instance()} 一返回真身就把它的类挂上（真身类是延迟初始化的，通常第一次访问前才有）。*/
    private static final XC_MethodHook INSTANCE_HOOK = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            Object api = param.getResult();
            if (api != null) hookClass(api.getClass());
        }
    };

    /* ---------- 挂实现类 ---------- */

    private static void hookClass(Class<?> impl) {
        if (impl == null) return;
        String name = impl.getName();
        synchronized (sHooked) {
            if (!sHooked.add(name)) return;
        }
        int n = 0;
        n += hookNamed(impl, "getPropertyValues", 3, GET_VALUES);
        n += hookNamed(impl, "setPropertyValue", 3, SET_VALUE);
        n += hookNamed(impl, "doAction", 3, DO_ACTION);
        n += hookNamed(impl, "batchGetDeviceProps", 3, BATCH_GET);
        n += hookNamed(impl, "getDeviceProp", 1, GET_DEVICE_PROP);
        n += hookNamed(impl, "callMethod", 5, CALL_METHOD);
        HookReport.ok(GROUP, name, "属性/动作入口 " + n + " 个");
        Log.d("Host API 层已接入: " + name + "（" + n + " 个挂点）");
    }

    /**
     * 沿父类链挂同名同参数个数的方法，只挂有方法体的（抽象方法挂了也不会被调到）。
     * 停到 {@code XmPluginHostApi} 为止 —— 再往上就是 Object 了。
     */
    private static int hookNamed(Class<?> impl, String name, int paramCount, XC_MethodHook callback) {
        int count = 0;
        Class<?> c = impl;
        while (c != null && c != Object.class) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                break;
            }
            for (Method m : methods) {
                if (!m.getName().equals(name) || m.getParameterCount() != paramCount) continue;
                if (Modifier.isAbstract(m.getModifiers())) continue;
                try {
                    XposedBridge.hookMethod(m, callback);
                    count++;
                } catch (Throwable t) {
                    // 同一个 Method 已经挂过 / 框架拒绝，都不影响别的挂点
                }
            }
            if (c.getName().equals(CL_XPHA)) break;
            c = c.getSuperclass();
        }
        return count;
    }

    /* ---------- 读：批量属性 ---------- */

    /** {@code getPropertyValues(Context, List<PropertyParam>, Callback)} —— 页面打开时的批量读。*/
    private static final XC_MethodHook GET_VALUES = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                List<?> params = param.args[1] instanceof List ? (List<?>) param.args[1] : null;
                String did = didOfParams(params);
                if (!isOurDevice(did)) return;
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                MiotSpec spec = DeviceListHook.specOf(dev);
                Context ctx = DeviceListHook.context();
                List<Object> filled = new ArrayList<>();
                if (params != null) {
                    for (Object p : params) {
                        int siid = intOf(p, "getSiid");
                        int piid = intOf(p, "getPiid");
                        Object v = DeviceStateStore.read(ctx, dev, spec, siid, piid);
                        setValueIn(p, v);
                        filled.add(p);
                    }
                }
                succeed(param.args[2], filled);
                param.setResult(null);
                Log.d("详情页读属性(HostApi): " + dev.model + " " + filled.size() + " 项");
                CallLog.record(ctx, CallLog.OP_GET, did, dev.model,
                        params == null ? "all" : String.valueOf(params.size()),
                        filled.size(), filled.size(), 0, "getPropertyValues");
            } catch (Throwable t) {
                Log.v("HostApi 读属性放行: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** {@code batchGetDeviceProps(String, JSONArray, Callback)} —— 老页面按 did + 属性清单读。*/
    private static final XC_MethodHook BATCH_GET = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = str(param.args[0]);
                if (!isOurDevice(did)) return;
                JSONArray wanted = param.args[1] instanceof JSONArray ? (JSONArray) param.args[1] : null;
                JSONArray arr = propArray(did, wanted);
                succeed(param.args[2], arr);
                param.setResult(null);
                Log.d("详情页批量读属性(HostApi): " + did + " → " + arr.length() + " 项");
            } catch (Throwable t) {
                Log.v("HostApi 批量读放行: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** {@code getDeviceProp(String) → JSONArray} —— 同步读「最近一次属性」，页面首屏就靠它。*/
    private static final XC_MethodHook GET_DEVICE_PROP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = str(param.args[0]);
                if (!isOurDevice(did)) return;
                JSONArray arr = propArray(did, null);
                param.setResult(arr);
                Log.d("详情页同步读属性(HostApi): " + did + " → " + arr.length() + " 项");
            } catch (Throwable t) {
                Log.v("HostApi 同步读放行: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 写 ---------- */

    /** {@code setPropertyValue(Context, PropertyParam, Callback)} —— 开关/滑杆真正落地的地方。*/
    private static final XC_MethodHook SET_VALUE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object pp = param.args[1];
                String did = str(XposedHelpers.callMethod(pp, "getDid"));
                if (!isOurDevice(did)) return;
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                MiotSpec spec = DeviceListHook.specOf(dev);
                Context ctx = DeviceListHook.context();
                int siid = intOf(pp, "getSiid");
                int piid = intOf(pp, "getPiid");
                Object want = XposedHelpers.callMethod(pp, "getValue");
                Object stored = DeviceStateStore.write(ctx, dev, spec, siid, piid, want);
                setValueIn(pp, stored);
                succeed(param.args[2], pp);
                param.setResult(null);
                Log.d("详情页写属性(HostApi): " + dev.model + " " + Consts.propKey(siid, piid)
                        + " " + brief(want) + " → " + brief(stored));
                CallLog.record(ctx, CallLog.OP_SET, did, dev.model,
                        Consts.propKey(siid, piid), 1, 1, 0,
                        "setPropertyValue " + brief(want) + " -> " + brief(stored));
            } catch (Throwable t) {
                Log.v("HostApi 写属性放行: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** {@code doAction(Context, ActionParam, Callback)} —— 按钮类动作（翻转、模式切换）。*/
    private static final XC_MethodHook DO_ACTION = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object ap = param.args[1];
                String did = str(XposedHelpers.callMethod(ap, "getDid"));
                if (!isOurDevice(did)) return;
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                Context ctx = DeviceListHook.context();
                int siid = intOf(ap, "getSiid");
                int aiid = intOf(ap, "getAiid");
                SpecPanelHook.applyActionEffect(dev, did, siid, aiid, ap);
                succeed(param.args[2], ap);
                param.setResult(null);
                Log.d("详情页动作(HostApi): " + dev.model + " " + siid + ":" + aiid);
                CallLog.record(ctx, CallLog.OP_ACTION, did, dev.model,
                        siid + ":" + aiid, 1, 1, 0, "doAction");
            } catch (Throwable t) {
                Log.v("HostApi 动作放行: " + HookUtil.shortMsg(t));
            }
        }
    };

    /**
     * {@code callMethod(did, method, params, Callback, Parser)} —— 老插件的 RPC 口子。
     * 现在只做两件事：把命中的调用记进日志（下次就知道是谁在用这条口子），
     * 顺带把读属性的几条 RPC 用本地值回掉，写 RPC 先放行不动，避免猜错参数格式把页面搞乱。
     */
    private static final XC_MethodHook CALL_METHOD = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = str(param.args[0]);
                if (!isOurDevice(did)) return;
                String method = str(param.args[1]);
                if (method == null) return;
                Log.d("详情页 RPC(HostApi): " + method + " did=" + did);
                if (method.contains("get_prop") || method.equals("get_properties")
                        || method.equals("get_prop_list")) {
                    JSONArray arr = propArray(did, null);
                    succeed(param.args[3], arr);
                    param.setResult(null);
                }
            } catch (Throwable t) {
                Log.v("HostApi RPC 放行: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 数据类型 ---------- */

    /** 按当前状态机造一份 {did,siid,piid,value,code,updateTime} 数组；wanted 非空时按它筛。*/
    private static JSONArray propArray(String did, JSONArray wanted) {
        JSONArray out = new JSONArray();
        SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
        MiotSpec spec = dev == null ? null : DeviceListHook.specOf(dev);
        Context ctx = DeviceListHook.context();
        if (dev == null || spec == null) return out;
        List<Object> matched = new ArrayList<>();
        for (MiotSpec.Prop p : spec.allProps()) {
            if (wanted != null && !wantedHas(wanted, p)) continue;
            matched.add(p);
        }
        // 参数格式和猜的不一样（一条都没对上）时退回整机属性，宁可多给也不要让页面空着
        if (wanted != null && matched.isEmpty()) {
            for (MiotSpec.Prop p : spec.allProps()) matched.add(p);
        }
        for (Object o : matched) {
            MiotSpec.Prop p = (MiotSpec.Prop) o;
            try {
                JSONObject one = new JSONObject();
                one.put("did", did);
                one.put("siid", p.siid);
                one.put("piid", p.iid);
                Object v = DeviceStateStore.read(ctx, dev, spec, p.siid, p.iid);
                one.put("value", v == null ? JSONObject.NULL : v);
                one.put("code", 0);
                one.put("updateTime", System.currentTimeMillis());
                out.put(one);
            } catch (Throwable t) {
                Log.v("属性项构造失败 " + p.key() + ": " + HookUtil.shortMsg(t));
            }
        }
        return out;
    }

    /** wanted 里每一项可能是 {siid,piid}，也可能带 did，按 siid/piid 对上就算命中。*/
    private static boolean wantedHas(JSONArray wanted, MiotSpec.Prop p) {
        for (int i = 0; i < wanted.length(); i++) {
            JSONObject o = wanted.optJSONObject(i);
            if (o == null) continue;
            int siid = o.optInt("siid", -1);
            int piid = o.optInt("piid", o.optInt("iid", -1));
            if (siid == p.siid && piid == p.iid) return true;
        }
        return false;
    }

    /* ---------- 小工具 ---------- */

    private static boolean isOurDevice(String did) {
        if (!Consts.isSimulatedDid(did)) return false;
        try {
            return ConfigRepository.current().findEnabledByDid(did) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String didOfParams(List<?> params) {
        if (params == null) return null;
        for (Object p : params) {
            try {
                String did = str(XposedHelpers.callMethod(p, "getDid"));
                if (did != null && !did.isEmpty()) return did;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object setValueIn(Object p, Object value) {
        try {
            XposedHelpers.callMethod(p, "setValue", value);
        } catch (Throwable t) {
            Log.v("回填参数失败: " + HookUtil.shortMsg(t));
        }
        return p;
    }

    private static int intOf(Object target, String getter) {
        Object v = XposedHelpers.callMethod(target, getter);
        if (!(v instanceof Number)) throw new IllegalArgumentException(getter + " 不是数字: " + v);
        return ((Number) v).intValue();
    }

    private static void succeed(Object callback, Object payload) {
        if (callback == null) return;
        try {
            XposedHelpers.callMethod(callback, "onSuccess", payload);
        } catch (Throwable t) {
            try {
                Method m = callback.getClass().getMethod("onSuccess", Object.class);
                m.invoke(callback, payload);
            } catch (Throwable ignored) {
                Log.e("回调 onSuccess 失败", t);
            }
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String brief(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
