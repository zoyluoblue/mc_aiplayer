package io.github.zoyluo.aibot.craft;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RecipeRegistry {
    public record Ingredient(List<Item> anyOf, int count) {
    }

    public record Recipe(Item output, int outputCount, List<Ingredient> ingredients, boolean needsCraftingTable) {
    }

    public static final List<Item> LOGS = List.of(
            Items.OAK_LOG,
            Items.SPRUCE_LOG,
            Items.BIRCH_LOG,
            Items.JUNGLE_LOG,
            Items.ACACIA_LOG,
            Items.DARK_OAK_LOG,
            Items.MANGROVE_LOG,
            Items.CHERRY_LOG);

    public static final List<Item> PLANKS = List.of(
            Items.OAK_PLANKS,
            Items.SPRUCE_PLANKS,
            Items.BIRCH_PLANKS,
            Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS,
            Items.MANGROVE_PLANKS,
            Items.CHERRY_PLANKS);

    private static final List<Item> STICKS = List.of(Items.STICK);
    private static final Map<Item, Recipe> BY_OUTPUT = new HashMap<>();

    static {
        registerAll();
    }

    private RecipeRegistry() {
    }

    public static Optional<Recipe> find(Item output) {
        return Optional.ofNullable(BY_OUTPUT.get(output));
    }

    private static void registerAll() {
        for (int index = 0; index < LOGS.size(); index++) {
            put(new Recipe(PLANKS.get(index), 4, List.of(new Ingredient(List.of(LOGS.get(index)), 1)), false));
        }
        put(new Recipe(Items.STICK, 4, List.of(new Ingredient(PLANKS, 2)), false));
        put(new Recipe(Items.CRAFTING_TABLE, 1, List.of(new Ingredient(PLANKS, 4)), false));
        put(new Recipe(Items.TORCH, 4, List.of(
                new Ingredient(List.of(Items.COAL, Items.CHARCOAL), 1),
                new Ingredient(STICKS, 1)), false));
        put(new Recipe(Items.BOWL, 4, List.of(new Ingredient(PLANKS, 3)), false));
        put(new Recipe(Items.BREAD, 1, List.of(new Ingredient(List.of(Items.WHEAT), 3)), false));

        put(new Recipe(Items.FURNACE, 1, List.of(new Ingredient(List.of(Items.COBBLESTONE), 8)), true));
        put(new Recipe(Items.CHEST, 1, List.of(new Ingredient(PLANKS, 8)), true));
        put(new Recipe(Items.LADDER, 3, List.of(new Ingredient(STICKS, 7)), true));

        tool(Items.WOODEN_PICKAXE, PLANKS, 3);
        tool(Items.STONE_PICKAXE, List.of(Items.COBBLESTONE), 3);
        tool(Items.IRON_PICKAXE, List.of(Items.IRON_INGOT), 3);
        tool(Items.WOODEN_AXE, PLANKS, 3);
        tool(Items.STONE_AXE, List.of(Items.COBBLESTONE), 3);
        tool(Items.IRON_AXE, List.of(Items.IRON_INGOT), 3);
        tool(Items.WOODEN_SHOVEL, PLANKS, 1);
        tool(Items.STONE_SHOVEL, List.of(Items.COBBLESTONE), 1);
        tool(Items.IRON_SHOVEL, List.of(Items.IRON_INGOT), 1);
        sword(Items.WOODEN_SWORD, PLANKS, 2);
        sword(Items.STONE_SWORD, List.of(Items.COBBLESTONE), 2);
        sword(Items.IRON_SWORD, List.of(Items.IRON_INGOT), 2);

        // P3:锄头(2 头料 + 2 木棍),供农业链倒推。
        tool(Items.WOODEN_HOE, PLANKS, 2);
        tool(Items.STONE_HOE, List.of(Items.COBBLESTONE), 2);
        tool(Items.IRON_HOE, List.of(Items.IRON_INGOT), 2);
    }

    private static void tool(Item output, List<Item> head, int headCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(head, headCount), new Ingredient(STICKS, 2)), true));
    }

    private static void sword(Item output, List<Item> head, int headCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(head, headCount), new Ingredient(STICKS, 1)), true));
    }

    private static void put(Recipe recipe) {
        BY_OUTPUT.put(recipe.output(), recipe);
    }
}
