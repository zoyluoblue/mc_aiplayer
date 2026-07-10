package io.github.zoyluo.aibot.goal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface GoalPredicate permits GoalPredicate.ItemCount,
        GoalPredicate.PickaxeTier,
        GoalPredicate.AnyItemCount,
        GoalPredicate.ArmorSet,
        GoalPredicate.Workstation,
        GoalPredicate.Stockpile,
        GoalPredicate.FoodUnits,
        GoalPredicate.Structure {
    GoalEvaluation evaluate(GoalSnapshot snapshot);

    record ItemCount(String itemId, int count) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = snapshot.inventoryCount(itemId);
            return GoalEvaluation.count(actual, count, Map.of("item", itemId, "actual", String.valueOf(actual)),
                    "missing_item:" + itemId);
        }
    }

    record PickaxeTier(int tier) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = snapshot.bestPickaxeTier();
            return GoalEvaluation.count(actual, tier, Map.of("best_pickaxe_tier", String.valueOf(actual)),
                    "pickaxe_tier_below:" + tier);
        }
    }

    record AnyItemCount(Set<String> itemIds, int count, String evidenceKey) implements GoalPredicate {
        public AnyItemCount {
            itemIds = Set.copyOf(itemIds);
        }

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = itemIds.stream().mapToInt(snapshot::inventoryCount).sum();
            return GoalEvaluation.count(actual, count,
                    Map.of(evidenceKey, String.valueOf(actual), "items", String.join(",", itemIds)),
                    "insufficient_" + evidenceKey);
        }
    }

    record ArmorSet(Set<String> requiredCapabilities) implements GoalPredicate {
        public ArmorSet {
            requiredCapabilities = Set.copyOf(requiredCapabilities);
        }

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int matched = 0;
            java.util.ArrayList<String> unmet = new java.util.ArrayList<>();
            for (String capability : requiredCapabilities) {
                if (snapshot.equipmentCapabilities().contains(capability)) {
                    matched++;
                } else {
                    unmet.add(capability);
                }
            }
            return new GoalEvaluation(unmet.isEmpty() ? GoalEvaluation.State.SATISFIED : GoalEvaluation.State.UNSATISFIED,
                    matched, requiredCapabilities.size(), Map.of("capabilities", String.join(",", snapshot.equipmentCapabilities())), unmet);
        }
    }

    record Workstation() implements GoalPredicate {
        private static final List<String> REQUIRED = List.of(
                "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest");

        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int matched = 0;
            java.util.ArrayList<String> unmet = new java.util.ArrayList<>();
            Map<String, String> evidence = new LinkedHashMap<>();
            for (String block : REQUIRED) {
                int count = snapshot.nearbyBlockCount(block);
                evidence.put(block, String.valueOf(count));
                if (count > 0) {
                    matched++;
                } else {
                    unmet.add(block);
                }
            }
            return new GoalEvaluation(unmet.isEmpty() ? GoalEvaluation.State.SATISFIED : GoalEvaluation.State.UNSATISFIED,
                    matched, REQUIRED.size(), evidence, unmet);
        }
    }

    record Stockpile(String itemId, int count) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            int actual = snapshot.containerCount(itemId);
            return GoalEvaluation.count(actual, count,
                    Map.of("container_item", itemId, "container_count", String.valueOf(actual)),
                    "container_missing_item:" + itemId);
        }
    }

    record FoodUnits(int count) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            return GoalEvaluation.count(snapshot.foodUnits(), count,
                    Map.of("food_units", String.valueOf(snapshot.foodUnits())), "insufficient_cooked_food");
        }
    }

    record Structure(String blueprint) implements GoalPredicate {
        @Override
        public GoalEvaluation evaluate(GoalSnapshot snapshot) {
            if (snapshot.structure().isEmpty()) {
                return GoalEvaluation.unknown("structure_binding_missing:" + blueprint);
            }
            StructureReport report = snapshot.structure().get();
            boolean satisfied = report.expected() > 0 && report.mismatched() == 0 && report.matched() == report.expected();
            return new GoalEvaluation(satisfied ? GoalEvaluation.State.SATISFIED : GoalEvaluation.State.UNSATISFIED,
                    report.matched(), report.expected(),
                    Map.of("blueprint", blueprint,
                            "anchor", report.anchor(),
                            "expected", String.valueOf(report.expected()),
                            "matched", String.valueOf(report.matched()),
                            "placed", String.valueOf(report.placed()),
                            "skipped", String.valueOf(report.skipped()),
                            "mismatched", String.valueOf(report.mismatched())),
                    satisfied ? List.of() : List.of("structure_mismatch:" + report.mismatched()));
        }
    }
}
