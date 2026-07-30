package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripMineStrictSurvivalBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void strictSurvivalFailsClosedWhileOperatorKeepsLegacyCompatibility() {
        assertEquals(StripMineTask.STRICT_SURVIVAL_REJECTION,
                StripMineTask.profileRejectionReason(OperatingProfile.STRICT_SURVIVAL).orElseThrow());
        assertEquals(StripMineTask.STRICT_SURVIVAL_REJECTION,
                StripMineTask.profileRejectionReason(null).orElseThrow());
        assertTrue(StripMineTask.profileRejectionReason(OperatingProfile.OPERATOR).isEmpty());
    }

    @Test
    void taskGateRunsBeforeAnyLegacyWorldInitialization() throws IOException {
        String source = read("task/StripMineTask.java");
        int onStart = source.indexOf("protected void onStart");
        int profileGate = source.indexOf("profileRejectionReason(AIBotConfig.get().profile())", onStart);
        int originRead = source.indexOf("origin = bot.getBlockPos()", onStart);

        assertTrue(onStart >= 0 && profileGate > onStart && originRead > profileGate,
                "strict profile gate must run before StripMine initializes from the world");
        assertTrue(source.substring(profileGate, originRead).contains("return;"),
                "strict rejection must return before any legacy raw reads");
    }

    @Test
    void longMiningGoalsDoNotDependOnLegacyStripMine() throws IOException {
        String executor = read("goal/GoalExecutor.java");
        String planner = read("goal/GoalPlanner.java");

        assertFalse(executor.contains("StripMineTask") || planner.contains("StripMineTask"),
                "GoalExecutor/GoalPlanner must keep 32 obsidian and 64 diamond on OreDig semantics");
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
