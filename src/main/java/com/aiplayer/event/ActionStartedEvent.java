package com.aiplayer.event;

import java.time.Instant;
import java.util.Map;

public class ActionStartedEvent {

    private final String agentId;
    private final String actionName;
    private final String description;
    private final Map<String, Object> parameters;
    private final Instant timestamp;

        public ActionStartedEvent(String agentId, String actionName, String description, Map<String, Object> parameters) {
        this.agentId = agentId;
        this.actionName = actionName;
        this.description = description;
        this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        this.timestamp = Instant.now();
    }

    public String getAgentId() {
        return agentId;
    }

    public String getActionName() {
        return actionName;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("ActionStartedEvent{agent='%s', action='%s', desc='%s'}",
            agentId, actionName, description);
    }
}
