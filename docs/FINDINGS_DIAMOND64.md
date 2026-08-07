# diamond64 / obsidian64 全链路稳定性调研(7 子系统并行审查 · 2026-08-07)

> 由 8 个并行审查 agent 产出并汇总(逐条含 file:line 证据)。状态标注由开发过程维护:
> `[已修]` 已落地并验证 · `[进行中]` · 空白 = 待处理。

# Findings (ranked by expected impact on mission completion rate)

Citation shorthand (as used in the source reports): `COT` = src/main/java/io/github/zoyluo/aibot/task/CreateObsidianTask.java, `AWT` = src/main/java/io/github/zoyluo/aibot/task/AcquireWaterTask.java, `OSC` = src/main/java/io/github/zoyluo/aibot/task/ObsidianSearchCursor.java, `GE` = src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java, `GP` = src/main/java/io/github/zoyluo/aibot/goal/GoalPlanner.java. Unprefixed class names (OreDigTask, HarvestCore, etc.) refer to their files under src/main/java/io/github/zoyluo/aibot/.

## Blockers

**[已修·阶段1] F1 — Pedestal-landed drop pickup livelock, escalated to whole-mission failure** (blocker; OreDigTask/HarvestCore pickup + GoalExecutor failure policy; from: oredig D1+S2, executor F2)
- Citations: OreDigTask:3315-3417, OreDigTask:3328-3333, OreDigTask:3356, OreDigTask:3364-3373; HarvestCore:419-471 (esp. 430-438, 439-470), HarvestCore:286-301 (unconditional `return true` at 294-301); FakePlayerMotion:239, 248, 264-266, 270; OreDigPickupGameTests:1116-1174; GE:3701-3707.
- Trigger: a mined drop rests on top of a 1×1 pedestal adjacent to the bot. `pickupStandPos`'s "one-higher" branch requires the below-cell to be standable (it is the pedestal itself), so the candidate ring picks the bot's current cell (distance 0). The `current.equals(stand)` branch does a 0.15-block in-cell nudge that can never cross the cell boundary, unconditionally returns true (swallowing nudge failure), so the fallback at OreDigTask:3364 never fires. Repeats every tick until the 200-tick deadline fails with `ore_dig_drop_unrecovered` — which GE:3701-3707 classifies as fail-closed terminal, killing the entire 64-diamond mission. This is the confirmed root cause of the known intermittent gametest failure.
- Smallest fix: add a pedestal branch to `pickupStandPos` (return `itemPos` when `itemPos.getY()==current.getY()+1 && !isStandable(below) && isStandable(itemPos)`, letting the existing exact-pickup-path branch climb it); make HarvestCore:294-299 return the real nudge result; in GE, demote `ore_dig_drop_unrecovered` for rare missions to a skipped-drop receipt + one make-up target instead of terminal.

**[已修·阶段3] F2 — Per-batch diamond quota with no surplus carryover structurally caps mission success at ~17–66%** (blocker; budget model; from: evidence)
- Citations: MiningBudget.java:42 (`MAX_RARE_RESOURCE_RETRIES_PER_BATCH=1`), :46-47, :29-30, :34, :40, :103-104; MiningMissionBudget.java:15; OreScan.java:61-107 (vein flood-fill, :85).
- Trigger: each batch of 8 diamonds must be found within its own 2 resource epochs. Expected yield is ~3–9 diamonds/epoch (6–18 per batch), so quota 8 sits at the low end of the expectation band; estimated single-batch shortfall probability is 10–30%, compounding over 8 batches to roughly 0.8^8≈17% … 0.95^8≈66% mission success — even with zero bugs.
- Smallest fix: carry diamond surplus across batches (count already-owned diamonds toward the next batch quota) and/or allow drawing extra retry epochs from mission-level margin instead of the hard per-batch retry=1 gate.

**[已修·阶段2] F3 — Descend landing drift is mission-terminal; single-tick knockback window** (blocker; DescendToYTask + GoalExecutor; from: descend A2)
- Citations: DescendToYTask.java:539-546, 818-828, 367-378, 380-392; GE:3678-3679, 3706.
- Trigger: after `descendInto` succeeds, one tick later feet must be at origin or target; a bare mob knockback (no pause) between task ticks pushing the bot to a third cell fires `descend_landing_pose_drift`, which is fail-closed terminal. A Y=64→-58 descent has ~122 such windows; both missions descend multiple times.
- Smallest fix: demote drift to a recoverable restart/replan of the descent step instead of mission-terminal.

**[已修·阶段4] F4 — Obsidian flow-control livelock burns the entire task budget** (blocker; CreateObsidianTask; from: obsidian D1)
- Citations: COT:670-696, 1128-1148, 61 (`WATER_SPREAD_TICKS=4`), 1150-1165, 2923-2944, 1146, 2545-2550, 56, 495-500.
- Trigger: at a lava-lake edge with only flowing lava visible, pour plans of geometry 3/4 place water not adjacent to the lava; water spreads 1 block per 5 ticks, so the 4-tick wait always recovers the water with zero world change; the clue is never rejected, `noteTopologyProgress` keeps resetting the 800-tick stall detector, and the ~70–170-tick loop repeats until the 153,600-tick `create_obsidian_timeout`.
- Smallest fix: when `pourPlan.destination()` is not adjacent to the clue, wait `PROTECTION_SPREAD_TICKS` (20) or distance×5 ticks; or `rejectLava(clue)` when post-recovery fluid state is unchanged.

**F5 — Obsidian `resumeFirst` reconciliation makes "open transaction + missing resource" an unreachable-repair deadlock** (blocker; GoalExecutor/CreateObsidianTask; from: obsidian D2)
- Citations: COT:199-204; GE:3791-3803, 4835-4842; COT:1627-1629, 1674-1680; GE:3903-3909 (MINE_ORE-only tool recovery); COT:1278-1281, 318-320; GE:71, 3777-3783.
- Trigger: with a transaction open (waterSource/pickupPos/activeBreakPos set), replan deletes all new MAKE_OBSIDIAN steps and inserts the resume step at index 0 — ahead of the resupply steps (new bucket / diamond pick). The resumed task refails instantly (`need_better_tool:`, `create_obsidian_bucket_lost_after_pour`, or onStart fail without bucket); zero progress → 3 consecutive replans → task dead.
- Smallest fix: when resuming first, keep supply steps that precede MAKE_OBSIDIAN in the fresh plan and match the failure-reason prefix (`need_better_tool:` / `*_missing_water` / `bucket_lost`).

**[已修·阶段5] F6 — Obsidian food budget fixed at 8 units with no underground resupply source** (blocker; GoalPlanner/MiningServiceTask; from: planner F1; interacts with F15)
- Citations: GP:66 (`OBSIDIAN_EXPEDITION_FOOD=8`), 566 (contrast: diamond 72), 1203-1210, 1227-1228; MiningFoodReserve.java:18; MiningServiceTask.java:509/529, 3091-3095; GP:1633-1656 (no CHEST/depot ensured), 1230-1233; GoalStep.java:23-41 (no ascend/return-to-surface step kind).
- Trigger: 64-block obsidian work exhausts 8 cooked units; the boundary service can only draw from a depot that was never provisioned, and no step kind can return to the surface for food → `mining_service_food_reserve_depleted` / `deep_mining_food_reserve_depleted` with no recovery.
- Smallest fix: scale obsidian food with missionTarget (like the diamond line) and/or provision the CHEST/depot the boundary service expects.

**[已修·阶段2] F7 — DescendToYTask has no tool gate; broken pick burns the full window then dies as untyped `descend_timeout`** (blocker; DescendToYTask/BlockMiner; from: descend A3)
- Citations: DescendToYTask.java:532-537, 894-900, 475-483, 1080-1090; BlockMiner.java:32, 95-101; DigDownTask.java:614-616, 839-840 (contrast: has gate + typed `need_better_tool:`); MiningMissionBudget.java:39-43; GE:3681.
- Trigger: pick durability runs out mid-descent (routine in a 64-diamond mission). `miner.begin` refreshes the progress clock every ~200-tick BlockMiner timeout cycle; lateral detours are not consumed; the loop only ends at the 12,160–40,000-tick window, then `descend_timeout` is goal-terminal and carries no type for the planner to infer "craft a pick".
- Smallest fix: add the same ToolTier gate + typed `need_better_tool:` failure as DigDownTask, and only count real block breaks as progress.

**F8 — Orphaned capacity-parent debt makes a successful rare batch fail the whole mission** (blocker; GoalExecutor; from: executor F1)
- Citations: GE:4272-4382 (esp. 4364-4365), 3872, 3551-3879 (no path clears `capacityParentNamespace`), 5451-5470, 1581-1590, 4992-5034, 4916-4920, 4952, 1571-1575, 552-601, 3435-3440.
- Trigger: a capacity-handoff service step fails → generic replan clears the queue (destroying the retry step) without clearing `capacityParentNamespace`; if the fresh plan lacks that ore family, settlement is unreachable forever; evidence capture then refuses to update `plan.miningCheckpoint` for all MINE_ORE, so the next completed rare batch fails `rare_batch_commit_checkpoint_invalid` at the moment of success. Restore validation accepts the orphan state, so restarts don't heal it.
- Smallest fix: roll back/settle the capacity-parent marker whenever generic replan drops the retry step (or the fresh plan lacks the parent family); or degrade `settleCompletedRareBatch` mismatch to re-capture instead of terminal.

**F9 — Recovery gate deadlock: hp≤10 with no food strands the paused mining task forever** (blocker; DangerWatcher/Resupply; from: survival D1)
- Citations: DangerWatcher.java:460-464, 545, 444-449, 633; AIBotConfig.java:152 (retreatHp=10); EatTask.java:66-73; ResupplyTask.java:149-158, 300-310, 340.
- Trigger: `canResumePausedWork` requires health > 10, but with no food there is no deterministic path to regain HP: EatTask fails `no_food`, hunting needs surface prey, ResupplyTask degrades to CraftTask(BREAD) → `no_supply`. The paused mining task is stranded; only the non-deterministic BrainCoordinator wake remains.
- Smallest fix: add a deterministic "return to surface and forage" escalation (or allow resume at reduced HP with shelter-first behavior) when the food-recovery chain is exhausted.

**F10 — CreeperDefense has no deadline and blocks eating → starvation stalemate** (blocker; CreeperDefenseTask/DangerWatcher; from: survival D7)
- Citations: CreeperDefenseTask.java:288-299, 347-352, 868-871, 559-583, 364-378; DangerWatcher.java:247-267; SurvivalGuard.java:31-36; TaskManager.java:435-443.
- Trigger: a visible but unreachable creeper (behind a gap/fence at 8–15 blocks) underground: not wall-urgent, visibility forbids completion, escape goal stays null → infinite ESCAPE; `scanBot` early-returns so eat/resupply never run; the bot starves with the mining task paused beneath it.
- Smallest fix: add a "stalemate N ticks at ≥ safe distance" downgrade exit, and permit hold-eat during ESCAPE stalls.

**[已修·阶段6] F11 — `targetCount==0` fast path bypasses hard timeout and face restore → unbounded post-restart freeze** (blocker; OreDigTask; from: oredig B1)
- Citations: OreDigTask onTick:879-881 (before 883-886 hard timeout and 887-890 restoringFace), 1433-1441, 1442-1445, 366-370 (`isWaiting` suppresses StuckWatcher), onStart:746-755.
- Trigger: last target ore's active break committed → process restart → restart stance cannot observe `active_break_pos` (UNKNOWN preserved) → `finishAlreadyDeliveredBatch` returns without motion or deadline every tick, forever.
- Smallest fix: in the UNKNOWN branch, perform `returnToSavedFace`-style movement when idle, bounded by `RESTORE_FACE_LIMIT`; on expiry conservatively `clearActiveTargetBreak` per the existing exact-once semantics.

## Major

**F12 — "Displacement = progress" defeats both replan circuit breakers for mining steps** (major; GoalExecutor; from: executor F3)
- Citations: GE:3279-3281, 3764-3766, 71, 3867-3871, 3294-3300; secondary bypass: `GoalStep.equals` includes count (GE:3867).
- Trigger: mining attempts almost always move ≥8 blocks, so `replanCount` resets every failure; a pathological area can burn all 24 lifetime replans (64-target mission) with zero real progress, each cycle also burning a full OreDig hard window.
- Smallest fix: for MINE/MINE_ORE, define progress by delivered target count (or checkpoint advance), not displacement; compare steps modulo remaining count for `replan_same_step`.

**F13 — DROP_DOWN with fallHeight≥2 on an anchored route is a deterministic plan/execute livelock** (major; PathExecutor/AStarPathfinder; from: descend A1)
- Citations: PathExecutor.java:213-215, 220-223, 618-653, 670-671; Standability.java:139-140; AStarPathfinder.java:213-233; NeighborEnumerator.java:155-173; AIBotConfig.java:156 (maxSafeFall=3); HuntTask.java:959-962 (affected caller).
- Trigger: mid-fall cell is unstandable → runtime return-proof start snaps → `runtime_return_start_not_exact` → `route_contract_lost`; planning-time validation never filters fall≥2 nodes, so replan regenerates the same route indefinitely. Hits the hunt/bootstrap phase of both missions on rough terrain.
- Smallest fix: skip runtime contract validation at intermediate fall cells (or prove from the landing cell), or filter fall≥2 drops from constrained routes at plan time.

**F14 — Destroyed/lost obsidian drop has no write-off path; recovery re-fails instantly** (major; CreateObsidianTask/GoalExecutor; from: obsidian D3)
- Citations: COT:1777-1782 (degraded PROTECT_PICKUP), 1871 (±8 box), 64, 1846-1852 (`PICKUP_LIMIT=600`), 2121, 335-377 (no PICKUP timer reset); GE:3552, 3678-3707 (reason not whitelisted).
- Trigger: drop burns on exposed lava or is pushed out of the search box → PICKUP times out; checkpoint restores stale `phaseStartedTick`, so resume fails on first tick; 3 replans wasted, task dies.
- Smallest fix: on resume with PICKUP expired, broken cell AIR, and no observable ItemEntity in the box, record a skipped-target write-off and continue; at minimum add the reason to the terminal whitelist to stop burning replans.

**F15 — Underground food economy runtime gaps: passive eating + no deterministic surface-return for food** (major; DangerWatcher/HuntTask/MiningServiceTask; from: survival D4+D5; interacts with F6, F9)
- Citations: DangerWatcher.java:561-604, 1124-1128; AIBotConfig.java:151 (Survival(14,6)); EatTask.java:87-98; HuntTask.java:1939-1947, 352-357, 364-367; GP:143-162; MiningServiceTask.java:2832 (floor only checked at boundaries), 2832-2838, 3071-3088, 3145-3148.
- Trigger: during multi-thousand-tick batches hunger sits below 18 (no natural regen); once reserves hit zero mid-batch at Y=-58, hunting is surface-only twice over and nothing between service boundaries recovers food → deterministic starvation path.
- Smallest fix: an "eat to 18+" hook at OreDig/MiningService tick boundaries, plus a deterministic surface-return-for-food escalation.

**F16 — Death at depth = total loss: corpse-run refused fail-closed** (major; DangerWatcher; from: survival D2)
- Citations: DangerWatcher.java:106-126, 146-150; GE:1474-1499 (task itself survives death).
- Trigger: any death at Y=-58 hits `deep_route_without_trail` (|Δy|>24 or >80 blocks refused); picks, torches, food and already-mined diamonds despawn in 5 minutes; mission restarts naked from surface. Amplifies every survival gap above into hours of lost progress.
- Smallest fix: allow bounded corpse-runs along the recorded descent/mining trail, or bank diamonds at service boundaries more aggressively.

**F17 — One-way descend gate: coal contract executes at Y16 instead of Y48** (major; GoalPlanner; from: planner F2)
- Citations: GP:543-544, 580-603, 718, 622-647, 669-671; MiningChain.java:29 (coal bestY=48).
- Trigger: iron contract descends to Y16 and sets plannedY=16; the subsequent ~160-coal chain never corrects upward (no ascend step), so coal is mined at a low-density layer, burning ore_dig budget — the documented pathological pattern.
- Smallest fix: make the gate bidirectional (also emit an ascend/correction when plannedY is below the target chain's bestY).

**F18 — Failed path start silently swallowed on UNKNOWN primary target → whole-task `ore_dig_no_progress`** (major; OreDigTask; from: oredig S1)
- Citations: OreDigTask:1162-1163, 1170 (early return makes 1215-1238 unreachable), 3042-3053 (contrast: has fallback), 3109-3125.
- Trigger: persistent pathfinder rejection (throttled/no route) on the UNKNOWN-owner branch freezes the bot until NO_PROGRESS_LIMIT=200 fails the entire task instead of excluding one ore.
- Smallest fix: on failure route through `continueUnknownOwnerApproach` (guards already present), or attach the F19 lease to this owner.

**F19 — High-work-pose route lease keeps ticking during pickup debt; reachable ores get excluded** (major; OreDigTask; from: oredig H1+H2)
- Citations: OreDigTask:3071-3074, 3080-3086 (APPROACH_LIMIT=80), onTick:969, 115 (`TARGET_DROP_RECOVERY_LIMIT=200`), 3127-3139 (lease untouched), 3068-3070 (arrival shortcut doesn't clear lease).
- Trigger: stacked vein — pickup recovery (up to 200 ticks) exceeds the 80-tick lease, so the next remembered-pose attempt instantly aborts and excludes a reachable ore (TTL 30s), feeding `consecutiveSkips` over a 64-target run.
- Smallest fix: clear the lease in `beginPendingTargetDrop`, or count only ticks where the route is actually issued.

**F20 — Runtime return-proof budget is fixed while proof distance grows; per-cell main-thread A*** (major; PathExecutor/AStarPathfinder; from: descend A4)
- Citations: PathExecutor.java:566-572, 366-368, 27-28, 664-668; AStarPathfinder.java:69-71, 38-39 (ε=1 degeneration acknowledged), 122 (`Standability.clearCache()` per proof); HuntTask.java:940-943 (RETRY-forever mapping).
- Trigger: anchored routes beyond a budget-determined radius always fail `runtime_return_failed:TIMEOUT/SEARCH_LIMIT` mid-route; worst case burns 50ms (a full server tick) per cell walked and thrashes the global standability cache.
- Smallest fix: scale lease budget with distance or reduce proof frequency; stop clearing the global cache per proof.

**[复核否决·阶段4] F21 — RECOVER_WATER / RETURN_TO_RIM resume with stale timers → instant deadline death after combat displacement** (major; CreateObsidianTask; from: obsidian D4)
- Citations: COT:348-353, 60 (`RECOVERY_LIMIT=100`), 1343-1355, 354-358, 1894-1897.
- Trigger: safety preemption at the lava lake displaces the bot; resuming into the same phase keeps old `phaseStartedTick`, so the 100-tick window is already expired on the first tick.
- Smallest fix: unconditionally `enter()` these phases on restore (windows are bounded, so no budget inflation).

**F22 — Postcondition-repair budget: 3 lifetime, never refilled on progress, but restore flow depends on it** (major; GoalExecutor; from: executor F4)
- Citations: GE:72, 3147, 1372-1379, 2222-2225, 3195-3200 (progressful repairs still debit), 1245-1246, 1283-1290, 4665-4690, 3169.
- Trigger: >3 crash-restore cycles across a 64-target mission exhaust the budget and die `postcondition_unsatisfied` even when every repair made progress.
- Smallest fix: refund the budget when `shouldAcceptPostconditionRepair` confirms progress.

**F23 — Second inventory-full event within one rare batch is mission-terminal** (major; GoalExecutor; from: executor F9)
- Citations: GE:3639-3645, 4322-4323 (rare excluded from bounded capacity handoff).
- Trigger: two `ore_dig_inventory_service_required` in the same rare batch kill the task although another depot trip would recover.
- Smallest fix: allow a second (bounded) depot trip per rare batch.

**F24 — Descend lateral-detour exhaustion is mission-terminal; no relocation after work starts** (major; DescendToYTask; from: descend E3)
- Citations: DescendToYTask.java:55-67 (MAX_LATERAL=32), 505/525/813, 909-950 (pre-start 3x3 relocation only); GE:3683; DigDownTask.java:290-397 (contrast: entry relocation exists).
- Trigger: wide aquifer/large cavern (>32 unique edges) → `descend_no_safe_landing` kills the mission instead of moving the shaft.
- Smallest fix: add post-start shaft relocation (reuse DigDownTask's entry-relocation pattern) before terminalizing.

**F25 — Surface resume demands full mission bootstrap for a tiny remainder** (major; GoalPlanner/MiningServiceTask; from: planner F4)
- Citations: GP:566, 624-631, 531-534; MiningBudget.java:57-58; MiningServiceTask.java:3397-3411 (`rareDescentKitReady` same yardstick).
- Trigger: owned=63, surface replan still requires 640 torches + 72 cooked food live, spawning a ~150-coal expedition and a dozen hunts for the last diamond — timeout risk far exceeding remaining work.
- Smallest fix: key bootstrap gates on remaining quota (batchCount of the gap), not the original `count`.

**F26 — Hunt pickup receipt fragile across restart (stats + inventory baselines both perishable)** (major; GoalExecutor/HuntPickupCheckpoint; from: executor F7)
- Citations: GE:1783-1803, 351-363, 1701-1710, 1776-1778 (CLOSED_NO_RAW needs world time ≥240); HuntPickupCheckpoint.java:147-153; DangerWatcher.java:557-611 (eat can consume bound raw meat); no bot-stat persistence in src/main/java/io/github/zoyluo/aibot/persist/.
- Trigger: restart drops `Stats.PICKED_UP` (if ServerStatHandler doesn't persist for fake players) and pre-shutdown eating consumed bound units → restore rejects the whole mission (`mission_restore_invalid_hunt_pickup_checkpoint`) or settles FAILED; world-time rollback also invalidates receipts.
- Smallest fix: persist the stat baseline (or a monotonic pickup counter) in the checkpoint itself; verify stat persistence in a live run (see open questions).

**F27 — Skeletons are unsolvable: leash 8 < bow range ~15, no bow ever provisioned** (major; CombatTask/GoalPlanner; from: survival D9)
- Citations: CombatTask.java:44, 539-544, 416-428, 564-569 (RANGED phase dead code — no bow ensure anywhere in GP).
- Trigger: any skeleton engagement: target leaves leash → disengage/retreat loop; surface nights during hunt/cook steps and underground corridors both suffer repeated interruption.
- Smallest fix: leash exception for ranged attackers, or provision bow/shield in the plan.

**F28 — (8,10] HP underground dead zone: can't fight, can't shelter, evade always fails** (major; DangerWatcher/EvadeTask; from: survival D8)
- Citations: DangerWatcher.java:997-999, 666, 1181 (LOW_HP only <6), 701-713, 1106-1111, 356-357, 52; EvadeTask.java:62-66, 108-111.
- Trigger: hp in (8,10] with a single same-level hostile within 8 blocks in a tunnel → falls to EvadeTask which fails `no_valid_escape_route`, 40–80-tick cooldown loop while taking hits.
- Smallest fix: widen underground shelter admission to hp ≤ retreatHp.

**F29 — Acceptance harness verifies obsidian at 32, not the mission's 64** (major; evidence harness; from: evidence)
- Citations: MiningEvidenceAudit.java:34 (`OBSIDIAN_TARGET=32`), scenario `obsidian_half_stack_32_from_zero`; mining_acceptance_contract.sh:54-66; AIBotVerifySubcommand.java:1891-1907 (fixed 240,000-tick timeout).
- Trigger: if the commitment is 64 obsidian, no existing verification attests it; timeout and thresholds are all calibrated to 32.
- Smallest fix: either re-scope the mission to 32 or add a 64 scenario with rescaled timeout/thresholds.

**F30 — Constrained-route stranding: no single-cell physical recovery; terminal triple-proof unrecoverable** (major; PathExecutor/ActionPack; from: descend B1+B2)
- Citations: PathExecutor.java:474-481, 79-100; ActionPack.java:282-300, 323-329 (non-constrained has `tryPhysicalSnap`), 330-336 (strict refuses privileged snap).
- Trigger: current cell itself invalid (gravel underfoot, fractional overlap) → `replan_failed: NO_START` / `terminal_not_standable` with no recovery — same family as the pedestal incident.
- Smallest fix: allow the one-cell physical snap primitive in constrained flows (it is physical, not privileged).

## Minor

**F31 — One STOCKPILE failure permanently deletes the Goal.Stockpile terminal step** (minor for these two missions; GoalExecutor; from: executor F5) — GE:3248-3250 vs 3245-3247 (COOK_FOOD exemption), 3735-3737, 2330-2346, 3788-3790, 1228-1229, 3137-3139, 3169. Fix: mirror the terminal-step exemption.

**F32 — Dimension-suspend is a silent deadlock that also swallows new goals** (minor here; GoalExecutor; from: executor F6) — GE:1903-1942, 2025-2039, 1482-1486, 1935/2034, 196-209, 1830-1849.

**F33 — Cross-dimension restore of pocket-less unsettled MINING_SERVICE bricks the mission** (minor; GoalExecutor; from: executor F8) — GE:2182-2202, 373-385. Fix: route through dimension-suspend instead of invalidation.

**F34 — Safety hunt (DangerWatcher) leaks into task accounting and isn't persisted** (minor; from: executor F10) — DangerWatcher.java:640; HuntTask.java:169; GE:1994-1999, 3749-3763.

**F35 — Coal-like fuel credited but never debited by smelt/cook** (minor; GoalPlanner; from: planner F3) — GP:1921-1922, 1320-1323, 1948-1953, 1336, 1936-1939; obsidian side compensated at 1717-1721, 1738-1739, 1762-1764; uncovered gap between 594-597 and 632. Underground manifestation is fail-closed `underground_fuel_reserve_depleted`.

**F36 — Obsidian reject memory wiped by any topology-epoch bump** (minor; from: obsidian D5) — COT:2545-2550; OSC:101, 260-270. Fix: radius-scoped or TTL-based invalidation. Amplifies F4.

**F37 — SCAN full-cube raycast up to ~910k cells on main thread** (minor/perf; from: obsidian D6) — COT:3112-3141. Fix: distance-layered early exit or ray caching.

**F38 — AcquireWater: rejection cap terminal at ocean edges; RETURN_SURFACE can idle to 6000-tick limit** (minor; from: obsidian D7) — AWT:1371-1394, 71, 1727-1733, 441-447, 1444-1446. Fix: sector-skip before terminalizing.

**F39 — Remaining drop-recovery geometry gaps: 1-high slot (no branch), silent double-fallback failure, permanently unsupported drops** (minor; OreDigTask/HarvestCore; from: oredig D2, D3/S3, D4) — HarvestCore:425-427, 266-268; FakePlayerMotion:270; OreDigTask:3379-3384 (zero action, zero log), 3419-3432 (horizontal≤1 cap).

**F40 — High-work-pose ledger eviction doesn't pin deep vein-queue members** (minor; OreDigTask; from: oredig H3) — OreDigTask:4128-4132, 4858, 3024-3034. Fix: pin the whole `veinQueue`.

**F41 — Checkpoint accounting nits: pickup window re-granted per restart; `bonusMined`/skip counters not persisted; `returnToSavedFace` retries to 1200 ticks; ignored seal-path result** (minor; OreDigTask; from: oredig B2, B3, S4, S5) — OreDigTask onStart:756-762; 1947-2011 (no keys); 2863-2874; 1314-1318 (bounded by 1222).

**F42 — Pick provisioning inefficiencies and durability blind spots** (minor; GoalPlanner; from: planner F5, F6, F7) — GP:610-613 vs 655-659 (tier-exact counting); 326-338, 585-591 + MiningServiceTask.java:3412-3423 vs 814-817, 1896-1908 (no durability proof for rare target picks); GP:1670 vs 1785, 1931-1932 (obsidian target pick zero durability margin).

**F43 — Hoe best-effort unresolved leak can hard-fail rare/obsidian bootstrap gates** (minor, narrow trigger; GoalPlanner; from: planner F8) — GP:1577-1579, 566-569, 1611-1615, 1575-1576.

**F44 — Pathfinding graph cannot express: flat gap-jumps, ladder/vine ascent, diagonal ±Y; water unstandable by design** (minor; NeighborEnumerator/Standability; from: descend C1-C4) — NeighborEnumerator.java:48-91, 141-153, 94-114; Standability.java:136-137, 119-125; DescendToYTask.sealLateralWater:1284-1328. Interacts with F20 (forces long detours into the budget ceiling).

**F45 — Cache staleness and latent hazards: external topology changes never invalidate; Standability cache has no TTL and feeds string-pull; cache key folds ε into maxNodes; DigDownTask 2-arg `firstSolid` lacks fluid guard** (minor; from: descend D1-D3) — MiningController.java:76; BuildAction.java:70/213; BucketAction.java:145; AStarPathfinder.java:86-93, 23-24, 132-136; Standability.java:17-30; PathExecutor.java:419; DigDownTask.java:1448-1456 vs 1439-1446 and DescendToYTask.java:1473-1481; masked today by DigDownTask.java:1379-1386, 1212-1217 and BlockMiner.java:85-93.

**F46 — Deadline gaps: DigDown return limit 600t doesn't scale with 512-point trails; Descend wet-wait at targetY has no local deadline (up to 33 min then terminal)** (minor; from: descend E1, E2) — DigDownTask.java:59, 63, 950-970, 981-983, 897-901; GE:3684; DescendToYTask.java:452-461, 830-835.

**F47 — Torch coverage limited to strip path; chase branches and old corridors stay dark → spawn source behind the bot** (minor; OreDigTask; from: survival D6) — OreDigTask.java:1674-1699; combines with DangerWatcher.java:356-357 entomb-on-hit into frequent interruptions.

**F48 — Hostile barricade only available to OreDigTask, not Descend/DigDown/MiningService/CreateObsidian** (minor; DangerWatcher; from: survival D10) — DangerWatcher.java:271-292.

**F49 — trapped_fight_back re-acquires any same-type mob within 20 blocks (open hunt, violates own contract)** (minor; DangerWatcher/CombatTask; from: survival D3) — DangerWatcher.java:817, 678-680; CombatTask.java:155-158.

**F50 — Obsidian verify timeout margin only 12.5% at the 15-TPS floor** (minor; harness; from: evidence) — AIBotVerifySubcommand.java:1900; mining_acceptance_contract.sh:15, 102-109.

# Budget model summary

## Diamond ×64 (rare channel)
- Batching: 8 batches × 8 diamonds (MiningBudget.java:103-104); `MAX_RARE_RESOURCE_RETRIES_PER_BATCH=1` → 2 resource epochs/batch → 16 epochs total (MiningBudget.java:42, 46-47).
- Per epoch: 910 stone-pick breaks (7 picks × 130, MiningBudget.java:29-30, 34); torch cap 40 (:40); food quota 4 (:48).
- Tick windows: OreDig hard window 24,000 ticks/epoch, 48,000 with retry (MiningMissionBudget.java:15, 53-59); descend Y64→-58 window 12,160 ticks, cap 40,000 (MiningMissionBudget.java:39-43).
- Mission timeout floor `diamondStack64FromZero` = 384,000 (oreDig) + 115,200 (service 24×4,800) + 80,000 (2 descents) + 24,000 (margin) = 603,200 ticks (MiningMissionBudget.java:70-84); realized estimate with bootstrap/auxiliary windows ≈0.7–1.0M ticks ≈47,000–67,000 s at 15 TPS vs 172,800 s verify cap → ≥2.5× headroom (not the bottleneck).
- Provisioning: 72 cooked food units (8×2×4+8, MiningBudget.java:50-53/154-157), 640 bootstrap torches (:56-58), 228 sticks (:59-65), 3 iron picks + 5 stone tunneling picks + 6 spare ingots + 60 stone (MiningBudget.java:101-165).
- Replans: lifetime max(12, 3×8)=24 (GE:3294-3300); consecutive cap 3 (GE:71); postcondition repairs 3 lifetime (GE:72).
- Task-internal deadlines: drop recovery 200 ticks (OreDigTask:115); approach lease 80 (OreDigTask:3080-3086); no-progress 200; restore-face 1,200.
- Physical throughput: ≈23 ticks/deepslate break → 910 breaks ≈20,930 ticks = 87% of the epoch window (tick window binds before the pick pool); effective 400–700 breaks/epoch → 200–350 m/epoch → expected 3–9 diamonds/epoch → 6–18 per 2-epoch batch vs quota 8. Per-batch shortfall ~10–30%; compounded mission success ~17–66% (F2). Torch cap 40 vs 25–44 needed at ~8 m spacing in good epochs — secondary binding constraint.

## Obsidian ×64 (harness currently verifies ×32)
- Task hard budget: `maxElapsedForTarget(64)` = max(24,000, 64×2,400) = 153,600 ticks, persisted across restart/replan/service (COT:54-55, 210-213, 2120, 2306, 2403).
- Per block: worst-case phase-limit sum ≈2,650 ticks (APPROACH 260×3 + FORMATION 120 + SPREAD 20×2 + RECOVERY 100×2 + DRAIN 60×2 + mine ≈188 + PICKUP 600 + RETURN 600, COT:58-69) > 2,400 amortized; normal path ≈500–1,000 ticks/block. Search overhead ≈35–40 ticks/face, SCAN every 4 faces (COT:67-68).
- Service boundaries: every 8 blocks (COT:53, 2027-2041) → 8 OBSIDIAN_8 services for 64, each on MiningServiceTask's own budget (doesn't erode obsidian budget).
- AcquireWater: 30,000 ticks / 224 waypoints per expedition (AWT:49, 66).
- Provisioning: food fixed at 8 cooked units (GP:66) with underground floor 2 (MiningFoodReserve.java:18); 1 replacement diamond pick (3 diamonds via nested ensureMineOre) + 1 acquisition iron pick (GP:1668-1701); 4 stone picks, ~124 cobble, ~86 sticks, ~39 torches + ~31+8 descent torches for 3 descents (GP:1708-1791); 14 sealed logs.
- Retries/replans: same GoalExecutor budgets as diamond (24 lifetime at 8 batches, 3 consecutive, 3 postcondition).
- Harness: scenario is 32 blocks, fixed 240,000-tick timeout (AIBotVerifySubcommand.java:1891-1907) = 16,000 s at 15 TPS vs 18,000 s cap → 12.5% margin (F29, F50). No 64-block verification exists.

# Open questions requiring a live run to answer

1. **Actual diamond yield per epoch** at Y≈-59 with the real search pattern (breaks/epoch, seed encounter rate, vein flood-fill sizes) — validates the 3–9/epoch estimate and the 10–30% per-batch shortfall band behind F2.
2. **Realized end-to-end tick consumption** of the nominal diamond plan (estimate 0.7–1.0M ticks) and whether the 15-TPS floor holds under runtime return-proof load and SCAN raycasting (F20, F37) — the timeout headroom claim depends on both.
3. **Does ServerStatHandler persist fake-player stats across server restart?** Decides whether the hunt pickup receipt's statistics leg survives restarts (F26).
4. **Empirical frequency of descend landing-drift knockback** per 122-window descent under mob pressure (F3), and of fall≥2 DROP_DOWN nodes appearing in anchored hunt routes on natural terrain (F13).
5. **Is there any GoalExecutor per-tick hard deadline for RUNNING tasks** that would eventually rescue the F11 freeze? The oredig report's grep was inconclusive ("未穷尽验证").
6. **Torch consumption per epoch** in high-advance epochs vs the 40/epoch cap — does `ore_dig_torch_epoch_exhausted` fire in practice on good terrain?
7. **How often is only flowing (non-still) lava observable** at natural lava-lake geometry — determines F4's pre-fix trigger rate and the SEARCH-tunnel overhead assumption (>8–10 branches/block ⇒ timeout).
8. **Obsidian drop loss rate** (burned on exposed lava / pushed outside the ±8 box) during the degraded PROTECT_PICKUP path (F14).
9. **Per-block obsidian tick cost distribution** vs the 2,400 amortized budget — how often does a pathological block exceed 2,650 ticks, and does averaging actually absorb it over 32/64 blocks?
10. **If the 64-obsidian commitment stands**, a live run is needed to size a new scenario timeout and thresholds — the current 240,000-tick/32-block calibration provides no evidence for 64 (F29).
