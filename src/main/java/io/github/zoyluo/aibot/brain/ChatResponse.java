package io.github.zoyluo.aibot.brain;

import java.util.List;

public record ChatResponse(
        String content,
        List<ChatToolCall> toolCalls,
        String finishReason,
        int promptTokens,
        int completionTokens,
        int promptCacheHitTokens
) {
    public boolean wantsToolCalls() {
        return "tool_calls".equals(finishReason) && toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isDone() {
        return "stop".equals(finishReason);
    }
}
