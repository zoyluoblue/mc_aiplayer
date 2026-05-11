package com.aiplayer.recipe;

import java.util.Optional;

public final class BasicSourceResolver {
    public Optional<RecipeNode> gatherNode(String item, int count) {
        if (isLog(item)) {
            return Optional.of(RecipeNode.gather(MaterialRequirement.of(item, count), "tree"));
        }
        return SurvivalRecipeBook.baseSource(item)
            .map(source -> RecipeNode.gather(MaterialRequirement.of(item, count), source));
    }

    public boolean isBaseResource(String item) {
        return gatherNode(item, 1).isPresent();
    }

    public static boolean isLog(String item) {
        return item.endsWith("_log")
            && !item.contains("stripped_")
            && item.startsWith("minecraft:");
    }
}
