/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 11:16:22 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 米家官方型号目录（大类 → 子类 → 型号）。
 *
 * <p>数据来自米家 APK 自带的 {@code assets/device_config/plugin_config_cn-zh_cn}，
 * 由 {@code tools/gen_catalog.mjs} 压成 {@code assets/catalog/devices.json.gz}：
 * 每条是「型号、中文名、产品图 URL、品牌」，前两层是米家「手动添加设备」里那套分类。
 *
 * <p>品牌不是米家给的字段（12160 条里只有 73 条有 brand_name），所以生成时按型号前缀推：
 * 认得的厂牌映射成中文，认不得的直接用前缀。排序统一按「品牌 → 设备名」的中文拼音顺序。
 *
 * <p>为什么不去在线拉：这份目录是米家自己缓存的快照，官方没有给出可直接用的公开接口；
 * 而型号库真正需要联网的只是 spec（属性表），那部分仍然走 {@code SpecFetcher}。
 */
public final class DeviceCatalog {

    /** gzip 压缩的 JSON；故意用 .bin 后缀，避免 aapt2 把 .gz 自动解压掉。*/
    private static final String ASSET = "catalog/catalog.bin";

    /** 中文拼音排序：品牌、设备名都要按它排。*/
    private static final Collator COLL = Collator.getInstance(Locale.CHINA);

    static {
        COLL.setStrength(Collator.SECONDARY);
    }

    /**
     * 近似叫法表：米家目录里同一类设备写法五花八门（扫地 / 扫拖、插座 / 排插、门锁 / 智能锁…），
     * 搜其中任何一个都该把整组捞出来。词条已按 {@link #normalize} 的规则手写成小写无空格，
     * 运行时会再归一化一遍，写错标点也不会漏。
     */
    private static final String[][] SYNONYMS = {
            {"扫地机器人", "扫拖机器人", "扫拖一体", "扫拖一体机", "扫地机", "清扫机器人", "扫地拖地机器人"},
            {"洗地机", "擦地机", "拖地机", "吸拖一体", "洗地机器人"},
            {"吸尘器", "无线吸尘器", "手持吸尘器"},
            {"空气净化器", "净化器", "空净"},
            {"加湿器", "加湿机"},
            {"除湿机", "抽湿机"},
            {"风扇", "电风扇", "循环扇", "塔扇", "落地扇", "空气循环扇"},
            {"空调", "空调器", "空气调节器"},
            {"电暖器", "取暖器", "暖风机", "加热器", "踢脚线取暖器"},
            {"冰箱", "电冰箱", "冰柜"},
            {"洗衣机", "洗烘一体机", "洗干一体机", "烘干机", "干衣机", "洗衣干衣机"},
            {"热水器", "电热水器", "燃气热水器"},
            {"插座", "排插", "插线板", "插排", "接线板"},
            {"开关", "面板开关", "墙壁开关"},
            {"灯泡", "灯", "照明", "吸顶灯", "台灯", "灯带", "氛围灯", "彩灯", "筒灯", "射灯", "夜灯"},
            {"摄像头", "摄像机", "监控", "相机", "网络摄像机"},
            {"门锁", "智能锁", "指纹锁"},
            {"门铃", "智能门铃", "可视门铃"},
            {"窗帘", "窗帘电机", "电动窗帘", "卷帘", "卷帘电机", "开合帘"},
            {"传感器", "感应器"},
            {"网关", "中枢网关", "多模网关"},
            {"音箱", "音响", "扬声器", "小爱音箱"},
            {"电视", "电视机", "智慧屏"},
            {"路由器", "路由"},
            {"净水器", "净水机", "纯水机"},
            {"洗碗机", "洗碗碟机"},
            {"微波炉", "烤箱", "蒸烤箱", "微蒸烤"},
            {"电饭煲", "电饭锅", "电压力锅", "压力锅"},
            {"空气炸锅", "炸锅"},
            {"电水壶", "养生壶", "水壶", "烧水壶"},
            {"体重秤", "体脂秤", "电子秤", "体重计"},
            {"剃须刀", "电动剃须刀"},
            {"电动牙刷", "牙刷"},
            {"喂食器", "宠物喂食器", "宠物饮水机"},
            {"马桶盖", "智能马桶盖"},
            {"晾衣架", "晾衣机", "智能晾衣架"},
            {"油烟机", "集成灶", "燃气灶", "烟灶"},
            {"空气检测仪", "温湿度计", "温湿度传感器", "环境检测"},
            {"温度计", "温度传感器", "温度检测仪", "体温计"},
    };

    /** 归一化后的词 → 同组其它写法（含自己）。*/
    private static final Map<String, String[]> SYN = buildSynonyms();

    private static Map<String, String[]> buildSynonyms() {
        Map<String, String[]> map = new HashMap<>();
        for (String[] group : SYNONYMS) {
            String[] norm = new String[group.length];
            for (int i = 0; i < group.length; i++) norm[i] = normalize(group[i]);
            for (String key : norm) map.put(key, norm);
        }
        return map;
    }

    /** 搜索前统一收拾：半角化、去空白和常见标点、转小写。中文标点也一起干掉。*/
    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            switch (ch) {
                case '(': case ')': case '[': case ']': case '{': case '}':
                case '.': case ',': case ':': case ';': case '/': case '\\':
                case '-': case '_': case '+': case '*': case '|':
                case '·': case '—': case '－': case '、': case '，': case '。':
                case '：': case '；': case '（': case '）': case '【': case '】':
                case '《': case '》': case '「': case '」': case '“': case '”':
                case '‘': case '’': case '!': case '?': case '！': case '？':
                    continue;
                default:
                    sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /** 查一个词的所有近似写法：整词命中就换整组，包含某个词就把那个词替换成同组其它写法。*/
    private static List<String> expand(String q) {
        List<String> out = new ArrayList<>();
        out.add(q);
        String[] whole = SYN.get(q);
        if (whole != null) {
            for (String t : whole) if (!t.equals(q)) out.add(t);
            return out;
        }
        for (Map.Entry<String, String[]> e : SYN.entrySet()) {
            if (!q.contains(e.getKey())) continue;
            for (String alt : e.getValue()) {
                if (alt.equals(e.getKey())) continue;
                out.add(q.replace(e.getKey(), alt));
            }
        }
        return out;
    }

    public static final class Item {
        public final String model;
        public final String name;
        public final String icon;
        public final String brand;
        /** 上市/推出时间（秒，米家目录的 verified_time）；0 = 目录里没给。*/
        public final long releasedAt;
        public final String category;
        public final String subcategory;

        /** 搜索用的归一化文本：去空格/标点、转小写，构造时算一次，搜的时候别再算一万多遍。*/
        private final String nName;
        private final String nModel;
        private final String nBrand;

        public Item(String model, String name, String icon, String brand, long releasedAt,
                    String category, String subcategory) {
            this.model = model;
            this.name = name;
            this.icon = icon;
            this.brand = brand == null ? "" : brand;
            this.releasedAt = releasedAt;
            this.category = category;
            this.subcategory = subcategory;
            this.nName = normalize(this.name);
            this.nModel = normalize(this.model);
            this.nBrand = normalize(this.brand);
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
                                        row.optString(2), row.optString(3), row.optLong(4, 0),
                                        cn, sn));
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

    /** 某个大类下的全部型号；cat/sub 为空 = 不限。结果按「品牌 → 设备名」拼音递增。*/
    public List<Item> of(String cat, String sub) {
        return of(cat, sub, (Collection<String>) null);
    }

    /** 品牌集合也一起过滤；brands 为空/null = 不限品牌，多个品牌是「或」的关系。*/
    public List<Item> of(String cat, String sub, Collection<String> brands) {
        boolean anyBrand = brands == null || brands.isEmpty();
        List<Item> out = new ArrayList<>();
        for (Cat c : cats) {
            if (!cat.isEmpty() && !cat.equals(c.name)) continue;
            for (Sub s : c.subs) {
                if (!sub.isEmpty() && !sub.equals(s.name)) continue;
                for (Item it : s.items) {
                    if (!anyBrand && !brands.contains(it.brand)) continue;
                    out.add(it);
                }
            }
        }
        out.sort(DeviceCatalog::byBrandThenName);
        return out;
    }

    /** 当前范围里出现过的品牌 → 该品牌型号数；顺序按品牌名拼音递增。*/
    public Map<String, Integer> brands(String cat, String sub) {
        Map<String, Integer> counts = new HashMap<>();
        for (Cat c : cats) {
            if (!cat.isEmpty() && !cat.equals(c.name)) continue;
            for (Sub s : c.subs) {
                if (!sub.isEmpty() && !sub.equals(s.name)) continue;
                for (Item it : s.items) counts.merge(it.brand, 1, Integer::sum);
            }
        }
        List<String> names = new ArrayList<>(counts.keySet());
        names.sort(COLL);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String n : names) out.put(n, counts.get(n));
        return out;
    }

    /**
     * 模糊搜「中文名 / 型号 / 品牌」，跨全部大类。
     *
     * <p>先把查询和设备名都 {@link #normalize 归一化}（去空格标点、转小写），
     * 所以「扫拖机器人6」和「扫拖机器人 6」是一回事；
     * 再按 {@link #SYNONYMS} 把近似叫法展开，搜「扫地机器人」也能搜到「扫拖机器人」。
     * 命中设备名的排在命中型号/品牌的前面，组内仍按品牌 → 名称。
     */
    public List<Item> search(String query, int limit) {
        String q = normalize(query);
        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        List<String> terms = expand(q);

        // 分档：原词完全命中 > 原词前缀 > 原词包含 > 近似叫法命中 > 型号/品牌命中。
        // 不分档的话搜「灯带」会被同组的「灯」淹掉，真正的灯带排在几千条后面。
        List<List<Item>> buckets = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) buckets.add(new ArrayList<>());
        for (Item it : all) {
            int tier;
            if (it.nName.equals(q)) {
                tier = 0;
            } else if (it.nName.startsWith(q)) {
                tier = 1;
            } else if (it.nName.contains(q)) {
                tier = 2;
            } else {
                tier = -1;
                for (int i = 1; i < terms.size(); i++) {   // terms[0] 就是 q，前面已比过
                    if (it.nName.contains(terms.get(i))) {
                        tier = 3;
                        break;
                    }
                }
                if (tier < 0) {
                    for (String t : terms) {
                        if (it.nModel.contains(t) || it.nBrand.contains(t)) {
                            tier = 4;
                            break;
                        }
                    }
                }
            }
            if (tier >= 0) buckets.get(tier).add(it);
        }
        for (List<Item> bucket : buckets) {
            bucket.sort(DeviceCatalog::byBrandThenName);
            out.addAll(bucket);
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static int byBrandThenName(Item a, Item b) {
        int c = COLL.compare(a.brand, b.brand);
        return c != 0 ? c : COLL.compare(a.name, b.name);
    }

    public Sub subOf(String cat, String sub) {
        for (Cat c : cats) {
            if (!c.name.equals(cat)) continue;
            for (Sub s : c.subs) if (s.name.equals(sub)) return s;
        }
        return null;
    }
}
