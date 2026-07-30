package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks lighting to carried torches so mining cannot consume tool-service sticks. */
class MiningTorchReserveSourceContractTest {
    private static final Path TASKS = Path.of("src/main/java/io/github/zoyluo/aibot/task");

    @Test
    void oreDigLightingOnlyUsesCarriedTorches() throws IOException {
        String source = Files.readString(TASKS.resolve("OreDigTask.java"));
        String lighting = methodSlice(source, "private void stripMine", "static void restoreActiveChannelTool");

        assertTrue(lighting.contains("Items.TORCH"),
                "strip mining must retain carried-torch lighting");
        assertFalse(lighting.contains("Items.COAL"),
                "strip mining must not convert incidental coal into torches");
        assertFalse(lighting.contains("Items.STICK"),
                "strip mining must preserve tool-service sticks");
        assertFalse(lighting.contains("ore_dig_torch_crafted"),
                "strip mining must not publish a synthetic torch craft");
    }

    @Test
    void descentLightingOnlyUsesCarriedTorches() throws IOException {
        String source = Files.readString(TASKS.resolve("DescendToYTask.java"));
        String lighting = methodSlice(source, "private void maybePlaceTorch", "static void restoreActiveMiningTool");

        assertTrue(lighting.contains("Items.TORCH"),
                "descent must retain carried-torch lighting");
        assertFalse(lighting.contains("Items.COAL"),
                "descent must not convert incidental coal into torches");
        assertFalse(lighting.contains("Items.STICK"),
                "descent must preserve tool-service sticks");
        assertFalse(lighting.contains("giveItem("),
                "descent lighting must not synthesize inventory");
    }

    private static String methodSlice(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing lighting method boundary: " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
