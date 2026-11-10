/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 19:05:12 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 「这台条目是我造的假设备吗」+「是就把它落成正式设备」。
 *
 * <p>为什么需要这一层：米家扫描列表里的条目是 {@code PushBindEntity}，字段名全被混淆
 * （did 落在 {@code Oooo000}、型号落在 {@code Oooo00O}，11.9.705 实测），而且换个版本就换名。
 * 所以认设备<b>一律按值特征、不按字段名</b>：
 * <ul>
 *   <li>did：我们占用 {@code 9} 开头的 10 位纯数字段（{@link Consts#isSimulatedDid}），
 *       真实 did 是 10~11 位且不会以 9 开头，所以「值本身」就是身份，不需要记名字；</li>
 *   <li>型号：点分小写、≤64 字符、不含斜杠（miot spec 的型号形状）；</li>
 *   <li>token：32 位 hex；mac：{@code 28:6C:07:xx:xx:xx} 这种 17 位冒号串。</li>
 * </ul>
 *
 * <p>「添加」在本地到底能做什么（11-10 静态取证结论，见 docs/HOOKS.md）：
 * 米家那条路要 热点检查 → smartconfig → 云端绑定 才会诞生 {@code Device}，假设备永远走不完。
 * 所以短路之后由这里把这台设备<b>落进本模块配置清单</b>，首页列表由已有的设备注入负责显示 —— 
 * 用户看到的「添加成功」是真的出现在首页，而不是一个假的成功提示。
 */
public final class DeviceAcceptor {

    private DeviceAcceptor() {
    }

    /** 一个对象上最多读多少个 String 字段（混淆类字段多，读太多会拖慢热路径）。*/
    private static final int MAX_STRINGS = 32;

    /* ---------- 认身份 ---------- */

    /**
     * 从任意对象（PushBindEntity / Parcelable / 米家自己的 bean）里认出我们的 did。
     * 认不出来返回 null —— 调用方据此决定「完全不碰米家的真实流程」。
     */
    public static String simulatedDidOf(Object target) {
        for (String s : collectStrings(target)) {
            if (Consts.isSimulatedDid(s)) return s;
        }
        return null;
    }

    /** 这份 did 是否属于「本模块已经知道的设备」：配置清单里 或 向导现场合成的那台。*/
    public static boolean isKnownSimulatedDid(String did) {
        if (did == null || did.isEmpty()) return false;
        if (ConfigRepository.current().findByDid(did) != null) return true;
        RequestedModel.Record r = RequestedModel.get();
        return r != null && did.equals(r.device.did);
    }

    /** 从 intent 里认：先看 Parcelable（{@code bind_aiot_wifi} / {@code aiot_wifi}），再扫全部 String extras。*/
    public static String simulatedDidInIntent(Object rawIntent) {
        if (!(rawIntent instanceof Intent)) return null;
        Intent intent = (Intent) rawIntent;
        for (String key : PARCELABLE_KEYS) {
            Object parcel = quietParcelable(intent, key);
            String did = simulatedDidOf(parcel);
            if (did != null) return did;
        }
        try {
            android.os.Bundle b = intent.getExtras();
            if (b != null) {
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    if (v instanceof String && Consts.isSimulatedDid((String) v)) return (String) v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 11.9.705 实测：扫描条目点击后走 {@code putParcelable("bind_aiot_wifi", entity)}，配网页再用 {@code "aiot_wifi"}。*/
    private static final String[] PARCELABLE_KEYS = {"bind_aiot_wifi", "aiot_wifi"};

    private static Object quietParcelable(Intent intent, String key) {
        try {
            return intent.getParcelableExtra(key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 对象上第一个长得像 miot 型号的 String 字段。*/
    public static String modelOf(Object target) {
        for (String s : collectStrings(target)) {
            if (looksLikeModel(s)) return s;
        }
        return null;
    }

    /** intent 里的型号：先按已知键，再扫全部 String extras。*/
    public static String modelInIntent(Object rawIntent) {
        if (!(rawIntent instanceof Intent)) return null;
        Intent intent = (Intent) rawIntent;
        for (String key : new String[]{"model", "extra_model", "device_model", "chosen_model"}) {
            String v;
            try {
                v = intent.getStringExtra(key);
            } catch (Throwable t) {
                continue;
            }
            if (looksLikeModel(v)) return v;
        }
        try {
            android.os.Bundle b = intent.getExtras();
            if (b != null) {
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    if (v instanceof String && looksLikeModel((String) v)) return (String) v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** miot 型号形状：点分、小写字母/数字/-/_、≤64、不含斜杠。*/
    public static boolean looksLikeModel(String v) {
        if (v == null) return false;
        int dot = v.indexOf('.');
        if (dot <= 0 || dot == v.length() - 1) return false;
        if (v.length() > 64 || v.indexOf('/') >= 0) return false;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '-' || ch == '_';
            if (!ok) return false;
        }
        return true;
    }

    /* ---------- 落地 ---------- */

    /**
     * 把一条扫描条目变成正式设备：写进配置清单（Provider + 镜像），后续由首页列表注入显示。
     *
     * <p>三种来源都要能落：
     * <ol>
     *   <li>向导现场合成、还挂在 {@link RequestedModel} 上的那台 —— 直接用它已经生成好的
     *       did/token/mac，保证「扫描页那一条」和「首页这一台」是同一个身份；</li>
     *   <li>配置里早就有的设备 —— 幂等，只确保 {@code enabled}；</li>
     *   <li>只认得出 did 的条目（例如别的进程造的） —— 从条目本身把 token/mac 抄回来，
     *       抄不到就新生成。</li>
     * </ol>
     *
     * @return 一行给人看的话（成功/失败都要说清楚，界面和日志都靠它）
     */
    public static String accept(Context ctx, String did, String model, Object bean) {
        if (did == null || !Consts.isSimulatedDid(did)) {
            return "没认出模拟设备身份，交给米家真实流程";
        }
        SimConfig cfg = ConfigRepository.current();
        SimDevice d = cfg.findByDid(dedup(did));
        boolean isNew = false;
        if (d == null) {
            RequestedModel.Record r = RequestedModel.get();
            if (r != null && did.equals(r.device.did)) {
                d = r.device;                     // 用现场合成的那份身份
            }
        }
        if (d == null) {
            d = new SimDevice();
            d.did = did;
            d.token = tokenOf(bean);
            d.mac = macOf(bean);
            d.type = Consts.TYPE_WIFI;
            isNew = true;
        }
        String beanModel = model == null ? modelOf(bean) : model;
        if (beanModel != null && (d.model == null || d.model.isEmpty())) d.model = beanModel;
        if (d.token == null || d.token.isEmpty()) d.token = SimDevice.newToken();
        if (d.mac == null || d.mac.isEmpty()) d.mac = SimDevice.newMac();
        d.enabled = true;
        d.online = true;
        if (!d.isValid()) {
            return "did=" + did + " 认出来了但型号不明，写不进清单（米家那边照常走）";
        }
        if (isNew) {
            d.sort = cfg.size() + 1;
            cfg.devices.add(d);
        }
        boolean ok = ConfigRepository.save(ctx, cfg);
        if (!ok) {
            return "配置写入失败：Provider 不可达（did=" + did + " model=" + d.model + "）";
        }
        HookReport.publish(ctx);
        return "已落进清单：" + d.model + " did=" + d.did + "（清单共 " + cfg.size() + " 台）";
    }

    /** did 段是保留的，配置里同 did 只应有一条；这里只做防御性清洗。*/
    private static String dedup(String did) {
        return did == null ? "" : did.trim();
    }

    private static String tokenOf(Object bean) {
        for (String s : collectStrings(bean)) {
            if (s.length() == Consts.TOKEN_LENGTH && isHex(s)) return s.toUpperCase(java.util.Locale.ROOT);
        }
        return "";
    }

    private static String macOf(Object bean) {
        for (String s : collectStrings(bean)) {
            if (s.length() == 17 && s.charAt(2) == ':' && s.charAt(5) == ':' && s.charAt(14) == ':') return s;
        }
        return "";
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    /** 把对象（含父类）上的非静态 String 字段值抄一份出来，供上面按值特征匹配。*/
    public static List<String> collectStrings(Object target) {
        List<String> out = new ArrayList<>();
        if (target == null) return out;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return out;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (!(v instanceof String)) continue;
                    String s = ((String) v).trim();
                    if (s.isEmpty() || s.length() > 64) continue;
                    out.add(s);
                    if (out.size() >= MAX_STRINGS) return out;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    /** 任意一个参数里有没有我们的条目（点击参数、集合元素都算）。*/
    public static String findSimulatedDid(Collection<?> candidates) {
        if (candidates == null) return null;
        for (Object o : candidates) {
            if (o == null) continue;
            String did = simulatedDidOf(o);
            if (did != null) return did;
            if (o instanceof Collection) {
                String nested = findSimulatedDid((Collection<?>) o);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
