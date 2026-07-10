# AIBot 能力矩阵

此文件由 `reports/capability_baseline_manifest.tsv`、`reports/baselines/index.tsv` 通过 `scripts/capability_matrix.sh` 生成。

重要：`reports/baselines/index.tsv` 是 VERIFIED 证据的唯一选择器，优先于 legacy manifest；生成器不会自动选择“最好”或“最新”的结果。没有新式 pinned bundle 时，只展示 manifest 固化的 legacy 汇总快照。manifest 保留历史源文件名与 SHA-256 供追溯，但生成器不会读取或依赖这些本地报告；legacy 数据缺少 tested revision、配置 hash 和 actual seed，因此始终为 `UNVERIFIED`。

| ID | 能力 | 场景 | 结果 | 成熟度 | 证据 | 可信度 | 测试版本 | 日期 | 模式 | Fixture | 备注 |
|---|---|---|---:|---|---|---|---|---|---|---|---|
| `wood_from_zero` | 自然采木 | `real_wood` | 4/4 (100%; 4 seeds; FAIL 0, ERR 0) | `DEMONSTRATED` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-06-24 | 未知（legacy） | 自然地形、空背包、白天、地表化、零死亡 | 固定小批次全绿，但报告未绑定代码和配置 |
| `food_from_zero` | 获取熟食 | `real_food` | 8/10 (80%; 10 seeds; FAIL 2, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-06-18 | 未知（legacy） | 自然地形、空背包、白天、地表化、四份熟食、零死亡 | 较好批次 8/10，不能代表当前 HEAD；失败阶段 other=2 |
| `wheat_to_bread` | 种麦做面包 | `real_wheat` | 1/5 (20%; 5 seeds; FAIL 4, ERR 0) | `UNSTABLE` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-06-24 | 未知（legacy） | 自然地形、空背包、randomTickSpeed=40、两个面包、零死亡 | 生长时间被加速，仍只有少量成功；失败阶段 other=3, wood_gather=1 |
| `iron_bulk_100` | 百量级铁矿 | `real_iron_bulk` | 2/12 (17%; 6 seeds; FAIL 6, ERR 4) | `UNSTABLE` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-07-03 | 未知（legacy） | 预置五把铁镐、深矿装备和 64 火把，目标 100 raw iron | 隔离长跑挖矿；ERR 计入总分母；失败阶段 other=6, server_err=4 |
| `diamond_from_zero` | 从零挖钻石 | `real_diamond` | 6/10 (60%; 10 seeds; FAIL 4, ERR 0) | `EXPERIMENTAL` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-07-06 | 未知（legacy） | 自然地形、空背包、白天、地表化、一颗钻石、零死亡 | 完整深层工具链，尚未达到发布门槛；失败阶段 other=2, timeout=1, wood_gather=1 |
| `iron_armor_from_zero` | 从零铁甲与剑 | `real_armor` | 10/12 (83%; 6 seeds; FAIL 2, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-07-05 | 未知（legacy） | 自然地形、空背包、整套铁甲加铁剑、零死亡 | 当前本地报告中最强的长链之一；失败阶段 timeout=2 |
| `small_hut_core` | 真实地形建房 | `real_build` | 7/10 (70%; 10 seeds; FAIL 3, ERR 0) | `ALPHA` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-06-24 | 未知（legacy） | 预置多树种木板和整地材料，只测选址、整地和落成 | 断言为附近至少 80 块木板，不代表结构完整；失败阶段 other=3 |
| `navigate_120` | 长距离导航 | `real_nav_far` | 0/4 (0%; 4 seeds; FAIL 4, ERR 0) | `BLOCKED` | `UNVERIFIED` (legacy snapshot) | `LOW` | unknown; seed未回读 | 2026-06-24 | 未知（legacy） | 自然地形长距离移动 | 样本很小，但当前 pinned batch 全红；失败阶段 aborted=2, other=1, timeout=1 |
| `combat_multiseed` | 综合战斗 | `combat` | — | `UNKNOWN` | `MISSING` | `NONE` | 无测试记录 | — | — | 尚无显式 pin 的多 seed 战斗报告 | 存在确定性 combat 场景和战斗实现，但没有可比较的真实基线 |

## 判定规则

- `DEMONSTRATED`：在有限 pinned batch 中表现稳定，但尚未达到 release gate。
- `ALPHA`：主链已跑通，仍有显著环境失败。
- `EXPERIMENTAL`：能成功，但成功率或过程稳定性不足。
- `UNSTABLE/BLOCKED`：不能作为用户承诺。
- `UNVERIFIED`：manifest 仅保留 legacy 汇总、历史源文件名与 hash，缺少可验证的唯一代码、配置或 actual seed 证据。
- `MISSING`：尚无明确 pin 的可比较批次。

## v0.1 Release Gate

- 四条黄金链各至少 20 个固定公开 seed，成功率 `>= 90%`；
- `cancel/replace/restart-resume` 为 `100%`；
- `PARTIAL` 不得计入 PASS；
- 所有计入门禁的 run 必须记录 `commit_sha/config_hash/actual_seed/mode`；
- `UNVERIFIED` 和 `MISSING` 永远不计入发布通过率。

## 更新方式

1. 运行不可变、带 metadata 的多 seed 测试；
2. 用 `scripts/pin_baseline.sh` 将 VERIFIED run 显式绑定到 capability ID；
3. 将 immutable bundle 与 `reports/baselines/index.tsv` 一起纳入版本控制；
4. 运行 `bash scripts/capability_matrix.sh --output docs/CAPABILITY_MATRIX.md`；
5. P0-07b CI 建立后运行 `bash scripts/capability_matrix.sh --check docs/CAPABILITY_MATRIX.md`。
