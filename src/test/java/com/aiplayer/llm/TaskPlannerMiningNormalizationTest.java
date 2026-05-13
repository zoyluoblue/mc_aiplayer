package com.aiplayer.llm;

import com.aiplayer.action.Task;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskPlannerMiningNormalizationTest {
    private final RecipeResolver recipeResolver = new RecipeResolver();
    private final WorldSnapshot snapshot = WorldSnapshot.empty("");

    @Test
    void normalizesLegacyTargetParameterThroughMiningGoalResolver() {
        Task normalized = TaskPlanner.normalizeMineTask(
            new Task("mine", Map.of("target", "minecraft:diamond_ore", "quantity", 2)),
            recipeResolver
        );

        assertEquals("make_item", normalized.getAction());
        assertEquals("minecraft:diamond", normalized.getStringParameter("item"));
        assertEquals(2, normalized.getIntParameter("quantity", 0));
    }

    @Test
    void normalizesBlockTypeParameterThroughMiningGoalResolver() {
        Task normalized = TaskPlanner.normalizeMineTask(
            new Task("mine", Map.of("blockType", "iron_ore", "count", 3)),
            recipeResolver
        );

        assertEquals("make_item", normalized.getAction());
        assertEquals("minecraft:raw_iron", normalized.getStringParameter("item"));
        assertEquals(3, normalized.getIntParameter("quantity", 0));
    }

    @Test
    void normalizesGoldOreToRawGoldAndGoldIngotToSmeltedGoal() {
        Task rawGold = TaskPlanner.normalizeMineTask(
            new Task("mine", Map.of("target", "gold_ore", "quantity", 4)),
            recipeResolver
        );
        Task goldIngot = TaskPlanner.normalizeMineTask(
            new Task("mine", Map.of("target", "minecraft:gold_ingot", "quantity", 2)),
            recipeResolver
        );

        assertEquals("minecraft:raw_gold", rawGold.getStringParameter("item"));
        assertEquals("minecraft:gold_ingot", goldIngot.getStringParameter("item"));
    }

    @Test
    void invalidMineTaskDoesNotRemainLegacyMineAction() {
        Task missingTarget = TaskPlanner.normalizeMineTask(new Task("mine", Map.of()), recipeResolver);
        Task unknownTarget = TaskPlanner.normalizeMineTask(
            new Task("mine", Map.of("target", "not_a_real_mining_target")),
            recipeResolver
        );

        assertEquals("invalid_mining_target", missingTarget.getAction());
        assertEquals("invalid_mining_target", unknownTarget.getAction());
    }

    @Test
    void makeItemOreBlockTargetsUseMiningGoalResolver() {
        Task diamond = TaskPlanner.normalizeMakeOrCraftTask(
            new Task("make_item", Map.of("item", "minecraft:diamond_ore", "quantity", 1)),
            snapshot,
            recipeResolver
        );
        Task rawGold = TaskPlanner.normalizeMakeOrCraftTask(
            new Task("make_item", Map.of("item", "gold_ore", "quantity", 4)),
            snapshot,
            recipeResolver
        );
        Task goldIngot = TaskPlanner.normalizeMakeOrCraftTask(
            new Task("make_item", Map.of("item", "minecraft:gold_ingot", "quantity", 2)),
            snapshot,
            recipeResolver
        );

        assertEquals("minecraft:diamond", diamond.getStringParameter("item"));
        assertEquals("minecraft:raw_gold", rawGold.getStringParameter("item"));
        assertEquals("minecraft:gold_ingot", goldIngot.getStringParameter("item"));
    }

    @Test
    void gatherMiningTargetsUseMakeItemCraftingTreeEntry() {
        Task rawGold = TaskPlanner.normalizeGatherTask(
            new Task("gather", Map.of("resource", "gold_ore", "quantity", 2)),
            recipeResolver
        );
        Task goldIngot = TaskPlanner.normalizeGatherTask(
            new Task("gather", Map.of("resource", "minecraft:gold_ingot", "quantity", 2)),
            recipeResolver
        );
        Task tree = TaskPlanner.normalizeGatherTask(
            new Task("gather", Map.of("resource", "tree", "quantity", 1)),
            recipeResolver
        );

        assertEquals("make_item", rawGold.getAction());
        assertEquals("minecraft:raw_gold", rawGold.getStringParameter("item"));
        assertEquals(2, rawGold.getIntParameter("quantity", 0));
        assertEquals("make_item", goldIngot.getAction());
        assertEquals("minecraft:gold_ingot", goldIngot.getStringParameter("item"));
        assertEquals("gather", tree.getAction());
    }
}
