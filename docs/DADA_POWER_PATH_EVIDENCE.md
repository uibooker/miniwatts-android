# dada 电池禁充与系统供电通路：独立证据核验

日期：2026-10-13。对象：小米 15 国行 `24129PN74C` / `dada`，当前固件 `OS3.0.303.0.WOCCNXM`、Android 16。此代理只读取公开一手资料和已采集本地工件，没有执行 ADB、没有修改模块、没有读写手机电源属性。主代理报告本轮设备采集也没有电源节点写入或模式切换；后续 ADB 离线，未完成追加的 GLINK 静态设备树采集。

## 结论

**目前有“充电开关”和“输入通路分别控制”的软件证据，有真正禁充的候选链路，仍没有足够证据保证某个可恢复用户接口会让本机停止电池充电并继续用外部电源稳定供系统。** 当前不能把这些候选接入模块写后端。

最值得继续追踪的是 `charge_enable` 的 buck 请求，经策略 voter 到 `mca_qcom_subpmic_proxy` / MCA ADSP GLINK 的链路；必须继续确认远端固件如何实现禁充，以及独立请求票如何准确撤销。外部 SC8585 充电泵是否仍在充电、快速充电策略是否重新启用它，也是完整禁充必须回答的问题。[L1][L2][K2][D1][D2][D3]

以下三个概念必须分别验收：

| 目标 | 所需证据 | 当前结果 |
| --- | --- | --- |
| 软件停止充电请求 | store 协议、目标控制位/voter、provider 调用、请求自身撤销方法 | buck 请求链已经有候选；请求恢复与额外副作用待审核 |
| 外部电源继续维持系统 | 当前硬件/远端代码明确 SYS 输入维持；运行时输入有实际功率，电池没有持续放电 | 尚未证明 |
| 电池被物理隔离、不参与瞬时补电 | 对应本机的 BATFET/电源拓扑和允许工作状态，或足以排除电池补电的测量 | 尚未证明，不能由“禁充”推导 |

可实用的“禁充但保持系统供电”不一定要求把电池物理断开。相反，强制 BATFET/ship mode 可能使系统断电并破坏拔线后的回退；本报告不推荐这类操作。参考芯片官方资料也显示，禁充和输入功率不足时允许电池补电可以同时存在，因此“请求禁充”不能直接保证电池电流永远为零。[T2]

## 1. 一手来源与版本边界

1. 小米官方 `MiCode/kernel_devicetree` 的 `dada-v-oss` 分支固定提交为 `233bd52eddce9f081f8e87802796f5994babafa5`，提交说明是 Xiaomi 15 / 15 Pro Android V，基于 QCOM `LA.VENDOR.15.4.0.r1-08900-r1.0.r1_00022.1`。[D0]
2. 小米官方内核 `dada-v-oss` 固定提交为 `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5`。其 MCA 驱动目录为仓库外 proprietary 目录的符号链接，并不包含该实现；这限制了从公开 C 源追到底层硬件的能力。[K0][L1]
3. 这两个 Android V 公共快照不能自动对应当前 Android 16 固件。仅当某个属性与本机已采集 live DT 一致时，才把其具体内容用于本机比较；不能把其他节点、寄存器或固件实现也一并认定相同。[L2]
4. 本机磁盘 ELF、`/proc/modules` 中的加载记录与 driver 绑定是各自不同的证据。磁盘 SHA-256 与模块加载名称不能证明内存执行代码逐字节等于磁盘 ELF，既有 `vermagic` 与运行内核也不同。[L1][L2]
5. TI BQ25890、BQ25980 文档只用作电源拓扑和术语的反例。它们不是这台手机 buck/CP 芯片的认定，也不是可以移植到本机的寄存器说明。[T1][T2]

公开资料和提取文本保存在 `.tooling/bypass-check/power-path/`；`manifest.json` 记录哈希、大小与固定版本。所有本机资料来自主代理预先批准的普通文件/静态属性采集。本报告没有执行手机读取。

## 2. 官方设备树真正指出了什么

### 2.1 一个远端 PMIC 控制路径和一个外部 CP

`dada-sm8750.dtsi` 引入 `dada-charger-common.dtsi`。该文件：

- 在 I2C bus 下配置 `sc8585_master@6e`，`compatible="sc8585_master"`，`ic_role=0`；配置输入/电池过压、输入过流等阈值。它还配置 `bq27z561_standlone@55` 电量计和 `hl7603@77`。[D1][D4]
- 将 `mca_platform_cp` 启用为一颗 CP，`cp-num=1`，`cp-dir-list="cp_master"`。[D1]
- 启用 `mca_qcom_subpmic_proxy` 和 `mca_adsp_glink`，前者为 `mca,qcom_subpmic`，后者为 `mca,adsp_glink`。[D1][D2][D3]

主代理已采集的 live DT 中，`mca_platform_cp` 的 compatible、status、cp-num、cp-dir-list 与这些官方属性一致；`mca_qcom_subpmic_proxy` 的 compatible、status、otg_boost_src 也一致。实际采集的 I2C metadata 确认 SC8585 同名节点；磁盘驱动文件名为 `sc8581.ko`，因此文件名和匹配的设备名称也不应混为一谈。[L2]

这里能确认 MCA 分开管理 CP 与 buck/proxy。不能据此声称本机使用某个公开 TI buck、某个 SMB 芯片型号、某个未经定位的 BATFET 寄存器，或声称 CP 的旁路转换比就是电池隔离。

### 2.2 GLINK 边界：请求/响应不等于硬件保证

公共 `sun.dtsi` 的 `qcom,pmic_glink` 使用 `PMIC_RTR_ADSP_APPS`，`qcom,subsys-name="lpass"`，保护域为 `msm/adsp/charger_pd`。它的两个 child 是标准 `qcom,battery-charger` 与 MCA `mca,adsp_glink`，默认均 disabled；dada 文件明确启用的是 MCA child。[D1][D3]

因此，公开标准 `qti_battery_charger.c` 是理解 Linux power_supply 与远端消息的参考，并不能自动成为现实 MCA 节点的 provider。即使从本机 proxy ELF 确定某个请求 ID 和 GLINK set 消息，也只能证明 Linux 侧发送的目标和值；对端如何修改充电器、输入、充电泵、BATFET，以及应答是否代表请求入队还是硬件生效，需要对端实现、对应规格或足够完整的设备证据。[K2][L1]

本机 proxy 的离线反汇编中，`qcom_subpmic_enable_charging+0x40` 将 property ID `0x2003` 传给 `mca_adsp_glink_write_prop`；`qcom_subpmic_set_input_suspend+0x40` 用不同的 `0x2019`。这独立支持 AP 侧把禁充与暂停输入当作不同远端属性。[L4] 它仍不能证明远端实际禁充不会连带影响输入，也不能证明两个请求的所有状态可恢复。

当前缺失的静态核对是本机 `soc/qcom,pmic_glink` 的 channel、subsys、protection-domain，以及两个 child 的 compatible/status。追加采集因 ADB 离线未完成。公共源显示它们的设计，但本报告不把尚未采集的 live 属性写成现场事实。

### 2.3 path_control 不是已证明的电池通路开关

公共 `mca.dtsi` 明确注释：

- `gate_type`: 0 ovpgate、1 wpcgate、2 ext_bst_gate、3 wireless_vdd_gate、4 haptics_bst_gate。
- `control_scheme`: 0 GPIO、1 PMIC_REGISTER、2 CP_CHIP。
- 本机同类属性将 gate0 交给 CP_CHIP / `ovpgate,cp_chip_role`，gate2/3/4 采用 GPIO；path condition 包括 USB_IN、WIRELESS_IN、WIRELESS_REV、OTG_IN 和组合。
- `usbin_ctrl` 是 gate0 开、gate3 与 gate2 关。[D2]

本机已采集的 `control_scheme`、`path_condition`、`usbin_path` 和 `usbin_ctrl` 与这一公共定义一致。[L2]

这些是来源选择、输入门控和反向供电管理的证据，尚未出现电池与 SYS 之间的 BATFET gate 项。不能因 `control_value`、`usb_in`、`path` 的名称就把其写入当作电池旁路。当前排除把它当作旁路后端；这个排除不等于已经证明整个硬件没有任何电池通路控制能力。

## 3. Linux 定义与常见误判

| 名称/现象 | 一手定义或实际代码 | 正确解释 |
| --- | --- | --- |
| `charge_control_limit` | Linux ABI 称为最大允许充电电流、用于限速；实际 setter、单位、0 语义由具体 provider 决定。[U1] | 节点名称不证明支持“写 0 禁充”。MCA FCC=0 现已追到撤票，而非强制零电流。[L1] |
| 当前官方 `qti_battery_charger.c` | battery setter 和 writable callback 只列 start/end threshold；内部 thermal setter把 level 转 FCC。[K2] | 本快照不能作为“battery/charge_control_limit 可写”的证据，更不能当作当前 MCA sysfs 的执行实现 |
| `charge_behaviour=inhibit-charge` | Linux ABI 为“AC 连接时不充电”；`force-discharge` 则明确强制放电。[U1] | 若本机 provider 确实实现，inhibit-charge 是比 FCC/输入限流更贴近目标的语义候选；当前没有确认本机支持此属性 |
| `input_current_limit` | Linux ABI 控制输入 IBUS 上限。[U1] | 输入限流作用于外部可用功率，可能使电池补电；不保证停止电池充电 |
| `input_suspend` / HiZ | 已有 MCA 分析将其分到独立输入控制链；原现场 input_suspend 为**空输出**，suspend_status 为 0。[L1] | 暂停外部输入不能代替保持外部系统供电；不能把另一属性的 0 当作其读值 |
| `online=1` | Linux ABI 表示 VBUS present / fixed supply。[U1] | 不能证明输入电流足够支持系统，也不能证明电池电流为零 |
| `status=Not charging` | Linux ABI 为充电状态字段；不同 driver 的 getter实现不同。[U1] | 是辅助证据，不能单独证明系统输入路径和不补电 |
| `charge_type=Bypass` / CP bypass | Linux ABI 定义绕过 integrated converter、由适配器做转换；TI BQ25980 官方明说 “7-A bypass mode charge”。Linux 该 CP driver 独立设置 bypass 与 charge enable。[U1][T1][U2] | 仍可能给电池充电。不是“电池旁路供电”的同义词 |
| `smart_bypass` | 本机智能充电链为 scene/温度表选 FCC，表中值均正。[L1] | 当前模块请求的 FCC 限流策略，不是本报告所要证明的禁充/系统路径 |
| voter 有效值 | 官方 Qualcomm voter 把每个请求者的 enabled/value 与选举结果分别保存，MIN/MAX 无票时有效结果为负错误值；SET_ANY另有规则。[K1] | 总体有效值不是某个 requester 的原票，不能用它“原样恢复”请求者。Qualcomm voter也不能自动代替本机 MCA voter 的规则 |

参考 TI BQ25890 数据手册 §9.2.3.5 明确“充电禁用后 BATFET 关闭，转换器仍建立系统电压”；§9.2.6 明确 SYS 可以由输入、电池或两者供电；§9.2.6.2 输入过载、充电电流降到零后仍可能启用电池补电。[T2] 这些形成了应寻找的**证据类型**，没有提供对本机的电气保证。

## 4. 当前候选、排除项与缺口

| 类别 | 接口/链 | 证据级别 | 尚缺什么 / 为什么不执行 |
| --- | --- | --- | --- |
| 首要候选 | `mca_charge_interface/charge_enable` 的 buck requester -> enable voter -> platform set_chg -> QCOM proxy / ADSP | 已有三段 store 与策略/间接 provider 链证据；本轮链路代理另已发现 AND voter 和额外投票副作用。[L1][L3] | 确认 requester有效范围、独立撤票 API、原票可读性、`pmic_temp_term` override撤销副作用；确认远端禁充同时保输入；确认 CP/quickchg也不继续充电 |
| 语义候选，未在本机确认 | 本机若实现 `charge_behaviour` 的 inhibit-charge 或官方充电阈值策略 | Linux有定义，小米官方有80%暂停充电功能声明。[U1][X1][X2] | 找实际本机 provider和固件策略，核对其是否可恢复且不覆盖用户的80%健康保护；官网声明不能证明SYS路径 |
| 已追踪，暂不作为模块后端 | `soc_limit` / 厂商SOC保护策略 | kernel导出函数、event52到buck/quick/无线、buck charge-enable与input-voltage票和quick disable OR票已经闭环。[L5] | 没有soc_limit模块自身sysfs store；共用OEM soc_limit owner；现场smart_chg已有bit4对应厂商ENDURANCE_PRO；quick复杂退出与票准确撤销待整体评审，不得挪用已有owner |
| 排除 | business charger的debug SOC入口 | 可经debug_ctrl触及charger_partition_write_soc_limit持久写。[L5] | 不是仅在RAM保留的独立禁充请求，恢复和持久化风险；不读取/写入debug_ctrl探索 |
| 排除 | BMD / BTB状态 | 监测电池连接安全、发布状态/异常事件；仅有btb_sysfs_show，未找到store。[L5] | 安全保护检测，不是旁路用户开关；getter可能读ADC/GPIO/电量计，不能把unknown BTB属性泛读成纯内存 |
| 排除 | battery power_supply假定写开关、qcom_sysfs假定charge_enable | 已检查的battery setter只对部分property返回0但不使用传入value、不写硬件；qcom_sysfs store列表无charge_enable。[L5] | “setter返回成功”不代表有电源控制；qcom_sysfs的dam_ovpgate为输入gate，其他ship/thermal功能不满足独立禁充目标 |
| 排除为当前后端 | `smart_chg` bypass请求 / FCC=0 | 已证明是限流策略；零FCC撤票。[L1] | 不满足真正禁充需求 |
| 排除 | input_suspend、输入限流到0、HiZ、handle_allow_charge | 暂停输入/策略混合控制；后者本轮链路代理观察到同时投inputlimit与chg_enable。[L1][L3] | 容易改为电池供系统；不具备独立“保持输入”的证据 |
| 排除 | path_control直接门控、CP mode/bypass、ovpgate | 官方DT是输入/反向门控；CP bypass另有转换语义。[D2][U1][T1] | 不能代替电池充电开关；有转压和输入断开风险 |
| 排除 | ship mode、BATFET force-off、寄存器裸写、驱动卸载、强制GLINK/debug命令 | 不是已建立可恢复的用户接口；参考BATFET full-system reset存在断电语义。[T2] | 不进行探索性实机操作 |

`charge_enable` 的恢复尤其不能简化为“原来整体是1，所以最后再写1”。AND voter中的 requester写1和撤销 requester不是同一个动作；总体显示的有效客户端/结果没有提供原始 requester票快照。若setter会触动另一个 override，那么请求本身看似恢复后，该副作用仍可能存在。缺少准确逆操作时，本报告将其判为**未通过写入预审**。[L1][L3]

### 4.1 SOC保护链的闭环和边界

独立查看本机 `mca_soc_limit.ko` 的反汇编，`soc_limit_process` 在enable分支读取当前SOC，仅当 `SOC > threshold` 时将active置1，向type5发event52；disable分支置0后同样发event52。这是kernel导出函数，不是该模块自身暴露的用户sysfs store。BTF把event52识别为 `MCA_EVENT_SOC_LIMIT`。[L5]

链路代理完成的闭环为：business charger event52传播到buck、quick与无线策略；buck的SOC callback普通分支对 `chg_enable` 和 **`input_voltage`** 给 `soc_limit` 启用零票，解除时撤这两票并撤一条 `csd_pulse` charge-limit票。这里不是简单“input-current清零”。不过 `support_charge_more` 的另一分支会逐步限制charge/input current并最终禁充，因此不能把普通分支的发现泛化到所有固件和DT配置。[L5]

quick分支把 `soc_limit` 送入charge-disable OR voter，非零触发复杂的CP/PD停止流程；解除到零是OR中性票，但不是撤销active requester。smart_charge中的ENDURANCE_PRO（bit4）或navigation（bit1及阈值）也能驱动相同SOC链：周期中达到阈值触发，降低到阈值减5时解除，并涉及两种模式优先级。现场聚合 `smart_chg=80` 已含bit4，因此不能由新模块占用这个OEM owner，否则会改变现有健康保护/耐久策略。[L1][L5]

这条链提供了“厂商自身存在跨buck/quick的SOC暂停机制”的软件证据，但并未提供新模块可独立、无持久化副作用、可精确撤销的禁充入口，也没有填上ADSP对端保持SYS输入的电气语义缺口。debug SOC入口进一步调用partition写函数，当前排除。BMD是BTB安全检测与异常分发而不是用户旁路入口，当前也排除。[L5]

## 5. 只读取证建议及读取副作用审查

这里是供主代理审核的读取范围，不是手机执行脚本。设备再次在线也不自动开始采集；需先遵守本轮任务边界和对应节点 show/getter审核。

| 数据 | 推荐范围 | 副作用审查 |
| --- | --- | --- |
| 固件与绑定元数据 | getprop、`/proc/modules`、普通 `modules.dep`/`modules.load`；driver/of_node/module符号链接的目标 | 不读取硬件寄存器；仅列目录/readlink，不对未知设备节点open |
| 静态GLINK DT | `soc/qcom,pmic_glink/{compatible,qcom,pmic-glink-channel,qcom,subsys-name,qcom,protection-domain}`；child `mca,adsp_glink` 与 `qcom,battery_charger` 的 compatible/status | DT原始属性是静态字节，不调用driver sysfs show；路径需先确认为普通DT属性。当前尚未追加采集 |
| 已有DT补充 | 已发现 `sc8585_master@6e` 的 compatible/reg/status/role；`soc/mca_platform_cp` cp-num/cp-dir-list；相应of_node绑定 | 只读取静态属性，不遍历整套 sysfs 后逐一cat |
| 电池与USB遥测 | 已审查的 power_supply `current_now`、voltage_now、capacity、temp、status；USB online/current_now/voltage_now | 阅读实际provider getter，核对是否触发ADC、同步GLINK查询、缓存刷新或故障读取；确认以后才纳入固定遥测白名单。不能只用文件可读权限推断无副作用 |
| voter状态 | 已确认只格式化内存vote的状态/show，如charge_enable有效票；对应本机MCA完整requester票表 | 先确认show只读存储状态，或仅普通遥测getter。若记录有缺项，报告unknown，不能拼接其他节点值 |
| 禁止泛读 | `debug_ctrl`、`registers`、regmap debugfs、I2C/SPMI寄存器、故障/中断flag、ULOG/debug command、固件更新/校准接口 | 某些读取会清故障/IRQ、刷新缓存、触发硬件消息。TI BQ25890 §9.2.9 / §9.2.16.7的fault register明确read-to-clear。[T2] 这只是存在性反例，不表示本机每个同名节点都有相同副作用 |

主代理已审计采集 archive：前97个member构成五个完整子树，随后缺少额外的 `mca_adsp_glink` 路径导致错误文本进入原始archive尾部。已保留rawsource，并生成仅含验证有效prefix的 `live-charger-dt-verified-subset.tar` 与哈希/范围清单 `dt-acquisition-integrity.json`。本报告只引用这五棵已验证树及其单项原始属性；不把末尾错误、未列出的属性或未采GLINK节点当作“该节点不存在”的证据。[L2]

## 6. 候选操作的预审清单

以下全部满足之后，才有理由另行设计受控设备验证；这份清单不是实机执行授权，也没有给出可直接运行的写命令。

1. **身份与实现对应**：设备、固件、运行模块绑定、磁盘ELF/BTF、加载代码对应边界明确；若只能证明磁盘候选，则仍标记远端/运行实现未知。
2. **完整禁充覆盖**：buck和外部CP的并联/切换关系明确；候选不会只禁用其中一条而留下另一条充电路径，quickchg服务不会在下一轮重新启用充电。
3. **输入维持证据**：明确操作只影响充电，不使输入HiZ/suspend/OVPGATE关闭、不把ICL降到不足供系统；具备本机对应的硬件/远端依据。
4. **请求所有权与准确恢复**：能读取本请求者原票，或明确本请求者此前不存在；能撤销该请求者而不覆盖thermal/JEITA/用户健康保护；不能用全局effective值作为原票。
5. **副作用与恢复**：解析全部setter调用和错误分支，列出其他override/客户端/模式变化；每项都有精确逆操作和读取验证。写1代替撤票、重启代替恢复均不能直接通过。
6. **失败路径**：USB拔除、源功率不足、温度异常、服务竞态、GLINK超时、ACK与实际状态不一致、SIGKILL、崩溃重启时，均有明确退出和不会丢失原票的设计。只在模拟器/离线检查中先验证。
7. **读回覆盖**：请求状态、有效结果、provider实际状态与输入遥测分开呈现；不把ACK、sysfs回显或battery status当作硬件供电证明。
8. **候选被拒绝时停止**：未知字串/值域、不可撤票、调用链缺失、不明debug sideeffect，不靠试0/1、轮试不同节点、裸寄存器或切驱动来补证据。

## 7. 最后对比验证应采用的可检验判据

本轮只完成离线证据对比，不完成写入验证。若后续候选通过预审并获得具体受控验证授权，可把目标定义为“禁充、输入维持、电池净充放电很小”，并将硬件隔离作为单独更强的目标。

- 先确认电池电流方向与单位。本机vendor不一定遵守通用Linux符号约定；正负号需要由实际getter/芯片规格解释，不用绝对值代替。Linux ABI约定电池充电正、放电负。[U1]
- 保留同SOC、同适配器/线缆、接近温度、同负载的原始时间序列，区分原本就80%暂停、热限流、供电源不足和新禁充请求。
- 在确认输入功率有余量的条件下，验证输入持续有实际能量输送，且电池没有持续充电或持续放电。VBUS存在与online=1不够，单帧Ibat接近0也不够。
- 至少分别比较待机和一档稳定系统负载，并在准确撤票后确认原充电策略恢复；若源不足导致电池补电，报告“禁充仍有补电”，不要报告“完全旁路”。不通过人为热冲击、高负载极限、拔线或低SOC制造风险来补覆盖。
- 用积分电流/charge_counter及长窗口趋势交叉检查；整数SOC不变只能作为辅助。手机传感器精度、采样延迟、远端缓存和电池瞬态参与都须列作测量限度。
- 只有对应本机的电路/远端控制语义或更充分测量才能进一步称为电池物理隔离。本报告不把“电池净电流很小”升级为这一结论。[U1][T2]

## 引用

- [D0] MiCode 官方设备树提交及分支：<https://github.com/MiCode/kernel_devicetree/commit/233bd52eddce9f081f8e87802796f5994babafa5>，<https://github.com/MiCode/kernel_devicetree/tree/dada-v-oss>。本地 `dt-branch.json`。
- [D1] 官方 dada 充电设备树：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/dada-charger-common.dtsi>。关键范围：75-123 I2C硬件，142-179 MCA GLINK/proxy/buck，480-482 path，793-797 CP。
- [D2] 官方 MCA 通用设备树：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/mca.dtsi>。关键范围：495-584 path condition/gate映射，629-634 CP。
- [D3] 官方 sun 设备树：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/sun.dtsi#L3001>。关键范围：3001-3016 PMIC GLINK与child。
- [D4] 官方 dada 设备树入口：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/dada-sm8750.dtsi#L9>。
- [K0] 官方内核 MCA 符号链接：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca>。
- [K1] 官方树中的 Qualcomm voter 实现：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/pmic-voter.c>。关键范围：301-317有效结果说明，413-500独立票与选举，641-699只读status实现。此实现仅作voter模型参考，不认定MCA逻辑等同。
- [K2] 官方 QTI battery driver：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qti_battery_charger.c>。关键范围：1174-1255阈值远端请求，1273-1329 FCC与thermal，1376-1407 setter/writable。
- [U1] Linux v6.6 power_supply sysfs ABI：<https://github.com/torvalds/linux/blob/v6.6/Documentation/ABI/testing/sysfs-class-power>。关键属性current_now、charge_control_limit、charge_type、status、charge_behaviour、input_current_limit、online；类文档：<https://github.com/torvalds/linux/blob/v6.6/Documentation/power/power_supply_class.rst>。
- [U2] Linux v6.6 BQ25980 driver：<https://github.com/torvalds/linux/blob/v6.6/drivers/power/supply/bq25980_charger.c>。关键函数bq25980_set_bypass、bq25980_set_chg_en与CHARGE_TYPE getter。
- [T1] TI官方 BQ25980 datasheet，SLUSEC6，September 2020，实际取得10页，正文p1-2：<https://www.ti.com/lit/ds/symlink/bq25980.pdf>。本地 `bq25980.pdf` 与完整逐页提取 `bq25980.txt`；不包含全部芯片寄存器规格，不用于本机候选。
- [T2] TI官方 BQ25890/BQ25892 datasheet，SLUSC86D，March 2015 / revised October 2022，§9.2.3.5、9.2.6、9.2.9、9.2.10、9.2.16.7：<https://www.ti.com/lit/ds/symlink/bq25890.pdf>。本地 `bq25890.pdf` 与完整逐页提取 `bq25890.txt`。芯片不是本机认定，只用于术语和读取副作用反例。
- [X1] 小米 15 官方80%充电保护 FAQ：<https://www.mi.com/uk/support/faq/details/KA-538544/>；已采集 `.tooling/bypass-check/xiaomi-15-charge-protection.txt`。区域与固件边界见 `.tooling/bypass-check/official-research.md`。
- [X2] 小米通用智能充电官方 FAQ：<https://www.mi.com/global/support/faq/details/KA-539817/>；已采集 `.tooling/bypass-check/xiaomi-smart-charging.txt`。
- [L1] 本机已有驱动取证与协议分析：[XIAOMI_DADA_BYPASS_RESEARCH.md](XIAOMI_DADA_BYPASS_RESEARCH.md)；原始八ELF及manifest位于 `.tooling/bypass-check/driver-binaries/`，含协议调用偏移/BTF核对。当前模块行为和过去实机写验证应查主任务后续记录，不用该文档末尾旧时点描述本轮结果。
- [L2] 本轮主代理只读取证：`.tooling/bypass-check/root-acquisition/device-metadata.txt`、`dt-and-binding-paths.txt`、`provider-manifest.json`、`dt-acquisition-integrity.json`、`live-charger-dt-verified-subset.tar` 和 `live-dt/soc/` 单项属性；provider ELF由主代理采集，保存在其指定分析目录。archive仅使用经主代理验证的五子树范围，不用于缺失推断。
- [L3] 本轮控制链代理的AND票、pmic_temp_term override与handle_allow_charge离线分析：原始依据 `.tooling/bypass-check/control-chain/mca_common.annotated.txt`、`.tooling/bypass-check/driver-binaries/mca_strategy_buckchg.annotated.txt`、`.tooling/bypass-check/control-chain/mca_business_charger_comp.annotated.txt`。这些具体新发现需由主代理结合链路代理报告进行最后复核；本报告不将尚缺的逆操作认定为可用。
- [L4] 本机QCOM proxy离线反汇编：`.tooling/bypass-check/provider/mca_qcom_subpmic_proxy.disasm.txt`，enable_charging函数起点`.text+0x1418`，其`+0x40`指令`.text+0x1458`装载0x2003；input_suspend函数起点`.text+0x11b8`，其`+0x40`指令`.text+0x11f8`装载0x2019。两者均调用mca_adsp_glink_write_prop；原ELF哈希见[L2]provider-manifest。
- [L5] 本轮SOC/BMD闭环的离线工件：`.tooling/bypass-check/control-chain/mca_soc_limit.annotated.txt` / `.btf.json`，soc_limit_process `.text+0x4`；business charger `.text+0x258c..0x25e8`的event52分发；`.tooling/bypass-check/driver-binaries/mca_strategy_buckchg.annotated.txt` 中SOC callback `.text+0x6288`及另一分支`.text+0x4918..0x4aac`；`.tooling/bypass-check/control-chain/mca_strategy_quickchg.annotated.txt` 中SOC callback `.text+0xaa2c`、disable OR callback `.text+0x4190`和复杂退出`.text+0x445c`；smart_charge原始工件见[L1]；BMD `.tooling/bypass-check/control-chain/mca_bmd.annotated.txt` / `.btf.json` 中btb_sysfs_show `.text+0x4`及monitor `.text+0xb54`。battery/qcom_sysfs分析工件分别为同目录`mca_business_battery_comp.annotated.txt`与`mca_qcom_sysfs.annotated.txt`。具体复杂流程以链路代理报告和主代理最终复核为准，不能用上述地址拼成实机写脚本。
