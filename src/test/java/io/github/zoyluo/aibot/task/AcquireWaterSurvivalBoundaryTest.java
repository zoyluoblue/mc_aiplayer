package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guardrails for the strict-survival surface-water expedition. */
class AcquireWaterSurvivalBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void visibleWaterAndVanillaBucketInteractionAreTheOnlySuccessPath() throws IOException {
        String task = read("task/AcquireWaterTask.java");
        int scan = task.indexOf("nearestObservableWaterSource");
        int visibility = task.indexOf("ObservableWorldQuery.canObserveCell(bot, candidate)", scan);
        int fluidRead = task.indexOf("world.getFluidState(candidate)", visibility);
        int bucket = task.indexOf("BucketAction.fillWaterSource(bot, waterSource)");

        assertTrue(scan >= 0 && visibility > scan && fluidRead > visibility,
                "fluid state must be read only after an observable-cell gate");
        assertTrue(bucket >= 0,
                "water acquisition must finish through the vanilla bucket adapter");
        assertTrue(task.contains("InventoryAction.countItem(bot, Items.WATER_BUCKET)"),
                "inventory observation must be the postcondition");

        assertFalse(task.contains("setBlockState("));
        assertFalse(task.contains("InventoryAction.giveItem("));
        assertFalse(task.contains("InventoryAction.removeItems("));
        assertFalse(task.contains("teleport("));
        assertTrue(task.contains("FakePlayerMotion.shiftToSupportEdge(")
                        && task.contains("FakePlayerMotion.returnToBlockCenter("),
                "multi-level cave ascent must pay for an isolated support with a bounded"
                        + " sneak-bridge placement");
        assertFalse(task.contains("FakePlayerMotion.stepTo("),
                "water acquisition itself must not manufacture adjacent travel");
        assertFalse(task.contains("FakePlayerMotion.jumpTo("),
                "ascent landing must remain owned by the ordinary surface path executor");
        assertFalse(task.contains("EMERGENCY_TELEPORT"));
        assertFalse(task.contains("OreProspector"));
    }

    @Test
    void searchAndRestartBudgetsFailClosed() throws IOException {
        String task = read("task/AcquireWaterTask.java");
        String executor = read("goal/GoalExecutor.java");

        assertTrue(task.contains("implements CheckpointableTask"));
        assertTrue(task.contains("CHECKPOINT_SCHEMA"));
        assertTrue(task.contains("budget_used"));
        assertTrue(task.contains("acquire_water_invalid_checkpoint"));
        assertTrue(task.contains("acquire_water_search_exhausted"));
        assertTrue(task.contains("acquire_water_timeout"));
        assertTrue(task.contains("boolean limitPair")
                        && task.contains("!issuedInBounds")
                        && task.contains("budget < 0 || budget > elapsedLimit"),
                "checkpoint decoder must accept only a task-owned waypoint/time authority pair");
        assertTrue(task.contains("SearchCursor.afterIssued(issued)")
                        && task.contains("!cursorShape || !waypointShape")
                        && task.contains("Math.abs((long) gridX)"),
                "checkpoint decoder must bind issued count to a safe deterministic cursor");
        assertTrue(task.contains("PREVIOUS_CHECKPOINT_SCHEMA = 3")
                        && task.contains("LEGACY_MAX_WAYPOINTS = 100")
                        && task.contains("LEGACY_MAX_ELAPSED = 12_000")
                        && task.contains("values.put(\"waypoint_limit\"")
                        && task.contains("values.put(\"budget_limit\""),
                "schema 4 must widen new searches without reviving schema-2/3 authority");
        assertTrue(task.contains("reached < 0 || reached > issued"));
        assertTrue(task.contains("waypoint_started_budget"));
        assertTrue(task.contains("boolean surfacePhaseShape = surfaceExit")
                        && task.contains("!surfacePhaseShape"),
                "all checkpoint schemas must reject SEARCH/APPROACH without a surface latch");
        assertFalse(task.contains("surfaceExit = true;"),
                "legacy checkpoint migration must not mint a surface-exit latch");
        assertTrue(executor.contains("plan.peekTaskCheckpoint(GoalStep.Kind.ACQUIRE_WATER)"),
                "mission restart must retain the old water checkpoint until a successor is trusted");
    }

    @Test
    void ascentWorldReadsStayBehindObservableCellGates() throws IOException {
        String task = read("task/AcquireWaterTask.java");
        int inspect = task.indexOf("private static AscentCandidate inspectAscentCandidate");
        int arcLoop = task.indexOf("for (BlockPos cell :", inspect);
        int arcGate = task.indexOf("observableCellOrBlock(bot, cell)", arcLoop);
        int arcRead = task.indexOf("world.getBlockState(cell)", arcLoop);
        int supportGate = task.indexOf("observableCellOrBlock(bot, support)", arcRead);
        int supportRead = task.indexOf("world.getBlockState(support)", supportGate);

        assertTrue(inspect >= 0 && arcLoop > inspect && arcGate > arcLoop && arcRead > arcGate,
                "jump-arc state must be read behind an observable-cell gate");
        assertTrue(supportGate > arcRead && supportRead > supportGate,
                "ascent support state must be read only after the visible arc obstruction is"
                        + " removed and its own observable-cell gate passes");
        assertTrue(task.contains("hasObservableAdjacentFluid(bot, world, cell)"),
                "adjacent fluid evidence must use the observable-world adapter");
        assertTrue(task.contains("ObservableWorldQuery.canObserveCell(bot, pos)")
                        && task.contains("ObservableWorldQuery.canObserveBlock(bot, pos)"),
                "empty and solid ascent cells must each use the matching observable query");
        assertFalse(task.contains("firstAscentObstruction(ServerWorld"),
                "a second ungated obstruction scan must not bypass the inspected arc result");
    }

    @Test
    void carvedRelocationRechecksVisibleDryCellsBeforePhysicalMovement() throws IOException {
        String task = read("task/AcquireWaterTask.java");
        int inspect = task.indexOf(
                "private static CarvedRelocationCandidate inspectCarvedAscentRelocation");
        int orderedCells = task.indexOf(
                "new BlockPos[]{target.up(), target}", inspect);
        int cellGate = task.indexOf("observableCellOrBlock(bot, cell)", orderedCells);
        int stateRead = task.indexOf("world.getBlockState(cell)", cellGate);
        int fluidGuard = task.indexOf("hasObservableAdjacentFluid(bot, world, cell)", stateRead);
        int landingProof = task.indexOf(
                "safeObservableRelocation(bot, world, target)", fluidGuard);
        int tick = task.indexOf("private boolean tickAscentRelocation");
        int movement = task.indexOf(
                "startSurfacePathTo(ascentRelocationTarget)", tick);

        assertTrue(inspect >= 0 && orderedCells > inspect,
                "carved relocation must inspect head before feet");
        assertTrue(cellGate > orderedCells && stateRead > cellGate && fluidGuard > stateRead,
                "each carved relocation block read must follow its observable gate and fluid check");
        assertTrue(landingProof > fluidGuard,
                "support/standability proof must happen only after both pocket cells are clear");
        assertTrue(movement >= 0,
                "carved relocation movement must stay with the ordinary surface path executor");
        assertTrue(task.contains("MAX_ASCENT_RELOCATIONS_PER_LEVEL"),
                "carved relocation must retain the bounded per-level movement budget");
    }

    @Test
    void reusableSurfaceEgressReadsStayBehindObservableStandCellGates() throws IOException {
        String task = read("task/AcquireWaterTask.java");
        int helper = task.indexOf("private static boolean hasReusableSurfaceEgress");
        assertTrue(helper >= 0, "reusable surface-egress helper is missing");
        int helperEnd = task.indexOf("/**", helper);
        assertTrue(helperEnd > helper, "reusable surface-egress helper boundary is missing");
        String body = task.substring(helper, helperEnd);

        int sameLevel = body.indexOf("BlockPos sameLevel");
        int sameLevelGate = body.indexOf("observableStandCell(bot, sameLevel)", sameLevel);
        int sameLevelSkyRead = body.indexOf("world.isSkyVisible(sameLevel)", sameLevelGate);
        int sameLevelStateRead = body.indexOf(
                "Standability.isStandable(world, sameLevel)", sameLevelGate);
        int uphill = body.indexOf("BlockPos uphill", sameLevelStateRead);
        int uphillGate = body.indexOf("observableStandCell(bot, uphill)", uphill);
        int uphillSkyRead = body.indexOf("world.isSkyVisible(uphill)", uphillGate);
        int uphillStateRead = body.indexOf(
                "Standability.isStandable(world, uphill)", uphillGate);
        int supportStateRead = body.indexOf("world.getBlockState(sameLevel)", uphillStateRead);

        assertTrue(sameLevelGate > sameLevel
                        && sameLevelSkyRead > sameLevelGate
                        && sameLevelStateRead > sameLevelGate,
                "same-level surface candidate reads must follow its observable stand-cell gate");
        assertTrue(uphillGate > uphill
                        && uphillSkyRead > uphillGate
                        && uphillStateRead > uphillGate
                        && supportStateRead > uphillStateRead,
                "uphill surface candidate and support reads must follow its observable gate");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
