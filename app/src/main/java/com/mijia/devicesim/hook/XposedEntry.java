/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-27 09:07:46 +08:00
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

            // 侦察兵最先上线：后面所有「类可能还没加载」的挂点都由它等着，
            // 页面类进内存时自动交给狙击手补挂（见 HookScout / HookSniper）
            HookScout.install(lpparam);
            HookRegistry.install(lpparam);
            DeviceListHook.install(lpparam);
            // 新版首页房间列表的出口兜底：HomeManager 的 (Room)->List 返回后按房间补假设备
            // （新版首页不再读 Home#getDids，而是按 did 解析 Device 后过滤，见 HomeRoomDeviceHook 注释）
            HomeRoomDeviceHook.install(lpparam);
            // 「常用 / 全部」两个聚合 tab 的设备仓库出口：ixn.(String)->List 返回后补模拟设备
            // （默认 tab 走 OooO0o 频次列表 + getDeviceByDid 二次解析，假 did 必被丢，见 NormalRoomDeviceHook 注释）
            NormalRoomDeviceHook.install(lpparam);
            // 「全屋」tab（默认首页）真正的设备来源：homeroom 频次设备仓库 (String)->List 出口，
            // 假 did 不在频次表里永远解析不到，这条不补首页就一直是空的（见 FreqDeviceHook 注释）
            FreqDeviceHook.install(lpparam);
            // 自动化触发条件：假设备没有云端场景配置会被过滤掉，这里给它们补一份；
            // 选设备页还会用 _m_j.dxe#OooOO0(did) 二次解析，同一条路直接放行（见 SceneConfigHook 注释）
            SceneConfigHook.install(lpparam);
            // 11.10.623 真正的保存出口（t5n.OooOooo / x0p.OooO0OO）含模拟设备就地判成功；
            // v93/v111 的 dpd/o29 死点位已删（见 SceneCreateLocalHook 注释）
            SceneCreateLocalHook.install(lpparam);
            // 保存成功但「智能」tab 列表不见：623 列表行是 qjq，解析完按本地场景表补行
            SceneListLocalHook.install(lpparam);
            // 选设备页的数据源遍历的是 Room#getDids()/Home#getDids()，假 did 不在名单里就遍历不到；
            // 按房间名把启用中的模拟设备补进名单（见 RoomDidsHook 注释）
            RoomDidsHook.install(lpparam);
            // 选完设备之后那一页「可触发能力列表」：本地 TCA 库里没有假型号，靠这里补数据（见 SceneTcaDataHook）
            SceneTcaDataHook.install(lpparam);
            // 11.10.623 起能力页改走 specscene 新仓库，旧挂点全失效；这层把假 did 的查询改成
            // 「必走远端 + 借同品类型号」，页面才列得出可触发能力（见 SceneTcaSeedHook 注释）
            SceneTcaSeedHook.install(lpparam);
            // 编辑页「不在当前家庭/部分功能已失效/创建点不了」：623 把家庭判定搬进
            // lhr$OooO0O0 的四个谓词，这里对启用中的模拟 did 一律放行（见 SceneDeviceStateHook）
            SceneDeviceStateHook.install(lpparam);
            // 选设备页总出口：623 的 SpecSceneDeviceListViewModel 按「本地 TCA 库有没有可触发能力」
            // 过滤，模拟 did 永远过不了；这里补名单 + 并入 TCA 结果集（见 ScenePickerListHook）
            ScenePickerListHook.install(lpparam);
            // 同一症状的校验码层兜底：a38 单条校验码 + who 汇总校验码对模拟设备清零，
            // 黄条和「创建」闸门都吃这组码（见 SceneValidationHook）
            SceneValidationHook.install(lpparam);
            // 卡片页「更多操作」：恢复米家原插件链路，只打点不拦截（见 CardMoreActionHook）
            CardMoreActionHook.install(lpparam);
            SpecPanelHook.install(lpparam);
            // 详情页真正常走的那条：插件 Host API（getPropertyValues / setPropertyValue / doAction /
            // getDeviceProp）。不挂这层，页面读不到假设备属性就弹「数据获取异常」、控件全灰、开关点了没反应
            HostApiPanelHook.install(lpparam);
            // RN 插件页（PluginRNActivity）的观测层：桥上每一句 JS→native 调用打一行，
            // 用来定位详情页到底从哪个 NativeModule 读属性（见 RnBridgeProbe 注释）
            RnBridgeProbe.install(lpparam);
            // RN 详情页真正的数据闸口：MIOTSpec.getSpecString / getProperty / setProperty。
            // 页面打开时 did 传的是 null，拿不到 spec 就永远「加载中」、控件全灰（见 RnSpecHook 注释）
            RnSpecHook.install(lpparam);
            // spec 下发之后插件还要查一次云端隐私授权（/device/batchdevicedatas），
            // 假设备云端不存在 → code -1018，页面卡在「加载中」；这条由本地直接回「已授权」（见 RnCloudStub）
            RnCloudStub.install(lpparam);
            // 新版 RN 插件不经 Service 模块，走 JSI 直调 callMethodFromCloud：同一条本地状态机回答
            // get_properties / set_properties / action（见 RnDeviceCallStub）
            RnDeviceCallStub.install(lpparam);
            // 走蓝牙网关的假设备（子擎存在传感器 / 人体传感器 2 这类 BLE  accessory）：
            // 插件页会问「网关连上没」，云端答案永远是没连 → 顶部红条「未连接蓝牙网关」；
            // 这层只对启用的假 mac/did 回「已连接」（见 BleGatewayStub 注释）
            BleGatewayStub.install(lpparam);
            // 「更多操作」的标准插件候选兜底：本机元配置白名单里有型号，但服务端 category 映射没下发时补上候选
            StdPluginRouteHook.install(lpparam);
            CloudSyncGuard.install(lpparam);
            AddFlowHook.install(lpparam);
            // 「设备已重置 → 扫描中」这一页的原生卡片注入 + 点击短路（重写版，见 ScanPageHook 注释）
            ScanPageHook.install(lpparam);
            // 蓝牙型号的「扫描设备」页（ScanChooseBluetoothDeviceV2），另一套字段、同一个思路
            BleScanPageHook.install(lpparam);

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
