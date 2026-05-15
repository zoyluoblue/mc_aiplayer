# Minecraft AI Agent for Fabric

这是一个面向 Minecraft Java Edition 的 Fabric AI Agent 项目。玩家可以用自然语言给自己的 AI 玩家下达任务，例如砍树、制作工具、挖矿、烧炼、打水和建造。项目目标不是做一个能凭空改世界的管理员工具，而是持续把 AI 玩家做成一个可信的普通生存玩家。

![Minecraft AI Agent 项目定位](docs/images/aiplayer-overview.svg)

目标版本：

- Minecraft: `1.21.3`
- Fabric Loader: `0.18.4`
- Fabric API: `0.114.1+1.21.3`
- Java: `21`

## 这个项目是什么

AI 玩家是一个真实存在于世界里的实体，拥有自己的背包、位置、任务状态和执行队列。玩家通过 `/ai say ...` 或 AI 面板输入中文自然语言任务后，DeepSeek 负责理解意图和生成高层计划，本地模组代码负责查询 Minecraft 事实、验证计划，并用普通生存动作执行。

核心边界：

- AI 初始没有免费材料。
- AI 使用自己的真实背包，也可以按规则读取附近箱子。
- 采集、合成、熔炼、放置和打水都必须消耗真实物品。
- 破坏或放置方块前必须移动到可触及距离。
- 配方、材料数量、工具等级、箱子内容、方块存在性和路径可达性由游戏代码验证。
- DeepSeek 不负责编造物品、配方或直接修改世界。

## 如何开始

开发环境运行：

```bash
./gradlew runClient
```

打包：

```bash
./gradlew build
```

构建产物位于：

```text
build/libs/aiplayer-fabric-1.0.0.jar
```

如果要在正常客户端或服务端使用，把构建出的 jar 放入对应 Minecraft 实例的 `mods/` 目录，并安装上方版本对应的 Fabric Loader 和 Fabric API。

## 配置 DeepSeek

配置文件统一使用：

```text
config/ai.json
```

开发环境执行 `runClient` 时，会自动复制到：

```text
run/config/ai.json
```

可以参考 [config/ai.json.example](config/ai.json.example) 创建本地配置：

```json
{
  "deepseek": {
    "apiKey": "",
    "baseUrl": "https://api.deepseek.com",
    "model": "deepseek-v4-flash",
    "thinkingEnabled": false,
    "reasoningEffort": "high",
    "maxTokens": 8000,
    "temperature": 0.7
  },
  "behavior": {
    "actionTickDelay": 20,
    "enableChatResponses": true,
    "maxActiveAiPlayers": 10
  }
}
```

不要把真实 API key 写入仓库。

## 如何玩耍

![基础游玩流程](docs/images/gameplay-flow.svg)

先召唤自己的 AI 玩家：

```mcfunction
/ai spawn
```

也可以指定名字：

```mcfunction
/ai spawn 小助手
```

除非你主动执行 `/ai remove`，AI 不会被自动删除。玩家使用 `/tp`、远距离移动或切换到其他位置后，如果原 AI 实体因为区块卸载变成 inactive，模组会尝试在玩家当前维度附近恢复这个 AI，并保留它的名称、背包和记忆。手动删除会写入本地墓碑记录，避免旧区块里的 AI 副本在后续加载或重启后重新复活；如果你之后重新 `/ai spawn`，只有新生成的 AI 实体会被允许注册。如果进入游戏后 `/ai spawn 111` 提示名称占用，但 `/ai list` 又看不到自己的 AI，可以执行 `/ai remove 111` 清理自己拥有或没有 owner 的旧占用记录，然后重新召唤。

给 AI 下达自然语言任务：

```mcfunction
/ai say 去砍一棵树
```

```mcfunction
/ai say 做一个门
```

```mcfunction
/ai say 帮我做一把铁镐
```

```mcfunction
/ai say 帮我挖两块金锭
```

```mcfunction
/ai say 帮我打一桶水
```

这些任务会按生存逻辑推进。比如制作铁镐时，AI 会先确认背包和附近箱子里有什么，再按需要砍树、做木板、做木棍、做工作台、做木镐、挖圆石、做石镐、找铁矿和煤、烧铁锭，最后合成铁镐。

金锭任务会按完整生存链路推进：木材、木板、工作台、木镐、圆石、石镐、铁原矿、燃料、熔炉、铁锭、铁镐、金原矿、金锭。配方和数量优先来自当前 Minecraft 服务端的 `RecipeManager`，因此会跟随本地 1.21.3 游戏数据；基础获取行为使用模组内的生存规则模型映射到砍树、挖矿、采集方块、击杀生物、装水或熔炼等动作。AI 不会凭空获得材料；树木采集会先验证保守交互距离、视线和可站立工作位，若目标树反复超距、被遮挡或站位不可达，会快速换目标并在日志里记录 `out_of_reach`、`blocked_line_of_sight`、`stand_unreachable` 或 `tree_target_no_effective_progress`。下探或平挖矿道时会先挖出两格高通道，阶梯向下时还会额外清理入口头部空间，确保自己能像普通玩家一样走到下一格。若当前世界附近缺少可执行的树、裸露石头、矿物、燃料或安全路线，恢复过程中会记录当前阶段和已尝试动作。恢复次数达到上限或遇到硬性失败时，当前任务可能结束，但日志和状态会说明卡在哪个阶段、已经尝试了什么以及玩家可以怎么协助。

如果阶梯下探暂时找不到下一阶，AI 不会立刻结束挖矿任务；它会在当前层开出一段两格高的水平绕行矿道，换方向后重新尝试下探。遇到下一阶脚下是空气时，AI 会优先使用自己背包里的圆石、泥土或木板等真实方块补支撑，放置会消耗背包材料。挖掘和补支撑前会校验距离、视线和可点击的邻接面，避免隔墙或超距破坏、放置方块。到达目标高度后，AI 会按主方向推进矿道；矿道推进到一段距离后会重新探矿修正路线，并按鱼骨模式在主线两侧交替开分支，而不是原地等待或直接失败。探矿找到矿点后，执行器会生成 `direct_route`，把矿点、邻近站位、下一步方向和下一步站位记录到日志和状态里，后续路线优先按这张直达路线挖到矿点旁边。到达矿点邻位后，如果矿物仍没有暴露面，AI 会先清理相邻可挖方块来暴露矿物，并在日志里记录被清理的遮挡方块，再继续挖目标矿；当 AI 站在矿物上一层、矿物正下方被包住时，会优先挖开矿物上方或旁边的暴露点，而不是把该矿误判为 `target_changed` 后反复重扫。AI 会记录最近安全矿道点，遇到路线卡住时优先回退到当前矿道附近的可用点再继续恢复；明显高于当前路线、距离过远、已经失败、站位失效或寻路不可用的旧点会被跳过并写入 `mining.log`，避免从低处反复回爬到旧山顶点。当前 AI 实体为完全无敌，敌对生物、摔落、燃烧、岩浆和其他环境伤害不会再作为挖矿路线的终止条件；日志会以 `ignored_mining_danger` 记录这些被忽略的风险。这个过程仍然按生存规则破坏方块、消耗工具耐久，并保留工具等级、方块可破坏性、世界高度、区块加载和真实背包物品这些硬约束。若相邻矿道站位已经挖通但原版导航没有走进去，AI 会使用短步推进进入相邻格，并用脚底中心距离判断是否已经到达。若最终仍失败，日志会输出绕行方向、移动格数、换向次数、通道清理方块数或 `adjacent_mining_step_stuck`，并附带失败分类、可执行建议和最近 5 条路线决策。

自动挖矿入口：

```mcfunction
/ai mining start iron 3
```

上例会尝试获得 `minecraft:raw_iron x3`。常用目标包括 `coal`、`copper`、`iron`、`gold`、`diamond`、`redstone`、`lapis`、`emerald`、`obsidian`、`ancient_debris`。目标解析统一走挖矿事实模型：`gold`、`金矿`、`minecraft:gold_ore` 会按普通生存掉落解析为粗金，`金锭`、`minecraft:gold_ingot` 会先挖粗金再熔炼为金锭，`minecraft:diamond_ore` 会解析为普通生存可获得的钻石而不是精准采集矿石方块。没有明确矿物目标时，自动挖矿默认把 `Y=0` 作为探索高度；指定矿物后会按矿物资料表选择目标高度，例如金矿进入地下金矿高度，钻石进入深层高度，煤和高山铁矿可以保留在有效地表高度，不会全部硬挖到同一层。

换层时也会沿用同一套高度策略。比如铁矿在山地范围内会继续使用山地层，远古残骸会限制在下界低层范围，钻石、红石和青金石会进入对应深层，不会在无效高度长期鱼骨挖矿。

探矿过程中，失败候选不会被永久拉黑。比如某个矿点没有可用暴露面、路线目标在当前层上方或刚刚移动失败，AI 会把该候选放入短期冷却并立即重扫，优先尝试其他候选；冷却到期后仍可再次选择它，避免既反复追同一个失败点，又不会永久错过矿物。候选评分会同时考虑分类、水平距离和垂直距离，基础矿物会优先选择近处可沿矿道开过去的提示点，避免远处暴露矿点因为“看起来可挖”而压过当前矿道附近的低成本路线。破坏方块失败现在会记录结构化原因，例如 `blocked_line_of_sight`、`out_of_reach`、`target_air` 或 `destroy_failed`，并记录实际遮挡方块；如果视线被可挖方块挡住，AI 会先按生存规则逐层清理遮挡，再继续原路线。同一方块同一失败原因连续达到阈值后会换目标、换路线或触发恢复，不会长时间原地挥手。达到恢复上限时，终止消息会区分缺工具、不可破坏、区块未加载、世界边界、目标消失、背包已满、路线不可达和长期无进展等硬原因，并提示玩家下一步应移动位置、补材料、清背包或重新探矿。

挖矿前和路线段推进前会检查工具等级和最低耐久。若当前镐快坏了，AI 会先尝试用真实材料制作一把替换工具，并优先装备耐久足够的新工具；如果材料不足，状态会进入等待玩家并说明缺少替换工具，而不是继续用快坏的镐深挖。

DeepSeek 在挖矿中只作为策略顾问：它只能建议继续当前路线、重扫、换层、回退安全点或请求玩家协助。坐标、矿物事实、配方数量、破坏和放置动作都由本地代码验证和执行；如果 DeepSeek 返回坐标、命令或作弊建议，模组会拒绝该建议并继续使用本地恢复策略。

挖矿回归样例记录在 `docs/mining-regression.md`。当前自动化矩阵覆盖圆石、煤、铁原矿、铁锭、金原矿、金锭、钻石、红石、青金石、绿宝石和黑曜石的目标解析、工具门禁、高度策略、直达路线阶段、最终背包目标、失败分类和端到端命令字段；实机复测时按该文档的模板记录 taskId、起始背包、最终背包和失败建议。

查看状态：

```mcfunction
/ai status
```

状态会显示当前目标、动作、位置、背包以及当前动作细节。挖矿和制作链任务会额外显示里程碑、路线计划、阶段计划、直达路线、阶梯下探、主矿道、鱼骨分支和待补支撑等信息，便于判断 AI 是在准备工具、下探、开矿道、探矿修正、采矿还是等待玩家协助。面板和 `/ai status` 会使用中文摘要，不再直接展示 `target_primary_midpoint`、`reason=...` 这类内部调试字段；完整原始调试信息仍写入 `run/log/mining/` 等日志目录。

`/ai mining status` 和 `/ai status` 会显示同一套挖矿详情，包括当前 step、当前矿点、路线阶段、当前步、行动 tick、重扫行动计数、目标、阶段、高度策略、路线、访问过的 Y、候选数量、拒绝数量、可达数量、最近挖掘点、已挖方块、恢复决策、探矿冷却、30 步重扫计数、下次重扫剩余触发条件和最近安全点。状态还会显示工具门禁、背包容量、挖掘动作目标和最近失败分类：工具门禁用于确认所需镐、当前镐等级和耐久；背包容量会说明可容纳、缺少数量、缺少空格和当前背包摘要；挖掘动作会显示 AI 当前正在对准的方块；最近失败分类会给出原因、恢复策略和建议。状态还会说明当前挖矿危险策略：AI 会忽略敌对生物和环境伤害，但仍会保留工具等级、不可破坏方块、区块加载、世界边界、背包容量和目标变化这些硬约束。

查看位置：

```mcfunction
/ai location
```

查看 AI 背包：

```mcfunction
/ai backpack
```

把玩家背包里的真实物品交给 AI：

```mcfunction
/ai backpack put minecraft:oak_log 16
```

从 AI 背包取回真实物品：

```mcfunction
/ai backpack take minecraft:oak_log 16
```

停止当前任务，并让 AI 按正常导航逻辑回到玩家身边：

```mcfunction
/ai stop
```

移除自己的 AI 玩家：

```mcfunction
/ai remove
```

按名称清理自己的 AI 或旧占用记录：

```mcfunction
/ai remove 111
```

## AI 面板

进入游戏后按 `Alt+2` 打开 AI 面板，macOS 为 `Option+2`。

面板提供：

- 输入自然语言任务。
- 显示 AI 消息和系统消息。
- 显示背包按钮，最多展示 20 个 AI 背包格。
- 显示状态和位置。
- 显示停止按钮，触发 `/ai stop`。

面板文案以中文为主，但游戏命令子命令保持英文，例如 `spawn`、`say`、`stop`、`status`。

## 基本原理

![观察、规划、执行、复盘循环](docs/images/survival-loop.svg)

这个模组把“AI 理解任务”和“Minecraft 生存执行”拆开：

1. Observe 观察层收集当前事实，包括 AI 背包、附近箱子、附近方块、附近实体、坐标、维度、主手工具和当前任务。
2. RecipeResolver 配方层用 Minecraft `RecipeManager` 和本地生存来源规则递归计算材料链，并扣减 AI 背包和附近箱子中已经存在的材料。
3. DeepSeek Planner 只基于观察结果和配方事实生成高层计划，例如“先收集木头，再制作工作台，再合成目标物品”。
4. Plan Validator 本地验证动作白名单、物品 ID、数量、工具要求、路径和生存边界。
5. Executor 执行一个小步骤，例如移动到树旁、破坏原木、打开工作台或熔炉、放置方块。
6. Replan 每完成一步或遇到失败后重新观察，必要时让 DeepSeek 复盘下一步。

DeepSeek 请求是异步的，不能阻塞 Minecraft server thread。世界读取和世界修改必须回到 server thread 执行。

## 可调试命令

输出 AI 当前可观察状态 JSON：

```mcfunction
/ai snapshot
```

查看目标物品的递归配方链：

```mcfunction
/ai recipe minecraft:diamond_pickaxe 1
```

输出会同时包含一份本地生成的依赖图，格式类似 `source:stone -> gather -> minecraft:cobblestone x3` 或 `[minecraft:cobblestone x3, minecraft:stick x2] -> craft:crafting_table -> minecraft:stone_pickaxe x1`。这张图只使用当前游戏实例解析出的配方、背包、箱子和基础获取规则，不依赖网页配方数据。

内部结构见 [Crafting Tree 结构](docs/crafting-tree.md)。代码入口是 `CraftingTree.fromRecipePlan(...)`，可输出节点/边 JSON、文本依赖图或 Mermaid 图。每张图都有稳定 `graphId`，每个 `TreeStep` 都有 action 节点 ID；矿物类 `make_item`、`mine` 和 DeepSeek 返回的矿物 `gather` 任务会先归一到这套图，再通过 `CraftingTreeActionRouter` 映射到本地执行 step。

查看本地验证后的高层执行计划：

```mcfunction
/ai plan minecraft:oak_door 1
```

查看自动挖矿状态：

```mcfunction
/ai mining status
```

停止自动挖矿任务：

```mcfunction
/ai mining stop
```

停止挖矿并让 AI 正常走回玩家身边：

```mcfunction
/ai mining return
```

## 日志

运行日志会写入游戏目录下的 `log/` 目录。开发环境常见位置是：

```text
run/log/
```

常用分类包括：

- `planning`：自然语言任务、DeepSeek 输入输出和本地计划。
- `snapshot`：观察层导出的当前世界事实。
- `recipe`：递归配方、材料缺口和工作站需求。
- `action`：动作调度和任务队列。
- `make_item`：制作链 step、背包变化和失败原因。
- `mining`：探矿、路线、目标矿物和挖矿终止原因。
- `mining_strategy`：长任务中 DeepSeek 给出的策略建议。

排查任务时，先在 `planning.log` 找到本次命令的 `taskId`，再用同一个 `taskId` 到其他日志分类中追踪。

金锭和长挖矿任务重点关注这些字段：

- `graphId` / `treeNode`：当前依赖图和正在执行的 `item -> prerequisite -> action` 节点。
- `milestone`：当前制作链阶段，例如木材、圆石、铁原矿、金原矿或金锭。
- `stoneScan`：圆石阶段是否找到了可直接挖、可到站位挖或只能下挖的石头。
- `future_stand_invalid`：阶梯下挖前模拟出的未来站位不可用原因。
- `route_clearance` / `clear_entry_head`：前进前正在清理两格高矿道或下行台阶入口头部空间。
- `embedded_hint_rebind`：附近扫描发现更近的隐藏矿点，AI 会放弃旧的远距离直达路线并重建路线。
- `target_not_exposed`：目标矿物仍被周围方块包住，AI 会回到暴露面清理阶段，而不是把矿物本身误判为目标变化。
- `mining tool gate`：挖矿前的工具等级、耐久和下一步前置链判断。
- `mining animation`：当前挖矿动作的模式、目标方块、方块类型和手持物，用于排查是否对准正确方块。
- `mining inventory` / `inventory_full_for_drop`：背包容量、缺少数量、缺少空格和当前背包摘要。
- `failureCategory` / `recoveryAction`：失败分类和下一步恢复策略，例如重扫、换方向、准备工具或请求玩家协助。
- `embedded_above_current_layer`：隐藏矿点位于当前层上方，已被拒绝并触发重探。
- `smelting preflight`：熔炼前的工作站、燃料、原料和背包摘要。
- `Recovered AI player` / `restored task state`：AI 因区块卸载恢复实体后，会把当前目标、正在执行的任务和后续队列重新入队，从当前背包与当前位置继续执行。NBT 读取只会缓存恢复状态，真正恢复会在实体空闲时应用一次，避免同一任务被反复恢复并重置当前动作。

更完整的回归排查模板见 [挖矿任务回归日志模板](docs/mining-regression.md)。

## 设计文档

- [AI 玩家阶段路线](docs/phases.md)
- [挖矿任务回归日志模板](docs/mining-regression.md)
- [Crafting Tree 结构](docs/crafting-tree.md)
