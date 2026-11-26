/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 16:02:54 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化选设备页：把启用中的模拟设备真正塞进列表（v148）。
 *
 * <p>623 的选设备页是 {@code EditControllableDeviceList} + {@code SpecSceneDeviceListViewModel}。
 * 数据流（反编译 11.10.623 classes21 确认）：
 * <ol>
 *   <li>{@code SpecSceneDeviceListViewModel#OooOOO()} 遍历 {@code Room#getDids()} 和
 *       {@code Home#getDids()}，逐个调 {@code lhr$OooO0O0#o00Ooo(did)} 解析成 Device，
 *       解析不到就 {@code LogType.SCENE "SceneDeviceList" "findDeviceById null: did=…"} 丢掉。
 *       模拟 did 由 {@link RoomDidsHook} 补进名单、由 {@link SceneDeviceStateHook} 解析出假
 *       Device，所以这一步日志能打出来 —— 但列表里还是看不到，问题在下一道闸。</li>
 *   <li>{@code SpecSceneConfigRepository#Ooooo00(pairs, mode, tcaMode, cont)}（Kotlin 协程
 *       {@code batchCheckTCA}）拿「did+model」去本地 TCA 库
 *       {@code LocalDataResource#OooOoo0(did)} 查「有没有可触发能力」，过期的按型号去云端刷，
 *       最后 {@code queryTCASupportFromDb} 返回一个 <b>Set&lt;did&gt;</b>。</li>
 *   <li>VM 建房间分组时只保留 {@code set.contains(did)} 的设备 —— 模拟 did 不在本地 TCA 库里，
 *       返回的 Set 里没有它，于是被静默过滤，页面只剩真设备。</li>
 * </ol>
 *
 * <p>所以这里补两刀：
 * <ul>
 *   <li>{@code OooOOO()} 返回后，把缺的模拟设备补成该页自己的 {@code DeviceInfo}
 *       （did / model / name / icon 四个字段，构造参数一模一样），保证走到分组那一步时
 *       「名单里找得到」；</li>
 *   <li>{@code batchCheckTCA} 的状态机 {@code SpecSceneConfigRepository$batchCheckTCA$1#invokeSuspend}
 *       返回最终 Set 时，把启用中的模拟 did 直接并进去，保证 {@code set.contains} 放行。
 *       这个挂点选的是协程续体：{@code Ooooo00} 本身会挂起，after 回调只能看到
 *       {@code COROUTINE_SUSPENDED}；续体的 {@code invokeSuspend} 才是真正把 Set 交回去的那一下。</li>
 * </ul>
 *
 * <p>只加启用中的模拟 did，真设备、真过滤逻辑一律不动。
 */
public final class ScenePickerListHook {

    private static final String GROUP = "scene-picker";

    /** 选设备页的 ViewModel（623 实测）。*/
    private static final String CL_LIST_VM = "com.xiaomi.smarthome.specscene.manager.SpecSceneDeviceListViewModel";
    /** {@code () -> List<DeviceInfo>}，页面数据的总出口。*/
    private static final String M_COLLECT = "OooOOO";

    /** 页面自己的小数据类 {@code SpecSceneDeviceListViewModel$OooO00o}(did, model, name, icon)。*/
    private static final String CL_ITEM = CL_LIST_VM + "$OooO00o";
    private static final String F_DID = "OooO00o";
    private static final String F_MODEL = "OooO0O0";
    private static final String F_NAME = "OooO0OO";
    private static final String F_ICON = "OooO0Oo";

    /** {@code batchCheckTCA} 的续体：最终 Set 从这里返回。*/
    private static final String CL_TCA_FLOW =
            "com.xiaomi.smarthome.specscene.manager.SpecSceneConfigRepository$batchCheckTCA$1";
    private static final String M_RESUME = "invokeSuspend";

    private static final AtomicInteger sCollectHits = new AtomicInteger();
    private static final AtomicInteger sTcaHits = new AtomicInteger();

    private static ClassLoader sLoader;

    private ScenePickerListHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        sLoader = lpparam.classLoader;
        HookSniper.hook(lpparam.classLoader, GROUP, CL_LIST_VM, new String[]{M_COLLECT},
                HookSniper.Shape.noArgs("List"), "[Oo0]{6,9}", AFTER_COLLECT);
        HookSniper.hook(lpparam.classLoader, GROUP, CL_TCA_FLOW, new String[]{M_RESUME},
                HookSniper.Shape.of("Object", "Object"), null, AFTER_TCA_RESUME);
    }

    /* ---------- 1. 选设备名单：缺的模拟设备补进 DeviceInfo 列表 ---------- */

    private static final XC_MethodHook AFTER_COLLECT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object result = param.getResult();
                if (!(result instanceof List)) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                List<SimDevice> sims = cfg.enabledDevices();
                if (sims.isEmpty()) return;

                List list = (List) result;
                Class<?> itemClass = itemClassOf(list);
                if (itemClass == null) return;
                Constructor<?> ctor = itemClass.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class);
                ctor.setAccessible(true);

                Set<String> present = new HashSet<>();
                for (Object o : list) {
                    String did = readString(o, F_DID);
                    if (did != null) present.add(did);
                }

                Context ctx = DeviceListHook.context();
                ArrayList<Object> extra = new ArrayList<>();
                for (SimDevice d : sims) {
                    if (d == null || d.did == null || d.did.isEmpty()) continue;
                    if (present.contains(d.did)) continue;
                    String model = d.model == null ? "" : d.model;
                    String name = d.name == null || d.name.isEmpty() ? model : d.name;
                    extra.add(ctor.newInstance(d.did, model, name, iconOf(ctx, d, cfg)));
                    present.add(d.did);
                }
                if (extra.isEmpty()) return;

                ArrayList<Object> out = new ArrayList<>(list);
                out.addAll(extra);
                param.setResult(out);
                int n = sCollectHits.incrementAndGet();
                if (n <= 5) {
                    Log.d("选设备名单补 " + extra.size() + " 台模拟设备");
                }
            } catch (Throwable t) {
                Log.v("选设备名单补写异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** 从列表元素拿到 {@code DeviceInfo} 的真实类；空列表就按固定内部类名找。*/
    private static Class<?> itemClassOf(List list) {
        for (Object o : list) {
            if (o != null) return o.getClass();
        }
        return HookUtil.findClassQuiet(CL_ITEM, sLoader);
    }

    /** 设备图标地址（拿不到就给 null，行里显示默认图标）。*/
    private static String iconOf(Context ctx, SimDevice d, SimConfig cfg) {
        try {
            Object bean = DeviceBeanFactory.deviceFor(ctx, d,
                    DeviceListHook.specOf(d), DeviceListHook.stamp(cfg));
            if (bean == null) return null;
            Method m = bean.getClass().getMethod("getIcon");
            Object v = m.invoke(bean);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- 2. TCA 闸门：最终 Set 里并入模拟 did ---------- */

    private static final XC_MethodHook AFTER_TCA_RESUME = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object result = param.getResult();
                if (!(result instanceof Set)) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;

                Set<String> out = new LinkedHashSet<>();
                for (Object o : (Set) result) {
                    if (o instanceof String) out.add((String) o);
                }
                int before = out.size();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (d != null && d.did != null && !d.did.isEmpty()) out.add(d.did);
                }
                if (out.size() == before) return;
                param.setResult(out);
                int n = sTcaHits.incrementAndGet();
                if (n <= 5) {
                    Log.d("TCA 过滤放行 " + (out.size() - before) + " 个模拟 did");
                }
            } catch (Throwable t) {
                Log.v("TCA 放行异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 小工具 ---------- */

    private static String readString(Object target, String field) {
        if (target == null) return null;
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(target);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }
}