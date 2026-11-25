/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 21:29:55 +08:00
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 首页房间 tab（feature.vm 这一套）的设备列表注入。
 *
 * <p>11.10.623 的首页用的是 {@code com.xiaomi.smarthome.feature.fragment.NormalRoomFragment}
 * + {@code feature.vm.NormalRoomViewModel}，数据在
 * {@code feature.vm.repository.NormalRoomRepository#getMemoryLatest} 里拼：
 * 它直接读自己的字段 {@code deviceRepository}（混淆名 {@code _m_j.ixn}，全类只有一个
 * {@code (String)->List} 方法）→ {@code _m_j.di7.OooOO0(roomId)} 按 roomId 分支：
 * <ul>
 *   <li>真实房间 id → {@code HomeManager.o000OOO(roomId)} → Room → {@code o000OOoO(Room)}
 *       → 遍历 {@code room.getDids()}，再逐台解析 Device 并过可见性过滤，假 did 解析不到就丢；</li>
 *   <li>{@code mijia.roomid.common} → 「常用」频次列表，同样按 did 二次解析；</li>
 *   <li>{@code mijia.roomid.all} → GridViewData 列表转 Device，只认云端已有设备。</li>
 * </ul>
 *
 * <p>这一层挂在仓库出口：设备仓库的类从 {@code NormalRoomRepository.deviceRepository}
 * 字段类型拿（不写死混淆名，也不靠 getter —— 上游是直接读字段，getter 根本不会调），
 * 再按形状找它唯一的 {@code (String)->List} 方法，after 里补启用中的模拟设备：
 * 聚合 tab（常用/全部）补全部；真实房间 tab 按 {@link DeviceListHook#roomNameOf(String)}
 * 缓存到的 roomId→房间名匹配设备房间；查不到名字就不补（宁可少补不乱补）。
 * 返回列表复制后再追加，不改米家自己那份活数据。
 */
public final class NormalRoomDeviceHook {

    private static final String GROUP = "home-room-list";
    private static final String CL_REPO = "com.xiaomi.smarthome.feature.vm.repository.NormalRoomRepository";
    /** 仓库里持有设备表的字段名（Kotlin private 字段，当前未被混淆）。*/
    private static final String F_STORE = "deviceRepository";
    /** 兜底：字段改名时用 getter 的返回类型找仓库类。*/
    private static final String M_GET_STORE = "getDeviceRepository";

    /** 「常用」tab 的 magic roomId。*/
    private static final String ROOM_COMMON = "mijia.roomid.common";
    /** 「全部设备」tab 的 magic roomId。*/
    private static final String ROOM_ALL = "mijia.roomid.all";

    private NormalRoomDeviceHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        Class<?> repo = HookUtil.findClassQuiet(CL_REPO, loader);
        if (repo == null) {
            // 仓库类晚加载：侦察兵等到类后再走同一套发现
            HookScout.await(CL_REPO, c -> attachFrom(c, loader));
            HookReport.skip(GROUP, CL_REPO, "类尚未加载，已交侦察兵");
            return;
        }
        attachFrom(repo, loader);
    }

    /** 先吃字段类型；字段改名就退回 getter 返回类型。两条都从狙击手口挂。*/
    private static void attachFrom(Class<?> repo, ClassLoader loader) {
        Field f = HookUtil.findField(repo, F_STORE);
        Class<?> store = f == null ? null : f.getType();
        if (store != null) {
            attachStore(store);
            return;
        }
        HookSniper.hookOnClass(repo, GROUP, new String[]{M_GET_STORE},
                HookSniper.Shape.anyArgs(null), M_GET_STORE, ATTACH_STORE);
    }

    /** 备用发现路径：getter 返回的设备仓库实例，取它的运行时类再挂出口。*/
    private static final XC_MethodHook ATTACH_STORE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object store = param.getResult();
                if (store == null) return;
                attachStore(store.getClass());
            } catch (Throwable t) {
                Log.v("设备仓库出口挂载跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static void attachStore(Class<?> store) {
        if (store == null) return;
        String key = store.getName() + "#(String)->List";
        if (HookUtil.isInstalled(key)) return;

        Method target = null;
        int count = 0;
        for (Method m : store.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != String.class) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            target = m;
            count++;
        }
        if (count != 1 || target == null) {
            HookReport.skip(GROUP, key, "设备仓库里 (String)->List 不唯一: " + count);
            return;
        }
        if (HookSniper.hookResolved(target, APPEND_ROOM_DEVICES)) {
            Log.d("首页设备仓库出口已挂: " + store.getName() + "#" + target.getName() + "(String)->List");
        }
    }

    private static final XC_MethodHook APPEND_ROOM_DEVICES = new XC_MethodHook() {
        @Override
        @SuppressWarnings("unchecked")
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object roomIdObj = param.args == null || param.args.length == 0 ? null : param.args[0];
                String roomId = roomIdObj == null ? "" : String.valueOf(roomIdObj);
                Object raw = param.getResult();
                Log.d("房间设备仓库调用: room=" + roomId + " 返回="
                        + (raw == null ? "null" : (raw instanceof List ? ((List<?>) raw).size() : raw.getClass().getSimpleName())));

                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled) return;

                boolean aggregate = isAggregateRoom(roomId);
                String wantRoom = aggregate ? null : DeviceListHook.roomNameOf(roomId);
                if (!aggregate && wantRoom == null) {
                    Log.d("首页设备仓库: roomId=" + roomId + " 还没拿到房间名，本次不补");
                    return;
                }

                List<Object> want = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (!aggregate && !normalize(wantRoom)
                            .equals(normalize(Consts.effectiveRoom(d.roomName)))) continue;
                    Object bean = DeviceBeanFactory.deviceFor(DeviceListHook.context(), d,
                            DeviceListHook.specOf(d), DeviceListHook.stamp(cfg));
                    if (bean != null) want.add(bean);
                }
                if (want.isEmpty()) return;

                Object result = param.getResult();
                if (result == null) {
                    param.setResult(want);
                    record(param, roomId, 0, want.size());
                    return;
                }
                if (!(result instanceof List)) return;
                List<Object> list = (List<Object>) result;

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
                // 复制返回：米家自己那份列表可能被缓存/序列化，不往里塞假对象
                ArrayList<Object> merged = new ArrayList<>(list);
                merged.addAll(extra);
                param.setResult(merged);
                record(param, roomId, before, merged.size());
            } catch (Throwable t) {
                Log.v("房间设备列表注入跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** 「常用」「全部」两个聚合 tab 补全部；其余按 roomId→房间名匹配。*/
    private static boolean isAggregateRoom(String roomId) {
        return ROOM_COMMON.equals(roomId) || ROOM_ALL.equals(roomId);
    }

    private static void record(XC_MethodHook.MethodHookParam param, String roomId, int before, int after) {
        Log.d("首页房间设备注入: room=" + roomId + " 列表 " + before + "→" + after);
        try {
            CallLog.record(DeviceListHook.context(), CallLog.OP_INJECT, "room", roomId,
                    "home-room-list", before, after, 0, DeviceListHook.methodName(param));
        } catch (Throwable ignored) {
        }
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}