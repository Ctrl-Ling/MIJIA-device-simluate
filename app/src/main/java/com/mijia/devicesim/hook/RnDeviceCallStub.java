/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 15:28:39 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceStateStore;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RN 插件页的 JSI 应答层：{@code MIOTDeviceModuleCore.callMethodFromCloud / callMethodFromLocal}。
 *
 * <p>和 {@link RnCloudStub}（{@code MIOTServiceModuleCore.callSmartHomeAPI}）不是同一条路：
 * 新一些的 RN 插件（热水器 17 / 冰箱标准插件等）不经过 Service 模块，而是 JS 直接调
 * {@code MIOTDeviceModule.callMethodFromCloud(did, method, params, extra, callback)}，
 * 由 {@code IRnPluginHostApiProvider} 转云端。实测热水器详情页每 5 秒调一次
 * <pre>
 * callMethodFromCloud("9176617352", "get_properties",
 *   [{"did":"9176617352","siid":2,"piid":6}, …], {}, callback)
 * </pre>
 * 云端没有假设备 → 回调永远不来 → 页面一直「加载中」、控件整片灰。
 *
 * <p>这里对模拟 did 用本地状态机直接应答（格式与云一致），真设备、非属性/动作类方法一律放行。
 *
 * <p>回调形状必须照抄米家自己的实现（11.10.623 反编译坐实）：
 * {@code RnPluginHostApiProviderImpl$o0000O00.OooO00o(String)} 先 {@code Arguments.createMap()}，
 * 再把云返回的 JSON 灌进 {@code WritableMap}，最后
 * {@code callback.invoke(Boolean.TRUE, WritableMap)}。之前这里回的是 JSON 字符串，
 * JS 拿到 {@code data.code} 是 undefined → 判失败 → 继续 5 秒轮询，页面永远转圈。
 */
public final class RnDeviceCallStub {

    private static final String GROUP = "rn-device-call";
    private static final String CL_DEVICE =
            "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTDeviceModuleCore";

    private static volatile boolean sInstalled;

    /** "did|method" -> 已记过日志，避免轮询刷屏。*/
    private static final Set<String> sLogged = new HashSet<>();

    private RnDeviceCallStub() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstalled) return;
        sInstalled = true;
        int ok = HookSniper.hook(lpparam.classLoader, GROUP, CL_DEVICE,
                new String[]{"callMethodFromCloud", "callMethodFromLocal"},
                HookSniper.Shape.of(null, "String", "String", "String", "String", "Callback"),
                "callMethodFrom(Cloud|Local)", CALL);
        Log.d("RN 设备方法应答安装: " + ok);
    }

    private static final XC_MethodHook CALL = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 5) return;
                String did = (String) param.args[0];
                String method = (String) param.args[1];
                String params = (String) param.args[2];
                Object cb = param.args[4];
                if (!Consts.isSimulatedDid(did) || method == null) return;

                String lower = method.toLowerCase();
                JSONObject body = null;
                if (lower.contains("get_properties") || lower.equals("getproperties")) {
                    body = propsBody(did, params);
                } else if (lower.contains("set_properties") || lower.equals("setproperties")) {
                    body = setBody(did, params);
                } else if (lower.contains("action")) {
                    body = actionBody(did, params);
                }
                if (body == null) return;
                Object map = RnMap.toMap(body, cb);
                if (map == null) {
                    // 转成 WritableMap 失败就别拦：让原方法走云端，至少语义不做坏
                    Log.w("RN 设备应答转 WritableMap 失败: " + method);
                    return;
                }
                if (!invokeCallback(cb, Boolean.TRUE, map)) {
                    Log.w("RN 设备应答回不去: " + method);
                    return;
                }
                param.setResult(null);
                logOnce(did, method, body.toString());
            } catch (Throwable t) {
                Log.w("RN callMethodFromCloud 异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static void logOnce(String did, String method, String body) {
        String key = did + "|" + method;
        synchronized (sLogged) {
            if (!sLogged.add(key)) return;
        }
        Log.d("RN 设备本地应答 " + method + " did=" + did + " → " + shorten(body));
    }

    /* ---------- 读属性 ---------- */

    private static JSONObject propsBody(String did, String params) {
        JSONArray req = paramsArray(params);
        if (req == null) return null;
        Context ctx = DeviceListHook.context();
        SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
        MiotSpec spec = dev == null ? null : DeviceListHook.specOf(dev);
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < req.length(); i++) {
                JSONObject o = req.optJSONObject(i);
                if (o == null) continue;
                int siid = o.optInt("siid", -1);
                int piid = o.optInt("piid", -1);
                if (siid < 0 || piid < 0) continue;
                Object value = DeviceStateStore.read(ctx, dev, spec, siid, piid);
                if (value == null) {
                    // 型号没有本地 spec（比如 viomi.waterheater.u34）时不知道属性类型，
                    // 但 RN 插件拿到一堆 null 会一直判「没有数据」并 5 秒轮询一次，页面永远「加载中」。
                    // 模拟设备的属性都是数字型（开关 0/1、温度 0、枚举 0），先回 0 让页面有数据可渲染；
                    // 有 spec 的型号仍然走上面的默认值，不受影响。
                    value = 0;
                }
                JSONObject item = new JSONObject();
                item.put("did", did);
                item.put("siid", siid);
                item.put("piid", piid);
                item.put("code", 0);
                item.put("value", value == null ? JSONObject.NULL : value);
                item.put("updateTime", System.currentTimeMillis());
                result.put(item);
            }
            if (result.length() == 0) return null;
            return envelope(result);
        } catch (Throwable t) {
            Log.w("RN 设备读属性拼装失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- 写属性 ---------- */

    private static JSONObject setBody(String did, String params) {
        JSONArray req = paramsArray(params);
        if (req == null) return null;
        Context ctx = DeviceListHook.context();
        SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
        MiotSpec spec = dev == null ? null : DeviceListHook.specOf(dev);
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < req.length(); i++) {
                JSONObject o = req.optJSONObject(i);
                if (o == null || !o.has("value")) continue;
                int siid = o.optInt("siid", -1);
                int piid = o.optInt("piid", -1);
                if (siid < 0 || piid < 0) continue;
                Object applied = DeviceStateStore.write(ctx, dev, spec, siid, piid, o.opt("value"));
                JSONObject item = new JSONObject();
                item.put("did", did);
                item.put("siid", siid);
                item.put("piid", piid);
                item.put("code", 0);
                if (applied != null) item.put("value", applied);
                result.put(item);
                Log.d("RN 设备写属性: " + did + " " + siid + "." + piid + "=" + applied);
            }
            if (result.length() == 0) return null;
            return envelope(result);
        } catch (Throwable t) {
            Log.w("RN 设备写属性拼装失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- 动作：先回成功，让页面别报错 ---------- */

    private static JSONObject actionBody(String did, String params) {
        Log.d("RN 设备动作请求: " + did + " " + shorten(params));
        try {
            return envelope(new JSONArray());
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- 小工具 ---------- */

    private static JSONObject envelope(Object result) throws Exception {
        JSONObject body = new JSONObject();
        body.put("code", 0);
        body.put("message", "ok");
        body.put("result", result);
        return body;
    }

    /** 兼容 {@code [...]} 与 {@code {"params":[...]}} 两种包法。*/
    private static JSONArray paramsArray(String params) {
        if (params == null || params.isEmpty()) return null;
        try {
            String text = params.trim();
            if (text.startsWith("[")) return new JSONArray(text);
            return new JSONObject(text).optJSONArray("params");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shorten(String text) {
        if (text == null) return "null";
        return text.length() <= 220 ? text : text.substring(0, 220) + "…";
    }

    /** RN 回调约定 {@code invoke(Object[])}；回不去返回 false。*/
    private static boolean invokeCallback(Object callback, Object... values) {
        if (callback == null) return false;
        try {
            Method m = callback.getClass().getMethod("invoke", Object[].class);
            m.setAccessible(true);
            m.invoke(callback, new Object[]{values});
            return true;
        } catch (Throwable t) {
            Log.w("RN 设备应答 invoke 失败: " + HookUtil.shortMsg(t));
            return false;
        }
    }

    /**
     * org.json → RN {@code WritableMap} 的转换。
     *
     * <p>为什么不用模块里直接 import：本工程不带 react-native 的编译依赖（compileOnly 也没配），
     * 只能按类名反射。类名是 RN 自己的稳定 API，方法名也稳定；米家那套混淆的
     * {@code MIOTUtils.OooOOo0} 反而每个版本都可能变，不碰。
     */
    private static final class RnMap {

        private static volatile Object sLoadedFor;
        private static volatile Rn sRn;

        static Object toMap(JSONObject o, Object cb) {
            try {
                Rn rn = rn(cb);
                if (rn == null || o == null) return null;
                return mapOf(o, rn);
            } catch (Throwable t) {
                Log.w("RN JSON→WritableMap 失败: " + HookUtil.shortMsg(t));
                return null;
            }
        }

        private static Rn rn(Object cb) {
            ClassLoader cl = cb.getClass().getClassLoader();
            if (cl == null) cl = RnMap.class.getClassLoader();
            if (sRn != null && sLoadedFor == cl) return sRn;
            synchronized (RnMap.class) {
                if (sRn != null && sLoadedFor == cl) return sRn;
                try {
                    sRn = new Rn(cl);
                    sLoadedFor = cl;
                } catch (Throwable t) {
                    Log.w("RN 反射桥接失败: " + HookUtil.shortMsg(t));
                    sRn = null;
                }
                return sRn;
            }
        }

        private static Object mapOf(JSONObject o, Rn rn) throws Exception {
            Object map = rn.createMap.invoke(null);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if (v == null || v == JSONObject.NULL) {
                    rn.putNull.invoke(map, k);
                } else if (v instanceof Boolean) {
                    rn.putBoolean.invoke(map, k, v);
                } else if (v instanceof Integer) {
                    rn.putInt.invoke(map, k, v);
                } else if (v instanceof Number) {
                    rn.putDouble.invoke(map, k, ((Number) v).doubleValue());
                } else if (v instanceof JSONObject) {
                    rn.putMap.invoke(map, k, mapOf((JSONObject) v, rn));
                } else if (v instanceof JSONArray) {
                    rn.putArray.invoke(map, k, arrayOf((JSONArray) v, rn));
                } else {
                    rn.putString.invoke(map, k, String.valueOf(v));
                }
            }
            return map;
        }

        private static Object arrayOf(JSONArray a, Rn rn) throws Exception {
            Object arr = rn.createArray.invoke(null);
            for (int i = 0; i < a.length(); i++) {
                Object v = a.opt(i);
                if (v == null || v == JSONObject.NULL) {
                    rn.pushNull.invoke(arr);
                } else if (v instanceof Boolean) {
                    rn.pushBoolean.invoke(arr, v);
                } else if (v instanceof Integer) {
                    rn.pushInt.invoke(arr, v);
                } else if (v instanceof Number) {
                    rn.pushDouble.invoke(arr, ((Number) v).doubleValue());
                } else if (v instanceof JSONObject) {
                    rn.pushMap.invoke(arr, mapOf((JSONObject) v, rn));
                } else if (v instanceof JSONArray) {
                    rn.pushArray.invoke(arr, arrayOf((JSONArray) v, rn));
                } else {
                    rn.pushString.invoke(arr, String.valueOf(v));
                }
            }
            return arr;
        }

        private static final class Rn {
            final Method createMap;
            final Method createArray;
            final Method putNull;
            final Method putBoolean;
            final Method putInt;
            final Method putDouble;
            final Method putString;
            final Method putMap;
            final Method putArray;
            final Method pushNull;
            final Method pushBoolean;
            final Method pushInt;
            final Method pushDouble;
            final Method pushString;
            final Method pushMap;
            final Method pushArray;

            Rn(ClassLoader cl) throws Exception {
                Class<?> args = Class.forName("com.facebook.react.bridge.Arguments", true, cl);
                Class<?> wm = Class.forName("com.facebook.react.bridge.WritableMap", false, cl);
                Class<?> wa = Class.forName("com.facebook.react.bridge.WritableArray", false, cl);
                Class<?> rm = Class.forName("com.facebook.react.bridge.ReadableMap", false, cl);
                Class<?> ra = Class.forName("com.facebook.react.bridge.ReadableArray", false, cl);
                createMap = args.getMethod("createMap");
                createArray = args.getMethod("createArray");
                putNull = wm.getMethod("putNull", String.class);
                putBoolean = wm.getMethod("putBoolean", String.class, boolean.class);
                putInt = wm.getMethod("putInt", String.class, int.class);
                putDouble = wm.getMethod("putDouble", String.class, double.class);
                putString = wm.getMethod("putString", String.class, String.class);
                putMap = wm.getMethod("putMap", String.class, rm);
                putArray = wm.getMethod("putArray", String.class, ra);
                pushNull = wa.getMethod("pushNull");
                pushBoolean = wa.getMethod("pushBoolean", boolean.class);
                pushInt = wa.getMethod("pushInt", int.class);
                pushDouble = wa.getMethod("pushDouble", double.class);
                pushString = wa.getMethod("pushString", String.class);
                pushMap = wa.getMethod("pushMap", rm);
                pushArray = wa.getMethod("pushArray", ra);
            }
        }
    }
}