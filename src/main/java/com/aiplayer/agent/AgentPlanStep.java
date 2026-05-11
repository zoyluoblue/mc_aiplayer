package com.aiplayer.agent;

import java.util.List;
import java.util.Map;

public record AgentPlanStep(
    String stepId,
    String action,
    Map<String, Object> parameters,
    String expectedResult,
    List<String> requiredFacts,
    String failurePolicy
) {
    public AgentPlanStep {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        requiredFacts = requiredFacts == null ? List.of() : List.copyOf(requiredFacts);
    }
}
