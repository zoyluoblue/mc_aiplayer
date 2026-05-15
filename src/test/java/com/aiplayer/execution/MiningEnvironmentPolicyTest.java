package com.aiplayer.execution;

import com.aiplayer.recipe.MiningResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningEnvironmentPolicyTest {
    @Test
    void coalCanSearchAtHighOverworldSurfaceWithoutForcedDeepDescent() {
        MiningResource.Profile coal = MiningResource.findByMineTarget("coal").orElseThrow();

        MiningEnvironmentPolicy.Decision decision = MiningEnvironmentPolicy.decide("minecraft:overworld", 90, coal);

        assertTrue(decision.dimensionAllowed());
        assertTrue(decision.canSearchHere());
        assertFalse(decision.shouldDescend());
        assertEquals(90, decision.height().targetY());
        assertTrue(decision.toStatusText().contains("可搜索"));
    }

    @Test
    void diamondAndRedstoneDescendToDeepOverworldBands() {
        MiningResource.Profile diamond = MiningResource.findByMineTarget("diamond").orElseThrow();
        MiningResource.Profile redstone = MiningResource.findByMineTarget("redstone").orElseThrow();

        MiningEnvironmentPolicy.Decision diamondDecision = MiningEnvironmentPolicy.decide("minecraft:overworld", 70, diamond);
        MiningEnvironmentPolicy.Decision redstoneDecision = MiningEnvironmentPolicy.decide("minecraft:overworld", 70, redstone);

        assertTrue(diamondDecision.dimensionAllowed());
        assertTrue(diamondDecision.shouldDescend());
        assertEquals(-24, diamondDecision.height().targetY());
        assertTrue(redstoneDecision.shouldDescend());
        assertEquals(-24, redstoneDecision.height().targetY());
    }

    @Test
    void obsidianUsesSpecialEnvironmentWithoutDimensionLock() {
        MiningResource.Profile obsidian = MiningResource.findByMineTarget("obsidian").orElseThrow();

        MiningEnvironmentPolicy.Decision overworld = MiningEnvironmentPolicy.decide("minecraft:overworld", 64, obsidian);
        MiningEnvironmentPolicy.Decision nether = MiningEnvironmentPolicy.decide("minecraft:the_nether", 32, obsidian);

        assertTrue(overworld.dimensionAllowed());
        assertTrue(nether.dimensionAllowed());
        assertTrue(overworld.specialEnvironment());
        assertFalse(overworld.shouldDescend());
        assertTrue(overworld.toLogText().contains("existing_obsidian_or_water_lava_area"));
    }

    @Test
    void netherOnlyResourcesRejectOverworldBeforeHeightSearch() {
        MiningResource.Profile ancientDebris = MiningResource.findByMineTarget("ancient_debris").orElseThrow();
        MiningResource.Profile quartz = MiningResource.findByMineTarget("quartz").orElseThrow();
        MiningResource.Profile netherGold = MiningResource.findByMineTarget("gold_nugget").orElseThrow();

        MiningEnvironmentPolicy.Decision debris = MiningEnvironmentPolicy.decide("minecraft:overworld", 80, ancientDebris);
        MiningEnvironmentPolicy.Decision netherQuartz = MiningEnvironmentPolicy.decide("minecraft:overworld", 80, quartz);
        MiningEnvironmentPolicy.Decision netherGoldDecision = MiningEnvironmentPolicy.decide("minecraft:overworld", 80, netherGold);

        assertFalse(debris.dimensionAllowed());
        assertFalse(debris.shouldDescend());
        assertTrue(debris.dimensionFailureText("远古残骸").contains("minecraft:the_nether"));
        assertTrue(debris.dimensionFailureText("远古残骸").contains("minecraft:overworld"));
        assertFalse(netherQuartz.dimensionAllowed());
        assertTrue(netherQuartz.dimensionFailureText("下界石英矿").contains("minecraft:the_nether"));
        assertTrue(netherQuartz.dimensionFailureText("下界石英矿").contains("minecraft:overworld"));
        assertFalse(netherGoldDecision.dimensionAllowed());
        assertTrue(netherGoldDecision.dimensionFailureText("下界金矿").contains("minecraft:the_nether"));
        assertTrue(netherGoldDecision.dimensionFailureText("下界金矿").contains("minecraft:overworld"));
    }

    @Test
    void netherResourcesAreAllowedInNether() {
        MiningResource.Profile ancientDebris = MiningResource.findByMineTarget("ancient_debris").orElseThrow();
        MiningResource.Profile quartz = MiningResource.findByMineTarget("quartz").orElseThrow();
        MiningResource.Profile netherGold = MiningResource.findByMineTarget("gold_nugget").orElseThrow();

        MiningEnvironmentPolicy.Decision decision = MiningEnvironmentPolicy.decide("minecraft:the_nether", 80, ancientDebris);
        MiningEnvironmentPolicy.Decision quartzDecision = MiningEnvironmentPolicy.decide("minecraft:the_nether", 80, quartz);
        MiningEnvironmentPolicy.Decision goldDecision = MiningEnvironmentPolicy.decide("minecraft:the_nether", 80, netherGold);

        assertTrue(decision.dimensionAllowed());
        assertTrue(decision.shouldDescend());
        assertEquals(15, decision.height().targetY());
        assertTrue(decision.height().rangeText().contains("nether_low_branch_mine"));
        assertTrue(quartzDecision.dimensionAllowed());
        assertTrue(goldDecision.dimensionAllowed());
    }
}
