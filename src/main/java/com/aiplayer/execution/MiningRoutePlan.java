package com.aiplayer.execution;

import com.aiplayer.recipe.MiningResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record MiningRoutePlan(
    BlockPos entrance,
    int targetY,
    String primaryRange,
    String fallbackRange,
    Direction mainDirection,
    int branchSegmentLength,
    int maxBranchTurns,
    String routeHint
) {
    public static MiningRoutePlan create(
        BlockPos start,
        MiningResource.Profile profile,
        Direction direction,
        int branchSegmentLength,
        int maxBranchTurns
    ) {
        BlockPos entrance = start == null ? BlockPos.ZERO : start.immutable();
        Direction mainDirection = direction == null ? Direction.NORTH : direction;
        int targetY = entrance.getY();
        String primaryRange = "any";
        String fallbackRange = "any";
        String routeHint = "local_search";
        if (profile != null) {
            primaryRange = profile.primaryRange() == null ? profile.preferredYText() : profile.primaryRange().text();
            fallbackRange = profile.fallbackRange() == null ? profile.preferredYText() : profile.fallbackRange().text();
            routeHint = profile.routeHint();
            if (profile.primaryRange() != null) {
                targetY = midpoint(profile.primaryRange().minY(), profile.primaryRange().maxY());
            } else if (profile.hasPreferredYRange()) {
                targetY = midpoint(profile.preferredMinY(), profile.preferredMaxY());
            }
        }
        return new MiningRoutePlan(
            entrance,
            targetY,
            primaryRange,
            fallbackRange,
            mainDirection,
            branchSegmentLength,
            maxBranchTurns,
            routeHint
        );
    }

    public String toLogText() {
        return "entrance=" + entrance.toShortString()
            + ", targetY=" + targetY
            + ", primary=" + primaryRange
            + ", fallback=" + fallbackRange
            + ", direction=" + mainDirection.getName()
            + ", branchSegment=" + branchSegmentLength
            + ", maxTurns=" + maxBranchTurns
            + ", routeHint=" + routeHint;
    }

    public MiningRoutePlan withTargetY(int newTargetY, String reason) {
        String suffix = reason == null || reason.isBlank() ? "" : "|" + reason;
        return new MiningRoutePlan(
            entrance,
            newTargetY,
            primaryRange,
            fallbackRange,
            mainDirection,
            branchSegmentLength,
            maxBranchTurns,
            routeHint + suffix
        );
    }

    private static int midpoint(int min, int max) {
        long sum = (long) min + (long) max;
        return (int) (sum / 2L);
    }
}
