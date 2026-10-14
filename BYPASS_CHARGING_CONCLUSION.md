# 旁路充电调研结项报告

日期：2026-10-14。设备：Xiaomi 15 `24129PN74C` / `dada` / SM8750，HyperOS `OS3.0.307.0.WOCCNXM`，内核 `6.6.118-android15-8-gc44b714366cc-abogki519650608-4k`，Android 16 / API 36，SukiSU `ksud 4.1.3`。

---

## 1. 结论

**这台设备无法实现真正的硬件旁路充电（电池退出供电路径）。因此不开发 SukiSU 模块，调研与开发到此结束。**

三条独立理由（每条都可单独成立）：

1. **软件栈没有暴露该原语** —— xm_power/MCA 全栈（48 个模块）经三轮静态逆向确认无电池隔离能力。
2. **能做的那个东西（FCC 限流）解决不了原始目的** —— 它消除不了"充电上限"引入的微循环，而真旁路才能同时消除高压与循环。
3. **热点场景下它很可能根本不触发** —— 旁路温度表覆盖仅 -10…37°C，热点板温易越界（此项为逆向推导，未实测确认）。

**推荐替代方案**：把系统充电上限调到 60–70% + 加强散热。这是本机上减缓老化性价比最高的一步，不需要任何开发。

---

## 2. 问题与背景

**原始目标**：写一个 SukiSU Ultra 模块实现旁路充电。

**真实动机**（决策时澄清）：该手机主要用作**常插电热点**，需要减缓电池老化。

这个背景改变了评估标准：

- 常插电老化主因排序：**① 长期高 SOC 高压 > ② 温升 > ③ 微循环**
- "充电上限"能解决 ①（最大一项），但自身引入 ③
- **真旁路**本可同时解决 ① 和 ③ —— 这才是"旁路"值得单独做的原因
- 但本机做不到真旁路，而 FCC 限流消除不了 ③

---

## 3. 调研方法

| 手段 | 说明 |
|---|---|
| 静态逆向 | 51 个内核模块（48 MCA + 3 个充电泵驱动），`ET_REL` 且**未 strip**，符号表完整 |
| 工具 | `llvm-objdump -dr` / `llvm-nm`；`.rela.*` 重定位表解析字符串与函数引用 |
| 取证 | 设备上普通 shell 读不到 `/vendor_dlkm`（SELinux 拒绝），需 `su` + `exec-out` |
| 真机实验 | 一次可逆写入实验（写 `1025` / `1024` 到 `smart_chg`）+ 有界负载测试 |

**关键方法细节**：报告原方法用宿主 `echo` 写节点会因 fd 关闭而失败；本次改为**保持 fd 打开**：

```sh
su -c 'exec 3>/sys/devices/platform/soc/soc:smart_charge/smart_chg && echo 1025 >&3'
```

---

## 4. 核心发现

### 4.1 `smart_bypass` 只投票 FCC 限流，无任何硬件动作

`smart_charge_handle_bypass_chg`（内联于 `smart_charge_workfunc`，`0x2214`）进入旁路时调用 `update_smart_bypass_temp_section`，按 **20 字节步长**查温度表：

```asm
2f20  ldr w8, [x0, #0x2b4]     ; 板温
2f38  add x10, x10, #0x14      ; ← 步长 20 字节
2f48  cmp w8, w11              ; 温度上界
```

唯一动作是 `mca_vote(..., "smart_bypass", 温度档对应的 FCC)`（`.rodata+0x175f`）。**没有寄存器写、没有 GPIO、没有路径开关。**

全栈交叉验证：字符串 `smart_bypass` 只在 `mca_smart_charge.ko` 出现 1 次，**无任何消费者**执行硬件动作。

### 4.2 `smart_chg` 不是位掩码节点（修正既有认知）

`smart_chg` 是**9 属性字段表**的分发节点，与设备实际目录完全吻合：

| idx | 属性名 | idx | 属性名 |
|---|---|---|---|
| 0 | `smart_chg` | 5 | `posture` |
| 1 | `smart_fv` | 6 | `scene` |
| 2 | `smart_ichg` | 7 | `board_temp` |
| 3 | `smart_batt` | 8 | `smart_sic_mode` |
| 4 | `smart_night` | | |

store 的共同尾部是 `mca_vote(attr, "smart_chg", 1, value)` —— **直投属性值**。
show 的读回值是 **"哪些属性槽非零"的位图**（`mask |= slot[i].value ? 1<<i : 0`）。

> **因此 `SUMMARY.md` §2.2 把 `1025` 读成"bit10 = 旁路请求"是位图与选择器协议的混淆。**
> 且 `smart_bypass` **不在**这 9 个可写属性里 —— 它只是内部投票名，用户空间碰不到。

### 4.3 全栈无电池隔离原语

| 模块 | 能力 | 结论 |
|---|---|---|
| `mca_path_control.ko` | GPIO 切换 `PATH_CONTROL_{OTG,USB,WLS,WLS_REV,VDD}` | 只控**输入侧** |
| `mca_charge_interface.ko` | `charge_enable` / `input_suspend` / `ichg_limit` / `iin_limit` / `power_limit`，按 **15 条路径** | `input_suspend` 是按**输入路径**挂起，无电池维度 |
| `sc8581.ko`（充电泵） | 52 个 ops：`enable_charge` / `get_vbatt` / `ovpgate` / `wpcgate` / `set_mode` / `fsw` / `rcp` / `revchg` … | **无 batfet / isolation / battery-disconnect** |
| `POWER_SUPPLY_*` 全枚举 | 无 `CHARGE_TYPE_BYPASS`、无 `CHARGE_BEHAVIOUR` | 与开源树判断一致 |

### 4.4 唯一真实的 "bypass" 是充电泵直通

```
CP_MODE_FORWARD_1_1 / _2_1 / _4_1     ← 前向 1:1 / 2:1 / 4:1
CP_MODE_REVERSE_1_1 / _1_2 / _1_4     ← 反向（OTG）
```

`sc8581_set_operation_mode` 以 mode 为索引查表，索引恰好等于枚举值：

```
.rodata+0x1bf8:  0 1 2 2 | 0 1 2 2     ← 低 2 位 = 泵比，bit2 = 方向
```

**`CP_MODE_FORWARD_1_1` = 充电泵直通（旁路转换器）**，与上游 ABI 对 `charge_type="Bypass"` 的定义严格一致：

> *"The charger bypasses the charging path around the integrated converter…"*

**但它旁路的是"转换器"，不是"电池"。** 厂商把三种不同的东西都叫 bypass，这是混淆的根本原因：

```
                    ┌─ smart_bypass (mca_smart_charge)   → 只限流，无硬件动作
  厂商口中的         ├─ CP_MODE_FORWARD_1_1 (sc8581)       → 充电泵直通，旁路转换器
  "bypass" 共 3 处   └─ cp_get_bypass_support (sc8581)    → 芯片自称支持，但全栈无人查询（死接口）
```

### 4.5 真机实验结论

**可逆写入（保持 fd 打开）**：

| 步骤 | `smart_chg` | dmesg |
|---|---|---|
| 写入前 | `82` | — |
| 写 `1025` | → **`1106`**（`write_rc=0`） | `smartcharging_handle_controlmessage:563 input correct` |
| 写 `1024` 恢复 | → **`82`** | 同上 |

**可写、可读回、可逆** —— 但结合 §4.2，这证明的是"属性被投票为 N"，**不是**"旁路位被置位"。

**未插电时写入无任何行为变化**（投票在无输入时被忽略）：

```
写 1025 前：charge_enable=[buck eff_client:soc_limit 0]  ichg_limit=[... 100 ...]  current_now=315000
写 1025 后：charge_enable=[buck eff_client:soc_limit 0]  ichg_limit=[... 100 ...]  current_now=327000
```

**`current_now` 完全可用（推翻 SUMMARY §2.3 的"恒为 0"）**：

| 状态 | `battery/current_now` |
|---|---|
| 未插电空闲 | +105 ~ +220 mA |
| 8× CPU 满载 | **+2180 ~ +2398 mA** |

**正 = 放电，负 = 充电**，对负载高度敏感。原报告观测到恒 0，是因为当时插着 ADB（SDP）且 `smart_chg` 处于 79%/80% 保护阈值 —— 属个别工况。

### 4.6 热点场景可能触发不了旁路

旁路状态机三重门控（`smart_charge_workfunc`）：

| 门控 | 证据 |
|---|---|
| 功能总门控 `[x19+0x238]` | `0x2214` |
| **屏幕状态** ≥ 0x53c | `0x221c` `ldrh w22,[x19,#0x53c]` |
| **温度区间** | `temp over range invalid, current:%d` |

设备树温度表覆盖 **-10 … 37°C**：

```
lmt_cfg: -100..150 | 200..240 | 150..254 | 251..300 | 301..370   (×0.1°C)
         -10..15     20..24     15..25.4   25.1..30   30.1..37
```

热点长时间工作板温易超过 37°C → 落进 `temp over range invalid`，请求被拒。
**想让它帮忙的场景（息屏、发热），恰是它拒绝工作的场景。**

> 此条为逆向推导，**未插电实测确认**。

---

## 5. 对本仓库既有产物的影响

### 5.1 `modules/sukisu-bypass`（v0.2.0）—— 不需要改动

三轮逆向**持续强化**其现有标注，未推翻任何一项：

```
description=... Default off; actual hardware bypass remains unverified.
policy=fcc_limit
power_path=unverified
```

README 第 5 行"厂商'智能旁路'请求会让驱动按温度表投票限制电池充电电流"**与反汇编逐字一致**。

### 5.2 需要修正的既有认知

| 位置 | 原说法 | 修正 |
|---|---|---|
| `SUMMARY.md` §2.2 | `smart_chg` 写 `1024+bit`，`bit10` = 旁路请求 | 实为 9 属性字段表直投；读回值是位图 |
| `SUMMARY.md` §2.3 | `battery/current_now` 恒为 0 | 完全可用，正=放电/负=充电，对负载敏感 |
| `profiles/mca.sh` | `mca_read()` 用 `(RAW & 1024)/1024` 提取"请求位" | 建议直接比对 `raw_node_value`，不要解释位含义 |
| `bypass-charging-nodes.md` §4.2 | 本栈"没有 input_suspend" | `input_suspend` 存在，但是**按输入路径**的设备操作 |

---

## 6. 决策记录

| 时间点 | 决策 |
|---|---|
| 起 | 目标：写 SukiSU Ultra 模块实现旁路充电 |
| 中 | 选择方案 B（只调研，不改内核驱动）—— 内核驱动路线风险大 |
| 中 | 一度倾向方案 A（扩白名单交付限流模块） |
| **终** | **停止开发。** 系统充电上限已取得最大项收益，模块换不来额外老化收益 |

**否决方案 A 的理由**：

1. 热点场景可能不触发（温度门控）
2. 真旁路做不到；FCC 限流消除不了微循环 → 解决不了原始目的
3. 本机电池仍健康（90 循环 / 98.8% 容量），未到需要动它的程度
4. 系统充电上限是 OEM 调的：不依赖 root、不随 ROM 更新失效、不写厂商充电节点（无 PMIC 风险）

### 6.1 若将来要重启方案 A

实现路径（**代码改动极小**，成本在验证）：

1. **改 `profiles/mca.sh`**：新增 profile 标识而非放宽校验
   ```sh
   MCA_PROFILE_ID=xiaomi-dada-os3.0.307.0-fcc-v2
   MCA_HASH=1b0ab19b8c2abdd31709aac66d2cc17f98af67e6856ec2e6e345e5b0d1451095
   # 校验 incremental = OS3.0.307.0.WOCCNXM、uname -r = 6.6.118-…
   ```
   **改 `MCA_PROFILE_ID` 是必需的** —— 它写进 `journal`，`load_journal()` 会校验，否则旧会话记录会被误判有效。

2. **构建**：`python3 modules/sukisu-bypass/tests/test_controller.py && python3 modules/sukisu-bypass/build.py`

3. **验证必须插电**（无输入时投票被忽略）。对照 `bit=0` / `bit=1` 采样 `charge_enable` / `ichg_limit` / `current_now` / 电量趋势。

---

## 7. 设备实测数据（2026-10-14）

```
cycle_count        = 90            ← 使用很轻
charge_full        = 5337000  →  98.8% 设计容量（健康度极好）
charge_full_design = 5400000
capacity (RSOC)    = 74%
voltage_now        = 4164000   (4.164 V @ 74%)
temp               = 278       (27.8 °C)
board_temp         = 291
battery (thermal)  = 27800
charger_therm0     = 30263
usb/online         = 0         （测试期间未插电）
charge_enable      = buck eff_client:soc_limit 0
battery/charge_control_limit     = 0  (max 16)
battery/constant_charge_current  = 0  (max 100000)
```

**热点场景功耗量级**：空闲放电 160–500 mA；插 5W ADB 供电时 56–120 mA。
→ **充电器处于或低于系统功耗**，故"电池零净电流"在本机物理上不可达。

---

## 8. 后续建议（不需要开发）

1. **把系统充电上限调到 60–70%** —— 电压压力大幅降低，这是本机减缓老化性价比最高的一步
   | SOC | 电芯电压 | 相对压力 |
   |---|---|---|
   | 100% | ~4.45 V | 基准 |
   | 80% | ~4.20 V | 显著降低 |
   | **60–70%** | **~3.9–4.05 V** | **大幅降低** |
2. **加强散热** —— 热点持续发射的温升对老化影响不比电压小，这是第二条腿
3. 偶尔完整充放一次以校准电量计（不影响寿命）

---

## 9. 证据索引

| 内容 | 位置 |
|---|---|
| 完整三轮逆向报告（710 行，含全部复现命令） | `.tooling/mca-reverse/REVERSE-ENGINEERING-REPORT.md` |
| 51 个内核模块二进制（未 strip） | `.tooling/mca-reverse/ko/` |
| 全量 dmesg（含可逆写入实验） | `.tooling/mca-reverse/evidence/dmesg-full.txt` |
| 开源树节点取证（774 行） | `.tooling/bypass-research/bypass-charging-nodes.md` |
| SukiSU 机制取证（733 行） | `.tooling/bypass-research/sukisu-plugins.md` |
| 结论摘要（含 App 侧只读展示实现） | `.tooling/bypass-research/SUMMARY.md` |
| 设备树旁路配置 | `.tooling/bypass-check/smart-bypass-device-tree.txt` |
| 设备上已有的模块（v0.2.0，保持 disable） | `modules/sukisu-bypass/` |

> `.tooling/` 被 `.gitignore` 忽略，以上研究材料为本地留存。

**关键二进制指纹**：

| 模块 | sha256 |
|---|---|
| `mca_smart_charge.ko` | `1b0ab19b8c2abdd31709aac66d2cc17f98af67e6856ec2e6e345e5b0d1451095` |
| `sc8581.ko`（充电泵） | `5815a1185310eb53126bb42949b196db2e4cb18a3daed1b325f510d09f7e6f69` |
| `mca_path_control.ko` | `79310e52a0cf97af926948bb1da87188e5c795604c7aa4d7d59dc36621448533` |
| `mca_charge_interface.ko` | `15c25eab1199fb677940c43d0b012a626bb81a3b461b335037db1decad990448` |

---

## 10. 未确认事项（明确边界）

1. **热点高温下旁路是否真被拒绝** —— §4.6 为逆向推导，未插电实测（设备测试期间未插电）。
2. **`smart_chg` 值 `1025` 的 TLV `func_type` 表未枚举** —— 未捕获到 `smart_chg_control:` 日志。对结论无影响（该属性最终只影响 FCC 投票）。
3. **硬件硅片层面是否物理具备电池隔离 FET** —— 三轮证明的是**软件栈未暴露/未实现该原语**，不排除芯片有能力而驱动未用。
4. **`mca_business_charger_comp.ko` 的 `business_charger_event_thread` 未逐指令审计** —— 已完成函数清点与 power_supply 属性面审计。

---

## 11. 设备状态

本次调研与实验结束后：

```
smart_chg = 82                          （原值，已恢复）
模块 disable 标志 = present              （保持禁用，不会开机自启）
journal / recovery_pending = 无
未修改任何持久配置
```

设备上原有的 `miniwatts_bypass` v0.2.0 未做任何改动，保持原样。
