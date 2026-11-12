/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-12 15:22:46 +08:00
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
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *       合成一行 {@code cif(假型号, donorScIds, donorSaIds)} 塞进去。donor 按型号前缀选
 *       （同品牌同品类优先，例如 linp.light.* 互相借）。</li>
 *   <li><b>详情 JSON</b> —— 建 {@code hif} 之前（{@code osf.Oooooo}）把 JSON 里 donor 的型号替换成
 *       假设备的型号，并把 {@code can_sustained} 强制为 true：视图模型只保留这个字段为 true 的项，
 *       不置 true 就算塞进去也会被过滤掉。</li>
 * </ol>
 * 每一步都只对「本模块配置里启用着的假设备」生效，真实设备一行数据都不改。
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

        // 生效点 + 诊断都在同一个挂点上：HookUtil 对同一签名只允许装一次，
        // 所以 before（假设备整段接管）和 after（证据）必须合并成一个 hook。
        boolean build = HookUtil.hookMethod(GROUP,
                HookUtil.findClassQuiet(CL_REPO, lpparam.classLoader), M_CACHE_BY,
                CACHE_BY, String.class, cont);
        boolean trace = HookUtil.hookMethod(GROUP,
                HookUtil.findClassQuiet(CL_REPO, lpparam.classLoader), M_REMOTE,
                AFTER_REMOTE, String.class, String.class, cont);

        Log.d("TCA 挂点安装 进程=" + HookReport.process() + " 过期=" + expire + " 型号=" + models
                + " 详情=" + hif + " 追踪=" + trace + " 兜底=" + build);
        if (expire || models || hif || build) {
            HookReport.ok(GROUP, "install", "过期=" + expire + "，型号=" + models + "，详情=" + hif
                    + "，追踪=" + trace + "，兜底=" + build);
        } else {
            HookReport.fail(GROUP, "install", "三个挂点都没挂上");
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
     */

    /** 假设备的触发项：donor 型号的每条 launch 配置换型号后建 hif，再包成 gp4。*/
    private static List<Object> buildTriggerItems(SimDevice dev) {
        List<Object> out = new ArrayList<>();
        Map<String, String[]> cat = catalog();
        if (cat.isEmpty()) {
            Log.w("TCA 整段兜底：型号目录为空（库读不到）");
            return out;
        }
        String[] donor = pickDonor(cat, dev.model);
        if (donor == null) {
            Log.w("TCA 整段兜底：找不到可借的 donor 型号");
            return out;
        }
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

    /** 型号前缀匹配最长的 donor；同分时取 id 更多的那个。*/
    private static String[] pickDonor(Map<String, String[]> catalog, String model) {
        String[] best = null;
        int bestScore = -1;
        int bestIds = -1;
        for (Map.Entry<String, String[]> e : catalog.entrySet()) {
            int score = commonSegments(model, e.getKey());
            int ids = idCount(e.getValue()[0]);
            if (score > bestScore || (score == bestScore && ids > bestIds)) {
                bestScore = score;
                bestIds = ids;
                best = e.getValue();
            }
        }
        return best;
    }

    private static int commonSegments(String a, String b) {
        String[] x = a == null ? new String[0] : a.split("\\.");
        String[] y = b == null ? new String[0] : b.split("\\.");
        int n = 0;
        while (n < x.length && n < y.length && x[n].equals(y[n])) n++;
        return n;
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
