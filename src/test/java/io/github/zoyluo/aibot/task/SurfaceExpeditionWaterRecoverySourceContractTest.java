package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks gathering and hunting to one shared, physical water-recovery handoff. */
class SurfaceExpeditionWaterRecoverySourceContractTest {
    private static final Path TASKS = Path.of("src/main/java/io/github/zoyluo/aibot/task");

    @Test
    void surfaceTasksPauseExplorationUntilSharedRescueReturnsToDryGround() throws IOException {
        String safety = read("NavSafetyNet.java");
        String gather = read("GatherQuotaTask.java");
        String hunt = read("HuntTask.java");

        assertTrue(safety.contains("public void requestWaterRescue(AIPlayerEntity bot)"));
        assertTrue(safety.contains("public boolean isWaterRescueActive(AIPlayerEntity bot)"));
        assertTrue(safety.contains("FakePlayerMotion.swimStepTo("),
                "wet rescue cells need an adjacent physical swim step; inputs alone do not travel");
        assertTrue(safety.contains("FakePlayerMotion.stepToStandable("),
                "the final dry rescue cell must be a supported physical landing");
        assertTrue(gather.contains("if (waitForDryGround(bot))"));
        assertTrue(hunt.contains("if (waitForDryGround(bot))"));
        assertTrue(gather.contains("NavSafetyNet.INSTANCE.requestWaterRescue(bot)"));
        assertTrue(hunt.contains("NavSafetyNet.INSTANCE.requestWaterRescue(bot)"));
    }

    private static String read(String file) throws IOException {
        return Files.readString(TASKS.resolve(file));
    }
}
