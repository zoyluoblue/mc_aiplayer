package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;

public final class EmergencyShelterTask extends AbstractTask {
    /** Four optional foundations, eight side cells, one roof support and one center roof. */
    public static final int MAX_PLACEMENT_BLOCKS = 14;
    private static final int NO_PROGRESS_LIMIT = 20;
    private static final int ANCHOR_RECOVERY_LIMIT = 20;
    private static final int BUILD_LIMIT = 120;
    private static final int EXIT_LIMIT = 500;
    private static final int MIN_HOLD_TICKS = 100;
    private static final int DAYLIGHT_GRACE_TICKS = 100;
    private static final int LOW_HEALTH_HOLD_LIMIT = 600;
    private static final float SAFE_EXIT_HEALTH = 18.0F;
    private static final String ENVIRONMENTAL_ESCAPE_REQUIRED =
            "shelter_environmental_escape_required";
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    private enum Phase {
        BUILD,
        HOLD,
        OPEN_EXIT,
        STEP_OUT
    }

    private record ShelterPlan(BlockPos egressFeet,
                               BlockPos roofSupport,
                               BlockPos roofSupportBase,
                               List<BlockPos> targets) {
    }

    private final Queue<BlockPos> targets = new LinkedList<>();
    private final Map<BlockPos, BlockState> ownedPlacements = new LinkedHashMap<>();
    private final Set<Direction> rejectedEgress = EnumSet.noneOf(Direction.class);
    private final Set<Direction> pressuredEgress = EnumSet.noneOf(Direction.class);
    private final BlockMiner exitMiner = new BlockMiner();
    private int placed;
    private int lastProgressTick;
    private int phaseStartedElapsed;
    private int exitStartedElapsed;
    private int anchorRecoveryStartedElapsed;
    private BlockPos shelterFeet;
    private BlockPos roofSupport;
    private BlockPos roofSupportBase;
    private BlockPos egressFeet;
    private BlockPos exitMiningTarget;
    private boolean elevatedForRoofSupport;
    private boolean surfaceShelter;
    private boolean forcePressureExit;
    private int consecutiveDaylightTicks;
    private int observationReseals;
    private EatTask holdEatTask;
    private Phase phase = Phase.BUILD;
    private String pendingFailure;
    private boolean terminalRecorded;

    @Override
    public String name() {
        return "shelter";
    }

    @Override
    public String describe() {
        return "Emergency shelter phase=" + phase + " placed=" + placed
                + " remaining=" + targets.size()
                + " observation_reseals=" + observationReseals
                + " pressured_egress=" + pressuredEgress.size()
                + " daylight_ticks=" + consecutiveDaylightTicks
                + " exit_age=" + exitAge()
                + " force_pressure_exit=" + forcePressureExit;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case BUILD -> {
                int total = placed + targets.size();
                yield total == 0 ? 0.0D : Math.min(0.65D, (double) placed / total * 0.65D);
            }
            case HOLD -> 0.75D;
            case OPEN_EXIT -> 0.9D;
            case STEP_OUT -> 0.95D;
        };
    }

    @Override
    public boolean isWaiting() {
        // Every phase owns a tighter domain watchdog. A generic stuck abort cannot repay the
        // asynchronous owned-block exit debt and would recreate the sealed terminal state.
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        targets.clear();
        ownedPlacements.clear();
        rejectedEgress.clear();
        pressuredEgress.clear();
        placed = 0;
        lastProgressTick = 0;
        phaseStartedElapsed = 0;
        exitStartedElapsed = -1;
        anchorRecoveryStartedElapsed = -1;
        phase = Phase.BUILD;
        pendingFailure = null;
        terminalRecorded = false;
        exitMiningTarget = null;
        surfaceShelter = false;
        forcePressureExit = false;
        consecutiveDaylightTicks = 0;
        observationReseals = 0;
        holdEatTask = null;
        if (!canStartAtCurrentPose(bot)) {
            failShelter(bot, "shelter_origin_not_stable");
            return;
        }
        BlockPos feet = bot.getBlockPos();
        // A path/evade owner can stop with its BlockPos still equal to this cell while the body
        // box straddles an edge and retains horizontal velocity. Vanilla placement then rejects
        // the adjacent wall because it would collide with the player itself. Establish the same
        // stationary, centered pose a real client reports before beginning the fixed enclosure.
        if (!isCenteredAnchorEntitySpaceClear(bot, feet)
                || !FakePlayerMotion.returnToBlockCenter(bot, feet, "shelter_anchor_settle")) {
            failShelter(bot, "shelter_origin_not_centerable");
            return;
        }
        // Capture this before the shelter itself adds a roof. Nearby no-leaves terrain height
        // keeps a shallow canopy or overhang in the surface-night transaction while the Y floor
        // excludes an open deep mine that happens to have a vertical view of the sky.
        surfaceShelter = isSurfaceShelterAnchor(bot, feet);
        shelterFeet = feet.toImmutable();
        Optional<ShelterPlan> planned = planShelter(bot, shelterFeet);
        if (planned.isEmpty()) {
            failShelter(bot, "shelter_no_owned_egress");
            return;
        }
        ShelterPlan plan = planned.orElseThrow();
        egressFeet = plan.egressFeet();
        roofSupport = plan.roofSupport();
        roofSupportBase = plan.roofSupportBase();
        elevatedForRoofSupport = false;
        targets.addAll(plan.targets());
        int required = requiredShelterBlocks(bot, plan);
        int available = countShelterBlocks(bot);
        if (available < required) {
            failShelter(bot,
                    "missing shelter_blocks required=" + required + " available=" + available);
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (handleEnvironmentalOwnershipConflict(bot)) {
            return;
        }
        if (surfaceShelter && !bot.getServerWorld().isDay()) {
            consecutiveDaylightTicks = 0;
        }
        switch (phase) {
            case BUILD -> tickBuild(bot);
            case HOLD -> tickHold(bot);
            case OPEN_EXIT -> tickOpenExit(bot);
            case STEP_OUT -> tickStepOut(bot);
        }
    }

    private void tickBuild(AIPlayerEntity bot) {
        if (phaseAge() > BUILD_LIMIT) {
            BlockPos blockedTarget = targets.peek();
            if (blockedTarget != null
                    && placementBlockingHostile(bot, blockedTarget).isPresent()) {
                rejectBlockedPlacementDirection(directionTo(blockedTarget));
            }
            if (elevatedForRoofSupport) {
                returnToShelterFeet(bot);
                elevatedForRoofSupport = false;
            }
            beginExit(bot, "shelter_timeout placed=" + placed + " remaining=" + targets.size());
            return;
        }
        if (elevatedForRoofSupport) {
            placeRoofSupportFromRaisedView(bot);
            return;
        }
        if (!recoverAnchorOrRelease(bot)) {
            return;
        }
        // Only remove a target after the world proves it is sealed. The old unconditional poll
        // discarded unsupported roof/side placements even when BuildAction failed, publishing a
        // COMPLETED shelter with holes that skeleton arrows could still cross.
        String lastFailure = "none";
        while (!targets.isEmpty()) {
            BlockPos target = targets.peek();
            if (isSealed(bot, target)) {
                targets.poll();
                lastProgressTick = elapsed;
                continue;
            }
            if (settleAnchorBeforePlacement(bot, target)) {
                return;
            }
            if (handlePlacementBlockingHostile(bot, target)) {
                return;
            }
            OptionalInt blockSlot = findShelterBlockSlot(bot);
            if (blockSlot.isEmpty()) {
                beginExit(bot, "missing shelter_block");
                return;
            }
            if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
                beginExit(bot, "cannot_equip_shelter_block");
                return;
            }
            if (target.equals(roofSupport)
                    && isSealed(bot, roofSupportBase)
                    && FakePlayerMotion.jumpTo(bot, shelterFeet.up(), "shelter_roof_support_view")) {
                elevatedForRoofSupport = true;
                lastProgressTick = elapsed;
                return;
            }
            ActionResult result = isFoundation(target)
                    ? placeFoundationFromEdge(bot, target)
                    : BuildAction.placeBlockAt(bot, target);
            if (result.isSuccess() && isSealed(bot, target)) {
                targets.poll();
                rememberPlacement(bot, target);
                placed++;
                lastProgressTick = elapsed;
                break; // one physical placement per tick
            }
            lastFailure = result.reason();
            // Every queue element is a prerequisite for the element after it: foundations precede
            // side feet, each side foot precedes its head, roofSupportBase precedes roofSupport,
            // and center roof is last. Retrying the head preserves that dependency graph; rotating
            // a failed target lets the roof occupy the jump clearance before its support exists.
            break;
        }
        if (targets.isEmpty()) {
            if (!isEnvelopeSealed(bot)) {
                beginExit(bot, "shelter_envelope_not_sealed");
                return;
            }
            phase = Phase.HOLD;
            phaseStartedElapsed = elapsed;
            bot.getActionPack().stopAll();
            BotLog.action(bot, "shelter_hold_started", "placed", placed);
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            beginExit(bot, "shelter_unsealable:" + lastFailure);
        }
    }

    /**
     * Re-centres only when the next full-cube wall actually overlaps the bot. Admission already
     * clears residual path velocity; treating any later sub-pixel velocity as displacement would
     * misclassify the ordinary roof-support landing before its vertical motion settles. This
     * remains a same-cell, support-checked fake-client move and never crosses a block boundary.
     * A {@code true} result means the build tick must stop; a successful return deliberately
     * yields {@code false} so blocker inspection and placement can continue atomically this tick.
     */
    private boolean settleAnchorBeforePlacement(AIPlayerEntity bot, BlockPos target) {
        // Only the horizontal foot/head envelope can be invaded by edge drift. The center roof is
        // deliberately placed immediately after the raised-view transaction; its vertical faces
        // can differ by floating-point contact while gravity settles, without representing the
        // side-cell collision that this guard exists to repair.
        if (target.getY() < shelterFeet.getY()
                || target.getY() > shelterFeet.getY() + 1
                || directionTo(target) == null
                || !new Box(target).intersects(bot.getBoundingBox())) {
            return false;
        }
        if (!isCenteredAnchorEntitySpaceClear(bot, shelterFeet)) {
            Direction blockedDirection = directionTo(target);
            rejectBlockedPlacementDirection(blockedDirection);
            beginExit(bot, "shelter_anchor_center_entity_occupied");
            BotLog.action(bot, "shelter_anchor_center_entity_occupied",
                    "target", target,
                    "direction", blockedDirection);
            return true;
        }
        if (!FakePlayerMotion.returnToBlockCenter(bot, shelterFeet,
                "shelter_build_anchor_settle")) {
            beginExit(bot, "shelter_anchor_recovery_failed");
            return true;
        }
        if (new Box(target).intersects(bot.getBoundingBox())) {
            beginExit(bot, "shelter_anchor_still_blocks_wall");
            return true;
        }
        // Re-centering restores the precondition for work; it is not durable build progress and
        // must not extend the no-progress watchdog under repeated contact pressure.
        return false;
    }

    /**
     * Vanilla correctly refuses to place a wall through a living entity. Keep the ordered queue
     * intact and allow at most one ordinary cooldown/LOS constrained melee hit to create real
     * placement clearance. The occupied cell is re-read immediately: a cleared cell may be sealed
     * in this same tick, while persistent pressure rejects that side and starts the physical exit
     * transaction. Explosive contact threats are never struck.
     */
    private boolean handlePlacementBlockingHostile(AIPlayerEntity bot, BlockPos target) {
        Optional<LivingEntity> blocker = placementBlockingHostile(bot, target);
        if (blocker.isEmpty()) {
            return false;
        }
        LivingEntity hostile = blocker.orElseThrow();
        Direction blockedDirection = directionTo(target);
        if (CombatCore.isMeleeForbiddenThreat(hostile)) {
            rejectBlockedPlacementDirection(blockedDirection);
            beginExit(bot, "shelter_wall_blocked_by_melee_forbidden_hostile");
            return true;
        }
        boolean canStrike = CombatCore.inMeleeRange(bot, hostile)
                && CombatCore.hasLineOfSight(bot, hostile);
        boolean struck = false;
        if (canStrike) {
            bot.getActionPack().stopMovement();
            CombatCore.ensureMeleeWeapon(bot);
            struck = CombatCore.strikeIfReady(bot, hostile);
            if (!struck) {
                // A newly equipped weapon may need a few ordinary cooldown ticks. Waiting here is
                // not progress and remains bounded by the build-phase deadline; once the first
                // real hit lands, this target is never attacked a second time by shelter.
                return true;
            }
        }
        if (struck) {
            BotLog.action(bot, "shelter_wall_blocker_struck",
                    "target", target,
                    "direction", blockedDirection,
                    "entity", hostile.getType(),
                    "entity_id", hostile.getId());
        }
        if (placementBlockingHostile(bot, target).isEmpty()) {
            return false;
        }
        rejectBlockedPlacementDirection(blockedDirection);
        beginExit(bot, "shelter_wall_blocked_by_persistent_hostile");
        BotLog.action(bot, "shelter_wall_blocker_persisted",
                "target", target,
                "direction", blockedDirection,
                "entity", hostile.getType(),
                "entity_id", hostile.getId(),
                "struck", struck);
        return true;
    }

    /** Prevents a same-cell correction from teleporting through blocks or collidable entities. */
    private static boolean isCenteredAnchorEntitySpaceClear(AIPlayerEntity bot, BlockPos anchor) {
        double centerX = anchor.getX() + 0.5D;
        double centerZ = anchor.getZ() + 0.5D;
        Box centeredBox = bot.getBoundingBox().offset(
                centerX - bot.getX(), anchor.getY() - bot.getY(), centerZ - bot.getZ());
        return bot.getServerWorld().isSpaceEmpty(bot, centeredBox);
    }

    private Optional<LivingEntity> placementBlockingHostile(AIPlayerEntity bot, BlockPos target) {
        Box placementBox = new Box(target);
        return bot.getServerWorld().getEntitiesByClass(
                        LivingEntity.class,
                        placementBox,
                        entity -> entity != bot
                                && DangerWatcher.isActiveHostileThreat(bot, entity)
                                && ObservableWorldQuery.canObserveEntity(bot, entity))
                .stream()
                .filter(entity -> placementBox.intersects(entity.getBoundingBox()))
                .min(Comparator.comparingDouble(bot::squaredDistanceTo));
    }

    private void rejectBlockedPlacementDirection(Direction direction) {
        if (direction == null) {
            return;
        }
        rejectedEgress.add(direction);
        if (direction == directionTo(egressFeet)) {
            egressFeet = null;
        }
    }

    private void tickHold(AIPlayerEntity bot) {
        if (!recoverAnchorOrRelease(bot)) {
            return;
        }
        if (!isEnvelopeSealed(bot)) {
            beginExit(bot, "shelter_breached_during_hold");
            return;
        }
        if (!forcePressureExit
                && exitStartedElapsed >= 0
                && exitAge() >= EXIT_LIMIT) {
            beginForcedPressureExit(bot);
            return;
        }
        if (surfaceShelter) {
            if (!bot.getServerWorld().isDay()) {
                consecutiveDaylightTicks = 0;
            } else if (consecutiveDaylightTicks < DAYLIGHT_GRACE_TICKS) {
                consecutiveDaylightTicks++;
            }
        }
        // Healing belongs inside the sealed safety transaction. Scheduling EatTask only after the
        // door opens exposes a critical-health bot to the exact hostile the shelter was built for.
        // Tick the ordinary physical eating primitive here so inventory selection, use duration,
        // food consumption and vanilla regeneration all remain survival-authentic.
        if (tickHoldEating(bot)) {
            return;
        }
        // A surface shelter is the night transaction. Stay sealed through the complete hostile
        // spawn window; daylight will still be followed by a factual observation-port check.
        if (surfaceShelter) {
            if (!bot.getServerWorld().isDay()
                    || consecutiveDaylightTicks < DAYLIGHT_GRACE_TICKS) {
                return;
            }
        }
        if (bot.getHealth() < SAFE_EXIT_HEALTH) {
            boolean canStillRecover = bot.getHungerManager().getFoodLevel() >= 18
                    || InventoryAction.findFoodSlot(bot) >= 0;
            if (phaseAge() < LOW_HEALTH_HOLD_LIMIT || canStillRecover) {
                return;
            }
            // Do not publish a terminal state from inside an owned enclosure. If recovery really
            // is impossible, repay the doorway debt first and surface the typed failure outside.
            beginExit(bot, "shelter_low_health_recovery_unavailable");
            return;
        }
        if (phaseAge() < MIN_HOLD_TICKS) {
            return;
        }
        beginExit(bot, null);
    }

    private boolean tickHoldEating(AIPlayerEntity bot) {
        if (holdEatTask == null) {
            if (bot.getHealth() >= SAFE_EXIT_HEALTH
                    || bot.getHungerManager().getFoodLevel() >= 20
                    || InventoryAction.findFoodSlot(bot) < 0) {
                return false;
            }
            holdEatTask = new EatTask();
            holdEatTask.start(bot);
            BotLog.action(bot, "shelter_hold_eat_started",
                    "health", bot.getHealth(),
                    "food", bot.getHungerManager().getFoodLevel());
        }
        holdEatTask.tick(bot);
        if (holdEatTask.state() == TaskState.RUNNING) {
            return true;
        }
        if (holdEatTask.state() == TaskState.FAILED) {
            BotLog.action(bot, "shelter_hold_eat_failed",
                    "reason", holdEatTask.failureReason());
        }
        holdEatTask = null;
        return true;
    }

    private void tickOpenExit(AIPlayerEntity bot) {
        if (!forcePressureExit
                && exitStartedElapsed >= 0
                && exitAge() >= EXIT_LIMIT) {
            beginForcedPressureExit(bot);
        }
        // NavSafety can physically move a wet bot out through a diagonal corner before the shelter
        // miner has reopened its planned doorway. That movement makes the player safe, but it does
        // not repay the task's owned enclosure debt. Keep operating the nearby owned door and only
        // publish the environmental terminal once a genuine two-cell, collision-free side exists.
        boolean environmentalDebt = ENVIRONMENTAL_ESCAPE_REQUIRED.equals(pendingFailure);
        if (!forcePressureExit && environmentalDebt && hasPassableEnvelopeSide(bot)) {
            BotLog.action(bot, "shelter_environmental_exit_repaid",
                    "anchor", shelterFeet,
                    "actual", bot.getBlockPos());
            failShelter(bot, ENVIRONMENTAL_ESCAPE_REQUIRED);
            return;
        }
        if (!forcePressureExit && !environmentalDebt && !recoverAnchorOrRelease(bot)) {
            return;
        }
        if (egressFeet == null || !isRecoverableEgress(bot, egressFeet)) {
            if (forcePressureExit) {
                egressFeet = findForcedEgress(bot);
            } else {
                BlockPos supportedEgress = findRecoverableEgress(bot);
                if (supportedEgress != null) {
                    egressFeet = supportedEgress;
                } else if (egressFeet == null || !isOpenableEgress(bot, egressFeet)) {
                    egressFeet = findOpenableEgress(bot);
                }
            }
            if (egressFeet == null) {
                if (!forcePressureExit
                        && !pressuredEgress.isEmpty()
                        && findOwnedOpenableEgressIgnoringPressure(bot) != null) {
                    pressuredEgress.clear();
                    returnToPressureHold(bot, "shelter_pressure_cycle_restarted");
                    return;
                }
                if (forcePressureExit || !hasOpenEnvelopeSide(bot)) {
                    // A typed pressure timeout may not become terminal at the sealed anchor. Keep
                    // retrying until an owned, non-hard-rejected side can be made physically
                    // passable; STEP_OUT is the only terminal boundary for the forced transaction.
                    return;
                }
                // A build can lose its remaining material immediately after preflight, before any
                // wall closes around the bot. There is then no supported adjacent landing to step
                // onto (for example a single-pillar spawn), but publishing the pending failure at
                // the original safe feet is truthful: the task has not created an enclosure or an
                // exit debt. Do not spin forever in OPEN_EXIT waiting for a route that never existed.
                String reason = pendingFailure == null
                        ? "shelter_exit_unavailable"
                        : pendingFailure;
                BotLog.action(bot, "shelter_exit_unavailable", "reason", reason,
                        "open_side", hasOpenEnvelopeSide(bot));
                failShelter(bot, reason);
                return;
            }
        }
        if (exitMiningTarget != null) {
            BlockMiner.Status status = exitMiner.tick(bot);
            if (status == BlockMiner.Status.MINING) {
                return;
            }
            if (status == BlockMiner.Status.FAILED) {
                rejectCurrentEgress(bot, "shelter_exit_mine_failed:" + exitMiner.failureReason());
                return;
            }
            if (status == BlockMiner.Status.DONE) {
                ownedPlacements.remove(exitMiningTarget);
                exitMiningTarget = null;
            }
        }
        if (!forcePressureExit && resealObservedPressure(bot)) {
            return;
        }
        BlockPos obstruction = firstExitObstruction(bot, egressFeet);
        if (obstruction != null) {
            if (!ownsCurrentPlacement(bot, obstruction)) {
                rejectCurrentEgress(bot, "shelter_exit_blocked_unowned");
                return;
            }
            exitMiningTarget = obstruction.toImmutable();
            exitMiner.begin(bot, exitMiningTarget);
            exitMiner.tick(bot);
            return;
        }
        Standability.clearCache();
        if (!Standability.isStandable(bot.getServerWorld(), egressFeet)) {
            if (forcePressureExit) {
                deferForcedEgress(bot, "shelter_exit_not_standable");
                return;
            }
            // The doorway has now been physically reopened and every removed block was owned by
            // this task. If its landing support disappeared meanwhile, stepping out would invent
            // movement into a ravine. Publish a bounded failure at the original safe feet instead.
            String reason = pendingFailure == null
                    ? "shelter_exit_not_standable"
                    : pendingFailure;
            BotLog.action(bot, "shelter_exit_opened_unsupported", "reason", reason,
                    "egress", egressFeet);
            failShelter(bot, reason);
            return;
        }
        phase = Phase.STEP_OUT;
        phaseStartedElapsed = elapsed;
    }

    /**
     * The head cell is deliberately opened before the foot cell, creating a one-block observation
     * port that cannot be walked through. Only facts visible through that port count as pressure.
     * If pressure exists, restore the exact owned head wall and return to a sealed HOLD transaction
     * without ever opening the passable foot cell.
     */
    private boolean resealObservedPressure(AIPlayerEntity bot) {
        if (egressFeet == null
                || ENVIRONMENTAL_ESCAPE_REQUIRED.equals(pendingFailure)
                || !isOpenCell(bot, egressFeet.up())
                || !ownsCurrentPlacement(bot, egressFeet)) {
            return false;
        }
        int visiblePressure = observableExitPressure(bot);
        if (visiblePressure <= 0) {
            return false;
        }
        OptionalInt blockSlot = findShelterBlockSlot(bot);
        if (blockSlot.isEmpty()
                || InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            // Fail closed in the non-passable observation state and retry while the exit watchdog
            // remains bounded. Never mine the foot wall merely because the spare block is delayed.
            return true;
        }
        BlockPos observationPort = egressFeet.up().toImmutable();
        ActionResult result = BuildAction.placeBlockAt(bot, observationPort);
        if (!result.isSuccess() || !isSealed(bot, observationPort)) {
            return true;
        }
        rememberPlacement(bot, observationPort);
        placed++;
        observationReseals++;
        Direction pressuredDirection = directionTo(egressFeet);
        if (pressuredDirection != null) {
            pressuredEgress.add(pressuredDirection);
        }
        BlockPos resealedEgress = egressFeet;
        egressFeet = null;
        lastProgressTick = elapsed;
        BotLog.action(bot, "shelter_observation_resealed",
                "port", observationPort,
                "egress", resealedEgress,
                "direction", pressuredDirection,
                "visible_hostiles", visiblePressure,
                "reseals", observationReseals);
        returnToPressureHold(bot, "shelter_pressure_hold_started");
        return true;
    }

    private int observableExitPressure(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getEntitiesByClass(
                        LivingEntity.class,
                        new Box(egressFeet).expand(CombatCore.hostilePressureScanRange()),
                        entity -> DangerWatcher.isActiveHostileThreat(bot, entity)
                                && ObservableWorldQuery.canObserveEntity(bot, entity)
                                && CombatCore.isWithinHostilePressureEnvelope(bot, entity))
                .size();
    }

    private void tickStepOut(AIPlayerEntity bot) {
        if (!forcePressureExit
                && exitStartedElapsed >= 0
                && exitAge() >= EXIT_LIMIT) {
            beginForcedPressureExit(bot);
            return;
        }
        if (bot.getBlockPos().equals(egressFeet)
                || FakePlayerMotion.stepToStandable(bot, egressFeet, "shelter_owned_egress")) {
            if (!bot.getBlockPos().equals(egressFeet)) {
                noteExitFailure("shelter_exit_pose_unverified");
                phase = Phase.OPEN_EXIT;
                return;
            }
            if (pendingFailure == null) {
                completeShelter(bot);
            } else {
                failShelter(bot, pendingFailure);
            }
            return;
        }
        phase = Phase.OPEN_EXIT;
    }

    private void placeRoofSupportFromRaisedView(AIPlayerEntity bot) {
        if (isSealed(bot, roofSupport)) {
            if (!targets.isEmpty() && targets.peek().equals(roofSupport)) {
                targets.poll();
            } else {
                targets.remove(roofSupport);
            }
            if (!returnToShelterFeet(bot)) {
                beginExit(bot, "shelter_elevation_return_failed");
                return;
            }
            elevatedForRoofSupport = false;
            lastProgressTick = elapsed;
            return;
        }
        OptionalInt blockSlot = findShelterBlockSlot(bot);
        if (blockSlot.isEmpty()) {
            returnToShelterFeet(bot);
            elevatedForRoofSupport = false;
            beginExit(bot, "missing shelter_block");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, blockSlot.getAsInt()) < 0) {
            returnToShelterFeet(bot);
            elevatedForRoofSupport = false;
            beginExit(bot, "cannot_equip_shelter_block");
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, roofSupport);
        if (result.isSuccess() && isSealed(bot, roofSupport)) {
            rememberPlacement(bot, roofSupport);
            placed++;
            lastProgressTick = elapsed;
            return; // next tick physically drop back before placing the center roof
        }
        if (!returnToShelterFeet(bot)) {
            beginExit(bot, "shelter_elevation_return_failed:" + result.reason());
            return;
        }
        elevatedForRoofSupport = false;
    }

    private boolean returnToShelterFeet(AIPlayerEntity bot) {
        if (bot.getBlockPos().equals(shelterFeet)) {
            return true;
        }
        Standability.clearCache();
        return Standability.isStandable(bot.getServerWorld(), shelterFeet)
                && FakePlayerMotion.stepToStandable(
                bot, shelterFeet, "shelter_roof_support_return");
    }

    /**
     * A shelter owns a fixed, supported center. Knockback or gravity may move the player before
     * the shell is sealed; repeatedly trying a one-cell snap back while an enemy keeps attacking
     * turns the atomic-exit guarantee into a death lock. A player who has already landed safely
     * outside the anchor has no enclosure debt and can release the safety scheduler immediately.
     * An airborne/unsettled pose gets one second to return to a still-standable anchor, then fails
     * closed so combat or navigation rescue can take over.
     */
    private boolean recoverAnchorOrRelease(AIPlayerEntity bot) {
        if (bot.getBlockPos().equals(shelterFeet)) {
            anchorRecoveryStartedElapsed = -1;
            return true;
        }
        Standability.clearCache();
        BlockPos here = bot.getBlockPos();
        if (Standability.isStandable(bot.getServerWorld(), here)) {
            failDisplacedAnchor(bot, "shelter_anchor_displaced");
            return false;
        }
        if (anchorRecoveryStartedElapsed < 0) {
            anchorRecoveryStartedElapsed = elapsed;
        }
        if (returnToShelterFeet(bot)) {
            anchorRecoveryStartedElapsed = -1;
            return true;
        }
        if (elapsed - anchorRecoveryStartedElapsed > ANCHOR_RECOVERY_LIMIT) {
            failDisplacedAnchor(bot, "shelter_anchor_recovery_timeout");
        }
        return false;
    }

    private void failDisplacedAnchor(AIPlayerEntity bot, String reason) {
        String terminalReason = pendingFailure == null ? reason : pendingFailure;
        exitMiner.cancel(bot);
        exitMiningTarget = null;
        bot.getActionPack().stopAll();
        BotLog.action(bot, "shelter_anchor_released",
                "reason", reason,
                "anchor", shelterFeet,
                "actual", bot.getBlockPos(),
                "owned", ownedPlacements.size(),
                "phase", phase,
                "terminal_reason", terminalReason);
        failShelter(bot, terminalReason);
    }

    static boolean canStartAtCurrentPose(AIPlayerEntity bot) {
        Standability.clearCache();
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        // NavSafety owns the complete water-recovery episode. A fixed shelter anchor must not
        // compete for movement or place an enclosure around either a pending rescue waypoint or a
        // body column whose fluid shape can change underneath it.
        if (NavSafetyNet.INSTANCE.isWaterRescueActive(bot) || hasBodyFluid(bot)) {
            return false;
        }
        if (!Standability.isStandable(world, feet)) {
            return false;
        }
        if (bot.isOnGround()) {
            return true;
        }
        // Clientless players can retain a stale false onGround bit immediately after a verified
        // spawn/teleport. Accept that bit only when Minecraft's own micro-support probe finds the
        // exact block under this collision box. A genuinely falling player over an open shaft has
        // no such support and remains ineligible for a fixed shelter anchor.
        Box box = bot.getBoundingBox();
        Box supportProbe = new Box(
                box.minX, box.minY - 1.0E-6D, box.minZ,
                box.maxX, box.minY, box.maxZ);
        return world.findSupportingBlockPos(bot, supportProbe)
                .filter(feet.down()::equals)
                .isPresent();
    }

    static boolean isSurfaceShelterAnchor(AIPlayerEntity bot, BlockPos origin) {
        if (origin.getY() < 32) {
            return false;
        }
        for (int dx = -8; dx <= 8; dx += 4) {
            for (int dz = -8; dz <= 8; dz += 4) {
                int topY = bot.getServerWorld().getTopY(
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        origin.getX() + dx,
                        origin.getZ() + dz);
                if (Math.abs(topY - origin.getY()) <= 8) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isFoundation(BlockPos target) {
        return target.getY() == shelterFeet.getY() - 1
                && Math.abs(target.getX() - shelterFeet.getX())
                + Math.abs(target.getZ() - shelterFeet.getZ()) == 1;
    }

    private ActionResult placeFoundationFromEdge(AIPlayerEntity bot, BlockPos target) {
        int dx = target.getX() - shelterFeet.getX();
        int dz = target.getZ() - shelterFeet.getZ();
        Direction direction = null;
        for (Direction candidate : Direction.Type.HORIZONTAL) {
            if (candidate.getOffsetX() == dx && candidate.getOffsetZ() == dz) {
                direction = candidate;
                break;
            }
        }
        if (direction == null
                || !FakePlayerMotion.shiftToSupportEdge(
                        bot, shelterFeet, direction, "shelter_foundation")) {
            return ActionResult.failed("foundation_edge_unreachable");
        }
        ActionResult result = BuildAction.placeBlock(
                bot, shelterFeet.down(), direction, Hand.MAIN_HAND);
        if (!FakePlayerMotion.returnToBlockCenter(bot, shelterFeet, "shelter_foundation")) {
            return ActionResult.failed("foundation_edge_return_failed");
        }
        return result;
    }

    private void beginExit(AIPlayerEntity bot, String failure) {
        if (failure != null) {
            noteExitFailure(failure);
        }
        phase = Phase.OPEN_EXIT;
        phaseStartedElapsed = elapsed;
        if (exitStartedElapsed < 0) {
            exitStartedElapsed = elapsed;
        }
        elevatedForRoofSupport = false;
        exitMiningTarget = null;
        cancelHoldEating(bot, "shelter_hold_finished");
        exitMiner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    private void beginForcedPressureExit(AIPlayerEntity bot) {
        noteExitFailure("shelter_exit_pressure_timeout");
        forcePressureExit = true;
        pressuredEgress.clear();

        Direction currentDirection = directionTo(egressFeet);
        boolean keepCurrent = currentDirection != null
                && !rejectedEgress.contains(currentDirection)
                && isRecoverableEgress(bot, egressFeet);
        if (!keepCurrent) {
            exitMiner.cancel(bot);
            exitMiningTarget = null;
            egressFeet = findForcedEgress(bot);
        }
        phase = Phase.OPEN_EXIT;
        phaseStartedElapsed = elapsed;
        elevatedForRoofSupport = false;
        cancelHoldEating(bot, "shelter_pressure_timeout");
        bot.getActionPack().stopAll();
        BotLog.action(bot, "shelter_pressure_exit_forced",
                "reason", pendingFailure,
                "egress", egressFeet,
                "kept_current", keepCurrent,
                "exit_age", exitAge());
    }

    private void returnToPressureHold(AIPlayerEntity bot, String reason) {
        phase = Phase.HOLD;
        phaseStartedElapsed = elapsed;
        exitMiningTarget = null;
        exitMiner.cancel(bot);
        bot.getActionPack().stopAll();
        BotLog.action(bot, reason,
                "pressured", pressuredEgress.size(),
                "exit_age", exitAge());
    }

    private void cancelHoldEating(AIPlayerEntity bot, String reason) {
        if (holdEatTask != null) {
            holdEatTask.cancel(bot, reason);
            holdEatTask = null;
        }
    }

    private void noteExitFailure(String reason) {
        if (pendingFailure == null) {
            pendingFailure = reason;
        }
    }

    private void rejectCurrentEgress(AIPlayerEntity bot, String reason) {
        noteExitFailure(reason);
        Direction direction = directionTo(egressFeet);
        if (direction != null) {
            rejectedEgress.add(direction);
        }
        exitMiner.cancel(bot);
        exitMiningTarget = null;
        egressFeet = null;
    }

    private void deferForcedEgress(AIPlayerEntity bot, String reason) {
        exitMiner.cancel(bot);
        exitMiningTarget = null;
        egressFeet = null;
        BotLog.action(bot, "shelter_forced_egress_deferred",
                "reason", reason,
                "exit_age", exitAge());
    }

    private int phaseAge() {
        return Math.max(0, elapsed - phaseStartedElapsed);
    }

    private int exitAge() {
        return exitStartedElapsed < 0 ? 0 : Math.max(0, elapsed - exitStartedElapsed);
    }

    private boolean handleEnvironmentalOwnershipConflict(AIPlayerEntity bot) {
        if (!NavSafetyNet.INSTANCE.isWaterRescueActive(bot) && !hasBodyFluid(bot)) {
            return false;
        }
        // During an incomplete build, a verified two-high open side proves there is no sealed
        // enclosure debt to repay. Release immediately so NavSafety can own the next movement tick.
        if (phase == Phase.BUILD && hasOpenEnvelopeSide(bot)) {
            BotLog.action(bot, "shelter_environmental_release",
                    "phase", phase,
                    "anchor", shelterFeet,
                    "open_side", true);
            failShelter(bot, ENVIRONMENTAL_ESCAPE_REQUIRED);
            return true;
        }

        // A sealed HOLD (or a BUILD with every side closed) must reopen an owned doorway before it
        // can publish failure. If another exit reason was already pending, water ownership wins the
        // terminal type while preserving the prior reason in the log.
        if (!ENVIRONMENTAL_ESCAPE_REQUIRED.equals(pendingFailure)) {
            BotLog.action(bot, "shelter_environmental_exit_required",
                    "phase", phase,
                    "anchor", shelterFeet,
                    "previous_reason", pendingFailure == null ? "" : pendingFailure);
            pendingFailure = ENVIRONMENTAL_ESCAPE_REQUIRED;
        }
        if (phase == Phase.BUILD || phase == Phase.HOLD) {
            beginExit(bot, null);
        }
        return false;
    }

    private static boolean hasBodyFluid(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        return bot.isSubmergedInWater()
                || !world.getFluidState(feet).isEmpty()
                || !world.getFluidState(feet.up()).isEmpty();
    }

    private void completeShelter(AIPlayerEntity bot) {
        complete();
        recordTerminal(bot, TaskState.COMPLETED, "");
    }

    private void failShelter(AIPlayerEntity bot, String reason) {
        fail(reason);
        recordTerminal(bot, TaskState.FAILED, reason);
    }

    private void recordTerminal(AIPlayerEntity bot, TaskState outcome, String reason) {
        if (terminalRecorded) {
            return;
        }
        terminalRecorded = true;
        BlockPos anchor = shelterFeet == null ? bot.getBlockPos() : shelterFeet;
        DangerWatcher.INSTANCE.noteShelterTerminal(bot, anchor, outcome, reason);
    }

    private static void addFoundationIfNeeded(AIPlayerEntity bot,
                                              List<BlockPos> targets,
                                              BlockPos sideFeet) {
        if (!isSealed(bot, sideFeet) && !isSealed(bot, sideFeet.down())) {
            targets.add(sideFeet.down().toImmutable());
        }
    }

    private static Optional<ShelterPlan> planShelter(AIPlayerEntity bot, BlockPos feet) {
        BlockPos head = feet.up();
        BlockPos roof = head.up();
        // The eight side cells are only diagonal to the center roof. In strict survival they can
        // never be clicked as a face-adjacent placement support for that roof. Build one permanent
        // top-rim block above the north wall first; once the north head wall exists, the rim can be
        // placed on it and the center roof can then be placed against the rim.
        BlockPos roofSupport = roof.north().toImmutable();
        BlockPos roofSupportBase = head.north().toImmutable();
        BlockPos egressFeet = selectPlannedEgress(bot, feet);
        if (egressFeet == null) {
            return Optional.empty();
        }
        List<BlockPos> targets = new ArrayList<>(MAX_PLACEMENT_BLOCKS);
        // Only an open side whose landing support is missing needs a foundation. Put the planned
        // exit support first so no envelope block can be placed until the task has physically
        // proved that it owns a supported way back out.
        addFoundationIfNeeded(bot, targets, egressFeet);
        for (Direction direction : HORIZONTAL) {
            BlockPos side = feet.offset(direction);
            if (!side.equals(egressFeet)) {
                addFoundationIfNeeded(bot, targets, side);
            }
        }
        // Build the envelope from the ground up. Keeping the center roof last prevents an earlier
        // failed target from leaving the player under a roof that can no longer be supported.
        for (Direction direction : HORIZONTAL) {
            targets.add(feet.offset(direction).toImmutable());
            targets.add(head.offset(direction).toImmutable());
        }
        if (!isSealed(bot, roof)) {
            targets.add(roofSupport);
            targets.add(roof.toImmutable());
        }
        return Optional.of(new ShelterPlan(
                egressFeet.toImmutable(),
                roofSupport,
                roofSupportBase,
                List.copyOf(targets)));
    }

    private static int requiredShelterBlocks(AIPlayerEntity bot, ShelterPlan plan) {
        int required = 0;
        for (BlockPos target : plan.targets()) {
            if (!isSealed(bot, target)) {
                required++;
            }
        }
        return required;
    }

    private static BlockPos selectPlannedEgress(AIPlayerEntity bot, BlockPos feet) {
        var world = bot.getServerWorld();
        // Prefer a landing that already exists. Only extend a foundation when no naturally
        // supported two-block doorway is available.
        for (Direction direction : HORIZONTAL) {
            BlockPos candidate = feet.offset(direction);
            if (!isDryReplaceable(world.getBlockState(candidate))
                    || !isDryReplaceable(world.getBlockState(candidate.up()))) {
                continue;
            }
            BlockPos support = candidate.down();
            if (isSafeSupport(bot, support)) {
                return candidate.toImmutable();
            }
        }
        for (Direction direction : HORIZONTAL) {
            BlockPos candidate = feet.offset(direction);
            if (isDryReplaceable(world.getBlockState(candidate))
                    && isDryReplaceable(world.getBlockState(candidate.up()))
                    && isDryReplaceable(world.getBlockState(candidate.down()))) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private BlockPos findRecoverableEgress(AIPlayerEntity bot) {
        return findRecoverableEgress(bot, false);
    }

    private BlockPos findRecoverableEgressIgnoringPressure(AIPlayerEntity bot) {
        return findRecoverableEgress(bot, true);
    }

    private BlockPos findRecoverableEgress(AIPlayerEntity bot, boolean ignorePressure) {
        for (Direction direction : HORIZONTAL) {
            if (rejectedEgress.contains(direction)
                    || !ignorePressure && pressuredEgress.contains(direction)) {
                continue;
            }
            BlockPos candidate = shelterFeet.offset(direction);
            if (isRecoverableEgress(bot, candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private BlockPos findOpenableEgress(AIPlayerEntity bot) {
        return findOpenableEgress(bot, false);
    }

    private BlockPos findOpenableEgressIgnoringPressure(AIPlayerEntity bot) {
        return findOpenableEgress(bot, true);
    }

    private BlockPos findOpenableEgress(AIPlayerEntity bot, boolean ignorePressure) {
        for (Direction direction : HORIZONTAL) {
            if (rejectedEgress.contains(direction)
                    || !ignorePressure && pressuredEgress.contains(direction)) {
                continue;
            }
            BlockPos candidate = shelterFeet.offset(direction);
            if (isOpenableEgress(bot, candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private BlockPos findForcedEgress(AIPlayerEntity bot) {
        BlockPos supported = findRecoverableEgressIgnoringPressure(bot);
        return supported == null ? findOpenableEgressIgnoringPressure(bot) : supported;
    }

    private BlockPos findOwnedOpenableEgressIgnoringPressure(AIPlayerEntity bot) {
        for (Direction direction : HORIZONTAL) {
            if (rejectedEgress.contains(direction)) {
                continue;
            }
            BlockPos candidate = shelterFeet.offset(direction);
            if (isOpenableEgress(bot, candidate)
                    && (ownsCurrentPlacement(bot, candidate)
                    || ownsCurrentPlacement(bot, candidate.up()))) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private boolean isRecoverableEgress(AIPlayerEntity bot, BlockPos candidate) {
        return candidate != null
                && isSafeSupport(bot, candidate.down())
                && isOpenableEgress(bot, candidate);
    }

    private boolean isOpenableEgress(AIPlayerEntity bot, BlockPos candidate) {
        return candidate != null
                && (isOpenCell(bot, candidate) || ownsCurrentPlacement(bot, candidate))
                && (isOpenCell(bot, candidate.up()) || ownsCurrentPlacement(bot, candidate.up()));
    }

    private BlockPos firstExitObstruction(AIPlayerEntity bot, BlockPos candidate) {
        if (!isOpenCell(bot, candidate.up())) {
            return candidate.up().toImmutable();
        }
        if (!isOpenCell(bot, candidate)) {
            return candidate.toImmutable();
        }
        return null;
    }

    private void rememberPlacement(AIPlayerEntity bot, BlockPos target) {
        ownedPlacements.put(target.toImmutable(), bot.getServerWorld().getBlockState(target));
    }

    private boolean ownsCurrentPlacement(AIPlayerEntity bot, BlockPos target) {
        BlockState owned = ownedPlacements.get(target);
        return owned != null && owned.equals(bot.getServerWorld().getBlockState(target));
    }

    private boolean isEnvelopeSealed(AIPlayerEntity bot) {
        if (!isSealed(bot, shelterFeet.up(2))) {
            return false;
        }
        for (Direction direction : HORIZONTAL) {
            if (!isSealed(bot, shelterFeet.offset(direction))
                    || !isSealed(bot, shelterFeet.up().offset(direction))) {
                return false;
            }
        }
        return true;
    }

    private boolean hasOpenEnvelopeSide(AIPlayerEntity bot) {
        for (Direction direction : HORIZONTAL) {
            BlockPos side = shelterFeet.offset(direction);
            if (isOpenCell(bot, side) && isOpenCell(bot, side.up())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPassableEnvelopeSide(AIPlayerEntity bot) {
        for (Direction direction : HORIZONTAL) {
            BlockPos side = shelterFeet.offset(direction);
            if (hasNoCollision(bot, side) && hasNoCollision(bot, side.up())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNoCollision(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.getServerWorld();
        return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    private static boolean isOpenCell(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.getServerWorld();
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isSafeSupport(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.getServerWorld();
        BlockState state = world.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && !state.getCollisionShape(world, pos).isEmpty()
                && !Standability.isDangerous(state);
    }

    private static boolean isDryReplaceable(BlockState state) {
        return state.getFluidState().isEmpty() && state.isReplaceable();
    }

    private Direction directionTo(BlockPos target) {
        if (target == null) {
            return null;
        }
        int dx = target.getX() - shelterFeet.getX();
        int dz = target.getZ() - shelterFeet.getZ();
        for (Direction direction : HORIZONTAL) {
            if (direction.getOffsetX() == dx && direction.getOffsetZ() == dz) {
                return direction;
            }
        }
        return null;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        exitMiner.cancel(bot);
        cancelHoldEating(bot, "shelter_aborted");
        if (elevatedForRoofSupport) {
            returnToShelterFeet(bot);
        }
        bot.getActionPack().stopAll();
    }

    private static boolean isSealed(AIPlayerEntity bot, BlockPos pos) {
        var world = bot.getServerWorld();
        var state = world.getBlockState(pos);
        return !state.isReplaceable()
                && !state.getCollisionShape(world, pos).isEmpty();
    }

    private static OptionalInt findShelterBlockSlot(AIPlayerEntity bot) {
        return MaterialPalette.pickEmergencyShelterBlockSlot(bot);
    }

    private static int countShelterBlocks(AIPlayerEntity bot) {
        return MaterialPalette.countEmergencyShelterBlocks(bot);
    }

    public static boolean hasShelterBlock(AIPlayerEntity bot) {
        return findShelterBlockSlot(bot).isPresent();
    }

    /**
     * Admission probe shared with the danger scheduler. It uses the exact same physical plan and
     * inventory palette as {@link #onStart(AIPlayerEntity)}, so a one-block inventory cannot pause
     * live work for a shelter that is guaranteed to fail before its first placement.
     */
    static boolean hasMaterialsForCurrentPose(AIPlayerEntity bot) {
        if (!canStartAtCurrentPose(bot)) {
            return false;
        }
        Optional<ShelterPlan> plan = planShelter(bot, bot.getBlockPos());
        return plan.isPresent()
                && countShelterBlocks(bot) >= requiredShelterBlocks(bot, plan.orElseThrow());
    }
}
