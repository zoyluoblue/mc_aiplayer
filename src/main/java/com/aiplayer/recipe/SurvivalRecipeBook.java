package com.aiplayer.recipe;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.entity.AiPlayerEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SurvivalRecipeBook {
    public static final List<String> WOOD_LOGS = List.of(
        "minecraft:oak_log",
        "minecraft:spruce_log",
        "minecraft:birch_log",
        "minecraft:jungle_log",
        "minecraft:acacia_log",
        "minecraft:dark_oak_log",
        "minecraft:mangrove_log",
        "minecraft:cherry_log"
    );
    public static final List<String> WOOD_PLANKS = List.of(
        "minecraft:oak_planks",
        "minecraft:spruce_planks",
        "minecraft:birch_planks",
        "minecraft:jungle_planks",
        "minecraft:acacia_planks",
        "minecraft:dark_oak_planks",
        "minecraft:mangrove_planks",
        "minecraft:cherry_planks"
    );
    public static final Map<String, String> PLANK_TO_LOG = Map.ofEntries(
        Map.entry("minecraft:oak_planks", "minecraft:oak_log"),
        Map.entry("minecraft:spruce_planks", "minecraft:spruce_log"),
        Map.entry("minecraft:birch_planks", "minecraft:birch_log"),
        Map.entry("minecraft:jungle_planks", "minecraft:jungle_log"),
        Map.entry("minecraft:acacia_planks", "minecraft:acacia_log"),
        Map.entry("minecraft:dark_oak_planks", "minecraft:dark_oak_log"),
        Map.entry("minecraft:mangrove_planks", "minecraft:mangrove_log"),
        Map.entry("minecraft:cherry_planks", "minecraft:cherry_log")
    );
    public static final Map<String, String> DOOR_TO_PLANK = Map.ofEntries(
        Map.entry("minecraft:oak_door", "minecraft:oak_planks"),
        Map.entry("minecraft:spruce_door", "minecraft:spruce_planks"),
        Map.entry("minecraft:birch_door", "minecraft:birch_planks"),
        Map.entry("minecraft:jungle_door", "minecraft:jungle_planks"),
        Map.entry("minecraft:acacia_door", "minecraft:acacia_planks"),
        Map.entry("minecraft:dark_oak_door", "minecraft:dark_oak_planks"),
        Map.entry("minecraft:mangrove_door", "minecraft:mangrove_planks"),
        Map.entry("minecraft:cherry_door", "minecraft:cherry_planks")
    );

    private static final Map<String, Definition> RECIPES = new LinkedHashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();
    private static final Map<String, String> BASE_SOURCES = new HashMap<>();
    private static final Map<String, String> BASE_TOOLS = new HashMap<>();
    private static final Map<String, Integer> SOURCE_DROP_COUNTS = new HashMap<>();
    private static final int MAX_RECIPE_VARIANTS = 256;

    static {
        recipe("minecraft:stick", 4, "inventory", null, "木板合成木棍", "minecraft:oak_planks", 2);
        recipe("minecraft:crafting_table", 1, "inventory", null, "木板合成工作台", "minecraft:oak_planks", 4);
        recipe("minecraft:chest", 1, "crafting_table", "minecraft:crafting_table", "木板合成箱子", "minecraft:oak_planks", 8);
        recipe("minecraft:torch", 4, "inventory", null, "煤和木棍合成火把", "minecraft:coal", 1, "minecraft:stick", 1);
        recipe("minecraft:furnace", 1, "crafting_table", "minecraft:crafting_table", "圆石合成熔炉", "minecraft:cobblestone", 8);

        registerToolSet("wooden", "minecraft:oak_planks", "木制工具需要木板和木棍");
        registerToolSet("stone", "minecraft:cobblestone", "石制工具需要圆石和木棍");

        recipe("minecraft:iron_ingot", 1, "furnace", "minecraft:furnace", "熔炉烧炼生铁为铁锭",
            "minecraft:raw_iron", 1, "minecraft:coal", 1);
        recipe("minecraft:copper_ingot", 1, "furnace", "minecraft:furnace", "熔炉烧炼粗铜为铜锭",
            "minecraft:raw_copper", 1, "minecraft:coal", 1);
        recipe("minecraft:gold_ingot", 1, "furnace", "minecraft:furnace", "熔炉烧炼粗金为金锭",
            "minecraft:raw_gold", 1, "minecraft:coal", 1);
        recipe("minecraft:cooked_beef", 1, "furnace", "minecraft:furnace", "熔炉烧熟牛肉",
            "minecraft:beef", 1, "minecraft:coal", 1);

        registerToolSet("iron", "minecraft:iron_ingot", "铁制工具需要铁锭和木棍");
        registerToolSet("golden", "minecraft:gold_ingot", "金制工具需要金锭和木棍");
        registerToolSet("diamond", "minecraft:diamond", "钻石工具需要钻石和木棍");
        registerArmorSet("iron", "minecraft:iron_ingot", "铁护甲需要铁锭");
        registerArmorSet("golden", "minecraft:gold_ingot", "金护甲需要金锭");
        registerArmorSet("diamond", "minecraft:diamond", "钻石护甲需要钻石");

        recipe("minecraft:shield", 1, "crafting_table", "minecraft:crafting_table", "木板和铁锭合成盾牌",
            "minecraft:oak_planks", 6, "minecraft:iron_ingot", 1);
        recipe("minecraft:bucket", 1, "crafting_table", "minecraft:crafting_table", "铁锭合成桶",
            "minecraft:iron_ingot", 3);
        recipe("minecraft:water_bucket", 1, "water_source", null, "用空桶装水",
            "minecraft:bucket", 1);
        recipe("minecraft:shears", 1, "crafting_table", "minecraft:crafting_table", "铁锭合成剪刀",
            "minecraft:iron_ingot", 2);

        alias("minecraft:oak_door", "door", "woodendoor", "木门", "门");
        alias("minecraft:oak_planks", "planks", "woodenplanks", "木板");
        alias("minecraft:oak_log", "log", "wood", "原木", "木头");
        alias("minecraft:stick", "stick", "sticks", "木棍");
        alias("minecraft:crafting_table", "craftingtable", "workbench", "工作台");
        alias("minecraft:chest", "chest", "箱子");
        alias("minecraft:torch", "torch", "torches", "火把");
        alias("minecraft:furnace", "furnace", "熔炉");
        alias("minecraft:coal", "coal", "煤", "煤炭");
        alias("minecraft:raw_iron", "rawiron", "生铁", "粗铁", "铁矿");
        alias("minecraft:iron_ingot", "ironingot", "铁锭");
        alias("minecraft:raw_copper", "rawcopper", "粗铜", "铜矿");
        alias("minecraft:copper_ingot", "copperingot", "铜锭");
        alias("minecraft:raw_gold", "rawgold", "粗金", "金矿");
        alias("minecraft:gold_ingot", "goldingot", "金锭");
        alias("minecraft:diamond", "diamond", "diamonds", "钻石");
        alias("minecraft:redstone", "redstone", "红石");
        alias("minecraft:lapis_lazuli", "lapislazuli", "lapis", "青金石");
        alias("minecraft:emerald", "emerald", "绿宝石");
        alias("minecraft:quartz", "netherquartz", "quartz", "下界石英", "石英", "石英矿");
        alias("minecraft:gold_nugget", "goldnugget", "金粒", "下界金", "下界金矿");
        alias("minecraft:obsidian", "obsidian", "黑曜石");
        alias("minecraft:ancient_debris", "ancientdebris", "远古残骸");
        alias("minecraft:shield", "shield", "盾", "盾牌");
        alias("minecraft:bucket", "bucket", "桶", "铁桶");
        alias("minecraft:water_bucket", "waterbucket", "水桶", "一桶水", "桶水");
        alias("minecraft:shears", "shears", "剪刀");
        alias("minecraft:beef", "beef", "rawbeef", "生牛肉", "牛肉");
        alias("minecraft:cooked_beef", "cookedbeef", "steak", "熟牛肉", "熟牛排", "牛排");

        registerToolAliases("wooden", "wood", "木");
        registerToolAliases("stone", "stone", "石");
        registerToolAliases("iron", "iron", "铁");
        registerToolAliases("golden", "gold", "金");
        registerToolAliases("diamond", "diamond", "钻石");
        registerArmorAliases("iron", "iron", "铁");
        registerArmorAliases("golden", "gold", "金");
        registerArmorAliases("diamond", "diamond", "钻石");

        source("minecraft:cobblestone", "stone", "minecraft:wooden_pickaxe");
        for (MiningResource.Profile profile : MiningResource.profiles()) {
            source(profile.item(), profile.source(), profile.requiredTool());
        }
        source("minecraft:glowstone_dust", "block:minecraft:glowstone", null, 4);
        source("minecraft:clay_ball", "block:minecraft:clay", null, 4);
        source("minecraft:flint", "block:minecraft:gravel", null);
        source("minecraft:string", "block:minecraft:cobweb", "minecraft:shears");
        source("minecraft:sugar_cane", "block:minecraft:sugar_cane", null);
        source("minecraft:bamboo", "block:minecraft:bamboo", null);
        source("minecraft:cactus", "block:minecraft:cactus", null);
        source("minecraft:kelp", "block:minecraft:kelp", null);
        source("minecraft:seagrass", "block:minecraft:seagrass", "minecraft:shears");
        source("minecraft:vine", "block:minecraft:vine", "minecraft:shears");
        source("minecraft:brown_mushroom", "block:minecraft:brown_mushroom", null);
        source("minecraft:red_mushroom", "block:minecraft:red_mushroom", null);
        source("minecraft:pumpkin", "block:minecraft:pumpkin", null);
        source("minecraft:melon_slice", "block:minecraft:melon", null, 7);
        source("minecraft:beef", "mob:minecraft:cow", null);
    }

    private SurvivalRecipeBook() {
    }

    public static Optional<Definition> find(String item) {
        return findLocal(item);
    }

    public static Optional<Definition> findLocal(String item) {
        if (PLANK_TO_LOG.containsKey(item)) {
            return Optional.of(new Definition(
                MaterialRequirement.of(item, 4),
                List.of(MaterialRequirement.of(PLANK_TO_LOG.get(item), 1)),
                "inventory",
                null,
                "原木分解为木板"
            ));
        }
        if (DOOR_TO_PLANK.containsKey(item)) {
            return Optional.of(new Definition(
                MaterialRequirement.of(item, 3),
                List.of(MaterialRequirement.of(DOOR_TO_PLANK.get(item), 6)),
                "crafting_table",
                "minecraft:crafting_table",
                "木门需要 2x3 配方"
            ));
        }
        return Optional.ofNullable(RECIPES.get(item));
    }

    public static List<Definition> findAll(AiPlayerEntity aiPlayer, String item) {
        List<Definition> definitions = new ArrayList<>();
        findLocal(item).ifPresent(definitions::add);
        definitions.addAll(findVanilla(aiPlayer, item));
        return deduplicate(definitions);
    }

    public static boolean isSupported(String item) {
        return findLocal(item).isPresent();
    }

    public static String normalizeItemId(String item) {
        if (item == null || item.isBlank()) {
            return "minecraft:air";
        }
        String normalized = item.trim().toLowerCase(Locale.ROOT);
        String compact = compact(normalized);
        String alias = ALIASES.get(compact);
        if (alias != null) {
            return alias;
        }
        if (normalized.contains(":")) {
            return normalized;
        }
        return "minecraft:" + normalized.replace(" ", "_").replace("-", "_");
    }

    public static String requiredToolForBaseResource(String item) {
        return BASE_TOOLS.get(item);
    }

    public static Optional<String> baseSource(String item) {
        String explicit = BASE_SOURCES.get(item);
        if (explicit != null) {
            return Optional.of(explicit);
        }
        if (isPlainLogItem(item)) {
            return Optional.of("tree");
        }
        Block block = blockFromId(item);
        if (block != Blocks.AIR && block.asItem() != Items.AIR) {
            return Optional.of("block:" + item);
        }
        return Optional.empty();
    }

    public static Optional<String> explicitBaseSource(String item) {
        return Optional.ofNullable(BASE_SOURCES.get(item));
    }

    public static boolean isGenericBlockSource(String item, String source) {
        return source != null
            && source.equals("block:" + item)
            && !BASE_SOURCES.containsKey(item)
            && !isPlainLogItem(item);
    }

    public static boolean isWoodLog(String item) {
        return WOOD_LOGS.contains(item);
    }

    public static boolean isWoodPlank(String item) {
        return WOOD_PLANKS.contains(item);
    }

    public static boolean isGenericWoodLogRequirement(String item) {
        return "minecraft:oak_log".equals(item);
    }

    public static boolean isGenericWoodPlankRequirement(String item) {
        return "minecraft:oak_planks".equals(item);
    }

    public static boolean isGenericWoodMaterialRequirement(String item) {
        return isGenericWoodLogRequirement(item) || isGenericWoodPlankRequirement(item);
    }

    public static List<String> equivalentMaterialItems(String item) {
        if (isGenericWoodLogRequirement(item)) {
            return WOOD_LOGS;
        }
        if (isGenericWoodPlankRequirement(item)) {
            return WOOD_PLANKS;
        }
        return item == null || item.isBlank() ? List.of() : List.of(item);
    }

    public static int sourceDropCount(String item, String source) {
        return SOURCE_DROP_COUNTS.getOrDefault(item + "|" + source, 1);
    }

    public static boolean isGenericWoodenDoor(String item) {
        if (item == null) {
            return false;
        }
        String compact = compact(item);
        return compact.equals("woodendoor") || compact.equals("door") || compact.equals("木门") || compact.equals("门");
    }

    public static String doorForLog(String log) {
        for (Map.Entry<String, String> entry : PLANK_TO_LOG.entrySet()) {
            if (entry.getValue().equals(log)) {
                return doorForPlank(entry.getKey());
            }
        }
        return null;
    }

    public static String doorForPlank(String plank) {
        for (Map.Entry<String, String> entry : DOOR_TO_PLANK.entrySet()) {
            if (entry.getValue().equals(plank)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static Map<String, String> aliases() {
        return Map.copyOf(ALIASES);
    }

    private static List<Definition> findVanilla(AiPlayerEntity aiPlayer, String item) {
        if (aiPlayer == null || !(aiPlayer.level() instanceof ServerLevel serverLevel)) {
            return List.of();
        }
        Item targetItem = itemFromId(item);
        if (targetItem == Items.AIR) {
            return List.of();
        }
        List<Definition> definitions = new ArrayList<>();
        for (RecipeHolder<?> holder : serverLevel.getServer().getRecipeManager().getRecipes()) {
            try {
                definitions.addAll(definitionsFromRecipe(serverLevel, holder.value(), targetItem));
            } catch (RuntimeException e) {
                AiPlayerMod.debug("recipe", "Skipping recipe '{}' during full recipe scan: {}",
                    holder.id().location(), e.getMessage());
            }
        }
        return definitions;
    }

    @SuppressWarnings("unchecked")
    private static List<Definition> definitionsFromRecipe(ServerLevel serverLevel, Recipe<?> recipe, Item targetItem) {
        if (recipe.isSpecial()) {
            return List.of();
        }
        if (recipe.getType() == RecipeType.CRAFTING && recipe instanceof CraftingRecipe craftingRecipe) {
            Recipe<CraftingInput> typedRecipe = (Recipe<CraftingInput>) craftingRecipe;
            ItemStack output = typedRecipe.assemble(CraftingInput.EMPTY, serverLevel.registryAccess());
            if (output.isEmpty() || !output.is(targetItem)) {
                return List.of();
            }
            String station = recipeStation(craftingRecipe);
            return definitionsFromIngredients(
                output,
                craftingRecipe.placementInfo().ingredients(),
                station,
                "crafting_table".equals(station) ? "minecraft:crafting_table" : null,
                "来自原版合成配方"
            );
        }
        if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
            return definitionsFromSingleInputRecipe(
                serverLevel,
                cookingRecipe,
                targetItem,
                cookingStation(recipe.getType()),
                cookingStationItem(recipe.getType()),
                cookingConsumesFuel(recipe.getType()),
                "来自原版烧炼配方"
            );
        }
        if (recipe.getType() == RecipeType.STONECUTTING && recipe instanceof SingleItemRecipe singleItemRecipe) {
            return definitionsFromSingleInputRecipe(
                serverLevel,
                singleItemRecipe,
                targetItem,
                "stonecutter",
                "minecraft:stonecutter",
                false,
                "来自原版切石机配方"
            );
        }
        if (recipe.getType() == RecipeType.SMITHING && recipe instanceof SmithingRecipe smithingRecipe) {
            return definitionsFromSmithingRecipe(serverLevel, smithingRecipe, targetItem);
        }
        return List.of();
    }

    private static List<Definition> definitionsFromSingleInputRecipe(
        ServerLevel serverLevel,
        SingleItemRecipe recipe,
        Item targetItem,
        String station,
        String stationItem,
        boolean consumesFuel,
        String note
    ) {
        List<Ingredient> ingredients = recipe.placementInfo().ingredients();
        if (ingredients.isEmpty()) {
            return List.of();
        }
        List<Definition> definitions = new ArrayList<>();
        for (String inputItem : ingredientOptions(ingredients.getFirst())) {
            ItemStack output = recipe.assemble(new SingleRecipeInput(new ItemStack(itemFromId(inputItem))), serverLevel.registryAccess());
            if (output.isEmpty() || !output.is(targetItem)) {
                continue;
            }
            List<Ingredient> expanded = new ArrayList<>();
            expanded.add(Ingredient.of(itemFromId(inputItem)));
            List<Definition> variants = definitionsFromIngredients(output, expanded, station, stationItem, note);
            if (consumesFuel) {
                for (Definition variant : variants) {
                    List<MaterialRequirement> requirements = new ArrayList<>(variant.requires());
                    requirements.add(MaterialRequirement.of("minecraft:coal", 1));
                    definitions.add(new Definition(variant.output(), List.copyOf(requirements), variant.station(), variant.stationItem(), variant.note()));
                }
            } else {
                definitions.addAll(variants);
            }
            if (definitions.size() >= MAX_RECIPE_VARIANTS) {
                break;
            }
        }
        return definitions;
    }

    private static List<Definition> definitionsFromSmithingRecipe(ServerLevel serverLevel, SmithingRecipe recipe, Item targetItem) {
        List<Ingredient> ingredients = new ArrayList<>();
        recipe.templateIngredient().ifPresent(ingredients::add);
        recipe.baseIngredient().ifPresent(ingredients::add);
        recipe.additionIngredient().ifPresent(ingredients::add);
        List<Map<String, Integer>> alternatives = requirementAlternatives(ingredients);
        List<Definition> definitions = new ArrayList<>();
        for (Map<String, Integer> alternative : alternatives) {
            List<String> keys = new ArrayList<>(alternative.keySet());
            if (keys.size() < 3) {
                continue;
            }
            ItemStack output = recipe.assemble(
                new SmithingRecipeInput(new ItemStack(itemFromId(keys.get(0))), new ItemStack(itemFromId(keys.get(1))), new ItemStack(itemFromId(keys.get(2)))),
                serverLevel.registryAccess()
            );
            if (output.isEmpty() || !output.is(targetItem)) {
                continue;
            }
            definitions.add(new Definition(
                MaterialRequirement.of(itemId(output.getItem()), output.getCount()),
                alternative.entrySet().stream()
                    .map(entry -> MaterialRequirement.of(entry.getKey(), entry.getValue()))
                    .toList(),
                "smithing_table",
                "minecraft:smithing_table",
                "来自原版锻造台配方"
            ));
            if (definitions.size() >= MAX_RECIPE_VARIANTS) {
                break;
            }
        }
        return definitions;
    }

    private static List<Definition> definitionsFromIngredients(
        ItemStack output,
        List<Ingredient> ingredients,
        String station,
        String stationItem,
        String note
    ) {
        List<Map<String, Integer>> alternatives = requirementAlternatives(ingredients);
        List<Definition> definitions = new ArrayList<>();
        for (Map<String, Integer> alternative : alternatives) {
            definitions.add(new Definition(
                MaterialRequirement.of(itemId(output.getItem()), output.getCount()),
                alternative.entrySet().stream()
                    .map(entry -> MaterialRequirement.of(entry.getKey(), entry.getValue()))
                    .toList(),
                station,
                stationItem,
                note
            ));
        }
        return definitions;
    }

    private static List<Map<String, Integer>> requirementAlternatives(List<Ingredient> ingredients) {
        List<Map<String, Integer>> alternatives = new ArrayList<>();
        alternatives.add(new LinkedHashMap<>());
        for (Ingredient ingredient : ingredients) {
            List<String> options = ingredientOptions(ingredient);
            if (options.isEmpty()) {
                return List.of();
            }
            List<Map<String, Integer>> next = new ArrayList<>();
            for (Map<String, Integer> existing : alternatives) {
                for (String option : options) {
                    Map<String, Integer> candidate = new LinkedHashMap<>(existing);
                    candidate.merge(option, 1, Integer::sum);
                    next.add(candidate);
                    if (next.size() >= MAX_RECIPE_VARIANTS) {
                        break;
                    }
                }
                if (next.size() >= MAX_RECIPE_VARIANTS) {
                    break;
                }
            }
            alternatives = next;
        }
        return alternatives;
    }

    private static List<String> ingredientOptions(Ingredient ingredient) {
        List<String> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Holder<Item> holder : ingredient.items()) {
            String item = itemId(holder.value());
            if (!"minecraft:air".equals(item) && seen.add(item)) {
                options.add(item);
            }
        }
        return options;
    }

    private static String recipeStation(CraftingRecipe recipe) {
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            return shapedRecipe.getWidth() <= 2 && shapedRecipe.getHeight() <= 2 ? "inventory" : "crafting_table";
        }
        int ingredientCount = recipe.placementInfo().ingredients().size();
        return ingredientCount <= 4 ? "inventory" : "crafting_table";
    }

    private static String cookingStation(RecipeType<?> recipeType) {
        if (recipeType == RecipeType.BLASTING) {
            return "blast_furnace";
        }
        if (recipeType == RecipeType.SMOKING) {
            return "smoker";
        }
        if (recipeType == RecipeType.CAMPFIRE_COOKING) {
            return "campfire";
        }
        return "furnace";
    }

    private static String cookingStationItem(RecipeType<?> recipeType) {
        return switch (cookingStation(recipeType)) {
            case "blast_furnace" -> "minecraft:blast_furnace";
            case "smoker" -> "minecraft:smoker";
            case "campfire" -> "minecraft:campfire";
            default -> "minecraft:furnace";
        };
    }

    private static boolean cookingConsumesFuel(RecipeType<?> recipeType) {
        return recipeType != RecipeType.CAMPFIRE_COOKING;
    }

    private static void registerToolSet(String tier, String material, String note) {
        recipe("minecraft:" + tier + "_pickaxe", 1, "crafting_table", "minecraft:crafting_table", note,
            material, 3, "minecraft:stick", 2);
        recipe("minecraft:" + tier + "_axe", 1, "crafting_table", "minecraft:crafting_table", note,
            material, 3, "minecraft:stick", 2);
        recipe("minecraft:" + tier + "_shovel", 1, "crafting_table", "minecraft:crafting_table", note,
            material, 1, "minecraft:stick", 2);
        recipe("minecraft:" + tier + "_sword", 1, "crafting_table", "minecraft:crafting_table", note,
            material, 2, "minecraft:stick", 1);
        recipe("minecraft:" + tier + "_hoe", 1, "crafting_table", "minecraft:crafting_table", note,
            material, 2, "minecraft:stick", 2);
    }

    private static void registerArmorSet(String tier, String material, String note) {
        recipe("minecraft:" + tier + "_helmet", 1, "crafting_table", "minecraft:crafting_table", note, material, 5);
        recipe("minecraft:" + tier + "_chestplate", 1, "crafting_table", "minecraft:crafting_table", note, material, 8);
        recipe("minecraft:" + tier + "_leggings", 1, "crafting_table", "minecraft:crafting_table", note, material, 7);
        recipe("minecraft:" + tier + "_boots", 1, "crafting_table", "minecraft:crafting_table", note, material, 4);
    }

    private static void registerToolAliases(String tier, String englishPrefix, String chinesePrefix) {
        String tierId = "minecraft:" + tier;
        alias(tierId + "_pickaxe", englishPrefix + "pickaxe", chinesePrefix + "镐", chinesePrefix + "镐子", chinesePrefix + "稿", chinesePrefix + "稿子");
        alias(tierId + "_axe", englishPrefix + "axe", chinesePrefix + "斧", chinesePrefix + "斧头", chinesePrefix + "斧子");
        alias(tierId + "_shovel", englishPrefix + "shovel", chinesePrefix + "铲", chinesePrefix + "铲子", chinesePrefix + "锹", chinesePrefix + "锹子");
        alias(tierId + "_sword", englishPrefix + "sword", chinesePrefix + "剑");
        alias(tierId + "_hoe", englishPrefix + "hoe", chinesePrefix + "锄", chinesePrefix + "锄头");
        if ("wooden".equals(tier)) {
            alias(tierId + "_pickaxe", "woodpickaxe");
            alias(tierId + "_axe", "woodaxe", "斧头", "斧子");
            alias(tierId + "_shovel", "woodshovel");
            alias(tierId + "_sword", "woodsword");
            alias(tierId + "_hoe", "woodhoe");
        }
        if ("golden".equals(tier)) {
            alias(tierId + "_pickaxe", "goldpickaxe");
            alias(tierId + "_axe", "goldaxe");
            alias(tierId + "_shovel", "goldshovel");
            alias(tierId + "_sword", "goldsword");
            alias(tierId + "_hoe", "goldhoe");
        }
    }

    private static void registerArmorAliases(String tier, String englishPrefix, String chinesePrefix) {
        String tierId = "minecraft:" + tier;
        alias(tierId + "_helmet", englishPrefix + "helmet", chinesePrefix + "头盔", chinesePrefix + "帽子");
        alias(tierId + "_chestplate", englishPrefix + "chestplate", chinesePrefix + "胸甲", chinesePrefix + "盔甲");
        alias(tierId + "_leggings", englishPrefix + "leggings", chinesePrefix + "护腿", chinesePrefix + "裤子");
        alias(tierId + "_boots", englishPrefix + "boots", chinesePrefix + "靴子", chinesePrefix + "鞋子");
        if ("golden".equals(tier)) {
            alias(tierId + "_helmet", "goldhelmet");
            alias(tierId + "_chestplate", "goldchestplate");
            alias(tierId + "_leggings", "goldleggings");
            alias(tierId + "_boots", "goldboots");
        }
    }

    private static void recipe(String item, int outputCount, String station, String stationItem, String note, Object... requirements) {
        RECIPES.put(item, new Definition(
            MaterialRequirement.of(item, outputCount),
            requirements(requirements),
            station,
            stationItem,
            note
        ));
    }

    private static List<MaterialRequirement> requirements(Object... values) {
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Recipe requirements must be item/count pairs");
        }
        List<MaterialRequirement> requirements = new java.util.ArrayList<>();
        for (int i = 0; i < values.length; i += 2) {
            requirements.add(MaterialRequirement.of((String) values[i], (Integer) values[i + 1]));
        }
        return List.copyOf(requirements);
    }

    private static void alias(String item, String... aliases) {
        ALIASES.put(compact(item), item);
        ALIASES.put(compact(item.replace("minecraft:", "")), item);
        for (String alias : aliases) {
            ALIASES.put(compact(alias), item);
        }
    }

    private static void source(String item, String source, String requiredTool) {
        source(item, source, requiredTool, 1);
    }

    private static void source(String item, String source, String requiredTool, int dropCount) {
        BASE_SOURCES.put(item, source);
        if (requiredTool != null) {
            BASE_TOOLS.put(item, requiredTool);
        }
        SOURCE_DROP_COUNTS.put(item + "|" + source, Math.max(1, dropCount));
    }

    private static String compact(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "").replace("minecraft:", "");
    }

    private static boolean isPlainLogItem(String item) {
        return item.endsWith("_log")
            && !item.contains("stripped_")
            && item.startsWith("minecraft:");
    }

    private static List<Definition> deduplicate(List<Definition> definitions) {
        List<Definition> deduplicated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Definition definition : definitions) {
            String key = definition.output() + "|" + definition.requires() + "|" + definition.station() + "|" + definition.stationItem();
            if (seen.add(key)) {
                deduplicated.add(definition);
            }
        }
        return List.copyOf(deduplicated);
    }

    private static Item itemFromId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Items.AIR;
        }
        try {
            return BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(itemId));
        } catch (RuntimeException e) {
            return Items.AIR;
        }
    }

    private static Block blockFromId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return Blocks.AIR;
        }
        try {
            return BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse(blockId));
        } catch (RuntimeException e) {
            return Blocks.AIR;
        }
    }

    private static String itemId(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "minecraft:air" : key.toString();
    }

    public record Definition(MaterialRequirement output, List<MaterialRequirement> requires, String station, String stationItem, String note) {
    }
}
