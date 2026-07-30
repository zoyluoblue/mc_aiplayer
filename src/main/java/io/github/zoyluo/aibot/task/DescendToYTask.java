package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.action.ToolSelector;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningMissionBudget;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * DESCEND_TO_Y(挖深层矿重构 P1):连续挖竖井**下到指定 Y 层**,然后交还 —— 专为"挖钻石/红石等深层矿前先到矿层"设计。
 *
 * 病根(实测):OreDigTask 把"下挖"和"找矿"耦合在一个 scan 限频循环里,从 Y=48 想挖到钻石层(Y<16)时
 * 反复"锁定斜下方够不到的矿→水平掘隧道→dist 卡死→no_progress",卡死 11 分钟。本任务把"下到矿层"独立出来:
 * 用共享 {@link BlockMiner} 连续挖脚下(不受任何限频),bot 无被动重力则主动 descendInto 下沉,
 * 遇岩浆硬停、遇水穿过(与 DigDownTask 一致),一路掘到 targetY。到层后由 GoalExecutor 接 MINE_ORE(此时矿在水平面近处)。
 *
 * 自包含状态机(G1,不自 assign),全程主线程(G2)。
 */
public final class DescendToYTask extends AbstractTask implements CheckpointableTask {
    private static final int CHECKPOINT_SCHEMA = 4;
    private static final int EDGE_CHECKPOINT_SCHEMA = 3;
    private static final int LEGACY_CHECKPOINT_SCHEMA = 2;
    private static final int MAX_CHECKPOINTED_WATER_SEALS = 256;
    private static final int NO_PROGRESS_LIMIT = 200;  // 10s 没破任何块即失败(挖不动/卡住)
    private static final int MIN_Y = -60;
    // One obstructed layer can be the roof of a broad natural cave, not only a lava pool.
    // Keep the graph walk bounded, but leave enough factual movement budget to reach a nearby
    // supported rim after exploring a short dead branch (seed-3000 needs 21 unique edges).
    private static final int MAX_LATERAL = 32;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final int targetY;
    private final BlockMiner miner = new BlockMiner();
    // A water seal is a task-owned safety wall, not ordinary tunnel obstruction.  Direction
    // rejection alone is insufficient because a later horizontal detour can approach the same
    // cell from another origin and otherwise feed it back to BlockMiner.
    private final Map<BlockPos, Block> ownedWaterSeals = new LinkedHashMap<>();
    // A lateral detour is a bounded graph walk around one obstructed layer. Keep the directed
    // edges already crossed so a necessary one-time backtrack remains possible, while A->B->A
    // cannot immediately consume the remaining budget by replaying A->B forever.
    private final Set<DetourEdge> traversedDetourEdges = new LinkedHashSet<>();
    private final RestoreMetadata restoredCheckpoint;
    private final boolean invalidCheckpoint;
    private boolean committed;
    private int budgetOffset;
    private int budgetLimit;
    private int lastProgressTick;
    private int lateralDetours; // 当前高度层已横移绕岩浆/卡点的次数
    private int stairDirIndex;  // 台阶斜下的当前水平方向(HORIZONTAL 下标)
    private int detourHeadingIndex = -1; // 绕行保持航向，把立即原路返回放到最后
    // SafetyNet runs after task ticks.  Remember the most recent physical landing so that, when
    // water/unsupported-footing recovery returns the bot to its origin, the next task tick rotates
    // away instead of issuing the identical rejected edge forever.
    private BlockPos pendingLandingOrigin;
    private BlockPos pendingLandingTarget;
    private int pendingLandingDirection = -1;
    private BlockPos rejectedLandingOrigin;
    private int rejectedLandingDirections;
    // Falling sand/gravel is scheduled after the task tick that opened the stair. Keep the last
    // two factual, dry landings so a newly buried bot can take exactly one ordinary adjacent step
    // back instead of depending on strict-survival's forbidden emergency teleport.
    private BlockPos latestSafeLanding;
    private BlockPos previousSafeLanding;
    private BlockPos blockedBodyRecoveryTarget;
    private boolean started;    // 是否已打 descend_started 日志
    private int lastTorchY = Integer.MAX_VALUE; // P1:上次插火把的 Y(每下 TORCH_EVERY 格插一支)
    private static final int TORCH_EVERY = 6;   // 火把光照半径足够覆盖 6 格落差,不刷怪

    public DescendToYTask(int targetY) {
        this(targetY, Map.of());
    }

    public DescendToYTask(int targetY, Map<String, String> checkpoint) {
        this.targetY = targetY;
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Optional<RestoreMetadata> restored = inspectCheckpoint(values);
        this.invalidCheckpoint = !values.isEmpty()
                && (restored.isEmpty() || restored.orElseThrow().targetY() != targetY);
        this.restoredCheckpoint = invalidCheckpoint ? null : restored.orElse(null);
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()) {
            return Optional.empty();
        }
        try {
            int schema = Integer.parseInt(required(checkpoint, "task_schema"));
            boolean legacyWithoutEdges = schema == LEGACY_CHECKPOINT_SCHEMA;
            boolean legacyBudget = legacyWithoutEdges || schema == EDGE_CHECKPOINT_SCHEMA;
            if (!legacyBudget && schema != CHECKPOINT_SCHEMA) {
                return Optional.empty();
            }
            Set<String> expectedKeys = new LinkedHashSet<>(Set.of(
                    "task_schema",
                    "task_open",
                    "target_y",
                    "budget_used",
                    "last_progress_budget",
                    "stair_direction",
                    "lateral_detours",
                    "detour_heading",
                    "pending_landing_origin",
                    "pending_landing_target",
                    "pending_landing_direction",
                    "rejected_landing_origin",
                    "rejected_landing_directions",
                    "last_torch_y",
                    "owned_water_seals"));
            if (!legacyWithoutEdges) {
                expectedKeys.add("traversed_detour_edges");
            }
            if (!legacyBudget) {
                expectedKeys.add("budget_limit");
            }
            if (!checkpoint.keySet().equals(expectedKeys)) {
                return Optional.empty();
            }
            boolean taskOpen = strictBoolean(required(checkpoint, "task_open"));
            int restoredTargetY = Integer.parseInt(required(checkpoint, "target_y"));
            int budgetUsed = Integer.parseInt(required(checkpoint, "budget_used"));
            int lastProgressBudget = Integer.parseInt(required(checkpoint, "last_progress_budget"));
            int budgetLimit = legacyBudget
                    ? -1 : Integer.parseInt(required(checkpoint, "budget_limit"));
            int stairDirection = Integer.parseInt(required(checkpoint, "stair_direction"));
            int lateralDetours = Integer.parseInt(required(checkpoint, "lateral_detours"));
            int detourHeading = Integer.parseInt(required(checkpoint, "detour_heading"));
            BlockPos pendingOrigin = decodeOptionalPos(required(checkpoint, "pending_landing_origin"));
            BlockPos pendingTarget = decodeOptionalPos(required(checkpoint, "pending_landing_target"));
            int pendingDirection = Integer.parseInt(required(checkpoint, "pending_landing_direction"));
            BlockPos rejectedOrigin = decodeOptionalPos(required(checkpoint, "rejected_landing_origin"));
            int rejectedDirections = Integer.parseInt(required(checkpoint, "rejected_landing_directions"));
            int lastTorchY = Integer.parseInt(required(checkpoint, "last_torch_y"));
            Set<DetourEdge> traversedEdges = legacyWithoutEdges
                    ? Set.of()
                    : decodeDetourEdges(required(checkpoint, "traversed_detour_edges"));
            String encoded = required(checkpoint, "owned_water_seals");
            Map<BlockPos, Block> seals = new LinkedHashMap<>();
            if (!encoded.isBlank()) {
                String[] entries = encoded.split(";", -1);
                if (entries.length > MAX_CHECKPOINTED_WATER_SEALS) {
                    return Optional.empty();
                }
                for (String entry : entries) {
                    String[] coordinates = entry.split(",", -1);
                    if (coordinates.length != 4) {
                        return Optional.empty();
                    }
                    BlockPos seal = new BlockPos(
                            Integer.parseInt(coordinates[0]),
                            Integer.parseInt(coordinates[1]),
                            Integer.parseInt(coordinates[2]));
                    Block sealBlock = Registries.BLOCK
                            .getOptionalValue(Identifier.of(coordinates[3])).orElse(null);
                    if (sealBlock == null || !MaterialPalette.isSacrificialBlock(sealBlock)
                            || sealBlock.getDefaultState().isAir()
                            || !sealBlock.getDefaultState().getFluidState().isEmpty()
                            || seals.putIfAbsent(seal, sealBlock) != null) {
                        return Optional.empty();
                    }
                }
            }
            boolean pendingShape = (pendingOrigin == null) == (pendingTarget == null)
                    && ((pendingOrigin == null && pendingDirection == -1)
                    || (pendingOrigin != null
                    && pendingDirection >= 0 && pendingDirection < HORIZONTAL.length
                    && pendingTarget.equals(pendingOrigin.offset(HORIZONTAL[pendingDirection]).down())));
            boolean rejectedShape = rejectedDirections >= 0
                    && rejectedDirections < (1 << HORIZONTAL.length)
                    && (rejectedDirections == 0 || rejectedOrigin != null);
            boolean valid = restoredTargetY > MIN_Y && restoredTargetY <= 320
                    && (legacyBudget
                    ? budgetUsed >= 0
                    && budgetUsed <= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS + 1
                    : budgetLimit >= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    && budgetLimit <= MiningMissionBudget.DESCEND_HARD_WINDOW_TICKS
                    && budgetUsed >= 0 && budgetUsed <= budgetLimit + 1)
                    && lastProgressBudget >= 0 && lastProgressBudget <= budgetUsed
                    && stairDirection >= 0 && stairDirection < HORIZONTAL.length
                    && lateralDetours >= 0 && lateralDetours <= MAX_LATERAL
                    && lateralDetours == traversedEdges.size()
                    && (!legacyWithoutEdges || lateralDetours == 0)
                    && detourHeading >= -1 && detourHeading < HORIZONTAL.length
                    && (traversedEdges.isEmpty()
                    || directionIndex(lastEdge(traversedEdges)) == detourHeading)
                    && pendingShape && rejectedShape
                    && (taskOpen || pendingOrigin == null)
                    && (lastTorchY == Integer.MAX_VALUE || lastTorchY >= -64 && lastTorchY <= 320);
            if (!valid) {
                return Optional.empty();
            }
            return Optional.of(new RestoreMetadata(
                    restoredTargetY,
                    taskOpen,
                    budgetUsed,
                    lastProgressBudget,
                    budgetLimit,
                    stairDirection,
                    lateralDetours,
                    detourHeading,
                    pendingOrigin,
                    pendingTarget,
                    pendingDirection,
                    rejectedOrigin,
                    rejectedDirections,
                    lastTorchY,
                    seals,
                    traversedEdges));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public record RestoreMetadata(int targetY,
                                  boolean transactionOpen,
                                  int budgetUsed,
                                  int lastProgressBudget,
                                  int budgetLimit,
                                  int stairDirection,
                                  int lateralDetours,
                                  int detourHeading,
                                  BlockPos pendingLandingOrigin,
                                  BlockPos pendingLandingTarget,
                                  int pendingLandingDirection,
                                  BlockPos rejectedLandingOrigin,
                                  int rejectedLandingDirections,
                                  int lastTorchY,
                                  Map<BlockPos, Block> ownedWaterSeals,
                                  Set<DetourEdge> traversedDetourEdges) {
        public RestoreMetadata {
            pendingLandingOrigin = pendingLandingOrigin == null
                    ? null : pendingLandingOrigin.toImmutable();
            pendingLandingTarget = pendingLandingTarget == null
                    ? null : pendingLandingTarget.toImmutable();
            rejectedLandingOrigin = rejectedLandingOrigin == null
                    ? null : rejectedLandingOrigin.toImmutable();
            ownedWaterSeals = ownedWaterSeals == null ? Map.of() : Map.copyOf(ownedWaterSeals);
            traversedDetourEdges = traversedDetourEdges == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(traversedDetourEdges));
        }
    }

    public record DetourEdge(BlockPos origin, BlockPos target) {
        public DetourEdge {
            if (origin == null || target == null) {
                throw new IllegalArgumentException("detour edge positions are required");
            }
            origin = origin.toImmutable();
            target = target.toImmutable();
        }
    }

    @Override
    public String name() {
        return "descend";
    }

    @Override
    public String describe() {
        return "Descend to Y=" + targetY;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.5D;
    }

    @Override
    public boolean isWaiting() {
        // 下挖期 bot 站着挖,位置基本不变;视为 waiting 让 StuckWatcher 不误判,由本任务 NO_PROGRESS_LIMIT 看门狗兜底。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (restoredCheckpoint == null) {
            budgetOffset = 0;
            budgetLimit = budgetLimitFor(bot.getBlockPos().getY(), targetY);
            lastProgressTick = 0;
            lateralDetours = 0;
            detourHeadingIndex = -1;
            traversedDetourEdges.clear();
        } else {
            budgetOffset = restoredCheckpoint.budgetUsed();
            // Schema 2/3 had a fixed 4,800-tick clock. Promote a live legacy checkpoint once from
            // its factual restart height, but never resurrect an already exhausted old terminal.
            budgetLimit = restoredCheckpoint.budgetLimit()
                    >= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    ? restoredCheckpoint.budgetLimit()
                    : budgetOffset >= MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    ? MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS
                    : migratedLegacyBudgetLimit(
                            budgetOffset, bot.getBlockPos().getY(), targetY);
            lastProgressTick = restoredCheckpoint.lastProgressBudget();
            stairDirIndex = restoredCheckpoint.stairDirection();
            lateralDetours = restoredCheckpoint.lateralDetours();
            detourHeadingIndex = restoredCheckpoint.detourHeading();
            pendingLandingOrigin = restoredCheckpoint.pendingLandingOrigin();
            pendingLandingTarget = restoredCheckpoint.pendingLandingTarget();
            pendingLandingDirection = restoredCheckpoint.pendingLandingDirection();
            rejectedLandingOrigin = restoredCheckpoint.rejectedLandingOrigin();
            rejectedLandingDirections = restoredCheckpoint.rejectedLandingDirections();
            lastTorchY = restoredCheckpoint.lastTorchY();
            ownedWaterSeals.putAll(restoredCheckpoint.ownedWaterSeals());
            traversedDetourEdges.addAll(restoredCheckpoint.traversedDetourEdges());
            started = budgetOffset > 0;
            committed = !restoredCheckpoint.transactionOpen();

            if (committed) {
                complete();
                return;
            }

            // NavSafetyNet's transient controller is not part of the Mission checkpoint. If the
            // server stopped after descendInto but before the landing was accepted, an entity back
            // at the origin cannot safely retry that same edge. Preserve the physical debt by
            // rejecting the direction; a bot already at the target is validated by onTick.
            BlockPos feet = bot.getBlockPos();
            if (pendingLandingOrigin != null && feet.equals(pendingLandingOrigin)) {
                int interruptedDirection = pendingLandingDirection;
                pendingLandingOrigin = null;
                pendingLandingTarget = null;
                pendingLandingDirection = -1;
                rejectLandingDirection(feet, interruptedDirection);
            }
        }
        // 带铁套加成:下潜进危险深层前主动穿上背包里最好的甲(钻石计划已在 preamble 备了头胸甲)。
        // 深潜死因多是生存(岩浆/怪/低血),铁甲直接减伤;不等战斗触发才穿(被动伤害也护)。
        io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot);
        initializeSafeLandingHistory(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        blockedBodyRecoveryTarget = null;
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        // A safety task can interrupt in the same server tick that Descend physically reached a
        // dry landing. Accept that factual landing before shelter/combat is allowed to move the
        // bot away; otherwise the resumed task compares the new safety position with a stale
        // origin/target pair and reports descend_landing_pose_drift.
        settlePendingLandingAtCurrentPose(bot);
        // Safety work may seal the currently open stair. Do not retain an in-flight BlockMiner
        // cursor that would immediately reopen that wall when the descent resumes.
        miner.cancel(bot);
        blockedBodyRecoveryTarget = null;
        bot.getActionPack().stopAll();
    }

    void avoidCurrentDescentDirection(AIPlayerEntity bot, BlockPos threatPos) {
        // DangerWatcher calls this immediately before pauseFor(). Settle the landing first, then
        // record the threat rejection against the new factual origin. onPause() will subsequently
        // no-op and therefore preserve this newly recorded rejection.
        settlePendingLandingAtCurrentPose(bot);
        BlockPos origin = bot.getBlockPos();
        miner.cancel(bot);
        rejectLandingDirection(origin, stairDirIndex);
        BotLog.danger(bot, "descend_threat_direction_rejected",
                "from", origin.toShortString(),
                "direction", HORIZONTAL[stairDirIndex].asString(),
                "threat", threatPos == null ? "unknown" : threatPos.toShortString());
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("descend_invalid_checkpoint");
            return;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        // Vanilla falling-block updates run after task decisions. A stair that was clear when the
        // bot entered it can therefore become occupied before the next task tick. Resolve the
        // current collision first; continuing to mine the next stair leaves the bot suffocating,
        // and eating cannot remove the block around its head.
        if (recoverBlockedBody(bot, world, feet)) {
            return;
        }
        rememberSafeLanding(world, feet);
        if (totalBudget() > budgetLimit) {
            fail("descend_timeout at_y=" + bot.getBlockPos().getY());
            return;
        }
        if (feet.getY() < targetY) {
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            pendingLandingOrigin = null;
            pendingLandingTarget = null;
            pendingLandingDirection = -1;
            fail("descend_overshoot_unrecoverable target_y=" + targetY + " at_y=" + feet.getY());
            BotLog.danger(bot, "descend_overshoot_unrecoverable",
                    "target_y", targetY, "at_y", feet.getY(), "at", feet.toShortString());
            return;
        }
        if (handleRejectedLanding(bot, world, feet)) {
            return;
        }
        // A water expedition can hand Descend a dry shoreline stance whose four cardinal lower
        // landings are all water or unsupported even though an immediately adjacent diagonal
        // stance has a normal safe stair. Before the first mutation only, inspect that bounded
        // 3x3 neighborhood and take one ordinary physical step. This is deliberately not a
        // general path search: an already observed safe cardinal stair keeps priority, while an
        // occluded support is never read or promoted to safe. Both diagonal corner columns and
        // the complete destination/stair envelope must be observable and passable (the same
        // no-corner-clipping invariant as A*). If that positive proof is unavailable, retain the
        // existing fail-closed descent behavior.
        if (tryFreshEntryRelocation(bot, world, feet)) {
            return;
        }
        // 含水层的水会在破开相邻石块后侧向/从顶上灌进刚挖出的台阶。只检查下一格是否
        // 已经是水不够：检查时是石头，破坏完成后才变成流动水。和 DigDownTask 一样，
        // 每 tick 用背包方块逐格封住脚位/头位周围的水，先恢复干燥工作泡再继续下潜。
        if (sealLateralWater(bot, world)) {
            return;
        }
        // 到达目标层也必须交付一个干燥工作面。旧逻辑在水下 Y=16 直接 complete，紧接的
        // OreDig 看不到可工作格并在 200 tick 后错误 replan 到矿底砍树。
        if (feet.getY() == targetY) {
            // Entity water flags lag a server tick behind fluid placement/removal. Read the
            // authoritative cells as well and require actual footing before handing the face to
            // OreDig; otherwise a just-sealed flow can still be present for one fluid tick and
            // Descend reports success from inside it.
            Standability.clearCache();
            boolean wet = bot.isSubmergedInWater()
                    || bot.isTouchingWater()
                    || world.getFluidState(feet).isIn(FluidTags.WATER)
                    || world.getFluidState(feet.up()).isIn(FluidTags.WATER);
            if (wet || !Standability.isStandable(world, feet)) {
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                lastProgressTick = totalBudget();
                return;
            }
            miner.cancel(bot);
            committed = true;
            complete();
            return;
        }
        maybePlaceTorch(bot, world, feet); // P1:下潜途中定距点火把,深井不再全黑刷怪(实测下潜 Y-58 全程 light=0 被骷髅围杀)
        BlockPos below = feet.down();
        if (below.getY() <= MIN_Y) {
            fail("descend_reached_min_y");
            return;
        }
        // 卡住太久(挖不动/被挡)→ 先横移到相邻列绕过,四面不通才失败。
        if (totalBudget() - lastProgressTick > NO_PROGRESS_LIMIT) {
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = totalBudget();
                return;
            }
            miner.cancel(bot);
            fail("descend_no_progress at_y=" + feet.getY());
            return;
        }

        // 推进当前挖掘。
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return;
        }
        if (status == BlockMiner.Status.DONE) {
            lastProgressTick = totalBudget();
        }

        // 台阶式斜向下挖(拟人 + 安全):挖"下一级台阶"(斜前下方),先暴露前方——下一级或其踏面是水/岩浆
        // 就换方向绕,绝不直挖脚下(避免一镐捅穿到下方的水/岩浆)。像挖楼梯一样一级一级斜下。
        ensureRejectedLandingOrigin(feet);
        if ((rejectedLandingDirections & 1 << stairDirIndex) != 0) {
            if (rotateStair(world, feet)) {
                return;
            }
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = totalBudget();
                return;
            }
            fail("descend_no_safe_landing at_y=" + (feet.getY() - 1));
            return;
        }
        Direction dir = HORIZONTAL[stairDirIndex];
        BlockPos ahead = feet.offset(dir);   // 下一级头位 (x+d, y)
        BlockPos next = ahead.down();         // 下一级站位 (x+d, y-1)
        if (containsOwnedWaterSeal(world, ahead, ahead.up(), next)
                || isLava(world, next) || isLava(world, next.down()) || isLava(world, ahead)
                || isWater(world, next) || isWater(world, next.down())
                || !hasSafeSupport(world, next)) {
            rejectLandingDirection(feet, stairDirIndex);
            if (rotateStair(world, feet)) {
                return; // 换了个不挨水/岩浆的斜下方向
            }
            // 四个斜下方向都被水/岩浆挡 → 退回横移绕(卡死兜底)。
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = totalBudget();
                return;
            }
            miner.cancel(bot);
            fail("descend_no_safe_landing at_y=" + next.getY());
            return;
        }
        // 清出下一级身位:ahead(前方头位,可见先挖) + ahead.up()(前上,头顶净空) + next(脚位)。
        // 关键补挖 ahead.up()——只清 next+ahead 的旧台阶每列仅 2 格(Y-1,Y),玩家从上一级走下来时头会撞到
        // 前方 Y+1 的实心顶,对角下台阶只剩 1 格可走高、正常玩家(1.8高)钻不进(实测下潜矿道人过不去)。
        // 补 ahead.up() 后下潜巷道沿对角线真正 2 格净空可通行。firstSolid3 跳流体防溃浆。
        BlockPos solid = firstSolid(world, ahead, ahead.up(), next);
        if (solid != null) {
            miner.begin(bot, solid);
            miner.tick(bot);
            markStarted(bot, feet);
            return;
        }
        // 身位已通 → 斜下踏到下一级台阶(bot 无被动重力,仍需主动移一格;斜向移近似踏下一级楼梯)。
        BlockPos origin = feet.toImmutable();
        boolean descended = bot.getActionPack().descendInto(next);
        if (descended && bot.getBlockPos().equals(next)) {
            pendingLandingOrigin = origin;
            pendingLandingTarget = next.toImmutable();
            pendingLandingDirection = stairDirIndex;
        } else {
            rejectLandingDirection(origin, stairDirIndex);
            rotateStair(world, origin);
            BotLog.action(bot, "descend_landing_rejected",
                    "from", origin.toShortString(), "target", next.toShortString());
        }
        markStarted(bot, feet);
    }

    /**
     * Clears or physically backs out of a body cell that was reoccupied after a verified landing.
     * This is deliberately task-local: Descend owns the adjacent factual stair history and can
     * reject the collapsed edge, while NavSafetyNet has no such history and its long-range
     * suffocation snap is correctly denied by strict_survival.
     */
    private boolean recoverBlockedBody(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        BlockPos blocked = firstBodyCollision(world, feet);
        if (blocked == null) {
            if (blockedBodyRecoveryTarget == null) {
                return false;
            }
            BlockPos recoveryTarget = blockedBodyRecoveryTarget;
            BlockState recoveryState = world.getBlockState(recoveryTarget);
            if (recoveryState.getCollisionShape(world, recoveryTarget).isEmpty()) {
                // The ordinary BlockMiner broke the obstruction (or it resumed falling). Cancel
                // its now-stale cursor and yield one tick so another falling block must also pass
                // this preflight before normal descent can resume.
                miner.cancel(bot);
                blockedBodyRecoveryTarget = null;
                lastProgressTick = totalBudget();
                return true;
            }
            // A collapsed same-level detour is first observed while it occupies the bot's body.
            // After the exact physical retreat, keep clearing that now-adjacent factual block
            // before general detour search is allowed to promote it into an upper landing. This
            // does not authorize a hidden support read: only the already observed body cell is
            // mined, and ordinary landing proof still runs after it becomes air.
            if (!isAdjacentSameLevel(feet, recoveryTarget)
                    || !canObservePosition(bot, recoveryTarget)
                    || recoveryState.getFluidState().isIn(FluidTags.LAVA)) {
                miner.cancel(bot);
                blockedBodyRecoveryTarget = null;
                return true;
            }
            if (miner.target() == null || !miner.target().equals(recoveryTarget)) {
                miner.begin(bot, recoveryTarget);
            }
            BlockMiner.Status recoveryStatus = miner.tick(bot);
            markStarted(bot, feet);
            lastProgressTick = totalBudget();
            if (recoveryStatus == BlockMiner.Status.FAILED) {
                blockedBodyRecoveryTarget = null;
                fail("descend_blocked_body_clear_failed at=" + feet.toShortString()
                        + " blocked=" + recoveryTarget.toShortString()
                        + " reason=" + miner.failureReason());
            }
            return true;
        }

        BlockState obstruction = world.getBlockState(blocked);
        BlockPos retreat = findRecentPhysicalRetreat(world, feet);
        if (retreat != null && physicallyRetreat(bot, feet, retreat)) {
            markStarted(bot, feet);
            miner.cancel(bot);
            boolean rolledBackDetour = rollbackCollapsedDetourEdge(bot, retreat, feet);
            blockedBodyRecoveryTarget = rolledBackDetour ? blocked.toImmutable() : null;
            if (!rolledBackDetour) {
                rejectCollapsedEdge(retreat, feet);
            }
            pendingLandingOrigin = null;
            pendingLandingTarget = null;
            pendingLandingDirection = -1;
            latestSafeLanding = retreat.toImmutable();
            previousSafeLanding = null;
            lastProgressTick = totalBudget();
            BotLog.danger(bot, "descend_blocked_body_retreat",
                    "from", feet.toShortString(),
                    "to", retreat.toShortString(),
                    "blocked", blocked.toShortString(),
                    "block", Registries.BLOCK.getId(obstruction.getBlock()),
                    "detour_rolled_back", rolledBackDetour);
            return true;
        }

        // No still-valid adjacent landing remains (for example after a restart). Mine the visible
        // block occupying the bot's own body with the normal survival BlockMiner. Prefer the head
        // cell in firstBodyCollision so breathing is restored before any lower obstruction.
        if (!blocked.equals(blockedBodyRecoveryTarget)
                || miner.target() == null || !miner.target().equals(blocked)) {
            miner.begin(bot, blocked);
            blockedBodyRecoveryTarget = blocked.toImmutable();
            BotLog.danger(bot, "descend_blocked_body_clear",
                    "at", feet.toShortString(),
                    "blocked", blocked.toShortString(),
                    "block", Registries.BLOCK.getId(obstruction.getBlock()));
        }
        BlockMiner.Status status = miner.tick(bot);
        markStarted(bot, feet);
        if (status == BlockMiner.Status.FAILED) {
            blockedBodyRecoveryTarget = null;
            fail("descend_blocked_body_clear_failed at=" + feet.toShortString()
                    + " blocked=" + blocked.toShortString()
                    + " reason=" + miner.failureReason());
        }
        // Treat every recovery tick as useful bounded work. The dedicated BlockMiner timeout still
        // rejects an unbreakable obstruction; the ordinary no-progress detour must not interrupt a
        // live escape attempt first.
        lastProgressTick = totalBudget();
        return true;
    }

    private void initializeSafeLandingHistory(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        if (!isDryStandable(world, feet)) {
            return;
        }
        latestSafeLanding = feet.toImmutable();
        if (pendingLandingOrigin != null && feet.equals(pendingLandingTarget)) {
            previousSafeLanding = pendingLandingOrigin.toImmutable();
        }
    }

    private void rememberSafeLanding(ServerWorld world, BlockPos feet) {
        if (!isDryStandable(world, feet)) {
            return;
        }
        BlockPos immutable = feet.toImmutable();
        if (latestSafeLanding == null) {
            latestSafeLanding = immutable;
        } else if (!latestSafeLanding.equals(immutable)) {
            previousSafeLanding = latestSafeLanding;
            latestSafeLanding = immutable;
        }
    }

    private BlockPos findRecentPhysicalRetreat(ServerWorld world, BlockPos feet) {
        BlockPos detourOrigin = lastDetourOriginFor(feet);
        for (BlockPos candidate : new BlockPos[]{
                detourOrigin, pendingLandingOrigin, previousSafeLanding, latestSafeLanding}) {
            if (candidate == null || candidate.equals(feet)
                    || !isPhysicalRetreatStep(feet, candidate)) {
                continue;
            }
            Standability.clearCache();
            if (isDryStandable(world, candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static boolean physicallyRetreat(AIPlayerEntity bot, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        return dy == 1
                ? io.github.zoyluo.aibot.mode.FakePlayerMotion.jumpTo(
                        bot, to, "descend_blocked_body_retreat")
                : io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, to, "descend_blocked_body_retreat");
    }

    private void rejectCollapsedEdge(BlockPos retreat, BlockPos buried) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            if (buried.equals(retreat.offset(HORIZONTAL[i]).down())) {
                stairDirIndex = i;
                rejectLandingDirection(retreat, i);
                return;
            }
        }
        clearRejectedLandingDirections();
    }

    /**
     * A lateral edge is provisional until its target survives the following world update. Sand or
     * gravel can reoccupy that target after the task tick that issued the physical step. When the
     * bot can retreat along the exact last edge, roll that uncommitted traversal back instead of
     * permanently spending graph budget on a landing that never became stable.
     */
    private boolean rollbackCollapsedDetourEdge(AIPlayerEntity bot,
                                                BlockPos retreat,
                                                BlockPos buried) {
        if (traversedDetourEdges.isEmpty()) {
            return false;
        }
        DetourEdge edge = lastEdge(traversedDetourEdges);
        if (!edge.origin().equals(retreat) || !edge.target().equals(buried)) {
            return false;
        }
        if (!traversedDetourEdges.remove(edge)) {
            throw new IllegalStateException("last detour edge disappeared during rollback");
        }
        lateralDetours = traversedDetourEdges.size();
        detourHeadingIndex = traversedDetourEdges.isEmpty()
                ? -1 : directionIndex(lastEdge(traversedDetourEdges));
        BotLog.action(bot, "descend_detour_edge_rolled_back",
                "from", buried.toShortString(),
                "to", retreat.toShortString(),
                "used", lateralDetours,
                "budget", MAX_LATERAL);
        return true;
    }

    private BlockPos lastDetourOriginFor(BlockPos target) {
        if (traversedDetourEdges.isEmpty()) {
            return null;
        }
        DetourEdge edge = lastEdge(traversedDetourEdges);
        return edge.target().equals(target) ? edge.origin().toImmutable() : null;
    }

    private static boolean isPhysicalRetreatStep(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = to.getY() - from.getY();
        int dz = Math.abs(to.getZ() - from.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        return dx <= 1 && dz <= 1 && dy >= -1 && dy <= 1
                && changedAxes >= 1 && changedAxes <= 2
                && (dy == 0 || dx + dz <= 1);
    }

    private static boolean isAdjacentSameLevel(BlockPos origin, BlockPos target) {
        return origin.getY() == target.getY()
                && Math.abs(origin.getX() - target.getX())
                + Math.abs(origin.getZ() - target.getZ()) == 1;
    }

    private static boolean isDryStandable(ServerWorld world, BlockPos feet) {
        Standability.clearCache();
        return world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.up()).isEmpty()
                && Standability.isStandable(world, feet);
    }

    private static BlockPos firstBodyCollision(ServerWorld world, BlockPos feet) {
        BlockPos head = feet.up();
        if (!world.getBlockState(head).getCollisionShape(world, head).isEmpty()) {
            return head.toImmutable();
        }
        if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) {
            return feet.toImmutable();
        }
        return null;
    }

    private boolean handleRejectedLanding(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        if (pendingLandingOrigin == null || pendingLandingTarget == null) {
            return false;
        }
        if (feet.equals(pendingLandingOrigin)) {
            BlockPos rejected = pendingLandingTarget;
            int rejectedDirection = pendingLandingDirection;
            pendingLandingOrigin = null;
            pendingLandingTarget = null;
            pendingLandingDirection = -1;
            miner.cancel(bot);
            rejectLandingDirection(feet, rejectedDirection);
            boolean rotated = rotateStair(world, feet);
            lastProgressTick = totalBudget();
            BotLog.action(bot, "descend_wet_landing_rejected",
                    "from", feet.toShortString(),
                    "rejected", rejected.toShortString(),
                    "rotated", rotated);
            if (!rotated) {
                boolean detouring = lateralDetours < MAX_LATERAL
                        && tryLateralDetour(bot, world, feet);
                if (!detouring) {
                    fail("descend_no_safe_landing at_y=" + rejected.getY());
                }
            }
            // Yield once so the safety controller observes the dry origin and clears ownership.
            return true;
        }
        if (!feet.equals(pendingLandingOrigin) && !feet.equals(pendingLandingTarget)) {
            BlockPos origin = pendingLandingOrigin;
            BlockPos target = pendingLandingTarget;
            pendingLandingOrigin = null;
            pendingLandingTarget = null;
            pendingLandingDirection = -1;
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            fail("descend_landing_pose_drift origin=" + origin.toShortString()
                    + " target=" + target.toShortString() + " at=" + feet.toShortString());
            return true;
        }
        if (feet.equals(pendingLandingTarget)) {
            if (!settlePendingLandingAtCurrentPose(bot)) {
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                return true;
            }
        }
        return false;
    }

    /**
     * Commits an unresolved physical landing only when the bot is still standing dry at the
     * recorded target. This is shared by the normal next-tick acknowledgement and by safety-task
     * interruption, whose shelter/combat work may legitimately relocate the bot before Descend
     * resumes.
     */
    private boolean settlePendingLandingAtCurrentPose(AIPlayerEntity bot) {
        if (pendingLandingTarget == null || !bot.getBlockPos().equals(pendingLandingTarget)) {
            return false;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        Standability.clearCache();
        boolean wet = bot.isSubmergedInWater()
                || bot.isTouchingWater()
                || world.getFluidState(feet).isIn(FluidTags.WATER)
                || world.getFluidState(feet.up()).isIn(FluidTags.WATER);
        if (NavSafetyNet.INSTANCE.isWaterRescueActive(bot) || wet
                || !Standability.isStandable(world, feet)) {
            return false;
        }
        // MAX_LATERAL 是单个障碍层的绕行预算，不是整段深潜的全局预算。
        // 只有落到本轮绕行触及的最低层以下，才算真正绕过障碍。上退一层后重新
        // 落回同一最低层不构成进展：若在这里清账，A->B->A->upper->A 会每几 tick
        // 重新获得完整预算并永久循环。湿落点被退回原位时同样不能白得预算。
        boolean advancedBelowDetourFloor = traversedDetourEdges.isEmpty()
                || feet.getY() < lowestTraversedDetourY();
        if (advancedBelowDetourFloor) {
            lateralDetours = 0;
            detourHeadingIndex = pendingLandingDirection;
            traversedDetourEdges.clear();
        } else {
            BotLog.action(bot, "descend_detour_floor_revisited",
                    "at", feet.toShortString(),
                    "floor_y", lowestTraversedDetourY(),
                    "used", lateralDetours,
                    "budget", MAX_LATERAL);
        }
        pendingLandingOrigin = null;
        pendingLandingTarget = null;
        pendingLandingDirection = -1;
        clearRejectedLandingDirections();
        rememberSafeLanding(world, feet);
        lastProgressTick = totalBudget();
        return true;
    }

    private int lowestTraversedDetourY() {
        int lowest = Integer.MAX_VALUE;
        for (DetourEdge edge : traversedDetourEdges) {
            lowest = Math.min(lowest, Math.min(edge.origin().getY(), edge.target().getY()));
        }
        return lowest;
    }

    private void markStarted(AIPlayerEntity bot, BlockPos feet) {
        lastProgressTick = totalBudget();
        if (!started) {
            started = true;
            BotLog.action(bot, "descend_started", "target_y", targetY, "from_y", feet.getY());
        }
    }

    /**
     * Takes at most one same-level diagonal step before the descent transaction starts.
     *
     * <p>The relocation is intentionally derived from current observable geometry rather than a
     * remembered route or hidden scan. Once it succeeds {@link #started} is latched, so a restart
     * resumes from the factual destination and cannot refund or replay this preflight.</p>
     */
    private boolean tryFreshEntryRelocation(AIPlayerEntity bot,
                                            ServerWorld world,
                                            BlockPos feet) {
        if (started || feet.getY() <= targetY || !isDryStandable(world, feet)
                || hasObservedSafeStairDirection(bot, world, feet)) {
            return false;
        }
        Direction[][] diagonals = {
                {Direction.NORTH, Direction.EAST},
                {Direction.NORTH, Direction.WEST},
                {Direction.SOUTH, Direction.EAST},
                {Direction.SOUTH, Direction.WEST}
        };
        for (Direction[] pair : diagonals) {
            BlockPos firstCorner = feet.offset(pair[0]);
            BlockPos secondCorner = feet.offset(pair[1]);
            BlockPos candidate = firstCorner.offset(pair[1]);
            if (!isObservedDryPassableColumn(bot, world, firstCorner)
                    || !isObservedDryPassableColumn(bot, world, secondCorner)
                    || !isObservedDryStandable(bot, world, candidate)) {
                continue;
            }
            int safeDirection = observedSafeStairDirection(bot, world, candidate);
            if (safeDirection < 0) {
                continue;
            }
            miner.cancel(bot);
            if (!FakePlayerMotion.stepToStandable(bot, candidate,
                    "descend_fresh_entry_relocation")) {
                continue;
            }
            stairDirIndex = safeDirection;
            clearRejectedLandingDirections();
            markStarted(bot, feet);
            BotLog.action(bot, "descend_entry_relocated",
                    "from", feet.toShortString(),
                    "to", candidate.toShortString(),
                    "stair_direction", HORIZONTAL[safeDirection].asString());
            return true;
        }
        return false;
    }

    private boolean hasObservedSafeStairDirection(AIPlayerEntity bot,
                                                  ServerWorld world,
                                                  BlockPos feet) {
        for (int direction = 0; direction < HORIZONTAL.length; direction++) {
            if (!canObserveStairEnvelope(bot, feet, direction)) {
                continue;
            }
            if (isSafeStairDirection(world, feet, direction)) {
                return true;
            }
        }
        return false;
    }

    private int observedSafeStairDirection(AIPlayerEntity bot,
                                           ServerWorld world,
                                           BlockPos feet) {
        for (int direction = 0; direction < HORIZONTAL.length; direction++) {
            if (!canObserveStairEnvelope(bot, feet, direction)) {
                continue;
            }
            if (isSafeStairDirection(world, feet, direction)) {
                return direction;
            }
        }
        return -1;
    }

    private static boolean canObserveStairEnvelope(AIPlayerEntity bot,
                                                   BlockPos feet,
                                                   int direction) {
        BlockPos ahead = feet.offset(HORIZONTAL[direction]);
        BlockPos landing = ahead.down();
        return canObservePosition(bot, ahead)
                && canObservePosition(bot, ahead.up())
                && canObservePosition(bot, landing)
                && canObservePosition(bot, landing.down());
    }

    private boolean isSafeStairDirection(ServerWorld world,
                                         BlockPos feet,
                                         int direction) {
        BlockPos ahead = feet.offset(HORIZONTAL[direction]);
        BlockPos landing = ahead.down();
        return !containsOwnedWaterSeal(world, ahead, ahead.up(), landing)
                && !isLava(world, landing)
                && !isLava(world, landing.down())
                && !isLava(world, ahead)
                && !isWater(world, landing)
                && !isWater(world, landing.down())
                && hasSafeSupport(world, landing);
    }

    private static boolean isObservedDryPassableColumn(AIPlayerEntity bot,
                                                       ServerWorld world,
                                                       BlockPos feet) {
        if (!canObservePosition(bot, feet) || !canObservePosition(bot, feet.up())) {
            return false;
        }
        return world.getFluidState(feet).isEmpty()
                && world.getFluidState(feet.up()).isEmpty()
                && world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
    }

    private static boolean isObservedDryStandable(AIPlayerEntity bot,
                                                   ServerWorld world,
                                                   BlockPos feet) {
        return isObservedDryPassableColumn(bot, world, feet)
                && canObservePosition(bot, feet.down())
                && isDryStandable(world, feet);
    }

    private static boolean canObservePosition(AIPlayerEntity bot, BlockPos pos) {
        return ObservableWorldQuery.canObserveCell(bot, pos)
                || ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos);
    }

    // 竖井被岩浆(或卡点)挡住时,横移一格到"无岩浆、可下挖"的相邻列,绕过去继续下挖。
    // 挖开通往侧列的块(挨岩浆的块不挖,防溃浆淹没);通了就通过相邻物理 step/jump 过去。
    // 四面都不可行 → 返回 false,由调用方判失败(交规避层"困死撤离"兜底)。
    private boolean tryLateralDetour(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        // 先在当前层找无岩浆侧列绕;当前层四面都被岩浆封死(大岩浆湖,实测 at_y=50:脚下及四面 side.down 皆岩浆
        // → 当前层无解 → 整步失败)时,上退一层在岩浆湖顶上方绕——通常能爬到岩浆湖边缘外继续下挖。
        int[] directionOrder = detourDirectionOrder();
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos base = feet.up(dy);
            for (int directionIndex : directionOrder) {
                Direction dir = HORIZONTAL[directionIndex];
                BlockPos side = base.offset(dir);
                BlockPos support = side.down();
                DetourEdge edge = new DetourEdge(feet, side);
                if (traversedDetourEdges.contains(edge)) {
                    continue;
                }
                // Strict-survival detours may only inspect the exact body/support envelope the bot
                // can currently see.  In particular, never read a hidden floor merely because a
                // horizontal fallback is otherwise out of graph edges.
                if (!canObservePosition(bot, side)
                        || !canObservePosition(bot, side.up())
                        || !canObservePosition(bot, support)) {
                    continue;
                }
                // A seal in the body column is an obstruction Descend must never mine through.
                // A seal used only as the landing's solid floor is safe to stand on and is not a
                // placement target; rejecting it here would erase the bounded upper escape route.
                if (containsOwnedWaterSeal(world, side, side.up())) {
                    continue;
                }
                if (isLava(world, side) || isLava(world, side.up()) || isLava(world, support)) {
                    continue; // 别往岩浆方向横移
                }
                // Verify the factual landing before clearing its body column. A solid side block
                // can hide an unsupported floor below it; mining that block first both discovers
                // the bad landing too late and may destroy the only support for the dry upper
                // landing that Descend must use to retreat around an aquifer.
                if (!hasSafeSupport(world, side)) {
                    // An upper retreat can require a two-block foundation transaction and remains
                    // fail-closed here.  The seed-3000 aquifer failure is the narrower same-level
                    // isolated-pillar case: place one real, visible floor block, then yield.  On
                    // the next tick (or after restart) the ordinary hasSafeSupport branch above is
                    // the receipt; no checkpoint-local authority can refund or duplicate the item.
                    if (dy == 0 && tryPlaceDetourSupport(
                            bot, world, feet, side, support, directionIndex)) {
                        return true;
                    }
                    continue;
                }
                BlockPos solid = firstSolid(world, side, side.up());
                if (solid != null) {
                    if (adjacentLava(world, solid)) {
                        continue; // 要挖的块挨着岩浆,挖了会溃浆淹没,换方向
                    }
                    if (miner.target() == null || !miner.target().equals(solid)) {
                        miner.begin(bot, solid);
                    }
                    miner.tick(bot);
                    markStarted(bot, feet);
                    return true; // 正在挖通往侧列的路(本 tick 算进展)
                }
                // 侧列已通(脚位+头位皆空)→ 相邻物理移动(可能上退一层),下个 tick 在新列继续下挖。
                miner.cancel(bot);
                boolean moved = dy == 0
                        ? io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                                bot, side, "descend_lava_detour")
                        : io.github.zoyluo.aibot.mode.FakePlayerMotion.jumpTo(
                                bot, side, "descend_lava_detour");
                if (!moved) {
                    continue;
                }
                if (!traversedDetourEdges.add(edge)) {
                    throw new IllegalStateException("detour edge replay escaped preflight");
                }
                markStarted(bot, feet);
                lateralDetours++;
                detourHeadingIndex = directionIndex;
                if (dy == 1) {
                    // The upper landing is a bounded retreat, not a fresh descent origin. Keep the
                    // exact reverse stair rejected across the next tick and checkpoint restart;
                    // otherwise Descend immediately drops into the lower cell it just escaped and
                    // clears the detour history when that landing settles.
                    rejectLandingDirection(
                            side, (directionIndex + HORIZONTAL.length / 2) % HORIZONTAL.length);
                }
                BotLog.action(bot, "descend_lava_detour",
                        "dir", dir.asString(),
                        "at_y", side.getY(),
                        "up", dy,
                        "used", lateralDetours,
                        "budget", MAX_LATERAL);
                return true;
            }
        }
        return false;
    }

    /**
     * Builds one same-level floor edge using an ordinary strict-survival block interaction.
     * Successful placement always yields; movement is forbidden until a later tick observes the
     * resulting collision shape through {@link #hasSafeSupport(ServerWorld, BlockPos)}.
     */
    private boolean tryPlaceDetourSupport(AIPlayerEntity bot,
                                          ServerWorld world,
                                          BlockPos origin,
                                          BlockPos landing,
                                          BlockPos support,
                                          int directionIndex) {
        if (!origin.equals(bot.getBlockPos())
                || landing.getY() != origin.getY()
                || !landing.equals(origin.offset(HORIZONTAL[directionIndex]))
                || containsOwnedWaterSeal(world, landing, landing.up(), support)) {
            return false;
        }

        BlockState feetState = world.getBlockState(landing);
        BlockState headState = world.getBlockState(landing.up());
        BlockState supportState = world.getBlockState(support);
        if (!feetState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()
                || !supportState.getFluidState().isEmpty()
                || !feetState.getCollisionShape(world, landing).isEmpty()
                || !headState.getCollisionShape(world, landing.up()).isEmpty()
                || !supportState.getCollisionShape(world, support).isEmpty()
                || !supportState.isReplaceable()
                || Standability.isDangerous(feetState)
                || Standability.isDangerous(headState)
                || Standability.isDangerous(supportState)
                || hasObservedAdjacentLava(bot, world, landing, landing.up(), support)) {
            return false;
        }

        OptionalInt blockSlot = MaterialPalette.pickPathSupportBlockSlot(
                bot, MiningBudget.EMERGENCY_STONE_LIKE);
        if (blockSlot.isEmpty()) {
            return false;
        }
        String item = String.valueOf(bot.getInventory().main
                .get(blockSlot.getAsInt()).getItem());
        miner.cancel(bot);
        bot.getActionPack().stopAll();
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            return false;
        }
        // The pillar's top face hides the target floor from its center. Reproduce an ordinary
        // sneak-bridge transaction: expose the support's side, click that exact visible face, and
        // always settle back on the original center before any receipt or movement decision.
        Standability.clearCache();
        if (!bot.isOnGround() && Standability.isStandable(world, origin)) {
            bot.setOnGround(true);
        }
        Direction direction = HORIZONTAL[directionIndex];
        if (!FakePlayerMotion.shiftToSupportEdge(
                bot, origin, direction, "descend_detour_support")) {
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", origin.toShortString(),
                    "landing", landing.toShortString(),
                    "support", support.toShortString(),
                    "reason", "support_edge_unreachable");
            return false;
        }
        ActionResult placed = ActionResult.failed("placement_not_attempted");
        boolean returned;
        try {
            placed = BuildAction.placeBlock(
                    bot, origin.down(), direction, Hand.MAIN_HAND);
        } finally {
            returned = FakePlayerMotion.returnToBlockCenter(
                    bot, origin, "descend_detour_support");
        }
        if (!returned) {
            fail("descend_detour_support_return_failed origin=" + origin.toShortString());
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", origin.toShortString(),
                    "landing", landing.toShortString(),
                    "support", support.toShortString(),
                    "reason", "support_edge_return_failed");
            return true;
        }
        if (placed.isFailed()) {
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", origin.toShortString(),
                    "landing", landing.toShortString(),
                    "support", support.toShortString(),
                    "reason", placed.reason());
            return false;
        }

        Standability.clearCache();
        BlockState receipt = world.getBlockState(support);
        if (!receipt.getFluidState().isEmpty()
                || receipt.getCollisionShape(world, support).isEmpty()
                || Standability.isDangerous(receipt)) {
            fail("descend_no_safe_landing support_receipt_invalid="
                    + support.toShortString());
            BotLog.action(bot, "descend_detour_support_failed",
                    "origin", origin.toShortString(),
                    "landing", landing.toShortString(),
                    "support", support.toShortString(),
                    "reason", "invalid_world_receipt");
            return true;
        }

        markStarted(bot, origin);
        lastProgressTick = totalBudget();
        BotLog.action(bot, "descend_detour_support_placed",
                "origin", origin.toShortString(),
                "landing", landing.toShortString(),
                "support", support.toShortString(),
                "item", item,
                "stone_like_reserve", MiningBudget.EMERGENCY_STONE_LIKE);
        return true;
    }

    /** Reads only visible neighbours; hidden cells never become implicit lava-scan authority. */
    private static boolean hasObservedAdjacentLava(AIPlayerEntity bot,
                                                    ServerWorld world,
                                                    BlockPos... positions) {
        for (BlockPos position : positions) {
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = position.offset(direction);
                if (canObservePosition(bot, adjacent) && isLava(world, adjacent)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 沿当前航向探索障碍边缘：直行、右转、左转，最后才原路返回。
     * 固定按 NORTH/EAST/SOUTH/WEST 枚举会在两格通道中把 south 的回头路
     * 排在 west 的新出口之前，从而北南往返直到耗尽预算。
     */
    private int[] detourDirectionOrder() {
        int forward = detourHeadingIndex >= 0 ? detourHeadingIndex : stairDirIndex;
        return new int[]{
                forward,
                (forward + 1) % HORIZONTAL.length,
                (forward + HORIZONTAL.length - 1) % HORIZONTAL.length,
                (forward + 2) % HORIZONTAL.length
        };
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    /** Seals one observable water ingress cell and yields until the next tick. */
    private boolean sealLateralWater(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        for (BlockPos level : new BlockPos[]{feet, feet.up()}) {
            for (Direction direction : HORIZONTAL) {
                if (trySealWater(bot, world, level.offset(direction))) {
                    return true;
                }
            }
        }
        return trySealWater(bot, world, feet.up(2));
    }

    private boolean trySealWater(AIPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (!isWater(world, pos)) {
            return false;
        }
        if (ownedWaterSeals.size() >= MAX_CHECKPOINTED_WATER_SEALS) {
            pruneStaleWaterSeals(world);
            if (ownedWaterSeals.size() >= MAX_CHECKPOINTED_WATER_SEALS) {
                miner.cancel(bot);
                bot.getActionPack().stopAll();
                fail("descend_water_seal_checkpoint_capacity");
                return true;
            }
        }
        OptionalInt blockSlot = MaterialPalette.pickSacrificialBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
        if (BuildAction.placeBlockAt(bot, pos).isFailed()) {
            return false;
        }
        BlockState sealState = world.getBlockState(pos);
        if (sealState.isAir() || !sealState.getFluidState().isEmpty()) {
            return false;
        }
        ownedWaterSeals.put(pos.toImmutable(), sealState.getBlock());
        miner.cancel(bot);
        markStarted(bot, bot.getBlockPos());
        rejectSealedStairDirection(bot.getBlockPos(), pos);
        lastProgressTick = totalBudget();
        BotLog.action(bot, "descend_seal_water", "at", pos.toShortString());
        return true;
    }

    private void rejectSealedStairDirection(BlockPos feet, BlockPos sealed) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            BlockPos side = feet.offset(HORIZONTAL[i]);
            if (sealed.equals(side) || sealed.equals(side.up())) {
                rejectLandingDirection(feet, i);
                return;
            }
        }
    }

    // 台阶斜下:换到下一个"不挨水/岩浆"的斜下方向;四面都不行返回 false(交横移兜底)。
    private boolean rotateStair(ServerWorld world, BlockPos feet) {
        ensureRejectedLandingOrigin(feet);
        for (int i = 0; i < HORIZONTAL.length; i++) {
            stairDirIndex = (stairDirIndex + 1) % HORIZONTAL.length;
            if ((rejectedLandingDirections & 1 << stairDirIndex) != 0) {
                continue;
            }
            BlockPos ahead = feet.offset(HORIZONTAL[stairDirIndex]);
            BlockPos next = ahead.down();
            if (!containsOwnedWaterSeal(world, ahead, ahead.up(), next)
                    && !isLava(world, next) && !isLava(world, next.down()) && !isLava(world, ahead)
                    && !isWater(world, next) && !isWater(world, next.down())
                    && hasSafeSupport(world, next)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOwnedWaterSeal(ServerWorld world, BlockPos... positions) {
        for (BlockPos position : positions) {
            Block owned = ownedWaterSeals.get(position);
            if (owned == null) {
                continue;
            }
            BlockState live = world.getBlockState(position);
            // Air/fluid proves the old wall no longer exists; retaining that stale coordinate
            // would permanently blacklist a valid stair after a restart. A different solid block
            // remains protected: it may be a player repair and is never evidence that Descend owns
            // the right to mine through it.
            if (live.isAir() || !live.getFluidState().isEmpty()) {
                ownedWaterSeals.remove(position);
                continue;
            }
            if (live.isOf(owned)) {
                return true;
            }
            // A different solid is not task-owned, but it is still a deliberate wall. Keep the
            // coordinate protected so stale identity never becomes authority to mine a repair.
            return true;
        }
        return false;
    }

    private void pruneStaleWaterSeals(ServerWorld world) {
        ownedWaterSeals.entrySet().removeIf(entry -> {
            BlockState live = world.getBlockState(entry.getKey());
            return live.isAir() || !live.getFluidState().isEmpty();
        });
    }

    private void rejectLandingDirection(BlockPos origin, int direction) {
        ensureRejectedLandingOrigin(origin);
        if (direction >= 0 && direction < HORIZONTAL.length) {
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

    private static boolean hasSafeSupport(ServerWorld world, BlockPos landing) {
        BlockPos supportPos = landing.down();
        var support = world.getBlockState(supportPos);
        return support.getFluidState().isEmpty()
                && !support.getCollisionShape(world, supportPos).isEmpty()
                && !Standability.isDangerous(support);
    }

    private static boolean adjacentLava(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (isLava(world, pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    // P1 下潜照明(真实玩家下矿标准操作):每下 TORCH_EVERY 格、光照<8、有火把就在脚位插一支。
    // 治"下潜深井全程 light=0、骷髅/僵尸成群刷出来围杀"(实测 real_diamond 下潜 Y-58 全程 light=0 被 5 只骷髅围攻)。
    // 照明是增益不是前置:缺火把不阻塞下潜。
    private void maybePlaceTorch(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        if (lastTorchY != Integer.MAX_VALUE && lastTorchY - feet.getY() < TORCH_EVERY) {
            return;
        }
        if (world.getLightLevel(net.minecraft.world.LightType.BLOCK, feet) >= 8) {
            lastTorchY = feet.getY(); // 已够亮也推进基准,避免每 tick 重判
            return;
        }
        var torchSlot = InventoryAction.findItem(bot, net.minecraft.item.Items.TORCH);
        if (torchSlot.isPresent()) {
            InventoryAction.equipFromSlot(bot, torchSlot.getAsInt());
            if (!BuildAction.placeBlockAt(bot, feet).isFailed()) {
                lastTorchY = feet.getY();
                markStarted(bot, feet);
                BotLog.action(bot, "descend_torch", "pos", feet.toShortString());
            }
        }
        // Slot identity is not stable: equipping a non-hotbar/offhand torch in a full inventory
        // swaps the selected pick out of its old slot. Restore from the factual active block rather
        // than selecting the stale index; without an active break, the next miner.begin selects.
        restoreActiveMiningTool(bot, world, miner);
    }

    static void restoreActiveMiningTool(AIPlayerEntity bot,
                                        ServerWorld world,
                                        BlockMiner activeMiner) {
        if (activeMiner.target() != null) {
            ToolSelector.equipBestTool(bot, world.getBlockState(activeMiner.target()));
        }
    }

    // 3 参版:依次返回第一个"固体且非流体"的格(流体跳过,绝不挖→防溃浆/溃水)。用于下潜台阶清三格身位
    // (ahead 头位 + ahead.up 头顶净空 + next 脚位),保证下潜巷道 2 格可走高。
    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b, BlockPos c) {
        for (BlockPos p : new BlockPos[]{a, b, c}) {
            if (!world.getBlockState(p).isAir() && world.getFluidState(p).isEmpty()) {
                return p.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir() && world.getFluidState(a).isEmpty()) {
            return a.toImmutable();
        }
        if (!world.getBlockState(b).isAir() && world.getFluidState(b).isEmpty()) {
            return b.toImmutable();
        }
        return null;
    }

    @Override
    public Map<String, String> checkpoint() {
        if (invalidCheckpoint || ownedWaterSeals.size() > MAX_CHECKPOINTED_WATER_SEALS) {
            return Map.of();
        }
        ArrayList<Map.Entry<BlockPos, Block>> sorted = new ArrayList<>(ownedWaterSeals.entrySet());
        sorted.sort(Map.Entry.comparingByKey(java.util.Comparator.comparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ)));
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<BlockPos, Block> entry : sorted) {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            BlockPos seal = entry.getKey();
            encoded.append(seal.getX()).append(',')
                    .append(seal.getY()).append(',')
                    .append(seal.getZ()).append(',')
                    .append(Registries.BLOCK.getId(entry.getValue()));
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_schema", String.valueOf(CHECKPOINT_SCHEMA));
        values.put("task_open", String.valueOf(!committed));
        values.put("target_y", String.valueOf(targetY));
        values.put("budget_used", String.valueOf(totalBudget()));
        values.put("last_progress_budget", String.valueOf(lastProgressTick));
        values.put("budget_limit", String.valueOf(budgetLimit));
        values.put("stair_direction", String.valueOf(stairDirIndex));
        values.put("lateral_detours", String.valueOf(lateralDetours));
        values.put("detour_heading", String.valueOf(detourHeadingIndex));
        values.put("pending_landing_origin", encodeOptionalPos(pendingLandingOrigin));
        values.put("pending_landing_target", encodeOptionalPos(pendingLandingTarget));
        values.put("pending_landing_direction", String.valueOf(pendingLandingDirection));
        values.put("rejected_landing_origin", encodeOptionalPos(rejectedLandingOrigin));
        values.put("rejected_landing_directions", String.valueOf(rejectedLandingDirections));
        values.put("last_torch_y", String.valueOf(lastTorchY));
        values.put("owned_water_seals", encoded.toString());
        values.put("traversed_detour_edges", encodeDetourEdges(traversedDetourEdges));
        Map<String, String> result = Map.copyOf(values);
        return inspectCheckpoint(result).isPresent() ? result : Map.of();
    }

    private int totalBudget() {
        return budgetOffset + elapsed;
    }

    /**
     * Three body-column breaks per stair level at conservative Deepslate/stone-pick speed, plus
     * movement and bounded safety-detour headroom. The persisted result is the task's monotonic
     * hard window; restart must never recompute a fresh clock from the remaining distance.
     */
    public static int budgetLimitFor(int fromY, int targetY) {
        return MiningMissionBudget.descendTaskWindowTicks(fromY, targetY);
    }

    private static int migratedLegacyBudgetLimit(int budgetUsed, int fromY, int targetY) {
        long migrated = (long) budgetUsed + budgetLimitFor(fromY, targetY);
        return (int) Math.min(MiningMissionBudget.DESCEND_HARD_WINDOW_TICKS,
                Math.max(MiningMissionBudget.DESCEND_MIN_HARD_WINDOW_TICKS, migrated));
    }

    private static String encodeOptionalPos(BlockPos pos) {
        return pos == null ? "none"
                : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos decodeOptionalPos(String value) {
        if ("none".equals(value)) {
            return null;
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("invalid_pos");
        }
        return new BlockPos(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    private static String encodeDetourEdges(Set<DetourEdge> edges) {
        StringBuilder encoded = new StringBuilder();
        for (DetourEdge edge : edges) {
            if (!encoded.isEmpty()) {
                encoded.append(';');
            }
            encoded.append(edge.origin().getX()).append(',')
                    .append(edge.origin().getY()).append(',')
                    .append(edge.origin().getZ()).append(',')
                    .append(edge.target().getX()).append(',')
                    .append(edge.target().getY()).append(',')
                    .append(edge.target().getZ());
        }
        return encoded.toString();
    }

    private static Set<DetourEdge> decodeDetourEdges(String encoded) {
        if (encoded.isBlank()) {
            return Set.of();
        }
        String[] entries = encoded.split(";", -1);
        if (entries.length > MAX_LATERAL) {
            throw new IllegalArgumentException("too many detour edges");
        }
        Set<DetourEdge> edges = new LinkedHashSet<>();
        for (String entry : entries) {
            String[] coordinates = entry.split(",", -1);
            if (coordinates.length != 6) {
                throw new IllegalArgumentException("invalid detour edge");
            }
            BlockPos origin = new BlockPos(
                    Integer.parseInt(coordinates[0]),
                    Integer.parseInt(coordinates[1]),
                    Integer.parseInt(coordinates[2]));
            BlockPos target = new BlockPos(
                    Integer.parseInt(coordinates[3]),
                    Integer.parseInt(coordinates[4]),
                    Integer.parseInt(coordinates[5]));
            int horizontalDistance = Math.abs(origin.getX() - target.getX())
                    + Math.abs(origin.getZ() - target.getZ());
            int rise = target.getY() - origin.getY();
            if (horizontalDistance != 1 || rise < 0 || rise > 1
                    || origin.getY() <= MIN_Y || origin.getY() > 320
                    || target.getY() <= MIN_Y || target.getY() > 320
                    || !edges.add(new DetourEdge(origin, target))) {
                throw new IllegalArgumentException("invalid detour edge");
            }
        }
        return Collections.unmodifiableSet(edges);
    }

    private static DetourEdge lastEdge(Set<DetourEdge> edges) {
        DetourEdge last = null;
        for (DetourEdge edge : edges) {
            last = edge;
        }
        if (last == null) {
            throw new IllegalArgumentException("missing detour edge");
        }
        return last;
    }

    private static int directionIndex(DetourEdge edge) {
        int dx = edge.target().getX() - edge.origin().getX();
        int dz = edge.target().getZ() - edge.origin().getZ();
        for (int index = 0; index < HORIZONTAL.length; index++) {
            Direction direction = HORIZONTAL[index];
            if (direction.getOffsetX() == dx && direction.getOffsetZ() == dz) {
                return index;
            }
        }
        throw new IllegalArgumentException("invalid detour heading");
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("invalid_boolean");
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        return value;
    }
}
