/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 19:29:10 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;
import android.widget.Toast;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 设备卡片「更多操作」：模拟设备点了不能像死键一样没反应。
 *
 * <p>大白话：这个按钮的处理在 {@code MIUI10CardActivity$OooOO0#onClick} 里。它只有两条路：
 * 网关型号先去看子设备页，其余型号走插件框架
 * {@code _m_j.zuc#OooO0o(Context, Device, Intent, boolean, callback)}。
 * 插件框架要按 model 找/下载插件 —— 模拟设备的 model 在云端没有对应插件，
 * 有的型号本机装着插件所以点得开，有的型号发出去就音信全无（连失败回调都不来），
 * 看起来就是「点了没反应」。
 *
 * <p>处理：点到「更多操作」时先认这台设备是不是启用中的模拟设备；是的话直接弹一句
 * 「模拟设备暂无插件」并跳过原逻辑，不再进入插件下载状态机。这样所有模拟设备的行为一致、
 * 都有明确反馈，也不会把页面的 {@code isDownloading} 旗标卡在 true 上。
 *
 * <p>真设备一条都不拦。
 */
public final class CardMoreActionHook {

    private static final String GROUP = "card-more-action";

    /**
     * 「更多操作」的点击监听器。它是卡片页的匿名内部类，名字会被混淆，
     * 外层 Activity 名是固定的，所以按「外层类名$混淆名」交给侦察兵在类加载后挂。
     */
    private static final String CL_LISTENER = "com.xiaomi.smarthome.miui10.MIUI10CardActivity$OooOO0";
    private static final String M_CLICK = "onClick";

    /** 监听器里指回卡片的合成字段。*/
    private static final String F_OUTER = "o0OOo0OO";
    /** 卡片页上当前这台设备。*/
    private static final String F_DEVICE = "mDevice";

    private CardMoreActionHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        int n = HookSniper.hook(lpparam.classLoader, GROUP, CL_LISTENER,
                new String[]{M_CLICK}, HookSniper.Shape.of(null, "View"),
                "[oO0]{6,9}", CLICK);
        if (n > 0) {
            HookReport.ok(GROUP, "install", "更多操作点击接管=" + n);
        } else {
            HookReport.skip(GROUP, "install", "监听器还没加载或已改名，交给侦察兵");
        }
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
                if (cfg == null || !cfg.injectEnabled) return;
                SimDevice sim = cfg.findEnabledByDid(did);
                if (sim == null) return;

                Context ctx = activity instanceof Context ? (Context) activity : DeviceListHook.context();
                if (ctx != null) {
                    Toast.makeText(ctx, "模拟设备暂无插件，更多操作不可用", Toast.LENGTH_SHORT).show();
                }
                param.setResult(null);
                Log.d("更多操作：模拟设备直接反馈 did=" + did + " model=" + sim.model);
            } catch (Throwable t) {
                Log.e("更多操作接管异常", t);
            }
        }
    };
}