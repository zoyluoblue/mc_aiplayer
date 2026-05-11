package com.aiplayer.recipe;

import java.util.Locale;
import java.util.Map;

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
    private static final Map<String, String> SMELTED_TO_RAW = Map.of(
        "minecraft:iron_ingot", "minecraft:raw_iron",
        "minecraft:gold_ingot", "minecraft:raw_gold",
        "minecraft:copper_ingot", "minecraft:raw_copper"
    );

    public static AutoMiningTarget resolve(String input, int quantity) {
        String safeInput = input == null ? "" : input.trim();
        int safeQuantity = Math.max(1, quantity);
        if (safeInput.isBlank()) {
            return unsupported(safeInput, safeQuantity, null, "挖矿目标不能为空。");
        }

        String normalizedItem = SurvivalRecipeBook.normalizeItemId(safeInput);
        String rawResource = SMELTED_TO_RAW.get(normalizedItem);
        if (rawResource != null) {
            MiningResource.Profile profile = MiningResource.findByItemOrSource(rawResource, null).orElse(null);
            if (profile != null) {
                return supported(safeInput, normalizedItem, safeQuantity, profile,
                    "目标会按生存链先采集 " + profile.item() + "，再熔炼为 " + normalizedItem + "。");
            }
        }

        MiningResource.Profile profile = MiningResource.findByMineTarget(safeInput).orElse(null);
        if (profile == null) {
            return unsupported(safeInput, safeQuantity, null,
                "未知挖矿目标：%s。".formatted(safeInput));
        }

        if (isExplicitOreBlockTarget(safeInput, profile)) {
            return unsupported(safeInput, safeQuantity, profile,
                "暂不支持直接获取矿石方块本体：%s。需要精准采集时请先实现附魔工具链；普通挖矿请改用 %s。"
                    .formatted(safeInput, profile.item()));
        }

        return supported(safeInput, profile.item(), safeQuantity, profile,
            "目标会按普通生存链取得 " + profile.item() + "。");
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

    private static boolean isExplicitOreBlockTarget(String input, MiningResource.Profile profile) {
        String normalizedInput = normalize(input);
        for (String blockId : profile.blockIds()) {
            String normalizedBlock = normalize(blockId);
            if (normalizedInput.equals(normalizedBlock) && blockId.endsWith("_ore")) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
            .replace("minecraft:", "")
            .replace("block:", "")
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "");
    }
}
