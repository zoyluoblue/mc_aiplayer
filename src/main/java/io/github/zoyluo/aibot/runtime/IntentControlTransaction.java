package io.github.zoyluo.aibot.runtime;

import java.util.Objects;

/** Pure ordering contract for an atomic user-intent cancellation. */
public final class IntentControlTransaction {
    private IntentControlTransaction() {
    }

    public static Outcome cancel(Port port,
                                 Scope scope,
                                 boolean invalidateDecision) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(scope, "scope");

        boolean decisionInvalidated = invalidateDecision && port.invalidateDecision();
        boolean wakeSourcesCleared = port.clearWakeSources();

        // Mission ownership must disappear before Task cancellation, otherwise GoalExecutor may
        // observe FAILED/aborted and immediately replan the supposedly cancelled work.
        boolean currentMissionDetached = port.detachCurrentMission();
        int queuedMissionsCleared = scope == Scope.ALL ? Math.max(0, port.clearMissionQueue()) : 0;
        boolean longTermGoalCleared = port.clearLongTermGoal();
        boolean claimedJobCancelled = port.cancelClaimedJob();
        boolean workCancelled = port.cancelActiveAndPausedWork();
        boolean actionsStopped = port.stopActions();

        boolean changed = decisionInvalidated
                || wakeSourcesCleared
                || currentMissionDetached
                || queuedMissionsCleared > 0
                || longTermGoalCleared
                || claimedJobCancelled
                || workCancelled
                || actionsStopped;
        Outcome outcome = new Outcome(
                scope,
                decisionInvalidated,
                wakeSourcesCleared,
                currentMissionDetached,
                queuedMissionsCleared,
                longTermGoalCleared,
                claimedJobCancelled,
                workCancelled,
                actionsStopped,
                changed);
        if (changed) {
            port.publish(outcome);
        }
        return outcome;
    }

    public enum Scope {
        CURRENT,
        ALL
    }

    public interface Port {
        boolean invalidateDecision();

        boolean clearWakeSources();

        boolean detachCurrentMission();

        int clearMissionQueue();

        boolean clearLongTermGoal();

        boolean cancelClaimedJob();

        boolean cancelActiveAndPausedWork();

        boolean stopActions();

        void publish(Outcome outcome);
    }

    public record Outcome(
            Scope scope,
            boolean decisionInvalidated,
            boolean wakeSourcesCleared,
            boolean currentMissionDetached,
            int queuedMissionsCleared,
            boolean longTermGoalCleared,
            boolean claimedJobCancelled,
            boolean workCancelled,
            boolean actionsStopped,
            boolean changed) {
    }
}
