/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 09:02:18 +08:00
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
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.RequestedModel;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 「设备已重置 → 扫描中…」这一页（{@code RescanWifiV2Activity}）的注入。
 *
 * <p><b>为什么重写这一层</b>：以前的实现挂在 {@code DeviceScanManager.dispatchOnScan(List)}，
 * 但实测这一页根本不走那个分发器 —— 它直接读 {@code WifiManager.getScanResults()} 和蓝牙广播，
 * 所以旧钩子一次都没响过。11.9.705 的页面结构（jadx 全量反编译 + 真机截图核对）是：
 * <ul>
 *   <li>「发现设备」网格 = 字段 {@code Oooo0oo}，适配器 {@code _m_j.iza}，数据列表字段 {@code OooOoO0}；</li>
 *   <li>网格里每个条目按类型渲染，Wi-Fi 条目就是 {@code android.net.wifi.ScanResult}，
 *       {@code iza.getView} 会从 SSID 里解析型号、查本地插件记录拿图标和名字；</li>
 *   <li>点击条目 → {@code _m_j.eza} → {@code c23(13).OooO0o0(Object)} → 只调一句
 *       {@code RescanWifiV2Activity.gotoWifiSettingPage(ScanResult)}，之后才进热点配网。</li>
 * </ul>
 *
 * <p>所以这里用「原生卡片」而不是自绘浮层：往适配器数据里塞一个合成出来的 {@code ScanResult}，
 * 米家自己就会把这张卡画得跟真扫描到的一样；用户点它时拦住
 * {@code gotoWifiSettingPage}，就地落库、结束向导、回到首页。
 *
 * <p>SSID 形状必须是米家自己的 AP 命名规则：{@code 型号把"."换成"-" + "_miap" + 4位}。
 * 型号里有点号，SSID 里只允许横杠，米家的 {@code gc4.o0ooOoO()} 会把横杠换回点号再比型号。
 * 后 4 位取 MAC 末 4 位，和 {@code gc4.ooOO(BSSID, SSID)} 的取法一致。
 */
public final class ScanPageHook {

    private ScanPageHook() {
    }

    private static final String GROUP = "scan-page";

    /** 扫描页。类名未混淆（R8 只改 _m_j 下的类）。*/
    private static final String CLS_RESCAN = "com.xiaomi.smarthome.wificonfig.RescanWifiV2Activity";
    /** 「发现设备」网格的适配器：{@code final class _m_j.iza extends BaseAdapter}。*/
    private static final String CLS_ADAPTER = "_m_j.iza";

    /* ---------- 11.9.705 字段名（jadx 实测，换版本会变，读不到就降级，不崩） ---------- */

    /** String：intent 里的目标型号。*/
    private static final String F_MODEL = "OooOoO";
    /** iza：扫描页真正的「发现设备」网格适配器（网格 id 2131432321，最多 3 格 + 更多）。*/
    private static final String F_ADAPTER_FOUND = "Oooo0o0";
    /** iza：超时兜底页里那张网格的适配器（网格 id 2131427792）。*/
    private static final String F_ADAPTER_ALT = "Oooo";
    /** ArrayList：iza 的数据列表。*/
    private static final String F_ADAPTER_LIST = "OooOoO0";
    /** iza 里指向宿主 Activity 的字段。*/
    private static final String F_ADAPTER_HOST = "OooOo";
    /** View：「发现设备」区块（标题 + 网格，id 2131432323），扫描中/超时默认 GONE。*/
    private static final String F_FOUND_BLOCK = "Oooo000";
    /** FixHeightGridView：「发现设备」区块里的网格本体（id 2131432321）。*/
    private static final String F_FOUND_GRID = "Oooo0OO";
    /** View：超时后「未扫描到附近设备…」的提示块（id 2131433225）。*/
    private static final String F_TIMEOUT_HINT = "Oooo0O0";

    /** 扫描页顶部那行型号名 TextView（aapt2 资源表里名字被混淆成 077d，只能记数字）。*/
    private static final int ID_MODEL_NAME = 2131429245;

    private static final String AP_SUFFIX = "_miap";

    /** SSID → 型号：点击时反查，避免二次解析出错。*/
    private static final Map<String, String> sSsidToModel = new ConcurrentHashMap<>();
    /** 当前这条合成结果。适配器里必须是同一个实例，点击时才好认。*/
    private static volatile ScanResult sFake;
    private static volatile String sFakeModel = "";

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader loader = lpparam.classLoader;
        Class<?> act = HookUtil.findClassQuiet(CLS_RESCAN, loader);
        if (act == null) {
            HookReport.skip(GROUP, CLS_RESCAN, "类不存在（本版本没有这个扫描页）");
            return;
        }
        boolean created = HookUtil.hookMethod(GROUP, act, "onCreate", ON_CREATE, Bundle.class);
        HookUtil.hookMethod(GROUP, act, "onResume", ON_RESUME);
        boolean click = HookUtil.hookMethod(GROUP, act, "gotoWifiSettingPage", ON_GOTO_SETTING, ScanResult.class);
        boolean timeout = HookUtil.hookMethod(GROUP, act, "onTimeOut", ON_TIMEOUT);
        HookReport.ok(GROUP, "RescanWifiV2Activity",
                "onCreate=" + (created ? "已挂" : "未挂") + "，点击短路=" + (click ? "已挂" : "未挂")
                        + "，超时补偿=" + (timeout ? "已挂" : "未挂"));

        Class<?> adapter = HookUtil.findClassQuiet(CLS_ADAPTER, loader);
        if (adapter != null) {
            HookUtil.hookMethod(GROUP, adapter, "OooO00o", ON_SET_DATA, java.util.ArrayList.class);
        } else {
            HookReport.skip(GROUP, CLS_ADAPTER, "适配器类不存在");
        }
    }

    /* ---------- 页面生命周期：把合成条目塞进网格 ---------- */

    private static final XC_MethodHook ON_CREATE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object activity = param.thisObject;
                prepare(activity);
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

    /** 型号从页面字段读（intent 里就是 "model"），顺手登记成「当前请求」。*/
    private static String readModel(Object activity) {
        Object v = HookUtil.readFieldQuiet(activity, F_MODEL);
        String model = v instanceof String ? ((String) v).trim() : "";
        if (model.isEmpty()) {
            try {
                Intent intent = ((Activity) activity).getIntent();
                if (intent != null) model = intent.getStringExtra("model");
            } catch (Throwable ignored) {
            }
        }
        if (model == null) model = "";
        model = model.trim();
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

    /**
     * 两张网格都补上：{@code Oooo0o0} 是页面自己展示扫描结果的那张，{@code Oooo} 是超时兜底页里的那张。
     * 只补一张就会出现「数据进去了但用户那块区域永远空着」的坑（11-11 实测）。
     */
    private static void inject(Object activity, ScanResult fake) {
        if (activity == null || fake == null) return;
        int found = append(activity, F_ADAPTER_FOUND, fake);
        int alt = append(activity, F_ADAPTER_ALT, fake);
        showFound(activity);
        Log.d("扫描网格已更新：发现区块=" + found + " 条，兜底区块=" + alt + " 条");
    }

    /** 往指定适配器的数据列表塞条目（已在就不重复），返回最终条数；适配器读不到返回 -1。*/
    private static int append(Object activity, String adapterField, ScanResult fake) {
        Object adapter = HookUtil.readFieldQuiet(activity, adapterField);
        if (adapter == null) return -1;
        Object listObj = HookUtil.readFieldQuiet(adapter, F_ADAPTER_LIST);
        if (!(listObj instanceof List)) return -1;
        return appendToList((List) listObj, adapter, fake);
    }

    @SuppressWarnings("unchecked")
    private static int appendToList(List list, Object adapter, ScanResult fake) {
        boolean present = false;
        for (Object o : list) {
            if (o instanceof ScanResult && sameSsid((ScanResult) o, fake)) {
                present = true;
                break;
            }
        }
        if (!present) {
            list.add(0, fake); // 放第一格，用户一眼就能看见
            if (adapter instanceof BaseAdapter) ((BaseAdapter) adapter).notifyDataSetChanged();
        }
        return list.size();
    }

    private static boolean sameSsid(ScanResult a, ScanResult b) {
        return a != null && b != null && a.SSID != null && a.SSID.equals(b.SSID);
    }

    /** 「发现设备」区块默认 GONE，这里连同网格一起显示，并把超时提示收起来。*/
    private static void showFound(Object activity) {
        setVisible(activity, F_FOUND_BLOCK, View.VISIBLE);
        setVisible(activity, F_FOUND_GRID, View.VISIBLE);
        setVisible(activity, F_TIMEOUT_HINT, View.GONE);
    }

    private static void setVisible(Object activity, String field, int visibility) {
        Object v = HookUtil.readFieldQuiet(activity, field);
        if (v instanceof View && ((View) v).getVisibility() != visibility) {
            ((View) v).setVisibility(visibility);
        }
    }

    /* ---------- 米家自己刷新网格时，把条目补回去 ---------- */

    private static final XC_MethodHook ON_SET_DATA = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object adapter = param.thisObject;
                Object host = HookUtil.readFieldQuiet(adapter, F_ADAPTER_HOST);
                if (host == null || !CLS_RESCAN.equals(host.getClass().getName())) return;
                String model = readModel(host);
                if (model.isEmpty()) return;
                ScanResult fake = fakeFor(model, host);
                // 米家每次刷新都会 clear + addAll，把我们的条目一起冲掉；这里在它刷完之后补回同一张列表
                Object listObj = HookUtil.readFieldQuiet(adapter, F_ADAPTER_LIST);
                if (listObj instanceof List) appendToList((List) listObj, adapter, fake);
                showFound(host);
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
                commit(param.thisObject, model, srcName(param.thisObject));
            } catch (Throwable t) {
                Log.e("点击短路失败（已放行给米家）", t);
            }
        }
    };

    /**
     * 把「用户正在添加的这台」落进配置清单，然后按成功返回上一页。
     * 首页列表由 {@link DeviceListHook} 负责显示，所以这里落库成功就等于「添加完成」。
     */
    private static void commit(Object activity, String model, String name) {
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
        if (name != null && !name.trim().isEmpty()) d.name = name.trim();
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

    /** 页面顶部显示的型号名（米家从本地插件记录里取的名字），读不到就用型号本身。*/
    private static String srcName(Object activity) {
        if (activity instanceof Activity) {
            try {
                TextView tv = ((Activity) activity).findViewById(ID_MODEL_NAME);
                if (tv != null && tv.getText() != null) {
                    String s = tv.getText().toString().trim();
                    if (!s.isEmpty()) return s;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static java.util.List<String> didsOf(SimConfig cfg) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (SimDevice d : cfg.devices) out.add(d.did);
        return out;
    }
}
