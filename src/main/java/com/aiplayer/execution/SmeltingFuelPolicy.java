package com.aiplayer.execution;

import com.aiplayer.recipe.SurvivalRecipeBook;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SmeltingFuelPolicy {
    private SmeltingFuelPolicy() {
    }

    public static boolean isFuelRequirement(String itemId) {
        return SurvivalRecipeBook.isFurnaceFuelRequirement(itemId);
    }

    public static List<String> fuelItems() {
        return SurvivalRecipeBook.furnaceFuelItems();
    }

    public static int countFuel(Map<String, Integer> inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String item : fuelItems()) {
            count += Math.max(0, inventory.getOrDefault(item, 0));
        }
        return count;
    }

    public static String fuelSummary(Map<String, Integer> inventory) {
        Map<String, Integer> present = new TreeMap<>();
        if (inventory != null) {
            for (String item : fuelItems()) {
                int count = inventory.getOrDefault(item, 0);
                if (count > 0) {
                    present.put(item, count);
                }
            }
        }
        return present.toString();
    }

    public static boolean canAllocateFuelAfterInputs(
        Map<String, Integer> inventory,
        Map<String, Integer> nonFuelInputs,
        int fuelNeeded
    ) {
        Map<String, Integer> remaining = new TreeMap<>();
        if (inventory != null) {
            remaining.putAll(inventory);
        }
        if (nonFuelInputs != null) {
            for (Map.Entry<String, Integer> entry : nonFuelInputs.entrySet()) {
                int available = remaining.getOrDefault(entry.getKey(), 0);
                int needed = Math.max(0, entry.getValue());
                if (available < needed) {
                    return false;
                }
                remaining.put(entry.getKey(), available - needed);
            }
        }
        return countFuel(remaining) >= Math.max(0, fuelNeeded);
    }
}
