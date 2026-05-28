package io.github.zoyluo.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public record AIBotConfig(
        DeepSeek deepseek,
        Perception perception,
        Brain brain
) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static AIBotConfig instance = defaults();

    public static AIBotConfig get() {
        return instance;
    }

    public static AIBotConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("aibot.json");
        AIBotConfig loaded = defaults();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                AIBotConfig parsed = GSON.fromJson(reader, AIBotConfig.class);
                if (parsed != null) {
                    loaded = parsed.withDefaults();
                }
            } catch (IOException exception) {
                AIBotMod.LOGGER.warn("[AIBot] Failed to read config {}, using defaults", path, exception);
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(loaded, writer);
                }
                AIBotMod.LOGGER.warn("[AIBot] Wrote default config template to {}; set DEEPSEEK_API_KEY or deepseek.apiKey", path);
            } catch (IOException exception) {
                AIBotMod.LOGGER.warn("[AIBot] Failed to write default config {}", path, exception);
            }
        }

        String envKey = System.getenv("DEEPSEEK_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            loaded = loaded.withDeepSeek(loaded.deepseek().withApiKey(envKey));
        }
        if (loaded.deepseek().apiKey().isBlank()) {
            AIBotMod.LOGGER.warn("[AIBot] DeepSeek API key not configured; M4 brain requests will fail fast");
        }
        instance = loaded;
        return loaded;
    }

    public AIBotConfig withDeepSeek(DeepSeek deepseek) {
        return new AIBotConfig(deepseek, perception(), brain());
    }

    private AIBotConfig withDefaults() {
        AIBotConfig defaults = defaults();
        return new AIBotConfig(
                deepseek == null ? defaults.deepseek : deepseek.withDefaults(defaults.deepseek),
                perception == null ? defaults.perception : perception.withDefaults(defaults.perception),
                brain == null ? defaults.brain : brain.withDefaults(defaults.brain));
    }

    public static AIBotConfig defaults() {
        return new AIBotConfig(
                new DeepSeek("", "https://api.deepseek.com", "deepseek-chat", 2048, 0.3D, 60, 3, 500),
                new Perception(16, 20, 10, 10),
                new Brain(20, 6, 5));
    }

    public record DeepSeek(
            String apiKey,
            String baseUrl,
            String model,
            int maxTokens,
            double temperature,
            int timeoutSeconds,
            int retryCount,
            int retryBackoffMs
    ) {
        DeepSeek withApiKey(String apiKey) {
            return new DeepSeek(apiKey, baseUrl, model, maxTokens, temperature, timeoutSeconds, retryCount, retryBackoffMs);
        }

        DeepSeek withDefaults(DeepSeek defaults) {
            return new DeepSeek(
                    apiKey == null ? defaults.apiKey : apiKey,
                    blankToDefault(baseUrl, defaults.baseUrl),
                    blankToDefault(model, defaults.model),
                    positiveOrDefault(maxTokens, defaults.maxTokens),
                    temperature,
                    positiveOrDefault(timeoutSeconds, defaults.timeoutSeconds),
                    Math.max(0, retryCount),
                    positiveOrDefault(retryBackoffMs, defaults.retryBackoffMs));
        }
    }

    public record Perception(int radius, int maxBlocks, int maxEntities, int maxItems) {
        Perception withDefaults(Perception defaults) {
            return new Perception(
                    positiveOrDefault(radius, defaults.radius),
                    positiveOrDefault(maxBlocks, defaults.maxBlocks),
                    positiveOrDefault(maxEntities, defaults.maxEntities),
                    positiveOrDefault(maxItems, defaults.maxItems));
        }
    }

    public record Brain(int maxHistoryMessages, int maxToolCallsPerTurn, int maxTurnsPerRequest) {
        Brain withDefaults(Brain defaults) {
            return new Brain(
                    positiveOrDefault(maxHistoryMessages, defaults.maxHistoryMessages),
                    positiveOrDefault(maxToolCallsPerTurn, defaults.maxToolCallsPerTurn),
                    positiveOrDefault(maxTurnsPerRequest, defaults.maxTurnsPerRequest));
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }
}
