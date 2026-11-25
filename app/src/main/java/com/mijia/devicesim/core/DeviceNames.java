/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 16:52:30 +08:00
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
 * 型号 → 米家官方中文名。
 *
 * <p>数据来自米家 APK 自带的 {@code assets/device_config/plugin_config_cn-zh_cn}
 * （构建时由 {@code tools/gen_names.mjs} 抽成 {@code assets/catalog/names.bin}），
 * 也就是米家「手动添加设备」列表里那一列名字 —— 用户看到的名字和米家里显示的一致。
 *
 * <p>扫描页落库时手里只有一个型号（SSID 把点号换成了横杠），要把它变成
 * 「#米家冰箱 巨能装Pro 620L对开」这种可读名字就查这里；查不到返回空串，
 * 调用方自己再退 spec 类型名或型号本身。
 *
 * <p>读的是<b>模块自己</b>的 assets，米家进程里也能读（{@link ModuleContext}）。
 */
public final class DeviceNames {

    private DeviceNames() {
    }

    private static final String ASSET = "catalog/names.bin";

    private static volatile Map<String, String> sIndex;

    /** 型号的官方中文名；没收录返回空串（不返回 null，调用方少判一层）。*/
    public static String of(Context ctx, String model) {
        if (ctx == null || model == null || model.isEmpty()) return "";
        Map<String, String> idx = index(ctx);
        if (idx == null) return "";
        String name = idx.get(model);
        return name == null ? "" : name;
    }

    private static Map<String, String> index(Context ctx) {
        Map<String, String> cached = sIndex;
        if (cached != null) return cached;
        synchronized (DeviceNames.class) {
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
                Log.w("设备名索引读取失败: " + t);
            }
            if (map.isEmpty()) return null;
            sIndex = map;
            Log.d("设备名索引就绪: " + map.size() + " 条");
            return map;
        }
    }

    /**
     * 把「自动生成的名字」纠正成 {@code #官方中文名}：名字为空、等于裸型号、
     * 或等于 {@code #型号} 这几种都是程序写进去的；用户自己改过的名字一律不动。
     *
     * @return 改写后的名字；不需要改返回 null
     */
    public static String repair(Context ctx, SimDevice d) {
        if (d == null || d.model == null || d.model.isEmpty()) return null;
        String name = d.name == null ? "" : d.name.trim();
        String base = of(ctx, d.model);
        // 老版本把官方中文名（不带 #）直接写进名字，这里也算「自动生成」，一并纠正
        boolean auto = name.isEmpty() || name.equals(d.model) || name.equals(SimDevice.NAME_PREFIX + d.model)
                || (!base.isEmpty() && (name.equals(base) || name.equals(SimDevice.NAME_PREFIX + base)));
        if (!auto) return null;
        if (base.isEmpty()) return null;   // 连官方名都没有就保留现状，别把型号也抹了
        return SimDevice.taggedName(base, d.model);
    }
}