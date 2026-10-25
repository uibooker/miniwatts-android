# 新 ROM MiCharge 接口独立审查

日期：2026-10-13。范围为电脑上已提取的 `vendor.xiaomi.hardware.micharge-service` 和 `vendor.xiaomi.hardware.micharge-V2-ndk.so`。没有执行手机、ADB、Binder/RPC、sysfs、插件写入或安装操作，没有增加手机白名单。

审查者仅向 native 代理取得有限窗口坐标；使用项目 LLVM 23.1.3 的 `llvm-objdump` 从原 ELF **重新解码**，独立解析 `.dynsym`、NDK 跳转表和服务 `.relr.dyn`。没有把 native 的文字结论、annotation 或 method-map 当证明来源。原始字节、重新解码片段和检查结果见 `.tooling/bypass-check/stage1/power/native-review.json` 与同目录 `native-review-*.txt`；提取程序是 `inspect_native_review.py`。

## 审查结果

四项关键映射通过离线核验，但没有形成可用旁路控制接口：

1. tx43 `setMiChargePath` 使用服务的 key→path 表，**不是将 caller key 直接作为任意 sysfs 路径**。表初始化片段中，`charge_enable` 键的 value 从 global `0x124058` 复制；该 global 的初始化字节来自 `/sys/class/xm_power/charger/charge_interface/input_suspend`。不能按 `charge_enable` 的字面名称把这个调用认作 MCA 的 charging-enable 控制。
2. tx42 `setInputSuspendState` 将固定前缀 `micharge all ` 与 caller 字符串拼接，写到同一 global 指定的 `input_suspend` 路径。它没有在本次有限链中调用独立申请/撤销接口。
3. tx53/54 `getChargeCommonInfo/setChargeCommonInfo` 的原厂实现仅日志与 `AStatus_newOk`，没有写 getter 字符串或 setter 整数输出；NDK dispatcher 在调用前预清空输出。因此成功响应及默认空串/0 不能证明查询或控制完成。
4. tx43 把 path/value 复制到独立对象并启动 detached thread，等待 deadline；超时分支写整数 -1，但仍返回 Binder OK。在已审查方法、thread dispatcher 和 worker 中没有取消写入步骤；worker 执行写入在发布完成状态之前。因此不能把超时解释为未写入或已经回滚。

这四项是**磁盘包实现的静态行为**，不是实机调用结果。主审另行完成官方包与已采磁盘 ADSP 文件的逐字节同源对照；这也不证明当前运行的 HAL、ADSP 或 PMIC 配置。此报告没有扩展这些运行身份结论。

## 方法身份：独立重建 tx 到实现

NDK Bp 的动态符号包含真实方法名和完整符号范围，函数内的 `AIBinder_transact` 参数以 w1 装载 transaction number。NDK Bn 在 `0xc11c..0xc13c` 用 `code-1` 索引 `0x7578` 的 uint16 表，target=`0xc140 + entry*4`。服务 constructor `0x23640..0x23648` 把 vptr 设为 `0x38048`；以下槽位的原始 pointer 和 RELR bitmap 均独立核对。

| tx / NDK Bp 符号范围 | Bn dispatch / 槽位 | 服务 slot 地址 / 实现 | 判断 |
| --- | --- | --- | --- |
| 42 `setInputSuspendState`，`0x12c40` size `0x260` | `0xc800` / `+0x178` | `0x381c0→0x25024` | 固定 input-suspend 路径与前缀 |
| 43 `setMiChargePath`，`0x12ea0` size `0x290` | `0xcacc` / `+0x180` | `0x381c8→0x265a8` | 查 key 表，异步写入 |
| 53 `getChargeCommonInfo`，`0x146b0` size `0x268` | `0xcd08` / `+0x1d0` | `0x38218→0x2c890` | 原厂日志 + Binder OK，未填写字符串 |
| 54 `setChargeCommonInfo`，`0x14918` size `0x290` | `0xc26c` / `+0x1d8` | `0x38220→0x2c8d0` | 原厂日志 + Binder OK，未填写整数 |

这里的虚表 pointer 使用 **RELR 相对重定位**，不是普通 RELA addend。审查程序解码 direct address / 63-word bitmap，确认这些位置属于 relocation 集合，并检查 ELF 中的原始相对 pointer。运行时加 load base 的行为没有改成可供手机调用的固定地址。

## charge_enable 键实际复制 input_suspend 路径

两段初始化和后续使用的独立证据如下：

| 有限窗口 | 原始指令所支持的值流 |
| --- | --- |
| `0x2f298..0x2f2e8` | 分配 64-byte buffer，从 rodata `0x4404` 复制 58 字符，末尾置 NUL；global `0x124058` 储存 long-string layout：capacity/tag、size=58、buffer pointer。rodata 字符串准确值为 `/sys/class/xm_power/charger/charge_interface/input_suspend` |
| `0x31e40..0x31ea8` | rodata `0x3df0` 是完整键 `charge_enable`；写 key 到 `sp+0x350`。value 地址由 `(sp+0x50)+0x318=sp+0x368` 得到，从 global `0x124058` 复制字符串：short 分支复制 24 bytes，long 分支用 pointer/size 拷贝 |
| `0x34528..0x34568` | 对 map `0x124d48`，起点 `sp+0x50`、步长 `0x30`、终点 offset `0x14a0` 调插入 helper；`sp+0x350/sp+0x368` 正是其中一对 key/value |
| tx43 `0x265dc..0x26618` | x0=`0x124d48`，x1 保持 caller key，调用 `0x2e7a4`；找到 node 后复制 node+`0x28` value 到本地 path |
| lookup `0x2e7a4..0x2e968` | 使用 hash/bucket，并比较 key 长度和字节（short 字节循环 / long `memcmp`）；未命中返回 NULL。它不把 caller key 直接当 filesystem path |

这闭合了初始化表中 `charge_enable→input_suspend` 的映射。审查范围没有对全服务所有 global 写入和后续 map 修改做不变性证明，因此结论限定为这份原厂初始化及所审调用链；没有声称 runtime map 永远不能被其他代码改变。也没有证明这个 `/sys/class/xm_power/...` 路径在连接手机上的 alias 解析或具体驱动绑定。

tx42 在 `0x250c0..0x250ec` 复制 rodata `0x2ff8` 的 `micharge all `；长度为13。其后追加 caller 字符串并补 NUL，`0x25110` 取 global `0x124058`，`0x2511c` 调 writer `0x239c0`。`0x25124` 将 writer 返回值写到整数输出，`0x25134` 单独构造 Binder OK。因此必须区分 Binder status、整数写入结果及硬件结果。固定 caller 前缀也不构成 enabled=0 的撤销参数；本次没有新增调用方案。

## 异步 writer、超时与错误语义

tx43 在 key 未命中时记录 `Not Support` 日志并返回 Binder OK，未给整数输出赋失败值；NDK 在 `0xcaf0` 预置整数0。未命中的0同样不是控制成功。

命中时，服务将 path/value 分别复制到 async object `+0x78/+0x90`，对象 vptr=`0x38260`。独立 RELR pointer `0x38278`，即 vptr+`0x18`，指向 worker `0x2eaa4`。`0x267f0` 的 thread entry=`0x2ebbc`，`0x26800` 调 `pthread_create`；其后 `thread::detach`。thread entry 用指针成员的虚调编码找到 vptr+`0x18` worker。

worker 在 `0x2eae4` 用 `access(path,0)` 查存在性，存在时 `0x2eb1c..0x2eb24` 将 object+`0x78` path 与 +`0x90` value 传入 `0x239c0`。随后才加锁、设置完成位与整数结果并 notify。这条链没有 phone whitelist：`access` 之后是真正的 `open/write`。

| 层 | 已证实语义 | 无法推导的结果 |
| --- | --- | --- |
| writer `0x239c0..0x23acc` | `__open_2(path,0x802)`，`__write_chk(fd,value,length,-1)`，`close(fd)`；open 失败或 write 结果的 W22 bit31 为1时返回 -1，否则返回0 | 没有核对短写等于请求长度，没有检验 close 返回；0不是 MCA 聚合/provider/硬件执行证明 |
| deadline `0x2688c..0x268a8` | `steady_clock::now` 加 `0x1dcd6500=500000000`；标准 Android libc++ 纳秒 clock ABI 下是500ms，非实测耗时。调 `0x2ec38` 等待完成状态/时间截止 | 并未为 sysfs writer 配置可中止期限 |
| wait helper `0x2ec38..0x2ed54` | 查完成位，使用 `pthread_cond_clockwait` 与 deadline，返回状态；没有调用取消 thread/write 的接口 | wait 超时不能证明 worker 已停止 |
| timeout `0x268b0..0x268e0` | 整数=-1，日志 `Timeout while writing to node %s`，同时返回 `AStatus_newOk` | 超时不能当作未执行、自动撤销或原态已恢复 |
| 正常完成 `0x26974..0x269a8` | 取异步结果到整数输出，再单独返回 Binder OK | 仍只反映上表 writer 的软件结果 |

本报告没有发明迟到写入实机事故。明确结论是：已审链没有 cancellation/rollback 契约，后台写入与调用者等待是分开的。若将来采用此接口，需要先解决部分执行、迟到执行、错误保留和恢复确认；当前不具备可交付的控制与恢复方案。

## CommonInfo stub 与默认输出

服务 `0x2c890..0x2c8d0`、`0x2c8d0..0x2c910` 的完整函数都只有两次直接 call：`__android_log_print` 与 `AStatus_newOk`。日志准确字符串分别是 `micharge getChargeCommonInfo`、`micharge setChargeCommonInfo`。函数只写 hidden Binder status 输出，没有使用 caller 输出指针填结果，没有间接控制调用。

NDK tx53 `0xcd24/0xcd28` 预清空 `sp+0x18` 的字符串，call 后将其序列化；tx54 `0xc290` 将 `sp+0xc` 整数清零，公共尾 `0xcf18..0xcf20` 序列化这个整数。服务 stub 的 Binder OK 因此可伴随默认空串/0。这里没有可用于查询 charge enable 或申请/释放停充的真实实现，不能根据 AIDL 方法存在就认定厂家已提供支持。

## 证据完整性与实施决定

本次独立提取23个有限窗口，45项检查通过：fresh disassembly 每个 instruction word 与原 ELF 字节一致；Bp 符号/transaction number、Bn 跳转、服务与 worker RELR pointer、原始 rodata、关键输出/超时指令以及两个 stub 的完整 call 列表均核对。

原 ELF SHA256：

- service：`0dcfb7b5816d962978e8724fdbb7798627f0575c6a72beae277c55b51f1f158b`，1201336 bytes。
- NDK：`42e7271e3da28f2e844b460b577e4a4f38463d1a57d7982ff776ca4c90c525fc`，146728 bytes。

该范围没有找到同时满足申请、查询、释放与恢复确认的厂商入口。tx43 的 `charge_enable` 初始化映射、tx42 的 input suspend 写入和 CommonInfo stub 都不能满足“停止电池充电并保留系统输入供电”的证明要求。现有插件后端继续按已验证的 FCC 限流评价；没有加入本次 HAL 控制、直接寄存器操作或试验命令。
