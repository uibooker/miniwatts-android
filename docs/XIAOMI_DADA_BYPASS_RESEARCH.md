# Xiaomi 15 / dada 旁路供电接口调研

调研日期：2026-10-13。目标：核对已连接手机的 MCA 充电节点是否有可验证的真正旁路接口。本记录使用小米官方内核仓库；没有对手机充电节点执行写入。

## 结论

**已从手机实际驱动确认原生旁路请求协议，但尚未确认真正电源旁路。** 首轮官方源码调研遇到缺失的 MCA 实现；取得 root 后，对手机 vendor 镜像中的驱动二进制进行了只读分析，追踪到 `smart_chg` 中的原生 bypass 请求及 FCC 限流投票。该机制不等同于已证明外部供电维持系统运行且电池停止充放电，详见后面的二进制补充。

MiCode 官方 `dada-v-oss` 分支包含 MCA 构建入口，但实际驱动目录是指向仓库外 `vendor/xiaomi/proprietary/mca/driver/mca` 的符号链接；本次读取的官方分支没有提供该目标目录的实现。因此官方公开源代码仍不足以追踪 `control_value`、`usb_in` 等节点的完整 show/store 函数、值域、硬件调用和恢复语义。[M2][M3][M4]

手机上存在 MCA 节点这一事实，只能证明当前内核暴露了相关接口。它不能证明某个值会实现电池隔离、停止给电池充电并维持外部供电，或证明操作可安全恢复。模块不应猜测值、轮流写入候选节点，或把“禁充”和“电源通路已验证”当成同一个状态。

## 目标设备与取证范围

下表来自本次主任务的 ADB 只读取证和管理器画面，属于本机观测；不是公开源码对设备兼容性的承诺。

| 项目 | 观测值 |
| --- | --- |
| 型号 | `24129PN74C` |
| 设备代号 | `dada` |
| Android | `16` |
| 系统版本 | `OS3.0.303.0.WOCCNXM` |
| 内核 | `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k` |
| SukiSU | `v4.1.3`，版本码 `40796`；首页显示工作中、`<LKM>` |
| ADB shell 首轮 | 未获得可用 root shell；尝试读取部分节点遭 SELinux 拒绝 |
| ADB shell 后续 | 用户授权 Shell 后，`su -c id` 返回 `uid=0(root)`，可读取 MCA 状态和 vendor 驱动 |

观察到的目录或节点名包括：

- `soc:mca_business_battery`
- `soc:mca_business_charger`
- `soc:mca_path_control`，下有 `control_value`、`otg_in`、`usb_in`、`wls_in`、`wls_rev`、`wls_vdd`
- `mca_strategy_buckchg_class`
- `mca_charge_interface`
- `mca_qcom_sysfs`

这些名称不是本报告推荐写入的路径清单。首轮取证时权限不足，未取得完整的实际路径、权限、show 输出、store 实现或 root 环境下的读取结果；用户后续授权 Shell 后完成的 root 只读取证和驱动分析，见“本机驱动二进制补充”，取代首轮的权限限制。首轮 ADB shell 没有可用 `su` 入口不能单独判定 SukiSU 未工作；也不能仅据此确定授权或隐藏机制的具体原因。

## 官方源码证据

| 证据 | 具体内容 | 能支持的结论 |
| --- | --- | --- |
| MiCode 设备分支表 | `dada-v-oss` 对应 `Xiaomi 15 / Xiaomi 15 Pro`、`Android V`，基线为 `qcom-LA.VENDOR.15.4.0.r1-08900-r1.0.r1_00022.1` | 这是官方给 dada 设备族列出的 Android V 分支。[M1] |
| 分支固定版本 | 本次读取 `dada-v-oss` 的提交为 `afb5a488a44e7e980b2ef7b4c145703cfcb7aac5` | 后续引用固定到该提交，不依赖分支之后是否变化。[M2] |
| 电源驱动 Makefile | 第 115 行为 `obj-$(CONFIG_XM_POWER_SUPPLY) += mca/` | MCA 随 `CONFIG_XM_POWER_SUPPLY` 参与该树的构建。[M3] |
| MCA 目录入口 | GitHub 标记 `drivers/power/supply/mca` 为 `symbolic link`、55 Bytes；内容是 `../../../../../vendor/xiaomi/proprietary/mca/driver/mca` | 该路径不是公开在这里的 MCA 源码目录，而是构建时依赖的外部目录链接。[M4] |
| 仓库根目录 | 同一提交的根目录没有 `vendor` 目录 | 此仓库快照本身不包含链接目标，不能从这里追踪 MCA 节点实现。[M2] |
| dada 构建配置 | `build.config.msm.dada` 继承 `${ROOT_DIR}/msm-kernel/build.config.msm.sun` | dada 构建入口也使用 sun 平台配置；单独该配置不会补齐缺失的 MCA 实现。[M5] |

这里的“缺失”限定为本次读取的官方仓库快照；不等于断言小米从未在任何其他渠道公布 MCA 源码。本次没有找到能对应当前手机驱动的官方 MCA 实现，无法据此填写后端配置。

## 与当前手机版本的对应限制

MiCode 分支表标记的是 Android V，而当前手机运行 Android 16。即使分支同属 dada，也尚未建立上述官方提交和手机 `gf9a1d4bd8353` / `abogki440974771` 内核构建之间的对应关系。不能把较早分支的某个节点约定直接当作当前固件的约定。[M1][M2]

此外，本次核对的是内核仓库中的链接与构建入口；它没有回答当前固件中 MCA 与 PMIC、充电芯片或远端固件通信的具体方式。`path_control`、`usb_in`、`bypass` 等命名本身不足以替代这种追踪。

## 模块应采用的约束

1. 默认保持后端未配置，明确显示 `power_path=unverified`，避免“已启动旁路”之类未经证实的状态。
2. 手机获得授权后的下一步是只读采集：确认 root 身份、解析节点实际路径、列出权限并读取与充电相关的现有状态，不写入 `control_value` 或其他未知节点。
3. 配置后端之前需要对应当前驱动的 show/store 源码或可信驱动协议，确认允许值、调用的硬件操作、前置条件、恢复方法以及与其他充电服务的关系。
4. 后续如果找到可用节点，必须保存原值并验证恢复；读取开关回显只能证明驱动接受了该值，不能证明硬件电源路径。
5. 真正旁路的运行验证应在外部供电与明确负载下记录输入状态、电池电流、温度及电量变化，并结合驱动行为判断。单次电流接近零或电量不变不足以单独确认硬件旁路。

普通 SukiSU 脚本模块的结构、禁用/卸载流程，以及其他小米内核中“旁路命名节点实际只写热限制”的反例，见 [SUKISU_BYPASS_RESEARCH.md](SUKISU_BYPASS_RESEARCH.md)。该反例不能用来推断 dada 的 MCA 实现；它说明为什么需要先核对驱动语义。

## 来源

- [M1] MiCode 官方设备分支表，README 分支固定提交 `16d89a630ea8752c84bf4d76201964524d620616`：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/16d89a630ea8752c84bf4d76201964524d620616/README.md>。
- [M2] MiCode dada 官方源码树固定提交：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/tree/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5>；设备分支入口：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/tree/dada-v-oss>。
- [M3] 电源驱动 Makefile：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/Makefile#L115>。
- [M4] MCA 符号链接：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca>。
- [M5] dada 构建配置：<https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/build.config.msm.dada>。

## 本机驱动二进制补充

以下结论来源于本机 `/vendor_dlkm/lib/modules` 的原始 ELF 文件，是当前手机 vendor 镜像的一手工件。普通 `adb pull` 遭手机权限拒绝，随后使用已获授权的 `adb exec-out su -c 'cat ...'` 只读提取。未写设备充电节点、未加载或卸载任何驱动。工件和分析中间结果保存在 `.tooling/bypass-check/driver-binaries/`，`manifest.json` 记录完整 SHA-256、大小、来源和采集方式。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `mca_charge_interface.ko` | 29656 | `e3e71e2510d390a2a64ad530a9d8878ff84aec55db82ba28a19700d5e601d3c7` |
| `mca_path_control.ko` | 40488 | `5ced1e59c6ebd5276e62e301e024d1520c77a384cd6ed596346f6609cebfd578` |
| `mca_platform_buckchg_class.ko` | 85376 | `bb66b31353fc1e0c820dcf137b7b338368372b4a1024c953ad179a3e59448a41` |
| `mca_strategy_buckchg.ko` | 130904 | `110c062e2b39d0603da94e926616cda207d768c4ec2698fd3ca41a2cd54d6a74` |
| `mca_platform_cp_class.ko` | 76056 | `a8074a9acf66fab9026497b6b03c8e8b09f725fe267939082541f03ae1ebd473` |
| `mca_sysfs.ko` | 32632 | `63e0aa14578bf61620ce0f8dd52066ffde1334afa1acdd3c7e6a0c875c09b505` |
| `mca_smart_charge.ko` | 88544 | `9c85ed0c761e597df3a549ed25b9a9cfbbc9414fb7b38d2b6a6043bc787510af` |
| `mca_business_charger_comp.ko` | 99048 | `a7ed48bf0e1b435b7337fa8c6cc8880ed1bb23295f0a916fed350f2521ffc675` |

八个文件均为 `elf64-littleaarch64`，含符号表、重定位和拆分 BTF 类型信息，没有可用 DWARF 调试信息。反汇编使用 `llvm-objdump -dr`，并结合 ELF 重定位解析字符串引用、BTF 结构字段偏移和枚举值；没有仅凭 `strings` 内容确定接口协议。

八个文件的 `vermagic` 均为：

```text
6.6.57-android15-8-g6f1d969db78f-4k SMP preempt mod_unload modversions aarch64
```

这与当前 `uname` 的 `6.6.77...gf9a1d4bd8353...` 不同。文件确实来自本机 vendor 镜像，但 `vermagic` 本身不能建立当前运行内核与 MiCode 官方提交的对应关系，也不能证明每个提取文件此刻都已加载。

### 原生请求入口：smart_chg

实际入口是 `class/xm_power/charger/smart_charge` 链接所指向的 `soc:smart_charge` 目录中的 `smart_chg`，不是名为 `smart_bypass` 的节点。主任务只读取证发现的九个属性，与二进制 `smartchg_sysfs_field_tbl` 的九项完全一致：`smart_chg`、`smart_fv`、`smart_ichg`、`smart_batt`、`smart_night`、`posture`、`scene`、`board_temp`、`smart_sic_mode`。

二进制中的 `smart_bypass` 字符串用于 `mca_vote` 的客户端名称，不在此 sysfs 属性表中。全局节点搜索也没有发现充电相关的 `*bypass*` 节点；因此不能根据这个字符串拼出 sysfs 路径。

`mca_smart_charge.ko` 的下列代码和 BTF 相互对应：

- `smart_charge_sysfs_store` 位于 `.text+0x314`，以 `kstrtoint(..., base=0, ...)` 解析整数；`smart_chg` 分支在 `0x3ec` 调用 `smartcharging_receive_control_message`（`0x954`）。
- 控制字 bit 0 是 enable；bits 1..15 是单个功能选择位；bits 16..31 传递功能值。`0x9b4..0x9d0` 用 `x & (x - 1)` 检查功能位是否有多个，多个功能位会被标记错误。不是普通布尔节点。
- BTF 枚举 `SMART_CHG_MODE` 中 `SMART_CHG_BYPASS=10`。`0xa70` 检查 bit 10；`0xa74..0xa7c` 把 bit 0 写入 `smart_chg_data[10].enable` 对应的 `smart_charge_info+0x5e4`，并清零其 value。
- 由此可解释控制字 **1025（`0x401`）为原生 bypass enable 请求，1024（`0x400`）为 disable 请求**。这是二进制协议推导，调研期间没有执行这些写入，不能据此称为已验证旁路启动。
- `smart_charge_sysfs_show` 在 `0x100..0x1e8` 逐项汇总各功能 enable，bit 10 来自相同的 `+0x5e4` 字段。读值是状态集合，不是可以原样写回的控制字。现场 `smart_chg=80` 表示 bits 4、6 已设置，并没有 bit 10。

因此，未来后端必须按单个功能命令设置和恢复 bit 10；禁止把现场聚合读值 `80` 保存后整值写回，这会违反单功能控制协议。1025/1024 分支只改 bypass 请求字段，其他功能请求字段保持原值。请求回显只是 software enable，不能代替硬件电源路径测量。

### 请求后的实际行为：SOC、无线状态、scene、温度表与 FCC

设备树只读结果显示 `smart_bypass_support=1`、`smart_bypass_entry_soc=5`，三套 `smart_bypass_{high,med,low}_lmt_cfg` 各 120 字节。对应 BTF 结构 `SMART_BYPASS_CFG` 包含 support、active_status、cur_temp_index、smart_bypass_fcc、smart_bypass_mode、entry_soc 和三套温度配置。

`smart_charge_workfunc`（`.text+0x203c`）使用的是 `smart_charge_work` 成员指针，比 `smart_charge_info` 基址大 `0xa8`，必须扣除这个偏移理解字段：

1. `0x2214` 检查 support，`0x221c` 读取 bypass 请求 enable；`0x2224..0x2230` 比较 SOC 与 entry_soc。
2. SOC 低于 entry_soc 或无线充电在线时，分支打印退出日志并使本轮 bypass 模式归零；已有 `smart_bypass` 投票在 `0x22b8..0x22cc` 撤销。
3. 满足条件时，`scene` 选择 high/med/low 温度表，`update_smart_bypass_temp_section`（`0x2f0c`）选择温度区间；`0x24b8` 读取该区间的 FCC。`scene` 会改变限流配置选择，并不是一个独立的旁路开关。
4. `0x24d4..0x24e8` 调用 `mca_vote(smartchg_fcc_voter, "smart_bypass", 1, FCC)`；active_status、smart_bypass_mode、FCC 均另有状态字段，与请求 enable 有区别。
5. `mca_strategy_buckchg.ko` 的 `strategy_buckchg_smartchg_set_fcc_callback`（`0x6238`）把有效 FCC 转为充电电流 voter 的 `smartchg` 客户端投票。结果为零时撤销这一级投票，不能把 FCC=0 简化为“将充电电流强制为零”。

设备树原始十六进制保存在 `.tooling/bypass-check/smart-bypass-device-tree.txt`。按大端有符号 32 位整数解码，各记录为五个整数、20 字节；反汇编的表索引步长为 `0x14`，读取第五码作为 FCC。这与 BTF 中三套配置各为扁平 `int[50]`、运行时另存有效长度的结构一致。

| 温度区间原始阈值 | high 表 FCC | med 表 FCC | low 表 FCC |
| --- | ---: | ---: | ---: |
| -100..150 | 3000 | 17600 | 17600 |
| 151..200 | 2500 | 17600 | 17600 |
| 201..250 | 2000 | 17600 | 17600 |
| 251..300 | 1500 | 8000 | 9000 |
| 301..370 | 1000 | 5000 | 6000 |
| 371..800 | 650 | 2000 | 3000 |

每行中间的两个整数均为 5，参与温度区间转换的滞回判断。上述数值是原始驱动配置：BTF 本身不保存物理单位，阈值与常见十分之一摄氏度、FCC 与常见 mA 约定一致，但本次未追踪到底层硬件 provider 的单位换算，不把这点作为已验证事实。**所有 FCC 值均为正，配置没有零充电电流目标**；即使未来遇到零值，该 callback 的撤票语义也不支持把它直接当作禁充。

这条已追踪链路体现的是小米驱动原生的智能充电 FCC 限流机制。没有在已追踪路径发现明确的电池隔离、切换系统电源路径或保证电池电流为零的硬件操作。不能将 `smart_bypass_support=1`、enable 回显或 `smart_bypass` 有效票直接升级为“真正旁路已确认”。

### charge_enable 与 input_suspend 是不同控制链

`mca_charge_interface.ko` 的 `mca_charge_if_sysfs_store`（`0x74c`）对普通属性使用 `sscanf("%s %s %s", user, type, value)` 三段文本协议，并按 type 注册的 callback 分发。不是向 `charge_enable` 写一个裸 `0` 或 `1`。

`charge_enable` 的 BTF 枚举值为 1，分支解析数值后调用 `mca_charge_if_ops.set_charge_enable`（结构偏移 `0x20`）；`buck` 的处理函数 `strategy_buckchg_if_set_chg_en`（`mca_strategy_buckchg.ko:0x5ddc`）在充电 enable voter 上投票。其 show 函数 `strategy_buckchg_if_get_chg_en`（`0x5e78`）返回有效客户端和结果，解释了现场读值 `buck eff_client:online 1`；这不是某个调用者原始投票的快照。

有效 enable 最终的 callback `strategy_buckchg_enable`（`0x3ee0`）调用 `platform_class_buckchg_ops_set_chg`。后者在 `mca_platform_buckchg_class.ko:0xdd8` 取注册平台操作 `+0xf0` 并间接调用。已提取的这一层没有完整的硬件 provider 实现，因此尚不能确定 disable 对外部系统供电的最终影响。

`input_suspend` 是另一套操作与 voter；`strategy_buckchg_input_suspend`（`0x3f70`）投票到输入控制链。有标签的现场记录 `.tooling/bypass-check/device-root-inventory.txt` 第 26、27 行分别是 `input_suspend=`（空输出）和 `suspend_status=0`；不能把后一属性的 0 当成 `input_suspend` 的读值。空输出也不足以判定输入控制状态。它不能被视为 `charge_enable` 的同义开关，更不能用暂停输入来替代旁路。

该链路能够证明驱动区分“充电 enable”与“输入控制”，但没有单独证明关闭充电后系统稳定由适配器供电。下一步如要实现真正禁充并维持供电的模式，需要继续追踪平台硬件 provider 或在明确、可恢复的试验条件下测量；当前研究没有进行这样的写入试验。

## 模块在本机的验证

模块更新为 0.1.1，扩展 MCA 只读诊断，但没有将厂商 FCC 限流策略接成真正旁路后端。18 项隔离模拟测试通过；新增检查诊断不创建状态文件，且不能把 MCA 的聚合状态配置成布尔开关。

在手机 `/data/local/tmp/miniwatts-bypass-preview` 解包预览 ZIP，Android `/system/bin/sh` 对所有脚本的语法检查通过，root 执行 `control.sh diagnose` 通过；随后用 `/data/adb/ksu/bin/busybox sh`、`ASH_STANDALONE=1` 执行同一诊断也通过。证据位于 `.tooling/bypass-check/module-diagnose-android.txt` 和 `module-diagnose-busybox.txt`。

只读诊断仍见 `smart_chg=80`、`smart_bypass_support=1`、`smart_bypass_entry_soc=5`。这些结果不能作为旁路激活或物理效果的证明。本轮没有安装模块、没有启动监控、没有写电源节点、没有执行 1025/1024 请求，也没有测量开启后的供电效果。
