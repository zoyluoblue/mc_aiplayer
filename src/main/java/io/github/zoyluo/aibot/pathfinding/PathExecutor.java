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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.OptionalInt;

public final class PathExecutor {
    private static final int STUCK_TICKS_LIMIT = 60;
    private static final int REPLAN_COOLDOWN_TICKS = 40;
    private static final int DIG_APPROACH_TICKS_LIMIT = 80;

    private List<Node> path;
    private int index = 1;
    private final BlockPos originalGoal;
    private WalkToController subWalker;
    private WalkToController approachWalker;
    private int approachTicks;
    private int parkourTicks;
    private MiningController subMiner;
    private boolean digWalking;
    private boolean replanTried;
    private Vec3d lastPos;
    private int stuckTicks;
    private int totalTicks;
    private int lastReplanTick = -REPLAN_COOLDOWN_TICKS;
    private int activeWalkTargetIndex = -1;
    private int nodeRetry;
    private int buildRetries;
    private int nodeActionTicks;

    public PathExecutor(List<Node> path, BlockPos originalGoal) {
        this.path = List.copyOf(path);
        this.originalGoal = originalGoal.toImmutable();
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
            case WALK, DIAGONAL, JUMP_UP, DROP_DOWN -> tickWalk(pack, next, false);
            case SWIM -> tickWalk(pack, next, true);
            case DIG_THROUGH -> tickDigThrough(pack, next);
            case PILLAR_UP -> tickPillar(pack, next);
            case SCAFFOLD -> tickScaffold(pack, next);
            case PARKOUR -> tickParkour(pack, next);
        };
        if (!result.isInProgress()) {
            return result;
        }
        if (next.moveType() == MoveType.DIG_THROUGH
                || next.moveType() == MoveType.PILLAR_UP
                || next.moveType() == MoveType.SCAFFOLD) {
            // 挖掘和施工本来就会原地停留数秒，不能按位移判 stuck；各动作有自己的超时与重试上限。
            return ActionResult.IN_PROGRESS;
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
        return MaterialPalette.pickScaffoldBlockSlot(player).isPresent();
    }

    public boolean isWorkingStationary() {
        if (index >= path.size()) {
            return false;
        }
        MoveType type = path.get(index).moveType();
        return type == MoveType.DIG_THROUGH || type == MoveType.PILLAR_UP || type == MoveType.SCAFFOLD;
    }

    /** True while the remaining route intentionally crosses water, so the safety layer will not pull it back to shore. */
    public boolean hasWaterTraversalAhead() {
        for (int i = index; i < path.size(); i++) {
            if (path.get(i).moveType() == MoveType.SWIM) {
                return true;
            }
        }
        return false;
    }

    private ActionResult tickWalk(ActionPack pack, Node next, boolean allowDeepWater) {
        if (arrivedAt(pack.player().getBlockPos(), next.pos())) {
            advance();
            return ActionResult.IN_PROGRESS;
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
            subWalker = new WalkToController(Vec3d.ofCenter(target.pos()), allowDeepWater);
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

    private ActionResult tickDigThrough(ActionPack pack, Node next) {
        if (++nodeActionTicks > 800) {
            return handleStuck(pack, "dig_node_timeout");
        }
        // WATER-5:湿身不挖(同 DigNav)。在水里执行挖掘节点=沿水位线无限啃岸/挖穿放水,
        // 先交给 handleStuck 走一次替代路线重规划,不行就把失败上抛给任务层。
        if (pack.player().isTouchingWater()) {
            if (subMiner != null) {
                subMiner.abort(pack.player());
                subMiner = null;
            }
            return handleStuck(pack, "dig_in_water");
        }
        if (!digWalking) {
            // 头号实测卡死根因(81/90 次 path_stuck 全是 out_of_reach):路径拉直/推进后挖掘节点常离身位
            // >reach,直接建 MiningController 立即失败 → replan 常拿同一条路 → replan_throttled 任务挂。
            // 修法与真人一致:先走到够得着的地方再抡镐。每 tick 先判 reach,一进范围立刻转挖。
            if (subMiner == null && !withinDigReach(pack, next.pos())) {
                if (approachWalker == null) {
                    approachWalker = new WalkToController(Vec3d.ofCenter(next.pos()));
                    approachTicks = 0;
                }
                approachTicks++;
                if (approachTicks > DIG_APPROACH_TICKS_LIMIT) {
                    approachWalker = null;
                    return handleStuck(pack, "dig_approach_timeout");
                }
                ActionResult walk = approachWalker.tick(pack);
                if (walk.isFailed()) {
                    // 贴墙走不到方块中心是常态(目标就是要挖的实心块,walker 必然 stuck_blocked/timeout 收场)
                    // ——只要人已经贴进 reach 就算接近成功;真够不到才交给 replan。
                    approachWalker = null;
                    if (!withinDigReach(pack, next.pos())) {
                        return handleStuck(pack, "dig_approach_failed: " + walk.reason());
                    }
                } else if (!withinDigReach(pack, next.pos())) {
                    return ActionResult.IN_PROGRESS;
                }
                approachWalker = null;
                pack.stopMovement();
            }
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
            subWalker = new WalkToController(Vec3d.ofCenter(next.pos()));
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

    /**
     * 助跑平跳越沟:面向落点 → 全速前进 + 冲刺预蓄(起跳前的水平速度决定跳距,旧执行器上台阶跳
     * 强制关 sprint 的结构性抑制不适用于本节点)→ 走到沿边(脚下前方无支撑)瞬间点跳 → 落地判定推进。
     */
    private ActionResult tickParkour(ActionPack pack, Node next) {
        AIPlayerEntity player = pack.player();
        BlockPos landing = next.pos();
        if (player.isOnGround() && arrivedAt(player.getBlockPos(), landing)
                && Math.abs(player.getBlockY() - landing.getY()) <= 1) {
            pack.setSprinting(false);
            advance();
            return ActionResult.IN_PROGRESS;
        }
        parkourTicks++;
        if (parkourTicks > 40) {
            pack.stopMovement();
            return handleStuck(pack, "parkour_timeout");
        }
        Vec3d landingCenter = Vec3d.ofCenter(landing);
        LookAction.lookAt(player, landingCenter.add(0.0D, 1.0D, 0.0D)); // 瞄准要精确,走瞬时不走平滑
        pack.setForward(1.0F);
        pack.setSprinting(true);
        if (player.isOnGround() && parkourTicks >= 3) {
            // 沿边判定:前方 0.6 格的脚下已无支撑 → 到沿了,点跳(jumpOnce 单跳,不长按)
            Vec3d dir = landingCenter.subtract(player.getPos());
            double len = Math.max(1.0E-3D, Math.sqrt(dir.x * dir.x + dir.z * dir.z));
            BlockPos aheadBelow = BlockPos.ofFloored(
                    player.getX() + dir.x / len * 0.6D,
                    player.getY() - 0.5D,
                    player.getZ() + dir.z / len * 0.6D);
            if (player.getServerWorld().getBlockState(aheadBelow)
                    .getCollisionShape(player.getServerWorld(), aheadBelow).isEmpty()) {
                pack.jumpOnce();
            }
        }
        return ActionResult.IN_PROGRESS;
    }

    // Tower node: the target is one block above the current feet cell. Place exactly at the
    // original feet cell after the player clears it; never derive the destination from view yaw.
    private ActionResult tickPillar(ActionPack pack, Node next) {
        if (++nodeActionTicks > 200) {
            return handleStuck(pack, "pillar_node_timeout");
        }
        AIPlayerEntity player = pack.player();
        BlockPos placeSlot = next.pos().down(); // 当前脚位,支撑方块放这里
        if (player.getBlockY() >= next.pos().getY() && player.isOnGround()) {
            advance();
            return ActionResult.IN_PROGRESS;
        }
        pack.setForward(0.0F);
        if (isSupportingBlock(player.getServerWorld(), placeSlot)) {
            if (player.isOnGround()) {
                return ActionResult.IN_PROGRESS;
            }
            return ActionResult.IN_PROGRESS; // Placed; wait for the landing check at the top of this method.
        }
        OptionalInt slot = MaterialPalette.pickScaffoldBlockSlot(player);
        if (slot.isEmpty()) {
            return handleStuck(pack, "pillar_no_block");
        }
        InventoryAction.equipFromSlot(player, slot.getAsInt());
        if (player.isOnGround()) {
            pack.jumpOnce();
            return ActionResult.IN_PROGRESS;
        }
        // placeSlot 就是起跳前占用的脚格。实体底部越过该格顶部即可放置；旧条件用了
        // nextY + 0.99（等于要求跳近两格高），普通跳跃永远达不到，只会原地循环跳。
        if (player.getY() < placeSlot.getY() + 0.99D) {
            return ActionResult.IN_PROGRESS;
        }
        ActionResult placed = BuildAction.placeBlockAtExactly(player, placeSlot);
        if (!placed.isSuccess() || !isSupportingBlock(player.getServerWorld(), placeSlot)) {
            if (++buildRetries > 12) {
                return handleStuck(pack, "pillar_place_failed: " + placed.reason());
            }
        } else {
            buildRetries = 0;
            BotLog.path(player, "path_pillar_placed", "pos", LogFields.pos(placeSlot));
        }
        return ActionResult.IN_PROGRESS;
    }

    private ActionResult tickScaffold(ActionPack pack, Node next) {
        if (++nodeActionTicks > 200) {
            return handleStuck(pack, "scaffold_node_timeout");
        }
        AIPlayerEntity player = pack.player();
        if (arrivedAt(player.getBlockPos(), next.pos()) && isSupportingBlock(player.getServerWorld(), next.pos().down())) {
            advance();
            return ActionResult.IN_PROGRESS;
        }
        BlockPos floor = next.pos().down();
        var floorState = player.getServerWorld().getBlockState(floor);
        if (floorState.isReplaceable()) {
            OptionalInt slot = MaterialPalette.pickScaffoldBlockSlot(player);
            if (slot.isEmpty()) {
                return handleStuck(pack, "scaffold_no_block");
            }
            pack.stopMovement();
            InventoryAction.equipFromSlot(player, slot.getAsInt());
            LookAction.lookAtBlock(player, floor, Direction.UP);
            ActionResult placed = BuildAction.placeBlockAt(player, floor);
            if (!placed.isSuccess() || !isSupportingBlock(player.getServerWorld(), floor)) {
                if (++buildRetries > 12) {
                    return handleStuck(pack, "scaffold_place_failed: " + placed.reason());
                }
                return ActionResult.IN_PROGRESS;
            }
            buildRetries = 0;
            BotLog.path(player, "path_scaffold_placed", "pos", LogFields.pos(floor));
            return ActionResult.IN_PROGRESS;
        }
        if (!isSupportingBlock(player.getServerWorld(), floor)) {
            return handleStuck(pack, "scaffold_support_invalid: " + compact(floor));
        }
        return tickWalk(pack, next, false);
    }

    private static boolean isSupportingBlock(net.minecraft.server.world.ServerWorld world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return !state.isReplaceable() && !state.getCollisionShape(world, pos).isEmpty();
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
        approachWalker = null;
        approachTicks = 0;
        parkourTicks = 0;
        subMiner = null;
        digWalking = false;
        stuckTicks = 0;
        lastPos = null;
        activeWalkTargetIndex = -1;
        nodeRetry = 0;
        buildRetries = 0;
        nodeActionTicks = 0;
        replanTried = false;
    }

    /** 与 MiningController 的 reach 判定同源,留 0.3 格余量:接近到这里再开挖,绝不会秒失败 out_of_reach。 */
    private static boolean withinDigReach(ActionPack pack, BlockPos pos) {
        double reach = pack.player().getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.BLOCK_INTERACTION_RANGE);
        return pack.player().getEyePos().distanceTo(pos.toCenterPos()) <= reach + 0.2D;
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
            if (type != MoveType.WALK && type != MoveType.DIAGONAL && type != MoveType.JUMP_UP) {
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

    private static boolean arrivedAt(BlockPos current, BlockPos target) {
        int dx = current.getX() - target.getX();
        int dz = current.getZ() - target.getZ();
        return dx * dx + dz * dz <= 1 && Math.abs(current.getY() - target.getY()) <= 1;
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
        if (!replanTried) {
            int now = pack.player().getServer().getTicks();
            if (now - lastReplanTick < REPLAN_COOLDOWN_TICKS) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_throttled");
            }
            lastReplanTick = now;
            replanTried = true;
            BotLog.path(pack.player(), "path_stuck", "at_node", reason, "stuck_ticks", stuckTicks);
            if (!pack.snapPlayerToNearestStandable("path_replan_start_invalid")) {
                cleanup(pack);
                return ActionResult.failed(reason + "; replan_failed: NO_START");
            }
            boolean canPillar = hasPlaceableBlock(pack.player());
            AStarPathfinder finder = new AStarPathfinder(pack.player().getServerWorld(), pack.player().getBlockPos(), originalGoal, canPillar);
            PathfindingResult fresh = finder.findPath();
            if (fresh.success()) {
                BotLog.path(pack.player(), "path_replan", "at_node", reason, "new_path_size", fresh.path().size());
                path = fresh.path();
                index = 1;
                subWalker = null;
                approachWalker = null;
                approachTicks = 0;
                parkourTicks = 0;
                subMiner = null;
                digWalking = false;
                buildRetries = 0;
                nodeActionTicks = 0;
                stuckTicks = 0;
                lastPos = null;
                replanTried = false;
                return ActionResult.IN_PROGRESS;
            }
            reason = reason + "; replan_failed: " + fresh.reason();
        }
        cleanup(pack);
        return ActionResult.failed(reason);
    }

    private void cleanup(ActionPack pack) {
        if (subMiner != null) {
            subMiner.abort(pack.player());
            subMiner = null;
        }
        subWalker = null;
        approachWalker = null;
        approachTicks = 0;
        parkourTicks = 0;
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
