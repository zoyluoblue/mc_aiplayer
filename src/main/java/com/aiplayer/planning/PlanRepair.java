package com.aiplayer.planning;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.Optional;

public final class PlanRepair {
    private final PlanValidator validator;

    public PlanRepair(PlanValidator validator) {
        this.validator = validator;
    }

    public PlanValidator.ValidationResult parseValidateOrFallback(
        String json,
        AiPlayerEntity aiPlayer,
        WorldSnapshot snapshot,
        RecipePlan recipePlan
    ) {
        Optional<PlanSchema> parsed = PlanParser.parse(json);
        if (parsed.isPresent()) {
            PlanValidator.ValidationResult validation = validator.validate(parsed.get(), aiPlayer, snapshot, recipePlan);
            if (validation.valid()) {
                return validation;
            }
        }
        if (recipePlan != null && recipePlan.isSuccess()) {
            PlanSchema fallback = PlanSchema.fromRecipePlan("make_item", recipePlan);
            return validator.validate(fallback, aiPlayer, snapshot, recipePlan);
        }
        return PlanValidator.ValidationResult.invalid(java.util.List.of("DeepSeek 计划无法解析，且没有可用 fallback"));
    }
}
