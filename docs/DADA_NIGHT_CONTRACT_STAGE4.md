# dada 第四轮：smart_night 的参数、工作队列与恢复契约

日期：2026-10-13。目标包：Xiaomi 15 / dada / OS3.0.303.0.WOCCNXM。本子任务仅在电脑上读取已有官方包 ELF；手机操作为 0，没有 ADB、Binder/HAL 调用、安装、运行厂商代码或手机试写。

**`smart_night` 的下游已经闭合为全局 OEM 夜间策略：保存一个整数，把夜间 SOC 上限固定为 80，再重算共享策略。整数 0 有明确的禁用与重算路径，但它不是只撤销插件请求的接口，也不能精确恢复操作前状态。** 它另有条件性的 CSD 脉冲模式消费者；读回仅为软件保存的整数，不包含夜间电量快照、共享阈值、投票结果或硬件确认。因此本轮没有取得可用于旁路插件的完整独立申请/查询/释放契约。[E1][E2][E3][R2][R3]

单独的 `smart_night` sysfs show 分支及其名称查找依赖，只读软件内存并格式化输出，没有查到供电、硬件读取、投票、worker 排队或分区写入。这个结论限定于配套包中的具体分支及驱动正常初始化、对象存活的条件；不会扩大为所有 `smart_charge` 属性、字符设备、全部 getter 或当前手机运行代码的保证。[E1][E4]

## 材料与新增边界

| 工件 | 字节数 | SHA-256 |
| --- | --- | --- |
| `mca_smart_charge.ko` | 88544 | `9c85ed0c761e597df3a549ed25b9a9cfbbc9414fb7b38d2b6a6043bc787510af` |
| `mca_sysfs.ko` | 32632 | `63e0aa14578bf61620ce0f8dd52066ffde1334afa1acdd3c7e6a0c875c09b505` |

材料来自此前逐字节核对的官方包；发布包取得及现场身份边界沿用 [官方固件证据](DADA_FIRMWARE_HAL_ACQUISITION.md)。这里没有把磁盘工件等同于当前加载内存，也没有取得新运行 build-id。

新增证据位于 [stage4/night](../.tooling/bypass-check/stage4/night/)。仅生成 8 个完整函数窗口和 1 个有 `.altinstructions` 重定位依据的辅助尾部窗口，共 773 条 AArch64 指令；另有 52 个数据窗口，含 sysfs 表、跳转表、file-ops、split-BTF、alternatives 和所用字符串。各窗口明确保存 section、section file offset、section 内偏移、实际 file offset、原始字节、RELA、symbol 边界及 fresh host LLVM 输出。未重新生成 Stage 3 的 39 个 retail 窗口，也未重复 HAL 的 56 方法或 110 key 扫描。[E5][E6]

| 新完整函数 | `.text` 节内范围 | 本轮用途 |
| --- | --- | --- |
| `smart_charge_sysfs_show` | `0x84..0x310` | 读回字段和 show 跳转 |
| `smart_charge_sysfs_store` | `0x314..0x4cc` | 参数解析、错误、store 跳转 |
| `handle_smart_night_message` | `0xfac..0x101c` | 夜间参数、快照、共享重算 |
| `mca_sysfs_lookup_attr`，在 `mca_sysfs.ko` | `0x780..0x7f0` | show 的名称查找依赖 |
| `smart_charge_file_open` | `0x32e0..0x3360` | 字符设备共享缓冲区生命周期 |
| `smart_charge_ioctl` | `0x3008..0x31a0` | 同一驱动的候选别名范围 |
| `smart_charge_mmap` | `0x31b0..0x32dc` | 同上 |
| `smart_charge_file_close` | `0x3364..0x3704` | 同上 |

`smart_charge_ioctl` 的 symbol size 不包含 `0x31a0..0x31ac` 的三条 alternatives 指令。本轮单独保留其原始字节，依据为 `.altinstructions+0x10/+0x1c/+0x34` 的 PREL32 重定位，不能用相邻符号推测函数边界，也不把该尾部算成另一完整函数。[E5]

## 1. sysfs 名称、参数和读回

HAL tx19/44 到 `/sys/class/xm_power/charger/smart_charge/smart_night` 的固定读写已在 [Stage 3 HAL 报告](DADA_HAL_COVERAGE_STAGE3.md)闭合，本轮从该文件的驱动属性表继续。[R1]

`smartchg_sysfs_field_tbl` 的每条记录为 40 字节，共 9 条。`smart_night` 位于 `.data+0xa0`，mode 原始数值为 `0x1b4`（0664），id 为 4；show/store 指针重定位分别为 `.text+0x84/+0x314`。show 以 `.rodata+0x0` 的 byte 跳表从 `0xdc` 派发：id4 的 byte 为 `0x65`，得到 `0x270`；store 以 `.rodata+0x9` 从 `0x3e8` 派发：id4 的 byte 为 `0x1e`，得到 `0x460`。这些是表字节与真实分支的对应，不能仅由属性名猜测行为。[E3][E5]

store 在 `0x378..0x388` 调 `kstrtoint(buffer, base=0, &value)`。该值是一个有符号整数，不是 owner/type/value 多词协议，HAL 也不对其结构化解释。解析函数非零时，store 在 `0x390` 统一返回 `-22/-EINVAL`；没有给 `smart_night` 检查 0/1 数值域。已知属性查找失败或 device driverdata 为 NULL 时返回 -1。解析成功后，`0x460..0x464` 直接把该整数传给夜间 handler。[E2][R1]

show 的 `0x270..0x27c` 通过 `.bss+0x30` 的全局 info 指针，只载入 `info+0x2bc` 的 32-bit 整数；在 `0x2e8..0x2f8` 以 `.rodata+0x732` 的精确格式 `"%d\n"` 写出。它不是归一化布尔值，也不是“当前实际旁路状态”。夜间分支不读取其他策略数组、有效 SOC 限制、`soc_limit_enable`、voter 客户端或电池/输入硬件状态。[E1][E3]

因此，任意非零输入不能简单归纳为有效启用：handler 保留完整 32-bit raw 值，却只把低 16 bit 写入 enable 字段；低 16 bit 为 0 的非零整数会走电量快照读取，却在共享冲突判断中被视为夜间未启用。这是静态解析边界，不能作为手机试值建议。[E2]

## 2. handler：固定 80 与启用快照

`handle_smart_night_message` 的完整 28 条指令表明：[E2]

| 地址 | 行为 |
| --- | --- |
| `0xfbc..0xfc8` | 从 `.bss+0x30` 取全局 info |
| `0xfdc` | 保存原始 32-bit 输入到 `info+0x2bc/smart_night_val` |
| `0xfec..0xff4` | 原始输入非零时，调用 `strategy_class_fg_ops_get_rsoc(&info+0x638)`；不检查返回值 |
| `0xff8 / 0x1004` | 无论输入 0 或非零，把夜间 `func_value` 固定写为 80 |
| `0x1000` | 把输入低 16 bit 存到夜间 enable halfword |
| `0x1008` | 始终调用共享 `smartcharging_handle_algorithm_conflict` |

本轮直接从模块 `.BTF` 冻结并解码相关记录：`smart_night_val=info+0x2bc`，`night_enable_rsoc=+0x638`，`smart_chg_data=+0x568`。`SMART_CHG_INFO.smart_chgcfg` 在该对象内偏移 4；`SMART_CHG_DATA` 大小 12，`func_value` 在偏移 2；`SMART_CHG_NIGHT` 索引为 0。因此夜间记录的首 halfword 与值 halfword 精确落在 `info+0x56c/+0x56e`。首 halfword 的 BTF 名称来自未取得的 base 字符串，报告没有补造它的字段名；启用语义来自 handler 的写入和共享冲突函数的 `ldrh/cbz`。[E3][R2]

split-BTF 的本地字符串基址由第一条命名记录对应 pool 首字符串 `mca_sysfs_attr_info` 建立，相关 record 原始 hex、section/file offset 和未解析的 base 名称均保存。不把局部 ordinal 当成全局 kernel type id，也不推导未取得的 base 类型名称。[E3]

## 3. 重算、取消和释放的准确范围

下游引用 Stage 3 已冻结完整窗口：[共享冲突函数](../.tooling/bypass-check/stage3/retail/smart-mishow-algorithm-conflict.fresh-disasm.txt)、[SOC worker](../.tooling/bypass-check/stage3/retail/smart-smart_charge_soc_limit_workfunc.fresh-disasm.txt)、[callback 派发](../.tooling/bypass-check/stage3/retail/smart-smart_charge_set_soc_limit.fresh-disasm.txt)。[R2][R3]

冲突函数 `0x101c..0x113c` 按 ENDURANCE_PRO、NAVIGATION、NIGHT 顺序读取共享 enable/值，之后才判断 cached mishow 与 bootmode/HW 条件。结果写入 `info+0x290/soc_limit`。`0x10b8..0x10d8` 对 `info+0x1b8/smart_soc_limit_work` 调 `cancel_delayed_work_sync`，再在 `system_wq` 上以 delay 0 排队。取消和 queue 的返回没有形成可供 sysfs 调用者查询的完成状态；排队之后 store 返回，不等待新一轮 SOC 决策或各 provider 硬件成功。

禁用夜间字段不会无条件释放 SOC 请求。若更高优先级策略仍启用，或零夜间字段之后命中 mishow 条件，共享阈值仍可能非零。SOC worker 对“适配器已拔出”的缓存条件（`info+0x294`）为 0 时直接退出；在线且阈值为 0 的分支才清 `soc_limit_enable` 并派发 active=0。阈值非零时，SOC >= threshold 申请，SOC <= threshold-5 且原来 active 才释放，中间区间保留状态；因此夜间上限 80 对应 80/75 滞回。循环排队的 delay 常量是 **250 jiffies**，没有在此报告中假定 HZ 或转换成毫秒。

SOC active=0 派发的是共享 OEM 状态，不携带夜间请求 owner。先前已审查 buck 使用固定 `soc_limit` 客户端申请/撤销 `chg_enable` 和 `input_voltage` 票；quick 对 fixed `soc_limit` disable-OR 票保持 enabled=1 并改变 active/0 值，快充 stop 还可能调整 CP、PD 与 buck fallback。已识别两个 callback 不代表六个 registry 槽位全已闭合。本轮不重做下游，也不能把这一 OEM 释放升级为插件独立票的精确撤销。[R3][R4]

## 4. 夜间电量快照另有 CSD 消费者

这项结论使用 Stage 3 的 [完整 `smart_charge_workfunc`](../.tooling/bypass-check/stage3/retail/smart-smart_charge_workfunc.fresh-disasm.txt)及 [probe](../.tooling/bypass-check/stage3/retail/smart-smart_charge_probe.fresh-disasm.txt)，没有重复生成旧窗口。[R2]

该普通 worker 接到的是 `info+0xa8/smart_charge_work`，所以不能把其反汇编中的偏移当成 info 偏移直接引用。probe 的 `0x1a44..0x1a6c` 设置该 work 的回调为 `.text+0x203c`；BTF 也给出 field offset `+0xa8`。普通 worker 的 `0x2160` 读 `[work+0x4c4]`，实际是 `info+0x56c` 的夜间 enable；`0x2458` 读 `[work+0x590]`，实际是 `info+0x638/night_enable_rsoc`。

在适配器条件、HW 条件和 `support_csd=info+0x630` 等分支允许时，`0x2454..0x2474` / `0x250c..0x2524` 使用夜间启用状态、启用时 RSOC 快照和当前 RSOC 选择 pulse mode 2 或 3；`0x21b4..0x21c8` 把结果存到 `info+0x62c/pulse_mode`，再 `mca_event_block_notify(type=5, event=54, &pulse_mode)`。该路径不是每次启用必定发生。本子任务未补查整个 CSD 下游，不能据此宣称某一实际电池电流或硬件周期。

夜间 handler 只取消并重排 SOC work；没有取消普通 `smart_charge_work`、等待其 CSD event 完成或直接恢复旧 pulse mode。输入 0 也不清启用 RSOC 快照。因而即使共享 SOC 状态最后释放，仍不能仅凭夜间 raw 整数读回证明其他夜间相关状态已恢复。[E2][R2]

## 5. 字符设备候选别名的限定检查

为了界定同一驱动的生命周期，本轮补查 file-open/ioctl/mmap/file-close 的完整函数，而未扩查任意设备 ioctl、构造 payload 或运行代码。[E7]

`g_smt_chg_file_ops` 位于 `.rodata+0x1a78`，size=264，含上述四个回调的重定位。已有 probe 在 `0x1ba4..0x1bd0` 把它绑定到 `info+8` 的 cdev，明确了 inode 路线的上下文差 8。file-open 从 cdev 取得共享缓冲区 `info+0x620`，为空时分配 4096 字节并把 info 保存到 file private data；已有缓冲区分支直接返回 0，没有在该分支设新的 file private data，也没有显示每个打开者的独立 night owner/refcount。

ioctl 从 file private data 读取同一个 `info+0x620` 缓冲区，复制 4096 字节用户数据；mmap 把这个全局缓冲区映射出去并修改 `info+0x628/map_flag`。file-close 验证该缓冲区的 checksum/配置，在允许的分支可能经 registry 的 `update_baa_para` 调 JEITA、wired QC、wireless QC，最后清 map flag、释放并清空共享缓冲区；checksum 或配置错误也只记录日志并最终返回 0。这里只界定可见直接实现；不把未检查的 callback 内部语义补成夜间行为。

这些函数没有提供 `smart_night` 的独立 owner、request-id、查询或撤销参数，也没有直接调用夜间 handler 或写夜间 enable/value/raw 字段。它们不是夜间安全恢复别名。它们的 open、ioctl、mmap、close 还具有上述分配、复制、映射及条件 callback 副作用，不能把字符设备打开/关闭当成 `smart_night` 的纯读查询。[E7]

## 6. 已补契约与仍不能实施的原因

| 要求 | 本轮取得的实际行为 | 对插件的结论 |
| --- | --- | --- |
| 准确参数 | 一个 signed int；base0 解析；没有 0/1 域检查 | 已补齐驱动解析契约，不能据此允许任意试值 |
| 查询 | raw `smart_night_val`，精确 `"%d\n"` | 正常 sysfs 分支是软件只读；它不是 owner/生效查询 |
| 启用 | 保存 raw、截为 enable halfword、固定值 80、条件获取 RSOC 快照 | 共享 OEM 策略，无法提供立即停充或任意 SOC 上限 |
| 禁用 | 清夜间 enable halfword、仍覆盖 limit=80、重算并异步排队共享 SOC | 有禁用入口；没有独立票释放或完成确认 |
| 超时/取消 | 同步取消旧 SOC work，随后立即排新 work | 无 userspace deadline、请求取消、lease 或晚到完成状态 |
| 错误传播 | 解析失败可见；handler/get_rsoc/queue/provider 完成未形成 store 结果 | count/HAL writer 成功不等于硬件控制成功 |
| 所有权 | 一个全局夜间字段及固定 OEM SOC owner | 不能区分 OEM、用户和插件写入 |
| 完整恢复 | getter 不给 snapshot/limit/shared active/CSD 状态；回写非零重新取 RSOC；回写零保留 snapshot 和 80 | 不能用保存 raw 后回写实现 exact restore |
| 物理旁路 | 共用 buck/quick SOC 链，末端 ADSP/PMIC 和真实供电对比缺口沿用旧报告 | 未证明系统输入持续供电且电池净电流近零 |

本子任务建议继续保持该候选后端不可用：本轮交付的是具体排除依据及可评估的单节点读查询语义，不新增插件写入口、不交付推测 ZIP，也不把当前 FCC 限流功能改称物理旁路。要形成安全后端，仍需独立 owner 的可恢复协议、软件票与硬件完成一致性、CSD/快充附带行为的边界及物理供电验证；root 权限本身不补这些契约。[R4]

## 证据索引与验证

- [E1] [sysfs show 完整新反汇编](../.tooling/bypass-check/stage4/night/smart_charge_sysfs_show.fresh-disasm.txt)。
- [E2] [sysfs store 完整新反汇编](../.tooling/bypass-check/stage4/night/smart_charge_sysfs_store.fresh-disasm.txt)与 [night handler 完整新反汇编](../.tooling/bypass-check/stage4/night/handle_smart_night_message.fresh-disasm.txt)。
- [E3] [raw data/RELA](../.tooling/bypass-check/stage4/night/data-windows.json)、[BTF 相关 record 原始字节与字段](../.tooling/bypass-check/stage4/night/btf-night-records.json)、[精确跳转与契约记录](../.tooling/bypass-check/stage4/night/contract.json)。
- [E4] [lookup helper 完整新反汇编](../.tooling/bypass-check/stage4/night/mca_sysfs_lookup_attr.fresh-disasm.txt)：仅 strcmp、遍历、返回。
- [E5] [全部新 code 窗口、边界、原始字节和 RELA](../.tooling/bypass-check/stage4/night/windows.json)，[主模块 ELF 元数据](../.tooling/bypass-check/stage4/night/elf-metadata.json)及 [lookup ELF 元数据](../.tooling/bypass-check/stage4/night/lookup-elf-metadata.json)。
- [E6] [manifest](../.tooling/bypass-check/stage4/night/manifest.json)和 [独立原字节/RELA/fresh LLVM 验证](../.tooling/bypass-check/stage4/night/verification.json)；[限定提取脚本](../.tooling/bypass-check/stage4/night/extract_night.py)、[BTF/dispatch 解码](../.tooling/bypass-check/stage4/night/decode_contract.py)、[验证脚本](../.tooling/bypass-check/stage4/night/verify_evidence.py)。
- [E7] 字符设备 [open](../.tooling/bypass-check/stage4/night/smart_charge_file_open.fresh-disasm.txt)、[ioctl](../.tooling/bypass-check/stage4/night/smart_charge_ioctl.fresh-disasm.txt)、[ioctl alternative tail](../.tooling/bypass-check/stage4/night/smart_charge_ioctl-alternative-tail.fresh-disasm.txt)、[mmap](../.tooling/bypass-check/stage4/night/smart_charge_mmap.fresh-disasm.txt)、[close](../.tooling/bypass-check/stage4/night/smart_charge_file_close.fresh-disasm.txt)；file-ops 和 alternatives 原字节见 E3。
- [R1] [Stage 3 HAL 方法覆盖](DADA_HAL_COVERAGE_STAGE3.md)：tx19/44、raw String、writer/Binder 边界；未重复此阶段的 HAL 分析。
- [R2] [Stage 3 retail 报告](DADA_RETAIL_POLICY_STAGE3.md)和其已冻结 smart conflict、workfunc、probe 窗口；本轮引用其共享算法和字段消费者。
- [R3] [Stage 3 retail 冻结窗口](../.tooling/bypass-check/stage3/retail/windows.json)：SOC worker、SOC callback 派发、buck/quick callback 的既有原始字节证据。
- [R4] [充电控制与恢复链](DADA_CHARGE_CONTROL_CHAIN.md)：独立 owner、错误传播、buck/quick/输入分叉与物理供电缺口。

验证只读取配套包与此前证据，重新解析 ELF 并逐条核对本轮 773 个 opcode。手机操作仍为 0；完整函数范围与 auxiliary tail 分开计数，不以分析数量作为功能验收。
