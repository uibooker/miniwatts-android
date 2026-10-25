# dada 第四轮：运行身份读取与独立审查

日期：2026-10-13。目标是核对当前手机与既有离线分析材料的构建身份、静态 HAL 文件和已知 class/driver 绑定。本代理只在电脑审查和解析证据，不执行 ADB，不操作手机。实际采集统一由主代理执行。

## 当前状态

本轮已经独立确认 **18 个当前加载模块的 GNU build ID 与相应离线 ELF 一致，20 个静态 HAL/KO 文件 SHA-256 对应，22 个 class/driver/I2C 链接目标有效**。新分析的 charger_partition、fg_comp 和三个无线模块均在这次身份对应范围内；18 个标准 initstate 均为 live。模块证据来自第三批的有效 partial，静态文件和完整链接来自第四批完成采集；没有把安全停止前的有效证据丢弃，也没有把失败输出计为成功字段。

主代理明确把标准元数据范围扩展到新增的三个无线模块以及已知 smart_charge 符号链接，合计 18 模块、22 链接路径和 20 静态文件。四批原始证据都保留，本代理没有操作手机。这些结果补齐了部分运行构建与绑定缺口，不能据此宣称物理旁路或恢复契约已经成立。

现有第三轮研究发现 `charger_partition_mishow` 与 `charger_partition_poweroffmode` 的 sysfs show 会经 alloc/read/dealloc 调用 SCSI 分区写入。因此，本轮只核对该模块的标准加载元数据、普通 KO 文件摘要以及 sysfs 符号链接，不读取这两个属性，也不调用 HAL getter。[L1]

## 1. 本轮允许范围

具体初始路径和失败规则冻结在 [initial-whitelist.json](../.tooling/bypass-check/stage4/identity-review/initial-whitelist.json)，新增三个模块及 smart_charge 链接由 [script-plan-review.json](../.tooling/bypass-check/stage4/identity-review/script-plan-review.json) 明确审查。

| 读取 | 用途及副作用边界 | root 要求 |
| --- | --- | --- |
| 固定七个 `ro.*` 的 `getprop`、`uname -r`、`id` | 核对产品、ROM、内核及采集身份；不调用充电接口 | 标准 shell 可读取时无需 root |
| `/proc/modules` | 标准 procfs 格式化当前模块列表；只用于选中模块加载状态对比，不提取地址或遍历硬件 | 权限由现场决定 |
| 固定 18 个模块的 `initstate`、`srcversion`、`notes/.note.gnu.build-id` | 标准 module 属性；initstate 格式化 state，srcversion 格式化保存字符串，note 从加载 note 缓冲区复制 | 权限由现场决定；仅同一固定读命令可以 root 重试 |
| 已知 class、device、driver、module 的符号链接 | `readlink` 查询链接文本或规范目标目录，不 open 属性内容，不运行充电 show | 本轮 class 路径的 canonical 解析和部分 metadata 确实需要 root，限定同路径重试 |
| 固定 MiCharge service、NDK 和选中 KO 普通文件 SHA-256 | 核对当前存储文件和官方包字节摘要；先确认普通文件且非 symlink，禁止任意新路径 | 普通文件权限不足时可用 root 读取同一目标 |

初始所选模块为 common、charge_interface、sysfs、buck/quick 策略、buckclass、proxy、ADSP、smart_charge、business_charger_comp、fg_comp、charger_partition、SC8581、HL7603、QTI PMIC GLINK；本轮再明确加入 basic_wireless、quick_wireless、wireless_revchg 的相同标准 handler。固定的普通文件目录为 `/vendor/bin/hw/`、`/vendor/lib64/` 与 `/vendor_dlkm/lib/modules/`；不扫描目录内其他文件。

读取仍会产生常规 CPU、文件缓存或认证记录等系统工作，不应描述为全系统绝对零扰动。这里审查的是没有电源控制回调、没有持久充电策略写入以及没有未知设备访问的限定路径。

## 2. 失败处理

设备数量或型号不符合预期时先停止。每项独立保留命令、stdout、stderr、退出码、字节数和 SHA-256；缺少可选标准属性、无权限、成功但空内容分别记录。不存在不等于空 srcversion 或空 build ID 匹配。

超时、异常类型、非预期输出或身份不一致时停止后续依赖，保留已采证据；没有挂载 debugfs、改权限、load/unload、服务重启、重新安装、自动扫描替代路径的处理。srcversion 缺失不需要替代读取，标准 build ID 不存在也不能转向 module sections、内存或寄存器。

旧 [collect_metadata.py](../.tooling/bypass-check/reconnect-acquisition/collect_metadata.py) 还包括 DT `find`、debugfs 目录和其他 metadata；它只是历史采集依据，本轮不能直接全脚本复跑。本轮以新的明确命令表为准。

## 2.1 新脚本的执行前审查

完整的 [capture_runtime.py](../.tooling/bypass-check/stage4/root/capture_runtime.py) 已手工审查；本代理没有 import 或执行该脚本，也没有运行 `--capture`。另用独立 AST 常量核对和命令重构确认 [read-plan.json](../.tooling/bypass-check/stage4/runtime/read-plan.json) 的 148 条请求完全属于上述已允许范围，具体记录见 [script-plan-review.json](../.tooling/bypass-check/stage4/identity-review/script-plan-review.json)。最初预审没有识别 exec-out 的远端退出码/双流限制；实际结果审查识别后，错误记录被冻结，后续以 shell v2 重新读取，详见下一节。

最初审查绑定 script SHA-256 `844646fff4c2a6c42b3d3e4cebf79a81dfefb4d4ab06bb9be090ece6a587a409` 和 plan SHA-256 `d54da8c7765dd93dc9304b99beba675f996eb97995be6d4ed5307250715fa4a3`。本代理按主代理逐次明确授权，另写各采集目录的 `review-approval.json` 作为目录外例外；实际采集仍由主代理负责。四批脚本、计划、批准和 manifest 均保留。

脚本先以普通 shell 读取；明确 denied 才在一次 uid=0 身份预检后重试同一固定命令。每条 remote command 整体用 `shlex.quote` 包装，不执行远端文件内容。链接先 `ls -ld`，只在类型为 symlink 时请求目标；remote 又有 `-L` guard。静态文件先核对普通非 symlink，再核对 canonical 目标仍在 vendor/vendor_dlkm，最后摘要读取。执行须保留 Python assertions，不能加 `-O` 或 `PYTHONOPTIMIZE`，因为当前审批 hash、计划相等及身份停止条件使用 assert。

一个不影响本轮读取范围的记录限制是：设备 discovery 若超时会立即停止，但脚本尚未落盘该超时返回的 partial stdout/stderr；发生这种情况时不能称 discovery raw record 完整。整个 capture 完成也不代表所有可选属性、链接和文件均存在或匹配，须由采集后独立核验分别判断。

## 2.2 传输错误与分批证据

| 批次 | 实际结果 | 可用范围与处理 |
| --- | --- | --- |
| `runtime` | discovery 阶段因本机 ADB daemon 无法绑定 smartsocket，报 Operation not permitted；固定请求 0，root 预检 0 | 保留失败 manifest 与 devices stdout/stderr；未覆盖重跑 |
| `runtime-retry` | `adb exec-out` 共 109 attempts；脚本在首个静态文件 lstat 的类型检查安全停止 | exec-out 将远端 stderr 混入 stdout，且远端失败被表现为返回 0。不能把 108 个 transport `ok` 当实际读取成功。系统属性与 `/proc/modules` 可用，12 个既有 driver/I2C/GLINK 链接目标有效；18 个 module initstate/note 输出其实是 Permission denied，18 srcversion 输出是 No such file 文本，class 目标空或 guard 文本，均不作有效字段 |
| `runtime-shell` | 改为 `adb shell -n -T`，无 `-x`；103 attempts，37 个 root attempts；在 class charger 的 canonical readlink 以 rc1、双空输出安全停止 | 18 GNU note 与 18 initstate 已经有效获得；18 srcversion 独立为 not_found。单个 class 目标失败不否定之前的有效模块证据 |
| `runtime-tail` | 93 项固定子集完成采集，共 124 attempts、60 root attempts：9 个身份、44 个链接 metadata/target、40 个静态 lstat/hash，没有 module 或 `/proc/modules` 重读 | 20 静态文件 SHA 全对应；22 链接目标有效。94 个 ok、29 个非 root denied 和 1 个 canonical rc1 error；后两类均按已审规则同命令 root 重试后取得有效结果 |

ADB v2 模式依据主机工具自身 [adb-help.stdout](../.tooling/bypass-check/stage4/root/adb-help.stdout) 的 78-84 行：`-n` 不读 stdin，`-T` 禁用 PTY，`-x` 才禁用远端退出码和 stdout/stderr 分离。本轮修正脚本没有 `-x`。

独立审查还发现权限传播问题：即使同路径 lstat 已因 denied 用 root 成功，后续默认 nonroot 的 `-L`/`-f` 仍可能因父目录权限静默为 false，被误判为非 symlink/非普通文件。因此第三版将已确认需要 root 的同 index/path lstat 结果传给其 guarded target/hash；第四版只为已成功确认 symlink/regular 的同路径 guard 48/45，或 canonical rc1 双空结果，增加一次同命令 root 重试。每次首次 root 都需 uid=0 预检，forced-root 失败直接停止。没有增加新路径、充电 getter、HAL 或写入。

第三版脚本 SHA-256 为 `4c4385826ad572e49a49dc39ff699bb8905f900e95405ff6f3e9214f5c1289f6`，第四版为 `eec63506c9959c63e28b49944ec035097c9249b66951f5d37be59b75cc3a99c4`。第四版 plan SHA-256 为 `28215e4a3d4de0cb1b7d9a48a3cbb143f6581ce13940480c2ed94c2597cc15aa`；93 个 request dict 逐项等于原 148 请求对应 label。批准记录和差异审查分别为 [shell-script-review.json](../.tooling/bypass-check/stage4/identity-review/shell-script-review.json)、[tail-script-review.json](../.tooling/bypass-check/stage4/identity-review/tail-script-review.json)。

四批手机操作账本如下；attempt 指主代理记录的远端固定命令调用，包括同命令失败和重试以及 root id 预检，不是成功身份项数。另有四次 `adb devices -l` 枚举，首批仅枚举失败。

| 批次 | 远端 attempts | root attempts | root id 预检 | 充电属性读取 / HAL / 控制写入 / 安装加载 |
| --- | ---: | ---: | ---: | --- |
| runtime | 0 | 0 | 0 | 均 0 |
| runtime-retry | 109 | 0 | 0 | 均 0 |
| runtime-shell | 103 | 37 | 1 | 均 0 |
| runtime-tail | 124 | 60 | 1 | 均 0 |
| 合计 | 336 | 97 | 2 | 均 0 |

336 条记录包含 334 条原白名单命令尝试和 2 条 root 身份预检；不是 336 个新增接口。四批每条 stdout/stderr 的 672 文件记录均按相应 manifest 独立重算 size/hash，审批 hash 与实际命令范围核对通过。debugfs、voter、寄存器、内存、服务重启、驱动加载、充电 getter 和供电控制都没有执行。账本见 [combined-runtime-identity.json](../.tooling/bypass-check/stage4/identity-review/combined-runtime-identity.json)。

## 3. 标准实现依据与版本边界

既有小米固定源码与 Linux v6.6 已核对 module metadata/getter 实现：[旧独立审查](DADA_RECONNECT_EVIDENCE_REVIEW.md)。本轮向 Android 官方 `kernel/common` 请求 uname 短提交 `f9a1d4bd8353` 的 commit/main.c/sysfs.c/procfs.c 精确 URL，均得到 HTTP 404，见 [exact-gki-source-attempt.json](../.tooling/bypass-check/stage4/identity-review/exact-gki-source-attempt.json)。这是四个精确请求的结果，不是整仓库源码不存在的结论。

同版本 Linux stable v6.6.77 的 tag 解析为固定提交 `c719455843a8bc3e969ba58ec92335ed7510e9fc`，commit 与三个 handler 文件请求均为 HTTP 200，大小和 SHA-256 保存在 [standard-source-acquisition.json](../.tooling/bypass-check/stage4/identity-review/standard-source-acquisition.json)。该来源只为标准读取语义提供参考，不证明它与当前 OEM/GKI 内核逐字节相同。

| 精确实现 | 核对结果 |
| --- | --- |
| `kernel/module/main.c:502` 的 `MODINFO_ATTR(srcversion)` | show 用 `scnprintf` 输出 `mk->mod->field`；test 只确认字符串非 NULL。不存在该属性与成功读取空字符串须区分 |
| `kernel/module/main.c:886` 的 `show_initstate` | 格式化模块 LIVE/COMING/GOING state，无充电 provider 调用；这是标准正常加载状态的实现范围 |
| `kernel/module/sysfs.c:149` 的 `module_notes_read` 及 `:204` 注册 | 将加载 note section 的缓冲区按 pos/count `memcpy` 给读取者；属性 mode 0444，不经驱动 show |
| `kernel/module/procfs.c:49` 与 `:74` | 读取模块链表时取得 module_mutex，并用 `seq_printf` 输出名称、大小、依赖、state 与 taint；不调用电源接口 |
| `fs/kernfs/symlink.c:111` 的 `kernfs_getlink` | 在 kernfs 读锁下格式化已有 parent/target 路径；不读取目标属性内容 |
| AOSP Android 16 `toolbox/getprop.cpp:74` 与 `:149` | 单值模式输出指定名字的 `GetProperty`；无参数才遍历所有属性。本轮只允许单值调用 |

来源片段还存于 [standard-handler-excerpts.json](../.tooling/bypass-check/stage4/identity-review/standard-handler-excerpts.json)。kernfs 与 AOSP 请求另见 [auxiliary-source-acquisition.json](../.tooling/bypass-check/stage4/identity-review/auxiliary-source-acquisition.json)；AOSP tag 对应 `system/core` 提交 `68be0c2c0006a0740d0b1809abe4717308f90d15`。这些参考实现不替手机上的工具或内核建立二进制身份。

补查 AOSP libbase `GetProperty` 内部实现的两个精确请求发生 TLS EOF，失败记录保存在 [property-source-acquisition.json](../.tooling/bypass-check/stage4/identity-review/property-source-acquisition.json)，没有取得该下层源码，也没有把失败算为主源证实。现有 getprop 源码依据只明确到标准单值模式调用 `GetProperty`，不是本机工具二进制校验。

本代理另以独立标准库解析器读取 18 份本地选中 ELF 的 section table，用明确 section file offset 取得 `.note.gnu.build-id`。每份均为单个 36 字节 GNU/type=3 note，descriptor 为 20 字节，结果保存在 [package-build-ids.json](../.tooling/bypass-check/stage4/identity-review/package-build-ids.json)。这只是新采集之前的离线对照基准。

## 4. 采集后可得和不可得的结论

[independent-runtime-shell-identity.json](../.tooling/bypass-check/stage4/identity-review/independent-runtime-shell-identity.json) 已独立核对第三批 103 attempts 的 206 个 stdout/stderr 文件大小与摘要、script/plan/approval 绑定和实际 root 重试规则，全部通过。每份有效 note 均重新按 namesz/descsz/type、GNU owner、四字节对齐及 descriptor 解析，原始 36 字节 note 与本地 ELF 对照相同。18 个 initstate 是有效 live 字符串；18 个 srcversion 返回 not_found，不是成功空值，也没有作 srcversion 匹配宣称。

系统仍识别为 dada / 24129PN74C，Android 16、`OS3.0.303.0.WOCCNXM`、内核 `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k`。`ro.product.vendor.device` 为 mivendor，vendor fingerprint 带 Android 15，而系统 fingerprint 带 Android 16；这些是所读字段的原值，不替代实际文件 hash 与 module note 对比。

[independent-runtime-tail-identity.json](../.tooling/bypass-check/stage4/identity-review/independent-runtime-tail-identity.json) 独立核对第四批 124 attempts 的 248 个 stdout/stderr 文件及每次实际 root 重试前提，全部通过。MiCharge service SHA-256 为 `0dcfb7b5816d962978e8724fdbb7798627f0575c6a72beae277c55b51f1f158b`，NDK 为 `42e7271e3da28f2e844b460b577e4a4f38463d1a57d7982ff776ca4c90c525fc`；这两个和 18 个固定 KO 的摘要全部与已有离线分析工件相同。它们是当前普通文件的摘要对应，没有执行或加载这些二进制。

最关键的现场 class 构造现在有直接对应：

| class 链接 | canonical platform 目标 | driver / module |
| --- | --- | --- |
| `/sys/class/xm_power/charger` | `/sys/devices/virtual/xm_power/charger` | class 分类设备 |
| `charger/charge_interface` | `/sys/devices/platform/soc/soc:mca_charge_interface` | `mca_charge_interface` / `mca_charge_interface` |
| `charger/charger_partition` | `/sys/devices/platform/soc/soc:charger_partition` | `charger_partition` / `charger_partition` |
| `charger/smart_charge` | `/sys/devices/platform/soc/soc:smart_charge` | `smart_charge` / `mca_smart_charge` |

其余既有 SC8581/SC8585、HL7603、QCOM subpmic proxy、MCA ADSP GLINK 的 driver/device/module 12 条链接也全部有效，精确路径与目标在第四批独立 JSON 中。这只核对链接；没有读取那些设备的 control/status 文件或当前回调注册表。

模块 note 的有效窗口为北京时间 **21:03:23–21:03:31**，静态摘要/链接窗口为 **21:11:09–21:11:18**；两个窗口 serial、kernel 和七个 ro property 值一致。它们间隔约八分钟，**不是原子 snapshot**，不能声称整个期间没有模块重载、进程映射或运行时状态变化。

普通 vendor 文件 SHA-256 对应可以支持“当前路径上的静态文件与官方分析工件相同”。module note 对应可以支持“当时已加载模块呈现与该 ELF 相同的声明构建 ID”。class/driver symlink 结果可以支持“当时该链接指向哪个 platform/driver/module 对象”。三项证据不能互相替代。

它们不能证明当前 MiCharge 进程映射或正在执行这份 service 文件、模块运行内存没有 patch、ADSP 对端执行身份或 PMIC 型号，更不能证明停充后系统保供、电池通路恢复或插件功能已经实现。本轮仍没有电气测量或候选开关 A/B。

## 5. 独立结果与证据冻结

主代理另用不同的 ELF/note 解析器生成 [runtime-validation.json](../.tooling/bypass-check/stage4/root/runtime-validation.json)。本代理逐项比对两套解析结果：18 个 module build ID 与 ELF hash、20 个静态文件 hash、22 个链接目标，共 60 项全部一致；记录见 [root-independent-cross-check.json](../.tooling/bypass-check/stage4/identity-review/root-independent-cross-check.json)。这项交叉核对不扩大上述构建身份与静态文件结论的范围。

相对本轮开始时的 [start-baseline.json](../.tooling/bypass-check/stage4/root/start-baseline.json)，802 个旧阶段证据文件和 35 个既有产品文件的大小及 SHA-256 均保持一致，见 [preservation-checks.json](../.tooling/bypass-check/stage4/identity-review/preservation-checks.json)。本代理没有修改插件产品或旧证据。最终 [manifest.json](../.tooling/bypass-check/stage4/identity-review/manifest.json) 绑定四批原始采集、脚本、计划、批准、本地 ELF/HAL 对照与本报告；冻结后的逐项重算结果另存 [manifest-verification.json](../.tooling/bypass-check/stage4/identity-review/manifest-verification.json)。清单自身及验证输出不进入清单哈希，避免自引用。手机操作账本仍以主代理的四批记录为准，本代理 ADB/手机操作为 0。

## 来源

- [L1] [第三轮 retail 策略报告](DADA_RETAIL_POLICY_STAGE3.md)：show 经 SCSI 写入与 cached getter 的区别。
- [L2] [第二轮 class 构造报告](DADA_CLASS_PATH_STAGE2.md)：class → link → platform 属性组的静态创建链。
- [L3] [旧重连独立审查](DADA_RECONNECT_EVIDENCE_REVIEW.md)：已加载 note 的独立解析、标准 metadata 源码及其版本边界。
- [L4] [本轮第一版白名单](../.tooling/bypass-check/stage4/identity-review/initial-whitelist.json)：只列允许目标，未执行手机操作。
- [S1] Linux stable v6.6.77 标准模块元数据：[main.c](https://kernel.googlesource.com/pub/scm/linux/kernel/git/stable/linux/+/c719455843a8bc3e969ba58ec92335ed7510e9fc/kernel/module/main.c)、[sysfs.c](https://kernel.googlesource.com/pub/scm/linux/kernel/git/stable/linux/+/c719455843a8bc3e969ba58ec92335ed7510e9fc/kernel/module/sysfs.c)、[procfs.c](https://kernel.googlesource.com/pub/scm/linux/kernel/git/stable/linux/+/c719455843a8bc3e969ba58ec92335ed7510e9fc/kernel/module/procfs.c)。
- [S2] 同固定 Linux commit 的 [kernfs symlink](https://kernel.googlesource.com/pub/scm/linux/kernel/git/stable/linux/+/c719455843a8bc3e969ba58ec92335ed7510e9fc/fs/kernfs/symlink.c)，只作链接查询实现依据。
- [S3] AOSP Android 16 固定 `system/core` 提交的 [getprop.cpp](https://android.googlesource.com/platform/system/core/+/68be0c2c0006a0740d0b1809abe4717308f90d15/toolbox/getprop.cpp)，只作标准单值 getprop 模式依据。
