/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 21:45:30 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.BaseAdapter;
import android.widget.Toast;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceAcceptor;
import com.mijia.devicesim.core.DeviceNames;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotLabels;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.RequestedModel;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;
import com.mijia.devicesim.core.SpecRepository;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 「设备已重置 → 扫描中…」这一页（{@code RescanWifiV2Activity}）的注入。
 *
 * <p><b>11.10.623 的页面结构</b>（jadx 全量反编译 + 真机核对）：
 * <ul>
 *   <li>「发现设备」网格用的适配器是 {@code com.xiaomi.smarthome.wificonfig.OooO0O0}
 *       （{@code extends BaseAdapter}，11.9 时代的 {@code _m_j.iza} 已经不存在）；</li>
 *   <li>页面持有两个这种适配器的字段（一个给主网格、一个给超时兜底网格），
 *       刷新时都用同一个 {@code OooO0oo(List)}（clear + addAll + notifyDataSetChanged）灌数据；</li>
 *   <li>适配器的数据列表是它自己的 public final {@code List}，条目按类型分三支渲染：
 *       {@code android.net.wifi.ScanResult} / {@code BleDeviceGroup} / {@code _m_j.d08}；</li>
 *   <li>点击 Wi-Fi 卡最终只落到一句 {@code RescanWifiV2Activity.gotoWifiSettingPage(ScanResult)}。</li>
 * </ul>
 *
 * <p>所以这里不去猜混淆字段名，改成<b>按形状</b>干活：往「字段类型就是这个适配器类」的实例里
 * 塞一个合成出来的 {@code ScanResult}，再按 View 树把 {@code getAdapter()} 指向它的网格
 * 及其 GONE 祖先一齐放出来。米家自己就会把这张卡画得跟真扫到的一样；用户点它时拦住
 * {@code gotoWifiSettingPage}，就地落库、结束向导、回到首页。
 *
 * <p>SSID 形状必须是米家自己的 AP 命名规则：{@code 型号把"."换成"-" + "_miap" + 4位}。
 * 型号里有点号，SSID 里只允许横杠，米家的 {@code DeviceFactory.OooOoOO()} 会把横杠换回点号再比型号。
 * 后 4 位取 MAC 末 4 位，和真设备的命名方式一致。
 */
public final class ScanPageHook {

    private ScanPageHook() {
    }

    private static final String GROUP = "scan-page";

    /** 扫描页。类名未混淆（R8 只改 _m_j 下的类）。*/
    static final String CLS_RESCAN = "com.xiaomi.smarthome.wificonfig.RescanWifiV2Activity";
    /** 「发现设备」网格的适配器（两个扫描页共用这一个类，11.10.623 实测）。*/
    static final String CLS_ADAPTER = "com.xiaomi.smarthome.wificonfig.OooO0O0";
    /** 扫描卡标题的解析器：{@code Oooo00O(ScanResult)} → 可读中文名。*/
    private static final String CLS_DEVICE_FACTORY = "com.xiaomi.smarthome.device.DeviceFactory";

    /**
     * 11.10.623 的可选字段：读不到就只靠 View 树兜底，不崩。
     * 两个都是 {@code android.view.View}：
     * {@code o0OOoO0} = 「发现设备」区块（扫到东西时才显示），{@code o0OOoo0} = 超时提示块。
     */
    private static final String F_FOUND_BLOCK = "o0OOoO0";
    private static final String F_TIMEOUT_BLOCK = "o0OOoo0";

    private static final String AP_SUFFIX = "_miap";

    /** SSID → 型号：点击时反查，避免二次解析出错。*/
    private static final Map<String, String> sSsidToModel = new ConcurrentHashMap<>();
    /**
     * BSSID → 型号：同一张卡被米家再包一层、SSID 被裁掉时（配网页 intent 里只剩
     * {@code bssid} 字符串）就靠这条认。<b>只登记我们自己造的那一个 MAC</b>，
     * 真实热点、真实设备同型号真机开的热点都不在这张表里。
     */
    private static final Map<String, String> sBssidToModel = new ConcurrentHashMap<>();
    /** 当前这条合成结果。适配器里必须是同一个实例，点击时才好认。*/
    private static volatile ScanResult sFake;
    private static volatile String sFakeModel = "";

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        HookSniper.hook(loader, GROUP, CLS_RESCAN, new String[]{"onCreate"},
                HookSniper.Shape.of(null, "Bundle"), "onCreate", ON_CREATE);
        HookSniper.hook(loader, GROUP, CLS_RESCAN, new String[]{"onResume"},
                HookSniper.Shape.noArgs(null), "onResume", ON_RESUME);
        HookSniper.hook(loader, GROUP, CLS_RESCAN, new String[]{"gotoWifiSettingPage"},
                HookSniper.Shape.of(null, "ScanResult"), "gotoWifiSettingPage", ON_GOTO_SETTING);
        HookSniper.hook(loader, GROUP, CLS_RESCAN, new String[]{"onTimeOut"},
                HookSniper.Shape.noArgs(null), "onTimeOut", ON_TIMEOUT);
        // 适配器两个扫描页共用；类/方法改名时按「List 入参」形状兜底
        HookSniper.hook(loader, GROUP, CLS_ADAPTER, new String[]{"OooO0oo"},
                HookSniper.Shape.of(null, "List"), "[oO0]{6,9}", ON_SET_DATA);
        // Wi-Fi 卡的标题是适配器拿 SSID 找这个解析器算出来的，名字带不带「#」全在这儿
        HookSniper.hook(loader, GROUP, CLS_DEVICE_FACTORY, new String[]{"Oooo00O"},
                HookSniper.Shape.of("String", "ScanResult"), "Oooo00O", ON_SCAN_TITLE);
    }

    /* ---------- 页面生命周期：把合成条目塞进网格 ---------- */

    private static final XC_MethodHook ON_CREATE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                prepare(param.thisObject);
            } catch (Throwable t) {
                Log.e("扫描页 onCreate 注入失败（米家流程不受影响）", t);
            }
        }
    };

    private static final XC_MethodHook ON_RESUME = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                prepare(param.thisObject);
            } catch (Throwable t) {
                Log.e("扫描页 onResume 注入失败", t);
            }
        }
    };

    /** 页面扫超时会自己把「发现设备」区块藏起来，这里补一次，卡片不会一闪就没。*/
    private static final XC_MethodHook ON_TIMEOUT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!injectionEnabled()) {
                    retract(param.thisObject);
                    return;
                }
                ScanResult fake = sFake;
                if (fake == null) return;
                inject(param.thisObject, fake);
            } catch (Throwable t) {
                Log.e("超时后补扫描条目失败", t);
            }
        }
    };

    /** 读出页面目标型号 → 合成身份 → 下一轮主线程把卡片补进适配器并让区块可见。*/
    private static void prepare(Object activity) {
        if (!injectionEnabled()) {
            retract(activity);
            return;
        }
        String model = readModel(activity);
        if (model == null || model.isEmpty()) return;
        ScanResult fake = fakeFor(model, activity);
        if (fake == null) return;
        final Object act = activity;
        final ScanResult item = fake;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                inject(act, item);
            } catch (Throwable t) {
                Log.e("扫描页卡片注入失败", t);
            }
        });
    }

    /**
     * 扫描结果注入的总闸，和首页「⋮ → 更多开关 → 扫描结果注入」读的是同一个
     * {@link SimConfig#scanInjection}（外加总注入开关）。
     *
     * <p>为什么每个入口都要单独问一次：这条页面注入根本不经 {@code DeviceScanManager.dispatchOnScan}，
     * 以前只在 dispatchOnScan 那条路上判开关，所以关了开关这一页照样冒卡片。每次都是直接读配置缓存，
     * 不加任何状态位/计时器；开关一改，配置观察者重载后下一次 onCreate/onResume 或适配器刷新就按新值走。
     */
    static boolean injectionEnabled() {
        SimConfig cfg = ConfigRepository.current();
        return cfg.scanInjection && cfg.injectEnabled;
    }

    /** 开关被关掉时把已经塞进网格的合成条目撤回来，别让它在屏上赖着。*/
    private static void retract(Object activity) {
        ScanResult fake = sFake;
        if (activity == null || fake == null) return;
        int left = 0;
        int seen = 0;
        for (Object adapter : adaptersIn(activity)) {
            seen++;
            left += removeItem(adapter, fake);
        }
        if (seen == 0) return;
        if (left == 0) setVisibleField(activity, F_FOUND_BLOCK, View.GONE);
        Log.d("扫描注入已关闭，撤下合成条目：适配器 " + seen + " 个，剩余 " + left + " 条");
    }

    /** 型号优先取 intent 的 "model"（米家所有扫描页都按这个键传），再退回页面上的型号字段。*/
    private static String readModel(Object activity) {
        String model = "";
        try {
            if (activity instanceof Activity) {
                String m = DeviceAcceptor.modelInIntent(((Activity) activity).getIntent());
                if (m != null) model = m;
            }
        } catch (Throwable ignored) {
        }
        if (model.isEmpty()) {
            String m = DeviceAcceptor.modelOf(activity);
            if (m != null) model = m;
        }
        model = model == null ? "" : model.trim();
        if (model.isEmpty()) {
            // 兜底：向导上一页（选型号/重置页）已经把「他要添加的型号」登记过了。
            // 扫描页的 intent 在部分入口只带 SSID/mac，不带 model，这里接着用那份记录，
            // 保证卡片的身份和后面落库的是同一台。
            RequestedModel.Record rec = RequestedModel.get();
            if (rec != null) model = rec.model;
        }
        if (!model.isEmpty()) {
            RequestedModel.capture(model, activity);
        }
        return model;
    }

    /** 同一型号复用同一个 ScanResult；换型号才重建，避免网格里条目抖动。*/
    private static ScanResult fakeFor(String model, Object activity) {
        ScanResult cached = sFake;
        if (cached != null && model.equals(sFakeModel)) return cached;
        RequestedModel.Record rec = RequestedModel.get();
        String mac = (rec != null && model.equals(rec.model)) ? rec.device.mac : SimDevice.newMac();
        ScanResult sr = new ScanResult();
        sr.SSID = ssidFor(model, mac);
        sr.BSSID = mac.toLowerCase(Locale.ROOT);
        sr.capabilities = "[WPA2-PSK-CCMP][ESS]";
        sr.frequency = 2437;
        sr.level = -45;
        sr.timestamp = android.os.SystemClock.elapsedRealtime() * 1000L;
        sSsidToModel.put(sr.SSID, model);
        sBssidToModel.put(sr.BSSID, model);
        sFake = sr;
        sFakeModel = model;
        Log.d("合成扫描条目 SSID=" + sr.SSID + " model=" + model + " bssid=" + sr.BSSID);
        return sr;
    }

    /** 米家的 AP 命名规则：点号换横杠 + "_miap" + MAC 末 4 位。*/
    private static String ssidFor(String model, String mac) {
        String compact = mac == null ? "" : mac.replace(":", "").toUpperCase(Locale.ROOT);
        String tail = compact.length() >= 4 ? compact.substring(compact.length() - 4) : "A1B2";
        return model.replace('.', '-') + AP_SUFFIX + tail;
    }

    /** 把条目补进页面的每一个扫描适配器，并把网格放出来。*/
    private static void inject(Object activity, ScanResult fake) {
        if (activity == null || fake == null) return;
        List<Object> adapters = adaptersIn(activity);
        if (adapters.isEmpty()) {
            Log.v("扫描页还没建好适配器，等下一次刷新");
            return;
        }
        int total = 0;
        for (Object adapter : adapters) {
            total += appendItem(adapter, fake);
        }
        showFound(activity, adapters);
        Log.d("扫描网格已更新：适配器 " + adapters.size() + " 个，共 " + total + " 条");
    }

    private static boolean sameSsid(ScanResult a, ScanResult b) {
        return a != null && b != null && a.SSID != null && a.SSID.equals(b.SSID);
    }

    /**
     * 扫描卡标题加「#」：适配器画 {@code ScanResult} 那一支时标题就是
     * {@code DeviceFactory.Oooo00O(sr)}（格式串是 {@code "%s"}，原样显示）。
     * 只对我们合成的那条（SSID 登记在 {@link #sSsidToModel}）改返回，
     * 真实 AP 的 SSID 是另一个，一个字符都不会被动。
     */
    private static final XC_MethodHook ON_SCAN_TITLE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object arg = (param.args == null || param.args.length == 0) ? null : param.args[0];
                if (!(arg instanceof ScanResult)) return;
                String ssid = ((ScanResult) arg).SSID;
                if (ssid == null || !sSsidToModel.containsKey(ssid)) return;
                Object r = param.getResult();
                if (!(r instanceof String)) return;
                String title = ((String) r).trim();
                if (title.startsWith(SimDevice.NAME_PREFIX)) return;
                param.setResult(SimDevice.NAME_PREFIX + title);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 「发现设备」区块默认 GONE，这里连同网格一起显示，并把超时提示收起来。*/
    private static void showFound(Object activity, List<Object> adapters) {
        setVisibleField(activity, F_FOUND_BLOCK, View.VISIBLE);
        setVisibleField(activity, F_TIMEOUT_BLOCK, View.GONE);
        // 字段名在别的版本里可能变；按「网格的 adapter 就是它」找一遍，把整条祖先链放出来
        revealGrids(activity, adapters);
    }

    /* ---------- 米家自己刷新网格时，把条目补回去 ---------- */

    /**
     * {@code OooO0oo(List)} = clear + addAll + notifyDataSetChanged：米家每次刷新都会把我们的条目
     * 一起冲掉，所以这里在它刷完之后补回同一张列表。适配器上只挂着 {@code Context}，宿主页按它分流。
     */
    private static final XC_MethodHook ON_SET_DATA = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object adapter = param.thisObject;
                Object ctx = firstField(adapter, Context.class);
                if (!(ctx instanceof Activity)) return;
                String host = ctx.getClass().getName();
                if (BleScanPageHook.CLS_PAGE.equals(host)) {
                    // 蓝牙扫描页用的是同一个适配器类，转给它自己的补条目逻辑
                    BleScanPageHook.onAdapterRefreshed(adapter, ctx);
                    return;
                }
                if (!CLS_RESCAN.equals(host)) return;
                if (!injectionEnabled()) {
                    retract(ctx);
                    return;
                }
                String model = readModel(ctx);
                if (model.isEmpty()) return;
                ScanResult fake = fakeFor(model, ctx);
                appendItem(adapter, fake);
                List<Object> one = new ArrayList<>(1);
                one.add(adapter);
                showFound(ctx, one);
            } catch (Throwable t) {
                Log.e("适配器刷新时补条目失败", t);
            }
        }
    };

    /* ---------- 点击：本地落库，不走真实配网 ---------- */

    private static final XC_MethodHook ON_GOTO_SETTING = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object arg = param.args[0];
                if (!(arg instanceof ScanResult)) return;
                ScanResult sr = (ScanResult) arg;
                String model = sSsidToModel.get(sr.SSID);
                if (model == null) return; // 不是我们造的条目，放行米家真实流程

                SimConfig cfg = ConfigRepository.current();
                if (!cfg.acceptShortCircuit) {
                    Log.d("acceptShortCircuit 已关闭，交给米家真实配网");
                    return;
                }
                param.setResult(Boolean.TRUE);
                commit(param.thisObject, model);
            } catch (Throwable t) {
                Log.e("点击短路失败（已放行给米家）", t);
            }
        }
    };

    /**
     * 把「用户正在添加的这台」落进配置清单，然后按成功返回上一页。
     * 首页列表由 {@link DeviceListHook} 负责显示，所以这里落库成功就等于「添加完成」。
     *
     * <p>{@code activity} 允许为 null：从点击闸（那层拿不到页面对象）接管时照样落库，
     * 只是没法替用户关页。
     */
    static void commit(Object activity, String model) {
        Context ctx = DeviceListHook.context();
        if (ctx == null && activity instanceof Context) ctx = (Context) activity;
        if (ctx == null) {
            Log.w("没有 Context，无法落库");
            return;
        }
        SimConfig cfg = ConfigRepository.current();
        RequestedModel.Record rec = RequestedModel.get();
        SimDevice d = (rec != null && model.equals(rec.model)) ? rec.device : null;
        if (d == null) {
            for (SimDevice x : cfg.devices) {
                if (model.equals(x.model)) {
                    d = x;
                    break;
                }
            }
        }
        if (d == null) {
            d = new SimDevice();
            d.model = model;
            d.did = SimDevice.newDid(didsOf(cfg));
            d.token = SimDevice.newToken();
            d.mac = SimDevice.newMac();
        }
        // 走米家「添加设备」加进来的这一类统一带「#」前缀：清单里一眼认出是模拟的。
        // 名字用米家自己的官方中文名（names.bin），拿不到才退 spec 类型名/型号 ——
        // 以前这里直接拿 model 当名字，落库就成了「#mibx5.fridge.bd47m」。
        String base = baseNameOf(ctx, model);
        String shown = (d.name == null || d.name.trim().isEmpty()) ? base : d.name.trim();
        d.name = SimDevice.taggedName(shown, model);
        d.model = model;
        d.type = Consts.TYPE_WIFI;
        d.enabled = true;
        d.online = true;
        if (cfg.findByDid(d.did) == null) {
            d.sort = cfg.size() + 1;
            cfg.devices.add(d);
        }
        boolean saved = ConfigRepository.save(ctx, cfg);
        Log.d("添加完成 did=" + d.did + " model=" + d.model + " name=" + d.name + " 落库=" + saved);
        HookReport.ok(GROUP, "accept", "已添加 " + d.model + " did=" + d.did + " 落库=" + saved);

        try {
            Toast.makeText(ctx, "已添加模拟设备：" + (d.name == null || d.name.isEmpty() ? d.model : d.name),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }

        if (activity instanceof Activity) {
            Activity act = (Activity) activity;
            Intent result = new Intent();
            result.putExtra("finish", true);
            act.setResult(Activity.RESULT_OK, result);
            act.finish();
        }
    }

    /**
     * 落库时给人看的名字：米家官方中文名（{@link DeviceNames}）→ spec 类型的中文名 → 型号。
     * 蓝牙扫描页也走这里，两个添加入口的命名保持一致。
     */
    static String baseNameOf(Context ctx, String model) {
        if (model == null || model.isEmpty()) return "";
        String official = DeviceNames.of(ctx, model);
        if (!official.isEmpty()) return official;
        MiotSpec spec = SpecRepository.forModel(ctx, model);
        if (spec != null) {
            // typeLabel 是「中文(英文slug)」，名字只要前半段
            String t = MiotLabels.typeLabel(spec.category());
            int paren = t.indexOf('(');
            if (paren > 0) t = t.substring(0, paren).trim();
            if (!t.isEmpty()) return t;
        }
        return model;
    }

    private static java.util.List<String> didsOf(SimConfig cfg) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SimDevice d : cfg.devices) out.add(d.did);
        return out;
    }

    /* ---------- 共用的按形状工具（蓝牙扫描页也走这里） ---------- */

    /**
     * 页面里所有「声明类型就是这个扫描适配器」的实例。
     *
     * <p>为什么按字段类型而不是按字段名：11.9 → 11.10 字段名整批重排过，
     * 但「页面上挂着 {@code wificonfig.OooO0O0} 类型的适配器」这个事实没变。
     */
    static List<Object> adaptersIn(Object host) {
        List<Object> out = new ArrayList<>();
        if (host == null) return out;
        Class<?> c = host.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return out;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!CLS_ADAPTER.equals(f.getType().getName())) continue;
                Object v = HookUtil.readFieldQuiet(host, f.getName());
                if (v == null) continue;
                boolean dup = false;
                for (Object o : out) if (o == v) { dup = true; break; }
                if (!dup) out.add(v);
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /**
     * 适配器自己的数据列表：它只有一个 {@code List} 字段（11.10.623 实测），
     * 按类型找比按名字找稳。
     */
    static List dataListOf(Object adapter) {
        if (adapter == null) return null;
        Class<?> c = adapter.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return null;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!List.class.isAssignableFrom(f.getType())) continue;
                Object v = HookUtil.readFieldQuiet(adapter, f.getName());
                if (v instanceof List) return (List) v;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 往适配器里塞一条 ScanResult（已同 SSID 就不重复），返回最终条数。*/
    static int appendItem(Object adapter, ScanResult fake) {
        if (adapter == null || fake == null) return 0;
        List list = dataListOf(adapter);
        if (list == null) return 0;
        for (Object o : list) {
            if (o instanceof ScanResult && sameSsid((ScanResult) o, fake)) return list.size();
        }
        // 米家自己那条「同类型其他设备」影子卡（d08）和我们是同一个型号，留在一格里就是
        // 两张同型号卡、只有我们这张能加；先把它剔掉。真实设备一律不动 —— 见 stripShadowCards。
        stripShadowCards(adapter, fake, modelOf(fake.SSID));
        // v154：同一台模拟设备在页面上还有别的「投影」（账号里那台的卡片、上一轮合成的旧条目），
        // 它们名字都带「#」或带着我们的 did/mac，一并剔掉，一格里只留我们这一张。
        pruneSimEchoes(adapter, fake);
        // 放第一格：用户一眼就能看见（网格有上限，放末尾可能被「更多」折进去）
        list.add(0, fake);
        notifyChanged(adapter);
        return list.size();
    }

    /**
     * 只剔「影子卡」：米家自己补进网格、同型号但点不出结果的 {@code _m_j.d08}
     * （「同类型其他设备」分组）。<b>真实设备一条都不碰</b>：
     * <ul>
     *   <li>{@code ScanResult}（真扫到的 AP，包括同型号真机开的热点）原样保留；</li>
     *   <li>{@code BleDevice} / {@code BleDeviceGroup}（真扫到的蓝牙设备）原样保留。</li>
     * </ul>
     * 判断影子卡按形状，不按混淆名：不是 ScanResult、没有 {@code Device.model} 字段、
     * 但兜里揣着一组条目（d08 的 {@code List<e08>}）。只把组里属于本型号的条目摘掉，
     * 组里有别的型号就留着一张卡给它们；摘空了才连卡一起撤。
     *
     * @return 实际摘掉的条目数（含整卡撤掉的那一下）
     */
    static int stripShadowCards(Object adapter, Object fake, String model) {
        if (adapter == null || model == null || model.isEmpty()) return 0;
        List list = dataListOf(adapter);
        if (list == null) return 0;
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Object item = list.get(i);
            if (item == fake || item instanceof ScanResult) continue;
            if (HookUtil.readFieldQuiet(item, "model") instanceof String) continue; // 真蓝牙设备
            List sub = firstListOf(item);
            if (sub == null) continue; // 不认识的东西，别碰
            int pruned = pruneModel(sub, model);
            if (pruned <= 0) continue;
            removed += pruned;
            if (sub.isEmpty()) {
                list.remove(i);
                removed++;
            }
        }
        if (removed > 0) notifyChanged(adapter);
        return removed;
    }

    /** 从一条影子卡的子列表里摘掉属于 {@code model} 的条目。*/
    private static int pruneModel(List entries, String model) {
        int removed = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (anyStringInModel(entries.get(i), model, 2)) {
                entries.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /**
     * 把「同一台模拟设备在页面上的其他投影」剔掉，网格里只留我们注入的那一张。
     *
     * <p><b>为什么要这一步</b>：米家的扫描页会把同一份数据灌进两个网格，账号里同类型的那台
     * 也会被再画一张影子卡。v153 只按 {@code _m_j.d08} 的形状剔，漏掉了名字已经被我们改成
     * 「#中文名」的另一种投影 —— 现场就是两张一模一样的「#米家扫拖机器人2C」，一张点下去没反应。
     *
     * <p><b>凭什么认定是「我们的」</b>：只认两个特征 ——
     * <ol>
     *   <li>卡片名字（或条目里任意一层字符串）以「#」开头：那是 {@link SimDevice#taggedName}
     *       给模拟设备统一加的前缀，真机名字不会带；</li>
     *   <li>did / mac / 显示名恰好等于配置里的某台模拟设备。</li>
     * </ol>
     * 真实设备的名字、did、mac 都不会命中，所以真机卡片一张不碰。
     *
     * @return 摘掉的条目数（含整卡撤掉的那一下）
     */
    static int pruneSimEchoes(Object adapter, Object fake) {
        List list = dataListOf(adapter);
        if (list == null) return 0;
        List<String> ids = simIdentities();
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Object item = list.get(i);
            if (item == fake) continue;            // 我们自己那张，留着
            if (item instanceof ScanResult) continue; // 真 Wi-Fi 热点，别碰
            if (looksLikeSim(item, ids, 2)) {
                list.remove(i);
                removed++;
                continue;
            }
            // 分组卡（d08 之类）：只摘属于模拟设备的子条目，组里还有真设备就留卡
            List sub = firstListOf(item);
            if (sub == null) continue;
            int pruned = 0;
            for (int k = sub.size() - 1; k >= 0; k--) {
                if (looksLikeSim(sub.get(k), ids, 1)) {
                    sub.remove(k);
                    pruned++;
                }
            }
            removed += pruned;
            if (pruned > 0 && sub.isEmpty()) {
                list.remove(i);
                removed++;
            }
        }
        if (removed > 0) notifyChanged(adapter);
        return removed;
    }

    /** 模拟设备的可认身份：did / mac / 显示名。每次现取，配置一改就跟着变（不加重试位）。*/
    private static List<String> simIdentities() {
        List<String> out = new ArrayList<>();
        try {
            SimConfig cfg = ConfigRepository.current();
            for (SimDevice d : cfg.devices) {
                addIdentity(out, d.did);
                addIdentity(out, d.mac);
                addIdentity(out, d.name);
            }
        } catch (Throwable ignored) {
        }
        try {
            RequestedModel.Record rec = RequestedModel.get();
            if (rec != null && rec.device != null) {
                addIdentity(out, rec.device.did);
                addIdentity(out, rec.device.mac);
                addIdentity(out, rec.device.name);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addIdentity(List<String> out, String v) {
        if (v == null) return;
        v = v.trim();
        if (!v.isEmpty() && !out.contains(v)) out.add(v);
    }

    /**
     * 这一条是不是「我们造的东西」：名字以「#」开头，或某个字符串字段命中模拟设备身份。
     * 会往子列表里看一层（{@code d08 → e08}），深度由外层给。
     */
    private static boolean looksLikeSim(Object target, List<String> ids, int depth) {
        if (target == null || depth < 0) return false;
        String name = "";
        Object n = HookUtil.readFieldQuiet(target, "name");
        if (n instanceof String) name = ((String) n).trim();
        if (name.isEmpty() && !(target instanceof ScanResult)) {
            // 有的条目名字只有一个 getName()（BleDevice 系）；读不到就算了，不硬造
            Object g = HookUtil.invokeQuiet(target, target.getClass(), "getName", new Class<?>[0]);
            if (g instanceof String) name = ((String) g).trim();
        }
        if (name.startsWith(SimDevice.NAME_PREFIX)) return true;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return false;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object v = HookUtil.readFieldQuiet(target, f.getName());
                if (v instanceof String) {
                    String s = ((String) v).trim();
                    if (!s.isEmpty() && ids.contains(s)) return true;
                } else if (v instanceof List && depth > 0) {
                    for (Object e : (List) v) {
                        if (looksLikeSim(e, ids, depth - 1)) return true;
                    }
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** 条目身上第一个非空 {@code List} 字段（d08 的条目组）。*/
    private static List firstListOf(Object item) {
        if (item == null) return null;
        Class<?> c = item.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return null;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object v = HookUtil.readFieldQuiet(item, f.getName());
                if (v instanceof List) return (List) v;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 递归两层找型号：d08 自己的 String 字段 + 它 List 里每个 e08 的 String 字段。*/
    private static boolean anyStringInModel(Object target, String model, int depth) {
        if (target == null || depth <= 0) return false;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return false;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object v = HookUtil.readFieldQuiet(target, f.getName());
                if (v instanceof String) {
                    if (model.equalsIgnoreCase(((String) v).trim())) return true;
                } else if (v instanceof List && depth > 1) {
                    for (Object e : (List) v) {
                        if (anyStringInModel(e, model, depth - 1)) return true;
                    }
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /** 合成条目的 SSID → 型号：先查登记表，查不到再按 {@code _miapXXXX} 尾巴切。*/
    static String modelOf(String ssid) {
        if (ssid == null) return "";
        String known = sSsidToModel.get(ssid);
        if (known != null) return known;
        int at = ssid.indexOf(AP_SUFFIX);
        String base = at > 0 ? ssid.substring(0, at) : ssid;
        return base.replace('-', '.').trim();
    }

    /**
     * 这条扫描结果是不是<b>我们合成的</b>那张卡？是就返回型号，不是返回 null。
     *
     * <p>只认两张登记表（SSID / BSSID），不认「长得像」：
     * 真实设备同型号真机开的热点也是 {@code 型号_miapXXXX} 这种名字，
     * 按名字猜会把它一起短路掉 —— 用户明确要求真实设备必须能正常添加。
     */
    static String syntheticModelOf(ScanResult sr) {
        if (sr == null) return null;
        if (sr.SSID != null) {
            String m = sSsidToModel.get(sr.SSID);
            if (m != null) return m;
        }
        String bssid = sr.BSSID == null ? null : sr.BSSID.toLowerCase(Locale.ROOT);
        return bssid == null ? null : sBssidToModel.get(bssid);
    }

    /**
     * 配网页 / Wi-Fi 选择页的 intent 里是否带着我们那张卡。
     *
     * <p>11.10.623 的 {@code _m_j.sn5.OooO0o} 拼出来的 intent 一定带 {@code scanResult}
     * （{@link ScanResult} 本身）+ {@code model} + {@code bssid}；页面闸只在
     * 「带着我们造的那张卡」时才动手，真设备的 intent 里那两个值都命中不了登记表。
     */
    static String syntheticModelInIntent(Intent intent) {
        if (intent == null) return null;
        for (String key : new String[]{"scanResult", "bind_aiot_wifi", "aiot_wifi"}) {
            try {
                Object o = intent.getParcelableExtra(key);
                if (o instanceof ScanResult) {
                    String m = syntheticModelOf((ScanResult) o);
                    if (m != null) return m;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            String bssid = intent.getStringExtra("bssid");
            if (bssid != null) return sBssidToModel.get(bssid.toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 从适配器里去掉指定实例或同 SSID 的条目，返回剩余条数。*/
    static int removeItem(Object adapter, ScanResult fake) {
        if (adapter == null || fake == null) return 0;
        List list = dataListOf(adapter);
        if (list == null) return 0;
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            Object o = list.get(i);
            if (o == fake || (o instanceof ScanResult && sameSsid((ScanResult) o, fake))) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) notifyChanged(adapter);
        return list.size();
    }

    static void notifyChanged(Object adapter) {
        try {
            if (adapter instanceof BaseAdapter) ((BaseAdapter) adapter).notifyDataSetChanged();
        } catch (Throwable ignored) {
        }
    }

    /** 类型是 {@code want} 的第一个字段值（适配器上的 Context 就是宿主页）。*/
    static Object firstField(Object target, Class<?> want) {
        if (target == null) return null;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (!want.isAssignableFrom(f.getType())) continue;
                    Object v = HookUtil.readFieldQuiet(target, f.getName());
                    if (want.isInstance(v)) return v;
                }
            } catch (Throwable t) {
                return null;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    static void setVisibleField(Object target, String field, int visibility) {
        Object v = HookUtil.readFieldQuiet(target, field);
        if (v instanceof View && ((View) v).getVisibility() != visibility) {
            ((View) v).setVisibility(visibility);
        }
    }

    /**
     * 按 View 树把「adapter 就是我们这几个」的网格放出来，连带祖先上被 GONE 的容器。
     *
     * <p>为什么不用字段名直接 {@code setVisibility}：区块/网格字段名每版都会变，
     * 而「网格的 adapter 是哪个实例」是运行期事实。祖先只往上放 6 层，避免把全屏遮罩也放出来。
     */
    static void revealGrids(Object activity, List<Object> adapters) {
        if (!(activity instanceof Activity) || adapters == null || adapters.isEmpty()) return;
        View root;
        try {
            root = ((Activity) activity).getWindow().getDecorView();
        } catch (Throwable t) {
            return;
        }
        walkTree(root, adapters, 0);
    }

    private static void walkTree(View v, List<Object> adapters, int depth) {
        if (v == null || depth > 40) return;
        if (v instanceof android.widget.AdapterView) {
            Object ad = null;
            try {
                ad = ((android.widget.AdapterView<?>) v).getAdapter();
            } catch (Throwable ignored) {
            }
            if (ad != null) {
                for (Object want : adapters) {
                    if (want == ad) {
                        v.setVisibility(View.VISIBLE);
                        ViewParent p = v.getParent();
                        int up = 0;
                        while (p instanceof View && up < 6) {
                            View pv = (View) p;
                            if (pv.getVisibility() != View.VISIBLE) pv.setVisibility(View.VISIBLE);
                            p = pv.getParent();
                            up++;
                        }
                        break;
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkTree(g.getChildAt(i), adapters, depth + 1);
            }
        }
    }
}