package com.aiplayer.execution;

import com.aiplayer.recipe.MiningResource;

public final class MiningHeightPolicy {
    public static final int DEFAULT_TARGET_Y = 0;

    private MiningHeightPolicy() {
    }

    public static Decision decide(int currentY, MiningResource.Profile profile) {
        if (profile == null) {
            return new Decision(
                DEFAULT_TARGET_Y,
                DEFAULT_TARGET_Y,
                DEFAULT_TARGET_Y,
                "0..0(default)",
                "default_y0_no_resource",
                "any",
                currentY == DEFAULT_TARGET_Y
            );
        }
        MiningResource.HeightRange primary = profile.primaryRange();
        if (primary != null) {
            if (primary.contains(currentY)) {
                return decision(currentY, primary, "current_in_primary_range", profile, true);
            }
            if (profile.surfaceAllowed() && profile.fallbackRange() != null && profile.fallbackRange().contains(currentY)) {
                return decision(currentY, profile.fallbackRange(), "current_in_surface_fallback_range", profile, true);
            }
            return decision(midpoint(primary.minY(), primary.maxY()), primary, "target_primary_midpoint", profile, false);
        }
        if (profile.hasPreferredYRange()) {
            int minY = profile.preferredMinY();
            int maxY = profile.preferredMaxY();
            if (currentY >= minY && currentY <= maxY) {
                return new Decision(currentY, minY, maxY, minY + ".." + maxY + "(preferred)", "current_in_preferred_range", profile.key(), true);
            }
            return new Decision(midpoint(minY, maxY), minY, maxY, minY + ".." + maxY + "(preferred)", "target_preferred_midpoint", profile.key(), false);
        }
        return new Decision(currentY, Integer.MIN_VALUE, Integer.MAX_VALUE, "any", "current_y_unbounded", profile.key(), true);
    }

    public static int nextLayerTarget(int currentY, MiningResource.Profile profile, int shiftIndex, int shiftBlocks) {
        if (profile == null) {
            return DEFAULT_TARGET_Y;
        }
        HeightBand band = targetBandFor(currentY, profile);
        int minY = band.minY();
        int maxY = band.maxY();
        int step = Math.max(1, shiftBlocks);
        int shift = Math.max(1, shiftIndex);
        if (currentY > maxY) {
            return midpoint(minY, maxY);
        }
        if (currentY < minY) {
            return minY;
        }
        int next = currentY - (step * shift);
        return clamp(next, minY, maxY);
    }

    private static HeightBand targetBandFor(int currentY, MiningResource.Profile profile) {
        MiningResource.HeightRange primary = profile.primaryRange();
        if (primary != null) {
            if (profile.surfaceAllowed() && profile.fallbackRange() != null && profile.fallbackRange().contains(currentY)) {
                return new HeightBand(profile.fallbackRange().minY(), profile.fallbackRange().maxY());
            }
            return new HeightBand(primary.minY(), primary.maxY());
        }
        if (profile.hasPreferredYRange()) {
            return new HeightBand(profile.preferredMinY(), profile.preferredMaxY());
        }
        return new HeightBand(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static Decision decision(
        int targetY,
        MiningResource.HeightRange range,
        String reason,
        MiningResource.Profile profile,
        boolean currentInTargetRange
    ) {
        return new Decision(
            targetY,
            range.minY(),
            range.maxY(),
            range.text(),
            reason,
            profile.key(),
            currentInTargetRange
        );
    }

    private static int midpoint(int min, int max) {
        if (min == Integer.MIN_VALUE || max == Integer.MAX_VALUE) {
            return DEFAULT_TARGET_Y;
        }
        long sum = (long) min + (long) max;
        return (int) (sum / 2L);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HeightBand(int minY, int maxY) {
    }

    public record Decision(
        int targetY,
        int minY,
        int maxY,
        String rangeText,
        String reason,
        String resourceKey,
        boolean currentInTargetRange
    ) {
        public String toLogText() {
            return "resource=" + resourceKey
                + ", targetY=" + targetY
                + ", range=" + rangeText
                + ", reason=" + reason
                + ", currentInTargetRange=" + currentInTargetRange;
        }
    }
}
