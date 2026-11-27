/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-27 08:41:15 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceAcceptor;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.RequestedModel;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 蓝牙型号的「设备已重置 → 扫描设备」页（{@code ScanChooseBluetoothDeviceV2}）注入。
 *
 * <p><b>为什么单独一个类</b>：Wi-Fi 型号走 {@code RescanWifiV2Activity}，蓝牙型号走这个页面，
 * 两者是两个 Activity，但从 11.10.623 起共用同一个网格适配器
 * {@code com.xiaomi.smarthome.wificonfig.OooO0O0}（11.9 的 {@code _m_j.iza} 已不存在）。
 * 页面自己的逻辑是：
 * <ul>
 *   <li>网格适配器 {@code OooO0O0} 的条目分三支：{@code ScanResult} / {@code BleDeviceGroup} / {@code _m_j.d08}；</li>
 *   <li>扫到蓝牙设备后，页面的入口是三个单参 void 方法 {@code o00Oo0 / o00Ooo / o0Oo0oo(BleDevice)}，
 *       名字每版都会变，所以按<b>形状</b>挂；</li>
 *   <li>25 秒超时后收起「发现设备」区块、摆出底部「重新搜索」栏。</li>
 * </ul>
 *
 * <p>所以这里做两件事：
 * <ol>
 *   <li>造一个 {@code BleDeviceGroup}（继承 {@code BleDevice}，适配器认这个类型才会画蓝牙卡），
 *       塞进网格第一格，并把「发现设备」区块显出来、收起底部超时栏；</li>
 *   <li>拦三个 BleDevice 入口：点到我们这条就就地落库、结束向导，不碰真实蓝牙配对。</li>
 * </ol>
 *
 * <p>米家这个页面用 {@code null} 参数表示「用户返回」，所以拦截前必须先比对 mac，
 * 不能一把全拦，否则系统返回键会失效。
 *
 * <p><b>v152 去重与命名（2026-11-26 现场）</b>：米家自己也会往「同类型其他设备」网格里塞一条
 * 同型号的 {@code _m_j.d08} 条目（名字由插件记录翻成中文，点它是 OtherRegionDevice 流程，
 * 点了没反应），再加上我们注入的 {@code BleDeviceGroup}（名字当时写的是裸型号），
 * 一格里就出现「中文名 + 型号」两张卡，只有后者能加。
 *
 * <p><b>v153 收紧（同日晚）</b>：v152 的「同型号就剔」会把用户<b>真实存在</b>的同一型号设备
 * 一起清掉，那是错的。现在只剔米家那条非可点的影子卡（{@code _m_j.d08} 分组），
 * 真扫到的 {@code BleDevice}/{@code BleDeviceGroup}/{@code ScanResult} 一条不碰；
 * 卡片显示名也统一带「#」前缀，真机与模拟设备一眼分得开、两张都能点。
 *
 * <p><b>v154 去重（同日晚）</b>：米家会把同一份扫描数据同时灌进页面的两个网格，账号里那台
 * 模拟设备还会再被画一张「#中文名」的卡片；按型号认不出来（它是设备清单的投影，不是 d08），
 * 于是现场又出现两张一模一样的「#米家扫拖机器人2C」。现在按<b>模拟身份</b>去重：
 * 名字带「#」、或 did/mac 命中配置里任何一台模拟设备的条目一律摘掉，真机不命中、原样保留。
 */
public final class BleScanPageHook {

    private BleScanPageHook() {
    }

    private static final String GROUP = "ble-scan-page";

    /** 蓝牙扫描页（类名未混淆）；{@link ScanPageHook} 的适配器钩子要按它分流。*/
    static final String CLS_PAGE = "com.xiaomi.smarthome.wificonfig.ScanChooseBluetoothDeviceV2";
    /** 蓝牙设备基类，用来按形状匹配页面的点击入口。*/
    private static final String CLS_BLE_DEVICE = "com.xiaomi.smarthome.device.BleDevice";
    /** 蓝牙设备分组，网格只认这个类型。*/
    private static final String CLS_BLE_GROUP = "com.xiaomi.smarthome.device.bluetooth.BleDeviceGroup";

    /** 11.10.623 未混淆字段：底部「找不到设备 / 重新搜索」栏，默认 GONE。*/
    private static final String F_TIMEOUT_LAYOUT = "mBottomTimeoutLayout";
    /** 「发现设备」区块（和 Wi-Fi 扫描页同名字段，刷新 Runnable 按 list.size 显隐它）。*/
    private static final String F_FOUND_BLOCK = "o0OOoO0";

    /** 反射拿到的类；为空 = 这个版本没有，整条链降级。*/
    private static Class<?> sGroupClass;
    private static Class<?> sBleClass;

    /** 当前合成的那条蓝牙设备（适配器里必须是同一个实例，点击时才好认）。*/
    private static volatile Object sFake;
    private static volatile String sFakeModel = "";
    private static volatile String sFakeMac = "";

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        sGroupClass = HookUtil.findClassQuiet(CLS_BLE_GROUP, loader);
        sBleClass = HookUtil.findClassQuiet(CLS_BLE_DEVICE, loader);
        if (sGroupClass == null) {
            HookReport.skip(GROUP, CLS_BLE_GROUP, "蓝牙设备分组类不存在（整条链降级）");
            return;
        }

        // 页面三个生命周期点走狙击手；类晚加载由侦察兵补挂
        HookSniper.hook(loader, GROUP, CLS_PAGE, new String[]{"onCreate"},
                HookSniper.Shape.of(null, "Bundle"), "onCreate", ON_CREATE);
        HookSniper.hook(loader, GROUP, CLS_PAGE, new String[]{"onResume"},
                HookSniper.Shape.noArgs(null), "onResume", ON_RESUME);
        HookSniper.hook(loader, GROUP, CLS_PAGE, new String[]{"onTimeOut"},
                HookSniper.Shape.noArgs(null), "onTimeOut", ON_TIMEOUT);

        // 点击入口本来就要按形状扫（名字每版都在换）：类在就现在扫，类晚到就交给侦察兵
        Class<?> page = HookUtil.findClassQuiet(CLS_PAGE, loader);
        if (page != null) {
            hookBleEntries(page);
        } else {
            HookScout.await(CLS_PAGE, c -> hookBleEntries(c));
        }
    }

    /**
     * 按形状挂页面的蓝牙点击入口：单参、参数是 {@code BleDevice}、返回 void、非静态。
     *
     * <p>为什么不写死名字：11.9 是 {@code OooOO0o}，11.10 变成了 {@code o00Oo0 / o00Ooo / o0Oo0oo}，
     * 这个形状却是稳定的（页面把「用户选了哪个蓝牙设备」统一从这三个口子送出去）。
     * 排除 {@code o00o0O(BleDevice)->SpannableStringBuilder} 这类只做文案的同参方法。
     */
    private static int hookBleEntries(Class<?> page) {
        if (sBleClass == null) return 0;
        int hooked = 0;
        for (Method m : page.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != void.class) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 1 || !pts[0].isAssignableFrom(sBleClass)) continue;
            // 方法已经按形状选中，统一走狙击手的去重挂载口
            if (HookSniper.hookResolved(m, ON_PICK)) hooked++;
        }
        return hooked;
    }

    /* ---------- 页面生命周期 ---------- */

    private static final XC_MethodHook ON_CREATE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                prepare(param.thisObject);
            } catch (Throwable t) {
                Log.e("蓝牙扫描页 onCreate 注入失败（米家流程不受影响）", t);
            }
        }
    };

    private static final XC_MethodHook ON_RESUME = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                prepare(param.thisObject);
            } catch (Throwable t) {
                Log.e("蓝牙扫描页 onResume 注入失败", t);
            }
        }
    };

    /** 页面 25 秒超时会收起「发现设备」区块，这里补回来。*/
    private static final XC_MethodHook ON_TIMEOUT = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (!isBlePage(param.thisObject)) return;
                if (!ScanPageHook.injectionEnabled()) {
                    retract(param.thisObject);
                    return;
                }
                Object fake = sFake;
                if (fake == null) return;
                inject(param.thisObject, fake);
            } catch (Throwable t) {
                Log.e("蓝牙扫描页超时后补条目失败", t);
            }
        }
    };

    /** 回调挂在继承链共享的 onCreate/onResume 上，按实例类名认准「是不是这个蓝牙页」。*/
    private static boolean isBlePage(Object page) {
        return page != null && CLS_PAGE.equals(page.getClass().getName());
    }

    private static void prepare(Object page) {
        // 共享挂点：Wi-Fi 扫描页触发时绝不能往它的网格里塞蓝牙卡（现场那两张同型号卡之一）。
        if (!isBlePage(page)) return;
        if (!ScanPageHook.injectionEnabled()) {
            retract(page);
            return;
        }
        String model = readModel(page);
        if (model.isEmpty()) return;
        Object fake = fakeFor(model);
        if (fake == null) return;
        final Object host = page;
        final Object item = fake;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                inject(host, item);
            } catch (Throwable t) {
                Log.e("蓝牙扫描页卡片注入失败", t);
            }
        });
    }

    /** 型号取 intent 的 "model"（米家所有扫描页都按这个键传），顺手登记成「当前请求」。*/
    private static String readModel(Object page) {
        String model = "";
        try {
            if (page instanceof Activity) {
                String m = DeviceAcceptor.modelInIntent(((Activity) page).getIntent());
                if (m != null) model = m;
            }
        } catch (Throwable ignored) {
        }
        model = model == null ? "" : model.trim();
        if (model.isEmpty()) {
            // 和 Wi-Fi 扫描页一致：intent 没带型号就沿用向导上一页登记的那台
            RequestedModel.Record rec = RequestedModel.get();
            if (rec != null) model = rec.model;
        }
        if (!model.isEmpty()) {
            RequestedModel.capture(model, page);
        }
        return model;
    }

    /** 同一型号复用同一个设备对象；换型号才重建，避免网格里的卡闪烁。*/
    private static Object fakeFor(String model) {
        Object cached = sFake;
        if (cached != null && model.equals(sFakeModel)) return cached;
        String mac = macFor(model);
        Object group;
        try {
            group = sGroupClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            Log.e("无法创建 BleDeviceGroup", t);
            return null;
        }
        // 分组本身继承 BleDevice：页面点击时就是把它当 BleDevice 送出去的。
        // 再往里放一台同名同 mac 的子设备，兼容「按分组第一个子设备走」的那些分支。
        Object child = newBleDevice(model, mac);
        if (child != null) {
            HookUtil.invokeQuiet(group, sGroupClass, "addBleDevice", new Class<?>[]{sBleClass}, child);
        }
        // 卡片上给米家官方中文名（names.bin），并统一带「#」前缀：和首页清单、落库后的
        // 名字同一套命名，一眼分得出这是模拟的那台。拿不到中文名才退回型号。
        String shown = ScanPageHook.baseNameOf(DeviceListHook.context(), model);
        if (shown.isEmpty()) shown = model;
        shown = SimDevice.taggedName(shown, model);
        HookUtil.setFieldQuiet(group, "model", model);
        HookUtil.setFieldQuiet(group, "mac", mac);
        HookUtil.setFieldQuiet(group, "name", shown);
        if (child != null) HookUtil.setFieldQuiet(child, "name", shown);
        sFake = group;
        sFakeModel = model;
        sFakeMac = mac;
        Log.d("合成蓝牙条目 mac=" + mac + " model=" + model + " name=" + shown);
        return group;
    }

    private static Object newBleDevice(String model, String mac) {
        if (sBleClass == null) return null;
        try {
            Object dev = sBleClass.getDeclaredConstructor().newInstance();
            HookUtil.setFieldQuiet(dev, "model", model);
            HookUtil.setFieldQuiet(dev, "mac", mac);
            RequestedModel.Record rec = RequestedModel.get();
            String did = (rec != null && model.equals(rec.model)) ? rec.device.did : SimDevice.newDid(null);
            HookUtil.invokeQuiet(dev, sBleClass, "setDid", new Class<?>[]{String.class}, did);
            HookUtil.setFieldQuiet(dev, "did", did);
            return dev;
        } catch (Throwable t) {
            Log.v("造蓝牙子设备失败（只用分组兜底）: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /** 身份优先取向导里已经生成的那台，保证扫描卡和落库的 mac 是同一个。*/
    private static String macFor(String model) {
        RequestedModel.Record rec = RequestedModel.get();
        if (rec != null && model.equals(rec.model) && rec.device != null
                && rec.device.mac != null && !rec.device.mac.isEmpty()) {
            return rec.device.mac;
        }
        return SimDevice.newMac();
    }

    /* ---------- 网格注入 ---------- */

    /** 开关关掉时把已经摆进网格的合成条目撤回来。*/
    private static void retract(Object page) {
        Object fake = sFake;
        if (page == null || fake == null) return;
        int seen = 0;
        int left = 0;
        for (Object adapter : ScanPageHook.adaptersIn(page)) {
            seen++;
            left += remove(adapter, fake);
        }
        if (seen > 0 && left == 0) {
            ScanPageHook.setVisibleField(page, F_FOUND_BLOCK, View.GONE);
            ScanPageHook.setVisibleField(page, F_TIMEOUT_LAYOUT, View.VISIBLE);
        }
        Log.d("蓝牙扫描注入已关闭，撤下合成条目：适配器 " + seen + " 个，剩余 " + left + " 条");
    }

    private static void inject(Object page, Object fake) {
        if (page == null || fake == null) return;
        List<Object> adapters = ScanPageHook.adaptersIn(page);
        if (adapters.isEmpty()) {
            Log.v("蓝牙扫描页还没建好适配器，等下一次刷新");
            return;
        }
        int total = 0;
        for (Object adapter : adapters) {
            total += append(adapter, fake);
        }
        showFound(page, adapters);
        Log.d("蓝牙网格已更新：适配器 " + adapters.size() + " 个，共 " + total + " 条");
    }

    /**
     * 往适配器里塞一条（同一个实例已在就不重复），返回最终条数。
     *
     * <p>塞之前先剔掉米家自己那条同型号「同类型其他设备」影子卡（{@code _m_j.d08}）：
     * 它名字是中文名、点下去走 OtherRegionDevice 流程像没反应，和我们这张并排就是
     * 两张同型号卡。只剔影子卡，真扫到的蓝牙设备一律不动 ——
     * 用户要在同一页既能连真机也能加模拟设备。
     */
    private static int append(Object adapter, Object fake) {
        List list = ScanPageHook.dataListOf(adapter);
        if (list == null) return 0;
        int stripped = ScanPageHook.stripShadowCards(adapter, fake, sFakeModel);
        if (stripped > 0) {
            Log.d("蓝牙网格去掉同型号影子卡 " + stripped + " 处: " + sFakeModel);
        }
        // v154：账号里那台模拟设备会被米家再画一张「#中文名」的卡，按型号认不出来，
        // 这里按模拟身份（# 前缀 / did / mac）再剔一遍，一格里只留我们这一张。
        int echoes = ScanPageHook.pruneSimEchoes(adapter, fake);
        if (echoes > 0) {
            Log.d("蓝牙网格去掉模拟设备投影 " + echoes + " 条");
        }
        for (Object o : list) if (o == fake) return list.size();
        list.add(0, fake);
        ScanPageHook.notifyChanged(adapter);
        return list.size();
    }

    private static int remove(Object adapter, Object fake) {
        List list = ScanPageHook.dataListOf(adapter);
        if (list == null) return 0;
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == fake) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed) ScanPageHook.notifyChanged(adapter);
        return list.size();
    }

    /** 区块默认 GONE，这里连网格一起显出来，并把底部超时栏收起。*/
    private static void showFound(Object page, List<Object> adapters) {
        ScanPageHook.setVisibleField(page, F_FOUND_BLOCK, View.VISIBLE);
        ScanPageHook.setVisibleField(page, F_TIMEOUT_LAYOUT, View.GONE);
        ScanPageHook.revealGrids(page, adapters);
    }

    /** 米家刷新网格（clear + addAll）时把条目补回同一张列表。由 {@link ScanPageHook} 的适配器钩子转过来。*/
    static void onAdapterRefreshed(Object adapter, Object page) {
        if (!isBlePage(page)) return;
        if (!ScanPageHook.injectionEnabled()) {
            retract(page);
            return;
        }
        String model = readModel(page);
        if (model.isEmpty()) return;
        Object fake = fakeFor(model);
        if (fake == null) return;
        append(adapter, fake);
        List<Object> one = new ArrayList<>(1);
        one.add(adapter);
        showFound(page, one);
    }

    /* ---------- 点击：本地落库，不走真实蓝牙配对 ---------- */

    private static final XC_MethodHook ON_PICK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object arg = param.args[0];
                if (arg == null) return; // null = 用户返回，必须放行
                Object mac = HookUtil.readFieldQuiet(arg, "mac");
                if (!(mac instanceof String) || !((String) mac).equalsIgnoreCase(sFakeMac)) return;
                String model = sFakeModel;
                param.setResult(null);
                commit(param.thisObject, model, displayName(arg));
            } catch (Throwable t) {
                Log.e("蓝牙点击短路失败（已放行给米家）", t);
            }
        }
    };

    /** 卡片上显示的名字；取不到就用型号本身。*/
    private static String displayName(Object bleDevice) {
        Object name = HookUtil.readFieldQuiet(bleDevice, "name");
        if (name instanceof String && !((String) name).trim().isEmpty()) return ((String) name).trim();
        if (sBleClass != null) {
            Object n = HookUtil.invokeQuiet(bleDevice, sBleClass, "getName", new Class<?>[0]);
            if (n instanceof String && !((String) n).trim().isEmpty()) return ((String) n).trim();
        }
        return sFakeModel;
    }

    /** 落库 + 提示 + 按成功结果结束，和 Wi-Fi 那条链路保持一致的返回值约定。*/
    private static void commit(Object page, String model, String name) {
        Context ctx = DeviceListHook.context();
        if (ctx == null && page instanceof Context) ctx = (Context) page;
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
            d.mac = sFakeMac;
        }
        if (d.mac == null || d.mac.isEmpty()) d.mac = sFakeMac;
        // 和 Wi-Fi 那条链路一致：落库的名字统一带「#」前缀。
        // 页面给的名字、已有名字都只当「用户可读名」用；一旦它其实就是型号（或空），
        // 换成米家官方中文名，别再让「#型号」当设备名。
        String shown = name == null ? "" : name.trim();
        if (shown.isEmpty() || shown.equals(model)) {
            shown = d.name == null ? "" : d.name.trim();
        }
        if (shown.isEmpty() || shown.equals(model) || shown.equals(SimDevice.NAME_PREFIX + model)) {
            shown = ScanPageHook.baseNameOf(ctx, model);
        }
        d.name = SimDevice.taggedName(shown, model);
        d.model = model;
        d.type = Consts.TYPE_BLE;
        d.enabled = true;
        d.online = true;
        if (cfg.findByDid(d.did) == null) {
            d.sort = cfg.size() + 1;
            cfg.devices.add(d);
        }
        boolean saved = ConfigRepository.save(ctx, cfg);
        Log.d("蓝牙添加完成 did=" + d.did + " model=" + d.model + " name=" + d.name + " 落库=" + saved);
        HookReport.ok(GROUP, "accept", "已添加 " + d.model + " did=" + d.did + " 落库=" + saved);

        try {
            Toast.makeText(ctx, "已添加模拟设备：" + (d.name == null || d.name.isEmpty() ? d.model : d.name),
                    Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }

        if (page instanceof Activity) {
            Activity act = (Activity) page;
            Intent result = new Intent();
            result.putExtra("finish", true);
            act.setResult(Activity.RESULT_OK, result);
            act.finish();
        }
    }

    private static java.util.List<String> didsOf(SimConfig cfg) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SimDevice d : cfg.devices) out.add(d.did);
        return out;
    }
}