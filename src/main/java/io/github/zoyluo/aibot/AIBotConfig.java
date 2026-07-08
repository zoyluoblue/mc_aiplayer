package io.github.zoyluo.aibot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public record AIBotConfig(
        DeepSeek deepseek,
        Perception perception,
        Brain brain,
        Watchdog watchdog,
        Logging logging,
        Survival survival,
        Combat combat,
        Night night,
        Mining mining,
        Goal goal,
        Nav nav,
        Pickup pickup
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
                BotLog.error("config_read_failed", exception, "path", path);
            }
        } else {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(loaded, writer);
                }
                BotLog.config("config_template_written", "path", path);
            } catch (IOException exception) {
                BotLog.error("config_write_failed", exception, "path", path);
            }
        }

        String envKey = System.getenv("DEEPSEEK_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            loaded = loaded.withDeepSeek(loaded.deepseek().withApiKey(envKey));
        }
        if (loaded.deepseek().apiKey().isBlank()) {
            BotLog.warn(LogCategory.CONFIG, null, "deepseek_key_missing");
        }
        instance = loaded;
        return loaded;
    }

    public AIBotConfig withDeepSeek(DeepSeek deepseek) {
        return new AIBotConfig(deepseek, perception(), brain(), watchdog(), logging(), survival(), combat(), night(), mining(), goal(), nav(), pickup());
    }

    private AIBotConfig withDefaults() {
        AIBotConfig defaults = defaults();
        return new AIBotConfig(
                deepseek == null ? defaults.deepseek : deepseek.withDefaults(defaults.deepseek),
                perception == null ? defaults.perception : perception.withDefaults(defaults.perception),
                brain == null ? defaults.brain : brain.withDefaults(defaults.brain),
                watchdog == null ? defaults.watchdog : watchdog.withDefaults(defaults.watchdog),
                logging == null ? defaults.logging : logging.withDefaults(defaults.logging),
                survival == null ? defaults.survival : survival.withDefaults(defaults.survival),
                combat == null ? defaults.combat : combat.withDefaults(defaults.combat),
                night == null ? defaults.night : night.withDefaults(defaults.night),
                mining == null ? defaults.mining : mining.withDefaults(defaults.mining),
                goal == null ? defaults.goal : goal.withDefaults(defaults.goal),
                nav == null ? defaults.nav : nav.withDefaults(defaults.nav),
                pickup == null ? defaults.pickup : pickup.withDefaults(defaults.pickup));
    }

    public static AIBotConfig defaults() {
        return new AIBotConfig(
                new DeepSeek("", "https://api.deepseek.com", "deepseek-chat", 2048, 0.3D, 60, 3, 500),
                new Perception(16, 20, 10, 10, false),
                new Brain(36, 6, 25, false, true, false, 3, true), // maxTurns 12→25: bot слишком долго думает
                new Watchdog(200),
                new Logging(true, "logs/aibot", true, "daily", 50, 30, true, Map.of(
                        "LIFECYCLE", "INFO",
                        "COMM", "INFO",
                        "API", "INFO",
                        "ACTION", "INFO",
                        "PERCEPTION", "DEBUG",
                        "PATH", "DEBUG",
                        "TASK", "INFO",
                        "DANGER", "INFO",
                        "ERROR", "ERROR",
                        "CONFIG", "INFO")),
                new Survival(14, 6),
                new Combat(10, 2),
                new Night(true, 8),
                new Mining(2, 0.10D, true),
                new Goal(24, true, true), // S7:配方补全后链更深(熟食/盾/钻装备等),16→24 留余量
                new Nav(1.0D, 12, 60, 30, 4, 2, 3.0D, 3),
                new Pickup(4.0D, 4.0D, 8.0D));
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

    public record Perception(int radius, int maxBlocks, int maxEntities, int maxItems, boolean includeRawLists) {
        Perception withDefaults(Perception defaults) {
            return new Perception(
                    positiveOrDefault(radius, defaults.radius),
                    positiveOrDefault(maxBlocks, defaults.maxBlocks),
                    positiveOrDefault(maxEntities, defaults.maxEntities),
                    positiveOrDefault(maxItems, defaults.maxItems),
                    includeRawLists);
        }
    }

    public record Brain(
            int maxHistoryMessages,
            int maxToolCallsPerTurn,
            int maxTurnsPerRequest,
            Boolean exposeLowLevelTools,
            Boolean enableMemoryTools,
            Boolean enableCoordinationTools,
            int maxTaskRetries,
            Boolean verboseReports
    ) {
        Brain withDefaults(Brain defaults) {
            return new Brain(
                    positiveOrDefault(maxHistoryMessages, defaults.maxHistoryMessages),
                    positiveOrDefault(maxToolCallsPerTurn, defaults.maxToolCallsPerTurn),
                    Math.max(positiveOrDefault(maxTurnsPerRequest, defaults.maxTurnsPerRequest), defaults.maxTurnsPerRequest),
                    boolOrDefault(exposeLowLevelTools, defaults.exposeLowLevelTools),
                    boolOrDefault(enableMemoryTools, defaults.enableMemoryTools),
                    boolOrDefault(enableCoordinationTools, defaults.enableCoordinationTools),
                    positiveOrDefault(maxTaskRetries, defaults.maxTaskRetries),
                    boolOrDefault(verboseReports, defaults.verboseReports));
        }

        public boolean exposesLowLevelTools() {
            return Boolean.TRUE.equals(exposeLowLevelTools);
        }

        public boolean memoryToolsEnabled() {
            return Boolean.TRUE.equals(enableMemoryTools);
        }

        public boolean coordinationToolsEnabled() {
            return Boolean.TRUE.equals(enableCoordinationTools);
        }

        public boolean verboseReportsEnabled() {
            return Boolean.TRUE.equals(verboseReports);
        }
    }

    public record Survival(int hungerEatThreshold, int hungerCriticalThreshold) {
        Survival withDefaults(Survival defaults) {
            return new Survival(
                    positiveOrDefault(hungerEatThreshold, defaults.hungerEatThreshold),
                    positiveOrDefault(hungerCriticalThreshold, defaults.hungerCriticalThreshold));
        }
    }

    public record Combat(int retreatHp, int maxEnemiesToFight) {
        Combat withDefaults(Combat defaults) {
            return new Combat(
                    positiveOrDefault(retreatHp, defaults.retreatHp),
                    positiveOrDefault(maxEnemiesToFight, defaults.maxEnemiesToFight));
        }
    }

    public record Night(boolean autoSleep, int torchLightThreshold) {
        Night withDefaults(Night defaults) {
            return new Night(autoSleep, positiveOrDefault(torchLightThreshold, defaults.torchLightThreshold));
        }
    }

    public record Mining(int returnWhenFreeSlots, double toolDurabilityFloor, boolean placeTorches) {
        Mining withDefaults(Mining defaults) {
            return new Mining(
                    positiveOrDefault(returnWhenFreeSlots, defaults.returnWhenFreeSlots),
                    toolDurabilityFloor > 0.0D ? toolDurabilityFloor : defaults.toolDurabilityFloor,
                    placeTorches);
        }
    }

    public record Goal(int maxPlanDepth, Boolean replanOnFailure, Boolean autoToolFill) {
        Goal withDefaults(Goal defaults) {
            return new Goal(
                    positiveOrDefault(maxPlanDepth, defaults.maxPlanDepth),
                    boolOrDefault(replanOnFailure, defaults.replanOnFailure),
                    boolOrDefault(autoToolFill, defaults.autoToolFill));
        }

        public boolean replanOnFailureEnabled() {
            return Boolean.TRUE.equals(replanOnFailure);
        }

        public boolean autoToolFillEnabled() {
            return Boolean.TRUE.equals(autoToolFill);
        }
    }

    public record Nav(double jumpReach,
                      int sidleAfter,
                      int sidleLimit,
                      int hardLimit,
                      int lookahead,
                      int nodeRetry,
                      double sprintMinDist,
                      int maxSafeFall) {
        Nav withDefaults(Nav defaults) {
            return new Nav(
                    positiveDoubleOrDefault(jumpReach, defaults.jumpReach),
                    positiveOrDefault(sidleAfter, defaults.sidleAfter),
                    positiveOrDefault(sidleLimit, defaults.sidleLimit),
                    positiveOrDefault(hardLimit, defaults.hardLimit),
                    positiveOrDefault(lookahead, defaults.lookahead),
                    positiveOrDefault(nodeRetry, defaults.nodeRetry),
                    positiveDoubleOrDefault(sprintMinDist, defaults.sprintMinDist),
                    positiveOrDefault(maxSafeFall, defaults.maxSafeFall));
        }
    }

    public record Pickup(double forceRadiusH, double forceRadiusV, double sweepRadius) {
        Pickup withDefaults(Pickup defaults) {
            return new Pickup(
                    positiveDoubleOrDefault(forceRadiusH, defaults.forceRadiusH),
                    positiveDoubleOrDefault(forceRadiusV, defaults.forceRadiusV),
                    positiveDoubleOrDefault(sweepRadius, defaults.sweepRadius));
        }
    }

    public record Watchdog(int stuckWindowTicks) {
        Watchdog withDefaults(Watchdog defaults) {
            return new Watchdog(positiveOrDefault(stuckWindowTicks, defaults.stuckWindowTicks));
        }
    }

    public record Logging(
            boolean enabled,
            String directory,
            boolean perBotFile,
            String rotation,
            int maxFileSizeMb,
            int maxBackups,
            boolean mirrorToSlf4j,
            Map<String, String> categories
    ) {
        Logging withDefaults(Logging defaults) {
            return new Logging(
                    enabled,
                    blankToDefault(directory, defaults.directory),
                    perBotFile,
                    blankToDefault(rotation, defaults.rotation),
                    positiveOrDefault(maxFileSizeMb, defaults.maxFileSizeMb),
                    positiveOrDefault(maxBackups, defaults.maxBackups),
                    mirrorToSlf4j,
                    categories == null || categories.isEmpty() ? defaults.categories : categories);
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private static double positiveDoubleOrDefault(double value, double defaultValue) {
        return value > 0.0D ? value : defaultValue;
    }

    private static Boolean boolOrDefault(Boolean value, Boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}
