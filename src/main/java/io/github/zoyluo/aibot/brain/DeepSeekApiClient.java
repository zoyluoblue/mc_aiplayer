package io.github.zoyluo.aibot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
        if (config.thinkingDisabled()) {
            // 双通道兼容: DeepSeek 官方用 {"thinking":{"type":"disabled"}};
            // StepFun step-3.7-flash 用 {"reasoning_effort":"low"}。实测 Step 模型即使
            // low 仍会把正文写进 reasoning_content(不会写 content),故 parseResponse 里需做 fallback。
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
            body.addProperty("reasoning_effort", "low");
        }
        BotLog.api(null, "api_request",
                "model", config.model(),
                "msg_count", history.size(),
                "tools_count", tools == null ? 0 : tools.size(),
                "max_tokens", config.maxTokens());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizedBaseUrl() + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        long t0 = System.currentTimeMillis();
        String responseBody = null;
        int statusCode = 0;
        String error = null;
        try {
            HttpResponse<String> response = sendWithRetry(request);
            statusCode = response.statusCode();
            responseBody = response.body();
            if (statusCode != 200) {
                error = classifyStatus(statusCode, responseBody);
                throw new DeepSeekApiException(error);
            }
            if (responseBody == null || responseBody.isBlank()) {
                throw new DeepSeekApiException("empty_response");
            }
        } catch (DeepSeekApiException e) {
            io.github.zoyluo.aibot.log.ConversationLogger.INSTANCE.onApiCall(
                    "?", config.model(), normalizedBaseUrl() + "/v1/chat/completions",
                    body.toString(), responseBody, System.currentTimeMillis() - t0, 0, 0, e.getMessage());
            throw e;
        }
        ChatResponse parsed = parseResponse(responseBody);
        io.github.zoyluo.aibot.log.ConversationLogger.INSTANCE.onApiCall(
                "?", config.model(), normalizedBaseUrl() + "/v1/chat/completions",
                body.toString(), responseBody, System.currentTimeMillis() - t0,
                parsed.promptTokens(), parsed.completionTokens(), null);
        return parsed;
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) throws DeepSeekApiException {
        int attempts = Math.max(0, config.retryCount()) + 1;
        long backoffMs = Math.max(1, config.retryBackoffMs());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if ((status == 429 || status >= 500) && attempt < attempts) {
                    BotLog.warn(LogCategory.API, null, "api_retry", "attempt", attempt, "reason", status, "backoff_ms", backoffMs);
                    sleep(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                return response;
            } catch (HttpTimeoutException exception) {
                if (attempt >= attempts) {
                    BotLog.error("api_timeout", exception, "attempt", attempt);
                    throw new DeepSeekApiException("api_timeout: " + exception.getMessage(), exception);
                }
                BotLog.warn(LogCategory.API, null, "api_retry", "attempt", attempt, "reason", "api_timeout", "backoff_ms", backoffMs);
                sleep(backoffMs);
                backoffMs *= 2;
            } catch (IOException exception) {
                if (attempt >= attempts) {
                    BotLog.error("api_io_error", exception, "attempt", attempt);
                    throw new DeepSeekApiException("io_error: " + exception.getMessage(), exception);
                }
                BotLog.warn(LogCategory.API, null, "api_retry", "attempt", attempt, "reason", "io_error", "backoff_ms", backoffMs);
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

    private static String classifyStatus(int status, String body) {
        String excerpt = body == null ? "" : body.substring(0, Math.min(200, body.length()));
        if (status == 429) {
            return "rate_limited: status=429 body=" + excerpt;
        }
        if (status == 408) {
            return "api_timeout: status=408 body=" + excerpt;
        }
        if (status >= 500) {
            return "server_error: status=" + status + " body=" + excerpt;
        }
        if (status == 401 || status == 403) {
            return "auth_error: status=" + status + " body=" + excerpt;
        }
        return "http_error: status=" + status + " body=" + excerpt;
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
            // name 仅对 legacy function-role 有意义;tool-role 上带 name 会被挑剔的网关(StepFun)拒(400)。
            // 我们内部用 ChatMessage.name 携带工具名做 finish 识别,但绝不让它进 tool 消息的线格式。
            if (message.name() != null && !"tool".equals(message.role())) {
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

    static ChatResponse parseResponse(String body) throws DeepSeekApiException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                    ? root.getAsJsonArray("choices")
                    : null;
            if (choices == null || choices.isEmpty()) {
                throw new DeepSeekApiException("empty_choices");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            // Step 模型把正文放 reasoning_content 而不是 content。
            // 关键防范:reasoning_content 是完整思考流(几百~几千字),全部当正文 → 触发 TTS 把思考全念出来,非常烦人。
            // 策略:(1) tool_calls 时 reasoning_content 是纯思考,不进 content;(2) finish_reason=stop 且 content 为空时,
            //         取 reasoning_content 末尾 ≤200 字符(Step 倾向于把结论写末尾),超出截断。
            String content = nullableString(message.get("content"));
            String finishReason = nullableString(choice.get("finish_reason"));
            if ((content == null || content.isBlank()) && "stop".equals(finishReason)) {
                String reasoning = nullableString(message.get("reasoning_content"));
                if (reasoning != null && !reasoning.isBlank()) {
                    content = conclusionTail(reasoning, 200);
                }
            }
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
            BotLog.api(null, "api_response",
                    "tokens_in", promptTokens,
                    "tokens_out", completionTokens,
                    "cache_hit", cacheHitTokens,
                    "finish_reason", finishReason);
            return new ChatResponse(content, toolCalls, finishReason, promptTokens, completionTokens, cacheHitTokens);
        } catch (DeepSeekApiException exception) {
            BotLog.warn(LogCategory.API, null, "api_parse_error", "reason", exception.getMessage(), "body_excerpt", body.substring(0, Math.min(200, body.length())));
            throw exception;
        } catch (RuntimeException exception) {
            BotLog.error("api_parse_error", exception, "body_excerpt", body.substring(0, Math.min(200, body.length())));
            throw new DeepSeekApiException("bad_response: " + exception.getMessage(), exception);
        }
    }

    private static String nullableString(JsonElement element) {
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    /**
     * 从 reasoning_content 里取"结论段":Step 把结论写在末尾,但盲取 substring(len-max) 会从半句/半词
     * 切断,读起来是残句。改为:先截末尾 max 字符,再从其中**第一个句末标点之后**开始,保证以完整句起头。
     * 找不到句界(整段一句话)就回退整段末尾。
     */
    static String conclusionTail(String reasoning, int max) {
        String text = reasoning.strip();
        if (text.length() <= max) {
            return text;
        }
        String tail = text.substring(text.length() - max);
        int cut = -1;
        for (int i = 0; i < tail.length() - 1; i++) {
            char c = tail.charAt(i);
            if (c == '。' || c == '.' || c == '!' || c == '?' || c == '！' || c == '？' || c == '\n') {
                cut = i;
                break;
            }
        }
        String result = cut >= 0 ? tail.substring(cut + 1).strip() : tail.strip();
        return result.isBlank() ? tail.strip() : result;
    }

    private static int intField(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsInt() : 0;
    }
}
