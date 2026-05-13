package com.aiplayer.execution;

import java.util.Map;

public final class MiningSupportPolicy {
    private static final String[] SUPPORT_BLOCKS = {
        "minecraft:cobblestone",
        "minecraft:cobbled_deepslate",
        "minecraft:dirt",
        "minecraft:stone",
        "minecraft:deepslate",
        "minecraft:oak_planks",
        "minecraft:spruce_planks",
        "minecraft:birch_planks",
        "minecraft:jungle_planks",
        "minecraft:acacia_planks",
        "minecraft:dark_oak_planks",
        "minecraft:mangrove_planks",
        "minecraft:cherry_planks"
    };

    private MiningSupportPolicy() {
    }

    public static String chooseSupportBlock(Map<String, Integer> inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return null;
        }
        for (String block : SUPPORT_BLOCKS) {
            if (inventory.getOrDefault(block, 0) > 0) {
                return block;
            }
        }
        return null;
    }
}
