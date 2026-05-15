package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class AdjacentMiningStepPolicy {
    private static final double LEVEL_REACHED_DISTANCE_SQ = 0.12D;
    private static final double DESCENT_REACHED_HORIZONTAL_SQ = 0.16D;
    private static final double DESCENT_REACHED_Y_MARGIN = 0.65D;
    private static final double LEVEL_STEP_SPEED = 0.24D;
    private static final double DESCENT_STEP_SPEED = 0.34D;
    private static final double DESCENT_DROP_HORIZONTAL_SQ = 0.64D;
    private static final double DESCENT_DROP_SPEED = -0.16D;

    private AdjacentMiningStepPolicy() {
    }

    public static boolean isDescending(BlockPos currentBlock, BlockPos target) {
        return currentBlock != null && target != null && target.getY() < currentBlock.getY();
    }

    public static boolean isOneStepTarget(BlockPos currentBlock, BlockPos target) {
        if (currentBlock == null || target == null) {
            return false;
        }
        int horizontal = Math.abs(currentBlock.getX() - target.getX()) + Math.abs(currentBlock.getZ() - target.getZ());
        int vertical = currentBlock.getY() - target.getY();
        return (horizontal == 1 && vertical >= 0 && vertical <= 1)
            || (horizontal == 0 && vertical == 1);
    }

    public static boolean reached(BlockPos currentBlock, Vec3 position, BlockPos target) {
        return reached(currentBlock, position, target, isDescending(currentBlock, target));
    }

    public static boolean reached(BlockPos currentBlock, Vec3 position, BlockPos target, boolean descending) {
        if (position == null) {
            return false;
        }
        if (target == null) {
            return false;
        }
        Vec3 targetCenter = Vec3.atBottomCenterOf(target);
        double horizontalSq = horizontalDistanceSq(position, targetCenter);
        if (descending) {
            return isInsideTargetColumn(position, target)
                && horizontalSq <= DESCENT_REACHED_HORIZONTAL_SQ
                && position.y <= target.getY() + DESCENT_REACHED_Y_MARGIN;
        }
        if (target.equals(currentBlock)) {
            return true;
        }
        return position.distanceToSqr(targetCenter) <= LEVEL_REACHED_DISTANCE_SQ;
    }

    public static double progressDistanceSq(BlockPos currentBlock, Vec3 position, BlockPos target) {
        return progressDistanceSq(currentBlock, position, target, isDescending(currentBlock, target));
    }

    public static double progressDistanceSq(BlockPos currentBlock, Vec3 position, BlockPos target, boolean descending) {
        if (position == null || target == null) {
            return Double.MAX_VALUE;
        }
        Vec3 targetCenter = Vec3.atBottomCenterOf(target);
        if (!descending) {
            return position.distanceToSqr(targetCenter);
        }
        double verticalPenalty = Math.max(0.0D, position.y - (target.getY() + DESCENT_REACHED_Y_MARGIN));
        return horizontalDistanceSq(position, targetCenter) + verticalPenalty * verticalPenalty;
    }

    public static Vec3 wantedPosition(BlockPos currentBlock, Vec3 position, BlockPos target) {
        return wantedPosition(currentBlock, position, target, isDescending(currentBlock, target));
    }

    public static Vec3 wantedPosition(BlockPos currentBlock, Vec3 position, BlockPos target, boolean descending) {
        Vec3 targetCenter = Vec3.atBottomCenterOf(target);
        if (descending && position != null) {
            return new Vec3(targetCenter.x, position.y, targetCenter.z);
        }
        return targetCenter;
    }

    public static Vec3 stepVelocity(BlockPos currentBlock, Vec3 position, Vec3 currentVelocity, BlockPos target) {
        return stepVelocity(currentBlock, position, currentVelocity, target, isDescending(currentBlock, target));
    }

    public static Vec3 stepVelocity(
        BlockPos currentBlock,
        Vec3 position,
        Vec3 currentVelocity,
        BlockPos target,
        boolean descending
    ) {
        if (position == null || target == null) {
            return Vec3.ZERO;
        }
        Vec3 safeCurrentVelocity = currentVelocity == null ? Vec3.ZERO : currentVelocity;
        Vec3 targetCenter = Vec3.atBottomCenterOf(target);
        double dx = targetCenter.x - position.x;
        double dz = targetCenter.z - position.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal <= 0.02D) {
            double y = descendingDropVelocity(position, safeCurrentVelocity, target, descending, 0.0D);
            return new Vec3(0.0D, y, 0.0D);
        }
        double speed = Math.min(descending ? DESCENT_STEP_SPEED : LEVEL_STEP_SPEED, horizontal);
        double y = descendingDropVelocity(position, safeCurrentVelocity, target, descending, horizontal * horizontal);
        return new Vec3(dx / horizontal * speed, y, dz / horizontal * speed);
    }

    private static double descendingDropVelocity(
        Vec3 position,
        Vec3 currentVelocity,
        BlockPos target,
        boolean descending,
        double horizontalSq
    ) {
        if (!descending) {
            return currentVelocity.y;
        }
        if (horizontalSq <= DESCENT_DROP_HORIZONTAL_SQ && position.y > target.getY() + 0.05D) {
            return Math.min(currentVelocity.y, DESCENT_DROP_SPEED);
        }
        return currentVelocity.y;
    }

    private static double horizontalDistanceSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private static boolean isInsideTargetColumn(Vec3 position, BlockPos target) {
        return position.x >= target.getX()
            && position.x < target.getX() + 1.0D
            && position.z >= target.getZ()
            && position.z < target.getZ() + 1.0D;
    }
}
