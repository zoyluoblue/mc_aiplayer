package com.aiplayer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.aiplayer.AiPlayerMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class AiPlayerConfig {
    private static final String DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final String DEFAULT_DEEPSEEK_REASONING_EFFORT = "high";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("ai.json");

    public static final ConfigValue<String> DEEPSEEK_API_KEY = new ConfigValue<>("");
    public static final ConfigValue<String> DEEPSEEK_BASE_URL = new ConfigValue<>(DEFAULT_DEEPSEEK_BASE_URL);
    public static final ConfigValue<String> DEEPSEEK_MODEL = new ConfigValue<>(DEFAULT_DEEPSEEK_MODEL);
    public static final BooleanValue DEEPSEEK_THINKING_ENABLED = new BooleanValue(false);
    public static final ConfigValue<String> DEEPSEEK_REASONING_EFFORT =
        new ConfigValue<>(DEFAULT_DEEPSEEK_REASONING_EFFORT);
    public static final IntValue MAX_TOKENS = new IntValue(8000);
    public static final DoubleValue TEMPERATURE = new DoubleValue(0.7D);
    public static final IntValue ACTION_TICK_DELAY = new IntValue(20);
    public static final BooleanValue ENABLE_CHAT_RESPONSES = new BooleanValue(true);
    public static final IntValue MAX_ACTIVE_AI_PLAYERS = new IntValue(10);

    private AiPlayerConfig() {
    }

    public static void load() {
        if (Files.notExists(CONFIG_PATH)) {
            save();
            AiPlayerMod.info("config", "Created default AiPlayer AI config at {}", CONFIG_PATH);
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data == null) {
                save();
                return;
            }

            DeepSeek deepseek = data.deepseek == null ? new DeepSeek() : data.deepseek;
            Behavior behavior = data.behavior == null ? new Behavior() : data.behavior;

            DEEPSEEK_API_KEY.set(nullToDefault(deepseek.apiKey, ""));
            DEEPSEEK_BASE_URL.set(normalizeBaseUrl(nullToDefault(
                deepseek.baseUrl,
                DEFAULT_DEEPSEEK_BASE_URL
            )));
            DEEPSEEK_MODEL.set(normalizeDeepSeekModel(deepseek.model));
            DEEPSEEK_THINKING_ENABLED.set(deepseek.thinkingEnabled);
            DEEPSEEK_REASONING_EFFORT.set(normalizeReasoningEffort(deepseek.reasoningEffort));
            MAX_TOKENS.set(clamp(deepseek.maxTokens, 100, 393216));
            TEMPERATURE.set(clamp(deepseek.temperature, 0.0D, 2.0D));
            ACTION_TICK_DELAY.set(clamp(behavior.actionTickDelay, 1, 100));
            ENABLE_CHAT_RESPONSES.set(behavior.enableChatResponses);
            MAX_ACTIVE_AI_PLAYERS.set(clamp(behavior.maxActiveAiPlayers, 1, 50));
        } catch (Exception e) {
            AiPlayerMod.error("config", "Failed to load AiPlayer AI config from {}", CONFIG_PATH, e);
            save();
        }
    }

    public static void save() {
        Data data = new Data();
        data.deepseek.apiKey = DEEPSEEK_API_KEY.get();
        data.deepseek.baseUrl = DEEPSEEK_BASE_URL.get();
        data.deepseek.model = DEEPSEEK_MODEL.get();
        data.deepseek.thinkingEnabled = DEEPSEEK_THINKING_ENABLED.get();
        data.deepseek.reasoningEffort = DEEPSEEK_REASONING_EFFORT.get();
        data.deepseek.maxTokens = MAX_TOKENS.get();
        data.deepseek.temperature = TEMPERATURE.get();
        data.behavior.actionTickDelay = ACTION_TICK_DELAY.get();
        data.behavior.enableChatResponses = ENABLE_CHAT_RESPONSES.get();
        data.behavior.maxActiveAiPlayers = MAX_ACTIVE_AI_PLAYERS.get();

        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            AiPlayerMod.error("config", "Failed to write AiPlayer AI config to {}", CONFIG_PATH, e);
        }
    }

    private static String nullToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        }
        return normalized;
    }

    private static String normalizeDeepSeekModel(String model) {
        String normalized = nullToDefault(model, DEFAULT_DEEPSEEK_MODEL).trim();
        if (!normalized.startsWith("deepseek-")) {
            return DEFAULT_DEEPSEEK_MODEL;
        }
        return normalized;
    }

    private static String normalizeReasoningEffort(String reasoningEffort) {
        String normalized = nullToDefault(reasoningEffort, DEFAULT_DEEPSEEK_REASONING_EFFORT)
            .trim()
            .toLowerCase();
        return switch (normalized) {
            case "low", "medium", "high" -> "high";
            case "xhigh", "max" -> "max";
            default -> DEFAULT_DEEPSEEK_REASONING_EFFORT;
        };
    }

    public static String getDeepSeekChatCompletionsUrl() {
        return DEEPSEEK_BASE_URL.get() + "/chat/completions";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static class ConfigValue<T> {
        private T value;

        public ConfigValue(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }
    }

    public static final class IntValue extends ConfigValue<Integer> {
        public IntValue(Integer value) {
            super(value);
        }
    }

    public static final class DoubleValue extends ConfigValue<Double> {
        public DoubleValue(Double value) {
            super(value);
        }
    }

    public static final class BooleanValue extends ConfigValue<Boolean> {
        public BooleanValue(Boolean value) {
            super(value);
        }
    }

    private static final class Data {
        private DeepSeek deepseek = new DeepSeek();
        private Behavior behavior = new Behavior();
    }

    private static final class DeepSeek {
        private String apiKey = "";
        private String baseUrl = DEFAULT_DEEPSEEK_BASE_URL;
        private String model = DEFAULT_DEEPSEEK_MODEL;
        private boolean thinkingEnabled = false;
        private String reasoningEffort = DEFAULT_DEEPSEEK_REASONING_EFFORT;
        private int maxTokens = 8000;
        private double temperature = 0.7D;
    }

    private static final class Behavior {
        private int actionTickDelay = 20;
        private boolean enableChatResponses = true;
        private int maxActiveAiPlayers = 10;
    }
}
