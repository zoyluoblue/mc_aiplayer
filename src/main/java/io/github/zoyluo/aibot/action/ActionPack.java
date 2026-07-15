package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.pathfinding.PathExecutor;
import io.github.zoyluo.aibot.pathfinding.PathfindingResult;
import io.github.zoyluo.aibot.pathfinding.MoveType;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Collections;
import java.util.Optional;

public final class ActionPack {
    private static final int PATHFIND_SUCCESS_COOLDOWN_TICKS = 5;
    private static final int PATHFIND_FAILURE_COOLDOWN_TICKS = 20;
    // NAV-OPT 两阶段寻路预算:纯步行只搜空气格(空间小,给足额度);挖穿限额更小,压住被困/地下时的 3D 体积爆搜。
    private static final int WALK_MAX_NODES = 10_000;
    private static final int DIG_MAX_NODES = 4_000;
    // 接近原语专用大预算:接近被包裹的矿必然要挖,直接 DIG 且预算放大(挖掘邻居分支因子小,
    // 24k 节点覆盖 ~40 格穿山直达;普通 startPathTo 的小预算 DIG 仅作走路兜底,语义不变)。
    private static final int DIG_APPROACH_MAX_NODES = 24_000;

    private final AIPlayerEntity player;

    private float forward;
    private float strafing;
    private boolean sneaking;
    private boolean sprinting;
    private boolean jumping;
    private int jumpTicks;

    private WalkToController walkTo;
    private MiningController mining;
    private PathExecutor pathExecutor;
    private int itemUseCooldown;
    private int blockHitDelay;
    private BlockPos lastPathGoal;
    private BlockPos activePathGoal;
    private int nextPathfindTick;

    public ActionPack(AIPlayerEntity player) {
        this.player = player;
    }

    public AIPlayerEntity player() {
        return player;
    }

    public void setForward(float value) {
        this.forward = clampInput(value);
    }

    public void setStrafing(float value) {
        this.strafing = clampInput(value);
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
        player.setSneaking(sneaking);
        if (sneaking && sprinting) {
            setSprinting(false);
        }
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
        player.setSprinting(sprinting);
        if (sprinting && sneaking) {
            setSneaking(false);
        }
    }

    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    public void jumpOnce() {
        this.jumpTicks = 2;
    }

    public ActionResult startWalkTo(Vec3d target) {
        this.walkTo = new WalkToController(target);
        this.mining = null;
        this.pathExecutor = null;
        return ActionResult.IN_PROGRESS;
    }

    /**
     * Short-range callers used to bypass the path executor with startWalkTo, which meant a
     * combat retreat could run face-first into a wall while normal movement knew how to dig,
     * climb, and scaffold. Keep direct walking for physics-sensitive escapes, but use this for
     * ordinary task movement so every intentional destination shares the same navigation brain.
     */
    public ActionResult startSmartWalkTo(Vec3d target) {
        return startPathTo(BlockPos.ofFloored(target));
    }

    // 统一接近原语入口:挖掘感知寻路(大预算 DIG 直达,目标可为"挖开即站"的实心格——见
    // AStarPathfinder.resolveEndpoint 的挖掘终点豁免)。接近被包裹的矿/穿山直达用这个;
    // 普通走路仍用 startPathTo(先 WALK 后小预算 DIG)。
    public ActionResult startDigPathTo(BlockPos goal) {
        int now = player.getServer().getTicks();
        BlockPos immutableGoal = goal.toImmutable();
        if (lastPathGoal != null && lastPathGoal.equals(immutableGoal) && now < nextPathfindTick) {
            return pathExecutor != null ? ActionResult.IN_PROGRESS : ActionResult.failed("pathfinding_throttled");
        }
        if (!snapPlayerToNearestStandable("path_start_invalid")) {
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        int buildBlocks = MaterialPalette.countScaffoldBlocks(player);
        boolean canPillar = buildBlocks > 0;
        PathfindingResult result = new AStarPathfinder(player.getServerWorld(), player.getBlockPos(), goal,
                DIG_APPROACH_MAX_NODES, AStarPathfinder.dynamicBudgetMillis(), canPillar, true, 10.0D).findPath();
        if (requiresTooManyBlocks(result, buildBlocks)) {
            result = new AStarPathfinder(player.getServerWorld(), player.getBlockPos(), goal,
                    DIG_APPROACH_MAX_NODES, AStarPathfinder.dynamicBudgetMillis(), false, true, 10.0D).findPath();
        }
        if (!result.success()) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        this.pathExecutor = new PathExecutor(result.path(), resolvedGoal);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    public ActionResult startPathTo(BlockPos goal) {
        int now = player.getServer().getTicks();
        BlockPos immutableGoal = goal.toImmutable();
        if (lastPathGoal != null && lastPathGoal.equals(immutableGoal) && now < nextPathfindTick) {
            return pathExecutor != null ? ActionResult.IN_PROGRESS : ActionResult.failed("pathfinding_throttled");
        }
        if (!snapPlayerToNearestStandable("path_start_invalid")) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: NO_START");
        }
        int buildBlocks = MaterialPalette.countScaffoldBlocks(player);
        boolean canPillar = buildBlocks > 0;
        ServerWorld world = player.getServerWorld();
        BlockPos from = player.getBlockPos();
        // NAV-OPT 两阶段寻路:先纯步行(禁挖,搜索空间=空气格,收敛快、不会被挖穿邻居撑爆到 SEARCH_LIMIT);
        // 纯步行无解再允许挖穿兜底(隧道/破障),挖穿预算更小以限制被困/地下时的 3D 体积爆搜。
        PathfindingResult result =
                new AStarPathfinder(world, from, goal, WALK_MAX_NODES, AStarPathfinder.dynamicBudgetMillis(), canPillar, false).findPath();
        if (requiresTooManyBlocks(result, buildBlocks)) {
            result = new AStarPathfinder(world, from, goal, WALK_MAX_NODES,
                    AStarPathfinder.dynamicBudgetMillis(), false, false).findPath();
        }
        if (!result.success()) {
            PathfindingResult dig =
                    new AStarPathfinder(world, from, goal, DIG_MAX_NODES, AStarPathfinder.dynamicBudgetMillis(), canPillar, true).findPath();
            if (requiresTooManyBlocks(dig, buildBlocks)) {
                dig = new AStarPathfinder(world, from, goal, DIG_MAX_NODES,
                        AStarPathfinder.dynamicBudgetMillis(), false, true).findPath();
            }
            if (dig.success()) {
                result = dig;
            }
        }
        if (!result.success()) {
            lastPathGoal = immutableGoal;
            activePathGoal = null;
            nextPathfindTick = now + PATHFIND_FAILURE_COOLDOWN_TICKS;
            return ActionResult.failed("pathfinding_failed: " + result.reason());
        }
        lastPathGoal = immutableGoal;
        nextPathfindTick = now + PATHFIND_SUCCESS_COOLDOWN_TICKS;
        BlockPos resolvedGoal = result.resolvedGoal() == null ? immutableGoal : result.resolvedGoal();
        activePathGoal = resolvedGoal;
        this.pathExecutor = new PathExecutor(result.path(), resolvedGoal);
        this.walkTo = null;
        this.mining = null;
        return ActionResult.IN_PROGRESS;
    }

    private static boolean requiresTooManyBlocks(PathfindingResult result, int available) {
        if (!result.success()) {
            return false;
        }
        long required = result.path().stream()
                .filter(node -> node.moveType() == MoveType.PILLAR_UP || node.moveType() == MoveType.SCAFFOLD)
                .count();
        return required > available;
    }

    public BlockPos activePathGoal() {
        return activePathGoal;
    }

    public boolean snapPlayerToNearestStandable(String reason) {
        ServerWorld world = player.getServerWorld();
        BlockPos current = player.getBlockPos();
        Standability.clearCache();
        if (Standability.isStandable(world, current)) {
            return true;
        }
        Optional<BlockPos> snapped = Standability.findNearestStandable(world, current, 8, 128, 32);
        if (snapped.isEmpty()) {
            BotLog.warn(LogCategory.PATH, player, "path_start_snap_failed", "reason", reason, "from", io.github.zoyluo.aibot.log.LogFields.pos(current));
            return false;
        }
        BlockPos safe = snapped.get();
        stopMovement();
        player.teleport(world,
                safe.getX() + 0.5D,
                safe.getY(),
                safe.getZ() + 0.5D,
                Collections.emptySet(),
                player.getYaw(),
                player.getPitch(),
                true);
        Standability.clearCache();
        BotLog.path(player, "path_start_snapped",
                "reason", reason,
                "from", io.github.zoyluo.aibot.log.LogFields.pos(current),
                "to", io.github.zoyluo.aibot.log.LogFields.pos(safe));
        return true;
    }

    /**
     * 主动把 bot 下沉一格到指定(已为空气的)格子。
     * 关键:bot 是 ServerPlayerEntity,服务端**不跑 travel()**(真实玩家的移动/重力由客户端驱动,
     * fake player 没有客户端),因此**没有被动重力**——挖空脚下不会自动下落。竖井下挖类任务
     *(DigDownTask / OreDigTask.digDownOneLayer)必须靠本方法主动驱动下沉,否则会站着空转直到看门狗失败
     *(实测:dig_down 全程 y 恒定、200t no_progress 卡死的共享根因)。
     * 幂等:bot 已在该层或更低则不动。teleport 会清零 fallDistance,不会摔伤。
     */
    public void descendInto(BlockPos target) {
        if (player.getBlockPos().getY() <= target.getY()) {
            return;
        }
        stopMovement();
        player.teleport(player.getServerWorld(),
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                Collections.emptySet(), player.getYaw(), player.getPitch(), false);
    }

    public ActionResult startMining(BlockPos pos, Direction face) {
        this.mining = new MiningController(pos, face);
        this.pathExecutor = null;
        this.forward = 0.0F;
        this.strafing = 0.0F;
        return ActionResult.IN_PROGRESS;
    }

    public void stopMining() {
        if (this.mining != null) {
            this.mining.abort(player);
            this.mining = null;
        }
    }

    public void stopMovement() {
        setSneaking(false);
        setSprinting(false);
        this.forward = 0.0F;
        this.strafing = 0.0F;
        this.jumping = false;
        this.jumpTicks = 0;
        player.setJumping(false);
    }

    public void stopAll() {
        if (pathExecutor != null) {
            pathExecutor.abort(this);
            pathExecutor = null;
        }
        activePathGoal = null;
        stopMining();
        this.walkTo = null;
        stopMovement();
    }

    public boolean isPathExecutorIdle() {
        return pathExecutor == null;
    }

    public boolean isNavigationWorkingStationary() {
        return pathExecutor != null && pathExecutor.isWorkingStationary();
    }

    public boolean isWalkToIdle() {
        return walkTo == null;
    }

    public boolean isMiningIdle() {
        return mining == null;
    }

    public void onUpdate() {
        tickPathExecutor();
        tickWalkTo();
        tickMining();

        if (itemUseCooldown > 0) {
            itemUseCooldown--;
        }
        if (blockHitDelay > 0) {
            blockHitDelay--;
        }

        float velocity = sneaking ? 0.3F : 1.0F;
        player.forwardSpeed = forward * velocity;
        player.sidewaysSpeed = strafing * velocity;
        boolean jumpNow = jumping || jumpTicks > 0;
        player.setJumping(jumpNow);
        if (jumpTicks > 0) {
            jumpTicks--;
        }
    }

    public int itemUseCooldown() {
        return itemUseCooldown;
    }

    public void setItemUseCooldown(int itemUseCooldown) {
        this.itemUseCooldown = Math.max(0, itemUseCooldown);
    }

    public int blockHitDelay() {
        return blockHitDelay;
    }

    public void setBlockHitDelay(int blockHitDelay) {
        this.blockHitDelay = Math.max(0, blockHitDelay);
    }

    private void tickWalkTo() {
        if (walkTo == null) {
            return;
        }

        ActionResult result = walkTo.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "walk_complete");
        } else if ("water_ahead".equals(result.reason())) {
            // WATER-3:被水挡路是预期停走(follow 隔水等人会每 tick 重试),记 PATH 调试级,不当错误刷屏。
            BotLog.path(player, "walk_water_blocked");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "walk_failed", "reason", result.reason());
        }
        walkTo = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickPathExecutor() {
        if (pathExecutor == null) {
            return;
        }

        ActionResult result = pathExecutor.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.path(player, "path_complete", "ticks", pathExecutor.totalTicks());
        } else {
            BotLog.warn(LogCategory.ERROR, player, "path_failed", "reason", result.reason());
        }
        pathExecutor = null;
        activePathGoal = null;
        forward = 0.0F;
        strafing = 0.0F;
        jumping = false;
        player.setJumping(false);
    }

    private void tickMining() {
        if (mining == null) {
            return;
        }

        ActionResult result = mining.tick(this);
        if (result.isInProgress()) {
            return;
        }

        if (result.isSuccess()) {
            BotLog.action(player, "mine_complete");
        } else {
            BotLog.warn(LogCategory.ERROR, player, "mine_failed", "reason", result.reason());
        }
        mining = null;
    }

    private static float clampInput(float value) {
        return Math.max(-1.0F, Math.min(1.0F, value));
    }
}
