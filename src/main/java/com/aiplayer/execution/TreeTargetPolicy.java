package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

final class TreeTargetPolicy {
    static final double SAFE_TREE_REACH = 4.0D;
    private static final double LOG_VERTICAL_WEIGHT = 12.0D;
    private static final double STAND_VERTICAL_WEIGHT = 18.0D;
    private static final double LOG_STAND_VERTICAL_WEIGHT = 4.0D;
    private static final double LEAF_STRUCTURE_BONUS = 18.0D;
    private static final double CONNECTED_LOG_BONUS = 6.0D;
    private static final double BOTTOM_LOG_BONUS = 24.0D;

    private TreeTargetPolicy() {
    }

    static double scanCandidateScore(BlockPos current, BlockPos logPos) {
        if (current == null || logPos == null) {
            return Double.MAX_VALUE;
        }
        double distance = current.distSqr(logPos);
        int verticalDelta = Math.abs(logPos.getY() - current.getY());
        return distance + verticalDelta * verticalDelta * LOG_VERTICAL_WEIGHT;
    }

    static double workTargetScore(BlockPos current, BlockPos logPos, BlockPos standPos) {
        if (current == null || logPos == null || standPos == null) {
            return Double.MAX_VALUE;
        }
        double distanceToStand = current.distSqr(standPos);
        int standVerticalDelta = Math.abs(standPos.getY() - current.getY());
        int logStandVerticalDelta = Math.abs(logPos.getY() - standPos.getY());
        return distanceToStand
            + standVerticalDelta * standVerticalDelta * STAND_VERTICAL_WEIGHT
            + logStandVerticalDelta * logStandVerticalDelta * LOG_STAND_VERTICAL_WEIGHT;
    }

    static boolean isWithinSafeReach(double distanceSq) {
        return distanceSq <= SAFE_TREE_REACH * SAFE_TREE_REACH;
    }

    static double structureScoreAdjustment(int nearbyLeaves, int connectedLogs, boolean bottomLog) {
        int cappedLeaves = Math.max(0, Math.min(nearbyLeaves, 12));
        int cappedLogs = Math.max(0, Math.min(connectedLogs, 8));
        return -(cappedLeaves * LEAF_STRUCTURE_BONUS)
            - (cappedLogs * CONNECTED_LOG_BONUS)
            - (bottomLog ? BOTTOM_LOG_BONUS : 0.0D);
    }

    static double standFeetToBlockCenterDistanceSq(BlockPos standPos, BlockPos blockPos) {
        if (standPos == null || blockPos == null) {
            return Double.MAX_VALUE;
        }
        double dx = standPos.getX() + 0.5D - (blockPos.getX() + 0.5D);
        double dy = standPos.getY() - (blockPos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (blockPos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    static boolean shouldFailNoReachableScan(
        int noReachableScans,
        int scanLimit,
        int exploreAttempts,
        int maxExplorePoints,
        boolean hasExploreTarget
    ) {
        return noReachableScans >= scanLimit
            && exploreAttempts >= maxExplorePoints
            && !hasExploreTarget;
    }
}
