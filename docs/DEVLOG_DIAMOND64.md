# 开发记录 · 稳定挖 64 钻石 + 64 黑曜石(diamond64 / obsidian64)

> 本文件是该阶段的**滚动开发记录**:每一阶段做了什么、验证结果、以及**待解决问题清单**。
> 设计蓝图见 `PLAN.md`;历史阶段见 `PLAN_HISTORY*.md` 与 `M*_VALIDATION_REPORT.md`。
> 目标(用户确认):strict_survival 下,端到端稳定完成 MineOre[diamond_ore ×64] 与 obsidian ×64;
> 允许按需扩展觅食、照明、战斗能力。LLM = `deepseek-v4-flash`。

---

## 阶段 0 · 基线修复与模型切换(2026-08-07)

**提交**:`d90352e6`(上批 hunt/route-contract 硬化)、`eed0adf2`(本批)

**完成**:
- `PathExecutor.isExactConstrainedRoute` 抽出为共享精确性契约;`HuntTask` 委托之,
  单元测试不再触发需要 registry bootstrap 的 `HuntTask.<clinit>`。
- `MiningCheckpointMissionGameTests` legacy checkpoint 模拟补移除 `snap_dimension`
  (孤键曾令 restore 正确地 fail-closed:`mission_restore_invalid_replan_snapshot`)。
- DeepSeek 默认模型 `deepseek-chat` → `deepseek-v4-flash`:
  - 显式发送 `thinking {enabled, reasoning_effort=low}`(V4 默认 high 且推理与正文共享
    `max_tokens`,隐式默认会饿死 tool call);`maxTokens` 2048 → 8192。
  - 响应解析记录 `reasoning_tokens`;`finish_reason=length` 且无任何可执行输出时告警
    `api_truncated_before_output`。
  - 用户配置缺 `reasoningEffort` 时不再 NPE(fail 回默认值)。

**验证**:336 单测全绿;576 gametest 全绿(`./gradlew runGameTest`)。

---

## 阶段 1 · P1 拾取恢复滞留修复 + 全链路调研(2026-08-07)

**完成**:
- **P1 根因确认并修复**(两个证据运行):
  - 形态 A(实锤):掉落物移位后未再被观察,`lastSeen` 停留旧格;`approachKnownPickupCell`
    解析的 stand 恰为当前格 → 同格 nudge 每 tick 返回 true("正在追")→ 恢复循环在空柱下
    原地滞留 200 tick 超时。任务失败后 navsafe 偶然挪动一格,原版碰撞立即完成拾取。
  - 形态 B(原始 flake):身体与石墩分数重叠 → 寻路起点校验静默失败 + 20 tick 冷却,无升级无日志。
  - 修复(`OreDigTask`):滞留检测(`updatePickupRecoveryStall`,移动所有权/换格重置)+
    30 tick 阈值升级为**观察扫描**(`startPickupObservationSweepStep`,镜像 Hunt 的
    sweep:绕 last-seen 环形可观察站位走精确无挖掘路线)+ 滞留限频日志
    `ore_dig_pickup_recovery_stalled`。`HarvestCore.startExactPickupPath` 提为 public 共享。
  - 新增确定性 gametest `pedestalLandedDropIsPhysicallyRecovered`(拦截掉落钉到高台,消 RNG);
    修复前 1/3 失败,修复后连续 5 轮全套 577 用例全绿。
- **flaky 测试修复**:`oldNearbyRawDropCannotPoisonFreshKillTransaction` — offset 竞技场仅靠
  假玩家 chunk 票据维持加载,瞬时卸载/重载使闭包持有的实体引用 `isAlive()=false`。
  改为实体延后生成(tick 40)+ 断言时 `getEntitiesByClass` 现查(仓库既有惯例)。
- **全链路调研完成**:8 个并行 agent 审查 7 子系统(148 万 tokens),产出
  [FINDINGS_DIAMOND64.md](FINDINGS_DIAMOND64.md):**11 blocker / 19 major / 20 minor**,
  含预算模型与 10 个需实跑回答的开放问题。

**关键结论(节选)**:
- F2:每批次钻石配额无盈余结转 + 每批次仅 2 个资源 epoch,结构性把任务成功率压到
  ~17–66%(与 bug 无关的数学上限)—— 最高优先级。
- F3/F7:下降途中落点漂移、镐耗尽均直接判 mission 终态(应降级为可恢复)。
- F4/F5/F6:黑曜石链三大死锁(倒水活锁、resumeFirst 排序、食物 8 单位写死)。
- F29:验收体系只有 obsidian 32 场景,64 需新场景与预算标定(即 P3)。

---

## 阶段 2 · 下潜链路加固:F3 + F7(2026-08-07)

**完成**:
- **F3 落点漂移降级**(`DescendToYTask`):origin/target 之外的第三格站位不再立即
  `descend_landing_pose_drift` 终结任务 —— 视为外力位移,清空 pending landing、以当前
  实际站位重新锚定台阶循环,单次下潜内限 8 次(`MAX_LANDING_DRIFT_RECOVERIES`),
  超限才回落原 fail-closed 语义。事件 `descend_landing_drift_recovered`。
- **F7 下潜镐门禁**(`DescendToYTask`):两处生产性台阶挖掘(主台阶 + 横移绕行)在
  `miner.begin` 前检查 `ToolTier.canHarvestWithInventory`,不合格立即类型化失败
  `need_better_tool:<pickaxe_id>`(与 DigDownTask 契约一致),交 GoalExecutor 倒推补镐;
  身体被埋的求生清障**不设门禁**(徒手也必须能脱困)。
- 新增 gametest `knockbackLandingDriftReanchorsInsteadOfFailingTheMission`;
  两个既有 descend fixture 补发铁镐(真实任务中下潜前必有镐,fixture 与生产语义对齐)。

**验证**:578 gametest 全绿;336 单测全绿。

---

## 阶段 3 · F2 稀有矿任务级 margin epoch 池(2026-08-07)

**问题**:64 钻任务切成 8 批 × 8 钻,每批只有 2 个有界资源 epoch(初始 + 1 次 retry,
`MAX_RARE_RESOURCE_RETRIES_PER_BATCH=1`)。钻石产量随机(单 epoch 期望 3–9 颗),单批
10–30% 概率挖不满配额;epoch 1 窗口超时直接 `finishActive` 终结整个任务——哪怕已挖到
63/64。结构性把任务成功率压到 ~17–66%(与 bug 无关的数学上限)。

**方案:有界的任务级 margin epoch 池**
- 新增 `MiningBudget.rareMissionEpochMargin(batchCount) = min(batchCount / 2, 2)`
  (64 目标 → 2,cap 见下),pinned 常量 `DIAMOND_STACK_EPOCH_MARGIN = 2`;每批 epoch
  上限 = `rareMissionResourceEpochCapacity(batchCount) = 2 + margin`(64 目标 → 4)。
- `GoalExecutor.scheduleRareResourceRetry`:批内 retry 耗尽后(epoch >= 1),若任务级
  margin 池还有余额,允许再抽一个 epoch(epoch 2/3/…),机制与既有 retry 完全一致
  (fresh RARE_ORE_BATCH service + 新 24,000-tick OreDig 窗口,硬预算单调不刷新)。
  抽取事件 `rare_epoch_margin_drawn`(used/pool)。
- **持久化账本**:`ActivePlan.rareEpochMarginUsed`,checkpoint 键 `rare_epoch_margin_used`。
  任务级单调递增:批次 closed commit 不归零(与批内 epoch 相反),只有全新 mission 从 0
  开始。restore fail-closed:缺失=legacy 0;非 canonical 非负整数、超池、或
  `epoch - 1 > margin_used`(即出现账本没付过钱的 epoch)一律
  `mission_restore_invalid_rare_epoch_margin`;`normalizeRestoredRareResourceEpoch` 扩展为
  margin epoch 必须与 durable open batch 的 epoch 精确一致。
- **窗口数学**:`MiningMissionBudget.rareOreDigCumulativeHardWindowTicks` 新增显式
  `maxResourceEpochs` 重载(单参旧接口仍只认 2 个常规 epoch);OreDig checkpoint 解码/
  `advanceResourceEpoch` 的 epoch 边界改为任务目标推导的 capacity,普通矿批仍钉死 epoch 0。
- **物资预置(margin epoch 全额上买单,防止变成无界续期)**:
  - 食物:`RARE_BOOTSTRAP_FOOD` 72 → **80**(18 epoch × 4 + 8 buffer);
  - 火把:`DIAMOND_STACK_MIN_BOOTSTRAP_TORCHES` 640 → **720**(18 epoch × 40);
  - 木棍:`DIAMOND_STACK_CHANNEL_REPAIR_STICKS` 224 → **252**,
    `DIAMOND_STACK_BOOTSTRAP_STICKS` 228 → **256**;
  - `forQuota`/`rareMissionFoodTarget` 对任意稀有配额同步加 margin 项(如 18 目标:
    火把 240→280、食物 32→36;32 目标食物 40→48)。
  - **石材不加 margin(对 brief 的偏离,见下)**。
- **服务合约**:`ServicePolicy.rareOreBatch` 接受 margin epoch(上限任务推导),margin
  epoch 复用 epoch-1 retry 的政策形态(食物地板 clamp 到 8、火把/木棍地板不变——margin
  物资是额外携带的,minimum 是地板不是刷新);`rareServiceFoodMinimum` 对 epoch>=2 clamp。
- **外层超时**:`diamondStack64FromZero()` retry 项加 margin(retryOreDig/retryService
  8 → 10),floor 603,200 → 660,800 ticks;live-plan 版本随 nominal plan 自动扩张。

**对 brief 的两处偏离(均源自 36 格主背包物理墙)**:
1. **margin 池 = 2 而非 batchCount/2 = 4**。第一轮全量 gametest 以事实证明 4 个 margin
   epoch 的携带(+160 火把 +56 木棍 = +4 格)让 rare boundary service 的
   `requiredWorkingFreeSlots` 合约不可满足——多个 64 目标 fixture 在 boundary-zero 直接
   `mining_service_inventory_reserve_depleted:free=5:required=7` / 强启弃置 pocket 失败
   (margin 物资全部属于受保护类别,不可弃置)。实测富余仅 2 格 →
   `RARE_MISSION_EPOCH_MARGIN_CAP = 2`(+80 火把 +28 木棍 +8 食物,恰好 +2 格)。
   把池扩回 4 需要 mission-depot 银行化 margin 物资(跨层往返新机制),另行立项。
2. **石材不加 margin**:石材是地下唯一自补给资源,既有设计本就只 bootstrap 首批两个
   pool(后续批由 service 用挖矿 spoil 现造,epoch>=1 的 service 政策只保 EMERGENCY
   储备),margin epoch 与 epoch-1 retry 同型,沿用同一来源;且携带上也放不下。

margin=2 下的结构性收益:单批失败需要连续烧穿"批内 retry + 任务仅有的 2 个 margin
epoch"才终结任务;按调研的 10–30% 单批缺口概率,任务级失败率显著低于原 1-shortfall 即死。

**验证**:`./gradlew test` 340 单测全绿(新增 margin 池数学、窗口重载、margin 账本
decode/restore fail-closed、normalize margin epoch 归属、epoch 超时分类共 5 个用例);
`./gradlew runGameTest` 580 gametest 全绿(新增
`epochOneTimeoutWithMissionMarginSurvivesAndDrawsOneEpoch`——epoch 1 精确 48,000-tick
超时 + margin 可用 → 任务存活、原子抽取 1 个 margin epoch、硬预算不刷新;
`epochTimeoutWithExhaustedMarginPoolStaysTerminal`——margin 耗尽后同型超时仍 terminal;
既有 `sameBatchEpochOneChannelToolFailureIsTerminalWithoutAnotherService` 注入
margin_used=2 保持 terminal 语义;checkpoint round-trip 补 margin 键断言)。
descent-kit 压力 fixture 因 margin 物资多占 3 格改为只携带 1 把木镐(木/石镐退役合约
不变);`diamond64RestoresMissionKit` 拥挤边界由 free=4 收紧为 free=2。
两个 diamond64 coal-bootstrap fixture(`diamond64BootstrapCoal…` 与
`spawnDiamond64CoalBootstrapMiner`)按扩大后的合约补给:720 火把把煤链扩到 12 批,
channel-repair 镐头需 56×3=168 石材,圆石 160→**192**(仍 3 格),否则规划器会在煤
OreDig 前插入 挖石头 绕行、撞 fixture 的 tick 80/100 死线;木棍 234→262
(=BOOTSTRAP_STICKS+6)、原木收敛到 64(1 格)保持携带量贴近 margin 前基线。

---

## 阶段 4 · 黑曜石链死锁:F4 + F21(2026-08-07)

**完成**(`CreateObsidianTask`):
- **F4 倒水活锁**:flat-pool 地形的倒水点离岩浆线索最远 4 格,而原版水每 5 tick 推进
  一格 —— 固定 4-tick 等待意味着水永远到不了岩浆、回收时世界零变化、同一线索无限重放
  (每轮还会 `noteTopologyProgress` 重置 800-tick 停滞检测,直到烧穿 153,600-tick 任务
  预算)。修复:①等待时长按 `max(4, 距离×5+4)` 缩放(`pourSpreadWaitTicks`,上界
  24 tick);②记录本轮浇灌的线索(`lastPourClue`),排空周期结束仍无转化、无拾取且该
  线索可观察地仍为岩浆时,`rejectLava` 进入有界拒绝账本(TTL 600)轮换搜索,事件
  `create_obsidian_barren_pour_rejected`。遮挡不判负(只拒绝"观察到仍是岩浆")。
- **F21 复核后否决**:曾按调研建议改为 restore 无条件 `enter()`,被既有 checkpoint
  往返 gametest 当场击落(`restore changed task checkpoint key phase_started`)。复核
  结论:任务时钟是任务 tick 制,**暂停期间不走表**,安全抢占不会烧相位窗口;跨进程
  restore 按设计"续剩余窗口而非刷新时钟"(防重启刷预算)。调研对 F21 的定性有误,
  已回退,findings 标注 `[复核否决]`。教训:agent 结论必须过既有契约测试的裁决。

**验证**:并入 F2 收尾后的统一全量套件验证(见阶段 3/5 记录)。

---

## 阶段 5 · F6 黑曜石食物预算按任务量缩放(2026-08-07)

**完成**:
- `MiningBudget.obsidianExpeditionFoodTarget(missionTarget)`:每个 8 块 service 段 4 个
  熟食 + 4 buffer,地板保持旧 8 单位(prepared 短程合约不变)。64 块 → **36** 单位、
  32 块 → 20、16 块 → 12,均 1 格以内。旧的写死 8 单位在 64 块任务中途必然断粮,深层
  既无猎物也无预置 depot,`mining_service_food_reserve_depleted` 无解(F6)。
- 公式落在无 registry 依赖的 `MiningBudget`(可纯单测;GoalPlanner `<clinit>` 需要
  bootstrap,教训同 HuntTask)。`MiningPlanningSourceContractTest` 改钉缩放调用;
  新增 `ObsidianFoodBudgetTest`(地板/缩放/取整/单格上限);3 个 planner gametest 的
  fixture 口粮与断言随常量派生(20 单位)。

**验证**:343 单测全绿;580 gametest 全绿。

---

## 阶段 6 · F11 已交付批次的重启冻结(2026-08-07)

**完成**(`OreDigTask.finishAlreadyDeliveredBatch`):
- `targetCount==0` 快路径先于硬超时执行,其 UNKNOWN 分支(重启后的 restore 站位观察
  不到 `active_break_pos`)原来每 tick 空转 `return`,没有任何东西能终结它 → 无界冻结。
- 修复:观察恢复窗口有界化 —— 空转时每 20 tick 沿断块格的可观察环形站位走一步
  (`startObservationSweepStep`,由 P1 的拾取扫描泛化共用);超过 `RESTORE_FACE_LIMIT`
  (1200 tick)仍不可观察,则取**少记不多记**的 exact-once 保守结果:
  `clearActiveTargetBreak`(矿留在世界里,绝不虚计),事件
  `ore_dig_delivered_batch_break_unobservable`。

**验证**:343 单测全绿;580 gametest 全绿。
**TODO**:补一个确定性 gametest(restore 注入 targetCount=0 + 断块格被遮挡的 fixture,
断言有界窗口内脱困)——列入 P5。

---

## 阶段 7 · 双死锁修复:F5 黑曜石 resumeFirst 缺物资重排 + F8 容量父命名空间孤儿(2026-08-07)

**完成**:

- **F5 黑曜石 resumeFirst 缺物资死锁**(`GoalExecutor.reconcileObsidianSteps`):
  开放事务(waterSource/pickupPos/activeBreakPos)下的 replan 原来无条件把 resume 步插到
  index 0 —— 排在 fresh 计划的补给步(新桶/替换钻镐/取水)之前。若失败原因本身就是
  "缺物资"类,恢复任务第一 tick 以同因重败,3 次零进展 replan 判死整个任务。修复:
  - 失败原因命中精确前缀集(`need_better_tool:` / `create_obsidian_bucket_lost_after_pour` /
    `*_missing_water`,新帮助函数 `isObsidianMissingResourceFailure`)且 resume-first 时,
    保留 fresh 计划中首个 MAKE_OBSIDIAN 之前的补给前缀并让它先物理执行,resume 步插到
    前缀之后;其余失败原因维持今天的 resume-first(物理续作的正确顺序)。
  - 重排决策记录独立事件 `goal_obsidian_resume_resupply_first`(reason/supply_steps/target)。
  - fresh 计划里没有 MAKE_OBSIDIAN(无可证实的补给前缀)或规划失败时,行为与旧逻辑
    完全一致(resume 置顶)。restore 路径(`rebuildObsidianAcquisition`)不变:重启后若
    资源仍缺失,第一次失败即走修复后的 replan 重排,单次 replan 内自愈,不会烧穿 3 次。
- **F8 容量父命名空间孤儿**(`GoalExecutor.handleStepFailure` 通用 replan 段):
  容量 handoff 服务失败 → 通用 replan `steps.clear()` 销毁精确 retry 步,但从不清
  `capacityParentNamespace`;若 fresh 计划不再包含父矿族,结算永远不可达 —— 证据采集
  从此拒绝所有 MINE_ORE 的 `plan.miningCheckpoint` 更新,下一个成功提交的稀有批次在
  成功那一刻死于 `rare_batch_commit_checkpoint_invalid`。修复(与安装新队列同一事务):
  - replan 时若 `capacityParentNamespace != null` 且 fresh 计划中不存在能重新绑定该
    debit 的同族 MINE_ORE 步(fingerprint + `acceptsStepTarget` 与指派期
    `isCapacityParentRetry` 同判据),回滚标记与全部 watermark
    (delivered/face/services_used),事件 `goal_capacity_parent_rolled_back`。
  - AUXILIARY 命名空间连同悬空的 open 普通游标一起丢弃(无物理台账,已交付产物在背包,
    规划器按库存如实重算;保留反而令重启在缺标记的 aux 命名空间上 fail-closed);
    MINING 命名空间沿用 `goal_failed_primary_service_retired` 同型的游标退役。
  - **fail-closed 边界不放松**:父 checkpoint 无法解码、带未结物理台账
    (pending_pickup/active_break)、或 `rare_mission_target != 0`(标记指向稀有游标的
    不可解释状态)一律不回滚,维持既有语义。restore 校验未改动 —— 回滚发生在持久化之前的
    同一事务内,不引入新持久键;既有 `mission_restore_orphaned_capacity_handoff_cursor`
    等出口原样保留。

**回归测试**:
- 单测 `GoalExecutorObsidianResumeReconcileTest`(新增 5 用例):补给前缀重排的精确索引与
  顺序、物理续作 resume-first 不变、无前缀/空计划回退、closed-transaction 原位替换契约、
  失败原因前缀集的精确范围。
- gametest `CreateObsidianMissionRecoveryGameTests.missingToolFailureWithOpenTransactionResuppliesBeforeResuming`:
  restore 一个 target 32 的开放 active-break 事务(世界里真放黑曜石),移除钻镐注入
  `need_better_tool:minecraft:diamond_pickaxe` → 断言任务存活、补给步先于 resume 被指派、
  obsidian.* 命名空间原样保留事务身份,CRAFT 补镐完成后 MAKE 以原 target/active_break
  恢复运行。
- gametest `MiningCheckpointMissionGameTests.failedCapacityHandoffWithoutParentFamilyRollsBackDebtAndRareBatchSettles`:
  伪造 auxiliary 容量父(open 铁矿 debit)+ 受保护 diamond64 稀有游标 + 预算耗尽的容量
  服务,restore 后服务典型化失败 `mining_service_timeout:` → 通用 replan(fresh 计划经
  预检不含铁族)→ 断言标记/watermark/aux 命名空间全部回滚、稀有游标保留;再 restore 一个
  交付满额(delivered=8/8)的稀有批次,断言其 commit 正常结算
  (`mining.batch_open=false`、epoch 归零),而非死于 `rare_batch_commit_checkpoint_invalid`。

**过程教训**:F8 gametest 第一版在 tick 回调内部再注册 `context.runAtEveryTick`,直接
NPE 崩掉 GameTest 调度器(`GameTestState.tickTests` 迭代中修改监听表)—— 改为在 probe 内
同步驱动 commit(`AbstractTask.abort` 对 COMPLETED 是 no-op,仅清 TaskManager 槽位后手动
`tickBot` 结算)。嵌套注册 tick 监听是本仓库 gametest 的硬禁区。

**验证**:`./gradlew test` 348 单测全绿(343 + 新增 5);`./gradlew runGameTest` 582 用例
(580 + 新增 2)连续两轮全绿。

---

## 待解决问题(滚动清单)

| ID | 严重度 | 问题 | 状态 |
|---|---|---|---|
| P1 | major | 拾取恢复滞留活锁(高台掉落 / 同格 nudge 假进展 / 静默 NO_START)| **已修**(阶段 1) |
| F1-F50 | 见报告 | 全链路调研发现清单,详见 [FINDINGS_DIAMOND64.md](FINDINGS_DIAMOND64.md);修复进度在该文件逐条标注 | 攻坚中 |
| P2 | 待评估 | diamond64/obsidian64 全链路稳定性缺口 — 七子系统并行调研进行中(OreDig 恢复、Planner 批次、Executor 重规划预算、Obsidian 岩浆链、Descend 往返、生存中断恢复、预算/证据体系),产出后按影响排序逐项立项。 | 调研中 |
| P3 | major | **黑曜石目标 32 → 64**:现有验收契约(`docs/MINING_ACCEPTANCE.md`)只定义了 `obsidian_half_stack_32`;用户目标是 64。需扩展 Goal/预算/验收场景到 64,并核对岩浆源池容量假设(单湖是否稳定供 64 块)。 | 待立项 |
| P4 | note | **认证长跑成本**:from-zero 钻石 live plan 已声明 2,120,000 ticks(15 TPS ≈ 39 小时/run),20-seed 门禁是天级算力。本阶段交付"能力与稳定性 + 可复验入口",sealed 批量认证由用户择机启动(`scripts/evidence_batch.sh`)。 | 已知约束 |

---

## 里程碑规划(随调研结果细化)

- **S1 修复已知不稳定点**:P1 及调研发现的 blocker 级缺陷。
- **S2 挖矿链路加固**:64 目标全程的批次/预算/checkpoint 一致性。
- **S3 黑曜石链路加固**:岩浆场景的掉落保全与安全恢复。
- **S4 生存扩展**:按需扩展觅食(地下食物经济)、照明(火把节奏)、战斗(隧道遭遇)。
- **S5 端到端证据**:evidence run 连续 N 次 diamond64+obsidian64 通过率验收。
