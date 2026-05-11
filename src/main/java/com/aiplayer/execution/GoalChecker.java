package com.aiplayer.execution;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.recipe.SurvivalRecipeBook;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class GoalChecker {
    public boolean hasItem(AiPlayerEntity aiPlayer, String itemId, int count) {
        Item item = itemFromId(itemId);
        return item != Items.AIR && aiPlayer.getItemCount(item) >= count;
    }

    public int countItem(AiPlayerEntity aiPlayer, String itemId) {
        if (SurvivalRecipeBook.isGenericWoodMaterialRequirement(itemId)) {
            return SurvivalRecipeBook.equivalentMaterialItems(itemId)
                .stream()
                .map(this::itemFromId)
                .filter(item -> item != Items.AIR)
                .mapToInt(aiPlayer::getItemCount)
                .sum();
        }
        Item item = itemFromId(itemId);
        return item == Items.AIR ? 0 : aiPlayer.getItemCount(item);
    }

    public boolean isComplete(AiPlayerEntity aiPlayer, String itemId, int count) {
        return hasItem(aiPlayer, itemId, count);
    }

    private Item itemFromId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Items.AIR;
        }
        try {
            return BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(itemId));
        } catch (RuntimeException e) {
            return Items.AIR;
        }
    }
}
