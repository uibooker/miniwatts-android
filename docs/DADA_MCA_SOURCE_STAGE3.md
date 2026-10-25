# dada 第三轮：公开仓库发现与有限目录核对

日期：2026-10-13。仅在电脑上读取公开 GitHub 仓库元数据、固定ref和目录网页；没有访问手机、ADB、浏览器、凭据，没有执行下载内容、构建、安装或编写猜测布局的可加载扩展。旧报告/证据未修改。

## 结果

**三个限定仓库名称/描述查询没有导出新的实际 MCA C/头文件入口。** 对三个未检查的非空SM8650模块仓库固定提交并读取根目录、`qcom/opensource`直接目录；这些页面没有提供明确的 MCA driver/include 路径依据，因此没有继续递归扫描或接口语义审查。[E1][E2][E3]

这不是代码级穷尽搜索，也不是整个仓库或所有公开来源不存在 MCA 的结论。真实 C/头文件取得数为0，接口/type/lifetime审查候选数为0，当前 dada 构建匹配新增证明为0。未把SM8650仓库名、同名模块或通用内核描述当成本机ABI对应证据。

## 三个仓库发现查询

GitHub `/search/repositories` 查询限定 `in:name,description`，`per_page=30`，没有调用或冒充authenticated code search。三份HTTP200响应原文均保留，`incomplete_results=false`，返回条目数分别等于total_count。[E1]

| 查询 | total_count / 返回数 | 新入口筛选结果 |
| --- | --- | --- |
| `mca xiaomi in:name,description` | 2 / 2 | dev-mca/HyperPack为icon-pack项目；Mahfujs247/MCACharge为既查WebUI/shell模块，不作为新driver/include来源 |
| `android_kernel_xiaomi_sm8750 in:name,description` | 7 / 7 | 均已出现在旧仓库发现记录中；其中piano-LAB模块树已经检查，本轮排除。其他此前只发现过的对象没有MCA目录证据，本轮未深查，也没有据此排除其全部文件 |
| `android_kernel_xiaomi_sm8650 in:name,description` | 24 / 24 | 包含通用kernel、modules、devicetrees、预编译device-kernel和manifest对象；限定选择下面3个未查、非空modules仓库作目录预筛选，其余不扩大跟进 |

sm8750列表中的 `zesshiru/android_kernel_xiaomi_sm8750` 描述称dada、6.6.139、基于Google GKI android15-6.6；这不是当前运行 `f9a1d4bd8353-abogki440974771` 或已加载MCA的来源对应关系，也不提供MCA目录依据。没有据其描述断言所有文件无MCA。

排除列表利用旧 `github-research/kernel/pinned-refs.json`、两份旧repository-search响应和stage2固定onyx入口记录，不重新读取MiCode dada/onyx symlink、piano-LAB既查modules树、Lineage sm8250、Xtra sm8450、MCACharge、ACC等旧对象。完整排除依据保存为 `excluded-prior-objects.json`。[L1][L2]

## 三个固定提交的目录预筛选

三次GitHub ref API均返回403，原文明确是rate-limit响应；没有计作ref不存在或源码无匹配。随后只对同三个已选仓库，用公开 `git ls-remote` 读取指定分支，成功取得以下40位commit。没有克隆、checkout或执行仓库代码。[E2]

| 仓库 / 分支 | 固定commit | 根目录返回数 | qcom/opensource 返回数 |
| --- | --- | --- | --- |
| [cnmrlin/android_kernel_xiaomi_sm8650-modules](https://github.com/cnmrlin/android_kernel_xiaomi_sm8650-modules/tree/875428614b3dfcfb508994d51e4d8302ab294e93) / lineage-24.0 | `875428614b3dfcfb508994d51e4d8302ab294e93` | 5 / totalCount5 | 20 / totalCount20 |
| [kmiit/android_kernel_xiaomi_sm8650-modules](https://github.com/kmiit/android_kernel_xiaomi_sm8650-modules/tree/d88e7df7ff7159ad670b7e5b0ee9d3bfc7325442) / lineage-23.0 | `d88e7df7ff7159ad670b7e5b0ee9d3bfc7325442` | 5 / totalCount5 | 20 / totalCount20 |
| [Xiaomi-SM8650/android_kernel_xiaomi_sm8650-modules](https://github.com/Xiaomi-SM8650/android_kernel_xiaomi_sm8650-modules/tree/974789eb54a7e5339d6a7c5ce12abfe81b821b0f) / lineage-21 | `974789eb54a7e5339d6a7c5ce12abfe81b821b0f` | 4 / totalCount4 | 14 / totalCount14 |

GitHub API限流后使用上述固定commit的公开目录HTML，不使用浏览器登录态。页面HTTP200原文与其中服务端 `react-app.embeddedData` 的JSON原文解析保留；只解析数据，没有执行页面脚本。六个页面的currentOid都与git读取的commit一致，页面path分别为 `/` 与 `qcom/opensource`；items长度与totalCount一致。[E3]

三份根目录都显示 `nxp/opensource/driver`、`qcom/opensource`、Android.bp、Android.mk；前两份另显示合并路径 `xiaomi/touch-driver`。qcom直接子目录为audio、camera、display、graphics、data/network、video等模块，前两份还有bt、dsp、fingerprint、spu、synx等；第三份较少。完整路径名单见 `bounded-directory-summary.json`。已查看的直接目录没有MCA、charger/power或 `vendor/xiaomi/proprietary/mca/{driver,include}` 入口依据，因而没有选择明确MCA文件继续下载。

这六份数据是**直接目录页面**，不是递归git tree。没有使用截断tree，没有将缺少某个直接入口扩大为全仓库不存在C文件；qcom目录中的其他子树、其他分支和其余21个SM8650发现结果未检查。公开页面完整返回其直接列表，只支持本轮目录预筛选边界。

## 实施判断与能改变判断的材料

代码存在、语义相近、匹配本机构建仍是三个不同条件。本轮没有真实MCA C/完整头文件，因此没有从third-party代码补充结构、锁或回调类型，也没有做兼容性承诺。取到代码后还要核对来源/许可、对象获取/引用/unbind/destroy、无分配owner查询、enabled=0释放、错误/副作用恢复及实际构建输出；一个保留inactive槽位的恢复契约不免除这些条件。[L2]

下一项有价值的来源材料仍是：dada / 24129PN74C / OS3.0.303.0.WOCCNXM 对应的 `vendor/xiaomi/proprietary/mca/driver/mca` 与 `vendor/xiaomi/proprietary/mca/include/mca` **实际文件**，附提交/包hash及source/build对应声明、vendor最终配置/manifest/完整符号输出；当前运行f9a构建的source/config/build身份需独立对应。若未来有可复核的新仓库明确MCA路径，也可先固定commit获取实际文本，再与本机ELF对照；仅换查询词或广扫通用模块树没有补齐该入口。

本轮没有确认公共代码索引的authenticated可用性；主线程单独核验浏览器登录条件。本报告不操作认证、不读取凭据，也不把索引登录/限流页面作为零代码匹配。当前结果不足以进入新可加载后端或手机试写。

## 证据与校验

全部本轮输入/输出位于 `.tooling/bypass-check/stage3/source/`，`manifest.json`冻结请求状态、原文长度/SHA-256、固定commit、有限检查范围和已有输入hash；`verification.json`独立检查保存JSON、三查询结果数量、6固定目录currentOid/path/计数及manifest字节身份。无手机操作，校验不计作电气旁路验证。

- [E1] `discovery-requests.json`；`discovery-{mca-xiaomi,sm8750,sm8650}.request.json/.response`。最初sandbox DNS失败原文另以 `.sandbox-failure.*` 保留，升级后的响应未覆盖这些失败记录。
- [E2] `candidate-ref-requests.json`；`{cnmrlin,kmiit,xiaomi-sm8650}-ref.request.json/.response`；三份`-ls-remote.request.json/.response/.stderr`。
- [E3] `candidate-root-requests.json`、`candidate-qcom-requests.json`；三仓库的`-root-tree`和`-qcom-opensource-tree.request.json/.response/.parsed.json`；`bounded-directory-summary.json`。没有执行下载HTML中的代码。
- [L1] [既有GitHub内核研究](GITHUB_BYPASS_KERNEL_RESEARCH.md)，`.tooling/bypass-check/github-research/kernel/{pinned-refs,github-sm8750-repository-search,github-mca-repository-search}.json`。
- [L2] [第二轮MCA source](DADA_MCA_SOURCE_STAGE2.md)与其stage2/source manifest、onyx固定入口响应；[第一阶段source/build边界](DADA_BUILD_SOURCE_STAGE1.md)。
