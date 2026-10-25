# GitHub 公开充电驱动与 dada MCA 旁路接口对照

日期：2026-10-13。范围：最多三个内核/驱动候选，只读公开源码和已有本地证据；本子任务没有执行 ADB、手机 root 命令、节点写入、安装或下载源码中的脚本。本文不是可执行测试方案。

## 结论

本轮没有找到能直接接到当前 dada MCA、且已经证明可独立控制电池充电并撤销本插件票的公开实现。找到的两个其他 Android 实现恰好说明节点名称不能替代控制链证据：

| 候选 | 实际调用 | 退出/恢复 | 对当前手机的价值 |
| --- | --- | --- | --- |
| MiCode dada-v-oss | SMB5 的 CHG_DISABLE 与输入暂停在源码中独立；当前 MCA 目录只是指向 proprietary 的 symlink | SMB5 有 voter 核心；未获得匹配 MCA 公共撤票入口 | 架构参照与发布缺口证据，不能换用旧 SMB5 节点或寄存器 |
| Lineage Xiaomi sm8250 | battery_charging_enabled 实际给 USB_ICL 投票；battery_input_suspend 给 FCC 投 0 票 | enabled 写非零撤 USB_ICL 指定客户端票，部分构建还撤 ESR FCC 票 | 排除按节点名称套用；FCC=0 思路需重新核对当前 provider |
| XtraAether sm8450 仓库 | bypass_charging_enable 实际发送 BATT_CHG_CTRL_LIM 热限档 | 退出直接写档位 0，未保存原档、未提供独立票所有权 | 能参考 UI 名称，不能作为已实现电气旁路的证据 |

两个跨设备候选都没有闭合自己的 ADSP/硬件供电链。对 Xtra 的结论严格限定为 Linux 调用属性及热限档，不断言远端一定只改 FCC 或一定断输入。[X1][X2]

## 1. 最贴近设备：MiCode dada-v-oss 与缺失的 MCA 实现

官方 README 在固定提交 `16d89a630ea8752c84bf4d76201964524d620616` 把 dada-v-oss 对应 Xiaomi 15 / Xiaomi 15 Pro、Android V、qcom-LA.VENDOR.15.4.0.r1-08900-r1.0.r1_00022.1。[D0] 已固定内核提交 `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5`，`drivers/power/supply/mca` 是 mode 120000 的 symlink，内容是 `../../../../../vendor/xiaomi/proprietary/mca/driver/mca`，不是可审查的 MCA C 源码。[D1] 因而不能从该公开发布获得与当前 `mca_vote(chg_enable, user, 1, bool)` 匹配的 public API、头文件、MCA 文件许可或可构建 driver。

同一发布里的 `drivers/power/supply/qcom/smb5-lib.c` 可用作机制对照：

- 1757-1775：`smblib_chg_disable_vote_callback` 只在此局部写 `CHARGING_ENABLE_CMD_REG` 的使能位；8218-8220 创建 `CHG_DISABLE`，类型 `VOTE_SET_ANY`。[D2]
- 2448-2475：`smblib_set_prop_input_suspend` 分别给 `usb_icl_votable` 的 USER_VOTER 投 0mA、给 `dc_suspend_votable` 投暂停票；写 false 是撤这个客户端的票。[D2]
- `qpnp-smb5.c` 1195-1218/1331-1335/1360-1374：此固定版本公开的是 `INPUT_CURRENT_LIMIT` 路由到上述暂停 setter，没有 `BATTERY_CHARGING_ENABLED` 属性。因此也不能把别的 fork 的节点表套在同一设备代号上。[D3]
- `smb5-reg.h` 62-63 给出了该旧 SMB5 实现的 CHGR_BASE+0x42/bit0；它不是当前 firmware 某个同偏移寄存器的意义证明。已解码固件的 runtime model、SID/ops 和副作用仍需独立闭合。[D4]

SMB5 文件是 GPL-2.0-only，且这是内核 driver 内部函数/投票体系，不是 SukiSu shell 插件可直接调用的稳定 ABI。其公开源码存在不能证明 dada 当前加载、绑定或经它控制充电。[D2][D3]

额外有界检查：GitHub 仓库名称搜索 `android_kernel_xiaomi_sm8750` 返回七个仓库，选择其中专门的 `piano-LAB/android_kernel_xiaomi_sm8750-modules`。固定 `09037d31e562e4335ac53064c376dfbe4744d8e2` 的递归树返回 11037 项、`truncated=false`；路径中没有 MCA 目录或 charger 源文件。[D5] 这只说明检查过的这个固定树未提供目标源码，不是所有公开 fork 都不存在。另一次 `mca xiaomi in:name,description` 搜索返回 MCACharge WebUI 和无关 icon pack；搜索本身不是代码级穷尽证明。MCACharge 由模块候选研究单独审查。

与已解码当前模块比较：MCA 的 chg_enable 为 AND/default0，而 SMB5 的 CHG_DISABLE 为 SET_ANY 禁充票，极性、default 和聚合规则均不相同。当前 charge_enable 0/1 都会留下 active 用户票，其他 voter 的同名客户端撤票不能跨对象恢复 chg_enable。公开 SMB5 的撤票语义不能自动填补这个缺口。见本仓库 [DADA_CHARGE_CONTROL_CHAIN.md](DADA_CHARGE_CONTROL_CHAIN.md) 和 [DADA_RECONNECT_CONTROL_RESEARCH.md](DADA_RECONNECT_CONTROL_RESEARCH.md)；这部分依据本地模块反汇编，不属于找到的公开 MCA 源码。

## 2. Lineage Xiaomi sm8250：同名停充接口实际改输入限流

来源固定 `a04dba03999b36cc8f472c014063395b41fb1022`，查询时分支 lineage-22.2。文件开头 SPDX GPL-2.0-only；Makefile 第3行在 `CONFIG_QPNP_SMB5` 下编译 qpnp-smb5/smb5-lib/pmic-voter 等，Kconfig 18-29 定义 SMB5 充电外设 driver。另有 dagu/psyche/munch 变体，本文只审查普通 `smb5-lib.c`，不假定具体出厂手机选择哪一变体。[L4]

真实可写链是 `power_supply_sysfs.c:484` 的 `battery_charging_enabled` → `qpnp-smb5.c:3339-3340` 的 setter，3378 行允许写 → `smb5-lib.c:3641-3681`。[L1][L2][L3] setter 没有投 CHG_DISABLE，而是：

- six_pin_step_charge 分支投 USB_ICL 的 MAIN_ICL_MIN_VOTER；其他分支投 MAIN_CHG_SUSPEND_VOTER，数值依 power_good_en、QC 状态而定，部分路径为 0。
- 某些 fuel-gauge 构建还会启动 reduce_fcc_work；8110-8144 可见其通过 ESR_WORK_VOTER 循环改变 FCC 票。
- 写非零撤上述 USB_ICL 客户端票；相应构建下还撤 ESR_WORK_VOTER 并取消 delayed_work。函数没有逐个检查 vote 返回值，最终直接 return0。

同源码的 `smblib_set_icl_current` 在1870行计算 `icl_ua <= USBIN_25MA` 是否 suspend，1891行进入 `smblib_set_usb_suspend(true)`；765-783行写 USBIN_SUSPEND 位。这仅证明该函数在特定 ICL 值下的局部分支；此次取得的文件通过 `find_votable` 获取外部创建的 USB_ICL，尚未证明该外部 votable 的 callback 绑定或特定 runtime 调用会走到这个函数。因此不能将上层 setter 到输入寄存器描述为已闭合，也不能把 `battery_charging_enabled` 统称“只关闭电池充电”。[L2]

同一源码的 `battery_input_suspend` 倒是经 `qpnp-smb5.c:3246-3247` → `smb5-lib.c:3183-3197` 给 FCC USER_VOTER 投 0，false 撤票；它与 `input_suspend`（3158-3180）给 USB/DC 暂停投票的行为分开。[L1][L2] 这提示可以研究“专门 FCC 停充”接口，但不能据此确认当前 MCA 的 FCC=0 电气结果或可移植节点名。

所有权限制：用户态 sysfs 没有传入插件专用 client 名，使用预设 voter；用户、另一充电工具或 OEM 使用同一客户端时，写 false 会撤同一票槽，无法证明只撤本插件限制。pmic-voter 的 `vote()` 是按 client_str 查同一槽并更新 enabled/value，再重新聚合，SET_ANY 是所有 enabled 的 OR；这是独立客户端撤票模式的源码依据，不是现成的用户态租约机制。[L5] 其 getter（2548-2569）也是查询特定 USB_ICL 票，不是测量当前电池电流或供电通路。

## 3. XtraAether：名字叫 bypass，Linux 实现是热限档

来源 `Gustyx-Power/android_oss_kernel_xiaomi_sm8450`，查询时分支 oss-5.10.xx，固定 `3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c`。完整 tree 中实际文件在 `drivers/power/supply/`，不是 qcom 子目录。教程自称针对 POCO F5 / Marble、Snapdragon 7+ Gen 2、AOSP Android14-16；这些是作者声明，不是当前 dada 的兼容性证明。[X0]

`qti_battery_charger_xiaomi.c:2003-2053` 的实现：[X1]

- 开启：取 `num_thermal_levels * bypass_thermal_level_percent / 100`，百分数默认89，并截到最后一个档位；发送 BATTERY `BATT_CHG_CTRL_LIM` 属性。
- 关闭：直接对相同属性发送 0，没有读取/保存之前档位，没有独立 voter 或 compare-and-restore。
- 状态读取：仅返回 Linux static `bypass_charging_enabled`；不是远端读回，也不是电池电流或电气旁路测量。
- `bypass_charging_level` 50-100 指档位索引百分比，不能解释为89%电流抑制；具体档位到电流/硬件动作的对应未闭合。

同 driver 的 `qti_battery_charger.c:1268-1286` 将标准 charge_control_limit 的 val 按 thermal-level 处理并发送同属性；74行映射 POWER_SUPPLY_PROP_CHARGE_CONTROL_LIMIT。因此可确认 Linux 调用层是热限档入口。236-254行把 property/value 封成 PMIC GLINK 消息，远端 charger firmware 接收之后的处理代码未取得；不能从这里证明独立的电池充电通路开关。[X2]

还有一个状态问题：`battery_chg_write:179-200` 在 GLINK DOWN 或 debug block_tx 条件下直接 return0，可能没有发送消息；bypass store 在没有负返回值时照样设置 static flag。因此显示1不能单独证明请求送达。[X2]

头文件423行有 `XM_PROP_BYPASS_CHARGING_ENABLE`，但在此次取得的主 driver、Xiaomi driver、header 三文件中，只有该枚举声明命中，bypass store 并没有使用它。[X3] 教程的“温度过高自动退出”“直接由充电器供电”宣称，也没有由这个 store、show 或 flag 的所有读写位置证明；不把宣传当作闭合实现。[X0][X1]

GPL-2.0-only 的两份 driver 在 Makefile100-101行合进 `qti_battery_charger_main.o`；Kconfig437-448依赖 QTI_PMIC_GLINK/EXTCON 并明确通过远端 DSP firmware 管理 charger。教程要求替换 vendor/vendor_dlkm 内核模块，涉及目标内核 ABI/配置、GLINK协议和远端 firmware 匹配，绝不是 root 后可通用的插件接口；本子任务没有执行教程任何步骤。[X4]

## 可复用部分与不可移植边界

可复用的是审查方法：分别闭合 sysfs → property/vote → aggregate callback → provider → 远端实现；退出要撤独立插件 client 的票，并处理失败/重连/原策略。可参考 SMB5 的 CHG_DISABLE 与 USB_SUSPEND 分离，以及 voter 重新聚合设计。[D2][L5]

不能移植的是 sysfs 名字、旧 PMIC 的寄存器地址、GLINK property 数字、第三方模块二进制或热限档默认0。当前 MCA chg_enable 的 active票缺少撤销协议，当前远端2003还有模型相关副作用，公开候选都没有解决这两个已知问题。即使停充成功，负载下电池接近0A且适配器仍向系统供能也仍需后续独立证据；本轮没有硬件验证。

## 固定来源

- [D0] [MiCode README](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/16d89a630ea8752c84bf4d76201964524d620616/README.md)，dada-v-oss 行；已有快照 `.tooling/bypass-check/root-next/official-source-check.json`。
- [D1] [官方 MCA symlink](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca)，同快照及 provider/micode-dada-tree.json。
- [D2] [官方 smb5-lib.c](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/smb5-lib.c#L1757)，本地 reconnect-evidence/dada-smb5-lib.c。
- [D3] [官方 qpnp-smb5.c](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/qpnp-smb5.c#L1195)，本地 reconnect-evidence/dada-qpnp-smb5.c。
- [D4] [官方 smb5-reg.h](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/smb5-reg.h#L62)，本地 reconnect-evidence/dada-smb5-reg.h。
- [D5] [piano-LAB 模块固定树](https://github.com/piano-LAB/android_kernel_xiaomi_sm8750-modules/tree/09037d31e562e4335ac53064c376dfbe4744d8e2)，本次 API tree 原文保存 github-research/kernel/piano-sm8750-modules-tree.json。
- [L1] [Lineage qpnp-smb5.c](https://github.com/LineageOS/android_kernel_xiaomi_sm8250/blob/a04dba03999b36cc8f472c014063395b41fb1022/drivers/power/supply/qcom/qpnp-smb5.c#L3339)。
- [L2] [Lineage smb5-lib.c](https://github.com/LineageOS/android_kernel_xiaomi_sm8250/blob/a04dba03999b36cc8f472c014063395b41fb1022/drivers/power/supply/qcom/smb5-lib.c#L3641)。
- [L3] [Lineage power_supply_sysfs.c](https://github.com/LineageOS/android_kernel_xiaomi_sm8250/blob/a04dba03999b36cc8f472c014063395b41fb1022/drivers/power/supply/power_supply_sysfs.c#L484)。
- [L4] [Lineage Makefile](https://github.com/LineageOS/android_kernel_xiaomi_sm8250/blob/a04dba03999b36cc8f472c014063395b41fb1022/drivers/power/supply/qcom/Makefile#L3) / [Kconfig](https://github.com/LineageOS/android_kernel_xiaomi_sm8250/blob/a04dba03999b36cc8f472c014063395b41fb1022/drivers/power/supply/qcom/Kconfig#L18)。
- [L5] [Lineage pmic-voter.c](https://github.com/LineageOS/android_kernel_xiaomi_sm8250/blob/a04dba03999b36cc8f472c014063395b41fb1022/drivers/power/supply/qcom/pmic-voter.c#L70)，vote 主体420-516。
- [X0] [Xtra 教程](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/BYPASS%20CHARGING%20TUTORIAL.md)。
- [X1] [Xtra Xiaomi driver](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/qti_battery_charger_xiaomi.c#L2003)。
- [X2] [Xtra 主 driver](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/qti_battery_charger.c#L179)。
- [X3] [Xtra header](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/qti_battery_charger.h#L423)。
- [X4] [Xtra Makefile](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/Makefile#L100) / [Kconfig](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/Kconfig#L437)。

本轮抓取原文、成功/失败记录、固定ref及hash放在 `.tooling/bypass-check/github-research/kernel/`。网络最初在sandbox DNS失败，按批准流程升级只读网络查询；一次模块tree读取出现网络错误，重试后完整取得。没有以失败结果推断源码不存在。
