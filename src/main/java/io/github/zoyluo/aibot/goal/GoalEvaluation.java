package io.github.zoyluo.aibot.goal;

import java.util.List;
import java.util.Map;

public record GoalEvaluation(
        State state,
        int matched,
        int required,
        Map<String, String> evidence,
        List<String> unmet
) {
    public enum State {
        SATISFIED,
        UNSATISFIED,
        UNKNOWN
    }

    public GoalEvaluation {
        matched = Math.max(0, matched);
        required = Math.max(0, required);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
    }

    public static GoalEvaluation count(int matched, int required, Map<String, String> evidence, String unmet) {
        boolean satisfied = matched >= required;
        return new GoalEvaluation(
                satisfied ? State.SATISFIED : State.UNSATISFIED,
                matched,
                required,
                evidence,
                satisfied || unmet == null || unmet.isBlank() ? List.of() : List.of(unmet));
    }

    public static GoalEvaluation unknown(String reason) {
        return new GoalEvaluation(State.UNKNOWN, 0, 1, Map.of("reason", reason), List.of(reason));
    }
}
