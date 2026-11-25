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

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 新版首页（11.10.623 的 newui）房间设备列表的兜底注入。
 *
 * <p>为什么要这一层：新版首页不再读 {@code Home#getDids()}，而是
 * {@code HouseNormalRoomRepository} → {@code _m_j.ixn} → {@code _m_j.di7.OooOO0}
 * → {@code HomeManager.o000OOoO(Room)}；后者把 {@code room.getDids()} 里每个 did
 * 交给 {@link com.xiaomi.smarthome.device.SmartHomeDeviceManager} 解析成 Device，
 * 再逐台过 {@code HomeManager.o000o0Oo(Device)} 的可见性过滤，只有解析出 Device 的
 * 才会进列表 —— 假 did 在米家仓库里本来就不存在，解析出来是 null，直接被过滤掉。
 *
 * <p>所以修法是两层：
 * <ol>
 *   <li>{@link HookRegistry} 把稳定类名 {@code SmartHomeDeviceManager} 加进锚点，
 *       它的 by-did 查询在假 did 上直接回我们的 bean（见 {@code DeviceListHook#BY_DID}），
 *       过滤条件（hideMode / permitLevel / pid / 型号类别）都能过；</li>
 *   <li>这里再做一层「列表出口」兜底：按下述形状钩住 {@code HomeManager} 上唯一的
 *       {@code (Room) -> List} 方法（11.10.623 里就是 o000OOoO），after 里按房间名
 *       把启用中的模拟设备补进返回列表。即便哪天过滤条件又多了一条，首页照样有卡。</li>
 * </ol>
 *
 * <p>不按方法名写死：房间列表方法名是 R8 生成的（o000OOoO），下个版本还会变；
 * {@code (Room) -> List} 这个形状在 HomeManager 里唯一（已核对 11.10.623 声明），
 * 挂形状比挂名字耐用。返回列表复制后再追加，不改米家自己那份活数据。
 */
public final class HomeRoomDeviceHook {

    private static final String GROUP = "home-room";
    private static final String CL_HOME_MANAGER = "com.xiaomi.smarthome.homeroom.HomeManager";
    private static final String CL_ROOM = "com.xiaomi.smarthome.homeroom.model.Room";

    private HomeRoomDeviceHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        Class<?> manager = HookUtil.findClassQuiet(CL_HOME_MANAGER, loader);
        Class<?> room = HookUtil.findClassQuiet(CL_ROOM, loader);
        if (manager == null) {
            // 首页管理器还没进内存：交给侦察兵，类到了再按形状扫出口
            HookScout.await(CL_HOME_MANAGER,
                    c -> hookRoomLists(c, HookUtil.findClassQuiet(CL_ROOM, loader)));
            HookReport.skip(GROUP, "install", "HomeManager 尚未加载，已交侦察兵");
            return;
        }
        if (room == null) {
            HookReport.skip(GROUP, "install", "Room 类不存在（HomeManager 在，只能等它加载）");
            return;
        }
        hookRoomLists(manager, room);
    }

    /** 按 (Room)->List 形状扫 HomeManager 的出口，命中后统一走狙击手挂载。*/
    private static void hookRoomLists(Class<?> manager, Class<?> room) {
        if (manager == null || room == null) return;
        int hooked = 0;
        for (Method m : manager.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 1 || !pts[0].isAssignableFrom(room)) continue;
            if (!List.class.isAssignableFrom(m.getReturnType())) continue;
            String sig = manager.getSimpleName() + "#" + m.getName() + "(Room)->List";
            // 形状已经由这里判定，挂载交给狙击手去重
            if (HookSniper.hookResolved(m, APPEND_ROOM_DEVICES)) {
                HookReport.ok(GROUP, sig, "房间设备列表追加已挂上（形状命中）");
                hooked++;
            }
        }
        if (hooked == 0) {
            HookReport.fail(GROUP, "install", "HomeManager 上没有 (Room)->List 形状的方法");
        }
    }

    private static final XC_MethodHook APPEND_ROOM_DEVICES = new XC_MethodHook() {
        @Override
        @SuppressWarnings("unchecked")
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object room = param.args == null || param.args.length == 0 ? null : param.args[0];
                if (room == null) return;
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled) return;

                String roomName = DeviceListHook.strField(room, "name");
                if (roomName == null || roomName.trim().isEmpty()) return;
                String wantRoom = normalize(roomName);

                List<Object> want = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (!wantRoom.equals(normalize(Consts.effectiveRoom(d.roomName)))) continue;
                    Object bean = DeviceBeanFactory.deviceFor(DeviceListHook.context(), d,
                            DeviceListHook.specOf(d), DeviceListHook.stamp(cfg));
                    if (bean != null) want.add(bean);
                }
                if (want.isEmpty()) return;

                Object result = param.getResult();
                if (result == null) {
                    param.setResult(want);
                    record(param, roomName, 0, want.size());
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
                record(param, roomName, before, merged.size());
            } catch (Throwable t) {
                Log.v("房间设备列表注入跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static void record(XC_MethodHook.MethodHookParam param, String roomName, int before, int after) {
        Log.d("首页房间设备注入: room=" + roomName + " 列表 " + before + "→" + after);
        try {
            CallLog.record(DeviceListHook.context(), CallLog.OP_INJECT, "room", roomName,
                    "home-room", before, after, 0, DeviceListHook.methodName(param));
        } catch (Throwable ignored) {
        }
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}