# AIBot 项目文档

## 文档入口

- [运行模式与特权能力](OPERATING_PROFILES.md)：`strict_survival` / `operator` 的解析、迁移和 capability matrix。
- [测试与证据](TESTING_AND_EVIDENCE.md)：JUnit、GameTest、测试 harness、不可变 evidence bundle 与 baseline pin 流程。
- [能力矩阵](CAPABILITY_MATRIX.md)：当前显式证据与 legacy 诊断的生成结果。
- [P0 Runtime Hardening](P0_RUNTIME_HARDENING.md)：运行时加固阶段与验收项。
- [产品与工程路线图](../ROADMAP.md)：后续可靠性和产品规划。

## 当前工程口径

- 新安装默认 `strict_survival`；已有旧配置缺少 `profile` 时，才按 legacy compatibility 载入为 `operator` 并告警。
- 生产 jar 不包含 `/aibot test`、`/aibot verify` 或 GameTest 实现。测试命令只通过 `src/gametest` 与 `runHarnessServer` 提供。
- 本地 dirty-worktree run 可以用于诊断，但必须是 `UNVERIFIED`，不能作为发布结论。
- 新式 `VERIFIED` 能力基线只能由 `reports/baselines/index.tsv` 显式选择，不能按“最好”或“最新”自动挑选。

## 能力矩阵的来源

`CAPABILITY_MATRIX.md` 是生成文件，其输入有两层：

1. `reports/baselines/index.tsv` 指向不可变、校验通过的 `VERIFIED` bundle，是新式基线的唯一选择器；
2. `reports/capability_baseline_manifest.tsv` 是能力注册表与 legacy fallback。旧 TSV 缺少完整 commit、配置与 actual-seed provenance，因此保持 `UNVERIFIED`。

更新或检查矩阵：

```bash
bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md
bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md
```

不要手工修改生成结果。提交能力基线时，必须同时提交 immutable bundle、`reports/baselines/index.tsv` 与重新生成的矩阵；任何 SHA-256、场景、profile、mode 或 commit provenance 不一致都会使检查失败。
