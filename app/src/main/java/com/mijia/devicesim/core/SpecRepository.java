/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

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
        if (cached != null) return cached == sMissing ? null : cached;
        String json = loadJson(ctx, model);
        if (json == null) {
            sCache.put(model, sMissing);
            return null;
        }
        try {
            MiotSpec spec = new MiotSpec(new JSONObject(json));
            if (!spec.isValid()) {
                Log.w("spec 没有 services，按无效处理: " + model);
                sCache.put(model, sMissing);
                return null;
            }
            sCache.put(model, spec);
            return spec;
        } catch (Throwable t) {
            Log.e("spec 解析失败 " + model, t);
            sCache.put(model, sMissing);
            return null;
        }
    }

    /** 一个空壳，用来标记「查过了，确实没有」，避免每次控制调用都重跑一遍 IO。*/
    private static final MiotSpec sMissing = new MiotSpec(new JSONObject());

    public static void clearCache() {
        sCache.clear();
    }

    private static String loadJson(Context ctx, String model) {
        String json = ConfigChannel.readSpec(ctx, model);          // Provider
        if (json != null) return json;
        json = ModuleContext.readAsset(ctx, ASSET_DIR + "/" + model + ".json"); // 内置
        if (json != null) return json;
        return ConfigChannel.rootFile("specs/" + ConfigChannel.safeFileName(model) + ".json"); // adb 灌的
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
        return spec.category() + " · " + spec.services.size() + " 服务 · " + spec.allProps().size()
                + " 属性（可写 " + writable + "）";
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
