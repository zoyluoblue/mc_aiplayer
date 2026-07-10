package io.github.zoyluo.aibot.mode;

/** Structured audit seam emitted before any privileged operation can run. */
public record CapabilityDecision(
        OperatingProfile profile,
        PrivilegedCapability capability,
        boolean allowed,
        Reason reason
) {
    public enum Reason {
        ALLOWED_OPERATOR_FLAG,
        DENIED_STRICT_SURVIVAL,
        DENIED_OPERATOR_FLAG,
        DENIED_MISSING_CONFIGURATION
    }
}
