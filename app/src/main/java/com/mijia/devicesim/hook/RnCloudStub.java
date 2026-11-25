/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 21:28:25 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;

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
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * RN 插件页的「云端应答」层：{@code MIOTService.callSmartHomeAPI(path, params, callback)}。
 *
 * <p>为什么要有这一层（v102/v103 实测）：
 * <ul>
 *   <li>v102 —— 页面把 spec 拿到、骨架渲染完，插件启动时还要查一次隐私授权：
 *       {@code /device/batchdevicedatas [{"did":"...","props":["prop.s_auth_config"]}]}。
 *       假设备云端不存在 → 返回 null → {@code code:-1018} → {@code pluginLegalInformationCheck}
 *       直接失败，整页数据流停住（「加载中」/「—— ℃」/控件全灰）。</li>
 *   <li>v103 —— 隐私这关过了之后（日志变成 {@code ProtocolManager_NoNeedToGetPrivacyChanges}），
 *       页面开始每 3 秒轮询 {@code /miotspec/prop/get}，这条同样打云端，假设备永远没有值，
 *       所以页面继续「加载中」。属性读写必须由本地状态机回答。</li>
 * </ul>
 *
 * <p>应答格式的出处（都不是猜的）：
 * <ul>
 *   <li>隐私：同一个插件 bundle 里的 {@code Service.smarthome.batchGetDeviceDatas} 用法是
 *       {@code t[deviceID]['prop.s_auth_config']}，值里 {@code privacyAuthed:true} 表示已授权；</li>
 *   <li>属性：{@code RnPluginHostApiProviderImpl.Ooooo00} 把 prop/get|set 转给
 *       {@code XmPluginHostApi.doSpecRequest}，回调 {@code _m_j.c2d.onSuccess} 最终
 *       {@code callback.invoke([true, jsonString]}) —— 所以回给 JS 的永远是
 *       {@code (Boolean, String)}，String 里是米家云那套 {@code {"code":0,"result":[...]}}。</li>
 * </ul>
 *
 * <p>真设备、别的 path 一律放行，一个字节都不改。
 */
public final class RnCloudStub {

    private static final String GROUP = "rncloud";
    private static final String CL_SERVICE =
            "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTServiceModuleCore";

    /** 批量读属性（隐私授权状态也走这条）。*/
    private static final String PATH_BATCH_GET = "/device/batchdevicedatas";
    /** 单/多属性读，页面轮询的主通道。*/
    private static final String PATH_PROP_GET = "/miotspec/prop/get";
    /** 属性写（开关、调温都走这条）。*/
    private static final String PATH_PROP_SET = "/miotspec/prop/set";
    /** 动作（模式切换之类）。*/
    private static final String PATH_ACTION = "/miotspec/action";
    /** 设备设置批量读（海拔/定位授权/防冻通知开关都在这）。*/
    private static final String PATH_SETTING_GET = "/v2/device/getsettingv2";
    /** 设备设置写。*/
    private static final String PATH_SETTING_SET = "/device/setsetting";

    private static volatile boolean sInstalled;

    /** 同一条 path + 同一台设备只记一条应答日志，别把日志刷爆。*/
    private static final Set<String> sAnswered = new HashSet<>();

    /** "did|key" -> value：本进程内记住插件写过的设备设置。*/
    private static final java.util.Map<String, String> sSettings =
            new java.util.concurrent.ConcurrentHashMap<>();

    private RnCloudStub() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstalled) return;
        sInstalled = true;

        int ok = HookSniper.hook(lpparam.classLoader, GROUP, CL_SERVICE,
                new String[]{"callSmartHomeAPI"},
                HookSniper.Shape.of(null, "String", "String", "Callback"),
                "callSmartHomeAPI", CALL);
        Log.d("RN 云端应答安装: callSmartHomeAPI=" + ok);
    }

    private static final XC_MethodHook CALL = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 3) return;
                String path = (String) param.args[0];
                String params = (String) param.args[1];
                Object cb = param.args[2];
                if (path == null) return;

                String body;
                if (PATH_BATCH_GET.equals(path)) {
                    String did = firstSimDid(params);
                    if (did == null) return; // 真设备：原样走云端
                    body = batchGetBody(did);
                } else if (PATH_PROP_GET.equals(path)) {
                    body = propGetBody(params);
                } else if (PATH_PROP_SET.equals(path)) {
                    body = propSetBody(params);
                } else if (PATH_ACTION.equals(path)) {
                    body = actionBody(params);
                } else if (PATH_SETTING_GET.equals(path)) {
                    body = settingGetBody(params);
                } else if (PATH_SETTING_SET.equals(path)) {
                    body = settingSetBody(params);
                } else {
                    return;
                }
                if (body == null) return;

                if (!invokeCallback(cb, Boolean.TRUE, body)) {
                    Log.w("RN 云端应答回不去: " + path);
                    return;
                }
                param.setResult(null);
                logOnce(path, body);
            } catch (Throwable t) {
                Log.w("RN callSmartHomeAPI 异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static void logOnce(String path, String body) {
        String key = path + "|" + body.length();
        synchronized (sAnswered) {
            if (!sAnswered.add(key)) return;
        }
        Log.d("RN 本地应答 " + path + " → " + shorten(body));
    }

    /* ---------- /device/batchdevicedatas：隐私授权 ---------- */

    /**
     * {@code {"code":0,"message":"ok","result":{"<did>":{"prop.s_auth_config":"{\"privacyAuthed\":true}"}}}}
     */
    private static String batchGetBody(String did) {
        try {
            JSONObject auth = new JSONObject();
            auth.put("privacyAuthed", true);

            JSONObject props = new JSONObject();
            props.put("prop.s_auth_config", auth.toString());

            JSONObject result = new JSONObject();
            result.put(did, props);
            return envelope(result);
        } catch (Throwable t) {
            Log.w("RN 云端应答拼装失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- /miotspec/prop/get：读属性 ---------- */

    /**
     * 请求形如 {@code {"params":[{"did":"...","siid":2,"piid":1}, ...]}}；
     * 应答按米家云格式逐条回 {@code {"did","siid","piid","code":0,"value","updateTime"}}。
     * 只要有一条是真设备就不接管，整包放行。
     */
    private static String propGetBody(String params) {
        JSONArray req = paramsArray(params);
        if (req == null || req.length() == 0) return null;
        Context ctx = DeviceListHook.context();
        JSONArray result = new JSONArray();
        int answered = 0;
        try {
            for (int i = 0; i < req.length(); i++) {
                JSONObject o = req.optJSONObject(i);
                if (o == null) continue;
                String did = o.optString("did", "");
                if (!Consts.isSimulatedDid(did)) return null; // 混了真设备：不接管
                int siid = o.optInt("siid", -1);
                int piid = o.optInt("piid", -1);
                if (siid < 0 || piid < 0) continue;

                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                MiotSpec spec = dev == null ? null : DeviceListHook.specOf(dev);
                Object value = DeviceStateStore.read(ctx, dev, spec, siid, piid);

                JSONObject item = new JSONObject();
                item.put("did", did);
                item.put("siid", siid);
                item.put("piid", piid);
                item.put("code", 0);
                item.put("value", value == null ? JSONObject.NULL : value);
                item.put("updateTime", System.currentTimeMillis());
                result.put(item);
                answered++;
            }
            if (answered == 0) return null;
            return envelope(result);
        } catch (Throwable t) {
            Log.w("RN prop/get 拼装失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- /miotspec/prop/set：写属性 ---------- */

    private static String propSetBody(String params) {
        JSONArray req = paramsArray(params);
        if (req == null || req.length() == 0) return null;
        Context ctx = DeviceListHook.context();
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < req.length(); i++) {
                JSONObject o = req.optJSONObject(i);
                if (o == null) continue;
                String did = o.optString("did", "");
                if (!Consts.isSimulatedDid(did)) return null;
                int siid = o.optInt("siid", -1);
                int piid = o.optInt("piid", -1);
                if (siid < 0 || piid < 0) continue;
                if (!o.has("value")) continue;

                SimDevice dev = ConfigRepository.current().findEnabledByDid(did);
                MiotSpec spec = dev == null ? null : DeviceListHook.specOf(dev);
                Object applied = DeviceStateStore.write(ctx, dev, spec, siid, piid, o.opt("value"));

                JSONObject item = new JSONObject();
                item.put("did", did);
                item.put("siid", siid);
                item.put("piid", piid);
                item.put("code", 0);
                if (applied != null) item.put("value", applied);
                result.put(item);
                Log.d("RN 详情页写属性: " + did + " " + siid + "." + piid + "=" + applied);
            }
            return envelope(result);
        } catch (Throwable t) {
            Log.w("RN prop/set 拼装失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- /miotspec/action：动作 ---------- */

    /**
     * 动作目前只回「成功」让页面别报错，并把参数记进日志 ——
     * 洗衣机的启动/暂停这类动作要按 spec 的 action 定义落到状态上，等看到真实请求再补。
     *
     * <p>两种包法都要认：热水器用 {@code {"params":[...]}}，洗衣机用
     * {@code {"params":{"did":...,"siid":6,"aiid":1,"in":[]}}}（实测）。
     */
    private static String actionBody(String params) {
        JSONObject obj = objectOf(params);
        if (obj != null) {
            JSONObject one = obj.optJSONObject("params");
            if (one == null) return null;
            if (!Consts.isSimulatedDid(one.optString("did", ""))) return null;
            Log.d("RN 动作请求: " + shorten(params));
            try {
                return envelope(new JSONObject());
            } catch (Throwable t) {
                return null;
            }
        }
        JSONArray req = paramsArray(params);
        if (req == null || req.length() == 0) return null;
        for (int i = 0; i < req.length(); i++) {
            JSONObject o = req.optJSONObject(i);
            if (o == null) continue;
            if (!Consts.isSimulatedDid(o.optString("did", ""))) return null;
        }
        Log.d("RN 动作请求: " + shorten(params));
        try {
            return envelope(new JSONArray());
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- /v2/device/getsettingv2 与 /device/setsetting：设备设置 ---------- */

    /**
     * 插件每次回前台都会读一次设置（{@code Service.smarthome.getDeviceSettingV2}）：
     * <pre>
     * {"did":"...","settings":["push_enable_35813","push_enable_35814",
     *                           "hightMode","latitude","locationAgree","showLocationRequestWindow"]}
     * </pre>
     * 读到的 {@code res.result.settings} 直接决定要不要弹「海拔模式」授权窗：
     * 插件里是 {@code var _requestLocation = (state.showLocationRequestWindow) !== "1"}
     * 以及 {@code locationAgree === "1" && highAltitudeMode != undefined} 才走正常路径。
     * 假设备云端没有这条记录 → 每次都当「没授权过」→ 每次弹窗。
     *
     * <p>默认值取「已同意 + 已检查海拔（普通模式）+ 防冻通知已开」，
     * 也就是真机用户点过一次同意之后的状态；写过的值记在本进程里，语义上和真机一致。
     */
    private static String settingGetBody(String params) {
        JSONObject req = objectOf(params);
        if (req == null) return null;
        String did = req.optString("did", "");
        if (!Consts.isSimulatedDid(did)) return null;

        JSONArray keys = req.optJSONArray("settings");
        JSONObject settings = new JSONObject();
        try {
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.optString(i, "");
                    if (key.isEmpty()) continue;
                    settings.put(key, settingValue(did, key));
                }
            }
            JSONObject result = new JSONObject();
            result.put("settings", settings);
            return envelope(result);
        } catch (Throwable t) {
            Log.w("RN settings 读拼装失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /**
     * 写设置：{@code {"did":"...","settings":{"locationAgree":"1"}}}。
     * 插件对结果只看成功/失败（{@code .catch(function () {})} 之后照常继续），回空 result 就够。
     */
    private static String settingSetBody(String params) {
        JSONObject req = objectOf(params);
        if (req == null) return null;
        String did = req.optString("did", "");
        if (!Consts.isSimulatedDid(did)) return null;
        JSONObject settings = req.optJSONObject("settings");
        if (settings != null) {
            java.util.Iterator<String> it = settings.keys();
            while (it.hasNext()) {
                String key = it.next();
                String value = settings.optString(key, null);
                if (key == null || value == null) continue;
                sSettings.put(did + "|" + key, value);
                Log.d("RN 设备设置写入: " + did + " " + key + "=" + value);
            }
        }
        try {
            return envelope(new JSONObject());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前设置值：本进程写过的优先，否则给「真机用户已点过同意」那套默认值。*/
    private static String settingValue(String did, String key) {
        String live = sSettings.get(did + "|" + key);
        if (live != null) return live;
        if (key.startsWith("push_enable")) return "1";
        if ("locationAgree".equals(key)) return "1";
        if ("showLocationRequestWindow".equals(key)) return "1";
        // hightMode / latitude：ALTITUDE_MODE_CHECKED = "1" = 已检查、普通海拔
        if ("hightMode".equals(key)) return "1";
        if ("latitude".equals(key)) return "1";
        return null;
    }

    /* ---------- 小工具 ---------- */

    private static JSONObject objectOf(String params) {
        if (params == null || params.isEmpty()) return null;
        try {
            String text = params.trim();
            return text.startsWith("{") ? new JSONObject(text) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String envelope(Object result) throws Exception {
        JSONObject body = new JSONObject();
        body.put("code", 0);
        body.put("message", "ok");
        body.put("result", result);
        return body.toString();
    }

    /** 兼容 {@code {"params":[...]}} / {@code [...]} 两种请求包法。*/
    private static JSONArray paramsArray(String params) {
        if (params == null || params.isEmpty()) return null;
        try {
            String text = params.trim();
            if (text.startsWith("[")) return new JSONArray(text);
            JSONObject o = new JSONObject(text);
            JSONArray arr = o.optJSONArray("params");
            return arr;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 请求体里第一个假 did；真设备返回 null。*/
    private static String firstSimDid(String params) {
        JSONArray arr = paramsArray(params);
        if (arr == null) {
            try {
                String did = new JSONObject(params.trim()).optString("did", "");
                return Consts.isSimulatedDid(did) ? did : null;
            } catch (Throwable t) {
                return null;
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String did = o.optString("did", "");
            if (Consts.isSimulatedDid(did)) return did;
        }
        return null;
    }

    private static String shorten(String text) {
        if (text == null) return "null";
        return text.length() <= 220 ? text : text.substring(0, 220) + "…";
    }

    /** 按 RN 回调约定 {@code invoke(Object[])} 回值；回不去返回 false 让原方法继续跑。*/
    private static boolean invokeCallback(Object callback, Object... values) {
        if (callback == null) return false;
        try {
            Method m = callback.getClass().getMethod("invoke", Object[].class);
            m.setAccessible(true);
            m.invoke(callback, new Object[]{values});
            return true;
        } catch (Throwable t) {
            Log.w("RN 云端应答 invoke 失败: " + HookUtil.shortMsg(t));
            return false;
        }
    }
}
