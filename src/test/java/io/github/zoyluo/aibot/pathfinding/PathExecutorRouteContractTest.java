package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExecutorRouteContractTest {
    private static final BlockPos START = new BlockPos(0, 64, 0);
    private static final BlockPos GOAL = new BlockPos(4, 64, 0);
    private static final BlockPos ANCHOR = new BlockPos(0, 64, 2);

    @Test
    void unrestrictedContractPreservesLegacySnappedAndLowRoutes() {
        PathExecutor.RouteValidation validation = PathExecutor.validateRouteContract(
                success(START, new BlockPos(2, 20, 0), GOAL.east()),
                GOAL, PathExecutor.RouteContract.unrestricted(), null);

        assertTrue(validation.accepted(), validation.reason());
    }

    @Test
    void exactOutboundAboveFloorIsAcceptedWithoutReturnProof() {
        PathExecutor.RouteContract contract =
                PathExecutor.RouteContract.constrainedSurface(60, null);

        PathExecutor.RouteValidation validation = PathExecutor.validateRouteContract(
                success(START, new BlockPos(2, 60, 0), GOAL),
                GOAL, contract, null);

        assertTrue(validation.accepted(), validation.reason());
    }

    @Test
    void outboundMustResolveExactlyAndNeverCrossFloor() {
        PathExecutor.RouteContract contract =
                PathExecutor.RouteContract.constrainedSurface(60, null);

        PathExecutor.RouteValidation snapped = PathExecutor.validateRouteContract(
                success(START, GOAL.east()), GOAL, contract, null);
        PathExecutor.RouteValidation belowFloor = PathExecutor.validateRouteContract(
                success(START, new BlockPos(2, 59, 0), GOAL),
                GOAL, contract, null);

        assertFalse(snapped.accepted());
        assertEquals("outbound_goal_not_exact", snapped.reason());
        assertFalse(belowFloor.accepted());
        assertEquals("outbound_below_minimum_y", belowFloor.reason());
    }

    @Test
    void roundTripRequiresExactStartAnchorAndFloor() {
        PathExecutor.RouteContract contract =
                PathExecutor.RouteContract.constrainedSurface(60, ANCHOR);
        PathfindingResult outbound = success(START, GOAL);

        assertTrue(PathExecutor.validateRouteContract(
                outbound, GOAL, contract, success(GOAL, ANCHOR)).accepted());

        assertEquals("return_start_not_exact", PathExecutor.validateRouteContract(
                outbound, GOAL, contract, success(GOAL.east(), ANCHOR)).reason());
        assertEquals("return_anchor_not_exact", PathExecutor.validateRouteContract(
                outbound, GOAL, contract, success(GOAL, ANCHOR.east())).reason());
        assertEquals("return_below_minimum_y", PathExecutor.validateRouteContract(
                outbound, GOAL, contract,
                success(GOAL, new BlockPos(2, 59, 1), ANCHOR)).reason());
    }

    @Test
    void failedOrMissingReturnProofIsRejected() {
        PathExecutor.RouteContract contract =
                PathExecutor.RouteContract.constrainedSurface(60, ANCHOR);
        PathfindingResult outbound = success(START, GOAL);

        assertEquals("return_failed", PathExecutor.validateRouteContract(
                outbound, GOAL, contract, null).reason());
        assertEquals("return_failed", PathExecutor.validateRouteContract(
                outbound, GOAL, contract,
                PathfindingResult.failure(FailureReason.TIMEOUT, 200, 51L)).reason());
    }

    @Test
    void runtimeReturnLeaseRequiresFreshExactCurrentAnchorAndFloor() {
        PathExecutor.RouteContract contract =
                PathExecutor.RouteContract.constrainedSurface(60, ANCHOR);

        assertTrue(PathExecutor.validateRuntimeReturnContract(
                success(START, ANCHOR), START, contract).accepted());
        assertEquals("runtime_return_start_not_exact",
                PathExecutor.validateRuntimeReturnContract(
                        success(START.east(), ANCHOR), START, contract).reason());
        assertEquals("runtime_return_anchor_not_exact",
                PathExecutor.validateRuntimeReturnContract(
                        success(START, ANCHOR.east()), START, contract).reason());
        assertEquals("runtime_return_below_minimum_y",
                PathExecutor.validateRuntimeReturnContract(
                        success(START, new BlockPos(1, 59, 1), ANCHOR),
                        START, contract).reason());
        assertEquals("runtime_return_failed:TIMEOUT",
                PathExecutor.validateRuntimeReturnContract(
                        PathfindingResult.failure(
                                FailureReason.TIMEOUT, 200, 51L),
                        START, contract).reason());
    }

    private static PathfindingResult success(BlockPos... positions) {
        List<Node> nodes = new ArrayList<>();
        Node parent = null;
        for (BlockPos position : positions) {
            Node node = new Node(position, nodes.size(), 0.0D, MoveType.WALK, parent);
            nodes.add(node);
            parent = node;
        }
        return PathfindingResult.success(nodes, nodes.size(), 1L);
    }
}
