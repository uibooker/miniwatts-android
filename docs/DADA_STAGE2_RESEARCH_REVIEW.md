# dada 第二轮推进结果与实施决策

日期：2026-10-13。继续[第一阶段主审](DADA_STAGE1_RESEARCH_REVIEW.md)留下的具体线索，三个子代理分别补原生接口、查实际 MCA 源码和核对 PMIC 定义；主代理补 class 路径构造，并复用已完成代理交叉审查。范围是电脑上的已有官方发布包、ELF 和公开文本，未启动第三轮泛搜或手机实验。

**本轮补齐了原生键表的全部 11 个未解路径，并证实 HAL class 路径在配套驱动中的构造关联；新增深查的两个候选都不提供插件充电请求的撤销。匹配 MCA 源码和 PMIC 定义仍未取得，因此本轮没有形成可实现、可恢复、可验证的物理旁路后端。** 这限定于本轮分析范围，不是所有厂商入口都已审查，也不是硬件不支持旁路的结论。

本轮手机读取、写入、ADB、Binder/HAL 调用、安装、模块加载、寄存器操作及实机对比均为 **0**。没有修改插件或生成新安装包。现有插件的能力边界仍是 FCC 限流，物理旁路未验证。

## 本轮实际新增的证据

| 第一阶段留下的事项 | 第二轮结果 | 对后端实现的影响 |
| --- | --- | --- |
| HAL 键表 11 个路径未解 | 11/11 手工追到 String 初始化和复制；完整表为 110 行 | 可以准确界定候选路径，不增加接口使用授权 |
| 99 个自动路径的检查范围不清 | 按名称筛查为 25 控制、32 维护、53 遥测/其他候选；另 11 个路径手审，88 个仍未逐项手审 | 分类是研究清单；名称、路径和返回成功均不能替代下游语义 |
| `enable_rollback` 是否撤销控制 | 新提取 fg_comp 中为低压关机阈值回退标志；受条件限制更新 cutoff 参数 | 排除为本轮停充/撤票候选；不能把写 false 当作即时恢复 |
| `shipmode_count_reset` 是否恢复控制 | charge_interface → buck → class → provider 设置 shipmode 标志；重启通知在特定事件下可发送远端请求 | 排除为旁路/撤票候选；不能当成普通计数器清零 |
| HAL class 路径与平台驱动只按名称关联 | 配套驱动创建 xm_power/charger，属性组落在平台对象，charge_interface 链接指向它 | 编译期关联闭合；现场 symlink、HAL/provider 身份和初始化结果仍未核对 |
| 是否另有真实 MCA 源码来源 | 五个限定网页查询未提供实际 C/头文件候选；GitHub 代码索引 401；onyx 两个发布入口仍是 proprietary symlink | 没有取得可供实现审查的源码，不能由检索受限推出源码不存在 |
| 是否能补 PMIC 型号和附带字段定义 | 一个旧 BOOT.XF.3.3 第三方镜像的两个候选组不含匹配型号/FSW/Misc 定义 | 不能绑定本机模型、猜单位/极性或移植其他芯片寄存器字段 |

路径核对还确认两处容易误读的编译结果：`fg2_soh_sn` 指向 `fg_slave/soh`，`is_support_mca` 指向 `fg_master/soh`。`charge_full_design` 保留原始相对路径 `sys/class/power_supply/battery/charge_full_design`，没有擅自补斜线。以上只说明本包初始化映射；不证明这些名字对应的业务方法或当前解析结果。

## 两条新候选为什么不能用于退出旁路

`enable_rollback` 的 name relocation、id14、show/store 跳表与 consumer 都取得了原字节证据。store 将十进制输入转换为 bool，写 `info+0x988`；可识别的 consumer 为 `mca_battery_shutdown_update_vcutoff_para`，在低压与其他配置条件满足时更新低压关机阈值。它没有独立 owner、vote enabled 参数或充电撤票动作。该判断覆盖本模块已确认的实现，不把仅三个精确 offset 引用升级为全系统没有其他用途。[N]

`shipmode_count_reset` 在本次选中链中固定 user 为 `micharger`、type 为 buck，解析 unsigned 后通过已绑定 ops 设置 provider 的 `info+0xc0` 标志。即时 setter 不发送 GLINK；后续 reboot notifier 在 event1/3且 flag 有效时才执行相关动作，可条件关闭 CP OVPGATE并发送 property5。这里必须区分设置延后条件与已经触发硬件：撤 flag 不能证明已触发后的恢复，更不提供插件充电 voter 的释放。store 还忽略部分解析/回调错误，成功 count 不能作为完成证明。[N]

## class 路径关联补齐后的准确边界

`mca_sysfs` 的实际 name 指针表包含 charger、fuelgauge、typec、battery、hw_monitor。`mca_charge_if_probe` 传 `charger`、`charge_interface`、原平台设备对象和无额外 group-name 的七属性组给 helper；helper 先在该对象建 group，再从分类设备建 link。group/link 都返回 0 的分支完成该构造。[C]

这支持把本包 HAL 的路径映射关联到配套驱动属性实现；没有在手机上确认链接目前存在或 HAL正在使用这套对象。HAL 的 `charge_enable` 键仍指向 **input_suspend** 属性，驱动自身还另有同名 charge_enable 属性；本轮没有把二者混成电池专用停充入口。class/device 创建只检查 NULL，以及 link 失败未撤 group 的处理，也不能据此获得生命周期或完整失败恢复保证。[C][L]

## 当前实施条件

| 条件 | 本轮状态 | 仍需取得或证明的内容 |
| --- | --- | --- |
| 申请、无分配查询、释放自身请求 | 新增两候选均不满足；其他入口未完整审查 | 一个完整厂商契约，或匹配 MCA 内部受限接口 |
| 固定 owner 功能恢复 | 既有 enabled=0 内核机制仍只有局部证据 | 在正确类型、锁和对象寿命下释放并重新聚合；允许保留一个 inactive slot |
| 控制失败和远端附带恢复 | 本轮未补齐 | 真实错误传播、部分执行处理、硬件确认；不能把 active allow=1 当成撤票 |
| MCA 实现与当前构建 | 未取得实际 C/完整头文件和对应构建输出 | driver/mca 与 include/mca、Kconfig/Makefile、引用/解绑契约及 source/build 对照 |
| 停充后保留系统输入 | 未证明 | 同型号/修订的 ADSP PMIC 初始化和 SCHG 字段、板级 SYS/VPH/电池/CP 关系 |
| 物理验收测量 | 本轮没有新增合格测量接口 | 保留 validity/errno 的硬件查询，以及通道、测点、符号、单位、校准、采样时效和读副作用证据 |

新 fg_comp 只证明来自已验证 vendor_dlkm 分区；既有 metadata 显示当时加载过同名模块，但没有它的 live build-id，不能称为当前加载字节匹配。既有20个模块的包内对应证据不替新工件补这个证明。[N][R]

源码线的 issue40346 是 onyx 用户缺源码反馈，不能冒充 OEM 声明或 dada 的全仓库状态。PMIC 线的旧模型枚举最高具名值42，也不是全型号空间上限；旧协议的 PmicModel 字段不能绑定本机 ADSP 模型 `0x47/0x4c/0x56/0x59`。检索失败、有限文件未命中和 build-id 对应分别保留各自范围。[S][P]

## 下一步能实质改变结果的材料

1. **匹配 MCA 来源及构建包**：dada / 24129PN74C / OS3.0.303.0.WOCCNXM 已加载 MCA 对应的 `vendor/xiaomi/proprietary/mca/driver/mca` 和 `include/mca` 实际文件，完整配置/构建 manifest/符号输出及来源关联；当前 `f9a1d4bd8353-abogki440974771` 内核构建资料须另有明确对应。先核对对象获取/释放、锁、unbind/destroy、无分配查询和回调错误/恢复协议，再决定能否在 MCA 内部实现受限后端。[S]
2. **匹配 ADSP/PMIC/板级资料**：PM8750BH、PMIH010X 与 ADSP 模型/修订的明确对应、device/comm/SlaveID 初始化；同一型号/修订的 SCHG DCDC FSW 与 Misc register/HAL 定义，覆盖 `0x2742`、`0x2c42/0x2c43` 及既有附带转换。只有这些定义和运行对象/板级关系闭合后，才设计停充与完整恢复。[P]
3. **另一条有证据的完整原生入口**也可替代自建源码路线，但需证明实际申请、无副作用查询、独立释放、真实错误和附带恢复。当前110项只是路径/分类清单，尚有88个自动路径未手审及大量下游语义未检查；本轮没有据两项排除宣布厂商全部入口不可用。[N]

本轮没有这些材料，因此不进入靠猜结构或地址的可加载实现，不生成待用户试装的旁路版本，也没有合格的手机对比方案。继续分析应由上述具体材料或新的明确入口支撑；材料齐备后再依[既定推进方案](DADA_IMPLEMENTATION_NEXT_STEPS.md)完成电脑状态机与恢复验证，形成可审阅的实机方案。

## 交付与核验

- [N] [原生键表与两个候选](DADA_NATIVE_INTERFACE_STAGE2.md)：11未解闭合、110分类、11额外路径手审与原字节窗口。
- [C] [class 路径构造](DADA_CLASS_PATH_STAGE2.md)：两模块、五函数、四数据/BSS对象、属性与 name 表重定位。
- [S] [源码限定检索](DADA_MCA_SOURCE_STAGE2.md)：五限定查询、索引受限记录、onyx固定 symlink 和材料要求。
- [P] [PMIC 定义核对](DADA_PMIC_DEFINITIONS_STAGE2.md)：固定第三方镜像的两组候选、身份与字段缺口。
- [Q] [最终独立交叉审查](DADA_STAGE2_INDEPENDENT_REVIEW.md)：1项P2已解决，当前未解决实质 finding 为0；class37、PMIC91、修正版native269项检查通过。
- [R] `.tooling/bypass-check/stage2/root/manifest.json` 和 `verification.json`：两 class 模块与官方包逐字节比较；重新从 SHA-256 已验证 vendor_dlkm 读取三个精确 FG 文件并比对大小、hash与目标字符串，只有 fg_comp保留。
- [L] [第一阶段主审](DADA_STAGE1_RESEARCH_REVIEW.md)及[原生关键链独立审查](DADA_NATIVE_INTERFACE_REVIEW.md)。

独立审查发现并推动修正了一个 P2 工件问题：原生窗口提取器按虚拟地址选择首个覆盖节，而 ET_REL 的多个节起址都为 0，导致 `ship-provider-00/01` 的旧记录取到 `.rodata` 文本字节。旧 PASS 作废；修正版严格按指定 section 的文件偏移抽取并检查边界，使用独立解析器与新 LLVM 反汇编逐条比较全部60窗口、1341条机器码。provider注册窗口还扩至 `0x690..0x6bc`，包含 priority255 的来源。旧错误字节与修复状态保留在 `review/native-window-original-finding.json`，不是删除错误记录后沿用旧批准。

源码线由另一代理独立核验，`review/source-independent-check.json` 为PASS243，未发现实质问题；保留了网页解析的CDATA注释残片限制。独立审查未让原作者自审自己的源码报告。

主线程最终复核五份冻结manifest中的318项文件记录，包括重复来源；六份本轮报告的本地链接与格式检查、`git diff --check` 通过。审查manifest中明确标为主审草稿的快照单独保留，最终主审hash另存交付manifest，未修改审查者记录来冒充其审批最终编辑。

最终记录见 `.tooling/bypass-check/stage2/root/final-integrity-checks.json` 和 `stage2-delivery-manifest.json`。这些核验针对电脑材料与推导，不计作手机控制成功或电气旁路 PASS。
