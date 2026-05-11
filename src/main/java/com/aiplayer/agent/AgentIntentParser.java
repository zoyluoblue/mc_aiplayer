package com.aiplayer.agent;

import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AgentIntentParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private final RecipeResolver recipeResolver;

    public AgentIntentParser(RecipeResolver recipeResolver) {
        this.recipeResolver = recipeResolver == null ? new RecipeResolver() : recipeResolver;
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
            return AgentIntent.gather("tree", extractQuantity(raw), raw, "玩家要求采集树木");
        }

        String item = itemFromCommand(normalized, snapshot);
        if (item != null && !isStructureIntent(normalized)) {
            int quantity = extractQuantity(raw);
            if ("minecraft:water_bucket".equals(item)) {
                return new AgentIntent(AgentIntentType.FILL_WATER, item, "water_source", null, null, quantity, java.util.List.of(), raw, "玩家要求打一桶水");
            }
            if (hasCookingVerb(normalized)) {
                return new AgentIntent(AgentIntentType.SMELT_ITEM, item, null, null, null, quantity, java.util.List.of(), raw, "玩家要求烧炼或烹饪物品");
            }
            if (hasCraftingVerb(normalized) || isItemAcquisitionIntent(normalized)) {
                return AgentIntent.makeItem(item, quantity, raw, "玩家要求获得或制作物品");
            }
            return new AgentIntent(AgentIntentType.UNKNOWN, item, null, null, null, quantity, java.util.List.of(), raw, "识别到物品但缺少明确动作");
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

    private String itemFromCommand(String normalized, WorldSnapshot snapshot) {
        if (isWaterBucketIntent(normalized)) {
            return "minecraft:water_bucket";
        }
        if (hasCookingVerb(normalized) && containsAny(normalized, "牛肉", "beef", "steak")) {
            return "minecraft:cooked_beef";
        }
        if (containsAny(normalized, "woodendoor", "door", "木门", "门")) {
            WorldSnapshot safeSnapshot = snapshot == null ? WorldSnapshot.empty("") : snapshot;
            return recipeResolver.chooseWoodenDoorTarget(safeSnapshot);
        }
        for (Map.Entry<String, String> entry : SurvivalRecipeBook.aliases().entrySet()
            .stream()
            .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
            .toList()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (isGenericMiningIntent(normalized)) {
            return "minecraft:raw_iron";
        }
        return null;
    }

    private int extractQuantity(String command) {
        Matcher matcher = NUMBER_PATTERN.matcher(command == null ? "" : command);
        if (matcher.find()) {
            try {
                return Math.max(1, Math.min(64, Integer.parseInt(matcher.group(1))));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        String normalized = compact(command == null ? "" : command);
        if (containsAny(normalized, "九")) return 9;
        if (containsAny(normalized, "八")) return 8;
        if (containsAny(normalized, "七")) return 7;
        if (containsAny(normalized, "六")) return 6;
        if (containsAny(normalized, "五")) return 5;
        if (containsAny(normalized, "四")) return 4;
        if (containsAny(normalized, "三")) return 3;
        if (containsAny(normalized, "两", "二")) return 2;
        return 1;
    }

    private boolean hasCraftingVerb(String normalized) {
        return containsAny(normalized,
            "做", "制作", "制造", "合成", "打造", "烧", "烤", "煮", "烹饪", "打", "装",
            "挖", "采", "采集", "收集", "取得", "获取", "找",
            "craft", "make", "cook", "smelt", "grill", "fill", "造一个", "造一把", "造个");
    }

    private boolean hasCookingVerb(String normalized) {
        return containsAny(normalized, "烧", "烤", "煮", "烹饪", "cook", "smelt", "grill");
    }

    private boolean isWaterBucketIntent(String normalized) {
        return containsAny(normalized, "一桶水", "桶水", "水桶", "waterbucket", "bucketofwater");
    }

    private boolean isItemAcquisitionIntent(String normalized) {
        return containsAny(normalized,
            "给我", "我要", "我想要", "拿", "取", "来一", "来个", "来把", "弄", "搞", "准备",
            "挖", "采", "采集", "收集", "取得", "获取", "找",
            "give me", "get me", "iwant", "bringme");
    }

    private boolean isGenericMiningIntent(String normalized) {
        return containsAny(normalized, "自动挖矿", "去挖矿", "挖一些矿", "挖点矿", "挖矿", "gomining", "mineore");
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
        for (String pattern : patterns) {
            if (text.contains(compact(pattern))) {
                return true;
            }
        }
        return false;
    }

    static String compact(String text) {
        return text.toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "");
    }
}
