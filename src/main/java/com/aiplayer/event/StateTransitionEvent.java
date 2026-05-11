package com.aiplayer.event;

import com.aiplayer.execution.AgentState;

import java.time.Instant;

public class StateTransitionEvent {

    private final String agentId;
    private final AgentState fromState;
    private final AgentState toState;
    private final String reason;
    private final Instant timestamp;

        public StateTransitionEvent(String agentId, AgentState fromState, AgentState toState, String reason) {
        this.agentId = agentId;
        this.fromState = fromState;
        this.toState = toState;
        this.reason = reason;
        this.timestamp = Instant.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public AgentState getFromState() {
        return fromState;
    }

    public AgentState getToState() {
        return toState;
    }

    public String getReason() {
        return reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("StateTransitionEvent{agent='%s', %s → %s, reason='%s'}",
            agentId, fromState, toState, reason);
    }
}
