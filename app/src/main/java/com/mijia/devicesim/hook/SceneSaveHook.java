/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-18 18:30:29 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化「创建 / 保存」链路的兜底。
 *
 * <p>现场：编辑页放行之后（v91），点「创建」仍弹「保存失败」。链路是
 * {@code SpecSceneEditPageV2.OooOoo0} → {@code OooOO0O.OooOO0O(弱引用,mue,owe,ht1,awe)}
 * 拼好 {@code SceneInfo} 之后，把保存交给 {@code _m_j.dpd#OooO00o(SceneInfo, Callback)}：
 * 它先取一份 {@code lumi_extra_map_info}（场景动作转换表）再往下走，成功才轮到
 * {@code b39.onSuccess} → {@code b9d(case 5)} → {@code rf0.invoke()} 真正发起建场景请求。
 *
 * <p>为什么假设备会卡在这一步 —— 预检那一段拿的是云端/插件下发的转换表，遇到云端不认识的
 * did 组合就整段回失败；失败信息到 {@code b39.onFailure(int, String)} 时被直接丢掉，
 * 只播两句 toast（0x7f0c2139「创建失败，请重试」+ 0x7f0c1c8b「保存失败」），所以现场看不出原因。
 *
 * <p>做法：
 * <ol>
 *   <li>{@code dpd.OooO00o} 入口把回调换成代理 —— 成功照常透传；失败先把
 *       {@code code/msg} 打进日志（append-only 的 LSPosed 日志里能直接 grep 到），
 *       再拿手里的 {@code SceneInfo} 走一次 {@code onSuccess}，让米家自己继续
 *       「b9d(5) → rf0 → 真正建场景」那条路，不伪造任何界面状态；</li>
 *   <li>{@code b39#onFailure / onSuccess} 各挂一条只读日志，超时（10s aid）或别处直接回调
 *       的失败也能看到错误码。</li>
 * </ol>
 */
public final class SceneSaveHook {

    private static final String GROUP = "scene-save";

    /** 场景保存的唯一入口（单例 {@code OooO0OO}）。*/
    private static final String CL_SAVE_ENTRY = "_m_j.dpd";
    /** 保存回调的外壳：失败码就在它这里被丢掉。*/
    private static final String CL_SAVE_CALLBACK = "_m_j.b39";
    /** 设备 API 的回调接口，做代理要用。*/
    private static final String CL_CALLBACK_IFACE = "com.xiaomi.smarthome.device.api.Callback";
    /** SceneInfo 外观类，只用来精确挂签名。*/
    private static final String CL_SCENE_INFO = "com.xiaomi.smarthome.device.api.SceneInfo";

    /** b39 的 case 字段：0 = RN 插件弹窗，1 = 场景保存。*/
    private static final String F_CASE = "OooO00o";

    private static Class<?> sCallbackIface;

    private SceneSaveHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        sCallbackIface = HookUtil.findClassQuiet(CL_CALLBACK_IFACE, lpparam.classLoader);
        Class<?> scene = HookUtil.findClassQuiet(CL_SCENE_INFO, lpparam.classLoader);

        boolean entry;
        if (scene != null && sCallbackIface != null) {
            entry = HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_SAVE_ENTRY, "OooO00o",
                    BEFORE_SAVE, scene, sCallbackIface);
        } else {
            entry = HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_SAVE_ENTRY, "OooO00o",
                    BEFORE_SAVE, Object.class, Object.class);
        }

        boolean fail = HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_SAVE_CALLBACK,
                "onFailure", AFTER_SAVE_FAILED, int.class, String.class);
        boolean ok = HookUtil.hookByClassName(GROUP, lpparam.classLoader, CL_SAVE_CALLBACK,
                "onSuccess", AFTER_SAVE_DONE, Object.class);

        if (entry) {
            HookReport.ok(GROUP, "install", "保存入口接管，失败码日志=" + fail + "，成功日志=" + ok);
        } else {
            HookReport.fail(GROUP, "install", "保存入口没挂上");
        }
    }

    /* ---------- 1. 保存入口：失败就换成「直存」 ---------- */

    private static final XC_MethodHook BEFORE_SAVE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                Object scene = param.args[0];
                Object callback = param.args[1];
                if (scene == null || callback == null) return;
                if (!CL_SAVE_CALLBACK.equals(callback.getClass().getName())) return;

                Log.d("场景保存开始: " + describe(scene));
                Object proxy = rescueProxy(callback, scene);
                if (proxy != null) {
                    param.args[1] = proxy;
                    Log.d("保存回调已换成直存代理");
                }
            } catch (Throwable t) {
                Log.e("场景保存接管异常", t);
            }
        }
    };

    /**
     * 回调代理：成功原样透传；失败打日志后改走 onSuccess。
     *
     * <p>「改走 onSuccess」不是伪造界面 —— 米家的成功分支就是
     * {@code b9d(case 5) → rf0.invoke()}，真正建场景的请求在 rf0 里，让 App 自己走完。
     */
    private static Object rescueProxy(final Object delegate, final Object scene) {
        final Class<?> iface = sCallbackIface;
        if (iface == null || !iface.isInterface()) return null;
        try {
            ClassLoader loader = iface.getClassLoader();
            return Proxy.newProxyInstance(loader, new Class<?>[]{iface}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    try {
                        if ("onFailure".equals(name)) {
                            String code = args != null && args.length > 0 ? String.valueOf(args[0]) : "?";
                            String msg = args != null && args.length > 1 ? String.valueOf(args[1]) : "";
                            Log.d("场景保存失败: code=" + code + " msg=" + msg + "，改用直存");
                            Method ok = delegate.getClass().getMethod("onSuccess", Object.class);
                            ok.setAccessible(true);
                            return ok.invoke(delegate, scene);
                        }
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("toString".equals(name)) return "SceneSaveProxy";
                    } catch (Throwable t) {
                        Log.e("保存回调代理异常", t);
                    }
                    return method.invoke(delegate, args);
                }
            });
        } catch (Throwable t) {
            Log.v("保存回调代理创建失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /* ---------- 2. 只读日志：错误码与成功 ---------- */

    private static final XC_MethodHook AFTER_SAVE_FAILED = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                int which = HookUtil.readIntField(param.thisObject, F_CASE, -1);
                if (which != 1) return;
                Object code = param.args != null && param.args.length > 0 ? param.args[0] : null;
                Object msg = param.args != null && param.args.length > 1 ? param.args[1] : null;
                Log.d("场景保存失败回调: code=" + code + " msg=" + msg);
                // v111：再接一份调用栈 —— 现场证明 dpd 入口根本没被调用，得先看清是谁把失败递给了 b39
                Log.d(callerTrace());
            } catch (Throwable t) {
                Log.v("保存失败日志异常: " + t);
            }
        }
    };

    /** 当前线程里 b39.onFailure 的调用链（after 钩子仍在这个调用栈内）。*/
    private static String callerTrace() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder("场景保存失败来源:");
        int shown = 0;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (c == null) continue;
            if (c.startsWith("java.lang.Thread") || c.startsWith("de.robv.android.xposed")) continue;
            sb.append("\n    at ").append(e);
            if (++shown >= 14) break;
        }
        return sb.toString();
    }

    private static final XC_MethodHook AFTER_SAVE_DONE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                int which = HookUtil.readIntField(param.thisObject, F_CASE, -1);
                if (which != 1) return;
                Object scene = param.args != null && param.args.length > 0 ? param.args[0] : null;
                Log.d("场景保存回调成功: " + describe(scene));
            } catch (Throwable t) {
                Log.v("保存成功日志异常: " + t);
            }
        }
    };

    /* ---------- 小工具 ---------- */

    /** 只读描述：触发/动作/条件各有几条、都挂在哪些 did 上。*/
    private static String describe(Object scene) {
        if (scene == null) return "null";
        StringBuilder sb = new StringBuilder();
        append(sb, scene, "mLaunchList", "触发");
        append(sb, scene, "mActions", "动作");
        append(sb, scene, "mConditionDevice", "条件");
        return sb.length() == 0 ? scene.getClass().getSimpleName() : sb.toString();
    }

    private static void append(StringBuilder sb, Object scene, String field, String label) {
        Object v = HookUtil.readFieldQuiet(scene, field);
        if (!(v instanceof List)) return;
        List<?> list = (List<?>) v;
        if (sb.length() > 0) sb.append(' ');
        sb.append(label).append('=').append(list.size());
    }
}
