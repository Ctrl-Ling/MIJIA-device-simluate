/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-09 22:05:00 +08:00
 */
package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public interface IXposedHookLoadPackage {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
