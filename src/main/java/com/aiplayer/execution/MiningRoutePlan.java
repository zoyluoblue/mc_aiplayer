package com.aiplayer.execution;

import com.aiplayer.recipe.MiningResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record MiningRoutePlan(
    BlockPos entrance,
    int targetY,
    String primaryRange,
    String fallbackRange,
    String heightReason,
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
        String primaryRange = "any";
        String fallbackRange = "any";
        String routeHint = "local_search";
        MiningHeightPolicy.Decision heightDecision = MiningHeightPolicy.decide(entrance.getY(), profile);
        int targetY = heightDecision.targetY();
        String heightReason = heightDecision.toLogText();
        if (profile != null) {
            primaryRange = profile.primaryRange() == null ? profile.preferredYText() : profile.primaryRange().text();
            fallbackRange = profile.fallbackRange() == null ? profile.preferredYText() : profile.fallbackRange().text();
            routeHint = profile.routeHint();
        }
        return new MiningRoutePlan(
            entrance,
            targetY,
            primaryRange,
            fallbackRange,
            heightReason,
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
            + ", height={" + heightReason + "}"
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
            heightReason + suffix,
            mainDirection,
            branchSegmentLength,
            maxBranchTurns,
            routeHint + suffix
        );
    }
}
