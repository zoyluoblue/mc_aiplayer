package com.aiplayer.agent;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.planning.PlanSchema;
import com.aiplayer.planning.PlanValidator;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.List;

public final class AgentPlanGuard {
    private final PlanValidator validator;

    public AgentPlanGuard(PlanValidator validator) {
        this.validator = validator;
    }

    public GuardResult validate(PlanSchema plan, AiPlayerEntity aiPlayer, WorldSnapshot snapshot, RecipePlan recipePlan) {
        PlanValidator.ValidationResult result = validator.validate(plan, aiPlayer, snapshot, recipePlan);
        if (result.valid()) {
            return new GuardResult(true, result.plan(), List.of(), result.corrected());
        }
        List<PlanViolation> violations = result.errors()
            .stream()
            .map(error -> new PlanViolation("plan", error, repairHint(error)))
            .toList();
        return new GuardResult(false, null, violations, false);
    }

    private String repairHint(String error) {
        if (error.contains("创造") || error.contains("命令式")) {
            return "改为 make_item、gather、craft_station 等生存动作";
        }
        if (error.contains("非法物品")) {
            return "使用 Minecraft 注册表中存在的物品 ID";
        }
        if (error.contains("count")) {
            return "把数量改为正整数";
        }
        return "重新生成符合本地配方和动作白名单的计划";
    }

    public record GuardResult(boolean valid, PlanSchema plan, List<PlanViolation> violations, boolean corrected) {
    }
}
