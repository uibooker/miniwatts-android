# dada 第二轮独立交叉审查

日期：2026-10-13。审查对象为第二轮 class 原始证据、原生候选报告和 PMIC 定义报告；也检查主审汇总的相关结论。审查代理不复查自己编写的 MCA source 报告，该线由主代理另行核验。没有手机/ADB 操作、HAL/Binder 调用、驱动加载、寄存器访问、安装或插件修改；只运行自己编写的主机解析器、已有 LLVM 主机反汇编工具及 EROFS 主机数据读取工具，没有执行其他代理的脚本或目标/vendor 代码。

## 发现与最终处置

**发现1项 P2 证据生成缺陷，修正后独立重验通过；当前没有未解决的实质 finding。** 这一通过针对静态工件身份和有限推导，不是手机控制或物理旁路 PASS。

初版 `native/build_evidence.py` 的模块窗口先计算 VA，再在所有 ELF 节中选第一个覆盖该地址的节。ET_REL 模块多个节的 `sh_addr=0`，范围重叠，因而 provider 的两份 `.text` 窗口保存了 `.rodata` 字节。原验证器复用相同地址查找，能够自洽地批准同一个错误，不能支持旧版“全部窗口 PASS”。

| 旧错窗 | 旧范围 | 旧保存字节 SHA-256 | 直接按 `.text` 核验 |
| --- | --- | --- | --- |
| `ship-provider-00` | `0x6a4..0x6bc`，24字节 | `a9899af2197216ff7840e5be721fd58d6e674fe21a2930a624ebefca740e1981` | 旧字节以 `5d25733a25642064` 开始，是文本；实际指令以 `0800009008010091` 开始 |
| `ship-provider-01` | `0xdfc..0xee8`，236字节 | `f9de331d819e1562b9ec980ae9453c18432e1641b710a0224f5572ba32dcf78f` | 与原 `.text` 不一致；实际函数以 `3f2303d5ff0301d1` 开始 |

初次独立按指定节核对60窗，仅上述两窗不一致。原作者已经覆盖旧产物；本审查保存的 `native-window-original-finding.json` 留存初查全部旧/期望字节、旧 hash、坐标及修正版 hash，明确它们是旧版本记录，不把旧字节描述为当前工件。[R1]

修正后生成器在指定节内用 `sh_offset + section_offset` 取字节，服务 VA 只在指定 `.text` 内转换，字面量只读 `.rodata`。本地验证器另行解析指定节，再比较 fresh LLVM 的逐指令 opcode。provider00 扩到 `0x690..0x6bc`，覆盖 w24=255 定义；其新窗口 SHA-256 为 `d2a4eb64c35a59709e57c35fbb6ae66d41b5bc266c4747fd3c80e22c6fb305dd`，provider01 新 hash 为 `00f768d1111e2528877d0a7658088636a14401af05229375da9a470d64211575`。本审查没有依靠原作者的 PASS：从原 ELF 重新生成6份仅 `.text` 的反汇编，逐一核对60窗字节、完整指令地址和全部1341个 opcode，独立通过。[R2]

另反馈并确认了 class 文稿的精度补正：class_create/device_create 分支只检测 NULL，没有 ERR_PTR 处理；helper 只有两个 sysfs 调用均返回0才返回0，link失败后没有 remove_group。补正后的文稿未将静态构造升级为完整错误恢复保证。

## class 路径的独立核对

独立 ELF 解析器没有导入/运行 `inspect_class_links.py`。从原2个模块取得5个函数、4个 data/BSS 对象及相关重定位，与结构化证据逐项比较，并核对两份 fresh disasm 的每个指令字；37项检查通过。[R3]

- `init_module` 的 `xm_power` 重定位、class helper 的 class_create、五名称指针表和 device_create 循环共同支持构造 `xm_power` class 下的 charger 分类设备，不能只由字符串存在推断路径。
- probe 的传入 pdev 在 `0xe4` 取 `+0x10`，在 `0x104` 保存到上下文首成员，`0x124` 读回作为 helper x2。这个地址也是 devm_kmalloc 的设备参数。helper 原样传它给 sysfs_create_group，并作为 sysfs_create_link 的目标；源对象来自 charger 分类设备的保存指针。
- helper 搜索 xm_power 与 charger；group创建返回非零直接失败，group返回0后才创建 charge_interface link；link成功才返回0。输入NULL/查找失败与 sysfs非零分支均有限定的失败路径，probe helper非零返回-1，分配失败返回-12。没有将这些路径当作完整回滚。
- 字段表280字节、七条40字节记录的模式/id/名称及show/store地址一致，依次为 input_suspend、charge_enable、iin_limit、ichg_limit、power_limit、suspend_status、shipmode_count_reset。init_attrs写七个指针及第八个NULL，BSS本身不提供现场值。
- group首8字节为零且没有该偏移重定位，唯一指针重定位在+24指向attrs数组；因此是NULL group-name，不是额外同名目录。

为解释 struct 语义，精确取得 Google 官方固定提交 `d415a2d4bd29d2e3d5b138d72fe606034ebdef44` 下的三个头文件：`sysfs.h:84` 中 name/attrs，`device.h:706` 中首成员 kobj，`platform_device.h:24` 中内嵌 dev。原 Gitiles Base64响应、解码文本及请求记录均保留。[R4] 这些只说明基线语义；没有用其布局计算当前 ABI，也没有据该基线证明当前手机运行的 class symlink、HAL 文件身份、probe结果或对象寿命。

## 原生候选的独立核对

修正版 native 工件共269项自动检查通过，另手工追关键控制流。[R2]

11个缺项的原服务 `.rodata` 字面量、全局 String 构造及键表两种复制分支与报告一致。`charge_full_design` 长49且没有前导斜线；保持相对路径是正确边界，当前工作目录和实际解析未证明。batt_sn 从 `0x51e5` 取得32字节/尾部，经 `sp+0x20` 和 x19缓存，在 `0x30178/0x30190/0x30194` 正确恢复；独立保存的 `0x2f7d8..0x30178` 616条指令文本没有字面 x19/w19 或该缓存位置引用，结合 ABI保留寄存器规则支持本条静态追踪，但不是全程序别名证明。

分类文件确有110个连续index：99个自动来源、11个本轮闭合；其中另11个只是path手查，88个自动路径仍明确未手审。25/32/53是名称筛查分组，每行均说明“不等于驱动语义”；不能由遥测组名称推导 show 无副作用或读取授权。反直觉的 fg2_soh_sn→fg_slave/soh、is_support_mca→fg_master/soh，以及 charge_enable→input_suspend 均在初始化/复制证据中得到支持；没有把同名驱动 charge_enable 属性混成同一入口。

`enable_rollback`：独立核对 `.data+0x408` 名称、id14、show/store跳表，目标为 `0x64bc/0x6824`。store解析十进制 signed int，非零转换为bool写 `info+0x988`，没有撤票参数或即时硬件调用；show读同一标志。完整 `.text` 对字面 `#0x988` 的三处可识别引用一致，consumer位于 mca_battery_shutdown_update_vcutoff_para。其他条件及阈值比较决定是否把 +0x930/+0x934 设3050、+0x938设2900；这支持低压关机策略回退解释。有限offset扫描不能排除间接访问或其他模块用途，也不能证明false会即时恢复阈值。

ship链：id6和跳表进入 `0xa04`；特殊输入固定buck和micharger。`0xa64` 清零临时unsigned，kstrtouint返回未检查，callback返回也未用于最终count。原重定位闭合 charge-interface ops+0x60→buck set_ship_mode→class provider ops+0x1e0→qcom setter。setter仅保存 `info+0xc0` 并返回0；getter读同一flag。probe将回调放在 `info+0xc8` 注册reboot notifier，priority定义为255。notifier event1/3且flag非零时才进一步查询CP vendor，条件关闭role0 OVPGATE，再发送property5/8-byte event；失败只记录，末端仍返回0。这支持“延后条件动作”结论，不是无害计数器清零，也不提供独立充电owner释放或已触发硬件后的恢复。

运行provider绑定、远端property5 handler与新fg_comp的live build-id均未闭合；报告正确地将磁盘模块、包来源、软件flag与运行/电气状态分开。

## PMIC 定义、来源与最终 hash

PMIC候选核对91项通过：14工件、2依赖、报告的hash/大小；5源码Git blob与固定tree对应；token结果；两个型号枚举独立展开及旧修订结构。具名最大42，旧定义中的PM8150/PM8250/PM8150B别名存在，但没有本机目标型号或ADSP模型code的绑定。DCDC源头的boot.xf/3.2与2019日期、第三方镜像身份及有限五文件/三文件否定范围与文稿一致；没有把旧字段、型号空间或缺搜索结果升级为本机schema。[R5]

对root partition来源另外执行精确数据重提：先核对vendor_dlkm.img哈希，再读取三份已选FG文件，尺寸/哈希/enable_rollback token均与selection一致，fg_comp与保留文件逐字节相同。没有执行提取出的模块。class两模块与包内文件的对应也复核一致。[R6]

最终manifest检查181条记录全部通过：root 9工件+6输入，power 14工件+2依赖+报告，native 149文件。记录包含重复材料，数字不是唯一文件计数；本审查没有复核自己source线的manifest。最终root class、native、power报告hash均与各自冻结manifest一致。[R7]

主审相关措辞未把构造证据称作现场存在、软件count称作硬件成功、有限型号/文件阴性称作资料不存在，也未把本轮候选排除称作全部厂商入口不可用。两候选的排除与class关联补齐成立；本轮仍没有可安全实现、可恢复、可电气验证的旁路后端。无须新增手机实验来完成这次静态审查。

## 保存材料

全部审查工件位于 `.tooling/bypass-check/stage2/review/`，`manifest.json`记录本审查输出与输入hash；未修改其他代理材料。

- [R1] `native-window-original-finding.json`：旧错窗、原字节差异和修复身份记录。
- [R2] `check_native_evidence.py`、`native-review-checks.json`、6份 `fresh-*.disasm.txt`、60份 `fresh-window-*.txt`、`batt-sn-cache-between-fresh.txt`：独立原字节/逐opcode核对及手工追踪材料。
- [R3] `check_class_evidence.py`、`class-review-checks.json`；输入 `stage2/root/class-links-evidence.json` 和两份class fresh disasm。
- [R4] `google-{sysfs,device,platform_device}.h`、对应`.base64`和`.request.json`；[sysfs](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/include/linux/sysfs.h#84)、[device](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/include/linux/device.h#706)、[platform_device](https://android.googlesource.com/kernel/common/+/d415a2d4bd29d2e3d5b138d72fe606034ebdef44/include/linux/platform_device.h#24)。
- [R5] `check_power_evidence.py`、`power-review-checks.json`；[PMIC报告](DADA_PMIC_DEFINITIONS_STAGE2.md)。
- [R6] `root-power-manifest-review.json`：独立三FG精确重提和早期root/power身份核对。
- [R7] `final-input-manifest-checks.json`：181条最终记录；[class报告](DADA_CLASS_PATH_STAGE2.md)、[native报告](DADA_NATIVE_INTERFACE_STAGE2.md)、[主审](DADA_STAGE2_RESEARCH_REVIEW.md)。
