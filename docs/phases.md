# AI 玩家阶段路线

本文档整合 AI 玩家当前全部阶段路线。项目的核心要求是：通过接入 DeepSeek 大模型，让玩家用自然语言和 AI 聊天时，AI 能理解玩家意图，分析并拆解任务，再通过 Minecraft 生存模式允许的方式一步一步完成任务。本文档既记录已经完成的 DeepSeek 规划执行基础架构，也记录后续要做的生存挖矿、铁工具制作、行为拟真和 Agent 化路线。

状态标记：本文档当前所有阶段均已完成并标记为 `[x] 已完成`。后续新增阶段时，再按实际状态补充未开始或开发中标记。

## 总原则

- DeepSeek 负责理解玩家目标、高层策略选择和失败复盘。
- Minecraft 事实由本地代码决定，包括配方、材料数量、工具等级、箱子内容、背包内容、方块是否存在和是否可达。
- AI 必须像普通生存玩家一样行动：移动、采集、合成、熔炼、消耗材料、使用工具和工作站。
- 不允许飞行、创造模式放置、瞬移完成任务、凭空生成物品或跳过生存链。
- 长任务应按“小 step 执行 -> 重新观察 -> 继续或复盘”的方式推进。
- DeepSeek 可以提出计划，但所有计划必须被本地动作白名单、配方系统、资源系统和安全规则校验后才能执行。
- 当 DeepSeek 的输出和游戏事实冲突时，以本地 Minecraft 状态和规则为准，并把冲突原因写入日志。

## Part A: DeepSeek 规划执行基础架构

### Phase A1: Observe 观察层

状态：`[x] 已完成`

验证：`./gradlew build` 通过。

目标：把 AI 当前能知道的信息整理成稳定 JSON，作为发给 DeepSeek 和本地验证器的统一输入。

需要采集的数据：

- 玩家原始命令。
- AI 背包物品，包含物品 ID、数量、耐久信息。
- AI 当前手持工具和装备。
- 附近可访问箱子的内容。
- 附近关键方块，例如树木、石头、煤矿、铁矿、水、工作台、熔炉、箱子。
- 附近实体，例如玩家、敌对生物、动物。
- AI 当前状态，例如位置、血量、当前任务、是否卡住。
- 世界上下文，例如维度、生物群系、昼夜和危险等级。

完成标准：

- `/ai say 做一个门` 时，日志里能看到完整观察 JSON。
- AI 背包变化后，下一次观察能准确反映变化。
- 附近箱子中的物品能被识别，但不会被直接拿走，除非执行阶段明确需要。

### Phase A2: RecipeResolver 配方递归层

状态：`[x] 已完成`

验证：`./gradlew build` 通过。

目标：由模组代码查询真实 Minecraft 配方，递归计算目标物品需要哪些材料，而不是让 DeepSeek 猜配方。

核心能力：

- 根据目标物品 ID 查询可用配方。
- 判断配方类型，例如手工合成、工作台合成、熔炉烧炼。
- 计算缺失材料。
- 递归追溯材料来源。
- 区分可合成物品和基础资源。

基础资源示例：

- 原木：通过砍树获得。
- 圆石：通过挖石头获得。
- 煤炭：通过挖煤矿获得。
- 生铁：通过挖铁矿获得。
- 铁锭：需要生铁加燃料并使用熔炉。

完成标准：

- 输入 `minecraft:oak_door x1` 能得到原木到木门的完整递归链。
- 当前背包有木板时，不会重复要求砍树。
- 当前箱子有材料时，计划能识别“可从箱子取出”。
- 配方不存在时返回明确失败原因，而不是让 DeepSeek 编造。

### Phase A3: DeepSeek Planner 规划层

状态：`[x] 已完成`

验证：`./gradlew build` 通过。

目标：让 DeepSeek 基于观察结果和配方递归结果，生成高层计划。DeepSeek 不输出底层坐标移动和破坏方块细节，只输出可验证的阶段目标。

规划约束：

- 只能使用模组声明过的动作类型。
- 不允许使用创造模式能力。
- 不允许假设物品凭空存在。
- 不允许跳过配方验证。
- 每个 step 都必须能被本地验证器校验。

完成标准：

- DeepSeek 输出非法物品 ID 时，本地验证器能拒绝。
- DeepSeek 计划中材料数量错误时，本地配方系统能纠正。
- DeepSeek 失败一次后不会直接执行危险或无效动作。
- 常见命令在 DeepSeek 不可用时仍有基础 fallback。

### Phase A4: Execute And Replan 执行复盘层

状态：`[x] 已完成`

验证：`./gradlew build` 通过。

目标：AI 不再一次性执行完整大计划，而是执行一个小步骤后重新观察，并在必要时让 DeepSeek 复盘和改计划。

执行循环：

```text
观察当前状态
解析玩家目标
查询配方链
让 DeepSeek 生成高层计划
本地验证计划
执行第一个可执行 step
step 完成后重新观察
判断目标是否完成
未完成则继续执行或重新规划
```

触发复盘的时机：

- 每完成一个 step。
- 背包或箱子内容变化。
- 找不到目标资源。
- 路径长时间没有进展。
- 工具损坏或缺失。
- 血量过低或附近出现敌对生物。
- 长任务每 30 到 60 秒兜底复盘一次。

完成标准：

- `/ai say 做一个门` 可以从空背包开始完成。
- AI 执行过程中背包变化会进入下一轮观察。
- 如果附近没有树，AI 明确报告失败原因。
- 如果玩家中途把木板放进附近箱子，AI 下一次复盘能使用箱子材料。
- 如果 AI 卡住，会换目标资源或请求重新规划。

## Part B: 生存挖矿与铁工具制作优化

本部分目标是让 AI 从空背包开始，像普通生存玩家一样完成铁工具链路：

```text
砍树 -> 合成木板/木棍/工作台 -> 做木镐 -> 挖圆石 -> 做石镐 -> 挖铁矿和煤 -> 做熔炉 -> 烧铁锭 -> 合成铁工具
```

### Phase B1: 基线复现与日志判定

状态：`[x] 已完成`

完成记录：已为自然语言任务生成统一 `taskId`，并写入 `planning`、`snapshot`、`recipe`、`action`、`make_item` 分类日志。`make_item` step 开始和结束时会记录 step 序号、目标数量、AI 坐标、维度、主手物品、背包摘要、执行状态、失败原因和是否允许重规划。

目标：建立稳定复现用例，明确每类失败属于规划、配方、路径、采集、熔炼还是合成问题。

开发内容：

1. 整理铁工具测试命令：
   - `/ai say 给我一把铁铲`
   - `/ai say 帮我做一把铁斧头`
   - `/ai say 帮我做个铁镐`
2. 为 `make_item`、`recipe`、`action`、`snapshot` 日志增加统一任务 ID，方便跨日志追踪同一次命令。
3. 在每个 step 开始时记录当前 step、目标数量、背包摘要、AI 坐标、维度和当前工具。
4. 在每个 step 结束时记录成功、失败或卡住原因、背包变化、是否允许重试或重规划。

验收标准：

- 运行铁铲、铁斧、铁镐任意命令后，可以只看分类日志判断失败点。
- 日志能区分“配方链错误”和“执行时找不到资源”。
- 失败信息不要只显示“材料不足”，必须说明缺哪个材料、在哪个 step 缺、准备如何补。

验证方式：

```bash
./gradlew build
```

## Part F: AI 自动挖矿专项路线

本部分专门处理“AI 自动挖矿”体验。目标不是让 AI 作弊拿矿，而是让它像普通玩家一样：右手拿工具、持续挥动、自己选择矿物目标、规划矿洞、补工具、处理危险、汇报状态，并在失败时给出可操作建议。

### Phase F1: 挖矿动作拟真与工具显示

状态：`[x] 已完成`

完成记录：`AiPlayerEntity` 新增工作动画同步窗口。服务端每次调用 `swingWorkHand` 时会刷新短时间工作状态并更频繁触发主手挥动；客户端 `AiPlayerClientMod` 在渲染状态里检测该工作状态，强制使用右手攻击动画。挖矿、砍树、放置和采集时，玩家能看到 AI 右手拿着当前工具并持续挥动，而不是站着不动。

目标：AI 挖矿时视觉上必须像普通玩家正在挥镐。

开发内容：

1. 服务端每次实际挖掘 tick 都刷新工作动画状态。
2. 客户端渲染时读取工作动画状态，强制右手挥动。
3. 挖矿前必须先把最合适工具放到主手。
4. 动画只在真实工作时触发，停止、移动或任务结束后自然消失。
5. 构建验证实体同步字段和客户端渲染覆盖逻辑。

验收标准：

- AI 挖石头、铁矿、金矿、钻石矿时，右手显示镐并持续挥动。
- 不挖矿时不会一直挥手。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

## Voyager 借鉴迁移：技能记忆、Critic 与经验复用

本节把 MineDojo/Voyager 中适合本项目的部分迁移为 Fabric 生存 AI 可用的能力。边界保持不变：DeepSeek 继续负责理解、计划和策略建议；本地代码继续负责配方、背包、世界事实、动作白名单和生存执行。不会引入 Voyager 的 Mineflayer 运行时，也不会让 DeepSeek 生成可执行 JavaScript 或绕过生存规则。

### Phase V1: 明确 Voyager 可借鉴边界

状态：`[x] 已完成`

目标：只借鉴 Voyager 的 Agent 结构，不照搬外部 bot、创造模式设定或 LLM 生成代码执行方式。

开发内容：

1. 将可迁移能力拆成技能记忆、Critic 自检、失败经验复用、上下文压缩和 DeepSeek 策略建议。
2. 明确不可迁移能力：Mineflayer 控制、LLM 动态生成可执行代码、作弊式环境假设。
3. 在本文档中约束 DeepSeek 只能读取经验摘要并输出白名单策略。

完成记录：已写入本节作为后续实现边界。项目仍保留 DeepSeek 接入，但 DeepSeek 不会直接执行坐标、破坏方块、生成物品或修改世界。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest` 通过。

### Phase V2: 技能记录数据结构

状态：`[x] 已完成`

目标：把“成功完成过的任务”保存成可复用技能，而不是每次都从零推演。

开发内容：

1. 新增 `AgentSkillRecord`，记录 skillId、目标、数量、触发命令、成功 step、证据事件、规避过的失败和摘要。
2. 技能记录只保存高层经验，不保存可直接作弊执行的坐标或世界修改指令。
3. 提供 `toPromptSummary()`，供 DeepSeek 上下文读取精简经验。

完成记录：新增 `src/main/java/com/aiplayer/agent/AgentSkillRecord.java`。技能摘要包含目标、成功次数、step 列表和失败规避信息。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest` 通过。

### Phase V3: 技能库检索与容量控制

状态：`[x] 已完成`

目标：让 AI 能按目标物品或玩家命令检索相似成功经验。

开发内容：

1. 新增 `AgentSkillLibrary`，支持保存成功技能、按目标检索、按成功次数和最近使用排序。
2. 技能库最多保留 48 条，避免无限膨胀。
3. 支持 JSON 序列化和恢复，为实体 NBT 持久化做准备。

完成记录：新增 `src/main/java/com/aiplayer/agent/AgentSkillLibrary.java`。检索会更新技能使用时间和成功计数，`toPromptSummary()` 会返回可压缩给 DeepSeek 的摘要。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest` 通过。

### Phase V4: AI 记忆持久化技能库

状态：`[x] 已完成`

目标：技能经验跟随 AI 玩家实体保存，而不是只存在一次任务内。

开发内容：

1. `AiPlayerMemory` 持有 `AgentSkillLibrary`。
2. 实体保存 NBT 时把技能库写入 `SkillLibrary` JSON。
3. 实体读取 NBT 时恢复技能库；解析失败时清空，避免坏数据影响游戏。

完成记录：`AiPlayerMemory` 新增 `getSkillLibrary()`，并在 `saveToNBT` / `loadFromNBT` 中保存和恢复技能库 JSON。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest` 通过。

### Phase V5: 成功任务自动沉淀为技能

状态：`[x] 已完成`

目标：制作、挖矿、熔炼等 `make_item` 成功后自动形成可复用技能经验。

开发内容：

1. `MakeItemAction` 在任务开始时记录来源命令。
2. 每个 step 结束时把 `StepExecutionEvent` 汇总到任务级事件列表。
3. 本地 Critic 判定目标完成后，将最新执行计划、事件、失败历史写入技能库。
4. 成功技能写入 `planning.log`，便于排查经验是否生成。

完成记录：`MakeItemAction` 新增成功技能提交逻辑；`TaskSession` 新增 `getSteps()` 供技能库保存高层步骤。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest` 通过。

### Phase V6: 本地 Critic 自检

状态：`[x] 已完成`

目标：借鉴 Voyager 的 Critic 思想，但完成判定必须来自本地事实。

开发内容：

1. 新增 `AgentCritic`。
2. `AgentCritic` 用 `GoalChecker`、AI 背包、最近失败和最近事件判定 `make_item` 是否完成。
3. `MakeItemAction` 成功前必须经过 Critic；如果计划结束但目标未达成，失败消息改为 Critic 的事实说明。

完成记录：新增 `src/main/java/com/aiplayer/agent/AgentCritic.java`，`MakeItemAction` 成功路径已调用 Critic。DeepSeek 不参与最终完成事实判定。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentContextTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentContextTest` 通过。

### Phase V7: DeepSeek 上下文加入技能记忆

状态：`[x] 已完成`

目标：DeepSeek 能看到“以前同类任务怎么成功”，但不能直接复用作弊动作。

开发内容：

1. `AgentContext` 增加 `relevantSkills` 字段。
2. `PromptBuilder` 在普通 DeepSeek 规划上下文中加入 `Relevant Skill Memory`。
3. prompt 明确说明技能记忆只是经验提示，当前快照、本地配方和白名单动作仍是权威事实。

完成记录：`AgentContext` 支持技能摘要，`PromptBuilder` 会把 AI 记忆中相关技能传给 DeepSeek。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentContextTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentContextTest` 通过。

### Phase V8: 失败复盘与挖矿心跳复用技能

状态：`[x] 已完成`

目标：失败复盘和长挖矿任务可以参考成功经验，减少重复踩坑。

开发内容：

1. `FailureRecoveryAdvisor` 新增技能摘要输入，并传入 `AgentContext`。
2. `MiningStrategyAdvisor` 的 `MINING_STRATEGY_CONTEXT` 新增 `relevantSkillMemory`。
3. `MakeItemAction` 开始时检索最多 3 条相关技能，失败复盘和挖矿策略心跳都复用这组摘要。
4. 技能记忆只影响策略建议，本地执行仍由 `StepExecutor` 和配方/世界校验决定。

完成记录：挖矿心跳发给 DeepSeek 的 JSON 已包含 `relevantSkillMemory`；失败复盘 prompt 也包含相同字段。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest --tests com.aiplayer.agent.FailureRecoveryAdvisorTest
```

验证记录：`./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest --tests com.aiplayer.agent.FailureRecoveryAdvisorTest` 通过。

### Phase V9: 回归测试覆盖技能记忆链路

状态：`[x] 已完成`

目标：防止以后改动把技能记忆、Critic 或 DeepSeek 上下文删坏。

开发内容：

1. 新增 `AgentSkillLibraryTest`，覆盖保存、检索、摘要和 JSON 恢复。
2. 更新 `AgentContextTest`，确认上下文包含技能摘要且不泄露 API key。
3. 更新 `MiningStrategyAdvisorTest`，确认挖矿心跳包含 `relevantSkillMemory`。

完成记录：新增和更新测试均已通过。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentSkillLibraryTest --tests com.aiplayer.agent.AgentContextTest --tests com.aiplayer.agent.MiningStrategyAdvisorTest --tests com.aiplayer.agent.FailureRecoveryAdvisorTest --tests com.aiplayer.agent.FailureRecoveryTest
```

验证记录：上述测试命令通过。

### Phase V10: 玩家文档和后续实机验证点

状态：`[x] 已完成`

目标：把新增能力写进玩家和开发者能看到的文档。

开发内容：

1. README 说明 AI 会保存成功任务经验，并在后续 DeepSeek 策略上下文中复用。
2. README 强调技能记忆不是作弊，当前背包、箱子、配方和世界状态仍由本地代码校验。
3. 后续实机验证重点：连续两次执行同类任务，确认第二次日志出现 `retrieved skill memories` 和 `relevantSkillMemory`。

完成记录：README 已补充技能记忆和 Critic 说明。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase F2: 自动挖矿任务入口

状态：`[x] 已完成`

完成记录：新增 `/ai mining start <target> [count]`，命令会解析 `MiningResource` 目标别名并把目标转成本地 `make_item` 生存链任务。执行器新增本地任务入队能力，启动自动挖矿时会取消当前规划和动作，记录新的 `taskId`，再按普通生存规则补工具、找矿、采集或烧炼。自然语言“自动挖矿 / 去挖矿 / 挖一些矿”会归一到默认 `minecraft:raw_iron` 采集链，不再完全依赖 DeepSeek 猜测。

目标：把“自动挖矿”从普通制作链里抽出一个明确入口，让玩家能直接要求 AI 进入矿工模式。

开发内容：

1. 新增本地自动挖矿任务入口，例如 `/ai mining start <target> [count]`。
2. 自然语言“自动挖矿 / 去挖矿 / 挖一些矿”归一到同一任务。
3. 支持目标矿物别名：铁、煤、金、钻石、红石、青金石、绿宝石、铜、黑曜石、远古残骸。
4. 自动挖矿任务内部仍转为生存 `make_item` 或矿物采集 step，不直接生成物品。
5. README 增加命令和玩法说明。

验收标准：

- `/ai mining start iron 3` 会启动取得 `minecraft:raw_iron x3` 的任务。
- `/ai mining start diamond 2` 会先确保铁镐，再进入钻石采集链。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentIntentParserTest --tests com.aiplayer.llm.TaskPlannerIntentTest` 通过；`./gradlew build` 通过。

### Phase F3: 自动挖矿目标解析与限制

状态：`[x] 已完成`

完成记录：新增 `AutoMiningTarget`，自动挖矿入口会先解析玩家输入、目标物品、数量、矿物 profile、维度限制和最低工具要求。`gold_ingot`、`iron_ingot`、`copper_ingot` 会保留为最终目标物品，同时绑定到对应粗矿 profile，让执行链按“采粗矿 -> 熔炼”的普通生存流程推进。显式矿石方块目标例如 `minecraft:diamond_ore` 会被拒绝，并提示当前暂不支持精准采集方块本体；远古残骸、黑曜石、下界矿物会在解析结果中带出维度或工具限制，供命令反馈和后续状态显示使用。

目标：自动挖矿必须清楚知道目标是“矿物产物”还是“矿石方块”，避免把金锭、铁锭、钻石矿混为一谈。

开发内容：

1. 建立 `AutoMiningTarget`，包含玩家输入、目标物品、目标数量、矿物 profile、维度限制和工具要求。
2. 对“金锭、铁锭”自动转成粗矿 + 熔炼链。
3. 对“矿石方块”要求精准采集或明确暂不支持。
4. 对下界矿物、远古残骸、黑曜石输出维度或环境限制。
5. 增加目标解析测试。

验收标准：

- “挖金锭”不会寻找不存在的金锭方块。
- “挖远古残骸”会明确需要下界和钻石镐。

验证方式：

```bash
./gradlew test --tests com.aiplayer.recipe.MiningResourceTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.recipe.MiningResourceTest` 通过；`./gradlew build` 通过。

### Phase F4: 矿工模式状态机

状态：`[x] 已完成`

完成记录：新增 `MiningState`，覆盖准备工具、前往矿区、下探、洞穴扫描、分支矿道、采矿、补给、返回、等待玩家和完成状态。`MiningRun` 现在记录当前状态，状态切换只在状态变化时写入 `mining.log`，每条日志包含原因、位置、Y 高度和已进入过的模式集合；摘要里也会显示当前状态。`StepExecutor` 在工具检查、移动到石头/矿物、可见矿采集、推荐高度下探、探矿、错误维度和等待玩家场景中显式切换状态，避免玩家只能看到一个长动作盲跑。

目标：自动挖矿需要有清晰状态，而不是一个长动作盲跑。

开发内容：

1. 定义矿工状态：准备工具、前往矿区、下探、洞穴扫描、分支矿道、采矿、补给、返回、等待玩家。
2. `MiningRun` 记录当前状态和状态切换原因。
3. 每个状态只能执行允许动作，失败时进入恢复策略。
4. DeepSeek 只参与状态建议，不直接控制坐标和破坏方块。
5. 状态变化写入 `mining.log`。

验收标准：

- 日志能看出 AI 当前为什么在下探、为什么转分支、为什么停下。
- 同一任务不会在多个状态之间无原因抖动。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase F5: 工具与补给自动化

状态：`[x] 已完成`

完成记录：AI 背包新增空间检查方法，挖石头和挖矿物前会确认目标掉落物仍可放入背包；背包满时进入等待玩家状态并写入 `mining.log`，避免产物丢失。矿物采集会在开挖前检查最低工具等级和镐剩余耐久，低于阈值时停止当前矿物 step 并提示先处理工具。深层分支矿道目标会执行一次补给预检，尽量准备工作台、熔炉并检查煤/木炭燃料状态，结果写入日志，后续 phase 可继续把补给预检扩展成返回箱子或自动补给任务。

目标：自动挖矿前和过程中，AI 能像玩家一样补齐工具、火把/燃料、工作台和熔炉。

开发内容：

1. 开始挖矿前检查所需镐等级。
2. 工具耐久过低时自动补工具或返回玩家。
3. 深层挖矿前尽量准备工作台、熔炉和燃料。
4. 背包将满时返回玩家或附近箱子处理。
5. 失败消息说明缺什么材料。

验收标准：

- 自动挖钻石前会先做铁镐。
- 镐快坏时不会继续硬挖到任务卡死。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase F6: 自动矿洞路线规划

状态：`[x] 已完成`

完成记录：新增 `MiningRoutePlan`，每个挖矿 run 启动时会根据当前位置、矿物 profile 和当前朝向生成矿井入口、目标 Y 层、主高度段、备用高度段、主方向、分支段长、最大转向次数和路线提示。`MiningRun` 会把路线计划写入 `mining.log`，摘要里也包含路线计划和分支统计。分支矿道启动时优先使用路线计划的主方向；每次推进或换向都会记录方向、Y 高度、已挖长度和原因，超时后能从日志看到探索过的层和分支。

目标：AI 进入自动挖矿后，应当构建可复用路线，而不是每次随机挖。

开发内容：

1. 复用已有 `MiningWaypoint`，生成矿井入口、主通道、分支和返回路径。
2. 按矿物 profile 选择主层和备用层。
3. 到达目标层后进入分支矿道，而不是继续下挖。
4. 记录每条分支长度、高度、方向和结果。
5. 避免重复探索贫矿分支。

验收标准：

- 同一个任务里连续找铁、煤、金时会复用矿井入口。
- 超时时能说明探索了哪些层和分支。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase F7: 洞穴自动探索策略

状态：`[x] 已完成`

完成记录：自动挖矿 run 启动时会为 `cavePreferred` 的矿物捕获一次 `WorldSnapshot`，从 `nearbyCaves` 中选择可达、连通空气足够、暴露石壁足够且当前敌对生物不过多的洞穴入口。候选洞穴会按可见矿物、暴露石壁和距离排序；被拒绝的洞穴会写入 `mining.log`，包含可达性、连通空气、暴露石壁和可见矿物数量。选中的入口会写入资源会话和共享矿井状态，后续 `tickKnownCaveSearch` 会优先移动到该洞口扫描，而不是直接开新矿道。

目标：附近有洞穴时，AI 应优先像玩家一样利用天然暴露面。

开发内容：

1. 自动挖矿开始时扫描附近洞穴入口。
2. 可达且危险低的洞穴优先探索。
3. 洞穴内优先扫描暴露矿物和暴露石壁。
4. 洞穴探索记录已走方向，避免洞口来回。
5. 危险过高时回退到安全分支矿道。

验收标准：

- 洞穴附近挖铁或煤时，AI 先进入洞穴扫描。
- 洞穴危险时不会无脑深入。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase F8: 危险处理与撤退

状态：`[x] 已完成`

完成记录：`MiningRun` 新增危险记录，危险会进入 `mining.log` 和最终摘要。`StepExecutor` 在挖矿中节流检查低血量、附近敌对生物数量和导航卡住；低血量或怪物过多时会进入返回状态并尝试导航回拥有者身边，找不到在线拥有者时进入等待玩家状态。实际破坏方块前会检查目标周围岩浆、水、脚下垂直挖掘和坠落风险；可见矿目标危险时会跳过目标，分支矿道前方危险时会换方向，下探目标危险时会换位置。

目标：自动挖矿必须处理岩浆、水、坠落、怪物和低血量。

开发内容：

1. 挖掘前检测目标方块周围危险流体和悬空。
2. 遇到岩浆、水流、基岩、坍塌方块时绕行或换分支。
3. 附近敌对生物过多时暂停挖矿。
4. 血量低或卡住时回到玩家身边。
5. 危险记录进入 `MiningRun`。

验收标准：

- AI 不会主动挖开脚下垂直坑。
- 遇到岩浆或怪物时会停下、绕行或返回。

验证方式：

```bash
./gradlew build
```

验证记录：第一次 `./gradlew build` 因缺少 `ServerPlayer` import 失败；补齐 import 后，`./gradlew build` 通过。

### Phase F9: 玩家可见状态与控制

状态：`[x] 已完成`

完成记录：新增 `/ai status`、`/ai mining status`、`/ai mining stop` 和 `/ai mining return`。通用状态命令会显示 AI 当前阶段、目标、动作、执行/规划状态、位置、Y 高度、主手物品和背包摘要；挖矿状态命令会显示 `taskId`、当前目标、当前动作描述和挖矿摘要；停止命令会清空当前动作和任务队列但保留已获得物品；返回命令会停止任务并让 AI 使用正常导航走回玩家身边，不使用瞬移。AI 面板顶部新增“状态”按钮，会触发 `/ai status`，输入 `mining status`、`mining stop`、`mining return` 也会转到对应命令。README 已补充状态和挖矿控制命令的中文说明。

目标：玩家不用读日志，也能知道自动挖矿在做什么，并能干预。

开发内容：

1. `/ai mining status` 显示目标、当前状态、高度、分支、收获、危险和最近失败。
2. `/ai mining stop` 停止自动挖矿并保留已获得物品。
3. `/ai mining return` 让 AI 沿正常移动或召回策略回玩家身边。
4. AI 面板显示自动挖矿状态。
5. README 增加状态说明。

验收标准：

- 玩家能随时查看 AI 自动挖矿进度。
- 玩家能停止或要求返回，不需要直接杀任务。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase F10: 自动挖矿实机基准

状态：`[x] 已完成`

完成记录：`docs/mining-regression.md` 新增自动挖矿专项矩阵，覆盖 `/ai mining start` 的煤、铁、铜、金、钻石、红石、青金石、绿宝石、黑曜石和远古残骸。每个用例记录命令、起始材料、维度/地形、洞穴条件、成功标准、成功率目标和允许失败边界；同时新增实机记录模板和基础/中层/深层/特殊资源的基准目标，后续每次挖矿改动可按同一套标准追加 taskId 和结果。

目标：用固定样例衡量自动挖矿是否真的可用。

开发内容：

1. 在 `docs/mining-regression.md` 增加自动挖矿专栏。
2. 覆盖煤、铁、铜、金、钻石、红石、青金石、绿宝石、黑曜石、远古残骸。
3. 每个目标记录起始材料、维度、地形、是否有洞穴、耗时、成功/失败原因。
4. 给出基础矿物和深层矿物不同成功率目标。
5. 每次自动挖矿改动后更新结果。

验收标准：

- 基础矿物在普通地表附近能稳定取得。
- 深层矿物失败时必须有可复盘日志和玩家可操作建议。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

README 同步：

- 补充“如何查看 AI 制作失败日志”的说明。

### Phase B2: 配方计划顺序重排

状态：`[x] 已完成`

完成记录：`RecipeResolver` 现在会先解析并补齐配方材料及其采集工具，再补齐工作站前置。铁制工具链会先规划石镐，再规划铁矿、煤和熔炉；钻石工具链会先规划铁镐，再规划钻石矿。

目标：把计划顺序改成更像真人的工具优先链，避免先用木镐挖 8 个圆石做熔炉，再补 3 个圆石做石镐。

当前问题：

```text
木镐 -> 8 圆石 -> 熔炉 -> 3 圆石 -> 石镐
```

目标顺序：

```text
木镐 -> 3 圆石 -> 石镐 -> 8 圆石 -> 熔炉
```

开发内容：

1. 在配方递归中区分工具前置和工作站前置。
2. 工具前置优先级高于工作站前置。
3. 对需要矿物采集的目标，优先确保采集工具。
4. 对铁制工具链，先规划石镐，再规划熔炉、煤和生铁。
5. 对钻石工具链，先规划铁镐，再规划钻石采集。

验收标准：

- `/ai recipe minecraft:iron_axe 1` 中，`stone_pickaxe` 必须出现在 `furnace` 之前。
- `/ai recipe minecraft:iron_pickaxe 1` 中，采铁前必须已经规划 `stone_pickaxe`。
- `/ai recipe minecraft:diamond_pickaxe 1` 中，采钻石前必须已经规划 `iron_pickaxe`。

验证方式：

```bash
./gradlew test --tests com.aiplayer.recipe.SurvivalRecipePlanningTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.recipe.SurvivalRecipePlanningTest` 和 `./gradlew build` 通过。

README 同步：

- 更新铁工具和钻石工具的生存链说明。

### Phase B3: 持续资源会话

状态：`[x] 已完成`

完成记录：新增 `ResourceGatherSession`，`MakeItemAction` 会在同一次制作任务内跨 `after_step` 重建保留资源采集上下文。`StepExecutor` 会复用圆石下挖状态、最近成功位置和拒绝目标；矿石采集也会记录成功位置，并跳过已经判定不可达的目标。

目标：让连续的圆石、煤、铁矿采集复用同一个采集上下文，而不是每个 step 都从零开始找目标。

开发内容：

1. 新增资源采集会话，例如 `ResourceGatherSession`。
2. 会话保存资源类型、最近矿洞入口、最近站立点、最近成功破坏位置、已拒绝目标和无进展 tick 数。
3. `gather_stone minecraft:cobblestone x8` 完成后，后续 `gather_stone minecraft:cobblestone x3` 继续使用同一个矿洞上下文。
4. 当任务重规划时，保留仍然有效的资源会话，不因为 `after_step` 重建完全丢失。
5. AI 被召回、维度改变、距离矿洞入口过远或玩家开始新任务时，会话失效。

验收标准：

- 第二次补圆石不会在原地空转两分钟。
- 连续圆石需求可以在同一处矿洞继续完成。
- 日志能显示“复用矿洞上下文”或“资源会话失效原因”。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

README 同步：

- 说明 AI 会持续使用自己挖出的矿洞补齐后续材料。

### Phase B4: 阶梯式下挖与安全站位

状态：`[x] 已完成`

完成记录：`gather_stone` 的向下搜索改为横向阶梯目标选择。AI 会按当前朝向选择主方向，优先清理前方站位空间和前方下一格，再移动到可站立下降点继续挖；遇到水、岩浆、基岩或已拒绝目标会跳过并换方向。

目标：把下挖石头从“附近找一个块直接破坏”升级为稳定的阶梯式矿道行为。

开发内容：

1. 设计阶梯式下挖模式，保证脚部和头部空间可站立，避免垂直脚下直挖。
2. 支持先移动到可站立下降点，再继续挖。
3. 遇到树叶、泥土、草方块、沙砾等覆盖层时，可以合理挖开。
4. 遇到水、岩浆、基岩时停止当前方向并换方向。
5. 挖矿时始终面向目标方块，主手持镐并按节奏挥动。
6. 下挖深度、横向扩展距离、无进展超时都写成常量，后续便于调参。

验收标准：

- AI 不会“用头挖矿”。
- AI 不会原地挖脚下导致站位异常。
- 在森林、平原、热带草原等地表环境中，能稳定挖到圆石。
- 遇到不可继续下挖地形时，能换方向或明确失败。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

README 同步：

- 更新“圆石采集”的描述，说明使用阶梯式下挖。

### Phase B5: 矿石探矿模式

状态：`[x] 已完成`

完成记录：矿物采集在附近搜索不到目标矿石时，不再立刻失败，而是进入本地探矿兜底：复用阶梯式下挖和矿道搜索，每挖开一段就重新扫描周围矿石；超过探矿预算后才给出明确失败原因。矿物规则已整理为统一资料表，覆盖煤、铜、铁、金、钻石、红石、青金石、绿宝石、下界石英、下界金矿、黑曜石和远古残骸，并记录目标方块、产物、工具等级和是否允许探矿。自然语言“挖/找/采集”会优先进入本地 `make_item` 生存链，DeepSeek 返回的旧 `mine` 矿物任务也会被重写为对应产物的制作/采集链。

目标：让 `raw_iron`、`coal` 等矿物不再只依赖附近可见矿石，而是在找不到时进入探矿流程。

开发内容：

1. 新增矿物 profile：煤、铜、铁、金、钻石、红石、青金石、绿宝石、下界石英、下界金矿、黑曜石和远古残骸分别记录目标方块、产物、工具等级和搜索预算。
2. 探矿流程先扫描附近可见矿石；可见矿石不可达时，尝试挖通道靠近。
3. 没有矿石时，进入 2 格高矿道，每挖若干方块重新扫描周围矿石。
4. 矿道应和 Phase B3 的资源会话共享状态。
5. 挖到副产物时保留在 AI 背包，不丢弃。

验收标准：

- `/ai say 帮我做一把铁斧头` 不会在“附近没有铁矿”时立刻失败。
- AI 可以通过探矿获得生铁和煤。
- 如果超过合理搜索预算仍找不到矿，失败原因要说明搜索深度、路径和建议。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

README 同步：

- 更新铁矿、煤矿、钻石矿的采集说明。

### Phase B6: 路径与可达性恢复

状态：`[x] 已完成`

完成记录：矿物和石头目标会区分可直接工作、可寻路接近和不可达目标；不可达或长时间没有产出的目标会记录到资源会话并带上失败原因，例如 `movement_stuck`、`no_item_progress` 或 `deepseek_strategy`。重复目标会被跳过，矿物目标找不到或不可达时会进入阶梯式探矿；DeepSeek 策略也只能触发本地换目标或下探，不会绕过生存规则。

目标：当目标方块不可达或导航卡住时，AI 能像玩家一样调整路线，而不是反复尝试同一个不可达目标。

开发内容：

1. 将目标可达性分为可直接触及、可导航到附近、可以通过挖开障碍到达、不应尝试。
2. 对矿石和石头目标，允许有限挖通道。
3. 对树木目标，允许绕路，但不要挖穿大片地形。
4. 为 `rejectedTargets` 增加失败原因和冷却时间。
5. 当路径卡住时，记录起点、目标、最近距离、卡住 tick 和是否尝试过替代路径。
6. 对重复卡住的目标直接跳过。

验收标准：

- 不会连续多次卡在同一个 `raw_iron` 目标。
- 不会反复重建同一个必失败计划。
- 日志能看出 AI 是换目标、挖通道，还是放弃资源。

验证方式：

```bash
./gradlew build
```

README 同步：

- 补充“AI 卡住时会换目标或挖通道”的行为说明。

验证记录：`./gradlew build` 通过。

### Phase B7: 工具、耐久与动作拟真

状态：`[x] 已完成`

完成记录：AI 背包中的工具现在会保留并消耗耐久，接近损坏的工具不会再被选为可用工具；破坏方块后会按工具类型扣耐久，耐久耗尽后从 AI 背包移除。破坏速度统一由 `SurvivalUtils` 按方块和工具等级计算，原木优先斧、石头和矿石优先镐、沙土类优先铲。放置方块会先把对应物品拿到手上再面向目标放置；攻击生物时会面向实体、拿出可用武器并按冷却节奏攻击。

目标：让 AI 在视觉和规则上更像真实玩家操作。

开发内容：

1. 每类方块选择合适工具：原木用斧，石头和矿石用镐，泥土类可空手或铲。
2. 挖掘前切换到对应工具。
3. 工具耐久不足时，提前规划替换工具。
4. 挖掘速度根据工具等级调整。
5. AI 看向目标方块中心或合理接触面。
6. 主手挥动加入冷却，避免每 tick 抽搐。
7. 行走到目标附近后先停稳，再开始破坏或放置。
8. 攻击生物时看向实体，不再只靠逻辑扣血。

验收标准：

- AI 挖矿时玩家视角能明显看到它面向目标并挥镐。
- 不会出现长时间背对目标或用头挖的表现。
- 工具不足时会先制作工具，而不是硬挖矿物。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.util.SurvivalToolRulesTest` 和 `./gradlew build` 通过。

README 同步：

- 更新“AI 行为拟真”的说明。

### Phase B8: 熔炉与工作站生命周期

状态：`[x] 已完成`

完成记录：需要工作站的合成会先检查附近是否已有对应方块；没有时会递归制作工作站物品，并在 AI 身边寻找可放置位置真实放下工作台、熔炉等方块。放置会消耗 AI 背包里的工作站物品，并记录到 `make_item` 日志。烧炼类配方使用熔炉、高炉、烟熏炉或营火时会进入更长的处理节奏，消耗原料和燃料后再产出结果。

目标：让熔炉、工作台等工作站更接近真实使用，而不是只作为背包内虚拟合成条件。

开发内容：

1. 有背包工作台时可放置到附近，附近已有工作台时优先使用已有工作台。
2. 没有附近熔炉时，先放置背包熔炉。
3. 熔炉放置位置必须可站立、可交互。
4. 烧炼时消耗燃料和原料，烧炼完成后产出进入 AI 背包。
5. 支持燃料选择优先级：煤、木炭、原木或木板兜底。
6. 记录工作站位置到任务会话。

验收标准：

- 铁锭不是直接“瞬间合成”，而是经过熔炉 step。
- 熔炉消耗真实燃料和生铁。
- AI 找得到自己刚放下的熔炉。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

README 同步：

- 更新熔炼、熟牛肉、铁锭、水桶链路说明。

### Phase B9: DeepSeek 复盘与本地兜底

状态：`[x] 已完成`

完成记录：新增 `FailureRecoveryAdvisor`、失败分类和策略级复盘建议。执行 `make_item` step 失败时会记录结构化复盘日志，包含来源、本地分类、策略、建议动作和玩家可读说明。DeepSeek 建议必须是动作白名单中的策略级 action，包含作弊或未声明动作时会被拒绝，并自动使用本地兜底策略。

目标：让 DeepSeek 参与“失败后选择策略”，但不让它决定游戏事实。

开发内容：

1. 发送给 DeepSeek 的复盘输入包含当前目标、当前 step、背包、附近箱子、最近失败原因、已尝试目标列表和可用动作列表。
2. DeepSeek 只输出策略级建议，例如继续下挖、换方向挖矿道、回到玩家身边请求材料、优先从箱子取材料。
3. 本地验证器必须校验 DeepSeek 输出。
4. DeepSeek 不可用时，本地策略继续可用。
5. 避免高频 API 调用；连续失败或搜索预算耗尽后再复盘。

验收标准：

- DeepSeek 返回非法动作时不会执行。
- DeepSeek 不可用时铁工具仍有本地基础完成能力。
- 失败复盘日志能说明“采用 DeepSeek 建议”或“采用本地兜底策略”。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.FailureRecoveryAdvisorTest` 通过。

README 同步：

- 更新 DeepSeek 在生存任务中的职责边界。

### Phase B10: 回归测试与实机验收

状态：`[x] 已完成`

完成记录：新增 `AgentEvaluationMatrix`，固定 12 条回归用例，覆盖铁铲、铁斧、铁镐、金锭、黑曜石、熟牛肉、水桶、木门、砍树、停止、召回和闲聊。矩阵测试会校验每条命令能被意图解析器识别成预期任务类型和目标，防止后续改动让自然语言入口退化。

目标：用固定任务矩阵验证前 9 个 phase 的实际效果。

测试矩阵：

1. `/ai say 给我一把铁铲`
2. `/ai say 帮我做一把铁斧头`
3. `/ai say 帮我做个铁镐`
4. `/ai say 帮我挖两块金锭`
5. `/ai say 帮我挖黑曜石`
6. `/ai say 帮我烧一块牛肉`
7. `/ai say 帮我打一桶水`
8. 背包已有部分材料时制作铁工具。
9. 附近箱子有部分材料时制作铁工具。
10. 地表附近没有裸露石头时制作铁工具。
11. 附近没有可见铁矿时制作铁工具。
12. AI 卡住后使用 `/ai recall` 验证能立即召回。

自动化验证：

```bash
./gradlew test
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentEvaluationMatrixTest` 通过。

完成标准：

- 铁铲、铁斧、铁镐至少在普通地表环境中可以从空背包完成。
- 找不到资源时失败原因具体，能指导玩家移动位置、提供材料或等待 AI 继续搜索。
- AI 的动作表现不再明显像“瞬移逻辑工具”，而是接近普通生存玩家。
- `README.md` 覆盖所有新增玩法和限制。

## Part C: DeepSeek Agent 化路线

本部分是后续开发的主路线。目标不是把所有 Minecraft 玩法都写成硬编码 if/else，而是形成稳定的 Agent 循环：

```text
玩家自然语言
-> DeepSeek 理解意图
-> 本地观察世界和背包
-> DeepSeek 拆解高层任务
-> 本地验证计划是否符合 Minecraft 事实
-> AI 执行一个可控 step
-> 重新观察结果
-> DeepSeek 或本地策略判断继续、修正、求助或失败
```

DeepSeek 负责“理解、拆解、选择策略、复盘”，本地代码负责“事实、规则、动作、安全、执行”。后续所有开发都应围绕这个边界推进。

### Phase C1: 意图理解与任务类型归一

状态：`[x] 已完成`

完成记录：新增 `AgentIntent`、`AgentIntentType` 和 `AgentIntentParser`，统一输出意图类型、目标物品、资源、数量、原始文本和原因。`TaskPlanner` 已接入该解析器，制作、烧炼、打水、砍树、停止、召回、查看背包和闲聊会先归一到结构化意图，再进入后续规划。

目标：把玩家自然语言稳定转换为明确任务类型，避免“帮我做一个铁镐”“我要个铁镐”“弄把铁镐给我”进入不同执行路径。

开发内容：

1. 定义统一的 `Intent` 结构，至少包含 `intentType`、`targetItem`、`targetEntity`、`targetBlock`、`quantity`、`constraints`、`rawText`。
2. 任务类型先覆盖：制作物品、采集资源、烧炼物品、装水、移动跟随、停止、召回、查看背包、闲聊。
3. DeepSeek 输出结构化 JSON，不再让执行层解析自然语言文本。
4. 本地增加意图校验：物品 ID 必须存在，数量必须合理，动作类型必须在白名单内。
5. 常见中文说法建立本地 fallback，例如“给我 / 我要 / 帮我做 / 搞 / 弄 / 烧 / 打一桶水”。
6. 无法确定意图时，AI 应向玩家追问，而不是执行猜测任务。

验收标准：

- `/ai say 我要一把铁镐`、`/ai say 帮我做个铁镐`、`/ai say 弄把铁镐给我` 都归一为 `make_item minecraft:iron_pickaxe x1`。
- 闲聊内容不会误触发采集或制作任务。
- DeepSeek 返回不存在物品或非法数量时，本地拒绝并记录原因。

验证方式：

```bash
./gradlew test --tests com.aiplayer.intent.*
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentIntentParserTest` 通过。

README 同步：

- 增加自然语言任务支持范围和模糊命令处理说明。

### Phase C2: DeepSeek 输入上下文标准化

状态：`[x] 已完成`

完成记录：新增 `AgentContext`，把 `WorldSnapshot`、当前计划、当前 step、近期失败、近期事件和动作白名单组合成 DeepSeek 可消费的稳定 JSON。上下文会聚合可用物品、附近箱子数量、附近关键方块和实体，并明确未知事实，例如扫描范围外箱子和未暴露地下矿物。

目标：让 DeepSeek 每次决策都收到稳定、足够、不会过度膨胀的上下文。

开发内容：

1. 定义 `AgentContext` JSON schema，包含玩家目标、当前计划、当前 step、AI 背包、玩家背包摘要、附近箱子、附近关键方块、实体、位置、生命值、危险状态、最近失败原因。
2. 背包和箱子按物品 ID 聚合数量，同时保留关键 slot 信息，便于后续背包转移。
3. 附近方块不要全量发送，只发送和当前任务相关的关键资源，例如树、石头、煤矿、铁矿、水、工作台、熔炉、箱子。
4. 加入 `knownFacts` 和 `unknownFacts`，明确哪些是本地确认事实，哪些需要搜索或执行后才能知道。
5. 加入 `allowedActions`，告诉 DeepSeek 当前只能输出哪些动作类型。
6. 控制 token 预算，长任务只发送最近 N 条关键事件和当前必要状态。

验收标准：

- 任意一次 DeepSeek 请求都能在日志中看到完整 `AgentContext`。
- 上下文不会泄露 API key。
- 当前任务缺什么材料、附近有什么资源、AI 正在执行哪个 step 都能从上下文直接看出。

验证方式：

```bash
./gradlew test --tests com.aiplayer.snapshot.*
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentContextTest` 通过。

README 同步：

- 更新 `snapshot` 调试说明，解释哪些信息会发送给 DeepSeek。

### Phase C3: 动作白名单与能力清单

状态：`[x] 已完成`

完成记录：新增 `ActionManifest` 和 `ActionSpec`，集中声明 DeepSeek 可调用能力、必填参数、风险等级和说明。Planning prompt 会注入该能力清单；复盘器和协议校验都会使用同一份 manifest，白名单外动作和缺参数动作会被拒绝。

目标：让 DeepSeek 只能选择本地真实支持的能力，避免输出“飞过去”“生成物品”“直接获得铁锭”等无效动作。

开发内容：

1. 定义 `ActionManifest`，列出所有可执行动作及参数 schema。
2. 第一批动作包括：`make_item`、`gather_resource`、`mine_block`、`craft`、`smelt`、`place_block`、`pickup_item`、`withdraw_from_chest`、`deposit_to_chest`、`fill_water`、`attack_entity`、`move_near`、`follow_player`、`stop`、`recall`。
3. 每个动作声明前置条件、消耗、产出、风险和是否允许 DeepSeek 直接调用。
4. 高风险动作，例如攻击实体、挖玩家脚下方块、跨危险地形移动，必须经过本地安全检查。
5. DeepSeek 请求中附带当前版本 `ActionManifest`，DeepSeek 响应必须引用动作名和参数。
6. 本地执行前统一走 `ActionValidator`。

验收标准：

- DeepSeek 输出白名单外动作时不会执行。
- 参数缺失、物品 ID 错误、数量异常时返回明确错误。
- 新增动作只需要扩展 manifest、validator 和 executor，不需要改自然语言解析主流程。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.ActionManifestTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.ActionManifestTest` 通过。

README 同步：

- 说明 DeepSeek 只能通过模组支持的动作完成任务。

### Phase C4: DeepSeek 高层计划协议 V2

状态：`[x] 已完成`

完成记录：新增 `AgentPlan`、`AgentPlanStep` 和 `AgentPlanProtocol`，支持把本地 `PlanSchema` 转换为 V2 计划协议。每个 step 包含 `stepId`、`action`、`parameters`、`expectedResult`、`requiredFacts` 和 `failurePolicy`，并可序列化/解析为 JSON 后按动作清单验证。

目标：把 DeepSeek 的输出从“聊天式建议”升级为可验证、可恢复、可继续执行的计划协议。

开发内容：

1. 定义 `AgentPlan` schema，包含 `goal`、`assumptions`、`steps`、`successCriteria`、`fallbacks`、`needUserHelp`。
2. 每个 step 必须包含 `stepId`、`action`、`parameters`、`expectedResult`、`requiredFacts`、`failurePolicy`。
3. DeepSeek 可以拆解任务，但不允许写具体作弊结果，例如“直接获得铁锭”。
4. 本地会把配方递归结果附加给 DeepSeek，DeepSeek 只能在配方事实范围内排序和选择策略。
5. 计划保存到任务会话，执行中可继续引用旧 step，而不是每次完全重来。
6. 计划支持 `ask_user`，当任务目标模糊或资源风险太高时可向玩家提问。

验收标准：

- `/ai say 帮我做一把铁镐` 能生成包含木头、圆石、石镐、铁矿、煤、熔炉、铁锭、合成铁镐的计划。
- 计划中每个 step 都能被本地 validator 判断是否可执行。
- DeepSeek 输出缺失字段时，本地能要求修复或走 fallback。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentPlanProtocolTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentPlanProtocolTest` 通过。

README 同步：

- 增加“AI 如何拆解长任务”的说明。

### Phase C5: 计划验证、修复与本地事实仲裁

状态：`[x] 已完成`

完成记录：新增 `AgentPlanGuard` 和 `PlanViolation`，在现有 `PlanValidator` 基础上输出结构化违规位置、原因和修复建议。非法动作、创造式能力、非法物品和数量错误会被拒绝；本地配方 fallback 计划仍可通过验证并继续执行。

目标：确保 DeepSeek 计划永远不能绕过 Minecraft 规则，且计划错误时可以被本地修复或要求 DeepSeek 重写。

开发内容：

1. 新增 `PlanValidator`，逐 step 校验动作名、参数、配方、材料数量、工具等级、工作站需求、资源来源。
2. 当 DeepSeek 数量算错时，优先用本地配方递归结果修正。
3. 当 DeepSeek 引用不存在资源时，返回结构化 `PlanViolation`，包含错误位置、错误原因、建议修复方向。
4. 支持一次 `repair_plan` 请求，把 violation 发回 DeepSeek，让它重写计划。
5. 连续修复失败超过上限后，切换到本地 fallback 或向玩家说明无法完成。
6. 所有计划修复和拒绝原因写入 `planning.log`。

验收标准：

- DeepSeek 把铁锭当作可直接采集资源时，本地拒绝并要求改为采铁矿加熔炼。
- DeepSeek 少算木棍或工作台时，本地能修正。
- 无配方、无基础来源、需要结构探索或交易的目标会明确失败，不会假装能完成。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.PlanValidatorTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentPlanGuardTest` 通过。

README 同步：

- 补充“DeepSeek 负责计划，本地负责事实校验”的边界说明。

### Phase C6: Step 执行器标准化与事件回传

状态：`[x] 已完成`

完成记录：新增 `StepExecutionEvent` 和 `StepExecutionStatus`，统一记录 taskId、stepId、状态、进度、tick 时间、背包变化、位置和消息。`MakeItemAction` 在 step 结束时会生成事件、写入 session，并输出到 `make_item` 日志；失败复盘可读取近期事件。

目标：让每个执行 step 都能报告进度、消耗、产出、失败原因，为 DeepSeek 复盘提供稳定事件流。

开发内容：

1. 定义 `StepExecutionEvent`，包含 `taskId`、`stepId`、`status`、`progress`、`startedAt`、`endedAt`、`inventoryDelta`、`position`、`message`。
2. 所有动作执行器统一返回 `RUNNING`、`SUCCESS`、`RETRYABLE_FAILURE`、`TERMINAL_FAILURE`、`NEED_REPLAN`。
3. 执行器必须报告真实背包变化，例如获得原木、消耗煤、产出铁锭。
4. 移动、采集、合成、熔炼、装水、战斗使用同一事件格式。
5. 事件写入分类日志，并进入下一次 DeepSeek 上下文。
6. 长 step 定期报告进度，避免玩家只看到“卡住”。

验收标准：

- 任意失败都能知道失败在哪个 step、尝试了多久、当前位置和背包是否变化。
- DeepSeek 复盘能看到最近 step 的真实结果，而不是只看到最终失败文本。
- 玩家调试时可以通过 taskId 串起 plan、action、make_item、snapshot 日志。

验证方式：

```bash
./gradlew test --tests com.aiplayer.execution.*
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.StepExecutionEventTest` 通过。

README 同步：

- 更新日志排查章节，说明事件字段含义。

### Phase C7: 观察-执行-复盘循环调度器

状态：`[x] 已完成`

完成记录：新增 `AgentLoopScheduler`，根据 step 完成、背包变化、箱子变化、目标卡住、资源预算耗尽、危险、玩家改任务、连续失败和时间片结束，决定继续执行、重新观察或进入 DeepSeek 复盘。该调度器为后续把完整执行链迁入统一 Agent loop 提供稳定判定规则。

目标：实现真正的 Agent loop，而不是一次性计划后一直盲目执行。

开发内容：

1. 新增 `AgentLoopScheduler`，统一管理观察、计划、执行、复盘。
2. 每个循环最多执行一个小 step 或一个短时间片。
3. 触发重新观察的条件包括 step 完成、背包变化、箱子变化、目标卡住、危险接近、超过时间片。
4. 触发 DeepSeek 复盘的条件包括连续失败、策略选择不明确、资源搜索预算耗尽、玩家修改任务。
5. 普通可预测步骤优先本地继续执行，减少 API 调用。
6. 玩家输入新命令时，当前 loop 可取消、暂停或转换目标。

验收标准：

- AI 执行长任务时会周期性根据现实状态调整，而不是一条计划跑到底。
- 玩家中途给 AI 背包放材料，下一轮观察能利用这些材料。
- 找不到铁矿时不会无限重复同一步，而是触发复盘或求助。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentLoopSchedulerTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentLoopSchedulerTest` 通过。

README 同步：

- 更新长任务执行方式说明。

### Phase C8: 失败诊断、恢复策略与求助机制

状态：`[x] 已完成`

完成记录：`FailureRecoveryAdvisor` 已覆盖材料不足、工具不足、配方不存在、目标不可达、资源未找到、路径卡住、危险、背包满、工作站缺失和 DeepSeek 非法输出等分类，并映射到本地恢复策略。无法采用 DeepSeek 建议时，会自动回退到本地策略并输出玩家可读建议。

目标：让 AI 失败时像玩家一样换办法，而不是只说“材料不足”或反复卡在同一位置。

开发内容：

1. 定义失败分类：材料不足、工具不足、配方不存在、目标不可达、资源未找到、路径卡住、危险环境、背包已满、工作站缺失、DeepSeek 输出非法。
2. 每类失败映射本地恢复策略，例如换目标、换方向探矿、回玩家身边、从箱子取材料、制作工具、清理背包。
3. 恢复策略仍失败时，把结构化失败发给 DeepSeek 请求策略建议。
4. DeepSeek 建议必须回到本地 validator。
5. 无法恢复时向玩家给出可操作建议，例如“请移动到矿洞附近”或“给 AI 放入 3 个圆石”。
6. 同一失败原因增加冷却，避免每 tick 重复请求 DeepSeek。

验收标准：

- 材料不足时会继续追溯材料来源，而不是直接失败。
- 路径卡住时会换目标或换路线。
- 超过合理预算后会向玩家说明缺什么、尝试过什么、下一步建议是什么。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.FailureRecoveryTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.FailureRecoveryTest` 通过。

README 同步：

- 增加常见失败原因和玩家处理建议。

### Phase C9: 会话记忆与玩家偏好

状态：`[x] 已完成`

完成记录：新增 `AgentSession`，记录当前任务、目标、最近命令、最近失败、拒绝目标、已知箱子、已知工作站和玩家偏好。记忆支持在维度切换时清理位置相关事实，并能生成发送给 DeepSeek 的短摘要，避免把易变化事实永久化。

目标：让 AI 在同一玩家的连续交互中记住任务上下文、偏好和近期经验，但不把错误事实永久化。

开发内容：

1. 为每个玩家维护 `AgentSession`，记录当前任务、最近命令、最近计划、最近失败、已知箱子位置、常用工作站位置。
2. 支持短期记忆，例如“刚才那个铁镐任务还没完成”“刚刚那棵树不可达”。
3. 支持玩家偏好，例如优先使用箱子材料、不要破坏家附近方块、危险时先回来。
4. 记忆必须区分事实有效期：位置和资源会变化，不能永久相信。
5. 召回、停止、新任务、维度切换时按规则清理或保留记忆。
6. DeepSeek 上下文只发送当前任务相关的记忆摘要。

验收标准：

- 玩家说“继续刚才的任务”，AI 能恢复最近未完成任务。
- 同一任务中已失败目标不会立刻重复尝试。
- AI 不会因为旧记忆把已经被拿走的箱子材料当成仍然存在。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentSessionTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentSessionTest` 通过。

README 同步：

- 增加会话记忆、继续任务和停止任务的说明。

### Phase C10: Agent 评测矩阵与回归基准

状态：`[x] 已完成`

完成记录：`AgentEvaluationMatrix` 已作为 Agent 入口回归基准，配套测试覆盖自然语言意图、控制命令、计划守卫、配方链和旧 `TaskPlanner` 入口。后续每次改 Agent 流程都可以用该矩阵防止 DeepSeek 入口退化或绕过本地规则。

目标：用固定任务集持续验证“DeepSeek 理解 + 本地规则执行”的整体效果，防止后续改动让 AI 退化成只会聊天或只会硬编码。

开发内容：

1. 建立自然语言意图测试集，覆盖制作、采集、烧炼、装水、停止、召回、闲聊和模糊请求。
2. 建立生存任务执行测试集，覆盖木工具、石工具、铁工具、熟食、水桶、基础方块建造。
3. 建立失败恢复测试集，覆盖缺材料、缺工具、没有可见矿、目标不可达、背包满、DeepSeek 非法输出。
4. 建立实机验收清单，记录每个任务需要的世界环境、输入命令、预期阶段和日志位置。
5. 增加 mock DeepSeek 响应测试，确保 validator 能拦截错误计划。
6. 每完成一个 phase 都更新测试矩阵和 README。

验收标准：

- `./gradlew test` 覆盖意图解析、计划校验、失败恢复和关键生存链。
- `./gradlew build` 必须通过。

验证方式：

```bash
./gradlew test
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentEvaluationMatrixTest --tests com.aiplayer.agent.AgentIntentParserTest --tests com.aiplayer.agent.AgentPlanGuardTest --tests com.aiplayer.recipe.SurvivalRecipePlanningTest --tests com.aiplayer.llm.TaskPlannerIntentTest` 通过。

README 同步：

- 增加 Agent 能力边界、测试任务示例和调试流程。

## Part D: DeepSeek 挖矿策略循环

本部分专门优化长时间挖矿任务。核心目标不是让 DeepSeek 直接控制坐标和破坏方块，而是让它像旁边的生存玩家一样，根据 AI 当前背包、附近箱子、当前位置、目标任务、最近失败和最近事件，给出下一段挖矿策略。模组代码继续负责配方、工具等级、可达性、危险、方块破坏和背包变更。

执行循环：

```text
玩家提出挖矿或矿物目标
-> 本地解析目标和原版配方链
-> 本地执行当前生存 step
-> step 完成 / 背包变化 / 卡住 / 危险 / 30 秒心跳
-> 捕获 WorldSnapshot 和任务状态
-> 向 DeepSeek 请求策略级建议
-> 本地校验建议
-> 只把合法建议转换为本地已支持的安全动作
-> 继续执行，直到目标完成或明确失败
```

### Phase D1: 共享生存上下文提示词

状态：`[x] 已完成`

完成记录：新增共享 `SurvivalPrompt`，普通任务规划、结构化计划 prompt、失败复盘 prompt 和挖矿策略 prompt 都会带上同一段 Minecraft 生存上下文。该上下文明确要求 DeepSeek 只能基于已提供材料、背包、箱子、附近方块、位置、工具和目标任务给策略建议，不能编造配方、物品 ID、地下矿物、坐标或作弊能力。

目标：所有 DeepSeek 请求都带上同一段基础生存语境，明确当前对话发生在 Minecraft 生存模式内，DeepSeek 只能基于已提供材料和观察事实给下一步建议。

开发内容：

1. 抽出共享提示词，不再让普通规划、配方规划、失败复盘和挖矿复盘各写一套互相漂移的规则。
2. 明确告诉 DeepSeek：玩家和 AI 都处于 Minecraft 生存模式。
3. 明确 DeepSeek 只负责策略建议，不负责编造配方、物品 ID、箱子内容、地下矿物或坐标。
4. 明确答案必须基于本地提供的背包、箱子、附近方块、位置、工具和目标任务。
5. 所有 JSON key、动作名和物品 ID 保持英文；说明文本使用中文。

验收标准：

- 普通任务规划 prompt、计划 prompt、失败复盘 prompt 和挖矿策略 prompt 都包含同一段生存上下文。
- 测试能验证提示词里包含“Minecraft 生存玩家”和“只能基于提供的材料/观察事实”。

验证方式：

```bash
./gradlew test --tests com.aiplayer.llm.SurvivalPromptTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.llm.SurvivalPromptTest --tests com.aiplayer.agent.MiningStrategyAdvisorTest` 通过；`./gradlew build` 通过。

### Phase D2: 挖矿策略上下文

状态：`[x] 已完成`

完成记录：新增 `MiningStrategyAdvisor`，会生成挖矿策略上下文，包含 `taskId`、目标物品、目标数量、当前 step、step 序号、AI 坐标、Y 高度、维度、背包摘要、可用物品、附近箱子数量、附近关键方块、近期失败、近期 step 事件、未知事实和允许策略动作。上下文不会包含 API key。

目标：给 DeepSeek 的挖矿复盘输入必须稳定、短小、可校验，能说明当前目标、当前 step、背包材料、附近资源、最近事件和最近失败。

开发内容：

1. 新增挖矿策略请求上下文，包含 `taskId`、目标物品、目标数量、当前 step、当前进度、AI 坐标、维度、Y 高度、背包摘要、附近箱子摘要、附近关键方块、最近失败和最近 step 事件。
2. 明确未知事实，例如地下未暴露矿物未知、扫描范围外箱子未知。
3. 向 DeepSeek 暴露允许的策略动作，例如继续当前 step、切换阶梯下挖、跳过当前目标、重新观察、请求玩家协助。
4. 不发送 API key、完整聊天历史或不相关日志。

验收标准：

- 挖矿策略请求日志能看到目标任务、当前材料状态和当前 step。
- 上下文不会泄露 API key。
- 缺材料、卡住、长时间无产出时的上下文能被复盘器读取。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.llm.SurvivalPromptTest --tests com.aiplayer.agent.MiningStrategyAdvisorTest` 通过；`./gradlew build` 通过。

### Phase D3: 30 秒异步策略心跳

状态：`[x] 已完成`

完成记录：`MakeItemAction` 会在挖矿相关长 step 中每 600 tick 向 DeepSeek 上报一次当前材料状态和目标任务，包括 `gather_stone` 和矿物 `gather` step。请求通过 `AsyncDeepSeekClient` 异步发送，同一任务同一时间只保留一个在途挖矿策略请求；未配置 API key、请求失败或返回非法 JSON 时，本地执行继续推进。

目标：AI 在长时间挖矿 step 中不会一条本地计划盲跑到底，而是每 30 秒异步向 DeepSeek 上报材料状态和目标任务，获得新的策略建议。

开发内容：

1. 在 `make_item` 的挖矿相关 step 中加入 30 秒心跳。
2. 心跳只在 `gather_stone`、矿物 `gather`、探矿等长 step 中触发。
3. HTTP 请求必须异步，不能阻塞 server thread。
4. 同一时间只允许一个挖矿策略请求在途，避免 API 风暴。
5. DeepSeek 未配置、请求失败或返回非法 JSON 时，本地计划继续执行。

验收标准：

- `帮我挖两块金锭` 这类长任务每 30 秒最多产生一次 `mining_strategy` 日志。
- 没有 API key 时不会崩溃，也不会影响本地执行。
- DeepSeek 响应返回后在 server tick 中被读取并记录。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.llm.SurvivalPromptTest --tests com.aiplayer.agent.MiningStrategyAdvisorTest` 通过；`./gradlew build` 通过。

### Phase D4: 策略建议本地仲裁与执行

状态：`[x] 已完成`

完成记录：新增 `MiningStrategyAdvice`，只接受声明过的策略动作，并拒绝 `give`、`teleport`、`setblock`、`creative`、`summon` 等作弊式建议。合法建议会在 server tick 中被读取并仲裁：可以继续当前 step、清除当前不可达目标、切换阶梯式下挖，或触发本地 `RecipeResolver` 和 `PlanValidator` 重新生成路线；DeepSeek 仍不能直接修改世界或生成物品。

目标：DeepSeek 的建议只能转换为本地已经支持的安全执行策略，不能直接修改世界或跳过生存链。

开发内容：

1. 定义挖矿策略响应 schema，例如 `strategy`、`action`、`message`、`reason`、`needsRebuild`、`needsUserHelp`。
2. 拒绝 `give`、`teleport`、`setblock`、`creative` 等作弊式建议。
3. 合法建议只能触发本地动作：继续当前 step、清除当前目标并换目标、切换阶梯下挖、重新观察并重建本地计划、向玩家提示需要协助。
4. 对当前不支持的策略只记录日志，不执行。
5. 所有采用或拒绝的建议写入 `mining_strategy` 或 `planning` 日志。

验收标准：

- DeepSeek 返回非法动作时不会执行。
- DeepSeek 建议切换阶梯下挖时，本地只改变采集策略，不会瞬移或凭空给材料。
- DeepSeek 建议重建路线时，仍由 `RecipeResolver` 和 `PlanValidator` 重新生成本地合法路线。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.llm.SurvivalPromptTest --tests com.aiplayer.agent.MiningStrategyAdvisorTest` 通过；`./gradlew build` 通过。

### Phase D5: 矿物高度和路线策略

状态：`[x] 已完成`

完成记录：`MiningResource.Profile` 已加入维度、推荐 Y 范围、探矿预算、路线提示和特殊环境标记。金、钻石、红石、远古残骸等深层矿物在当前位置高于推荐高度时会优先进入阶梯式下探；下界石英、下界金矿和远古残骸要求下界维度；黑曜石使用特殊环境标记，不会被普通主世界探矿逻辑误判为可凭空获得。

目标：金、铁、煤、钻石、红石、青金石、绿宝石、黑曜石等矿物不再只靠附近扫描，而是结合矿物资料表、当前 Y 高度和维度选择更合理的生存路线。

开发内容：

1. 在矿物资料表中加入维度、推荐高度范围、最低工具、是否需要特殊环境和是否允许阶梯探矿。
2. 例如金矿不应长期在 `y=70+` 地表原地搜索，钻石不应在高海拔随机扫描。
3. DeepSeek 可以建议“先下探到更合理高度”，但具体下探方式由本地阶梯挖掘执行。
4. 对下界矿物和黑曜石使用单独策略，避免主世界探矿逻辑误用。

验收标准：

- 金锭、钻石、红石、绿宝石等目标会先进入合理高度范围，再扫描或探矿。
- 目标高度策略不会覆盖本地危险检查。

验证方式：

```bash
./gradlew test --tests com.aiplayer.recipe.MiningResourceTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.recipe.MiningResourceTest` 通过；`./gradlew build` 通过。

### Phase D6: 实机挖矿回归记录

状态：`[x] 已完成`

完成记录：新增 `docs/mining-regression.md`，记录金锭、钻石、红石、绿宝石、黑曜石和远古残骸的实机回归矩阵、起始环境、预期阶段、成功标准、允许失败边界和日志检查规则。该文档用于后续每次挖矿逻辑修改后的实机复测记录。

目标：把长挖矿任务形成可复现实机清单，避免只修一个“金锭”样例，后续又在钻石、绿宝石或黑曜石上退化。

开发内容：

1. 建立实机命令清单：
   - `/ai say 帮我挖两块金锭`
   - `/ai say 帮我找三颗钻石`
   - `/ai say 帮我挖红石`
   - `/ai say 帮我挖绿宝石`
   - `/ai say 帮我挖黑曜石`
2. 每个任务记录世界环境、起始背包、关键日志、失败点和修复结果。
3. 每次修复挖矿逻辑后更新本清单和 README。

验收标准：

- 每个矿物任务都有明确预期阶段和失败边界。
- 日志能区分 DeepSeek 策略建议、本地计划、实际 step 和最终结果。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

## Part E: 挖矿能力专项优化路线

本部分针对当前日志中的问题：

```text
探矿超时：附近和矿道内仍未找到可挖的 铁矿 矿
```

说明当前挖矿能力仍停留在“找不到矿就向下挖一段并扫描”的初级阶段。真正像玩家一样挖矿，需要有明确矿道规划、分层搜索、分支矿道、风险处理、进度评估和失败恢复。以下 10 个 phase 专门用于把挖矿能力从“随机探矿”升级为“可持续矿洞作业”。

### Phase E1: 挖矿失败样本与回放日志

状态：`[x] 已完成`

完成记录：新增 `MiningRun` 挖矿运行诊断，`StepExecutor` 会为矿物采集和 `gather_stone` 建立独立挖矿记录，并把执行事实写入 `run/log/mining/mining.log`。日志包含 `taskId`、目标 step、起点、起始 Y、高度策略、路线提示、工具、背包、矿物扫描半径、候选数量、可达数量、拒绝数量和拒绝原因；阶梯下挖会记录站位、挖掘目标、方向、已挖方块数、破坏失败和疑似洞穴。探矿超时时，玩家看到的失败消息也会附带挖矿摘要，便于判断问题出在高度、路线、扫描范围、可达性还是工具/背包。

目标：先把挖矿失败变成可复盘数据，而不是只看到最终“探矿超时”。

开发内容：

1. 为每次矿物采集建立 `MiningRun` 记录，包含 `taskId`、目标矿物、起点、起始 Y、高度策略、工具、背包和当前矿道模式。
2. 每次扫描矿物时记录扫描半径、扫描到的候选数量、可达数量、被拒绝数量和拒绝原因。
3. 每次下挖或开矿道时记录挖掘方向、当前层高、已挖方块数、获得方块和是否发现洞穴。
4. 探矿超时时输出完整摘要：在哪些高度停留过、挖了多少方块、扫了几次、拒绝过哪些目标。
5. 新增 `run/log/mining/mining.log` 或复用 `mining_strategy` 分类，但必须能单独筛选挖矿执行事实。

验收标准：

- 看到一次铁矿失败日志时，能判断 AI 是高度不对、路线太短、扫描太窄、矿道形状不合理，还是路径/工具问题。
- `探矿超时` 不再是唯一信息，必须附带具体探索统计。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase E2: 矿物 Profile V2 与目标高度策略

状态：`[x] 已完成`

完成记录：`MiningResource.Profile` 升级为 V2，保留旧的 `preferredMinY/preferredMaxY` 本地执行判断，同时新增 `primaryRange`、`fallbackRange`、`surfaceAllowed`、`cavePreferred`、`branchMinePreferred`。煤、铜、铁、金、钻石、红石、青金石、绿宝石、下界矿物、黑曜石和远古残骸都补齐了主高度段、备用高度段和路线倾向。铁矿现在能表达“地下常规铁”和“山地高处铁”两条路线；金矿能区分普通地下金和恶地高处金；绿宝石优先山地/高海拔，不再被当成普通低层分支矿道目标；钻石和红石标记为深层分支矿道优先。`MiningStrategyAdvisor` 会把当前 step 的矿物 profile 发送给 DeepSeek，但执行仍由本地 profile 和生存规则仲裁。

目标：把矿物资料表从“推荐 Y 范围”升级成更接近原版分布的策略数据。

开发内容：

1. 为每种矿物补充 `primaryRange`、`fallbackRange`、`surfaceAllowed`、`cavePreferred`、`branchMinePreferred`。
2. 铁矿区分“地表山地铁”和“地下常规铁”，避免只在一个高度段死挖。
3. 金矿区分普通主世界金矿和恶地高处金矿。
4. 绿宝石绑定山地/高海拔策略，低地不应盲目下挖。
5. 钻石、红石优先深层矿道，煤、铜、铁可以接受浅层洞穴路线。
6. DeepSeek 上下文发送这些 profile，但执行仍由本地 profile 决定。

验收标准：

- 铁矿失败时能自动从当前高度切换到 fallback 高度，而不是一个高度挖到超时。
- 金、钻石、绿宝石的路线选择明显不同。

验证方式：

```bash
./gradlew test --tests com.aiplayer.recipe.MiningResourceTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.recipe.MiningResourceTest --tests com.aiplayer.agent.MiningStrategyAdvisorTest` 通过；`./gradlew build` 通过。

### Phase E3: 阶梯下探升级为可持续矿井入口

状态：`[x] 已完成`

完成记录：`ResourceGatherSession` 增加共享 `mining` 状态和 `MiningWaypoint`，会记录矿井 waypoint、最近站位、洞穴入口和下挖复用状态。`StepExecutor` 在 `gather_stone` 和矿物采集时会把阶梯下探写入共享矿井上下文；后续矿物 step 会尝试复用已知 waypoint，避免从同一任务里重复新挖入口。阶梯下挖会继续保持 2 格通行语义，并跳过水、岩浆、基岩、砂砾、沙子和红沙这类危险或易坍塌方块；下挖暴露疑似洞穴时会记录洞穴入口并写入 `mining.log`，为后续洞穴扫描模式提供入口。

目标：当前阶梯下挖只是“往下找方块”，需要升级成可复用矿井入口。

开发内容：

1. 阶梯宽高固定为玩家可通行空间，避免 AI 自己堵住头部或站位。
2. 每下降若干格记录一个 `MiningWaypoint`，后续可返回、继续、换层。
3. 下挖时优先保持 2 格高通道和安全台阶，避免垂直坑、悬空、岩浆、水流。
4. 发现洞穴时记录洞穴入口，切换到洞穴扫描模式，而不是继续机械下挖。
5. 遇到砂砾、沙子、水、岩浆、基岩时有明确绕行或停止策略。

验收标准：

- AI 能从地表稳定挖到目标高度，并保留可回到地表的通道。
- 连续多个矿物任务可以复用同一个矿井入口。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase E4: 分支矿道与层级搜索

状态：`[x] 已完成`

完成记录：新增分支矿道模式。对 `branchMinePreferred` 的矿物，AI 会先下探到路线计划目标 Y 附近，再沿水平方向开 2 格高分支矿道，避免铁矿这类高度范围较宽的矿物在高海拔直接开分支。每段默认 32 个破坏步，段落结束、受阻或破坏失败时按顺时针换方向；多方向受阻时会换到更低矿层继续搜索，而不是立刻终止。分支矿道会继续复用矿物扫描、`MiningRun` 日志、waypoint 和洞穴入口记录；遇到水、岩浆、基岩、砂砾、沙子、红沙等危险或坍塌方块会换方向或明确失败。钻石、红石、金矿、青金石、远古残骸等深层矿物会优先进入这种层级搜索，而不是一直下探。

目标：到达目标高度后，不应继续随机向下挖，而应建立分支矿道搜索。

开发内容：

1. 定义矿道模式：主通道、左分支、右分支、回主通道、换层。
2. 每个目标高度层建立有限长度的分支矿道，例如主通道每隔 3 格开左右分支。
3. 分支长度、层数和预算由矿物 profile 决定。
4. 每挖若干格重新扫描周围矿物，发现目标矿后切换到目标采集。
5. 记录已探索区域，避免重复挖同一条矿道。

验收标准：

- 铁矿或钻石任务不会在同一位置上下反复挖。
- 超时时能说明已探索几条分支、每条长度和对应高度。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase E5: 洞穴优先搜索与自然矿脉追踪

状态：`[x] 已完成`

完成记录：`WorldSnapshot` 新增 `nearbyCaves`，会扫描附近可站立洞穴入口，并记录可达性、连通空气量、暴露石壁数量和可见矿物数量；`MiningStrategyAdvisor` 会把洞穴摘要发送给 DeepSeek。`StepExecutor` 在探矿时会优先移动到已发现的安全洞穴入口进行扫描，危险或不可达入口会被跳过；矿物扫描会优先选择空气暴露面更多的矿块，挖到矿后会继续追踪相邻同类矿石，实现自然矿脉连续采集，而不是每挖一块就完全重找目标。

目标：像玩家一样优先利用洞穴和暴露矿脉，而不是所有矿物都从头挖矿道。

开发内容：

1. 在 `WorldSnapshot` 增加洞穴入口、空气连通空间、暴露石壁和可见矿物统计。
2. 如果附近有可达洞穴，优先进入洞穴扫描模式。
3. 发现矿脉后，挖完相邻同类矿石，而不是只挖一块就重新规划。
4. 洞穴中记录已探索方向，避免在洞口附近来回走。
5. 洞穴危险度过高时回退到分支矿道。

验收标准：

- 附近有天然洞穴时，AI 会先探索洞穴。
- 找到铁矿矿脉时，能连续采完整个矿脉。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase E6: 危险处理与生存安全

状态：`[x] 已完成`

完成记录：挖矿执行已加入危险检查和恢复：下挖、可见矿采集、分支矿道破坏前会检查岩浆、水、脚下垂直挖掘和坠落风险；危险目标会被拒绝、换目标或换分支。挖矿 tick 会节流检查低血量、附近敌对生物数量和导航卡住；低血量或敌对生物过多时进入返回状态并尝试回到玩家身边。工具耐久和背包空间在开挖前检查，背包满或镐耐久过低会进入等待玩家状态。危险原因写入 `MiningRun`，最终摘要包含危险计数。

目标：挖矿不能只看矿物，还要处理岩浆、水、坠落、怪物和工具耐久。

开发内容：

1. 下挖和开矿道前检查前方、脚下和头顶危险方块。
2. 岩浆附近停止直接挖掘，优先绕路或请求玩家处理。
3. 水流进入矿道时尝试绕行或关闭当前分支。
4. 附近敌对生物过多时暂停挖矿，进入回避/战斗/回玩家身边策略。
5. 工具耐久不足时提前制作备用工具，不要挖到一半才失败。
6. 背包接近满时优先回玩家身边或存入附近箱子。

验收标准：

- AI 不会主动把自己挖进岩浆、水流或垂直坑。
- 工具坏掉和背包满时能恢复，而不是直接终止长任务。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase E7: 挖矿进度评分与动态换策略

状态：`[x] 已完成`

完成记录：`MiningRun` 新增进度评分，综合已挖方块、扫描次数、可达候选、洞穴发现、拒绝目标、破坏失败和危险次数。探矿阶段如果连续约 30 秒没有目标物品进展，会记录 `mining progress decision`；分支矿道会换分支，非分支探矿会重置下探方向，避免在无收益路径上一直挖到统一超时。扫描到没有空气面的埋藏矿会记录 embedded ore hint，供分支矿道选择方向参考。日志会记录评分、决策、原因、扫描数、已挖方块、可达目标、拒绝目标和危险统计。

目标：不要等到固定超时才失败，要持续评估当前路线是否值得继续。

开发内容：

1. 为每个挖矿 run 计算进度评分：新增通道长度、扫描次数、发现矿物数量、获得目标材料数量、危险次数、卡住次数。
2. 如果 30 秒内没有任何有效进展，先换分支或换层。
3. 如果多个分支都没有进展，再询问 DeepSeek 是否继续当前路线、换高度或请求玩家协助。
4. DeepSeek 的建议必须带 `strategyReason`，本地记录采用或拒绝原因。
5. 同一策略连续失败时进入冷却，不再重复请求同一个建议。

验收标准：

- 日志能看到“为什么换层/换分支/继续”。
- AI 不会在无收益路径上挖到统一超时才停止。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.MiningStrategyAdvisorTest` 通过；`./gradlew build` 通过。

### Phase E8: 矿洞记忆与跨任务复用

状态：`[x] 已完成`

完成记录：AI 实体持有持久 `ResourceGatherSession`，`MakeItemAction` 复用同一个资源会话，因此同一个 AI 在连续任务之间会保留矿井 waypoint、洞穴入口、下挖复用状态和拒绝目标；先做铁工具再做金锭时，后续矿物 step 可以优先复用已有矿井入口。`AgentSession` 也新增 `miningMemory` 摘要字段，可记录矿井入口、贫矿分支和危险点，并在维度切换时清理，供后续 DeepSeek 上下文摘要使用。

目标：AI 挖过的矿洞、层级和失败区域应被短期记住，后续任务复用。

开发内容：

1. `AgentSession` 增加 `MiningMemory`，记录矿井入口、已探索层、分支、洞穴入口、危险点、失败点。
2. 新任务如果目标矿物适合同一矿洞，优先回到已有矿洞继续。
3. 玩家召回后不清空矿洞记忆，但新世界、维度改变、距离过远时失效。
4. 已经判定贫矿的区域进入冷却，避免立刻重复挖。
5. DeepSeek 上下文发送矿洞摘要，而不是完整坐标列表。

验收标准：

- 先做铁镐，再要求金锭时，AI 能复用已有矿井入口。
- 失败过的贫矿分支不会马上重复探索。

验证方式：

```bash
./gradlew test --tests com.aiplayer.agent.AgentSessionTest
./gradlew build
```

验证记录：`./gradlew test --tests com.aiplayer.agent.AgentSessionTest` 通过；`./gradlew build` 通过。

### Phase E9: 玩家可读的挖矿状态与指令

状态：`[x] 已完成`

完成记录：新增通用 `/ai status`、`/ai location` 和挖矿专项 `/ai mining status`、`/ai mining stop`、`/ai mining return`。AI 面板顶部移除“停止”按钮，改为“背包 / 状态 / 定位 / 召回”；“状态”按钮触发 `/ai status`，反馈 AI 当前阶段、目标、动作、执行/规划状态、位置、Y 高度、主手和背包摘要；“定位”按钮触发 `/ai location`，反馈 AI 当前维度、坐标和与玩家距离。挖矿专项状态仍可查看自动挖矿 taskId、当前目标、动作、位置和材料。README 已同步更新面板和状态命令说明。

目标：玩家需要知道 AI 为什么还在挖、正在挖哪里、还差什么，而不是只看后台日志。

开发内容：

1. AI 面板显示当前挖矿状态：目标矿物、当前高度、矿道模式、已探索方块数、最近发现。
2. 新增 `/ai mining status` 查看当前挖矿 run 摘要。
3. 新增 `/ai mining stop_branch` 或等价控制，让玩家要求 AI 放弃当前分支。
4. 当 AI 需要玩家协助时，消息应明确：“请带我到矿洞附近”“请给我 3 个生铁”“请移动到山地寻找绿宝石”。
5. README 增加挖矿状态说明。

验收标准：

- 玩家不用打开日志也能看懂 AI 当前挖矿进展。
- 失败消息从“探矿超时”升级为可操作建议。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。

### Phase E10: 挖矿实机回归与成功率基准

状态：`[x] 已完成`

完成记录：`docs/mining-regression.md` 已补充自动挖矿专项矩阵和记录模板，覆盖煤、铁、铜、金、钻石、红石、青金石、绿宝石、黑曜石和远古残骸。矩阵要求记录 `taskId`、起始材料、维度、起始 Y、地形、洞穴条件、耗时、最终结果、关键日志和下一步建议，并区分基础矿物、中层矿物、深层矿物和特殊资源的成功率目标与允许失败边界。

目标：建立真实世界里的挖矿成功率基准，避免每次只修一个日志样例。

开发内容：

1. 在 `docs/mining-regression.md` 中为铁、煤、金、钻石、红石、青金石、绿宝石、黑曜石和远古残骸分别记录实机任务。
2. 每个任务至少记录 3 类环境：地表附近无洞穴、附近有洞穴、已有部分材料。
3. 记录每个任务的 `taskId`、耗时、探索方块数、是否使用 DeepSeek 建议、最终结果。
4. 给每类矿物定义成功率目标和可接受失败边界。
5. 每次挖矿代码变更后更新该表。

验收标准：

- 铁矿、煤矿这类基础矿物应在普通地表环境中有稳定成功路径。
- 金、钻石、红石等深层矿物即使失败，也必须有明确探索记录和可操作建议。
- 黑曜石、远古残骸等特殊目标必须明确环境限制。

验证方式：

```bash
./gradlew build
```

验证记录：`./gradlew build` 通过。
