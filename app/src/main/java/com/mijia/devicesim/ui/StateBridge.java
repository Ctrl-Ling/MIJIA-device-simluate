/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.ui;

import android.content.Context;

import com.mijia.devicesim.core.ConfigChannel;
import com.mijia.devicesim.core.Consts;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 界面侧的属性状态读写。
 *
 * <p>为什么不走 {@code DeviceStateStore}：那份内存缓存属于米家进程，界面进程再持一份
 * 就会出现「两个进程各自改各自的，谁也不知道对方写了什么」。所以界面只做一件事 ——
 * 把 Provider 里的 state.json 读出来、改一格、整个写回去；Provider 写完 notifyChange，
 * 米家进程自己重载，联动就是这么来的。
 *
 * <p>这里每次读写都重新拉一遍文件、不做本地缓存：状态文件很小，
 * 而「读到的必须是最新的」在这个界面上是功能正确性的前提（改完值要看得到变化）。
 */
final class StateBridge {

    private StateBridge() {
    }

    /** 全量状态根对象；读不到就当作空的。*/
    private static JSONObject root(Context ctx) {
        String json = ConfigChannel.readState(ctx);
        if (json == null) return new JSONObject();
        try {
            return new JSONObject(json);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 一台设备被面板改过的实时值（没被碰过的属性不在这里，读值时要回落到初值/默认值）。*/
    static Map<String, Object> readDevice(Context ctx, String did) {
        Map<String, Object> out = new LinkedHashMap<>();
        JSONObject per = root(ctx).optJSONObject(did);
        if (per == null) return out;
        java.util.Iterator<String> keys = per.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = per.opt(k);
            if (v != null && v != JSONObject.NULL) out.put(k, v);
        }
        return out;
    }

    /** 写一个属性；value 传 null 表示删掉这一格（回落到配置初值）。返回是否落进 Provider。*/
    static boolean writeProp(Context ctx, String did, String key, Object value) {
        JSONObject root = root(ctx);
        try {
            JSONObject per = root.optJSONObject(did);
            if (per == null) {
                if (value == null) return true;   // 本来就没有这格，删个寂寞
                per = new JSONObject();
                root.put(did, per);
            }
            if (value == null) {
                per.remove(key);
                if (per.length() == 0) root.remove(did);
            } else {
                per.put(key, value);
            }
            return ConfigChannel.writeState(ctx, root.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 清掉一台设备的全部覆盖值，回到配置里的属性初值。*/
    static boolean resetDevice(Context ctx, String did) {
        JSONObject root = root(ctx);
        if (!root.has(did)) return true;
        root.remove(did);
        try {
            return ConfigChannel.writeState(ctx, root.toString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 兜底目录里有没有同名文件：有的话 Hook 侧优先读它，界面写的东西会被盖住。*/
    static boolean rootStateShadowed() {
        return ConfigChannel.rootFile("state.json") != null;
    }

    static boolean rootConfigShadowed() {
        return ConfigChannel.rootFile("config.json") != null;
    }

    /** 键 "siid:piid" 解析成 {siid, piid}；非法返回 null。*/
    static int[] parseKey(String key) {
        if (key == null) return null;
        String[] parts = key.split(Consts.KEY_SEPARATOR);
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
