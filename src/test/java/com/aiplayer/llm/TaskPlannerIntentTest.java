package com.aiplayer.llm;

import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskPlannerIntentTest {
    private final RecipeResolver recipeResolver = new RecipeResolver();
    private final WorldSnapshot snapshot = WorldSnapshot.empty("");

    @Test
    void detectsIronShovelRequest() {
        String item = TaskPlanner.detectLocalMakeItemTarget("帮我做一把铁铲", snapshot, recipeResolver);

        assertEquals("minecraft:iron_shovel", item);
    }

    @Test
    void detectsAcquisitionStyleToolRequests() {
        assertEquals("minecraft:iron_shovel",
            TaskPlanner.detectLocalMakeItemTarget("给我一把铁铲", snapshot, recipeResolver));
        assertEquals("minecraft:iron_pickaxe",
            TaskPlanner.detectLocalMakeItemTarget("我要一把铁镐", snapshot, recipeResolver));
        assertEquals("minecraft:gold_ingot",
            TaskPlanner.detectLocalMakeItemTarget("帮我挖两块金锭", snapshot, recipeResolver));
        assertEquals("minecraft:obsidian",
            TaskPlanner.detectLocalMakeItemTarget("帮我挖黑曜石", snapshot, recipeResolver));
        assertEquals("minecraft:raw_iron",
            TaskPlanner.detectLocalMakeItemTarget("自动挖矿", snapshot, recipeResolver));
        assertEquals("minecraft:raw_iron",
            TaskPlanner.detectLocalMakeItemTarget("挖一些矿", snapshot, recipeResolver));
    }

    @Test
    void detectsCookedBeefRequest() {
        String item = TaskPlanner.detectLocalMakeItemTarget("帮我烧一块牛肉", snapshot, recipeResolver);

        assertEquals("minecraft:cooked_beef", item);
    }

    @Test
    void detectsWaterBucketRequest() {
        String item = TaskPlanner.detectLocalMakeItemTarget("帮我打一桶水", snapshot, recipeResolver);

        assertEquals("minecraft:water_bucket", item);
    }
}
