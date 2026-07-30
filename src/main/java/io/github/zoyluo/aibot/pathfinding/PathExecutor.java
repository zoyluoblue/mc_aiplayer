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

public final class PathExecutor {
    private static final int STUCK_TICKS_LIMIT = 60;
    private static final int REPLAN_COOLDOWN_TICKS = 40;

    private List<Node> path;
    private int index = 1;
    private final BlockPos originalGoal;
    private final boolean replanCanPillar;
    private final boolean replanAllowDig;
    private final int protectedStoneLikeReserve;
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
        this.path = List.copyOf(path);
        this.originalGoal = originalGoal.toImmutable();
        this.replanCanPillar = replanCanPillar;
        this.replanAllowDig = replanAllowDig;
        this.protectedStoneLikeReserve = Math.max(0, protectedStoneLikeReserve);
    }

    public ActionResult tick(ActionPack pack) {
        totalTicks++;
        if (path.isEmpty() || index >= path.size()) {
            cleanup(pack);
            double distSq = pack.player().getBlockPos().getSquaredDistance(originalGoal);
            if (distSq > 4.0D) {
                BotLog.warn(LogCategory.PATH, pack.player(), "path_end_far_from_goal",
                        "dist_sq", distSq, "goal", LogFields.pos(originalGoal));
                return ActionResult.failed("ended_far_from_goal dist_sq=" + (int) distSq);
            }
            return ActionResult.SUCCESS;
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
            advance();
            return ActionResult.IN_PROGRESS;
        }
        if (next.moveType() == MoveType.JUMP_UP) {
            if (FakePlayerMotion.jumpTo(pack.player(), next.pos(), "path_jump_up")) {
                BotLog.path(pack.player(), "path_jump_complete", "to", LogFields.pos(next.pos()));
                advance();
                return ActionResult.IN_PROGRESS;
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
            advanceTo(activeWalkTargetIndex + 1);
            return ActionResult.IN_PROGRESS;
        }
        ActionResult result = subWalker.tick(pack);
        if (result.isSuccess()) {
            advanceTo(activeWalkTargetIndex + 1);
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
            advance();
            return ActionResult.IN_PROGRESS;
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
        if (step.equals(target)) {
            BotLog.path(player, "path_drop_complete", "to", LogFields.pos(target));
            advance();
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

        ActionResult walk = subWalker.tick(pack);
        if (walk.isSuccess()) {
            advance();
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
            advance();
            return ActionResult.IN_PROGRESS;
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
                advance();
                return ActionResult.IN_PROGRESS;
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

    private void advance() {
        advanceTo(index + 1);
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
            if (!pack.snapPlayerToNearestStandable("path_replan_start_invalid")) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_failed: NO_START");
            }
            // A runtime obstruction may narrow an existing route, but it may never broaden the
            // caller's original movement contract. In particular, surface/pickup paths are created
            // without digging or disposable pillars; silently enabling either here can destroy the
            // wall hiding a finite drop or consume mission materials. Pillaring also remains gated
            // by the current inventory because a previously available support may have been spent.
            boolean canPillar = replanCanPillar
                    && hasPlaceableBlock(pack.player(), protectedStoneLikeReserve);
            AStarPathfinder finder = new AStarPathfinder(
                    pack.player().getServerWorld(), pack.player().getBlockPos(), originalGoal,
                    canPillar, replanAllowDig);
            PathfindingResult fresh = finder.findPath();
            if (fresh.success()) {
                BotLog.path(pack.player(), "path_replan", "at_node", reason, "new_path_size", fresh.path().size());
                path = fresh.path();
                index = 1;
                subWalker = null;
                subMiner = null;
                digWalking = false;
                stuckTicks = 0;
                lastPos = null;
                return ActionResult.IN_PROGRESS;
            }
            reason = reason + "; replan_failed: " + fresh.reason();
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
