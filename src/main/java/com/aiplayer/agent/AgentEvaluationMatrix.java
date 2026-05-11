package com.aiplayer.agent;

import java.util.List;

public final class AgentEvaluationMatrix {
    private AgentEvaluationMatrix() {
    }

    public static List<AgentEvaluationCase> defaultCases() {
        return List.of(
            new AgentEvaluationCase("iron-shovel", "/ai say 给我一把铁铲", AgentIntentType.MAKE_ITEM, "minecraft:iron_shovel", List.of("recipe", "ore", "smelt")),
            new AgentEvaluationCase("iron-axe", "/ai say 帮我做一把铁斧头", AgentIntentType.MAKE_ITEM, "minecraft:iron_axe", List.of("recipe", "ore", "smelt")),
            new AgentEvaluationCase("iron-pickaxe", "/ai say 帮我做个铁镐", AgentIntentType.MAKE_ITEM, "minecraft:iron_pickaxe", List.of("recipe", "ore", "smelt")),
            new AgentEvaluationCase("gold-ingot", "/ai say 帮我挖两块金锭", AgentIntentType.MAKE_ITEM, "minecraft:gold_ingot", List.of("recipe", "ore", "smelt")),
            new AgentEvaluationCase("obsidian", "/ai say 帮我挖黑曜石", AgentIntentType.MAKE_ITEM, "minecraft:obsidian", List.of("diamond_pickaxe", "mining")),
            new AgentEvaluationCase("cooked-beef", "/ai say 帮我烧一块牛肉", AgentIntentType.SMELT_ITEM, "minecraft:cooked_beef", List.of("mob", "furnace")),
            new AgentEvaluationCase("water-bucket", "/ai say 帮我打一桶水", AgentIntentType.FILL_WATER, "minecraft:water_bucket", List.of("bucket", "water_source")),
            new AgentEvaluationCase("wood-door", "/ai say 做一个门", AgentIntentType.MAKE_ITEM, "minecraft:oak_door", List.of("wood", "crafting_table")),
            new AgentEvaluationCase("one-tree", "/ai say 去砍一棵树", AgentIntentType.GATHER_RESOURCE, "tree", List.of("gather_tree")),
            new AgentEvaluationCase("stop", "/ai stop", AgentIntentType.STOP, "", List.of("control")),
            new AgentEvaluationCase("recall", "/ai recall", AgentIntentType.RECALL, "", List.of("control")),
            new AgentEvaluationCase("chat", "/ai say 你好", AgentIntentType.CHAT, "", List.of("chat"))
        );
    }
}
