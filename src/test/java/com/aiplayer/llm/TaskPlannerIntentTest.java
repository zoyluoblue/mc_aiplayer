package com.aiplayer.llm;

import com.aiplayer.action.Task;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void locksExplicitItemGoalBeforeDeepSeekFallback() {
        Task task = TaskPlanner.lockedItemGoalTaskForTest("帮我两块金锭", snapshot, recipeResolver, "task-test");

        assertEquals("make_item", task.getAction());
        assertEquals("minecraft:gold_ingot", task.getStringParameter("item"));
        assertEquals(2, task.getIntParameter("quantity", 1));
        assertEquals("minecraft:gold_ingot x2", task.getStringParameter("locked_item_goal"));
        assertEquals("local_item_goal", task.getStringParameter("intent_source"));
    }

    @Test
    void leavesTreeGatheringOutOfItemGoalLock() {
        Task task = TaskPlanner.lockedItemGoalTaskForTest("去砍树", snapshot, recipeResolver, "task-test");

        assertNull(task);
    }

    @Test
    void repairsDeepSeekPlanThatConflictsWithLockedItemGoal() {
        ResponseParser.ParsedResponse wrongPlan = new ResponseParser.ParsedResponse(
            "模型误判为砍树",
            "砍一棵树",
            List.of(new Task("gather", Map.of("resource", "tree", "quantity", 1)))
        );

        ResponseParser.ParsedResponse repaired = TaskPlanner.repairLockedItemGoalForTest(
            "帮我两块金锭",
            wrongPlan,
            snapshot,
            recipeResolver,
            "task-test"
        );

        Task task = repaired.getTasks().getFirst();
        assertEquals("make_item", task.getAction());
        assertEquals("minecraft:gold_ingot", task.getStringParameter("item"));
        assertEquals(2, task.getIntParameter("quantity", 1));
        assertEquals(true, task.getParameter("plan_repaired"));
        assertEquals("砍一棵树", task.getStringParameter("original_plan"));
    }

    @Test
    void doesNotRepairNonItemGoalTreePlan() {
        ResponseParser.ParsedResponse treePlan = new ResponseParser.ParsedResponse(
            "玩家要求砍树",
            "砍一棵树",
            List.of(new Task("gather", Map.of("resource", "tree", "quantity", 1)))
        );

        ResponseParser.ParsedResponse result = TaskPlanner.repairLockedItemGoalForTest(
            "去砍树",
            treePlan,
            snapshot,
            recipeResolver,
            "task-test"
        );

        assertEquals(treePlan, result);
    }

    @Test
    void itemGoalEntryRegressionMatrix() {
        List<EntryCase> cases = List.of(
            new EntryCase("帮我两块金锭", "make_item", "minecraft:gold_ingot", 2, true),
            new EntryCase("帮我做两个金锭", "make_item", "minecraft:gold_ingot", 2, true),
            new EntryCase("挖两块金锭", "make_item", "minecraft:gold_ingot", 2, true),
            new EntryCase("给我两个铁锭", "make_item", "minecraft:iron_ingot", 2, true),
            new EntryCase("帮我做铁镐", "make_item", "minecraft:iron_pickaxe", 1, true),
            new EntryCase("收集圆石", "make_item", "minecraft:cobblestone", 1, true),
            new EntryCase("去砍树", null, null, 1, false)
        );

        for (EntryCase entry : cases) {
            Task task = TaskPlanner.lockedItemGoalTaskForTest(entry.command(), snapshot, recipeResolver, "task-test");
            if (!entry.locked()) {
                assertNull(task, entry.command());
                continue;
            }
            assertEquals(entry.action(), task.getAction(), entry.command());
            assertEquals(entry.item(), task.getStringParameter("item"), entry.command());
            assertEquals(entry.quantity(), task.getIntParameter("quantity", 1), entry.command());
            assertEquals("local_item_goal", task.getStringParameter("intent_source"), entry.command());
        }
    }

    private record EntryCase(String command, String action, String item, int quantity, boolean locked) {
    }
}
