# 开发记录 · 稳定挖 64 钻石 + 64 黑曜石(diamond64 / obsidian64)

> 本文件是该阶段的**滚动开发记录**:每一阶段做了什么、验证结果、以及**待解决问题清单**。
> 设计蓝图见 `PLAN.md`;历史阶段见 `PLAN_HISTORY*.md` 与 `M*_VALIDATION_REPORT.md`。
> 目标(用户确认):strict_survival 下,端到端稳定完成 MineOre[diamond_ore ×64] 与 obsidian ×64;
> 允许按需扩展觅食、照明、战斗能力。LLM = `deepseek-v4-flash`。

---

## 阶段 0 · 基线修复与模型切换(2026-08-07)

**提交**:`d90352e6`(上批 hunt/route-contract 硬化)、`eed0adf2`(本批)

**完成**:
- `PathExecutor.isExactConstrainedRoute` 抽出为共享精确性契约;`HuntTask` 委托之,
  单元测试不再触发需要 registry bootstrap 的 `HuntTask.<clinit>`。
- `MiningCheckpointMissionGameTests` legacy checkpoint 模拟补移除 `snap_dimension`
  (孤键曾令 restore 正确地 fail-closed:`mission_restore_invalid_replan_snapshot`)。
- DeepSeek 默认模型 `deepseek-chat` → `deepseek-v4-flash`:
  - 显式发送 `thinking {enabled, reasoning_effort=low}`(V4 默认 high 且推理与正文共享
    `max_tokens`,隐式默认会饿死 tool call);`maxTokens` 2048 → 8192。
  - 响应解析记录 `reasoning_tokens`;`finish_reason=length` 且无任何可执行输出时告警
    `api_truncated_before_output`。
  - 用户配置缺 `reasoningEffort` 时不再 NPE(fail 回默认值)。

**验证**:336 单测全绿;576 gametest 全绿(`./gradlew runGameTest`)。

---

## 阶段 1 · P1 拾取恢复滞留修复 + 全链路调研(2026-08-07)

**完成**:
- **P1 根因确认并修复**(两个证据运行):
  - 形态 A(实锤):掉落物移位后未再被观察,`lastSeen` 停留旧格;`approachKnownPickupCell`
    解析的 stand 恰为当前格 → 同格 nudge 每 tick 返回 true("正在追")→ 恢复循环在空柱下
    原地滞留 200 tick 超时。任务失败后 navsafe 偶然挪动一格,原版碰撞立即完成拾取。
  - 形态 B(原始 flake):身体与石墩分数重叠 → 寻路起点校验静默失败 + 20 tick 冷却,无升级无日志。
  - 修复(`OreDigTask`):滞留检测(`updatePickupRecoveryStall`,移动所有权/换格重置)+
    30 tick 阈值升级为**观察扫描**(`startPickupObservationSweepStep`,镜像 Hunt 的
    sweep:绕 last-seen 环形可观察站位走精确无挖掘路线)+ 滞留限频日志
    `ore_dig_pickup_recovery_stalled`。`HarvestCore.startExactPickupPath` 提为 public 共享。
  - 新增确定性 gametest `pedestalLandedDropIsPhysicallyRecovered`(拦截掉落钉到高台,消 RNG);
    修复前 1/3 失败,修复后连续 5 轮全套 577 用例全绿。
- **flaky 测试修复**:`oldNearbyRawDropCannotPoisonFreshKillTransaction` — offset 竞技场仅靠
  假玩家 chunk 票据维持加载,瞬时卸载/重载使闭包持有的实体引用 `isAlive()=false`。
  改为实体延后生成(tick 40)+ 断言时 `getEntitiesByClass` 现查(仓库既有惯例)。
- **全链路调研完成**:8 个并行 agent 审查 7 子系统(148 万 tokens),产出
  [FINDINGS_DIAMOND64.md](FINDINGS_DIAMOND64.md):**11 blocker / 19 major / 20 minor**,
  含预算模型与 10 个需实跑回答的开放问题。

**关键结论(节选)**:
- F2:每批次钻石配额无盈余结转 + 每批次仅 2 个资源 epoch,结构性把任务成功率压到
  ~17–66%(与 bug 无关的数学上限)—— 最高优先级。
- F3/F7:下降途中落点漂移、镐耗尽均直接判 mission 终态(应降级为可恢复)。
- F4/F5/F6:黑曜石链三大死锁(倒水活锁、resumeFirst 排序、食物 8 单位写死)。
- F29:验收体系只有 obsidian 32 场景,64 需新场景与预算标定(即 P3)。

---

## 待解决问题(滚动清单)

| ID | 严重度 | 问题 | 状态 |
|---|---|---|---|
| P1 | major | 拾取恢复滞留活锁(高台掉落 / 同格 nudge 假进展 / 静默 NO_START)| **已修**(阶段 1) |
| F1-F50 | 见报告 | 全链路调研发现清单,详见 [FINDINGS_DIAMOND64.md](FINDINGS_DIAMOND64.md);修复进度在该文件逐条标注 | 攻坚中 |
| P2 | 待评估 | diamond64/obsidian64 全链路稳定性缺口 — 七子系统并行调研进行中(OreDig 恢复、Planner 批次、Executor 重规划预算、Obsidian 岩浆链、Descend 往返、生存中断恢复、预算/证据体系),产出后按影响排序逐项立项。 | 调研中 |
| P3 | major | **黑曜石目标 32 → 64**:现有验收契约(`docs/MINING_ACCEPTANCE.md`)只定义了 `obsidian_half_stack_32`;用户目标是 64。需扩展 Goal/预算/验收场景到 64,并核对岩浆源池容量假设(单湖是否稳定供 64 块)。 | 待立项 |
| P4 | note | **认证长跑成本**:from-zero 钻石 live plan 已声明 2,120,000 ticks(15 TPS ≈ 39 小时/run),20-seed 门禁是天级算力。本阶段交付"能力与稳定性 + 可复验入口",sealed 批量认证由用户择机启动(`scripts/evidence_batch.sh`)。 | 已知约束 |

---

## 里程碑规划(随调研结果细化)

- **S1 修复已知不稳定点**:P1 及调研发现的 blocker 级缺陷。
- **S2 挖矿链路加固**:64 目标全程的批次/预算/checkpoint 一致性。
- **S3 黑曜石链路加固**:岩浆场景的掉落保全与安全恢复。
- **S4 生存扩展**:按需扩展觅食(地下食物经济)、照明(火把节奏)、战斗(隧道遭遇)。
- **S5 端到端证据**:evidence run 连续 N 次 diamond64+obsidian64 通过率验收。
