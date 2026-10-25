# DADA 旁路供电受控验证：最终准入与验收方案

日期：2026-10-13。本报告仅分析电脑上的固定证据，手机/ADB/控制/安装/电气采样均为 0。计划中的 A/B/A 属于满足全部条件后的未来验证，尚未执行。当前所有控制试验为 **NO-GO**；本文不包含试写命令、裸 RPC 或猜测的撤销值。[控制契约](DADA_ADSP_POWER_CONTRACT_FINAL.md)。

## 1. 先确定能够声明的结果

| 级别 | 可声明的结果 | 最少需要的证据 |
| --- | --- | --- |
| 软件观察 | 某 voter、状态位、配置或 getter 显示某值 | 完整入口/返回/字段语义与相应运行时身份；不等同电气效果 |
| 操作性旁路 | 在声明的电源、SOC、温度与负载范围内，外部输入持续供设备，电池充入与放出电荷均小于预先声明的容差，并能完整恢复 | 输入功率与有效双向电池电流同步记录；负载对比；误差界；完整控制与恢复状态 |
| 物理隔离 | 电池与 SYS 的指定电气通路/FET 被隔离 | 匹配拓扑、器件/外设修订 schema 与有效硬件状态或合格物理测量；仅平均电流接近 0 不足 |

“操作性旁路”只覆盖实际测得条件，不能推成所有负载、所有电源或“电池物理断开”。停充但电池持续放电也不满足该级别；充入和放出交替抵消造成平均为 0，同样不满足。

## 2. 测量入口准入矩阵

以下“未准入”是当前证据判断，不声称手机当前发生读取故障。所有额外 phone getter 都要单独闭合并列入明确白名单，本报告不新增读取白名单。

| 候选量/入口 | 已证明的 provider 或动作 | 会混淆结果的边界 | 当前资格 |
| --- | --- | --- | --- |
| USB `online` / `qcom_subpmic_get_online` | Linux proxy 复制缓存 `ctx+0x2f0` | 在场/缓存不证明输入已供 SYS | 仅辅助；不能判旁路 |
| `charge_interface/charge_enable` | strategy 有效 client/result 两次分别锁定查询 | 软件投票；非硬件 enable；两次非原子；缺行/错误不能当关闭 | 仅辅助 |
| ADSP `0x2003` getter | 读取主 enable bit 的实际 ops `+0x34` | 不读回所有附带字段，不证明拓扑/电流/完整恢复 | 未准入新增读取；即使有效也仅状态辅助 |
| USB `CURRENT_NOW` | business→buck class→proxy `0x20004`→ADC category 6 | caller 输出先清 0，proxy 失败后 business 强制 rc 0；名称无匹配也可能成功而未填 channel；runtime ADC/测点/副作用未知 | 不能用于零值或输入功率验收 |
| `get_vsys_volt` / `0x200c` | 实际 getter 调 ADC channel 0 `VPH_PWR` | `VSYS` 是另一个 channel 2；测点、单位、runtime DAL 和采样动作未知；名字不是物理 SYS 保证 | 未准入 |
| PACK_IBAT / `0x2027` | PMQBG 16-bit 读取、校准、单/双路合并；请求 `0x4fa4` 或 `0x4fc4/0x4fc6` | 软件 flag 能屏蔽贡献；实际 type/slave、校准/符号/年龄/read-clear/latch 未闭合 | 不能以 0 验收；未准入新增读取 |
| battery `CURRENT_NOW` | business→策略 FG class→选定策略/provider；详见第 3 节 | 可吞 rc，固定值/缓存/无输出；BQ 错误可产生 out 0，外层仍成功；新增 FG/BQ 未 live 匹配 | 不能独立以 0 验收 |
| battery `CHARGE_COUNTER` | 正常分支 `strategy_class_fg_get_rm`→策略剩余容量缓存 | 不是此分支直接读独立硬件库仑累计；更新年龄、容量重估、双路与错误边界未知 | 条件性辅助，不独立作电荷积分真值 |
| battery `STATUS` / ADSP `0x2001` | 前者合成输入/SOC/charging_done；后者返回 charger 状态枚举 | “Not charging”“Pause”“Disabled”不证明 SYS 输入或电池零流 | 仅辅助 |
| 已校准外置 USB 功率/能量表 | USB 输入侧 V/I/能量，若量程/协议/频率/测点合格 | 有输入仍可能同时充电池；表/线缆损耗和动态误差；不测内部 SYS | 条件性输入主证据，需要电池主证据配对 |
| 独立合格电池电流测量 | 指定电池支路的双向电流/电荷 | 必须匹配测点/多支路拓扑、带宽、误差、同步；不能以拔电池替代测量 | 条件性电池主证据；当前没有此记录 |

USB、ADC、PACK 完整已有证据见 [第一阶段测量](DADA_POWER_MEASUREMENT_STAGE1.md) 与 [后续遥测](DADA_NEXT_TELEMETRY_RESEARCH.md)。本轮未把 remote getter 名称当作纯读保证：某 selector 的底层取 byte 还先发请求写入。[控制下游边界](DADA_ADSP_POWER_CONTRACT_FINAL.md)。

## 3. 本轮补齐的 battery CURRENT_NOW 与 CHARGE_COUNTER

### 3.1 business 分派、错误与 fake 状态

固定官方包 `mca_business_battery_comp.ko` SHA-256 `e583456be088ff535cdbeab6f5ce3d47df59c4b694a432a71c1fcf0ec5b3691d`。`_batt_psy_desc` 的 raw RELA 将 get_property `+0x30` 绑定 `batt_psy_get_prop .text+0x378`；`batt_psy_props` 实际包含对应属性数值。用固定 MiCode `power_supply.h` 枚举作参照，独立展开 `.rodata` byte dispatch table：[原始字节与全部分派](../.tooling/bypass-check/final/power/battery-dispatch.json)、[描述符/RELA](../.tooling/bypass-check/final/power/evidence-index.json)。该公开枚举是参照，并非当前运行 kernel 编译 ABI 的独立证明。

| 路径 | CURRENT_NOW=17 | CHARGE_COUNTER=28 |
| --- | --- | --- |
| 正常 `.rodata+0x625`，基址 PC `0x46c` | byte `0x5d`→`0x5e0`；`0x5e4` 调 `strategy_class_fg_ops_get_current`；`0x5e8` 强制 rc 0 | byte `0x67`→`0x608`；`0x60c` 调 `strategy_class_fg_get_rm`；经 `0x678/0x67c` 检查负 rc，负值转 `-61` |
| fake `.rodata+0x5f0`，基址 PC `0x3f0` | byte `0x48`→`0x510`；写固定 `-500000`，返回 0 | byte `0x3f`→`0x4ec`；记录 unsupported，返回 0，没有写 caller 输出 |

函数在 `0x3c0` 读取 `ctx+0x90`，非零走 fake 表；此前 BTF `batt_psy_info.fake_power` bit offset 1152/byte `0x90` 与之对应。没有观察当前 flag，不能说本机正在 fake。正常 current 分支吞下层 rc 这一事实，也不能反向把历史某个 0 判为失败。[完整 business 函数](../.tooling/bypass-check/final/power/linux/mca_business_battery_comp-batt_psy_get_prop.txt)、[BTF](../.tooling/bypass-check/final/power/battery-btf-selected.json)。

### 3.2 策略 FG 与 platform FG 的必要链路

完整包新增来源如下，raw RELA、函数窗口与 NID/镜像来源都已保存；这四个新增 KO 不具备本轮 live-note 对照：[来源](../.tooling/bypass-check/final/power/package-provenance.json)。

| KO | SHA-256 | 选定接口 |
| --- | --- | --- |
| `mca_strategy_fg_class` | `97463684472a5ae82d0f56ffc1f18eaa25a93b9edf90a972e9a0b6291ce2937d` | current ops `+0x38`；rm ops `+0x50` |
| `mca_strategy_fg_comp` | `ea7814275655d963e40be17cfed0f020d4baccdad7e7eeb710a16549ee69efd8` | `.data+0xf8` 上述 RELA 分别指向 `0x5988/0x5b3c`；probe `0xe9c` 注册 |
| `mca_platform_fg_ic_ops` | `21879795940f442c5806a2554a7c25846b3c74d4b0fb48ed5174af4e4d9b6707` | id 0/1 从 `g_fg_ic_ops` 调 current `+0x20`、rm `+0x58`；缺对象/槽返回 `-95` |
| `bq27z561` | `500e179b1daf727befda65b059e84bc006b6eaf55d6c7eeec7111324d119e276` | `.data+0x82e8` 对应 RELA 指向 `fg_read_one_current 0xde48` / `fg_read_one_rm 0xdf2c`；probe `0x9f58` 注册 |

`strategy_fg_ops_get_curr 0x5988` 的条件必须保留：ctx 缺失或 `ctx+0x830` 为 0 时返回固定 `-500000`；`ctx+0xa04==1000` 时返回 `ctx+0x898` 缓存；否则按 `ctx+0x504` 选择 id 0、id 0/1 合并、值 2 时成功但不写输出，或错误。双路只有两路 rc OR 为 0 才相加。这些字段不凭偏移猜名称，也不据包中分支声称当前运行时故障。[current 策略](../.tooling/bypass-check/final/power/linux/mca_strategy_fg_comp-strategy_fg_ops_get_curr.txt)。

`strategy_fg_ops_get_rm 0x5b3c` 在初始化条件满足时直接返回 `ctx+0x8c0` 缓存，getter 本身不采样。在 `fg_update_status` 的选定双路分支 `0x496c/0x497c` 分别取 rm；成功时 `0x49a4` 把和保存 `+0x9a8`，而后 `0x4c78` 复制到 `+0x8c0`。更新读取失败时不能据缓存数字认为是新鲜测量；这里只闭合本次必要缓存路径，不声称穷尽所有动态写入/更新算法。[完整更新函数](../.tooling/bypass-check/final/power/linux/strategy-fg-cache-update.txt)、[rm getter](../.tooling/bypass-check/final/power/linux/mca_strategy_fg_comp-strategy_fg_ops_get_rm.txt)。

### 3.3 BQ 候选 provider 的边界

文件表 current/rm 槽分别调用 `fg_read_current 0x1528` / `fg_read_rm 0x1d54`。前者使用对象 byte `+0xcc` 的 command，后者用 `+0xd4`，经 `__fg_read_word 0x72d8` 到 `i2c_smbus_read_word_data`；helper 负错误时最多五次读请求。当前对象/command/注册没有 live 证明，不能把这些 byte 值猜成固定寄存器。[current](../.tooling/bypass-check/final/power/linux/bq27z561-fg_read_current.txt)、[rm](../.tooling/bypass-check/final/power/linux/bq27z561-fg_read_rm.txt)、[SMBus helper](../.tooling/bypass-check/final/power/linux/bq27z561-__fg_read_word.txt)。

current 分支还存在：对象 `+0x18a` 非零给固定 `-500000`；对象 `+0x24c` 改变读取/旧缓存回退；正常字节组合、方向变换与乘 1000 后存 `+0x138`；读失败则输出 0 并返回负 rc。这个负 rc 到 business current 分支会被改成 0，因此存在“下层失败、外层成功显示 0”的完整静态可返回路径。它不是本次手机故障记录。

rm 有 `0xff` command、对象 flag、数值范围和旧 `+0x13c` 缓存回退等边界。乘 1000 与属性 ABI 提供单位调查线索，不能替代匹配 gauge 型号/修订、测点、采样年龄、校准与容量算法。尤其 `CHARGE_COUNTER` 标签不能把剩余容量缓存升格为独立硬件积分量。

## 4. 所有门槛必须在首个控制动作前满足

| 门槛 | 必须有的具体记录 | 当前判定 |
| --- | --- | --- |
| G1 身份与对象 | 同一设备/固件/内核会话；当前 ADSP 身份与配置；控制和测量模块 live 身份；实际 model/type/revision、id/slave/channel/provider 注册 | 不满足；stage4 只补足部分 Linux 身份 |
| G2 硬件定义/拓扑 | 本机匹配 chip 与 peripheral schema/勘误；全部五类副作用；USB/buck/CP/SYS/VPH/BAT 走线及保护/补电关系 | 不满足 |
| G3 入口与完整恢复 | 审计过的合法业务入口；消息 ABI、错误/竞争规则；插件控制归属与副作用状态；成功与部分失败的撤销顺序、owner 功能 inactive 及当前 OEM 合法状态 | 不满足；`0x2003` 0/1 与主 getter 不构成完整恢复 |
| G4 观测可用性 | 每一拟读项闭合 show→provider→实际 backend；读取/转换副作用；有效/错误/flag/cache 标识；测点、单位、符号、更新年龄、频率上限、单/双路 | 不满足；第 2/3 节入口不能保证零值真实 |
| G5 测量能力 | 已校准输入与电池主证据；时间同步、带宽、误差/量化/掉样模型；能检出预先声明的容差 | 不满足；没有合格同步原始数据 |
| G6 有界操作脚本 | 仅已批准入口/读取清单、次数和时长；不写未知字段；完整日志；停止条件与已证明恢复路径；物理测量如需要则具备器材/资料 | 未启动 |

任意一项不满足即 NO-GO，不通过试写补 G1/G2，也不以“成功返回”补 G3/G4。代码审计完成、材料齐全、门槛逐项签署才算进入未来测量；当前没有可批准的控制命令。

恢复采用业务功能标准：插件撤销自己拥有的控制，固定 owner 不再影响有效投票；允许 registry 中保留 inactive 记录和旧 stored value，不要求名称严格消失。其他 OEM owner 的动态投票须保留，原始快照只用于归因，不删除、覆盖或强行回写它们。硬件附带状态须按已审计归属、竞争与当前 OEM 合法策略恢复；若无法区分控制归属，G3 不能通过。充电是否重新开始以当前 OEM 策略/SOC/温度条件为准，不通过强制充电制造恢复 PASS。

## 5. 满足门槛后的完整一次性验证顺序

1. 冻结设备/会话、控制版本、合法启用/撤销规则、遥测白名单、插件控制归属与观察到的原始投票。记录电源/线缆/仪表校准、SOC/温度/负载与安全边界。起点须离开自然满充/终止/温控/SOC 上限的停充区域，否则停充无法归因；随后 OEM 动态变化保留并记录。
2. 在原厂 A 状态做观测校准。验证电池充入符号、单位与错误标记，测出更新间隔/样本年龄、分辨率和缓存条件；输入与电池量对齐同一时间轴。需要方向对照时只能用已明确准入的日常供电状态，不增加未知硬件控制。校准不合格即结束。
3. 按原厂规格选择空闲与一个可重复的普通较高负载；保持充电器、线缆、屏幕、网络、后台活动和工作负载相同，记录不可避免漂移。负载上限来自设备/仪表与温度限制，不通过压力测试寻找崩溃点。
4. 每种负载至少三组 A→B→A：A 为保存的原厂状态，B 为已审计目标状态，末次 A 为完整撤销。每次转换只执行已审计入口一次，等待已定义稳定条件；瞬态原始数据保留，稳态判定另计。每段长度由第 6 节误差/量化约束决定，不能默认五分钟足够。
5. 转换后同时核对全部受影响主/附带状态及有效输入功率、电池双向电流；以负载增加时输入相应承担需求、且电池充入/放出都在容差内作为电气证据。只有 USB online 或状态文字变化，记录为证据不足。
6. 每次末 A 核对插件 owner 功能 inactive、本次拥有的主/附带控制已依 G3 撤销、其他 OEM 投票未被篡改；在当前 OEM 允许充电的条件下检查充电行为，若策略变化则按记录解释，不能强行充电或回写旧 OEM 票。记录相同负载恢复对比。终端关闭、策略竞争、掉线等情形只执行已审计恢复，不在首轮扩展成故障注入；恢复规则不能覆盖这些情况则 G3 不应通过。
7. 用原始有效数据计算每段能量/电荷与误差，逐组给出 PASS/FAIL/INVALID；保留失败、掉样和瞬态。结束时确认插件不再影响有效控制，当前供电/充电状态遵循 OEM 合法策略。A/B/A 未完整结束不能宣称可恢复旁路。

此顺序是完整计划，不能绕过当前 NO-GO。没有 G3 的真实撤销入口与规则，就不存在可执行的第 4 步。

## 6. 定量判据、时长与无效数据

统一把电池电流定义为“充入为正”，原始值和原始符号另存；这是分析约定，必须由 G4/G5 实际校准建立映射。以 monotonic 记录时间，raw wall-clock 另存。输入 `P_in=V_in×I_in` 与能量积分仅在相同测点、对齐时间及有效样本上计算；USB 与内部 SYS 测点不可混用。

每个 B 稳态窗分别计算 `Q_net=∫I_bat dt`、`Q_charge=∫max(I_bat,0)dt` 和 `Q_discharge=∫max(-I_bat,0)dt`，报告带宽限制、量化和误差造成的上下界。净积分小但正/负分量很大，判不满足。零附近噪声要纳入积分上下界，不能把不利样本裁成 0。观测不到的快速双向脉冲只允许声明相应带宽下的结果。

容差 `I_tol/Q_tol` 及输入能量/漂移门槛必须在看 B 结果前写定，由目标需求和校准能力共同决定。仪表/传感器误差大于目标容差时判 INVALID，不能放宽到刚好通过。用置信区间时按真实更新周期/自相关分块；重复读同一缓存不增加有效样本数，95% 区间也不能解决系统偏差。

`charge_counter` 只作辅助一致性检查。即使另有已证明为硬件积分的量，若分辨率为 `q`、目标平均电流为 `I_tol`，时长至少受 `T>=q/I_tol` 的数量级约束，端点双重误差、更新年龄和容量重估会进一步提高要求。举例：`q=1 mAh`，`I_tol=10 mA` 时一格就需要约 6 分钟；“五分钟没跳一格”不能证明 10 mA 内。当前缓存 counter 的重估/年龄误差未定，延长时长也不能自行使其成为独立硬件积分真值。

除净积分外，对输入能量做不等式检查：扣除仪表误差后输入能量须确实为正，并能解释相同设备负载变化；电池允许贡献上界必须小于预声明份额。无需假设转换器效率为 100%，也不能只用 `P_in≈P_bat` 推断内部 SYS 功率；匹配拓扑/测点与完整电荷记录仍是必要条件。

以下任一情况会使相关窗口 INVALID：缺失/超时/空输出、未确认协议响应、单位/方向不明、fake/固定值/缓存状态不明、读取失败被吞、超龄样本、timestamp 缺失、双路数据不同步、当前实际 provider 未匹配、仪表量程不足或不能分辨目标容差。不可把 INVALID 记为 0、carry-forward 成功样本或从结果中删去；无法保持可用同步窗则停止该组并按已审计规则恢复。

## 7. 停止、恢复与结果判定

出现超出匹配原厂/器材限制的温度、电压、电流，输入掉线/功率异常、异常重启、关键样本失效、未知策略竞争、未预期状态位，立即停止目标验证并只执行 G3 已证明的撤销路径。禁止以切换更多未知位、写另一个值、重启或拔电池当作默认恢复。若连安全恢复都没有定义，试验原本就不具备准入条件。

PASS 必须同时满足：全部有效输入/电池证据、每组两个方向电荷与瞬态在容差内、重复负载响应可解释、插件所有拥有的副作用已依合法规则撤销且 owner 功能 inactive、其他 OEM 动态投票被保留、末 A 行为符合当前 OEM 条件。FAIL 是有效测量明确超容差、目标未达或恢复不完整；INVALID 是资料/测量资格不足。无效数据与未恢复不会因为主位变回 1 被改成 PASS。

历史 FCC 样本约 62 秒、13 条记录，`charge_counter` 增加 17000 µAh 与 current 约 -1 A 一致，说明早期方案仍有充入；这些是历史观察，不能重标为本轮测量，也不能依据 Linux 通用充入为正规则改写其 vendor 符号。[历史记录](SUKISU_BYPASS_POWER_TEST.md)。

本轮已完成控制条件与必要测量入口收尾。尚缺第 4 节的 runtime/schema/topology/restore/measurement 材料，结论为 NO-GO，没有执行 A/B/A 或输出可试写接口。全部新 evidence 与报告由 [manifest](../.tooling/bypass-check/final/power/manifest.json) 冻结，[verification](../.tooling/bypass-check/final/power/verification.json) 给出作者侧离线复核；该校验不取代监工独审和电气验收。
