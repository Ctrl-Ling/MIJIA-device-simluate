/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 12:28:06 +08:00
 */
package com.mijia.devicesim.hook;

import android.content.Intent;

import com.mijia.devicesim.core.CallLog;
import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.MiotSpec;
import com.mijia.devicesim.core.RequestedModel;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 「添加设备 → 扫描」这一步的注入（plan Phase 3 里驱动原生添加动画的那条路）。
 *
 * <p><b>11.9.705 实测到的唯一出口</b>：{@code com.xiaomi.smarthome.device.DeviceScanManager}
 * 是个枚举单例，类名和方法名都没混淆。它把扫描结果分发给所有已注册页面走的是
 * {@code private void dispatchOnScan(java.util.List)} —— 字节码就是遍历 {@code mListener}
 * （元素是回调接口 {@code _m_j.gua}）逐个调 {@code gua.onScan(List)}。首页扫描、
 * 「手动添加 → 选型号 → 设备已重置 → 扫描」那几页的结果都从这一个方法出去。
 * <b>挂这一处，胜过到处猜 onScan 实现类叫什么。</b>
 *
 * <p>老路子 {@link #probe}（靠 {@code HookRegistry} 的 loadClass 指纹撞实现类）保留但只是补充：
 * 设备管理器按已知类名挂满 3 个之后 {@code HookRegistry} 会立刻摘掉 loadClass 钩子，
 * 正常机器上那条根本跑不到 —— 这就是以前「扫描注入开了也没反应」的一半原因。
 *
 * <p>条目怎么造：扫描列表是异构的，适配层 {@code _m_j.ol2} 按类型分发，实测出现的有
 * {@code PushBindEntity}、{@code BleDeviceGroup}、{@code android.net.wifi.ScanResult}、
 * {@code UdpGatewayBean}。其中「已重置的设备自己冒出来」那条路上报的就是
 * {@code com.xiaomi.smarthome.smartconfig.PushBindEntity}，它的 did/model/token/mac/
 * ssid/bssid/sn/router_* 全部由一个 JSONObject 直接填（构造函数字节码里实测到的键名）：
 * <pre>
 *   static PushBindEntity create(JSONObject json, boolean force)   // 11.9.705 里方法名被混淆
 *     model 为空 → 拿 ssid 去查本地插件记录；否则用 model 查
 *     force==true  → 不管记录查到没有，直接 new PushBindEntity(record, json) 并返回
 *     force==false → 记录为空就打一条日志丢掉
 * </pre>
 * 所以优先叫米家自己的工厂造：结构一定合法，而且<b>不要求那个型号在本机装过插件</b>
 * （force 传 true）。工厂找不到才退回「照现有元素克隆」。两条都不行 → 放行不注入。
 *
 * <p>独立开关 {@link SimConfig#scanInjection}，默认关：显示这一步做完还要看「完成添加」
 * 那一下米家怎么绑设备，所以先把条目做进扫描列表、开关留着验证。
 * 主路径仍然是「模块界面添加 → 列表读取处注入」，不开扫描注入也能把设备加出来。
 *
 * <p><b>11-10 12:28 补：按需合成（他真正要的那件事）。</b>
 * 光配设备不够，还要知道「他此刻在向导里选了哪个型号」，所以这里同时挂三个向导页的
 * {@code onCreate}（{@link RequestedModel}）把型号抄下来，扫描派发时<b>优先</b>按它现场合成。
 *
 * <p>顺带修掉一个真正的显示 bug：适配层 {@code _m_j.ol2.OooOOOO(List)} 对每个
 * {@code PushBindEntity} 无条件读 {@code pushBindEntity.<PluginDeviceInfo 字段>.OooOO0o()}，
 * 而这个字段就是 {@code create(json, force)} 里查到的本地插件记录 ——
 * 本机没装过这个型号插件时它是 <b>null</b>，于是整张扫描列表在刷新时直接 NPE，
 * 一条都不显示（真机 v6/v7「假条目不出现」的现场解释）。
 * 所以 {@code force=true} 造出来的条目必须过一道 {@link #hasPluginRecord} 再塞：
 * 没有插件记录的条目 <b>不塞</b>，宁可什么都不显示也不把米家的列表刷崩。
 * 反过来讲：他刚从向导里选出来的那个型号，插件记录一定是现成的
 * （{@code ResetDevicePage.onCreate} 查到 null 会自己 {@code finish()}），
 * 所以「按所选型号合成」这条路天然不会因为缺记录而被丢掉。
 */
public final class AddFlowHook {

    private static final String GROUP = "add-flow";

    private static final String SCAN_MANAGER = "com.xiaomi.smarthome.device.DeviceScanManager";
    private static final String PUSH_BIND_ENTITY = "com.xiaomi.smarthome.smartconfig.PushBindEntity";

    /**
     * 会写明「用户此刻要添加的型号」的三个向导页（11.9.705 实测三个类名都在，
     * 但走哪一页取决于型号种类，所以全挂）。类不存在只记 SKIP，米家换实现不影响主流程。
     */
    private static final String[] MODEL_PAGES = {
            "com.xiaomi.smarthome.device.ResetDevicePage",
            "com.xiaomi.smarthome.device.choosedevice.ResetPageRoute",
            "com.xiaomi.smarthome.device.ResetDevicePageScan",
    };

    /** 各版本里出现过的型号键名，按优先级试；都没有就去 plugin_record 里翻。*/
    private static final String[] MODEL_KEYS = {"model", "extra_model", "device_model"};

    /** install() 时存下来，probe() 和构造条目要用同一个 ClassLoader。*/
    private static volatile ClassLoader sLoader;

    private AddFlowHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        sLoader = lpparam.classLoader;
        Class<?> scanManager = HookUtil.findClassQuiet(SCAN_MANAGER, lpparam.classLoader);
        if (scanManager == null) {
            HookReport.skip(GROUP, "DeviceScanManager", "类不存在（本版本没有扫描管理器）");
        } else {
            HookReport.ok(GROUP, "DeviceScanManager", "扫描结果唯一出口已定位");
            // dispatchOnScan(List)：塞进它传出去的那个 List = 所有开着的扫描页都收到
            HookUtil.hookMethod(GROUP, scanManager, "dispatchOnScan", APPEND_SCAN_RESULT, List.class);
        }
        installWizardCapture(lpparam.classLoader);
        // 条目能显示只是第一步，点下去不能报错 —— 两道短路闸（详见 AddShortCircuit）
        AddShortCircuit.install(lpparam.classLoader);
    }

    /**
     * 抄下「他在向导里选的型号」：三个候选页各挂 {@code onCreate}（读型号）和 {@code onDestroy}
     * （释放）。挂的是 Activity 生命周期的正常点，不改米家任何行为。
     *
     * <p>顺带把整个 extras 记一条 {@code wizard} 调用日志：真机上 {@code dumpsys} 只会打
     * {@code (has extras)} 不给键值，现场抓一次就把键名和值钉死了，省得下回再连手机猜。
     */
    private static void installWizardCapture(ClassLoader loader) {
        for (String page : MODEL_PAGES) {
            Class<?> clazz = HookUtil.findClassQuiet(page, loader);
            if (clazz == null) {
                HookReport.skip(GROUP, page, "本页没有这个向导类（型号来源少了这一个）");
                continue;
            }
            String simple = clazz.getSimpleName();
            boolean created = HookUtil.hookMethod(GROUP, clazz, "onCreate", CAPTURE_REQUESTED_MODEL,
                    android.os.Bundle.class);
            HookUtil.hookMethod(GROUP, clazz, "onDestroy", CLEAR_REQUESTED_MODEL);
            HookReport.ok(GROUP, simple, "型号捕获 onCreate=" + (created ? "已挂" : "未挂")
                    + "，onDestroy 已挂");
        }
    }

    /** onCreate 之后读 intent：先按已知键名试，再退回 plugin_record，最后把整个 extras 记日志。*/
    private static final XC_MethodHook CAPTURE_REQUESTED_MODEL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object activity = param.thisObject;
                String model = modelFromActivity(activity);
                RequestedModel.Record rec = RequestedModel.capture(model, activity);
                CallLog.record(DeviceListHook.context(),
                        rec == null ? CallLog.OP_EVENT : CallLog.OP_INJECT,
                        "wizard", activity.getClass().getSimpleName(),
                        model == null ? "" : model, 0, rec == null ? 0 : 1, 0,
                        "extras " + dumpExtras(activity));
                if (rec != null) {
                    // 型号是主进程里的现场状态，子进程上报的报告里没有它；
                    // 抓到新请求的这一刻立刻由主进程发一次报告，界面马上能看到「正在请求的型号」。
                    HookReport.publish(DeviceListHook.context());
                }
            } catch (Throwable t) {
                Log.e("向导型号捕获失败（不影响米家添加流程）", t);
            }
        }
    };

    /** 向导页销毁：把「他正在要的那台型号」收回来，别让它无限期粘在扫描列表里。*/
    private static final XC_MethodHook CLEAR_REQUESTED_MODEL = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                RequestedModel.release(param.thisObject);
            } catch (Throwable ignored) {
            }
        }
    };

    /** 从向导 Activity 的 intent 里挖出型号；挖不到返回空串（不覆盖上一次的有效请求）。*/
    private static String modelFromActivity(Object activity) {
        Intent intent;
        try {
            Object raw = XposedHelpers.callMethod(activity, "getIntent");
            if (!(raw instanceof Intent)) return "";
            intent = (Intent) raw;
        } catch (Throwable t) {
            return "";
        }
        for (String key : MODEL_KEYS) {
            String v;
            try {
                v = intent.getStringExtra(key);
            } catch (Throwable t) {
                continue;
            }
            if (v != null && !v.isEmpty()) return v;
        }
        // 兜底：plugin_record 这个 Parcelable 里总有一个字符串字段就是型号
        try {
            Object record = intent.getParcelableExtra("plugin_record");
            if (record != null) {
                String m = firstModelLikeField(record);
                if (m != null) return m;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 找对象上第一个长得像型号的 String 字段（认值特征、不认混淆名，实现见 DeviceAcceptor）。*/
    private static String firstModelLikeField(Object target) {
        return com.mijia.devicesim.core.DeviceAcceptor.modelOf(target);
    }

    private static boolean isModelLike(String v) {
        return com.mijia.devicesim.core.DeviceAcceptor.looksLikeModel(v);
    }

    /** extras 全量摘要（截断），只为把键名钉死；值太长的一律压成类型名。*/
    private static String dumpExtras(Object activity) {
        try {
            Intent intent = (Intent) XposedHelpers.callMethod(activity, "getIntent");
            android.os.Bundle b = intent == null ? null : intent.getExtras();
            if (b == null) return "(无 extras)";
            StringBuilder sb = new StringBuilder();
            for (String k : b.keySet()) {
                Object v;
                try {
                    v = b.get(k);
                } catch (Throwable t) {
                    continue;
                }
                if (v == null) continue;
                boolean primitive = v instanceof String || v instanceof Number || v instanceof Boolean;
                sb.append(k).append('=');
                if (primitive) {
                    String s = String.valueOf(v);
                    sb.append(s.length() > 48 ? s.substring(0, 48) + "…" : s);
                } else {
                    sb.append('<').append(v.getClass().getSimpleName()).append('>');
                }
                sb.append(' ');
                if (sb.length() > 320) break;
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return "(读不出 extras: " + HookUtil.shortMsg(t) + ")";
        }
    }

    /**
     * 由 {@link HookRegistry} 在类加载时调用：凡是自己声明了 {@code void onScan(List)} 的实现类都挂上。
     * 接口方法（abstract）挂不住，必须挂在实现类上，所以只能靠类加载现场判断。
     *
     * <p>主力出口是 {@link #install} 里那条 {@code dispatchOnScan}；这里只是给
     * 「扫描管理器形状变了、但 onScan 实现还在」的版本留的后路。{@code dispatchOnScan}
     * 已经挂上时直接返回 0，免得同一份结果注入两遍。
     */
    public static int probe(Class<?> clazz) {
        if (clazz == null || sLoader == null) return 0;
        if (HookUtil.isInstalled(SCAN_MANAGER + "#dispatchOnScan(List)")) return 0;
        Method target = null;
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (!m.getName().equals("onScan")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && Collection.class.isAssignableFrom(pts[0])) {
                    target = m;
                    break;
                }
            }
        } catch (Throwable t) {
            return 0;
        }
        if (target == null) return 0;
        return HookUtil.hookMethod(GROUP, clazz, "onScan", APPEND_SCAN_RESULT, List.class) ? 1 : 0;
    }

    @SuppressWarnings("unchecked")
    private static final XC_MethodHook APPEND_SCAN_RESULT = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                SimConfig cfg = ConfigRepository.current();
                if (!cfg.scanInjection || !cfg.injectEnabled) return;
                if (!(param.args[0] instanceof List)) return;
                List<Object> results = (List<Object>) param.args[0];
                List<Object> entries = buildEntries(results, cfg);
                // 无论有没有塞进去都要留证据：这条是「扫描页到底走不走这个出口」的现场判定，
                // 出问题时靠 in/out/note 就能分清「没被调用」和「被调用了但造不出条目」。
                // 派发是每秒级的，所以只在结果变化时记一条，别把日志刷满。
                if (!entries.isEmpty() || !lastReason.equals(sLastLogged)) {
                    sLastLogged = lastReason;
                    CallLog.record(DeviceListHook.context(),
                            entries.isEmpty() ? CallLog.OP_EVENT : CallLog.OP_INJECT,
                            "scan", param.thisObject == null ? "" : param.thisObject.getClass().getName(),
                            "", results.size(), entries.size(), 0, lastReason);
                }
                if (entries.isEmpty()) return;
                results.addAll(entries);
                // 条目已经在屏上了：趁这一刻从扫描管理器往下找「点击出口」把点击闸挂上。
                // 宿主页那一路（AddShortCircuit.install）挂不上时的兜底发现点，
                // 只在真的塞了东西时才跑一趟，且挂上以后立刻短路返回。
                AddShortCircuit.installClickGuardFrom(param.thisObject, sLoader);
            } catch (Throwable t) {
                Log.e("扫描结果注入失败（放行原结果）", t);
            }
        }
    };

    /** 上一次塞扫描列表时为什么塞/没塞，进调用日志的 note。*/
    private static volatile String lastReason = "init";
    private static volatile String sLastLogged = "";

    /**
     * 要补进扫描列表的条目，两个数据源按优先级：
     * <ol>
     *   <li><b>他刚在米家向导里选的型号</b>（{@link RequestedModel}）—— 现场合成身份，
     *       这是这个功能的主路；</li>
     *   <li>本模块配置里启用的设备 —— 老的「先配好再扫出来」用法，留作第二源。</li>
     * </ol>
     * 两条源都优先叫米家自己的 {@code PushBindEntity.create(json, force)} 造，
     * 并且<b>造完必须过 {@link #hasPluginRecord} 才允许塞</b>：适配器对插件记录为 null 的
     * 条目会直接 NPE，塞进去等于把整张扫描列表刷没。工厂找不到才退回「照现有元素克隆」。
     */
    private static List<Object> buildEntries(List<Object> results, SimConfig cfg) {
        List<Object> out = new ArrayList<>();
        Object template = firstNonNull(results);
        Method factory = pushBindFactory();
        StringBuilder note = new StringBuilder();
        int dropped = 0;

        RequestedModel.Record req = RequestedModel.get();
        if (req != null) {
            if (holdsDid(results, req.device.did)) {
                note.append("请求型号已在列表 ");
            } else {
                Object bean = beanFor(factory, template, req.device);
                if (bean == null) {
                    dropped++;
                    note.append("请求型号 ").append(req.model).append(" 本机无插件记录，未塞 ");
                } else {
                    out.add(bean);
                    note.append("按需 ").append(req.model).append(' ');
                }
            }
        }

        List<SimDevice> devices = cfg.enabledDevices();
        int already = 0;
        for (SimDevice d : devices) {
            if (holdsDid(results, d.did) || holdsDid(out, d.did)) { already++; continue; }
            Object bean = beanFor(factory, template, d);
            if (bean == null) { dropped++; continue; }
            out.add(bean);
        }

        if (req == null && devices.isEmpty()) {
            lastReason = "没有请求型号、也没有参与注入的设备";
        } else {
            note.append(factory != null ? "PushBindEntity" : "克隆")
                    .append(" 配置 ").append(devices.size() - already).append(" 条")
                    .append(already > 0 ? "(已在列表 " + already + ")" : "")
                    .append(dropped > 0 ? " 丢弃 " + dropped + " 条(本机无插件记录)" : "");
            if (req == null) note.insert(0, "无请求型号 ");
            lastReason = note.toString();
        }
        return out;
    }

    /**
     * 一台模拟设备 → 一个能安全塞进扫描列表的条目；造不出来（或造出来会让适配器崩）返回 null。
     */
    private static Object beanFor(Method factory, Object template, SimDevice d) {
        if (factory != null) {
            Object bean = createPushBind(factory, d);
            if (bean != null) {
                if (hasPluginRecord(bean)) return bean;
                Log.v("丢弃条目 " + d.did + " " + d.model + "：本机没有该型号的插件记录，塞进去会让扫描列表刷新时 NPE");
                return null;
            }
        }
        if (template != null) return cloneAs(template, d);
        return null;
    }

    /**
     * 适配层 {@code _m_j.ol2.OooOOOO(List)} 会无条件读 PushBindEntity 里那个
     * {@code PluginDeviceInfo} 字段的方法（11.9.705 是 {@code OooOo.OooOO0o()}），
     * 字段名混淆了就按<b>类型</b>找：有这种类型的字段且为 null = 危险；
     * 一个都没有（别的版本形状不同）= 说明不了什么，放行。
     */
    private static boolean hasPluginRecord(Object bean) {
        Class<?> c = bean.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return true;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().getName().endsWith("PluginDeviceInfo")) continue;
                try {
                    f.setAccessible(true);
                    return f.get(bean) != null;
                } catch (Throwable t) {
                    return true;
                }
            }
            c = c.getSuperclass();
        }
        return true;
    }

    private static Object firstNonNull(List<Object> results) {
        for (Object o : results) if (o != null) return o;
        return null;
    }

    /* ---------- 正路：让米家自己造 PushBindEntity ---------- */

    private static Method sPushBindFactory;
    /** 只解析一次：扫描页每秒都在派发结果，不能每次都翻一遍方法表。*/
    private static boolean sFactoryResolved;

    /**
     * 反射找 {@code static PushBindEntity create(JSONObject, boolean)}。
     * 只按「返回类型 + 参数类型」匹配，方法名被混淆成什么都不影响，米家改名也照样找得到。
     */
    private static Method pushBindFactory() {
        if (sFactoryResolved) return sPushBindFactory;
        ClassLoader loader = sLoader;
        if (loader == null) return null;
        Method found = null;
        Class<?> bean = HookUtil.findClassQuiet(PUSH_BIND_ENTITY, loader);
        if (bean == null) {
            HookReport.skip(GROUP, "PushBindEntity", "类不存在，扫描条目只能走克隆");
        } else {
            try {
                for (Method m : bean.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (!bean.isAssignableFrom(m.getReturnType())) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 2 && pts[0] == JSONObject.class && pts[1] == boolean.class) {
                        found = m;
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.v("PushBindEntity 工厂查找失败: " + HookUtil.shortMsg(t));
            }
            HookReport.ok(GROUP, "PushBindEntity.create",
                    found == null ? "没有 (JSONObject,boolean) 工厂，只能克隆" : "工厂方法 = " + found.getName());
        }
        sPushBindFactory = found;
        sFactoryResolved = true;
        return found;
    }

    /**
     * 键名就是 {@code PushBindEntity.<init>(PluginDeviceInfo, JSONObject)} 里读的那几个。
     * force 传 true：查不到本地插件记录也照常造条目 —— 模拟设备的型号本机常常没装过插件，
     * 传 false 会被工厂丢掉。
     */
    private static Object createPushBind(Method factory, SimDevice d) {
        try {
            JSONObject json = new JSONObject();
            json.put("model", d.model);
            json.put("did", d.did);
            json.put("token", d.token == null ? "" : d.token);
            json.put("mac", d.mac == null ? "" : d.mac);
            json.put("ssid", "");
            json.put("bssid", "");
            json.put("rssi", "-50");
            json.put("sn", d.did);
            json.put("public_key", "");
            Object bean = factory.invoke(null, json, true);
            if (bean != null) Log.v("扫描条目已构造: " + d.did + " " + d.model);
            return bean;
        } catch (Throwable t) {
            Log.v("PushBindEntity 构造失败(" + HookUtil.shortMsg(t) + ")，退回克隆");
            return null;
        }
    }

    /**
     * 列表里是否已经有一条代表这台模拟设备的条目。
     *
     * <p>不能只认叫 {@code did} 的字段：PushBindEntity 的字段名全被混淆了（did 落在某个
     * 短名字段上）。所以把对象上所有 String 字段读一遍，任意一个等于我们的 did 就算已存在。
     * 扫描列表是几十条这个量级，这点反射代价换「不重复注入」值得。
     */
    private static boolean holdsDid(List<Object> results, String did) {
        if (did == null || did.isEmpty()) return false;
        for (Object o : results) {
            if (o == null) continue;
            if (did.equals(DeviceListHook.strField(o, "did"))) return true;
            if (anyStringFieldEquals(o, did)) return true;
        }
        return false;
    }

    private static boolean anyStringFieldEquals(Object target, String value) {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                return false;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    if (value.equals(f.get(target))) return true;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /* ---------- 退路：照着现有元素克隆 ---------- */

    private static Object cloneAs(Object template, SimDevice d) {
        try {
            Object copy = XposedHelpers.newInstance(template.getClass());
            copyEveryField(template, copy);
            applyIdentity(copy, d);
            return copy;
        } catch (Throwable t) {
            Log.v("扫描条目克隆失败(" + template.getClass().getSimpleName() + "): " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /** 把假设备的身份写进克隆出来的扫描条目；字段名不存在就跳过，存在就按类型换算写入。 */
    private static void applyIdentity(Object entry, SimDevice d) {
        XposedFieldSetter.setIfPresent(entry, "did", d.did);
        XposedFieldSetter.setIfPresent(entry, "model", d.model);
        XposedFieldSetter.setIfPresent(entry, "deviceName", d.name);
        XposedFieldSetter.setIfPresent(entry, "name", d.name);
        XposedFieldSetter.setIfPresent(entry, "token", d.token);
        XposedFieldSetter.setIfPresent(entry, "mac", d.mac);
        XposedFieldSetter.setIfPresent(entry, "extra",
                "{\"" + Consts.EXTRA_SIMULATED + "\":1}");
    }

    private static void copyEveryField(Object from, Object to) {
        Class<?> c = from.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    f.set(to, f.get(from));
                } catch (Throwable ignored) {
                    // 不可写的字段跳过，不影响其它字段
                }
            }
            c = c.getSuperclass();
        }
    }

    private static MiotSpec specOf(SimDevice d) {
        return DeviceListHook.specOf(d);
    }
}
