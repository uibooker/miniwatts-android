# 内核热区作为温度探头：可取数据源调研与落地

日期：2026-10-14。对象：本仓库 `miniwatts-android` 与实机小米 15 `24129PN74C` / `dada`，Android 16 / HyperOS `OS3.0.303.0.WOCCNXM`，内核 `6.6.118-android15-8`，SELinux `Enforcing`。

本文解释为什么 README 里"额外温度探头：不可用"这条结论**在本机是错的**，以及 1.5 版本如何处理它。

---

## 0. 结论

1. **`/sys/class/thermal` 对普通应用完全开放**：以无任何权限的应用身份（`run-as`，uid 10409）实测，`/sys/class/thermal` 可**列举**，84 个 `thermal_zone*` 中 **73 个是温度热区、其中 70 个可读**，另有 **64 个 `cooling_device*`** 与 **2 个 cpufreq 策略**可读。**不需要 root，也不需要 Shizuku。**
2. 这与 `/sys/class/power_supply/*` 形成鲜明对比：后者被小米厂商策略标为私有类型（`vendor_sysfs_usb_supply` 等），`shell` 与应用都读不到。**不能把"sysfs 读不到"当成通则**，必须逐类实测。
3. 同一个目录里**混有不是温度的节点**，且它们的数值恰好落在合理温度区间内 —— 只能按**名称**剔除，不能靠取值范围。
4. `-273000` 是内核"传感器未装配"哨兵。三个热区返回该值。
5. 热区只提供**温度**，不提供功耗；`gpu_busy_percentage` 被 SELinux 拒绝；本机**没有**环境温度传感器（传感器表里只有环境光）。所以"整机功耗/GPU 功耗/环境温度"仍然不可得。

---

## 1. 本机实测证据

### 1.1 可读性（应用身份，无权限）

```text
/sys/class/thermal/                       -> 可列举（readdir 允许）
/sys/class/thermal/thermal_zone0/temp     -> 31200     (31.2 °C)
/sys/class/thermal/thermal_zone0/type     -> aoss-0
/sys/class/thermal/cooling_device0/       -> cur_state / max_state / type 可读
/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq   -> 3532800
/sys/devices/system/cpu/cpufreq/policy6/{scaling_cur_freq,cpuinfo_max_freq} -> 可读
/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage            -> EACCES（唯一被拒的候选）
```

设备上跑真实读取器得到的快照（插桩测试日志）：

```text
zones=73 measured=70
skipped=[pm8550-bcl-lvl0, pm8550-bcl-lvl1, pm8550-bcl-lvl2,
         pmih010x-bcl-lvl0, pmih010x-bcl-lvl1, pmih010x-bcl-lvl2,
         pmih010x-ibat-lvl0, pmih010x-ibat-lvl1, socd, vbat]
denied=[wireless]
cooling=64 active=5
cpu=[policy0:2745600/3532800, policy6:3801600/4320000]
```

`73 + 10（非温度）+ 1（被拒）= 84`，与目录计数一致。

### 1.2 具名温度热区（节选，单位 0.1 °C）

| 名称 | 实测 | 分组 |
| --- | --- | --- |
| `battery` | 27600 | 电池 |
| `charger_therm0` | 30492 | 充电与接口 |
| `conn_therm` / `conn_therm2` | 27560 / 27706 | 充电与接口 |
| `display_therm` | 27625 | 显示 |
| `quiet_therm` | 30314 | 其他 |
| `pa_therm0` / `pa_therm1` | 31647 / 30758 | 无线与射频 |
| `wifi_therm` | 31084 | 无线与射频 |
| `wireless_therm` | 31831 | 无线与射频 |
| `cpu-0-*` … `cpu-1-*`、`cpuss-*` | 33000–35500 | CPU |
| `gpuss-0` … `gpuss-7` | 31000–32100 | GPU |
| `mdmss-*`、`nsphvx-*`、`nsphmx-*` | 31000–32400 | 调制解调器 |
| `camera-*`、`video`、`flash_therm` | 29000–31400 | 相机与视频 |
| `pm8550*`、`pm8010m_tz`、`pmr735d_tz`、`pmih010x*` | 27000–59410 | 电源管理 |
| `xo-therm`、`cpu_therm` | 28940 / 30972 | 其他 / CPU |

### 1.3 三个必须处理的陷阱

| 陷阱 | 实例 | 若处理不当会怎样 |
| --- | --- | --- |
| **非温度节点混在同一目录** | `vbat` = 4306（**毫伏**）、`pmih010x-ibat-lvl0` = -109（**电流**）、`socd` = 23（百分比类） | 4306 落在 "-40…150 °C" 区间内，会被显示成 **430.6 °C 的电池温度** |
| **传感器未装配哨兵** | `sdr0` / `sdr0_pa` / `mmw_ific0` = **-273000** | 显示为 -273.0 °C，或让"最热探头"统计彻底失真 |
| **逐节点可读性不同** | `wireless` 被策略拒绝，其余同类可读 | 整页降级为不可用，而实际只有 1 个节点不可用 |
| **内核文本节点可能带 NUL** | `/proc/self/attr/current` 返回**以 NUL 结尾**的 SELinux 上下文；`trim()` 不会去掉 NUL | NUL 进入界面文本与导出：`uiautomator` 直接崩在 `Illegal character (U+0)`，导出的 JSON 含裸控制字符而不再是合法 JSON |

因此实现里的规则是：**先按名称剔除**（`vbat` / `ibat` / `bcl` / `-lvl` / `socd`），再做哨兵判定（`-273000` → 不可用），再做合理性兜底（超出 -40…150 °C 一律视为非温度），**最后统一剔除控制字符**（`ThermalReader`、`PrivilegedUserService` 与 `PrivilegedParser` 三处都做，其中解析器那层是防御性的，确保第三方或旧版服务端也无法注入控制字符）。被剔除与被拒绝的节点名称都保留在快照里，供诊断页与导出说明"为什么它不在列表里"。

---

## 2. 明确不可得的部分

| 想法 | 实测结论 |
| --- | --- |
| 环境温度探头 | 本机传感器表只有环境**光**传感器，无 `TYPE_AMBIENT_TEMPERATURE` |
| GPU 占用率 | `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` → `EACCES` |
| 整机 / GPU / 屏幕功耗 | 热区只有温度；`power_supply` 被拒；系统无公开 API |
| 热区与功耗的换算 | 需要厂商热阻模型，未公开，**不做推测** |
| 剩余可用时间 | `PowerManager.getBatteryDischargePrediction()` 本机返回 `null` |
| 充满剩余时间 | `BatteryManager.computeChargeTimeRemaining()` 本机返回 `0`，HealthInfo 同为 0，按不支持处理 |

---

## 3. 落地状态（1.5 已实现）

| 能力 | 实现位置 | 免权限 |
| --- | --- | --- |
| 热区分组、单位过滤、哨兵判定 | `core/Thermal.kt`（`ThermalGroup` / `ThermalSnapshot` / `Thermal`） | — |
| 内核热区、cooling device、cpufreq 读取 | `app/ThermalReader.kt`，约 300 次小文件读取，**2 秒节流**且**不写入样本** | ✅ |
| 发热页：分组探头、热缓解、CPU 频率、示意图槽位 | `LivePanels.temperature()`；充电芯片/SoC 槽位改用对应热区最高值 | ✅ |
| 设备页补补丁级别 / 内核 / 电池技术 | `LivePanels.device()` | ✅ |
| 诊断页补热区摘要与特权 probe 原文 | `MainActivity.thermalDiagnostics()` / `privilegedDiagnostics()` | ✅ |
| 诊断导出补完整热区表、cooling 表、CPU 策略、被拒/非温度清单、probe 原文 | `MainActivity.thermalJson()` / `privilegedJson()` | ✅ |
| 「性能与热区」子页：全部热区降序列表、每策略频率条、全部 cooling 档位 | `LivePanels.thermalDetail()`（page 8，从发热页进入） | ✅ |
| CPU 频率比例历史（会话内，最多 90 点 ≈ 3 分钟） | `Engine.perfHistory()` + `RatioHistoryView`，**不落库** | ✅ |

设计取舍：

* **热区快照不进 `Sample`。** 一个机型可有 80 个热区，逐样本入库会让每行 JSON 膨胀数十倍，且热区变化远慢于采样。因此它在 `Engine` 上单独保存并按 2 秒节流刷新；诊断导出时会强制取一次最新快照。
* **快照永远不"补零"。** 缺失节点、非温度节点、被拒绝节点分别计数并在界面与导出中列名，不用 0 顶替。
* **不推断功耗。** 热区只用来显示温度与相对趋势。

---

## 4. 证据索引

| 文件 | 内容 |
| --- | --- |
| [thermal-zones-app-identity.txt](.tooling/adapter-power-research/device/thermal-zones-app-identity.txt) | 以应用身份逐条列举 84 个热区的 `type` 与 `temp`（含 `DENIED`） |
| [thermal-and-prediction-probe.txt](.tooling/adapter-power-research/device/thermal-and-prediction-probe.txt) | 探针 APK 输出：热区/冷却设备可读计数、CPU 频率、`computeChargeTimeRemaining`、`getBatteryDischargePrediction` |
| `.tooling/adapter-power-research/probe/` | 上述探针的源码与构建产物（不参与正式构建） |
| `app/src/androidTest/.../RegressionRunner.kt` | 真机插桩用例 `thermalZonesAreReadableAndConsistent`：在设备上跑真实读取器并校验契约 |
| `core/src/test/.../CoreTest.kt` | 单位换算、哨兵、名称剔除、分组、快照聚合的单测 |
