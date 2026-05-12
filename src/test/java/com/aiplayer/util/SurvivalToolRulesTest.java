package com.aiplayer.util;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalToolRulesTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preferredToolsMatchCommonSurvivalBlocks() {
        assertEquals("pickaxe", SurvivalUtils.preferredToolForBlock(Blocks.STONE));
        assertEquals("pickaxe", SurvivalUtils.preferredToolForBlock(Blocks.IRON_ORE));
        assertEquals("axe", SurvivalUtils.preferredToolForBlock(Blocks.OAK_LOG));
        assertEquals("shovel", SurvivalUtils.preferredToolForBlock(Blocks.GRAVEL));
        assertEquals("shovel", SurvivalUtils.preferredToolForBlock(Blocks.SAND));
        assertNull(SurvivalUtils.preferredToolForBlock(Blocks.SHORT_GRASS));
    }

    @Test
    void shovelRulesCoverSoftTerrainBlocks() {
        assertTrue(SurvivalUtils.requiresShovel(Blocks.DIRT));
        assertTrue(SurvivalUtils.requiresShovel(Blocks.GRASS_BLOCK));
        assertTrue(SurvivalUtils.requiresShovel(Blocks.CLAY));
        assertTrue(SurvivalUtils.requiresShovel(Blocks.SNOW_BLOCK));
    }

    @Test
    void leavesDoNotDropLeafBlocksInBasicSurvivalSimulation() {
        assertTrue(SurvivalUtils.isLeaves(Blocks.OAK_LEAVES));
        assertEquals(net.minecraft.world.item.Items.AIR, SurvivalUtils.getSurvivalDrop(Blocks.OAK_LEAVES.defaultBlockState()));
    }
}
