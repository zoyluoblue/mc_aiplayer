package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningFailurePolicyTest {
    @Test
    void noTargetUsesResetDescentStartUntilLimitIsExceeded() {
        MiningFailurePolicy policy = new MiningFailurePolicy();

        MiningFailurePolicy.Decision first = policy.record(MiningFailureType.NO_TARGET, "no_downward_target");
        policy.record(MiningFailureType.NO_TARGET, "no_downward_target");
        MiningFailurePolicy.Decision third = policy.record(MiningFailureType.NO_TARGET, "no_downward_target");
        MiningFailurePolicy.Decision fourth = policy.record(MiningFailureType.NO_TARGET, "no_downward_target");

        assertEquals(MiningRecoveryAction.RESET_DESCENT_START, first.action());
        assertFalse(first.terminal());
        assertFalse(third.terminal());
        assertTrue(fourth.terminal());
    }

    @Test
    void dangerIsTerminalImmediately() {
        MiningFailurePolicy policy = new MiningFailurePolicy();

        MiningFailurePolicy.Decision decision = policy.record(MiningFailureType.DANGER, "lava");

        assertEquals(MiningRecoveryAction.ASK_PLAYER, decision.action());
        assertTrue(decision.terminal());
    }

    @Test
    void resetClearsFailureCounts() {
        MiningFailurePolicy policy = new MiningFailurePolicy();

        policy.record(MiningFailureType.MOVE_STUCK, "movement_stuck");
        policy.reset();
        MiningFailurePolicy.Decision decision = policy.record(MiningFailureType.MOVE_STUCK, "movement_stuck");

        assertEquals(1, decision.count());
        assertTrue(policy.summary().contains("move_stuck=1"));
    }

    @Test
    void countsRecoveryByTypeReasonAndTarget() {
        MiningFailurePolicy policy = new MiningFailurePolicy();

        MiningFailurePolicy.Decision firstTarget = policy.record(MiningFailureType.MOVE_STUCK, "movement_stuck", "10,64,10");
        MiningFailurePolicy.Decision secondTarget = policy.record(MiningFailureType.MOVE_STUCK, "movement_stuck", "12,64,12");
        MiningFailurePolicy.Decision firstTargetAgain = policy.record(MiningFailureType.MOVE_STUCK, "movement_stuck", "10,64,10");

        assertEquals(1, firstTarget.count());
        assertEquals(1, secondTarget.count());
        assertEquals(2, firstTargetAgain.count());
        assertTrue(policy.summary().contains("move_stuck|movement_stuck|10,64,10=2"));
    }
}
