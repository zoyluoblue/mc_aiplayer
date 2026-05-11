package com.aiplayer.event;

import java.time.Instant;

public class ActionCompletedEvent {

    private final String agentId;
    private final String actionName;
    private final boolean success;
    private final String message;
    private final long durationMs;
    private final Instant timestamp;

        public ActionCompletedEvent(String agentId, String actionName, boolean success, String message, long durationMs) {
        this.agentId = agentId;
        this.actionName = actionName;
        this.success = success;
        this.message = message;
        this.durationMs = durationMs;
        this.timestamp = Instant.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public String getActionName() {
        return actionName;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("ActionCompletedEvent{agent='%s', action='%s', success=%s, duration=%dms}",
            agentId, actionName, success, durationMs);
    }
}
