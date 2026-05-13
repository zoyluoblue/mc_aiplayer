package com.aiplayer.recipe;

public record AutoMiningTarget(
    String input,
    String item,
    int quantity,
    MiningResource.Profile profile,
    String dimension,
    String requiredTool,
    boolean supported,
    String message
) {
    public static AutoMiningTarget resolve(String input, int quantity) {
        String safeInput = input == null ? "" : input.trim();
        int safeQuantity = Math.max(1, quantity);
        if (safeInput.isBlank()) {
            return unsupported(safeInput, safeQuantity, null, "挖矿目标不能为空。");
        }

        MiningGoalResolver.Goal goal = MiningGoalResolver.resolve(safeInput).orElse(null);
        if (goal == null) {
            return unsupported(safeInput, safeQuantity, null,
                "未知挖矿目标：%s。".formatted(safeInput));
        }

        String message = goal.needsSmelting()
            ? "目标会按生存链先采集 " + goal.directMiningItem() + "，再熔炼为 " + goal.finalItem() + "。"
            : "目标会按普通生存链取得 " + goal.finalItem() + "。";
        return supported(safeInput, goal.finalItem(), safeQuantity, goal.profile(), message);
    }

    private static AutoMiningTarget supported(
        String input,
        String item,
        int quantity,
        MiningResource.Profile profile,
        String message
    ) {
        return new AutoMiningTarget(
            input,
            item,
            quantity,
            profile,
            profile.dimension(),
            profile.requiredTool(),
            true,
            message
        );
    }

    private static AutoMiningTarget unsupported(
        String input,
        int quantity,
        MiningResource.Profile profile,
        String message
    ) {
        return new AutoMiningTarget(
            input,
            profile == null ? "minecraft:air" : profile.item(),
            quantity,
            profile,
            profile == null ? "unknown" : profile.dimension(),
            profile == null ? "unknown" : profile.requiredTool(),
            false,
            message
        );
    }

}
