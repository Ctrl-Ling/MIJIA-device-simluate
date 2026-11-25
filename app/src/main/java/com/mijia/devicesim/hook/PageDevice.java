/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 15:52:34 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedHelpers;

/**
 * 「现在这个页面上，用户看的是哪台模拟设备」。
 *
 * <p>为什么要它：11.10.623 实测，首页点开卡片后，卡片页（{@code MIUI10CardActivity} 里的
 * RN 卡片）向宿主问设备时 did 传的是 <b>null</b> ——
 * {@code PluginHostApiImpl.getDeviceByDid(null) -> null}。真设备页面里这个值当然不是 null，
 * 所以米家对 null 的回答就是「没有这台设备」，卡片随后不再读属性：标题下面只有
 * 「模式 --」，底部「更多操作」点了也没反应。
 *
 * <p>所以这里从「页面现场」把 did 认回来，只用来补 null 查询：
 * <ol>
 *   <li>先在 ActivityThread 的活动表里看每个页面的 Intent extras，找长得像模拟 did 的字符串；</li>
 *   <li>再兜底看 Activity 自己的字段（卡片页常把 did 放在 {@code mDid} 之类的字段里）。</li>
 * </ol>
 *
 * <p>判定规则只有一条：值必须是 {@link Consts#isSimulatedDid(String)}，
 * 并且确实在启用配置里。真设备页面不会命中，调 null 查询也照样拿到 null，行为不变。
 */
final class PageDevice {

    private PageDevice() {
    }

    /** 认出来的假设备；认不出返回 null。*/
    static SimDevice resolve(SimConfig cfg) {
        if (cfg == null) return null;
        try {
            Object thread = XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"), "currentActivityThread");
            if (thread == null) return null;
            Object records = XposedHelpers.getObjectField(thread, "mActivities");
            if (records == null) return null;
            int size = (Integer) XposedHelpers.callMethod(records, "size");
            SimDevice best = null;
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < size; i++) {
                Object rec = XposedHelpers.callMethod(records, "valueAt", i);
                if (rec == null) continue;
                Object act = XposedHelpers.getObjectField(rec, "activity");
                if (!(act instanceof Activity)) continue;
                String did = didOfPage((Activity) act);
                if (did == null) continue;
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) continue;
                int score = scoreOf((Activity) act, rec);
                if (score > bestScore) {
                    bestScore = score;
                    best = dev;
                }
            }
            if (best != null) {
                Log.d("页面现场认出设备: " + best.model + " did=" + best.did);
            }
            return best;
        } catch (Throwable t) {
            Log.v("页面现场认设备失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /** 卡片页 / 插件页优先，其次是拿到焦点的页面。*/
    private static int scoreOf(Activity act, Object record) {
        int score = 0;
        String name = act.getClass().getSimpleName().toLowerCase();
        if (name.contains("card")) score += 4;
        if (name.contains("plugin") || name.contains("rn")) score += 3;
        Object focused = HookUtil.readFieldQuiet(record, "focused");
        if (Boolean.TRUE.equals(focused)) score += 2;
        Object paused = HookUtil.readFieldQuiet(record, "paused");
        if (Boolean.FALSE.equals(paused)) score += 1;
        return score;
    }

    /** 页面里带的模拟 did：先 Intent extras，再页面字段。*/
    static String didOfPage(Activity act) {
        try {
            Intent intent = act.getIntent();
            Bundle extras = intent == null ? null : intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object v = extras.get(key);
                    String did = asSimDid(v);
                    if (did != null) return did;
                }
            }
        } catch (Throwable t) {
            Log.v("页面 Intent 取 did 失败: " + HookUtil.shortMsg(t));
        }
        // 卡片页不一定把 did 放 extras，字段里也常见（mDid / device_id …）
        for (Class<?> c = act.getClass(); c != null && c != Activity.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                if (f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    String did = asSimDid(f.get(act));
                    if (did != null) return did;
                } catch (Throwable ignored) {
                    // 不可读的字段跳过
                }
            }
        }
        return null;
    }

    private static String asSimDid(Object v) {
        if (v instanceof String && Consts.isSimulatedDid((String) v)) return (String) v;
        if (v instanceof Number) {
            String s = String.valueOf(((Number) v).longValue());
            if (Consts.isSimulatedDid(s)) return s;
        }
        return null;
    }
}