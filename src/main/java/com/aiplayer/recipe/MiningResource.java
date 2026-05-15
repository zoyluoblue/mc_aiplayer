package com.aiplayer.recipe;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class MiningResource {
    private static final List<Profile> PROFILES = List.of(
        profile("coal", "minecraft:coal", "coal_ore", "煤矿", "minecraft:wooden_pickaxe", true,
            "minecraft:overworld", 0, 128, 64, "surface_cave_or_shallow_stair", false,
            range(0, 128, "overworld_surface_and_cave"),
            range(128, 256, "mountain_coal"),
            true, true, false,
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore"),
        profile("raw_copper", "minecraft:raw_copper", "copper_ore", "铜矿", "minecraft:stone_pickaxe", true,
            "minecraft:overworld", 0, 96, 72, "shallow_cave_or_stair", false,
            range(0, 96, "overworld_copper_band"),
            range(-16, 112, "wide_copper_fallback"),
            false, true, false,
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore"),
        profile("raw_iron", "minecraft:raw_iron", "iron_ore", "铁矿", "minecraft:stone_pickaxe", true,
            "minecraft:overworld", -24, 72, 96, "surface_cave_or_descend_to_mid_depth", false,
            range(-24, 72, "regular_underground_iron"),
            range(80, 232, "mountain_surface_iron"),
            true, true, true,
            "minecraft:iron_ore", "minecraft:deepslate_iron_ore"),
        profile("raw_gold", "minecraft:raw_gold", "gold_ore", "金矿", "minecraft:iron_pickaxe", true,
            "minecraft:overworld", -64, 32, 128, "descend_before_searching_surface_gold", false,
            range(-64, 32, "regular_underground_gold"),
            range(32, 256, "badlands_surface_gold"),
            false, false, true,
            "minecraft:gold_ore", "minecraft:deepslate_gold_ore"),
        profile("diamond", "minecraft:diamond", "diamond_ore", "钻石矿", "minecraft:iron_pickaxe", true,
            "minecraft:overworld", -64, 16, 160, "deep_stair_mining", false,
            range(-64, 16, "deep_diamond_branch_mine"),
            range(-64, 0, "deepslate_cave_diamond"),
            false, false, true,
            "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore"),
        profile("redstone", "minecraft:redstone", "redstone_ore", "红石矿", "minecraft:iron_pickaxe", true,
            "minecraft:overworld", -64, 16, 160, "deep_stair_mining", false,
            range(-64, 16, "deep_redstone_branch_mine"),
            range(-64, 0, "deepslate_cave_redstone"),
            false, false, true,
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore"),
        profile("lapis_lazuli", "minecraft:lapis_lazuli", "lapis_ore", "青金石矿", "minecraft:stone_pickaxe", true,
            "minecraft:overworld", -64, 64, 128, "mid_depth_cave_or_stair", false,
            range(-64, 64, "mid_depth_lapis"),
            range(-32, 32, "lapis_dense_band"),
            false, true, true,
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore"),
        profile("emerald", "minecraft:emerald", "emerald_ore", "绿宝石矿", "minecraft:iron_pickaxe", true,
            "minecraft:overworld", 80, 236, 96, "mountain_biome_surface_or_high_cave", true,
            range(80, 236, "mountain_emerald"),
            range(32, 80, "high_cave_emerald_fallback"),
            true, true, false,
            "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore"),
        profile("quartz", "minecraft:quartz", "block:minecraft:nether_quartz_ore", "下界石英矿", "minecraft:wooden_pickaxe", false,
            "minecraft:the_nether", 10, 118, 0, "nether_surface_scan", true,
            range(10, 118, "nether_open_terrain"),
            range(10, 118, "nether_wall_scan"),
            true, false, false,
            "minecraft:nether_quartz_ore"),
        profile("gold_nugget", "minecraft:gold_nugget", "block:minecraft:nether_gold_ore", "下界金矿", "minecraft:wooden_pickaxe", false,
            "minecraft:the_nether", 10, 118, 0, "nether_surface_scan", true,
            range(10, 118, "nether_gold_open_terrain"),
            range(10, 118, "nether_gold_wall_scan"),
            true, false, false,
            "minecraft:nether_gold_ore"),
        profile("obsidian", "minecraft:obsidian", "block:minecraft:obsidian", "黑曜石", "minecraft:diamond_pickaxe", false,
            "any", Integer.MIN_VALUE, Integer.MAX_VALUE, 0, "existing_obsidian_or_water_lava_area", true,
            range(Integer.MIN_VALUE, Integer.MAX_VALUE, "existing_obsidian"),
            range(Integer.MIN_VALUE, Integer.MAX_VALUE, "water_lava_area"),
            true, false, false,
            "minecraft:obsidian"),
        profile("ancient_debris", "minecraft:ancient_debris", "block:minecraft:ancient_debris", "远古残骸", "minecraft:diamond_pickaxe", true,
            "minecraft:the_nether", 8, 22, 160, "nether_low_stair_mining", true,
            range(8, 22, "nether_low_branch_mine"),
            range(12, 16, "ancient_debris_dense_band"),
            false, false, true,
            "minecraft:ancient_debris")
    );

    private static final Map<String, Profile> BY_ITEM = new HashMap<>();
    private static final Map<String, Profile> BY_SOURCE = new HashMap<>();
    private static final Map<String, Profile> BY_BLOCK = new HashMap<>();
    private static final Map<String, Profile> BY_MINE_TARGET = new HashMap<>();

    static {
        for (Profile profile : PROFILES) {
            BY_ITEM.put(profile.item(), profile);
            BY_SOURCE.put(profile.source(), profile);
            BY_SOURCE.put(profile.source().replace("block:minecraft:", ""), profile);
            BY_MINE_TARGET.put(normalize(profile.key()), profile);
            BY_MINE_TARGET.put(normalize(profile.item()), profile);
            BY_MINE_TARGET.put(normalize(profile.source()), profile);
            BY_MINE_TARGET.put(normalize(profile.displayName()), profile);
            for (String blockId : profile.blockIds()) {
                BY_BLOCK.put(blockId, profile);
                BY_MINE_TARGET.put(normalize(blockId), profile);
                BY_MINE_TARGET.put(normalize(blockId.replace("minecraft:", "")), profile);
                BY_MINE_TARGET.put(normalize(blockId.replace("minecraft:", "").replace("_ore", "")), profile);
            }
        }
        alias("coal", "煤", "煤炭", "煤矿", "煤矿石");
        alias("raw_copper", "copper", "铜", "铜矿", "铜矿石", "粗铜");
        alias("raw_iron", "iron", "铁", "铁矿", "铁矿石", "粗铁", "生铁");
        alias("raw_gold", "gold", "金", "金矿", "金矿石", "粗金");
        alias("diamond", "diamonds", "钻石", "钻石矿", "钻石矿石");
        alias("redstone", "红石", "红石矿", "红石矿石");
        alias("lapis_lazuli", "lapis", "青金石", "青金石矿", "青金石矿石");
        alias("emerald", "绿宝石", "绿宝石矿", "绿宝石矿石");
        alias("quartz", "quartz", "石英", "下界石英", "下界石英矿", "石英矿");
        alias("gold_nugget", "nethergold", "下界金", "下界金矿", "金粒");
        alias("obsidian", "黑曜石");
        alias("ancient_debris", "ancientdebris", "远古残骸");
    }

    private MiningResource() {
    }

    public static List<Profile> profiles() {
        return PROFILES;
    }

    public static Optional<Profile> findByItemOrSource(String item, String source) {
        Profile byItem = item == null ? null : BY_ITEM.get(item);
        if (byItem != null) {
            return Optional.of(byItem);
        }
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        Profile bySource = BY_SOURCE.get(source);
        if (bySource != null) {
            return Optional.of(bySource);
        }
        if (source.startsWith("block:")) {
            return Optional.ofNullable(BY_BLOCK.get(source.substring("block:".length())));
        }
        return Optional.empty();
    }

    public static Optional<Profile> findByMineTarget(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_MINE_TARGET.get(normalize(target)));
    }

    public static Optional<Profile> findByBlockId(String blockId) {
        return Optional.ofNullable(BY_BLOCK.get(blockId));
    }

    private static Profile profile(
        String key,
        String item,
        String source,
        String displayName,
        String requiredTool,
        boolean prospectable,
        String dimension,
        int preferredMinY,
        int preferredMaxY,
        int descentBudget,
        String routeHint,
        boolean specialEnvironment,
        HeightRange primaryRange,
        HeightRange fallbackRange,
        boolean surfaceAllowed,
        boolean cavePreferred,
        boolean branchMinePreferred,
        String... blockIds
    ) {
        return new Profile(
            key,
            item,
            source,
            displayName,
            requiredTool,
            List.of(blockIds),
            prospectable,
            dimension,
            preferredMinY,
            preferredMaxY,
            descentBudget,
            routeHint,
            specialEnvironment,
            primaryRange,
            fallbackRange,
            surfaceAllowed,
            cavePreferred,
            branchMinePreferred
        );
    }

    private static HeightRange range(int minY, int maxY, String strategy) {
        return new HeightRange(minY, maxY, strategy);
    }

    private static void alias(String key, String... values) {
        Profile profile = PROFILES.stream()
            .filter(candidate -> candidate.key().equals(key))
            .findFirst()
            .orElse(null);
        if (profile == null) {
            return;
        }
        for (String value : values) {
            BY_MINE_TARGET.put(normalize(value), profile);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
            .replace("minecraft:", "")
            .replace("block:", "")
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "");
    }

    public record Profile(
        String key,
        String item,
        String source,
        String displayName,
        String requiredTool,
        List<String> blockIds,
        boolean prospectable,
        String dimension,
        int preferredMinY,
        int preferredMaxY,
        int descentBudget,
        String routeHint,
        boolean specialEnvironment,
        HeightRange primaryRange,
        HeightRange fallbackRange,
        boolean surfaceAllowed,
        boolean cavePreferred,
        boolean branchMinePreferred
    ) {
        public boolean allowsDimension(String dimensionId) {
            return "any".equals(dimension) || dimension.equals(dimensionId);
        }

        public boolean hasPreferredYRange() {
            return preferredMinY != Integer.MIN_VALUE || preferredMaxY != Integer.MAX_VALUE;
        }

        public boolean isAbovePreferredY(int y) {
            if (!hasPreferredYRange()) {
                return false;
            }
            if (surfaceAllowed && fallbackRange != null && fallbackRange.contains(y)) {
                return false;
            }
            return y > preferredMaxY;
        }

        public boolean isWithinPreferredY(int y) {
            return !hasPreferredYRange() || (y >= preferredMinY && y <= preferredMaxY);
        }

        public String preferredYText() {
            if (!hasPreferredYRange()) {
                return "any";
            }
            return preferredMinY + ".." + preferredMaxY
                + " primary=" + primaryRange.text()
                + " fallback=" + fallbackRange.text();
        }

        public boolean isWithinPrimaryY(int y) {
            return primaryRange != null && primaryRange.contains(y);
        }

        public boolean isWithinFallbackY(int y) {
            return fallbackRange != null && fallbackRange.contains(y);
        }

        public MiningToolTier requiredToolTier() {
            return MiningToolTier.fromPickaxeItem(requiredTool);
        }

        public boolean requiresPickaxe() {
            return requiredToolTier().requiresPickaxe();
        }

        public boolean isLowLayerBranchMiningResource() {
            return branchMinePreferred && preferredMaxY <= 32;
        }
    }

    public record HeightRange(int minY, int maxY, String strategy) {
        public boolean contains(int y) {
            return y >= minY && y <= maxY;
        }

        public String text() {
            return minY + ".." + maxY + "(" + strategy + ")";
        }
    }
}
