package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectMiningRouteTest {
    @Test
    void routeTargetNeighborIsPreferredAsTargetStand() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(10, 64, 0);
        BlockPos routeTarget = new BlockPos(9, 64, 0);

        DirectMiningRoute route = DirectMiningRoute.create(current, ore, routeTarget, Direction.EAST);

        assertEquals(routeTarget, route.targetStand());
        assertEquals(Direction.EAST, route.nextDirection());
        assertEquals(new BlockPos(1, 64, 0), route.nextStand());
        assertEquals(9, route.horizontalDistance());
        assertEquals(0, route.verticalDelta());
        assertEquals("direct_horizontal", route.reason());
    }

    @Test
    void choosesNearestHorizontalNeighborWhenRouteTargetIsOre() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(5, 64, 0);

        DirectMiningRoute route = DirectMiningRoute.create(current, ore, ore, Direction.EAST);
        List<BlockPos> candidates = DirectMiningRoute.candidateStands(current, ore, ore, Direction.EAST);

        assertFalse(candidates.isEmpty());
        assertEquals(new BlockPos(4, 64, 0), route.targetStand());
        assertEquals(Direction.EAST, route.nextDirection());
        assertEquals(new BlockPos(1, 64, 0), route.nextStand());
    }

    @Test
    void sameLayerRouteAdvancesOneHorizontalSegmentAtATime() {
        BlockPos ore = new BlockPos(4, 64, 3);
        BlockPos routeTarget = new BlockPos(4, 64, 2);

        DirectMiningRoute first = DirectMiningRoute.create(new BlockPos(0, 64, 0), ore, routeTarget, Direction.EAST);
        DirectMiningRoute second = DirectMiningRoute.create(first.nextStand(), ore, routeTarget, first.nextDirection());

        assertEquals(routeTarget, first.targetStand());
        assertEquals(Direction.EAST, first.nextDirection());
        assertEquals(new BlockPos(1, 64, 0), first.nextStand());
        assertEquals(Direction.EAST, second.nextDirection());
        assertEquals(new BlockPos(2, 64, 0), second.nextStand());
        assertFalse(first.arrived());
    }

    @Test
    void descendingRouteMovesForwardAndDownOneStep() {
        DirectMiningRoute route = DirectMiningRoute.create(
            new BlockPos(0, 70, 0),
            new BlockPos(4, 66, 0),
            new BlockPos(3, 66, 0),
            Direction.EAST
        );

        assertTrue(route.needsDescent());
        assertEquals(Direction.EAST, route.nextDirection());
        assertEquals(new BlockPos(1, 69, 0), route.nextStand());
        assertEquals(-4, route.verticalDelta());
        assertEquals("direct_descent", route.reason());
    }

    @Test
    void targetAboveCurrentLayerHasNoNextStand() {
        DirectMiningRoute route = DirectMiningRoute.create(
            new BlockPos(0, 60, 0),
            new BlockPos(0, 64, 2),
            new BlockPos(0, 64, 1),
            Direction.SOUTH
        );

        assertTrue(route.targetAboveCurrentLayer());
        assertNull(route.nextStand());
        assertEquals("target_above_current_layer", route.reason());
    }

    @Test
    void verticalExposureTargetIsNotTreatedAsStandingTarget() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(5, 64, 0);

        DirectMiningRoute above = DirectMiningRoute.create(current, ore, ore.above(), Direction.EAST);
        DirectMiningRoute below = DirectMiningRoute.create(current, ore, ore.below(), Direction.EAST);

        assertEquals(new BlockPos(4, 64, 0), above.targetStand());
        assertEquals(new BlockPos(4, 64, 0), below.targetStand());
        assertEquals(Direction.EAST, above.nextDirection());
        assertEquals(Direction.EAST, below.nextDirection());
        assertEquals(new BlockPos(1, 64, 0), above.nextStand());
        assertEquals(new BlockPos(1, 64, 0), below.nextStand());
    }
}
