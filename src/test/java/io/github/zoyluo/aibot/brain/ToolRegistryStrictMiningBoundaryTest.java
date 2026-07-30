package io.github.zoyluo.aibot.brain;

import io.github.zoyluo.aibot.mode.OperatingProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryStrictMiningBoundaryTest {
    @Test
    void strictProfileDoesNotPublishLegacyMiningTools() {
        assertFalse(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "strip_mine"));
        assertFalse(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "mine_vein"));
        assertFalse(ToolRegistry.publishTool(null, "strip_mine"));
        assertTrue(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "mine_ore"));
        assertTrue(ToolRegistry.publishTool(OperatingProfile.STRICT_SURVIVAL, "assign_task"));

        assertTrue(ToolRegistry.publishTool(OperatingProfile.OPERATOR, "strip_mine"));
        assertTrue(ToolRegistry.publishTool(OperatingProfile.OPERATOR, "mine_vein"));
    }

    @Test
    void everyPublicRouteRejectsBeforeReplacingTheCurrentTask() throws IOException {
        String registry = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/brain/ToolRegistry.java"));
        String command = Files.readString(Path.of(
                "src/main/java/io/github/zoyluo/aibot/command/AIBotTaskSubcommand.java"));

        assertTrue(occurrences(registry, "legacyMiningTaskRejection(\"") >= 2,
                "direct strip_mine and mine_vein handlers must both reject strict mode");
        int assignTaskGate = registry.indexOf("legacyMiningTaskRejection(taskType)");
        int createTask = registry.indexOf("Task task = createTask(bot, taskType, params)");
        assertTrue(assignTaskGate >= 0 && createTask > assignTaskGate,
                "assign_task must reject legacy mining before task assignment");
        assertTrue(occurrences(command, "requireLegacyMiningProfile();") == 2,
                "both player command routes must reject before constructing legacy mining work");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
