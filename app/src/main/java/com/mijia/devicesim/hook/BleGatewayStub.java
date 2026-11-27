/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-27 09:14:44 +08:00
 */
package com.mijia.devicesim.hook;

import com.mijia.devicesim.core.ConfigRepository;
import com.mijia.devicesim.core.Consts;
import com.mijia.devicesim.core.Log;
import com.mijia.devicesim.core.SimDevice;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 蓝牙网关「已连接」应答层：让走网关的假设备（子擎存在传感器、人体传感器 2……）
 * 在 RN 插件页里显示成「已连接蓝牙网关 / 设备正常」，而不是「未连接蓝牙网关」。
 *
 * <p>为什么是这两个方法（直接从插件 bundle 里读出来的调用链，不是猜的）：
 * <pre>
 * // 设备卡片/详情页 SDK（mhuirn）：
 * _miot.Bluetooth.isBleOrMeshGatewayConnected(Device.mac, true).then(function (gatewayStatus) {
 *   var gatewayConnected = gatewayStatus && gatewayStatus.code === 0
 *                          && gatewayStatus.data && gatewayStatus.data.connected;
 *   ...
 * });
 *
 * // 旧插件（开关面板等）：
 * _miot.Bluetooth.isBleGatewayConnected(Device.mac).then(function (res) {
 *   callback(res === 0 || res === true);   // 0 = 已连接
 * });
 * </pre>
 *
 * <p>所以网关状态只有两个出口，形状也都是现成的（见 classes11 dex 反编译）：
 * <ul>
 *   <li>{@code isBleOrMeshGatewayConnected(String mac, boolean, Callback)}
 *       —— 成功时回调 {@code (Boolean.TRUE, {code:0, data:{connected:true}})}；</li>
 *   <li>{@code isBleGatewayConnected(String mac, Callback)} —— 回调整数 {@code 0}（JS 判 0 即连上）。</li>
 * </ul>
 * 再加 tiny 插件版的 {@code isBleOrMeshGatewayConnectedByDid(String did, boolean, Callback, String mac)}。
 *
 * <p>只认配置文件里 {@code enabled} 且 mac（或 did）对得上的假设备；真设备的 mac 一个字节都不碰，
 * 原方法照常走云端 / 本地蓝牙栈。
 */
public final class BleGatewayStub {

    private static final String GROUP = "ble-gateway";

    /** RN 插件（大插件）原生模块。*/
    private static final String CL_CORE =
            "com.xiaomi.smarthome.framework.plugin.rn.nativemodule.MIOTBluetoothModuleCore";
    /** 轻量插件（tiny）原生模块，接口同款但多了 by-did 版本。*/
    private static final String CL_TINY =
            "com.xiaomi.smarthome.framework.plugin.tiny.nativemodule.MIOTBluetoothModuleTiny";

    private static volatile boolean sInstalled;

    /** 同一条「设备+方法」只打一条日志，别把日志刷爆。*/
    private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();

    private BleGatewayStub() {
    }

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstalled) return;
        sInstalled = true;

        int n = 0;
        for (String cls : new String[]{CL_CORE, CL_TINY}) {
            // 新 SDK（mhuirn 卡片/详情页）走这条：{code:0,data:{connected:true}}
            n += HookSniper.hook(lpparam.classLoader, GROUP, cls,
                    new String[]{"isBleOrMeshGatewayConnected"},
                    HookSniper.Shape.of(null, "String", "boolean", "Callback"), GATEWAY_CONNECTED);
            // 老插件面板走这条：整数 0 = 已连接
            n += HookSniper.hook(lpparam.classLoader, GROUP, cls,
                    new String[]{"isBleGatewayConnected"},
                    HookSniper.Shape.of(null, "String", "Callback"), GATEWAY_CODE);
        }
        // tiny 版按 did 查询（页面拿不到 mac 时走这里）
        n += HookSniper.hook(lpparam.classLoader, GROUP, CL_TINY,
                new String[]{"isBleOrMeshGatewayConnectedByDid"},
                HookSniper.Shape.of(null, "String", "boolean", "Callback", "String"), GATEWAY_CONNECTED_BY_DID);

        Log.d("蓝牙网关应答安装: " + n + " 个方法");
    }

    /* ---------- 三个回调 ---------- */

    /** {@code isBleOrMeshGatewayConnected(mac, flag, cb)}：回「已连接」。*/
    private static final XC_MethodHook GATEWAY_CONNECTED = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 3) return;
                String mac = asString(param.args[0]);
                Object cb = param.args[2];
                if (!isSimMac(mac)) return; // 真设备：原样走
                Object body = connectedMap(param);
                if (body == null) return;
                if (invoke(cb, Boolean.TRUE, body)) {
                    param.setResult(null);
                    logOnce("isBleOrMeshGatewayConnected", mac);
                }
            } catch (Throwable t) {
                Log.w("蓝牙网关应答异常: " + HookUtil.shortMsg(t));
            }
        }
    };

    /** {@code isBleOrMeshGatewayConnectedByDid(did, flag, cb, mac)}：同上，只是按 did 认。*/
    private static final XC_MethodHook GATEWAY_CONNECTED_BY_DID = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 3) return;
                String did = asString(param.args[0]);
                Object cb = param.args[2];
                if (!isSimDid(did)) return;
                Object body = connectedMap(param);
                if (body == null) return;
                if (invoke(cb, Boolean.TRUE, body)) {
                    param.setResult(null);
                    logOnce("isBleOrMeshGatewayConnectedByDid", did);
                }
            } catch (Throwable t) {
                Log.w("蓝牙网关应答异常(byDid): " + HookUtil.shortMsg(t));
            }
        }
    };

    /** {@code isBleGatewayConnected(mac, cb)}：回整数 0（JS 判 0 = 已连接）。*/
    private static final XC_MethodHook GATEWAY_CODE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                String mac = asString(param.args[0]);
                Object cb = param.args[1];
                if (!isSimMac(mac)) return;
                if (invoke(cb, Integer.valueOf(0))) {
                    param.setResult(null);
                    logOnce("isBleGatewayConnected", mac);
                }
            } catch (Throwable t) {
                Log.w("蓝牙网关应答异常(code): " + HookUtil.shortMsg(t));
            }
        }
    };

    /* ---------- 假设备识别 ---------- */

    private static boolean isSimDid(String did) {
        return Consts.isSimulatedDid(did) && ConfigRepository.current().findEnabledByDid(did) != null;
    }

    /** mac 按「只留十六进制、转小写」比对；配置里启用中的设备才算。*/
    private static boolean isSimMac(String mac) {
        String norm = normalizeMac(mac);
        if (norm.isEmpty()) return false;
        for (SimDevice d : ConfigRepository.current().devices) {
            if (d == null || !d.enabled) continue;
            if (norm.equals(normalizeMac(d.mac))) return true;
        }
        return false;
    }

    private static String normalizeMac(String mac) {
        if (mac == null) return "";
        StringBuilder sb = new StringBuilder(mac.length());
        for (int i = 0; i < mac.length(); i++) {
            char c = mac.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (c >= 'a' && c <= 'f') sb.append(c);
            else if (c >= 'A' && c <= 'F') sb.append((char) (c + 32));
        }
        return sb.toString();
    }

    /* ---------- RN 回包 ---------- */

    /**
     * 造 {@code {code:0, data:{connected:true}}}。
     *
     * <p>模块没有 com.facebook.react 的编译依赖（stubs 是 compileOnly，运行时由米家提供），
     * 所以统一按名字反射；参数类型必须精确取 {@code ReadableMap} 接口，否则 getMethod 找不到 putMap。
     */
    private static Object connectedMap(XC_MethodHook.MethodHookParam param) {
        try {
            ClassLoader loader = param.method.getDeclaringClass().getClassLoader();
            Class<?> argsCls = Class.forName("com.facebook.react.bridge.Arguments", true, loader);
            Class<?> writable = Class.forName("com.facebook.react.bridge.WritableMap", true, loader);
            Class<?> readable = Class.forName("com.facebook.react.bridge.ReadableMap", true, loader);

            Object inner = argsCls.getMethod("createMap").invoke(null);
            writable.getMethod("putBoolean", String.class, boolean.class).invoke(inner, "connected", true);

            Object outer = argsCls.getMethod("createMap").invoke(null);
            writable.getMethod("putInt", String.class, int.class).invoke(outer, "code", 0);
            writable.getMethod("putMap", String.class, readable).invoke(outer, "data", inner);
            return outer;
        } catch (Throwable t) {
            Log.w("蓝牙网关回包拼装失败: " + HookUtil.shortMsg(t));
            return null;
        }
    }

    /** 按 RN 回调约定 {@code invoke(Object[])} 回值；回不去返回 false 让原方法继续跑。*/
    private static boolean invoke(Object callback, Object... values) {
        if (callback == null) return false;
        try {
            Method m = callback.getClass().getMethod("invoke", Object[].class);
            m.setAccessible(true);
            m.invoke(callback, (Object) values);
            return true;
        } catch (Throwable t) {
            Log.w("蓝牙网关应答 invoke 失败: " + HookUtil.shortMsg(t));
            return false;
        }
    }

    private static String asString(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static void logOnce(String api, String key) {
        if (key == null) return;
        if (!sLogged.add(api + "|" + normalizeMac(key))) return;
        Log.d("蓝牙网关本地应答 " + api + " → 已连接 (" + key + ")");
    }
}