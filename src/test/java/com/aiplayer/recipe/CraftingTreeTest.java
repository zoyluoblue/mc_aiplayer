package com.aiplayer.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTreeTest {
    @Test
    void buildsPrerequisiteActionOutputGraph() {
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
            List.of(MaterialRequirement.of("minecraft:cobblestone", 3)),
            Map.of()
        );

        CraftingTree tree = plan.toCraftingTree();

        assertTrue(tree.success());
        assertEquals("tree:minecraft_stone_pickaxe:x1", tree.graphId());
        assertEquals("minecraft:stone_pickaxe", tree.target().item());
        assertEquals(1, tree.target().count());
        assertEquals("action:1:gather:minecraft:cobblestone", tree.stepByIndex(1).orElseThrow().id());
        assertEquals("minecraft:cobblestone", tree.stepByIndex(1).orElseThrow().output().getItem());
        assertTrue(tree.stepByIndex(99).isEmpty());
        assertTrue(tree.nodes().stream().anyMatch(node -> node.id().equals("source:stone")));
        assertTrue(tree.nodes().stream().anyMatch(node -> node.id().equals("item:minecraft:cobblestone")));
        assertTrue(tree.nodes().stream().anyMatch(node -> node.id().equals("item:minecraft:stone_pickaxe")
            && node.type() == CraftingTree.NodeType.TARGET_ITEM));

        Map<CraftingTree.EdgeType, Long> edgeCounts = tree.edges().stream()
            .collect(Collectors.groupingBy(CraftingTree.Edge::type, Collectors.counting()));
        assertEquals(3L, edgeCounts.get(CraftingTree.EdgeType.REQUIRES));
        assertEquals(2L, edgeCounts.get(CraftingTree.EdgeType.PRODUCES));

        String dependencyText = tree.toDependencyText();
        assertTrue(dependencyText.contains("source:stone -> gather -> minecraft:cobblestone x3"));
        assertTrue(dependencyText.contains("minecraft:stone_pickaxe x1"));
        assertTrue(tree.toMermaid().contains("graph TD"));
        assertTrue(tree.toJson().contains("\"target\""));
    }

    @Test
    void preservesWithdrawChestAsSourceAction() {
        RecipePlan plan = RecipePlan.success(
            MaterialRequirement.of("minecraft:iron_ingot", 1),
            List.of(RecipeNode.withdraw(MaterialRequirement.of("minecraft:iron_ingot", 1), "nearest_chest")),
            List.of(),
            Map.of("minecraft:iron_ingot", 0)
        );

        CraftingTree tree = plan.toCraftingTree();

        assertTrue(tree.nodes().stream().anyMatch(node -> node.id().equals("source:nearest_chest")));
        assertTrue(tree.edges().stream().anyMatch(edge -> edge.action().equals("withdraw_chest")
            && edge.type() == CraftingTree.EdgeType.REQUIRES));
        assertTrue(tree.toDependencyText().contains("source:nearest_chest -> withdraw_chest -> minecraft:iron_ingot x1"));
    }

    @Test
    void exposesFailedPlanWithoutPretendingAvailable() {
        RecipePlan plan = RecipePlan.failure(
            MaterialRequirement.of("minecraft:not_real", 1),
            "未知物品 ID：minecraft:not_real",
            Map.of()
        );

        CraftingTree tree = plan.toCraftingTree();

        assertFalse(tree.success());
        assertEquals("未知物品 ID：minecraft:not_real", tree.failureReason());
        assertTrue(tree.toDependencyText().contains("配方解析失败"));
        assertFalse(tree.toDependencyText().contains("already_available"));
        assertTrue(tree.toJson().contains("\"success\": false"));
    }
}
