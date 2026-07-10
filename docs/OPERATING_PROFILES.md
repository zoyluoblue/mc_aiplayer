# AIBot 运行模式

AIBot 有两种运行模式：默认的 `strict_survival` 与兼容用途的 `operator`。模式控制的是特权边界，不改变 Goal/Task 的基本架构。

## 配置

推荐在 `aibot.json` 中显式写出 profile：

```json
{
  "profile": "strict_survival",
  "operatorCapabilities": {
    "hiddenBlockScan": false,
    "emergencyTeleport": false,
    "forcedPickup": false,
    "manualTeleport": false
  }
}
```

也可以用环境变量为当前进程覆盖文件：

```bash
AIBOT_PROFILE=strict_survival ./gradlew runServer
```

合法值只有 `strict_survival` 和 `operator`。配置在启动时解析；更改文件或环境变量后应重启服务端。

## 解析与迁移规则

| 输入状态 | 最终 profile | 行为 |
|---|---|---|
| 新安装、尚无配置文件 | `strict_survival` | 写出带显式 strict profile 的默认配置。 |
| 已有文件，profile 合法 | 文件值 | 正常加载。 |
| 已有 legacy 文件，完全缺少 `profile` | `operator` | 仅用于向后兼容，并记录 `operating_profile_legacy_compatibility` 警告。应尽快显式迁移。 |
| 文件中的 profile 类型或值非法 | `strict_survival` | Fail closed，并记录 `operating_profile_invalid`。 |
| `AIBOT_PROFILE` 合法 | 环境变量值 | 覆盖文件或 legacy 解析结果。 |
| `AIBOT_PROFILE` 非空但非法 | `strict_survival` | Fail closed，不回退到文件中的 operator，并记录 `operating_profile_environment_invalid`。 |
| 配置无法解析 | `strict_survival`，或合法环境覆盖值 | 记录配置读取错误；绝不因解析失败扩大权限。 |

“旧配置缺字段时兼容为 operator”只适用于**已经存在且可解析、并且确实没有 `profile` 字段**的文件。`null`、错误类型和未知字符串都不属于 legacy missing，不能获得 operator 权限。

## Capability matrix

| Capability | `strict_survival` | `operator` 默认 | `operator` 显式关闭后 |
|---|---:|---:|---:|
| `hiddenBlockScan` | 拒绝 | 允许 | 拒绝 |
| `emergencyTeleport` | 拒绝 | 允许 | 拒绝 |
| `forcedPickup` | 拒绝 | 允许 | 拒绝 |
| `manualTeleport` | 拒绝 | 允许 | 拒绝 |

operator 的四个默认值为 `true`，用于保持旧版本行为；它们是四个独立开关，不是一个总开关。例如，只允许手动传送：

```json
{
  "profile": "operator",
  "operatorCapabilities": {
    "hiddenBlockScan": false,
    "emergencyTeleport": false,
    "forcedPickup": false,
    "manualTeleport": true
  }
}
```

### 四项能力的含义

- `hiddenBlockScan`：允许绕过 strict 的可观察性过滤进行资源探测。strict 模式下，方块必须在配置半径内、暴露且命中视线 raycast；实体必须在半径内且可见。
- `emergencyTeleport`：允许危险处理或导航兜底执行长距离紧急传送。strict 模式下，相关路径会尝试普通动作，无法安全处理时明确失败。
- `forcedPickup`：允许直接把附近掉落物转移到 Bot 背包。strict 模式只使用正常的世界拾取过程。
- `manualTeleport`：允许通过控制面板/网络动作发起手动传送。未生效时，UI 按钮会被禁用，服务端仍会再次拒绝请求。

## Strict survival 语义

`strict_survival` 不只是隐藏 UI 按钮。服务端 capability gate 会在动作发生前再次判断：

- 资源与实体扫描先经过近距离、暴露和视线过滤；
- 禁止紧急传送、手动传送与强制拾取；
- 死亡按生命周期在世界出生点恢复，不用传送能力制造原地复活；
- 不通过强制跳夜或远程 world mutation 绕开生存约束。

服务器自身的 Bot 创建、持久化恢复和正常死亡生命周期不是玩家可调用的 operator capability；它们属于静态生命周期边界。

## 可观测性与审计

启动时会记录 `operating_profile_resolved`，包含 profile 来源、配置开关和 effective capabilities；具体迁移/非法配置 warning 由相邻的独立配置事件记录。运行时 capability gate 会输出节流后的结构化 `capability_decision` 记录，说明 capability、profile、allow/deny 与 reason。

游戏内 snapshot 和 Bob 控制面板展示 `operatingProfile` 与 `effectiveCapabilities`。不要只依据配置文件判断权限；排查问题时应以启动日志和 UI 中的最终生效值为准。

## 验证

测试专用 harness 同时覆盖 strict 与 operator：

```bash
bash scripts/evidence_run.sh \
  --scenario capability_profile+runtime_control_suite \
  --profile strict_survival

bash scripts/evidence_run.sh \
  --scenario capability_profile+runtime_control_suite \
  --profile operator \
  --operator-capabilities all
```

operator 也可传入逗号分隔的子集，例如 `--operator-capabilities manualTeleport`；`none` 表示四项全部关闭。strict 不接受启用 operator capability 的参数。

当前工作树留存的 strict/operator 本地诊断均为 `7/7 PASS`，但因为运行时工作树不干净，bundle 正确标记为 `UNVERIFIED`。该结果证明 harness 与策略在这次本地状态下通过，不等同于已发布 commit 的能力认证。
