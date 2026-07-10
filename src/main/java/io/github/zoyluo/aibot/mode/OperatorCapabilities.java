package io.github.zoyluo.aibot.mode;

/** Fine-grained operator-mode switches. Boxed fields preserve explicit false through Gson defaults. */
public record OperatorCapabilities(
        Boolean hiddenBlockScan,
        Boolean emergencyTeleport,
        Boolean forcedPickup,
        Boolean manualTeleport
) {
    public static OperatorCapabilities defaults() {
        // Operator is the compatibility profile, so its defaults retain the existing enhanced behavior.
        return new OperatorCapabilities(true, true, true, true);
    }

    public static OperatorCapabilities none() {
        return new OperatorCapabilities(false, false, false, false);
    }

    public OperatorCapabilities withDefaults(OperatorCapabilities defaults) {
        OperatorCapabilities fallback = defaults == null ? defaults() : defaults;
        return new OperatorCapabilities(
                booleanOrDefault(hiddenBlockScan, fallback.hiddenBlockScan),
                booleanOrDefault(emergencyTeleport, fallback.emergencyTeleport),
                booleanOrDefault(forcedPickup, fallback.forcedPickup),
                booleanOrDefault(manualTeleport, fallback.manualTeleport));
    }

    public boolean enabled(PrivilegedCapability capability) {
        return switch (capability) {
            case HIDDEN_BLOCK_SCAN -> Boolean.TRUE.equals(hiddenBlockScan);
            case EMERGENCY_TELEPORT -> Boolean.TRUE.equals(emergencyTeleport);
            case FORCED_PICKUP -> Boolean.TRUE.equals(forcedPickup);
            case MANUAL_TELEPORT -> Boolean.TRUE.equals(manualTeleport);
        };
    }

    private static Boolean booleanOrDefault(Boolean value, Boolean fallback) {
        return value == null ? fallback : value;
    }
}
