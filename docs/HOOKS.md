<!--
  Author: Ctrl-Ling
  Last Modified By: Ctrl-Ling
  Last Modified At: 2026-11-11 21:23:42 +08:00
-->

# 钩子点清单（Phase 1 实测取证）

目标 APK：`hookapk/mijia_11.9.705.apk`（米家 11.9.705）
取证方式：**纯静态**。本机内存不足以对 73MB 主 apk 做 jadx 全量反编译，所以用自研的
Node DEX 解析器 `recon/dexkit.mjs` 直接扫 classes*.dex 的 type/descriptor 表和方法签名。
11-10 起再加一条快路：`java -cp D:\SoftWare\jadx-gui\lib\jadx-gui-1.5.5-all.jar jadx.cli.JadxCLI
-q --no-res --single-class <类名> --single-class-output <file> dex/classesNN.dex` ——
单类反编译一个 4 秒，够把方法体读成人话（`_m_j` 里有些类被标 DONT_GENERATE，只能回去看 dexdump）。
下面每一条都是「在 dex 里真实存在」的形状；标了 **待实测** 的是静态层面看不到、
必须上真机验证的（没有真机在手，这些一律做成可关可开的开关，默认不依赖它们）。

## 1. 设备管理类（把假设备塞进列表）

R8 把实现类名混淆掉了，但方法形状没变，所以定位靠「形状指纹」而不是类名。

| 目标 | 位置（11.9.705 实测） | 方法形状 | 用途 |
|---|---|---|---|
| 设备管理接口 | `_m_j.c67` | 声明 `getDeviceByDid(String)` / `getDeviceList(String)` 等 | 接口本身不能 hook（抽象方法），只用来识别实现类 |
| 实现类候选 1 | `_m_j.zxf` | 同上 | 已知名字，首屏直接挂 |
| 实现类候选 2 | `_m_j.ob7` | 同上 | 同上 |
| 实现类候选 3 | `com.xiaomi.smarthome.device.impl.OooO0O0` | 同名方法 + **一堆模糊名 getter** | 未混淆包里的实现；同时是 `_m_j.k87`（自动化那套 Kotlin 设备仓库）的实现 |
| 列表 getter（模糊名） | 上述类 | `Oooo0oO()` / `OoooO00()` / `o000000O()` -> `List` | R8 把名字改没了，只能按「无参返回集合」挂，运行时验元素 |
| 映射 getter（模糊名） | 上述类 | `OooOOO()` / `Oooo0()` / `Oooo00o()` / `Ooooooo()` -> `Map` | did -> Device，自动化「家居设备」页按 Map 遍历；复制返回，不动活缓存 |
| 子设备查询 | 上述类 | `getSubDeviceByParentDid(String) -> Collection` | Zigbee / BLE Mesh 子设备挂到假网关下 |

> 11-11 修正：之前 `looksLikeDeviceManager` 只认白名单方法名（`getDeviceList` 等），
> 于是 `impl.OooO0O0` 这种「getter 全被改名」的真仓库从未被挂过 —— 首页列表靠
> `Home.getDids()` + 老管理类还正常，但自动化选设备页走这个仓库，假设备就没了。
> 现在判定放宽成：**有 `(String) -> Device` 查询方法 + 任意无参返回 Collection/Map**，
> 没名字的 getter 一律走运行时元素类型校验（`APPEND_IF_DEVICE_LIST` / `APPEND_IF_DEVICE_MAP`），
> 空表则看泛型签名里有没有设备类型，签名不明就不动。

`hook/HookRegistry.java` 的两条路径：
1. **加速路径**：先按上表已知类名挂（覆盖当前版本，冷启动就能生效）；
2. **指纹路径**：钩 `ClassLoader.loadClass`，对每个新载入的类做 `looksLikeDeviceManager`
   （有没有 `getDeviceByDid(String)`、有没有返回 `Device` 集合的方法），命中就挂上；
   挂满 3 个类（`ENOUGH`）以后把所有 loadClass 钩子摘掉，不留长期性能尾巴。

> 米家升级把 `_m_j.*` 改名以后，第 1 条路会 SKIP，第 2 条码仍然能找到；
> 界面「实时调试」页会显示是哪一组黄了。

## 2. 设备 bean（造出来的假对象是什么类型）

| 类 | 用途 |
|---|---|
| `com.xiaomi.smarthome.device.Device` | 列表里的主 bean；按调用方声明的返回类型造 |
| `com.xiaomi.smarthome.device.api.DeviceStat` | 控制面板/统计用的 bean |
| `com.xiaomi.smarthome.device.MiioDeviceV2` | WiFi（miio）设备 |
| `com.xiaomi.smarthome.device.BleDevice` | 蓝牙设备 |

`hook/DeviceBeanFactory.java` 用反射按 bean 类型填字段（did / model / extra / token / mac /
online / propInfo / sort / parentDid），字段不存在就跳过（`XposedFieldSetter.setIfPresent`），
所以 bean 字段改名不会抛异常到米家。

## 3. 家庭/房间的 did 名单（**不补这里假设备永远不被请求**）

| 类 | 方法 | 说明 |
|---|---|---|
| `com.xiaomi.smarthome.homeroom.model.Home` | `dids` 字段 / `setDids(List)` / `setAllDids(List)` | 首页按 did 名单去设备管理里取每台设备 |
| `com.xiaomi.smarthome.homeroom.model.Room` | `dids` / `setDids(List)` | 房间维度同上 |

两个动作：
- **读侧注入**（`DeviceListHook`）：读到名单时复制一份、把假 did 追加进去；
- **写侧过滤**（`CloudSyncGuard`）：`setDids` 里把保留段 did 摘掉，
  保证假 did 不会落进米家本地 DB、被云同步当成真实家庭成员上报。
  复制返回是这一节的关键：直接 `list.add()` 会写进米家自己缓存的那份列表。

## 4. 控制面板（点开关不联网，走本地状态机）

米家的 spec 操作层 **没有被混淆**（属于对外 API），所以这一层签名是硬绑的：

| 类 | 方法签名（实测） | 挂钩动作 |
|---|---|---|
| `com.xiaomi.smarthome.device.api.spec.operation.controller.DeviceController` | `getSpecProperties(Context, List, Callback)` | 假设备命中 → 直接 `succeed` 本地值，`setResult(null)` 阻断网络 |
| 同上 | `setSpecProperty(Context, PropertyParam, Callback)` | 写进 `DeviceStateStore`，回写 `propInfo` |
| 同上 | `doAction(Context, ActionParam, Callback)` | 记流水 + `toggle` 类动作翻转对应属性 |
| 同上 | `subscribeProperty(DeviceStat, List, Callback)` | 订阅回调给当前值 |
| `com.xiaomi.smarthome.device.api.DeviceStat` | `getPropertyValue(int siid, int piid)` | 面板直接读的那一格 |
| `com.xiaomi.smarthome.device.api.Callback` | — | 结果载体 |
| `...spec.operation.PropertyParam` / `ActionParam` | — | 入参，含 did / siid / iid / value |

只挂 `DeviceController` 这一层，原生面板和插件面板都覆盖：
插件宿主内部也是 `getSpecDeviceController(did)` 拿同一个控制器，两层都挂会出现二次回调。

插件宿主实现类（`DeviceListHook` 的插件名单注入用到，共 6 个）：

```
com.xiaomi.smarthome.frame.plugin.host.IPluginHostApiImpl
com.xiaomi.smarthome.framework.api.provider.core.RnPluginHostApiProviderImpl
com.xiaomi.smarthome.framework.plugin.mpk.PluginHostApiImpl
com.xiaomi.smarthome.framework.plugin.tiny.nativemodule.MIOTBaseJavaModuleTiny
com.xiaomi.smarthome.plugin.DefaultPluginHostApi
com.miot.provider.internal.impl.TinyPluginHostApiProviderImpl
```

## 5. 添加设备流程（按需合成所选型号，默认关）

### 5.1 数据流（11-10 真机 + dexdump/jadx 双证）

```
SmartHomeMainActivity
 └─ device.choosedevice.ChooseDeviceActivity          「添加设备/手动添加」，result 3
     └─ device.choosedevice.ChooseSubCategoryDeviceActivity  「选具体型号」，result 1003
         └─ device.ResetDevicePage                     「设备已重置」，Intent 带 extras ← 型号在这里
             └─ wificonfig.RescanWifiV2Activity         选网/重扫（实测只活 1.4s，带 result 退回）
                  → 真正出扫描列表的那一屏（12:06–12:09 那轮有 4 次 dispatchOnScan）
```

| 类 | 位置 | 说明 |
|---|---|---|
| `com.xiaomi.smarthome.device.DeviceScanManager` | 枚举单例，未混淆 | 扫描管理器；`dispatchOnScan(List)` 是**唯一分发出口**（遍历 `mListener` 调 `gua.onScan(List)`） |
| `_m_j.gua` | `void onScan(List)` | 回调接口；实现类实测有 `ChooseDeviceActivity`、`ChooseDeviceManually`、`ChooseConnectDevice`、`ChooseUdpDeviceActivity`、`BLEMeshBatchBindSelectActivity`、`_m_j.awf` —— 全在 `dispatchOnScan` 下游，不用挨个挂 |
| `device.ResetDevicePage` | classes10.dex | **型号来源①**：`showActivity()` 里 `intent.putExtra("model", pluginDeviceInfo.OooOO0o())`；`onCreate` 读 `"model"`，空则退 `"device_model"`，再退 Parcelable `"plugin_record"`。查不到本机插件记录会自己 `finish()` —— **所以能停在这一页的型号，本机一定有插件记录** |
| `device.choosedevice.ResetPageRoute` | classes10.dex | **型号来源②**：`EXTRA_MODEL = "extra_model"`（`@RouterUri("ResetPageRoute")`） |
| `device.ResetDevicePageScan` | classes10.dex | **型号来源③**：读 `"model"`，加载 `home.mi.com/views/deviceReset.html?model=` |
| `com.xiaomi.smarthome.smartconfig.PushBindEntity` | classes12.dex | 条目 bean。`static create(JSONObject, boolean)`（本版本方法名混淆成 `OooO0O0`，按 `(JSONObject,boolean)→PushBindEntity` 形状找）。**字段映射（构造函数逐条实测）**：`OooOo=PluginDeviceInfo`、`OooOoO=ssid`、`OooOoOO=bssid`(没有 bssid 键时改用 `mac`)、`OooOoo=router_bssid`、`OooOoo0=router_ssid`、`OooOooO=rssi`(String)、`Oooo000=did`、`Oooo00O=model`、`Oooo00o=public_key`、`Oooo0=token`、`Oooo0OO=sn` 末 4 位 |
| `_m_j.ol2`（`ChooseConnectDeviceAdapter`） | classes10.dex | 扫描列表适配器。`OooOOO0(List)` → 静态 `OooOOOO(List)` 预筛：`BleDeviceGroup/BleDevice/ScanResult` 有去重和 `n8h` 校验，**其它类型（含 PushBindEntity）原样通过** |
| `_m_j.em2.OooO00o(PluginDeviceInfo, Intent, boolean)` | classes10.dex | 「下一步」的总路由：按 pid 分派到 `ResetDevicePage` / `ScanBarcodeActivity` / `SmartConfigMainActivity` / `ConfigRouterStationActivity` / `ETHStationActivity` 等 |

### 5.2 「假条目不显示」的真凶（v8 已修）

`ol2.OooOOOO(List)` 对每个 `PushBindEntity` **无条件**执行 `pushBindEntity.OooOo.OooOO0o()`
（`OooOo` 就是那个 `PluginDeviceInfo`）。而 `create(json, force)` 在 `force=true` 时
**不检查记录是否存在**就 `new PushBindEntity(record, json)`，`record` 为 null 时字段就是 null ——
于是本机没装过该型号插件的条目会让**整张扫描列表在刷新时 NPE**，一条都不出。
这就是 v6/v7「`in=0 out=4 PushBindEntity 4 条` 记了、屏幕上却没有」的解释。

修法（`AddFlowHook.hasPluginRecord`）：按**类型**（`*PluginDeviceInfo`）找那个字段，
null 就丢弃这条目、只记原因，绝不塞进去。字段类型改名/不存在时按「说明不了什么」放行。

### 5.3 模块的做法

1. `install()` 同时挂 `dispatchOnScan(List)` 和三个向导页的 `onCreate`/`onDestroy`，
   把「他此刻选的型号」记进 `core/RequestedModel`（含现场合成的 did/token/mac，同型号复用不抖动）。
2. 派发时**先按请求型号**造条目，再补配置清单里启用的设备；两条都过 5.2 的记录校验。
3. 每个向导页 `onCreate` 额外记一条 `wizard` 调用日志，把 **extras 全量键值**（截断）写进去 ——
   `dumpsys` 只给 `(has extras)`，这条日志才是把键名钉死的证据。
4. 新抓到型号时由主进程立刻 `HookReport.publish()`，界面「正在请求的型号」当场可见
   （子进程上报的报告里没有这个状态）。

> 仍待实测（要真机）：
> 1. 三个键名里哪一个真是米家用的（`wizard` 日志一跑就知道，别再猜）；
> 2. 过完校验的条目在扫描屏到底显不显示（同一时刻截图对比）；
> 3. 点条目 →「完成添加」：米家会拿 `did/token` 走 miio 握手/云端绑定，本地假设备没人应答，
>    这一跳要不要拦、在哪拦（`_m_j.em2`、`DevicePushBindManager`、`IXmPluginHostActivity$DeviceFindCallback`）。
>
> `settings.scanInjection` 默认 false 不变；关掉时「界面写配置 → 首页列表注入」那条路照样能加出设备。

## 6. 面板 spec 解析（增强项，未实现）

| 位置 | 说明 |
|---|---|
| `_m_j.ge7#o00O0O(Device) -> SpecDevice` | 米家自己把 Device 解析成 SpecDevice 的入口 |
| `com.xiaomi.smarthome.newui.card.oo0o0Oo#OooO0o(String)` | 按 model 拿卡片配置 |

未知型号（米家本地没这份 spec）时，这两处需要自建 `SpecDevice` 才能画满卡片；
按 plan 属于增强项，当前版本的行为是：**型号有 spec 就正常，没有则面板读数来自配置初值**。
真机上如果确认「部分型号面板一片空白」，从这里开始做。

## 7. 拿 Context

| 位置 | 说明 |
|---|---|
| `android.app.Instrumentation#callApplicationOnCreate(Application)` | 第一优先，稳定 |
| `android.app.Application#onCreate()` | 兜底 |
| 反射 `ActivityThread.currentApplication()` | 惰性兜底（`SimContextHolder.get()`） |

## 8. 身份约定与云同步隔离

- 假 did：**9 开头的 10 位纯数字**（真实米家 did 是 10~11 位且不以 9 开头），
  判定函数 `Consts.isSimulatedDid`；
- `Device.extra` 里打标记 `mijiaSimulated=1`，作为第二重识别；
- token：32 位大写 hex，本地生成即可（miio 校验格式不校验内容）；
- 所有注入都走「复制返回」，不改米家自己的缓存列表和 `Home.dids` 字段本体。

## 9. 失败即降级

`hook/HookUtil.java` 的每个挂点都过 `HookReport.ok/skip/fail`，报告写进 Provider，
界面「实时调试」页按 FAIL → SKIP → OK 排序显示。
任何一处注入异常都在自己的 try/catch 里吃掉（`Log.e`），绝不冒到米家；
挂不上的类一律保留真实行为，不影响真机设备。
