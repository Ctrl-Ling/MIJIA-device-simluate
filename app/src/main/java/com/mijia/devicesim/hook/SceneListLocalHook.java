/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 20:38:14 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.lang.reflect.Constructor;
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
 * 让「只在本地成立」的自动化出现在米家「智能」tab 列表里（623 重写版，v134）。
 *
 * <p>大白话：v134 的 {@link SceneCreateLocalHook} 把假设备的保存请求就地判成功了，
 * 米家自己只把这条场景放内存（{@code SceneManagerV2} 的 map），重启就没了；
 * 而「智能」tab 的列表数据整条都从云端 JSON 解析出来 —— 云端不知道这条场景，
 * 列表里永远看不到它。
 *
 * <p>623 的列表模型和旧版完全换了：行模型是 {@code _m_j.qjq}（extends {@code _m_j.u6r}），
 * 解析入口是 {@code _m_j.qjq$OooO00o#OooO0Oo(JSONArray, Home, String type, Map)}，
 * 旧版挂的 {@code _m_j.zt6}/{@code _m_j.vfe}/{@code specscene.manager.OooOOO} 这套已经不存在。
 *
 * <p>处理：挂在解析器返回之后，只在 {@code type == "automation"} 那一路动手，
 * 把 {@link LocalSceneStore} 里同家庭、云端结果里没有的场景补成行：
 * <b>克隆一条已经解析好的真行（模板），把基类字段逐个换成我们的 id / 名字 / 启用状态，
 * 三个列表换成新的空 ArrayList</b>，再追加到返回列表。
 *
 * <p>为什么用克隆而不是自己造 JSON：解析器要 {@code Long.parseLong(template_id / last_edit_time)}，
 * 手写的半截 JSON 一崩就是整页列表挂掉；而 {@code qjq} 的构造函数是
 * {@code (Z, Boolean, List, List, List, Z)}，参数语义只在对云端字段时才知道，
 * 克隆真行最不容易错，字段类型也天然对齐。
 *
 * <p>没解析到任何真行（模板还不存在）时只打日志不硬造：宁可这一轮不显示，也不能把列表搞崩。
 */
public final class SceneListLocalHook {

    private static final String GROUP = "scene-list-local";

    /** 623 云端场景列表解析器的内部类（实例方法，返回 List&lt;qjq&gt;）。*/
    private static final String CL_PARSER = "_m_j.qjq$OooO00o";
    private static final String M_PARSE = "OooO0Oo";
    /** Home 类（解析器第二个参数的类型）。*/
    private static final String CL_HOME = "com.xiaomi.smarthome.homeroom.model.Home";

    /* ---------- u6r 基类公共字段 ---------- */
    /** 场景 id。*/
    private static final String F_SCENE_ID = "o0OOo0Oo";
    /** 场景名。*/
    private static final String F_SCENE_NAME = "o0OOo0oo";
    /** 是否启用。*/
    private static final String F_ENABLE = "o0OOo0oO";
    /** 所属家庭 id。*/
    private static final String F_HOME_ID = "o0OOoO";
    /** 最后编辑时间（毫秒）。*/
    private static final String F_EDIT_TIME = "o0OOo0OO";
    /** 房间 id；空串表示未分房间。*/
    private static final String F_ROOM_ID = "o0OOoOO";

    /* ---------- qjq 自己的三个集合：ViewModel 补全时会直接 .clear()，不能是 null ---------- */
    private static final String F_LIST_A = "o0OOoo0";
    private static final String F_LIST_B = "o0OOoo0O";
    private static final String F_LIST_C = "o0OOoo0o";
    /** u6r 上的第四个集合，也换成新的，避免和模板共享。*/
    private static final String F_LIST_BASE = "o0OOoO0o";

    /** 最近一次解析到的真行，用来当克隆模板（必须已完整解析：字段/集合都齐）。*/
    private static volatile Object sTemplate;

    private SceneListLocalHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        int n = HookSniper.hook(lpparam.classLoader, GROUP, CL_PARSER, new String[]{M_PARSE},
                HookSniper.Shape.of("List", "JSONArray", "Home", "String", "Map"), AFTER_PARSE);
        if (n > 0) {
            HookReport.ok(GROUP, "install", "智能列表注入已挂上（623 qjq 解析器）");
        } else {
            HookReport.skip(GROUP, "install", "解析器还没加载或已改名，交给侦察兵");
        }
    }

    private static final XC_MethodHook AFTER_PARSE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
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
        Object result = param.getResult();
        List<Object> list;
        if (result instanceof List) {
            //noinspection unchecked
            list = (List<Object>) result;
        } else if (result == null) {
            list = new ArrayList<>();
            param.setResult(list);
        } else {
            return;
        }

        // 先把模板记下来：云端有真自动化时它最完整
        for (Object item : list) {
            if (item != null) {
                sTemplate = item;
                break;
            }
        }
        Object template = sTemplate;
        if (template == null) {
            List<LocalSceneStore.Record> records = LocalSceneStore.snapshot();
            if (!records.isEmpty()) {
                Log.d("智能列表注入跳过：这一路还没有真行当模板，本地场景 " + records.size() + " 条等下一轮");
            }
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

        List<LocalSceneStore.Record> records = LocalSceneStore.snapshot();
        int added = 0;
        int skippedHome = 0;
        for (LocalSceneStore.Record r : records) {
            if (r == null || r.id == null || r.id.isEmpty() || seen.contains(r.id)) continue;
            if (!homeId.isEmpty() && !r.homeId.isEmpty() && !homeId.equals(r.homeId)) {
                skippedHome++;
                continue;
            }
            Object row = cloneRow(template, r);
            if (row == null) continue;
            if (addRow(param, list, row)) {
                seen.add(r.id);
                added++;
                Log.d("智能列表注入一条: id=" + r.id + " 名字=" + r.name + " 家庭="
                        + (r.homeId.isEmpty() ? homeId : r.homeId));
            }
        }

        if (!records.isEmpty() || added > 0) {
            Log.d("智能列表注入统计: home=" + homeId + " 本地场景=" + records.size()
                    + " 已有=" + seen.size() + " 家庭不符=" + skippedHome + " 注入=" + added);
        }
    }

    /** 先试着直接 add；列表不可变时复制一份再 add，最后把结果塞回解析器。*/
    private static boolean addRow(XC_MethodHook.MethodHookParam param, List<Object> list, Object row) {
        try {
            list.add(row);
            return true;
        } catch (Throwable ignored) {
            // 云端结果可能是 unmodifiableList，复制一份
        }
        try {
            List<Object> copy = new ArrayList<>(list);
            copy.add(row);
            param.setResult(copy);
            return true;
        } catch (Throwable t) {
            Log.v("列表追加失败: " + HookUtil.shortMsg(t));
            return false;
        }
    }

    /**
     * 克隆模板行 → 我们的行。
     *
     * <p>构造用 {@code (Z, Boolean, List, List, List, Z)}（qjq 唯一的公开构造函数），
     * 三个集合先给新 ArrayList；然后逐层复制模板的全部实例字段，最后覆盖基类那几个
     * 「身份字段」。这样不管 qjq 以后再加什么展示用字段，克隆都能带上模板的安全默认值。
     */
    private static Object cloneRow(Object template, LocalSceneStore.Record record) {
        try {
            Class<?> cls = template.getClass();
            Constructor<?> ctor = cls.getDeclaredConstructor(boolean.class, Boolean.class,
                    List.class, List.class, List.class, boolean.class);
            ctor.setAccessible(true);
            Object row = ctor.newInstance(true, Boolean.TRUE,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), true);

            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
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
                        f.set(row, f.get(template));
                    } catch (Throwable ignored) {
                        // 单个字段搬失败不影响整条
                    }
                }
            }

            // 集合换成新的：模板的列表属于另一条自动化，共享出去会被 ViewModel 一起 clear
            HookUtil.setFieldQuiet(row, F_LIST_A, new ArrayList<>());
            HookUtil.setFieldQuiet(row, F_LIST_B, new ArrayList<>());
            HookUtil.setFieldQuiet(row, F_LIST_C, new ArrayList<>());
            HookUtil.setFieldQuiet(row, F_LIST_BASE, new ArrayList<>());

            // 身份字段换成我们的
            HookUtil.setFieldQuiet(row, F_SCENE_ID, record.id);
            HookUtil.setFieldQuiet(row, F_SCENE_NAME,
                    record.name == null || record.name.isEmpty() ? "模拟设备自动化" : record.name);
            HookUtil.setFieldQuiet(row, F_ENABLE, Boolean.TRUE);
            HookUtil.setFieldQuiet(row, F_EDIT_TIME, System.currentTimeMillis());
            Object tplHomeRaw = HookUtil.readFieldQuiet(template, F_HOME_ID);
            String tplHome = tplHomeRaw instanceof String ? (String) tplHomeRaw : "";
            HookUtil.setFieldQuiet(row, F_HOME_ID, record.homeId.isEmpty() ? tplHome : record.homeId);
            HookUtil.setFieldQuiet(row, F_ROOM_ID, "");
            return row;
        } catch (Throwable t) {
            Log.v("构造列表行失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    private static String sceneId(Object scene) {
        Object id = HookUtil.readFieldQuiet(scene, F_SCENE_ID);
        return id instanceof String ? (String) id : null;
    }
}