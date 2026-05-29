# AIBot

AIBot 是一个基于 Fabric 的 Minecraft AI 玩家 Mod。它会在服务器中生成一个真实的 AI 假玩家实体，让 AI 通过感知、任务、动作和记忆系统在世界里执行移动、采集、挖矿、建造、战斗、生存工具链等行为。

这个项目的核心目标不是做一个普通聊天机器人，而是让 LLM 只负责高层规划，具体 Minecraft 行为由确定性的 Task 状态机执行。

## 当前能力

- 生成和移除 AI 玩家，例如 `Bob`
- 基础移动、寻路、看向目标、跳跃、停止
- 背包查看、快捷栏选择、工具装备、调试给予物品
- 自动挖矿、采集、战斗、建造、农场、繁殖、睡觉
- 生存工具链：
  - 挖矿时自动选择更合适的工具
  - 合成常见工具和基础物品
  - 饥饿时自动进食
  - 使用熔炉冶炼矿物
- 条带挖矿、矿脉挖掘、危险检测、火把放置
- 容器存取：存入、取出、保留工具
- Bot 记忆：地点、目标、状态注入
- DeepSeek / OpenAI 风格接口驱动的自然语言决策
- 结构化日志、性能 profile、replay、TPS guard
- 客户端控制面板：通过快捷键打开 Bob 面板，查看状态并发送指令

## 环境要求

- Minecraft `1.21.3`
- Fabric Loader `0.18.4` 或更高版本
- Fabric API `0.114.1+1.21.3`
- Java `21`
- Gradle Wrapper

## 快速开始

构建项目：

```bash
./gradlew build
```

启动开发服务端：

```bash
./gradlew runServer
```

启动开发客户端：

```bash
./gradlew runClient
```

首次运行时，Mod 会在 Fabric 配置目录生成 `aibot.json`。

## 配置 AI 模型

推荐通过环境变量提供 API Key：

```bash
export DEEPSEEK_API_KEY="your-api-key"
```

也可以编辑生成的 `aibot.json`：

```json
{
  "deepseek": {
    "baseUrl": "https://api.deepseek.com",
    "model": "deepseek-chat"
  }
}
```

配置文件还包含感知范围、脑区上下文长度、日志、生存、战斗、夜晚、挖矿等参数。

## 常用命令

生成一个 AI 玩家：

```mcfunction
/aibot spawn Bob
```

查看当前 Bot：

```mcfunction
/aibot list
```

让 Bot 执行任务：

```mcfunction
/aibot task assign Bob move 0 64 0
/aibot task assign Bob mine minecraft:stone 16
/aibot task assign Bob craft minecraft:stone_pickaxe 1
/aibot task assign Bob smelt minecraft:raw_iron minecraft:iron_ingot 3
/aibot task assign Bob eat
```

查看或中止任务：

```mcfunction
/aibot task status Bob
/aibot task abort Bob
```

让 LLM 处理自然语言请求：

```mcfunction
/aibot brain say Bob 帮我准备一把铁镐
```

查看或重置脑区状态：

```mcfunction
/aibot brain status Bob
/aibot brain reset Bob
```

## 客户端控制面板

在客户端环境中，按 `Alt + 0` 可以打开 AIBot 面板。

面板支持：

- 锁定目标 Bot，例如 `Bob`
- 查看生命值、饥饿值、任务状态、脑区状态、token 使用量、背包摘要
- 向 Bot 发送自然语言消息
- 快速执行移动、停止、进食、挖矿、合成、冶炼等操作

如果没有服务器命令权限，面板会提示权限不足；具体可执行动作仍取决于服务器权限配置。

## 项目结构

```text
src/main/java/io/github/zoyluo/aibot
|-- action/        # 低层动作：移动、挖掘、交互、背包、建造
|-- brain/         # LLM 请求、工具注册、决策协调
|-- command/       # /aibot 命令
|-- coordination/  # 多 Bot 任务板和空闲协调
|-- craft/         # 硬编码配方和合成辅助
|-- entity/        # AI 玩家实体
|-- log/           # 结构化日志
|-- manager/       # Bot 生命周期管理
|-- memory/        # Bot 记忆和目标状态
|-- network/       # 客户端面板通信 payload
|-- observe/       # TPS、profile、replay
|-- pathfinding/   # 寻路和危险检测
|-- persist/       # Bot 持久化
`-- task/          # 确定性任务状态机
```

客户端代码位于：

```text
src/client/java/io/github/zoyluo/aibot/client
```

## 架构原则

AIBot 的核心原则是：

> LLM 做高层规划，Task 做确定执行。

例如“帮我合成一把铁镐”会被拆成：

```text
craft(crafting_table)
craft(wooden_pickaxe)
mine(stone)
craft(stone_pickaxe)
mine(iron_ore)
smelt(raw_iron -> iron_ingot)
craft(iron_pickaxe)
```

每一步都是可观察、可中止、可失败、可重试的确定性任务。Task 不依赖 GUI 点击，也不会让 LLM 直接操作 Minecraft 内部细节。

## 开发验证

常用验证命令：

```bash
./gradlew clean build
./gradlew runServer
./gradlew runClient
```

涉及 Minecraft / Fabric API 的改动需要特别注意版本兼容，当前项目以 Yarn `1.21.3+build.2` 为准。修改物品组件、进食、燃料注册、挖掘速度、熔炉库存或客户端网络代码前，应先确认当前版本的方法签名。

## 当前状态

AIBot 仍处于开发阶段。当前代码已经具备 Fabric 基础骨架、服务端 Bot 生命周期、任务执行、LLM 工具集成、结构化观察、生存工具链和早期客户端控制面板。后续可以继续强化从零生存闭环、多 Bot 协作、客户端交互体验、长期记忆恢复和自动化验证场景。
