package io.github.zoyluo.aibot.brain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one concrete LLM request within a bot decision session.
 *
 * <p>The bot id alone is not an incarnation identity: offline player UUIDs are deterministic and
 * can be reused after a despawn. The session id prevents a response from an old bot incarnation
 * from matching a newly created conversation.</p>
 */
public record DecisionLease(UUID botId, UUID sessionId, long epoch, long requestSequence) {
    public DecisionLease {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(sessionId, "sessionId");
        if (epoch <= 0L) {
            throw new IllegalArgumentException("epoch must be positive");
        }
        if (requestSequence <= 0L) {
            throw new IllegalArgumentException("requestSequence must be positive");
        }
    }
}
