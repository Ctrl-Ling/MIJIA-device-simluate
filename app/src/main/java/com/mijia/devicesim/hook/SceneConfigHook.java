/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 16:51:19 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;

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
 * 把两个字符串字段换成假设备的值，列表逐项浅拷贝。
 *
 * <p><b>借用规则（v151，用户 2026-11-26 定）</b>：错数据比空白更误导，所以只有两种情况补：
 * <ol>
 *   <li><b>同型号</b>：账户里有同款真机，那份配置就是这型号的真实能力，直接克隆；</li>
 *   <li><b>同系列 + 能力校验</b>：两边都必须有该型号自己的真 spec，且服务/属性/动作/事件
 *       的 iid 与语义逐项一致（品牌、型号、版本段不参与比较）。一致才说明模板里引用的
 *       siid/piid 在目标型号上真的存在，才允许克隆。</li>
 * </ol>
 * 原来那两档「缓存里随便拿」「任意一份非空配置」已经删掉 —— 那种会把冰箱的触发项安到
 * 热水器上，正是“看着能用、其实在骗人”。校验不过就当没有，选设备页不出现这台设备。
 *
 * <p><b>v79 补充（11-12 现场实测）</b>：真正拦住假设备的是选设备页那一层 ——
 * {@code com.xiaomi.smarthome.specscene.manager.Oooo0#OooO0o0} 先拿 Home/Room 的 did 名单
 * （这两处已经由 {@code DeviceListHook} 注入过），再对每个 did 调 {@code _m_j.dxe#OooOO0(did)}
 * 解析成 Device。dxe 内部除了主表 {@code getDeviceByDid}，还会查一张「隐藏成员表」
 * （{@code o00Ooo(did)}）；非音箱组的设备只要在隐藏表里被查到，dxe 就直接 {@code return null}，
 * 假设备于是当场消失。所以这里直接在 dxe.OooOO0 的入口返回假 Device，绕开内部的所有闸。
 *
 * <p><b>v133 说明</b>：11.10.623 里 dxe 只剩 {@code OooOO0} 一个解析口，当年挂在 dxe 上的
 * 「不在当前家庭」三个判定口已搬进 {@code _m_j.lhr$OooO0O0} 的四个谓词。家庭/可用性放行
 * 统一由 {@link SceneDeviceStateHook} 负责，本类只保留解析口、落表补写和保存结果观测。
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

    private SceneConfigHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        hookSceneResolver(lpparam);
        hookSaveResult(lpparam);
        ClassLoader loader = lpparam.classLoader;
        // 落表补写 / 查表兜底：名字 + (Map,Map)/(String,String) 形状，改名后在同类里按混淆名模式兜底
        HookSniper.hook(loader, GROUP, CL_MANAGER, new String[]{"OooOOo0"},
                HookSniper.Shape.of(null, "Map", "Map"), "[oO0]{6,9}", AFTER_FILL);
        HookSniper.hook(loader, GROUP, CL_MANAGER, new String[]{"OooOOO0"},
                HookSniper.Shape.of(null, "String", "String"), "[oO0]{6,9}", AFTER_LOOKUP);
    }

    /* ---------- 0. did → 设备（自动化选设备页的唯一解析口） ---------- */

    private static void hookSceneResolver(XC_LoadPackage.LoadPackageParam lpparam) {
        // 单 String 入参 + 只认混淆名家族，避免在解析器里误挂别的 (String) 方法
        HookSniper.hook(lpparam.classLoader, GROUP, CL_SCENE_RESOLVER,
                new String[]{M_RESOLVE}, HookSniper.Shape.of(null, "String"),
                "OooO[Oo0]{2,3}", BEFORE_RESOLVE);
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

    /* ---------- 0.6 保存结果观测（只记录，不改流程） ---------- */

    private static void hookSaveResult(XC_LoadPackage.LoadPackageParam lpparam) {
        HookSniper.hook(lpparam.classLoader, GROUP, CL_SAVE_RESULT, new String[]{"invoke"},
                HookSniper.Shape.of(null, "Object"), "invoke", BEFORE_LOG_SAVE_RESULT);
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
                Log.d("场景配置补发: " + model + " (" + did + ") 档=" + kind[0]);
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
        }
        int added = sameModel + sameFamily;
        if (added > 0) {
            String detail = "补 " + added + " 台（同型号 " + sameModel
                    + "，同系列且能力一致 " + sameFamily + "）";
            HookReport.ok(GROUP, where, detail);
            CallLog.record(DeviceListHook.context(), CallLog.OP_INJECT, "scene-config", where,
                    "", present, present + added, 0, detail);
            XposedEntry.publishSoon();
        }
        if (missing > 0) {
            HookReport.skip(GROUP, where + "-missing", missing + " 台没有同型号配置、同系列能力对不上或缺 spec");
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
     * 挑模板：同型号 &gt; 同系列且能力校验通过，其余一律不借。
     *
     * <p>“能力校验”用两份真实 spec 比结构：服务/属性/动作/事件的 iid 和语义逐项一致，
     * 才说明模板里引用的 siid/piid 在目标型号上真的存在。缺 spec、比不过、没有同系列 ——
     * 都按没有处理（选设备页少一台，总比给一台假的强）。
     *
     * @param kindOut 出参，记下用的是哪一档：same-model / same-family / none
     */
    private static Object pickDonor(Object table, String model, String[] kindOut) {
        Object family = null;
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
                    if (family == null && familyKey != null && familyKey.equals(familyOf(dm))
                            && donorFitsModel(v, model)) {
                        family = v;
                    }
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
            if (!isConfig(e.getValue()) || items(e.getValue()).isEmpty()) continue;
            if (e.getKey().equals(model)) {
                kindOut[0] = "same-model";
                return e.getValue();
            }
            if (family == null && familyKey != null && familyKey.equals(familyOf(e.getKey()))
                    && donorFitsModel(e.getValue(), model)) {
                family = e.getValue();
            }
        }
        if (family != null) {
            kindOut[0] = "same-family";
            return family;
        }
        kindOut[0] = "none";
        return null;
    }

    /**
     * 这份模板能不能套到目标型号上。
     * <ul>
     *   <li>同型号：真机同款的云端配置，本来就是该型号的真实能力，直接过；</li>
     *   <li>否则必须两边都有该型号自己的真 spec，且能力结构完全一致。</li>
     * </ul>
     * 拿不到任何一边的 spec 就判不过 —— 宁可不补，也不拿别家的触发项糊上去。
     */
    private static boolean donorFitsModel(Object donor, String model) {
        String donorModel = str(donor, F_MODEL);
        if (donorModel == null || donorModel.isEmpty()) return false;
        if (donorModel.equals(model)) return true;
        android.content.Context ctx = DeviceListHook.context();
        if (ctx == null) return false;
        MiotSpec target = SpecRepository.forModel(ctx, model);
        MiotSpec source = SpecRepository.forModel(ctx, donorModel);
        String a = capabilitySignature(target);
        String b = capabilitySignature(source);
        if (a == null || b == null) {
            Log.d("场景模板校验: " + model + " vs " + donorModel + " 缺 spec，按不借处理");
            return false;
        }
        boolean same = a.equals(b);
        Log.d("场景模板校验: " + model + " vs " + donorModel + " 能力" + (same ? "一致，允许借用" : "不一致，不借"));
        return same;
    }

    /**
     * 能力签名：按 iid 列出每个服务/属性/动作/事件的语义类型。
     *
     * <p>urn 形如 {@code urn:miot-spec-v2:property:temperature:00000020:xiaomi-ymm5:2:0000D06A}，
     * 第 2~4 段才是语义（property / temperature / 00000020），后面是型号 token、版本和校验段，
     * 两个型号必然不同 —— 所以只取前四段比较，同能力不同型号的签名才会相等。
     */
    private static String capabilitySignature(MiotSpec spec) {
        if (spec == null || !spec.isValid()) return null;
        StringBuilder sb = new StringBuilder();
        for (MiotSpec.Service s : spec.services) {
            sb.append("S").append(s.iid).append(':').append(semantic(s.type)).append(';');
            for (MiotSpec.Prop p : s.properties) {
                sb.append("P").append(s.iid).append('.').append(p.iid)
                        .append(':').append(semantic(p.type)).append(';');
            }
            for (MiotSpec.Action a : s.actions) {
                sb.append("A").append(s.iid).append('.').append(a.iid)
                        .append(':').append(semantic(a.type)).append(';');
            }
            for (MiotSpec.Event e : s.events) {
                sb.append("E").append(s.iid).append('.').append(e.iid)
                        .append(':').append(semantic(e.type)).append(';');
            }
        }
        return sb.toString();
    }

    /** urn → {@code property:temperature:00000020}（去掉型号 token / 版本 / 校验段）。*/
    private static String semantic(String urn) {
        if (urn == null) return "";
        String[] p = urn.split(":");
        if (p.length < 5) return urn;
        return p[2] + ":" + p[3] + ":" + p[4];
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
        HookReport.skip(GROUP, "no-template", model
                + " 没有同型号真机的场景配置，同系列也没有能力一致的模板，按不补处理（宁缺勿错）");
        XposedEntry.publishSoon();
    }
}
