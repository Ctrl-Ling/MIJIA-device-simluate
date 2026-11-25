/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 20:38:14 +08:00
 */
package com.mijia.devicesim.hook;

import android.os.Bundle;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 设备卡片「更多操作」：恢复米家原链路，只做观测（v134）。
 *
 * <p>大白话：v132 为了让「点了没反应」变成「有反馈」，把这个点击整个拦下来了
 * （弹「模拟设备暂无插件」+ 跳过原逻辑）。那是错的方向 —— 模拟设备用的是真实型号，
 * 型号仓库里本来就有对应插件，拦掉以后所有设备都进不去插件页了。v134 把拦截拆掉，
 * 恢复原链路：{@code MIUI10CardActivity$OooOO0#onClick} → 隐藏视图、置 isDownloading
 * → {@code _m_j.zuc#OooO0o(Context, Device, Intent, Z, tvp)} 交给插件框架按 model
 * 找/下载插件，回调是匿名类 {@code MIUI10CardActivity$OooOO0$OooO00o}。
 *
 * <p>这里只留两条观测：
 * <ol>
 *   <li>点击时打一行：did / model / 当时 isDownloading 旗标 —— 分清是「压根没进框架」
 *       还是「进了框架但回调不来」；</li>
 *   <li>插件框架回调（成功 {@code OooOOo(Bundle)} / 失败 {@code OooOOo0(cc9)}）各打一行，
 *       失败把 code/msg 原样带出来 —— 这才是「哪个型号下不到插件」的直接证据。</li>
 * </ol>
 *
 * <p>不改任何返回值、不弹任何提示：真设备和模拟设备走完全一样的逻辑。
 */
public final class CardMoreActionHook {

    private static final String GROUP = "card-more-action";

    /**
     * 「更多操作」的点击监听器。它是卡片页的匿名内部类，名字会被混淆，
     * 外层 Activity 名是固定的，所以按「外层类名$混淆名」交给侦察兵在类加载后挂。
     */
    private static final String CL_LISTENER = "com.xiaomi.smarthome.miui10.MIUI10CardActivity$OooOO0";
    private static final String M_CLICK = "onClick";

    /** 插件框架回调（{@code _m_j.zuc#OooO0o} 的最后那个参数）。*/
    private static final String CL_PLUGIN_CB = "com.xiaomi.smarthome.miui10.MIUI10CardActivity$OooOO0$OooO00o";
    private static final String M_CB_SUCCESS = "OooOOo";
    private static final String M_CB_FAIL = "OooOOo0";

    /** 监听器里指回卡片的合成字段。*/
    private static final String F_OUTER = "o0OOo0OO";
    /** 卡片页上当前这台设备。*/
    private static final String F_DEVICE = "mDevice";
    /** 卡片页的「插件下载中」旗标；为 true 时点击会被直接丢掉（看起来就是死键）。*/
    private static final String F_DOWNLOADING = "o0OOoOo";

    private CardMoreActionHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        int n = HookSniper.hook(cl, GROUP, CL_LISTENER,
                new String[]{M_CLICK}, HookSniper.Shape.of(null, "View"),
                "[oO0]{6,9}", CLICK);
        int ok = HookSniper.hook(cl, GROUP, CL_PLUGIN_CB,
                new String[]{M_CB_SUCCESS}, HookSniper.Shape.of(null, "Bundle"),
                "[oO0]{6,9}", CB_SUCCESS);
        int fail = HookSniper.hook(cl, GROUP, CL_PLUGIN_CB,
                new String[]{M_CB_FAIL}, HookSniper.Shape.anyArgs(null),
                "[oO0]{6,9}", CB_FAIL);

        HookReport.ok(GROUP, "install", "更多操作已恢复原链路（只观测）：点击=" + n
                + " 成功回调=" + ok + " 失败回调=" + fail);
    }

    private static final XC_MethodHook CLICK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object listener = param.thisObject;
                if (listener == null) return;
                Object activity = HookUtil.readFieldQuiet(listener, F_OUTER);
                if (activity == null) return;
                Object device = HookUtil.readFieldQuiet(activity, F_DEVICE);
                if (device == null) return;
                String did = (String) HookUtil.readFieldQuiet(device, "did");
                if (!Consts.isSimulatedDid(did)) return;
                SimConfig cfg = ConfigRepository.current();
                SimDevice sim = cfg == null ? null : cfg.findEnabledByDid(did);
                Object downloading = HookUtil.readFieldQuiet(activity, F_DOWNLOADING);
                Log.d("更多操作点击放行: did=" + did + " model=" + (sim == null ? "?" : sim.model)
                        + " 下载中=" + downloading);
            } catch (Throwable t) {
                Log.e("更多操作打点异常", t);
            }
        }
    };

    private static final XC_MethodHook CB_SUCCESS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object a = param.args == null || param.args.length < 1 ? null : param.args[0];
                Bundle b = a instanceof Bundle ? (Bundle) a : null;
                String did = currentCardDid(param.thisObject);
                Log.d("插件回调找到: did=" + did + " extra=" + (b == null ? "null" : b.toString()));
            } catch (Throwable t) {
                Log.v("插件成功回调打点异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static final XC_MethodHook CB_FAIL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object err = param.args == null || param.args.length < 1 ? null : param.args[0];
                String code = "";
                String msg = "";
                if (err != null) {
                    Object c = HookUtil.invokeQuiet(err, err.getClass(), "getCode", new Class<?>[0]);
                    if (c != null) code = String.valueOf(c);
                    Object m = HookUtil.invokeQuiet(err, err.getClass(), "getMessage", new Class<?>[0]);
                    if (m != null) msg = String.valueOf(m);
                }
                Log.d("插件回调失败: did=" + currentCardDid(param.thisObject)
                        + " code=" + code + " msg=" + msg);
            } catch (Throwable t) {
                Log.v("插件失败回调打点异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** 回调里拿到外层监听器 → 卡片页 → 当前设备 did，纯粹给日志用。*/
    private static String currentCardDid(Object cb) {
        if (cb == null) return "?";
        try {
            Object listener = HookUtil.readFieldQuiet(cb, "OooO00o");
            Object activity = listener == null ? null : HookUtil.readFieldQuiet(listener, F_OUTER);
            Object device = activity == null ? null : HookUtil.readFieldQuiet(activity, F_DEVICE);
            Object did = device == null ? null : HookUtil.readFieldQuiet(device, "did");
            return did instanceof String ? (String) did : "?";
        } catch (Throwable ignored) {
            return "?";
        }
    }
}