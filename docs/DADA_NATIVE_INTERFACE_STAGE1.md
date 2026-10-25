# dada 原生 MiCharge 入口核查：第一阶段

日期：2026-10-13。范围为电脑上的公开资料、已下载源码、公开 MiCharge 样本，以及主审从官方 OTA 选择性提取的厂商服务/配套接口库的静态检查。本报告没有执行 ADB、手机读取/写入、安装、驱动加载、寄存器操作，也没有运行仓库附带的构建、刷机或测试脚本。

**官方包内所查 MiCharge 原生路线不满足插件的完整申请/释放要求。** 已从配套 AIDL 库核对全部 56 个方法名及目标，并专项实证 tx42/43/53/54：common getter/setter 是空实现；`setMiChargePath("charge_enable", …)` 实际映射到 `input_suspend`；异步写接口超时不取消工作，也没有插件请求释放契约。这不是“整个固件不存在任何入口”的结论：其他 setter 和表中其余策略键未由这四项全局排除。当前还不能据此实现或试写安全旁路后端。

证据保存在 [native/manifest.json](../.tooling/bypass-check/stage1/native/manifest.json)，包含固定 commit、来源 URL、档案与文件 SHA-256、原厂样本导入符号/依赖及精确摘录。所有文件名和方法参数均是资料定位用途，本文不提供 Binder 调用或写节点命令。

## 官方 OTA 实际入口与构建边界

主审取得 [官方 OS3.0.303.0.WOCCNXM OTA](https://cdnorg.d.miui.com/OS3.0.303.0.WOCCNXM/dada-ota_full-OS3.0.303.0.WOCCNXM-user-16.0-527efd3838.zip)，在电脑选择性重建 vendor/vendor_dlkm，验证分区 hash 和 operation 数据 hash。解出的 `vendor/build.prop:21` 为 `ro.vendor.build.version.incremental=OS3.0.303.0.WOCCNXM`，`:17` 为 `Xiaomi/mivendor/mivendor:15/AQ3A.250226.002/OS3.0.303.0.WOCCNXM:user/release-keys`。OTA metadata 的系统内部版本另为 `16OS3.1.260529.201026374.QCPECN.S`，不能把不同分区身份写成同一个版本。

[mca-package-comparison.json](../.tooling/bypass-check/stage1/root-firmware/mca-package-comparison.json) 的 20/20 份包内 MCA/sc8581 模块与此前手机采集的磁盘 ELF 字节 SHA-256 完全相同，支持新 HAL 包与此前已分析 MCA 磁盘构建的关联；没有重新读取手机 HAL 文件或验证当前内存状态。不能声称本次已验证手机当前运行的 HAL 与包内完全相同。

| 包内材料 | 精确身份 |
| --- | --- |
| `vendor/bin/hw/vendor.xiaomi.hardware.micharge-service` | 1,201,336 B；SHA-256 `0dcfb7b5816d962978e8724fdbb7798627f0575c6a72beae277c55b51f1f158b`；Build ID `7d86416112f836f15071f609c9b50b9f` |
| `vendor/lib64/vendor.xiaomi.hardware.micharge-V2-ndk.so` | 146,728 B；SHA-256 `42e7271e3da28f2e844b460b577e4a4f38463d1a57d7982ff776ca4c90c525fc`；Build ID `78ada333da98e40a4727b4fa3c5d38ae` |
| `vendor/etc/init/vendor.xiaomi.hardware.micharge-service.rc:184-185` | `micharge-hal` 服务，AIDL `vendor.xiaomi.hardware.micharge.IMiCharge/default` |
| `vendor/etc/vintf/manifest/vendor.xiaomi.hardware.micharge.xml` | AIDL version=2，`IMiCharge/default`；厂商输入注释 `vendor/xiaomi/proprietary/micharge/aidl/default/vendor.xiaomi.hardware.micharge.xml` |

上述服务 Build ID 与公开 shim 样本不同，不用 shim 代码替代官方 ELF 实现。完整 [ota-method-map.json](../.tooling/bypass-check/stage1/native/ota-method-map.json) 来自 ELF 的 NDK 动态符号、Bp vtable relocation、Bn 分发表和服务 vtable；不再依赖作者自述 transaction 表。NDK 使用 Android packed relocation，`inspect_native.py` 使用 LLVM 解码的 ABS64 符号配合 ELF 原始 addend，并记录所有 56 项签名。

### 专项方法链及返回语义

NDK `0xc11c..0xc13c` 的分发表以 `(transaction-1)` 索引 `0x7578` 的 56 项 uint16 表，目标为 `0xc140 + entry*4`。服务构造 `0x23628..0x2365c` 写入 vptr=`0x38048`；下表目标通过对应 vtable offset 和 relocation 交叉确认。所有坐标为包内 ELF 虚拟地址，不是手机运行地址，不可作为写地址。

| 方法 | NDK → 服务目标 | 实际行为与结论 |
| --- | --- | --- |
| tx42 `setInputSuspendState(String) -> int` | Bp `0x12dcc` 指定 code42；Bn `0xc800` / offset`0x178`；服务 `0x25024..0x25174` | 拼接固定前缀 `micharge all ` 与输入字符串，再向 global`0x124058` 代表的 `input_suspend` 节点直接写入。把通用 writer 的 0/-1 存入 int 返回值，同时返回 Binder OK。该入口控制输入暂停，固定厂商 owner；没有独立插件 owner 参数、释放/自动退出契约 |
| tx43 `setMiChargePath(String key,String value) -> int` | Bp `0x13058` code43；Bn `0xcacc` / offset`0x180`；服务 `0x265a8..0x26a00` | key 只在固定表 global`0x124d48` 查找，普通 key 不接受任意绝对节点路径。复制映射后的路径与原始 value 后建异步写任务；不追加插件 owner、不转换 MCA 参数、不调用撤票。`charge_enable` 键实际对应 `input_suspend`，详见下节 |
| tx53 `getChargeCommonInfo(String) -> String` | Bn `0xcd08` / offset`0x1d0`；服务 `0x2c890..0x2c8cc` | 只打印 `micharge getChargeCommonInfo` 并返回 `AStatus_newOk`；不读取节点、不写输出字符串。NDK 预清空输出，因此客户端收到空字符串。这不是可用状态 getter |
| tx54 `setChargeCommonInfo(String,String) -> int` | Bp `0x14ad0` code54；Bn `0xc26c` / offset`0x1d8`；服务 `0x2c8d0..0x2c90c` | 只打印 `micharge setChargeCommonInfo` 并返回 `AStatus_newOk`；不写节点、不写输出 int。NDK `0xc290` 先将返回 int 置0，因此客户端可收到0，却没有控制效果 |

tx43 的 worker vptr=`0x38260`、execute offset`0x18`，执行函数 `0x2eaa4..0x2eba0` 从对象字段`+0x78`取得路径、`+0x90`取得值，先 `access(F_OK)`，再调用 writer=`0x239c0..0x23ac8`。writer 使用 open flags`0x802`，调用 write，然后 close；open/write 失败返回 -1。它只检测 write 返回负号，不比较短写与期望长度，忽略 close 返回；返回0只表示其软件检查成功，不代表 MCA/远端/PMIC 确认。

tx43 `0x2688c..0x268ac` 设定等待期限为 steady clock + `0x1dcd6500` ns（500 ms）。超时在 `0x268b0..0x268e0` 写 int=-1 并返回 Binder OK。工作线程已 detach，超时路径仅释放引用；worker 没有读取取消标志，之后仍可能完成写入。不能把超时当作“远端没有执行”，也没有回滚/释放协议。未知 key `0x26640..0x2666c` 仅记不支持日志并返回 Binder OK，未写输出 int；NDK 的预置0也会使未知 key 表现为成功值0。

tx17 `getInputSuspendState` 读取同一 global`0x124058`（`0x24f44..0x25020`），tx18 `getMiChargePath` 另有通用异步 getter及 `set_cycle_power` / `unset_cycle_power` 特殊分支（`0x26a04..0x26e84`）。本次没有将 getter 扩为手机读取白名单；名字以 get 开头不意味着无副作用。

独立子代理使用原始 ELF 与新生成的23个 LLVM 窗口完成45项核对，[DADA_NATIVE_INTERFACE_REVIEW.md](DADA_NATIVE_INTERFACE_REVIEW.md) 结果 PASS，确认上述4方法分发、关键别名、stub、writer与超时边界。该复核没有验证手机当前运行路径绑定，也没有进行手机操作。

### `charge_enable` 的真实映射与固定 key 表范围

两段独立原始指令闭合这个容易误导的别名：

1. `0x2f298..0x2f2e4` 用 rodata`0x4404` 的完整字符串 `/sys/class/xm_power/charger/charge_interface/input_suspend` 初始化 global C++ String`0x124058`。
2. `0x31e40..0x31ea4` 把 rodata`0x3df0` 的键 `charge_enable` 放在 stack`+0x350`，把 global`0x124058` 的值复制到同一键值记录的 stack`+0x368`。`0x34528..0x34564` 把 stack`+0x50` 起、总长`0x14a0` 的记录以`0x30`步长插入表，即110条。

所以 `setMiChargePath("charge_enable", …)` 并非访问已分析的 sysfs `charge_enable`，也不补该对象缺少 enabled=0 用户参数的问题。tx42 的固定前缀同样针对输入暂停。这里的字符串只解释接口语义，没有给出可试值。

本次确认的是服务初始化中的 class 路径：`/sys/class/xm_power/charger/charge_interface/input_suspend`。它与此前采集的 `/sys/devices/platform/soc/soc:mca_charge_interface/…` 路径不同；本轮没有访问手机，未证明当前运行时 symlink/alias 绑定或初始化后全局映射永不被修改。因此上述“输入暂停”按包内命名和已核对象类别评价，不将路径文字本身当作运行绑定或硬件动作已验证。

[ota-static-key-table.json](../.tooling/bypass-check/stage1/native/ota-static-key-table.json) 保存全部110个 key 名字及局部静态解码结果：99条路径候选、11条明确未解；解码只模拟限定初始化指令，不执行目标 ELF。**除上述手工闭合的关键映射外，自动路径候选未逐条独立审查，不能当作控制值表或允许调用清单。** 未解项逐条保留，不能把“没解出”当作“不存在”。

表内另外有 `smart_batt` / `smart_chg`、`handle_state`、`handle_stop_charging`、`enable_rollback`、`shipmode_count_reset` 等策略/维护名字；这些名字和路径不证明插件 owner 撤销。56项方法名称中没有显式 owner/vote/release 方法，不能因此排除通用键或其他 HAL 的控制契约。本次没有扩展到无限泛扫其他策略函数；这些项作为后续精确资料缺口保留。

已有 [DADA_CHARGE_CONTROL_CHAIN.md](DADA_CHARGE_CONTROL_CHAIN.md) 的有限驱动研究可用于对应名字的包内类别判断，而无需重复扫描：`handle_state` / `stop_handle_charge` 业务store派发 event60；buck分支同时对 input_limit 与 chg_enable 请求0，allow分支撤两票，并涉及 USB power_supply/uevent。这是混合停输入与停充路线，不能成为电池专用旁路。`smart_chg` / `smart_batt` 已核链属于 SOC 保护/限流策略，不能只凭新HAL键名升级为旁路或独立插件释放接口。class路径到这些对象的当前运行绑定仍未验证。对 `enable_rollback`、`shipmode_count_reset` 及11条未解路径仍保留明确未完成状态，不推断它们安全或可用于撤票。

## 三个详细调查候选与一项排除记录

| 来源与固定版本 | 能确认的内容 | 不能确认的内容 / 判断 |
| --- | --- | --- |
| [twrpdtgen/android_device_xiaomi_dada](https://github.com/twrpdtgen/android_device_xiaomi_dada/tree/85c067e6e37f602e71564a9261b02744a00b44b6)，`85c067e6e37f602e71564a9261b02744a00b44b6` | README 指向 `24129PN74G` / dada；`omni_dada.mk` 是 Android 15、`OS2.0.109.0.VOCMIXM`。实际 21 文件是 recovery 设备树 | 不匹配本机 `24129PN74C` / Android 16 / `OS3.0.303.0.WOCCNXM`；没有 MiCharge 文件或 proprietary-files.txt，不能提供 HAL 实现。`extract-files.sh` 默认 SRC=adb，且依赖外部 extract-utils；未执行 |
| [LineageOS/android_hardware_xiaomi](https://github.com/LineageOS/android_hardware_xiaomi/tree/7dd87795288bf6713795f112056b1bf549355c81)，`7dd87795288bf6713795f112056b1bf549355c81` | `interfaces/Android.bp:109-114` 声明 `prebuilt_hidl_interfaces`，名称 `vendor.xiaomi.hardware.micharge@1.0::IMiCharge`；`vintf/xiaomi_framework_compatibility_matrix.xml:248-255` 声明 HIDL 1.0/default、optional=true | 源码档案中没有 MiCharge `.hal`、方法实现或服务二进制；通用兼容矩阵中的可选接口不能证明当前 dada 固件提供 HIDL 服务，也不能证明任何请求撤销能力 |
| [GUF296/MiChargeShim](https://github.com/GUF296/MiChargeShim/tree/3ed8e92b8c8487adff48dc25927cb48b3de0ed29)，`3ed8e92b8c8487adff48dc25927cb48b3de0ed29` | 22 个档案常规文件（含 `.gitignore`）包括替代服务源码、原厂服务 ELF、rc、VINTF；rc 明确 AIDL 实例 `vendor.xiaomi.hardware.micharge.IMiCharge/default`，VINTF version=2 | 没有原始 `.aidl` 或配套 `-V2-ndk.so`；样本没有来源机型、ROM 版本、提取日志、原始包 hash。可以定位文件和方法线索，不能当作当前 dada 的实现或可安装后端 |
| 尝试后排除：[AOSPA/android_device_xiaomi_spes](https://github.com/AOSPA/android_device_xiaomi_spes/tree/2aff7ab0d9f624b178efe1eef5ca197fc2360548)，`2aff7ab0d9f624b178efe1eef5ca197fc2360548` | 两份固定文件 `device.mk` / `proprietary-files.txt`；Charging 段只列 `hvdcp_opti`，另有 batterysecret / 开机前充电程序 | 没有 MiCharge；spes 不是 dada，未继续下载或分析其 blob。经主审同意，以 MiChargeShim 替代此无关候选作为详细调查对象；此项仅保留尝试记录 |

GitHub 仓库搜索能用于发现候选，不能用于证明不存在实现：`android_device_xiaomi_dada` 返回上述 recovery 树，`proprietary_vendor_xiaomi_dada` 返回 0，`xiaomi micharge` 返回 MiChargeShim。部分提交元数据 API 返回 403；固定版本用 `git ls-remote` 和 commit codeload/raw 内容核查，没有将 API 失败推断为源码不存在。

## 原厂样本的实际身份与提取目标

MiChargeShim `samples/original/vendor.xiaomi.hardware.micharge-service` 是 stripped AArch64 动态链接 PIE，解释器 `/system/bin/linker64`，Build ID 为 `bf53669d96f7e4e7b54b458ac37cba4c`。本机 LLVM 的静态 `llvm-readobj` / `llvm-objdump` 确认：

- `DT_NEEDED` 包含 `vendor.xiaomi.hardware.micharge-V2-ndk.so`、`libbinder_ndk.so` 等。
- 导入 `aidl::vendor::xiaomi::hardware::micharge::BnMiCharge` 构造/析构、`getInterfaceVersion`、`getInterfaceHash`、`createBinder` 和 `IMiCharge::descriptor`。
- 可见 `charge_enable` 键及 `/sys/class/xm_power/charger/charge_interface/input_suspend`、`iin_limit` 等字符串。这仅证明二进制含此文字，不证明允许插件 owner、参数语法、撤销语义或硬件效果。

这些检查确认它是 AIDL 原厂服务样本，独立于 README 声明；没有确认其来自 dada。源配置明确以下路径，主审已收到以便选择性提取 ROM：

| 文件 / 位置 | 证据级别与用途 |
| --- | --- |
| `/vendor/bin/hw/vendor.xiaomi.hardware.micharge-service` | 样本 rc 中明确的服务路径；不同于早先 HIDL 搜索候选中的 `@1.0-service` |
| `vendor.xiaomi.hardware.micharge-V2-ndk.so` | ELF 明确的依赖 SONAME；`/vendor/lib64/` 是提取候选位置，包内实际位置需要确认 |
| `vendor.xiaomi.hardware.micharge-service.rc`、`vendor.xiaomi.hardware.micharge.xml` | 样本确有两个文件；ROM 中通常位于 etc/init 和 etc/vintf/manifest，目录仍须以包为准 |
| `vendor/xiaomi/proprietary/micharge/aidl/default/vendor.xiaomi.hardware.micharge.xml` | 样本 VINTF 注释给出的厂商源码输入路径，不代表源码已公开 |

同名、相同 AIDL 版本、相同服务 Build ID 均不足以证明当前构建兼容。后续应以包内 build.prop、VINTF、依赖库/服务 hash、已有匹配模块或远端固件证据共同核对。系统显示增量版本与 OTA metadata 内部版本可能不同，不能默认映射成立。

## 公共 shim 方法线索及其证据边界

MiChargeShim [docs/micharge-analysis.md](https://github.com/GUF296/MiChargeShim/blob/3ed8e92b8c8487adff48dc25927cb48b3de0ed29/docs/micharge-analysis.md#L86) 自述从未随仓库发布的 `vendor.xiaomi.hardware.micharge-V2-ndk.so` 恢复 56 个方法的 transaction 表，含 AIDL hash `f8886a701012d522ea9977256f52b3c47596963d`。其 [service.cpp](https://github.com/GUF296/MiChargeShim/blob/3ed8e92b8c8487adff48dc25927cb48b3de0ed29/shim/micharge/service.cpp#L1604) 用手写 Binder transaction 分发复现此表。两者出自同一作者，不能作为两份独立的原厂签名证据；本次签名的独立验证来自前文官方 NDK ELF。README 仍写“all 16 methods”，与实际表及代码的 56 项不一致，优先记录代码和可核查样本，而不接受 README 的能力范围声明。下表专述 shim 能力，不能覆盖前文官方实现结论。

| 候选方法（作者恢复表） | 替代实现可确认行为 | 原厂链路 / 恢复判断 |
| --- | --- | --- |
| 17 `getInputSuspendState() -> String`；18 `getMiChargePath(String) -> String`；53 `getChargeCommonInfo(String) -> String` | 替代源码读节点或返回默认字符串；generic getter 可因键不同触发 uevent 辅助逻辑 | 不能证明是 MCA voter 快照、没有硬件读副作用、错误有效性、采样年龄或真实物理读数；不纳入实机读取白名单 |
| 42 `setInputSuspendState(String) -> int` | `service.cpp:1678-1687` 仅读入值、记录、返回 0 | 未确认原厂输入暂停对象和参数；输入暂停不自动等于电池充电暂停或旁路，不满足独立电池控制 / 恢复要求 |
| 43 `setMiChargePath(String,String) -> int` | `service.cpp:1658-1675` 普通键只读入、记录并返回 0；仅 `smart_chg` 键进入替代服务自己的智能充电逻辑 | 原厂路径映射、允许的格式、owner 选择、写入错误、部分执行、撤销语义未知。不能拿它封装原 `charge_enable` 并称为完整入口 |
| 54 `setChargeCommonInfo(String,String) -> int` | 与 43 同一分发分支，但没有 `smart_chg` 特殊处理；读入并返回 0 | 样本有 `micharge setChargeCommonInfo` / `charge_enable` 字符串，尚未得到原厂方法到该键/节点/MCA 的可靠映射，不能证明撤销支持 |
| 49 `setWirelessChargingEnabled(bool) -> int` | 替代实现读 bool、记录、返回 0 | 方法针对无线输入，不能当作有线系统供电与电池充电通路的完整控制；无功能恢复证据 |
| 52/56 TypeC/Battery common setter | 替代实现只记录字符串参数、返回 0 | 通用名字不提供安全值表、原始对象或所有权契约；无理由扩大为猜键/猜值试验 |

`smart_chg` 特殊处理也没有提供本机 MCA 释放能力：`requestBatteryChargingEnabledLocked` / `applySmartChargeLimitLocked`（约 793-837 行）根据容量阈值写替代服务自定义属性和 pause/resume 序列；它不调用已分析 MCA 的 `mca_vote(enabled=0)`，不查询 owner 原始状态，不传播原厂 HAL 硬件确认。其 resume 请求是替代策略中的 enable 值，不能代替插件撤票。这里记录它以解释为什么“setter 返回 0”不等于实现了硬件操作，不建议部署替换厂商服务。

## 当前可用接口判断及精确缺项

| 所需能力 | 当前判断 | 缺少的具体资料 |
| --- | --- | --- |
| 确定控制充电对象及参数 | 已确认所查方法42/43走输入暂停；54不操作 | 已取得包内服务/NDK/配置，56签名已核对。仍缺可独立停电池充电且保留系统输入的完整原生对象契约；其他 setter / generic策略键不由本次四方法排除 |
| 查询不分配 owner、不写控制状态 | 53已排除为有效getter；17/18未纳入白名单 | getter单位/错误/时效/副作用与完整MCA/硬件状态关联仍缺，tx18还有维护特殊分支；不能把成功空字符串或shim fallback作为状态 |
| 释放插件 owner 为 `enabled=0` | 所查42/43/54没有补充该能力 | 42固定厂商前缀，43写原值且别名指输入暂停，54空实现；仍需独立owner、撤票、重新聚合和硬件恢复契约。现有sysfs `charge_enable` active allow=1不替代撤销，见 [恢复研究](DADA_NEXT_RESTORE_RESEARCH.md) |
| 写入失败及部分执行恢复 | 43已证实超时不取消；writer软件错误不足 | 缺少晚到写入、短写、close失败、MCA缓存/远端附带动作、并发与进程退出的完整恢复保证，不能把BinderOK/int0或超时作为硬件确认或未执行证明 |
| 停电池充电后保留系统输入供电 | 未证实 | 服务控制需进一步闭合到当前 MCA/provider/PMIC 状态和匹配板级定义，再与经过审查的测量方案对比；HAL 名字不证明物理通路 |

本次取得官方服务和接口库后，在所查方法链及有限固定key表的明确边界结束，不反复扩展未知函数扫描，不把泛用 `setMiChargePath` 当作自动获得的新控制能力，也不借用厂商 owner。若继续原生路线，需要对明确候选的对象与恢复契约补证；若不能取得完整入口，则转入 [下一阶段方案](DADA_IMPLEMENTATION_NEXT_STEPS.md) 中匹配驱动接口资料可行性评估。当前不能从本报告生成合格的手机试写方案。
