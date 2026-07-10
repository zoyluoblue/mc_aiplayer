package io.github.zoyluo.aibot.craft;

import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时配方索引(知识层数据驱动):服务端启动后从 RecipeManager 扫全部 crafting 配方建索引,
 * 让规划器能倒推**手写表没收录的物品**——包括任何模组物品(暮色森林等的配方自动可用)。
 *
 * 两级策略(见 RecipeRegistry.find):手写表优先(vanilla 关键链路钉死、行为零回归),
 * 未命中才查本索引(长尾/模组兜底)。同物多配方时选"材料种类最少"的一条(确定性,利于倒推收敛)。
 */
public final class RuntimeRecipeIndex {
    private static final Map<Item, RecipeRegistry.Recipe> INDEX = new HashMap<>();
    private static volatile boolean ready;

    private RuntimeRecipeIndex() {
    }

    public static void rebuild(MinecraftServer server) {
        Map<Item, RecipeRegistry.Recipe> fresh = new HashMap<>();
        int scanned = 0;
        int indexed = 0;
        for (RecipeEntry<?> entry : server.getRecipeManager().values()) {
            scanned++;
            try {
                if (!(entry.value() instanceof CraftingRecipe crafting)) {
                    continue;
                }
                // 1.21.3 配方重构后 result 无公开 getter:shaped/shapeless 的 craft() 实现都是
                // 直接 result.copy()(不看输入),用空输入调它即取得产物;特殊配方(染色等)会抛/返回空,
                // 由外层 catch 与 isEmpty 跳过。
                ItemStack result = crafting.craft(
                        net.minecraft.recipe.input.CraftingRecipeInput.EMPTY, server.getRegistryManager());
                if (result == null || result.isEmpty()) {
                    continue;
                }
                List<RecipeRegistry.Ingredient> ingredients = convertIngredients(crafting);
                if (ingredients == null || ingredients.isEmpty()) {
                    continue;
                }
                boolean needsTable = needsCraftingTable(crafting, ingredients);
                RecipeRegistry.Recipe candidate = new RecipeRegistry.Recipe(
                        result.getItem(), result.getCount(), ingredients, needsTable);
                RecipeRegistry.Recipe existing = fresh.get(result.getItem());
                // 同物多配方:选材料种类最少的(倒推收敛快且确定);并列保留先到的。
                if (existing == null || candidate.ingredients().size() < existing.ingredients().size()) {
                    fresh.put(result.getItem(), candidate);
                }
                indexed++;
            } catch (RuntimeException ignored) {
                // 单条坏配方(模组自定义序列化等)不毁整个索引
            }
        }
        pruneReciprocalPairs(fresh);
        synchronized (INDEX) {
            INDEX.clear();
            INDEX.putAll(fresh);
        }
        ready = true;
        BotLog.comm(null, "runtime_recipe_index_built", "scanned", scanned, "indexed", INDEX.size());
    }

    // 剔除互逆配方对(块↔物存储转换:铁块↔9铁锭/粗铁块↔9粗铁/煤块↔9煤…):它们是仓储压缩,
    // 不是获取途径——进了索引会被"材料种类最少"选中压过正道,倒推成 A→B→A 死循环
    //(实测 cycle:iron_block 后修了锭,又在 raw_iron_block 复发——系统性问题系统性除)。
    private static void pruneReciprocalPairs(Map<Item, RecipeRegistry.Recipe> index) {
        List<Item> toRemove = new ArrayList<>();
        for (Map.Entry<Item, RecipeRegistry.Recipe> e : index.entrySet()) {
            RecipeRegistry.Recipe r = e.getValue();
            if (r.ingredients().size() != 1 || r.ingredients().get(0).anyOf().size() != 1) {
                continue;
            }
            Item material = r.ingredients().get(0).anyOf().get(0);
            RecipeRegistry.Recipe back = index.get(material);
            if (back != null && back.ingredients().size() == 1
                    && back.ingredients().get(0).anyOf().size() == 1
                    && back.ingredients().get(0).anyOf().get(0) == e.getKey()) {
                toRemove.add(e.getKey());
                toRemove.add(material);
            }
        }
        toRemove.forEach(index::remove);
    }

    /** 手写表未命中时的兜底查找(见 RecipeRegistry.find 两级策略)。索引未建(单测/早期)返回 empty。 */
    public static Optional<RecipeRegistry.Recipe> find(Item item) {
        if (!ready) {
            return Optional.empty();
        }
        synchronized (INDEX) {
            return Optional.ofNullable(INDEX.get(item));
        }
    }

    public static void clear() {
        synchronized (INDEX) {
            INDEX.clear();
        }
        ready = false;
    }

    // Ingredient(同槽多候选) → 我们的 anyOf 结构;同种材料多槽合并 count。
    // 1.21.3:原料统一从 getIngredientPlacement().getIngredients() 取(shaped/shapeless 同口),
    // 取物用 getMatchingItems()(RegistryEntry 列表);hasNoPlacement=动态特殊配方,调用方跳过。
    private static List<RecipeRegistry.Ingredient> convertIngredients(CraftingRecipe crafting) {
        if (crafting.getIngredientPlacement().hasNoPlacement()) {
            return List.of();
        }
        Map<List<Item>, Integer> merged = new HashMap<>();
        for (Ingredient ing : crafting.getIngredientPlacement().getIngredients()) {
            List<Item> anyOf = new ArrayList<>();
            for (net.minecraft.registry.entry.RegistryEntry<Item> e : ing.getMatchingItems()) {
                Item item = e.value();
                if (!anyOf.contains(item)) {
                    anyOf.add(item);
                }
            }
            if (anyOf.isEmpty()) {
                continue;
            }
            merged.merge(anyOf, 1, Integer::sum);
        }
        List<RecipeRegistry.Ingredient> out = new ArrayList<>();
        for (Map.Entry<List<Item>, Integer> e : merged.entrySet()) {
            out.add(new RecipeRegistry.Ingredient(List.copyOf(e.getKey()), e.getValue()));
        }
        return out;
    }

    // 3x3 才需要工作台:shaped 看宽高,shapeless 看总材料格数(>4 需要)。
    private static boolean needsCraftingTable(CraftingRecipe crafting, List<RecipeRegistry.Ingredient> ingredients) {
        if (crafting instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        int slots = ingredients.stream().mapToInt(RecipeRegistry.Ingredient::count).sum();
        return slots > 4;
    }
}
