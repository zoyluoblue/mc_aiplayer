package com.aiplayer.planning;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.recipe.RecipePlan;
import com.aiplayer.recipe.RecipeResolver;
import com.aiplayer.snapshot.WorldSnapshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PlanValidator {
    private static final Set<String> ALLOWED_STEPS = Set.of(
        "gather_tree",
        "gather_stone",
        "gather",
        "craft_inventory",
        "craft_station",
        "fill_water",
        "withdraw_chest",
        "deposit_chest",
        "place_block",
        "return_to_owner"
    );
    private static final Set<String> FORBIDDEN_STEPS = Set.of(
        "creative",
        "give",
        "summon",
        "teleport",
        "tp",
        "fill",
        "setblock",
        "spawn_item"
    );

    private final RecipeResolver recipeResolver;

    public PlanValidator(RecipeResolver recipeResolver) {
        this.recipeResolver = recipeResolver;
    }

    public ValidationResult validate(PlanSchema plan, AiPlayerEntity aiPlayer, WorldSnapshot snapshot, RecipePlan recipePlan) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("计划为空");
            return ValidationResult.invalid(errors);
        }
        if (plan.getGoal() == null || plan.getGoal().isBlank()) {
            errors.add("缺少 goal");
        }
        PlanTarget target = plan.getTarget();
        if (target == null || target.getItem() == null || target.getItem().isBlank()) {
            errors.add("缺少 target.item");
        } else if (!isKnownItem(recipeResolver.normalizeItemId(target.getItem()))) {
            errors.add("非法物品 ID：" + target.getItem());
        }
        if (target != null && target.getCount() <= 0) {
            errors.add("target.count 必须大于 0");
        }

        for (PlanStep step : plan.getPlan()) {
            validateStep(step, errors);
        }
        if (!errors.isEmpty()) {
            return ValidationResult.invalid(errors);
        }

        PlanSchema corrected = correctWithRecipePlan(plan, recipePlan);
        boolean correctedPlan = corrected != plan;
        return ValidationResult.valid(corrected, correctedPlan);
    }

    private void validateStep(PlanStep step, List<String> errors) {
        if (step == null) {
            errors.add("计划包含空 step");
            return;
        }
        String stepName = normalizeStepName(step.getStep());
        if (stepName == null || stepName.isBlank()) {
            errors.add("step 缺少名称");
            return;
        }
        if (FORBIDDEN_STEPS.contains(stepName)) {
            errors.add("禁止使用创造或命令式能力：" + stepName);
            return;
        }
        if (!ALLOWED_STEPS.contains(stepName)) {
            errors.add("未声明的 step：" + stepName);
        }
        if (step.getCount() <= 0) {
            errors.add(stepName + " 的 count 必须大于 0");
        }
        if ("craft_station".equals(stepName)) {
            if (step.getStation() == null || step.getStation().isBlank() || "inventory".equals(step.getStation())) {
                errors.add("craft_station 必须声明有效 station");
            }
        }
        if (step.getItem() != null && !step.getItem().isBlank()) {
            String item = recipeResolver.normalizeItemId(step.getItem());
            if (!isKnownItem(item)) {
                errors.add("step 中非法物品 ID：" + step.getItem());
            }
        }
    }

    private PlanSchema correctWithRecipePlan(PlanSchema plan, RecipePlan recipePlan) {
        if (recipePlan == null || !recipePlan.isSuccess() || plan.getTarget() == null) {
            return plan;
        }
        String targetItem = recipeResolver.normalizeItemId(plan.getTarget().getItem());
        if (!recipePlan.getTarget().getItem().equals(targetItem)) {
            return plan;
        }
        PlanSchema expected = PlanSchema.fromRecipePlan(plan.getGoal(), recipePlan);
        if (sameSteps(plan, expected)) {
            return plan;
        }
        expected.setReason("DeepSeek 计划已由本地配方系统纠正材料数量和步骤");
        return expected;
    }

    private boolean sameSteps(PlanSchema left, PlanSchema right) {
        List<PlanStep> leftSteps = left.getPlan();
        List<PlanStep> rightSteps = right.getPlan();
        if (leftSteps.size() != rightSteps.size()) {
            return false;
        }
        for (int i = 0; i < leftSteps.size(); i++) {
            PlanStep a = leftSteps.get(i);
            PlanStep b = rightSteps.get(i);
            if (!normalizeStepName(a.getStep()).equals(normalizeStepName(b.getStep()))) {
                return false;
            }
            if (!safeEquals(recipeResolver.normalizeItemId(a.getItem()), recipeResolver.normalizeItemId(b.getItem()))) {
                return false;
            }
            if (a.getCount() != b.getCount()) {
                return false;
            }
            if (!safeEquals(normalizeStation(a.getStation()), normalizeStation(b.getStation()))) {
                return false;
            }
        }
        return true;
    }

    private String normalizeStepName(String step) {
        if (step == null) {
            return "";
        }
        return switch (step) {
            case "withdraw" -> "withdraw_chest";
            case "craft" -> "craft_station";
            case "gather_tree" -> "gather_tree";
            case "gather_stone" -> "gather_stone";
            default -> step;
        };
    }

    private String normalizeStation(String station) {
        return station == null || station.isBlank() ? null : station;
    }

    private boolean isKnownItem(String item) {
        if (item == null || item.isBlank()) {
            return false;
        }
        try {
            Item value = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(item));
            return value != Items.AIR || "minecraft:air".equals(item);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean safeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    public record ValidationResult(boolean valid, PlanSchema plan, List<String> errors, boolean corrected) {
        public static ValidationResult valid(PlanSchema plan, boolean corrected) {
            return new ValidationResult(true, plan, List.of(), corrected);
        }

        public static ValidationResult invalid(List<String> errors) {
            return new ValidationResult(false, null, List.copyOf(errors), false);
        }

        public String toUserText() {
            if (valid) {
                return corrected ? "计划已通过验证，并按本地配方纠正。" : "计划已通过验证。";
            }
            return "计划验证失败：" + errors;
        }
    }
}
