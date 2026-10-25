# dada 第二轮：HAL class 路径的驱动构造证据

日期：2026-10-13。本轮仅在电脑上分析已有配套驱动 ELF。没有 ADB、手机读取/写入、Binder 调用、安装、驱动加载、寄存器访问或实机测试。

**已补齐 `/sys/class/xm_power/charger/charge_interface` 的编译期构造链：`mca_sysfs` 创建 `xm_power` class 和 `charger` 分类设备，`mca_charge_interface` 把属性组建在 probe 所用的平台设备对象上，再从分类设备创建名为 `charge_interface` 的链接。** 这使第一阶段 HAL 路径与驱动实现之间的关联有了直接证据；仍须以初始化及 probe 成功为条件，不能证明手机当前链接、HAL 文件或对象的运行身份。[E1][E2][L1]

这项新证据没有增加撤销充电请求的能力，也没有证明停充后输入继续承担系统负载。HAL 的 `charge_enable` 键仍按第一阶段已审查的映射走 `input_suspend` 属性；两个名称不应混用为电池专用控制。[L2]

## 输入工件及适用范围

| 工件 | 字节数 | SHA-256 | 来源与边界 |
| --- | --- | --- | --- |
| `mca_sysfs.ko` | 32632 | `63e0aa14578bf61620ce0f8dd52066ffde1334afa1acdd3c7e6a0c875c09b505` | 既有磁盘工件；第一阶段与 OS3.0.303.0.WOCCNXM 发布包逐字节对应 |
| `mca_charge_interface.ko` | 29656 | `e3e71e2510d390a2a64ad530a9d8878ff84aec55db82ba28a19700d5e601d3c7` | 同上；此处解析 ELF 及重定位，不执行模块 |

原始路径为 `.tooling/bypass-check/driver-binaries/`。此次脚本从 ELF section/symbol/RELA 表重新取得函数字节、对象字节与重定位，并用已有 LLVM 主机工具重新反汇编选定函数。证据保存到 `.tooling/bypass-check/stage2/root/`；旧模块和旧分析输出没有改动。配套发布包与现场加载/远端固件身份的不同边界沿用第一阶段报告。[E1][L1]

## 1. class 与分类设备

`mca_sysfs.ko` 的 `init_module` 在 `.init.text+0x48/0x4c` 通过重定位取得 `.rodata+0x2f` 的 `xm_power` 字符串，`+0x50` 调用 `.text+0x174` 的 `mca_sysfs_get_or_create_class`。后者查找已有 class，在新建分支的 `.text+0x1f4` 调用 `class_create`，将返回指针存到条目 `+0x18`。[E1][E2]

`g_mca_sysfs_dev_name_list` 位于 `.data+0x0`，大小 40 字节，是五个带 `R_AARCH64_ABS64` 重定位的指针：

| 表偏移 | 指向 `.rodata` 的偏移 | 分类名 |
| --- | --- | --- |
| `0x0` | `0x13f` | `charger` |
| `0x8` | `0x14e` | `fuelgauge` |
| `0x10` | `0x29` | `typec` |
| `0x18` | `0x1bb` | `battery` |
| `0x20` | `0x1c3` | `hw_monitor` |

`init_module` 在 `+0xdc..+0x100` 使用该表逐项调用 `device_create`：x0 为上述 class 指针，parent/devt/drvdata 为零，x4 为 `%s`，x5 为分类名。循环在 `+0x188` 比较 5；不是仅凭一个 `charger` 字符串推断分类设备存在。成功路径把名字和设备指针分别保存为条目 `+0x10/+0x18`。[E1][E2]

## 2. 属性组落在平台对象，分类设备提供链接

`mca_charge_if_probe` 位于 `.text+0xd0`，保留传入的 platform-device 对象，并在 `+0xe4` 取得其 `+0x10` 地址，作为 `devm_kmalloc` 的设备参数。该地址随后保存在新上下文首成员，并在 `+0x124` 读出为 x2。[E1][E3]

`+0x128..+0x140` 的参数及重定位为：

| 参数 | 编译期值 | 证据 |
| --- | --- | --- |
| x0 | `charger` | `.rodata+0x1d6` |
| x1 | `charge_interface` | `.rodata+0x27b` |
| x2 | probe 中使用的原设备对象地址 | 保存/读取链：`+0xe4 → +0x104 → +0x124` |
| x3 | `mca_charge_if_sysfs_attr_group` | `.rodata+0x5d0`，40 字节 |
| call | `mca_sysfs_create_link_group` | `+0x140` 的 `R_AARCH64_CALL26` |

`mca_sysfs_create_link_group` 在 `.text+0x3f0..+0x414` 找 `xm_power` class，在 `+0x444..+0x454` 按输入 x0 找分类设备，然后依次执行：[E1][E2]

1. `+0x45c..+0x464`：把原 x2 对象与原 x3 属性组交给 `sysfs_create_group`。
2. group 返回 0 后，`+0x46c..+0x478`：把分类设备对象、原 x2 目标对象、原 x1 链接名交给 `sysfs_create_link`。
3. 两个 sysfs 调用都返回 0 时 helper 才返回 0。helper 检测到输入 NULL、查找失败或 sysfs 返回非零的分支返回 -1；probe 在 helper 非零结果时也返回 -1。

因此构造成功时，属性属于原平台对象，class 路径通过 `charger/charge_interface` 链接到它。此 helper 在 link 创建失败分支中没有调用 `sysfs_remove_group`；不能把 probe 失败视为所有中间创建动作都已撤销，也不能凭磁盘代码确认现场成功。

另一个失败处理边界是：上述 `class_create` / `device_create` 返回路径在本模块中只检查 NULL，没有检查 ERR_PTR。构造链的静态对应不构成对驱动错误处理、生命周期或恢复质量的保证。

## 3. 七个属性的完整表

probe 在 `+0x108..+0x120` 传入 `.data+0xf8` 的 280 字节字段表、`.bss+0x98` 的 64 字节指针数组和数量 7。`mca_sysfs_init_attrs` 的 `.text+0x700..+0x778` 以 40 字节步长把字段表记录地址写入数组，最后写 NULL。[E1][E2][E3]

字段表各记录的名称指针、公共 show/store 指针、id 均取得；mode 原始值都是 `0x1a0`（0640）。group 原始 40 字节全零，仅 `+0x18` 有指向 `.bss+0x98` 的指针重定位，name 没有重定位且为 NULL，因此没有额外命名子目录。[E1]

| 记录偏移 | id | 属性名 | show / store |
| --- | --- | --- | --- |
| `.data+0xf8` | 0 | `input_suspend` | `.text+0x1c4 / +0x74c` |
| `.data+0x120` | 1 | `charge_enable` | 同上 |
| `.data+0x148` | 2 | `iin_limit` | 同上 |
| `.data+0x170` | 3 | `ichg_limit` | 同上 |
| `.data+0x198` | 4 | `power_limit` | 同上 |
| `.data+0x1c0` | 5 | `suspend_status` | 同上 |
| `.data+0x1e8` | 6 | `shipmode_count_reset` | 同上 |

BSS 是 NOBITS，本轮证据只记录其符号范围，不把空文件字节或推测的现场内容当成运行状态。上述表明确区分 `input_suspend` 与 `charge_enable` 两个驱动属性；HAL 键名映射到前者的结论来自服务的初始化数据，而不是本表把它们设成相同属性。[L2]

## 本轮新增结论与保留缺口

编译期 class → link → platform 属性组关联已闭合，下一轮不必仅凭名称重新猜测这条链。仍未核对当前手机的 class 链接目标、probe 成功结果、HAL 工件身份或动态对象寿命；这些都不由此次静态构造证据自动获得。字段存在与可写 mode 也不构成可读/可写白名单。

固定 owner 的申请、无分配查询、enabled=0 释放、真实错误传播与硬件/远端附带恢复仍须另有实现证明。此报告不建议替换 HAL、不创建新增 shell 开关、不提供手机命令，不将 class 映射补齐计为旁路功能验收。

## 证据索引

- [E1] [结构化字节及重定位](../.tooling/bypass-check/stage2/root/class-links-evidence.json)：输入整体 SHA-256、五个选定函数字节及 hash、四个 data/BSS 对象、全部相关重定位。
- [E2] [mca_sysfs 新反汇编](../.tooling/bypass-check/stage2/root/mca_sysfs-class-links.disasm.txt)：init、class、link 和 attrs 初始化函数。
- [E3] [mca_charge_interface 新反汇编](../.tooling/bypass-check/stage2/root/mca_charge_interface-class-links.disasm.txt)：probe 参数构造。
- [E4] [本轮主机解析脚本](../.tooling/bypass-check/stage2/root/inspect_class_links.py)：只读取原 ELF，输出本轮证据；未在手机运行。
- [L1] [发布包取得与对应](DADA_FIRMWARE_HAL_ACQUISITION.md)及[第一阶段主审](DADA_STAGE1_RESEARCH_REVIEW.md)。
- [L2] [原厂 HAL 方法、键表与 writer](DADA_NATIVE_INTERFACE_STAGE1.md)及[关键映射独立审查](DADA_NATIVE_INTERFACE_REVIEW.md)。
