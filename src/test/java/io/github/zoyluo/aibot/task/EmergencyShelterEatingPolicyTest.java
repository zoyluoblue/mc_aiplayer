package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmergencyShelterEatingPolicyTest {
    @Test
    void nonEmergencyRecoveryWaitsUntilFoodDropsBelowEighteen() {
        assertFalse(EmergencyShelterTask.shouldStartHoldEating(17.0F, 19));
        assertFalse(EmergencyShelterTask.shouldStartHoldEating(17.0F, 18));
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(17.0F, 17));
    }

    @Test
    void criticalHealthCanStillUseFoodNineteenForImmediateSurvival() {
        assertFalse(EmergencyShelterTask.shouldStartHoldEating(8.1F, 19));
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(8.0F, 19));
        assertTrue(EmergencyShelterTask.shouldStartHoldEating(8.0F, 18));
        assertFalse(EmergencyShelterTask.shouldStartHoldEating(8.0F, 20));
    }

    @Test
    void safeHealthNeverStartsShelterRecoveryEating() {
        assertFalse(EmergencyShelterTask.shouldStartHoldEating(18.0F, 0));
    }
}
