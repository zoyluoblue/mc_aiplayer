package io.github.zoyluo.aibot.mining;

import io.github.zoyluo.aibot.goal.GoalStep;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningMissionBudgetTest {
    @Test
    void diamondStackOuterTimeoutCoversEveryBoundedTargetStageAndBootstrap() {
        MiningMissionBudget.OuterTimeoutBudget budget =
                MiningMissionBudget.diamondStack64FromZero();

        assertEquals(8, budget.targetOreDigBatches());
        assertEquals(0, budget.bootstrapOreDigBatches());
        assertEquals(8, budget.retryOreDigBatches());
        assertEquals(8, budget.targetServiceCheckpoints());
        assertEquals(0, budget.bootstrapServiceCheckpoints());
        assertEquals(8, budget.retryServiceCheckpoints());
        assertEquals(8, budget.inventoryServiceCheckpoints());
        assertEquals(2, budget.descents());
        assertEquals(384_000, budget.oreDigTicks());
        assertEquals(115_200, budget.serviceTicks());
        assertEquals(80_000, budget.descendTicks());
        assertEquals(24_000, budget.bootstrapMarginTicks());
        assertEquals(603_200, budget.timeoutTicks());
        assertTrue(budget.timeoutTicks() > 240_000,
                "the former magic timeout expired before its bounded children");
    }

    @Test
    void longRareResourceEpochsOwnIndependentWindowsWithoutRestartRefresh() {
        assertEquals(24_000,
                MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(0));
        assertEquals(48_000,
                MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(1));
        assertThrows(IllegalArgumentException.class,
                () -> MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(-1));
        assertThrows(IllegalArgumentException.class,
                () -> MiningMissionBudget.rareOreDigCumulativeHardWindowTicks(2));
    }

    @Test
    void descendWindowScalesFromFactualDepthWithoutOverflow() {
        assertEquals(4_800, MiningMissionBudget.descendTaskWindowTicks(57, 48));
        assertEquals(8_400, MiningMissionBudget.descendTaskWindowTicks(16, -59));
        assertEquals(32_720, MiningMissionBudget.descendTaskWindowTicks(320, -59));
        assertEquals(40_000, MiningMissionBudget.descendTaskWindowTicks(
                Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void auxiliaryWindowsUseRealShortTaskBoundsAndFailClosedOnOverflow() {
        assertEquals(4_800, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.CRAFT, Integer.MAX_VALUE));
        assertEquals(4_800, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.HUNT, 4));
        assertEquals(24_001, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.HUNT, 50));
        assertEquals(24_000, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.SMELT, 90));
        assertEquals(24_061, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.SMELT, 91));
        assertEquals(26_401, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.COOK_FOOD, 100));
        assertEquals(76_800, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.MAKE_OBSIDIAN, 32));
        assertEquals(24_000, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.GATHER, 1));
        assertThrows(ArithmeticException.class,
                () -> MiningMissionBudget.auxiliaryStepWindowTicks(
                        GoalStep.Kind.HUNT, Integer.MAX_VALUE));
        assertThrows(ArithmeticException.class,
                () -> MiningMissionBudget.auxiliaryStepWindowTicks(
                        GoalStep.Kind.SMELT, Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, MiningMissionBudget.auxiliaryStepWindowTicks(
                GoalStep.Kind.MAKE_OBSIDIAN, Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> MiningMissionBudget.auxiliaryStepWindowTicks(GoalStep.Kind.HUNT, 0));
    }

    @Test
    void negativeTimeoutComponentsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new MiningMissionBudget.OuterTimeoutBudget(
                        -1, 0, 8, 8, 0, 1, 8, 2, 0, 24_000));
    }

    @Test
    void verifierBindsTimeoutToTheLiveNominalPlanInsteadOfAMagicLiteral() throws IOException {
        String source = Files.readString(Path.of(
                "src/gametest/java/io/github/zoyluo/aibot/command/AIBotVerifySubcommand.java"));
        int start = source.indexOf("private static Result assignDiamondStack64FromZero");
        int end = source.indexOf("private static Result assignRealArmor", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("GoalPlanner.plan(bot, goal)"));
        assertTrue(method.contains("diamondStack64FromZero(nominalPlan).timeoutTicks()"));
        assertFalse(method.contains("240000"));
    }
}
