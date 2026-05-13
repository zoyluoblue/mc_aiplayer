package com.aiplayer.mining;

import net.minecraft.core.BlockPos;

public final class OreProspectSelectionPolicy {
    private OreProspectSelectionPolicy() {
    }

    public static Rejection rejectionFor(OreProspectTarget target, BlockPos center, BlockPos candidate, OreProspectClassification classification) {
        if (target == null || center == null || candidate == null || classification == null) {
            return Rejection.none();
        }
        int verticalDelta = candidate.getY() - center.getY();
        if (isLayerSensitiveEmbeddedTarget(target)
            && classification == OreProspectClassification.EMBEDDED_HINT
            && verticalDelta > 0) {
            return Rejection.reject("embedded_above_current_layer", verticalDelta);
        }
        return Rejection.none();
    }

    private static boolean isLayerSensitiveEmbeddedTarget(OreProspectTarget target) {
        String key = target.key() == null ? "" : target.key();
        String item = target.item() == null ? "" : target.item();
        return key.equals("raw_iron")
            || item.equals("minecraft:raw_iron")
            || key.equals("raw_gold")
            || item.equals("minecraft:raw_gold");
    }

    public record Rejection(boolean rejected, String reason, int verticalDelta) {
        static Rejection none() {
            return new Rejection(false, "none", 0);
        }

        static Rejection reject(String reason, int verticalDelta) {
            return new Rejection(true, reason == null || reason.isBlank() ? "rejected" : reason, verticalDelta);
        }
    }
}
