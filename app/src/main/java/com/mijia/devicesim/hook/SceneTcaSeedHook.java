/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-27 08:23:21 +08:00
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
import java.util.HashSet;
import java.util.List;
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
 * <p>11-27 定：模拟设备<b>绝不借真实设备的身份</b>。库里的配置行属于那台真实设备，
 * 借它的 did / sa_id / payload_json，页面就会把「模拟设备B的动作」认回「真实设备A的动作」
 * （实测：选模拟热水器，列表里显示的是厨房那台真实热水器）。所以这里只做四件事 ——
 * <ol>
 *   <li>ViewModel 启动前：{@code loadData} 第一句就是「型号为空 → 发空状态」，而假 did 在
 *       这个上下文里 {@code getDeviceByDid} 解析不到、型号是空串。所以先在
 *       {@code OoooOO0()} 里把型号补成<b>这台设备自己的</b>型号，协程才有机会往下走；</li>
 *   <li>缓存版：本地 simple 表确有该型号配置时按原样放行（按假 did 查自己型号的行）；</li>
 *   <li>缓存版/远端版：本地没有该型号配置时，直接让 {@link SceneTcaDataHook#synthesize}
 *       按设备自己的 MIoT spec 现造 —— 条目里的 did / sa_id / payload_json 全是模拟设备自己的；</li>
 *   <li>过期判定：{@code LocalDataResource.isConfigExpired} 对「本地 simple 表确有该型号配置」
 *       的模拟 did 直接回 false，缓存版就会按假 did 查本地库出列表 —— 不联网也不借设备。</li>
 * </ol>
 * 现造不出条目时保持米家原行为：宁可空白，也不挂另一台设备的能力。
 *
 * <p>方法名是 11.10.623 混淆后的名字，已在反汇编里逐个核对过；版本再变时走
 * {@link HookSniper} 的三级解析：名字+形状 → 名字（形状变了全挂） → 形状+混淆名模式；
 * 页面类还没加载就交给 {@link HookScout} 延迟挂载。整条链失败也不影响米家其它功能。
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

    /**
     * 狙击手用的形状（混淆名变了也能认）：
     * <ul>
     *   <li>缓存版 {@code (String, Continuation) -> Object}；</li>
     *   <li>远端版 {@code (String, String, Continuation) -> Object}；</li>
     *   <li>过期判定 {@code (String, Continuation) -> Object}（Boolean 装箱后也是 Object）。</li>
     * </ul>
     * suspend 方法在字节码层面返回 Object，所以返回类型不做强校验（写 "Object" 即任意）。
     */
    private static final HookSniper.Shape SHAPE_CACHE =
            HookSniper.Shape.of("Object", "String", "Continuation");
    private static final HookSniper.Shape SHAPE_REMOTE =
            HookSniper.Shape.of("Object", "String", "String", "Continuation");
    private static final HookSniper.Shape SHAPE_NOARG = HookSniper.Shape.noArgs(null);

    /** 形状兜底时的混淆名模式：6~9 位、只由 o/O/0 组成（11.10.623 的仓库方法全这个长相）。*/
    private static final String RE_OBFUSCATED = "[oO0]{6,9}";

    /** simple 表里 {@code l} 非空的型号（本地就有能力配置），读库时整份刷新。*/
    private static volatile Set<String> sConfigured;
    private static final Set<String> sLogged = new HashSet<>();

    private SceneTcaSeedHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        // 狙击手负责「类在不在、方法叫什么、形状对不对」；类没加载就由侦察兵等着，
        // 页面类一进内存自动补挂，不会像旧版那样安装那一刻找不到就永远空白
        int n = 0;
        n += HookSniper.hook(lpparam.classLoader, GROUP, CL_REPO, M_CACHE,
                SHAPE_CACHE, RE_OBFUSCATED, SEED);
        n += HookSniper.hook(lpparam.classLoader, GROUP, CL_REPO, M_REMOTE,
                SHAPE_REMOTE, RE_OBFUSCATED, SEED);
        int nExp = HookSniper.hook(lpparam.classLoader, GROUP, CL_LDR,
                new String[]{M_LDR_EXPIRE}, SHAPE_CACHE, null, EXPIRED);
        int nVm = HookSniper.hook(lpparam.classLoader, GROUP, CL_VM,
                new String[]{M_VM_LOAD}, SHAPE_NOARG, null, SEED_VM);
        HookReport.ok(GROUP, "install", "TCA 三路缓存/远端共挂 " + n + " 个方法，过期判定 " + nExp
                + "，ViewModel 补型号 " + nVm + "（侦察待挂 " + HookScout.pendingCount() + "）");
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
                    // 本地缓存版 (did, continu)。11-27 定：模拟设备绝不借真实设备的身份 ——
                    // 库里那一行属于真实设备，换成它的 did 查出来的行带的是它的 sa_id / payload_json，
                    // 页面就会把「选了模拟设备B」认回「真实设备A」。所以只有两条路：
                    //   本地确有该型号配置 → 放行，按假 did 查它自己型号的行；
                    //   本地没有 → 直接按这台设备自己的 spec 现造（条目身份全是它自己的）。
                    logOnce("hit2:" + a0, "TCA 缓存查询 did=" + a0);
                    SimConfig cfg = ConfigRepository.current();
                    if (cfg == null || !cfg.injectEnabled) return;
                    SimDevice dev = cfg.findEnabledByDid(a0);
                    if (dev == null) return;
                    if (hasLocalConfig(dev.model)) {
                        logOnce("cache-local:" + a0, "TCA 缓存走本地库 did=" + a0 + " model=" + dev.model);
                        return;
                    }
                    Object built = SceneTcaDataHook.synthesize(param.method.getName(), dev, param.thisObject);
                    if (built != null) {
                        param.setResult(built);
                        logOnce("cache-spec:" + a0, "TCA 缓存按自身 spec 生成 did=" + a0
                                + " model=" + dev.model + " 入口=" + param.method.getName());
                        return;
                    }
                    logOnce("cache-keep:" + a0, "TCA 缓存 spec 生成不出条目，保持原 did="
                            + a0 + " model=" + dev.model);
                    return;
                }

                if (args.length >= 3 && args[1] instanceof String) {
                    String a1 = (String) args[1];
                    logOnce("hit:" + a0 + ":" + a1,
                            "TCA 远端查询 a0=" + a0 + " a1=" + a1);
                    SimConfig cfg = ConfigRepository.current();
                    if (cfg == null || !cfg.injectEnabled) return;
                    // 远端版：混淆后两个参数一个是型号、一个是 did，顺序因版本而异 ——
                    // 用「哪个是模拟 did」来认，不猜位置。11-27 定：同样不借真实设备，
                    // 本地没该型号配置就按自身 spec 现造；造不出就保持原参数，
                    // 宁可空白，也不把真实设备的能力挂到模拟设备上。
                    boolean a0Sim = cfg.findEnabledByDid(a0) != null;
                    boolean a1Sim = cfg.findEnabledByDid(a1) != null;
                    if (!a0Sim && !a1Sim) return;
                    String model = a0Sim ? a1 : a0;
                    SimDevice dev = cfg.findEnabledByDid(a0Sim ? a0 : a1);
                    if (dev == null) return;
                    Object built = SceneTcaDataHook.synthesize(param.method.getName(), dev, param.thisObject);
                    if (built != null) {
                        param.setResult(built);
                        logOnce("remote-spec:" + model, "TCA 远端按自身 spec 生成 model="
                                + model + " 入口=" + param.method.getName());
                        return;
                    }
                    logOnce("remote-keep:" + model, "TCA 远端 spec 生成不出条目，保持原参数 " + model);
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

    /**
     * ViewModel 启动加载前：假 did 的型号若为空，补成<b>这台设备自己的</b>型号。
     *
     * <p>11-27 定：以前这里还会把型号换成 donor（同品类真实设备）的型号，好让缓存版
     * 借那台设备的本地配置 —— 结果整页能力全是真实设备的，选完动作变成「真实设备的动作」。
     * 现在只补空值，绝不换型号；没有本地配置的型号交给 {@link SceneTcaDataHook#synthesize}
     * 按自身 spec 现造。
     */
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
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) return;

                String model = asString(HookUtil.readFieldQuiet(vm, F_VM_MODEL));
                if (model == null || model.isEmpty()) {
                    // 假 did 在这个上下文里 getDeviceByDid 解析不到，型号字段是空串，
                    // loadData 第一句就发空状态 —— 后面三路查询一次都不会被调用。
                    // v154：先把它补成配置里这台设备自己的型号（就是用户添加时的那个型号）。
                    if (HookUtil.setFieldQuiet(vm, F_VM_MODEL, dev.model)) {
                        Log.d("TCA VM 型号为空，补成自身型号 did=" + did + " model=" + dev.model);
                    }
                    return;
                }
                logOnce("vm:" + did, "TCA VM did=" + did + " model=" + model
                        + " 本地配置=" + hasLocalConfig(model));
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

    
    
    /* ---------- 本地「有配置的型号」清单 ---------- */

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
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
     * 读一次 TCA 库，记下「本地 simple 表里确有配置的型号」。
     *
     * <p>每次现读不缓存：米家启动时会同步并重建这张表，缓存一次就可能拿到「同步到一半」
     * 的空表（实测启动那会儿只剩 2 对型号）。查询只有几十行，一次页面打开最多读三次。
     *
     * <p>11-27 定：这里<b>不再</b>收「真实设备 (did, 型号) 对」当 donor —— 模拟设备绝不
     * 拿真实设备的 did 去借本地配置，借来的行会把它认回真实设备。
     */
    private static void loadLib() {
        Set<String> configured = new HashSet<>();
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
            }
        } catch (Throwable t) {
            Log.w("读取 TCA 本地配置失败: " + HookUtil.shortMsg(t));
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
        Log.d("TCA 本地配置型号=" + configured.size() + " 库=" + db);
        if (db != null) sConfigured = configured;
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