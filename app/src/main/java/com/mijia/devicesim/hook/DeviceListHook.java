/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-12 11:03:48 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 设备列表注入（plan Phase 3）。两条路一起走，缺一条首页就不显示：
 *
 * <ol>
 *   <li><b>对象列表</b>：所有「返回 Collection&lt;Device&gt; 的设备管理器」—— 米家这块被 R8 改名成
 *       {@code _m_j.c67} 之类，但 {@code getDeviceList()} / {@code getDeviceByDid(String)} 这两个方法名
 *       是保留的（11.9.705 实测，见 docs/HOOKS.md），所以按【结构特征】在类加载时现场挂，
 *       不写死混淆类名，米家升级改名不会整体失效。</li>
 *   <li><b>did 列表</b>：首页真正遍历的是 {@code Home.getDids()} 里的字符串，再逐个
 *       {@code getDeviceByDid}。假 did 不在这个名单里，对象造得再对也不会被请求 ——
 *       所以 {@code Home} 的四个 did getter 也要追加。</li>
 * </ol>
 */
public final class DeviceListHook {

    private DeviceListHook() {
    }

    private static final String GROUP = "device-list";
    private static final String GROUP_REGISTRY = "device-registry";
    private static final String GROUP_HOME = "home-dids";
    private static final String GROUP_PLUGIN = "plugin-api";

    private static final Set<String> COLLECTION_GETTERS = new HashSet<>(java.util.Arrays.asList(
            "getDeviceList", "getAllDevices", "getDevices", "getDeviceListV2", "getSmartHomeDevices"));
    private static final Set<String> DID_GETTERS = new HashSet<>(java.util.Arrays.asList(
            "getDeviceByDid", "getDeviceWithDid", "getDevice"));
    private static final Set<String> HOME_DID_GETTERS = new HashSet<>(java.util.Arrays.asList(
            "getDids", "getAllDids", "getDidsExcludeStatus", "getDefDids"));

    private static XC_LoadPackage.LoadPackageParam sLpparam;

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        sLpparam = lpparam;
        DeviceBeanFactory.prepare(lpparam.classLoader);
        hookHomeDidLists(lpparam);
        hookPluginApi(lpparam);
    }

    /* ---------- 1. 结构指纹：设备管理器 ---------- */

    /**
     * HookRegistry 在类加载时把可疑类丢进来。判定标准（11.9.705 实测）：
     *
     * <p>必备那一半：类里有 {@code (String) -> Device/DeviceStat} 的查询方法（名字不一定是
     * {@code getDeviceByDid}，所以先认名字、再认形状）。
     *
     * <p>另一半（11-11 放宽）：**任意**无参、返回 {@code Collection} 或 {@code Map} 的方法都算
     * 「能吐出设备集合」。原因是自动化那套走的是 Kotlin 版设备仓库
     * {@code com.xiaomi.smarthome.device.impl.OooO0O0}（实现 {@code _m_j.k87}），它的列表 getter
     * 全被 R8 改名成 {@code Oooo0oO()} / {@code OoooO00()} / {@code o000000O()}，映射表
     * {@code OooOOO()} / {@code Oooo0()} / {@code Oooo00o()} / {@code Ooooooo()} 同理 ——
     * 老白名单一个都匹配不上，于是这台「真设备仓库」从来没被挂过，自动化选设备自然看不到假设备。
     *
     * <p>放宽不会误伤：这些没名字的 getter 走 {@link #APPEND_IF_DEVICE_LIST} /
     * {@link #APPEND_IF_DEVICE_MAP}，运行时先看元素/值是不是设备对象，不是就原样放行。
     */
    public static boolean looksLikeDeviceManager(Class<?> clazz) {
        Method[] methods;
        try {
            methods = clazz.getDeclaredMethods();
        } catch (Throwable t) {
            return false;
        }
        boolean hasByDid = false;
        boolean hasList = false;
        for (Method m : methods) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] pts = m.getParameterTypes();
            Class<?> rt = m.getReturnType();
            if (pts.length == 1 && pts[0] == String.class && isDeviceLike(rt)) {
                hasByDid = true;
            } else if (pts.length == 2 && pts[0] == String.class && isDeviceLike(rt)) {
                // 门面版：OooO0o(String, cl3)，第二个参数只是个包装器，did 仍是第一个
                hasByDid = true;
            } else if (m.getName().equals("getSubDeviceByParentDid") && pts.length == 1 && pts[0] == String.class
                    && Collection.class.isAssignableFrom(rt)) {
                // Zigbee 子设备挂在网关下面时，网关页走的是这个方法而不是 getDeviceList
                hasList = true;
            } else if (pts.length == 0 && (Collection.class.isAssignableFrom(rt)
                    || java.util.Map.class.isAssignableFrom(rt))) {
                hasList = true;
            }
        }
        return hasByDid && hasList;
    }

    private static boolean isDeviceLike(Class<?> rt) {
        String n = rt == null ? "" : rt.getName();
        return n.equals(DeviceBeanFactory.CL_DEVICE) || n.equals(DeviceBeanFactory.CL_DEVICE_STAT)
                || n.equals("com.xiaomi.smarthome.device.BleDevice")
                || n.equals("com.xiaomi.smarthome.device.MiioDeviceV2");
    }

    /** 命中特征的设备管理器：挂它的集合 getter 和 by-did getter。*/
    public static int hookDeviceManager(Class<?> clazz) {
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] pts = m.getParameterTypes();
            Class<?> rt = m.getReturnType();
            if (COLLECTION_GETTERS.contains(m.getName()) && pts.length == 0 && Collection.class.isAssignableFrom(rt)) {
                if (HookUtil.hookMethod(GROUP, clazz, m.getName(), APPEND_COLLECTION)) hooked++;
            } else if (m.getName().equals("getSubDeviceByParentDid") && pts.length == 1 && pts[0] == String.class
                    && Collection.class.isAssignableFrom(rt)) {
                if (HookUtil.hookMethod(GROUP, clazz, m.getName(), APPEND_SUB_DEVICES, String.class)) hooked++;
            } else if (isDeviceLookup(m)) {
                // 按 did 查设备的方法在混淆版里可能不叫 getDeviceByDid，
                // 但 (String)->Device 这个形状是唯一的；BY_DID 只在参数命中保留段时才动手，安全。
                if (HookUtil.hookMethod(GROUP, clazz, m.getName(), BY_DID, String.class)) hooked++;
            } else if (pts.length == 2 && pts[0] == String.class && isDeviceLike(rt)) {
                // (String, 包装器) -> Device：门面那一层，did 永远在第一个参数
                if (HookUtil.hookMethod(GROUP, clazz, m.getName(), BY_DID, String.class, pts[1])) hooked++;
            } else if (pts.length == 0 && java.util.Map.class.isAssignableFrom(rt)) {
                // did -> Device 的映射表：自动化「家居设备」页是按 Map 遍历的
                if (HookUtil.hookMethod(GROUP, clazz, m.getName(), APPEND_IF_DEVICE_MAP)) hooked++;
            } else if (pts.length == 0 && Collection.class.isAssignableFrom(rt)) {
                if (HookUtil.hookMethod(GROUP, clazz, m.getName(), APPEND_IF_DEVICE_LIST)) hooked++;
            }
        }
        if (hooked > 0) Log.d("设备管理器指纹命中: " + clazz.getName() + "，挂上 " + hooked + " 个方法");
        return hooked;
    }

    private static boolean isDeviceLookup(Method m) {
        Class<?>[] pts = m.getParameterTypes();
        return pts.length == 1 && pts[0] == String.class && isDeviceLike(m.getReturnType());
    }

    /** 返回集合但不敢确定元素是设备：运行时看一眼真实元素再决定补不补。*/
    private static final XC_MethodHook APPEND_IF_DEVICE_LIST = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object result = param.getResult();
                if (!(result instanceof Collection)) return;
                Collection<?> list = (Collection<?>) result;
                if (!allLooksLikeDevice(list)) {
                    // 空表也别急着放弃：泛型签名写着 List<Device> 的，语义已经明确
                    if (!list.isEmpty() || !declaresDeviceType(param)) return;
                }
                appendDevices(param);
            } catch (Throwable t) {
                Log.e("设备集合注入异常", t);
            }
        }
    };

    /**
     * 返回 {@code Map<did, Device>} 的方法：复制一份再补假设备。
     *
     * <p>为什么不改原表：这张表多半是设备仓库自己的活缓存，往里塞假 did 会跟着被持久化/上报。
     * 复制后 {@code setResult}，调用方（自动化选设备页）看到的是「本次读取」的增强视图。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final XC_MethodHook APPEND_IF_DEVICE_MAP = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object result = param.getResult();
                if (!(result instanceof java.util.Map)) return;
                java.util.Map map = (java.util.Map) result;

                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled || cfg.devices.isEmpty()) return;
                List<SimDevice> targets = cfg.enabledDevices();
                if (targets.isEmpty()) return;

                // 值类型决定该造 Device 还是 DeviceStat
                Class<?> want = null;
                if (map.isEmpty()) {
                    if (!declaresDeviceType(param)) return;
                } else {
                    int seen = 0;
                    for (Object v : map.values()) {
                        if (v == null) continue;
                        if (!looksLikeDeviceBean(v)) return;
                        want = v.getClass();
                        if (++seen >= 5) break;
                    }
                }

                Set<String> present = new java.util.HashSet<>();
                for (Object k : map.keySet()) if (k instanceof String) present.add((String) k);
                for (Object v : map.values()) {
                    String did = v == null ? null : strField(v, "did");
                    if (did != null) present.add(did);
                }

                java.util.LinkedHashMap<Object, Object> merged = new java.util.LinkedHashMap<>(map);
                int before = map.size();
                for (SimDevice d : targets) {
                    if (present.contains(d.did)) continue;
                    Object bean = beanFor(want, d, cfg);
                    if (bean != null) merged.put(d.did, bean);
                }
                if (merged.size() == before) return;

                Class<?> rt = returnType(param);
                if (rt != null && !rt.isAssignableFrom(java.util.LinkedHashMap.class)) {
                    // 返回具体实现（ConcurrentHashMap 之类）装不下副本 → 宁可不动，也不改活缓存
                    Log.v("映射表返回类型 " + rt.getSimpleName() + " 装不下副本，跳过注入");
                    return;
                }
                param.setResult(merged);
                CallLog.record(context(), CallLog.OP_INJECT, "map", declaringClass(param).getSimpleName(),
                        "", before, merged.size(), 0, methodName(param));
            } catch (Throwable t) {
                Log.e("设备映射注入异常", t);
            }
        }
    };

    private static boolean looksLikeDeviceBean(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        return n.equals(DeviceBeanFactory.CL_DEVICE)
                || n.equals(DeviceBeanFactory.CL_DEVICE_STAT)
                || n.equals(DeviceBeanFactory.CL_BLE_DEVICE)
                || n.equals(DeviceBeanFactory.CL_MIIO_DEVICE_V2);
    }

    /** 泛型签名里出现设备类型（{@code List<Device>} / {@code Map<String, ? extends Device>}）。*/
    private static boolean declaresDeviceType(XC_MethodHook.MethodHookParam param) {
        Method m = methodOf(param);
        return m != null && mentionsDevice(m.getGenericReturnType(), 0);
    }

    private static boolean mentionsDevice(java.lang.reflect.Type t, int depth) {
        if (t == null || depth > 4) return false;
        if (t instanceof Class) return looksLikeDeviceBeanType((Class<?>) t);
        if (t instanceof java.lang.reflect.ParameterizedType) {
            for (java.lang.reflect.Type a : ((java.lang.reflect.ParameterizedType) t).getActualTypeArguments()) {
                if (mentionsDevice(a, depth + 1)) return true;
            }
            return false;
        }
        if (t instanceof java.lang.reflect.WildcardType) {
            for (java.lang.reflect.Type b : ((java.lang.reflect.WildcardType) t).getUpperBounds()) {
                if (mentionsDevice(b, depth + 1)) return true;
            }
            return false;
        }
        if (t instanceof java.lang.reflect.GenericArrayType) {
            return mentionsDevice(((java.lang.reflect.GenericArrayType) t).getGenericComponentType(), depth + 1);
        }
        return false;
    }

    /**
     * 空表时只能看泛型签名，所以只认基类 {@code Device} 和 {@code DeviceStat}。
     * 不认 {@code MiioDeviceV2}/{@code BleDevice}：假对象的具体子类由配置的接入类型决定，
     * 声明成更窄的类型时可能造出另一个子类，塞进去就是 ClassCastException —— 宁可不动。
     */
    private static boolean looksLikeDeviceBeanType(Class<?> c) {
        String n = c == null ? "" : c.getName();
        return n.equals(DeviceBeanFactory.CL_DEVICE) || n.equals(DeviceBeanFactory.CL_DEVICE_STAT);
    }

    /**
     * 非空且全部是 Device/DeviceStat 才算设备列表；空表返回 false，
     * 由调用方（{@link #APPEND_IF_DEVICE_LIST}）再看泛型签名决定。
     */
    private static boolean allLooksLikeDevice(Collection<?> list) {
        if (list == null || list.isEmpty()) return false;
        int seen = 0;
        for (Object o : list) {
            if (o == null) continue;
            String n = o.getClass().getName();
            boolean ok = n.equals(DeviceBeanFactory.CL_DEVICE)
                    || n.equals(DeviceBeanFactory.CL_DEVICE_STAT)
                    || n.equals("com.xiaomi.smarthome.device.BleDevice")
                    || n.equals("com.xiaomi.smarthome.device.MiioDeviceV2");
            if (!ok) return false;
            if (++seen >= 5) return true;
        }
        return seen > 0;
    }

    /** 返回 Collection 的方法：往结果里补假设备。*/
    private static final XC_MethodHook APPEND_COLLECTION = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            appendDevices(param);
        }
    };

    @SuppressWarnings("unchecked")
    private static void appendDevices(XC_MethodHook.MethodHookParam param) {
        try {
            SimConfig cfg = ConfigRepository.current();
            if (!cfg.injectEnabled || cfg.devices.isEmpty()) return;
            List<SimDevice> targets = cfg.enabledDevices();
            List<Object> fakes = new ArrayList<>();
            List<String> fakeDids = new ArrayList<>();
            for (SimDevice d : targets) {
                Object bean = beanFor(returnType(param), d, cfg);
                if (bean != null) {
                    fakes.add(bean);
                    fakeDids.add(d.did);
                }
            }
            if (fakes.isEmpty()) return;
            Object result = param.getResult();
            if (result == null) {
                param.setResult(new ArrayList<>(fakes));
                return;
            }
            if (!(result instanceof Collection)) return;
            Collection<Object> list = (Collection<Object>) result;
            Set<String> present = didsIn(list);
            int before = list.size();
            List<Object> missing = new ArrayList<>();
            for (int i = 0; i < fakes.size(); i++) {
                if (!present.contains(fakeDids.get(i))) missing.add(fakes.get(i));
            }
            if (missing.isEmpty()) return;
            Class<?> rt = returnType(param);
            if (rt == Collection.class || rt == Iterable.class || List.class.isAssignableFrom(rt)) {
                // 复制返回，不把假对象塞进米家自己的缓存列表：
                // 那份列表一旦被写回本地 DB，云同步就可能把假 did 当真实成员上报。
                ArrayList<Object> merged = new ArrayList<>(list);
                merged.addAll(missing);
                param.setResult(merged);
            } else if (java.util.Set.class.isAssignableFrom(rt)) {
                java.util.LinkedHashSet<Object> merged = new java.util.LinkedHashSet<>(list);
                merged.addAll(missing);
                param.setResult(merged);
            } else {
                try {
                    list.addAll(missing);
                } catch (Throwable readOnly) {
                    Log.v("集合只读且类型未知，放弃注入: " + rt.getSimpleName());
                    return;
                }
            }
            CallLog.record(context(), CallLog.OP_INJECT, "list", declaringClass(param).getSimpleName(),
                    "", before, before + missing.size(), 0, methodName(param));
        } catch (Throwable t) {
            // 注入失败绝不能冒到米家
            Log.e("列表注入异常", t);
        }
    }

    /* ---------- MethodHookParam.method 是 Member，取方法信息要绕一下 ---------- */

    static java.lang.reflect.Method methodOf(XC_MethodHook.MethodHookParam param) {
        Object m = param.method;
        return m instanceof java.lang.reflect.Method ? (java.lang.reflect.Method) m : null;
    }

    static Class<?> returnType(XC_MethodHook.MethodHookParam param) {
        java.lang.reflect.Method m = methodOf(param);
        return m == null ? null : m.getReturnType();
    }

    static Class<?> declaringClass(XC_MethodHook.MethodHookParam param) {
        java.lang.reflect.Member m = param.method;
        return m == null ? DeviceListHook.class : m.getDeclaringClass();
    }

    static String methodName(XC_MethodHook.MethodHookParam param) {
        java.lang.reflect.Member m = param.method;
        return m == null ? "?" : m.getName();
    }

    /** 按调用方声明的返回类型决定造 Device 还是 DeviceStat。*/
    static Object beanFor(Class<?> want, SimDevice d, SimConfig cfg) {
        if (want != null && want.getName().equals(DeviceBeanFactory.CL_DEVICE_STAT)) {
            return DeviceBeanFactory.deviceStatFor(context(), d, specOf(d), stamp(cfg));
        }
        return DeviceBeanFactory.deviceFor(context(), d, specOf(d), stamp(cfg));
    }

    /** 按 did 取设备：假 did 直接给假对象，真 did 原样放行。*/
    private static final XC_MethodHook BY_DID = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = (String) param.args[0];
                if (!Consts.isSimulatedDid(did)) return;
                SimConfig cfg = ConfigRepository.current();
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) return;
                Class<?> want = returnType(param);
                Object bean = beanFor(want, dev, cfg);
                if (bean != null && (want == null || want.isInstance(bean))) {
                    param.setResult(bean);
                } else if (bean != null) {
                    Log.v("did " + did + " 的返回类型 " + (want == null ? "?" : want.getSimpleName()) + " 与假对象不匹配，放行");
                }
            } catch (Throwable t) {
                Log.e("by-did 注入异常", t);
            }
        }
    };

    /** 网关详情页的子设备名单：只补 parentDid 对得上的假设备。*/
    @SuppressWarnings("unchecked")
    private static final XC_MethodHook APPEND_SUB_DEVICES = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String parentDid = (String) param.args[0];
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled || parentDid == null) return;
                List<Object> kids = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (!parentDid.equals(d.parentDid)) continue;
                    Object bean = DeviceBeanFactory.deviceFor(context(), d, specOf(d), stamp(cfg));
                    if (bean != null) kids.add(bean);
                }
                if (kids.isEmpty()) return;
                Object result = param.getResult();
                if (result == null) {
                    param.setResult(kids);
                    return;
                }
                if (result instanceof Collection) {
                    Collection<Object> list = (Collection<Object>) result;
                    Set<String> present = didsIn(list);
                    for (Object o : kids) {
                        String did = strField(o, "did");
                        if (did != null && !present.contains(did)) list.add(o);
                    }
                }
            } catch (Throwable t) {
                Log.e("子设备注入异常", t);
            }
        }
    };

    /* ---------- 2. Home 的 did 名单 ---------- */

    private static void hookHomeDidLists(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> home = HookUtil.findClassQuiet("com.xiaomi.smarthome.homeroom.model.Home", lpparam.classLoader);
        if (home == null) {
            HookReport.skip(GROUP_HOME, "Home", "类不存在");
            return;
        }
        for (String name : HOME_DID_GETTERS) {
            HookUtil.hookMethod(GROUP_HOME, home, name, APPEND_HOME_DIDS);
        }
        // 房间里的 did 名单：Room.getDids()，假设备要能落到指定房间
        Class<?> room = HookUtil.findClassQuiet("com.xiaomi.smarthome.homeroom.model.Room", lpparam.classLoader);
        if (room != null) {
            HookUtil.hookMethod(GROUP_HOME, room, "getDids", APPEND_ROOM_DIDS);
        } else {
            HookReport.skip(GROUP_HOME, "Room#getDids", "类不存在");
        }
    }

    @SuppressWarnings("unchecked")
    private static final XC_MethodHook APPEND_HOME_DIDS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String homeId = strField(param.thisObject, "id");
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled) return;
                List<String> want = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (d.homeId != null && !d.homeId.isEmpty() && homeId != null && !homeId.isEmpty()
                            && !d.homeId.equals(homeId)) continue;
                    want.add(d.did);
                }
                appendStrings(param, want, "home:" + methodName(param));
            } catch (Throwable t) {
                Log.v("家庭 did 名单注入跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    /**
     * 房间维度的 did 名单：只有配置里指定了房间、且和当前房间名一致（或没指定房间）才注入，
     * 这样界面上的「所属房间」选择器是真的能落到对应房间的。
     */
    @SuppressWarnings("unchecked")
    private static final XC_MethodHook APPEND_ROOM_DIDS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled) return;
                String roomName = firstNonEmpty(param.thisObject, "name", "roomName", "desc");
                List<String> want = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (d.roomName == null || d.roomName.isEmpty()) {
                        want.add(d.did);
                        continue;
                    }
                    if (roomName != null && roomName.equalsIgnoreCase(d.roomName.trim())) want.add(d.did);
                }
                appendStrings(param, want, "room:" + roomName);
            } catch (Throwable t) {
                Log.v("房间 did 名单注入跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    @SuppressWarnings("unchecked")
    private static void appendStrings(XC_MethodHook.MethodHookParam param, List<String> want, String where) {
        if (want.isEmpty()) return;
        List<Object> result = (List<Object>) param.getResult();
        if (result == null) {
            param.setResult(new ArrayList<Object>(want));
            CallLog.record(context(), CallLog.OP_INJECT, "dids", where, "", 0, want.size(), 0, methodName(param));
            return;
        }
        Set<Object> present = new HashSet<>(result);
        List<String> missing = new ArrayList<>();
        for (String did : want) if (!present.contains(did)) missing.add(did);
        if (missing.isEmpty()) return;
        int before = result.size();
        // 不直接改 Home 里那份活列表：Home.dids 是会被序列化/上报云端的字段，
        // 往里塞假 did 等于把假设备写进家庭数据，云端一合并就可能被当成真实成员。
        // 复制一份返回，注入只存在于本次读取。
        ArrayList<Object> merged = new ArrayList<>(result);
        merged.addAll(missing);
        param.setResult(merged);
        CallLog.record(context(), CallLog.OP_INJECT, "dids", where, "", before, merged.size(), 0, methodName(param));
    }

    private static String firstNonEmpty(Object target, String... names) {
        for (String n : names) {
            String v = strField(target, n);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /* ---------- 3. 插件进程用的 DeviceStat ---------- */

    /** 11.9.705 实测：这些是插件宿主 API 的实现类（接口 XmPluginHostApi 是抽象的，挂不住）。*/
    private static final String[] PLUGIN_IMPLS = {
            "com.xiaomi.smarthome.framework.plugin.mpk.PluginHostApiImpl",
            "com.xiaomi.smarthome.frame.plugin.host.IPluginHostApiImpl",
            "com.xiaomi.smarthome.framework.api.provider.core.RnPluginHostApiProviderImpl",
            "com.miot.provider.internal.impl.TinyPluginHostApiProviderImpl",
            "com.xiaomi.smarthome.plugin.DefaultPluginHostApi",
            "com.xiaomi.smarthome.framework.plugin.tiny.nativemodule.MIOTBaseJavaModuleTiny",
    };

    private static void hookPluginApi(XC_LoadPackage.LoadPackageParam lpparam) {
        for (String impl : PLUGIN_IMPLS) {
            Class<?> clazz = HookUtil.findClassQuiet(impl, lpparam.classLoader);
            if (clazz == null) {
                HookReport.skip(GROUP_PLUGIN, impl, "类不存在");
                continue;
            }
            for (Method m : clazz.getDeclaredMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (m.getName().equals("getDeviceList") && pts.length == 0 && Collection.class.isAssignableFrom(m.getReturnType())) {
                    HookUtil.hookMethod(GROUP_PLUGIN, clazz, m.getName(), APPEND_PLUGIN_LIST);
                } else if (m.getName().equals("getDeviceListV2") && pts.length == 1 && pts[0] == List.class) {
                    HookUtil.hookMethod(GROUP_PLUGIN, clazz, m.getName(), APPEND_PLUGIN_LIST_V2, List.class);
                } else if (isDeviceLookup(m)) {
                    // 按形状挂：getDeviceByDid / getDevice2ByDid 都是 (String)->Device[Stat]，
                    // 以前只认 getDeviceByDid 这个名字，getDevice2ByDid 一直是漏的
                    HookUtil.hookMethod(GROUP_PLUGIN, clazz, m.getName(), APPEND_PLUGIN_BY_DID, String.class);
                }
            }
        }
    }

    private static final XC_MethodHook APPEND_PLUGIN_LIST = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled) return;
                Object result = param.getResult();
                List<Object> stats = new ArrayList<>();
                List<String> statsDids = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    Object s = DeviceBeanFactory.deviceStatFor(context(), d, specOf(d), stamp(cfg));
                    if (s != null) {
                        stats.add(s);
                        statsDids.add(d.did);
                    }
                }
                if (stats.isEmpty()) return;
                if (result == null) {
                    param.setResult(stats);
                } else if (result instanceof Collection) {
                    Set<String> present = didsIn((Collection<?>) result);
                    for (int i = 0; i < stats.size(); i++) {
                        if (!present.contains(statsDids.get(i))) {
                            ((Collection<Object>) result).add(stats.get(i));
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e("插件列表注入异常", t);
            }
        }
    };

    /**
     * {@code getDeviceListV2(List<String> modelNames)}：插件按型号要设备。
     * 只有假设备的 model 在请求名单里才补，避免把无关型号塞进去。
     */
    private static final XC_MethodHook APPEND_PLUGIN_LIST_V2 = new XC_MethodHook() {
        @Override
        @SuppressWarnings("unchecked")
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.injectEnabled) return;
                List<String> models = (List<String>) param.args[0];
                if (models == null || models.isEmpty()) return;
                List<Object> stats = new ArrayList<>();
                for (SimDevice d : cfg.enabledDevices()) {
                    if (!models.contains(d.model)) continue;
                    Object s = DeviceBeanFactory.deviceStatFor(context(), d, specOf(d), stamp(cfg));
                    if (s != null) stats.add(s);
                }
                if (stats.isEmpty()) return;
                Object result = param.getResult();
                if (result == null) param.setResult(stats);
                else if (result instanceof Collection) ((Collection<Object>) result).addAll(stats);
            } catch (Throwable t) {
                Log.e("插件列表 V2 注入异常", t);
            }
        }
    };

    private static final XC_MethodHook APPEND_PLUGIN_BY_DID = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = (String) param.args[0];
                if (!Consts.isSimulatedDid(did)) return;
                SimConfig cfg = ConfigRepository.current();
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) return;
                // 返回类型可能是 DeviceStat，也可能是 Device —— 按调用方声明来造
                Class<?> want = returnType(param);
                Object s = beanFor(want, dev, cfg);
                if (s != null && (want == null || want.isInstance(s))) {
                    param.setResult(s);
                }
            } catch (Throwable t) {
                Log.e("插件 by-did 异常", t);
            }
        }
    };

    /* ---------- 公共小工具 ---------- */

    private static Set<String> didsIn(Collection<?> list) {
        Set<String> out = new HashSet<>();
        if (list == null) return out;
        for (Object o : list) {
            String did = strField(o, "did");
            if (did != null) out.add(did);
        }
        return out;
    }

    static String strField(Object target, String name) {
        Object v = HookUtil.readFieldQuiet(target, name);
        return v == null ? null : String.valueOf(v);
    }

    public static MiotSpec specOf(SimDevice d) {
        return SpecRepository.forModel(context(), d.model);
    }

    /** 配置指纹：内容变了缓存就失效。*/
    public static int stamp(SimConfig cfg) {
        return (int) (cfg.updatedAt * 31 + cfg.devices.size() * 7 + (cfg.injectEnabled ? 1 : 0));
    }

    public static Context context() {
        return SimContextHolder.get();
    }
}
