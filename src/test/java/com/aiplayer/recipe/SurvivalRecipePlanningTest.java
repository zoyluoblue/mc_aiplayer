package com.aiplayer.recipe;

import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanStep;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurvivalRecipePlanningTest {
    private final RecipeResolver recipeResolver = new RecipeResolver();
    private final WorldSnapshot emptySnapshot = WorldSnapshot.empty("");

    @Test
    void ironShovelExpandsToOreSmeltingAndToolChain() {
        RecipePlan plan = recipeResolver.resolve(null, emptySnapshot, "minecraft:iron_shovel", 1);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertHasCraft(plan, "minecraft:iron_shovel", "crafting_table");
        assertHasCraft(plan, "minecraft:iron_ingot", "furnace");
        assertHasCraft(plan, "minecraft:stone_pickaxe", "crafting_table");
        assertHasCraft(plan, "minecraft:furnace", "crafting_table");
        assertHasGather(plan, "minecraft:raw_iron", "iron_ore");
        assertNoNode(plan, "minecraft:coal");
        assertBefore(plan, "minecraft:stone_pickaxe", "minecraft:furnace");
        assertBefore(plan, "minecraft:stone_pickaxe", "minecraft:raw_iron");
    }

    @Test
    void ironPickaxeUsesOreSourcesNotStorageBlocks() {
        RecipePlan plan = recipeResolver.resolve(null, emptySnapshot, "minecraft:iron_pickaxe", 1);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertHasCraft(plan, "minecraft:iron_pickaxe", "crafting_table");
        assertHasCraft(plan, "minecraft:iron_ingot", "furnace");
        assertHasGather(plan, "minecraft:raw_iron", "iron_ore");
        assertNoNode(plan, "minecraft:coal");
        assertNoNode(plan, "minecraft:raw_iron_block");
        assertNoNode(plan, "minecraft:coal_block");
        assertBefore(plan, "minecraft:stone_pickaxe", "minecraft:furnace");
        assertBefore(plan, "minecraft:stone_pickaxe", "minecraft:raw_iron");
    }

    @Test
    void diamondPickaxeEnsuresIronPickaxeBeforeDiamondMining() {
        RecipePlan plan = recipeResolver.resolve(null, emptySnapshot, "minecraft:diamond_pickaxe", 1);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertHasCraft(plan, "minecraft:iron_pickaxe", "crafting_table");
        assertHasGather(plan, "minecraft:diamond", "diamond_ore");
        assertBefore(plan, "minecraft:iron_pickaxe", "minecraft:diamond");
    }

    @Test
    void oreBlockTargetsNormalizeToSurvivalDropsAtRecipeBoundary() {
        RecipePlan diamondOre = recipeResolver.resolve(null, emptySnapshot, "minecraft:diamond_ore", 1);
        RecipePlan goldOre = recipeResolver.resolve(null, emptySnapshot, "gold_ore", 2);

        assertTrue(diamondOre.isSuccess(), diamondOre.getFailureReason());
        assertEquals("minecraft:diamond", diamondOre.getTarget().getItem());
        assertHasCraft(diamondOre, "minecraft:iron_pickaxe", "crafting_table");
        assertHasGather(diamondOre, "minecraft:diamond", "diamond_ore");
        assertNoNode(diamondOre, "minecraft:diamond_ore");

        assertTrue(goldOre.isSuccess(), goldOre.getFailureReason());
        assertEquals("minecraft:raw_gold", goldOre.getTarget().getItem());
        assertHasCraft(goldOre, "minecraft:iron_pickaxe", "crafting_table");
        assertHasGather(goldOre, "minecraft:raw_gold", "gold_ore");
        assertNoNode(goldOre, "minecraft:gold_ore");
    }

    @Test
    void goldIngotExpandsThroughIronPickaxeRawGoldAndFuel() {
        RecipePlan plan = recipeResolver.resolve(null, emptySnapshot, "minecraft:gold_ingot", 2);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertHasCraft(plan, "minecraft:iron_pickaxe", "crafting_table");
        assertHasGather(plan, "minecraft:raw_gold", "gold_ore");
        assertNoNode(plan, "minecraft:coal");
        assertHasCraft(plan, "minecraft:gold_ingot", "furnace");
        assertBefore(plan, "minecraft:iron_pickaxe", "minecraft:raw_gold");
        assertBefore(plan, "minecraft:raw_gold", "minecraft:gold_ingot");
    }

    @Test
    void advancedMiningResourcesDeclareToolChains() {
        RecipePlan emerald = recipeResolver.resolve(null, emptySnapshot, "minecraft:emerald", 1);
        RecipePlan obsidian = recipeResolver.resolve(null, emptySnapshot, "minecraft:obsidian", 1);
        RecipePlan quartz = recipeResolver.resolve(null, emptySnapshot, "minecraft:quartz", 1);
        RecipePlan ancientDebris = recipeResolver.resolve(null, emptySnapshot, "minecraft:ancient_debris", 1);

        assertTrue(emerald.isSuccess(), emerald.getFailureReason());
        assertHasCraft(emerald, "minecraft:iron_pickaxe", "crafting_table");
        assertHasGather(emerald, "minecraft:emerald", "emerald_ore");
        assertBefore(emerald, "minecraft:iron_pickaxe", "minecraft:emerald");

        assertTrue(obsidian.isSuccess(), obsidian.getFailureReason());
        assertHasCraft(obsidian, "minecraft:diamond_pickaxe", "crafting_table");
        assertHasGather(obsidian, "minecraft:obsidian", "block:minecraft:obsidian");
        assertBefore(obsidian, "minecraft:diamond_pickaxe", "minecraft:obsidian");

        assertTrue(quartz.isSuccess(), quartz.getFailureReason());
        assertHasCraft(quartz, "minecraft:wooden_pickaxe", "crafting_table");
        assertHasGather(quartz, "minecraft:quartz", "block:minecraft:nether_quartz_ore");

        assertTrue(ancientDebris.isSuccess(), ancientDebris.getFailureReason());
        assertHasCraft(ancientDebris, "minecraft:diamond_pickaxe", "crafting_table");
        assertHasGather(ancientDebris, "minecraft:ancient_debris", "block:minecraft:ancient_debris");
    }

    @Test
    void explicitBaseSourcesTakePriorityOverGenericBlockSources() {
        assertTrue(recipeResolver.shouldPreferBaseSource("minecraft:raw_iron"));
        assertTrue(recipeResolver.shouldPreferBaseSource("minecraft:coal"));
        assertTrue(SurvivalRecipeBook.isGenericBlockSource("minecraft:raw_iron_block", "block:minecraft:raw_iron_block"));
        assertTrue(SurvivalRecipeBook.isGenericBlockSource("minecraft:coal_block", "block:minecraft:coal_block"));
        assertFalse(recipeResolver.isReachableBlockInSnapshot(emptySnapshot, "minecraft:raw_iron_block"));
    }

    @Test
    void genericWoodRequirementsIncludeNetherStemsAndPlanks() {
        assertTrue(SurvivalRecipeBook.equivalentMaterialItems("minecraft:oak_log").contains("minecraft:crimson_stem"));
        assertTrue(SurvivalRecipeBook.equivalentMaterialItems("minecraft:oak_log").contains("minecraft:warped_stem"));
        assertTrue(SurvivalRecipeBook.equivalentMaterialItems("minecraft:oak_planks").contains("minecraft:crimson_planks"));
        assertTrue(SurvivalRecipeBook.equivalentMaterialItems("minecraft:oak_planks").contains("minecraft:warped_planks"));
    }

    @Test
    void cookedBeefExpandsToCowFuelAndFurnace() {
        RecipePlan plan = recipeResolver.resolve(null, emptySnapshot, "minecraft:cooked_beef", 1);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertHasCraft(plan, "minecraft:cooked_beef", "furnace");
        assertHasCraft(plan, "minecraft:furnace", "crafting_table");
        assertHasGather(plan, "minecraft:beef", "mob:minecraft:cow");
        assertNoNode(plan, "minecraft:coal");
    }

    @Test
    void smeltingUsesExistingWoodFuelInsteadOfAddingCoalStep() {
        WorldSnapshot snapshot = WorldSnapshot.withAiInventory("", Map.of(
            "minecraft:raw_iron", 3,
            "minecraft:furnace", 1,
            "minecraft:oak_planks", 3
        ));

        RecipePlan plan = recipeResolver.resolve(null, snapshot, "minecraft:iron_ingot", 3);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertHasCraft(plan, "minecraft:iron_ingot", "furnace");
        assertNoNode(plan, "minecraft:coal");
        assertNoNode(plan, "minecraft:oak_planks");
    }

    @Test
    void waterBucketExpandsToBucketAndFillWaterStep() {
        RecipePlan plan = recipeResolver.resolve(null, emptySnapshot, "minecraft:water_bucket", 1);

        assertTrue(plan.isSuccess(), plan.getFailureReason());
        assertHasCraft(plan, "minecraft:bucket", "crafting_table");
        assertHasCraft(plan, "minecraft:water_bucket", "water_source");
        PlanSchema schema = PlanSchema.fromRecipePlan("make_item", plan);
        List<PlanStep> steps = schema.getPlan();

        assertTrue(
            steps.stream().anyMatch(step ->
                "fill_water".equals(step.getStep())
                    && "minecraft:water_bucket".equals(step.getItem())
                    && "water_source".equals(step.getResource())),
            "water_bucket should become a fill_water execution step"
        );
    }

    @Test
    void genericWoodRequirementsAcceptOtherWoodTypes() {
        MissingMaterialResolver materials = MissingMaterialResolver.fromItems(
            Map.of("minecraft:spruce_log", 2, "minecraft:birch_planks", 3),
            Map.of("minecraft:dark_oak_log", 1, "minecraft:acacia_planks", 4)
        );

        MissingMaterialResolver.TakeResult logs = materials.takeForIngredient("minecraft:oak_log", 3);
        MissingMaterialResolver.TakeResult planks = materials.takeForIngredient("minecraft:oak_planks", 5);

        assertEquals(2, logs.fromBackpack());
        assertEquals(1, logs.fromChest());
        assertEquals(0, logs.missing());
        assertEquals(3, planks.fromBackpack());
        assertEquals(2, planks.fromChest());
        assertEquals(0, planks.missing());
    }

    private static void assertHasCraft(RecipePlan plan, String item, String station) {
        assertTrue(
            plan.getRecipeChain().stream().anyMatch(node ->
                "craft".equals(node.getType())
                    && item.equals(node.getOutput().getItem())
                    && station.equals(node.getStation())),
            "Expected craft node for " + item + " at " + station + " in\n" + plan.toUserText()
        );
    }

    private static void assertHasGather(RecipePlan plan, String item, String source) {
        assertTrue(
            plan.getRecipeChain().stream().anyMatch(node ->
                "gather".equals(node.getType())
                    && item.equals(node.getOutput().getItem())
                    && source.equals(node.getSource())),
            "Expected gather node for " + item + " from " + source + " in\n" + plan.toUserText()
        );
    }

    private static void assertNoNode(RecipePlan plan, String item) {
        assertTrue(
            plan.getRecipeChain().stream().noneMatch(node -> item.equals(node.getOutput().getItem())),
            "Did not expect node for " + item + " in\n" + plan.toUserText()
        );
    }

    private static void assertBefore(RecipePlan plan, String firstItem, String secondItem) {
        int firstIndex = indexOf(plan, firstItem);
        int secondIndex = indexOf(plan, secondItem);
        assertTrue(firstIndex >= 0, "Expected node for " + firstItem + " in\n" + plan.toUserText());
        assertTrue(secondIndex >= 0, "Expected node for " + secondItem + " in\n" + plan.toUserText());
        assertTrue(
            firstIndex < secondIndex,
            "Expected " + firstItem + " before " + secondItem + " in\n" + plan.toUserText()
        );
    }

    private static int indexOf(RecipePlan plan, String item) {
        List<RecipeNode> chain = plan.getRecipeChain();
        for (int i = 0; i < chain.size(); i++) {
            if (item.equals(chain.get(i).getOutput().getItem())) {
                return i;
            }
        }
        return -1;
    }
}
