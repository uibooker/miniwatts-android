# Dada 重连后的撤票入口与 voter 状态复核

日期：2026-10-13。本子任务没有调用 ADB、没有读写手机接口、没有修改插件。只处理主任务已采集的本地文件；分析工件写在 `.tooling/bypass-check/reconnect-control/`。

## 当前结论

对现有 **20 份完整 ELF** 的 symbol、relocation、sysfs/debugfs store 和相关 voter 后端重新检查后，仍未找到可将 **插件自己的 buck/quick charge_enable 客户端设为 enabled=0** 的用户空间入口。内核 `mca_vote(..., enabled=0, ...)` 能停用票，但用户协议没有暴露这一参数。本结论是已查范围的结果，不是整个系统绝无此入口的证明。

另一个重要结论：AND/OR voter 的 `effective=<name>` 不能单独用来认定谁导致停充。该 ELF 的聚合分支将有效客户端标记为当前投票调用者，可能与遍历发现的阻断票不是同一客户端。需要逐行检查所有 `en=1` 的票、聚合类型和 override/force，而不是按 effective 名称归因。即使完整软件票解释得通，也不能证明真实外部供系统、电池隔离或硬件命令成功。

本轮重连采集确认当前没有可读的 voter status 快照：挂载表无 debugfs，`business-votable` 路径缺失；主任务没有为获取状态挂载它。不能据此解释当前电流或归因停充。

## 检查范围及可复现工件

已检查：mca_adsp_glink、mca_bmd、mca_business_battery_comp、mca_business_charger_comp、mca_business_misc_comp、mca_charge_interface、mca_common、mca_path_control、mca_platform_base、mca_platform_buckchg_class、mca_platform_cp_class、mca_platform_loadsw_class、mca_qcom_subpmic_proxy、mca_qcom_sysfs、mca_smart_charge、mca_soc_limit、mca_strategy_buckchg、mca_strategy_quickchg、mca_sysfs、sc8581。

- [manifest.json](../.tooling/bypass-check/reconnect-control/manifest.json)：20 个 ELF 原始来源与完整 SHA-256。
- [interface-overview.json](../.tooling/bypass-check/reconnect-control/interface-overview.json)：store/force/vote 函数和直接 voter 调用偏移。
- [attribute-tables.json](../.tooling/bypass-check/reconnect-control/attribute-tables.json)：属性表 symbol 范围与字符串重定位。
- 每个模块的 `.elf.json`、`.disasm.txt`、`.annotated.txt`；关键 store/status 另保存为 `.selected.txt`。
- 前一轮 [控制链报告](DADA_CHARGE_CONTROL_CHAIN.md) 和 [provider 报告](DADA_CHARGER_PROVIDER_RESEARCH.md) 提供协议、末端 GLINK ID 和已有排除证据。

位置均是 `.text` 节偏移，不是运行内存地址。BTF 未解析的 base 字符串不用于命名推断。本子任务独立对比主任务采集的八个已加载模块 GNU build-id note（charge_interface/common/buck策略/quick策略/buckclass/proxy/ADSP/sc8581），均与此前磁盘 ELF note 字节相同，见 [build-id-review.json](../.tooling/bypass-check/reconnect-control/build-id-review.json)。这加强了对应同一声明构建身份的证据，仍不是运行内存逐字节相等或未被运行时修改的证明。新采集 hl7603/qti_pmic_glink 的两项由 provider/主任务另行分析，不纳入这里的 20 ELF 接口范围。部分 OEM 模块/远端固件未取得，不能把有限样本负向搜索扩大为整机不存在。

## 是否能从其他属性撤同一张票

| 已检查入口 | 直接证据 | 对独立 owner 撤 charge_enable 的结果 |
| --- | --- | --- |
| charge_interface charge_enable/buck | setter `0x5ddc`，`0x5e4c..0x5e58`：`mca_vote(chg_enable+0xd0,user,1,bool)` | 0 与 1 都是 active 票；没有 disabled 分支。 |
| charge_interface input_suspend/buck | setter `0x5c50`：vote 对 `input_suspend+0xd8`，enabled 固定 1 | 不是同一 voter；无法借其恢复 charge_enable owner。 |
| charge_interface ichg_limit/buck | setter `0x60ac`，`0x610c..0x6124`：value=0 时 enabled=0，但目标是 `charge_limit+0xf0` | 确实可撤 **电流限值** 票，却不能跨 voter 撤 chg_enable；0 不是停充 0mA。 |
| charge_interface iin_limit/buck | setter `strategy_buckchg_if_set_input_cur+0x5ef8`，目标 `input_limit+0xe8` | 输入限流不是充电使能，不能用同名 user 跨 voter 撤票。 |
| charge_interface charge_enable/quick | setter `0xa6ec`：目标 `chg_en+0x210`，enabled 固定 1 | 没有 disabled 分支。 |
| charge_interface input_suspend/quick | setter `0xa594`：对同一 `chg_en+0x210` 投相反布尔值，enabled 固定 1 | 与 charge_enable 共用目标，但仍不表达 owner disabled。 |
| charge_interface ichg_limit/quick | setter `0xa7b4`，`0xa808` 解析 `%d#%d`，`0xa824/0xa838` 分别对 `+0x218/+0x220` 投 single/multi 电流票，两次 enabled 都固定 1 | 连 quick 电流票也没有 buck 式 value=0 撤票；不能当作 charge enable 恢复。 |
| common debugfs | `mca_create_votable+0xf08` 创建 status、force_val、force_active；完整函数表/export 无 generic userspace client-vote setter | force 不提供独立 owner disabled，且绕 OEM 聚合，不使用。 |
| smart_charge | store `0x314` 的直接投票客户端是固定 `smart_chg`；bit 功能及 SOC 的间接回调是 OEM 客户端 | 没有任意 owner 参数；复用/覆盖 OEM owner 被排除。 |
| charger handle/debug_ctrl | handle event60 可撤固定 `handle`；debug_ctrl 分发固定事件，某些分支写持久分区 | 系统所有权和额外作用；不是插件 owner 撤票。 |

同名 user 只在相应 voter 内寻址。例如 `charge_limit` 上的 user=X 与 `chg_enable` 上的 user=X 是不同槽位；不能以“其他节点能撤同名 user”推断恢复本票。负数也不是 charge_enable 撤票协议：顶层用 unsigned 解析且吞转换错误，非法值可能变成 0，无法表达 enabled=0。

### 本轮新增完整 store 排查

| 模块 / store | 属性及行为 | 为什么不构成入口 |
| --- | --- | --- |
| mca_strategy_quickchg / `0x51d4` | mode_enable、cp_path_enable、power_max、fake_pps_ptf、quick_charge_type、current_limit、current_ration、volt_dec、vfc_iout；mode 分支修改 div1/2/4 配置，可能走 `0x445c` 复杂 stop | 接收模式标记/数值，不接收任意 voter owner + enabled；副作用包含 CP/PD/override。 |
| mca_platform_cp_class / cp store `0x2168` | writable 分支是 ovpgate（`0x2204..0x2264`）及 fsw（`0x2294..0x22fc`） | 直接 provider gate/频率调用，无 voter client；不能撤充电使能票。 |
| mca_platform_cp_class / platform store `0x24e8` | 直接返回 0；表含 ibus_delta/ibus_total/cp_mode | 没有修改逻辑；写入返回 0 也不是控制成功。 |
| mca_platform_loadsw_class / `0x500` | ibat_limit、low_power 直接 provider ops `+0x10/+0x18` | 没有 owner、没有 mca_vote；不能用未知 load switch 语义代替撤票。 |
| business charger debugfs / `0x3018` | dam_test 十六进制整数，修改字段 `+0x448` 并调整 wake lock | 不是充电 voter；不试写。 |
| sc8581 debugfs / `0x230` | 芯片寄存器调试访问 | 原始硬件访问没有 owner 恢复协议，排除。 |
| mca_sysfs | 完整模块是属性/调试组创建、链接、lookup 包装；外调 sysfs_create_group/debugfs_create_file，没有 mca_vote | 包装其他模块提供的 callback，不提供另一 generic vote 协议。 |
| business_battery/BMD/qcom_sysfs | battery setter 的若干 property 空成功，BMD 仅 show，qcom store 为认证/输入 gate/无线/ship计数/thermal等 | 前一轮已闭环排除，没有新增独立 owner 入口。 |

不会以新增内核模块、内存写、任意内核函数调用、force/override、OEM owner 冒用来补缺口。以上是研究结论，不是试写建议。

## status 的准确含义

证据：[mca_common.selected.txt](../.tooling/bypass-check/reconnect-control/mca_common.selected.txt)，`mca_show_votable_clients+0x1348`；[完整反汇编](../.tooling/bypass-check/reconnect-control/mca_common.annotated.txt)。

### 每个客户端行

`<voter>: <client>: en=<n> v=<n>` 在 `0x13ac..0x13c8` 从 name 数组 `+0x1a8`、enabled `+0x50`、value `+0x54` 读取。只要客户端 name 被分配就打印，**en=0 也打印**。没有某行与某行 en=0 是不同原始状态；禁用不会释放 name，最多 32 槽。value 的单位/语义取决于 voter，不能都当作电流或布尔。

### effective 行

`effective=<client> type=<type> v=<value>` 在 `0x13d4..0x148c` 输出。优先顺序如下：

1. force active `+0x2c4` 非零：输出 `DEBUG_FORCE_CLIENT` 和 force value `+0x2c0`。
2. override effective `+0x160` 不等于 sentinel -22：输出 override client/value（名字位于结构 `+0x8`）。
3. 普通聚合：读 cached effective client `+0x158`、cached result `+0x15c`；无 client 时名字 `none`。result 是 sentinel -22 时回退 configured default `+0x2d0`。

AND/OR 的有效名字存在归因局限：`mca_vote+0x5f4` 开始时 w21 保存本次调用 user 的槽位；OR 分支 `0x964..0x970`、AND 分支 `0x9c0..0x9d0` 将 w22（effective client）赋成 w21，而遍历当前票采用另一索引。可能出现 A 已有 active=1/value=0 的 AND 票，B 的允许票触发重新选举，结果仍为 0 但 effective 名称为 B。不能只看名称就说 B 请求停充。MIN/MAX 走实际选中索引的另一分支，不能泛化同一归因问题。

type=AND 时任意 active 0 都能禁止；type=OR 时任意 active 非零都能触发；MIN/MAX 看全部 active 数值。inactive value 不参与普通选举，但仍显示。override/force 会覆盖普通计算，且原票列表继续存在。必须先识别 effective 行是否指向 force/override，不能仅用普通列表解释最终值。

status show 只软件字段读、mutex 和 seq_printf，没有 provider callback、寄存器读取或 read-to-clear。这个事实支持主任务审查过的既有 debugfs 状态读取，不授权挂载 debugfs 或追加任何硬件读取。

### 仍然不能证明硬件状态

`mca_vote` 在 `0x7d4/0x7d8` 写入 client enabled/value，在 `0xa18/0xa1c` 保存 aggregate，之后 `0xbf0` 才调用硬件 callback；失败不回滚。buck setter 又在 `0x5e5c` 无条件返回 0，顶层 sysfs 再吞错误。相同票可能被 `0x790..0x7c0` 跳过。故 status 是软件请求/聚合缓存，不是“命令已经在 PMIC 生效”的证明，也不构成硬件恢复确认。

## 重连现场数据分析

本轮已取得主任务的 [voter-directory.txt](../.tooling/bypass-check/reconnect-acquisition/voter-directory.txt)，原文为 `business-votable absent`，stderr 空；[mounts.txt](../.tooling/bypass-check/reconnect-acquisition/mounts.txt) 含 sysfs 与 tracefs 挂载，但没有 debugfs。主任务未挂载 debugfs，故没有任何本轮 status 快照可以解析。读取已挂载 debugfs status 的 show 审查通过，不等于授权改变挂载状态。

可审计来源是 [metadata-manifest.json](../.tooling/bypass-check/reconnect-acquisition/metadata-manifest.json)：mounts 工件 SHA-256 `5c63c21600a9d812b997001cec6655190056198b3f7703b1533982a38fc20e97`；加载 note 的独立对比见本报告工件。主任务随后在 [module-build-id-comparison.json](../.tooling/bypass-check/reconnect-acquisition/module-build-id-comparison.json) 完成包含新 hl7603/qti_pmic_glink 的十项对比，均匹配。路径缺失不表示 voter 未创建、不表示客户端票没有存在，也不表示内核不支持 debugfs；这里只能认定当前入口不可观察。

没有本轮电流时间序列、voter 原始快照和硬件反馈，因此 **没有可证实的“当前停充由谁引起”结论**。此前 capacity 接近 80、current=0 是旧记录，SOC 上限/充满/安全策略/其它软件或硬件状态仍是待排查解释，不能归因插件或候选接口，不能把重连当作旁路 A/B 验证。

当前可交付结果是：已扩展并明确独立撤票入口的排查范围；加强了加载模块声明构建身份对应；纠正 effective 名称和 success/readback 的含义；保留本轮缺失 status 与物理效果证据。插件保持原有能力界限，不据这些离线结果新增自动写接口。
