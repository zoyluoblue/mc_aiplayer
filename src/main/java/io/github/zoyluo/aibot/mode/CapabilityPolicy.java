package io.github.zoyluo.aibot.mode;

import java.util.Objects;

/** Pure allow/deny matrix for privileged capabilities. */
public final class CapabilityPolicy {
    private CapabilityPolicy() {
    }

    public static CapabilityDecision decide(OperatingProfile profile,
                                            OperatorCapabilities operatorCapabilities,
                                            PrivilegedCapability capability) {
        Objects.requireNonNull(capability, "capability");
        OperatingProfile effectiveProfile = profile == null
                ? OperatingProfile.STRICT_SURVIVAL : profile;
        if (effectiveProfile == OperatingProfile.STRICT_SURVIVAL) {
            return new CapabilityDecision(effectiveProfile, capability, false,
                    CapabilityDecision.Reason.DENIED_STRICT_SURVIVAL);
        }
        if (operatorCapabilities == null) {
            return new CapabilityDecision(effectiveProfile, capability, false,
                    CapabilityDecision.Reason.DENIED_MISSING_CONFIGURATION);
        }
        boolean allowed = operatorCapabilities.enabled(capability);
        return new CapabilityDecision(effectiveProfile, capability, allowed,
                allowed
                        ? CapabilityDecision.Reason.ALLOWED_OPERATOR_FLAG
                        : CapabilityDecision.Reason.DENIED_OPERATOR_FLAG);
    }
}
