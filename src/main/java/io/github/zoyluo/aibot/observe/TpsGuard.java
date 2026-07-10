package io.github.zoyluo.aibot.observe;

import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.server.MinecraftServer;

public final class TpsGuard {
    public static final TpsGuard INSTANCE = new TpsGuard();

    private static final double DEGRADED_TICK_MS = 55.0D;
    private static final int NORMAL_CONTINUATION_SECONDS = 3;
    private static final int DEGRADED_CONTINUATION_SECONDS = 8;
    private static final int NORMAL_SCAN_INTERVAL = 1;
    private static final int DEGRADED_SCAN_INTERVAL = 20;
    private static final int DEGRADED_DANGER_SCAN_INTERVAL = 5;
    private static final int NON_CRITICAL_TASK_INTERVAL = 5;

    private long lastSampleNanos;
    private double averageTickMs = 20.0D;
    private boolean lastDegraded;
    private int sampleCount;

    private TpsGuard() {
    }

    public synchronized void tick(MinecraftServer server) {
        long now = System.nanoTime();
        if (lastSampleNanos == 0L) {
            lastSampleNanos = now;
            return;
        }
        double elapsedMs = Math.max(0.0D, (now - lastSampleNanos) / 1_000_000.0D);
        lastSampleNanos = now;
        if (elapsedMs > 200.0D) {
            return;
        }
        sampleCount++;
        averageTickMs = averageTickMs * 0.95D + elapsedMs * 0.05D;
        boolean degraded = degraded(server);
        if (degraded != lastDegraded) {
            lastDegraded = degraded;
            BotLog.profile(null, "tps_guard_state",
                    "degraded", degraded,
                    "avg_tick_ms", String.format(java.util.Locale.ROOT, "%.2f", averageTickMs),
                    "estimated_tps", String.format(java.util.Locale.ROOT, "%.2f", estimatedTps()));
        }
    }

    public synchronized boolean degraded(MinecraftServer server) {
        return sampleCount >= 40 && averageTickMs > DEGRADED_TICK_MS;
    }

    public synchronized int continuationDelaySeconds() {
        return lastDegraded ? DEGRADED_CONTINUATION_SECONDS : NORMAL_CONTINUATION_SECONDS;
    }

    public synchronized int scanInterval() {
        return lastDegraded ? DEGRADED_SCAN_INTERVAL : NORMAL_SCAN_INTERVAL;
    }

    public synchronized int dangerScanInterval() {
        return lastDegraded ? DEGRADED_DANGER_SCAN_INTERVAL : NORMAL_SCAN_INTERVAL;
    }

    public synchronized boolean shouldTickNonCriticalTask(MinecraftServer server) {
        return !lastDegraded || server.getTicks() % NON_CRITICAL_TASK_INTERVAL == 0;
    }

    public synchronized Snapshot snapshot(MinecraftServer server) {
        return new Snapshot(averageTickMs, estimatedTps(), lastDegraded, continuationDelaySeconds(), scanInterval());
    }

    public synchronized void reset() {
        lastSampleNanos = 0L;
        averageTickMs = 20.0D;
        lastDegraded = false;
        sampleCount = 0;
    }

    private double estimatedTps() {
        return Math.min(20.0D, 1000.0D / Math.max(1.0D, averageTickMs));
    }

    public record Snapshot(double averageTickMs, double estimatedTps, boolean degraded, int continuationDelaySeconds, int scanInterval) {
    }
}
