/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 19:44:33 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceStateStore;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.ModuleContext;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口（{@code assets/xposed_init} 里写的就是这个类）。
 *
 * <p>大白话：LSPosed 往米家每个进程里塞模块时都会调 {@link #handleLoadPackage}，
 * 我们在这里按进程分工，把四类钩子挂上：
 * <ol>
 *   <li>{@link SimContextHolder}：先弄到一个 Context，后面读配置全靠它；</li>
 *   <li>{@link HookRegistry} + {@link DeviceListHook}：把假设备塞进设备列表和 Home 的 did 名单；</li>
 *   <li>{@link SpecPanelHook}：面板点开关不联网，走本地状态机；</li>
 *   <li>{@link CloudSyncGuard}：云端下发列表时别把假设备冲掉。</li>
 * </ol>
 *
 * <p>另外本模块自己也在工作区作用域里（{@code xposed_scope} 第二项），所以界面进程会走到
 * {@link #hookSelf()} —— 用「自挂探针」证明框架确实在给本模块下钩子，界面上的「已生效」不是猜的。
 */
public class XposedEntry implements IXposedHookLoadPackage {

    private static final String TAG = Consts.LOG_TAG;
    private static volatile boolean sTargetInstalled;
    private static volatile boolean sBooted;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) return;

        if (Consts.MODULE_PACKAGE.equals(lpparam.packageName)) {
            hookSelf();
            return;
        }
        if (!Consts.TARGET_PACKAGE.equals(lpparam.packageName)) return;
        if (sTargetInstalled) return;
        sTargetInstalled = true;

        String process = lpparam.processName == null ? "?" : lpparam.processName;
        HookReport.setProcess(process);
        XposedBridge.log(TAG + " 载入，进程=" + process);
        try {
            SimContextHolder.install(lpparam);
            Context ctx = SimContextHolder.get();
            if (ctx != null) bootstrap(ctx);

            HookRegistry.install(lpparam);
            DeviceListHook.install(lpparam);
            SpecPanelHook.install(lpparam);
            CloudSyncGuard.install(lpparam);
            AddFlowHook.install(lpparam);
            // 「设备已重置 → 扫描中」这一页的原生卡片注入 + 点击短路（重写版，见 ScanPageHook 注释）
            ScanPageHook.install(lpparam);

            HookReport.ok("entry", process, "钩子安装流程走完，共 " + HookUtil.installedCount() + " 个挂点");
            publishSoon();
        } catch (Throwable t) {
            // 任何一步炸了都不能把米家带崩
            HookUtil.hookFailed("entry", process, t);
            XposedBridge.log(TAG + " 安装钩子异常: " + t);
        }
    }

    /**
     * Context 一到就做的初始化：读配置、观察变更、加载状态机。
     * 由 {@link SimContextHolder#attach} 回调过来，所以只跑一次。
     */
    static void bootstrap(Context ctx) {
        if (ctx == null || sBooted) return;
        sBooted = true;
        try {
            ModuleContext.of(ctx);
            ConfigRepository.init(ctx);
            DeviceStateStore.init(ctx);
            HookReport.ok("bootstrap", ctx.getClass().getName(),
                    "配置设备数=" + ConfigRepository.current().size());
            publishSoon();
        } catch (Throwable t) {
            sBooted = false;
            HookUtil.hookFailed("bootstrap", "ConfigRepository/DeviceStateStore", t);
        }
    }

    /** 报告落 Provider：界面「钩子命中」页读这个；Provider 不通时留根目录兜底。*/
    static void publishSoon() {
        try {
            Context ctx = SimContextHolder.get();
            if (ctx == null) return;
            HookReport.publish(ctx);
        } catch (Throwable t) {
            Log.v("报告发布失败: " + HookUtil.shortMsg(t));
        }
    }

    /* ---------- 自挂激活检测 ---------- */

    /**
     * 探针在 {@link ActivationProbe} 里，挂上以后界面就能读到真实激活状态。
     * 单独一个类是因为界面进程不能加载带 Xposed 签名的类（stubs 是 compileOnly，运行时不存在）。
     */
    private static void hookSelf() {
        try {
            XposedHelpers.findAndHookMethod(ActivationProbe.class, "probe", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
        } catch (Throwable ignored) {
            // 模块不在自己作用域里，探针会如实返回 false
        }
    }

    /** 保留旧调用点，界面推荐直接用 {@link ActivationProbe#isActive()}。*/
    public static boolean isModuleActiveInThisProcess() {
        return ActivationProbe.isActive();
    }
}
