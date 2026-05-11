package com.aiplayer.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AgentPlanProtocol {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private AgentPlanProtocol() {
    }

    public static String toJson(AgentPlan plan) {
        return GSON.toJson(plan);
    }

    public static Optional<AgentPlan> parse(String json) {
        try {
            return Optional.ofNullable(GSON.fromJson(json, AgentPlan.class));
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    public static List<String> validateProtocol(AgentPlan plan, ActionManifest manifest) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            return List.of("AgentPlan 为空");
        }
        if (plan.goal() == null || plan.goal().isBlank()) {
            errors.add("缺少 goal");
        }
        if (plan.targetItem() == null || plan.targetItem().isBlank()) {
            errors.add("缺少 targetItem");
        }
        if (plan.steps().isEmpty() && !plan.needUserHelp()) {
            errors.add("没有 step，且未声明需要玩家帮助");
        }
        ActionManifest safeManifest = manifest == null ? ActionManifest.survivalDefaults() : manifest;
        for (AgentPlanStep step : plan.steps()) {
            if (step.stepId() == null || step.stepId().isBlank()) {
                errors.add("step 缺少 stepId");
            }
            if (!safeManifest.isAllowed(step.action())) {
                errors.add("未声明动作：" + step.action());
            }
            errors.addAll(safeManifest.validate(step.action(), step.parameters()));
            if (step.expectedResult() == null || step.expectedResult().isBlank()) {
                errors.add(step.stepId() + " 缺少 expectedResult");
            }
        }
        return errors;
    }
}
