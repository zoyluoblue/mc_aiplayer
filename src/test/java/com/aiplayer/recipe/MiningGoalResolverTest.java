package com.aiplayer.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningGoalResolverTest {
    @Test
    void resolvesSmeltedIngotToRawOreChain() {
        MiningGoalResolver.Goal goal = MiningGoalResolver.resolve("金锭").orElseThrow();

        assertEquals("minecraft:gold_ingot", goal.finalItem());
        assertEquals("minecraft:raw_gold", goal.directMiningItem());
        assertEquals("gold_ore", goal.source());
        assertTrue(goal.needsSmelting());
        assertEquals("minecraft:furnace", goal.station());
        assertEquals("minecraft:iron_pickaxe", goal.requiredTool());
        assertEquals("minecraft:overworld", goal.dimension());
        assertEquals("raw_gold", goal.profileKey());
        assertTrue(goal.blockIds().contains("minecraft:gold_ore"));
        assertTrue(goal.blockIds().contains("minecraft:deepslate_gold_ore"));
    }

    @Test
    void resolvesOreBlockToSurvivalDropInsteadOfSilkTouchBlock() {
        MiningGoalResolver.Goal diamondOre = MiningGoalResolver.resolve("minecraft:diamond_ore").orElseThrow();
        MiningGoalResolver.Goal goldOre = MiningGoalResolver.resolve("gold_ore").orElseThrow();

        assertEquals("minecraft:diamond", diamondOre.finalItem());
        assertEquals("minecraft:diamond", diamondOre.directMiningItem());
        assertFalse(diamondOre.needsSmelting());
        assertEquals("diamond", diamondOre.profileKey());

        assertEquals("minecraft:raw_gold", goldOre.finalItem());
        assertEquals("minecraft:raw_gold", goldOre.directMiningItem());
        assertFalse(goldOre.needsSmelting());
        assertEquals("raw_gold", goldOre.profileKey());
    }

    @Test
    void resolvesCommonMiningAliasesToSameFactChain() {
        MiningGoalResolver.Goal commandGold = MiningGoalResolver.resolve("gold").orElseThrow();
        MiningGoalResolver.Goal chineseGoldOre = MiningGoalResolver.resolve("金矿").orElseThrow();
        MiningGoalResolver.Goal rawGold = MiningGoalResolver.resolve("minecraft:raw_gold").orElseThrow();

        assertEquals(commandGold.finalItem(), chineseGoldOre.finalItem());
        assertEquals(commandGold.finalItem(), rawGold.finalItem());
        assertEquals(commandGold.blockIds(), chineseGoldOre.blockIds());
        assertEquals(commandGold.requiredTool(), rawGold.requiredTool());
    }

    @Test
    void coversSpecialMiningTargets() {
        MiningGoalResolver.Goal obsidian = MiningGoalResolver.resolve("黑曜石").orElseThrow();
        MiningGoalResolver.Goal ancientDebris = MiningGoalResolver.resolve("ancient_debris").orElseThrow();

        assertEquals("minecraft:obsidian", obsidian.finalItem());
        assertEquals("minecraft:diamond_pickaxe", obsidian.requiredTool());
        assertEquals("any", obsidian.dimension());
        assertFalse(obsidian.needsSmelting());

        assertEquals("minecraft:ancient_debris", ancientDebris.finalItem());
        assertEquals("minecraft:the_nether", ancientDebris.dimension());
        assertEquals("minecraft:diamond_pickaxe", ancientDebris.requiredTool());
        assertFalse(ancientDebris.needsSmelting());
    }

    @Test
    void rejectsUnknownMiningGoal() {
        assertTrue(MiningGoalResolver.resolve("not_a_real_mining_target").isEmpty());
    }
}
