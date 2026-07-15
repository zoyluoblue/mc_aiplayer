package io.github.zoyluo.aibot.brain;

import java.util.List;

public record ChatMessage(
        String role,
        String content,
        List<ChatToolCall> toolCalls,
        String toolCallId,
        String name
) {
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), null, null);
    }

    public static ChatMessage assistant(String content, List<ChatToolCall> calls) {
        return new ChatMessage("assistant", content, calls == null ? List.of() : List.copyOf(calls), null, null);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, List.of(), toolCallId, null);
    }

    /** name = 触发该结果的工具名。BrainCoordinator 靠它识别 finish 工具(否则 finish 检测永远 false)。 */
    public static ChatMessage toolResult(String toolCallId, String content, String toolName) {
        return new ChatMessage("tool", content, List.of(), toolCallId, toolName);
    }
}
