# MiniWatts Android

中文优先的原生 Android 电池仪表。最低 Android 8.0 / API 26，Kotlin，target/compile API 35。离线运行，无联网权限、广告、埋点或账户。独立实现，灵感来自 [ResistanceTo/MiniWatts](https://github.com/ResistanceTo/MiniWatts)（Apache-2.0），未复制其代码、图像、商标或资源。

## 使用

1.1 界面依据四张原版截图的结构重做：冷白/浅青渐变与低对比网格、24dp圆角卡片、大功率圆弧仪表、独立电池指标、带坐标及采样时间的真实曲线。深色模式使用独立深青表面。没有复制参考截图或其中的读数。

1.2 修复仪表单位/说明居中和背景重复重绘透明度；充电器、历史列表/详情及汇总增加电流符号冲突说明。会话永久保留已观察到的冲突标记，JSON/CSV 同时导出会话和样本标记；原始电流、功率符号和积分值保持原样。

1.3 按 `img/` 中七张 iOS 实际使用截图更新五页结构、卡片层级、标题、胶囊导航、双圈仪表与曲线填充。适配器输入圈保留为空，电芯圈显示带符号电芯功率对应的绝对值量程；数值保留符号。新增供电路径、本次充电摘要、设备页、电量与温度分区曲线和历史小曲线。参考中的适配器输入、PD 档位、线缆阻抗和多探头温度没有对应数据时显示不可用，不填入截图读数。验证记录见 [IOS_ALIGNMENT_REPORT.md](IOS_ALIGNMENT_REPORT.md)。

1.4 接入经本机实测可读的适配器数据：系统电池广播中的**充电器协商上限**（最大充电电压 × 最大充电电流）、`invalid_charger` 合规标记，以及小米粘性广播的快充类型与上报档位。新增**可选 Shizuku 增强**：开启后由一个只读白名单用户服务在 Shizuku 进程内尝试读取输入侧实时电压/电流、真实充电器类型、PD 状态机与认证、满充/设计容量与厂商 SOH；Shizuku 以 ADB 身份运行时厂商策略会拒绝这些节点，读数保持不可用。有输入侧实测读数时，功率页才显示推算的负载与损耗、电芯占比。调研与实测证据见 [ADAPTER_POWER_SOURCE_RESEARCH.md](docs/ADAPTER_POWER_SOURCE_RESEARCH.md)。

1.5 发热页改为真实热区读数：读取内核 `/sys/class/thermal`（本机 73 个温度热区中 70 个免权限可读），按功能分组显示各组当前最高温，并给出热缓解档位（cooling device）与 CPU 各调频策略的实时/最高频率；示意图的充电芯片与 SoC 槽位改用对应热区实测值。**按名称剔除不是温度的节点**（`vbat` 是毫伏、`*-ibat-lvl*` 是电流、`socd` 是百分比），`-273000` 视为传感器缺失，绝不当作温度。设备页补补丁级别、内核版本与电池技术；诊断页补热区摘要与特权 probe 原始输出，诊断导出补完整热区表、cooling 表、CPU 策略、被拒/非温度节点清单与 probe 原文。实测与证据见 [THERMAL_ZONES_RESEARCH.md](docs/THERMAL_ZONES_RESEARCH.md)。

1.6 把已经拿到的输入侧实测数据送到各处：通知与悬浮窗摘要、桌面 Widget、以及功率页趋势图的第二条曲线。趋势图的两条线**共用同一个 W 量程**（适配器输入参与量程计算），缺口规则与既有曲线一致；Widget 在多出这一行时自动把字号从 16sp 降到 14sp，避免在默认 100dp 高度里被裁掉。所有新增内容**只在特权通道真的读到输入轨时出现**，没有通道时文本与曲线与 1.5 完全一致，不会把"没通道"显示成 0。

1.7 新增「性能与热区」子页（从发热页进入，不占底部导航）：CPU 各调频策略的当前/最高频率与比例条、全部 cooling device 档位（生效项优先）、**全部热区按温度降序列出**，以及**本次会话内**的 CPU 频率比例历史（每约 2 秒一点、最多 90 点；不落库，重启即清空，长时间停止采样会画出断口而不是连成直线）。设备页新增「容量估算（无特权通道时）」：满充容量 ≈ 充入电荷 ÷ 电量上升百分比，取多次充电记录的中位数，需要一次电量上升 ≥20% 且电荷计数器可读的充电；不足条件时显示"还没有可用记录"而不是 0，且当特权通道已读到真实满充/设计容量时会用它收紧合理性检查（偏离 0.5–1.5 倍的估算直接丢弃）。这条估算在文案里始终标注为**估算而非实测**。

1.8 会话级输入能量：`sessions` 表新增 `inputWh` 与 `inputCovered`（**v2→v3 迁移**，旧会话完整保留，但其输入能量报「不可用」而不是 0）。「本次充电」新增「适配器输入」与「电芯获得比例」两项。比例 = 电芯净能量 ÷ 适配器输入，**刻意不叫效率**：它可能大于 100%（电芯同时还在使用先前存的电），也可能为负（充电器只覆盖了部分负载、电芯在放电）。只有当输入实测覆盖达到电芯窗口的一半时才给比例——两个不同窗口的比值不算比值。CSV/JSON 导出同步新增该会话级字段（`schema` 升为 3）。

1.9 修发热页的对齐：此前「温度探头」把中文分组名和等宽内容塞进同一个等宽文本里用 `padEnd` 补齐——但 `padEnd` 数的是**字符数**，而 Android 的 `MONOSPACE` 对中文会回退到**比例字体**，所以列根本对不齐。现在分组名改成一列真实的左列 View（与字体度量无关），右列只放纯 ASCII 的等宽内容，因此探头名与温度各自成一列。同一张页面上「热缓解」列表原本完全没补齐、数值散开，改为按最长名字自适应补齐；「CPU 频率」也改为名字与当前值都补齐，让分隔符与上限值对齐。新增真机用例 `probeColumnsLineUp` 断言五行的数值列 x 坐标完全一致。

1.10 补上两个不新增数据的能力页，**都不需要数据库迁移**。**会话对比**（历史页工具栏进入，不占底部导航）：在列表里点选两个会话即并列比较——开始/结束/时长、电量起止与变化、电芯净能量、平均与峰值功率、有效覆盖、最高电池温度、适配器输入能量与平均输入功率、电芯获得比例、符号冲突，逐项给出 A/B 与差值；下方给出两条独立的电芯净功率曲线（有实测输入时叠加适配器输入）。两侧覆盖时长通常不同，因此**只在两段覆盖窗口相差不超过一倍时才给比例**，窗口相差过大时只给绝对差值与各自均值，页面上直接说明理由；任一缺失项显示「不可用」而不是 0，适配器输入只有在特权通道真的读到输入轨时才有一侧出现数值。**导出中心**（历史页工具栏与设置页均可进入）：把此前分散在历史菜单、诊断页和会话详情里的四份文档集中到一个页面——历史 JSON、历史 CSV、诊断 JSON、probe 原文，每项标注内容与单位口径；诊断与 probe 原文的组装移到 `ExportCenter`，从而可以不经系统文件选择器直接断言其内容。会话详情与历史菜单的导出入口保留不变。实现与验证记录见 [EXPORT_COMPARE_REPORT.md](EXPORT_COMPARE_REPORT.md)。

1.11 **移除「旁路充电」卡**。这张卡读取的是可选 KernelSU 模块 `miniwatts_bypass` 的自报状态，而该模块**从未开发**——[BYPASS_CHARGING_CONCLUSION.md](BYPASS_CHARGING_CONCLUSION.md) 的结论是本机做不到真正的硬件旁路（电池退出供电路径），而能做到的 FCC 限流解决不了原始问题，因此调研到此结束。也就是说这张卡永远只会显示「未安装模块」或「未启用通道」，属于向用户承诺一个不存在的功能，故整条链路一并删除：适配器页的卡片、`BypassModule`、`MainActivity` 的周期读取、`PrivilegedReader.bypassStatus()`、用户服务里的脚本执行分支，以及 AIDL 的 `bypassStatus()` 方法。删掉服务端方法同时**缩小了特权服务的可信面**：该服务不再有任何执行外部脚本的能力，只剩固定白名单的只读节点读取。适配器页其余卡片（握手信息、线缆与连接等）不变；真机用例新增断言，保证该页不再出现「旁路」字样且其余卡片仍在。

1.12 修符号冲突暴露出的三处「把不确定说成确定、把有说成没有」。**① 会话级「电芯获得比例」补上冲突闸门（1.14 已撤回，见 1.14 条）**：当时的判断是「负比例的分子符号被冲突质疑」，于是只要会话出现过冲突就不给比例，适配器输入能量照常显示。**② 历史卡片与详情的「适配器输入」不再硬编码 `— Wh`**：1.8 已经加了会话级 `inputWh`，这两处却仍在无条件声称「没有数据」，详情底部也还写着「没有适配器输入能量或效率数据」；现在有实测就显示数值、没实测才显示「不可用」，详情新增「电芯获得比例」一行并重写该说明（覆盖不足一半时不给值；为负表示该时段电芯净放电——1.14 撤回了当时附带的冲突闸门）。**③ 冲突提示改成可操作的说明**：原文只有一句「保留原始值，功率方向未经确认」，现在说明是哪两个读数矛盾、为什么保持原样（不取绝对值）、哪些派生量因此不计算，并指出方向应以电量趋势判断（1.13 起这段说明移到帮助页，实时页面只留一枚徽标）。顺带修掉 1.10 引入的一个工具栏回归：会话详情页此前会显示「对比会话」按钮，而对比需要历史列表，现在详情页隐藏它。

1.13 **把 1.12 的冲突提示缩回一枚徽标**。1.12 把解释写全了，但写成了一整段话贴在功率仪表下方——这个页面的用途是一眼看功率，不是读说明。现在实时页面（功率/适配器/设备）只显示一枚橙色胶囊「符号冲突 · 功率方向未确认」，与「充电中」「正常」并列；完整解释移到帮助页的「读数规则」之后。会话级与汇总级提示同步缩短为一行。真机用例新增契约：实时页面那串文本长度必须保持在一行以内，且帮助页的解释长度至少是它的 3 倍——把「徽标在实时页面、解释在帮助页」这个分工钉住，避免下次又写回一段话。

1.14 **纠正 1.12 的因果判断，并让表盘上的功率不再带符号。**1.12 把「状态=充电中 + 电流为负」解释成**厂商电量计符号反转**，并据此撤掉了会话级比例——这个解释是**错的，且没有证据**。实测（公开 API 与厂商节点 `battery/current_now` 读数一致）显示：状态 `2` 描述的是**策略**（已插电且允许充电），而电芯净电流是此刻真正流入/流出电芯的量；插着电但系统耗电超过输入时，电芯**确实**净放电（实测同一时刻输入 7.05 W、电芯 -5.1 W，对应系统负载约 12 W；另一时刻输入 2.6 W、电芯 0.00 A，连测六次都为 0）。因此那个 `-68 %` 是**真实测量**（该时段电芯净放电 0.158 Wh、适配器输入 0.232 Wh），不是符号 bug。据此：**撤回 1.12 的比例闸门**（比例照常给出，为负表示该时段电芯净放电，1.8 起就是这么写的）；帮助页与徽标文案改为陈述真实机制（「状态与电芯方向相反」）；把「部分机型电量计报负数」这句没有依据的话删掉。同轮做了一个纯显示决定：**表盘、供电卡与通知上的电芯净功率去掉正负号**，改显示绝对值，并新增一枚「电芯充电 / 电芯放电」说明给出方向——理由是表盘圆弧本来就按量程绘制（`abs`），数字却带着符号；而方向由文字说清比由一个紧挨着「充电中」的负号说清更不容易误读。**带符号的原始值全部保留**：样本、趋势曲线、电芯电流、净能量与电芯获得比例、以及全部导出都没有改动，数据库列名 `signConflict` 也不变（因此仍然不需要迁移）。真机用例新增断言：负读数在表盘上不出现符号、量值正确、方向说明为「电芯放电」；被标记的会话仍然给出（可能为负的）比例。

1.15 对照上游 [ResistanceTo/MiniWatts](https://github.com/ResistanceTo/MiniWatts) 的实测口径，补两道我们缺的闸门，并修正文档里对上游的错误描述。**① 输入读数偏小的合理性闸门**（照搬上游 `partialInputWatts`）：能量只能经充电器单向流入，所以「进入电芯的功率远高于端口读数」只可能是**端口读数偏小**（厂商驱动只暴露了其中一路输入），而不是电芯凭空多得。判据 `电芯 > 输入 × 1.2 且 差值 > 1.5 W`（留 margin 是因为两条轨是不同传感器、读取有先后，负载阶跃会让电芯短暂领先几个百分点；上游实测到的真实案例是 1.8× 与 2.6×）。一旦判定偏小：`trustedInputW` 为 null，**负载与损耗、电芯占比、会话比例全部不算**，页面写明原因；**原始读数照常保留**（样本 JSON 增 `adapterInputTrusted` / `adapterInputPartialW`，CSV 末尾追加 `adapter_input_trusted,adapter_input_partial_W`，历史 JSON `schema` 升为 4），因为它是关于设备本身的证据。**② 逐样本与会话的「比例」都要求电芯确实在获得能量**（上游 `chargePathEfficiency` 要求 `batteryWatts > 0`）：电芯在净放电时「进到电芯的比例」根本不是一个比例，所以不再给负值——逐样本另按上游上限截到 100%（超出那几个百分点属于两条轨的读取时序，更大的差距已被①判为输入偏小）；会话级在电芯净放电时不给比例，但**净能量与输入能量两个数本身照常给出**，所以没有隐藏任何东西。会话级仍允许 >100%：那来自两个积分覆盖的窗口不同（上游用「成对累加」从根上消除，代价是要加一列、要迁移，本轮不做，差异写在这里）。**③ 修正文档**：`docs/ADAPTER_POWER_SOURCE_RESEARCH.md` 原先把上游写成「MiniWatts（macOS 原版）| IOKit/SMC」——上游其实是 **iPhone/iPad 应用**（Swift、iOS 17+、只用私有 API、自签安装），取数是四路，真正的端口输入来自 `IOHIDEventSystemClient` 读的 PMU 传感器，`SMC` 是 Mac 上的东西、它没用。改正后那句「Android 没有对等 API」也有了准确理由：**iOS 把 USB-C 电源轨开放给沙盒应用，Android 只开放给特权身份**——这正是本项目 Shizuku 通道存在的原因。

1.16 **把那枚不好懂的徽标改成人话，并让它只出现在需要它的页面。**1.14 起冲突徽标写的是「状态与电芯方向相反」——「状态」指谁的状态、「方向」指谁的方向，都要先解释一遍才看得懂。现在它直接陈述发生的事：`status=2 且电芯为负` 时是 **「插着电，电芯在放电」**（反过来 `status=3 且电芯为正` 时是「电芯在充电，状态仍显示放电」，一般出现在刚插上、状态还没更新时）。同时**功率页不再显示这枚徽标**：那一页已经把「充电中」和「电芯放电/电芯充电」作为两枚胶囊并排显示，第三行再说一遍只是重复；徽标保留在**适配器页与设备页**——那两页没有方向胶囊，需要它。会话级与汇总级的两句仍是「状态与电芯方向不一致」，因为它们覆盖两个方向、而会话只存了一个布尔值，写成单方向的句子会在另一种情形下变成错的。帮助页开头的说明改为从「插着电，电芯在放电」讲起，与徽标措辞对上。

底部胶囊图标导航包含功率、发热、适配器、设备、历史记录；功率页右上角齿轮进入设置，诊断和离线帮助从设置进入。历史页工具栏有三个动作：删除历史、导出中心、对比会话；进入对比选择后删除与导出让位给「取消对比」，系统返回键先退出选择而不是退出应用。历史卡片点击可查看详情，详情保留导出历史及删除本会话入口。窄屏/大字体时指标改为纵向排列，导航占独立空间，不覆盖滚动内容；API 35 处理系统栏安全区。

安装 `app/build/outputs/apk/debug/app-debug.apk`，或连接自己的设备后执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk`。本项目构建不包含原生应用库，可在 ARM64 / ARM / x86 Android 上安装；实际读数取决于设备驱动。

手机已有不同密钥签名的版本时，使用下文 `-PisolatedDebug=true` 构建独立的 `MiniWatts Test`（`dev.miniwatts.macdebug`），保留原应用及数据。不要直接卸载原应用解决签名冲突。

- 功率：电量、电芯净功率（表盘显示绝对值，方向另有说明）、带符号电芯电流、电压、温度、充放电状态与电芯方向、供电路径、本次充电摘要和最近 3 分钟趋势（最多 180 样本）。趋势图在特权通道读到输入轨时叠加适配器输入曲线，两条线共用同一 W 量程。
- 发热：API 29+ 系统热状态、分组温度探头（内核热区，免权限）、热缓解档位、CPU 调频策略、带真实电池温度标记的示意图，并可进入「性能与热区」子页看全部热区与频率比例历史。系统热状态不是温度探头；热区只给温度，不给功耗。
- 适配器：真实功率与协商上限、握手信息、广播供电规格、实时供电轨和线缆信息区域。输入读数被判定只读到一路时，该值不参与任何派生并写明原因，原始值仍在导出里。协商上限来自系统广播（充电器上限，不是实时输入）；真实类型、供电角色、PD 状态机与认证、输入侧实时电压电流需要可选 Shizuku 增强，未启用或被策略拒绝时显示不可用。PDO 逐档列表与线缆阻抗没有可用来源，始终不可用。
- 设备：本机型号、电池状态与电量、循环次数、电荷计数器、容量健康度（需特权读取满充与设计容量）、容量估算（无特权时的替代，标注为估算）、Android/补丁级别/内核/电池技术和系统版本；配件电量暂不可用。
- 历史：插拔会话、净电芯 Wh、有效覆盖时长、峰值和平均功率、功率/电量/温度曲线。默认最多 60 会话，每会话最多保留最新 1500 原始样本，累计积分及峰值仍覆盖全部已测区间。可删除单条/全部，导出 CSV/JSON 到系统文件选择器。
- 会话对比：在历史列表点选两个会话即并列显示逐项数值差值与两条功率曲线。两段覆盖窗口相差过大时不给比例，缺失项一律「不可用」；「额定功率 / 通路电阻」标注为无来源。
- 导出中心：历史 JSON、历史 CSV、诊断 JSON、probe 原文四个入口集中一页，各自说明内容与单位口径；文件仍由系统文件选择器决定位置，取消不产生副作用。
- 设置：1/2/5/10/30 秒采样、摄氏/华氏、中文/英文/跟随系统、深浅主题/跟随系统、10/30/60 会话、充电时常亮、可选 Shizuku 增强。记录、悬浮窗、常亮、Shizuku 默认关闭。
- 主动开始持续记录后前台服务运行，通知有读数、记录时长和停止按钮。启用悬浮仪表需单独授予悬浮窗权限，再返回点击开启；拖动读数区移动，窗内可关闭。悬浮窗依附记录服务，关闭窗不停止记录，停止记录会移除窗。通知与悬浮窗摘要在有输入侧实测时多一行"适配器输入 … · 实测"。
- Widget 在桌面手动添加，显示“上次采样 · 非实时”和采样时间；同样在有输入侧实测时多一行适配器输入，并把字号降到 14sp 以适配默认高度。系统约每 30 分钟调度一次（可能延迟），App 采样时最多每分钟刷新。不会每秒唤醒 Widget。

## 数据边界

`ACTION_BATTERY_CHANGED` 提供 level/scale、状态、类型、温度（0.1°C）、电压（mV）、健康状态。`BatteryManager` 的瞬时/平均电流为 µA、charge counter 为 µAh、energy counter 为 nWh。循环次数只在 API 34+ 广播实际含有效值时显示。`MIN_VALUE/MAX_VALUE` 哨兵和缺失属性为 null，不伪装成 0；真实电流 0 可有效。health 是系统健康状态，不是容量健康百分比。

`W = µA × mV / 10⁹` 是**电芯侧净功率（推算）**，不是充电器输出功率：`currentUa` 来自 `BatteryManager.CURRENT_NOW`，`voltageMv` 来自 `ACTION_BATTERY_CHANGED` 的 `voltage`。公开 API 正号表示流入电芯、负号表示流出。**存下来和算出来的值一律保留符号**（样本、趋势曲线、净能量、电芯获得比例、全部导出），只有**表盘与卡片上那个头条数字显示绝对值**，方向由「电芯充电 / 电芯放电」这枚说明给出。原因是平台上报的充放电状态描述的是策略（已插电且允许充电），插电但系统耗电超过输入时电芯确实会净放电，两者并不矛盾；这种相反的时段会记在会话上并给出一枚徽标。电流/温度等 API 读数标为实测，W、积分 Wh 标为推算；每个样本附墙钟时间、单调时间、启动编号和来源。诊断页/JSON 保留原始单位；CSV 通过列名给单位，缺失为空字段。

**适配器数据有两条互相独立的来源，绝不混为一谈。**

其一，`ACTION_BATTERY_CHANGED` 中的 `max_charging_current`（µA）与 `max_charging_voltage`（µV）。这两个 key 在 AOSP 里是隐藏常量，但值是普通广播数据，用字符串 key 读取即可，不需要反射或权限。平台取「在线充电器类 power_supply 中 `current_max × voltage_max` 最大的一路」，所以它是**充电器上报的协商上限**，不是此刻实际输入；UI 一律标注「上限」。同时读取 `invalid_charger`（合规标记）与小米粘性广播 `miui.intent.action.ACTION_QUICK_CHARGE_TYPE` 的 `quick_charge_type`、`POWER_MAX`、`CAR_CHARGE`（厂商定义，枚举含义未经本机验证，`POWER_MAX` 可能为 0；仅在插电时读取，避免拔线后残留旧档位）。

其二，可选的 Shizuku 增强。Shizuku 不是 root：它让一个用户服务跑在 Shizuku 服务端进程里（身份取决于用户如何启动 Shizuku），本 App 自身**不调用 su、不直接读任何厂商节点、不申请 root**。该服务只执行一份固定的只读白名单（`/sys/class/power_supply/usb/*`、`battery/charge_full*`、`/sys/class/typec/port0/{power_role,data_role}`、`/sys/class/xm_power/**` 若干），把读不到的原因区分成 `!EACCES`（策略拒绝）与 `!ENOENT`（本机无此节点），两者都按不可用处理，绝不当作 0。小米 15 `dada` 的厂商策略把这四类节点标为私有类型，`shell` 没有任何 allow，因此 **ADB 身份的 Shizuku 在这些节点上必然被拒**；只有 root 身份的 Shizuku 才能读到值。是否真正读到以设置页显示的 `uid`/SELinux 上下文与拒绝节点数为准。

**温度探头走的是完全不同的通道，不需要任何特权。** 内核 `/sys/class/thermal` 在 `dada` 上由普通应用身份可列举且几乎全部可读（实测 73 个温度热区中 70 个可读、1 个被策略拒绝、其余为传感器缺失；另有 64 个 cooling device 与 2 个调频策略可读）。这份数据有三个必须处理的陷阱，代码里都做了：**① 同一目录下混有非温度节点**——`vbat` 报毫伏（4306）、`pmih010x-ibat-lvl*` 报电流、`socd` 报百分比，数值恰好落在合理温度区间内，所以只能用**名称**剔除，不能靠取值范围；**② `-273000` 是"传感器未装配"哨兵**，保留原始值供诊断但读数记为不可用；**③ 缺省标签路径**——被拒绝的节点在界面上报"被系统拒绝"而不是消失。热区只提供温度，不提供功耗，因此"GPU 功耗/整机功耗"仍然没有来源。环境温度探头本机不存在，GPU 占用率节点被 SELinux 拒绝，两者在发热页明确标注为不可用。

冲突会话的 Wh 按原始符号积分，充/放电方向未经机型确认。SQLite v1→v2 升级从仍保留的样本补记冲突，不修改历史数值；升级前已经裁剪的样本无法追溯。新版本中，即使冲突样本后来被裁剪，会话标记也会保留。JSON 新增 `signConflictObserved`/样本 `signConflict`；CSV 在原列末尾追加 `session_sign_conflict_observed,current_sign_conflict`。

适配器数据不新增数据库列：样本本身以 JSON 存在 `samples.data`，因此新增字段对旧库向后兼容（旧样本解析为不可用）。JSON 样本新增 `adapter` 对象与 `loadW`/`cellSharePct`，诊断 JSON 另加 `privilegedChannel`（启用状态、uid、SELinux 上下文、被拒绝与缺失节点清单）；导出 JSON 的 `schema` 升为 2。CSV 在末尾追加 `adapter_max_current_uA,adapter_max_voltage_uV,adapter_ceiling_W,adapter_input_uV,adapter_input_uA,adapter_input_W,adapter_invalid,quick_charge_type,adapter_advertised_W,adapter_real_type,typec_power_role,capacity_health_pct,load_W,cell_share_pct,adapter_source`，缺失仍为空字段。
输入侧能量与功率**分开积分**：`Integration.betweenInput` 用同一套连续性规则（同 boot、同 continuity、dt>0、dt≤3×interval 且 ≤60s）对 `adapter.inputW` 单独做梯形积分，得到会话的 `inputWh`/`inputCoveredMs`，与电芯侧的 `energyWh`/`coveredMs` 互不影响。因此会话可以「电芯窗口完整、输入完全没测到」，此时 UI 报不可用；两者的覆盖时长也往往不同，比例只有在输入覆盖 ≥ 电芯覆盖一半时才计算；**若该会话电芯净放电（净能量 ≤ 0），比例也不计算**——「进到电芯的比例」在电芯没获得能量时不成立，而两个数值本身照常给出。**0 不等于没测到**：`inputCoveredMs == 0` 才是「从未测到」，`inputWh` 为 0 但覆盖 >0 表示确实测到了接近 0 的输入。
迁移不重算历史：v2→v3 只加列，旧会话的输入列保持 0（即「未测到」），不会把过去的充电会话伪造成有输入数据。JSON 会话新增 `inputWh`（未测到为 `null`）、`inputCoveredMs`、`cellShareOfInputPct`（不给比例时为 `null`）；CSV 会话列追加 `session_input_wh,session_input_covered_ms,session_cell_share_of_input_pct`；1.15 起样本列末尾再追加 `adapter_input_trusted,adapter_input_partial_W`（历史 JSON `schema` 升为 4，新增 `adapterInputTrusted` / `adapterInputPartialW`）。

积分只连接同次启动、单调 `0 < dt ≤ 3×相邻采样间隔最大值` 且 `dt ≤ 60s`、两端功率有效且连续采样标记一致的样本，使用梯形积分和单调 dt。暂停/重新开始采样分配新标记，即使很短的已知暂停也不积分。曲线也遵守同样缺口规则。正负变化按带符号净能量积分，平均功率=净 Wh/有效覆盖小时数。覆盖为 0 时平均功率不可用，净 Wh=0 表示没有积分区间。没有输入侧实测读数时，不计算负载、损耗、电芯占比或任何效率比例；出现过符号冲突时同样不计算需要方向的负载与损耗、电芯占比、电芯获得比例。**有**输入侧实测读数（即特权通道确实读到输入轨）**且该读数没有被判为偏小**时，`负载与损耗 = 实测输入 − 电芯净功率`（仅当输入大于电芯时给值）、`电芯占比 = 电芯净功率 ÷ 实测输入`（仅当电芯为正时给值，上限 100%），两者都标为推算：负载与损耗同时包含系统功耗与转换损耗，不等于热量，也不等于线缆损耗。**输入读数是否可信**：能量只能经充电器单向流入，因此 `电芯 > 输入 × 1.2 且 差值 > 1.5 W` 判为「端口只读到一路输入」，该读数不参与任何派生（原始值仍导出，见 `adapterInputTrusted`）。输入轨取绝对值，方向由供电角色与插电状态描述。

会话级统计（净 Wh、平均功率、峰值）仍然只用电芯侧功率与覆盖时长，不因输入侧数据出现而改变口径；适配器字段按样本随 JSON/CSV 一起导出。

进行中会话写入 SQLite；切页、旋转、锁屏不拆会话。同次系统启动的长采样缺口保留会话但不积分；系统重启/单调时间重置则关闭旧会话于最后观测时间。离线期间拔出再插入无法被普通 App 事后知道，可能归入同一会话；不声称它是完整连续的插拔日志。采样停止时发生的拔出只能在下次采样观察到。历史列表可重新进入刷新。

本版只读取**经本机确认过语义与单位**的节点：公开广播与一个固定只读白名单（仅通过可选 Shizuku 增强、且由 Shizuku 进程执行），不读取任意 sysfs 节点、不调用 su、不申请 root、不写任何节点。PDO 逐档列表、线缆阻抗、通路损耗没有可用来源，显示不可用；后续添加节点必须先确认机型、权限、语义、单位，并保留原路径和来源。

## 架构

- `core/`：无 Android 依赖的 Sample/Session/Adapter 模型、单位/哨兵转换、单调时间积分、插拔及恢复决策、特权 probe 文本解析（`PrivilegedParser`）、**会话对比口径**（`SessionCompare`）与**导出目录契约**（`ExportCatalog`），JUnit 测试可单独运行。
- `SensorReader`：只读平台采集，API 版本判断与 null 降级；同时读取电池广播的适配器上限与小米快充广播。
- `Store`：SQLite 事务、会话恢复、样本保留、积分统计、删除和导出。
- `Engine`：单个后台执行器串行采集/数据库/导出；主线程分发读数。界面/服务共享采样器，避免切页、旋转时重复记录。仅在「Shizuku 增强已开启且正在采样」时绑定可选特权通道。
- `MainActivity`：导航、生命周期、设置和权限/导出流程；`UiKit`：主题颜色、圆角表面、卡片、详情行、指标列、Canvas图标和网格背景；`MeterViews`：功率仪表、电量条、单电池示意。
- `LivePanels`：功率/温度/充电器页面及真实样本绑定，不按每秒采样重建页面；`HistoryPanels`：会话汇总、卡片、对比选择与详情；`ComparePanels`：两会话并列页，列宽由表头与数据行共用、数值单元格自动缩字而不是换行；`ChartView`：带轴/时间的断线曲线；`Words`：中文/英文。
- `ExportCenter`：诊断与 probe 原文两份文档的唯一组装点，界面与真机用例渲染同一条路径。
- `PrivilegedReader` / `PrivilegedUserService` + `IPrivileged.aidl`：可选 Shizuku 通道的客户端与用户服务端；服务端只读固定白名单，客户端负责绑定、探测与降级。
- `ThermalReader`：只读内核 `/sys/class/thermal` 与 `cpufreq`。快照约 300 次小文件读取，因此按 2 秒节流且**不写入样本**（一个机型可有 80 个热区，逐样本入库会撑爆数据行）；热区分组、单位过滤与哨兵判定在 `core/Thermal.kt` 并由单测覆盖。
- 历史数据与趋势预览在`Engine.io`执行；列表每会话只查询最新120样本，详情读取保留的1500样本。`RecordingService`管理前台通知和悬浮窗；`MeterWidget`管理系统低频刷新。

没有使用数据库/界面框架依赖；Gradle 8.10.2、AGP 8.7.3、Kotlin 2.0.21、JUnit 4.13.2 固定版本。Wrapper 分发包有 SHA-256 校验。唯一的第三方运行时依赖是可选 Shizuku 通道所需的 `dev.rikka.shizuku:api` / `:provider` 13.1.5（其传递的 `androidx.annotation` 已排除，工程保持 `android.useAndroidX=false`）；不启用该通道时不会绑定任何 Shizuku 服务。

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

Mac 可使用项目内官方 SDK 初始化脚本；SDK、Gradle 缓存和本地签名留在 gitignored 的 `.tooling/`，不修改全局配置。设置真实 `JAVA_HOME`；脚本也可复用 PATH 中的 JDK（支持 mise 安装的 JDK）。SDK 许可证由操作者确认，`adb` 需已在 PATH 中：

```bash
scripts/bootstrap-mac.sh
# 核心测试、独立测试 APK、lint、原生真机回归 APK
scripts/build-local.sh -PisolatedDebug=true :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.miniwatts.macdebug.test/dev.miniwatts.RegressionRunner
adb shell am start -n dev.miniwatts.macdebug/dev.miniwatts.MainActivity
```

多设备时每条 adb 命令指定 `-s <设备>`。小米可能需要启用“USB 调试（安全设置）”和“USB 安装”，并在限时提示中确认继续安装。回归测试仅允许独立测试包，数据库用例会清理该测试包的数据；须检查输出 `Tests run: 5, failures: 0`，不能只依据 adb 退出码。

### 正式版（release）与签名

`assembleDebug` 出来的包是 debug 签名、`android:debuggable="true"`，只适合自测；日常使用应装 release 包：

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

**签名密钥是这个 App 永久的升级身份**，因此它放在 `keystore/miniwatts-release.jks`（不在 `.tooling/` 这个可随时清理的临时目录里），口令放在 gitignored 的 `local.properties`：

```properties
release.storeFile=keystore/miniwatts-release.jks
release.storePassword=<口令>
release.keyAlias=miniwatts
release.keyPassword=<口令>
```

两处都被 `.gitignore` 覆盖，**不会进版本库，所以必须自己备份**：密钥丢失后无法再覆盖安装，只能卸载重装并丢失全部历史数据。换成新密钥同理。`app/build.gradle.kts` 的 release 签名配置读不到 `local.properties` 时会退回 debug 身份，保证干净检出也能构建，但那种产物不应分发。

回归测试仍然只跑在独立 debug 包上（`-PisolatedDebug=true`，用例会断言包名以 `.macdebug` 结尾），**不会**在 release 包上执行；release 包的验证方式是安装后人工过一遍验收清单。

早期 Linux ARM64 宿主无预装 Java；Google SDK 的 Linux `aapt2` 是 x86-64，直接运行得到 `cannot execute binary file: Exec format error`。该环境采用 [lzhiyong/android-sdk-tools 35.0.2](https://github.com/lzhiyong/android-sdk-tools/releases/tag/35.0.2) 的 ARM64 静态 aapt2，使用 AGP 的 `android.aapt2FromMavenOverride`，不修改官方 SDK 或系统。替代工具与下载包均在 `.tooling/`；它是第三方构建工具，只用于本地，Mac 使用 Google 官方工具。

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
8. 诊断与帮助确认：协商上限只在广播给出有效值时显示；PDO 逐档列表、线缆阻抗、通路损耗始终不可用；未开启 Shizuku 增强时输入侧数值与容量健康度不可用，没有伪造读数。开启 Shizuku 增强后用 ADB 身份验证节点被拒时读数仍为不可用、设置页显示拒绝节点数；用 root 身份验证输入侧电压/电流与容量健康度出现。充电时常亮默认关，开启仅影响 App 前台。
9. 发热页确认：分组温度探头与设备自身温度对得上；`vbat`/`socd`/`*-ibat-lvl*` **没有**出现在温度列表里；`-273000` 的热区显示为传感器缺失而不是数值；人为让某热区不可读时该组降级为不可用而不是消失；热缓解计数的变化与 CPU 频率变化方向一致。诊断导出里的热区表包含原始毫度值与被拒/非温度节点清单。
10. 会话对比与导出中心确认：历史页工具栏进入对比选择，选中两个会话后进入并列页，A/B 两列与「差值」表头和数据列对齐、每行只占一行；两段覆盖窗口相差超过一倍时页面明说不给比例；未开启 Shizuku 增强或无输入实测时「适配器输入能量 / 电芯获得比例」显示「不可用」而不是 0；插电但系统耗电超过输入的时段，功率页会同时显示「充电中」与「电芯放电」两枚胶囊（这一页不再另有徽标），适配器页与设备页则出现「插着电，电芯在放电」；此时电芯获得比例仍给出，为负表示该时段电芯净放电；「额定功率 / 通路电阻」标注无来源。导出中心四个入口各导出一次，缺失字段为空字段/`null`；在系统文件选择器取消不写文件、无副作用。

2026-10-12 已在 Xiaomi 24129PN74C（Android 16 / API 36）初测手机上已有的 1.1 APK，检查四页、历史详情/返回及采样暂停恢复。2026-10-13 已在 Mac 构建并独立安装 1.2：核心 6 项测试、原生真机 5 项回归、assembleDebug/assembleDebugAndroidTest 均通过；lint 0 错误、14 警告。修复和证据见 [DEVICE_VALIDATION_REPORT.md](DEVICE_VALIDATION_REPORT.md)。截图、数据库和日志保存在 gitignored 的 `.tooling/`。大字体、横屏、持续记录、悬浮窗、Widget、SAF 文件选择器导出等仍未验证；此结果不代表整份验收清单通过。2026-10-14 在 1.4 上复核：核心 16 项测试、原生真机 6 项回归、assembleDebug/assembleDebugAndroidTest/lintDebug 通过（lint 0 错误、24 警告，均为既有的 DrawAllocation/SetTextI18n/ViewConstructor/Overdraw 类别），并读回真机 `latest` 样本确认适配器字段（协商上限 7.5 W、小米快充类型 1、合规 0）与来源标注正确，未开启 Shizuku 时输入侧与容量健康度保持不可用（`null`，不是 0）。2026-10-14 在 1.5 上复核：核心 22 项测试、原生真机 7 项回归（新增 `thermalZonesAreReadableAndConsistent`，在设备上跑真实读取器并校验契约）通过，lint 0 错误、24 警告；设备实测热区 `zones=73 measured=70 skipped=10 denied=[wireless] cooling=64 active=5 cpu=[policy0:2745600/3532800, policy6:3801600/4320000]`，与独立 shell 取证一致。同一轮实测还发现并修复了一个健壮性缺陷：`/proc/self/attr/current` 返回**以 NUL 结尾**的 SELinux 上下文，`trim()` 去不掉该字节，它会随特权 probe 原文进入诊断界面与导出，导致 `uiautomator` 崩溃、导出 JSON 含裸控制字符；现在服务端、解析器与热区读取器三处统一剔除控制字符（保留换行），并有单测覆盖。2026-10-14 在 1.6 上追加复核：核心 22 项、真机回归 9 项（新增 `powerChartOverlaysAdapterInput` 用像素断言叠加曲线只在有实测输入时出现、`summaryAddsAdapterOnlyWhenMeasured` 断言摘要不多不少只加一行）全部通过，lint 0 错误、24 警告。1.6 的界面效果未做截图复核（当时设备已由用户使用，遂改用像素与文案断言代替）。2026-10-14 在 1.7 上复核：核心 **26 项**（新增 4 项容量估算单测：跨度阈值与符号、取最宽可用跨度、设计容量已知/未知两种合理性判定、中位数抗单次异常）、真机回归 **11 项**（新增 `thermalDetailRendersZonesAndFrequency` 渲染详情页并断言入口行真的触发回调、`capacityEstimateReadsStoredChargeSpan` 走真实数据库读取路径并断言 5400 mAh 与 5% 跨度不出估算）全部通过；lint 0 错误、25 警告（新增 1 条 `ViewConstructor`，与既有 4 个自定义 View 同类，属既存风格）。1.7 的界面效果同样未做截图复核，理由同上。2026-10-14 在 1.7/1.8 上补做了视觉复核（用户交还设备后）：功率页趋势图两条曲线与共用 W 量程、发热页入口行、「性能与热区」子页（含 70 个热区降序表与会话频率比例图）、设备页容量估算卡与内核单行显示均已截图确认；复核中发现冷却设备一次性列出 64 行会把热区表挤到很后面，已改为只列**生效项 + 全部 `cpufreq-*`**，其余折叠为一行计数并指向诊断导出，热区表因此回到首屏。2026-10-14 在 1.8 上复核：核心 **28 项**（新增输入侧梯形积分独立性与会话比例覆盖闸门两项单测）、真机回归 **12 项**（新增 `inputEnergyMigratesAndAccumulates`：构造 v2 库→迁移→断言旧会话 `inputWh` 导出为 `null` 且不给比例、新会话两个积分各自累积、10 W 输入/8 W 入电芯得到 80% 比例）全部通过；lint 0 错误、25 警告。真机运行也确认迁移已在实际库上发生（当前会话显示「输入实测覆盖 30s」而旧会话保持不可用）。2026-10-14 在 1.9 上复核：真机回归 **13 项**（新增 `probeColumnsLineUp`：渲染含中文分组名的探头卡，断言五行数值列在各自父容器坐标系里的 x 完全相同、且温度起始字符下标一致）、核心 28 项、lint 0 错误 25 警告全部通过；并截图确认「温度探头」「热缓解」「CPU 频率」三张卡的列均已对齐。2026-10-14 首次产出**正式版**：新增发布签名配置，`keystore/miniwatts-release.jks`（RSA 4096、有效期 10000 天）+ gitignored 的 `local.properties` 口令；`assembleRelease` 产出 `dev.miniwatts` 1.9 (versionCode 10)、SHA-256 指纹 `9b63e997…`、无 `application-debuggable`、并保留 `rikka.shizuku.ShizukuProvider`（authority `dev.miniwatts.shizuku`）。真机安装后逐一确认：进程无崩溃、功率页在授予 Shizuku 权限后出现「适配器输入 0.9 W · 实测」、特权服务进程为 `root dev.miniwatts:privileged`、发热页三张卡对齐且采样正常（123 个样本）。2026-10-15 在 1.10 上复核：核心 **32 项**（新增 3 项：对比的缺失值不进数字与不可用判定、窗口相差一倍才给比例的闸门、导出目录完整性）、真机回归 **16 项**（新增 3 项：`exportCentreListsEveryDocument` 断言目录覆盖全部格式并直接渲染诊断 JSON 与 probe 原文、`comparisonRendersBothSessionsWithoutInventingValues` 在真实控件上断言 A/B 数值与「不可用」列且未把未实测输入渲染成 0、`historyOffersTheComparisonSelection` 走完整真实点击路径：历史工具栏→选择→点选两个会话→进入并列页）全部通过；lint 0 错误、28 警告（全部落在既有的 ChartView/MeterViews/LivePanels/RecordingService/UiKit/HistoryPanels 六个文件，两个新页面与两个新 core 文件零警告）；`assembleDebug`/`assembleDebugAndroidTest` 通过。触屏截图复核了导出中心四行与对比页（含表头对齐、单行数值、覆盖窗口闸门的橙色说明）；Shizuku 以 root 身份运行时功率页读回「适配器输入 7.1 W · 实测」，因此对比页的实测一侧来自真实输入轨而不是构造值。同轮产出**正式版 1.10**：`assembleRelease` 产出 `dev.miniwatts` 1.10 (versionCode 11)，`app-release.apk` 805,009 字节、SHA-256 前缀 `8a4351e8…`，签名证书 SHA-256 仍为 `9b63e997…`（与 1.9 同一把密钥，覆盖安装身份不变），无 `application-debuggable`。2026-10-15 在 1.11 上复核：删除「旁路充电」卡及其整条数据链路（含 AIDL `bypassStatus()` 与用户服务的脚本执行分支），核心 32 项、真机回归 16 项全部通过；`fiveTabsOpenTheirOwnScreens` 新增断言确认适配器页仍有「握手信息」与「线缆与连接」且**不再含任何「旁路」文本**；触屏截图确认该页布局完好（实测输入 7.05 W / 协商上限 7.50 W、利用率 94%）。lint 0 错误、**26 警告**（删掉该卡同时消掉 2 条 SetTextI18n，警告现全部落在 MeterViews/ChartView/RecordingService/HistoryPanels/UiKit/LivePanels 六个既有文件）；`assembleRelease` 产出 `dev.miniwatts` 1.11 (versionCode 12)、`app-release.apk` 797,761 字节、SHA-256 前缀 `3f83927b…`，签名证书仍为 `9b63e997…`、无 `application-debuggable`。2026-10-15 在 1.12 上复核：核心 32 项（`sessionShareNeedsComparableCoverage` 增加冲突闸门断言：冲突会话不给比例、但 `inputWh` 与 `hasInputCoverage` 照常）、真机回归 16 项（`conflictWarningsFollowReadingsAndHistory` 增加断言：带实测输入的冲突会话在功率页的解释必须是**冲突**而**不是**覆盖不足，且适配器输入能量仍显示；历史详情不再出现 `— Wh` 占位符并显示实测值，详情新增的「电芯获得比例」行为「不可用」）全部通过；lint 0 错误、**24 警告**（DrawAllocation 10 / SetTextI18n 7 / ViewConstructor 5 / Overdraw 1 / DataExtractionRules 1，仍全部落在既有文件，两个新页面与两个新 core 文件零警告）；触屏截图确认功率页「本次充电」、供电路径的负载与损耗/电芯占比在冲突期间为占位符、以及历史详情的新行与重写说明、会话详情页只保留删除与导出两个动作。`assembleRelease` 产出 `dev.miniwatts` 1.12 (versionCode 13)、`app-release.apk` 798,417 字节、SHA-256 前缀 `f8c3ec00…`，签名证书仍为 `9b63e997…`、无 `application-debuggable`。2026-10-15 在 1.13 上复核：核心 32 项、真机回归 16 项全部通过，其中 `conflictWarningsFollowReadingsAndHistory` 钉住「实时页面是徽标、解释在帮助页」的长度契约，`fiveTabsOpenTheirOwnScreens` 额外走一遍 功率→设置→离线帮助 并断言帮助页**确实渲染**了完整说明（不只是字符串存在）；截图确认功率页只留「符号冲突 · 功率方向未确认」一枚胶囊（与「充电中」「正常」并列）。lint 仍为 0 错误、24 警告（新文件零警告）；`assembleRelease` 产出 `dev.miniwatts` 1.13 (versionCode 14)，签名证书不变。2026-10-15 在 1.14 上复核：核心 32 项（比例闸门撤回后，被标记会话的比例照常给出、且可为负）、真机回归 16 项（新增：负读数在表盘上不出现符号且量值正确、方向说明三态映射正确、被标记会话的历史详情仍显示比例）全部通过；lint 仍为 0 错误、24 警告（新文件零警告）；`assembleRelease` 产出 `dev.miniwatts` 1.14 (versionCode 15)、`app-release.apk` 799,261 字节、SHA-256 前缀 `ba9a47ea…`，签名证书仍为 `9b63e997…`、无 `application-debuggable`。2026-10-15 在 1.15 上复核：核心 **32 项**（`loadAndCellShareFollowTheTrustedInput` 覆盖新判据：实测输入 6.61 W 下，电芯 4 W 时负载 2.61 W、占比 60.5%；电芯净放电时不给占比但负载仍给出（且含电芯贡献）；电芯 12 W 时判为输入偏小、两个派生量都不给而原始值保留；电芯 7.2 W 属读取时序、占比截到 100% 且无剩余量可报。`sessionShareNeedsComparableCoverage` 断言会话净放电时不给比例、而两个数值本身照常）全部通过；`assembleDebug` / `assembleDebugAndroidTest` / `assembleRelease` 通过；lint 0 错误、24 警告（新文件零警告）；`assembleRelease` 产出 `dev.miniwatts` 1.15 (versionCode 16)、`app-release.apk` 800,685 字节、SHA-256 前缀 `254812bf…`，签名证书仍为 `9b63e997…`、无 `application-debuggable`。真机回归 **16 项通过**（设备掉线后重连补跑；`conflictWarningsFollowReadingsAndHistory` 已按新语义改写：净放电会话不给比例并给出解释、净充电会话给 80%、历史详情对净放电会话显示「不可用」）。真机实测同时是这两道新闸门的现场证据：当时端口输入 7.06 W、电芯 **−2.11 W（净放电）**，供电卡给出「负载与损耗 9.16 W」而「电芯占比 —%」，并显示「电芯此刻在净放电，没有『输入中进到电芯的比例』；负载与损耗仍按 实测输入 − 电芯净功率 给出」；实测节点读数 `battery/current_now=−1049000`、`usb/current_now=1487000`、`usb/voltage_now=4743000`（即 −4.45 W 与 7.05 W）与上述口径一致。截图确认供电卡与本次充电卡。仍未在现场触发的一条：输入偏小（①）需要端口读数真的低于电芯，本机未出现该情形，因此该分支只有核心单测覆盖（6 个边界：正常、净放电、偏小 1.8×、时序性微超、无输入、未插电），未做真机截图。2026-10-15 在 1.16 上复核：核心 32 项、真机回归 **16 项**通过，lint 0 错误、24 警告（新文件零警告）；`assembleRelease` 产出 `dev.miniwatts` 1.16 (versionCode 17)、`app-release.apk` 800,921 字节、SHA-256 前缀 `592d8541…`，签名证书仍为 `9b63e997…`、无 `application-debuggable`。徽标改动做了真机截图复核，且**等到了真实冲突时刻**（测试进程内等待 `engine.latest.signConflict` 为真再截图，日志 `conflicted=true latest=-2.99`）：适配器页「实时供电轨」卡在 `电池侧电流 −0.70 A`、`电芯净功率 2.99 W` 下方显示 **「插着电，电芯在放电」**；同一冲突时刻的功率页只显示「充电中」+「电芯放电」两枚胶囊，**没有**这枚徽标。**首次运行出现 1 次偶发失败**并已定位：`historyOffersTheComparisonSelection` 等了默认 8 s 没等到可选卡片——进入选择态会在 io executor 上重读数据库后重绘（`showHistory()` 会把指纹置空并重新加载，逻辑本身正确），而这个用例排在多个重数据库用例之后，8 s 在真机上不够。已把该处等待放宽到 20 s，并让失败信息打印当时的屏幕文本；随后**连续 5 次运行全部 16/16 通过**。
