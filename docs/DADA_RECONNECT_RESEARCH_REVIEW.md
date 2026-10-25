# Xiaomi 15 / dada 重连后的供电通路最终审查

日期：2026-10-13。设备：Xiaomi 15 / dada / 24129PN74C，Android 16；固件此前识别为 OS3.0.303.0.WOCCNXM。本轮利用已经取得的 root 继续研究，遵守用户要求，不在连接手机上做探索性电源操作。本文接续[重连之前的审查](DADA_POWER_PATH_RESEARCH_REVIEW.md)，后者的“尚未取得远端 handler”和断连状态属于前阶段。

## 结论

**已从本机普通 ADSP 固件文件追到真正的充电使能控制候选：MCA `0x2003` → `PmSchgChgr_SetEnableCharging` → PMIC 充电使能位的读改写。它与 FCC 限流、输入暂停分别处理。** 同一属性的硬件位读取链也已定位。这关闭了此前“只能看到 Linux 发请求、看不到远端如何执行”的静态代码缺口。

**仍未形成可安全接入插件的旁路后端。** 完整 `0x2003` 请求还执行其他硬件联动，部分位的电气含义未明，联动错误没有汇总返回；独立 owner 的准确撤票、CP 充电路径覆盖、实际运行 provider 和外部保 SYS 条件仍缺少证据。找到禁充控制位不等于证明整个请求能停止电池持续充电并保持外部电源承担系统负载。

本轮没有写候选 sysfs、电源属性或寄存器，没有切换 CP 模式、挂载 debugfs、加载或修改手机驱动/固件、安装或修改插件、重启，也没有候选开关 A/B 测试。0.2.0 插件的既有 `smart_chg` 限流实现没有升级；本轮没有生成声称增加旁路能力的新安装包。既有插件进程的运行行为不属于本轮新实验，本轮也没有新增电流/功率遥测，不能据此解释手机当前充电功率。

## 1. 重连补齐了什么

只读采集确认 `uid=0(root)`，SELinux context 为 `u:r:ksu:s0`；内核仍为 `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k`。所有手机采集由主代理执行，子代理只处理本地文件。

| 新证据 | 支持的结论 | 保留的边界 |
| --- | --- | --- |
| live 静态 DT：`qcom,qti-pmic-glink`、`PMIC_RTR_ADSP_APPS`、`lpass`、`msm/adsp/charger_pd` | 当前 Linux 配置把 MCA 请求发往 ADSP charger protection domain；MCA child 为 ok，标准 battery-charger child 为 disabled | 不等于已测量远端正在执行哪份内存镜像 |
| 10 个加载模块 GNU build-id note 与磁盘 ELF note 字节一致 | common/interface/buck/quick/class/proxy/ADSP、SC8581、HL7603、QTI transport 具有正面的构建身份对应 | 旧 vermagic 与运行内核不同不能单独否定此对应；build ID 也不是整个运行代码完整性校验 |
| 实际绑定 `6-006e → sc8581`、`6-0077 → hl7603` | CP 和 HL7603 驱动确实绑定，非仅凭文件名猜测 | 不证明板级 BAT/SYS 接线或电池隔离 |
| `adsp.mdt`、相关普通 bNN 分片及远端 DT payload | 可以按真实地址、Hexagon v79 指令集离线定位 handler、ops 和配置 | 未采运行内存；BSS、CFGL 选择和 runtime model 不由文件补零得到 |
| mounts 无 debugfs，`business-votable` 路径缺失 | 本轮没有可用的内存 voter status 快照 | 不表示没有 voter，不为了观察它而挂载 debugfs |

主代理[完整性复核](../.tooling/bypass-check/reconnect-acquisition/root-final-evidence-verification.json)核对五份采集 manifest 的 **103 份文件**：metadata 11、static 61、firmware header 11、firmware data 17、charger code 3，大小、SHA-256、return code 和 stderr 均符合记录；10 份加载 note 与 ELF 的对比全部匹配。

## 2. 充电使能的静态执行链已经闭合

候选 Linux 节点为 `/sys/devices/platform/soc/soc:mca_charge_interface/charge_enable`，使用 user/type/value 三词协议。以下只说明调用关系，不是手机执行命令：

```text
charge_interface / buck strategy
  → mca_vote(chg_enable, user, enabled=1, value=bool)
  → AND effective callback / buck class
  → qcom_subpmic_enable_charging
  → MCA GLINK owner 0x800a / property 0x2003 / 1-byte 请求
  → QTI RPMSG / ADSP charger_pd
  → 磁盘固件 mca_set_charger_buck_properties 0x60031450
  → 0x2003 分支 0x600314c4
  → PmSchgChgr_SetEnableCharging 0x60075bb0
  → runtime entry.ops+4
  → 两种已定位 enable 实现之一
  → PMIC 指定位的 masked read-modify-write
```

远端 set dispatcher 减去属性基 `0x2003`，读取原始 b19 内 `0x6016d5ac` 的 41 项跳转表。主代理和独立证据代理分别读取原始表，与 provider 的全部 41 项记录一致；关键映射如下：

| 属性 | 分支地址 | 已证明的控制类型 |
| --- | --- | --- |
| `0x2003 CHG_ENABLE` | `0x600314c4` | 取 ctx+3 的 device index，payload byte 传给 SetEnableCharging；随后有额外联动 |
| `0x2005 FCC` | `0x60031680` | 单独的充电电流限值分支；本文未闭合其全部末端操作 |
| `0x2008 ICL` | `0x600317b4` | technology index 0 的远端聚合请求 |
| `0x2019 INPUT_SUSPEND` | `0x60031a7c` | technology index 3 的输入暂停聚合请求 |

`InitializePeripheralSchgChgr`（`0x600909b0`）按输入 PmicModel 选择 ops。两个子代理独立穷举原始 58 项模型表：8 个模型进入受支持初始化分支，涉及 6 张 file-backed ops 表，最终只有两种 enable 实现；其余 50 项和范围外模型走 `0x201` 错误分支，没有默认 ops 赋值。未支持调用也不保证清除既有 entry。这是静态分支接受范围，不证明硬件初始化成功或当前采用其中哪项。反初始化可清空 entry，重复初始化也可覆盖它。

| 固件 PmicModel 数值 | ops+4 enable 实现 | PMIC 地址与操作 |
| --- | --- | --- |
| `0x20 / 0x29 / 0x2e` | `0x6008ca80` | `0x1042`，mask=1 |
| `0x32 / 0x47 / 0x4c / 0x56 / 0x59` | `0x6008e330` | `0x2646`，mask=1 |

两者调用 `0x6005b330`：先读旧字节，再组合 `(data & mask) | (old & ~mask)`，随后写一个字节。底层日志和调用链明确到 SPMI `Spmi_WriteLong`。因此，**这一底层 API 改变充电使能 bit0，保留同字节其他位**。上表的模型是固件内部数值，没有据此认定本机实际 device index、slave、硅片或正在使用哪张 ops 表。

对应 getter 的静态链为 `0x2003` get 分支 `0x6002f478` → `PmSchgChgr_GetEnableCharging`（`0x60075dc0`）→ **ops+0x34** → 对同地址以 mask1 读取 bit0。它有硬件位读取的代码依据，但未在手机调用，未自动加入手机读取白名单；成功读到禁充位也不能证明外部保供或电池零电流。

原始 wrapper 与 b19/b20/b21/b22 数据逐字节一致，MDT `e_flags=0x79`，反汇编明确指定 `--triple=hexagon --mcpu=hexagonv79`。独立代理核对官方 LLVM 固定版本、packet 分支地址处理，并完成 76 字节代表样例的汇编往返。执行段中仍有常量和字符串；结论来自已追踪调用和原始表，不来自“全部字节都能反汇编”的假设。

## 3. 完整请求还有额外联动，不能当作单 bit 开关

主代理重新生成了[16 个局部反汇编窗口](../.tooling/bypass-check/hexagon-host-tools/root-final-side-effects.txt)，[来源与工具散列](../.tooling/bypass-check/hexagon-host-tools/root-final-side-effects.json)也已记录。控制链代理另行核对 ctx+0xc0 的 factory、getter、ops 绑定和错误路径。

`0x600314d0` 给主 API 传 payload byte；`0x600314e0` 对附加联动的分支判断另读 payload 的 32 位值。两种读取宽度不能混为同一实现细节，消息填充规则仍应保留审查范围。

| 对象 / wrapper | 主请求中的调用 | 已定位的附加作用与边界 |
| --- | --- | --- |
| ctx+0xbc / `0x6002e7e0` | 非零分支参数 `(3, 0x1388)` | 调用对象+0xd4；相关 initializer 绑定 `0x6013acb0`，末端语义未在本报告闭合 |
| ctx+0xbc / `0x6002eaf0` | 非零 `(0,1)`，零 `(1,0)` | 对象+0xfc 可绑定 `0x6013bc60`；对象 model>0x46 时对 `0x2c42` 分别做 mask2、mask1 操作 |
| ctx+0xbc / `0x6002eb70` | 非零传 1，零传 0 | 对象+0x100 可绑定 `0x6013bd10`；该函数仅在其对象 model=0x47 时操作 `0x2c43` bit0，其他模型本函数无该写入 |
| ctx+0xc0 / `0x601351b0` | 仅非零分支传 `0x30` | 对象+0x90；局部 0x4c/0x56 初始化路径绑定 `0x60137590`，还有内部型号/通道分派 |
| ctx+0xc0 / `0x601348f0` | 非零分支按 MCA model 传 4 或 5，零分支传 4 | 对象+0x60；同初始化路径绑定 `0x60136710`，可对 `0x2641` 或条件选择的 `0x2341` 做 mask7 操作 |

ctx+0xbc 的相关 factory 在 BSS `0x60201208` 保存对象指针，getter `0x6002ee80` 返回这个指针；ctx+0xc0 的 factory 在 BSS `0x602be310` 保存对象指针，getter `0x60135520` 返回它。**这些 BSS 地址是指针存储位置，不是从文件读到的实时对象。** MCA ctx+5 为 0x4c/0x56 的局部分支分别选择了上述 initializer，但没有观察运行时型号、对象内容或当前 ops。

在已分析的 ctx+0xbc backend 且其对象 model>0x46 时，非零分支请求在 `0x2c42` 清 bit1、置 bit0，零分支相反；实际是否写入成功还取决于 callback/bus 返回。这只描述代码条件和参数；没有把未知位命名为 HIZ、BATFET、隔离或强制 buck，也没有从这些请求推导板级供电效果。地址均是离线固件常量，不能据此在手机直接读写寄存器。

**新增的实际失败问题：** `0x600314d8` 仅保存主 SetEnableCharging 返回值。随后全部附加 callback 的返回值均未检查，两分支最终在 `0x600322b8` 返回保存的主状态；主操作失败也继续执行联动。因此整个请求不是原子操作，可能“主充电位成功、联动部分失败、请求仍成功”。ctx+0xc0 初始化还会先发布对象指针、再执行初始化 callback，外层忽略 factory 状态，只看非空；非空不能证明初始化全部完成。独立记录见[局部联动审查](../.tooling/bypass-check/reconnect-control/remote-side-effects/REVIEW.md)。

## 4. 各候选与旁路目标的对比

| 路径 | 已确认的作用 | 当前判断 |
| --- | --- | --- |
| 既有插件 `smart_chg` | 厂商智能充电 FCC 限流策略 | 旧 62 秒被动记录电量计增加约 17mAh、平均充电约 987mA；未实现禁充保供，该数据不是本轮新采样 |
| buck `charge_enable` / `0x2003` | 静态链已到真实充电使能位，还有附加硬件操作 | 继续研究的主要候选，尚不足以批准自动写入 |
| `input_suspend` / `0x2019` | HLOS voter → chgr_suspend 聚合 → set_usb_suspend；在已定位新 backend 路径中请求操作 `0x2954` mask1，并按 bus selector 分派 | 这是输入暂停候选，不能用作保持外部输入的禁充开关；写 0 也不保证其他 voter 全部解除 |
| quick `charge_enable` | 独立策略会退出快充、关 CP、改变 PD/buck 状态 | 覆盖另一条充电路径的线索；不能以广播 all 代替完整联动和恢复审查 |
| CP bypass / forward 1:1 | 转换比与工作模式 | 不能证明电池旁路或隔离，不试切模式 |
| HL7603 boost/bypass | 电池侧最低输出电压保障及 VOUT/HBM 联动 | 没有形成“外部供系统、停止电池充电”的控制后端；debug DATA 可直接 I2C 写，排除 |
| OEM SOC 保护 | OEM 状态和释放链，跨多策略传播 | 与其他场景共用 owner，不借用该 owner 接管插件 |

## 5. 接入插件前尚须补齐的条件

1. **独立请求能完整恢复。** 已查 20 份 ELF 的相关用户入口，buck/quick charge_enable 仍把 enabled 固定为 1，没有找到独立 owner 的 enabled=0 协议。若 owner 原来不存在或 inactive，写允许不能恢复原状态；若原来 active 且原值已知，值可写回，但本轮没有原始票快照。buck AND default=0 意味着残留允许票在其他 active 票全部撤销时可能改变结果；这是条件性风险，不能说当前已经发生。
2. **主请求和联动具有可靠反馈与恢复。** Linux voter 先更新缓存、后调用硬件，失败不回滚；buck setter/sysfs 吞错，同票重试还可能跳过 callback。此次又确认远端只返回主 API 状态，联动错误被忽略。sysfs 写成功、软件 status、禁充位 getter 三者都不能单独确认完整恢复。
3. **当前模型与未知位有匹配定义。** Linux 10 个 build ID 的匹配不能跨用成当前 ADSP 内存身份。远端 DT 的 `pm8750bh@7` 和 battman 配置是积极线索，但 CFGL 选择与实际 provider 仍未观测，未知位含义和 BAT/SYS 拓扑也不能从名称推断。公开 SMB5 参考实现没有与本机 handler 建立型号/布局对应，不移植其寄存器定义。
4. **buck 与 CP 的停充/恢复条件完整。** buck bit0 不证明独立 CP 已停充，quick 的退出流程又是复杂策略。只要存在未覆盖路径，就不能声称电池已停止持续充电。
5. **证明外部电源承担系统负载。** 本轮没有电气 A/B 数据。后续受控验证应使用已审查的 getter，在相同 SOC、温度、适配器和负载下比较输入实际功率、电池电流时间序列和 charge_counter，避开原本 80% 保护停充等混淆；还须确认退出后的策略和充电恢复。online=1、单帧 0mA、SOC 不变或充电使能位清零均不足以验收。

上述缺口决定本轮交付为接口研究与证据审查，没有把候选做成自动动作。继续工作应先在本地补匹配硬件字段、联动与准确撤票/恢复定义；只有能给出具体原状态、失败处理和恢复路径时，才具备制定受控实机对比方案的条件。电池物理隔离是更强目标，还另需 BAT/SYS 硬件证据。

## 6. 分工、独立复核与来源

三个子代理分别负责用户协议/voter/恢复、provider/远端固件、独立证据与电气语义。主代理重解 Linux 关键调用、直接核对原始跳转表和采集散列，并重新生成上述 16 个远端联动窗口；最终汇总再交独立证据代理审查范围。没有用模拟输入或旧低电流快照替代实机效果验证。

- [控制与撤票复核](DADA_RECONNECT_CONTROL_RESEARCH.md)：20 ELF 范围、effective 名称归因局限、缓存/吞错、缺失 status。
- [provider 与远端 handler](DADA_RECONNECT_PROVIDER_RESEARCH.md)：真实 QTI transport、41 项属性表、Set/GetEnableCharging、58 项模型表、SPMI、输入暂停。
- [独立证据审查](DADA_RECONNECT_EVIDENCE_REVIEW.md)：读取实现依据、10 note 对比、963 节点/5114 属性的独立 FDT 对比、MDT/分片、v79 工具与 handler 表。
- [独立模型初始化审查](../.tooling/bypass-check/reconnect-control/remote-provider-init/REVIEW.md)、[独立 ctx+0xc0 联动审查](../.tooling/bypass-check/reconnect-control/remote-side-effects/REVIEW.md)。
- [主代理底层 enable 复核](../.tooling/bypass-check/hexagon-host-tools/root-provider-enable-review.txt)、[完整采集复核](../.tooling/bypass-check/reconnect-acquisition/root-final-evidence-verification.json)、[主代理联动窗口](../.tooling/bypass-check/hexagon-host-tools/root-final-side-effects.txt)、[最终原始表与报告对比](../.tooling/bypass-check/hexagon-host-tools/root-final-report-comparison.json)。
- 小米固定内核 [QTI GLINK transport](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/soc/qcom/qti_pmic_glink.c)、[ADSP loader](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/remoteproc/qcom_q6v5_pas.c)、[MDT loader](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/soc/qcom/mdt_loader.c)。
- 小米固定设备树 [sun.dtsi](https://github.com/MiCode/kernel_devicetree/blob/233bd52eddce9f081f8e87802796f5994babafa5/qcom/sun.dtsi)、[HL7603 厂商说明](https://hmisemi.com/hl7603/)、[LLVM 固定版本 Hexagon decoder](https://github.com/llvm/llvm-project/blob/llvmorg-23.1.3/llvm/lib/Target/Hexagon/Disassembler/HexagonDisassembler.cpp)。

文件内的固件地址只用于复核磁盘代码，不是实机操作指南。原始采集与分析工具位于本地 `.tooling/bypass-check/`；没有修改它们所描述的手机硬件状态。
