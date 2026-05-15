package com.aiplayer.execution;

import com.aiplayer.recipe.MiningToolTier;
import com.aiplayer.recipe.MiningResource;

import java.util.List;
import java.util.Locale;

public final class MiningToolGate {
    private MiningToolGate() {
    }

    public static Result evaluate(String requiredTool, String currentBestTool, int durability, int minDurability) {
        String required = normalize(requiredTool);
        String current = normalize(currentBestTool);
        int requiredTier = MiningToolTier.fromPickaxeItem(required).level();
        int currentTier = MiningToolTier.fromPickaxeItem(current).level();
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

    public static Result evaluate(MiningResource.Profile profile, String currentBestTool, int durability, int minDurability) {
        String requiredTool = profile == null ? null : profile.requiredTool();
        return evaluate(requiredTool, currentBestTool, durability, minDurability);
    }

    public static Result evaluateWithFallback(
        String requiredTool,
        String highestTierTool,
        int highestTierDurability,
        String healthyTool,
        int healthyToolDurability,
        int minDurability
    ) {
        Result highest = evaluate(requiredTool, highestTierTool, highestTierDurability, minDurability);
        Result healthy = evaluate(requiredTool, healthyTool, healthyToolDurability, minDurability);
        if (healthy.ready()) {
            return healthy;
        }
        if (highest.hasRequiredTier() || healthyTool == null || healthyTool.isBlank()) {
            return highest;
        }
        if (healthy.hasRequiredTier()) {
            return healthy;
        }
        return highest.currentTier() >= healthy.currentTier() ? highest : healthy;
    }

    public static Result evaluateWithFallback(
        MiningResource.Profile profile,
        String highestTierTool,
        int highestTierDurability,
        String healthyTool,
        int healthyToolDurability,
        int minDurability
    ) {
        String requiredTool = profile == null ? null : profile.requiredTool();
        return evaluateWithFallback(requiredTool, highestTierTool, highestTierDurability, healthyTool, healthyToolDurability, minDurability);
    }

    public static int pickaxeTier(String itemId) {
        return MiningToolTier.fromPickaxeItem(itemId).level();
    }

    public static int replacementTargetCount(int currentCount) {
        return Math.max(0, currentCount) + 1;
    }

    public static List<String> replacementCandidates(String requiredTool) {
        MiningToolTier requiredTier = MiningToolTier.fromPickaxeItem(normalize(requiredTool));
        return switch (requiredTier) {
            case NONE -> List.of();
            case WOOD -> List.of(
                "minecraft:wooden_pickaxe",
                "minecraft:stone_pickaxe",
                "minecraft:iron_pickaxe",
                "minecraft:diamond_pickaxe"
            );
            case STONE -> List.of(
                "minecraft:stone_pickaxe",
                "minecraft:iron_pickaxe",
                "minecraft:diamond_pickaxe"
            );
            case IRON -> List.of(
                "minecraft:iron_pickaxe",
                "minecraft:diamond_pickaxe"
            );
            case DIAMOND, NETHERITE -> List.of(
                "minecraft:diamond_pickaxe",
                "minecraft:netherite_pickaxe"
            );
        };
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

        public String toStatusText() {
            return "需要工具=" + requiredTool
                + "，当前工具=" + currentBestTool
                + "，等级=" + currentTier + "/" + requiredTier
                + "，耐久=" + durabilityText()
                + "，状态=" + readableReason()
                + "，下一步=" + nextMilestone;
        }

        private String durabilityText() {
            if (durability == Integer.MAX_VALUE) {
                return "无限";
            }
            return durability + "/" + minDurability;
        }

        private String readableReason() {
            return switch (reason) {
                case "ready" -> "就绪";
                case "no_pickaxe_required" -> "无需镐";
                case "missing_required_tool" -> "缺少所需等级工具";
                case "low_tool_durability" -> "工具耐久不足";
                default -> reason;
            };
        }
    }
}
