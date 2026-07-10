package io.github.zoyluo.aibot.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BotMemoryGoalCancellationTest {
    @Test
    void clearingLongTermGoalPreventsAutomaticWakeAndIsIdempotent() {
        BotMemory memory = new BotMemory();
        memory.setGoal("build", List.of("gather", "build"));
        assertTrue(memory.hasActiveGoal());

        assertTrue(memory.clearGoal());
        assertFalse(memory.hasActiveGoal());
        assertFalse(memory.clearGoal());
    }
}
