/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 16:15:17 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.util.LinkedHashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化选设备页的最后一道闸：TCA（触发条件能力）白名单。
 *
 * <p>为什么还要补这一刀 —— {@code SpecSceneDeviceListViewModel#getAllData} 把设备装进分组
 * 之前还有一步：把所有 (did, model) 交给 {@code SpecSceneConfigRepository#Ooooo00}
 * （批量查 TCA 能力），拿回一个 <b>允许出现的 did 集合</b>，房间分组和「未分配」分组
 * 都只收 {@code 集合.contains(did)} 的设备；不在集合里的会被日志
 * {@code batchCheckTCA filtered(no TCA capability)} 点名丢掉。
 *
 * <p>这个集合来自本地库 {@code DeviceTcaConfigDatabase} + 云端下发的模型缓存，
 * 假设备哪边都没有记录，所以永远是空的 → 页面一片空白。
 *
 * <p>做法：不去污染数据库，也不改模型缓存，只在结果集合返回给调用方之前补上假 did。
 * 这两个方法都是 suspend：但它们编译出来的状态机就是方法本体，挂起时返回
 * {@code COROUTINE_SUSPENDED}（枚举，不是 Set，直接跳过），真正完成的那次调用
 * 同步返回 Set，after 钩子在这里把假 did 并进去，调用方拿到的是替换后的集合，
 * 后续 {@code Set#contains(did)} 过滤就能收录假设备。
 *
 * <p>两层都挂是为了兜版本差异：{@code Ooooo00} 是选设备页那条，{@code OoooOOO}
 * 是同签名（List,String,Z,Continuation）的另一条批量查询，任一挂上都能生效，
 * 两个都挂上也只是幂等地补一次（集合已经有就不动）。
 *
 * <p>11.10.623 起这两个方法在 {@code specscene.manager.SpecSceneConfigRepository}
 * （类名未混淆），旧版的 {@code specscene.manager.OooOo00} 已不存在。
 */
public final class SceneTcaHook {

    private static final String GROUP = "scene-tca";

    /** 批量 TCA 能力查询所在仓库（11.10.623 未混淆）。*/
    private static final String CL_REPO = "com.xiaomi.smarthome.specscene.manager.SpecSceneConfigRepository";
    /** 选设备页入口：batchCheck(List<Pair<did,model>>, sceneId, isAuto, cont) → Set。*/
    private static final String M_BATCH = "Ooooo00";
    /** 同形状的另一条批量查询。*/
    private static final String M_QUERY = "OoooOOO";
    /** suspend 方法的续体参数类型（版本会变，只在找不到精确签名时用）。*/
    private static final String CL_CONT = "kotlin.coroutines.jvm.internal.ContinuationImpl";

    private SceneTcaHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> repo = HookUtil.findClassQuiet(CL_REPO, lpparam.classLoader);
        if (repo == null) {
            HookReport.skip(GROUP, CL_REPO, "类不存在（TCA 能力查询可能已改版）");
            return;
        }
        Class<?> cont = HookUtil.findClassQuiet(CL_CONT, lpparam.classLoader);

        boolean batch = hookSuspend(repo, M_BATCH, java.util.List.class, cont);
        boolean query = hookSuspend(repo, M_QUERY, java.util.List.class, cont);
        if (batch || query) {
            HookReport.ok(GROUP, "install", "批量查=" + batch + "，同形查询=" + query);
        } else {
            HookReport.fail(GROUP, "install", "两个挂点都没挂上");
        }
    }

    /** 精确签名优先；续体类对不上就按方法名全挂（同名只有一个重载）。*/
    private static boolean hookSuspend(Class<?> clazz, String method, Class<?> listType, Class<?> cont) {
        if (cont != null && HookUtil.hookMethod(GROUP, clazz, method, AFTER,
                listType, String.class, boolean.class, cont)) {
            return true;
        }
        return HookUtil.hookMethod(GROUP, clazz, method, AFTER);
    }

    private static final XC_MethodHook AFTER = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object res = param.getResult();
                // 挂起中的那次返回 COROUTINE_SUSPENDED（枚举），不是 Set，跳过；
                // 真正完成的那次同步返回 Set，这里补假 did
                if (!(res instanceof Set)) return;

                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;

                Set<?> original = (Set<?>) res;
                LinkedHashSet<Object> merged = null;
                for (SimDevice d : cfg.enabledDevices()) {
                    if (d == null || d.did == null || d.did.isEmpty()) continue;
                    if (original.contains(d.did)) continue;
                    if (merged == null) merged = new LinkedHashSet<Object>(original);
                    merged.add(d.did);
                }
                if (merged == null) return;

                param.setResult(merged);
                int added = merged.size() - original.size();
                HookReport.ok(GROUP, "pass", "TCA 白名单放行 " + added + " 台假设备");
                CallLog.record(DeviceListHook.context(), CallLog.OP_INJECT, "scene-tca",
                        "tca-set", "", original.size(), merged.size(), 0,
                        "自动化选设备页 TCA 白名单补 " + added + " 台");
                XposedEntry.publishSoon();
            } catch (Throwable t) {
                Log.e("TCA 白名单补写异常", t);
            }
        }
    };
}
