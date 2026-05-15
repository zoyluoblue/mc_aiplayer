package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

public final class ProspectRouteAdvancePolicy {
    private ProspectRouteAdvancePolicy() {
    }

    public static boolean shouldMoveToNextStand(
        DirectMiningRoute route,
        BlockPos current,
        BlockPos nextStand,
        boolean nextStandWalkable,
        boolean nextStandRejected
    ) {
        if (nextStand == null || nextStandRejected || !nextStandWalkable) {
            return false;
        }
        if (current != null && current.equals(nextStand)) {
            return false;
        }
        return route == null || !route.arrived();
    }
}
