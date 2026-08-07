package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.task.HuntSearchCursor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorHuntCursorPersistenceTest {
    @Test
    void missingNamespaceIsTheOnlyLegacyFreshRepresentation() {
        Optional<HuntSearchCursor> decoded =
                GoalExecutor.decodeHuntSearchCursorNamespace(Map.of("revision", "4"));

        assertTrue(decoded.isPresent());
        assertEquals(HuntSearchCursor.initial().encode(), decoded.orElseThrow().encode());
    }

    @Test
    void modernHuntWatermarkCannotLoseItsCursorNamespace() {
        Map<String, String> checkpoint = completeSnapshot();

        assertTrue(GoalExecutor.decodeHuntSearchCursorNamespace(checkpoint).isEmpty());
        assertEquals("mission_restore_invalid_hunt_search_cursor",
                GoalExecutor.restoreCheckpointValidationFailure(checkpoint).orElseThrow());
    }

    @Test
    void namespaceRoundTripsMissionSearchFacts() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        assertTrue(cursor.setSurfaceAnchorIfAbsent("minecraft:overworld", 10, 72, -4));
        assertTrue(cursor.markVisited("minecraft:overworld", 10, -4));
        assertEquals(0L, cursor.claimNextOrdinal());

        Map<String, String> namespaced =
                GoalExecutor.encodeHuntSearchCursorNamespace(cursor);
        Optional<HuntSearchCursor> restored =
                GoalExecutor.decodeHuntSearchCursorNamespace(namespaced);

        assertTrue(namespaced.keySet().stream().allMatch(key -> key.startsWith("hunt.")));
        assertTrue(restored.isPresent());
        assertEquals(cursor.encode(), restored.orElseThrow().encode());
    }

    @Test
    void captureShapeRoundTripsSnapshotAndCursorAsIndependentNamespaces() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        assertTrue(cursor.setSurfaceAnchorIfAbsent("minecraft:overworld", 10, 72, -4));
        assertTrue(cursor.markVisited("minecraft:overworld", 10, -4));
        assertEquals(0L, cursor.claimNextOrdinal());

        Map<String, String> checkpoint = completeSnapshot();
        checkpoint.putAll(GoalExecutor.encodeHuntSearchCursorNamespace(cursor));

        Optional<?> snapshot = decodeReplanSnapshot(checkpoint);
        Optional<HuntSearchCursor> restored =
                GoalExecutor.decodeHuntSearchCursorNamespace(checkpoint);

        assertTrue(snapshot.isPresent());
        assertEquals(7, snapshotInt(snapshot.orElseThrow(), "steps"));
        assertEquals(3, snapshotInt(snapshot.orElseThrow(), "targetCount"));
        assertEquals(24, snapshotInt(snapshot.orElseThrow(), "huntRawMeat"));
        assertEquals(1, snapshotInt(snapshot.orElseThrow(), "huntVisitedSectors"));
        assertEquals("minecraft:overworld",
                snapshotString(snapshot.orElseThrow(), "dimension"));
        assertTrue(restored.isPresent());
        assertEquals(cursor.encode(), restored.orElseThrow().encode());
        assertTrue(GoalExecutor.restoreCheckpointValidationFailure(checkpoint).isEmpty());
    }

    @Test
    void partialHuntReplanWatermarkFailsClosedWithoutErasingValidCursor() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        assertTrue(cursor.markVisited("minecraft:overworld", 64, -64));
        Map<String, String> checkpoint = legacySnapshot();
        checkpoint.put("snap_hunt_raw_meat", "12");
        checkpoint.putAll(GoalExecutor.encodeHuntSearchCursorNamespace(cursor));

        assertTrue(decodeReplanSnapshot(checkpoint).isEmpty());
        Optional<HuntSearchCursor> restored =
                GoalExecutor.decodeHuntSearchCursorNamespace(checkpoint);
        assertTrue(restored.isPresent());
        assertEquals(cursor.encode(), restored.orElseThrow().encode());

        checkpoint.remove("snap_hunt_raw_meat");
        checkpoint.put("snap_hunt_visited_sectors", "1");
        assertTrue(decodeReplanSnapshot(checkpoint).isEmpty());
        assertTrue(GoalExecutor.decodeHuntSearchCursorNamespace(checkpoint).isPresent());
    }

    @Test
    void malformedHuntWatermarksFailClosedWhileLegacySnapshotRemainsCompatible() {
        Map<String, String> legacy = legacySnapshot();
        Optional<?> legacyDecoded = decodeReplanSnapshot(legacy);
        assertTrue(legacyDecoded.isPresent());
        assertEquals(-1, snapshotInt(legacyDecoded.orElseThrow(), "huntRawMeat"));
        assertEquals(-1, snapshotInt(
                legacyDecoded.orElseThrow(), "huntVisitedSectors"));

        Map<String, String> negativeRaw = completeSnapshot();
        negativeRaw.put("snap_hunt_raw_meat", "-1");
        assertTrue(decodeReplanSnapshot(negativeRaw).isEmpty());

        Map<String, String> malformedSectors = completeSnapshot();
        malformedSectors.put("snap_hunt_visited_sectors", "1.0");
        assertTrue(decodeReplanSnapshot(malformedSectors).isEmpty());
    }

    @Test
    void realRestoreValidationRejectsEveryPresentMalformedSnapshot() {
        assertTrue(GoalExecutor.restoreCheckpointValidationFailure(Map.of()).isEmpty());
        assertTrue(GoalExecutor.restoreCheckpointValidationFailure(legacySnapshot()).isEmpty());

        Map<String, String> partialCore = new LinkedHashMap<>();
        partialCore.put("snap_steps", "7");
        assertEquals("mission_restore_invalid_replan_snapshot",
                GoalExecutor.restoreCheckpointValidationFailure(partialCore).orElseThrow());

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        Map<String, String> partialHunt = legacySnapshot();
        partialHunt.put("snap_hunt_raw_meat", "12");
        partialHunt.putAll(GoalExecutor.encodeHuntSearchCursorNamespace(cursor));
        assertEquals("mission_restore_invalid_replan_snapshot",
                GoalExecutor.restoreCheckpointValidationFailure(partialHunt).orElseThrow());

        Map<String, String> malformedHunt = completeSnapshot();
        malformedHunt.put("snap_hunt_visited_sectors", "1.0");
        malformedHunt.putAll(GoalExecutor.encodeHuntSearchCursorNamespace(cursor));
        assertEquals("mission_restore_invalid_replan_snapshot",
                GoalExecutor.restoreCheckpointValidationFailure(malformedHunt).orElseThrow());
    }

    @Test
    void persistedMissionCountersAllowOnlyMissingOrNonNegativeIntegers() {
        for (String key : new String[]{"revision", "lifetime_replans", "replan_count"}) {
            assertEquals(0, GoalExecutor.decodePersistedMissionCounter(
                    Map.of(), key).orElseThrow());
            assertEquals(17, GoalExecutor.decodePersistedMissionCounter(
                    Map.of(key, "17"), key).orElseThrow());
            for (String invalid : new String[]{
                    "-1", "1.0", "2147483648", "01", "+1"}) {
                OptionalInt decoded = GoalExecutor.decodePersistedMissionCounter(
                        Map.of(key, invalid), key);
                assertTrue(decoded.isEmpty(), () -> key + " accepted " + invalid);
            }
        }

        assertEquals("mission_restore_invalid_completed_step_count",
                GoalExecutor.restoreCheckpointValidationFailure(
                        Map.of("revision", "-1")).orElseThrow());
        assertEquals("mission_restore_invalid_lifetime_replan_count",
                GoalExecutor.restoreCheckpointValidationFailure(
                        Map.of("lifetime_replans", "bad")).orElseThrow());
        assertEquals("mission_restore_invalid_replan_count",
                GoalExecutor.restoreCheckpointValidationFailure(
                        Map.of("replan_count", "2147483648")).orElseThrow());
    }

    @Test
    void validSnapshotCannotAuthorizeMalformedHuntNamespace() {
        Map<String, String> checkpoint = completeSnapshot();
        checkpoint.put("hunt.schema", "1");

        assertTrue(decodeReplanSnapshot(checkpoint).isPresent());
        assertTrue(GoalExecutor.decodeHuntSearchCursorNamespace(checkpoint).isEmpty());
    }

    @Test
    void snapshotShapeIsExactAndCanonical() {
        Map<String, String> modernWithoutDimension = completeSnapshot();
        modernWithoutDimension.remove("snap_dimension");
        assertTrue(decodeReplanSnapshot(modernWithoutDimension).isEmpty());

        Map<String, String> unknown = completeSnapshot();
        unknown.put("snap_future", "1");
        assertTrue(decodeReplanSnapshot(unknown).isEmpty());
        unknown.putAll(GoalExecutor.encodeHuntSearchCursorNamespace(
                HuntSearchCursor.initial()));
        assertEquals("mission_restore_invalid_replan_snapshot",
                GoalExecutor.restoreCheckpointValidationFailure(unknown)
                        .orElseThrow());

        for (String key : List.of(
                "snap_steps", "snap_target", "snap_x", "snap_y", "snap_z",
                "snap_hunt_raw_meat", "snap_hunt_visited_sectors")) {
            Map<String, String> nonCanonical = completeSnapshot();
            nonCanonical.put(key, key.equals("snap_x") ? "+1" : "01");
            assertTrue(decodeReplanSnapshot(nonCanonical).isEmpty(),
                    () -> "snapshot accepted non-canonical " + key);
        }

        Map<String, String> nonCanonicalDimension = completeSnapshot();
        nonCanonicalDimension.put("snap_dimension", "overworld");
        assertTrue(decodeReplanSnapshot(nonCanonicalDimension).isEmpty());
    }

    @Test
    void restoredCursorKeepsOrdinalAndVisitedFactsMonotonicAcrossRepeatedCapture() {
        HuntSearchCursor original = HuntSearchCursor.initial();
        assertTrue(original.setSurfaceAnchorIfAbsent(
                "minecraft:overworld", -18, 68, 33));
        assertTrue(original.markVisited("minecraft:overworld", -18, 33));
        assertEquals(0L, original.claimNextOrdinal());
        assertEquals(1L, original.claimNextOrdinal());

        HuntSearchCursor restored = GoalExecutor.decodeHuntSearchCursorNamespace(
                GoalExecutor.encodeHuntSearchCursorNamespace(original)).orElseThrow();
        int restoredVisited = restored.visitedCount();
        assertEquals(2L, restored.nextOrdinal());
        assertEquals(2L, restored.claimNextOrdinal());
        assertEquals(3L, restored.nextOrdinal());
        assertTrue(restored.markVisited("minecraft:overworld", 96, 96));
        assertEquals(restoredVisited + 1, restored.visitedCount());
        assertTrue(restored.contains("minecraft:overworld", -18, 33));

        HuntSearchCursor restoredAgain = GoalExecutor.decodeHuntSearchCursorNamespace(
                GoalExecutor.encodeHuntSearchCursorNamespace(restored)).orElseThrow();
        assertEquals(restored.nextOrdinal(), restoredAgain.nextOrdinal());
        assertEquals(restored.visitedCount(), restoredAgain.visitedCount());
        assertEquals(restored.encode(), restoredAgain.encode());
    }

    @Test
    void partialOrMalformedNamespaceFailsClosed() {
        assertFalse(GoalExecutor.decodeHuntSearchCursorNamespace(
                Map.of("hunt.schema", "1")).isPresent());
        assertFalse(GoalExecutor.decodeHuntSearchCursorNamespace(
                Map.of("hunt", "present")).isPresent());
        assertFalse(GoalExecutor.decodeHuntSearchCursorNamespace(
                Map.of("hunt.", "present")).isPresent());

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        Map<String, String> malformed = new LinkedHashMap<>(
                GoalExecutor.encodeHuntSearchCursorNamespace(cursor));
        malformed.put("hunt.unknown", "field");
        assertFalse(GoalExecutor.decodeHuntSearchCursorNamespace(malformed).isPresent());
    }

    @Test
    void transitionCaptureRunsBeforeDispatchAndAgainAfterSuccess() {
        List<String> events = new ArrayList<>();

        GoalExecutor.captureBeforeAndAfterDispatch(
                () -> events.add("capture"),
                () -> events.add("dispatch"));

        assertEquals(List.of("capture", "dispatch", "capture"), events);
    }

    @Test
    void failedDispatchStillRetainsItsWriteAheadCapture() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> GoalExecutor.captureBeforeAndAfterDispatch(
                        () -> events.add("capture"),
                        () -> {
                            events.add("dispatch");
                            throw new IllegalStateException("start failed");
                        }));

        assertEquals(List.of("capture", "dispatch"), events);
    }

    @Test
    void completedAndSkippedStepTransitionsCaptureBeforeDispatch()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java"));

        int completed = source.indexOf(
                "plan.completedSteps++; // Phase A:完成一步=进展信号");
        int completedEnd = source.indexOf(
                "if (status.state() == TaskState.FAILED)", completed);
        String completedTransition = source.substring(completed, completedEnd);
        assertTrue(completedTransition.contains(
                "captureTransitionAndAssignNext(bot, plan);"),
                "ordinary completed-step transition must be captured before successor dispatch");

        int skipped = source.indexOf("goal_step_skipped_besteffort");
        int skippedEnd = source.indexOf(
                "// Phase A 进度感知预算", skipped);
        String skippedTransition = source.substring(skipped, skippedEnd);
        assertTrue(skippedTransition.contains(
                "clearCompletedTaskCheckpoint(plan);"),
                "best-effort skip must retire its settled task checkpoint before capture");
        assertTrue(skippedTransition.indexOf("plan.current = null;")
                        < skippedTransition.indexOf(
                        "captureTransitionAndAssignNext(bot, plan);"),
                "best-effort skip must clear current authority before write-ahead capture");
        assertTrue(skippedTransition.indexOf("plan.currentTask = null;")
                        < skippedTransition.indexOf(
                        "captureTransitionAndAssignNext(bot, plan);"),
                "best-effort skip must detach the failed task before write-ahead capture");
        assertTrue(skippedTransition.contains(
                "captureTransitionAndAssignNext(bot, plan);"),
                "best-effort skip transition must be captured before successor dispatch");
    }

    @Test
    void repairReplanAndServiceSchedulesUseWriteAheadCapture()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/goal/GoalExecutor.java"));

        assertTrue(sourceSection(
                        source,
                        "BotLog.task(bot, \"goal_plan\"",
                        "public boolean tickBot(MinecraftServer server, AIPlayerEntity bot)")
                        .contains("captureTransitionAndAssignNext(bot, active);"),
                "initial/restore ActivePlan dispatch lacks write-ahead capture");

        for (String[] bounds : new String[][]{
                {"goal_obsidian_service_scheduled",
                        "if (plan.current.kind() == GoalStep.Kind.MINING_SERVICE"},
                {"goal_postcondition_repair\",",
                        "goal_postcondition_repair_rejected"},
                {"report(bot, \"遇到问题,我重新规划了一次。\")",
                        "private static Optional<MiningServiceTask.RestoreMetadata>"},
                {"goal_rare_resource_epoch_advanced",
                        "/** Schedules the one sealed inventory service"},
                {"goal_rare_inventory_service_scheduled",
                        "/**\n     * Inserts a capacity-only ORE_BATCH service"},
                {"goal_mining_capacity_handoff_scheduled",
                        "// 优化2:目标最近"}
        }) {
            assertTrue(sourceSection(source, bounds[0], bounds[1]).contains(
                            "captureTransitionAndAssignNext(bot, plan);"),
                    () -> "transition lacks write-ahead capture: " + bounds[0]);
        }

        assertTrue(sourceSection(
                        source,
                        "goal_step_retry_after_tool_recovery",
                        "private static boolean hasRecoveredMiningChannelTool")
                        .contains("captureBeforeAndAfterDispatch("),
                "tool-recovery dispatch lacks write-ahead capture");
        assertTrue(sourceSection(
                        source,
                        "debitChannelToolResupply(plan.taskCheckpoint)",
                        "/**\n     * Atomically trades this exact open batch")
                        .contains("captureBeforeAndAfterDispatch("),
                "channel-resupply dispatch lacks write-ahead capture");
        assertFalse(source.matches(
                        "(?s).*assignNext\\(bot, plan\\);\\s*markDirty\\(bot\\);.*"),
                "a committed plan transition still captures only after successor dispatch");
    }

    private static Map<String, String> legacySnapshot() {
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("snap_steps", "7");
        checkpoint.put("snap_target", "3");
        checkpoint.put("snap_x", "-12");
        checkpoint.put("snap_y", "41");
        checkpoint.put("snap_z", "88");
        return checkpoint;
    }

    private static Map<String, String> completeSnapshot() {
        Map<String, String> checkpoint = legacySnapshot();
        checkpoint.put("snap_dimension", "minecraft:overworld");
        checkpoint.put("snap_hunt_raw_meat", "24");
        checkpoint.put("snap_hunt_visited_sectors", "1");
        return checkpoint;
    }

    private static Optional<?> decodeReplanSnapshot(Map<String, String> checkpoint) {
        try {
            Method method = GoalExecutor.class.getDeclaredMethod(
                    "decodeReplanSnapshot", Map.class);
            method.setAccessible(true);
            return (Optional<?>) method.invoke(null, checkpoint);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cannot invoke replan snapshot decoder", exception);
        }
    }

    private static int snapshotInt(Object snapshot, String accessor) {
        try {
            Method method = snapshot.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return (int) method.invoke(snapshot);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "cannot read replan snapshot field " + accessor, exception);
        }
    }

    private static String snapshotString(Object snapshot, String accessor) {
        try {
            Method method = snapshot.getClass().getDeclaredMethod(accessor);
            method.setAccessible(true);
            return (String) method.invoke(snapshot);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "cannot read replan snapshot field " + accessor, exception);
        }
    }

    private static String sourceSection(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertTrue(from >= 0, () -> "missing source marker " + start);
        assertTrue(to > from, () -> "missing source marker " + end);
        return source.substring(from, to);
    }
}
