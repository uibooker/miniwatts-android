# Xiaomi 15 / dada 供电与充电通路：最终对比审查

> 前阶段记录：本文截至重新连接之前。2026-10-13 重连后已完成只读采集，并在磁盘 ADSP 固件中追到 `0x2003` handler、PMIC 充电使能位及附加联动；10 份加载模块 build ID 也已匹配。当前结论请读[重连后的最终审查](DADA_RECONNECT_RESEARCH_REVIEW.md)。本文的断连状态、远端 handler 未取得等表述不代表重连后结果。

日期：2026-10-13。对象：24129PN74C / dada，OS3.0.303.0.WOCCNXM，Android 16。任务是利用 root 继续定位接口，同时避免在连接手机上做探索性电源操作。

## 审查结论

**已找到独立于 FCC 限流的 buck 电池充电使能请求链，尚未建立可安全恢复、覆盖所有充电路径且保证外部电源继续供系统的旁路后端。** 这比此前仅找到 `smart_chg` 限流更进一步，但不能据此宣布旁路已实现，也不能据此判断硬件不支持。

本轮没有向候选充电/供电节点写入，没有切 CP 模式、访问裸寄存器、安装或修改模块、重启手机。已有 0.2.0 插件仍是此前的限流实现；本轮没有给它增加新控制动作。旧插件已有 watchdog 的运行行为不属于本轮新实验，断连后手机最终状态未知。

三名子代理分别负责上层控制与恢复、底层 provider/GLINK、独立电源语义核验；主代理对报告交叉对比，并从原始 ELF 重新生成关键函数反汇编，核验关键参数和失败路径。这里的“对比验证”是离线控制链与证据对比，**没有进行实机开关 A/B 验证**。

## 接口对比

| 路径 | 已确认的作用 | 对旁路目标的判断 |
| --- | --- | --- |
| 已安装插件：`smart_chg` bit10 | 进入厂商智能充电场景的 FCC 限流策略 | 过去 62 秒记录电量计增加约 17mAh，平均充电约 987mA；不是已实现的禁充保供 |
| `mca_charge_interface/charge_enable` 的 buck 请求 | 三词协议经 AND voter、buck class 到 QCOM provider，发出 `BUCK_ENABLE_CHARGING` 请求 | 首要离线候选；准确撤票、远端保供语义及 CP 覆盖仍缺失 |
| quick `charge_enable` | 独立 voter，禁用时退出快充、关闭 CP，并改变模式、PD 电压及 buck 策略 | 可触及另一条充电路径，但不是单一、已证明可准确恢复的开关 |
| OEM SOC 保护 | 跨 buck/quick/无线传播 SOC 暂停请求，有自身释放链 | 与 endurance/navigation/night 共用 OEM 状态，不能借用其 owner 接管插件；debug 入口还涉及持久化写 |
| FCC / `ichg_limit` | 调整充电电流上限；buck 的值 0 是撤销该限流票 | 不能把 0 当作强制 0mA |
| `input_suspend` / ICL / path_control | 输入电流限制、输入门控或 OTG/无线路径控制 | 可能减少或断开系统外部输入，不满足禁充保供目标 |
| CP bypass / forward 1:1 | 充电泵转换比及工作模式；驱动 bypass_support 恒返回 true | 不能证明电池隔离或停止电池充电，不试切模式 |
| battery 可写属性 / BMD / qcom_sysfs | 部分 battery setter 成功返回但不执行；BMD 是连接安全检测；所查 qcom_sysfs 无专用禁充 store | 没有形成替代的可用旁路接口 |

这里的目标是“停止电池持续充电，同时保持外部电源承担系统负载”。电池物理隔离是更强、另需证明的目标，不能由禁充或瞬时电流为零推导。

## 已闭合的首要候选链

候选节点是 `/sys/devices/platform/soc/soc:mca_charge_interface/charge_enable`，不是可直接写布尔值的通用节点。以下是调用关系说明，不是手机执行命令：

```text
user / type / value 三词解析
  -> buck strategy set_charge_enable
  -> mca_vote(chg_enable, user, enabled=1, value=bool)
  -> AND effective callback
  -> platform_class_buckchg_ops_set_chg
  -> provider ops +0xf0: qcom_subpmic_enable_charging
  -> MCA ADSP GLINK: 0x2003 / BUCK_ENABLE_CHARGING / 1 byte
  -> 远端固件 handler [尚未取得]
  -> PMIC 与 BAT/SYS 电气状态 [尚未证明]
```

provider 层的 FCC 为 `0x2005`，INPUT_SUSPEND 为 `0x2019`，与充电使能不同。`enable_charging` 局部函数没有同时发送输入暂停或 ICL，但这只能证明 Linux 局部请求的区分，不能保证远端固件不联动输入。

另外，`get_vsys_volt` 实际请求 `0x200c / BUCK_VSYSMIN`。在缺少远端定义时，不能把函数名称当作实际 VSYS 电压测量，也不能用其稳定值证明外部供电。

## 阻止接入自动控制的实际问题

1. **不能准确撤销独立请求。** buck/quick setter 把 enabled 固定为 1。写允许值只留下 active 的允许票，无法恢复插件 owner 原来不存在或 disabled 的状态；buck AND default=0，因此留下允许票不能视为无影响。所查用户空间入口未找到独立 enabled=0 协议；内核 API 存在不等于插件已有可用入口。
2. **写入成功可能是假成功。** `mca_vote` 先存 client enabled/value 和 effective 状态，再调用硬件 callback，失败不回滚。buck setter 在调用后无条件返回 0，sysfs 又忽略回调错误。软件回显和 write count 都不能证明硬件已经执行；重复相同票还可能被跳过，不能据此设计简单重试。
3. **buck 禁充没有证明覆盖 CP。** 外部 CP 有独立使能与策略。quick 停止流程会改 CP、PD 与 buck 状态，其全部电气影响和恢复条件尚未明确。不能以广播 `all` 代替完整审查。
4. **缺少本机远端保供依据。** `0x2003` handler、实际 charger/PMIC 的 BAT/SYS 拓扑及输入保持保证仍未取得。root 权限让文件可访问，不补上这些语义证据。
5. **运行版本对应仍有边界。** 磁盘 ELF vermagic 是 6.6.57 系列，运行内核是 6.6.77；绑定、模块名和文件 hash 没有证明加载内存代码逐字节相同。官方 Android V 源也不能自动等同当前 Android 16 固件。

buck 允许分支还可能撤销 `pmic_temp_term` 的 FV/input override。已验证完整 buck DT 子树没有启用该分支的属性，按所查 ELF/DT 组合预期关闭；这仍不能替代运行实现核对。OEM owner、force/debug override、裸 GLINK、寄存器写及驱动重载均未作为恢复绕路。

## 主代理最后复核

- 重新计算控制链 manifest 中 12 份原始 ELF 的 SHA-256，全部一致；provider 工件与采集 manifest 的检查亦通过。
- 使用 Xcode llvm-objdump 从原始 ELF 重新提取 8 个关键函数，核验三词解析、固定 enabled=1、buck 吞错、vote 先改缓存后回调、provider 属性号及纯内存 voter status 的实现。原始复核记录为 [final-independent-review.txt](../.tooling/bypass-check/root-acquisition/final-independent-review.txt)，散列记录为 [final-independent-review.json](../.tooling/bypass-check/root-acquisition/final-independent-review.json)。
- 设备树 raw tar 后部混入错误文本，保留原件并验证前 97 个成员构成的五棵完整子树。标准 EOF 子集与范围见 [dt-acquisition-integrity.json](../.tooling/bypass-check/root-acquisition/dt-acquisition-integrity.json)。未采 GLINK 子树不能当作节点不存在。
- 三份报告在候选定位、撤票问题、CP 独立路径、远端缺口上无实质冲突。额外发现 buck setter 自身吞错后，已反馈子代理并纳入控制链报告。
- 最后一次 `adb devices -l` 仅返回标题，没有连接设备。没有证据判断断连原因，也没有据此判断手机崩溃。追加实机只读采集尚未完成。

没有更改模块实现，因此没有重跑其无关测试套件，也没有生成声称新增旁路能力的安装包。

## 下一轮最小只读取证

设备重新连接后，先确认序列号与固件，再按已审查范围取证：

1. nested `soc/qcom,pmic_glink` 及 MCA child 的静态 DT 属性，补齐 provider 远端连接身份；补充 SC8585/HL7603 静态绑定与普通驱动文件。
2. 运行模块的普通构建元数据，用于缩小加载代码与磁盘 ELF 的版本差异。
3. 仅在 debugfs 已挂载且节点存在时，读取已反汇编确认只格式化内存的 `business-votable/*/status`：chg_enable、input_suspend、buck_input、quick_chg_en、quick_chg_disable。不挂载 debugfs，不触及 force 属性。
4. 继续对匹配当前固件的远端实现/协议和独立撤票入口做离线追踪。先审 getter，再决定哪些硬件遥测有足够用途纳入读取白名单。

补齐只读资料不自动授权实机写入。若后续出现完整候选，应先给出具体接口、原始状态、失败处理与精确恢复方法，再安排受控验证；当前尚不能给出这样的方案。

未来验证需避开“本来就在 80% 停充”的混淆：同 SOC、适配器、温度与负载比较，确认输入持续提供实际功率，并用电池电流时间序列及 charge_counter 交叉验证没有持续充电或放电。单帧 0mA、online=1、SOC 不变或 sysfs 成功均不足以验收。

## 分工报告与一手来源

- 上层协议、voter、恢复与替代路径：[DADA_CHARGE_CONTROL_CHAIN.md](DADA_CHARGE_CONTROL_CHAIN.md)。
- provider、GLINK、CP 与远端边界：[DADA_CHARGER_PROVIDER_RESEARCH.md](DADA_CHARGER_PROVIDER_RESEARCH.md)。
- 官方来源与独立电气语义核验：[DADA_POWER_PATH_EVIDENCE.md](DADA_POWER_PATH_EVIDENCE.md)。
- 旧插件实测：[SUKISU_BYPASS_POWER_TEST.md](SUKISU_BYPASS_POWER_TEST.md)。
- 小米官方设备树固定提交：[MiCode/kernel_devicetree](https://github.com/MiCode/kernel_devicetree/commit/233bd52eddce9f081f8e87802796f5994babafa5)。
- 小米官方内核 MCA 外部源码边界：[Xiaomi_Kernel_OpenSource MCA](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/afb5a488a44e7e980b2ef7b4c145703cfcb7aac5/drivers/power/supply/mca)。
- Linux 属性语义参考：[Linux v6.6 power_supply ABI](https://github.com/torvalds/linux/blob/v6.6/Documentation/ABI/testing/sysfs-class-power)。参考定义不能替代本机 provider 实现。
