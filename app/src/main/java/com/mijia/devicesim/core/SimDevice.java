/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 10:44:37 +08:00
 */
package com.mijia.devicesim.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一台模拟设备。配置里的这一条 + MIoT spec + 实时状态 = 米家看到的那个 Device。
 * 只依赖 org.json，米家进程和模块界面进程都能直接用。
 */
public class SimDevice {

    public static final int CURRENT_VERSION = 1;

    /** 保留段 did：9 开头的 10 位数字，见 {@link Consts#SIM_DID_PREFIX}。*/
    private static final AtomicInteger sDidSeq = new AtomicInteger(0);
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    public String did = "";
    public String model = "";
    public String name = "";
    public String roomName = "";
    /** 多 households 时指定归属；留空 = 当前默认家庭。*/
    public String homeId = "";
    /** {@link Consts} 里的 TYPE_* 之一。*/
    public String type = Consts.TYPE_WIFI;
    /** Zigbee 子设备 / BLE Mesh 子设备挂载的父网关 did；空 = 不挂网关。*/
    public String parentDid = "";
    public String token = "";
    public String mac = "";
    public boolean online = true;
    public boolean enabled = true;
    /** 列表内排序，米家首页按 sort 排。*/
    public int sort = 0;
    /** 加进模块的时间（毫秒）。老配置没有这个字段，读出来是 0，按 sort 兜底排序。*/
    public long addedAt = 0;
    /** 面板初始属性：键 "siid:piid"，值按 spec 的 format 给。*/
    public Map<String, Object> initialProps = new LinkedHashMap<>();
    /** 原样透传给 Device.extra 的字段（icon、partnerId 等）。*/
    public Map<String, String> extra = new LinkedHashMap<>();

    public boolean isValid() {
        return did != null && did.length() > 0 && model != null && model.length() > 0;
    }

    /* ---------- 工厂 ---------- */

    /** 生成一个没被占用的保留段 did。*/
    public static String newDid(java.util.Collection<String> taken) {
        for (int attempt = 0; attempt < 4096; attempt++) {
            String candidate = randomDid();
            if (taken == null || !taken.contains(candidate)) return candidate;
        }
        // 4096 次都撞上（几乎不可能），退回顺序段
        int n = sDidSeq.incrementAndGet();
        return Consts.SIM_DID_PREFIX + String.format(Locale.ROOT, "%09d", n % 1_000_000_000);
    }

    private static String randomDid() {
        StringBuilder sb = new StringBuilder(Consts.SIM_DID_LENGTH);
        sb.append(Consts.SIM_DID_PREFIX);
        for (int i = 1; i < Consts.SIM_DID_LENGTH; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    /** miio 设备要求的 32 位大写 hex key。*/
    public static String newToken() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte value : b) sb.append(String.format(Locale.ROOT, "%02X", value));
        return sb.toString();
    }

    /** 本地生成的 MAC：前 3 字节用小米 OUI，后 3 字节随机，冒号分隔。*/
    public static String newMac() {
        byte[] b = new byte[3];
        RANDOM.nextBytes(b);
        return String.format(Locale.ROOT, "28:6C:07:%02X:%02X:%02X", b[0], b[1], b[2]).toUpperCase(Locale.ROOT);
    }

    /* ---------- JSON ---------- */

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("did", did);
        o.put("model", model);
        o.put("name", name);
        o.put("roomName", roomName);
        o.put("homeId", homeId);
        o.put("type", type);
        o.put("parentDid", parentDid);
        o.put("token", token);
        o.put("mac", mac);
        o.put("online", online);
        o.put("enabled", enabled);
        o.put("sort", sort);
        o.put("addedAt", addedAt);
        o.put("props", new JSONObject(initialProps));
        o.put("extra", new JSONObject(extra));
        return o;
    }

    public static SimDevice fromJson(JSONObject o) {
        SimDevice d = new SimDevice();
        d.did = o.optString("did");
        d.model = o.optString("model");
        d.name = o.optString("name");
        d.roomName = o.optString("roomName");
        d.homeId = o.optString("homeId");
        d.type = o.optString("type", Consts.TYPE_WIFI);
        d.parentDid = o.optString("parentDid");
        d.token = o.optString("token");
        d.mac = o.optString("mac");
        d.online = o.optBoolean("online", true);
        d.enabled = o.optBoolean("enabled", true);
        d.sort = o.optInt("sort");
        d.addedAt = o.optLong("addedAt", 0);
        JSONObject props = o.optJSONObject("props");
        if (props != null) {
            JSONArray keys = props.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.optString(i);
                    Object v = props.opt(k);
                    if (v != null && v != JSONObject.NULL) d.initialProps.put(k, v);
                }
            }
        }
        JSONObject ex = o.optJSONObject("extra");
        if (ex != null) {
            JSONArray keys = ex.names();
            if (keys != null) {
                for (int i = 0; i < keys.length(); i++) {
                    String k = keys.optString(i);
                    d.extra.put(k, ex.optString(k));
                }
            }
        }
        return d;
    }

    /** 复制一份并换 did，用于界面「复制设备」。*/
    public SimDevice copyAsNew(List<SimDevice> existing) {
        SimDevice c = fromJson(toJsonSafe());
        java.util.Set<String> taken = new java.util.HashSet<>();
        if (existing != null) for (SimDevice e : existing) taken.add(e.did);
        c.did = newDid(taken);
        c.token = newToken();
        c.name = name + " 副本";
        c.sort = sort + 1;
        c.addedAt = System.currentTimeMillis();
        return c;
    }

    public JSONObject toJsonSafe() {
        try {
            return toJson();
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * 反序列化清单。
     *
     * <p>这里**不能**按 {@link #isValid()} 过滤：型号是编辑到一半才填的，
     * 「新增设备」先落一条只有 did 的记录再跳编辑器，编辑器回来后要能在清单里找到它。
     * 一旦在这里把空型号的行丢掉，编辑器就查不到这台设备、直接退出，界面表现就是
     * 「点新增没反应，只弹了一句已保存」。
     *
     * <p>所以只丢「连 did 都没有」的行（那种条目谁寻址不到，留着只会让界面出现空白行），
     * 是否参与注入一律由 {@link SimConfig#enabledDevices()} 按 isValid() 决定。
     */
    public static List<SimDevice> listFromJson(JSONArray arr) {
        List<SimDevice> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            SimDevice d = fromJson(o);
            if (d.did == null || d.did.length() == 0) {
                Log.w("清单里第 " + (i + 1) + " 条没有 did，已忽略");
                continue;
            }
            out.add(d);
        }
        return out;
    }

    public static JSONArray listToJson(List<SimDevice> list) throws JSONException {
        JSONArray a = new JSONArray();
        if (list != null) for (SimDevice d : list) a.put(d.toJson());
        return a;
    }

    @Override
    public String toString() {
        return "SimDevice{" + did + " " + model + " \"" + name + "\" " + type + (online ? " online" : " offline") + "}";
    }
}
