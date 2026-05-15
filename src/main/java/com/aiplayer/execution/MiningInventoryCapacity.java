package com.aiplayer.execution;

import java.util.Map;
import java.util.TreeMap;

public final class MiningInventoryCapacity {
    private MiningInventoryCapacity() {
    }

    public static Report evaluate(
        String itemId,
        int expectedCount,
        int availableCount,
        int emptySlots,
        int maxStackSize,
        Map<String, Integer> backpack
    ) {
        int expected = Math.max(1, expectedCount);
        int available = Math.max(0, availableCount);
        int missing = Math.max(0, expected - available);
        int stackSize = Math.max(1, maxStackSize);
        int missingSlots = missing == 0 ? 0 : (missing + stackSize - 1) / stackSize;
        return new Report(
            itemId == null || itemId.isBlank() ? "minecraft:air" : itemId,
            expected,
            available,
            missing,
            Math.max(0, emptySlots),
            missingSlots,
            new TreeMap<>(backpack == null ? Map.of() : backpack)
        );
    }

    public static int remainingNeeded(int startCount, int goalCount, int currentCount) {
        int target = Math.max(0, startCount) + Math.max(1, goalCount);
        return Math.max(1, target - Math.max(0, currentCount));
    }

    public record Report(
        String itemId,
        int expectedCount,
        int availableCount,
        int missingCount,
        int emptySlots,
        int missingSlots,
        Map<String, Integer> backpack
    ) {
        public boolean enough() {
            return missingCount <= 0;
        }

        public String toStatusText() {
            return "目标物品=" + itemId
                + "，需要容量=" + expectedCount
                + "，可容纳=" + availableCount
                + "，缺少数量=" + missingCount
                + "，缺少空格=" + missingSlots
                + "，当前空格=" + emptySlots
                + "，背包=" + backpackSummary();
        }

        public String toFailureText(String reason) {
            return "背包空间不足，" + (reason == null || reason.isBlank() ? "当前任务" : reason)
                + " 需要容纳 " + itemId + " x" + expectedCount
                + "，当前只能容纳 " + availableCount
                + "，缺少 " + missingCount + " 个物品容量，至少需要 " + missingSlots
                + " 个空格或等价堆叠空间；当前空格=" + emptySlots
                + "，背包=" + backpackSummary();
        }

        private String backpackSummary() {
            return backpack.isEmpty() ? "{}" : backpack.toString();
        }
    }
}
