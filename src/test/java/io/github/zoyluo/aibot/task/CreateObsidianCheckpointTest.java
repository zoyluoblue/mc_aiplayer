package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateObsidianCheckpointTest {
    private static final int TARGET = 32;
    private static final int MAX_BUDGET = 76800;

    @Test
    void liveSourceAndActiveBreakRoundTripAndResumeWithRecoveryFirst() {
        Map<String, String> values = validCheckpoint(CreateObsidianTask.Phase.MINE);
        values.put("water_source", "9,-53,4");
        values.put("water_bucket_baseline", "4");
        values.put("obsidian", "9,-54,5");
        values.put("stand", "8,-53,5");
        values.put("obsidian_stand_hint", "8,-53,5");
        values.put("protection_prepared", "true");
        values.put("active_break_pos", "9,-54,5");
        values.put("active_break_inventory", "11");

        CreateObsidianTask.ObsidianCheckpoint decoded = decode(values);

        assertEquals(CreateObsidianTask.Phase.RECOVER_WATER, decoded.resumePhase());
        assertEquals(new BlockPos(9, -53, 4), decoded.waterSource());
        assertEquals(4, decoded.waterBucketBaseline());
        assertEquals(new BlockPos(9, -54, 5), decoded.obsidian());
        assertEquals(new BlockPos(8, -53, 5), decoded.standPos());
        assertEquals(decoded.standPos(), decoded.obsidianStandHint());
        assertTrue(decoded.protectionPrepared());
        assertEquals(decoded.obsidian(), decoded.activeBreakPos());
        assertEquals(11, decoded.activeBreakInventoryBaseline());
        assertEquals(values, decoded.encode());
    }

    @Test
    void lavaFormationAndPendingPickupLedgersRoundTrip() {
        Map<String, String> forming = validCheckpoint(CreateObsidianTask.Phase.WAIT_FORM);
        forming.put("water_source", "4,-51,3");
        forming.put("water_bucket_baseline", "2");
        forming.put("lava_target", "4,-52,4");
        forming.put("stand", "3,-51,4");
        forming.put("pour_support", "3,-51,3");
        forming.put("pour_face", "EAST");

        CreateObsidianTask.ObsidianCheckpoint decodedFormation = decode(forming);
        assertEquals(new BlockPos(4, -52, 4), decodedFormation.lavaTarget());
        assertEquals(new BlockPos(3, -51, 3), decodedFormation.pourPlan().support());
        assertEquals(forming, decodedFormation.encode());

        Map<String, String> pickup = validCheckpoint(CreateObsidianTask.Phase.PICKUP);
        pickup.put("pending_pickup_pos", "12,-54,-8");
        pickup.put("pending_pickup_inventory", "15");
        pickup.put("pickup_gain_budget", "390");
        pickup.put("return_rim", "11,-53,-8");

        CreateObsidianTask.ObsidianCheckpoint decodedPickup = decode(pickup);
        assertEquals(CreateObsidianTask.Phase.PICKUP, decodedPickup.resumePhase());
        assertEquals(new BlockPos(12, -54, -8), decodedPickup.pickupPos());
        assertEquals(decodedPickup.pickupPos(), decodedPickup.pickupLastSeenPos(),
                "older schema-3 pickup debt must fall back to its durable break cell");
        assertEquals(15, decodedPickup.pickupInventoryBaseline());
        assertEquals(390, decodedPickup.pickupGainBudget());
        assertEquals(new BlockPos(11, -53, -8), decodedPickup.returnRim());
        assertEquals(pickup, decodedPickup.encode());

        Map<String, String> movingPickup = new LinkedHashMap<>(pickup);
        movingPickup.put("pending_pickup_last_seen_pos", "17,-57,-16");
        CreateObsidianTask.ObsidianCheckpoint decodedMovingPickup = decode(movingPickup);
        assertEquals(new BlockPos(17, -57, -16), decodedMovingPickup.pickupLastSeenPos());
        assertEquals(movingPickup, decodedMovingPickup.encode());
    }

    @Test
    void pickupProtectionWindowSurvivesRestartBeforeWaterRecovery() {
        Map<String, String> protectedPickup = validCheckpoint(
                CreateObsidianTask.Phase.PROTECT_PICKUP);
        protectedPickup.put("water_source", "12,-54,-8");
        protectedPickup.put("water_bucket_baseline", "1");
        protectedPickup.put("pending_pickup_pos", "12,-54,-8");
        protectedPickup.put("pending_pickup_last_seen_pos", "13,-55,-9");
        protectedPickup.put("pending_pickup_inventory", "15");
        protectedPickup.put("return_rim", "11,-53,-8");

        CreateObsidianTask.ObsidianCheckpoint decoded = decode(protectedPickup);

        assertEquals(CreateObsidianTask.Phase.PROTECT_PICKUP, decoded.resumePhase());
        assertEquals(350, decoded.phaseStartedBudget(),
                "restart must not reset the remaining water-spread window");
        assertEquals(new BlockPos(13, -55, -9), decoded.pickupLastSeenPos());
        assertEquals(protectedPickup, decoded.encode());
    }

    @Test
    void missionMetadataRetainsOriginalTargetAndOpenTransaction() {
        Map<String, String> safe = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        CreateObsidianTask.RestoreMetadata safeMetadata =
                CreateObsidianTask.inspectCheckpoint(safe).orElseThrow();
        assertEquals(TARGET, safeMetadata.targetCount());
        assertFalse(safeMetadata.transactionOpen());

        Map<String, String> liveSource = validCheckpoint(CreateObsidianTask.Phase.RECOVER_WATER);
        liveSource.put("water_source", "1,-50,1");
        liveSource.put("water_bucket_baseline", "1");
        CreateObsidianTask.RestoreMetadata debtMetadata =
                CreateObsidianTask.inspectCheckpoint(liveSource).orElseThrow();
        assertEquals(TARGET, debtMetadata.targetCount());
        assertTrue(debtMetadata.transactionOpen());
    }

    @Test
    void serviceBoundariesAreEightSixteenTwentyFourButNeverTheFinalThirtyTwo() {
        assertEquals(0, CreateObsidianTask.nextServiceBoundary(0, 7, TARGET));
        assertEquals(8, CreateObsidianTask.nextServiceBoundary(0, 8, TARGET));
        assertEquals(8, CreateObsidianTask.nextServiceBoundary(0, 9, TARGET),
                "a 7-to-9 pickup jump must not skip boundary 8");
        assertEquals(16, CreateObsidianTask.nextServiceBoundary(8, 16, TARGET));
        assertEquals(24, CreateObsidianTask.nextServiceBoundary(16, 24, TARGET));
        assertEquals(0, CreateObsidianTask.nextServiceBoundary(24, 31, TARGET));
        assertEquals(0, CreateObsidianTask.nextServiceBoundary(16, 32, TARGET),
                "final completion must outrank a crossed boundary");
    }

    @Test
    void serviceBoundaryAcknowledgementIsDurableAndIdempotent() {
        Map<String, String> pending = validCheckpoint(CreateObsidianTask.Phase.SERVICE_BOUNDARY);
        pending.put("collected", "9");
        pending.put("produced", "9");
        pending.put("pending_service_boundary", "8");

        CreateObsidianTask.RestoreMetadata metadata =
                CreateObsidianTask.inspectCheckpoint(pending).orElseThrow();
        assertEquals(TARGET, metadata.targetCount());
        assertEquals(8, metadata.pendingServiceBoundary());
        assertFalse(metadata.transactionOpen());

        Map<String, String> acknowledged = CreateObsidianTask
                .acknowledgeServiceBoundary(pending).orElseThrow();
        CreateObsidianTask.ObsidianCheckpoint decoded = decode(acknowledged);
        assertEquals(CreateObsidianTask.Phase.SCAN, decoded.phase());
        assertEquals(8, decoded.servicedCollected());
        assertEquals(0, decoded.pendingServiceBoundary());
        assertEquals("32", acknowledged.get("target_count"));
        assertEquals("3", acknowledged.get("inventory_baseline"));
        assertEquals(acknowledged, CreateObsidianTask
                .acknowledgeServiceBoundary(acknowledged).orElseThrow());
    }

    @Test
    void serviceBoundaryRejectsEveryOpenTransactionDebt() {
        Map<String, String> pending = validCheckpoint(CreateObsidianTask.Phase.SERVICE_BOUNDARY);
        pending.put("collected", "8");
        pending.put("produced", "8");
        pending.put("pending_service_boundary", "8");
        assertTrue(CreateObsidianTask.inspectCheckpoint(pending).isPresent());

        Map<String, String> waterDebt = new LinkedHashMap<>(pending);
        waterDebt.put("water_source", "1,-50,1");
        waterDebt.put("water_bucket_baseline", "1");
        assertInvalid(waterDebt);

        Map<String, String> pickupDebt = new LinkedHashMap<>(pending);
        pickupDebt.put("pending_pickup_pos", "1,-50,2");
        pickupDebt.put("pending_pickup_inventory", "7");
        pickupDebt.put("return_rim", "0,-50,2");
        assertInvalid(pickupDebt);

        Map<String, String> activeBreakDebt = new LinkedHashMap<>(pending);
        activeBreakDebt.put("obsidian", "1,-51,2");
        activeBreakDebt.put("active_break_pos", "1,-51,2");
        activeBreakDebt.put("active_break_inventory", "7");
        assertInvalid(activeBreakDebt);

        Map<String, String> returnDebt = new LinkedHashMap<>(pending);
        returnDebt.put("return_rim", "0,-50,2");
        assertInvalid(returnDebt);
    }

    @Test
    void crossedBoundaryMayPersistPickupDebtButCannotPublishServiceYet() {
        Map<String, String> pickup = validCheckpoint(CreateObsidianTask.Phase.PICKUP);
        pickup.put("collected", "8");
        pickup.put("produced", "8");
        pickup.put("pending_service_boundary", "8");
        pickup.put("pending_pickup_pos", "12,-54,-8");
        pickup.put("pending_pickup_inventory", "7");
        pickup.put("return_rim", "11,-53,-8");

        CreateObsidianTask.ObsidianCheckpoint decoded = decode(pickup);
        assertEquals(CreateObsidianTask.Phase.PICKUP, decoded.phase());
        assertEquals(8, decoded.pendingServiceBoundary());
        CreateObsidianTask.RestoreMetadata metadata =
                CreateObsidianTask.inspectCheckpoint(pickup).orElseThrow();
        assertTrue(metadata.transactionOpen());
        assertEquals(8, metadata.pendingServiceBoundary());
    }

    @Test
    void legacyCheckpointCannotInventCompletedService() {
        Map<String, String> legacy = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        legacy.put("task_schema", "1");
        legacy.remove("scan_resume_face");
        legacy.remove("serviced_collected");
        legacy.remove("pending_service_boundary");

        CreateObsidianTask.ObsidianCheckpoint decoded = decode(legacy);
        assertEquals(0, decoded.servicedCollected());
        assertEquals(0, decoded.pendingServiceBoundary());
        assertEquals("3", decoded.encode().get("task_schema"));
        assertEquals(legacy.get("work_face"), decoded.encode().get("scan_resume_face"));
    }

    @Test
    void previousSchemaMigratesScanResumeToItsFactualCursorFace() {
        Map<String, String> previous = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        previous.put("task_schema", "2");
        previous.remove("scan_resume_face");

        CreateObsidianTask.ObsidianCheckpoint decoded = decode(previous);
        assertEquals(decoded.searchCursor().face(), decoded.scanResumeFace());
        assertEquals("3", decoded.encode().get("task_schema"));

        Map<String, String> truncatedCurrent = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        truncatedCurrent.remove("scan_resume_face");
        assertInvalid(truncatedCurrent);
    }

    @Test
    void timeoutCheckpointSelfDecodesAndRestoreRetainsTheSameTerminalFailure() {
        CreateObsidianTask exhausted = new CreateObsidianTask(TARGET);
        // Unit-drive the hard boundary without a Minecraft server. onTick must decide the timeout
        // before touching the bot, exactly as the real AbstractTask tick does after incrementing.
        exhausted.state = TaskState.RUNNING;
        exhausted.elapsed = MAX_BUDGET - 1;
        exhausted.tick(null);

        assertEquals(TaskState.FAILED, exhausted.state());
        String terminalReason = exhausted.failureReason();
        assertEquals("create_obsidian_timeout collected=0", terminalReason);

        Map<String, String> terminal = exhausted.checkpoint();
        assertEquals(String.valueOf(MAX_BUDGET), terminal.get("budget_used"));
        assertEquals(MAX_BUDGET, decode(terminal).budgetUsed(),
                "a terminal checkpoint must remain inside its own decoder domain");

        CreateObsidianTask restored = new CreateObsidianTask(TARGET, terminal);
        restored.state = TaskState.RUNNING;
        restored.onStart(null);
        restored.tick(null);

        assertEquals(TaskState.FAILED, restored.state(),
                "restoring terminal evidence must not grant a fresh budget window");
        assertEquals(terminalReason, restored.failureReason());
        Map<String, String> restoredTerminal = restored.checkpoint();
        assertEquals(String.valueOf(MAX_BUDGET), restoredTerminal.get("budget_used"));
        assertEquals(MAX_BUDGET, decode(restoredTerminal).budgetUsed());
    }

    @Test
    void damagedTransactionShapesFailClosed() {
        Map<String, String> sourceWithoutBaseline = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        sourceWithoutBaseline.put("water_source", "1,-50,1");
        assertInvalid(sourceWithoutBaseline);

        Map<String, String> baselineWithoutRecovery = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        baselineWithoutRecovery.put("water_bucket_baseline", "1");
        assertInvalid(baselineWithoutRecovery);

        Map<String, String> partialPickup = validCheckpoint(CreateObsidianTask.Phase.PICKUP);
        partialPickup.put("pending_pickup_pos", "1,-50,2");
        partialPickup.put("return_rim", "0,-49,2");
        assertInvalid(partialPickup);

        Map<String, String> lastSeenWithoutPickup = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        lastSeenWithoutPickup.put("pending_pickup_last_seen_pos", "1,-50,2");
        assertInvalid(lastSeenWithoutPickup);

        Map<String, String> outOfBoundsLastSeen = validCheckpoint(CreateObsidianTask.Phase.PICKUP);
        outOfBoundsLastSeen.put("pending_pickup_pos", "1,-50,2");
        outOfBoundsLastSeen.put("pending_pickup_last_seen_pos", "10,-50,2");
        outOfBoundsLastSeen.put("pending_pickup_inventory", "7");
        outOfBoundsLastSeen.put("return_rim", "0,-49,2");
        assertInvalid(outOfBoundsLastSeen);

        Map<String, String> drainingPickupWithoutRim = validCheckpoint(
                CreateObsidianTask.Phase.WAIT_DRAIN);
        drainingPickupWithoutRim.put("pending_pickup_pos", "1,-50,2");
        drainingPickupWithoutRim.put("pending_pickup_inventory", "7");
        assertInvalid(drainingPickupWithoutRim);

        Map<String, String> mineWithoutActiveLedger = validCheckpoint(CreateObsidianTask.Phase.MINE);
        mineWithoutActiveLedger.put("obsidian", "1,-50,3");
        mineWithoutActiveLedger.put("stand", "0,-49,3");
        assertInvalid(mineWithoutActiveLedger);

        Map<String, String> mismatchedActiveLedger = new LinkedHashMap<>(mineWithoutActiveLedger);
        mismatchedActiveLedger.put("active_break_pos", "2,-50,3");
        mismatchedActiveLedger.put("active_break_inventory", "8");
        assertInvalid(mismatchedActiveLedger);

        Map<String, String> malformedPosition = validCheckpoint(CreateObsidianTask.Phase.RECOVER_WATER);
        malformedPosition.put("water_source", "broken");
        malformedPosition.put("water_bucket_baseline", "1");
        assertInvalid(malformedPosition);

        Map<String, String> partialPourPlan = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        partialPourPlan.put("pour_support", "0,-50,0");
        assertInvalid(partialPourPlan);

        Map<String, String> exhaustedBudget = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        exhaustedBudget.put("budget_used", String.valueOf(MAX_BUDGET + 1));
        assertInvalid(exhaustedBudget);

        Map<String, String> wrongTarget = validCheckpoint(CreateObsidianTask.Phase.SCAN);
        wrongTarget.put("target_count", "31");
        assertInvalid(wrongTarget);
        assertTrue(new CreateObsidianTask(TARGET, wrongTarget).checkpoint().isEmpty(),
                "a rejected restore must not synthesize a replacement checkpoint");
    }

    private static CreateObsidianTask.ObsidianCheckpoint decode(Map<String, String> values) {
        return CreateObsidianTask.ObsidianCheckpoint.decode(values, TARGET, MAX_BUDGET)
                .orElseThrow();
    }

    private static void assertInvalid(Map<String, String> values) {
        assertFalse(CreateObsidianTask.ObsidianCheckpoint.decode(values, TARGET, MAX_BUDGET).isPresent());
    }

    private static Map<String, String> validCheckpoint(CreateObsidianTask.Phase phase) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(new BlockPos(0, -50, 0), 12)
                        .withProduced(7)
                        .encode());
        values.put("task_schema", "3");
        values.put("target_count", String.valueOf(TARGET));
        values.put("phase", phase.name());
        values.put("scan_resume_face", "0,-50,0");
        values.put("inventory_baseline", "3");
        values.put("collected", "7");
        values.put("serviced_collected", "0");
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "400");
        values.put("phase_started", "350");
        values.put("last_progress", "390");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", "-1");
        values.put("pending_pickup_inventory", "-1");
        values.put("pickup_gain_budget", "-1");
        values.put("active_break_inventory", "-1");
        values.put("protection_prepared", "false");
        return values;
    }
}
