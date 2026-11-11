/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 09:25:23 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 米家官方型号目录（大类 → 子类 → 型号）。
 *
 * <p>数据来自米家 APK 自带的 {@code assets/device_config/plugin_config_cn-zh_cn}，
 * 由 {@code tools/gen_catalog.mjs} 压成 {@code assets/catalog/devices.json.gz}：
 * 每条只有「型号、中文名、产品图 URL」，前两层是米家「手动添加设备」里那套分类。
 *
 * <p>为什么不去在线拉：这份目录是米家自己缓存的快照，官方没有给出可直接用的公开接口；
 * 而型号库真正需要联网的只是 spec（属性表），那部分仍然走 {@code SpecFetcher}。
 */
public final class DeviceCatalog {

    /** gzip 压缩的 JSON；故意用 .bin 后缀，避免 aapt2 把 .gz 自动解压掉。*/
    private static final String ASSET = "catalog/catalog.bin";

    public static final class Item {
        public final String model;
        public final String name;
        public final String icon;
        public final String category;
        public final String subcategory;

        public Item(String model, String name, String icon, String category, String subcategory) {
            this.model = model;
            this.name = name;
            this.icon = icon;
            this.category = category;
            this.subcategory = subcategory;
        }
    }

    public static final class Sub {
        public final String name;
        public final List<Item> items;

        Sub(String name, List<Item> items) {
            this.name = name;
            this.items = items;
        }
    }

    public static final class Cat {
        public final String name;
        public final List<Sub> subs;
        public int count;

        Cat(String name, List<Sub> subs) {
            this.name = name;
            this.subs = subs;
            for (Sub s : subs) count += s.items.size();
        }
    }

    private static volatile DeviceCatalog sCached;

    private final long stamp;
    private final List<Cat> cats;
    private final List<Item> all = new ArrayList<>();
    private final Map<String, Item> byModel = new HashMap<>();

    private DeviceCatalog(long stamp, List<Cat> cats) {
        this.stamp = stamp;
        this.cats = cats;
        for (Cat c : cats) {
            for (Sub s : c.subs) {
                for (Item it : s.items) {
                    all.add(it);
                    if (!byModel.containsKey(it.model)) byModel.put(it.model, it);
                }
            }
        }
    }

    /** 已经装载过就直接给，没装过返回 null（调用方自己在后台线程 load）。*/
    public static DeviceCatalog cached() {
        return sCached;
    }

    /** 读一遍压缩资源；失败抛异常，由界面回显原因并退回旧的型号列表。*/
    public static DeviceCatalog load(Context ctx) throws Exception {
        DeviceCatalog c = sCached;
        if (c != null) return c;
        synchronized (DeviceCatalog.class) {
            if (sCached != null) return sCached;
            sCached = parse(ctx);
            return sCached;
        }
    }

    private static DeviceCatalog parse(Context ctx) throws Exception {
        String text;
        try (InputStream raw = ctx.getAssets().open(ASSET);
             GZIPInputStream in = new GZIPInputStream(raw);
             ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            text = bos.toString("UTF-8");
        }
        JSONObject root = new JSONObject(text);
        long ts = root.optLong("ts", 0);
        JSONArray catArr = root.optJSONArray("cats");
        List<Cat> cats = new ArrayList<>();
        if (catArr != null) {
            for (int i = 0; i < catArr.length(); i++) {
                JSONObject co = catArr.optJSONObject(i);
                if (co == null) continue;
                String cn = co.optString("n");
                List<Sub> subs = new ArrayList<>();
                JSONArray subArr = co.optJSONArray("subs");
                if (subArr != null) {
                    for (int j = 0; j < subArr.length(); j++) {
                        JSONObject so = subArr.optJSONObject(j);
                        if (so == null) continue;
                        String sn = so.optString("n");
                        List<Item> items = new ArrayList<>();
                        JSONArray itemArr = so.optJSONArray("items");
                        if (itemArr != null) {
                            for (int k = 0; k < itemArr.length(); k++) {
                                JSONArray row = itemArr.optJSONArray(k);
                                if (row == null || row.length() < 2) continue;
                                items.add(new Item(row.optString(0), row.optString(1),
                                        row.optString(2), cn, sn));
                            }
                        }
                        if (!items.isEmpty()) subs.add(new Sub(sn, items));
                    }
                }
                if (!subs.isEmpty()) cats.add(new Cat(cn, subs));
            }
        }
        Collections.sort(cats, (a, b) -> b.count - a.count);
        return new DeviceCatalog(ts, cats);
    }

    public long stamp() {
        return stamp;
    }

    public List<Cat> cats() {
        return cats;
    }

    public int size() {
        return all.size();
    }

    public Item find(String model) {
        if (model == null) return null;
        return byModel.get(model.trim());
    }

    /** 某个大类下的全部型号；sub 为空 = 该大类全部。*/
    public List<Item> of(String cat, String sub) {
        List<Item> out = new ArrayList<>();
        for (Cat c : cats) {
            if (!cat.isEmpty() && !cat.equals(c.name)) continue;
            for (Sub s : c.subs) {
                if (!sub.isEmpty() && !sub.equals(s.name)) continue;
                out.addAll(s.items);
            }
        }
        return out;
    }

    /** 按「中文名 / 型号」模糊搜，跨全部大类。*/
    public List<Item> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        for (Item it : all) {
            if (it.name.toLowerCase(Locale.ROOT).contains(q)
                    || it.model.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(it);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    public Sub subOf(String cat, String sub) {
        for (Cat c : cats) {
            if (!c.name.equals(cat)) continue;
            for (Sub s : c.subs) if (s.name.equals(sub)) return s;
        }
        return null;
    }
}
