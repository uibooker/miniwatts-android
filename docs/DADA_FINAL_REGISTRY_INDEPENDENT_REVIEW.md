# DADA 最终注册表独立审查

日期：2026-10-13。结论：**未发现需要修正最终注册表报告的新增缺陷，静态证据审查通过。** 六个固定提供者、19 个非空回调与全部直接消费者均符合报告描述；槽位 4/5 的 SOC 字段确为空，未补出插件独立控制、查询或撤销契约。该通过不表示手机已完成旁路或恢复验收，真实控制仍不具备准入条件。

受审对象是 [最终报告](DADA_SOC_REGISTRY_FINAL.md)，SHA-256 `c7f84f94fb67fe43492d020a54a38401c5f2a1b238982cd39f2fc900febae984`，以及 [作者冻结 manifest](../.tooling/bypass-check/final/registry/manifest.json)，SHA-256 `df7985119ac0ec0d65e9af0a8f3084dd20ef7ba4002c60b1888d5eb92f2fbd55`。本次审查没有执行作者的提取、扫描、校验或冻结脚本，也没有改写受审报告、旧 Stage0–4 或产品。手机、ADB、控制、安装、目标程序执行和网络操作均为 0。

## 独立方法与覆盖

审查员新写 [ELF64 解析与复核脚本](../.tooling/bypass-check/final/registry-review/review_registry.py)，从 ELF header、section header、symbol table 与 RELA 的原始字节建立独立索引，未导入作者解析器。随后使用固定宿主 `dump.erofs` 重新遍历镜像目录树，逐一重新读取全部 KO；提取内容在内存中与作者冻结文件比较，没有覆写原始 KO。LLVM 每次明确指定 ET_REL section，完整函数以真实 `STT_FUNC` 边界解码，避免 `.text/.init.text/.exit.text` 同偏移混淆。

独立 [机械校验](../.tooling/bypass-check/final/registry-review/verification.json) 为 PASS 17,849 项，没有失败项。该数目含开始和结束时对作者冻结集合的两次哈希检查，不能当作 17,849 项独立物理结论。人工语义复核的全部回调、probe 与消费者记录见 [semantic-review.json](../.tooling/bypass-check/final/registry-review/semantic-review.json)。

| 复核项 | 独立结果 |
| --- | --- |
| EROFS 完整目录树 | 4 个目录；所有 392 个 `.ko` 均为普通文件，路径/NID 和原始目录输出一致 |
| 全部模块重新读取 | 392/392 从固定镜像读出的字节与冻结 KO 逐字节相等 |
| 全部 ELF 索引 | 18,110 sections、646,489 symbols、2,614,545 RELA，与作者逐模块记录一致 |
| 精确注册符号 | 7 个模块出现，1 个实际定义、6 个 `.text` CALL26 使用者 |
| 80 字节静态结构匹配 | 12 个候选，6 个实际 smart ops；另 6 个为 MSI、GPIO pinmux 或 IRQ domain 的形状匹配 |
| ops 字段 | 六槽 48 个字段：19 非空、29 空，SOC 回调仅槽位 0–3 共四个 |
| 私有注册表引用 | 92 个 RELA，全部落入报告的 8 个实际函数 |
| 实际导出表 | 7 个 `__ksymtab` 项，每项 function/name/namespace 三个 PREL32 重定位均重新核对 |
| split BTF | 完整 108 条本地记录及本地字符串池、80 字节 ops 和七个 enum 值一致 |
| fresh LLVM | 74 窗口，68 完整函数、6 个明确标记的局部注册窗口，8,481 指令字全量匹配原始字节 |
| 完整 smart 函数集合 | 35 个大小非零的实际 `STT_FUNC`，含 init/exit；集合与完整窗口集合相等 |
| 数据窗口 | 507 个原始数据窗口及其 section、file offset、RELA 一致 |

[独立 inventory](../.tooling/bypass-check/final/registry-review/inventory.json)、[完整扫描](../.tooling/bypass-check/final/registry-review/scan.json)、[代码窗口](../.tooling/bypass-check/final/registry-review/windows.json)、[数据窗口](../.tooling/bypass-check/final/registry-review/data-windows.json) 保存原始来源和新宿主命令。全部 74 个新鲜 LLVM stdout 与作者保存的同 section stdout 逐字节一致。

## 六槽与 19 个回调的语义核对

[独立 routes](../.tooling/bypass-check/final/registry-review/routes.json) 直接核对六张完整 80 字节对象。每个非空字段都是一个 `R_AARCH64_ABS64`，落到实际 `.text` 函数入口；每个空字段同时满足原始 64 位零和该 8 字节范围没有 RELA。JEITA 的 SOC 位置为 `.data+0x120`，thermal 为 `.data+0x140`，两个检查均通过。

| 槽位 | 全部非空回调的独立核对 |
| --- | --- |
| 0 buck | FCC 对 `ctx+0xf0` 的 `smartchg` 票操作，输入 0 令 enable=0；SOC 先写 `ctx+0x810`，再按能力/gate 处理 `soc_limit`、`csd_pulse` 和 work；boost 写 `ctx+0x144` |
| 1 basic wireless | FV 在指定 hwid 条件下取 signed `min(input,20)` 后写 `ctx+0x10e8`；SOC 先写原输入至 `ctx+0x1054`，gate 为零或输入为零时取消 work 并传本地零至 helper；quiet 写 `ctx+0x10f4`，gate 成立后调用 queue helper |
| 2 quick charge | FV、ICHG、FCC、boost 分别写 `ctx+0x240/+0x244/+0x364/+0x248`；SOC 对 `ctx+0x208` 的 `soc_limit` 票使用 enable=1/value=input；BAA 分配、替换配置数组、更新 count、释放旧数组 |
| 3 quick wireless | FV、ICHG 写 `ctx+0x2a0/+0x2a4`；SOC 写 `ctx+0x3d9` 的输入非零 bool，并对 `ctx+0x20` 的 `soc_limit` 票使用 enable=1/value=input；BAA 同类数组替换与旧数组释放 |
| 4 JEITA | FV 的指定硬件条件上限和 `ctx+0x158` 存储一致；BAA 对两组 20 字节输入记录检查 index 后修改 48 字节行，并设置 `ctx+0x160`；无 SOC 回调 |
| 5 thermal | super 先写 `ctx+0x778`，再调用 `0x70` 的温控 helper，随后返回零而未传播 helper 结果；无 SOC 回调 |

这些返回零、缓存写入和投票不能升级为硬件成功确认。quick charge 的 FCC/boost 等短函数仅存储；FCC0、SOC2、SOC3 的 vote 返回值随后被零覆盖。SOC2/3 的 value=0 不代表 enable=0，更不代表回放原先共享 OEM 票。无线 BAA 和有线 BAA 都存在 `fortify_panic/brk` 路径；JEITA helper 将输入 `+0xc/+0x10` 写入行 `+0x14/+0x18`，还可能覆盖最后一个 entry。它们均为有副作用的参数更新入口。

温控完整 helper `0x70..0x518` 的两个 `mca_strategy_func_process`、`ctx+0x778` 条件选择及十个 votable 处理均与报告一致。该参数恢复为零会重新计算 OEM 温控策略，未提供独立快照或恢复确认。basic 无线的缓存/stepper 差异保持 [Stage4 已冻结 P2](DADA_STAGE4_REGISTRY_INDEPENDENT_REVIEW.md)，本轮未把它改写成新发现或声称已修复。

## 注册生命周期、消费者与实际 API

六个完整 probe 在精确 CALL26 前一条指令把 context 写入 ops `+8`，接着传入相同 ops；调用后的指令未检查该注册返回值。六个 remove 的完整函数和直接 call 集合一致：槽位 0/3/4 只返回 0，槽位 1/2/5 只做 sysfs link 清理后返回 0。smart remove 做字符设备、class/sysfs 和通知注销，没有注册表清零、work 取消或硬件恢复。存在 probe、remove 或模块文件不证明当前设备已执行注册，更不能组成插件撤销事务。

注册函数 `mca_smartchg_if_ops_register .text+0x4..0x80` 先检查 null、读取 u32 槽号并用无符号 `<6` 判断，随后在 `+0x70` 直接覆盖 `.bss+0` 的六槽私有表。完整函数没有旧指针读取、占用检查、所有者 token、引用计数或同步调用。表符号为 local `STT_OBJECT`，大小 48。

[独立私表与 export 记录](../.tooling/bypass-check/final/registry-review/registry-accesses-and-exports.json) 重新定位 92 个引用及八个函数。FV/ICHG/FCC/SOC 分别使用字段 `+0x10/+0x18/+0x20/+0x28`；SOC 输入先 `&1`。boost 使用 `+0x30`；control message 的 super/quiet 分支使用 `+0x38/+0x40`；file close 按 4、2、3 槽处理 `+0x48` 的 BAA 数据，最终释放共享缓冲区。空对象/回调检查、context `+8` 加载与间接调用一致，没有完整逐槽结果汇总或失败回滚。consumer 中的 CFI 检查只限制间接调用类型，不能代替所有权和生命周期契约。

实际七项 export 由 `__ksymtab` 的原始三重定位解析，未把 global 函数误算为 export。`smart_charge_check_bypass_status` 是 global `STT_FUNC`，缺少对应 `__ksymtab/__kstrtab`；其完整函数包含 `mca_vote`、时间缓存和状态改写，不能当作纯读取探针。四个真正导出的 getter 读取 smart context 的缓存/条件；两个 setter 更新缓存、scene 或 work，不构成旁路撤销 API。

## 阴性结论和功能恢复的边界

全镜像精确扫描未找到 `mca_smartchg_if_ops_unregister`。结构扫描的额外六对象是其他子系统 ops 的形状匹配，没有精确 smart 注册调用；不能据此增加 charger 槽位。这个阴性结论只覆盖**受审固定包中的精确符号、RELA 和有符号静态对象**。匿名或动态表、函数地址/间接注册、builtin、其他分区和当前运行实例覆盖仍在边界之外。

split BTF 本地字符串基址由首个有名本地结构与池起点重新推断，108 条记录一致；没有 kernel base 字符串或类型。ops 首两个成员名字仍是 `unresolved_base:993/633`；偏移及用途由指令核对，不把本地 ordinal 当成 global type ID。

作者末段已明确：原状态快照/精确撤销的缺口针对这组共享 OEM 接口。另一条经审计的插件固定 owner 路线可以要求恢复到**功能 inactive**，允许 owner 记录继续存放，同时保留其他 OEM 动态票和当前合法策略。本审查没有把“必须删除记录”或“强制回放其他票/旧物理状态”加入通用要求；目前这些 OEM callback 使用的固定 `smartchg/soc_limit` 名称也不能据此宣称是插件独占 owner。

本轮审查结束。报告可作为静态注册表证据交付，不能据此开启真实控制、安装可写后端或宣称电气效果。作者 10 个输入和 1,679 个 artifact 的身份在复核开始及结束时一致；新审查文件由 [独审 manifest](../.tooling/bypass-check/final/registry-review/manifest.json) 冻结。
