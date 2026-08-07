package io.github.zoyluo.aibot.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorReplanPolicyTest {
    private static final String OVERWORLD = "minecraft:overworld";
    @Test
    void foodHuntSurfaceDebtCannotReachBestEffortSkip() {
        Goal food = new Goal.Food(4);
        GoalStep hunt = GoalStep.hunt(1);

        for (String reason : new String[]{
                "hunt_surface_return_timeout anchor=1,64,1",
                "hunt_surface_return_unreachable anchor=1,64,1 from=1,20,1",
                "hunt_surface_anchor_missing",
                "hunt_dimension_changed expected=minecraft:overworld actual=minecraft:the_nether",
                "hunt_search_capacity_exhausted sectors=2048",
                "hunt_search_ordinal_exhausted"
        }) {
            assertTrue(GoalExecutor.isUnsettledHuntPhysicalDebt(hunt.kind(), reason),
                    () -> "missing physical debt classification for " + reason);
            assertFalse(GoalExecutor.shouldSkipFailedStep(food, hunt, reason),
                    () -> "Food/HUNT best-effort bypassed physical debt " + reason);
        }
    }

    @Test
    void foodHuntOrdinaryFailureRemainsBestEffortAndDebtIsHuntScoped() {
        Goal food = new Goal.Food(4);
        GoalStep hunt = GoalStep.hunt(1);

        assertFalse(GoalExecutor.isUnsettledHuntPhysicalDebt(
                hunt.kind(), "hunt_no_progress collected=0"));
        assertTrue(GoalExecutor.shouldSkipFailedStep(
                food, hunt, "hunt_no_progress collected=0"));
        assertFalse(GoalExecutor.isUnsettledHuntPhysicalDebt(
                GoalStep.Kind.MOVE, "hunt_surface_return_timeout anchor=1,64,1"));
        assertTrue(GoalExecutor.shouldSkipFailedStep(
                food, GoalStep.move(net.minecraft.util.math.BlockPos.ORIGIN),
                "hunt_surface_return_timeout anchor=1,64,1"));
        assertFalse(GoalExecutor.shouldSkipFailedStep(
                food, GoalStep.cookFood(4), "no_raw_food"));
    }

    @Test
    void longRareOreLimitUsesOriginalEightItemBatchCount() {
        assertEquals(24, GoalExecutor.longRareOreLifetimeReplanLimit(64));
        assertEquals(15, GoalExecutor.longRareOreLifetimeReplanLimit(33));
    }

    @Test
    void shortRareMissionsKeepBaseLimit() {
        assertEquals(12, GoalExecutor.longRareOreLifetimeReplanLimit(32));
        assertEquals(12, GoalExecutor.longRareOreLifetimeReplanLimit(7));
        assertEquals(12, GoalExecutor.longRareOreLifetimeReplanLimit(0));
    }

    @Test
    void lifetimeBoundaryAndConsecutiveNoProgressGateRemainBounded() {
        assertTrue(GoalExecutor.withinReplanBudget(24, 2, 23));
        assertFalse(GoalExecutor.withinReplanBudget(24, 3, 0));
        assertFalse(GoalExecutor.withinReplanBudget(24, 0, 24));
        assertTrue(GoalExecutor.withinReplanBudget(12, 2, 11));
        assertFalse(GoalExecutor.withinReplanBudget(12, 2, 12));
    }

    @Test
    void huntDoesNotTreatHorizontalTravelOrDescentAsProgress() {
        assertFalse(progress(
                GoalStep.Kind.HUNT,
                2, 2,
                0, 0,
                12, 12,
                7, 7,
                200, 20, 200,
                0, 70, 0));
    }

    @Test
    void huntAcceptsOnlyNetRawMeatOrNewVisitedSectorAsExtraProgress() {
        assertTrue(progress(
                GoalStep.Kind.HUNT,
                2, 2,
                0, 0,
                13, 12,
                7, 7,
                0, 70, 0,
                0, 70, 0));
        assertTrue(progress(
                GoalStep.Kind.HUNT,
                2, 2,
                0, 0,
                12, 12,
                8, 7,
                0, 70, 0,
                0, 70, 0));
    }

    @Test
    void completedStepAndGoalOutputRemainUniversalProgress() {
        assertTrue(progress(
                GoalStep.Kind.HUNT,
                3, 2,
                0, 0,
                12, 12,
                7, 7,
                0, 70, 0,
                0, 70, 0));
        assertTrue(progress(
                GoalStep.Kind.HUNT,
                2, 2,
                1, 0,
                12, 12,
                7, 7,
                0, 70, 0,
                0, 70, 0));
    }

    @Test
    void nonHuntTasksRetainControlledMovementProgress() {
        assertTrue(progress(
                GoalStep.Kind.MINE_ORE,
                2, 2,
                0, 0,
                12, 12,
                7, 7,
                8, 70, 0,
                0, 70, 0));
        assertTrue(progress(
                GoalStep.Kind.MINE_ORE,
                2, 2,
                0, 0,
                12, 12,
                7, 7,
                0, 69, 0,
                0, 70, 0));
    }

    @Test
    void nonHuntMovementProgressIsDimensionBoundAndLegacySafe() {
        assertFalse(progressInDimensions(
                GoalStep.Kind.MINE_ORE,
                2, 2,
                0, 0,
                12, 12,
                7, 7,
                "minecraft:the_nether", OVERWORLD,
                8, 69, 0,
                0, 70, 0));
        assertFalse(progressInDimensions(
                GoalStep.Kind.MINE_ORE,
                2, 2,
                0, 0,
                12, 12,
                7, 7,
                OVERWORLD, "",
                8, 69, 0,
                0, 70, 0));
        assertTrue(progressInDimensions(
                GoalStep.Kind.MINE_ORE,
                3, 2,
                0, 0,
                12, 12,
                7, 7,
                "minecraft:the_nether", OVERWORLD,
                0, 70, 0,
                0, 70, 0),
                "completed steps remain universal across dimensions");
    }

    private static boolean progress(
            GoalStep.Kind kind,
            int completedSteps, int snapshotSteps,
            int targetCount, int snapshotTargetCount,
            int huntRawMeat, int snapshotHuntRawMeat,
            int huntVisitedSectors, int snapshotHuntVisitedSectors,
            int x, int y, int z,
            int snapshotX, int snapshotY, int snapshotZ) {
        return GoalExecutor.madeReplanProgress(
                kind,
                completedSteps, snapshotSteps,
                targetCount, snapshotTargetCount,
                huntRawMeat, snapshotHuntRawMeat,
                huntVisitedSectors, snapshotHuntVisitedSectors,
                OVERWORLD, OVERWORLD,
                x, y, z,
                snapshotX, snapshotY, snapshotZ);
    }

    private static boolean progressInDimensions(
            GoalStep.Kind kind,
            int completedSteps, int snapshotSteps,
            int targetCount, int snapshotTargetCount,
            int huntRawMeat, int snapshotHuntRawMeat,
            int huntVisitedSectors, int snapshotHuntVisitedSectors,
            String dimension, String snapshotDimension,
            int x, int y, int z,
            int snapshotX, int snapshotY, int snapshotZ) {
        return GoalExecutor.madeReplanProgress(
                kind,
                completedSteps, snapshotSteps,
                targetCount, snapshotTargetCount,
                huntRawMeat, snapshotHuntRawMeat,
                huntVisitedSectors, snapshotHuntVisitedSectors,
                dimension, snapshotDimension,
                x, y, z,
                snapshotX, snapshotY, snapshotZ);
    }
}
