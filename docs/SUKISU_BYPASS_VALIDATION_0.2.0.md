# SukiSU 模块 0.2.0：审查与实机验证

日期：2026-10-13。模块：`miniwatts_bypass`，版本 `0.2.0`，versionCode `3`。

## 结论

厂商 FCC 限流策略的请求、读回和关闭恢复已在连接的 Xiaomi dada 手机上通过验证。**真正的硬件旁路供电尚未验证**，本轮不把请求位、瞬时电流或低充电功率作为电池隔离的证据。

初次官方安装成功后，SukiSU 将完整文件放在 `/data/adb/modules_update/miniwatts_bypass`，待重启激活。用户随后重启，已确认模块正常激活、监控运行与管理器 WebView 真实只读桥接；详见下方重启复验。Action 开关点击尚未由主代理再次测试。

## 分工与审查修复

三个子代理分别负责控制后端、WebUI / Action / 打包、独立黑盒测试。界面代理另对控制与恢复代码进行了只读交叉审查；主代理复核协议、异常恢复、手机工具兼容性，并执行最终实机验证。

本轮审查发现并修复：

- 首次启动恢复失败或普通保护恢复失败后，需要持续重试，并阻止新会话。现在所有恢复失败统一保留 journal 和 pending 标记，直到恢复成功。
- 原目录锁在创建后、写入 owner 前被强杀可能永久阻塞。改为内核 `flock`，锁文件 inode 不删除，进程退出释放内核锁。
- Android mksh 默认对额外文件描述符设置 CLOEXEC；BusyBox 调用需显式 `9>&9`。实机验证直接调用会报 Bad file descriptor，加重定向后取得、竞争拒绝和释放均符合预期。
- 手机 BusyBox 不支持 `flock -w`。实现改用 `-n 9`，最多四次尝试和三次一秒等待；测试模型也拒绝不支持参数。
- 守护进程首次加载 MCA 实现前删除模块目录会失去恢复实现。现在启动时预加载，目录消失后仍可处理已有记录。
- 原驱动绑定检查不够严格。现在核对双向真实路径必须对应 `/sys/bus/platform/drivers/smart_charge` 与目标设备目录。
- WebUI 在状态附带兼容错误时仍允许启用与保存阈值。现在错误状态阻止这些操作，待恢复记录仍允许关闭重试。
- 正常终止的恢复重试最终成功，应返回成功；之前失败过一次不再造成错误退出码。

最新只读交叉审查和主审未发现新的阻塞性代码问题。该结论受以下验证范围限制。

## 自动化验证

```sh
python3 -m unittest discover -s modules/sukisu-bypass/tests -p 'test_*.py'
node --test modules/sukisu-bypass/tests/test_webui.js
python3 modules/sukisu-bypass/build.py
```

主代理独立执行：49 项 Python 测试通过，耗时 78.393 秒；9 项 WebUI 黑盒测试通过，无失败、无跳过。测试代理另执行完整套件，并在最后同步 shell 描述符继承后重验 19 项原生后端测试，全部通过。

覆盖独立厂商命令模型、聚合值禁止写回、其他功能位变化的保留、外部预置请求拒绝接管、指纹和绑定不匹配、保护条件、失败写入与读回、恢复失败保留记录、配置丢失和记录损坏、跨启动不写旧记录、并发操作、持锁强杀、恢复重试、重复守护进程、模块目录删除。模拟器不构成硬件充电效果验证。

WebUI 黑盒验证官方三参数 `ksu.exec` 调用、命令白名单、阈值注入防护、无桥接时禁用、失败和超时后的未知状态、外部请求和恢复入口。离线桌面与 360×800 布局由界面代理检查，无横向溢出。初次验证时未完成实际管理器 WebView 调用；后续重启复验已验证状态刷新和只读探测调用。

全部 shell 文件通过宿主语法检查；打包脚本生成固定时间戳 ZIP，包含后端和本地 WebUI，不包含测试模型。手机临时解包后，运行 Android `/system/bin/sh -n` 与 SukiSU BusyBox `sh -n`，均通过。

## 实机验证

环境：Xiaomi `24129PN74C` / `dada`，Android 16，ROM `OS3.0.303.0.WOCCNXM`，内核 `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k`，SukiSU Ultra `v4.1.3` / `40796`。

只读 `probe` 返回 `mca_supported=yes`；未配置 `status` 返回 `configured=no`，不创建状态目录。测试前聚合请求值为 `80`，bit 10 关闭，无已有本模块会话。

在临时目录运行同一安装包中的控制器及有退出清理的验证脚本。脚本显式保存 profile、启动临时守护进程、请求开启、立即关闭，并停止自身创建的守护进程。保护阈值为至少 30% 电量、低于 45°C，启用时电量 79%、电池温度 36.2°C、板温 38.0°C、USB 在线、无线离线。

| 阶段 | 聚合读值 | 模块会话 | bit 10 请求 |
| --- | --- | --- | --- |
| 原始状态 | 80 | 无 | 关闭 |
| 请求开启后 | 1104 | 存在 | 开启 |
| 关闭恢复后 | 80 | 无 | 关闭 |

只有 `1025` 和 `1024` 作为开启 / 关闭命令，未写回聚合值 `80`。恢复后没有 journal 或 pending 标记，临时守护进程已退出。请求与恢复发生于同一秒，未进行持续负载或供电效果实验。

然后通过 `/data/adb/ksu/bin/ksud module install` 安装最终 ZIP，命令返回 `Module installed successfully!`。安装后的再次状态核对为：

```text
configured=yes
session_active=no
request_state=off
raw_node_value=80
mode=xiaomi-mca-policy
policy=fcc_limit
recovery_pending=no
watchdog=unavailable
power_path=unverified
```

适配配置和默认保护阈值保留，当前无运行监控、无开启请求。安装包自身仍默认不配置、不启用；本机配置由本轮显式验证保存。未更改其他充电节点、权限或 SELinux，未操作 `charge_enable`、`input_suspend`。

最终包：`modules/sukisu-bypass/build/miniwatts-bypass-0.2.0.zip`。

```text
SHA-256: 7cf07667bf87781a5ff1b5b2923f65ab28d969cd99b94844cd15b3da3c2e2177
```

电脑产物与手机临时安装包的 SHA-256 一致。

## 用户重启后的复验

2026-10-13 12:37–12:40（Asia/Shanghai），用户告知已重启后执行检查：

- 完整模块文件已进入 `/data/adb/modules/miniwatts_bypass`，版本仍为 0.2.0。安装的 control、lib、MCA profile 三个文件 SHA-256 与电脑审查版本相同。
- 启动 ID 为 `0188ddee-e02d-45fe-9098-d5a48e46d2d1`。启动阶段的守护进程 PID 2962 正常运行，心跳持续更新。
- 检查开始时已有本次启动的模块会话，聚合请求值为 1104，bit 10 开启，journal 记录原始逻辑值 0。事件日志在启动约 61 秒记录一次 `Vendor smart bypass FCC-limit request accepted`。用户随后明确确认“是我点的”，因此本次开启与用户手动操作对应，不将它作为重启自动开启的证据；具体点击的是 WebUI 还是 Action 未进一步确认。
- 无 `recovery_pending` 重试标记，status 无 error。`recovery_pending=yes` 此时来自正常会话的 journal，表示回滚信息存在；当前页面把它显示为“恢复记录仍在”，容易与恢复失败混淆，属于文案问题。
- 在 SukiSU 实际模块页打开 WebUI，页面正确显示限流模式、开启请求、运行监控和原始值 1104。点击刷新后时间与状态更新；点击只读探测后显示匹配 dada 厂商 FCC 策略，证明真实 WebView 桥接可以执行并接收 status / probe 结果。
- 最后核对电量 75%、电池温度 35.5°C、板温 36.8°C，USB 在线、无线离线，请求仍为开启。此次只做只读检查，没有点击启用、关闭或 Action，没有改变用户已有会话。

## 仍待完成的验证与能力边界

1. 管理器 Action 点击和 WebUI 开关按钮的完整实机循环。重启激活、启动阶段监控、实际 WebView 状态刷新与只读探测已确认；为保留检查开始时已有的开启会话，此次没有切换开关。
2. 真正旁路供电的硬件与驱动证据，以及明确电流符号后，在稳定电量、外部供电、不同负载下的持续对照测量。目前的表项是正 FCC，不能据此保证电池停止充电或退出供电路径。
3. 物理拔线、过温、休眠、重启与卸载的实机故障场景；相关软件恢复逻辑已做隔离模拟测试，但未在手机上制造这些条件。

严格型号 / ROM / 内核 / 磁盘驱动哈希和绑定检查能降低误用，但不能证明内存已加载 ELF 恰好等于磁盘文件。另一个控制器在同一 bit 10 上写入相同值无法通过读回识别来源，应避免多个充电控制器同时管理该请求。

调研依据见 `docs/SUKISU_BYPASS_RESEARCH.md` 和 `docs/XIAOMI_DADA_BYPASS_RESEARCH.md`。
