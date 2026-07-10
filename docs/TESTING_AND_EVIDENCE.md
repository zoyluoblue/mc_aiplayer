# AIBot 测试与证据

AIBot 将“代码通过测试”“场景在本机通过”和“可作为发布依据”分开处理。场景结果为 `PASS` 不代表 evidence state 一定是 `VERIFIED`。

## 测试分层

| 层级 | 入口 | 当前规模 | 作用 |
|---|---|---:|---|
| JUnit | `./gradlew test` | 19 个测试类、68 个测试 | 验证纯 Java 策略、权限、Goal predicate/result、序列化和原子存储边界。 |
| Fabric GameTest | `./gradlew runGameTest` | 3 个测试 | 在真实 Minecraft world context 中验证确定性 smoke case。 |
| 交互 harness | `./gradlew runHarnessServer` | test-only 命令 | 提供 `/aibot test`、`/aibot verify` 与 restart probe 命令。 |
| 单次 evidence | `scripts/evidence_run.sh` | 一个 scenario/seed/profile | 启动隔离服务端并封存不可变 run bundle。 |
| 批量 evidence | `scripts/evidence_batch.sh` | 显式 seed/run matrix | 汇总多个独立 bundle，不自动选择基线。 |
| 两 JVM 重启 | `scripts/persistence_restart_test.sh` | 两个连续服务端进程 | 验证非默认 checkpoint、Mission/queue/pause 精确恢复、stale Job lease 重开，以及 resume 后达到 `COMPLETED 4/4`。 |

当前源码测试清单是 19 个 JUnit 类、68 个测试和 3 个 GameTest。当前本地诊断中，strict/operator 的 capability + runtime-control suite 均为 `7/7 PASS`；两 JVM persistence probe 精确恢复 checkpoint map，并在 resume 后得到原 Mission 的 `COMPLETED 4/4`。这些数字描述本次工作树的验证，不替代 clean-commit evidence gate。

## 生产边界

`AIBotTestSubcommand`、`AIBotVerifySubcommand`、GameTest 类和 restart harness 都位于 `src/gametest`。生产 `src/main` 不注册 `/aibot test` 或 `/aibot verify`，生产 jar 也不应包含这些类。

构建后可运行带 artifact 检查的静态门禁：

```bash
./gradlew clean build
CI_STATIC_CHECK_ARTIFACTS=1 bash scripts/ci_static_check.sh
```

该检查会扫描 `build/libs/*.jar`，发现 GameTest 或 verification command 泄漏时失败。

## 单次隔离 evidence

最小命令：

```bash
bash scripts/evidence_run.sh \
  --scenario capability_profile+runtime_control_suite \
  --seed 20260610 \
  --profile strict_survival
```

常用参数：

- `--profile strict_survival|operator`：默认为 strict；
- `--operator-capabilities all|none|<csv>`：只对 operator 生效；
- `--seed <integer>`：请求的 world seed；
- `--timeout` 与 `--startup-timeout`：scenario 与启动超时；
- `--mode deterministic`：默认，无 LLM 费用；
- `--mode llm_story --with-llm`：显式启用 LLM，要求 `DEEPSEEK_API_KEY`；
- `--fixture-log <file>`：只测试封存/解析结构，永远不能成为 `VERIFIED`。

脚本使用独立 run directory、动态本地端口、进程锁和清理 trap，不执行全局 `gradlew --stop`，也不会删除共享 `run/`。已有 evidence 目录不会被覆盖。

## Bundle 结构

成功封存后，脚本输出 `EVIDENCE_DIR=...`。单次 run 位于 `artifacts/evidence/<run-id>/`，包含：

```text
manifest.tsv
result.tsv
server.log
effective-config.redacted.json
checksums.sha256
LOCKED
```

- `manifest.tsv`：commit、工作树起止状态、runtime、profile、effective capability、配置 hash、requested/actual seed、隔离目录、结果与 evidence state；
- `result.tsv`：场景结果与 `/aibot verify` summary；
- `effective-config.redacted.json`：实际测试配置的脱敏快照；
- `checksums.sha256` 与 `LOCKED`：封存后的完整性边界；
- `server.log`：已执行 credential pattern 检查与必要脱敏的服务端日志。

Bundle 一旦发布即视为 immutable。不要在目录内手工修结果或 metadata；需要修复时重新运行。

## `PASS` 与 `VERIFIED`

`result=PASS` 只表示 scenario 的断言通过。要获得 `evidence_state=VERIFIED`，还必须同时满足 provenance 条件，包括：

- 工作树在开始和结束时均为 clean；
- 起止 commit 相同且可用；
- 从该 commit 的 `git archive` 快照执行，而不是不稳定的 live worktree；
- 服务端成功启动并正常退出；
- 请求 seed 与服务端回读的 actual seed 一致；
- isolated working directory 与 Java runtime 可核验；
- revision/build/config metadata 完整；
- 不是 fixture input；
- 封存日志中没有需要替换的 secret。

任一条件不满足时，scenario 仍可显示 `PASS`，但 evidence 会成为 `UNVERIFIED`，并在 `verification_reason` 中列出原因。当前仓库内的本地 strict/operator `7/7` run 就属于这种正确降级：工作树为 dirty，因此不能进入发布基线。

## 验证 bundle

结构与 checksum 验证：

```bash
bash scripts/evidence_validate.sh artifacts/evidence/<run-id>
```

发布门禁验证：

```bash
bash scripts/evidence_validate.sh \
  --require-verified artifacts/evidence/<run-id>
```

validator 不 source bundle 中的 metadata，并拒绝路径穿越、symlink、staging 目录、重复 manifest key、未知 schema、checksum 错配及 provenance 矛盾。可运行其安全自测：

```bash
bash scripts/evidence_validate.sh --self-test
```

## 批量运行

```bash
bash scripts/evidence_batch.sh \
  --scenario real_food \
  --seeds 12345,246810,632510390 \
  --runs 2 \
  --profile strict_survival
```

默认情况下，batch 要求每个 child run 都是 `VERIFIED`。`--allow-unverified` 只适合本地诊断，不得用于 release gate。batch 输出到 `artifacts/evidence-batches/<batch-id>/`，只引用各 child bundle，不会替用户自动挑选或 pin 结果。

## 显式 pin 基线

新式 baseline 的唯一选择器是 `reports/baselines/index.tsv`。Pin 操作必须显式提供 capability ID 与一个已通过 `--require-verified` 的 run：

```bash
bash scripts/pin_baseline.sh \
  food_from_zero \
  artifacts/evidence/<run-id>
```

Pin 流程还会验证：

- capability ID 在 `reports/capability_baseline_manifest.tsv` 中恰好注册一次；
- run scenario 与该 capability 的注册 scenario 一致；
- profile 为 `strict_survival`，mode 为 `deterministic`；
- 默认只接受 `PASS`；
- immutable bundle 与 `LOCKED` hash 一致。

脚本把 bundle 复制到 `reports/baselines/<capability-id>/<run-id>/`，再原子更新 `reports/baselines/index.tsv`。旧 run 不会因重新 pin 而删除。

`reports/capability_baseline_manifest.tsv` 仍保存 capability 注册信息和 legacy fallback。它不是“自动选最好报告”的索引；没有新式 pin 时，生成的能力矩阵会显示 legacy report，并保持 `UNVERIFIED`。

更新矩阵：

```bash
bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md
bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md
```

## CI 约束

- PR CI 与 nightly deterministic workflow 不接收 `DEEPSEEK_API_KEY`；
- nightly 覆盖 `strict_survival` 与 `operator` profile；
- LLM story evidence 只能通过手动 workflow 触发，并要求明确确认计费；
- workflow 在失败时仍上传诊断 artifact；
- release claim 只能引用校验通过、显式 pin 的 `VERIFIED` bundle。
