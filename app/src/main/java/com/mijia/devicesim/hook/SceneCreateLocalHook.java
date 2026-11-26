/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-26 14:52:21 +08:00
 */
package com.mijia.devicesim.hook;

import android.os.Handler;
import android.os.Looper;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 11.10.623 的「创建自动化 → 保存失败」就地成功（v134）。
 *
 * <p>大白话：623 的保存链已经换代，v93/v111 挂的 {@code _m_j.dpd} / {@code _m_j.o29} 在
 * 这版里压根不存在（v133 日志零命中），所以点「创建」每次都被云端原样拒绝：
 * 假设备 did 不在云端家庭的设备表里，服务端直接判 home_id invalid，客户端把回调翻成
 * toast「保存失败」。
 *
 * <p>623 真正两条出口（都从 {@code SceneOperationManager.OoooOoO} 分出去）：
 * <ol>
 *   <li><b>新建 / v1→v2 转换</b>：{@code _m_j.t5n.OooOooo(Context, String, c61)} ——
 *       拿着 {@code sbr.OooOOOo().toString()} 去 POST 转换接口，回包交给
 *       {@code SceneOperationManager$OooOO0#onSuccess(JSONObject)}；失败走
 *       {@code #onFailure(cc9)}，最后 {@code o00OOooo} 弹「保存失败」。</li>
 *   <li><b>编辑已有场景</b>：{@code SceneOperationManager.Oooo000/OooOOOo} 拼出
 *       {@code SceneInfo}，再交给 {@code _m_j.x0p#OooO0OO(SceneInfo, Callback)}
 *       发云端；失败走 {@code SceneOperationManager$OooO#onFailure(int,String)}
 *       （固定 toast R.string.wi0 =「保存失败」）。</li>
 * </ol>
 *
 * <p>处理：两条出口都在发请求之前拦一次 —— 只要这一发场景里出现启用中的模拟 did，
 * 就把请求吃掉，按米家的成功回包格式直接调它自己的成功回调：
 * <ul>
 *   <li>新建：合成 {@code {"scene_id": <本地 id>}}，<b>故意不带 activated、不带
 *       trigger_order</b>。不带 activated 走 {@code onOk}（页面成功收尾 + finish），
 *       带了反而会弹「是否立即执行」对话框再去 POST ExecuteScene。</li>
 *   <li>编辑：直接把原 {@code SceneInfo} 回给 {@code onSuccess}，页面按「保存好了」收尾。</li>
 * </ul>
 *
 * <p>同时把「我们替云端认下来的场景」登记进 {@link LocalSceneStore}，重启后
 * {@link SceneListLocalHook} 还能把它补回「智能」tab。
 *
 * <p>真设备（场景里没有模拟 did）一条都不碰。
 *
 * <p><b>v156（11-26 现场：v154/v155 之后保存又弹「保存失败」）</b>：t5n/x0p 那两刀只挡住
 * 「请求发出去」这一段，前面还有一道 license 闸（{@code SceneOperationManager.Oooo} 先查
 * {@code show_scene_user_lincense} 偏好，再 {@code DeviceApi.getUserLicenseConfig} 走网络，
 * 回调失败时直接 toast R.string.wi0=「保存失败」，根本走不到 t5n/x0p）。这里把闸提到
 * 保存总入口 {@code Oooo(WeakReference, sbr, editType, success, fail, int)}：只要场景里
 * 出现<b>配置表里真实存在的模拟 did</b>，就先补 scene_id、登记 LocalSceneStore、把 success
 * 回调 post 到主线程，然后整条链（license → 转 JSON → 云端）全部跳过。真实场景一格不碰。
 */
public final class SceneCreateLocalHook {

    private static final String GROUP = "scene-create-local";

    /** 新建 / 转换请求的唯一出口（静态）。*/
    private static final String CL_CONVERT = "_m_j.t5n";
    private static final String M_CONVERT = "OooOooo";
    /** 编辑已有场景的保存出口（单例实例方法）。*/
    private static final String CL_SAVE = "_m_j.x0p";
    private static final String M_SAVE = "OooO0OO";
    /** 新建回调（c61 子类）：成功方法 onSuccess(JSONObject)，失败 onFailure(cc9)。*/
    private static final String CL_CREATE_CB = "com.xiaomi.smarthome.specscene.SceneOperationManager$OooOO0";
    /** 编辑回调：成功 onSuccess(Object)（实际类型是 SceneInfo），失败 onFailure(int,String)。*/
    private static final String CL_EDIT_CB = "com.xiaomi.smarthome.specscene.SceneOperationManager$OooO";

    /** 新建回调里存着的 {@code sbr}（场景编辑模型，名字 / 家庭都从这取）。*/
    private static final String F_CREATE_SBR = "OooO0O0";

    /**
     * 623 场景保存的总入口：{@code Oooo(WeakReference,sbr,editType,ewa,ewa,int)}。
     * <p>license 查询、v1→v2 转换、云端保存全在它下面分叉；在它前面短路，前面那些闸
     * （含 toast「保存失败」的两处回调）就都走不到。
     */
    private static final String CL_SAVE_ENTRY = "com.xiaomi.smarthome.specscene.SceneOperationManager";
    private static final String M_SAVE_ENTRY = "Oooo";

    /** sbr（及其父类 u6r）里的场景字段。*/
    private static final String F_SCENE_ID = "o0OOo0Oo";
    private static final String F_SCENE_NAME = "o0OOo0oo";
    private static final String F_SCENE_HOME = "o0OOoO";
    /** 触发 / 条件 / 动作三个列表。*/
    private static final String[] F_SCENE_LISTS = {"o0OOooO", "o0OOoo0o", "o0OOooOo"};

    /** 兜底识别：JSON 里成对引号包着的保留段 did（9 开头共 10 位）。*/
    private static final Pattern SIM_DID_IN_JSON = Pattern.compile("\"(9\\d{9})\"");

    private SceneCreateLocalHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;

        // v156：保存总入口短路（license 闸之前），命中后 t5n/x0p 根本不会被调到
        int entry = HookSniper.hook(cl, GROUP, CL_SAVE_ENTRY, new String[]{M_SAVE_ENTRY},
                HookSniper.Shape.of(null, "WeakReference", null, null, null, null, "int"),
                "[oO0]{3,6}", BEFORE_SAVE_ENTRY);
        int converted = HookSniper.hook(cl, GROUP, CL_CONVERT, new String[]{M_CONVERT},
                HookSniper.Shape.of(null, "Context", "String", null), BEFORE_CONVERT);
        int saved = HookSniper.hook(cl, GROUP, CL_SAVE, new String[]{M_SAVE},
                HookSniper.Shape.of(null, "SceneInfo", "Callback"), BEFORE_SAVE);

        // 纯观测：失败分支打原样错误码/文案，方便升级后第一时间知道是哪一段拒绝的
        HookSniper.hook(cl, GROUP, CL_CREATE_CB, new String[]{"onFailure"},
                HookSniper.Shape.anyArgs(null), AFTER_CREATE_FAIL);
        HookSniper.hook(cl, GROUP, CL_EDIT_CB, new String[]{"onFailure"},
                HookSniper.Shape.of(null, "int", "String"), AFTER_EDIT_FAIL);

        if (entry > 0 || converted > 0 || saved > 0) {
            HookReport.ok(GROUP, "install", "场景保存接管：入口=" + entry + " 新建=" + converted
                    + " 编辑=" + saved);
        } else {
            HookReport.skip(GROUP, "install", "保存出口没挂上（类未加载或已改名）");
        }
    }

    /* ---------- 0. 保存总入口（license 闸之前） ---------- */

    /**
     * {@code SceneOperationManager.Oooo(WeakReference, sbr, editType, success, fail, int)}
     * 之前：场景里有模拟设备就地成功，license 查询 / JSON 转换 / 云端请求一律不发。
     *
     * <p>为什么提到这里：t5n/x0p 只挡「请求」这一层，而 license 闸 {@code getUserLicenseConfig}
     * 的网络回调失败时，{@code $OooO00o.onFailure} 会先调 fail 回调再 toast「保存失败」——
     * 页面根本走不到 t5n。每天第一次保存、网络抖一下、隐私版本对不上，都会在这里断。
     *
     * <p>只认配置表里<b>真实存在</b>的 did（精确相等），不做 9 开头正则兜底：普通数字 id
     * 也是 9 开头十位数，正则兜底会误伤真实场景。
     */
    private static final XC_MethodHook BEFORE_SAVE_ENTRY = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                if (a == null || a.length != 6) return;
                Object sbr = a[1];
                Object success = a[3];
                if (sbr == null || success == null) return;

                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;

                String did = findConfiguredSimDid(sbr, cfg);
                if (did == null) return;

                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) {
                    Log.d("场景保存跳过：did=" + did + " 不在启用列表（保持原流程）");
                    return;
                }

                Method invoke = findInvokeMethod(success);
                if (invoke == null) {
                    Log.w("场景保存接管失败：成功回调没有 invoke(Object)，放行原流程 回调="
                            + success.getClass().getName());
                    return;
                }

                String id = readString(sbr, F_SCENE_ID);
                boolean creating = id == null || id.isEmpty() || "0".equals(id);
                if (creating) id = LocalSceneStore.newSceneId();
                if (!HookUtil.setFieldQuiet(sbr, F_SCENE_ID, id)) {
                    Log.w("场景保存接管失败：写不回 scene_id=" + id);
                    return;
                }
                String name = readString(sbr, F_SCENE_NAME);
                String home = readString(sbr, F_SCENE_HOME);

                param.setResult(null); // license / 转换 / 云端保存全部不再发
                postInvoke(invoke, success, sbr);
                LocalSceneStore.add(id, name, home);
                Log.d("场景保存就地成功(" + (creating ? "新建" : "编辑") + "): did=" + did
                        + " id=" + id + " name=" + name + " home=" + home);
            } catch (Throwable t) {
                Log.e("场景保存入口接管异常", t);
            }
        }
    };

    /* ---------- 1. 新建 / 转换 ---------- */

    private static final XC_MethodHook BEFORE_CONVERT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                if (a == null || a.length != 3) return;
                if (!(a[1] instanceof String)) return;
                String sceneJson = (String) a[1];
                String did = matchSimDid(sceneJson);
                if (did == null) return;
                Object cb = a[2];
                if (cb == null) return;

                String id = pickSceneId(sceneJson);
                String name = "";
                String home = "";
                Object sbr = HookUtil.readFieldQuiet(cb, F_CREATE_SBR);
                if (sbr != null) {
                    Object n = HookUtil.readFieldQuiet(sbr, "o0OOo0oo");
                    if (n instanceof String) name = (String) n;
                    Object h = HookUtil.readFieldQuiet(sbr, "o0OOoO");
                    if (h instanceof String) home = (String) h;
                }
                if (name.isEmpty()) name = optString(sceneJson, "name");
                if (home.isEmpty()) home = optString(sceneJson, "home_id");

                JSONObject resp = new JSONObject();
                resp.put("scene_id", id);
                // 不带 activated / trigger_order：走 onOk → o00OOooO → 埋点 + finish，
                // 带了 activated 会再弹「是否立即执行」并去 POST ExecuteScene（假设备执行没有意义）
                Method onSuccess = findSuccessMethod(cb, JSONObject.class);
                if (onSuccess == null) {
                    Log.w("场景新建接管失败：回调没有 onSuccess(JSONObject)，放行原逻辑 回调="
                            + cb.getClass().getName());
                    return;
                }
                param.setResult(null); // 请求不再发出去：云端永远判 home_id invalid
                postInvoke(onSuccess, cb, resp);
                LocalSceneStore.add(id, name, home);
                Log.d("场景新建就地成功: did=" + did + " id=" + id + " name=" + name + " home=" + home);
            } catch (Throwable t) {
                Log.e("场景新建接管异常", t);
            }
        }
    };

    /* ---------- 2. 编辑已有场景 ---------- */

    private static final XC_MethodHook BEFORE_SAVE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                if (a == null || a.length != 2) return;
                Object sceneInfo = a[0];
                Object cb = a[1];
                if (sceneInfo == null || cb == null) return;
                String did = findSimDidIn(sceneInfo);
                if (did == null) return;

                Method onSuccess = findSuccessMethod(cb, sceneInfo.getClass());
                if (onSuccess == null) {
                    Log.w("场景编辑接管失败：回调没有 onSuccess，放行原逻辑 回调="
                            + cb.getClass().getName());
                    return;
                }
                param.setResult(null);
                postInvoke(onSuccess, cb, sceneInfo);

                String id = readString(sceneInfo, "mSceneIdV2");
                if (id == null || id.isEmpty() || "0".equals(id)) {
                    Object legacy = HookUtil.readFieldQuiet(sceneInfo, "mSceneId");
                    if (legacy instanceof Number) id = String.valueOf(((Number) legacy).intValue());
                }
                String name = readString(sceneInfo, "mName");
                LocalSceneStore.add(id, name, "");
                Log.d("场景编辑就地成功: did=" + did + " id=" + id + " name=" + name);
            } catch (Throwable t) {
                Log.e("场景编辑接管异常", t);
            }
        }
    };

    /* ---------- 3. 失败分支观测 ---------- */

    private static final XC_MethodHook AFTER_CREATE_FAIL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object err = param.args == null || param.args.length < 1 ? null : param.args[0];
                Log.d("场景新建失败回调: " + describeError(err));
            } catch (Throwable t) {
                Log.v("新建失败回调打点异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    private static final XC_MethodHook AFTER_EDIT_FAIL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object[] a = param.args;
                String code = a != null && a.length > 0 ? String.valueOf(a[0]) : "?";
                String msg = a != null && a.length > 1 ? String.valueOf(a[1]) : "";
                Log.d("场景编辑失败回调: code=" + code + " msg=" + msg);
            } catch (Throwable t) {
                Log.v("编辑失败回调打点异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 工具 ---------- */

    /** 在回调类（含父类）里找单参数的 onSuccess：先找精确参数类型，再退可赋值类型。*/
    private static Method findSuccessMethod(Object callback, Class<?> argType) {
        Class<?> target = argType;
        for (Class<?> c = callback.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"onSuccess".equals(m.getName()) || m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] == target) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        for (Class<?> c = callback.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"onSuccess".equals(m.getName()) || m.getParameterCount() != 1) continue;
                Class<?> p = m.getParameterTypes()[0];
                if (p.isAssignableFrom(target)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 永远排到主线程队列再调：保证 before 钩子先 setResult，页面状态机再收成功。*/
    private static void postInvoke(final Method m, final Object receiver, final Object arg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    m.invoke(receiver, arg);
                } catch (Throwable t) {
                    Log.e("场景成功回调调用失败 " + m, t);
                }
            }
        });
    }

    /** 场景 JSON 里出现的第一台模拟设备 did；没有就是真实场景，一律放行。*/
    private static String matchSimDid(String sceneJson) {
        if (sceneJson == null || sceneJson.isEmpty()) return null;
        SimConfig cfg = ConfigRepository.current();
        if (cfg != null) {
            for (SimDevice d : cfg.devices) {
                if (d == null || d.did == null || d.did.isEmpty()) continue;
                if (sceneJson.contains("\"" + d.did + "\"")) return d.did;
            }
        }
        Matcher m = SIM_DID_IN_JSON.matcher(sceneJson);
        if (m.find()) {
            String did = m.group(1);
            if (Consts.isSimulatedDid(did)) return did;
        }
        return null;
    }

    /**
     * 场景里是否含着「配置表里真实存在」的模拟 did。
     *
     * <p>先按 sbr 的三个列表（触发 / 条件 / 动作）各扫一遍，再整体兜一遍；只做精确相等
     * （或 JSON 引号包裹的精确相等），不用 9 开头正则 —— 普通场景里的数字 id 也是 9 开头
     * 十位数，正则兜底会把真实场景一起吃掉。
     */
    private static String findConfiguredSimDid(Object sbr, SimConfig cfg) {
        if (sbr == null || cfg == null || cfg.devices == null || cfg.devices.isEmpty()) return null;
        for (String f : F_SCENE_LISTS) {
            Object list = HookUtil.readFieldQuiet(sbr, f);
            if (list == null) continue;
            List<String> strings = new ArrayList<>();
            collectStrings(list, 0, new IdentityHashMap<Object, Boolean>(), strings);
            String hit = matchConfigured(cfg, strings);
            if (hit != null) return hit;
        }
        List<String> all = new ArrayList<>();
        collectStrings(sbr, 0, new IdentityHashMap<Object, Boolean>(), all);
        return matchConfigured(cfg, all);
    }

    private static String matchConfigured(SimConfig cfg, List<String> strings) {
        for (String s : strings) {
            if (s == null || s.isEmpty()) continue;
            for (SimDevice d : cfg.devices) {
                if (d == null || d.did == null || d.did.isEmpty()) continue;
                if (s.equals(d.did) || s.contains("\"" + d.did + "\"")) return d.did;
            }
        }
        return null;
    }

    /** 找 Kotlin 函数对象的 invoke(Object)（{@code _m_j.ewa} / Function1）。*/
    private static Method findInvokeMethod(Object f) {
        for (Class<?> c = f.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : methods) {
                if ("invoke".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 在任意对象图里（字符串字段 / List / Map，深度有限）找模拟 did。*/
    private static String findSimDidIn(Object root) {
        List<String> strings = new ArrayList<>();
        collectStrings(root, 0, new IdentityHashMap<Object, Boolean>(), strings);
        SimConfig cfg = ConfigRepository.current();
        for (String s : strings) {
            if (s == null || s.isEmpty()) continue;
            if (cfg != null) {
                for (SimDevice d : cfg.devices) {
                    if (d == null || d.did == null || d.did.isEmpty()) continue;
                    if (s.equals(d.did) || s.contains("\"" + d.did + "\"")) return d.did;
                }
            }
            Matcher m = SIM_DID_IN_JSON.matcher(s);
            if (m.find()) {
                String did = m.group(1);
                if (Consts.isSimulatedDid(did)) return did;
            }
        }
        return null;
    }

    private static void collectStrings(Object o, int depth, IdentityHashMap<Object, Boolean> seen,
                                       List<String> out) {
        if (o == null || depth > 4 || out.size() > 400) return;
        if (o instanceof String) {
            out.add((String) o);
            return;
        }
        if (o instanceof CharSequence) {
            out.add(o.toString());
            return;
        }
        if (o instanceof Number || o instanceof Boolean) return;
        if (seen.put(o, Boolean.TRUE) != null) return;
        if (o instanceof Iterable) {
            for (Object e : (Iterable<?>) o) collectStrings(e, depth + 1, seen, out);
            return;
        }
        if (o instanceof Map) {
            for (Object e : ((Map<?, ?>) o).values()) collectStrings(e, depth + 1, seen, out);
            return;
        }
        if (o instanceof JSONObject) return; // 原样 JSON 太大，交给字段里的 String 覆盖
        if (o.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < n && i < 64; i++) {
                collectStrings(java.lang.reflect.Array.get(o, i), depth + 1, seen, out);
            }
            return;
        }
        String cn = o.getClass().getName();
        if (cn.startsWith("android.") || cn.startsWith("java.")) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    collectStrings(f.get(o), depth + 1, seen, out);
                } catch (Throwable ignored) {
                    // 单个字段取不到不影响整体判定
                }
            }
        }
    }

    /** 编辑已有场景沿用原 id；新建给一个稳定的本地 id。*/
    private static String pickSceneId(String sceneJson) {
        String id = optString(sceneJson, "scene_id");
        if (id != null && !id.isEmpty() && !"0".equals(id)) return id;
        return LocalSceneStore.newSceneId();
    }

    private static String optString(String json, String key) {
        if (json == null) return "";
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readString(Object target, String field) {
        Object v = HookUtil.readFieldQuiet(target, field);
        return v instanceof String ? (String) v : "";
    }

    private static String describeError(Object err) {
        if (err == null) return "null";
        String code = "";
        String msg = "";
        try {
            Object c = HookUtil.invokeQuiet(err, err.getClass(), "getCode", new Class<?>[0]);
            if (c != null) code = String.valueOf(c);
        } catch (Throwable ignored) {
        }
        try {
            Object m = HookUtil.invokeQuiet(err, err.getClass(), "getMessage", new Class<?>[0]);
            if (m != null) msg = String.valueOf(m);
        } catch (Throwable ignored) {
        }
        if (code.isEmpty() && msg.isEmpty()) return err.toString();
        return "code=" + code + " msg=" + msg;
    }
}