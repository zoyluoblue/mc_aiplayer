package com.aiplayer.agent;

import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ItemGoalParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private final RecipeResolver recipeResolver;

    public ItemGoalParser(RecipeResolver recipeResolver) {
        this.recipeResolver = recipeResolver == null ? new RecipeResolver() : recipeResolver;
    }

    public Optional<ItemGoal> parsePrimary(String command, WorldSnapshot snapshot) {
        List<ItemGoal> goals = parseAll(command, snapshot);
        return goals.stream()
            .filter(ItemGoal::explicitItemGoal)
            .max(Comparator.comparingInt(ItemGoal::confidence)
                .thenComparingInt(goal -> goal.matchedText().length()));
    }

    public List<ItemGoal> parseAll(String command, WorldSnapshot snapshot) {
        String raw = command == null ? "" : command;
        String normalized = compact(raw);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<ItemGoal> goals = new ArrayList<>();
        addSpecialGoals(raw, normalized, goals);
        for (Map.Entry<String, String> entry : SurvivalRecipeBook.aliases().entrySet()
            .stream()
            .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
            .toList()) {
            String alias = entry.getKey();
            if (!normalized.contains(alias)) {
                continue;
            }
            String item = normalizeDoorIfNeeded(entry.getValue(), snapshot);
            int quantity = extractQuantity(raw);
            boolean explicit = isExplicitGoal(normalized);
            int confidence = confidence(alias, explicit, quantity);
            goals.add(new ItemGoal(raw, item, quantity, alias, confidence, explicit));
        }
        if (isGenericMiningIntent(normalized)) {
            goals.add(new ItemGoal(raw, "minecraft:raw_iron", extractQuantity(raw), "挖矿", 45, true));
        }
        return deduplicate(goals);
    }

    public int extractQuantity(String command) {
        String raw = command == null ? "" : command;
        Matcher matcher = NUMBER_PATTERN.matcher(raw);
        if (matcher.find()) {
            try {
                return Math.max(1, Math.min(64, Integer.parseInt(matcher.group(1))));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        String normalized = compact(raw);
        if (containsAny(normalized, "十")) return 10;
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

    public static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(compact(pattern))) {
                return true;
            }
        }
        return false;
    }

    public static String compact(String text) {
        return (text == null ? "" : text).toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "");
    }

    private void addSpecialGoals(String raw, String normalized, List<ItemGoal> goals) {
        if (isWaterBucketIntent(normalized)) {
            goals.add(new ItemGoal(raw, "minecraft:water_bucket", extractQuantity(raw), "水桶", 90, true));
        }
        if (hasCookingVerb(normalized) && containsAny(normalized, "牛肉", "beef", "steak")) {
            goals.add(new ItemGoal(raw, "minecraft:cooked_beef", extractQuantity(raw), "牛肉", 90, true));
        }
    }

    private String normalizeDoorIfNeeded(String item, WorldSnapshot snapshot) {
        if (!SurvivalRecipeBook.isGenericWoodenDoor(item)) {
            return item;
        }
        WorldSnapshot safeSnapshot = snapshot == null ? WorldSnapshot.empty("") : snapshot;
        return recipeResolver.chooseWoodenDoorTarget(safeSnapshot);
    }

    private boolean isExplicitGoal(String normalized) {
        return hasCraftingVerb(normalized)
            || isItemAcquisitionIntent(normalized)
            || hasQuantityWord(normalized)
            || isWaterBucketIntent(normalized);
    }

    private boolean hasQuantityWord(String normalized) {
        return NUMBER_PATTERN.matcher(normalized).find()
            || containsAny(normalized, "一", "两", "二", "三", "四", "五", "六", "七", "八", "九", "十");
    }

    private int confidence(String alias, boolean explicit, int quantity) {
        int confidence = alias.length() * 4;
        if (explicit) {
            confidence += 40;
        }
        if (quantity > 1) {
            confidence += 10;
        }
        return confidence;
    }

    private static List<ItemGoal> deduplicate(List<ItemGoal> goals) {
        List<ItemGoal> result = new ArrayList<>();
        for (ItemGoal goal : goals) {
            boolean replaced = false;
            for (int i = 0; i < result.size(); i++) {
                ItemGoal existing = result.get(i);
                if (existing.itemId().equals(goal.itemId())) {
                    if (goal.confidence() > existing.confidence()
                        || (goal.confidence() == existing.confidence()
                        && goal.matchedText().length() > existing.matchedText().length())) {
                        result.set(i, goal);
                    }
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                result.add(goal);
            }
        }
        return result;
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
            "帮我", "给我", "我要", "我想要", "拿", "取", "来一", "来个", "来把", "弄", "搞", "准备",
            "挖", "采", "采集", "收集", "取得", "获取", "找",
            "helpme", "giveme", "getme", "iwant", "bringme");
    }

    private boolean isGenericMiningIntent(String normalized) {
        return containsAny(normalized, "自动挖矿", "去挖矿", "挖一些矿", "挖点矿", "挖矿", "gomining", "mineore");
    }

    public record ItemGoal(
        String rawCommand,
        String itemId,
        int quantity,
        String matchedText,
        int confidence,
        boolean explicitItemGoal
    ) {
        public ItemGoal {
            rawCommand = rawCommand == null ? "" : rawCommand;
            itemId = itemId == null ? "" : itemId;
            quantity = Math.max(1, Math.min(64, quantity));
            matchedText = matchedText == null ? "" : matchedText;
        }
    }
}
