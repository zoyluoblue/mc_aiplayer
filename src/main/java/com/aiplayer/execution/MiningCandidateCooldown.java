package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MiningCandidateCooldown {
    private static final int DEFAULT_COOLDOWN_TICKS = 20 * 60;
    private static final int REPEAT_LOG_INTERVAL_TICKS = 20 * 20;

    private final Map<BlockPos, Entry> entries = new HashMap<>();

    public Entry reject(BlockPos pos, String reason, int tick) {
        if (pos == null) {
            return null;
        }
        BlockPos key = pos.immutable();
        Entry previous = entries.get(key);
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        int failures = previous == null ? 1 : previous.failures() + 1;
        int expiresAt = Math.max(0, tick) + cooldownTicksFor(safeReason, failures);
        Entry next = new Entry(key, safeReason, Math.max(0, tick), expiresAt, failures,
            previous == null ? -REPEAT_LOG_INTERVAL_TICKS : previous.lastLogTick());
        entries.put(key, next);
        return next;
    }

    public boolean isCooling(BlockPos pos, int tick) {
        if (pos == null) {
            return false;
        }
        Entry entry = entries.get(pos);
        if (entry == null) {
            return false;
        }
        if (tick >= entry.expiresAtTick()) {
            entries.remove(pos);
            return false;
        }
        return true;
    }

    public Set<BlockPos> activePositions(int tick) {
        prune(tick);
        return entries.entrySet().stream()
            .filter(entry -> tick < entry.getValue().expiresAtTick())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    public int activeCount(int tick) {
        return activePositions(tick).size();
    }

    public boolean shouldLog(BlockPos pos, String reason, int tick) {
        if (pos == null) {
            return false;
        }
        Entry entry = entries.get(pos);
        if (entry == null) {
            return true;
        }
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        if (!entry.reason().equals(safeReason) || tick - entry.lastLogTick() >= REPEAT_LOG_INTERVAL_TICKS) {
            entries.put(pos.immutable(), entry.withLastLogTick(tick));
            return true;
        }
        return false;
    }

    public String summary(int tick) {
        prune(tick);
        if (entries.isEmpty()) {
            return "cooldowns=0";
        }
        Entry next = entries.values().stream()
            .min(Comparator.comparingInt(Entry::expiresAtTick))
            .orElse(null);
        return "cooldowns=" + entries.size()
            + ",next=" + (next == null ? "none" : next.pos().toShortString())
            + ",reason=" + (next == null ? "none" : next.reason())
            + ",expiresIn=" + (next == null ? 0 : Math.max(0, next.expiresAtTick() - tick));
    }

    public void clear() {
        entries.clear();
    }

    private void prune(int tick) {
        entries.entrySet().removeIf(entry -> tick >= entry.getValue().expiresAtTick());
    }

    private int cooldownTicksFor(String reason, int failures) {
        int multiplier = Math.max(1, Math.min(4, failures));
        if (reason.contains("movement_stuck") || reason.contains("passage_blocked")) {
            return DEFAULT_COOLDOWN_TICKS * multiplier;
        }
        if (reason.contains("above_current_layer") || reason.contains("no_exposure")) {
            return DEFAULT_COOLDOWN_TICKS * 2;
        }
        return DEFAULT_COOLDOWN_TICKS;
    }

    public record Entry(BlockPos pos, String reason, int firstRejectedTick, int expiresAtTick, int failures, int lastLogTick) {
        Entry withLastLogTick(int tick) {
            return new Entry(pos, reason, firstRejectedTick, expiresAtTick, failures, tick);
        }
    }
}
