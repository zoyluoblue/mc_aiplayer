package com.aiplayer.execution;

import com.aiplayer.recipe.MiningResource;

import org.junit.jupiter.api.Test;

import java.util.List;

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
    void pickaxeTierUsesSharedMiningToolTiers() {
        assertEquals(0, MiningToolGate.pickaxeTier("minecraft:air"));
        assertEquals(1, MiningToolGate.pickaxeTier("minecraft:wooden_pickaxe"));
        assertEquals(1, MiningToolGate.pickaxeTier("minecraft:golden_pickaxe"));
        assertEquals(2, MiningToolGate.pickaxeTier("minecraft:stone_pickaxe"));
        assertEquals(3, MiningToolGate.pickaxeTier("minecraft:iron_pickaxe"));
        assertEquals(4, MiningToolGate.pickaxeTier("minecraft:diamond_pickaxe"));
        assertEquals(5, MiningToolGate.pickaxeTier("minecraft:netherite_pickaxe"));
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
        assertTrue(result.toStatusText().contains("工具耐久不足"));
        assertTrue(result.toStatusText().contains("耐久=4/12"));
    }

    @Test
    void replacementToolRequiresOneMoreThanCurrentCount() {
        assertEquals(1, MiningToolGate.replacementTargetCount(0));
        assertEquals(2, MiningToolGate.replacementTargetCount(1));
        assertEquals(4, MiningToolGate.replacementTargetCount(3));
    }

    @Test
    void replacementCandidatesAllowSameOrHigherTierTools() {
        assertEquals(List.of(
            "minecraft:iron_pickaxe",
            "minecraft:diamond_pickaxe"
        ), MiningToolGate.replacementCandidates("minecraft:iron_pickaxe"));
        assertEquals(List.of(
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe"
        ),
            MiningToolGate.replacementCandidates("minecraft:diamond_pickaxe"));
    }

    @Test
    void lowDurabilityRequiredTierIsNotHiddenByHealthyLowerTierTool() {
        MiningToolGate.Result result = MiningToolGate.evaluateWithFallback(
            "minecraft:iron_pickaxe",
            "minecraft:iron_pickaxe",
            4,
            "minecraft:stone_pickaxe",
            100,
            12
        );

        assertFalse(result.ready());
        assertTrue(result.hasRequiredTier());
        assertFalse(result.enoughDurability());
        assertEquals("low_tool_durability", result.reason());
        assertEquals("replace_tool:minecraft:iron_pickaxe", result.nextMilestone());
    }

    @Test
    void healthyRequiredTierToolIsPreferredOverLowDurabilityHigherTierTool() {
        MiningToolGate.Result result = MiningToolGate.evaluateWithFallback(
            "minecraft:diamond_pickaxe",
            "minecraft:netherite_pickaxe",
            4,
            "minecraft:diamond_pickaxe",
            100,
            12
        );

        assertTrue(result.ready());
        assertEquals("minecraft:diamond_pickaxe", result.currentBestTool());
        assertEquals("ready", result.reason());
        assertEquals("mine_resource", result.nextMilestone());
    }

    @Test
    void resourceProfilesDriveToolGateRequirements() {
        MiningResource.Profile redstone = MiningResource.findByMineTarget("redstone").orElseThrow();
        MiningResource.Profile obsidian = MiningResource.findByMineTarget("obsidian").orElseThrow();
        MiningResource.Profile ancientDebris = MiningResource.findByMineTarget("ancient_debris").orElseThrow();

        MiningToolGate.Result redstoneWithStone = MiningToolGate.evaluate(redstone, "minecraft:stone_pickaxe", 100, 12);
        MiningToolGate.Result redstoneWithIron = MiningToolGate.evaluate(redstone, "minecraft:iron_pickaxe", 100, 12);
        MiningToolGate.Result obsidianWithIron = MiningToolGate.evaluate(obsidian, "minecraft:iron_pickaxe", 100, 12);
        MiningToolGate.Result ancientDebrisWithDiamond = MiningToolGate.evaluate(ancientDebris, "minecraft:diamond_pickaxe", 100, 12);

        assertFalse(redstoneWithStone.ready());
        assertEquals("make_item:minecraft:iron_pickaxe", redstoneWithStone.nextMilestone());
        assertTrue(redstoneWithIron.ready());
        assertFalse(obsidianWithIron.ready());
        assertEquals("make_item:minecraft:diamond_pickaxe", obsidianWithIron.nextMilestone());
        assertTrue(ancientDebrisWithDiamond.ready());
    }
}
