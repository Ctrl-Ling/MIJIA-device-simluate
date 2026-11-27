/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-27 15:10:53 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 本地模拟自动化不去拉云端详情 —— 保住「智能」tab 的长按多选。
 *
 * <p>大白话：{@link SceneListLocalHook} 往列表里补的 #行，云端根本没有这条场景。
 * 米家绑定列表时发现仓库（{@code SceneManagerV2}）里没有对应对象，就会走
 * {@code SceneListViewModel#getOneSceneDetail(scene, home, i)} 去云端要详情，
 * 并且<b>先把这个 id 记进「正在拉详情」的 HashSet</b>（字段 {@code o0OOoO}），
 * 等详情回来再摘掉。我们的 id 云端永远查不到，回调进不来，这个 id 就永远留在集合里。
 *
 * <p>而 623 的长按入口 {@code MySceneFragmentkt$OooO0O0#OooO0oO(qjq)} 开头就有一句：
 * 「集合非空 → 弹『操作失败，请重试』并直接 return」。所以只要注入过一条模拟自动化，
 * 之后<b>所有</b>自动化（包括真场景）的长按多选都被这一句卡死。
 * v167 的实测：进列表时集合变成 {@code [906116258, 939432850, 959843029]}
 * （三条都是我们 9 开头的本地 id），之后没有任何一条回调把它清空。
 *
 * <p>处理：挂在 {@code getOneSceneDetail} 的入口，id 命中 {@link LocalSceneStore}
 * 就地 {@code setResult(null)} 跳过 —— 方法体只有「加集合 + 起协程拉云端」两件事，
 * 跳过之后页面表现和原来完全一样（原来那次请求也从来没有结果），
 * 但集合不再被脏 id 占住，长按闸门就恢复正常。
 * 真实场景的 id 不在登记表里，一律原样放行，米家自己的拉取与回收不受影响。
 */
public final class SceneDetailGuard {

    private static final String GROUP = "scene-detail-guard";

    private static final String CL_SLV = "com.xiaomi.smarthome.scene.ui.list.SceneListViewModel";
    /** getOneSceneDetail：详情请求的唯一入口（脏集合就在这里被写进去）。*/
    private static final String M_FETCH = "Oooo0OO";
    /** u6r 基类上的场景 id。*/
    private static final String F_SCENE_ID = "o0OOo0Oo";

    private SceneDetailGuard() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        int n = HookSniper.hook(lpparam.classLoader, GROUP, CL_SLV, new String[]{M_FETCH},
                HookSniper.Shape.of(null, "u6r", "Home", "int"), SKIP_LOCAL);
        if (n > 0) {
            HookReport.ok(GROUP, "install", "本地自动化详情请求拦截已挂上");
        } else {
            HookReport.skip(GROUP, "install", "SceneListViewModel 尚未加载或已改名，交给侦察兵");
        }
    }

    private static final XC_MethodHook SKIP_LOCAL = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length < 1 || args[0] == null) return;
                Object id = HookUtil.readFieldQuiet(args[0], F_SCENE_ID);
                if (!(id instanceof String)) return;
                if (!LocalSceneStore.contains((String) id)) return;
                // void 方法：setResult 即跳过原方法体（加集合 + 拉云端都不做）
                param.setResult(null);
                Log.d("本地自动化不拉云端详情: id=" + id + "（长按多选闸门保住了）");
            } catch (Throwable t) {
                // 拦截层出问题也不能影响米家原流程
                Log.v("详情拦截异常: " + HookUtil.shortMsg(t));
            }
        }
    };
}
