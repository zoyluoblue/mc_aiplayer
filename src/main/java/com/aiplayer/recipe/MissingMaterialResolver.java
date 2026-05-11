package com.aiplayer.recipe;

import com.aiplayer.snapshot.ChestSnapshot;
import com.aiplayer.snapshot.InventorySnapshot;
import com.aiplayer.snapshot.WorldSnapshot;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MissingMaterialResolver {
    private final Map<String, Integer> backpackItems = new HashMap<>();
    private final Map<String, Integer> chestItems = new HashMap<>();

    private MissingMaterialResolver() {
    }

    private MissingMaterialResolver(Map<String, Integer> backpackItems, Map<String, Integer> chestItems) {
        this.backpackItems.putAll(backpackItems);
        this.chestItems.putAll(chestItems);
    }

    static MissingMaterialResolver fromItems(Map<String, Integer> backpackItems, Map<String, Integer> chestItems) {
        return new MissingMaterialResolver(backpackItems, chestItems);
    }

    public static MissingMaterialResolver fromSnapshot(WorldSnapshot snapshot) {
        MissingMaterialResolver resolver = new MissingMaterialResolver();
        for (InventorySnapshot stack : snapshot.getAi().getInventory()) {
            resolver.backpackItems.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        for (ChestSnapshot chest : snapshot.getNearbyChests()) {
            if (!chest.isReachable()) {
                continue;
            }
            for (InventorySnapshot stack : chest.getItems()) {
                resolver.chestItems.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return resolver;
    }

    public TakeResult takeForIngredient(String item, int count) {
        int remaining = Math.max(0, count);
        Map<String, Integer> fromBackpackItems = takeAny(backpackItems, item, remaining);
        int fromBackpack = sum(fromBackpackItems);
        remaining -= fromBackpack;
        Map<String, Integer> fromChestItems = takeAny(chestItems, item, remaining);
        int fromChest = sum(fromChestItems);
        remaining -= fromChest;
        return new TakeResult(fromBackpack, fromChest, remaining, fromBackpackItems, fromChestItems);
    }

    public int withdrawToBackpack(String item, int count) {
        return withdrawToBackpackDetailed(item, count).fromChest();
    }

    public TakeResult withdrawToBackpackDetailed(String item, int count) {
        int requested = Math.max(0, count);
        Map<String, Integer> movedItems = takeAny(chestItems, item, requested);
        int moved = sum(movedItems);
        movedItems.forEach(this::addBackpack);
        return new TakeResult(0, moved, requested - moved, Map.of(), movedItems);
    }

    public void addBackpack(String item, int count) {
        if (count > 0) {
            backpackItems.merge(item, count, Integer::sum);
        }
    }

    public int consumeBackpack(String item, int count) {
        return sum(takeAny(backpackItems, item, Math.max(0, count)));
    }

    public int getBackpackCount(String item) {
        if (SurvivalRecipeBook.isGenericWoodMaterialRequirement(item)) {
            return SurvivalRecipeBook.equivalentMaterialItems(item)
                .stream()
                .mapToInt(candidate -> backpackItems.getOrDefault(candidate, 0))
                .sum();
        }
        return backpackItems.getOrDefault(item, 0);
    }

    public int getChestCount(String item) {
        if (SurvivalRecipeBook.isGenericWoodMaterialRequirement(item)) {
            return SurvivalRecipeBook.equivalentMaterialItems(item)
                .stream()
                .mapToInt(candidate -> chestItems.getOrDefault(candidate, 0))
                .sum();
        }
        return chestItems.getOrDefault(item, 0);
    }

    public Map<String, Integer> availableItems() {
        Map<String, Integer> available = new HashMap<>(backpackItems);
        chestItems.forEach((item, count) -> available.merge(item, count, Integer::sum));
        return Map.copyOf(available);
    }

    public MissingMaterialResolver copy() {
        return new MissingMaterialResolver(backpackItems, chestItems);
    }

    public void replaceWith(MissingMaterialResolver other) {
        backpackItems.clear();
        backpackItems.putAll(other.backpackItems);
        chestItems.clear();
        chestItems.putAll(other.chestItems);
    }

    private static int take(Map<String, Integer> source, String item, int count) {
        if (count <= 0) {
            return 0;
        }
        int current = source.getOrDefault(item, 0);
        int taken = Math.min(current, count);
        if (taken <= 0) {
            return 0;
        }
        int remaining = current - taken;
        if (remaining <= 0) {
            source.remove(item);
        } else {
            source.put(item, remaining);
        }
        return taken;
    }

    private static Map<String, Integer> takeAny(Map<String, Integer> source, String item, int count) {
        Map<String, Integer> takenItems = new LinkedHashMap<>();
        int remaining = Math.max(0, count);
        for (String candidate : SurvivalRecipeBook.equivalentMaterialItems(item)) {
            if (remaining <= 0) {
                break;
            }
            int taken = take(source, candidate, remaining);
            if (taken > 0) {
                takenItems.merge(candidate, taken, Integer::sum);
                remaining -= taken;
            }
        }
        return takenItems;
    }

    private static int sum(Map<String, Integer> items) {
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }

    public record TakeResult(
        int fromBackpack,
        int fromChest,
        int missing,
        Map<String, Integer> fromBackpackItems,
        Map<String, Integer> fromChestItems
    ) {
        public TakeResult {
            fromBackpackItems = Map.copyOf(fromBackpackItems);
            fromChestItems = Map.copyOf(fromChestItems);
        }
    }
}
