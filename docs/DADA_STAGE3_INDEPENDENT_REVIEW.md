# DADA Stage 3 HAL / retail 独立审查

日期：2026-10-13。审查者与 HAL、retail 作者不同。本报告不审查审查者自己编写的 Stage 3 源码检索报告；该报告由主线程单独审查。全部操作在主机完成，网络、ADB、手机读写、HAL/Binder 调用、安装、驱动加载和目标代码执行均为 0。没有修改旧阶段材料或插件，没有运行任何阶段的作者生成器或验证器。

**最终 HAL / retail 报告没有发现尚未解决的实质问题。独立字节与边界检查通过，静态语义支持排除本轮候选作为当前可交付后端；这不是物理旁路、实机身份或安全恢复验证。** 对主审草稿提出一项措辞修正，主线程已采纳：普通 buck 分支未直接投的是 `info+0xd8` 的 input-suspend 和 `info+0xe8` 的 buck-input，不能扩大成任何输入限制都不受影响，更不能据此证明输入供电保留。

## 独立核验方法和范围

审查使用新编写的 [independent_audit.py](../.tooling/bypass-check/stage3/review/independent_audit.py)，不导入作者代码。它从原始 ELF64 小端节头重新读取指定 section，独立解析通过 `sh_link` 绑定的符号表和 RELA 表；ET_REL 必须以明确 section/index/文件偏移定位，不能从多个 `sh_addr=0` 的节中任选一个。重定位的目标节、符号、addend 和窗口内全部行均比较。

对每个代码窗口，审查直接调用已固定的主机 LLVM，以明确 `.text`、start/stop 重新反汇编，保存于本轮 `review/fresh/`，逐四字节 opcode 比较原始 ELF、作者归档和新的 LLVM 输出，包括 `.word` 的原始编码。没有把作者的 PASS 作为独立证据。既有输入与作者 final manifest 均重新逐字节读取并核对 size/SHA-256。

service 的 `.eh_frame` 由审查者自己解析原始 CIE/FDE，按实际 `zR`、PC-relative signed 32-bit pointer 和 range 得到全部 124 个 FDE。56 方法逐项匹配原始 FDE；service `.relr.dyn` 另独立解码相对指针槽与 `.data.rel.ro` 的 in-place addend，确认全部方法 vtable 的实际目标。TypeC 的完整函数还直接从 AArch64 原始 ADRP/ADD/BL 编码提取每次 `strcmp` 的 literal，没有依赖作者插入的 STRING 注释。retail 的 33 个完整具名函数另与原始符号 value/size 核对；其余 6 个窗口是指定初始化/注册/局部窗口，没有冒充完整函数。

| 检查组 | 独立结果 | 解释 |
| --- | --- | --- |
| HAL 代码 | 81 窗口，9299 instruction words | 明确 `.text`，原始字节、坐标、源 hash 和 fresh LLVM 一致 |
| retail 代码 | 39 窗口，3507 instruction words | ET_REL 节、完整 RELA 和 fresh LLVM 一致；其中 33 个完整具名函数边界匹配 |
| retail 数据 | 10 窗口 | raw data、section/index、文件偏移及 RELA 一致 |
| service 边界 | 124 FDE，56 方法 / 55 函数 / 15 setter | tx15/41 共用函数；全部 vtable RELR 与 in-place target 一致 |
| TypeC 比较 | 7 getter literal，8 setter key 与 2 value literal | `boot` / `normal` 只作值比较，没有增加为 key |
| 作者 final manifests | native 187 行；retail 51 artifacts + 47 inputs | 按记录分组统计，不能合称唯一文件数 |
| 自动检查总数 | 1919 PASS | 指令字数量是窗口比较次数，含重叠窗口，不是唯一指令数 |

详细检查见 [verification.json](../.tooling/bypass-check/stage3/review/verification.json)、[independent-fdes.json](../.tooling/bypass-check/stage3/review/independent-fdes.json)、[raw-typec-comparisons.json](../.tooling/bypass-check/stage3/review/raw-typec-comparisons.json) 和 [retail-function-boundaries.json](../.tooling/bypass-check/stage3/review/retail-function-boundaries.json)。复核总计 6 个 ELF，包括 service、NDK 和 4 个模块。

## HAL 人工语义复核

- tx19/44：真实 reader/writer 共用 `0x124100`，固定 `smart_night` literal；setter 原样 String 写入并把 writer 的 int 结果写到输出。该路径不证明下游 owner、0 值释放、系统输入保留或原策略恢复。
- tx40/50：完整函数各 64 字节，仍调用 `0x239c0` 并写 int 输出。没有按短函数误判为 stub。tx49 的 bool 转 ASCII 0/1，初始化复用先前 SIMD 缓存的路径，目标是 `reverse_chg_mode`；没有根据函数名猜测充电方向。
- tx51：7 个完整 key 中，5 个 HID 分支有 write 后 read；两个软件 String 状态不是充电请求查询。未知 key 的 OK 与空输出不证明支持该请求。
- tx52：完整 8 个 key 属于 HID、软件和固件；`boot`/`normal` 使用 value。`stopSendFirmFile` 匹配仅日志并继续比较，未建立 cancel/join/free/停止标志。两个异步 worker 与手柄端点闭合，不扩张成完整升级协议安全审查。
- tx55/56：完整函数仅日志、`AStatus_newOk` 和 hidden status 返回槽写入，不用 key/value、不写业务输出。NDK 的空 String / int 0 初始化以及后续公共序列化路径与报告一致。Binder OK 或返回 0 不证明硬件执行。

上述关键调用、分支和输出契约已人工对照新的原始字节确认的反汇编；其余方法的覆盖分类核对完整函数调用表。56 个 HAL 方法的高层覆盖不等于 110 个 key 的下游人工覆盖。NightCharging 下游不在本轮证明范围，第二轮留下的 88 个路径也没有因此被补审。

## retail 人工语义复核

| 审查问题 | 原始实现锚点 | 独立判断 |
| --- | --- | --- |
| 查询是否有副作用 | partition show `0xa98/0xac8` 到 `get_info_1`；alloc `0x154/0x160`、dealloc `0x65c/0x664` | 可达 SCSI header 写入；不能列为无副作用手机读取。cached getter `0x9f4..0xa50` 自身只读缓存并转 bool |
| 是否持锁执行 SCSI | write helper `0x428` unlock，`0x4ac` `scsi_execute_cmd` | 已释放相应 spinlock；没有以持锁 SCSI 推断死锁 |
| store 能否证明执行成功 | `kstrtoint` 后刷新/改字段/整记录 helper，末尾返回 count | helper 错误没有形成 store 错误传播或事务回滚；shell 成功不能证明持久写入或硬件完成 |
| 写 0 是否重算阈值 | smart work `0x2324` 更新 bool，`0x2330` 为 0 跳过 `0x2338` conflict | 该 worker 关闭路径缺重算；不能保证撤销共享 SOC 80，也没有声称永远无法恢复 |
| 阈值和回差 | conflict 按优先级写 `info+0x290`；SOC work `0x25f4/0x25f8/0x2600` | 条件命中时 80 请求停充 / 75 请求恢复，是原厂 SOC 循环；回调未传播错误，不能证明硬件完成。延迟是 jiffies/ticks，没有无依据换算成毫秒 |
| 哪些注册槽已知 | buck/quick `.data+0x168` 首字分别 0/2，注册调用与 callback `+0x28` | 只闭合 slot0/2；slot1/3/4/5 未建立运行绑定，不能宣称为空 |
| buck 是否保留输入 | 普通分支 `0x630c/0x6320` 固定 `soc_limit` 两票；电压 callback `0x44a4..0x44d0` | 软件 5V/9V 目标更新不证明实际输入切换或保留；charge_more `0x4918..0x4ab0` 另投电流/buck-input，并在阶梯 10 时禁充 |
| quick false 是否释放 owner | `0xaa70` 固定 enabled=1，`0xaa74` value=status；disable callback 为 0 跳 `0x4218` | false 保留激活的中性 0 票；没有直接恢复 CP/重新排队链，不能等同 owner 删除或硬件恢复 |
| quick stop 的影响范围 | `0x445c..0x4850` CP role0/1、协议电压/模式/override/ADC 等 | 广泛原厂快充停止流程；所调用全部硬件 helper 和物理稳态未全量证明，不能当作电池专用开关 |
| 原厂事件/卸载是否补恢复 | event 0/2 跳表、取消 work、清字段与 helper(false)；remove `0x1cdc..0x1d60` | 后续数值事件的释放链不构成 store=0 协议；remove 没有可见撤票/取消 work 保证 |
| poweroffmode 有无电气值义 | `.data` id1 → cached `+0xa8`；prepare `0x1ac8` raw64 写 `0x200000002` | 持久整数和初始化写 2 已闭合；没有字段 schema 或电池/SYS 通路消费者，不能推导 0/1/2 电气语义 |

这些判断支持报告的后端决定：本轮没有独立插件 owner、有效请求状态、仅撤自己请求、错误完成与可逆恢复的完整契约。原厂 `soc_limit` 票和 CP stop 链不能替代外部供电承担 SYS 负载、电池稳态电流为零的物理证据。新 partition 模块仍只有官方包内身份，未新增运行实例匹配证明。

## 冻结和主审边界

两个作者均已收到独立复核结果，当前 final 报告没有要求修正的实质问题；主审草稿的字段措辞已修正。审查目录的 [manifest.json](../.tooling/bypass-check/stage3/review/manifest.json) 冻结 final HAL/retail 报告、作者 manifests/verification 的输入身份、本审查报告、脚本和 fresh 输出。

主审草稿只按 `reviewed_main_draft_snapshot` 冻结实际已读版本；它的 HAL/retail 段落已审查，源码段及旧阶段/插件保留验证由主线程承担。本审查不把未来主审最终版 hash 冒充已审核身份。主线程添加收口数量或调整文字后应自行核对并另存 final delivery manifest。

所有 PASS 均限于归档身份、字节、边界与上述静态推导。本轮实机电气对比数为 0，没有授权依据新增节点名称、未知值、root 权限或 getter 名称开展手机试验。
