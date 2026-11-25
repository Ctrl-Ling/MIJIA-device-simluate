/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 20:02:02 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化编辑页「不在当前家庭 / 部分功能已失效 / 创建点不了」的根修复（v133）。
 *
 * <p>大白话：v91 当年修这个问题，是把米家那三个「这台设备在不在我家」的静态判定口
 * （{@code _m_j.dxe.OooooOo / OooooOO / o000oOoO}）对启用中的模拟 did 一律返回 false。
 * 11.10.623 把这套判定搬进了 {@code _m_j.lhr$OooO0O0} 的四个实例谓词，老 dxe 口子已经没了，
 * 所以 v132 挂了个空、症状原样回来。v133 照老方子再钉一次，只是换到新门牌：
 *
 * <ul>
 *   <li>{@code o000o0O0(Device)} —— 设备权限/家庭归属，true = 有问题（校验码 -1）；</li>
 *   <li>{@code o000ooo(Device, String homeId)} —— 「不在当前家庭」，true = 不在（校验码 -2）；</li>
 *   <li>{@code o000OoOo(Device)} —— 蓝牙类设备整体不可用，true = 不可用（校验码 -15）；</li>
 *   <li>{@code o00O00(Device)} —— 蓝牙离线 / 信号差，true = 不可用（校验码 -7）。</li>
 * </ul>
 *
 * <p>这四个谓词同时喂给三处 UI：编辑页行的「不在当前家庭」副标题（{@code _m_j.srm}）、
 * 行点击时的 toast + return（{@code _m_j.rcp.o00000oO}）、以及校验链
 * {@code a38$OooO00o#OooO00o → w28 → who.OooO0Oo → SceneValidatorViewModel} 的
 * 黄条和「创建」闸门。所以把它们放行，行文案、点击、黄条、创建一起通。
 *
 * <p>另外保留 v132 的 {@code o00Ooo(did) -> Device} 补查询：编辑页用它把 did 还原成
 * Device 对象，模拟 did 不在米家设备表里，得由我们直接喂 {@link DeviceBeanFactory} 造的实例。
 *
 * <p>只碰「启用中的模拟 did」，真设备原样返回。
 */
public final class SceneDeviceStateHook {

    private static final String GROUP = "scene-device-state";

    /** 场景条目「did → Device」的查询口（混淆类，运行时按名字 + 形状解析）。*/
    private static final String CL_LOOKUP = "_m_j.lhr$OooO0O0";
    private static final String M_LOOKUP = "o00Ooo";

    /** 家庭/可用性四谓词的宿主类（和 o00Ooo 同一个类，623 实测）。*/
    private static final String CL_GUARD = "_m_j.lhr$OooO0O0";

    private static final String M_INVALID = "o000o0O0";
    private static final String M_NOT_IN_HOME = "o000ooo";
    /** 默认参数桥接：内部只转调 {@code o000ooo}，挂上纯属双保险。*/
    private static final String M_NOT_IN_HOME_BRIDGE = "o000oooO";
    private static final String M_BLE_UNAVAILABLE = "o000OoOo";
    private static final String M_BLE_OFFLINE = "o00O00";

    /** 命中次数限流：前若干条打全，之后每 200 条打一条，免得刷爆日志。*/
    private static final AtomicInteger sHits = new AtomicInteger();

    private SceneDeviceStateHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        int lookup = HookSniper.hook(lpparam.classLoader, GROUP, CL_LOOKUP,
                new String[]{M_LOOKUP}, HookSniper.Shape.of("Device", "String"),
                "[oO0]{5,9}", LOOKUP);

        int invalid = HookSniper.hook(lpparam.classLoader, GROUP, CL_GUARD,
                new String[]{M_INVALID}, HookSniper.Shape.of("boolean", "Device"),
                "[oO0]{6,9}", PREDICATE);
        int notHome = HookSniper.hook(lpparam.classLoader, GROUP, CL_GUARD,
                new String[]{M_NOT_IN_HOME}, HookSniper.Shape.of("boolean", "Device", "String"),
                "[oO0]{6,9}", PREDICATE);
        int bridge = HookSniper.hook(lpparam.classLoader, GROUP, CL_GUARD,
                new String[]{M_NOT_IN_HOME_BRIDGE},
                HookSniper.Shape.of("boolean", "Device", "String", "int", "Object"),
                "[oO0]{7,10}", PREDICATE);
        int ble = HookSniper.hook(lpparam.classLoader, GROUP, CL_GUARD,
                new String[]{M_BLE_UNAVAILABLE}, HookSniper.Shape.of("boolean", "Device"),
                "[oO0]{6,9}", PREDICATE);
        int offline = HookSniper.hook(lpparam.classLoader, GROUP, CL_GUARD,
                new String[]{M_BLE_OFFLINE}, HookSniper.Shape.of("boolean", "Device"),
                "[oO0]{5,9}", PREDICATE);

        int guards = invalid + notHome + bridge + ble + offline;
        if (lookup > 0 && guards > 0) {
            HookReport.ok(GROUP, "install",
                    "did查询=" + lookup + "，放行谓词=" + guards
                            + "（无效=" + invalid + " 不在家=" + notHome + " 桥接=" + bridge
                            + " 蓝牙不可用=" + ble + " 蓝牙离线=" + offline + "）");
        } else if (guards > 0) {
            HookReport.ok(GROUP, "install", "放行谓词=" + guards + "，did查询未命中");
        } else {
            HookReport.fail(GROUP, "install",
                    "谓词一个都没命中（类/方法可能又改名，看上面各挂点报告）");
        }
    }

    /* ---------- 1. 设备缓存查询：模拟 did 直接给假 Device ---------- */

    private static final XC_MethodHook LOOKUP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object arg = param.args[0];
                String did = arg instanceof String ? (String) arg : null;
                if (!Consts.isSimulatedDid(did)) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) return;
                Object bean = DeviceBeanFactory.deviceFor(DeviceListHook.context(), dev,
                        DeviceListHook.specOf(dev), DeviceListHook.stamp(cfg));
                if (bean == null) return;
                param.setResult(bean);
                Log.d("场景设备缓存命中模拟 did=" + did + " model=" + dev.model);
            } catch (Throwable t) {
                Log.e("场景设备缓存注入异常", t);
            }
        }
    };

    /* ---------- 2. 家庭/可用性谓词：模拟设备一律放行 ---------- */

    private static final XC_MethodHook PREDICATE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                String did = didOf(param.args[0]);
                if (did == null) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                if (cfg.findEnabledByDid(did) == null) return;
                param.setResult(Boolean.FALSE);
                int n = sHits.incrementAndGet();
                if (n <= 10 || n % 200 == 0) {
                    Log.d("模拟设备放行谓词 #" + n + " " + methodName(param) + " did=" + did);
                }
            } catch (Throwable t) {
                Log.e("场景家庭谓词放行异常", t);
            }
        }
    };

    /** 参数里是不是带 did 的 Device；不是就返回 null。*/
    private static String didOf(Object obj) {
        if (obj == null) return null;
        Object did = HookUtil.readFieldQuiet(obj, "did");
        return did instanceof String ? (String) did : null;
    }

    private static String methodName(XC_MethodHook.MethodHookParam param) {
        Object m = param.method;
        return m instanceof java.lang.reflect.Member
                ? ((java.lang.reflect.Member) m).getName() : "?";
    }
}