package com.aiplayer.agent;

import com.aiplayer.execution.ExecutionStep;
import com.aiplayer.llm.SurvivalPrompt;
import com.aiplayer.snapshot.WorldSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Locale;

public final class FailureRecoveryAdvisor {
    private final ActionManifest manifest;

    public FailureRecoveryAdvisor() {
        this(ActionManifest.survivalDefaults());
    }

    public FailureRecoveryAdvisor(ActionManifest manifest) {
        this.manifest = manifest == null ? ActionManifest.survivalDefaults() : manifest;
    }

    public AgentFailureAdvice review(
        String targetItem,
        ExecutionStep currentStep,
        WorldSnapshot snapshot,
        List<String> recentFailures,
        String deepSeekJson
    ) {
        return review(targetItem, currentStep, snapshot, recentFailures, deepSeekJson, List.of());
    }

    public AgentFailureAdvice review(
        String targetItem,
        ExecutionStep currentStep,
        WorldSnapshot snapshot,
        List<String> recentFailures,
        String deepSeekJson,
        List<String> relevantSkills
    ) {
        String latestFailure = latestFailure(recentFailures);
        FailureCategory category = classify(latestFailure);
        String prompt = buildDeepSeekPrompt(targetItem, currentStep, snapshot, recentFailures, category, relevantSkills);
        AgentFailureAdvice deepSeekAdvice = parseDeepSeekAdvice(deepSeekJson, category, prompt);
        if (deepSeekAdvice != null) {
            return deepSeekAdvice;
        }
        return localFallback(category, latestFailure, prompt);
    }

    public FailureCategory classify(String failureMessage) {
        String text = failureMessage == null ? "" : failureMessage.toLowerCase(Locale.ROOT);
        if (containsAny(text, "材料不足", "缺少材料", "missing material")) return FailureCategory.MATERIAL_MISSING;
        if (containsAny(text, "缺少镐", "缺少石镐", "缺少铁镐", "缺少工具", "tool")) return FailureCategory.TOOL_MISSING;
        if (containsAny(text, "配方", "没有找到可用配方", "recipe")) return FailureCategory.RECIPE_MISSING;
        if (containsAny(text, "不可达", "无法到达", "unreachable")) return FailureCategory.TARGET_UNREACHABLE;
        if (containsAny(text, "分支矿道", "branch_blocked", "no_air_neighbor", "探矿超时", "下探", "矿层")) return FailureCategory.MINING_ROUTE_FAILED;
        if (containsAny(text, "找不到", "未找到", "没有找到", "resource")) return FailureCategory.RESOURCE_NOT_FOUND;
        if (containsAny(text, "卡住", "stuck")) return FailureCategory.PATH_STUCK;
        if (containsAny(text, "危险", "敌对", "danger", "hostile")) return FailureCategory.DANGER;
        if (containsAny(text, "背包满", "inventory full")) return FailureCategory.INVENTORY_FULL;
        if (containsAny(text, "工作站", "熔炉", "工作台", "station", "furnace")) return FailureCategory.STATION_MISSING;
        if (containsAny(text, "非法", "禁止", "未声明", "illegal")) return FailureCategory.ILLEGAL_DEEPSEEK_OUTPUT;
        return FailureCategory.UNKNOWN;
    }

    private AgentFailureAdvice parseDeepSeekAdvice(String json, FailureCategory category, String prompt) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            String action = object.has("action") ? object.get("action").getAsString() : "";
            if (!manifest.isDeepSeekCallable(action)) {
                return null;
            }
            String strategy = object.has("strategy") ? object.get("strategy").getAsString() : "continue";
            if (isForbiddenStrategy(strategy)) {
                return null;
            }
            String message = object.has("message") ? object.get("message").getAsString() : "采用 DeepSeek 复盘建议：" + strategy;
            return new AgentFailureAdvice(FailureAdviceSource.DEEPSEEK, category, strategy, action, message, prompt, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private AgentFailureAdvice localFallback(FailureCategory category, String failure, String prompt) {
        return switch (category) {
            case MATERIAL_MISSING -> local(category, "rebuild_recipe_chain", "make_item", "材料不足，重新观察背包和箱子后递归补齐材料", prompt);
            case TOOL_MISSING -> local(category, "craft_required_tool", "make_item", "工具不足，先制作或切换所需工具", prompt);
            case TARGET_UNREACHABLE, PATH_STUCK -> local(category, "stop_and_return", "return_to_owner", "目标不可达或路径卡住，记录原因并结束任务，回到玩家身边", prompt);
            case RESOURCE_NOT_FOUND -> local(category, "prospect_or_ask_user", "gather_resource", "附近资源不足，继续探矿或请求玩家移动/提供材料", prompt);
            case MINING_ROUTE_FAILED -> local(category, "continue_downward_or_shift_mining_layer", "gather_resource", "挖矿路线失败，继续下探、换矿层或换分支后再搜索", prompt);
            case STATION_MISSING -> local(category, "craft_and_place_station", "craft", "缺少工作站，先制作并放置可交互工作站", prompt);
            case DANGER -> local(category, "return_to_owner", "follow_player", "环境危险，优先回到玩家附近", prompt);
            case INVENTORY_FULL -> local(category, "deposit_or_ask_user", "deposit_to_chest", "背包空间不足，尝试放入附近箱子或请求玩家清理", prompt);
            case ILLEGAL_DEEPSEEK_OUTPUT -> local(category, "reject_and_use_local_plan", "make_item", "DeepSeek 输出非法，拒绝该建议并使用本地配方计划", prompt);
            case RECIPE_MISSING -> local(category, "ask_user", "stop", "没有可用配方或基础来源，需要向玩家说明无法完成", prompt);
            case UNKNOWN -> local(category, "observe_and_replan", "make_item", failure == null || failure.isBlank() ? "未知失败，重新观察并复盘" : failure, prompt);
        };
    }

    private AgentFailureAdvice local(FailureCategory category, String strategy, String action, String message, String prompt) {
        return new AgentFailureAdvice(FailureAdviceSource.LOCAL_FALLBACK, category, strategy, action, message, prompt, false);
    }

    private String buildDeepSeekPrompt(
        String targetItem,
        ExecutionStep currentStep,
        WorldSnapshot snapshot,
        List<String> failures,
        FailureCategory category,
        List<String> relevantSkills
    ) {
        AgentContext context = AgentContext.from(snapshot, null, currentStep, failures, List.of(), manifest, relevantSkills);
        return SurvivalPrompt.sharedContext() + "\n" + """
            你是 Minecraft 生存 AI 的失败复盘器。只输出 JSON。
            只能给策略建议，不能编造物品、配方、箱子内容或创造模式能力。
            failureCategory=%s
            targetItem=%s
            allowedActions=%s
            context=%s
            输出格式：{"strategy":"continue_downward_mining","action":"gather_resource","message":"..."}
            """.formatted(category, targetItem, manifest.names(), context.toJson());
    }

    private boolean isForbiddenStrategy(String strategy) {
        String text = strategy == null ? "" : strategy.toLowerCase(Locale.ROOT);
        return containsAny(text, "give", "creative", "teleport", "summon", "setblock", "spawn_item");
    }

    private String latestFailure(List<String> failures) {
        if (failures == null || failures.isEmpty()) {
            return "";
        }
        return failures.get(failures.size() - 1);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
