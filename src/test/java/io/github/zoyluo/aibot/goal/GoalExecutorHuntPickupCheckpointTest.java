package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.task.HuntPickupCheckpointTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorHuntPickupCheckpointTest {
    @Test
    void validHuntPickupNamespacePassesMissionPreflight() {
        Map<String, String> root = rootWithTask(
                HuntPickupCheckpointTest.openCheckpoint("none"));
        assertTrue(GoalExecutor.restoreCheckpointValidationFailure(root).isEmpty());
    }

    @Test
    void malformedOrMislabeledHuntNamespaceFailsTypedPreflight() {
        Map<String, String> missing = new LinkedHashMap<>(
                HuntPickupCheckpointTest.openCheckpoint("none"));
        missing.remove("pickup_origin");
        assertEquals("mission_restore_invalid_hunt_pickup_checkpoint",
                GoalExecutor.restoreCheckpointValidationFailure(
                        rootWithTask(missing)).orElseThrow());

        Map<String, String> mislabeled = new LinkedHashMap<>(
                rootWithTask(HuntPickupCheckpointTest.openCheckpoint("none")));
        mislabeled.put("task_kind", "CRAFT");
        assertEquals("mission_restore_invalid_hunt_pickup_checkpoint",
                GoalExecutor.restoreCheckpointValidationFailure(
                        mislabeled).orElseThrow());
    }

    @Test
    void legacyAbsenceStillPasses() {
        assertTrue(GoalExecutor.restoreCheckpointValidationFailure(Map.of()).isEmpty());
    }

    @Test
    void openClockRollbackIsCheckedOnlyInReceiptDimension() {
        var open = metadata("OPEN", "none");

        assertTrue(GoalExecutor.hasSameDimensionOpenHuntTimeRollback(
                open, "minecraft:overworld", 1199));
        assertTrue(!GoalExecutor.hasSameDimensionOpenHuntTimeRollback(
                open, "minecraft:the_nether", 1199),
                "cross-dimension restore must suspend before comparing another clock");
    }

    @Test
    void closedCollectedNeedsLiveInventoryOrPickupStatCoverage() {
        String bound = UUID.fromString(
                "00000000-0000-0000-0000-000000000001") + "=2";
        var closed = metadata("CLOSED_COLLECTED", bound);

        assertTrue(GoalExecutor.trustedClosedHuntPickupReceipt(
                closed, "minecraft:overworld", 5, 7, 1201));
        assertTrue(GoalExecutor.trustedClosedHuntPickupReceipt(
                closed, "minecraft:overworld", 3, 9, 1201));
        assertTrue(!GoalExecutor.trustedClosedHuntPickupReceipt(
                closed, "minecraft:overworld", 4, 8, 1201));
        assertTrue(!GoalExecutor.trustedClosedHuntPickupReceipt(
                closed, "minecraft:the_nether", 5, 9, 1201));
    }

    @Test
    void closedNoRawNeedsEmptyBindingAndFullRecoveryAge() {
        var closed = metadata("CLOSED_NO_RAW", "none");

        assertTrue(!GoalExecutor.trustedClosedHuntPickupReceipt(
                closed, "minecraft:overworld", 3, 7,
                1200 + io.github.zoyluo.aibot.task.HuntPickupCheckpoint
                        .RECOVERY_LIMIT_TICKS - 1));
        assertTrue(GoalExecutor.trustedClosedHuntPickupReceipt(
                closed, "minecraft:overworld", 3, 7,
                1200 + io.github.zoyluo.aibot.task.HuntPickupCheckpoint
                        .RECOVERY_LIMIT_TICKS));
    }

    @Test
    void openHuntDebtPrecedesCompoundObsidianTailFailure() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java"));
        int branch = source.indexOf(
                "boolean compoundObsidianTailUnavailable");
        int end = source.indexOf(
                "if (compoundObsidianTailUnavailable)", branch);
        assertTrue(branch >= 0 && end > branch);
        assertTrue(source.substring(branch, end)
                .contains("&& !unsettledHuntPickup"));
    }

    private static io.github.zoyluo.aibot.task.HuntPickupCheckpoint.Metadata metadata(
            String state, String units) {
        Map<String, String> checkpoint = new LinkedHashMap<>(
                HuntPickupCheckpointTest.openCheckpoint(units));
        checkpoint.put("transaction_state", state);
        return io.github.zoyluo.aibot.task.HuntPickupCheckpoint
                .inspect(checkpoint).orElseThrow();
    }

    private static Map<String, String> rootWithTask(Map<String, String> task) {
        Map<String, String> root = new LinkedHashMap<>();
        root.put("task_kind", "HUNT");
        task.forEach((key, value) -> root.put("task." + key, value));
        return Map.copyOf(root);
    }
}
