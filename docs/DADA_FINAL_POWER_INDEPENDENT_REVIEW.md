# DADA 最终供电与受控验证独立审查

日期：2026-10-13。审查 [控制契约](DADA_ADSP_POWER_CONTRACT_FINAL.md) 与 [受控验证计划](DADA_CONTROLLED_VALIDATION_FINAL.md)。仅在电脑上解析已取得的固定 ELF／原始字节和公开源码文本，使用独立 ELF32／ELF64 parser 与 fresh LLVM；没有导入或运行作者脚本、访问网络、查询手机、写节点、加载驱动或执行目标代码。手机／ADB操作为 0。

## 1. 审查发现与修订

**[P2] 官方控制链的 payload 契约已知，不能把未知填充作为这条路径的阻塞条件。** 首审控制报告第42行把合法值、消息长度及填充统一列作仍需闭合的协议缺口。独立复核三个完整函数后确认：[raw／RELA／命令](../.tooling/bypass-check/final/power-review/raw-review.json)、[语义锚点及256字节覆盖检查](../.tooling/bypass-check/final/power-review/semantic-review.json)。

- `platform_class_buckchg_ops_set_chg .text+0xdd8` 在 `+0xe20` 执行 `w1 & 1`，通过 provider `ops+0xf0` 传入 0／1。它不是“任意非零都等于1”。
- `qcom_subpmic_enable_charging .text+0x1418` 保存一个参数 byte；`+0x1458/+0x145c` 固定 property `0x2003` 与复制长度1，再调用 GLINK。
- `mca_adsp_glink_write_prop .text+4` 的 `+0x44..+0xa0` zero stores 完整覆盖 `sp+0x18..sp+0x117` 的256字节 payload。随后按经过边界检查的长度 memcpy，固定发送 `0x114` 字节消息。受审官方路径上 payload byte 与 payload32 均为同一个 0／1。

因此，该官方路径的 0／1、复制长度及零填充已闭合；主／附带消费者的 byte／word 差异仍应保留，但不能将它作为当前方案的额外未决条件。这个结论不覆盖其他直接 caller 或裸 RPC，也不证明硬件、副作用或恢复。**该P2已修复**：最终第3节准确写明 `&1`、输入2变0、len1、256字节零填充及固定总长，删除了受审官方链的额外填充缺口。

没有发现其他会改变最终静态结论的实质偏差。以下 PASS 是材料与论证的独立审查结果；实际旁路、完整恢复和手机 A/B/A 均未执行，实施继续为 NO-GO。

## 2. 独立字节与解码复核

首审身份为 `final/power/manifest.json` SHA256 `342071be7f052b5eb055368af189305b02c86a35b1332498f3831339e04f7597`。首审 manifest 和两份报告已保存在独审目录的 `reviewed-pre-payload-*`，使 P2 修订前的内容可以复核。作者已保存更早 Linux section 修订前的 manifest／verification；独审不把后来重建的 stdout 当作当时已经留存的原文件。

| 复核对象 | 独立检查范围 | 结果 |
| --- | --- | --- |
| ADSP 来源 | ELF32 little endian、machine164、flags0x79；四个 file-backed section 逐字节对应固定包 `adsp.b19/b20/b21/b22` | PASS；不等同当前 ADSP 运行内存身份 |
| 作者 Hexagon 窗口 | 21个窗口、2,893个指令字；按唯一 file-backed section／文件偏移重抽；fresh LLVM 显式 `.text`、v79 | 原字节／保存输出全部一致 |
| 作者 Linux 窗口 | 15个完整 `.symtab` 函数与3个补充窗口、1,885个指令字；ET_REL、section index与file offset、完整 RELA及表 | PASS；没有用重叠 VA0 猜段 |
| 原始分派表 | 139个 remote地址项与127个 battery byte项；重新展开 key、base PC和目标 | PASS |
| P2与VPH补证 | 3个既有官方Linux完整函数及2个既有 VPH getter窗口；58个 ADC 名称指针从固件重读 | PASS；未扩大研究范围 |
| packet PC | 对选定2,906字的 raw parse bits检查 packet开始／结束；591个 B22 branch/call用原编码位移加实际packet PC核对目标 | PASS；只覆盖出现的编码与窗口 |

Linux fresh LLVM 均带 `--section=.text`，每个完整输出中的所有指令字都与选择的原段核对，没有过滤掉同段不符字节。Hexagon 的分支位移基址使用 packet PC；例如服务表计算 `0x6000b530` 所属packet起点是 `0x6000b52c`，不是把 instruction PC 当基址。独立输出、逐窗口命令、stderr、原字节、RELA与来源身份在 [raw-review.json](../.tooling/bypass-check/final/power-review/raw-review.json)，raw packet边界在 [packet-review.json](../.tooling/bypass-check/final/power-review/packet-review.json)。

作者曾以仅地址范围的 LLVM 命令混入相同VA的 `.init/.exit`。当前显式 section 修订已核对。18份旧stdout按相同主机命令重建后与此前记录SHA吻合；`linux-section-revision.json` 明确指出原stdout与失败stderr／trace当时没有落盘，不伪造旧日志。这限制历史失败的可追溯性，但当前独立 raw／section核对没有相同问题。

## 3. 控制与测量边界

主 `0x2003` 的 masked请求有 enable／disable两个方向。软件请求存在，不能解释成只有关闭入口。它也不能证明已保留系统输入供电。报告准确区分了本机运行时 model／slave／ops选择与固定镜像静态分支；BSS零值不作运行时证据。

五类附带操作和主返回链的结论与原字节一致：外层保存主rc，末尾仍从同一stack位置返回，未汇总附带rc；主失败也继续执行附带调用。FSW及附带字段没有配套schema或成对旧值恢复证明，固定编码4／5、位互换和 `0xa5` 前置写不能自行命名为BATFET、隔离或解锁。selector1 RMW保存read rc后仍组合并write，返回被write rc覆盖；selector2所谓read先执行多次请求写入；服务分派可走trap或运行时表并间接跳转。报告没有把这些边界升级成物理总线完成或原状态恢复。

两份固定 primary PMIC loader文本及其实际头文件的常量、哈希、限定名称查询均一致。它们只给通用chip revision读取规则，不能证明本机数值、芯片别名或charger peripheral schema，更不能把GPIO／PMIC偏移或无名字段推为电池开关。404与访问失败没有作为全网不存在的证明。

`get_vsys_volt / 0x200c` 的已审 getter经过 ADC channel0，固件名称是 `VPH_PWR`；channel2才名为 `VSYS`。当前最终报告已经修正早期仅依据 `BUCK_VSYSMIN` enum 推成设置值的解释，同时保留物理测点与runtime ADC未证的限制。

battery CURRENT_NOW完整静态分派和错误链吻合：business正常分支在下层call后强制返回0；fake分支给固定 `-500000`。策略current的固定值、缓存、双路合并与 `ctx+0x504==2` 成功但不写输出分支均实际存在；BQ读失败路径写out0而保留负rc，进入上述business后负rc被吞。它们说明某个零值可能不代表真实电池零流，不能倒推手机当前正在fake或读失败。

CHARGE_COUNTER正常分支走remaining-capacity getter，策略直接取 `ctx+0x8c0` 缓存。选定更新路径把双路rm合并并最终复制到该缓存；它不是独立硬件库仑积分的直接读取。单位乘1000只能是调查线索，实际provider／gauge修订、校准、样本年龄和容量重估仍须证明。新FG／BQ包内ELF也没有被称为已完成live匹配。

## 4. 验证门槛与恢复标准

G1运行时身份／对象、G2匹配硬件定义／拓扑、G3合法入口／完整恢复、G4有效观测、G5同步测量能力、G6有界操作条件分别有具体缺件。P2修订缩小了官方payload的缺口，但没有补上这些独立条件，当前NO-GO成立。

恢复以插件自己的控制不再影响当前合法策略为标准；固定 owner 保留inactive槽位和stored value可以接受，严格消失不是门槛。OEM动态投票允许变化，不要求回写所有旧OEM票，也不强制末A一定恢复充电；若当前SOC／温度等OEM策略暂停充电，应依记录解释。主位变回1或ACK成功均不单独构成恢复PASS。

A/B/A计划尚不可执行，但作为有条件验收方案，其起点归因、可重复负载、输入／电池双向同步证据、正负电荷分别积分、预先固定误差／容差、cache／假值／错误样本判INVALID、完整撤销与失败保留均合理。平均净电流接近0、五分钟counter不跳、online或状态文字均不足。操作性旁路只覆盖已测条件与带宽，不升格为物理隔离。

## 5. 最终身份与审查状态

最终作者manifest SHA256为 **`dd7d50816c5edafb29e99cb053fab65825775b97125dbff72d10eeae9a52ce96`**，44 inputs／174 artifacts。原始控制报告SHA256 `75401b36d681b83e21aeb46c56184061cbec218ba22ddfa93ce5ecdc93d7eafb` 修订为 `85e13a1aeef90165f767e2854c12f75d6d0cf8af7724926d155115f3992d8ea5`。修改限定在第3节协议段和对应证据／修订记录；受控验证计划、原始作者输入与原始反汇编窗口不变。

最终身份、限定变更范围与P2前后diff由 [revision-review.json](../.tooling/bypass-check/final/power-review/revision-review.json) 核对。独立审查 **PASS，1项P2已解决、0项未解决实质发现**；控制实施仍为NO-GO。独审 [verification.json](../.tooling/bypass-check/final/power-review/verification.json) 与 [manifest.json](../.tooling/bypass-check/final/power-review/manifest.json) 冻结报告、工件和当前输入；这些审计记录不授予手机控制权限。
