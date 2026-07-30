package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObsidianSearchCursorTest {
    @Test
    void squareSpiralPhysicallyReachesWorkFacesOutsideInitialLos() {
        ObsidianSearchCursor cursor = ObsidianSearchCursor.initial(BlockPos.ORIGIN, 4);
        List<BlockPos> reached = new ArrayList<>();
        int previousEpoch = cursor.topologyEpoch();

        for (int i = 0; i < 400; i++) {
            if (cursor.directionIndex() < 0 || cursor.stepsLeft() == 0) {
                cursor = cursor.beginNextLeg();
            }
            BlockPos next = cursor.nextFace();
            ObsidianSearchCursor advanced = cursor.advanceTo(next);
            assertEquals(previousEpoch + 1, advanced.topologyEpoch());
            previousEpoch = advanced.topologyEpoch();
            cursor = advanced;
            reached.add(cursor.face());
        }

        assertTrue(reached.stream().anyMatch(pos ->
                        Math.abs(pos.getX()) > 16 || Math.abs(pos.getZ()) > 16),
                "the persisted branch spiral must expand beyond an initially empty 16-block LOS");
        assertEquals(cursor.face(), ObsidianSearchCursor.decode(cursor.encode()).orElseThrow().face());
    }

    @Test
    void multiplePoolsAccumulateUntilTheHalfStackPostcondition() {
        int collected = 0;
        for (int poolYield : new int[]{8, 12, 12}) {
            assertTrue(CreateObsidianTask.needsMorePools(32, collected));
            collected += poolYield;
        }
        assertFalse(CreateObsidianTask.needsMorePools(32, collected));
    }

    @Test
    void checkpointsPreserveOneSixteenAndThirtyOneProgressWithTheExactWorkFace() {
        for (int produced : new int[]{1, 16, 31}) {
            ObsidianSearchCursor cursor = ObsidianSearchCursor.initial(new BlockPos(7, -54, -3), 12)
                    .beginNextLeg();
            for (int step = 0; step < produced; step++) {
                if (cursor.stepsLeft() == 0) {
                    cursor = cursor.beginNextLeg();
                }
                cursor = cursor.advanceTo(cursor.nextFace());
            }
            cursor = cursor.withProduced(produced);

            ObsidianSearchCursor restored = ObsidianSearchCursor.decode(cursor.encode()).orElseThrow();
            assertEquals(produced, restored.produced());
            assertEquals(cursor.face(), restored.face());
            assertEquals(cursor.directionIndex(), restored.directionIndex());
            assertEquals(cursor.legIndex(), restored.legIndex());
            assertEquals(cursor.stepsLeft(), restored.stepsLeft());
            assertEquals(cursor.topologyEpoch(), restored.topologyEpoch());
        }
    }

    @Test
    void fourBlockedDirectionsPersistUntilPhysicalOrTopologyProgress() {
        ObsidianSearchCursor cursor = ObsidianSearchCursor.initial(BlockPos.ORIGIN, 4);
        int factualEpoch = cursor.topologyEpoch();
        for (int direction = 0; direction < 4; direction++) {
            cursor = cursor.beginNextLeg().skipBlockedLeg();
            assertEquals(factualEpoch, cursor.topologyEpoch(),
                    "pure leg rotation must not invent a topology change");
            assertEquals(direction == 3, cursor.blockedAllDirections());
        }
        assertEquals(0b1111, cursor.blockedDirections());

        ObsidianSearchCursor restored = ObsidianSearchCursor.decode(cursor.encode()).orElseThrow();
        assertEquals(0b1111, restored.blockedDirections());
        assertTrue(restored.blockedAllDirections());

        ObsidianSearchCursor moved = restored.beginNextLeg()
                .advanceTo(restored.beginNextLeg().nextFace());
        assertEquals(0, moved.blockedDirections());
        assertFalse(moved.blockedAllDirections());
        assertEquals(0, restored.topologyChanged().blockedDirections());
    }

    @Test
    void legacyCursorMigratesBeforeRecordingBlockedDirections() {
        Map<String, String> legacy = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(new BlockPos(2, -59, 3), 12).encode());
        legacy.put("schema", "1");
        legacy.remove("blocked_directions");

        ObsidianSearchCursor restored = ObsidianSearchCursor.decode(legacy).orElseThrow();
        assertEquals(ObsidianSearchCursor.CURRENT_SCHEMA, restored.schema());
        restored = restored.beginNextLeg().skipBlockedLeg();
        ObsidianSearchCursor roundTrip = ObsidianSearchCursor.decode(
                restored.encode()).orElseThrow();
        assertEquals(1, roundTrip.blockedDirections());

        Map<String, String> missingMask = new LinkedHashMap<>(roundTrip.encode());
        missingMask.remove("blocked_directions");
        assertTrue(ObsidianSearchCursor.decode(missingMask).isEmpty());
        Map<String, String> invalidMask = new LinkedHashMap<>(roundTrip.encode());
        invalidMask.put("blocked_directions", "16");
        assertTrue(ObsidianSearchCursor.decode(invalidMask).isEmpty());
    }

    @Test
    void currentSchemaRequiresEveryPersistedIdentityField() {
        Map<String, String> encoded = ObsidianSearchCursor.initial(
                new BlockPos(2, -59, 3), 12).encode();

        for (String required : List.of(
                "schema", "cursor_kind", "origin", "work_face", "direction", "leg",
                "steps_left", "leg_length", "base_leg_length", "faces_since_scan",
                "topology_epoch", "blocked_directions", "produced")) {
            Map<String, String> missing = new LinkedHashMap<>(encoded);
            missing.remove(required);
            assertTrue(ObsidianSearchCursor.decode(missing).isEmpty(),
                    () -> "schema 2 must reject a missing " + required);
        }
    }

    @Test
    void decodeRejectsOutOfRangeCursorStateBeforeNormalization() {
        Map<String, String> encoded = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(BlockPos.ORIGIN, 4).encode());

        assertRejectedWith(encoded, "direction", "-2");
        assertRejectedWith(encoded, "direction", "4");
        assertRejectedWith(encoded, "leg", "-1");
        assertRejectedWith(encoded, "base_leg_length", "0");
        assertRejectedWith(encoded, "leg_length", "3");
        assertRejectedWith(encoded, "steps_left", "5");
        assertRejectedWith(encoded, "faces_since_scan", "-1");
        assertRejectedWith(encoded, "topology_epoch", "-1");
        assertRejectedWith(encoded, "produced", "-1");
        assertRejectedWith(encoded, "blocked_directions", "-1");
        assertRejectedWith(encoded, "blocked_directions", "16");
    }

    @Test
    void legacyDefaultsRemainCompatibleButCannotNormalizeInvalidValues() {
        Map<String, String> legacy = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(BlockPos.ORIGIN, 4).encode());
        legacy.put("schema", "1");
        for (String compatibleDefault : List.of(
                "direction", "leg", "steps_left", "leg_length", "base_leg_length",
                "faces_since_scan", "topology_epoch", "blocked_directions", "produced")) {
            legacy.remove(compatibleDefault);
        }

        ObsidianSearchCursor restored = ObsidianSearchCursor.decode(legacy).orElseThrow();
        assertEquals(-1, restored.directionIndex());
        assertEquals(0, restored.stepsLeft());
        assertEquals(12, restored.legLength());
        assertEquals(0, restored.blockedDirections());

        legacy.put("topology_epoch", "-1");
        assertTrue(ObsidianSearchCursor.decode(legacy).isEmpty());
        legacy.remove("topology_epoch");
        legacy.put("direction", "4");
        assertTrue(ObsidianSearchCursor.decode(legacy).isEmpty());
    }

    @Test
    void advanceToAcceptsOnlyBoundedSameLayerForwardMotion() {
        ObsidianSearchCursor cursor = ObsidianSearchCursor.initial(BlockPos.ORIGIN, 4)
                .beginNextLeg();

        assertEquals(cursor, cursor.advanceTo(new BlockPos(1, 0, -1)),
                "off-axis motion must not change the durable face");
        assertEquals(cursor, cursor.advanceTo(new BlockPos(0, 1, -1)),
                "cross-Y motion must not change the durable face");
        assertEquals(cursor, cursor.advanceTo(new BlockPos(0, 0, -5)),
                "motion beyond stepsLeft must not be truncated into progress");
        assertEquals(cursor, cursor.advanceTo(new BlockPos(0, 0, -3)),
                "a one-cell search walker must not accept skipped intermediate faces");
        assertEquals(cursor, cursor.advanceTo(new BlockPos(0, 0, 1)),
                "backward motion must not change the durable face");

        ObsidianSearchCursor advanced = cursor.advanceTo(new BlockPos(0, 0, -1));
        assertEquals(new BlockPos(0, 0, -1), advanced.face());
        assertEquals(3, advanced.stepsLeft());
        assertEquals(1, advanced.facesSinceScan());
        assertEquals(1, advanced.topologyEpoch());
    }

    @Test
    void rejectionExpiresOnEitherTopologyChangeOrTtl() {
        ObsidianTargetMemory memory = new ObsidianTargetMemory();
        BlockPos lava = new BlockPos(3, -55, 9);

        memory.reject(lava, 4, 100, 20);
        assertTrue(memory.isRejected(lava, 4, 119));
        assertFalse(memory.isRejected(lava, 5, 119));

        memory.reject(lava, 5, 200, 20);
        assertFalse(memory.isRejected(lava, 5, 220));
    }

    private static void assertRejectedWith(Map<String, String> encoded,
                                           String key, String value) {
        Map<String, String> invalid = new LinkedHashMap<>(encoded);
        invalid.put(key, value);
        assertTrue(ObsidianSearchCursor.decode(invalid).isEmpty(),
                () -> "cursor must reject " + key + "=" + value);
    }
}
