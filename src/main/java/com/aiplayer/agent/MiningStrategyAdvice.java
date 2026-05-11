package com.aiplayer.agent;

import java.util.Locale;

public record MiningStrategyAdvice(
    boolean available,
    boolean accepted,
    String source,
    String strategy,
    String action,
    String message,
    String reason,
    boolean needsRebuild,
    boolean needsUserHelp,
    String rawJson
) {
    public MiningStrategyAdvice {
        source = source == null ? "" : source;
        strategy = strategy == null ? "" : strategy;
        action = action == null ? "" : action;
        message = message == null ? "" : message;
        reason = reason == null ? "" : reason;
        rawJson = rawJson == null ? "" : rawJson;
    }

    public static MiningStrategyAdvice unavailable(String reason) {
        return new MiningStrategyAdvice(false, false, "disabled", "local_continue", "continue_current_step",
            "DeepSeek 挖矿策略不可用，本地继续执行", reason, false, false, "");
    }

    public static MiningStrategyAdvice invalid(String reason, String rawJson) {
        return new MiningStrategyAdvice(true, false, "invalid", "local_continue", "continue_current_step",
            "DeepSeek 挖矿策略无效，本地继续执行", reason, false, false, rawJson);
    }

    public static MiningStrategyAdvice accepted(String strategy, String action, String message, String reason,
                                                boolean needsRebuild, boolean needsUserHelp, String rawJson) {
        return new MiningStrategyAdvice(true, true, "deepseek", strategy, action, message, reason,
            needsRebuild, needsUserHelp, rawJson);
    }

    public boolean switchToStairDescent() {
        return accepted && containsAny(action, "switch_to_stair_descent", "stair_descent", "prospect_lower")
            || accepted && containsAny(strategy, "stair", "downward", "lower", "下挖", "阶梯", "下探");
    }

    public boolean rejectCurrentTarget() {
        return accepted && containsAny(action, "reject_current_target", "try_alternative_target", "switch_target")
            || accepted && containsAny(strategy, "alternative", "switch_target", "换目标", "跳过当前");
    }

    public boolean rebuildPlan() {
        return accepted && (needsRebuild || containsAny(action, "rebuild_plan", "replan", "observe_and_replan"));
    }

    private static boolean containsAny(String value, String... needles) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
