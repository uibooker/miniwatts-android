# SukiSU Ultra 旁路供电模块调研

调研日期：2026-10-13。范围：官方模块协议、管理器与守护程序源码、Linux 电源 ABI、提供旁路节点的内核仓库源码。这里只记录证据与实现约束，不代表已在目标手机验证。

## 结论

SukiSU Ultra 可以通过普通 KernelSU ZIP 模块启动脚本并提供管理器 Action 按钮；不需要编写 KPM，也不需要替换系统文件。适合先实现 `service.sh` 守护程序、`action.sh` 和命令行控制器。纯脚本模块不需要 metamodule 挂载器。[S1][S2][S3][K1]

真正的不确定性在手机充电驱动。Root 权限允许访问已有驱动接口，不会自动增加硬件电源通路能力。Linux 通用电源 ABI 记录了输入电流、充电电流、充电限制等属性，没有提供一个可直接用于所有 Android 手机的通用旁路开关。可接受的后端必须按具体手机、内核与固件核对，而不是轮流写入疑似充电节点。[L1][X1][X2]

**关键反例：XtraAether 的 `bypass_charging_enable` 名字不能证明真正旁路。** 已找到这个节点的实际驱动实现；它写入充电热限制属性，并不直接操作电池隔离或电源路径寄存器。教程也只给出 15-20 分钟游戏后电量从 45% 上升到 46% 的结果。不能照抄教程路径，把该手机未验证的节点声明为真正旁路。[X1][X2][X3]

## 核对版本与证据级别

| 项目 | 本次读取版本 | 能证明的范围 |
| --- | --- | --- |
| SukiSU Ultra | `223897c72cc5c23b6fea84d6542aa8ef24459998` | 当前上游安装、启动、Action、WebUI 与禁用/卸载流程 |
| KernelSU | `0c0fdb960178fa12d80d6cbcfdcb6ad8be6a865d` | 上游模块与 WebUI 文档；旧管理器版本可能不同 |
| XtraAether 内核仓库 | `3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c`，`oss-5.10.xx` | 该分支的旁路命名节点实现；不是所有小米内核的协议 |
| Linux | 调研日读取 `master` 的 `sysfs-class-power` | 通用电源属性的含义与单位；厂商驱动可能有偏差 |

以上源码是协议证据。实际安装版本、SELinux 限制、节点存在性、固件响应和电源流向仍需要真机验证。项目先前记录的 `24129PN74C` 不足以确认它采用本报告引用的内核驱动；本次父任务检查 ADB 未发现连接设备，不能给出已适配结论。

## 模块协议

### 最小文件与 ZIP 安装

```text
module.zip
├── module.prop
├── customize.sh
├── skip_mount
├── service.sh
├── action.sh
├── uninstall.sh
└── bin/
    └── bypassctl
```

`module.prop` 必须在 ZIP 根目录，不能再套一个外层目录。安装器从 ZIP 根提取它并读取 `id`、`name`、`author`；没有它不会按模块识别。`id` 的格式为 `^[a-zA-Z][a-zA-Z0-9._-]+$`，至少两个字符；`versionCode` 是整数，文本使用 LF 换行。常规字段为 `id`、`name`、`version`、`versionCode`、`author`、`description`；`updateJson`、`actionIcon`、`webuiIcon` 可选。[S1][S2][K1]

安装器先解压并设置默认目录权限 `0755`、文件权限 `0644`，再 **source** `customize.sh`。后者适合通过 `set_perm` 给 CLI 和脚本设置明确权限、建立配置目录。`MODPATH` 是安装目录，`ZIPFILE` 是安装包，`abort` 会执行安装清理。若使用 `SKIPUNZIP=1`，就要自行承担解压与权限；这个模块没有必要使用它。[S1][K1]

ZIP 由 SukiSU Ultra 管理器安装。KernelSU 官方说明模块 ZIP 不适合从自定义 Recovery 安装。普通脚本模块不需要 `META-INF` 安装包装，也不需要挂载 `system`、改写 `vendor` 或安装 `.ko`。[S1][K1]

### 启动、Action 与脚本环境

`service.sh` 在 late_start service 阶段启动，不阻塞系统启动。SukiSU 的 `on_services()` 调用 `run_stage("service", NoWait)`，由它执行活跃模块的 `service.sh`。脚本存在即可由 BusyBox `sh` 执行；建议仍给可直接调用的入口明确的可执行权限。`MODDIR=${0%/*}` 可获得模块目录，避免硬编码模块 ID 路径。[S2][S3][K1]

管理器根据 `action.sh` 的存在判断模块有 Action；`ksud` 的 `run_action()` 执行 `/data/adb/modules/<id>/action.sh`。Action 可以调用与服务共用的控制器实现状态查询和开关，必须把“请求成功”与“真实供电路径已验证”分开显示。[S2]

当前 SukiSU 脚本环境包含 `ASH_STANDALONE=1`、`KSU=true`、`KSU_SUKISU=true`、`KSU_VER`、`KSU_VER_CODE`、`KSU_KERNEL_VER_CODE` 等。不要把 `KSU=true` 当成只能运行在原版 KernelSU 的标志；`KSU_SUKISU` 是这个分支增加的识别字段。保持 BusyBox/POSIX shell 兼容，不依赖 Bash。[S2][K1]

注意 SukiSU 当前 `exec_script()` 的等待分支将 `command.status()` 映射为成功调用，没有检查脚本返回码是否为零。因此 Action 页面“执行完成”不能代替控制器自身打印明确的失败信息及状态。CLI 仍应返回非零退出码，方便直接调用者处理错误。[S2]

### WebUI

KernelSU 的 WebUI 入口是模块下的 `webroot/index.html`，其余 HTML/CSS/JS 同样放在 `webroot` 中，官方 `kernelsu` JavaScript 库提供执行 shell 等能力。SukiSU 管理器也从 `<MODDIR>/webroot` 加载内容并向 WebView 注入名为 `ksu` 的 JavaScript 接口，因此有官方协议与实现依据。[K2][S4]

WebUI 只是可选控制面板，对启动旁路节点不是必需条件。当前方案采用 Action 和 CLI，可减少打包依赖，也避免把不同版本管理器的 WebView 扩展 API 当成底层控制依赖。[K2][S2][S4]

### 与 KernelSU 的差异和版本限制

| 项目 | 当前核对结果 |
| --- | --- |
| 核心模块格式 | `module.prop`、`customize.sh`、`service.sh`、`action.sh`、状态文件保持 KernelSU 协议；无需 SukiSU 专属 ZIP 格式。[S1][S2][K1] |
| 环境变量 | SukiSU 增加 `KSU_SUKISU=true`，仍提供 KernelSU 的通用字段。[S2] |
| 挂载 | 本次 SukiSU main 和 KernelSU main 都使用 metamodule 流程；只执行脚本不需要 metamodule。不要把旧版“内置 OverlayFS 挂载”描述当作现版本要求。[S3][K1] |
| Lua | 当前 SukiSU 在 ARM64 Android 的阶段脚本后额外执行 Lua，Action 没有 shell 时可以回退 Lua；本模块使用 shell，无需绑定这个扩展。[S2][S3] |
| KPM | SukiSU 的 KPM 是另一种内核扩展能力；现有 sysfs 控制只需要普通模块脚本，无需编写或加载 KPM。[S5][S2] |
| 新 API | 当前文档包含模块配置、晚加载和可选图标能力；为了兼容已安装的旧 SukiSU，不宜强制依赖这些可选的新能力。[K1][S2] |

## 禁用与卸载是否能恢复节点

当前 SukiSU `disable_module()` 的行为是创建 `disable` 文件并重新生成下次启动的 init RC；函数没有终止正在运行的 shell 守护程序。`foreach_active_module()` 会跳过带 `disable` 或 `remove` 标记的模块，约束的是后续启动阶段。不能假定管理器禁用时自动运行模块恢复逻辑。[S2]

`uninstall_module()` 先创建 `remove` 标记，当前不立即删除目录。下次常规 `post-fs-data` 的 `prune_modules()` 扫描这个标记，先调用 `uninstall.sh`，再清理配置和删除目录；因此运行卸载脚本时 `remove` 标记仍在。安全模式会提前返回并跳过这轮清理，不能承诺任何重启情形都会立刻执行卸载脚本。[S2][S3]

实现宜同时具备以下恢复路径：

- 守护程序定期检查 `disable`、`remove`、模块目录存在性；看到标记后恢复已保存的节点原值并退出。
- `uninstall.sh` 做同一项尽力恢复，不把“先标记卸载”当作需要拒绝恢复的条件。
- 显式 `off`/`restore` 和可处理的 `TERM`/`INT` 信号执行恢复。
- 写入前持久化原值，并通过互斥保护，避免两个控制入口丢失恢复状态。

上述是对源码流程的实现建议，不是管理器保证。如果采用五秒轮询，恢复有最多约一个轮询周期再加系统调度的延迟。SIGKILL、守护程序崩溃、断电、sysfs 节点消失、驱动拒绝写入或厂商进程覆盖状态时，脚本无法保证立即恢复。外部分支若在禁用瞬间直接杀守护程序，也不能靠轮询保证恢复。因此用户文档应明确说明“恢复尽力而为”，不能承诺无条件恢复。[S2][S3]

## 真旁路、停止充电与暂停输入

这里将目标行为定义为：外接电源承担系统负载，电池不持续净充电，也不在正常稳态持续承担主要放电负载。电池可能仍连接并承担负载瞬态；“完全电气隔离电池”是更强的硬件主张，需要额外证据。

| 接口/现象 | 能判断什么 | 不能据此判断什么 |
| --- | --- | --- |
| `input_suspend` 或 USB input suspend | 对输入通路的暂停请求；SMB5 实现会设置 `USBIN_SUSPEND_BIT`。[X4] | 不能当作外接电源持续向系统供电的保证，暂停输入可能使系统转向电池。 |
| `charging_enabled`、`charge_disable` 等厂商字段 | 可能表示禁止电池充电，具体依赖驱动实现。 | 不能仅凭名字断言已隔离电池或系统负载由充电器承担。 |
| `charge_control_limit`/热限制 | 通用 ABI 将它描述为为热管理或电池健康限制充电速率；厂商实现可以用热等级而非直接电流。[L1][X3] | 减小充电电流不等于真旁路；不能猜测单位或写任意数字。 |
| `current_now` | 瞬时电流，标准 ABI 单位为微安，USB 与 battery 的意义不同。[L1] | 单个接近零的读数不能证明持续稳态旁路；厂商符号可能有偏差。 |
| 电量不变、状态 `Not charging` | 可作为观察结果。 | 电量分辨率、负载、温度和充电控制都影响结果，不能单独证明电源路径。 |
| 明确的设备旁路布尔节点 | 可表达驱动已接收请求，并可检查节点读回。[X2] | 节点名称、布尔读回或接口文档均不能替代实现审查和真机功率验证。 |

`charging_enabled` 等字段没有在本次报告中确认一个统一实现，不应作为可写候选。不同驱动可能将停止电池充电和维持系统输入分开，也可能联动；必须追到当前手机的具体驱动和固件协议。对于无法确认的接口，应报告 `unsupported` 或 `power_path=unverified`，不要显示“真实旁路已启动”。[L1][X2][X4]

## XtraAether 节点反例的具体证据

仓库教程声称针对 POCO F5（Marble）、Snapdragon 7+ Gen 2、AOSP ROM 和指定 XtraAether 内核，通过 `/sys/class/qcom-battery/bypass_charging_enable` 的 `1`/`0` 开关使用旁路。教程还要求替换 `qti_battery_charger_main.ko`，说明这不是纯 Root 就普遍可用的能力。[X1]

已核对同一仓库驱动 `qti_battery_charger_xiaomi.c`：

1. 第 82-83 行定义静态 `bypass_charging_enabled=0` 和 `bypass_thermal_level_percent=89`。
2. 第 2003-2008 行的 `show()` 只返回静态变量，不读取实际充电器状态。
3. 第 2025-2031 行开启时计算 `thermal_level = num_thermal_levels * 89 / 100`，再写 `BATT_CHG_CTRL_LIM`。
4. 第 2039-2040 行关闭时向相同属性写 `0`，并没有保存此前热限制等级。
5. 第 2048 行写入成功后才更新静态变量；第 5329-5330 行确实注册相关 sysfs 属性，所以它不是只有教程而没有实现的节点。[X2]

同分支 `qti_battery_charger.c` 把 `BATT_CHG_CTRL_LIM` 映射到 `POWER_SUPPLY_PROP_CHARGE_CONTROL_LIMIT`，`battery_psy_set_charge_current()` 把它用来设置热限制等级。驱动将请求交给远端电池充电固件；仅靠这个源码无法看到固件内部最终电流与硬件路径。因此这里能证明的是 **该节点发出热限制请求**，不能证明完全没有充电，也不能证明电池被硬件旁路。[X3]

教程实测文字明确写“charging is significantly slowed”及 45% 到 46%。这支持“限制充电”的解读，没有充分验证“直接由充电器供电、不充电”。此外，教程“温度过高自动关闭旁路”的主张，在上述 `show/store` 与该状态变量的使用中没有对应的温度检测或自动清零逻辑；不能照抄为模块的安全保证。[X1][X2]

**因此本模块不应内置这个路径为已验证后端，也不应自动写它。** 若用户后续提供该驱动的其他版本，也需重新核对。反例路径用于解释名字不可靠，不是配置建议。

## 设备验收与实现边界

发布能执行节点请求的通用模块，与确认某一手机真正旁路是两个独立验收项。

通用模块应默认无已配置后端，扫描只读；配置只能明确指定一个已审查的 sysfs 布尔节点及启用/禁用值。首次控制前保存原值，写后核对读回，读回失败不能报成功。拒绝 `input_suspend`、充电电流和热等级作为真旁路默认替代。插件安装与开机不应因节点名称匹配而自动写入。[S1][S2][L1][X2][X4]

目标手机需要另外收集并核验：

- 精确设备型号/代号、ROM 与 build、内核版本、SukiSU 管理器与内核版本。
- 节点真实路径、驱动 `show/store` 与最终寄存器/固件命令的语义；不只检查 `ls` 能看到文件。
- 实际 SELinux 权限、写入成功、正确读回、厂商服务是否覆盖节点。
- 外接电源在线、USB 输入功率、持续采样的电池电流/电压/电量、温度；先在普通模式确认电流符号及单位，再做相同负载的 A/B 对比。
- 轻/重负载、充电器功率不足、插拔线、温度和电量边界、重启、禁用、卸载、异常退出时的行为与恢复。

USB 有输入且电池电流持续接近零是支持旁路的证据，仍应结合电源路径/固件实现，不能靠一张读数截图给“硬件完全隔离”结论。没有这轮验收时，所有成功状态保持 `power_path=unverified`，并准确表述为“已写入启用请求”。[L1][X2][X3]

## 来源

- [S1] [SukiSU Ultra installer.sh（固定提交）](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/userspace/ksud/src/installer.sh)：第 371-383 行提取与读取 module.prop，第 410-427 行解压、默认权限及 customize.sh。
- [S2] [SukiSU Ultra module.rs（固定提交）](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/userspace/ksud/src/module.rs)：第 50-99 行 ID 与脚本环境，第 131-165 行活跃模块过滤，第 279-378 行脚本启动，第 563-613 行卸载清理，第 955-1029 行卸载、Action 与禁用，第 1260-1290 行 WebUI/Action 探测。
- [S3] [SukiSU Ultra init_event.rs（固定提交）](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/userspace/ksud/src/init_event.rs)：安全模式提前返回、post-fs-data 清理模块、metamodule 挂载与 service/boot-completed 阶段。
- [S4] [SukiSU Ultra WebViewHelper.kt（固定提交）](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/manager/app/src/main/java/com/sukisu/ultra/ui/webui/WebViewHelper.kt)：第 60 行有效模块检查，第 91 行 webroot，第 192 行注入 ksu 接口。
- [S5] [SukiSU Ultra 官方 README（固定提交）](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/docs/README.md)：KernelSU 分支定位与 KPM 特性。
- [K1] [KernelSU 官方 Module guide（固定提交）](https://github.com/tiann/KernelSU/blob/0c0fdb960178fa12d80d6cbcfdcb6ad8be6a865d/website/docs/guide/module.md)：普通模块、ZIP、安装变量、脚本环境、metamodule 与启动阶段。
- [K2] [KernelSU 官方 Module WebUI（固定提交）](https://github.com/tiann/KernelSU/blob/0c0fdb960178fa12d80d6cbcfdcb6ad8be6a865d/website/docs/guide/module-webui.md)：webroot/index.html 与 JavaScript API。
- [L1] [Linux sysfs-class-power ABI](https://github.com/torvalds/linux/blob/master/Documentation/ABI/testing/sysfs-class-power)：current_now、online、charge_control_limit 等属性的标准语义；读取日期为 2026-10-13。
- [X1] [XtraAether BYPASS CHARGING TUTORIAL（固定提交）](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/BYPASS%20CHARGING%20TUTORIAL.md)：声明的适用设备、节点、安装前提与实测。
- [X2] [XtraAether qti_battery_charger_xiaomi.c（固定提交）](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/qti_battery_charger_xiaomi.c#L2003)：旁路命名节点的静态 show、热限制 store 与属性注册。
- [X3] [XtraAether qti_battery_charger.c（固定提交）](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/qti_battery_charger.c#L1259)：BATT_CHG_CTRL_LIM 映射与热限制等级设置。
- [X4] [XtraAether smb5-lib.c（固定提交）](https://github.com/Gustyx-Power/android_oss_kernel_xiaomi_sm8450/blob/3fcd84e2bd5c7600394dd1d4ffe2ae60bf355e4c/drivers/power/supply/qcom/smb5-lib.c#L711)：USB input suspend 设置 USBIN_SUSPEND_BIT。
