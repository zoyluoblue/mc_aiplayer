package io.github.zoyluo.aibot.brain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BrainCoordinatorControlContinuationTest {
    @Test
    void controlBatchKeepsLeaseOpenForAnyReplacementWork() {
        assertFalse(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.NONE, true, true, 1, true));
        assertFalse(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, false, false, 0, false));

        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, true, false, 0, false));
        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, false, true, 0, false));
        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_ALL, false, false, 1, false));
        assertTrue(BrainCoordinator.shouldContinueAfterControl(
                ActionDispatcher.ControlEffect.CANCEL_CURRENT, false, false, 0, true));

        assertTrue(BrainCoordinator.hasRuntimeWork(false, false, 1, false));
        assertTrue(BrainCoordinator.hasRuntimeWork(false, false, 0, true));
        assertFalse(BrainCoordinator.hasRuntimeWork(false, false, 0, false));
    }
}
