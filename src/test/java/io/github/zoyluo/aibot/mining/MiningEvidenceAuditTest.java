package io.github.zoyluo.aibot.mining;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MiningEvidenceAuditTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");
    private static final Path VERIFY = Path.of(
            "src/gametest/java/io/github/zoyluo/aibot/command/AIBotVerifySubcommand.java");

    @Test
    void diamondRequiresEveryPhysicalAndSurvivalBoundaryAt64() {
        assertTrue(diamond(64, 64, 64, 0, 0).passes());
        assertFalse(diamond(63, 64, 64, 0, 0).passes());
        assertFalse(diamond(64, 63, 64, 0, 0).passes());
        assertFalse(diamond(64, 64, 63, 0, 0).passes());
        assertFalse(diamond(64, 64, 64, 1, 0).passes());
        assertFalse(diamond(64, 64, 64, 0, 1).passes());
        assertFalse(diamond(64, 64, 64, 0, 0, 1).passes());
    }

    @Test
    void obsidianRequiresWaterConversionBreakAndPickupAt32() {
        assertTrue(obsidian(1, 32, 32, 32, 32, 0, 0).passes());
        assertFalse(obsidian(0, 32, 32, 32, 32, 0, 0).passes());
        assertFalse(obsidian(1, 31, 32, 32, 32, 0, 0).passes());
        assertFalse(obsidian(1, 32, 31, 32, 32, 0, 0).passes());
        assertFalse(obsidian(1, 32, 32, 31, 32, 0, 0).passes());
        assertFalse(obsidian(1, 32, 32, 32, 31, 0, 0).passes());
        assertFalse(obsidian(1, 32, 32, 32, 32, 1, 0).passes());
        assertFalse(obsidian(1, 32, 32, 32, 32, 0, 1).passes());
    }

    @Test
    void globalVanillaMinedCountCannotCreateAnExactDiamondBreak() {
        MiningEvidenceAudit.DiamondTransactions transactions =
                new MiningEvidenceAudit.DiamondTransactions();

        int auditedBreaks = transactions.auditedBreaksBoundedBy(64);

        assertEquals(0, auditedBreaks);
        assertFalse(diamond(auditedBreaks, 64, 64, 0, 0).passes());
    }

    @Test
    void oneExactDiamondBreakCreditsAtMostOnePickupAndCannotBeReplayed() {
        MiningEvidenceAudit.DiamondTransactions transactions =
                new MiningEvidenceAudit.DiamondTransactions();
        BlockPos ore = new BlockPos(4, -58, 9);
        transactions.observeBeforeBreak(ore);

        assertTrue(transactions.recordExactBreak(ore));
        assertEquals(1, transactions.auditedBreaksBoundedBy(64));
        assertTrue(transactions.recordNativePickup(ore, true, 64));
        assertEquals(1, transactions.nativePickupCredits());
        assertFalse(transactions.recordNativePickup(ore, true, 64));
        assertEquals(1, transactions.nativePickupCredits());
    }

    @Test
    void obsidianRequiresACompleteExactCellChainAndRejectsReplay() {
        MiningEvidenceAudit.ObsidianTransactions transactions =
                new MiningEvidenceAudit.ObsidianTransactions();
        BlockPos backed = new BlockPos(8, -58, 3);
        BlockPos unrelated = backed.east();
        transactions.armWaterPlacement(java.util.Set.of(backed));

        assertFalse(transactions.recordConversion(unrelated));
        assertFalse(transactions.isBreakAuthorized(unrelated));
        assertTrue(transactions.recordConversion(backed));
        assertFalse(transactions.recordConversion(backed));
        assertTrue(transactions.isBreakAuthorized(backed));
        assertTrue(transactions.recordExactBreak(backed));
        assertFalse(transactions.recordExactBreak(backed));
        assertFalse(transactions.isBreakAuthorized(backed));
        assertTrue(transactions.recordExactPickup(backed, 32));
        assertFalse(transactions.recordExactPickup(backed, 32));

        assertEquals(1, transactions.conversionCredits());
        assertEquals(1, transactions.breakCredits());
        assertEquals(1, transactions.pickupCredits());
    }

    @Test
    void oneExactObsidianBreakCannotAmplifyAnUnrelatedInventoryDelta() {
        MiningEvidenceAudit.ObsidianTransactions transactions =
                new MiningEvidenceAudit.ObsidianTransactions();
        BlockPos backed = new BlockPos(-4, -57, 11);
        transactions.armWaterPlacement(java.util.Set.of(backed));

        assertTrue(transactions.recordConversion(backed));
        assertTrue(transactions.recordExactBreak(backed));
        assertTrue(transactions.recordExactPickup(backed, 64));
        assertEquals(1, transactions.pickupCredits());
    }

    @Test
    void oneWaterPlacementCanCreditOnlyItsPreObservedBatch() {
        MiningEvidenceAudit.ObsidianTransactions transactions =
                new MiningEvidenceAudit.ObsidianTransactions();
        BlockPos first = new BlockPos(2, -58, 2);
        BlockPos second = first.east();
        BlockPos unobserved = second.east();
        transactions.armWaterPlacement(java.util.Set.of(first, second));

        assertTrue(transactions.recordConversion(first));
        assertTrue(transactions.recordConversion(second));
        assertFalse(transactions.recordConversion(unobserved));
        assertEquals(2, transactions.conversionCredits());
        assertTrue(transactions.isBreakAuthorized(first));
        assertTrue(transactions.isBreakAuthorized(second));
        assertFalse(transactions.isBreakAuthorized(unobserved));
    }

    @Test
    void closedWaterPlacementCannotCreditALateUnrelatedConversion() {
        MiningEvidenceAudit.ObsidianTransactions transactions =
                new MiningEvidenceAudit.ObsidianTransactions();
        BlockPos stale = new BlockPos(5, -58, 5);
        transactions.armWaterPlacement(java.util.Set.of(stale));
        transactions.closeWaterPlacements();

        assertFalse(transactions.recordConversion(stale));
        assertEquals(0, transactions.conversionCredits());
        assertFalse(transactions.isBreakAuthorized(stale));
    }

    @Test
    void nextWaterPlacementCannotInheritPriorGenerationCandidates() {
        MiningEvidenceAudit.ObsidianTransactions transactions =
                new MiningEvidenceAudit.ObsidianTransactions();
        BlockPos stale = new BlockPos(8, -58, 8);
        BlockPos current = stale.east();
        int firstGeneration = transactions.armWaterPlacement(java.util.Set.of(stale));
        int secondGeneration = transactions.armWaterPlacement(java.util.Set.of(current));

        assertTrue(firstGeneration > 0);
        assertTrue(secondGeneration > firstGeneration);
        assertFalse(transactions.recordConversion(stale));
        assertTrue(transactions.recordConversion(current));
        transactions.closeWaterPlacements();
        assertTrue(transactions.isBreakAuthorized(current),
                "closing a placement must preserve already committed conversion authority");
    }

    @Test
    void runtimeHooksBindFactsAtTheActualDecisionAndTransactionBoundaries() throws IOException {
        String capability = Files.readString(MAIN.resolve("mode/CapabilityRuntime.java"));
        String audit = Files.readString(MAIN.resolve("mining/MiningEvidenceAudit.java"));
        String oreDig = Files.readString(MAIN.resolve("task/OreDigTask.java"));
        String obsidian = Files.readString(MAIN.resolve("task/CreateObsidianTask.java"));
        String lifecycle = Files.readString(MAIN.resolve("runtime/RuntimeLifecycleCoordinator.java"));

        assertTrue(capability.contains(
                "MiningEvidenceAudit.recordCapabilityDecision(bot, decision.allowed())"));
        assertTrue(audit.contains("Stats.CUSTOM.getOrCreateStat(Stats.DEATHS)"));
        assertTrue(oreDig.contains("MiningEvidenceAudit.observeDiamondOreBeforeBreak("));
        assertTrue(oreDig.contains("MiningEvidenceAudit.recordDiamondOreBreak(bot, pos)"));
        assertTrue(oreDig.contains("MiningEvidenceAudit.recordDiamondNativePickup("));
        assertTrue(obsidian.contains("MiningEvidenceAudit.recordWaterPlacement(bot, observableLava)"));
        assertTrue(obsidian.contains("MiningEvidenceAudit.reconcileAndCloseWaterPlacement(bot)"));
        assertTrue(obsidian.contains("MiningEvidenceAudit.recordLavaToObsidian(bot, obsidian)"));
        int scanStart = obsidian.indexOf("private void scan(AIPlayerEntity bot)");
        int scanEnd = obsidian.indexOf("private void returnToScanFace", scanStart);
        assertTrue(scanStart >= 0 && scanEnd > scanStart);
        assertFalse(obsidian.substring(scanStart, scanEnd)
                        .contains("MiningEvidenceAudit.recordLavaToObsidian"),
                "SCAN must not promote an open or stale placement candidate");
        assertTrue(obsidian.contains("audit_session"));
        assertTrue(obsidian.contains("create_obsidian_audit_session_missing_or_mismatched"));
        assertTrue(obsidian.contains("MiningEvidenceAudit.isObsidianBreakAuthorized(bot, target)"));
        assertTrue(obsidian.contains("MiningEvidenceAudit.auditedObsidianPickupCredits(bot)"));
        assertTrue(obsidian.contains("MiningEvidenceAudit.recordObsidianBreak(bot, pickupPos)"));
        assertTrue(obsidian.contains("MiningEvidenceAudit.recordObsidianPickup("));
        assertTrue(lifecycle.contains("MiningEvidenceAudit.clear(bot)"));
        assertTrue(lifecycle.contains("MiningEvidenceAudit.clearAll()"));
    }

    @Test
    void fromZeroAuditStartsAfterScenarioPreparation() throws IOException {
        String verify = Files.readString(VERIFY);
        assertPreparationPrecedesAuditBegin(
                verify,
                "private static Result assignDiamondStack64FromZero",
                "MiningEvidenceAudit.begin(bot, MiningEvidenceAudit.Target.DIAMOND);");
        assertPreparationPrecedesAuditBegin(
                verify,
                "private static Result assignObsidianHalfStack32FromZero",
                "MiningEvidenceAudit.begin(bot, MiningEvidenceAudit.Target.OBSIDIAN);");
    }

    private static void assertPreparationPrecedesAuditBegin(String source,
                                                             String methodMarker,
                                                             String beginMarker) {
        int method = source.indexOf(methodMarker);
        int prepare = source.indexOf("prepareMiningFromZero(bot);", method);
        int begin = source.indexOf(beginMarker, method);
        assertTrue(method >= 0 && prepare > method && begin > prepare,
                "audit baseline must start after from-zero scenario preparation");
    }

    private static MiningEvidenceAudit.Snapshot diamond(int breaks,
                                                         int drops,
                                                         int pickups,
                                                         int modeViolations,
                                                         int privilegedAllowed) {
        return diamond(breaks, drops, pickups, modeViolations, privilegedAllowed, 0);
    }

    private static MiningEvidenceAudit.Snapshot diamond(int breaks,
                                                         int drops,
                                                         int pickups,
                                                         int modeViolations,
                                                         int privilegedAllowed,
                                                         int deathDelta) {
        return new MiningEvidenceAudit.Snapshot(
                MiningEvidenceAudit.Target.DIAMOND,
                MiningEvidenceAudit.DIAMOND_TARGET,
                1,
                modeViolations,
                privilegedAllowed,
                deathDelta,
                breaks,
                drops,
                pickups,
                0,
                0,
                0,
                0,
                0);
    }

    private static MiningEvidenceAudit.Snapshot obsidian(int water,
                                                          int conversions,
                                                          int breaks,
                                                          int pickups,
                                                          int vanillaBreaks,
                                                          int modeViolations,
                                                          int privilegedAllowed) {
        return new MiningEvidenceAudit.Snapshot(
                MiningEvidenceAudit.Target.OBSIDIAN,
                MiningEvidenceAudit.OBSIDIAN_TARGET,
                1,
                modeViolations,
                privilegedAllowed,
                0,
                0,
                0,
                0,
                water,
                conversions,
                breaks,
                pickups,
                vanillaBreaks);
    }
}
