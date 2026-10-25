# 会话对比页与导出中心：实现与验证记录

日期：2026-10-15。版本：1.10（versionCode 11）。范围：可选项 ⑤ 会话对比页、⑥ 独立导出中心页。**两者都不新增数据库列，因此没有迁移。**

真机：Xiaomi 24129PN74C / `dada`，Android 16 / API 36，USB（`（已省略）`）；本轮 Shizuku 以 **root** 身份运行，功率页读回「适配器输入 7.1 W · 实测」，因此对比页的实测一侧来自真实输入轨。

---

## 0. 结论

| 项 | 结果 |
| --- | --- |
| 核心单测 | **32 项通过**（新增 3 项：对比缺失值、窗口闸门、导出目录完整性） |
| 真机回归 | **16 项通过**（新增 3 项，见 §4） |
| `assembleDebug` / `assembleDebugAndroidTest` | 通过 |
| `lintDebug` | 0 错误 / **28 警告**，全部落在既有 6 个文件；两个新页面与两个新 core 文件 **0 警告** |
| 触屏截图 | 导出中心、对比选择、对比页（表头对齐与单行数值）均已确认 |
| 未验证 | 系统文件选择器实际落盘（SAF 交互）、英文界面、大字体/横屏、窄屏 |

---

## 1. ⑤ 会话对比页

### 1.1 交互

历史页工具栏新增第三个动作「对比会话」。进入后历史列表的每张卡片多出一行「选中对比」（卡片本身仍可点击进入详情，长按也可选中，因为长按不可发现）；顶部提示卡实时显示「已选中 n/2」。选满两个即进入「会话对比」子页（page 10，不占底部导航）。系统返回键**先退出选择**而不是退出应用；在对比页按返回回到历史并保留已选的一对，便于换一个再比。

### 1.2 逐项口径

`core/SessionCompare.kt` 把 `Session` 折算成 `CompareSide`，页面再生成 `CompareRow` 列表。字段：开始、结束、时长、电量起止、电量变化、电芯净能量、平均电芯功率、峰值电芯功率、有效覆盖、最高电池温度、适配器输入能量、适配器平均输入功率、电芯获得比例、符号冲突、额定功率/通路电阻。

三条与既有约束一致的规则：

1. **缺失不进数字。** 任一为 null 的字段在该侧显示「不可用」，差值也为「不可用」，绝不当作 0；`levelDelta` 只有两端都观测到才存在。
2. **比例要有闸门。** 两个会话的覆盖窗口通常不同长。`SessionComparison.ratioComparable` 只在 `min(covered)/max(covered) ≥ 0.5` 时成立，与 1.8 会话「电芯获得比例」的闸门同一口径（两个不同窗口的比值不算比值）。窗口相差过大时页面显示橙色说明，**只给绝对差值**，因为绝对差是两个各自窗口上的积分之差，本身就是诚实的减法。为负的「电芯获得比例」是真实测量（插电但系统耗电超过输入、电芯净放电），不是错误。
3. **输入侧只有真的测到才出现。** `measuredInputWh` 在 `inputCoveredMs == 0` 时为 null（即「从未测到」），此时该侧显示「不可用」；`inputWh == 0` 但覆盖 > 0（确实测到接近 0 的输入）仍给出 `0.000 Wh`，两者可区分。「额定功率 / 通路电阻」标注「无来源」，因为内核与系统都不向第三方提供。

### 1.3 排版上的三个具体修正

1. **表头与数据列对齐。** 第一版表头只有 A/B/差值三格，而数据行有「标签 + A + B + 差值」四格，于是表头落在错误的列上。现在表头用一个空的首格，列宽权重由表头与数据行共用（`LABEL_WEIGHT` / `VALUE_WEIGHT` / `DELTA_WEIGHT`）。
2. **数值单元格缩字而不是换行。** 列宽是卡片的固定比例，日期时间这类长值原本会折成两行，行高不一致。现在 A/B/差值单元格 `maxLines = 1` 且自动缩字（11–14sp、10–12sp），任何语言与任何长度都保持一行。
3. **时间取到分钟。** 表格里「开始/结束」用 `Words.timeMinutes()`（`MM-dd HH:mm`），秒只在描述单条读数的地方保留（会话详情与导出仍是完整时间）。时长类差值改用 `Words.signedDuration()`，读作 `-1m 30s` 而不是 `-90000 秒`。

---

## 2. ⑥ 独立导出中心

`core/ExportCatalog.kt` 是一份 **对 `ExportKind` 全覆盖**的目录：历史 JSON、历史 CSV、诊断 JSON、probe 原文；`catalog()` 会断言每种格式恰好出现一次、文件名与 MIME 非空，新增格式若忘了登记就会在开发期直接失败，而不是从界面上静默消失。新增格式 = 加一个 `ExportKind` + 一个目录项 + 一个写出分支。

界面（page 9）由目录生成四行动作，每行下方说明内容与单位口径，末尾一段说明隐私边界（只在你选定位置后写入、取消无副作用、不含序列号/Android ID/账户标识）。入口：历史页工具栏、设置页首行、诊断页的「打开导出中心」。

`ExportCenter` 成为诊断 JSON 与 probe 原文的**唯一组装点**，此前这两份文档内联在 `onActivityResult` 里，不经过系统文件选择器就无法断言内容。现在真机用例可以直接渲染同一路径并检查字段。

四个入口的历史行为保持不变：历史页右上角菜单、会话详情的「导出历史」仍直接进入 JSON/CSV。

---

## 3. 改动文件

| 文件 | 改动 |
| --- | --- |
| `core/SessionCompare.kt` | 新增：对比口径（`CompareSide` / `CompareRow` / `SessionComparison`），无 Android 依赖 |
| `core/ExportCatalog.kt` | 新增：导出目录契约（`ExportKind` / `ExportEntry` / `ExportCatalog`） |
| `app/ComparePanels.kt` | 新增：对比页渲染，列宽共用、数值缩字 |
| `app/ExportCenter.kt` | 新增：诊断 JSON 与 probe 原文的组装 |
| `app/MainActivity.kt` | page 9/10、工具栏三动作、选择状态机、导出走目录 |
| `app/HistoryPanels.kt` | `list()` 增加可选的选择模式（默认 null 时列表与 1.9 完全一致） |
| `app/Words.kt` | `timeMinutes()`、`signedDuration()` |
| `app/build.gradle.kts` | versionCode 11 / versionName 1.10 |
| `core/.../CoreTest.kt` | 新增 3 项单测 |
| `app/src/androidTest/.../RegressionRunner.kt` | 新增 3 项真机用例 |

---

## 4. 验证

### 4.1 核心单测（32 项，0 失败）

新增：

* `comparisonKeepsUnavailableValuesOutOfTheNumbers` — 缺失电量端点不产生变化值；未实测的输入（`inputCoveredMs == 0`）报不可用，而**实测到的 0** 仍是一次测量。
* `comparisonWithholdsRatiosUntilBothWindowsAreComparable` — 覆盖窗口比恰好 0.5 时给比例、1/3 时不给，但绝对差值仍在；任一侧窗口为 0 时不做除法。
* `exportCatalogueIsCompleteAndNamingIsTotal` — 目录覆盖全部 `ExportKind`、文件名唯一、历史/诊断分类正确。

### 4.2 真机回归（16 项，0 失败）

新增：

* `exportCentreListsEveryDocument` — 目录覆盖全部格式；用**真实设备身份**渲染诊断 JSON，断言厂家/型号/API/补丁/内核、能力矩阵与边界文本、特权通道字段、样本内嵌（含 `adapter.inputW`）；缺失样本导出为 `null`；probe 原文保留换行且不会打印 Kotlin 的 `null`。
* `comparisonRendersBothSessionsWithoutInventingValues` — 在真实控件上渲染一对会话（一侧有实测输入、一侧没有）：断言 A/B 数值、能量差值的格式化、缺峰值温度时差值为「不可用」、**页面上不存在被当作 0 的未实测输入**、以及窗口相差过大时出现不给比例的说明。
* `historyOffersTheComparisonSelection` — 走完整真实点击路径：历史标签 → 对比动作 → 选满两个会话 → 进入并列页；断言工具栏进入「取消对比」状态、两侧会话标题都是真实 id。**这条用例在开发中真的抓到了缺陷**：第一版把选择状态放在「当前页 == 历史」这一条件下重置，于是页面切到对比页的瞬间把选中的一对清空，对比页只能报「选中的会话已被删除」。

### 4.3 排版复核（触屏截图）

导出中心四行与说明、对比页表头与数据列对齐、每行单行数值、覆盖窗口闸门的橙色说明、以及 `1s` 级会话的 `+0s` 差值显示均已截图确认。

---

## 5. 本轮踩到并记录的两个工程事实

1. **插桩测试运行在被测应用进程内。** 因此 `am force-stop <包名>` 会杀掉测试进程本身（表现为 `shortMsg=Process crashed.`），而 `targetContext.applicationContext as MiniWattsApp).engine.store` 与 Activity 读的是**同一个连接**。用例顺序因此有真实约束：凡调用 `deleteDatabase` 的用例（迁移类）必须排在**首次使用 engine store 之前**，否则连接指向已被移走的文件，下一次写入报 `SQLITE_READONLY_DBMOVED`。对比页的两条 UI 用例因此固定在 `capacityEstimateReadsStoredChargeSpan` 之后、`inputEnergyMigratesAndAccumulates` 之前，代码里有注释说明原因。
2. **本机 MIUI 拒绝 `adb shell input tap`**（`SecurityException: Injecting input events requires the caller to have the INJECT_EVENTS permission`），因为未开启「USB 调试（安全设置）」。真机用例改用 `performAccessibilityAction(ACTION_CLICK)`，这也正是 uiautomator 与无障碍服务使用的路径；对刚被 io 线程重建过的卡片，普通 `performClick()` 会丢失，无障碍动作不会。

截图与临时脚手架（一次性 seed 脚本、截图用例）**均不在仓库内**：`.tooling/` 或 `/tmp`，截图用例在复核后已从回归清单删除。
