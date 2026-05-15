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
        assertEquals("REPROSPECT", route.routeStage());
        assertEquals("reprospect_target_above", route.currentStepText());
    }

    @Test
    void verticalExposureTargetIsNotTreatedAsStandingTarget() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos ore = new BlockPos(5, 64, 0);

        DirectMiningRoute above = DirectMiningRoute.create(current, ore, ore.above(), Direction.EAST);
        DirectMiningRoute below = DirectMiningRoute.create(current, ore, ore.below(), Direction.EAST);

        assertEquals(new BlockPos(4, 65, 0), above.targetStand());
        assertEquals(new BlockPos(4, 64, 0), below.targetStand());
        assertEquals(Direction.EAST, above.nextDirection());
        assertEquals(Direction.EAST, below.nextDirection());
        assertEquals(new BlockPos(1, 64, 0), above.nextStand());
        assertEquals(new BlockPos(1, 64, 0), below.nextStand());
    }

    @Test
    void adjacentAboveOreExposureTreatsCurrentBlockAsReadyStand() {
        BlockPos current = new BlockPos(-780, 59, -1);
        BlockPos ore = new BlockPos(-780, 58, 0);
        BlockPos exposure = ore.above();

        DirectMiningRoute route = DirectMiningRoute.create(current, ore, exposure, Direction.SOUTH);

        assertEquals(current, route.targetStand());
        assertTrue(route.arrived());
        assertEquals("EXPOSE_OR_MINE", route.routeStage());
        assertEquals("arrived", route.reason());
    }

    @Test
    void nearFieldRouteUsesEstimatedStepBudget() {
        DirectMiningRoute near = DirectMiningRoute.create(
            new BlockPos(0, 64, 0),
            new BlockPos(8, 64, 0),
            new BlockPos(7, 64, 0),
            Direction.EAST
        );
        DirectMiningRoute far = DirectMiningRoute.create(
            new BlockPos(0, 64, 0),
            new BlockPos(20, 64, 0),
            new BlockPos(19, 64, 0),
            Direction.EAST
        );

        assertTrue(near.withinNearField(10));
        assertFalse(far.withinNearField(10));
    }

    @Test
    void nearFieldBudgetIncludesVerticalDelta() {
        DirectMiningRoute vertical = DirectMiningRoute.create(
            new BlockPos(0, 70, 0),
            new BlockPos(4, 62, 0),
            new BlockPos(3, 62, 0),
            Direction.EAST
        );

        assertEquals(11, vertical.estimatedSteps());
        assertFalse(vertical.withinNearField(10));
    }

    @Test
    void statusTextExposesRouteStageAndCurrentStep() {
        DirectMiningRoute route = DirectMiningRoute.create(
            new BlockPos(0, 70, 0),
            new BlockPos(4, 66, 0),
            new BlockPos(3, 66, 0),
            Direction.EAST
        );

        String status = route.statusText();

        assertTrue(status.contains("路线阶段=阶梯下探"));
        assertTrue(status.contains("当前矿点=4, 66, 0"));
        assertTrue(status.contains("当前步=挖开前方两格空间并下降到 1, 69, 0"));
        assertTrue(route.toLogText().contains("routeStage=DESCEND"));
        assertTrue(route.toLogText().contains("currentStep=clear_forward_and_down_to_1, 69, 0"));
    }

    @Test
    void arrivedRouteStatusUsesReadableExposeOrMineStage() {
        DirectMiningRoute route = DirectMiningRoute.create(
            new BlockPos(3, 66, 0),
            new BlockPos(4, 66, 0),
            new BlockPos(3, 66, 0),
            Direction.EAST
        );

        String status = route.statusText();

        assertTrue(route.arrived());
        assertTrue(status.contains("路线阶段=暴露或挖取矿物"));
        assertFalse(status.contains("EXPOSE_OR_MINE"));
    }
}
