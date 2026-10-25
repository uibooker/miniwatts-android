# Dada 充电控制链与恢复语义离线取证

日期：2026-10-13。目标设备：Xiaomi 15 / dada / 24129PN74C，OS3.0.303.0.WOCCNXM。

结论：`charge_enable` 的 buck 后端确实区别于输入限流和输入门控，值得继续核对其硬件 provider；但现有用户空间接口没有独立客户端撤票，buck 控制也没有覆盖快速充电 CP 路径。因此本报告不能证明物理旁路，也不能给出可安全安装的自动控制后端。本子任务仅处理已取得的本地 ELF、BTF、设备树和记录，没有调用 ADB，没有在手机上读写接口，没有修改插件代码。

## 证据范围

主要证据是设备固件的 ELF 重定位、AArch64 反汇编和模块 BTF；函数位置均指 **ELF 的 `.text` 节内偏移**，不是手机内存地址。BTF 的 `base:<offset>` 名称未解析，不能据此补造字段名称。可重现资料位于 [control-chain](../.tooling/bypass-check/control-chain/) 与 [driver-binaries](../.tooling/bypass-check/driver-binaries/)。完整散列与来源记录见 [evidence-manifest.json](../.tooling/bypass-check/control-chain/evidence-manifest.json)、[provider-manifest.json](../.tooling/bypass-check/root-acquisition/provider-manifest.json)。

| ELF | SHA-256 |
| --- | --- |
| mca_charge_interface | e3e71e2510d390a2a64ad530a9d8878ff84aec55db82ba28a19700d5e601d3c7 |
| mca_strategy_buckchg | 110c062e2b39d0603da94e926616cda207d768c4ec2698fd3ca41a2cd54d6a74 |
| mca_platform_buckchg_class | bb66b31353fc1e0c820dcf137b7b338368372b4a1024c953ad179a3e59448a41 |
| mca_path_control | 5ced1e59c6ebd5276e62e301e024d1520c77a384cd6ed596346f6609cebfd578 |
| mca_business_charger_comp | a7ed48bf0e1b435b7337fa8c6cc8880ed1bb23295f0a916fed350f2521ffc675 |
| mca_common | 9ae45a000698215fe3cddbef0ce2f19a307cb1d514e0f4fd7f40358c235a5d9e |
| mca_strategy_quickchg | 1a9cc4157b3899496b0b6ade716fd9d5f852c8af2298be1545a9e1c86e8dc339 |
| mca_soc_limit | 39ecdb364d05256660cbf5e238f1f9f187a09b7849f1bc5770f144d509e64568 |
| mca_bmd | 961c4292aa002ef1ffe1b9ec047236e0c845c05bb81f5676401daffbd538c0e7 |
| mca_business_battery_comp | e583456be088ff535cdbeab6f5ce3d47df59c4b694a432a71c1fcf0ec5b3691d |
| mca_qcom_sysfs | 5fafc96413fbad6c75dd15ed645575c7c49356919806efcf59e224b3cf479574 |
| mca_smart_charge | 9c85ed0c761e597df3a549ed25b9a9cfbbc9414fb7b38d2b6a6043bc787510af |

设备树原始 tar 的末尾混入缺失路径错误文本，不能直接信任解包失败后的任意缺项。主任务已逐 tar 头、body 和边界验证前 96256 字节的 97 成员，重建标准 EOF 的五子树子集。见 [dt-acquisition-integrity.json](../.tooling/bypass-check/root-acquisition/dt-acquisition-integrity.json)：验证子集 SHA-256 为 `8f1873148dbd91bd656236b3926ac20f3d2fbc205aaaa2f5c0cafa930e42f4b1`。仅对其中完整的 `mca_path_control`、`mca_platform_cp`、`mca_qcom_subpmic_proxy`、`mca_strategy_buckchg_class`、`smart_charge` 可讨论正向属性及缺项；未取得 `soc/mca_adsp_glink` 子树。

重新生成的 [dt-decoded.json](../.tooling/bypass-check/control-chain/dt-decoded.json) 同时保存原始 hex 和属性散列。四字节全零是 BE32 数值 0，不是空字符串。磁盘模块 vermagic 为 6.6.57 系列，而现场运行内核为 6.6.77 系列；磁盘 ELF、驱动绑定及设备树之间的一致性不等于已证明加载内存二进制身份。

## `charge_enable` 的三词协议

节点：`/sys/devices/platform/soc/soc:mca_charge_interface/charge_enable`。协议是三个空白分隔词 **user、type、value**，user 是 voter 客户端名，不是 Android 用户或 UID。这里只描述协议，不提供执行脚本。

证据：[mca_charge_interface.annotated.txt](../.tooling/bypass-check/driver-binaries/mca_charge_interface.annotated.txt)，`mca_charge_if_sysfs_store +0x74c`。

- `0x920..0x940` 用 `sscanf("%s %s %s")`；type 查 `.rodata+0x3a8` 的名称表。
- 实际表为 `buck/main_buck/aux_buck/quick/quick_main/quick_aux/div1/div2/div4/wl_buck/wl_main_buck/wl_aux_buck/wl_quick/wl_quick_main/wl_quick_aux/wl_div1/wl_div2/wl_div4/all`。这些名称存在不代表每个后端已注册。
- `all` 在 `0x9b8..0x9c8` 扩展为全部注册回调；不能用作便利的“全部电池停充”入口。
- `charge_enable` 属性分支在 `0xaa8..0xab8` 用 `kstrtouint(base=0)`，传给 ops 的 `set_charge_enable`（结构偏移 `+0x20`）。buck 将数值转布尔：0 禁止、非零允许。
- 数值转换返回值被忽略，临时数值先在 `0xa64` 清零，非法数字可能作为 0 进入回调。`blr` 后不检查回调结果；即使 ops 没注册、回调失败，也可走输入 count 返回。词数错误、type 不存在或输入过长才走 `-EINVAL`。
- `%s` 没有宽度，但此分支在 `0x8fc..0x91c` 先拒绝 count >= 254，三个目标分别位于 `sp+0x10/+0x110/+0x210`，各有 256 字节空间（初始化 `0x798..0x894`）。在真实 sysfs count 约束下，最长单词和 NUL 小于缓冲区；本次未据此发现溢出。未来后端仍须固定短 owner、固定 type 和合法 0/1，禁止模糊测试。

`charge_enable` 的 show 返回有效客户端/结果，不返回任意 user 的原票。此前 `buck eff_client:online 1` 不能用作插件 owner 原状态，也不能直接当作写回协议。

## buck 停充与输入控制的分叉

直接调用链为：

`charge_enable` → `strategy_buckchg_if_set_chg_en` → `mca_vote(chg_enable, user, 1, bool)` → AND 聚合 → `strategy_buckchg_enable` → `platform_class_buckchg_ops_set_chg(role, bool)` → 注册 provider。

证据：[buck 反汇编](../.tooling/bypass-check/driver-binaries/mca_strategy_buckchg.annotated.txt)、[buck class 反汇编](../.tooling/bypass-check/control-chain/mca_platform_buckchg_class.annotated.txt)。

| 控制项 | setter / 聚合回调 | 实际作用与局限 |
| --- | --- | --- |
| charge_enable / buck | setter `0x5ddc`；vote `0x5e4c..0x5e58`；enable callback `0x3ee0` | voter `info+0xd0`。回调在 `0x3f34` 调 role 0 set_chg；support_multi_buck 非零再在 `0x3f50` 调 role 1。本回调没有直接投 input_suspend/input_limit 票。 |
| input_suspend / buck | setter `0x5c50`；callback `0x3f70` | voter `+0xd8` OR 聚合，非零通过 `0x3fa8..0x3fc0` 对 buck_input `+0xe8` 的 `input_suspend` 客户端启用 value 0 票。属于输入限流至 0，不能当作只停电池充电。 |
| ichg_limit / buck | setter `0x60ac` | 对 charge_limit `+0xf0` MIN 投票。`0x610c..0x6124` 的 value=0 分支把 enabled 设为 0，撤客户端限流；**0 不是 0mA 停充**。 |

`platform_class_buckchg_ops_set_chg +0xdd8` 在 `0xe20` 转布尔，载入 provider ops `+0xf0`，在 `0xe3c` 调用。另一个代理已在 [provider 报告](DADA_CHARGER_PROVIDER_RESEARCH.md) 闭环该 ops：`g_qcom_buckchg_ops+0xf0` → `qcom_subpmic_enable_charging .text+0x1418` → `mca_adsp_glink_write_prop`，属性 `0x2003/BUCK_ENABLE_CHARGING`，payload 1 byte。输入暂停是 `0x2019`、FCC 是 `0x2005`、ICL 是 `0x2008`，确认 Linux 侧不是同一个请求。主任务原 ELF 独立复核位于 [provider-independent-review.txt](../.tooling/bypass-check/root-acquisition/provider-independent-review.txt)。远端 ADSP handler 的 PMIC 操作和板级拓扑仍未知，这条链本身不是 BATFET/系统供电分离的证据。provider 报告还发现 VSYS 名称实际读 `0x200c/BUCK_VSYSMIN`，不能用其稳定值证明实际系统输入电压。

buck 的非零 enable 分支还有条件副作用：`0x5e08..0x5e3c` 在 `support_pmic_vterm_dynamics_adjust`（BTF `+0x84`）为真时撤销 vterm 和 input voter 的 `pmic_temp_term` override。probe `0x528..0x55c` 根据 `support-pmic-vterm-dynamics-adjust` 属性存在性设置字段。该属性在已验证完整 buck DT 子树中不存在，因此按此 ELF/DT 组合预期关闭该分支；这不能替代运行内存身份和状态验证。

## voter 所有权与恢复问题

证据：[mca_common.annotated.txt](../.tooling/bypass-check/control-chain/mca_common.annotated.txt)、[mca_common.btf.json](../.tooling/bypass-check/control-chain/mca_common.btf.json)、[mca_common.elf.json](../.tooling/bypass-check/control-chain/mca_common.elf.json)。

`votable_type` 是 MIN=0、MAX=1、OR=2、AND=3。buck probe 创建 `chg_enable`（`0x6bc..0x6d8`）为 AND/default 0，`input_suspend` 为 OR/default 0，`buck_input` 为 MIN/default 100，`buck_charge_curr` 为 MIN/default 4000。AND 对启用客户端只要有一票 0 就禁止；没有启用票时返回配置 default。因此新增 active=1/value=1 票与客户端原本不存在并不等价，尤其最后一个 OEM active 票撤去时，默认行为可能不同。

`mca_vote +0x5f4` 最多分配 32 客户端，未知名字会 `kstrdup` 保存（`0x6f4..0x708`）。内核可以通过同名 `mca_vote(..., enabled=0, value)` 停用投票，但该操作不释放客户端名/槽位；只有销毁整个 voter 才释放全部。因此即使将来提供撤票，也应复用稳定独立 owner，不能每次随机命名。

本次检查模块完整函数表、export 和 debugfs 创建点：有 `mca_vote`、`mca_get_client_vote`、`mca_is_client_vote_enabled` 等内核 API；没有独立 remove-client export。该模块创建的用户空间入口是 status、force_val、force_active，而非通用 owner 撤票。buck `set_chg_en` 和 quick `set_chg_en` 无论请求 0 或 1 都把 enabled 固定为 1；`charge_enable` 无第四词 enabled 参数。结论限定于已检查的这些用户空间入口，不能宣称整个内核绝无其他入口。

恢复有两个独立问题：

1. owner 原来不存在/disabled 时，写 1 只会留下 active 的允许票，无法还原原始票状态。保存 show 的有效值无法解决。
2. `mca_vote` 在 `0x7d4/0x7d8` 保存 client enabled/value，在 `0xa18/0xa1c` 保存 effective client/result，之后 `0xbf0` 才调用回调；失败 `0xbf4..0xc18` 仅记录并返回错误，不回滚票和 effective 缓存。相同票可能在 `0x790..0x7c0` 被忽略，不保证再次尝试硬件。buck setter 在 `0x5e58` 调 mca_vote 后，`0x5e5c` 无条件将返回值改为 0，已先吞掉 vote/provider 错误；sysfs 还再吞一次回调返回值。票已变/硬件未变、票已还原/硬件未还原两类状态必须分别验证，journal 不能只保存一个布尔值。

禁止复用 `online`、温控、SOC、BTB、`pmic_temp_term` 等 OEM 客户端或 override 来避免新增 owner：这些状态属于系统，可能被异步更新，插件不可覆盖其安全决策。

## quick / CP 是另一条电池充电路由

证据：[mca_strategy_quickchg.annotated.txt](../.tooling/bypass-check/control-chain/mca_strategy_quickchg.annotated.txt)、[ELF 表](../.tooling/bypass-check/control-chain/mca_strategy_quickchg.elf.json)。probe `0x368..0x374` 注册 `.data+0xf8` 的 `mca_charge_if_ops`，其首字段重定位指向字符串 `quick`；`set_charge_enable` 重定位指向 `.text+0xa6ec`。

- probe `0x1680..0x169c` 创建 `quick_chg_en` AND/default 1，保存于 BTF `mca_quick_charge_info+0x210`。
- setter `0xa6ec` 同样是 `mca_vote(+0x210, user, 1, bool)`，没有撤票分支。
- `input_suspend` 的 quick setter `0xa594` 将 suspend 布尔取反后投到同一 enable voter，不等于 buck 的输入限流逻辑。
- enable callback `0x4238` 结果为 0 时取消 delayed work，再调用 `mca_quick_charge_stop_charging +0x445c`。
- stop 在 `0x44b0/0x44bc` 关闭 role 0/1 CP 充电；还重设频率和工作模式、条件切换 PD 固定 5V/9V（`0x457c/0x459c`）、更改 buck helper 及 `wire_qc`/override。它是完整快充退出流程，不能归纳为只改变电池开关且保持输入不变。

所以仅禁 buck 不能证明全部电池电流停止；对 `all` 广播又会引入多个不同语义和不可独立恢复的票。

## path_control 控制输入/OTG 门，不是 BATFET

证据：[mca_path_control.annotated.txt](../.tooling/bypass-check/control-chain/mca_path_control.annotated.txt)、[BTF](../.tooling/bypass-check/control-chain/mca_path_control.btf.json)、已验证 [DT](../.tooling/bypass-check/control-chain/dt-decoded.json)。节点前缀为 `/sys/devices/platform/soc/soc:mca_path_control/`。

属性是 `usb_in/wls_in/wls_rev/otg_in/wls_vdd/control_value`。前五项位值分别 1、2、4、8、16。show `+0x4c8` 读 software condition_value `+0xd8`，单项返回 masked 位值而非都归一为 0/1。store `+0x5bc` 用十进制 signed integer，非零设置对应位，再执行路径表。

处理函数 `+0x1a4` 按精确 condition 匹配 DT 的路径和 gate。scheme 0 是 GPIO（`+0x1350`，`0x13e0` 调 `gpiod_direction_output_raw`），scheme 1 的 PMIC register 函数 `+0x1454` 在此 ELF 是直接成功返回的 no-op，scheme 2 CP chip `+0x1460` 仅对 role 0 在 `0x1518` 调 `platform_class_cp_enable_ovpgate`。role 为 OVPGATE/WPCGATE/EXT_BST/WLS_VDD/HBST，没有 battery/BATFET role。

现场 DT control_scheme 仅 role 0 是 `2,ovpgate,cp_chip_role`；role 值属性 bytes=`00000000`，即 CP role 0。USB 路径 `usbin_ctrl=[0,1,3,0,2,0]` 将 OVPGATE 设为 1，其余内容不能当作电池充电命令。

store 先改变 condition；handle `0x478` 调 scheme，随后 `0x480` 无论返回如何缓存 gate 状态。`control_value` store 分支 `0x8e4..0x8ec` 直接调用 handle，不自行保存 condition_value，因此总值读回甚至不保证回显写值。周期 `mca_path_control_update_status_work +0x1248` 会根据在线/OTG 等另行更新 condition。保存 aggregate 后写回无法恢复多个系统事件/客户端的原状态；本入口排除。

## OEM 原生 SOC 维护路径：有撤票，但不能借用作插件 owner

证据：[mca_soc_limit.annotated.txt](../.tooling/bypass-check/control-chain/mca_soc_limit.annotated.txt)、buck/quick/smart_charge 反汇编。

`mca_soc_limit` 导出的 `soc_limit_process +0x4` 是内核 API，模块完整函数表没有 sysfs store。enable 且 SOC **大于** threshold 时在 `0x4c` 发 type 5/event 52 的 active=1，disable 在 `0x80` 发 active=0。business charger `0x258c..0x25e8` 将 SOC 事件分发给 buck/quick/无线策略（type 0..3）。

另一路是现有 `smart_chg` 单功能控制：bit 4 ENDURANCE_PRO 与 bit 1 NAVIGATION 在 `0xab4/0xa84` 设置对应 enable 和 upper 8-bit value；`smartcharging_handle_algorithm_conflict +0x101c` 按 endurance、navigation、night 优先级选择共享 SOC 阈值。`smart_charge_soc_limit_workfunc +0x2588` 在 SOC >= threshold 触发，在 SOC <= threshold-5 释放，经 `smart_charge_set_soc_limit +0x2d7c` 调注册 callbacks。

buck `strategy_buckchg_soc_limit_sts_callback +0x6288`：

- 普通分支（`support_charge_more +0xa4` 为 0）在有 proc_data 且请求有效时，`0x630c/0x6320` 对 `chg_enable +0xd0` 和 `input_voltage +0xe0` 的 `soc_limit` 客户端投 enabled=1/value=0。这里是输入电压策略票，**不是 buck_input 的 0mA 票**。
- 释放在 `0x6384..0x63ac` 撤这两票，还涉及撤 `csd_pulse` charge_limit 票；允许继续由其他策略决定充电。
- `support-charge-more` 路径 `strategy_buckchg_process_soc_limit_change_more +0x4918` 则按步数同时限制 charge_limit 和 input_limit，最终投 chg_enable=0；释放撤三票（`0x4a38..0x4a78`）。其属性在已验证完整 buck DT 子树中不存在，按此 ELF/DT 预期普通分支，但不能推广到其他机型/运行身份。

quick `strategy_quickchg_smartchg_soc_limit_callback +0xaa2c` 对 `chg_disable +0x208` 的 `soc_limit` always enabled=1，value 为 active 状态。该 voter 名称 `quick_chg_disable`、OR 聚合；`mca_quick_charge_chg_disable_voter_cb +0x4190` 非零取消 work 并调用同一复杂 CP stop `+0x445c`。释放写 0 是 OR 中性票，仍不是删除该客户端；也不会逐项还原之前的 PD/CP mode。

这说明 OEM SOC 状态具有自己的释放链，比随意改内核内存更明确，但不足以成为插件独立、精确恢复的电池开关。此前现场 `smart_chg=80` 已有 bit 4，不能占用、降低、关闭用户/OEM 的 SOC 策略。聚合 show 只含功能 enable，不给所有旧 value、当前 owner 或冲突算法状态，无法只保存一个读值恢复原设置。`debug_ctrl` 的 SOC limit 分支还有 `charger_partition_write_soc_limit`（`0xa10`）持久分区写，排除。

## 其他替代入口排除

| 模块/入口 | 直接证据 | 结论 |
| --- | --- | --- |
| business charger handle_state / stop_handle_charge | store `0x6ac/0x6b4` 改全局状态，`+0xaec` 派 buck/quick event 60；buck event60 `0x3918..0x3940` 同时给 input_limit 和 chg_enable 的 handle 客户端启用 0 票，allow 分支 `0x382c..0x3850` 撤两票 | 停输入与停充一起，且有 USB psy/唤醒/uevent，排除电池专用旁路入口。 |
| mca_bmd | [反汇编](../.tooling/bypass-check/control-chain/mca_bmd.annotated.txt) 完整函数表只有 `btb_sysfs_show +0x4`，没有 sysfs store；其工作流监测 BTB/BMD 状态 | 电池接触/安全状态监测，不能拿异常状态客户端作停充控制。 |
| battery power_supply 可写 property | [反汇编](../.tooling/bypass-check/control-chain/mca_business_battery_comp.annotated.txt) `batt_psy_set_prop +0x760`，若干 property 命中 `0x7b4` bitmask 即返回 0；不使用 value，不调用硬件或充电 voter；其余返回错误 | writable 标记/成功返回不能推出有 charging_enable 硬件控制。 |
| mca_qcom_sysfs | [完整函数表与重定位](../.tooling/bypass-check/control-chain/mca_qcom_sysfs.annotated.txt) 的 store 为 pd_verifed、cc_toggle、dam_ovpgate、wireless fw、reverse_chg_mode、shipmode_count_reset、wls_thermal_remove、hall_phone_case 等 | 未发现 charge_enable/charging_enable 专用 store；dam_ovpgate 属输入 gate，ship/反向充电/温控移除均不满足目标，禁止尝试。 |

## 可只读核对的 voter 状态与不可用的 force

`mca_create_votable` 在 `0x10ec..0x1104` 创建 `business-votable/<name>/status`，mode `0444`、fops `.rodata+0x410`。`mca_votable_status_open +0x1318` 是 single_open，show `+0x1348..0x14b0` 只 mutex lock、读软件票/有效/override/force 标志、seq_printf、unlock；没有硬件回调、寄存器访问或 read-to-clear。仅在 debugfs **已经挂载**且节点存在时，主任务可考虑进一步读取 `chg_enable/status`、`input_suspend/status`、`buck_input/status`、`quick_chg_en/status`、`quick_chg_disable/status`。本子任务没有执行这些读取，现场有效票和 override 状态仍未知。

`force_val` 另有 debugfs 控制入口；`mca_force_active_set +0x150c` 可绕正常投票直接执行 effective callback。它不提供“撤自己票”，不保证温控/安全票有效，不能作为恢复绕路。全局 override、销毁/重载 driver、改内存、调用任意内核函数均不是本插件可接受的用户空间恢复策略。

## 尚缺的证明与验证条件

1. Linux provider 至 GLINK 已闭环；仍缺远端 ADSP/PMIC 的末端控制语义，尤其 charge enable 是否保持系统输入供电，而非同时移除电池供电路径；磁盘/provider ID 不能直接证明物理寄存器行为。
2. buck 与 quick 目前的实际 active/override/force 状态、系统正在采用哪条充电路径；这些本轮未取得 debugfs 读数。
3. 独立 owner 的可用 userspace enabled=0 撤票协议，以及读取该 owner 原 enabled/value 的协议；目前检查链中未找到。
4. quick 停止后的输入与 buck fallback 行为，以及退出时恢复由 OEM 策略决定的条件；不能靠同一 1 命令宣称 exact restore。
5. 加载代码与磁盘模块的对应关系，及有限的、可信的状态读回；sysfs count 成功、software voter、path cache 都不是硬件确认。
6. 真实负载下 USB 继续供电同时电池净电流接近 0、charge_counter 长时基本不增长的对比数据。目前只有此前限流插件仍充电的记录，没有这些候选接口的实机写测试。主任务末次只读记录 capacity=80、current=0，可能符合已有 SOC 上限或其他充电策略；不能归因于候选接口、不能用该近阈值单帧作为旁路或 A/B 效果证明。

在这些条件解决前，保留 buck charge_enable 为 **离线候选**；不升级为可用旁路后端，不新增自动写入动作。后续任何实机验证应由主任务先给出单一明确接口、原始状态和恢复条件的审查结果，再安排受控实验；本报告不授权实验。
