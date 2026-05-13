package com.aiplayer.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MilestoneTaskState {
    private static final String GOLD_INGOT = "minecraft:gold_ingot";
    private static final List<Milestone> GOLD_MILESTONES = List.of(
        new Milestone("wood", "木材"),
        new Milestone("planks", "木板"),
        new Milestone("crafting_table", "工作台"),
        new Milestone("wooden_pickaxe", "木镐"),
        new Milestone("cobblestone", "圆石"),
        new Milestone("stone_pickaxe", "石镐"),
        new Milestone("raw_iron", "铁原矿"),
        new Milestone("fuel", "燃料"),
        new Milestone("furnace", "熔炉"),
        new Milestone("iron_ingot", "铁锭"),
        new Milestone("iron_pickaxe", "铁镐"),
        new Milestone("raw_gold", "金原矿"),
        new Milestone("gold_ingot", "金锭")
    );
    private static final Set<String> LOG_ITEMS = Set.of(
        "minecraft:oak_log",
        "minecraft:spruce_log",
        "minecraft:birch_log",
        "minecraft:jungle_log",
        "minecraft:acacia_log",
        "minecraft:dark_oak_log",
        "minecraft:mangrove_log",
        "minecraft:cherry_log",
        "minecraft:crimson_stem",
        "minecraft:warped_stem"
    );
    private static final Set<String> PLANK_ITEMS = Set.of(
        "minecraft:oak_planks",
        "minecraft:spruce_planks",
        "minecraft:birch_planks",
        "minecraft:jungle_planks",
        "minecraft:acacia_planks",
        "minecraft:dark_oak_planks",
        "minecraft:mangrove_planks",
        "minecraft:cherry_planks",
        "minecraft:crimson_planks",
        "minecraft:warped_planks"
    );
    private static final Set<String> FUEL_ITEMS = Set.of(
        "minecraft:coal",
        "minecraft:charcoal",
        "minecraft:oak_log",
        "minecraft:spruce_log",
        "minecraft:birch_log",
        "minecraft:jungle_log",
        "minecraft:acacia_log",
        "minecraft:dark_oak_log",
        "minecraft:mangrove_log",
        "minecraft:cherry_log",
        "minecraft:oak_planks",
        "minecraft:spruce_planks",
        "minecraft:birch_planks",
        "minecraft:jungle_planks",
        "minecraft:acacia_planks",
        "minecraft:dark_oak_planks",
        "minecraft:mangrove_planks",
        "minecraft:cherry_planks"
    );

    private final String targetItem;
    private final int targetCount;
    private final List<Milestone> milestones;
    private final Map<String, Integer> failureCounts = new LinkedHashMap<>();
    private final Map<String, Integer> recoveryCounts = new LinkedHashMap<>();
    private List<String> completed = List.of();
    private int currentIndex;
    private String lastReason = "new";
    private String nextRecoveryAction = "none";

    private MilestoneTaskState(String targetItem, int targetCount, List<Milestone> milestones) {
        this.targetItem = normalize(targetItem);
        this.targetCount = Math.max(1, targetCount);
        this.milestones = List.copyOf(milestones);
        this.currentIndex = milestones.isEmpty() ? 0 : 0;
    }

    public static MilestoneTaskState create(String targetItem, int targetCount) {
        String normalized = normalize(targetItem);
        if (GOLD_INGOT.equals(normalized)) {
            return new MilestoneTaskState(normalized, targetCount, GOLD_MILESTONES);
        }
        return new MilestoneTaskState(normalized, targetCount, List.of(new Milestone("target", normalized)));
    }

    public boolean tracked() {
        return !milestones.isEmpty();
    }

    public boolean isGoldChain() {
        return GOLD_INGOT.equals(targetItem);
    }

    public boolean refresh(Map<String, Integer> inventory, ExecutionStep currentStep, String reason) {
        List<String> oldCompleted = completed;
        int oldIndex = currentIndex;
        lastReason = reason == null || reason.isBlank() ? "unknown" : reason;
        Map<String, Integer> safeInventory = normalizedInventory(inventory);
        List<String> nextCompleted = new ArrayList<>(completed);
        for (Milestone milestone : milestones) {
            if (!nextCompleted.contains(milestone.id()) && isMilestoneComplete(milestone.id(), safeInventory)) {
                nextCompleted.add(milestone.id());
            }
        }
        completed = List.copyOf(nextCompleted);
        currentIndex = firstIncompleteIndex();
        return oldIndex != currentIndex || !oldCompleted.equals(completed);
    }

    public boolean recordStepResult(ExecutionStep step, StepResult result, Map<String, Integer> inventory) {
        boolean changed = refresh(inventory, step, result == null ? "step_result" : "step_" + result.getStatus().name().toLowerCase(Locale.ROOT));
        if (result != null && !result.isSuccess() && !result.isRunning()) {
            failureCounts.merge(currentMilestone().id(), 1, Integer::sum);
            changed = true;
        }
        if (result != null && result.requiresReplan()) {
            recoveryCounts.merge(currentMilestone().id(), 1, Integer::sum);
            changed = true;
        }
        return changed;
    }

    public void recordRecoveryAction(String action) {
        nextRecoveryAction = action == null || action.isBlank() ? "none" : action;
    }

    public String currentLabel() {
        return currentMilestone().label();
    }

    public int currentNumber() {
        if (milestones.isEmpty()) {
            return 0;
        }
        return Math.min(currentIndex + 1, milestones.size());
    }

    public int total() {
        return milestones.size();
    }

    public String toLogText() {
        return "milestone=" + currentNumber() + "/" + total()
            + ", current=" + currentMilestone().label()
            + ", completed=" + completedLabels()
            + ", failures=" + failureCounts
            + ", recoveries=" + recoveryCounts
            + ", nextRecovery=" + nextRecoveryAction
            + ", reason=" + lastReason;
    }

    public String toUserText() {
        return currentNumber() + "/" + total() + " " + currentMilestone().label();
    }

    private int firstIncompleteIndex() {
        for (int i = 0; i < milestones.size(); i++) {
            if (!completed.contains(milestones.get(i).id())) {
                return i;
            }
        }
        return Math.max(0, milestones.size() - 1);
    }

    private Milestone currentMilestone() {
        if (milestones.isEmpty()) {
            return new Milestone("none", "无里程碑");
        }
        return milestones.get(Math.min(currentIndex, milestones.size() - 1));
    }

    private List<String> completedLabels() {
        return completed.stream()
            .map(this::labelFor)
            .toList();
    }

    private String labelFor(String id) {
        return milestones.stream()
            .filter(milestone -> milestone.id().equals(id))
            .map(Milestone::label)
            .findFirst()
            .orElse(id);
    }

    private boolean isMilestoneComplete(String id, Map<String, Integer> inventory) {
        if (!isGoldChain()) {
            return count(inventory, targetItem) >= targetCount;
        }
        return switch (id) {
            case "wood" -> hasAny(inventory, LOG_ITEMS) || isMilestoneComplete("planks", inventory);
            case "planks" -> hasAny(inventory, PLANK_ITEMS) || count(inventory, "minecraft:stick") > 0 || isMilestoneComplete("crafting_table", inventory);
            case "crafting_table" -> count(inventory, "minecraft:crafting_table") > 0 || isMilestoneComplete("wooden_pickaxe", inventory);
            case "wooden_pickaxe" -> hasUsablePickaxe(inventory, "minecraft:wooden_pickaxe") || isMilestoneComplete("cobblestone", inventory);
            case "cobblestone" -> count(inventory, "minecraft:cobblestone") >= 3 || isMilestoneComplete("stone_pickaxe", inventory);
            case "stone_pickaxe" -> hasUsablePickaxe(inventory, "minecraft:stone_pickaxe") || isMilestoneComplete("raw_iron", inventory);
            case "raw_iron" -> count(inventory, "minecraft:raw_iron") >= 3 || isMilestoneComplete("iron_ingot", inventory);
            case "fuel" -> hasAny(inventory, FUEL_ITEMS) || isMilestoneComplete("iron_ingot", inventory) || isMilestoneComplete("gold_ingot", inventory);
            case "furnace" -> count(inventory, "minecraft:furnace") > 0 || isMilestoneComplete("iron_ingot", inventory) || isMilestoneComplete("gold_ingot", inventory);
            case "iron_ingot" -> count(inventory, "minecraft:iron_ingot") >= 3 || isMilestoneComplete("iron_pickaxe", inventory);
            case "iron_pickaxe" -> hasUsablePickaxe(inventory, "minecraft:iron_pickaxe") || isMilestoneComplete("raw_gold", inventory);
            case "raw_gold" -> count(inventory, "minecraft:raw_gold") >= targetCount || isMilestoneComplete("gold_ingot", inventory);
            case "gold_ingot" -> count(inventory, "minecraft:gold_ingot") >= targetCount;
            default -> false;
        };
    }

    private boolean hasUsablePickaxe(Map<String, Integer> inventory, String minimumPickaxe) {
        int minimumRank = pickaxeRank(minimumPickaxe);
        return inventory.entrySet().stream()
            .anyMatch(entry -> entry.getValue() > 0 && pickaxeRank(entry.getKey()) >= minimumRank);
    }

    private int pickaxeRank(String item) {
        return switch (normalize(item)) {
            case "minecraft:wooden_pickaxe" -> 1;
            case "minecraft:stone_pickaxe" -> 2;
            case "minecraft:iron_pickaxe" -> 3;
            case "minecraft:diamond_pickaxe" -> 4;
            case "minecraft:netherite_pickaxe" -> 5;
            default -> 0;
        };
    }

    private static Map<String, Integer> normalizedInventory(Map<String, Integer> inventory) {
        if (inventory == null || inventory.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> normalized = new TreeMap<>();
        inventory.forEach((item, count) -> normalized.merge(normalize(item), Math.max(0, count), Integer::sum));
        return normalized;
    }

    private static int count(Map<String, Integer> inventory, String item) {
        return inventory.getOrDefault(normalize(item), 0);
    }

    private static boolean hasAny(Map<String, Integer> inventory, Set<String> items) {
        return items.stream().anyMatch(item -> count(inventory, item) > 0);
    }

    private static String normalize(String item) {
        if (item == null || item.isBlank()) {
            return "";
        }
        String safe = item.toLowerCase(Locale.ROOT);
        return safe.contains(":") ? safe : "minecraft:" + safe;
    }

    private record Milestone(String id, String label) {
    }
}
