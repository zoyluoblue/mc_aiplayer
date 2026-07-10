package io.github.zoyluo.aibot.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class IntentControlTransactionTest {
    @Test
    void cancelAllDetachesMissionBeforeCancellingTasks() {
        FakePort port = FakePort.withEverything();

        IntentControlTransaction.Outcome outcome = IntentControlTransaction.cancel(
                port,
                IntentControlTransaction.Scope.ALL,
                true);

        assertEquals(List.of(
                "invalidateDecision",
                "clearWakeSources",
                "detachCurrentMission",
                "clearMissionQueue",
                "clearLongTermGoal",
                "cancelClaimedJob",
                "cancelActiveAndPausedWork",
                "stopActions",
                "publish:ALL"), port.events);
        assertTrue(outcome.changed());
        assertEquals(2, outcome.queuedMissionsCleared());
    }

    @Test
    void cancelCurrentPreservesQueueWithoutSynchronousPromotion() {
        FakePort port = FakePort.withEverything();

        IntentControlTransaction.Outcome outcome = IntentControlTransaction.cancel(
                port,
                IntentControlTransaction.Scope.CURRENT,
                true);

        assertFalse(port.events.contains("clearMissionQueue"));
        assertTrue(port.events.indexOf("detachCurrentMission") < port.events.indexOf("cancelActiveAndPausedWork"));
        assertTrue(outcome.changed());
        assertEquals(0, outcome.queuedMissionsCleared());
        assertEquals(2, port.queuedMissions);
    }

    @Test
    void llmToolPreservesApplyingDecisionDuringCancellation() {
        FakePort port = FakePort.withEverything();

        IntentControlTransaction.Outcome outcome = IntentControlTransaction.cancel(
                port,
                IntentControlTransaction.Scope.CURRENT,
                false);

        assertFalse(port.events.contains("invalidateDecision"));
        assertTrue(outcome.changed());
        assertFalse(outcome.decisionInvalidated());
        assertTrue(outcome.currentMissionDetached());
    }

    @Test
    void repeatedCancelIsIdempotentAndDoesNotRepublish() {
        FakePort port = new FakePort();

        IntentControlTransaction.Outcome outcome = IntentControlTransaction.cancel(
                port,
                IntentControlTransaction.Scope.ALL,
                true);

        assertFalse(outcome.changed());
        assertFalse(port.events.stream().anyMatch(event -> event.startsWith("publish:")));
    }

    private static final class FakePort implements IntentControlTransaction.Port {
        private final List<String> events = new ArrayList<>();
        private boolean decision;
        private boolean wakeSources;
        private boolean currentMission;
        private int queuedMissions;
        private boolean longTermGoal;
        private boolean claimedJob;
        private boolean tasks;
        private boolean actions;

        private static FakePort withEverything() {
            FakePort port = new FakePort();
            port.decision = true;
            port.wakeSources = true;
            port.currentMission = true;
            port.queuedMissions = 2;
            port.longTermGoal = true;
            port.claimedJob = true;
            port.tasks = true;
            port.actions = true;
            return port;
        }

        @Override
        public boolean invalidateDecision() {
            events.add("invalidateDecision");
            return takeDecision();
        }

        @Override
        public boolean clearWakeSources() {
            events.add("clearWakeSources");
            boolean changed = wakeSources;
            wakeSources = false;
            return changed;
        }

        @Override
        public boolean detachCurrentMission() {
            events.add("detachCurrentMission");
            boolean changed = currentMission;
            currentMission = false;
            return changed;
        }

        @Override
        public int clearMissionQueue() {
            events.add("clearMissionQueue");
            int count = queuedMissions;
            queuedMissions = 0;
            return count;
        }

        @Override
        public boolean clearLongTermGoal() {
            events.add("clearLongTermGoal");
            boolean changed = longTermGoal;
            longTermGoal = false;
            return changed;
        }

        @Override
        public boolean cancelClaimedJob() {
            events.add("cancelClaimedJob");
            boolean changed = claimedJob;
            claimedJob = false;
            return changed;
        }

        @Override
        public boolean cancelActiveAndPausedWork() {
            events.add("cancelActiveAndPausedWork");
            boolean changed = tasks;
            tasks = false;
            return changed;
        }

        @Override
        public boolean stopActions() {
            events.add("stopActions");
            boolean changed = actions;
            actions = false;
            return changed;
        }

        @Override
        public void publish(IntentControlTransaction.Outcome outcome) {
            events.add("publish:" + outcome.scope());
        }

        private boolean takeDecision() {
            boolean changed = decision;
            decision = false;
            return changed;
        }
    }
}
