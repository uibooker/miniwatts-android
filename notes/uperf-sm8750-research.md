# yc9559/uperf 对 SM8750 / Android 16 的独立研究

## 结论与范围

**现有 ELF 有配置适配空间，但不宜作为这台 Android 16 手机的新项目基础。更可靠方案是借鉴公开配置设计，自研可构建的运行时。** 公开仓库有 Apache-2.0 的脚本、模板和文档，但所查树没有控制器 C++ 实现或构建工程；发布包里的核心是实现源码未公开的二进制。不能声称可以从该仓库修改、编译出 Android 16 核心，也不能仅增加 SM8750 名称后直接刷入原模块。

目标条件及父代理补充的实机只读证据：Xiaomi 15，`24129PN74C/dada`，SM8750，`OS3.0.307.0.WOCCNXM`，Android 16/API 36，kernel `6.6.118 android15 aarch64`，KSU root，SELinux Enforcing。本研究代理未访问手机、未运行上游 ELF/脚本/BusyBox/注入器，未写实施源码；以下设备观测由父代理提供，未在本代理重跑。

| 实机观测 | 能得出的结论与边界 |
|---|---|
| 实际页大小 4096 | 当前机器不受旧 ELF 4KB 对齐的直接阻塞；不能据此证明完整装载/运行成功。未来16KB设备另行考虑 |
| policy0：CPU 0–5，384000–3532800 kHz，16档 | 已确认第一频率域为六核，适配不能沿用旧4+3+1 |
| policy6：CPU 6、7，1017600–4320000 kHz，16档 | 已确认第二频率域为双核，模型/频率限制应以双policy与实际离散档位处理 |
| governor为walt；cpuset/cpuctl v1与cgroup2混合 | 不能以“Android16必为纯v2”否定适配；仍须验证旧ELF实际依赖的文件、监听行为及权限 |
| 初始最大频率上限2400000/1958400 kHz | 只是观测时的约束，原因未确定，不能归因于温控；硬件最大频率不等于当前可长期使用上限 |
| mi_thermald、vendor thermal/power/perf等服务running | 原厂控制器在运行；必须考虑多方约束，不能用上游旧脚本盲目停服务 |
| 存在com.sulisong.uperf应用进程；外部目录cur_powermode为performance | 只证明有相关命名进程及模式文件，未确认来源、版本、与yc9559官方ELF的关系或是否实际控制频率；不能算官方核心已适配证明 |

本次整体核查未执行上游ELF、未写调度节点。父代理确认误开的实施草稿已清空；本报告仅记录可行性研究，未来实现另开项目。

固定源码提交：[49c7f877d6dac0e33c90f81725e0e708098051bd](https://github.com/yc9559/uperf/tree/49c7f877d6dac0e33c90f81725e0e708098051bd)，只读克隆 `/tmp/uperf-upstream-review-202610`。成品核查来自作者官方 [DEV 22.09.04](https://github.com/yc9559/uperf/releases/tag/dev-22.09.04) 主附件，不是自动生成的 Source code ZIP。

## 许可证与真正公开范围

| 对象 | 核实结果 | 含义 |
|---|---|---|
| 根 LICENSE | Apache-2.0，作者 Matt Yang 2021–2022 | 允许依条款使用、修改、分发 Source/Object；不保证获得核心源码 |
| 外围 shell | 有 Apache 文件头，源码公开 | 能改包装层，但其行为不自动适合新 ROM |
| 配置说明、模板 | 公开 JSON 接口及说明 | 能重写模型、模式和部分节点映射 |
| 控制器核心 | 所查 Git 树无 C/C++ 实现或构建工程，成品有 `bin/uperf` | 不能重编译、修复内部路径、监听器或平台 ABI |
| SfAnalysis/SsAnalysis | 官方单独发布附件，本次未解包这两份 | 不声称已审其许可、核心实现或 Android 16 注入兼容性 |
| 第三方组件 | NOTICE 列出 jpcre2/json/pcre2/scnlib/spdlog/BusyBox 等 | 根 Apache 不替代各组件许可，尤其 BusyBox GPLv2 |

证据：[完整树](https://github.com/yc9559/uperf/tree/49c7f877d6dac0e33c90f81725e0e708098051bd)、[LICENSE 授权/分发](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/LICENSE#L67-L129)、[作者声明](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/LICENSE#L179-L191)、[脚本文件头](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/libuperf.sh#L1-L15)、[NOTICE 组件清单](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/NOTICE#L6-L13)、[BusyBox GPLv2](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/NOTICE#L439-L616)。

官方主 ZIP 中 LICENSE/NOTICE 与所查提交字节一致，未见该 ZIP 另设 uperf 核心禁用/禁分发条款。**“源码未公开”不等于“许可证必然禁止分发 ELF”**，也不应把 Apache 仓库标签当所有独立附件授权证明。若复用/分发公开文件，保留 LICENSE、NOTICE、作者与归属，并突出标明修改；不暗示上游背书。若携带 BusyBox，应满足其对应源码提供义务；独立工具聚合不意味着自研运行时自动变 GPL。自研方案最省歧义：不携带旧 ELF、BusyBox 或注入附件，只保留确实复用内容的必要许可与归属。

## 版本与官方二进制证据

本次 [GitHub releases/latest](https://api.github.com/repos/yc9559/uperf/releases/latest) 返回 **v1-20200516**；[发布列表](https://api.github.com/repos/yc9559/uperf/releases?per_page=5)较新的 **dev-22.09.04** 标为 prerelease，模块版本 `v3(22.09.04)`。不能用 `/latest` 自动选包，也不要混用 v1/v3 格式。官方 v3 支持列表到骁龙 8 Gen 1/8+ Gen 1 等，没有 SM8750，见[发布说明](https://github.com/yc9559/uperf/releases/tag/dev-22.09.04)和[更新日志](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/changelog.md#L3-L19)。

静态解析主附件，未运行：

- ZIP 74 项，CRC 检查通过，SHA-256：`f5872b5a77dd0ca810bdf6814718c89673821ed797092b4265c9f7c7f7944e25`。
- `bin/uperf` 1,461,512 字节，SHA-256：`f1265757009ff0c85dd8587d9e7bfcf5e51d10d36fe5e1341688215ae1fb49d8`。
- ELF64/AArch64、ET_DYN，解释器 `/system/bin/linker64`；不是完全静态程序。
- DT_NEEDED：`libm.so`、`libdl.so`、`libc.so`。字符串还有 `/system/lib64/libandroidfw.so`、`/system/lib64/libandroid.so`，因此不能只看 DT_NEEDED 就排除运行时加载/私有 ABI。
- 四个 PT_LOAD 的对齐为 `0x1000`，即 4KB。父代理实测当前手机页大小4096，因此这不是当前机器的直接阻塞，仍未证明旧ELF成功运行；对未来16KB设备另作兼容加载评估。JSON不可能重新链接ELF。
- 字符串有固定 `/dev/cpuset/{top-app,foreground,background,restricted}/tasks`、旧 CPU/厂商频率路径、`ro.build.version.release`。这是静态依赖线索，不能据此断言每条路径必执行或 Android 16 必然崩溃。

以上是对[官方 release 附件](https://github.com/yc9559/uperf/releases/tag/dev-22.09.04)做结构/字符串检查的结果，不是可复现构建证明。

## 配置接口及边界

以 [v3 config/README](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/config/README.md)和 [template](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/config/template.json)为准。根 README 自己说明部分情景介绍不适用 v3；旧 `platform/clusterCpuId`、旧 hint 和旧 knob 类型不能直接搬入 v3。

| 区域 | 可配置内容 |
|---|---|
| `modules.switcher` | 模式文件 `switchInode`、应用规则 `perapp`、hint 时长；基础模块不可关闭 |
| `modules.input` | evdev 输入监听与触摸/手势阈值 |
| `modules.sfanalysis` | 能关闭 SF 信号分析，不必为主程序先安装旧注入模块 |
| `modules.cpu.powerModel` | 按集群顺序的 `efficiency/nr/typicalPower/typicalFreq/sweetFreq/plainFreq/freeFreq` |
| `modules.sysfs.knob` | 逻辑键到任意绝对节点的通用写入映射 |
| `modules.sched` | CPU 编号组、按场景 affinity/prio、进程/线程正则规则 |
| `initials` | 动态参数默认值 |
| `presets` | 模式中 `* / idle / touch / trigger / gesture / junk / switch` 的层叠参数 |

来源：[switcher](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/config/README.md#L12-L71)、[input/SF/CPU模型](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/config/README.md#L89-L134)、[sysfs/sched](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/config/README.md#L136-L260)、[动态参数与presets](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/config/README.md#L263-L375)。

需要注意：

1. perapp 匹配大小写敏感全包名，必须有 `*` 默认与 `-` 熄屏兜底。
2. `modules` 是实例化时读取一次的静态区。不能因支持配置重载就假定修改模型/路径/启停可安全热生效，应按重启处理。
3. 通用 sysfs knob 不等于能替换闭源 CPU 写入器或 cgroup 监听器内部路径。
4. `prio=1..98` 是 SCHED_FIFO，模板有 96–98 的实时类，不是普通 nice。新方案默认不照抄实时规则。
5. 功耗上限依赖模型估算，不是实测整机瓦数。不能只复制 `slowLimitPower/fastLimitPower` 后声称达到某个功耗。

实际启动形态见 [libuperf.sh:27–45](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/libuperf.sh#L27-L45)：程序参数为配置路径，`-o` 指定日志；[模式切换](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/powercfg_main.sh#L18-L27)就是写文件字符串。没有文档证据时不能猜测 `--dry-run/--check/--version` 无副作用，本次未调用。

## Android 16 / SM8750 的实际障碍

**平台别名只是安装门槛。** [libsysinfo.sh](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/libsysinfo.sh#L104-L171)映射到 taro 等旧平台，没有 SM8750；[setup.sh](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/setup.sh#L46-L71)找不到配置即中止。绕过不代表核心适配成功。

官方8+ Gen1配置为4+3+1；父代理已确认目标为policy0=CPU0–5、policy6=CPU6/7的6+2双频率域，各有16档。不能复制旧 `0..3/4..6/7` CPU组、最大频率或Cortex-A的efficiency/功耗模型。v3模型支持数组，但闭源核心对目标walt、频率入口和任务列表的实际行为不能从配置文档保证。当前观察到的2400000/1958400 kHz上限原因未确定，不能视为原厂额定最大值，也不能判定温控限频。

**旧cgroup与私有平台接口。** 源码直接操作 `/dev/cpuset/.../tasks`、stune、cpuctl uclamp，见 [libcgroup.sh](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/libcgroup.sh#L32-L71)及[初始化](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/powercfg_once.sh#L26-L58)。父代理已确认设备采用cpuset/cpuctl v1与cgroup2混合，不能以纯v2不兼容为由否定旧ELF。但这不证明所有硬编码路径、读取内容、变更通知和跨进程权限满足核心要求；若核心必需路径不存在，JSON不一定能改，不应bind-mount假路径凑通。

**SF hook与权限。** 上游[支持声明](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/README.md#L14-L19)是 Android 6–12；[SF说明](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/README.md#L160-L170)承认 native hook 受版本、OEM、SELinux 约束。不把旧 SF/SS 模块作为 Android16 起步条件。KSU root UID 不等于 init/SF 的 domain，不保证 evdev、sysfs、跨进程亲和性/实时调度均允许；应具体记录拒绝后降级或中止，不能靠全局 permissive。

**外围脚本会直接改变系统，不可原样继承。** [initsvc.sh](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/initsvc.sh#L26-L37)覆盖 `/data/powercfg*`，无条件运行 powercfg_once 后启动核心。后者清 top-app/移动系统任务，清stune/uclamp、禁hotplug/core_ctl、尝试禁msm_thermal、大范围清boost，停perfservice/miuibooster/perfd/power HAL，还 kill mi_thermald 并写 thermal_message。部分服务随后重启不等于完全不动系统服务/温控。见[控制节点](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/powercfg_once.sh#L82-L118)、[服务/温控与实际执行顺序](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/powercfg_once.sh#L170-L253)。

[lock_val/mask_val](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/libcommon.sh#L22-L54)会改owner/权限、bind mount遮挡节点；没有逐项原值快照。[卸载脚本](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/uninstall.sh#L37-L50)清目录及所有 `/data/powercfg*`，没有相应完整恢复，可能影响其他工具。不能宣称卸载必定恢复原厂。

配置默认在外部存储并等待解锁，[pathinfo.sh](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/pathinfo.sh#L18-L28)、[wait_until_login](https://github.com/yc9559/uperf/blob/49c7f877d6dac0e33c90f81725e0e708098051bd/magisk/script/libcommon.sh#L98-L112)；新实现应使用 root 私有目录，外部只做显式导入导出。

## 最小可靠开发方案（仅计划）

### 推荐：独立开源兼容运行时

1. 把“兼容”限定为自己的四档模式与 hint 参数概念，或明确列出的 JSON 子集；未经验证不声称 uperf v3 全格式/算法等价。保留实际复制文件的 Apache 许可/NOTICE，核心源码、构建与二进制应可对应。
2. 做 root 私有配置/运行目录、单实例锁、设备能力探测与节点白名单；根据实际 policy/频率表生成 SM8750 数据，不写死 4+3+1。模式切换串行、最小频率不高于最大频率，写后读回，失败状态可见。
3. MVP只做手动四档 CPU 频率上限与一键恢复。每个修改前持久保存原值/权限，退出/停用/卸载有恢复路径；保留系统 governor、PowerHAL、温控。不能用 chmod 锁死和循环抢写掩盖系统覆盖，冲突要展示。
4. 主机测试自己配置的 schema/引用/CPU集合、真实频率范围的计算、读写失败回滚、重复切换及崩溃恢复。硬件接入后再验证实际频率约束和状态一致性。
5. 再逐项加入前台应用、屏幕状态、输入 hint；优先使用可验证且适合Android16的公开信号，每项可关闭/超时降级。首版不注入SF、不提升系统关键线程为FIFO、不修改温控/热插拔。
6. 最后才引入实测标定的 6+2 功耗模型、自动策略和线程亲和性。通过固定亮度/刷新率/温度/电量的原厂对照评估帧率、启动耗时、能耗和待机，不能以运行成功代替省电证明。

### 若仍试验原 ELF，应先跨过的门槛

仅作为可舍弃实验分支，不能依赖旧包装脚本。固定上列哈希，改用最小启动层和私有配置；先验证目标页大小/装载器、核心固定cgroup路径、Android内部ABI和基本解析/退出能力。所有可选模块关闭也不能证明无副作用，因为核心无源码；不能把第一次运行包装成只读检测。

若要继续，先 SF/sched/cpu/sysfs 全关，仅保留必要 switcher/log，随后每次只打开一个已核实功能并记录实际行为。最小功能可尝试用通用 sysfs writer 驱动手动模式；CPU模块的功耗模型必须另行校准。若遇到不可配置的内部ABI/cgroup/频率接口错误，立即停止旧ELF路线，不能通过改系统linker、全局SELinux、伪造挂载或停温控解决。

这一路线可交付的最多是“旧二进制的实验配置适配”；对现代设备长期维护来说，自研可构建核心更可靠。

## 核查边界

仅查当前固定树、官方GitHub API和主ZIP，未审全部Git历史，也不声称作者其他仓库绝无相关源码。SfAnalysis/SsAnalysis附件未审。报告另整合父代理提供的设备只读快照，本研究代理没有上机操作；现有同名应用/模式文件不构成官方二进制控制能力证明。Android官方[cgroup文档](https://source.android.com/docs/core/cgroups)及[16KB页说明](https://developer.android.com/guide/practices/page-sizes)在本环境抓取失败，列作后续核对入口，不作为已读取的具体兼容性证据。没有上游程序运行结论、功耗或帧率验证，也没有修改或编译uperf核心。
