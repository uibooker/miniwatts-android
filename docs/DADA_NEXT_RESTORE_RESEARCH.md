# Dada 下一步：独立充电票的恢复边界

日期：2026-10-13。本次仅定向复核已取得的 `mca_common` 原始 ELF、buck/quick setter 和现有接口资料，没有重新漫扫 20 个模块，没有调用 ADB、操作手机、改插件或编写内核驱动。

**可决定路线的结论：当前已定位接口不能让纯用户态 SukiSU 模块完整恢复插件 owner 的原始 absent/inactive 状态。若完整恢复是验收条件，现阶段不能把 `charge_enable` 接入自动后端。下一项工程工作应是获取匹配的 MCA/内核源码与构建资料，评估受限 snapshot/apply/restore 接口；继续试同一节点的数值不能补上缺失的 enabled 参数和删除语义。**

## 现有入口与内核 API 是两层能力

| 入口 / API | 本地直接证据 | 能力边界 |
| --- | --- | --- |
| charge_enable/buck | setter `.text+0x5ddc`，`0x5e4c..0x5e58` 的 mca_vote enabled 固定为 1 | 0 是启用的禁止票，1 是启用的允许票；没有配对撤票参数。 |
| charge_enable/quick | setter `.text+0xa6ec`，enabled 固定为 1 | 同样不能停用该 owner。 |
| ichg_limit/buck | 已验证 `.text+0x610c..0x6124` 的 value=0 撤的是 charge_limit | voter 与 chg_enable 不同，同名 user 不跨 voter 寻址。 |
| common debugfs status | `.text+0x1348` 逐个输出已分配名字和 en/value | 可区分行不存在与行 en=0，但本轮现有采集没有可用节点；不是撤票入口。 |
| mca_vote | `__ksymtab+0xc0` 重定位至 `.text+0x5f4` | 内核可以传 enabled=0。会保留 name/槽位，不提供 remove-client。 |
| mca_find_votable | `__ksymtab+0x18` 重定位至 `.text+0xe6c` | 在内核链表按名字查找，返回原始对象指针；不是用户空间 syscall，也没有在此函数获取对象生命周期引用。 |
| mca_rerun_election | `__ksymtab+0xa8` 重定位至 `.text+0xd8c` | 按 force、override、cached effective/default 选择值，再调用回调并返回错误。不是撤票，也不重新扫描客户端进行聚合；不能单独作为事务恢复。 |

这些 export 是磁盘 ELF 的声明事实；root 权限不会把它们自动变成可从 shell 调用的接口。已有 userspace 搜索范围与各排除入口详见 [撤票入口复核](DADA_RECONNECT_CONTROL_RESEARCH.md)。本报告不将范围内的未发现扩大为整机绝对不存在。

## 新发现：按 owner 查询可能先创建 owner

定向检查查询 API 得到不能忽略的副作用：

- `mca_is_client_vote_enabled_locked` 在 `.text+0x154` 调 `kstrdup`，`0x15c` 保存客户端名字；非 locked 版本在 `0x254/0x25c` 做同样的操作。
- `mca_get_client_vote_locked` 在 `0x354/0x35c` 也有名字分配；普通 `mca_get_client_vote` 加锁后调用它。
- 这些路径在找不到名字且存在可用槽位时可分配名字。因此调用前 absent，查询后可能变成已分配 inactive。返回 false 不能区分两者，也不能证明查询无修改。
- 对 AND 类型，`get_client_vote_locked` 在 `0x388..0x390` 检查 en，inactive 时在 `0x3a4` 返回 -22，不能获取其原始存储 value。现有 status show 直接读取字段才保留这一信息。

所以新增接口不能简单拼接 `find_votable + is_enabled + get_vote + vote` 就声称精确快照。它需要真正不分配名字的查询，明确返回 `present/enabled/stored_value`，并在同一受控生命周期和锁协议中处理并发。当前两个查询 export 连“检查前保持 absent”这一条件都不保证。

这里的副作用只针对上述按 owner 查询的内核函数。既有 debugfs `status` show 仍是加锁后逐字段读取和输出，没有调用这些查询或分配名字；原有 status 只读白名单结论保持不变。

## 完整恢复与功能恢复要分别验收

| 原始状态 | 当前 userspace 写 1 | 理论 mca_vote(enabled=0) | 严格恢复还缺什么 |
| --- | --- | --- | --- |
| absent | 留下 active allow，不相等 | 留下有名字的 inactive，不相等 | 只删除该插件创建槽位的受限语义，或明确修改验收为事先注册的固定 inactive 基线。 |
| present/inactive | 留下 active allow，不相等 | 可停用，但原 stored_value 和硬件结果未因此自动恢复 | 无副作用快照、准确原 value、回调/硬件确认。 |
| present/active | 只有原值已知为 1 才可能恢复票字段 | 停用反而改变原状态 | 原 enabled/value 快照；禁止只保存 effective 值。 |

**功能恢复不要求删除名字。** 对新 owner 成功执行 enabled=0 后，它不再参与普通选举，名字/slot 残留主要是资源状态变化（最多 32 个槽位）和逐字段差异。复用固定独立 owner 可避免每次累积新槽位。如果验收只要求撤去插件约束并交回正常聚合，这一内核停用语义有价值；若要求原始 absent 状态逐字段相等，才另外需要删除语义。当前用户空间连 enabled=0 都没有暴露，所以两种验收均被现有入口限制，不能靠保留 active allow 解决。

`mca_vote` 先改 client/en/value 与 effective 缓存，后执行硬件回调，失败不回滚；同样票还可能跳过回调。buck setter 将 vote 返回改为 0，顶层 sysfs 又吞错误。最新远端离线分析还显示属性 0x2003 执行附加 callback、但主返回只覆盖主 charge-enable API。因此软件票恢复、主 PMIC 位恢复、附加动作恢复是不同验收项。

允许票 1、force/override、借用 OEM owner 都不能作为这张独立票的精确恢复替代。重启、重载策略或销毁整个 voter 也不是只恢复插件 owner；本方案不以这些动作补缺口。

## 若新增受限接口，必须先解决的前提

以下是离线架构评估，不是可直接编译/加载的方案，也不授权手机实验。

1. **匹配构建与许可。** 取得正在运行的内核/MCA 对应源码、头文件、配置、工具链、Module.symvers 和模块 ABI。已采集运行版本为 6.6.77 系列，磁盘 common 的 vermagic 为 6.6.57 系列且有 modversions；build-id 对应不能消除新扩展的 ABI 校验问题。本地 common 没有尾部模块签名标记也不能证明当前内核接受新未签名模块。签名强制、信任链、模块加载策略与 SukiSU 能力均未闭合，不能假设可加载。
2. **准确对象与生命周期。** `chg_enable`、`quick_chg_en` 名称有 ELF 创建点，但 lookup 返回指针不等于持有有效引用。需要源码确认命名唯一性、创建/销毁时序、模块依赖与设备解绑保护；不得固定猜测运行地址、结构偏移或以相似机型头文件替代。BTF 外部 base 类型未解析，不能补造原型。
3. **固定最小权限。** 仅接受固定插件 owner、白名单 voter 和合法操作；不开放任意 owner、任意 voter、任意寄存器或函数地址。保留 OEM/温控/override 的正常聚合约束。buck/quick 分别评估，不能 `all` 广播。
4. **真正原状态与并发协议。** 无分配 snapshot 明确 absent/inactive/active、value 和 generation；避免多次 getter 间的状态变化。严格 absent 恢复需在 MCA 内实现受限槽位删除和重新聚合并确认所有索引/缓存安全，现有 export 没有该契约。预注册固定 owner 是另一种产品契约，不能追认成原始 absent 的还原。
5. **失败与恢复不是布尔开关。** 现有 mca_vote 无 rollback；新增入口必须保存自己的旧票、正确传播错误，拒绝未知状态，并支持可审计的恢复结果。简单失败后反向 vote 不保证硬件或远端附加状态已复原；rerun 会重放当前 effective，但仍可失败、也不覆盖未观察的附加状态。不能假设 timeout 表示远端未执行；断连、进程死亡、崩溃后的恢复必须有明确设计。
6. **控制语义仍需独立完成。** 即使撤票完整，也不能据此宣称外部供系统、电池隔离或 CP/PD 状态完整恢复。远端 callback、电气拓扑与硬件确认缺口仍按其他报告保留。

## 后续路线选择

推荐把 **匹配源码/构建资料是否可取得** 作为下一道明确决策点：可取得时，先在离线源码层定义无副作用 snapshot 和受限 apply/restore 契约，再讨论实现与验证；不可取得时，维持该设备的只读诊断，不把现有 charge_enable 接入可写后端。纯用户态加脚本不能修补内核协议遗漏。

如果未来发现厂商已提供等价的受限用户入口，需要直接证明它能表示 enabled=0、读取原始 owner 状态并返回真实执行结果，再替换上述路线。仅出现一个新名字或可写权限不能作为通过条件。

本次关键 evidence： [API/export 与重定位](../.tooling/bypass-check/reconnect-control/next-restore/targeted-api-evidence.json)、[定向指令窗口](../.tooling/bypass-check/reconnect-control/next-restore/targeted-api-windows.txt)。基础来源是本地原始 ELF 和已审查采集，所有地址为 ELF `.text` 偏移。原始四模块散列保持不变。
