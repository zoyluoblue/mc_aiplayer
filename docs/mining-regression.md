# 挖矿回归日志模板

本文件记录金锭链路相关的挖矿失败模式、期望行为和对应测试。后续分析 `run/log` 时，优先按这里的字段判断是否是已知回归。

统一检索方式：先用 `[taskId=...]` 过滤同一任务，再按 `make_item`、`mining`、`planning` 等日志分类查看阶段状态、候选拒绝和恢复动作。

## 近期失败样本索引

这些样本来自最近的 `run/log` 分析，用于后续 phase 直接复现路线、暴露、前进、容量和恢复问题。记录中只保留任务事实和相对日志分类，不写入 API key 或本机绝对路径。

| 样本编号 | 日志来源 | 目标/阶段 | 最小输入 | 失败信号 | 期望修复方向 |
| --- | --- | --- | --- | --- | --- |
| `R-2026-05-15-03492d17` | `make_item`、`action`、`planning` | `minecraft:gold_ingot x2` 的第 1/13 步，采集 `minecraft:oak_log x1` | AI 背包为空，从木材起步；附近扩展扫描发现 `53` 个原木候选 | 只检查 `16` 个候选路径且均为 `no_reachable_stand`，`noReachableScans` 达到阈值后直接终止，未耗尽树木探索点 | 扩大树木路径候选验证数量；`no_reachable_stand` 只触发继续探索和重扫，探索点耗尽前不终止制作链 |
| `R-2026-05-15-tree-structure` | `make_item` | 金锭、铁工具、石工具等长链路的第一个木材 step | 附近有多种树、树叶或草地站位；通用目标仍显示为 `minecraft:oak_log` | 旧逻辑按单个原木评分，可能优先检查树冠、高差大或不像树的孤立原木；脚下有草、花等无碰撞植物时也可能误判没有站位 | 树木候选增加真实树结构评分：有树叶、连接原木多、树干底部优先；树木站位允许无碰撞植物；通用木材扩展到绯红菌柄和诡异菌柄 |
| `R-2026-05-15-ff6adb24` | `make_item`、`action` | `minecraft:gold_ingot x2` 的铁锭前置链，制作 `minecraft:furnace x1` | AI 已完成树木、圆石、石镐和 `minecraft:raw_iron x3`，背包已有 `minecraft:crafting_table x1`、`minecraft:cobblestone x43`、`minecraft:raw_iron x3` | 在地下矿道进入 `craft_station minecraft:furnace x1` 后失败：`找不到可放置工作台的位置`；不是矿物目标或配方失败，而是附近没有现成可放置空气格 | 工作站放置搜索扩大到附近有效支撑格；若没有空气格，先按生存规则清理有支撑、可触达的普通地形方块形成工作台/熔炉放置空间，排除箱子、木桶、已有工作站和带方块实体的容器，并记录清理失败原因 |
| `R-2026-05-15-344d3a06` | `make_item`、`mining`、`action` | `minecraft:gold_ingot x2` 的第 12/13 步，采集 `minecraft:raw_gold x2` | AI 已完成木材、工作台、石镐、铁矿、熔炉、铁镐，背包已有 `minecraft:iron_pickaxe x1`，正在从 Y=115 向金矿目标 `-506,20,-297` 下探 | 旧台阶站位最初是相邻下一阶，但实体位移后当前位置变成 `-442,112,-301`，旧站位仍是 `-442,109,-302`，垂直差扩大到 3 格；代码继续按 `prospect_stair_step_move` 相邻一步处理，最终累计 `adjacent_mining_step_stuck` 终止 | 台阶移动目标必须每 tick 按当前位置重新确认只能是一格水平/下降站位；目标过期时清空旧目标并按当前位置重建下一阶；相邻台阶进入超时只拒绝当前站位并重规划，不再直接把整条挖矿链判为终止失败 |
| `R-2026-05-15-a124d328` | `make_item`、`mining`、`action` | `minecraft:gold_ingot x2` 的第 7/13 步，采集 `minecraft:raw_iron x3` | AI 已完成木材、工作台、木镐、圆石和石镐，从 Y=81 向铁矿目标 `-3,66,-7` 下探，途中遇到大量砂砾和沙子 | 四个阶梯方向都被 `future_stand_invalid:support=minecraft:gravel` 或 `head_blocked=minecraft:gravel` 拒绝；`canStandAt` 允许砂砾支撑，但阶梯预判和通道可挖规则把砂砾/沙子排除，导致没有下一阶目标并终止 `no_downward_target` | 挖矿通道规则统一：砂砾、沙子、红沙可以作为可破坏的头部/脚部通道方块，也可以作为普通下探站位支撑；真正阻断下探的硬条件保留为空气缺支撑、水、岩浆、基岩、工具无法采集、世界边界或区块未加载 |
| `R-2026-05-15-c99f58d0` | `make_item`、`mining`、`action` | `minecraft:gold_ingot x2` 的铁镐前置链，第 7/13 步采集 `minecraft:raw_iron x3` | AI 在 `6,67,8`，铁矿 `8,66,8`，已挖开 `7,66,8` 作为水平暴露邻格，背包已有圆石和石镐 | 路线又把 `7,66,8` 当作下一站位 `7,67,8` 的脚下支撑补成圆石，导致刚暴露的矿重新变成 `no_air_neighbor`，随后 `prospect_timeout` | 支撑放置前必须检测是否会封住已打开的水平矿物暴露格；若会封住，应进入该暴露格作为站位，而不是放置支撑 |
| `R-2026-05-15-155e48be` | `make_item`、`mining`、`action` | `minecraft:gold_ingot x2` 的第 12/13 步，采集 `minecraft:raw_gold x2` | AI 已完成铁镐、熔炉和燃料，正在从 Y=50 向金矿目标层下探 | 通道判定 `passage_ready`，但目标站位 `-10,49,-25` 反复 `adjacent_mining_step_stuck:prospect_stair_step_move`，实体没有稳定走入下一阶 | 相邻下降 step 使用专门移动策略：水平贴近下一阶中心，靠近后给向下速度，并用水平距离 + 落点高度作为进展和到达判定 |
| `R-2026-05-15-32c1096a` | `make_item`、`mining`、`action` | `minecraft:gold_ingot x2` 的第 7/13 步，采集 `minecraft:raw_iron x3` | AI 已有工作台、石镐、`raw_iron=2`，铁矿线索在附近矿道内 | 大量候选为 `no_air_neighbor`；路线目标和暴露面多次 `blocked_line_of_sight`，遮挡清理后仍被 `prospect_timeout` 判为长期无进展 | 直接挖矿目标必须同时校验距离和视线；无清晰视线时继续两格高通道推进；成功清理视线遮挡也计入有效挖矿进展 |
| `R-2026-05-15-f7c1f684` | `make_item`、`action`、`survival` | `minecraft:gold_ingot x2` 的第 6/13 步，制作石镐 | AI 已有 `minecraft:cobblestone x3`、`minecraft:stick x2`、木镐和已放置过的工作台 | 制作链重建后没有复用附近工作台，重新进入 `gather_tree minecraft:oak_log x1`；树木扫描选中可疑站位后又反复 `stand_unreachable` | 非消耗工作站先复用观察层真实可达方块；工作站搜索半径与观察层一致；路径判断必须要求 `Path.canReach()`，避免伪可达树木站位 |
| `R-2026-05-15-16bf9b52` | `make_item`、`mining`、`action`、`survival` | `minecraft:gold_ingot x2` 的第 5/13 步，采集 `minecraft:cobblestone x3` | AI 已完成木镐，在 `70,66,-1` 附近尝试下探，目标层低于当前位置 | 反复 `move_to_stone_descent_start`，破坏 `70,65,-1` 被 `blocked_line_of_sight blocker=70,66,-1` 拒绝，最终 `stone_no_progress` | 指定目标层下探时锁定当前阶梯会话，先清前方脚部和头部空间，再挖斜下脚部；清理覆盖层计为下探进展 |
| `R-2026-05-14-e2ad0a9a` | `make_item`、`mining`、`action` | `minecraft:gold_ingot x2` 的第 7/13 步，采集 `minecraft:raw_iron x1` | AI 在 `-780,59,-1`，矿点 `-780,58,0`，暴露点 `-780,59,0` | 近场矿点被反复拒绝，`target_changed:minecraft:iron_ore`、`no_air_neighbor`、`no_path` 持续增长，最终 `prospect_timeout` | 当前站位已贴近矿点时进入 `EXPOSE_OR_MINE`，不再把相邻暴露点当成不可达路线 |
| `R-2026-05-14-c852ddd1` | `mining`、`survival` | `minecraft:iron_ingot x2`，最终成功，但过程多次破坏失败 | 石头目标 `-790,74,10`，铁矿目标 `-774,68,-2`、`-773,67,-3` | 多次 `blocked_line_of_sight`，阻挡方块位于目标侧面或头部通道 | 两格通道清理要先处理脚部/头部遮挡，再执行破坏；该样本作为可恢复遮挡回归 |
| `R-2026-05-13-ae778bec` | `mining`、`action` | `minecraft:iron_ingot x2` 的探矿路线 | AI 在 `14,60,-30`，矿点 `-65,59,-24`，路线点 `-65,60,-24` | 长距离 `TUNNEL` 阶段被 `blocked_line_of_sight`、`occlusion_clear_failed`、`no_air_neighbor`、`no_path` 组合阻断 | 远距离矿点要支持路线重绑定、通道清理失败分类和鱼骨兜底，不能只持续追同一不可执行直线 |
| `R-2026-05-14-6727c2a0` | `action`、`make_item`、`mining` | `minecraft:raw_iron x2` 任务恢复 | 任务从 `nbt_load` 恢复，activeTask=`make_item`，目标描述包含木镐、石镐、铁矿和熔炼链 | 多次 `restored task state after nbt_load`，恢复后仍需继续原链路和探矿路线 | 任务恢复样本必须保留目标、已完成 milestone、当前背包和路线摘要，避免恢复后丢任务或重复前置 |
| `S-capacity-drop-uncollected` | 单测夹具 | 采矿掉落无法收入背包 | 背包容量不足，方块已破坏但掉落未收集 | `drop_uncollected` 或容量不足类信号 | 容量不足要分类为可恢复或可解释失败，优先提示取出/存放物品，不能误判为路线失败 |

样本字段明细：

```text
R-2026-05-15-16bf9b52:
- taskId=task-16bf9b52
- target=minecraft:gold_ingot x2
- step=milestone 5/13 gather_stone minecraft:cobblestone x3
- aiPos=70,66,-1 and nearby descent start candidates
- routeStage=stone_descent
- rejectionReason=blocked_line_of_sight blocker=70,66,-1 while target=70,65,-1
- lastInteractionTarget=minecraft:dirt at 70,65,-1
- lastBreakResult=Survival breakBlock rejected because forward foot block still blocked line of sight
- backpack=wooden_pickaxe and early wood chain materials
- milestone=gold_ingot milestone 5/13 current=gather_stone minecraft:cobblestone x3
- routeSummary=repeated start reselection and cover-block digging did not count as descent progress

R-2026-05-14-e2ad0a9a:
- taskId=task-e2ad0a9a
- target=minecraft:gold_ingot x2
- step=milestone 7/13 gather minecraft:raw_iron x1
- aiPos=-780,59,-1
- orePos=-780,58,0
- routeTarget=-780,59,0
- routeStage=EXPOSE_OR_MINE
- rejectionReason=target_changed:minecraft:iron_ore + no_air_neighbor + no_path
- lastInteractionTarget=minecraft:iron_ore at -780,58,0
- lastBreakResult=prospect_timeout after route kept rebuilding around exposed adjacent ore
- backpack=cobblestone, dirt, grass_block, iron_ingot=2, spruce_planks, stone_pickaxe, wooden_pickaxe
- milestone=gold_ingot milestone 7/13 current=gather minecraft:raw_iron x1
- routeSummary=current=-780,59,-1, ore=-780,58,0, exposure=-780,59,0

R-2026-05-14-c852ddd1:
- taskId=task-c852ddd1
- target=minecraft:iron_ingot x2
- step=recoverable stone and iron line-of-sight retries
- targetBlock=-774,68,-2
- blocker=-775,68,-2
- routeStage=RECOVERABLE_BREAK_RETRY
- rejectionReason=blocked_line_of_sight
- lastInteractionTarget=minecraft:iron_ore at -774,68,-2 and -773,67,-3
- lastBreakResult=Survival breakBlock rejected because blocker was between AI and ore
- backpack=not_logged_for_recoverable_sample
- milestone=iron_ingot x2 route eventually succeeded after retries
- routeSummary=targetBlock=-774,68,-2, blocker=-775,68,-2

R-2026-05-13-ae778bec:
- taskId=task-ae778bec
- target=minecraft:iron_ingot x2
- step=long TUNNEL route to iron ore
- aiPos=14,60,-30
- orePos=-65,59,-24
- routeTarget=-65,60,-24
- routeStage=TUNNEL
- rejectionReason=blocked_line_of_sight + occlusion_clear_failed + no_air_neighbor + no_path
- lastInteractionTarget=minecraft:iron_ore route target -65,60,-24
- lastBreakResult=blocked_line_of_sight at blocker 13,61,-30; route terminated as blocked
- backpack=not_logged_in_terminal_summary
- milestone=iron_ingot x2 gather raw_iron while following long tunnel
- routeSummary=start=14,65,-28, current=14,60,-30, target=-65,59,-24, horizontalDistance=85

R-2026-05-14-6727c2a0:
- taskId=task-6727c2a0
- target=minecraft:raw_iron x2
- step=restored make_item after nbt_load
- aiPos=-782,75,16
- oreHint=-785,71,26
- routeStage=TASK_RESTORE
- rejectionReason=repeated_nbt_load_restore
- lastInteractionTarget=active make_item minecraft:raw_iron x2
- lastBreakResult=no break attempted in restore sample
- backpack=must preserve restored backpack snapshot before continuing
- milestone=activeTask=make_item, item=minecraft:raw_iron, quantity=2, queued=0
- routeSummary=restored startPos=-782,75,16, later ore hint=-785,71,26

S-capacity-drop-uncollected:
- taskId=synthetic-capacity
- target=mining drop pickup
- step=block broken but drop cannot enter backpack
- routeStage=PICKUP_AFTER_BREAK
- rejectionReason=inventory_full_for_drop
- lastInteractionTarget=dropped mined item
- lastBreakResult=block broken, drop_uncollected:3
- backpack=full backpack with no free slot for mined drop
- milestone=not_applicable_capacity_sample
- routeSummary=capacity fixture from MiningDropProgressTest
```

最小可复现输入：

```text
route/exposure:
- current=-780,59,-1
- ore=-780,58,0
- routeTarget=-780,59,0
- expectedRouteStage=EXPOSE_OR_MINE

movement/occlusion:
- targetBlock=-774,68,-2
- blocker=-775,68,-2
- failure=blocked_line_of_sight
- expectedNext=clear feet/head passage before retry

far tunnel/rebind:
- current=14,60,-30
- ore=-65,59,-24
- routeTarget=-65,60,-24
- expectedRouteStage=TUNNEL
- expectedFailureClass=route_blocked_or_rebind_needed

capacity:
- failure=drop_uncollected
- expectedFailureClass=inventory_capacity

task recovery:
- event=restored task state after nbt_load
- activeTask=make_item
- expected=keep target and milestone state
```

## 端到端挖矿回归矩阵

这张矩阵用于固定常见挖矿目标的事实链，不代表所有地形都能保证成功。自动化测试覆盖目标解析、工具门禁、高度策略、路线阶段、失败分类和文档字段；实机复测时按同一命令记录 taskId、最终背包和失败建议。

| 目标 | 命令示例 | 起始背包 | 关键阶段 | 期望最终背包 | 失败时应说明 |
| --- | --- | --- | --- | --- | --- |
| 圆石 | `/ai say 帮我挖三块圆石` | 空背包 | 木材 -> 木镐 -> 露天石头或阶梯下挖 -> 圆石 | `minecraft:cobblestone x3` | 缺木材、缺镐、背包容量或无可挖石头 |
| 煤 | `/ai mining start coal 3` | 木镐或可制作木镐材料 | 木镐 -> 煤矿高度 -> 采煤 | `minecraft:coal x3` | 缺木镐、维度错误、路线阻断或背包容量 |
| 铁锭 | `/ai say 帮我烧两块铁锭` | 空背包 | 木材 -> 木镐 -> 圆石 -> 石镐 -> 铁原矿 -> 熔炉/燃料 -> 铁锭 | `minecraft:iron_ingot x2` | 缺石镐、缺燃料、路线阻断或背包容量 |
| 金锭 | `/ai say 帮我挖两块金锭` | 空背包 | 木材 -> 石镐 -> 铁原矿 -> 铁锭 -> 铁镐 -> 金原矿 -> 熔炼金锭 | `minecraft:gold_ingot x2` | 缺铁镐、金矿高度错误、路线阻断或熔炼前置不足 |
| 钻石 | `/ai mining start diamond 1` | 铁镐或可制作铁镐链 | 铁镐 -> 深层高度 -> 探矿直达或鱼骨搜索 -> 钻石 | `minecraft:diamond x1` | 缺铁镐、深层路线阻断或目标消失 |
| 红石 | `/ai mining start redstone 4` | 铁镐或可制作铁镐链 | 铁镐 -> 深层高度 -> 红石矿 -> 红石 | `minecraft:redstone x4` | 缺铁镐、深层路线阻断或背包容量 |
| 青金石 | `/ai mining start lapis 4` | 石镐或可制作石镐链 | 石镐 -> 中深层高度 -> 青金石矿 -> 青金石 | `minecraft:lapis_lazuli x4` | 缺石镐、目标高度错误或路线阻断 |
| 绿宝石 | `/ai mining start emerald 1` | 铁镐或可制作铁镐链 | 铁镐 -> 山地/高层策略 -> 绿宝石矿 -> 绿宝石 | `minecraft:emerald x1` | 缺铁镐、维度/高度不合适或附近无山地矿点 |
| 黑曜石 | `/ai mining start obsidian 1` | 钻石镐 | 钻石镐 -> 已有黑曜石或水岩浆区域 -> 黑曜石 | `minecraft:obsidian x1` | 缺钻石镐、目标方块不可达或背包容量 |

实机复测记录模板：

```text
case=
command=
taskId=
startBackpack=
finalBackpack=
result=success|classified_failure
failureCategory=
failureSuggestion=
notes=
```

## 金锭主链路

目标命令：

```mcfunction
/ai say 帮我挖两块金锭
```

期望阶段：

1. 木材
2. 木板
3. 工作台
4. 木镐
5. 圆石
6. 石镐
7. 铁原矿
8. 燃料
9. 熔炉
10. 铁锭
11. 铁镐
12. 金原矿
13. 金锭

关键日志字段：

```text
milestone=
current=
completed=
failures=
recoveries=
nextRecovery=
```

对应测试：

```text
MilestoneTaskStateTest
```

## 木材前置阶段

已知失败：

```text
从高处开始寻找树木时，候选排序优先选择远处低海拔树
路径系统给出可达站位，但实际导航后到达相邻或低一格位置，被判定 stand_unreachable
发现新树或持续接近目标后，仍因为全局砍树计时超时直接终止
失败文本包含 tool=axe 时被误判为 TOOL_MISSING
反复选择 `stand=direct` 或边界站位，但实际破坏时超距、视线被挡或静默失败
```

期望行为：

```text
树木候选优先选择距离近、垂直落差小、站位稳定的目标
AI 已经处于可触及距离时可以直接砍树，不要求精确站到预计算站位
只有背包原木增加、成功砍落方块、真实接近目标或到达探索点才刷新有效进展计时
树木交互目标使用保守距离和视线校验，边界距离或遮挡目标要快速换目标
tool=axe 只表示建议工具，不应被当成缺少工具
```

关键日志字段：

```text
tree search scan:
candidates=
reachable=
selected=
stand=
tree progress:
reason=tree_target_selected
reason=tree_move_closer
reason=tree_target_in_reach
reason=tree_inventory_increased
reason=tree_block_broken
reason=break_failed:blocked_line_of_sight
reason=break_failed:out_of_reach
reason=tree_target_no_effective_progress
```

对应测试：

```text
TreeTargetPolicyTest
FailureRecoveryTest
```

## 圆石阶段

已知失败：

```text
附近有可见石头但没有优先使用，直接进入下挖
路径预算被远处候选耗尽，近处可达裸露石头没有检查
```

期望行为：

```text
先选择可直接挖的裸露石头
其次选择可移动到站位挖的裸露石头
没有可用裸露目标时才进入阶梯下挖
进入下挖时日志必须包含 stoneScan
```

关键日志字段：

```text
stoneScan{decision=..., reason=..., direct=..., reachable=..., visibleUnreachable=..., hidden=..., pathBudgetSkipped=..., rejectionReasons=...}
```

对应测试：

```text
StoneAcquisitionPolicyTest
```

## 阶梯下挖

已知失败：

```text
stand_no_longer_valid
future_stand_invalid
descent_move_stuck
no_downward_target
descent_limit
```

期望行为：

```text
选择方向前模拟挖完后的站位
未来站位无支撑、头部不可清理、脚部不可清理或危险时提前拒绝方向
当前阶梯 stand 失效时换方向恢复，不直接终止 gather_stone
常见失败进入 MiningFailurePolicy，达到阈值前返回 running
达到阈值后失败消息必须包含失败类型、恢复动作和失败摘要
```

关键日志字段：

```text
future_stand_invalid
horizontalTarget=
verticalTarget=
standTarget=
support=
mining failure review:
failureType=
action=
count=
limit=
terminal=
```

对应测试：

```text
StairFutureStandValidatorTest
MiningFailurePolicyTest
```

## 铁矿阶段

已知失败：

```text
prospect_target_above_current_layer
隐藏铁矿位于当前层上方却被作为硬目标追踪
```

期望行为：

```text
raw_iron 的 EMBEDDED_HINT 如果 verticalDelta > 0，拒绝为 embedded_above_current_layer
暴露铁矿不拒绝
当前层或下方隐藏铁矿可作为路线提示
拒绝后必须重探或继续恢复，不直接清空金锭任务
```

关键日志字段：

```text
classification=
verticalDelta=
rejectionReasons={embedded_above_current_layer=...}
requests prospect rescan
```

对应测试：

```text
OreProspectSelectionPolicyTest
```

## 金矿阶段

已知失败：

```text
没有铁镐仍尝试挖金矿
上方隐藏金矿被作为硬目标追踪
```

期望行为：

```text
gold_ore 和 deepslate_gold_ore 必须要求 iron_pickaxe 或更高等级
石镐不能通过金矿工具门禁
raw_gold 的 EMBEDDED_HINT 如果 verticalDelta > 0，拒绝为 embedded_above_current_layer
当前层或下方隐藏金矿仍可作为路线提示
```

关键日志字段：

```text
mining tool gate:
requiredTool=
currentBestTool=
durability=
nextMilestone=
rejectionReasons={embedded_above_current_layer=...}
```

对应测试：

```text
MiningToolGateTest
OreProspectSelectionPolicyTest
```

## 熔炼阶段

已知失败：

```text
缺燃料时只显示材料不足
输入物自身也可作为燃料时被重复计算
```

期望行为：

```text
熔炉烧炼时先扣非燃料输入，再检查剩余燃料
输入和燃料都实际消耗成功后才能添加产物
煤、木炭、原木、木板都可作为熔炉燃料候选
失败消息包含熔炼前置摘要
```

关键日志字段：

```text
smelting preflight:
stationItem=
hasStationItem=
fuelCount=
fuels=
backpack=
```

对应测试：

```text
SmeltingFuelPolicyTest
SurvivalRecipePlanningTest
```

## 扩展事实矩阵和历史覆盖

本节保留更细的事实覆盖和历史自动化范围。权威端到端复测矩阵以上方“端到端挖矿回归矩阵”为准；这里用于确认铁原矿、金原矿等中间目标和旧样本仍被自动化覆盖。

| 用例 | 起始背包 | 目标命令 | 目标链 | 关键阶段 | 期望结果 |
| --- | --- | --- | --- | --- | --- |
| 圆石 | 空或基础木材 | `/ai say 帮我挖 3 个圆石` | 木镐 -> 石头 -> 圆石 | 工具门禁、两格高通道、支撑 | AI 背包增加圆石或说明缺少木材/可达石头 |
| 煤 | 木镐或更高 | `/ai mining start coal 3` | 煤矿 -> 煤 | 浅层/洞穴扫描、候选冷却 | 获得煤，失败时说明未发现候选或路线阻断 |
| 铁原矿 | 石镐或更高 | `/ai mining start iron 3` | 铁矿 -> 铁原矿 | 石镐门禁、山地/中层高度策略、探矿重扫 | 获得铁原矿或说明候选/路线 |
| 铁锭 | 木材起步 | `/ai say 帮我做一块铁锭` | 木镐 -> 圆石 -> 石镐 -> 铁原矿 -> 燃料 -> 熔炉 -> 铁锭 | 工具升级、熔炼 preflight | 获得铁锭或说明缺燃料/铁矿/路线 |
| 金原矿 | 铁镐或更高 | `/ai mining start gold 2` | 金矿 -> 粗金 | 铁镐门禁、地下金矿高度、直达路线 | 获得粗金或说明候选/路线 |
| 金锭 | 木材起步 | `/ai say 帮我做一块金锭` | 石镐 -> 铁原矿 -> 铁镐 -> 金原矿 -> 金锭 | 铁镐门禁、金矿高度、候选冷却 | 不用石镐挖金矿；成功或说明铁链/金矿阶段原因 |
| 钻石 | 铁镐 | `/ai mining start diamond 1` | 深层下探 -> 钻石矿 -> 钻石 | 深层高度、鱼骨、探矿重扫 | 获得钻石或说明深层路线/候选 |
| 红石 | 铁镐 | `/ai mining start redstone 4` | 深层下探 -> 红石矿 -> 红石 | 深层高度、候选评分 | 获得红石或说明候选/路线 |
| 青金石 | 石镐或更高 | `/ai mining start lapis 3` | 中层下探 -> 青金石矿 -> 青金石 | 中层高度、探矿 | 获得青金石或说明候选/路线 |
| 绿宝石 | 铁镐，山地附近 | `/ai mining start emerald 1` | 山地层/高洞穴 -> 绿宝石矿 -> 绿宝石 | 山地高度策略 | 获得绿宝石或说明维度/高度/候选 |
| 黑曜石 | 钻石镐，水岩浆环境 | `/ai mining start obsidian 1` | 现有黑曜石 -> 黑曜石 | 钻石镐门禁、触达校验 | 获得黑曜石或说明缺钻石镐/环境 |

自动化覆盖：

```text
MiningRegressionMatrixTest:
- 圆石基础资源来源、木镐前置、最终背包目标
- 煤、铁原矿、铁锭、金原矿、金锭、钻石、红石、青金石、绿宝石、黑曜石的最终物品、直接采集物、来源、工具门禁、维度、是否熔炼、高度策略和直达路线阶段
- 铁锭/金锭的“挖到的是原矿，最终背包目标是锭”分离
- recentMiningFailureSamplesRemainReproducible 固化 route、exposure、movement、capacity、task recovery 五类近期失败样本
MiningGoalResolverTest
MiningHeightPolicyTest
MiningMovementSimulatorTest
MiningCandidateCooldownTest
MiningToolGateTest
SmeltingFuelPolicyTest
```

## 自然语言入口误规划回归

最新失败样本：

```text
source_command=帮我两块金锭
实际错误任务=Task{action='gather', parameters={quantity=1, resource=tree}}
结果=AI 去找树，附近候选原木存在但站位不可达，快速失败
```

该失败不属于挖矿路线失败，而是入口规划把明确物品目标误降级成树木采集。修复后，自然语言入口会先由本地代码锁定明确物品目标和数量，DeepSeek 或 fallback 不能覆盖这个目标。

入口回归矩阵：

| 命令 | 期望入口动作 | 期望目标 | 数量 | 是否允许 fallback 改目标 |
| --- | --- | --- | --- | --- |
| `/ai say 帮我两块金锭` | `make_item` | `minecraft:gold_ingot` | 2 | 否 |
| `/ai say 帮我做两个金锭` | `make_item` | `minecraft:gold_ingot` | 2 | 否 |
| `/ai say 挖两块金锭` | `make_item` | `minecraft:gold_ingot` | 2 | 否 |
| `/ai say 给我两个铁锭` | `make_item` | `minecraft:iron_ingot` | 2 | 否 |
| `/ai say 帮我做铁镐` | `make_item` | `minecraft:iron_pickaxe` | 1 | 否 |
| `/ai say 收集圆石` | `make_item` | `minecraft:cobblestone` | 1 | 否 |
| `/ai say 去砍树` | `gather` | `tree` | 1 | 保持树木采集 |

自动化覆盖：

```text
ItemGoalParserTest
AgentIntentParserTest
TaskPlannerIntentTest:
- itemGoalEntryRegressionMatrix
- repairsDeepSeekPlanThatConflictsWithLockedItemGoal
- doesNotRepairNonItemGoalTreePlan
```

实机观察重点：

```text
planning.log 应出现 locked_item_goal=minecraft:gold_ingot x2
action.log 第一条执行任务应为 make_item，而不是 gather tree
/ai status 的“入口”字段应显示本地锁定目标
若后续失败，失败点应落在工具、路线、矿点、熔炼或背包容量等真实生存阶段
```

手动实机复测记录：

```text
实机待测：
1. `/ai say 帮我做一把铁镐`
2. `/ai say 帮我做一块金锭`
3. `/ai mining start diamond 1`
4. `/ai say 帮我打一桶水`

记录时填写 taskId、关键 mining.log / planning.log 片段、最终背包和失败建议。
```
