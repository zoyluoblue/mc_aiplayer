package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HuntPickupCheckpointTest {
    private static final UUID FIRST = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString(
            "00000000-0000-0000-0000-000000000002");

    @Test
    void strictOpenCheckpointDecodesBoundUnits() {
        Map<String, String> checkpoint = openCheckpoint(
                FIRST + "=2;" + SECOND + "=1");

        HuntPickupCheckpoint.Metadata restored =
                HuntPickupCheckpoint.inspect(checkpoint).orElseThrow();

        assertTrue(restored.open());
        assertEquals(3, restored.boundUnits());
        assertEquals("minecraft:beef", restored.expectedRawItemId());
        assertEquals(Map.of(FIRST, 2, SECOND, 1), restored.boundDropUnits());
    }

    @Test
    void codecRejectsMissingUnknownAndNonCanonicalFields() {
        Map<String, String> missing = new LinkedHashMap<>(openCheckpoint("none"));
        missing.remove("pickup_origin");
        assertTrue(HuntPickupCheckpoint.inspect(missing).isEmpty());

        Map<String, String> unknown = new LinkedHashMap<>(openCheckpoint("none"));
        unknown.put("extra", "value");
        assertTrue(HuntPickupCheckpoint.inspect(unknown).isEmpty());

        Map<String, String> nonCanonical = new LinkedHashMap<>(openCheckpoint("none"));
        nonCanonical.put("target_count", "01");
        assertTrue(HuntPickupCheckpoint.inspect(nonCanonical).isEmpty());

        Map<String, String> unsorted = new LinkedHashMap<>(openCheckpoint(
                SECOND + "=1;" + FIRST + "=1"));
        assertTrue(HuntPickupCheckpoint.inspect(unsorted).isEmpty());
    }

    @Test
    void codecRejectsDuplicateOrOverCapacityBoundUnits() {
        Map<String, String> duplicate = new LinkedHashMap<>(openCheckpoint(
                FIRST + "=1;" + FIRST + "=1"));
        assertTrue(HuntPickupCheckpoint.inspect(duplicate).isEmpty());

        Map<String, String> tooManyUnits = new LinkedHashMap<>(openCheckpoint(
                FIRST + "=65"));
        assertTrue(HuntPickupCheckpoint.inspect(tooManyUnits).isEmpty());
    }

    @Test
    void closedNoRawRequiresAbsenceOfBoundUnits() {
        Map<String, String> valid = new LinkedHashMap<>(openCheckpoint("none"));
        valid.put("transaction_state", "CLOSED_NO_RAW");
        assertTrue(HuntPickupCheckpoint.inspect(valid).isPresent());

        valid.put("bound_drop_units", FIRST + "=1");
        assertTrue(HuntPickupCheckpoint.inspect(valid).isEmpty());
    }

    @Test
    void worldTimeAgeCannotRollbackOrRefreshBindWindow() {
        assertEquals(239L, HuntPickupCheckpoint.ageAt(100L, 339L));
        assertEquals(-1L, HuntPickupCheckpoint.ageAt(100L, 99L));
        assertTrue(HuntPickupCheckpoint.canBindFreshDropAtAge(40L));
        assertFalse(HuntPickupCheckpoint.canBindFreshDropAtAge(41L));
    }

    @Test
    void boundUnitsRequireInventoryOrPickupStatCoverage() {
        assertFalse(HuntPickupCheckpoint.collectionCoversBoundUnits(
                10, 11, 20, 21, 2));
        assertTrue(HuntPickupCheckpoint.collectionCoversBoundUnits(
                10, 12, 20, 20, 2));
        assertTrue(HuntPickupCheckpoint.collectionCoversBoundUnits(
                10, 10, 20, 22, 2));
    }

    @Test
    void settlementRestoreAcceptsOnlyOpenTransactions() {
        assertTrue(HuntPickupCheckpoint.settlementRestore(HuntTask.TransactionState.OPEN));
        assertFalse(HuntPickupCheckpoint.settlementRestore(
                HuntTask.TransactionState.CLOSED_COLLECTED));
        assertFalse(HuntPickupCheckpoint.settlementRestore(
                HuntTask.TransactionState.CLOSED_NO_RAW));
        assertFalse(HuntPickupCheckpoint.settlementRestore(null));
    }

    @Test
    void checkpointOnlyFailsClosedOnStructuralCorruption() {
        // Unparseable payload with content = corruption; an absent checkpoint simply hunts fresh.
        assertTrue(HuntPickupCheckpoint.checkpointStructurallyInvalid(
                Map.of("task_schema", "1"), java.util.Optional.empty()));
        assertFalse(HuntPickupCheckpoint.checkpointStructurallyInvalid(
                Map.of(), java.util.Optional.empty()));
        assertFalse(HuntPickupCheckpoint.checkpointStructurallyInvalid(
                null, java.util.Optional.empty()));
    }

    public static Map<String, String> openCheckpoint(String units) {
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("task_schema", "1");
        checkpoint.put("cursor_kind", "hunt_pickup");
        checkpoint.put("transaction_state", "OPEN");
        checkpoint.put("target_count", "8");
        checkpoint.put("require_full_quota", "true");
        checkpoint.put("dimension", "minecraft:overworld");
        checkpoint.put("expected_raw_item", "minecraft:beef");
        checkpoint.put("pickup_origin", "10,64,10");
        checkpoint.put("pickup_return_anchor", "9,64,10");
        checkpoint.put("inventory_baseline", "3");
        checkpoint.put("pickup_stat_baseline", "7");
        checkpoint.put("aux_inventory_baseline", "1");
        checkpoint.put("aux_pickup_stat_baseline", "4");
        checkpoint.put("pickup_started_world_time", "1200");
        checkpoint.put("bound_drop_units", units);
        return Map.copyOf(checkpoint);
    }
}
