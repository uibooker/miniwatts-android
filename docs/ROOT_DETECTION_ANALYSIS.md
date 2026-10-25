# 小骨检测 / 春秋 Native Check —— Root 与环境检测机制分析

分析日期：2026-10-14。目标：连接的实机 `24129PN74C`（Xiaomi，代号 dada，Android 16 / SDK 36，内核 `6.6.118-android15-8-gc44b714366cc-abogki519650608-4k`，SukiSU Ultra root，`adb` 走网络 （局域网地址，已省略））。

本文只记录**实际观测与可复现的证据**。所有结论都标注了获得方式（静态反编译 / 运行时 maps / 系统调用跟踪 / 界面结果）。

| 目标 | 包名 | 版本 | 体积 | 加固 |
| --- | --- | --- | --- | --- |
| 小骨检测 | `com.envdetector` | 1.0.0 (vc 1) | 14.3 MB | 无壳，Kotlin + Compose |
| 春秋 Native Check | `com.chunqiunativecheck` | 2.8.3 "Eros" (vc 32) | 11.2 MB | 大篆片加固（字符串加密 + 混淆 + SO 保护） |

---

## 0. 结论速览

1. **两者的检测思路完全不同。** 小骨检测是**大而全的框架型**检测器：84 个 Kotlin 检测器类、11 个分类，逐项串行跑，输出 123 个检测项。春秋是**少而精的 native 型**检测器：Java 层只有 48 个 `native` 方法声明，全部真实逻辑在 `libchunqiunative.so` 里，界面只展示"异常项"。
2. **小骨检测有一个致命实现缺陷：native 桥从未初始化。** `System.loadLibrary("native_detector")` 只出现在 `NativeBridge.init()` 里，而**全部 5 个 dex 里没有任何地方调用 `init()`**；运行时 `/proc/<pid>/maps` 也证实 `libnative_detector.so` 从未被映射。因此 NATIVE 分类的 6 个检测器、`AntiHookGuard`、以及所有"Java 读 vs native 读"的差分检测全部失效，且失败方向统一是**漏报**。
3. **春秋的 native 库并不是被卸载，而是从 APK 内联映射。** `/data/app/.../lib/arm64/libchunqiunative.so` 实际不存在（`openat` 返回 `ENOENT`）；该 SO 在 APK 里以 **stored（不压缩）** 方式存放于偏移 `0x345df7`，运行时直接按此偏移映射 APK 文件。三个 `PT_LOAD` 段与观测到的三段映射逐字节吻合（见 §3.3）。所以 `grep libchunqiunative /proc/pid/maps` 查不到它，是正常现象，不代表没加载。
4. **本机实测结果**：小骨检测 123 项中 4 项"危险"；春秋只报出 2 组异常。两者都没有抓到 SukiSU Ultra 本身，抓到的是**周边痕迹**（MT 管理器、Root 管理器 App、风险应用、adb 已开启）。

---

## 1. 复现环境与工具链

主机：macOS + JDK 17。设备：SukiSU Ultra（`su -c id` → `uid=0 context=u:r:ksu:s0`），`/system/bin/strace` 4.25 可用。

| 用途 | 工具 |
| --- | --- |
| 取包与资源 | `adb pull`、`aapt dump --values resources`（`.tooling/android-sdk/build-tools/35.0.0`） |
| Java 层反编译 | `jadx` 1.5.6（`-Duser.home=<可写目录>`，否则因沙箱无法创建 `~/Library/Application Support`） |
| DEX 精准提取 | 自写 `tools/dexmapper.py`：解析 DEX，输出**每个方法实际加载的 `const-string` 与 `invoke` 目标**。比全量 jadx 快且不受 Compose 体积拖累，也是唯一能拿到加固包字符串的办法 |
| ELF 分析 | 自写 `tools/elfread.py`（VMA↔文件偏移、段表、重定位），`nm -D` / `objdump -d` |
| 运行时 | `/proc/<pid>/maps`、`/proc/<pid>/mem`、`strace -f`、SELinux `avc: denied` 审计日志 |
| 界面取证 | `adb exec-out screencap` + `tesseract -l chi_sim`（无中文字库时下载 `chi_sim.traineddata`） |

工具与中间产物在 `/tmp/rootcheck/`（`tools/dexmapper.py`、`tools/elfread.py`、`env_detectors.txt`、`env_core.txt`、`cq_app.txt`、各 trace 文件）。**工作区源码未做任何修改。**

---

## 2. 小骨检测（`com.envdetector`）

### 2.1 框架结构

反编译 + DEX 提取得到的框架（`core/` 包）：

```
IDetector { id, name, category, priority, timeout=5000ms, suspend detect(Context): DetectorResult }
DetectorRegistry.getAll()  -> 固定顺序实例化 74 个检测器
DetectorManager.startScan(): Flow<ScanProgress>   // 冷 flow + 单个 for 循环 = 串行
ScanProgress = Checking | ItemDone | Complete
DetectorResult = Success(items: List<DetectionItem>) | Skipped(reason)
DetectionItem(name, status: PASS|WARNING|FAIL, detail, severity: LOW|MEDIUM|HIGH|CRITICAL)
DetectorCategory = ROOT, HOOK, PROPERTY, MOUNT, PROCESS, SELINUX, ATTESTATION,
                   ENVIRONMENT, MODULE, APP, NATIVE
```

要点（均来自字节码）：

- **串行执行**：`startScan` 是普通 `flow { }`，没有 `async` / `flatMapMerge` / `Dispatchers.Default`。最坏情况 74 × 5 s ≈ 6 分钟。
- **超时统一 5 s**：`IDetector$DefaultImpls.getTimeout` 返回 `const-wide/16 #5000`，**84 个检测器全部委托给默认实现**，没有一个自定义超时。
- **两条 catch**：`TimeoutCancellationException` → `Skipped("检测超时")`；其他 `Throwable` → `Skipped("异常: " + msg)`。超时/异常**仍然会发出 `ItemDone`**，所以卡住的检测项显示为"跳过"而不是失败。
- **优先级是按分类分段的 3 位数**：ROOT 10–15、HOOK 20–28、PROPERTY 30–38、MOUNT 40–49、SELINUX 50–52、PROCESS 60–65、ATTESTATION 70–75、ENVIRONMENT 80–87、MODULE 90–97、APP 100–103、NATIVE 110–115、残留类 120–128。
- **残留类被归到 `APP` 分类**（`DetectorCategory` 里没有 RESIDUE 常量）。

### 2.2 两个"写了但没接上"的模块

这两条是本次分析里最有价值的部分，且都经过交叉验证（静态 + 运行时）。

**(a) native 桥从未初始化。**
- 9 个 JNI 方法由 `JNI_OnLoad` 动态注册，类 `com.envdetector/core/NativeBridge`，注册表从 `libnative_detector.so` 的 `.data` + `.rela.dyn` 完整还原：

  | # | native 方法 | 签名 | 实现（符号名未 strip） |
  | --- | --- | --- | --- |
  | 0 | `nativeAccessCheck` | `(String)Z` | `access_check()` |
  | 1 | `nativeStatCheck` | `(String)Z` | `stat_check()` |
  | 2 | `nativeReadMountInfo` | `()String` | `read_mount_info()` → `/proc/self/mountinfo` |
  | 3 | `nativeReadMaps` | `()String` | `read_maps()` → `/proc/self/maps` |
  | 4 | `nativeReadModules` | `()String` | `/proc/modules` |
  | 5 | `nativeDirectSyscallOpen` | `(String)I` | `direct_openat()` → **裸 `svc #0`，syscall 56** |
  | 6 | `nativeReadProperty` | `(String)String` | `read_property()` → `__system_property_get` + `/system/build.prop` 兜底 |
  | 7 | `nativeCheckInlineHook` | `(String)Z` | `check_inline_hook()` → `dlsym` 后比对函数前 8 字节 |
  | 8 | `nativeGetVersion` | `()String` | — |

  另外导出（不注册为 JNI）的裸系统调用助手：`direct_stat`（syscall 79 `newfstatat`，`AT_FDCWD`）、`direct_read`（syscall 63）、`direct_openat`（syscall 56）。设计意图明确：**绕开被 hook 的 libc `open`/`stat`**。
- 但 `System.loadLibrary` **只**出现在 `NativeBridge.init()`；对全部 5 个 dex 做 invoke 目标扫描，`NativeBridge;->init`、`->isLoaded`、`->getLoadError` 的调用点数为 **0**。
- 运行时验证：`grep -c libnative_detector /proc/<pid>/maps` = **0**。
- 后果：`isLoaded == false` 恒成立，每个 `safe*` 包装器直接返回默认值（`false` / `""` / `-1`）。于是以下能力**全部失效**：整个 NATIVE 分类（6 个检测器）、`AntiHookGuard`、以及 `Resetprop` / `Debuggable` / `BootHash` / `MountNamespace` / `MountGraphics` / `BindMount` / `DebugRamdisk` / `LoopDevice` / `MountInfo` / `OverlayFS` / `LSPosed` / `ZygiskSU` / `SuBinary` 的"Java 值 vs native 值"差分半支。**方向全部是漏报**，不会误报。

**(b) 10 个检测器是死代码。** 全部 5 个 dex 里没有任何构造调用：7 个 `module/*`（`shamiko`、`zygisk_next`、`play_integrity_fix`、`nohello`、`susfs`、`vbmeta_disguiser`、`zygisk_assistant`）、`mount/SUSFSMountDetector`、`mount/AdbModuleMountDetector`、`attestation/PlayIntegrityDetector`。其中 `SUSFSDetector`（含 `vfs_open` inline-hook 探测）和 `PlayIntegrityDetector` 是完整实现，只是没注册。**MODULE 分类实际只剩 `hma` 一个。** 另外 `PlaceholderDetector`（返回 `Skipped("待实现")`）和 `ResultProtector`（`protectResult` 原样返回、无人调用）也是死代码。

实际参与扫描的检测器 = **64 个**。

### 2.3 检测手段目录（按分类）

以下为各检测器实际使用的**文件路径 / shell 命令 / 属性名 / 包名 / 判定规则**，全部来自字节码字符串。

#### ROOT（6）

| 检测器 | 手段 |
| --- | --- |
| `su_binary` su二进制文件扫描 | 12 条路径：`/system/bin/su`、`/system/xbin/su`、`/sbin/su`、`/vendor/bin/su`、`/data/local/xbin/su`、`/data/local/bin/su`、`/system/bin/.ext/su`、`/system/sd/xbin/su`、`/system/bin/failsafe/su`、`/data/su`、`/su/bin/su`、`/system/app/Superuser.apk`；详情串里带 `nativeAccess=` 交叉验证 |
| `magisk` | 9 条路径 `/sbin/magisk*`、`/dev/magisk`、`/dev/.magisk`；`ps -A` grep `magiskd`/`magiskinit`；`id -Z` 含 `magisk`；`magisk -v` 可执行；`/overlay.d` 存在 |
| `kernelsu` | `/system/lib/modules/ksud.ko`、`/sys/kernel/security/ksu/status`；`/proc/modules` grep `ksud`/`kernelsu`；`ps -A`；`dmesg` 统计条数 |
| `apatch` | `/dev/superuser` 设备节点；`ps -A` grep **`apd`**；`dmesg` grep **`.kpm`**（KPM 内核模块日志） |
| `kitsune_mask` | `magisk -v` 输出含 `Kitsune`/`Delta`；`magisk --hide status` 执行成功 |
| `magisk_alpha` | 包名 `com.topjohnwu.magisk.alpha` |

#### HOOK（9）

| 检测器 | 手段 |
| --- | --- |
| `zygisk` | `ps -A` grep `zygiskd`；`/proc/self/maps` grep `zygisk`；环境变量 `ZYGISK`；属性 `persist.magisk.zygisk`；**遍历 `/proc/self/fd` 逐个 `readlink`，grep `magisk`**（fd 泄漏） |
| `zygisk_su` | `ps -A` grep **`rezygiskd`**；`/proc/self/environ` 含 `LD_PRELOAD`，或**变量数量异常偏少**（环境被清洗的特征）；`su -c id` 输出含 `uid=0`；属性 `persist.zygisk.enable` |
| `lsposed` | 6 个属性（`ro.dalvik.vm.native.bridge`、`dalvik.vm.zygote.canary`、`persist.lsposed.verbose`、`persist.lspd.verbose`、`lsposed.version`、`lspd.version`）+ 5 个包名 + `/data/data/org.lsposed.lspd/` + `ps -A` grep `lspd` + maps grep `XposedBridge`/`lsplant` + odex 目录 + 5 个日志文件；**反射取 `Thread.dispatchUncaughtException` / `ActivityThread.attach` 的 ArtMethod 入口点**，判断 `rwx`/`r-x`；**`strings <odex> \| head -20` 找 `--inline-max-code-units=0`** |
| `xposed_bridge` | `Class.forName("de.robv.android.xposed.XposedBridge")` 与 `XC_MethodHook`；14 个管理器包名；maps grep `xposed` |
| `riru` | `/data/misc/riru`；maps grep `riru` |
| `frida` | TCP 连 `127.0.0.1:27042` / `27043`；maps grep `frida`/`frida-agent`/`linjector`/`gum-js-loop`；`ps -A` grep `frida-server` |
| `substrate` | `Class.forName`；maps grep `libsubstrate`；`/system/lib{,64}/libsubstrate.so` |
| `lsplant` | maps grep `lsplant`；native inline-hook 检查 **`art::Runtime::Start`** 与 **`art::ClassLinker::DoResolve`**（依赖未初始化的 native 桥，实际失效） |
| `inline_hook` | native inline-hook 检查 `open`、`access`、`stat`、`pthread_create`、`fopen`、`read`（同样失效） |

#### PROPERTY（10）

| 检测器 | 手段 |
| --- | --- |
| `prop_build_tags` | `Build.TAGS` 含 `test-keys` |
| `prop_debuggable` | `ro.debuggable` + `Settings.Global.ADB_ENABLED` + Java/native 差分 |
| `prop_secure` | `ro.secure == 0` |
| `prop_bootloader` | `ro.boot.vbmeta.digest`、`ro.boot.vbmeta.device_state`、`ro.flash.locked`、`ro.boot.flash.locked`、`ro.boot.veritymode`、`ro.boot.verifiedbootstate` |
| `prop_resetprop` | 4 重检测：Java vs native 属性差分；`/system/build.prop` 文件值 vs `getprop` 值；**fingerprint 含希腊字母**（正则 `[\u0391-\u03C9]`）；**`getprop -T` / `getprop -Z` 读属性 serial 号**判断是否被 resetprop 改过 |
| `prop_device_spoof` | `/odm/etc/build.prop`、`/vendor/build.prop`、`/system/build.prop`、`/proc/cpuinfo`、`/proc/device-tree/model`、`/sys/devices/soc0/machine` 多源比对 `Build.MODEL/BRAND/DEVICE/MANUFACTURER/HARDWARE` |
| `prop_kernel_info` | 7 重：`/proc/version` 含 `zygisk`/`magisk`/`ksu`/`susfs`/`apatch`/`kernelsu`；`-Dirty` 后缀；`/proc/modules` 含 `ksud`；**`/proc/config.gz` 含 `CONFIG_KSU_SUSFS`**；`uname -r` 高风险词；内核后缀黑名单 `-perf`/`-custom`/`-geforce`/`-gperf`/`-rooted`/`-modded`；**内核版本格式正则 `\d+\.\d+\.\d+-\d+-[a-z0-9]+`** |
| `prop_magisk` | `ro.magisk.disable`、`persist.magisk.zygisk`、`ro.boot.magisk`、`persist.sys.root_access`、`ro.build.type == userdebug` |
| `cpu_spoof` | `/proc/cpuinfo` 的 `Hardware` vs `Build.HARDWARE`；8 个联想平板型号黑名单；内置 SoC 型号库（`kona`/`lahaina`/`taro`/`kalama`/`pineapple`/`SM8xxx`/`mt6xxx`/`mt8xxx`） |
| `flash_prop_residue` | `persist.adb.nonblocking_ffs`、`persist.hyperceiler.log.level`、`persist.com.luckyzyx.luckytool.*`；`/data/property/persistent_properties` 中的隔离属性残留 |

#### MOUNT（10，实际 8）

统一模式：**同时用 Java `File` 读和 native 读 `/proc/self/mountinfo`，然后做差分**（native 侧失效，见 §2.2）。

| 检测器 | 手段 |
| --- | --- |
| `mount_info` | 13 个关键词黑名单：`magisk`、`zygisk`、`ksu`、`apatch`、`susfs`、`lspd`、`frida`、`riru`、`kernelsu`、`shamiko`、`xposed`；9 个系统前缀白名单 |
| `mount_loop` | 非 APEX 路径的 `/dev/block/loop*` 挂载 |
| `mount_bind` | bind 挂载含 `magisk`/`adb`；`/sbin/mirror` |
| `mount_overlay` | overlay 的 `lowerdir=` 白名单（`/system_ext/`、`/product/`、`/my_*`、`/prism/`、`/optics/` 等）；非系统分区的 `trusted.overlay.opaque` |
| `mount_debug_ramdisk` | `/debug_ramdisk/.magisk`；`ls -la /debug_ramdisk/`；挂载类型为 `overlay` |
| `mount_namespace` | `readlink /proc/1/ns/mnt` vs `/proc/self/ns/mnt`；**挂载点 > 350 判为异常**；仅当前进程可见的 root 挂载；Java/native 差分 |
| `mount_proc_exe` | `readlink /proc/self/exe` vs `ls -la /proc/self/exe` 双源比对；exe 路径含 root 关键词 |
| `mount_graphics` | GPU 库（`libEGL`/`libvulkan`/`vulkan`/`egl`）的 **`st_dev` 与 `/vendor` 分区对比**、**maps inode vs 磁盘 inode 对比**（`stat -c '%d'` / `stat -c '%i'`）；插桩覆盖挂载 |

#### SELINUX（3）

`selinux_state`（`getenforce`、`/sys/fs/selinux/enforce`）、`selinux_context`（`id -Z`、`/proc/self/attr/current`，含关键词 `magisk`/`zygisk`/`su`）、`selinux_policy`（**`cat /sys/fs/selinux/policy 2>/dev/null | strings | grep -E 'magisk|ksu|apatch|zygisk|lsposed'`**）。

#### PROCESS（6）

`proc_root_processes`（`ps -A` **加上**直接枚举 `/proc/<pid>/comm`，关键词 `magiskd`/`magisk`/`ksud`/`apd`/`zygiskd`/`lspd`/`rezygiskd`/`ksu_susfs`/`magiskinit`/`resetprop`）、`proc_zygote_inject`（zygote maps 注入库；**cmdline vs comm 不一致**；**maps 中 `.so` 数量 > 500**；`environ` 含 `LD_PRELOAD`）、`proc_thanon_service`（`ps -A` + 3 个包名 + 4 个目录 + `getprop | grep -i thanox`）、`proc_tracer_pid`（`/proc/self/status` 的 `TracerPid`，解析失败视为安全）、`proc_port_scan`（27042/27043，异常时 `检测完成(异常安全退出)`）、`proc_fd_leak`（`/proc/self/fd` `readlink` grep `magisk`/`zygisk`/`lsposed`）。

#### ATTESTATION（6，实际 5）

5 个存活检测器都用 `AndroidKeyStore` 生成临时 EC P-256 密钥、`setAttestationChallenge`、检查证书链：

- `att_key_attestation`：OID **`1.3.6.1.4.1.11129.2.1.17`**；`teeEnforced` 中 tag **709（`02c5`，USER_ID）** 与 tag **710（`02c6`，APPLICATION_ID）**；证书 `security_patch` 标签 vs `ro.build.version.security_patch`；链长 2–4；根证书 Subject 含 `AOSP`/`CTS` 判为测试密钥。
- `att_aosp_key`：根证书 Subject 含 `aosp`/`android open source project`；SecurityLevel 扩展为 `Software`。
- `att_boot_hash`：`ro.boot.vbmeta.digest` 必须是 64 位 hex（正则 `^[0-9a-fA-F]{64}$`）；**全 0 digest 判为伪造/关闭 verified boot**；`verifiedbootstate != green`；`device_state != locked`；`ro.boot.flash.locked` 对比 `1`/`true`；Java vs native 差分。
- `att_cert_chain`：链长、issuer/subject 串联校验、每张证书的 AOSP 测试密钥识别。

#### ENVIRONMENT（8）

`env_emulator`（`goldfish`/`ranchu`/`qemu`/`genymotion` 等关键词 + `/system/lib/libhoudini.so`、`/dev/qemu_pipe`）、`env_virtual_machine`（`/proc/cpuinfo` 含 `qemu`/`kvm`/`vmware`/`virtualbox`；`/dev/vboxguest` 等）、`env_multi_open`（9 个多开包名；**`/data/user/999/` 数据路径**；**进程名含冒号但无包名**）、`env_work_profile`（DevicePolicyManager；**`userId = uid / 100000 >= 10`**；Java UID vs `/proc/self/status` UID 不一致）、`env_multi_user`（`userId != 0`）、`env_container`（`/.dockerenv`、`/proc/1/cgroup` 含 `docker`/`lxc`、根路径 overlay）、`blacklist_device`（**45 个机型型号硬编码黑名单**）、`y700_device`（`Build.MODEL` 含 `Y700` 或以 `TB-` 开头 + 5 个联想组件包）。

#### MODULE（8 类，实际 1 个存活）

唯一存活的 `hma`（HideMyApplist 检测）用了一个巧妙手法：**对比 `UsageStats` 与 `PackageManager.getInstalledPackages()` 的数量**——`UsageStats` 里存在但已安装列表里没有的应用，就是被隐藏/伪装的应用。

#### APP（12：4 个应用检测 + 8 个残留检测）

- `root_manager_apps`：**23 个 Root 管理器包名**，含 `com.topjohnwu.magisk`、`me.weishu.kernelsu`、`com.rifsxd.ksunext`、**`com.sukisu.ultra`**、`me.bmax.apatch`、`me.garfieldhan.apatch.next`、`com.dergoogler.mmrl` 等。
- `debug_tools`：`org.lsposed.manager`、`de.robv.android.xposed.installer`、`re.frida.server`。
- `virtual_apps` / `terminal_apps`：7 / 5 个包名。
- `mt2_folder`：`/storage/emulated/0/MT2`。
- `mt_manager`：`bin.mt.plus{,.canary,.pro,.mtz}` + 数据目录 + `ps -A` 三重验证。
- `local_tmp_residue`：`ls -la /data/local/tmp/` + 19 条残留路径；**`/data/local/tmp` 的 inode > 10000 判为异常**（文件churn 启发式）。
- `wechat_xposed`、`kernel_driver_residue`（44 条路径，含大量游戏外挂驱动的中文目录名）、`forbidden_folders`、`scene_trace`（Scene/omarea 全套：13 文件 + 22 包名 + 8 目录 + 10 属性）、`root_residue`（55 条路径 + 13 个数据包 + 10 个 OBB 包）。
- **`root_residue` 会去猎杀别的检测工具**：`com.scottyab.rootbeer`、`rikka.safetynetchecker`、`io.github.vvb2060.keyattestation`、`me.garfieldhan.holmes`、`icu.nullptr.nativetest`、`icu.nullptr.applistdetector`、`io.github.huskydg.memorydetector`、`com.reveny.nativechecker` 等 13 个包。

### 2.4 值得注意的横切手法

1. **同一文件两条读取路径再差分**（本 app 的招牌手法）：`/proc/self/mountinfo`、`/proc/self/maps`、`/proc/modules`、`ro.*` 属性、`/proc/version`、`/proc/self/exe`、UID —— Java 读一遍、native 读一遍，不一致就报"可能被 Hook"。**这正好是 SUSFS/Shamiko 一族最怕的**……但在本版本里因为 native 侧没初始化而全部作废。
2. **裸系统调用**：`nativeDirectSyscallOpen` 用 `svc #0` 直接走 syscall 56，绕过 libc PLT/GOT hook。讽刺的是，能抓 hook 的恰恰是这些没被初始化的 native。
3. **普遍 fail-open**：`检测完成(异常安全退出)` 出现在 **13 个检测器**里；`检测失败，视为未发现`、`XposedBridge类检测异常，视为未加载`、`无法解析TracerPid，视为安全`、`无法读取SELinux策略（正常，无root权限）`。**让任何一个 `/proc` 读取抛异常，就可能把 FAIL 变成 PASS** —— 这是全 app 最大的规避面。
4. **抗伪装的启发式**（不靠关键词，较难伪造）：挂载点 > 350；maps 中 `.so` > 500；`/data/local/tmp` inode > 10000；1000 次 `getuid` 耗时 > 50 ms（`AntiHookGuard.checkTimingAnomaly`，但无人调用）；`environ` 变量数异常偏少；`cmdline` vs `comm` 不一致；GPU 库的 `st_dev` + inode 比对。
5. **31 个类、53 处 `safeShellExec`**，命令走 `/system/bin/sh -c`，默认超时 3000 ms。`ps -A`、`dmesg`、`getprop`、`readlink`、`stat` 都是可被 hook 的通道 —— 这也正是它要另开文件直读通道的原因。

### 2.5 实机结果

扫描耗时 39.8 s，共 **123 个检测项：119 安全 / 0 风险 / 4 危险**。

| 危险项 | 原因 |
| --- | --- |
| Debuggable 检测 | 本机 `adb` 通过 TCP 开启，`Settings.Global.ADB_ENABLED = 1` |
| Root 管理 App 检测 | 已安装 `com.sukisu.ultra`（SukiSU Ultra 管理器），命中 23 个包名清单 |
| MT2 文件夹扫描 | `/storage/emulated/0/MT2` 存在（MT 管理器） |
| MT 管理器检测 | 已安装 `bin.mt.plus` |

**KernelSU / Magisk / APatch / KitsuneMask / Magisk Alpha / su 二进制 / Native 系列 / 挂载 / SELinux / 密钥认证 / 属性差分 —— 全部报"安全"。** 后半部分有相当比例是 §2.2(a) 的实现缺陷造成的假阴性，不能当作"SukiSU 隐藏得好"的证据。

SELinux 审计日志（`logcat` 中 `avc: denied`）如实记录了小骨检测的探测目标：`/proc/pid_max`、`/proc/modules`、`/proc/version`、`/proc/config.gz`、`/dev/kmsg`、`/dev/pmsg0`、`/sys/kernel/tracing/trace_marker`、`/sys/.../model`、`/sys/.../machine`，以及 `ps`/`dmesg`/`getprop`/`cat` 等 shell 调用。

---

## 3. 春秋 Native Check（`com.chunqiunativecheck` 2.8.3）

### 3.1 加固情况

APK 内 `assets/muhuaya/标准版字符串加密.txt` 是大篆片的水印文件，说明该包经过**大篆片"标准版字符串加密"**处理：

```
本应用经过大篆片处理
官网：yun.dzpgrw.cn / yun.tuliu66.cn
处理时间: 2026-03-11 19:47:49
（可添加 BGM/弹窗/校验/混淆/加密/加固/对齐优化/设置原Entry/过签体积优化/
  修改文件时间/更改加固状态；还有 签名/随机签名/提取图标/加固检测/Activity记录 等）
```

静态特征：

- Java 层类名混淆成 `Lwm;`、`Ltq;`、`Lkm;`、`LO0O0O0I;` 之类；`MainActivity` 的字符串常量**全部是密文**，形如 `'ᓆ ఉ蠟ᓲ ఉ落…'`，按 UTF-16 code unit 每 4 个一组落在各自窄字符带内（`0x14xx` / `0x205x` / `0x0C0x` / `0xF91x`）——典型的短周期循环密钥特征。
- 解密器在 `Lmuhuaya/String;->mh`（大篆命名空间 `muhuaya`），配套 `Lmuhuaya/BJ9izU8HYEYIKaYcuikEuPZu0rlesYBtKqjLROWMzBbAmC6HS;` 等类，内部用 `۟`–`ۨ`（U+06DF–U+06E8）编码的常量做索引。`Lmuhuaya/String;->mh` 在 Java 层被调用 7 次（`ExperimentalFragment`、`KeyAttestation` 等）。
- **资源层未被加密**：`aapt dump --values resources` 直接拿到全部明文字符串（见 §3.4）。这是加固的一个盲点。

### 3.2 48 个 native 检测方法

`MainActivity` 与 `ExperimentalFragment` 中声明的 `native` 方法（`ACC_NATIVE` 置位、无 `code_item`），这是**春秋检测能力的完整清单**：

| 组 | 方法 |
| --- | --- |
| **Root 框架** | `detectSuBinary`、`detectSulist`、`detectKernelsu`、`detectKernelsuJbd2`、`detectApatch`、`detectZygisk`、`detectLspHook`、`detectMaliciousHook`、`detectResetprop` |
| **挂载 / 命名空间** | `detectMountAnomalies`、`detectMountLoophole`、`checkMountConsistency`、`checkMountConsistencyPairs`、`getProc1MountDiffDetails` |
| **内核 / 启动** | `detectKernelBuild`、`detectKernelBlacklist`、`detectDirtyKernel`、`detectAvbVersion`、`getBootloaderDetails`、`detectCgroupSupport` |
| **进程 / 注入** | `detectHiddenProcessGroups`、`detectZygoteEnvironment`、`getZygoteEnvironmentDetails`、`detectThanonService`、`detectEvilServices`、`detectRiskApps`、`detectScenePort`、`detectPty` |
| **反调试 / 反分析** | `detectPtrace`、`detectHwBreakpoints`、`detectAnonExec`、`detectFaccessatFchownatSidechannel` |
| **SO / JNI 完整性** | `detectSoTampering`、`detectLibraryOrder`、`detectJniTableSource`、`detectNativeBridge`、`detectVirtualArch` |
| **文件 / 属性痕迹** | `detectSuspiciousFiles`、`detectSuspiciousFiles222`、`getSuspiciousFilesDetails`、`detectSuspiciousPersistProps`、`detectEnvAnomalies`、`detectFakeEnvironment`、`detectThirdPartyRom`、`detectAbnormal`、`detectDeviceTampering`、`detectDeviceTamperingEx` |

命名本身就说明了很多：`detectKernelsuJbd2` 是经典的**通过 `jbd2` 内核线程名识别 KernelSU**（KernelSU 会 patch `jbd2` 线程）；`detectHwBreakpoints` 用硬件断点寄存器查调试；`detectJniTableSource`/`detectLibraryOrder`/`detectSoTampering` 检查自己的 JNI 表与 SO 是否被动过；`detectFaccessatFchownatSidechannel` 是走 `faccessat`/`fchownat` 的旁路探测。

### 3.3 native 库是怎么加载的（重要更正）

一开始 `grep libchunqiunative /proc/<pid>/maps` 结果为 0，容易误判为"加载后被卸载"。实际不是：

1. `strace -e trace=%file` 显示有进程 `openat("/data/app/~~b7cp1r4Z…/lib/arm64/libchunqiunative.so")` → **`-1 ENOENT`**。即该路径**根本不存在**（APK 的 `extractNativeLibs=false`，库没有被解压出来）。
2. `unzip` 元数据：`lib/arm64-v8a/libchunqiunative.so` 的 `compress_type = 0`（**stored，不压缩**），`file_size = 0x31b1d0 = 3,256,784 B`，在 APK 中的偏移 **`0x345df7`**。
3. 进程 maps 里出现三段相邻映射，路径都写 `base.apk`：

   ```
   708a641000-708a947000 r-xp 00346000 … base.apk     (size 0x306000)
   708a947000-708a957000 r--p 0064b000 … base.apk     (size 0x10000)
   708a957000-708a95e000 rw-p 0065a000 … base.apk     (size 0x7000)
   ```
4. 对照该 SO 的 `PT_LOAD`：

   | PT_LOAD | 段内偏移 | filesz | 段内偏移 + 0x345df7 | 页对齐后 | 观测值 |
   | --- | --- | --- | --- | --- | --- |
   | R+X | `0x000000` | `0x305fe0` | `0x345df7` | `0x346000` | ✅ `0x346000`，`0x306000` |
   | R+W | `0x305fe0` | `0x00e6b8` | `0x64bdd7` | `0x64c000` | ✅ `0x64b000`，`0x10000` |
   | R+W | `0x3146a0` | `0x0061f8` | `0x65a497` | `0x65a000` | ✅ `0x65a000`，`0x7000` |

   **三段映射与 ELF 段表逐项吻合。** 结论：`libchunqiunative.so` 是**从 APK 内联映射**的，因此在 maps 中不以自己的文件名出现。所谓"SO 被卸载/抹除"不成立。

工程含义：要 dump 或 hook 这个库，必须按 APK 内联映射的地址（`base.apk` 的 `0x346000` 附近）去找，按文件名 grep 永远找不到。

### 3.4 实验性检测（Java 层，资源明文）

`aapt dump --values resources` 拿到 6 个实验性检测点的双语名称（`ExperimentalFragment` 实现，非 native）：

- `Mount Namespace（挂载命名空间）` / `Mount namespace anomaly`
- `PID Namespace（进程命名空间）` / `PID namespace anomaly`
- `UID Namespace mismatch（同 UID 命名空间不一致）` / `UID namespace mismatch for same UID`
- `Procfs view anomaly（代理 / 隔离的 /proc）` / `Procfs view anomaly (proxied / isolated /proc)`
- `OverlayFS system partitions（系统分区 OverlayFS）` / `OverlayFS on system partitions`
- `Early injected non-system libs（早期非系统库簇）` / `Early injected non-system libraries`

资源里还有 `Bootloader locked` / `Bootloader unlocked`、`Local attestation key (AOSP test root)` / `(OEM root)`、以及致谢名单（提到 `vvb2060/KeyAttestation` 作为 UI 参考）。

### 3.5 密钥认证

`KeyAttestation` / `KeyInfoActivity` / `RevocationChoiceActivity` 是独立的密钥认证模块：支持本地静态吊销库与在线吊销两种模式（`更改吊销检测方式后，请划掉后台重新打开应用以重新检测`）。这部分与 Android Key Attestation（`1.3.6.1.4.1.11129.2.1.17`）相关，是硬件级、最难伪造的一类信号。

### 3.6 反分析措施（实测）

- **截图被冻结**。应用启动完成后，`adb exec-out screencap` 连续返回**逐字节相同**的帧（连状态栏时钟都停在启动那一刻的 `23:17`）；同一时刻对启动器截图则正常变化。窗口 dump 中 `fl=` 未见 `FLAG_SECURE`，`mDisableSecureWindows=false`，说明不是常规的 secure-window 机制，但效果一致：**自动化读取界面结果的路径被封堵**。
- `uiautomator dump` 对该应用返回 `ERROR: null root node returned by UiTestAutomationBridge`。
- 主界面只显示**异常项**，不展示完整检测清单，因此也无法通过界面枚举它的检测项（本文的检测清单来自 §3.2 的 native 方法声明）。
- 尝试从运行内存捞取已解密字符串（扫描 934 个可写映射、按 ASCII 段过滤 `magisk`/`/system/bin/su`/`/data/adb` 等）**零命中**；大篆的解密字符串生命周期很短，或不落在可扫描的可写匿名段内。native 库的 `.rodata` 仅 24 KB（`.text` 2.7 MB），也说明 native 字符串同样是运行时解密。

### 3.7 实机结果

启动即判定 **"System compromised"**，但只列出 **2 组**异常：

| 异常项 | 内容 |
| --- | --- |
| MT 管理器(MT2文件夹)/异常文件 | 与 MT 管理器相关的目录/文件 |
| Risk apps | `moe.fuqiuluo.portaldev`、`top.hookvip.pro` |

**SukiSU Ultra、KernelSU、Zygisk、LSPosed、su 二进制、挂载异常、内核、SELinux —— 均未报出。** 与首页同时展示了 `MODEL: Xiaomi 24129PN74C (dada)`、`KERNEL: 6.6.118-android15-8-gc44b714366cc-abogki519650608-4k`、`SYSTEM VERSION: Android 16`、`SECURITY PATCH: 2026-08-01`。

strace 记录到的实际探测行为：大量 `/proc/<pid>/comm` 遍历（进程扫描）、`/proc/self/attr/current`（SELinux 上下文）、`/proc/self/exe`、`/proc/self/cmdline`、`/dev/__properties__/u:object_r:bootloader_prop:s0`（直接读属性区域，绕过 `getprop`）。

---

## 4. 两者对比与对 Root 隐藏的启示

| 维度 | 小骨检测 | 春秋 Native Check |
| --- | --- | --- |
| 检测位置 | 94% 在 Kotlin 层 | 100% 在 native（Java 只有 48 个声明） |
| 检测项数量 | 123 项 / 74 个检测器 | 48 个 native 例程 + 6 个实验性项 |
| 执行方式 | 串行、5 s 超时、逐项展示 | 启动时一次性跑完，只展示异常 |
| 加固 | 无 | 大篆片：字符串加密 + 混淆 + APK 内联 SO |
| 抗自动化 | 无（截图/OCR 可读） | 有（截图冻结、uiautomator 失效、只显示异常） |
| 最强的检测点 | 密钥认证链（OID + tag 709/710）、`getprop -T/-Z` 属性 serial、`UsageStats` vs `PackageManager` 的 App 隐藏对比、`st_dev`/inode 比对 | 48 个 native 例程中的 `detectKernelsuJbd2`、`detectHwBreakpoints`、`detectJniTableSource`、`detectSoTampering`、`detectFaccessatFchownatSidechannel`（未取得实现细节） |
| 最弱的环节 | **native 桥未初始化 → 所有 native 与差分检测作废**；10 个检测器死代码；13 处 fail-open | 只报异常项、可被 `ps`/`dmesg`/`getprop` 通道影响 |

**对本机（SukiSU Ultra）的实际意义：**

1. 两台检测器真正抓到的是**周边痕迹**，而不是 root 本身：Root 管理器 App 已安装（`com.sukisu.ultra`）、`adb` 已开启、MT 管理器、以及 `top.hookvip.pro` / `moe.fuqiuluo.portaldev` 这类被判定为风险的应用。**优先处理"装了什么"而不是"root 藏得够不够"**，收益最高。
2. 小骨检测的"全绿"有相当比例是假阴性，**不能作为隐藏效果的验收依据**。要评估隐藏质量，应看它报出的 4 个危险项，或换用 native 桥正常初始化的检测器。
3. 春秋的 `detectKernelsuJbd2`、`detectJniTableSource`、`detectSoTampering` 这类检查，是 SukiSU 侧真正需要关注的对象；但它们在本机未触发。
4. 两者都大量使用 `/proc` 直读（`mountinfo`、`maps`、`modules`、`comm`、`attr/current`、`fd`）与 `ps`/`dmesg`/`getprop` shell 通道。**SUSFS 一类的内核侧路径隐藏需要覆盖 `/proc` 直读，而不仅是 hook libc 或过滤 `ps` 输出**。

---

## 5. 复现步骤

```bash
# 1. 取包
adb shell pm path com.envdetector com.chunqiunativecheck
adb pull <base.apk> envdetector.apk ; adb pull <base.apk> chunqiu.apk

# 2. 资源明文（对加固包尤其有效）
AAPT=$REPO/.tooling/android-sdk/build-tools/35.0.0/aapt
$AAPT dump --values resources chunqiu.apk | grep -oE '"[^"]{3,}"'

# 3. Java 层
java -Duser.home=/tmp/jadxhome -jar jadx-1.5.6-all.jar -d out --no-res app.apk
# 或（对加固/大体积包更快、且能拿到真实 const-string）
python3 tools/dexmapper.py classes*.dex --filter '<包名前缀>' --mode strings

# 4. native 层
nm -D --defined-only libnative_detector.so          # 未 strip 时直接看符号
python3 tools/elfread.py lib.so secs                # 段表 / VMA
python3 tools/elfread.py lib.so dump 0x4a1e0 0x130  # .data 中的 JNINativeMethod 表
objdump -d --start-address=<JNI_OnLoad> lib.so      # 配合 .rela.dyn 还原注册表

# 5. 运行时
adb shell su -c "cat /proc/$(pidof <pkg>)/maps"     # 看 SO 到底有没有映射
adb shell su -c "cat /proc/$(pidof <pkg>)/mem"      # 需 root
adb shell su -c "strace -f -e trace=%file -p $(pidof <pkg>)"
adb logcat -d | grep 'avc:.*denied'                 # SELinux 审计日志会暴露探测目标

# 6. 界面取证
adb exec-out screencap -p > shot.png                # 注意：可能被应用冻结
TESSDATA_PREFIX=./tessdata tesseract shot.png - -l chi_sim --psm 6
```

**注意事项**

- `adb shell am start -n <pkg>/.MainActivity` 对未导出的 Activity 会抛 `SecurityException`，要用启动器 Activity（春秋是 `.SplashActivity`）。
- 通过 zygote 的 `wrap.<pkg>` 属性做全量 `strace` 在 Android 16 + enforcing SELinux 上不可用（脚本无法被 zygote 执行/读取，应用会起不来）；用"启动后尽快 `-p` 附加"的方式更可靠。
- jadx 在本沙箱下必须用 `-Duser.home=<可写目录>`，否则无法创建 `~/Library/Application Support/io.github.skylot.jadx` 而直接退出。
- 大篆包的解密器位于 `Lmuhuaya/String;->mh`。本次未完成该循环密钥的破译（4 段独立密钥、可用中文词频做区分度，但单字模型区分度不足）；资源层明文是更省力的替代路径。
