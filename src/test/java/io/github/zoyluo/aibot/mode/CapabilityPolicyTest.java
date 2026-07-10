package io.github.zoyluo.aibot.mode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityPolicyTest {
    @Test
    void strictSurvivalRejectsEveryPrivilegeEvenWhenOperatorFlagsAreEnabled() {
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            CapabilityDecision decision = CapabilityPolicy.decide(
                    OperatingProfile.STRICT_SURVIVAL,
                    OperatorCapabilities.defaults(),
                    capability);

            assertFalse(decision.allowed(), capability.name());
            org.junit.jupiter.api.Assertions.assertEquals(
                    CapabilityDecision.Reason.DENIED_STRICT_SURVIVAL, decision.reason());
        }
    }

    @Test
    void operatorHonorsEachCapabilityFlagIndependently() {
        OperatorCapabilities flags = new OperatorCapabilities(true, false, true, false);

        assertTrue(decide(flags, PrivilegedCapability.HIDDEN_BLOCK_SCAN).allowed());
        assertFalse(decide(flags, PrivilegedCapability.EMERGENCY_TELEPORT).allowed());
        assertTrue(decide(flags, PrivilegedCapability.FORCED_PICKUP).allowed());
        assertFalse(decide(flags, PrivilegedCapability.MANUAL_TELEPORT).allowed());
    }

    @Test
    void missingProfileOrOperatorConfigurationFailsClosed() {
        assertFalse(CapabilityPolicy.decide(null, OperatorCapabilities.defaults(),
                PrivilegedCapability.HIDDEN_BLOCK_SCAN).allowed());
        CapabilityDecision missing = CapabilityPolicy.decide(OperatingProfile.OPERATOR, null,
                PrivilegedCapability.HIDDEN_BLOCK_SCAN);
        assertFalse(missing.allowed());
        org.junit.jupiter.api.Assertions.assertEquals(
                CapabilityDecision.Reason.DENIED_MISSING_CONFIGURATION, missing.reason());
    }

    private static CapabilityDecision decide(OperatorCapabilities flags, PrivilegedCapability capability) {
        return CapabilityPolicy.decide(OperatingProfile.OPERATOR, flags, capability);
    }
}
