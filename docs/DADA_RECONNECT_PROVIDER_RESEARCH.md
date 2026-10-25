# dada 重新连接后的 provider 与远端固件定位

日期：2026-10-13。仅离线分析主代理采集的普通文件/静态设备树和一手公开源码。本代理未操作 ADB、未访问手机 debug 接口、未读写硬件寄存器、未修改插件。Linux模块偏移为可重定位ELF section offset，ADSP地址按MDT虚拟地址表示；两者都不是可执行操作指南。

## 当前结论

新增 live DT 已确认充电请求发往 **ADSP / LPASS 的 `msm/adsp/charger_pd`**，实际使用 MCA child，标准 `qcom,battery_charger` child disabled。真实 `qti_pmic_glink.ko` 是消息传输与 owner 分发层。随后对实际磁盘ADSP固件做正确Hexagon v79离线解码，已经闭合 `0x2003` property dispatch → `PmSchgChgr_SetEnableCharging` → 初始化可选ops → 两组PMIC地址bit0读改写。该属性还有上层联动，**不能把整个属性说成只改一个bit，也不能由静态调用链证明外部源持续供SYS**。[L1][L2][L9][S1]

新增 `hl7603.ko` 和厂商一手产品页交叉说明：它属于电池供电端的 boost 最低输出电压保障，驱动提供 VOUT threshold 和面板 HBM 状态联动；**这里的 boost/bypass 不构成“停电池充电、外部供系统”的后端证据。** 磁盘驱动没有 buck/CP ops 注册；暴露的 DATA debug 接口包含直接 I2C 写入，应排除为本次候选。[L3][H1]

普通 `adsp.mdt` / `adsp.bNN` 与 `adsp_dtb.mdt` / 分片，以及 `adspr.jsn`、`adsps.jsn`、`adspua.jsn` 已作为离线材料采集。公开源码缺少厂商handler，但已从实际磁盘固件找到上述handler及PMIC操作链；目前缺口是运行时选择、保供与恢复证据，不能据这些缺口判定硬件不支持。[L4][L9][S2][S3]

## 1. live GLINK 拓扑已经确认

主代理在 `.tooling/bypass-check/reconnect-acquisition/live-dt/soc/qcom,pmic_glink/` 采集的静态 bytes：[L1]

| 属性 | 原始值（NUL以分项表示） |
| --- | --- |
| compatible | `qcom,qti-pmic-glink` |
| qcom,pmic-glink-channel | `PMIC_RTR_ADSP_APPS` |
| qcom,subsys-name | `lpass` |
| qcom,protection-domain | `tms/servreg`, `msm/adsp/charger_pd` |
| mca,adsp_glink/compatible、status | `mca,adsp_glink`, `ok` |
| qcom,battery_charger/compatible、status | `qcom,battery-charger`, `disabled` |

driver directory 中 MCA child 绑定为 `soc:qcom,pmic_glink:mca,adsp_glink`，module metadata 中 `mca_adsp_glink` 和 `qti_pmic_glink` initstate=live。**新增10份live `.note.gnu.build-id` 与相应磁盘ELF note逐字节一致**，包括HL7603、QTI transport、MCA ADSP/proxy和控制链模块；这提供当前已加载模块与分析ELF的正面构建身份对应，旧vermagic不能单独否定该匹配。[L1] build-id不证明远端工作状态，也不是逐字节内存完整性/无运行时patch检查。

小米官方固定 DT 的 ADSP remoteproc compatible 为 `qcom,sun-adsp-pas`，firmware-name为 `adsp.mdt`, `adsp_dtb.mdt`；其 glink-edge remote-pid=2、label=adsp、glink-label=lpass，`PMIC_RTR_ADSP_APPS` channel 挂在此 edge。与新增 live GLINK 相符。[S4]

同固定内核的 `qcom_q6v5_pas.c` 中 `sun_adsp_resource` 也指定上述两个文件，ssr_name=lpass、sysmon_name=adsp、smem_host_id=2；probe允许 DT覆盖默认 firmware-name。公开源码定位的是框架，未包含厂商 charger protection domain 的请求 handler。[S2]

## 2. 真实 QTI transport 不是远端 charger handler

新 `qti_pmic_glink.ko` SHA-256：`890d46b352fce1ae1a762f160660bdf23445d2bad6b5f7ea3c93d0a6cff7d71f`。关键离线调用：[L2]

- `pmic_glink_write` `.text+0x4`：在 `0x7c` 调用 `rpmsg_send`，发送客户端消息。
- `pmic_glink_rx_work` `.text+0xb60`：`0xbe4` 调用 `idr_find` 按消息开头 owner字段找 client，`0xc48` 间接调用相应消息 callback。
- `pmic_glink_rpmsg_callback` `.text+0x1274`：检查长度并将消息复制入工作队列。
- ELF 字符串和绑定 compatible 包含 `qcom,qti-pmic-glink`、两个 ADSP/APPS channel，以及 protection-domain/subsys属性名。

官方 `drivers/soc/qcom/qti_pmic_glink.c` 与此架构匹配：读取 DT channel/subsys/protection-domain，RPMSG match包含 `PMIC_RTR_ADSP_APPS`、`PMIC_LOGS_ADSP_APPS`，owner用于client路由。[S1] 同仓的 `pmic_glink.c` 为另一通用实现，只能参考，不应把它代替本机匹配 `qcom,qti-pmic-glink` 的实现。

前轮已经证明 `mca_qcom_subpmic_proxy` 发送 owner=`0x800a`、property=`0x2003`、1-byte charging enable payload。仅分析Linux模块与QTI transport时，证据链止于RPMSG；追加ADSP离线证据后，见4.5：

`buck ops -> qcom_subpmic_enable_charging -> MCA GLINK property 0x2003 -> pmic_glink_write -> RPMSG ADSP channel -> 离线ADSP buck property dispatch -> PmSchgChgr API -> 模型选择的PMIC ops`

`0x2003` 是 MCA 属性号，不能作为 PMIC 寄存器地址；QTI transport 的 ACK/发送成功也不说明远端保留 SYS 输入。[L2][L5]

## 3. HL7603 的实际驱动作用

新 `hl7603.ko` SHA-256：`1677ba723eb65f29351d8fc25a7a464a0acbdecf6a0e16e9d993eaab17d2f585`。本机绑定 `6-0077 -> hl7603`，SC8585 `6-006e -> sc8581`；HL7603 static DT compatible=`hl7603`、reg=0x77，配置 `vout_threshold`，并不是仅凭文件名判定。[L1][L3]

厂商官方产品页明确：HL7603 以单节 Li-Ion 等电池供电，在电池低于系统最小需求电压时提供最低输出电压，以延长运行时间；可在 Bypass Mode 与 Boost Mode之间平滑转换，也支持 Forced Bypass以降低静态电流。[H1] 这是 boost regulator的转换模式术语；不能推导适配器端禁充或电池隔离。该页面没有提供本机接线保证，也不应据此假定手机所有负载必经 HL7603。

| 实际 ELF 函数 | 关键偏移与行为 | 可得判断 |
| --- | --- | --- |
| `hl7603_probe` `0x434` | `0x4a8` parse VOUT threshold，默认3400；`0x51c`调用threshold setter；`0x570`注册type7 notifier | 电压阈值初始化与面板事件 |
| `hl7603_set_voltage_threshold` `0x360` | `0x3c4`写 I2C reg2；成功后 `0x3d8`读回reg2用于日志 | 修改最低输出相关阈值，不是已定位的充电开关 |
| `hl7603_panel_notifier_cb` `0x5f4` | 只处理event0x41，按payload选择普通/HBM阈值，在`0x62c`调用setter | BTF将0x41命名`MCA_EVENT_PANEL_HBM_STATE_CHANGE` |
| `boost_debugfs_show` `0x4` | 会读reg2、reg0或address/count指定区间 | 并非全部是内存状态格式化 |
| `boost_debugfs_store` `0x220` | 除threshold和address/count存储外，DATA分支`0x354`直接I2C写 | 未建立安全范围、权限和恢复；排除debug接口 |

BTF `boost_attr_list` 只有 VOUT_THRESHOLD=0、CHIP_ID=1、ADDRESS=2、COUNT=3、DATA=4；完整符号/relocation未见 `platform_class_buckchg_ops_register` 或 `platform_class_cp_register_ops`。这说明这份 ELF 没有提供对应ops入口，不说明外围硬件一定没有其他控制能力。[L3]

磁盘 `vermagic` 仍为旧 `6.6.57-android15-8-g6f1d969db78f-4k` 构建，新增HL7603和QTI的live build-id均与各自ELF匹配，故不能再泛称没有运行构建身份对应。所有HL寄存器说明只描述离线所见驱动，不作直接实机访问建议。

## 4. 普通固件文件的分阶段离线分析

主代理普通目录元数据已确认当前固件目录 `/vendor/firmware_mnt/image/` 中：[L4]

- `adsp.mdt` 8364 bytes，`adsp_dtb.mdt` 4044 bytes。
- `adsp.b00` 至更高编号的若干分片、`adsp_dtb.b00`、`.b01`、`.b02`。编号缺口不应解释为丢失，须按MDT program headers判断。
- `adspr.jsn` 513 bytes、`adsps.jsn` 515 bytes、`adspua.jsn` 703 bytes，可离线查看 service/protection-domain清单。

官方 `mdt_loader.c` 从MDT ELF headers识别PT_LOAD、hash、零长度段，并按 `b%02d` 构造split文件名；分片size需等于header p_filesz。并非所有MDT自身包含code：若 p_offset+p_filesz超过MDT长度，内容通常在split段中。读取MDT metadata不等于取得所有handler code。[S3]

`sun_adsp_resource.uses_elf64=true` 在此源代码里影响coredump格式，不能用来预判固件MDT的EI_CLASS、machine或指令集。实际MDT必须独立检查ELF魔数、class、endianness、machine、header bounds、program headers和各分片长度，才决定可用离线解析工具。[S2][S3]

### 4.1 实际header与JSN离线解析

新增普通小文件已采集至 `reconnect-acquisition/firmware/`，manifest为 `firmware-header-manifest.json`。独立解析ELF魔数、class、endianness、header bounds和全部program headers后：[L6]

| 文件 | 实际结果 | 意义 |
| --- | --- | --- |
| adsp.mdt | ELF32、little endian、e_machine=164、ET_EXEC、57 program headers；entry=0x9f300000 | Hexagon固件，不得用AArch64解码；MDT自身不是完整代码 |
| adsp_dtb.mdt | ELF32、little endian、e_machine=1、ET_EXEC、3 program headers | 不能据占位machine认定为SPARC程序；b01有159100-byte load payload，需离线检查是否FDT |
| adspr.jsn | domain=adsp、subdomain=root_pd、tms/servreg | 普通service registry定义 |
| adsps.jsn | domain=adsp、subdomain=sensor_pd、tms/servreg | 同上 |
| adspua.jsn | domain=adsp、subdomain=audio_pd，另avs/audio | 同上 |

三份JSN都未列charger_pd，不等于charger_pd不存在；live DT已明确订阅该PD，需要继续寻找固件内部/其他注册文件。adsp phdr27和55的p_filesz=0，故b27/b55不存在与headers一致。其他非空split段size与主代理目录元数据相符。

以下保留header解析阶段制定的采集顺序，后续已取得相关文件并完成4.5的handler追踪。该阶段不凭大小/地址猜测哪个PD属于哪个段，优先追加的**普通静态文件**材料为：

1. `adsp_dtb.b01`，159100 bytes；先验证FDT magic/size，解析远端设备配置是否包含charger/PMIC型号和path信息。
2. ADSP只读、非执行PT_LOAD分片 `b06,b12,b13,b15,b18,b22,b23,b24,b28,b40,b44,b50,b51,b52,b53,b54`，合计5,815,997 bytes。用于找charger/MCA/PMIC/BAT/SYS字符串、常量表，并按phdr的vaddr定位。这里的“只读”指固件ELF flags，采集动作本身对全部文件均只读。
3. 如果只读数据没有足够线索，再分析可写、非执行PT_LOAD分片 `b02,b08,b10,b11,b14,b16,b20,b21,b26,b29,b35,b36,b37,b38,b39,b42,b43,b45,b48,b49`，合计5,241,560 bytes。
4. 只有数据提供可定位xref后，再选择相关代码分片。当前全部执行段合计19,190,301 bytes，尚无逐段charger归属证据，不把代码段大小当作选择依据。

字符串或 `0x2003` 常量命中不证明远端handler或电气行为；即使获得实际代码，也须使用Hexagon支持的离线反汇编工具并验证控制流。不能用远端ulog/debug、memory dump、寄存器probe或recovery动作代替普通文件分析。

### 4.2 已采只读数据字符串定位

主代理随后按上述范围取得16个只读非执行ADSP分片及 `adsp_dtb.b01`，17文件共5,975,097 bytes，size与phdr一致，manifest为 `firmware-data-manifest.json`。本代理只扫描本地普通文件，未对手机访问固件服务。[L7]

使用ASCII printable strings（长度至少5）与charging/PMIC关键词筛选，共974命中，**946个集中于 `adsp.b22`**。该段为222028 bytes，vaddr base=`0x602c0000`；以下是真实字节及其定位，并不是反编译函数定义：

| b22文件偏移 | phdr推算vaddr | 字符串线索 |
| --- | --- | --- |
| 0x2bc8 | 0x602c2bc8 | `mca_core.c:Info: set fcc %d` |
| 0x2c4c | 0x602c2c4c | `mca_core.c:Info: set suspend %d` |
| 0x772c | 0x602c772c | `mca_glink.c:Info: evt_buf->msg_owner is %x, evt_buf->opcode is %d` |
| 0x133e0 | 0x602d33e0 | `pm_chgr.c:ERROR: PmSchgChgr_SetEnableCharging: STATUS_ERROR_INVALID_ARGUMENT` |
| 0x13430 | 0x602d3430 | `pm_chgr.c:ERROR: PmSchgChgr_SetEnableCharging: Status = 0x%X` |
| 0x13470 | 0x602d3470 | `pm_chgr.c:ERROR: PmSchgChgr_GetEnableCharging: STATUS_ERROR_INVALID_ARGUMENT` |
| 0x2afbc | 0x602eafbc | `PmicTccPlatform_Schg_PDPhy_R1_LPD_PM8550B_HiZ_Cfg` 日志 |
| 0x33690 | 0x602f3690 | `sw_8750b.c:Info: set_usb_suspend: %d` |
| 0x33838 | 0x602f3838 | `sw_8750b.c:Info: set_fcc: %d` |

这提供当前firmware中MCA消息处理、充电使能底层API、ICL/FCC/suspend实现的明确搜索锚点。`PM8550B`日志说明固件包含命名为该平台的PD PHY/LPD实现，不能直接认定buck硅片、实际选择平台或相应寄存器。未看到同等明确的VSYS保持/电池隔离语义，也没有字符串把属性0x2003直接映射至上述API。

对已采RO数据进行4-byte对齐的little-endian `0x2003` 扫描，没有命中。这仅描述当前数据范围，不表示handler不存在：属性可能编码在Hexagon立即数、表索引、未采RW或其他表示中。

字符串定位阶段据相邻 `0x600...` 装载布局提出追加 `adsp.b19`（1,872,388 bytes，vaddr=`0x60000000`，执行段）和 `b20`/`b21`（22,856/272 bytes，非执行可写数据）。**当时这只是候选链接布局假设**，不能以section相邻关系代替0x2003 dispatch与 `SetEnableCharging` 的控制流证明。当时Apple LLVM没有Hexagon支持；随后相关分片已取得（4.4），正确v79工具已就绪（4.3），执行链已独立追踪（4.5）。

完整字符串地址记录为 `charger-strings.json`，补充短字符串扫描与充电API筛选为 `b22-key-strings.json`，data常量扫描为 `property-id-data-hits.json`。远端DT payload已交独立证据代理解析，本文不重复认定其硬件配置。

### 4.4 b19/b20/b21 已取得的结构关联

主代理随后只读采集b19/b20/b21，共1,895,516 bytes；manifest为 `firmware-charger-code-manifest.json`，实际文件长度与phdr一致。本代理仅离线处理。[L8]

| 工件位置 | 原始字符串/数据 | 可用含义 |
| --- | --- | --- |
| b19+0x16d774 / vaddr0x6016d774 | `mca_get_charger_buck_properties` | 精确远端get接口名字锚点 |
| b19+0x16d92c / vaddr0x6016d92c | `mca_set_charger_buck_properties` | 精确远端set接口名字锚点 |
| b19+0x172aee / vaddr0x60172aee | `mca_glink_process_rx_data` | 远端消息处理名字锚点 |
| b19+0x185c54 / vaddr0x60185c54 | `PmSchgChgr_SetEnableCharging` | 底层充电使能API名字锚点 |
| b19+0x185cef / vaddr0x60185cef | `PmSchgChgr_GetEnableCharging` | 对应getter名字锚点 |
| b20+0x1320起 | Hexagon GDB register XML | 固件包含debugger描述数据；不访问设备debug服务 |
| b20+0x2e18 | `BM_VOTER0` | 票机制相关名字线索，未定位其结构或语义 |

这些位置是**字符串地址，不是同名函数入口地址**。对上述字符串地址进行b19/b20/b21的4-byte对齐absolute pointer扫描未有命中；Hexagon可能用编码立即数或其他引用形式，不能由此判断没有被调用。

另一个更可靠的数据关联是：b19中有946个对齐32-bit值精确指向b22充电/PMIC日志字符串起点。多个位置可见相邻重复的“opaque32字段、2/4/8等级样字段、b22字符串地址”布局。例如b19+0x16efe4的value=0x602c2bc8，正好指向 `mca_core.c:Info: set fcc %d`；b19+0x16f008的value=0x602c2c4c指向set suspend日志。这证明两段保存互相关联的日志数据，而非单纯相邻地址猜测。未解码前不能把这些pointer值当作执行中的load/call/xref。

b19的可执行PT_LOAD同时包含大量ASCII和日志metadata，不能假设整个分片均为指令。b20/b21中也有落入b19地址范围的32-bit值，但在未建立结构定义和控制流时不将其认定为函数表。新增三个分片没有4-byte对齐raw `0x2003` 常量命中，同样不排除属性dispatch用立即数、表索引或其他表示。

该阶段只定位了接口名字与日志关联。后续正确Hexagon decoder已建立4.5所述执行链，保供/准确恢复仍需独立证据。XML里的 `hexagonv5` 仅为嵌入debugger描述，不能据此认定此固件全部指令使用该ISA版本。

### 4.3 正规Hexagon v79工具已就绪

主代理在工作区下载Homebrew官方LLVM23.1.3 ARM64 bottle并核验官方SHA，提取所需工具/动态库、处理本地依赖，没有安装系统工具。本地 `hexagon-host-tools/charger-analysis.elf` 只是按原始b19虚拟地址构造分析wrapper；`charger-v79.disasm.txt` 明确使用 `--triple=hexagon --mcpu=hexagonv79`，退出0且stderr为空。不能假设整个b19均为代码，下面只沿已验证的函数/表/调用追踪。[L9][T1]

实际MDT `e_flags=0x79`；LLVM固定tag `llvmorg-23.1.3` 的ELF定义将0x79映射至Hexagon ISA_V79，Hexagon.td含hexagonv79 CPU。默认ELF CPU推测并不会从该字段自动选择Hexagon v79，故明确指定CPU。解码器按packet地址处理PC相对运算，`add(pc,imm)`不能用单条显示地址代替packet开头；本节主要证据是绝对立即数和直接calls。[T2][T3][T4]

### 4.5 实际磁盘ADSP的充电使能与输入暂停调用链

以下是同一组已采普通固件的**离线执行链**，不是手机当前ADSP内存完整性/运行状态证明；Linux live build-id匹配也不能延伸为ADSP内存匹配。[L8][L9]

`mca_set_charger_buck_properties` 位于0x60031450：0x600314a4减property基0x2003，检查索引不超过0x28，0x600314bc读取41项表0x6016d5ac，0x600314c0跳转。原始b19表逐项解析保存在 `buck-set-dispatch-table.json`。关键映射为：

| 属性 | 远端分支 | 已证明动作 |
| --- | --- | --- |
| 0x2003 CHG_ENABLE | 0x600314c4 | 取ctx+3 device index，0x600314d0读取payload byte，0x600314d4调用0x60075bb0 `PmSchgChgr_SetEnableCharging`；附加联动的零值判断另在0x600314e0读取payload32 |
| 0x2005 FCC | 0x60031680 | model0x4c调用0x6002e700，否则0x6002e770；继续间接ops+0x80/0x84，尚未在本文闭合全链 |
| 0x2008 ICL | 0x600317b4 | 调用0x60048330聚合器，technology index0、HLOS voter、请求值为payload32 |
| 0x2019 INPUT_SUSPEND | 0x60031a7c | 0x60031a80读取payload32，0x60031a84转为非零bool；同聚合器，technology index3、HLOS voter |

0x60075bb0名字有实际logger立即数引用；它按device index查 `*(0x602af5c4 + index*0x24)`，取ops+4后callr。该表在b20的BSS范围，不能从文件补零推导runtime pointer。离线初始化0x600909b0 `InitializePeripheralSchgChgr` 确实生成同一entry，并按输入PmicModel配置ops；58项switch原始表0x6018e408支持下列8个模型值。[L9][L10]

| PmicModel数值 | 写入entry+4的ops | ops+4 enable实现 | 该实现PMIC地址 |
| --- | --- | --- | --- |
| 0x20、0x2e | 0x601cdf38 | 0x6008ca80 | 0x1042 |
| 0x29 | 0x601cdf9c | 0x6008ca80 | 0x1042 |
| 0x32 | 0x601ce000 | 0x6008e330 | 0x2646 |
| 0x47 | 0x601ce064 | 0x6008e330 | 0x2646 |
| 0x56 | 0x601ce0c8 | 0x6008e330 | 0x2646 |
| 0x4c、0x59 | 0x601ce12c | 0x6008e330 | 0x2646 |

这6张常量ops表位于b20的file-backed范围，和BSS runtime entry表不同。**模型是固件内部数值，本文没有把它们强行映射为本机实际硅片型号**；runtime device index、model、slave selection均未直接观察。独立代理复核58项映射及deinitializer：0x60091060清entry+4，0x6009106c清active，不能因此把当前entry当成永远有效。[L10]

两种enable实现都调用0x6005b330，参数为BusID=0、SlaveID=entry+1、PMIC地址0x1042/0x2646、mask=1、data=enable byte。0x6005b330先调用0x6005ae10读取旧字节，组合 `(data & mask) | (old & ~mask)`，然后0x6005b3f0写一个字节；后者实际日志名为 `battman_pmio_spmi_writemultibytes`，错误anchor明确 `Spmi_WriteLong`，调用0x6012323c。故**这个底层API只改变相应地址bit0并保留其他bits**，与INPUT_SUSPEND走不同逻辑。但完整0x2003在API之后还调用ctx+0xbc及ctx+0xc0对象ops；根代理已追到0x2c42相关位写入等联动，不能以底层API单一bit行为替代整个属性的副作用分析。[L9][L11]

对应getter也已闭合：0x6002f3e0 `mca_get_charger_buck_properties` 减基0x2001后读42项表0x6016d378；0x2003→0x6002f478→0x60075dc0 `PmSchgChgr_GetEnableCharging`。它取相同runtime ops的**+0x34**，old实现0x6008d9a0/new实现0x6008e990，对应0x1042/0x2646以mask1调用0x6005ae10并返回byte。它有硬件位读取调用依据，仍不能把读取成功当作供SYS或电池零电流证明，也未在手机调用。[L9]

INPUT_SUSPEND更深链为：0x60048330 `battmngr_tech_aggregator_execute`；0x60201210 voter在0x60037420..30以名字 `hlos` 注册；technology index3的静态初始化名字为 `chgr_suspend`，0x600373c0..c8把callback注册为0x60035fc0 `mca_set_usb_suspend_tot_aggrcb`。它把聚合值非零映射为1，否则0，调用0x6002e400，经ctx+0xbc对象ops+0x28。新backend initializer0x60138060在0x60138138设置该slot为0x60138820 `set_usb_suspend`；后者0x601388d8..e0调用0x6002d7e0，地址0x2954、mask1、bool data。该通用bus函数按ctx+1分派：selector0经0x6002da10到同SPMI RMW函数0x6005b330，其他selector走其他backend。因此这是暂停输入候选，**不是保外部输入的电池禁充开关**；聚合器的其他voters还可能继续保持暂停，写0不能保证最终解除。[L9]

目前禁止把上述ADSP虚拟地址/PMIC地址用作手机直接读写命令。已证明静态控制分离，尚未证明电气分离、负载下保供、完整属性联动恢复、charge pump返回普通充电策略或板级BAT/SYS FET行为。

## 5. PMIC身份与公开源码边界

官方dada DT经 `sm8750-xiaomi-common.dtsi`、sun MTP overlays引入多个PMIC定义，包括PM8550家族和PMIH010x。公开PMIH010x DT有SPMI PMIC、温度、GPIO、haptics、BCL等peripheral，不能由这些节点认定 `BUCK_ENABLE_CHARGING` 最终操作哪个charger silicon或BAT/SYS FET。[S4][L5]

当前资料边界：

1. 已有：Linux发出的准确MCA属性、当前GLINK channel和PD、真实QTI transport、外部CP和HL7603绑定、普通ADSP固件文件存在。
2. 已有磁盘固件0x2003远端handler到PMIC bit操作及getter的离线链；未有对应当前ADSP内存的身份/执行状态证明、输入/SYS保持条件、buck与CP完整联动、准确恢复接口。
3. 未有：板级外部源/CP/buck/BAT/SYS电路图、可证明battery参与/隔离的硬件定义。

小米公开内核MCA目录是外部vendor源码symlink，不包含完整实现；公开DT与Linux remoteproc/transport框架也不构成远端ADSP代码。公开Android V固定提交与当前Android16固件不是版本对应证明。本轮通过实际磁盘固件的正确Hexagon解码补齐了0x2003 handler、初始化ops、PMIC位操作和getter链，仍不能把候选批准为安全可写后端，更不能声称已完成禁充保供验证。[S0][L5][L9]

## 引用与工件

- [S0] 官方内核固定提交MCA symlink：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca>。
- [S1] 官方QTI transport：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/soc/qcom/qti_pmic_glink.c>，关键511-514 RPMSG、602-644 DT、397-423 owner路由；本地 `reconnect-provider/qti_pmic_glink.c`。同目录通用 `pmic_glink.c`仅对比。
- [S2] 官方ADSP loader：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/remoteproc/qcom_q6v5_pas.c>，关键1315-1326 DT文件名覆盖、1341-1344 coredump格式、1893-1908 sun资源；本地 `reconnect-provider/qcom_q6v5_pas.c`。
- [S3] 官方MDT loader：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/soc/qcom/mdt_loader.c>，关键21-63有效段/分片、280-304 split识别；本地 `reconnect-provider/mdt_loader.c`。
- [S4] 官方sun DT：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/sun.dtsi>，关键2769-2845 ADSP、3001-3016 GLINK；PMIC overlay和PMIH010x：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/sun-pmic-overlay.dtsi>、<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/pmih010x.dtsi>；副本在 `power-path/`。其他版本overlay须独立核对。
- [H1] HL7603厂商产品页：<https://hmisemi.com/hl7603/>，已读取正文630-632，副本 `reconnect-provider/hl7603-manufacturer.html`。另保存该页链接的官方product brief PDF，本文未解析PDF内容，不以未读内容作证据。
- [T1] Homebrew官方LLVM动态元数据与配方：<https://formulae.brew.sh/api/formula/llvm.json>、<https://github.com/Homebrew/homebrew-core/blob/HEAD/Formula/l/llvm.rb>。本地snapshot `reconnect-provider/homebrew-llvm.json`、`homebrew-llvm.rb`；属于下载时内容，不是当前手机版本来源，文件hash记入工件manifest。
- [L1] `.tooling/bypass-check/reconnect-acquisition/live-dt/`、`binding-directory.txt`、`module-metadata.txt`、`metadata-manifest.json`、`static-manifest.json`、`module-build-id-comparison.json`。均由主代理采集；最后文件10项note_matches=true，含原始live/ELF note hex。
- [L2] `reconnect-acquisition/driver-binaries/qti_pmic_glink.ko`；离线反汇编 `reconnect-provider/qti_pmic_glink.disasm.txt`。
- [L3] `reconnect-acquisition/driver-binaries/hl7603.ko`；离线 `reconnect-provider/hl7603.disasm.txt`、`hl7603.symbols.txt`、`hl7603.btf.json`。BTF未解析base字符串不用于推断。
- [L4] `reconnect-acquisition/adsp-firmware-files.txt`、`firmware-directories.txt`，目录/size/日期不证明文件当前已加载或内存code与磁盘一致。
- [L5] `docs/DADA_CHARGER_PROVIDER_RESEARCH.md`、`docs/DADA_POWER_PATH_EVIDENCE.md`，前轮provider属性/版本/硬件边界。
- [L6] `reconnect-acquisition/firmware/`、`firmware-header-manifest.json`；本代理离线解析结果 `reconnect-provider/adsp-headers.json`、`adsp_dtb-headers.json`。检查header bounds后按实际ELF32 little endian解全部phdr，不能替代代码解析或远端执行状态验证。
- [L7] `reconnect-acquisition/firmware-data-manifest.json`及其对应普通分片；本代理 `reconnect-provider/charger-strings.json`、`charger-key-strings.json`、`b22-key-strings.json`、`property-id-data-hits.json`。ASCII字符串与地址加法只作为离线定位证据。
- [T2] LLVM23固定tag ELF定义：<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/include/llvm/BinaryFormat/ELF.h>；本地 `reconnect-provider/llvm23-ELF.h`。
- [T3] 同tag CPU与推测实现：<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/lib/Target/Hexagon/Hexagon.td>、<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/lib/Object/ELFObjectFile.cpp>、<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/lib/Target/Hexagon/MCTargetDesc/HexagonMCTargetDesc.cpp>；本地llvm23 snapshots。
- [T4] 同tag Hexagon decoder：<https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/lib/Target/Hexagon/Disassembler/HexagonDisassembler.cpp>；本地 `reconnect-provider/llvm23-HexagonDisassembler.cpp`。
- [L9] `hexagon-host-tools/charger-v79.disasm.txt`、`charger-analysis.elf`、`reconnect-provider/buck-set-dispatch-table.json`、`schg-initializer-data.json`、`buck-remote-key-windows.txt`。由正确v79 decoder与原始分片表解析交叉建立，地址为固件vaddr。
- [L10] 独立初始化审查 `reconnect-control/remote-provider-init/REVIEW.md`、`reconnect-control/remote-provider-init/independent-init-audit.json`，主代理 `hexagon-host-tools/root-dispatch-review.txt`、`root-provider-enable-review.txt`。
- [L11] 根代理上层联动复核 `hexagon-host-tools/root-enable-side-effects-review.txt`；完整联动语义以其最终审查为准，不在本文为未知位命名。
- [L8] `reconnect-acquisition/firmware-charger-code-manifest.json`及b19/b20/b21；本代理 `reconnect-provider/charger-code-data-strings.json`、`charger-absolute-data-candidates.json`、`charger-name-data-candidates.json`。记录真实文件偏移、phdr推算vaddr、字符串与精确对齐数据匹配；没有错误ISA的反汇编结果。

以上相对工件路径均位于 `.tooling/bypass-check/` 下。本报告只完成静态证据比较，没有手机写入或运行时供电效果验证。
