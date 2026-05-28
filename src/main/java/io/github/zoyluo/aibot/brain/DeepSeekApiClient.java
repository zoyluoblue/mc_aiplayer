package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.AIBotMod;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DeepSeekApiClient {
    private final AIBotConfig.DeepSeek config;
    private final HttpClient httpClient;

    public DeepSeekApiClient(AIBotConfig.DeepSeek config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public ChatResponse chat(List<ChatMessage> history, List<ToolDefinition> tools) throws DeepSeekApiException {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new DeepSeekApiException("deepseek_api_key_missing");
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", config.model());
        body.add("messages", serializeMessages(history));
        if (tools != null && !tools.isEmpty()) {
            body.add("tools", serializeTools(tools));
            body.addProperty("tool_choice", "auto");
        }
        body.addProperty("max_tokens", config.maxTokens());
        body.addProperty("temperature", config.temperature());
        body.addProperty("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() != 200) {
            throw new DeepSeekApiException("status=" + response.statusCode() + " body=" + response.body());
        }
        return parseResponse(response.body());
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws DeepSeekApiException {
        int attempts = Math.max(0, config.retryCount()) + 1;
        long backoffMs = Math.max(1, config.retryBackoffMs());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if ((status == 429 || status >= 500) && attempt < attempts) {
                    sleep(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                return response;
            } catch (IOException exception) {
                if (attempt >= attempts) {
                    throw new DeepSeekApiException("io_error: " + exception.getMessage(), exception);
                }
                sleep(backoffMs);
                backoffMs *= 2;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DeepSeekApiException("interrupted", exception);
            }
        }
        throw new DeepSeekApiException("retry_exhausted");
    }

    private static void sleep(long millis) throws DeepSeekApiException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DeepSeekApiException("interrupted", exception);
        }
    }

    private String normalizedBaseUrl() {
        String base = config.baseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        return base;
    }

    private static JsonArray serializeMessages(List<ChatMessage> history) {
        JsonArray messages = new JsonArray();
        for (ChatMessage message : history) {
            JsonObject object = new JsonObject();
            object.addProperty("role", message.role());
            if (message.content() != null) {
                object.addProperty("content", message.content());
            } else {
                object.add("content", null);
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                JsonArray toolCalls = new JsonArray();
                for (ChatToolCall call : message.toolCalls()) {
                    JsonObject callObject = new JsonObject();
                    callObject.addProperty("id", call.id());
                    callObject.addProperty("type", "function");
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments", call.arguments() == null ? "{}" : call.arguments());
                    callObject.add("function", function);
                    toolCalls.add(callObject);
                }
                object.add("tool_calls", toolCalls);
            }
            if (message.toolCallId() != null) {
                object.addProperty("tool_call_id", message.toolCallId());
            }
            if (message.name() != null) {
                object.addProperty("name", message.name());
            }
            messages.add(object);
        }
        return messages;
    }

    private static JsonArray serializeTools(List<ToolDefinition> tools) {
        JsonArray array = new JsonArray();
        for (ToolDefinition tool : tools) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.parametersSchema());
            wrapper.add("function", function);
            array.add(wrapper);
        }
        return array;
    }

    private static ChatResponse parseResponse(String body) throws DeepSeekApiException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            String content = nullableString(message.get("content"));
            String finishReason = nullableString(choice.get("finish_reason"));
            List<ChatToolCall> toolCalls = new ArrayList<>();
            if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
                for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                    JsonObject call = element.getAsJsonObject();
                    JsonObject function = call.getAsJsonObject("function");
                    toolCalls.add(new ChatToolCall(
                            nullableString(call.get("id")),
                            nullableString(function.get("name")),
                            nullableString(function.get("arguments"))));
                }
            }
            JsonObject usage = root.has("usage") && root.get("usage").isJsonObject() ? root.getAsJsonObject("usage") : new JsonObject();
            int promptTokens = intField(usage, "prompt_tokens");
            int completionTokens = intField(usage, "completion_tokens");
            int cacheHitTokens = intField(usage, "prompt_cache_hit_tokens");
            AIBotMod.LOGGER.info("[AIBot] DeepSeek usage prompt_tokens={} completion_tokens={} cache_hit_tokens={}",
                    promptTokens, completionTokens, cacheHitTokens);
            return new ChatResponse(content, toolCalls, finishReason, promptTokens, completionTokens, cacheHitTokens);
        } catch (RuntimeException exception) {
            throw new DeepSeekApiException("bad_response: " + exception.getMessage(), exception);
        }
    }

    private static String nullableString(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static int intField(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsInt() : 0;
    }
}
