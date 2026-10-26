# PerfMon — CPU 频率/温度 悬浮监控

Android 悬浮窗监控工具。无 root，读内核 sysfs 与 BatteryManager，实时显示 8 核频率、CPU 温度、内存、电池温度。

## 产物

| 项 | 值 |
|---|---|
| 源码 | `~/data/perfmon` |
| APK | `~/data/perfmon/app/build/outputs/apk/release/app-release.apk` |
| 包名 | `com.jyolo.perfmon` |
| 版本 | 1.3.3 (versionCode 7) |
| 签名 | 自签 `selfsign.jks`（口令在 `local.properties`，均已 gitignore） |
| 体积 | 约 30 KB |

## 构建

```bash
cd ~/data/perfmon
./gradlew :app:assembleRelease          # 增量约 3s，全量约 30s
```

产物已由 `app/build.gradle.kts` 内的 `signingConfig` 自动签名（`apksigner verify --print-certs` 可验）。

## 安装与首次使用

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set com.jyolo.perfmon SYSTEM_ALERT_WINDOW allow   # 或用界面里的「去授权」
```

1. 打开 PerfMon
2. 点「启动」→ 右上角出现悬浮窗
3. 单击悬浮窗 = 展开/收起；按住拖动 = 移动（位置自动记住）；右上角 ✕ = 停止

**MIUI / HyperOS 还要两步**（否则后台一会儿就被收）：

1. 设置 → 应用设置 → 应用管理 → PerfMon → 权限管理 → 显示悬浮窗
2. 设置 → 电池与性能 → 应用智能省电 → PerfMon → 无限制

## 界面参数

- 刷新间隔：0.5 / 1 / 2 / 3 秒
- 字号：小 / 中 / 大（面板宽度随之 150 / 166 / 184 dp）
- 可选显示：内存占用、电池温度、功耗（面板只显示纯数值，无图标）
- 悬浮窗透明度：20%–100%，滑杆拖动实时生效（`View.setAlpha`，不重建面板）
- 无障碍浮层：开关（详见下节）

## 卡片铺满与透明度（1.1.0）

- 设置页所有卡片/分组/复选框/滑杆统一用 `MATCH_PARENT`（`fill()` 助手）加入竖向 LinearLayout；
  之前用 `wrap_content`，卡片宽度会随内容缩水。ScrollView 的根容器宽度也显式撑满。
- 单选组改成等分整行：刷新间隔 4 格、字号 3 格各占等宽（`weight=1`，`setMinWidth(0)` 防截字）。
- 实测（模拟器 1080×2340 / 440dpi = 393dp，uiautomator bounds）：
  卡片内容区 x=94…986（卡左 50px + 16dp 内边距 44px），四等分 94/317/540/763/986，像素复核卡片横向覆盖 50…1026。

## 上层应用隐藏悬浮窗时怎么办（安兔兔等）

症状：打开某些跑分/安全类 App 后悬浮窗“消失”。实测 `dumpsys window windows` 里我们的窗口状态是
`mForceHideNonSystemOverlayWindow=true`、`mPolicyVisibility=false`，而前台窗口带
`HIDE_NON_SYSTEM_OVERLAY_WINDOWS` 私有标志 —— 即该 App 调用了 `Window.setHideOverlayWindows(true)`，
系统会把**所有 `TYPE_APPLICATION_OVERLAY` 窗口整层隐藏**，普通悬浮窗没有更高的层级可用。

解法：改用无障碍浮层 `TYPE_ACCESSIBILITY_OVERLAY`（设置页「外观 → 无障碍浮层（最上层显示）」）。
它不受上述隐藏机制影响；代价是需要在系统「无障碍」里勾选 PerfMon。

两种宿主共用同一块面板（`PanelView`）与同一套拖动逻辑（`PanelDragHelper`）：

```
OverlayService        → TYPE_APPLICATION_OVERLAY（默认，只需要悬浮窗权限）
PerfMonA11yService    → TYPE_ACCESSIBILITY_OVERLAY（开关打开且无障碍已授权时接管显示）
```

无障碍服务只申请 `typeWindowStateChanged` 观察能力，`canRetrieveWindowContent=false`，不读取界面内容、不联网。

实测对照（模拟器，前台 = 系统设置，它会隐藏普通悬浮窗）：

| 状态 | 观测 |
|---|---|
| 无障碍浮层关闭 | 面板区域像素 = 背景 (241,240,247)，面板不可见 |
| 无障碍浮层开启 | 日志 `a11y panel 457x311 type=ACCESSIBILITY_OVERLAY alpha=42%`；面板区域像素 (148,148,153)，按混色公式反解 alpha = 0.42，与滑杆 42% 一致 |


## 数据来源（全部只读，无估算）

| 项 | 来源 | 读不到时 |
|---|---|---|
| 频率 | `/sys/devices/system/cpu/cpuN/cpufreq/scaling_cur_freq` (kHz) | 回退 `cpuinfo_cur_freq`，再失败显示 `--` |
| 下线核心 | `/sys/devices/system/cpu/cpuN/online` == 0 | 显示 `off` |
| CPU 温度 | `/sys/class/thermal/thermal_zone*/{type,temp}`，取 `cpu-*-usr` 分区最大值 | 宽松匹配 soc/cpu 分区；都没有显示 `--` |
| 内存 | `ActivityManager.MemoryInfo` | 显示 `--` |
| 电池温度 | `ACTION_BATTERY_CHANGED` 的 `EXTRA_TEMPERATURE` | 显示 `--` |
| 功耗 | `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` × `EXTRA_VOLTAGE`，回落 `/sys/class/power_supply/battery/{current_now,voltage_now}` | 显示 `--` |

温度合理性闸门：`-20..150 °C` 之外的值一律丢弃，防止个别内核的坏 zone 把数字顶上去。
主界面的「实时读取」区块会显示命中的 thermal zone 名（如 `cpu-1-1-usr`），便于核对来源。

## 功耗（W）怎么算的

`功耗 = |电流| × 电压`，单位瓦。取数顺序：

1. `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` + sticky 广播的 `EXTRA_VOLTAGE`
2. 回落 `/sys/class/power_supply/battery/current_now` + `voltage_now`
3. 都拿不到 → 显示 `--`（不估算）

四个必须处理的差异：

- **单位不统一**：官方文档写 µA / µV，但部分厂商给 mA / mV。代码不再用固定阈值猜，而是
  「一次锁定」：见到 `|raw| > 20000` 就锁定 µA（换成 mA 意味着 20 A 以上，不可能）；
  只有「µA 解释算不出合理功率、mA 解释才成立」时才锁定 mA，避免逐个样本来回跳导致数字乱跳。
- **电压量级**：只认 mV(2000..6000) 或 µV(2000000..6000000)，其它一律视为不可用。
- **电流为 0 不算 0 W**：不少 ROM 对不支持的属性返回 `0` 而不是 `Integer.MIN_VALUE`。
  读到 0 时先回问 sysfs 的 `current_now`；仍为 0 就显示 `--` —— 手机不存在真正的 0 瓦空闲，
  摆一个 `0.00W` 只会误导。
- **充电时电流方向相反**：此时功率是「充入电池」而不是「整机耗电」。面板只显示纯数值（不带图标），
  充电状态在设置页「实时读取」里用文字标注「(充电中)」。

功率合理区间 0.05..25 W，区间外的解读一律丢弃。

实测（Android 14 模拟器）：`CURRENT_NOW=900000`、`EXTRA_VOLTAGE=5000`，恒定不变
（执行 `adb emu power ac off` 也不变），所以模拟器上永远显示 `功耗 4.50W`。
这是模拟器没有真实电流传感器，不是算错 —— 准不准必须在真机上看。
设置页「实时读取」会同时显示原始读数，便于按 ROM 核对：
`原始 900mA @ 5.00V（ROM 原始值 900000 / 5000）`。

## 已知修复：单选按钮互斥失效（1.3.0）

症状：刷新间隔里「1 秒」永远是选中的，点其它选项没有任何反应，设置也不落盘。

根因：`RadioButton` 没有设置 id。`RadioGroup` 靠子 View 的 id 维护互斥与选中态，id 为 `NO_ID` 时
它无法取消其它已选项；同时监听器里 `findViewById(g.getCheckedRadioButtonId())` 返回 null，
于是「保存设置」那一步被直接 return 掉了 —— 看起来像点了没反应。

修法：`rb.setId(View.generateViewId())`，监听器统一用 `getCheckedRadioButtonId()` 取当前项。
实测：点「2 秒」后仅 2 秒选中；再点「0.5 秒」后仅 0.5 秒选中；强杀进程重启后仍是 0.5 秒（已落盘）。

## 外部控制（adb / Tasker / 快捷方式）

```bash
adb shell am broadcast -a com.jyolo.perfmon.START  -n com.jyolo.perfmon/.ControlReceiver
adb shell am broadcast -a com.jyolo.perfmon.STOP   -n com.jyolo.perfmon/.ControlReceiver
adb shell am broadcast -a com.jyolo.perfmon.RELOAD -n com.jyolo.perfmon/.ControlReceiver
adb shell am broadcast -a com.jyolo.perfmon.RESET  -n com.jyolo.perfmon/.ControlReceiver
```

`ControlReceiver` 只接受这四个动作，不接受参数、不返回数据。`OverlayService` 本身 `exported="false"`。

## 架构

```
MainActivity.java    控制面板：授权/启停/设置/实时预览（每秒采样一次自证 sysfs 可读）
OverlayService.java  前台服务 + WindowManager 悬浮窗（TYPE_APPLICATION_OVERLAY）
CpuReader.java       只读采样器（sysfs + BatteryManager），失败返回哨兵值而非估算
ControlReceiver.java 外部启停入口
Prefs.java           设置键集中管理
```

三个实现要点：

1. **采样在 HandlerThread 上**，主线程只做 setText；每秒 13+ 次 sysfs 读不占 UI 线程。
2. **面板宽度固定**（按字号档位 150/166/184 dp），不用 `wrap_content` + 字体测量——后者在
   不同设备上会让窗口莫名其妙撑到 80% 屏宽。
3. **拖动与点击用 5dp 位移阈值区分**，松手时若发生过位移就只存位置，不触发折叠。

## 已实测（Redmi K20 Pro / Android 11 / MIUI V125 / 骁龙 855）

| 验证项 | 证据 |
|---|---|
| 8 核频率读取 | 面板显示 cpu0-3 1786 / cpu4-6 1805 / cpu7 `off` |
| CPU 温度 | 52.5 → 58.7 °C 随负载变化，zone 名 `cpu-1-1-usr` |
| 内存 | 61-62%，4.5/7.3G |
| 电池温度 | 35.5-36.2 °C（sticky 广播，Activity 与 Service 两条路径都通） |
| 拖动 | `(606,385) → (206,1155)`，与预期逐像素一致，`prefs` 落盘 |
| 折叠/展开 | 高度 345px ↔ 107px，`collapsed` 与 prefs 同步 |
| 位置持久化 | 停服务再启，窗口回到 `(206,1155)` |
| 窗口属性 | `ty=APPLICATION_OVERLAY` `appop=SYSTEM_ALERT_WINDOW` `fl=NOT_FOCUSABLE LAYOUT_IN_SCREEN` |
| 停止清理 | `stopSelf` 后 `dumpsys window` 中该包窗口数 = 0 |

## 已知限制

- **这台 MIUI 无 SIM 卡，`adb shell input` 被系统封死**
  （`SecurityException: Injecting to another application requires INJECT_EVENTS permission`），
  所以拖动/点击是用「向面板派发伪造 MotionEvent」自测的，不是真实手指。真实触摸链路另有证据：
  测试期间 AURA 的自动化点击落在面板上，确实触发了折叠。
- 温度取的是 CPU 分区最高值，不是某个固定传感器；不同机型分区命名不同，已做两轮匹配 + 合理性闸门。
- 部分 ROM 的 `cpufreq` 节点对普通 app 不可读，此时面板显示 `--`（不做估算、不显示上次值）。
