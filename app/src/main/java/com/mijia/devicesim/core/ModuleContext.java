/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.core;

import android.content.Context;

/**
 * 在米家进程里取「本模块的 Context」，用来读模块 apk 自带的 assets（内置型号 spec 库）。
 * createPackageContext 只要求目标应用已安装，不需要 root；失败退回宿主 Context，
 * 那时内置 spec 读不到，就只剩 Provider / 兜底目录两条路。
 */
public final class ModuleContext {

    private ModuleContext() {
    }

    private static volatile Context sCached;

    public static Context of(Context host) {
        Context cached = sCached;
        if (cached != null) return cached;
        if (host == null) return null;
        Context result = host;
        if (!Consts.MODULE_PACKAGE.equals(host.getPackageName())) {
            try {
                result = host.createPackageContext(Consts.MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            } catch (Throwable t) {
                Log.w("拿不到模块 Context（内置 spec 库不可用）: " + t);
                result = host;
            }
        }
        sCached = result;
        return result;
    }

    /** 从 assets 读一个文本文件，不存在返回 null。*/
    public static String readAsset(Context ctx, String path) {
        Context mod = of(ctx);
        if (mod == null) return null;
        try (java.io.InputStream in = mod.getAssets().open(path);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public static String[] listAssetDir(Context ctx, String dir) {
        Context mod = of(ctx);
        if (mod == null) return new String[0];
        try {
            String[] names = mod.getAssets().list(dir);
            return names != null ? names : new String[0];
        } catch (Throwable t) {
            return new String[0];
        }
    }
}
