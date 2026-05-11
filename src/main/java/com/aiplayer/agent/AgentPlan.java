package com.aiplayer.agent;

import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record AgentPlan(
    String goal,
    String targetItem,
    int targetCount,
    List<String> assumptions,
    List<AgentPlanStep> steps,
    List<String> successCriteria,
    List<String> fallbacks,
    boolean needUserHelp
) {
    public AgentPlan {
        targetCount = Math.max(1, targetCount);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        successCriteria = successCriteria == null ? List.of() : List.copyOf(successCriteria);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }

    public static AgentPlan fromPlanSchema(PlanSchema schema) {
        if (schema == null || schema.getTarget() == null) {
            return new AgentPlan("", "", 1, List.of(), List.of(), List.of(), List.of("ask_user"), true);
        }
        List<AgentPlanStep> converted = new ArrayList<>();
        int index = 1;
        for (PlanStep step : schema.getPlan()) {
            converted.add(new AgentPlanStep(
                "step-" + index++,
                step.getStep(),
                Map.of(
                    "item", step.getItem() == null ? "" : step.getItem(),
                    "resource", step.getResource() == null ? "" : step.getResource(),
                    "count", step.getCount(),
                    "station", step.getStation() == null ? "" : step.getStation()
                ),
                step.getReason() == null ? "完成 step" : step.getReason(),
                List.of("local_recipe_plan", "world_snapshot"),
                "失败时重新观察并进入复盘"
            ));
        }
        return new AgentPlan(
            schema.getGoal(),
            schema.getTarget().getItem(),
            schema.getTarget().getCount(),
            List.of("Minecraft 事实以本地配方和世界快照为准"),
            converted,
            List.of("AI 背包中目标物品数量达到要求"),
            List.of("local_recipe_fallback", "ask_user_for_materials"),
            false
        );
    }
}
