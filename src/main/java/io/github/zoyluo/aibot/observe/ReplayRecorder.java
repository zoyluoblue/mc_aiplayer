package io.github.zoyluo.aibot.observe;

import com.google.gson.Gson;
import io.github.zoyluo.aibot.brain.ChatToolCall;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ReplayRecorder {
    public static final ReplayRecorder INSTANCE = new ReplayRecorder();

    private static final Gson GSON = new Gson();
    private static final int MAX_MEMORY_EVENTS = 200;
    private static final int MAX_TEXT = 1600;

    private final Map<UUID, ArrayDeque<ReplayEvent>> events = new ConcurrentHashMap<>();

    private ReplayRecorder() {
    }

    public void onDecision(AIPlayerEntity bot, String perceptionDigest, List<ChatToolCall> calls, String result) {
        if (bot == null) {
            return;
        }
        ReplayEvent event = new ReplayEvent(
                Instant.now().toString(),
                bot.getUuid().toString(),
                bot.getGameProfile().getName(),
                trim(perceptionDigest),
                calls == null ? List.of() : calls.stream().map(Call::from).toList(),
                trim(result));
        remember(bot.getUuid(), event);
        write(bot, event);
        BotLog.replay(bot, "replay_recorded", "calls", event.calls().size(), "result", event.result());
    }

    public List<ReplayEvent> tail(UUID botId, int count) {
        ArrayDeque<ReplayEvent> deque = events.get(botId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        List<ReplayEvent> all;
        synchronized (deque) {
            all = new ArrayList<>(deque);
        }
        int start = Math.max(0, all.size() - Math.max(1, count));
        return all.subList(start, all.size());
    }

    public void clear(UUID botId) {
        events.remove(botId);
    }

    public void clearAll() {
        events.clear();
    }

    private void remember(UUID botId, ReplayEvent event) {
        ArrayDeque<ReplayEvent> deque = events.computeIfAbsent(botId, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(event);
            while (deque.size() > MAX_MEMORY_EVENTS) {
                deque.removeFirst();
            }
        }
    }

    private void write(AIPlayerEntity bot, ReplayEvent event) {
        try {
            Path dir = bot.getServer().getSavePath(WorldSavePath.ROOT).resolve("aibot").resolve("replay");
            Files.createDirectories(dir);
            Path file = dir.resolve(safe(bot.getGameProfile().getName()) + "-" + LocalDate.now() + ".jsonl");
            try (Writer writer = Files.newBufferedWriter(file,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND)) {
                writer.write(GSON.toJson(event));
                writer.write("\n");
            }
        } catch (IOException | RuntimeException exception) {
            BotLog.error(bot, "replay_write_failed", exception);
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\n', ' ').trim();
        return cleaned.length() <= MAX_TEXT ? cleaned : cleaned.substring(0, MAX_TEXT - 3) + "...";
    }

    private static String safe(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    public record ReplayEvent(String timestamp, String botId, String botName, String perception, List<Call> calls, String result) {
        public String summary() {
            String callSummary = calls.isEmpty()
                    ? "no_tools"
                    : calls.stream().map(Call::name).reduce((left, right) -> left + "," + right).orElse("no_tools");
            return timestamp + " calls=" + callSummary + " result=" + result;
        }
    }

    public record Call(String id, String name, String arguments) {
        private static Call from(ChatToolCall call) {
            return new Call(call.id(), call.name(), trim(call.arguments()));
        }
    }
}
