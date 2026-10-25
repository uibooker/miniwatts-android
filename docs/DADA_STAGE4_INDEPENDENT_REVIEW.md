# DADA Stage 4：夜间接口的独立审查

日期：2026-10-13。审查对象为 [smart_night 报告](DADA_NIGHT_CONTRACT_STAGE4.md)及其 [stage4/night](../.tooling/bypass-check/stage4/night/) 新增证据。本报告由无线 registry 子任务作者交叉审查夜间子任务，**不包含对自己的 registry 结论或其 P2 修订的自审**。registry 的独立审查由另一代理负责。

**未发现需要修订夜间作者最终报告的可行动问题。新增字节、函数边界、sysfs 路由、split-BTF 字段与报告的主要行为结论一致；这份通过结果不批准手机写入，也不证明可用旁路后端。** 夜间策略仍缺少独立请求所有权、完整状态查询、可确认的硬件完成和精确恢复契约，报告准确保留了这些限制。

## 独立性与验证结果

作者最终 manifest SHA-256 为 `4e0d9d9e062b8ba8a9a3092fab35c0b670016b2a0323959cb48aa196a5d3f1fe`，其 16 项输入与 20 项工件逐项身份一致。审查器使用自己的 ELF64 节头、完整符号表、RELA、split-BTF 解析逻辑，并重新运行主机 LLVM，逐条把反汇编 opcode 与指定 section 的 little-endian 原始 word 对照；未执行作者的提取、解码、验证脚本或厂商程序。

| 独立校验 | 结果 |
| --- | --- |
| 检查总数 | 1240，全部通过 |
| 新 code 窗口 | 9：8 个实际 symbol value/size 的完整函数，1 个 alternatives 辅助尾部 |
| 新 opcode words | 773，全部逐字对照 raw bytes 与 fresh host LLVM |
| 新 data 窗口 | 52，全部独立节偏移、原字节、RELA、字符串核验 |
| split-BTF | 独立解析 108 条本地记录，与作者选定的 9 条记录逐项一致 |
| 手机操作 / 作者脚本执行 | 0 / 0 |

详细结果保存在 [独立验证](../.tooling/bypass-check/stage4/review/night-independent-verification.json)、[独立 ELF 元数据](../.tooling/bypass-check/stage4/review/independent-elf-metadata.json)、[完整本地 BTF](../.tooling/bypass-check/stage4/review/independent-btf.json)与 [语义记录](../.tooling/bypass-check/stage4/review/night-independent-semantics.json)。作者 manifest、verification、全部输入和工件的复查身份见 [reviewed-night-inputs.json](../.tooling/bypass-check/stage4/review/reviewed-night-inputs.json)。

独立脚本最初把九条 sysfs 记录都当作有 store 回调，随后依据原始 mode/指针/RELA 修正：row5 `posture` 为 0440，只注册 show，store 原指针为 0 且没有重定位。这是审查器自己的假设错误，没有作为作者 finding。契约里的 store 跳表目的地与是否注册 store 是两件事；本审查的语义记录明确了这一点。

## 原始窗口与路由

两份实际 ELF 的完整节/符号/RELA 与作者元数据一致。ET_REL 各节地址重叠为 0，审查全程按 section index、section file offset 和节内 offset 取字节，未依赖裸 VA 选择材料。

`smart_night` 是 `.data+0xa0` 的 row4，stride40、mode0664、id4；show/store 的 ABS64 RELA 分别指向 `.text+0x84/+0x314`。两份 byte 跳表的真实分派得到 show `0x270`、store `0x460`。独立核验覆盖九条记录的名称、id、mode、show/store 指针以及跳表，夜间路径与报告一致。

8 个函数均核验真实 STT_FUNC symbol 的 value/size，分别为 show、store、night handler、lookup helper、file-open、ioctl、mmap、file-close。ioctl symbol 在 `0x31a0` 结束，`0x31a0..0x31ac` 三条辅助尾部不落在任何非零函数 symbol 范围内；`.altinstructions` 三个 PREL32 replacement 精确指向 `0x31a0/0x31a4/0x31a8`。不能把尾部计作完整函数，作者已经如此划界。

## 查询、写入、恢复与并发

独立完整反汇编证明，夜间 show 分支只通过全局 info 读取 `+0x2bc`，共同结尾用精确 `"%d\n"` 格式输出。lookup helper 仅检查输入、strcmp、按40字节遍历并返回；正常初始化与对象存活条件下，选定夜间路径没有充电 helper、voter 查询、worker 排队或分区读写。其他 show 分支含 `mca_get_client_vote`，不能把这个结论推广为全驱动或全部 HAL getter；作者未作此推广。磁盘包与现场运行代码之间的身份也仍需另证。

store 在 `0x388` 调 signed `kstrtoint(base=0)`，失败统一返回 -EINVAL，没有0/1值域限制；id4 在 `0x464` 直接调用 night handler。handler 保存完整 raw32 到 `+0x2bc`，raw非零时读取启用 RSOC 到 `+0x638` 且不检查返回值，随后写 raw低16到 `+0x56c`，固定80到 `+0x56e`，并无条件调用共享 conflict。raw非零但低16为0确实会读取 RSOC 而留下禁用 halfword；这个静态边界不是试值建议。

split-BTF 的本地字符串池起点、基址2526837、记录原字节和 unresolved base 名称独立一致。`smart_chg_data+0x568`、cfg偏移4、记录大小12、value偏移2与 NIGHT索引0共同确认 `+0x56c/+0x56e`；`night_enable_rsoc+0x638`、普通work+0xa8、SOCwork+0x1b8与pulse+0x62c也一致。保留未取得的base名字/type-id，不补造全局类型或首halfword字段名。

引用已冻结的 [Stage 3 conflict](../.tooling/bypass-check/stage3/retail/smart-mishow-algorithm-conflict.fresh-disasm.txt)与 [SOC worker](../.tooling/bypass-check/stage3/retail/smart-smart_charge_soc_limit_workfunc.fresh-disasm.txt)，确认0会禁用夜间并重算共享策略，未见夜间 owner撤销协议。高优先级 ENDURANCE_PRO/NAVIGATION 或 mishow条件仍可给出阈值；cached `info+0x294` 为0的worker路径直接退出，不显式派发false。这是软件缓存分支，不是当次适配器物理状态证明。在线且共享阈值为0才清latch并派发false；非零阈值走 threshold/threshold-5滞回，夜间为80/75。delay是250jiffies，未猜测HZ。

handler的可见完整实现没有owner token、驱动锁或compare-exchange；raw、RSOC、enable、80分别更新后才重算，也没有用户空间的atomic snapshot/restore请求。sysfs store返回count，不代表异步provider或硬件完成。读取旧raw后回写非零会重新取RSOC，回写零仍覆盖80并保留snapshot；raw查询又不包含共享阈值、有效票和CSD状态。因此无法从这一getter/writer组合推出精确恢复。这里的判断限定于完整可见实现，未声称整个内核不存在其他同步机制。

## CSD 消费者与字符设备范围

CSD只引用已冻结的 [Stage 3 完整普通 worker](../.tooling/bypass-check/stage3/retail/smart-smart_charge_workfunc.fresh-disasm.txt)与 [probe](../.tooling/bypass-check/stage3/retail/smart-smart_charge_probe.fresh-disasm.txt)，没有重生成全部Stage3证据。probe给worker base为info+0xa8；所以 `0x2160` 的work+0x4c4是night enable `info+0x56c`，`0x2458` 的work+0x590是启用RSOC `info+0x638`。条件允许时 `0x2454..0x2474` 或 `0x250c..0x2524` 选择2/3，并经 `0x21b4..0x21c8` 写pulse到info+0x62c后调用 type5/event54。报告正确限定了HW/support/适配器缓存及SOC/温度等分支，未把event54当作每次写入必然发生，也未补造末端物理电流。

夜间handler的call集合只有log、RSOC、conflict，没有取消普通work或恢复旧pulse。CSD完整下游仍未闭合，整数0不能作为所有副作用已撤销的证明。

file-ops对象 `.rodata+0x1a78` 的size264与五个回调重定位独立一致，包括两个ioctl字段指向0x3008、mmap0x31b0、open0x32e0、close0x3364。已冻结probe绑定cdev在info+8，open/close的inode路线偏移也相符。open为空时分配共享4096-byte缓冲区并设置file private data，已有缓冲区分支直接成功返回而不设置该打开者private data；ioctl按0xd0001001复制4096字节，mmap共享缓冲区且在remap结果判断之前置map_flag=1。close经checksum/配置分支，可调用registry ops+0x48 `update_baa_para`，随后释放并清map_flag/共享指针，正常错误分支最终返回0。

四份完整字符设备函数没有直接调用night handler/conflict或直接写night raw/enable/value字段，未取得其独立night owner/query/release参数。BTF也确认了ops+0x48的字段名。此处只证明直接实现；未审的callback内部不能被提升为night行为。open/close/mmap具有可见副作用，不能当作夜间纯读查询。作者对候选别名的排除与恢复限制准确。

## 剩余验收边界

通过原字节与报告一致性审查，只能支持继续研究和评估特定单节点读查询的静态语义。它没有解决独立owner、原状态完整查询、错误传播、晚到完成、CSD/快充附加状态、PMIC/ADSP末端控制、持续系统输入供电及电池净电流验证，也不能证明手机当前加载模块逐字相同。上述边界为任务仍未完成的条件，未作为作者报告bug。

审查证据冻结在 [review manifest](../.tooling/bypass-check/stage4/review/manifest.json)，[freeze verification](../.tooling/bypass-check/stage4/review/freeze-verification.json)记录输入/工件身份校验。没有修改night工件、旧Stage3证据、插件或手机状态；运行身份采集和它的主要审查由主代理与metadata代理独立负责。
