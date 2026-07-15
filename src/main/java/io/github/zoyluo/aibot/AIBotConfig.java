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
                // StepFun step-3.7-flash: ~1s 有 structured tool_calls + 识图,正文在 reasoning_content(需 fallback)。
                // max_tokens=768: 直播短句足够,且 Step reasoning 吃 token 预算,留余量给正文。
                new DeepSeek("", "https://api.stepfun.com/step_plan", "step-3.7-flash", 768, 0.3D, 60, 3, 500, false),
                new Perception(16, 20, 10, 10, false),
                new Brain(36, 6, 12, false, true, false, 3, true, false, true), // maxTurns 24→12(早止损);advancedTools 默认藏;ownerEventPush 默认开
                new Watchdog(120), // 200t(10s)发呆才判卡太钝,120t(6s)更快触发恢复(实例 config 里的旧值 200 部署时须同步改)
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
                new Nav(1.0D, 12, 60, 30, 4, 2, 3.0D, 3, true),
                new Pickup(2.75D, 2.5D, 8.0D)); // 实测 1.5/1.0 太小:砍树掉落物垂直差>1 就吸不到→countSoFar=0 死循环
    }

    public record DeepSeek(
            String apiKey,
            String baseUrl,
            String model,
            int maxTokens,
            double temperature,
            int timeoutSeconds,
            int retryCount,
            int retryBackoffMs,
            Boolean disableThinking
    ) {
        DeepSeek withApiKey(String apiKey) {
            return new DeepSeek(apiKey, baseUrl, model, maxTokens, temperature, timeoutSeconds, retryCount, retryBackoffMs, disableThinking);
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
                    positiveOrDefault(retryBackoffMs, defaults.retryBackoffMs),
                    boolOrDefault(disableThinking, defaults.disableThinking));
        }

        public boolean thinkingDisabled() {
            return Boolean.TRUE.equals(disableThinking);
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
            Boolean verboseReports,
            Boolean exposeAdvancedTools, // false(默认)=strip_mine/mine_vein/set_goal 不暴露给模型(弱模型误选诱饵),命令行不受影响
            Boolean ownerEventPush       // true(默认)=主人被打且 bot 未在护卫时,节流喂大脑一条警报事件
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
                    boolOrDefault(verboseReports, defaults.verboseReports),
                    boolOrDefault(exposeAdvancedTools, defaults.exposeAdvancedTools),
                    boolOrDefault(ownerEventPush, defaults.ownerEventPush));
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

        public boolean advancedToolsExposed() {
            return Boolean.TRUE.equals(exposeAdvancedTools);
        }

        public boolean ownerEventPushEnabled() {
            return Boolean.TRUE.equals(ownerEventPush);
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
                      int maxSafeFall,
                      Boolean parkour) {
        Nav withDefaults(Nav defaults) {
            return new Nav(
                    positiveDoubleOrDefault(jumpReach, defaults.jumpReach),
                    positiveOrDefault(sidleAfter, defaults.sidleAfter),
                    positiveOrDefault(sidleLimit, defaults.sidleLimit),
                    positiveOrDefault(hardLimit, defaults.hardLimit),
                    positiveOrDefault(lookahead, defaults.lookahead),
                    positiveOrDefault(nodeRetry, defaults.nodeRetry),
                    positiveDoubleOrDefault(sprintMinDist, defaults.sprintMinDist),
                    positiveOrDefault(maxSafeFall, defaults.maxSafeFall),
                    boolOrDefault(parkour, defaults.parkour));
        }

        /** feature flag:平跳越沟(1~2 格)+ 助跑跳。误跳出问题时改 false 一键回退。 */
        public boolean parkourEnabled() {
            return Boolean.TRUE.equals(parkour);
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
