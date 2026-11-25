/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 16:15:17 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 房间 / 家庭设备 did 名单补假设备。
 *
 * <p>为什么需要这一层：米家 11.10.623 的「创建自动化 → 添加触发条件 → 家居设备」
 * 页（{@code specscene.device.EditControllableDeviceList}）的数据源是
 * {@code SpecSceneDeviceListViewModel#getAllData}，它按「房间列表 → Room#getDids()」
 * 和「Home#getDids()（未分配）」两条路遍历 did，再拿 did 去
 * {@code SmartHomeDeviceManager.getDeviceByDid} 解析成 Device。
 *
 * <p>假设备只有 {@link DeviceListHook} 那一层能在 by-did 查询时造出 bean，
 * 但它的 did 从来没进过 Room / Home 的 did 名单 —— 遍历不到，卡片自然不会出现。
 *
 * <p>所以这里在名单出口补：{@code Room#getDids()} 按房间名匹配 {@code SimDevice.roomName}；
 * {@code Home#getDids()} 只补「不属于任何已有房间」的那几台（它在场景选设备里表示未分配）。
 * 返回的列表一律复制后再追加，米家自己那份活数据不动。
 *
 * <p>不写死房间：房间名来自运行时 Room 对象，比对走 {@link Consts#effectiveRoom(String)}，
 * 配置里写「客厅」就进客厅，写不存在/空就落进未分配。
 */
public final class RoomDidsHook {

    private static final String GROUP = "room-dids";
    private static final String CL_ROOM = "com.xiaomi.smarthome.homeroom.model.Room";
    private static final String CL_HOME = "com.xiaomi.smarthome.homeroom.model.Home";

    private static Class<?> sHomeClass;

    private RoomDidsHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> room = HookUtil.findClassQuiet(CL_ROOM, lpparam.classLoader);
        Class<?> home = HookUtil.findClassQuiet(CL_HOME, lpparam.classLoader);
        sHomeClass = home;
        boolean r = room != null && HookUtil.hookMethod(GROUP, room, "getDids", ROOM_DIDS);
        boolean h = home != null && HookUtil.hookMethod(GROUP, home, "getDids", HOME_DIDS);
        if (room == null || home == null) {
            HookReport.skip(GROUP, "install", "类不存在（Room=" + (room != null)
                    + " Home=" + (home != null) + "）");
        } else if (r || h) {
            HookReport.ok(GROUP, "install", "房间名单=" + r + "，家庭未分配名单=" + h);
        } else {
            HookReport.fail(GROUP, "install", "Room/Home 上都没挂上 getDids");
        }
    }

    private static final XC_MethodHook ROOM_DIDS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object room = param.thisObject;
                if (room == null) return;
                String roomName = DeviceListHook.strField(room, "name");
                if (roomName == null || roomName.trim().isEmpty()) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                String want = normalize(roomName);

                List<String> extra = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (d == null || d.did == null || d.did.isEmpty()) continue;
                    if (!want.equals(normalize(Consts.effectiveRoom(d.roomName)))) continue;
                    extra.add(d.did);
                }
                merge(param, extra, "room=" + roomName);
            } catch (Throwable t) {
                Log.v("Room#getDids 补假设备跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static final XC_MethodHook HOME_DIDS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object home = param.thisObject;
                if (home == null) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;

                Set<String> rooms = roomNames(home);
                List<String> extra = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (d == null || d.did == null || d.did.isEmpty()) continue;
                    String room = normalize(Consts.effectiveRoom(d.roomName));
                    if (rooms.contains(room)) continue; // 已归属某房间的由 ROOM_DIDS 负责
                    extra.add(d.did);
                }
                merge(param, extra, "home=unassigned");
            } catch (Throwable t) {
                Log.v("Home#getDids 补假设备跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** 家庭里已有房间名（归一化）；取不到就当没有任何房间，全部按未分配补。*/
    private static Set<String> roomNames(Object home) {
        Set<String> out = new HashSet<>();
        try {
            Object list = HookUtil.invokeQuiet(home, sHomeClass, "getRoomList", new Class<?>[0]);
            if (!(list instanceof List)) return out;
            for (Object r : (List<?>) list) {
                String name = DeviceListHook.strField(r, "name");
                if (name != null && !name.trim().isEmpty()) out.add(normalize(name));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 把缺的 did 追加到返回列表；列表不存在就直接给一份新的。*/
    private static void merge(XC_MethodHook.MethodHookParam param, List<String> extra, String where) {
        if (extra.isEmpty()) return;
        Object result = param.getResult();
        if (result != null && !(result instanceof List)) return;
        List<?> list = (List<?>) result;
        Set<String> present = new HashSet<>();
        if (list != null) for (Object o : list) if (o instanceof String) present.add((String) o);

        ArrayList<Object> merged = null;
        int added = 0;
        for (String did : extra) {
            if (!present.add(did)) continue;
            if (merged == null) {
                merged = new ArrayList<>();
                if (list != null) merged.addAll(list);
            }
            merged.add(did);
            added++;
        }
        if (merged == null) return;
        param.setResult(merged);
        Log.d("场景选设备名单补 " + added + " 个假 did（" + where + "）");
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}