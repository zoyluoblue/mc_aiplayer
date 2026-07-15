package io.github.zoyluo.aibot.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.brain.ChatToolCall;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bob 与玩家的完整对话日志(两层):
 * 1. {botName}.log  —— 人类可读 summary(时间戳 + 角色 + 内容 + 工具调用名 + 结果摘要)
 * 2. {botName}.jsonl —— 结构化原始数据(完整 API request/response JSON、工具调用参数、token 用量、延迟)
 *
 * 用途:复盘 Bob 行为、排查"为什么 Bob 突然说这句/干这事"、给 KB 提供素材。
 * 文件位置: {gameDir}/logs/aibot_conversations/{botName}.log(.jsonl)
 */
public final class ConversationLogger {
    public static final ConversationLogger INSTANCE = new ConversationLogger();

    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private Path baseDir;
    private boolean enabled;
    private final Map<String, BufferedWriter> summaryWriters = new ConcurrentHashMap<>();
    private final Map<String, BufferedWriter> jsonlWriters = new ConcurrentHashMap<>();

    private ConversationLogger() {
    }

    public void start(AIBotConfig config) {
        if (config == null || !config.logging().enabled()) {
            enabled = false;
            return;
        }
        this.enabled = true;
        this.baseDir = FabricLoader.getInstance().getGameDir()
                .resolve(config.logging().directory()).resolve("aibot_conversations");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            io.github.zoyluo.aibot.log.BotLog.warn(LogCategory.COMM, null, "conversation_log_disabled", "reason", e.getMessage());
            this.enabled = false;
        }
    }

    public void onUserMessage(String botName, String sender, String text) {
        if (!enabled) return;
        summary("[%s] USER  %s: %s\n", now(), sender == null ? "?" : sender, truncate(text, 200));
        jsonl(Map.of(
                "ts", Instant.now().toString(), "type", "user",
                "bot", botName, "sender", sender == null ? "?" : sender, "text", text));
    }

    public void onAssistant(String botName, String content, List<ChatToolCall> toolCalls, String finishReason) {
        if (!enabled) return;
        StringBuilder sb = new StringBuilder();
        if (content != null && !content.isBlank()) {
            sb.append("[ASSISTANT] text: ").append(truncate(content, 300)).append('\n');
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            sb.append("[ASSISTANT] tool_calls: ").append(toolCalls.size()).append('\n');
            for (ChatToolCall c : toolCalls) {
                sb.append("    - ").append(c.name()).append('(').append(truncate(c.arguments(), 150)).append(")\n");
            }
        }
        sb.append("[ASSISTANT] finish_reason: ").append(finishReason).append('\n');
        summary("[%s] %s", now(), sb.toString());

        List<Map<String, String>> calls = new java.util.ArrayList<>();
        if (toolCalls != null) {
            for (ChatToolCall c : toolCalls) {
                Map<String, String> cm = new LinkedHashMap<>();
                cm.put("id", c.id() == null ? "" : c.id());
                cm.put("name", c.name());
                cm.put("arguments", c.arguments() == null ? "" : c.arguments());
                calls.add(cm);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", Instant.now().toString());
        m.put("type", "assistant");
        m.put("bot", botName == null ? "" : botName);
        m.put("content", content == null ? "" : content);
        m.put("tool_calls", calls);
        m.put("finish_reason", finishReason == null ? "" : finishReason);
        jsonl(m);
    }

    public void onToolResult(String botName, String callName, String callId, String result) {
        if (!enabled) return;
        summary("[%s] TOOL  %s -> %s\n", now(), callName, truncate(result, 200));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ts", Instant.now().toString());
        m.put("type", "tool_result");
        m.put("bot", botName == null ? "" : botName);
        m.put("name", callName == null ? "" : callName);
        m.put("id", callId == null ? "" : callId);
        m.put("result", result == null ? "" : result);
        jsonl(m);
    }

    public void onApiCall(String botName, String model, String endpoint, String requestBodyJson,
                          String responseBodyJson, long latencyMs, int promptTokens, int completionTokens, String error) {
        if (!enabled) return;
        summary("[%s] API   %s %s  %dms  prompt=%d compl=%d%s\n",
                now(), model, endpoint, latencyMs, promptTokens, completionTokens,
                error == null ? "" : "  ERROR=" + truncate(error, 100));
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ts", Instant.now().toString());
        m.put("type", "api_call");
        m.put("bot", botName == null ? "" : botName);
        m.put("model", model == null ? "" : model);
        m.put("endpoint", endpoint == null ? "" : endpoint);
        m.put("latency_ms", latencyMs);
        m.put("prompt_tokens", promptTokens);
        m.put("completion_tokens", completionTokens);
        m.put("error", error == null ? "" : error);
        m.put("request", safeJson(requestBodyJson));
        m.put("response", safeJson(responseBodyJson));
        jsonl(m);
    }

    public void onTrace(String botName, String line) {
        if (!enabled) return;
        summary("[%s] TRACE %s\n", now(), line);
    }

    // ========================= 内部 =========================

    private static String now() {
        return TS.format(Instant.now());
    }

    private void summary(String format, Object... args) {
        String line;
        try {
            line = String.format(format, (Object[]) args);
        } catch (RuntimeException formatError) {
            // 格式串/参数不匹配绝不能让调用方(handleMessage→submit)崩掉,退化为原样拼接。
            line = format + " " + java.util.Arrays.toString(args) + "\n";
        }
        BufferedWriter w = summaryWriters.computeIfAbsent("all", k -> writer(k + ".log"));
        if (w == null) return;
        synchronized (w) {
            try {
                w.write(line);
                w.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private void jsonl(Object payload) {
        BufferedWriter w = jsonlWriters.computeIfAbsent("all", k -> writer(k + ".jsonl"));
        if (w == null) return;
        synchronized (w) {
            try {
                w.write(JSON.toJson(payload));
                w.write('\n');
                w.flush();
            } catch (IOException ignored) {
            }
        }
    }

    private BufferedWriter writer(String fileName) {
        try {
            return Files.newBufferedWriter(baseDir.resolve(fileName),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            return null;
        }
    }

    private static String safeJson(String text) {
        if (text == null) return "";
        try {
            return JSON.toJson(com.google.gson.JsonParser.parseString(text));
        } catch (Exception e) {
            return text;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    public void shutdown() {
        enabled = false;
        for (BufferedWriter w : summaryWriters.values()) close(w);
        for (BufferedWriter w : jsonlWriters.values()) close(w);
        summaryWriters.clear();
        jsonlWriters.clear();
    }

    private static void close(BufferedWriter w) {
        if (w == null) return;
        try {
            w.close();
        } catch (IOException ignored) {
        }
    }
}
