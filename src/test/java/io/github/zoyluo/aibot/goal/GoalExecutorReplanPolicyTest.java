package io.github.zoyluo.aibot.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoalExecutorReplanPolicyTest {
    @Test
    void longRareOreLimitUsesOriginalEightItemBatchCount() {
        assertEquals(24, GoalExecutor.longRareOreLifetimeReplanLimit(64));
        assertEquals(15, GoalExecutor.longRareOreLifetimeReplanLimit(33));
    }

    @Test
    void shortRareMissionsKeepBaseLimit() {
        assertEquals(12, GoalExecutor.longRareOreLifetimeReplanLimit(32));
        assertEquals(12, GoalExecutor.longRareOreLifetimeReplanLimit(7));
        assertEquals(12, GoalExecutor.longRareOreLifetimeReplanLimit(0));
    }

    @Test
    void lifetimeBoundaryAndConsecutiveNoProgressGateRemainBounded() {
        assertTrue(GoalExecutor.withinReplanBudget(24, 2, 23));
        assertFalse(GoalExecutor.withinReplanBudget(24, 3, 0));
        assertFalse(GoalExecutor.withinReplanBudget(24, 0, 24));
        assertTrue(GoalExecutor.withinReplanBudget(12, 2, 11));
        assertFalse(GoalExecutor.withinReplanBudget(12, 2, 12));
    }
}
