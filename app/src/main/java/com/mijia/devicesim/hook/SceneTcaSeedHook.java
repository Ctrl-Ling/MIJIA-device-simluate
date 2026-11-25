/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 17:11:15 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 11.10.623 版「可触发能力列表」的补数据层（TCA）。
 *
 * <p>页面链路（已按字节码核对）：选完设备进 {@code TriggerDetailSelectPage} →
 * {@code TcaDetailSelectViewModel} 依次问 {@code SpecSceneConfigRepository} 三路数据：
 * <pre>
 *   触发 getTriggerListCacheBy(did, cont)        / getTriggerListRemote(did, model, cont)
 *   条件 getConditionListCacheBy(did, cont)      / getConditionListRemote(did, model, cont)
 *   动作 getActionListCacheBy(did, cont)         / getActionListRemote(did, model, cont)
 * </pre>
 * 缓存版（…CacheBy）内部先看 did 在内存表 {@code OooO0o} 里的上次时间戳，24 小时内才查本地
 * 库 {@code device_config_v3_tca_simple}（按 {@code Device.model}），否则返回「过期」标记
 * {@code gxo$OooO00o} 让上层转远端。假 did 没有时间戳，永远「过期」→ 一定走远端；远端再用
 * 同一个假 model 去问，库里/云端都没有这个型号 → 页面提示「当前设备暂无额外自动化能力可选择」。
 *
 * <p>为什么只动参数、不自己造对象：列表项是 {@code aps}（详情）+ {@code x38/ee7/oa7}
 * （三种包装）三层拼出来的，字段多、还夹着在线判断和黑名单过滤；自己造一个对象很容易在
 * 后续点击时炸掉。这里只做三件最小的事 ——
 * <ol>
 *   <li>ViewModel 启动前：{@code loadData} 第一句就是「型号为空 → 发空状态」，而假 did 在
 *       这个上下文里 {@code getDeviceByDid} 解析不到、型号是空串。所以先在
 *       {@code OoooOO0()} 里把型号补成 donor 型号，协程才有机会往下走；</li>
 *   <li>缓存版：库里没有该型号配置时，把参数里的假 did 换成 donor 的真实 did，
     *       让缓存版按 donor 型号查本地库（donor 也无同步记录的话它自己会判过期，
     *       上层随即转远端，远端那一刀再把整对换成 donor）；</li>
 *   <li>过期判定：{@code LocalDataResource.isConfigExpired} 对「本地 simple 表确有该型号配置」
 *       的模拟 did 直接回 false，缓存版就会按假 did 查本地库出列表 —— 不联网也不借设备；</li>
 *   <li>远端版：混淆后两个字符串参数一个是型号、一个是 did，用「哪个是模拟 did」来认。
 *       认出后整对换成库里 access 表登记过、simple 表确有配置的真实 (did, 型号) —— 服务端
 *       按这对返回能力列表，上层再把列表挂回模拟设备的假 did。donor 优先同型号，其次同品类
 *       （{@code 品牌.品类.型号} 的第二段；品牌相同优先）；都没有就不动，宁可空白也不串品类。</li>
 * </ol>
 *
 * <p>方法名是 11.10.623 混淆后的名字，已在反汇编里逐个核对过；版本再变时整条链会静默跳过
 * （{@link HookUtil#hookAllByName} 找不到就返回 0），不会影响米家其它功能。
 */
public final class SceneTcaSeedHook {

    private static final String GROUP = "scene-tca-seed";

    /** TCA 配置仓库（11.10.623 未混淆）。*/
    private static final String CL_REPO = "com.xiaomi.smarthome.specscene.manager.SpecSceneConfigRepository";

    /** 三路「本地缓存」(String did, Continuation) → gxo。*/
    private static final String[] M_CACHE = {"o0000o0", "o000000", "o0ooOO0"};
    /** 三路「远端查询」(String did, String model, Continuation) → gxo。*/
    private static final String[] M_REMOTE = {"o0000oO0", "o00000O0", "o0Oo0oo"};

    /**
     * 「本地配置是否过期」判定：{@code isConfigExpired(did, cont)} →
     * 现在 - 上次同步时间 &gt; 24h 为 true。假 did 没同步记录，永远「过期」。
     * 库里本身有该型号配置时，这里改成 false，让缓存版直接查本地库。
     */
    private static final String CL_LDR = "com.xiaomi.smarthome.scene.dao.LocalDataResource";
    private static final String M_LDR_EXPIRE = "OooOoo0";

    /**
     * 页面 ViewModel。构造函数里已经调过 {@code getDeviceByDid(did)} 取型号，取不到就写空串；
     * {@code loadData} 第一件事就是「型号为空 → 直接发空状态」。假 did 在这个上下文里解析不到，
     * 所以必须在 {@code OoooOO0()}（构造函数末尾启动加载的那个方法）之前把型号补上。
     */
    private static final String CL_VM = "com.xiaomi.smarthome.specscene.manager.TcaDetailSelectViewModel";
    /** ViewModel 里「启动加载」的无参方法（构造函数尾调它，刷新也会调）。*/
    private static final String M_VM_LOAD = "OoooOO0";
    /** ViewModel 字段：did / 型号。*/
    private static final String F_VM_DID = "o0OOo0OO";
    private static final String F_VM_MODEL = "o0OOoO0";

    /** simple 表里 {@code l} 非空的型号（本地就有能力配置），只读一次。*/
    private static volatile Set<String> sConfigured;
    /** 假型号 → donor（含「找不到」的负缓存，值为 null）。*/
    private static final Map<String, Donor> sDonor = new HashMap<>();
    private static final Set<String> sLogged = new HashSet<>();

    private SceneTcaSeedHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> repo = HookUtil.findClassQuiet(CL_REPO, lpparam.classLoader);
        if (repo == null) {
            HookReport.skip(GROUP, CL_REPO, "类不存在（TCA 仓库可能已改版）");
            return;
        }
        int n = 0;
        for (String name : M_CACHE) n += HookUtil.hookAllByName(GROUP, repo, name, SEED);
        for (String name : M_REMOTE) n += HookUtil.hookAllByName(GROUP, repo, name, SEED);
        Class<?> ldr = HookUtil.findClassQuiet(CL_LDR, lpparam.classLoader);
        int nExp = ldr == null ? 0 : HookUtil.hookAllByName(GROUP, ldr, M_LDR_EXPIRE, EXPIRED);
        if (ldr == null) {
            HookReport.skip(GROUP, CL_LDR, "本地资源类不存在（缓存会一直判过期，只剩远端借设备这条路）");
        }
        Class<?> vm = HookUtil.findClassQuiet(CL_VM, lpparam.classLoader);
        int nVm = vm == null ? 0 : HookUtil.hookAllByName(GROUP, vm, M_VM_LOAD, SEED_VM);
        if (vm == null) {
            HookReport.skip(GROUP, CL_VM, "ViewModel 类不存在（版号可能已变）");
        }
        if (n > 0) {
            HookReport.ok(GROUP, "install", "TCA 三路缓存/远端共挂 " + n + " 个方法，过期判定 " + nExp
                    + "，ViewModel 补型号 " + nVm);
        } else {
            HookReport.fail(GROUP, "install", "6 个方法名一个都没对上");
        }
        Log.d("TCA 补数据安装: 进程=" + lpparam.processName + " 仓库挂点=" + n
                + " 过期判定=" + nExp + " VM 挂点=" + nVm);
    }

    private static final XC_MethodHook SEED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length < 2) return;
                // 缓存版签名是 (String did, Continuation)，第二个参数不是字符串；
                // 远端版是 (String a, String b, Continuation)。只有第一个参数必是字符串。
                if (!(args[0] instanceof String)) return;

                String a0 = (String) args[0];
                if (args.length == 2) {
                    // 本地缓存版 (did, continu)。库里本来就有这个型号的配置 → 放它自己查库，
                    // 结果按假 did 返回，完全不用云；库里没有 → 把 did 换成 donor 的真实 did，
                    // 让缓存版拿 donor 型号查本地库（donor 没同步记录时它自己会判过期，上层
                    // 转远端，远端那一刀照样把这一对换掉）。这里不能再指望「返回过期标记」：
                    // 那个标记类在混淆包里按名字取不到（CL_GXO 一直是空），以前等于什么都没做。
                    logOnce("hit2:" + a0, "TCA 缓存查询 did=" + a0);
                    SimConfig cfg = ConfigRepository.current();
                    if (cfg == null || !cfg.injectEnabled) return;
                    SimDevice dev = cfg.findEnabledByDid(a0);
                    if (dev == null) return;
                    if (hasLocalConfig(dev.model)) {
                        logOnce("cache-local:" + a0, "TCA 缓存走本地库 did=" + a0 + " model=" + dev.model);
                        return;
                    }
                    Donor donor = donorFor(dev.model);
                    if (donor == null) {
                        logOnce("cache-keep:" + a0, "TCA 缓存无可用 donor，保持原 did=" + a0
                                + " model=" + dev.model);
                        return;
                    }
                    args[0] = donor.did;
                    logOnce("cache:" + a0, "TCA 缓存借 donor 本地配置 did=" + a0 + " model=" + dev.model
                            + " → " + donor.model + "/" + donor.did);
                    return;
                }

                if (args.length >= 3 && args[1] instanceof String) {
                    String a1 = (String) args[1];
                    logOnce("hit:" + a0 + ":" + a1,
                            "TCA 远端查询 a0=" + a0 + " a1=" + a1);
                    SimConfig cfg = ConfigRepository.current();
                    if (cfg == null || !cfg.injectEnabled) return;
                    // 远端版：混淆后两个参数一个是型号、一个是 did，顺序因版本而异 ——
                    // 用「哪个是模拟 did」来认，不猜位置。认出后整对换成库里有配置的真实
                    // (did, 型号)：服务端按这对返回能力列表，上层再把列表挂回假 did。
                    boolean a0Sim = cfg.findEnabledByDid(a0) != null;
                    boolean a1Sim = cfg.findEnabledByDid(a1) != null;
                    if (!a0Sim && !a1Sim) return;
                    String model = a0Sim ? a1 : a0;
                    Donor donor = donorFor(model);
                    if (donor == null) {
                        logOnce("remote-keep:" + model, "TCA 远端无可用 donor，保持原参数 " + model);
                        return;
                    }
                    if (a0Sim) {
                        args[0] = donor.did;
                        args[1] = donor.model;
                    } else {
                        args[0] = donor.model;
                        args[1] = donor.did;
                    }
                    logOnce("remote:" + a0 + ":" + a1, "TCA 远端借真实设备 "
                            + model + "/" + (a0Sim ? a0 : a1) + " → " + donor.model + "/" + donor.did);
                }
            } catch (Throwable t) {
                Log.e("TCA 补数据异常", t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length != 3) return;
                if (!(args[0] instanceof String) || !(args[1] instanceof String)) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                String s0 = (String) args[0];
                String s1 = (String) args[1];
                if (cfg.findEnabledByDid(s0) == null && cfg.findEnabledByDid(s1) == null) return;

                Object res = param.getResult();
                if (res == null) return;
                String cls = res.getClass().getName();
                if (cls.endsWith("CoroutineSingletons")) return; // 挂起中的那次
                logOnce("result:" + s0 + ":" + s1, "TCA 远端返回 " + shortType(res));
            } catch (Throwable ignored) {
            }
        }
    };

    /**
     * 「本地配置过期」判定：模拟 did 且库里确有该型号配置 → 直接回 {@code false}（没过期）。
     *
     * <p>为什么值得单独挂这一刀：{@code isConfigExpired} 拿「上次同步时间」算 24 小时，
     * 假 did 没有同步记录所以永判过期，缓存版数据永远走不到。而 simple 表里本来就存着
     * 不少型号的完整能力配置（比如 {@code ijai.vacuum.v18}、{@code linp.switch.s2dw3}），
     * 把这刀改掉，缓存版拿假 did 查「自己型号」的本地行就能出列表 —— 不借别人设备、
     * 不联网，条目还天然对得上型号。库里没有的型号不动，仍旧过期 → 走远端 donor。
     */
    private static final XC_MethodHook EXPIRED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length < 1 || !(args[0] instanceof String)) return;
                String did = (String) args[0];
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) return;
                if (!hasLocalConfig(dev.model)) return;
                param.setResult(Boolean.FALSE);
                logOnce("fresh:" + did, "TCA 过期判定改未过期 did=" + did + " model=" + dev.model);
            } catch (Throwable t) {
                Log.e("TCA 过期判定异常", t);
            }
        }
    };

    /** ViewModel 启动加载前：假 did 的型号若为空（或库里没配置），换成 donor 型号。*/
    private static final XC_MethodHook SEED_VM = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object vm = param.thisObject;
                if (vm == null) return;
                String did = asString(HookUtil.readFieldQuiet(vm, F_VM_DID));
                if (did == null || did.isEmpty()) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                if (cfg.findEnabledByDid(did) == null) return;

                String model = asString(HookUtil.readFieldQuiet(vm, F_VM_MODEL));
                if (hasLocalConfig(model)) return;   // 本地就有这型号的配置，保持原型号
                Donor donor = donorFor(model);
                logOnce("vm:" + did, "TCA VM did=" + did + " model=" + model
                        + " donor=" + (donor == null ? "无" : donor.model + "/" + donor.did));
                if (donor == null || donor.model.equals(model)) return;
                // loadData 第一件事就是拿这个字段判空；必须在协程开跑前写好
                if (HookUtil.setFieldQuiet(vm, F_VM_MODEL, donor.model)) {
                    Log.d("TCA VM 型号补齐 did=" + did + " " + model + " → " + donor.model);
                }
            } catch (Throwable t) {
                Log.e("TCA VM 预置异常", t);
            }
        }
    };

    /** 结果摘要：{@code gxo$OooOO0O[x]}，非列表就是类名。*/
    private static String shortType(Object res) {
        try {
            String name = res.getClass().getName();
            name = name.substring(name.lastIndexOf('.') + 1);
            Object list = HookUtil.readFieldQuiet(res, "OooO0O0");
            if (list instanceof List) return name + "[" + ((List<?>) list).size() + "]";
            return name;
        } catch (Throwable t) {
            return res.getClass().getName();
        }
    }

    
    
    /* ---------- donor：库里有配置的真实 (did, 型号) 对 ---------- */

    /** 一台「真实存在且库里有 TCA 配置」的设备。服务端按这对返回能力列表。*/
    private static final class Donor {
        final String did;
        final String model;

        Donor(String did, String model) {
            this.did = did;
            this.model = model;
        }
    }

    /**
     * 假型号 → donor。找不到返回 null（不改，保持米家原行为）。
     * 型号形如 {@code 品牌.品类.型号}，第二段当品类；同型号优先，其次同品类（品牌相同优先）。
     */
    private static Donor donorFor(String model) {
        if (model == null || model.isEmpty()) return null;
        synchronized (sDonor) {
            Donor hit = sDonor.get(model);
            if (hit != null) return hit;
        }
        Donor found = null;
        try {
            found = pick(donors(), model);
        } catch (Throwable t) {
            Log.w("TCA donor 查找失败: " + HookUtil.shortMsg(t));
        }
        // 只缓存「找到了」的；没找到的下次重新读库，等米家同步完就能找到
        if (found != null) {
            synchronized (sDonor) {
                sDonor.put(model, found);
            }
        }
        return found;
    }

    private static Donor pick(List<Donor> donors, String model) {
        for (Donor d : donors) {
            if (d.model.equals(model)) return d;
        }
        String cat = segment(model, 1);
        if (cat == null) return null;
        String brand = segment(model, 0);
        Donor fallback = null;
        for (Donor d : donors) {
            if (!cat.equals(segment(d.model, 1))) continue;
            if (brand != null && brand.equals(segment(d.model, 0))) return d;
            if (fallback == null) fallback = d;
        }
        return fallback;
    }

    private static String segment(String model, int idx) {
        if (model == null) return null;
        String[] parts = model.split("\\.");
        return idx < parts.length ? parts[idx] : null;
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    /**
     * 读一次 TCA 库，拼出 donor 名单：
     * <ul>
     *   <li>{@code device_config_v3_tca_simple(model, l)} —— {@code l} 里确实有 sc_id 才算有配置；</li>
     *   <li>{@code device_config_v3_access(did, model)} —— 真实 did；跳过模拟 did，
     *       不然拿假 did 去问服务端一样是空。</li>
     * </ul>
     */
    private static List<Donor> donors() {
        return loadLib();
    }

    /** 某型号的 TCA 配置是否就存在本地 simple 表里（含非空 l 才算）。*/
    private static boolean hasLocalConfig(String model) {
        if (model == null || model.isEmpty()) return false;
        Set<String> set = sConfigured;
        if (set == null) {
            loadLib();
            set = sConfigured;
        }
        return set != null && set.contains(model);
    }

    /**
     * 读一次 TCA 库：返回「可借的真实 (did, 型号)」，顺带记下「本地有配置的型号」。
     *
     * <p>donor 名单每次现读不缓存：米家启动时会同步并重建这两张表，缓存一次就可能拿到
     * 「同步到一半」的空表（实测启动那会儿只剩 2 对），后面整页都借不到设备。
     * 查询本身只有几十行，一次页面打开最多读三次，开销可以忽略。
     */
    private static List<Donor> loadLib() {
        List<Donor> out = new ArrayList<>();
        Set<String> configured = new HashSet<>();
        Map<String, String> didByModel = new HashMap<>();
        Context ctx = SimContextHolder.get();
        if (ctx == null) ctx = DeviceListHook.context();
        File db = ctx == null ? null : findDb(ctx);
        SQLiteDatabase sql = null;
        Cursor c = null;
        try {
            if (db != null) {
                sql = openDb(db);
                c = sql.rawQuery("SELECT model, l FROM device_config_v3_tca_simple", null);
                while (c.moveToNext()) {
                    String model = c.getString(0);
                    String l = c.getString(1);
                    if (model == null || model.isEmpty()) continue;
                    if (l == null || l.length() <= 4 || "[]".equals(l.trim())) continue;
                    configured.add(model);
                }
                c.close();
                c = sql.rawQuery("SELECT did, model FROM device_config_v3_access", null);
                while (c.moveToNext()) {
                    String did = c.getString(0);
                    String model = c.getString(1);
                    if (did == null || did.isEmpty() || model == null || model.isEmpty()) continue;
                    if (com.mijia.devicesim.core.Consts.isSimulatedDid(did)) continue;
                    if (!didByModel.containsKey(model)) didByModel.put(model, did);
                }
            }
        } catch (Throwable t) {
            Log.w("读取 TCA 库 donor 失败: " + HookUtil.shortMsg(t));
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
        for (Map.Entry<String, String> e : didByModel.entrySet()) {
            if (configured.contains(e.getKey())) out.add(new Donor(e.getValue(), e.getKey()));
        }
        Log.d("TCA donor 对数=" + out.size() + "（有配置型号 " + configured.size() + "）库=" + db);
        if (db != null) sConfigured = configured;
        return out;
    }

    private static File findDb(Context ctx) {
        try {
            File dir = new File(ctx.getApplicationInfo().dataDir, "databases");
            File f = new File(dir, "device_tca_config.db");
            if (f.isFile()) return f;
        } catch (Throwable t) {
            Log.v("列 TCA 库目录失败: " + HookUtil.shortMsg(t));
        }
        return null;
    }

    private static SQLiteDatabase openDb(File db) {
        try {
            return SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Throwable rw) {
            return SQLiteDatabase.openDatabase(db.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        }
    }

    private static void logOnce(String key, String msg) {
        try {
            if (!sLogged.add(key)) return;
        } catch (Throwable ignored) {
            return;
        }
        Log.d(msg);
    }
}