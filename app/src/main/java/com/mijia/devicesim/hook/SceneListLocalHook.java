/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-19 16:20:12 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import org.json.JSONArray;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 让「只存在本地」的自动化出现在米家「智能」tab 列表里（v112）。
 *
 * <p>大白话：v111 把假设备的场景保存请求在客户端就地吃了，编辑页正常关闭、
 * 米家也把这条自动化写进了自己的本地场景库；可「智能」tab 的列表数据是
 * 整条云端链路给的 —— {@code SceneDataRepository} 拿云端 JSON、按
 * {@code manual_scene_info_list / voice_scene_info_list / auto_scene_info_list}
 * 解析成 {@code cqd}，本地库只在后面参与「补全已有条目」和「对账删手动场景」，
 * 从不会把「云端没有、本地才有」的条目塞进列表。于是保存是成功的，列表里就是不见。
 *
 * <p>这条钩子挂在云端 JSON 的解析器（{@code _m_j.zt6#OooOO0o}，静态、非挂起）返回之后：
 * 只在解析「自动化」那一路（参数 {@code "automation"}）动手，把本地场景库里
 * 同属当前家庭、且云端结果里没有的自动场景（{@code _m_j.wld}）转成列表项
 * （{@code _m_j.vfe}）追加进去。之后 ViewModel 原有的「按 scene_id 回查本地库补全」
 * 照常跑，图标、触发条件、动作、房间、开关状态都由米家自己填。
 *
 * <p>为什么不在云端 JSON 上直接造一条：编辑器发出去的是请求格式
 * （{@code us_id / st_id / sr_id / setting{...}}），云端回的是列表格式
 * （{@code scene_id / first_trigger / scene_action}），两者字段对不上，
 * 硬塞请求 JSON 解析出来是一条空壳。直接用本地库里的 {@code wld} 对象最稳。
 */
public final class SceneListLocalHook {

    private static final String GROUP = "scene-list-local";

    /** 云端场景列表解析器（静态方法，返回 ArrayList&lt;vfe&gt;）。*/
    private static final String CL_PARSER = "_m_j.zt6";
    private static final String M_PARSE = "OooOO0o";
    /** 本地场景库单例（com.xiaomi.smarthome.specscene.manager.OooOOO）。*/
    private static final String CL_STORE = "com.xiaomi.smarthome.specscene.manager.OooOOO";
    private static final String M_STORE_INSTANCE = "OooOO0O";
    private static final String M_STORE_ALL = "OooO0o0";
    /** 场景基类 / 列表项 / 自动化场景。*/
    private static final String CL_FRE = "_m_j.fre";
    private static final String CL_VFE = "_m_j.vfe";
    private static final String CL_WLD = "_m_j.wld";
    /** Home 类（解析器第二个参数的类型）。*/
    private static final String CL_HOME = "com.xiaomi.smarthome.homeroom.model.Home";

    /** 场景 id（fre 的公共字段）。*/
    private static final String F_SCENE_ID = "OooOoO0";
    /** 场景名。*/
    private static final String F_SCENE_NAME = "OooOoo";
    /** 所属家庭 id（对账方法 OooO0O0 就是拿它和 homeId 做 equals）。*/
    private static final String F_HOME_ID = "Oooo00o";

    /** 列表项自己的三个集合：ViewModel 补全时会直接 .clear()，不能是 null。*/
    private static final String F_TRIGGERS = "Oooo0o";
    private static final String F_CONDITIONS = "Oooo0oO";
    private static final String F_ACTIONS = "Oooo0oo";
    private static final String F_FLAG = "Oooo0o0";

    private static Class<?> sVfeClass;
    private static Class<?> sFreClass;
    private static Class<?> sWldClass;

    private SceneListLocalHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        Class<?> parser = HookUtil.findClassQuiet(CL_PARSER, cl);
        Class<?> home = HookUtil.findClassQuiet(CL_HOME, cl);
        sFreClass = HookUtil.findClassQuiet(CL_FRE, cl);
        sVfeClass = HookUtil.findClassQuiet(CL_VFE, cl);
        sWldClass = HookUtil.findClassQuiet(CL_WLD, cl);

        boolean hooked = false;
        if (parser != null && home != null) {
            hooked = HookUtil.hookMethod(GROUP, parser, M_PARSE, AFTER_PARSE,
                    JSONArray.class, home, String.class, Map.class);
        }
        if (hooked) {
            HookReport.ok(GROUP, "install", "智能列表注入已挂上");
        } else {
            HookReport.fail(GROUP, "install", "解析器没挂上（zt6="
                    + (parser != null) + " home=" + (home != null) + "）");
        }
    }

    private static final XC_MethodHook AFTER_PARSE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
            try {
                Object[] args = param.args;
                if (args == null || args.length != 4) return;
                // 只处理自动化那一路；手动（userClick）/语音（voice）不动
                if (!"automation".equals(args[2])) return;
                inject(param, args[1]);
            } catch (Throwable t) {
                Log.e("智能列表注入异常", t);
            }
        }
    };

    private static void inject(XC_MethodHook.MethodHookParam param, Object home) {
        if (sFreClass == null || sVfeClass == null || sWldClass == null) return;

        Object result = param.getResult();
        List<Object> list;
        if (result instanceof List) {
            //noinspection unchecked
            list = (List<Object>) result;
        } else if (result == null) {
            // 云端这条列表压根没有：自己造一个交回去，不然想加也没地方加
            list = new ArrayList<>();
            param.setResult(list);
        } else {
            return;
        }

        String homeId = "";
        if (home != null) {
            try {
                Object id = XposedHelpers.callMethod(home, "getId");
                if (id instanceof String) homeId = (String) id;
            } catch (Throwable ignored) {
                // 拿不到 home id 就按「不筛家庭」处理
            }
        }

        Set<String> seen = new HashSet<>();
        for (Object item : list) {
            String id = sceneId(item);
            if (id != null && !id.isEmpty()) seen.add(id);
        }

        Class<?> storeClass = HookUtil.findClassQuiet(CL_STORE, sFreClass.getClassLoader());
        if (storeClass == null) return;
        Object store = HookUtil.invokeQuiet(null, storeClass, M_STORE_INSTANCE, new Class<?>[0]);
        if (store == null) return;
        Object all = HookUtil.invokeQuiet(store, storeClass, M_STORE_ALL, new Class<?>[0]);
        if (!(all instanceof Iterable)) return;

        int local = 0;
        int sameHome = 0;
        int added = 0;
        for (Object scene : (Iterable<?>) all) {
            local++;
            if (scene == null || !sWldClass.isInstance(scene)) continue;
            Object hid = HookUtil.readFieldQuiet(scene, F_HOME_ID);
            if (!homeId.isEmpty() && !homeId.equals(hid)) continue;
            sameHome++;
            String id = sceneId(scene);
            if (id == null || id.isEmpty() || "0".equals(id) || seen.contains(id)) continue;
            Object vfe = buildListItem(scene);
            if (vfe == null) continue;
            list.add(vfe);
            seen.add(id);
            added++;
            Log.d("智能列表注入一条: id=" + id + " 名字=" + HookUtil.readFieldQuiet(scene, F_SCENE_NAME)
                    + " 家庭=" + hid);
        }

        Log.d("智能列表注入统计: home=" + homeId + " 本地场景=" + local
                + " 同家庭自动化=" + sameHome + " 已有=" + seen.size() + " 注入=" + added);
    }

    /** 本地 {@code wld} → 列表项 {@code vfe}：公共字段逐个搬，三个集合给空 ArrayList。*/
    private static Object buildListItem(Object scene) {
        try {
            Object vfe = XposedHelpers.newInstance(sVfeClass);
            for (Field f : sFreClass.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.set(vfe, f.get(scene));
                } catch (Throwable ignored) {
                    // 单个字段搬失败不影响整条
                }
            }
            HookUtil.setFieldQuiet(vfe, F_TRIGGERS, new ArrayList<>());
            HookUtil.setFieldQuiet(vfe, F_CONDITIONS, new ArrayList<>());
            HookUtil.setFieldQuiet(vfe, F_ACTIONS, new ArrayList<>());
            HookUtil.setFieldQuiet(vfe, F_FLAG, Boolean.TRUE);
            return vfe;
        } catch (Throwable t) {
            Log.v("构造列表项失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    private static String sceneId(Object scene) {
        Object id = HookUtil.readFieldQuiet(scene, F_SCENE_ID);
        return id instanceof String ? (String) id : null;
    }
}