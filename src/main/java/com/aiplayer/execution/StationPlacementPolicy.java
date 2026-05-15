package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Set;

final class StationPlacementPolicy {
    private static final double BREAKING_PENALTY = 8.0D;
    private static final double VERTICAL_PENALTY = 16.0D;
    private static final Set<String> SAFE_CLEARING_BLOCKS = Set.of(
        "minecraft:stone",
        "minecraft:cobblestone",
        "minecraft:deepslate",
        "minecraft:cobbled_deepslate",
        "minecraft:dirt",
        "minecraft:grass_block",
        "minecraft:coarse_dirt",
        "minecraft:rooted_dirt",
        "minecraft:gravel",
        "minecraft:sand",
        "minecraft:red_sand",
        "minecraft:granite",
        "minecraft:diorite",
        "minecraft:andesite",
        "minecraft:tuff",
        "minecraft:calcite",
        "minecraft:dripstone_block",
        "minecraft:clay",
        "minecraft:mud",
        "minecraft:netherrack",
        "minecraft:basalt",
        "minecraft:blackstone"
    );

    private StationPlacementPolicy() {
    }

    static boolean hasUsableSupport(Block support) {
        return support != Blocks.AIR
            && support != Blocks.WATER
            && support != Blocks.LAVA
            && support != Blocks.BEDROCK;
    }

    static double candidateScore(BlockPos center, BlockPos target, boolean requiresBreaking) {
        if (center == null || target == null) {
            return Double.MAX_VALUE;
        }
        int verticalDelta = Math.abs(target.getY() - center.getY());
        return center.distSqr(target)
            + verticalDelta * verticalDelta * VERTICAL_PENALTY
            + (requiresBreaking ? BREAKING_PENALTY : 0.0D);
    }

    static boolean isSafeSpaceClearingBlock(String blockId) {
        return blockId != null && SAFE_CLEARING_BLOCKS.contains(blockId);
    }
}
