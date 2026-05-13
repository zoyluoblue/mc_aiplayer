package com.aiplayer.recipe;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class MiningGoalResolver {
    private static final Map<String, SmeltedProduct> SMELTED_PRODUCTS = Map.of(
        "minecraft:iron_ingot", new SmeltedProduct("minecraft:raw_iron", "minecraft:iron_ingot"),
        "minecraft:gold_ingot", new SmeltedProduct("minecraft:raw_gold", "minecraft:gold_ingot"),
        "minecraft:copper_ingot", new SmeltedProduct("minecraft:raw_copper", "minecraft:copper_ingot")
    );

    private MiningGoalResolver() {
    }

    public static Optional<Goal> resolve(String input) {
        String safeInput = input == null ? "" : input.trim();
        if (safeInput.isBlank()) {
            return Optional.empty();
        }

        String normalizedItem = SurvivalRecipeBook.normalizeItemId(safeInput);
        SmeltedProduct smeltedProduct = SMELTED_PRODUCTS.get(normalizedItem);
        if (smeltedProduct != null) {
            return goalForSmeltedProduct(safeInput, smeltedProduct);
        }

        MiningResource.Profile profile = MiningResource.findByItemOrSource(normalizedItem, null)
            .or(() -> MiningResource.findByMineTarget(safeInput))
            .orElse(null);
        if (profile == null) {
            return Optional.empty();
        }

        boolean explicitOreBlock = isExplicitOreBlockTarget(safeInput, profile);
        String finalItem = explicitOreBlock ? profile.item() : profile.item();
        return Optional.of(new Goal(
            safeInput,
            finalItem,
            profile.item(),
            profile.source(),
            profile.blockIds(),
            false,
            null,
            profile.requiredTool(),
            profile.dimension(),
            profile
        ));
    }

    public static Optional<Goal> resolveItemOrSource(String item, String source) {
        Optional<Goal> byItem = resolve(item);
        if (byItem.isPresent()) {
            return byItem;
        }
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        MiningResource.Profile profile = MiningResource.findByItemOrSource(item, source)
            .or(() -> MiningResource.findByMineTarget(source))
            .orElse(null);
        if (profile == null) {
            return Optional.empty();
        }
        return Optional.of(new Goal(
            item == null || item.isBlank() ? source : item,
            profile.item(),
            profile.item(),
            profile.source(),
            profile.blockIds(),
            false,
            null,
            profile.requiredTool(),
            profile.dimension(),
            profile
        ));
    }

    private static Optional<Goal> goalForSmeltedProduct(String input, SmeltedProduct smeltedProduct) {
        MiningResource.Profile profile = MiningResource.findByItemOrSource(smeltedProduct.rawItem(), null).orElse(null);
        if (profile == null) {
            return Optional.empty();
        }
        return Optional.of(new Goal(
            input,
            smeltedProduct.finalItem(),
            profile.item(),
            profile.source(),
            profile.blockIds(),
            true,
            "minecraft:furnace",
            profile.requiredTool(),
            profile.dimension(),
            profile
        ));
    }

    public static boolean isExplicitOreBlockTarget(String input, MiningResource.Profile profile) {
        if (profile == null) {
            return false;
        }
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

    private record SmeltedProduct(String rawItem, String finalItem) {
    }

    public record Goal(
        String input,
        String finalItem,
        String directMiningItem,
        String source,
        List<String> blockIds,
        boolean needsSmelting,
        String station,
        String requiredTool,
        String dimension,
        MiningResource.Profile profile
    ) {
        public String profileKey() {
            return profile.key();
        }
    }
}
