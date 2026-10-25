# MiniWatts Bypass Control / SukiSU Ultra 模块

这是独立的纯脚本模块，带本地 WebUI、管理器 Action 和 root CLI。默认关闭，安装不选择配置、不写电源节点、不启用，也不会请求重启。不修改 MiniWatts Android 应用。

**0.2.0 实现了 Xiaomi dada 厂商 FCC 限流策略的独立协议后端，真实硬件旁路供电仍未验证。** 厂商“智能旁路”请求会让驱动按温度表投票限制电池充电电流；表中的 FCC 为正值。低充电功率、请求回显或模块会话存在，均不能证明电池退出供电路径。

## 当前功能与边界

- 本地 WebUI 零联网、无外部依赖，提供状态 / 只读探测、明确配置 dada 策略、手动启用 / 关闭、保护阈值编辑、诊断与命令结果。事件日志可由 root CLI 查看。普通浏览器没有 `ksu` 接口时只能预览，所有控制禁用。
- `configure-mca` 严格检查受支持的 dada 型号、ROM、内核、设备树与 MCA 接口，仅保存配置，不写硬件。配置与启用是两个独立动作。匹配成功不等于当前供电、安全条件已经满足。
- MCA 后端只发送已确认的 1025 / 1024 开启 / 关闭请求，并读取 bit 10。聚合读值 80 等不能写回；恢复的是进入会话前的请求状态，不是整份聚合值。
- 已有原生布尔节点后端仍可由 CLI 显式配置。节点名限定为 `bypass_charging`、`bypass_charging_enable`、`bypass_mode`、`charge_bypass`；这只是防误用规则，不构成已验证的硬件旁路白名单。
- 写前记录恢复信息，写后读回；监控供电和遥测，条件不满足时恢复。遇到写入、恢复失败或异常读值，保留恢复记录并报告错误。

停止充电、FCC 限流、暂停输入和真正硬件旁路是不同操作。模块拒绝将 `input_suspend`、`charging_enabled`、`charge_disable` 配置为原生后端，不试写候选节点，不改节点权限、SELinux，不挂载系统文件。

严格设备白名单用于当前已核对的 dada 环境。换 ROM、内核、型号或设备树后应重新取证并扩展后端验证，不能通过伪造属性或手改配置绕过。实际条件及最终是否接受请求以控制器返回为准。

## 构建与安装

```sh
python3 modules/sukisu-bypass/tests/test_controller.py
python3 modules/sukisu-bypass/build.py
```

版本来自 `module.prop`；当前生成 `modules/sukisu-bypass/build/miniwatts-bypass-0.2.0.zip` 和 SHA-256 文件。ZIP 根目录含 `module.prop`、脚本、`profiles/mca.sh`、`webroot` 和 `skip_mount`，不打包测试。通过 SukiSU Ultra 模块页安装 ZIP，不需要 metamodule。

安装后路径为 `/data/adb/modules/miniwatts_bypass`。监控进程由启动阶段的 `service.sh` 启动；安装后可以手动重启，或由已经授权的 root 终端明确执行以下命令。模块不自动重启，也不自动启用策略。

```sh
su -c 'sh /data/adb/modules/miniwatts_bypass/service.sh >/dev/null 2>&1 &'
```

## WebUI、Action 与 CLI

在管理器打开模块 WebUI。页面启动只读取状态；先点击“只读探测”，匹配后点击“明确选用 dada 厂商 FCC 限流策略”。保存配置后仍处于关闭请求，随后才可点击“请求启用”。启用要求配置可读、监控有效、当前请求为关闭、供电与遥测条件合格。

状态分开显示配置模式、模块会话、驱动请求回显、监控、恢复记录与原始读值。每次操作后重新读取状态；未读、失败、超时或缺失字段显示未知，不能把未知显示为关闭。`power_path=unverified` 始终保留。若驱动请求开启但没有本模块会话 / 恢复记录，界面提示排查其他控制器；关闭按钮只处理本模块的恢复记录，不接管外部请求。

管理器 Action：未配置时只探测并告诉配置途径；可靠状态下，已有会话请求关闭，无会话且请求关闭时请求开启。状态未知、待恢复或外部请求开启时拒绝自动启用，不默认选择 profile。Action 页面“执行完成”不能代替脚本结果，应核对输出和状态。

WebUI 通过 SukiSU / KernelSU 官方三参数 `ksu.exec(command, optionsJson, callbackName)` 接口执行，回调是 `(errno, stdout, stderr)`。命令入口固定为模块 `control.sh`，白名单只有 `status`、`probe`、`configure-mca`、`on`、`off`、`limits`、`diagnose`。页面不接受任意 shell 文本；阈值只允许范围内整数且以固定单引号参数提交。后台也独立检查参数。全部诊断内容以文本显示，不执行返回内容。

参考固定版本：[KernelSU WebUI 文档](https://github.com/tiann/KernelSU/blob/0c0fdb960178fa12d80d6cbcfdcb6ad8be6a865d/website/docs/guide/module-webui.md)、[SukiSU WebViewInterface](https://github.com/SukiSU-Ultra/SukiSU-Ultra/blob/223897c72cc5c23b6fea84d6542aa8ef24459998/manager/app/src/main/java/com/sukisu/ultra/ui/webui/WebViewInterface.kt)。

CLI 示例（已有授权的 root 终端，`probe` / `diagnose` 只读）：

```sh
su -c 'sh /data/adb/modules/miniwatts_bypass/control.sh probe'
su -c 'sh /data/adb/modules/miniwatts_bypass/control.sh diagnose'
su -c 'sh /data/adb/modules/miniwatts_bypass/control.sh configure-mca'
su -c 'sh /data/adb/modules/miniwatts_bypass/control.sh on'
su -c 'sh /data/adb/modules/miniwatts_bypass/control.sh status'
su -c 'sh /data/adb/modules/miniwatts_bypass/control.sh off'
```

`status` 输出 `key=value`，包含 `mode`、`configured`、`session_active`、`request_state`、`raw_node_value`、`watchdog`、`recovery_pending`、阈值、`power_path`，异常时包含 `error`。`request_state=on` 仅表示驱动请求回显，`session_active=yes` 仅表示本模块有会话，不表示真实旁路已生效。`recovery_pending=yes` 表示仍有恢复记录，要看具体状态和日志判断是否需要重试。

原生接口配置模板是 `configure NODE ON OFF ONLINE_NODE`。必须先核对当前驱动的供电路径、接口含义、极性和遥测布局，再填写已验证的绝对路径；不提供默认原生 profile。原生布尔配置不能接到 MCA 聚合协议上。

配置文件按数据解析，绝不 `source`；配置、日志和恢复记录在 root 私有 `/data/adb/miniwatts-bypass`，升级 / 卸载后保留以便排障。活动会话期间不能改配置或阈值。

## 保护与恢复

默认最低电量 30%、最高电池温度 450（45°C，要求低于阈值）。修改时先关闭并确认恢复完成：

```sh
su -c 'sh /data/adb/modules/miniwatts_bypass/control.sh limits 40 420'
```

本例至少 40%、低于 42°C；允许电量 10..95%，温度 300..500（30..50°C）。重新配置会恢复默认阈值。MCA 后端还检查供电类型与板温；界面不能替代这些后端检查。

监控约每 5 秒轮询一次，在断开电源、低电量、过温、坏遥测、其他控制器改动、模块禁用 / 移除等情况下尝试恢复。系统休眠或调度可能延迟轮询，这不是硬件级保护。无法读回或恢复时保留记录并报错；先查看 `diagnose` 与 `/data/adb/miniwatts-bypass/events.log`，再执行 `off` 重试。

重启后不自动开启；旧启动的记录不能写到新内核。正常终止信号会尝试恢复，`SIGKILL`、掉电、内核故障或驱动拒绝写入无法保证恢复。卸载前先 `off` 并确认恢复，管理器可能不会因卸载脚本恢复失败而中止卸载。也不能只凭重启或卸载假定厂商请求已经关闭。

## 验证范围与未完成目标

隔离模拟测试覆盖协议 / 恢复和安全条件，但不能验证充电芯片、固件或真实能量流。WebUI 的普通浏览器预览只验证布局，管理器桥接与实际开关结果应由真机记录核对；最终测试记录以本轮主审查结果为准。

已有只读取证：Xiaomi 24129PN74C（dada，Android 16，OS3.0.303.0.WOCCNXM，SukiSU v4.1.3），核对驱动与设备树，执行 Android shell / SukiSU BusyBox 语法检查和诊断。详见仓库 `docs/SUKISU_BYPASS_RESEARCH.md` 与 `docs/XIAOMI_DADA_BYPASS_RESEARCH.md`。这些取证本身不代表模块开关已成功。

真正旁路仍是未完成的独立目标：需要确认驱动 / 硬件提供的电池隔离与供电路径，在明确电流符号后，使用电池电流、charge counter、电量趋势、外部输入和增加负载的对照实验验证。若当前硬件 / 驱动仅有正 FCC 限流策略，本插件不能把它变成真正旁路，也不能拿瞬时低功率替代证明。
