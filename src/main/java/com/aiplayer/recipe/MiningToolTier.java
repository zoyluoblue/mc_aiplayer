package com.aiplayer.recipe;

import java.util.Locale;

public enum MiningToolTier {
    NONE(0, "none", "minecraft:air"),
    WOOD(1, "wood", "minecraft:wooden_pickaxe"),
    STONE(2, "stone", "minecraft:stone_pickaxe"),
    IRON(3, "iron", "minecraft:iron_pickaxe"),
    DIAMOND(4, "diamond", "minecraft:diamond_pickaxe"),
    NETHERITE(5, "netherite", "minecraft:netherite_pickaxe");

    private final int level;
    private final String key;
    private final String pickaxeItem;

    MiningToolTier(int level, String key, String pickaxeItem) {
        this.level = level;
        this.key = key;
        this.pickaxeItem = pickaxeItem;
    }

    public int level() {
        return level;
    }

    public String key() {
        return key;
    }

    public String pickaxeItem() {
        return pickaxeItem;
    }

    public boolean satisfies(MiningToolTier required) {
        MiningToolTier safeRequired = required == null ? NONE : required;
        return level >= safeRequired.level;
    }

    public boolean requiresPickaxe() {
        return this != NONE;
    }

    public static MiningToolTier fromPickaxeItem(String itemId) {
        String normalized = normalize(itemId);
        return switch (normalized) {
            case "minecraft:wooden_pickaxe", "minecraft:golden_pickaxe" -> WOOD;
            case "minecraft:stone_pickaxe" -> STONE;
            case "minecraft:iron_pickaxe" -> IRON;
            case "minecraft:diamond_pickaxe" -> DIAMOND;
            case "minecraft:netherite_pickaxe" -> NETHERITE;
            default -> NONE;
        };
    }

    private static String normalize(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "minecraft:air";
        }
        String normalized = itemId.toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }
}
