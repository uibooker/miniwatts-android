# 适配器功率 / 供电路径 / 实时功率：可取数据源调研

日期：2026-10-14。对象：本仓库 `miniwatts-android`（Kotlin、离线、无 root、无 INTERNET 权限、minSdk 26 / target 35）与实机小米 15 `24129PN74C` / `dada`，Android 16 / HyperOS `OS3.0.303.0.WOCCNXM`，内核 `6.6.77-android15-8`，SELinux `Enforcing`。

本文回答 README 中标为"缺少通用公开 API，显示不可用"的三处：**充电器页的适配器功率与身份**、**功率页的供电路径**、**实时功率**。所有结论都区分"公开 API 可读"、"隐藏但可读的 extra"、"需要特权/Shizuku"、"需要 root"，并给出本机实测证据文件。

---

## 0. 结论（TL;DR）

1. **适配器侧的"协商上限"其实已经由平台读好并广播出来了**，普通 App 用字符串 key 就能取到：`max_charging_current`（µA）与 `max_charging_voltage`（µV）。本机实测 `1500000 × 5000000` = **7.5 W**。它取自"在线充电器类 power_supply 中 `current_max × voltage_max` 最大的那一路"（AOSP `BatteryMonitor.cpp`），是**适配器支持的档位上限**，不是实时输入功率。[A2][A3]
2. **适配器的实时输入电压/电流（真正的输入侧实时功率）普通 App 拿不到。** 本机 `/sys/class/power_supply/usb/{voltage_now,current_now}` 存在（root 下读到 `4.766 V × 1.387 A ≈ 6.61 W`），但应用身份与 shell 身份都被 SELinux 拒绝（`EACCES`，连 `stat` 都不行）。[L1][L2]
3. **Xiaomi 额外给了一条零权限通道**：粘性广播 `miui.intent.action.ACTION_QUICK_CHARGE_TYPE`，extras 为 `miui.intent.extra.quick_charge_type` / `POWER_MAX` / `CAR_CHARGE`。本机实测第三方 App 可读。[L3]
4. **供电路径方向（source/sink）无 root 拿不到**：`UsbManager.getPorts()` 需要 `MANAGE_USB`，实测抛 `InvocationTargetException`。`sysfs` 的 `typec/port0/power_role`、`xm_power/typec/strategy_pd_auth/current_pr` 只有 root 能读。**但 `com.android.shell` 已被授予 `MANAGE_USB`**，所以 Shizuku（shell 身份）是唯一不 root 的现实路径。[L3][L4][L5]
5. **适配器 PDO/PPS 档位列表在本机连 root 也拿不到**：`/sys/class/usb_power_delivery/pd0` 只暴露 `revision`，没有 `source-capabilities`；`pd1` 的 `source-capabilities/1:fixed_supply`（5000 mV / 1500 mA）是**手机自己作为 source** 的能力，不是适配器的。`/sys/class/typec/port0-partner/` 只有 `accessory_mode` / `supports_usb_power_delivery` / `usb_power_delivery_revision=0.0`。[L1][K1]
6. **顺带解决"容量健康百分比"**：`charge_full / charge_full_design` 与 health HAL 的 `batteryFullChargeUah / batteryFullChargeDesignCapacityUah` 本机均可读，但**只有 root**。无 root 只能用 `charge_counter` + 电量百分比估算（AccuBattery 路线）。[L1][L6][A4]
7. **纠正一条流传中的错误结论**：`EXTRA_MAX_CHARGING_CURRENT/VOLTAGE` 是 `@hide`，但**读 extra 不需要反射**，用字符串字面量 `intent.getIntExtra("max_charging_current", -1)` 不受非 SDK 接口限制。本仓库已用 `targetSdk 35` 的探针 APK 在真机上实测通过（见 §3.1 与 §7）。[L3][A5]

---

## 1. 先把三个概念分开

| 概念 | 物理含义 | 本机可读节点/接口 | 单位 |
| --- | --- | --- | --- |
| **适配器档位上限** | 充电头+协议协商出的最大输出能力 | health HAL `maxChargingCurrentMicroamps` / `maxChargingVoltageMicrovolts` → 广播 extra | µA / µV |
| **适配器实时输入** | 此刻 VBUS 上真实的电压电流（扣掉线缆压降） | `/sys/class/power_supply/usb/voltage_now` / `current_now` | µV / µA |
| **供电路径** | 谁给谁供电：端口角色（sink/source）、数据角色、PD 状态机、鉴权 | `/sys/class/typec/port0/power_role`、`xm_power/typec/strategy_pd_auth/*`、`UsbManager.getPorts()` | 枚举 |
| **电池侧实时** | 经充电 IC/电荷泵之后真正进电芯的功率 | `BatteryManager` 全部属性、`battery/current_now` | µA / µV |

`W = µA × µV / 10¹²`。三个"功率"互不相等：本机同一时刻适配器上限 7.5 W、适配器实测输入 6.61 W、电池侧净功率约 3.5 W，差额是系统负载与转换损耗。

---

## 1.5 落地状态（1.4 已实现）

本节记录本文结论在 `miniwatts-android` 1.4 中的落地情况，便于复核哪些是研究结论、哪些已经进了产品。

| 能力 | 实现位置 | 免 root？ | 本机验证 |
| --- | --- | --- | --- |
| 适配器协商上限（`max_charging_current × max_charging_voltage`） | `SensorReader` 读广播字符串 key；`core.Adapter.ceilingW` | ✅ 零权限 | ✅ 真机读回 1500000 µA × 5000000 µV = **7.5 W** |
| `invalid_charger` 合规标记 | `SensorReader` → 适配器页"合规标记" | ✅ 零权限 | ✅ 读回 0（未标记） |
| 小米快充类型与上报档位 | `SensorReader` 读 `miui.intent.action.ACTION_QUICK_CHARGE_TYPE`（仅插电时读，避免拔线残留旧档位） | ✅ 零权限 | ✅ 读回 `quick_charge_type=1`、`POWER_MAX=0`，与 root 下 `xm_power` 节点一致 |
| 输入侧实时电压/电流 | 可选 Shizuku 通道（`PrivilegedUserService` 只读白名单 → `PrivilegedParser`） | ⚠️ 需 Shizuku，且**只有 root 身份**读得到 | ⏳ 解析逻辑用真机节点值做了 fixture 回归；端到端待设备解锁后验证 |
| 真实充电器类型 / PD 状态机 / PD 认证 | 同上 | ⚠️ 同上 | ⏳ 同上 |
| 容量健康度（满充 / 设计容量）与厂商 SOH | 同上；`Adapter.capacityHealthPct` | ⚠️ 同上 | ✅ 真机读回 5337 / 5400 mAh → 98.8%，`soh_new=100` |
| 负载与损耗 / 电芯占比 | `Sample.loadW` / `Sample.cellSharePct`，仅在真的读到输入侧、有插电且无符号冲突时给出 | ⚠️ 随输入侧 | ✅ 无输入侧时保持不可用（真机读回 null） |
| **会话级输入能量与电芯获得比例** | `Session.inputWh` / `inputCoveredMs` / `cellShareOfInputPct`；`Integration.betweenInput` 与电芯侧分开积分 | ⚠️ 随输入侧 | ✅ v2→v3 迁移；真机当前会话「输入实测覆盖 30s」、旧会话保持不可用 |
| PDO 逐档列表 / 线缆阻抗 / 通路损耗 | 明确不做 | ❌ 无来源 | — |

**端到端实测（1.4/1.5，Shizuku 以 root 身份运行）**：Shizuku 用户服务进程为 `root … dev.miniwatts.macdebug:privileged`，读回 `usb.voltage_now=5096000 µV`、`usb.current_now=1028000 µA`（≈5.24 W）、`charger.real_type=PD`、`typec.power_role=sink`、`pd.current_state=SNK_Ready`、`battery.charge_full/design=5337000/5400000`，来源标注合并为 `… + Shizuku user service (uid=0)`。

**实测中发现并修掉的一个健壮性缺陷**：`/proc/self/attr/current` 返回**以 NUL 结尾**的 SELinux 上下文，Kotlin 的 `trim()` 不会去掉 NUL，于是这个字节随 `probe.raw` 进入诊断界面与导出。后果是 `uiautomator dump` 直接崩在 `Illegal character (U+0)`，且导出的诊断 JSON 会含裸控制字符而不再是合法 JSON。现在 `PrivilegedUserService`、`PrivilegedParser`（防御性）与 `ThermalReader` 三处都统一剔除控制字符（保留换行以维持键值结构），并有单测覆盖。

两条设计约束已写进代码与 README：**适配器上限一律标注"协商上限"而不是实测输入**；**缺失、`!EACCES`、`!ENOENT` 全部按不可用处理，绝不当作 0**。Shizuku 通道默认关闭，只在"已开启且正在采样"时绑定；ADB 身份下会被厂商策略拒绝并以不可用呈现（策略见 §2.2）。

样本持久化不需要数据库迁移：`samples.data` 存的是 JSON，新增 `adapter` 对象与 `loadW`/`cellSharePct` 对旧库向后兼容；CSV 在末尾追加适配器列，导出 `schema` 升为 2。

---

## 2. 本机实测结果

设备在线（`（已省略）`），本轮全部操作**只读**；root 仅用于确认"数据是否存在及其单位"，**不是本项目的实现路径**。

### 2.1 普通 App（uid 10409，无 root、无任何权限、targetSdk 35）实测可读

证据：[app-feasibility.txt](.tooling/adapter-power-research/device/app-feasibility.txt)、[app-feasibility-logcat.txt](.tooling/adapter-power-research/device/app-feasibility-logcat.txt)

`Intent.ACTION_BATTERY_CHANGED` 粘性广播 extras（原样）：

```text
max_charging_current = 1500000        # µA  ← 适配器支持的最大充电电流
max_charging_voltage = 5000000        # µV  ← 适配器支持的最大充电电压
charge_counter       = 3524000        # µAh
android.os.extra.CYCLE_COUNT     = 90
android.os.extra.CHARGING_STATUS = 0  # 本机 HAL 报 INVALID，无意义
android.os.extra.CAPACITY_LEVEL  = 3
invalid_charger = 0                   # 充电器合规标记
level/scale/plugged/status/health/temperature/voltage/technology
```

`miui.intent.action.ACTION_QUICK_CHARGE_TYPE`（粘性）extras：

```text
miui.intent.extra.quick_charge_type = 1
miui.intent.extra.POWER_MAX         = 0
miui.intent.extra.CAR_CHARGE        = 0
```

`android.hardware.usb.action.USB_STATE`（粘性）extras 只有 `connected/configured/host_connected/unlocked`，**不含供电角色**。

`BatteryManager.getIntProperty` 实测：`CURRENT_NOW=-887000`、`CHARGE_COUNTER=3524000`、`CAPACITY=69`、`STATUS=2`；`CURRENT_AVERAGE` 返回 `Integer.MIN_VALUE`、`ENERGY_COUNTER` 返回 `Long.MIN_VALUE`（本机不支持，项目现有的"哨兵值→null"处理是对的）。

### 2.2 普通 App 实测**拿不到**

同一探针进程内逐条 `open()` 的结果，全部 `EACCES`：

```text
/sys/class/power_supply/usb/voltage_now                  -> DENIED
/sys/class/power_supply/usb/current_now                  -> DENIED
/sys/class/power_supply/usb/type                         -> DENIED
/sys/class/power_supply/battery/charge_full              -> DENIED
/sys/class/power_supply/battery/charge_full_design       -> DENIED
/sys/class/xm_power/charger/charger_common/real_type     -> DENIED
/sys/class/typec/port0/power_role                        -> DENIED
/sys/class/usb_power_delivery/pd0/revision               -> DENIED
```

`UsbManager.getPorts()`（`@hide @SystemApi @RequiresPermission(MANAGE_USB)`）→ `java.lang.reflect.InvocationTargetException`。

**为什么被拒：不是文件权限，而是厂商 SELinux 标签。** 本机 `vendor_sepolicy.cil` 给这些节点打了厂商私有类型，**不是** AOSP 对 `domain` 开放的 `sysfs_usb`，也不是 `sysfs_batteryinfo`：

```text
genfscon sysfs ".../soc:mca_business_charger/power_supply/usb"          vendor_sysfs_usb_supply      (vendor_sepolicy.cil:187)
genfscon sysfs ".../soc:mca_business_battery/power_supply/battery"      vendor_sysfs_battery_supply  (vendor_sepolicy.cil:163)
genfscon sysfs ".../soc:mca_strategy_*" 等 xm_power 节点                vendor_sysfs_xm_power        (vendor_sepolicy.cil 多处)
```

全部策略文件里 `untrusted_app` / `appdomain` / `platform_app` / `priv_app` / `shell` 对这些 `vendor_sysfs_*` 类型**零 allow**；非 root 身份里只有 `system_app`（平台签名 + system UID）能读 `usb_supply` / `battery_supply` / `xm_power` / `qcom_battery`。AOSP 侧同样是编译期封死的：`neverallow` 把 `sysfs_batteryinfo` 限定给 `charger`/`hal_health_server`/`incidentd`/`shell`，未打标路径走默认 `sysfs` 标签，而 `neverallow untrusted_app_all sysfs (file (… read open …))`。这解释了几件事：

* `xm_power/charger/charger_common/{real_type,power_max,quick_charge_type}` 的 **DAC 模式是 0444**（驱动侧字段表，root 可读），但 App 仍拿到 `EACCES` —— 拦住它的是 SELinux，不是 `chmod`；`charge_interface/*` 更是 0640。
* `/sys/class/typec` 被单独标成 `vendor_sysfs_usb_c`，只放行 `vendor_hal_usb_qti` 与 `ueventd`，**连 `system_app` 都读不到** → PDO/PPS 对任何第三方 App 彻底不可达。
* **Shizuku（shell 身份）也读不到**，这与本机实测、PowerMeter 在 HyperOS / Android 16 上的结论完全一致 [L2]。

另外，**vendor AIDL HAL 也不可达**：`vendor.xiaomi.hardware.micharge.IMiCharge/default` 的 SELinux 标签是 `hal_micharge_service`，其客户端类型 `hal_micharge_client` 定义为 `{bluetooth, nfc, platform_app, system_app, system_server, hal_audio_default, hal_micharge_default}`（`vendor_sepolicy.cil:1490-1495`），**`untrusted_app` 与 `shell` 都不在名单里**。即"直接用 MiCharge HAL 拿快充功率"这条路对第三方 App 与 Shizuku 都是关闭的。

### 2.3 root 下才可见的数据（仅用于确认存在性与单位）

证据：[usb-uevent.txt](.tooling/adapter-power-research/device/usb-uevent.txt)、[battery-uevent.txt](.tooling/adapter-power-research/device/battery-uevent.txt)、[xm-power-charger.txt](.tooling/adapter-power-research/xm-power-charger.txt)、[xm-power-typec-cp-fg.txt](.tooling/adapter-power-research/xm-power-typec-cp-fg.txt)

**适配器实时输入**（`power_supply/usb/uevent`）：

```text
POWER_SUPPLY_TYPE=USB_PD           # 接口类型：走 PD
POWER_SUPPLY_ONLINE=1
POWER_SUPPLY_VOLTAGE_NOW=4766000   # 4.766 V  ← 输入侧实时电压
POWER_SUPPLY_CURRENT_NOW=1387000   # 1.387 A  ← 输入侧实时电流  ⇒ 6.61 W
POWER_SUPPLY_VOLTAGE_MAX=5000000   # 与广播的 max_charging_voltage 完全一致
POWER_SUPPLY_CURRENT_MAX=1500000   # 与广播的 max_charging_current 完全一致
```

**供电路径 / PD 状态机**：

```text
/sys/class/typec/port0/power_role              = source [sink]   # 当前 sink
/sys/class/typec/port0/data_role               = host [device]
/sys/class/typec/port0/power_operation_mode    = usb_power_delivery
/sys/class/typec/port0/usb_power_delivery_revision = 3.0
/sys/class/typec/port0-partner/supports_usb_power_delivery = yes
/sys/class/xm_power/typec/strategy_pd_auth/current_pr     = sink
/sys/class/xm_power/typec/strategy_pd_auth/current_state  = SNK_Ready
/sys/class/xm_power/typec/strategy_pd_auth/is_pd_adapter  = false
/sys/class/xm_power/typec/strategy_pd_auth/verified       = 0
/sys/class/xm_power/typec/strategy_pd_auth/adapter_id     = 00000000
/sys/class/xm_power/typec/strategy_pd_auth/adapter_svid   = 0000
/sys/class/xm_power/typec/apdo_max                        = 0   # PPS 档位
```

**充电器身份与快充档位**（与 §2.1 广播交叉一致）：

```text
/sys/class/xm_power/charger/charger_common/real_type         = PD
/sys/class/xm_power/charger/charger_common/quick_charge_type = 1    # 广播同为 1
/sys/class/xm_power/charger/charger_common/power_max         = 0    # 广播 POWER_MAX 同为 0
/sys/class/xm_power/charger/charge_interface/iin_limit       = buck eff_client:volt_limit 1400
/sys/class/xm_power/charger/charge_interface/ichg_limit      = buck eff_client:volt_thermal_limit 1500
```

**容量健康度**（`battery/uevent`）：

```text
POWER_SUPPLY_CHARGE_FULL         = 5337000   # 5337 mAh
POWER_SUPPLY_CHARGE_FULL_DESIGN  = 5400000   # 5400 mAh  ⇒ 98.8%
POWER_SUPPLY_MODEL_NAME          = O3_5400mah_90w
POWER_SUPPLY_CYCLE_COUNT         = 90
POWER_SUPPLY_CHARGE_TYPE         = Fast
```

**health HAL 的完整 `HealthInfo`**（root `dumpsys android.hardware.health.IHealth/default`）：

```text
maxChargingCurrentMicroamps: 1500000, maxChargingVoltageMicrovolts: 5000000
batteryFullChargeUah: 5337000, batteryFullChargeDesignCapacityUah: 5400000
batteryCurrentMicroamps: -839000, batteryCycleCount: 90
chargingState: INVALID, chargingPolicy: INVALID
```

### 2.4 本机 PD 能力确实不可得

```text
/sys/class/usb_power_delivery/pd0/  -> device, power, revision, subsystem, uevent   （无 capabilities）
/sys/class/usb_power_delivery/pd1/  -> source-capabilities/1:fixed_supply { voltage=5000mV, maximum_current=1500mA }
```

`pd1` 是 `/sys/devices/virtual/...`，代表手机自身作为供电方的能力；`pd0` 是 UCSI 真实端口，只报了 `revision=3.0`。**适配器的 PDO 列表既没有 Android API，本机内核也没有把它暴露到 sysfs**，所以"广播供电规格 / 协商档位 V×A 逐档列出"这一项应继续保持不可用。

补充两点内核事实（对避免踩坑很重要）：[K1][K2]

* `usb_power_delivery` class 是 **Linux 6.0** 才引入的，只承载 **PDO 声明**（协商能力），**没有**"当前生效 PDO / 实测 V·I"文件；`source-capabilities/*/maximum_current` 与 `sink-capabilities/*/operational_current` 是**角色差异**，不是"标称 vs 实测"。真正的输入侧实测值仍只在 `power_supply/usb/{voltage_now,current_now}`。
* 博客里常见的 `input_voltage_now`、`input_current_now`、`usb_type`、`pd_active`、`pd_voltage`、`pd_current`、`typec_power_role`、以及 `power_supply/usb/real_type` **都不是 mainline 属性**，是 Qualcomm/厂商私有扩展。本机 `/sys/class/power_supply/usb/` 实际只有 `current_max current_now input_current_limit online present type voltage_max voltage_now`（`real_type` 只存在于 `xm_power/charger/charger_common/`）。照抄这些名字会读到 `ENOENT`。

---

## 3. 各数据项的获取途径

### 3.1 适配器档位上限（推荐落地）

```kotlin
// 无需权限、无需反射：直接用字符串 key 读粘性广播
val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
val maxUa = i?.getIntExtra("max_charging_current", -1) ?: -1   // µA
val maxUv = i?.getIntExtra("max_charging_voltage", -1) ?: -1   // µV
val ceilingW = if (maxUa > 0 && maxUv > 0) maxUa.toDouble() * maxUv / 1e12 else null
```

* AOSP 定义：`EXTRA_MAX_CHARGING_CURRENT = "max_charging_current"`（注释即"maximum charging current supported by **the charger**"，µA）、`EXTRA_MAX_CHARGING_VOLTAGE = "max_charging_voltage"`（µV）。[A1]
* 数值来源：`BatteryMonitor` 遍历在线充电器类 power_supply，取 `current_max × voltage_max` 最大的一路写入 `HealthInfo`，`BatteryService` 再放进 `ACTION_BATTERY_CHANGED`。[A2][A3]
* **语义限制**：它是"档位上限/合同上限"，**不是实时功率**。UI 必须写"协商上限"，不能写"来自充电器"的实测值。
* 缺失兼容：其它机型可能不写这个 extra（返回 -1）或写 0，必须降级为不可用。

### 3.2 Xiaomi 快充类型与档位瓦数（可选增强）

```kotlin
val q = context.registerReceiver(null, IntentFilter("miui.intent.action.ACTION_QUICK_CHARGE_TYPE"))
val type = q?.getIntExtra("miui.intent.extra.quick_charge_type", -1)
val powerW = q?.getIntExtra("miui.intent.extra.POWER_MAX", -1)
val carCharge = q?.getIntExtra("miui.intent.extra.CAR_CHARGE", -1)
```

* 本机实测该 action 是**粘性广播**且第三方 App 可读（同一探针进程拿到 extras），`miui-services.jar` 里存在 `quick_charge_type` 与 `ACTION_QUICK_CHARGE_TYPE`，`miui-framework.jar` / `miuix.jar` 亦有该 action。[L3]
* `POWER_MAX` 的语义：小米框架把它作为**展示用档位瓦数**交给 SystemUI（`BatteryStatus.maxChargingWattage`，档位表 `5,10,15,18,22,27,33,50,67,90,120` W）。该结论来自第三方再实现 MiChargeShim 的文档，**不是小米官方源码**，属"待用原装 90 W 充电器实测确认"。[M1]
* 本机交叉验证通过：广播 `quick_charge_type=1` / `POWER_MAX=0` 与 root 下 `xm_power/charger/charger_common/quick_charge_type=1` / `power_max=0` 完全一致。
* `quick_charge_type` 的枚举含义本机**未验证**：当前适配器只是 5 V/1.5 A 的 PD 源，却报 `1`；MiChargeShim 记录 1=fast、2/3=turbo、4=super，只能作为待验证假设。[M1]
* 兼容性：非小米机型该 action 不存在，`registerReceiver` 返回 null，必须隐藏整块 UI；若要注册常驻接收器，targetSdk 34+ 需显式传 `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` 并实测。

### 3.3 供电路径方向（无 root 只能靠 Shizuku）

| 途径 | 数据 | 结论 |
| --- | --- | --- |
| `UsbManager.getPorts()` / `UsbPortStatus` | `power_role`(source/sink)、`data_role`、`current_mode`(ufp/dfp)、`role_combinations` | 需 `MANAGE_USB`，普通 App 实测抛异常 |
| Shizuku（shell 身份） | 同上（仍无数值 V/I，`UsbPortStatus` 没有电压/电流字段） | **技术上可行**：`com.android.shell` 在本机 `android.permission.MANAGE_USB: granted=true`；`dumpsys usb` 已显示 `power_role=sink, data_role=device, current_mode=ufp`。但收益仅一个角色枚举 |
| root 读 sysfs | `typec/port0/power_role`、`xm_power/typec/strategy_pd_auth/{current_pr,current_state}` | 可行，仅 root |
| 公开广播 | `USB_STATE` 只有 connected/configured | 无角色信息 |

注意：**Shizuku 只能解决角色/路径，解决不了 sysfs 功率**——本机 shell 身份读 `power_supply/*` 同样 `EACCES`（与本机内核 SELinux 标签一致，也与 PowerMeter 在 HyperOS Android 16 上的实测结论一致）。[L2]

### 3.4 实时功率

| 数据 | 无 root | Shizuku | root |
| --- | --- | --- | --- |
| 电池侧净功率（`CURRENT_NOW × VOLTAGE`） | ✅ | ✅ | ✅ |
| 适配器档位上限功率（§3.1） | ✅ | ✅ | ✅ |
| 适配器实时输入功率（`usb/voltage_now × current_now`） | ❌ | ❌（sysfs 被拦） | ✅ |

结论：**"供电路径"卡片的"负载与损耗 / 电芯占比"在无 root 下应继续标不可用**。强行用"档位上限 − 电池功率"当负载是错的：上限是合同值，不是此刻实际输入。

### 3.5 容量健康度（顺带，README 亦标"不可用"）

* root：`battery/charge_full ÷ charge_full_design`（本机 5337/5400 = 98.8%）；health HAL `batteryFullChargeUah ÷ batteryFullChargeDesignCapacityUah`（同值）；小米另有 `xm_power/fuelgauge/strategy_fg/soh_new = 100`。[L1][L6]
* 无 root：两者都不在广播里，只能用 `charge_counter`（µAh，已实测可读，含隐藏 extra `charge_counter`）配合电量百分比**估算满充容量**——这正是 AccuBattery 的做法（官方明确说明它测的是电池侧、并通过充放电积分估算容量）。[A4]

---

## 4. 类似项目的做法

| 项目 | 取数通道 | 是否适配器侧 | 对本项目的意义 |
| --- | --- | --- | --- |
| Ampere / Battery Guru / Charge Meter / charging power / Battery One | 只读 `BatteryManager`（部分历史版本读 sysfs） | ❌ 官方 FAQ 明确"只显示充进电池的那部分" | 说明"充电功率"类 App 普遍是电池侧口径，UI 必须写清楚 |
| AccuBattery | `BatteryManager` + 会话积分 | ❌ 官方对比文章解释"测 USB 线会永远偏高" | 容量健康度只能估算；可引用其免责公式 |
| PowerMeter（xinxiangjueming） | 电池节点 + Shizuku 兜底 | 尝试过 | **实测证伪**：Xiaomi 24031PN0DC / Android 16 / HyperOS 上 shell（uid 2000）被 SELinux 拦死全部 `power_supply` / `qcom-battery` 节点 |
| RedmiBatteryDiag（byteknoneco） | sysfs + Shizuku 兜底，README 提示风险 | 尝试过 | 可作为"Shizuku 可选增强"的实现参考，但必须容忍整条通道不可用 |
| beam（montafra） | 只靠 `BatteryManager` 做常驻通知 | ❌ | 与"零权限、低功耗"路线一致 |
| acc（Magisk 模块） | root 写/读 sysfs，`consumed_watts = 输入功率 − 电池功率` | ✅（root） | 本项目"供电路径"的物理算法可参考，但前提是 root |
| MiChargeShim | 替换 `vendor.xiaomi.hardware.micharge` HAL + 内核模块 | ✅（系统/root） | 给出 `POWER_MAX`、`quick_charge_type`、`real_type` 的语义与档位表，是本机 MiCharge 取证的一手材料 [M1] |
| MiniWatts（iOS 原版，ResistanceTo） | 四路：IOKit `IOPMPowerSource`、powerd 的电池与充电器描述（含 PD 档位表）、以及 `IOHIDEventSystemClient` 读的 **PMU / 充电 IC 传感器**——真正的端口输入电压电流（`VQ0u × IQ0u`）来自最后这一路 | ✅ | **平台差异正是本项目「可选 Shizuku 增强」的由来**：iOS 把 USB-C 电源轨开放给沙盒应用（经 PMU 传感器），Android 只把它开放给特权身份。其"适配器 → 系统 → 电芯"三端口划分、以及"标称/协商上限 ≠ 实测输入"的区分值得借鉴，取数方式不能照搬 |

横向结论：**没有任何一个非特权 Android App 声称能读适配器侧实测功率**（对照之下，iOS 的 MiniWatts 能直接读到端口轨，因为 iOS 把 PMU 传感器开放给了沙盒应用——这不是 Android 上可复制的路径）；能读到"适配器侧"的只有平台自己广播出来的档位上限，以及小米的档位瓦数广播。同时，**目前不存在任何免 root 读取 `xm_power` 的开源项目**：`acc`、`charging-bypass-magisk`、`meowpower`、`mi_thermald-mod` 全部依赖 root（Magisk/LSPosed），PowerMeter 与 RedmiBatteryDiag 依赖 Shizuku，MiChargeShim 需要重刷 vendor 分区。[X1]

---

## 5. 落地建议（按档位）

**A. 立即可做（零权限、零风险、离线可用）**

1. 充电器页把"适配器输入 — V × — A"改为**"适配器协商上限"**：`max_charging_voltage × max_charging_current`，明确标注"上限 / 非实时"，缺失时降级不可用。
2. 充电器页新增 **Xiaomi 快充信息**（quick_charge_type 文本化 + POWER_MAX 瓦数），仅在广播存在时显示；`POWER_MAX` 语义在未实测前只显示数值与"小米上报档位"，不要断言就是充电头额定瓦数。
3. 增加 `invalid_charger` 合规提示；`CHARGING_STATUS` 本机实测 `INVALID`，**不要展示**。
4. 功率页"供电路径"卡：保留"来自充电器"为协商上限，把"负载与损耗 / 电芯占比"继续标不可用并写明原因（缺少输入侧实测读数）。

**B. 可选增强（Shizuku）——技术上可行，但收益很低，建议先不做**

5. 唯一能拿到"供电角色"的不 root 方式是 Shizuku：`com.android.shell` 在本机 `MANAGE_USB: granted=true`，因此 shell 身份调用 `UsbManager.getPorts()` 可以成功。**但**它只能给出 `power_role` / `data_role` / `current_mode` 这类枚举，`UsbPortStatus` 里**没有任何电压/电流字段**，且引入 Shizuku 会给一个"离线、零依赖"的 App 增加常驻组件与权限引导。本机 shell 读 sysfs 同样被 SELinux 拦死，**Shizuku 拿不到任何功率数值**。结论：除非将来确实要展示"当前是 sink 还是 source"，否则不值得。
6. **不要**把 Shizuku 当作读 sysfs 的手段：本机厂商策略里 `shell` 对所有 `vendor_sysfs_*` 类型零 allow，PowerMeter 在 HyperOS / Android 16 上的实测同样是失败。

**C. 明确不做**

7. 不引入 root 依赖（与项目"无 root、不调用 su"的声明一致）。
8. 不承诺 PD PDO/PPS 逐档列出、线缆识别、通路电阻：本机内核未暴露，Android 也没有 API。

**D. 若未来要真正的输入侧实时功率**

9. 唯一路径是特权组件（root、系统签名、或厂商 HAL 放行）。可在 UI 上把该行标为"需要 root"，而不是留一个永远的"— W"。

---

## 6. 需要纠正的两条既有说法

1. **"`EXTRA_MAX_CHARGING_CURRENT/VOLTAGE` 被 hidden-API 拦截，target 35 不可用"——错。** 这两个常量确实是 `@hide` + `@UnsupportedAppUsage(maxTargetSdk = R)`，但该限制约束的是**反射访问隐藏字段/方法**；用字符串字面量读 Intent extra 走的是公开 API（`Intent.getIntExtra(String, int)`），不在限制范围内（官方限制文档只覆盖非 SDK 接口的反射与链接）。[A5] 本仓库已用 `targetSdk 35` 的探针 APK 在真机读到 `1500000 / 5000000`，证据见 §2.1。
2. **"Xiaomi 粘性广播能否被第三方接收待核实"——已核实可以。** `miui.intent.action.ACTION_QUICK_CHARGE_TYPE` 在本机是粘性广播，普通 App 进程实测拿到全部三个 extras。

---

## 7. 证据索引

### 引注图例

| 标号 | 含义 |
| --- | --- |
| [L1] | 本机 root 只读采样：`usb-uevent.txt`、`battery-uevent.txt`、`xm-power-charger.txt`、`xm-power-typec-cp-fg.txt`、`typec-pd-nodes.txt`、`health-hal-dump.txt` |
| [L2] | 本机 App / shell 身份读 sysfs 被拒的实测：`app-feasibility.txt` |
| [L3] | 本机普通 App 探针读粘性广播 extras：`app-feasibility.txt`、`app-feasibility-logcat.txt` |
| [L4] | `UsbManager.getPorts()` 抛异常：`app-feasibility.txt` |
| [L5] | `com.android.shell` 已授予 `MANAGE_USB`：`shell-permissions.txt` |
| [L6] | health HAL `HealthInfo` 全量：`health-hal-dump.txt` |

### 本仓库文件

| 文件 | 内容 |
| --- | --- |
| [app-feasibility.txt](.tooling/adapter-power-research/device/app-feasibility.txt) | 普通 App（uid 10409）逐项可取/不可取、广播 extras 原文 |
| [app-feasibility-logcat.txt](.tooling/adapter-power-research/device/app-feasibility-logcat.txt) | 上述探针的 logcat 原始输出 |
| [usb-uevent.txt](.tooling/adapter-power-research/device/usb-uevent.txt) | 适配器实时输入与档位上限（root） |
| [battery-uevent.txt](.tooling/adapter-power-research/device/battery-uevent.txt) | 电芯侧、charge_full / design、循环次数（root） |
| [typec-pd-nodes.txt](.tooling/adapter-power-research/device/typec-pd-nodes.txt) | typec 角色与 PD revision、PDO 暴露情况（root） |
| [health-hal-dump.txt](.tooling/adapter-power-research/device/health-hal-dump.txt) | health HAL `HealthInfo` 全量字段（root） |
| [shell-permissions.txt](.tooling/adapter-power-research/device/shell-permissions.txt) | `com.android.shell` 的 `MANAGE_USB` 授权 |
| [xm-power-charger.txt](.tooling/adapter-power-research/xm-power-charger.txt) | `xm_power/charger/**` 全量节点与取值（root） |
| [xm-power-typec-cp-fg.txt](.tooling/adapter-power-research/xm-power-typec-cp-fg.txt) | PD 状态机、鉴权、电荷泵、电量计（root） |
| [dumpsys-usb.txt](.tooling/adapter-power-research/device/dumpsys-usb.txt) | framework 侧端口角色（power_role=sink 等） |
| [dumpsys-battery.txt](.tooling/adapter-power-research/device/dumpsys-battery.txt) | Max charging current/voltage 的 framework 视角 |
| [probe/](.tooling/adapter-power-research/probe/) | 本次取证用的一次性探针 APK 源码与构建产物（不参与本项目构建） |
| [probe-gradle/](.tooling/adapter-power-research/probe-gradle/) | 另一个探针模块（Gradle 版，测 38 条 sysfs 路径 + binder 可达性），已从仓库根移入 `.tooling/` |
| [open-source-apps.md](.tooling/adapter-power-research/open-source-apps.md) | 开源 App 取数通道的详细对照与源码行号 |
| [aosp-sysfs-sepolicy.md](.tooling/adapter-power-research/aosp-sysfs-sepolicy.md) | AOSP 策略 `neverallow`、`sysfs_batteryinfo` 边界、HAL `protected_service`、内核 ABI 澄清 |
| [xiaomi-hyperos.md](.tooling/adapter-power-research/xiaomi-hyperos.md) | `xm_power` 全量枚举、属性 mode、SELinux 标签、`IMiCharge` 客户端白名单、无 root 开源项目盘点 |
| [vendor_sepolicy.cil](.tooling/adapter-power-research/device/vendor_sepolicy.cil) | 厂商 SELinux 标签与 `hal_micharge_client` 定义原文 |
| [docs/DADA_CHARGER_PROVIDER_RESEARCH.md](docs/DADA_CHARGER_PROVIDER_RESEARCH.md) 等 | 既有 MiCharge / MCA / 禁充链路取证 |
| [MiChargeShim 文档](.tooling/bypass-check/stage1/native/MiChargeShim-3ed8e92b8c8487adff48dc25927cb48b3de0ed29/docs/micharge-analysis.md) | `POWER_MAX` 档位表、`quick_charge_type` 映射、HAL 读取节点清单 |

### 外部一手来源

- [A1] [AOSP `BatteryManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/BatteryManager.java) — `EXTRA_MAX_CHARGING_CURRENT` / `EXTRA_MAX_CHARGING_VOLTAGE` 定义与 `@hide` 标注
- [A2] [AOSP `BatteryService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/BatteryService.java) — 把 `mHealthInfo.maxChargingCurrentMicroamps/Voltage` 放进 `ACTION_BATTERY_CHANGED`
- [A3] [AOSP `BatteryMonitor.cpp`](https://android.googlesource.com/platform/system/core/+/refs/heads/main/healthd/BatteryMonitor.cpp) — 取 `current_max × voltage_max` 最大的在线充电器
- [A4] [AOSP `HealthInfo.aidl`](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/health/aidl/android/hardware/health/HealthInfo.aidl) — `batteryFullChargeUah` / `batteryFullChargeDesignCapacityUah` / `chargingState` / `chargingPolicy`
- [A5] [非 SDK 接口限制](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces) — 限制仅覆盖非 SDK 成员的访问，不覆盖公开 API 的字符串 extra
- [K1] Linux 内核 ABI 文档 `Documentation/ABI/testing/sysfs-class-usb_power_delivery` 与 `sysfs-class-typec`（本地缓存 [cache/linux/](.tooling/adapter-power-research/cache/linux/)）— `source-capabilities` / `sink-capabilities` 与 PDO 语义
- [K2] AOSP SELinux 策略与内核 ABI 的交叉核对详见 [aosp-sysfs-sepolicy.md](.tooling/adapter-power-research/aosp-sysfs-sepolicy.md)（含 `neverallow` 原文、`sysfs_batteryinfo` 放行域、`usb_power_delivery` 引入版本实测）
- [M1] MiChargeShim `docs/micharge-analysis.md`、`docs/micharge-uevent-helper.md` — 第三方再实现，非小米官方；仅作语义参考
- [X1] [xiaomi-hyperos.md](.tooling/adapter-power-research/xiaomi-hyperos.md) — 本仓库对 dada vendor 镜像的静态取证（`vendor_sepolicy.cil`、784 个 vendor_dlkm 模块字段表、HAL 静态 key 表）
