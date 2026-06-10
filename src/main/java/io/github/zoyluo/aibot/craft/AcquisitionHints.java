package io.github.zoyluo.aibot.craft;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.Set;

public final class AcquisitionHints {
    private static final Set<Item> MINE_ITEMS = Set.of(
            Items.COBBLESTONE,
            Items.STONE,
            Items.COAL,
            Items.RAW_IRON,
            Items.RAW_COPPER,
            Items.RAW_GOLD,
            Items.IRON_ORE,
            Items.COAL_ORE,
            Items.COPPER_ORE,
            Items.GOLD_ORE,
            Items.OBSIDIAN);

    private static final Set<Item> SMELT_ITEMS = Set.of(
            Items.IRON_INGOT,
            Items.COPPER_INGOT,
            Items.GOLD_INGOT,
            Items.STONE,
            Items.CHARCOAL);

    private static final Set<Item> CRAFT_ITEMS = Set.of(
            Items.STICK,
            Items.CRAFTING_TABLE,
            Items.FURNACE,
            Items.CHEST,
            Items.TORCH,
            Items.WOODEN_PICKAXE,
            Items.STONE_PICKAXE,
            Items.IRON_PICKAXE,
            Items.WOODEN_AXE,
            Items.STONE_AXE,
            Items.IRON_AXE,
            Items.WOODEN_SHOVEL,
            Items.STONE_SHOVEL,
            Items.IRON_SHOVEL,
            Items.WOODEN_SWORD,
            Items.STONE_SWORD,
            Items.IRON_SWORD);

    private AcquisitionHints() {
    }

    public static String source(Item item) {
        if (RecipeRegistry.LOGS.contains(item) || MINE_ITEMS.contains(item)) {
            return "mine";
        }
        if (RecipeRegistry.PLANKS.contains(item) || CRAFT_ITEMS.contains(item)) {
            return "craft";
        }
        if (SMELT_ITEMS.contains(item)) {
            return "smelt";
        }
        if (isForageItem(item)) {
            return "forage";
        }
        return "unknown";
    }

    private static boolean isForageItem(Item item) {
        return item == Items.WHEAT
                || item == Items.WHEAT_SEEDS
                || item == Items.CARROT
                || item == Items.POTATO
                || item == Items.BEEF
                || item == Items.PORKCHOP
                || item == Items.CHICKEN
                || item == Items.MUTTON;
    }
}
