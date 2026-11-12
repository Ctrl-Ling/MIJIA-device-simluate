/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-12 18:45:53 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 型号 → 设备产品图 URL。
 *
 * <p>米家所有画设备图的地方（首页卡片、自动化「选择设备」页的网格、插件里的设备头图）
 * 拿的都是同一个来源：云端 device list 下发的 {@code icon}，米家把它塞进
 * {@code com.xiaomi.smarthome.device.Device} 的 private 字段 {@code icon}，
 * 对外只经 {@code getIcon()} / {@code getLogo()} 与
 * {@code DeviceStat.deviceIconReal} 暴露出去。
 *
 * <p>假设备是我们自己反射造出来的 bean，这个字段一直没人写，所以米家只能画自己的
 * 灰色占位图 —— 这正是选择设备页里那一片米家 logo 的来历。
 *
 * <p>数据来源是米家自带的 {@code device_config/plugin_config_cn-zh_cn}（构建时把
 * 每个型号的 {@code icon_real} 抽成 {@code assets/catalog/icons.bin}，gzip 后 500KB 左右）。
 * 只按型号查，查不到就返回 null，调用方保持原样。
 */
public final class MijiaIcons {

    private MijiaIcons() {
    }

    /** model 与 URL 之间用 TAB 分隔：比 JSON 省一半字节，读的时候 indexOf 一次就够。*/
    private static final String ASSET = "catalog/icons.bin";

    private static volatile Map<String, String> sIndex;

    public static String iconFor(Context ctx, String model) {
        if (ctx == null || model == null || model.isEmpty()) return null;
        Map<String, String> idx = index(ctx);
        if (idx == null) return null;
        String url = idx.get(model);
        return url == null || url.isEmpty() ? null : url;
    }

    /** 全量索引只建一次：12147 条，gzip 解压 + 切行大约几十毫秒，之后是纯查表。*/
    private static Map<String, String> index(Context ctx) {
        Map<String, String> cached = sIndex;
        if (cached != null) return cached;
        synchronized (MijiaIcons.class) {
            if (sIndex != null) return sIndex;
            InputStream raw = ModuleContext.openAsset(ctx, ASSET);
            if (raw == null) return null;
            Map<String, String> map = new HashMap<>(16384);
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0 || tab == line.length() - 1) continue;
                    map.put(line.substring(0, tab), line.substring(tab + 1));
                }
            } catch (Throwable t) {
                Log.w("设备图索引读取失败: " + t);
            }
            if (map.isEmpty()) return null;
            sIndex = map;
            Log.d("设备图索引就绪: " + map.size() + " 条");
            return map;
        }
    }
}
