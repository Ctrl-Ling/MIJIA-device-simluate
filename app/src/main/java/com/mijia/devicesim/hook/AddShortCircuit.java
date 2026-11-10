/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 19:12:40 +08:00
 */
package com.mijia.devicesim.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.DeviceAcceptor;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.RequestedModel;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「完成添加」短路：假设备被点开时不进入米家的真实配网，而是就地落成正式设备。
 *
 * <p><b>为什么必须短路</b>（11-10 对 11.9.705 的静态取证，详见 docs/HOOKS.md §6）：
 * 扫描条目点击的唯一出口是适配层 {@code _m_j.ol2} 里那个 7 参方法（参数形状：
 * {@code ScanDeviceType, String, ScanResult, BleDevice, MiTVDevice, PushBindEntity, Object}），
 * 它内部会做插件记录检查后路由到 {@code PushBindConfigActivity}，那页的主按钮
 * （字节码里挂在 view id 2131432127 上的 {@code _m_j.d1c#onClick}）再走
 * 热点检查 {@code hia.Oooo0OO} → smartconfig → 云端绑定 {@code SuccessStep.bindDeviceToHome}。
 * 假设备没有热点、也没有云端可绑，这条路一定失败 —— 所谓「点了报错」就是走到这里。
 *
 * <p><b>两道闸，按可靠性排序</b>：
 * <ol>
 *   <li><b>点击闸</b> {@link #ACCEPT_CLICK}：挂在那个 7 参出口上，{@code setResult(null)}
 *       让米家根本不发起配网。出口方法是「值特征认出假条目 → 就地 accept」，
 *       真设备一条指令都不多下。</li>
 *   <li><b>页面闸</b> {@link #ACCEPT_AT_PROVISION_PAGE}：挂 {@code PushBindConfigActivity}
 *       和 {@code SmartConfigMainActivity} 的 {@code onCreate}（类名不混淆、跨版本稳定）。
 *       万一点击闸那个版本不生效（适配层改名/形状变了），页面刚起来就被 {@code finish()}，
 *       设备照样落成，用户不会看到报错弹窗。</li>
 * </ol>
 *
 * <p>两道闸都只做一件事：把设备交给 {@link DeviceAcceptor#accept} 写进配置清单，
 * 首页列表由已有的设备注入显示 —— 也就是米家升级把混淆名换了，功能也只是退到页面闸，
 * 不会退成「点了报错」。
 *
 * <p>点击闸的类只能现场认（混淆名 {@code ol2} 不固定）：不猜名字，而是在「扫描列表那一页
 * 真的建起来了」的那一刻，从页面对象出发按<b>方法形状</b>搜出持有这种方法的类再挂 ——
 * 见 {@link #installClickGuardFrom}。四个 {@code new ol2(...)} 的宿主页类名都不混淆
 * （{@code ChooseConnectDevice} / {@code ChooseDeviceManually} / {@code ChooseUdpDeviceActivity}
 * / {@code MeshSubDeviceConnectActivity} / 首页气泡 {@code ScanDeviceView}），所以挂在它们身上
 * 找出口比挂在派发口上稳定，也不会每次派发都重扫一遍对象图。
 */
public final class AddShortCircuit {

    private static final String GROUP = "accept-flow";

    /** 米家配网页（类名不混淆，跨版本稳定）—— 页面闸挂这两页的 onCreate。*/
    private static final String[] PROVISION_PAGES = {
            "com.xiaomi.smarthome.smartconfig.PushBindConfigActivity",
            "com.xiaomi.smarthome.smartconfig.SmartConfigMainActivity",
    };

    /**
     * 扫描列表的宿主页（11-10 实测这四个地方 {@code new ol2(...)}，加上首页气泡那个覆盖层）。
     * 前两个是 Fragment（{@code onCreateView} 里装 adapter），后三个是 Activity/View，
     * 所以两种生命周期点都试一次，挂不上的只记 SKIP。
     */
    private static final String[] ROW_LIST_PAGES = {
            "com.xiaomi.smarthome.device.ChooseConnectDevice",
            "com.xiaomi.smarthome.device.ChooseDeviceManually",
            "com.xiaomi.smarthome.device.ChooseUdpDeviceActivity",
            "com.xiaomi.smarthome.device.subPlc.MeshSubDeviceConnectActivity",
            "com.xiaomi.smarthome.device.choosedevice.ScanDeviceView",
    };

    /** 点击闸已经挂过的适配层类名（同一进程内不重复挂）。*/
    private static final Set<String> sClickHookedClasses =
            Collections.synchronizedSet(new HashSet<String>());

    private AddShortCircuit() {
    }

    public static void install(ClassLoader loader) {
        for (String page : PROVISION_PAGES) {
            Class<?> clazz = HookUtil.findClassQuiet(page, loader);
            if (clazz == null) {
                HookReport.skip(GROUP, page, "类不存在（本版本配网页换了名字，只剩点击闸）");
                continue;
            }
            boolean ok = HookUtil.hookMethod(GROUP, clazz, "onCreate",
                    ACCEPT_AT_PROVISION_PAGE, android.os.Bundle.class);
            HookReport.ok(GROUP, clazz.getSimpleName() + "#onCreate",
                    ok ? "页面闸已挂（假设备进来就 finish）" : "页面闸未挂（精确签名失败）");
        }
        installClickGuardProbes(loader);
    }

    /**
     * 挂到扫描列表宿主页上，等页面建好以后再去对象图里找点击出口。
     * 这一步挂不上不影响功能：页面闸照样兜得住，只是点下去会闪一下配网页再退回。
     */
    private static void installClickGuardProbes(ClassLoader loader) {
        for (String page : ROW_LIST_PAGES) {
            Class<?> clazz = HookUtil.findClassQuiet(page, loader);
            if (clazz == null) {
                HookReport.skip(GROUP, page, "类不存在（这一版的扫描列表页换了位置）");
                continue;
            }
            String simple = clazz.getSimpleName();
            boolean view = HookUtil.hookMethod(GROUP, clazz, "onCreateView", ATTACH_CLICK_GUARD,
                    android.view.LayoutInflater.class, android.view.ViewGroup.class,
                    android.os.Bundle.class);
            boolean created = HookUtil.hookMethod(GROUP, clazz, "onCreate", ATTACH_CLICK_GUARD,
                    android.os.Bundle.class);
            HookReport.ok(GROUP, simple, "点击出口探测 onCreateView=" + (view ? "已挂" : "无此签名")
                    + " onCreate=" + (created ? "已挂" : "无此签名"));
        }
    }

    /** 页面建好 = adapter 已经 new 出来了，此刻从页面对象往下找点击出口。*/
    private static final XC_MethodHook ATTACH_CLICK_GUARD = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!sClickHookedClasses.isEmpty()) return;
            if (!ConfigRepository.current().acceptShortCircuit) return;
            installClickGuardFrom(param.thisObject, null);
        }
    };

    /**
     * 塞完条目以后也可以直接调用（宿主页没挂上的版本留这条路）：从根对象出发按方法形状找一次。
     * 每次调用都是一趟独立的广度优先， visited 用局部集合，不缓存「找过哪些类」——
     * 因为第一次派发时 adapter 可能还没 new 出来，缓存成永久黑名单就永远挂不上了。
     */
    public static void installClickGuardFrom(Object root, ClassLoader loader) {
        if (!sClickHookedClasses.isEmpty()) return;
        try {
            Set<Object> visited = new HashSet<>(Collections.singleton(new Wrapper(root)));
            ArrayDeque<Object> queue = new ArrayDeque<>();
            ArrayDeque<Integer> depth = new ArrayDeque<>();
            queue.add(root);
            depth.add(0);
            int nodes = 0;
            while (!queue.isEmpty() && nodes < 400) {
                Object node = queue.poll();
                Integer d = depth.poll();
                if (node == null || d == null) continue;
                nodes++;
                Class<?> c = node.getClass();
                Method m = clickMethodOf(c);
                if (m != null) {
                    hookClickMethod(c, m);
                    if (!sClickHookedClasses.isEmpty()) return;   // 挂上了就收工
                }
                if (d >= 3) continue;
                for (Field f : fieldsOf(c)) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft.getName().startsWith("java.lang.")) continue;
                    Object child;
                    try {
                        f.setAccessible(true);
                        child = f.get(node);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (push(queue, depth, child, d + 1, visited)) {
                        if (child instanceof Iterable) {
                            int n = 0;
                            for (Object o : (Iterable<?>) child) {
                                if (n++ > 24) break;
                                push(queue, depth, o, d + 2, visited);
                                if (!sClickHookedClasses.isEmpty()) return;
                            }
                        }
                    }
                }
                if (!sClickHookedClasses.isEmpty()) return;
            }
            if (sClickHookedClasses.isEmpty()) {
                Log.v("这 " + nodes + " 个对象里没找到扫描条目点击出口（页面闸继续兜着）");
            }
        } catch (Throwable t) {
            Log.v("点击出口搜索失败（只剩页面闸兜着）: " + HookUtil.shortMsg(t));
        }
    }

    /** HashSet 按 equals 比对象，米家很多对象没重写 equals —— 包一层只比身份，防止误判已访问。*/
    private static final class Wrapper {
        private final Object target;

        Wrapper(Object target) {
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Wrapper && ((Wrapper) o).target == target;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(target);
        }
    }

    private static boolean push(ArrayDeque<Object> queue, ArrayDeque<Integer> depth,
                                Object o, int d, Set<Object> visited) {
        if (o == null || visited.contains(new Wrapper(o))) return false;
        String name = o.getClass().getName();
        // 只往下钻米家自己的对象；framework/android 的字段图太大，而且出口不可能在那里面
        if (name.startsWith("android.") || name.startsWith("dalvik.")
                || name.startsWith("libcore.") || name.startsWith("kotlin.")) return false;
        visited.add(new Wrapper(o));
        queue.add(o);
        depth.add(d);
        return true;
    }

    private static Field[] fieldsOf(Class<?> c) {
        List<Field> out = new ArrayList<>();
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                out.addAll(Arrays.asList(k.getDeclaredFields()));
            } catch (Throwable t) {
                break;
            }
            k = k.getSuperclass();
        }
        return out.toArray(new Field[0]);
    }

    /**
     * 扫描条目点击出口的方法形状（11.9.705 实测 {@code _m_j.ol2.OooO0Oo}）：
     * {@code void (ScanDeviceType, String, ScanResult, BleDevice, MiTVDevice, PushBindEntity, Object)}。
     * 按类型认，一个混淆名都不写：只要第 3 个参数是 {@code android.net.wifi.ScanResult}、
     * 第 6 个是 {@code PushBindEntity}，就是它。
     */
    private static Method clickMethodOf(Class<?> c) {
        Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            return null;
        }
        for (Method m : methods) {
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 7 || m.getReturnType() != void.class) continue;
            if (!pts[2].getName().equals("android.net.wifi.ScanResult")) continue;
            if (!isPushBind(pts[5])) continue;
            return m;
        }
        return null;
    }

    private static boolean isPushBind(Class<?> type) {
        return type != null && type.getName().endsWith("PushBindEntity");
    }

    private static void hookClickMethod(Class<?> c, Method m) {
        String sig = c.getName() + "#" + m.getName() + "(7)";
        if (!sClickHookedClasses.add(c.getName())) return;
        try {
            XposedBridge.hookMethod(m, ACCEPT_CLICK);
            HookReport.ok(GROUP, sig, "点击闸已挂（按方法形状认出，不依赖混淆名）");
            Log.v("点击出口已挂：" + sig);
        } catch (Throwable t) {
            sClickHookedClasses.remove(c.getName());
            HookReport.fail(GROUP, sig, HookUtil.shortMsg(t));
        }
    }

    /** 点击闸：认出是我们造的条目 → 拦掉配网 → 就地 accept。真设备原样放行。*/
    private static final XC_MethodHook ACCEPT_CLICK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object bean = null;
                String did = null;
                for (Object a : param.args) {
                    if (a == null) continue;
                    String d = DeviceAcceptor.simulatedDidOf(a);
                    if (d != null) {
                        did = d;
                        bean = a;
                        break;
                    }
                }
                if (did == null) return;
                if (!ConfigRepository.current().acceptShortCircuit) {
                    Log.v("点击到模拟条目 " + did + "，但「完成添加短路」关着，交给米家真实流程");
                    return;
                }
                param.setResult(null);       // 米家的配网/路由一步都不执行
                String note = accept(DeviceListHook.context(), did, bean);
                CallLog.record(DeviceListHook.context(), CallLog.OP_ACCEPT, did,
                        modelFor(did), "click", 0, 1, 0, note);
                toast(DeviceListHook.context(), note);
                HookReport.publish(DeviceListHook.context());
            } catch (Throwable t) {
                Log.e("点击短路失败（米家真实流程照常）", t);
            }
        }
    };

    /** 页面闸：配网页已经起来了才发现是假设备 —— 立刻 finish，设备照样落成。*/
    private static final XC_MethodHook ACCEPT_AT_PROVISION_PAGE = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object activity = param.thisObject;
                if (!(activity instanceof Activity)) return;
                if (!ConfigRepository.current().acceptShortCircuit) return;
                Intent intent;
                try {
                    intent = (Intent) XposedHelpers.callMethod(activity, "getIntent");
                } catch (Throwable t) {
                    return;
                }
                String did = DeviceAcceptor.simulatedDidInIntent(intent);
                if (did == null) return;             // 真实设备，一点都不要动
                Object bean = parcelFrom(intent);
                String note = accept((Context) activity, did, bean);
                CallLog.record(DeviceListHook.context(), CallLog.OP_ACCEPT, did,
                        modelFor(did), activity.getClass().getSimpleName(), 0, 1, 0, note);
                toast((Context) activity, note);
                try {
                    ((Activity) activity).finish();
                } catch (Throwable t) {
                    Log.v("配网页 finish 失败（设备已落地，页面要手退）: " + HookUtil.shortMsg(t));
                }
                HookReport.publish(DeviceListHook.context());
            } catch (Throwable t) {
                Log.e("页面闸失败（米家配网页照常走）", t);
            }
        }
    };

    private static Object parcelFrom(Intent intent) {
        for (String key : new String[]{"bind_aiot_wifi", "aiot_wifi"}) {
            try {
                Object o = intent.getParcelableExtra(key);
                if (o != null) return o;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 落地 = 交给 core 写配置；这里只负责把「向导现场那台」的型号带上。*/
    private static String accept(Context ctx, String did, Object bean) {
        try {
            String model = modelFor(did);
            return DeviceAcceptor.accept(ctx, did, model, bean);
        } catch (Throwable t) {
            return "落地失败：" + HookUtil.shortMsg(t);
        }
    }

    /** did 对应的型号：先看配置，再看「他刚从向导里选的那台」，最后从条目本身读。*/
    private static String modelFor(String did) {
        SimConfig cfg = ConfigRepository.current();
        SimDevice d = cfg.findByDid(did);
        if (d != null && d.model != null && !d.model.isEmpty()) return d.model;
        RequestedModel.Record r = RequestedModel.get();
        if (r != null && did.equals(r.device.did)) return r.model;
        return "";
    }

    private static void toast(Context ctx, String note) {
        if (ctx == null) return;
        try {
            Toast.makeText(ctx.getApplicationContext(),
                    "模拟设备已添加：" + note, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.v("提示条弹不出来（不影响设备已落地）: " + HookUtil.shortMsg(t));
        }
    }
}
