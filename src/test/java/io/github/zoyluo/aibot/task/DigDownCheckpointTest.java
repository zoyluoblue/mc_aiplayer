package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigDownCheckpointTest {
    private static final BlockPos START = new BlockPos(4, 64, -3);
    private static final List<BlockPos> TRAIL = List.of(
            START,
            START.add(1, -1, 0),
            START.add(2, -2, 0),
            START.add(2, -2, 1));

    @Test
    void returnCheckpointRoundTripsEveryDebtField() {
        Map<String, String> values = checkpoint(DigDownTask.Phase.RETURN, TRAIL, 1, 317);
        DigDownTask.DigDownCheckpoint restored = DigDownTask.DigDownCheckpoint.decode(values).orElseThrow();

        assertEquals(START, restored.startPos());
        assertEquals(62, restored.targetY());
        assertEquals(TRAIL, restored.trail());
        assertEquals(1, restored.returnTrailIndex());
        assertEquals(317, restored.returnBudgetUsed());
        assertTrue(restored.returnPathFallback());
        assertEquals(DigDownTask.Phase.RETURN, restored.resumePhase());
        assertEquals(values, restored.encode());
    }

    @Test
    void descendingCheckpointKeepsItsTrailAndDoesNotInventReturnDebt() {
        Map<String, String> values = new LinkedHashMap<>(
                checkpoint(DigDownTask.Phase.RETURN, TRAIL, 1, 0));
        values.put("phase", DigDownTask.Phase.DESCEND.name());
        values.put("collected", "1");
        values.put("return_index", "-1");
        values.put("return_budget_used", "0");
        values.put("last_return_path_budget", "-20");
        values.put("return_path_fallback", "false");

        DigDownTask.DigDownCheckpoint restored = DigDownTask.DigDownCheckpoint.decode(values).orElseThrow();
        assertEquals(DigDownTask.Phase.DESCEND, restored.resumePhase());
        assertEquals(TRAIL, restored.trail());
        assertEquals(-1, restored.returnTrailIndex());
    }

    @Test
    void sameHeightFarAwayIsNotACompletedReturn() {
        assertFalse(DigDownTask.hasReturned(START, START.add(20, 0, 0)));
        assertTrue(DigDownTask.hasReturned(START, START));
    }

    @Test
    void unsafeOrInterruptedEntriesAreQuarantinedWithoutPoisoningToolRecovery() {
        assertTrue(DigDownTask.shouldExcludeEntry(DigDownTask.ReturnOutcome.WALLED, false));
        assertTrue(DigDownTask.shouldExcludeEntry(
                DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED, false));
        assertTrue(DigDownTask.shouldExcludeEntry(
                DigDownTask.ReturnOutcome.NO_PROGRESS, true));
        assertTrue(DigDownTask.shouldExcludeEntry(
                DigDownTask.ReturnOutcome.TIMEOUT, true));
        assertFalse(DigDownTask.shouldExcludeEntry(
                DigDownTask.ReturnOutcome.NO_PROGRESS, false));
        assertFalse(DigDownTask.shouldExcludeEntry(
                DigDownTask.ReturnOutcome.TIMEOUT, false));
        assertFalse(DigDownTask.shouldExcludeEntry(
                DigDownTask.ReturnOutcome.NEED_BETTER_TOOL, true));
    }

    @Test
    void threeAxisDiagonalAndLongJumpAreNeverMicroSteps() {
        assertFalse(DigDownTask.isValidReturnMicroStep(START, START.add(1, 1, 1)));
        assertFalse(DigDownTask.isValidReturnMicroStep(START, START.add(2, 0, 0)));
        assertTrue(DigDownTask.isValidReturnMicroStep(START, START.add(1, -1, 0)));

        List<BlockPos> forged = new ArrayList<>(TRAIL);
        forged.set(1, START.add(1, -1, 1));
        assertFalse(DigDownTask.DigDownCheckpoint.decode(
                checkpoint(DigDownTask.Phase.RETURN, forged, 1, 20)).isPresent(),
                "a checkpoint may not smuggle a three-axis teleport into the factual trail");
    }

    @Test
    void returnBudgetIsIndependentBoundedAndFailClosed() {
        assertTrue(DigDownTask.DigDownCheckpoint.decode(
                checkpoint(DigDownTask.Phase.RETURN, TRAIL, 1, 600)).isPresent());

        Map<String, String> exhausted = new LinkedHashMap<>(
                checkpoint(DigDownTask.Phase.RETURN, TRAIL, 1, 600));
        exhausted.put("return_budget_used", "601");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(exhausted).isPresent());

        Map<String, String> resetWorkBudget = new LinkedHashMap<>(
                checkpoint(DigDownTask.Phase.RETURN, TRAIL, 1, 300));
        resetWorkBudget.put("work_budget_used", "2401");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(resetWorkBudget).isPresent());
    }

    @Test
    void currentSafetyReturnCheckpointPersistsProgressLeaseAndHardCap() {
        Map<String, String> values = new DigDownTask.DigDownCheckpoint(
                4,
                "minecraft:stone",
                3,
                DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.COMPLETE,
                START,
                62,
                0,
                3,
                2400,
                2390,
                0,
                0,
                0,
                false,
                null,
                0,
                TRAIL,
                1,
                1200,
                1180,
                true,
                1175,
                1,
                49L,
                true).encode();

        DigDownTask.DigDownCheckpoint restored =
                DigDownTask.DigDownCheckpoint.decode(values).orElseThrow();
        assertEquals(1175, restored.lastReturnProgressBudget());
        assertEquals(1, restored.returnProgressWaypointIndex());
        assertEquals(49L, restored.returnBestDistanceSquared());
        assertTrue(restored.returnSafetyRecovery());
        assertEquals(values, restored.encode());

        Map<String, String> ordinaryOverLegacyLimit = new LinkedHashMap<>(values);
        ordinaryOverLegacyLimit.put("return_safety_recovery", "false");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(ordinaryOverLegacyLimit).isPresent(),
                "ordinary return accepted a budget beyond its 600-tick hard cap");

        Map<String, String> beyondSafetyHardCap = new LinkedHashMap<>(values);
        beyondSafetyHardCap.put("return_budget_used", "2401");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(beyondSafetyHardCap).isPresent(),
                "safety recovery accepted a budget beyond its 2400-tick hard cap");

        Map<String, String> forgedProgressClock = new LinkedHashMap<>(values);
        forgedProgressClock.put("return_last_progress_budget", "1201");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(forgedProgressClock).isPresent(),
                "checkpoint accepted a progress clock ahead of total return time");
    }

    @Test
    void largeQuotaWorkBudgetScalesAndCheckpointUsesTheExactInclusiveBoundary() {
        int smallBudget = DigDownTask.maxWorkBudgetForTarget("minecraft:stone", 3);
        int largeBudget = DigDownTask.maxWorkBudgetForTarget("minecraft:stone", 64);

        assertEquals(2400, smallBudget, "small bootstrap quota lost its two-minute baseline");
        assertEquals(7040, largeBudget,
                "64-block stone quota did not budget its 24-block return reserve");
        assertEquals(8000,
                DigDownTask.maxWorkBudgetForTarget("minecraft:stone", 76),
                "strict obsidian bootstrap lost its exact large-stone work budget");
        assertEquals(24000,
                DigDownTask.maxWorkBudgetForTarget("minecraft:stone", 4096),
                "scaled work budget exceeded its bounded cap");

        Map<String, String> exactBoundary = new DigDownTask.DigDownCheckpoint(
                3,
                "minecraft:stone",
                64,
                DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                START,
                62,
                0,
                61,
                largeBudget,
                largeBudget,
                0,
                0,
                0,
                false,
                null,
                0,
                TRAIL,
                -1,
                0,
                -20,
                false).encode();

        assertTrue(DigDownTask.DigDownCheckpoint.decode(exactBoundary).isPresent(),
                "checkpoint rejected the exact scaled work-budget boundary");
        Map<String, String> overBoundary = new LinkedHashMap<>(exactBoundary);
        overBoundary.put("work_budget_used", String.valueOf(largeBudget + 1));
        assertFalse(DigDownTask.DigDownCheckpoint.decode(overBoundary).isPresent(),
                "checkpoint accepted one tick beyond the scaled work-budget boundary");
    }

    @Test
    void horizontalFrontierSettleDebtRoundTripsAtTheHardWorkBoundary() {
        int maxBudget = DigDownTask.maxWorkBudgetForTarget("minecraft:stone", 1);
        Map<String, String> values = new DigDownTask.DigDownCheckpoint(
                3,
                "minecraft:stone",
                1,
                DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                START,
                START.getY(),
                0,
                0,
                maxBudget,
                maxBudget,
                7,
                0,
                0,
                true,
                null,
                0,
                List.of(START),
                -1,
                0,
                -20,
                false).encode();

        DigDownTask.DigDownCheckpoint restored =
                DigDownTask.DigDownCheckpoint.decode(values).orElseThrow();
        assertEquals(DigDownTask.Phase.DESCEND, restored.resumePhase());
        assertEquals(maxBudget, restored.workBudgetUsed());
        assertEquals(7, restored.pickupGrace());
        assertTrue(restored.horizontalMode());
        assertEquals(values, restored.encode());
    }

    @Test
    void schemaShapeTargetAndMinimumYAreValidated() {
        Map<String, String> valid = checkpoint(DigDownTask.Phase.RETURN, TRAIL, 1, 20);

        Map<String, String> wrongSchema = new LinkedHashMap<>(valid);
        wrongSchema.put("schema", "2");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(wrongSchema).isPresent());

        Map<String, String> extra = new LinkedHashMap<>(valid);
        extra.put("unknown", "value");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(extra).isPresent());

        Map<String, String> wrongY = new LinkedHashMap<>(valid);
        wrongY.put("target_y", "61");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(wrongY).isPresent());

        Map<String, String> malformedTarget = new LinkedHashMap<>(valid);
        malformedTarget.put("target_block", "not an identifier");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(malformedTarget).isPresent());
    }

    @Test
    void currentSchemaPersistsRejectedWaterSealDirection() {
        BlockPos face = TRAIL.getLast();
        Map<String, String> values = new DigDownTask.DigDownCheckpoint(
                2,
                "minecraft:stone",
                3,
                DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                START,
                62,
                0,
                1,
                200,
                200,
                0,
                0,
                0,
                false,
                face,
                1,
                TRAIL,
                -1,
                0,
                -20,
                false).encode();

        DigDownTask.DigDownCheckpoint restored =
                DigDownTask.DigDownCheckpoint.decode(values).orElseThrow();
        assertEquals(face, restored.rejectedLandingOrigin());
        assertEquals(1, restored.rejectedLandingDirections());
        assertEquals(values, restored.encode());

        Map<String, String> invalidMask = new LinkedHashMap<>(values);
        invalidMask.put("rejected_landing_directions", "16");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(invalidMask).isPresent());
    }

    @Test
    void failureReturnOutcomeSurvivesRestartButCannotAppearDuringDescent() {
        Map<String, String> values = new DigDownTask.DigDownCheckpoint(
                3,
                "minecraft:stone",
                8,
                DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.TIMEOUT,
                START,
                62,
                0,
                2,
                2400,
                2399,
                0,
                0,
                0,
                false,
                null,
                0,
                TRAIL,
                2,
                17,
                0,
                false).encode();

        DigDownTask.DigDownCheckpoint restored =
                DigDownTask.DigDownCheckpoint.decode(values).orElseThrow();
        assertEquals(DigDownTask.ReturnOutcome.TIMEOUT, restored.returnOutcome());
        assertEquals(values, restored.encode());

        Map<String, String> forgedDescent = new LinkedHashMap<>(values);
        forgedDescent.put("phase", DigDownTask.Phase.DESCEND.name());
        forgedDescent.put("return_index", "-1");
        forgedDescent.put("return_budget_used", "0");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(forgedDescent).isPresent(),
                "a terminal failure outcome may exist only while exact return debt is active");
    }

    @Test
    void safetyInterruptedReturnDebtRoundTripsAndRemainsARequiredReturn() {
        Map<String, String> values = new DigDownTask.DigDownCheckpoint(
                3,
                "minecraft:stone",
                8,
                DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                START,
                62,
                0,
                2,
                2400,
                200,
                0,
                0,
                0,
                false,
                null,
                0,
                TRAIL,
                TRAIL.size() - 1,
                0,
                -20,
                false).encode();

        DigDownTask.DigDownCheckpoint restored =
                DigDownTask.DigDownCheckpoint.decode(values).orElseThrow();
        assertEquals(DigDownTask.Phase.RETURN, restored.resumePhase());
        assertEquals(DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                restored.returnOutcome());
        assertEquals(TRAIL.size() - 1, restored.returnTrailIndex(),
                "the factual tail must be the first waypoint after a safety displacement");
        assertEquals(values, restored.encode());

        Map<String, String> forgedDescent = new LinkedHashMap<>(values);
        forgedDescent.put("phase", DigDownTask.Phase.DESCEND.name());
        forgedDescent.put("return_index", "-1");
        assertFalse(DigDownTask.DigDownCheckpoint.decode(forgedDescent).isPresent(),
                "an interrupted return outcome may not be downgraded to fresh descent work");
    }

    @Test
    void stoneDescentGrossReserveTracksDepthAcrossEveryCheckpointSchema() {
        for (int schema = 1; schema <= 4; schema++) {
            assertGrossReserveBoundary(schema, 2, 4, 5);
            assertGrossReserveBoundary(schema, 24, 26, 27);
        }
    }

    private static void assertGrossReserveBoundary(int schema, int depth,
                                                   int beforeThreshold, int atThreshold) {
        List<BlockPos> trail = new ArrayList<>();
        for (int step = 0; step <= depth; step++) {
            trail.add(START.add(step, -step, 0));
        }
        Map<String, String> values = new DigDownTask.DigDownCheckpoint(
                schema,
                "minecraft:stone",
                3,
                DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                START,
                START.getY() - depth,
                0,
                beforeThreshold,
                200,
                190,
                0,
                0,
                0,
                false,
                null,
                0,
                trail,
                -1,
                0,
                -20,
                false).encode();

        assertEquals(DigDownTask.Phase.DESCEND,
                DigDownTask.DigDownCheckpoint.decode(values).orElseThrow().resumePhase(),
                "schema=" + schema + " depth=" + depth + " promoted before return reserve");
        Map<String, String> collectedEnough = new LinkedHashMap<>(values);
        collectedEnough.put("collected", String.valueOf(atThreshold));
        assertEquals(DigDownTask.Phase.RETURN,
                DigDownTask.DigDownCheckpoint.decode(collectedEnough).orElseThrow().resumePhase(),
                "schema=" + schema + " depth=" + depth + " did not promote at gross target");
    }

    private static Map<String, String> checkpoint(DigDownTask.Phase phase,
                                                   List<BlockPos> trail,
                                                   int returnIndex,
                                                   int returnBudget) {
        int targetY = trail.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        return new DigDownTask.DigDownCheckpoint(
                1,
                "minecraft:stone",
                3,
                phase,
                DigDownTask.ReturnOutcome.COMPLETE,
                START,
                targetY,
                0,
                3,
                2400,
                2390,
                0,
                0,
                0,
                false,
                null,
                0,
                trail,
                returnIndex,
                returnBudget,
                Math.min(returnBudget, 300),
                phase == DigDownTask.Phase.RETURN).encode();
    }
}
