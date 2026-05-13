package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningCandidateCooldownTest {
    @Test
    void rejectedCandidateCoolsDownThenExpires() {
        MiningCandidateCooldown cooldown = new MiningCandidateCooldown();
        BlockPos pos = new BlockPos(3, -20, 7);

        cooldown.reject(pos, "movement_stuck", 100);

        assertTrue(cooldown.isCooling(pos, 200));
        assertFalse(cooldown.isCooling(pos, 100 + 20 * 60 * 4 + 1));
    }

    @Test
    void activePositionsOnlyContainsUnexpiredCandidates() {
        MiningCandidateCooldown cooldown = new MiningCandidateCooldown();
        BlockPos old = new BlockPos(1, 20, 1);
        BlockPos active = new BlockPos(2, 20, 2);

        cooldown.reject(old, "no_exposure", 0);
        cooldown.reject(active, "movement_stuck", 2000);

        assertFalse(cooldown.activePositions(20 * 60 * 2 + 10).contains(old));
        assertTrue(cooldown.activePositions(20 * 60 * 2 + 10).contains(active));
    }

    @Test
    void repeatedSameReasonIsRateLimitedForLogs() {
        MiningCandidateCooldown cooldown = new MiningCandidateCooldown();
        BlockPos pos = new BlockPos(4, 30, 4);

        cooldown.reject(pos, "passage_blocked", 100);
        assertTrue(cooldown.shouldLog(pos, "passage_blocked", 100));
        cooldown.reject(pos, "passage_blocked", 120);
        assertFalse(cooldown.shouldLog(pos, "passage_blocked", 120));
        cooldown.reject(pos, "passage_blocked", 100 + 20 * 20);
        assertTrue(cooldown.shouldLog(pos, "passage_blocked", 100 + 20 * 20));
    }
}
