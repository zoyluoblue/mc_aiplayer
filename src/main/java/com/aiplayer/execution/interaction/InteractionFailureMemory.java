package com.aiplayer.execution.interaction;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

public final class InteractionFailureMemory {
    private final Map<InteractionFailureKey, Integer> failures = new HashMap<>();

    public void remember(InteractionTarget target, String reason, int currentTick, int ttlTicks) {
        if (target == null || ttlTicks <= 0) {
            return;
        }
        failures.put(InteractionFailureKey.from(target, reason), currentTick + ttlTicks);
    }

    public int expiresAt(InteractionTarget target, int currentTick) {
        prune(currentTick);
        for (Map.Entry<InteractionFailureKey, Integer> entry : failures.entrySet()) {
            if (entry.getKey().matches(target)) {
                return entry.getValue();
            }
        }
        return -1;
    }

    public boolean isRejected(InteractionTarget target, int currentTick) {
        return rejectionFor(target, currentTick).isPresent();
    }

    public Optional<InteractionFailureKey> rejectionFor(InteractionTarget target, int currentTick) {
        prune(currentTick);
        for (InteractionFailureKey key : failures.keySet()) {
            if (key.matches(target)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    public void clear() {
        failures.clear();
    }

    private void prune(int currentTick) {
        Iterator<Map.Entry<InteractionFailureKey, Integer>> iterator = failures.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= currentTick) {
                iterator.remove();
            }
        }
    }
}
