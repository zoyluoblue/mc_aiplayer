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

        return Optional.of(new Goal(
            safeInput,
            requestedItem(safeInput, normalizedItem, profile),
            profile.item(),
            profile.item(),
            profile.source(),
            profile.blockIds(),
            false,
            null,
            null,
            profile.requiredTool(),
            profile.requiredToolTier(),
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
            item == null || item.isBlank() ? source : item,
            profile.item(),
            profile.item(),
            profile.source(),
            profile.blockIds(),
            false,
            null,
            null,
            profile.requiredTool(),
            profile.requiredToolTier(),
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
            smeltedProduct.finalItem(),
            profile.item(),
            profile.source(),
            profile.blockIds(),
            true,
            smeltedProduct.rawItem(),
            "minecraft:furnace",
            profile.requiredTool(),
            profile.requiredToolTier(),
            profile.dimension(),
            profile
        ));
    }

    private static String requestedItem(String input, String normalizedItem, MiningResource.Profile profile) {
        if (profile == null) {
            return normalizedItem;
        }
        String normalizedInput = normalize(input);
        for (String blockId : profile.blockIds()) {
            if (normalizedInput.equals(normalize(blockId))) {
                return blockId;
            }
        }
        return profile.item();
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
        String requestedItem,
        String finalItem,
        String directMiningItem,
        String source,
        List<String> blockIds,
        boolean needsSmelting,
        String smeltingInput,
        String station,
        String requiredTool,
        MiningToolTier requiredToolTier,
        String dimension,
        MiningResource.Profile profile
    ) {
        public List<String> sourceBlocks() {
            return blockIds;
        }

        public String profileKey() {
            return profile.key();
        }
    }
}
