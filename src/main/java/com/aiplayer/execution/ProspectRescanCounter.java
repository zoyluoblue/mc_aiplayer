package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

public final class ProspectRescanCounter {
    private ProspectRescanCounter() {
    }

    public static String refreshReason(
        int blocksSince,
        int distanceSince,
        int actionsSince,
        int blockInterval,
        int distanceInterval,
        int actionInterval
    ) {
        if (blockInterval > 0 && blocksSince >= blockInterval) {
            return "blocks_since_prospect:" + blocksSince;
        }
        if (distanceInterval > 0 && distanceSince >= distanceInterval) {
            return "distance_since_prospect:" + distanceSince;
        }
        if (actionInterval > 0 && actionsSince >= actionInterval) {
            return "actions_since_prospect:" + actionsSince;
        }
        return null;
    }

    public static String statusText(
        int blocksSince,
        int distanceSince,
        int actionsSince,
        int blockInterval,
        int distanceInterval,
        int actionInterval
    ) {
        return "blocks=" + blocksSince + "/" + blockInterval
            + ", distance=" + distanceSince + "/" + distanceInterval
            + ", actions=" + actionsSince + "/" + actionInterval;
    }

    public static String remainingStatusText(
        int blocksSince,
        int distanceSince,
        int actionsSince,
        int blockInterval,
        int distanceInterval,
        int actionInterval
    ) {
        return "remainingBlocks=" + remaining(blocksSince, blockInterval)
            + ", remainingDistance=" + remaining(distanceSince, distanceInterval)
            + ", remainingActions=" + remaining(actionsSince, actionInterval)
            + ", nextTrigger=" + nextTrigger(blocksSince, distanceSince, actionsSince, blockInterval, distanceInterval, actionInterval);
    }

    public static String bindingDecision(BlockPos oldOrePos, BlockPos newOrePos) {
        return binding(oldOrePos, newOrePos).decision();
    }

    public static String bindingDecision(
        BlockPos oldOrePos,
        BlockPos newOrePos,
        int oldHorizontalDistance,
        int oldVerticalDelta,
        int newHorizontalDistance,
        int newVerticalDelta,
        boolean newExposureValid
    ) {
        return binding(
            oldOrePos,
            newOrePos,
            oldHorizontalDistance,
            oldVerticalDelta,
            newHorizontalDistance,
            newVerticalDelta,
            newExposureValid
        ).decision();
    }

    public static Binding binding(BlockPos oldOrePos, BlockPos newOrePos) {
        if (newOrePos == null) {
            return oldOrePos == null
                ? new Binding("no_candidate", false, false)
                : new Binding("no_candidate_keep_current_route", false, true);
        }
        if (oldOrePos == null) {
            return new Binding("initial_candidate", true, false);
        }
        if (oldOrePos.equals(newOrePos)) {
            return new Binding("same_candidate_keep_current_route", false, true);
        }
        return new Binding("new_candidate_rebind", true, false);
    }

    public static Binding binding(
        BlockPos oldOrePos,
        BlockPos newOrePos,
        int oldHorizontalDistance,
        int oldVerticalDelta,
        int newHorizontalDistance,
        int newVerticalDelta,
        boolean newExposureValid
    ) {
        Binding base = binding(oldOrePos, newOrePos);
        if (!base.rebindRoute()) {
            return base;
        }
        if (!newExposureValid) {
            return new Binding("new_candidate_no_exposure_keep_current_route", false, oldOrePos != null);
        }
        if (oldOrePos == null) {
            return base;
        }
        int oldCost = routeCost(oldHorizontalDistance, oldVerticalDelta);
        int newCost = routeCost(newHorizontalDistance, newVerticalDelta);
        if (newCost <= oldCost) {
            return new Binding("new_candidate_better_route_rebind", true, false);
        }
        if (newCost <= oldCost + 6 && Math.abs(newVerticalDelta) <= Math.abs(oldVerticalDelta) + 2) {
            return new Binding("new_candidate_similar_route_rebind", true, false);
        }
        return new Binding("new_candidate_costlier_keep_current_route", false, true);
    }

    private static int remaining(int value, int interval) {
        if (interval <= 0) {
            return 0;
        }
        return Math.max(0, interval - Math.max(0, value));
    }

    private static int routeCost(int horizontalDistance, int verticalDelta) {
        int horizontal = Math.max(0, horizontalDistance);
        int vertical = Math.abs(verticalDelta);
        return horizontal + vertical * 2;
    }

    private static String nextTrigger(
        int blocksSince,
        int distanceSince,
        int actionsSince,
        int blockInterval,
        int distanceInterval,
        int actionInterval
    ) {
        int blockRemaining = remaining(blocksSince, blockInterval);
        int distanceRemaining = remaining(distanceSince, distanceInterval);
        int actionRemaining = remaining(actionsSince, actionInterval);
        int best = Integer.MAX_VALUE;
        String trigger = "none";
        if (blockInterval > 0 && blockRemaining < best) {
            best = blockRemaining;
            trigger = "blocks";
        }
        if (distanceInterval > 0 && distanceRemaining < best) {
            best = distanceRemaining;
            trigger = "distance";
        }
        if (actionInterval > 0 && actionRemaining < best) {
            trigger = "actions";
        }
        return trigger;
    }

    public record Binding(String decision, boolean rebindRoute, boolean keepCurrentRoute) {
        public Binding {
            decision = decision == null || decision.isBlank() ? "unknown" : decision;
        }
    }
}
