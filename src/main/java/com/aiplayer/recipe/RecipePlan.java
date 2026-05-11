package com.aiplayer.recipe;

import java.util.List;
import java.util.Map;

public final class RecipePlan {
    private final MaterialRequirement target;
    private final boolean success;
    private final String failureReason;
    private final List<RecipeNode> recipeChain;
    private final List<MaterialRequirement> missingBaseResources;
    private final Map<String, Integer> availableItems;

    private RecipePlan(
        MaterialRequirement target,
        boolean success,
        String failureReason,
        List<RecipeNode> recipeChain,
        List<MaterialRequirement> missingBaseResources,
        Map<String, Integer> availableItems
    ) {
        this.target = target;
        this.success = success;
        this.failureReason = failureReason;
        this.recipeChain = List.copyOf(recipeChain);
        this.missingBaseResources = List.copyOf(missingBaseResources);
        this.availableItems = Map.copyOf(availableItems);
    }

    public static RecipePlan success(
        MaterialRequirement target,
        List<RecipeNode> recipeChain,
        List<MaterialRequirement> missingBaseResources,
        Map<String, Integer> availableItems
    ) {
        return new RecipePlan(target, true, "", recipeChain, missingBaseResources, availableItems);
    }

    public static RecipePlan failure(MaterialRequirement target, String failureReason, Map<String, Integer> availableItems) {
        return new RecipePlan(target, false, failureReason, List.of(), List.of(), availableItems);
    }

    public MaterialRequirement getTarget() {
        return target;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public List<RecipeNode> getRecipeChain() {
        return recipeChain;
    }

    public List<MaterialRequirement> getMissingBaseResources() {
        return missingBaseResources;
    }

    public Map<String, Integer> getAvailableItems() {
        return availableItems;
    }

    public String toUserText() {
        if (!success) {
            return "配方解析失败：" + failureReason;
        }
        if (recipeChain.isEmpty()) {
            return target + " 已经在 AI 背包中，无需额外材料。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("目标：").append(target).append("\n");
        for (int i = 0; i < recipeChain.size(); i++) {
            builder.append(i + 1).append(". ").append(recipeChain.get(i).toUserText()).append("\n");
        }
        return builder.toString().stripTrailing();
    }

    @Override
    public String toString() {
        return toUserText();
    }
}
