package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;

import java.util.List;

public final class BrainValidation {
    private BrainValidation() {
    }

    public static ValidationResult apiFailure(AIPlayerEntity bot) {
        AIBotConfig.DeepSeek current = AIBotConfig.get().deepseek();
        AIBotConfig.DeepSeek invalid = new AIBotConfig.DeepSeek(
                "",
                current.baseUrl(),
                current.model(),
                current.maxTokens(),
                current.temperature(),
                current.timeoutSeconds(),
                0,
                current.retryBackoffMs(),
                current.disableThinking());
        try {
            new DeepSeekApiClient(invalid).chat(List.of(ChatMessage.user("validation ping")), List.of());
            return unexpected(bot, "api_failure", "request unexpectedly succeeded");
        } catch (DeepSeekApiException exception) {
            return expected(bot, "api_failure", exception.getMessage());
        } catch (Exception exception) {
            return unexpected(bot, "api_failure", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    public static ValidationResult badToolArgs(AIPlayerEntity bot) {
        ActionDispatcher dispatcher = new ActionDispatcher(new ToolRegistry());
        List<ChatMessage> results = dispatcher.dispatch(bot, List.of(new ChatToolCall(
                "validation_bad_tool_args",
                "move_to",
                "{\"x\":0,\"y\":80}")));
        String content = results.isEmpty() ? "" : results.getFirst().content();
        boolean ok = content != null && content.contains("\"ok\":false") && content.contains("missing_or_bad_arg");
        if (ok) {
            return expected(bot, "bad_tool_args", content);
        }
        return unexpected(bot, "bad_tool_args", content);
    }

    public static ValidationResult badResponse(AIPlayerEntity bot) {
        try {
            DeepSeekApiClient.parseResponse("{\"choices\":[]}");
            return unexpected(bot, "bad_response", "parse unexpectedly succeeded");
        } catch (DeepSeekApiException exception) {
            return expected(bot, "bad_response", exception.getMessage());
        } catch (Exception exception) {
            return unexpected(bot, "bad_response", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static ValidationResult expected(AIPlayerEntity bot, String scenario, String message) {
        BotLog.api(bot, "brain_validation_expected_error", "scenario", scenario, "message", message);
        return new ValidationResult(true, scenario, message);
    }

    private static ValidationResult unexpected(AIPlayerEntity bot, String scenario, String message) {
        BotLog.api(bot, "brain_validation_unexpected_result", "scenario", scenario, "message", message);
        return new ValidationResult(false, scenario, message);
    }

    public record ValidationResult(boolean ok, String scenario, String message) {
    }
}
