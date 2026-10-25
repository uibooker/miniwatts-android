# dada 第一阶段：匹配 MCA 源码与构建资料

日期：2026-10-13。执行依据：[DADA_IMPLEMENTATION_NEXT_STEPS.md](DADA_IMPLEMENTATION_NEXT_STEPS.md) 第一阶段。范围限定三个有依据的官方来源路线，仅使用主机已有工件和公开网络；没有新手机读取、ADB、驱动加载、寄存器访问、构建或执行上游脚本，也没有修改插件或编写可加载扩展。

## 阶段判断

**目前未取得足以安全修改匹配 MCA 驱动或构建受限控制接口的完整材料。** 已有本机 MCA ELF、加载 build-id 对应、部分类型和符号 CRC；新增取得官方源码中的 MCA 构建入口、同版本 Google GKI 基线的模块校验规则与 ABI 列表、SukiSU 官方构建类型定义。这些资料可以列清缺项，不能消除匹配 MCA C 源码、完整头文件、生命周期契约、当前内核构建配置/输出和实际加载策略的缺口。

本阶段接受固定插件 owner 在释放后保留 **inactive 槽位** 的功能恢复契约；不以 remove-client 为第一版前提。现有内核 `mca_vote(enabled=0)` 已有局部证据，但用户入口没有该参数。新增受限入口仍需要准确类型、无分配查询、固定 owner 和对象引用/锁协议，也仍需确认远端硬件副作用恢复。减少恢复目标没有补齐这些材料。[L1][L2]

这不是“系统没有任何源码来源”的结论。下面的未取得与不匹配均限定于已检查工件、固定提交和请求路径。

## 匹配基准与已有本机资料

基准是已有 `device-root-inventory.txt` 的前5行：型号 **24129PN74C / dada**，系统增量 **OS3.0.303.0.WOCCNXM**，运行内核 **6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k**；已有其他取证记录为 Android16。Android 系统版本与内核分支字符串分别记录，不从它们推断谁替换了内核。[L0]

对本地 `mca_common.ko`、`mca_strategy_buckchg.ko`、`mca_charge_interface.ko` 作 ELF 元数据核对，结果见 `stage1/build/local-module-build-audit.json`：

- 三者均是 AArch64 磁盘模块，modinfo 为 GPL v2、`intree=Y`，vermagic 是 **6.6.57-android15-8-g6f1d969db78f-4k SMP preempt mod_unload modversions aarch64**。`intree=Y` 是编译元数据，不表示 built-in；已有 modules.load 中出现这些 .ko，已有运行记录也列为加载模块。[L0][L3]
- `.comment` 含 Android clang18.0.0、预编译包11368308、r510928 和 llvm-project `477610d4d0d988e69dbc3fae4fe86bff3f07f2b5` 标识。这证明磁盘 ELF 的编译器标记，不证明当前运行内核全部工具链/编译选项。[L3]
- common 有18个 MCA 导出 CRC；抽取的 common消费者共有8项可与该 provider 对照，全部相同。三个模块分别有31/108/21个 `__versions` 导入项。这只是这些磁盘模块之间的一致性，**不是完整 Module.symvers，也不是现场加载承诺**。[L3]
- 已有 live GNU build-id note 与磁盘 ELF 对应包含这三份模块，共10项；该证据支持已分析 MCA 构建身份，不能用于批准一个新扩展的 ABI/加载，也不证明远端 firmware 内存身份或无运行时改写。[L4]
- 三份本地模块均没有标准尾部签名标记；这既不能单独证明现场允许任意未签名模块，也不能单独证明签名是必须条件。实际策略仍未取得。[L3]

## 三条官方来源路线

### 1. MiCode 的 dada 设备发布：相关，但不匹配当前构建

仅作一次最新官方 ref 复核，查询 README 和 `refs/heads/dada*`：返回 README `16d89a630ea8752c84bf4d76201964524d620616` 与 dada-v-oss `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5`，没有额外 dada 命名分支。此结果只覆盖该仓库与命名模式，不排除不同仓库或不同命名的发布。[M0]

README 仍将 dada-v-oss 对应 Xiaomi15/15Pro、Android V、qcom-LA.VENDOR.15.4.0.r1-08900-r1.0.r1_00022.1。新增读出的该固定提交根 Makefile 第2-4行标注 **6.6.30**，不能叫当前运行6.6.77或已加载 MCA 6.6.57 的精确源码。[M1]

实际 MCA 构建边界已取得：[M2]

- `drivers/power/supply/Makefile:115`：`CONFIG_XM_POWER_SUPPLY` 包含 `mca/`。
- `drivers/power/supply/Kconfig:978`：source `drivers/power/supply/mca/Kconfig`。
- `drivers/power/supply/mca` 内容仍是55字节链接 `../../../../../vendor/xiaomi/proprietary/mca/driver/mca`；已有固定树记录 mode120000。链接和外层 Kconfig/Makefile没有交付目标目录中的 Kconfig、Makefile、C 实现和头文件。

取得该发布的 gki_defconfig、build.config.common/aarch64/gki、modules.rst 和加载框架，属于 **可取得但不匹配的基线资料**。其 build.config.common 声明 android15-6.6、KMI_GENERATION=8；gki_defconfig 设置 MODULES/MODVERSIONS/CFI/MODULE_SIG/MODULE_SIG_PROTECT。这些是公开默认配置，不能替代当前手机的完整最终 .config 或 vendor fragment。[M3]

没有因为旧递归树截断或某个文件未命中而推断整个仓库没有其他材料。本次直接读取上述明确路径，MCA 目录仍未提供实际源码。

### 2. Google Android15 GKI：同6.6.77基线可取得，精确构建未取得

理由：当前 uname 明确带 android15-8，Google官方 Android15 6.6 能提供 GKI/KMI/模块校验的 primary source。精确短 hash 请求 `kernel/common/+/f9a1d4bd8353?format=JSON` 返回404；这只说明该次 **kernel/common Gitiles路径** 未解析出该提交，不说明所有 source mirrors、其他仓库或私有构建都没有该源码。[G0]

固定查询到 android15-6.6 分支 head `0670949d6852a5c556e94abd6e62b945e30d41db`，另选官方 tag android15-6.6-2025-03_r1，其 tag object 为 `4c3ba252b1f3566947076b88aac2fb47422dd549`，Gitiles解析实际 commit **d415a2d4bd29d2e3d5b138d72fe606034ebdef44**。该版本 Makefile 标注6.6.77。后续源码均固定在这个实际 commit，避免把 tag object 和 commit 混为一谈。[G1]

取得这个同版本基线的 gki_defconfig、r510928工具链常量、build.config.common、module main/signing/version、vermagic头，以及完整 `android/` 目录响应与四份符号列表：通用 abi_gki_aarch64、xiaomi、xiaomi2、protected exports。[G2][G3] 符号列表不是实际构建的 Module.symvers/CRC 文件；其可取得不表示匹配当前 hash、vendor MCA或 SukiSU 加载环境。此次读取的四份列表无 `mca_` 项，仅说明它们没有提供目标 vendor MCA 导出契约，不扩大为全部 GKI/厂商实现都没有。

两个与已有现象相关但不能升级为现场承诺的机制：[G4]

- `kernel/module/version.c:79-89`：has_crcs 时 `same_magic` 忽略 vermagic 第一个空格前的版本字符串，再比剩余字段；同文件13-55逐符号比较 CRC。旧vermagic MCA 与新内核同时存在，与这种机制相容，不必据版本数字否认已有build-id对应；也不能仅据剩余字段相同批准新扩展。
- `signing.c:27-45/126-139`：signature enforcement 可以来自配置/运行状态；CONFIG_MODULE_SIG_PROTECT 条件下某类未签名模块不会仅因未签名被此处拒绝。`main.c:1157-1179/1347-1378` 还有 vendor导入/受保护导出限制，2669附近有 CAP_SYS_MODULE/modules_disabled 检查。实际内核及 SELinux/LSM 策略仍需对应构建资料，root不是全部条件。

modules.rst:64-71 明确 modules_prepare 不生成 Module.symvers；470-551说明 MODVERSIONS/导出符号 CRC和外部模块间符号资料。不能用一次准备源码、从ELF抽少量CRC或同KMI的符号列表替代完整构建输出。[M4]

### 3. SukiSU官方来源：扩展类型定义可取得，当前加载策略未匹配

理由：用户的现有环境是 SukiSU Ultra，已有 UI记录为v4.1.3/40796、LKM；需区分普通 ZIP shell模块与内核 LKM/KPM。使用前轮已固定的官方 source commit **223897c72cc5c23b6fea84d6542aa8ef24459998**，不声称它匹配本机40796或已加载内核二进制。[S0]

取得 `kernel/Kconfig`、`kernel/Kbuild`、`kernel/Makefile`：[S1]

- Kconfig：KSU 是 tristate，可选 M构建 kernelsu；KPM是依赖KSU与64BIT的独立可选配置，默认n。仅有 SukiSU root或管理器LKM标签不证明当前支持可任意调用内部 MCA对象的扩展，也不证明KPM启用。
- Kbuild 是 kernelsu内部对象与外部构建选项；Makefile的外部模块流程要求现有 KDIR构建树，并引用vmlinux做symbol检查。源码文本可取得；未运行其构建目标或工具。
- 完整 tree API请求返回403 rate limit；没有将其解释为源码消失，明确路径Kbuild随后通过固定raw URL取得。一个旧文件名 `kernel/ksu.c` 返回404，本轮没有因此扩大扫描或声称无实现。

这条路线未提供匹配当前运行内核/MCA的头文件、Module.symvers、信任链、运行时enforcement与SELinux策略，也没有提供当前MCA无分配查询/固定owner释放协议。当前上游源代码的存在不能解决这些设备构建缺项。

## 明确条件矩阵

“已有”表示主机工件中取得；“可取得”表示公开固定来源本轮已成功取到相关基线；“未取得”表示目标匹配材料缺失；“不匹配”表示有具体版本/范围证据，不能作为当前构建依据。每行给出当前路线是否可继续，避免将默认配置等同于设备配置。

| 必要材料/能力 | 本轮状态 | 已有或可取得的证据 | 当前实施边界 |
| --- | --- | --- | --- |
| 型号、OS增量、运行uname | 已有 | 同机 inventory前5行 | 使用此基准；不推断GKI替换来源 |
| 当前MCA磁盘ELF与加载构建身份 | 已有 | 10项live/disk build-id；三份ELF元数据 | 可继续离线分析；不是新扩展加载保证 |
| 当前MCA实际C源/Kconfig/Makefile | 未取得；公开设备基线不匹配 | MiCode外层构建入口与proprietary symlink；根版本6.6.30 | 自建接口源码线在此缺项停止 |
| 完整MCA头文件与回调原型 | 未取得 | split BTF有局部类型，但base未解析；有符号/CRC | 不靠BTF片段补造结构或原型 |
| 对象获取、引用、unbind/destroy生命周期 | 未取得完整契约 | 局部lookup/全局链表锁、destroy及kfree反汇编 | 原始指针不是生命周期保护；不实现猜结构外部扩展 |
| 无分配owner查询 | 未取得安全API | 现有按owner getter可能kstrdup；status show局部只读 | snapshot必须在准确类型/锁下实现，不拼接会分配的getter |
| 固定owner申请/释放enabled=0 | 已有内核局部机制；未取得用户接口 | mca_vote export及inactive不参与普通聚合证据 | 第一版允许固定inactive残留；仍缺受限完整接口 |
| 当前最终kernel/vendor配置 | 未取得；基线可取得 | MiCode和Google gki_defconfig、KMI8 | 不能替代current .config、vendor fragments和SukiSU选项 |
| 当前完整toolchain/编译参数/构建manifest | 未取得；部分标识已有 | 磁盘clang18/r510928；Google基线同r510928 | 相同工具链名不是同构建重现证明 |
| 当前kernel + MCA Module.symvers/ABI输出 | 未取得；部分CRC已有 | common18导出；8消费者/providerCRC相同；官方基线列表 | 缺完整CRC/type/构建输出，不生成伪Module.symvers |
| 当前GKI source revision | 未取得；同版本基线可取得但不匹配 | f9a Gitiles请求404；d415官方6.6.77 | 不把版本号相同叫hash匹配 |
| 现场签名/受保护符号/CAP/LSM加载策略 | 未取得；框架源码可取得 | Google基线loader/signing规则、Suki配置定义 | 不提供加载承诺或操作命令 |
| license/source分发边界 | ELF元数据已有；MCA实际源许可未取得 | 磁盘GPLv2、公开核心文件SPDX | modinfo许可不能替代未交付源码/头文件来源 |
| 控制返回值、硬件确认与远端附带恢复 | 部分反汇编已有；闭合资料未取得 | 票先更新、回调失败不回滚；远端2003附带调用 | inactive恢复仅软件参与状态，不能算电气/附带状态恢复 |

## 收敛结果与所需精确材料

三条已选来源没有给出匹配MCA完整源或完整厂商请求入口，本线不进入接口实现。下一步能实质改变判断的材料限定为：与 **OS3.0.303.0.WOCCNXM 已加载MCA构建** 对应的 `vendor/xiaomi/proprietary/mca/driver/mca` 目录及头文件、vendor配置/构建manifest/完整符号输出；与 **运行f9a1d4bd8353构建** 对应的完整source/config/toolchain/build输出和实际模块加载策略资料。二者可分别来自OEM驱动发布与实际GKI构建提供者，需要显式对应关系，不能要求它们单凭vermagic同一个版本号。

若匹配源码以后取得，应优先在MCA内部加入固定owner的受限query/apply/release，而不是外部模块猜对象布局；在正确生命周期与锁中提供无分配查询，释放enabled=0重新聚合，真实传播回调错误，并确认副作用恢复。保留inactive slot可满足本阶段产品契约；不把严格absent删除变成此时额外前提。当前没有这些材料，不编写可加载骨架，也不借已匹配的8项CRC进行试加载。

同版本固件包/HAL的取得由主线程另行核对；本报告没有把包名或Android16版本升级为驱动source/ABI对应证明。

## 固定来源与证据

本轮全部URL、状态与sha256在 `.tooling/bypass-check/stage1/build/acquisition*.json`；固定refs、来源匹配范围、条件矩阵和工件hash在同目录manifest/condition-matrix文件。HTTP404/403保留为有限请求失败；没有把失败当作全局不存在证明。

- [L0] 既有 `.tooling/bypass-check/device-root-inventory.txt`、`root-acquisition/modules.load`，没有本轮手机读取。
- [L1] [DADA_NEXT_RESTORE_RESEARCH.md](DADA_NEXT_RESTORE_RESEARCH.md)，mca_vote/getter/lifetime与功能恢复边界；原始 `.tooling/bypass-check/reconnect-control/mca_common.annotated.txt`。
- [L2] [DADA_NEXT_SIDE_EFFECT_RESEARCH.md](DADA_NEXT_SIDE_EFFECT_RESEARCH.md)，远端2003主/附带返回与恢复不对称。
- [L3] 本轮离线 `stage1/build/local-module-build-audit.json`；原ELF来源见 `.tooling/bypass-check/reconnect-control/manifest.json`。
- [L4] 既有 `.tooling/bypass-check/reconnect-acquisition/module-build-id-comparison.json`。
- [M0] 官方ref查询：`https://github.com/MiCode/Xiaomi_Kernel_OpenSource.git` 的 README / dada*；本轮结果存 `official-ref-record.json`。
- [M1] [MiCode Makefile](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/Makefile#L2)；README固定16d89a...沿用前轮已取官方原文。
- [M2] [MCA链接](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca)、[外层Makefile](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/Makefile#L115)、[Kconfig](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/Kconfig#L978)。
- [M3] [gki_defconfig](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/arch/arm64/configs/gki_defconfig#L97)、[build.config.common](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/build.config.common)。
- [M4] [官方发布中的modules.rst](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/Documentation/kbuild/modules.rst#L64)。
- [G0] [精确hash请求](https://android.googlesource.com/kernel/common/+/f9a1d4bd8353?format=JSON)，本轮404，仅限此URL。
- [G1] [官方tag解析](https://android.googlesource.com/kernel/common/+/4c3ba252b1f3566947076b88aac2fb47422dd549)、[实际commit](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44)；本轮固定ref记录。
- [G2] [Google同版本gki_defconfig](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/arch/arm64/configs/gki_defconfig#99)、[toolchain常量](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/build.config.constants)。
- [G3] [Google官方ABI目录](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/android/)；本轮取得四份符号列表，未将其视作当前运行ABI。
- [G4] [version.c](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/kernel/module/version.c#79)、[signing.c](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/kernel/module/signing.c#126)、[main.c](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/kernel/module/main.c#1157)。
- [S0] 既有 [XIAOMI_DADA_BYPASS_RESEARCH.md](XIAOMI_DADA_BYPASS_RESEARCH.md) 的SukiSU UI记录；不是当前source commit对应证明。
- [S1] [SukiSU Kconfig](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/kernel/Kconfig)、[Kbuild](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/kernel/Kbuild)、[Makefile](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/kernel/Makefile)。
