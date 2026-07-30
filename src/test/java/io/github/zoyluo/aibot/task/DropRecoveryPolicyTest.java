package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DropRecoveryPolicyTest {
    private static final BlockPos SPAWN = new BlockPos(-64, 66, -128);

    @Test
    void shortClearRouteCanRecover() {
        DangerWatcher.DropRecoveryDecision decision = DangerWatcher.dropRecoveryDecision(
                SPAWN, SPAWN.add(24, -8, 12), 0, false);

        assertTrue(decision.allowed());
        assertEquals("short_clear_route", decision.reason());
    }

    @Test
    void deepMineWithoutPersistedTrailFailsClosed() {
        DangerWatcher.DropRecoveryDecision decision = DangerWatcher.dropRecoveryDecision(
                SPAWN, new BlockPos(52, 6, -184), 0, false);

        assertFalse(decision.allowed());
        assertEquals("deep_route_without_trail", decision.reason());
    }

    @Test
    void observedHostilesAndKnownDangerEachBlockCorpseRun() {
        assertEquals("hostile_death_site", DangerWatcher.dropRecoveryDecision(
                SPAWN, SPAWN.add(12, 0, 0), 1, false).reason());
        assertEquals("known_danger_zone", DangerWatcher.dropRecoveryDecision(
                SPAWN, SPAWN.add(12, 0, 0), 0, true).reason());
    }

    @Test
    void longSurfaceRouteDoesNotPretendToBeReachable() {
        DangerWatcher.DropRecoveryDecision decision = DangerWatcher.dropRecoveryDecision(
                SPAWN, SPAWN.add(81, 0, 0), 0, false);

        assertFalse(decision.allowed());
        assertEquals("route_too_far", decision.reason());
    }
}
