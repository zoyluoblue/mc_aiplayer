package com.aiplayer.agent;

public final class AgentLoopScheduler {
    private static final int DEFAULT_TIME_SLICE_TICKS = 600;
    private static final int DEFAULT_FAILURE_REVIEW_THRESHOLD = 2;

    public LoopDecision decide(LoopState state) {
        if (state == null) {
            return new LoopDecision(true, true, "没有 loop 状态，重新观察并规划");
        }
        if (state.playerChangedTask()) {
            return new LoopDecision(true, true, "玩家修改了任务");
        }
        if (state.stepCompleted() || state.inventoryChanged() || state.chestChanged()) {
            return new LoopDecision(true, false, "状态变化，需要重新观察");
        }
        if (state.targetStuck() || state.resourceBudgetExhausted()) {
            return new LoopDecision(true, true, "执行失败或资源搜索预算耗尽");
        }
        if (state.nearbyDanger()) {
            return new LoopDecision(true, true, "附近存在危险");
        }
        if (state.ticksSinceObservation() >= DEFAULT_TIME_SLICE_TICKS) {
            return new LoopDecision(true, false, "时间片结束，重新观察");
        }
        if (state.consecutiveFailures() >= DEFAULT_FAILURE_REVIEW_THRESHOLD) {
            return new LoopDecision(true, true, "连续失败，进入复盘");
        }
        return new LoopDecision(false, false, "继续当前 step");
    }

    public record LoopState(
        boolean stepCompleted,
        boolean inventoryChanged,
        boolean chestChanged,
        boolean targetStuck,
        boolean resourceBudgetExhausted,
        boolean nearbyDanger,
        boolean playerChangedTask,
        int consecutiveFailures,
        int ticksSinceObservation
    ) {
    }

    public record LoopDecision(boolean observe, boolean reviewWithDeepSeek, String reason) {
    }
}
