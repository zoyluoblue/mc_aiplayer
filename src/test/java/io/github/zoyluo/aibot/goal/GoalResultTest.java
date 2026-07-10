package io.github.zoyluo.aibot.goal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoalResultTest {
    @Test
    void completedRequiresSatisfiedPredicate() {
        GoalEvaluation satisfied = GoalEvaluation.count(4, 4, Map.of(), "");
        GoalEvaluation partial = GoalEvaluation.count(2, 4, Map.of(), "missing");
        GoalEvaluation failed = GoalEvaluation.count(0, 4, Map.of(), "missing");
        GoalEvaluation unknown = GoalEvaluation.unknown("no_binding");

        assertEquals(GoalResult.Status.COMPLETED, GoalResult.classify(satisfied, false));
        assertEquals(GoalResult.Status.PARTIAL, GoalResult.classify(partial, false));
        assertEquals(GoalResult.Status.FAILED, GoalResult.classify(failed, false));
        assertEquals(GoalResult.Status.FAILED, GoalResult.classify(unknown, false));
    }

    @Test
    void cancellationAlwaysWinsOverFactsAndSkippedStepsCannotCreateCompletion() {
        GoalEvaluation satisfied = GoalEvaluation.count(4, 4, Map.of(), "");
        assertEquals(GoalResult.Status.CANCELLED, GoalResult.classify(satisfied, true));

        GoalEvaluation unmet = new GoalEvaluation(GoalEvaluation.State.UNSATISFIED, 3, 4,
                Map.of("skipped", "true"), List.of("missing_final_item"));
        assertEquals(GoalResult.Status.PARTIAL, GoalResult.classify(unmet, false));
    }
}
