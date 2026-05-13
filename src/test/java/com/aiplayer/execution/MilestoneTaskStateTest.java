package com.aiplayer.execution;

import com.aiplayer.planning.PlanStep;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneTaskStateTest {
    @Test
    void goldChainStartsAtWoodMilestone() {
        MilestoneTaskState state = MilestoneTaskState.create("minecraft:gold_ingot", 2);

        state.refresh(Map.of(), null, "start");

        assertEquals(1, state.currentNumber());
        assertEquals(13, state.total());
        assertEquals("木材", state.currentLabel());
        assertTrue(state.toLogText().contains("milestone=1/13"));
    }

    @Test
    void goldChainAdvancesThroughCompletedToolMilestones() {
        MilestoneTaskState state = MilestoneTaskState.create("minecraft:gold_ingot", 2);

        state.refresh(Map.of(
            "minecraft:oak_planks", 3,
            "minecraft:stick", 2,
            "minecraft:wooden_pickaxe", 1,
            "minecraft:cobblestone", 3,
            "minecraft:stone_pickaxe", 1
        ), new ExecutionStep(PlanStep.gather("ore", "minecraft:raw_iron", 3)), "after_step");

        assertEquals("铁原矿", state.currentLabel());
        assertTrue(state.toLogText().contains("木镐"));
        assertTrue(state.toLogText().contains("石镐"));
    }

    @Test
    void goldChainTracksFuelBeforeSmeltingIron() {
        MilestoneTaskState state = MilestoneTaskState.create("minecraft:gold_ingot", 2);

        state.refresh(Map.of(
            "minecraft:stone_pickaxe", 1,
            "minecraft:raw_iron", 3
        ), null, "after_iron");

        assertEquals("燃料", state.currentLabel());
    }

    @Test
    void stepFailureIsRecordedWithoutLosingCompletedMilestones() {
        MilestoneTaskState state = MilestoneTaskState.create("minecraft:gold_ingot", 2);
        Map<String, Integer> inventory = Map.of(
            "minecraft:oak_planks", 3,
            "minecraft:stick", 2,
            "minecraft:wooden_pickaxe", 1
        );
        state.refresh(inventory, null, "before_failure");

        state.recordStepResult(
            new ExecutionStep(PlanStep.gather("stone", "minecraft:cobblestone", 3)),
            StepResult.failure("挖矿站位已经不可站立"),
            inventory
        );

        assertEquals("圆石", state.currentLabel());
        assertTrue(state.toLogText().contains("failures={cobblestone=1}"));
        assertTrue(state.toLogText().contains("木镐"));
    }

    @Test
    void completedMilestonesDoNotRegressWhenMaterialsAreConsumed() {
        MilestoneTaskState state = MilestoneTaskState.create("minecraft:gold_ingot", 2);
        state.refresh(Map.of(
            "minecraft:oak_planks", 3,
            "minecraft:stick", 2,
            "minecraft:wooden_pickaxe", 1
        ), null, "wood_tool_ready");

        state.refresh(Map.of(
            "minecraft:stick", 2
        ), null, "materials_consumed");

        assertEquals("圆石", state.currentLabel());
        assertTrue(state.toLogText().contains("木板"));
        assertTrue(state.toLogText().contains("木镐"));
    }

    @Test
    void recoveryActionIsIncludedInMilestoneLog() {
        MilestoneTaskState state = MilestoneTaskState.create("minecraft:gold_ingot", 2);

        state.refresh(Map.of(), null, "start");
        state.recordRecoveryAction("retry_current_milestone");

        assertTrue(state.toLogText().contains("nextRecovery=retry_current_milestone"));
        assertTrue(state.toLogText().contains("current="));
        assertTrue(state.toLogText().contains("completed="));
        assertTrue(state.toLogText().contains("failures="));
        assertTrue(state.toLogText().contains("recoveries="));
    }
}
