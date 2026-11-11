/*
 * Author: Ctrl-Ling
 * Last Modified By: Ctrl-Ling
 * Last Modified At: 2026-11-11 10:45:04 +08:00
 */
package com.mijia.devicesim.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MIoT spec 里那些英文标识的中文说法。
 *
 * <p>spec 的 {@code description} 只有英文（官方没有中文 i18n 接口），但属性的
 * {@code type} 第三段是一个稳定的英文 slug（比如 {@code switch-status}）。
 * 这里按 slug 查一张人工整理的对照表，命中就给「中文名」，没命中才退回英文原文。
 *
 * <p>三层兜底，命中率由高到低：
 * <ol>
 *   <li>{@link #PROPS}：常见属性的整词对照（最准，如 mop-water-output-level → 拖布水量档位）；</li>
 *   <li>词表拼装：把英文描述切成词，按词表逐词翻译（未知 slug 也能看懂，
 *       如 Vacuum Frameware Version → 扫地机固件版本）；</li>
 *   <li>原文：连词都认不全就保留英文，至少不会翻错。</li>
 * </ol>
 */
public final class MiotLabels {

    private MiotLabels() {
    }

    private static final Map<String, String> PROPS = new HashMap<>();
    private static final Map<String, String> VALUES = new HashMap<>();
    private static final Map<String, String> WORDS = new HashMap<>();
    /** 设备类型（urn 第三段）→ 中文，只放高频的，其余用词表拼。*/
    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("light", "灯");
        TYPES.put("switch", "开关");
        TYPES.put("plug", "插座");
        TYPES.put("vacuum", "扫地机器人");
        TYPES.put("air-conditioner", "空调");
        TYPES.put("airpurifier", "空气净化器");
        TYPES.put("air-purifier", "空气净化器");
        TYPES.put("humidifier", "加湿器");
        TYPES.put("fan", "风扇");
        TYPES.put("heater", "取暖器");
        TYPES.put("sensor", "传感器");
        TYPES.put("camera", "摄像机");
        TYPES.put("curtain", "窗帘");
        TYPES.put("lock", "门锁");
        TYPES.put("gateway", "网关");
        TYPES.put("kettle", "电水壶");
        TYPES.put("cooker", "电饭煲");
        TYPES.put("washer", "洗衣机");
        TYPES.put("dryer", "烘干机");
        TYPES.put("mosquito-dispeller", "驱蚊器");
        TYPES.put("walkingpad", "走步机");
    }

    /**
     * 设备类型的中文说法：有中文就给「中文(英文)」，没有就单给英文
     * （型号库的 spec 摘要用，别让人对着一个光秃秃的 switch 猜）。
     */
    public static String typeLabel(String slug) {
        if (slug == null || slug.isEmpty()) return "";
        String cn = TYPES.get(slug.toLowerCase(Locale.ROOT));
        if (cn == null) cn = phrase(slug);
        if (cn == null || cn.isEmpty() || cn.equalsIgnoreCase(slug)) return slug;
        return cn + "(" + slug + ")";
    }

    static {
        // ---- 设备信息 ----
        put("manufacturer", "制造商");
        put("model", "型号");
        put("name", "名称");
        put("serial-number", "设备序列号");
        put("serial-no", "序列号");
        put("firmware-revision", "固件版本");
        put("vacuum-frameware-version", "扫地机固件版本");
        put("hardware-version", "硬件版本");
        put("wifi-ssid", "Wi-Fi 名称");
        put("wifi-rssi", "Wi-Fi 信号强度");
        put("mac", "MAC 地址");
        put("identify", "识别（响铃）");

        // ---- 开关 / 基础 ----
        put("on", "开关");
        put("off", "关闭");
        put("on-off", "开关");
        put("switch-status", "开关状态");
        put("toggle", "翻转");
        put("fault", "故障码");
        put("fault-ids", "故障码列表");
        put("status", "状态");
        put("mode", "模式");
        put("button", "按键");
        put("button-type", "按键类型");
        put("count", "次数");
        put("delay-off", "延时关闭");
        put("action-after-finish", "完成后动作");
        put("action-result", "执行结果");
        put("language", "语言");
        put("heartbeat", "心跳");
        put("avoid-incodeletion", "防误删");
        put("default-power-on-state", "上电默认状态");
        put("poweroff-memory", "断电记忆");
        put("charge-protect", "过充保护");
        put("charge-protect-thre", "充电保护门槛");
        put("physical-controls-locked", "按键锁");
        put("current-physical-control-lock", "当前按键锁状态");
        put("disable-btn", "按键禁用");
        put("not-disturb-switch", "勿扰模式");
        put("no-disturb", "勿扰模式");
        put("current-no-disturb", "当前勿扰状态");
        put("enable-time-period", "生效时间段");
        put("child-lock", "童锁");
        put("lock", "锁");
        put("mute", "静音");
        put("volume", "音量");
        put("current-time", "当前时间");
        put("countdown-time", "倒计时");
        put("target-voice", "目标语音包");
        put("cur-voice", "当前语音包");
        put("download-status", "下载状态");
        put("download-progress", "下载进度");
        put("voice-url", "语音包地址");
        put("trigger-source", "触发来源");

        // ---- 灯 ----
        put("brightness", "亮度");
        put("color-temperature", "色温");
        put("color", "颜色");
        put("saturability", "饱和度");
        put("flow", "流光效果");
        put("bright-scene", "明亮场景");
        put("ct-scene", "色温场景");
        put("night-close-enable", "夜间关闭指示灯");
        put("indicator-light", "指示灯");
        put("flash-light", "补光灯");

        // ---- 环境 / 传感器 ----
        put("temperature", "温度");
        put("target-temperature", "目标温度");
        put("relative-humidity", "相对湿度");
        put("atmospheric-pressure", "气压");
        put("pm2.5-density", "PM2.5 浓度");
        put("pm10-density", "PM10 浓度");
        put("co2-density", "CO₂ 浓度");
        put("tvoc-density", "TVOC 浓度");
        put("air-quality-index", "空气质量指数");
        put("air-quality", "空气质量");
        put("battery-level", "电量");
        put("voltage", "电压");
        put("electric-power", "功率");
        put("power-consumption", "耗电量");
        put("power-consumption-accumulation-way", "电量统计方式");
        put("power-change-abs", "功率变化绝对值");
        put("power-change-per", "功率变化百分比");
        put("power-overload", "功率过载");
        put("temperature-alarm", "温度报警");
        put("power-alarm", "功率报警");
        put("alarm", "报警");
        put("abnormal-condition", "异常情况");
        put("operation-method", "操作方式");
        put("operation-id", "操作 ID");
        put("door-state", "门状态");
        put("alarm-volume-basic", "基础报警音量");
        put("alarm-volume-home", "在家报警音量");
        put("alarm-volume-away", "离家报警音量");
        put("alarm-volume-sleep", "睡眠报警音量");
        put("alarm-duration-basic", "基础报警时长");
        put("alarm-duration-home", "在家报警时长");
        put("alarm-duration-away", "离家报警时长");
        put("alarm-duration-sleep", "睡眠报警时长");
        put("delay-time-basic", "基础警戒延时");
        put("delay-time-home", "在家警戒延时");
        put("delay-time-away", "离家警戒延时");
        put("delay-time-sleep", "睡眠警戒延时");
        put("arming-mode", "警戒模式");
        put("target-arming-mode", "目标警戒模式");
        put("arming-change", "警戒模式切换");
        put("alarm-auto-log", "报警自动记录");

        // ---- 清洁（扫地 / 洗地机器人）----
        put("fan-level", "风速档位");
        put("motor-control", "电机控制");
        put("current-position", "当前位置");
        put("target-position", "目标位置");
        put("horizontal-angle", "水平角度");
        put("vertical-angle", "垂直角度");
        put("horizontal-swing", "左右摆风");
        put("vertical-swing", "上下摆风");
        put("left-time", "剩余时间");
        put("drying-level", "烘干档位");
        put("drying-time", "烘干时长");
        put("drying-progress", "烘干进度");
        put("dry-left-time", "烘干剩余时间");
        put("dryer", "烘干");
        put("airer", "晾衣架");
        put("target-water-level", "目标水位");
        put("water-level", "水位");
        put("charging-state", "充电状态");
        put("filter-life-level", "滤芯寿命");
        put("filter-left-time", "滤芯剩余时间");
        put("brush-life-level", "主刷寿命");
        put("brush-left-time", "主刷剩余时间");
        put("roller-life-level", "滚刷寿命");
        put("roller-left-time", "滚刷剩余时间");
        put("roller-outswing", "滚刷伸缩");
        put("auto-wash-roller", "自动清洗滚刷");
        put("mop-status", "拖布状态");
        put("clean-area", "清扫面积");
        put("clean-time", "清扫时间");
        put("clean-times", "清扫遍数");
        put("cleaning-area", "清扫面积");
        put("cleaning-time", "清扫时长");
        put("cleaning-progress", "清扫进度");
        put("total-clean-area", "累计清扫面积");
        put("total-clean-time", "累计清扫时间");
        put("total-clean-count", "累计清扫次数");
        put("current-clean-record", "当前清扫记录");
        put("clean-record", "清扫记录");
        put("last-clean-time", "上次清扫时间");
        put("statistical-clean-area", "统计清扫面积");
        put("statistical-cleaning-times", "统计清扫次数");
        put("statistical-cleaning-duration", "统计清扫时长");
        put("points", "地图点数据");
        put("direction-key", "方向键");
        put("common-params", "通用参数");
        put("sweep-mop-type", "扫拖类型");
        put("sweep-type", "清扫类型");
        put("sweep-route", "清扫路线");
        put("sweep-break-switch", "断点续扫");
        put("sweep-mop-status", "扫拖状态");
        put("sweep-ai-detection", "AI 智能识别");
        put("sweep-ai-object", "AI 识别物体");
        put("sweep-furniture", "家具识别数据");
        put("sweep-params", "清扫参数");
        put("sweep-carpet-first", "地毯优先清扫");
        put("mop-water-output-level", "拖布水量档位");
        put("mop-water-output-level-no-tank", "拖布水量档位（无水箱）");
        put("water-output-for-washing-mop", "洗拖布水量");
        put("frequency-mop-wash", "洗拖布频率");
        put("frequency-mop-wash-no-tank", "洗拖布频率（无水箱）");
        put("frequency-mop-wash-by-time", "定时洗拖布间隔");
        put("enable-mop-wash", "自动洗拖布");
        put("repeat-wash-mop", "重复洗拖布");
        put("hot-water-mop-wash", "热水洗拖布");
        put("wash-mop-water-temperature", "洗拖布水温");
        put("mop-temperature", "拖布水温");
        put("mop-auto-lift", "拖布自动抬升");
        put("mop-outer-swing-frequency", "拖布外摆频率");
        put("auto-mop-dry", "自动烘干拖布");
        put("auto-dust-arrest", "自动集尘");
        put("dust-arrest-frequency", "集尘频率");
        put("auto-water-change", "自动换水");
        put("auto-water-change-installed", "自动换水已安装");
        put("use-detergent", "使用清洁液");
        put("detergent-left-level", "清洁液余量");
        put("detergent-left-time", "清洁液剩余时间");
        put("detergent-self-delivery", "自动添加清洁液");
        put("detergent-self-delivery-level", "清洁液添加档位");
        put("detergent-depletion-reminder", "清洁液不足提醒");
        put("dust-bag-life-level", "尘袋寿命");
        put("dust-bag-left-time", "尘袋剩余时间");
        put("carpet-boost", "地毯增压");
        put("carpet-avoidance", "避让地毯");
        put("carpet-display", "地毯显示");
        put("carpet-object", "地毯识别数据");
        put("carpet-cleaning-method", "地毯清洁方式");
        put("carpet-deep-cleaning", "地毯深度清洁");
        put("carpet-discriminate", "地毯识别");
        put("obstacle-avoidance-strategy", "避障策略");
        put("object-detection", "物体识别");
        put("dirt-detection", "脏污识别");
        put("room-detection", "房间识别");
        put("edge-sweep-frequency", "沿边清扫频率");
        put("worry-free-clean-mode", "省心清洁模式");
        put("base-station-working-status", "基站工作状态");
        put("base-station-water-tank-status", "基站水箱状态");
        put("host-water-tank-status", "主机水箱状态");
        put("sewage-tank-status", "污水箱状态");
        put("water-tank-status", "水箱状态");
        put("water-check-list", "水箱检测列表");
        put("water-check-status", "水箱检测状态");
        put("water-level-remind", "缺水提醒");
        put("sleep-status", "休眠状态");
        put("location-status", "定位状态");
        put("map-complete-dialog", "建图完成弹窗");
        put("exit-remote-dialog", "退出遥控弹窗");
        put("order-clean", "预约清扫");
        put("vacuum-route", "清扫轨迹");
        put("zone-ids", "选区 ID");
        put("restricted-sweep-areas", "禁区");
        put("restricted-walls", "虚拟墙");
        put("vacuum-room-ids", "房间 ID");
        put("room-information", "房间信息");
        put("current-cleaning-config", "当前清扫配置");
        put("user-define-sweep-cfg", "自定义清扫配置");
        put("user-define-sweep-id", "自定义清扫 ID");
        put("plugin-info-remind", "插件提示");
        put("notice", "提示信息");
        put("real-scene-capture", "实景拍照");
        put("auto-night-fill-light", "夜间自动补光");
        put("ai-wake-up", "AI 语音唤醒");
        put("brush-outswing", "边刷伸缩");
        put("sill", "门槛数据");
        put("furniture-for-automation", "自动化家具");
        put("map-hidden-zone", "地图隐藏区域");
        put("cmd", "指令");
        put("flag", "标志位");
        put("map-obj-name", "地图对象名");
        put("trajectory-obj-name", "轨迹对象名");
        put("vacuum-position", "扫地机位置");
        put("map-management", "地图管理");
        put("current-map-id", "当前地图 ID");
        put("carpet-obj-name", "地毯对象名");
        put("map-3d-info", "3D 地图信息");
        put("map-switch-method", "地图切换方式");
        put("map-upload-stop", "地图上传暂停");
        put("map-upload-stop-action-result", "上传暂停结果");
        put("backup-map-list", "备份地图列表");
        put("restore-map-result", "恢复地图结果");
        put("cloud-clean-record", "云端清扫记录");
        put("map-room-info", "地图房间信息");
        put("map-object-info", "地图物体信息");
        put("map-carpet-info", "地图地毯信息");
        put("self-check-items", "自检项目");
        put("self-check-results", "自检结果");
        put("precise-diagnosis", "精准诊断");
        put("lidar-status", "雷达升降状态");
        put("lidar-button-type", "雷达按键类型");
        put("low-region-strategy", "低矮区域策略");
        put("query-id", "查询 ID");
        put("updata-difference", "增量更新数据");
        put("spec-test", "spec 测试");
        put("get-bit-status", "位状态查询");
        put("get-check-list", "检测列表查询");
        put("video-recording-warnings", "录像提示方式");
        put("visible-to-shared-users", "对共享用户可见");
        put("log-str", "日志");

        // ---- 洗衣机 / 厨电 ----
        put("rinsh-time", "漂洗时间");
        put("rinsh-times", "漂洗次数");
        put("soak-time", "浸泡时间");
        put("spin-speed", "脱水转速");
        put("spin-time", "脱水时间");
        put("wash-time", "洗涤时间");
        put("heat-level", "加热档位");
        put("auto-keep-warm", "自动保温");
        put("cook-mode", "烹饪模式");
        put("eco", "节能");
        put("anion", "负离子");
        put("heater", "加热");
        put("sleep-mode", "睡眠模式");
        put("customize-mode-1", "自定义模式 1");
        put("customize-mode-2", "自定义模式 2");
        put("customize-mode-3", "自定义模式 3");
        put("customize-mode-4", "自定义模式 4");
        put("customize-mode-5", "自定义模式 5");
        put("customize-mode-6", "自定义模式 6");

        // ---- 值（英文 -> 中文），key 会自动切词归一 ----
        putValue("on", "开");
        putValue("off", "关");
        putValue("open", "打开");
        putValue("close", "关闭");
        putValue("closed", "已关闭");
        putValue("open/close", "打开/关闭");
        putValue("turn off", "关闭");
        putValue("turn on", "开启");
        putValue("auto", "自动");
        putValue("automatic", "自动");
        putValue("manual", "手动");
        putValue("fixed", "固定");
        putValue("pause", "暂停");
        putValue("paused", "已暂停");
        putValue("idle", "空闲");
        putValue("standby", "待机");
        putValue("busy", "工作中");
        putValue("working", "工作中");
        putValue("finished", "已完成");
        putValue("error", "故障");
        putValue("normal", "正常");
        putValue("low", "低");
        putValue("middle", "中");
        putValue("medium", "中");
        putValue("high", "高");
        putValue("max", "最大");
        putValue("min", "最小");
        putValue("silence", "静音");
        putValue("silent", "静音");
        putValue("quiet", "安静");
        putValue("low level", "低档");
        putValue("mid level", "中档");
        putValue("high level", "高档");
        putValue("basic", "标准");
        putValue("strong", "强力");
        putValue("full speed", "全力");
        putValue("charging", "充电中");
        putValue("break charging", "断续充电");
        putValue("go charging", "回充");
        putValue("go wash", "去洗拖布");
        putValue("not charging", "未充电");
        putValue("not-charging", "未充电");
        putValue("not chargeable", "不可充电");
        putValue("charged", "已充满");
        putValue("charging completely", "充电完成");
        putValue("charging competely", "充电完成");
        putValue("sweeping", "清扫中");
        putValue("mopping", "拖地中");
        putValue("sweeping and mopping", "扫拖中");
        putValue("auto-clean", "自动清扫");
        putValue("spot-clean", "定点清扫");
        putValue("wallflow-clean", "沿边清扫");
        putValue("returning", "回充中");
        putValue("charging dock", "充电桩");
        putValue("building map", "建图中");
        putValue("mapping pause", "建图暂停");
        putValue("updating", "升级中");
        putValue("self checking", "自检中");
        putValue("summoning", "召唤中");
        putValue("station working", "基站工作中");
        putValue("multi task station working", "多功能基站工作中");
        putValue("no faults", "无故障");
        putValue("no-faults", "无故障");
        putValue("nofault", "无故障");
        putValue("faults", "存在故障");
        putValue("none", "无");
        putValue("default", "默认");
        putValue("stay", "保持");
        putValue("recover before", "恢复原状态");
        putValue("increase", "增加");
        putValue("decrease", "减少");
        putValue("color", "彩色");
        putValue("day", "白天");
        putValue("night", "夜间");
        putValue("cool", "制冷");
        putValue("heat", "制热");
        putValue("dry", "除湿");
        putValue("hot", "温水");
        putValue("warm", "温热");
        putValue("fan", "送风");
        putValue("enabled", "开启");
        putValue("disabled", "关闭");
        putValue("true", "开");
        putValue("false", "关");
        putValue("unknown", "未知");
        putValue("ok", "正常");
        putValue("excellent", "优");
        putValue("fine", "良");
        putValue("low battery", "低电量");
        putValue("over temperature", "温度过高");
        putValue("overload", "过载");
        putValue("overload and overheat", "过载且过热");
        putValue("reset", "重置");
        putValue("global", "全局清扫");
        putValue("zone", "选区清扫");
        putValue("area", "划区清扫");
        putValue("edge", "沿边清扫");
        putValue("point", "定点清扫");
        putValue("custom area", "自定义区域");
        putValue("appointment", "预约清扫");
        putValue("linkage", "联动触发");
        putValue("fast", "快速");
        putValue("quick", "快速");
        putValue("daily", "日常");
        putValue("careful", "精细");
        putValue("ai hosting", "AI 托管");
        putValue("sweep", "扫地");
        putValue("sweep mop", "扫拖");
        putValue("sweep before mopping", "先扫后拖");
        putValue("one time", "1 遍");
        putValue("two time", "2 遍");
        putValue("three time", "3 遍");
        putValue("by room", "按房间");
        putValue("by area", "按面积");
        putValue("by time", "按时间");
        putValue("deep", "深度");
        putValue("save water", "省水");
        putValue("once", "1 次");
        putValue("twice", "2 次");
        putValue("triple", "3 次");
        putValue("self adaption", "自适应");
        putValue("avoid", "避让");
        putValue("ignore", "忽略");
        putValue("span", "跨越");
        putValue("less collisions", "少碰撞");
        putValue("high coverage", "高覆盖");
        putValue("not downloaded", "未下载");
        putValue("downloading", "下载中");
        putValue("downloaded", "已下载");
        putValue("download fail", "下载失败");
        putValue("apply success", "应用成功");
        putValue("apply fail", "应用失败");
        putValue("few", "少量");
        putValue("standard", "标准");
        putValue("many", "较多");
        putValue("lots of", "大量");
        putValue("not full", "未满");
        putValue("full", "已满");
        putValue("not empty", "非空");
        putValue("empty", "已空");
        putValue("empting", "排水/注水中");
        putValue("empting completed", "排水/注水完成");
        putValue("empting failed", "排水/注水失败");
        putValue("not installed", "未安装");
        putValue("voice", "语音提示");
        putValue("light", "灯光提示");
        putValue("strong reminder", "强提醒");
        putValue("uncheck", "未检测");
        putValue("checking", "检测中");
        putValue("check success", "检测通过");
        putValue("check fail", "检测失败");
        putValue("skip cleaning", "跳过清扫");
        putValue("final cleaning", "收尾清扫");
        putValue("between cleaning", "中途清扫");
        putValue("call cleaning", "召唤清扫");
        putValue("global sweep", "全局清扫");
        putValue("room sweep", "房间清扫");
        putValue("zone sweep", "选区清扫");
        putValue("press up", "按压上升");
        putValue("lift up", "抬起");
        putValue("press left", "按压左移");
        putValue("lift left", "左抬");
        putValue("press right", "按压右移");
        putValue("lift right", "右抬");
        putValue("bottom", "下降到底");
        putValue("top", "上升到顶");
        putValue("lifting", "上升中");
        putValue("downing", "下降中");
        putValue("down", "下降");
        putValue("lift", "抬升");
        putValue("safe mode", "安全模式");
        putValue("extreme mode", "极限模式");
        putValue("7 sweeps", "每 7 次清扫");
        putValue("every time", "每次");
        putValue("ordinary temperature", "常温水");
        putValue("smart", "智能");
        putValue("silent clean", "安静清洁");
        putValue("deep clean", "深度清洁");
        putValue("general", "常规");
        putValue("butler", "管家模式");
        putValue("dry boiler", "烘干内胆");
        putValue("no boiler", "无内胆");
        putValue("keep warm", "保温");
        putValue("quick cook", "快速烹饪");
        putValue("fine cook", "精煮");
        putValue("cook congee", "煮粥");
        putValue("boiling", "煮沸中");
        putValue("insulation", "保温中");
        putValue("reserved", "预约中");
        putValue("quick wash", "快洗");
        putValue("daily wash", "日常洗");
        putValue("delicate wash", "轻柔洗");
        putValue("heavy wash", "强力洗");
        putValue("intensive", "强力");
        putValue("cotton", "棉织物");
        putValue("synthetic", "化纤");
        putValue("wool", "羊毛");
        putValue("shirt", "衬衫");
        putValue("jacket", "羽绒服");
        putValue("down coat", "羽绒服");
        putValue("underwear", "内衣");
        putValue("baby care", "婴儿护理");
        putValue("drum clean", "筒自洁");
        putValue("rinse", "漂洗");
        putValue("spin", "脱水");
        putValue("delay", "延时");
        putValue("user define", "自定义");
        putValue("ble outdoor", "蓝牙户外");
        putValue("low water", "水位低");
        putValue("water tank shortage", "缺水");
        putValue("no energy bar installed", "未装电池");
        putValue("grid voltage anomaly", "电网电压异常");
        putValue("offline", "离线");
        putValue("away arming", "离家警戒");
        putValue("home arming", "在家警戒");
        putValue("basic arming", "基础警戒");
        putValue("sleep arming", "睡眠警戒");
        putValue("shuimian", "睡眠警戒");
        putValue("shushi", "舒适模式");
        putValue("jieneng", "节能模式");
        putValue("boil", "烧水");
        putValue("coffee", "咖啡");
        putValue("milk", "牛奶");
        putValue("eoc", "充电完成");

        // ---- 词表：未知属性按词拼中文（只用于兜底）----
        putWord("device", "设备");
        putWord("vacuum", "扫地机");
        putWord("robot", "机器人");
        putWord("cleaner", "清洁机");
        putWord("status", "状态");
        putWord("state", "状态");
        putWord("mode", "模式");
        putWord("level", "档位");
        putWord("switch", "开关");
        putWord("version", "版本");
        putWord("firmware", "固件");
        putWord("frameware", "固件");
        putWord("information", "信息");
        putWord("manufacturer", "制造商");
        putWord("model", "型号");
        putWord("serial", "序列");
        putWord("number", "编号");
        putWord("no", "编号");
        putWord("time", "时间");
        putWord("times", "次数");
        putWord("duration", "时长");
        putWord("left", "剩余");
        putWord("target", "目标");
        putWord("current", "当前");
        putWord("total", "累计");
        putWord("statistical", "统计");
        putWord("last", "上次");
        putWord("area", "面积");
        putWord("cleaning", "清扫");
        putWord("clean", "清洁");
        putWord("sweep", "清扫");
        putWord("mop", "拖布");
        putWord("water", "水");
        putWord("tank", "水箱");
        putWord("output", "输出");
        putWord("wash", "清洗");
        putWord("washing", "清洗");
        putWord("drying", "烘干");
        putWord("dry", "烘干");
        putWord("dust", "尘");
        putWord("bag", "袋");
        putWord("box", "盒");
        putWord("arrest", "收集");
        putWord("brush", "刷");
        putWord("roller", "滚刷");
        putWord("filter", "滤芯");
        putWord("life", "寿命");
        putWord("carpet", "地毯");
        putWord("room", "房间");
        putWord("map", "地图");
        putWord("zone", "区域");
        putWord("zoned", "区域");
        putWord("restricted", "禁区");
        putWord("walls", "墙");
        putWord("wall", "墙");
        putWord("route", "路线");
        putWord("position", "位置");
        putWord("location", "定位");
        putWord("record", "记录");
        putWord("config", "配置");
        putWord("configuration", "配置");
        putWord("params", "参数");
        putWord("param", "参数");
        putWord("result", "结果");
        putWord("results", "结果");
        putWord("progress", "进度");
        putWord("frequency", "频率");
        putWord("enable", "启用");
        putWord("enabled", "启用");
        putWord("auto", "自动");
        putWord("self", "自动");
        putWord("base", "基站");
        putWord("station", "基站");
        putWord("host", "主机");
        putWord("sewage", "污水");
        putWord("clean water", "清水");
        putWord("detergent", "清洁液");
        putWord("storage", "存储");
        putWord("delivery", "投放");
        putWord("depletion", "不足");
        putWord("reminder", "提醒");
        putWord("remind", "提醒");
        putWord("notice", "提示");
        putWord("info", "信息");
        putWord("voice", "语音");
        putWord("download", "下载");
        putWord("url", "地址");
        putWord("user", "用户");
        putWord("define", "自定义");
        putWord("custom", "自定义");
        putWord("common", "通用");
        putWord("setting", "设置");
        putWord("settings", "设置");
        putWord("control", "控制");
        putWord("controls", "控制");
        putWord("locked", "锁定");
        putWord("lock", "锁");
        putWord("physical", "物理按键");
        putWord("obstacle", "障碍物");
        putWord("avoidance", "避障");
        putWord("strategy", "策略");
        putWord("detection", "识别");
        putWord("detect", "检测");
        putWord("object", "物体");
        putWord("objects", "物体");
        putWord("dirt", "脏污");
        putWord("sill", "门槛");
        putWord("threshold", "门槛");
        putWord("lidar", "激光雷达");
        putWord("imu", "陀螺仪");
        putWord("sensor", "传感器");
        putWord("check", "检测");
        putWord("test", "测试");
        putWord("diagnosis", "诊断");
        putWord("precise", "精准");
        putWord("error", "错误");
        putWord("fault", "故障");
        putWord("faults", "故障");
        putWord("alarm", "报警");
        putWord("volume", "音量");
        putWord("mute", "静音");
        putWord("battery", "电池");
        putWord("voltage", "电压");
        putWord("power", "功率");
        putWord("temperature", "温度");
        putWord("humidity", "湿度");
        putWord("brightness", "亮度");
        putWord("color", "颜色");
        putWord("light", "灯");
        putWord("fan", "风扇");
        putWord("swing", "摆风");
        putWord("angle", "角度");
        putWord("horizontal", "水平");
        putWord("vertical", "垂直");
        putWord("heater", "加热");
        putWord("heat", "加热");
        putWord("sleep", "睡眠");
        putWord("eco", "节能");
        putWord("anion", "负离子");
        putWord("door", "门");
        putWord("history", "记录");
        putWord("operation", "操作");
        putWord("method", "方式");
        putWord("trigger", "触发");
        putWord("source", "来源");
        putWord("data", "数据");
        putWord("list", "列表");
        putWord("ids", "ID");
        putWord("id", "ID");
        putWord("index", "指数");
        putWord("quality", "质量");
        putWord("air", "空气");
        putWord("density", "浓度");
        putWord("pressure", "气压");
        putWord("atmospheric", "大气");
        putWord("relative", "相对");
        putWord("washer", "洗衣机");
        putWord("rinsh", "漂洗");
        putWord("rinse", "漂洗");
        putWord("soak", "浸泡");
        putWord("spin", "脱水");
        putWord("speed", "速度");
        putWord("cook", "烹饪");
        putWord("cooker", "电饭煲");
        putWord("kettle", "热水壶");
        putWord("keep", "保持");
        putWord("warm", "保温");
        putWord("curtain", "窗帘");
        putWord("humidifier", "加湿器");
        putWord("gateway", "网关");
        putWord("arming", "布防");
        putWord("change", "切换");
        putWord("delay", "延时");
        putWord("away", "离家");
        putWord("home", "在家");
        putWord("basic", "基础");
        putWord("customize", "自定义");
        putWord("indicator", "指示灯");
        putWord("night", "夜间");
        putWord("flash", "闪光");
        putWord("visible", "可见");
        putWord("shared", "共享");
        putWord("users", "用户");
        putWord("video", "录像");
        putWord("recording", "录像");
        putWord("warnings", "提示");
        putWord("stream", "视频流");
        putWord("management", "管理");
        putWord("identify", "识别");
        putWord("heartbeat", "心跳");
        putWord("memory", "记忆");
        putWord("protect", "保护");
        putWord("charge", "充电");
        putWord("charging", "充电");
        putWord("discharge", "放电");
        putWord("power off", "断电");
        putWord("power on", "上电");
        putWord("default", "默认");
        putWord("up", "上");
        putWord("down", "下");
        putWord("left", "左");
        putWord("right", "右");
        putWord("press", "按压");
        putWord("lift", "抬升");
        putWord("count", "次数");
        putWord("by", "按");
        putWord("of", "的");
        putWord("to", "到");
        putWord("for", "用于");
        putWord("in", "于");
        putWord("fill", "补充");
        putWord("capture", "拍照");
        putWord("camera", "相机");
        putWord("wake", "唤醒");
        putWord("outswing", "伸缩");
        putWord("furniture", "家具");
        putWord("automation", "自动化");
        putWord("hidden", "隐藏");
        putWord("backup", "备份");
        putWord("restore", "恢复");
        putWord("cloud", "云端");
        putWord("upload", "上传");
        putWord("stop", "暂停");
        putWord("action", "执行");
        putWord("response", "响应");
        putWord("step", "步进");
        putWord("accumulation", "累计");
        putWord("way", "方式");
        putWord("abs", "绝对值");
        putWord("per", "百分比");
        putWord("overload", "过载");
        putWord("child", "儿童");
    }

    private static final Map<String, String> UNITS = new HashMap<>();

    static {
        // ---- 单位 ----
        UNITS.put("second", "秒");
        UNITS.put("seconds", "秒");
        UNITS.put("minute", "分钟");
        UNITS.put("minutes", "分钟");
        UNITS.put("hour", "小时");
        UNITS.put("hours", "小时");
        UNITS.put("day", "天");
        UNITS.put("days", "天");
        UNITS.put("percent", "%");
        UNITS.put("percentage", "%");
        UNITS.put("%", "%");
        UNITS.put("celsius", "℃");
        UNITS.put("°c", "℃");
        UNITS.put("c", "℃");
        UNITS.put("fahrenheit", "℉");
        UNITS.put("lux", "lux");
        UNITS.put("ppm", "ppm");
        UNITS.put("ug/m3", "μg/m³");
        UNITS.put("mg/m3", "mg/m³");
        UNITS.put("watt", "W");
        UNITS.put("w", "W");
        UNITS.put("kw", "kW");
        UNITS.put("wh", "Wh");
        UNITS.put("kwh", "kWh");
        UNITS.put("volt", "V");
        UNITS.put("v", "V");
        UNITS.put("ampere", "A");
        UNITS.put("ma", "mA");
        UNITS.put("meter", "米");
        UNITS.put("m", "米");
        UNITS.put("cm", "厘米");
        UNITS.put("mm", "毫米");
        UNITS.put("square meters", "m²");
        UNITS.put("m2", "m²");
        UNITS.put("minute(s)", "分钟");
    }

    private static void put(String slug, String cn) {
        PROPS.put(slug, cn);
    }

    private static void putValue(String en, String cn) {
        VALUES.put(words(en), cn);
    }

    private static void putWord(String en, String cn) {
        WORDS.put(words(en), cn);
    }

    /** 属性的中文名；没有对照就按词翻译英文描述，再不行保留原文。*/
    public static String propName(MiotSpec.Prop p) {
        if (p == null) return "";
        String slug = MiotSpec.typeSegment(p.type);
        String cn = PROPS.get(slug);
        if (cn != null) return cn;
        if (!p.description.isEmpty()) return phrase(p.description);
        return slug.isEmpty() ? "属性" + p.iid : slug;
    }

    /** 属性行标题：中文名(英文 slug) 1:2 [bool][rwn]。*/
    public static String propLabel(MiotSpec.Prop p) {
        if (p == null) return "";
        String slug = MiotSpec.typeSegment(p.type);
        String cn = propName(p);
        StringBuilder sb = new StringBuilder(cn);
        if (!slug.isEmpty() && !slug.equals(cn)) sb.append('(').append(slug).append(')');
        sb.append(' ').append(p.key()).append(" [").append(p.format).append('[');
        sb.append(p.readable() ? "r" : "-").append(p.writable() ? "w" : "-")
                .append(p.notifiable() ? "n" : "-").append(']');
        if (p.range != null) sb.append(' ').append(trim(p.range[0])).append('~')
                .append(trim(p.range[p.range.length - 1]));
        if (!p.unit.isEmpty()) sb.append(' ').append(unit(p.unit));
        return sb.toString();
    }

    /** 单位的中文说法；没有对照原样返回。*/
    public static String unit(String u) {
        if (u == null || u.isEmpty()) return "";
        String cn = UNITS.get(u.trim().toLowerCase(Locale.ROOT));
        return cn == null ? u : cn;
    }

    /**
     * 下拉框里一行枚举值：数值 + 中文(英文)，重复的数值只留一次。
     * 例如 (1, "One Time") → "1 遍(One Time)"；(0, "Off") → "0 关(Off)"。
     * 翻不出中文时只显示英文，不硬凑。
     */
    public static String valueTitle(Object value, String description) {
        String num = plainNumber(value);
        String en = description == null ? "" : description.trim();
        String cn = value(description);
        boolean translated = !cn.isEmpty() && !cn.equalsIgnoreCase(en) && hasChinese(cn);
        String base;
        if (!translated) {
            base = en.isEmpty() ? num : (num.isEmpty() ? en : num + " " + en);
        } else if (!num.isEmpty() && cn.startsWith(num)) {
            base = cn;   // 中文本身就带数字（"1 档"），别再叠一个
        } else {
            base = num.isEmpty() ? cn : num + " " + cn;
        }
        if (translated && !en.isEmpty()) base = base + "(" + en + ")";
        return base;
    }

    /**
     * 某个值在 spec 枚举里的中文说法，带上原始数值，例如 {@code 1 空闲}。
     * 不在枚举里（或没有枚举）就返回空串，由调用方自己显示数字。
     */
    public static String valueText(MiotSpec.Prop p, Object v) {
        if (p == null || v == null || !p.hasValueList()) return "";
        for (MiotSpec.ValueUnit vu : p.valueList) {
            if (!same(vu.value, v)) continue;
            return valueTitle(vu.value, vu.description);
        }
        return "";
    }

    /** 数字去掉多余小数位：1.0 → 1。*/
    private static String plainNumber(Object v) {
        if (v == null) return "";
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(v);
    }

    /** 枚举值的中文说法；已经是中文的原样返回。*/
    public static String value(String description) {
        if (description == null || description.isEmpty()) return "";
        String key = words(description);
        String cn = VALUES.get(key);
        if (cn != null) return cn;
        // Level1 / level2 … 这种批量值
        if (key.startsWith("level") && key.length() > 5) {
            String num = key.substring(5).trim();
            if (!num.isEmpty()) return num + " 档";
        }
        return phrase(description);
    }

    /**
     * 英文描述按词翻成中文；认得的词占比太低就放弃（宁可显示英文也不显示半截胡话）。
     * 已经含中文的直接原样返回。
     */
    public static String phrase(String en) {
        if (en == null) return "";
        String s = en.trim();
        if (s.isEmpty() || hasChinese(s)) return s;
        String key = words(s);
        if (key.isEmpty()) return s;
        String exact = VALUES.get(key);
        if (exact != null) return exact;
        StringBuilder sb = new StringBuilder();
        int known = 0;
        int total = 0;
        for (String t : key.split(" ")) {
            total++;
            String cn = WORDS.get(t);
            if (cn != null) {
                sb.append(cn);
                known++;
            } else if (t.matches("\\d+")) {
                sb.append(t);
                known++;
            }
        }
        if (total == 0 || known * 10 < total * 7) return s;
        String out = sb.toString();
        return out.isEmpty() ? s : out;
    }

    /* ---------- 切词 ---------- */

    /** 归一成小写、空格分隔的词串：{@code GoCharging} / {@code go_charging} → {@code "go charging"}。*/
    private static String words(String s) {
        if (s == null) return "";
        String spaced = s
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5]+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return spaced.replaceAll("\\s+", " ");
    }

    private static boolean hasChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fa5') return true;
        }
        return false;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) < 1e-9;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
