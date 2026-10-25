# MiniWatts Android 真机验证记录

验证日期：2026-10-12 初测，2026-10-13 修复复测（北京时间）。初测针对手机已有的 1.1；复测使用 Mac 本地签名的独立 1.2 测试包。原应用及历史没有覆盖或删除。下方“初测”部分保留当时观察，修复状态以“1.2 修复复测”为准。

## 初测设备与版本

- 厂商/型号：Xiaomi / 24129PN74C。
- Android 16 / API 36；屏幕 1200 × 2670，密度 520 dpi。
- applicationId：`dev.miniwatts`；versionName 1.1，versionCode 2，targetSdk 35。
- 从设备只读取得的 APK 为 909,853 字节，SHA-256 为 `d7fbe563605c71d09fa45e305718621baa38207e015174d8cf09b4b0df3f16a3`，与交接记录一致。
- 普通 USB 调试支持启动和截图。模拟点击最初报 `INJECT_EVENTS` 权限错误；用户开启“USB 调试（安全设置）”并重启手机后，模拟点击、滑动和返回键可以使用。
- 未在文档中记录设备序列号、Android ID、账户或其他个人标识。

## 1.1 已实际验证

| 检查 | 结果与证据边界 |
| --- | --- |
| 四页导航 | 功率、温度、充电器、历史都能切换，已拍摄真实 APK 截图。 |
| 滚动与安全区 | 默认字体、竖屏下，功率/温度曲线和充电器下半页能滚动到达；底部导航与系统手势区域有独立空间。未验证横屏或大字体。 |
| 温度 | 显示电池温度及系统热状态，明确热状态不是温度；手机图仅显示一个电池示意标记。 |
| 充电器能力 | 实际电源枚举为 AC；输入功率、电压/电流、额定功率、PD 和效率显示不可用，没有把电芯功率作为适配器功率。 |
| 历史详情和返回 | 点击当前会话可打开详情；发送系统返回键后回到历史列表。未测试手势返回。 |
| 英文与深色 | 通过应用设置切换后拍摄四页截图。文本能够换行，导航可操作；发现仪表文字和背景问题，见下文。 |
| 原设置恢复 | 主题和语言恢复为 `system`；采样间隔维持原来的 1 秒。没有开启持续记录或悬浮窗。 |
| 暂停采样 | 返回桌面后，间隔 5 秒读取的两个最新样本时间戳完全相同，说明此次未记录状态下采样停止。 |
| 恢复与积分缺口 | 重开后保持会话 id 3。首个恢复区间为 7,289 ms，continuity 改变；覆盖时长只增加随后两个有效区间的 1,007 + 1,017 = 2,024 ms。数据库净 Wh 增量为 -0.002919145168333248，与仅对这两个区间进行梯形积分的结果一致，没有计入暂停缺口。 |
| 数据库 | 暂停状态下只读复制恢复前后 SQLite；两份 `PRAGMA integrity_check` 均为 `ok`。会话数量保持 3，旧启动的会话已关闭并带恢复标记。此检查不代表保留上限、删除或导出通过。 |
| 崩溃日志 | 本轮最后读取的最近 2,000 条日志中，`AndroidRuntime:E` 过滤结果为空；这只覆盖此次短时操作。 |

采样暂停/恢复使用 `input keyevent 3`、`am start` 和应用私有数据的只读快照进行验证，未修改电池服务状态、传感器节点或手机系统显示设置。

## 1.1 发现的问题

### 1. 电流符号与充电状态冲突，需要机型验证

在 23:26:47 的真实样本中，`plugged=1`、`status=2`，电流为 `-921000 µA`，电压为 `4215 mV`；计算结果 `-3.882015 W` 与原始单位换算一致。平均电流和 energy counter 为 `null`，没有显示为零。温度为 `340`（34.0°C），系统热状态为 0，循环次数为 89。

此后电量从 73% 上升到 75%，charge counter 从 3,774,000 上升到 3,870,000 µAh，但电流仍多次为负；历史详情出现电量 73% → 74% 与负净 Wh 同时显示的情况。

功率页正确保留原始符号并显示冲突警告，但充电器页的电芯指标和历史能量页没有同样醒目的冲突说明。这台机型的充/放电方向及 Wh 的物理解释尚未确认，不能宣称充电净能量验证通过，也不能直接取绝对值。后续应保留原始读数，补充各相关页面的冲突提示，并通过真实插拔及 charge counter 变化核实机型语义。

### 2. 功率仪表的单位与说明偏左

中文浅色与英文深色截图都显示：大数字居中，`W`、净功率说明及量程文字从仪表左侧开始，并与圆弧区域交叠。

对应 `app/src/main/java/dev/miniwatts/MeterViews.kt` 的 `PowerGauge`：数值单独设置了文字居中，而三个说明 TextView 没有设置文字 gravity；父容器居中不能代替这些宽度填满父容器的 TextView 的文字对齐。需修复后在真机重新截图。

### 3. 网格背景刷新后失去原有渐变色

`settings-dark-en.png` 的初始背景为深青，切换到四个实时/历史页面后，背景变为灰色，而卡片仍为深青；在多张截图中重复出现。

对应 `app/src/main/java/dev/miniwatts/UiKit.kt` 的 `GridBackground.draw()`：同一个 Paint 先绘制渐变，随后通过低透明度 `paint.color` 绘制网格。下一次 draw 在绘制渐变前没有恢复 Paint 的 alpha，渐变会沿用网格的透明度。这与观察到的刷新后颜色变化相符；需重置画笔透明度并通过重绘截图验证。

## 1.2 修复复测（2026-10-13）

### 工具链与独立安装

- 复用本机 JDK 17，在 `.tooling/android-sdk/` 安装 Google 官方 Mac command-line tools、Platform 35 和 Build-tools 35.0.0；Gradle 8.10.2 缓存及本地 debug 签名均留在 `.tooling/`。下载 SDK 工具的固定 SHA-256 已校验。
- 新增 `scripts/bootstrap-mac.sh`，修复 `build-local.sh` 在 macOS Bash 3.2 下空数组配合 `set -u` 的失败，并支持复用 PATH 中未注册到 `java_home` 的 JDK。
- 使用 `-PisolatedDebug=true` 构建 `dev.miniwatts.macdebug`，名称 `MiniWatts Test`，versionName 1.2 / versionCode 3。默认包名仍为 `dev.miniwatts`；测试后者会被回归 runner 拒绝。
- 用户开启 USB 安装后，已确认小米限时“继续安装”提示并成功安装应用及测试 APK。原 `dev.miniwatts` 的签名、安装和私有数据保留。
- 应用 APK 为 911,205 字节，SHA-256 为 `28db769f9c7938a58a128d5e8e92e0eb02ce54cd3c36c7b6a5763c7eac90b08d`；`apksigner verify --verbose` 通过 v2 签名验证。包信息确认 minSdk 26、targetSdk 35，权限中没有 INTERNET。

### 修复与回归结果

| 项目 | 修复与实际检查 |
| --- | --- |
| 背景重绘 | 绘制渐变前恢复 Paint alpha=255。修复前真机回归捕获同一像素 `ffeffcfc → 12f1ffff`；修复后连续重绘像素一致，英文深色切页再返回仍保持深青网格背景。 |
| 仪表文字 | 为单位及两行说明设置文字居中。修复前回归发现 W 的中心为 28 px，预期 520 px；修复后单位及说明均在预期中心 ±2 px 内，中文浅色/英文深色截图均居中。 |
| 当前读数提示 | 功率与充电器页共用冲突提示。原生双语视图测试覆盖负电流与 charging 的冲突，以及 null、0、正常正电流和冲突解除；异常时显示，其他情况隐藏。测试样本为明确的回归夹具，不作为真实电池数据。 |
| 历史提示 | 活跃会话、历史卡片、详情及全部会话汇总显示对应冲突说明，双语视图检查通过；无冲突会话不会显示提示。 |
| 冲突保留 | SQLite 升级到 v2，累计 `signConflictObserved`。真机测试 ingest 1502 个夹具样本，确认最初的冲突样本已被裁剪、仅保留 1500 个正常样本；关闭并重开 Store 后会话标记及 JSON/CSV 导出标记仍保留。 |
| 旧数据迁移 | v1 夹具数据库含冲突/正常两会话；迁移后 id、净 Wh、覆盖时长和原始负电流/负功率不变，只补记冲突标记。旧样本不含新增标记，迁移依据原始字段计算。已被旧版本裁剪的样本无法追溯。 |

构建执行 `scripts/build-local.sh -PisolatedDebug=true :app:assembleDebugAndroidTest`，核心 6 个 JUnit 测试、应用 APK、测试 APK 和 lint 均通过。lint 为 0 错误、14 警告，主要涉及既有绘制分配、自定义 View 构造、字符串格式和备份属性；此轮没有以修复全部 lint 警告为目标。

修复前先在同一手机跑失败回归：最初 2 项渲染检查均失败；修复背景后 4 项检查中仅背景通过，对齐和数据库标记仍失败。最终新增双语提示检查后运行 `am instrument -w`，实际输出为 **Tests run: 5, failures: 0**，逐项 status 均为 0。Runner 不依赖第三方测试框架，数据库用例仅清理独立测试包。

### 截图与数据边界

已重新拍摄 1.2 的中文浅色四页、英文深色四页、英文功率页再次重绘和充电器下半页。默认字体/竖屏下仪表说明居中、背景保持正确，电芯读数与适配器不可用说明能正常显示。本轮实时样本主要为电量 79%、电流 0 µA、功率 0 W；历史曲线中也出现短暂正电流读数。零电流没有被当作缺失或冲突，不能据此推断此前负电流问题已消失。

所有修复保留原始符号、单位换算及积分规则。冲突提示明确说明方向未经确认；本轮没有通过真实拔插完成机型方向验证。

另将初测时只读保存的真实 v1 数据库复制到独立测试包验证实际迁移，先备份测试包自己的新历史。升级后 `user_version=2`、`integrity_check=ok`；715 条原始样本 JSON 全部一致，两条已结束会话的原字段全部一致，会话 2/3 补记冲突、会话 1 无冲突。进行中的会话 3 在打开页面后追加了新的真实样本，其净 Wh 从 -0.7107697380911109 变为 -0.7103961501913888；此增量来自随后采样，不作为迁移修改历史的证据。中英文截图确认列表/详情警告可读，中文汇总警告也已截图检查，系统返回键回到列表。截图中的历史源于旧快照，实时状态源于此后采样，不是新记录的完整插拔会话。复查后已恢复测试包自己的历史，主题/语言回到跟随系统；原应用数据未写入。

最后核对测试包恢复的数据库与备份逐字节一致，再打开测试版功率页。原包仍为 1.1 / code 2，数据库仍为 v1，完整性为 `ok`。最后截图中的真实读数为 1010 mA / +4.29 W（充电中）；这表明本轮也读到正电流，不能采用一律反号的机型修正。最近 2000 条日志的 `AndroidRuntime:E` 过滤结果为空，仅代表此次短时操作未捕获崩溃。

## 本地证据

证据保存在 gitignored 的 `.tooling/device-check/` 中，截图和数据库不会随本报告提交到远程：

| 内容 | 文件 |
| --- | --- |
| 中文/系统浅色四页 | `power.png`、`thermal.png`、`charger.png`、`history.png` |
| 曲线与下半页 | `power-bottom.png`、`thermal-bottom.png`、`charger-bottom.png` |
| 历史详情与系统返回 | `history-detail.png`、`history-detail.xml`、`history-back.png` |
| 英文深色四页 | `power-dark-en.png`、`thermal-dark-en.png`、`charger-dark-en.png`、`history-dark-en.png` |
| 设置及采样快照 | `settings-before.xml`、`settings-restored.xml`、`paused-first.xml`、`paused-second.xml` |
| 暂停/恢复数据库 | `db-before-pause.sqlite`、`db-after-resume.sqlite` |
| 版本和系统对照 | `installed.apk`、`battery-service.txt`、`runtime-errors.log` |

实时页面每秒更新会导致部分 `uiautomator dump` 无法取得 idle state；失败后取得的旧 XML 不作为页面验证证据。本轮页面显示以实际截图为准。

1.2 证据保存在 gitignored 的 `.tooling/device-check-v1.2/`：`*-zh-light.png`、`*-en-dark.png`、`power-en-dark-redraw.png`、`charger-en-dark-bottom.png`、`legacy-history-*`、`legacy-detail-*`、`legacy-cards-en-dark.png`、`legacy-summary-zh-light.png`、`legacy-migration-check.json` 及迁移前后数据库。构建/安装/回归日志位于 `.tooling/final-build.log`、`final-install-*.log`、`final-regressions.log`；失败基线为 `baseline-regressions.log`、`intermediate-regressions.log`。APK、密钥、设备截图与数据库均不提交 Git。

## 未覆盖与下一步

- 未执行大字体、横屏、窄屏、手势返回，以及原版截图逐项视觉对照。
- 未执行持续记录、锁屏/深度休眠、通知允许/拒绝流程、OEM 后台限制、真实插拔、悬浮窗、Widget 或 SAF 导出/删除测试。
- Store 的 JSON/CSV 导出已通过回归测试，但系统 SAF 文件选择器的保存、取消流程尚未验证。
- 下一步优先验证真实插拔与电流方向，随后补充持续记录、锁屏/休眠、通知、悬浮窗及 Widget。原服务器 debug 密钥不在本机，继续使用独立测试包，不擅自卸载原应用。
