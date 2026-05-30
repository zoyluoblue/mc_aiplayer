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

## WO-RL-1 · 失败自诊断与恢复  (PLAN §RL-1) ✅ done: TaskManager 失败记录/消费与 BrainCoordinator 失败诊断续跑已实现, compileJava/compileClientJava 通过。
**文件**:`task/TaskManager`、`brain/BrainCoordinator`、`AIBotConfig.Brain`
**改动**:
- TaskManager:加 `record FailureRecord(name, reason, count, tick)` + `Map<UUID,FailureRecord> lastFailure`;`tickAll` 中任务转 `FAILED` → 同 `name+reason` 累加 count,否则新建;`COMPLETED` 清除。提供 `consumeFailure(bot)`(取出并清)。
- BrainCoordinator:`maybeInjectFailure(bot,conv)` —— 任务结束/idle 且会话不 busy 时 `consumeFailure`,注入中文诊断 user 消息("上一个任务失败:X,原因:Y(第 n 次)。请:补齐前置后重试 / 换方法 / say 放弃" + count≥max 时加放弃倾向)→ submit。触发点:`scheduleContinuation` 任务结束分支 + idle-watcher。
- `AIBotConfig.Brain.maxTaskRetries`=3。
**约束**:恢复在 Brain 层(G1);consume 式注入,幂等不重复。
**验收**:缺料 craft 失败 → LLM 自动补料重试成功;同失败 3 次 → 改方案/放弃,无无限重试。

## WO-RL-2 · 前置条件自动补齐(缺料闭环)  (PLAN §RL-2) ✅ done: plan_craft 只读规划、AcquisitionHints 和缺料补齐提示已接入, compileJava/compileClientJava 通过。
**文件**:`craft/CraftingHelper`、新 `craft/AcquisitionHints`、`brain/ToolRegistry`、`systemPrompt`
**改动**:
- 新只读工具 `plan_craft(item,count)` → JSON `{feasible, steps[], missing:[{item,count,source}]}`;`source` 由 AcquisitionHints 推断(mine/smelt/craft/forage/unknown:log←mine、cobblestone/stone←mine、raw_iron←mine、iron_ingot←smelt、planks/stick←craft、coal←mine)。
- systemPrompt 增:合成前可 `plan_craft` 查缺料,按 source 用 `assign_task mine`/`smelt` 补齐再 craft。
**约束**:只读工具,不在任务内递归采集(G1)。
**验收**:空背包 `plan_craft minecraft:stone_pickaxe` → 列出缺料 + 来源;LLM 补齐后造成功。

## WO-RL-3 · 卡死检测与自救  (PLAN §RL-3) ✅ done: StuckWatcher、Task.isWaiting 豁免和卡死失败记录已实现, compileJava/compileClientJava 通过。
**文件**:新 `task/StuckWatcher`、`task/Task`(加 `default boolean isWaiting()`)、`SmeltTask`/`SleepTask`(等待阶段 true)、`AIBotMod`、`AIBotConfig`
**改动**:采样 `(blockPos, taskProgress, 库存总数)`;`stuckWindow`(默认 200tick)内三者不变且任务 `RUNNING` 且 `!isWaiting()` → `abort` + 写 `lastFailure(reason="stuck:"+name)`(RL-1 接管)。pos 变/库存涨即重置。
**验收**:让 bot mine 够不到的方块 → ~10s 自救 abort + 重规划;冶炼/睡觉**不误杀**。

## WO-RL-4 · 感知精炼(省 token)  (PLAN §RL-4) ✅ done: 感知 highlights、距离排序截断和 includeRawLists 开关已实现, compileJava/compileClientJava 通过。
**文件**:`perception/PerceptionSnapshot`、`perception/PerceptionCollector`、`AIBotConfig.Perception`
**改动**:加 `Highlights`(nearest_tree/stone/ore/water/furnace/chest/bed/hostile,各最近 1–2 个带坐标距离);blocks 按距离排序截断;`includeRawLists`(默认 false)关原始全列表省 token;`toJson` highlights 在前。
**验收**:`api_request` tokens_in 下降;LLM 直接引用最近资源坐标;开 `includeRawLists` 原始列表回归。

## WO-RL-5 · M8–M17 端到端验证矩阵 + 清账  (PLAN §RL-5) ✅ done: /aibot verify 验证矩阵骨架与场景断言已实现, compileJava/compileClientJava 通过。
**文件**:新 `command/AIBotVerifySubcommand`(挂 `/aibot verify`)、PLAN 验证矩阵附表
**改动**:每能力一个场景(setup→assign→poll 超时→assert→PASS/FAIL):覆盖 `persist/container/combat/sleep/farm/strip_mine/build(autosite)/memory/job/craft_chain`。FAIL 逐个修(含已知:溺水规避无效 / CraftTask 不自动造工作台 / 睡觉多人强制跳夜)。
**验收**:`/aibot verify all` 全 PASS 或对豁免项明确标注。

## WO-RL-6 · 异常与超时统一治理  (PLAN §RL-6) ✅ done: 工具/网络异常捕获、失败分类和任务超时边界已补齐, compileJava/compileClientJava 通过。
**文件**:`brain/ActionDispatcher`、`network/AIBotServerNetworking.handleCommand`、各 `Task`、`brain/DeepSeekApiClient`、容器/世界访问点
**改动**:dispatch/C2S handler catch 范围扩到 `RuntimeException`(修 `Identifier.of(乱码)` 抛 `InvalidIdentifierException` 逃逸的坑)且必 `BotLog.error` 留痕;审计补齐所有任务超时(`*_timeout`);API 429/超时/空响应分类;BlockEntity/世界访问前置判空。
**验收**:坏 id / 卸载 chunk / 断网 → 捕获给 reason 不崩;无超时任务消除。

## WO-RL-7 · 持久化与多 bot 健壮性  (PLAN §RL-7) ✅ done: 持久化原子写、saveAll 单飞和多 bot owner/维度恢复加固已实现, compileJava/compileClientJava 通过。
**文件**:`persist/BotPersistence`、`manager/AIPlayerManager.respawnFromRecord`
**改动**:写 `bots.json.tmp` 再 `Files.move(ATOMIC_MOVE)`(跨 FS 失败回退普通 move);`saveAllAsync` 加 `AtomicBoolean` 单飞;维度 `getWorld(key)`==null → overworld 出生点 + warn;`respawnFromRecord` 重建 `ownerIndex`/`botOwners`。
**验收**:Bob+Alice 跨维度 + `kill -9` → 全恢复(位置/维度/背包/血饿/owner)+ 单 AI 限额生效 + 附魔损耗工具往返无损。

## WO-RL-8 · 长期目标真正驱动  (PLAN §RL-8) ✅ done: 长期目标注入、任务完成后续推和 idle watcher 唤起已实现, compileJava/compileClientJava 通过。
**文件**:`brain/BrainCoordinator`、`memory/BotMemory.inject`
**改动**:组 prompt 注入"当前目标 + 下一步"(`BotMemory.inject`);任务 `COMPLETED` 且有活跃 goal → 注入"上一步完成,剩余 X,请推进";**idle-watcher 与 RL-1 合并**:idle + 会话 idle + (失败未消费 || 有未完成 goal) → 唤起一轮。goal 持久化复用 M8/M16。
**验收**:`set_goal "建基地" [挖石,盖墙,放箱]` → 自动逐步推进;重启后 `goal_status` 仍在第 N 步并续做。

## WO-RL-9 · 性能实测调优  (PLAN §RL-9) ✅ done: 单遍历 tick 协调器、A* 缓存/节流、degraded 分级扫描已实现, compileJava/compileClientJava 通过。
**文件**:新 `task/BotTickCoordinator`(合并 DangerWatcher/StuckWatcher/IdleCoordinator/唤起为**单 all-bots 遍历**)、`pathfinding`、`observe/TpsGuard`
**改动**:单遍历分发 危险/卡死/空闲/目标 四类检查(读 `TpsGuard.scanInterval()`);A* 失败短路 + 结果缓存/节流;`TpsGuard` degraded **分级降级**(先降感知/续转/非关键任务,危险响应最后降),阈值实测调参。
**验收**:10 bot 同时干活 TPS≥19;`/aibot profile` 无单段失控;制造卡顿能 degraded 并恢复。

## WO-RL-10 · 对话反馈:错误友好化 + 主动汇报  (PLAN §RL-10) ✅ done: BotReporter/ReasonText/verboseReports 已实现, 任务节点中文系统播报与失败友好文案已接入, compileJava/compileClientJava 通过。
**文件**:新 `brain/BotReporter`、`brain/ReasonText`、`AIBotConfig.Brain.verboseReports`
**改动**:任务 `assigned/关键phase/completed/failed` 节点经 `sendBotChat`(面板通道,OPT-7)播中文摘要(**只播节点+限频+去重**);长任务 25/50/75/100% 各播一次;失败 reason → 中文友好(`need:x`→缺少X、`pickup_timeout`→没捡到掉落物、`stuck`→卡住换办法)。给 LLM 的仍是原始 reason(RL-1)。
**验收**:`gather 石头` → 面板"开始挖石头 → 已挖 5/10 → 完成"中文播报;失败给人话。

## WO-RL-11 · 面板增强:任务进度 / 目标看板 / 设置页  (PLAN §RL-11) ✅ done: TaskCard/GoalCard/SettingsCard、goal/option payload、SetOptionC2S 写回与 OP 校验已实现, compileJava/compileClientJava 通过。
**文件**:新 `client/screen/ui/cards/TaskCard`、`GoalCard`;`network/payload/BotSnapshotS2C`(加 `goalTitle/goalCurrentStep/goalTotalSteps`,双端 codec 同步);新 `SetOptionC2S`;`BotPanelScreen`(`Mode.SETTINGS` 或子视图);`buildCards`
**改动**:TaskCard 当前任务+进度条+阶段;GoalCard 目标步骤+当前步高亮;设置页落地开关(手动模式/记忆/播报),经 `SetOptionC2S` 写回(`hasPermissionLevel(2)` 校验)。
**约束**:payload 双端注册 + codec 字段读写顺序一致;设置写回 OP 校验。
**验收**:任务进度条实时;目标看板显示步骤;设置页切"手动模式"→低层工具回归(验 OPT-1 开关)。

---

# W2 · 基地与持续生产

## WO-RL-12 · "攒够 X"持续采集目标  (PLAN §RL-12) ✅ done: GatherQuotaTask 与 HarvestCore 已实现并接入 gather/assign_task/命令, MineTask 复用 helper, compileJava/compileClientJava 通过。
**文件**:新 `task/GatherQuotaTask`、抽 `action/HarvestCore`(挖+走到掉落物+捡拾的复用核心,并入 OPT-4/RL-7 捡拾改进)、`ToolRegistry`(`gather`)+`assign_task gather`
**改动**:配额循环 `找最近资源→走→采集→计数→未达标继续`(按 item 类型选挖/打/收);满仓 → base 存(RL-13)否则 `fail("inventory_full")`;范围无目标 → `fail("no_resource_nearby")`。**内联复用 HarvestCore,绝不 assign 子任务(G1)**。
**验收**:`gather minecraft:cobblestone 64` → 持续采到 64;满仓自动存箱续采。

## WO-RL-13 · 基地标记 + 智能归仓  (PLAN §RL-13) ✅ done: set_base/deposit_all 与 StockpileTask 已接入, gather 满仓复用归仓状态机, compileJava/compileClientJava 通过。
**文件**:`memory/BotMemory.places`、工具 `set_base`/`deposit_all`、新 `task/StockpileTask`(或扩 `ContainerTask`)
**改动**:`set_base` 记当前坐标为 "base";`deposit_all` 扫 base 半径内容器,按"就近填充 + 同类聚拢"分配存入(`ContainerAction`,G3),逐栈/帧。
**验收**:`set_base` → 采一堆杂物 → `deposit_all` → 自动入 base 附近箱。

## WO-RL-14 · 自动补给(工具/食物耗尽)  (PLAN §RL-14) ✅ done: DangerWatcher/BotTickCoordinator 扫描链路触发 ResupplyTask, 可回 base 取/造工具和食物并恢复暂停任务, compileJava/compileClientJava 通过。
**文件**:`maybeResupply`(并入 RL-9 的 BotTickCoordinator)、新 `task/ResupplyTask`
**改动**:主工具 `getDamage()` 近 `getMaxDamage()`(<10%)或 无食物且饿 → `pauseFor` 原任务 + `ResupplyTask`(回 base→`withdraw` 或 `CraftingHelper.applyCraft` 现造)→ 完成后 `resumeFromPause`/RL-1 恢复。
**验收**:铁镐快断 → 回 base 换镐续挖;饿且无食物 → 回 base 取食物吃。

## WO-RL-15 · 长期农业循环  (PLAN §RL-15) ✅ done: FarmTask keep_tending 长期等待不退出, 增 DEPOSIT/入 base/留种逻辑和 isWaiting 豁免, compileJava/compileClientJava 通过。
**文件**:`task/FarmTask`(增 `DEPOSIT` 阶段 + 留种)
**改动**:`keepTending` 强化:成熟才收、收后补种、周期性入 base、补种后余种入箱(自给);等待成熟阶段 `isWaiting()` 豁免 RL-3。
**验收**:`farm <area> wheat keep_tending` → 长期 收-补种-入箱-留种 循环。

## WO-RL-16 · 冶炼队列(批量/多炉/续料)  (PLAN §RL-16) ✅ done: SmeltTask 支持单炉批量续装/续燃料/收集循环, 燃料可从背包或 base 箱补, compileJava/compileClientJava 通过。
**文件**:新 `task/SmeltQueueTask`(或扩 `SmeltTask`),复用 `SmeltTask` 炉操作 + `FUEL_TICKS`
**改动**:大 `count` 分批装料 + 燃料不足自动从背包/base 补;先单炉大批量 + 自动续燃料,多炉为增强;G3 直接 `AbstractFurnaceBlockEntity`;等待阶段 `isWaiting()` 豁免。
**验收**:`smelt minecraft:raw_iron minecraft:iron_ingot 32` → 续燃料烧完产 32。

---

# W3 · 真玩家行为

## WO-RL-17 · 跟随 / 待命 / 护卫  (PLAN §RL-17) ✅ done: 新增 FollowTask/HoldTask/GuardTask 与 CombatCore, 工具/assign_task 接入 follow/hold/guard, compileJava/compileClientJava 通过。
**文件**:新 `task/FollowTask`/`HoldTask`/`GuardTask`、抽战斗内核 helper(GuardTask + CombatTask 共用)、工具 `follow`/`hold`/`guard`
**改动**:Follow 持续路径到玩家 reach(半径滞回 + 限频重算);Hold `stopAll` 待命(威胁仍交 DangerWatcher);Guard 守护点,敌近**内联**战斗(复用 helper,不 assign,G1)。目标玩家默认 = 指令发起者/owner。
**验收**:`follow` 跟随保持 2–4 格;`hold` 不动;`guard` 守位打怪打完回位。

## WO-RL-18 · 战斗强化(盾/弓/撤退治疗/装备升级)  (PLAN §RL-18) ✅ done: CombatTask 增 RANGED/BLOCK/HEAL 与撤退治疗内联, EquipAction 支持弓箭选择和副手盾, compileJava/compileClientJava 通过。
**文件**:`action/EquipAction`(`bestRangedSlot` + 装盾副手)、`task/CombatTask`(`RANGED`/`BLOCK`/`HEAL` 阶段 + 距离决策 + 撤退治疗内联)
**改动**:远(>近战阈值)用弓蓄力、近用剑、危急副手盾格挡;HP≤阈值 → 退到安全 + 内联 `EatAction` 回血再战;开战前 `equipBestArmor`。**先做 装备升级+盾+撤退治疗(稳),弓为增强**。
**约束**:`⚠️VERIFY` 弓蓄力 `useItem` / `PersistentProjectileEntity`。
**验收**:给弓箭剑盾甲 → 远弓近剑被围格挡低血撤退回血再战。

## WO-RL-19 · 钓鱼  (PLAN §RL-19) ✅ done: 新增 FishTask 自包含钓鱼循环, 接入 fish 工具与 assign_task fish, WAIT_BITE isWaiting 豁免, compileJava/compileClientJava 通过。
**文件**:新 `task/FishTask`、工具 `fish`
**改动**:`FIND_WATER→CAST(useItem 鱼竿)→WAIT_BITE(检测咬钩)→REEL→COLLECT→LOOP`;`WAIT_BITE` 阶段 `isWaiting()` 豁免;咬钩检测读 `ServerPlayerEntity.fishHook` / owner==bot 的 `FishingBobberEntity` 状态,不稳则定时兜底收竿。
**约束**:`⚠️VERIFY` `fishHook` / `FishingBobberEntity` 咬钩状态字段。
**验收**:对水 `fish` → 抛竿-咬钩-收竿-得鱼,循环;渔获进背包。

## WO-RL-20 · 村民交易  (PLAN §RL-20) ✅ done: 新增 TradeTask 直接结算简单一输入村民 offer, 接入 trade 工具与 assign_task trade, compileJava/compileClientJava 通过。
**文件**:新 `task/TradeTask`、工具 `trade`
**改动**:找最近 `VillagerEntity` → `getOffers` → 选可负担 offer → **直接结算**(移除买入物 + `giveItem` 产物 + 维护 `offer.use()`/`villager.afterUsing` 补货/经验),**不开 `MerchantScreen`**(G3);先支持简单 1-input(或绿宝石单买入)offer。
**约束**:`⚠️VERIFY` `TradeOffer`/`MerchantEntity.getOffers`/`afterUsing`/`TradeOffer.use`。
**验收**:附近有村民 → `trade`(买面包)→ 扣绿宝石得面包,村民经验/补货更新。

---

## 进度勾选(Codex 做完一张勾一张)
- [x] RL-1 done: 失败记录、消费式诊断注入和 maxTaskRetries 配置已实现,编译通过。  [x] RL-2 done: plan_craft 只读预检与 missing source 提示已实现,编译通过。  [x] RL-3 done: StuckWatcher、isWaiting 豁免和 stuck 失败回灌已实现,编译通过。  [x] RL-4 done: perception highlights 与 includeRawLists=false 默认裁剪已实现,编译通过。  [x] RL-5 done: /aibot verify 矩阵、跨 tick 轮询和 craft_chain 自动工作台清账已实现,编译通过。  [x] RL-6 done: RuntimeException 边界、API 错误分类、BuildTask 空安全和统一 timeout reason 已实现,编译通过。  [x] RL-7 done: 持久化原子写/异步单飞、owner 恢复和维度兜底已实现,编译通过。  [x] RL-8 done: 目标记忆摘要、任务完成续推和 idle 目标唤起已实现,编译通过。  [ ] RL-9  [ ] RL-10
- [ ] RL-11 [ ] RL-12 [ ] RL-13 [ ] RL-14 [ ] RL-15 [ ] RL-16 [ ] RL-17 [ ] RL-18 [ ] RL-19 [ ] RL-20

---

# 审查记录 · RL-1 … RL-20 验证(2026-05-30,Claude 审查)

## 结构性验证:全面通过 ✅
- **提交**:RL-1…RL-20 共 20 个独立 commit + 1 个 audit 提交,顺序正确。
- **完成度**:无 blocked、代码无 `BLOCKED` TODO。
- **编译**:`./gradlew clean compileJava compileClientJava` 全过 → 20 步所有 ⚠️VERIFY 的 1.21.3 API 解析正确。
- **铁律 G1**:`TaskManager.assign` 仅在 `DangerWatcher`/`IdleCoordinator` 两个编排器;新任务类(Gather/Resupply/Guard/Follow/Hold/Combat/Fish/Trade…)内部零 assign;`CombatCore` 抽取给 Guard/Combat 共用。
- **RL-9 watcher 合并**:`BotTickCoordinator` 单次 all-bots 遍历;三 watcher 重构为 `scanBot/tickBot`;`tickAll` 在 AIBotMod 只调一次(无双 tick);danger 用更高频 `dangerScanInterval`,危险响应未退化。
- **新文件**:HarvestCore / AcquisitionHints / BotReporter / ReasonText / SetOptionC2S / TaskCard / GoalCard / SettingsCard 全部就位。
- **抽查 TradeTask**:直接结算不开 MerchantScreen(G3);显式只选单输入 offer;canFit→扣款→给货 顺序安全。

> **未覆盖:运行期行为**(编译过 ≠ 行为对)。需在 dev server 实测,见下。

## 运行期核验清单(用户在游戏里跑,结果回填)
- [ ] `/aibot verify all` → 记录各 feature 的 PASS / FAIL
- [ ] 端到端「帮我从原料造一把铁镐」:全程走高层任务(不再 `mine_block` 空转)、缺料自动补齐、背包真增长、中文反馈只在面板(RL-1/2/4/10)
- [ ] 停服 → 重启:bot 原位/背包/维度/owner 全恢复(RL-7)
- [ ] `gather cobblestone 64`:持续采集到量,满仓存箱续采(RL-12)
- [ ] 缺料 craft:失败→自动补料→成功;同失败 ≤3 次后改方案,不无限重试(RL-1)
- [ ] 够不到的方块 mine:~10s 自救 abort;冶炼/睡觉不误杀(RL-3)
- [ ] 多 bot(5–10)同时干活:TPS ≥ 19(RL-9)
- [ ] 战斗 / 钓鱼 / 交易 各跑一次(RL-18/19/20)

## 修复工单池(运行期发现 → 登记于此 → 交 Codex 修)
- [x] **FIX-1** `TradeTask.afterUsing` 现走反射(`afterUsing`/`method_18008`):若 1.21.3 该方法可直接调用则改直调,保留反射兜底。(静态审查发现,低优) ✅ done: 1.21.3 方法为 protected,改用 Mixin `@Invoker` 主路径并保留反射兜底; compileJava/compileClientJava 与 runServer 启动验证通过。
- [x] **FIX-2(核心,P0)** 矿物采集走"下挖到矿层"策略,而非 naive MineTask ✅ done: `mine *_ore` 路由到 `StripMineTask`,自动下井清三格楼梯空间,compileJava/compileClientJava 通过;短验收拿到 raw_iron。
  - **现象**:地表(y=105)`帮我挖一些铁矿` → `assign_task mine iron_ore` 连续 `no_block_found:minecraft:iron_ore` 秒失败、反复重试无效。
  - **根因**:`MineTask` 只在局部体积(水平 12→48、**垂直仅 ±6 `VERTICAL_SPAN`**)扫**已暴露**方块;地下矿石扫不到,且它**不会下挖**。
  - **改**:
    (a) **StripMineTask 增"自动下井"**:目标是矿石且局部扫描找不到时,先挖楼梯/竖井**下到该矿合适 Y 段**(iron 地下普遍、深板岩层更密),再支巷 + `OreScan` 跟矿脉。自包含状态机(G1,不 assign 子任务)。先确认现有 StripMineTask 是否在当前 Y 平挖——若是,补"先 descend 到矿层"。
    (b) **工具/Prompt 引导**:`assign_task` 的 `mine` 描述 + `systemPrompt` 明确:**矿石(`*_ore`)用 `strip_mine`,不要用 `mine`**;`mine` 只挖地表/已暴露方块。
  - **验收**:地表对 Bob 说"帮我挖一些铁矿" → 自动下挖到矿层、找到并挖回,`raw_iron` 进背包(全程不再 `no_block_found` 空转)。

- [x] **FIX-3(P0,本次失败的真正主因)** 寻路容错:`GOAL_NOT_STANDABLE` / `NO_START` 自救(卡树顶/非法站位) ✅ done: A* 终点吸附、非法起点安全校正、重寻路与 spawn/restore 站位校正已实现, compileJava/compileClientJava 通过。
  - **现象**:Bob 困在 y=105 树冠,本次会话**十余次** `pathfinding_failed: GOAL_NOT_STANDABLE`(LLM 给的落点在空中/树叶里不可站)+ 最终 `NO_START`(自身位置也不可站)→ 完全无法移动 → 一切采集/挖矿失败到 max_turns。**这才是这次"挖铁矿失败"的根本原因——bot 根本下不了树、动不了,压根没走到挖矿那步。**
  - **根因**:`AStarPathfinder` 起点或终点 `!isStandable` 时**直接失败、零容错**(`elapsed_ticks=0`):起点不可站 → `NO_START`;终点不可站 → `GOAL_NOT_STANDABLE`。而 LLM 在树冠里只能猜坐标,几乎必然不可站。
  - **改**:
    (a) **终点吸附**:`GOAL_NOT_STANDABLE` 时不直接失败,先把 goal **吸附到最近可站方块**(沿该列向下扫地面 → 再周边邻格),用吸附后的 goal 重算。
    (b) **起点吸附/脱困**:`NO_START` 时把 start 吸附到最近可站点(向下找地面);若不可达,触发"脱困"——向下挖/逐格下落到实地后再寻路。
    (c) 次要:排查为何 bot 会停在 y=105 树冠(疑似持久化(RL-7)恢复到上次卡树的位置,或 forage 砍树后遗留站位)——恢复/出生时校正到脚下实地。
  - **验收**:把 bot 放树顶/半空再派 move/采集 → 自动落到地面并正常寻路,不再 `GOAL_NOT_STANDABLE`/`NO_START` 死循环。

- [x] **FIX-4(P1)** 失败原因可执行 + RL-1 换策略引导(防原地重试同一招) ✅ done: `MineTask` 矿石无暴露目标返回 `no_exposed_ore:use_strip_mine`,失败 streak 与待注入分离,连续同因失败会强制提示换策略; compileJava/compileClientJava 通过。
  - **现象**:`no_block_found` 后 LLM 重试**同一个** `mine iron_ore` 共 4 次(count 1→4)才放弃。
  - **改**:(a) MineTask 目标是 `*_ore` 且扫不到 → reason 带建议,如 `no_exposed_ore:use_strip_mine`;(b) RL-1 同(任务+原因)重复 ≥2 次时,注入消息**明确要求换方法**(别再 `mine` 同一矿,改 `strip_mine`/下挖)。
  - **验收**:地表挖矿失败一次后,LLM 即改用 `strip_mine`,不再重复 `mine` 同一矿石。

- [ ] **FIX-5(P0,采集无产出的真正主因)** AI 助手强制生存模式(不跟随召唤者 gamemode)
  - **现象**:`gather/mine birch_log` → `mine_complete` 瞬间完成但 `pickup_timeout`(本会话 mine_complete=2 / pickup_collected=0 / give=0)。日志 `bot_spawned … mode='creative'`、`bots.json "gameMode":"creative"`,`mine_start`与`mine_complete`同秒(创造瞬破)。
  - **根因**:`AIPlayerManager.spawn` 用 `executor.interactionManager.getGameMode()` 跟随召唤者;玩家在创造 → bot 创造。**创造模式破方块不掉落物品**,所以永远没东西可捡。
  - **改**:
    (a) `AIPlayerManager.spawn`:固定 `GameMode.SURVIVAL`(不再读 executor 的模式)。可加配置 `survival.forceSurvival`(默认 true)留开关。
    (b) `respawnFromRecord` / 持久化:恢复时也强制 survival(忽略 `BotRecord` 里存的 creative);或持久化只存/恢复 survival。
  - **验收**:玩家处于创造模式时 spawn Bob → Bob 仍是 survival;砍木正常掉落、`pickup_collected>0`、`gather/mine` 产物进背包。
  - **注意(现有存档)**:当前 Bob 已被存成 creative —— 改完后需 `/aibot despawn Bob` 再 `spawn`(或删 `run/saves/*/aibot/bots.json`),让它以 survival 重新回来。
