package com.aiplayer.planning;

import com.aiplayer.recipe.MaterialRequirement;
import com.aiplayer.recipe.RecipeNode;
import com.aiplayer.recipe.RecipePlan;

import java.util.ArrayList;
import java.util.List;

public final class PlanSchema {
    private String goal;
    private PlanTarget target;
    private List<PlanStep> plan;
    private String replanAfter;
    private String reason;

    public PlanSchema() {
        this.plan = new ArrayList<>();
    }

    public PlanSchema(String goal, PlanTarget target, List<PlanStep> plan, String replanAfter, String reason) {
        this.goal = goal;
        this.target = target;
        this.plan = new ArrayList<>(plan);
        this.replanAfter = replanAfter;
        this.reason = reason;
    }

    public static PlanSchema fromRecipePlan(String goal, RecipePlan recipePlan) {
        List<PlanStep> steps = new ArrayList<>();
        for (RecipeNode node : recipePlan.getRecipeChain()) {
            MaterialRequirement output = node.getOutput();
            switch (node.getType()) {
                case "withdraw" -> steps.add(PlanStep.withdraw(output.getItem(), output.getCount(), node.getSource()));
                case "gather" -> steps.add(PlanStep.gather(node.getSource(), output.getItem(), output.getCount()));
                case "craft" -> steps.add(PlanStep.craft(output.getItem(), output.getCount(), node.getStation()));
                default -> {
                }
            }
        }
        return new PlanSchema(
            goal,
            new PlanTarget(recipePlan.getTarget().getItem(), recipePlan.getTarget().getCount()),
            steps,
            "each_step",
            "基于本地配方递归和当前观察状态生成"
        );
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public PlanTarget getTarget() {
        return target;
    }

    public void setTarget(PlanTarget target) {
        this.target = target;
    }

    public List<PlanStep> getPlan() {
        return plan == null ? List.of() : List.copyOf(plan);
    }

    public void setPlan(List<PlanStep> plan) {
        this.plan = plan == null ? new ArrayList<>() : new ArrayList<>(plan);
    }

    public String getReplanAfter() {
        return replanAfter;
    }

    public void setReplanAfter(String replanAfter) {
        this.replanAfter = replanAfter;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
