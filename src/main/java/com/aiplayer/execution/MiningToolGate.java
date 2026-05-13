package com.aiplayer.execution;

import java.util.Locale;

public final class MiningToolGate {
    private MiningToolGate() {
    }

    public static Result evaluate(String requiredTool, String currentBestTool, int durability, int minDurability) {
        String required = normalize(requiredTool);
        String current = normalize(currentBestTool);
        int requiredTier = pickaxeTier(required);
        int currentTier = pickaxeTier(current);
        boolean requiredPickaxe = requiredTier > 0;
        boolean hasRequiredTier = !requiredPickaxe || currentTier >= requiredTier;
        boolean enoughDurability = !requiredPickaxe || durability == Integer.MAX_VALUE || durability >= minDurability;
        String reason;
        String nextMilestone;
        if (!requiredPickaxe) {
            reason = "no_pickaxe_required";
            nextMilestone = "mine_resource";
        } else if (!hasRequiredTier) {
            reason = "missing_required_tool";
            nextMilestone = "make_item:" + required;
        } else if (!enoughDurability) {
            reason = "low_tool_durability";
            nextMilestone = "replace_tool:" + required;
        } else {
            reason = "ready";
            nextMilestone = "mine_resource";
        }
        return new Result(required, current, requiredTier, currentTier, durability, minDurability,
            hasRequiredTier, enoughDurability, reason, nextMilestone);
    }

    public static int pickaxeTier(String itemId) {
        String normalized = normalize(itemId);
        return switch (normalized) {
            case "minecraft:wooden_pickaxe", "minecraft:golden_pickaxe" -> 1;
            case "minecraft:stone_pickaxe" -> 2;
            case "minecraft:iron_pickaxe" -> 3;
            case "minecraft:diamond_pickaxe" -> 4;
            case "minecraft:netherite_pickaxe" -> 5;
            default -> 0;
        };
    }

    public static int replacementTargetCount(int currentCount) {
        return Math.max(0, currentCount) + 1;
    }

    private static String normalize(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "minecraft:air";
        }
        String normalized = itemId.toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    public record Result(
        String requiredTool,
        String currentBestTool,
        int requiredTier,
        int currentTier,
        int durability,
        int minDurability,
        boolean hasRequiredTier,
        boolean enoughDurability,
        String reason,
        String nextMilestone
    ) {
        public boolean ready() {
            return hasRequiredTier && enoughDurability;
        }

        public String toLogText() {
            return "requiredTool=" + requiredTool
                + ", currentBestTool=" + currentBestTool
                + ", requiredTier=" + requiredTier
                + ", currentTier=" + currentTier
                + ", durability=" + durability
                + ", minDurability=" + minDurability
                + ", ready=" + ready()
                + ", reason=" + reason
                + ", nextMilestone=" + nextMilestone;
        }
    }
}
