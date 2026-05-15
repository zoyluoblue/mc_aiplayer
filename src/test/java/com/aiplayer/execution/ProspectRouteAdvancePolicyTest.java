package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProspectRouteAdvancePolicyTest {
    @Test
    void arrivedRouteDoesNotScheduleMoveToCurrentStand() {
        BlockPos current = new BlockPos(-780, 59, -1);
        BlockPos ore = new BlockPos(-780, 58, 0);
        DirectMiningRoute route = DirectMiningRoute.create(current, ore, ore.above(), Direction.SOUTH);

        assertFalse(ProspectRouteAdvancePolicy.shouldMoveToNextStand(
            route,
            current,
            route.nextStand(),
            true,
            false
        ));
    }

    @Test
    void unfinishedRouteCanMoveToWalkableNextStand() {
        BlockPos current = new BlockPos(0, 64, 0);
        DirectMiningRoute route = DirectMiningRoute.create(
            current,
            new BlockPos(5, 64, 0),
            new BlockPos(4, 64, 0),
            Direction.EAST
        );

        assertTrue(ProspectRouteAdvancePolicy.shouldMoveToNextStand(
            route,
            current,
            route.nextStand(),
            true,
            false
        ));
    }

    @Test
    void rejectedOrBlockedNextStandDoesNotMove() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos next = new BlockPos(1, 64, 0);

        assertFalse(ProspectRouteAdvancePolicy.shouldMoveToNextStand(null, current, next, false, false));
        assertFalse(ProspectRouteAdvancePolicy.shouldMoveToNextStand(null, current, next, true, true));
    }
}
