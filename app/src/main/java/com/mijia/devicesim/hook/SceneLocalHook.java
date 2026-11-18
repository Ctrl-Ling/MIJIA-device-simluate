/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-18 18:30:23 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化「创建 / 保存」的本地接管（v111）。
 *
 * <p>为什么还要再加一层：v93 的 {@link SceneSaveHook} 把
 * {@code _m_j.dpd#OooO00o(SceneInfo, Callback)} 当唯一入口，现场却证明它压根没被调用 ——
 * 日志里只有 {@code b39.onFailure} 收到云端原始错误码（-400300032 / scene device home_id invalid），
 * 没有任何「场景保存开始」。也就是说这条保存链走的是另一条路，但终点一样：
 * 一定有人把拼好的场景 JSON 交给 {@code _m_j.o29#OooOOOo(Context,int,String,String,int,i50,boolean,boolean)}
 * 串到云端 {@code /appsceneservice/AppSceneService/Edit}。
 *
 * <p>云端的拒绝理由是不可能绕开的：假设备 did 不在云端 home 设备表里，服务端
 * {@code CheckTriggerConditionSrcKeyExtraValueValueTypeDuration} 直接判 home_id invalid。
 * 所以只能在客户端把这一发编辑请求就地「吃掉」：参数里带模拟设备 did 就把请求拦下来，
 * 直接给调用方回一个成功响应（scene_id + activated=1，故意不带 trigger_order，
 * 免得弹出「触发顺序」对话框），后面米家自己的收尾（关 loading、写本地场景库、关页面）照常跑。
 *
 * <p>附带一个收尾保护：编辑页起手 post 了一个 10s 超时 Runnable（{@code _m_j.aid} case=1）。
 * 正常成功/失败都会把它的 AtomicBoolean 置位，超时那趟直接 return；我们绕过回调后没人置位，
 * 10 秒后它会拿着已经 finish 的页面再弹一次「保存失败」。这里在它的 run 入口按
 * 「Activity 已 finish / 已销毁」直接短路 —— 用的是当前页面状态，不是计时器。
 */
public final class SceneLocalHook {

    private static final String GROUP = "scene-local";

    /** 场景编辑请求的唯一出口（静态方法）。*/
    private static final String CL_EDIT_API = "_m_j.o29";
    private static final String M_EDIT = "OooOOOo";
    /** 请求回调基类：成功调 sendSuccessMessage 走它自己的 Handler 派发。*/
    private static final String CL_CALLBACK_BASE = "_m_j.i50";
    /** 编辑页的 10s 超时 Runnable。*/
    private static final String CL_TIMEOUT_RUN = "_m_j.aid";

    /** aid 的 case 选择器；1 = 场景编辑超时。*/
    private static final String F_AID_CASE = "OooOo";
    /** aid 的 WeakReference&lt;Activity&gt;。*/
    private static final String F_AID_ACTIVITY = "OooOoO";

    /** 兜底识别：JSON 里成对引号包着的保留段 did（9 开头共 10 位）。*/
    private static final Pattern SIM_DID_IN_JSON = Pattern.compile("\"(9\\d{9})\"");

    private SceneLocalHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        Class<?> editApi = HookUtil.findClassQuiet(CL_EDIT_API, cl);
        Class<?> cbBase = HookUtil.findClassQuiet(CL_CALLBACK_BASE, cl);
        Class<?> timeout = HookUtil.findClassQuiet(CL_TIMEOUT_RUN, cl);

        boolean edit;
        if (editApi != null && cbBase != null) {
            edit = HookUtil.hookMethod(GROUP, editApi, M_EDIT, BEFORE_EDIT,
                    android.content.Context.class, int.class, String.class, String.class,
                    int.class, cbBase, boolean.class, boolean.class);
        } else {
            edit = editApi != null && HookUtil.hookMethod(GROUP, editApi, M_EDIT, BEFORE_EDIT);
        }

        boolean timeoutHooked = HookUtil.hookMethod(GROUP, timeout, "run", BEFORE_TIMEOUT_RUN);

        if (edit) {
            HookReport.ok(GROUP, "install", "编辑请求接管=true，超时短路=" + timeoutHooked);
        } else {
            HookReport.fail(GROUP, "install", "编辑请求没挂上");
        }
    }

    /* ---------- 1. 编辑 / 创建请求：含假设备就地成功 ---------- */

    private static final XC_MethodHook BEFORE_EDIT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length != 8) return;
                if (!(args[3] instanceof String)) return;
                String sceneJson = (String) args[3];
                String did = matchSimDid(sceneJson);
                if (did == null) return;

                String sceneId = pickSceneId(sceneJson);
                Object callback = args[5];
                Log.d("场景保存本地接管: did=" + did + " scene_id=" + sceneId
                        + " 回调=" + (callback == null ? "null" : callback.getClass().getName()));

                // 请求不再发出去：云端永远会判假设备 home_id invalid
                param.setResult(null);
                deliverSuccess(callback, sceneId);
            } catch (Throwable t) {
                Log.e("场景保存本地接管异常", t);
            }
        }
    };

    /**
     * 合成一份「云端已建好」的最小响应，交给米家自己的成功收尾：
     * {@code _m_j.yu} case 12 → 关 loading、写 scene_id、写本地场景库、finish 编辑页。
     *
     * <p>故意不带 trigger_order：带了就会弹「触发顺序」对话框，而假设备的触发顺序没有意义。
     */
    private static void deliverSuccess(final Object callback, final String sceneId) {
        if (callback == null) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject resp = new JSONObject();
                    resp.put("scene_id", sceneId);
                    resp.put("activated", 1);
                    XposedHelpers.callMethod(callback, "sendSuccessMessage", resp);
                    Log.d("场景保存已本地放行: scene_id=" + sceneId);
                } catch (Throwable t) {
                    Log.e("场景本地成功回调失败", t);
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            new Handler(Looper.getMainLooper()).post(r);
        }
    }

    /* ---------- 2. 超时 Runnable：页面已经没了就别再弹失败 ---------- */

    private static final XC_MethodHook BEFORE_TIMEOUT_RUN = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object self = param.thisObject;
                if (self == null) return;
                Integer which = HookUtil.readIntField(self, F_AID_CASE, -1);
                if (which == null || which != 1) return;
                Object ref = HookUtil.readFieldQuiet(self, F_AID_ACTIVITY);
                if (!(ref instanceof java.lang.ref.Reference)) return;
                Object page = ((java.lang.ref.Reference<?>) ref).get();
                if (!(page instanceof Activity)) {
                    // 页面已经被回收：这趟超时只会在后台弹一句失败，直接免掉
                    param.setResult(null);
                    return;
                }
                Activity a = (Activity) page;
                if (a.isFinishing()) param.setResult(null);
            } catch (Throwable t) {
                Log.v("场景超时短路异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 小工具 ---------- */

    /** 场景 JSON 里出现的第一台模拟设备 did；没有就是真实场景，一律放行。*/
    private static String matchSimDid(String sceneJson) {
        if (sceneJson == null || sceneJson.isEmpty()) return null;
        SimConfig cfg = ConfigRepository.current();
        if (cfg != null) {
            for (SimDevice d : cfg.devices) {
                if (d == null || d.did == null || d.did.isEmpty()) continue;
                if (sceneJson.contains("\"" + d.did + "\"")) return d.did;
            }
        }
        Matcher m = SIM_DID_IN_JSON.matcher(sceneJson);
        if (m.find()) {
            String did = m.group(1);
            if (Consts.isSimulatedDid(did)) return did;
        }
        return null;
    }

    /** 编辑已有场景沿用原 id；新建给一个稳定的本地 id。*/
    private static String pickSceneId(String sceneJson) {
        try {
            JSONObject o = new JSONObject(sceneJson);
            String id = o.optString("scene_id", "");
            if (id.length() > 0 && !"0".equals(id)) return id;
        } catch (Throwable ignored) {
            // JSON 解析不了就按新建处理
        }
        return String.valueOf(System.currentTimeMillis());
    }
}
