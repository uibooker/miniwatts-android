# MiniWatts Android

中文优先的原生 Android 电池仪表。最低 Android 8.0 / API 26，Kotlin，target/compile API 35。离线运行，无联网权限、广告、埋点或账户。独立实现，灵感来自 [ResistanceTo/MiniWatts](https://github.com/ResistanceTo/MiniWatts)（Apache-2.0），未复制其代码、图像、商标或资源。

## 使用

1.1 界面依据四张原版截图的结构重做：冷白/浅青渐变与低对比网格、24dp圆角卡片、大功率圆弧仪表、独立电池指标、带坐标及采样时间的真实曲线。深色模式使用独立深青表面。没有复制参考截图或其中的读数。

底部胶囊图标导航包含功率、温度、充电器、历史；右上角齿轮进入设置，诊断和离线帮助从设置进入。历史卡片点击可查看详情，历史操作菜单包含JSON/CSV导出和删除全部；详情保留导出历史及删除本会话入口。窄屏/大字体时指标改为纵向排列，导航占独立空间，不覆盖滚动内容；API 35处理系统栏安全区，交互入口至少48dp。

安装 `app/build/outputs/apk/debug/app-debug.apk`，或连接自己的设备后执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。本项目构建不包含原生应用库，可在 ARM64 / ARM / x86 Android 上安装；实际读数取决于设备驱动。

- 功率：电量、带符号电芯净功率、电流、电压、温度、充放电状态和电源类型、最近 120 个样本趋势。
- 温度：电池温度、API 29+ 系统热状态。系统热状态不是额外温度探头。
- 充电器：真实电源类型和能力矩阵。适配器输入功率、PD、身份、容量健康百分比缺少通用公开 API，显示不可用。
- 历史：插拔会话、净电芯 Wh、有效覆盖时长、峰值和平均功率、功率/温度曲线。默认最多 60 会话，每会话最多保留最新 1500 原始样本，累计积分及峰值仍覆盖全部已测区间。可删除单条/全部，导出 CSV/JSON 到系统文件选择器。
- 设置：1/2/5/10/30 秒采样、摄氏/华氏、中文/英文/跟随系统、深浅主题/跟随系统、10/30/60 会话、充电时常亮。记录、悬浮窗、常亮默认关闭。
- 主动开始持续记录后前台服务运行，通知有读数、记录时长和停止按钮。启用悬浮仪表需单独授予悬浮窗权限，再返回点击开启；拖动读数区移动，窗内可关闭。悬浮窗依附记录服务，关闭窗不停止记录，停止记录会移除窗。
- Widget 在桌面手动添加，显示“上次采样 · 非实时”和采样时间。系统约每 30 分钟调度一次（可能延迟），App 采样时最多每分钟刷新。不会每秒唤醒 Widget。

## 数据边界

`ACTION_BATTERY_CHANGED` 提供 level/scale、状态、类型、温度（0.1°C）、电压（mV）、健康状态。`BatteryManager` 的瞬时/平均电流为 µA、charge counter 为 µAh、energy counter 为 nWh。循环次数只在 API 34+ 广播实际含有效值时显示。`MIN_VALUE/MAX_VALUE` 哨兵和缺失属性为 null，不伪装成 0；真实电流 0 可有效。health 是系统健康状态，不是容量健康百分比。

`W = µA × mV / 10⁹` 是**电芯侧带符号净功率（推算）**，不是充电器输出功率。公开 API 正号表示流入、负号表示流出。厂商符号与状态冲突时保留原始值并提示，绝不无条件取绝对值。电流/温度等 API 读数标为实测，W、积分 Wh 标为推算；每个样本附墙钟时间、单调时间、启动编号和来源。诊断页/JSON 保留原始单位；CSV 通过列名给单位，缺失为空字段。

积分只连接同次启动、单调 `0 < dt ≤ 3×相邻采样间隔最大值` 且 `dt ≤ 60s`、两端功率有效且连续采样标记一致的样本，使用梯形积分和单调 dt。暂停/重新开始采样分配新标记，即使很短的已知暂停也不积分。曲线也遵守同样缺口规则。正负变化按带符号净能量积分，平均功率=净 Wh/有效覆盖小时数。覆盖为 0 时平均功率不可用，净 Wh=0 表示没有积分区间。没有输入功率，所以不计算效率或成对能量比例，不把负载/损耗称为热量。

进行中会话写入 SQLite；切页、旋转、锁屏不拆会话。同次系统启动的长采样缺口保留会话但不积分；系统重启/单调时间重置则关闭旧会话于最后观测时间。离线期间拔出再插入无法被普通 App 事后知道，可能归入同一会话；不声称它是完整连续的插拔日志。采样停止时发生的拔出只能在下次采样观察到。历史列表可重新进入刷新。

本版未提供任何经机型验证的 sysfs 白名单，因此不会读任意节点，不调用 su、不申请 root。额外探头、适配器输入、PD 协商均清楚展示不可用及原因；后续添加节点必须先确认机型、权限、语义、单位，并保留原路径和来源。

## 架构

- `core/`：无 Android 依赖的 Sample/Session 模型、单位/哨兵转换、单调时间积分、插拔及恢复决策，JUnit 测试可单独运行。
- `SensorReader`：只读平台采集，API 版本判断与 null 降级。
- `Store`：SQLite 事务、会话恢复、样本保留、积分统计、删除和导出。
- `Engine`：单个后台执行器串行采集/数据库/导出；主线程分发读数。界面/服务共享采样器，避免切页、旋转时重复记录。
- `MainActivity`：导航、生命周期、设置和权限/导出流程；`UiKit`：主题颜色、圆角表面、卡片、详情行、指标列、Canvas图标和网格背景；`MeterViews`：功率仪表、电量条、单电池示意。
- `LivePanels`：功率/温度/充电器页面及真实样本绑定，不按每秒采样重建页面；`HistoryPanels`：会话汇总、卡片与详情；`ChartView`：带轴/时间的断线曲线；`Words`：中文/英文。
- 历史数据与趋势预览在`Engine.io`执行；列表每会话只查询最新120样本，详情读取保留的1500样本。`RecordingService`管理前台通知和悬浮窗；`MeterWidget`管理系统低频刷新。

没有使用数据库/界面框架依赖；Gradle 8.10.2、AGP 8.7.3、Kotlin 2.0.21、JUnit 4.13.2 固定版本。Wrapper 分发包有 SHA-256 校验。

## 构建与测试

标准 Linux x86_64 / macOS / Windows：使用 JDK 17、Android SDK platform 35 和 build-tools 35.0.0，设置 `JAVA_HOME` 和 `ANDROID_HOME`（或在 gitignored `local.properties` 中配置 `sdk.dir`），运行：

```bash
# Debug key also stays inside the project; generate once
mkdir -p .tooling
keytool -genkeypair -keystore .tooling/debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 10000
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

本宿主 Linux ARM64 无预装 Java；Google SDK 的 Linux `aapt2` 是 x86-64，直接运行得到 `cannot execute binary file: Exec format error`。本项目实际采用 [lzhiyong/android-sdk-tools 35.0.2](https://github.com/lzhiyong/android-sdk-tools/releases/tag/35.0.2) 的 ARM64 静态 aapt2，使用 AGP 的 `android.aapt2FromMavenOverride`，不修改官方 SDK 或系统。替代工具与下载包均在 `.tooling/`；它是第三方构建工具，只用于本地，CI 使用 Google 官方 x86 工具。

```bash
# 可复现 ARM64 项目内初始化，SDK 许可证由操作者确认
scripts/bootstrap-arm64.sh
# 复用项目 JDK/SDK，自动选择 ARM64 aapt2，运行测试、APK 与 lint
scripts/build-local.sh
# 单独核心测试（配置 SDK 或 local.properties 后）
JAVA_HOME="$PWD/.tooling/jdk-17.0.20.1+1" \
GRADLE_USER_HOME="$PWD/.tooling/gradle-home" ./gradlew :core:test
```

初始化要求约 2.5GB 可用空间，无 apt、模拟器镜像或全局配置修改；下载工具带固定 SHA-256。构建日志在本次工作目录 `.tooling/build.log`，本次UI重做日志为`.tooling/ui-redesign-build-final.log`；最终结果见`UI_REDESIGN_REPORT.md`，首版实现记录见`IMPLEMENTATION_REPORT.md`。`.github/workflows/android.yml` 使用 GitHub x86 Ubuntu 24.04 执行核心测试/assembleDebug/lintDebug 并上传 APK 与报告。创建工作流不代表已在 GitHub 执行；本项目未推送。

## 权限与兼容性

- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`：用户主动开始的本地电池仪表记录和可选悬浮仪表。API 34+ 显式使用 specialUse，Manifest 提供真实 subtype。不是 dataSync，也不以插电假定 connectedDevice。
- Android 13+ `POST_NOTIFICATIONS`：主动开始记录时申请。拒绝仍可开始服务，系统可能不在通知栏显示，App 设置页有停止按钮。
- `SYSTEM_ALERT_WINDOW`：用户主动点击悬浮仪表才打开授权页，拒绝不影响其他功能。
- 文件导出使用 Storage Access Framework，不要广泛存储权限。没有 INTERNET、READ_PHONE_STATE、位置权限。备份关闭，不导出序列号/Android ID/账户等标识；诊断含厂家、型号、系统 API。

前台界面默认每秒采样；离开且未记录即停止。记录不持有唤醒锁、不申请电池优化豁免，深度休眠、OEM 杀后台或系统强杀可能产生缺口；服务 `START_NOT_STICKY`，不会未经用户重新主动启动。无需精确闹钟。发行到 Google Play 时 specialUse 的实际用途仍需提交审核，本工程的类型声明不是商店审核结论。

平台依据（本次实际读取）：[BatteryManager](https://developer.android.com/reference/android/os/BatteryManager)、[PowerManager](https://developer.android.com/reference/android/os/PowerManager)、[前台服务类型 specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)。

## 真机验收步骤

1. 在 API 26、29、33、34/35 设备安装 debug APK，检查中文/英文、系统/深浅主题、大字体、旋转、边缘安全区；断网也应可运行。
2. 电池供电及 AC/USB/无线接入，和设备电池接口比对 level、温度、电压、状态；确认 µA→mA、mV→V 与带符号 W。unsupported 时必须为不可用，真实 0 不能被误判缺失；异常符号出现警告。API 34+ 检查 cycle extra 有无。
3. 切页和旋转不拆会话；不记录时回桌面暂停，返回图中断线且 Wh 不跨缺口。开始持续记录，锁屏数分钟后检查记录、通知、覆盖时间、休眠缺口；拔插出现新会话，停止后不再记录。
4. 杀进程后重开验证历史持久化；系统重启后旧会话在最后观测处结束；确认不假称离线插拔被检测。记录服务被系统杀死需手动重启。历史中推断关闭的会话注明结束时间不确定。
5. 验证通知权限允许/拒绝、悬浮权限允许/拒绝、拖动、窗内关闭、停止服务移除悬浮窗；拒绝后其他页照常使用。
6. 桌面添加/调整 Widget，点击打开 App，显示采样时间和非实时标识；检查系统低频及服务更新节流。
7. 建立多个会话并检查曲线、有效覆盖、净 Wh/峰值/均值；改变保留上限、超过 1500 样本、删除单条/全部；选取 SAF 文件位置导出 JSON/CSV，缺失字段和单位正确，取消导出无副作用。
8. 诊断与帮助确认适配器功率/PD/额外探头/容量健康百分比不可用，没有伪造读数。充电时常亮默认关，开启仅影响 App 前台。

当前无 ADB 连接设备，项目内也没有模拟器镜像或可执行的layoutlib渲染库，以上真机流程和此次UI渲染验收尚未执行，没有产出APK页面截图。核心逻辑测试/编译/lint 的通过不代替传感器、服务、悬浮窗、Widget 或系统文件选择器的真机验收。
