package com.aiplayer.agent;

import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.execution.TaskSession;
import com.aiplayer.llm.SurvivalPrompt;
import com.aiplayer.llm.async.AsyncDeepSeekClient;
import com.aiplayer.llm.async.AsyncLLMClient;
import com.aiplayer.recipe.MiningResource;
import com.aiplayer.snapshot.SnapshotSerializer;
import com.aiplayer.snapshot.WorldSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class MiningStrategyAdvisor {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> ALLOWED_STRATEGY_ACTIONS = Set.of(
        "continue_current_step",
        "switch_to_stair_descent",
        "rebuild_plan",
        "observe_and_replan",
        "request_user_help"
    );

    private final AsyncLLMClient client;
    private final boolean enabled;
    private final String disabledReason;

    public MiningStrategyAdvisor() {
        String apiKey = AiPlayerConfig.DEEPSEEK_API_KEY.get();
        if (apiKey == null || apiKey.isBlank()) {
            this.client = null;
            this.enabled = false;
            this.disabledReason = "DeepSeek API key 未配置";
            return;
        }
        AsyncLLMClient createdClient = null;
        boolean createdEnabled = false;
        String reason = "";
        try {
            createdClient = new AsyncDeepSeekClient(
                apiKey,
                AiPlayerConfig.DEEPSEEK_BASE_URL.get(),
                AiPlayerConfig.DEEPSEEK_MODEL.get(),
                AiPlayerConfig.MAX_TOKENS.get(),
                AiPlayerConfig.TEMPERATURE.get(),
                AiPlayerConfig.DEEPSEEK_THINKING_ENABLED.get(),
                AiPlayerConfig.DEEPSEEK_REASONING_EFFORT.get()
            );
            createdEnabled = true;
        } catch (RuntimeException e) {
            reason = e.getMessage() == null ? "DeepSeek 挖矿策略客户端初始化失败" : e.getMessage();
        }
        this.client = createdClient;
        this.enabled = createdEnabled;
        this.disabledReason = reason;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String disabledReason() {
        return disabledReason;
    }

    public CompletableFuture<MiningStrategyAdvice> requestAdvice(
        String taskId,
        AiPlayerEntity aiPlayer,
        String targetItem,
        int targetQuantity,
        TaskSession session
    ) {
        return requestAdvice(taskId, aiPlayer, targetItem, targetQuantity, session, List.of());
    }

    public CompletableFuture<MiningStrategyAdvice> requestAdvice(
        String taskId,
        AiPlayerEntity aiPlayer,
        String targetItem,
        int targetQuantity,
        TaskSession session,
        List<String> relevantSkills
    ) {
        if (!enabled || client == null) {
            return CompletableFuture.completedFuture(MiningStrategyAdvice.unavailable(disabledReason));
        }
        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, "mining_strategy_review");
        String userPrompt = buildUserPrompt(taskId, aiPlayer, targetItem, targetQuantity, session, snapshot, relevantSkills);
        Map<String, Object> params = Map.of(
            "systemPrompt", buildSystemPrompt(),
            "model", AiPlayerConfig.DEEPSEEK_MODEL.get(),
            "maxTokens", AiPlayerConfig.MAX_TOKENS.get(),
            "temperature", AiPlayerConfig.TEMPERATURE.get(),
            "thinkingEnabled", AiPlayerConfig.DEEPSEEK_THINKING_ENABLED.get(),
            "reasoningEffort", AiPlayerConfig.DEEPSEEK_REASONING_EFFORT.get()
        );
        return client.sendAsync(userPrompt, params)
            .thenApply(response -> parseAdvice(response.getContent()))
            .exceptionally(throwable -> MiningStrategyAdvice.invalid(
                throwable.getMessage() == null ? "DeepSeek 挖矿策略请求失败" : throwable.getMessage(),
                ""
            ));
    }

    public static String buildSystemPrompt() {
        return SurvivalPrompt.sharedContext() + "\n" + """
            你是 Minecraft 生存 AI 的挖矿策略顾问。只输出 JSON。
            你只能基于上下文里提供的事实给下一步策略建议。
            矿点坐标、配方、工具要求和材料数量由本地代码扫描和验证；你只能建议是否继续、下挖、重建计划、复盘或请求协助。
            不要输出具体坐标，不要假设地下有矿，不要编造背包或箱子材料。
            允许的 strategy action 只有：
            - continue_current_step：继续当前 step
            - switch_to_stair_descent：切换到阶梯式下挖或继续下探
            - rebuild_plan：重新观察并用本地 RecipeResolver 重建路线
            - observe_and_replan：重新观察后复盘
            - request_user_help：请求玩家移动、给材料或改变目标
            JSON 格式：
            {"strategy":"...","action":"continue_current_step","message":"...","reason":"...","needsRebuild":false,"needsUserHelp":false}
            输出必须是单个 JSON 对象，不要 Markdown。
            """;
    }

    public static String buildUserPrompt(
        String taskId,
        AiPlayerEntity aiPlayer,
        String targetItem,
        int targetQuantity,
        TaskSession session,
        WorldSnapshot snapshot
    ) {
        return buildUserPrompt(taskId, aiPlayer, targetItem, targetQuantity, session, snapshot, List.of());
    }

    public static String buildUserPrompt(
        String taskId,
        AiPlayerEntity aiPlayer,
        String targetItem,
        int targetQuantity,
        TaskSession session,
        WorldSnapshot snapshot,
        List<String> relevantSkills
    ) {
        Map<String, Object> context = new LinkedHashMap<>();
        ExecutionStep currentStep = session == null ? null : session.currentStep();
        WorldSnapshot safeSnapshot = snapshot == null ? WorldSnapshot.empty("mining_strategy_review") : snapshot;
        context.put("taskId", taskId == null ? "task-unknown" : taskId);
        context.put("goal", "make_item");
        context.put("targetItem", targetItem == null ? "" : targetItem);
        context.put("targetQuantity", Math.max(1, targetQuantity));
        context.put("currentStep", currentStep == null ? "" : currentStep.describe());
        context.put("currentStepIndex", session == null ? 0 : session.getCurrentStepIndex() + 1);
        context.put("stepCount", session == null ? 0 : session.getStepCount());
        context.put("position", aiPlayer == null ? "" : aiPlayer.blockPosition().toShortString());
        context.put("y", aiPlayer == null ? 0 : aiPlayer.blockPosition().getY());
        context.put("dimension", aiPlayer == null ? "" : aiPlayer.level().dimension().location().toString());
        context.put("inventory", aiPlayer == null ? Map.of() : aiPlayer.getInventorySnapshot());
        context.put("availableItems", safeSnapshot.availableItems());
        context.put("nearbyChests", safeSnapshot.getNearbyChests().size());
        context.put("nearbyBlocks", safeSnapshot.getNearbyBlocks().stream()
            .map(WorldSnapshot.BlockSnapshot::getBlock)
            .distinct()
            .limit(40)
            .toList());
        context.put("nearbyCaves", safeSnapshot.getNearbyCaves().stream()
            .map(cave -> Map.of(
                "position", cave.getPosition(),
                "reachable", cave.isReachable(),
                "connectedAir", cave.getConnectedAir(),
                "exposedWalls", cave.getExposedWalls(),
                "visibleOres", cave.getVisibleOres(),
                "distance", cave.getDistance()
            ))
            .toList());
        context.put("recentFailures", session == null ? List.of() : session.getFailureHistory());
        context.put("recentEvents", session == null ? List.of() : session.getRecentEvents().stream()
            .map(event -> event.stepId() + " " + event.status() + " " + event.message() + " delta=" + event.inventoryDelta())
            .limit(10)
            .toList());
        context.put("relevantSkillMemory", relevantSkills == null ? List.of() : relevantSkills);
        context.put("miningProfile", miningProfileContext(currentStep, targetItem));
        context.put("prospectingRules", List.of(
            "本地 OreProspector 负责扫描已加载区块中的真实矿石方块",
            "本地 StageMiningPlan 负责把矿点转换为阶梯下挖、横挖、暴露矿物和采集阶段",
            "DeepSeek 不直接决定坐标，只判断当前阶段是否应继续、重扫、重建计划或请求玩家协助",
            "遇到不可达、环境危险、目标被挖掉或目标位于当前层上方时，本地代码会终止任务并返回玩家身边"
        ));
        context.put("unknownFacts", List.of(
            "地下未暴露矿物未知，需要实际探矿确认",
            "扫描范围外箱子内容未知",
            "DeepSeek 不能直接决定坐标或破坏方块"
        ));
        context.put("allowedStrategyActions", ALLOWED_STRATEGY_ACTIONS.stream().sorted().toList());
        context.put("snapshotJson", SnapshotSerializer.toCompactJson(safeSnapshot));
        return "MINING_STRATEGY_CONTEXT:\n" + GSON.toJson(context);
    }

    private static Map<String, Object> miningProfileContext(ExecutionStep currentStep, String targetItem) {
        MiningResource.Profile profile = null;
        if (currentStep != null) {
            profile = MiningResource.findByItemOrSource(currentStep.getItem(), currentStep.getResource()).orElse(null);
        }
        if (profile == null && targetItem != null) {
            profile = MiningResource.findByItemOrSource(targetItem, null).orElse(null);
        }
        if (profile == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("key", profile.key());
        context.put("displayName", profile.displayName());
        context.put("dimension", profile.dimension());
        context.put("requiredTool", profile.requiredTool());
        context.put("primaryRange", rangeContext(profile.primaryRange()));
        context.put("fallbackRange", rangeContext(profile.fallbackRange()));
        context.put("surfaceAllowed", profile.surfaceAllowed());
        context.put("cavePreferred", profile.cavePreferred());
        context.put("branchMinePreferred", profile.branchMinePreferred());
        context.put("routeHint", profile.routeHint());
        context.put("specialEnvironment", profile.specialEnvironment());
        return context;
    }

    private static Map<String, Object> rangeContext(MiningResource.HeightRange range) {
        if (range == null) {
            return Map.of();
        }
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("minY", range.minY());
        context.put("maxY", range.maxY());
        context.put("strategy", range.strategy());
        return context;
    }

    public static MiningStrategyAdvice parseAdvice(String json) {
        if (json == null || json.isBlank()) {
            return MiningStrategyAdvice.invalid("DeepSeek 返回空挖矿策略", json);
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String action = getString(object, "action", "continue_current_step");
            String strategy = getString(object, "strategy", "continue_current_step");
            String message = getString(object, "message", "继续当前挖矿 step");
            String reason = getString(object, "reason", "");
            boolean needsRebuild = getBoolean(object, "needsRebuild", false);
            boolean needsUserHelp = getBoolean(object, "needsUserHelp", false);
            if (!ALLOWED_STRATEGY_ACTIONS.contains(action)) {
                return MiningStrategyAdvice.invalid("未声明的挖矿策略动作：" + action, json);
            }
            if (containsForbidden(action) || containsForbidden(strategy) || containsForbidden(message)) {
                return MiningStrategyAdvice.invalid("挖矿策略包含作弊能力", json);
            }
            return MiningStrategyAdvice.accepted(strategy, action, message, reason, needsRebuild, needsUserHelp, json);
        } catch (RuntimeException e) {
            return MiningStrategyAdvice.invalid("DeepSeek 挖矿策略 JSON 无法解析：" + e.getMessage(), json);
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsBoolean();
    }

    private static boolean containsForbidden(String value) {
        String text = value == null ? "" : value.toLowerCase();
        return text.contains("give")
            || text.contains("teleport")
            || text.contains("setblock")
            || text.contains("creative")
            || text.contains("summon")
            || text.contains("spawn_item")
            || text.contains("fill ");
    }
}
