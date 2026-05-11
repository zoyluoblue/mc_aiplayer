package com.aiplayer.llm;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.Task;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.llm.async.AsyncLLMClient;
import com.aiplayer.llm.async.AsyncDeepSeekClient;
import com.aiplayer.llm.async.LLMCache;
import com.aiplayer.llm.resilience.LLMFallbackHandler;
import com.aiplayer.llm.resilience.ResilientLLMClient;
import com.aiplayer.memory.WorldKnowledge;
import com.aiplayer.planning.PlanParser;
import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanValidator;
import com.aiplayer.planning.PlanningPromptBuilder;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.snapshot.SnapshotSerializer;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TaskPlanner {
    private final DeepSeekClient deepSeekClient;
    private final AsyncLLMClient asyncDeepSeekClient;
    private final LLMCache llmCache;
    private final RecipeResolver recipeResolver;
    private final PlanValidator planValidator;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    public TaskPlanner() {
        this.deepSeekClient = new DeepSeekClient();
        this.recipeResolver = new RecipeResolver();
        this.planValidator = new PlanValidator(recipeResolver);

        this.llmCache = new LLMCache();
        LLMFallbackHandler fallbackHandler = new LLMFallbackHandler();

        String apiKey = AiPlayerConfig.DEEPSEEK_API_KEY.get();
        String baseUrl = AiPlayerConfig.DEEPSEEK_BASE_URL.get();
        String model = AiPlayerConfig.DEEPSEEK_MODEL.get();
        int maxTokens = AiPlayerConfig.MAX_TOKENS.get();
        double temperature = AiPlayerConfig.TEMPERATURE.get();
        boolean thinkingEnabled = AiPlayerConfig.DEEPSEEK_THINKING_ENABLED.get();
        String reasoningEffort = AiPlayerConfig.DEEPSEEK_REASONING_EFFORT.get();

        AsyncLLMClient baseDeepSeek = new AsyncDeepSeekClient(
            apiKey,
            baseUrl,
            model,
            maxTokens,
            temperature,
            thinkingEnabled,
            reasoningEffort
        );
        this.asyncDeepSeekClient = new ResilientLLMClient(baseDeepSeek, llmCache, fallbackHandler);

        AiPlayerMod.info("planning", "TaskPlanner initialized with DeepSeek client");
    }

    public ResponseParser.ParsedResponse planTasks(AiPlayerEntity aiPlayer, String command) {
        return planTasks(aiPlayer, command, "task-sync");
    }

    public ResponseParser.ParsedResponse planTasks(AiPlayerEntity aiPlayer, String command, String taskId) {
        try {
            WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, command);
            AiPlayerMod.info("snapshot", "[taskId={}] World snapshot for AiPlayer '{}': {}",
                taskId, aiPlayer.getAiPlayerName(), SnapshotSerializer.toCompactJson(snapshot));

            MakeItemIntent makeItemIntent = findMakeItemIntent(command, snapshot);
            if (makeItemIntent != null) {
                logRecipePlan(aiPlayer, snapshot, makeItemIntent.item(), makeItemIntent.quantity(), taskId);
                return makeItemResponse(makeItemIntent.item(), makeItemIntent.quantity(), makeItemIntent.description(), taskId);
            }
            if (isSingleTreeCommand(command)) {
                return singleTreeResponse(taskId);
            }
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(aiPlayer);
            String userPrompt = PromptBuilder.buildUserPrompt(aiPlayer, command, worldKnowledge, snapshot);

            AiPlayerMod.info("planning", "[taskId={}] Requesting DeepSeek plan for AiPlayer '{}': {}",
                taskId, aiPlayer.getAiPlayerName(), command);

            String response = deepSeekClient.sendRequest(systemPrompt, userPrompt);
            if (response == null) {
                AiPlayerMod.error("planning", "Failed to get DeepSeek response for command: {}", command);
                return null;
            }

            ResponseParser.ParsedResponse parsedResponse = ResponseParser.parseAIResponse(response);
            if (parsedResponse == null) {
                AiPlayerMod.error("planning", "Failed to parse DeepSeek response");
                return null;
            }

            ResponseParser.ParsedResponse normalized = normalizeForCommand(command, parsedResponse, snapshot, taskId);
            AiPlayerMod.info("planning", "[taskId={}] Plan: {} ({} tasks)",
                taskId, normalized.getPlan(), normalized.getTasks().size());
            return normalized;
        } catch (Exception e) {
            AiPlayerMod.error("planning", "[taskId={}] Error planning tasks with DeepSeek", taskId, e);
            return null;
        }
    }

    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(AiPlayerEntity aiPlayer, String command) {
        return planTasksAsync(aiPlayer, command, "task-async");
    }

    public CompletableFuture<ResponseParser.ParsedResponse> planTasksAsync(AiPlayerEntity aiPlayer, String command, String taskId) {
        try {
            WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, command);
            AiPlayerMod.info("snapshot", "[taskId={}] World snapshot for AiPlayer '{}': {}",
                taskId, aiPlayer.getAiPlayerName(), SnapshotSerializer.toCompactJson(snapshot));

            MakeItemIntent makeItemIntent = findMakeItemIntent(command, snapshot);
            if (makeItemIntent != null) {
                logRecipePlan(aiPlayer, snapshot, makeItemIntent.item(), makeItemIntent.quantity(), taskId);
                AiPlayerMod.info("planning", "[taskId={}] [Async] Using local make_item plan for AiPlayer '{}': {}",
                    taskId, aiPlayer.getAiPlayerName(), command);
                return CompletableFuture.completedFuture(makeItemResponse(
                    makeItemIntent.item(),
                    makeItemIntent.quantity(),
                    makeItemIntent.description(),
                    taskId
                ));
            }
            if (isSingleTreeCommand(command)) {
                AiPlayerMod.info("planning", "[taskId={}] [Async] Using local one-tree plan for AiPlayer '{}': {}",
                    taskId, aiPlayer.getAiPlayerName(), command);
                return CompletableFuture.completedFuture(singleTreeResponse(taskId));
            }
            String systemPrompt = PromptBuilder.buildSystemPrompt();
            WorldKnowledge worldKnowledge = new WorldKnowledge(aiPlayer);
            String userPrompt = PromptBuilder.buildUserPrompt(aiPlayer, command, worldKnowledge, snapshot);

            AiPlayerMod.info("planning", "[taskId={}] [Async] Requesting DeepSeek plan for AiPlayer '{}': {}",
                taskId, aiPlayer.getAiPlayerName(), command);

            Map<String, Object> params = Map.of(
                "systemPrompt", systemPrompt,
                "model", AiPlayerConfig.DEEPSEEK_MODEL.get(),
                "maxTokens", AiPlayerConfig.MAX_TOKENS.get(),
                "temperature", AiPlayerConfig.TEMPERATURE.get(),
                "thinkingEnabled", AiPlayerConfig.DEEPSEEK_THINKING_ENABLED.get(),
                "reasoningEffort", AiPlayerConfig.DEEPSEEK_REASONING_EFFORT.get()
            );

            return asyncDeepSeekClient.sendAsync(userPrompt, params)
                .thenApply(response -> {
                    String content = response.getContent();
                    if (content == null || content.isEmpty()) {
                        AiPlayerMod.error("planning", "[taskId={}] [Async] Empty response from DeepSeek", taskId);
                        return null;
                    }

                    ResponseParser.ParsedResponse parsed = ResponseParser.parseAIResponse(content);
                    if (parsed == null) {
                        AiPlayerMod.error("planning", "[taskId={}] [Async] Failed to parse DeepSeek response", taskId);
                        return null;
                    }

                    ResponseParser.ParsedResponse normalized = normalizeForCommand(command, parsed, snapshot, taskId);
                    AiPlayerMod.info("planning", "[taskId={}] [Async] Plan received: {} ({} tasks, {}ms, {} tokens, cache: {})",
                        taskId,
                        normalized.getPlan(),
                        normalized.getTasks().size(),
                        response.getLatencyMs(),
                        response.getTokensUsed(),
                        response.isFromCache());

                    return normalized;
                })
                .exceptionally(throwable -> {
                    AiPlayerMod.error("planning", "[taskId={}] [Async] Error planning tasks: {}",
                        taskId, throwable.getMessage());
                    return null;
                });
        } catch (Exception e) {
            AiPlayerMod.error("planning", "[taskId={}] [Async] Error setting up DeepSeek task planning", taskId, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    public LLMCache getLLMCache() {
        return llmCache;
    }

    private ResponseParser.ParsedResponse normalizeForCommand(String command, ResponseParser.ParsedResponse response, WorldSnapshot snapshot, String taskId) {
        MakeItemIntent makeItemIntent = findMakeItemIntent(command, snapshot);
        if (makeItemIntent != null) {
            return makeItemResponse(makeItemIntent.item(), makeItemIntent.quantity(), makeItemIntent.description(), taskId);
        }
        if (isSingleTreeCommand(command)) {
            return singleTreeResponse(taskId);
        }

        List<Task> normalizedTasks = new ArrayList<>();
        boolean changed = false;
        for (Task task : response.getTasks()) {
            Task normalizedTask = normalizeTask(task, snapshot);
            Task stampedTask = stampTask(normalizedTask, taskId);
            normalizedTasks.add(stampedTask);
            changed = changed || normalizedTask != task || stampedTask != normalizedTask;
        }
        if (!changed) {
            return response;
        }
        return new ResponseParser.ParsedResponse(response.getReasoning(), response.getPlan(), normalizedTasks);
    }

    private void logRecipePlan(AiPlayerEntity aiPlayer, WorldSnapshot snapshot, String targetItem, int count, String taskId) {
        RecipePlan recipePlan = recipeResolver.resolve(aiPlayer, snapshot, targetItem, count);
        if (recipePlan.isSuccess()) {
            AiPlayerMod.info("recipe", "[taskId={}] Recipe plan for AiPlayer '{}': {}",
                taskId, aiPlayer.getAiPlayerName(), recipePlan.toUserText());
            PlanSchema localPlan = PlanSchema.fromRecipePlan("make_item", recipePlan);
            PlanValidator.ValidationResult validation = planValidator.validate(localPlan, aiPlayer, snapshot, recipePlan);
            AiPlayerMod.info("recipe", "[taskId={}] Validated high-level plan for AiPlayer '{}': {} {}",
                taskId,
                aiPlayer.getAiPlayerName(),
                validation.toUserText(),
                validation.valid() ? PlanParser.toJson(validation.plan()) : "");
            AiPlayerMod.debug("planning", "[taskId={}] DeepSeek planning prompt preview for AiPlayer '{}': system={}, user={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                PlanningPromptBuilder.buildSystemPrompt(),
                PlanningPromptBuilder.buildUserPrompt("make " + targetItem, snapshot, recipePlan));
        } else {
            AiPlayerMod.warn("recipe", "[taskId={}] Recipe plan failed for AiPlayer '{}': {}",
                taskId, aiPlayer.getAiPlayerName(), recipePlan.getFailureReason());
        }
    }

    private ResponseParser.ParsedResponse singleTreeResponse(String taskId) {
        List<Task> normalizedTasks = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("resource", "tree");
        parameters.put("quantity", 1);
        parameters.put("task_id", taskId);
        normalizedTasks.add(new Task("gather", parameters));

        return new ResponseParser.ParsedResponse(
            "玩家要求砍一棵树，改为单棵树采集任务",
            "砍一棵树",
            normalizedTasks
        );
    }

    private ResponseParser.ParsedResponse makeItemResponse(String item, int quantity, String description, String taskId) {
        List<Task> normalizedTasks = new ArrayList<>();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("item", item);
        parameters.put("quantity", Math.max(1, quantity));
        parameters.put("task_id", taskId);
        normalizedTasks.add(new Task("make_item", parameters));

        return new ResponseParser.ParsedResponse(
            "玩家要求制作物品，使用观察、配方、step 执行和复盘循环",
            description,
            normalizedTasks
        );
    }

    private Task stampTask(Task task, String taskId) {
        if (task == null) {
            return null;
        }
        return task.withParameter("task_id", taskId == null || taskId.isBlank() ? "task-unknown" : taskId);
    }

    private Task normalizeTask(Task task, WorldSnapshot snapshot) {
        if (task == null) {
            return task;
        }
        String action = task.getAction();
        if (!"craft".equals(action) && !"make_item".equals(action)) {
            return task;
        }

        String item = task.getStringParameter("item", null);
        if (item == null || item.isBlank()) {
            return task;
        }
        int quantity = task.getIntParameter("quantity", task.getIntParameter("count", 1));
        String normalizedItem = recipeResolver.isGenericWoodenDoor(item)
            ? recipeResolver.chooseWoodenDoorTarget(snapshot)
            : recipeResolver.normalizeItemId(item);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("item", normalizedItem);
        parameters.put("quantity", Math.max(1, quantity));
        return new Task("make_item", parameters);
    }

    private MakeItemIntent findMakeItemIntent(String command, WorldSnapshot snapshot) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = compact(command);
        String item = detectLocalMakeItemTarget(command, snapshot, recipeResolver);
        if (item == null || isStructureIntent(normalized)) {
            return null;
        }
        if (!hasCraftingVerb(normalized) && !isItemAcquisitionIntent(normalized) && !isUtilityAcquisitionIntent(normalized, item)) {
            return null;
        }
        int quantity = extractQuantity(command);
        return new MakeItemIntent(item, quantity, "按生存流程制作 " + item + " x" + quantity);
    }

    static String detectLocalMakeItemTarget(String command, WorldSnapshot snapshot, RecipeResolver recipeResolver) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String normalized = compact(command);
        return itemFromCommand(normalized, snapshot, recipeResolver);
    }

    private static String itemFromCommand(String normalized, WorldSnapshot snapshot, RecipeResolver recipeResolver) {
        if (isWaterBucketIntent(normalized)) {
            return "minecraft:water_bucket";
        }
        if (hasCookingVerb(normalized) && containsAny(normalized, "牛肉", "beef", "steak")) {
            return "minecraft:cooked_beef";
        }
        if (containsAny(normalized, "woodendoor", "door", "木门", "门")) {
            RecipeResolver resolver = recipeResolver == null ? new RecipeResolver() : recipeResolver;
            WorldSnapshot safeSnapshot = snapshot == null ? WorldSnapshot.empty("") : snapshot;
            return resolver.chooseWoodenDoorTarget(safeSnapshot);
        }
        for (Map.Entry<String, String> entry : SurvivalRecipeBook.aliases().entrySet()
            .stream()
            .sorted((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()))
            .toList()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean hasCraftingVerb(String normalized) {
        return containsAny(normalized,
            "做", "制作", "制造", "合成", "打造", "烧", "烤", "煮", "烹饪", "打", "装",
            "craft", "make", "cook", "smelt", "grill", "fill",
            "造一个", "造一把", "造个");
    }

    private static boolean hasCookingVerb(String normalized) {
        return containsAny(normalized, "烧", "烤", "煮", "烹饪", "cook", "smelt", "grill");
    }

    private static boolean isWaterBucketIntent(String normalized) {
        return containsAny(normalized, "一桶水", "桶水", "水桶", "waterbucket", "bucketofwater");
    }

    private static boolean isUtilityAcquisitionIntent(String normalized, String item) {
        return "minecraft:water_bucket".equals(item) && isWaterBucketIntent(normalized);
    }

    private static boolean isItemAcquisitionIntent(String normalized) {
        return containsAny(normalized,
            "给我", "我要", "我想要", "拿", "取", "来一", "来个", "来把", "弄", "搞", "准备",
            "give me", "get me", "i want", "bring me");
    }

    private static boolean isStructureIntent(String normalized) {
        return containsAny(normalized, "房", "屋", "小木屋", "house", "buildhouse");
    }

    private static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(compact(pattern))) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String text) {
        return text.toLowerCase(Locale.ROOT)
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "");
    }

    private int extractQuantity(String command) {
        Matcher matcher = NUMBER_PATTERN.matcher(command);
        if (matcher.find()) {
            try {
                return Math.max(1, Math.min(64, Integer.parseInt(matcher.group(1))));
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        String normalized = compact(command);
        if (containsAny(normalized, "九")) {
            return 9;
        }
        if (containsAny(normalized, "八")) {
            return 8;
        }
        if (containsAny(normalized, "七")) {
            return 7;
        }
        if (containsAny(normalized, "六")) {
            return 6;
        }
        if (containsAny(normalized, "五")) {
            return 5;
        }
        if (containsAny(normalized, "四")) {
            return 4;
        }
        if (containsAny(normalized, "三")) {
            return 3;
        }
        if (containsAny(normalized, "两", "二")) {
            return 2;
        }
        return 1;
    }

    private boolean isSingleTreeCommand(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT).replace(" ", "");
        boolean asksOneTree = normalized.contains("一棵树")
            || normalized.contains("1棵树")
            || normalized.contains("一颗树")
            || normalized.contains("砍树")
            || normalized.contains("砍一棵")
            || normalized.contains("chopatree")
            || normalized.contains("cutatree");
        boolean asksBuilding = normalized.contains("建")
            || normalized.contains("房")
            || normalized.contains("屋")
            || normalized.contains("build")
            || normalized.contains("house");
        return asksOneTree && !asksBuilding;
    }

    private boolean isWoodenDoorCommand(String command) {
        if (command == null) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT).replace(" ", "");
        boolean mentionsDoor = normalized.contains("门") || normalized.contains("door");
        boolean asksCrafting = normalized.contains("做")
            || normalized.contains("制作")
            || normalized.contains("制造")
            || normalized.contains("合成")
            || normalized.contains("造一个")
            || normalized.contains("craft")
            || normalized.contains("make");
        return mentionsDoor && asksCrafting;
    }

    private record MakeItemIntent(String item, int quantity, String description) {
    }

    public boolean isProviderHealthy(String provider) {
        return asyncDeepSeekClient.isHealthy();
    }

    public boolean validateTask(Task task) {
        String action = task.getAction();

        return switch (action) {
            case "pathfind" -> task.hasParameters("x", "y", "z");
            case "mine" -> task.hasParameters("block", "quantity");
            case "place" -> task.hasParameters("block", "x", "y", "z");
            case "craft" -> task.hasParameters("item", "quantity");
            case "attack" -> task.hasParameters("target");
            case "follow" -> task.hasParameters("player");
            case "gather" -> task.hasParameters("resource", "quantity");
            case "make_item" -> task.hasParameters("item", "quantity");
            case "build" -> task.hasParameters("structure", "blocks", "dimensions");
            default -> {
                AiPlayerMod.warn("planning", "Unknown action type: {}", action);
                yield false;
            }
        };
    }

    public List<Task> validateAndFilterTasks(List<Task> tasks) {
        return tasks.stream()
            .filter(this::validateTask)
            .toList();
    }
}
