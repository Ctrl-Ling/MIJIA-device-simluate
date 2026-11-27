/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-27 16:46:12 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 智能 tab 长按「本地自动化」也弹出重命名 / 复制 / 删除三个按钮（623 版）。
 *
 * <p>大白话：真自动化的长按菜单是米家自己算出来的 —— 选择态刷新方法
 * {@code MySceneFragmentkt$OooO0O0#OooO0o0()V} 先按选中 id 去
 * {@code SceneManagerV2.OoooOOO(id)} 捞场景对象，再按对象上的能力位决定三个按钮显不显示。
 * 我们补出来的 #行只有 id 在 {@link LocalSceneStore} 里，云端/内存里都查不到对象，
 * 所有「对象存在才显示」的分支一律判 false，于是整条底栏三个按钮全没了（标题还显示「已选择1项」）。
 *
 * <p>处理：在选择态刷新方法返回之后兜一层 —— 当前恰好选中一条本地场景时，
 * 把底栏和三个按钮强制可见、透明度和位移复位，并把三个按钮的点击换成「本地版」：
 * <ul>
 *   <li>重命名 → 只改 {@link LocalSceneStore} + 当前行名字；</li>
 *   <li>复制 → 复制登记表记录，并克隆当前行插到它后面；</li>
 *   <li>删除 → 从登记表和列表里同时移除。</li>
 * </ul>
 * 两个列表操作都直接改适配器自己的数据表，再让列表刷新，不走云端；改完调用米家自己的
 * {@code o000Oo0()} 退出选择态（真删除成功后走的也是它，见 o000o0oO 的反编译）。
 *
 * <p>点击监听是「包装」不是「替换」：原来的监听器留着，选中的是真场景时原样转发，
 * 所以真实自动化的重命名/复制/删除行为一点不动。
 */
public final class SceneMenuLocalHook {

    private static final String GROUP = "scene-menu-local";

    /** 选择态刷新方法所在的内部分类（c5p 回调实现）。*/
    private static final String CL_INNER = "com.xiaomi.smarthome.scene.ui.list.MySceneFragmentkt$OooO0O0";
    private static final String M_SELECT_UI = "OooO0o0";
    /** 底栏按钮的 R 字段容器（只用来按资源名兜底找按钮；重命名的 R 字段实测是桌面快捷，别再按字段猜）。*/

    /* ---------- MySceneFragmentkt 字段 ---------- */
    private static final String F_INNER_FRAG = "OooO0O0";
    private static final String F_ADAPTER_UC = "o0OOoOo";
    private static final String F_ADAPTER_VC = "o0OOoOo0";
    private static final String F_ADAPTER_AUTO = "o0OOoo0";
    private static final String F_DELEGATE = "o0OOoO0O";
    private static final String F_MENU_BAR = "o0OOoo";

    /* ---------- 两个适配器的选中表 / 数据表 ---------- */
    private static final String F_UC_SELECT = "o0OOoO0o";
    private static final String F_UC_LIST = "o0OOoO0";
    private static final String F_NEW_SELECT = "o0OOoO0O";
    private static final String F_NEW_LIST = "o0OOoO00";

    /* ---------- qjq 行字段 ---------- */
    private static final String F_ROW_ID = "o0OOo0Oo";
    private static final String F_ROW_NAME = "o0OOo0oo";

    /* ---------- 底栏按钮：按文案找，混淆名/R 字段换版本就废，中文文案是死在 layout 里的 ---------- */
    private static final String T_RENAME = "重命名";
    private static final String T_COPY = "复制";
    private static final String T_DELETE = "删除";
    private static final String T_SHORTCUT = "桌面快捷";
    /** 资源名兜底（623 实测：wy=重命名 wt=复制 wu=删除 ws=桌面快捷）。*/
    private static final String[] N_RENAME = {"wy"};
    private static final String[] N_COPY = {"wt"};
    private static final String[] N_DELETE = {"wu"};
    private static final String[] N_SHORTCUT = {"ws"};

    /** 米家自己的「退出选择态」（清适配器选中表 + 底栏动画收起 + 列表刷新）。*/
    private static final String M_EXIT_SELECT = "o000Oo0";

    private static final int KIND_RENAME = 1;
    private static final int KIND_COPY = 2;
    private static final int KIND_DELETE = 3;

    private SceneMenuLocalHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        int n = HookSniper.hook(lpparam.classLoader, GROUP, CL_INNER,
                new String[]{M_SELECT_UI}, HookSniper.Shape.noArgs("void"), AFTER_SELECT_UI);
        Log.d("scene-menu-local install -> " + n);
        if (n > 0) {
            HookReport.ok(GROUP, "install", "本地自动化长按菜单已挂上（623 OooO0o0）");
        } else {
            HookReport.skip(GROUP, "install", "选择态刷新方法还没加载或已改名，交给侦察兵");
        }
    }

    private static final XC_MethodHook AFTER_SELECT_UI = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object frag = HookUtil.readFieldQuiet(param.thisObject, F_INNER_FRAG);
                Log.d("scene-menu-local fire, frag=" + (frag == null ? "null" : frag.getClass().getName()));
                if (frag == null) return;
                diagSelection(frag);
                Object row = singleLocalSelection(frag);
                if (row == null) return;
                forceMenu(frag);
            } catch (Throwable t) {
                Log.d("本地自动化菜单注入异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** 临时诊断：把每个适配器选中表的大小 / 键 / 值类型 / 行 id 全打出来。*/
    private static void diagSelection(Object frag) {
        for (Object adapter : adapters(frag)) {
            Map<?, ?> map = selectionMap(adapter);
            if (map == null) {
                Log.d("diag " + adapter.getClass().getName() + " map=null");
                continue;
            }
            StringBuilder sb = new StringBuilder("diag " + adapter.getClass().getName()
                    + " size=" + map.size());
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (i++ >= 4) break;
                Object v = e.getValue();
                sb.append(" | k=").append(e.getKey() == null ? "null" : e.getKey().toString())
                        .append(" kc=").append(e.getKey() == null ? "-" : e.getKey().getClass().getSimpleName())
                        .append(" vc=").append(v == null ? "null" : v.getClass().getSimpleName());
                if (v != null) {
                    Object id = HookUtil.readFieldQuiet(v, F_ROW_ID);
                    sb.append(" id=").append(id).append(" contains=").append(LocalSceneStore.contains(String.valueOf(id)));
                }
            }
            Log.d(sb.toString());
        }
    }

    /** 当前选中集合里恰好只有一条本地场景行时返回它，否则 null。*/
    private static Object singleLocalSelection(Object frag) {
        Object found = null;
        int count = 0;
        for (Object adapter : adapters(frag)) {
            Map<?, ?> map = selectionMap(adapter);
            if (map == null) continue;
            for (Object v : map.values()) {
                if (v == null) continue;
                count++;
                if (count > 1) return null;
                found = v;
            }
        }
        if (found == null) return null;
        String id = idOf(found);
        return LocalSceneStore.contains(id) ? found : null;
    }

    /** 强制底栏和三个按钮可见（按钮点击换成「本地优先」包装）。*/
    private static void forceMenu(Object frag) {
        Object barObj = HookUtil.readFieldQuiet(frag, F_MENU_BAR);
        if (!(barObj instanceof View)) {
            Log.d("forceMenu bar 不是 View: " + (barObj == null ? "null" : barObj.getClass().getName()));
            return;
        }
        View bar = (View) barObj;
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(1f);
        bar.setTranslationY(0f);
        Log.d("forceMenu bar vis=" + bar.getVisibility());

        // 本地场景米家自己顶出来的是「桌面快捷」，真实自动化没有它；藏着，对齐真实的三按钮
        View shortcut = findButton(bar, T_SHORTCUT, N_SHORTCUT);
        if (shortcut != null) {
            shortcut.setVisibility(View.GONE);
            Log.d("forceMenu 隐藏桌面快捷");
        }
        showButton(bar, findButton(bar, T_RENAME, N_RENAME), "重命名", KIND_RENAME, frag);
        showButton(bar, findButton(bar, T_COPY, N_COPY), "复制", KIND_COPY, frag);
        showButton(bar, findButton(bar, T_DELETE, N_DELETE), "删除", KIND_DELETE, frag);
    }

    private static void showButton(View bar, View btn, String label, int kind, Object frag) {
        if (btn == null) {
            Log.d("showButton 「" + label + "」没找到");
            return;
        }
        btn.setVisibility(View.VISIBLE);
        btn.setAlpha(1f);
        btn.setTranslationY(0f);
        btn.setEnabled(true);
        Log.d("showButton 「" + label + "」 vis=" + btn.getVisibility());
        View.OnClickListener cur = listenerOf(btn);
        if (cur instanceof LocalClick) return;
        btn.setOnClickListener(new LocalClick(frag, kind, cur));
    }

    /** 先按中文文案找，再按资源名兜底；都找不到返回 null。*/
    private static View findButton(View bar, String text, String[] names) {
        View v = findByText(bar, text);
        if (v != null) return v;
        for (String n : names) {
            try {
                int id = bar.getContext().getResources()
                        .getIdentifier(n, "id", "com.xiaomi.smarthome");
                if (id != 0) {
                    View hit = bar.findViewById(id);
                    if (hit != null) return hit;
                }
            } catch (Throwable ignored) {
                // 资源名换版本了，继续试下一个
            }
        }
        return null;
    }

    private static View findByText(View root, String text) {
        if (root == null) return null;
        if (root instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) root).getText();
            if (t != null && text.contentEquals(t)) return root;
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), text);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 读 View 当前挂着的点击监听（包装用；读不到就当没有）。*/
    private static View.OnClickListener listenerOf(View v) {
        try {
            Object info = XposedHelpers.getObjectField(v, "mListenerInfo");
            if (info == null) return null;
            Object l = XposedHelpers.getObjectField(info, "mOnClickListener");
            return l instanceof View.OnClickListener ? (View.OnClickListener) l : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /* ---------- 点击分派 ---------- */

    private static final class LocalClick implements View.OnClickListener {
        private final Object frag;
        private final int kind;
        private final View.OnClickListener orig;

        LocalClick(Object frag, int kind, View.OnClickListener orig) {
            this.frag = frag;
            this.kind = kind;
            this.orig = orig;
        }

        @Override
        public void onClick(View v) {
            try {
                Object row = singleLocalSelection(frag);
                String id = row == null ? null : idOf(row);
                LocalSceneStore.Record rec = id == null ? null : LocalSceneStore.get(id);
                if (row == null || rec == null) {
                    // 选的是真场景（或已经退出选择态）：原样交给米家的监听
                    if (orig != null) orig.onClick(v);
                    return;
                }
                if (kind == KIND_RENAME) {
                    askRename(v, frag, row, rec);
                } else if (kind == KIND_COPY) {
                    doCopy(v, frag, row, rec);
                } else if (kind == KIND_DELETE) {
                    askDelete(v, frag, row, rec);
                } else if (orig != null) {
                    orig.onClick(v);
                }
            } catch (Throwable t) {
                Log.w("本地场景菜单点击失败: " + t);
                toast(v, "操作失败：" + HookUtil.shortMsg(t));
            }
        }
    }

    private static void askRename(View v, Object frag, Object row, LocalSceneStore.Record rec) {
        Context ctx = v.getContext();
        EditText et = new EditText(ctx);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(rec.name == null ? "" : rec.name);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(ctx)
                .setTitle("重命名自动化")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String name = et.getText().toString().trim();
                        if (name.isEmpty()) name = rec.name;
                        if (!LocalSceneStore.rename(rec.id, name)) {
                            toast(v, "改名失败：本地登记里没有这条场景");
                            return;
                        }
                        HookUtil.setFieldQuiet(row, F_ROW_NAME, name);
                        exitSelection(frag);
                        notifyList(frag);
                        toast(v, "已重命名为「" + name + "」");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void askDelete(View v, Object frag, Object row, LocalSceneStore.Record rec) {
        Context ctx = v.getContext();
        new AlertDialog.Builder(ctx)
                .setTitle("删除自动化")
                .setMessage("「" + (rec.name == null || rec.name.isEmpty() ? "模拟设备自动化" : rec.name) + "」"
                        + "仅从本地列表移除，不会动到真实设备。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (!LocalSceneStore.remove(rec.id)) {
                            toast(v, "删除失败：本地登记里没有这条场景");
                            return;
                        }
                        removeRow(frag, row);
                        exitSelection(frag);
                        notifyList(frag);
                        toast(v, "已删除该自动化");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void doCopy(View v, Object frag, Object row, LocalSceneStore.Record rec) {
        LocalSceneStore.Record copy = new LocalSceneStore.Record();
        copy.id = LocalSceneStore.newSceneId();
        String base = rec.name == null || rec.name.isEmpty() ? "模拟设备自动化" : rec.name;
        copy.name = base + " 副本";
        copy.homeId = rec.homeId;
        LocalSceneStore.add(copy.id, copy.name, copy.homeId);

        Object newRow = SceneListLocalHook.cloneRow(row, copy);
        if (newRow == null) {
            toast(v, "复制失败：这条行克隆不出来");
            return;
        }
        insertRowAfter(frag, row, newRow);
        exitSelection(frag);
        notifyList(frag);
        toast(v, "已复制为「" + copy.name + "」");
    }

    /* ---------- 适配器 / 列表操作 ---------- */

    private static List<Object> adapters(Object frag) {
        List<Object> out = new ArrayList<>(3);
        Object uc = HookUtil.readFieldQuiet(frag, F_ADAPTER_UC);
        Object vc = HookUtil.readFieldQuiet(frag, F_ADAPTER_VC);
        Object auto = HookUtil.readFieldQuiet(frag, F_ADAPTER_AUTO);
        if (uc != null) out.add(uc);
        if (vc != null) out.add(vc);
        if (auto != null) out.add(auto);
        return out;
    }

    private static Map<?, ?> selectionMap(Object adapter) {
        Object m = HookUtil.readFieldQuiet(adapter, F_UC_SELECT);
        if (m instanceof Map) return (Map<?, ?>) m;
        m = HookUtil.readFieldQuiet(adapter, F_NEW_SELECT);
        if (m instanceof Map) return (Map<?, ?>) m;
        return null;
    }

    private static List<?> dataList(Object adapter) {
        Object l = HookUtil.readFieldQuiet(adapter, F_UC_LIST);
        if (l instanceof List) return (List<?>) l;
        l = HookUtil.readFieldQuiet(adapter, F_NEW_LIST);
        if (l instanceof List) return (List<?>) l;
        return null;
    }

    /** 在「包含这一行」的适配器数据表里把它删掉。*/
    private static boolean removeRow(Object frag, Object row) {
        for (Object adapter : adapters(frag)) {
            List<?> data = dataList(adapter);
            if (data == null) continue;
            int idx = indexOfSame(data, row);
            if (idx < 0) continue;
            try {
                //noinspection unchecked
                ((List<Object>) data).remove(idx);
                return true;
            } catch (Throwable t) {
                Log.v("从列表移除失败: " + HookUtil.shortMsg(t));
                return false;
            }
        }
        return false;
    }

    /** 在「包含这一行」的适配器数据表里，把新行插到它后面。*/
    private static boolean insertRowAfter(Object frag, Object row, Object newRow) {
        for (Object adapter : adapters(frag)) {
            List<?> data = dataList(adapter);
            if (data == null) continue;
            int idx = indexOfSame(data, row);
            if (idx < 0) continue;
            try {
                //noinspection unchecked
                ((List<Object>) data).add(idx + 1, newRow);
                return true;
            } catch (Throwable t) {
                Log.v("往列表插入失败: " + HookUtil.shortMsg(t));
                return false;
            }
        }
        return false;
    }

    private static int indexOfSame(List<?> list, Object target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) return i;
        }
        return -1;
    }

    /** 退出选择态：走米家自己的收尾（清选中表 + 底栏动画 + 列表刷新）。*/
    private static void exitSelection(Object frag) {
        try {
            XposedHelpers.callMethod(frag, M_EXIT_SELECT);
        } catch (Throwable t) {
            Log.v("退出选择态失败: " + HookUtil.shortMsg(t));
        }
    }

    /** 让 DelegateAdapter 整表刷新（真删除后米家也是这么刷的）。*/
    private static void notifyList(Object frag) {
        Object da = HookUtil.readFieldQuiet(frag, F_DELEGATE);
        if (da == null) return;
        try {
            XposedHelpers.callMethod(da, "notifyDataSetChanged");
        } catch (Throwable t) {
            Log.v("列表刷新失败: " + HookUtil.shortMsg(t));
        }
    }

    private static String idOf(Object row) {
        Object id = HookUtil.readFieldQuiet(row, F_ROW_ID);
        return id instanceof String ? (String) id : null;
    }

    private static void toast(View v, String msg) {
        try {
            Toast.makeText(v.getContext().getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 宿主 Context 不可用就只留日志
            Log.d(msg);
        }
    }
}