package com.aiplayer.agent;

import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemGoalParserTest {
    private final ItemGoalParser parser = new ItemGoalParser(new RecipeResolver());
    private final WorldSnapshot snapshot = WorldSnapshot.empty("");

    @Test
    void parsesChineseItemGoalsAndQuantities() {
        assertGoal("帮我两块金锭", "minecraft:gold_ingot", 2);
        assertGoal("帮我做两个铁镐", "minecraft:iron_pickaxe", 2);
        assertGoal("挖三颗钻石", "minecraft:diamond", 3);
        assertGoal("帮我2个金锭", "minecraft:gold_ingot", 2);
        assertGoal("金锭两块", "minecraft:gold_ingot", 2);
        assertGoal("收集三块圆石", "minecraft:cobblestone", 3);
    }

    @Test
    void parsesUtilityAndCookingGoals() {
        assertGoal("帮我打一桶水", "minecraft:water_bucket", 1);
        assertGoal("帮我烧一块牛肉", "minecraft:cooked_beef", 1);
    }

    @Test
    void doesNotTreatPlainTreeGatheringAsItemGoal() {
        assertFalse(parser.parsePrimary("去砍树", snapshot).isPresent());
    }

    private void assertGoal(String command, String item, int quantity) {
        ItemGoalParser.ItemGoal goal = parser.parsePrimary(command, snapshot).orElseThrow();
        assertTrue(goal.explicitItemGoal(), command);
        assertEquals(item, goal.itemId(), command);
        assertEquals(quantity, goal.quantity(), command);
    }
}
