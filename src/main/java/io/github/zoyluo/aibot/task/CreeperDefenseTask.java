package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Continuous SAFETY ownership for one or more Creeper risks.
 *
 * <p>Every entity fact enters through an observable sixteen-block snapshot. Recent risks then
 * remain as UUID-keyed, position-only memories for one hundred ticks, so a LOS flicker or a weaker
 * second Creeper cannot release an armed source. Escape remains the normal tactic; close, armed or
 * genuinely stalled pressure escalates to a two-high owned blast wall without ever resuming the
 * interrupted mission on a failed placement.</p>
 */
public final class CreeperDefenseTask extends AbstractTask {
    private static final int CREEPER_SCAN_RANGE = 16;
    private static final int RISK_MEMORY_TICKS = 100;
    private static final int ESCAPE_DISTANCE = 12;
    private static final double ESCAPE_GOAL_REACHED_SQUARED = 6.25D;
    private static final double URGENT_DISTANCE_SQUARED = 4.0D * 4.0D;
    private static final double STALLED_WALL_DISTANCE_SQUARED = 7.0D * 7.0D;
    private static final double SAFE_LAST_SEEN_DISTANCE_SQUARED = 10.0D * 10.0D;
    private static final double AWAY_PROGRESS_DISTANCE = 0.35D;
    private static final float LATE_FUSE_PROGRESS = 0.45F;
    private static final int WALL_STALL_TICKS = 5;
    private static final int REPATH_STALL_TICKS = 10;
    private static final int WALL_BUILD_LIMIT = 20;
    private static final int WALL_RETRY_TICKS = 5;
    private static final int OWNER_WATCHDOG_TICKS = 2400;
    private static final int WATCHDOG_LOG_INTERVAL = 200;

    private enum Phase {
        ESCAPE,
        BUILD_CORE,
        HOLD_BARRIER
    }

    /** Assignment payload whose position was captured inside the same observation boundary. */
    record ObservedCreeper(UUID uuid, BlockPos pos) {
    }

    private record VisibleCreeper(CreeperEntity entity,
                                  UUID uuid,
                                  BlockPos pos,
                                  double distanceSquared,
                                  float fuseProgress,
                                  boolean fuseStarted,
                                  boolean lateFuse,
                                  boolean charged) {
        int riskRank() {
            if (lateFuse || charged && fuseStarted) {
                return 4;
            }
            if (fuseStarted) {
                return 3;
            }
            if (distanceSquared <= URGENT_DISTANCE_SQUARED) {
                return 3;
            }
            if (charged && distanceSquared <= STALLED_WALL_DISTANCE_SQUARED) {
                return 2;
            }
            return 1;
        }
    }

    private static final class RiskMemory {
        private final UUID uuid;
        private BlockPos pos;
        private double distanceSquared;
        private int lastSeenElapsed;
        private boolean fuseObserved;
        private boolean lateFuseObserved;
        private boolean chargedObserved;

        private RiskMemory(UUID uuid,
                           BlockPos pos,
                           double distanceSquared,
                           int lastSeenElapsed) {
            this.uuid = uuid;
            this.pos = pos.toImmutable();
            this.distanceSquared = distanceSquared;
            this.lastSeenElapsed = lastSeenElapsed;
        }

        private void observe(VisibleCreeper visible, int now) {
            pos = visible.pos();
            distanceSquared = visible.distanceSquared();
            lastSeenElapsed = now;
            // These are the latest factually visible states. If LOS is lost, the snapshot remains
            // unchanged for the grace window; if the same Creeper visibly defuses, stale armed
            // pressure must not stay latched forever.
            fuseObserved = visible.fuseStarted();
            lateFuseObserved = visible.lateFuse();
            chargedObserved = visible.charged();
        }

        private int riskRank() {
            if (lateFuseObserved || chargedObserved && fuseObserved) {
                return 4;
            }
            if (fuseObserved) {
                return 3;
            }
            if (distanceSquared <= URGENT_DISTANCE_SQUARED) {
                return 3;
            }
            if (chargedObserved
                    && distanceSquared <= STALLED_WALL_DISTANCE_SQUARED) {
                return 2;
            }
            return 1;
        }
    }

    private record RiskSelection(UUID uuid,
                                 BlockPos pos,
                                 double distanceSquared,
                                 int lastSeenElapsed,
                                 boolean fuseObserved,
                                 boolean lateFuseObserved,
                                 boolean chargedObserved,
                                 VisibleCreeper visible) {
    }

    private record StepCandidate(int dx, int dz, double awayScore) {
    }

    private final UUID initiallyObservedId;
    private final BlockPos initiallyObservedPos;
    private final Map<UUID, RiskMemory> recentRisks = new HashMap<>();
    private final Deque<BlockPos> coreTargets = new ArrayDeque<>();
    private final Deque<BlockPos> wingTargets = new ArrayDeque<>();
    private final Set<BlockPos> placedWallBlocks = new HashSet<>();

    private Phase phase = Phase.ESCAPE;
    private UUID trackedCreeperId;
    private BlockPos lastSeenPos;
    private int lastVisibleElapsed;
    private boolean rememberedFuse;
    private boolean rememberedLateFuse;
    private boolean rememberedCharged;
    private BlockPos escapeGoal;
    private Vec3d awayProgressAnchor;
    private int awayProgressElapsed;
    private int lastRepathElapsed;
    private int nextWallAttemptElapsed;
    private int nextOwnerWatchdogElapsed;
    private BlockPos barrierFeet;
    private Direction barrierTowardThreat;
    private int wallStartedElapsed;
    private int barrierHeldElapsed;
    private int wallPlacements;
    private boolean wingPlacementDisabled;
    private int currentlyVisibleRiskCount;

    /**
     * Compatibility admission for callers that already proved this exact entity observable.
     * Production scheduling uses the UUID snapshot overload below and never retains the entity.
     */
    public CreeperDefenseTask(CreeperEntity initiallyObserved, BlockPos initiallyObservedPos) {
        this(initiallyObserved == null ? null : initiallyObserved.getUuid(), initiallyObservedPos);
    }

    public CreeperDefenseTask(UUID initiallyObservedId, BlockPos initiallyObservedPos) {
        this.initiallyObservedId = initiallyObservedId;
        this.initiallyObservedPos = initiallyObservedPos == null
                ? null : initiallyObservedPos.toImmutable();
    }

    @Override
    public String name() {
        return "creeper_defense";
    }

    @Override
    public String describe() {
        return "Creeper defense phase=" + phase
                + " source=" + compact(lastSeenPos)
                + " source_id=" + trackedCreeperId
                + " escape=" + compact(escapeGoal)
                + " core_remaining=" + coreTargets.size()
                + " wing_remaining=" + wingTargets.size()
                + " wall_placed=" + wallPlacements
                + " hidden_ticks=" + hiddenTicks();
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case ESCAPE -> Math.min(0.60D, elapsed / 400.0D);
            case BUILD_CORE -> Math.min(0.82D, 0.60D + wallPlacements * 0.10D);
            case HOLD_BARRIER -> 0.90D;
        };
    }

    @Override
    public boolean isWaiting() {
        // This task owns tighter displacement, build and lifetime watchdogs.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.ESCAPE;
        trackedCreeperId = null;
        lastSeenPos = initiallyObservedPos;
        lastVisibleElapsed = 0;
        rememberedFuse = false;
        rememberedLateFuse = false;
        rememberedCharged = false;
        escapeGoal = null;
        coreTargets.clear();
        wingTargets.clear();
        placedWallBlocks.clear();
        barrierFeet = null;
        barrierTowardThreat = null;
        wallPlacements = 0;
        wingPlacementDisabled = false;
        currentlyVisibleRiskCount = 0;
        nextWallAttemptElapsed = 0;
        nextOwnerWatchdogElapsed = OWNER_WATCHDOG_TICKS;
        resetAwayProgress(bot);

        if (initiallyObservedId != null && initiallyObservedPos != null) {
            recentRisks.put(initiallyObservedId, new RiskMemory(
                    initiallyObservedId,
                    initiallyObservedPos,
                    bot.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(initiallyObservedPos)),
                    0));
        }
        RiskSelection risk = refreshRiskSelection(bot).orElse(null);
        applyRiskSelection(bot, risk);
        if (lastSeenPos == null) {
            failOwner(bot, "creeper_defense_missing_observed_source");
            return;
        }

        boolean synchronousWall = distanceToLastSeenSquared(bot) <= URGENT_DISTANCE_SQUARED
                || rememberedLateFuse
                || rememberedCharged && rememberedFuse;
        if (synchronousWall && beginWall(bot, risk)) {
            int coreAttempts = rememberedLateFuse
                    || rememberedCharged && rememberedFuse ? 2 : 1;
            for (int attempt = 0;
                 attempt < coreAttempts && phase == Phase.BUILD_CORE;
                 attempt++) {
                placeNextCoreBlock(bot, risk);
            }
            return;
        }
        startEscapePath(bot, risk, "initial");
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed >= nextOwnerWatchdogElapsed) {
            BotLog.danger(bot, "creeper_defense_watchdog_extended",
                    "phase", phase,
                    "source", lastSeenPos,
                    "source_id", trackedCreeperId,
                    "hidden_ticks", hiddenTicks(),
                    "distance_sq", distanceToLastSeenSquared(bot));
            nextOwnerWatchdogElapsed = elapsed + OWNER_WATCHDOG_TICKS;
            if (phase == Phase.ESCAPE) {
                startEscapePath(bot, null, "owner_watchdog_repath");
            }
        }
        if (elapsed % WATCHDOG_LOG_INTERVAL == 0) {
            BotLog.danger(bot, "creeper_defense_watchdog",
                    "phase", phase,
                    "source", lastSeenPos,
                    "source_id", trackedCreeperId,
                    "recent_risks", recentRisks.size(),
                    "hidden_ticks", hiddenTicks(),
                    "distance_sq", distanceToLastSeenSquared(bot),
                    "wall_placed", wallPlacements);
        }

        RiskSelection risk = refreshRiskSelection(bot).orElse(null);
        boolean ownerChanged = applyRiskSelection(bot, risk);
        if (ownerChanged) {
            resetAwayProgress(bot);
            if (phase == Phase.ESCAPE) {
                startEscapePath(bot, risk, "risk_owner_changed");
            }
        }

        // A wall is tied to one remembered ray. Revalidate that geometry every tick, not only
        // when a UUID changes: the same Creeper can walk around the side of a completed column.
        if (phase != Phase.ESCAPE && !barrierFaces(bot, lastSeenPos)) {
            fallbackToEscape(bot, risk, "creeper_wall_direction_changed");
        }

        switch (phase) {
            case ESCAPE -> tickEscape(bot, risk);
            case BUILD_CORE -> tickCoreBuild(bot, risk);
            case HOLD_BARRIER -> tickBarrierHold(bot, risk);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }

    private void tickEscape(AIPlayerEntity bot, RiskSelection risk) {
        updateAwayProgress(bot);
        if (canCompleteAfterRiskGrace(bot, risk)) {
            completeOwner(bot, "last_seen_clearance");
            return;
        }

        double sourceDistanceSquared = distanceToLastSeenSquared(bot);
        int stalledTicks = elapsed - awayProgressElapsed;
        boolean wallUrgent = sourceDistanceSquared <= URGENT_DISTANCE_SQUARED
                || rememberedFuse
                || rememberedCharged
                && sourceDistanceSquared <= STALLED_WALL_DISTANCE_SQUARED
                || (sourceDistanceSquared <= STALLED_WALL_DISTANCE_SQUARED
                && stalledTicks >= WALL_STALL_TICKS);
        if (wallUrgent
                && elapsed >= nextWallAttemptElapsed
                && beginWall(bot, risk)) {
            placeNextCoreBlock(bot, risk);
            if (phase == Phase.BUILD_CORE
                    && (rememberedLateFuse || rememberedCharged && rememberedFuse)) {
                placeNextCoreBlock(bot, risk);
            }
            return;
        }

        boolean reachedGoal = escapeGoal != null
                && bot.getBlockPos().getSquaredDistance(escapeGoal)
                <= ESCAPE_GOAL_REACHED_SQUARED;
        if (reachedGoal
                || bot.getActionPack().isPathExecutorIdle()
                || stalledTicks >= REPATH_STALL_TICKS) {
            if (elapsed - lastRepathElapsed >= WALL_RETRY_TICKS) {
                startEscapePath(bot, risk,
                        stalledTicks >= REPATH_STALL_TICKS
                                ? "away_displacement_stalled" : "route_boundary");
            }
        } else {
            bot.getActionPack().setSprinting(true);
        }
    }

    private boolean beginWall(AIPlayerEntity bot, RiskSelection risk) {
        bot.getActionPack().stopAll();
        BlockPos origin = bot.getBlockPos().toImmutable();
        boolean stepped = tryFastStepAway(bot, lastSeenPos);
        BlockPos retreatFeet = bot.getBlockPos().toImmutable();
        Direction towardThreat = dominantDirectionToward(retreatFeet, lastSeenPos);
        if (towardThreat == null) {
            fallbackToEscape(bot, risk, "creeper_wall_missing_direction");
            return false;
        }

        BlockPos center = retreatFeet.offset(towardThreat).toImmutable();
        if (intersectsBot(bot, center)) {
            fallbackToEscape(bot, risk, "creeper_wall_center_intersects_bot");
            return false;
        }

        coreTargets.clear();
        wingTargets.clear();
        placedWallBlocks.clear();
        wallPlacements = 0;
        wingPlacementDisabled = false;
        barrierFeet = center;
        barrierTowardThreat = towardThreat;
        addColumn(coreTargets, center);
        for (Direction side : orderedBarrierSides(retreatFeet, lastSeenPos, towardThreat)) {
            BlockPos sideFeet = center.offset(side).toImmutable();
            if (!sideFeet.equals(retreatFeet) && !intersectsBot(bot, sideFeet)) {
                addColumn(wingTargets, sideFeet);
            }
        }

        phase = Phase.BUILD_CORE;
        wallStartedElapsed = elapsed;
        escapeGoal = null;
        resetAwayProgress(bot);
        BotLog.danger(bot, "creeper_wall_started",
                "origin", origin,
                "retreat", retreatFeet,
                "stepped", stepped,
                "source", lastSeenPos,
                "source_id", trackedCreeperId,
                "center", barrierFeet,
                "toward", barrierTowardThreat,
                "core_targets", coreTargets.size(),
                "wing_targets", wingTargets.size());
        return true;
    }

    private void tickCoreBuild(AIPlayerEntity bot, RiskSelection risk) {
        if (elapsed - wallStartedElapsed > WALL_BUILD_LIMIT) {
            fallbackToEscape(bot, risk, "creeper_wall_core_timeout");
            return;
        }
        if (!ownedCorePrefixMaintained(bot)) {
            fallbackToEscape(bot, risk, "creeper_wall_core_prefix_lost");
            return;
        }
        placeNextCoreBlock(bot, risk);
    }

    private void placeNextCoreBlock(AIPlayerEntity bot, RiskSelection risk) {
        if (coreTargets.isEmpty()) {
            enterBarrierHold(bot);
            return;
        }
        BlockPos target = coreTargets.peek();
        String failure = placeOwnedWallBlock(bot, target);
        if (failure != null) {
            fallbackToEscape(bot, risk, "creeper_wall_core_" + failure);
            return;
        }
        coreTargets.remove();
        if (coreTargets.isEmpty()) {
            enterBarrierHold(bot);
        }
    }

    private void enterBarrierHold(AIPlayerEntity bot) {
        if (!maintainedCoreBarrier(bot)) {
            phase = Phase.ESCAPE;
            nextWallAttemptElapsed = elapsed + WALL_RETRY_TICKS;
            BotLog.danger(bot, "creeper_wall_core_unproven",
                    "center", barrierFeet,
                    "source", lastSeenPos);
            startEscapePath(bot, null, "creeper_wall_core_unproven");
            return;
        }
        phase = Phase.HOLD_BARRIER;
        barrierHeldElapsed = elapsed;
        bot.getActionPack().stopAll();
        BotLog.danger(bot, "creeper_wall_core_complete",
                "center", barrierFeet,
                "placed", wallPlacements,
                "wing_targets", wingTargets.size());
    }

    private void tickBarrierHold(AIPlayerEntity bot, RiskSelection risk) {
        bot.getActionPack().stopAll();
        if (!maintainedCoreBarrier(bot)) {
            fallbackToEscape(bot, risk, "creeper_wall_barrier_lost");
            return;
        }
        if (canCompleteAfterRiskGrace(bot, risk)) {
            completeOwner(bot, "owned_barrier_or_clearance");
            return;
        }

        if (!wingPlacementDisabled && !wingTargets.isEmpty()) {
            BlockPos target = wingTargets.peek();
            String failure = placeOwnedWallBlock(bot, target);
            if (failure == null) {
                wingTargets.remove();
            } else {
                // Wings improve diagonal coverage but never own the minimum safety boundary.
                // Missing material/support here must not abandon the proven two-high core.
                wingPlacementDisabled = true;
                wingTargets.clear();
                BotLog.danger(bot, "creeper_wall_wings_abandoned",
                        "reason", failure,
                        "center", barrierFeet,
                        "source", lastSeenPos);
            }
        }

        boolean occluded = ownedWallOccludesLastSeen(bot);
        boolean selectedVisible = risk != null && risk.visible() != null;
        if ((!occluded || selectedVisible && !rememberedFuse)
                && elapsed - barrierHeldElapsed >= WALL_RETRY_TICKS) {
            fallbackToEscape(bot, risk, "creeper_wall_occlusion_unproven");
        }
    }

    private String placeOwnedWallBlock(AIPlayerEntity bot, BlockPos target) {
        if (intersectsBot(bot, target)) {
            return "target_intersects_bot";
        }
        OptionalInt slot = MaterialPalette.pickEmergencyShelterBlockSlot(bot);
        if (slot.isEmpty()) {
            return "missing_material";
        }
        if (InventoryAction.equipFromSlot(bot, slot.getAsInt()) < 0) {
            return "equip_failed";
        }
        ActionResult result = BuildAction.placeBlockAt(bot, target);
        if (!result.isSuccess()) {
            return "place_failed:" + result.reason();
        }
        if (!isObservablePhysicalBarrierCell(bot, target)) {
            return "placed_block_unobservable_or_nonphysical";
        }
        placedWallBlocks.add(target.toImmutable());
        wallPlacements++;
        BotLog.action(bot, "creeper_wall_block_placed",
                "target", target,
                "core_remaining", coreTargets.size(),
                "wing_remaining", wingTargets.size(),
                "center", barrierFeet);
        return null;
    }

    private void fallbackToEscape(AIPlayerEntity bot,
                                  RiskSelection risk,
                                  String reason) {
        bot.getActionPack().stopAll();
        phase = Phase.ESCAPE;
        escapeGoal = null;
        coreTargets.clear();
        wingTargets.clear();
        nextWallAttemptElapsed = elapsed + WALL_RETRY_TICKS;
        resetAwayProgress(bot);
        BotLog.danger(bot, "creeper_wall_fallback_to_escape",
                "reason", reason,
                "source", lastSeenPos,
                "source_id", trackedCreeperId,
                "wall_placed", wallPlacements);
        startEscapePath(bot, risk, reason);
    }

    private boolean startEscapePath(AIPlayerEntity bot,
                                    RiskSelection risk,
                                    String reason) {
        LivingEntity visibleSource = risk == null || risk.visible() == null
                ? null : risk.visible().entity();
        escapeGoal = EvadeTask.admitBestSurfaceEscapePath(
                bot, visibleSource, lastSeenPos, ESCAPE_DISTANCE);
        lastRepathElapsed = elapsed;
        resetAwayProgress(bot);
        if (escapeGoal == null) {
            BotLog.danger(bot, "creeper_defense_escape_admission_failed",
                    "reason", reason,
                    "source", lastSeenPos,
                    "source_id", trackedCreeperId,
                    "hidden_ticks", hiddenTicks());
            return false;
        }
        BotLog.action(bot, "creeper_defense_escape_started",
                "reason", reason,
                "source", lastSeenPos,
                "source_id", trackedCreeperId,
                "goal", escapeGoal,
                "hidden_ticks", hiddenTicks());
        return true;
    }

    private Optional<RiskSelection> refreshRiskSelection(AIPlayerEntity bot) {
        Map<UUID, VisibleCreeper> visibleById = new HashMap<>();
        List<VisibleCreeper> visibleSnapshots = observableCreeperSnapshots(bot);
        currentlyVisibleRiskCount = visibleSnapshots.size();
        for (VisibleCreeper visible : visibleSnapshots) {
            visibleById.put(visible.uuid(), visible);
            recentRisks.computeIfAbsent(
                            visible.uuid(),
                            ignored -> new RiskMemory(
                                    visible.uuid(),
                                    visible.pos(),
                                    visible.distanceSquared(),
                                    elapsed))
                    .observe(visible, elapsed);
        }
        for (RiskMemory memory : recentRisks.values()) {
            // The source position is remembered, but the Bot's own position is always current.
            // Recompute clearance before ranking so movement cannot leave a stale "near/far"
            // ordering across multiple remembered Creepers.
            memory.distanceSquared = bot.getPos().squaredDistanceTo(
                    Vec3d.ofBottomCenter(memory.pos));
        }
        // Grace expiry alone is not a safety proof. Keep every close, unoccluded memory until the
        // Bot has physically cleared that source (or its own maintained wall blocks that exact
        // remembered ray), otherwise a safer selected Creeper could make us forget a second one.
        recentRisks.entrySet().removeIf(entry ->
                elapsed - entry.getValue().lastSeenElapsed > RISK_MEMORY_TICKS
                        && rememberedRiskSafe(bot, entry.getValue()));
        return recentRisks.values().stream()
                .min(Comparator
                        .comparingInt(RiskMemory::riskRank).reversed()
                        .thenComparingDouble(memory -> memory.distanceSquared)
                        .thenComparing(Comparator.comparingInt(
                                (RiskMemory memory) -> memory.lastSeenElapsed).reversed()))
                .map(memory -> new RiskSelection(
                        memory.uuid,
                        memory.pos,
                        memory.distanceSquared,
                        memory.lastSeenElapsed,
                        memory.fuseObserved,
                        memory.lateFuseObserved,
                        memory.chargedObserved,
                        visibleById.get(memory.uuid)));
    }

    private boolean applyRiskSelection(AIPlayerEntity bot, RiskSelection risk) {
        if (risk == null) {
            return false;
        }
        UUID previous = trackedCreeperId;
        trackedCreeperId = risk.uuid();
        lastSeenPos = risk.pos();
        lastVisibleElapsed = risk.lastSeenElapsed();
        rememberedFuse = risk.fuseObserved();
        rememberedLateFuse = risk.lateFuseObserved();
        rememberedCharged = risk.chargedObserved();
        boolean changed = previous != null && !previous.equals(trackedCreeperId);
        if (changed) {
            BotLog.danger(bot, "creeper_defense_risk_owner_changed",
                    "from", previous,
                    "to", trackedCreeperId,
                    "source", lastSeenPos,
                    "rank", riskRank(risk),
                    "distance_sq", risk.distanceSquared());
        }
        return changed;
    }

    private static int riskRank(RiskSelection risk) {
        if (risk.lateFuseObserved()
                || risk.chargedObserved() && risk.fuseObserved()) {
            return 4;
        }
        if (risk.fuseObserved()) {
            return 3;
        }
        if (risk.distanceSquared() <= URGENT_DISTANCE_SQUARED) {
            return 3;
        }
        if (risk.chargedObserved()
                && risk.distanceSquared() <= STALLED_WALL_DISTANCE_SQUARED) {
            return 2;
        }
        return 1;
    }

    private static List<VisibleCreeper> observableCreeperSnapshots(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getEntitiesByClass(
                        CreeperEntity.class,
                        bot.getBoundingBox().expand(CREEPER_SCAN_RANGE),
                        entity -> ObservableWorldQuery.canObserveEntity(bot, entity))
                .stream()
                .filter(CreeperEntity::isAlive)
                .map(entity -> {
                    float fuseProgress = entity.getClientFuseTime(1.0F);
                    boolean fuseStarted = entity.isIgnited()
                            || entity.getFuseSpeed() > 0
                            || fuseProgress > 0.0F;
                    boolean charged = entity.isCharged();
                    return new VisibleCreeper(
                            entity,
                            entity.getUuid(),
                            entity.getBlockPos().toImmutable(),
                            bot.squaredDistanceTo(entity),
                            fuseProgress,
                            fuseStarted,
                            fuseProgress >= LATE_FUSE_PROGRESS,
                            charged);
                })
                .toList();
    }

    static Optional<ObservedCreeper> selectObservableCreeper(AIPlayerEntity bot) {
        return observableCreeperSnapshots(bot).stream()
                .min(Comparator
                        .comparingInt(VisibleCreeper::riskRank).reversed()
                        .thenComparingDouble(VisibleCreeper::distanceSquared))
                .map(visible -> new ObservedCreeper(visible.uuid(), visible.pos()));
    }

    private boolean tryFastStepAway(AIPlayerEntity bot, BlockPos source) {
        if (source == null) {
            return false;
        }
        BlockPos from = bot.getBlockPos();
        double awayX = bot.getX() - (source.getX() + 0.5D);
        double awayZ = bot.getZ() - (source.getZ() + 0.5D);
        List<StepCandidate> candidates = new ArrayList<>();
        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            int dx = direction.getOffsetX();
            int dz = direction.getOffsetZ();
            double score = dx * awayX + dz * awayZ;
            if (score >= -1.0E-6D) {
                candidates.add(new StepCandidate(dx, dz, score));
            }
        }
        candidates.sort(Comparator
                .comparingDouble(StepCandidate::awayScore).reversed()
                .thenComparingInt(candidate -> Math.abs(candidate.dx()) + Math.abs(candidate.dz())));
        for (StepCandidate candidate : candidates) {
            BlockPos target = from.add(candidate.dx(), 0, candidate.dz());
            if (ObservableWorldQuery.canObserveCell(bot, target)
                    && ObservableWorldQuery.canObserveCell(bot, target.up())
                    && ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, target.down())
                    && FakePlayerMotion.stepToStandable(bot, target, "creeper_fast_step_away")) {
                bot.setVelocity(Vec3d.ZERO);
                bot.fallDistance = 0.0F;
                bot.setOnGround(true);
                return true;
            }
        }
        return false;
    }

    private static void addColumn(Deque<BlockPos> targets, BlockPos feet) {
        targets.add(feet.toImmutable());
        targets.add(feet.up().toImmutable());
    }

    private static List<Direction> orderedBarrierSides(BlockPos retreatFeet,
                                                       BlockPos source,
                                                       Direction towardThreat) {
        Direction first;
        Direction second;
        if (towardThreat.getAxis() == Direction.Axis.X) {
            int residual = source.getZ() - retreatFeet.getZ();
            first = residual >= 0 ? Direction.SOUTH : Direction.NORTH;
            second = first.getOpposite();
        } else {
            int residual = source.getX() - retreatFeet.getX();
            first = residual >= 0 ? Direction.EAST : Direction.WEST;
            second = first.getOpposite();
        }
        return List.of(first, second);
    }

    private static Direction dominantDirectionToward(BlockPos from, BlockPos source) {
        if (source == null) {
            return null;
        }
        int dx = source.getX() - from.getX();
        int dz = source.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean barrierFaces(AIPlayerEntity bot, BlockPos source) {
        if (barrierFeet == null || source == null) {
            return false;
        }
        Direction currentToward = dominantDirectionToward(bot.getBlockPos(), source);
        return currentToward != null
                && currentToward == barrierTowardThreat
                && barrierFeet.equals(bot.getBlockPos().offset(currentToward));
    }

    private boolean ownedCorePrefixMaintained(AIPlayerEntity bot) {
        if (barrierFeet == null) {
            return false;
        }
        for (BlockPos core : List.of(barrierFeet, barrierFeet.up())) {
            if (placedWallBlocks.contains(core)
                    && !isObservablePhysicalBarrierCell(bot, core)) {
                return false;
            }
        }
        return true;
    }

    private boolean maintainedCoreBarrier(AIPlayerEntity bot) {
        return barrierFeet != null
                && placedWallBlocks.contains(barrierFeet)
                && placedWallBlocks.contains(barrierFeet.up())
                && isObservablePhysicalBarrierCell(bot, barrierFeet)
                && isObservablePhysicalBarrierCell(bot, barrierFeet.up());
    }

    private static boolean isObservablePhysicalBarrierCell(AIPlayerEntity bot, BlockPos pos) {
        if (!ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos)) {
            return false;
        }
        var state = bot.getServerWorld().getBlockState(pos);
        return !state.getCollisionShape(bot.getServerWorld(), pos).isEmpty();
    }

    private static boolean intersectsBot(AIPlayerEntity bot, BlockPos feet) {
        return bot.getBoundingBox().intersects(new Box(feet))
                || bot.getBoundingBox().intersects(new Box(feet.up()));
    }

    private boolean ownedWallOccludesLastSeen(AIPlayerEntity bot) {
        return ownedWallOccludes(bot, lastSeenPos);
    }

    private boolean ownedWallOccludes(AIPlayerEntity bot, BlockPos source) {
        if (source == null || !maintainedCoreBarrier(bot)) {
            return false;
        }
        Vec3d rememberedEye = Vec3d.ofCenter(source).add(0.0D, 0.75D, 0.0D);
        BlockHitResult hit = bot.getServerWorld().raycast(new RaycastContext(
                bot.getEyePos(),
                rememberedEye,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                bot));
        return hit.getType() == HitResult.Type.BLOCK
                && placedWallBlocks.contains(hit.getBlockPos())
                && isObservablePhysicalBarrierCell(bot, hit.getBlockPos());
    }

    private void updateAwayProgress(AIPlayerEntity bot) {
        if (awayProgressAnchor == null || lastSeenPos == null) {
            resetAwayProgress(bot);
            return;
        }
        Vec3d source = Vec3d.ofCenter(lastSeenPos);
        Vec3d away = new Vec3d(
                awayProgressAnchor.x - source.x,
                0.0D,
                awayProgressAnchor.z - source.z);
        if (away.lengthSquared() < 1.0E-6D) {
            return;
        }
        Vec3d displacement = bot.getPos().subtract(awayProgressAnchor);
        double outward = displacement.x * away.x / Math.sqrt(away.lengthSquared())
                + displacement.z * away.z / Math.sqrt(away.lengthSquared());
        if (outward >= AWAY_PROGRESS_DISTANCE) {
            resetAwayProgress(bot);
        }
    }

    private void resetAwayProgress(AIPlayerEntity bot) {
        awayProgressAnchor = bot.getPos();
        awayProgressElapsed = elapsed;
    }

    private boolean canCompleteAfterRiskGrace(AIPlayerEntity bot, RiskSelection risk) {
        if (currentlyVisibleRiskCount > 0) {
            return false;
        }
        if (!recentRisks.isEmpty()) {
            for (RiskMemory memory : recentRisks.values()) {
                if (elapsed - memory.lastSeenElapsed < RISK_MEMORY_TICKS
                        || !rememberedRiskSafe(bot, memory)) {
                    return false;
                }
            }
            return true;
        }
        if (hiddenTicks() < RISK_MEMORY_TICKS) {
            return false;
        }
        return distanceToLastSeenSquared(bot) >= SAFE_LAST_SEEN_DISTANCE_SQUARED
                || ownedWallOccludesLastSeen(bot);
    }

    private boolean rememberedRiskSafe(AIPlayerEntity bot, RiskMemory memory) {
        return memory.distanceSquared >= SAFE_LAST_SEEN_DISTANCE_SQUARED
                || ownedWallOccludes(bot, memory.pos);
    }

    private int hiddenTicks() {
        return Math.max(0, elapsed - lastVisibleElapsed);
    }

    private double distanceToLastSeenSquared(AIPlayerEntity bot) {
        return lastSeenPos == null
                ? Double.POSITIVE_INFINITY
                : bot.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(lastSeenPos));
    }

    private void completeOwner(AIPlayerEntity bot, String reason) {
        bot.getActionPack().stopAll();
        DangerWatcher.INSTANCE.noteEvadeCompleted(bot);
        BotLog.danger(bot, "creeper_defense_completed",
                "reason", reason,
                "source", lastSeenPos,
                "source_id", trackedCreeperId,
                "hidden_ticks", hiddenTicks(),
                "distance_sq", distanceToLastSeenSquared(bot),
                "occluded", ownedWallOccludesLastSeen(bot));
        complete();
    }

    private void failOwner(AIPlayerEntity bot, String reason) {
        bot.getActionPack().stopAll();
        BotLog.danger(bot, "creeper_defense_failed",
                "reason", reason,
                "phase", phase,
                "source", lastSeenPos,
                "source_id", trackedCreeperId,
                "hidden_ticks", hiddenTicks(),
                "wall_placed", wallPlacements);
        fail(reason);
    }

    private static String compact(BlockPos pos) {
        return pos == null ? "(none)"
                : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
