<!--
  Author: Ctrl-Ling
  Last Modified By: Ctrl-Ling
  Last Modified At: 2026-11-10 10:45:39 +08:00
-->

# 使用说明

模块名：米家设备模拟 / MIJIA device simulate
注入目标：`com.xiaomi.smarthome`（米家 11.9.705 实测；钩子点按形状定位，新版本大概率仍可用）
最低系统：Android 9.0（API 28）
需要：LSPosed（或同类 Xposed 框架）

## 一、装完第一次要做的事

1. 安装 `米家设备模拟-*-debug.apk`。
2. 打开 LSPosed → 模块 → 启用「米家设备模拟」。
3. 作用域勾选 **两个**：`米家` 和 `米家设备模拟` 自身。
   第二个不是笔误：界面顶部那行「框架生效」是靠给自己挂一个探针测出来的，
   不勾自己就只能靠猜。
4. 完全退出米家（最近任务划掉），再打开。
5. 打开「米家设备模拟」图标 → 顶部状态行三项都该是：框架生效=是、Provider=通、米家=已装。

## 二、加一台假设备

1. 主页点「新增设备」；
2. 型号一栏点「型号库」挑一个（内置 8 个型号，见下表），也可以手输任意真实型号；
3. 选接入类型：
   - **WiFi**：独立设备，最常用；
   - **蓝牙 BLE**：独立 BLE；
   - **蓝牙 Mesh**：子设备，要选父网关；
   - **Zigbee 子设备**：要选父网关；
   - **网关本体**：自己当网关，给子设备挂；
4. 属性初值：下面按 spec 自动列出可写属性（开关是拨钮、数值有滑条和范围、枚举是下拉），
   填你希望米家第一次看到的值；
5. 点「保存并生效」。首页列表下次读取时就带上它。

内置型号（`app/src/main/assets/specs/`）：

| 型号 | 类型 | 备注 |
|---|---|---|
| `xiaomi.plug.mcn003` | WiFi 插座 | on/off + 功率 |
| `yeelink.light.bslamp2` | WiFi 灯 | 开关/亮度/色温 |
| `yeelink.light.spot1` | WiFi 灯 | 精简版 |
| `mijia.vacuum.v3` | WiFi 扫地机 | 动作最多，适合验证 action 链路 |
| `lumi.sensor_ht.agl02` | BLE 温湿度 | 只读量为主 |
| `miot.sensor_ht.s2` | BLE Mesh 温湿度 | 需要父网关 |
| `lumi.plug.sacn02` | Zigbee 插座 | 需要父网关 |
| `lumi.gateway.mgl03` | 多模网关 | 当父网关用 |

## 三、让假设备「像真的」

- **控制面板**：点设备进面板，开关/滑条调一下 —— 走的是本地状态机，不联网，
  值会持久化，杀掉米家再进来还是那个值。
- **自动化**：设备是标准 `Device` bean，能被条件（属性变化）和动作（设置属性/调服务）引用；
  条件的判断读的是同一份状态机。
- **暂时不想看到某台**：主页那一行右边的开关关掉，数据保留。
- **排序**：按住行左边的 `≡` 上下拖，拖完自动按新顺序重排 `sort`，注入顺序=界面顺序。
- **整份配置**：菜单 → 导入/导出配置，导出的 JSON 可以版本管理或换机导入。

## 四、出问题看哪里

主页菜单 → **实时调试**。这一页三块内容：

1. **钩子命中**：按 `失败 → 跳过 → 挂上` 排序。
   - 全是空的：米家进程根本没载入模块 → 回第一节，确认作用域并真正重启米家；
   - `list` 组红/黄：设备管理类换名字了 → 开「更多开关 → 详细日志」，重启米家后再看，
     把新的类名补进 `docs/HOOKS.md` 第 1 节的候选表。
2. **属性实时状态**：每台启用设备 + 每个可读属性的当前值；**点一行可以直接改值**，
   改完米家面板下次读取就跟着变（这就是验证联动的最快路径）。
3. **最近控制调用**：面板真的调了什么（GET/SET/ACTION + 入参出参 + 耗时）。
   这里没记录 = 米家没走到我们的控制层，通常是面板所在的子进程没被注入。

## 五、几个「不生效」的正常原因

| 现象 | 原因与处理 |
|---|---|
| 保存了但首页没变化 | 首页列表是缓存的。下拉刷新或退出重进米家。 |
| 面板打开是空白 | 该型号米家本地没有 spec。先在型号库里「在线刷新该 spec」，或换个内置型号。 |
| 面板读数不动 | 见下一节「propInfo 形状」。 |
| 假设备过一会儿自己消失 | 云同步把名单覆盖了。确认「更多开关 → 云同步隔离」是开的。 |
| 界面保存失败提示 | Provider 不可达 → 模块被 LSPosed 禁用或没装完整。 |
| 冷启动米家后什么都没有 | MIUI 自启动管控拦住米家拉起模块进程。先打开一次模块界面再进米家，或到安全中心给本模块放开自启动；详见第七节。 |
| 加了设备但注入台数比列表少 | 那几台型号是空的，不参与注入；行尾会写「未参与注入：型号为空」。填了型号保存就计入。 |
| 新增设备没填型号就退出 | 这台半成品会被自动删掉，不用手动清理。 |
| 装了 root 后界面改了没反应 | 见下一节兜底目录的优先级。 |

## 六、propInfo 形状（唯一可能要按新版米家改的地方）

假设备的属性值喂给米家的结构在 `core/DeviceStateStore.propInfo()`：

```json
{ "2:1": { "value": true, "type": "prop" } }
```

新版米家如果改成 `{"siid":2,"piid":1,"value":true,"code":0}` 这类数组形式，
面板读数就会不动 —— 这是设计上留给你的一个可调点，改这一个方法即可，
其余链路（列表注入、控制回调、状态机）都不用动。

## 七、三条数据通道与读取顺序

米家进程读配置按「兜底文件 → Provider 实时 → 本地镜像」这个顺序来，读到任何一条就停，
并把「到底从哪条读到的」上报给界面（`root` / `provider` / `mirror` / `none`）。

- **第 1 条：adb/root 兜底文件**（优先级最高）。把同名文件放到 `/data/local/tmp/mijia_devicesim/`：

  ```
  /data/local/tmp/mijia_devicesim/config.json   # 设备清单，和界面导出的一模一样
  /data/local/tmp/mijia_devicesim/state.json    # 属性实时值
  /data/local/tmp/mijia_devicesim/specs/<model>.json
  ```

  有这一层就能离线灌配置、做 A/B 回归；但界面保存会被它盖住，
  所以主界面顶部和调试页在检测到这层时都会明确提示。

- **第 2 条：Provider 实时**（默认通道）。界面写 → 模块自己的 `files/config.json` →
  Provider `call()` → 米家读，改完 `notifyChange` 立刻热重载，不需要重编模块、不需要杀进程。

- **第 3 条：本地镜像（last-known-good）**。上面两条真读到东西时，**每个进程都在自己的**
  `files/mijia_devicesim_config.json` / `mijia_devicesim_state.json` 里留一份完整副本；
  下次两条都读不到，就用这份顶上，冷启动也不会整场空转。

### 为什么需要第 3 条

MIUI 的 `WakePathChecker` 不让米家这种后台应用把别的进程拉起来。模块进程没活着的时候，
米家一侧跨进程访问 Provider 会直接抛 `IllegalArgumentException: Unknown authority`，
看上去就像「配置是空的」——假设备整场消失。两个对策，都不靠定时器：

1. **界面每次打开都喊一嗓子**（`notifyChange`）：你打开模块界面 = 模块进程此刻活着，
   米家的观察器收到就重读一趟；
2. **未加载不缓存**：Provider 读失败时不把空配置当权威，等下一个真要用的时刻（有钩子来取设备列表）
   自动再试一趟，读到就恢复正常。

两种情况都不静默：主界面顶部和调试页会写「米家侧上报：读取来源=本地缓存」并给出恢复步骤，
日志里也有对应的一行。

### 一行命令看当前走的是哪条

```
adb shell content call --uri content://com.mijia.devicesim.provider/config --method getReport
```

看 `configSource`（来源）、`configDevices`（读到几台）、`injectDevices`（型号非空、真参与注入几台）。

## 八、配置长什么样

```json
{
  "version": 1,
  "injectEnabled": true,
  "cloudGuard": true,
  "scanInjection": false,
  "persistState": true,
  "verboseLog": false,
  "devices": [
    {
      "did": "9007132455",
      "model": "yeelink.light.bslamp2",
      "name": "客厅模拟灯",
      "roomName": "客厅",
      "type": "wifi",
      "parentDid": "",
      "token": "32 位大写 HEX",
      "mac": "28:6C:07:xx:xx:xx",
      "online": true,
      "enabled": true,
      "sort": 1,
      "props": { "2:1": true, "2:2": 60 },
      "extra": {}
    }
  ]
}
```

`props` 的键是 `siid:piid`，取自该型号 spec；界面上的属性编辑器就是在写这一段。

## 九、不做什么

- 不联网伪造云端绑定、不改账号、不碰 token 校验逻辑：全部靠米家本地的列表读取和控制入口；
- 假 did 一律走保留段并打 `mijiaSimulated` 标记，写侧还会从 `Home/Room#setDids` 里摘掉，
  避免假设备被云同步上报成真实家庭成员；
- 任何一个钩子失败都只降级、不影响真实设备，也不会把异常冒到米家。
