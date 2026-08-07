package io.github.zoyluo.aibot.goal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F5:开放黑曜石事务的 replan 重排契约。缺物资失败(need_better_tool / bucket-lost /
 * missing-water)必须让 fresh 计划的补给前缀物理地排在 resume 步之前;其余失败原因保持
 * resume-first 的物理续作顺序。
 */
class GoalExecutorObsidianResumeReconcileTest {

    private static List<GoalStep> freshPlanShape() {
        return new ArrayList<>(List.of(
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.makeObsidian(24),
                GoalStep.placeStations(),
                GoalStep.makeObsidian(8)));
    }

    @Test
    void missingResourceFailureKeepsSupplyPrefixBeforeResume() {
        List<GoalStep> steps = freshPlanShape();

        int resumeIndex = GoalExecutor.reconcileObsidianSteps(steps, 32, true, true);

        assertEquals(3, resumeIndex);
        assertEquals(List.of(
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.makeObsidian(32),
                GoalStep.placeStations()), steps);
    }

    @Test
    void physicalContinuationFailureKeepsResumeFirstOrder() {
        List<GoalStep> steps = freshPlanShape();

        int resumeIndex = GoalExecutor.reconcileObsidianSteps(steps, 32, true, false);

        assertEquals(0, resumeIndex);
        assertEquals(List.of(
                GoalStep.makeObsidian(32),
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.placeStations()), steps);
    }

    @Test
    void missingResourceWithoutAttestedSupplyPrefixResumesAtHead() {
        // fresh 计划里没有 MAKE_OBSIDIAN → 没有可证实的补给前缀,保持 resume-first。
        List<GoalStep> withoutMake = new ArrayList<>(List.of(
                GoalStep.hunt(2), GoalStep.cookFood(2)));
        assertEquals(0, GoalExecutor.reconcileObsidianSteps(withoutMake, 32, true, true));
        assertEquals(List.of(
                GoalStep.makeObsidian(32),
                GoalStep.hunt(2),
                GoalStep.cookFood(2)), withoutMake);

        // MAKE 已在队首 → 前缀为空,resume 仍在队首。
        List<GoalStep> makeFirst = new ArrayList<>(List.of(
                GoalStep.makeObsidian(24), GoalStep.placeStations()));
        assertEquals(0, GoalExecutor.reconcileObsidianSteps(makeFirst, 32, true, true));
        assertEquals(List.of(
                GoalStep.makeObsidian(32), GoalStep.placeStations()), makeFirst);

        // replan 失败时 fresh 步骤为空 → 仅剩 resume 步(与既有行为一致)。
        List<GoalStep> empty = new ArrayList<>();
        assertEquals(0, GoalExecutor.reconcileObsidianSteps(empty, 32, true, true));
        assertEquals(List.of(GoalStep.makeObsidian(32)), empty);
    }

    @Test
    void closedTransactionReconcileKeepsInPlaceReplacement() {
        List<GoalStep> steps = freshPlanShape();

        GoalExecutor.reconcileObsidianSteps(steps, 32, false, false);

        assertEquals(List.of(
                GoalStep.hunt(2),
                GoalStep.cookFood(2),
                GoalStep.acquireWater(),
                GoalStep.makeObsidian(32),
                GoalStep.placeStations()), steps);
    }

    @Test
    void missingResourceReasonScopeIsExact() {
        assertTrue(GoalExecutor.isObsidianMissingResourceFailure(
                "need_better_tool:minecraft:diamond_pickaxe"));
        assertTrue(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_bucket_lost_after_pour"));
        assertTrue(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_pickup_protection_missing_water"));

        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(null));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(""));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_timeout"));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_no_progress collected=3 phase=SEARCH"));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure(
                "create_obsidian_search_enclosed"));
        assertFalse(GoalExecutor.isObsidianMissingResourceFailure("stuck:blocked"));
    }
}
