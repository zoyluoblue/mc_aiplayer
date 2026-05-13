package com.aiplayer.execution;

public record MiningDropProgress(
    String targetItem,
    int beforeCount,
    int afterCount,
    int gainedCount,
    int targetTotal,
    int remainingCount,
    boolean complete,
    String dropItem,
    int dropCount,
    int insertedCount,
    int uncollectedCount,
    String reason
) {
    public MiningDropProgress {
        targetItem = targetItem == null || targetItem.isBlank() ? "minecraft:air" : targetItem;
        dropItem = dropItem == null || dropItem.isBlank() ? "minecraft:air" : dropItem;
        beforeCount = Math.max(0, beforeCount);
        afterCount = Math.max(0, afterCount);
        gainedCount = Math.max(0, gainedCount);
        targetTotal = Math.max(0, targetTotal);
        remainingCount = Math.max(0, remainingCount);
        dropCount = Math.max(0, dropCount);
        insertedCount = Math.max(0, insertedCount);
        uncollectedCount = Math.max(0, uncollectedCount);
        reason = reason == null || reason.isBlank() ? "unknown" : reason;
    }

    public static MiningDropProgress analyze(
        String targetItem,
        int beforeCount,
        int afterCount,
        int targetTotal,
        String dropItem,
        int dropCount,
        int insertedCount,
        int uncollectedCount,
        boolean breakSuccess,
        String breakFailureReason
    ) {
        int gained = Math.max(0, afterCount - beforeCount);
        int remaining = Math.max(0, targetTotal - afterCount);
        boolean complete = afterCount >= targetTotal;
        String safeDrop = dropItem == null || dropItem.isBlank() ? "minecraft:air" : dropItem;
        String safeTarget = targetItem == null || targetItem.isBlank() ? "minecraft:air" : targetItem;
        String reason;
        if (!breakSuccess) {
            reason = "break_failed:" + (breakFailureReason == null || breakFailureReason.isBlank() ? "unknown" : breakFailureReason);
        } else if (uncollectedCount > 0) {
            reason = complete ? "target_item_complete_with_uncollected:" + uncollectedCount : "drop_uncollected:" + uncollectedCount;
        } else if (gained > 0) {
            reason = complete ? "target_item_complete" : "target_item_increased";
        } else if ("minecraft:air".equals(safeDrop) || dropCount <= 0) {
            reason = "break_success_no_drop";
        } else if (!safeTarget.equals(safeDrop)) {
            reason = "drop_not_target:" + safeDrop;
        } else {
            reason = "target_item_not_added";
        }
        return new MiningDropProgress(
            safeTarget,
            beforeCount,
            afterCount,
            gained,
            targetTotal,
            remaining,
            complete,
            safeDrop,
            dropCount,
            insertedCount,
            uncollectedCount,
            reason
        );
    }

    public String toLogText() {
        return "targetItem=" + targetItem
            + ", before=" + beforeCount
            + ", after=" + afterCount
            + ", gained=" + gainedCount
            + ", targetTotal=" + targetTotal
            + ", remaining=" + remainingCount
            + ", complete=" + complete
            + ", drop=" + dropItem + "x" + dropCount
            + ", inserted=" + insertedCount
            + ", uncollected=" + uncollectedCount
            + ", reason=" + reason;
    }
}
