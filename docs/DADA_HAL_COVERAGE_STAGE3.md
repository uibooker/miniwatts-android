# DADA 官方 MiCharge HAL 第三轮覆盖收敛

日期：2026-10-13。本轮只在电脑分析已取得的官方 ELF；没有 ADB、手机读写、HAL/Binder 调用、安装、驱动加载、网络访问、目标程序执行或上游脚本执行。

**官方 MiCharge 的 56 个方法已按真实函数边界收齐高层实现覆盖，15 个 setter 均有去向分类。新深查的 NightCharging、TypeCCommon、BatteryCommon 没有交付可用于插件的完整独立申请/释放接口。** NightCharging 是固定 `smart_night` 文件的原样 String 读写；TypeCCommon 是固定 key 的 USB HID 手柄与固件逻辑；BatteryCommon 是记日志并返回 Binder OK 的空实现。这个结论不排除整个固件还有别的入口，也不等于所有充电策略键都已经完成下游审查。

## 样本与证据边界

沿用 [Stage 1 的官方 OTA 与方法签名固定点](DADA_NATIVE_INTERFACE_STAGE1.md)：[OS3.0.303.0.WOCCNXM 官方 OTA](https://cdnorg.d.miui.com/OS3.0.303.0.WOCCNXM/dada-ota_full-OS3.0.303.0.WOCCNXM-user-16.0-527efd3838.zip)。这里是包内 ELF 的静态结论，当前手机运行实例身份仍属于主审独立核对的边界。

| 文件 | SHA-256 / Build ID |
| --- | --- |
| `vendor/bin/hw/vendor.xiaomi.hardware.micharge-service` | `0dcfb7b5816d962978e8724fdbb7798627f0575c6a72beae277c55b51f1f158b` / `7d86416112f836f15071f609c9b50b9f` |
| `vendor/lib64/vendor.xiaomi.hardware.micharge-V2-ndk.so` | `42e7271e3da28f2e844b460b577e4a4f38463d1a57d7982ff776ca4c90c525fc` / `78ada333da98e40a4727b4fa3c5d38ae` |

方法名、签名、transaction、vtable offset 与目标取自 [已固定的 56-method map](../.tooling/bypass-check/stage1/native/ota-method-map.json)。本轮另从 service 的 `.eh_frame` 读取 [124 个 FDE](../.tooling/bypass-check/stage3/native/service-fdes.json)，由 FDE 起点和 `address_range` 定义函数区间，**不使用“下一个方法目标”代替函数结束**。56 方法对应 55 个独立函数：tx15/41 共用 `0x25a20..0x25a60`。

[机器覆盖表](../.tooling/bypass-check/stage3/native/method-coverage.json) 保存每个方法完整函数窗口、FDE、直接 `bl`、间接 `blr` 和跳出函数的 `b`。内部普通跳转没有被错误计成函数调用。完整 opcode 窗口来自明确命名的 `.text`；字面量来自明确命名的 `.rodata`。独立 verifier 重新解析 ELF 节，检查所有窗口界限，调用本机 LLVM 重反汇编并逐条对比四字节原始 opcode；同时重取 FDE 和检查 Stage 1/2 文件保持不变。本轮没有再使用可能把多个零地址 section 混淆的抽取方式。

## 56 方法高层覆盖

“固定读取”仅表示完整函数调用 common reader `0x23694`；不是该文件的硬件语义、安全读取白名单或每个下游节点的完整验证。“支持查询”按实际 `access` / 属性 / 表查找 / reader 路由分类。“固定写入”表示使用 common writer `0x239c0`，其错误边界仍受 Stage 1 已查语义限制。

| tx | 方法 | 实际 FDE 区间（结束不含） | 实现分类 / 关键调用 |
| --- | --- | --- | --- |
| 1 | `getBatteryAuthentic` | `0x25a60..0x25b40` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-01.txt) |
| 2 | `getBatteryCapacity` | `0x248a0..0x24930` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-02.txt) |
| 3 | `getBatteryChargeFull` | `0x24290..0x24320` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-03.txt) |
| 4 | `getBatteryChargeType` | `0x24320..0x24400` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-04.txt) |
| 5 | `getBatteryCycleCount` | `0x24120..0x241b0` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-05.txt) |
| 6 | `getBatteryIbat` | `0x24930..0x249c0` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-06.txt) |
| 7 | `getBatteryResistance` | `0x24a50..0x24b30` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-07.txt) |
| 8 | `getBatterySoh` | `0x241b0..0x24290` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-08.txt) |
| 9 | `getBatteryTbat` | `0x249c0..0x24a50` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-09.txt) |
| 10 | `getBatteryThermaLevel` | `0x24b30..0x24bc0` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-10.txt) |
| 11 | `getBatteryVbat` | `0x24810..0x248a0` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-11.txt) |
| 12 | `getBtTransferStartState` | `0x25cec..0x25d7c` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-12.txt) |
| 13 | `getCarChargingType` | `0x25edc..0x25fbc` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-13.txt) |
| 14 | `getChargingPowerMax` | `0x25dbc..0x25e9c` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-14.txt) |
| 15 | `getCoolModeState` | `0x25a20..0x25a60` | [log/status stub](../.tooling/bypass-check/stage3/native/method-15.txt) |
| 16 | `getFastChargeModeStatus` | `0x25780..0x25860` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-16.txt) |
| 17 | `getInputSuspendState` | `0x24f44..0x25024` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-17.txt) |
| 18 | `getMiChargePath` | `0x26a04..0x26e84` | [110-key 表 → 异步读取（Stage 1）](../.tooling/bypass-check/stage3/native/method-18.txt) |
| 19 | `getNightChargingState` | `0x25b40..0x25c20` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-19.txt) |
| 20 | `getPSValue` | `0x2644c..0x264dc` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-20.txt) |
| 21 | `getPdApdoMax` | `0x24730..0x24810` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-21.txt) |
| 22 | `getPdAuthentication` | `0x24650..0x24730` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-22.txt) |
| 23 | `getQuickChargeType` | `0x24bc0..0x24cd4` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-23.txt) |
| 24 | `getSBState` | `0x262a0..0x26380` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-24.txt) |
| 25 | `getSocDecimal` | `0x25860..0x25940` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-25.txt) |
| 26 | `getSocDecimalRate` | `0x25940..0x25a20` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-26.txt) |
| 27 | `getTxAdapt` | `0x24570..0x24650` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-27.txt) |
| 28 | `getUsbCurrent` | `0x24490..0x24570` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-28.txt) |
| 29 | `getUsbVoltage` | `0x24400..0x24490` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-29.txt) |
| 30 | `getWirelessChargingStatus` | `0x25508..0x255e8` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-30.txt) |
| 31 | `getWirelessFwStatus` | `0x26144..0x261d4` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-31.txt) |
| 32 | `getWirelessReverseStatus` | `0x24cd4..0x24db4` | [固定读取 → common reader](../.tooling/bypass-check/stage3/native/method-32.txt) |
| 33 | `isBatteryLifeFunctionSupported` | `0x24db4..0x24f44` | [支持/布尔查询；access + property_get](../.tooling/bypass-check/stage3/native/method-33.txt) |
| 34 | `isDPConnected` | `0x270e4..0x27244` | [支持/布尔查询；access + reader + strcmp](../.tooling/bypass-check/stage3/native/method-34.txt) |
| 35 | `isFunctionSupported` | `0x26e84..0x26fd4` | [支持/布尔查询；表查找 + access](../.tooling/bypass-check/stage3/native/method-35.txt) |
| 36 | `isUSB32` | `0x26fd4..0x270e4` | [支持/布尔查询；access + reader + strcmp](../.tooling/bypass-check/stage3/native/method-36.txt) |
| 37 | `isWirelessChargingSupported` | `0x25178..0x25468` | [支持/布尔查询；access + property_get](../.tooling/bypass-check/stage3/native/method-37.txt) |
| 38 | `isWiressFwUpdateSupported` | `0x25fbc..0x26144` | [支持/布尔查询；access + property_get](../.tooling/bypass-check/stage3/native/method-38.txt) |
| 39 | `setBtState` | `0x255e8..0x256b4` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-39.txt) |
| 40 | `setBtTransferStartState` | `0x25d7c..0x25dbc` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-40.txt) |
| 41 | `setCoolModeState` | `0x25a20..0x25a60` | [log/status stub](../.tooling/bypass-check/stage3/native/method-15.txt) |
| 42 | `setInputSuspendState` | `0x25024..0x25178` | [固定写入，加 micharge all 前缀（Stage 1）](../.tooling/bypass-check/stage3/native/method-42.txt) |
| 43 | `setMiChargePath` | `0x265a8..0x26a04` | [110-key 表 → 异步写入（Stage 1）](../.tooling/bypass-check/stage3/native/method-43.txt) |
| 44 | `setNightChargingState` | `0x25c20..0x25cec` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-44.txt) |
| 45 | `setRxCr` | `0x256b4..0x25780` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-45.txt) |
| 46 | `setSBState` | `0x26380..0x2644c` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-46.txt) |
| 47 | `setSmCountReset` | `0x264dc..0x265a8` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-47.txt) |
| 48 | `setUpdateWirelessFw` | `0x261d4..0x262a0` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-48.txt) |
| 49 | `setWirelessChargingEnabled` | `0x25468..0x25508` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-49.txt) |
| 50 | `setWlsTxSpeed` | `0x25e9c..0x25edc` | [固定写入 → common writer](../.tooling/bypass-check/stage3/native/method-50.txt) |
| 51 | `getTypeCCommonInfo` | `0x29af0..0x2a8e4` | [显式 key → HID/软件/固件](../.tooling/bypass-check/stage3/native/method-51.txt) |
| 52 | `setTypeCCommonInfo` | `0x2aebc..0x2c890` | [显式 key → HID/软件/固件](../.tooling/bypass-check/stage3/native/method-52.txt) |
| 53 | `getChargeCommonInfo` | `0x2c890..0x2c8d0` | [log/status stub](../.tooling/bypass-check/stage3/native/method-53.txt) |
| 54 | `setChargeCommonInfo` | `0x2c8d0..0x2c910` | [log/status stub](../.tooling/bypass-check/stage3/native/method-54.txt) |
| 55 | `getBatteryCommonInfo` | `0x2c910..0x2c950` | [log/status stub](../.tooling/bypass-check/stage3/native/method-55.txt) |
| 56 | `setBatteryCommonInfo` | `0x2c950..0x2c990` | [log/status stub](../.tooling/bypass-check/stage3/native/method-56.txt) |

计数：30 固定 reader；9 固定 writer；6 log/status stub；6 支持或布尔查询；2 TypeC 固定 key 实现；1 表/异步 reader；1 带前缀固定 writer；1 表/异步 writer。该计数覆盖的是 HAL 方法实现，不是 110 个表键的下游驱动覆盖率。

## 全部 15 个 setter 的实际去向

| tx | setter | 路由 / 固定目标 | 输入与结果边界 |
| --- | --- | --- | --- |
| 39 | `setBtState` | `/sys/class/xm_power/wireless_master/bt_state`；`0x25668→0x239c0` | raw String；writer int + Binder OK |
| 40 | `setBtTransferStartState` | `/sys/class/xm_power/wireless_master/bt_transfer_start`；`0x25d9c→0x239c0` | raw String；writer int + Binder OK |
| 41 | `setCoolModeState` | log/status stub；tx15/41 同一函数 | 不处理输入、不写 int*out；Binder OK |
| 42 | `setInputSuspendState` | 固定 input_suspend；Stage 1 既有证据 | String 加 micharge all 前缀；writer int |
| 43 | `setMiChargePath` | 110-key 表，detached worker；Stage 1 | 500 ms 等待，超时不取消；未知 key 不写 int |
| 44 | `setNightChargingState` | `/sys/class/xm_power/charger/smart_charge/smart_night`；`0x25ca0→0x239c0` | raw String；writer int + Binder OK |
| 45 | `setRxCr` | `/sys/class/xm_power/wireless_master/rx_cr`；`0x25734→0x239c0` | raw String；writer int + Binder OK |
| 46 | `setSBState` | `/sys/class/xm_power/charger/smart_charge/smart_batt`；`0x26400→0x239c0` | raw String；writer int + Binder OK |
| 47 | `setSmCountReset` | `/sys/class/xm_power/charger/charge_interface/shipmode_count_reset`；`0x2655c→0x239c0` | raw String；writer int + Binder OK |
| 48 | `setUpdateWirelessFw` | `/sys/class/xm_power/charger/wls_rev_charge/wireless_chip_fw`；`0x26254→0x239c0` | raw String；writer int + Binder OK |
| 49 | `setWirelessChargingEnabled` | `/sys/class/xm_power/charger/wls_rev_charge/reverse_chg_mode`；`0x254bc→0x239c0` | bool to ASCII 0/1；writer int + Binder OK |
| 50 | `setWlsTxSpeed` | `/sys/class/xm_power/wireless_master/wls_tx_speed`；`0x25ebc→0x239c0` | raw String；writer int + Binder OK |
| 52 | `setTypeCCommonInfo` | 8 显式 key；HID/软件/固件 | String key/value；不写 int*out，部分错误 Binder 异常 |
| 54 | `setChargeCommonInfo` | log/status stub | 不处理输入、不写 int*out；Binder OK |
| 56 | `setBatteryCommonInfo` | log/status stub | 不处理输入、不写 int*out；Binder OK |

九个固定 writer 的 [路径闭合证据](../.tooling/bypass-check/stage3/native/fixed-setter-paths.json) 逐个关联字面量、global 初始化和写调用，保留原始路径字节。tx47 的硬件意义引用 [Stage 2](DADA_NATIVE_INTERFACE_STAGE2.md)，tx42/43/54 引用 [Stage 1](DADA_NATIVE_INTERFACE_STAGE1.md)，本轮不重做它们的下游硬件研究。

tx40/50 完整函数都只有 `0x40` 字节，却分别直接调用 writer，并把 `w0` 存到 `int*`：`0x25d9c/0x25da0` 与 `0x25ebc/0x25ec0`。因此不能按函数短、没有复制 String 或相邻方法是 stub 判断它们为空实现。

tx49 的 bool 在 `0x25490..0x254b8` 转成短 String 的 ASCII `0`/`1`，`0x254bc` 调 writer。它的 global `0x124a78` **与 `0x124a60` 相同路径**，路径为 `reverse_chg_mode`。初始化 `0x30f74..0x31004` 中，前一 global 载入路径后把 q1/q0 保存到 `sp+0x20`，q0/q2 保存到 `sp`；后一 global 从两处缓存复用全部路径字节，不应因没有独立 literal load 而猜成另一个“charging enable”文件。这里没有对 `reverse_chg_mode` 驱动语义作新的结论。

## NightCharging：tx19 / tx44

签名为 `getNightChargingState(String*)` 和 `setNightChargingState(const String&, int*)`。NDK 的 `0xc918` / `0xc244` 分别加载 vtable `+0xc0` / `+0x188`，对应 service `0x25b40..0x25c20` / `0x25c20..0x25cec`。见 [getter 完整函数](../.tooling/bypass-check/stage3/native/method-19.txt)、[setter 完整函数](../.tooling/bypass-check/stage3/native/method-44.txt)、[NDK tx19](../.tooling/bypass-check/stage3/native/ndk-tx19.txt)、[NDK tx44](../.tooling/bypass-check/stage3/native/ndk-tx44.txt)。

global `0x124100` 的源 literal 在 `.rodata:0x28e8`，构造闭合在 `0x2f4ac..0x2f4f8`：`/sys/class/xm_power/charger/smart_charge/smart_night`。getter 的 `0x25bb8` 调 common reader。setter 复制路径 global，保留传入 String 原始内容，`0x25ca0` 调 common writer，`0x25ca4` 把 writer 返回值写入 `int*`，随后返回 Binder OK。

HAL 这一层没有单独 owner、enabled、lease/timeout、取消、请求状态或撤销方法；输入仅有一个未被 HAL 结构化解释的 String。getter 读取的是该文件内容，不能据此证明它返回“本插件请求是否生效”。setter 不创建 worker，也没有本层的异步超时问题。此处没有用名称推导 `smart_night` 的电池/系统供电方向，没有尝试构造 payload，更没有证明写 `0` 能仅撤销本插件请求或恢复原有策略；这些仍取决于下游实现。本轮新证据只闭合 HAL→固定文件。

common writer 的 open/write/close 与 `0/-1` 边界已在 Stage 1 核对：写失败不等于 Binder 失败；不验证完整短写/close 成功。本轮不重复该分析。NDK 的 setter 输出先置 0，随后调用服务并序列化 int；NightCharging 本身确实写回 writer int，不能与 BatteryCommon 的预置 0 混为一谈。

## TypeCCommon：tx51 / tx52

签名为 `getTypeCCommonInfo(const String& key, String*)` 和 `setTypeCCommonInfo(const String& key, const String& value, int*)`。NDK `0xc1fc` / `0xceec` 加载 vtable `+0x1c0` / `+0x1c8`，实际实现是 `0x29af0..0x2a8e4` / `0x2aebc..0x2c890`。后一个函数包含多个固件缓冲分支，不能用两端相邻方法地址裁出一个假的短函数。见 [getter](../.tooling/bypass-check/stage3/native/method-51.txt)、[setter](../.tooling/bypass-check/stage3/native/method-52.txt)、[完整 key 与 literal bytes](../.tooling/bypass-check/stage3/native/typec-explicit-keys.json)。

getter 的原始 key 被保留在 x21，输出 String* 在 x20。全函数有 **7 次 strcmp**，都是以下 key 比较；5 个硬件查询会先 write 命令，再 read 响应，故不能把 getter 当作纯只读手机检查入口。报告只记协议 key，不读取或展示设备序列号、密钥或返回数据。

| key（完整集合） | literal VA / strcmp VA | 实际分支路由 |
| --- | --- | --- |
| `getHandleOtaState` | `0x2a22` / `0x29b5c` | software String global 0x124d70 |
| `getHandleColor` | `0x37ce` / `0x29b7c` | HID command write and read |
| `getUsbHandleSN` | `0x5f14` / `0x29d78` | HID command write and read |
| `getUsbHandleSecretText` | `0x56f9` / `0x29fec` | HID command write and read |
| `getUsbHandleSecretIV` | `0x4156` / `0x2a1f4` | HID command write and read |
| `getUsbHandleSecretKey` | `0x3896` / `0x2a3fc` | HID command write and read |
| `getUpdateState` | `0x5386` / `0x2a600` | software String global 0x3c020 |

两个软件查询从 String global `0x124d70` / `0x3c020` 复制结果（`0x2a644..0x2a730`），并非按 owner 查询充电请求。未知 key 在 `0x2a608` 返回 Binder OK，未填写输出 String；NDK 预先构造的空 String 会被序列化。因此未知 key 的“成功+空字符串”不证明接口支持它。

setter 保存 key 到 x21、value 到 x20（`0x2aeec/0x2aef4`），前 6 次比较用 x22 raw key，随后 `0x2afa4..0x2afbc` 重新从 x21 取得 **key** 再比较剩余两项。全函数有 10 次 strcmp：**8 项 primary key** 与 2 项 value 子比较。`boot` / `normal` 位于 `0x2b59c/0x2b5d0`，输入是 `sp+0x40` 的 value String，不能多算成 common key。

| key（完整集合） | literal VA / strcmp VA | 实际分支路由 |
| --- | --- | --- |
| `PhoneBatteryChanged` | `0x453c` / `0x2af20` | value decimal -> HID 5-byte report via 0x2ce5c |
| `screenStateChanged` | `0x5d9d` / `0x2af34` | pthread_create 0x2a8e4 -> HID 5-byte report |
| `startSendFirmFile` | `0x37dd` / `0x2af48` | parse size and allocate firmware global buffers |
| `sendHandleFWMD5` | `0x5026` / `0x2af5c` | copy value to global 0x124e18 via strncpy |
| `sendFirmFileBuffer` | `0x4d11` / `0x2af70` | parse chunks, firmware buffer/MD5, pthread_create 0x27374 |
| `stopSendFirmFile` | `0x3040` / `0x2af88` | log only, fall through remaining key comparisons; no cancel |
| `isHandleBootMode` | `0x3051` / `0x2afc0` | value boot/normal comparisons and logs only |
| `setCameraStateToUsbHandle` | `0x5db0` / `0x2afd4` | value decimal -> HID 5-byte report via 0x2ce5c |

`PhoneBatteryChanged` 与 `setCameraStateToUsbHandle` 分支把 value 按十进制解析为字节，分别由 `0x2b178/0x2b82c` 打开 HID，再由 `0x2b1dc/0x2b890` 发送 5 字节报告；看到的是电话电池/相机状态发给手柄，没有 common charge sysfs writer 调用。`screenStateChanged` 在 `0x2b074` 创建函数 `0x2a8e4` 的线程，该 worker 比较 `on`/`off` 并写 5 字节 HID 报告。创建线程成功的 Binder 返回不能证明异步发送成功。

`sendFirmFileBuffer` 在 chunk/MD5 流程后由 `0x2c850` 创建 `0x27374` 的 OTA worker；其实际端点调用包括 `0x27430→0x2aaf8`，随后 write/read/usleep。此处只审端点归属与控制契约，不把完整手柄升级协议视作已审安全功能。`stopSendFirmFile` 匹配时仅 `0x2afa0` 日志，随后继续 key 比较最终进入未知 key 的 Binder OK；该分支没有 free/cancel/join，也没有设置停止标志。清理函数 `0x2cd54` 确实存在，但错误/失败路径的 buffer free 与这个 stop key 不是同一件事。`isHandleBootMode` 解析 value 后只比较并日志 `boot`/`normal`，同样未执行硬件模式切换。

固定端点由 [format helper](../.tooling/bypass-check/stage3/native/hid-path-format.txt) 的 `.rodata:0x4e7f` 字面量 `/dev/hidraw%d` 构造；[getter opener](../.tooling/bypass-check/stage3/native/hid-get-open.txt) 与 [setter opener](../.tooling/bypass-check/stage3/native/hid-set-open.txt) 枚举设备、access/open、`ioctl 0x80084803`，测试设备标识包括 VID/PID `0x2717/0x50b2` 与 `0x0712/0x0019`。这些静态常量不证明连接手机上存在任何匹配 HID 设备。tx51/52 与所查两个 worker 没有调用 common writer `0x239c0`；TypeCCommon 的实现不是任意充电路径的 key→sysfs 表。

错误返回需要分别看服务状态与输出：setter 部分 open/write/parse/thread 错误返回 `AStatus_fromExceptionCode(-5)`，未知 key `0x2b248` 返回 OK。其 prologue 没保存传入 `x3=int*out`，最早的 strcmp 已可破坏 x3，完整函数中后续 x3/w3 用途均为重新赋值的参数/数值，不写调用者的 int 输出。NDK `0xcea8` 先置 int=0，`0xcf18..0xcf20` 在 Binder OK 后序列化该预置值；因此即便已执行 HID 分支，返回的 0 也不是充电生效或 worker 完成的证明。见 [NDK tx52](../.tooling/bypass-check/stage3/native/ndk-tx52.txt)。

此 key 集没有插件 owner、enabled=0 release、按请求查询或超时取消契约。两个软件状态 String 与固件 buffer 清理也没有形成电池充电通路的恢复协议。这是完整显式 key 集和所查端点的结论，不根据 `TypeC` 名称扩大到其他固件接口。

## BatteryCommon：tx55 / tx56

签名为 `getBatteryCommonInfo(const String& key, String*)` / `setBatteryCommonInfo(const String& key, const String& value, int*)`。NDK `0xcc90` / `0xcd00` 加载 vtable `+0x1e0` / `+0x1e8`，实际完整 FDE 区间是 `0x2c910..0x2c950` / `0x2c950..0x2c990`。见 [getter](../.tooling/bypass-check/stage3/native/method-55.txt)、[setter](../.tooling/bypass-check/stage3/native/method-56.txt)、[NDK tx55](../.tooling/bypass-check/stage3/native/ndk-tx55.txt)、[NDK tx56](../.tooling/bypass-check/stage3/native/ndk-tx56.txt)。

这两个函数只调用 `__android_log_print` 和 `AStatus_newOk`，唯一业务写入是把 status 指针写到保存的 hidden return slot x19（来自 x8），不使用 key/value，也不写 String*/int*。**显式 key 集是空集，没有 sysfs writer、table writer 或 owner/恢复实现。**

NDK tx55 在 `0xcc68/0xcc6c` 将输出 String 置空，经 `0xcd50` 公共 getter 调用和 `0xcd80→0xf934→AParcel_writeString` 序列化，所以成功时得到空 String。tx56 在 `0xccbc` 预置 int=0，然后经 `0xcef0` 调用和 `0xcf20` 序列化；服务未改它，所以成功时得到 0。需要区分 Binder OK、预置的业务值 0、真实硬件动作：这里只证明前两者。

Stage 1 已查 tx53/54 ChargeCommon 的相同 stub 行为仍成立，本轮只保留其完整函数与调用覆盖，不再把它们当新候选。

## 独立请求与恢复条件对比

| 检查项 | NightCharging | TypeCCommon | BatteryCommon |
| --- | --- | --- | --- |
| 实际入口 | 固定 `smart_night` 文件 | 7 getter / 8 setter 显式 key；HID/软件/固件 | log + Binder OK stub |
| HAL 独立 owner 字段或请求对象 | 无；String 下游未在本轮证明 | 无 | 无 |
| 本插件请求状态查询 | 文件 getter 不能证明 owner 状态 | 两个 String 全局状态；不查询充电请求 | 输出空 String |
| enabled=0 仅撤自己的请求 | 本轮未证明下游语义 | key 集不存在该契约 | 没有实现 |
| timeout 后取消 | setter 本层同步，无 timeout；无取消 API | worker 存在，stop key 仅 log；无取消 API | 没有动作 |
| 错误完成证据 | common writer int 与 Binder OK 分开 | 部分异常；int 预置0不证明异步完成 | Binder OK + 预置0 |
| 不影响系统供电、原策略恢复 | 未证明 | 没有该充电接口 | 没有该充电接口 |

本轮扩大了 **HAL 方法覆盖**，不扩大对 driver 表路径的审查范围。[Stage 2](DADA_NATIVE_INTERFACE_STAGE2.md) 的 11 个缺失路径闭合、11 个附加路径人工检查、2 个新增候选深查保持原边界；仍有 88/110 路径未人工补审，本轮没有批量手工审它们，也不宣称所有键或整个固件都无旁路能力。九个固定 setter 的路径/调用闭合也不证明所有下游语义安全。

目前这三组新入口没有补足插件独立 owner、有效查询、仅撤自己的请求、系统供电保留与可验证恢复的完整条件；据此不能生成新的手机试写步骤、借用厂商 owner 或把未知 key 的成功值当作可用后端。

## 可复核产物

本轮生成器仅写本轮目录：[build_evidence.py](../.tooling/bypass-check/stage3/native/build_evidence.py)。独立验证器不导入它：[verify_evidence.py](../.tooling/bypass-check/stage3/native/verify_evidence.py)。本机 LLVM 是固定在仓库工具目录中的 host 工具，未执行 service、NDK 或模块。

[instruction-windows.json](../.tooling/bypass-check/stage3/native/instruction-windows.json) 保留源文件 SHA、明确 section、VA、section/file offset、原始字节、窗口 SHA 和完整反汇编。覆盖表记录高层全部函数；额外归档 HID opener、screen worker、OTA worker 与 selected NDK output 窗口，OTA worker 仅作端点/调用覆盖，未声称固件协议已完整审查。

[verification.json](../.tooling/bypass-check/stage3/native/verification.json) 与 [manifest.json](../.tooling/bypass-check/stage3/native/manifest.json) 记录检查结果、逐指令比较数量、Stage 1/2 保留检查与本轮文件 hash。验证 PASS 是证据完整性结果，不是硬件效果测试或运行实例兼容证明。
