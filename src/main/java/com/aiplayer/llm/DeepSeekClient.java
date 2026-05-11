package com.aiplayer.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class DeepSeekClient {
    private static final int MAX_RETRIES = 3;
    private static final int INITIAL_RETRY_DELAY_MS = 1000;

    private final HttpClient client;
    private final String apiKey;
    private final String apiUrl;

    public DeepSeekClient() {
        this.apiKey = AiPlayerConfig.DEEPSEEK_API_KEY.get();
        this.apiUrl = AiPlayerConfig.getDeepSeekChatCompletionsUrl();
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    public String sendRequest(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isEmpty()) {
            AiPlayerMod.error("llm", "DeepSeek API key not configured!");
            return null;
        }

        JsonObject requestBody = buildRequestBody(systemPrompt, userPrompt);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
            .build();
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    String responseBody = response.body();
                    if (responseBody == null || responseBody.isEmpty()) {
                        AiPlayerMod.error("llm", "DeepSeek API returned empty response");
                        return null;
                    }
                    return parseResponse(responseBody);
                }
                if (response.statusCode() == 429 || response.statusCode() >= 500) {
                    if (attempt < MAX_RETRIES - 1) {
                        int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                        AiPlayerMod.warn("llm", "DeepSeek API request failed with status {}, retrying in {}ms (attempt {}/{})",
                            response.statusCode(), delayMs, attempt + 1, MAX_RETRIES);
                        Thread.sleep(delayMs);
                        continue;
                    }
                }
                AiPlayerMod.error("llm", "DeepSeek API request failed: {}", response.statusCode());
                AiPlayerMod.error("llm", "Response body: {}", response.body());
                return null;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AiPlayerMod.error("llm", "Request interrupted", e);
                return null;
            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    int delayMs = INITIAL_RETRY_DELAY_MS * (int) Math.pow(2, attempt);
                        AiPlayerMod.warn("llm", "Error communicating with DeepSeek API, retrying in {}ms (attempt {}/{})",
                        delayMs, attempt + 1, MAX_RETRIES, e);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    AiPlayerMod.error("llm", "Error communicating with DeepSeek API after {} attempts", MAX_RETRIES, e);
                    return null;
                }
            }
        }

        return null;
    }

    private JsonObject buildRequestBody(String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", AiPlayerConfig.DEEPSEEK_MODEL.get());
        body.addProperty("max_tokens", AiPlayerConfig.MAX_TOKENS.get());
        body.addProperty("stream", false);

        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", AiPlayerConfig.DEEPSEEK_THINKING_ENABLED.get() ? "enabled" : "disabled");
        body.add("thinking", thinking);

        if (AiPlayerConfig.DEEPSEEK_THINKING_ENABLED.get()) {
            body.addProperty("reasoning_effort", AiPlayerConfig.DEEPSEEK_REASONING_EFFORT.get());
        } else {
            body.addProperty("temperature", AiPlayerConfig.TEMPERATURE.get());
        }

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();
        
        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("role", "system");
        systemMessage.addProperty("content", systemPrompt);
        messages.add(systemMessage);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", userPrompt);
        messages.add(userMessage);

        body.add("messages", messages);
        
        return body;
    }

    private String parseResponse(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            
            if (json.has("choices") && json.getAsJsonArray("choices").size() > 0) {
                JsonObject firstChoice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (firstChoice.has("message")) {
                    JsonObject message = firstChoice.getAsJsonObject("message");
                    if (message.has("content") && !message.get("content").isJsonNull()) {
                        return message.get("content").getAsString();
                    }
                }
            }
            
            AiPlayerMod.error("llm", "Unexpected DeepSeek response format: {}", responseBody);
            return null;
            
        } catch (Exception e) {
            AiPlayerMod.error("llm", "Error parsing DeepSeek response", e);
            return null;
        }
    }
}
