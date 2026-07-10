package io.github.zoyluo.aibot.brain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side state machine that owns the right to apply asynchronous LLM callbacks.
 *
 * <p>Every transition compares the full lease. A stale success, stale error, duplicate callback,
 * or delayed continuation therefore fails closed without changing the current request state.</p>
 */
public final class DecisionSession {
    private final UUID botId;
    private UUID sessionId;
    private long epoch;
    private long requestSequence;
    private DecisionPhase phase = DecisionPhase.IDLE;

    public DecisionSession(UUID botId) {
        this(botId, UUID.randomUUID());
    }

    DecisionSession(UUID botId, UUID sessionId) {
        this.botId = Objects.requireNonNull(botId, "botId");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    /** Starts a latest-wins user or automatic decision intent. */
    public synchronized DecisionLease beginEpoch() {
        if (epoch == Long.MAX_VALUE) {
            // Rotate the non-reusable session identity instead of wrapping into an old epoch.
            sessionId = UUID.randomUUID();
            epoch = 1L;
        } else {
            epoch++;
        }
        requestSequence = 1L;
        phase = DecisionPhase.IN_FLIGHT;
        return currentLease();
    }

    /** Claims a successful callback exactly once before any response side effect is applied. */
    public synchronized boolean tryAcceptResponse(DecisionLease lease) {
        if (phase != DecisionPhase.IN_FLIGHT || !matches(lease)) {
            return false;
        }
        phase = DecisionPhase.APPLYING_RESPONSE;
        return true;
    }

    /** Claims an error callback and returns this request to idle without touching a newer request. */
    public synchronized boolean tryAcceptError(DecisionLease lease) {
        if (phase != DecisionPhase.IN_FLIGHT || !matches(lease)) {
            return false;
        }
        phase = DecisionPhase.IDLE;
        return true;
    }

    /** Marks an accepted tool-calling response as waiting for its deterministic work to settle. */
    public synchronized boolean awaitContinuation(DecisionLease lease) {
        if (phase != DecisionPhase.APPLYING_RESPONSE || !matches(lease)) {
            return false;
        }
        phase = DecisionPhase.WAITING_CONTINUATION;
        return true;
    }

    /** Issues the next HTTP request in the same intent after a waiting continuation. */
    public synchronized Optional<DecisionLease> advanceContinuation(DecisionLease lease) {
        if (phase != DecisionPhase.WAITING_CONTINUATION || !matches(lease)) {
            return Optional.empty();
        }
        if (requestSequence == Long.MAX_VALUE) {
            // There is no safe sequence value to issue. Stop the intent rather than wrap.
            phase = DecisionPhase.IDLE;
            return Optional.empty();
        }
        requestSequence++;
        phase = DecisionPhase.IN_FLIGHT;
        return Optional.of(currentLease());
    }

    /** Completes an accepted response that will not issue another request. */
    public synchronized boolean complete(DecisionLease lease) {
        if (phase != DecisionPhase.APPLYING_RESPONSE || !matches(lease)) {
            return false;
        }
        phase = DecisionPhase.IDLE;
        return true;
    }

    public synchronized boolean isWaiting(DecisionLease lease) {
        return phase == DecisionPhase.WAITING_CONTINUATION && matches(lease);
    }

    public synchronized boolean isApplying(DecisionLease lease) {
        return phase == DecisionPhase.APPLYING_RESPONSE && matches(lease);
    }

    public synchronized boolean busy() {
        return phase != DecisionPhase.IDLE;
    }

    public synchronized DecisionPhase phase() {
        return phase;
    }

    /** Invalidates all outstanding callbacks while preserving the conversation incarnation. */
    public synchronized void invalidate() {
        if (epoch == Long.MAX_VALUE) {
            sessionId = UUID.randomUUID();
            epoch = 0L;
        } else {
            epoch++;
        }
        requestSequence = 0L;
        phase = DecisionPhase.IDLE;
    }

    public synchronized boolean invalidateIfBusy() {
        if (phase == DecisionPhase.IDLE) {
            return false;
        }
        invalidate();
        return true;
    }

    /** Fails a request that could not be submitted after its lease was issued. */
    public synchronized boolean failSubmission(DecisionLease lease) {
        if (phase != DecisionPhase.IN_FLIGHT || !matches(lease)) {
            return false;
        }
        phase = DecisionPhase.IDLE;
        return true;
    }

    private DecisionLease currentLease() {
        return new DecisionLease(botId, sessionId, epoch, requestSequence);
    }

    private boolean matches(DecisionLease lease) {
        return lease != null
                && botId.equals(lease.botId())
                && sessionId.equals(lease.sessionId())
                && epoch == lease.epoch()
                && requestSequence == lease.requestSequence();
    }

    public enum DecisionPhase {
        IDLE,
        IN_FLIGHT,
        APPLYING_RESPONSE,
        WAITING_CONTINUATION
    }
}
