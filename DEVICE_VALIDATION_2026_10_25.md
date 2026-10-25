# 真机验收与修复记录（2026-10-25）

设备：Xiaomi 24129PN74C（`dada`），Android 16 / API 36，HyperOS，1200×2670 / 520 dpi，USB 序列号 `（已省略）`，无线 adb `（局域网地址，已省略）`。App：MiniWatts Test（`dev.miniwatts.macdebug`）1.16 / versionCode 17，Shizuku 以 root 运行。

## 结论总览

| 项 | 结果 |
| --- | --- |
| 核心单测 / `assembleDebug` / `assembleDebugAndroidTest` / lint | 通过（lint 0 错误） |
| 真机回归（RegressionRunner） | **17/17 通过**（原 16 项 + 新增旋转回归） |
| 横屏 | 首次验收**发现数字被裁**，已修复并复拍确认 |
| 英文界面 | 五页实拍；发现英文仪表说明压到圆弧，已修复 |
| 窄屏（720×1604） | 功率页与历史页布局正常，无挤压换行 |
| 大字体 | 既有回归 `gaugeLabelsAreCentered` 等覆盖；`minimumHeight` 分支在代码中保留 |
| 导出落盘 | 通过同一写出路径真实写入 44 659 字节 schema 4 历史 JSON 并读回校验 |
| 电流符号方向 | **实验确证：本机型为「放电为正」**，与 Android 标准相反；App 保留原值并提示冲突的设计正确 |
| 生命周期 | **发现并修复旋转后采样停止的 bug**（详见下） |

## 1. 生命周期 bug：旋转后采样停止（已修复）

`MainActivity` 此前在 `onStart`/`onStop` 管理 `engine.foreground`。旋转时生命周期顺序为
`旧.onPause → 新.onCreate/onStart/onResume → 旧.onStop`，旧实例的 `onStop` 在新实例已
`onResume` 之后执行，把全局 `foreground` 清为 false 并经 `updateSampling()` 取消采样任务——
前台可见但采样停止，UI 数值冻结。

**修复**：`foreground` 与 listener 注册移至 `onResume`/`onPause`（paused 必然先于下一次
resume，flag 始终归属用户可见的实例）；Shizuku listener 与 `active` 留在 `onStart`/`onStop`。

**回归测试**：新增 `foregroundSurvivesARotation`（真机旋转 + 等待真实调度器采样 ≥1 次）。
修复前该测试红（采样停止），修复后 17/17 绿。

> 附注：曾尝试用「重复 `am start` 叠加实例」复现，本机系统将其路由到已有任务，未产生第二
> 实例；旋转是可稳定复现的形态，故回归以旋转实现。

## 2. 横屏布局（已修复）

竖屏窄屏（720×1604）与各页面横屏截图存于 gitignored `.tooling/validation-1025/`。

发现两处问题：

1. **PowerGauge 无宽度上限**：横屏卡片被拉满全宽（探针实测 gauge 宽 992 px），数字落出
   首屏。修复：gauge 宽度封顶 `min(屏宽−64dp, 360dp)` 并居中（`LivePanels.power`）。
2. **PowerGauge 最小高度未分方向**：横屏可视高度约一半，250 dp 的 `minimumHeight` 把数字
   推到导航栏之下。修复：横屏时 `minimumHeight` 降为 170 dp。

复拍确认：横屏下数字完整、弧居中、卡片在首屏内。

## 3. 英文仪表说明溢出（已修复）

"Adapter input 7.2 W · measured" 比中文长，autosize 下限 9 sp 时两端压到圆弧。修复：
autosize 下限降到 8 sp、固定宽度 180→200 dp。复拍确认不再溢出。

## 4. 导出落盘验证

HyperOS 拒绝 shell 与 instrumentation 的输入注入（「USB 调试（安全设置）」未开），系统文件
选择器的确认按钮无法自动化；驱动 DocumentsUI 的尝试会让选择器停留前台并阻塞后续启动
（`force-stop com.google.android.documentsui` 可解除）。

改为验证**与 SAF 结果完全相同的写出路径**：`ContentResolver.openOutputStream(uri, "wt")`
→ `Store.export(writer, json=true)`，URI 来自 MediaStore Downloads（无需权限），写入后用
`openInputStream` 读回断言。结果：**44 659 字节、schema 4、含 sessions/samples**，读回一致。
选择器本身的确认是系统行为，不在可自动化范围内；写入侧逻辑已验证。

测试实现于 `ValidationRunner`（androidTest，`-e steps saf` 等 7 个步骤），其
`testInstrumentationRunner` 指向需要临时切换；跑回归时保持 `RegressionRunner`。

## 5. 电流符号方向：确证「放电为正」

方法：root 读取 sysfs，设备端后台循环每 5 秒采样（不依赖 USB），配合**真实拔插充电器**。

实验数据（`.tooling/validation-1025/current-sign-experiment.log`，共 32 样本）：

| 状态 | `current_now` | `charge_counter`（µAh） | `online` |
| --- | --- | --- | --- |
| 插电 Charging | **−1.23 ~ −1.43 A** | 3 060 000 → 3 085 000 **单调上升**（+25 000 µAh ≈ 2.5 分钟充入 5% 电量对应量级） | 1 |
| 拔线 Discharging | **+0.20 ~ +0.49 A** | 持平 → 缓降（3 085 000 → 3 079 000） | 0 |

正电荷净流入电池时读数为负、净流出时为正 ⇒ **该机型 `current_now` 符号约定为「放电为正」**，
与 Android 标准（充电为正）相反。此前 HANDOFF 里「充电中观察到负电流」的疑问由此定论：
不是测量错误，也不是间歇性异常。

App 的处理（保留原始带符号值、图标/表头用绝对值、方向由充放电状态表述、冲突时给徽标）
与这一机型语义兼容，无需修改。导出与积分保持原始符号不变。

## 6. 设备与工具边界（后续会话需知）

- `adb shell input tap/swipe`、`settings put`、`UiAutomation.injectInputEvent` 均被
  HyperOS 拒绝（需要「USB 调试（安全设置）」）。UI 驱动只能走
  accessibility action（应用内）或 `requestedOrientation`。
- `wm size` 恢复：本机物理 1200×2670，验证后如未恢复需执行
  `adb shell wm size 1200x2670`。
- 无线 adb：`adb tcpip 5556` 后 `adb connect <手机WLAN IP>:5556`；手机连上 WiFi 时可用，
  本机热点/蜂窝共享时不可用。
- 手写 androidTest manifest 的 `<instrumentation>` 会与 AGP 生成的条目冲突
  （`testInstrumentationRunner` 才是唯一来源）；androidTest/AndroidManifest.xml 保持空壳。
- instrument 产生的 DocumentsUI 卡死用 `adb shell am force-stop com.google.android.documentsui`
  解除。
- 实验用的 sysfs 采样脚本已从 `/data/local/tmp` 清理。

## 7. 复现命令

```bash
scripts/build-local.sh -PisolatedDebug=true :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.miniwatts.macdebug.test/dev.miniwatts.RegressionRunner
# ValidationRunner（需临时把 build.gradle.kts 的 testInstrumentationRunner 指向它）
adb shell am instrument -w -e steps power,thermal,lang-en,land,narrow,saf,probe \
  dev.miniwatts.macdebug.test/dev.miniwatts.ValidationRunner
```

## 8. 尚未覆盖

- 真实点击系统文件选择器确认（输入注入被系统策略阻止，见 §4）。
- 大字体（fontScale > 1.2）实拍：代码分支存在，回归覆盖仪表测量，但没有截图证据。
- 锁屏/深度休眠/后台强杀的长时间记录（需要长时间挂机）。
- Widget 更新节奏、悬浮窗拖动关闭（需要手动交互）。
