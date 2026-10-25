# DADA Stage 4 无线 SOC 注册链独立审查

## 审查发现与处理

**[P2，已修订并复核] 普通无线的 gate 只改变当次调用参数，未清除缓存的开启请求。** 首次冻结报告说明了 gate 不满足时调用 `process(false)`，却没有明确排队任务仍会读取原始 request=1。这会影响“门控关闭后持续降阶/释放”的恢复判断。作者已在最终报告第 44 行补齐条件化路径，并收紧恢复段的前提；独立复核确认修订有原始指令和 RELA 支持。

完整 callback `.text+0x9ba4..0x9c20` 在 `0x9bc0` 将原始 request 写入 ctx+0x1054；request=1、ctx+0x104c=0 时，`0x9bf4` 仅把局部 w20 置零。完整 process `.text+0x867c..0x8850` 将常规 step2..10 降到1..9，并在 `0x87e4` 重新排队。完整 stepper `.text+0x5344..0x536c` 用 `0x5350` 读取 work+0x3c4，即 ctx+0x1054；没有 gate 复查。若排队成功、任务运行，且期间没有事件改写缓存或取消任务，下次 `process(true)` 会升回原阶。例如10→9→10会重新申请 `chg_enable` 和 force-vout 票。这里证明的是静态条件路径，没有声称连接的手机已经发生此现象或硬件操作成功。

首次作者 manifest 的 SHA-256 是 `d09d3811f00d72fddd9806d32b11a36d001e834d2ab9ff5ac0beec4745a24c77`；审查保存了其原字节及首次报告。最终作者 manifest 的 SHA-256 是 `44a9c72f1e0c2ea6786ce9f785c4371a37ba16994595f9dedcc0ab0848724230`。原始发现和修订 diff 均保留，未覆盖历史结论。

最终范围内未发现其他待修的实质性问题。**slot1/3 仍不能作为插件旁路后端**：两条链改变输入侧参数，复位请求不构成硬件快照恢复，固定原厂 owner 与外层错误吞掉问题没有解决。

## 独立验证结果

本审查没有运行或导入作者的提取器、生成器、冻结器或校验器。使用自己编写的 ELF64 节头、符号表和 RELA 解析器，直接读取三个模块；重新运行已有主机 LLVM 反汇编，并从已有 EROFS 镜像按三个精确文件路径重新提取后逐字节比较。目标模块只作为数据读取，没有执行或加载。

| 独立检查 | 实测结果 | 产物 |
| --- | --- | --- |
| 首次冻结与原始证据 | PASS 520 项；18 code 窗口、1054 instruction words、17 data 窗口 | [raw-review.json](../.tooling/bypass-check/stage4/registry-review/raw-review.json) |
| 完整函数边界 | 12 完整函数窗口、6 局部 probe 窗口；来自各 ELF `.symtab` 的 value/size | [independent-code-windows.json](../.tooling/bypass-check/stage4/registry-review/independent-code-windows.json) |
| 来源重新提取 | 3 个 EROFS 输出与冻结模块全部字节相同，元数据相同 | [independent-acquisition.json](../.tooling/bypass-check/stage4/registry-review/independent-acquisition.json) |
| 注册路由 | 3 个精确模块的 NUL 字符串、symbol 和 RELA 重新解析一致 | [independent-routing.json](../.tooling/bypass-check/stage4/registry-review/independent-routing.json) |
| 最终修订身份 | PASS 74 项；最终 35 artifacts / 13 inputs，44 个旧记录未变、全部13 inputs未变 | [final-refreeze-review.json](../.tooling/bypass-check/stage4/registry-review/final-refreeze-review.json) |
| 恢复和副作用语义 | PASS 224 项；171 个自身 raw-word/RELA 锚点，包含 P2 修订校验 | [semantic-review.json](../.tooling/bypass-check/stage4/registry-review/semantic-review.json) |

上述计数采用本审查自身的检查粒度，不引用作者的 3647 项作为独立验证，也不将静态检查数量解释为运行成功率。首次 520 项验证针对最初冻结；最终校验确认原始窗口、反汇编、模块和输入的身份没有变化，因此对应原始证据仍适用于修订后的语义。

最终身份变化严格限于 `docs/DADA_SOC_REGISTRY_STAGE4.md`、作者的 `freeze_manifest.py` 和 `verify_evidence.py`；新加 `basic-gated-request-trace.json`。新增 trace 只引用原有完整窗口，没有新取代码范围。首次 manifest 被按原字节另存为历史引用，并排除在当前 artifacts/inputs 之外。记录详情见 [最终身份审查](../.tooling/bypass-check/stage4/registry-review/final-refreeze-review.json)，文档差异见 [author-report-revision.diff](../.tooling/bypass-check/stage4/registry-review/author-report-revision.diff)。

## ET_REL 地址与来源

所有 code 检查明确选择 `.text`，并使用节内 offset 加 section file offset 定位原始字节；所有 data 检查明确选择 `.data` 或 `.rodata`。未用多个 ET_REL 节共有的 VA=0 来匹配节。逐锚点文件 offset、word little-endian 原始字节及 RELA 保存于 [independent-semantic-anchors.json](../.tooling/bypass-check/stage4/registry-review/independent-semantic-anchors.json)。

| 模块 | ELF 字节数 / SHA-256 | 相关节身份 |
| --- | --- | --- |
| `mca_basic_wireless.ko` | 199088 / `47eef298cd8dfabe4b5d51077c1b4f0bc8c8fdf3f66fc87c7f34faad617ced2e` | `.text` index7，file offset0x4eb0；`.data` index5，file offset0xd8；`.rodata` index6，file offset0x388 |
| `mca_quick_wireless.ko` | 116088 / `1886a2c3f2abf3254b7400568e8bbfa884b9f54f41265bbee03e9d716dfb976e` | `.text` index4，file offset0x32d0；`.data` index2，file offset0x40；`.rodata` index3，file offset0x200 |
| `mca_wireless_revchg.ko` | 113824 / `f7786adc533fecef4e16f69a867c4bca9dfae407606f36c102076415ad310c3b` | 仅精确注册符号/RELA/NUL 字符串查找，没有扩大为全局不存在证明 |

三个模块均从已有 `vendor_dlkm.img` 的 `/lib/modules/<模块名>` 重新提取。镜像为77938688字节，SHA-256 `b11940f67f673076ce5f0ac409919505d7af7495f4999f123891f426ae63042b`；已有 `dump.erofs` SHA-256 `2e1c2b2d2399183803c28bbf3e695d43db06083fb398be157ece48ce31c3ef0e`，已有 LLVM SHA-256 `e4c0d646ab0133854426873bd4e308c5b3a3c31b028d22fcdc212da80aa6c6f1`。这提供源包身份，没有提供这些模块的 live build-id、probe 成功或当前注册表内容。

## 控制契约核对

| 路径 | 自身 raw/RELA 核对结果 | 适用边界 |
| --- | --- | --- |
| 新注册提供者 | 两模块 `.data+0x168` 首u32分别1/3；`.data+0x190` ABS64 指向 basic `.text+0x9ba4` / quick `.text+0x55f0`；probe写ops+8并在0x1a10/0x830调用精确register | 包内静态slot1/3；不证明活对象、没有覆盖或生命周期可靠 |
| 普通无线电流 | `.rodata+0x4a88` 的11行全部解析；step1..10同时启用 `buck_charge_curr` 与 `wireless_buck_input`；末行0/250，step0撤两票 | 表值是voter整数；没有物理电流或独立SYS供电保证 |
| 普通无线末端/释放 | step10申请enable=1/value0的充电票与enable=1/value1的force-vout票；step1..9撤这两票；step0自身只撤两项电流票 | 常规递降需要缓存request保持0且worker完成；不能泛化为任意异常step一次恢复 |
| 普通无线force-vout | 完整callback非零时调用role0 vout6000；零路径直接返回0 | 撤票没有在本callback恢复原电压；SOC外层返回0吞掉process状态 |
| 快速无线SOC | 固定 `soc_limit` 对 `wls_quick_chg_disable` 始终enabled1、value为原始status；type2/default0 | false是仍激活的中性票；disable零分支没有立即CP重启或排队 |
| 快速无线stop/bridge | stop有条件地关闭CP0/1，修改parallel charge、mode、ADC、vout和其他owner票；bridge首hwid u32必须5，可能请求vout6000/10000、输入300、轮询vout/iout后切桥并撤输入票 | 多项原厂状态变化；没有保存完整快照，也没有持续SYS独立供电证明 |
| remove | basic完整函数只调用移除sysfs link group；quick完整函数只返回0 | 所查remove本身没有注销smart ops、取消SOC worker或独立释放 |

普通无线输入 callback 直接调用 `platform_class_buckchg_ops_set_wls_input_curr_lmt`；快速无线使用 `thermal_phone`、`wireless_qc`、`quickchg`、`qc_sw_bridge` 等固定原厂 owner。上述路径不具备插件独立 owner 的申请、无副作用查询、配对释放和错误恢复契约。沿用此前 voter/provider 限制的地方仅作为既有证据前提，本次没有重新扫描或验证其他模块的全部实现。

## 范围和交付

结合已接受的 Stage3 slot0/2，当前静态闭合4/6槽；slot4/5仍未闭合，没有扩大第四模块检索，也不能推断为空。未确认实时加载身份、输入与电池通路分离、可恢复ABI或实机物理效果。

本子代理的手机动作、ADB、网络动作、驱动加载和安装次数均为0；未修改作者原始证据、旧阶段文件或插件。主代理另外取得的只读手机信息不属于此独立审查的来源或验证范围。最终审查输入与产物冻结于 [registry-review/manifest.json](../.tooling/bypass-check/stage4/registry-review/manifest.json)。
