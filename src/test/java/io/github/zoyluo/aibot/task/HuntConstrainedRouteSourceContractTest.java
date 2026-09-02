package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks every Hunt movement segment to its runtime surface/return contract.
 *
 * <p>These assertions read {@code HuntTask.java} as text on purpose: loading the class would
 * initialise its {@code EntityType}/{@code Item} constants, which needs a bootstrapped Minecraft
 * registry that plain unit tests do not have. The executable geometry checks live in
 * {@code PathExecutorRouteContractTest} instead.</p>
 */
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
                        "digBreakthroughFloor(bot.getBlockPos(), attackPose, surfaceFloorY(bot))"),
                "approach execution must bind the near-level dig floor to its own segment");
        assertTrue(approach.contains("returnAnchor, true);"),
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
    void exactSurfaceProofDelegatesToTheSharedRouteContract() throws IOException {
        String source = Files.readString(SOURCE);
        String proof = between(
                source,
                "private static SurfaceRouteProof proveExactSurfaceRoute",
                "private static SurfacePathStart startExactSurfacePath");

        assertTrue(proof.contains("PathExecutor.isExactConstrainedRoute("),
                "exactness must be decided by the shared route contract, not a local copy");
        assertTrue(proof.contains("result, origin, destination, minimumY"),
                "the shared contract must be given this proof's own origin and floor");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing source markers: " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
