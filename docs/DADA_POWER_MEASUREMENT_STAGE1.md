# dada 第一阶段：供电字段与测量入口审查

日期：2026-10-13。依据 `DADA_NEXT_SIDE_EFFECT_RESEARCH.md`、`DADA_NEXT_TELEMETRY_RESEARCH.md`；仅使用电脑已有 Linux ELF、ADSP 磁盘镜像、remote DT 和固定 MiCode 提交的公开定义。补下载的是电脑上的三份官方源码，没有运行下载代码。本阶段手机命令、ADB、设备读写、插件修改及白名单新增均为 0。

## 阶段结果

本轮取得了两个能直接影响后续验收的结果：

1. `mca_charge_interface/charge_enable` 的 buck 显示链读取软件 voter 的有效 client/result，**不调用 proxy，不发送 `0x2003` getter，也不是硬件 enable 状态**。查询错误会被 show 跳过相应行；成功显示仍不能证明远端执行或输入继续供电。
2. MCA 注册的 USB power_supply `CURRENT_NOW` 分支调用输入电流 getter 后，无条件把回调返回值改为 0。输出槽在调用前清零，因此存在“GLINK 失败、getter 未更新输出，但回调仍成功返回数值 0”的静态路径。**这是接口可返回路径，不是本轮在手机观察到的事故**，也不能用这条发现重解释历史某个实测值为失败。

PMIC 字段仍没有闭合。remote DT 给出 `pm8750bh@7`，公开 Linux DT 给出 `pmih010x@7`；两者都不能独立确定当前 ADSP 的对象模型、硅片及外设修订。本轮限定检查两份相关公开定义，没有得到附带字段的匹配 schema。因此没有可加入插件的供电控制后端，也没有可新增手机白名单的可信测量入口。

## 身份证据和 PMIC 定义候选

已有 `device-root-inventory.txt` 的系统身份是 `24129PN74C / dada`、`OS3.0.303.0.WOCCNXM`、内核 `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k`。这是先前采集的本地证据。本轮使用的是已采磁盘 `adsp.b19..b22` 形成的 `charger-analysis.elf` 和 `adsp_dtb.b01`；仍没有证明当前运行 ADSP/charger PD 与这些文件的身份、配置选择和 runtime ops 一致。Linux 模块身份或同版本包来源不能替代运行 ADSP 身份证明。

remote DT 的 FDT offset 372 中：

| 已取得字段 | 原始值 | 能支持的判断 |
| --- | --- | --- |
| `/model` | `qcom,pakala` | 平台配置名称，不能当 PMIC 型号 |
| `/soc/spmi_bus@c400000/pm8750bh@7/reg` | `00 00 00 07`；BE32=7 | 文件配置 SID 7，compatible 为 generic `qcom,spmi-pmic` |
| 同树其他 PMIC 节点 | `pmk8550@0`、`pm8550@1`、`pm8550ve@3/6/8`、`luminous@4`、`pm8550vs@5/9` | 不是单一 PMIC 系统；名字不能绑定每个 MCA provider 对象 |
| `charger_cfg/ufcs_cfg/pmic-idx` | `00 00 00 07` | 此 UFCS 配置字段按 BE32 可解为 7，不能替代 buck device ID |
| `usbpd_cfg/tcpc_cfg/pmic-idx` | `07 00 00 00` | 与前项字节次序不同；其 schema 未取得，不能不经解析器证明直接按 BE32 或 LE32统一解读 |
| `charger_cfg/chg_idt_cfg/slave-addr` | `3b` | 属 IDT 配置，不能当充电 PMIC 的 SPMI SlaveID |

公开固定 DT 提交 `233bd52eddce9f081f8e87802796f5994babafa5` 的 `pmih010x.dtsi` 为 `qcom,pmih010x@7` 配 `reg=<0x7 SPMI_USID>`，`sun-pmic-overlay.dtsi` 还包含 `PMIH010X_ADC5_GEN4_VPH_PWR/IIN/ICHG_FB` 名称。现有材料没有证明 `PM8750BH` 与 `PMIH010X` 是同型号、别名或相同修订，也没有把这些 ADC 名称绑定到运行时 DAL channel。VPH 名字不等于 SYS 测点。

本轮只核对以下两份 register/HAL 身份相关定义，不扩展陌生 PMIC 候选：

| 候选，均固定在 MiCode `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5` | 检查结果 |
| --- | --- |
| [drivers/power/supply/qcom/smb5-reg.h](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/qcom/smb5-reg.h)，已有本地 `dada-smb5-reg.h` | 定义 CHGR=0x1000、DCDC=0x1100、MISC=0x1600，FSW 为 0x1150、SMB_EN_CMD 为 0x1648；未给 ADSP 新地址族、PMIH010X/PM8750BH 修订及模型映射。不能把旧偏移平移到新 base |
| [include/linux/qpnp/qpnp-revid.h](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/include/linux/qpnp/qpnp-revid.h) | 公开 Linux subtype、rev1..rev4 schema；没有 PMIH010X/PM8750BH 定义，也没有 ADSP 内部 PmicModel `0x47/0x4c/0x56/0x59` 对照。Linux subtype 和 ADSP enum 不能视为同一命名域 |

具体阻断资料如下，不是继续查更多同偏移即可解决：

| 要闭合的对象/字段 | 下一份必要材料 |
| --- | --- |
| MCA `ctx+0xbc`、`ctx+0xc0` provider identity | 对应本构建的 factory 输入结构定义；PmicModel enum 数值到型号映射；provider `+0` model、`+1` transport selector、`+0x18` channel 及 SlaveID 来源/实际选择；`PM8750BH↔PMIH010X` 对照 |
| PMIC / 外设 revision | 本型号主/次修订以及 SchgChgr、DCDC、Misc、相关 input peripheral 的 type/subtype/revision；匹配版本 register/HAL schema，不能仅用 marketing 名称 |
| FSW `0x2742[1:0]` 或 `[2:0]` | 相应 revision 的字段名、5000 参数单位、编码到频率表和旧值恢复要求；编码 0 不等于 0Hz |
| `0x2c42.bit1/bit0`、`0x2c43.bit0` | 字段名、极性、访问语义、输入/充电联动、恢复要求；Misc HAL `+0x3c` 的具名接口绑定 |
| `0x2641[2:0]` / `0x2341[2:0]` | 4/5 枚举含义与允许转换，双通道对应关系 |
| `0x2842[6:4]` / `0x2442[6:4]`，`0x28d0/0x24d0` | 编码 3 意义、前置写 0xa5 的准确语义、写保护/恢复流程；不把 0xa5 自动称作解锁 |
| 保留系统输入的物理条件 | 本机 SYS/VPH/电池/CP 路径图或匹配 HAL 状态机，证明启用/停充/快充退出各转换，不仅是充电 enable bit |

## 入口一：charge_enable 显示止于软件聚合

选择的是先前本地清单里的 `/sys/devices/platform/soc/soc:mca_charge_interface/charge_enable`；仅作为候选入口审查记录，不批准手机读取。

原始 ELF 证据完整关联如下：

```text
mca_charge_interface.ko .data+0x120
  name relocation -> .rodata+0x131 = "charge_enable"
  show relocation -> .text+0x1c4; enum(+0x20)=1
  show: .rodata[1] jump byte -> .text+0x6a4
  .text+0x6a4 loads ops+0x28; .text+0x57c invokes getter

mca_strategy_buckchg.ko probe .text+0xb74..0xb80
  registers .data+0xf8 record, context at record+8
  record name="buck"; relocation .data+0x120 (record+0x28)
  -> strategy_buckchg_if_get_chg_en .text+0x5e78

strategy getter:
  ctx+0xd0 -> mca_get_effective_client; null -> -1
  same ctx+0xd0 -> mca_get_effective_result
  format="eff_client:%s %d"; returns 0 after formatting
```

`mca_get_effective_client` (`mca_common.ko .text+0x560`) 与 `mca_get_effective_result` (`.text+0x480`) 各自加锁读取 voter 结构并解锁；这两次调用不是同一个原子快照。getter 将 result 当整数格式化，没有把负 result 统一识别为查询错误，也没有询问 provider、GLINK 或硬件 enable。读取 client 不走按 owner 分配槽位的接口，此结论只适用于这条有效 client 查询链。

show 在 `.text+0x580` 只在 getter 返回 0 时拼接该对象行；非零或未注册 getter 跳到 `.text+0x584`，继续后续对象，最后 `.text+0x6c8` 返回总文本长度。因此缺 buck 行、空文本不能解释成 disabled；`buck eff_client:online 1` 只能说明该软件聚合显示。不能把它升级为“充电硬件已经启用”，也不能声称这个 show 已覆盖 `0x2003` 的错误。

远端 `0x2003` getter 虽存在（`0x6002f478→0x60075dc0`，读 runtime ops+0x34），**与该 show 没有调用关系**。既有报告已追到 `0x1042/0x2646` bit0 读取，本轮保存定向片段用于对照，但没有绕过 sysfs 发 RPC 的方案，也没有为该 getter 新增入口或白名单。这里不存在可继续追的“show→proxy→2003”链，缺口是厂商是否提供另一条明确、受审查的硬件状态查询及错误/有效性接口。

## 入口二：USB current_now 会吞掉 getter 失败

选择的是 MCA 业务模块注册的名为 `usb` 的 power_supply。ELF `.data+0x368` descriptor 名字 relocation 为 `usb`，`.data+0x398` 绑定 `.text+0x354c usb_psy_get_prop`；`business_charger_usb_psy_init .text+0x345c` 用该 descriptor 注册。目标设备该名称的运行绑定仍须另行核对；不把公开树的 sysfs 实现与当前编译内核直接等同。

固定公开 [power_supply.h](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/include/linux/power_supply.h) 的 `CURRENT_NOW=17`。ELF `.rodata+0x21f0` 跳转表 `index=17-3=14` 的实际 byte=0x45，计算 `0x35ac+0x45*4=0x36c0`，与函数分支一致。该公开头文件是枚举参照；运行编译 ABI 仍不以同名源码代替证明。

| 层 | 精确动作与返回值 | 输出有效性后果 |
| --- | --- | --- |
| 通用 sysfs show 参照 | 固定公开 `power_supply_sysfs.c:286` 调用 get_property；负数返回错误；成功默认按 `%d\n` 输出 | 只能传播回调保留下来的错误，不能识别回调伪成功的零 |
| MCA USB 回调 `.text+0x354c` | `+0x3578` 用 `str xzr,[sp,#8]` 清零；`+0x36c0` 指向 `sp+8`；`+0x36c8` 调 `platform_class_buckchg_ops_get_bus_curr`；`+0x36cc` 读输出；`+0x36d0` 强制返回 0；`+0x36d4` 写 val | 不检查 getter 返回值。getter 出错而没写输出时，成功值为初始化的 0；仅是代码路径，未在本轮实机触发 |
| platform class `.text+0x360` | ID 0，ops+0x38；ID/未注册/空 slot 返回 -1；正常回调保留返回值 | 正常保留 provider 错误，但 USB 回调丢弃它 |
| proxy `.text+0x1070` | probe `.text+0x784..0x790` 注册 `.data+0x128`；其 `+0x38` relocation 指向本 getter。property=`0x20004`、size=4，直接返回 `mca_adsp_glink_read_prop` 结果 | 此层没有归零或隐藏 RPC 错误；这是磁盘模块的绑定证据，运行对象选择仍未证明 |
| GLINK read `.text+0x1e4` | 空 out/超宽 size/无 context=-1；离线=-107；write 错误原样返回；completion timeout=-110；远端状态非零不复制；成功 `+0x330` 才复制 4 bytes | 读取函数不必更新失败输出；调用者必须保留返回状态。没有时间戳或 ADC-valid metadata 返回给 USB callback |
| 远端正常 read 路由 | `0x60045860` 清响应 256-byte payload；`0x20004→0x60045a80→0x6002ff30`；case `0x6003003c` 调 category6 ADC API，保存状态后将输出×1000；`0x60030148` 返回保存状态 | 远端 payload 预零不是物理 0。case 即使 API 报错仍执行输出缩放；必须先判断返回状态 |
| 远端 ACK | `0x60045770` 调 read 路由；`0x600457cc` 把 getter 状态放 response+0x10；`0x600457e0` 回显 transaction ID 到+0x14；`0x60045848` 发 280-byte response | 静态正常回复保留状态，但不携带采样时间/硬件 validity flag |

GLINK callback `.text+0xc40` 的正常成功响应检查 size=280、远端状态0、transaction ID 匹配和读 opcode=1 后复制 payload；非零远端状态写入 `ctx+0xd4` 并 complete。transaction ID 不匹配仅记录日志、不 complete。本函数没有显式验证 property 与当前请求相同；opcode 非1而其他条件成立时会 complete 而不复制 payload。长度不匹配分支仍取远端 status 并 complete，status 为0时不是自生负 errno。因此“`read_prop` 返回0”本身也不是全部协议字段/物理数据有效性的保证。回调和 transport 的完整正常绑定仍应以运行构建审查为准；本轮没有制造异常消息。

状态复位需要按实际写入宽度审查：read 在 `.text+0x310` 取 `ctx+0xd4` 后，`+0x314` 的 `mov w8,#-1` 将 x8 上半 32 位清零，`+0x318` 用 **64-bit** `str x8,[x25,#0xd0]` 同时把 `ctx+0xd0` 置为 -1、把 `ctx+0xd4` 清零；timeout 的 `+0x3fc/+0x404` 也执行同样覆盖。因此不能仅因成功 callback 没有显式 `#0xd4` store 就推断错误状态残留。read 前段 `+0x23c` 等 `#0xd4` store 则以 x9=`sp+4` 为基址，清的是本地请求栈，不是 context。当前已闭合的是正常 completion/timeout 后的覆盖复位；这并不补上 property/长度/opcode 的独立错误语义或物理 validity。

### ADC 输出仍缺物理可信度

`0x600610f0` 将 channel 查找返回与 ADC 返回以 16-bit OR 合并；category6 使用设备 ID 构造 `USB_IN_I_MA_PM_XX`，搜索 channel40..45。无匹配分支仍可能返回0而没填 channel 栈槽，旧报告的缺口仍存在。本轮没有扫其他无名 ADC backend，也没有假定当前设备确实进入该故障分支。

`battman_adc_read 0x60060640` 检查参数/通道有效标志，调用运行时 attach 得到的 DAL vtable，经 `0x60060950` 请求；DAL 非零或结果评价非零时不向 caller 写 physical 字段。结果评价 `0x600609f0..0x60060d48` 包含 stale/timeout/invalid/FIFO 状态分支，返回0的状态也没有把原始结果状态和采样时间一并送到 Linux。此链只能证明 ADC API 请求和软件转换，仍未证明转换 backend 是否触发硬件、是否写配置、实际采样年龄及物理正方向。

对 channel40..45，ADC 成功输出路径在 `0x60060920..0x60060928` 取 physical 字段；USB handler 随后×1000。Linux ABI 规定电流为微安，但没有匹配 DAL 单位 schema 就不能仅据 `I_MA` 字符串与乘数确认真实校准单位、测点、符号或误差。正输入电流也不单独证明流向 SYS；零值也不证明电池或输入通路关闭。

## 阶段候选决定与下一步输入

| 候选 | 本阶段决定 | 下一步需要的 file / field |
| --- | --- | --- |
| charge_enable show | 可分类为软件聚合显示；不能当硬件确认。未新增读取白名单 | 匹配用户态 HAL/query 定义，区分 voter effective value、provider callback result、远端 hardware enable；带 errno/validity 的硬件查询入口 |
| USB current_now | 当前回调不能作为可靠 0 电流验收；未新增读取白名单 | 匹配内核 core 的 `power_supply_show_property/get_property` 与属性 ABI；纠正错误传播的匹配 MCA 源码；同构建 DAL ADC provider、device ID/channel 注册表、单位/测点/方向/采样年龄/副作用 schema |
| 0x2003 附带供电操作 | 仍不能实现受限控制接口 | PM8750BH/PMIH010X/PmicModel 对照、主次及外设 revision、表列字段定义与恢复契约、板级 SYS/电池/CP 拓扑 |

达到此处后应转向取得上述具体材料，而不是更多 getter 漫扫或试写寄存器。root 主审负责同 build 固件/HAL 来源核查；新取得文件需要与本包身份和构建记录对比。现阶段不能交付实机控制或采样命令，现有插件仍按已验证 FCC 限流评价。

## 精确证据包

`.tooling/bypass-check/stage1/power/` 包含 `extract_evidence.py`、原始公开源码、ELF attribute/descriptor/ops/jump table 的原始字节及 relocation、完整相关 Linux 函数片段、定向远端函数窗口及原始字节、DT 身份字段、两候选审查和 `manifest.json`。`exact-linux-function-index.json` 用原符号范围保存 byte hash，`exact-remote-window-index.json` 明确 start/end-exclusive；ADSP 片段沿用经验证的 Hexagon v79 解码，不使用 AArch64 解码远端。`verification.json` 复读原文件检验大小/hash、逐行片段和关键表项。

没有改旧报告、旧证据 manifest 或插件。远端函数地址是当前磁盘文件的离线审计坐标，不是运行地址授权，也不是可移植访问指令。
