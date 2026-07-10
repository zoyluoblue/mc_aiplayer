# P0 Runtime Hardening

状态：Implementation complete（当前工作树，尚未提交）  
目标：以小步、可独立验证的提交，消除当前发布阻断问题。  
原则：先定义契约，再修改实现；不借 P0 重写现有 Task。

## 实施状态

更新于：2026-07-10

| 范围 | 状态 | 当前证据 |
|---|---|---|
| P0-07a Test bootstrap | 已完成 | 19 个测试类、68 个 JUnit 测试，另有 3 个 GameTest；失败均返回非零退出码 |
| P0-01 Decision lease | 已完成 | lease/session 测试覆盖旧回调、重复 callback、continuation、外部 invalidate 和同 UUID 新 runtime |
| P0-02 Intent control | 已完成 | `IntentController`、Mission paused gate、Task origin、可嵌套 `ExecutionStack`；控制套件 6/6 |
| P0-03 Authorization | 已完成 | owner/OP 策略统一覆盖 command、chat、C2S、订阅、Tool 与 Job；拒绝进入 SECURITY 审计 |
| P0-04 Goal postcondition | 已完成 | 9 类 Goal 使用 typed predicate；终态统一为 `COMPLETED/PARTIAL/FAILED/CANCELLED` |
| P0-05 Lifecycle/persistence | 已完成 | schema v1、原子合并写、legacy migration、Mission/checkpoint/Job lease 恢复；两 JVM 实测 PASS |
| P0-06 Operating profiles | 已完成 | 新装 strict、legacy operator 兼容、4 项 capability gate、UI/日志审计；双 profile 7/7 |
| P0-07b CI/nightly | 已完成 | testmod 与生产 jar 隔离；PR、nightly 双 profile/seed、手动计费 LLM 工作流已配置 |
| P0-08 Evidence | 已完成 | 不可变 bundle、动态端口/runDir、校验/脱敏、batch、显式 pin 与 baseline index 已实现 |

## 执行顺序

```text
P0-07a Test bootstrap
   ├── P0-01 Decision epoch
   │      └── P0-02 Intent control
   │             ├── P0-04 Goal postcondition
   │             └── P0-05 Lifecycle/persistence
   ├── P0-03 Authorization
   └── P0-06 Operating mode contract

P0-07b CI/nightly 收口
   └── P0-08 Evidence pipeline
```

每个行为工单都先提交可复现的失败测试，再提交实现；P0-07 不是最后补测试，而是贯穿整个阶段。

## P0-01：隔离过期 LLM 响应

问题证据：

- `BrainCoordinator.handleMessage` 在活跃 Goal 时允许新请求进入；
- `AsyncDecisionExecutor` 回调没有 request id；
- 旧请求晚到后仍可进入 `ActionDispatcher`。

实现范围：

- 为每个 Bot 增加单调递增的 `decisionEpoch`；
- 每次新消息、reset、外部 panel/command cancel、despawn 和 server stop 都使旧 epoch 失效；
- HTTP 回调回到主线程后，先校验 bot 存活、Runtime 身份和 epoch；
- 过期响应只记录 `stale_decision_dropped`，不得写 history 或 dispatch Tool。

LLM 同一批 Tool 内的 `stop/abort_task` 已通过 typed `ControlEffect` 保留当前 APPLYING lease，整批 Tool 完成后再根据是否存在 replacement 决定终止或续航；`stop + 新 Goal` 不会被中途截断。

验收：

- 构造 A 请求延迟、B 请求先返回，A 的 Tool 永不执行；
- reset/despawn 后返回的响应不影响新 Bot；
- 正常单请求路径行为不变。

风险：不能只依赖 `Future.cancel(true)`；网络调用可能不响应中断，epoch 校验必须是最终防线。

## P0-02：统一暂停、取消和替换语义

当前实现：`cancel_current/cancel_all/replace/pause/resume` 均通过同一事务入口；用户暂停与安全抢占分层保存。

问题证据：

- `stop` 只停止 `ActionPack`；
- `abort_task` 和面板 `abort` 只中止当前 Task；
- GoalExecutor 可能把中止视为失败并重新规划。

先固定契约：

| 操作 | 当前 Mission | 队列 | paused Task | LLM epoch |
|---|---|---|---|---|
| `pause` | 保留 | 保留 | 保留 | 失效 |
| `resume` | 继续 | 保留 | 恢复 | 新 epoch |
| `cancel_current` | 清除 | 保留 | 清除 | 失效 |
| `cancel_all` | 清除 | 清除 | 清除 | 失效 |
| `replace` | 清除并换新 | 默认保留显式追加项 | 清除 | 失效并新建 |

注：表中 epoch 描述适用于玩家面板/命令等外部控制。LLM 的同批 `stop + replacement` 为保证批次原子性，会保留当前 APPLYING lease；批次结束后若 replacement 已启动则继续正常 continuation，否则结束该 decision。

P0-02a 已实现：

- 唯一 `IntentController` 原子处理 Brain、Goal/queue、BotMemory、claimed Job、active/paused Task、ActionPack、失败缓存和 UI 状态；
- `stop`、`abort_task`、`cancel_all`、面板直接派工/abort/reset、命令直接派工/abort/reset、despawn/server-stop 已接统一入口；
- 取消结果使用 `CANCELLED`，内部安全熔断仍使用 `FAILED`；
- queue 只在下一 server tick 晋升，避免同 tick 重复取消连续吞掉排队目标；
- `IntentController.replace()` 已接面板直接派工、`/aibot task` 和 `/aibot memory goto`；LLM 生产路径通过同批 `stop + Goal/Action` 实现等价替换；
- `runtime_control_suite` 验证 200 tick 不复活、重复取消幂等、队列晋升、Goal/action replacement 及 replacement 启动失败回滚。

P0-02b 已实现：

- 引入 Mission-level paused gate、Task origin 和可嵌套 ExecutionStack；
- 安全层允许保命工作，但不得自动恢复用户显式暂停的 Mission；
- 面板、命令和聊天入口统一 `pause/resume` 语义。

`ExecutionStack` 按 LIFO 恢复嵌套安全任务；安全任务结束后若 Mission 仍被用户暂停，不会误恢复。所有 Task 分配入口均携带 `TaskOrigin`，持久化只在存在 active/queued Mission 时保存 pause gate。

验收：

- “别挖了，改盖房”不会先把旧矿挖完，也不会把建房排到错误位置；
- `cancel_all` 后 200 tick 内无 Goal 复活、无旧 Tool 回调；
- cancel 后面板显示 idle/cancelled，不残留 FAILED。

## P0-03：统一 Bot 授权策略

问题证据：

- 命令和部分面板操作要求 OP；
- item move、teleport 和 `@Bot` 聊天入口没有一致的 owner/OP 校验；
- 传入 botName 时可以解析任意 Bot。

实现范围：

- 新建 `BotAuthorizationPolicy`；
- 明确 `VIEW/COMMAND/INVENTORY/TELEPORT/ADMIN` 权限；
- 默认 owner 可操作自己的 Bot，OP 可管理全部，其他玩家拒绝；
- 所有 command、chat、C2S payload、订阅和 bot-to-bot 消息统一调用；
- 拒绝事件记录 actor、bot、operation，不记录敏感消息正文。

验收：

- 非 owner 无法订阅、下令、取物、放物或传送任意 Bot；
- owner 和 OP 的允许矩阵全通过；
- botName 为空和显式名称两条路径行为一致。

风险：需要先决定是否允许共享 Bot；若允许，应使用显式 ACL，不应继续依赖名字。

## P0-04：Goal 最终后置条件

问题证据：

- 多数 Goal 在 steps 耗尽时直接报告完成；
- `Stockpile` 等步骤失败可能被 best-effort 跳过；
- `PlaceStationsTask` 和 Build 的部分落块可被视为完成。

实现范围：

- 每类 Goal 定义 typed `GoalPredicate`；
- 完成前基于 WorldSnapshot/Inventory/Structure 再验证；
- 结果分为 `COMPLETED/PARTIAL/FAILED/CANCELLED`；
- best-effort 只影响是否继续，不得改变最终事实；
- Build 记录 expected/placed/skipped/mismatched，并进行结构复扫。

验收：

- 库存不足、箱子未入库、工作站缺件和房屋缺关键块均不能返回 COMPLETED；
- 已经满足目标时可零步骤完成；
- UI、日志、LLM 反馈使用同一结果。

## P0-05：Runtime cleanup 与 Mission 持久化

问题证据：

- `BotRecord` 不保存 active Goal、队列或进度；
- GoalExecutor 的 per-bot Map 未在所有 lifecycle 入口完整清理；
- 持久化 Job 的 `CLAIMED` 状态重启后可能没有 owner 继续处理。

实现范围：

- 持久化声明式 `MissionSpec`、队列、checkpoint metadata，不序列化具体 Task 对象；
- 重启后重新 plan，并用后置条件跳过已完成步骤；
- 增加 `schemaVersion` 与迁移；
- server stop、despawn、death、reset 统一走 Runtime lifecycle；
- 启动时把没有有效 lease 的 `CLAIMED` Job 重新开放或明确失败；
- 持久化采用原子写，后台批量 flush。

验收：

- 挖矿、建房、排队目标在重启后继续，且不重复消耗已完成产物；
- 同 JVM 切换世界不复用旧 Runtime；
- 老格式存档可迁移或给出明确、可恢复错误。

完成结果：统一 `runtime.json` 使用 `schemaVersion=1`，后台 750ms 合并写并在 server stop 同步 flush；临时文件唯一、fsync 后原子替换。损坏或未来 schema 进入只读保护，不覆盖原文件。重启从 `MissionSpec` 重新规划并先应用 checkpoint/后置条件；旧 session 的 `CLAIMED` Job 自动重开。`scripts/persistence_restart_test.sh` 已用同一世界连续启动两个 JVM，精确比对非默认 checkpoint map、active Mission、队列与 pause 状态，验证 stale lease 重开，并在 resume 后确认原 Mission 达到 `COMPLETED 4/4`。

## P0-06：运行模式与公平性契约

已决策：新安装默认 `strict_survival`，`operator` 显式开启；旧配置缺少 profile 时暂按 `operator` 兼容并输出迁移警告。

实现范围：

- 将 hidden block scan、紧急 teleport、强制拾取等能力标记为 capability flag；
- 每个验证 run 记录实际模式；
- `strict_survival` 下只使用允许的感知与动作；
- `operator` 下保持现有增强能力，但 UI 和文档明确展示；
- README、Wiki 和配置说明与实际行为一致。

验收：

- 两种模式有独立测试矩阵；
- strict 模式代码路径无法调用 privileged capability；
- operator 模式的增强行为可审计、可关闭。

完成结果：配置、环境变量 `AIBOT_PROFILE`、服务端 snapshot 与控制面板使用同一 effective policy。资源/实体发现先经过 `ObservableWorldQuery`；strict 下禁用隐藏扫描、紧急传送、强制拾取与手动传送，死亡回到世界出生点，睡眠尊重服务器 quorum，远程放置和容器/熔炉 mutation 受 reach/visibility 约束。operator 的四项开关可独立关闭，所有 allow/deny 都产生节流后的结构化决策日志。

## P0-07：快速测试金字塔与 CI

实现范围：

- 从 `origin/alpha` 的旧测试中借鉴纯策略测试模式，不恢复旧实现；
- 优先覆盖 Decision lease、取消状态转换、授权矩阵、GoalPredicate/GoalResult、profile 解析、持久化迁移和 Job scope；
- PR CI：JUnit、compile、remap jar、最小 dedicated-server smoke；
- Nightly：GameTest/Testmod、多 seed、真实 LLM story 分离执行；
- 把完整 verify harness 从生产 jar 迁移为测试 source set，生产只保留必要自诊断。

验收：

- `./gradlew test` 不再是 `NO-SOURCE`；
- 每个 P0 bug 至少有一个先红后绿的自动化测试；
- CI 对失败返回非零状态且保存诊断产物。

当前：生产 jar 与 sources jar 均经内容检查，不含 GameTest 类和 `/aibot test`、`/aibot verify`；命令驱动 harness 位于 `src/gametest`。PR CI 运行 JUnit、GameTest、build、两 JVM restart 与 strict evidence；nightly 运行 strict/operator × 多 seed；真实 LLM story 只能手动确认计费后执行。所有 workflow 在失败时上传诊断与 evidence。

## P0-08：可审计能力报告

实现范围：

- 每次 run 写不可变目录和 manifest；
- 记录 `commit_sha/build_version/timestamp/runtime/config_hash/requested_seed/actual_seed/mode`；
- 统一 harness 锁、动态临时目录、动态端口和 cleanup trap；
- capability baseline 必须显式 pin run，不允许自动选择“最好的一次”；
- README 数字由已提交 baseline 自动生成或引用。

验收：

- fresh clone 能复现 baseline；
- 报告可追溯到唯一代码与配置；
- 缺失、损坏或 metadata 不完整的报告被标为 `UNVERIFIED`，不得计入发布门禁。

完成结果：`scripts/evidence_run.sh`、`evidence_batch.sh`、`evidence_validate.sh` 和 `pin_baseline.sh` 共用加锁、动态端口、唯一 runDir、进程树清理与原子发布。bundle 绑定起止 revision/worktree、actual seed、实际 JVM、profile/capabilities、redacted config、日志和多级 checksum。clean worktree 使用 `git archive` 固定源码；dirty 或 fixture 自动降级为 `UNVERIFIED`。`reports/baselines/index.tsv` 是唯一新式 baseline 选择器，能力矩阵不会扫描目录挑最好结果。

## P0 Definition of Done

- 上述工单均有独立自动化测试；提交拆分留待用户明确授权 `cp`，当前未擅自 stage/commit；
- 无 P0/P1 未授权控制路径；
- stale response、cancel、restart 和 postcondition 套件全绿；
- capability matrix 能由 pinned manifest 自动生成；
- ROADMAP、安装文档和 README 不再与真实行为矛盾。
