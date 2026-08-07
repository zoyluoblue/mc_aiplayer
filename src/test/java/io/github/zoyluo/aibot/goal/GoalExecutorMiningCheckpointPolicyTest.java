package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.mining.MiningMissionBudget;
import io.github.zoyluo.aibot.task.OreDigTask;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorMiningCheckpointPolicyTest {
    private static final String COAL = "minecraft:coal_ore,minecraft:deepslate_coal_ore";
    private static final String IRON = "minecraft:deepslate_iron_ore,minecraft:iron_ore";
    private static final String DIAMOND = "minecraft:deepslate_diamond_ore,minecraft:diamond_ore";

    @Test
    void prerequisiteCoalOrIronCannotOverwriteDiamondCursor() {
        assertFalse(GoalExecutor.shouldReplaceMiningCheckpoint(DIAMOND, COAL, false));
        assertFalse(GoalExecutor.shouldReplaceMiningCheckpoint(DIAMOND, IRON, false));
    }

    @Test
    void diamondReplacesEarlierBootstrapCursorEvenForCompositeGoal() {
        assertTrue(GoalExecutor.shouldReplaceMiningCheckpoint(COAL, DIAMOND, false));
        assertTrue(GoalExecutor.shouldReplaceMiningCheckpoint(IRON, DIAMOND, false));
    }

    @Test
    void exactFamilyAndDirectGoalProgressRemainReplaceable() {
        assertTrue(GoalExecutor.shouldReplaceMiningCheckpoint(DIAMOND, DIAMOND, false));
        assertTrue(GoalExecutor.shouldReplaceMiningCheckpoint(COAL, IRON, true));
        assertFalse(GoalExecutor.shouldReplaceMiningCheckpoint(DIAMOND, "", true));
    }

    @Test
    void plannedCraftCountIsAddedToExistingInventory() {
        org.junit.jupiter.api.Assertions.assertEquals(5, GoalExecutor.craftTargetCount(1, 4));
        org.junit.jupiter.api.Assertions.assertEquals(4, GoalExecutor.craftTargetCount(0, 4));
    }

    @Test
    void plannedGatherDeltaIsConvertedToAbsoluteFamilyQuota() {
        org.junit.jupiter.api.Assertions.assertEquals(32, GoalExecutor.gatherTargetCount(29, 3));
        org.junit.jupiter.api.Assertions.assertEquals(3, GoalExecutor.gatherTargetCount(0, 3));
    }

    @Test
    void capacityHandoffProtectsTheDirectObsidianBootstrapHorizon() {
        assertEquals(76,
                GoalExecutor.capacityHandoffStoneLikeReserveForObsidianTarget(32));
        assertEquals(MiningBudget.EMERGENCY_STONE_LIKE,
                GoalExecutor.capacityHandoffStoneLikeReserveForObsidianTarget(0));
        assertEquals(76, GoalExecutor.capacityHandoffStoneLikeReserveForTargets(32, 0));
        assertEquals(MiningBudget.EMERGENCY_STONE_LIKE,
                GoalExecutor.capacityHandoffStoneLikeReserveForTargets(0, 64));
        assertEquals(MiningBudget.EMERGENCY_STONE_LIKE,
                GoalExecutor.capacityHandoffStoneLikeReserveForTargets(0, 0));
    }

    @Test
    void miningParentReserveTracksTheDurableMissionPhase() {
        assertEquals(76, GoalExecutor.miningParentStoneLikeReserveForTargets(
                32, 0, false, 0));
        assertEquals(MiningBudget.EMERGENCY_STONE_LIKE,
                GoalExecutor.miningParentStoneLikeReserveForTargets(
                        0, 64, false, 0));
        assertEquals(MiningBudget.RARE_SERVICE_PROTECTED_STONE_LIKE,
                GoalExecutor.miningParentStoneLikeReserveForTargets(
                        0, 64, true, 0));
        assertEquals(MiningBudget.EMERGENCY_STONE_LIKE,
                GoalExecutor.miningParentStoneLikeReserveForTargets(
                        0, 64, true, 1));
    }

    @Test
    void activeServicePocketIncludesEveryPhysicalPhaseAndCommittedVerifiedSeal() {
        for (String phase : Set.of(
                "OPEN_DISPOSAL_POCKET",
                "CAPTURE_DISPOSAL_BASELINE",
                "DROP_DISPOSABLE",
                "SETTLE_DISPOSABLE",
                "RETURN_TO_DISPOSAL_FACE",
                "SEAL_DISPOSAL_POCKET")) {
            assertTrue(GoalExecutor.hasActiveServicePocket(Map.of(
                    "phase", phase,
                    "pocket_drop_committed", "true",
                    "pocket_ledger_verified", "true")), phase);
        }
        for (String identityKey : Set.of(
                "pocket_entry",
                "pocket_sink",
                "pocket_direction",
                "pocket_entities",
                "pocket_lineage",
                "pocket_baseline",
                "pocket_ledger",
                "pocket_drop_committed",
                "pocket_ledger_verified",
                "pocket_phase_started",
                "pocket_failure",
                "pocket_clear_index")) {
            assertTrue(GoalExecutor.hasActiveServicePocket(Map.of(
                    "phase", "SUPPLIES", identityKey, "identity")), identityKey);
        }
        assertFalse(GoalExecutor.hasActiveServicePocket(Map.of(
                "phase", "SUPPLIES",
                "unrelated", "true_without_schema_key")));
        assertFalse(GoalExecutor.hasActiveServicePocket(Map.of("phase", "DONE")));
        assertFalse(GoalExecutor.hasActiveServicePocket(Map.of(
                "task_schema", "4", "unrelated", "ordinary_checkpoint_without_phase")));
    }

    @Test
    void capacityParentRequiresOneOpenOrdinaryDebit() {
        assertTrue(GoalExecutor.validCapacityParentRetry(Optional.of(
                restoreMetadata(true, 0, 0, true))));
        assertFalse(GoalExecutor.validCapacityParentRetry(Optional.of(
                restoreMetadata(true, 0, 0, false))));
        assertFalse(GoalExecutor.validCapacityParentRetry(Optional.of(
                restoreMetadata(false, 0, 0, true))));
        assertFalse(GoalExecutor.validCapacityParentRetry(Optional.of(
                restoreMetadata(true, 64, 0, true))));
        assertFalse(GoalExecutor.validCapacityParentRetry(Optional.empty()));
    }

    @Test
    void committedCapacityParentRequiresExactClosedOrdinaryTaskWithoutPhysicalDebt() {
        Map<String, String> closedParent = Map.of(
                "batch_open", "false",
                "ore_fingerprint", COAL);
        OreDigTask.RestoreMetadata closedOrdinary = restoreMetadata(false, 0, 0, false);

        assertTrue(GoalExecutor.validCommittedCapacityParent(
                Optional.of(closedOrdinary), GoalStep.Kind.MINE_ORE,
                closedParent, closedParent));
        assertFalse(GoalExecutor.validCommittedCapacityParent(
                Optional.of(restoreMetadata(true, 0, 0, true)), GoalStep.Kind.MINE_ORE,
                closedParent, closedParent));
        assertFalse(GoalExecutor.validCommittedCapacityParent(
                Optional.of(closedOrdinary), GoalStep.Kind.MINING_SERVICE,
                closedParent, closedParent));
        assertFalse(GoalExecutor.validCommittedCapacityParent(
                Optional.of(closedOrdinary), GoalStep.Kind.MINE_ORE,
                Map.of("batch_open", "false"), closedParent));
        assertFalse(GoalExecutor.validCommittedCapacityParent(
                Optional.of(restoreMetadata(false, 64, 0, false)), GoalStep.Kind.MINE_ORE,
                closedParent, closedParent));
        Map<String, String> physicalDebt = new java.util.LinkedHashMap<>(closedParent);
        physicalDebt.put("active_break_pos", "0,0,0");
        assertFalse(GoalExecutor.validCommittedCapacityParent(
                Optional.of(closedOrdinary), GoalStep.Kind.MINE_ORE,
                physicalDebt, physicalDebt));
    }

    @Test
    void waterRelocationRetiresOnlyClosedOrdinaryCursorWithoutPhysicalDebt() {
        OreDigTask.RestoreMetadata closedOrdinary = restoreMetadata(false, 0);
        OreDigTask.RestoreMetadata openOrdinary = restoreMetadata(true, 0);
        OreDigTask.RestoreMetadata closedRare = restoreMetadata(false, 64);

        assertTrue(GoalExecutor.shouldRetireRelocatedOrdinaryMiningCheckpoint(
                Optional.of(closedOrdinary), false));
        assertFalse(GoalExecutor.shouldRetireRelocatedOrdinaryMiningCheckpoint(
                Optional.of(openOrdinary), false));
        assertFalse(GoalExecutor.shouldRetireRelocatedOrdinaryMiningCheckpoint(
                Optional.of(closedRare), false));
        assertFalse(GoalExecutor.shouldRetireRelocatedOrdinaryMiningCheckpoint(
                Optional.of(closedOrdinary), true));
        assertFalse(GoalExecutor.shouldRetireRelocatedOrdinaryMiningCheckpoint(
                Optional.empty(), false));
    }

    @Test
    void rareResourceEpochPersistenceIsStrictAndLegacyMissingMeansZero() {
        assertEquals(0, GoalExecutor.decodePersistedRareResourceEpoch(Map.of())
                .orElseThrow());
        assertEquals(1, GoalExecutor.decodePersistedRareResourceEpoch(
                Map.of("rare_resource_retries_used", "1")).orElseThrow());
        assertTrue(GoalExecutor.decodePersistedRareResourceEpoch(
                Map.of("rare_resource_retries_used", "-1")).isEmpty());
        // Margin epochs may persist beyond the per-batch retry; the mission-derived bound
        // (per-batch retry + margin pool) is enforced at the restore site.
        assertEquals(2, GoalExecutor.decodePersistedRareResourceEpoch(
                Map.of("rare_resource_retries_used", "2")).orElseThrow());
        assertTrue(GoalExecutor.decodePersistedRareResourceEpoch(
                Map.of("rare_resource_retries_used", "not-an-int")).isEmpty());
    }

    @Test
    void rareEpochMarginPersistenceIsStrictAndLegacyMissingMeansZero() {
        assertEquals(0, GoalExecutor.decodePersistedRareEpochMarginUsed(Map.of())
                .orElseThrow());
        assertEquals(3, GoalExecutor.decodePersistedRareEpochMarginUsed(
                Map.of("rare_epoch_margin_used", "3")).orElseThrow());
        assertTrue(GoalExecutor.decodePersistedRareEpochMarginUsed(
                Map.of("rare_epoch_margin_used", "-1")).isEmpty());
        assertTrue(GoalExecutor.decodePersistedRareEpochMarginUsed(
                Map.of("rare_epoch_margin_used", "01")).isEmpty());
        assertTrue(GoalExecutor.decodePersistedRareEpochMarginUsed(
                Map.of("rare_epoch_margin_used", "not-an-int")).isEmpty());
    }

    @Test
    void restoredMarginLedgerMustCoverEveryEpochBeyondThePerBatchRetry() {
        // Legal states: margin inside the pool and no epoch beyond retry+margin coverage.
        assertTrue(GoalExecutor.validRestoredRareEpochMargin(0, 0, 4));
        assertTrue(GoalExecutor.validRestoredRareEpochMargin(1, 0, 4));
        assertTrue(GoalExecutor.validRestoredRareEpochMargin(2, 1, 4));
        assertTrue(GoalExecutor.validRestoredRareEpochMargin(5, 4, 4));
        assertTrue(GoalExecutor.validRestoredRareEpochMargin(1, 4, 4));
        // Overdraw or an epoch the margin ledger never paid for fails closed.
        assertFalse(GoalExecutor.validRestoredRareEpochMargin(0, 5, 4));
        assertFalse(GoalExecutor.validRestoredRareEpochMargin(0, -1, 4));
        assertFalse(GoalExecutor.validRestoredRareEpochMargin(2, 0, 4));
        assertFalse(GoalExecutor.validRestoredRareEpochMargin(2, 1, 0));
    }

    @Test
    void capacityParentDeliveredPersistenceIsStrictAndLegacyMissingMeansUnbound() {
        assertEquals(-1, GoalExecutor.decodePersistedCapacityParentDelivered(Map.of())
                .orElseThrow());
        assertEquals(0, GoalExecutor.decodePersistedCapacityParentDelivered(
                Map.of("capacity_parent_delivered", "0")).orElseThrow());
        assertEquals(2, GoalExecutor.decodePersistedCapacityParentDelivered(
                Map.of("capacity_parent_delivered", "2")).orElseThrow());
        assertTrue(GoalExecutor.decodePersistedCapacityParentDelivered(
                Map.of("capacity_parent_delivered", "-1")).isEmpty());
        assertTrue(GoalExecutor.decodePersistedCapacityParentDelivered(
                Map.of("capacity_parent_delivered", "not-an-int")).isEmpty());
    }

    @Test
    void capacityParentServiceCountPersistenceIsStrictAndLegacyMissingMeansZero() {
        assertEquals(0, GoalExecutor.decodePersistedCapacityParentServicesUsed(Map.of())
                .orElseThrow());
        assertEquals(1, GoalExecutor.decodePersistedCapacityParentServicesUsed(
                Map.of("capacity_parent_services_used", "1")).orElseThrow());
        assertEquals(3, GoalExecutor.decodePersistedCapacityParentServicesUsed(
                Map.of("capacity_parent_services_used", "3")).orElseThrow());
        assertTrue(GoalExecutor.decodePersistedCapacityParentServicesUsed(
                Map.of("capacity_parent_services_used", "0")).isEmpty());
        assertTrue(GoalExecutor.decodePersistedCapacityParentServicesUsed(
                Map.of("capacity_parent_services_used", "-1")).isEmpty());
        assertTrue(GoalExecutor.decodePersistedCapacityParentServicesUsed(
                Map.of("capacity_parent_services_used", "not-an-int")).isEmpty());
    }

    @Test
    void rareResourceEpochIsOwnedByTheCurrentOpenBatch() {
        OreDigTask.RestoreMetadata openEpochZero = restoreMetadata(true, 64, 0);
        OreDigTask.RestoreMetadata openEpochOne = restoreMetadata(true, 64, 1);
        OreDigTask.RestoreMetadata closedEpochZero = restoreMetadata(false, 64, 0);

        assertEquals(0, GoalExecutor.normalizeRestoredRareResourceEpoch(
                0, Optional.of(openEpochZero)).orElseThrow());
        // Compatibility with the former mission-global counter: epoch-zero is authoritative.
        assertEquals(0, GoalExecutor.normalizeRestoredRareResourceEpoch(
                1, Optional.of(openEpochZero)).orElseThrow());
        assertEquals(1, GoalExecutor.normalizeRestoredRareResourceEpoch(
                1, Optional.of(openEpochOne)).orElseThrow());
        assertTrue(GoalExecutor.normalizeRestoredRareResourceEpoch(
                0, Optional.of(openEpochOne)).isEmpty());
        assertEquals(0, GoalExecutor.normalizeRestoredRareResourceEpoch(
                1, Optional.of(closedEpochZero)).orElseThrow());
        assertEquals(0, GoalExecutor.normalizeRestoredRareResourceEpoch(
                1, Optional.empty()).orElseThrow());
        assertTrue(GoalExecutor.normalizeRestoredRareResourceEpoch(
                2, Optional.of(openEpochZero)).isEmpty());
    }

    @Test
    void marginEpochsRestoreOnlyWithTheirExactOpenDurableOwner() {
        OreDigTask.RestoreMetadata openEpochTwo = restoreMetadata(true, 64, 2);
        OreDigTask.RestoreMetadata openEpochThree = restoreMetadata(true, 64, 3);
        // 64 targets → capacity 4 (2 regular + 2 capped margin); the durable epoch round-trips.
        assertEquals(2, GoalExecutor.normalizeRestoredRareResourceEpoch(
                2, Optional.of(openEpochTwo)).orElseThrow());
        assertEquals(3, GoalExecutor.normalizeRestoredRareResourceEpoch(
                3, Optional.of(openEpochThree)).orElseThrow());
        // Any mismatch, capacity overflow, or missing owner fails closed.
        assertTrue(GoalExecutor.normalizeRestoredRareResourceEpoch(
                1, Optional.of(openEpochTwo)).isEmpty());
        assertTrue(GoalExecutor.normalizeRestoredRareResourceEpoch(
                4, Optional.of(restoreMetadata(true, 64, 4))).isEmpty());
        assertTrue(GoalExecutor.normalizeRestoredRareResourceEpoch(
                2, Optional.empty()).isEmpty());
        // A one-batch mission (target 8) owns no margin: durable epoch two is out of capacity.
        assertTrue(GoalExecutor.normalizeRestoredRareResourceEpoch(
                2, Optional.of(restoreMetadata(true, 8, 2))).isEmpty());
    }

    @Test
    void rareEpochTimeoutConsumesOnlyTheExactPersistedWindow() {
        assertTrue(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 0, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS),
                "ore_dig_timeout collected=3"));
        assertFalse(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 0, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS - 1),
                "ore_dig_timeout collected=3"));
        assertTrue(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 1, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS * 2),
                "ore_dig_timeout collected=7"));
        assertFalse(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 1, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS),
                "ore_dig_timeout collected=7"));
        assertFalse(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 2, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS * 2),
                "ore_dig_timeout collected=7"));
        // Margin epochs own their exact cumulative window inside the mission capacity of four.
        assertTrue(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 2, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS * 3),
                "ore_dig_timeout collected=7"));
        assertTrue(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 3, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS * 4),
                "ore_dig_timeout collected=7"));
        assertFalse(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 4, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS * 5),
                "ore_dig_timeout collected=7"));
        assertFalse(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(false, 64, 0, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS),
                "ore_dig_timeout collected=3"));
        assertFalse(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 0, 0, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS),
                "ore_dig_timeout collected=3"));
        assertFalse(GoalExecutor.isLongRareResourceEpochTimeout(
                restoreMetadata(true, 64, 0, false,
                        MiningMissionBudget.ORE_DIG_HARD_WINDOW_TICKS),
                "ore_dig_no_progress"));
    }

    private static OreDigTask.RestoreMetadata restoreMetadata(
            boolean batchOpen, int rareMissionTarget) {
        return restoreMetadata(batchOpen, rareMissionTarget, 0);
    }

    private static OreDigTask.RestoreMetadata restoreMetadata(
            boolean batchOpen, int rareMissionTarget, int resourceEpoch) {
        return restoreMetadata(batchOpen, rareMissionTarget, resourceEpoch, false);
    }

    private static OreDigTask.RestoreMetadata restoreMetadata(
            boolean batchOpen,
            int rareMissionTarget,
            int resourceEpoch,
            boolean inventoryServiceUsed) {
        return restoreMetadata(batchOpen, rareMissionTarget, resourceEpoch,
                inventoryServiceUsed, 0);
    }

    private static OreDigTask.RestoreMetadata restoreMetadata(
            boolean batchOpen,
            int rareMissionTarget,
            int resourceEpoch,
            boolean inventoryServiceUsed,
            int budgetUsed) {
        return new OreDigTask.RestoreMetadata(
                Set.of(),
                1,
                0,
                rareMissionTarget,
                batchOpen,
                MiningCursor.initial(BlockPos.ORIGIN, 48),
                budgetUsed,
                40,
                0,
                resourceEpoch,
                inventoryServiceUsed);
    }

}
