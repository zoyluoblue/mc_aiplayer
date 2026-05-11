package com.aiplayer.execution;

public final class ReplanPolicy {
    private static final int MAX_FAILURE_REPLANS = 2;
    private static final int PERIODIC_REPLAN_TICKS = 1200;

    public boolean shouldReplanAfterStep() {
        return true;
    }

    public boolean shouldReplanAfterFailure(TaskSession session) {
        return session.getFailureCount() < MAX_FAILURE_REPLANS;
    }

    public boolean shouldReplanAfterFailureCount(int failureReplans) {
        return failureReplans < MAX_FAILURE_REPLANS;
    }

    public boolean shouldPeriodicReplan(int ticksSinceLastPlan) {
        return ticksSinceLastPlan >= PERIODIC_REPLAN_TICKS;
    }
}
