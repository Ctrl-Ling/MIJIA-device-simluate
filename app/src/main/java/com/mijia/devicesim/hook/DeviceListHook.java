/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
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
     * HookRegistry 在类加载时把可疑类丢进来。判定标准（11.9.705 里 {@code _m_j.c67} 的形状）：
     * 同时声明了 {@code X getDeviceByDid(String)} 和 {@code Collection getDeviceList()}。
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
            if (DID_GETTERS.contains(m.getName()) && pts.length == 1 && pts[0] == String.class
                    && isDeviceLike(rt)) {
                hasByDid = true;
            } else if (COLLECTION_GETTERS.contains(m.getName()) && pts.length == 0
                    && Collection.class.isAssignableFrom(rt)) {
                hasList = true;
            } else if (m.getName().equals("getSubDeviceByParentDid") && pts.length == 1 && pts[0] == String.class
                    && Collection.class.isAssignableFrom(rt)) {
                // Zigbee 子设备挂在网关下面时，网关页走的是这个方法而不是 getDeviceList
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
                if (!(result instanceof Collection) || !allLooksLikeDevice((Collection<?>) result)) return;
                appendDevices(param);
            } catch (Throwable t) {
                Log.e("设备集合注入异常", t);
            }
        }
    };

    /**
     * 空集合不动（无法判断元素类型，补进去可能污染别的语义的列表）；
     * 非空且全部是 Device/DeviceStat 才认为是设备列表。
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
                } else if (m.getName().equals("getDeviceByDid") && pts.length == 1 && pts[0] == String.class) {
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
                Object s = DeviceBeanFactory.deviceStatFor(context(), dev, specOf(dev), stamp(cfg));
                if (s != null) param.setResult(s);
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
