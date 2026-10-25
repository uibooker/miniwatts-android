# dada MCA 独立 owner 与内核适配路线最终判断

日期：2026-10-13。范围：固定公开源码快照、既有 MCA ELF、当前系统固件包内的 392 份模块，以及前四阶段证据。子任务手机／ADB操作为 0；没有写节点、调用内核函数、加载驱动、运行下载的代码、修改插件或生成可加载内核扩展。

工件路径中的 `final/abi`、`stage1` 等均以仓库 `.tooling/bypass-check/` 为前缀。

**本轮对可部署的独立 owner 后端作 NO-GO 判断。** MCA 内核已经有 `enabled=0` 释放参与权的机制，并有强制重放回调的 `mca_rerun_election`；固定 owner 释放后保留一个 inactive 槽位可以满足功能恢复契约，缺 remove-client 不是否决理由。现有证据还不能把一个具体的 LKM／KPM 实现证明为安全：实际 voter 与驱动／回调上下文的绑定、跨解绑代际的有效性、允许睡眠的执行与锁顺序，以及下游完成／恢复契约仍未闭合。即使将普通 opaque 函数的调用约定视为已闭合，后面这些条件也独立阻止实施。[E1][E2][H1]

这不是“没有任何可能方案”或“全球找不到 MCA 源码”的结论。本轮界定候选中的成功、失败和未覆盖范围如下，不再用下一轮泛查作为本轮交付。

## 1. 源码检索完成范围

沿用当前基准：Xiaomi **24129PN74C／dada**、**OS3.0.303.0.WOCCNXM**、运行内核 **6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k**。磁盘 MCA 模块另标注 6.6.57 系列 vermagic；前阶段 live build-id 对应支持这些 MCA ELF 的构建身份，不消除新扩展的类型、配置或加载条件。392 包内模块的检索范围也不等于 392 份都已完成 live 对应。[B1][B2]

新的 GitHub `sm8750 modules in:name,description` 仓库发现请求 HTTP200，`total_count=32`、`incomplete_results=false`。其中新增一个有直接 Xiaomi 模块依据的候选 `lolipuru/kernel_xiaomi_sm8750-modules`，描述是 bixi 测试仓库，不能叫 dada 当前构建。没有下载其余 32 个名字相近的全厂商仓库。[S1]

GitHub 固定 tree／ref API 随后返回 HTTP403 rate limit，因此对四个已界定 Xiaomi 模块候选使用公开 `git ls-remote` 固定 ref、codeload 固定提交快照作为替代。四份快照均 HTTP200、退出码0，完整解析压缩归档并读取、哈希、检索每个普通文件的完整字节；保存全部成员／符号链接清单。精确标识符边界检索三个名字：`mca_vote`、`mca_create_votable`、`mca_adsp_glink_write_prop`。[S2]

| 仓库／固定提交 | 完整成员数 | 普通文件数 | 符号链接数 | 三个精确名字的普通文件命中 |
| --- | ---: | ---: | ---: | --- |
| [lolipuru SM8750](https://github.com/lolipuru/kernel_xiaomi_sm8750-modules/tree/37e1dc07d12a2853c4ae783a676629d49c82da6c) `37e1dc07d12a2853c4ae783a676629d49c82da6c` | 10,333 | 9,116 | 6 | 0 |
| [cnmrlin SM8650](https://github.com/cnmrlin/android_kernel_xiaomi_sm8650-modules/tree/875428614b3dfcfb508994d51e4d8302ab294e93) `875428614b3dfcfb508994d51e4d8302ab294e93` | 10,309 | 9,107 | 13 | 0 |
| [kmiit SM8650](https://github.com/kmiit/android_kernel_xiaomi_sm8650-modules/tree/d88e7df7ff7159ad670b7e5b0ee9d3bfc7325442) `d88e7df7ff7159ad670b7e5b0ee9d3bfc7325442` | 10,109 | 8,911 | 13 | 0 |
| [Xiaomi-SM8650](https://github.com/Xiaomi-SM8650/android_kernel_xiaomi_sm8650-modules/tree/974789eb54a7e5339d6a7c5ce12abfe81b821b0f) `974789eb54a7e5339d6a7c5ce12abfe81b821b0f` | 9,226 | 8,091 | 16 | 0 |

上述 **35,225 个普通文件** 的结果替代前三阶段仅检查 root／qcom 直接目录的较弱范围。唯一含 `mca` 子串的路径都是音频 `msmcal-hwdep.h`，与 MCA 充电接口无关。外部 symlink 目标及子模块内容没有取得；这些快照与当前 dada ROM／驱动均无新增精确匹配证明，不能从限定阴性结果推出全球源码不存在。[S2]

其余已有来源不重复扫描：[B1][B3]

- MiCode dada-v-oss 固定 `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5` 的核心版本是6.6.30，MCA driver／include 是指向 `vendor/xiaomi/proprietary/mca` 的链接；明确路径读取没有交付目标 C／头文件。旧核心递归树 `truncated=true`，不作为全仓库阴性证据。
- piano-LAB SM8750 modules 固定 `09037d31e562e4335ac53064c376dfbe4744d8e2` 的旧递归树 `truncated=false`、11,037项，只有无关音频路径；这是完整路径范围的证据，未把它改称全文件内容检索。
- 本轮 grep.app 的三个精确名字请求均 HTTP429／验证页面，Sourcegraph 三项均 HTTP403。旧 GitHub unauthenticated code API 是401。它们没有提供可审计代码候选，不能记录为“0代码命中”。[S3]

全部 URL、ref、HTTP／进程状态、原响应、响应大小和 SHA256 保存在 `final/abi/public-requests/`；快照完整清单与逐文件 SHA256 在 `*.inventory.json`。没有执行任何下载内容。

## 2. exact export、owner 释放、查询与强制重试

本次重新按 ELF section index、文件偏移和 `.symtab` 的完整函数边界取得 common 12个完整函数／**1,072 个指令字**；包内 owner 驱动8个完整函数／**1,353 个指令字**。所有指令字与原始 ELF 字节一致，保存完整 RELA 及来源哈希，不以 ET_REL 中重叠的虚拟地址猜段。[E1][E2]

`mca_common.ko` SHA256 是 `9ae45a000698215fe3cddbef0ce2f19a307cb1d514e0f4fd7f40358c235a5d9e`。实际 `__ksymtab` 是18个 MCA export，相关 CRC：`mca_vote=507f5e76`、`mca_rerun_election=e61c2488`、`mca_find_votable=79cd0ce7`、`mca_create_votable=e7845e6f`、`mca_destroy_votable=7f55289d`。有 lock／unlock、client／effective getters、override API；该导出表没有 owner-presence-without-allocation、voter get／put／pin 或 remove-client。部分 CRC 是准确证据，不能替代全部构建输出或类型证明。[E1]

| 条件 | 本次可确认行为 | 对受限后端的含义 |
| --- | --- | --- |
| 固定 owner 的申请／释放 | vote 可设置 enabled低位；32槽上限。各普通聚合分支跳过 disabled client；名字由 kstrdup 一次保存，释放不删除名字。 | 使用固定名字及 `enabled=0` 的功能恢复是可行机制；不要每次随机申请名字。没有 remove-client 不妨碍这一契约。 |
| 同票重试 | 已初始化时，相同 enabled／value 可在 `.text+0x790..0x7c0` 返回0；聚合有效值／client 都相同也可跳过回调。 | 再写一次相同票不能证明失败硬件被重试。 |
| callback错误 | client 先在 `+0x7d4/+0x7d8` 更新，effective 先在 `+0xa18/+0xa1c` 更新，`+0xbf0` 后才调用callback；保存返回值，错误路径日志后返回，不回滚上述缓存。 | journal 必须区分软件票、callback返回与硬件／远端完成；错误后不能只反向投票并宣布恢复。 |
| 强制重放 | `mca_rerun_election +0xd8c` 获取对象mutex，按force／override／cached effective／default取值，若callback存在则直接调用并返回其结果；没有同票短路。 | 有真正的强制callback入口，不能声称没有重试机制。它重放当前结果，不重新扫描客户聚合，也不移除票、不回滚其他owner。 |
| 锁 | vote／rerun 在内部获取对象mutex，callback同步在该mutex内执行。 | 不能先外部 `mca_lock_votable` 再调用这两者；会再次获取同一mutex。外部设备锁与下游锁顺序仍须证明。 |
| client getter | unknown owner 时 locked／非locked is-enabled 和 get-vote 均可 kstrdup，占用槽位。is-enabled 的false不能区分 absent、inactive、无槽／分配失败；某些非OR voter 的inactive get-vote返回-22，不能读取原stored value。 | 不能把第一次按名字查询宣传为纯只读且无分配快照。 |

固定 inactive 契约允许一种较窄的设计：**先在受控初始化中明确预留一个固定 owner**，把该动作记录为 mutation；同一 voter 代际已成功预留、名字互斥归属已保证后，后续 getter 命中现有槽位，不需要再分配。释放只要求 inactive，可不恢复 inactive stored value。这里不把 getter 第一次分配单独当作所有方案的否决理由。但现有 getter 不报告 `present/generation`，而初始化成功、owner独占与代际有效性目前没有受限用户接口保证；不能从getter的false／-22倒推这些条件。[E1]

预留本身仍是vote：尚未初始化的voter可按default进行回调，不能把enabled0的初始化宣传为无硬件动作。[E1]

status show 在对象mutex内读取现有票并 seq_printf，没有调用硬件或分配 client 名字，可为已有 debugfs 入口提供观测证据；它没有提供跨 query／apply 的事务或 voter 身份绑定。本子任务没有挂载／读取手机 debugfs。[E1]

## 3. 对象生命周期：module／device引用能解决什么

common 的完整 find 返回方式：全局链表 spinlock 内按名字查找，解锁后返回原始对象指针，没有在该函数取得对象引用。destroy 在全局锁下摘链，解锁后清 debugfs、释放 client名字及对象，没有获取 vote mutex或等候该锁持有者。仅把 find 与 lock 组合不能自行构成生命周期保护。[E1]

本次对同一包的 **392 份 ELF** 完整符号与 RELA 表检查精确 `mca_find_votable/create/destroy` 引用。仅有两个 external CALL26 destroy callsite，均位于 `mca_business_misc_comp:business_votable_init` 的错误分支，调用前置 `x0=0`；没有把它们误写成正常销毁 buck／quick voter 的路径。范围不覆盖 built-in 内核、运行时patch或未取得的模块。[E2]

同时，buck remove 完整8字节仅 return0；quick remove 只调用 sysfs remove-link-group再return0，没有在这些函数调用 destroy。因此不能宣称 buck／quick 一卸载便销毁 voter。可确认的另一个问题是：buck probe 用 `devm_kmalloc` 分配ctx并在create-votable传入；quick probe同样 devm 分配ctx，再把ctx传给create-voter，`quick_chg_en` 绑定 callback `+0x4238`。voter仍存在不证明callback ctx在解绑后仍有效。[E2]

common create在已存在同名voter时返回旧指针（`+0xf70..+0xff4`），没有把它重新绑定到新的ctx。因此即使device当前再次处于bound状态，也不能仅据名字查找断言voter属于这次probe的ctx代际。[E1]

引用方案的判断以 primary driver-core为依据，但这份 Google同版本基线仍不是运行f9a构建的精确源码：[G1]

- 普通 LKM 的合法 symbol dependency／`try_module_get` 可以保护 provider 的代码卸载；它不自动保护另一个设备的 devm ctx或voter的语义寿命。
- `get_device` 保持 `struct device` 本身的引用；基线 driver detach 仍会 `devres_release_all`，释放托管资源并清 drvdata。因此仅get_device不足以让回调ctx持续有效。
- `device_lock` 与基线 `__device_release_driver` 的解绑互斥相容。若确切取得所属device，在锁内确认driver／ctx／voter属于同一代际，并在整个同步callback操作期间保持适当的module引用与设备锁，**可能解决该操作期间的解绑问题**。不能因当前没有专用refcount export便否认这种合理路线。
- 这需要真正的owner定位和存活验证，而不是按名字find后猜ctx或device指针。`device_lock` 是 `include/linux/device.h` 中访问device mutex的inline，不是当前材料提供的通用opaque MCA export；外部KPM不能省略准确device类型或等效原生wrapper。还要证明跨buck／quick／provider锁及 cancel-work／ADSP等待的顺序不会死锁。
- 在同版本基线，`__symbol_get` 明确只接受 GPL_ONLY export并取得provider引用；common实际导出节是 `__ksymtab`。不能假设这一具体基线的普通 `__symbol_get("mca_vote")` 自然可用。普通LKM导入、显式合法模块引用或由owner提供session wrapper仍是不同的可评估路线；未在手机试调用它们。[G2][E1]

opaque调用可避免把整个 `mca_votable` 私有结构写进外部模块。ELF、export及已有split BTF提供参数数量、指针与标量传递的强证据；未解析的base BTF仍不当成完整C／CFI原型。**不要求所有opaque变体一律拥有全部私有布局，也不以整套源码缺失单项否决。** 可接受的替代材料是验证过的准确原型及当前ABI依据，加上上述实际owner/session契约。即使普通调用ABI先行通过，ctx／锁／完成条件也仍须满足。[B1][E1]

## 4. SukiSU Ultra KPM 替代路线

本轮完整取得 SukiSU 官方固定提交 `223897c72cc5c23b6fea84d6542aa8ef24459998` 的源码归档：976成员／750普通文件。Kconfig显示KPM可选、默认n；Kbuild条件编入KPM对象。`kernel/kpm/kpm.c` 的加载／卸载／控制函数实际是stub，官方 `docs/README.md:49` 明确指向配套 [ShirkNeko/SukiSU_KernelPatch_patch](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch)。这不是当前手机不支持KPM的证明。[K1]

仅追踪该官方指定项目，固定其公开HEAD为 **`c5f0785dc7fac22a846eecc6392f34198d8e18b5`**，完整归档352成员、514,812字节。其 `kernel/patch/sukisu/sukisu.c` hook上述stub转至自有load／control函数；`kernel/patch/module/module.c` 自行布局、解析、重定位ELF并调用KPM init。普通LKM的完整Module.symvers并非这个自有加载流程的统一必要条件，不能据传统构建缺件否掉所有KPM。[K2]

但这个**固定公开组合**还有明确边界：[K1][K2][G3]

1. undefined import经过 `simplify_symbols -> symbol_lookup_name -> sukisu_compact_find_symbol -> find_kernel_symbol_exact`。Suki的6.6分支走kernel-only `kallsyms_on_each_match_symbol`；fallback也明确拒绝 `module_name`。不能据此承诺默认解析已加载MCA模块的export。普通 `kallsyms_lookup_name` 的基线实现可以转module查找，所以设计显式模块符号获取仍可能；获取地址本身不取得模块／voter／ctx引用。
2. 自有symbol resolver／loader不为MCA对象提供安全session或自动Linux模块依赖。使用确切模块导出、合法provider pin、所属device验证可以是适配设计；当前设备实际KPM版本、补丁身份、helper导出及类型未证明，不能把上游HEAD与手机管理器版本／LKM标签作对应。
3. 固定源码 `module_control0/1` 在 `rcu_read_lock` 区间直接调用ctl；对应headers将RCU helper接到内核函数。MCA vote／rerun获取mutex，quick callback还会cancel-work，provider可等待远端，不能默认直接放进允许睡眠的入口。需要已验证的分发上下文，或把操作交给合法睡眠worker，并保障worker／KPM退出与卸载同步。退出释放owner可能再次等待硬件，不能只留一个exit反向vote。
4. KPM不会补齐MCA private ABI、voter代际、callbackctx有效性、锁顺序或硬件完成。没有下载或执行补丁工具，没有修改内核或使用root probe这些能力。

## 5. 强制回调仍不等于硬件恢复

common 能返回自己实际收到的callback rc；下游是否保存错误必须逐层看。本次完整callback窗口提供以下区分：[E2][H1]

| 路径 | callback行为 | release／rerun仍缺的确认 |
| --- | --- | --- |
| buck `strategy_buckchg_enable +0x3ee0` | 保留role0 `platform_class_buckchg_ops_set_chg` rc；配置需要role1时再调用并OR其rc。 | errno没有被这一层统一置0，但组合结果不是每个role的完整状态；远端2003附带动作／完成及真实系统输入供电仍须验证。 |
| quick `mca_quick_charge_chg_en_voter_cb +0x4238` | 先写ctx缓存。有效值非0直接跳return0；值0才cancel delayed work、调用stop、可能撤override，最后仍强制return0。 | 硬件／stop／override错误可能被吞；释放后有效值恢复1不会在这一回调同步重启硬件。强制rerun返回0也不能宣布quick通路已恢复。 |

已有远端2003分析还区分主charge-enable返回与附带callback动作、超时后是否实际执行、恢复是否成对；本报告不重复把ACK、软件effective或cached getter认作寄存器／电气证明。[H1]

## 6. 路线决定与最少外部缺件

| 路线 | 本轮判断 | 具体原因／可改变判断的材料 |
| --- | --- | --- |
| 当前纯用户态charge_enable等入口 | NO-GO：不能达到独立owner功能恢复 | 已定位setter把enabled固定1，没有受限独立owner及enabled0入口。该结论限已检查入口。 |
| 在MCA／实际owner内部增加受限session接口 | 条件可行，当前NO-GO | 原生接口可以封装owner、query、锁与解绑有效性；缺匹配实现／契约及完成恢复证明。源码是一个解决办法，不是所有路线唯一办法。 |
| 普通LKM仅用准确opaque exports | 条件可行，当前NO-GO | enabled0／rerun机制已有；可避免voter私有布局。仍缺可成立的所属device／ctx代际保护和完整callback错误／恢复契约；该LKM另需匹配构建与加载资料。 |
| KPM／受限kernel shim | 条件可行，当前NO-GO | 不因传统symvers单项否决；需实际环境证明、显式安全模块符号获取／pin及合法睡眠分发，同样受ctx／锁／完成gate约束。固定上游默认lookup的限制不代表所有KPM无解。 |
| force／override／直接调用未知内核地址 | 不作为独立owner方案 | 影响其他owner或缺准确接口与恢复契约；没有生成这类实现或试写。 |

能改变当前决定的最少资料是三组，不要求同时补齐每条路线全部材料：

1. **可验证的MCA session契约**：准确opaque原型／ABI依据，以及实际voter所属驱动、device、callbackctx的同代际关联和存活验证；允许睡眠的执行、锁顺序、解绑／worker退出同步；固定owner一次性预留、查询有效性、enabled0释放。可以是匹配MCA／owner源码与头文件，也可以是厂商或构建方交付的已审阅原生受限接口及对应证据；只给符号地址或某个base BTF片段不足。
2. **选定的一条扩展环境证明**：若走普通LKM，提供当前内核对应config／构建输出及可用加载策略；若走KPM，提供实际已安装KPM／patch身份和与此内核匹配的符号获取、模块引用、调度／退出能力。上游默认配置、root、管理器LKM标签不能替代；不要求为了确认而在手机加载未知扩展。
3. **完成与恢复证据**：buck／quick／provider各role真实返回与状态契约，2003主／附带动作可审计完成和配对恢复，以及可区分“暂停电池充电且保留系统输入供电”的观测条件。单纯enabled0软件恢复或主ACK仍不足。此项即使前两组齐备也独立存在。[H1]

本轮没有通过上述门槛的具体安全后端，故不生成可加载包、不以连接手机试验补缺失契约。现有固定候选检索、export与恢复机制、解绑／ctx、KPM替代路线及下游错误边界已完成收口；需要的是以上有明确身份与能力的外部材料，不是继续扩大无依据的仓库或节点扫描。

## 证据定位

- [S1] `final/abi/public-requests/sm8750-modules-discovery.*`：公开GitHub repository discovery完整响应与失败sandbox记录。
- [S2] `final/abi/fixed-source-summary.json`、四份 `*-fixed-archive.inventory.json`：URL／commit、HTTP响应哈希、完整成员与普通文件内容检索；`lolipuru-sm8750-ls-remote.*` 固定新候选ref。
- [S3] `final/abi/public-requests/*grepapp*`、`*sourcegraph*`、`*full-tree*`：明确访问限制；旧401范围见 [DADA_MCA_SOURCE_STAGE2.md](DADA_MCA_SOURCE_STAGE2.md)。
- [E1] `final/abi/common-metadata.json`、`common-windows.json` 及12份 fresh disasm：18 exact export／CRC与1,072指令字；原common ELF SHA256固定如上。
- [E2] `final/abi/lifetime-reference-scan.json`、`owner-lifetime-windows.json` 及8份fresh disasm：392份包内ELF准确输入哈希、2个NULL destroy callsite及1,353指令字。
- [B1] [DADA_BUILD_SOURCE_STAGE1.md](DADA_BUILD_SOURCE_STAGE1.md)：MiCode明确路径、split BTF／当前构建缺项、同版本Google来源。
- [B2] 既有 `stage4/identity-review/manifest.json` 中18份KO输入；当前身份核对由主任务独立保存，不把本次包内scan当live身份采集。
- [B3] [DADA_MCA_SOURCE_STAGE3.md](DADA_MCA_SOURCE_STAGE3.md)、`github-research/kernel/piano-sm8750-modules-tree.json`：旧固定ref、完整树与此前目录检索的边界。
- [G1] Google固定基线 `d415a2d4bd29d2e3d5b138d72fe606034ebdef44`：[driver detach](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/drivers/base/dd.c#1261)、[device_lock/get_device](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/include/linux/device.h#1001)、[devres_release_all](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/drivers/base/devres.c#511)；原base64响应及解码文件在 `final/abi/gki-driver-core-*`。
- [G2] 同一Google基线 [module引用／symbol_get](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/kernel/module/main.c#1315)，沿用 `stage1/build/google-kernel_module_main.c`。
- [G3] 同一Google基线 [kallsyms lookup范围](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/kernel/kallsyms.c#264)，`final/abi/gki-kallsyms.c` 与原响应。
- [K1] SukiSU官方固定提交：[Kconfig](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/kernel/Kconfig#L29)、[官方KPM文档](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/docs/README.md#L44)、[stub](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/kernel/kpm/kpm.c#L54)、[compact](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/kernel/kpm/compact.c#L85)、[kernel-only resolver](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/kernel/infra/symbol_resolver.c#L57)；完整归档及来源清单在 `final/abi/sukisu-fixed-archive.inventory.json`。
- [K2] 官方指定配套项目固定 `c5f0785dc7fac22a846eecc6392f34198d8e18b5`：[stub hook](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch/blob/c5f0785dc7fac22a846eecc6392f34198d8e18b5/kernel/patch/sukisu/sukisu.c#L27)、[自有ELF加载／控制](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch/blob/c5f0785dc7fac22a846eecc6392f34198d8e18b5/kernel/patch/module/module.c#L399)、[symbol resolver](https://github.com/ShirkNeko/SukiSU_KernelPatch_patch/blob/c5f0785dc7fac22a846eecc6392f34198d8e18b5/kernel/base/symbol.c#L45)。完整归档、ref／HTTP／SHA256记录在 `final/abi/public-requests/sukisu-kernelpatch-*`。
- [H1] [DADA_CHARGE_CONTROL_CHAIN.md](DADA_CHARGE_CONTROL_CHAIN.md)、[DADA_NEXT_SIDE_EFFECT_RESEARCH.md](DADA_NEXT_SIDE_EFFECT_RESEARCH.md)、[DADA_NIGHT_CONTRACT_STAGE4.md](DADA_NIGHT_CONTRACT_STAGE4.md)：普通控制链、远端主／附带动作及已收口夜充契约。历史文档中的严格absent恢复目标不作为本阶段固定inactive契约的额外门槛。

机器可审计证据、来源完整性和本报告由 `final/abi/manifest.json` 冻结；该manifest不能把未证明事项升级为已实现功能。
