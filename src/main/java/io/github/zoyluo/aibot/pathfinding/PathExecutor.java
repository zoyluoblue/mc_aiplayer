package io.github.zoyluo.aibot.pathfinding;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.ActionPack;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.action.MiningController;
import io.github.zoyluo.aibot.action.WalkToController;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Objects;

public final class PathExecutor {
    private static final int STUCK_TICKS_LIMIT = 60;
    private static final int REPLAN_COOLDOWN_TICKS = 40;
    private static final int CONSTRAINED_ROUTE_MAX_NODES = 10_000;
    private static final long CONSTRAINED_ROUTE_MAX_MILLIS = 50L;

    private List<Node> path;
    private int index = 1;
    private final BlockPos originalGoal;
    private final boolean replanCanPillar;
    private final boolean replanAllowDig;
    private final int protectedStoneLikeReserve;
    private final RouteContract routeContract;
    private WalkToController subWalker;
    private MiningController subMiner;
    private boolean digWalking;
    private final ReplanGate replanGate = new ReplanGate();
    private Vec3d lastPos;
    private int stuckTicks;
    private int totalTicks;
    private int lastReplanTick = -REPLAN_COOLDOWN_TICKS;
    private int activeWalkTargetIndex = -1;
    private int nodeRetry;
    private BlockPos lastRuntimeContractPosition;

    public PathExecutor(List<Node> path, BlockPos originalGoal) {
        this(path, originalGoal, false, false, 0);
    }

    public PathExecutor(List<Node> path, BlockPos originalGoal,
                        boolean replanCanPillar, boolean replanAllowDig) {
        this(path, originalGoal, replanCanPillar, replanAllowDig, 0);
    }

    public PathExecutor(List<Node> path, BlockPos originalGoal,
                        boolean replanCanPillar, boolean replanAllowDig,
                        int protectedStoneLikeReserve) {
        this(path, originalGoal, replanCanPillar, replanAllowDig,
                protectedStoneLikeReserve, RouteContract.unrestricted());
    }

    public PathExecutor(List<Node> path, BlockPos originalGoal,
                        boolean replanCanPillar, boolean replanAllowDig,
                        int protectedStoneLikeReserve,
                        RouteContract routeContract) {
        this.path = List.copyOf(path);
        this.originalGoal = originalGoal.toImmutable();
        this.replanCanPillar = replanCanPillar;
        this.replanAllowDig = replanAllowDig;
        this.protectedStoneLikeReserve = Math.max(0, protectedStoneLikeReserve);
        this.routeContract = Objects.requireNonNull(routeContract, "routeContract");
    }

    public ActionResult tick(ActionPack pack) {
        totalTicks++;
        if (path.isEmpty() || index >= path.size()) {
            if (routeContract.constrained()) {
                BlockPos current = pack.player().getBlockPos();
                if (!current.equals(originalGoal)) {
                    return failRuntimeContract(
                            pack, "terminal_goal_not_exact current=" + compact(current)
                                    + " goal=" + compact(originalGoal));
                }
                if (current.getY() < routeContract.minimumY()) {
                    return failRuntimeContract(pack, "terminal_below_minimum_y");
                }
                Standability.clearCache();
                if (!Standability.isStandable(pack.player().getServerWorld(), current)
                        || !FakePlayerMotion.isBlockCollisionFree(pack.player())) {
                    return failRuntimeContract(pack, "terminal_not_standable");
                }
                ActionResult terminalProof = ensureRuntimeContract(pack, true);
                if (terminalProof.isFailed()) {
                    return terminalProof;
                }
                cleanup(pack);
                return ActionResult.SUCCESS;
            }
            cleanup(pack);
            double distSq = pack.player().getBlockPos().getSquaredDistance(originalGoal);
            if (distSq > 4.0D) {
                BotLog.warn(LogCategory.PATH, pack.player(), "path_end_far_from_goal",
                        "dist_sq", distSq, "goal", LogFields.pos(originalGoal));
                return ActionResult.failed("ended_far_from_goal dist_sq=" + (int) distSq);
            }
            return ActionResult.SUCCESS;
        }
        ActionResult runtimeContract = ensureRuntimeContract(pack, false);
        if (runtimeContract.isFailed()) {
            return runtimeContract;
        }

        Node next = path.get(index);
        String danger = DangerCheck.scan(pack.player().getServerWorld(), next.pos());
        if (danger != null) {
            BotLog.warn(LogCategory.PATH, pack.player(), "path_danger", "at_node", LogFields.pos(next.pos()), "reason", danger);
            cleanup(pack);
            return ActionResult.failed("danger_at_node: " + danger);
        }

        ActionResult result = switch (next.moveType()) {
            case WALK, DIAGONAL, JUMP_UP -> tickWalk(pack, next);
            case DROP_DOWN -> tickDrop(pack, next);
            case DIG_THROUGH -> tickDigThrough(pack, next);
            case PILLAR_UP -> tickPillar(pack, next);
        };
        if (!result.isInProgress()) {
            return result;
        }
        Node progressNode = activeWalkTargetIndex >= index && activeWalkTargetIndex < path.size()
                ? path.get(activeWalkTargetIndex)
                : next;
        return checkProgress(pack, progressNode);
    }

    public void abort(ActionPack pack) {
        cleanup(pack);
    }

    public int totalTicks() {
        return totalTicks;
    }

    public static boolean hasPlaceableBlock(AIPlayerEntity player) {
        return hasPlaceableBlock(player, 0);
    }

    public static boolean hasPlaceableBlock(AIPlayerEntity player,
                                            int protectedStoneLikeReserve) {
        return findPlaceableBlock(player, protectedStoneLikeReserve) >= 0;
    }

    private ActionResult tickWalk(ActionPack pack, Node next) {
        if (arrivedAt(pack.player().getBlockPos(), next.pos())) {
            return commitAdvance(pack, index + 1);
        }
        if (next.moveType() == MoveType.JUMP_UP) {
            if (FakePlayerMotion.jumpTo(pack.player(), next.pos(), "path_jump_up")) {
                BotLog.path(pack.player(), "path_jump_complete", "to", LogFields.pos(next.pos()));
                return commitAdvance(pack, index + 1);
            }
            return handleStuck(pack, "jump_up_blocked");
        }
        if (subWalker == null) {
            activeWalkTargetIndex = chooseWalkTargetIndex(pack);
            Node target = path.get(activeWalkTargetIndex);
            if (activeWalkTargetIndex > index) {
                BotLog.path(pack.player(), "path_skip",
                        "from_index", index,
                        "to_index", activeWalkTargetIndex,
                        "from", LogFields.pos(next.pos()),
                        "to", LogFields.pos(target.pos()));
            }
            subWalker = new WalkToController(
                    Vec3d.ofCenter(target.pos()), WalkToController.PATH_NODE_ARRIVAL_THRESHOLD);
        }
        Node target = path.get(activeWalkTargetIndex);
        if (arrivedAt(pack.player().getBlockPos(), target.pos())) {
            return commitAdvance(pack, activeWalkTargetIndex + 1);
        }
        BlockPos beforeControllerTick = pack.player().getBlockPos().toImmutable();
        ActionResult result = subWalker.tick(pack);
        ActionResult movedContract =
                ensureRuntimeContractAfterControllerMove(pack, beforeControllerTick);
        if (movedContract.isFailed()) {
            return movedContract;
        }
        if (result.isSuccess()) {
            return commitAdvance(pack, activeWalkTargetIndex + 1);
        }
        if (result.isFailed()) {
            return handleWalkFailure(pack, "walk_failed: " + result.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    /** Executes a safe fall one collision-validated adjacent cell at a time for a clientless bot. */
    private ActionResult tickDrop(ActionPack pack, Node next) {
        AIPlayerEntity player = pack.player();
        BlockPos current = player.getBlockPos();
        BlockPos target = next.pos();
        if (current.equals(target)) {
            return commitAdvance(pack, index + 1);
        }
        if (current.getY() <= target.getY()
                || Math.abs(current.getX() - target.getX()) > 1
                || Math.abs(current.getZ() - target.getZ()) > 1) {
            return handleStuck(pack, "drop_pose_drift");
        }
        int stepX = Integer.compare(target.getX(), current.getX());
        int stepZ = stepX == 0 ? Integer.compare(target.getZ(), current.getZ()) : 0;
        BlockPos step = current.add(stepX, -1, stepZ);
        if (DangerCheck.scan(player.getServerWorld(), step) != null
                || !FakePlayerMotion.stepTo(player, step, "path_drop_down")) {
            return handleStuck(pack, "drop_step_blocked");
        }
        ActionResult stepContract = ensureRuntimeContract(pack, false);
        if (stepContract.isFailed()) {
            return stepContract;
        }
        if (step.equals(target)) {
            BotLog.path(player, "path_drop_complete", "to", LogFields.pos(target));
            return commitAdvance(pack, index + 1);
        }
        return ActionResult.IN_PROGRESS;
    }

    private ActionResult tickDigThrough(ActionPack pack, Node next) {
        if (!digWalking) {
            if (subMiner == null) {
                Direction face = faceFromPlayer(pack, next.pos());
                LookAction.lookAtBlock(pack.player(), next.pos(), face);
                subMiner = new MiningController(next.pos(), face);
            }
            ActionResult mine = subMiner.tick(pack);
            if (mine.isFailed()) {
                return handleStuck(pack, "dig_failed: " + mine.reason());
            }
            if (mine.isInProgress()) {
                return ActionResult.IN_PROGRESS;
            }
            // 穿山双格挖:脚位挖完后头位仍有碰撞(实心山体内部每步如此)→ 再挖头位,人才进得去。
            // 配合 NeighborEnumerator.hasHeadroom 的"头位可挖即可"放宽,挖掘寻路从贴地刨坑升级为穿山打洞。
            BlockPos headPos = next.pos().up();
            if (!pack.player().getServerWorld().getBlockState(headPos)
                    .getCollisionShape(pack.player().getServerWorld(), headPos).isEmpty()) {
                Direction headFace = faceFromPlayer(pack, headPos);
                LookAction.lookAtBlock(pack.player(), headPos, headFace);
                subMiner = new MiningController(headPos, headFace);
                return ActionResult.IN_PROGRESS;
            }
            subMiner = null;
            digWalking = true;
            subWalker = new WalkToController(
                    Vec3d.ofCenter(next.pos()), WalkToController.PATH_NODE_ARRIVAL_THRESHOLD);
        }

        BlockPos beforeControllerTick = pack.player().getBlockPos().toImmutable();
        ActionResult walk = subWalker.tick(pack);
        ActionResult movedContract =
                ensureRuntimeContractAfterControllerMove(pack, beforeControllerTick);
        if (movedContract.isFailed()) {
            return movedContract;
        }
        if (walk.isSuccess()) {
            return commitAdvance(pack, index + 1);
        }
        if (walk.isFailed()) {
            return handleWalkFailure(pack, "dig_walk_failed: " + walk.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    // NAV-9:垫方块上升一格。看向脚下→起跳→升空瞬间在原脚位放支撑方块→落到其上。
    private ActionResult tickPillar(ActionPack pack, Node next) {
        AIPlayerEntity player = pack.player();
        BlockPos placeSlot = next.pos().down(); // 当前脚位,支撑方块放这里
        if (player.getBlockY() >= next.pos().getY() && player.isOnGround()) {
            return commitAdvance(pack, index + 1);
        }
        int slot = findPlaceableBlock(player, protectedStoneLikeReserve);
        if (slot < 0) {
            return handleStuck(pack, "pillar_no_block");
        }
        InventoryAction.equipFromSlot(player, slot);
        LookAction.lookAtBlock(player, placeSlot, Direction.UP);
        pack.setForward(0.0F);
        pack.setJumping(true);
        pack.jumpOnce();
        double rise = player.getY() - placeSlot.getY();
        if (rise > 0.5D && rise < 1.2D && player.getServerWorld().getBlockState(placeSlot).isAir()) {
            BuildAction.placeBlockAt(player, placeSlot);
            return ActionResult.IN_PROGRESS;
        }

        // ServerPlayerEntity normally receives its jump displacement from client movement packets.
        // Our fake player has no client, so setJumping alone can leave it bouncing at the same block
        // forever (the obsidian pool pickup path is a deterministic two-block-deep reproduction).
        // Model exactly one adjacent jump cell through the reviewed fake-client adapter, then place
        // the support with the normal visible vanilla interaction. If placement fails, return to the
        // factual old feet cell so a replan never leaves the bot suspended in an invented pose.
        if (player.isOnGround() && player.getBlockPos().equals(placeSlot)) {
            if (!FakePlayerMotion.jumpTo(player, next.pos(), "path_pillar_jump")) {
                return handleStuck(pack, "pillar_jump_blocked");
            }
            ActionResult placed = BuildAction.placeBlockAt(player, placeSlot);
            if (placed.isSuccess()) {
                BotLog.path(player, "path_pillar_complete",
                        "from", LogFields.pos(placeSlot),
                        "to", LogFields.pos(next.pos()));
                return commitAdvance(pack, index + 1);
            }
            FakePlayerMotion.stepTo(player, placeSlot, "path_pillar_rollback");
            return handleStuck(pack, "pillar_place_failed: " + placed.reason());
        }
        return ActionResult.IN_PROGRESS;
    }

    private static int findPlaceableBlock(AIPlayerEntity player,
                                          int protectedStoneLikeReserve) {
        // A fluid seal and a standing surface have different geometry requirements. Use the
        // path-only full-height palette so lowered blocks such as mud cannot invalidate the
        // one-block rise assumed by PILLAR_UP.
        return MaterialPalette.pickPathSupportBlockSlot(
                player, protectedStoneLikeReserve).orElse(-1);
    }

    private ActionResult checkProgress(ActionPack pack, Node next) {
        Vec3d current = pack.player().getPos();
        if (lastPos != null && current.distanceTo(lastPos) < 0.03D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = current;
        if (stuckTicks > STUCK_TICKS_LIMIT) {
            return handleStuck(pack, "no_progress_at: " + compact(next.pos()));
        }
        return ActionResult.IN_PROGRESS;
    }

    private ActionResult commitAdvance(ActionPack pack, int nextIndex) {
        ActionResult contract = ensureRuntimeContract(pack, false);
        if (contract.isFailed()) {
            return contract;
        }
        advanceTo(nextIndex);
        return ActionResult.IN_PROGRESS;
    }

    private void advanceTo(int nextIndex) {
        Node next = path.get(index);
        BotLog.path(null, "path_advance", "index", index, "total", path.size(), "move_type", next.moveType(), "pos", LogFields.pos(next.pos()));
        index = Math.max(index + 1, Math.min(nextIndex, path.size()));
        subWalker = null;
        subMiner = null;
        digWalking = false;
        stuckTicks = 0;
        lastPos = null;
        activeWalkTargetIndex = -1;
        nodeRetry = 0;
        replanGate.resetAfterNodeAdvance();
        // A committed node starts a new safety lease even when rounding keeps the same BlockPos.
        // This catches a return corridor changed between two executor ticks before the next node.
        lastRuntimeContractPosition = null;
    }

    private int chooseWalkTargetIndex(ActionPack pack) {
        int best = index;
        BlockPos from = pack.player().getBlockPos();
        int max = Math.min(path.size() - 1, index + AIBotConfig.get().nav().lookahead());
        for (int candidate = index + 1; candidate <= max; candidate++) {
            if (!canStringPullTo(pack, from, candidate)) {
                break;
            }
            best = candidate;
        }
        return best;
    }

    private boolean canStringPullTo(ActionPack pack, BlockPos from, int candidateIndex) {
        for (int i = index; i <= candidateIndex; i++) {
            MoveType type = path.get(i).moveType();
            // A jump node is a mandatory change of elevation. WalkToController only steers on the
            // horizontal plane, so string-pulling across JUMP_UP silently skips the climb.
            if (type != MoveType.WALK && type != MoveType.DIAGONAL) {
                return false;
            }
        }
        BlockPos target = path.get(candidateIndex).pos();
        int dy = target.getY() - from.getY();
        if (dy < -1 || dy > 1) {
            return false;
        }
        return lineClearForStringPull(pack.player().getServerWorld(), from, target);
    }

    private static boolean lineClearForStringPull(net.minecraft.server.world.ServerWorld world, BlockPos from, BlockPos target) {
        int dx = target.getX() - from.getX();
        int dy = target.getY() - from.getY();
        int dz = target.getZ() - from.getZ();
        int samples = Math.max(1, Math.max(Math.abs(dx), Math.abs(dz)) * 2);
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            BlockPos sample = BlockPos.ofFloored(
                    from.getX() + 0.5D + dx * t,
                    from.getY() + dy * t,
                    from.getZ() + 0.5D + dz * t);
            if (!passableColumn(world, sample)) {
                return false;
            }
            if (!hasSupport(world, sample) && !sample.equals(from)) {
                return false;
            }
        }
        return Standability.isStandable(world, target);
    }

    private static boolean passableColumn(net.minecraft.server.world.ServerWorld world, BlockPos feet) {
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
    }

    private static boolean hasSupport(net.minecraft.server.world.ServerWorld world, BlockPos feet) {
        BlockPos below = feet.down();
        return !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
    }

    static boolean arrivedAt(BlockPos current, BlockPos target) {
        // String-pulling may deliberately collapse a verified clear run. Every node left in the
        // executable path, however, is a required geometric waypoint: accepting an adjacent cell
        // skipped the final horizontal setup before JUMP_UP and asked the bot to jump two blocks.
        return current.equals(target);
    }

    private ActionResult handleWalkFailure(ActionPack pack, String reason) {
        if (reason.contains("stuck_blocked") && nodeRetry < AIBotConfig.get().nav().nodeRetry()) {
            nodeRetry++;
            int previous = index;
            index = Math.max(1, index - 1);
            subWalker = null;
            activeWalkTargetIndex = -1;
            stuckTicks = 0;
            lastPos = null;
            pack.stopMovement();
            BotLog.path(pack.player(), "path_node_retry",
                    "reason", reason,
                    "retry", nodeRetry,
                    "from_index", previous,
                    "to_index", index);
            return ActionResult.IN_PROGRESS;
        }
        return handleStuck(pack, reason);
    }

    private ActionResult handleStuck(ActionPack pack, String reason) {
        if (replanGate.tryAcquire()) {
            int now = pack.player().getServer().getTicks();
            if (now - lastReplanTick < REPLAN_COOLDOWN_TICKS) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_throttled");
            }
            lastReplanTick = now;
            BotLog.path(pack.player(), "path_stuck", "at_node", reason, "stuck_ticks", stuckTicks);
            if (routeContract.constrained()
                    && pack.player().getBlockPos().getY() < routeContract.minimumY()) {
                cleanup(pack);
                return ActionResult.failed(
                        reason + "; replan_failed: ROUTE_CONTRACT:start_below_minimum_y");
            }
            boolean startReady = routeContract.constrained()
                    ? pack.recenterPlayerInCurrentStandableCell(
                            "path_replan_start_invalid")
                    : pack.snapPlayerToNearestStandable("path_replan_start_invalid");
            if (!startReady) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_failed: NO_START");
            }
            // A runtime obstruction may narrow an existing route, but it may never broaden the
            // caller's original movement contract. In particular, surface/pickup paths are created
            // without digging or disposable pillars; silently enabling either here can destroy the
            // wall hiding a finite drop or consume mission materials. Pillaring also remains gated
            // by the current inventory because a previously available support may have been spent.
            boolean canPillar = !routeContract.constrained() && replanCanPillar
                    && hasPlaceableBlock(pack.player(), protectedStoneLikeReserve);
            boolean allowDig = !routeContract.constrained() && replanAllowDig;
            // The active path was planned against older topology. A collision timeout is direct
            // evidence that its cached success may now be stale (external placement, piston,
            // gravity block, explosion, door, etc.); a replan must inspect the current world
            // instead of replaying the same obsolete route until the caller's recovery deadline.
            AStarPathfinder.invalidateCache("runtime_path_obstruction");
            AStarPathfinder finder = routeContract.constrained()
                    ? new AStarPathfinder(
                    pack.player().getServerWorld(), pack.player().getBlockPos(), originalGoal,
                    CONSTRAINED_ROUTE_MAX_NODES, CONSTRAINED_ROUTE_MAX_MILLIS,
                    false, false)
                    : new AStarPathfinder(
                    pack.player().getServerWorld(), pack.player().getBlockPos(), originalGoal,
                    canPillar, allowDig);
            PathfindingResult fresh = routeContract.constrained()
                    ? finder.findPathUncachedAtOrAbove(routeContract.minimumY())
                    : finder.findPath();
            PathfindingResult returnProof = fresh.success() && routeContract.requiresReturnProof()
                    ? proveConstrainedReturnRoute(pack, routeContract.returnAnchor()) : null;
            RouteValidation validation =
                    validateRouteContract(fresh, originalGoal, routeContract, returnProof);
            if (validation.accepted()) {
                BotLog.path(pack.player(), "path_replan", "at_node", reason, "new_path_size", fresh.path().size());
                path = fresh.path();
                index = 1;
                subWalker = null;
                subMiner = null;
                digWalking = false;
                stuckTicks = 0;
                lastPos = null;
                lastRuntimeContractPosition = null;
                return ActionResult.IN_PROGRESS;
            }
            reason = reason + "; replan_failed: "
                    + (fresh.success() ? "ROUTE_CONTRACT:" + validation.reason()
                    : fresh.reason());
        }
        cleanup(pack);
        return ActionResult.failed(reason);
    }

    boolean replanCanPillar() {
        return replanCanPillar;
    }

    boolean replanAllowDig() {
        return replanAllowDig;
    }

    int protectedStoneLikeReserve() {
        return protectedStoneLikeReserve;
    }

    RouteContract routeContract() {
        return routeContract;
    }

    private PathfindingResult proveConstrainedReturnRoute(
            ActionPack pack, BlockPos returnAnchor) {
        return new AStarPathfinder(
                pack.player().getServerWorld(), originalGoal, returnAnchor,
                CONSTRAINED_ROUTE_MAX_NODES, CONSTRAINED_ROUTE_MAX_MILLIS,
                false, false).findPathUncachedAtOrAbove(routeContract.minimumY());
    }

    private ActionResult ensureRuntimeContract(ActionPack pack, boolean forceReturnProof) {
        if (!routeContract.constrained()) {
            return ActionResult.SUCCESS;
        }
        BlockPos current = pack.player().getBlockPos().toImmutable();
        if (current.getY() < routeContract.minimumY()) {
            return failRuntimeContract(pack, "current_below_minimum_y");
        }
        if (!routeContract.requiresReturnProof()) {
            lastRuntimeContractPosition = current;
            return ActionResult.SUCCESS;
        }
        if (!forceReturnProof && current.equals(lastRuntimeContractPosition)) {
            return ActionResult.SUCCESS;
        }
        PathfindingResult proof = new AStarPathfinder(
                pack.player().getServerWorld(), current, routeContract.returnAnchor(),
                CONSTRAINED_ROUTE_MAX_NODES, CONSTRAINED_ROUTE_MAX_MILLIS,
                false, false).findPathUncachedAtOrAbove(routeContract.minimumY());
        RouteValidation validation =
                validateRuntimeReturnContract(proof, current, routeContract);
        if (!validation.accepted()) {
            return failRuntimeContract(pack, validation.reason());
        }
        lastRuntimeContractPosition = current;
        return ActionResult.SUCCESS;
    }

    private ActionResult ensureRuntimeContractAfterControllerMove(
            ActionPack pack, BlockPos beforeControllerTick) {
        BlockPos current = pack.player().getBlockPos();
        return current.equals(beforeControllerTick)
                ? ActionResult.SUCCESS
                : ensureRuntimeContract(pack, false);
    }

    private ActionResult failRuntimeContract(ActionPack pack, String reason) {
        cleanup(pack);
        BotLog.warn(LogCategory.PATH, pack.player(), "path_route_contract_lost",
                "reason", reason,
                "at", LogFields.pos(pack.player().getBlockPos()),
                "goal", LogFields.pos(originalGoal));
        return ActionResult.failed("route_contract_lost: " + reason);
    }

    /**
     * Returns true when {@code result} is a successful route that resolved to exactly the
     * requested start and goal cells and never dips below {@code minimumY}.
     *
     * <p>Callers proving a reversible surface corridor need this stricter check than
     * {@link #validateRouteContract}, which deliberately leaves the outbound start cell
     * unconstrained because the executor already stands there.</p>
     */
    public static boolean isExactConstrainedRoute(
            PathfindingResult result, BlockPos start, BlockPos goal, int minimumY) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        return result != null
                && result.success()
                && start.equals(result.resolvedStart())
                && goal.equals(result.resolvedGoal())
                && allNodesAtOrAbove(result, minimumY);
    }

    /**
     * A dig route whose corridor is its own walk-only return: every consecutive step is
     * stair-shaped, so nothing on the way home needs fresh digging or pillaring. A dig path
     * that drops more than one block cannot be walked back up, and digging on the return leg
     * is exactly the debt the route contract exists to prevent.
     */
    public static boolean isReversibleStair(PathfindingResult result) {
        Objects.requireNonNull(result, "result");
        List<Node> stair = result.path();
        for (int i = 1; i < stair.size(); i++) {
            int rise = stair.get(i).pos().getY() - stair.get(i - 1).pos().getY();
            if (rise < -1 || rise > 1) {
                return false;
            }
        }
        return !stair.isEmpty();
    }

    public static RouteValidation validateRouteContract(
            PathfindingResult outbound,
            BlockPos requestedGoal,
            RouteContract contract,
            PathfindingResult returnProof) {
        Objects.requireNonNull(requestedGoal, "requestedGoal");
        Objects.requireNonNull(contract, "contract");
        if (outbound == null || !outbound.success()) {
            return RouteValidation.reject("outbound_failed");
        }
        if (!contract.constrained()) {
            return RouteValidation.accept();
        }
        if (!requestedGoal.equals(outbound.resolvedGoal())) {
            return RouteValidation.reject("outbound_goal_not_exact");
        }
        if (!allNodesAtOrAbove(outbound, contract.minimumY())) {
            return RouteValidation.reject("outbound_below_minimum_y");
        }
        if (!contract.requiresReturnProof()) {
            return RouteValidation.accept();
        }
        if (returnProof == null || !returnProof.success()) {
            return RouteValidation.reject("return_failed");
        }
        if (!requestedGoal.equals(returnProof.resolvedStart())) {
            return RouteValidation.reject("return_start_not_exact");
        }
        if (!contract.returnAnchor().equals(returnProof.resolvedGoal())) {
            return RouteValidation.reject("return_anchor_not_exact");
        }
        if (!allNodesAtOrAbove(returnProof, contract.minimumY())) {
            return RouteValidation.reject("return_below_minimum_y");
        }
        return RouteValidation.accept();
    }

    static RouteValidation validateRuntimeReturnContract(
            PathfindingResult returnProof,
            BlockPos current,
            RouteContract contract) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(contract, "contract");
        if (!contract.constrained() || !contract.requiresReturnProof()) {
            return RouteValidation.accept();
        }
        if (returnProof == null || !returnProof.success()) {
            String reason = returnProof == null
                    ? "runtime_return_missing"
                    : "runtime_return_failed:" + returnProof.reason();
            return RouteValidation.reject(reason);
        }
        if (!current.equals(returnProof.resolvedStart())) {
            return RouteValidation.reject("runtime_return_start_not_exact");
        }
        if (!contract.returnAnchor().equals(returnProof.resolvedGoal())) {
            return RouteValidation.reject("runtime_return_anchor_not_exact");
        }
        if (!allNodesAtOrAbove(returnProof, contract.minimumY())) {
            return RouteValidation.reject("runtime_return_below_minimum_y");
        }
        return RouteValidation.accept();
    }

    private static boolean allNodesAtOrAbove(PathfindingResult result, int minimumY) {
        return result.path().stream().allMatch(node -> node.pos().getY() >= minimumY);
    }

    public record RouteContract(boolean constrained, int minimumY, BlockPos returnAnchor) {
        public RouteContract {
            if (!constrained) {
                minimumY = Integer.MIN_VALUE;
                returnAnchor = null;
            } else if (returnAnchor != null) {
                returnAnchor = returnAnchor.toImmutable();
            }
        }

        public static RouteContract unrestricted() {
            return new RouteContract(false, Integer.MIN_VALUE, null);
        }

        public static RouteContract constrainedSurface(
                int minimumY, BlockPos returnAnchor) {
            return new RouteContract(true, minimumY, returnAnchor);
        }

        public boolean requiresReturnProof() {
            return constrained && returnAnchor != null;
        }
    }

    public record RouteValidation(boolean accepted, String reason) {
        private static RouteValidation accept() {
            return new RouteValidation(true, "");
        }

        private static RouteValidation reject(String reason) {
            return new RouteValidation(false, reason);
        }
    }

    /**
     * Grants one internal replan until the executor commits real node progress. Finding another
     * path is not progress: an unreachable WalkTo target may produce the same valid A* route on
     * every timeout, so resetting here would turn the executor into an unbounded retry loop.
     */
    static final class ReplanGate {
        private boolean acquired;

        boolean tryAcquire() {
            if (acquired) {
                return false;
            }
            acquired = true;
            return true;
        }

        void resetAfterNodeAdvance() {
            acquired = false;
        }
    }

    private void cleanup(ActionPack pack) {
        if (subMiner != null) {
            subMiner.abort(pack.player());
            subMiner = null;
        }
        subWalker = null;
        digWalking = false;
        pack.stopMovement();
    }

    private static Direction faceFromPlayer(ActionPack pack, BlockPos pos) {
        Direction raw = Direction.getFacing(pack.player().getEyePos().subtract(pos.toCenterPos()));
        if (raw == Direction.UP || raw == Direction.DOWN) {
            double dx = pack.player().getX() - (pos.getX() + 0.5D);
            double dz = pack.player().getZ() - (pos.getZ() + 0.5D);
            if (Math.abs(dx) >= Math.abs(dz)) {
                return dx > 0.0D ? Direction.EAST : Direction.WEST;
            }
            return dz > 0.0D ? Direction.SOUTH : Direction.NORTH;
        }
        return raw;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
