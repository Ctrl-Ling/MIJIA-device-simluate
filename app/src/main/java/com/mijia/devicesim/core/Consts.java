/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-10 10:35:40 +08:00
 */
package com.mijia.devicesim.core;

/**
 * 全局常量。这个类会被米家进程加载，所以只放字符串和纯函数，不能碰任何 Android 组件。
 */
public final class Consts {

    private Consts() {
    }

    /** 注入目标：米家。*/
    public static final String TARGET_PACKAGE = "com.xiaomi.smarthome";
    /** 本模块自身包名，用于 LSPosed 里的「模块是否生效」自挂检测。*/
    public static final String MODULE_PACKAGE = "com.mijia.devicesim";
    /** logcat / LSPosed 日志里的统一前缀，方便 {@code logcat | grep} 过滤。*/
    public static final String LOG_TAG = "MijiaSim";

    public static final String PROVIDER_AUTHORITY = "com.mijia.devicesim.provider";
    public static final String PROVIDER_BASE = "content://" + PROVIDER_AUTHORITY;

    /** 配置：设备清单（唯一数据源，界面写、Hook 读）。*/
    public static final String PATH_CONFIG = "/config";
    /** 运行时属性状态：每个假设备当前 siid:piid 取值。*/
    public static final String PATH_STATE = "/state";
    /** 型号 spec 库：model -> MIoT spec JSON。*/
    public static final String PATH_SPEC = "/spec";
    /** 钩子命中报告：哪些点挂上了、哪些没挂上。*/
    public static final String PATH_REPORT = "/report";
    /** 最近控制调用记录（get/set/action）。*/
    public static final String PATH_CALLLOG = "/calllog";

    /**
     * 无 root 场景的兜底目录。Magisk 环境下用户可以用 adb/终端把同一份 JSON 放进去，
     * 读的时候优先这个目录，界面写的时候写 Provider。
     */
    public static final String ROOT_DIR = "/data/local/tmp/mijia_devicesim";

    /* ---------- Provider call 方法名 ---------- */
    public static final String CALL_PING = "ping";
    public static final String CALL_PUT_CONFIG = "putConfig";
    public static final String CALL_GET_CONFIG = "getConfig";
    public static final String CALL_PUT_STATE = "putState";
    public static final String CALL_GET_STATE = "getState";
    public static final String CALL_PUT_SPEC = "putSpec";
    public static final String CALL_GET_SPEC = "getSpec";
    public static final String CALL_LIST_SPEC = "listSpec";
    public static final String CALL_DELETE_SPEC = "deleteSpec";
    public static final String CALL_PUT_REPORT = "putReport";
    public static final String CALL_GET_REPORT = "getReport";
    public static final String CALL_PUT_CALLLOG = "putCallLog";
    public static final String CALL_GET_CALLLOG = "getCallLog";

    /* ---------- 假设备身份约定 ---------- */
    /**
     * 保留 did 段：真实米家 did 是 10~11 位纯数字且不会以 "9" 开头（9 段留给本项目），
     * 再叠加 extra 里的 {@link #EXTRA_SIMULATED} 标记位，云端下发的同 did 设备不会互相误伤。
     */
    public static final String SIM_DID_PREFIX = "9";
    public static final int SIM_DID_LENGTH = 10;
    /** Device.extra / propInfo 里标记「这是我造的假设备」。*/
    public static final String EXTRA_SIMULATED = "mijiaSimulated";
    /** 配置里保存的 token 段：miio 设备需要 32 位 hex，本地生成即可。*/
    public static final int TOKEN_LENGTH = 32;

    /** 属性状态 map 的键格式：siid:piid。*/
    public static final String KEY_SEPARATOR = ":";
    /** 动作调用记录键格式：siid:aiid。*/
    public static final String ACTION_SEPARATOR = ":";

    /* ---------- 设备接入类型（配置层，和米家 pid 的映射在 hook/DeviceBeanFactory）---------- */
    public static final String TYPE_WIFI = "wifi";
    public static final String TYPE_BLE = "ble";
    public static final String TYPE_BLE_MESH = "blemesh";
    public static final String TYPE_ZIGBEE_SUB = "zigbee";
    public static final String TYPE_GATEWAY = "gateway";

    /* ---------- 宿主本地镜像（last-known-good）---------- */
    /**
     * 配置镜像的文件名。写在「当前进程自己的」files 目录里：
     * 跑在米家进程时就是米家的目录，跑在本模块界面进程时就是模块的目录，
     * 两个进程各留各的，谁也不碰谁的数据，SELinux 一行都不用改。
     * 用途只有一个：MIUI 的自启动管控拦住米家拉起本模块 Provider 时，
     * 冷启动仍然能拿到上一次成功读到的配置，不至于整场空转。
     */
    public static final String MIRROR_CONFIG = "mijia_devicesim_config.json";
    /** 属性状态镜像，同上。*/
    public static final String MIRROR_STATE = "mijia_devicesim_state.json";

    /* ---------- 配置读取来源（界面与报告都用这套可见文案的键）---------- */
    /** 来源：adb/root 手工灌的 /data/local/tmp 文件。*/
    public static final String SOURCE_ROOT = "root";
    /** 来源：本模块 Provider（跨进程实时读）。*/
    public static final String SOURCE_PROVIDER = "provider";
    /** 来源：宿主进程里的本地镜像（Provider 当时不可达）。*/
    public static final String SOURCE_MIRROR = "mirror";
    /** 来源：什么都没读到，按空配置放行真实逻辑。*/
    public static final String SOURCE_NONE = "none";

    public static boolean isSimulatedDid(String did) {
        if (did == null || did.length() != SIM_DID_LENGTH) return false;
        if (did.charAt(0) != '9') return false;
        for (int i = 0; i < did.length(); i++) {
            char c = did.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static String propKey(int siid, int piid) {
        return siid + KEY_SEPARATOR + piid;
    }

    public static String actionKey(int siid, int aiid) {
        return siid + ACTION_SEPARATOR + aiid;
    }
}
