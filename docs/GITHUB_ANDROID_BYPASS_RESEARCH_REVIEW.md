# GitHub 与其他 Android 手机旁路供电方案：主审对照

日期：2026-10-13。结论：**有开源充电控制实现，也有厂商正式提供的旁路/游戏停充功能；本轮没有找到可直接适配当前 Xiaomi 15 / dada、同时解决控制与可靠恢复的现成 SukiSU 模块。** 这个结论限定于下列固定提交和官方资料，不是对所有仓库的穷尽证明，也不表示 dada 硬件一定不支持。

本轮由三个研究子代理分别审查用户态模块、公开驱动和厂商官方资料，主代理交叉阅读关键源码，另核对 Google 官方驱动和小米官方支持页。工作仅发生在电脑：手机命令、ADB、设备读取/写入、安装、加载驱动、重启和硬件试验均为 0。没有执行下载的项目脚本，也没有修改现有插件。

## GitHub 上已取得的实现

| 项目与固定提交 | 源码实际做了什么 | 对本项目的价值与限制 |
| --- | --- | --- |
| [VR-25/acc](https://github.com/VR-25/acc/tree/908a5a4b716b7f2506a2e1426429293a11f42ab3)，`908a5a4…`，v2025.5.18-dev | 按电量、温度、电压控制暂停/恢复，支持优先寻找 Battery Idle Mode；具有候选节点表、日志和正常退出恢复 | 策略层最值得参考。能否保留外部供电由设备驱动决定；候选混合停充、输入暂停、限流、限压。自动探测/测试会真实试写，不能用于本次只读调查；未实现 dada MCA 专用撤票协议 |
| [charging-bypass-magisk](https://github.com/AbhishekTor55/charging-bypass-magisk/tree/37d187d62e89e4ffe9e4165b2898e9d8b0627313)，`37d187d…` | 每秒根据屏幕状态写 `battery/charging_enabled`，亮屏还执行 `setenforce 0` | 有代码，但无写入检查、原态记录、供电验证、退出恢复；卸载只删日志。作者自述 Miatoll 测试不能证明 dada 兼容，不采用其控制方式 |
| [Mahfujs247/MCACharge](https://github.com/Mahfujs247/MCACharge/tree/b141b69820a8ca5e434ad51510ee52b5aa31568e)，`b141b69…` | 当前固定提交只有 README，没有控制脚本、WebUI 或 HAL 源码；旁路研究列在 Planned | 名称与新小米 MCA 框架相关，目标声明是 Civi 5 Pro / luming。值得关注，但当前不能作为已公开实现，不能填补撤票或恢复缺口 |
| [XtraAether 内核候选](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/tree/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c)，`3fcd84e…` | `bypass_charging_enable` 实际发送 `BATT_CHG_CTRL_LIM` 热限档；默认 89 是档位索引百分比，关闭直接写档位 0 | 教程声明 POCO F5 / Marble；源码未闭合远端物理控制，也未保存原档。show 只是软件 flag，底层在 GLINK DOWN 时可能返回成功而未发送。不能把名字或游戏时电量增长慢当作已验证旁路 |
| [Lineage Xiaomi sm8250](https://github.com/LineageOS/android_kernel_xiaomi_sm8250/tree/a04dba03999b36cc8f472c014063395b41fb1022)，`a04dba0…` | `battery_charging_enabled` 投的是 USB_ICL 策略票；另一个 `battery_input_suspend` 才投 FCC=0 | 说明同名节点可能控制不同通路。可参考撤客户端票的设计；sysfs 使用预设 client，未提供独立插件所有权，也不是当前 MCA 的可移植接口 |

ACC 属 GPLv3 系列，简单模块属 MIT，所查内核 driver 为 GPL-2.0-only；MCACharge 只有 README 的 MIT 声明。借鉴源码时仍要遵守相应许可，不能把“公开可读”直接当作可任意复制的授权。详细调用链、恢复逻辑、许可与固定源码见[用户态专项](GITHUB_BYPASS_OPEN_SOURCE_RESEARCH.md)和[内核专项](GITHUB_BYPASS_KERNEL_RESEARCH.md)。

## 其他 Android 手机已经提供的功能

| 官方资料与直接核对的机型 | 功能及条件 | 能确认到什么程度 |
| --- | --- | --- |
| [Samsung Galaxy：Pause USB PD charging](https://www.samsung.com/levant/support/apps-services/pause-usb-pd-power-delivery-charging-when-gaming-feature-on-your-galaxy-device/)；支持依具体机型菜单 | 暂停电池充电、优先给设备供电。需 PD + PPS、充电器能力至少 25W、电量至少 20%、游戏运行；熄屏或切离游戏画面时不工作 | 官方明确描述保留设备供电的产品行为；没有给通用 root 接口或电池物理隔离定义 |
| [ASUS ROG Phone 7：Bypass Charging](https://rog.asus.com/phones/rog-phone-7/) | 游戏时暂停充电，与 Steady Charging 的充电限速分开 | 官方确认功能。页面中的 65W 是配套充电器规格，没有写成旁路门槛；SOC、PPS、端口限制未从此页确认 |
| [Sony Xperia 1 IV：H.S. power control](https://helpguide.sony.net/mobile/xperia-1m4/v1/en/contents/TP1001096940.html)；[1 V 也有该设置](https://helpguide.sony.net/mobile/xperia-1m5/v1/en/contents/use_game_enhancer_menu.html) | Game enhancer 运行且开关开启时，1 IV 官方明确电池不充电；要充电则关闭 | 证实游戏停充行为。所取原文未证明电池物理断开或 SYS 拓扑，不能扩写为已经独立验证直接供电 |
| [Google Pixel 6a 及之后：Limit to 80%](https://support.google.com/pixelphone/answer/6090612?hl=en) | 官方支持 80% 上限；每第 10 个循环仍需充满，100% 后继续插电至少 30 分钟完成容量读数校准 | 此页没有宣称是旁路，不把它计入本轮已证实旁路的产品清单，也不由此断言 Pixel 硬件不支持 |

这些产品行为证明功能在部分 Android 手机上存在；厂商菜单本身不提供跨设备移植方法。详细条件、官方原文及失败来源边界见 [OEM 专项](ANDROID_OEM_BYPASS_RESEARCH.md)。

## Google 官方源码提供的具体参照

除 GitHub 候选外，主代理取得 Google 官方 `kernel/google-modules/bms` 的 `android-gs-caimito-6.1-android15` 分支固定提交 `0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f`。两份源码的 Git blob SHA-1 与官方树一致；这是一个公开的、条件明确的控制实现参照，不是当前 Pixel 80% 设置的源码归属证明，更不是 dada 适配代码。

可复核的链是：

1. [google_charger.c:3660](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/google_charger.c#3660) 的 debug `chg_suspend` 给 MSC_FCC 的 USER_VOTER 投 0，关闭该请求时把 enabled 置 false；不是对 USB_ICL 投 0。MSC_FCC 创建时没有 callback，实际由 [MSC_INTERVAL 更新回调:4369](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/google_charger.c#4369) 读取生效 FCC，经 charger 更新传入 `POWER_SUPPLY_PROP_CONSTANT_CHARGE_CURRENT_MAX`。需要有效 FCC 确为 0、更新周期大于 0、非 NOOP、有效 FV、PPS 流程允许及 I/O 成功；写 debug 节点不保证立即执行硬件动作。
2. **若设备树配置的 charger 确实绑定 max77759**，同树 [max77759_charger.c:2384](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/max77759_charger.c#2384) 的 provider 会按参数/缓存等分支调用 [电流 setter:1404](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/max77759_charger.c#1404)。current=0 成功写入后经 [mode 投票:1295](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/max77759_charger.c#1295) 撤去 `CC_MAX` 的充电 mode 请求。本轮没有取得目标设备树来证明某个实际 Pixel 绑定此 driver。
3. **在撤后其他充电 mode 票计数小于 2、有效 USB buck 请求仍保留，且无 raw、CHGIN_OFF、standby、DC、OTG/FRS、USB+WLC 或 WLC/POGO 替代 usecase 时**，[usecase:784](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/max77759_charger.c#784) 可选择 `BUCK_ON`，区别于充电时的 `CHGR_BUCK_ON`。[充电判定:608](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/max77759_charger.c#608) 使用票计数至少 2，故撤一票不是普遍停充保证。[输入选择:867](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/max77759_charger.c#867) 在 buck 请求和输入未暂停的条件下保留 CHGIN；模式转换、输入选择及寄存器写入成功后才落地。这支持“保留输入降压供电、停止充电”的条件性源码设计，但不证明某台手机的运行绑定、电池已被物理隔离或在所有负载下电池电流为零。
4. 同一 provider 的 [GBMS_PROP_CHARGE_DISABLE](https://android.googlesource.com/kernel/google-modules/bms/+/0578e6b493fce7a8017d9ec54bcf9ba7ad89c53f/max77759_charger.c#2541) 却投 `STBY_ON`，其普通有线分支可选择 `ALL_OFF`。因此连 `CHARGE_DISABLE` 名称都不能跨驱动当作保留外部供电的保证。`input_suspend` 也另走 USB/DC 输入暂停链。

这组源码可借鉴的是：分别表示充电请求、系统输入请求和整体 standby，按独立 client 重新聚合并处理模式转换。它没有提供跨手机通用 root 开关，也没有为 MCA 的数据结构、寄存器或 GLINK property 提供替代定义。这里未审完其全部错误回滚与所有权恢复，不能把它描述为可直接移植的安全后端。

## 对当前 Xiaomi 15 的判断与建议

本轮结果没有改变[上一轮主审](DADA_NEXT_RESEARCH_REVIEW.md)中的三个关键事实：

- 已找到的本机 MCA `charge_enable` 用户入口固定 `enabled=1`；写允许值不等于撤销插件的请求。上述开源模块没有提供缺失的 enabled=0 用户协议，也没有解决查询可能分配 owner、quick/CP 及附带操作恢复。
- [MiCode dada-v-oss](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca) 的 MCA 入口是指向 proprietary 路径的链接。额外检查的一个 sm8750 modules 固定树也未提供目标源码；这个范围内仍没有匹配当前构建的 MCA 实现。
- [小米 15 官方充电说明](https://www.mi.com/uk/support/faq/details/KA-538544)解释的是 Smart charging / Battery protection 的 80% 限充，不包含足以确认保留外部系统供电的描述。此页不能证明旁路支持，也不能单独证明没有旁路。另一个 [POCO F7 官方问答](https://www.mi.com/uk/support/faq/details/KA-618354)明确说该机不支持 bypass，说明同品牌或同为高通也不能自动推定相同能力。

**开发路线仍应是“现有策略层 + 经过证明的机型专用后端”。** ACC 的 SOC/温度条件、迟滞、日志和正常退出流程可以作为策略参照；Google/SMB5 的输入与充电分离设计可以作为底层接口审查参照。dada 后端的下一项具体工作是取得匹配的 MCA/provider 资料或厂商已有入口，证明专用 owner 的申请/撤销、错误传播及完整恢复；资料齐备再决定如何实现，不安装 ACC 自动探测或第三方内核来绕过资料缺口。

现有插件仍应按已验证的 FCC 限流能力评估，不升级为“已验证旁路”。后续旁路验收需要在相同电量、温度和负载条件下，同时确认外部输入承担系统负载、电池净电流及方向、充电/CP 状态和退出恢复。充电瓦数降低、SOC 不升、一个软件开关显示 1 或单次电池电流为 0 均不足以完成验收。本轮没有执行这些实机试验。

原始资料与散列保存在 `.tooling/bypass-check/github-research/`；Google/小米资料清单为 [root-source-manifest.json](../.tooling/bypass-check/github-research/root-source-manifest.json)，Google 行号摘录为 [google-source-excerpts.json](../.tooling/bypass-check/github-research/google-source-excerpts.json)。Google 专项独审见 [google-independent-review.json](../.tooling/bypass-check/github-research/kernel/google-independent-review.json)。
