# Xiaomi 15 / dada：后续调查与实施决策

日期：2026-10-13。本文接续[重新连接后的主审报告](DADA_RECONNECT_RESEARCH_REVIEW.md)，更新恢复、附带操作和遥测三项调查。本轮所有分析均在电脑上完成，手机命令为 0，没有控制写入、安装、采样或实机 A/B；插件仍为既有限流实现，未增加旁路后端。

**应继续调查，但下一项决策是能否取得匹配源码和硬件定义。现有 `charge_enable` 不满足插件请求的可靠撤销和全部附带动作的恢复要求，不接入自动控制。** 本轮已将缺口定位到具体 API、字段和测量链，不再以尝试节点数值作为调查方法。这不等于证明手机硬件没有旁路能力。

## 新证据改变了哪些判断

| 项目 | 已核实的结果 | 对插件的影响 |
| --- | --- | --- |
| 独立请求恢复 | 内核 `mca_vote` 支持 enabled=0；已定位 buck/quick 用户入口均固定 enabled=1。`mca_rerun_election` 只重放当前 force/override/缓存/default 结果，不撤票，也不重新聚合所有 client | 写允许值不能替代撤销插件约束；root 不会自动把内核 export 变成 shell 接口 |
| 原状态查询 | 多个按 owner 查询 API 在名字不存在时可能分配槽位；AND 类型的 inactive 查询不返回原始存储 value | 不能简单拼接现有 getter 宣称无副作用快照。既有 debugfs status 的逐字段只读结论不变 |
| 充电开关附带动作 | `0x2003` 启用分支的 `+0xd4` 与 `0x2004 / BUCK_SET_FSW` 共用 wrapper，并有同固件 FSW 日志。参数 `(3,5000)` 在已定位实现中写 `0x2742` 编码 0，掩码依模型为 3 或 7 | 已确认它还涉及开关频率设置业务；单位、实际频率和运行时模型未证明。其他附带字段仍缺准确含义，整体返回不汇总其错误，启用/禁用不构成完整 restore |
| 名为 VSYS 的 getter | `0x200c` 实际调用 ADC channel 0；文件名称表对应 `VPH_PWR`，独立的 `VSYS` 位于 channel 2 | 修正早期仅凭 `BUCK_VSYSMIN` 名称的解释：不是已经证明的配置值 getter，也不是已验证的 SYS 测点 |
| 输入和电池电流 | USB 电流需匹配已注册 ADC 通道；无匹配静态路径可返回 0 而未输出通道。PACK_IBAT 使用模型选择的硬件数据读取，并有校准、合并及置零 flag | rc=0、读数 0、online=1 都不能单独证明外部供 SYS 或电池停充；三项新 getter 不加入手机读取白名单 |

请求恢复要区分两个契约：**功能恢复**是让固定插件 owner 不再参与选举；**严格状态恢复**还要求还原原始 absent/inactive/active、存储值和槽位。内核 enabled=0 有助于前者，但会保留名字；现有用户入口连这一参数也没有暴露。两者还都需要硬件和远端附带状态的确认，不能以软件缓存恢复替代。

三个专项报告：[独立请求的恢复边界](DADA_NEXT_RESTORE_RESEARCH.md)、[附带操作与字段缺口](DADA_NEXT_SIDE_EFFECT_RESEARCH.md)、[遥测完整链及有效性边界](DADA_NEXT_TELEMETRY_RESEARCH.md)。

## 官方源码核查的实际结果

本轮通过小米官方仓库的精确 Git refs 和固定提交原始文件复核，不使用搜索摘要替代源码。API 的 README 查询遇到 HTTP 403 限流后，改用同一官方仓库的只读 Git 元数据；没有访问手机。

- [官方索引固定提交](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/16d89a630ea8752c84bf4d76201964524d620616/README.md) 将 Xiaomi 15 / 15 Pro 列为 `dada-v-oss / Android V`。
- `dada-v-oss` 当前指向 `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5`；该提交树中 `drivers/power/supply/mca` 为 mode `120000`。其 [55 字节链接内容](https://raw.githubusercontent.com/MiCode/Xiaomi_Kernel_OpenSource/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca) 是 `../../../../../vendor/xiaomi/proprietary/mca/driver/mca`，Git blob SHA-1 经原始内容验证为 `c15342cc61dbdbca5498daaa1ebb32fdcd984684`。这个入口没有交付 MCA 实际源码。
- 精确查询的 `refs/heads/dada-w-oss` 没有返回。这个负结果仅限本次官方索引及准确分支名，不表示其他仓库、名称或后续发布绝对不存在匹配源码。

本机此前采集的是 Android 16、6.6.77 系列内核；公开 Android V 入口及链接本身不能充当当前构建的 MCA 源码、ABI 或 PMIC 定义。Linux live/disk build-id 对应，也不能证明 ADSP 运行镜像身份或让不匹配的内核扩展可加载。

官方核查原始响应、URL、提交和散列见 [official-source-check.json](../.tooling/bypass-check/root-next/official-source-check.json)。

## 下一阶段的明确终点

1. **匹配源码与恢复入口。** 获取当前 MCA 实际源码、头文件、配置、工具链、Module.symvers/ABI 及加载策略资料；或找到厂商已提供的等价用户入口，直接证明它支持无分配快照、enabled=0 和真实执行结果。资料齐备后才评估固定 owner 的受限 snapshot/apply/restore；现阶段不编写或加载靠猜结构、地址的扩展。若这条资料路线不可取得，保持该设备的诊断能力，不把现有开关包装为自动旁路。
2. **附带字段和运行时选型。** 需要精确匹配 PMIC 修订的 `0x2742`、`0x2c42/0x2c43`、`0x2641/0x2341`、`0x2842/0x2442` 及前置 `0xd0` 的定义，明确编码、极性、允许转换与恢复要求；再结合实际对象模型、通道和配置选择。相似 SMB5 偏移、字符串邻接或 converter 1:1 模式不能补足这些定义。
3. **测量与验证方案。** 先闭合拟用 sysfs show 的错误处理、ADSP 身份、ADC/PMQBG provider、测点、单位、符号、采样年龄、校准和读副作用；排除 USB 无匹配路径与电流置零 flag 的误判。控制与恢复也通过后，才形成固定接口、原状态、失败退出和准确恢复均明确的一次性实机对比方案。验收应同时确认电池停充、外部输入承担系统负载以及退出后恢复，不能只看充电功率下降。

以上是具体资料缺口和工程决策点，不是继续无限扫描或默认授权硬件试写。当前没有满足这些条件的实机测试方案，也没有新的充电功率或电池电流数据。

## 主代理核验记录

主代理逐项核验 54 份不同文件的散列与适用的大小字段，直接从固件文件解析并对照全部 58 个 ADC 名称、3 张 PMQBG ops 表的电流槽、89 个附带操作跳转项及关键 property 表项；4082 行专项报告使用的 Hexagon 指令与已验证 v79 完整反汇编一致。另外独立重解了 6 个恢复/ADC 窗口和 9 个附带操作/电流窗口，人工复核查询分配、缓存重放、FSW 共用业务、模型/通道分支和 USB 无匹配条件。

可复核产物：[恢复与 ADC 重解](../.tooling/bypass-check/root-next/local-call-review.json)、[本轮原始字节及证据核验](../.tooling/bypass-check/root-next/root-next-evidence-verification.json)。这些 PASS 是离线材料核验，不是插件效果、运行时对象或旁路供电的 PASS。
