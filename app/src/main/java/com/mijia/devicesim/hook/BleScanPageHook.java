/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 20:27:51 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Toast;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.RequestedModel;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 蓝牙型号的「设备已重置 → 扫描设备」页（{@code ScanChooseBluetoothDeviceV2}）注入。
 *
 * <p><b>为什么单独一个类</b>：Wi-Fi 型号走 {@code RescanWifiV2Activity}，蓝牙型号走这个页面，
 * 两者是两个 Activity、两套字段，但布局里的「发现设备」区块用的是同一批资源 id
 * （区块 {@code cw4} / 网格 {@code cw2}）。页面自己的逻辑是：
 * <ul>
 *   <li>网格适配器 {@code Oooo00o}（{@code _m_j.iza}，最多 3 格 + 更多）；</li>
 *   <li>扫到完全同型号的蓝牙设备时，它<b>不显示卡片</b>，直接跳 {@code OooOO0o(BleDevice)} 进配网；</li>
 *   <li>只有扫到「同系列但不同型号」才把卡片摆出来；</li>
 *   <li>25 秒超时后收起区块、摆出底部「重新搜索」栏。</li>
 * </ul>
 *
 * <p>所以这里做两件事：
 * <ol>
 *   <li>造一个 {@code BleDeviceGroup}（{@code iza} 认这个类型才会画蓝牙卡），塞进网格第一格，
 *       并把「发现设备」区块显出来、收起底部超时栏；</li>
 *   <li>拦 {@code OooOO0o(BleDevice)}：点到我们这条就就地落库、结束向导，不碰真实蓝牙配对。</li>
 * </ol>
 *
 * <p>米家这个页面用 {@code OooOO0o(null)} 表示「用户返回」，所以拦截前必须先比对 mac，
 * 不能一把全拦，否则系统返回键会失效。
 */
public final class BleScanPageHook {

    private BleScanPageHook() {
    }

    private static final String GROUP = "ble-scan-page";

    /** 蓝牙扫描页（类名未混淆）；包内可见，{@link ScanPageHook} 的适配器钩子要按它分流。*/
    static final String CLS_PAGE = "com.xiaomi.smarthome.wificonfig.ScanChooseBluetoothDeviceV2";
    /** 蓝牙设备基类，用来精确匹配 {@code OooOO0o(BleDevice)} 的签名。*/
    private static final String CLS_BLE_DEVICE = "com.xiaomi.smarthome.device.BleDevice";
    /** 蓝牙设备分组，网格只认这个类型。*/
    private static final String CLS_BLE_GROUP = "com.xiaomi.smarthome.device.bluetooth.BleDeviceGroup";

    /* ---------- 11.9.705 字段名（jadx 实测，换版本读不到就降级，不崩） ---------- */

    /** String：intent 里的目标型号（页面字段名和 Wi-Fi 页不同）。*/
    private static final String F_MODEL = "OooOoO0";
    /** String：可选的指定 mac（从上一页带过来的）。*/
    private static final String F_MAC = "OooOoo";
    /** iza：「发现设备」网格适配器。*/
    private static final String F_ADAPTER_FOUND = "Oooo00o";
    /** iza：超时兜底布局里的那张网格适配器。*/
    private static final String F_ADAPTER_ALT = "Oooo0OO";
    /** ArrayList：iza 的数据列表（两个适配器同类，字段同名）。*/
    private static final String F_ADAPTER_LIST = "OooOoO0";
    /** iza 里指向宿主 Activity 的字段。*/
    private static final String F_ADAPTER_HOST = "OooOo";
    /** View：「发现设备」区块（资源 id cw4），默认 GONE。*/
    private static final String F_FOUND_BLOCK = "Oooo000";
    /** FixHeightGridView：「发现设备」区块里的网格（资源 id cw2）。*/
    private static final String F_FOUND_GRID = "Oooo00O";
    /** RelativeLayout：底部「找不到设备 / 重新搜索」栏，默认 GONE。*/
    private static final String F_TIMEOUT_LAYOUT = "mBottomTimeoutLayout";

    /** 反射拿到的 BleDeviceGroup 类；为空 = 这个版本没有，整条链降级。*/
    private static Class<?> sGroupClass;
    /** 反射拿到的 BleDevice 类，用来调用 getName 兜底显示名。*/
    private static Class<?> sBleDeviceClass;

    /** 当前合成的那条蓝牙设备（适配器里必须是同一个实例，点击时才好认）。*/
    private static volatile Object sFake;
    private static volatile String sFakeModel = "";
    private static volatile String sFakeMac = "";

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        Class<?> page = HookUtil.findClassQuiet(CLS_PAGE, loader);
        if (page == null) {
            HookReport.skip(GROUP, CLS_PAGE, "类不存在（本版本没有蓝牙扫描页）");
            return;
        }
        sGroupClass = HookUtil.findClassQuiet(CLS_BLE_GROUP, loader);
        sBleDeviceClass = HookUtil.findClassQuiet(CLS_BLE_DEVICE, loader);
        if (sGroupClass == null) {
            HookReport.skip(GROUP, CLS_BLE_GROUP, "蓝牙设备分组类不存在");
            return;
        }

        boolean created = HookUtil.hookMethod(GROUP, page, "onCreate", ON_CREATE, Bundle.class);
        HookUtil.hookMethod(GROUP, page, "onResume", ON_RESUME);
        boolean timeout = HookUtil.hookMethod(GROUP, page, "onTimeOut", ON_TIMEOUT);
        boolean bind = sBleDeviceClass != null
                && HookUtil.hookMethod(GROUP, page, "OooOO0o", ON_BIND, sBleDeviceClass);
        HookReport.ok(GROUP, "ScanChooseBluetoothDeviceV2",
                "onCreate=" + (created ? "已挂" : "未挂") + "，点击短路=" + (bind ? "已挂" : "未挂")
                        + "，超时补偿=" + (timeout ? "已挂" : "未挂"));
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
                if (!injectionEnabled()) {
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

    private static void prepare(Object page) {
        if (!injectionEnabled()) {
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

    /** 型号优先读页面字段，读不到退回 intent 的 "model"，顺手登记成「当前请求」。*/
    private static String readModel(Object page) {
        Object v = HookUtil.readFieldQuiet(page, F_MODEL);
        String model = v instanceof String ? ((String) v).trim() : "";
        if (model.isEmpty()) {
            try {
                Intent intent = ((Activity) page).getIntent();
                if (intent != null) model = intent.getStringExtra("model");
            } catch (Throwable ignored) {
            }
        }
        if (model == null) model = "";
        model = model.trim();
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
        HookUtil.setFieldQuiet(group, "model", model);
        HookUtil.setFieldQuiet(group, "mac", mac);
        sFake = group;
        sFakeModel = model;
        sFakeMac = mac;
        Log.d("合成蓝牙条目 mac=" + mac + " model=" + model);
        return group;
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

    /**
     * 扫描结果注入的总闸：和首页「⋮ → 更多开关 → 扫描结果注入」同一个
     * {@link SimConfig#scanInjection}（外加总注入开关）。
     *
     * <p>蓝牙这一页和 Wi-Fi 页一样不走 {@code DeviceScanManager.dispatchOnScan}，
     * 以前只在分发器那条路上判开关，所以开关关了这里照样冒卡（11-11 报的 bug）。
     * 每次直接读配置缓存，靠开关值判断，不加状态位也不加计时器。
     */
    private static boolean injectionEnabled() {
        SimConfig cfg = ConfigRepository.current();
        return cfg.scanInjection && cfg.injectEnabled;
    }

    /** 开关关掉时把已经摆进网格的合成条目撤回来。*/
    private static void retract(Object page) {
        Object fake = sFake;
        if (page == null || fake == null) return;
        int found = remove(page, F_ADAPTER_FOUND, fake);
        int alt = remove(page, F_ADAPTER_ALT, fake);
        if (found == 0 && alt == 0) setVisible(page, F_FOUND_BLOCK, View.GONE);
        Log.d("蓝牙扫描注入已关闭，撤下合成条目：发现区块=" + found + " 条，兜底区块=" + alt + " 条");
    }

    /** 从指定适配器的数据列表里去掉我们那条；返回剩余条数，适配器读不到返回 -1。*/
    private static int remove(Object page, String adapterField, Object fake) {
        Object adapter = HookUtil.readFieldQuiet(page, adapterField);
        if (adapter == null) return -1;
        Object listObj = HookUtil.readFieldQuiet(adapter, F_ADAPTER_LIST);
        if (!(listObj instanceof List)) return -1;
        List list = (List) listObj;
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == fake) {
                list.remove(i);
                changed = true;
            }
        }
        if (changed && adapter instanceof BaseAdapter) ((BaseAdapter) adapter).notifyDataSetChanged();
        return list.size();
    }

    private static void inject(Object page, Object fake) {
        if (page == null || fake == null) return;
        int found = append(page, F_ADAPTER_FOUND, fake);
        int alt = append(page, F_ADAPTER_ALT, fake);
        showFound(page);
        Log.d("蓝牙网格已更新：发现区块=" + found + " 条，兜底区块=" + alt + " 条");
    }

    private static int append(Object page, String adapterField, Object fake) {
        Object adapter = HookUtil.readFieldQuiet(page, adapterField);
        if (adapter == null) return -1;
        Object listObj = HookUtil.readFieldQuiet(adapter, F_ADAPTER_LIST);
        if (!(listObj instanceof List)) return -1;
        return appendToList((List) listObj, adapter, fake);
    }

    @SuppressWarnings("unchecked")
    private static int appendToList(List list, Object adapter, Object fake) {
        if (list.contains(fake)) return list.size();
        list.add(0, fake); // 放第一格，用户一眼就能看见
        if (adapter instanceof BaseAdapter) ((BaseAdapter) adapter).notifyDataSetChanged();
        return list.size();
    }

    /** 区块默认 GONE，这里连网格一起显出来，并把底部超时栏收起。*/
    private static void showFound(Object page) {
        setVisible(page, F_FOUND_BLOCK, View.VISIBLE);
        setVisible(page, F_FOUND_GRID, View.VISIBLE);
        setVisible(page, F_TIMEOUT_LAYOUT, View.GONE);
    }

    private static void setVisible(Object page, String field, int visibility) {
        Object v = HookUtil.readFieldQuiet(page, field);
        if (v instanceof View && ((View) v).getVisibility() != visibility) {
            ((View) v).setVisibility(visibility);
        }
    }

    /** 米家刷新网格（clear + addAll）时把条目补回同一张列表。由 {@link ScanPageHook} 的适配器钩子转过来。*/
    static void onAdapterRefreshed(Object adapter, Object page) {
        if (!injectionEnabled()) {
            retract(page);
            return;
        }
        String model = readModel(page);
        if (model.isEmpty()) return;
        Object fake = fakeFor(model);
        if (fake == null) return;
        Object listObj = HookUtil.readFieldQuiet(adapter, F_ADAPTER_LIST);
        if (listObj instanceof List) appendToList((List) listObj, adapter, fake);
        showFound(page);
    }

    /* ---------- 点击：本地落库，不走真实蓝牙配对 ---------- */

    private static final XC_MethodHook ON_BIND = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object arg = param.args[0];
                if (arg == null) return; // OooOO0o(null) = 用户返回，必须放行
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

    /** 卡片上显示的名字（插件记录里的中文名）；取不到就用型号本身。*/
    private static String displayName(Object bleDevice) {
        Object name = HookUtil.readFieldQuiet(bleDevice, "name");
        if (name instanceof String && !((String) name).trim().isEmpty()) return ((String) name).trim();
        if (sBleDeviceClass != null) {
            Object n = HookUtil.invokeQuiet(bleDevice, sBleDeviceClass, "getName", new Class<?>[0]);
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
        if (name != null && !name.trim().isEmpty()) d.name = name.trim();
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
