/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-13 00:33:49 +08:00
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
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化触发条件的「设备必须有场景配置」补丁。
 *
 * <p>为什么需要它 —— 米家「+ → 自动化 → 创建自动化 → 添加触发条件 → 家居设备」这条路上，
 * 设备不是直接列出来的，而是先过一道闸：
 * {@code _m_j.lo0.OooOO0(device, null)} 里拿
 * {@code OooOOO.OooOO0O().OooOOO0(device.model, device.did)} 去查场景配置表，
 * <b>查不到就返回 null，设备当场被丢掉</b>。这张表（{@code OooOOO.OooO0OO}）是云端
 * launch json 按「真实设备」填的，假设备天然没有条目，所以列表里永远看不到。
 *
 * <p>做法：不动云端也不动数据库，只在内存里给假设备补一份配置 ——
 * <ol>
 *   <li>hook {@code OooOOO#OooOOo0(Map,Map)}（云端配置落表那一步），落完表后按假设备清单补齐；</li>
 *   <li>hook {@code OooOOO#OooOOO0(String,String)}（按 model+did 查表），漏了也当场补上再返回，
 *       顺带兜住 {@code OooO0oO()} 十分钟清表后的窗口期。</li>
 * </ol>
 *
 * <p>补什么 —— 克隆同型号真实设备那份 {@code _m_j.b43}（字段就 model、did 和四个列表），
 * 把两个字符串字段换成假设备的值，列表逐项浅拷贝。同型号找不到就退到同系列
 * （{@code yeelink.light} 这种两段前缀），再不行借任意一份非空配置，并在报告里标明来源，
 * 方便他知道哪几台是「借壳」的。
 *
 * <p><b>v79 补充（11-12 现场实测）</b>：真正拦住假设备的是选设备页那一层 ——
 * {@code com.xiaomi.smarthome.specscene.manager.Oooo0#OooO0o0} 先拿 Home/Room 的 did 名单
 * （这两处已经由 {@code DeviceListHook} 注入过），再对每个 did 调 {@code _m_j.dxe#OooOO0(did)}
 * 解析成 Device。dxe 内部除了主表 {@code getDeviceByDid}，还会查一张「隐藏成员表」
 * （{@code o00Ooo(did)}）；非音箱组的设备只要在隐藏表里被查到，dxe 就直接 {@code return null}，
 * 假设备于是当场消失。所以这里直接在 dxe.OooOO0 的入口返回假 Device，绕开内部的所有闸。
 */
public final class SceneConfigHook {

    private static final String GROUP = "scene-config";

    /** 场景配置表所在的类（11.9.705：SceneManagerV2）。*/
    private static final String CL_MANAGER = "com.xiaomi.smarthome.specscene.manager.OooOOO";
    /** 单台设备的场景配置：model + did + 四个列表。*/
    private static final String CL_CONFIG = "_m_j.b43";
    /**
     * 场景页面里「did → 设备」的唯一解析入口（11-12 现场实测）。
     * <p>自动化选设备页 {@code EditControllableDeviceList} 的 ViewModel 每拿到一个 did 都先问它；
     * 它内部会先查主表、再查「隐藏成员表」，两条路都查不到才算失败。假设备在隐藏表那条路
     * 一旦被查到，非音箱组的分支会直接 {@code return null} —— 所以这里直接在最外层返回假对象。
     */
    private static final String CL_SCENE_RESOLVER = "_m_j.dxe";
    /** dxe 里的解析方法：{@code static Device OooOO0(String did)}。*/
    private static final String M_RESOLVE = "OooOO0";
    /**
     * 场景编辑页/首页自动化卡片判断「这台设备到底在不在当前家庭」的三个判定口
     * （11.9.705 {@code _m_j.dxe}）。三者都返回 <b>true = 有问题</b>：
     * <ul>
     *   <li>{@code OooooOo(Device, String homeId)} —— 「不在当前家庭」文案、行变灰的源头；</li>
     *   <li>{@code OooooOO(dxe, Device)} —— 分组/共享视角下再看一遍；</li>
     *   <li>{@code o000oOoO(Device)} —— 设备整体是否可用（家庭表里没有 permitLevel 就判无效）。</li>
     * </ul>
     * 假设备的 did 不在云端家庭表里，三条全中，于是自动化编辑页黄条 + 行灰 + 保存前校验直接拦死。
     * 这里只对「启用的假 did」把结果改成 false（视为合法），真实设备一个字节都不动。
     */
    private static final String M_NOT_IN_HOME = "OooooOo";
    private static final String M_NOT_IN_GROUP = "OooooOO";
    private static final String M_DEVICE_INVALID = "o000oOoO";
    /**
     * 保存结果回调（11.9.705 {@code _m_j.awe}，合成 lambda）。
     * <p>case 0 收到 Integer（保存前检查），case 1 收到 {@code _m_j.jc5} 错误对象（云端拒绝）。
     * 这里只「看」不「改」：把错误码打进 LSPosed 日志，现场一复现就知道云端到底卡在哪一条，
     * 省得来回猜。
     */
    private static final String CL_SAVE_RESULT = "_m_j.awe";
    private static final String F_CALLBACK_CASE = "OooOo";
    private static final String F_ERR_CODE = "OooO00o";
    private static final String F_ERR_MSG = "OooO0O0";

    /** OooOOO 里的表：{@code model+did -> b43}。*/
    private static final String F_TABLE = "OooO0OO";
    /** b43.model。*/
    private static final String F_MODEL = "OooO00o";
    /** b43.did。*/
    private static final String F_DID = "OooO0O0";
    /** b43 的触发项列表（元素是 {@code _m_j.u33}，非空才说明这份配置能用）。*/
    private static final String F_TRIGGERS = "OooO0OO";

    /** 上次落表时的模板备份：表被十分钟 TTL 清掉后还能补。*/
    private static final Map<String, Object> sDonors = new ConcurrentHashMap<>();
    /** 已经报过「缺模板」的型号，别把报告页刷爆。*/
    private static final Set<String> sReportedMissing = new HashSet<>();
    /** 走「借壳」来源报过的型号。*/
    private static final Set<String> sReportedBorrowed = new HashSet<>();

    private SceneConfigHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookSceneResolver(lpparam);
        hookHomeGuard(lpparam);
        hookSaveResult(lpparam);
        Class<?> mgr = HookUtil.findClassQuiet(CL_MANAGER, lpparam.classLoader);
        if (mgr == null) {
            HookReport.skip(GROUP, CL_MANAGER, "类不存在（场景配置表可能已改版）");
            return;
        }
        boolean fill = HookUtil.hookMethod(GROUP, mgr, "OooOOo0", AFTER_FILL, Map.class, Map.class);
        boolean lookup = HookUtil.hookMethod(GROUP, mgr, "OooOOO0", AFTER_LOOKUP, String.class, String.class);
        if (fill || lookup) {
            HookReport.ok(GROUP, "install", "落表补写=" + fill + "，查表兜底=" + lookup);
        } else {
            HookReport.fail(GROUP, "install", "两个挂点都没挂上");
        }
    }

    /* ---------- 0. did → 设备（自动化选设备页的唯一解析口） ---------- */

    private static void hookSceneResolver(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> resolver = HookUtil.findClassQuiet(CL_SCENE_RESOLVER, lpparam.classLoader);
        if (resolver == null) {
            HookReport.skip(GROUP, CL_SCENE_RESOLVER + "#" + M_RESOLVE, "类不存在（选设备页解析口可能已改版）");
            return;
        }
        if (HookUtil.hookMethod(GROUP, resolver, M_RESOLVE, BEFORE_RESOLVE, String.class)) {
            HookReport.ok(GROUP, "resolve", "选设备页 did→设备 直通");
        }
    }

    private static final XC_MethodHook BEFORE_RESOLVE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object arg = param.args == null || param.args.length == 0 ? null : param.args[0];
                String did = arg instanceof String ? (String) arg : null;
                if (!Consts.isSimulatedDid(did)) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) return;
                Object bean = DeviceBeanFactory.deviceFor(DeviceListHook.context(), dev,
                        DeviceListHook.specOf(dev), DeviceListHook.stamp(cfg));
                if (bean == null) return;
                Class<?> want = DeviceListHook.returnType(param);
                if (want == null || want.isInstance(bean)) {
                    param.setResult(bean);
                }
            } catch (Throwable t) {
                Log.e("选设备页解析注入异常", t);
            }
        }
    };

    /* ---------- 0.5 家庭归属校验（黄条 / 灰行 / 保存前拦死） ---------- */

    private static void hookHomeGuard(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> dxe = HookUtil.findClassQuiet(CL_SCENE_RESOLVER, lpparam.classLoader);
        Class<?> device = HookUtil.findClassQuiet(DeviceBeanFactory.CL_DEVICE, lpparam.classLoader);
        if (dxe == null || device == null) {
            HookReport.skip(GROUP, "device-guard",
                    "dxe=" + (dxe != null) + " Device=" + (device != null) + "，家庭校验口不可用");
            return;
        }
        // 真实类签名：OooooOo(Device,String) / OooooOO(dxe,Device) / o000oOoO(Device)
        boolean a = HookUtil.hookMethod(GROUP, dxe, M_NOT_IN_HOME, homeGuard(0), device, String.class);
        boolean b = HookUtil.hookMethod(GROUP, dxe, M_NOT_IN_GROUP, homeGuard(1), dxe, device);
        boolean c = HookUtil.hookMethod(GROUP, dxe, M_DEVICE_INVALID, homeGuard(0), device);
        HookReport.ok(GROUP, "device-guard",
                "不在家庭=" + a + "，分组校验=" + b + "，设备无效=" + c);
    }

    /**
     * 三个判定口共用同一套逻辑，只有设备参数的位置不同 ——
     * {@code OooooOO} 的第 0 个参数是 dxe 实例，设备在第 1 个；另外两个都在第 0 个。
     */
    private static XC_MethodHook homeGuard(final int index) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (isSimulatedArgument(param.args, index)) {
                        param.setResult(Boolean.FALSE);
                    }
                } catch (Throwable t) {
                    Log.e("家庭校验放行异常", t);
                }
            }
        };
    }

    /** args[index] 是不是「已启用的假设备」。索引越界/类型不符都当否。*/
    private static boolean isSimulatedArgument(Object[] args, int index) {
        if (args == null || args.length <= index) return false;
        Object dev = args[index];
        if (dev == null) return false;
        Object didObj = HookUtil.readFieldQuiet(dev, "did");
        if (!(didObj instanceof String)) return false;
        String did = (String) didObj;
        if (!Consts.isSimulatedDid(did)) return false;
        SimConfig cfg = ConfigRepository.current();
        return cfg != null && cfg.injectEnabled && cfg.findEnabledByDid(did) != null;
    }

    /* ---------- 0.6 保存结果观测（只记录，不改流程） ---------- */

    private static void hookSaveResult(XC_LoadPackage.LoadPackageParam lpparam) {
        if (HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_SAVE_RESULT, "invoke",
                BEFORE_LOG_SAVE_RESULT, Object.class)) {
            HookReport.ok(GROUP, "save-result", "保存错误码写日志");
        }
    }

    private static final XC_MethodHook BEFORE_LOG_SAVE_RESULT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                int which = HookUtil.readIntField(param.thisObject, F_CALLBACK_CASE, -1);
                Object r = param.args == null || param.args.length == 0 ? null : param.args[0];
                if (which == 1) {
                    Object code = HookUtil.readFieldQuiet(r, F_ERR_CODE);
                    Object msg = HookUtil.readFieldQuiet(r, F_ERR_MSG);
                    Log.d("场景保存失败回调: code=" + code + " msg=" + msg);
                } else if (which == 0) {
                    Log.d("场景保存前检查: " + r);
                }
            } catch (Throwable t) {
                Log.v("保存结果观测异常: " + t);
            }
        }
    };

    /* ---------- 1. 云端配置落表之后 ---------- */

    private static final XC_MethodHook AFTER_FILL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                fillMissing(param.thisObject, "fill");
            } catch (Throwable t) {
                Log.e("场景配置落表补写异常", t);
            }
        }
    };

    /* ---------- 2. 按 model+did 查表 ---------- */

    private static final XC_MethodHook AFTER_LOOKUP = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param.getResult() != null) return;
                String model = param.args[0] instanceof String ? (String) param.args[0] : null;
                String did = param.args[1] instanceof String ? (String) param.args[1] : null;
                if (!Consts.isSimulatedDid(did)) return;

                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null || dev.model == null || dev.model.isEmpty()) return;
                if (model == null || model.isEmpty()) model = dev.model;

                Object table = HookUtil.readFieldQuiet(param.thisObject, F_TABLE);
                String[] kind = new String[1];
                Object donor = pickDonor(table, model, kind);
                Object clone = donor == null ? null : cloneFor(donor, model, did);
                if (clone == null) {
                    reportMissingOnce(model);
                    return;
                }
                if (table instanceof Map) {
                    // @SuppressWarnings 不能加在匿名类里，这里是原始类型
                    ((Map) table).put(model + did, clone);
                }
                param.setResult(clone);
                noteBorrowed(model, did, kind[0]);
            } catch (Throwable t) {
                Log.e("场景配置查表兜底异常", t);
            }
        }
    };

    /* ---------- 补写逻辑 ---------- */

    /**
     * 给所有启用的假设备补场景配置。
     *
     * @param where 日志/报告里的触发点（fill / lookup）
     * @return 本次新增的条数
     */
    private static int fillMissing(Object manager, String where) {
        SimConfig cfg = ConfigRepository.current();
        if (cfg == null || !cfg.injectEnabled) return 0;
        Object tableObj = HookUtil.readFieldQuiet(manager, F_TABLE);
        if (!(tableObj instanceof Map)) return 0;
        Map table = (Map) tableObj;
        snapshotDonors(table);

        int sameModel = 0;
        int sameFamily = 0;
        int cached = 0;
        int borrowed = 0;
        int missing = 0;
        int present = 0;
        for (SimDevice d : cfg.enabledDevices()) {
            if (d == null || d.did == null || d.did.isEmpty()) continue;
            if (d.model == null || d.model.isEmpty()) continue;
            String key = d.model + d.did;
            if (table.containsKey(key)) {
                present++;
                continue;
            }
            String[] kind = new String[1];
            Object donor = pickDonor(table, d.model, kind);
            Object clone = donor == null ? null : cloneFor(donor, d.model, d.did);
            if (clone == null) {
                missing++;
                reportMissingOnce(d.model);
                continue;
            }
            table.put(key, clone);
            if ("same-model".equals(kind[0])) sameModel++;
            else if ("same-family".equals(kind[0])) sameFamily++;
            else if ("cached".equals(kind[0])) cached++;
            else borrowed++;
            if ("borrowed".equals(kind[0])) noteBorrowed(d.model, d.did, kind[0]);
        }
        int added = sameModel + sameFamily + cached + borrowed;
        if (added > 0) {
            String detail = "补 " + added + " 台（同型号 " + sameModel + "，同系列 " + sameFamily
                    + "，缓存 " + cached + "，借壳 " + borrowed + "）";
            HookReport.ok(GROUP, where, detail);
            CallLog.record(DeviceListHook.context(), CallLog.OP_INJECT, "scene-config", where,
                    "", present, present + added, 0, detail);
            XposedEntry.publishSoon();
        }
        if (missing > 0) {
            HookReport.skip(GROUP, where + "-missing", missing + " 台没有可借的模板");
            XposedEntry.publishSoon();
        }
        return added;
    }

    /** 落表之后把可用的配置留一份备份，TTL 清表后仍能补。*/
    private static void snapshotDonors(Map table) {
        try {
            for (Object v : table.values()) {
                if (!isConfig(v)) continue;
                String model = str(v, F_MODEL);
                if (model == null || model.isEmpty()) continue;
                if (items(v).isEmpty()) continue;
                sDonors.put(model, v);
            }
        } catch (Throwable t) {
            Log.v("场景配置模板备份失败: " + HookUtil.shortMsg(t));
        }
    }

    /**
     * 挑模板：同型号 &gt; 同系列（两段前缀）&gt; 备份 &gt; 任意非空配置。
     *
     * @param kindOut 出参，记下用的是哪一档，报告里要写清楚
     */
    private static Object pickDonor(Object table, String model, String[] kindOut) {
        Object family = null;
        Object any = null;
        String familyKey = familyOf(model);
        if (table instanceof Map) {
            try {
                for (Object v : ((Map) table).values()) {
                    if (!isConfig(v)) continue;
                    if (items(v).isEmpty()) continue;
                    String dm = str(v, F_MODEL);
                    if (dm == null || dm.isEmpty()) continue;
                    if (dm.equals(model)) {
                        kindOut[0] = "same-model";
                        return v;
                    }
                    if (family == null && familyKey != null && familyKey.equals(familyOf(dm))) {
                        family = v;
                    }
                    if (any == null) any = v;
                }
            } catch (Throwable t) {
                Log.v("场景配置模板遍历失败: " + HookUtil.shortMsg(t));
            }
        }
        if (family != null) {
            kindOut[0] = "same-family";
            return family;
        }
        for (Map.Entry<String, Object> e : sDonors.entrySet()) {
            String dm = e.getKey();
            if (dm.equals(model)) {
                kindOut[0] = "cached";
                return e.getValue();
            }
            if (family == null && familyKey != null && familyKey.equals(familyOf(dm))) {
                family = e.getValue();
            }
        }
        if (family != null) {
            kindOut[0] = "cached";
            return family;
        }
        if (any != null) {
            kindOut[0] = "borrowed";
            return any;
        }
        kindOut[0] = "none";
        return null;
    }

    /** 两段前缀：{@code yeelink.light.lamp1} → {@code yeelink.light}。*/
    private static String familyOf(String model) {
        if (model == null) return null;
        int first = model.indexOf('.');
        if (first <= 0) return null;
        int second = model.indexOf('.', first + 1);
        return second > first ? model.substring(0, second) : model;
    }

    /** 克隆一份配置，只把 model/did 换成假设备的，列表逐项浅拷贝。*/
    private static Object cloneFor(Object donor, String model, String did) {
        try {
            String donorModel = str(donor, F_MODEL);
            String donorDid = str(donor, F_DID);
            Object clone = donor.getClass().getDeclaredConstructor().newInstance();
            for (Field f : donor.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(donor);
                if (v instanceof String) {
                    String s = (String) v;
                    if (donorModel != null && donorModel.equals(s)) {
                        v = model;
                    } else if (donorDid != null && donorDid.equals(s)) {
                        v = did;
                    }
                } else if (v instanceof Collection) {
                    v = new ArrayList<Object>((Collection<?>) v);
                }
                f.set(clone, v);
            }
            HookUtil.setFieldQuiet(clone, F_MODEL, model);
            HookUtil.setFieldQuiet(clone, F_DID, did);
            return clone;
        } catch (Throwable t) {
            Log.v("场景配置克隆失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- 小工具 ---------- */

    private static boolean isConfig(Object o) {
        return o != null && CL_CONFIG.equals(o.getClass().getName());
    }

    private static String str(Object target, String field) {
        Object v = HookUtil.readFieldQuiet(target, field);
        return v == null ? null : String.valueOf(v);
    }

    /** 触发项列表；拿不到就返回空集合（这份配置不可用）。*/
    private static Collection<?> items(Object config) {
        Object v = HookUtil.readFieldQuiet(config, F_TRIGGERS);
        return v instanceof Collection ? (Collection<?>) v : java.util.Collections.emptyList();
    }

    private static void reportMissingOnce(String model) {
        synchronized (sReportedMissing) {
            if (!sReportedMissing.add(model)) return;
        }
        HookReport.skip(GROUP, "no-template", model + " 一条真实场景配置都没有，先在这台手机上加一台同型号真实设备再试");
        XposedEntry.publishSoon();
    }

    private static void noteBorrowed(String model, String did, String kind) {
        if (!"borrowed".equals(kind)) return;
        synchronized (sReportedBorrowed) {
            if (!sReportedBorrowed.add(model)) return;
        }
        HookReport.skip(GROUP, "borrowed", model + " 没有同型号/同系列配置，借了一份别的设备的（" + did + "）");
        XposedEntry.publishSoon();
    }
}
