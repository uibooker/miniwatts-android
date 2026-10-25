# Xiaomi 15 / dada：第一阶段实施决策与主审

日期：2026-10-13。执行 [DADA_IMPLEMENTATION_NEXT_STEPS.md](DADA_IMPLEMENTATION_NEXT_STEPS.md) 的第一阶段，三个子代理分别核对原生接口、匹配源码/构建、供电/测量；主代理取得发布包、核对工件身份并汇总决策。之后复用两个子代理独立审查取得过程与 HAL 关键链。

**本轮取得了配套发布包中的原厂 MiCharge HAL，并把所查方法追到真实写入实现；这些入口仍不能组成可安全申请、查询和释放的旁路后端。自建受限接口所需的匹配 MCA 源码和硬件资料也未补齐。** 因而完成第一阶段的资料与可实施性判断，停在具体材料缺口；不进入驱动实现或实机试验。该结论限定于已分析材料，不能推断硬件永远不支持旁路，或全固件绝对没有其他可用入口。

本轮 ADB、手机读取、写入、安装、驱动加载、寄存器操作和实机对比均为 **0**。没有修改插件。现有插件仍按 FCC 限流能力评价，物理旁路未验证。

## 与上一轮相比，实际补齐了什么

| 项目 | 本轮新增的具体证据 | 对实施的影响 |
| --- | --- | --- |
| 厂商用户态实现 | 从 OS3.0.303.0.WOCCNXM 发布包取得 service、AIDL v2 NDK 库、rc/VINTF 和 vendor build.prop | 不再仅凭 HIDL 可选声明或未知来源的第三方样本判断方法行为 |
| 配套工件身份 | 包内 20 份 MCA/sc8581 模块、25 份选定 ADSP 文件与先前电脑工件逐字节相同 | 支持在这套配套磁盘材料上关联 HAL、MCA 和 ADSP；不证明当前 HAL 文件或 ADSP 运行身份 |
| 原生通用充电方法 | `setChargeCommonInfo` / `getChargeCommonInfo` 在包内服务是日志加 Binder OK 的空实现；NDK setter 出参预置为 0 | 客户端成功或数值 0 不能证明该方法控制了硬件；它没有提供插件撤票能力 |
| 原生路径控制 | `setMiChargePath` 的固定映射中 `charge_enable` 指向 `charge_interface/input_suspend`；`setInputSuspendState` 也使用这条路径 | 键名不能当作电池专用停充证据。已查路径没有增加充电 voter 的 `enabled=0` 撤销参数 |
| 写入及超时 | 底层 writer 未检查短写和 close 结果；路径方法在等待前 detach worker，超时返回后写入仍可能继续 | 不能把超时当作“没有执行”，也没有取消、事务性恢复或远端硬件确认 |
| 匹配驱动资料 | MiCode dada 发布根版本 6.6.30，MCA 仍为指向 proprietary 目录的链接；同6.6.77 Google基线可取，但未对应运行 hash | 完整 MCA 源码/头文件/生命周期和当前构建输出仍缺；root或相同版本号不能替代这些条件 |
| 软件开关显示 | `charge_enable` 的 buck show 只读取 voter 的 effective client/result，不调用硬件 getter | 不能据此确认远端充电开关或保留系统输入 |
| USB 电流显示 | 回调预置输出零，调用 getter 后丢弃错误、强制成功 | 存在失败显示零的静态路径；零值不能单独用于旁路验收，没有在手机触发这个路径 |

原生方法的 transaction、vtable、初始化键表和 writer 证据见 [原生接口报告](DADA_NATIVE_INTERFACE_STAGE1.md)，其关键映射与失败语义见 [独立接口审查](DADA_NATIVE_INTERFACE_REVIEW.md)。完整取得和版本边界见 [HAL 取得记录](DADA_FIRMWARE_HAL_ACQUISITION.md)及 [取得独立审查](DADA_FIRMWARE_ACQUISITION_REVIEW.md)。

## 原生路线的明确范围

NDK 库提供准确方法符号，已建立全部 56 项 transaction 与原厂 service 方法位置的对应。深查集中于输入暂停、通用路径和充电 common getter/setter；不能把其他方法未检查、名字不含 release 或固定键表的未解项当作全局不存在证明。固定表的其余字段也没有自动获得可试写授权。

实际原厂服务与 MiChargeShim 公共样本 Build ID 不同；替代实现的返回值、限充策略和方法说明没有代替本包行为。所查 setter 存在空实现、输入暂停映射和异步执行，均不满足第一阶段要求的固定插件 owner、无分配查询、准确释放、错误传播和硬件恢复确认。

固定表另有 `handle_state` / `handle_stop_charging`、`smart_chg` / `smart_batt` 等有名线索。前者对应既有 [控制链报告](DADA_CHARGE_CONTROL_CHAIN.md) 中同时控制 input_limit 与 chg_enable 的 handle 事件；后者对应原生 SOC 保护或策略，不是已经证明的电池专用旁路。不能借用其他原生 owner，或把解除一个混合输入策略当作插件独立请求的恢复。

上述类别对照不代表本轮已核实运行节点绑定：HAL 硬编码 `/sys/class/xm_power/...` 路径，旧手机清单主要记录 platform 路径。本轮没有核对当前 class alias、最终驱动对象及现场 HAL 身份，不能仅凭节点名相同就合并两条路径。这项运行身份缺口和 PMIC/ADSP 的运行身份缺口分别保留。

## 两条实施路线的条件判断

| 必要条件 | 原生入口路线 | 修改匹配驱动路线 |
| --- | --- | --- |
| 控制对象与参数实际定义 | 所查方法及路径已取得；未得到完整旁路契约 | 未取得 MCA 完整头文件、对象引用与生命周期协议 |
| 固定插件 owner，无分配查询 | 未找到所查入口提供此能力的证据 | 需要在准确类型/锁下实现；已有按 owner getter 可能分配槽位 |
| 释放自身参与状态 | 所查方法未补 `enabled=0` 充电撤票入口 | 内核局部机制存在；未具备安全加入受限接口的资料 |
| 部分执行/超时后的恢复 | 所查路径没有取消或硬件恢复确认 | 票先更新、回调失败不回滚，需匹配源码修正并定义恢复 |
| 当前 ABI 与加载条件 | 不采用猜 transaction 的客户端调用 | 缺当前完整 config、build manifest、toolchain 参数、Module.symvers 和实际加载策略 |
| 停充后仍由输入承担系统负载 | 未证明 | 未取得 PMIC 修订、附带字段和板级 SYS/电池/CP 条件 |
| 有效的物理测量 | 本轮两个候选显示不满足验收 | 同样需要 provider/channel/校准/时效和读副作用审查 |

功能恢复仍采用第一版既定契约：固定插件 owner 不参与聚合，允许一个 inactive 槽位残留，不把 remove-client 作为额外前提。但 native 重新聚合、真实硬件状态及远端附带动作的恢复也必须确认；写 active allow=1 仍不等于撤票。

## 使下一步能够继续的具体材料

1. **匹配 MCA 实现：** 与本包已确认磁盘模块对应的 `vendor/xiaomi/proprietary/mca/driver/mca` 实际 C 源码、完整头文件、Kconfig/Makefile、无分配查询及对象引用/锁/解绑生命周期定义。只有 symlink、部分 BTF 或导出 CRC 不够。[构建资料报告](DADA_BUILD_SOURCE_STAGE1.md)
2. **实际内核构建输出：** 运行 `f9a1d4bd8353` 构建的 source/config/toolchain/build manifest 与完整符号输出，以及 vendor MCA 的对应输出和实际加载策略。Google 同6.6.77基线能解释部分规则，不能充当当前构建身份；现有20模块匹配不构成新扩展加载承诺。
3. **PMIC/板级定义：** `PM8750BH ↔ PMIH010X ↔ ADSP PmicModel` 对照、实际模型/通道/SlaveID 和 silicon/peripheral revision；对应 `0x2742`、`0x2c42/0x2c43`、`0x2641/0x2341`、`0x2842/0x2442` 等字段的极性、枚举、附带转换及恢复，或可提供等价契约的匹配厂商 HAL/板级资料。[供电及测量报告](DADA_POWER_MEASUREMENT_STAGE1.md)
4. **有效查询和验收资料：** 保留 errno/validity 的硬件状态查询、ADC/PMQBG 实际 provider 与 channel 绑定、单位/符号/测点/校准/采样年龄和读副作用。当前软件 voter 与可能伪成功的零电流不能构成对比验收。

若能取得另一条完整的厂商入口，也可替代自建驱动路线；它必须证明实际申请、查询、释放和附带状态恢复，而不是仅增加一个名为 reset/disable 的方法。上述资料未取得时，继续增加 shell 包装或发布新 ZIP 不会补齐控制能力。

## 主审与验证边界

主代理复读构建、供电和原生接口最终三份 manifest 的 427 项文件记录，大小及 SHA-256 全部一致；这是记录数，包含重复来源，不能称为427个独立工件。取得审查独立解析 ZIP/payload，验证 vendor 379 个操作、modem 95 个操作、目标范围及整分区 SHA；独立比较20驱动和25 ADSP文件。原生接口另行由第二代理重解关键 dispatch、键表初始化和写入窗口，45 项离线检查通过。

证据索引在 `.tooling/bypass-check/stage1/`。这些检查证明电脑材料和推导的可复核性，不是运行时加载、插件效果或旁路供电的 PASS。没有新硬件读白名单，没有合格的实机控制方案，也没有让用户尝试未审查的开关命令。
