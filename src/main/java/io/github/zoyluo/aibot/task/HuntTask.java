package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalPlanner;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.pathfinding.FailureReason;
import io.github.zoyluo.aibot.pathfinding.PathExecutor;
import io.github.zoyluo.aibot.pathfinding.PathfindingResult;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * HUNT(第2层 食物自给):主动猎杀附近可食用动物并捡起生肉,直到凑够目标数量的肉。
 *
 * 背景:CombatCore/CombatTask 原本只打**敌对怪**(HostileEntity);bot 饿了却没有"主动去搞肉"的能力
 *(EatTask 只吃现有食物,没肉就放弃)。本任务补上这一环:找最近的牛/猪/羊/鸡/兔 → 接近 → 击杀 → 捡肉 → 凑够数。
 *
 * 复用共享原语:接近/攻击走 {@link CombatCore},掉落用 {@link HarvestCore} 强拾取(与挖矿采集一致)。
 * 自包含状态机(G1,不自 assign),全程主线程(G2)。数量达成或周围无猎物即结束,交编排层处理(如继续去烤)。
 */
public final class HuntTask extends AbstractTask implements CheckpointableTask {
    private enum Phase { RETURN_SURFACE, ACQUIRE, APPROACH, STRIKE, PICKUP, ROAM }
    public enum TransactionState { OPEN, CLOSED_COLLECTED, CLOSED_NO_RAW }
    private enum RoamResult { STARTED, RETRY, EXHAUSTED }
    private enum SurfacePathStart { STARTED, RETRY, UNREACHABLE }
    private enum SurfaceRouteProof { SAFE, RETRY, UNREACHABLE }

    private record AttackPoseSelection(
            BlockPos pose, BlockPos preyCell, SurfaceRouteProof proof) {
        private static AttackPoseSelection safe(BlockPos pose, BlockPos preyCell) {
            return new AttackPoseSelection(
                    pose.toImmutable(), preyCell.toImmutable(), SurfaceRouteProof.SAFE);
        }

        private static AttackPoseSelection failed(SurfaceRouteProof proof) {
            return new AttackPoseSelection(null, null, proof);
        }
    }

    private static final int SEARCH_RANGE = 64;        // 找猎物的扫描范围(动物分散→扩到 64 格,再走过去)
    private static final int MAX_ELAPSED = 3600;       // 3 分钟硬超时
    private static final int NO_PROGRESS_LIMIT = 400;  // 20s 无进展(没靠近/没掉肉)即失败
    private static final int PICKUP_RECOVERY_LIMIT = 240; // 可见掉落/在途拾取的物理恢复硬上限
    private static final int APPROACH_STUCK_TICKS = 30;
    private static final int MAX_PREY_ROAMS = 10;      // 找不到猎物时漫游换片的最多次数(目标量大时多找几片)
    private static final int ROAM_DISTANCE = 32;       // 每次漫游的水平距离
    private static final int ROAM_RETRY_ROTATION_DEGREES = 11;
    private static final int MAX_SURFACE_DESCENT = 16;
    /** Match ActionPack's surface-path budget so its outbound execution reuses A*'s success cache. */
    private static final int SURFACE_ROUTE_MAX_NODES = 10_000;
    private static final long SURFACE_ROUTE_MAX_MILLIS = 50L;
    private static final int SURFACE_RETURN_LIMIT = 400;
    private static final double MIN_ROAM_ADVANCE_SQUARED = 64.0D; // 至少实际走 8 格才算探索过一片
    private static final int WET_PREY_REJECTION_TICKS = 300; // 上岸后 15s 内不重追刚把 bot 带进水里的同一只动物
    private static final int BLIND_PICKUP_SWEEP_DELAY = 20; // 没捡到任何副产物也要有界搜索,覆盖猪等单掉落猎物
    private static final int PICKUP_DROP_BIND_WINDOW = 40;
    private static final double PICKUP_DROP_ORIGIN_RADIUS_SQUARED = 16.0D;
    private static final int PICKUP_CHECKPOINT_SCHEMA = 1;
    private static final int MAX_BOUND_DROP_ENTRIES = 16;
    private static final int MAX_BOUND_DROP_UNITS = 64;
    private static final Set<String> PICKUP_CHECKPOINT_KEYS = Set.of(
            "task_schema", "cursor_kind", "transaction_state",
            "target_count", "require_full_quota", "dimension", "expected_raw_item",
            "pickup_origin", "pickup_return_anchor", "inventory_baseline",
            "pickup_stat_baseline", "aux_inventory_baseline",
            "aux_pickup_stat_baseline", "pickup_started_world_time",
            "bound_drop_units");

    // 可食用猎物及其生肉掉落(烤熟前先拿到生肉)。
    private static final Set<EntityType<?>> PREY = Set.of(
            EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT);
    private static final Set<Item> RAW_MEATS = Set.of(
            Items.BEEF, Items.PORKCHOP, Items.MUTTON, Items.CHICKEN, Items.RABBIT);
    private static final Set<Item> PREY_AUXILIARY_DROPS = Set.of(
            Items.LEATHER, Items.FEATHER, Items.RABBIT_HIDE, Items.RABBIT_FOOT,
            Items.WHITE_WOOL, Items.ORANGE_WOOL, Items.MAGENTA_WOOL, Items.LIGHT_BLUE_WOOL,
            Items.YELLOW_WOOL, Items.LIME_WOOL, Items.PINK_WOOL, Items.GRAY_WOOL,
            Items.LIGHT_GRAY_WOOL, Items.CYAN_WOOL, Items.PURPLE_WOOL, Items.BLUE_WOOL,
            Items.BROWN_WOOL, Items.GREEN_WOOL, Items.RED_WOOL, Items.BLACK_WOOL);
    // Walk a small observed ring around the factual kill cell. A low ItemEntity at the player's
    // feet can fail LivingEntity.canSee even though a sibling drop was physically collected there.
    private static final int[][] PICKUP_SWEEP_OFFSETS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2}
    };
    private static final int[][] ATTACK_POSE_OFFSETS = {
            {1, 0}, {0, 1}, {-1, 0}, {0, -1},
            {1, 1}, {-1, 1}, {-1, -1}, {1, -1},
            {2, 0}, {0, 2}, {-2, 0}, {0, -2},
            {2, 1}, {1, 2}, {-1, 2}, {-2, 1},
            {-2, -1}, {-1, -2}, {1, -2}, {2, -1}
    };
    private static final double ATTACK_POSE_RANGE =
            CombatCore.ATTACK_RANGE - 0.25D;

    private final int targetMeat;
    private final boolean requireFullQuota;
    private final HuntSearchCursor searchCursor;
    private final int maxElapsed; // 硬超时按目标量放大(每块肉约多给 24s),打大量肉不被固定 3 分钟掐断
    private final RestoreMetadata restoredPickup;
    private final boolean invalidCheckpoint;
    private final boolean settlementOnly;
    private int meatBaseline;
    private int collected;
    private int lastProgressTick;
    private int pickupGrace;
    private EntityType<?> targetPreyType;
    private Item targetExpectedRawMeat;
    private int targetKillStatBaseline;
    private int targetExpectedMeatBaseline;
    private int targetExpectedMeatPickupBaseline;
    private final Map<UUID, Integer> targetFreshRawDropUnits = new HashMap<>();
    private Item pickupExpectedRawMeat;
    private int pickupInventoryBaseline;
    private int pickupRawMeatStatBaseline;
    private long pickupStartedWorldTime;
    private final Map<UUID, Integer> pickupDropUnits = new HashMap<>();
    private String pickupDimension = "";
    private TransactionState pickupTransactionState;
    private boolean checkpointDirty;
    private int targetAuxiliaryBaseline;
    private long targetAuxiliaryPickupBaseline;
    private int pickupSweepCursor;
    private BlockPos pickupOrigin;
    private BlockPos pickupReturnAnchor;
    private Phase phase = Phase.ACQUIRE;
    private LivingEntity target;
    private BlockPos attackPose;
    private BlockPos attackPreyCell;
    private BlockPos approachStuckPos; // 接近卡路障检测:上次记录的站位
    private int approachStuckTick;     // 记录该站位的 tick
    private int roamCount;             // 找猎物漫游换片次数
    private BlockPos roamTarget;       // 漫游落脚点
    private BlockPos roamOrigin;       // 本次漫游实际起点；预算只按真实位移结算
    private int roamOrdinal;           // 本次漫游成功后应结算的序号
    private boolean roamCredited;
    private int roamStartTick;         // 本次漫游起步 tick(给寻路起步宽限,防"未出发即判到达"瞬退)
    private int nextRoamRetryTick;     // 候选路径暂时全拒时退避；不把 NO_START 误报成动物耗尽
    private int surfaceReturnStartTick;
    private final Map<UUID, Integer> wetPreyRejectedUntil = new HashMap<>();
    private final Map<UUID, Integer> unsafePreyRejectedUntil = new HashMap<>();

    public HuntTask(int targetMeat) {
        this(targetMeat, false);
    }

    public HuntTask(int targetMeat, boolean requireFullQuota) {
        this(targetMeat, requireFullQuota, HuntSearchCursor.initial());
    }

    public HuntTask(int targetMeat, boolean requireFullQuota, HuntSearchCursor searchCursor) {
        this(targetMeat, requireFullQuota, searchCursor, Map.of());
    }

    public HuntTask(int targetMeat, boolean requireFullQuota,
                    HuntSearchCursor searchCursor, Map<String, String> checkpoint) {
        this.targetMeat = Math.max(1, targetMeat);
        this.requireFullQuota = requireFullQuota;
        this.searchCursor = java.util.Objects.requireNonNull(searchCursor, "searchCursor");
        this.maxElapsed = Math.max(MAX_ELAPSED, this.targetMeat * 480);
        Map<String, String> values = checkpoint == null ? Map.of() : checkpoint;
        Optional<RestoreMetadata> restored = inspectCheckpoint(values);
        this.invalidCheckpoint = HuntPickupCheckpoint.checkpointStructurallyInvalid(
                values, restored);
        this.restoredPickup = invalidCheckpoint ? null : restored.orElse(null);
        this.settlementOnly = HuntPickupCheckpoint.settlementRestore(restoredPickup == null
                ? null : restoredPickup.transactionState());
    }

    @Override
    public String name() {
        return "hunt";
    }

    @Override
    public String describe() {
        return "Hunting meat " + collected + "/" + targetMeat + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetMeat);
    }

    @Override
    public boolean isWaiting() {
        // 追击/捡肉期 bot 可能短暂站立或被地形挡,本任务自带 NO_PROGRESS / 漫游 / 超时三重兜底,
        // 不交给 StuckWatcher 那个"200t 位置没变就 abort"的粗监控误杀(实测追羊卡墙被它 200t abort)。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (invalidCheckpoint) {
            fail("hunt_pickup_invalid_checkpoint");
            checkpointDirty = true;
            return;
        }
        if (settlementOnly) {
            restorePickup(bot);
            return;
        }
        // A closed receipt carries no recoverable debt: the replan already re-derived this
        // step's quota from live inventory, so fall through and hunt fresh instead of
        // failing at tick 0 on a transaction that was already settled.
        CombatCore.equipMelee(bot);
        meatBaseline = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        targetPreyType = null;
        targetExpectedRawMeat = null;
        targetKillStatBaseline = 0;
        targetExpectedMeatBaseline = 0;
        targetExpectedMeatPickupBaseline = 0;
        pickupExpectedRawMeat = null;
        pickupInventoryBaseline = meatBaseline;
        pickupRawMeatStatBaseline = 0;
        pickupStartedWorldTime = 0L;
        pickupDropUnits.clear();
        pickupDimension = "";
        pickupTransactionState = null;
        checkpointDirty = false;
        targetAuxiliaryBaseline = HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS);
        targetAuxiliaryPickupBaseline = pickedUpAuxiliary(bot);
        pickupSweepCursor = 0;
        pickupOrigin = null;
        pickupReturnAnchor = null;
        roamCount = 0;
        clearRoamIntent();
        nextRoamRetryTick = 0;
        wetPreyRejectedUntil.clear();
        unsafePreyRejectedUntil.clear();
        clearTargetIntent();
        phase = Phase.ACQUIRE;
        initializeSurfaceAnchor(bot);
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // 收肉计数:强拾取脚边掉落 + 固定基线绝对增量(刚击杀的肉随后落袋也会算进来)。
        HarvestCore.forcePickupNearbyAnyOf(bot, RAW_MEATS, 2.5D, 2.5D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, RAW_MEATS) - meatBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            roamCount = 0; // 打到肉=这一带有货,重置漫游预算(否则打大量肉时 MAX_PREY_ROAMS 累计早早耗尽、没凑够就收工)
            BotLog.action(bot, "hunt_collected", "total", collected + "/" + targetMeat);
        }
        // An acknowledged kill owns a physical transaction. Goal quota, ordinary hunt timeout and
        // no-progress checks may not commit past that debt.
        if (phase == Phase.PICKUP) {
            pickup(bot);
            return;
        }
        if (elapsed > maxElapsed) {
            failAtMissionDeadline(bot);
            return;
        }
        // A roam that slips into water must not start and consume ten new paths while the bot is
        // still swimming (seed 3000 burned the whole prey budget in 21 ticks). Hand control to the
        // shared physical shore rescue and resume ACQUIRE only after dry ground is restored.
        if (waitForDryGround(bot)) {
            return;
        }

        if (enforceSurfaceEnvelope(bot)) {
            return;
        }
        if (collected >= targetMeat) {
            // Inventory success cannot erase a physical water/surface-return debt. Publish
            // completion only after the two gates above have restored an ordinary surface pose,
            // then retire every controller before the mission advances to cooking/mining.
            bot.getActionPack().stopAll();
            complete();
            return;
        }

        BlockPos feet = bot.getBlockPos();
        String dimension = dimension(bot);
        EpisodeMemory.INSTANCE.recordTrail(bot.getUuid(), "hunt", feet);
        if (!searchCursor.markVisited(dimension, feet.getX(), feet.getZ())
                && searchCursor.isFull()
                && !searchCursor.contains(dimension, feet.getX(), feet.getZ())) {
            fail("hunt_search_capacity_exhausted sectors=" + searchCursor.visitedCount());
            return;
        }

        // 无进展看门狗:长时间没靠近猎物/没掉肉 → 干净失败,交编排层(可能周围没动物了)。
        if (phase != Phase.PICKUP
                && phase != Phase.RETURN_SURFACE
                && elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            fail("hunt_no_progress collected=" + collected);
            return;
        }

        switch (phase) {
            case RETURN_SURFACE -> returnToSurface(bot);
            case ACQUIRE -> acquire(bot);
            case APPROACH -> approach(bot);
            case STRIKE -> strike(bot);
            case PICKUP -> pickup(bot);
            case ROAM -> roamMove(bot);
        }
    }

    private void initializeSurfaceAnchor(AIPlayerEntity bot) {
        String dimension = dimension(bot);
        Optional<HuntSearchCursor.SurfaceAnchor> persisted =
                searchCursor.surfaceAnchor(dimension);
        if (persisted.isEmpty() && searchCursor.surfaceAnchor().isPresent()) {
            fail("hunt_dimension_changed expected="
                    + searchCursor.surfaceAnchor().orElseThrow().dimension()
                    + " actual=" + dimension);
            return;
        }
        if (persisted.isEmpty()) {
            BlockPos feet = bot.getBlockPos();
            Standability.clearCache();
            if (!isFactualSurfaceAnchor(bot, feet)) {
                fail("hunt_surface_anchor_unavailable at=" + feet.toShortString());
                return;
            }
            searchCursor.setSurfaceAnchorIfAbsent(
                    dimension, feet.getX(), feet.getY(), feet.getZ());
        }
        if (outsideSurfaceEnvelope(bot)) {
            beginSurfaceReturn(bot);
        }
    }

    private static boolean isFactualSurfaceAnchor(AIPlayerEntity bot, BlockPos feet) {
        return Standability.isStandable(bot.getServerWorld(), feet)
                && GoalPlanner.canAcquireSurfaceResources(bot);
    }

    private void failAtMissionDeadline(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
        if (phase == Phase.PICKUP) {
            if (!pickupDropUnits.isEmpty()) {
                fail("hunt_drop_unrecovered origin="
                        + (pickupOrigin == null ? "unknown" : pickupOrigin.toShortString())
                        + " item=" + pickupExpectedRawMeat
                        + " deadline=max_elapsed");
            } else {
                fail("hunt_pickup_observation_timeout origin="
                        + (pickupOrigin == null ? "unknown" : pickupOrigin.toShortString())
                        + " item=" + pickupExpectedRawMeat);
            }
            return;
        }
        if (phase == Phase.RETURN_SURFACE) {
            HuntSearchCursor.SurfaceAnchor anchor =
                    searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
            fail("hunt_surface_return_timeout anchor="
                    + (anchor == null ? "unknown"
                    : new BlockPos(anchor.x(), anchor.y(), anchor.z()).toShortString()));
            return;
        }
        if (bot.isTouchingWater() || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
            fail("hunt_water_rescue_timeout at=" + bot.getBlockPos().toShortString());
            return;
        }
        fail("hunt_timeout collected=" + collected);
    }

    /**
     * A hunt is a surface expedition. Safety tasks may pause it and move the bot, but resuming
     * below the mission's original surface band must first settle the physical return debt.
     */
    private boolean enforceSurfaceEnvelope(AIPlayerEntity bot) {
        if (state != TaskState.RUNNING) {
            return true;
        }
        if (phase == Phase.RETURN_SURFACE) {
            returnToSurface(bot);
            return true;
        }
        if (!outsideSurfaceEnvelope(bot)) {
            return false;
        }
        beginSurfaceReturn(bot);
        return true;
    }

    private boolean outsideSurfaceEnvelope(AIPlayerEntity bot) {
        HuntSearchCursor.SurfaceAnchor anchor =
                searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
        if (anchor == null) {
            return true;
        }
        BlockPos current = bot.getBlockPos();
        return current.getY() < anchor.y() - MAX_SURFACE_DESCENT;
    }

    private void beginSurfaceReturn(AIPlayerEntity bot) {
        HuntSearchCursor.SurfaceAnchor anchor =
                searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
        if (anchor == null) {
            fail("hunt_surface_anchor_missing");
            return;
        }
        bot.getActionPack().stopAll();
        clearTargetIntent();
        clearRoamIntent();
        phase = Phase.RETURN_SURFACE;
        surfaceReturnStartTick = elapsed;
        BlockPos destination = new BlockPos(anchor.x(), anchor.y(), anchor.z());
        int returnFloor = Math.min(
                bot.getBlockPos().getY(), surfaceFloorY(anchor));
        SurfacePathStart start = startExactSurfacePath(
                bot, destination, returnFloor, null);
        if (start == SurfacePathStart.UNREACHABLE) {
            fail("hunt_surface_return_unreachable anchor=" + destination.toShortString()
                    + " from=" + bot.getBlockPos().toShortString());
            return;
        }
        if (start == SurfacePathStart.STARTED) {
            BotLog.action(bot, "hunt_surface_return_started",
                    "from", bot.getBlockPos().toShortString(),
                    "to", destination.toShortString());
        }
    }

    private void returnToSurface(AIPlayerEntity bot) {
        HuntSearchCursor.SurfaceAnchor anchor =
                searchCursor.surfaceAnchor(dimension(bot)).orElse(null);
        if (anchor == null) {
            fail("hunt_surface_anchor_missing");
            return;
        }
        BlockPos destination = new BlockPos(anchor.x(), anchor.y(), anchor.z());
        Standability.clearCache();
        if (bot.getBlockPos().getSquaredDistance(destination) <= 4.0D
                && Standability.isStandable(bot.getServerWorld(), bot.getBlockPos())) {
            bot.getActionPack().stopAll();
            phase = Phase.ACQUIRE;
            lastProgressTick = elapsed;
            BotLog.action(bot, "hunt_surface_return_completed",
                    "at", bot.getBlockPos().toShortString());
            return;
        }
        if (elapsed - surfaceReturnStartTick > SURFACE_RETURN_LIMIT) {
            bot.getActionPack().stopAll();
            fail("hunt_surface_return_timeout anchor=" + destination.toShortString());
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            int returnFloor = Math.min(
                    bot.getBlockPos().getY(), surfaceFloorY(anchor));
            SurfacePathStart start = startExactSurfacePath(
                    bot, destination, returnFloor, null);
            if (start == SurfacePathStart.UNREACHABLE) {
                fail("hunt_surface_return_unreachable anchor=" + destination.toShortString()
                        + " from=" + bot.getBlockPos().toShortString());
            } else if (start == SurfacePathStart.STARTED) {
                BotLog.action(bot, "hunt_surface_return_started",
                        "from", bot.getBlockPos().toShortString(),
                        "to", destination.toShortString());
            }
        }
    }

    private static String dimension(AIPlayerEntity bot) {
        return bot.getServerWorld().getRegistryKey().getValue().toString();
    }

    private int surfaceFloorY(AIPlayerEntity bot) {
        return searchCursor.surfaceAnchor(dimension(bot))
                .map(HuntTask::surfaceFloorY)
                .orElse(Integer.MIN_VALUE);
    }

    private static int surfaceFloorY(HuntSearchCursor.SurfaceAnchor anchor) {
        return anchor.y() - MAX_SURFACE_DESCENT;
    }

    // 进入接近阶段:重置卡住基线/清障状态(否则沿用上一个目标的基线,新目标第一 tick 就被误判卡住),再起步寻路。
    private void beginApproach(AIPlayerEntity bot) {
        clearRoamIntent();
        phase = Phase.APPROACH;
        // Capture before combat. The target can die and its loot can enter inventory before the
        // following task tick notices !target.isAlive(); a baseline taken in beginPickup would
        // then mistake that real pickup for pre-existing food and wait forever.
        captureTargetTransactionEvidence(bot);
        approachStuckPos = null;
        approachStuckTick = elapsed;
        lastProgressTick = elapsed;
        SurfacePathStart start = startSafePreyApproach(bot, target);
        if (start == SurfacePathStart.UNREACHABLE) {
            rejectUnsafePrey(bot, target, "no_round_trip");
            clearTargetIntent();
            phase = Phase.ACQUIRE;
        }
    }

    private SurfacePathStart startSafePreyApproach(
            AIPlayerEntity bot, LivingEntity prey) {
        BlockPos returnAnchor = bot.getBlockPos().toImmutable();
        if (!isSafePreyPose(bot, prey)) {
            return SurfacePathStart.UNREACHABLE;
        }
        AttackPoseSelection selection = selectSafeAttackPose(bot, prey);
        if (selection.proof() == SurfaceRouteProof.RETRY) {
            return SurfacePathStart.RETRY;
        }
        if (selection.proof() != SurfaceRouteProof.SAFE) {
            return SurfacePathStart.UNREACHABLE;
        }
        attackPose = selection.pose();
        attackPreyCell = selection.preyCell();
        if (bot.getBlockPos().equals(attackPose)) {
            return SurfacePathStart.STARTED;
        }
        return startExactSurfacePath(
                bot, attackPose, surfaceFloorY(bot), returnAnchor);
    }

    private boolean isSafePreyPose(AIPlayerEntity bot, LivingEntity prey) {
        if (prey == null || !prey.isAlive()
                || !ObservableWorldQuery.canObserveEntity(bot, prey)) {
            return false;
        }
        BlockPos feet = prey.getBlockPos();
        ServerWorld world = bot.getServerWorld();
        if (feet.getY() < surfaceFloorY(bot)
                || !ObservableWorldQuery.canObserveCell(bot, feet)
                || !ObservableWorldQuery.canObserveCell(bot, feet.up())
                || !ObservableWorldQuery.canObserveBlock(bot, feet.down())) {
            return false;
        }
        Standability.clearCache();
        return Standability.isStandable(world, feet);
    }

    /**
     * Chooses a factual player stand near the current prey cell instead of pathing into a moving
     * entity's own block. The chosen pose and the factual kill/drop cell must both have ordinary
     * no-dig/no-pillar return routes, so a prey walking onto a cliff cannot manufacture a one-way
     * pickup debt after the strike.
     */
    private AttackPoseSelection selectSafeAttackPose(
            AIPlayerEntity bot, LivingEntity prey) {
        BlockPos current = bot.getBlockPos();
        BlockPos preyCell = prey.getBlockPos();
        int floorY = surfaceFloorY(bot);
        List<BlockPos> candidates = new ArrayList<>();
        if (withinAttackPoseRange(current, prey)) {
            candidates.add(current.toImmutable());
        }
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] offset : ATTACK_POSE_OFFSETS) {
                BlockPos candidate = preyCell.add(offset[0], dy, offset[1]);
                if (withinAttackPoseRange(candidate, prey)
                        && !candidates.contains(candidate)) {
                    candidates.add(candidate.toImmutable());
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
                candidate -> candidate.getSquaredDistance(current)));

        boolean retryObserved = false;
        for (BlockPos candidate : candidates) {
            if (candidate.equals(preyCell)
                    || candidatePoseIntersectsPrey(bot, candidate, prey)
                    || !isObservableStandCandidate(bot, candidate, current, floorY)) {
                continue;
            }
            SurfaceRouteProof outbound = proveRoundTripSurfaceRoute(
                    bot.getServerWorld(), current, candidate, floorY);
            if (outbound == SurfaceRouteProof.RETRY) {
                retryObserved = true;
                continue;
            }
            if (outbound != SurfaceRouteProof.SAFE) {
                continue;
            }
            SurfaceRouteProof dropRecovery = proveRoundTripSurfaceRoute(
                    bot.getServerWorld(), candidate, preyCell, floorY);
            if (dropRecovery == SurfaceRouteProof.RETRY) {
                retryObserved = true;
                continue;
            }
            if (dropRecovery == SurfaceRouteProof.SAFE) {
                return AttackPoseSelection.safe(candidate, preyCell);
            }
        }
        return AttackPoseSelection.failed(retryObserved
                ? SurfaceRouteProof.RETRY : SurfaceRouteProof.UNREACHABLE);
    }

    private static boolean candidatePoseIntersectsPrey(
            AIPlayerEntity bot, BlockPos candidate, LivingEntity prey) {
        Box candidateBody = bot.getDimensions(bot.getPose()).getBoxAt(
                candidate.getX() + 0.5D,
                candidate.getY(),
                candidate.getZ() + 0.5D);
        return candidateBody.intersects(prey.getBoundingBox());
    }

    private static boolean withinAttackPoseRange(
            BlockPos candidate, LivingEntity prey) {
        Vec3d pose = new Vec3d(
                candidate.getX() + 0.5D,
                candidate.getY(),
                candidate.getZ() + 0.5D);
        return pose.squaredDistanceTo(prey.getPos())
                <= ATTACK_POSE_RANGE * ATTACK_POSE_RANGE;
    }

    private static boolean isObservableStandCandidate(
            AIPlayerEntity bot, BlockPos candidate, BlockPos current, int floorY) {
        if (candidate.getY() < floorY) {
            return false;
        }
        if (!candidate.equals(current)
                && (!ObservableWorldQuery.canObserveCell(bot, candidate)
                || !ObservableWorldQuery.canObserveCell(bot, candidate.up())
                || !ObservableWorldQuery.canObserveBlock(bot, candidate.down()))) {
            return false;
        }
        Standability.clearCache();
        return Standability.isStandable(bot.getServerWorld(), candidate);
    }

    private boolean attackPoseMatchesTarget(AIPlayerEntity bot, LivingEntity prey) {
        return attackPose != null
                && attackPreyCell != null
                && prey != null
                && attackPreyCell.equals(prey.getBlockPos())
                && withinAttackPoseRange(attackPose, prey)
                && !candidatePoseIntersectsPrey(bot, attackPose, prey);
    }

    private void clearAttackIntent() {
        attackPose = null;
        attackPreyCell = null;
        approachStuckPos = null;
    }

    private void clearTargetIntent() {
        target = null;
        targetPreyType = null;
        targetExpectedRawMeat = null;
        targetKillStatBaseline = 0;
        targetExpectedMeatBaseline = 0;
        targetExpectedMeatPickupBaseline = 0;
        targetFreshRawDropUnits.clear();
        clearAttackIntent();
    }

    private void captureTargetTransactionEvidence(AIPlayerEntity bot) {
        targetFreshRawDropUnits.clear();
        if (target == null) {
            targetPreyType = null;
            targetExpectedRawMeat = null;
            return;
        }
        targetPreyType = target.getType();
        targetExpectedRawMeat = expectedRawMeat(targetPreyType);
        if (targetExpectedRawMeat == null) {
            return;
        }
        targetKillStatBaseline =
                bot.getStatHandler().getStat(Stats.KILLED, targetPreyType);
        targetExpectedMeatBaseline =
                HarvestCore.countInventoryItems(bot, Set.of(targetExpectedRawMeat));
        targetExpectedMeatPickupBaseline =
                bot.getStatHandler().getStat(Stats.PICKED_UP, targetExpectedRawMeat);
        targetAuxiliaryBaseline =
                HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS);
        targetAuxiliaryPickupBaseline = pickedUpAuxiliary(bot);
    }

    private void rejectUnsafePrey(
            AIPlayerEntity bot, LivingEntity prey, String reason) {
        if (prey == null) {
            return;
        }
        int rejectedUntil = elapsed + WET_PREY_REJECTION_TICKS;
        unsafePreyRejectedUntil.put(prey.getUuid(), rejectedUntil);
        EpisodeMemory.INSTANCE.exclude(
                bot.getUuid(), prey.getBlockPos(),
                bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        BotLog.action(bot, "hunt_unsafe_prey_rejected",
                "prey", prey.getUuid(),
                "at", prey.getBlockPos().toShortString(),
                "reason", reason,
                "until", rejectedUntil);
        clearAttackIntent();
    }

    private void acquire(AIPlayerEntity bot) {
        target = nearestPrey(bot);
        if (target != null) {
            beginApproach(bot);
            return;
        }
        // 周围(64 格)没猎物 → 先漫游换片找更多,努力凑够目标(动物分散/在远处),
        // 而非"猎到一点就收工"(实测:打 10 块肉,猎到几块后附近打光就 complete,没凑够数)。
        RoamResult roam = roamForPrey(bot);
        if (roam != RoamResult.EXHAUSTED) {
            return;
        }
        // 漫游也用尽仍找不到:普通觅食可尽力收；长期挖矿 readiness 必须达到完整配额。
        if (collected > 0 && !requireFullQuota) {
            complete();
            return;
        }
        fail((collected > 0 ? "insufficient_prey" : "no_prey_found")
                + " collected=" + collected + "/" + targetMeat + " roams=" + roamCount);
    }

    private boolean waitForDryGround(AIPlayerEntity bot) {
        boolean active = NavSafetyNet.INSTANCE.isWaterRescueActive(bot);
        if (!bot.isTouchingWater() && !active) {
            return false;
        }
        bot.getActionPack().stopAll();
        boolean preservePhysicalDebt =
                phase == Phase.PICKUP || phase == Phase.RETURN_SURFACE;
        if (roamTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUuid(), roamTarget,
                    bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        }
        if (target != null && target.isAlive()) {
            int rejectedUntil = elapsed + WET_PREY_REJECTION_TICKS;
            wetPreyRejectedUntil.put(target.getUuid(), rejectedUntil);
            BotLog.action(bot, "hunt_wet_prey_rejected",
                    "prey", target.getUuid(),
                    "until", rejectedUntil,
                    "at", target.getBlockPos().toShortString());
        }
        if (!preservePhysicalDebt) {
            clearRoamIntent();
            clearTargetIntent();
            phase = Phase.ACQUIRE;
            lastProgressTick = elapsed;
        }
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        return true;
    }

    // 找不到猎物 → 走到 ROAM_DISTANCE 外的露天地表换片再找;最多 MAX_PREY_ROAMS 次。
    private RoamResult roamForPrey(AIPlayerEntity bot) {
        if (elapsed < nextRoamRetryTick) {
            return RoamResult.RETRY;
        }
        int nextRoam = roamCount + 1;
        if (nextRoam > MAX_PREY_ROAMS) {
            return RoamResult.EXHAUSTED;
        }
        if (searchCursor.isFull()) {
            fail("hunt_search_capacity_exhausted sectors=" + searchCursor.visitedCount());
            return RoamResult.RETRY;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        String dimension = dimension(bot);
        long claimedOrdinal;
        try {
            claimedOrdinal = searchCursor.claimNextOrdinal();
        } catch (IllegalStateException exhausted) {
            fail("hunt_search_ordinal_exhausted");
            return RoamResult.RETRY;
        }
        int attemptSerial = (int) Math.floorMod(claimedOrdinal, Integer.MAX_VALUE);
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int start = Math.floorMod(attemptSerial + nextRoam, dirs.length);
        // 距离自适应:满距 8 方向全寻路被拒(山顶/悬崖/水域环绕)就减半再试——近处总有能走的点,
        // 先挪过去下轮再扩(与 GatherQuotaTask.roamToNewArea 同款,治"8 连拒直接放弃"速死)。
        for (int dist = ROAM_DISTANCE; dist >= ROAM_DISTANCE / 4; dist /= 2) {
            for (int i = 0; i < dirs.length; i++) {
                int[] d = dirs[(start + i) % dirs.length];
                BlockPos column = rotatedRoamColumn(feet, d[0], d[1], dist, attemptSerial);
                BlockPos ground = findGround(world, column.getX(), column.getZ());
                if (ground == null
                        || ground.getY() < surfaceFloorY(bot)
                        || searchCursor.contains(
                        dimension, ground.getX(), ground.getZ())
                        || EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUuid(), ground, bot.getServer().getTicks())
                        || EpisodeMemory.INSTANCE.nearTrail(
                                bot.getUuid(), "hunt", ground, 10.0D)) {
                    continue;
                }
                // Surface exploration is a sequence of waypoints, not a one-way cave descent.
                // Before accepting a lower waypoint, prove that a no-dig/no-pillar route can walk
                // back to the current surface. This rejects seed-3000's chained safe drops into a
                // Y=53 pocket whose only reverse path consumed pillar blocks and stranded the bot.
                if (!hasRoundTripSurfaceRoute(
                        world, feet, ground, surfaceFloorY(bot))) {
                    EpisodeMemory.INSTANCE.exclude(bot.getUuid(), ground,
                            bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
                    BotLog.action(bot, "hunt_roam_one_way_rejected",
                            "from", feet.toShortString(), "to", ground.toShortString());
                    continue;
                }
                bot.getActionPack().stopAll();
                // 寻路被拒(目标不可达/未加载)→ 试下一个方向。原来不看结果就进 ROAM,
                // 下一 tick isPathExecutorIdle 即真 → 瞬退回 ACQUIRE → 再 roam……
                // 同一秒连发 3 次 roam、瞬间烧光漫游预算,bot 原地没动(实测 hunt 在贫瘠地形 642t 空转失败)。
                SurfacePathStart pathStart = startExactSurfacePath(
                        bot, ground, surfaceFloorY(bot), feet);
                if (pathStart == SurfacePathStart.RETRY) {
                    nextRoamRetryTick = elapsed + 5;
                    phase = Phase.ACQUIRE;
                    return RoamResult.RETRY;
                }
                if (pathStart == SurfacePathStart.UNREACHABLE) {
                    continue;
                }
                roamTarget = ground;
                roamOrigin = feet.toImmutable();
                roamStartTick = elapsed;
                roamOrdinal = nextRoam;
                roamCredited = false;
                phase = Phase.ROAM;
                BotLog.action(bot, "hunt_roam",
                        "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                        "n", nextRoam, "dist", dist,
                        "ordinal", claimedOrdinal);
                return RoamResult.STARTED;
            }
        }
        // A full rejection must change the next candidate geometry. Merely sleeping and retrying
        // the same 8 directions at the same 3 radii strands the bot forever on seed-3000's ridge:
        // nextRoam stays 1 because no movement was committed, so every replan repeats the exact
        // same 24 cells until hunt_no_progress. Rotate the sampling fan deterministically while
        // preserving the roam credit; successful physical movement still owns roamCount.
        nextRoamRetryTick = elapsed + 20;
        phase = Phase.ACQUIRE;
        BotLog.action(bot, "hunt_roam_retry",
                "n", nextRoam,
                "ordinal", claimedOrdinal,
                "from", feet.toShortString());
        return RoamResult.RETRY;
    }

    /**
     * Produces a new deterministic surface-sampling fan after every fully rejected roam attempt.
     * Serial zero is byte-for-byte the old cardinal/diagonal geometry. Later attempts rotate that
     * fan through the 45-degree symmetry sector, so a narrow ridge between the compass axes can be
     * discovered without randomness, teleporting, digging, or accepting a one-way drop.
     */
    static BlockPos rotatedRoamColumn(BlockPos origin, int dx, int dz, int distance, int attemptSerial) {
        double baseAngle = Math.atan2(dz, dx);
        int rotation = Math.floorMod(attemptSerial * ROAM_RETRY_ROTATION_DEGREES, 45);
        double angle = baseAngle + Math.toRadians(rotation);
        double radius = distance * Math.sqrt((double) dx * dx + (double) dz * dz);
        int x = origin.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = origin.getZ() + (int) Math.round(Math.sin(angle) * radius);
        return new BlockPos(x, origin.getY(), z);
    }

    static boolean hasWalkableReturnRoute(ServerWorld world, BlockPos waypoint, BlockPos origin) {
        return hasExactSurfaceRoute(world, waypoint, origin, Integer.MIN_VALUE);
    }

    static boolean hasRoundTripSurfaceRoute(
            ServerWorld world, BlockPos origin, BlockPos destination, int minimumY) {
        return proveRoundTripSurfaceRoute(
                world, origin, destination, minimumY) == SurfaceRouteProof.SAFE;
    }

    private static boolean hasExactSurfaceRoute(
            ServerWorld world, BlockPos origin, BlockPos destination, int minimumY) {
        return proveExactSurfaceRoute(
                world, origin, destination, minimumY) == SurfaceRouteProof.SAFE;
    }

    private static SurfaceRouteProof proveRoundTripSurfaceRoute(
            ServerWorld world, BlockPos origin, BlockPos destination, int minimumY) {
        return proveSurfaceRouteContract(
                world, origin, destination, minimumY, origin);
    }

    private static SurfaceRouteProof proveSurfaceRouteContract(
            ServerWorld world, BlockPos origin, BlockPos destination,
            int minimumY, BlockPos returnAnchor) {
        SurfaceRouteProof outbound =
                proveExactSurfaceRoute(world, origin, destination, minimumY);
        if (outbound != SurfaceRouteProof.SAFE) {
            return outbound;
        }
        return returnAnchor == null ? SurfaceRouteProof.SAFE
                : proveExactSurfaceRoute(
                        world, destination, returnAnchor, minimumY);
    }

    private static SurfaceRouteProof proveExactSurfaceRoute(
            ServerWorld world, BlockPos origin, BlockPos destination, int minimumY) {
        Standability.clearCache();
        if (origin.equals(destination)) {
            return origin.getY() >= minimumY && Standability.isStandable(world, origin)
                    ? SurfaceRouteProof.SAFE : SurfaceRouteProof.RETRY;
        }
        PathfindingResult result = new AStarPathfinder(
                world, origin, destination,
                SURFACE_ROUTE_MAX_NODES, SURFACE_ROUTE_MAX_MILLIS,
                false, false).findPathUncachedAtOrAbove(minimumY);
        if (result.success()) {
            return PathExecutor.isExactConstrainedRoute(
                            result, origin, destination, minimumY)
                    ? SurfaceRouteProof.SAFE : SurfaceRouteProof.UNREACHABLE;
        }
        return result.reason() == FailureReason.NO_START
                || result.reason() == FailureReason.TIMEOUT
                || result.reason() == FailureReason.SEARCH_LIMIT
                ? SurfaceRouteProof.RETRY : SurfaceRouteProof.UNREACHABLE;
    }

    private static SurfacePathStart startExactSurfacePath(
            AIPlayerEntity bot, BlockPos destination, int minimumY,
            BlockPos returnAnchor) {
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        SurfaceRouteProof proof = proveSurfaceRouteContract(
                world, origin, destination, minimumY, returnAnchor);
        if (proof == SurfaceRouteProof.RETRY) {
            return SurfacePathStart.RETRY;
        }
        if (proof != SurfaceRouteProof.SAFE) {
            return SurfacePathStart.UNREACHABLE;
        }
        ActionResult result = returnAnchor == null
                ? bot.getActionPack().startSurfacePathTo(destination, minimumY)
                : bot.getActionPack().startSurfacePathTo(
                        destination, minimumY, returnAnchor);
        if (result.isFailed()) {
            return "pathfinding_throttled".equals(result.reason())
                    || result.reason().contains("NO_START")
                    ? SurfacePathStart.RETRY : SurfacePathStart.UNREACHABLE;
        }
        BlockPos resolved = bot.getActionPack().activePathGoal();
        if (resolved == null || !resolved.equals(destination)) {
            bot.getActionPack().stopAll();
            return SurfacePathStart.UNREACHABLE;
        }
        return SurfacePathStart.STARTED;
    }

    // 漫游途中持续扫猎物:发现就转入捕猎;到落脚点/走不动则回 ACQUIRE 重扫。
    private void roamMove(AIPlayerEntity bot) {
        LivingEntity prey = nearestPrey(bot);
        if (prey != null) {
            target = prey;
            // ROAM owns a different route contract and return anchor. Retire it before installing
            // the prey approach so same-goal cooldown cannot leave the old executor in control.
            bot.getActionPack().stopAll();
            beginApproach(bot);
            return;
        }
        double advance = roamOrigin == null
                ? 0.0D : bot.getBlockPos().getSquaredDistance(roamOrigin);
        if (!roamCredited && advance >= MIN_ROAM_ADVANCE_SQUARED) {
            roamCount = Math.max(roamCount, roamOrdinal);
            roamCredited = true;
            lastProgressTick = elapsed;
            BotLog.action(bot, "hunt_roam_committed",
                    "n", roamCount, "advance", (int) Math.sqrt(advance));
        }
        boolean arrived = roamTarget == null
                || bot.getBlockPos().getSquaredDistance(roamTarget) <= 9.0D;
        // 起步宽限 20t:startPathTo 后 A* 异步计算需几个 tick,期间 executor 仍 idle,
        // 立即判"走不动"会瞬退(roam 形同虚设)。宽限后 idle 才是真到不了;200t 上限防走太久。
        boolean gaveUp = (elapsed - roamStartTick > 20 && bot.getActionPack().isPathExecutorIdle())
                || elapsed - roamStartTick > 200;
        if (arrived || gaveUp) {
            if (!roamCredited || gaveUp) {
                excludeRoamTarget(bot);
            }
            if (gaveUp) {
                bot.getActionPack().stopAll();
            }
            clearRoamIntent();
            phase = Phase.ACQUIRE;
        }
    }

    private void excludeRoamTarget(AIPlayerEntity bot) {
        if (roamTarget != null) {
            EpisodeMemory.INSTANCE.exclude(bot.getUuid(), roamTarget,
                    bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        }
    }

    private void clearRoamIntent() {
        roamTarget = null;
        roamOrigin = null;
        roamOrdinal = 0;
        roamCredited = false;
    }

    // 在 (x,z) 列从高往低找第一个露天可站点(地表落脚点)。
    private static BlockPos findGround(ServerWorld world, int x, int z) {
        // 用高度图直接拿该列地表,跨任意海拔都成立。原来硬上限 y=110:bot 站在 y>110 的高地/丘陵时
        // 永远找不到落脚点 → 漫游全废 → 明明附近有猎物也 hunt_stuck_no_escape(实测 y=111、有鸡 dist 13 仍失败)。
        // 树冠穿透:原 MOTION_BLOCKING 顶面在森林落在树冠上(高大云杉 20+ 格,固定下穿格数赌不赢),
        // 林下地面又不见天 → 采样点全 null → 漫游全拒速死(实测云杉林出生)。
        // 正解:MOTION_BLOCKING_NO_LEAVES 高度图原生跳过树叶,顶面=地形/树干;再下穿几格落到地面。
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = surfaceY; y >= surfaceY - 24 && y > world.getBottomY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (Standability.isStandable(world, p)) {
                return p;
            }
        }
        return null;
    }

    private void approach(AIPlayerEntity bot) {
        if (resolveUnavailableTarget(bot)) {
            return;
        }
        if (!isSafePreyPose(bot, target)) {
            rejectUnsafePrey(bot, target, "pose_left_surface_envelope");
            clearTargetIntent();
            bot.getActionPack().stopAll();
            phase = Phase.ACQUIRE;
            return;
        }
        CombatCore.lookAt(bot, target);
        if (!attackPoseMatchesTarget(bot, target)) {
            bot.getActionPack().stopAll();
            SurfacePathStart retarget = startSafePreyApproach(bot, target);
            if (retarget == SurfacePathStart.UNREACHABLE) {
                rejectUnsafePrey(bot, target, "moving_pose_unreachable");
                clearTargetIntent();
                phase = Phase.ACQUIRE;
            } else if (retarget == SurfacePathStart.STARTED
                    && readyToStrikeFromProvenPose(bot)) {
                bot.getActionPack().stopAll();
                phase = Phase.STRIKE;
            }
            return;
        }
        if (readyToStrikeFromProvenPose(bot)) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        BlockPos at = bot.getBlockPos();
        BlockPos activeGoal = bot.getActionPack().activePathGoal();
        boolean staleGoal = activeGoal != null && !activeGoal.equals(attackPose);
        if (staleGoal) {
            bot.getActionPack().stopAll();
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            SurfacePathStart start = startSafePreyApproach(bot, target);
            if (start == SurfacePathStart.UNREACHABLE) {
                rejectUnsafePrey(bot, target, "surface_route_lost");
                clearTargetIntent();
                phase = Phase.ACQUIRE;
                return;
            }
            if (start == SurfacePathStart.RETRY) {
                // NO_START/throttling/search-budget exhaustion is transient. Leave the factual
                // prey pending and let the task-wide no-progress deadline bound retries instead
                // of poisoning the animal as unsafe after 30 fast, stationary ticks.
                return;
            }
            if (readyToStrikeFromProvenPose(bot)) {
                bot.getActionPack().stopAll();
                phase = Phase.STRIKE;
                return;
            }
            // A new exact path has just been accepted. Its executor can legitimately spend a few
            // ticks turning before changing block coordinates, so start the stuck budget here.
            approachStuckPos = at;
            approachStuckTick = elapsed;
            return;
        }
        if (at.equals(approachStuckPos)) {
            if (elapsed - approachStuckTick > APPROACH_STUCK_TICKS) {
                BotLog.action(bot, "hunt_approach_stuck", "pos", at.toShortString(),
                        "dist", (int) bot.distanceTo(target));
                rejectUnsafePrey(bot, target, "surface_path_stuck");
                clearTargetIntent();
                bot.getActionPack().stopAll();
                phase = Phase.ACQUIRE;
            }
            return;
        }
        approachStuckPos = at;
        approachStuckTick = elapsed;
    }

    private boolean readyToStrikeFromProvenPose(AIPlayerEntity bot) {
        return attackPoseMatchesTarget(bot, target)
                && (bot.getBlockPos().equals(attackPose)
                // PathExecutor can physically overshoot an adjacent pose into the factual prey
                // cell. selectSafeAttackPose already proved this exact cell reversible for drop
                // recovery, so stop there instead of letting a live controller drift farther.
                || bot.getBlockPos().equals(attackPreyCell))
                && CombatCore.inMeleeRange(bot, target)
                && CombatCore.hasLineOfSight(bot, target);
    }

    private void strike(AIPlayerEntity bot) {
        if (resolveUnavailableTarget(bot)) {
            return;
        }
        if (!isSafePreyPose(bot, target)) {
            rejectUnsafePrey(bot, target, "unsafe_strike_pose");
            clearTargetIntent();
            bot.getActionPack().stopAll();
            phase = Phase.ACQUIRE;
            return;
        }
        CombatCore.lookAt(bot, target);
        if (!readyToStrikeFromProvenPose(bot)) {
            // The prey moved out of the already-proven kill/drop envelope. Re-select a factual
            // attack pose instead of swinging from an unverified cliff or following its entity
            // block as a moving exact endpoint.
            beginApproach(bot);
            return;
        }
        CombatCore.equipMelee(bot); // 砍之前确保手持最佳武器(实测打猎 held=dirt,拿土砍肉伤害仅 1、极慢);equipFromSlot 幂等不抖
        // Refresh at the exact attack boundary. A long approach may cross unrelated meat or kill
        // statistics; only evidence produced after the swing that can own this target may settle
        // the transaction.
        captureTargetTransactionEvidence(bot);
        boolean struck = CombatCore.strikeIfReady(bot, target);
        // Vanilla death, kill statistics, and loot spawning are synchronous with the fatal attack.
        // Capture the fresh entity identity in this same task tick, but preserve the existing
        // phase transition timing: resolveUnavailableTarget opens PICKUP on the following tick.
        if (struck && target != null
                && (target.getHealth() <= 0.0F
                || target.getRemovalReason()
                == net.minecraft.entity.Entity.RemovalReason.KILLED)) {
            captureFreshTargetRawDropIds(bot);
        }
        // 不再每 tick 刷 lastProgressTick:命中→掉肉会在 onTick 顶部刷新进度;真打不动(够不到/无敌)则靠
        // NO_PROGRESS_LIMIT 兜底干净失败,而非"挥空也算进展"把任务拖到 maxElapsed。
    }

    /**
     * Distinguishes a factual kill from a stale entity reference.
     *
     * <p>{@link LivingEntity#isAlive()} is also false after chunk unload. Treating every removed
     * reference as a corpse creates a pickup debt for loot that never existed, while the same
     * animal can reappear alive when its chunk loads again. Only zero health or Minecraft's
     * explicit KILLED removal reason may open the atomic loot transaction.</p>
     */
    private boolean resolveUnavailableTarget(AIPlayerEntity bot) {
        if (target != null && (target.getHealth() <= 0.0F
                || target.getRemovalReason() == net.minecraft.entity.Entity.RemovalReason.KILLED)) {
            boolean creditedKill = targetPreyType != null
                    && targetExpectedRawMeat != null
                    && bot.getStatHandler().getStat(Stats.KILLED, targetPreyType)
                    > targetKillStatBaseline;
            if (creditedKill) {
                beginPickup(bot);
            } else {
                UUID lostId = target.getUuid();
                BlockPos lostAt = target.getBlockPos().toImmutable();
                bot.getActionPack().stopAll();
                clearTargetIntent();
                lastProgressTick = elapsed;
                phase = Phase.ACQUIRE;
                BotLog.action(bot, "hunt_target_death_uncredited",
                        "prey", lostId,
                        "at", lostAt.toShortString());
            }
            return true;
        }
        if (target != null && target.isAlive()) {
            return false;
        }

        UUID lostId = target == null ? null : target.getUuid();
        Object removal = target == null ? "missing" : target.getRemovalReason();
        bot.getActionPack().stopAll();
        clearTargetIntent();
        lastProgressTick = elapsed;
        phase = Phase.ACQUIRE;
        BotLog.action(bot, "hunt_target_reacquire", "prey", lostId, "removal", removal);
        return true;
    }

    private void beginPickup(AIPlayerEntity bot) {
        Map<UUID, Integer> creditedDropUnits = Map.copyOf(targetFreshRawDropUnits);
        pickupOrigin = target == null ? bot.getBlockPos().toImmutable()
                : target.getBlockPos().toImmutable();
        pickupReturnAnchor = bot.getBlockPos().toImmutable();
        pickupExpectedRawMeat = targetExpectedRawMeat;
        pickupInventoryBaseline = targetExpectedMeatBaseline;
        pickupRawMeatStatBaseline = targetExpectedMeatPickupBaseline;
        pickupStartedWorldTime = bot.getServerWorld().getTime();
        pickupDimension = dimension(bot);
        pickupDropUnits.clear();
        pickupDropUnits.putAll(creditedDropUnits);
        pickupTransactionState = TransactionState.OPEN;
        // Bind a factual drop identity at the kill site before yielding the first PICKUP tick.
        // Once bound, that same entity may drift, fall, or be moved beyond the origin envelope
        // without being mistaken for unrelated old meat. Unbound entities must still satisfy the
        // fresh-time and kill-origin checks in nearestTransactionRawDrop.
        nearestTransactionRawDrop(bot);
        clearTargetIntent();
        bot.getActionPack().stopAll();
        pickupGrace = 0;
        pickupSweepCursor = 0;
        lastProgressTick = elapsed;
        phase = Phase.PICKUP;
        checkpointDirty = true;
    }

    private void captureFreshTargetRawDropIds(AIPlayerEntity bot) {
        if (target == null || targetExpectedRawMeat == null) {
            return;
        }
        BlockPos killOrigin = target.getBlockPos().toImmutable();
        List<ItemEntity> freshDrops = bot.getServerWorld().getEntitiesByClass(
                ItemEntity.class,
                bot.getBoundingBox().expand(16.0D),
                entity -> entity.isAlive()
                        && !entity.getStack().isEmpty()
                        && entity.getStack().isOf(targetExpectedRawMeat)
                        && entity.getItemAge() >= 0
                        && entity.getItemAge() <= 3
                        && entity.getPos().squaredDistanceTo(Vec3d.ofCenter(killOrigin))
                        <= PICKUP_DROP_ORIGIN_RADIUS_SQUARED
                        && ObservableWorldQuery.canObserveEntity(bot, entity));
        for (ItemEntity drop : freshDrops) {
            int units = drop.getStack().getCount();
            if (units > 0 && bindDropUnits(targetFreshRawDropUnits, drop.getUuid(), units)) {
                BotLog.action(bot, "hunt_kill_drop_identity_bound",
                        "drop", drop.getUuid(),
                        "item", targetExpectedRawMeat,
                        "origin", killOrigin.toShortString(),
                        "age", drop.getItemAge(),
                        "units", units);
            }
        }
    }

    private void pickup(AIPlayerEntity bot) {
        if (!pickupDimension.equals(dimension(bot))) {
            checkpointDirty = true;
            fail("hunt_pickup_dimension_mismatch:expected=" + pickupDimension
                    + ":actual=" + dimension(bot));
            return;
        }
        long age = pickupAge(bot);
        if (age < 0L) {
            checkpointDirty = true;
            fail("hunt_pickup_time_rollback:started=" + pickupStartedWorldTime
                    + ":current=" + bot.getServerWorld().getTime());
            return;
        }
        pickupGrace = (int) Math.min(Integer.MAX_VALUE, age);
        Optional<ItemEntity> nearestDrop = nearestTransactionRawDrop(bot, age);
        if (state == TaskState.FAILED) {
            return;
        }
        boolean observedDrop = nearestDrop.isPresent();
        boolean pickupMovementActive = !bot.getActionPack().isPathExecutorIdle()
                || !bot.getActionPack().isWalkToIdle();
        if (nearestDrop.isPresent() && !pickupMovementActive) {
            ItemEntity drop = nearestDrop.orElseThrow();
            // A delayed ItemEntity remains a factual unresolved debt, but cannot make physical
            // pickup progress yet. Let remembered-cell/auxiliary sweep recover sibling loot
            // instead of allowing this entity to monopolize the controller indefinitely.
            if (!drop.cannotPickup()) {
                BlockPos stand = safeObservedDropStand(bot, drop);
                if (stand != null) {
                    pickupMovementActive = approachPickupStand(
                            bot, stand, drop.getPos());
                } else if (pickupGrace == 3 || pickupGrace % 40 == 0) {
                    BotLog.action(bot, "hunt_drop_one_way_rejected",
                            "drop", drop.getBlockPos().toShortString(),
                            "anchor", pickupReturnAnchor == null
                                    ? "unknown" : pickupReturnAnchor.toShortString());
                }
            }
        }
        int currentMeat = pickupExpectedRawMeat == null ? 0
                : HarvestCore.countInventoryItems(bot, Set.of(pickupExpectedRawMeat));
        int currentPickupStat = pickupExpectedRawMeat == null ? 0
                : bot.getStatHandler().getStat(Stats.PICKED_UP, pickupExpectedRawMeat);
        int requiredUnits = Math.max(1, boundDropUnitCount(pickupDropUnits));
        boolean collectionConfirmed = pickupExpectedRawMeat != null
                && collectionCoversBoundUnits(
                pickupInventoryBaseline, currentMeat,
                pickupRawMeatStatBaseline, currentPickupStat, requiredUnits);
        boolean auxiliaryPickupObserved = HarvestCore.countInventoryItems(bot, PREY_AUXILIARY_DROPS)
                > targetAuxiliaryBaseline
                || pickedUpAuxiliary(bot) > targetAuxiliaryPickupBaseline;
        if (!collectionConfirmed && !pickupMovementActive && pickupGrace >= 3) {
            // The kill coordinate is factual. Return there first, but do not camp forever when a
            // low sibling ItemEntity is hidden from canSee at the player's feet. Picking leather,
            // wool, feather or hide is observable proof that this kill's loot transaction began;
            // walk an observed, dry ring so the missed meat becomes visible/collidable. The fixed
            // delay covers prey without auxiliary drops (notably pigs) without hidden scans.
            if ((auxiliaryPickupObserved || pickupGrace >= BLIND_PICKUP_SWEEP_DELAY)
                    && startNextPickupSweepStep(bot)) {
                pickupMovementActive = true;
            } else if (pickupOrigin != null && safePickupCellRoute(bot, pickupOrigin)) {
                pickupMovementActive = approachKnownPickupCell(bot, pickupOrigin);
            }
        }
        // PICKUP is an atomic inventory transaction, not a one-tick visibility hint. A dead
        // animal's ItemEntity may become visible only after this task's tick; leaving early lets a
        // roam path overwrite the only physical pickup route. Hold the debt until inventory proves
        // success, and after that until every observed drop/controller has settled.
        if (pickupGrace < PICKUP_RECOVERY_LIMIT
                && (!collectionConfirmed || observedDrop || pickupMovementActive)) {
            return;
        }
        boolean unresolvedObservedRawDebt =
                !pickupDropUnits.isEmpty() && (!collectionConfirmed || observedDrop);
        if (unresolvedObservedRawDebt) {
            bot.getActionPack().stopAll();
            checkpointDirty = true;
            fail("hunt_drop_unrecovered origin="
                    + (pickupOrigin == null ? "unknown" : pickupOrigin.toShortString())
                    + " item=" + pickupExpectedRawMeat
                    + " baseline=" + pickupInventoryBaseline + " current=" + currentMeat);
            return;
        }
        if (!collectionConfirmed) {
            // A credited vanilla kill can legally yield no raw item (rabbit loot variance), and a
            // Fire Aspect weapon converts the expected raw item to cooked food. If no fresh raw
            // ItemEntity from this kill was ever observed, the bounded observation/sweep window is
            // absence evidence, not an unrecoverable physical debt.
            BotLog.action(bot, "hunt_kill_without_raw_drop",
                    "origin", pickupOrigin == null ? "unknown" : pickupOrigin.toShortString(),
                    "item", pickupExpectedRawMeat,
                    "waited", pickupGrace);
            finishPickupTransaction(bot, currentMeat, TransactionState.CLOSED_NO_RAW);
            return;
        }
        finishPickupTransaction(bot, currentMeat, TransactionState.CLOSED_COLLECTED);
    }

    private void finishPickupTransaction(
            AIPlayerEntity bot, int currentMeat, TransactionState closedState) {
        bot.getActionPack().stopAll();
        pickupTransactionState = closedState;
        checkpointDirty = true;
        lastProgressTick = elapsed;
        if (settlementOnly) {
            complete();
            return;
        }
        phase = Phase.ACQUIRE; // 捡完去找下一只(数量够了会在 onTick 顶部 complete)
    }

    private Optional<ItemEntity> nearestTransactionRawDrop(AIPlayerEntity bot) {
        long age = pickupAge(bot);
        return age < 0L ? Optional.empty() : nearestTransactionRawDrop(bot, age);
    }

    private Optional<ItemEntity> nearestTransactionRawDrop(AIPlayerEntity bot, long transactionAge) {
        if (pickupExpectedRawMeat == null || pickupOrigin == null) {
            return Optional.empty();
        }
        List<ItemEntity> observed = bot.getServerWorld().getEntitiesByClass(
                ItemEntity.class,
                bot.getBoundingBox().expand(16.0D),
                entity -> entity.isAlive()
                        && !entity.getStack().isEmpty()
                        && entity.getStack().isOf(pickupExpectedRawMeat)
                        && ObservableWorldQuery.canObserveEntity(bot, entity));
        ItemEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ItemEntity drop : observed) {
            boolean bound = pickupDropUnits.containsKey(drop.getUuid());
            if (!bound && isFreshTransactionDrop(drop, transactionAge)) {
                int units = drop.getStack().getCount();
                if (units > 0 && bindDropUnits(pickupDropUnits, drop.getUuid(), units)) {
                    checkpointDirty = true;
                    bound = true;
                    BotLog.action(bot, "hunt_pickup_drop_bound",
                            "drop", drop.getUuid(),
                            "item", pickupExpectedRawMeat,
                            "origin", pickupOrigin.toShortString(),
                            "age", drop.getItemAge(),
                            "units", units);
                } else if (units > 0) {
                    checkpointDirty = true;
                    fail("hunt_pickup_checkpoint_capacity:entries="
                            + pickupDropUnits.size() + ":units="
                            + boundDropUnitCount(pickupDropUnits));
                    return Optional.empty();
                }
            }
            if (!bound) {
                continue;
            }
            double distance = drop.squaredDistanceTo(bot);
            if (distance < nearestDistance) {
                nearest = drop;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private boolean isFreshTransactionDrop(ItemEntity drop, long transactionAge) {
        if (!canBindFreshDropAtAge(transactionAge)) {
            return false;
        }
        int itemAge = drop.getItemAge();
        if (itemAge < 0 || itemAge > transactionAge + 3) {
            return false;
        }
        return drop.getPos().squaredDistanceTo(Vec3d.ofCenter(pickupOrigin))
                <= PICKUP_DROP_ORIGIN_RADIUS_SQUARED;
    }

    private long pickupAge(AIPlayerEntity bot) {
        return pickupAgeAt(pickupStartedWorldTime, bot.getServerWorld().getTime());
    }

    static long pickupAgeAt(long startedWorldTime, long currentWorldTime) {
        return startedWorldTime < 0L || currentWorldTime < startedWorldTime
                ? -1L : currentWorldTime - startedWorldTime;
    }

    static boolean canBindFreshDropAtAge(long transactionAge) {
        return transactionAge >= 0L && transactionAge <= PICKUP_DROP_BIND_WINDOW;
    }

    static boolean collectionCoversBoundUnits(
            int inventoryBaseline, int currentInventory,
            int pickupStatBaseline, int currentPickupStat,
            int requiredUnits) {
        return requiredUnits > 0
                && ((long) currentInventory - inventoryBaseline >= requiredUnits
                || (long) currentPickupStat - pickupStatBaseline >= requiredUnits);
    }

    private static boolean bindDropUnits(
            Map<UUID, Integer> unitsById, UUID id, int units) {
        if (id == null || units <= 0 || units > MAX_BOUND_DROP_UNITS) {
            return false;
        }
        Integer existing = unitsById.get(id);
        if (existing != null) {
            return existing == units;
        }
        if (unitsById.size() >= MAX_BOUND_DROP_ENTRIES
                || (long) boundDropUnitCount(unitsById) + units > MAX_BOUND_DROP_UNITS) {
            return false;
        }
        unitsById.put(id, units);
        return true;
    }

    private static int boundDropUnitCount(Map<UUID, Integer> unitsById) {
        long total = 0L;
        for (Integer units : unitsById.values()) {
            if (units == null || units <= 0) {
                return Integer.MAX_VALUE;
            }
            total += units;
            if (total > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    private BlockPos safeObservedDropStand(AIPlayerEntity bot, ItemEntity drop) {
        if (!HarvestCore.isDropPhysicallySupported(bot, drop)) {
            return null;
        }
        Standability.clearCache();
        BlockPos stand = HarvestCore.pickupStandPos(bot, drop.getBlockPos());
        if (stand == null || !isObservablePickupStand(bot, stand)) {
            return null;
        }
        return safePickupCellRoute(bot, stand) ? stand : null;
    }

    private static boolean isObservablePickupStand(AIPlayerEntity bot, BlockPos stand) {
        if (stand.equals(bot.getBlockPos())) {
            return true;
        }
        return ObservableWorldQuery.canObserveCell(bot, stand)
                && ObservableWorldQuery.canObserveCell(bot, stand.up())
                && ObservableWorldQuery.canObserveBlock(bot, stand.down());
    }

    private boolean safePickupCellRoute(AIPlayerEntity bot, BlockPos destination) {
        if (destination == null || pickupReturnAnchor == null
                || destination.getY() < surfaceFloorY(bot)) {
            return false;
        }
        ServerWorld world = bot.getServerWorld();
        Standability.clearCache();
        if (!Standability.isStandable(world, destination)) {
            return false;
        }
        BlockPos current = bot.getBlockPos();
        if (!hasExactSurfaceRoute(world, current, destination, surfaceFloorY(bot))) {
            return false;
        }
        return hasExactSurfaceRoute(
                world, destination, pickupReturnAnchor, surfaceFloorY(bot));
    }

    private boolean approachKnownPickupCell(
            AIPlayerEntity bot, BlockPos itemPos) {
        Standability.clearCache();
        BlockPos stand = HarvestCore.pickupStandPos(bot, itemPos);
        return stand != null && safePickupCellRoute(bot, stand)
                && approachPickupStand(bot, stand, null);
    }

    private boolean approachPickupStand(
            AIPlayerEntity bot, BlockPos stand, Vec3d observedDropPosition) {
        if (pickupReturnAnchor == null || stand == null) {
            return false;
        }
        if (bot.getBlockPos().equals(stand)) {
            if (observedDropPosition == null) {
                bot.getActionPack().stopMovement();
            } else {
                io.github.zoyluo.aibot.mode.FakePlayerMotion.nudgeWithinBlockToward(
                        bot, stand, observedDropPosition, "physical_drop_pickup");
            }
            return true;
        }
        return startExactSurfacePath(
                bot, stand, surfaceFloorY(bot), pickupReturnAnchor)
                == SurfacePathStart.STARTED;
    }

    // 只猎确定掉生肉的成年 vanilla 动物。旧的“任意 AnimalEntity”兼容会把蜜蜂、青蛙等
    // 非食物生物当猎物，既浪费 200t 拾取预算又可能主动制造战斗；模组肉源应通过显式配置扩展。
    private static boolean isHuntable(LivingEntity entity) {
        return PREY.contains(entity.getType())
                && entity instanceof net.minecraft.entity.passive.AnimalEntity animal
                && !animal.isBaby();
    }

    private LivingEntity nearestPrey(AIPlayerEntity bot) {
        wetPreyRejectedUntil.entrySet().removeIf(entry -> entry.getValue() <= elapsed);
        unsafePreyRejectedUntil.entrySet().removeIf(entry -> entry.getValue() <= elapsed);
        Box box = bot.getBoundingBox().expand(SEARCH_RANGE);
        return bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, box,
                        entity -> entity.isAlive() && entity != bot && isHuntable(entity))
                .stream()
                .filter(entity -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .filter(entity -> !wetPreyRejectedUntil.containsKey(entity.getUuid()))
                .filter(entity -> !unsafePreyRejectedUntil.containsKey(entity.getUuid()))
                .filter(entity -> !EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUuid(), entity.getBlockPos(), bot.getServer().getTicks()))
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
    }

    boolean isWetPreyTemporarilyRejected(UUID preyId) {
        return preyId != null && wetPreyRejectedUntil.getOrDefault(preyId, -1) > elapsed;
    }

    private boolean startNextPickupSweepStep(AIPlayerEntity bot) {
        if (pickupOrigin == null) {
            return false;
        }
        ServerWorld world = bot.getServerWorld();
        for (int checked = 0; checked < PICKUP_SWEEP_OFFSETS.length; checked++) {
            int[] offset = PICKUP_SWEEP_OFFSETS[
                    Math.floorMod(pickupSweepCursor++, PICKUP_SWEEP_OFFSETS.length)];
            BlockPos candidate = pickupOrigin.add(offset[0], 0, offset[1]);
            Standability.clearCache();
            if (candidate.equals(bot.getBlockPos())
                    || !ObservableWorldQuery.canObserveCell(bot, candidate)
                    || !ObservableWorldQuery.canObserveCell(bot, candidate.up())
                    || !ObservableWorldQuery.canObserveBlock(bot, candidate.down())
                    || !Standability.isStandable(world, candidate)
                    || !safePickupCellRoute(bot, candidate)) {
                continue;
            }
            SurfacePathStart start = startExactSurfacePath(
                    bot, candidate, surfaceFloorY(bot), pickupReturnAnchor);
            if (start != SurfacePathStart.STARTED) {
                continue;
            }
            BotLog.action(bot, "hunt_pickup_observation_sweep",
                    "origin", pickupOrigin.toShortString(),
                    "to", candidate.toShortString(),
                    "step", pickupSweepCursor);
            return true;
        }
        return false;
    }

    private static long pickedUpAuxiliary(AIPlayerEntity bot) {
        long count = 0L;
        for (Item item : PREY_AUXILIARY_DROPS) {
            count += bot.getStatHandler().getStat(Stats.PICKED_UP, item);
        }
        return count;
    }

    private static Item expectedRawMeat(EntityType<?> preyType) {
        if (preyType == EntityType.COW) {
            return Items.BEEF;
        }
        if (preyType == EntityType.PIG) {
            return Items.PORKCHOP;
        }
        if (preyType == EntityType.SHEEP) {
            return Items.MUTTON;
        }
        if (preyType == EntityType.CHICKEN) {
            return Items.CHICKEN;
        }
        if (preyType == EntityType.RABBIT) {
            return Items.RABBIT;
        }
        return null;
    }

    private void restorePickup(AIPlayerEntity bot) {
        RestoreMetadata restored = restoredPickup;
        if (restored == null || restored.transactionState() != TransactionState.OPEN) {
            fail("hunt_pickup_invalid_checkpoint");
            checkpointDirty = true;
            return;
        }
        String liveDimension = dimension(bot);
        if (!restored.dimension().equals(liveDimension)) {
            fail("hunt_pickup_dimension_mismatch:expected=" + restored.dimension()
                    + ":actual=" + liveDimension);
            checkpointDirty = true;
            return;
        }
        long now = bot.getServerWorld().getTime();
        if (now < restored.pickupStartedWorldTime()) {
            fail("hunt_pickup_time_rollback:started=" + restored.pickupStartedWorldTime()
                    + ":current=" + now);
            checkpointDirty = true;
            return;
        }
        CombatCore.equipMelee(bot);
        meatBaseline = HarvestCore.countInventoryItems(bot, RAW_MEATS);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = (int) Math.min(Integer.MAX_VALUE,
                now - restored.pickupStartedWorldTime());
        pickupExpectedRawMeat = restored.expectedRawItem();
        pickupInventoryBaseline = restored.inventoryBaseline();
        pickupRawMeatStatBaseline = restored.pickupStatBaseline();
        targetAuxiliaryBaseline = restored.auxInventoryBaseline();
        targetAuxiliaryPickupBaseline = restored.auxPickupStatBaseline();
        pickupStartedWorldTime = restored.pickupStartedWorldTime();
        pickupDropUnits.clear();
        pickupDropUnits.putAll(restored.boundDropUnits());
        pickupDimension = restored.dimension();
        pickupTransactionState = TransactionState.OPEN;
        pickupOrigin = restored.pickupOrigin();
        pickupReturnAnchor = restored.pickupReturnAnchor();
        pickupSweepCursor = 0;
        clearRoamIntent();
        wetPreyRejectedUntil.clear();
        unsafePreyRejectedUntil.clear();
        clearTargetIntent();
        phase = Phase.PICKUP;
        checkpointDirty = false;
        bot.getActionPack().stopAll();
    }

    public boolean consumeCheckpointDirty() {
        boolean dirty = checkpointDirty;
        checkpointDirty = false;
        return dirty;
    }

    public boolean isSettlementOnly() {
        return settlementOnly;
    }

    public TransactionState transactionState() {
        return pickupTransactionState;
    }

    @Override
    public Map<String, String> checkpoint() {
        if (invalidCheckpoint || pickupTransactionState == null
                || pickupExpectedRawMeat == null || pickupOrigin == null
                || pickupReturnAnchor == null || pickupDimension.isBlank()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_schema", String.valueOf(PICKUP_CHECKPOINT_SCHEMA));
        values.put("cursor_kind", "hunt_pickup");
        values.put("transaction_state", pickupTransactionState.name());
        values.put("target_count", String.valueOf(targetMeat));
        values.put("require_full_quota", String.valueOf(requireFullQuota));
        values.put("dimension", pickupDimension);
        values.put("expected_raw_item",
                Registries.ITEM.getId(pickupExpectedRawMeat).toString());
        values.put("pickup_origin", encodeCheckpointPos(pickupOrigin));
        values.put("pickup_return_anchor", encodeCheckpointPos(pickupReturnAnchor));
        values.put("inventory_baseline", String.valueOf(pickupInventoryBaseline));
        values.put("pickup_stat_baseline", String.valueOf(pickupRawMeatStatBaseline));
        values.put("aux_inventory_baseline", String.valueOf(targetAuxiliaryBaseline));
        values.put("aux_pickup_stat_baseline",
                String.valueOf(targetAuxiliaryPickupBaseline));
        values.put("pickup_started_world_time",
                String.valueOf(pickupStartedWorldTime));
        values.put("bound_drop_units", encodeBoundDropUnits(pickupDropUnits));
        Map<String, String> encoded = Map.copyOf(values);
        return inspectCheckpoint(encoded).isPresent() ? encoded : Map.of();
    }

    public static Optional<RestoreMetadata> inspectCheckpoint(
            Map<String, String> checkpoint) {
        if (checkpoint == null || checkpoint.isEmpty()
                || !checkpoint.keySet().equals(PICKUP_CHECKPOINT_KEYS)) {
            return Optional.empty();
        }
        try {
            int schema = strictInt(required(checkpoint, "task_schema"));
            if (schema != PICKUP_CHECKPOINT_SCHEMA
                    || !"hunt_pickup".equals(required(checkpoint, "cursor_kind"))) {
                return Optional.empty();
            }
            TransactionState transactionState = TransactionState.valueOf(
                    required(checkpoint, "transaction_state"));
            int targetCount = strictInt(required(checkpoint, "target_count"));
            boolean fullQuota = strictBoolean(required(checkpoint, "require_full_quota"));
            String dimension = canonicalIdentifier(required(checkpoint, "dimension"));
            String itemId = canonicalIdentifier(required(checkpoint, "expected_raw_item"));
            Item expectedRaw = Registries.ITEM.getOptionalValue(
                    Identifier.of(itemId)).orElse(null);
            BlockPos origin = decodeCheckpointPos(
                    required(checkpoint, "pickup_origin")).orElse(null);
            BlockPos returnAnchor = decodeCheckpointPos(
                    required(checkpoint, "pickup_return_anchor")).orElse(null);
            int inventoryBaseline = strictInt(required(checkpoint, "inventory_baseline"));
            int pickupStatBaseline = strictInt(required(checkpoint, "pickup_stat_baseline"));
            int auxInventoryBaseline = strictInt(
                    required(checkpoint, "aux_inventory_baseline"));
            long auxPickupStatBaseline = strictLong(
                    required(checkpoint, "aux_pickup_stat_baseline"));
            long startedWorldTime = strictLong(
                    required(checkpoint, "pickup_started_world_time"));
            Optional<Map<UUID, Integer>> decodedUnits = decodeBoundDropUnits(
                    required(checkpoint, "bound_drop_units"));
            if (targetCount < 1 || targetCount > 4096
                    || dimension == null || expectedRaw == null
                    || !RAW_MEATS.contains(expectedRaw)
                    || origin == null || returnAnchor == null
                    || inventoryBaseline < 0 || inventoryBaseline > 4096
                    || pickupStatBaseline < 0 || auxInventoryBaseline < 0
                    || auxInventoryBaseline > 4096 || auxPickupStatBaseline < 0L
                    || startedWorldTime < 0L || decodedUnits.isEmpty()
                    || transactionState == TransactionState.CLOSED_NO_RAW
                    && !decodedUnits.orElseThrow().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RestoreMetadata(
                    transactionState, targetCount, fullQuota, dimension, expectedRaw,
                    origin, returnAnchor, inventoryBaseline, pickupStatBaseline,
                    auxInventoryBaseline, auxPickupStatBaseline, startedWorldTime,
                    decodedUnits.orElseThrow()));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public record RestoreMetadata(
            TransactionState transactionState,
            int targetCount,
            boolean requireFullQuota,
            String dimension,
            Item expectedRawItem,
            BlockPos pickupOrigin,
            BlockPos pickupReturnAnchor,
            int inventoryBaseline,
            int pickupStatBaseline,
            int auxInventoryBaseline,
            long auxPickupStatBaseline,
            long pickupStartedWorldTime,
            Map<UUID, Integer> boundDropUnits) {
        public RestoreMetadata {
            pickupOrigin = pickupOrigin.toImmutable();
            pickupReturnAnchor = pickupReturnAnchor.toImmutable();
            boundDropUnits = Map.copyOf(boundDropUnits);
        }

        public int boundUnits() {
            return boundDropUnitCount(boundDropUnits);
        }

        public boolean open() {
            return transactionState == TransactionState.OPEN;
        }
    }

    private static String encodeBoundDropUnits(Map<UUID, Integer> unitsById) {
        if (unitsById.isEmpty()) {
            return "none";
        }
        return unitsById.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(UUID::toString)))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private static Optional<Map<UUID, Integer>> decodeBoundDropUnits(String encoded) {
        if ("none".equals(encoded)) {
            return Optional.of(Map.of());
        }
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        Map<UUID, Integer> decoded = new LinkedHashMap<>();
        for (String pair : encoded.split(";", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            UUID id = UUID.fromString(parts[0]);
            if (!id.toString().equals(parts[0])) {
                return Optional.empty();
            }
            int units = strictInt(parts[1]);
            if (!bindDropUnits(decoded, id, units)) {
                return Optional.empty();
            }
        }
        Map<UUID, Integer> result = Map.copyOf(decoded);
        return encodeBoundDropUnits(result).equals(encoded)
                ? Optional.of(result) : Optional.empty();
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing_" + key);
        }
        return value;
    }

    private static int strictInt(String value) {
        int parsed = Integer.parseInt(value);
        if (!String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non_canonical_int");
        }
        return parsed;
    }

    private static long strictLong(String value) {
        long parsed = Long.parseLong(value);
        if (!String.valueOf(parsed).equals(value)) {
            throw new IllegalArgumentException("non_canonical_long");
        }
        return parsed;
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("non_canonical_boolean");
    }

    private static String canonicalIdentifier(String value) {
        String canonical = Identifier.of(value).toString();
        if (!canonical.equals(value)) {
            throw new IllegalArgumentException("non_canonical_identifier");
        }
        return canonical;
    }

    private static String encodeCheckpointPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static Optional<BlockPos> decodeCheckpointPos(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        String[] parts = encoded.split(",", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            BlockPos pos = new BlockPos(
                    strictInt(parts[0]), strictInt(parts[1]), strictInt(parts[2]));
            return encodeCheckpointPos(pos).equals(encoded)
                    ? Optional.of(pos) : Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    /** Factual net raw-food inventory used by GoalExecutor's HUNT-specific replan watermark. */
    public static int rawMeatCount(AIPlayerEntity bot) {
        return HarvestCore.countInventoryItems(bot, RAW_MEATS);
    }

    /** 周围是否有可猎动物——供饥饿链判断"值不值得派猎食任务",避免没动物时空派必失败。 */
    public static boolean hasPreyNearby(AIPlayerEntity bot) {
        return !bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(SEARCH_RANGE),
                        entity -> entity.isAlive() && entity != bot && isHuntable(entity))
                .stream()
                .filter(entity -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, entity))
                .toList()
                .isEmpty();
    }
}
