# dada 第二轮：MCA 实际源码的限定检索

日期：2026-10-13。依据主线程第二轮任务，仅在电脑上读取公开网页、GitHub API 和已有报告。没有 ADB、手机读取/写入、安装、驱动加载、寄存器访问、构建或执行上游脚本；没有修改插件、旧证据，也没有补造结构定义或 Module.symvers。

## 结论

**本轮没有取得实际 MCA C/头文件仓库，不能推进匹配驱动接口实现。** 五个限定发现查询只取得无关结果或网页明确显示的无结果；GitHub 精确代码索引返回401，不能将其当成代码不存在。新增可复核的厂商发布事实是：MiCode 问题单引用的 onyx 固定提交中，MCA 驱动和头文件入口均为指向 `vendor/xiaomi/proprietary/mca` 的符号链接。[S1][S2][S3]

这只支持本轮“未取得”的研究结论。没有证明其他公开仓库、认证代码索引或 OEM 内部资料中不存在源码。没有实际源码候选，因此详细审查仓库数为0；没有将符号链接目录当成源码仓库凑数。

本轮沿用第一阶段区分三种证据的标准：**代码存在**需要真实 C/头文件；**同一语义家族**需要实现与本机 MCA 行为对应；**匹配当前构建**还需要厂商明确的 source/build 关系及构建输出。本轮未满足第一项，后两项也没有新增证明。相同符号、版本号、工具链名或几个 CRC 均不能证明 ABI 兼容。[L1]

## 五个发现查询与索引边界

所有网页响应原文、请求 URL/时间/状态、结果区域解析和哈希位于 `.tooling/bypass-check/stage2/source/`。结论以保留的 HTTP 响应为准，不使用未保留原文的搜索摘要。[S1]

| 次序 | 精确查询 | 可审计响应 | 本轮结果的有限含义 |
| --- | --- | --- | --- |
| 1 | `"mca_create_votable"` | Bing HTTP200；10条结果为地图等无关页面 | 没有提供 MCA C/头文件候选；搜索返回结果并不保证遵守精确匹配 |
| 2 | `"MCA_EVENT_HANDLE_ALLOW_CHARGE"` | Bing HTTP200；结果区域明确“没有与此相关的结果” | 仅此保存响应没有候选 |
| 3 | `"vendor/xiaomi/proprietary/mca/driver/mca"` | Bing HTTP200；结果区域明确无结果 | 仅此保存响应没有候选 |
| 4 | `"mca_vote" Xiaomi` | Bing HTTP200；结果区域明确无结果 | 仅此保存响应没有候选 |
| 5 | `"mca_common" kernel github` | Bing HTTP200；结果区域明确无结果 | 仅此保存响应没有候选 |

在同一已限定符号范围内检查 GitHub `/search/code?q=mca_create_votable` 和 `/search/code?q=MCA_EVENT_HANDLE_ALLOW_CHARGE`，均返回 **401 Requires authentication**。这两项未提供可审计的代码结果；不能记作“0代码命中”。本轮不扩展关键词、认证方式或仓库扫描。[S2]

另执行一次允许的精确构建来源查询 `"f9a1d4bd8353" "abogki440974771"`。Bing HTTP200，保存的结果区域明确无结果。本轮没有建立该构建的公开来源对应关系，也没有由此证明所有来源不存在；没有后续扩大查询。[S1]

## 新取得的厂商发布入口

独立读取已知精确链接 [MiCode issue #40346](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/issues/40346)，其标题是 “Request to provide vendor/xiaomi/proprietary/mca driver for REDMI Turbo 4 Pro Android V(onyx)”。问题由用户在2025-10-12提出，描述 onyx-v-oss MCA 路径缺失并列出 sc8541、sc7601、sc96281_comp、mca_ut_test 模块。API读取时 state=open，评论数组为空。**这是公开问题反馈，不是 OEM 的完整源码声明，也不是 dada 的发布情况。**[S3]

对其引用的固定提交 **58e48d87fdc02f1e2978547f75ea524962217cc5**，只核验两个明确路径：[S4]

| 路径 | GitHub API 类型 | 文件 blob SHA | 实际链接目标 |
| --- | --- | --- | --- |
| `include/linux/mca` | symlink，53字节 | `e5930b1195af627e43f177d4d91a54d10c5bf907` | `../../../../vendor/xiaomi/proprietary/mca/include/mca` |
| `drivers/power/supply/mca` | symlink，55字节 | `c15342cc61dbdbca5498daaa1ebb32fdcd984684` | `../../../../../vendor/xiaomi/proprietary/mca/driver/mca` |

上述 API 响应证明这两个固定入口是链接，没有交付其目标的 C 实现或头文件；未遍历整个 onyx 仓库，因此不作全仓库缺源码结论。它们也不能给 dada 模块提供对象布局、锁、引用寿命、回调原型或恢复协议。issue 列出的 charge-pump/load-switch 名称不能替换本机已有 sc8581 等证据。

## 可以落实的材料获取要求

下一步有价值的源码请求应带上确切设备和构建基准，向 OEM 驱动发布渠道或实际构建提供者索取文件包及对应关系，而不是再凭通用 root 或同名符号试加载。[L1]

1. **厂商 MCA 来源包**：与 dada / 24129PN74C、系统增量 `OS3.0.303.0.WOCCNXM` 的已加载 MCA 构建对应的 `vendor/xiaomi/proprietary/mca/driver/mca` **和** `vendor/xiaomi/proprietary/mca/include/mca`；包括本机构建实际启用的 common、策略、charge_interface、平台/硬件接口及其 Kconfig/Makefile。提交号、压缩包哈希和适用设备/构建声明应随包提供。
2. **MCA 构建对应资料**：vendor 配置片段、最终配置、完整构建 manifest、工具链版本/编译选项、对应 kernel/vendor 的完整符号和版本输出。需要解释现有磁盘模块 `6.6.57-android15-8-g6f1d969db78f-4k` 与该源码/构建的关系；不要求它与当前运行内核版本字符串简单相等。
3. **当前内核构建资料**：运行 `6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k` 对应的源码 revision、最终 `.config`、vendor/SukiSU 配置、工具链与构建输出、实际模块签名/受保护导出和加载策略资料。要求由构建提供者明确关联该 uname，不把同6.6.77基线认作匹配。
4. **实现必须读清的契约**：votable 对象 lookup/get/put、unbind/destroy、锁范围和回调规则；无分配 owner 查询；固定 owner 的 enabled=0释放与重新聚合；回调错误传播和硬件确认、远端副作用恢复。第一版允许释放后留下 inactive slot；不额外要求 remove-client，但也不能把 inactive 当成电气恢复证明。

取得材料后先在主机固定提交并对照已有 ELF/build-id/CRC、实际类型和构建输出。未完成源码对应、生命周期及恢复审查前，本线不生成可加载扩展，不安排手机试写。root 提供权限前提，不补齐这些工程条件。

## 证据索引

- [L1] [DADA_BUILD_SOURCE_STAGE1.md](DADA_BUILD_SOURCE_STAGE1.md)：已有运行/磁盘基准、第一阶段来源和恢复边界；本轮没有重新访问手机。
- [S1] `stage2/source/web-search-log.json`；`web-query-{1..5}.request.json/.response/.parsed.json`；`origin-query.request.json/.response/.parsed.json`。五个不同发现查询加一次精确构建来源查询。
- [S2] `github-mca-create-code.request.json/.response`、`github-mca-event-code.request.json/.response`；公开 GitHub API HTTP401原文。
- [S3] `micode-issue-40346.request.json/.response`、`micode-issue-40346-comments.request.json/.response`；[主问题 API](https://api.github.com/repos/MiCode/Xiaomi_Kernel_OpenSource/issues/40346)、[评论 API](https://api.github.com/repos/MiCode/Xiaomi_Kernel_OpenSource/issues/40346/comments)。
- [S4] `onyx-mca-header-link.request.json/.response`、`onyx-mca-driver-link.request.json/.response`；[固定头文件入口](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/58e48d87fdc02f1e2978547f75ea524962217cc5/include/linux/mca)、[固定驱动入口](https://github.com/MiCode/Xiaomi_Kernel_OpenSource/blob/58e48d87fdc02f1e2978547f75ea524962217cc5/drivers/power/supply/mca)。

本目录 `manifest.json` 保存每份本轮工件的长度和 SHA256，以及来源/输入哈希。响应状态、源码存在性、语义相关性和当前构建匹配性分别记录，没有将 HTTP失败、无结果、symlink 或 issue反馈升级成安全实现或物理旁路已验证。
