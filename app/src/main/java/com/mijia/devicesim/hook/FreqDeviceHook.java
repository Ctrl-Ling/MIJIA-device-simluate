/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 14:59:12 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 「全屋」首页（{@code CommonRoomFragment}）设备卡片的注入点。
 *
 * <p>11.10.623 的默认首页是「全屋」tab：{@code CommonRoomViewModel} → {@code CommonRoomRepository}
 * → {@code getMemoryLatest(Home)}。它的设备列表<b>不</b>经过
 * {@code NormalRoomRepository} 那条 {@code _m_j.ixn}（那条只服务房间 tab 和排序/角标），
 * 而是：
 * <pre>
 * CommonRoomRepository.OooO0oO   ← 频次设备仓库（com.xiaomi.smarthome.homeroom.OooO0o）
 *   .Oooo00o(homeId)            ← getFreqDevice：LiveData 频次表里的 did → getDeviceByDid 逐台解析
 *     → ArrayList 副本（解析不到就丢，假 did 永远不会出现在这里）
 * → 调用方再过滤 did 不在 long-offline 集合且 isOnline 的设备
 * → 打包成 $filterDevices 扔进 getMemoryLatest$4 协程
 * </pre>
 *
 * <p>所以这里挂在频次仓库的出口 {@code Oooo00o(String)->List} 上：after 里追加启用中的
 * 模拟设备（{@link DeviceBeanFactory} 造的 Device bean，在线、可见），按 did 去重后
 * 复制列表再返回，不动米家自己那份活数据。后续协程里的卡片过滤（spec 查表、
 * {@code aag.OooO0o} 造卡片模型）对真型号的假设备是通的；万一某版把它们丢掉，
 * {@link #installCardProbe(ClassLoader)} 会针对假 did 打一行「卡片模型为空」的日志。
 *
 * <p>类名/方法名都是混淆名：优先从 {@code CommonRoomRepository} 的字段类型发现仓库类
 * （字段名 {@code OooO0oO}），字段改名时退回按类名加载。方法只认 {@code Oooo00o}
 * —— 同类里另外几个 {@code (String)->List} 是别的列表来源，挂错会串数据。
 */
public final class FreqDeviceHook {

    private static final String GROUP = "home-freq-list";
    private static final String CL_REPO = "com.xiaomi.smarthome.feature.vm.repository.CommonRoomRepository";
    /** CommonRoomRepository 里持有频次设备仓库的字段（当前未被改名）。*/
    private static final String F_STORE = "OooO0oO";
    /** 兜底：直接按类名找频次设备仓库。*/
    private static final String CL_FREQ = "com.xiaomi.smarthome.homeroom.OooO0o";
    /** 频次设备列表的出口：{@code (String homeId) -> List<Device>}。*/
    private static final String M_FREQ = "Oooo00o";

    /** 卡片模型工厂（混淆名），只用来做诊断，挂不上不影响注入。*/
    private static final String CL_CARD_FACTORY = "_m_j.aag";
    private static final String GROUP_CARD = "home-card-probe";

    private FreqDeviceHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> store = null;
        Class<?> repo = HookUtil.findClassQuiet(CL_REPO, lpparam.classLoader);
        if (repo != null) {
            Field f = HookUtil.findField(repo, F_STORE);
            if (f != null) store = f.getType();
        }
        if (store == null) store = HookUtil.findClassQuiet(CL_FREQ, lpparam.classLoader);
        if (store == null) {
            HookReport.skip(GROUP, CL_FREQ, "频次设备仓库类不存在");
            return;
        }
        String key = store.getName() + "#" + M_FREQ + "(String)->List";
        if (HookUtil.isInstalled(key)) return;

        if (HookUtil.hookMethod(GROUP, store, M_FREQ, APPEND_FREQ, String.class)) {
            Log.d("全屋频次设备出口已挂: " + store.getName() + "#" + M_FREQ + "(String)->List");
        } else {
            HookReport.skip(GROUP, key, "没有 " + M_FREQ + "(String)->List");
        }
        installCardProbe(lpparam.classLoader);
    }

    /**
     * 诊断探针：首页协程给每台设备造 MainPageDeviceModel 时（{@code aag.OooO0o}），
     * 如果假 did 得到 null，说明卡片层把它丢了 —— 只打日志，不改结果。
     */
    private static void installCardProbe(ClassLoader loader) {
        Class<?> factory = HookUtil.findClassQuiet(CL_CARD_FACTORY, loader);
        if (factory == null) return;
        String key = factory.getName() + "#OooO0o(Device,Z,I,Object)";
        if (HookUtil.isInstalled(key)) return;
        HookUtil.hookMethod(GROUP_CARD, factory, "OooO0o", CARD_PROBE,
                loadDevice(loader), boolean.class, int.class, Object.class);
    }

    private static Class<?> loadDevice(ClassLoader loader) {
        Class<?> c = HookUtil.findClassQuiet(DeviceBeanFactory.CL_DEVICE, loader);
        return c == null ? Object.class : c;
    }

    private static final XC_MethodHook CARD_PROBE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object device = param.args[0];
                String did = DeviceListHook.strField(device, "did");
                if (!Consts.isSimulatedDid(did)) return;
                Object model = param.getResult();
                Log.d("全屋卡片模型: did=" + did + (model == null ? " 造卡失败(null，卡片会被丢)" : " 正常"));
            } catch (Throwable t) {
                Log.v("全屋卡片探针跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static final XC_MethodHook APPEND_FREQ = new XC_MethodHook() {
        @Override
        @SuppressWarnings("unchecked")
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object homeObj = param.args == null || param.args.length == 0 ? null : param.args[0];
                String homeId = homeObj == null ? "" : String.valueOf(homeObj);
                if (homeId.isEmpty()) return;

                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled) return;

                Object result = param.getResult();
                if (result != null && !(result instanceof List)) return;

                List<Object> want = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    Object bean = DeviceBeanFactory.deviceFor(DeviceListHook.context(), d,
                            DeviceListHook.specOf(d), DeviceListHook.stamp(cfg));
                    if (bean != null) want.add(bean);
                }
                if (want.isEmpty()) return;

                List<Object> list = result == null ? new ArrayList<>() : (List<Object>) result;
                Set<String> present = new HashSet<>();
                for (Object o : list) {
                    String did = DeviceListHook.strField(o, "did");
                    if (did != null) present.add(did);
                }
                List<Object> extra = new ArrayList<>();
                for (Object bean : want) {
                    String did = DeviceListHook.strField(bean, "did");
                    if (did == null || !present.add(did)) continue;
                    extra.add(bean);
                }
                if (extra.isEmpty()) return;

                int before = list.size();
                // 复制返回：米家自己那份频次列表可能被 LiveData 缓存/复用，不往里塞假对象
                ArrayList<Object> merged = new ArrayList<>(list);
                merged.addAll(extra);
                param.setResult(merged);
                Log.d("全屋频次设备注入: home=" + homeId + " 列表 " + before + "→" + merged.size());
                try {
                    CallLog.record(DeviceListHook.context(), CallLog.OP_INJECT, "freq", homeId,
                            "home-freq-list", before, merged.size(), 0, DeviceListHook.methodName(param));
                } catch (Throwable ignored) {
                }
            } catch (Throwable t) {
                Log.v("全屋频次设备注入跳过: " + HookUtil.shortMsg(t));
            }
        }
    };
}