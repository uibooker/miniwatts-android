# Android OEM 旁路充电功能对照

调研日期：2026-10-13。范围：Samsung、ASUS ROG、Sony Xperia、Google Pixel 的官方产品/帮助文档。研究仅在电脑下载网页；没有 ADB、手机文件读取、sysfs/GLINK 操作、驱动调用或插件修改。

## 结论

官方资料能够确认 Samsung、ROG、Sony 在指定条件下提供“暂停电池充电”的功能。Samsung 还明确写出优先给设备供电。Pixel 当前官方帮助明确说明 80% 充电上限及定期充满校准；所审阅页面没有说明该功能的电池断开方式或供电拓扑。

这些资料证明各自产品的用户功能及条件，不能证明 Xiaomi dada 具有相同硬件通路，也不能据此把同名 sysfs 属性、某个 FCC 数值或 OEM 私有命令移植到 dada。取得 root 权限并不填补这条证据链。

## 功能与条件比较

| OEM / 已直接核对的产品 | 官方已确认的行为 | PD / PPS / 功率 | SOC / 游戏 / 其他条件 | 证据边界 |
| --- | --- | --- | --- | --- |
| Samsung Galaxy，具体支持机型依设备菜单核对 | `pauses battery charging, prioritising power supply to the device while gaming` | PD 充电器；必须支持 PPS；输出功率至少 25W | SOC 至少 20%；游戏运行；熄屏或切换到其他画面时不工作 | 是官方“停止电池充电、优先设备供电”表述；不是电路图或电池物理隔离证明。官网未给统一固定机型清单 |
| ASUS ROG Phone 7 | `Bypass Charging that pauses charging while you're gaming`，与控制充电速度的 Steady Charging 分开描述 | 同段描述配备 65W HyperCharge USB PD 充电器；没有声明这是旁路最低功率条件，也没有声明 PPS 门槛 | 游戏期间；本页没有 SOC 门槛或详细端口/握手条件 | 直接证据限 ROG Phone 7。FAQ 抓取失败；不能据此补写其他 ROG 型号条件或“强制 65W” |
| Sony Xperia 1 IV；1 V 另核对功能存在 | 1 IV：Game enhancer 运行且 H.S. power control 开启时，`the battery will not charge` | 所审阅 Game enhancer 说明没有列 PD、PPS 或瓦数门槛 | Game enhancer 运行且开关开启；需要充电时关闭；页面没有 SOC 门槛 | 证明电池不充电的用户行为；本轮可读原文没有“电池物理断开”或实际 SYS 接线描述。1 V 页面仅确认 H.S. 设置存在及可按游戏配置 |
| Google Pixel 6a 及之后（以当前官网为准） | `Limit to 80%`，降低长期满电停留；Adaptive Charging 是按习惯安排充到 100% 的另一选项 | 所审阅 80% 页面没有旁路 PD/PPS/功率条件 | 无游戏条件；每第 10 个循环仍需充满 100%，且显示 100% 后继续插电至少 30 分钟以完成容量读数校准 | 官方页面未把 80% 功能描述为旁路；不能由 80% 稳定或低电流推断电池隔离。也不能由页面未提及推断 Pixel 硬件不具备旁路能力 |

## Samsung：条件会决定功能是否真正执行

官方 [Pause USB PD charging when gaming](https://www.samsung.com/levant/support/apps-services/pause-usb-pd-power-delivery-charging-when-gaming-feature-on-your-galaxy-device/)（页面标注更新日期 2024-03-23）说明：通常输入电源同时服务设备运行与电池充电；此功能暂停电池充电并优先供给设备。

其条件不是只打开一个菜单开关：PD、PPS、至少 25W、SOC 至少 20%、游戏处于运行状态需要同时成立。页面明确指出熄屏或切到其他画面时不工作；SOC 低于 20% 或连接非 PD 充电器会继续按常规充电。支持情况随机型变化，应以 Gaming Hub → Game Booster 菜单为准。One UI 6.0 及之后可无需先连充电器检查支持情况；更旧系统可能先连 PD 充电器才显示该选项。

本地证据：`samsung-pause-usb-pd.html`；去除 script/style 并按文本节点分行的 `samsung-pause-usb-pd.txt:258`、`:260`、`:263`、`:266`、`:292`、`:300`。25W 指充电器能力要求，不能当作 SYS 实际耗电功率。

## ASUS：暂停充电与限速是不同功能

官方 [ROG Phone 7 产品页](https://rog.asus.com/phones/rog-phone-7/)把 `Steady Charging to control the charging speed` 与 `Bypass Charging that pauses charging while you're gaming` 并列。它支持“限速不等于暂停充电”的区分。

页面同段写 65W HyperCharge USB PD 充电器能快速充满，但没有写 Bypass Charging 必须达到 65W、必须 PPS、指定 SOC、指定 USB 端口或详细开启路径。因此这些条件在本报告中保持未知。不能把宣传段落中相邻的充电器规格转换为旁路功能的准入条件。

本地证据：`asus-rog-phone-7.html`、`asus-rog-phone-7.txt:329`。ASUS Game Genie FAQ 多个官方域名/地区 URL 的 curl 抓取均失败；候选 URL 没有作为已验证来源使用。

## Sony：H.S. control 已确认暂停电池充电

官方 [Xperia 1 IV：Using the Game enhancer menu](https://helpguide.sony.net/mobile/xperia-1m4/v1/en/contents/TP1001096940.html)明确写：Game enhancer 运行时，若 H.S. power control 开启，电池不充电；要给电池充电时关闭它。其目的表述为减少充电过热导致的性能下降与电池退化。

官方 [Xperia 1 V：Using the Game enhancer menu](https://helpguide.sony.net/mobile/xperia-1m5/v1/en/contents/use_game_enhancer_menu.html)确认 H.S. power control 设置存在，可针对每个游戏设置，且设置在不同 Game mode 之间共享。该页没有给出 SYS 供电拓扑。Xperia 1 II 的当前在线帮助也被保存，但本轮抓到的游戏页没有足够明确的 H.S. 文字，所以不拿它证明该型号的功能行为。

本地证据：`sony-xperia-1-iv-game-enhancer.txt:35`、`:36`，以及 `sony-xperia-1-v-game-enhancer.txt:197`。Sony support 站候选 FAQ 抓取失败；不能将未获取的 FAQ 或其他媒体对“直接供电”的转述当成该证据包的已核对原文。

## Pixel：80% 上限的官方证据与旁路证据分开

官方 [Get the most life from your Pixel phone battery](https://support.google.com/pixelphone/answer/6090612?hl=en)当前写“Pixel 6a and later devices”，并将 `Limit to 80%` 与 `Adaptive Charging` 分开。文档还说明每第 10 个循环仍需要充满，随后继续插电至少 30 分钟以完成容量读数校准。

此证据可以用于确认上限策略，不能确认 80% UI 的底层实现是 charger disable、输入限制还是电池开关。官网另一个 [Charge your Pixel phone](https://support.google.com/pixelphone/answer/7106961?hl=en) 页面被完整保存作为充电背景；本轮保存文本同样没有旁路控制接口说明。

本地证据：`google-pixel-battery-life.txt:181`、`:199`、`:204`、`:230`。其他版本文档、第三方报道或 Google charger 内核源码需要独立建立版本与 UI 的对应关系；不能直接与本页合并成一个已经证实的运行时实现。

## 对 dada 接口研究与最终比较的价值

各 OEM 的共同产品目标是：外部电源连接后，设备继续运行，同时减少或暂停电池充电。公开说明的强弱不同，均不足以证明电池物理断开或高负载瞬间电池完全不参与。充电上限、低充电功率、`Not charging` 状态、USB online 或单次电池电流接近零，都不足以单独证明这个完整目标已经实现。

最终验证需要另行回答三个问题：候选控制是否只影响电池充电；USB/DC 到 SYS 是否仍可供电；电池在稳态和负载变化时是否仍在持续放电。这个报告只提供比较标准，不提供手机实验命令。

所审阅的 OEM 页面公开的是用户菜单、行为和条件；没有公开可跨设备使用的 root API、sysfs 路径、寄存器地址或 GLINK 命令。本轮没有对这些品牌内核进行独立追链，故结论限定为“这些文档未公开底层接口”，而非“这些品牌没有接口”。dada 的控制链应继续以该设备实际驱动、固件及硬件文档为依据。

## 证据与可重复性

原始 HTML、派生文本、URL/时间/失败记录、逐文件长度和 SHA-256 位于 `.tooling/bypass-check/github-research/oem/`。派生文本是 HTMLParser 去除 script/style 后逐节点输出，可能包含目录与导航；引用行号只针对随包冻结的文本，不是远端页面行号。

`sources.json` 区分成功取得的 URL 与抓取失败的候选；`manifest.json` 固定原文、文本和来源记录的长度/哈希；`verification.json` 是再次读取文件核验的结果。官网会随时间变化，本报告结论以该包取得的内容为限。
