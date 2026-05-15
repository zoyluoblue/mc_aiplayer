package com.aiplayer.agent;

import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.Optional;

public final class AgentIntentParser {
    private final ItemGoalParser itemGoalParser;

    public AgentIntentParser(RecipeResolver recipeResolver) {
        this.itemGoalParser = new ItemGoalParser(recipeResolver == null ? new RecipeResolver() : recipeResolver);
    }

    public AgentIntent parse(String command, WorldSnapshot snapshot) {
        String raw = command == null ? "" : command;
        String normalized = compact(raw);
        if (normalized.isBlank()) {
            return AgentIntent.simple(AgentIntentType.UNKNOWN, raw, "空指令");
        }
        if (containsAny(normalized, "停止", "stop", "取消任务", "别做了")) {
            return AgentIntent.simple(AgentIntentType.STOP, raw, "玩家要求停止当前任务");
        }
        if (containsAny(normalized, "召回", "recall", "回来", "回到我身边")) {
            return AgentIntent.simple(AgentIntentType.RECALL, raw, "玩家要求立即召回 AI");
        }
        if (containsAny(normalized, "背包", "inventory", "backpack")) {
            return AgentIntent.simple(AgentIntentType.SHOW_BACKPACK, raw, "玩家要求查看背包");
        }
        if (isSingleTreeCommand(normalized)) {
            return AgentIntent.gather("tree", itemGoalParser.extractQuantity(raw), raw, "玩家要求采集树木");
        }

        Optional<ItemGoalParser.ItemGoal> itemGoal = itemGoalParser.parsePrimary(raw, snapshot);
        if (itemGoal.isPresent() && !isStructureIntent(normalized)) {
            String item = itemGoal.get().itemId();
            int quantity = itemGoal.get().quantity();
            if ("minecraft:water_bucket".equals(item)) {
                return new AgentIntent(AgentIntentType.FILL_WATER, item, "water_source", null, null, quantity, java.util.List.of(), raw, "玩家要求打一桶水");
            }
            if (hasCookingVerb(normalized) || "minecraft:cooked_beef".equals(item)) {
                return new AgentIntent(AgentIntentType.SMELT_ITEM, item, null, null, null, quantity, java.util.List.of(), raw, "玩家要求烧炼或烹饪物品");
            }
            return AgentIntent.makeItem(item, quantity, raw, "玩家要求获得或制作物品");
        }

        if (looksLikeChat(normalized)) {
            return AgentIntent.simple(AgentIntentType.CHAT, raw, "普通聊天");
        }
        return AgentIntent.simple(AgentIntentType.UNKNOWN, raw, "无法确定玩家意图");
    }

    public String detectMakeItemTarget(String command, WorldSnapshot snapshot) {
        AgentIntent intent = parse(command, snapshot);
        if (intent.intentType() == AgentIntentType.MAKE_ITEM
            || intent.intentType() == AgentIntentType.SMELT_ITEM
            || intent.intentType() == AgentIntentType.FILL_WATER) {
            return intent.targetItem();
        }
        return null;
    }

    private boolean hasCookingVerb(String normalized) {
        return containsAny(normalized, "烧", "烤", "煮", "烹饪", "cook", "smelt", "grill");
    }

    private boolean isStructureIntent(String normalized) {
        return containsAny(normalized, "房", "屋", "小木屋", "house", "buildhouse");
    }

    private boolean isSingleTreeCommand(String normalized) {
        boolean asksTree = containsAny(normalized, "一棵树", "1棵树", "一颗树", "砍树", "砍一棵", "chopatree", "cutatree");
        boolean asksBuilding = containsAny(normalized, "建", "房", "屋", "build", "house");
        return asksTree && !asksBuilding;
    }

    private boolean looksLikeChat(String normalized) {
        return containsAny(normalized, "你好", "在吗", "谢谢", "你是谁", "hello", "hi", "thanks");
    }

    static boolean containsAny(String text, String... patterns) {
        return ItemGoalParser.containsAny(text, patterns);
    }

    static String compact(String text) {
        return ItemGoalParser.compact(text);
    }
}
