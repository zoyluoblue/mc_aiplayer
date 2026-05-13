package com.aiplayer.execution;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

public final class MiningFailurePolicy {
    private final Map<MiningFailureType, Integer> failures = new EnumMap<>(MiningFailureType.class);

    public Decision record(MiningFailureType type, String reason) {
        MiningFailureType safeType = type == null ? MiningFailureType.NO_TARGET : type;
        int count = failures.merge(safeType, 1, Integer::sum);
        boolean terminal = safeType.recoveryLimit() <= 0 || count > safeType.recoveryLimit();
        return new Decision(safeType, safeType.defaultAction(), count, safeType.recoveryLimit(), terminal, normalize(reason));
    }

    public void reset() {
        failures.clear();
    }

    public String summary() {
        Map<String, Integer> byKey = new TreeMap<>();
        for (Map.Entry<MiningFailureType, Integer> entry : failures.entrySet()) {
            byKey.put(entry.getKey().key(), entry.getValue());
        }
        return byKey.toString();
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
        String reason
    ) {
        public String toLogText() {
            return "failureType=" + type.key()
                + ", action=" + action.key()
                + ", count=" + count
                + ", limit=" + limit
                + ", terminal=" + terminal
                + ", reason=" + reason;
        }
    }
}
