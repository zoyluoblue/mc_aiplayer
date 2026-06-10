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
    // 石料族:普通圆石/深板岩圆石/黑石——三者在 MC 里都能做熔炉与石工具(实测:bot 在 Y=-59 全是深板岩,
    // 旧配方只认 cobblestone,导致深层做不了熔炉、MINE stone 撞基岩、goal replan 死循环)。
    private static final List<Item> STONE_LIKE = List.of(
            Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE);
    private static final Map<Item, Recipe> BY_OUTPUT = new HashMap<>();

    static {
        registerAll();
    }

    private RecipeRegistry() {
    }

    // 两级查找:手写表优先(vanilla 关键链路钉死,确定性零回归);未命中查运行时索引
    //(RecipeManager 全量,模组/长尾物品兜底——暮色森林等模组配方自动可倒推)。
    public static Optional<Recipe> find(Item output) {
        Recipe handwritten = BY_OUTPUT.get(output);
        if (handwritten != null) {
            return Optional.of(handwritten);
        }
        return RuntimeRecipeIndex.find(output);
    }

    // S1:供依赖链审计(/aibot deplint)遍历全部已知配方。
    public static java.util.Collection<Recipe> all() {
        return java.util.Collections.unmodifiableCollection(BY_OUTPUT.values());
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
        // 蛋糕链:糖←甘蔗;桶←3铁;蛋糕=3奶桶+2糖+1蛋+3麦(蛋为被动产物,需背包已有,见 GoalPlanner)。
        put(new Recipe(Items.SUGAR, 1, List.of(new Ingredient(List.of(Items.SUGAR_CANE), 1)), false));
        put(new Recipe(Items.BUCKET, 1, List.of(new Ingredient(List.of(Items.IRON_INGOT), 3)), true));
        put(new Recipe(Items.CAKE, 1, List.of(
                new Ingredient(List.of(Items.MILK_BUCKET), 3),
                new Ingredient(List.of(Items.SUGAR), 2),
                new Ingredient(List.of(Items.EGG), 1),
                new Ingredient(List.of(Items.WHEAT), 3)), true));

        put(new Recipe(Items.FURNACE, 1, List.of(new Ingredient(STONE_LIKE, 8)), true));
        put(new Recipe(Items.CHEST, 1, List.of(new Ingredient(PLANKS, 8)), true));
        put(new Recipe(Items.LADDER, 3, List.of(new Ingredient(STICKS, 7)), true));

        tool(Items.WOODEN_PICKAXE, PLANKS, 3);
        tool(Items.STONE_PICKAXE, STONE_LIKE, 3);
        tool(Items.IRON_PICKAXE, List.of(Items.IRON_INGOT), 3);
        tool(Items.WOODEN_AXE, PLANKS, 3);
        tool(Items.STONE_AXE, STONE_LIKE, 3);
        tool(Items.IRON_AXE, List.of(Items.IRON_INGOT), 3);
        tool(Items.WOODEN_SHOVEL, PLANKS, 1);
        tool(Items.STONE_SHOVEL, STONE_LIKE, 1);
        tool(Items.IRON_SHOVEL, List.of(Items.IRON_INGOT), 1);
        sword(Items.WOODEN_SWORD, PLANKS, 2);
        sword(Items.STONE_SWORD, STONE_LIKE, 2);
        sword(Items.IRON_SWORD, List.of(Items.IRON_INGOT), 2);

        // P3:锄头(2 头料 + 2 木棍),供农业链倒推。
        tool(Items.WOODEN_HOE, PLANKS, 2);
        tool(Items.STONE_HOE, STONE_LIKE, 2);
        tool(Items.IRON_HOE, List.of(Items.IRON_INGOT), 2);

        // 第3层:铁甲(装备前置倒推用)。vanilla 用量——头5/胸8/腿7/脚4,纯金属无木棍。
        armorOf(Items.IRON_HELMET, Items.IRON_INGOT, 5);
        armorOf(Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8);
        armorOf(Items.IRON_LEGGINGS, Items.IRON_INGOT, 7);
        armorOf(Items.IRON_BOOTS, Items.IRON_INGOT, 4);

        // S1:钻石/金工具(挖钻后升级、高效挖矿)。
        tool(Items.DIAMOND_PICKAXE, List.of(Items.DIAMOND), 3);
        tool(Items.DIAMOND_AXE, List.of(Items.DIAMOND), 3);
        tool(Items.DIAMOND_SHOVEL, List.of(Items.DIAMOND), 1);
        tool(Items.DIAMOND_HOE, List.of(Items.DIAMOND), 2);
        sword(Items.DIAMOND_SWORD, List.of(Items.DIAMOND), 2);
        tool(Items.GOLDEN_PICKAXE, List.of(Items.GOLD_INGOT), 3);
        sword(Items.GOLDEN_SWORD, List.of(Items.GOLD_INGOT), 2);

        // S1:钻石甲 + 金甲(防具升级链 S30/S34 用)。
        armorOf(Items.DIAMOND_HELMET, Items.DIAMOND, 5);
        armorOf(Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8);
        armorOf(Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7);
        armorOf(Items.DIAMOND_BOOTS, Items.DIAMOND, 4);
        armorOf(Items.GOLDEN_HELMET, Items.GOLD_INGOT, 5);
        armorOf(Items.GOLDEN_CHESTPLATE, Items.GOLD_INGOT, 8);
        armorOf(Items.GOLDEN_LEGGINGS, Items.GOLD_INGOT, 7);
        armorOf(Items.GOLDEN_BOOTS, Items.GOLD_INGOT, 4);

        // S1:盾牌(防具 S32)——6 木板 + 1 铁锭。
        put(new Recipe(Items.SHIELD, 1, List.of(
                new Ingredient(PLANKS, 6),
                new Ingredient(List.of(Items.IRON_INGOT), 1)), true));

        // S1:养殖/圈养基建(模块 E)——栅栏、干草块。
        put(new Recipe(Items.OAK_FENCE, 3, List.of(
                new Ingredient(PLANKS, 4),
                new Ingredient(STICKS, 2)), true));
        put(new Recipe(Items.HAY_BLOCK, 1, List.of(new Ingredient(List.of(Items.WHEAT), 9)), false));
    }

    private static void tool(Item output, List<Item> head, int headCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(head, headCount), new Ingredient(STICKS, 2)), true));
    }

    private static void sword(Item output, List<Item> head, int headCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(head, headCount), new Ingredient(STICKS, 1)), true));
    }

    // 护甲(纯金属,无木棍)。material = 铁锭/钻石/金锭。
    private static void armorOf(Item output, Item material, int ingotCount) {
        put(new Recipe(output, 1, List.of(new Ingredient(List.of(material), ingotCount)), true));
    }

    private static void put(Recipe recipe) {
        BY_OUTPUT.put(recipe.output(), recipe);
    }
}
