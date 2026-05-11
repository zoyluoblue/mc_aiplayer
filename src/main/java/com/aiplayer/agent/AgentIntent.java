package com.aiplayer.agent;

import java.util.List;

public record AgentIntent(
    AgentIntentType intentType,
    String targetItem,
    String targetResource,
    String targetEntity,
    String targetBlock,
    int quantity,
    List<String> constraints,
    String rawText,
    String reason
) {
    public AgentIntent {
        quantity = Math.max(1, quantity);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        rawText = rawText == null ? "" : rawText;
        reason = reason == null ? "" : reason;
    }

    public static AgentIntent makeItem(String item, int quantity, String rawText, String reason) {
        return new AgentIntent(AgentIntentType.MAKE_ITEM, item, null, null, null, quantity, List.of(), rawText, reason);
    }

    public static AgentIntent gather(String resource, int quantity, String rawText, String reason) {
        return new AgentIntent(AgentIntentType.GATHER_RESOURCE, null, resource, null, null, quantity, List.of(), rawText, reason);
    }

    public static AgentIntent simple(AgentIntentType type, String rawText, String reason) {
        return new AgentIntent(type, null, null, null, null, 1, List.of(), rawText, reason);
    }
}
