package com.aiplayer.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningResourceTest {
    @Test
    void overworldDeepOresDeclarePreferredHeightAndToolRequirements() {
        MiningResource.Profile gold = MiningResource.findByItemOrSource("minecraft:raw_gold", null).orElseThrow();
        MiningResource.Profile diamond = MiningResource.findByItemOrSource("minecraft:diamond", null).orElseThrow();
        MiningResource.Profile redstone = MiningResource.findByItemOrSource("minecraft:redstone", null).orElseThrow();

        assertEquals("minecraft:overworld", gold.dimension());
        assertEquals("minecraft:iron_pickaxe", gold.requiredTool());
        assertTrue(gold.prospectable());
        assertTrue(gold.isAbovePreferredY(70));
        assertFalse(gold.isAbovePreferredY(32));
        assertEquals("regular_underground_gold", gold.primaryRange().strategy());
        assertEquals("badlands_surface_gold", gold.fallbackRange().strategy());
        assertTrue(gold.branchMinePreferred());

        assertEquals("minecraft:iron_pickaxe", diamond.requiredTool());
        assertTrue(diamond.preferredMaxY() <= 16);
        assertTrue(diamond.descentBudget() >= 128);
        assertTrue(diamond.branchMinePreferred());
        assertFalse(diamond.surfaceAllowed());

        assertEquals("minecraft:iron_pickaxe", redstone.requiredTool());
        assertTrue(redstone.isAbovePreferredY(70));
        assertTrue(redstone.branchMinePreferred());
    }

    @Test
    void commonOverworldOresKeepToolAndRouteMetadata() {
        MiningResource.Profile coal = MiningResource.findByItemOrSource("minecraft:coal", null).orElseThrow();
        MiningResource.Profile iron = MiningResource.findByItemOrSource("minecraft:raw_iron", null).orElseThrow();
        MiningResource.Profile lapis = MiningResource.findByItemOrSource("minecraft:lapis_lazuli", null).orElseThrow();

        assertEquals("minecraft:wooden_pickaxe", coal.requiredTool());
        assertTrue(coal.isWithinPreferredY(64));
        assertTrue(coal.cavePreferred());
        assertTrue(coal.surfaceAllowed());
        assertEquals("minecraft:stone_pickaxe", iron.requiredTool());
        assertFalse(iron.isAbovePreferredY(90));
        assertTrue(iron.isAbovePreferredY(250));
        assertTrue(iron.isWithinFallbackY(120));
        assertEquals("mountain_surface_iron", iron.fallbackRange().strategy());
        assertTrue(iron.branchMinePreferred());
        assertEquals("minecraft:stone_pickaxe", lapis.requiredTool());
        assertTrue(lapis.prospectable());
        assertTrue(lapis.cavePreferred());
    }

    @Test
    void netherAndSpecialResourcesUseSeparateRouteRules() {
        MiningResource.Profile quartz = MiningResource.findByItemOrSource("minecraft:quartz", null).orElseThrow();
        MiningResource.Profile netherGold = MiningResource.findByItemOrSource("minecraft:gold_nugget", null).orElseThrow();
        MiningResource.Profile ancientDebris = MiningResource.findByItemOrSource("minecraft:ancient_debris", null).orElseThrow();
        MiningResource.Profile obsidian = MiningResource.findByItemOrSource("minecraft:obsidian", null).orElseThrow();

        assertEquals("minecraft:the_nether", quartz.dimension());
        assertFalse(quartz.prospectable());
        assertEquals("minecraft:the_nether", netherGold.dimension());
        assertTrue(netherGold.specialEnvironment());

        assertEquals("minecraft:the_nether", ancientDebris.dimension());
        assertTrue(ancientDebris.prospectable());
        assertTrue(ancientDebris.isAbovePreferredY(70));
        assertTrue(ancientDebris.branchMinePreferred());

        assertEquals("any", obsidian.dimension());
        assertFalse(obsidian.prospectable());
        assertTrue(obsidian.allowsDimension("minecraft:overworld"));
        assertTrue(obsidian.allowsDimension("minecraft:the_nether"));
    }

    @Test
    void aliasesResolveToProfileWithRouteMetadata() {
        MiningResource.Profile goldByChinese = MiningResource.findByMineTarget("金矿").orElseThrow();
        MiningResource.Profile diamondByEnglish = MiningResource.findByMineTarget("diamond_ore").orElseThrow();

        assertEquals("raw_gold", goldByChinese.key());
        assertEquals("diamond", diamondByEnglish.key());
        assertTrue(goldByChinese.hasPreferredYRange());
        assertTrue(diamondByEnglish.hasPreferredYRange());
    }

    @Test
    void autoMiningTargetKeepsProductBlockAndSmeltingSemanticsSeparate() {
        AutoMiningTarget iron = AutoMiningTarget.resolve("iron", 3);
        AutoMiningTarget goldIngot = AutoMiningTarget.resolve("金锭", 2);
        AutoMiningTarget diamondOreBlock = AutoMiningTarget.resolve("minecraft:diamond_ore", 1);

        assertTrue(iron.supported());
        assertEquals("minecraft:raw_iron", iron.item());
        assertEquals(3, iron.quantity());
        assertEquals("raw_iron", iron.profile().key());

        assertTrue(goldIngot.supported());
        assertEquals("minecraft:gold_ingot", goldIngot.item());
        assertEquals("raw_gold", goldIngot.profile().key());
        assertEquals("minecraft:iron_pickaxe", goldIngot.requiredTool());

        assertFalse(diamondOreBlock.supported());
        assertEquals("diamond", diamondOreBlock.profile().key());
        assertTrue(diamondOreBlock.message().contains("精准采集"));
    }

    @Test
    void autoMiningTargetExposesDimensionAndToolLimits() {
        AutoMiningTarget ancientDebris = AutoMiningTarget.resolve("远古残骸", 1);

        assertTrue(ancientDebris.supported());
        assertEquals("minecraft:ancient_debris", ancientDebris.item());
        assertEquals("minecraft:the_nether", ancientDebris.dimension());
        assertEquals("minecraft:diamond_pickaxe", ancientDebris.requiredTool());
    }

    @Test
    void emeraldUsesHighMountainProfileInsteadOfLowlandBranchMining() {
        MiningResource.Profile emerald = MiningResource.findByItemOrSource("minecraft:emerald", null).orElseThrow();

        assertEquals("mountain_emerald", emerald.primaryRange().strategy());
        assertTrue(emerald.surfaceAllowed());
        assertTrue(emerald.cavePreferred());
        assertFalse(emerald.branchMinePreferred());
        assertFalse(emerald.isWithinPrimaryY(40));
        assertTrue(emerald.isWithinPrimaryY(120));
    }
}
