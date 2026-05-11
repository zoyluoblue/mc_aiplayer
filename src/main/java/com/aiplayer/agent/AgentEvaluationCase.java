package com.aiplayer.agent;

import java.util.List;

public record AgentEvaluationCase(
    String id,
    String command,
    AgentIntentType expectedIntent,
    String expectedTarget,
    List<String> expectedCapabilities
) {
    public AgentEvaluationCase {
        expectedCapabilities = expectedCapabilities == null ? List.of() : List.copyOf(expectedCapabilities);
    }
}
