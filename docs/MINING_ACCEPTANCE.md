# Mining First 能力契约

状态：M0 骨架已建立，64 钻石与 32 黑曜石均未认证。
最终能力 ID：`diamond_stack_64`、`obsidian_half_stack_32`。

> **2026-08 用户承诺口径扩展:整组 64 黑曜石(`obsidian_stack_64`)。**
> 32 契约保持原样(封存),64 是其超集,新场景已接入 verify/evidence 链:
> `obsidian_stack_64_controlled` / `obsidian_stack_64_prepared`(48,000 ticks)/
> `obsidian_stack_64_from_zero`(316,800 ticks = 32 版 240,000 + 增量 32 块 × 2,400
> 单块摊销;evidence target=`obsidian64`,wall-clock 上限 25,200 s = 15 TPS 最低速率下
> ~19% 余量)。审计阈值经 `MiningEvidenceAudit.begin(bot, OBSIDIAN, 64)` 参数化,
> 物理证据链(真实放水 ≥1、水成转化/挖掘/vanilla MINED/物理拾取 全部 ≥64)与 32 版同构。
> 发布门禁(20-seed ≥90% 等)对 64 同样适用,认证批次由用户择机启动。

本文件定义“能挖”具体意味着什么。源码中能接收 `count=64/32`、快速契约场景 PASS，或单个理想画布场景 PASS，都不能替代真实长跑验收。

## 1. 三层场景

| 层级 | 钻石场景 | 黑曜石场景 | 用途 | 是否可证明最终能力 |
|---|---|---|---|---|
| Controlled contract | `diamond_stack_64_controlled` | `obsidian_half_stack_32_controlled` | 秒级验证数量边界、typed postcondition 与 `MissionSpec` 往返 | 否 |
| Prepared execution | `diamond_stack_64_prepared` | `obsidian_half_stack_32_prepared` | 预置非目标装备与确定性资源，隔离连续采集、拾取、工具和 planner/task 接线 | 否 |
| From zero | `diamond_stack_64_from_zero` | `obsidian_half_stack_32_from_zero` | 自然地形、空背包、完整生存链 | 是，且必须满足多 seed 门禁 |

`controlled` 进入 PR CI 和普通 `mining` 回归；`prepared`、`from_zero` 是显式 opt-in，禁止进入 `/aibot verify all`。最终 capability manifest 只绑定 `from_zero` 场景。

## 2. 最终后置条件

### `diamond_stack_64`

- 背包内 `minecraft:diamond >= 64`；
- 开局空背包，不预放钻石矿，不给予钻石或目标掉落物；
- 自主完成木材、工作台、镐、铁、熔炼、补给、深潜、找矿、连续采集和拾取；
- 全程 survival、零死亡，禁止 hidden scan、forced pickup、emergency/manual teleport；
- sealed PASS 必须来自隔离的新建 from-zero world：其中不预置目标矿或目标掉落物。ledger 只认可 exact world-ore cell 上的 `observed diamond ore -> exact break -> 单次 native pickup credit` 事务；audited break 再与 `diamond_ore + deepslate_diamond_ore` 的 vanilla `MINED` 增量取较小值，native pickup credit 再与 vanilla `PICKED_UP diamond` 交叉约束，三项均须 `>=64`。当前 from-zero 链不使用 Fortune，单个 exact break 无论 inventory delta 多大都最多贡献 1 个 native pickup credit；最终背包数量不能替代这些来源证据；
- `PARTIAL`、超时、任务仍在运行、掉落未入包均不得计为 PASS。

### `obsidian_half_stack_32`

- 背包内 `minecraft:obsidian >= 32`；
- 开局空背包，不预放或给予黑曜石；
- 自主取得钻石镐、桶/水源并找到可安全操作的岩浆；
- 必须经过真实放水和 Minecraft 流体反应形成黑曜石。直接 `setBlockState(..., OBSIDIAN)`、缺水桶仍成型或伪造掉落均不符合最终验收；
- 全程 survival、零死亡，privileged capability 规则与钻石相同。
- sealed PASS 必须同时证明：真实 `water_bucket` 使用 `>=1`、观测到的 water-backed `lava -> obsidian` 转换 `>=32`、conversion-backed break 与 vanilla `MINED obsidian` 均 `>=32`、对应 physical pickup 与 vanilla `PICKED_UP obsidian` 均 `>=32`。

## 3. 发布门禁

固定公开 seed 集：

```text
3000,155361719,632510390,111,700,4040404,12345,54321,99999,246810,
105441651,1061665215,206232996,42414950,456718736,586434987,
633819475,715809951,222222,1234567
```

每项最终能力必须同时满足：

- 上述 20 seed 至少 `18/20 PASS (>=90%)`；
- 三个哨兵 seed `3000, 20260610, 777` 各重复 3 次，结果确定且不得出现重复计数；
- pause、cancel、restart-resume 分别在钻石 `1/32/63` 和黑曜石 `1/16/31` 进度点验证，控制语义 `100%`；
- 工具损坏、背包满、掉落物不可达、岩浆/水、敌对生物、跨 chunk 和资源暂不可见时，必须恢复或在硬时限内明确失败，不能无限空转；
- prepared 超时上限：钻石 `60000 ticks`、黑曜石 `24000 ticks`。from-zero 黑曜石 verifier 当前固定为 `240000 ticks`；from-zero 钻石不使用 magic literal，而由 live nominal plan 动态计算，理论 floor 为 `411200 ticks`。容量恢复按普通矿批次的每个可达 delivered 水位计费；辅助步骤则按真实 hard window 分类。当前空背包 live plan 宣布 `2120000 ticks`，GameTest 同时要求后续 nominal plan 不得超过 48 小时在 15 TPS 下可覆盖的 `2592000 ticks`。shell harness 的默认 wall-clock 上限按 target 区分：黑曜石 `18000 seconds`、钻石 `172800 seconds`；`evidence_run.sh` 必须从唯一的 `RUNNING timeout=N` 日志读取实际 tick 预算，并在 `verify_timeout_seconds * 15 < N` 时启动后立即 fail-closed。这里的 15 TPS 是长跑最低支持速率，tick 预算与 wall-clock 上限是两个不同合同，后续只能基于公开 evidence 收紧或经文档评审调整。

## 4. Evidence 资格

可用于能力判断的每个 run 必须由 `scripts/evidence_run.sh` / `scripts/evidence_batch.sh` 生成，并满足：

- clean worktree，开始与结束 `commit_sha` 一致；
- `profile=strict_survival`、`mode=deterministic`；
- `actual_seed_verified=yes`；
- `config_hash`、运行时版本、开始/结束时间和结果全部封存在 immutable bundle；
- `hiddenBlockScan=false, emergencyTeleport=false, forcedPickup=false, manualTeleport=false`；
- from-zero verifier 从场景开始到 terminal 每 tick 核对实际 GameMode，并以 vanilla `Stats.CUSTOM/DEATHS` 的场景基线增量证明 `death_delta=0`；任何非 Survival tick、死亡增量或任何 `CapabilityRuntime` 的 `allowed=true` 决策均不得通过；
- run manifest schema 2 封存 provenance schema 2 的 `mining_provenance_*`、GameMode/privilege/death 计数、两条物理来源链，以及 `verify_timeout_seconds` / `scenario_timeout_ticks`。非 fixture 的 Mining First PASS 必须存在唯一、逐字匹配 manifest 的 `RUNNING timeout=N`，且 wall-clock 上限至少覆盖 `ceil(N/15)` 秒；`evidence_validate.sh` 不论 verdict 为 PASS 或 FAIL，都要求唯一结构化终态事件并与 manifest 逐字段核对。旧 provenance schema 1、缺少 timeout 合同、事件缺失或字段缺失的 Mining First run 均拒绝封存/验证；
- 若 verifier summary 声称全 PASS、但物理 provenance verdict 为 FAIL，bundle 只能记为 `ERROR`，并保留原始 verifier pass counts/summary；不得伪装成 `FAIL + passed==total + PASS summary`。普通 `FAIL` 必须满足 `passed < total` 且退出码非零；
- 只有 `from_zero` 场景可绑定最终 capability ID；controlled/prepared evidence 只能用于诊断；
- 单个 PASS 不等于发布认证。20-seed 批次未达到门槛前，manifest 必须保持 `MISSING`，不得写成 `VERIFIED` 能力结论。
- `scripts/pin_baseline.sh` 的单-run pin 对两个 Mining First capability ID 硬拒绝；手工写入 `reports/baselines/index.tsv` 也会使能力矩阵校验失败。
- `scripts/mining_release_gate.sh` 只接受显式传入的两个 sealed batch，不扫描目录挑选最好结果；它还要求两个 batch 来自同一 commit、所有 run 使用同一 `config_hash` 与 Minecraft/Fabric/Java runtime。不同 GitHub runner 的 host kernel 完整信息仍保留在各 run manifest 中，但不作为跨 runner 相等条件。
- GitHub 分片通过 sealed shard descriptor 绑定 `target/role/seed/run_index` 与唯一 run `LOCKED` hash。聚合器按固定合同推导全部 shard ID，缺失、重复、额外 descriptor、重复引用同一 run、混用 commit/config/build/runtime 都会 fail-closed。

## 5. 执行入口

快速契约：

```bash
bash scripts/evidence_run.sh \
  --scenario mining_contract_suite \
  --seed 20260610 \
  --profile strict_survival
```

显式长跑：

```bash
AIBOT_MINING_SEEDS=3000,20260610,777 \
  bash scripts/mining_acceptance.sh prepared all

bash scripts/mining_acceptance.sh from_zero diamond
```

`from_zero` 不接受自定义 seed/runs：它自动运行固定 20-seed 主批次（每个 seed 1 次）和 `3000,20260610,777` 哨兵批次（每个 seed 3 次），再调用 `scripts/mining_release_gate.sh` 给出 `18/20 + 9/9` 的 `MINING_MULTI_SEED_GATE` verdict。该 verdict 只证明多 seed 与零死亡后置条件部分；pause/cancel/restart-resume 和边界场景仍须分别满足本文件第 3 节，不能据此单独认证完整能力。需要小样本定位问题时使用 `prepared`，不能把诊断样本表述成发布验收。

`AIBOT_MINING_TIMEOUT` 仍可显式覆盖默认 wall-clock 上限，但不能削弱场景合同：若覆盖值短于 verifier 实际宣布的 tick 预算，`evidence_run.sh` 会拒绝运行且不发布可被 release gate 接受的 bundle。当前 `2120000 ticks` 至少需要 `141334 seconds` 才能覆盖 15 TPS；默认钻石值与 harness 硬上限均为 `172800 seconds`，并覆盖 GameTest 锁定的 `2592000 ticks` plan ceiling。这些都不是对单次 run 实际耗时的承诺，提前达成完整后置条件仍会立即 PASS。

GitHub nightly 的手动入口提供同样的 `prepared` / `from_zero` 选择；常规 PR 不运行这些数小时场景。`MINING_MULTI_SEED_GATE=PASS` 仍不会自动改写 capability manifest；在其余发布门禁与聚合 evidence 的可提交 pin 格式落地前，最终能力继续 fail-closed 为 `MISSING`。

### GitHub Actions 分片执行

手动选择 `from_zero` 后，workflow 从 [mining_acceptance_contract.sh](../scripts/lib/mining_acceptance_contract.sh) 生成固定 58-job matrix：两项能力各 20 个 primary run 加 9 个 sentinel run。每个 job 最长独立运行 350 分钟，避免 29 个 run 串行挤进 GitHub-hosted runner 的 6 小时上限。该 350 分钟是现有 GitHub runner 的执行 envelope，不会覆盖当前所有可能的动态钻石 hard timeout；钻石若在此前完整 PASS，证据仍有效，否则 job 可能先于 verifier 的 typed timeout 被平台终止，缺失 shard 会使聚合 fail-closed。解决这一限制需要后续收紧并重新证明 mission budget，或调整 runner 架构；本合同不把 350 分钟误写成完整场景上限。

每个 shard 使用 [mining_evidence_shard.sh](../scripts/mining_evidence_shard.sh) 生成一个 sealed run bundle 和一个 sealed descriptor。最终两个 target-specific aggregate job 只下载自身 29 个显式命名 artifact，并由 [mining_evidence_aggregate.sh](../scripts/mining_evidence_aggregate.sh) 完成以下检查：

1. 预期 29 个 shard ID 与下载集合完全相等；
2. descriptor checksum/`LOCKED`、run checksum/`LOCKED` 全部有效；
3. 每个 seed/run-index 唯一，且同一 run bundle 不得被重复计数；
4. scenario、actual seed、strict profile、无特权 capability、commit、config、build/runtime 全部匹配；
5. 每个计为 PASS 的 run 都重新验证 Survival/privilege 边界及对应 target 的物理来源计数；
6. 重建现有 `evidence_validate.sh` 可独立验证的 primary/sentinel canonical batch；
7. 最后只给出 `MINING_MULTI_SEED_GATE`，并明确 `FULL_CAPABILITY_CERTIFIED=no`。

artifact 名称和下载 pattern 同时绑定 `github.run_id` 与 `github.run_attempt`，禁止把不同重跑批次混在一次聚合中。若某个 shard 需要重跑，应重新运行整个 workflow，而不是仅重跑失败 job 后复用上一 attempt 的成功 artifact。
