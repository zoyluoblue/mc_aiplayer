package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * DIGDOWN:站着挖竖井,采集 N 个目标方块(如圆石)。专为 GoalExecutor 的 MINE 步设计。
 *
 * 它不定位特定方块；首次在当前井口正常开挖，只有已观察到的失败入口才会经生存寻路换列:
 *  - 脚下若已是目标块(石头)→ 挖它,计入产出;
 *  - 脚下是泥/草/沙等非目标块 → 也挖掉穿过去(地表 bot 必须先穿过表层土才到石层),
 *    这正是实测#9 "站在草地上、相邻没石头就秒 no_reachable" 的对症修复;
 *  - 挖一格、bot 自然下落一格、再挖下一格,如此往下,直到采够。
 *
 * 安全:开挖每格前查正下方两格是否岩浆/深渊;碰到流体或挖到基岩层失败,交 GoalExecutor 处理。
 * 挖掘走共享原语 {@link BlockMiner}(只在空闲时发起、绝不中途重发清零进度、正确 face)。
 *
 * 自包含状态机(铁律 G1),不在内部 assign;全程主线程(G2)。
 */
public final class DigDownTask extends AbstractTask implements CheckpointableTask {
    private static final int CHECKPOINT_SCHEMA = 4;
    private static final int RETURN_OUTCOME_CHECKPOINT_SCHEMA = 3;
    private static final int WATER_SEAL_CHECKPOINT_SCHEMA = 2;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 1;
    private static final int MAX_ELAPSED_BASE = 2400;   // 小配额保留 2 分钟硬超时
    private static final int MAX_ELAPSED_CAP = 24000;   // 大配额仍须有界(20 分钟)
    private static final int MAX_ELAPSED_PER_BLOCK = 80;
    private static final int NO_PROGRESS_LIMIT = 200;   // 10s 无进展(没破任何块)即失败
    private static final int PICKUP_GRACE_TICKS = 30;   // 采够后多等一会儿确保掉落物落袋
    private static final int MIN_Y = -60;               // 别挖穿到基岩以下
    private static final int RETURN_LIMIT = 600;        // 普通回程硬上限(30s)
    private static final int RETURN_SAFETY_LIMIT = 2400; // 安全任务位移后的有界回连上限(2min)
    private static final int RETURN_STALL_LIMIT = 600;  // 到事实 waypoint 30s 无任何接近即 fail-closed
    private static final int MAX_DESCENT = 24;          // 下挖深度上限(格):超了还没采够多半是掉落物没捡到,扫拾再判,绝不无限挖到深处被怪围杀
    private static final int MAX_TRAIL_POINTS = 512;
    private static final int RETURN_PATH_RETRY = 20;
    private static final int ENTRY_RELOCATION_LIMIT = 600;
    private static final int[] ENTRY_RELOCATION_DISTANCES = {4, 8, 14};
    private static final int[][] ENTRY_RELOCATION_DIRECTIONS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, -1}, {1, -1}, {-1, 1}
    };
    // A factual staircase can lose supports to falling terrain and require vanilla pillar repairs
    // on the way out. Stone bootstrap steps therefore reserve at most one support per descended
    // layer; completion is still decided from the net inventory delta after exact return.

    enum Phase {
        DESCEND,
        RETURN,
        DONE
    }

    private enum TrailUpdate {
        UNCHANGED,
        APPENDED,
        DISCONNECTED,
        LIMIT
    }

    /**
     * A failed descent is not allowed to strand the bot below its exact entry point.  Persist the
     * terminal outcome while the same factual trail is being unwound; only after that debt is
     * settled does the task expose FAILED to GoalExecutor and permit a remaining-quota replan.
     */
    enum ReturnOutcome {
        COMPLETE,
        TIMEOUT,
        NEED_BETTER_TOOL,
        NO_PROGRESS,
        TRAIL_LIMIT,
        WALLED,
        SAFETY_INTERRUPTED,
        NET_DELIVERY_SHORTFALL
    }

    private final Block targetBlock;
    private final Set<Item> targetDrops;
    private final int targetCount;
    private final int maxWorkBudget;
    private final DigDownCheckpoint restored;
    private final boolean invalidCheckpoint;
    private final BlockMiner miner = new BlockMiner();
    private static final Direction[] HDIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private int hdirIndex;    // 撞基岩后水平掘进的当前方向
    private int stairDirIndex; // 台阶斜下的当前水平方向(HDIRS 下标)
    private boolean horizontalMode; // 下挖到上限/撞基岩后永久转横挖,经主体统一原语+看门狗(治 MAX_DESCENT 每tick自废)
    private BlockPos rejectedLandingOrigin;
    private int rejectedLandingDirections;

    private int invBaseline;
    private int collected;
    private int pickupGrace;
    private BlockPos startPos;       // 开挖前的井口位置(地表);采够后爬回这里再完成,免得困在井底出不来
    private int targetY;             // 已真实到达的最低 Y；checkpoint 用它校验轨迹没有被截断/伪造
    private Phase phase = Phase.DESCEND;
    private int returnStartTick;
    private int workBudgetOffset;
    private int workBudgetAtReturn;
    private int returnBudgetOffset;
    private int lastProgressBudget;
    private final List<BlockPos> descentTrail = new ArrayList<>();
    private int returnTrailIndex;
    private int lastReturnPathAttemptBudget;
    private boolean returnPathFallback;
    private int lastReturnProgressBudget;
    private int returnProgressWaypointIndex;
    private long returnBestDistanceSquared;
    private boolean returnSafetyRecovery;
    private ReturnOutcome returnOutcome = ReturnOutcome.COMPLETE;
    private final List<BlockPos> entryRelocationCandidates = new ArrayList<>();
    private int entryRelocationIndex;
    private int entryRelocationAttempts;
    private int entryRelocationStartElapsed;
    private BlockPos entryRelocationTarget;
    private String entryRelocationLastFailure = "none";

    public DigDownTask(Block targetBlock, int targetCount) {
        this(targetBlock, targetCount, Map.of());
    }

    public DigDownTask(Block targetBlock, int targetCount, Map<String, String> checkpoint) {
        this.targetBlock = targetBlock;
        this.targetCount = Math.max(1, targetCount);
        this.maxWorkBudget = maxWorkBudgetForTarget(
                Registries.BLOCK.getId(targetBlock).toString(), this.targetCount);
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        this.restored = DigDownCheckpoint.decode(values).orElse(null);
        this.invalidCheckpoint = !values.isEmpty()
                && (restored == null
                || !restored.targetBlockId().equals(Registries.BLOCK.getId(targetBlock).toString())
                || restored.targetCount() != this.targetCount);
        Set<Item> drops = new HashSet<>(HarvestCore.expectedDropsFor(Set.of(targetBlock)));
        if (targetBlock == Blocks.STONE) {
            // 深层(Y<0)全是深板岩(挖了掉 cobbled_deepslate)、远古遗迹是黑石——都算"石料",
            // 否则深层永远凑不够 cobblestone、做不了熔炉(实测 Y=-59 死循环根因)。
            drops.add(Items.COBBLED_DEEPSLATE);
            drops.add(Items.BLACKSTONE);
        }
        this.targetDrops = drops;
    }

    @Override
    public String name() {
        return "dig_down";
    }

    @Override
    public String describe() {
        return "DigDown " + net.minecraft.registry.Registries.BLOCK.getId(targetBlock).getPath()
                + " " + collected + "/" + targetCount;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // 下挖期 bot 站着挖,位置基本不变;视为 waiting 让 StuckWatcher 不误判,
        // 由本任务自己的 NO_PROGRESS_LIMIT 看门狗负责卡死保护。
        return true;
    }

    /**
     * A nearby observed lava cell closes this descent entry; the safe response is to repay the
     * task's factual staircase debt, not to hand a sealed underground pose to generic Evade.
     * Contact/fire/low-health/water states deliberately decline ownership so the global emergency
     * layer keeps priority. Repeated scans while RETURN is active are idempotent and never reset its
     * cursor or aggregate budget.
     */
    boolean claimObservedLavaReturn(AIPlayerEntity bot, BlockPos lavaPos) {
        if (lavaPos == null
                || startPos == null
                || descentTrail.isEmpty()
                || phase == Phase.DONE
                || state != TaskState.RUNNING && state != TaskState.PAUSED
                || bot.isInLava()
                || bot.isOnFire()
                || bot.isSubmergedInWater()
                || bot.hurtTime > 0
                || bot.getHealth() <= 8.0F
                || !bot.getServerWorld().getFluidState(lavaPos).isIn(FluidTags.LAVA)
                || !ObservableWorldQuery.canObserveBlock(bot, lavaPos)) {
            return false;
        }
        BlockPos current = bot.getBlockPos();
        if (!bot.getServerWorld().getFluidState(current).isEmpty()
                || !bot.getServerWorld().getFluidState(current.up()).isEmpty()) {
            return false;
        }

        boolean transitionedToLavaReturn = false;
        if (phase == Phase.DESCEND) {
            TrailUpdate update = rememberDescentStep(current);
            beginReturn(bot, update == TrailUpdate.LIMIT
                    ? ReturnOutcome.TRAIL_LIMIT : ReturnOutcome.WALLED);
            transitionedToLavaReturn = returnOutcome == ReturnOutcome.WALLED;
        } else if (returnOutcome == ReturnOutcome.COMPLETE
                || returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED) {
            // onPause() already published the exact cursor and safety hard-clock lease. Upgrade only
            // the terminal cause; beginReturn() here would forge a fresh cursor/budget window.
            returnOutcome = ReturnOutcome.WALLED;
            transitionedToLavaReturn = true;
        }

        // WALLED is the existing durable "this entry is factually closed" outcome. Record the same
        // episode exclusion even when a stronger pre-existing return failure keeps its own reason.
        EpisodeMemory.INSTANCE.exclude(bot.getUuid(), startPos,
                bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        if (transitionedToLavaReturn) {
            BotLog.danger(bot, "dig_down_observed_lava_return",
                    "at", current.toShortString(),
                    "lava", lavaPos.toShortString(),
                    "entry", startPos.toShortString(),
                    "state", state,
                    "return_index", returnTrailIndex,
                    "return_budget", returnBudget());
        }
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("dig_down_invalid_checkpoint");
            return;
        }
        if (restored != null) {
            // A DESCEND cursor is only factual at the last recorded stair cell. Replanning may
            // insert a surface gather before this MINE step; that task legitimately moves the bot
            // and makes the old local staircase unusable. Restart fresh at the current supported
            // pose instead of restoring an already-exhausted no-progress clock against stale
            // coordinates. RETURN debt is never discarded here.
            BlockPos restoredPose = restored.trail().isEmpty()
                    ? null : restored.trail().getLast();
            if (restored.resumePhase() != Phase.DESCEND
                    || restoredPose == null
                    || bot.getBlockPos().equals(restoredPose)) {
                restoreCheckpoint(bot);
                return;
            }
            BotLog.task(bot, "dig_down_descend_checkpoint_discarded",
                    "saved", restoredPose.toShortString(),
                    "current", bot.getBlockPos().toShortString(),
                    "reason", "pose_moved_before_resume");
        }
        // strict_survival 不允许预读地下流体。首次入口直接开挖；只有同一 goal 内已经以
        // WALLED 失败并写入 EpisodeMemory 的入口，才在 replan 时触发物理换列。迁移完成前
        // 不建立库存基线、轨迹或 checkpoint，重启可安全重选。
        int now = bot.getServer().getTicks();
        if (EpisodeMemory.INSTANCE.isExcluded(bot.getUuid(), bot.getBlockPos(), now)) {
            prepareEntryRelocation(bot);
            return;
        }
        initializeFreshDescent(bot);
    }

    private void prepareEntryRelocation(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        startPos = null;
        descentTrail.clear();
        entryRelocationCandidates.clear();
        entryRelocationIndex = 0;
        entryRelocationAttempts = 0;
        entryRelocationStartElapsed = elapsed;
        entryRelocationTarget = null;
        entryRelocationLastFailure = "none";

        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        int now = bot.getServer().getTicks();
        for (int dist : ENTRY_RELOCATION_DISTANCES) {
            for (int[] direction : ENTRY_RELOCATION_DIRECTIONS) {
                int x = feet.getX() + direction[0] * dist;
                int z = feet.getZ() + direction[1] * dist;
                // Flat and gently rolling terrain is the common case. Try the factual current
                // surface level first; a distant heightmap can resolve to a roof/tree/overhang
                // that is technically the top block but has no reversible route from this entry.
                addEntryRelocationCandidate(bot, world, new BlockPos(x, feet.getY(), z), now);
                int topY = world.getTopY(
                        net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (topY != feet.getY()) {
                    addEntryRelocationCandidate(bot, world, new BlockPos(x, topY, z), now);
                }
            }
        }
        startNextEntryRelocation(bot);
    }

    private void addEntryRelocationCandidate(AIPlayerEntity bot,
                                             ServerWorld world,
                                             BlockPos candidate,
                                             int now) {
        BlockPos immutable = candidate.toImmutable();
        if (Standability.isStandable(world, immutable)
                && !EpisodeMemory.INSTANCE.isExcluded(bot.getUuid(), immutable, now)
                && !entryRelocationCandidates.contains(immutable)) {
            entryRelocationCandidates.add(immutable);
        }
    }

    private boolean startNextEntryRelocation(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        while (entryRelocationIndex < entryRelocationCandidates.size()) {
            BlockPos candidate = entryRelocationCandidates.get(entryRelocationIndex++);
            entryRelocationAttempts++;
            if (!Standability.isStandable(world, candidate)
                    || EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUuid(), candidate, bot.getServer().getTicks())) {
                entryRelocationLastFailure = "candidate_became_unavailable";
                continue;
            }
            ActionResult result = bot.getActionPack().startSurfacePathTo(candidate);
            if (result.isFailed()) {
                entryRelocationLastFailure = result.reason();
                continue;
            }
            BlockPos resolved = bot.getActionPack().activePathGoal();
            if (resolved == null || !resolved.equals(candidate)) {
                bot.getActionPack().stopAll();
                entryRelocationLastFailure = "endpoint_not_exact";
                continue;
            }
            entryRelocationTarget = candidate;
            BotLog.action(bot, "dig_down_entry_relocation_path",
                    "to", candidate.toShortString(),
                    "attempt", entryRelocationAttempts);
            return true;
        }
        bot.getActionPack().stopAll();
        fail("dig_down_no_reachable_entry_column:attempted=" + entryRelocationAttempts
                + ":last=" + entryRelocationLastFailure);
        return false;
    }

    private void tickEntryRelocation(AIPlayerEntity bot) {
        if (elapsed - entryRelocationStartElapsed > ENTRY_RELOCATION_LIMIT) {
            bot.getActionPack().stopAll();
            fail("dig_down_no_reachable_entry_column:attempted=" + entryRelocationAttempts
                    + ":last=relocation_timeout");
            return;
        }
        BlockPos current = bot.getBlockPos();
        if (entryRelocationTarget != null && current.equals(entryRelocationTarget)) {
            if (Standability.isStandable(bot.getServerWorld(), current)
                    && !EpisodeMemory.INSTANCE.isExcluded(
                    bot.getUuid(), current, bot.getServer().getTicks())) {
                bot.getActionPack().stopAll();
                BotLog.action(bot, "dig_down_entry_relocation_complete",
                        "to", current.toShortString(),
                        "attempt", entryRelocationAttempts);
                initializeFreshDescent(bot);
                return;
            }
            bot.getActionPack().stopAll();
            entryRelocationLastFailure = "arrival_became_unavailable";
            entryRelocationTarget = null;
        } else if (entryRelocationTarget != null && bot.getActionPack().isPathExecutorIdle()) {
            entryRelocationLastFailure = "path_ended_before_target";
            entryRelocationTarget = null;
        }
        if (entryRelocationTarget == null) {
            startNextEntryRelocation(bot);
        }
    }

    private void initializeFreshDescent(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        lastProgressBudget = 0;
        pickupGrace = 0;
        startPos = bot.getBlockPos();   // 记井口,采够后回这里
        targetY = startPos.getY();
        phase = Phase.DESCEND;
        returnStartTick = 0;
        workBudgetOffset = -elapsed;
        workBudgetAtReturn = 0;
        returnBudgetOffset = 0;
        hdirIndex = 0;
        stairDirIndex = 0;
        horizontalMode = false;
        descentTrail.clear();
        descentTrail.add(startPos.toImmutable());
        returnTrailIndex = -1;
        lastReturnPathAttemptBudget = -RETURN_PATH_RETRY;
        returnPathFallback = false;
        lastReturnProgressBudget = 0;
        returnProgressWaypointIndex = -1;
        returnBestDistanceSquared = -1L;
        returnSafetyRecovery = false;
        returnOutcome = ReturnOutcome.COMPLETE;
        clearRejectedLandingDirections();
    }

    private void restoreCheckpoint(AIPlayerEntity bot) {
        invBaseline = restored.inventoryBaseline();
        collected = restored.collected();
        pickupGrace = restored.pickupGrace();
        startPos = restored.startPos();
        targetY = restored.targetY();
        phase = restored.resumePhase();
        boolean promotedToReturn = restored.phase() == Phase.DESCEND && phase == Phase.RETURN;
        hdirIndex = restored.horizontalDirection();
        stairDirIndex = restored.stairDirection();
        horizontalMode = restored.horizontalMode();
        workBudgetOffset = restored.workBudgetUsed();
        workBudgetAtReturn = restored.workBudgetUsed();
        returnBudgetOffset = promotedToReturn ? 0 : restored.returnBudgetUsed();
        returnStartTick = elapsed;
        lastProgressBudget = restored.lastProgressBudget();
        descentTrail.clear();
        descentTrail.addAll(restored.trail());
        returnTrailIndex = promotedToReturn
                ? Math.max(-1, descentTrail.size() - 2) : restored.returnTrailIndex();
        lastReturnPathAttemptBudget = promotedToReturn
                ? -RETURN_PATH_RETRY : restored.lastReturnPathAttemptBudget();
        returnPathFallback = !promotedToReturn && restored.returnPathFallback();
        lastReturnProgressBudget = promotedToReturn
                ? 0 : restored.lastReturnProgressBudget();
        returnProgressWaypointIndex = promotedToReturn
                ? returnTrailIndex : restored.returnProgressWaypointIndex();
        returnBestDistanceSquared = promotedToReturn
                ? -1L : restored.returnBestDistanceSquared();
        returnSafetyRecovery = !promotedToReturn && restored.returnSafetyRecovery();
        returnOutcome = restored.returnOutcome();
        if (phase == Phase.RETURN) {
            // A process snapshot may capture this task while it is paused, before the safety task
            // displaces the player and before onResume() can establish a new distance baseline.
            // Rebase only the physical distance geometry at the restored pose. The persisted hard
            // budget and last-progress budget remain untouched, so restarts cannot extend either
            // the 2400-tick safety cap or the current no-progress lease.
            returnProgressWaypointIndex = returnTrailIndex;
            BlockPos waypoint = pendingReturnWaypoint();
            returnBestDistanceSquared = waypoint == null
                    ? -1L : squaredDistance(bot.getBlockPos(), waypoint);
        }
        rememberUnusableEntry(bot, returnOutcome);
        if (promotedToReturn) {
            clearRejectedLandingDirections();
        } else {
            rejectedLandingOrigin = restored.rejectedLandingOrigin();
            rejectedLandingDirections = restored.rejectedLandingDirections();
        }
        bot.getActionPack().stopAll();
        BotLog.task(bot, "dig_down_restored",
                "phase", phase,
                "start", startPos.toShortString(),
                "target_y", targetY,
                "trail", descentTrail.size(),
                "return_index", returnTrailIndex,
                "return_budget", returnBudgetOffset);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        if (startPos == null) {
            return;
        }
        if (phase == Phase.DESCEND) {
            TrailUpdate update = rememberDescentStep(bot.getBlockPos());
            // A safety task is allowed to move the bot after pause().  Publish durable RETURN
            // debt now, while the last factual work cell is still known, so a paused snapshot or
            // a process restart can never reinterpret the displaced underground pose as a fresh
            // mine entrance.  The batch may be replanned only after the exact surface debt is paid.
            beginReturn(bot, update == TrailUpdate.LIMIT
                    ? ReturnOutcome.TRAIL_LIMIT : ReturnOutcome.SAFETY_INTERRUPTED);
            return;
        }
        if (phase == Phase.RETURN) {
            returnSafetyRecovery = true;
            // returnToSurface decrements the cursor immediately after a factual micro-step. If a
            // combat/resupply task now displaces the bot, first rejoin this exact recorded cell
            // instead of skipping it and opening a long diagonal gap to the preceding waypoint.
            BlockPos current = bot.getBlockPos();
            for (int i = descentTrail.size() - 1; i >= 0; i--) {
                if (descentTrail.get(i).equals(current)) {
                    returnTrailIndex = Math.max(returnTrailIndex, i);
                    returnPathFallback = false;
                    lastReturnPathAttemptBudget = returnBudget() - RETURN_PATH_RETRY;
                    break;
                }
            }
            // Persist the recovery lease at pause admission, before the safety task can move the
            // player. A process snapshot may happen while this task is still paused and therefore
            // never call onResume(); the aggregate hard clock is unchanged either way.
            resetReturnProgressLease(bot.getBlockPos());
        }
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        if (phase != Phase.RETURN) {
            return;
        }
        // A safety/background task may have moved the bot while this task was paused. Give that
        // new physical recovery pose one fresh stall lease, but never reset returnBudget(): the
        // 2400-tick safety hard cap remains monotonic across arbitrarily many interruptions.
        returnSafetyRecovery = true;
        returnPathFallback = false;
        lastReturnPathAttemptBudget = returnBudget() - RETURN_PATH_RETRY;
        resetReturnProgressLease(bot.getBlockPos());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (startPos == null) {
            tickEntryRelocation(bot);
            return;
        }
        if (phase == Phase.RETURN) {
            returnToSurface(bot);
            return;
        }
        if (phase == Phase.DONE) {
            complete();
            return;
        }
        TrailUpdate trailUpdate = rememberDescentStep(bot.getBlockPos());
        if (trailUpdate == TrailUpdate.LIMIT) {
            failAfterExactReturn(bot, ReturnOutcome.TRAIL_LIMIT);
            return;
        }
        if (trailUpdate == TrailUpdate.DISCONNECTED) {
            failAfterExactReturn(bot, ReturnOutcome.SAFETY_INTERRUPTED);
            return;
        }
        ServerWorld world = bot.getServerWorld();
        // pickupGrace>0 in DESCEND is a durable, already-observed horizontal-frontier settlement
        // debt. rotateStair() can fall back to digHorizontal() before horizontalMode is latched, so
        // the armed counter itself is the authority. It is not fresh mining work; service its
        // bounded physical pickup window before any watchdog or hard-budget failure. The counter is
        // checkpointed and therefore cannot be reset by restart.
        if (hasHorizontalFrontierSettleDebt()) {
            settleHorizontalFrontier(bot);
            return;
        }
        if (workBudget() >= maxWorkBudget) {
            // A final horizontal break can land exactly on the hard boundary. Observe the now-closed
            // geometry before timing out; otherwise the drop's vanilla pickup delay is preempted by
            // up to PICKUP_GRACE_TICKS and the same entry can be retried without settling its debt.
            if (horizontalMode && isObservedClosedHorizontalFrontier(world, bot.getBlockPos())) {
                armHorizontalFrontierSettle(bot);
                settleHorizontalFrontier(bot);
            } else {
                failAfterExactReturn(bot, ReturnOutcome.TIMEOUT);
            }
            return;
        }
        // 下挖深度兜底:超过 MAX_DESCENT 还没采够,多半是掉落物没及时捡(collected 不增)→ 大范围扫拾一次再判;
        // 仍不够则【一次性】永久转水平掘进(置 horizontalMode 标志),绝不继续无限往深里挖(实测无限下挖到 y6 被蜘蛛围杀)。
        // 关键:此处绝不每 tick cancel+begin+return——旧逻辑那样会绕过下面的 miner.tick(永不推进)和
        // L207 无进展看门狗(白等满 MAX_DESCENT 硬超时),每 tick 自废零破块(seed20260610 dig_down_timeout 根因)。
        // 改为只置标志一次,之后正常流经主体统一原语:miner.tick 逐块推进 + 看门狗真卡时 200t 干净失败交回 replan。
        if (!horizontalMode && startPos != null && startPos.getY() - bot.getBlockPos().getY() >= MAX_DESCENT) {
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 12.0D, 64);
            int got = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
            if (got >= grossCollectionTarget()) {
                collected = got;
                miner.cancel(bot);
                beginReturn(bot);
                return;
            }
            horizontalMode = true;        // 永久转横挖,后续走 L236 的 digHorizontal 决策(经 miner.tick + 看门狗)
            noteWorkProgress();            // 给横挖一个干净的看门狗起算窗口
            BotLog.action(bot, "dig_down_go_horizontal", "from", bot.getBlockPos().toShortString(),
                    "collected", collected + "/" + targetCount);
        }

        // 海平面/含水层下挖防淹:封堵脚位+头位四周的侧向水(见方法注释)。封了本 tick 收手,下 tick 续挖。
        if (sealLateralWater(bot, world)) {
            return;
        }
        // 工具闸:挖不动目标(无合格镐)直接失败,交 GoalExecutor 倒推补镐。
        if (!ToolTier.canHarvestWithInventory(bot, targetBlock.getDefaultState())) {
            failAfterExactReturn(bot, ReturnOutcome.NEED_BETTER_TOOL);
            return;
        }

        // 收集计数:绝对增量(固定基线),刚破的块的掉落物随后落袋会被算进来。
        // 垂直半径放大:下挖时刚破的 cobblestone 落在上层台阶,2.5 格够不到 → collected 永不增 → 无限下挖到深处被怪围杀。
        refreshCollected(bot);
        if (collected >= grossCollectionTarget()) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline
                    >= grossCollectionTarget()) {
                // 采够 → 进入回程,爬回井口再完成(否则困在井底,下一个任务如打猎追地表猎物会出不来→活锁)。
                beginReturn(bot);
            }
            return;
        }

        // 无进展看门狗:NO_PROGRESS_LIMIT 内没破任何块 → 干净失败,不空转。
        if (workBudget() - lastProgressBudget > NO_PROGRESS_LIMIT) {
            miner.cancel(bot);
            // 取证 dump:山地/自然地形 no_progress(实测 424t 零破块)光靠失败原因无法定位——
            // 把脚位、阶梯方向、下一级三格(头/脚/踏面)的方块、能否破障打出来,供日志诊断几何卡点。
            BlockPos feetNow = bot.getBlockPos();
            Direction dirNow = HDIRS[stairDirIndex];
            BlockPos aheadNow = feetNow.offset(dirNow);
            BlockPos nextNow = aheadNow.down();
            ServerWorld worldNow = bot.getServerWorld();
            BotLog.action(bot, "dig_down_stall_dump",
                    "feet", feetNow.toShortString(),
                    "dir", dirNow.asString(),
                    "ahead", worldNow.getBlockState(aheadNow).getBlock().toString(),
                    "next", worldNow.getBlockState(nextNow).getBlock().toString(),
                    "below_next", worldNow.getBlockState(nextNow.down()).getBlock().toString(),
                    "under_feet", worldNow.getBlockState(feetNow.down()).getBlock().toString(),
                    "can_harvest", ToolTier.canHarvestWithInventory(bot, targetBlock.getDefaultState()));
            failAfterExactReturn(bot, ReturnOutcome.NO_PROGRESS);
            return;
        }

        // 推进当前挖掘。
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return; // 正在挖,等它破/超时
        }
        if (status == BlockMiner.Status.DONE) {
            noteWorkProgress(); // 破了一格 = 进展(无论是石头还是表层土)
        }
        // DONE / FAILED / IDLE → 决定下一格(脚下柱)。

        BlockPos feet = bot.getBlockPos();
        if (horizontalMode || feet.down().getY() <= MIN_Y) {
            // horizontalMode:下挖到 MAX_DESCENT 上限后永久横挖(见上方一次性置标志处)。
            // 或到基岩上方、向下已无空间 → 转水平掘进继续挖石料(实测 Y=-59 时第一步 below 就 <= MIN_Y,
            // 旧逻辑直接 fail collected=0 → MINE stone 失败 → goal replan 死循环)。
            // 两路皆复用统一 digHorizontal:其 begin 有防重入守卫,经上面 miner.tick 推进 + L207 看门狗止损。
            digHorizontal(bot, world, feet);
            return;
        }

        // 台阶式斜向下挖(拟人 + 安全):绝不直挖脚下——下方可能是水/岩浆,一镐捅穿就溺水/葬身岩浆。
        // 改挖"下一级台阶"(斜前下方:ahead 头位 + next 脚位),深层这两格通常都是石料,顺带计入 collected;
        // 下一级或其踏面是水/岩浆就换斜下方向绕,像挖楼梯一样一级一级斜下(与 DescendToYTask 台阶逻辑一致)。
        ensureRejectedLandingOrigin(feet);
        if ((rejectedLandingDirections & 1 << stairDirIndex) != 0) {
            if (rotateStair(world, feet)) {
                return;
            }
            digHorizontal(bot, world, feet);
            return;
        }
        Direction dir = HDIRS[stairDirIndex];
        BlockPos ahead = feet.offset(dir);   // 下一级头位 (x+d, y)
        BlockPos next = ahead.down();         // 下一级站位 (x+d, y-1)
        if (!isViableStairDirection(world, feet, dir)) {
            if (rotateStair(world, feet)) {
                return; // 换到既不挨流体、又能形成真实落脚面的斜下方向
            }
            // 四个斜下方向都被水/岩浆挡 → 转水平掘进(此层还能继续凑石料),实在不行那里再判失败。
            digHorizontal(bot, world, feet);
            return;
        }
        // 清出下一级身位:ahead(前方身位,眼睛永远看得见,先挖) → ahead.up()(前上头顶净空) → next(前下踏面)。
        // ① 顺序:斜坡/上坡里 ahead 是挡在正前方的实心墙遮住通往 next 的视线,先挖被遮的 next 会射线撞墙
        //    判够不到→FAILED→零破块卡死(seed20260610 主因);ahead 先挖清遮挡。
        // ② 补挖 ahead.up():只清 next+ahead 每列仅 2 格(Y-1,Y),玩家从上一级下来时头撞前方 Y+1 实心顶,
        //    下台阶只剩 1 格可走高、正常玩家过不去。补头顶净空→下潜巷道 2 格可通行。firstSolid3 跳流体防溃浆。
        BlockPos solid = firstSolid(world, ahead, ahead.up(), next);
        if (solid != null) {
            miner.begin(bot, solid);
            miner.tick(bot); // 立即发起本格挖掘,不浪费一 tick
            return;
        }
        // 身位已通 → 斜下踏到下一级台阶。bot 无被动重力,仍需主动移一格;斜向 1 格微位移=踏下一级楼梯,
        // 不是 roam 那种跨图大范围闪现(下沉后刚破块的掉落物正好落入拾取半径,一并修掉 collected=0)。
        if (bot.getActionPack().descendInto(next)) {
            TrailUpdate landingUpdate = rememberDescentStep(bot.getBlockPos());
            if (landingUpdate == TrailUpdate.LIMIT) {
                failAfterExactReturn(bot, ReturnOutcome.TRAIL_LIMIT);
                return;
            }
            if (landingUpdate == TrailUpdate.DISCONNECTED) {
                failAfterExactReturn(bot, ReturnOutcome.SAFETY_INTERRUPTED);
                return;
            }
            clearRejectedLandingDirections();
            return;
        }
        // Terrain may change between viability check and movement (falling blocks/entities), and
        // ActionPack is the final collision authority. Never retry the same unsupported cell for
        // 200 ticks: rotate to a different safe staircase, then fall back to supported horizontal
        // mining if all four directions are gone.
        rejectLandingDirection(feet, stairDirIndex);
        if (!rotateStair(world, feet)) {
            digHorizontal(bot, world, feet);
        }
    }

    private void beginReturn(AIPlayerEntity bot) {
        beginReturn(bot, ReturnOutcome.COMPLETE);
    }

    private void failAfterExactReturn(AIPlayerEntity bot, ReturnOutcome outcome) {
        rememberUnusableEntry(bot, outcome);
        if (startPos == null) {
            fail(returnFailureReason(bot, outcome));
            return;
        }
        if (hasReturned(startPos, bot.getBlockPos())) {
            settleAfterExactReturn(bot, outcome);
            return;
        }
        beginReturn(bot, outcome);
    }

    private void rememberUnusableEntry(AIPlayerEntity bot, ReturnOutcome outcome) {
        if (!shouldExcludeEntry(outcome, horizontalMode) || startPos == null) {
            return;
        }
        EpisodeMemory.INSTANCE.exclude(bot.getUuid(), startPos,
                bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        BotLog.action(bot, "dig_down_entry_excluded",
                "entry", startPos.toShortString(),
                "reason", switch (outcome) {
                    case WALLED -> "observed_walled";
                    case SAFETY_INTERRUPTED -> "safety_interrupted";
                    case TIMEOUT -> "horizontal_work_timeout";
                    default -> "horizontal_no_progress";
                });
    }

    static boolean shouldExcludeEntry(ReturnOutcome outcome, boolean horizontalMode) {
        return outcome == ReturnOutcome.WALLED
                || outcome == ReturnOutcome.SAFETY_INTERRUPTED
                || horizontalMode && (outcome == ReturnOutcome.NO_PROGRESS
                || outcome == ReturnOutcome.TIMEOUT);
    }

    private void beginReturn(AIPlayerEntity bot, ReturnOutcome outcome) {
        // Settlement time is bounded by pickupGrace, not fresh work. Persist only the capped work
        // budget so RETURN checkpoints remain valid even when a drop settled after the hard edge.
        int exhaustedWorkBudget = Math.min(workBudget(), maxWorkBudget);
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        clearRejectedLandingDirections();
        phase = Phase.RETURN;
        returnOutcome = outcome == null ? ReturnOutcome.COMPLETE : outcome;
        workBudgetAtReturn = exhaustedWorkBudget;
        returnBudgetOffset = 0;
        returnStartTick = elapsed;
        BlockPos current = bot.getBlockPos();
        boolean currentRecorded = !descentTrail.isEmpty()
                && descentTrail.getLast().equals(current);
        // A safety task runs only after this task has been paused and may move the bot before the
        // first RETURN tick. Keep the current factual tail as the first waypoint in that case.
        // If no displacement happened, returnToSurface consumes the equal waypoint immediately.
        boolean mayBeDisplacedWhilePaused = returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED;
        returnTrailIndex = Math.max(-1, descentTrail.size()
                - (currentRecorded && !mayBeDisplacedWhilePaused ? 2 : 1));
        lastReturnPathAttemptBudget = -RETURN_PATH_RETRY;
        returnPathFallback = false;
        returnSafetyRecovery = returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED;
        resetReturnProgressLease(current);
        BotLog.action(bot, "dig_down_return_start", "from", bot.getBlockPos().toShortString(),
                "to", startPos.toShortString(), "trail", descentTrail.size(),
                "outcome", returnOutcome);
    }

    private TrailUpdate rememberDescentStep(BlockPos current) {
        if (current == null || phase != Phase.DESCEND) {
            return TrailUpdate.UNCHANGED;
        }
        BlockPos immutable = current.toImmutable();
        BlockPos last = descentTrail.isEmpty() ? null : descentTrail.getLast();
        if (last == null) {
            descentTrail.add(immutable);
            targetY = Math.min(targetY, immutable.getY());
            return TrailUpdate.APPENDED;
        }
        if (last.equals(immutable)) {
            return TrailUpdate.UNCHANGED;
        }
        int dx = Math.abs(last.getX() - immutable.getX());
        int dy = Math.abs(last.getY() - immutable.getY());
        int dz = Math.abs(last.getZ() - immutable.getZ());
        int changedAxes = changedAxes(dx, dy, dz);
        if (dx <= 1 && dy <= 1 && dz <= 1 && changedAxes >= 1 && changedAxes <= 2) {
            if (descentTrail.size() >= MAX_TRAIL_POINTS) {
                return TrailUpdate.LIMIT;
            }
            descentTrail.add(immutable);
            targetY = Math.min(targetY, immutable.getY());
            return TrailUpdate.APPENDED;
        }
        // Never keep mining from a safety-displaced pose that is disconnected from the factual
        // return trail. Transition to exact RETURN debt; interpolation would forge physical facts.
        return TrailUpdate.DISCONNECTED;
    }

    private String returnFailureReason(AIPlayerEntity bot, ReturnOutcome outcome) {
        return switch (outcome) {
            case COMPLETE -> "dig_down_return_completed_without_failure";
            case TIMEOUT -> "dig_down_timeout collected=" + collected;
            case NEED_BETTER_TOOL -> "need_better_tool:"
                    + ToolTier.requiredPickaxeItemId(targetBlock);
            case NO_PROGRESS -> "dig_down_no_progress collected=" + collected;
            case TRAIL_LIMIT -> "dig_down_trail_limit";
            case WALLED -> "dig_down_walled collected=" + collected;
            case SAFETY_INTERRUPTED -> "dig_down_safety_interrupted collected=" + collected;
            case NET_DELIVERY_SHORTFALL -> "dig_down_net_delivery_shortfall:have="
                    + Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops)
                    - invBaseline) + ":required=" + targetCount;
        };
    }

    private int grossCollectionTarget() {
        return grossCollectionTarget(Registries.BLOCK.getId(targetBlock).toString(), targetCount,
                startPos, targetY);
    }

    private static int grossCollectionTarget(String targetBlockId, int requested,
                                             BlockPos startPos, int targetY) {
        int safeRequested = Math.max(1, requested);
        int returnMaterialReserve = startPos == null ? 0
                : Math.min(MAX_DESCENT, Math.max(0, startPos.getY() - targetY));
        return "minecraft:stone".equals(targetBlockId)
                ? safeRequested + returnMaterialReserve
                : safeRequested;
    }

    /**
     * DigDown's no-progress watchdog remains the fast stuck detector. The independent hard budget
     * must therefore scale with useful quota instead of cutting a healthy large batch at the same
     * two-minute boundary as a three-block bootstrap. Stone also budgets its worst-case factual
     * return reserve, because those extra blocks are part of the bounded work required before a
     * safe exact return can begin.
     */
    static int maxWorkBudgetForTarget(String targetBlockId, int targetCount) {
        int safeTarget = Math.max(1, targetCount);
        long budgetedBlocks = (long) safeTarget
                + ("minecraft:stone".equals(targetBlockId) ? MAX_DESCENT : 0);
        long scaled = budgetedBlocks * MAX_ELAPSED_PER_BLOCK;
        return (int) Math.min(MAX_ELAPSED_CAP, Math.max(MAX_ELAPSED_BASE, scaled));
    }

    // 采够后优先按真实走过的台阶逐格逆行；只有轨迹缺口才对下一 waypoint 做短路径兜底。
    private void returnToSurface(AIPlayerEntity bot) {
        miner.cancel(bot);
        BlockPos at = bot.getBlockPos();
        if (hasReturned(startPos, at)) {
            bot.getActionPack().stopAll();
            settleAfterExactReturn(bot, returnOutcome);
            return;
        }
        while (returnTrailIndex >= 0 && at.equals(descentTrail.get(returnTrailIndex))) {
            returnTrailIndex--;
            returnPathFallback = false;
            resetReturnProgressLease(at);
        }
        noteReturnDistanceProgress(at);
        int currentReturnBudget = returnBudget();
        if (currentReturnBudget > returnHardLimit()) {
            bot.getActionPack().stopAll();
            fail("dig_down_return_failed:hard_limit from=" + at.toShortString()
                    + " to=" + startPos.toShortString());
            return;
        }
        if (currentReturnBudget - lastReturnProgressBudget > RETURN_STALL_LIMIT) {
            bot.getActionPack().stopAll();
            fail("dig_down_return_failed:stalled from=" + at.toShortString()
                    + " to=" + startPos.toShortString());
            return;
        }
        // A recorded stair cell can become unsupported after the descent (for example, sand or
        // gravel above the tunnel falls when an adjacent block is mined). Retrying a path to that
        // stale waypoint can snap the goal back to the current/lower stand and report an empty
        // successful path forever. Skip only historical intermediate cells that are no longer
        // factual landings; index 0 is the exact start debt and must never be skipped.
        while (returnTrailIndex > 0
                && !isSafeReturnLanding(bot.getServerWorld(), descentTrail.get(returnTrailIndex))) {
            BlockPos skipped = descentTrail.get(returnTrailIndex);
            // A factual stair can lose only its support when a later branch cuts underneath it.
            // Skipping that ascending cell may leave the next waypoint two blocks away and make
            // generic pathing drop back into the shaft (the water-sealed seed-3000 failure). Pay
            // the reserved return-material budget first: place one normal survival block under
            // the clear body cell, then retry the exact recorded micro-step on the next tick.
            if (tryRepairReturnSupport(bot, skipped)) {
                return;
            }
            bot.getActionPack().stopAll();
            BotLog.action(bot, "dig_down_return_waypoint_skipped",
                    "at", skipped.toShortString(), "reason", "unsafe_landing");
            returnTrailIndex--;
            returnPathFallback = false;
            lastReturnPathAttemptBudget = returnBudget() - RETURN_PATH_RETRY;
            resetReturnProgressLease(at);
        }
        BlockPos waypoint = returnTrailIndex >= 0
                ? descentTrail.get(returnTrailIndex) : startPos;
        if (at.equals(waypoint)) {
            returnTrailIndex--;
            returnPathFallback = false;
            resetReturnProgressLease(at);
            return;
        }

        // The exact entry cannot be skipped. A later mining branch may have removed startPos.down()
        // after that cell was recorded, just like an intermediate stair support. Repair it while
        // still standing on the adjacent factual waypoint; otherwise generic pathing can snap two
        // blocks down the open column and loop until RETURN_LIMIT.
        if (!isSafeReturnLanding(bot.getServerWorld(), waypoint)
                && tryRepairReturnSupport(bot, waypoint)) {
            return;
        }

        int dy = waypoint.getY() - at.getY();
        if (!returnPathFallback
                && isValidReturnMicroStep(at, waypoint)
                && isSafeReturnLanding(bot.getServerWorld(), waypoint)) {
            bot.getActionPack().stopAll();
            boolean moved = dy > 0
                    ? io.github.zoyluo.aibot.mode.FakePlayerMotion.jumpTo(
                    bot, waypoint, "dig_down_return_trail")
                    : io.github.zoyluo.aibot.mode.FakePlayerMotion.stepTo(
                    bot, waypoint, "dig_down_return_trail");
            if (moved) {
                returnTrailIndex--;
                returnPathFallback = false;
                resetReturnProgressLease(bot.getBlockPos());
            } else {
                returnPathFallback = true;
                BotLog.action(bot, "dig_down_return_path_fallback",
                        "from", at.toShortString(), "to", waypoint.toShortString(),
                        "reason", "micro_step_failed");
            }
        } else {
            if (!returnPathFallback) {
                returnPathFallback = true;
                BotLog.action(bot, "dig_down_return_path_fallback",
                        "from", at.toShortString(), "to", waypoint.toShortString(),
                        "reason", isValidReturnMicroStep(at, waypoint)
                                ? "unsafe_landing" : "non_adjacent_or_three_axis");
            }
        }

        if (returnPathFallback && bot.getActionPack().isPathExecutorIdle()
                && returnBudget() - lastReturnPathAttemptBudget >= RETURN_PATH_RETRY) {
            lastReturnPathAttemptBudget = returnBudget();
            // The ordinary two-phase path may accept a WALK result whose endpoint was snapped
            // down from an unsupported exact entry. DIG approach preserves a dry, clear requested
            // endpoint and lets the existing PILLAR_UP executor pay the real multi-block climb.
            var result = bot.getActionPack().startDigPathTo(waypoint);
            if (result.isFailed()) {
                BotLog.action(bot, "dig_down_return_path_retry",
                        "target", waypoint.toShortString(), "reason", result.reason());
            }
        }
    }

    /**
     * Settles the batch only after the exact entry debt is paid. A work timeout describes why the
     * descent stopped, not whether the requested material was ultimately delivered: return repair
     * can consume the gross reserve while still leaving the full requested quota in inventory.
     * Conversely, inventory is never allowed to forgive an incomplete/failed return route.
     */
    private void settleAfterExactReturn(AIPlayerEntity bot, ReturnOutcome outcome) {
        returnOutcome = outcome == null ? ReturnOutcome.COMPLETE : outcome;
        // A safety interruption is factual evidence about this exact mine entry, but publishing it
        // when pause begins would spend its 1,200-tick episode TTL during a return that may itself
        // take up to 2,400 ticks. Refresh only after the exact startPos debt has been paid, while the
        // typed cause is still intact, so a fresh remaining-quota plan must physically change column.
        if (returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED) {
            rememberUnusableEntry(bot, returnOutcome);
        }
        int netDelivered = Math.max(0,
                HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (netDelivered >= targetCount) {
            collected = netDelivered;
            returnOutcome = ReturnOutcome.COMPLETE;
        } else if (returnOutcome == ReturnOutcome.COMPLETE) {
            collected = netDelivered;
            returnOutcome = ReturnOutcome.NET_DELIVERY_SHORTFALL;
        }
        BotLog.action(bot, "dig_down_return_done", "pos", bot.getBlockPos().toShortString(),
                "surfaced", "true", "outcome", returnOutcome,
                "net_delivered", netDelivered + "/" + targetCount);
        phase = Phase.DONE;
        if (returnOutcome == ReturnOutcome.COMPLETE) {
            complete();
        } else {
            fail(returnFailureReason(bot, returnOutcome));
        }
    }

    private int returnHardLimit() {
        return returnSafetyRecovery || returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED
                ? RETURN_SAFETY_LIMIT : RETURN_LIMIT;
    }

    private BlockPos pendingReturnWaypoint() {
        return returnTrailIndex >= 0 && returnTrailIndex < descentTrail.size()
                ? descentTrail.get(returnTrailIndex) : startPos;
    }

    private void noteReturnDistanceProgress(BlockPos current) {
        BlockPos waypoint = pendingReturnWaypoint();
        if (current == null || waypoint == null) {
            return;
        }
        long distanceSquared = squaredDistance(current, waypoint);
        if (returnProgressWaypointIndex != returnTrailIndex
                || returnBestDistanceSquared < 0L
                || distanceSquared < returnBestDistanceSquared) {
            lastReturnProgressBudget = returnBudget();
            returnProgressWaypointIndex = returnTrailIndex;
            returnBestDistanceSquared = distanceSquared;
        }
    }

    private void resetReturnProgressLease(BlockPos current) {
        lastReturnProgressBudget = returnBudget();
        returnProgressWaypointIndex = returnTrailIndex;
        BlockPos waypoint = pendingReturnWaypoint();
        returnBestDistanceSquared = current == null || waypoint == null
                ? -1L : squaredDistance(current, waypoint);
    }

    private static long squaredDistance(BlockPos from, BlockPos to) {
        long dx = (long) from.getX() - to.getX();
        long dy = (long) from.getY() - to.getY();
        long dz = (long) from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean hasReturned(BlockPos start, BlockPos current) {
        return start != null && start.equals(current);
    }

    static boolean isValidReturnMicroStep(BlockPos from, BlockPos to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());
        int changedAxes = changedAxes(dx, dy, dz);
        return dx <= 1 && dy <= 1 && dz <= 1 && changedAxes >= 1 && changedAxes <= 2;
    }

    private static int changedAxes(int dx, int dy, int dz) {
        return (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
    }

    static boolean isSafeReturnLanding(ServerWorld world, BlockPos pos) {
        return world != null && pos != null
                && Standability.isStandable(world, pos)
                && world.getFluidState(pos).isEmpty()
                && world.getFluidState(pos.up()).isEmpty();
    }

    private boolean tryRepairReturnSupport(AIPlayerEntity bot, BlockPos landing) {
        ServerWorld world = bot.getServerWorld();
        // A level intermediate gap can still be routed around and the established skip behavior
        // preserves the mission payload. The exact origin is different: it may never be skipped,
        // so repair its support at any relative height while it is still physically reachable.
        boolean exactOrigin = startPos != null && startPos.equals(landing);
        BlockPos current = bot.getBlockPos();
        if ((!exactOrigin && landing.getY() <= current.getY())
                || !isValidReturnMicroStep(current, landing)
                || !bot.getActionPack().isPathExecutorIdle()
                || !isClearReturnBody(world, landing)) {
            return false;
        }
        BlockPos support = landing.down();
        // A support equal to the current feet cell needs jump-then-place pillar semantics. A raw
        // place attempt cannot succeed while the player occupies it; leave it to exact DIG pathing.
        if (support.equals(current)) {
            return false;
        }
        if (isSafeSupport(world, support)) {
            return false;
        }
        var supportState = world.getBlockState(support);
        if (!supportState.isReplaceable()
                && !supportState.getCollisionShape(world, support).isEmpty()) {
            return false;
        }
        OptionalInt slot = MaterialPalette.pickPathSupportBlockSlot(bot);
        if (slot.isEmpty()) {
            return false;
        }
        bot.getActionPack().stopAll();
        InventoryAction.equipFromSlot(bot, slot.getAsInt());
        ActionResult placed = BuildAction.placeBlockAt(bot, support);
        if (placed.isFailed()) {
            return false;
        }
        returnPathFallback = false;
        resetReturnProgressLease(bot.getBlockPos());
        BotLog.action(bot, "dig_down_return_support_repaired",
                "landing", landing.toShortString(),
                "support", support.toShortString());
        return true;
    }

    private static boolean isClearReturnBody(ServerWorld world, BlockPos landing) {
        if (world == null || landing == null) {
            return false;
        }
        for (BlockPos cell : new BlockPos[]{landing, landing.up()}) {
            var state = world.getBlockState(cell);
            if (!state.getFluidState().isEmpty()
                    || !state.getCollisionShape(world, cell).isEmpty()
                    || Standability.isDangerous(state)) {
                return false;
            }
        }
        return true;
    }

    private void noteWorkProgress() {
        lastProgressBudget = Math.min(workBudget(), maxWorkBudget);
    }

    private void refreshCollected(AIPlayerEntity bot) {
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 4.0D, 12.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (total <= collected) {
            return;
        }
        collected = total;
        noteWorkProgress();
        BotLog.action(bot, "dig_down_collected", "total", collected + "/" + targetCount);
    }

    private boolean hasHorizontalFrontierSettleDebt() {
        return phase == Phase.DESCEND && pickupGrace > 0
                && collected < grossCollectionTarget();
    }

    private void armHorizontalFrontierSettle(AIPlayerEntity bot) {
        if (pickupGrace > 0) {
            return;
        }
        pickupGrace = 1;
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, "dig_down_horizontal_settle_start",
                "at", bot.getBlockPos().toShortString(),
                "work_budget", Math.min(workBudget(), maxWorkBudget),
                "collected", collected + "/" + grossCollectionTarget());
    }

    private void settleHorizontalFrontier(AIPlayerEntity bot) {
        miner.cancel(bot);
        refreshCollected(bot);
        if (collected >= grossCollectionTarget()) {
            beginReturn(bot);
            return;
        }
        if (pickupGrace >= PICKUP_GRACE_TICKS) {
            failAfterExactReturn(bot, ReturnOutcome.WALLED);
            return;
        }
        pickupGrace++;
    }

    /** True only when every currently owned horizontal option is closed by factual geometry. */
    private boolean isObservedClosedHorizontalFrontier(ServerWorld world, BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        for (int directionIndex = 0; directionIndex < HDIRS.length; directionIndex++) {
            if ((rejectedLandingDirections & 1 << directionIndex) != 0) {
                continue;
            }
            Direction direction = HDIRS[directionIndex];
            BlockPos side = feet.offset(direction);
            if (isLava(world, side) || isLava(world, side.up()) || isLava(world, side.down())
                    || isWater(world, side) || isWater(world, side.up()) || isWater(world, side.down())
                    || !isSafeSupport(world, side.down())) {
                continue;
            }
            if (firstSolid(world, side, side.up()) != null || !descentTrail.contains(side)) {
                return false;
            }
        }
        return true;
    }

    private int workBudget() {
        return phase == Phase.DESCEND
                ? workBudgetOffset + elapsed
                : workBudgetAtReturn;
    }

    private int returnBudget() {
        return phase == Phase.RETURN
                ? returnBudgetOffset + Math.max(0, elapsed - returnStartTick)
                : returnBudgetOffset;
    }

    // 海平面/含水层下挖防淹:阶梯斜下会逐步漂向相邻海洋,水从【侧向】涌入脚位/头位泡死
    // (实测沙滩 Y63 出生 dig_down 连淹 12 次→guard 抢断→上浮→replan 重挖同列→死循环;
    // stair 只查正下/正前的水(L260),管不住侧向)。真人挖矿砌墙挡水:每 tick 封堵脚位+头位四周的水,
    // 从根上防淹,而非泡死再被生存层兜底。干燥地形无水→直接返回零放置、不改行为(geo_deep/shaft/cave 不回归)。
    // 返回 true=本 tick 封了一格(调用方收手,下 tick 续;BlockMiner 开挖会自动换回镐)。
    private boolean sealLateralWater(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        // ① 侧向:脚位+头位四周(阶梯斜下漂向海洋时水平涌入)。
        for (BlockPos level : new BlockPos[]{feet, feet.up()}) {
            for (Direction d : HDIRS) {
                if (trySealWater(bot, world, level.offset(d))) {
                    return true;
                }
            }
        }
        // ② 封顶:头顶上方一格——重水种子只封侧向仍 drown 的根因是水从竖井【上方】灌到头位
        //(实测 seal 侧向 25 次仍 drown 12);把头顶也堵上,bot 在 1×2 旱泡里下挖,氧不再被顶部灌水耗光。
        return trySealWater(bot, world, feet.up(2));
    }

    // 该格是水则封一块(真人砌墙/封顶挡水)。主手是镐时对水格交互被原版判 PASS 静默吞掉
    //(同 OreDigTask 封浆教训)→ 先装方块再放。返回 true=封了一格(调用方收手下 tick 续,BlockMiner 自动换回镐);
    // 无水/无块可封→false(交后续逻辑/生存层兜底,命比这格值钱,不卡死)。
    private boolean trySealWater(AIPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (!isWater(world, pos)) {
            return false;
        }
        OptionalInt blockSlot = MaterialPalette.pickSacrificialBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
        if (BuildAction.placeBlockAt(bot, pos).isFailed()) {
            return false;
        }
        // A BlockMiner started before this tick may still own exactly the water cell that has now
        // become our safety wall. Cancel that stale break intent and reject its stair direction;
        // otherwise the next miner tick removes the seal, water refills it and the task consumes
        // every portable block in an endless seal/mine loop.
        miner.cancel(bot);
        rejectSealedStairDirection(bot.getBlockPos(), pos);
        BotLog.action(bot, "dig_down_seal_water", "at", pos.toShortString());
        noteWorkProgress(); // 封堵=进展,别被 NO_PROGRESS 看门狗误杀
        return true;
    }

    private void rejectSealedStairDirection(BlockPos feet, BlockPos sealed) {
        for (int i = 0; i < HDIRS.length; i++) {
            BlockPos side = feet.offset(HDIRS[i]);
            if (sealed.equals(side) || sealed.equals(side.up())) {
                rejectLandingDirection(feet, i);
                return;
            }
        }
    }

    private static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    // 台阶斜下:换到下一个既不挨流体、又有真实踏面支撑的方向。天然坡面接悬崖时 ahead/next
    // 可能全是空气；旧逻辑只查水/岩浆，会永远对同一 unsupported next 调 descendInto。
    private boolean rotateStair(ServerWorld world, BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        for (int i = 0; i < HDIRS.length; i++) {
            stairDirIndex = (stairDirIndex + 1) % HDIRS.length;
            if ((rejectedLandingDirections & 1 << stairDirIndex) != 0) {
                continue;
            }
            if (isViableStairDirection(world, feet, HDIRS[stairDirIndex])) {
                return true;
            }
        }
        return false;
    }

    private void rejectLandingDirection(BlockPos origin, int direction) {
        ensureRejectedLandingOrigin(origin);
        if (direction >= 0 && direction < HDIRS.length) {
            rejectedLandingDirections |= 1 << direction;
        }
    }

    private void ensureRejectedLandingOrigin(BlockPos origin) {
        if (rejectedLandingOrigin == null || !rejectedLandingOrigin.equals(origin)) {
            rejectedLandingOrigin = origin.toImmutable();
            rejectedLandingDirections = 0;
        }
    }

    private void clearRejectedLandingDirections() {
        rejectedLandingOrigin = null;
        rejectedLandingDirections = 0;
    }

    static boolean isViableStairDirection(ServerWorld world, BlockPos feet, Direction direction) {
        BlockPos ahead = feet.offset(direction);
        BlockPos next = ahead.down();
        BlockPos support = next.down();
        if (isLava(world, ahead) || isLava(world, ahead.up())
                || isLava(world, next) || isLava(world, support)
                || isWater(world, ahead) || isWater(world, ahead.up())
                || isWater(world, next) || isWater(world, support)) {
            return false;
        }
        if (!isSafeSupport(world, support)) {
            return false;
        }
        return isClearableForStair(world, ahead)
                && isClearableForStair(world, ahead.up())
                && isClearableForStair(world, next);
    }

    private static boolean isSafeSupport(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isClearableForStair(ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || Standability.isDangerous(state)) {
            return false;
        }
        if (state.getCollisionShape(world, pos).isEmpty()) {
            return true;
        }
        return state.getHardness(world, pos) >= 0.0F && world.getBlockEntity(pos) == null;
    }

    // 撞基岩(向下到底)后转水平掘进:沿一个方向逐格挖石料,挖通就走进去换列继续。深层全深板岩,
    // 配合构造里把 cobbled_deepslate 计入 targetDrops,即可在 Y<0 凑够做熔炉的石料,不再死循环。
    private void digHorizontal(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        for (int tries = 0; tries < HDIRS.length; tries++) {
            if ((rejectedLandingDirections & 1 << hdirIndex) != 0) {
                hdirIndex = (hdirIndex + 1) % HDIRS.length;
                continue;
            }
            Direction dir = HDIRS[hdirIndex];
            BlockPos side = feet.offset(dir);
            // 对称岩浆检查也避开水:横挖挖进水里同样溺水(原仅查 lava 是缺口);四面皆水/浆则下面 walled 失败交生存层。
            if (isLava(world, side) || isLava(world, side.up()) || isLava(world, side.down())
                    || isWater(world, side) || isWater(world, side.up()) || isWater(world, side.down())) {
                hdirIndex = (hdirIndex + 1) % HDIRS.length; // 这个方向挨水/岩浆,换一个
                continue;
            }
            if (!isSafeSupport(world, side.down())) {
                hdirIndex = (hdirIndex + 1) % HDIRS.length;
                continue; // 横移也不能走进悬空格；换有支撑的方向
            }
            BlockPos solid = firstSolid(world, side, side.up());
            if (solid != null) {
                if (miner.target() == null || !miner.target().equals(solid)) {
                    miner.begin(bot, solid); // 由 onTick 顶部的 miner.tick 推进
                }
                return;
            }
            // 已记录的空格是精确返程轨迹，不是新的采掘前沿。走到旧矿道端点后
            // 若允许踏回轨迹，方向轮转会令 bot 在整条走廊两端 ping-pong，
            // 即使位置在变也没有任何新破块。只限制“双空气移动”；若方向上
            // 出现新的可挖实体块，上面的 solid 分支仍会正常开挖。
            if (descentTrail.contains(side)) {
                rejectLandingDirection(feet, hdirIndex);
                hdirIndex = (hdirIndex + 1) % HDIRS.length;
                continue;
            }
            // side 脚位+头位皆空 → 走进去换列,继续水平挖。这里只是相邻一格，
            // 不能每 tick 重启 startWalkTo：已挖通的长走廊会因控制器反复换目标而前后
            // 摆动，最终在没有新破块时误触 NO_PROGRESS。改用共享的相邻物理步，
            // 并在同一 tick 把落脚点写入事实轨迹，保证中断/重启后仍能精确返程。
            miner.cancel(bot);
            if (io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                    bot, side, "dig_down_horizontal")) {
                TrailUpdate stepUpdate = rememberDescentStep(bot.getBlockPos());
                if (stepUpdate == TrailUpdate.LIMIT) {
                    failAfterExactReturn(bot, ReturnOutcome.TRAIL_LIMIT);
                    return;
                }
                if (stepUpdate == TrailUpdate.DISCONNECTED) {
                    failAfterExactReturn(bot, ReturnOutcome.SAFETY_INTERRUPTED);
                    return;
                }
                clearRejectedLandingDirections();
                noteWorkProgress();
                return;
            }
            rejectLandingDirection(feet, hdirIndex);
            hdirIndex = (hdirIndex + 1) % HDIRS.length;
        }
        // A freshly mined horizontal frontier can close every onward direction immediately after
        // the bot steps into its drop cell. Persist an explicit bounded pickup debt before WALLED;
        // onTick services it ahead of the hard work budget, including after restart.
        armHorizontalFrontierSettle(bot);
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    // 3 参版:依次返回第一个"固体且非流体"的格(流体跳过不挖→防溃浆/溃水)。下潜台阶清三格身位
    // (ahead 头位 + ahead.up 头顶净空 + next 脚位),保证下潜巷道 2 格可走高、正常玩家能通过。
    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b, BlockPos c) {
        for (BlockPos p : new BlockPos[]{a, b, c}) {
            if (!world.getBlockState(p).isAir() && world.getFluidState(p).isEmpty()) {
                return p.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir()) {
            return a.toImmutable();
        }
        if (!world.getBlockState(b).isAir()) {
            return b.toImmutable();
        }
        return null;
    }

    @Override
    public Map<String, String> checkpoint() {
        if (invalidCheckpoint || startPos == null || phase == Phase.DONE || descentTrail.isEmpty()) {
            return Map.of();
        }
        DigDownCheckpoint live = new DigDownCheckpoint(
                CHECKPOINT_SCHEMA,
                Registries.BLOCK.getId(targetBlock).toString(),
                targetCount,
                phase,
                returnOutcome,
                startPos,
                targetY,
                invBaseline,
                collected,
                phase == Phase.DESCEND ? Math.min(workBudget(), maxWorkBudget) : workBudgetAtReturn,
                lastProgressBudget,
                pickupGrace,
                hdirIndex,
                stairDirIndex,
                horizontalMode,
                rejectedLandingOrigin,
                rejectedLandingDirections,
                List.copyOf(descentTrail),
                returnTrailIndex,
                returnBudget(),
                lastReturnPathAttemptBudget,
                returnPathFallback,
                lastReturnProgressBudget,
                returnProgressWaypointIndex,
                returnBestDistanceSquared,
                returnSafetyRecovery);
        Map<String, String> encoded = live.encode();
        return DigDownCheckpoint.decode(encoded).isPresent() ? encoded : Map.of();
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> values) {
        return DigDownCheckpoint.decode(values).flatMap(checkpoint ->
                Registries.BLOCK.getOptionalValue(Identifier.of(checkpoint.targetBlockId()))
                        .map(block -> new RestoreMetadata(
                                block,
                                checkpoint.targetCount(),
                                checkpoint.resumePhase() == Phase.RETURN)));
    }

    public record RestoreMetadata(Block targetBlock, int targetCount, boolean returnDebt) {
    }

    record DigDownCheckpoint(int schema,
                             String targetBlockId,
                             int targetCount,
                             Phase phase,
                             ReturnOutcome returnOutcome,
                             BlockPos startPos,
                             int targetY,
                             int inventoryBaseline,
                             int collected,
                             int workBudgetUsed,
                             int lastProgressBudget,
                             int pickupGrace,
                             int horizontalDirection,
                             int stairDirection,
                             boolean horizontalMode,
                             BlockPos rejectedLandingOrigin,
                             int rejectedLandingDirections,
                             List<BlockPos> trail,
                             int returnTrailIndex,
                             int returnBudgetUsed,
                             int lastReturnPathAttemptBudget,
                             boolean returnPathFallback,
                             int lastReturnProgressBudget,
                             int returnProgressWaypointIndex,
                             long returnBestDistanceSquared,
                             boolean returnSafetyRecovery) {
        private static final Set<String> KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "rejected_landing_origin", "rejected_landing_directions",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback", "return_outcome", "return_last_progress_budget",
                "return_progress_waypoint_index", "return_best_distance_squared",
                "return_safety_recovery");
        private static final Set<String> RETURN_OUTCOME_KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "rejected_landing_origin", "rejected_landing_directions",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback", "return_outcome");
        private static final Set<String> WATER_SEAL_KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "rejected_landing_origin", "rejected_landing_directions",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback");
        private static final Set<String> LEGACY_KEYS = Set.of(
                "schema", "target_block", "target_count", "phase", "start_pos", "target_y",
                "inventory_baseline", "collected", "work_budget_used", "last_progress_budget",
                "pickup_grace", "horizontal_direction", "stair_direction", "horizontal_mode",
                "trail", "return_index", "return_budget_used", "last_return_path_budget",
                "return_path_fallback");

        DigDownCheckpoint {
            returnOutcome = returnOutcome == null ? ReturnOutcome.COMPLETE : returnOutcome;
            startPos = startPos == null ? null : startPos.toImmutable();
            rejectedLandingOrigin = rejectedLandingOrigin == null
                    ? null : rejectedLandingOrigin.toImmutable();
            trail = trail == null ? List.of() : trail.stream().map(BlockPos::toImmutable).toList();
        }

        DigDownCheckpoint(int schema,
                          String targetBlockId,
                          int targetCount,
                          Phase phase,
                          ReturnOutcome returnOutcome,
                          BlockPos startPos,
                          int targetY,
                          int inventoryBaseline,
                          int collected,
                          int workBudgetUsed,
                          int lastProgressBudget,
                          int pickupGrace,
                          int horizontalDirection,
                          int stairDirection,
                          boolean horizontalMode,
                          BlockPos rejectedLandingOrigin,
                          int rejectedLandingDirections,
                          List<BlockPos> trail,
                          int returnTrailIndex,
                          int returnBudgetUsed,
                          int lastReturnPathAttemptBudget,
                          boolean returnPathFallback) {
            this(schema, targetBlockId, targetCount, phase, returnOutcome, startPos, targetY,
                    inventoryBaseline, collected, workBudgetUsed, lastProgressBudget, pickupGrace,
                    horizontalDirection, stairDirection, horizontalMode, rejectedLandingOrigin,
                    rejectedLandingDirections, trail, returnTrailIndex, returnBudgetUsed,
                    lastReturnPathAttemptBudget, returnPathFallback,
                    phase == Phase.RETURN ? returnBudgetUsed : 0,
                    phase == Phase.RETURN ? returnTrailIndex : -1,
                    -1L,
                    phase == Phase.RETURN && returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED);
        }

        Phase resumePhase() {
            return phase == Phase.DESCEND
                    && collected >= grossCollectionTarget(
                    targetBlockId, targetCount, startPos, targetY)
                    ? Phase.RETURN : phase;
        }

        Map<String, String> encode() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("schema", String.valueOf(schema));
            values.put("target_block", targetBlockId);
            values.put("target_count", String.valueOf(targetCount));
            values.put("phase", phase.name());
            if (schema >= RETURN_OUTCOME_CHECKPOINT_SCHEMA) {
                values.put("return_outcome", returnOutcome.name());
            }
            values.put("start_pos", encodePos(startPos));
            values.put("target_y", String.valueOf(targetY));
            values.put("inventory_baseline", String.valueOf(inventoryBaseline));
            values.put("collected", String.valueOf(collected));
            values.put("work_budget_used", String.valueOf(workBudgetUsed));
            values.put("last_progress_budget", String.valueOf(lastProgressBudget));
            values.put("pickup_grace", String.valueOf(pickupGrace));
            values.put("horizontal_direction", String.valueOf(horizontalDirection));
            values.put("stair_direction", String.valueOf(stairDirection));
            values.put("horizontal_mode", String.valueOf(horizontalMode));
            if (schema >= WATER_SEAL_CHECKPOINT_SCHEMA) {
                values.put("rejected_landing_origin", rejectedLandingOrigin == null
                        ? "none" : encodePos(rejectedLandingOrigin));
                values.put("rejected_landing_directions", String.valueOf(rejectedLandingDirections));
            }
            values.put("trail", trail.stream()
                    .map(DigDownTask::encodePos)
                    .collect(java.util.stream.Collectors.joining(";")));
            values.put("return_index", String.valueOf(returnTrailIndex));
            values.put("return_budget_used", String.valueOf(returnBudgetUsed));
            values.put("last_return_path_budget", String.valueOf(lastReturnPathAttemptBudget));
            values.put("return_path_fallback", String.valueOf(returnPathFallback));
            if (schema >= CHECKPOINT_SCHEMA) {
                values.put("return_last_progress_budget", String.valueOf(lastReturnProgressBudget));
                values.put("return_progress_waypoint_index",
                        String.valueOf(returnProgressWaypointIndex));
                values.put("return_best_distance_squared",
                        String.valueOf(returnBestDistanceSquared));
                values.put("return_safety_recovery", String.valueOf(returnSafetyRecovery));
            }
            return Map.copyOf(values);
        }

        static Optional<DigDownCheckpoint> decode(Map<String, String> values) {
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            try {
                int schema = integer(values, "schema");
                if (schema == LEGACY_CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(LEGACY_KEYS)) {
                        return Optional.empty();
                    }
                } else if (schema == WATER_SEAL_CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(WATER_SEAL_KEYS)) {
                        return Optional.empty();
                    }
                } else if (schema == RETURN_OUTCOME_CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(RETURN_OUTCOME_KEYS)) {
                        return Optional.empty();
                    }
                } else if (schema == CHECKPOINT_SCHEMA) {
                    if (!values.keySet().equals(KEYS)) {
                        return Optional.empty();
                    }
                } else {
                    return Optional.empty();
                }
                String blockId = Identifier.of(required(values, "target_block")).toString();
                int targetCount = integer(values, "target_count");
                Phase phase = Phase.valueOf(required(values, "phase"));
                ReturnOutcome returnOutcome = schema < RETURN_OUTCOME_CHECKPOINT_SCHEMA
                        ? ReturnOutcome.COMPLETE
                        : ReturnOutcome.valueOf(required(values, "return_outcome"));
                BlockPos start = decodePos(required(values, "start_pos")).orElse(null);
                int targetY = integer(values, "target_y");
                int baseline = integer(values, "inventory_baseline");
                int collected = integer(values, "collected");
                int workBudget = integer(values, "work_budget_used");
                int lastProgress = integer(values, "last_progress_budget");
                int pickupGrace = integer(values, "pickup_grace");
                int hdir = integer(values, "horizontal_direction");
                int stair = integer(values, "stair_direction");
                boolean horizontal = strictBoolean(values, "horizontal_mode");
                BlockPos rejectedOrigin = schema < WATER_SEAL_CHECKPOINT_SCHEMA
                        ? null : decodeOptionalPos(required(values, "rejected_landing_origin"));
                int rejectedDirections = schema < WATER_SEAL_CHECKPOINT_SCHEMA
                        ? 0 : integer(values, "rejected_landing_directions");
                List<BlockPos> trail = decodeTrail(required(values, "trail"));
                int returnIndex = integer(values, "return_index");
                int returnBudget = integer(values, "return_budget_used");
                int lastReturnPath = integer(values, "last_return_path_budget");
                boolean pathFallback = strictBoolean(values, "return_path_fallback");
                int returnLastProgress = schema < CHECKPOINT_SCHEMA
                        ? (phase == Phase.RETURN ? returnBudget : 0)
                        : integer(values, "return_last_progress_budget");
                int returnProgressIndex = schema < CHECKPOINT_SCHEMA
                        ? (phase == Phase.RETURN ? returnIndex : -1)
                        : integer(values, "return_progress_waypoint_index");
                long returnBestDistance = schema < CHECKPOINT_SCHEMA
                        ? -1L : longInteger(values, "return_best_distance_squared");
                boolean safetyRecovery = schema < CHECKPOINT_SCHEMA
                        ? phase == Phase.RETURN
                        && returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED
                        : strictBoolean(values, "return_safety_recovery");

                int minimumTrailY = trail.stream().mapToInt(BlockPos::getY).min().orElse(Integer.MAX_VALUE);
                boolean validTrail = start != null && !trail.isEmpty() && trail.getFirst().equals(start)
                        && trail.size() <= MAX_TRAIL_POINTS && minimumTrailY == targetY;
                for (int i = 1; validTrail && i < trail.size(); i++) {
                    validTrail = isValidReturnMicroStep(trail.get(i - 1), trail.get(i));
                }
                boolean phaseShape = switch (phase) {
                    case DESCEND -> returnIndex == -1 && returnBudget == 0 && !pathFallback
                            && returnOutcome == ReturnOutcome.COMPLETE
                            && returnLastProgress == 0 && returnProgressIndex == -1
                            && returnBestDistance == -1L && !safetyRecovery;
                    case RETURN -> returnIndex >= -1 && returnIndex < trail.size();
                    case DONE -> false;
                };
                boolean rejectedShape = rejectedDirections >= 0
                        && rejectedDirections < (1 << HDIRS.length)
                        && (rejectedDirections == 0 || rejectedOrigin != null)
                        && (rejectedOrigin == null || !trail.isEmpty()
                        && rejectedOrigin.equals(trail.getLast()))
                        && (phase != Phase.RETURN || rejectedOrigin == null);
                if (targetCount < 1 || targetCount > 4096
                        || baseline < 0 || baseline > 4096
                        || collected < 0 || collected > targetCount + 64
                        || workBudget < 0
                        || workBudget > maxWorkBudgetForTarget(blockId, targetCount)
                        || lastProgress < 0 || lastProgress > workBudget
                        || pickupGrace < 0 || pickupGrace > PICKUP_GRACE_TICKS + 1
                        || hdir < 0 || hdir >= HDIRS.length
                        || stair < 0 || stair >= HDIRS.length
                        || returnBudget < 0
                        || returnBudget > (schema >= CHECKPOINT_SCHEMA
                        && (safetyRecovery || returnOutcome == ReturnOutcome.SAFETY_INTERRUPTED)
                        ? RETURN_SAFETY_LIMIT : RETURN_LIMIT)
                        || lastReturnPath < -RETURN_PATH_RETRY || lastReturnPath > returnBudget
                        || returnLastProgress < 0 || returnLastProgress > returnBudget
                        || returnProgressIndex < -1 || returnProgressIndex >= trail.size()
                        || returnBestDistance < -1L
                        || targetY > (start == null ? Integer.MIN_VALUE : start.getY())
                        || !validTrail || !phaseShape || !rejectedShape) {
                    return Optional.empty();
                }
                return Optional.of(new DigDownCheckpoint(schema, blockId, targetCount, phase,
                        returnOutcome,
                        start, targetY, baseline, collected, workBudget, lastProgress, pickupGrace,
                        hdir, stair, horizontal, rejectedOrigin, rejectedDirections,
                        trail, returnIndex, returnBudget,
                        lastReturnPath, pathFallback, returnLastProgress, returnProgressIndex,
                        returnBestDistance, safetyRecovery));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        private static int integer(Map<String, String> values, String key) {
            return Integer.parseInt(required(values, key));
        }

        private static long longInteger(Map<String, String> values, String key) {
            return Long.parseLong(required(values, key));
        }

        private static boolean strictBoolean(Map<String, String> values, String key) {
            return switch (required(values, key)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("invalid_boolean:" + key);
            };
        }

        private static BlockPos decodeOptionalPos(String value) {
            if ("none".equals(value)) {
                return null;
            }
            return decodePos(value).orElseThrow();
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing_checkpoint_key:" + key);
            }
            return value;
        }

        private static List<BlockPos> decodeTrail(String encoded) {
            if (encoded.isBlank()) {
                return List.of();
            }
            List<BlockPos> result = new ArrayList<>();
            for (String part : encoded.split(";", -1)) {
                result.add(decodePos(part).orElseThrow());
            }
            return List.copyOf(result);
        }
    }

    private static String encodePos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<BlockPos> decodePos(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
