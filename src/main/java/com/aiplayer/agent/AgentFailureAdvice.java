package com.aiplayer.agent;

public record AgentFailureAdvice(
    FailureAdviceSource source,
    FailureCategory category,
    String strategy,
    String action,
    String userMessage,
    String deepSeekPrompt,
    boolean acceptedDeepSeek
) {
}
