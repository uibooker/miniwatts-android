# DADA smart charging 注册表最终静态闭合

本轮补齐了固定官方 `vendor_dlkm` 镜像中的全部六个静态注册槽位。原先尚未闭合的槽位 4 是 JEITA，槽位 5 是温控；两者的 `set_soc_limit_sts` 字段均为原始零值且无重定位，不能补出独立的暂停、查询或撤销接口。六张 ops 表共 48 个回调字段，19 个非空、29 个为空，实际 SOC 回调仍只有槽位 0–3 的四个。

完整注册表和所有直接消费者中，未找到可供插件独占使用的所有者令牌、原状态快照、硬件状态确认、精确恢复或注销契约。已有 SOC 参数 `0` 路径会继续广播和执行厂商策略；它不能据此视为插件的独立撤销事务。该结论维持“不启用真实控制、不可宣称旁路效果已验收”的实施边界。

本报告只补充最终注册表覆盖，不改写 Stage0–4。手机操作、ADB、控制调用、安装、联网、执行厂商程序均为 0。KO 仅作为宿主机上的数据解析；实际执行的外部程序为先前固定的宿主 `dump.erofs` 和 LLVM。

## 1. 固定输入与覆盖范围

一手输入是 [vendor_dlkm.img](../.tooling/bypass-check/stage1/root-firmware/vendor_dlkm.img)，大小 77,938,688 字节，SHA-256 `b11940f67f673076ce5f0ac409919505d7af7495f4999f123891f426ae63042b`。本轮遍历该 EROFS 的全部 4 个目录，全部 `.ko` 路径共 392 个且均为普通文件，没有依靠名称先行过滤或只扫描旧报告选出的充电模块。

原始目录输出、镜像路径/NID、提取文件长度与哈希见 [inventory.json](../.tooling/bypass-check/final/registry/inventory.json)；`/lib/modules` 的新列表与旧固定列表逐字节一致。全部 392 个 KO 的扫描记录见 [scan.json](../.tooling/bypass-check/final/registry/scan.json)，共解析 18,110 个 section、646,489 个 symbol、2,614,545 个 RELA。每个 KO 都保留完整提取文件与原始 `dump.erofs --path` 元数据，校验器再次从固定镜像读取并与提取文件逐字节比较，未覆写这些 KO。

宿主工具固定身份：

| 工具 | SHA-256 | 使用方式 |
| --- | --- | --- |
| `dump.erofs` 1.9.4 | `2e1c2b2d2399183803c28bbf3e695d43db06083fb398be157ece48ce31c3ef0e` | `--ls`、`--path`、`--cat`；宿主 `DYLD_LIBRARY_PATH=/opt/homebrew/lib` |
| `llvm-objdump` 23.1.3 | `e4c0d646ab0133854426873bd4e308c5b3a3c31b028d22fcdc212da80aa6c6f1` | 按 section 与实际函数范围进行新鲜 AArch64 反汇编 |

精确符号 `mca_smartchg_if_ops_register` 出现于 7 个模块，其中 1 个定义/导出、6 个模块各有 1 个 `.text` `R_AARCH64_CALL26` 直接注册调用。扫描还对所有 `.data/.rodata` 中有符号且大小为 80 的静态对象进行结构匹配：槽号小于 6，其余原始字节为零，至少有一个回调 RELA，且所有 RELA 都指向 `.text` 函数、位于八个回调字段。共 12 个结构候选；6 个为真实 smart ops，另 6 个是 MSI、GPIO pinmux 或 IRQ domain ops 的形状匹配，没有精确 smart 注册符号或调用。各对象、函数目标和引用位置见 [structural-disposition.json](../.tooling/bypass-check/final/registry/structural-disposition.json)。形状相似对象没有被计为额外 smart 注册。

该阴性结论的边界是“这份固定镜像中的精确符号/RELA 与有符号静态对象”。它不覆盖其他分区、内建模块、匿名或运行时构造的表、经函数地址/kallsyms 间接注册、当前运行实例覆盖槽位等情形。

## 2. 六槽枚举与 ops 布局

[btf.json](../.tooling/bypass-check/final/registry/btf.json) 从 `mca_smart_charge.ko` 的完整 split `.BTF` 重新解码 108 条本地记录，并冻结每条记录的 section/file offset、原始字节、哈希与完整本地字符串池。没有将本地序号当作全局 type ID，也没有推断未知 kernel base 类型。

`mca_smartchg_if_charger_type` 位于 `.BTF+0x6c0`，文件偏移 71,187，记录 SHA-256 `d031a916e770defeacf5f784fd01c1fdb33ad411694736d7b9049db56699845f`：BUCK=0、WL_BUCK=1、QC=2、WL_QC=3、JEITA=4、THERMAL=5、END=6。

`mca_smartchg_if_ops` 位于 `.BTF+0x5dc`，文件偏移 70,959，大小 80，记录 SHA-256 `1db1ee80f8f55dddf222cca2f016634493cae583e8edd2e186523befca0556f7`。首两个字段的名称来自不可用的 base 字符串，证据保留 `unresolved_base`；其偏移分别为 `+0` 与 `+8`。槽号的 u32 使用与 context 指针用途由注册函数、probe 写入以及消费者加载进一步证明。其后八个本地名称可完整解析：

| 回调偏移 | 字段 | 非空槽位 |
| --- | --- | --- |
| `+0x10` | `set_delta_fv` | 1、2、3、4 |
| `+0x18` | `set_delta_ichg` | 2、3 |
| `+0x20` | `set_fcc` | 0、2 |
| `+0x28` | `set_soc_limit_sts` | 0、1、2、3 |
| `+0x30` | `set_pwr_boost_sts` | 0、2 |
| `+0x38` | `set_wls_super_sts` | 5 |
| `+0x40` | `set_wls_quiet_sts` | 1 |
| `+0x48` | `update_baa_para` | 2、3、4 |

这里不存在 owner/query/release/snapshot 类型的回调字段，不能把参数设置字段重新解释为这种契约。

## 3. 六个提供者与注册生命周期

完整路由见 [routes.json](../.tooling/bypass-check/final/registry/routes.json)。下面的地址均为 ET_REL **section 内偏移**，不是运行时地址；`.data`/`.text` 的 section index 与文件偏移同时保存在原始窗口中，避免多个 section 的地址 0 相互混淆。

| 槽位 | 提供者 | ops 对象 `.data` 偏移 | `.text` 注册调用 | 非空字段数 | SOC 字段 |
| --- | --- | --- | --- | --- | --- |
| 0 | `mca_strategy_buckchg.ko` | `0x168` | `0xb90` | 3 | callback `0x6288` |
| 1 | `mca_basic_wireless.ko` | `0x168` | `0x1a10` | 3 | callback `0x9ba4` |
| 2 | `mca_strategy_quickchg.ko` | `0x168` | `0x384` | 6 | callback `0xaa2c` |
| 3 | `mca_quick_wireless.ko` | `0x168` | `0x830` | 4 | callback `0x55f0` |
| 4 | `mca_buckchg_jeita.ko` | `0xf8` | `0x348` | 2 | `.data+0x120` 原始 64 位零，无 RELA |
| 5 | `mca_charger_thermal.ko` | `0x118` | `0x858` | 1 | `.data+0x140` 原始 64 位零，无 RELA |

六个完整 probe 均已冻结。每次注册调用前一条指令把该实例的 context 写入 ops `+8`，随后传入 ops 对象；调用后没有对注册函数的返回值进行检验。这里证明的是固定二进制中的调用位置，不能把 probe 存在升级为当前设备已执行注册成功。

注册函数 `mca_smartchg_if_ops_register` 的完整范围是 `.text+0x4..0x80`：

- `+0x10` 对空指针分支，失败返回 `-1`。
- `+0x14` 加载 u32 槽号，`+0x1c/+0x20` 接受无符号槽号 `<6`；其他槽号返回 `-1`。
- `+0x70` 执行 `str x8,[x9,x3,lsl #3]`，直接覆盖私有 `g_mca_smartchg_if_ops` 的对应指针，成功返回 0。
- 完整函数中没有检查旧槽是否占用、没有保存旧指针、没有所有者 token、引用计数、恢复入口或注销动作；没有同步原语调用。

`g_mca_smartchg_if_ops` 是 local `STT_OBJECT`，位于 `.bss+0`、大小 48。全部 392 个 KO 的精确扫描中，它仅在 `mca_smart_charge.ko` 定义，没有 `mca_smartchg_if_ops_unregister` 精确符号。

六个 remove 函数也已完整冻结：槽位 0、3、4 只返回 0；槽位 1、2、5 只调用 `mca_sysfs_remove_link_group` 后返回 0。它们没有 smart 注销、work 取消或硬件恢复调用。smart 自己的 remove 清理字符设备、sysfs 链接和通知注册，也没有清空上述六槽表或进行精确通路恢复。不能把卸载、remove 或重启当作插件的撤销实现。

## 4. 全部 19 个非空回调的作用

19 个回调均按真实 `STT_FUNC` 边界保存完整字节与新鲜反汇编。每一个非空指针均由 ops 对象的 `R_AARCH64_ABS64` 对应到实际 `.text` 函数；每一个空字段均同时检查原始零值及没有 RELA。

| 槽位/字段 | 完整函数入口 | 实际行为与撤销限制 |
| --- | --- | --- |
| 0/FCC | `0x6238` | 对 context `+0xf0` 的 votable 发起具名投票，输入为 0 时撤销该票；没有保存前值，忽略 vote 返回值 |
| 0/SOC | `0x6288` | 先写 context `+0x810`，再依 capability 和输入调用 `strategy_buckchg_process_soc_limit_change_more`（`.text+0x4918`），或操作两组投票/调度 work；函数命名和调用不证明物理旁路，具体通路与恢复限制承接 Stage3/4 |
| 0/boost | `0x63f0` | 写 context `+0x144`，不返回硬件状态 |
| 1/delta FV | `0x9af4` | 硬件条件下将输入上限压到 20，写 context `+0x10e8` |
| 1/SOC | `0x9ba4` | 先写 context `+0x1054`；gate 为 0 或输入为 0 时取消 work，并以本地 0 调 helper；缓存与 stepper 的差异承接 Stage4 P2 |
| 1/quiet | `0x9c24` | 写 context `+0x10f4`；gate 成立时调用普通无线充电参数 helper，失败/未生效不构成恢复确认 |
| 2/delta FV | `0xa920` | 同类硬件条件上限 20，写 context `+0x240` |
| 2/delta ICHG | `0xa9b0` | 写 context `+0x244` |
| 2/FCC | `0xaa0c` | 写 context `+0x364` |
| 2/SOC | `0xaa2c` | 对 context `+0x208` votable 发起 enable=1、value=输入的具名投票；输入 0 也不是恢复原投票快照 |
| 2/boost | `0xaa9c` | 写 context `+0x248` |
| 2/BAA | `0xaabc` | 根据提供的记录替换配置表，分配新数组后更新指针和 count，并释放旧数组；不是原值快照或通路撤销 |
| 3/delta FV | `0x5538` | 写 context `+0x2a0` |
| 3/delta ICHG | `0x5594` | 写 context `+0x2a4` |
| 3/SOC | `0x55f0` | 写 context `+0x3d9` 的 bool，并对 context `+0x20` votable 发起 enable=1、value=输入的投票 |
| 3/BAA | `0x566c` | 同类配置数组替换与旧数组释放；不是恢复事务 |
| 4/delta FV | `0x2248` | 同类硬件条件上限 20，写 context `+0x158` |
| 4/BAA | `0x22d0` | 对两组 20 字节输入记录检查 index，原地修改 48 字节配置行；设置 context `+0x160`，无撤销快照 |
| 5/wls super | `0x29c4` | 写 context `+0x778` 后调用温控限流 helper，忽略 helper 的执行结果；不是 owner 或电池通路状态查询 |

一般参数回调对空 context 返回 `-1`，对非空 context 返回 0；该 0 表示函数路径完成，不代表硬件已应用。BAA 回调还会跳过无效 index、忽略逐记录失败等情况；完整快速充电 BAA 函数存在 `fortify_panic` / `brk` 路径，因此更不能当作无副作用探针。这里没有调用它们。

JEITA 的辅助函数 `.text+0x2434` 完整冻结：输入 `+0xc/+0x10` 会覆盖配置行 `+0x14/+0x18`，并在 count 成立时修改行内最后一个 entry。数据原地改写而无原值保存。

温控 helper `mca_wireless_charger_thermal_handle_limit` 的完整范围 `.text+0x70..0x518` 也已冻结。它依全局实例、配置及模式选择限流级别，context `+0x778` 非零时选择另一限流字段；调用两次 `mca_strategy_func_process`，并处理十个 votable 的具名投票。某分支关闭这些票，另一分支更新 enable/value 并重跑 election。它没有独占 token、原投票快照或电池/系统通路读取。将 wls-super 参数改回 0 会重新走温控策略计算，不能等同于“按原状态撤销插件操作”。

## 5. 私有表的全部消费者与公开 API

[registry-accesses.json](../.tooling/bypass-check/final/registry/registry-accesses.json) 索引了私有 `.bss+0..0x2f` 全部 92 个 RELA。这些指令引用落在 8 个函数中，均已保存完整函数：

| 函数 | `.text` 入口 | 访问与处理 |
| --- | --- | --- |
| `mca_smartchg_if_ops_register` | `0x4` | 按槽号覆盖一个指针 |
| `smart_charge_fv_control` | `0x276c` | 遍历六槽 `+0x10`，跳过空回调；不汇总回调结果，最后更新缓存并返回 0 |
| `smart_charge_ichg_control` | `0x29fc` | 遍历六槽 `+0x18`；不汇总结果，非空 context 最后返回 0 |
| `smart_charge_set_fcc_control` | `0x2bc0` | 遍历六槽 `+0x20`；不汇总结果，非空 context 最后返回 0 |
| `smart_charge_set_soc_limit` | `0x2d7c` | 将输入 `&1` 后遍历六槽 `+0x28`；没有逐槽确认或失败回滚 |
| `smartcharging_handle_power_boost` | `0x113c` | 按模式选择配置参数，遍历六槽 `+0x30` |
| `smartcharging_receive_control_message` | `0x954` | 控制消息的 super/quiet 分支遍历六槽 `+0x38/+0x40`；还会改变其他算法参数 |
| `smart_charge_file_close` | `0x3364` | 符合条件时把共享缓冲区内 BAA 数据依序交给槽位 4、2、3 的 `+0x48`；close 不能视为无副作用或恢复句柄 |

以上消费者检查空对象/空回调后，通过 ops `+8` 取 context 调用函数指针。缺少逐槽成功确认、事务失败回滚和所有者隔离；多提供者共享广播不是独立插件控制契约。

完整模块实际 `__ksymtab` 只有 7 个导出：register、set/get scene、set/get board temperature、is extreme cold enabled、get limit SOC。四个 getter 读取策略 context 内的缓存/条件值，未访问六槽表，也未读取电池充电通路原状态或返回所有者。set scene 会改 scene、发事件并取消/重排 work；set board temperature 会写策略缓存。这些 setter 不是专用旁路撤销接口。

`smart_charge_check_bypass_status` 在该 ELF 中是 global `STT_FUNC`，但**没有对应 `__ksymtab/__kstrtab`**，不能称为模块导出 API。其完整函数 `.text+0x838..0x954` 仍已保存；“check” 名称不能消除其具名投票及状态改写副作用，既有副作用结论承接 [Stage4 night 报告](DADA_NIGHT_CONTRACT_STAGE4.md)。

## 6. 身份与最终停止边界

六个提供者和 registry 的镜像文件身份如下，全部来自固定镜像 `/lib/modules/`；文件 SHA 表示离线提取文件身份，不证明手机当前执行的字节或运行时表内容。

| KO | 大小 | SHA-256 |
| --- | --- | --- |
| `mca_strategy_buckchg.ko` | 130904 | `110c062e2b39d0603da94e926616cda207d768c4ec2698fd3ca41a2cd54d6a74` |
| `mca_basic_wireless.ko` | 199088 | `47eef298cd8dfabe4b5d51077c1b4f0bc8c8fdf3f66fc87c7f34faad617ced2e` |
| `mca_strategy_quickchg.ko` | 195576 | `1a9cc4157b3899496b0b6ade716fd9d5f852c8af2298be1545a9e1c86e8dc339` |
| `mca_quick_wireless.ko` | 116088 | `1886a2c3f2abf3254b7400568e8bbfa884b9f54f41265bbee03e9d716dfb976e` |
| `mca_buckchg_jeita.ko` | 54168 | `de6f293cbfc0c8bbe995ee3b26278380e6cd816f4ee4a7bb7f7e361de087f7a5` |
| `mca_charger_thermal.ko` | 70640 | `283a6abb965986c9137e90ee0310cfe07f7f32f5eb82d54e93641dd90585a37b` |
| `mca_smart_charge.ko` | 88544 | `9c85ed0c761e597df3a549ed25b9a9cfbbc9414fb7b38d2b6a6043bc787510af` |

本轮没有补读 JEITA/thermal 的 live Build ID。这两张固定 ops 表没有 SOC 回调，余下回调也不能形成独立控制与撤销契约；额外身份读数不会改变当前实施决策。已有 live Build ID、存储 SHA 和 sysfs 链接的边界仍承接 Stage4：Build ID 不证明未 patch 的运行字节，存储 SHA 不证明当前服务执行，链接不证明当前 registry，更不证明系统输入与电池功率分离。

至此，可以在授权范围内完成的注册表静态项已经结束：全镜像 KO 精确扫描、有符号 80 字节结构扫描、六槽枚举、全部 48 个回调字段、六个 probe/remove、19 个回调及相关 JEITA/thermal helper、smart 全部 35 个大小非零的实际 `STT_FUNC` 和私有表直接消费者、实际导出表均已闭合。作者校验器对实际函数符号集合与完整窗口集合做相等检查，包含 `.text` 之外的 init/exit 函数。继续无边界猜测节点名称或枚举新窗口，不能填补这些 OEM 共享入口的以下缺口：

1. 可安全调用的 userspace ABI、有效协议/生命周期和成功确认。
2. 插件独立所有权与原状态快照，精确撤销并与厂商并发策略协调。
3. 当前实例与通路的可核验对应，以及系统输入供电、电池充放电的电气证据。

这三个缺口交由 final ABI、power 及主审报告共同判定。这里的“原状态快照/精确撤销”仅描述本注册表 OEM 共享入口所欠缺的契约；其他路线采用插件 owner 与固定 inactive 条件进行功能恢复的方案，属于主审单独评估范围。本报告不授权新的手机控制试验。旧的四个 SOC 通路结论及 basic 无线缓存/stepper P2 维持 [Stage4 registry 报告](DADA_SOC_REGISTRY_STAGE4.md) 与 [其独立复核](DADA_STAGE4_REGISTRY_INDEPENDENT_REVIEW.md) 的冻结版本。

## 7. 交付与机械复核

[windows.json](../.tooling/bypass-check/final/registry/windows.json) 为 74 个代码窗口，其中 68 个完整真实函数、6 个明确标注为注册局部上下文；共 8,481 个 32 位指令字逐一与新鲜 LLVM 和原始 ELF 字节对应。[data-windows.json](../.tooling/bypass-check/final/registry/data-windows.json) 为 507 个数据窗口，含六张完整 ops、完整 smart `.BTF` 及所用原始字符串。

[verify_registry.py](../.tooling/bypass-check/final/registry/verify_registry.py) 复读全部 392 个镜像 KO、完整目录集合、候选 ELF 元数据、全部代码/数据窗口及 RELA、每个 LLVM 指令字、真实函数边界、空回调无重定位、六个 probe context-store、remove 调用集合、BTF、完整 registry 访问索引和注册函数关键指令。结果为 PASS 16,597 项，见 [verification.json](../.tooling/bypass-check/final/registry/verification.json)。这是作者机械复核，独立交叉审查另行交付。

[manifest.json](../.tooling/bypass-check/final/registry/manifest.json) 冻结本报告、所有新证据/脚本以及固定外部输入；manifest 不将自身纳入哈希，以避免自引用。旧 Stage0–4 文件仅作为冻结输入引用，没有修改。检查通过只说明证据完整和报告可复核，不表示设备控制或旁路供电验收通过。
