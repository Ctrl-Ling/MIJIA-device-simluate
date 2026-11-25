/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 21:25:35 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigChannel;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotLabels;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 触发能力列表（TCA）的补数据层。
 *
 * <p>「添加触发条件 → 家居设备 → 选设备」之后那一页要列的是这个设备有哪些可触发能力，
 * 数据来源只有两张本地表：
 * <pre>
 *   device_config_v3_tca_simple   按 model 给出该型号支持哪些 sc_id / sa_id
 *   device_launch_configs_v3      按 sc_id 给出每条触发条件的 detail JSON
 * </pre>
 * 假设备的 model 不在米家同步下来的那十几个型号里，按 model 一查就是空 → 仓库返回
 * {@code rnd}（空/失败）→ 页面提示「当前设备暂无额外自动化能力可选择」。
 *
 * <p>这里从三个位置补：
 * <ol>
 *   <li><b>过期判断</b> —— {@code LocalDataResource#isConfigExpired(did)} 是「当前时间 - 该 did 的
 *       上次同步时间 &gt; 24h」。假 did 在 access 表里根本没有时间戳，永远判过期，一过期就走云端
 *       远程配置；云端当然没有这台设备，于是必然失败。假 did 直接返回 false，让它走本地缓存。</li>
 *   <li><b>型号查询</b> —— {@code ModelSimpleConfigDao} 查出空表时，用库里现成型号的 id 名单
 *       合成一行 {@code cif(假型号, donorScIds, donorSaIds)} 塞进去。donor 只允许同品类
 *       （型号第 2 段相同，例如 linp.light.* 互借、mijia.vacuum.* 借 ijai.vacuum.*），
 *       同品类一台都没有时宁可不借，也不跨品类拿别人的能力（否则热水器会冒出「开启空调」）。</li>
 *   <li><b>详情 JSON</b> —— 建 {@code hif} 之前（{@code osf.Oooooo}）把 JSON 里 donor 的型号替换成
 *       假设备的型号，并把 {@code can_sustained} 强制为 true：视图模型只保留这个字段为 true 的项，
 *       不置 true 就算塞进去也会被过滤掉。</li>
 * </ol>
 * 每一步都只对「本模块配置里启用着的假设备」生效，真实设备一行数据都不改。
 *
 * <p><b>执行动作那一侧</b>（创建自动化 → 添加执行动作 → 家居设备 → 选设备）走的是同一个仓库
 * {@code OooOo00}，只是缓存方法换成 {@code OooOO0(did)}（getActionListCacheBy），远端是
 * {@code OooOO0O}。库里对应的两张表是：
 * <pre>
 *   device_config_v3_tca_simple.a   按 model 给出该型号支持哪些 sa_id
 *   device_action_configs_v3        按 sa_id 给出每条执行动作的 detail JSON
 * </pre>
 * 动作项的构造链跟触发项不一样：{@code _m_j.awa#Oooo(homeId, detailJson)} 建
 * {@code _m_j.oOO00000}，再塞进 {@code _m_j.a94(did)}，最后同样套 {@code und(list)} 返回。
 * donor 只借「a 列有真 id」的同品类型号，一条都借不到就按设备自己的 MIoT spec 生成
 * （可写属性 → set_properties，spec action → action）。
 */
public final class SceneTcaDataHook {

    private SceneTcaDataHook() {
    }

    private static final String GROUP = "scene-tca-data";
    private static final String CL_EXPIRE = "com.xiaomi.smarthome.scene.dao.OooO";
    private static final String M_EXPIRE = "OooO";
    private static final String CL_MODEL_DAO = "com.xiaomi.smarthome.scene.dao.OooOO0";
    private static final String M_MODEL_QUERY = "OooO00o";
    private static final String CL_CIF = "_m_j.cif";
    private static final String CL_GP4 = "_m_j.gp4";
    private static final String CL_UND = "_m_j.und";
    private static final String CL_OSF = "_m_j.osf";
    private static final String M_BUILD_HIF = "Oooooo";
    private static final String CL_CONT = "kotlin.coroutines.jvm.internal.ContinuationImpl";
    /** 仓库层：进去看页面到底走的是缓存、远端还是直接空。*/
    private static final String CL_REPO = "com.xiaomi.smarthome.specscene.manager.OooOo00";
    private static final String M_CACHE_BY = "OooOoo0";
    private static final String M_REMOTE = "OooOoo";
    /** 执行动作那一侧：缓存查询与远端刷新（SpecSceneConfigRepository#getActionList*）。*/
    private static final String M_ACTION_CACHE_BY = "OooOO0";
    private static final String CL_AWA = "_m_j.awa";
    private static final String M_BUILD_ACTION = "Oooo";
    private static final String CL_A94 = "_m_j.a94";

    /** 从 TCA 库里读到的型号行：model -> {scIds JSON, saIds JSON}。只缓存成功读到的那一份。*/
    private static volatile Map<String, String[]> sCatalog;
    /** 米家的 ClassLoader：{@code _m_j.cif} 这类混淆类只在这个 loader 里找得到。*/
    private static ClassLoader sLoader;

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        sLoader = lpparam.classLoader;
        Class<?> cont = HookUtil.findClassQuiet(CL_CONT, lpparam.classLoader);
        if (cont == null) {
            HookReport.skip(GROUP, "install", "找不到 ContinuationImpl，无法挂 suspend 方法");
            return;
        }

        boolean expire = HookUtil.hookMethod(GROUP,
                HookUtil.findClassQuiet(CL_EXPIRE, lpparam.classLoader), M_EXPIRE,
                AFTER_EXPIRE, String.class, cont);

        boolean models = HookUtil.hookMethod(GROUP,
                HookUtil.findClassQuiet(CL_MODEL_DAO, lpparam.classLoader), M_MODEL_QUERY,
                AFTER_MODELS, java.util.ArrayList.class, cont);

        boolean hif = HookUtil.hookMethod(GROUP,
                HookUtil.findClassQuiet(CL_OSF, lpparam.classLoader), M_BUILD_HIF,
                BEFORE_HIF, String.class, String.class, JSONObject.class);

        // 623 的 OooOo00 上已经没有 OooOoo0/OooOoo（旧触发缓存与远端追踪），这两条挂点在
        // 这一版是死的；能力页的生效点统一由 SceneTcaSeedHook 负责，这里只留仍存在的观测点。
        // 执行动作侧：同一仓库的缓存方法 (String did, ContinuationImpl)
        boolean action = HookUtil.hookMethod(GROUP,
                HookUtil.findClassQuiet(CL_REPO, lpparam.classLoader), M_ACTION_CACHE_BY,
                ACTION_CACHE_BY, String.class, cont);

        Log.d("TCA 挂点安装 进程=" + HookReport.process() + " 过期=" + expire + " 型号=" + models
                + " 详情=" + hif + " 动作=" + action);
        if (expire || models || hif || action) {
            HookReport.ok(GROUP, "install", "过期=" + expire + "，型号=" + models + "，详情=" + hif
                    + "，动作=" + action);
        } else {
            HookReport.fail(GROUP, "install", "挂点一个都没上");
        }
    }

    /** 诊断行：只进调用流水和 Xposed 日志，不影响任何返回值。带进程名，才能分清哪个进程走的。*/
    private static void probe(String did, String model, String key, Object input, Object output, String note) {
        String line = "TCA-PROBE " + HookReport.process() + " | " + key
                + " did=" + did + " model=" + model
                + " in=" + input + " out=" + output + " | " + note;
        Log.d(line);
        try {
            Context ctx = context();
            if (ctx == null) return;
            CallLog.record(ctx, CallLog.OP_EVENT, did, model, key, input, output, 0,
                    "[probe " + HookReport.process() + "] " + note);
        } catch (Throwable ignored) {
        }
    }

    /* ---------- 0. 生效点 + 诊断：同一个挂点，before 接管、after 留证据 ---------- */

    private static final XC_MethodHook CACHE_BY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = param.args.length > 0 && param.args[0] instanceof String
                        ? (String) param.args[0] : null;
                SimDevice dev = fakeDeviceByDid(did);
                if (dev == null) return;

                List<Object> items = buildTriggerItems(dev);
                probe(did, dev.model, "cacheBy-build", "items=" + items.size(),
                        items.isEmpty() ? "空" : "已接管", "整段兜底组装触发项");
                if (items.isEmpty()) return;
                Object und = newUnd(items);
                if (und == null) return;
                param.setResult(und);
                Log.d("TCA 触发能力整段接管: " + did + " ← " + items.size() + " 项");
                CallLog.record(context(), CallLog.OP_INJECT, did, dev.model, "tca-build",
                        "", items.size(), 0, "触发能力页整段兜底");
            } catch (Throwable t) {
                Log.e("TCA 整段兜底异常", t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String did = param.args.length > 0 && param.args[0] instanceof String
                        ? (String) param.args[0] : null;
                Object res = param.getResult();
                String kind = res == null ? "null" : res.getClass().getSimpleName();
                probe(did, "", "cacheBy", kind,
                        kind.equals("und") ? "有结果" : "qnd=过期 / rnd=空失败 / tnd=远端无配置", "仓库缓存查询返回");
            } catch (Throwable ignored) {
            }
        }
    };

    /* ---------- 0b. 执行动作：同样是 before 接管 + after 留证据 ---------- */
    /*
     * OooOo00#OooOO0(did, cont) 就是「添加执行动作 → 选设备」那一页的缓存查询：
     * 表里查不到返回 rnd、过期返回 qnd，页面都当空处理。假设备两张表都没有行，
     * 所以和触发侧一样，直接把整份结果造好返回。
     */

    private static final XC_MethodHook ACTION_CACHE_BY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String did = param.args.length > 0 && param.args[0] instanceof String
                        ? (String) param.args[0] : null;
                SimDevice dev = fakeDeviceByDid(did);
                if (dev == null) return;

                List<Object> items = buildActionItems(dev);
                probe(did, dev.model, "action-build", "items=" + items.size(),
                        items.isEmpty() ? "空" : "已接管", "整段兜底组装执行动作");
                if (items.isEmpty()) return;
                Object und = newUnd(items);
                if (und == null) return;
                param.setResult(und);
                Log.d("TCA 执行动作整段接管: " + did + " ← " + items.size() + " 项");
                CallLog.record(context(), CallLog.OP_INJECT, did, dev.model, "tca-action",
                        "", items.size(), 0, "执行动作页整段兜底");
            } catch (Throwable t) {
                Log.e("TCA 执行动作整段兜底异常", t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String did = param.args.length > 0 && param.args[0] instanceof String
                        ? (String) param.args[0] : null;
                Object res = param.getResult();
                String kind = res == null ? "null" : res.getClass().getSimpleName();
                probe(did, "", "actionCacheBy", kind,
                        kind.equals("und") ? "有结果" : "qnd=过期 / rnd=空失败 / tnd=设备不在",
                        "仓库动作缓存查询返回");
            } catch (Throwable ignored) {
            }
        }
    };

    private static final XC_MethodHook AFTER_REMOTE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String model = param.args[0] instanceof String ? (String) param.args[0] : null;
                String did = param.args[1] instanceof String ? (String) param.args[1] : null;
                Object res = param.getResult();
                String kind = res == null ? "null" : res.getClass().getSimpleName();
                probe(did, model, "remote", kind, kind, "仓库远端查询返回");
            } catch (Throwable ignored) {
            }
        }
    };

    /* ---------- 1. 假 did 的缓存永不过期 ---------- */

    private static final XC_MethodHook AFTER_EXPIRE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object res = param.getResult();
                if (!(res instanceof Boolean)) return;   // 挂起那次返回 COROUTINE_SUSPENDED，跳过
                String did = param.args[0] instanceof String ? (String) param.args[0] : null;
                boolean hit = fakeDeviceByDid(did) != null;
                probe(did, "", "expire", String.valueOf(res), hit,
                        "本地缓存过期判断（hit=模块配置里有这台设备）");
                if (!((Boolean) res)) return;
                if (!hit) return;
                param.setResult(Boolean.FALSE);
                Log.d("TCA 缓存过期判断对假设备放行: " + did);
            } catch (Throwable t) {
                Log.v("TCA 过期判断注入跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 2. 型号查询：空表时合成一行 ---------- */

    @SuppressWarnings("unchecked")
    private static final XC_MethodHook AFTER_MODELS = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object res = param.getResult();
                if (!(res instanceof List)) return;
                if (!(param.args[0] instanceof List)) return;
                List<String> asked = (List<String>) param.args[0];
                List<String> fakeModels = fakeModelsIn(asked);
                List<Object> rows = (List<Object>) res;
                Map<String, String[]> catalog = catalog();
                probe("", asked.isEmpty() ? "" : String.valueOf(asked.get(0)), "models",
                        "asked=" + asked.size() + " hit=" + fakeModels.size(),
                        "rows=" + rows.size() + " catalog=" + catalog.size(),
                        "型号查询（hit=查的型号里有几台假设备）");
                if (fakeModels.isEmpty()) return;
                if (catalog.isEmpty()) return;

                List<Object> merged = null;
                for (String model : fakeModels) {
                    if (containsModel(rows, model)) continue;
                    String[] donor = pickDonor(catalog, model);
                    if (donor == null) continue;
                    Object row = newCif(model, donor[0], donor[1]);
                    if (row == null) continue;
                    if (merged == null) merged = new ArrayList<>(rows);
                    merged.add(row);
                    Log.d("TCA 型号行合成: " + model + " ← " + donor[2]);
                }
                if (merged == null) return;
                param.setResult(merged);
                CallLog.record(context(), CallLog.OP_INJECT, "tca-models", "OooOO0#OooO00o",
                        "", rows.size(), merged.size(), 0, M_MODEL_QUERY);
            } catch (Throwable t) {
                Log.v("TCA 型号行注入跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static List<String> fakeModelsIn(List<String> asked) {
        List<String> out = new ArrayList<>();
        if (asked == null || asked.isEmpty()) return out;
        List<SimDevice> known = enabledDevices();
        for (String m : asked) {
            if (m == null || m.isEmpty()) continue;
            for (SimDevice d : known) {
                if (m.equals(d.model) && !out.contains(m)) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 本进程认得出的启用设备。
     *
     * <p>为什么不只用 {@link ConfigRepository#current()}：场景页有可能跑在
     * {@code :plugin0} 这类子进程里，那儿的仓库可能还没被 init 过（首页没跑过就不会读配置）。
     * 这时候直接照 Provider 再读一遍，读到就用，读不到才算没有 —— 否则「配置没加载」
     * 会被误当成「这台不是假设备」，所有注入都静默跳过。
     */
    private static List<SimDevice> enabledDevices() {
        SimConfig cfg = ConfigRepository.current();
        List<SimDevice> list = cfg == null ? new ArrayList<SimDevice>() : cfg.enabledDevices();
        if (!list.isEmpty()) return list;
        Context ctx = context();
        if (ctx == null) return list;
        try {
            String json = ConfigChannel.readConfig(ctx);
            SimConfig raw = SimConfig.parse(json);
            if (raw != null) {
                List<SimDevice> backup = raw.enabledDevices();
                if (!backup.isEmpty()) {
                    Log.d("TCA 补数据：本进程配置为空，已从 Provider 现读 " + backup.size() + " 台");
                    return backup;
                }
            }
        } catch (Throwable t) {
            Log.v("TCA 补数据：现读配置失败 " + HookUtil.shortMsg(t));
        }
        return list;
    }

    /** 该型号是不是模块里的假设备（同 {@link #enabledDevices()} 的兜底口径）。*/
    private static SimDevice fakeDeviceByModel(String model) {
        if (model == null || model.isEmpty()) return null;
        for (SimDevice d : enabledDevices()) {
            if (model.equals(d.model)) return d;
        }
        return null;
    }

    private static SimDevice fakeDeviceByDid(String did) {
        if (!Consts.isSimulatedDid(did)) return null;
        for (SimDevice d : enabledDevices()) {
            if (did.equals(d.did)) return d;
        }
        return null;
    }

    private static boolean containsModel(List<Object> rows, String model) {
        for (Object o : rows) {
            if (o == null) continue;
            Object v = HookUtil.readFieldQuiet(o, "OooO00o");
            if (v != null && model.equals(String.valueOf(v))) return true;
        }
        return false;
    }

    private static Object newCif(String model, String scIds, String saIds) {
        try {
            Class<?> cif = HookUtil.findClassQuiet(CL_CIF, loader());
            if (cif == null) return null;
            Constructor<?> c = cif.getDeclaredConstructor(String.class, String.class, String.class);
            c.setAccessible(true);
            return c.newInstance(model, scIds, saIds);
        } catch (Throwable t) {
            Log.v("构造 cif 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- 3. 详情 JSON：donor 型号 → 假设备型号 ---------- */

    private static final XC_MethodHook BEFORE_HIF = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                String model = param.args[0] instanceof String ? (String) param.args[0] : null;
                Object raw = param.args[2];
                if (model == null || model.isEmpty() || !(raw instanceof JSONObject)) return;
                if (!isFakeModel(model)) return;

                JSONObject json = (JSONObject) raw;
                String old = json.optString("model");
                probe("", model, "hif", old, "rewrite", "建 hif 之前（old=donor 型号）");
                if (!old.isEmpty() && !old.equals(model)) {
                    // key / attr_new / 子属性里的 "prop.<donor>.<siid>.<piid>" 都是同一串型号，
                    // 整段字符串替换最省事，也不会漏掉嵌套字段
                    json = new JSONObject(json.toString().replace(old, model));
                }
                // 视图模型只要 can_sustained=true 的项，不置这一位等于白加
                json.put("can_sustained", true);
                param.args[2] = json;
            } catch (Throwable t) {
                Log.v("TCA 详情 JSON 改写跳过: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static boolean isFakeModel(String model) {
        return fakeDeviceByModel(model) != null;
    }

    /* ---------- 4. 整段兜底：假设备的触发项由这里自己拼，不再依赖那条链 ---------- */
    /*
     * 直接接管 getTriggerListCacheBy(did) 的返回值（实现见上面 {@link #CACHE_BY}）。
     *
     * 为什么不再顺着米家那条链走：11-12 现场日志（TCA-PROBE）显示，
     * OooOoo0 进来之后挂在协程里没回来 —— 后面的型号查询一次都没被调用，
     * 页面拿到的自然是空。与其继续补中间环节，不如把这一步整个换掉：
     * 拿 TCA 库里同品类现成型号的 sc_id 名单，按名单把 detail JSON 捞出来，
     * 换成假型号、置 can_sustained=true，用米家自己的 osf.Oooooo 建 hif，
     * 再套 gp4(did) / und(list) 直接返回。
     *
     * 这样返回的是「已经完成」的结果，调用方的状态机会把它当成同步返回继续走，
     * 不会再有任何挂起。
     *
     * donor 的口径（11-12 用户反馈「热水器里有开启空调的触发项」之后收口）：
     *   1) 同品牌同品类（型号前两段都相同）；
     *   2) 同品类不同品牌（第 2 段相同）；
     *   3) 都没有 —— 不许跨品类借，改从设备自己的 MIoT spec 生成触发项。
     * 步骤 3 才能保证列表里出现的每一项都属于这台设备本身。
     */

    /** 一页最多生成多少项：够用即可，别把列表撑爆。*/
    private static final int MAX_GENERATED = 80;
    /** 单个枚举属性最多展开几个值。*/
    private static final int MAX_ENUM_VALUES = 20;
    /** 设备信息类属性不做触发项：它们是静态字符串/编号，触发没有意义。*/
    private static final Set<String> SPEC_SKIP = new HashSet<>(Arrays.asList(
            "manufacturer", "model", "serial-number", "serial-no", "firmware-revision",
            "hardware-version", "mac", "identify", "wifi-ssid", "wifi-rssi", "name",
            "current-position", "target-position", "local-mode-config", "preheat-timer"));

    /** detail JSON 的骨架：从库里随便取一条现成的，逐字段覆盖，保证键名/类型和米家自己的一致。*/
    private static volatile JSONObject sPrototype;

    /** 假设备的触发项：同品类 donor 优先；没有就按 spec 生成。*/
    private static List<Object> buildTriggerItems(SimDevice dev) {
        Map<String, String[]> cat = catalog();
        if (!cat.isEmpty()) {
            String[] donor = pickDonor(cat, dev.model);
            if (donor != null) {
                List<Object> borrowed = fromDonor(dev, donor);
                if (!borrowed.isEmpty()) return borrowed;
                Log.w("TCA 整段兜底：donor " + donor[2] + " 没捞出 detail，改按 spec 生成");
            }
        } else {
            Log.w("TCA 整段兜底：型号目录为空（库读不到）");
        }
        return fromSpec(dev);
    }

    /** 借同品类 donor 的 launch 配置：换型号后建 hif，再包成 gp4。*/
    private static List<Object> fromDonor(SimDevice dev, String[] donor) {
        List<Object> out = new ArrayList<>();
        List<String> scIds = idsOf(donor[0]);
        if (scIds.isEmpty()) {
            Log.w("TCA 整段兜底：donor " + donor[2] + " 没有 sc_id");
            return out;
        }

        Context ctx = context();
        File dbFile = ctx == null ? null : findTcaDb(ctx);
        if (dbFile == null) return out;

        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = openDb(dbFile);
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < scIds.size(); i++) {
                if (i > 0) in.append(',');
                in.append('?');
            }
            c = sql.rawQuery("SELECT detail FROM device_launch_configs_v3 WHERE sc_id IN (" + in + ")",
                    scIds.toArray(new String[0]));
            while (c.moveToNext()) {
                String detail = c.getString(0);
                if (detail == null || detail.isEmpty()) continue;
                JSONObject json;
                try {
                    json = new JSONObject(detail);
                } catch (Throwable bad) {
                    continue;
                }
                if (containsDid(json.optJSONArray("black_dids"), dev.did)) continue;
                String old = json.optString("model");
                if (!old.isEmpty() && !old.equals(dev.model)) {
                    json = new JSONObject(json.toString().replace(old, dev.model));
                }
                json.put("can_sustained", true);   // 视图模型只留这一位为 true 的项
                Object hif = buildHif(dev.model, json);
                if (hif == null) continue;
                if (HookUtil.readIntField(hif, "OooO0Oo", 0) == 0) continue;   // sc_id=0 会被判过期
                Object item = newGp4(dev.did, hif);
                if (item != null) out.add(item);
            }
            Log.d("TCA 整段兜底：donor=" + donor[2] + " scIds=" + scIds.size() + " → 触发项 " + out.size());
        } catch (Throwable t) {
            Log.e("TCA 整段兜底查 detail 失败", t);
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
            if (sql != null) try {
                sql.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 按设备自己的 MIoT spec 生成触发项。
     *
     * <p>这是「同品类没有现成型号可借」时的唯一正确来源：属性 → {@code tr_id=102}，
     * 事件 → {@code tr_id=101}，键名用 {@code prop.<型号>.<siid>.<piid>}，值形状照抄库里
     * 真实行的三种：布尔 {@code value_type=4}、枚举 {@code =1}、数值区间 {@code =3}。
     * 名称优先用 spec 自带的中文 {@code comment}，没有才走词表翻译。
     */
    private static List<Object> fromSpec(SimDevice dev) {
        List<Object> out = new ArrayList<>();
        Context ctx = context();
        if (ctx == null) return out;
        MiotSpec spec = SpecRepository.forModel(ctx, dev.model);
        if (spec == null) {
            // 米家冷启动时跨进程读 spec 可能还没通（模块进程没起来 / MIUI 拦了一次）。
            // 先 ping 一次把 Provider 叫醒，清掉可能残留的负缓存再取一遍。
            boolean alive = ConfigChannel.providerAlive(ctx);
            SpecRepository.clearCache();
            spec = SpecRepository.forModel(ctx, dev.model);
            Log.w("TCA 整段兜底：spec 首次未命中 " + dev.model + " provider=" + alive
                    + " 第二次=" + (spec != null ? "命中" : "仍未命中"));
        }
        if (spec == null || !spec.isValid()) {
            Log.w("TCA 整段兜底：同品类无 donor，也没有 spec —— " + dev.model);
            return out;
        }
        JSONObject skeleton = prototypeDetail();
        if (skeleton == null) {
            Log.w("TCA 整段兜底：读不到 detail 原型，没法按 spec 生成");
            return out;
        }

        int base = 900000 + (Math.abs(dev.model.hashCode()) % 90000);
        List<JSONObject> jsons = new ArrayList<>();
        int propSeen = 0;
        int eventSeen = 0;
        try {
            for (MiotSpec.Service svc : spec.services) {
                for (MiotSpec.Prop p : svc.properties) {
                    if (jsons.size() >= MAX_GENERATED) break;
                    propSeen++;
                    if (!p.writable() && !p.notifiable()) continue;
                    if (p.isString()) continue;
                    String slug = MiotSpec.typeSegment(p.type);
                    if (!slug.isEmpty() && SPEC_SKIP.contains(slug)) continue;
                    addPropItems(jsons, skeleton, dev, p, base);
                }
                for (MiotSpec.Event e : svc.events) {
                    if (jsons.size() >= MAX_GENERATED) break;
                    eventSeen++;
                    addEventItem(jsons, skeleton, dev, e, base);
                }
            }
        } catch (Throwable t) {
            Log.e("TCA spec 生成触发项失败", t);
        }

        for (JSONObject json : jsons) {
            Object hif = buildHif(dev.model, json);
            if (hif == null) continue;
            if (HookUtil.readIntField(hif, "OooO0Oo", 0) == 0) continue;
            Object item = newGp4(dev.did, hif);
            if (item != null) out.add(item);
        }
        Log.d("TCA spec 生成触发项: " + dev.model + " 服务=" + spec.services.size()
                + " 属性=" + propSeen + " 事件=" + eventSeen
                + " 候选=" + jsons.size() + " → " + out.size() + " 项");
        return out;
    }

    private static void addPropItems(List<JSONObject> out, JSONObject skeleton, SimDevice dev,
                                     MiotSpec.Prop p, int base) {
        String key = "prop." + dev.model + "." + p.siid + "." + p.iid;
        String slug = MiotSpec.typeSegment(p.type);
        String cn = propCn(p);

        if (p.isBool()) {
            boolean plainSwitch = "on".equals(slug) || "on-off".equals(slug);
            addJson(out, propJson(skeleton, dev, key, base + out.size(),
                    plainSwitch ? "开机" : cn + "开启", Boolean.TRUE, 4));
            addJson(out, propJson(skeleton, dev, key, base + out.size(),
                    plainSwitch ? "关机" : cn + "关闭", Boolean.FALSE, 4));
            return;
        }

        if (p.hasValueList()) {
            int n = 0;
            for (MiotSpec.ValueUnit vu : p.valueList) {
                if (n >= MAX_ENUM_VALUES) break;
                Object v = enumValue(vu.value);
                if (v == null) continue;
                String vn = vu.comment.isEmpty() ? MiotLabels.value(vu.description) : vu.comment;
                if (vn == null || vn.isEmpty()) vn = String.valueOf(v);
                addJson(out, propJson(skeleton, dev, key, base + out.size(), cn + "为" + vn, v, 1));
                n++;
            }
            return;
        }

        if (p.range != null) {
            // [min, max, step]：长度 3 时最后一段是步长，别拿它当上限
            double min = p.range[0];
            double max = p.range.length > 1 ? p.range[1] : p.range[0];
            if (min > max) {
                double t = min;
                min = max;
                max = t;
            }
            JSONObject val = new JSONObject();
            try {
                val.put("max", numJson(max));
                val.put("min", numJson(min));
            } catch (Throwable ignored) {
                return;
            }
            String unit = MiotLabels.unit(p.unit);
            String name = cn + "在 " + plainNum(min) + "~" + plainNum(max) + unit + " 内";
            addJson(out, propJson(skeleton, dev, key, base + out.size(), name, val, 3));
        }
    }

    private static void addEventItem(List<JSONObject> out, JSONObject skeleton, SimDevice dev,
                                     MiotSpec.Event e, int base) {
        String key = "event." + dev.model + "." + e.siid + "." + e.iid;
        String cn = e.comment.isEmpty() ? MiotLabels.phrase(e.description) : e.comment;
        if (cn == null || cn.isEmpty()) cn = MiotSpec.typeSegment(e.type);
        if (cn == null || cn.isEmpty()) return;
        addJson(out, eventJson(skeleton, dev, key, base + out.size(), cn));
    }

    private static void addJson(List<JSONObject> out, JSONObject json) {
        if (json != null) out.add(json);
    }

    /** 属性的中文名：spec 自带 comment 最准，没有才翻。*/
    private static String propCn(MiotSpec.Prop p) {
        if (p.comment != null && !p.comment.isEmpty()) return p.comment;
        String cn = MiotLabels.propName(p);
        return cn == null || cn.isEmpty() ? "属性" + p.iid : cn;
    }

    private static JSONObject propJson(JSONObject skeleton, SimDevice dev, String key, int scId,
                                       String name, Object value, int valueType) {
        return detailJson(skeleton, dev, key, scId, name, value, valueType, "102");
    }

    private static JSONObject eventJson(JSONObject skeleton, SimDevice dev, String key, int scId,
                                        String name) {
        return detailJson(skeleton, dev, key, scId, name, "", 5, "101");
    }

    /**
     * 以库里的真实行为骨架，逐字段覆盖成这一条触发项。
     * sc_id 用 90 万起的号段：和库里现有号码不撞，且非 0（0 会被当过期）。
     */
    private static JSONObject detailJson(JSONObject skeleton, SimDevice dev, String key, int scId,
                                         String name, Object value, int valueType, String trId) {
        try {
            JSONObject j = new JSONObject(skeleton.toString());
            j.put("sc_id", String.valueOf(scId));
            j.put("tr_id", trId);
            j.put("src", "device");
            j.put("key", key);
            j.put("value", value);
            j.put("gid", "0");
            j.put("rank", 10);
            j.put("name", name);
            j.put("name_when_v2", "");
            j.put("name_if_v2", "");
            j.put("name_when", "");
            j.put("name_if", "");
            j.put("model", dev.model);
            j.put("value_type", valueType);
            j.put("groupInfo", JSONObject.NULL);
            j.put("protocol_type", 2);
            j.put("plug_id", "");
            j.put("attr_new", JSONObject.NULL);
            j.put("device_type", "");
            j.put("value_operation", 0);
            j.put("enable", false);
            j.put("status", 0);
            j.put("can_sustained", true);
            j.put("black_dids", new JSONArray());
            j.put("spec_info", JSONObject.NULL);
            j.put("data_ver", 0);
            j.put("spec_value_type", 0);
            j.put("c_rank", 0);
            return j;
        } catch (Throwable t) {
            Log.v("TCA 生成 detail 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    private static Object enumValue(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return v;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) ? (Object) ((int) d) : (Object) d;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
        }
        try {
            return Double.parseDouble(s);
        } catch (Throwable ignored) {
        }
        return s;
    }

    private static Object numJson(double d) {
        return d == Math.rint(d) ? (Object) ((int) d) : (Object) d;
    }

    private static String plainNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** 库里最新一条 detail 当模板；只读一次，之后用缓存。*/
    private static JSONObject prototypeDetail() {
        JSONObject cached = sPrototype;
        if (cached != null) return cached;
        Context ctx = context();
        if (ctx == null) return null;
        File db = findTcaDb(ctx);
        if (db == null) return null;
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = openDb(db);
            c = sql.rawQuery("SELECT detail FROM device_launch_configs_v3"
                    + " WHERE length(detail) > 100 ORDER BY sc_id DESC LIMIT 1", null);
            if (c.moveToFirst()) {
                JSONObject j = new JSONObject(c.getString(0));
                sPrototype = j;
                return j;
            }
        } catch (Throwable t) {
            Log.v("TCA 读 detail 原型失败: " + HookUtil.shortMsg(t));
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
            if (sql != null) try {
                sql.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean containsDid(JSONArray black, String did) {
        if (black == null || did == null) return false;
        for (int i = 0; i < black.length(); i++) {
            if (did.equals(black.optString(i))) return true;
        }
        return false;
    }

    private static List<String> idsOf(String json) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!id.isEmpty()) out.add(id);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 米家自己的 hif 构造器：{@code _m_j.osf#Oooooo(model, homeId, detailJson)}。*/
    private static Object buildHif(String model, JSONObject json) {
        try {
            Class<?> osf = HookUtil.findClassQuiet(CL_OSF, loader());
            if (osf == null) return null;
            Method m = osf.getDeclaredMethod(M_BUILD_HIF, String.class, String.class, JSONObject.class);
            m.setAccessible(true);
            return m.invoke(null, model, null, json);
        } catch (Throwable t) {
            Log.v("构造 hif 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    private static Object newGp4(String did, Object hif) {
        try {
            Class<?> gp4 = HookUtil.findClassQuiet(CL_GP4, loader());
            if (gp4 == null) return null;
            Constructor<?> c = gp4.getDeclaredConstructor(String.class);
            c.setAccessible(true);
            Object item = c.newInstance(did);
            if (!HookUtil.setFieldQuiet(item, "OooO0O0", hif)) return null;
            return item;
        } catch (Throwable t) {
            Log.v("构造 gp4 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    private static Object newUnd(List<Object> items) {
        try {
            Class<?> und = HookUtil.findClassQuiet(CL_UND, loader());
            if (und == null) return null;
            Constructor<?> c = und.getDeclaredConstructor(Object.class);
            c.setAccessible(true);
            return c.newInstance(items);
        } catch (Throwable t) {
            Log.v("构造 und 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- 5. 执行动作整段兜底：假设备的动作项由这里自己拼 ---------- */
    /*
     * 动作项的构造链（与触发侧不同）：
     *   awa.Oooo(homeId, detailJson) → oOO00000（lhf 的子类，sa_id 落在字段 OooO0Oo）
     *   → new a94(did)，把上面那个塞进字段 OooO0O0 → und(list)
     *
     * 数据来源顺序（11-12 定）：先按设备自己的 MIoT spec 生成 —— 动作是「真的要对这台设备
     * 做点什么」，spec 才说得清它有哪些可写能力；spec 一条都生不出来（没命中 / 全是不
     * 可写属性）时，才借同品类 donor 的 device_action_configs_v3。
     * 跨品类一律不借，理由和触发侧一样：热水器里冒出「开启空调」就是那么来的。
     */

    /** 动作页最多生成多少项。*/
    private static final int MAX_ACTIONS = 80;
    /** 单个枚举属性最多展开几个值（动作列表比触发列表更克制，太长不好点）。*/
    private static final int MAX_ACTION_ENUM_VALUES = 12;
    /** 动作的 detail 骨架，从库里取一条现成的；只读一次。*/
    private static volatile JSONObject sActionPrototype;
    /** 动作 donor 目录：model -> {a 列 sa_id JSON, 同上, model}。*/
    private static volatile Map<String, String[]> sActionCatalog;

    /** 假设备的执行动作：先按自身 spec 生成；spec 空才借同品类 donor。*/
    private static List<Object> buildActionItems(SimDevice dev) {
        List<Object> fromSpecItems = fromActionSpec(dev);
        if (!fromSpecItems.isEmpty()) return fromSpecItems;

        Map<String, String[]> cat = actionCatalog();
        if (!cat.isEmpty()) {
            String[] donor = pickActionDonor(cat, dev.model);
            if (donor != null) {
                List<Object> borrowed = fromActionDonor(dev, donor);
                if (!borrowed.isEmpty()) return borrowed;
                Log.w("TCA 执行动作：donor " + donor[2] + " 没捞出 detail，仍为空");
            }
        } else {
            Log.w("TCA 执行动作：型号目录为空（库读不到）");
        }
        return fromSpecItems;
    }

    /** 借同品类 donor 的 action 配置：换型号后建 oOO00000，再包成 a94。*/
    private static List<Object> fromActionDonor(SimDevice dev, String[] donor) {
        List<Object> out = new ArrayList<>();
        List<String> saIds = idsOf(donor[0]);
        if (saIds.isEmpty()) {
            Log.w("TCA 执行动作：donor " + donor[2] + " 没有 sa_id");
            return out;
        }

        Context ctx = context();
        File dbFile = ctx == null ? null : findTcaDb(ctx);
        if (dbFile == null) return out;

        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = openDb(dbFile);
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < saIds.size(); i++) {
                if (i > 0) in.append(',');
                in.append('?');
            }
            c = sql.rawQuery("SELECT detail FROM device_action_configs_v3 WHERE sa_id IN (" + in + ")",
                    saIds.toArray(new String[0]));
            while (c.moveToNext()) {
                String detail = c.getString(0);
                if (detail == null || detail.isEmpty()) continue;
                JSONObject json;
                try {
                    json = new JSONObject(detail);
                } catch (Throwable bad) {
                    continue;
                }
                if (containsDid(json.optJSONArray("black_dids"), dev.did)) continue;
                String old = json.optString("model");
                if (!old.isEmpty() && !old.equals(dev.model)) {
                    // command / model 都是同一串型号，整段替换最省事
                    json = new JSONObject(json.toString().replace(old, dev.model));
                }
                Object action = buildAction(json);
                if (action == null) continue;
                if (HookUtil.readIntField(action, "OooO0Oo", 0) == 0) continue;   // sa_id=0 会让整页判失败
                Object item = newA94(dev.did, action);
                if (item != null) out.add(item);
            }
            Log.d("TCA 执行动作：donor=" + donor[2] + " saIds=" + saIds.size() + " → " + out.size());
        } catch (Throwable t) {
            Log.e("TCA 执行动作查 detail 失败", t);
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
            if (sql != null) try {
                sql.close();
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * 按设备自己的 MIoT spec 生成执行动作。
     *
     * <p>可写属性 → {@code <model>.set_properties}：布尔拆「开/关」两条，枚举每个值一条，
     * 数值区间给一条带滑块的（payload 里的默认值取区间中点，attr 里带 min/max/step，
     * 页面点进去才会弹「设置多少」）。
     * spec 里声明的方法（action）→ {@code <model>.action}，带 {@code {siid, aiid, in: []}}。
     */
    private static List<Object> fromActionSpec(SimDevice dev) {
        List<Object> out = new ArrayList<>();
        Context ctx = context();
        if (ctx == null) return out;
        MiotSpec spec = SpecRepository.forModel(ctx, dev.model);
        if (spec == null) {
            // 与触发侧同款：冷启动可能还没读到，先 ping 一次 Provider、清负缓存再取
            boolean alive = ConfigChannel.providerAlive(ctx);
            SpecRepository.clearCache();
            spec = SpecRepository.forModel(ctx, dev.model);
            Log.w("TCA 执行动作：spec 首次未命中 " + dev.model + " provider=" + alive
                    + " 第二次=" + (spec != null ? "命中" : "仍未命中"));
        }
        if (spec == null || !spec.isValid()) {
            Log.w("TCA 执行动作：没有 spec，动作只能为空 —— " + dev.model);
            return out;
        }
        JSONObject skeleton = actionPrototype();
        if (skeleton == null) {
            Log.w("TCA 执行动作：读不到 action detail 原型，没法按 spec 生成");
            return out;
        }

        int base = 970000 + (Math.abs(dev.model.hashCode()) % 30000);
        List<JSONObject> jsons = new ArrayList<>();
        int propSeen = 0;
        int actionSeen = 0;
        try {
            for (MiotSpec.Service svc : spec.services) {
                for (MiotSpec.Prop p : svc.properties) {
                    if (jsons.size() >= MAX_ACTIONS) break;
                    if (!p.writable()) continue;
                    propSeen++;
                    if (p.isString()) continue;
                    String slug = MiotSpec.typeSegment(p.type);
                    if (!slug.isEmpty() && SPEC_SKIP.contains(slug)) continue;
                    addActionProps(jsons, skeleton, dev, p, base);
                }
                for (MiotSpec.Action a : svc.actions) {
                    if (jsons.size() >= MAX_ACTIONS) break;
                    actionSeen++;
                    addActionMethod(jsons, skeleton, dev, a, base);
                }
            }
        } catch (Throwable t) {
            Log.e("TCA spec 生成执行动作失败", t);
        }

        for (JSONObject json : jsons) {
            Object action = buildAction(json);
            if (action == null) continue;
            if (HookUtil.readIntField(action, "OooO0Oo", 0) == 0) continue;
            Object item = newA94(dev.did, action);
            if (item != null) out.add(item);
        }
        Log.d("TCA spec 生成执行动作: " + dev.model + " 服务=" + spec.services.size()
                + " 可写属性=" + propSeen + " 方法=" + actionSeen
                + " 候选=" + jsons.size() + " → " + out.size() + " 项");
        return out;
    }

    private static void addActionProps(List<JSONObject> out, JSONObject skeleton, SimDevice dev,
                                       MiotSpec.Prop p, int base) {
        String cn = propCn(p);
        String slug = MiotSpec.typeSegment(p.type);

        if (p.isBool()) {
            boolean plainSwitch = "on".equals(slug) || "on-off".equals(slug);
            addJson(out, actionJson(skeleton, dev, base + out.size(),
                    plainSwitch ? "开机" : cn + "开启",
                    propActionValue(p, Boolean.TRUE, true), null, false));
            addJson(out, actionJson(skeleton, dev, base + out.size(),
                    plainSwitch ? "关机" : cn + "关闭",
                    propActionValue(p, Boolean.FALSE, true), null, false));
            return;
        }

        if (p.hasValueList()) {
            int n = 0;
            for (MiotSpec.ValueUnit vu : p.valueList) {
                if (n >= MAX_ACTION_ENUM_VALUES) break;
                Object v = enumValue(vu.value);
                if (v == null) continue;
                String vn = vu.comment.isEmpty() ? MiotLabels.value(vu.description) : vu.comment;
                if (vn == null || vn.isEmpty()) vn = String.valueOf(v);
                addJson(out, actionJson(skeleton, dev, base + out.size(), cn + "设为" + vn,
                        propActionValue(p, v, false), null, false));
                n++;
            }
            return;
        }

        if (p.range != null) {
            // value-range 是 [min, max, step]，长度 2 时没有步长；
            // 以前把 range[len-1] 当 max，3 段时取到的是 step（35~1），
            // picker 拿到 min>max → 生成不出值 → 自己 finish，表现就是「点了没反应」
            double min = p.range[0];
            double max = p.range.length > 1 ? p.range[1] : p.range[0];
            if (min > max) {
                double t = min;
                min = max;
                max = t;
            }
            // interval 必须 >0：a94 会把这个键无条件塞进 Intent，0 会让 picker 立刻关掉
            double step = p.range.length > 2 && p.range[2] > 0 ? p.range[2] : 1;
            // 默认值取区间中点（和库里「设置亮度=50」一个口径），再对齐到步长
            double def = Math.rint((min + max) / 2);
            def = min + Math.round((def - min) / step) * step;
            if (def > max) def = max;
            String name = "设置" + cn;
            addJson(out, actionJson(skeleton, dev, base + out.size(), name,
                    propActionValue(p, def, false),
                    rangeAttr(name, def, min, max, step, p.unit), false));
        }
    }

    private static void addActionMethod(List<JSONObject> out, JSONObject skeleton, SimDevice dev,
                                        MiotSpec.Action a, int base) {
        String name = actionName(a);
        if (name == null || name.isEmpty()) return;
        JSONObject value = new JSONObject();
        try {
            value.put("siid", a.siid);
            value.put("aiid", a.iid);
            value.put("in", new JSONArray());
        } catch (Throwable t) {
            return;
        }
        addJson(out, actionJson(skeleton, dev, base + out.size(), name, value, null, true));
    }

    /** {@code payload.value} 属性型：一个元素的数组；布尔额外带 {@code value_type=4}。*/
    private static JSONArray propActionValue(MiotSpec.Prop p, Object v, boolean withType) {
        JSONArray arr = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("piid", p.iid);
            item.put("siid", p.siid);
            item.put("value", v);
            if (withType) item.put("value_type", 4);
        } catch (Throwable t) {
            return arr;
        }
        arr.put(item);
        return arr;
    }

    /**
     * 数值型动作的 {@code attr}：页面靠它弹「设置成多少」的滑块。
     * 形状照抄库里真实行：{@code {"attr_id":2001,"params":{...}}}。
     */
    private static JSONObject rangeAttr(String name, double def, double min, double max,
                                        double step, String unit) {
        try {
            JSONObject params = new JSONObject();
            params.put("default_val", numJson(def));
            String degree = degreeOf(unit);
            if (!degree.isEmpty()) params.put("degree", degree);
            params.put("display_sub_title", name);
            params.put("interval", numJson(step > 0 ? step : 1));
            params.put("json_val_tag", "equal");
            params.put("max_val", numJson(max));
            params.put("min_val", numJson(min));
            params.put("show_tags", new JSONArray());
            JSONObject attr = new JSONObject();
            attr.put("attr_id", 2001);
            attr.put("params", params);
            return attr;
        } catch (Throwable t) {
            return null;
        }
    }

    /** miot 的 unit → 米家滑块用的 degree（认不出就不写这个键）。*/
    private static String degreeOf(String unit) {
        if (unit == null || unit.isEmpty()) return "";
        if ("percentage".equals(unit)) return "percentage";
        if ("celsius".equals(unit)) return "celsius";
        if ("kelvin".equals(unit)) return "kelvin";
        return "";
    }

    /** spec 方法的中文名：description 翻词表，翻不动就用类型段。*/
    private static String actionName(MiotSpec.Action a) {
        if (a.description != null && !a.description.isEmpty()) {
            String cn = MiotLabels.phrase(a.description);
            if (cn != null && !cn.isEmpty()) return cn;
        }
        return MiotSpec.typeSegment(a.type);
    }

    /**
     * 以库里的真实动作行为骨架，逐字段覆盖成这一条。
     * {@code sa_id} 用 97 万起的号段：和库里现有号码不撞，且非 0（0 会让页面整份判失败）。
     */
    private static JSONObject actionJson(JSONObject skeleton, SimDevice dev, int saId, String name,
                                         Object value, JSONObject attr, boolean method) {
        try {
            JSONObject j = new JSONObject(skeleton.toString());
            JSONObject payload = new JSONObject();
            payload.put("title", "");
            payload.put("desc", "");
            payload.put("value", value == null ? JSONObject.NULL : value);
            payload.put("did", "");
            payload.put("command", dev.model + (method ? ".action" : ".set_properties"));
            payload.put("plug_id", "");
            payload.put("attr", attr == null ? JSONObject.NULL : attr);

            j.put("sa_id", String.valueOf(saId));
            j.put("type", "0");
            j.put("tr_id", "201");
            j.put("gid", "0");
            j.put("rank", 10);
            j.put("model", dev.model);
            j.put("name", name);
            j.put("keyName", name);
            j.put("groupInfo", JSONObject.NULL);
            j.put("protocol_type", 2);
            j.put("payload", payload);
            j.put("device_type", "");
            j.put("status", 0);
            j.put("black_dids", new JSONArray());
            j.put("spec_info", JSONObject.NULL);
            j.put("data_ver", 0);
            j.put("spec_value_type", 0);
            return j;
        } catch (Throwable t) {
            Log.w("TCA 生成 action detail 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /** 米家自己的动作构造器：{@code _m_j.awa#Oooo(homeId, detailJson)}（静态）。*/
    private static Object buildAction(JSONObject json) {
        try {
            Class<?> awa = HookUtil.findClassQuiet(CL_AWA, loader());
            if (awa == null) return null;
            Method m = awa.getDeclaredMethod(M_BUILD_ACTION, String.class, JSONObject.class);
            m.setAccessible(true);
            return m.invoke(null, null, json);
        } catch (Throwable t) {
            // 这一步失败等于整页空，必须留一份常规日志（Log.v 只在上详情时写）
            Log.w("构造 action 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    private static Object newA94(String did, Object action) {
        try {
            Class<?> a94 = HookUtil.findClassQuiet(CL_A94, loader());
            if (a94 == null) return null;
            Constructor<?> c = a94.getDeclaredConstructor(String.class);
            c.setAccessible(true);
            Object item = c.newInstance(did);
            if (!HookUtil.setFieldQuiet(item, "OooO0O0", action)) return null;
            return item;
        } catch (Throwable t) {
            Log.w("构造 a94 失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /** 库里最新一条动作 detail 当模板；只读一次，之后用缓存。*/
    private static JSONObject actionPrototype() {
        JSONObject cached = sActionPrototype;
        if (cached != null) return cached;
        Context ctx = context();
        if (ctx == null) return null;
        File db = findTcaDb(ctx);
        if (db == null) return null;
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = openDb(db);
            c = sql.rawQuery("SELECT detail FROM device_action_configs_v3"
                    + " WHERE length(detail) > 100 ORDER BY sa_id DESC LIMIT 1", null);
            if (c.moveToFirst()) {
                JSONObject j = new JSONObject(c.getString(0));
                sActionPrototype = j;
                return j;
            }
        } catch (Throwable t) {
            Log.w("TCA 读 action detail 原型失败: " + HookUtil.shortMsg(t));
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
            if (sql != null) try {
                sql.close();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /* ---------- TCA 库目录（读现成型号行，只读） ---------- */

    private static SQLiteDatabase openDb(File db) {
        try {
            return SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Throwable rw) {
            Log.w("TCA 库读写打开失败，退回只读: " + HookUtil.shortMsg(rw));
            return SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        }
    }

    private static Map<String, String[]> catalog() {
        Map<String, String[]> cached = sCatalog;
        if (cached != null && !cached.isEmpty()) return cached;
        Map<String, String[]> out = new LinkedHashMap<>();
        Context ctx = context();
        if (ctx == null) return out;
        File db = findTcaDb(ctx);
        if (db == null) {
            Log.w("找不到 device_tca_config.db，TCA 能力没法补");
            return out;
        }
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = openDb(db);
            c = sql.rawQuery("SELECT model, l, a FROM device_config_v3_tca_simple", null);
            while (c.moveToNext()) {
                String model = c.getString(0);
                String l = c.getString(1);
                String a = c.getString(2);
                if (model == null || l == null || !usableIds(l)) continue;
                out.put(model, new String[]{l, a == null ? "[]" : a, model});
            }
        } catch (Throwable t) {
            Log.w("读取 TCA 库失败: " + HookUtil.shortMsg(t));
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
            if (sql != null) try {
                sql.close();
            } catch (Throwable ignored) {
            }
        }
        if (!out.isEmpty()) sCatalog = out;
        return out;
    }

    private static File findTcaDb(Context ctx) {
        try {
            File dir = new File(ctx.getApplicationInfo().dataDir, "databases");
            File f = new File(dir, "device_tca_config.db");
            if (f.isFile()) return f;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File x : files) {
                    String n = x.getName();
                    if (n.contains("tca") && n.endsWith(".db")) return x;
                }
            }
        } catch (Throwable t) {
            Log.v("列数据库目录失败: " + HookUtil.shortMsg(t));
        }
        return null;
    }

    /** 名单里至少有一个真 id（mphone 那种 "0" 开头的行借过来只会得到空触发）。*/
    private static boolean usableIds(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String id = arr.optString(i, "");
                if (!id.isEmpty() && !"0".equals(id)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 只在同品类里挑 donor。两级：
     * <ol>
     *   <li>同品牌同品类（型号前两段相同，如 linp.light.* ↔ linp.light.*）；</li>
     *   <li>同品类不同品牌（第 2 段相同，如 linp.light.* ↔ yeelink.light.*）。</li>
     * </ol>
     * 跨品类一台都不给 —— 只看第 1 段（品牌）会把热水器和空调配成一对，那正是 11-12
     * 「热水器里出现开启空调」的根因。同品类都没有就返回 null，由调用方改走 spec 生成。
     */
    private static String[] pickDonor(Map<String, String[]> catalog, String model) {
        String[] same = bestInCategory(catalog, model, true);
        if (same != null) return same;
        return bestInCategory(catalog, model, false);
    }

    private static String[] bestInCategory(Map<String, String[]> catalog, String model, boolean sameBrand) {
        String brandCat = brandCategory(model);
        String cat = categoryOf(model);
        if (cat.isEmpty()) return null;
        String[] best = null;
        int bestIds = -1;
        for (Map.Entry<String, String[]> e : catalog.entrySet()) {
            String k = e.getKey();
            boolean hit = sameBrand
                    ? brandCat.equals(brandCategory(k))
                    : cat.equals(categoryOf(k));
            if (!hit) continue;
            int ids = idCount(e.getValue()[0]);
            if (ids > bestIds) {
                bestIds = ids;
                best = e.getValue();
            }
        }
        return best;
    }

    /**
     * 动作 donor 目录：只要 a 列（sa_id 名单）里有真 id 的型号。
     *
     * <p>为什么单独查一遍而不复用 {@link #catalog()}：那张表是按 sc_id 过滤的，
     * 像 {@code xiaomi.tv.v1}、{@code miir.*} 这些只有动作没有触发条件的型号会被漏掉。
     * 返回形状与 catalog 一致：{sa_id JSON, 同上, model}。
     */
    private static Map<String, String[]> actionCatalog() {
        Map<String, String[]> cached = sActionCatalog;
        if (cached != null && !cached.isEmpty()) return cached;
        Map<String, String[]> out = new LinkedHashMap<>();
        Context ctx = context();
        if (ctx == null) return out;
        File db = findTcaDb(ctx);
        if (db == null) return out;
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            sql = openDb(db);
            c = sql.rawQuery("SELECT model, a FROM device_config_v3_tca_simple", null);
            while (c.moveToNext()) {
                String model = c.getString(0);
                String a = c.getString(1);
                if (model == null || a == null || !usableIds(a)) continue;
                out.put(model, new String[]{a, a, model});
            }
        } catch (Throwable t) {
            Log.w("读取动作目录失败: " + HookUtil.shortMsg(t));
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
            if (sql != null) try {
                sql.close();
            } catch (Throwable ignored) {
            }
        }
        if (!out.isEmpty()) sActionCatalog = out;
        return out;
    }

    /** 动作 donor：同样只在同品类里挑，两级（同品牌同品类 → 同品类不同品牌）。*/
    private static String[] pickActionDonor(Map<String, String[]> catalog, String model) {
        String[] same = bestActionInCategory(catalog, model, true);
        if (same != null) return same;
        return bestActionInCategory(catalog, model, false);
    }

    private static String[] bestActionInCategory(Map<String, String[]> catalog, String model,
                                                 boolean sameBrand) {
        String brandCat = brandCategory(model);
        String cat = categoryOf(model);
        if (cat.isEmpty()) return null;
        String[] best = null;
        int bestIds = -1;
        for (Map.Entry<String, String[]> e : catalog.entrySet()) {
            String k = e.getKey();
            boolean hit = sameBrand
                    ? brandCat.equals(brandCategory(k))
                    : cat.equals(categoryOf(k));
            if (!hit) continue;
            int ids = idCount(e.getValue()[0]);   // 这里 [0] 是 a 列
            if (ids > bestIds) {
                bestIds = ids;
                best = e.getValue();
            }
        }
        return best;
    }

    /** 品牌+品类：linp.light.lx2bcw → linp.light。*/
    private static String brandCategory(String model) {
        String[] parts = model == null ? new String[0] : model.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : (model == null ? "" : model);
    }

    /** 品类：linp.light.lx2bcw → light。*/
    private static String categoryOf(String model) {
        String[] parts = model == null ? new String[0] : model.split("\\.");
        return parts.length >= 2 ? parts[1] : "";
    }

    private static int idCount(String json) {
        try {
            return new JSONArray(json).length();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static ClassLoader loader() {
        return sLoader;
    }

    private static Context context() {
        return SimContextHolder.get();
    }
}
