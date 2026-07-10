package io.github.zoyluo.aibot.goal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Immutable facts collected once at a Goal decision boundary. Registry identifiers are stable strings. */
public record GoalSnapshot(
        Map<String, Integer> inventory,
        int bestPickaxeTier,
        Set<String> equipmentCapabilities,
        Map<String, Integer> nearbyBlocks,
        Map<String, Integer> containerItems,
        int foodUnits,
        Optional<StructureReport> structure
) {
    public GoalSnapshot {
        inventory = inventory == null ? Map.of() : Map.copyOf(inventory);
        bestPickaxeTier = Math.max(0, bestPickaxeTier);
        equipmentCapabilities = equipmentCapabilities == null ? Set.of() : Set.copyOf(equipmentCapabilities);
        nearbyBlocks = nearbyBlocks == null ? Map.of() : Map.copyOf(nearbyBlocks);
        containerItems = containerItems == null ? Map.of() : Map.copyOf(containerItems);
        foodUnits = Math.max(0, foodUnits);
        structure = structure == null ? Optional.empty() : structure;
    }

    public static GoalSnapshot empty() {
        return new GoalSnapshot(Map.of(), 0, Set.of(), Map.of(), Map.of(), 0, Optional.empty());
    }

    public int inventoryCount(String itemId) {
        return inventory.getOrDefault(itemId, 0);
    }

    public int containerCount(String itemId) {
        return containerItems.getOrDefault(itemId, 0);
    }

    public int nearbyBlockCount(String blockId) {
        return nearbyBlocks.getOrDefault(blockId, 0);
    }
}
