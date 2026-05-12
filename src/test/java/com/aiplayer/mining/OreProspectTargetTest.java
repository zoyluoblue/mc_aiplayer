package com.aiplayer.mining;

import com.aiplayer.recipe.MiningResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreProspectTargetTest {
    @Test
    void targetKeepsOreBlocksDropItemAndToolRequirementTogether() {
        MiningResource.Profile gold = MiningResource.findByItemOrSource("minecraft:raw_gold", null).orElseThrow();

        OreProspectTarget target = OreProspectTarget.fromProfile(gold, -64, 319);

        assertEquals("raw_gold", target.key());
        assertEquals("minecraft:raw_gold", target.item());
        assertEquals("金矿", target.displayName());
        assertEquals("minecraft:iron_pickaxe", target.requiredTool());
        assertTrue(target.blockIds().contains("minecraft:gold_ore"));
        assertTrue(target.blockIds().contains("minecraft:deepslate_gold_ore"));
        assertTrue(target.scanMinY() <= -64);
        assertTrue(target.scanMaxY() >= 32);
    }

    @Test
    void targetHonorsDimensionRulesFromMiningProfile() {
        MiningResource.Profile ancientDebris = MiningResource.findByItemOrSource("minecraft:ancient_debris", null).orElseThrow();
        MiningResource.Profile obsidian = MiningResource.findByItemOrSource("minecraft:obsidian", null).orElseThrow();

        OreProspectTarget debrisTarget = OreProspectTarget.fromProfile(ancientDebris, -64, 319);
        OreProspectTarget obsidianTarget = OreProspectTarget.fromProfile(obsidian, -64, 319);

        assertTrue(debrisTarget.allowsDimension("minecraft:the_nether"));
        assertFalse(debrisTarget.allowsDimension("minecraft:overworld"));
        assertTrue(obsidianTarget.allowsDimension("minecraft:overworld"));
        assertTrue(obsidianTarget.allowsDimension("minecraft:the_nether"));
    }

    @Test
    void genericBlockTargetSupportsNonOreAlternatives() {
        OreProspectTarget target = OreProspectTarget.forBlocks(
            "logs",
            "minecraft:oak_log",
            "任意原木",
            null,
            "any",
            List.of(
                "minecraft:oak_log",
                "minecraft:birch_log",
                "minecraft:acacia_log"
            ),
            -64,
            319
        );

        assertEquals("logs", target.key());
        assertEquals("minecraft:oak_log", target.item());
        assertEquals("任意原木", target.displayName());
        assertTrue(target.allowsDimension("minecraft:overworld"));
        assertTrue(target.allowsDimension("minecraft:the_nether"));
        assertTrue(target.blockIds().contains("minecraft:birch_log"));
        assertTrue(target.blockIds().contains("minecraft:acacia_log"));
        assertEquals(-64, target.scanMinY());
        assertEquals(319, target.scanMaxY());
    }
}
