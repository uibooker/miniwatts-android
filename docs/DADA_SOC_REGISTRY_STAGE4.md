# DADA Stage 4：无线 SOC 回调补齐与恢复边界

## 结论

在三个确切存在的官方模块中，补出了六槽 smart-charge 注册表的 **slot 1 普通无线**和 **slot 3 快速无线**。加上 Stage 3 已闭合的 buck slot 0、quick slot 2，目前静态闭合 4/6 槽；slot 4/5 仍未建立提供者，不能推断为空。

两个新提供者都使用原厂固定 `soc_limit` owner，没有得到独立插件请求或恢复接口。普通无线控制同时改变电池充电电流、无线输入电流、充电允许票和无线输出电压；快速无线进入充电泵及无线协议停止流程。释放票也不等于逐项恢复硬件。**本轮仍不能据此实现“暂停电池充电、保留系统输入供电、可审计地恢复”的旁路后端。**

本子任务只在电脑上读取官方镜像/ELF，运行自己的解析器和已有主机工具。手机操作数为 0：没有 ADB、手机查询、目标程序执行、驱动加载或安装。旧证据与插件未修改。

## 来源与范围

来源沿用已经验证的 [官方 OS3.0.303.0.WOCCNXM OTA](https://cdnorg.d.miui.com/OS3.0.303.0.WOCCNXM/dada-ota_full-OS3.0.303.0.WOCCNXM-user-16.0-527efd3838.zip) 中主机已有 `vendor_dlkm.img`。重新核对 image SHA-256 为 `b11940f67f673076ce5f0ac409919505d7af7495f4999f123891f426ae63042b`，大小 77938688 字节；仅按以下精确路径用已有 host `dump.erofs` 提取。原始元数据、工具哈希、来源清单行和输出身份保存在 [acquisition.json](../.tooling/bypass-check/stage4/registry/acquisition.json)。

| 模块 | 原清单行 / NID | ELF 大小 / 压缩 on-disk 大小 | SHA-256 |
| --- | --- | --- | --- |
| `mca_basic_wireless.ko` | 137 / 2383732 | 199088 / 77824 | `47eef298cd8dfabe4b5d51077c1b4f0bc8c8fdf3f66fc87c7f34faad617ced2e` |
| `mca_quick_wireless.ko` | 170 / 2383973 | 116088 / 49152 | `1886a2c3f2abf3254b7400568e8bbfa884b9f54f41265bbee03e9d716dfb976e` |
| `mca_wireless_revchg.ko` | 180 / 2383758 | 113824 / 40960 | `f7786adc533fecef4e16f69a867c4bca9dfae407606f36c102076415ad310c3b` |

三文件都为 EROFS Layout 3 压缩文件。NID 和 ELF 文件内 offset 不等于镜像物理映射；未捏造单一 image byte offset。没有取得这三个模块的 live build-id，没有证明它们当前已加载或当前注册表内容。

本轮只检索这三文件中精确 `mca_smartchg_if_ops_register` 符号、RELA 和 NUL 结束字符串。basic/quick 各有一个 undefined symbol 和一个 `.text` CALL26；revchg 三项均无匹配。这个结果仅限本包这三文件，不能排除其他名字、间接注册或其他模块。详细冻结在 [routing.json](../.tooling/bypass-check/stage4/registry/routing.json)。没有扩大第四个新模块。

下文均使用 **指定 ELF section 内 offset**，不以 ET_REL 的重叠 VA 选择节。各窗口保留节号、section file offset、原始字节、SHA-256 和 RELA，并以 fresh LLVM 每个 opcode 对照原始 little-endian word。函数边界来自本模块 `.symtab` 的真实 value/size；局部窗口不冒充完整函数。

## 注册与上下文

沿用 [Stage 3 六槽实现](DADA_RETAIL_POLICY_STAGE3.md)：注册函数要求 type<6，保存 ops 指针到 `.bss + type*8`；SOC 分发读取 ops+8 context，调用 ops+0x28 callback，忽略 callback 返回值。旧 buck/quick 两条完整链没有重做。

| 新提供者 | 注册调用与上下文 | ops 数据 / SOC callback |
| --- | --- | --- |
| basic 无线 | probe `.text+0x1a04..0x1a10` 加载 `.data+0x168`，`+0x1a0c` 把 x19 写入 ops+8，`+0x1a10` 调精确 register | `.data+0x168` 首 u32=1；`.data+0x190` ABS64 RELA 指向 `.text+0x9ba4`，`strategy_wireless_soc_limit_sts_callback` |
| quick 无线 | probe `.text+0x824..0x830` 加载同模块 `.data+0x168`，`+0x82c` 写 x19 到 ops+8，`+0x830` 调 register | 首 u32=3；`.data+0x190` ABS64 RELA 指向 `.text+0x55f0`，`mca_wireless_quick_charge_smartchg_soc_limit_callback` |

注册返回值在所查调用点没有被检查。这里证明包内类型和调用，不证明实际 probe 成功、slot 未被覆盖或运行对象生命周期可靠。冻结见 [code 窗口](../.tooling/bypass-check/stage4/registry/windows.json) 和 [data 窗口](../.tooling/bypass-check/stage4/registry/data-windows.json)。

## slot 1：阶梯停充，输入也发生变化

SOC callback `.text+0x9ba4..0x9c20` 先把请求状态存到 ctx+0x1054。只有请求非零且 ctx+0x104c 非零才原样调用 process(true)；否则先取消 ctx+0xc90 delayed work，将本次 process 参数改为 false。ctx+0x104c 的当前状态及运行事件条件没有在本子任务确认，不能把入口调用成功解释为立即停充。

`strategy_wireless_process_soc_limit_change` `.text+0x867c..0x8850` 使用模块静态 `.bss+0x14` 的 step byte。true 每次递增，饱和到 10；false 每次递减到 0。非末端步骤以 1000 ticks/jiffies 重新排队 ctx+0xc90 worker，未证明对应毫秒。probe 的 `.text+0x1494..0x14c8` 将 worker 指向 `.text+0x5344`；worker 用 work+0x3c4 读取 ctx+0x1054，再次调用同一 process。不能以 bool false 的一次返回视作所有电流约束已同步撤销。

**条件门控也不构成持续解除。** 当 request=1、ctx+0x104c=0 时，`0x9bc0` 已把 1 保留在 ctx+0x1054，`0x9bf4` 仅把当次局部 w20 改为 0。常规 step 为 2..10 时，这次 process(false) 会降到 1..9，并在 `0x87e4` 重新排队。若排队成功、worker 得以运行，且期间没有其他事件改写缓存或取消任务，`0x5350` 仍从 work+0x3c4（ctx+0x1054）读到 1；该完整 stepper 不复查 ctx+0x104c，会改为 process(true) 再升阶。例如 2→1→2；原 step10 可经历 10→9→10，重新申请 `chg_enable` 和 force-vout 票。这个条件化路径不是实机发生证明，但足以排除“gate为0会保持降阶/释放”的承诺。原始完整窗口及逐地址推导见 [gated-request trace](../.tooling/bypass-check/stage4/registry/basic-gated-request-trace.json)。

固定 owner literal 在 `.rodata+0x3254` 为 `soc_limit`。两个电流列的原始表值为：

| step | `buck_charge_curr` 请求值 | `wireless_buck_input` 请求值 |
| --- | --- | --- |
| 1 / 2 / 3 | 1990 / 1400 / 1100 | 850 / 750 / 650 |
| 4 / 5 / 6 | 900 / 700 / 500 | 550 / 450 / 350 |
| 7 / 8 / 9 | 300 / 200 / 100 | 350 / 350 / 250 |
| 10 | 0 | 250 |

表从 `.rodata+0x4a88..0x4ae0` 原始字节逐项解析。表 row0 为 0/0，但 step0 分支撤销票，没有把 row0 写入。以上是传给 voter 的整数，不是测得的物理电流。

实际 voter 绑定如下：

| ctx 字段 | 绑定证据 | SOC 行为与下游范围 |
| --- | --- | --- |
| +0x78 | find-work `.text+0x364c..0x379c` 从 `buck_charge_curr` 查找；work base 为 ctx+0x6b8，减 0x640 正好为 ctx+0x78 | step1..10 enabled=1，采用表首列；step0 enabled=0 |
| +0x70 | probe `.text+0x1620..0x1648` 创建 `wireless_buck_input`，type0/default1100，callback `.text+0x8a64` | step1..10 enabled=1，采用表次列；step0 enabled=0；callback 调 role0 `platform_class_buckchg_ops_set_wls_input_curr_lmt` |
| +0x80 | find-work 减 0x638，查找 `chg_enable` | step10 enabled=1/value=0；step1..9 enabled=0/value=0 |
| +0x108 | probe `.text+0x1970..0x1998` 创建 `wireless_force_vout_6v`，type2/default0，callback `.text+0x91fc` | step10 enabled=1/value=1；step1..9 enabled=0/value=0 |

通用 voter type0=MIN、type2=OR，以及既有 `chg_enable` 的 AND/provider 语义沿用 [此前驱动控制链](DADA_CHARGE_CONTROL_CHAIN.md)，没有为本轮增加新模块。这里的 `wireless_buck_input` 直接进入无线输入电流 helper；因此不能声称该策略仅改变电池充电。所查 SOC process 未直接对 ctx+0x100 的 `input_suspend` 投票，也不能据此保证系统供电保持。

**明确的恢复边界：**force-vout callback `.text+0x91fc..0x9268` 非零时请求 `platform_class_wireless_set_vout(0,6000)`；零值只清 ctx+0x1064 并返回 0，没有在该 callback 请求先前 vout。当 cached request保持为0且逐步worker正常运行时，false 从 step10 到9会先撤 `chg_enable`/force-vout 票，同时把两项电流换成 step9值，后续逐步到0再撤电流票。gate为0但cached request仍为1不满足这个条件。其他 worker 可能重算电压，但本链没有保存原始电压或保证何时恢复。step0分支本身只撤两项电流票；常规 10→9→…→0 序列先在9撤其余两票，不能泛化为任意异常 step 都会一次清完四票。

SOC callback 在非空 context 下无条件返回0，丢弃 process/vote/provider 的错误。输入 callback 和 force-vout callback的部分 helper状态虽可返回到 voter，外层请求不传播；原有 `mca_vote` 先改软件票/有效缓存再调用硬件、失败不回滚和重复票可能不重试的限制仍适用，见 [恢复研究](DADA_NEXT_RESTORE_RESEARCH.md)。静态 step 先改变，失败后也没有本链可见的事务恢复。

basic remove `.text+0x1a88..0x1ac4` 只移除 sysfs link group并返回0；没有在该函数注销 smart ops、取消 SOC worker或调用 process(false)。本轮不把卸载/解绑当释放保证，也不推断正常设备必然会解绑。

## slot 3：快速无线停充与中性释放

SOC callback `.text+0x55f0..0x5668` 把非零状态缓存成 ctx+0x3d9 bool，然后对 ctx+0x20 使用固定 `.rodata+0x20b7` 的 `soc_limit`：**enabled 永远为1，value为传入status**。ctx+0x20 是 probe `.text+0x6ac..0x6d8` 创建的 `wls_quick_chg_disable`，type2/default0，callback `.text+0x298c`。false 的0值是 OR 中性票，仍是激活 owner，不是删除/停用；非空context下外层无条件返回0，吞掉 vote结果。

disable callback `.text+0x298c..0x2a04` 先缓存有效结果到 ctx+0x3c8。只有该结果非零且 ctx+0x2b0==1时，才取消 ctx+0x38 work并调用 `mca_wireless_quick_charge_stop_charging`。零值路径直接返回0，**没有立即重启 CP 或排队 quick work**；其他原厂事件可能重启，但没有本 callback 配对的恢复承诺。

完整 stop `.text+0x2bf8..0x2ef4` 及其 bridge helper `.text+0x2ef4..0x3170` 冻结后可证明它是多项流程：

- CP mode bit2未置位时，请求 role0/1 `set_charging_enable(false)`；之后还请求 parallel-charge=0、CP mode=1（role1有条件）、关闭两路 ADC，并改软件状态。mode1 的物理含义没有靠名字推断。
- 无线 present条件分支内调用 bridge(false)，另有带条件的 `wireless_set_vout(0,11000)`；bridge仅在 HW首u32==5等条件下工作，可能请求 vout6000、输入 `qc_sw_bridge`=300票、轮询输出电压/电流并切桥，再撤该输入票。这不是被证明的持续独立 SYS 供电。
- ctx+8 来自 `wireless_buck_input`，可能用 `thermal_phone` owner阶梯调整输入；同时撤 `wireless_qc` 票。ctx+0x10来自 `chg_enable`，条件下以 `quickchg` owner先投0，等待后投1；这也不是 owner撤销。涉及其他固定原厂owner，无法作为插件事务快照。
- 本链调用多个硬件 helper 后未以完整硬件结果形成成功/恢复返回值；SOC外层和分发层又吞掉状态。不能用止充请求返回0证明所有附加动作已完成或全部复原。

quick remove `.text+0x8dc..0x8e4` 仅返回0；该函数没有注销 ops或恢复票。停止快速无线和保持外部输入两件事，仍需下游 provider/协议和物理证据分别确认。

## 复核产物与剩余缺口

新证据全部在 [stage4/registry](../.tooling/bypass-check/stage4/registry/)：`elf-metadata.json` 保存三个 ELF 完整节/符号/RELA；`windows.json` 共 **18个 code窗口、1054个 instruction words**；`data-windows.json` 共 **17个 data窗口**。`verify_evidence.py` 使用独立节头/符号/RELA解析，重新按精确路径提取三文件对照字节，重新运行主机LLVM，逐word对照所有code字节，并检查注册slot、callback、关键参数、所有manifest身份和报告链接；结果保存在 `verification.json`，冻结范围在 `manifest.json`。

独立审查指出并已修订上述 gated-request 恢复边界。首次冻结 manifest 原字节另存 [manifest-initial.json](../.tooling/bypass-check/stage4/registry/manifest-initial.json)，SHA-256 为 `d09d3811f00d72fddd9806d32b11a36d001e834d2ab9ff5ac0beec4745a24c77`；它仅作历史引用，修订后的 manifest 将其排除于当前 artifacts/inputs 并单独记录身份。此次修订只补明确的条件化语义和 trace，未新增代码窗口或手机动作。当前验收应使用修订后的 `manifest.json` 与 `verification.json`。

剩余 slot4/5 未闭环；没有第四模块的新扫描。即使六槽全部补齐，也只是原厂 SOC 分发覆盖证据。本轮没有新用户空间 owner/create/query/release入口，没有安全生命周期/ABI、原始票状态无副作用查询、错误恢复、输入电池分离和当前运行身份证明，也没有实机物理对比结果。两个新回调不能升级为插件可用后端，手机读取白名单也不因此扩大。
