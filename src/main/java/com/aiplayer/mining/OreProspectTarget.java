package com.aiplayer.mining;

import com.aiplayer.recipe.MiningResource;

import java.util.List;

public record OreProspectTarget(
    String key,
    String item,
    String displayName,
    String requiredTool,
    String dimension,
    List<String> blockIds,
    int scanMinY,
    int scanMaxY
) {
    private static final int Y_MARGIN = 8;

    public static OreProspectTarget fromProfile(MiningResource.Profile profile, int levelMinY, int levelMaxY) {
        int scanMin = levelMinY;
        int scanMax = levelMaxY;
        if (profile.hasPreferredYRange()) {
            scanMin = Math.max(levelMinY, Math.min(rangeMin(profile.primaryRange()), rangeMin(profile.fallbackRange())) - Y_MARGIN);
            scanMax = Math.min(levelMaxY, Math.max(rangeMax(profile.primaryRange()), rangeMax(profile.fallbackRange())) + Y_MARGIN);
        }
        return new OreProspectTarget(
            profile.key(),
            profile.item(),
            profile.displayName(),
            profile.requiredTool(),
            profile.dimension(),
            profile.blockIds(),
            scanMin,
            scanMax
        );
    }

    public static OreProspectTarget forBlocks(
        String key,
        String item,
        String displayName,
        String requiredTool,
        String dimension,
        List<String> blockIds,
        int levelMinY,
        int levelMaxY
    ) {
        return new OreProspectTarget(
            key == null || key.isBlank() ? "generic_block" : key,
            item,
            displayName == null || displayName.isBlank() ? item : displayName,
            requiredTool,
            dimension == null || dimension.isBlank() ? "any" : dimension,
            blockIds == null ? List.of() : List.copyOf(blockIds),
            levelMinY,
            levelMaxY
        );
    }

    public boolean allowsDimension(String currentDimension) {
        return "any".equals(dimension) || dimension.equals(currentDimension);
    }

    private static int rangeMin(MiningResource.HeightRange range) {
        return range == null ? Integer.MAX_VALUE : range.minY();
    }

    private static int rangeMax(MiningResource.HeightRange range) {
        return range == null ? Integer.MIN_VALUE : range.maxY();
    }
}
