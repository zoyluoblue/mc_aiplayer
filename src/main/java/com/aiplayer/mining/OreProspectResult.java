package com.aiplayer.mining;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.TreeMap;

public record OreProspectResult(
    OreProspectTarget target,
    BlockPos orePos,
    boolean found,
    boolean partial,
    String message,
    int scannedBlocks,
    int scannedChunks,
    int candidates,
    int rejected,
    double distanceSq,
    int verticalDelta,
    OreProspectClassification classification,
    OreTargetScore selectedScore,
    Map<String, Integer> classificationCounts,
    Map<String, Integer> rejectionReasons
) {
    public static OreProspectResult found(
        OreProspectTarget target,
        BlockPos orePos,
        boolean partial,
        String message,
        int scannedBlocks,
        int scannedChunks,
        int candidates,
        int rejected,
        double distanceSq,
        int verticalDelta,
        OreProspectClassification classification,
        OreTargetScore selectedScore,
        Map<String, Integer> classificationCounts,
        Map<String, Integer> rejectionReasons
    ) {
        return new OreProspectResult(
            target,
            orePos == null ? null : orePos.immutable(),
            true,
            partial,
            message,
            scannedBlocks,
            scannedChunks,
            candidates,
            rejected,
            distanceSq,
            verticalDelta,
            classification == null ? OreProspectClassification.EMBEDDED_HINT : classification,
            selectedScore,
            copyMap(classificationCounts),
            copyMap(rejectionReasons)
        );
    }

    public static OreProspectResult found(
        OreProspectTarget target,
        BlockPos orePos,
        boolean partial,
        String message,
        int scannedBlocks,
        int scannedChunks,
        int candidates,
        int rejected,
        double distanceSq,
        int verticalDelta,
        Map<String, Integer> rejectionReasons
    ) {
        return found(
            target,
            orePos,
            partial,
            message,
            scannedBlocks,
            scannedChunks,
            candidates,
            rejected,
            distanceSq,
            verticalDelta,
            OreProspectClassification.EMBEDDED_HINT,
            null,
            Map.of(OreProspectClassification.EMBEDDED_HINT.name(), 1),
            rejectionReasons
        );
    }

    public static OreProspectResult notFound(
        OreProspectTarget target,
        boolean partial,
        String message,
        int scannedBlocks,
        int scannedChunks,
        int candidates,
        int rejected,
        Map<String, Integer> classificationCounts,
        Map<String, Integer> rejectionReasons
    ) {
        return new OreProspectResult(
            target,
            null,
            false,
            partial,
            message,
            scannedBlocks,
            scannedChunks,
            candidates,
            rejected,
            Double.MAX_VALUE,
            0,
            OreProspectClassification.NOT_FOUND,
            null,
            copyMap(classificationCounts),
            copyMap(rejectionReasons)
        );
    }

    public static OreProspectResult notFound(
        OreProspectTarget target,
        boolean partial,
        String message,
        int scannedBlocks,
        int scannedChunks,
        int candidates,
        int rejected,
        Map<String, Integer> rejectionReasons
    ) {
        return notFound(
            target,
            partial,
            message,
            scannedBlocks,
            scannedChunks,
            candidates,
            rejected,
            Map.of(OreProspectClassification.NOT_FOUND.name(), 1),
            rejectionReasons
        );
    }

    public boolean executable() {
        return classification != null && classification.executable();
    }

    private static Map<String, Integer> copyMap(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new TreeMap<>(map));
    }

    public String toLogText() {
        return "target=" + (target == null ? "unknown" : target.displayName())
            + ", found=" + found
            + ", partial=" + partial
            + ", orePos=" + (orePos == null ? "none" : orePos.toShortString())
            + ", classification=" + classification
            + ", selectedScore={" + (selectedScore == null ? "none" : selectedScore.toLogText()) + "}"
            + ", classificationCounts=" + classificationCounts
            + ", scannedBlocks=" + scannedBlocks
            + ", scannedChunks=" + scannedChunks
            + ", candidates=" + candidates
            + ", rejected=" + rejected
            + ", verticalDelta=" + verticalDelta
            + ", rejectionReasons=" + rejectionReasons
            + ", message=" + message;
    }
}
