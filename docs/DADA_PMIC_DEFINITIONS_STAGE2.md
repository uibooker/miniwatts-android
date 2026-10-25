# DADA PMIC 定义核对：第二阶段

日期：2026-10-13。范围：电脑上的公开源码检索、固定提交的文本核对及既有离线报告。本轮执行手机/ADB 操作 0 次，寄存器访问 0 次，HAL 调用 0 次，安装和插件修改 0 次；没有执行下载的代码或脚本。

## 结论

本轮没有取得能够把 `PM8750BH`、`PMIH010X` 与本机 ADSP 对象模型/修订绑定起来的原厂定义，也没有取得 `0x2003` 附带写入的匹配字段定义。**不能因此扩大插件可写范围，不能把旧版 charger 字段移植到新地址。**

核对限定为两个候选组，均来自同一个旧版 Qualcomm UEFI 源码第三方镜像。它们提供了真实的旧版模型枚举、修订结构和 DCDC 接口，但不满足本机匹配条件。公开目录检索与访问受阻的结果只描述本轮检索边界，不证明原厂资料不存在。

现有基线见 `DADA_POWER_MEASUREMENT_STAGE1.md` 和 `DADA_NEXT_SIDE_EFFECT_RESEARCH.md`：前者保留 PMIC 型号/内部枚举缺口，后者已定位主操作 `PmSchgChgr_SetEnableCharging` 和附带字段写入。这里不重复 getter 取证，也不据型号字符串相近推断别名。

## 候选与版本身份

源码镜像：[SwedMlite/BOOT.XF.3.3](https://github.com/SwedMlite/BOOT.XF.3.3/tree/55ff7c1920ccf084951b2770d46ffcbe8917864f)。固定提交 `55ff7c1920ccf084951b2770d46ffcbe8917864f`，Git tree `3a9ed3a7166e7b8e9c4e43b960a5f4117e4feec6`，递归目录返回 `truncated=false`。

这是第三方账户下的源码镜像，不能认证为 Qualcomm 官方发布，也不是 sun/pakala 平台的匹配源码。文件内 Qualcomm 版权/历史标识只能说明文本声称的来源。DCDC 实现头部保留 `boot.xf/3.2` 内部路径及 2019-06-17 时间，版本名称 `3.3` 不能覆盖这个文件级事实。每个下载文件均以 Git blob SHA-1 对照该固定 tree，并保存 SHA-256；校验证明下载身份，不证明原厂授权或板级匹配。

| 候选组 | 选择依据 | 结果及边界 |
| --- | --- | --- |
| 1：`pm_version.h` 与 `EFIPmicVersion.h` | 目录明确包含 PMIC model/version 定义；协议实际有 `PmicModel` 字段，是内部枚举调查的相关候选 | 具名模型最大值 42/`0x2a`；没有 `PM8750BH`、`PMIH010X`，没有 `0x47/0x4c/0x56/0x59` 的具名模型定义。不构成 ADSP 枚举对应关系 |
| 2：`pm_schg_dcdc.c`、`pm_schg_dcdc.h` 与 `pm_schg_driver.h` | 已知 `0x2003` 附带调用属于 FSW 设置业务，故只核对该镜像的 DCDC 实现、接口和寄存器结构 | 所查三文件没有 `fsw/frequency/freq`、`0x2742`、`0x2c42` 或精确 HAL 名称；没有得到目标 FSW 字段定义。负结果仅限这三个文件，不概括整个仓库 |

没有启用第三个候选组，也没有继续扫描镜像的其他充电器实现。

## 模型与修订核对

[pm_version.h 固定版本](https://github.com/SwedMlite/BOOT.XF.3.3/blob/55ff7c1920ccf084951b2770d46ffcbe8917864f/QcomPkg/Include/api/pmic/pm/pm_version.h) 的 60-62 行将 `pm_model_type` 描述为 PMIC 型号的 peripheral subtype 值；枚举 65-110 行的具名型号覆盖到 `PMX55=42`，另有 invalid sentinel `0x7fffffff`。这里的最大具名值不是整个 Qualcomm 型号空间的上限。

该文件确实给出了显式别名，例如 `PM8150/PM8250=PM855`、`PM8150B=PM855B`。这说明型号字符串到枚举的别名关系必须由匹配定义给出；不能因为另一个 PMIC 节点处在相同 SID，就补写 `PM8750BH=PMIH010X`。

[EFIPmicVersion.h 固定版本](https://github.com/SwedMlite/BOOT.XF.3.3/blob/55ff7c1920ccf084951b2770d46ffcbe8917864f/QcomPkg/Include/Protocol/EFIPmicVersion.h) 的 101-108 行分别保存 `PmicModel`、`PmicAllLayerRevision` 和 `PmicMetalRevision`。`pm_version.h` 的 148-152 行也分别保存型号与两种修订。**协议字段 `PmicModel` 的存在不能证明其枚举就是本机 ADSP 对象首字节的枚举**；本轮没有取得跨 namespace 的转换或初始化绑定。

因此不能根据旧版模型编号给 ADSP 的 `0x47/0x4c/0x56/0x59` 命名，也不能从 generic `qcom,spmi-pmic`、SID7 或公开 DT 中 ADC 常量推出 main/peripheral subtype 与 revision。

## `0x2003` 附带字段的核对

候选 2 的固定文件为：[DCDC 实现](https://github.com/SwedMlite/BOOT.XF.3.3/blob/55ff7c1920ccf084951b2770d46ffcbe8917864f/QcomPkg/Library/PmicLib/drivers/schg/src/pm_schg_dcdc.c)、[DCDC 接口](https://github.com/SwedMlite/BOOT.XF.3.3/blob/55ff7c1920ccf084951b2770d46ffcbe8917864f/QcomPkg/Include/api/pmic/pm/pm_schg_dcdc.h)、[SCHG 结构](https://github.com/SwedMlite/BOOT.XF.3.3/blob/55ff7c1920ccf084951b2770d46ffcbe8917864f/QcomPkg/Library/PmicLib/drivers/schg/src/pm_schg_driver.h)。

限定的文本核对没有找到目标 FSW 字段或枚举，不形成编码到物理频率的表。因此既有离线程序观察仍只能表述为：`0x2003` 启用分支向 FSW wrapper 传 `(3,5000)`，已定位实现将其转换成 `0x2742` 的编码 0，掩码随对象模型变化。不能给 5000 补上 Hz/kHz/MHz，也不能把编码 0 叫作关闭开关。

本轮也没有获得 `0x2c42` 的直接 schema。既有 `bit1/bit0` 互换不能命名为 SMB enable、BATFET 或输入通路开关，不能以旧 SMB5 的相同偏移、相邻 HAL 名称或文件名字替代定义。

## 公开检索边界

下载文件的精确 token 核对包括 `PM8750BH`、`PMIH010X`、`PmSchgChgr_SetEnableCharging` 与 `PmicModel`。本轮未取得可验证的精确型号匹配原始文件。公开代码索引 grep.app 的 `PMIH010X` 查询返回访问验证页，没有执行该页脚本，其结果也没有计作零匹配证据；本轮未完成该索引的其他 token 查询，不声称穷尽公开代码搜索。

冻结的 GitHub repository-name 查询 `BOOT.XF` 与 `boot_images qualcomm` 用于定位目录候选，不是 code search。CodeLinaro 的公开 project-name 查询 `BOOT.XF`、`pmic` 返回空列表，`uefi` 返回三个 Rust crate 项目。它们仅描述该公开 API 的项目名称响应，不能证明全部原厂源码不含所需符号。

目录、固定提交、源码与校验记录位于 `.tooling/bypass-check/stage2/power/`。`definition-audit.json` 保存每个文件的字节身份、token 命中和枚举展开；`manifest.json` 冻结报告、脚本、来源与调查结果，`verification.json` 给出有界校验结果。

## 下一步精确所需资料

| 优先级 | 所需 primary 资料 | 必须闭合的关系 |
| --- | --- | --- |
| 1 | 与本机 ADSP 固件构建对应的 PMIC 型号枚举、版本结构及 device/comm 初始化来源；源文件可能名为 `pm_version.h`、`PmicModel` 定义或同职能文件，不能假定沿用旧镜像文件名 | `PM8750BH` / `PMIH010X` 的显式别名或不同器件关系；MCA/provider 模型 `0x47/0x4c/0x56/0x59` 的具名定义；main/peripheral type/subtype/revision；对象 `+0`、`+1`、`+0x18` 与 SlaveID/传输选择器的绑定 |
| 2 | 同一型号和修订的 SCHG DCDC FSW register schema、HAL 接口表和枚举/单位定义；须覆盖 `PmSchgDcdc_SetFswSel` 或已绑定 FSW wrapper 的实际实现 | `0x2742[1:0]/[2:0]` 各编码的物理频率、参数 5000 单位、访问条件、默认值与恢复规则 |
| 3 | 同一版本 SCHG Misc register schema 与接口表；以及对应通道的相关外设字段资料 | `0x2c42.bit1/bit0`、`0x2c43.bit0` 的名称/极性/联动/恢复；Misc 表 `+0x3c` 的具名绑定；`0x2641/0x2341[2:0]` 编码 4/5、`0x2842/0x2442[6:4]` 编码 3 和 `0x28d0/0x24d0=0xa5` 的定义 |

这些材料若只是其他型号的相同偏移仍不合格。即使最终补齐静态定义，也需要独立确认运行时对象身份及 SYS/VPH/电池/CP 的板级拓扑，再设计转换与完整恢复；本报告不提供手机试写方案。

本轮已在两个不匹配候选处收敛。没有上述资料，扩大不具名函数扫描或试写寄存器都无法补足定义证据。
