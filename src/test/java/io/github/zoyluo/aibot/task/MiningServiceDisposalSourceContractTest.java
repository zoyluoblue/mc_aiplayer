package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningServiceDisposalSourceContractTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void disposalCentersAndStopsTheCursorFaceBeforePublishingOpenDebt()
            throws IOException {
        String service = read("task/MiningServiceTask.java");
        String motion = read("mode/FakePlayerMotion.java");
        int admission = service.indexOf("private void startDisposalPocket(AIPlayerEntity bot,\n"
                + "                                     int requiredFreeSlots,\n"
                + "                                     Direction rejectedDirection");
        int open = service.indexOf("private void openDisposalPocket", admission);
        String body = service.substring(admission, open);

        assertTrue(body.indexOf("ensureCenteredAtWorkFace(bot)")
                        < body.indexOf("enterPocketPhase(Phase.OPEN_DISPOSAL_POCKET)"),
                "disposal must own a stationary centered face before OPEN becomes durable debt");
        assertTrue(service.contains("bot.getVelocity().lengthSquared() <= 1.0E-8D"),
                "an exact center with residual walk velocity must still run the center return");
        assertTrue(motion.contains("bot.setVelocity(Vec3d.ZERO);"));
        assertTrue(motion.contains("bot.setOnGround(true);"));
    }

    @Test
    void disposalUsesVanillaEntitiesWithoutPickupDelayOrInventoryDeletion() throws IOException {
        String service = read("task/MiningServiceTask.java");
        String inventory = read("action/InventoryAction.java");

        assertTrue(service.contains("InventoryAction.dropSlotEntity"));
        assertTrue(inventory.contains("player.dropItem(removed, false, true)"));
        assertFalse(service.contains("setPickupDelay"));
        assertFalse(service.contains("setPickupDelayInfinite"));
        assertFalse(service.contains(".setOwner("));
        assertFalse(service.contains("discard()"));
    }

    @Test
    void hiddenSinkIsReadOnlyAfterEachObservedCursorCellAdvances() throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf("private BlockPos nextPocketSolid");
        int validator = service.indexOf("private boolean validateOpenPocket", selector);
        String body = service.substring(selector, validator);

        assertTrue(body.contains("cells[Math.max(0, pocketClearIndex)]"));
        assertTrue(body.indexOf("ObservableWorldQuery.canObserveCell(bot, cell)")
                        < body.indexOf("getBlockState(cell)"),
                "the next side-pocket cell must cross observation before its state is read");
        assertTrue(service.contains("OreScan.isOreBlock(state.getBlock())"));
        assertTrue(service.contains("mining_service_disposal_ore_preserved"));
    }

    @Test
    void serviceReceivesAReadOnlyCopyOfTheParentMiningCursor() throws IOException {
        String executor = read("goal/GoalExecutor.java");
        int method = executor.indexOf("private MiningCursor miningCursorForService");
        int next = executor.indexOf("private Map<String, String> checkpointForObsidian", method);
        String body = executor.substring(method, next);

        assertTrue(executor.contains("plan.miningCursorForService(step.ores())"));
        assertTrue(body.contains("Map.copyOf(miningCheckpoint)"));
        assertFalse(body.contains("miningCheckpoint.clear()"));
        assertFalse(body.contains("takeTaskCheckpoint"));
    }

    @Test
    void committedPhysicalDebtReturnsAndSealsBeforePublishingFailure() throws IOException {
        String service = read("task/MiningServiceTask.java");

        assertTrue(service.contains("RETURN_TO_DISPOSAL_FACE"));
        assertTrue(service.contains("beginPocketTerminalRecovery(bot,"));
        assertTrue(service.contains(
                "sealPocketThenFail(\"mining_service_disposal_settle_timeout\")"));
        assertFalse(service.contains(
                "fail(\"mining_service_disposal_settle_timeout\")"));
        assertTrue(service.contains("isSolid(bot, face.east())")
                        || service.contains("isSolidSeal(bot, pocketEntry)"),
                "sealed debt must factually verify both mouth cells before terminal propagation");
    }

    @Test
    void naturalOpenPocketUsesObservedFloorBeforeHeadSeal() throws IOException {
        String service = read("task/MiningServiceTask.java");
        int validator = service.indexOf("private boolean validateOpenPocket");
        int validatorEnd = service.indexOf(
                "private static boolean hasObservedPlacementSupport", validator);
        String validationBody = service.substring(validator, validatorEnd);
        int sealer = service.indexOf("private void sealDisposalPocket");
        int sealerEnd = service.indexOf("private void returnToDisposalFace", sealer);
        String sealBody = service.substring(sealer, sealerEnd);

        assertTrue(validationBody.contains(
                        "return hasObservedPlacementSupport(bot, pocketEntry);"),
                "an open pocket must attest the persistent floor support used by its first seal");
        assertTrue(sealBody.contains("BlockPos target = !lowerSealed ? pocketEntry\n"
                        + "                    : !upperSealed ? pocketEntry.up() : null;"),
                "the atomic seal must place feet against the floor before head against feet");
        assertFalse(sealBody.contains("BlockPos target = !upperSealed ? pocketEntry.up()"),
                "head-first sealing reintroduces no_adjacent_block in natural ore cavities");
    }

    @Test
    void oreRerouteSealRecoveryHasABoundedTerminalPath() throws IOException {
        String service = read("task/MiningServiceTask.java");

        assertTrue(service.contains("failPocketSealRecovery(reason)"));
        assertTrue(service.contains("failPocketSealRecovery(\n"
                + "                            \"mining_service_disposal_seal_failed:\""));
        assertTrue(service.contains("pocketPhaseAge() > POCKET_SETTLE_LIMIT"));
        assertTrue(service.contains("pocketTerminalFailure = normalized;\n"
                + "        fail(normalized);"));
    }

    @Test
    void schemaSixBindsOreRetryMarkerToPhaseAndOppositePocketDirection() throws IOException {
        String service = read("task/MiningServiceTask.java");

        assertTrue(service.contains("validPocketFailureCheckpoint("));
        assertTrue(service.contains("return committed && rejected == direction;"));
        assertTrue(service.contains("phase != Phase.OPEN_DISPOSAL_POCKET || committed"));
        assertTrue(service.contains(
                "rejected == clockwise && direction == counterclockwise"));
        assertTrue(service.contains("if (!identity || !validFailure)"));
    }

    @Test
    void schemaSixRareServiceCannotDecodeWithoutItsEmbeddedCursor() throws IOException {
        String service = read("task/MiningServiceTask.java");

        assertTrue(service.contains("policy.profile() == ServiceProfile.RARE_ORE_BATCH\n"
                + "                        && !hasCursor"));
        assertTrue(service.contains("cursorKeys.stream().anyMatch(values::containsKey)"));
        assertTrue(service.contains("values.keySet().containsAll(cursorKeys)"));
    }

    @Test
    void runtimePocketLedgerMergeCannotWrapAnInteger() throws IOException {
        String service = read("task/MiningServiceTask.java");

        assertTrue(service.contains(
                "long merged = (long) currentCount + incrementCount;"));
        assertTrue(service.contains(
                "merged > POCKET_CHECKPOINT_MAX_ITEM_COUNT ? -1 : (int) merged"));
        assertTrue(service.contains("mining_service_disposal_ledger_limit_exceeded"));
        assertFalse(service.contains("pocketDropLedger.merge"));
    }

    @Test
    void aggregateSinkCountsCannotReplacePersistedEntityAttestation() throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf("private boolean sinkLedgerSatisfied");
        int next = service.indexOf("private void enterPocketPhase", selector);
        String body = service.substring(selector, next);

        assertTrue(body.contains("attestPocketLedgerIdentity(bot, tracked)"));
        assertTrue(body.contains(
                "if (!attestPocketLedgerIdentity(bot, tracked)) {\n            return false;"));
        assertTrue(body.contains("!fullyContains(sink, item.getBoundingBox())"));
        assertTrue(body.contains("!ObservableWorldQuery.canObserveEntity(bot, item)"));
        assertTrue(body.contains("PocketLineage lineage = pocketLineage.get(uuid)"));
        assertTrue(body.contains(
                "liveTotal > committedTotal || guaranteedLedger < entry.getValue()"));
        assertTrue(body.contains(
                "pocketBaseline.getOrDefault(entry.getKey(), 0)"));
        assertTrue(body.contains(
                "only new lineage checkpoints can prove a"));
        assertTrue(body.indexOf("attestPocketLedgerIdentity(bot, tracked)")
                        < body.indexOf("reconcilePocketBaselineFromTrackedLedger"),
                "baseline reconciliation ran before persisted UUID attestation");
    }

    @Test
    void checkpointUuidCountIsBoundedByPerItemStacksAndRecordedItems() throws IOException {
        String service = read("task/MiningServiceTask.java");

        assertTrue(service.contains(
                "return minimumEntities <= entityCount && entityCount <= total;"));
        assertTrue(service.contains("(count + MAX_SURVIVAL_STACK_COUNT - 1L)"));
        assertTrue(service.contains("validPocketLedgerIdentityAuthority("));
        assertTrue(service.contains(
                "boolean normalPrebaseline = phase == Phase.CAPTURE_DISPOSAL_BASELINE\n"
                        + "                && failure.isBlank() && ledger.isEmpty();"));
        assertTrue(service.contains(
                "boolean terminalPrebaseline = (phase == Phase.RETURN_TO_DISPOSAL_FACE\n"
                        + "                || phase == Phase.SEAL_DISPOSAL_POCKET)\n"
                        + "                && committed && !failure.isBlank()"));
    }

    @Test
    void successfulSealClearsSettledTransactionBeforePublishingSupplies() throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf("private void sealDisposalPocket");
        int next = service.indexOf("private void returnToDisposalFace", selector);
        String body = service.substring(selector, next);

        assertTrue(body.contains("resetPocketAttempt(bot);\n        phase = Phase.SUPPLIES;"));
        assertTrue(body.indexOf("freeSlots < requiredFreeSlots")
                        < body.lastIndexOf("resetPocketAttempt(bot);"),
                "transaction state was cleared before post-seal capacity verification");
    }

    @Test
    void checkpointEncoderRejectsInvalidInternalLedgerInsteadOfFilteringIt()
            throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf("static String encodeItemLedger");
        int next = service.indexOf("private static Optional<Map<Item, Integer>> decodeItemLedger",
                selector);
        String body = service.substring(selector, next);

        assertFalse(body.contains(".filter("));
        assertTrue(body.contains("throw new IllegalStateException(\"invalid_pocket_ledger:"));
        assertTrue(body.contains("total > POCKET_CHECKPOINT_MAX_ITEM_COUNT"));
    }

    @Test
    void openingSpoilDetectionKeepsTrackedInflightEntitiesOutOfCollection() throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf(
                "private Optional<ItemEntity> nearestUntrackedOpeningSpoil");
        int next = service.indexOf("private boolean prebaselineDropsContained", selector);
        String body = service.substring(selector, next);

        assertFalse(body.contains("isDisposableJunk"));
        assertTrue(body.contains("!pocketEntityIds.contains(entity.getUuid())"));
        assertTrue(body.contains(
                "sink == null || !fullyContains(sink, entity.getBoundingBox())"));
    }

    @Test
    void sinkQueryMarginCannotExpandThePhysicalContainmentBoundary() throws IOException {
        String service = read("task/MiningServiceTask.java");
        int observable = service.indexOf(
                "private java.util.List<ItemEntity> observableSinkItems");
        int opening = service.indexOf(
                "private Optional<ItemEntity> nearestUntrackedOpeningSpoil", observable);
        String observableBody = service.substring(observable, opening);

        assertTrue(observableBody.contains("Box sink = pocketSinkBox();"));
        assertTrue(observableBody.contains("Box query = sink.expand(0.01D);"));
        assertTrue(observableBody.contains("ItemEntity.class, query,"));
        assertTrue(observableBody.contains(
                "fullyContains(sink, entity.getBoundingBox())"));
        assertFalse(observableBody.contains(
                "fullyContains(query, entity.getBoundingBox())"));

        int sinkBox = service.indexOf("private Box pocketSinkBox()");
        int containment = service.indexOf("private static boolean fullyContains", sinkBox);
        String sinkBoxBody = service.substring(sinkBox, containment);
        assertFalse(sinkBoxBody.contains(".expand("),
                "the physical sink boundary must remain the exact two-cell block volume");
    }

    @Test
    void onlyVerifiedSealTreatsTrackedMouthIdentityAsEscape()
            throws IOException {
        String service = read("task/MiningServiceTask.java");
        int drop = service.indexOf("private void dropDisposable");
        int settle = service.indexOf("private void settleDisposable", drop);
        int seal = service.indexOf("private void sealDisposalPocket", settle);
        int afterSeal = service.indexOf("private void returnToDisposalFace", seal);
        assertFalse(service.substring(drop, settle).contains("failOnTrackedLedgerEscape(bot)"));
        assertFalse(service.substring(settle, seal).contains("failOnTrackedLedgerEscape(bot)"));
        String sealBody = service.substring(seal, afterSeal);
        assertTrue(sealBody.indexOf("failOnTrackedLedgerEscape(bot)")
                        < sealBody.indexOf("nearestUntrackedOpeningSpoil(bot)"),
                "SEAL inspected untracked spoil before verified tracked identities");

        int selector = service.indexOf("private boolean failOnTrackedLedgerEscape");
        int next = service.indexOf("private boolean prebaselineDropsContained", selector);
        String body = service.substring(selector, next);
        assertTrue(body.contains("phase != Phase.SEAL_DISPOSAL_POCKET"
                + " || !pocketLedgerVerified"));
        assertTrue(body.contains("observableOpeningSpoil(bot).stream()"));
        assertTrue(body.contains(
                ".noneMatch(entity -> pocketEntityIds.contains(entity.getUuid()))"));
        assertTrue(body.contains(
                "String reason = \"mining_service_disposal_tracked_entity_escaped\";"));
        assertTrue(body.contains("pocketTerminalFailure = reason;"));
    }

    @Test
    void dropAndSettleCannotPersistStaleSealVerification() throws IOException {
        String service = read("task/MiningServiceTask.java");

        assertTrue(service.contains(
                "case DROP_DISPOSABLE -> clearIndex == 4 && !ledgerVerified;"));
        assertTrue(service.contains("case SETTLE_DISPOSABLE -> clearIndex == 4\n"
                + "                    && !ledgerVerified"));
        assertTrue(service.contains("if (next == Phase.DROP_DISPOSABLE"
                + " || next == Phase.SETTLE_DISPOSABLE) {\n"
                + "            // DROP/SETTLE always describe an unverified ledger generation."));
        assertTrue(service.contains("pocketDropLedger.put(item, merged);\n"
                + "        // Any ledger extension creates a new attestation generation."));
        assertTrue(service.contains("if (phase != Phase.SEAL_DISPOSAL_POCKET) {\n"
                + "            // A failure reached from an unverified generation"));
    }

    @Test
    void prebaselineReturnKeepsCaptureAuthorityUntilContainmentCanResume()
            throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf("private boolean ensureAtWorkFace");
        int next = service.indexOf("private boolean isPrebaselineCaptureDebt", selector);
        String body = service.substring(selector, next);
        int prebaseline = body.indexOf("if (isPrebaselineCaptureDebt())");
        int genericDebt = body.indexOf("if (hasCommittedDisposalDebt())", prebaseline);
        String branch = body.substring(prebaseline, genericDebt);

        assertTrue(branch.contains("startPathTo(workFace)"));
        assertTrue(branch.contains("startDigPathTo(workFace)"));
        assertTrue(branch.contains(
                "mining_service_disposal_prebaseline_return_failed:"));
        assertFalse(branch.contains("enterPocketPhase(Phase.RETURN_TO_DISPOSAL_FACE)"));
        assertTrue(service.contains(
                "return phase == Phase.CAPTURE_DISPOSAL_BASELINE\n"
                        + "                && pocketDropCommitted"
                        + " && pocketDropLedger.isEmpty()\n"
                        + "                && !pocketEntityIds.isEmpty();"));
    }

    @Test
    void hardRecoveryRevokesRetryRoutingAndTreatsEveryPocketPhaseAsDebt()
            throws IOException {
        String service = read("task/MiningServiceTask.java");
        int recovery = service.indexOf("private void beginPocketTerminalRecovery");
        int debt = service.indexOf("private boolean hasCommittedDisposalDebt", recovery);
        String recoveryBody = service.substring(recovery, debt);
        assertTrue(recoveryBody.contains("promotePocketTerminalFailure(reason)"));

        int next = service.indexOf("private BlockMiner.Status beginDisposalMine", debt);
        String debtBody = service.substring(debt, next);
        assertTrue(debtBody.contains("phase == Phase.OPEN_DISPOSAL_POCKET"));
        assertTrue(debtBody.contains("phase == Phase.CAPTURE_DISPOSAL_BASELINE"));
        assertTrue(debtBody.contains("phase == Phase.DROP_DISPOSABLE"));
        assertTrue(debtBody.contains("phase == Phase.SETTLE_DISPOSABLE"));
        assertTrue(debtBody.contains("phase == Phase.RETURN_TO_DISPOSAL_FACE"));
        assertTrue(debtBody.contains("phase == Phase.SEAL_DISPOSAL_POCKET"));
        assertTrue(debtBody.contains("pocketClearIndex > 0 || openPocketMutation"));

        int promote = service.indexOf("private void promotePocketTerminalFailure");
        int promoteEnd = service.indexOf("private void failPocketSealRecovery", promote);
        String promoteBody = service.substring(promote, promoteEnd);
        assertTrue(promoteBody.contains("if (isPocketOreRetryMarker())"));
        assertTrue(promoteBody.contains("pocketTerminalFailure = normalized;"));
    }

    @Test
    void alternatePocketStartsOnlyAfterPublishingANonPocketCheckpoint() throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf("private void sealDisposalPocket");
        int next = service.indexOf("private void returnToDisposalFace", selector);
        String body = service.substring(selector, next);

        String atomicBoundary = "resetPocketAttempt(bot);\n"
                + "            // The old pocket debt is now physically settled.";
        assertTrue(body.contains(atomicBoundary));
        assertTrue(body.contains("phase = Phase.PREPARE;\n"
                + "            noteProgress();\n"
                + "            startDisposalPocket(bot, requiredWorkingFreeSlots(bot),"
                + " rejected, marker);"));
    }

    @Test
    void unreachableUnsealedReturnFailsBoundedlyWithoutClearingPocketPayload()
            throws IOException {
        String service = read("task/MiningServiceTask.java");
        int selector = service.indexOf("private void returnToDisposalFace");
        int next = service.indexOf("private void sealPocketThenFail", selector);
        String body = service.substring(selector, next);

        assertTrue(body.contains("startPathTo(workFace)"));
        assertTrue(body.contains("startDigPathTo(workFace)"));
        assertTrue(body.contains("mining_service_disposal_unsealed_return_failed:"));
        assertTrue(body.contains("if (pocketPhaseAge() > POCKET_SETTLE_LIMIT)"));
        assertTrue(body.contains("pocketTerminalFailure = reason;"));
        assertTrue(body.contains("pocketDropCommitted = true;"));
        assertFalse(body.contains("resetPocketAttempt(bot)"));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
