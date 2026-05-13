package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningToolGateTest {
    @Test
    void goldOreRequiresIronTierPickaxe() {
        MiningToolGate.Result stone = MiningToolGate.evaluate(
            "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe",
            100,
            12
        );
        MiningToolGate.Result iron = MiningToolGate.evaluate(
            "minecraft:iron_pickaxe",
            "minecraft:iron_pickaxe",
            100,
            12
        );

        assertFalse(stone.ready());
        assertEquals("missing_required_tool", stone.reason());
        assertEquals("make_item:minecraft:iron_pickaxe", stone.nextMilestone());
        assertTrue(iron.ready());
    }

    @Test
    void higherTierPickaxeSatisfiesLowerRequirement() {
        MiningToolGate.Result result = MiningToolGate.evaluate(
            "minecraft:iron_pickaxe",
            "minecraft:diamond_pickaxe",
            1000,
            12
        );

        assertTrue(result.ready());
        assertEquals(4, result.currentTier());
        assertEquals("mine_resource", result.nextMilestone());
    }

    @Test
    void lowDurabilityFailsBeforeMiningStarts() {
        MiningToolGate.Result result = MiningToolGate.evaluate(
            "minecraft:iron_pickaxe",
            "minecraft:iron_pickaxe",
            4,
            12
        );

        assertFalse(result.ready());
        assertTrue(result.hasRequiredTier());
        assertFalse(result.enoughDurability());
        assertEquals("low_tool_durability", result.reason());
        assertEquals("replace_tool:minecraft:iron_pickaxe", result.nextMilestone());
    }

    @Test
    void replacementToolRequiresOneMoreThanCurrentCount() {
        assertEquals(1, MiningToolGate.replacementTargetCount(0));
        assertEquals(2, MiningToolGate.replacementTargetCount(1));
        assertEquals(4, MiningToolGate.replacementTargetCount(3));
    }
}
