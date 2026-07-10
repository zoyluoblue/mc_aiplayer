package io.github.zoyluo.aibot.observe;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BotProfiler {
    public static final BotProfiler INSTANCE = new BotProfiler();

    private static final int MAX_SAMPLES = 256;

    private final Map<UUID, Map<String, Samples>> byBot = new ConcurrentHashMap<>();

    private BotProfiler() {
    }

    public void record(AIPlayerEntity bot, String section, long nanos) {
        if (bot == null || section == null || section.isBlank() || nanos < 0L) {
            return;
        }
        record(bot.getUuid(), bot.getGameProfile().getName(), section, nanos);
    }

    public void record(UUID botId, String botName, String section, long nanos) {
        if (botId == null || section == null || section.isBlank() || nanos < 0L) {
            return;
        }
        Samples samples = byBot
                .computeIfAbsent(botId, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(section, ignored -> new Samples());
        samples.add(nanos);
        if (nanos >= 50_000_000L) {
            BotLog.profile(null, "profile_slow_section",
                    "bot", botName == null ? botId : botName,
                    "section", section,
                    "elapsed_ms", String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0D));
        }
    }

    public Map<String, Stat> snapshot(UUID botId) {
        Map<String, Samples> sections = byBot.get(botId);
        if (sections == null || sections.isEmpty()) {
            return Map.of();
        }
        Map<String, Stat> stats = new LinkedHashMap<>();
        sections.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> stats.put(entry.getKey(), entry.getValue().snapshot()));
        return stats;
    }

    public void clear(UUID botId) {
        byBot.remove(botId);
    }

    public void clearAll() {
        byBot.clear();
    }

    private static final class Samples {
        private final ArrayDeque<Long> nanos = new ArrayDeque<>();

        private synchronized void add(long value) {
            nanos.addLast(value);
            while (nanos.size() > MAX_SAMPLES) {
                nanos.removeFirst();
            }
        }

        private synchronized Stat snapshot() {
            if (nanos.isEmpty()) {
                return new Stat(0, 0.0D, 0.0D, 0.0D);
            }
            List<Long> values = new ArrayList<>(nanos);
            values.sort(Long::compareTo);
            long total = 0L;
            for (long value : values) {
                total += value;
            }
            int p95Index = Math.min(values.size() - 1, (int) Math.ceil(values.size() * 0.95D) - 1);
            return new Stat(
                    values.size(),
                    total / (double) values.size() / 1_000_000.0D,
                    values.get(p95Index) / 1_000_000.0D,
                    values.get(values.size() - 1) / 1_000_000.0D);
        }
    }

    public record Stat(int count, double avgMs, double p95Ms, double maxMs) {
    }
}
