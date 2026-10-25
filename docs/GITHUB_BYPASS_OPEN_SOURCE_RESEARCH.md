# GitHub 用户态旁路充电方案源码对照

研究日期：2026-10-13。范围限定为两份真实实现：VR-25/acc、AbhishekTor55/charging-bypass-magisk；另核对一个高度相关但目前未公开实现的候选 Mahfujs247/MCACharge。全程只在电脑上下载和阅读公开源码，未调用 ADB、未访问手机、未执行任何上游脚本、未安装模块、未修改当前插件。这里的“支持”只描述上游代码能力，不代表本机验证成功。

## 结论

ACC 可借鉴策略层、状态判定、错误日志和正常退出恢复，但不能直接提供 Xiaomi 15 / dada MCA 的缺失内核契约。简单模块仅按屏幕状态写另一个设备的充电开关，还关闭 SELinux enforcing；不能作为本机方案或验证依据。MCACharge 名称与目标框架更相关，但固定公开提交只有 README，旁路仍列为计划。三者都没有展示本机 `charge_enable` 的 vote owner 撤销接口、实际运行充电芯片识别、quick/CP 与 buck 的完整恢复。

当前插件降低电流的事实与“系统由充电器供电、电池停止净充放电”是两个不同结论。开源模块自称 bypass、SOC 不升、`status=Not charging`、一次电池电流接近零，均不能单独证明后者。

## 固定版本与许可

| 项目 | 固定研究提交 / 源码版本 | 许可 | 实际机制 |
| --- | --- | --- | --- |
| [VR-25/acc](https://github.com/VR-25/acc/tree/908a5a4b716b7f2506a2e1426429293a11f42ab3) | `908a5a4b716b7f2506a2e1426429293a11f42ab3`；默认分支 dev；提交日期 2025-06-06；module.prop 为 `v2025.5.18-dev` / `202505180` | License.md 为 GPLv3；源文件标注 GPLv3+ | 已知节点数据库 + 可选扫描 + 实际 off/on 试写；按 SOC/电压/温度等控制暂停恢复；限流与限压 |
| [charging-bypass-magisk](https://github.com/AbhishekTor55/charging-bypass-magisk/tree/37d187d62e89e4ffe9e4165b2898e9d8b0627313) | `37d187d62e89e4ffe9e4165b2898e9d8b0627313`；module.prop 版本 1.0，但名称/日志写 v3 | MIT | 每秒读取屏幕状态，向 `battery/charging_enabled` 写 0 或 1 |
| [Mahfujs247/MCACharge](https://github.com/Mahfujs247/MCACharge/tree/b141b69820a8ca5e434ad51510ee52b5aa31568e) | `b141b69820a8ca5e434ad51510ee52b5aa31568e`；唯一公开分支 main；无公开 tag；README 标为 v0.1.0 | README 声明 MIT；该提交无独立 LICENSE 文件 | 仅 README，无法审核控制实现 |

ACC master 在查询时为 `f3d6eb0f5697c3d2e401097e9b5b6b8370bac1b2`（2023-10-16），因此本报告采用仓库元数据标明的默认 dev 提交，而不是将 master 当作当前实现。仅研究固定提交，不宣称该提交是最新 release。原始源码档案、元数据与 SHA-256 清单见 `.tooling/bypass-check/github-research/open-source/manifest.json`。

## ACC：从候选节点到硬件写入的完整链路

1. [ctrl-files.sh:1](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/ctrl-files.sh#L1) 将候选表示为 `路径 ON OFF`，可串联多个三元组。候选同时包含 `charging_enabled`、`input_suspend`、MTK power path、Google chg_suspend、nubia charger_bypass、温控档位与 SOC 上限。**这是多个物理语义的混合数据库，命中路径不能证明旁路。**
2. [accd.sh:541](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/accd.sh#L541) 初始化用存在性和当前值过滤候选；`filter_sw` 还执行 `chmod a+r`。它不是本任务要求的完全只读探测器。[acc.sh:193](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/acc.sh#L193) 的额外 parse 基于 power_supply 日志中的布尔值/enable 字符串推测反值，并用名字黑名单过滤；推测仍需后续试写验证。
3. [read-ch-curr-ctrl-files-p2.sh:17](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/read-ch-curr-ctrl-files-p2.sh#L17) 枚举电流控制文件、读当前值作为默认值、按数值大小猜 mA/uA；随后将电流 0、低电流和 3600mV 限压候选也追加到 charging switches。这会把 FCC/ICL 类限制混入“开关”测试。
4. [misc-functions.sh:125](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/misc-functions.sh#L125) 的 `disable_charging` 调用 `flip_sw off` 和 `not_charging`；失败先 `flip_sw on`，auto 模式再遍历候选。[cycle_switches:79](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/misc-functions.sh#L79) 逐个实际写入，成功便保存选中开关。`prioritizeBattIdleMode=true` 优先寻找到判定为 Idle 的候选；未找到还可退回普通 Discharging 暂停。
5. [flip_sw:278](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/misc-functions.sh#L278) 解析 ON/OFF 值，然后调用 [write:397](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/misc-functions.sh#L397)。writer 会 `chmod a+w`、通过 shell echo 写节点、读回比较（空读回不会触发不匹配），再重复写五次。能验证一部分写入失败/读回差异，但不能验证后台 vote 或硬件已经按请求变化。
6. [acc.sh:127](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/acc.sh#L127) 的 `test_charging_switch_` 真实关闭、采样判定、再打开，要求恢复充电后才判通过；[--test 分支:533](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/acc.sh#L533) 可遍历整个列表。上游 [README:114](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/README.md#L114) 明确提到 `acc -t` 对意外重启开关进行自动黑名单处理。**本研究未执行 auto/test/install；不应把这些命令当作安全的只读能力查询。**

### Idle 的证据强度

[batt-interface.sh:86](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/batt-interface.sh#L86) 直接把 `Not charging` 映射为 Idle；开启 `battStatusWorkaround` 时，[idle_discharging:1](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/batt-interface.sh#L1) 才使用电流绝对值与阈值（默认 10mA）分类。`battStatusOverride` 还允许依据开关值或脚本覆盖状态。因此 ACC 输出 `battIdleMode=true` 是其软件判定，不能代替本机供电通路证明。

[README:1502](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/README.md#L1502) 区分 native idle 与 emulated idle：后者可以是限充电电压、窄电压阈值循环或 50%/49% SOC 循环。这些策略保持电量附近，仍可能让电池持续参与缓冲或充放电，不能等同硬件切断电池充电通路。

### 恢复边界

[enable_charging:199](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/misc-functions.sh#L199) 尝试恢复缓存 `.sw`，然后写选中开关的 ON 值；失败再遍历 ON 候选。[accd 的退出函数:100](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/accd.sh#L100) 重置配置中的默认电流/电压、温控档位并 enable charging。[uninstall.sh:38](https://github.com/VR-25/acc/blob/908a5a4b716b7f2506a2e1426429293a11f42ab3/install/uninstall.sh#L38) 先终止 daemon 并等待，10 秒后可 SIGKILL；它自身未直接撤销充电开关。正常退出恢复不能覆盖 SIGKILL、断电、内核失败或配置默认值不等于原态的情况。

这里主要是“写 ON/写默认值”，不是完整快照事务。读默认电流值不等于保存 vote owner 的 absent/enabled/value，也没有权限原态恢复。多节点组失败时没有逐项事务回滚。本机 MCA 的原态可能不存在该 owner；即使 shell ON 写成功，也不能据此声称已撤票。

## 简单 Magisk 模块：源码与 README 的差距

[README:2](https://github.com/AbhishekTor55/charging-bypass-magisk/blob/37d187d62e89e4ffe9e4165b2898e9d8b0627313/README.md#L2) 声称 Android 14–16、Miatoll AOSP16 测过；这是作者声明，没有 dada 证据。实际 [service.sh:5](https://github.com/AbhishekTor55/charging-bypass-magisk/blob/37d187d62e89e4ffe9e4165b2898e9d8b0627313/service.sh#L5) 每秒执行 dumpsys display，根据 `mScreenState` 输出中是否有 `ON` 决定写值；亮屏先 `setenforce 0` 再写 `battery/charging_enabled=0`，其他情况写 1。

- 不检查充电器是否连接、节点是否存在、写入是否成功或读回是否一致；解析失败也会走 OFF/写 1 分支。
- 不记录原始开关状态，不安装退出 trap，不验证输入电源是否仍给系统供电，不读取电池电流，不协调快充/CP。
- [uninstall.sh](https://github.com/AbhishekTor55/charging-bypass-magisk/blob/37d187d62e89e4ffe9e4165b2898e9d8b0627313/uninstall.sh) 仅删除日志，没有恢复 charging_enabled 或 SELinux enforcing。亮屏写 0 的返回码被后面的日志掩盖，日志宣称 disabled 不表示成功。
- [README:30](https://github.com/AbhishekTor55/charging-bypass-magisk/blob/37d187d62e89e4ffe9e4165b2898e9d8b0627313/README.md#L30) 以电量不升、图标返回、开关读值作为测试；这些证据不能区分暂停电池充电但保留输入、暂停整个输入、限流或电池仍在放电。

因此该项目只可用于说明“状态触发策略 + sysfs 写入”的最小结构，不能照搬其节点、SELinux处理或恢复逻辑。

## MCACharge：相关框架名称不等于已公开控制代码

仓库实际存在；本次 `git ls-remote HEAD` 与 `git ls-remote --heads --tags` 均固定到 `b141b69820a8ca5e434ad51510ee52b5aa31568e`，后者只返回 main 分支，未返回 tag。codeload 档案仅包含根目录与一个 [README.md](https://github.com/Mahfujs247/MCACharge/blob/b141b69820a8ca5e434ad51510ee52b5aa31568e/README.md)，没有 service、WebUI、HAL 调用、节点写入或卸载恢复代码。证据见 `mca-source.tar.gz`、`mca-refs.txt` 与 manifest；未把未公开版本猜作实现。

README 自述针对新 HyperOS 的 MCA/xm_power，目标与自述测试设备为 Xiaomi Civi 5 Pro / luming、HyperOS 3、Android 16。它把 framework detection、live dashboard、charger type、Quick Charge status、policy information 列为 Current；把 charging current control、profiles、smart charging、node inspector、MiCharge HAL support、bypass charging research 列为 Planned。由于缺少代码，连 Current 功能也只能标作作者声明，无法核对如何读取。

该候选值得后续关注，但当前不能回答 store 的 `user/type/value` 协议、owner 命名/生命周期、enabled=0 撤票、异常恢复与 dada 适配问题。不能将其 README 中的“reverse engineering”声明作为本机缺失恢复契约已经解决的证据。

## 对 dada MCA 的适用性判断

此前本机 ELF 静态证据见 [DADA_NEXT_RESTORE_RESEARCH.md](DADA_NEXT_RESTORE_RESEARCH.md)、[DADA_NEXT_SIDE_EFFECT_RESEARCH.md](DADA_NEXT_SIDE_EFFECT_RESEARCH.md) 与 [DADA_CHARGE_CONTROL_CHAIN.md](DADA_CHARGE_CONTROL_CHAIN.md)。结论依然成立：

| 问题 | ACC 能否填补 | 简单模块能否填补 |
| --- | --- | --- |
| 本机 `charge_enable` 是 `user type value`，buck/quick setter 固定 `enabled=1` | 未发现实现；数据库三元组不提供 MCA 三参数协议语义，也没有专用撤票方法 | 只写另一个路径的单个整数，未实现 |
| 恢复为 owner 不参与，而不是写 active allow=1 | `flip_sw on` 是写预设值，没有 `mca_vote(enabled=0)` 用户态入口 | 写 1，没有撤销协议 |
| strict absent 恢复与查询可能分配 owner | 未保存/恢复 MCA owner 表；无法消除此约束 | 未处理 |
| 正在运行的 PMIC/remote provider 与电路意义 | sysfs 名称、状态和电流可提供线索，未证明 dada 的 runtime binding | 无相关实现 |
| buck + quick/CP 及副作用完整恢复 | 多节点可配置，但本机节点、副作用及反向恢复契约仍须证明 | 单节点无协调 |

ACC 可借鉴显式手动开关、暂停/恢复阈值、日志与退出恢复结构；真正适配仍需首先确认芯片电源通路及 provider 的运行身份，获得能够撤销自己控制权的接口，并验证错误传播与恢复。不能用自动试写节点来替代这些缺失证据。

未来如经审核进入手机验证，应使用预先限定的单一接口与明确的恢复契约，并对照相同 SOC/温度/负载下的输入电压电流、电池净电流及其方向、buck/CP 状态、投票状态和恢复后状态。上述是后续验收条件，本报告没有执行该实验，也没有提供让用户直接运行的试写命令。
