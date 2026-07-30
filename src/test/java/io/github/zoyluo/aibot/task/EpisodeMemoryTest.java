package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpisodeMemoryTest {
    @Test
    void purposeScopedTrailsDoNotCrossContaminateAndUseHorizontalDistance() {
        UUID bot = UUID.randomUUID();
        EpisodeMemory memory = EpisodeMemory.INSTANCE;
        memory.reset(bot);

        memory.recordTrail(bot, "gather", new BlockPos(10, 64, 20));
        assertTrue(memory.nearTrail(bot, "gather", new BlockPos(12, -40, 22), 4.0D));
        assertFalse(memory.nearTrail(bot, "hunt", new BlockPos(10, 64, 20), 4.0D));

        memory.recordTrail(bot, "hunt", new BlockPos(-8, 70, 3));
        assertTrue(memory.nearTrail(bot, "hunt", new BlockPos(-8, 5, 3), 1.0D));
        memory.reset(bot);
        assertFalse(memory.nearTrail(bot, "gather", new BlockPos(10, 64, 20), 4.0D));
        assertFalse(memory.nearTrail(bot, "hunt", new BlockPos(-8, 70, 3), 4.0D));
    }
}
