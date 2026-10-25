# DADA Stage 3：两个零售策略入口的静态闭环

## 结论

官方包中存在 `charger_partition_mishow` 和 `charger_partition_poweroffmode` 的真实实现，但本轮没有得到可交给旁路插件使用的安全后端。`mishow` 能在特定条件下触发原厂 SOC 上下限充电策略；它共享原厂 `soc_limit` 所有者，带有 80/75 SOC 循环、其他策略优先级和不完整的关闭重算路径。`poweroffmode` 已追到持久化整数，没有追到可定义为“关闭电池充电且保持系统输入供电”的消费者。

本轮还发现：这两个 sysfs 的 **show 查询也可能写存储**。不能把它们列入手机只读探测白名单。这里的存储写入与 PMIC 供电切换是两回事；发现写盘不代表发现电气旁路。

本轮手机操作数为 0。没有 ADB、HAL 调用、寄存器访问、安装或目标程序执行；只读取主机已有官方镜像/ELF，运行自己的解析器和主机 LLVM。插件及 Stage 1/2 文件均未修改。

| 候选 | 已证明的作用 | 启用/释放的约束 | 后端决定 |
| --- | --- | --- | --- |
| `charger_partition_mishow`，HAL110 索引 29 | 存储整数；cached getter 输出是否非零；原厂 worker 有条件选 SOC 80 阈值，达到 80 请求停充、降到 75 请求恢复 | 受 bootmode/HW 条件及更高优先级策略约束；写 0 的该 worker 路径不调用阈值重算；固定原厂 `soc_limit` owner；部分回调错误被忽略 | 排除：不是插件独立票，不保证关闭恢复，更不是恒定旁路供电 |
| `charger_partition_poweroffmode`，索引 28 | 存储/缓存 `info+0xa8` 的有符号整数；初始化 prepare 条件路径写入 2 | 没有枚举值语义或电池通路消费者闭环；查询和写入涉及整记录及存储头字段 | 排除：名字、启动改值及枚举字符串不能证明充电开关 |

## 来源与定位规则

新提取模块来源为 Stage 1 已有官方 `vendor_dlkm.img`，SHA-256 `b11940f67f673076ce5f0ac409919505d7af7495f4999f123891f426ae63042b`。镜像模块清单第 37 行是 NID `2383351` 的 `charger_partition.ko`。通过主机 `dump.erofs` 精确提取 `/lib/modules/charger_partition.ko`，64792 字节，SHA-256 `c34075fa1d08dcb97c97fec46635c720568c953ea70c7439f37e31c7fe1cf7ec`。EROFS Layout 3 为压缩文件，NID 不等于物理字节偏移；未捏造单一镜像 offset。提取来源、工具哈希和元数据保存在 `charger-partition-acquisition.json` / `charger-partition-source-metadata.txt`。

`routing.json` 冻结了既有 20 个模块中这两个精确名字和关联符号的检索结果及来源哈希。`mca_smart_charge.ko` 引入 `charger_partition_get_mishow`，包含 `handle_mishow_config`；`mca_business_charger_comp.ko` 引入通用 partition alloc/write/dealloc，并含 `MCA_CHARGER_PARTITION_POWEROFFMODE` 字符串。后者只作路由证据，不把枚举字符串当消费语义。20 个模块中，smartops 注册调用的已知提供者是 buck 和 quick。

主机 ELF 身份不等于运行中模块身份。新模块没有取得手机内存 build-id；没有证明运行 sysfs 的 class alias、加载实例或运行回调注册表与本包一一对应。沿用 Stage 1/2 的包身份范围，不扩张到实时电气证据。

下文模块地址均为 **指定 ELF `.text` 节内偏移**；服务地址为 ET_DYN 虚拟地址。`windows.json` 为每个新窗口保留 ELF section/index、section 文件偏移、原始字节、源文件/窗口 SHA-256、RELA、fresh LLVM 反汇编及逐 opcode 字节核对。`elf-metadata.json` 保存六个路由/分析 ELF 的节表、符号和重定位；`data-windows.json` 保留 10 个数据窗口。不能以任意同数字地址替代这里的 section 定位。

## HAL 名字到 sysfs 提供者

`vendor.xiaomi.hardware.micharge-service` SHA-256 为 `0dcfb7b5816d962978e8724fdbb7798627f0575c6a72beae277c55b51f1f158b`。新 `.text` VA 窗口 `0x2f1f0..0x2f294` 和 `0x3232c..0x32440` 复核两项初始化：

| 索引 | key / 路径 literal | 初始化 global |
| --- | --- | --- |
| 28 | key VA `0x5d30`；路径 VA `0x5536`：`/sys/class/xm_power/charger/charger_partition/charger_partition_poweroffmode`，长度 76 | `0x124028`；`0x32370` 分支复制 |
| 29 | key VA `0x4c82`；路径 VA `0x5728`：`/sys/class/xm_power/charger/charger_partition/charger_partition_mishow`，长度 70 | `0x124040`；`0x323e8` 分支复制 |

这是静态初始化绑定，不证明之后映射不可变。本轮不重新扩展 HAL 通用写事务，也不调用它。

`charger_partition.ko` 的 `.data` 表基址 0，步长 `0x28`：row0 的 name RELA 指向 `.rodata+0x678`，id 为 0；row1 指向 `.rodata+0x99e`，id 为 1。两行 show/store 分别重定位到 `.text+0xa54` / `+0xb10`。probe `.text+0x14f8..0x1624` 初始化三行表并通过 `mca_sysfs_create_link_group` 注册 `charger` / `charger_partition`，随后注册并排队 partition worker。第三行仅冻结完整表以校验布局，没有作为第三个候选展开。

## show/store 的真实副作用和错误传播

两个 show 在 `.text+0xa54..0xb0c` 均调用 `charger_partition_get_info_1`；分别格式化 cached `info+0xb0` / `info+0xa8`。helper `.text+0xdb8..0xee0` 调用 `alloc(4,1,32)`、read、复制整个 32 字节到 `info+0xa8..0xc7`、dealloc。错误主要记录日志，show 没有获得明确错误状态，可能输出旧缓存。

`alloc` `.text+0x4..0x1bc` 检查 ready 和参数，分配/清零共享 4096 字节缓冲并读取存储头。成功读取后，如果原始头字 `buffer+0xc` 非零，`0x154` 将它置 0，`0x160` 调用 SCSI 写；如果头字为 0，则返回错误。`dealloc` `.text+0x528..0x6a4` 重新读取头，在 `0x65c` 将该原始字置 1，`0x664` 再写。这里没有源 schema 证明这个头字是锁、占用保证或其他特定协议。

SCSI 写实现 `.text+0x370..0x524` 构造 opcode `0x2a`，在 `0x4ac` 调用 `scsi_execute_cmd`，数据长度为 4096。相应自旋锁在调用之前已释放，不能据此声称持锁执行 SCSI 死锁。结论是 **show 的可达链有存储写副作用**，不是每次查询必然写成功，也不是 PMIC 寄存器写。

store `.text+0xb10..0xc1c` 使用 `kstrtoint(base=0)`，坏字符串返回 `-EINVAL`；没有对这两个字段进行枚举或范围检查。它先刷新整记录，再改对应缓存字段，再调用 set_info_1。后者 `.text+0xf20..0xff4` 经 alloc、`charger_partition_write` 和 dealloc 写整个 32 字节记录；write `.text+0x7e8..0x990` 读入目标 4096 字节，覆盖记录后写出。helper 错误未返回给 store；store 通常仍返回原始 count。读缓存、写盘、写回结果可能不一致，不能把 shell 写入成功当策略执行成功，也没有整记录回滚事务证明。

**区别：**导出的 `charger_partition_get_mishow` `.text+0x9f4..0xa50` 只读取 cached `info+0xb0`，非零转 bool；全局 info 不存在时记录日志并返回 -1。它本身没有 SCSI 读写。smart worker 调用的是这个 cached getter，不是 sysfs show。

## mishow：有条件的原厂 SOC 循环

smart worker `.text+0x203c..0x255c` 先受 work 相对字段 `+0x1ec` 约束，后段要求 HW 指针非空、bootmode 非零、HW 首个 u32 在 2..4。`0x2300` 先把本地 bool 置 0，`0x2308` 调用 cached getter且不检查返回值，`0x2324` 保存 bool 到 global info `+0x654`。

**关闭缺口：**`0x232c/0x2330` 在新 bool 为 0 时跳过 `0x2338` 的 `smartcharging_handle_algorithm_conflict`。因此此前选出的共享 SOC 阈值 80 不保证在写 mishow=0 后由该 worker 重算/清除。其他事件或策略重算可能恢复；没有声称阈值永远不恢复。该 worker 的再次排队使用 1250 ticks/jiffies，未证明对应毫秒数。

冲突函数 `.text+0x101c..0x113c` 按 endurance、navigation、night 的启用字段/上限顺序选择，随后才判断 mishow、bootmode 和 HW 条件；该条件命中时选择 80。结果写入共享 `info+0x290`，取消 SOC work 后以 delay=0 排队。因此此接口与已有原厂策略共享阈值，不是插件单独可控制的决策域。

SOC worker `.text+0x2588..0x2768` 的回调 x0 是 `info+0x1b8`，所以指令中的 `+0xd8/+0xdc/+0x113` 对应全局 `+0x290/+0x294/+0x2cb`。eligibility 为 0 时直接返回，没有显式 helper(false)；阈值为 0 时清 latch 并 helper(false)。SOC 达阈值且未 active 时先置 latch 再 helper(true)；SOC 小于等于阈值减 5 且 active 时先清 latch 再 helper(false)。对 mishow 的 80 即是 80 请求停充、75 请求恢复；周期为 250 ticks/jiffies，不能写成已确认的毫秒数。SOC getter返回值和各回调返回值缺少可见状态有效性/失败重试检查，latch 先改也可能抑制本次失败后的重试。

事件处理 `.text+0x1d64..0x1f84` 的四项跳表原始字节 `01 35 00 33`，base `0x1dcc`。numeric event 0/2 路径可进入取消 delayed work、清 eligibility/latch并于 `0x1e5c` 调用 helper(false)的释放链。这里只按数值事件描述，未用未闭环枚举名称“拔线事件”。这类后续原厂事件不是 store=0 的恢复协议。remove `.text+0x1cdc..0x1d60` 仅注销字符设备、sysfs 和 event notifier，没有看到取消 delayed work 或 helper(false)，也不能作为释放保证。

## 六槽注册、固定 owner 和电源范围

`mca_smartchg_if_ops_register` `.text+0x4..0x80` 校验 type<6，将指针写到全局 `.bss + type*8`。`smart_charge_set_soc_limit` `.text+0x2d7c..0x2f0c` 遍历六个指针，使用 ops+8 context、ops+0x28 callback并丢弃回调返回值。仅证明六个可注册槽，不证明运行时四个其余槽为空。

| 已知提供者 | 冻结数据与注册调用 | SOC callback |
| --- | --- | --- |
| buck | `.data+0x168` 的首个 u32=0；callback RELA 位于 `+0x190`，指向 `.text+0x6288`；probe `0xb84..0xb90` 登记 | slot **0** |
| quick | `.data+0x168` 的首个 u32=2；callback RELA 位于 `+0x190`，指向 `.text+0xaa2c`；probe `0x378..0x384` 登记 | slot **2** |

slot1/3/4/5 未闭环。这里注册表 type 与 strategy process type 的枚举不是同一编号体系。

buck callback `.text+0x6288..0x63ec` 使用固定 literal `soc_limit`（`.rodata+0x1f13`），普通分支对 charge-enable `info+0xd0` 和 input-voltage `info+0xe0` 都设 enabled=1/value=0；释放则将这两票 enabled=0，并可能重新排队 buck work。普通分支未直接投 input-suspend `info+0xd8` 或 buck-input `info+0xe8`；这不等于输入供电不受影响。init `.text+0x6a0..0x808` 将 input-voltage 绑定到 `strategy_buckchg_input_voltage`（type3/default1），该 callback `.text+0x44a4..0x44d0` 修改软件目标为 5000/9000，没有立即协议请求。后续电压 worker 的完整输入稳定性本轮未另行证明。

charge-enable callback `.text+0x3ee0..0x3f6c` 调用 role0 的 `platform_class_buckchg_ops_set_chg`，在条件分支还会操作 role1。虽然回调能返回状态，外层 SOC callback未传播这些错误。若 `support_charge_more` 字段启用则进入阶梯路径：`.text+0x4918..0x4ab0` 对 charge-current / buck-input 投票，阶梯饱和到 10 时再投 charge-disable；false 分支取消阶梯并撤销这三票。不能仅按普通分支推断所有运行配置均无输入限流，也没有默认假定运行字段为 0。

quick callback `.text+0xaa2c..0xaa98` 使用固定 `soc_limit`（`.rodata+0x38dd`），对 `quick_chg_disable`（info+0x208）始终 enabled=1，value=status。因此 false 是 **仍激活的中性 0 票**，不等于删除 owner。disable voter type2/default0；callback `.text+0x4190..0x4234` 在非零时取消 quick work并调用 stop charging；零值路径没有直接重启 CP 或重新排队的恢复步骤。

stop charging `.text+0x445c..0x4850` 会关闭 CP role0/1，条件调整 FSW、PD fixed voltage（5000/9000）、模式、buck helper、override 与 ADC，并含总线电压读取/等待分支。这是广泛的原厂快速充电停止流程，不是单独电池充电位。被调用的所有硬件 helper 和物理稳态没有本轮全量证明；不能把“CP stop”当“适配器持续独立供 SYS”的证据。

所有者固定为原厂 `soc_limit`，没有插件新 owner/create/release 接口证明。复用它会改变原厂决策，不能作为插件自己的安全票；快速充电中性释放、buck票撤销也不保证其他 owner、协议和硬件状态恢复。

## poweroffmode 的边界

模块没有专用导出 `get_poweroffmode`。本候选 store 只缓存/写 `info+0xa8`，没有立即调用充电 callback、投票或 queue worker。`charger_partition_work` `.text+0x1698..0x195c` 查找 SCSI device/partition，条件进行 header check、ready/retry 与初次记录读取；probe 设置初始字段 e9=1，worker 的初次路径会读取 info2 后清 e8/e9，之后重试/ready 分支才能进入 prepare。不能把它概括成无条件每次写入都会执行 prepare。

`charger_partition_prepare` `.text+0x1aa8..0x1bac` 在 `0x1ac8` 写入 64 位 `0x200000002` 到 `info+0xa8`，即 raw word +a8/+ac 都为 2，然后整记录写回并刷新。后续 event76..79 使用其他字段，不能据此推出 poweroffmode=2 的电气语义。没有该持久字段的 schema、bootloader/ADSP 消费者或 0/1/2 值义证明；启动路径改值不能等同于电池开关。`mca_business_charger_comp` 的通用 partition 导入及 enum string 也不能补上这段缺口。

## 可复核产物与后续门槛

证据根目录为 `.tooling/bypass-check/stage3/retail/`。`extract_windows.py` 仅对上述两个候选的提供者/消费者窗口运行 LLVM；`freeze_routing.py` 仅对既有 20 个 ELF 做精确名字路由。当前冻结为 **39 个 code 窗口、3507 个 instruction words、10 个 data 窗口**。`verify_evidence.py` 重新从 ELF 节头读取定位，比较原始字节/哈希、RELA、fresh LLVM opcode及语义锚点；`verification.json` 记录结果，`manifest.json` 冻结报告、脚本、产物及输入依赖。复核通过只能支持这里的静态结论。

未来可接受后端仍需独立插件 owner、明确输入不切断/电池充电分离语义、错误可见、可逆释放和失败恢复、精确机型/运行身份与物理对比验证。两个候选均不满足这些条件。本轮不为补齐缺口而授权在连接手机上查询、写入、HAL试值或停充实验。
