# DADA ADSP 供电控制契约：最终可行性结论

日期：2026-10-13。范围：固定官方固件的静态字节、已保存的 Linux 模块身份、匹配的软件调用与公开 primary 源码。本报告作者执行手机、ADB、寄存器访问、控制、安装和目标固件执行均为 0 次。本文收尾可行性调查，不代表已完成电气实测，也不批准新增手机操作。

## 1. 最终结论与实施判定

已把 `BUCK_ENABLE_CHARGING=0x2003` 追到主充电使能字段的 masked 写请求，也完成其五类附带操作、三类传输选择器及服务分派边界的必要调查。**当前仍不能实现并验证一个安全、可恢复的“外部电源供系统且停止电池充电”后端；本机原生控制实施为 NO-GO。**

这不是“厂商只有关闭命令、没有打开命令”的单向接口：静态程序存在 enable 的设置与读取，参数 0/1 对主位有对应路径。真正缺口是本机运行时选择、精确 PMIC/外设修订的电气定义、全部附带动作的恢复，以及能区分 SYS 输入与电池支路的有效测量。把主位改回 1 或取得成功 ACK，不能证明所有原状态已恢复。[主链](DADA_RECONNECT_PROVIDER_RESEARCH.md)、[附带操作](DADA_NEXT_SIDE_EFFECT_RESEARCH.md)、[测量与准入](DADA_CONTROLLED_VALIDATION_FINAL.md)。

本轮完成了可用材料范围内的闭合工作；剩余项明确列为外部材料或运行时证据条件。没有匹配资料时，不用未知寄存器试写、裸 property RPC 或无限扩大不具名函数扫描替代这些条件。

## 2. 来源身份与证明层级

固定官方包中 ADSP selected 文件与早期采集材料逐字节相同；本轮重新检查 `charger-analysis.elf` 四个 file-backed section 与官方 `adsp.b19/b20/b21/b22` 相等。MDT 为 ELF32 LE，machine 164，flags `0x79`；使用固定主机 LLVM 的 Hexagon v79 解码，分支按 packet PC 解释。[包对比](../.tooling/bypass-check/stage1/root-firmware/adsp-package-comparison.json)、[本轮输入与窗口](../.tooling/bypass-check/final/power/evidence-index.json)。

| 文件 | SHA-256 |
| --- | --- |
| `adsp.mdt` | `0708ab9b2a63e3d83b2f388baff403a541c5c930d15993a9555f3e59bb8e7f1e` |
| `adsp.b19` | `b4529c2d95b3a058751b1528c1bb1183b62a4aa145d6b5d0db474c62038df374` |
| `adsp.b20` | `1028fe2fd204f51592b1c77c1c89caa55b887305e41d327351fe2b6af9ad6dcc` |
| `adsp.b21` | `8162fce811481d34773639c84866fb74b219aa16f1cc0820cb4919e1bf69e491` |
| `adsp.b22` | `4ae0f1128f02457f8999d88b69ed3f6476eafa6220fc794c0cbe384f9544d266` |

第四阶段证明 18 个选定 Linux 模块的 loaded build-id 与已采集 ELF GNU note 一致，并核对部分文件哈希与 class/driver 链接；这些采集不是原子快照。它没有证明当前 ADSP 运行内存身份、实际 ops/对象值、物理器件型号或供电拓扑。BSS 中的零值也不是当前 runtime callback 值。[身份审计](DADA_RUNTIME_IDENTITY_STAGE4.md)。

本轮新增 FG/BQ 测量材料来自完整固定 `vendor_dlkm.img` 的 392 个 KO 清单，而非重新从手机读出。镜像 SHA-256 为 `b11940f67f673076ce5f0ac409919505d7af7495f4999f123891f426ae63042b`；新分析的策略 FG、平台 FG 与 BQ KO 尚没有本轮 loaded-note 对照。包中有实现，不等于本机正在注册该实现。[逐模块来源](../.tooling/bypass-check/final/power/package-provenance.json)。

## 3. `0x2003` 已闭合的软件控制契约

Linux `mca_qcom_subpmic_proxy` 的 `qcom_subpmic_enable_charging .text+0x1418` 从 buck ops `+0xf0` 进入 `0x2003` 的 1-byte 写请求。该 property 与 FCC `0x2005`、ICL `0x2008`、INPUT_SUSPEND `0x2019` 分离，不能把限流或停止输入称为同一个动作。[代理与注册](DADA_RECONNECT_PROVIDER_RESEARCH.md)。

ADSP setter `0x60031450` 的原始 41 项 dispatch table `0x6016d5ac` 令 `0x2003` 进入 `0x600314c4`。主调用在 `0x600314d4` 到 `PmSchgChgr_SetEnableCharging 0x60075bb0`；初始化选择 runtime 表 `0x602af5c4 + index*0x24` 的 ops `+4`。已定位选择如下：[本轮原始表](../.tooling/bypass-check/final/power/remote-tables.json)、[初始化独立核对](../.tooling/bypass-check/reconnect-control/remote-provider-init/independent-init-audit.json)。

| 描述模型值 | 已绑定主实现 | 请求地址/掩码 | transport 参数 |
| --- | --- | --- | --- |
| `0x20/0x2e/0x29` | `0x6008ca80` | `0x1042 / 0x01` | bus 0；slave 来自 runtime entry 的 `+1` |
| `0x32/0x47/0x56/0x4c/0x59` | `0x6008e330` | `0x2646 / 0x01` | 同上 |

这证明程序请求修改主位，没有证明本机取哪一行、对应哪一颗芯片，或该位关闭后输入转换器仍供 SYS。地址属于此次镜像的参数，不是跨版本通用接口。[新模型窗口](../.tooling/bypass-check/final/power/hexagon/main-enable-model-new.txt)、[旧模型窗口](../.tooling/bypass-check/final/power/hexagon/main-enable-model-old.txt)。

主调用读取 payload byte，附带分支判断读取 payload32。受审官方调用链的输入和填充已有静态闭合：`platform_class_buckchg_ops_set_chg .text+0xe20` 对参数执行 `&1`，传递值只为 0/1；这是取最低位，输入 2 会变为 0。`qcom_subpmic_enable_charging` 将传入 byte 存入请求，并在 `+0x145c` 指定 len=1。`mca_adsp_glink_write_prop .text+0x4..0x1dc` 先将 `sp+0x18..sp+0x117` 的 256 字节 payload 清零，通过参数检查后 `memcpy` 合法 len，再以固定总长 `0x114` 发送。因此该 class→proxy→GLINK 官方链上的首个 payload word 为零扩展的 0/1，高三个字节确定为零；不能把“填充未知”列为它的独立准入缺口。[修订原始证据](../.tooling/bypass-check/final/power/payload-contract-revision/evidence.json)。

这项保证限定于上述官方调用链，不扩展到绕过 class 的其他 caller 或自行构造的裸 RPC，也不能推出任意非零 payload 的所有分支等价。`0x2003` getter 读取同一主位的 ops `+0x34`；它没有读回下面所有附带状态。`charge_interface/charge_enable` show 读策略有效 voter/result，更不是这个硬件 getter。[getter 与 payload](DADA_RECONNECT_PROVIDER_RESEARCH.md)、[show](DADA_POWER_MEASUREMENT_STAGE1.md)。本节按独立审查收窄协议措辞；运行时型号/通路、附带动作恢复与有效测量的 NO-GO 保持不变，前版原字节和修订范围见 [revision](../.tooling/bypass-check/final/power/payload-contract-revision/revision.json)。

## 4. 五类附带操作与恢复不对称

以下是已选实现的程序参数。外层 MCA 模型、`ctx+0xbc` 对象模型和 `ctx+0xc0` 对象模型分别来自不同对象，不能自动视为同一值。所有字段名称及电气效果以“已证明”栏为限。[原始报告](DADA_NEXT_SIDE_EFFECT_RESEARCH.md)、[本轮重新解码](../.tooling/bypass-check/final/power/evidence-index.json)。

| 槽/实现 | 非零分支 | 零分支 | 已证明的地址与条件 | 恢复缺口 |
| --- | --- | --- | --- | --- |
| `bc +0xd4` / `0x6013acb0` | `(3,5000)` | 无对应调用 | 业务已绑定 FSW；数据编码 0 写 `0x2742`。对象 `0x4c/0x59` mask 3，其他适用分支 mask 7 | 没有保存/恢复旧 FSW；缺频率编码表和参数单位，0 不代表 0 Hz |
| `c0 +0x90` / `0x60137590` | `0x30` | 无对应调用 | 支持对象写 `0x28d0=0xa5` 后 `0x2842/mask0x70`；`0x56` 通道 2 还写 `0x24d0/0x2442`；通道 1 只写 `0x24d0=0xa5` | 字段编码 3 与前置全字节写的含义未知；没有恢复 |
| `bc +0xfc` / `0x6013bc60` | `(0,1)` | `(1,0)` | 对象模型 `>0x46` 时写 `0x2c42.bit1/bit0`；两个底层错误在局部 OR | 固定互换不等于恢复旧值；字段名/极性/联动未知 |
| `bc +0x100` / `0x6013bd10` | `1` | `0` | 仅对象 `0x47` 写 `0x2c43.bit0`，其他无目标写 | 缺字段定义和旧值恢复 |
| `c0 +0x60` / `0x60136710` | 外层模型 `0x4c` 为 4，其余 5 | 固定 4 | 对象 `0x56` 且 `+0x18=1` 写 `0x2341/mask7`；其余写 `0x2641/mask7` | 编码 4/5 与允许转换未知；固定 4 不是已证明的原状态 |

`c0 +0x90` 支持对象 `0x32/0x47/0x4c/0x59`，以及 `0x56` 的通道 0/1/2；其他返回 `0x201`。`0xd0=0xa5` 只能称前置写入，不能无匹配 schema 就叫“解锁”。附带位也不能猜命名为 BATFET、SMB enable 或 battery disconnect。

外层在 `0x600314d8` 保存主 rc，最终 `0x600322b8` 仍返回主 rc。它不汇总五类附带 rc，主失败也不阻止后续附带调用。因此可能发生部分状态变化，成功 ACK 不证明完整达成；失败 ACK 也不证明什么都没变。[主分支](../.tooling/bypass-check/final/power/hexagon/charge-enable-dispatch.txt)、[返回](../.tooling/bypass-check/final/power/hexagon/charge-enable-return.txt)。

## 5. 新完成的下游边界

### 5.1 SPMI 请求链与运行时服务

`0x6005b330` 的非全字节 mask 路径先调用 `0x6005ae10` 读 1 byte，读失败不继续写；成功时保留未掩码位，再调用 `0x6005b3f0` 写 1 byte。mask `0xff` 时跳过读取。这是软件 RMW，不是已经证明为硬件原子操作。[RMW/写](../.tooling/bypass-check/final/power/hexagon/spmi-rmw-and-write.txt)、[读](../.tooling/bypass-check/final/power/hexagon/spmi-read-and-masked-read.txt)。

多字节读 `0x6005ac90` 与写 `0x6005b3f0` 读取 BSS handle `0x6020d05c` 和 priority `0x6020d061`，分别经 `0x60123204` / `0x6012323c` 发出 operation `0x100` / `0x101`。[服务 wrapper](../.tooling/bypass-check/final/power/hexagon/spmi-service-wrappers.txt)。

本轮把 `0x6000b524` 追完至 `0x6000b5b0`：它有条件执行 `trap1`，也能按 runtime 服务表查找目标并 `jumpr`；查找失败路径返回 `-1`。故“始终直接 syscall 到物理总线”过强。当前准确终点是**对运行时服务的 bus 请求**，没有取得所选服务实现、实际总线应答和器件字段定义。[完整分派窗口](../.tooling/bypass-check/final/power/hexagon/service-table-or-trap.txt)。

### 5.2 附带 masked writer 的三个选择器

`0x6002d7e0` 读取对象 `+1` 的传输选择器；null 返回 `0x202`，未知/选择器 3 返回 `0x200`。静态分支可达不代表本机选中该分支。[通用 writer](../.tooling/bypass-check/final/power/hexagon/generic-masked-selector.txt)。

| selector | 已闭合的请求链 | 错误与副作用边界 |
| --- | --- | --- |
| 0 | 对象 `+4/+5` 作为 bus/slave，经 `0x6002da10` 到上述 SPMI RMW | 仍缺 runtime bus/slave/service 和匹配 schema |
| 1 | 对象 `+0xc/+0x10`，地址经 `swiz` 与右移 16，`0x6005fd40` 到 `0x6005efc0`；读 `0x6005f1d0`、写 `0x6005f680`，最终通过 `0x60114920` 的 operation `0x107` 到同类 runtime 分派 | RMW wrapper 保存 read rc 后没有判失败，仍组合数据并发 write，最终 rc 被 write rc 覆盖。不能视为有原值保障的恢复函数；不凭 selector 编号命名物理总线 |
| 2 | `0x6002da60` 先 `0x6002d0b0` 取 byte，成功后组合并 `0x6002d560` 写 | 所谓取 byte 实际先写 `0x7e4b/0x7e4c/0x7e4d`、`0x2142=0x10`，再轮询并从 `0x7e4e` 取结果。写路径还设置数据 `0x7e4e`；两者经 SPMI read/write wrapper。缺该间接访问协议 schema，不能称纯读或自行给它物理名称 |

selector 1 的锁/传输 helper 在读、写分别进入；wrapper 未提供完整事务失败恢复。selector 2 的读失败会让它自己的 masked writer 退出，但该读取过程此前已经有请求写入，退出不代表无副作用。[selector 1 RMW](../.tooling/bypass-check/final/power/hexagon/selector1-rmw.txt)、[selector 1 read](../.tooling/bypass-check/final/power/hexagon/selector1-read.txt)、[selector 1 write](../.tooling/bypass-check/final/power/hexagon/selector1-write.txt)、[selector 2](../.tooling/bypass-check/final/power/hexagon/selector2-read-with-writes.txt)。

## 6. 精确 PMIC 修订：公开定义能补到哪里

官方 ADSP DT 有 `pm8750bh@7`、generic `qcom,spmi-pmic` 与 SID 7；公开 MiCode DT 有 `PMIH010X` ADC 定义。这些是配置/名称证据，未给出二者别名，也未给出对象枚举、charger 外设修订或物理走线。[原定义调查](DADA_PMIC_DEFINITIONS_STAGE2.md)、[DT 与测量](DADA_POWER_MEASUREMENT_STAGE1.md)。

本轮新增核对两个固定 primary 源码：MiCode commit `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5` 与 Linux stable commit `c719455843a8bc3e969ba58ec92335ed7510e9fc` 的 `qcom-spmi-pmic.c` 和其实际包含的头文件。下载保留 HTTP 状态、headers、失败记录与 SHA-256。源码确认通用 chip revision loader 的定义如下，**不是本手机的测得数值或匹配的 charger schema**。

| 原始地址 | 源码字段 | 解释边界 |
| --- | --- | --- |
| `0x104` | TYPE，应为 `0x51` 才继续该 loader | 无本机读值 |
| `0x105` | SUBTYPE | 固定头文件没有 `PM8750BH/PMIH010X`，也没有把 ADSP `0x47/0x4c/0x56/0x59` 映射到芯片名 |
| `0x101` | REV2 | loader 保存 `rev2`；部分旧 subtype 有特殊 minor 解释 |
| `0x102` | REV3 / minor | 原始值与规范化值需分开保存 |
| `0x103` | REV4 / major | 部分旧 subtype 会加 1，不能只采格式化 `vM.m` |

该 loader 还依 base USID / `num_usids` 选择基础 PMIC，不能从任意功能 SID 推断 chip revision。芯片 subtype 与 SCHG/DCDC/Misc/QBG **外设 type/subtype/revision 是不同层**；上述 chip 表不提供 `0x2646/0x2742/0x2c42` 等外设字段 schema。`drivers/soc/qcom/qpnp-revid.c` 在所查 MiCode 固定路径返回 404，未计入定义证据；没有把该结果概括为全仓库或全部公开资料不存在。

primary 链接：[MiCode loader](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/mfd/qcom-spmi-pmic.c)、[MiCode subtype 头文件](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/include/soc/qcom/qcom-spmi-pmic.h)、[Linux loader](https://github.com/gregkh/linux/blob/c719455843a8bc3e969ba58ec92335ed7510e9fc/drivers/mfd/qcom-spmi-pmic.c)。本轮实际下载见 [源码目录](../.tooling/bypass-check/final/power/public/escalated-acquisition.json) 与 [头文件记录](../.tooling/bypass-check/final/power/public/headers-escalated-acquisition.json)。

## 7. SYS 输入与电池支路分离所需的完整条件

| 必须补齐的材料 | 必须回答的具体问题 | 当前状态 |
| --- | --- | --- |
| 与本机 ADSP 构建对应的型号枚举、版本结构、device/comm 初始化 | 对象 `+0/+1/+0x18`、main index、slave、SID、channel、ops 和实际器件/修订如何对应；当前运行代码与配置是否匹配 | 未闭合 |
| 匹配 chip 与 SCHG/DCDC/Misc revision 的寄存器/HAL schema 与勘误 | 主 enable 的 SYS/BAT 效果；全部附带字段的名称、极性、编码、读写副作用、默认/恢复与顺序要求 | 未取得 |
| 板级充电/供电拓扑或同等可验证资料 | USB→buck/CP→SYS/VPH 与电池的连接；并行路径、反向路径、保护及补电路径；哪些控制能停充且保留输入 | 未取得 |
| 完整可靠的控制/撤销业务入口与状态定义 | 从合法高层入口到所有写入；部分失败如何恢复；插件 owner 功能 inactive，保留其他 OEM 动态票；崩溃、掉线、重启、策略竞争时如何撤销插件控制 | 未闭合 |
| 运行时有效测量与校准 | 测点、单位、方向、双路合并、更新年龄、错误/缓存/固定值标志；输入功率与电池双向净流的同步证据 | 未满足准入 |

准确字段缺口包括主 `0x1042.bit0/0x2646.bit0`；FSW `0x2742[1:0]/[2:0]`；`0x2c42.bit1/bit0`、`0x2c43.bit0`；`0x2641/0x2341[2:0]` 编码 4/5；`0x2842/0x2442[6:4]` 编码 3 与 `0x28d0/0x24d0=0xa5`；此外 selector 2 的间接访问协议与 QBG/ADC 采样 schema 同样需匹配。

## 8. 已排除的替代证明与最终可交付边界

- 旧 SMB5、其他手机模块和 TI `BQ25890` 的 power-path 文档可说明“关闭充电而保留 SYS”在某些芯片上成立；其寄存器表不能移植到本机未知修订。[对比材料](DADA_POWER_PATH_EVIDENCE.md)。
- `SC8581/SC8585` 的 forward 1:1 / bypass 模式说明转换模式，不证明电池隔离或主充电支路关闭；`HL7603` 已定位的 battery-side boost 也不是该控制后端。
- FCC 限流、SOC 上限、INPUT_SUSPEND、online、软件 `charge_enable`、成功 ACK、某一个零电流值，均不能独立证明旁路有效。
- 内核 debugfs/regmap、原始 SPMI/I2C、内存写、BSS 指针推测、构造未知 binder/RPC，以及先关再猜一个“恢复值”，均未形成可实施方案。

本次交付是固定证据的最终控制契约、必要测量链路审计和有条件的完整验证计划。已完成离线可行性收尾；物理旁路、完整恢复与手机 A/B 验证仍为未实现/未执行，NO-GO 条件具体且可复核。未经这些条件闭合，不把已分析地址包装为 SukiSU 可写后端，也不对现有插件增加“已验证旁路”声明。

本轮 raw 窗口、解码、原始表、公开源码、报告及输入身份由 [manifest](../.tooling/bypass-check/final/power/manifest.json) 冻结；[verification](../.tooling/bypass-check/final/power/verification.json) 检查字节/指令与来源、已有材料保留和报告链接。所有手机操作计数为 0。
