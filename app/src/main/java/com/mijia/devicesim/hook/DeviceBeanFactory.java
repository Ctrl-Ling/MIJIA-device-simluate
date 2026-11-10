/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;
import android.os.Bundle;

import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceStateStore;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用反射造米家自己的设备对象。两个产物：
 * <ul>
 *   <li>{@code com.xiaomi.smarthome.device.Device} —— 首页列表/自动化用；</li>
 *   <li>{@code com.xiaomi.smarthome.device.api.DeviceStat} —— 插件（控制面板进程）用。</li>
 * </ul>
 * 这两个类的字段在 11.9.705 里全是 public 且没混淆（见 docs/HOOKS.md 的字段表），
 * 所以直接写字段比调 setter 稳；字段在某版被改名时，{@link #setIfPresent} 会跳过而不是崩。
 *
 * pid（接入类型）是 Device 上的 static int 字段，运行期由米家赋值，绝不硬编码 —— 读不到就留 0，
 * 列表照样显示，只是类型图标不对。
 */
public final class DeviceBeanFactory {

    private DeviceBeanFactory() {
    }

    public static final String CL_DEVICE = "com.xiaomi.smarthome.device.Device";
    public static final String CL_DEVICE_STAT = "com.xiaomi.smarthome.device.api.DeviceStat";
    /** WiFi(miio) 设备走这个子类，isOnlineAdvance 等判断才会走 miio 分支。*/
    public static final String CL_MIIO_DEVICE_V2 = "com.xiaomi.smarthome.device.MiioDeviceV2";
    public static final String CL_BLE_DEVICE = "com.xiaomi.smarthome.device.BleDevice";

    private static Class<?> sDeviceClass;
    private static Class<?> sStatClass;
    private static Class<?> sMiioClass;
    private static Class<?> sBleClass;

    /** did -> 生成时的配置指纹；配置一改就重建，避免界面上改了名米家还显示旧的。*/
    private static final Map<String, Object> sDeviceCache = new LinkedHashMap<>();
    private static final Map<String, Integer> sDeviceStamp = new LinkedHashMap<>();
    private static final Map<String, Object> sStatCache = new LinkedHashMap<>();
    private static final Map<String, Integer> sStatStamp = new LinkedHashMap<>();

    public static void prepare(ClassLoader loader) {
        if (sDeviceClass != null) return;
        sDeviceClass = HookUtil.findClassQuiet(CL_DEVICE, loader);
        sStatClass = HookUtil.findClassQuiet(CL_DEVICE_STAT, loader);
        sMiioClass = HookUtil.findClassQuiet(CL_MIIO_DEVICE_V2, loader);
        sBleClass = HookUtil.findClassQuiet(CL_BLE_DEVICE, loader);
        Log.d("Bean 类就绪: Device=" + (sDeviceClass != null) + " DeviceStat=" + (sStatClass != null)
                + " MiioV2=" + (sMiioClass != null) + " Ble=" + (sBleClass != null));
    }

    public static boolean ready() {
        return sDeviceClass != null;
    }

    public static void clearCache() {
        synchronized (sDeviceCache) {
            sDeviceCache.clear();
            sDeviceStamp.clear();
            sStatCache.clear();
            sStatStamp.clear();
        }
    }

    /** 一个 did 一台假设备对应的 Device 对象（同一次配置里必须复用同一实例，米家按引用比较）。*/
    public static Object deviceFor(Context ctx, SimDevice cfg, MiotSpec spec, int configStamp) {
        if (cfg == null || sDeviceClass == null) return null;
        synchronized (sDeviceCache) {
            Integer stamp = sDeviceStamp.get(cfg.did);
            Object cached = sDeviceCache.get(cfg.did);
            if (cached != null && stamp != null && stamp == configStamp) return cached;
            Object built = buildDevice(ctx, cfg, spec);
            if (built != null) {
                sDeviceCache.put(cfg.did, built);
                sDeviceStamp.put(cfg.did, configStamp);
            }
            return built;
        }
    }

    public static Object deviceStatFor(Context ctx, SimDevice cfg, MiotSpec spec, int configStamp) {
        if (cfg == null || sStatClass == null) return null;
        synchronized (sStatCache) {
            Integer stamp = sStatStamp.get(cfg.did);
            Object cached = sStatCache.get(cfg.did);
            if (cached != null && stamp != null && stamp == configStamp) return cached;
            Object built = buildDeviceStat(ctx, cfg, spec);
            if (built != null) {
                sStatCache.put(cfg.did, built);
                sStatStamp.put(cfg.did, configStamp);
            }
            return built;
        }
    }

    /* ---------- 构造 ---------- */

    private static Object buildDevice(Context ctx, SimDevice cfg, MiotSpec spec) {
        Class<?> target = pickDeviceClass(cfg.type);
        Object bean = newInstance(target, cfg);
        if (bean == null) return null;
        fillCommonFields(ctx, bean, cfg, spec);
        return bean;
    }

    private static Object buildDeviceStat(Context ctx, SimDevice cfg, MiotSpec spec) {
        Object bean = newInstance(sStatClass, cfg);
        if (bean == null) return null;
        fillCommonFields(ctx, bean, cfg, spec);
        return bean;
    }

    /**
     * WiFi 用 MiioDeviceV2、BLE 用 BleDevice，其余（Zigbee 子设备、网关）用基类 Device：
     * 子类型自带更严格的 isOnline 逻辑，挂错类型会让首页直接判离线。
     */
    private static Class<?> pickDeviceClass(String type) {
        if (Consts.TYPE_WIFI.equals(type) && sMiioClass != null) return sMiioClass;
        if ((Consts.TYPE_BLE.equals(type)) && sBleClass != null) return sBleClass;
        return sDeviceClass;
    }

    private static Object newInstance(Class<?> clazz, SimDevice cfg) {
        if (clazz == null) return null;
        // 优先 (String did, String model) 双参构造，米家两个 bean 都提供了
        try {
            Constructor<?> c = clazz.getConstructor(String.class, String.class);
            Object o = c.newInstance(cfg.did, cfg.model);
            if (o != null) return o;
        } catch (Throwable ignored) {
        }
        try {
            Constructor<?> c = clazz.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable t) {
            Log.w("无法实例化 " + clazz.getName() + ": " + HookUtil.shortMsg(t));
            return null;
        }
    }

    private static void fillCommonFields(Context ctx, Object bean, SimDevice cfg, MiotSpec spec) {
        setIfPresent(bean, "did", cfg.did);
        setIfPresent(bean, "model", cfg.model);
        setIfPresent(bean, "name", cfg.name == null || cfg.name.isEmpty() ? cfg.model : cfg.name);
        setIfPresent(bean, "token", cfg.token);
        setIfPresent(bean, "mac", cfg.mac);
        setIfPresent(bean, "isOnline", cfg.online);
        setIfPresent(bean, "isNew", false);
        setIfPresent(bean, "ownerId", currentUid(ctx));
        setIfPresent(bean, "userId", currentUid(ctx));
        setIfPresent(bean, "canAuth", true);
        setIfPresent(bean, "canUseNotBind", true);
        setIfPresent(bean, "isConnected", cfg.online);
        long now = System.currentTimeMillis();
        setIfPresent(bean, "lastOnline", now);
        setIfPresent(bean, "lastModified", now);
        setIfPresent(bean, "sort", cfg.sort);
        setIfPresent(bean, "hideMode", hideModeShow());
        setIfPresent(bean, "permitLevel", permitOwner());

        if (Consts.TYPE_ZIGBEE_SUB.equals(cfg.type) || Consts.TYPE_BLE_MESH.equals(cfg.type)) {
            setIfPresent(bean, "parentId", cfg.parentDid);
            setIfPresent(bean, "parentModel", modelOfParent(ctx, cfg.parentDid));
            setIfPresent(bean, "meshId", cfg.parentDid);
        }
        if (spec != null) {
            setIfPresent(bean, "specUrn", spec.urn);
        }

        int pid = pidFor(bean.getClass(), cfg.type);
        if (pid != 0) setIfPresent(bean, "pid", pid);

        JSONObject propInfo = DeviceStateStore.propInfo(ctx, cfg, spec);
        setIfPresent(bean, "propInfo", propInfo);
        setIfPresent(bean, "mPropInfo", propInfo == null ? null : propInfo.toString());
        Bundle bundle = propBundle(ctx, cfg, spec);
        if (bundle != null) setIfPresent(bean, "property", bundle);

        // 标记位：CloudSyncGuard 和自动化侧都靠它认这台是假设备
        try {
            JSONObject extra = new JSONObject();
            extra.put(Consts.EXTRA_SIMULATED, 1);
            for (Map.Entry<String, String> e : cfg.extra.entrySet()) extra.put(e.getKey(), e.getValue());
            setIfPresent(bean, "extra", extra);
        } catch (Throwable t) {
            Log.v("extra 构造失败: " + t);
        }
    }

    private static Bundle propBundle(Context ctx, SimDevice cfg, MiotSpec spec) {
        try {
            Map<String, Object> snap = DeviceStateStore.snapshot(ctx, cfg, spec);
            if (snap.isEmpty()) return null;
            Bundle b = new Bundle();
            for (Map.Entry<String, Object> e : snap.entrySet()) {
                Object v = e.getValue();
                if (v == null) continue;
                if (v instanceof Boolean) b.putBoolean(e.getKey(), (Boolean) v);
                else if (v instanceof Integer) b.putInt(e.getKey(), (Integer) v);
                else if (v instanceof Number) b.putDouble(e.getKey(), ((Number) v).doubleValue());
                else b.putString(e.getKey(), String.valueOf(v));
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setIfPresent(Object bean, String field, Object value) {
        if (value == null) return;
        if (HookUtil.findField(bean.getClass(), field) == null) return;
        try {
            XposedFieldSetter.set(bean, field, value);
        } catch (Throwable t) {
            // 类型不匹配（比如 sort 换了 long）不影响主流程
            Log.v("字段写入跳过 " + field + ": " + HookUtil.shortMsg(t));
        }
    }

    /* ---------- 运行期常量读取 ---------- */

    /** Device 上的 PID_* 是 static int，米家自己赋值，这里读当前进程里的真值。*/
    static int pidFor(Class<?> deviceClass, String type) {
        String fieldName;
        switch (type) {
            case Consts.TYPE_BLE:
                fieldName = "PID_BLUETOOTH";
                break;
            case Consts.TYPE_BLE_MESH:
                fieldName = "PID_BLE_MESH";
                break;
            case Consts.TYPE_ZIGBEE_SUB:
                fieldName = "PID_SUBDEVICE";
                break;
            case Consts.TYPE_GATEWAY:
                fieldName = "PID_MIIO";
                break;
            case Consts.TYPE_WIFI:
            default:
                fieldName = "PID_MIIO";
                break;
        }
        Class<?> c = deviceClass != null ? deviceClass : sDeviceClass;
        while (c != null && c != Object.class) {
            Integer v = staticInt(c, fieldName);
            if (v != null) return v;
            c = c.getSuperclass();
        }
        Log.v("读不到 " + fieldName + "，pid 留默认");
        return 0;
    }

    private static Integer staticInt(Class<?> c, String name) {
        try {
            java.lang.reflect.Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof Number ? ((Number) v).intValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** HIDE_MODE_SHOW_MAIN_LIST：不写就是 0，某些版本 0 代表隐藏。*/
    private static int hideModeShow() {
        Integer v = staticInt(sDeviceClass, "HIDE_MODE_SHOW_MAIN_LIST");
        return v == null ? 0 : v;
    }

    /** PERMISSION_OWNER：没有归属权限的话自动化里选不到这台设备。*/
    private static int permitOwner() {
        Integer v = staticInt(sDeviceClass, "PERMISSION_OWNER");
        return v == null ? 1 : v;
    }

    private static String currentUid(Context ctx) {
        try {
            Object accountManager = Class.forName("com.xiaomi.smarthome.account.AccountManager")
                    .getMethod("getInstance")
                    .invoke(null);
            Object miId = accountManager.getClass().getMethod("getMiId").invoke(accountManager);
            if (miId != null) return String.valueOf(miId);
        } catch (Throwable ignored) {
        }
        // 拿不到就不写 ownerId，米家按当前登录用户补齐
        return null;
    }

    private static String modelOfParent(Context ctx, String parentDid) {
        if (parentDid == null || parentDid.isEmpty()) return "";
        SimConfig cfg = com.mijia.devicesim.core.ConfigRepository.current();
        SimDevice parent = cfg.findByDid(parentDid);
        return parent == null ? "" : parent.model;
    }
}
