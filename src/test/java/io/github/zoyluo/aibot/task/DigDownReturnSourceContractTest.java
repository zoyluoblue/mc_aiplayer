package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigDownReturnSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void returnDebtIsExactIndependentAndCheckpointed() throws IOException {
        String task = Files.readString(MAIN.resolve("task/DigDownTask.java"));

        assertTrue(task.contains("implements CheckpointableTask"));
        assertTrue(task.contains("CHECKPOINT_SCHEMA"));
        assertTrue(task.contains("values.keySet().equals(KEYS)"),
                "checkpoint decoding must reject missing and unknown fields");
        assertTrue(task.contains("start.equals(current)"));
        assertFalse(task.contains("at.getY() >= startPos.getY()"),
                "surface height alone may not settle return debt");
        assertTrue(task.contains("currentReturnBudget > returnHardLimit()"));
        assertTrue(task.contains(
                        "currentReturnBudget - lastReturnProgressBudget > RETURN_STALL_LIMIT"),
                "a safety-expanded return must still fail closed when physical recovery stalls");
        assertTrue(task.contains("RETURN_SAFETY_LIMIT = 2400"),
                "safety displacement recovery must retain a finite hard cap");
        assertTrue(task.contains("workBudgetAtReturn"),
                "return must not consume or reset the descent budget");
        int begin = task.indexOf("private void beginReturn(AIPlayerEntity bot, ReturnOutcome outcome)");
        int stop = task.indexOf("bot.getActionPack().stopAll();", begin);
        int clearSealOwnership = task.indexOf("clearRejectedLandingDirections();", stop);
        int returnPhase = task.indexOf("phase = Phase.RETURN;", clearSealOwnership);
        assertTrue(begin >= 0 && stop > begin && clearSealOwnership > stop
                        && returnPhase > clearSealOwnership,
                "beginReturn must stop movement and clear local seal ownership before RETURN");
        assertTrue(task.contains("failAfterExactReturn(bot, ReturnOutcome.TIMEOUT)"));
        assertTrue(task.contains("failAfterExactReturn(bot, ReturnOutcome.NO_PROGRESS)"));
        assertFalse(task.contains("startWalkTo(side.toCenterPos())"),
                "adjacent horizontal traversal must not restart a long-path controller every tick");
        assertTrue(task.contains("descentTrail.contains(side)"),
                "horizontal traversal must not reinterpret the exact-return trail as fresh work");
        assertTrue(task.contains("rememberDescentStep(bot.getBlockPos())"),
                "a horizontal physical step must be checkpointed in the same task tick");
        assertTrue(task.contains("returnOutcome = restored.returnOutcome()"),
                "a restart during failure recovery must retain its terminal outcome");
        assertTrue(task.contains("non_adjacent_or_three_axis"));
        assertTrue(task.contains("unsafe_landing"));
        assertTrue(task.contains("startDigPathTo(waypoint)"),
                "failed or unsafe micro-steps must preserve the exact dry endpoint for pillar fallback");
    }

    @Test
    void missionRestoreReplaysMineBeforeSatisfiedShortCircuit() throws IOException {
        String executor = Files.readString(MAIN.resolve("goal/GoalExecutor.java"));

        int inspect = executor.indexOf("DigDownTask.inspectCheckpoint(restoredDigDownCheckpoint)");
        int satisfied = executor.indexOf("initialEvaluation.state() == GoalEvaluation.State.SATISFIED");
        assertTrue(inspect >= 0 && satisfied > inspect,
                "return debt must be inspected before already-satisfied Mission completion");
        assertTrue(executor.contains(
                "&& restoredDigDown.isEmpty() && !interruptedDescend"),
                "already-satisfied completion must preserve both return and descent debt");
        assertTrue(executor.contains("reconcileDigDownSteps(restoredSteps, metadata)"));
        assertTrue(executor.contains("reconcileDescendSteps(restoredSteps,"
                        + " restoredDescend.orElseThrow().targetY())"));
        assertTrue(executor.contains("plan.takeTaskCheckpoint(GoalStep.Kind.MINE)"));
        assertTrue(executor.contains(
                "restore.taskCheckpointKind() == GoalStep.Kind.MINE\n                && restoredDigDown.isEmpty()"),
                "MINE kind without a checkpoint must fail closed");
    }

    @Test
    void failedDescentCannotFeedItsOriginalBatchCheckpointIntoRemainingQuota() throws IOException {
        String executor = Files.readString(MAIN.resolve("goal/GoalExecutor.java"));

        int mineFailure = executor.indexOf(
                "plan.current != null && plan.current.kind() == GoalStep.Kind.MINE");
        int clear = executor.indexOf("plan.taskCheckpoint.clear();", mineFailure);
        int clearKind = executor.indexOf("plan.taskCheckpointKind = null;", clear);
        int replan = executor.indexOf("GoalPlanner.GoalPlan fresh", clearKind);
        assertTrue(mineFailure >= 0 && clear > mineFailure && clearKind > clear && replan > clearKind,
                "a terminal DigDown cursor must be discarded before planning the remaining quota");
        assertTrue(executor.contains("reason.startsWith(\"dig_down_return_failed\")"),
                "unsettled physical return debt must fail closed instead of being discarded");
        assertTrue(taskReportsFailureOnlyAfterReturn(),
                "a failed descent must settle its exact return before GoalExecutor clears it");
    }

    @Test
    void completedTaskCursorIsClearedWithoutErasingDedicatedMiningState() throws IOException {
        String executor = Files.readString(MAIN.resolve("goal/GoalExecutor.java"));

        int capture = executor.indexOf("captureTaskEvidence(bot, plan);",
                executor.indexOf("status.state() == TaskState.COMPLETED"));
        int clear = executor.indexOf("clearCompletedTaskCheckpoint(plan);", capture);
        int evaluate = executor.indexOf("GoalEvaluation completedEvaluation", clear);
        assertTrue(capture >= 0 && clear > capture && evaluate > clear,
                "a committed task cursor must be cleared before the next persisted step");
        assertTrue(executor.contains("plan.taskCheckpointKind == plan.current.kind()"));
        assertFalse(executor.contains("plan.miningCheckpoint.clear();\n            plan.taskCheckpoint.clear();"),
                "clearing a task cursor must not erase the dedicated branch cursor");
    }

    private static boolean taskReportsFailureOnlyAfterReturn() throws IOException {
        String task = Files.readString(MAIN.resolve("task/DigDownTask.java"));
        int exactReturn = task.indexOf("if (hasReturned(startPos, at))");
        int phaseDone = task.indexOf("phase = Phase.DONE;", exactReturn);
        int terminalFailure = task.indexOf("fail(returnFailureReason(bot, returnOutcome));", phaseDone);
        return exactReturn >= 0 && phaseDone > exactReturn && terminalFailure > phaseDone;
    }
}
