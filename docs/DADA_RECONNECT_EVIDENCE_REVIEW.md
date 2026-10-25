# dada 重连后的读取白名单与证据变化审查

日期：2026-10-13。目标设备：Xiaomi 15 / dada / 24129PN74C，固件此前识别为 `OS3.0.303.0.WOCCNXM`，Android 16。

本代理只审查公开一手实现、已有离线 ELF 和主代理新增的本地采集工件；没有运行 ADB、操作手机、修改模块或调用电源接口。主代理拥有 `.tooling/bypass-check/reconnect-acquisition/`；本代理仅写本文与 `.tooling/bypass-check/reconnect-evidence/`。

## 结论

**本轮读取白名单有直接实现依据：静态 DT 属性、普通 KO 文件和标准 module 构建/加载元数据不会经驱动控制硬件；已分析 MCA voter 的 status 只格式化内存票。** 这一判断有明确范围，不能扩展到未知 sysfs getter、module parameters、寄存器、CP 模式或其他 debugfs 文件。[S1][S2][L1]

新增证据已经确认nested MCA GLINK属性、QCOM proxy与SC8581/SC8585、HL7603的绑定，并使10个关键模块的加载GNU build ID与对应磁盘ELF逐字节匹配。这是明确的构建身份对应证据，比仅依据绑定与磁盘hash更强，之前仅由vermagic不同产生的对应不确定性已明显缩小；仍不是运行代码逐字节dump或远端handler的证明。`business-votable` 在这轮列目录结果中未呈现，mounts 工件也未列出 debugfs，因此没有新增 status 快照；不为了取得它而挂载 debugfs。[L2][L5]

已找到同一个官方内核快照内的 Qualcomm SMB5 参考实现，确实把 charging enable 与 USB input suspend、power path status 分开。**它仍未与本机 MCA `0x2003` 的远端 handler 或当前 charger 型号匹配，不能把参考寄存器定义移植成新后端或读取方案。** 本轮仍不能批准候选写入，更不能宣称硬件旁路成功。[S3][S4][S5][L3]

新增普通固件 `adsp_dtb.b01` 可以离线解出5个完整FDT，本代理与主代理的独立解析得到相同节点和属性原始字节。远端配置中出现 `pm8750bh@7`、`qcom,pm-battman-charger` 与 `charger_pd` 线索；DT自身不证明当前effective选择或BAT/SYS门控。随后正确v79代码分析取得了下述独立于DT的handler静态链。[L6][L9]

再追加取得普通 `adsp.b19/b20/b21` 文件，本代理独立核对三份size/hash与MDT相应program header；b19在镜像中声明R-X，b20/b21声明RW-，全部实际大小等于对应p_filesz。这为离线定位代码提供文件材料，**磁盘固件仍不等于当前ADSP内存执行内容**，MDT地址和权限也不是运行时测量。[L7]

本地LLVM23.1.3 Hexagon v79工具已获得可重复的离线解码验证：包装四个section保持原始分片字节、三个汇编往返样例共76字节完全一致，官方decoder源码支持packet地址和分支目标的工具语义。provider随后把磁盘固件 `0x2003` dispatch追至SetEnableCharging、初始化模型ops和PMIC bit0读改写，并闭合相应getter；`0x2019`走独立输入暂停链。**现在已有具体远端静态handler链，不再只有可分析文件或名字线索；仍未证明当前ADSP执行身份、实际运行provider、整个属性副作用恢复和SYS保供。**[S6][L8][L9]

## 1. 白名单逐项核验

| 白名单类别 | 已检查的实现与允许范围 | 不能由此扩展的行为 |
| --- | --- | --- |
| 静态设备树属性 | `drivers/of/kobj.c` 的 `of_node_property_read` 只把 `pp->value/length` 交给 `memory_read_from_buffer`。读取已定位的 GLINK、I2C compatible/status/reg/role/channel 属性不调用充电器 show。[S1] | 不把整个 `/sys/devices` 中可读文件当作静态DT；不调用未知 debug/ADC/寄存器 getter |
| 普通 vendor KO | 把已确认普通文件的 ELF 字节复制到本地、计算哈希、离线反汇编，只涉及文件内容 | 不加载/卸载/重载 KO、不open字符设备、不从设备 memory dump补身份 |
| `/sys/module/<name>/version`、srcversion、scmversion | 官方 `MODINFO_ATTR` 宏只格式化 module struct 保存的字符串；无硬件回调。[S2] | 不把srcversion当作整个加载ELF的SHA，也不因字符串相同认定运行内存逐字节相同 |
| 标准 taint/initstate/coresize/refcnt | 官方 getter分别格式化module taints、module state、加载内存大小、引用计数；没有充电provider调用。[S2] | 不以live/taint空/coresize推断硬件正常、请求生效或磁盘代码等同运行代码 |
| `/sys/module/<name>/notes/.note.gnu.build-id` | 标准module_notes_read仅从已加载note section缓冲区memcpy；文件内容与ELF note可离线比对。[S2][L5] | 构建ID不是整个运行内存的完整性校验，不自动证明远端固件版本/handler |
| driver/module/of_node绑定 | 只列目录、读取符号链接目标，核对哪个module绑定哪个设备 | 不写bind/unbind/uevent/driver_override；不把driver绑定升级为电气拓扑保证 |
| MCA business-votable特定status | 已有原始ELF中status open是single_open；show只mutex锁、读clients/effective/override/force内存、seq_printf、解锁。[L1] | 不读未知debugfs文件；不触force_val/force_active；不挂载debugfs；status票与硬件状态仍分开 |

MCA status 的范围限定在 `chg_enable`、`input_suspend`、`buck_input`、`quick_chg_en`、`quick_chg_disable` 的 **status 文件**，且debugfs已经挂载、真实路径存在时才有读取用途。纯内存的结论来自已取得磁盘ELF，仍要保留运行身份对应边界；格式化会占用CPU并短暂拿mutex，不能表述为系统完全无扰动，但没有发现硬件callback或read-to-clear。[L1]

模块的 `parameters/*` 不在本白名单：参数可以注册自定义getter，文件只读权限不保证只读内存。module `uevent` 也不是读取目标；同一官方源在该属性store中调用kobject_synth_uevent。应固定属性名，避免目录遍历后对所有文件执行cat。[S2]

空字节、缺失属性、读取错误三者分别记录。例如BE32为零的DT属性不是空字符串；version/srcversion没有出现在已采标准metadata结果中，也不是“版本已经匹配”。

## 2. 重连新证据与上一轮的对照

已独立验证 `.tooling/bypass-check/reconnect-acquisition/metadata-manifest.json` 的11份工件和 `static-manifest.json` 的61份工件：大小与SHA-256均相符，全部return code为0且stderr为空。后一批包含47份静态DT属性、2份普通KO、10份加载module note，以及2份普通目录/固件路径清单。本代理只处理采集后的本地文件，不执行其记录的采集命令。[L2][L5]

| 新事实 | 支持什么 | 不支持什么 |
| --- | --- | --- |
| 运行内核仍为 `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k` | 本轮内核版本与此前观测相同 | 不能单看vermagic 6.6.57不同就认定驱动实现不匹配；须结合新增build ID证据 |
| MCA common/interface/buck/quick/class/proxy/ADSP、SC8581、HL7603、QTI PMIC GLINK的initstate均live | 这些module当前以该名称进入live状态 | 不能证明每个回调即磁盘ELF、不能证明电气工作或充电策略结果 |
| 这批module taint为空；各coresize被记录 | 有标准加载状态线索，未见该字段输出taint字母 | 不等于代码身份校验，也不能据此断言内核从未发生错误 |
| module metadata没有version/srcversion行 | 本次白名单条件读取未输出这些属性 | 无法完成这两类字符串与磁盘metadata的比较；不能假造空值等于匹配 |
| driver目录有 `6-006e -> sc8581_charger_pump`、`6-0077 -> hl7603_boost_bypass`、QCOM proxy和nested MCA ADSP GLINK绑定 | 之前“普通磁盘文件名+DT compatible”的候选现有更清楚的实际driver/device绑定 | 不等于HL7603在充电通路中的功能已定义，不证明CP bypass是电池旁路 |
| nested GLINK关键属性原始字节已取得：compatible为qcom,qti-pmic-glink；channel为PMIC_RTR_ADSP_APPS；subsys为lpass；protection-domain为tms/servreg、msm/adsp/charger_pd | 公开sun DT所描述的transport与本机这几项static属性相符 | 不证明远端固件handler或每个property的电气实现 |
| 同一GLINK下MCA child compatible=mca,adsp_glink/status=ok，legacy qcom,battery-charger child status=disabled | 当前静态配置与实际MCA driver绑定共同支持MCA为本机控制路径；之前这一缺口已补齐 | 不把standard qti_battery_charger.c当作本机MCA opcode实现 |
| 10个加载module的GNU note与磁盘ELF note均匹配 | 已得到具体构建ID对应，明显加强使用这批ELF分析运行模块的依据 | 不证明运行内存没有patch、同ID一定语义完整一致，或ADSP对端同版本 |
| voter-directory输出business-votable absent；mounts未见debugfs | 本次没有可读的该debugfs目录/status快照 | 不证明voter没有运行，也不授权挂载debugfs |

GLINK原始字节解码见 `.tooling/bypass-check/reconnect-evidence/independent-glink-dt.json`。字符串保留NUL分隔：protection-domain是两个字符串，不是拼成单一路径；空字节DT属性如ship-mode-immediate是属性存在，不是取得了charging控制读数。新增nested路径不能反向修正为“上轮某节点不存在”。

### 2.1 构建 ID 的独立核验

本代理使用标准库struct另行解析ELF64 section table和section-name string table，直接提取 `.note.gnu.build-id`，没有复用主代理的提取程序。然后比较对应 `/sys/module/.../notes/.note.gnu.build-id` 的36字节原始note，并另核对应ELF SHA-256与主代理比较记录一致。以下10项全部匹配：mca_common、mca_charge_interface、mca_strategy_buckchg、mca_strategy_quickchg、mca_platform_buckchg_class、mca_qcom_subpmic_proxy、mca_adsp_glink、sc8581、hl7603、qti_pmic_glink。[L5]

独立记录为 `independent-build-id-check.json`。GNU note由namesz/descsz/type与GNU owner/20字节build ID组成，不是36字节的电源状态。应把这项结论准确表述为“当前加载模块呈现与分析ELF相同的构建ID”，不要只保留“磁盘代码对应仍未知”的旧幅度；但build ID也不是对运行text的字节哈希、没有检查运行期patch，更没有包含独立ADSP镜像的handler。[S2][L5]

### 2.2 与上一轮相比，哪些证据增强、哪些结论不变

| 事项 | 上一轮 | 本轮变化 | 仍需保留的边界 |
| --- | --- | --- | --- |
| Linux MCA分析材料与运行模块对应 | 普通KO、DT线索，vermagic存在差异 | 10份加载GNU note与磁盘ELF note一致，并有actual driver绑定 | 构建对应显著增强；不等于运行text完整哈希，也不覆盖ADSP |
| GLINK路径身份 | 公开sun DT与proxy反汇编 | 本机nested MCA GLINK/channel/PD静态属性补齐，legacy child为disabled | 运输路径更明确；这一类证据本身不证明远端handler，新增代码链见下一行 |
| 远端property handler | Linux链止于RPMSG，无远端执行语义 | 磁盘代码闭合0x2003至模型ops、PMIC bit0 RMW/getter，并定位独立0x2019输入暂停链 | 静态控制分离已证明；当前执行身份、运行模型和板级供电仍未知 |
| 远端固件研究材料 | 未取得匹配普通payload | 取得MDT、数据payload、可解析的5份FDT，再追加b19/b20/b21普通文件 | 文件来源与完整性有记录；不等于实际加载版本、当前内存或CFGL effective选择 |
| Hexagon代码解码 | 缺少适用本地decoder，主要是字符串/数据线索 | 官方版本本地工具、原字节包装、v79样例assemble往返已核验 | 有了离线分析能力；nominal text内仍可能有数据，不等于所有输出都是可达代码 |
| PMIC/充电配置线索 | QCOM proxy与旧SMB5公开参考 | 本地ADSP DT出现pm8750bh@7及battman类别；代码明确8模型值映射至6张ops表/2种enable实现 | 不把PMIC名称或模型数值替代实际运行芯片ID和完整寄存器schema |
| 候选接口行为与恢复 | enabled固定1、撤票/吞错/缓存/CP覆盖问题 | 本轮没有执行候选，未补齐恢复协议 | 新证据不改变写入安全条件，不批准试写 |
| 系统由外部供电、电池停止持续充放电 | 旧smart_chg被动数据不能证明旁路 | 本轮仍无候选开关A/B或电气因果数据 | 不以低功率、票值、单帧0mA或字符串宣称有效 |

因此本轮的实质进展是把运输、Linux构建对应、远端配置与静态handler链补齐。目标依然是验证电池充电控制及外部保供；新发现已减少静态实现的不确定性，尚未满足运行对应、属性联动恢复和电气效果条件，也没有产生新的手机测试授权。[L3][L5][L6][L7][L9]

## 3. 官方 PMIC 参考代码能否补上保供证据

### 3.1 找到了具体分离，但仍是参考实现

小米官方固定内核提交 `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5` 的 `drivers/power/supply/qcom/` 普通目录确实包含 `qpnp-smb5.c`、`smb5-lib.c`、`smb5-reg.h`，不依赖截断递归tree来推断是否存在。[S3]

该代码中：

- `smblib_chg_disable_vote_callback` 仅对 `CHARGING_ENABLE_CMD_REG` 的 enable bit做masked write；它没有在该局部callback同时执行USB suspend。[S4]
- `smblib_set_usb_suspend` 使用单独 `USBIN_CMD_IL_REG` / `USBIN_SUSPEND_BIT`，并管理ICL IRQ disable票。[S4]
- USB online getter读取 `POWER_PATH_STATUS_REG` 的 USE_USBIN和VALID_INPUT_SOURCE位；这比只看VBUS存在更接近“输入被采用”的源内证据。[S4][S5]

这证明“充电使能、输入暂停、已采用输入源”在公开Qualcomm的一种实现中是独立概念，并给出应该去核对的实现类型。它没有证明本机ADSP handler使用这套寄存器，也没有保证本机停充后输入一直足够供SYS。

### 3.2 为什么不能迁移

同文件 `qpnp-smb5.c` 的of match/config列表是PM8150B、PM7250B、PM6150、PMI632系列。当前已确认的MCA路径是QCOM subpmic proxy，经ADSP `0x2003`；没有从现有普通KO、DT、绑定或公开源码中取得“这个property由上面SMB5 callback实现”的连接，也没有当前charger型号/寄存器布局的证明。[S3][L3]

因此 `smb5-reg.h` 不进入手机读取/写入白名单。共享Qualcomm名字、同内核仓库含源码或enable/suspend概念相似，均不能完成与SMB5参考的映射。新增ADSP静态链给出具体实现，但仍没有当前运行provider与板级电气语义的证明；不能借旧参考资料为新地址或未知位命名。

标准公开 `qti_battery_charger.c` 也不补上缺口：它是另一套power_supply/GLINK属性定义，并不是这份MCA proxy的执行实现。当前本机MCA opcode/owner/property编码不能从普通QTI属性名称移植。[L3]

### 3.3 普通ADSP DTB文件的独立解析

主代理新增的 `firmware-data-manifest.json` 实际列17份普通文件：16份ADSP数据payload和1份 `adsp_dtb.b01`。本代理独立核对17份本地文件的大小、SHA-256和采集return code，全部一致且return code为0；没有重新执行采集命令。DTB文件159100字节，SHA-256为 `daae29c75da2a6e081da2a82a9d0ac21693856eeafe73685999204e7d732b919`，前4字节为ASCII `CFGL`。[L6]

本代理的 `parse_adsp_dtb.py` 直接核对FDT header、section边界、节点栈、property字符串名/长度、token以及END，没有借用主代理解析器，也没有根据ADSP ELF program header猜bxx分组。

| 文件内偏移 | FDT totalsize | 节点数 | 属性数 |
| --- | --- | --- | --- |
| 372 | 149376 | 880 | 4888 |
| 149748 | 2312 | 22 | 53 |
| 152060 | 3792 | 31 | 96 |
| 155852 | 3176 | 29 | 77 |
| 159028 | 72 | 1 | 0 |

五个FDT均为version17/last-compatible16，完整连续覆盖偏移372至文件末尾159100；最后一个只有空root。前四个root的model/compatible为 `qcom,pakala`。两套解析器按顺序比较全部963个节点以及5114个property的path/name/rawhex，逐项一致，记录见 `independent-root-fdt-comparison.json`。[L6]

最大FDT内可直接定位：

- `/soc/spmi_bus@c400000/pm8750bh@7` 的compatible为 `qcom,spmi-pmic`，reg原始字节 `00000007`；同一树还列出PMK8550、PM8550等节点。这个名称为PM8750BH提供了实际固件配置线索，仍不能当作运行芯片ID或SMB5匹配证明。
- `/sw/core/pmic/battman/charger_cfg`、`platform_cfg`、`usbpd_cfg`、`gauge_cfg`、`xiaomi_cfg` 分别使用 `qcom,pm-battman-charger`、`qcom,pm-battman-plat`、`qcom,pm-battman-usbpd`、`qcom,pm-battman-gauge`、`qcom,pm-battman-xiaomi`。这些是远端充电配置类别，并未给出MCA property handler。
- `/sw/core/debugtools/pd_mon/charger_process` 的pd_name为 `charger_process`、subdomain_name为 `charger_pd`，与Linux GLINK配置中的 `msm/adsp/charger_pd` 有语义对应线索；这不是IPC端点运行绑定证明。
- `/sw/core/pmic/battman/usbpd_cfg/pd_policies_cfg/sys-power-budget` 是4字节 `007270e0`，按BE32形式为7500000。单凭property名称与该数字不能把它当作实测SYS功率、恒定可用功率或旁路开关。

检索节点名、property名及可读字符串后，没有得到charging-enable/input-suspend/BATFET/VSYS/电池隔离的直接门控定义；这一否定仅限此次解析范围，不代表整个远端固件无相应功能。较小的三个非空FDT主要为debug/power/diag配置，没有battman树。

CFGL外层格式、平台选择与overlay合成规则尚未解明，因此不能把五个blob简单相加成为当前effective DT，也不能把某条配置的出现当作本机当前硬件已采用它。所有property保留rawhex；字符串识别是启发式，BE32只是展示一种数值解释，不把二进制全零误记为空文本，更不把packed结构默认按BE32展开为已知schema。此轮结果加强了下一步离线定位远端实现的依据，没有新增手机寄存器或控制接口白名单。[L6]

### 3.4 追加固件代码段：完整性、MDT权限与地址

本代理另从 `adsp.mdt` 原始字节解析ELF32 little-endian header及57个program header，不复用主代理解码器；全部57项type/offset/vaddr/paddr/filesz/memsz/flags/align与其JSON一致。MDT自身8364字节，SHA-256 `0708ab9b2a63e3d83b2f388baff403a541c5c930d15993a9555f3e59bb8e7f1e`。`firmware-header-manifest.json` 的11份工件、`firmware-charger-code-manifest.json` 的3份工件，大小/hash全部相符，记录return code均为0。[L7]

| 文件/PHDR index | 实际大小=p_filesz | p_memsz | p_vaddr | p_paddr | raw flags及ELF低3位权限 |
| --- | --- | --- | --- | --- | --- |
| adsp.b19 / 19 | 1872388 | 1875968 | 0x60000000 | 0xa0700000 | 0x08000005 / R-X |
| adsp.b20 / 20 | 22856 | 1003520 | 0x601ca000 | 0xa08ca000 | 0x08000006 / RW- |
| adsp.b21 / 21 | 272 | 4096 | 0x602bf000 | 0xa09bf000 | 0x08000006 / RW- |

对应SHA-256为：b19 `b4529c2d95b3a058751b1528c1bb1183b62a4aa145d6b5d0db474c62038df374`；b20 `1028fe2fd204f51592b1c77c1c89caa55b887305e41d327351fe2b6af9ad6dcc`；b21 `8162fce811481d34773639c84866fb74b219aa16f1cc0820cb4919e1bf69e491`。三段均PT_LOAD/align4096。p_memsz-p_filesz分别3580、980664、3824字节，未包含于取得的文件数据；不可把补零后的镜像视作读取了实际运行BSS或状态。[L7]

这些是镜像声明地址和权限，只用于离线定位；没有由此读取手机内存、调用loader、执行或patch固件。MDT高位flags保持原样，不在本文猜其vendor私有含义。bxx后缀与对应PHDR index及filesz一致，不凭相邻段、相邻地址或b22字符串认定它们属于同一process或MCA handler。

同批静态Linux DT的 `firmware-name` 字节为 `adsp.mdt\0adsp_dtb.mdt\0`，compatible为 `qcom,sun-adsp-pas`。它把remoteproc配置与这两个文件名联系起来，但尚未校验ADSP实际加载镜像的构建身份、运行内存、重定位或patch；因此不能将Linux模块build ID匹配结论跨用到这些远端固件文件。[L7]

### 3.5 本地Hexagon v79解码器、包装字节与packet验证

本代理再次读取原MDT并独立解析 `charger-analysis.elf` 的ELF32 header和section table，没有执行固件。包装保留原e_flags `0x79`，entry为0、program-header count为0；四个section的原始数据逐字节与b19/b20/b21/b22一致，sh_addr分别与原MDT p_vaddr相同，sh_size分别等于p_filesz。包装SHA-256为 `e847a49ed66b31c19bd2d072b014efcae77957c8b39a264efdbbdb21150718c1`。[L8]

| section | 原分片 | 大小 | section flags | 分析用途 |
| --- | --- | --- | --- | --- |
| .text | b19 | 1872388 | 6（ALLOC/EXEC） | 只在镜像声明的执行段内离线解码 |
| .data20 | b20 | 22856 | 3（WRITE/ALLOC） | 数据，不作为code反汇编 |
| .data21 | b21 | 272 | 3（WRITE/ALLOC） | 数据，不作为code反汇编 |
| .rodata | b22 | 222028 | 2（ALLOC） | 只读数据/字符串，不作为code反汇编 |

仅b19标EXEC不代表其内部每个字节都是真实可达指令。embedded常量、padding或跳转表也可能位于执行段；必须结合函数边界、调用/分支可达性、数据交叉引用和handler路径，再判断具体代码含义。25MB objdump输出不是25MB已确认函数，更不能把其中偶然可解的字节当作handler。

工具来源另行核对：本地LLVM bottle456195913字节及Z3 bottle13659457字节的SHA-256均与所保存的官方元数据一致；LLVM tar中选出的5个原始成员散列与提取manifest一致。llvm-mc/objdump/readobj及LLVM-C原始字节保持相同；libLLVM的文件hash和大小因依赖重定位及本地ad-hoc签名改变，当前hash与 `tool-relocation.json` 的relocated hash一致，依赖Z3/Zstd hash也一致。本代理还从tar原成员与当前Mach-O独立比较13个有文件数据的section，全部字节相同，包括代码和常量section。这是限定范围的内容比较，仍不把整份已修改Mach-O称作原始官方binary。[L8]

官方 `llvmorg-23.1.3` 源码的依据：[S6]

- ELF.h定义 `EM_HEXAGON=164`、`EF_HEXAGON_ISA_V79=0x79`、MACH_V79等于ISA_V79；MCTargetDesc的GetELFFlags将hexagonv79映射至MACH_V79。显式 `--triple=hexagon --mcpu=hexagonv79` 与原MDT标识一致，并非试错选一个能输出文本的CPU。
- Disassembler的makeBundle在处理同一packet时反复调用getSingleInstruction，传递同一个Address，并只推进Bytes；getSingleInstruction按read32le取得word，处理packet-end、duplex和extender。brtargetDecoder通过FullValue+Address构造jump/call目标。因此对于分支目标，本工具使用packet起始地址，不能把每个显示word的地址作为基准重新相加。
- fullValue合并扩展器和低位立即数；`.new` producer需要依据packet中的先前指令，而非顺序执行普通赋值的方式解释。objdump中的immext不能独立算作普通寄存器写入。

本代理使用本地llvm-mc生成三个普通对象文件，再用独立ELF section解析提取其text字节，与原b19对照，并用显式v79 objdump解码：[L8]

| 样例 | 原b19范围 | 往返内容 | 结果 |
| --- | --- | --- | --- |
| start_packets | offset0、60字节 | immext/add(pc)、多word packet、framelimit、callr | 原始60字节完全一致、无relocation |
| direct_call | offset0x3c、4字节 | 通过本地label解析call至offset0xd0 | 原始4字节完全一致、无relocation |
| packet_back_branch | offset0xe8、12字节 | cmp/`.new` forward branch和向后jump+store packet | 原始12字节完全一致、无relocation |

三个assemble与objdump均返回0。这里只核验76字节代表样例的编码/解码一致，不声称整个b19逐指令roundtrip，也不是以同一工具往返证明所有架构语义。`add(pc,##offset)` 样例验证了立即数和packet字节保持；本地现有官方decoder源码直接证明的是工具branch地址计算，不单凭它认定架构执行时PC读值或整个远端数据流。实际add(pc)交叉引用应继续结合匹配ISA说明或函数/数据关系核对。

这些对象与wrapper只存在于Mac本地分析目录，没有发送到手机、没有加载、没有执行Hexagon代码、没有读取手机内存或写硬件。后续静态handler研究已取得§3.6所列成果；实际加载身份、完整属性联动恢复、CP覆盖和owner撤票仍按原边界审查。

### 3.6 从工具核验推进到实际磁盘handler静态链

provider的最终§4.5、主代理独立dispatch/provider/联动窗口与初始化代理的独立审查，现在共同提供具体代码链。本文引用该分工结果，并独立核对报告与已存在本地工件的证据范围，不把这些链重复表述为手机已经执行。[L9]

| 新取得的静态证据 | 支持的实现判断 | 当前不能得出的判断 |
| --- | --- | --- |
| 0x60031450的set dispatcher减基0x2003，41项表首项指向0x600314c4，取device index/payload byte后调用0x60075bb0 | 磁盘固件0x2003确实调用PmSchgChgr_SetEnableCharging，而非仅字符串同名 | 不证明当前ADSP运行字节与该磁盘相同或请求已执行 |
| SetEnableCharging取runtime entry+4 ops；initializer按8个PmicModel数值选择6张file-backed ops表，enable slot折叠为2个实现 | 默认已分析静态初始化路径下ops的两组可选实现已定位 | BSS entry不是文件数据；当前device index、model、slave和active状态仍未知 |
| 两个enable实现向共同SPMI RMW传mask1、enable byte，对应镜像中的PMIC地址0x1042/0x2646 | 该底层API保留其他位并改变bit0 | 不能将完整0x2003缩成只改bit0，也不把它称作BATFET隔离/保SYS位 |
| get dispatcher的0x2003调用GetEnableCharging；ops+0x34读取相应地址mask1 | 已找到对应硬件位读取的静态调用路径 | getter并未在手机调用；读取该位不证明外部承担负载或电池无持续电流 |
| 0x2019走technology index3/hlos voter/chgr_suspend callback，在新backend有独立mask1路径 | 输入暂停与底层充电enable是独立静态控制链 | suspend不能作为保留外部输入的旁路开关；其他voters可能保持暂停 |

本代理另直接从b19原始字节读取set的41项表，逐项property/table address/target与provider记录一致；get表中0x2003的原始target确为0x6002f478，记录为 `independent-handler-dispatch-check.json`。原始dispatcher表、底层窗口和initializer映射已经支持上述静态判断。地址只用于本地反汇编定位；本文不据此提供手机寄存器访问指令或扩展读取白名单。对应getter虽有真实read调用，仍未完成本机运行对应、未知读副作用和上层调用全链的读取审核。

完整0x2003在SetEnableCharging之后还有ctx+0xbc/ctx+0xc0对象联动。主代理已进一步定位部分位写入，包括与0x2c42相关的操作；未有匹配schema时不为未知位命名，也不能称属性只控制充电bit。静态控制分离不等于电气分离：输入能力、CP策略、BAT/SYS接线、运行provider、失败恢复及owner撤票仍需分别证明。[L9]

## 4. 保供验收缺口是否改变

| 必须回答的问题 | 重连数据能否回答 |
| --- | --- |
| 请求owner的原始enabled/value、force/override和当前effective票 | 首批没有business-votable快照，不能回答；即使取得，只证明内存请求状态 |
| 独立owner可撤票且不影响OEM/温控保护 | 新metadata没有新增userspace撤票协议；此前enabled固定1问题保留 |
| vote/provider失败后硬件是否真的执行 | 新metadata没有硬件状态；缓存先改、吞错、相同票可跳过retry问题保留 |
| 禁buck后CP是否继续充电、quick退出后如何准确恢复 | 模块live与CP绑定不等于运行策略；复杂退出/恢复问题保留 |
| ADSP `0x2003`只禁电池充电并维持输入/SYS | 磁盘handler已追到模型ops/bit0 RMW/getter，0x2019静态独立；完整0x2003有附加联动，当前执行身份、运行provider和电气保证仍缺 |
| 外部输入功率承担系统负载、电池没有持续充放电 | 本轮无受控开关A/B，metadata和单帧电流不具备因果证据 |
| 电池物理隔离 | 未取得本机BAT/SYS拓扑，不由禁充或净电流近零推出 |

更完整的证明应在新增磁盘静态handler链基础上补齐当前执行对应、板级电源拓扑或硬件定义、独立请求准确恢复方法和CP覆盖，再在经过getter审核的输入功率与电池电流时间序列上做受控对比。读到charging-disabled、online=1、status票为0或单帧0mA都不能代替这些条件。[L3][L4][L9]

如果后续已经有充电保护在80%自然暂停，观察到输入在线且电池0mA只描述那个观测时刻。没有操作候选接口，也就没有依据把该现象归于候选；低频遥测还不能排除短时电池补电。

## 来源

- [S1] 小米官方固定提交的OF静态属性实现：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/of/kobj.c#L31>。同逻辑Linux v6.6参考：<https://github.com/torvalds/linux/blob/v6.6/drivers/of/kobj.c#L31>。本地 `.tooling/bypass-check/reconnect-evidence/dada-of-kobj.c`、`linux66-of-kobj.c`。
- [S2] 小米官方模块metadata getter：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/kernel/module/main.c>，MODINFO_ATTR宏502-531、initstate887-909、coresize924-937、taint967-978；本地`reconnect-evidence/dada-module-main.c`。标准sysfs创建参考：<https://github.com/torvalds/linux/blob/v6.6/kernel/module/sysfs.c>，本地`linux66-module-sysfs.c`。公共源码与运行内核仍有版本边界。
- [S3] 小米官方Qualcomm power-supply目录：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/tree/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom>；具体SMB5 match与subtype：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/qpnp-smb5.c#L3257>及306-352。普通目录API副本`micode-dada-qcom-supply.json`，源码副本`dada-qpnp-smb5.c`。
- [S4] 官方SMB5库：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/smb5-lib.c>。关键范围713-731输入suspend、1757-1773禁充callback、3397-3408输入source判断；本地`dada-smb5-lib.c`。仅作参考，不认定本机handler。
- [S5] 官方SMB5寄存器定义：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/smb5-reg.h>。本地`dada-smb5-reg.h`。本报告只用逻辑字段辨认概念，不把其地址值转成实机建议。
- [S6] LLVM官方固定tag `llvmorg-23.1.3`：<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/lib/Target/Hexagon/Disassembler/HexagonDisassembler.cpp>（makeBundle209-240、read32le377、packet/duplex/end处理390-499、brtargetDecoder810-822）；<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/lib/Target/Hexagon/MCTargetDesc/HexagonMCTargetDesc.cpp>（GetELFFlags705-727）；<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/include/llvm/BinaryFormat/ELF.h>（EM_HEXAGON262、ISA_V79/MACH_V79定义644/679）。已有本地副本为`reconnect-provider/llvm23-HexagonDisassembler.cpp`、`llvm23-HexagonMCTargetDesc.cpp`、`llvm23-ELF.h`；本代理仅读取本地副本，来源由provider给出，独立hash与其记录一致，见 `reconnect-evidence/independent-llvm-source-hashes.json`。
- [L1] 本机MCA status原始离线证据：`.tooling/bypass-check/control-chain/mca_common.annotated.txt`，status创建`.text+0x10ec..0x1104`，open`.text+0x1318`，show`.text+0x1348..0x14b0`；另见主代理独立重反汇编 `.tooling/bypass-check/root-acquisition/final-independent-review.txt`。散列见`control-chain/evidence-manifest.json`。
- [L2] 本轮主代理普通metadata采集：`.tooling/bypass-check/reconnect-acquisition/metadata-manifest.json`及该批明确列出的root-identity、kernel、modules、mounts、module-metadata、binding-directory、dt-files-0/1/2、voter-directory工件。本代理只读取本地副本，没有执行其记录的采集命令。
- [L3] 末端provider与失败/恢复缺口：[DADA_CHARGE_CONTROL_CHAIN.md](DADA_CHARGE_CONTROL_CHAIN.md)、[DADA_CHARGER_PROVIDER_RESEARCH.md](DADA_CHARGER_PROVIDER_RESEARCH.md)、[DADA_POWER_PATH_RESEARCH_REVIEW.md](DADA_POWER_PATH_RESEARCH_REVIEW.md)。provider已知运输边界不是远端电气保证。
- [L4] 输入存在/电流/charging类型和强弱目标：[DADA_POWER_PATH_EVIDENCE.md](DADA_POWER_PATH_EVIDENCE.md)，所引用Linux power_supply ABI和厂商原始资料。旧插件被动采样见[SUKISU_BYPASS_POWER_TEST.md](SUKISU_BYPASS_POWER_TEST.md)，不作为本轮候选A/B。
- [L5] 主代理追加静态工件：`.tooling/bypass-check/reconnect-acquisition/static-manifest.json`、`live-dt/`、`module-notes/`、`driver-binaries/`及`module-build-id-comparison.json`；本代理独立校验：`.tooling/bypass-check/reconnect-evidence/independent-build-id-check.json`、`independent-glink-dt.json`。标准加载note getter的源内依据是[S2]所列sysfs.c的149-157和204-208；不存在驱动硬件callback。
- [L6] 主代理普通固件文件及manifest：`.tooling/bypass-check/reconnect-acquisition/firmware-data-manifest.json`、`firmware/adsp_dtb.b01`。独立解析代码与结果：`.tooling/bypass-check/reconnect-evidence/parse_adsp_dtb.py`、`independent-adsp-fdt-check.json`、五份`adsp-fdt-<offset>.json/.dtb`；与主代理`firmware/root-remote-dt-decoded.json`逐项对照结果为 `independent-root-fdt-comparison.json`。本文只分析复制后的普通文件，不确认CFGL选择规则、远端运行镜像身份或电气效果。
- [L7] 主代理追加的普通文件：`.tooling/bypass-check/reconnect-acquisition/firmware/adsp.b19`、`adsp.b20`、`adsp.b21`、`adsp.mdt`，及 `firmware-charger-code-manifest.json`、`firmware-header-manifest.json`、`firmware/adsp.mdt.headers.json`；本代理独立代码/结果为 `.tooling/bypass-check/reconnect-evidence/check_charger_code.py`、`independent-charger-code-check.json`。静态remoteproc属性见同acquisition目录的 `live-dt/soc/remoteproc-adsp@03000000/firmware-name` 与 `compatible`。
- [L8] 主代理本地工具/包装/解码：`.tooling/bypass-check/hexagon-host-tools/` 的`bottle-metadata.json`、`z3-bottle-metadata.json`、两个bottle archive、`extracted-tools-manifest.json`、`tool-relocation.json`、`charger-analysis.elf`、`charger-v79.disasm.txt`。独立验证见 `.tooling/bypass-check/reconnect-evidence/check_hexagon_decoder.py`、`independent-hexagon-decoder-check.json`、`independent-host-tool-hashes.json`、`independent-llvm-library-sections.json`，及三个fixture的`.s/.o/.stdout/.stderr/.disasm.txt`；所有操作只发生于本地Mac文件。
- [L9] 新增远端handler分工审查：[DADA_RECONNECT_PROVIDER_RESEARCH.md](DADA_RECONNECT_PROVIDER_RESEARCH.md) §4.5；`.tooling/bypass-check/reconnect-provider/buck-set-dispatch-table.json`、`schg-initializer-data.json`、`buck-remote-key-windows.txt`；独立初始化审查 `reconnect-control/remote-provider-init/REVIEW.md`、`independent-init-audit.json`；主代理 `hexagon-host-tools/root-dispatch-review.txt`、`root-provider-enable-review.txt`、`root-enable-side-effects-review.txt`；本代理原始表核对 `reconnect-evidence/independent-handler-dispatch-check.json`。这些属于已采磁盘分片的静态代码/数据，不是远端内存身份、实际运行provider或电气效果证明。
