/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
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
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 控制面板可交互（plan Phase 4 的核心）。
 *
 * <p>大白话：米家面板点一下开关，最终都会走到 {@code DeviceController} 这一个类上；
 * 这个类负责「把属性读写请求发到云端/局域网」。我们把这个类的几个入口拦下来，
 * 请求不出门，直接查模块自己的状态机 {@link DeviceStateStore}，然后用它原本的回调
 * {@code Callback.onSuccess(...)} 把结果交回去 —— 面板看到的就是「控制成功、数值变了」。
 *
 * <p>为什么只挂这一层就够：插件面板（JS/RN）里的 {@code XmPluginHostApi.getPropertyValues /
 * setPropertyValue / doAction}，实现类内部都是先 {@code getSpecDeviceController(did)} 再调这几个方法
 * （11.9.705 实测，见 docs/HOOKS.md），所以在这里挂一次，主程 UI 和插件面板一起接住；
 * 两层都挂会让同一次调用回调两次，反而把面板搞乱。
 *
 * <p>类名 {@code com.xiaomi.smarthome.device.api.spec.operation.controller.DeviceController}
 * 是公开 API（给插件作者用的），R8 不会改它 —— 这也是这套钩子能长期稳的原因。
 */
public final class SpecPanelHook {

    private static final String GROUP = "control";
    private static final String CL_CONTROLLER =
            "com.xiaomi.smarthome.device.api.spec.operation.controller.DeviceController";
    private static final String CL_CALLBACK = "com.xiaomi.smarthome.device.api.Callback";
    private static final String CL_PROPERTY_PARAM =
            "com.xiaomi.smarthome.device.api.spec.operation.PropertyParam";
    private static final String CL_ACTION_PARAM =
            "com.xiaomi.smarthome.device.api.spec.operation.ActionParam";
    private static final String CL_DEVICE_STAT = "com.xiaomi.smarthome.device.api.DeviceStat";

    private static volatile ClassLoader sLoader;

    private SpecPanelHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        sLoader = lpparam.classLoader;
        Class<?> controller = HookUtil.findClassQuiet(CL_CONTROLLER, lpparam.classLoader);
        if (controller == null) {
            HookReport.skip(GROUP, CL_CONTROLLER, "类不存在");
            return;
        }
        // 批量读属性：面板打开时那一下
        HookUtil.hookMethod(GROUP, controller, "getSpecProperties", GET_SPEC_PROPS,
                Context.class, List.class, CL_CALLBACK);
        HookUtil.hookMethod(GROUP, controller, "setSpecProperty", SET_SPEC_PROPERTY,
                Context.class, CL_PROPERTY_PARAM, CL_CALLBACK);
        HookUtil.hookMethod(GROUP, controller, "doAction", DO_ACTION,
                Context.class, CL_ACTION_PARAM, CL_CALLBACK);
        HookUtil.hookMethod(GROUP, controller, "getPropertyValue", GET_PROPERTY_VALUE, int.class, int.class);
        HookUtil.hookMethod(GROUP, controller, "subscribeProperty", SUBSCRIBE,
                CL_DEVICE_STAT, List.class, CL_CALLBACK);
        HookReport.ok(GROUP, "DeviceController", "控制层本地状态机已接入");
    }

    /* ---------- 读 ---------- */

    /**
     * {@code getSpecProperties(Context, List<PropertyParam>, Callback)}：
     * 逐个填本地值，成功回调，原方法不再执行（否则真发请求 → 离线失败 → 面板显示「设备离线」）。
     */
    private static final XC_MethodHook GET_SPEC_PROPS = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String did = didOf(param.thisObject);
            if (!Consts.isSimulatedDid(did)) return;
            try {
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                if (dev == null) return;
                MiotSpec spec = DeviceListHook.specOf(dev);
                List<?> params = (List<?>) param.args[1];
                Object callback = param.args[2];
                List<Object> filled = new ArrayList<>();
                long t0 = System.currentTimeMillis();
                if (params != null) {
                    for (Object p : params) {
                        Object v = readOne(dev, spec, p);
                        if (v != MISS) filled.add(v == null ? p : setValueIn(p, v));
                        // 注意：属性值本身可以是 null（未初始化），也要照样回填
                    }
                }
                if (params == null || params.isEmpty()) {
                    filled = snapshotValues(dev, spec);
                }
                succeed(callback, filled);
                param.setResult(null);
                CallLog.record(DeviceListHook.context(), CallLog.OP_GET, did, dev.model,
                        params == null ? "all" : String.valueOf(params.size()),
                        params == null ? 0 : params.size(), filled.size(),
                        System.currentTimeMillis() - t0, "getSpecProperties");
            } catch (Throwable t) {
                // 兜底：放行原方法，让米家自己走一遍（大概率失败，但不会崩）
                Log.e("本地读属性失败，放行原逻辑", t);
            }
        }
    };

    /** {@code getPropertyValue(int siid, int piid)}：同步单属性读，通用面板和卡片都用它。*/
    private static final XC_MethodHook GET_PROPERTY_VALUE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String did = didOf(param.thisObject);
            if (!Consts.isSimulatedDid(did)) return;
            try {
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                if (dev == null) return;
                MiotSpec spec = DeviceListHook.specOf(dev);
                int siid = (Integer) param.args[0];
                int piid = (Integer) param.args[1];
                Object v = DeviceStateStore.read(DeviceListHook.context(), dev, spec, siid, piid);
                param.setResult(v);
                CallLog.record(DeviceListHook.context(), CallLog.OP_GET, did, dev.model,
                        Consts.propKey(siid, piid), 1, 1, 0, "getPropertyValue");
            } catch (Throwable t) {
                Log.e("getPropertyValue 本地读失败", t);
            }
        }
    };

    /* ---------- 写 ---------- */

    /** {@code setSpecProperty(Context, PropertyParam, Callback)}：写本地状态机并落盘。*/
    private static final XC_MethodHook SET_SPEC_PROPERTY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object pp = param.args[1];
            String did = stringOf(pp, "getDid");
            if (!Consts.isSimulatedDid(did)) return;
            try {
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                if (dev == null) return;
                int siid = intOf(pp, "getSiid");
                int piid = intOf(pp, "getPiid");
                Object want = XposedHelpers.callMethod(pp, "getValue");
                MiotSpec spec = DeviceListHook.specOf(dev);
                Object stored = DeviceStateStore.write(DeviceListHook.context(), dev, spec, siid, piid, want);
                succeed(param.args[2], pp);
                param.setResult(null);
                CallLog.record(DeviceListHook.context(), CallLog.OP_SET, did, dev.model,
                        Consts.propKey(siid, piid), 1, 1, 0, "setSpecProperty " + brief(want) + " -> " + brief(stored));
            } catch (Throwable t) {
                Log.e("本地写属性失败，放行原逻辑", t);
            }
        }
    };

    /** {@code doAction(Context, ActionParam, Callback)}：离线时动作只记流水、返回成功。*/
    private static final XC_MethodHook DO_ACTION = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object ap = param.args[1];
            String did = stringOf(ap, "getDid");
            if (!Consts.isSimulatedDid(did)) return;
            try {
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                if (dev == null) return;
                int siid = intOf(ap, "getSiid");
                int aiid = intOf(ap, "getAiid");
                // 动作的副作用（比如 toggle 翻转开关）能推出来的就顺手推，推不出来就只回成功
                applyActionEffect(dev, did, siid, aiid, ap);
                succeed(param.args[2], ap);
                param.setResult(null);
                CallLog.record(DeviceListHook.context(), CallLog.OP_ACTION, did, dev.model,
                        siid + ":" + aiid, 1, 1, 0, "doAction");
            } catch (Throwable t) {
                Log.e("本地动作失败，放行原逻辑", t);
            }
        }
    };

    /** {@code subscribeProperty(DeviceStat, List, Callback)}：订阅直接回一次当前值。*/
    private static final XC_MethodHook SUBSCRIBE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String did = didOf(param.thisObject);
            if (!Consts.isSimulatedDid(did)) return;
            try {
                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                if (dev == null) return;
                MiotSpec spec = DeviceListHook.specOf(dev);
                List<?> params = (List<?>) param.args[1];
                List<Object> out = new ArrayList<>();
                if (params != null) {
                    for (Object p : params) {
                        Object v = readOne(dev, spec, p);
                        if (v != MISS) out.add(v == null ? p : setValueIn(p, v));
                    }
                } else {
                    out = snapshotValues(dev, spec);
                }
                succeed(param.args[2], out);
                param.setResult(null);
                CallLog.record(DeviceListHook.context(), CallLog.OP_SUBSCRIBE, did, dev.model,
                        String.valueOf(out.size()), out.size(), out.size(), 0, "subscribeProperty");
            } catch (Throwable t) {
                Log.e("本地订阅失败，放行原逻辑", t);
            }
        }
    };

    /* ---------- 内部 ---------- */

    private static final Object MISS = new Object();

    /**
     * 一个 PropertyParam（或只带 siid/piid 的过滤器）→ 本地值。
     * 返回 {@link #MISS} 表示这个对象不是属性参数，跳过。
     */
    private static Object readOne(SimDevice dev, MiotSpec spec, Object p) {
        try {
            int siid = intOf(p, "getSiid");
            int piid = intOf(p, "getPiid");
            Context ctx = DeviceListHook.context();
            return DeviceStateStore.read(ctx, dev, spec, siid, piid);
        } catch (Throwable t) {
            return MISS;
        }
    }

    /** 把值写回 PropertyParam（{@code setValue(Object)} 返回自身，这里也照样返回对象本身）。*/
    private static Object setValueIn(Object p, Object value) {
        try {
            XposedHelpers.callMethod(p, "setValue", value);
        } catch (Throwable t) {
            Log.v("回填 PropertyParam 失败: " + HookUtil.shortMsg(t));
        }
        return p;
    }

    /** 没给具体属性列表时：把该设备全部可读属性造成长列表交回去。*/
    private static List<Object> snapshotValues(SimDevice dev, MiotSpec spec) {
        List<Object> out = new ArrayList<>();
        if (dev == null || spec == null || sLoader == null) return out;
        try {
            Class<?> pp = XposedHelpers.findClass(CL_PROPERTY_PARAM, sLoader);
            Context ctx = DeviceListHook.context();
            for (MiotSpec.Prop prop : spec.allProps()) {
                if (!prop.readable()) continue;
                Object obj = XposedHelpers.newInstance(pp, dev.did, prop.siid, prop.iid);
                XposedHelpers.callMethod(obj, "setValue",
                        DeviceStateStore.read(ctx, dev, spec, prop.siid, prop.iid));
                out.add(obj);
            }
        } catch (Throwable t) {
            Log.v("构造 PropertyParam 列表失败: " + HookUtil.shortMsg(t));
        }
        return out;
    }

    /**
     * 动作的可见效果：MIoT 里 {@code toggle}（siid:aiid 常见为 power-service 的 toggle）
     * 语义就是翻转同服务的 on 属性；识别不了就什么都不做，只回成功。
     */
    private static void applyActionEffect(SimDevice dev, String did, int siid, int aiid, Object ap) {
        try {
            MiotSpec spec = DeviceListHook.specOf(dev);
            if (spec == null) return;
            MiotSpec.Action action = spec.findAction(siid, aiid);
            if (action == null) return;
            String type = action.type == null ? "" : action.type;
            Context ctx = DeviceListHook.context();
            if (type.endsWith("toggle") || type.endsWith(":toggle")) {
                // toggle 的定义就是翻转同服务的 power 属性（MIoT 规范里 power 恒为 siid:1）
                MiotSpec.Prop on = spec.findProp(siid, 1);
                if (on != null) {
                    Object cur = DeviceStateStore.read(ctx, dev, spec, on.siid, on.iid);
                    DeviceStateStore.write(ctx, dev, spec, on.siid, on.iid, !truthy(cur));
                }
            }
        } catch (Throwable t) {
            Log.v("动作效果忽略: " + HookUtil.shortMsg(t));
        }
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        return !"0".equals(String.valueOf(v)) && !"false".equalsIgnoreCase(String.valueOf(v));
    }

    private static void succeed(Object callback, Object payload) {
        if (callback == null) return;
        try {
            XposedHelpers.callMethod(callback, "onSuccess", payload);
        } catch (Throwable t) {
            // onSuccess(Object) 有的实现是重载，找不到就试无类型版本
            try {
                Method m = callback.getClass().getMethod("onSuccess", Object.class);
                m.invoke(callback, payload);
            } catch (Throwable ignored) {
                Log.e("回调 onSuccess 失败", t);
            }
        }
    }

    private static String didOf(Object controller) {
        Object v = HookUtil.readFieldQuiet(controller, "did");
        if (v == null) {
            try {
                v = XposedHelpers.callMethod(controller, "getDid");
            } catch (Throwable ignored) {
            }
        }
        return v == null ? null : String.valueOf(v);
    }

    private static String stringOf(Object target, String getter) {
        try {
            Object v = XposedHelpers.callMethod(target, getter);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intOf(Object target, String getter) {
        Object v;
        try {
            v = XposedHelpers.callMethod(target, getter);
        } catch (Throwable t) {
            throw new IllegalArgumentException(getter + " 不可用: " + HookUtil.shortMsg(t));
        }
        if (!(v instanceof Number)) throw new IllegalArgumentException(getter + " 不是数字: " + v);
        return ((Number) v).intValue();
    }

    private static String brief(Object v) {
        if (v == null) return "null";
        String s = String.valueOf(v);
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
