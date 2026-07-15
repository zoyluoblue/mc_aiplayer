package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.log.LogFields;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WalkToController {
    private static final double ARRIVAL_THRESHOLD = 0.6D;
    private static final double PROGRESS_EPSILON = 0.04D;
    private static final double HARD_PROGRESS_EPSILON = 0.005D;
    private static final int MAX_TICKS = 160;
    private static final int SIDLE_STEP_TICKS = 8;

    private final Vec3d target;
    private final boolean allowDeepWater;
    private Vec3d lastPos;
    private int noProgressTicks;
    private int hardStuckTicks;
    private int sidleTicks;
    private int elapsed;

    public WalkToController(Vec3d target) {
        this(target, false);
    }

    public WalkToController(Vec3d target, boolean allowDeepWater) {
        this.target = target;
        this.allowDeepWater = allowDeepWater;
    }

    public ActionResult tick(ActionPack pack) {
        elapsed++;
        if (elapsed > MAX_TICKS) {
            pack.stopMovement();
            return ActionResult.failed("timeout");
        }

        var player = pack.player();
        ServerWorld world = player.getServerWorld();
        AIBotConfig.Nav nav = AIBotConfig.get().nav();
        Vec3d current = player.getPos();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= ARRIVAL_THRESHOLD) {
            pack.stopMovement();
            return ActionResult.SUCCESS;
        }

        Vec3d move = new Vec3d(dx / horizontalDistance, 0.0D, dz / horizontalDistance);
        // WATER-3:直线行走的入水熔断。A*(Standability)已拒深水,但 walkTo 是无图直线兜底
        // (follow 追人/接近原语),会闭眼走进湖里。前探 0.9 格:前方是游泳深度的水(上下两格都是水),
        // 或岸沿悬空、落点是深水,立即停走并以 water_ahead 失败——宁可停在岸边等,也不下水。
        // 距目标 <1.2 格时不熔断:允许走到紧贴水边的目标点(钓鱼/打水)。
        if (!allowDeepWater && horizontalDistance > 1.2D && deepWaterAhead(current, move, world)) {
            pack.stopMovement();
            return ActionResult.failed("water_ahead");
        }
        SidleCommand sidle = sidleCommand(move, nav);
        LookAction.lookHorizontallyAt(player, current.add(sidle.lookVector.multiply(4.0D)));
        pack.setForward(1.0F);
        pack.setStrafing(sidle.strafing);

        if (player.isTouchingWater()) {
            // WATER-4:碰水后跳跃键语义变了——MC 里 touchingWater 时 jump 输入不再是起跳(+0.42),
            // 而是每 tick +0.04 的游泳上浮。"落地单跳"在水里等于没跳,连一格水的岸沿都翻不上去
            // (实测:bot 掉进一格水永远出不来,卡死降级挖掘直行后开始无脑挖岸)。
            // 正解=真人做法:在水里**持续按住跳 + 前进**,浮起后借 step-up 蹬上岸沿。
            pack.setJumping(true);
            pack.setSprinting(false);
        } else {
            JumpDecision jump = shouldJump(current, move, world, nav);
            // 拟人化:只在"已落地 + 前方确有台阶/缺口"时点跳一次(单跳),绝不长按跳键。
            // 旧实现 setJumping(jump.jump) 会在障碍持续存在的多 tick 里一直按住跳——bot 落地即连跳(兔子跳),
            // 既不像正常玩家,跳跃还会拉低水平速度(实测"边跳边砍树、影响速度")。落地门控确保一台阶只跳一次。
            if (jump.jump && player.isOnGround()) {
                pack.jumpOnce();
            }
            pack.setJumping(false);
            pack.setSprinting(shouldSprint(horizontalDistance, jump, current, move, world, nav));
        }

        if (lastPos != null && current.distanceTo(lastPos) < PROGRESS_EPSILON) {
            noProgressTicks++;
        } else {
            noProgressTicks = 0;
            sidleTicks = 0;
        }
        if (lastPos != null && current.distanceTo(lastPos) < HARD_PROGRESS_EPSILON) {
            hardStuckTicks++;
        } else {
            hardStuckTicks = 0;
        }
        lastPos = current;

        boolean sidling = noProgressTicks >= nav.sidleAfter();
        if (hardStuckTicks > nav.hardLimit() && !sidling) {
            pack.stopMovement();
            logStuck(pack, "hard", current, move, world);
            return ActionResult.failed("stuck_hard");
        }
        if (sidling) {
            sidleTicks++;
        }
        if (sidleTicks > nav.sidleLimit()) {
            pack.stopMovement();
            logStuck(pack, "blocked", current, move, world);
            return ActionResult.failed("stuck_blocked");
        }
        return ActionResult.IN_PROGRESS;
    }

    private SidleCommand sidleCommand(Vec3d move, AIBotConfig.Nav nav) {
        if (noProgressTicks < nav.sidleAfter()) {
            return new SidleCommand(move, 0.0F);
        }
        int step = Math.floorMod(sidleTicks / SIDLE_STEP_TICKS, 4);
        return switch (step) {
            case 0 -> new SidleCommand(rotate(move, 35.0D), 1.0F);
            case 1 -> new SidleCommand(rotate(move, -35.0D), -1.0F);
            case 2 -> new SidleCommand(rotate(move, 60.0D), 0.7F);
            default -> new SidleCommand(rotate(move, -60.0D), -0.7F);
        };
    }

    private static JumpDecision shouldJump(Vec3d current, Vec3d move, ServerWorld world, AIBotConfig.Nav nav) {
        BlockPos front = footPos(current, move, nav.jumpReach());
        BlockState frontState = world.getBlockState(front);
        BlockState aboveFront = world.getBlockState(front.up());
        BlockPos playerPos = BlockPos.ofFloored(current);
        BlockState abovePlayer = world.getBlockState(playerPos.up());
        boolean headClear = isClear(world, front.up()) && isClear(world, playerPos.up());

        if (hasCollision(frontState, world, front)) {
            double top = collisionTop(frontState, world, front);
            if (top <= 1.0D && headClear) {
                return new JumpDecision(true, false, false);
            }
            return new JumpDecision(false, true, false);
        }

        if (isGapAhead(current, move, world) && isClear(world, abovePlayer, playerPos.up())) {
            return new JumpDecision(true, false, true);
        }
        return new JumpDecision(false, false, false);
    }

    private static boolean deepWaterAhead(Vec3d current, Vec3d move, ServerWorld world) {
        BlockPos front = footPos(current, move, 0.9D);
        if (isDeepWaterColumn(world, front)) {
            return true;
        }
        // 岸沿悬空:前方脚位是空气但正下方就是深水(走出去=直接落水)。
        return isClear(world, front) && isDeepWaterColumn(world, front.down());
    }

    // 游泳深度 = 该格与其下一格都是水;单格浅水(下面是实底)可涉,不算。
    private static boolean isDeepWaterColumn(ServerWorld world, BlockPos pos) {
        return world.getFluidState(pos).isIn(FluidTags.WATER)
                && world.getFluidState(pos.down()).isIn(FluidTags.WATER);
    }

    private static boolean isGapAhead(Vec3d current, Vec3d move, ServerWorld world) {
        BlockPos near = footPos(current, move, 1.35D);
        if (!isClear(world, near) || !isClear(world, near.up()) || !isClear(world, near.down())) {
            return false;
        }
        BlockPos landing = footPos(current, move, 2.1D);
        return isClear(world, landing)
                && isClear(world, landing.up())
                && hasCollision(world.getBlockState(landing.down()), world, landing.down());
    }

    private static boolean shouldSprint(double horizontalDistance, JumpDecision jump, Vec3d current, Vec3d move, ServerWorld world, AIBotConfig.Nav nav) {
        if (horizontalDistance < nav.sprintMinDist()) {
            return false;
        }
        if (jump.blocked || (jump.jump && !jump.gap)) {
            return false;
        }
        return clearAhead(current, move, world, 1.0D) && clearAhead(current, move, world, 2.0D);
    }

    private static boolean clearAhead(Vec3d current, Vec3d move, ServerWorld world, double distance) {
        BlockPos pos = footPos(current, move, distance);
        return isClear(world, pos) && isClear(world, pos.up());
    }

    private static boolean isClear(ServerWorld world, BlockPos pos) {
        return isClear(world, world.getBlockState(pos), pos);
    }

    private static boolean isClear(ServerWorld world, BlockState state, BlockPos pos) {
        return state.getCollisionShape(world, pos).isEmpty();
    }

    private static boolean hasCollision(BlockState state, ServerWorld world, BlockPos pos) {
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static double collisionTop(BlockState state, ServerWorld world, BlockPos pos) {
        if (!hasCollision(state, world, pos)) {
            return 0.0D;
        }
        return state.getCollisionShape(world, pos).getMax(Direction.Axis.Y);
    }

    private static BlockPos footPos(Vec3d current, Vec3d move, double distance) {
        return BlockPos.ofFloored(current.x + move.x * distance, current.y, current.z + move.z * distance);
    }

    private static Vec3d rotate(Vec3d move, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3d(move.x * cos - move.z * sin, 0.0D, move.x * sin + move.z * cos);
    }

    private static void logStuck(ActionPack pack, String reason, Vec3d current, Vec3d move, ServerWorld world) {
        BlockPos front = footPos(current, move, 1.0D);
        BlockState state = world.getBlockState(front);
        BotLog.warn(LogCategory.PATH, pack.player(), "walk_stuck",
                "reason", reason,
                "front", LogFields.pos(front),
                "front_block", Registries.BLOCK.getId(state.getBlock()),
                "yaw", Math.round(pack.player().getYaw()),
                "target", String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", current.x + move.x, current.y, current.z + move.z));
    }

    private record SidleCommand(Vec3d lookVector, float strafing) {
    }

    private record JumpDecision(boolean jump, boolean blocked, boolean gap) {
    }
}
