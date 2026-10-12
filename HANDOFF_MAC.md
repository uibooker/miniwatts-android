# MiniWatts Android：Mac / Hermes 开发交接

## 当前状态
项目由 ResistanceTo/MiniWatts 功能与截图启发，独立 Kotlin 原生 Android 实现。最低 API26、target/compile35。没有网络权限。已完成首版与一次界面重做，但没有安卓真机渲染或行为验证。

已验证：核心6个JUnit测试通过，assembleDebug成功，lint无错误（具体警告以当前报告为准），debug APK签名通过。不要把编译通过等同于真机功能完成。

阅读顺序：REQUIREMENTS.md → UI_REDESIGN.md → README.md → UI_REDESIGN_REPORT.md → IMPLEMENTATION_REPORT.md。两个报告是历史摘要，实际源码与新测试结果优先。部分报告含对自身内容的描述，不能据此认定存在额外逐项验收证据。

## Mac 准备
1. 从确认后的远程地址 git clone，进入项目。
2. 安装/打开 Android Studio，安装 Android SDK Platform35、Build-tools35.0.0、Platform-tools，配置JDK17。不要在Mac运行Linux ARM64 bootstrap脚本。
3. 本地 local.properties 写 sdk.dir=<Mac实际SDK路径>，通常是 /Users/<用户名>/Library/Android/sdk；该文件不得提交。
4. 设置 JAVA_HOME 为真实 JDK17 路径（安装后可用 /usr/libexec/java_home -v 17），ANDROID_HOME 为实际 SDK路径。
5. 创建本地debug签名（下方说明），执行 ./gradlew :core:test :app:assembleDebug :app:lintDebug。

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
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.miniwatts/.MainActivity
adb logcat
# 真实屏幕截图，通过本地文件查看
adb shell screencap -p /sdcard/miniwatts-screen.png
adb pull /sdcard/miniwatts-screen.png
```

执行启动前核实 app/build.gradle.kts 的 applicationId，若修改过则对应替换。诊断输出不可把设备序列号等个人标识提交远程。

## 架构
- core/：纯Kotlin模型、单位转换、缺口积分、会话转换、JUnit测试。
- SensorReader/Engine：平台采集及单一后台采样器。
- Store：SQLite会话/样本持久化、覆盖统计、导出。
- MainActivity和新增UI组件：四项底部导航、卡片、仪表、图表、历史。查实际文件列表了解组件职责。
- RecordingService：用户主动前台服务、通知停止入口、悬浮窗。
- MeterWidget：低频上次采样展示。

## 接下来优先做什么
1. 拍摄实际功率/温度/充电器/历史四页截图，和UI_REDESIGN.md的四张原版截图对照：卡片留白、仪表位置、导航、安全区、字体、窄屏和深色模式。不要凭代码宣称视觉达标。
2. 检查大字体、旋转、手势返回、设置和历史详情导航，确保数值和单位不挤压。
3. 电流符号、µA/mV单位、温度、unsupported/null对照设备实际；W是电芯净功率，不能叫充电器输入。
4. 持续记录锁屏、通知拒绝、OEM后台杀进程、插拔、暂停恢复、重启、SQLite保留/删除/导出。
5. 悬浮权限拒绝、拖动关闭、停止服务移除；Widget更新时间/过期提示。
6. 检查潜在生命周期问题：Activity旋转的foreground布尔状态、旧页面异步历史更新、返回导航、采样暂停标记、无wake-lock时深度休眠缺口。
7. 按证据修复并复跑核心测试、构建、lint，记录真机型号/API和复现步骤。

普通App没有通用PD握手、适配器实时功率、全部温度探头接口；目前没有机型sysfs白名单。后续可基于用户真机只读能力实测扩展，但不得伪造热图或效率。不调用su、不改节点。

## 给Mac上Hermes的任务提示

请读取本项目HANDOFF_MAC.md、REQUIREMENTS.md、UI_REDESIGN.md和README.md，在Mac继续MiniWatts Android开发。先确认Git状态、工具链与adb连接设备，不覆盖用户改动。构建安装并拍摄真实Android四页截图，按原MiniWatts参考截图改进仪表、卡片、排版和导航；验证中文/英文、深浅主题、大字体、安全区和返回行为。随后验证真实电池采集、后台锁屏记录、会话积分与恢复、导出、悬浮窗和Widget。遇到签名不一致不要擅自卸载，先说明数据影响并确认。硬件缺失显示不可用，不用电芯功率伪装充电器输入或生成多探头假热图。记录真实测试证据，修复后运行测试/构建/lint。只修改本项目，不改服务器服务，不将密钥、设备个人标识、.tooling或build提交远程。
