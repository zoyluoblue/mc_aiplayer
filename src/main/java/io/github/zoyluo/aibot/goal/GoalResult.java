package io.github.zoyluo.aibot.goal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record GoalResult(
        long sequence,
        UUID missionId,
        Goal goal,
        Status status,
        GoalEvaluation evaluation,
        String reason,
        int startedTick,
        int finishedTick,
        List<SkippedStep> skippedSteps,
        StructureReport structure
) {
    public enum Status {
        COMPLETED,
        PARTIAL,
        FAILED,
        CANCELLED
    }

    public record SkippedStep(String step, String reason) {
        public SkippedStep {
            step = step == null ? "" : step;
            reason = reason == null ? "" : reason;
        }
    }

    public GoalResult {
        Objects.requireNonNull(missionId, "missionId");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evaluation, "evaluation");
        reason = reason == null ? "" : reason;
        skippedSteps = skippedSteps == null ? List.of() : List.copyOf(skippedSteps);
    }

    public static Status classify(GoalEvaluation evaluation, boolean cancelled) {
        if (cancelled) {
            return Status.CANCELLED;
        }
        if (evaluation.state() == GoalEvaluation.State.SATISFIED) {
            return Status.COMPLETED;
        }
        return evaluation.matched() > 0 ? Status.PARTIAL : Status.FAILED;
    }
}
