package com.aiplayer.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopSchedulerTest {
    private final AgentLoopScheduler scheduler = new AgentLoopScheduler();

    @Test
    void requestsObservationAfterStepCompletion() {
        AgentLoopScheduler.LoopDecision decision = scheduler.decide(new AgentLoopScheduler.LoopState(
            true, false, false, false, false, false, false, 0, 20
        ));

        assertTrue(decision.observe());
        assertFalse(decision.reviewWithDeepSeek());
    }

    @Test
    void requestsDeepSeekReviewAfterStuckOrRepeatedFailures() {
        AgentLoopScheduler.LoopDecision stuck = scheduler.decide(new AgentLoopScheduler.LoopState(
            false, false, false, true, false, false, false, 0, 20
        ));
        AgentLoopScheduler.LoopDecision failures = scheduler.decide(new AgentLoopScheduler.LoopState(
            false, false, false, false, false, false, false, 2, 20
        ));

        assertTrue(stuck.reviewWithDeepSeek());
        assertTrue(failures.reviewWithDeepSeek());
    }
}
