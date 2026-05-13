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

    public static String bindingDecision(BlockPos oldOrePos, BlockPos newOrePos) {
        return binding(oldOrePos, newOrePos).decision();
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

    public record Binding(String decision, boolean rebindRoute, boolean keepCurrentRoute) {
        public Binding {
            decision = decision == null || decision.isBlank() ? "unknown" : decision;
        }
    }
}
