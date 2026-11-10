/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 整份配置 = 设备清单 + 全局开关。界面和 Hook 层读写的是同一份 JSON，
 * 这是 plan 里「一份 JSON 作为唯一配置源」的落地。
 */
public class SimConfig {

    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public List<SimDevice> devices = new ArrayList<>();

    /** 总开关：关掉以后所有钩子直接放行真实逻辑，用来做 A/B 回归。*/
    public boolean injectEnabled = true;
    /** Phase 6：云端设备列表下发时不覆盖/清掉保留段 did。*/
    public boolean cloudGuard = true;
    /**
     * 实验项：在米家「添加设备 → 扫描」结果里也塞一条假记录。
     * 扫描结果 bean 的结构只能真机抓（见 docs/HOOKS.md 待实测清单），所以默认关；
     * 关掉时添加走模块界面写配置这条路，一样能出现在首页列表。
     */
    public boolean scanInjection = false;
    /**
     * 「完成添加」短路：点开模拟条目时不进入米家的真实配网（热点检查 + smartconfig + 云端绑定），
     * 而是就地把它落进本模块清单，由首页列表注入显示。
     * 假设备永远配不完网，所以这个默认开 —— 关掉只是用来复现米家原本的报错行为做对比。
     */
    public boolean acceptShortCircuit = true;
    /** 面板控制调用是否回写状态并持久化。*/
    public boolean persistState = true;
    /** 详细日志（定位新版米家钩子失效时打开）。*/
    public boolean verboseLog = false;
    /** 保存时间戳，界面上显示「上次生效」。*/
    public long updatedAt = 0L;

    public static SimConfig empty() {
        return new SimConfig();
    }

    public List<SimDevice> enabledDevices() {
        List<SimDevice> out = new ArrayList<>();
        if (!injectEnabled) return out;
        for (SimDevice d : devices) if (d.enabled && d.isValid()) out.add(d);
        out.sort((a, b) -> a.sort != b.sort ? Integer.compare(a.sort, b.sort) : a.did.compareTo(b.did));
        return out;
    }

    public SimDevice findByDid(String did) {
        if (did == null) return null;
        for (SimDevice d : devices) if (did.equals(d.did)) return d;
        return null;
    }

    public SimDevice findEnabledByDid(String did) {
        SimDevice d = findByDid(did);
        return (d != null && d.enabled && injectEnabled) ? d : null;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", version);
        o.put("devices", SimDevice.listToJson(devices));
        o.put("injectEnabled", injectEnabled);
        o.put("cloudGuard", cloudGuard);
        o.put("scanInjection", scanInjection);
        o.put("acceptShortCircuit", acceptShortCircuit);
        o.put("persistState", persistState);
        o.put("verboseLog", verboseLog);
        o.put("updatedAt", updatedAt);
        return o;
    }

    public static SimConfig fromJson(JSONObject o) {
        SimConfig c = new SimConfig();
        if (o == null) return c;
        c.version = o.optInt("version", CURRENT_VERSION);
        c.devices = SimDevice.listFromJson(o.optJSONArray("devices"));
        c.injectEnabled = o.optBoolean("injectEnabled", true);
        c.cloudGuard = o.optBoolean("cloudGuard", true);
        c.scanInjection = o.optBoolean("scanInjection", false);
        c.acceptShortCircuit = o.optBoolean("acceptShortCircuit", true);
        c.persistState = o.optBoolean("persistState", true);
        c.verboseLog = o.optBoolean("verboseLog", false);
        c.updatedAt = o.optLong("updatedAt");
        for (int i = 0; i < c.devices.size(); i++) {
            SimDevice d = c.devices.get(i);
            if (d.sort == 0) d.sort = i + 1;
        }
        return c;
    }

    public String toJsonString() {
        try {
            return toJson().toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** 解析失败必须返回空配置而不是抛异常：Hook 层拿到异常会让米家直接崩。*/
    public static SimConfig parse(String json) {
        if (json == null || json.trim().isEmpty()) return empty();
        try {
            return fromJson(new JSONObject(json));
        } catch (Throwable t) {
            Log.e("配置解析失败，按空配置继续: " + t);
            return empty();
        }
    }

    public int size() {
        return devices.size();
    }
}
