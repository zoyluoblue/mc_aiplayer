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

    public String toStatusText() {
        return "入口=" + entrance.toShortString()
            + "，目标Y=" + targetY
            + "，主要矿层=" + readableRange(primaryRange)
            + "，备用矿层=" + readableRange(fallbackRange)
            + "，高度策略=" + MiningStatusText.heightReason(heightReason)
            + "，方向=" + MiningStatusText.direction(mainDirection)
            + "，路线=" + MiningStatusText.routeHint(routeHint)
            + "，分支段=" + branchSegmentLength
            + "，最多转向=" + maxBranchTurns;
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

    public MiningRoutePlan withMainDirection(Direction newMainDirection, String reason) {
        Direction direction = newMainDirection == null ? mainDirection : newMainDirection;
        String suffix = reason == null || reason.isBlank() ? "" : "|" + reason;
        return new MiningRoutePlan(
            entrance,
            targetY,
            primaryRange,
            fallbackRange,
            heightReason,
            direction,
            branchSegmentLength,
            maxBranchTurns,
            routeHint + suffix
        );
    }

    private static String readableRange(String range) {
        if (range == null || range.isBlank() || "any".equals(range)) {
            return "不限";
        }
        int strategyStart = range.indexOf('(');
        if (strategyStart > 0) {
            return range.substring(0, strategyStart);
        }
        return MiningStatusText.code(range);
    }
}
