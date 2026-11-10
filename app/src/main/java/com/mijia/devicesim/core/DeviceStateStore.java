/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 10:35:40 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假设备的实时属性状态机（plan Phase 2 的「内存+持久化属性状态」）。
 *
 * 取值优先级：运行时状态 -> 配置里的属性初值 -> spec 推导的默认值。
 * 写值一律先过 {@link MiotSpec#coerce} ，保证面板再怎么拖都不会出现非法值。
 */
public final class DeviceStateStore {

    private DeviceStateStore() {
    }

    /** did -> ("siid:piid" -> value) */
    private static final Map<String, Map<String, Object>> sState = new ConcurrentHashMap<>();
    private static final Object LOAD_LOCK = new Object();
    private static volatile boolean sLoaded;
    private static volatile Handler sFlushHandler;
    private static volatile boolean sDirty;
    private static volatile boolean sObserving;
    /** 标记「接下来那次 notifyChange 是自己落盘引起的」，观察者据此跳过重载。*/
    private static volatile boolean sSelfWrite;

    public static void init(Context ctx) {
        ensureLoaded(ctx);
        observeStateChanges(ctx);
    }

    /**
     * 界面在「实时调试」页手工改过一个属性值，写的是 Provider 里的 state.json；
     * 米家进程内存里那份如果不再读一次，面板就看不到联动。所以状态也挂观察：
     * 文件一变，内存整体替换。自己刚写的那一次跳过，避免无意义的重载。
     */
    private static void observeStateChanges(Context ctx) {
        if (ctx == null || sObserving) return;
        sObserving = true;
        try {
            Handler handler = Looper.myLooper() != null ? new Handler(Looper.myLooper()) : new Handler(Looper.getMainLooper());
            ctx.getContentResolver().registerContentObserver(
                    ConfigChannel.uri(Consts.PATH_STATE), false,
                    new android.database.ContentObserver(handler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            if (sSelfWrite) {
                                sSelfWrite = false;
                                return;
                            }
                            reloadFromSource(ctx);
                        }
                    });
        } catch (Throwable t) {
            sObserving = false;
            Log.w("注册状态观察失败: " + t);
        }
    }

    /** 丢掉内存里那份，按 Provider/兜底文件重新载入。*/
    public static void reloadFromSource(Context ctx) {
        synchronized (LOAD_LOCK) {
            sState.clear();
            sLoaded = false;
            ensureLoaded(ctx);
        }
    }

    private static void ensureLoaded(Context ctx) {
        if (sLoaded) return;
        synchronized (LOAD_LOCK) {
            if (sLoaded) return;
            sLoaded = true;
            ConfigChannel.Payload p = ConfigChannel.readStateWithSource(ctx);
            String json = p.json;
            if (json == null) {
                Log.d("没有历史状态，全部用配置初值");
                return;
            }
            if (!Consts.SOURCE_MIRROR.equals(p.source)) {
                // 真从 Provider/兜底读到的状态才留一份镜像：MIUI 拦自启动时冷启动也能接上上次的面板值
                ConfigChannel.mirrorWrite(ctx, Consts.MIRROR_STATE, json);
            }
            try {
                JSONObject root = new JSONObject(json);
                java.util.Iterator<String> dids = root.keys();
                while (dids.hasNext()) {
                    String did = dids.next();
                    JSONObject per = root.optJSONObject(did);
                    if (per == null) continue;
                    Map<String, Object> map = new ConcurrentHashMap<>();
                    java.util.Iterator<String> keys = per.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        Object v = per.opt(k);
                        if (v != null && v != JSONObject.NULL) map.put(k, normalize(v));
                    }
                    sState.put(did, map);
                }
                Log.d("状态载入完成，设备数=" + sState.size());
            } catch (Throwable t) {
                Log.e("状态解析失败，忽略历史状态", t);
            }
        }
    }

    /** org.json 在 Android 上会把整数解析成 Integer/Long/Double，统一成 Integer/Double 存。*/
    private static Object normalize(Object v) {
        if (v instanceof java.math.BigInteger) return ((java.math.BigInteger) v).longValue();
        return v;
    }

    public static Object peek(Context ctx, String did, String key) {
        ensureLoaded(ctx);
        Map<String, Object> per = sState.get(did);
        return per == null ? null : per.get(key);
    }

    /**
     * 读一个属性的当前值。
     * @param dev  配置项（提供初值）
     * @param spec 型号 spec（提供默认值和取值范围）
     */
    public static Object read(Context ctx, SimDevice dev, MiotSpec spec, int siid, int piid) {
        if (dev == null) return null;
        ensureLoaded(ctx);
        String key = Consts.propKey(siid, piid);
        Map<String, Object> per = sState.get(dev.did);
        if (per != null) {
            Object live = per.get(key);
            if (live != null) return live;
        }
        Object initial = dev.initialProps.get(key);
        MiotSpec.Prop def = spec == null ? null : spec.findProp(siid, piid);
        if (initial != null) return def == null ? initial : spec.coerce(def, initial);
        return def == null ? null : spec.defaultValue(def);
    }

    /** 写一个属性，返回真正落下的（已按 spec 校正的）值。*/
    public static Object write(Context ctx, SimDevice dev, MiotSpec spec, int siid, int piid, Object value) {
        if (dev == null) return null;
        ensureLoaded(ctx);
        MiotSpec.Prop def = spec == null ? null : spec.findProp(siid, piid);
        Object applied = (spec != null && def != null) ? spec.coerce(def, value) : normalize(value);
        if (applied == null) return null;
        Map<String, Object> per = sState.get(dev.did);
        if (per == null) {
            per = new ConcurrentHashMap<>();
            sState.put(dev.did, per);
        }
        per.put(Consts.propKey(siid, piid), applied);
        if (ConfigRepository.current().persistState) schedulePersist(ctx);
        return applied;
    }

    /** 整台设备的当前状态（spec 里所有属性都补齐，缺的按默认值）。*/
    public static Map<String, Object> snapshot(Context ctx, SimDevice dev, MiotSpec spec) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (dev == null) return out;
        if (spec != null) {
            for (MiotSpec.Prop p : spec.allProps()) {
                out.put(p.key(), read(ctx, dev, spec, p.siid, p.iid));
            }
        }
        ensureLoaded(ctx);
        Map<String, Object> per = sState.get(dev.did);
        if (per != null) out.putAll(per);   // spec 之外手工写进去的值也要能看见
        return out;
    }

    /**
     * 喂给 Device.propInfo / DeviceStat.propInfo 的结构。
     * 米家这里读的是 {"siid:piid":{"value":x}} 形式；真机上如果面板读数不动，
     * 唯一要改的就是这个形状（见 docs/USAGE.md 的「面板读数不动」一节）。
     */
    public static JSONObject propInfo(Context ctx, SimDevice dev, MiotSpec spec) {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Object> e : snapshot(ctx, dev, spec).entrySet()) {
                JSONObject one = new JSONObject();
                one.put("value", e.getValue());
                one.put("type", "prop");
                root.put(e.getKey(), one);
            }
        } catch (Throwable t) {
            Log.e("propInfo 构造失败", t);
        }
        return root;
    }

    public static void resetDevice(Context ctx, String did) {
        ensureLoaded(ctx);
        sState.remove(did);
        schedulePersist(ctx);
    }

    public static void clearAll(Context ctx) {
        sState.clear();
        schedulePersist(ctx);
    }

    /* ---------- 持久化：合并连续滑动，400ms 落一次盘 ---------- */

    private static final long FLUSH_DELAY_MS = 400L;

    private static void schedulePersist(Context ctx) {
        sDirty = true;
        Looper looper = Looper.myLooper();
        if (looper == null) looper = Looper.getMainLooper();
        Handler h = sFlushHandler;
        if (h == null || h.getLooper() != looper) {
            h = new Handler(looper);
            sFlushHandler = h;
        }
        final Handler fh = h;
        fh.removeCallbacksAndMessages(null);
        fh.postDelayed(() -> persistNow(ctx), FLUSH_DELAY_MS);
    }

    public static synchronized void persistNow(Context ctx) {
        if (!sDirty) return;
        sDirty = false;
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Map<String, Object>> e : sState.entrySet()) {
                JSONObject per = new JSONObject();
                for (Map.Entry<String, Object> v : new HashMap<>(e.getValue()).entrySet()) {
                    per.put(v.getKey(), v.getValue());
                }
                root.put(e.getKey(), per);
            }
            sSelfWrite = true;
            ConfigChannel.writeState(ctx, root.toString());
        } catch (Throwable t) {
            Log.e("状态落盘失败", t);
        } finally {
            sSelfWrite = false;
        }
    }
}
