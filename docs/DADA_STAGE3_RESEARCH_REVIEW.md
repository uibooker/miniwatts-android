# dada 第三轮推进与实施决策

日期：2026-10-13。延续[第二轮主审](DADA_STAGE2_RESEARCH_REVIEW.md)留下的具体候选，三个子代理分别完成 HAL 方法覆盖、零售策略链和新增公开源码入口筛选；已完成的源码代理转做 HAL 与零售证据的独立审查。全部工作在电脑上进行。

**本轮补齐了官方 MiCharge 的 56 个方法和 15 个 setter 的高层覆盖，并定位零售模式的 80%/75% 控制链；仍未取得可用于插件的完整控制、查询和恢复契约。尤其不能将 `mishow` 用作新的旁路后端：它涉及持久存储，读取也可能写盘，写回 0 的已分析路径不能保证撤销原有 SOC 限制。** 现有插件仍提供 FCC 限流，物理旁路未验证。

## 新入口的实际结果

| 本轮候选 | 已取得的实现证据 | 实施判断 |
| --- | --- | --- |
| NightCharging，tx19/44 | 固定读写 `/sys/class/xm_power/charger/smart_charge/smart_night`；setter 原样传 String，写结果与 Binder 状态分开 | HAL 路由闭合；下游的请求归属、独立释放和供电方向未在本轮证明 |
| TypeCCommon，tx51/52 | 完整集合为 7 个 getter key、8 个 setter key，处理 USB HID 手柄状态及固件 | 没有插件充电控制契约；5 个硬件 getter 先发送命令，不能作为无副作用读取入口 |
| BatteryCommon，tx55/56 | 完整函数只记日志并返回 Binder OK；不处理 key/value，也不填业务输出 | 空实现；返回空 String / 预置 int 0 不表示硬件动作 |
| `charger_partition_mishow`，表 index29 | 持久 signed int 的缓存转 bool；在特定启动模式、硬件和策略优先级条件下设共享 SOC 阈值 80；对应 SOC worker 使用 80%/75% 回差 | 原生 SOC 限制策略，有恢复与读取副作用缺口；没有形成插件可用的物理旁路后端 |
| `charger_partition_poweroffmode`，表 index28 | 与 mishow 同一持久记录中的另一个 int；初始化路径还会修改相关字段 | 本次确认的实现没有提供电池专用停充或插件请求释放 |

HAL 依据见[完整覆盖报告](DADA_HAL_COVERAGE_STAGE3.md)，零售策略依据见[控制链报告](DADA_RETAIL_POLICY_STAGE3.md)。56 个方法对应 55 个独立函数，tx15/41 共享实现；函数范围使用 `.eh_frame` 的 FDE，不能按下一个方法地址裁切。tx40/50 虽然各只有 64 字节，仍真实调用文件 writer；方法短不等于空实现。

该覆盖是 HAL 函数与调用覆盖。110 个表键的下游实现没有因此全部审完，第二轮留下的 88 个路径仍未逐项补审。NightCharging 只闭合到固定文件，不能据此宣布它的全部下游行为已被排除；整个固件或硬件是否还有其他旁路入口也没有被排除。

## mishow 的三个关键问题

**sysfs 查询可能写持久存储。** 本轮从已验证的官方 `vendor_dlkm.img` 精确提取 `charger_partition.ko`，记录大小、hash 和 EROFS 文件元数据。mishow 与 poweroffmode 的 sysfs show 都调用 `get_info_1`，进入 alloc/read/dealloc；alloc 在 header `+0xc` 的 word 非零时清零并调用 SCSI 分区写入，dealloc 再将其设为 1 并写入。这个 word 的 schema 尚未取得，不能给它臆定锁或借用语义。另一个内部 `charger_partition_get_mishow` 只读取缓存并转 bool，不能将它与 sysfs show 混为一谈。[R]

**写回 0 不能保证退出此前限制。** store 刷新 info1 后修改字段并写整个 32 字节记录，不通知 smart worker，也不直接撤 SOC 请求。smart worker 刷新 mishow bool 后，只有非零才调用 `smartcharging_handle_algorithm_conflict`；零值直接跳过。因此此前设为 80 的共享阈值没有在这条路径重算或清零。其他策略重算、事件或 SOC 回差可能改变状态，故不声称限制永远保留；但无法据此交付确定的关闭与恢复步骤。store 还忽略若干 helper 错误，成功返回 count 不是持久写入或硬件完成证明。[R]

**80%/75% 策略不能证明外部供电承担系统负载。** 本轮闭合两个 callback 的注册：buck 为 slot0，quick 为 slot2；注册表共有六个槽，slot1/3/4/5 的实现未建立。buck 普通分支使用原生 owner `soc_limit` 申请或释放 `chg_enable`、`input_voltage` 约束，所查分支未直接对 `input_suspend`（info+0xd8）或 buck-input（info+0xe8）投票；不能据此证明输入保留。`input_voltage` callback 只更新软件 5V/9V 目标，不等于硬件输入已经改变。另一个 `support_charge_more` 分支执行电流阶梯控制。quick 使用固定 `soc_limit` disable voter 并进入 CP 停止链。这些结果说明策略会影响充电与快充行为，不能证明电池稳态电流为零、输入通路持续供系统或附带状态可完整恢复；原生 owner 也不是插件可以借用的请求身份。[R]

本轮还检查了 smart 模块 remove 的完整函数，没有取得它撤 SOC 请求或取消这些 delayed work 的证据，不能把卸载作为恢复方案。新 partition 模块只有官方包内身份，没有新增 live build-id 对应；所分析字节不自动代表当前手机绑定对象。

## 公开源码检索的边界

[源码报告](DADA_MCA_SOURCE_STAGE3.md)保存三个仓库名称/描述查询，完整返回 2、7、24 条，并对三个新 SM8650 modules 候选固定提交。六份公开 HTML 只覆盖根目录及 `qcom/opensource` 直接目录，没有提供明确 MCA driver/include 入口；实际 MCA C/头文件取得数为 0。本轮没有据直接目录推出全仓库不存在代码，也没有排除其他分支、子树和未跟进对象。

主线程另核对所选 Codex 内置浏览器会话的 GitHub code search：页面要求登录，没有取得代码结果；CLI PATH 中没有 `gh`。这是该会话与 PATH 的可用性记录，不描述其他账号或应用。未登录、未访问凭据，临时标签已关闭。观察保存为人工 UI 转录，未冒充 HTTP 原文。[B]

主线程用独立 HTMLParser 从六份原始响应重新提取嵌入数据，逐份与作者保存的 JSON 比较，再核对固定 commit、路径、数量、查询范围、API 限流与公开 git ref。另核验 63 份工件及既有输入的字节身份，共 166 项检查通过。[S]

## 当前后端决策

本轮没有补齐下列任一完整实施路线：

1. **完整厂商入口：** 能查询真实且有效的请求状态，申请自身约束，独立释放自身请求，传播部分执行与恢复错误，并证明保留系统输入供电。NightCharging 的下游仍缺契约；TypeC/Battery 通用方法和本轮零售候选没有交付这些条件。
2. **匹配 MCA 的受限后端：** 对应 dada / OS3.0.303.0.WOCCNXM 的实际 `driver/mca`、`include/mca` 及 source/build 对应材料，足以审查准确类型、锁、对象获取/引用、解绑/销毁、无分配查询和 enabled=0 释放。root、同名模块或少量符号匹配不补足这些条件。

物理旁路与完整恢复还需要匹配的 ADSP/PMIC 型号、修订、字段及板级通路资料，和有 validity/errno、测点、单位、符号及采样时效定义的合格测量入口。第二轮记录的这些缺项没有在本轮消失。

下一项能改变实现决策的材料应是上述匹配源码/构建资料，或有实际实现依据的完整新入口；取得后先在电脑审查契约与状态机，再按[既定方案](DADA_IMPLEMENTATION_NEXT_STEPS.md)形成可审阅的手机对比方案。当前材料不足以进入新的可加载后端或实机试写；单纯扩大无明确入口的仓库扫描、照节点名称尝试写值，不能补齐恢复和供电证明。

## 交付与最终核验

本轮没有修改插件，也没有生成新的安装包。主线程核对现有 `lib.sh` 与 `profiles/mca.sh`：新 mishow/poweroffmode 节点没有加入现有自动读取入口或允许的原生配置名；MCA profile 仍固定为已有 FCC 策略。此项只说明新增候选未接入，不是对整个插件和全部 getter 的新安全认证。

手机读取、写入、ADB、Binder/HAL 调用、安装、驱动加载、寄存器操作和实机对比均为 **0**。证据完整性 PASS 与静态调用链闭合均不计作电气旁路 PASS。

[独立 HAL/零售审查](DADA_STAGE3_INDEPENDENT_REVIEW.md)未发现尚未解决的实质问题。审查者使用自己的 ELF 解析器与 fresh LLVM，核对 81 个 HAL 窗口、39 个零售代码窗口、10 个数据窗口、12,806 次 opcode 原字节比较（含重叠），以及 124 个 FDE、全部方法的原始 RELR/vtable 目标、TypeC 完整比较集合和零售 33 个完整具名函数边界；1,919 项独立检查通过。主线程采纳了 buck-input 字段的措辞修正，各报告统一将 80%/75% 写为停充/恢复请求，避免承诺硬件完成。[Q]

主线程最终核对本轮各份 manifest 的文件身份、五份报告的本地链接与格式，以及 `git diff --check`。本轮启动基线中的 307 份旧证据和 35 份既有产品文件均保留原字节。核验结果保存为 `.tooling/bypass-check/stage3/root/final-integrity-checks.json`，最终报告与清单身份另存 `stage3-delivery-manifest.json`。独立审查者冻结的主审草稿快照保持其原身份，主线程的最终编辑不冒充该草稿已获审核。

- [R] [零售策略报告](DADA_RETAIL_POLICY_STAGE3.md)、[精确提取记录](../.tooling/bypass-check/stage3/retail/charger-partition-acquisition.json)及该目录的显式 section 窗口/原始字节/重定位。
- [S] [独立公开响应核验](../.tooling/bypass-check/stage3/root/source-independent-check.json)、[独立解析器](../.tooling/bypass-check/stage3/root/check_saved_source.py)；未执行下载内容，没有网络或手机访问。
- [B] [GitHub 浏览器可用性观察](../.tooling/bypass-check/stage3/root/github-browser-observation.json)，仅人工转录 UI。
- [Q] [独立审查报告](DADA_STAGE3_INDEPENDENT_REVIEW.md)与[冻结清单](../.tooling/bypass-check/stage3/review/manifest.json)；[主线程清单](../.tooling/bypass-check/stage3/root/manifest.json)由最终核验器冻结。
