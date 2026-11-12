/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-12 12:12:20 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.mijia.devicesim.core.CallLog;
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
    private static final String CL_OSF = "_m_j.osf";
    private static final String M_BUILD_HIF = "Oooooo";
    private static final String CL_CONT = "kotlin.coroutines.jvm.internal.ContinuationImpl";

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

        if (expire || models || hif) {
            HookReport.ok(GROUP, "install", "过期=" + expire + "，型号=" + models + "，详情=" + hif);
        } else {
            HookReport.fail(GROUP, "install", "三个挂点都没挂上");
        }
    }

    /* ---------- 1. 假 did 的缓存永不过期 ---------- */

    private static final XC_MethodHook AFTER_EXPIRE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object res = param.getResult();
                if (!(res instanceof Boolean) || !((Boolean) res)) return;
                String did = param.args[0] instanceof String ? (String) param.args[0] : null;
                if (!Consts.isSimulatedDid(did)) return;
                if (ConfigRepository.current().findEnabledByDid(did) == null) return;
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
                if (fakeModels.isEmpty()) return;

                List<Object> rows = (List<Object>) res;
                Map<String, String[]> catalog = catalog();
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
        SimConfig cfg = ConfigRepository.current();
        for (String m : asked) {
            if (m == null || m.isEmpty()) continue;
            for (SimDevice d : cfg.enabledDevices()) {
                if (m.equals(d.model) && !out.contains(m)) {
                    out.add(m);
                    break;
                }
            }
        }
        return out;
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
        SimConfig cfg = ConfigRepository.current();
        for (SimDevice d : cfg.enabledDevices()) {
            if (model.equals(d.model)) return true;
        }
        return false;
    }

    /* ---------- TCA 库目录（读现成型号行，只读） ---------- */

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
            // 用读写模式打开：库是 WAL 的，只读连接在 -shm 不存在时打不开
            sql = SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
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
