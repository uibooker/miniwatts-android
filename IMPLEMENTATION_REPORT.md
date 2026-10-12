# 当前交付：Android 1.1 UI 重做

2026-10-12已完成截图驱动的原生界面重做，详见`UI_REDESIGN_REPORT.md`。当前APK为`app/build/outputs/apk/debug/app-debug.apk`，1.1（versionCode2），909,853字节，SHA-256：`d7fbe563605c71d09fa45e305718621baa38207e015174d8cf09b4b0df3f16a3`。本次`scripts/build-local.sh --rerun-tasks`成功，6个核心测试通过，lint零错误/14警告，原签名验证有效。无ADB设备或可运行Android渲染库，未做实际UI渲染验收、没有APK页面截图。

以下为首版1.0的历史记录，其APK大小/hash与lint数量不代表当前交付。

已在仓库实际完成 P0/P1 实现，并运行核心测试、APK 构建与 lint。采用 Kotlin、原生 Android UI、SQLite，最低 API 26，target API 35。

- P0：功率、温度、充电器能力、历史、设置、诊断与离线帮助页面；会话持久化、曲线缺口、单调时间梯形积分、保留策略、删除及 CSV/JSON 文件导出。
- 持续记录：用户主动启动 `specialUse` 前台服务，通知显示读数和时间并可停止；支持通知权限拒绝后的降级。
- P1：可拖动、可关闭悬浮仪表；低频桌面 Widget，显示采样时间和“非实时”提示。
- 数据边界：保留电流符号，缺失为 `null`，明确区分实测与推算。适配器输入功率、PD、额外温度探头及容量健康百分比按实际能力显示不可用，没有编造读数。

真实验证结果：

| 检查 | 结果 |
|---|---|
| `scripts/build-local.sh` | `BUILD SUCCESSFUL in 1m 34s` |
| 核心 JUnit 测试 | 6 个测试，0 失败、0 错误 |
| `lintDebug` | 0 错误、8 个非阻塞警告，报告中逐类说明 |
| APK 签名 | `Verifies`，v2 签名有效 |
| APK Manifest | minSdk 26、targetSdk 35，无网络或广泛存储权限 |
| ADB | 无连接设备 |

APK：`/opt/workSpace/miniwatts-android/app/build/outputs/apk/debug/app-debug.apk`  
大小：933,335 字节，约 911 KiB。  
SHA-256：`ef88efcc6be902ed6aa2be26087c1a7b975ba31fad5c6ec9bc042d3c3d1c0c32`

逐项验收、命令摘要和验证边界已写入 `IMPLEMENTATION_REPORT.md`；架构、构建、权限、安装及真机步骤在 `README.md`。官方 x86-64 `aapt2` 在 ARM64 宿主的 `Exec format error` 已真实复现，项目内 ARM64 替代工具成功构建；另提供 `.github/workflows/android.yml`，未推送或执行远程 CI。

未做真机传感器、锁屏记录、悬浮窗、Widget 和文件选择器验收。另有一项范围偏差：初次工具运行自动生成了宿主默认 Gradle 缓存和 debug 密钥；正式构建已固定到项目 `.tooling`，详情如实记录在报告末尾。