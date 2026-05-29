package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;

import java.util.ArrayList;
import java.util.List;

public final class ActionDispatcher {
    private final ToolRegistry registry;

    public ActionDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public List<ChatMessage> dispatch(AIPlayerEntity bot, List<ChatToolCall> calls) {
        int maxCalls = AIBotConfig.get().brain().maxToolCallsPerTurn();
        List<ChatMessage> results = new ArrayList<>();
        for (int index = 0; index < calls.size(); index++) {
            ChatToolCall call = calls.get(index);
            ToolDefinition.ToolResult result;
            if (index >= maxCalls) {
                result = new ToolDefinition.ToolResult(false, "throttled");
            } else {
                result = invoke(bot, call);
            }
            BotLog.action(bot, "tool_result", "tool", call.name(), "ok", result.ok(), "message", result.message());
            results.add(ChatMessage.toolResult(call.id(), result.toToolContent()));
        }
        return results;
    }

    private ToolDefinition.ToolResult invoke(AIPlayerEntity bot, ChatToolCall call) {
        try {
            ToolDefinition definition = registry.get(call.name())
                    .orElseThrow(() -> new IllegalArgumentException("unknown_tool: " + call.name()));
            JsonObject args = call.parsedArguments();
            BotLog.action(bot, "tool_dispatch", "tool", call.name(), "args", args);
            return definition.handler().invoke(bot, args);
        } catch (RuntimeException exception) {
            BotLog.error(bot, "tool_exception", exception, "tool", call.name());
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new ToolDefinition.ToolResult(false, "exception: " + reason);
        }
    }
}
