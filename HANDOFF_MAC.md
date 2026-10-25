# MiniWatts Android：Mac / Hermes 开发交接

## 当前状态
项目由 ResistanceTo/MiniWatts 功能与截图启发，独立 Kotlin 原生 Android 实现。最低 API26、target/compile35。没有网络权限。2026-10-25 在 Xiaomi 24129PN74C（Android16/API36）完成又一轮真机验收：回归 17/17（新增旋转采样回归）、横屏/英文/窄屏截图验收（修复两处布局）、导出写出路径落盘验证、电流符号方向实验确证（本机型「放电为正」）。详见 DEVICE_VALIDATION_2026_10_25.md。2026-10-12/13 的早期验收见 DEVICE_VALIDATION_REPORT.md。

本轮已验证：核心6个JUnit测试、原生真机5个回归测试、assembleDebug/assembleDebugAndroidTest通过，lint 0错误/14警告，debug APK签名通过。回归覆盖重绘、对齐、双语冲突提示、样本裁剪后的标记保留和旧数据库迁移。不要把此结果等同于整份真机验收完成。

阅读顺序：REQUIREMENTS.md → UI_REDESIGN.md → README.md → DEVICE_VALIDATION_REPORT.md → UI_REDESIGN_REPORT.md → IMPLEMENTATION_REPORT.md。UI_REDESIGN_REPORT.md 与 IMPLEMENTATION_REPORT.md 是历史摘要，实际源码与新测试结果优先。部分报告含对自身内容的描述，不能据此认定存在额外逐项验收证据。

## Mac 准备
1. 从确认后的远程地址 git clone，进入项目。
2. 配置JDK17；已通过mise安装的JDK可直接复用PATH中的java，不要求它被 /usr/libexec/java_home 注册。不要在Mac运行Linux ARM64 bootstrap脚本。
3. 执行 scripts/bootstrap-mac.sh，安装项目内官方SDK Platform35和Build-tools35.0.0，确认SDK许可证；或复用Android Studio SDK并设置ANDROID_HOME/local.properties（不提交该文件）。
4. adb需在PATH中。本机已有adb，SDK及Gradle缓存位于gitignored的 .tooling/。
5. 执行 scripts/build-local.sh -PisolatedDebug=true :app:assembleDebugAndroidTest，自动创建本地debug签名并运行核心测试、构建和lint。

## Debug签名特别说明
项目签名配置引用 .tooling/debug.keystore。该密钥不在Git中，服务器也没有上传。Mac需自行生成：

```bash
mkdir -p .tooling
keytool -genkeypair -keystore .tooling/debug.keystore -storepass android -keypass android \
  -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 10000
```

Mac生成的新密钥和服务器APK签名不同，不能直接覆盖手机上服务器签名安装的版本。首选使用独立测试applicationId或在备份/导出历史后卸载旧版再安装；卸载会删除应用本地数据。若必须保留旧安装数据，需用户明确要求后，通过私密途径迁移原debug密钥，绝不提交到GitHub。CI密钥同样不能保证覆盖服务器APK。

## 安卓真机接入
开启开发者选项、USB调试，USB连接Mac，手机确认RSA授权。

```bash
adb devices -l
# 多设备时所有后续命令都加 -s <序列号>
# 先使用 -PisolatedDebug=true 构建，再核实APK包名
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w dev.miniwatts.macdebug.test/dev.miniwatts.RegressionRunner
adb shell am start -n dev.miniwatts.macdebug/dev.miniwatts.MainActivity
adb logcat
# 真实屏幕截图，通过本地文件查看
adb shell screencap -p /sdcard/miniwatts-screen.png
adb pull /sdcard/miniwatts-screen.png
```

执行启动前核实APK实际applicationId：默认仍为dev.miniwatts，只有传入isolatedDebug=true才追加.macdebug。测试版Activity类名仍为dev.miniwatts.MainActivity，不能用测试包名的简写类名。回归测试会清理测试包数据库，拒绝在原包运行；检查最终输出“Tests run: 5, failures: 0”，不能仅看adb退出码。小米可能需要“USB调试（安全设置）”“USB安装”及限时确认“继续安装”。诊断输出不可把设备序列号等个人标识提交远程。

## 架构
- core/：纯Kotlin模型、单位转换、缺口积分、会话转换、JUnit测试。
- SensorReader/Engine：平台采集及单一后台采样器。
- Store：SQLite会话/样本持久化、覆盖统计、导出。
- MainActivity和新增UI组件：四项底部导航、卡片、仪表、图表、历史。查实际文件列表了解组件职责。
- RecordingService：用户主动前台服务、通知停止入口、悬浮窗。
- MeterWidget：低频上次采样展示。

## 接下来优先做什么
1. ~~大字体、横屏、窄屏及英文界面验收~~：2026-10-25 完成（横屏裁切与英文说明溢出两处已修复并复拍；见 DEVICE_VALIDATION_2026_10_25.md）。
2. 检查大字体、旋转、手势返回、设置和历史详情导航，确保数值和单位不挤压：旋转导致采样停止的 bug 已于 2026-10-25 修复（foreground 移至 onResume/onPause，含真机旋转回归）。
3. ~~通过真实插拔和 charge counter 变化确认该机型电流方向~~：2026-10-25 实验确证本机型 current_now 为「放电为正」，与 Android 标准相反；保留原值并提示冲突的处理正确，无需修改。
4. 持续记录锁屏、通知拒绝、OEM 后台杀进程、插拔、暂停恢复、重启、SQLite 保留/删除/导出。
5. 悬浮权限拒绝、拖动关闭、停止服务移除；Widget 更新时间/过期提示。
6. 剩余生命周期项：深度休眠采样缺口（无 wake-lock，属设计边界）、长时间挂机记录。
7. 按证据修复并复跑核心测试、构建、lint，记录真机型号/API 和复现步骤。

普通App没有通用PD握手、适配器实时功率、全部温度探头接口；目前没有机型sysfs白名单。后续可基于用户真机只读能力实测扩展，但不得伪造热图或效率。不调用su、不改节点。

## 给Mac上Hermes的任务提示

请读取本项目HANDOFF_MAC.md、REQUIREMENTS.md、UI_REDESIGN.md和README.md，在Mac继续MiniWatts Android开发。先确认Git状态、工具链与adb连接设备，不覆盖用户改动。构建安装并拍摄真实Android四页截图，按原MiniWatts参考截图改进仪表、卡片、排版和导航；验证中文/英文、深浅主题、大字体、安全区和返回行为。随后验证真实电池采集、后台锁屏记录、会话积分与恢复、导出、悬浮窗和Widget。遇到签名不一致不要擅自卸载，先说明数据影响并确认。硬件缺失显示不可用，不用电芯功率伪装充电器输入或生成多探头假热图。记录真实测试证据，修复后运行测试/构建/lint。只修改本项目，不改服务器服务，不将密钥、设备个人标识、.tooling或build提交远程。
