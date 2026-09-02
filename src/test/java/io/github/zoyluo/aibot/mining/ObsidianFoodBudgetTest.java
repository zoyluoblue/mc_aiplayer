package io.github.zoyluo.aibot.mining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the obsidian expedition food scaling (see obsidianExpeditionFoodTarget's contract). */
class ObsidianFoodBudgetTest {
    @Test
    void smallTargetsKeepTheBoundedLegacyFloor() {
        assertEquals(8, MiningBudget.obsidianExpeditionFoodTarget(1));
        assertEquals(8, MiningBudget.obsidianExpeditionFoodTarget(8));
    }

    @Test
    void longMissionsCarryPerSegmentRationsPlusBuffer() {
        assertEquals(12, MiningBudget.obsidianExpeditionFoodTarget(16));
        assertEquals(20, MiningBudget.obsidianExpeditionFoodTarget(32));
        assertEquals(36, MiningBudget.obsidianExpeditionFoodTarget(64));
    }

    @Test
    void partialSegmentsRoundUpAndStayWithinOneStack() {
        assertEquals(16, MiningBudget.obsidianExpeditionFoodTarget(17));
        assertTrue(MiningBudget.obsidianExpeditionFoodTarget(64) <= 64,
                "the mission ration must stay carriable in a single cooked-food stack");
    }

    @Test
    void initialRationIsStagedAtTheFloorBufferLevel() {
        assertEquals(8, MiningBudget.obsidianExpeditionInitialFoodTarget(1));
        assertEquals(8, MiningBudget.obsidianExpeditionInitialFoodTarget(8));
        assertEquals(12, MiningBudget.obsidianExpeditionInitialFoodTarget(16));
        assertEquals(12, MiningBudget.obsidianExpeditionInitialFoodTarget(32));
        assertEquals(12, MiningBudget.obsidianExpeditionInitialFoodTarget(64));
        assertEquals(MiningBudget.obsidianExpeditionFoodTarget(16),
                MiningBudget.obsidianExpeditionInitialFoodTarget(16),
                "small missions whose full ration fits the initial gate keep the single-gate shape");
        assertTrue(MiningBudget.obsidianExpeditionInitialFoodTarget(64)
                        < MiningBudget.obsidianExpeditionFoodTarget(64),
                "long missions must stage: the spawn herd cannot be asked to feed nine hunts");
    }
}
