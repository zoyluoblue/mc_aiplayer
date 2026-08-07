package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.pathfinding.MoveType;
import io.github.zoyluo.aibot.pathfinding.Node;
import io.github.zoyluo.aibot.pathfinding.PathfindingResult;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks every Hunt movement segment to its runtime surface/return contract. */
class HuntConstrainedRouteSourceContractTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/task/HuntTask.java");

    @Test
    void exactSurfaceStarterAlwaysThreadsRuntimeContractIntoActionPack()
            throws IOException {
        String source = Files.readString(SOURCE);
        String starter = between(
                source,
                "private static SurfacePathStart startExactSurfacePath",
                "// 漫游途中持续扫猎物");

        assertFalse(starter.contains("startSurfacePathTo(destination);"),
                "Hunt must not start an unrestricted one-argument surface path");
        assertTrue(starter.contains(
                        "startSurfacePathTo(destination, minimumY)"),
                "return-to-surface must retain its runtime minimumY");
        assertTrue(starter.contains(
                        "destination, minimumY, returnAnchor"),
                "reversible segments must install their exact return anchor");
    }

    @Test
    void approachAndRoamBindEachSegmentOriginAsReturnAnchor()
            throws IOException {
        String source = Files.readString(SOURCE);
        String approach = between(
                source,
                "private SurfacePathStart startSafePreyApproach",
                "private boolean isSafePreyPose");
        String roam = between(
                source,
                "private RoamResult roamForPrey",
                "/**\n     * Produces a new deterministic surface-sampling fan");

        assertTrue(approach.contains(
                        "BlockPos returnAnchor = bot.getBlockPos().toImmutable();"));
        assertTrue(approach.contains(
                        "bot, attackPose, surfaceFloorY(bot), returnAnchor"),
                "moving-prey replans must own the start of each new approach segment");
        assertTrue(roam.contains(
                        "bot, ground, surfaceFloorY(bot), feet"),
                "ROAM execution must retain the origin used by its round-trip proof");
    }

    @Test
    void pickupPathsUseTransactionAnchorWithoutHarvestCorePathStarts()
            throws IOException {
        String source = Files.readString(SOURCE);
        String pickup = between(
                source,
                "private void pickup(AIPlayerEntity bot)",
                "private void finishPickupTransaction");
        String pickupRouting = between(
                source,
                "private BlockPos safeObservedDropStand",
                "// 只猎确定掉生肉的成年 vanilla 动物");
        String sweep = between(
                source,
                "private boolean startNextPickupSweepStep",
                "private static long pickedUpAuxiliary");

        assertFalse(pickup.contains("HarvestCore.approachDropPhysically"),
                "visible drops must not start an unrestricted HarvestCore path");
        assertFalse(pickup.contains("HarvestCore.approachKnownPickupCell"),
                "known kill cells must not start an unrestricted HarvestCore path");
        assertTrue(pickup.contains("approachPickupStand("));
        assertTrue(pickup.contains("approachKnownPickupCell("));
        assertTrue(pickupRouting.contains(
                        "bot, stand, surfaceFloorY(bot), pickupReturnAnchor"),
                "every pickup path must retain the transaction return anchor");
        assertTrue(pickupRouting.contains(
                        "FakePlayerMotion.nudgeWithinBlockToward("),
                "same-cell physical pickup may retain the bounded nudge");
        assertTrue(sweep.contains(
                        "bot, candidate, surfaceFloorY(bot), pickupReturnAnchor"),
                "pickup observation sweeps must retain the transaction anchor");
    }

    @Test
    void returnToSurfaceUsesFloorWithoutManufacturingReturnDebt()
            throws IOException {
        String source = Files.readString(SOURCE);
        String surfaceReturn = between(
                source,
                "private void beginSurfaceReturn",
                "private static String dimension");

        assertTrue(surfaceReturn.contains(
                        "bot, destination, returnFloor, null"),
                "RETURN_SURFACE needs minimumY but no reverse-route contract");
    }

    @Test
    void exactSurfaceProofRejectsSnappedOriginGoalAndBelowFloorNodes() {
        BlockPos origin = new BlockPos(0, 64, 0);
        BlockPos destination = new BlockPos(3, 64, 0);

        assertTrue(HuntTask.isExactSurfaceRouteResult(
                success(origin, destination), origin, destination, 64));
        assertFalse(HuntTask.isExactSurfaceRouteResult(
                success(origin.east(), destination), origin, destination, 64),
                "snapped origin must not be pre-reported as SAFE");
        assertFalse(HuntTask.isExactSurfaceRouteResult(
                success(origin, destination.east()), origin, destination, 64));
        assertFalse(HuntTask.isExactSurfaceRouteResult(
                success(origin, origin.east().down(), destination),
                origin, destination, 64));
    }

    private static PathfindingResult success(BlockPos... positions) {
        List<Node> nodes = new ArrayList<>();
        Node parent = null;
        for (BlockPos position : positions) {
            Node node = new Node(
                    position, nodes.size(), 0.0D, MoveType.WALK, parent);
            nodes.add(node);
            parent = node;
        }
        return PathfindingResult.success(nodes, nodes.size(), 1L);
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing source markers: " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
