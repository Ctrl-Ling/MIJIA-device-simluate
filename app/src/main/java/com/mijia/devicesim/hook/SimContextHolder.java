/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 02:28:13 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;

import com.mijia.devicesim.core.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 拿米家的 Application Context。
 *
 * <p>为什么需要：模块所有跨进程动作（读配置 Provider、注册 ContentObserver、写文件）都要一个
 * Context，而 Hook 回调里只有 {@code thisObject}，未必是 Context。
 *
 * <p>三条路，从早到晚依次兜底：
 * <ol>
 *   <li>挂 {@code Instrumentation.callApplicationOnCreate(Application)} —— 这是系统调 App 自己
 *       {@code onCreate} 前的那一层，进程一起来就能拿到，比任何界面代码都早；</li>
 *   <li>挂米家 App 类的 {@code onCreate}（有些 ROM/插件进程不走上面那条）；</li>
 *   <li>反射 {@code ActivityThread.currentActivityThread().getApplication()}，任何时点都能现取，
 *       用于前两条没赶上的情况（例如米家进程已经活着、模块刚被启用）。</li>
 * </ol>
 * 拿不到时 {@link #get()} 返回 null，调用方全部做了 null 判定，不会因此崩米家。
 */
public final class SimContextHolder {

    private static final String GROUP = "context";

    private static volatile Context sApp;
    private static volatile boolean sInstrumented;

    private SimContextHolder() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstrumented) return;
        sInstrumented = true;
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                    Application.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.args[0];
                            if (app != null) attach(app);
                        }
                    });
        } catch (Throwable t) {
            HookUtil.hookFailed(GROUP, "Instrumentation#callApplicationOnCreate", t);
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    attach((Application) param.thisObject);
                }
            });
        } catch (Throwable t) {
            HookUtil.hookFailed(GROUP, "Application#onCreate", t);
        }
    }

    /** 由 XposedEntry 在 load 阶段直接塞（进程内已有 Application 实例时用）。*/
    public static void attach(Application app) {
        try {
            Context c = app.getApplicationContext();
            if (c == null) c = app;
            if (sApp != c) {
                sApp = c;
                Log.v("米家 Application Context 就绪: " + c.getClass().getName());
                // Context 一到手就把配置/状态机拉起来，钩子第一次被调用时就不会拿到空配置
                XposedEntry.bootstrap(c);
            }
        } catch (Throwable t) {
            Log.v("attach 失败: " + HookUtil.shortMsg(t));
        }
    }

    public static Context get() {
        Context c = sApp;
        if (c != null) return c;
        c = viaActivityThread();
        if (c != null) sApp = c;
        return c;
    }

    /**
     * 反射取当前进程的 Application。{@code currentActivityThread} 是隐藏方法，反射能读到；
     * 失败就当没有，不抛。
     */
    private static Context viaActivityThread() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (thread == null) return null;
            Object app = XposedHelpers.callMethod(thread, "getApplication");
            if (app instanceof Application) {
                Context c = ((Application) app).getApplicationContext();
                return c != null ? c : (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 兜底：用模块自己的宿主上下文以外的任何 Context 都行（Hook 回调里传进来的 thisObject）。*/
    public static void offer(Object maybeContext) {
        if (sApp != null || !(maybeContext instanceof Context)) return;
        try {
            Context c = ((Context) maybeContext).getApplicationContext();
            sApp = c != null ? c : (Context) maybeContext;
            XposedBridge.log("[MIJIA] context 由 thisObject 兜底获得");
        } catch (Throwable ignored) {
        }
    }
}
