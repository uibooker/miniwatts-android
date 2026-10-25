# dada 充电 provider 与远端控制语义取证

日期：2026-10-13。范围：本机已采集的普通 ELF 文件、静态设备树与元数据，以及小米官方公开源。本代理未执行 ADB、未读写手机硬件寄存器或电源属性、未改变插件。本报告中的地址均为可重定位 ELF 的 section offset，不能作为手机内存地址或寄存器写入指南。

## 结论

**已找到彼此独立的 buck 充电使能、充电电流和输入暂停 provider，并追到实际 MCA GLINK 属性请求。** 其中 `BUCK_ENABLE_CHARGING` 是继续调查“禁充而维持外部供系统”的首要候选；FCC 限流和 INPUT_SUSPEND 不具有相同的目标语义。本报告的前轮证据到达 Linux 侧 RPC 边界。重新连接后的后续分析已在实际磁盘ADSP中闭合property dispatch → PmSchgChgr API → 模型选择的PMIC位读改写，并发现附加联动；详见 `docs/DADA_RECONNECT_PROVIDER_RESEARCH.md` 第4.5节。电源拓扑、运行时保供和完整恢复仍未证明。[L1][L2]

因此，当前结论是“未建立可以安全恢复且已证明保持外部系统供电的接口”，而不是“手机硬件没有旁路能力”。找到充电开关也不等于证明停充后 VSYS 由适配器供电，更不能推导电池物理隔离。

另有两个需要纠正的证据解释：

- `qcom_subpmic_get_vsys_volt` 的 Linux ID 名为 `BUCK_VSYSMIN`；后续同固件取证将 getter 定位到 `battman_adc_read(channel=0)`，其 file-backed 名称表对应 `VPH_PWR`。不能靠 Linux 名字把它判为配置值，也不能把它当作已确认的 SYS 测点。
- SC8581 文件内的 `cp_get_bypass_support` 恒置 true；CP 的 forward 1:1 是转换模式，不能用它证明电池旁路或电池隔离。[L1][L3]

## 1. 来源和版本界限

本机对象为 Xiaomi 15 `dada`，当前固件 `OS3.0.303.0.WOCCNXM` / Android 16。主代理采集的磁盘 ELF `vermagic` 为 `6.6.57-android15-8-g6f1d969db78f-4k`，运行内核为另一 `6.6.77` 构建。加载名称、设备绑定、磁盘 ELF 分别是证据，不能把它们拼成“已证明运行内存代码逐字节等于这份磁盘 ELF”。[L0]

重新连接后补充：10份live GNU build-id note与对应磁盘ELF完全一致，包含本报告proxy、MCA GLINK、SC8581及新增HL7603/QTI transport。这已建立正面的加载构建身份对应；旧vermagic不能据以否认它们来自匹配构建。该补证不等于无运行时patch的内存完整性检查，也不覆盖ADSP远端语义。详见 `docs/DADA_RECONNECT_PROVIDER_RESEARCH.md` 与 `.tooling/bypass-check/reconnect-acquisition/module-build-id-comparison.json`。

| 工件 | SHA-256 | 用途 |
| --- | --- | --- |
| `mca_qcom_subpmic_proxy.ko` | `d83e6e5e620109a7fb9daa458d3ed2bc4c9e51b71582bdddb63b06a452aa52ea` | 实际 buck ops、GLINK 属性映射 |
| `mca_adsp_glink.ko` | `c838be8617edf41da0d31b23d898046dc9cb65b9d36dbf209288481eaecfb3de` | 请求、等待和失败返回 |
| `sc8581.ko` | `5885645f717c20e3479df4cc7dc4e83580a61bd15edeef26bbc5bff123d51aee` | 本机 CP 绑定对应候选驱动与模式实现 |

小米官方 `MiCode/kernel_devicetree` 的 `dada-v-oss` 固定提交 `233bd52eddce9f081f8e87802796f5994babafa5` 有完整设备树；对应内核公开快照固定提交 `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5` 的 MCA 目录是外部目录符号链接，不能取得 Linux MCA 全部 C 实现。[D0][K0] 内核递归 tree 响应为 truncated，不能据搜索未命中推断其他文件不存在；设备树 tree 响应没有截断。

公开 Android V DT 与当前 Android 16 不是同版本保证。有效 live DT 子集确认 proxy 的 compatible/status，以及 CP 的 compatible/status、`cp-num=1`、`cp-dir-list="cp_master"` 等相应静态事实。原 tar 前 97 members 经主代理检查有效，后续未找到 `soc/mca_adsp_glink` 导致 stderr 混入尾部；引用应使用 `live-charger-dt-verified-subset.tar` 与 `dt-acquisition-integrity.json`，不得作节点不存在的推断。实际 ADSP child 位于 nested `qcom,pmic_glink` 的线索未完成静态属性采集。[L0]

## 2. buck ops 到实际 provider 的映射

ELF 中 `g_qcom_buckchg_ops` 位于 `.data+0x128`，大小 656 字节。probe 在 `.text+0x790` 调用 `platform_class_buckchg_ops_register`，其 x2 参数来自该 ops 表。结合 BTF 布局、数据 relocation 和函数体，可以把间接 ops 调用定位到以下实现。[L1]

| ops 内偏移 | provider / `.text` 起点 | 远端属性 | payload 长度 | Linux 侧明确的区分 |
| --- | --- | --- | --- | --- |
| `+0xc0` | `qcom_subpmic_set_input_suspend` / `0x11b8` | `0x2019` / `ADSP_PROP_ID_BUCK_INPUT_SUSPEND` | 1 byte | 暂停输入请求 |
| `+0xc8` | `qcom_subpmic_set_wireless_input_suspend` / `0x1230` | `0x1004` / `ADSP_PROP_ID_DC_INPUT_SUSPEND` | 1 byte | 无线输入请求 |
| `+0xd0` | `qcom_subpmic_set_input_current_limit` / `0x12a8` | `0x2008` / `ADSP_PROP_ID_BUCK_INPUT_CURR_LIMIT` | 4 bytes | 输入电流限值 ICL |
| `+0xe8` | `qcom_subpmic_set_charge_current` / `0x13bc` | `0x2005` / `ADSP_PROP_ID_BUCK_CHARGE_CURR` | 4 bytes | 充电电流 FCC |
| `+0xf0` | `qcom_subpmic_enable_charging` / `0x1418` | `0x2003` / `ADSP_PROP_ID_BUCK_ENABLE_CHARGING` | 1 byte | 充电使能请求 |

`enable_charging` 在 `0x1444` 将参数低 byte 存入栈，在 `0x1458` 设置请求 ID `0x2003`，`0x145c` 设置长度 1，`0x1460` 调用 `mca_adsp_glink_write_prop`。`set_input_suspend` 的相应调用位于 `0x1200`，`set_charge_current` 的相应调用位于 `0x13e8`。这证明它们是不同属性请求，不能把 `input_suspend=1` 或 FCC=0 视作充电开关的同义操作。[L1]

仅在 provider 这一层，`enable_charging` 没有连带发送 INPUT_SUSPEND 或 ICL。这个局部事实不排除上层 setter/voter 的额外副作用，也不排除远端处理 `0x2003` 时联动输入。因此，需要与控制链报告合并审查，不能从局部函数直接批准用户接口。[L1][L4]

### 2.1 VSYS 遥测名称陷阱

ops `+0x70` 指向 `qcom_subpmic_get_vsys_volt` (`.text+0x10c8`)；该函数在 `0x10d4` 设 ID `0x200c`、长度 4，并在 `0x10dc` 调用 `mca_adsp_glink_read_prop`。同一 ELF 的 BTF 将 `0x200c` 定义为 `ADSP_PROP_ID_BUCK_VSYSMIN`。[L1]

初版在缺少远端实现时只确认了 Linux ID 名称，不能据此判断其返回配置阈值或实际电压。后续同固件取证已将 `0x200c` getter 定位到 `battman_adc_read(channel=0)`，file-backed ADC 名称表 `0x601cd370[0]` 为 `VPH_PWR`，表中的 `VSYS` 则位于 index 2。这个更新排除了“仅凭 BUCK_VSYSMIN 名称就判为配置值”的推断；也没有证明 channel 0 的板级测点、电气含义或运行时可用性。**当前不得把 `get_sys_volt` / `get_vsys_volt` 的名称当作已确认的 SYS 实测点，更不得把该值稳定当作外部供系统成功的证据。** 详细更新见后续 `docs/DADA_NEXT_TELEMETRY_RESEARCH.md`。

ops `+0x98` 的 `qcom_subpmic_get_chg_status` (`0x10f0`) 读取 `0x2001`。BTF 中对应充电状态包括 INHIBIT=0、TRICKLE=1、PRECHARGE=2、FULLON=3、TAPER=4、TERMINATION=5、PAUSE=6、CHARGING_DISABLED=7、INVALID=8。即使读到 disabled，也只能辅助证明远端报告禁充状态，不能据此确认 VBUS 有功率、SYS 输入保持或电池不补电。[L1]

## 3. GLINK 请求和失败边界

`mca_adsp_glink_write_prop` 从 `.text+0x4` 开始：[L2]

- 检查 payload 非空、长度不超过 255、全局 provider 非空。
- `0xc8` 获取 mutex；消息 owner=`0x800a` (32778)、type=1、opcode=2，填入属性 ID、递增序号和 payload。
- `0x124` 调用 `pmic_glink_write`，消息大小固定为 276 bytes。
- `0x138` 调用 `wait_for_completion_timeout`，参数为 250 ticks；未经 HZ 确认不能称为 250 ms。
- GLINK down 分支返回 -107；等待超时分支返回 -110；正常 completion 后读取保存的 response 返回值，释放 mutex。

该 mutex 串行化的是一条 RPC，不构成多属性操作的原子事务。上层若先改票、再发送充电开关、再改 CP 模式，中间仍可能出现部分应用、策略覆盖和失败后状态未知。传输成功/返回成功不直接证明 PMIC 状态生效；超时也不证明对端未应用。因此直接重复 set、逆 set 或重启都不是已证明正确的恢复方案。

这里有三层不同的验收对象：请求方票状态、Linux provider/RPC 状态、硬件电流与供电状态。它们不能用同一个 sysfs 回显代替。Linux 标准 `qti_battery_charger.c` 有类似远端消息架构，但它不是这份 MCA provider 的执行证明，不可移植其中属性编号或寄存器假设。[K1][L1][L2]

## 4. 外部 CP 的 bypass 是什么

官方 DT 配置 `sc8585_master@6e`，compatible=`sc8585_master`、role=0；还配置独立 gauge 和 `hl7603@77`。CP class 为一颗、`cp_master`。本机 metadata/绑定线索对应驱动 `sc8581_charger_pump` 和磁盘文件 `sc8581.ko`；文件名不足以单独辨认芯片，必须结合 compatible 和绑定。[D1][D2][L0]

`sc8581_chg_ops` 位于 `.data+0x170`，大小 392 bytes。probe 在 `.text+0xaec` 调用 `platform_class_cp_register_ops`，ops 参数来自此表。关键映射：[L3]

| ops 偏移 | 函数 / 起点 | 已证事实 |
| --- | --- | --- |
| `+0x0` | `ops_cp_enable_charge` / `0x38e8` | 独立充电使能实现，进入 `sc8581_enable_charge` |
| `+0xb8` | `ops_cp_set_mode` / `0x3da4` | 进入 `sc8581_set_operation_mode` (`0x2678`) |
| `+0xc0` | `ops_cp_get_mode` / `0x3e00` | 通过 I2C 读取寄存器 `0x0e`，取低 3 bits |
| `+0xd8` | `ops_cp_get_bypass_support` / `0x3fbc` | `0x3fd4` 将 true 写入输出，返回 0；没有硬件读取 |

BTF `platform_class_cp_mode` 为 FORWARD_4_1=0、FORWARD_2_1=1、FORWARD_1_1=2、reserved=3、REVERSE_1_4=4、REVERSE_1_2=5、REVERSE_1_1=6、reserved=7。另一个 `chg_mode` 枚举把 DIV4/DIV2/DIV1 定义为 0/1/2。它们指向转换比及方向，不能从 1:1 推导“电池停止充电且系统改由适配器供电”。[L3]

设置模式也不是只翻转一个独立 flag：`sc8581_set_operation_mode` 对多个寄存器读改写，已看到 reg1、2、6、8、9、0x6c、0x6d 等。例如首次 reg1 read 失败分支由 `0x2704` 跳往 `0x2738` 记录错误，随后在 `0x2754` 设 w2=0，仍在 `0x2760` 进入 I2C write。这个局部失败分支需要进一步核对，不能声称该模式是可安全试开、再恢复一个值的操作。[L3]

`bypass_support=true` 在本实现中是静态驱动能力声明，不能验证当前芯片模式、硬件工作状态、固件策略或电池隔离。即使读 CP mode，也会产生 I2C 访问；没有读取副作用审查和有效用途时，本轮不建议追加手机读取。

`hl7603` 的官方 DT 仅证明配置有该器件，并设置 `vout_threshold=3400`。本机 metadata 的驱动名为 `hl7603_boost_bypass`，但没有取得其 KO，也未取得对应厂家公开规格。名称不足以认定它是电池充电通路开关；本报告不赋予它已确认的电气语义。[D1][L0]

## 5. 远端固件与电源通路尚缺哪些证据

| 缺口 | 为什么当前证据不够 | 会改变结论的证据 |
| --- | --- | --- |
| `0x2003` 的远端 handler | Linux 只提交一个 byte，未见 handler 或 PMIC 控制位 | 与当前固件匹配的 handler、协议规格或厂家硬件定义，明确是否仅禁用 battery charge、是否保留输入调节与 SYS rail |
| 当前实际 charger/PMIC 型号与拓扑 | DT 中 PM8550/PMI 等名字和 proxy 不提供完整电路 | 当前板级原理图/电源拓扑或准确芯片配置，标出外部源、buck、CP、BAT/SYS 的关系 |
| buck 禁充是否覆盖 CP | CP 有自己的 enable/mode ops，不能假设 buck enable涵盖它 | quickchg/CP策略完整调用链及远端联动证明，确认不会留下 CP 继续充电 |
| SYS 外部供电是否保持 | INPUT_SUSPEND 与 CHG_ENABLE 分开，仅是软件分工；0x200c 已追到 VPH_PWR ADC，但未确认 SYS 测点 | 本机对应的电气/固件保证，及审查过的输入功率、净电池电流观测 |
| BATFET 或电池隔离 | 目前没有 BAT/SYS gate 实现定位 | 对应本机硬件文档及安全工作条件；单帧 Ibat=0 或禁充状态不够 |
| 请求所有权和准确撤销 | provider bool setter没有提供独立票恢复；上层可能另改 override | 控制链中完整 requester语义、原票快照、撤票接口与全部副作用逆操作 |
| 超时后恢复 | RPC timeout后对端可能已应用，多个请求非原子 | 实际硬件/readback、明确请求幂等性和失联恢复策略；不能用重发猜测 |
| 当前运行实现版本 | 磁盘 vermagic 与 live kernel不一致 | 对运行绑定、模块构建/来源的进一步离线对应，或匹配当前固件的原始源码/符号资料 |

缺口可通过追加离线固件/源码研究缩小，不能由试写 0/1、裸 GLINK、I2C/SPMI 或切 CP 模式代替。即使没有公开 handler 或用户态节点，也只是“未找到/未证明安全入口”；不能由此判断芯片一定不支持禁充保供。

## 6. 给主代理的对比核验要点

1. 将控制链中的 store/voter 映射与本表对接，确认最终是 CHG_ENABLE、FCC、ICL 还是 INPUT_SUSPEND；不要仅按节点名分组。
2. 审查独立请求票、AND/MIN 选举和额外 override，整体 effective值不能作为 requester原值；provider可写不等于 sysfs操作可准确恢复。
3. 把 CP 1:1、输入 gate、充电使能、实际输入供能、电池净电流分别记录，避免把任一项称作已经完成电池旁路。
4. 本轮验证仅是固定 hash工件、BTF、ELF relocation、静态 DT 与公开源对比。没有完成实机禁充/保供验证，报告不能宣称新后端有效。
5. 候选通过接口和远端语义预审后，才能另行制定具体受控实机对比；需要匹配 SOC、温度、适配器和负载的时间序列、准确恢复，并明确传感器符号和单位。当前无需进行探索写入。

## 引用与复核入口

- [D0] 小米官方设备树固定提交：<https://github.com/MiCode/kernel_devicetree/commit/233bd52eddce9f081f8e87802796f5994babafa5>。本地 `.tooling/bypass-check/provider/micode-dt-branch.json`、`micode-dt-tree.json`。
- [D1] 官方 `dada-charger-common.dtsi`：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/dada-charger-common.dtsi>。关键行 75 gauge、93 SC8585、114 HL7603、142 GLINK、150 proxy、795 CP。副本位于 `.tooling/bypass-check/provider/`。
- [D2] 官方 `mca.dtsi` 与 dada入口：<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/mca.dtsi>；<https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/dada-sm8750.dtsi>。公开设备树不是当前固件全部实现证明。
- [K0] 官方内核 MCA symlink：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca>；公开 include MCA 目录亦为 symlink。
- [K1] 官方通用 QTI driver，仅作架构参考：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qti_battery_charger.c>。本地 `provider/qti_battery_charger.c`。
- [L0] 主代理本机静态采集：`.tooling/bypass-check/root-acquisition/provider-manifest.json`、`device-metadata.txt`、`dt-and-binding-paths.txt`、`dt-acquisition-integrity.json`、`live-charger-dt-verified-subset.tar`。本代理未执行采集。原始档保留，未采子树不能作不存在证据。
- [L1] `.tooling/bypass-check/driver-binaries/mca_qcom_subpmic_proxy.ko`、同名 `.btf.json`；反汇编 `.tooling/bypass-check/provider/mca_qcom_subpmic_proxy.disasm.txt`；ops/注册 relocation汇总 `provider/ops-relocations.txt`。
- [L2] `.tooling/bypass-check/driver-binaries/mca_adsp_glink.ko`、同名 `.btf.json`；反汇编 `provider/mca_adsp_glink.disasm.txt`。以上三 ELF hash见本报告第1节。
- [L3] `.tooling/bypass-check/driver-binaries/sc8581.ko`、同名 `.btf.json`；反汇编 `provider/sc8581.disasm.txt`；`provider/ops-relocations.txt`。BTF解析采用已有split-BTF脚本，未解析的base字符串不用于命名推断。
- [L4] 控制链代理离线报告及其原始注释：`.tooling/bypass-check/control-chain/`；本报告仅确认provider端，具体用户态setter副作用由主代理结合该证据最后审核。另见 `docs/DADA_POWER_PATH_EVIDENCE.md` 对禁充、保供、电池隔离三种验收目标的区分。
