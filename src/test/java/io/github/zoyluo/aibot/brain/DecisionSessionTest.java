package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DecisionSessionTest {
    private static final UUID BOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void newerIntentRejectsLateSuccessFromPreviousIntent() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease first = session.beginEpoch();
        DecisionLease second = session.beginEpoch();

        assertFalse(session.tryAcceptResponse(first));
        assertTrue(session.tryAcceptResponse(second));
        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(first.epoch() + 1, second.epoch());
        assertEquals(1L, second.requestSequence());
    }

    @Test
    void lateErrorCannotClearBusyStateOfNewerIntent() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease first = session.beginEpoch();
        DecisionLease second = session.beginEpoch();

        assertFalse(session.tryAcceptError(first));
        assertTrue(session.busy());
        assertTrue(session.tryAcceptError(second));
        assertFalse(session.busy());
    }

    @Test
    void continuationKeepsEpochAndAdvancesRequestSequence() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease firstRequest = session.beginEpoch();

        assertTrue(session.tryAcceptResponse(firstRequest));
        assertTrue(session.awaitContinuation(firstRequest));

        Optional<DecisionLease> advanced = session.advanceContinuation(firstRequest);
        assertTrue(advanced.isPresent());
        DecisionLease secondRequest = advanced.orElseThrow();
        assertEquals(firstRequest.sessionId(), secondRequest.sessionId());
        assertEquals(firstRequest.epoch(), secondRequest.epoch());
        assertEquals(firstRequest.requestSequence() + 1, secondRequest.requestSequence());
        assertFalse(session.tryAcceptResponse(firstRequest));
        assertTrue(session.tryAcceptResponse(secondRequest));
    }

    @Test
    void staleContinuationTimerCannotAdvanceAfterNewIntent() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease first = session.beginEpoch();
        assertTrue(session.tryAcceptResponse(first));
        assertTrue(session.awaitContinuation(first));

        DecisionLease replacement = session.beginEpoch();

        assertTrue(session.advanceContinuation(first).isEmpty());
        assertTrue(session.tryAcceptResponse(replacement));
    }

    @Test
    void invalidationRejectsInflightAndWaitingCallbacks() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease inflight = session.beginEpoch();
        session.invalidate();

        assertFalse(session.tryAcceptResponse(inflight));
        assertFalse(session.tryAcceptError(inflight));
        assertFalse(session.busy());

        DecisionLease waiting = session.beginEpoch();
        assertTrue(session.tryAcceptResponse(waiting));
        assertTrue(session.awaitContinuation(waiting));
        session.invalidate();

        assertFalse(session.isWaiting(waiting));
        assertTrue(session.advanceContinuation(waiting).isEmpty());
        assertFalse(session.busy());
    }

    @Test
    void invalidationRejectsCallbackWhileResponseIsBeingApplied() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease applying = session.beginEpoch();
        assertTrue(session.tryAcceptResponse(applying));

        session.invalidate();

        assertFalse(session.complete(applying));
        assertFalse(session.awaitContinuation(applying));
        assertFalse(session.busy());
    }

    @Test
    void recreatedConversationUsesDifferentSessionIdentityForSameBotUuid() {
        DecisionSession oldSession = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease oldLease = oldSession.beginEpoch();
        DecisionSession replacement = new DecisionSession(
                BOT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000003"));
        DecisionLease replacementLease = replacement.beginEpoch();

        assertNotEquals(oldLease.sessionId(), replacementLease.sessionId());
        assertFalse(replacement.tryAcceptResponse(oldLease));
        assertTrue(replacement.tryAcceptResponse(replacementLease));
    }

    @Test
    void leaseForAnotherBotCannotMatchEvenWithSameSessionAndCounters() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease valid = session.beginEpoch();
        DecisionLease otherBot = new DecisionLease(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                valid.sessionId(),
                valid.epoch(),
                valid.requestSequence());

        assertFalse(session.tryAcceptResponse(otherBot));
        assertTrue(session.tryAcceptResponse(valid));
    }

    @Test
    void responseCanOnlyBeClaimedOnce() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease lease = session.beginEpoch();

        assertTrue(session.tryAcceptResponse(lease));
        assertTrue(session.isApplying(lease));
        assertFalse(session.tryAcceptResponse(lease));
        assertTrue(session.complete(lease));
        assertFalse(session.complete(lease));
        assertFalse(session.busy());
    }

    @Test
    void conditionalInvalidationOnlyChangesAnActiveDecision() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        assertFalse(session.invalidateIfBusy());
        DecisionLease active = session.beginEpoch();

        assertTrue(session.invalidateIfBusy());
        assertFalse(session.busy());
        assertFalse(session.tryAcceptResponse(active));
        assertFalse(session.invalidateIfBusy());
    }

    @Test
    void failedSubmissionReturnsOnlyItsOwnInflightLeaseToIdle() {
        DecisionSession session = new DecisionSession(BOT_ID, SESSION_ID);
        DecisionLease stale = session.beginEpoch();
        DecisionLease current = session.beginEpoch();

        assertFalse(session.failSubmission(stale));
        assertTrue(session.busy());
        assertTrue(session.failSubmission(current));
        assertFalse(session.busy());
    }
}
