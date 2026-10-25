# Dada 原生接口 Stage 2：补全键表与两条恢复候选排除

日期：2026-10-13。设备研究范围：Xiaomi 15 / dada / OS3.0.303.0.WOCCNXM 官方 OTA。本文继续 [Stage 1](DADA_NATIVE_INTERFACE_STAGE1.md)，不替代原报告。

本轮补齐 Stage 1 键表的 11 个未解路径，给全部 110 个键附上筛查分类，并手工核对另 11 个重要或异常路径。新增深查仅限 `enable_rollback` 与 `shipmode_count_reset`：前者是燃料计低压关机阈值回退标志，后者沿本文确认的 charge_interface/buck/provider 链设置 shipmode 标志，重启通知链可条件执行硬件请求。两者均不提供插件自己的充电撤票，不能升级为旁路后端。

本轮只在电脑上读取已经提取的 ELF、反汇编与报告；没有 ADB、手机读取或写入、HAL/Binder 调用、模块加载、安装包、寄存器实验或上游脚本执行。没有重新遍历旧 20 模块，也没有重新分析 tx42/43/53/54。下文所有位置都是服务 ELF 虚拟地址或模块节内偏移，不是手机运行内存地址。

## 来源与复核材料

官方来源仍是 [dada OS3.0.303.0.WOCCNXM OTA](https://cdnorg.d.miui.com/OS3.0.303.0.WOCCNXM/dada-ota_full-OS3.0.303.0.WOCCNXM-user-16.0-527efd3838.zip)。本轮沿用已验证的 vendor 与 vendor_dlkm 分区，只选择性补提 `mca_strategy_fg_comp.ko`；[fg-module-selection.json](../.tooling/bypass-check/stage2/root/fg-module-selection.json) 记录精确文件、大小、散列和另两项仅名称/string 检查结果。

| 来源 ELF | SHA-256 | 本轮用途 |
| --- | --- | --- |
| vendor.xiaomi.hardware.micharge-service | 0dcfb7b5816d962978e8724fdbb7798627f0575c6a72beae277c55b51f1f158b | 原始 String 初始化、键/路径复制、选中 ship setter |
| mca_strategy_fg_comp.ko | ea7814275655d963e40be17cfed0f020d4baccdad7e7eeb710a16549ee69efd8 | 新增 enable_rollback 实现 |
| mca_charge_interface.ko | e3e71e2510d390a2a64ad530a9d8878ff84aec55db82ba28a19700d5e601d3c7 | ship 节点 ID、特殊解析及回调 |
| mca_strategy_buckchg.ko | 110c062e2b39d0603da94e926616cda207d768c4ec2698fd3ca41a2cd54d6a74 | 选中回调及 ops 绑定 |
| mca_platform_buckchg_class.ko | bb66b31353fc1e0c820dcf137b7b338368372b4a1024c953ad179a3e59448a41 | ship provider ops 偏移 |

`mca_qcom_subpmic_proxy.ko` 的精确来源/散列另存于 [manifest.json](../.tooling/bypass-check/stage2/native/manifest.json)。旧 20 个磁盘模块与包内模块对应已由 Stage 1 验证；新 fg_comp 的既有 metadata 显示当时曾加载，但没有其运行内存 build ID，不能宣称当前加载二进制与新提取文件逐字节一致。

复核入口：[unresolved-closure.json](../.tooling/bypass-check/stage2/native/unresolved-closure.json)、[key-classification.json](../.tooling/bypass-check/stage2/native/key-classification.json)、[instruction-windows.json](../.tooling/bypass-check/stage2/native/instruction-windows.json)、[candidate-tables-and-relocations.json](../.tooling/bypass-check/stage2/native/candidate-tables-and-relocations.json)。窗口 JSON 保存原始 bytes、SHA-256、位置、来源 ELF 散列和对应反汇编文件。表证据保存原始 `.data`/跳表 bytes 和重定位，而非仅根据符号名称判断。可重现生成器 [build_evidence.py](../.tooling/bypass-check/stage2/native/build_evidence.py) 只读来源、只写本轮目录，不执行目标机器代码。

独立审查发现初版原始窗口抽取有 ET_REL 节选择错误：多个节 `sh_addr=0` 重叠，按首个覆盖地址的节查找可能误取 `.rodata`，其中 ship-provider-00/01 的 bytes 与 `.text` 不符；初版验证器复用相同地址查找，先前 PASS 无效。已修复为按指定节 `sh_offset + section_offset` 读取，服务也仅在指定 `.text` 内转换 VA，明确检查边界；重新生成全部 60 窗口及 manifest。修正版验证器独立解析节索引，并逐条比较新 LLVM 反汇编的实际 4-byte opcode，而非仅查指令地址覆盖；[verification.json](../.tooling/bypass-check/stage2/native/verification.json) 记录修正版结果。原 Stage 1 材料未修改。独立审查的最终通过另由主任务确认，不将本地 PASS 视为批准。

## 11 个未解路径的手工闭合

下表采用 `/sys/class/xm_power/` 为绝对前缀；`charge_full_design` 行是特殊的相对路径。每项同时追踪全局 String 的构造与键表短/长 String 两条复制分支。行号是 Stage 1 的零起始 index。

| index / key | String global | 初始化窗口 | 表复制分支 | 闭合路径（上述前缀后缀） |
| --- | --- | --- | --- | --- |
| 0 ui_soh | 0x1241a8 | 0x2f6c4..0x2f70c | 0x31828 | fg_master/ui_soh |
| 1 ui_slave_soh | 0x124250 | 0x2f898..0x2f8dc | 0x31894 | fg_slave/ui_soh |
| 3 rsoc2 | 0x1241d8 | 0x2f750..0x2f794 | 0x3194c | fg_slave/rsoc |
| 4 charge_full_design | 0x124bc8 | 0x313b4..0x31400 | 0x319b0 | **sys/class/power_supply/battery/charge_full_design**（完整原字节，无前导 `/`） |
| 28 charger_partition_poweroffmode | 0x124028 | 0x2f1f0..0x2f24c | 0x32370 | charger/charger_partition/charger_partition_poweroffmode |
| 46 fg1_vol | 0x1249e8 | 0x30e24..0x30e6c | 0x32ae0 | fg_master/vbatt |
| 63 get_learn_power_dev_b | 0x1249a0 | 0x30d44..0x30d90 | 0x331bc | fg_master/learning_power_dev_b |
| 77 qmax | 0x124508 | 0x300a8..0x300ec | 0x33734 | fg_master/qmax |
| 79 isc | 0x124538 | 0x3012c..0x30170 | 0x337f4 | fg_master/isc |
| 80 batt_sn | 0x124550 | 0x30170..0x301a4 | 0x3385c | fg_master/batt_sn |
| 90 shipmode_count_reset | 0x124148 | 0x2f598..0x2f5dc | 0x33cb8 | charger/charge_interface/shipmode_count_reset |

缺项主要是旧自动解析器未识别先指定 global、后加载字面量的构造顺序。`charge_full_design` 的 literal `0x6148` 长 49 字节，构造尾部也保留此相对路径；未擅自补 `/`，它的运行时工作目录和实际解析结果未知。

`batt_sn` 必须追缓存：`0x2f79c..0x2f7d8` 从 literal `0x51e5` 读取前 32 字节和尾部，`0x2f7d0` 将 SIMD 内容缓存到 `sp+0x20`；`0x30178` 取缓存，`0x30190` 按对应顺序写回，`0x30194` 写 x19 尾部。后续 `0x30fa4` 才覆盖缓存，时间顺序不影响前面的构造。不能用附近 `isc` 字面量猜这个 global。

## 99 个既有自动路径的分类与手工检查边界

完整 110 行均在分类 JSON，路径来源保留为 99 个 Stage 1 自动候选 + 11 个手工补全。分类仅按键名与路径命名空间作筛查，**不表示可写、读取无副作用或有对应硬件功能**。

| 筛查组 | 数量 | 下游检查程度 |
| --- | --- | --- |
| 策略、输入路径或其他控制候选 | 25 | 只对本文两项新增深查；其他按原报告或明确未审查处理 |
| 燃料计认证、学习、校准或维护候选 | 32 | 名称筛查，未逐项分析 store/持久性；禁止当成普通测量读取清单 |
| 遥测、身份或其他候选 | 53 | 名称筛查，不能推出 show 无副作用 |

另手工核对 11 个自动来源项的 path 构造/复制：index 10 `fg2_soh_sn`、15 `mtbf_current`、16 `charge_enable`、17 `smart_batt`、18 `smart_chg`、64 `enable_rollback`、65 `is_support_mca`、89 `wlscharge_control_limit`、92 `handle_state`、93 `handle_stop_charging`、109 `sconfig`。该核对本身不增加深查候选，也不升级下游权限。仍有 **88 个自动来源路径未逐项手工核对**，所有 110 个下游语义也没有完整审查。

两处反直觉别名确实存在于这份编译结果：`fg2_soh_sn` 从 global `0x124238` 复制 `fg_slave/soh`，`is_support_mca` 从 global `0x124220` 复制 `fg_master/soh`。本轮确认的是初始化路径，不是方法业务语义。`charge_enable` 仍复制 `0x124058` 的 `charge_interface/input_suspend`，不是同名 charge_enable 节点。`wlscharge_control_limit` 确认指向 `charger_thermal/wireless_ctrl_limit`；`sconfig` 指向 `/sys/class/thermal/thermal_message/sconfig`，这两项仅核对路径，没有本轮驱动语义或恢复证明。

`smart_batt`、`smart_chg`、`handle_state`、`handle_stop_charging` 的相关驱动链及恢复限制见 [控制链报告](DADA_CHARGE_CONTROL_CHAIN.md)。本文未重做那些实现，也未证明 class symlink 与运行 platform 实例的实时绑定。不能把旧报告的 platform 实现直接变成新 HAL 自动写入授权。

## 新深查一：enable_rollback

服务表 index 64 的 path 从 global `0x1249d0` 构造：literal `0x40de`，`0x30de0..0x30e20` 复制长度 57 的 `/sys/class/xm_power/fuelgauge/strategy_fg/enable_rollback`；`0x33224` 的两条 String 复制分支将它放进该键条目。

在官方包 `mca_strategy_fg_comp.ko`，sysfs 表基址 `.data+0x1d8`、记录宽 `0x28`，对应 name relocation 在 `.data+0x408`，ID 位于 `.data+0x428`，值 **14**。store 跳表 `.rodata+0x6b` 的 id14 为 `0x80`，相对基址 `.text+0x6624` 得 **0x6824**；show 跳表 `.rodata+0x53` 得 **0x64bc**。

store `0x6824..0x6864` 用 `kstrtoint(base10)`，解析失败返回 `-EINVAL`；解析成功将任意非零 signed integer 转为 bool，`0x6860` 写 `info+0x988`，之后返回 count。没有 owner 参数，没有 vote enabled 参数，没有撤票或立即硬件回调。show `0x64bc` 读同一 byte，返回软件标志。

本模块完整反汇编中，对字面 offset `#0x988` 的可识别引用仅有 show `0x64bc`、store `0x6860`、consumer `0x87c4`。consumer 是 **mca_battery_shutdown_update_vcutoff_para**：在其他配置条件允许且选中低压阈值不高于 `0xbb7` 时，flag 为真走 `0x87e8..0x8800`，把 `info+0x930/+0x934` 设为 3050，把 `info+0x938` 设为 2900，日志明确标为 `Enable_Rollback`、`vcutoff_fw`、`vcutoff_shutdown_delay`、`vcutoff_sw`。flag 为假或条件不符走普通阈值更新路径。

结论限定于本模块的这条可识别实现：这是低压关机阈值策略回退，不是 charge voter 回滚。写回 false 也不立即还原已经计算出的阈值，更不等于撤回插件充电票；未证明其他模块不存在别的用途，未证明各 flag 当前值或运行实例身份。排除为插件停充/恢复接口。

## 新深查二：shipmode_count_reset

静态键 index 90 指向 `charge_interface/shipmode_count_reset`，global `0x124148`。Stage 1 方法表还把 **tx47 setSmCountReset(String, int*)** 映射到服务 **0x264dc**：本轮窗口 `0x264dc..0x265a8` 确认它复制该 global，并在 `0x2655c` 将输入原 String 交 writer `0x239c0`。这里只沿用已归档的方法表，不新增 Binder 调用或其他候选 setter。

charge_interface 表 `.data+0xf8` 中 name 在 `.data+0x1e8`，记录 ID 为 **6**。它与普通三词接口不同：`0x8a8..0x8f8` 把原输入当 value，固定 type 为 `buck`，固定 user 为 **micharger**（有末尾 r），跳过 `%s %s %s` 解析。跳表 `.rodata+0x5` 原字节 `[32,41,48,51,64,16,0]`、基址 `.text+0xa04`，id6 正好进入 `0xa04`。

该分支 `0xa10` 调 `kstrtouint(base10)`，不检查解析返回值；每次派发前 `0xa64` 已将临时 unsigned 清零，解析失败也可能以零进入回调。随后加载 charge-interface ops **+0x60**，`0xa40` 调用后忽略回调返回，最后走 count 返回。成功 count 不能确认有效执行。

绑定关系由原始重定位闭合：`g_strategy_buckchg_if_ops` `.data+0xf8` 的 **+0x60 = .data+0x158** 指向 **strategy_buckchg_if_set_ship_mode .text+0x61dc**。该函数忽略 user/context，把 value 转 bool，role 固定 0，在 `0x61f4` 调 **platform_class_buckchg_ops_set_ship_mode .text+0x1b94**。class 在 `0x1bc4` 载入注册 provider ops **+0x1e0**；`g_qcom_buckchg_ops` `.data+0x128` 的对应 **.data+0x308** 重定位指向 **qcom_subpmic_set_ship_mode .text+0x1ba0**。运行时 provider 注册身份仍需主任务单独核对，静态表绑定不是运行证明。

**即时动作**：provider `0x1bac` 只保存 bool 至 `info+0xc0`，日志为 `is_enable_shipmode`，返回 0；getter `0x1be0` 读取此 byte。此 setter 本身没有 GLINK 写，没有停充票，也没有供电/电池通路分离动作。

**后续条件动作**：probe `0x6a4..0x6b8` 将 `qcom_subpmic_ship_mode .text+0xdfc` 注册为位于 `info+0xc8` 的 reboot notifier，priority 255。notifier `0xe14..0xe38` 仅在 event 数值 1 或 3 且 `(notifier-8) = info+0xc0` 的 flag 非零时进入。接着查询 CP vendor；若结果非零，在 `0xe8c` 关闭 role 0 的 OVPGATE，然后 `0xe90..0xe9c` 发送 **GLINK property 5、8-byte event payload**。回调失败只记录，最终仍返回 0。

这条链是设置延后的 shipmode 条件，不能因节点名包含 count/reset 就当作无害计数器清零。原 `mca_qcom_sysfs` 同名属性属于另一入口，本文未把它们混成同一实现。撤 flag 不提供独立 voter 所有权，也不能证明已触发硬件后的精确恢复；远端 property 5 handler 仍未取得。排除为旁路与充电撤票入口，不提供实机试写值。

## 完成范围与剩余缺口

本轮完成 11/11 缺项路径静态闭合、110/110 键名称/路径筛查分类、另 11 个路径手工检查、2/2 新候选深查，且这些结论均有原字节窗口与重定位。旧报告和 Stage 1 证据保持保留。补全路径不等于把 110 个接口全部完成审查。

剩余 88 个自动路径以及大部分 show/store 语义、其他 HAL common setter（包括未选中方法）、运行 class/provider 绑定、独立 owner enabled=0 用户空间协议、buck/quick 协同恢复和远端 ADSP/PMIC 末端物理行为，均不能由本轮排除推出不存在。没有候选变成可安全使用的插件后端，也没有新增实机 A/B 数据。本轮增加的是两条明确排除证据及下一轮可界定的静态范围，不是物理旁路已实现的证明。
