# MiniWatts Android

中文优先的原生 Android 电池仪表。离线运行，无网络权限、广告、埋点或账户。独立实现，灵感来自 [ResistanceTo/MiniWatts](https://github.com/ResistanceTo/MiniWatts)（Apache-2.0），未复制其代码、图像、商标或资源。最低 Android 8.0（API 26），target API 35，Kotlin。

**功能一览**（详见下文「界面」）：

- 五页胶囊导航：功率、发热、适配器、设备、历史记录
- 电芯净功率仪表（绝对值 + 方向说明）与带符号的原始读数并存
- 可选 Shizuku 增强读取适配器输入实测功率（默认关闭）
- 内核热区温度、热缓解与 CPU 频率（免权限）
- 插拔会话记录、逐项会话对比、四格式导出、桌面 Widget 与悬浮仪表

## 界面

| 页面 | 内容 |
| --- | --- |
| 功率 | 电芯净功率仪表（显示绝对值，方向由「电芯充电/放电」说明）、带符号电芯电流/电压/温度、供电路径、本次充电摘要、最近 3 分钟趋势（有实测输入时叠加适配器输入曲线，共用 W 量程） |
| 发热 | 系统热状态、按功能分组的内核热区温度、热缓解档位、CPU 调频策略、带电池温度标记的示意图；可进入「性能与热区」子页看全部热区与频率比例历史 |
| 适配器 | 实际功率与协商上限、握手信息、广播供电规格、实时供电轨、线缆与连接 |
| 设备 | 型号、电池状态与电量、循环次数、电荷计数器、容量健康度（需特权读取）、容量估算（无特权时的替代，标注为估算）、系统版本 |
| 历史记录 | 插拔会话列表与详情、所有会话汇总、逐项会话对比、导出与删除 |

底部导航之外：设置（采样间隔/单位/语言/主题/保留上限/Shizuku 开关）、诊断、离线帮助、导出中心。

## 数据边界（读数口径）

- `W = µA × mV / 10⁹` 是**电芯侧净功率（推算）**，不是充电器输出功率。公开 API 正号 = 流入电芯。
- **存下来和算出来的值一律保留符号**（样本、曲线、净能量、比例、全部导出）；只有表盘与卡片头条显示绝对值，方向由文字给出。平台「充电中」状态描述的是策略（已插电且允许充电），插电但系统耗电超过输入时电芯确实会净放电——这种相反时段会记在会话上并给出徽标「插着电，电芯在放电」。
- 适配器数据有两条独立来源，绝不混为一谈：①系统广播的**协商上限**（不是实时输入）；②可选 Shizuku 增强的**输入侧实测**。实测被判定只读到一路输入时（`电芯 > 输入 × 1.2 且 差值 > 1.5 W`），该值不参与任何派生并写明原因，原始值仍在导出里。
- 缺失值显示「不可用」，绝不伪装成 0；真实 0 与「没测到」严格区分（`inputCoveredMs == 0` 才是「从未测到」）。
- 温度探头走内核 `/sys/class/thermal`（免权限），按名称剔除非温度节点（`vbat` 是毫伏、`*-ibat-lvl*` 是电流），`-273000` 是传感器缺失哨兵。
- 积分只连接同次启动、`dt ≤ 3×采样间隔且 ≤60s`、两端功率有效的连续样本，梯形积分；曲线与导出遵守同样缺口规则，不跨缺口积分。
- 会话级「电芯获得比例」只在输入覆盖 ≥ 电芯窗口一半、且电芯确实在净充电时给出；可能 >100%（电芯同时在用先前存的电），**不叫效率**。
- 本机实测（Xiaomi 15, dada）：`current_now` 为「放电为正」，与 Android 标准相反——保留原值并提示冲突是正确处理，不做机型反号。

## 构建

需要 JDK 17、Android SDK Platform 35 与 Build-Tools 35.0.0（`ANDROID_HOME` 或 gitignored 的 `local.properties`）。

```bash
# 生成一次本地 debug 签名（.tooling/ 不进版本库）
mkdir -p .tooling
keytool -genkeypair -keystore .tooling/debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 10000

./gradlew :core:test :app:assembleDebug :app:lintDebug
```

macOS 可用 `scripts/bootstrap-mac.sh` 初始化项目内官方 SDK（装在 gitignored 的 `.tooling/`，不改全局配置）。

**签名冲突**：手机上已有不同密钥签名的版本时，用 `-PisolatedDebug=true` 构建独立测试版 `MiniWatts Test`（`dev.miniwatts.macdebug`），保留原应用与数据。不要卸载原应用解决冲突。

**正式版**：`./gradlew :app:assembleRelease`。签名密钥是 App 永久的升级身份，放 `keystore/miniwatts-release.jks`、口令在 gitignored 的 `local.properties`（两处都不进版本库，必须自行备份；密钥丢失只能卸载重装并丢失历史数据）。

## 真机测试

```bash
scripts/build-local.sh -PisolatedDebug=true :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.miniwatts.macdebug.test/dev.miniwatts.RegressionRunner
```

当前 **17 项真机回归 + 32 项核心单测**全部通过（含旋转采样回归、迁移、导出、对比页真实点击路径）。小米设备可能需要「USB 调试（安全设置）」「USB 安装」授权。检查输出 `Tests run: 17, failures: 0`，不要只看 adb 退出码。另有 `ValidationRunner`（截图/布局探针/导出落盘验证）与逐项验收清单，见 [DEVICE_VALIDATION_2026_10_25.md](DEVICE_VALIDATION_2026_10_25.md)。

## 权限

- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`：用户主动开始的持续记录与悬浮仪表（API 34+ specialUse，Manifest 含真实 subtype）
- Android 13+ `POST_NOTIFICATIONS`：记录通知；拒绝仍可记录，设置页有停止按钮
- `SYSTEM_ALERT_WINDOW`：仅用户主动开启悬浮仪表时进入授权页
- 导出走 Storage Access Framework；**没有** INTERNET、READ_PHONE_STATE、位置权限

持续记录不持有唤醒锁、不申请电池优化豁免；深度休眠或 OEM 杀后台可能产生缺口，服务 `START_NOT_STICKY`。

## 架构

```
core/          纯 Kotlin：模型、单位转换、积分、会话对比、导出目录（JUnit 可独立运行）
SensorReader   平台采集，API 版本判断与 null 降级
Store          SQLite：会话/样本持久化、迁移、积分统计、导出
Engine         单后台执行器串行采集/数据库/导出，主线程分发
MainActivity   导航、生命周期、设置与权限/导出流程
LivePanels / HistoryPanels / ComparePanels   三个页面组的视图绑定
MeterViews / ChartView / UiKit               仪表、曲线、主题组件
PrivilegedReader / PrivilegedUserService     可选 Shizuku 通道（AIDL，只读白名单）
ThermalReader   内核热区与 cpufreq（2 秒节流，不写入样本）
RecordingService   前台通知 + 悬浮仪表；MeterWidget   桌面小组件
```

依赖：Gradle 8.10.2、AGP 8.7.3、Kotlin 2.0.21、JUnit 4.13.2（固定版本）；唯一第三方运行时依赖是可选 Shizuku 通道的 `dev.rikka.shizuku:api` / `:provider` 13.1.5。不用数据库/界面框架。

## 文档

| 文档 | 内容 |
| --- | --- |
| [CHANGELOG.md](CHANGELOG.md) | 逐版本变化与当时的判断 |
| [DEVICE_VALIDATION_2026_10_25.md](DEVICE_VALIDATION_2026_10_25.md) | 最新真机验收：修复、证据、电流符号实验 |
| [DEVICE_VALIDATION_REPORT.md](DEVICE_VALIDATION_REPORT.md) | 早期验收（2026-10-13） |
| [REQUIREMENTS.md](REQUIREMENTS.md) / [UI_REDESIGN.md](UI_REDESIGN.md) | 需求与界面设计依据 |
| [docs/](docs/) | 调研存档：适配器功率来源、热区、root 检测、旁路充电结项等 |
| [BYPASS_CHARGING_CONCLUSION.md](BYPASS_CHARGING_CONCLUSION.md) | 为什么不做旁路充电模块 |

## 许可

本项目独立实现，未复制上游代码、资源、商标或截图。上游 [ResistanceTo/MiniWatts](https://github.com/ResistanceTo/MiniWatts) 为 Apache-2.0。
