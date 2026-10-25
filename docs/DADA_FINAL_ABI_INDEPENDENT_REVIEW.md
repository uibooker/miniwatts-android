# dada 最终 ABI 与独立 owner 路线独立审查

日期：2026-10-13。审查对象是已经冻结的 [MCA 可实施性报告](DADA_MCA_IMPLEMENTABILITY_FINAL.md)，作者 manifest SHA256 为 `4e15c7b68d81c0bfaef159932984a2003ed8ea585e69564a915b56ce360a8887`，报告 SHA256 为 `878e0716bd95d4c4bc24b5ae7261e01e01fdc1627d279ab9ef62be14fd9bb01a`。审查者此前负责 SoC registry 作者任务；本次只对另一作者的 ABI／source／lifetime 子任务交叉审查，不把自己的 registry 结论当作独立重做的证据。

**独立审查 PASS；未发现需要修改冻结报告的实质性错误。** 这个 PASS 表示限定证据、函数语义和路线判断相符，**不表示插件或内核后端可部署**。当前 deployable 独立 owner 后端的 NO-GO 有依据：确切 owner／device／回调 ctx 代际、允许睡眠的执行与锁顺序、下游完成和恢复仍未闭合。固定 owner 的 `enabled=0` 功能释放与强制 callback 重放机制已存在；不能把源码缺失、没有 remove-client 或传统 Module.symvers 缺失，单独推广成所有 opaque／KPM 路线无解。

审查过程中手机／ADB操作、网络请求、安装／加载／卸载、控制节点写入和内核函数调用均为 0。没有执行作者生成器、下载内容或 vendor 代码。使用审查者自己的 ELF64 parser 和固定 LLVM，输出位于 [abi-review](../.tooling/bypass-check/final/abi-review/manifest.json)。

## 1. 独立重建与冻结身份

从原始 ELF 按 section index、section 文件偏移及实际非零尺寸 STT_FUNC 重建 common 12个和 owner 驱动8个完整函数；重新运行 LLVM，并逐条核对 **2,425 个指令字**，不依赖 ET_REL 中重叠的虚拟地址。完整 RELA 与 derived `target_string`、`containing_functions`、`external_function_reference` 分别按原始 section／符号表重建。common 完整 section／symbol／RELA 表、18个 `__ksymtab` export 及所有 CRC 均吻合。

作者 `freeze-verifier-revision.json` 说明的首版比较失败来自派生 annotation 的比较形状。独立 parser 直接按真实 RELA 重建后的 annotation 均吻合；没有执行该作者 verifier，也没有以其最终 PASS 代替字节审查。

对 392 份固定包内 KO 独立扫描完整符号表与 RELA，重建所有精确 `mca_find_votable`／`mca_create_votable`／`mca_destroy_votable` 引用。重读四份固定 Xiaomi 源码归档的全部成员和 **35,225 个普通文件完整字节**，逐成员类型／大小、逐文件哈希和精确三个标识符边界检索均吻合。SukiSU 与官方指定 KernelPatch 配套归档也完整解析，并将18份和262份选定源码文件与归档原始字节逐一比对。独立 primary 检查 **3,341 项全部通过**。

最终 [verification.json](../.tooling/bypass-check/final/abi-review/verification.json) 另逐项重哈希作者 manifest 的405个 input、446个 artifact，及独立 primary 使用的815个 input；本审查自己的 [manifest.json](../.tooling/bypass-check/final/abi-review/manifest.json) 固定报告、工具、重新反汇编、成员清单和语义锚点。manifest 完整性只证明这些固定文件的身份，不证明手机运行环境能力。

## 2. 投票、查询与恢复语义

独立确认以下关键判断，原始字和 fresh LLVM 锚点保存在 [semantic-anchors.json](../.tooling/bypass-check/final/abi-review/semantic-anchors.json)，完整函数及 RELA 在 [independent-windows.json](../.tooling/bypass-check/final/abi-review/independent-windows.json)。这些语义由人工阅读完整控制流后记录，机器检查仅核实锚点身份。

| 被审查条件 | 独立核对结果 |
| --- | --- |
| enabled0与固定 owner | disabled client 不参与普通聚合，名字留槽；固定 inactive 的功能恢复契约成立，不要求删除槽或还原 inactive stored value。一次性预留仍是 mutation，首次未初始化 vote 可能触发 default callback。 |
| vote失败／同票重试 | client在 `.text+0x7d4/+0x7d8`、effective在 `+0xa18/+0xa1c` 先更新；callback在 `+0xbf0` 后发生。失败不回滚，返回错误后 initialized 仍可设置；同票或有效值／client不变可跳过 callback。 |
| rerun | mutex内按force／override／cached effective／default取值并直接 callback，返回其结果；没有客户重新聚合、移除 owner或回滚。不能把 rc0当作电气恢复。 |
| getters | unknown owner可 kstrdup 并占槽；false／-22不能区分 absence、inactive和失败。已成功预留一个固定 owner、保证同代际与名字独占后，命中已有槽的查询可以避免分配；这是条件受限的可行用法。 |
| 锁与status | vote／rerun内部获取同一 voter mutex，不能先外部 lock 再调用。status show在mutex内打印已有票，没有 client 分配／硬件动作；它不提供跨query／apply的事务或代际绑定。 |
| buck callback | role0 rc被保留，配置需要role1时OR其rc；这一层不统一吞错，但组合值失去每个role状态，不能证明远端完成。 |
| quick callback | nonzero值写ctx cache后直接return0；zero才cancel work／stop／可能撤override，相关结果被忽略，最后return0。释放恢复1不会在该回调同步重启硬件，rerun0同样不能证明恢复。 |

准确 opaque 调用约定可以使外部模块避免复制全部 voter 私有布局；已有机器码／export／split BTF 证据与完整 C／CFI 原型、当前构建依据仍须区别。即使普通调用 ABI 先行闭合，ctx寿命、锁和完成条件仍各自存在。

## 3. 生命周期与合理替代路线

find 只在全局 spinlock 内查找，解锁后返回原始指针；destroy摘链后释放，没有获取 voter mutex或等候其持有者。但实际392模块范围只有两个 external destroy CALL26，均是 `mca_business_misc_comp:business_votable_init +0x148/+0x1c4` 的错误分支，调用前 `x0=0`。buck remove完整8字节只return0；quick remove只有sysfs移除再return0。不能凭common API形状编造buck／quick正常销毁竞态。

独立确认的不同问题是 devm ctx：buck probe把 devm 分配的ctx作为create的x4并绑定enable callback；quick probe将devm ctx传入create-voter，quick_chg_en绑定 `.text+0x4238`。common同名create在 `+0xf70..+0xff4` 返回旧对象，没有重新绑定新ctx；voter存在、设备再次bound或代码module被pin，都不能单独证明ctx属于本次probe。

Google固定同版本基线中 module引用保护代码；get_device保护device对象，但detach仍释放devres并清drvdata。正确所属device的device_lock与解绑互斥相容，配合确切driver／ctx／voter代际验证和适当provider引用，**可能保护整个同步操作**。这条合理路线被保留；当前还没有准确所属定位／同代际验证与buck、quick、provider、cancel-work、ADSP等待之间的锁顺序证明。基线 `__symbol_get` 又明确只接受GPL_ONLY export，而common导出节是普通 `__ksymtab`，不能默认用此具体helper取得MCA引用。普通LKM导入、合法显式模块引用或原生session wrapper仍需分别评估。

这些固定 Google 文件是同版本基线，不是运行 `f9a1d4bd8353` 构建的精确源码。

## 4. 固定源码检索与 KPM 限界

四份 Xiaomi archive 在已固定提交的全普通文件范围，三个精确名字 `mca_vote`／`mca_create_votable`／`mca_adsp_glink_write_prop` 均为0。结果排除外部 symlink目标和未取得子模块，也不代表当前ROM精确源码或全球源码不存在。grep.app的429、Sourcegraph的403、sandbox transport的HTTP000均独立登记为失败；缺失response body没有被当作保存过的空结果或0代码命中。[independent-request-statuses.json](../.tooling/bypass-check/final/abi-review/independent-request-statuses.json)

SukiSU官方固定提交的KPM默认n、条件Kbuild、stub及官方配套链接吻合。指定KernelPatch项目自行加载／布局／重定位KPM ELF，传统完整Module.symvers并非所有自有加载路线的统一条件。默认undefined import链经过symbol_lookup_name／Suki compact／kernel-only resolver，不能默认解析MCA module export；compact亦可暴露kallsyms_lookup_name，而Google基线该函数可以查模块，因此显式合法获取方案仍应单独判断。没有把上游HEAD或管理器标签对应到当前手机patch身份。

补充一条固定源码边界：control0／control1直接ctl调用与unload的exit／free处于**名义RCU区间**；rcupdate wrappers经 `kfunc_call` 调用内核helper，ksyms初始化匹配它们，helper指针缺失时会静默跳过调用。固定unload函数没有grace period，并在同一区间删链、exit及释放模块。此代码不能证明当前手机helper已绑定、RCU上下文或卸载行为；它进一步说明合法睡眠worker、退出与卸载同步必须有选定实现的实际契约。该补充与作者现有条件式NO-GO一致，不要求改变冻结报告。[source-excerpts.json](../.tooling/bypass-check/final/abi-review/source-excerpts.json)

本轮ABI子任务完成收口。只有取得准确MCA session／代际与锁契约、选定扩展环境身份／能力、下游完成及成对恢复证据，才可能把某一具体后端改判为可实施。独立审查没有生成可加载包，也没有用连接手机试验代替上述缺件。
