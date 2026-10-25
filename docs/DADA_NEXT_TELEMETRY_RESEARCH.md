# dada 后续遥测接口离线审计

日期：2026-10-13。范围：已有 Linux KO、BTF 与 ADSP 文件镜像的静态分析。本轮执行的手机命令为 0；没有运行目标固件、采样、控制写入、插件更新或 A/B 测试。本报告不批准新增手机读取白名单。

## 1. 可直接用于下一轮决策的结果

| Linux getter | 请求 property / ADSP handler | 已证明的动作种类 | 当前不能证明 | 白名单状态 |
| --- | --- | --- | --- | --- |
| `qcom_subpmic_get_vsys_volt`，`.text+0x10c8` | `0x200c`；buck 表 `0x6016d378` 的 case `0x6002f4c4` | 调用 `battman_adc_read(0, out)`；通道 0 的文件表名称是 `VPH_PWR` | 本机运行时 ADC provider、物理测点与 SYS 拓扑、转换副作用、单位 | 不新增 |
| `qcom_subpmic_get_bus_curr`，`.text+0x1070` | `0x20004`；USB 表 `0x6016d440` 的 case `0x6003003c` | 请求 category 6，按设备 ID 匹配 `USB_IN_I_MA_PM_XX`，再进入 ADC API | 匹配通道实际注册、ADC provider 副作用、采样新鲜度、测量位置 | 不新增 |
| `qcom_subpmic_get_pack_ibat`，`.text+0x1f40` | `0x2027`；buck case `0x6002f7c8` | PMQBG 16-bit 数据读取、校准和条件合并；具体 backend 读 `0x4fa4` 或 `0x4fc4`，第二路可读 `0x4fc6` | 运行时 backend、寄存器 read-clear / latch 语义、电流符号及校准、数据新鲜度 | 不新增 |

三个 Linux getter 都调用 `mca_adsp_glink_read_prop(property, out, 4)`，不是自行读取某个已证明安全的 PMIC 节点。底层动作属于远端 RPC，返回值也不能直接等同物理状态。

**对早期仅根据 enum 名称的推断作出修正：** `0x200c` 在 Linux BTF 中名为 `BUCK_VSYSMIN`，但其 ADSP **getter** 是 ADC 通道 0 的读取链。因此不能根据该名称断言 getter 只返回 VSYSMIN 设定值。另一方面，实际通道名称是 `VPH_PWR`，并非名称表中独立的 `VSYS`（通道 2），也不能反向将其称为已经验证的 SYS 测量。这项结果只修正 getter 的解释，不改变相同 property 的 setter 或任何控制路径的既有结论。

## 2. VSYS 名称与 ADC 读取链

字节级链路如下：

```text
Linux .text+0x10d4: property = 0x200c
Linux .text+0x10dc: mca_adsp_glink_read_prop(..., size=4)
ADSP buck table 0x6016d378, base=0x2001, entry index=11
  -> 0x6002f4c4
  -> 0x6002f4d0 call 0x60020c20
  -> 0x60020c30 r0=0
  -> 0x60020c34 call 0x60060640 (battman_adc_read)
```

`0x6002f4c8` 读取 `ctx+0xbc` 作为 wrapper 参数；wrapper 保存该参数但实际 ADC 调用使用常数通道 0。不是从 `ctx+0xbc` 取函数指针进行间接调用。

ADC 初始化器 `0x60060360` 对 DAL 执行 attach，参数中有 device ID `0x2000067` 和版本 `0x40005`，输出 handle 放入 BSS `0x602aed68`。之后遍历 58 个通道，使用名称查找 API `0x600605a0`，成功后设置 `0x602aed88 + index*12` 的有效标志。名称指针表 `0x601cd370` 位于 `adsp.b20` 实际文件字节范围，字符串位于 `adsp.b19` 实际字节范围：

| index | 指针表地址 | 字符串地址 | 名称 |
| --- | --- | --- | --- |
| 0 | `0x601cd370` | `0x6017e5c7` | `VPH_PWR` |
| 1 | `0x601cd374` | `0x6017e5cf` | `VBATT` |
| 2 | `0x601cd378` | `0x6017e5d5` | `VSYS` |

`battman_adc_read` 验证 channel / output 与有效标志，读取 handle 和初始化得到的通道描述，再在 `0x60060780` 调用 `0x60060950`。后者依 handle 标记选择间接 DAL vtable 调用：带 bit0 标记时使用 slot `+0x44`、operation `0x10`；另一分支使用 slot `+0x40`、operation 3。具体 vtable 来自运行时 attach，本轮尚未追到实际 conversion backend。

结果经过 `0x600609f0` 的 `battman_adc_evaluate_result`。日志和分支存在 stale、timeout、invalid、FIFO-not-empty 状态。通道 0 最后从结果结构 physical 字段输出；针对部分温度等通道的 `/1000` 分支不适用于通道 0。没有相应 provider / 单位 schema 时不以字段名认定绝对单位。

所以静态证据是“ADC API 请求及结果转换”，而非“仅读内存”或“仅返回设置”。不能排除实际 backend 触发转换、更新 ADC 配置或缓存；也不能据 API 名称断言它一定写硬件。两种方向都需要具体实现证据。

## 3. USB 当前电流与输入采用状态

USB 原始 getter 表 `0x6016d440`，base `0x20001`，13 个地址项；`0x20004` 指向 `0x6003003c`。该 case 传入 `r0=6`、`r1=ctx byte+3`（设备 ID）、`r2=out`，在 `0x6003004c` 调用 `0x600610f0`，之后乘以 1000。此变换本身不能证明外层最终单位或物理测点。

`0x600610f0` 在 category 6 路径调用 `0x600613f0` 选择通道，然后在 `0x60061208` 调用 `battman_adc_read`。`0x600613f0` 的 category 表位于 `0x6017dfc8`，其 index 6 指向 `0x60061564`；该分支读取 `0x601cd45c` 的名称前缀 `USB_IN_I_MA_PM_`，拼接两位十进制设备 ID，在有效通道 40..45 中比较名称：

```text
40 USB_IN_I_MA_PM_01    41 USB_IN_I_MA_PM_03
42 USB_IN_I_MA_PM_04    43 USB_IN_I_MA_PM_05
44 USB_IN_I_MA_PM_06    45 USB_IN_I_MA_PM_07
```

这里存在需保留的无匹配边界：该范围搜索无匹配时计数达到 46，`0x60061840` 只检查是否大于 `0x39`（57），因此这一分支可返回 0，却没有写出匹配通道。上层 `0x600610f0` 在调用查找器前未见初始化输出通道栈槽 `r30-0xb`，之后仍传给 ADC API。静态无匹配路径不足以保证返回的是有效通道；不能据返回码 0 宣称已注册且有效，也不能依据可能的 0 数值宣称实际输入电流为 0。这不是已证明在手机运行时发生的故障，需要本机实际注册和设备 ID 证据。

作为参照，Linux `qcom_subpmic_get_online` (`.text+0xfb4`) 只将 `ctx+0x2f0` 的缓存值复制给输出，返回 0。ADSP property `0x20001` 的 case `0x6002ffb8` 会组合 attach 缓存与另一判断函数；本轮没有把 cache 更新和该判断追成电源路径状态。因此它不足以证明 USB 已给系统供电。

`USB_VOLTAGE_NOW=0x20002` 与 `USB_SNS_VOLTAGE_NOW=0x20003` 是另外的 getter，不在本轮深度闭合范围。在线或存在 VBUS 电压只能支持插入/电压存在；即使获得实际输入电流，也需要测点、正方向、有效标志和时间一致性才能计算输入功率。输入功率为正仍可能同时流向 SYS 和电池，不能单独证明电池充电支路已断开。

## 4. PACK_IBAT 的具体 backend 与硬件读取边界

`0x6002f7c8` case 调用 `0x6004aca0`，后者再调用 `0x6004adc0`。该 acquisition 读取 device ID `0x601cd289`，调用 `0x6007a200` (`pmqbg_main_get_ibatt_data`)；如果配置 byte `0x601cd288 == 2`，还调用 `0x6007a360` (`pmqbg_main_get_ibatt2_data`)。16-bit 结果被符号扩展，应用 BSS 校准参数 `0x6020afa8` 与 factor byte `0x601cd28b`；BSS flag `0x6020af80` 的低两位能将相应一路贡献置 0。结果合并为 64-bit，再经过 wrapper / case 的缩放。handler 中有 signed magic division 的 `/1000` 形式，但不据此单独定义最终单位。

PMQBG main API 按 ID 访问 BSS ops `0x602af9ec + id*16`；第一路调用 ops `+0x4c`，第二路调用 `+0x50`。这不是始终存在某个固定的单一函数：初始化器 `0x6009eea4` 形成 `0x602af9e8 + id*16`，依设备描述 type 的 dispatch 在 entry `+4` 写入以下三张真实文件表之一：

| 描述 type | ops 表 | IBAT slot `+0x4c` | IBAT2 slot `+0x50` | 具体动作 |
| --- | --- | --- | --- | --- |
| `0x32` | `0x601ce7f0` | `0x6009dba0` | null | 请求地址 `0x4fa4` 的 2 bytes |
| `0x47` / `0x56` | `0x601ce864` | `0x6009e5e0` | `0x6009e690` | 分别请求 `0x4fc4` / `0x4fc6` 的 2 bytes |
| `0x4c` / `0x59` | `0x601ce8d8` | `0x6009e5e0` | `0x6009e690` | 同上 |

上述描述 type 来自 `0x6018f72c` 原始 40 项 dispatch 表（base `0x32`），不是依据 PMIC 营销型号名称猜测。

两个主路实现和第二路实现都调用 `0x6005ac90`，使用 entry 的 slave 字段 `0x602af9e9 + id*16`，输出 2-byte 数据。`0x6005ac90` 读取 BSS handle `0x6020d05c` 与 priority `0x6020d061`，调用 `0x60123204`，后者以 operation `0x100` 经 `0x6000b524` syscall 继续。本轮闭合到请求具体地址与长度的硬件读取边界，没有证明该 syscall 后的完整总线实现及所有硬件副作用。

上述地址是当前 ADSP 镜像中的静态参数，不是 Linux sysfs 写入点，也不是可移植的寄存器操作说明。未证明本机实际注册为哪张表；不能直接采用任意 backend 地址作为“本机电池电流寄存器”。寄存器是否 read-clear、是否 latch / trigger、采样年龄、双电池合并和符号校准都尚未闭合。启用/禁用 flag 能导致某路贡献置零，所以输出 0 不能直接证明电池净电流为零，更不能证明充电 FET 被关闭。

## 5. Linux RPC 返回值的验证要求

`mca_adsp_glink_read_prop` (`.text+0x1e4`) 在 mutex 下构造 276-byte 读取请求，调用 `pmic_glink_write` (`+0x2f4`)，等待 completion，检查远端状态，然后才复制请求长度的数据到 caller (`+0x330`)。链路离线返回 `-107`；timeout 返回 `-110`。因此函数名里的 read 不意味着只有本地内存操作；`pmic_glink_write` 此处是发送读取协议消息，也不等同对充电控制 property 执行 setter。

调用端必须检查 getter / show handler 的错误处理、协议 response 匹配和远端状态，否则未更新输出或默认值会被误当实际遥测。本轮只闭合 selected proxy getter，完整 sysfs 入口 / framework 展示链由另一路审计补齐。不能绕过 sysfs show 直接发送 property 请求来采样。

## 6. 新增手机遥测前需要闭合的事项

1. 对应 Linux 运行时身份已验证还不够：需证明正在运行的 ADSP 与本地镜像身份及配置选择，确认实际 device ID、ops / ADC provider 注册。
2. 从拟读 sysfs show 到 proxy / RPC / 远端 handler / 实际 backend 完整闭合；ADC API 的具体实现必须明确是否触发转换或配置动作。
3. 结合本机 PMIC / ADC / QBG schema 或可靠实现证据确认测点、单位、正方向、采样年龄、错误 / stale 标志、读清除语义和可接受频率。
4. 对 PACK_IBAT 需证明校准、flag、单/双电池合并；对 USB_CURRENT_NOW 需排除无匹配路径。对 VPH_PWR 需确认物理意义，不能据 Linux getter 名称等同 SYS。
5. 之后才可由监工更新具体读取白名单和一次性对比方案。此报告既不触发手机读取，也不批准任何控制写入。证明“充电支路停充且外部电源供 SYS”还需控制后端与匹配的运行时状态证据，不能由这些 getter 的存在替代。

## 7. 可复核的离线证据

证据目录：`.tooling/bypass-check/reconnect-evidence/next-telemetry/`。

- `adc-channel-name-table.json`：逐项解析 58 个 file-backed 名称指针；channel 0 / 2 明确区分。
- `buck-get-dispatch-table.json`、`usb-get-dispatch-table.json`、`adc-category-dispatch-table.json`：getter / category 原始表。
- `pmqbg-ops-tables.json`：三张实际 ops 表的槽位和原始字节摘要。
- `pmqbg-type-dispatch-table.json`：设备描述 type 到具体注册分支的原始表。
- `linux-selected-getters.txt`、`linux-glink-read-prop.txt`：Linux 指令与 relocation。
- `adc-read-full.txt`、`adc-init-full.txt`、`usb-current-adc-api.txt`：ADC 初始化、读取和匹配分支。
- `pmqbg-current-concrete*.txt`、`pmqbg-provider-init-full.txt`、`pmqbg-read-helper.txt`、`pmqbg-bus-read.txt`：backend 选择、具体寄存器请求和总线边界。
- `offline-verification.json`：所有保存的 Hexagon excerpt 与已验证 v79 完整反汇编逐行匹配，结果 PASS；原始固件 / Linux / v79 来源摘要。
- `MANIFEST.json`：本轮材料大小与 SHA-256，方便监工独立核对。

反汇编为 Hexagon v79，分支地址按 packet-base 解码。没有用 AArch64 解码 ADSP；没有把 BSS 零填充当作实际 callback 值。本报告的证据等级为静态链路审计，未提升为运行时身份或电源路径验证。
