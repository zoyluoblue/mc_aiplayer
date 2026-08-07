package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuntSearchCursorTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    void ordinalClaimsRemainMissionGlobalAcrossReplansAndRestart() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        HuntSearchCursor firstTaskReference = cursor;
        HuntSearchCursor replannedTaskReference = cursor;

        assertEquals(0L, firstTaskReference.claimNextOrdinal());
        assertEquals(1L, firstTaskReference.claimNextOrdinal());
        assertEquals(2L, replannedTaskReference.claimNextOrdinal());

        HuntSearchCursor restored = HuntSearchCursor.decode(cursor.encode()).orElseThrow();
        assertEquals(3L, restored.nextOrdinal());
        assertEquals(3L, restored.claimNextOrdinal());
        assertEquals(4L, restored.nextOrdinal());
        assertEquals(4L, restored.revision());
    }

    @Test
    void sectorsUseFloorDivisionAndRemainDimensionAware() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();

        assertTrue(cursor.markVisited(OVERWORLD, 0, 31));
        assertTrue(cursor.contains(OVERWORLD, 31, 0));
        assertFalse(cursor.contains(OVERWORLD, 32, 0));
        assertFalse(cursor.contains(NETHER, 31, 0));

        assertTrue(cursor.markVisited(OVERWORLD, -1, -32));
        assertTrue(cursor.contains(OVERWORLD, -32, -1));
        assertFalse(cursor.contains(OVERWORLD, 0, -1));
        assertEquals(new HuntSearchCursor.Sector(OVERWORLD, -1, -1),
                HuntSearchCursor.sectorFor(OVERWORLD, -1, -1));
        assertEquals(2, cursor.visitedCount());
    }

    @Test
    void capacityRefusesNewSectorsWithoutEvictingOldHistory() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        for (int index = 0; index < HuntSearchCursor.MAX_VISITED_SECTORS; index++) {
            assertTrue(cursor.markVisited(
                    OVERWORLD, index * HuntSearchCursor.SECTOR_SIZE, 0));
        }

        assertTrue(cursor.isFull());
        assertEquals(HuntSearchCursor.MAX_VISITED_SECTORS, cursor.visitedCount());
        assertFalse(cursor.markVisited(OVERWORLD,
                HuntSearchCursor.MAX_VISITED_SECTORS * HuntSearchCursor.SECTOR_SIZE, 0));
        assertFalse(cursor.markVisited(OVERWORLD, 0, 0),
                "marking an existing sector must not pretend to add it");
        assertTrue(cursor.contains(OVERWORLD, 0, 0),
                "the oldest sector must never be evicted");
        assertFalse(cursor.contains(OVERWORLD,
                HuntSearchCursor.MAX_VISITED_SECTORS * HuntSearchCursor.SECTOR_SIZE, 0));

        HuntSearchCursor restored = HuntSearchCursor.decode(cursor.encode()).orElseThrow();
        assertEquals(HuntSearchCursor.MAX_VISITED_SECTORS, restored.visitedCount());
        assertTrue(restored.contains(OVERWORLD, 0, 0));
    }

    @Test
    void equivalentStateHasDeterministicCheckpointContentAndOrder() {
        HuntSearchCursor forward = HuntSearchCursor.initial();
        HuntSearchCursor reverse = HuntSearchCursor.initial();
        List<HuntSearchCursor.Sector> sectors = List.of(
                new HuntSearchCursor.Sector(NETHER, -2, 7),
                new HuntSearchCursor.Sector(OVERWORLD, 4, -3),
                new HuntSearchCursor.Sector(OVERWORLD, -1, 8));

        sectors.forEach(forward::markVisited);
        for (int index = sectors.size() - 1; index >= 0; index--) {
            reverse.markVisited(sectors.get(index));
        }
        forward.claimNextOrdinal();
        reverse.claimNextOrdinal();
        assertTrue(forward.setSurfaceAnchorIfAbsent(OVERWORLD, 12, 70, -9));
        assertTrue(reverse.setSurfaceAnchorIfAbsent(OVERWORLD, 12, 70, -9));

        assertEquals(forward.encode(), reverse.encode());
        assertEquals(
                List.of("schema", "cursor_kind", "next_ordinal", "revision",
                        "visited_count", "surface_anchor",
                        "sector.0000", "sector.0001", "sector.0002"),
                new ArrayList<>(forward.encode().keySet()));
        assertEquals(forward.visitedSectors(),
                HuntSearchCursor.decode(forward.encode()).orElseThrow().visitedSectors());
    }

    @Test
    void surfaceAnchorIsDimensionBoundSetOnceAndCheckpointed() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        HuntSearchCursor.SurfaceAnchor original =
                new HuntSearchCursor.SurfaceAnchor(OVERWORLD, 9, 68, -17);

        assertTrue(cursor.setSurfaceAnchorIfAbsent(original));
        assertFalse(cursor.setSurfaceAnchorIfAbsent(NETHER, 99, 80, 99));
        assertEquals(original, cursor.surfaceAnchor().orElseThrow());
        assertEquals(original, cursor.surfaceAnchor(OVERWORLD).orElseThrow());
        assertTrue(cursor.surfaceAnchor(NETHER).isEmpty(),
                "a surface coordinate must not leak across dimensions");

        HuntSearchCursor restored = HuntSearchCursor.decode(cursor.encode()).orElseThrow();
        assertEquals(original, restored.surfaceAnchor().orElseThrow());
        assertEquals(original, restored.surfaceAnchor(OVERWORLD).orElseThrow());
        assertTrue(restored.surfaceAnchor(NETHER).isEmpty());
    }

    @Test
    void nullAndEmptyLegacyCheckpointsRestoreFreshState() {
        for (Map<String, String> legacy : List.<Map<String, String>>of(
                Map.of(), new LinkedHashMap<>())) {
            HuntSearchCursor restored = HuntSearchCursor.decode(legacy).orElseThrow();
            assertEquals(HuntSearchCursor.CURRENT_SCHEMA, restored.schema());
            assertEquals(0L, restored.nextOrdinal());
            assertEquals(0L, restored.revision());
            assertEquals(0, restored.visitedCount());
            assertTrue(restored.surfaceAnchor().isEmpty());
        }
        assertTrue(HuntSearchCursor.decode(null).isPresent());
    }

    @Test
    void malformedNonEmptyCheckpointsFailClosed() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.markVisited(OVERWORLD, 0, 0);
        cursor.markVisited(NETHER, 64, -64);
        cursor.setSurfaceAnchorIfAbsent(OVERWORLD, 1, 65, 2);
        Map<String, String> valid = cursor.encode();

        for (String required : List.of(
                "schema", "cursor_kind", "next_ordinal", "visited_count",
                "revision", "surface_anchor", "sector.0000", "sector.0001")) {
            Map<String, String> missing = new LinkedHashMap<>(valid);
            missing.remove(required);
            assertTrue(HuntSearchCursor.decode(missing).isEmpty(),
                    () -> "missing " + required + " must fail closed");
        }

        assertRejectedWith(valid, "schema", "2");
        assertRejectedWith(valid, "schema", "01");
        assertRejectedWith(valid, "cursor_kind", "other");
        assertRejectedWith(valid, "next_ordinal", "-1");
        assertRejectedWith(valid, "next_ordinal", "+1");
        assertRejectedWith(valid, "revision", "-1");
        assertRejectedWith(valid, "revision", "01");
        assertRejectedWith(valid, "revision", "0");
        assertRejectedWith(valid, "visited_count", "2049");
        assertRejectedWith(valid, "visited_count", "02");
        assertRejectedWith(valid, "surface_anchor", "not-base64,1,2,3");
        assertRejectedWith(valid, "sector.0000", "not-base64,0,0");
        assertRejectedWith(valid, "sector.0000", valid.get("sector.0001"));

        Map<String, String> unknown = new LinkedHashMap<>(valid);
        unknown.put("unexpected", "true");
        assertTrue(HuntSearchCursor.decode(unknown).isEmpty());
    }

    @Test
    void invalidPublicDimensionsAndOrdinalExhaustionAreRejected() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        assertThrows(IllegalArgumentException.class,
                () -> cursor.markVisited(" ", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> cursor.setSurfaceAnchorIfAbsent("\n", 0, 64, 0));
        for (String nonCanonical : List.of(
                "overworld", "Minecraft:overworld", "minecraft:Overworld",
                "minecraft:over world")) {
            assertThrows(IllegalArgumentException.class,
                    () -> cursor.markVisited(nonCanonical, 0, 0),
                    () -> "accepted non-canonical dimension " + nonCanonical);
        }

        Map<String, String> exhausted = new LinkedHashMap<>(cursor.encode());
        exhausted.put("next_ordinal", String.valueOf(Long.MAX_VALUE));
        exhausted.put("revision", String.valueOf(Long.MAX_VALUE));
        HuntSearchCursor restored = HuntSearchCursor.decode(exhausted).orElseThrow();
        assertThrows(IllegalStateException.class, restored::claimNextOrdinal);
    }

    @Test
    void dirtyAndRevisionChangeOnlyForDurableMutations() {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        assertEquals(0L, cursor.revision());
        assertFalse(cursor.consumeDirty());

        cursor.claimNextOrdinal();
        assertEquals(1L, cursor.revision());
        assertTrue(cursor.consumeDirty());
        assertFalse(cursor.consumeDirty());
        assertEquals(1L, cursor.revision(),
                "consuming dirty must not change durable state");

        assertTrue(cursor.markVisited(OVERWORLD, 0, 0));
        assertEquals(2L, cursor.revision());
        assertTrue(cursor.consumeDirty());
        assertFalse(cursor.markVisited(OVERWORLD, 31, 31));
        assertEquals(2L, cursor.revision());
        assertFalse(cursor.consumeDirty());

        assertTrue(cursor.setSurfaceAnchorIfAbsent(OVERWORLD, 2, 70, 3));
        assertEquals(3L, cursor.revision());
        assertTrue(cursor.consumeDirty());
        assertFalse(cursor.setSurfaceAnchorIfAbsent(NETHER, 8, 80, 9));
        assertEquals(3L, cursor.revision());
        assertFalse(cursor.consumeDirty());

        HuntSearchCursor restored = HuntSearchCursor.decode(cursor.encode()).orElseThrow();
        assertEquals(3L, restored.revision());
        assertFalse(restored.consumeDirty(),
                "restoring an already persisted revision must start clean");
    }

    private static void assertRejectedWith(Map<String, String> valid,
                                           String key, String value) {
        Map<String, String> malformed = new LinkedHashMap<>(valid);
        malformed.put(key, value);
        assertTrue(HuntSearchCursor.decode(malformed).isEmpty(),
                () -> "cursor must reject " + key + "=" + value);
    }
}
