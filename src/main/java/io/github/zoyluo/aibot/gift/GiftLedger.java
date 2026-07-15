package io.github.zoyluo.aibot.gift;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 送礼榜:per-user 累计送礼次数 + 加权价值(tier×count),allTime 与按日各一份(按日保留 7 天)。
 * 存 config/aibot_gift_ledger.json 而非 world 目录——榜单是直播频道资产(观众真金白银),
 * 主播换图/删档重开新世界,粉丝贡献必须保留。
 * 全部读写在服务端主线程(record 由 gift handle 主线程调,tick 挂 END_SERVER_TICK),无需锁。
 */
public final class GiftLedger {
    public static final GiftLedger INSTANCE = new GiftLedger();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SAVE_INTERVAL_TICKS = 1200;  // 60s,dirty 才落盘
    private static final int HOUR_CHECK_INTERVAL_TICKS = 400;
    private static final int DAYS_KEPT = 7;

    private final Map<String, Entry> allTime = new LinkedHashMap<>();
    private final Map<String, Map<String, Entry>> byDay = new LinkedHashMap<>();
    private boolean dirty;
    private int lastSaveTick;
    private int lastHourCheckTick;
    private int lastThankedHour = -1;

    private GiftLedger() {
    }

    public void record(String user, String gift, int count, int tier) {
        String name = user == null || user.isBlank() ? "观众" : user.trim();
        long weighted = (long) Math.max(1, tier) * Math.max(1, count);
        allTime.computeIfAbsent(name, key -> new Entry()).add(count, weighted);
        String today = LocalDate.now().toString();
        byDay.computeIfAbsent(today, key -> new LinkedHashMap<>())
                .computeIfAbsent(name, key -> new Entry()).add(count, weighted);
        byDay.keySet().removeIf(day -> day.compareTo(LocalDate.now().minusDays(DAYS_KEPT).toString()) < 0);
        dirty = true;
    }

    /** 今日榜 topN,按加权价值降序。返回 (用户名, 加权值) 列表。 */
    public List<Map.Entry<String, Long>> topToday(int n) {
        return top(byDay.getOrDefault(LocalDate.now().toString(), Map.of()), n);
    }

    public List<Map.Entry<String, Long>> topAll(int n) {
        return top(allTime, n);
    }

    private static List<Map.Entry<String, Long>> top(Map<String, Entry> source, int n) {
        return source.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().weightedValue))
                .sorted(Comparator.<Map.Entry<String, Long>>comparingLong(Map.Entry::getValue).reversed())
                .limit(n)
                .toList();
    }

    /** 主线程每 tick 调用:定期落盘 + 整点感谢今日榜。 */
    public void tick(MinecraftServer server) {
        int now = server.getTicks();
        if (dirty && now - lastSaveTick >= SAVE_INTERVAL_TICKS) {
            lastSaveTick = now;
            saveNow();
        }
        if (now - lastHourCheckTick >= HOUR_CHECK_INTERVAL_TICKS) {
            lastHourCheckTick = now;
            hourlyThanks(server);
        }
    }

    private void hourlyThanks(MinecraftServer server) {
        if (!GiftDispatcher.INSTANCE.config().hourlyThanks()) {
            return;
        }
        int hour = LocalDateTime.now().getHour();
        if (hour == lastThankedHour) {
            return;
        }
        List<Map.Entry<String, Long>> top = topToday(3);
        if (top.isEmpty()) {
            lastThankedHour = hour;  // 空榜也翻篇,避免整点后第一份礼物立刻触发"榜单感谢"显得突兀
            return;
        }
        lastThankedHour = hour;
        StringBuilder text = new StringBuilder("感谢今日送礼榜:");
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
                text.append("、");
            }
            text.append("No.").append(i + 1).append(" ").append(top.get(i).getKey());
        }
        text.append("！比心！");
        server.getPlayerManager().broadcast(Text.literal(text.toString()).formatted(Formatting.GOLD), false);
        // 借默认 bot 的面板通道走一遍,采集客户端 TTS 会把感谢念出来。
        AIPlayerManager.INSTANCE.getByName(GiftDispatcher.INSTANCE.config().defaultBot())
                .ifPresent(bot -> BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", text.toString()));
        BotLog.task(null, "gift_hourly_thanks", "hour", hour, "top", top.size());
    }

    public void load() {
        Path file = file();
        allTime.clear();
        byDay.clear();
        dirty = false;
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            readEntries(root.get("allTime"), allTime);
            if (root.has("byDay") && root.get("byDay").isJsonObject()) {
                for (Map.Entry<String, JsonElement> day : root.getAsJsonObject("byDay").entrySet()) {
                    Map<String, Entry> entries = new LinkedHashMap<>();
                    readEntries(day.getValue(), entries);
                    if (!entries.isEmpty()) {
                        byDay.put(day.getKey(), entries);
                    }
                }
            }
            BotLog.lifecycle("gift_ledger_loaded", "users", allTime.size(), "days", byDay.size());
        } catch (Exception exception) {
            BotLog.error("gift_ledger_load_failed", exception, "path", file);
        }
    }

    public void saveNow() {
        Path file = file();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.add("allTime", writeEntries(allTime));
            JsonObject days = new JsonObject();
            for (Map.Entry<String, Map<String, Entry>> day : byDay.entrySet()) {
                days.add(day.getKey(), writeEntries(day.getValue()));
            }
            root.add("byDay", days);
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            BotLog.error("gift_ledger_save_failed", exception, "path", file);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    private static void readEntries(JsonElement element, Map<String, Entry> target) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> item : element.getAsJsonObject().entrySet()) {
            if (!item.getValue().isJsonObject()) {
                continue;
            }
            JsonObject object = item.getValue().getAsJsonObject();
            Entry entry = new Entry();
            entry.totalCount = object.has("count") ? object.get("count").getAsLong() : 0;
            entry.weightedValue = object.has("value") ? object.get("value").getAsLong() : 0;
            target.put(item.getKey(), entry);
        }
    }

    private static JsonObject writeEntries(Map<String, Entry> source) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, Entry> item : source.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("count", item.getValue().totalCount);
            entry.addProperty("value", item.getValue().weightedValue);
            object.add(item.getKey(), entry);
        }
        return object;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("aibot_gift_ledger.json");
    }

    private static final class Entry {
        long totalCount;
        long weightedValue;

        void add(int count, long weighted) {
            totalCount += Math.max(1, count);
            weightedValue += weighted;
        }
    }
}
