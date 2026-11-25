/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-25 19:27:29 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.HookReport;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimConfig;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 自动化编辑页「不在当前家庭 / 部分功能已失效 / 创建没反应」的根修复。
 *
 * <p>大白话：编辑页拿到场景后，会把每条触发/条件/动作里的设备状态
 * （{@code _m_j.sbr$OooO}，带 did + model）拿去和「设备缓存」对一遍：
 * {@code SceneOperationManager.Oooo0OO(sbr, editType, sType)} 里逐条调
 * {@code _m_j.lhr$OooO0O0#o00Ooo(did)} 查 Device，查到了、did 和 model 都对得上，
 * 才把「不可用」状态换成正常状态。模拟设备不在米家的设备表里，这一步必然查空，
 * 于是条目一直挂着「不可用」——页面显示「不在当前家庭」、banner 报
 * 「部分功能已失效，请修改后保存」，点「创建」时页面也被这条无效链路拖着不动。
 *
 * <p>两刀，从源头到兜底：
 * <ol>
 *   <li><b>补查询</b>：{@code _m_j.lhr$OooO0O0#o00Ooo(String)->Device} 对模拟 did
 *       直接返回 {@link DeviceBeanFactory} 造的 Device。类是混淆名、且 recon 里有重复拷贝，
 *       所以运行时按「类名 + 名字 + 形状」挂（{@link HookSniper}），名字变了还能按形状扫。</li>
 *   <li><b>补状态</b>：{@code SceneOperationManager.Oooo0OO} 原始逻辑跑完后，再扫一遍
 *       返回的场景对象：凡是状态仍是「不可用」、did 又是模拟设备的条目，就地换成
 *       {@code new sbr$OooO(Device)}。这一步不依赖 model 是否一致，是硬兜底。</li>
 * </ol>
 *
 * <p>只碰「启用中的模拟 did」，真设备的条目一个不动。
 */
public final class SceneDeviceStateHook {

    private static final String GROUP = "scene-device-state";

    /** 场景条目「did → Device」的查询口（混淆类，运行时按名字 + 形状解析）。*/
    private static final String CL_LOOKUP = "_m_j.lhr$OooO0O0";
    private static final String M_LOOKUP = "o00Ooo";

    /** 场景条目状态修复器（类名未混淆，方法名仍是主题名）。*/
    private static final String CL_OPERATION = "com.xiaomi.smarthome.specscene.SceneOperationManager";
    private static final String M_REPAIR = "Oooo0OO";

    /** 场景条目状态子类：不可用态。*/
    private static final String CL_STATE = "_m_j.sbr$OooO";
    /** 场景条目基类里的状态字段（三个子类都继承它）。*/
    private static final String F_ITEM_STATE = "OooOOo0";
    /** 不可用态里记录的 did。*/
    private static final String F_STATE_DID = "OooO00o";

    private SceneDeviceStateHook() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        int lookup = HookSniper.hook(lpparam.classLoader, GROUP, CL_LOOKUP,
                new String[]{M_LOOKUP}, HookSniper.Shape.of("Device", "String"),
                "[oO0]{5,9}", LOOKUP);
        int repair = HookSniper.hook(lpparam.classLoader, GROUP, CL_OPERATION,
                new String[]{M_REPAIR},
                HookSniper.Shape.of("sbr", "sbr", "OooO0O0", "OooO0OO"),
                "[oO0]{6,9}", REPAIR);
        if (lookup > 0 || repair > 0) {
            HookReport.ok(GROUP, "install", "did查询=" + lookup + "，状态兜底=" + repair);
        } else {
            HookReport.fail(GROUP, "install", "两个挂点都没命中（类/方法可能改名）");
        }
    }

    /* ---------- 1. 设备缓存查询：模拟 did 直接给假 Device ---------- */

    private static final XC_MethodHook LOOKUP = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length == 0) return;
                Object arg = param.args[0];
                String did = arg instanceof String ? (String) arg : null;
                if (!Consts.isSimulatedDid(did)) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) return;
                Object bean = DeviceBeanFactory.deviceFor(DeviceListHook.context(), dev,
                        DeviceListHook.specOf(dev), DeviceListHook.stamp(cfg));
                if (bean == null) return;
                param.setResult(bean);
                Log.d("场景设备缓存命中模拟 did=" + did + " model=" + dev.model);
            } catch (Throwable t) {
                Log.e("场景设备缓存注入异常", t);
            }
        }
    };

    /* ---------- 2. 状态兜底：原始修复之后再扫一遍 ---------- */

    private static final XC_MethodHook REPAIR = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object scene = param.getResult();
                if (scene == null && param.args != null && param.args.length > 0) {
                    scene = param.args[0];
                }
                if (scene == null) return;
                SimConfig cfg = ConfigRepository.current();
                if (cfg == null || !cfg.injectEnabled) return;
                Class<?> declared = declaringClass(param);
                ClassLoader cl = declared == null ? null : declared.getClassLoader();
                if (cl == null) cl = DeviceListHook.class.getClassLoader();
                repairScene(scene, cfg, cl);
            } catch (Throwable t) {
                Log.e("场景条目状态兜底异常", t);
            }
        }
    };

    private static Class<?> declaringClass(XC_MethodHook.MethodHookParam param) {
        Object m = param.method;
        return m instanceof java.lang.reflect.Member
                ? ((java.lang.reflect.Member) m).getDeclaringClass() : null;
    }

    private static void repairScene(Object scene, SimConfig cfg, ClassLoader cl) {
        Class<?> stateCls = HookUtil.findClassQuiet(CL_STATE, cl);
        Class<?> deviceCls = HookUtil.findClassQuiet(DeviceBeanFactory.CL_DEVICE, cl);
        if (stateCls == null || deviceCls == null) return;

        Constructor<?> stateCtor;
        try {
            stateCtor = stateCls.getDeclaredConstructor(deviceCls);
            stateCtor.setAccessible(true);
        } catch (Throwable t) {
            Log.v("状态构造器不可用: " + HookUtil.shortMsg(t));
            return;
        }

        int fixed = 0;
        int unavailable = 0;
        for (Object item : flatItems(scene)) {
            Field stateField = HookUtil.findField(item.getClass(), F_ITEM_STATE);
            if (stateField == null) continue;
            try {
                stateField.setAccessible(true);
                Object state = stateField.get(item);
                if (state == null || !stateCls.isInstance(state)) continue;
                unavailable++;
                String did = asString(HookUtil.readFieldQuiet(state, F_STATE_DID));
                if (!Consts.isSimulatedDid(did)) continue;
                SimDevice dev = cfg.findEnabledByDid(did);
                if (dev == null) continue;
                Object bean = DeviceBeanFactory.deviceFor(DeviceListHook.context(), dev,
                        DeviceListHook.specOf(dev), DeviceListHook.stamp(cfg));
                if (bean == null) continue;
                Object fresh = stateCtor.newInstance(bean);
                stateField.set(item, fresh);
                fixed++;
            } catch (Throwable t) {
                Log.v("单条状态修复失败: " + HookUtil.shortMsg(t));
            }
        }
        if (fixed > 0) {
            Log.d("场景条目状态已修复 " + fixed + " 条（共 " + unavailable + " 个不可用状态）");
        }
    }

    /** 场景对象里所有 List 字段里的条目（不预设 triggers/conditions/actions 的字段名）。*/
    private static List<Object> flatItems(Object scene) {
        List<Object> out = new ArrayList<>();
        Class<?> c = scene.getClass();
        while (c != null && c != Object.class) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(scene);
                    if (!(v instanceof Iterable)) continue;
                    for (Object o : (Iterable<?>) v) {
                        if (o != null) out.add(o);
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}