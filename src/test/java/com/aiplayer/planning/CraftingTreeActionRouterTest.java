package com.aiplayer.planning;

import com.aiplayer.recipe.CraftingTree;
import com.aiplayer.recipe.MaterialRequirement;
import com.aiplayer.recipe.RecipeNode;
import com.aiplayer.recipe.RecipePlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTreeActionRouterTest {
    private final CraftingTreeActionRouter router = new CraftingTreeActionRouter();

    @Test
    void routesTreeStoneOreCraftAndChestStepsToExecutorSteps() {
        RecipePlan plan = RecipePlan.success(
            MaterialRequirement.of("minecraft:gold_ingot", 2),
            List.of(
                RecipeNode.withdraw(MaterialRequirement.of("minecraft:coal", 1), "nearest_chest"),
                RecipeNode.gather(MaterialRequirement.of("minecraft:oak_log", 1), "tree"),
                RecipeNode.gather(MaterialRequirement.of("minecraft:cobblestone", 3), "stone"),
                RecipeNode.gather(MaterialRequirement.of("minecraft:raw_gold", 2), "gold_ore"),
                RecipeNode.craft(
                    MaterialRequirement.of("minecraft:gold_ingot", 2),
                    List.of(MaterialRequirement.of("minecraft:raw_gold", 2), MaterialRequirement.of("minecraft:coal", 1)),
                    "furnace",
                    "熔炼金锭"
                )
            ),
            List.of(),
            Map.of()
        );

        List<CraftingTreeActionRouter.Route> routes = router.route(plan.toCraftingTree());

        assertEquals("withdraw_chest", routes.get(0).executorStep());
        assertEquals("nearest_chest", routes.get(0).from());
        assertEquals("gather_tree", routes.get(1).executorStep());
        assertEquals("gather_stone", routes.get(2).executorStep());
        assertEquals("gather", routes.get(3).executorStep());
        assertEquals("gold_ore", routes.get(3).resource());
        assertEquals("craft_station", routes.get(4).executorStep());
        assertEquals("furnace", routes.get(4).station());
        assertTrue(routes.stream().allMatch(CraftingTreeActionRouter.Route::supported));
    }

    @Test
    void planSchemaUsesCraftingTreeRouter() {
        RecipePlan plan = RecipePlan.success(
            MaterialRequirement.of("minecraft:stone_pickaxe", 1),
            List.of(
                RecipeNode.gather(MaterialRequirement.of("minecraft:cobblestone", 3), "stone"),
                RecipeNode.craft(
                    MaterialRequirement.of("minecraft:stone_pickaxe", 1),
                    List.of(MaterialRequirement.of("minecraft:cobblestone", 3), MaterialRequirement.of("minecraft:stick", 2)),
                    "crafting_table",
                    "石镐"
                )
            ),
            List.of(),
            Map.of()
        );

        PlanSchema schema = PlanSchema.fromRecipePlan("make_item", plan);

        assertEquals(2, schema.getPlan().size());
        assertEquals("gather_stone", schema.getPlan().get(0).getStep());
        assertEquals("craft_station", schema.getPlan().get(1).getStep());
        assertEquals("crafting_table", schema.getPlan().get(1).getStation());
    }

    @Test
    void failedTreeDoesNotPretendToHaveRoutes() {
        CraftingTree failed = RecipePlan.failure(
            MaterialRequirement.of("minecraft:not_real", 1),
            "未知物品 ID：minecraft:not_real",
            Map.of()
        ).toCraftingTree();

        assertFalse(failed.success());
        assertTrue(router.route(failed).isEmpty());
        assertTrue(router.toPlanSteps(failed).isEmpty());
    }

    @Test
    void unsupportedActionFailsFastInsteadOfDroppingStep() {
        CraftingTree.TreeStep unknown = new CraftingTree.TreeStep(
            "action:1:dance:minecraft:gold_ingot",
            1,
            "dance",
            MaterialRequirement.of("minecraft:gold_ingot", 1),
            List.of(),
            null,
            null,
            "未知动作"
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> router.toPlanStep(unknown));

        assertTrue(exception.getMessage().contains("unsupported_action_route:dance"));
    }
}
