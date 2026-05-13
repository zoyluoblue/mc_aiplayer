# Crafting Tree 结构

本文档说明项目内部的制作依赖图结构，用于后续把目标物品拆成 `item -> prerequisite -> action` 的可执行树。

## 数据来源

配方和数量不依赖网站，统一来自当前游戏实例解析出的 `RecipePlan`：

1. `RecipeResolver` 查询 AI 背包、附近箱子、基础来源和 Minecraft `RecipeManager`。
2. `RecipePlan` 记录线性执行链。
3. `CraftingTree.fromRecipePlan(...)` 把线性执行链转换成节点和边。

基础获取行为仍由本地生存规则决定，例如：

- `source:tree -> gather -> minecraft:oak_log`
- `source:stone -> gather -> minecraft:cobblestone`
- `source:iron_ore -> gather -> minecraft:raw_iron`
- `minecraft:raw_iron + fuel -> craft:furnace -> minecraft:iron_ingot`

## 核心类

```text
src/main/java/com/aiplayer/recipe/CraftingTree.java
```

核心结构：

```text
CraftingTree
├── graphId
├── Target
├── success / failureReason
├── Node[]
├── Edge[]
└── TreeStep[]
```

### Target

目标物品：

```text
item: minecraft:stone_pickaxe
count: 1
```

`graphId` 由目标物品和数量生成，例如：

```text
tree:minecraft_gold_ingot:x2
```

日志、状态面板和后续 DeepSeek 复盘都应使用同一个 `graphId` 关联任务。

### Node

节点分四类：

```text
TARGET_ITEM  最终目标物品
ITEM         中间材料或可复用材料
ACTION       gather / craft / withdraw_chest 等动作
SOURCE       tree / stone / iron_ore / chest 等来源
```

### Edge

边分两类：

```text
REQUIRES  prerequisite -> action
PRODUCES  action -> item
```

因此一个石镐链路可以表达为：

```text
source:stone -> gather -> minecraft:cobblestone x3
[minecraft:cobblestone x3, minecraft:stick x2] -> craft:crafting_table -> minecraft:stone_pickaxe x1
```

### TreeStep

`TreeStep` 是线性执行入口，每个 step 都带有与 action 节点一致的 `id`：

```text
id: action:1:gather:minecraft:raw_gold
action: gather
output: minecraft:raw_gold x2
source: gold_ore
```

`make_item` 每次重建计划时都会记录当前 `graphId` 和 `treeNode`。旧式 `mine` 任务以及 DeepSeek 返回的矿物 `gather` 任务会先归一成 `make_item`，再由 `RecipePlan -> CraftingTree -> ExecutionStep` 进入生存执行。

## Action 路由

```text
src/main/java/com/aiplayer/planning/CraftingTreeActionRouter.java
```

`CraftingTreeActionRouter` 是 `TreeStep` 到本地执行 step 的集中映射表：

```text
withdraw_chest -> withdraw_chest
gather + source:tree -> gather_tree
gather + source:stone -> gather_stone
gather + source:ore/block -> gather
craft + station:inventory -> craft_inventory
craft + station:crafting_table/furnace/... -> craft_station
craft + station:water_source -> fill_water
```

后续新增 `smelt`、`deposit_chest` 或更细动作时，应优先扩展这个 router，而不是在多个执行器里继续分散字符串判断。

## 输出形式

`CraftingTree` 当前支持三种输出。

失败的 `RecipePlan` 也会生成 `CraftingTree`，但 `success=false`，`failureReason` 会保留失败原因，`nodes` 只保留目标节点，`edges` 和 `steps` 为空。消费方不能把空链路直接理解为“材料已具备”，必须先检查 `success`。

### 文本依赖图

```java
recipePlan.toCraftingTree().toDependencyText();
```

用于 `/ai recipe` 和日志阅读。

### JSON

```java
recipePlan.toCraftingTree().toJson();
```

用于后续给 AI 面板、DeepSeek 上下文或调试文件消费。

### Mermaid

```java
recipePlan.toCraftingTree().toMermaid();
```

用于后续把制作树渲染成可视化图。

## 后续接入方向

1. `/ai recipe`：继续输出文本依赖图。
2. `/ai plan`：可增加可选 JSON tree 输出。
3. AI 面板：可读取 `nodes` 和 `edges` 展示目标、缺口、动作和当前阶段。
4. DeepSeek 上下文：只传 `CraftingTree` 的高层结构，不允许 DeepSeek 改写配方数量。
5. 挖矿链路：将 `source:ore -> gather -> raw_item` 节点和 `craft:furnace` 节点作为里程碑来源。
