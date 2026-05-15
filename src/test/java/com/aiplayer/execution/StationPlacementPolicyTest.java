package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StationPlacementPolicyTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rejectsUnsafeOrMissingSupport() {
        assertTrue(!StationPlacementPolicy.hasUsableSupport(Blocks.AIR));
        assertTrue(!StationPlacementPolicy.hasUsableSupport(Blocks.WATER));
        assertTrue(!StationPlacementPolicy.hasUsableSupport(Blocks.LAVA));
        assertTrue(!StationPlacementPolicy.hasUsableSupport(Blocks.BEDROCK));
        assertTrue(StationPlacementPolicy.hasUsableSupport(Blocks.STONE));
        assertTrue(StationPlacementPolicy.hasUsableSupport(Blocks.DIRT));
    }

    @Test
    void prefersNearbyExistingAirOverDistantOrBreakingTargets() {
        BlockPos center = new BlockPos(0, 48, 0);
        BlockPos adjacentAir = new BlockPos(1, 48, 0);
        BlockPos adjacentWall = new BlockPos(1, 48, 1);
        BlockPos farAir = new BlockPos(4, 48, 0);

        assertTrue(
            StationPlacementPolicy.candidateScore(center, adjacentAir, false)
                < StationPlacementPolicy.candidateScore(center, adjacentWall, true)
        );
        assertTrue(
            StationPlacementPolicy.candidateScore(center, adjacentAir, false)
                < StationPlacementPolicy.candidateScore(center, farAir, false)
        );
    }

    @Test
    void clearingWhitelistAllowsTerrainButRejectsContainersAndWorkstations() {
        assertTrue(StationPlacementPolicy.isSafeSpaceClearingBlock("minecraft:stone"));
        assertTrue(StationPlacementPolicy.isSafeSpaceClearingBlock("minecraft:dirt"));
        assertTrue(StationPlacementPolicy.isSafeSpaceClearingBlock("minecraft:netherrack"));

        assertTrue(!StationPlacementPolicy.isSafeSpaceClearingBlock("minecraft:chest"));
        assertTrue(!StationPlacementPolicy.isSafeSpaceClearingBlock("minecraft:barrel"));
        assertTrue(!StationPlacementPolicy.isSafeSpaceClearingBlock("minecraft:crafting_table"));
        assertTrue(!StationPlacementPolicy.isSafeSpaceClearingBlock("minecraft:furnace"));
    }
}
