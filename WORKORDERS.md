# Codex 工单队列 · RL-1 … RL-20(按顺序全做)

## 如何使用(把下面整段发给 Codex 即可启动)

```text
请先完整阅读仓库根目录的 WORKORDERS.md 和 PLAN.md。任务:按顺序实现全部 RL-1 … RL-20,中途不停,全部做完后统一汇报。

【总体方式】
- 按 WORKORDERS.md 顺序逐张实现:RL-1 → RL-2 → … → RL-20。
- 每张工单是独立单元:只实现该工单(及其 PLAN.md "## RL-N" 章节)规定的范围;不要把后面工单的内容提前并进当前工单,也不要改与本工单无关的代码。
- 一张做完(编译通过 + commit + 勾选)再进入下一张;全部 20 张完成后再统一汇报。

【每张工单标准流程】
1. 先读 PLAN.md 对应 "## RL-N" 的完整设计。
2. 按设计实现(遵守下面的铁律)。
3. ./gradlew compileJava compileClientJava 必须通过;不过就修到过为止,绝不把编译错误带入下一张。
4. 执行该工单"验收"里能自动验的检查。
5. git commit,信息格式:RL-N: <一句话>(一张一个独立 commit,便于回滚)。
6. 在 WORKORDERS.md 把该工单复选框打勾,并在该行后用一句话备注结果(done / blocked:<原因>)。
7. 继续下一张。

【无人值守遇到卡点时】
- 若某张因 ⚠️VERIFY 的 API 实在对不上、或验收短时间修不好:不要硬改乱来、也不要悄悄跳过。给出"能编译通过的最小安全实现",在代码处留 // TODO RL-N BLOCKED: <原因>,在 WORKORDERS.md 该行标 blocked:<原因>,然后继续后面的工单,最后统一汇报所有 blocked 项。
- 任何时候都不要破坏已通过的既有功能;不要为了让某张通过而改坏前面的。

【铁律 · 不可违背】
- G1:复合行为做成自包含状态机;任务类内部绝不调用 TaskManager.INSTANCE.assign(编排只在 Brain/Watcher 层)。
- G2:世界/实体/库存只在主线程;HTTP、磁盘 IO 放后台线程,回调用 server.execute 回主线程。
- G3:容器/合成/交易一律直接操作 Inventory / BlockEntity,不打开 ScreenHandler 或任何 Screen。
- G4:物品 NBT 用 1.20.5+ 编解码(ItemStack.encode / fromNbt 带 RegistryWrapper)。

【技术栈锁死,不要升级】
Minecraft 1.21.3、Fabric Loader 0.18.4、Yarn 1.21.3+build.2、fabric-loom 1.16.2、Gradle 9.4.0、Java 21。
⚠️VERIFY 的 API 以编译通过为准,签名不确定对照 fabric-example-mod 的 1.21.3 分支。
复用现有类(BrainCoordinator / ToolRegistry / TaskManager / AbstractTask / ActionPack / PerceptionCollector / DangerWatcher / AIPlayerManager / BotMemory / AIBotConfig),不要重造。

【全部做完后的总汇报】
逐张列出:RL-N 做了什么、改了哪些文件、编译/验收结果、是否 blocked(原因)、有没有偏离 PLAN 之处;并贴最终 WORKORDERS.md 勾选状态。

现在从 WO-RL-1 开始,一直做到 WO-RL-20。
```

---

> 完整设计细节见 **`PLAN.md`** 对应 `## RL-N` 章节;本文件是可执行的工单清单。
>
> **全局规则(每张工单都适用)**
> 1. **严格按序**:W1(RL-1…11)→ W2(RL-12…16)→ W3(RL-17…20)。不要跳。
> 2. **一张工单一个独立 commit**;做完自测 `./gradlew compileJava compileClientJava` 通过 + 跑该工单的验收,再进入下一张。
> 3. **铁律(不可破)**:G1 复合行为自包含状态机、任务内**绝不** `TaskManager.INSTANCE.assign`(编排只在 Brain/Watcher 层);G2 世界/实体/库存只在主线程、IO 回 `server.execute`;G3 容器/合成直接操作 `Inventory`/`BlockEntity` 不走 ScreenHandler;G4 物品 NBT 用 1.20.5+ 编解码。
> 4. **`⚠️VERIFY` 的 1.21.3 API** 以 fabric-example-mod 1.21.3 分支为基准核对签名。
> 5. **不破坏既有功能**;复用现有锚点(BrainCoordinator/ToolRegistry/TaskManager/AbstractTask/ActionPack/PerceptionCollector/DangerWatcher/AIPlayerManager/BotMemory/AIBotConfig),勿重造。
> 6. 每张做完把改动交回审查(五查:G1 grep / G2 线程 / ⚠️VERIFY 编译 / 超时与失败兜底 / 不破坏既有)。

---

# W1 · 编排可靠性 + 验证 + 反馈(地基,先做透)

## WO-RL-1 · 失败自诊断与恢复  (PLAN §RL-1)
**文件**:`task/TaskManager`、`brain/BrainCoordinator`、`AIBotConfig.Brain`
**改动**:
- TaskManager:加 `record FailureRecord(name, reason, count, tick)` + `Map<UUID,FailureRecord> lastFailure`;`tickAll` 中任务转 `FAILED` → 同 `name+reason` 累加 count,否则新建;`COMPLETED` 清除。提供 `consumeFailure(bot)`(取出并清)。
- BrainCoordinator:`maybeInjectFailure(bot,conv)` —— 任务结束/idle 且会话不 busy 时 `consumeFailure`,注入中文诊断 user 消息("上一个任务失败:X,原因:Y(第 n 次)。请:补齐前置后重试 / 换方法 / say 放弃" + count≥max 时加放弃倾向)→ submit。触发点:`scheduleContinuation` 任务结束分支 + idle-watcher。
- `AIBotConfig.Brain.maxTaskRetries`=3。
**约束**:恢复在 Brain 层(G1);consume 式注入,幂等不重复。
**验收**:缺料 craft 失败 → LLM 自动补料重试成功;同失败 3 次 → 改方案/放弃,无无限重试。

## WO-RL-2 · 前置条件自动补齐(缺料闭环)  (PLAN §RL-2)
**文件**:`craft/CraftingHelper`、新 `craft/AcquisitionHints`、`brain/ToolRegistry`、`systemPrompt`
**改动**:
- 新只读工具 `plan_craft(item,count)` → JSON `{feasible, steps[], missing:[{item,count,source}]}`;`source` 由 AcquisitionHints 推断(mine/smelt/craft/forage/unknown:log←mine、cobblestone/stone←mine、raw_iron←mine、iron_ingot←smelt、planks/stick←craft、coal←mine)。
- systemPrompt 增:合成前可 `plan_craft` 查缺料,按 source 用 `assign_task mine`/`smelt` 补齐再 craft。
**约束**:只读工具,不在任务内递归采集(G1)。
**验收**:空背包 `plan_craft minecraft:stone_pickaxe` → 列出缺料 + 来源;LLM 补齐后造成功。

## WO-RL-3 · 卡死检测与自救  (PLAN §RL-3)
**文件**:新 `task/StuckWatcher`、`task/Task`(加 `default boolean isWaiting()`)、`SmeltTask`/`SleepTask`(等待阶段 true)、`AIBotMod`、`AIBotConfig`
**改动**:采样 `(blockPos, taskProgress, 库存总数)`;`stuckWindow`(默认 200tick)内三者不变且任务 `RUNNING` 且 `!isWaiting()` → `abort` + 写 `lastFailure(reason="stuck:"+name)`(RL-1 接管)。pos 变/库存涨即重置。
**验收**:让 bot mine 够不到的方块 → ~10s 自救 abort + 重规划;冶炼/睡觉**不误杀**。

## WO-RL-4 · 感知精炼(省 token)  (PLAN §RL-4)
**文件**:`perception/PerceptionSnapshot`、`perception/PerceptionCollector`、`AIBotConfig.Perception`
**改动**:加 `Highlights`(nearest_tree/stone/ore/water/furnace/chest/bed/hostile,各最近 1–2 个带坐标距离);blocks 按距离排序截断;`includeRawLists`(默认 false)关原始全列表省 token;`toJson` highlights 在前。
**验收**:`api_request` tokens_in 下降;LLM 直接引用最近资源坐标;开 `includeRawLists` 原始列表回归。

## WO-RL-5 · M8–M17 端到端验证矩阵 + 清账  (PLAN §RL-5)
**文件**:新 `command/AIBotVerifySubcommand`(挂 `/aibot verify`)、PLAN 验证矩阵附表
**改动**:每能力一个场景(setup→assign→poll 超时→assert→PASS/FAIL):覆盖 `persist/container/combat/sleep/farm/strip_mine/build(autosite)/memory/job/craft_chain`。FAIL 逐个修(含已知:溺水规避无效 / CraftTask 不自动造工作台 / 睡觉多人强制跳夜)。
**验收**:`/aibot verify all` 全 PASS 或对豁免项明确标注。

## WO-RL-6 · 异常与超时统一治理  (PLAN §RL-6)
**文件**:`brain/ActionDispatcher`、`network/AIBotServerNetworking.handleCommand`、各 `Task`、`brain/DeepSeekApiClient`、容器/世界访问点
**改动**:dispatch/C2S handler catch 范围扩到 `RuntimeException`(修 `Identifier.of(乱码)` 抛 `InvalidIdentifierException` 逃逸的坑)且必 `BotLog.error` 留痕;审计补齐所有任务超时(`*_timeout`);API 429/超时/空响应分类;BlockEntity/世界访问前置判空。
**验收**:坏 id / 卸载 chunk / 断网 → 捕获给 reason 不崩;无超时任务消除。

## WO-RL-7 · 持久化与多 bot 健壮性  (PLAN §RL-7)
**文件**:`persist/BotPersistence`、`manager/AIPlayerManager.respawnFromRecord`
**改动**:写 `bots.json.tmp` 再 `Files.move(ATOMIC_MOVE)`(跨 FS 失败回退普通 move);`saveAllAsync` 加 `AtomicBoolean` 单飞;维度 `getWorld(key)`==null → overworld 出生点 + warn;`respawnFromRecord` 重建 `ownerIndex`/`botOwners`。
**验收**:Bob+Alice 跨维度 + `kill -9` → 全恢复(位置/维度/背包/血饿/owner)+ 单 AI 限额生效 + 附魔损耗工具往返无损。

## WO-RL-8 · 长期目标真正驱动  (PLAN §RL-8)
**文件**:`brain/BrainCoordinator`、`memory/BotMemory.inject`
**改动**:组 prompt 注入"当前目标 + 下一步"(`BotMemory.inject`);任务 `COMPLETED` 且有活跃 goal → 注入"上一步完成,剩余 X,请推进";**idle-watcher 与 RL-1 合并**:idle + 会话 idle + (失败未消费 || 有未完成 goal) → 唤起一轮。goal 持久化复用 M8/M16。
**验收**:`set_goal "建基地" [挖石,盖墙,放箱]` → 自动逐步推进;重启后 `goal_status` 仍在第 N 步并续做。

## WO-RL-9 · 性能实测调优  (PLAN §RL-9)
**文件**:新 `task/BotTickCoordinator`(合并 DangerWatcher/StuckWatcher/IdleCoordinator/唤起为**单 all-bots 遍历**)、`pathfinding`、`observe/TpsGuard`
**改动**:单遍历分发 危险/卡死/空闲/目标 四类检查(读 `TpsGuard.scanInterval()`);A* 失败短路 + 结果缓存/节流;`TpsGuard` degraded **分级降级**(先降感知/续转/非关键任务,危险响应最后降),阈值实测调参。
**验收**:10 bot 同时干活 TPS≥19;`/aibot profile` 无单段失控;制造卡顿能 degraded 并恢复。

## WO-RL-10 · 对话反馈:错误友好化 + 主动汇报  (PLAN §RL-10)
**文件**:新 `brain/BotReporter`、`brain/ReasonText`、`AIBotConfig.Brain.verboseReports`
**改动**:任务 `assigned/关键phase/completed/failed` 节点经 `sendBotChat`(面板通道,OPT-7)播中文摘要(**只播节点+限频+去重**);长任务 25/50/75/100% 各播一次;失败 reason → 中文友好(`need:x`→缺少X、`pickup_timeout`→没捡到掉落物、`stuck`→卡住换办法)。给 LLM 的仍是原始 reason(RL-1)。
**验收**:`gather 石头` → 面板"开始挖石头 → 已挖 5/10 → 完成"中文播报;失败给人话。

## WO-RL-11 · 面板增强:任务进度 / 目标看板 / 设置页  (PLAN §RL-11)
**文件**:新 `client/screen/ui/cards/TaskCard`、`GoalCard`;`network/payload/BotSnapshotS2C`(加 `goalTitle/goalCurrentStep/goalTotalSteps`,双端 codec 同步);新 `SetOptionC2S`;`BotPanelScreen`(`Mode.SETTINGS` 或子视图);`buildCards`
**改动**:TaskCard 当前任务+进度条+阶段;GoalCard 目标步骤+当前步高亮;设置页落地开关(手动模式/记忆/播报),经 `SetOptionC2S` 写回(`hasPermissionLevel(2)` 校验)。
**约束**:payload 双端注册 + codec 字段读写顺序一致;设置写回 OP 校验。
**验收**:任务进度条实时;目标看板显示步骤;设置页切"手动模式"→低层工具回归(验 OPT-1 开关)。

---

# W2 · 基地与持续生产

## WO-RL-12 · "攒够 X"持续采集目标  (PLAN §RL-12)
**文件**:新 `task/GatherQuotaTask`、抽 `action/HarvestCore`(挖+走到掉落物+捡拾的复用核心,并入 OPT-4/RL-7 捡拾改进)、`ToolRegistry`(`gather`)+`assign_task gather`
**改动**:配额循环 `找最近资源→走→采集→计数→未达标继续`(按 item 类型选挖/打/收);满仓 → base 存(RL-13)否则 `fail("inventory_full")`;范围无目标 → `fail("no_resource_nearby")`。**内联复用 HarvestCore,绝不 assign 子任务(G1)**。
**验收**:`gather minecraft:cobblestone 64` → 持续采到 64;满仓自动存箱续采。

## WO-RL-13 · 基地标记 + 智能归仓  (PLAN §RL-13)
**文件**:`memory/BotMemory.places`、工具 `set_base`/`deposit_all`、新 `task/StockpileTask`(或扩 `ContainerTask`)
**改动**:`set_base` 记当前坐标为 "base";`deposit_all` 扫 base 半径内容器,按"就近填充 + 同类聚拢"分配存入(`ContainerAction`,G3),逐栈/帧。
**验收**:`set_base` → 采一堆杂物 → `deposit_all` → 自动入 base 附近箱。

## WO-RL-14 · 自动补给(工具/食物耗尽)  (PLAN §RL-14)
**文件**:`maybeResupply`(并入 RL-9 的 BotTickCoordinator)、新 `task/ResupplyTask`
**改动**:主工具 `getDamage()` 近 `getMaxDamage()`(<10%)或 无食物且饿 → `pauseFor` 原任务 + `ResupplyTask`(回 base→`withdraw` 或 `CraftingHelper.applyCraft` 现造)→ 完成后 `resumeFromPause`/RL-1 恢复。
**验收**:铁镐快断 → 回 base 换镐续挖;饿且无食物 → 回 base 取食物吃。

## WO-RL-15 · 长期农业循环  (PLAN §RL-15)
**文件**:`task/FarmTask`(增 `DEPOSIT` 阶段 + 留种)
**改动**:`keepTending` 强化:成熟才收、收后补种、周期性入 base、补种后余种入箱(自给);等待成熟阶段 `isWaiting()` 豁免 RL-3。
**验收**:`farm <area> wheat keep_tending` → 长期 收-补种-入箱-留种 循环。

## WO-RL-16 · 冶炼队列(批量/多炉/续料)  (PLAN §RL-16)
**文件**:新 `task/SmeltQueueTask`(或扩 `SmeltTask`),复用 `SmeltTask` 炉操作 + `FUEL_TICKS`
**改动**:大 `count` 分批装料 + 燃料不足自动从背包/base 补;先单炉大批量 + 自动续燃料,多炉为增强;G3 直接 `AbstractFurnaceBlockEntity`;等待阶段 `isWaiting()` 豁免。
**验收**:`smelt minecraft:raw_iron minecraft:iron_ingot 32` → 续燃料烧完产 32。

---

# W3 · 真玩家行为

## WO-RL-17 · 跟随 / 待命 / 护卫  (PLAN §RL-17)
**文件**:新 `task/FollowTask`/`HoldTask`/`GuardTask`、抽战斗内核 helper(GuardTask + CombatTask 共用)、工具 `follow`/`hold`/`guard`
**改动**:Follow 持续路径到玩家 reach(半径滞回 + 限频重算);Hold `stopAll` 待命(威胁仍交 DangerWatcher);Guard 守护点,敌近**内联**战斗(复用 helper,不 assign,G1)。目标玩家默认 = 指令发起者/owner。
**验收**:`follow` 跟随保持 2–4 格;`hold` 不动;`guard` 守位打怪打完回位。

## WO-RL-18 · 战斗强化(盾/弓/撤退治疗/装备升级)  (PLAN §RL-18)
**文件**:`action/EquipAction`(`bestRangedSlot` + 装盾副手)、`task/CombatTask`(`RANGED`/`BLOCK`/`HEAL` 阶段 + 距离决策 + 撤退治疗内联)
**改动**:远(>近战阈值)用弓蓄力、近用剑、危急副手盾格挡;HP≤阈值 → 退到安全 + 内联 `EatAction` 回血再战;开战前 `equipBestArmor`。**先做 装备升级+盾+撤退治疗(稳),弓为增强**。
**约束**:`⚠️VERIFY` 弓蓄力 `useItem` / `PersistentProjectileEntity`。
**验收**:给弓箭剑盾甲 → 远弓近剑被围格挡低血撤退回血再战。

## WO-RL-19 · 钓鱼  (PLAN §RL-19)
**文件**:新 `task/FishTask`、工具 `fish`
**改动**:`FIND_WATER→CAST(useItem 鱼竿)→WAIT_BITE(检测咬钩)→REEL→COLLECT→LOOP`;`WAIT_BITE` 阶段 `isWaiting()` 豁免;咬钩检测读 `ServerPlayerEntity.fishHook` / owner==bot 的 `FishingBobberEntity` 状态,不稳则定时兜底收竿。
**约束**:`⚠️VERIFY` `fishHook` / `FishingBobberEntity` 咬钩状态字段。
**验收**:对水 `fish` → 抛竿-咬钩-收竿-得鱼,循环;渔获进背包。

## WO-RL-20 · 村民交易  (PLAN §RL-20)
**文件**:新 `task/TradeTask`、工具 `trade`
**改动**:找最近 `VillagerEntity` → `getOffers` → 选可负担 offer → **直接结算**(移除买入物 + `giveItem` 产物 + 维护 `offer.use()`/`villager.afterUsing` 补货/经验),**不开 `MerchantScreen`**(G3);先支持简单 1-input(或绿宝石单买入)offer。
**约束**:`⚠️VERIFY` `TradeOffer`/`MerchantEntity.getOffers`/`afterUsing`/`TradeOffer.use`。
**验收**:附近有村民 → `trade`(买面包)→ 扣绿宝石得面包,村民经验/补货更新。

---

## 进度勾选(Codex 做完一张勾一张)
- [x] RL-1 done: 失败记录、消费式诊断注入和 maxTaskRetries 配置已实现,编译通过。  [x] RL-2 done: plan_craft 只读预检与 missing source 提示已实现,编译通过。  [x] RL-3 done: StuckWatcher、isWaiting 豁免和 stuck 失败回灌已实现,编译通过。  [x] RL-4 done: perception highlights 与 includeRawLists=false 默认裁剪已实现,编译通过。  [x] RL-5 done: /aibot verify 矩阵、跨 tick 轮询和 craft_chain 自动工作台清账已实现,编译通过。  [x] RL-6 done: RuntimeException 边界、API 错误分类、BuildTask 空安全和统一 timeout reason 已实现,编译通过。  [ ] RL-7  [ ] RL-8  [ ] RL-9  [ ] RL-10
- [ ] RL-11 [ ] RL-12 [ ] RL-13 [ ] RL-14 [ ] RL-15 [ ] RL-16 [ ] RL-17 [ ] RL-18 [ ] RL-19 [ ] RL-20
