/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-09 22:05:00 +08:00
 */
package de.robv.android.xposed.callbacks;

import android.content.pm.ApplicationInfo;

/**
 * 只用到 {@link LoadPackageParam} 这一个内部类（模块走 {@code IXposedHookLoadPackage} 入口，
 * 不直接注册 XC_LoadPackage 回调），所以这里只把字段形状摆正，不做完整继承链。
 */
public abstract class XC_LoadPackage {

    public abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;

    /** 字段顺序与类型照抄真实 API（appInfo 是 ApplicationInfo，不是 int）。*/
    public static class LoadPackageParam {
        /** Name of the package being loaded. */
        public String packageName;
        /** Process in which package is loaded. */
        public String processName;
        /** Application info for this process, or {@code null}. */
        public ApplicationInfo appInfo;
        /** Class loader used to load classes from package. */
        public ClassLoader classLoader;
        /** Name of the owning module, or {@code null}. */
        public String preferenceModulePackageName;
    }
}
