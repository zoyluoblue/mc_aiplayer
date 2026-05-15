package com.aiplayer.execution;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

public final class MiningFailurePolicy {
    private final Map<MiningFailureType, Integer> failures = new EnumMap<>(MiningFailureType.class);
    private final Map<String, Integer> targetFailures = new TreeMap<>();

    public Decision record(MiningFailureType type, String reason) {
        return record(type, reason, "unknown");
    }

    public Decision record(MiningFailureType type, String reason, String targetKey) {
        MiningFailureType safeType = type == null ? MiningFailureType.NO_TARGET : type;
        String normalizedReason = normalize(reason);
        String normalizedTarget = normalize(targetKey);
        failures.merge(safeType, 1, Integer::sum);
        int count = targetFailures.merge(failureKey(safeType, normalizedReason, normalizedTarget), 1, Integer::sum);
        boolean terminal = safeType.recoveryLimit() <= 0 || count > safeType.recoveryLimit();
        return new Decision(safeType, safeType.defaultAction(), count, safeType.recoveryLimit(), terminal, normalizedReason, normalizedTarget);
    }

    public void reset() {
        failures.clear();
        targetFailures.clear();
    }

    public String summary() {
        Map<String, Integer> byKey = new TreeMap<>();
        for (Map.Entry<MiningFailureType, Integer> entry : failures.entrySet()) {
            byKey.put(entry.getKey().key(), entry.getValue());
        }
        return byKey + ", targets=" + targetFailures;
    }

    private static String failureKey(MiningFailureType type, String reason, String targetKey) {
        return type.key() + "|" + reason + "|" + targetKey;
    }

    private static String normalize(String reason) {
        return reason == null || reason.isBlank() ? "unknown" : reason;
    }

    public record Decision(
        MiningFailureType type,
        MiningRecoveryAction action,
        int count,
        int limit,
        boolean terminal,
        String reason,
        String targetKey
    ) {
        public String toLogText() {
            return "failureType=" + type.key()
                + ", action=" + action.key()
                + ", count=" + count
                + ", limit=" + limit
                + ", terminal=" + terminal
                + ", reason=" + reason
                + ", target=" + targetKey;
        }
    }
}
