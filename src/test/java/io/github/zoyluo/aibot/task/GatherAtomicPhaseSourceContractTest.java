package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class GatherAtomicPhaseSourceContractTest {
    @Test
    void regionalWatchdogCannotInterruptHarvestOrPickup() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/task/GatherQuotaTask.java"));
        assertTrue(source.contains("phase == Phase.SURVEY || phase == Phase.GOTO"),
                "regional self-stuck watchdog must be limited to discovery/navigation phases");
        assertTrue(source.contains("elapsed - harvestStartedTick > HARVEST_LIMIT"),
                "HARVEST needs its own explicit bounded watchdog");
        int confirmation = source.indexOf("private boolean confirmPickup");
        int reset = source.indexOf("pickupMisses = 0", confirmation);
        int transition = source.indexOf("phase = Phase.DONE", confirmation);
        assertTrue(confirmation > 0 && reset > confirmation && reset < transition,
                "physical pickup confirmation must reset the consecutive-miss ledger");
        assertTrue(source.contains("private BlockPos pickupOrigin"),
                "gather must retain the factual break coordinate until pickup resolves");
        assertTrue(source.contains("HarvestCore.approachKnownPickupCell(bot, pickupOrigin)"),
                "an occluded drop must fall back to the remembered break coordinate");
        assertTrue(source.contains("Stats.PICKED_UP"),
                "vanilla pickup stats must distinguish collection from concurrent inventory consumption");
        int miss = source.indexOf("gather_pickup_miss");
        int watchdogReset = source.indexOf("resetSurveyWatchdog()", miss);
        int survey = source.indexOf("phase = Phase.SURVEY", miss);
        assertTrue(watchdogReset > miss && watchdogReset < survey,
                "a real pickup miss must receive a fresh local-survey watchdog window");
    }
}
