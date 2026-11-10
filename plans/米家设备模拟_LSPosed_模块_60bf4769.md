# 米家设备模拟 LSPosed 模块

## 目标与范围
- 在未持有真实设备的情况下，让米家 App 完成"手动添加设备 → 扫描 → 完成添加"，并在设备列表持久显示、进入控制面板可交互、能作为自动化条件/动作被引用。
- 覆盖全类型：WiFi 设备、BLE 设备、Zigbee 子设备（挂网关）、网关本体。
- 完全离线自包含，靠 LSPosed Hook 维持，不依赖真实云端绑定。

## 关键前提与分档（务必先对齐）
- 控制面板分两档：
  - 通用 MIoT 规范面板（原生按 spec 渲染）：离线直接可交互，优先支持。
  - 云端 Plugin 面板（maria/插件渲染）：需额外缓存并拦截插件加载，否则面板空白，作为增强项延后处理。
- 米家存在 R8 混淆且随版本变化，精确类名以 Phase 1 实测为准；下面类名给的是社区已知定位方向，需在设备上核验。

## Phase 0 环境与素材
- 工具准备：jadx / mt-manager（静态反编译）、Frida + Objection（动态确认运行时类）、Magisk + LSPosed（已具备）、Android Studio + Xposed API 模板、手机端 adb。
- 从手机 /data/app 提取当前米家 APK，或直接用已安装包反编译。
- 采集目标型号的 MIoT spec：优先从 App 本地缓存（`/data/data/com.xiaomi.smarthome` 下 spec/model 缓存）提取，缺失的从 miot-spec.org 拉取，作为合成状态机的依据。

## Phase 1 逆向定位钩子点
- 反编译并定位五处核心：
  - 设备实体类与设备列表管理：形如 `...nextio.device.Device`、`DeviceListManager` / `HomeDeviceManager`、SQLite（`smarthome.db` 内 `tabDevice` 等）。
  - 发现与添加回调：形如 `DiscoverManager`、扫描结果/`onDiscovered` 回调、手动添加流程的"完成添加"入口。
  - 命令收发层：`Device.send` / `MiotManager.sendProperty` / `getProps` / `getProperty`。
  - MIoT spec 加载：`MiotSpecManager` 及 spec 缓存路径。
  - 场景/自动化 bean：`Rule` / `Condition` / `Action`，确认其通过 `did + model + siid/piid` 引用设备。
- 用 Frida 在真机运行时打印与核验上述类的方法签名和调用栈，产出《钩子点清单》文档存于本仓库。

## Phase 2 LSPosed 模块骨架
- 新建模块工程：`AndroidManifest` 声明 xposed 元数据，target 仅 `com.xiaomi.smarthome`（含主进程与插件/子进程）。
- 配置后端：以一份 JSON 作为唯一配置源（存在模块私有目录），描述待模拟设备清单（型号 model、did、名称、所在房间、类型标志、父网关 did、各属性初值 siid/piid/value）。UI 与 Hook 层都读写这同一份 JSON，保证单一数据源。
- 状态机：为每个假设备在内存+持久化中维护一份属性状态，供控制层读写，并与配置 JSON 同步。

## Phase 2B 完整图形化管理界面（随核心同步交付，非最小版）
- 形态：模块自带一个独立 Launcher Activity（桌面图标），用 Jetpack Compose 或 ViewBinding 实现，作为设备模拟的管理后台；与米家进程解耦，改动通过配置 JSON + 生效广播传给 Hook 层。
- 设备列表页：展示当前所有模拟设备（型号/名称/房间/类型/在线状态），支持增、删、改、复制、启用/停用开关、拖拽排序。
- 添加/编辑向导：
  - 型号选择：内置型号库（按分类浏览 + 搜索 + 按 model 名检索），选中后自动带出该型号的 MIoT spec。
  - 基础信息：名称、房间、类型（WiFi/BLE/Zigbee 子设备/网关）、Zigbee 子设备的父网关选择。
  - 属性编辑器：依据 spec 的 siid/piid 逐项设初值（布尔/数值/枚举/字符串用对应控件，带取值范围校验）。
- 实时调试面板：显示每个假设备当前属性状态、最近的控制调用记录（get/set 入参出参），可现场手动改属性观察米家面板联动。
- 型号库管理：导入/导出 spec（从本地缓存提取或 miot-spec.org）、查看型号是否走 Plugin 面板并给出提示。
- 配置导入导出：整份 JSON 导出/导入，支持多套配置备份与分享。
- 生效机制：保存后写入 JSON 并触发米家列表刷新（重启米家进程或走内部刷新回调），无需重编译模块。

## Phase 3 注入假设备并走通"添加"流程
- 拦截手动添加的"扫描等待/发现结果"回调：按所选型号直接产生一个"已发现设备"事件，驱动正常添加动画后写入本地。
- 在设备列表读取处（DB query / `getAllDevices`）合并注入配置里的假设备对象，正确设置：did、model、本地生成 token、类型标志（区分 WiFi/BLE/Zigbee 子设备/网关）、Zigbee 子设备的父网关 did 与 on/offline 状态。
- 保证持久化：App 重启后假设备仍在列表（模块自己的存储兜底 + 列表读取处合并）。

## Phase 4 控制面板可交互
- Hook 命令收发层：所有 `getProps/setProps/send` 走本地假状态机，按 MIoT spec 返回结构合法的成功响应，实现开关/调光/色温/读数实时变化且不产生真实网络请求。
- 属性变化回写状态机并持久化，供自动化读取。
- Plugin 面板设备（增强项）：缓存目标插件并拦截插件加载路径，令其从本地读取；此项在通用面板稳定后再投入。

## Phase 5 自动化/场景可配置
- 确保假设备的 model+spec 可被 `MiotSpecManager` 解析，使条件/动作能枚举 siid/piid 属性。
- 校验自动化执行时命中假设备的属性读取同样走 Phase 4 的 Hook，能真实触发"当…则…"逻辑。

## Phase 6 抗检测与稳定性
- 云同步隔离：Hook 云端设备列表与本地合并逻辑，防止云端下发覆盖/清除假设备（用保留 did 段或标记位隔离）。
- 版本自适应：钩子点按"特征字符串/结构"定位而非硬编码混淆名，减少米家升级导致的失效；失败时降级不影响真实设备。
- 多进程与热更新 dex 处理。

## Phase 7 验证与型号扩充
- 分链路验证：添加成功 → 列表显示 → 面板交互 → 状态回写 → 自动化条件触发，逐条打勾。
- 扩充机制：新增型号仅需补 spec + 一条配置项，不改核心代码。
- 交付：模块 apk + 配置样例 + 钩子点清单文档 + 使用说明。

## 测试计划
- 每阶段在真机（Android 16 + Magisk + LSPosed）验证。
- 选取代表性型号：1 个 WiFi（通用面板）、1 个 BLE、1 个 Zigbee 子设备 + 1 个网关、1 个 Plugin 面板设备（增强验证）。
- 回归：确认真实设备添加与控制不受模块影响。

## 待确认事项（不阻塞开工，先按默认推进）
- 首批想精确支持的具体型号清单（用于优先取 spec 和验证 Plugin 情况）。
- 配置 UI 档位已确定：完整图形化管理界面（见 Phase 2B）。
- 待你补充：UI 技术栈偏好（Compose 还是传统 View/XML），无偏好则默认 Compose。
