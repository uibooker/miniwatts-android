# NovaSched Zen 帧采样只读核查

版本：GitHub main 对应提交 `da023393ac8b9a736f4f7d417b243a7367c9c389`，本地克隆 HEAD 相同。本次仅阅读源码及 Git 元数据，未执行模块、dumpsys 或设备节点操作。链接均固定到该提交。

1. **确认：MTK 备选节点被提前返回阻断。** [frame_ctrl.rs:207–219](https://github.com/zeng060812-creator/NovaSched-Zen-Edition/blob/da023393ac8b9a736f4f7d417b243a7367c9c389/native/src/frame_ctrl.rs#L207-L219) 按 KGSL、GED 顺序遍历。212 行 `read_trimmed(...).ok()?` 在首节点不存在、无权限或读取失败时从整个函数返回 `None`，并非继续循环；214 行解析失败也如此。因此，没有 KGSL 而 GED 可读的 MTK 设备不会尝试 GED。首节点可读、可解析但数值超出 0..100 时才会继续。213 行 `split('%').next()?` 通常不会失败，因为 split 至少产生一个片段。[431–437 行](https://github.com/zeng060812-creator/NovaSched-Zen-Edition/blob/da023393ac8b9a736f4f7d417b243a7367c9c389/native/src/frame_ctrl.rs#L431-L437) 收到 `None` 时不调用 `on_load`，无法依据 GPU 负载调整 boost，但状态仍显示 heuristic；不代表整个调度模块停止。修复方向：单节点读取/解析失败时继续尝试下一节点。

2. **确认：clear 生效后仍减 last_total，FPS 计算错误。** [256–275 行](https://github.com/zeng060812-creator/NovaSched-Zen-Edition/blob/da023393ac8b9a736f4f7d417b243a7367c9c389/native/src/frame_ctrl.rs#L256-L275) 先 dump，再 clear（261–264），随后计算 `total.saturating_sub(self.last_total)` 并保存本窗口 total（270–271）。clear 成功清零时，total 已是本窗口帧数，不能再减上一窗口。每秒均为 60 帧时首样本约 60 FPS，此后为 0；60→55 饱和为 0，55→60 仅算 5 FPS。这会严重低估帧率而非整数下溢或崩溃。返回仍为 `Some`，所以继续走 timestats；[145–160 行](https://github.com/zeng060812-creator/NovaSched-Zen-Edition/blob/da023393ac8b9a736f4f7d417b243a7367c9c389/native/src/frame_ctrl.rs#L145-L160) 将 FPS<1 视为暂停/静态画面并缓慢回落，提前返回也跳过掉帧比例判断，可能削弱应有加压。clear 失败被忽略；若实际持续累计，FPS 差分才适用，但 dropped/total 又是累计比例。修复方向：统一采用窗口计数或累计差分模型。

3. **补充确认：正 FPS 后连续采样失败不会自动退回启发式。** [418–437 行](https://github.com/zeng060812-creator/NovaSched-Zen-Edition/blob/da023393ac8b9a736f4f7d417b243a7367c9c389/native/src/frame_ctrl.rs#L418-L437) 中，成功样本设置 `frame_ok=true`、保存正 FPS；后续 `None if frame_ok` 仅执行 `frame_ok = status.fps > 0.0`（424–426），不清 FPS，也没有失败计数。在持续激活且没有其他状态重置时，再多次失败仍保持 true，跳过 GPU 分支，显示旧 FPS，并停止依据新采样调整 boost。与“连续失败才退回启发式”的注释不符。成功的 0 FPS 样本或释放状态等事件可以改变条件，故不宜表述成任何生命周期下永远不恢复。修复方向：增加连续失败次数或样本过期时间。

父代理补充的主检查结果（本次未独立重跑）：已下载 release ZIP，结构检查通过；WebUI/config/installer validate 通过；updater 在非 root 环境退出；ARM64 ELF 静态、16K 对齐检查通过。ZIP SHA-256：`42971a94d44873883d23b9892640154908ed2a4350d3c350d3326431603dd0b9`；ELF SHA-256：`658b24b5276c565cf3c61aa77a8dd657335bab3155b81374fac2b425f5990ee0`。这些仅证明包结构和相关静态检查，不能抵消上述三项逻辑缺陷。native Rust 测试在 macOS 因缺少 inotify 链接失败，未构成测试通过或真机运行证据。

验证边界：以上是本次独立静态核查。父代理另报告临时 Rust 逻辑夹具（提取原函数并 mock util）结果：仅 MTK 节点可读 80% 时返回 None；两个 clear 后各 120 帧窗口中第二次 FPS 为 0；连续 10 次失败后 frame_ok 仍为 true。这些是逻辑夹具结果，非本次执行，亦非真机验证。外部 HTTP 抓取不可用，未独立验证各 Android/OEM 的 clear 实现、GED 输出格式；第二项以 clear 实际生效为触发条件。
