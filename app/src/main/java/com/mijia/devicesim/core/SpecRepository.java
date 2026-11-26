/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 16:28:40 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 型号库：model -> MIoT Spec。三个来源按优先级叠加，命中即缓存。
 * <ol>
 *   <li>Provider 里的 specs/&lt;model&gt;.json —— 界面导入/在线拉取的，用户可以随时覆盖；</li>
 *   <li>模块 apk 内置 assets/specs/&lt;model&gt;.json —— 出厂自带的常用型号；</li>
 *   <li>/data/local/tmp/mijia_devicesim/specs/ —— 有 adb 的用户手工灌的。</li>
 * </ol>
 */
public final class SpecRepository {

    private SpecRepository() {
    }

    public static final String ASSET_DIR = "specs";

    private static final Map<String, MiotSpec> sCache = new ConcurrentHashMap<>();

    public static MiotSpec forModel(Context ctx, String model) {
        if (model == null || model.isEmpty()) return null;
        MiotSpec cached = sCache.get(model);
        if (cached != null) return cached;
        String json = loadJson(ctx, model);
        if (json == null) return null;
        try {
            MiotSpec spec = new MiotSpec(new JSONObject(json));
            if (!spec.isValid()) {
                Log.w("spec 没有 services，按无效处理: " + model);
                return null;
            }
            sCache.put(model, spec);
            return spec;
        } catch (Throwable t) {
            Log.e("spec 解析失败 " + model, t);
            return null;
        }
    }

    public static void clearCache() {
        sCache.clear();
    }

    private static String loadJson(Context ctx, String model) {
        String json = ConfigChannel.readSpec(ctx, model);          // Provider
        if (json != null) return json;
        json = ModuleContext.readAsset(ctx, ASSET_DIR + "/" + model + ".json"); // 内置
        if (json != null) return json;
        json = appSpecCache(ctx, model);                            // 米家自己下过的 spec
        if (json != null) return json;
        json = siblingSpec(ctx, model);                             // 同品牌同品类已缓存型号借一份
        if (json != null) return json;
        return ConfigChannel.rootFile("specs/" + ConfigChannel.safeFileName(model) + ".json"); // adb 灌的
    }

    /**
     * 同族借 spec：米家没给这个型号下过 spec 时，从 {@code files/specs} 里找
     * 「同品牌 + 同品类（型号前两段相同）」的已缓存型号，把它当模板，
     * 只把 urn 里的型号标记换成目标型号（{@code xiaomi-ymm7} → {@code xiaomi-ymm5}）。
     *
     * <p>为什么这么干：实测 {@code xiaomi.waterheater.ymm5} 在米家里没有本地 spec，
     * RN 插件页 {@code getSpecString} 拿不到东西直接白屏；同系列的
     * {@code xiaomi.waterheater.ymm7} 有缓存，两者品类/服务结构一致，
     * 换掉型号标记后 urn 与设备型号自洽，插件就能正常渲染。
     *
     * <p>只在前四个来源都落空时兜底；能拆出「品牌.品类.产品」三段以上才走。
     */
    private static String siblingSpec(Context ctx, String model) {
        if (ctx == null || model == null) return null;
        String[] target = model.split("\\.");
        if (target.length < 3) return null;
        String targetToken = target[0] + "-" + target[target.length - 1];
        File[] files = new File(ctx.getFilesDir(), ASSET_DIR).listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            String donor = name.substring(0, name.length() - 5);
            if (donor.equals(model)) continue;
            String[] parts = donor.split("\\.");
            if (parts.length != target.length) continue;
            boolean sameFamily = true;
            for (int i = 0; i < target.length - 1; i++) {
                if (!parts[i].equals(target[i])) {
                    sameFamily = false;
                    break;
                }
            }
            if (!sameFamily) continue;

            String donorToken = parts[0] + "-" + parts[parts.length - 1];
            String json = readFile(f);
            if (json == null || !json.contains(donorToken)) continue;
            Log.d("spec 借同族型号: " + model + " ← " + donor);
            return json.replace(donorToken, targetToken);
        }
        return null;
    }

    private static String readFile(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 米家自己的 spec 缓存：{@code <app files>/specs/<model>.json}。
     *
     * <p>为什么读它：用户点开过某台设备（或它挂在家庭里）之后，米家会把该型号的 spec 实例
     * 落一份在这里。模拟设备用的是真实型号，这份缓存通常已经在 —— 拿它当第四个来源，
     * {@code DeviceBean.specUrn} 才不是空串，标准插件链路（{@code StdPluginConfig} /
     * {@code isResourceValid}）才肯往下走；RN 页面也才有 spec 可以渲染。
     *
     * <p>优先顺序放在「adb 灌的」之前：adb 那份是手工覆盖，仍然是最高优先的可写通道，
     * 但它通常不存在；app 缓存是米家自己维护的实时数据。
     */
    private static String appSpecCache(Context ctx, String model) {
        if (ctx == null || model == null || model.isEmpty()) return null;
        try {
            File f = new File(new File(ctx.getFilesDir(), ASSET_DIR),
                    ConfigChannel.safeFileName(model) + ".json");
            if (!f.isFile() || !f.canRead()) return null;
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(64, f.length()));
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                String s = new String(bos.toByteArray(), Charset.forName("UTF-8")).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Throwable t) {
            Log.v("读米家 spec 缓存失败 " + model + ": " + t);
            return null;
        }
    }

    /** 内置 + 已导入的全部型号名（去掉 .json）。*/
    public static List<String> knownModels(Context ctx) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            String manifest = ConfigChannel.listSpecs(ctx);
            if (manifest != null) {
                JSONArray a = new JSONArray(manifest);
                for (int i = 0; i < a.length(); i++) out.add(a.optJSONObject(i).optString("model"));
            }
        } catch (Throwable ignored) {
        }
        for (String name : ModuleContext.listAssetDir(ctx, ASSET_DIR)) {
            if (name.endsWith(".json")) out.add(name.substring(0, name.length() - 5));
        }
        out.remove("");
        return new ArrayList<>(out);
    }

    /** 型号卡片上的摘要：设备类型、服务数、可写属性数。*/
    public static String describe(Context ctx, String model) {
        MiotSpec spec = forModel(ctx, model);
        if (spec == null) return "无 spec（面板可能空白，需先导入）";
        int writable = 0;
        for (MiotSpec.Prop p : spec.allProps()) if (p.writable()) writable++;
        return MiotLabels.typeLabel(spec.category()) + " · " + spec.services.size() + " 服务 · "
                + spec.allProps().size() + " 属性（可写 " + writable + "）";
    }

    public static String urnFor(Context ctx, String model) {
        MiotSpec spec = forModel(ctx, model);
        return spec == null ? "" : spec.urn;
    }

    public static boolean importSpec(Context ctx, String model, String json) {
        if (ctx == null || model == null || json == null) return false;
        try {
            new JSONObject(json); // 先验一遍语法，坏数据不入库
        } catch (Throwable t) {
            Log.w("导入的 spec 不是合法 JSON: " + t);
            return false;
        }
        boolean ok = ConfigChannel.writeSpec(ctx, model, json);
        if (ok) sCache.remove(model);
        return ok;
    }

    /**
     * 移除一份导入的 spec。
     * 只删 Provider 里那份：内置 assets 是随 apk 出厂的，删不掉也不该删，
     * 所以移除以后 {@link #forModel} 仍会退回内置版本，界面上会如实说明。
     */
    public static boolean deleteSpec(Context ctx, String model) {
        if (ctx == null || model == null || model.isEmpty()) return false;
        sCache.remove(model);
        return ConfigChannel.deleteSpec(ctx, model);
    }

    /** 这份 spec 是来自 Provider（可删）还是只存在于内置 assets（不可删）。*/
    public static boolean isImported(Context ctx, String model) {
        return ConfigChannel.readSpec(ctx, model) != null;
    }
}
