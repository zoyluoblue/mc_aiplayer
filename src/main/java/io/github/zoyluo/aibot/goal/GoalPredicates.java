package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.action.HarvestCore;
import net.minecraft.registry.Registries;

import java.util.Set;
import java.util.stream.Collectors;

public final class GoalPredicates {
    public static final Set<String> ARMOR_CAPABILITIES = Set.of("helmet", "chestplate", "leggings", "boots", "sword");

    private GoalPredicates() {
    }

    public static GoalPredicate forGoal(Goal goal) {
        return switch (goal) {
            case Goal.HaveItem haveItem -> new GoalPredicate.ItemCount(
                    Registries.ITEM.getId(haveItem.item()).toString(), haveItem.count());
            case Goal.HavePickaxeTier pickaxe -> new GoalPredicate.PickaxeTier(pickaxe.tier());
            case Goal.MineOre mineOre -> new GoalPredicate.AnyItemCount(
                    HarvestCore.expectedDropsFor(mineOre.ores()).stream()
                            .map(item -> Registries.ITEM.getId(item).toString())
                            .collect(Collectors.toSet()),
                    mineOre.count(), "ore_drops");
            case Goal.HarvestCrop crop -> new GoalPredicate.ItemCount(
                    Registries.ITEM.getId(crop.produce()).toString(), crop.count());
            case Goal.Armor ignored -> new GoalPredicate.ArmorSet(ARMOR_CAPABILITIES);
            case Goal.Workstation ignored -> new GoalPredicate.Workstation();
            case Goal.Stockpile stockpile -> new GoalPredicate.Stockpile(
                    Registries.ITEM.getId(stockpile.item()).toString(), stockpile.count());
            case Goal.Food food -> new GoalPredicate.FoodUnits(food.cookedCount());
            case Goal.Build build -> new GoalPredicate.Structure(build.blueprint());
        };
    }
}
