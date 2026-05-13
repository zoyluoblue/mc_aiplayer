package com.aiplayer.mining;

import net.minecraft.core.BlockPos;

import java.util.Locale;
import java.util.Set;

public record OreTargetScore(
    OreProspectClassification classification,
    double score,
    int horizontalDistance,
    int verticalDelta,
    boolean basicResource,
    boolean nearField,
    boolean sameOrLowerLayer
) {
    private static final int BASIC_RESOURCE_NEAR_RADIUS = 32;
    private static final Set<String> BASIC_RESOURCE_KEYS = Set.of(
        "coal",
        "raw_copper",
        "raw_iron",
        "stone",
        "block_source"
    );

    public static OreTargetScore calculate(
        OreProspectTarget target,
        BlockPos center,
        BlockPos candidate,
        OreProspectClassification classification
    ) {
        OreProspectClassification safeClassification = classification == null
            ? OreProspectClassification.NOT_FOUND
            : classification;
        int horizontal = Math.abs(candidate.getX() - center.getX()) + Math.abs(candidate.getZ() - center.getZ());
        int vertical = candidate.getY() - center.getY();
        boolean basic = isBasicResource(target);
        boolean near = horizontal <= BASIC_RESOURCE_NEAR_RADIUS;
        boolean sameOrLower = vertical <= 0;

        double value = Math.sqrt(candidate.distSqr(center));
        value += horizontal * 14.0D;
        value += Math.abs(vertical) * 18.0D;
        value += classificationBias(safeClassification);

        if (basic && near) {
            value -= 250.0D;
        }
        if (safeClassification == OreProspectClassification.EMBEDDED_HINT) {
            value += 180.0D;
        }
        if (vertical > 1) {
            value += vertical * 80.0D;
        }
        if (sameOrLower) {
            value -= 25.0D;
        }

        return new OreTargetScore(safeClassification, value, horizontal, vertical, basic, near, sameOrLower);
    }

    public boolean betterThan(OreTargetScore other) {
        if (other == null) {
            return true;
        }
        return score < other.score;
    }

    public String toLogText() {
        return "classification=" + classification
            + ", score=" + String.format(Locale.ROOT, "%.2f", score)
            + ", horizontalDistance=" + horizontalDistance
            + ", verticalDelta=" + verticalDelta
            + ", basicResource=" + basicResource
            + ", nearField=" + nearField
            + ", sameOrLowerLayer=" + sameOrLowerLayer;
    }

    private static boolean isBasicResource(OreProspectTarget target) {
        if (target == null) {
            return false;
        }
        String key = target.key() == null ? "" : target.key();
        String item = target.item() == null ? "" : target.item();
        return BASIC_RESOURCE_KEYS.contains(key)
            || item.equals("minecraft:coal")
            || item.equals("minecraft:raw_copper")
            || item.equals("minecraft:raw_iron")
            || item.equals("minecraft:cobblestone");
    }

    private static double classificationBias(OreProspectClassification classification) {
        return switch (classification) {
            case DIRECT_MINEABLE -> -700.0D;
            case APPROACHABLE_EXPOSED -> -250.0D;
            case EMBEDDED_HINT -> 120.0D;
            case REJECTED -> 10_000.0D;
            case NOT_FOUND -> 20_000.0D;
        };
    }
}
