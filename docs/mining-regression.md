# 挖矿任务实机回归记录

本文档记录长挖矿任务的实机验收清单。每次修改挖矿、探矿、DeepSeek 策略循环或资源 profile 后，优先使用这里的命令复测，并用同一个 `taskId` 串联 `planning`、`recipe`、`make_item`、`mining_strategy` 和 `snapshot` 日志。

## 通用环境

- Minecraft: `1.21.3`
- 模式：生存模式
- AI 初始背包：尽量为空，除非用例明确说明已有材料
- 配置：`run/config/ai.json`
- 主要日志：
  - `run/log/planning/planning.log`
  - `run/log/recipe/recipe.log`
  - `run/log/make_item/make_item.log`
  - `run/log/mining_strategy/mining_strategy.log`
  - `run/log/snapshot/snapshot.log`

## 验收规则

- AI 不允许通过创造模式、瞬移、`give`、`setblock` 或凭空生成物品完成任务。
- DeepSeek 只能给策略级建议，例如继续、换目标、阶梯下挖、重新观察或请求玩家协助。
- 配方、工具等级、材料数量、箱子内容和方块存在性必须以本地代码为准。
- 长 step 每 30 秒最多出现一次 `mining_strategy` 上报。
- 如果失败，日志必须能说明失败在哪个 step、当前位置、背包状态和尝试过的恢复策略。

## 回归矩阵

| ID | 命令 | 起始环境 | 预期阶段 | 成功标准 | 允许失败边界 |
| --- | --- | --- | --- | --- | --- |
| mining-gold-ingot | `/ai say 帮我挖两块金锭` | 主世界地表，AI 空背包，附近有树 | 木镐 -> 圆石 -> 石镐 -> 铁矿/煤 -> 熔炉 -> 铁镐 -> 下探金矿 -> 熔炼金锭 | AI 背包出现 `minecraft:gold_ingot x2` | 如果超过探矿预算仍无金矿，必须说明当前 Y、高度策略和已尝试路线 |
| mining-diamond | `/ai say 帮我找三颗钻石` | 主世界地表，AI 空背包，附近有树 | 木/石/铁工具链 -> 下探到深层高度 -> 扫描钻石矿 | AI 背包出现 `minecraft:diamond x3` | 如果找不到钻石，必须说明已进入推荐高度并记录探矿预算耗尽 |
| mining-redstone | `/ai say 帮我挖红石` | 主世界地表，AI 空背包，附近有树 | 铁镐链 -> 下探到深层高度 -> 挖红石矿 | AI 背包出现 `minecraft:redstone` | 如果失败，不能停在地表反复扫描 |
| mining-emerald | `/ai say 帮我挖绿宝石` | 主世界山地或高海拔区域优先 | 铁镐链 -> 高山/高海拔搜索 -> 挖绿宝石矿 | AI 背包出现 `minecraft:emerald` | 非山地或低海拔环境允许请求玩家移动到山地 |
| mining-obsidian | `/ai say 帮我挖黑曜石` | 主世界，附近有黑曜石或玩家带到岩浆/水区域 | 钻石镐链 -> 找到黑曜石 -> 挖黑曜石 | AI 背包出现 `minecraft:obsidian` | 如果附近没有黑曜石，不允许把岩浆和水交互编造成已完成 |
| mining-ancient-debris | `/ai say 帮我挖远古残骸` | 下界，AI 已能安全移动 | 钻石镐链 -> 下界低高度探矿 -> 挖远古残骸 | AI 背包出现 `minecraft:ancient_debris` | 如果在主世界执行，必须提示需要下界 |

## 自动挖矿专项矩阵

本矩阵用于验证 `/ai mining start <target> [count]`。每次实机测试需要记录 `taskId`、起始维度、起始 Y、是否有洞穴、是否有树、AI 起始背包、最终耗时、最终结果和关键失败日志。

| ID | 命令 | 起始材料 | 维度/地形 | 洞穴 | 成功标准 | 成功率目标 | 允许失败边界 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| auto-coal | `/ai mining start coal 8` | 空背包，附近有树 | 主世界地表或浅层山坡 | 有洞穴更优 | AI 背包出现 `minecraft:coal x8` | 基础矿物应高稳定 | 附近无煤且探矿预算耗尽时，日志说明扫描半径、候选数和路线 |
| auto-iron | `/ai mining start iron 3` | 空背包，附近有树 | 主世界地表，允许山地或地下 | 有洞穴更优 | AI 背包出现 `minecraft:raw_iron x3` | 基础矿物应高稳定 | 失败必须说明是否尝试石镐、推荐高度和洞穴/分支路线 |
| auto-copper | `/ai mining start copper 6` | 空背包，附近有树 | 主世界浅层或洞穴 | 有洞穴更优 | AI 背包出现 `minecraft:raw_copper x6` | 基础矿物应高稳定 | 失败必须说明浅层扫描和可达目标筛选结果 |
| auto-gold | `/ai mining start gold 2` | 空背包，附近有树 | 主世界，普通地下或恶地 | 可无洞穴 | AI 背包出现 `minecraft:raw_gold x2` | 深层矿物允许较低成功率 | 失败必须说明铁镐链、目标 Y、分支长度和危险/卡住原因 |
| auto-diamond | `/ai mining start diamond 2` | 空背包，附近有树 | 主世界深层 | 可无洞穴 | AI 背包出现 `minecraft:diamond x2` | 深层矿物允许较低成功率 | 必须先补铁镐；失败必须说明深层分支矿道尝试 |
| auto-redstone | `/ai mining start redstone 8` | 空背包，附近有树 | 主世界深层 | 可无洞穴 | AI 背包出现 `minecraft:redstone x8` | 深层矿物允许中等成功率 | 失败不能停在地表扫描，必须有下探或分支记录 |
| auto-lapis | `/ai mining start lapis 4` | 空背包，附近有树 | 主世界中低层 | 有洞穴更优 | AI 背包出现 `minecraft:lapis_lazuli x4` | 中层矿物应中高稳定 | 失败必须说明中层高度范围和矿洞路线 |
| auto-emerald | `/ai mining start emerald 1` | 空背包，附近有树 | 主世界山地或高海拔 | 有洞穴更优 | AI 背包出现 `minecraft:emerald x1` | 环境依赖，允许条件失败 | 非山地/低海拔时必须请求玩家移动到更合适区域 |
| auto-obsidian | `/ai mining start obsidian 1` | 空背包，附近有树 | 主世界，附近有黑曜石 | 不要求 | AI 背包出现 `minecraft:obsidian x1` | 特殊资源仅在环境满足时成功 | 附近没有黑曜石时必须明确缺少特殊环境，不能凭空生成 |
| auto-ancient-debris | `/ai mining start ancient_debris 1` | 下界起步，允许已有基础安全物资 | 下界低层 | 不要求 | AI 背包出现 `minecraft:ancient_debris x1` | 高风险资源允许条件失败 | 主世界执行必须提示维度错误；下界失败必须说明目标 Y 和分支路线 |

### 记录模板

```text
ID:
命令:
taskId:
起始材料:
维度/起始Y/地形:
是否有洞穴:
耗时:
结果:
关键日志:
下一步:
```

## 自动挖矿基准目标

- 基础矿物：煤、铁、铜。在有树且地形正常的主世界地表，目标是高稳定成功；失败必须能给出可移动位置、缺少工具或资源预算耗尽的具体原因。
- 中层矿物：青金石、绿宝石。成功依赖高度和地形；失败必须给出推荐高度、地形要求或移动建议。
- 深层矿物：金、钻石、红石。允许耗时更长或失败，但必须完成工具链、合理下探、分支矿道和危险记录。
- 特殊资源：黑曜石、远古残骸。只在环境和维度满足时成功；不允许通过普通探矿编造资源来源。

## 本轮记录

- 完成日期：2026-05-11
- 完成内容：
  - 已建立回归矩阵和日志检查规则。
  - 已新增自动挖矿专项矩阵，覆盖煤、铁、铜、金、钻石、红石、青金石、绿宝石、黑曜石和远古残骸。
  - 已为矿物 profile 增加维度、推荐 Y 范围、路线提示、特殊环境标记和探矿预算。
  - 已让金、钻石、红石、远古残骸等深层矿物在高于推荐高度时优先进入阶梯式下探。
  - 已验证自动化测试和构建。
- 本轮未运行交互式 `runClient` 实机操作；后续实机复测时，应在本文件追加每个命令的 `taskId`、最终结果和失败日志行。

## 回归记录：圆石下探卡住

- 完成日期：2026-05-12
- 触发命令：`/ai say 给我一个金锭`，中文说明：从空背包开始制作金锭，先补木镐、圆石、石镐、铁镐，再挖金矿并熔炼。
- 失败样本：`task-3bab60ee`
- 失败点：`gather_stone minecraft:cobblestone x3`
- 日志结论：
  - `mining.log` 显示 `已挖方块=0`、`破坏失败=0`、`扫描=0`，说明 AI 没有进入有效挖掘。
  - 模式停在 `descent_move_target` / `stone_move_lower`，拒绝原因包含 `movement_stuck`，说明 AI 在追逐下方可站立点时卡住。
  - 该问题发生在金锭链路早期的圆石步骤，不是金矿、铁镐或 DeepSeek 配方推理错误。
- 本轮修正：
  - `gather_stone` 的下探顺序改为优先选择可破坏的阶梯方块，只有找不到可挖目标时才移动到下方可站立点。
  - 下方可站立点新增 120 tick 无进展超时；超时后写入 `mining.log`，拒绝该位置并改为就地挖阶梯。
  - DeepSeek 建议切换阶梯下挖时会清理旧移动目标，避免继续沿用已经卡住的位置。
- 复测重点：
  - 再次执行 `/ai say 给我一个金锭`，确认圆石步骤出现 `mining dig attempt` 和 `mining dig result`。
  - 如果失败，优先检查 `run/log/mining/mining.log` 中是否还出现同一位置反复 `descent move target`。
