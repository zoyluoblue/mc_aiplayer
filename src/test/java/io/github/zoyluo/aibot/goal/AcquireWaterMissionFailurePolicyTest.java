package io.github.zoyluo.aibot.goal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source guardrail: an exhausted physical water search must not receive a fresh mission budget. */
class AcquireWaterMissionFailurePolicyTest {
    private static final Path EXECUTOR = Path.of(
            "src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java");

    @Test
    void exhaustedOrInvalidWaterSearchTerminatesTheMissionFailClosed() throws IOException {
        String source = Files.readString(EXECUTOR);
        int handler = source.indexOf("private void handleStepFailure");
        int replan = source.indexOf("GoalPlanner.GoalPlan fresh", handler);
        int timeout = source.indexOf("reason.startsWith(\"acquire_water_timeout\")", handler);
        int exhausted = source.indexOf(
                "reason.startsWith(\"acquire_water_search_exhausted\")", handler);
        int noRoute = source.indexOf(
                "reason.startsWith(\"acquire_water_no_reachable_surface_route\")", handler);
        int surfaceReturn = source.indexOf(
                "reason.startsWith(\"acquire_water_surface_return_unreachable\")", handler);
        int invalid = source.indexOf(
                "reason.startsWith(\"acquire_water_invalid_checkpoint\")", handler);

        assertTrue(handler >= 0 && timeout > handler && timeout < replan,
                "water timeout must terminate before GoalPlanner can issue a fresh search");
        assertTrue(exhausted > handler && exhausted < replan,
                "exhausted water cursor must terminate before replanning");
        assertTrue(noRoute > handler && noRoute < replan,
                "a sealed surface route must fail before burning a fresh search cursor");
        assertTrue(surfaceReturn > handler && surfaceReturn < replan,
                "an exhausted physical ascent must fail before restoring the same return checkpoint");
        assertTrue(invalid > handler && invalid < replan,
                "invalid durable water state must fail closed before replanning");
    }

    @Test
    void completedWaterExpeditionRetiresOnlyClosedOrdinaryMiningCursor() throws IOException {
        String source = Files.readString(EXECUTOR);
        int completed = source.indexOf("if (status.state() == TaskState.COMPLETED)");
        int evidence = source.indexOf("captureTaskEvidence(bot, plan)", completed);
        int relocation = source.indexOf(
                "retireRelocatedOrdinaryMiningCheckpoint(bot, plan)", evidence);
        int completedClear = source.indexOf(
                "clearCompletedTaskCheckpoint(plan)", relocation);
        int helper = source.indexOf(
                "private static void retireRelocatedOrdinaryMiningCheckpoint", relocation);
        int policy = source.indexOf(
                "shouldRetireRelocatedOrdinaryMiningCheckpoint(", helper);
        int ledgerGuard = source.indexOf(
                "hasOreDigPhysicalLedger(plan.miningCheckpoint)", helper);
        int clear = source.indexOf("plan.miningCheckpoint.clear()", ledgerGuard);

        assertTrue(completed >= 0 && evidence > completed && relocation > evidence
                        && completedClear > relocation,
                "water relocation boundary must be atomic between completed evidence and step clear");
        assertTrue(helper > relocation && policy > helper
                        && ledgerGuard > policy && clear > ledgerGuard,
                "water relocation must delegate the tested retirement policy before clearing");
    }
}
