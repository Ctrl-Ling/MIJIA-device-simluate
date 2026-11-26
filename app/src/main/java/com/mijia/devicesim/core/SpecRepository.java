/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 16:37:14 +08:00
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
 * 型号库：model -> MIoT Spec。只认「该型号自己的」spec，命中即缓存。
 * <ol>
 *   <li>Provider / 本地镜像 / adb 目录里的 specs/&lt;model&gt;.json —— 用户导入或在线取到的；</li>
 *   <li>模块 apk 内置 assets/specs/&lt;model&gt;.json —— 出厂自带的常用型号。</li>
 * </ol>
 *
 * <p>铁律（用户 2026-11-26 明确）：没有就是没有 —— <b>绝不拿同族/同品牌型号的 spec 顶替</b>。
 * 本模块是让用户在没真机时体验该型号真实的控制界面和自动化选项，错 spec 会渲染出一整套
 * 假控件，比空白更误导。缺的时候在线按型号取（{@link SpecFetcher}），取不到就按缺失处理。
 */
public final class SpecRepository {

    private SpecRepository() {
    }

    public static final String ASSET_DIR = "specs";

    private static final Map<String, MiotSpec> sCache = new ConcurrentHashMap<>();

    public static MiotSpec forModel(Context ctx, String model) {
        return forModel(ctx, model, false);
    }

    /**
     * 运行期取 spec：本地没有就顺手让 {@link SpecFetcher} 去官方库按型号取真货。
     * 只给钩子用 —— 界面枚举型号库时用 {@link #forModel}，免得一屏几十个型号同时开下载。
     */
    public static MiotSpec forModelOnline(Context ctx, String model) {
        return forModel(ctx, model, true);
    }

    private static MiotSpec forModel(Context ctx, String model, boolean allowFetch) {
        if (model == null || model.isEmpty()) return null;
        MiotSpec cached = sCache.get(model);
        if (cached != null) return cached;
        String json = loadJson(ctx, model);
        if (json == null) {
            if (allowFetch) {
                // 本地真没有：去官方库取一份真的。异步、同型号只跑一次；
                // 取不到就继续按缺失处理（页面空白也比假控件诚实），下次再打开会用取到的那份。
                SpecFetcher.fetchForModel(ctx, model, null);
            }
            return null;
        }
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

    /** 某个型号的 spec 变了（在线取到 / 重新导入 / 删除），丢掉它的缓存。*/
    public static void invalidate(String model) {
        if (model != null) sCache.remove(model);
    }

    /** 本地有没有该型号自己的 spec（只看本地，不触发联网）。*/
    public static boolean hasLocal(Context ctx, String model) {
        return model != null && !model.isEmpty() && loadJson(ctx, model) != null;
    }

    private static String loadJson(Context ctx, String model) {
        String json = ConfigChannel.readSpec(ctx, model);          // Provider / 镜像 / adb 目录
        if (json != null) return json;
        return ModuleContext.readAsset(ctx, ASSET_DIR + "/" + model + ".json"); // 内置
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
        // 米家进程里拿不到本模块的 Provider（Unknown authority），那边只走本地镜像；
        // 本模块自己的界面进程走 Provider，型号库列表才看得见、管得了。
        if (Consts.TARGET_PACKAGE.equals(ctx.getPackageName())) return false;
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
        if (Consts.TARGET_PACKAGE.equals(ctx.getPackageName())) return false;
        return ConfigChannel.deleteSpec(ctx, model);
    }

    /** 这份 spec 是来自 Provider（可删）还是只存在于内置 assets（不可删）。*/
    public static boolean isImported(Context ctx, String model) {
        return ConfigChannel.readSpec(ctx, model) != null;
    }
}
