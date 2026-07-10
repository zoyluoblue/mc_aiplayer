# AIBot 产品与工程路线图

状态：Active  
基线日期：2026-07-10  
适用分支：`main`

本文件是当前项目路线图的唯一入口。仓库中的本地 `PLAN*.md`、`WORKORDERS*.md` 和 `reports/*roadmap*.md` 作为历史研发记录保留，不再代表当前优先级。

## 1. North Star

目标不是让 Bob 展示“会做很多动作”，而是让它成为一个可信赖的 Minecraft 协作伙伴：

> “Bob，跟我来，在这里用附近材料盖一个小屋；天黑先回家，完工后把剩余材料放进这个箱子。”

完成这句话意味着 Bob 必须能够：

- 理解 owner、地点、区域、目标容器和先后顺序；
- 给出可执行计划并持续汇报真实进度；
- 在危险、卡死和资源不足时安全恢复；
- 正确处理暂停、继续、取消、替换和追加任务；
- 重启后恢复未完成 Mission；
- 只在最终状态经过验证后报告完成。

## 2. 产品定位

AIBot 保持现有核心路线：

```text
LLM 理解意图
    → Mission / Goal 描述目标状态
    → 确定性 Planner 展开依赖
    → Task 状态机执行
    → Action / Pathfinding 操作 Minecraft
```

不让 LLM 进入逐 tick 执行回路，也不以继续增加 Tool 数量作为主要进度指标。

需要明确区分两种运行策略：

| 模式 | 定位 | 隐藏资源扫描 | 紧急传送 | 适用场景 |
|---|---|---:|---:|---|
| `strict_survival` | 公平生存伙伴 | 禁止 | 禁止 | 生存服、展示真实游戏能力 |
| `operator` | 服务器管理型助手 | 可配置 | 可配置 | 私服、调试、自动化运维 |

已实现：新安装默认 `strict_survival`，`operator` 必须显式开启；旧配置缺少模式字段时按 `operator` 兼容启动并输出一次迁移警告，无效配置和无效环境变量 fail closed 到 strict。四项增强能力均为独立开关，实际 profile/effective capabilities 同时显示在 UI、snapshot 与结构化日志中。

`strict_survival` 不启用隐藏资源扫描、紧急传送、强制拾取或手动传送；资源目标在读取前先经过可见性边界。导航碰撞预检、首次 spawn/存档 restore、相邻 fake-player 下台阶、自己的 fishing hook 和显式 owner 跟随位置属于已记录的运行适配器，不可被资源搜索复用。

## 3. 当前基线

- Minecraft `1.21.3`、Fabric Loader `0.18.4`、Java `21`。
- 9 类 Goal、63 个 Tool 注册点、34 个具体 Task 状态机。
- testmod 的 `/aibot verify all` 含 98 个确定性场景，另有 1 个 opt-in 确定性场景和 4 个真实 LLM 场景；生产 jar 不包含 test/verify 命令。
- `clean test runGameTest build` 成功；当前有 19 个 JUnit 类、68 个测试和 3 个 GameTest。`capability_profile + runtime_control_suite` 在 strict/operator 下均为 7/7；两 JVM restart-resume 精确恢复非默认 checkpoint，并以原 Mission `COMPLETED 4/4` 结束。
- PR CI、nightly 双 profile/seed matrix 与手动计费 LLM workflow 已建立；evidence bundle 会绑定 revision/config/actual seed/runtime/profile 并做不可变封存。
- 现有多 seed 报告能用于诊断，但缺少 commit SHA、配置摘要和 actual seed 回读，不能作为 HEAD 的发布证明。

当前能力快照与证据边界见 [能力矩阵](docs/CAPABILITY_MATRIX.md)。

## 4. 目标运行时

不重写现有 Task；先用一个统一 Runtime 包住现有模块，再逐步迁移状态所有权：

```text
BotRuntime
├── IntentInbox
├── DecisionSession(epoch)
├── MissionQueue(persisted)
├── ExecutionStack
├── SafetyArbiter
├── WorldModel(dimension-aware)
├── Memory
└── EventLog(correlation id)
```

每项可对外承诺的能力最终收敛为 `Capability`：

```text
Capability
├── Tool schema
├── Preconditions
├── Planner expansion
├── Task factory
├── Postcondition
└── Verification scenarios
```

## 5. 阶段计划

### P0：稳定运行时（实现完成）

目标：让 Bob 可安全控制、可真正取消、可恢复且不会假完成。

- 过期 LLM 响应隔离；
- 原子暂停、恢复、取消与替换；
- owner/OP 权限统一；
- Goal 最终后置条件；
- Mission 与队列持久化、统一 cleanup；
- 运行模式契约；
- 快速单元测试、CI 和可审计报告。

实现状态：上述范围已完成并通过自动化验证。当前本地工作树尚未提交，所以本轮真实 evidence 按规则标为 `UNVERIFIED`；这不是测试失败，而是防止 dirty source 被误 pin 为发布基线。clean commit/CI 才能产生可 pin 的 `VERIFIED` bundle。

详细拆分见 [P0 Runtime Hardening](docs/P0_RUNTIME_HARDENING.md)。P0 全绿前，不新增高层 Goal 和大规模技能。

### v0.1：可信赖的核心助手

只打磨四条黄金链：

1. 跟随 owner、待命、守卫指定位置；
2. 从空背包获得稳定食物；
3. 从零获得铁锭/铁装备，并送入指定容器；
4. 在 owner 标记区域建造小屋，夜间暂停，白天继续并验收。

发布门槛：

- 每条真实链路至少 20 个固定公开 seed，成功率 `>= 90%`；
- `cancel/replace/restart-resume` 确定性场景 `100%` 通过；
- 不允许无限循环、过期工具调用或 `PARTIAL` 冒充 `COMPLETED`；
- 4 个 Bot 在约定参考机器上运行时 TPS `>= 19`；
- 无未授权玩家控制、取物或传送路径。

### v0.2：协作体验

- owner 坐标、视线目标、选中区域和目标容器进入上下文；
- 支持“这里”“这个箱子”“跟我来”“剩余材料给我”等协作语义；
- 工作区域、禁止破坏区域、材料预算和施工预览；
- 面板展示 Mission 队列、真实进度、失败原因、资源预算和 token 成本；
- 中文/英文 paraphrase 意图路由回归达到 `>= 95%`。

### v0.3：高级生存与生产

- 稳定深层钻石与百量级采集；
- 建筑修复、续建和多蓝图组合；
- 可持续农场、基地补给与长期经营；
- Nether 与跨维度 WorldModel；
- 单 Bot Runtime 稳定后再启用多 Bot 分工、租约和资源预留。

## 6. 工程门禁

每次合入必须满足对应层级：

| 层级 | PR 门禁 | Nightly 门禁 |
|---|---|---|
| Pure logic | JUnit、静态不变量检查、编译 | 同一套 JUnit/静态检查/生产构建 |
| Runtime contract | 取消、权限、生命周期、两 JVM restart probe、strict evidence | 7 项 profile/runtime 合约 × 2 profile × 2 seed |
| Game behavior | 3 个 Fabric GameTest | 现阶段同为 3 个 GameTest；v0.1 再分片扩到 98 场景、多 seed |
| LLM routing | 不注入 API key | 独立手动 workflow，明确确认计费后运行 4 个真实 LLM story |

上表描述当前已落地门禁；98 场景多 seed 分片与 Nightly restart/resume matrix 是 v0.1 后续项，不作为当前 P0 完成度的既成事实。

每次能力报告必须记录：

- `commit_sha`、`build_version`、时间；
- Java/Minecraft/Fabric 版本；
- 配置 hash、requested seed、actual seed；
- 场景、耗时、结果、失败阶段；
- 是否允许 privileged perception/teleport。

## 7. 非目标

在 v0.1 前明确不做：

- 端到端 LLM 或 RL 取代确定性 Task；
- 为展示数量继续增加 Tool；
- 未定义 lease/recovery 的多 Bot 编排；
- 同时支持多个 Minecraft 大版本；
- 没有验收标准的 UI 大改版。

## 8. 下一检查点

P0 已完成，下一步进入 v0.1 可靠性收敛：

1. 用户授权提交后，让 clean CI 生成首批 `VERIFIED` evidence，并通过 `pin_baseline.sh` 显式更新 `reports/baselines/index.tsv`；
2. 对四条黄金链各跑至少 20 个公开 seed，先选择“铁装备”或“简单建房”中的一条收敛到 `>= 90%`；
3. 将真实失败按 stage 聚类，只修最高频根因，不扩大 Goal/Tool 数量；
4. 以 strict 为发布口径，operator 作为单独可审计的服务器自动化模式持续回归。
