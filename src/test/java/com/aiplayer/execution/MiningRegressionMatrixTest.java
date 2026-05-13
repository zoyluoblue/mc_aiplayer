package com.aiplayer.execution;

import com.aiplayer.recipe.MiningGoalResolver;
import com.aiplayer.recipe.MiningResource;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningRegressionMatrixTest {
    @Test
    void commonMiningTargetsResolveToSurvivalOutputs() {
        assertEquals("minecraft:coal", MiningGoalResolver.resolve("coal").orElseThrow().finalItem());
        assertEquals("minecraft:iron_ingot", MiningGoalResolver.resolve("iron_ingot").orElseThrow().finalItem());
        assertEquals("minecraft:gold_ingot", MiningGoalResolver.resolve("gold_ingot").orElseThrow().finalItem());
        assertEquals("minecraft:diamond", MiningGoalResolver.resolve("diamond_ore").orElseThrow().finalItem());
        assertEquals("minecraft:redstone", MiningGoalResolver.resolve("redstone_ore").orElseThrow().finalItem());
        assertEquals("minecraft:lapis_lazuli", MiningGoalResolver.resolve("lapis_ore").orElseThrow().finalItem());
        assertEquals("minecraft:emerald", MiningGoalResolver.resolve("emerald_ore").orElseThrow().finalItem());
        assertEquals("minecraft:obsidian", MiningGoalResolver.resolve("obsidian").orElseThrow().finalItem());
    }

    @Test
    void deepMiningProfilesHaveExpectedToolAndHeightBands() {
        MiningResource.Profile gold = MiningResource.findByMineTarget("gold").orElseThrow();
        MiningResource.Profile diamond = MiningResource.findByMineTarget("diamond").orElseThrow();
        MiningResource.Profile ancientDebris = MiningResource.findByMineTarget("ancient_debris").orElseThrow();

        assertEquals("minecraft:iron_pickaxe", gold.requiredTool());
        assertEquals(-16, MiningHeightPolicy.decide(80, gold).targetY());
        assertEquals("minecraft:iron_pickaxe", diamond.requiredTool());
        assertEquals(-24, MiningHeightPolicy.decide(80, diamond).targetY());
        assertEquals("minecraft:diamond_pickaxe", ancientDebris.requiredTool());
        assertEquals(15, MiningHeightPolicy.decide(80, ancientDebris).targetY());
    }

    @Test
    void routeSimulatorRequiresTwoHighSpaceAndSupport() {
        BlockPos current = new BlockPos(0, 65, 0);
        BlockPos stand = new BlockPos(1, 64, 0);

        MiningMovementSimulator.Result feet = MiningMovementSimulator.simulate(new MiningMovementSimulator.Input(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty()
        ));

        assertEquals(MiningMovementSimulator.Action.PLACE_SUPPORT, feet.action());
    }

    @Test
    void failedCandidatesCoolDownWithoutPermanentBlacklist() {
        MiningCandidateCooldown cooldown = new MiningCandidateCooldown();
        BlockPos pos = new BlockPos(12, -16, 3);

        cooldown.reject(pos, "movement_stuck", 0);

        assertTrue(cooldown.activePositions(100).contains(pos));
        assertTrue(cooldown.activePositions(20 * 60 + 1).isEmpty());
    }
}
