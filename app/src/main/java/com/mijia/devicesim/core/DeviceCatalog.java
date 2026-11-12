/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-12 08:10:18 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import java.util.zip.GZIPOutputStream;

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
    /** 子类 id → 中文名，由 gen_catalog.mjs 一起生成；App 内同步时照着它翻译。*/
    private static final String SUBS_ASSET = "catalog/subs.json";
    /** 米家包名与它自带的目录资产（AssetManager 里不带 assets/ 前缀）。*/
    private static final String MIJIA_PKG = "com.xiaomi.smarthome";
    private static final String MIJIA_ASSET = "device_config/plugin_config_cn-zh_cn";
    /** 点「同步米家目录」后，从手机上重新解析出来的快照存这里，下次启动优先用它。*/
    private static final String CACHE_FILE = "catalog-synced.bin";
    /** 全字段快照（JSONL，一行一个型号）；详情页按型号查，不参与列表加载。*/
    private static final String DETAILS_ASSET = "catalog/details.bin";
    private static final String DETAILS_CACHE = "details-synced.bin";

    /** 型号前缀 → 中文厂牌；认不得就用前缀本身（与 gen_catalog.mjs 保持一致）。*/
    private static final Map<String, String> PREFIX_BRAND = new HashMap<>();

    static {
        PREFIX_BRAND.put("xiaomi", "小米");
        PREFIX_BRAND.put("mijia", "米家");
        PREFIX_BRAND.put("yeelink", "Yeelight");
        PREFIX_BRAND.put("viomi", "云米");
        PREFIX_BRAND.put("yunmi", "云米");
        PREFIX_BRAND.put("chunmi", "纯米");
        PREFIX_BRAND.put("zhimi", "智米");
        PREFIX_BRAND.put("chuangmi", "创米");
        PREFIX_BRAND.put("lumi", "绿米");
        PREFIX_BRAND.put("dreame", "追觅");
        PREFIX_BRAND.put("roborock", "石头");
        PREFIX_BRAND.put("rockrobo", "石头");
        PREFIX_BRAND.put("dooya", "杜亚");
        PREFIX_BRAND.put("leishi", "雷士");
        PREFIX_BRAND.put("opple", "欧普");
        PREFIX_BRAND.put("aupu", "奥普");
        PREFIX_BRAND.put("bull", "公牛");
        PREFIX_BRAND.put("philips", "飞利浦");
        PREFIX_BRAND.put("honyar", "鸿雁");
        PREFIX_BRAND.put("loock", "鹿客");
        PREFIX_BRAND.put("bshcn", "博西家电");
        PREFIX_BRAND.put("linp", "领普");
    }

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
            {"扫地机器人", "扫拖机器人", "扫拖一体", "扫拖一体机", "扫地机", "清扫机器人", "扫地拖地机器人",
                    "掃地機器人", "掃拖機器人", "掃拖一體機", "掃地機"},
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

    /**
     * 查一个词的所有近似写法。
     *
     * <p>三条路，缺一不可：
     * <ol>
     *   <li><b>整词命中</b>：搜的就是表里的词，整组换过去；</li>
     *   <li><b>查询包含表里的词</b>：比如「米家扫地机器人」，把词替换成同组写法；</li>
     *   <li><b>查询是表里词的前缀</b>：边打边搜时（「扫地」「扫拖」「扫地机器」）也算同组，
     *       否则只有打满四个字才认，看着就像模糊搜索失灵。</li>
     * </ol>
     * 前缀只认 2 个字以上：1 个字展开会把「电」「灯」这种首字带进一堆不相关的组。
     */
    private static List<String> expand(String q) {
        List<String> out = new ArrayList<>();
        out.add(q);
        String[] whole = SYN.get(q);
        if (whole != null) {
            for (String t : whole) if (!t.equals(q)) out.add(t);
            return out;
        }
        boolean prefix = q.length() >= 2;
        for (Map.Entry<String, String[]> e : SYN.entrySet()) {
            String key = e.getKey();
            if (q.contains(key)) {
                for (String alt : e.getValue()) {
                    if (alt.equals(key)) continue;
                    String t = q.replace(key, alt);
                    if (!out.contains(t)) out.add(t);
                }
            } else if (prefix && key.startsWith(q)) {
                // 还没输完：先把整组备选都算上，命中越全越靠前由分档去管
                for (String alt : e.getValue()) if (!out.contains(alt)) out.add(alt);
                if (!out.contains(key)) out.add(key);
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
        private final String nCat;
        private final String nSub;

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
            this.nCat = normalize(this.category);
            this.nSub = normalize(this.subcategory);
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
            DeviceCatalog fromCache = tryCache(ctx);       // 点过「同步米家目录」就用那份
            sCached = fromCache != null ? fromCache : parseAsset(ctx);
            return sCached;
        }
    }

    /** 删掉同步快照，回到 APK 里内置的那份目录。*/
    public static DeviceCatalog reloadBuiltin(Context ctx) throws Exception {
        cacheFile(ctx).delete();
        detailsCacheFile(ctx).delete();
        DeviceCatalog c = parseAsset(ctx);
        synchronized (DeviceCatalog.class) {
            sCached = c;
        }
        return c;
    }

    /** 私有目录里有没有同步过的快照 —— 界面用它判断「恢复内置」是不是白点。*/
    public static boolean hasSyncedCache(Context ctx) {
        File f = cacheFile(ctx);
        return f.isFile() && f.length() > 0;
    }

    private static File cacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), CACHE_FILE);
    }

    private static File detailsCacheFile(Context ctx) {
        return new File(ctx.getFilesDir(), DETAILS_CACHE);
    }

    private static DeviceCatalog tryCache(Context ctx) {
        File f = cacheFile(ctx);
        if (!f.isFile() || f.length() == 0) return null;
        try (InputStream in = new GZIPInputStream(new FileInputStream(f))) {
            return parseStream(in);
        } catch (Throwable t) {
            return null;   // 快照坏了就当没有，别把型号库卡死
        }
    }

    private static DeviceCatalog parseAsset(Context ctx) throws Exception {
        try (InputStream in = new GZIPInputStream(ctx.getAssets().open(ASSET))) {
            return parseStream(in);
        }
    }

    /** 读紧凑格式（内置目录和同步快照共用一套）。*/
    private static DeviceCatalog parseStream(InputStream in) throws Exception {
        String text;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20)) {
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

    /* ---------- 同步：直接读手机上米家 APK 自带的目录 ---------- */

    /** 同步结果：新目录 + 米家版本号，界面拿去提示。*/
    public static final class SyncResult {
        public final DeviceCatalog catalog;
        public final int models;
        public final String mijiaVersion;

        SyncResult(DeviceCatalog catalog, String mijiaVersion) {
            this.catalog = catalog;
            this.models = catalog.size();
            this.mijiaVersion = mijiaVersion;
        }
    }

    /**
     * 从手机上已装的米家 APK 里重新读一遍目录。
     *
     * <p>能读别人的资产：{@code createPackageContext} 会带回米家那套 AssetManager，
     * 按路径就能开（Android 11 起要清单里声明 {@code <queries>}）。
     * 用 {@link JsonReader} 流式解析 17MB 的 JSON，不把整棵树塞进内存。
     *
     * <p>解析完写一份快照到模块私有目录，重启后直接用，不用每次再解 17MB。
     */
    public static SyncResult syncFromMijia(Context ctx) throws Exception {
        Context mijia = ctx.createPackageContext(MIJIA_PKG, Context.CONTEXT_IGNORE_SECURITY);
        String version = "";
        try {
            version = ctx.getPackageManager().getPackageInfo(MIJIA_PKG, 0).versionName;
        } catch (Throwable ignored) {
        }
        Map<Integer, String> subs = readSubs(ctx);
        MijiaParsed parsed;
        File detailTmp = new File(ctx.getFilesDir(), DETAILS_CACHE + ".tmp");
        try {
            try (InputStream in = mijia.getAssets().open(MIJIA_ASSET);
                 OutputStream os = new FileOutputStream(detailTmp);
                 Writer details = new BufferedWriter(new OutputStreamWriter(
                         new GZIPOutputStream(os), StandardCharsets.UTF_8))) {
                parsed = readMijia(in, subs, details);
            }
        } catch (Exception e) {
            detailTmp.delete();     // 半截快照宁可不要，别让详情页读到残行
            throw e;
        }
        long ts = parsed.ts > 0 ? parsed.ts : System.currentTimeMillis() / 1000L;
        for (Cat c : parsed.cats) {
            for (Sub s : c.subs) s.items.sort(DeviceCatalog::byBrandThenName);
        }
        parsed.cats.sort((a, b) -> b.count - a.count);
        DeviceCatalog fresh = new DeviceCatalog(ts, parsed.cats);
        writeCache(ctx, ts, parsed.cats);
        File detailDst = detailsCacheFile(ctx);
        detailDst.delete();
        if (!detailTmp.renameTo(detailDst)) detailTmp.delete();
        synchronized (DeviceCatalog.class) {
            sCached = fresh;
        }
        return new SyncResult(fresh, version);
    }

    private static final class MijiaParsed {
        long ts;
        final List<Cat> cats = new ArrayList<>();
    }

    private static final class Raw {
        String model, name, icon, brand, cat;
        int subId;
        long released;
    }

    /** 流式读米家目录：只关心 result.devices.list，其它键一律 skip；每条原始字段顺手写进详情快照。*/
    private static MijiaParsed readMijia(InputStream in, Map<Integer, String> subs,
                                         Writer details) throws IOException {
        MijiaParsed out = new MijiaParsed();
        Map<String, Map<String, List<Item>>> groups = new LinkedHashMap<>();
        JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        r.setLenient(true);
        r.beginObject();
        while (r.hasNext()) {
            if (!"result".equals(r.nextName())) {
                r.skipValue();
                continue;
            }
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                if (!"devices".equals(k)) {
                    r.skipValue();
                    continue;
                }
                r.beginObject();
                while (r.hasNext()) {
                    String dk = r.nextName();
                    if ("last_modify".equals(dk)) {
                        out.ts = readLongAny(r);
                    } else if ("list".equals(dk)) {
                        r.beginArray();
                        while (r.hasNext()) addRaw(groups, subs, readEntry(r, details));
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
            }
            r.endObject();
        }
        r.endObject();
        r.close();

        for (Map.Entry<String, Map<String, List<Item>>> ce : groups.entrySet()) {
            List<Sub> subList = new ArrayList<>();
            for (Map.Entry<String, List<Item>> se : ce.getValue().entrySet()) {
                if (!se.getValue().isEmpty()) subList.add(new Sub(se.getKey(), se.getValue()));
            }
            if (!subList.isEmpty()) out.cats.add(new Cat(ce.getKey(), subList));
        }
        return out;
    }

    private static void addRaw(Map<String, Map<String, List<Item>>> groups,
                               Map<Integer, String> subs, Raw raw) {
        if (raw == null) return;
        String model = trim(raw.model);
        String name = trim(raw.name);
        if (model.isEmpty() || name.isEmpty()) return;
        String cat = trim(raw.cat);
        if (cat.isEmpty()) cat = "其他";
        String sub = subs.get(raw.subId);
        if (sub == null || sub.isEmpty()) sub = "其他设备";
        Item item = new Item(model, name, trim(raw.icon), brandOf(model, raw.brand),
                raw.released, cat, sub);
        groups.computeIfAbsent(cat, x -> new LinkedHashMap<>())
                .computeIfAbsent(sub, x -> new ArrayList<>())
                .add(item);
    }

    /**
     * 读一条型号：整条原始字段都收下来（详情页要展示全部 68 个键），
     * 顺手取列表要用的那 7 个；原始对象按行写进详情快照。
     */
    private static Raw readEntry(JsonReader r, Writer details) throws IOException {
        JSONObject all = readObject(r);
        Raw raw = new Raw();
        raw.model = all.optString("model", "");
        raw.name = all.optString("name", "");
        raw.icon = all.optString("icon_real", "");
        raw.brand = all.optString("brand_name", "");
        raw.cat = all.optString("cate_name", "");
        raw.subId = (int) all.optLong("subcategory_id", 0);
        raw.released = all.optLong("verified_time", 0);
        String model = trim(raw.model);
        if (details != null && !model.isEmpty()) {
            try {
                JSONObject line = new JSONObject();
                line.put("model", model);
                line.put("f", all);
                details.write(line.toString());
                details.write('\n');
            } catch (Throwable ignored) {
                // 详情快照写失败不影响目录同步本身
            }
        }
        return raw;
    }

    /** 通用读值：字符串 / 数字 / 布尔 / null / 数组 / 对象，整棵子树照收。*/
    private static Object readAny(JsonReader r) throws IOException {
        switch (r.peek()) {
            case BEGIN_ARRAY: {
                JSONArray arr = new JSONArray();
                r.beginArray();
                while (r.hasNext()) arr.put(readAny(r));
                r.endArray();
                return arr;
            }
            case BEGIN_OBJECT:
                return readObject(r);
            case STRING:
                return r.nextString();
            case NUMBER: {
                double d = r.nextDouble();
                if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
                    return (long) d;
                }
                return d;
            }
            case BOOLEAN:
                return r.nextBoolean();
            case NULL:
                r.nextNull();
                return JSONObject.NULL;
            default:
                r.skipValue();
                return JSONObject.NULL;
        }
    }

    private static JSONObject readObject(JsonReader r) throws IOException {
        JSONObject o = new JSONObject();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            Object v = readAny(r);
            try {
                o.put(k, v);
            } catch (JSONException ignored) {
                // 键名非法就丢这一项，其它字段照收
            }
        }
        r.endObject();
        return o;
    }

    /**
     * 取某个型号的全部原始字段：先读同步快照，没有快照再读 APK 内置的详情文件。
     * 找不到（或文件坏了）返回 null，由界面显示「没有这个型号的字段」。
     */
    public static JSONObject detailFields(Context ctx, String model) {
        String m = trim(model);
        if (m.isEmpty()) return null;
        JSONObject fromCache = detailFromFile(detailsCacheFile(ctx), m);
        if (fromCache != null) return fromCache;
        try (InputStream in = ctx.getAssets().open(DETAILS_ASSET)) {
            return detailFromStream(in, m);
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject detailFromFile(File f, String model) {
        if (!f.isFile() || f.length() == 0) return null;
        try (InputStream in = new FileInputStream(f)) {
            return detailFromStream(in, model);
        } catch (Throwable t) {
            return null;
        }
    }

    /** JSONL 顺序扫到目标型号为止；不整份读进内存。*/
    private static JSONObject detailFromStream(InputStream in, String model) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                try {
                    JSONObject row = new JSONObject(line);
                    if (model.equals(row.optString("model"))) {
                        JSONObject f = row.optJSONObject("f");
                        return f != null ? f : new JSONObject();
                    }
                } catch (Throwable ignored) {
                    // 坏行跳过，别让一行毁掉整次查询
                }
            }
        }
        return null;
    }

    private static long readLongAny(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.NUMBER) return r.nextLong();
        if (t == JsonToken.STRING) {
            String s = r.nextString().trim();
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        r.skipValue();
        return 0;
    }

    private static String readString(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        if (t == JsonToken.STRING) return r.nextString();
        if (t == JsonToken.NUMBER) return String.valueOf(r.nextLong());
        if (t == JsonToken.BOOLEAN) return String.valueOf(r.nextBoolean());
        if (t == JsonToken.NULL) {
            r.nextNull();
            return "";
        }
        r.skipValue();
        return "";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** 与 gen_catalog.mjs 同一套：brand_name 脏数据能还原就还原，否则按型号前缀推。*/
    private static String brandOf(String model, String rawBrand) {
        String b = cleanBrand(rawBrand);
        if (!b.isEmpty()) return b;
        int dot = model.indexOf('.');
        String prefix = dot > 0 ? model.substring(0, dot) : model;
        String mapped = PREFIX_BRAND.get(prefix.toLowerCase(Locale.ROOT));
        if (mapped != null) return mapped;
        return prefix.isEmpty() ? "其他" : prefix;
    }

    private static String cleanBrand(String raw) {
        String b = raw == null ? "" : raw.trim();
        if (b.isEmpty()) return "";
        if (b.matches("(?i)(u[0-9a-fA-F]{4})+")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 5 <= b.length(); i += 5) {
                try {
                    sb.append((char) Integer.parseInt(b.substring(i + 1, i + 5), 16));
                } catch (NumberFormatException ignored) {
                }
            }
            return sb.toString();
        }
        try {
            return URLDecoder.decode(b, "UTF-8");
        } catch (Throwable t) {
            return b;
        }
    }

    /** 子类 id → 中文名，构建时由 gen_catalog.mjs 写进 APK。*/
    private static Map<Integer, String> readSubs(Context ctx) {
        Map<Integer, String> map = new HashMap<>();
        try (InputStream in = ctx.getAssets().open(SUBS_ASSET)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject o = new JSONObject(bos.toString("UTF-8"));
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                try {
                    map.put(Integer.parseInt(k), o.optString(k));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    /** 把刚解析出来的目录压成紧凑快照，下次启动直接读。*/
    private static void writeCache(Context ctx, long ts, List<Cat> cats) {
        try {
            JSONArray catArr = new JSONArray();
            for (Cat c : cats) {
                JSONObject co = new JSONObject();
                co.put("n", c.name);
                JSONArray subArr = new JSONArray();
                for (Sub s : c.subs) {
                    JSONObject so = new JSONObject();
                    so.put("n", s.name);
                    JSONArray rows = new JSONArray();
                    for (Item it : s.items) {
                        JSONArray row = new JSONArray();
                        row.put(it.model);
                        row.put(it.name);
                        row.put(it.icon);
                        row.put(it.brand);
                        row.put(it.releasedAt);
                        rows.put(row);
                    }
                    so.put("items", rows);
                    subArr.put(so);
                }
                co.put("subs", subArr);
                catArr.put(co);
            }
            JSONObject root = new JSONObject();
            root.put("ts", ts);
            root.put("cats", catArr);
            File f = cacheFile(ctx);
            try (FileOutputStream fos = new FileOutputStream(f);
                 GZIPOutputStream gz = new GZIPOutputStream(fos)) {
                gz.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
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

    /** 单分类 + 品牌过滤；为空 = 不限。*/
    public List<Item> of(String cat, String sub, Collection<String> brands) {
        return of(cat == null || cat.isEmpty() ? null : java.util.Collections.singleton(cat),
                sub == null || sub.isEmpty() ? null : java.util.Collections.singleton(sub),
                brands);
    }

    /**
     * 多分类 / 多子类 / 多品牌过滤：每个集合为空/null = 该项不限，集合内部是「或」。
     *
     * <p>分类和子类是「且」：既要在选中的大类里，也要在选中的子类里。
     * 子类名在不同大类下可能重复（比如好几个类都有「其他设备」），按名字合并正合适 ——
     * 用户勾「其他设备」本来就想看所有类里的其他设备。
     */
    public List<Item> of(Collection<String> cats, Collection<String> subs, Collection<String> brands) {
        boolean anyCat = cats == null || cats.isEmpty();
        boolean anySub = subs == null || subs.isEmpty();
        boolean anyBrand = brands == null || brands.isEmpty();
        List<Item> out = new ArrayList<>();
        for (Cat c : this.cats) {
            if (!anyCat && !cats.contains(c.name)) continue;
            for (Sub s : c.subs) {
                if (!anySub && !subs.contains(s.name)) continue;
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
        return brands(cat == null || cat.isEmpty() ? null : java.util.Collections.singleton(cat),
                sub == null || sub.isEmpty() ? null : java.util.Collections.singleton(sub));
    }

    /** 多分类 / 多子类范围里的品牌计数，供品牌多选弹窗用。*/
    public Map<String, Integer> brands(Collection<String> cats, Collection<String> subs) {
        boolean anyCat = cats == null || cats.isEmpty();
        boolean anySub = subs == null || subs.isEmpty();
        Map<String, Integer> counts = new HashMap<>();
        for (Cat c : this.cats) {
            if (!anyCat && !cats.contains(c.name)) continue;
            for (Sub s : c.subs) {
                if (!anySub && !subs.contains(s.name)) continue;
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
     * 模糊搜「中文名 / 型号 / 品牌 / 类型 / 子类」，跨全部大类。
     *
     * <p>先把查询和设备名都 {@link #normalize 归一化}（去空格标点、转小写），
     * 所以「扫拖机器人6」和「扫拖机器人 6」是一回事；
     * 再按 {@link #SYNONYMS} 把近似叫法展开，搜「扫地机器人」也能搜到「扫拖机器人」。
     * 命中设备名的排在前面，其次型号/品牌，最后类型/子类（按大类名回捞整类），
     * 组内仍按品牌 → 名称。
     */
    public List<Item> search(String query, int limit) {
        return search(query, limit, null, null, null);
    }

    /**
     * 带筛选的模糊搜索：先按分类/子类/品牌筛，再在剩下的范围里分档排。
     *
     * <p>为什么不是「搜完再筛」：先搜再筛会把 limit 用光 ——
     * 搜「灯」前 300 条全是别的类，筛完剩不下东西，看着像筛选没生效。
     * 筛选条件放进循环里，limit 算的是命中筛选之后的结果。
     * 三个集合语义与 {@link #of(Collection, Collection, Collection)} 完全一致：空 = 不限，集合内「或」。
     */
    public List<Item> search(String query, int limit,
                             Collection<String> cats, Collection<String> subs,
                             Collection<String> brands) {
        String q = normalize(query);
        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        List<String> terms = expand(q);
        boolean anyCat = cats == null || cats.isEmpty();
        boolean anySub = subs == null || subs.isEmpty();
        boolean anyBrand = brands == null || brands.isEmpty();

        // 分档：原词完全命中 > 原词前缀 > 原词包含 > 近似叫法命中 > 型号/品牌命中 > 类型/子类命中。
        // 不分档的话搜「灯带」会被同组的「灯」淹掉，真正的灯带排在几千条后面。
        List<List<Item>> buckets = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) buckets.add(new ArrayList<>());
        for (Item it : all) {
            // 筛选先过一遍：AND 语义，三个维度都得上
            if (!anyCat && !cats.contains(it.category)) continue;
            if (!anySub && !subs.contains(it.subcategory)) continue;
            if (!anyBrand && !brands.contains(it.brand)) continue;
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
                if (tier < 0) {
                    for (String t : terms) {
                        // 类型/子类放最后一档：搜「扫地机器人」仍然先出名字里带这几个字的
                        if (it.nCat.contains(t) || it.nSub.contains(t)) {
                            tier = 5;
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
