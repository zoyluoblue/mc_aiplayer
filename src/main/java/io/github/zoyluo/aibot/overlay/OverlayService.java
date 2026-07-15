package io.github.zoyluo.aibot.overlay;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.gift.GiftDispatcher;
import io.github.zoyluo.aibot.gift.GiftLedger;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * OBS 浏览器源的数据出口:把 bot 状态/礼物流水/今日榜预序列化成一份 JSON 快照。
 * 线程模型:主线程每 20 tick 构建完整 JSON 后一次性写 volatile String,
 * GiftHttpBridge 的 HTTP 线程只读该 String(GET /status),零锁零拷贝。
 * recentGifts 仅主线程写(gift handle 经 mc.execute 已在主线程),refresh 也在主线程,无需同步;
 * lastBotSpeech 可能由 brain 回调线程写,volatile 取最新值即可。
 */
public final class OverlayService {
    public static final OverlayService INSTANCE = new OverlayService();

    private static final Gson GSON = new Gson();
    private static final int MAX_GIFTS = 10;
    private static final int TOP_N = 5;

    private volatile String statusJson = "{}";
    private volatile String lastBotSpeech = "";
    private final ArrayDeque<GiftRecord> recentGifts = new ArrayDeque<>();

    private OverlayService() {
    }

    /** HTTP 线程调用:预序列化好的完整快照。 */
    public String statusJson() {
        return statusJson;
    }

    /** 任意线程:bot 每说一句(role=="bot",与 TTS 念的同源)就更新浮层气泡。 */
    public void recordBotSpeech(String botName, String text) {
        if (text != null && !text.isBlank()) {
            lastBotSpeech = text.trim();
        }
    }

    /** 主线程(gift handle):礼物流水,最新在前,保留 10 条。 */
    public void recordGift(GiftDispatcher.GiftEvent event, int tier) {
        String user = event.user() == null || event.user().isBlank() ? "观众" : event.user().trim();
        recentGifts.addFirst(new GiftRecord(user, event.gift(), Math.max(1, event.count()),
                tier, System.currentTimeMillis()));
        while (recentGifts.size() > MAX_GIFTS) {
            recentGifts.removeLast();
        }
    }

    public void clear() {
        recentGifts.clear();
        lastBotSpeech = "";
        statusJson = "{}";
    }

    /** 主线程每 20 tick 调用:采集快照并整体替换 statusJson。 */
    public void refresh(MinecraftServer server) {
        JsonObject root = new JsonObject();
        root.addProperty("ts", System.currentTimeMillis());

        AIPlayerEntity bot = AIPlayerManager.INSTANCE
                .getByName(GiftDispatcher.INSTANCE.config().defaultBot())
                .orElseGet(() -> AIPlayerManager.INSTANCE.all().stream().findFirst().orElse(null));
        if (bot != null) {
            root.add("bot", botSection(bot));
        }
        root.addProperty("speech", lastBotSpeech);

        JsonArray gifts = new JsonArray();
        for (GiftRecord gift : recentGifts) {
            JsonObject item = new JsonObject();
            item.addProperty("user", gift.user());
            item.addProperty("gift", gift.gift());
            item.addProperty("count", gift.count());
            item.addProperty("tier", gift.tier());
            item.addProperty("ts", gift.timestamp());
            gifts.add(item);
        }
        root.add("gifts", gifts);

        JsonArray top = new JsonArray();
        int rank = 1;
        for (Map.Entry<String, Long> entry : GiftLedger.INSTANCE.topToday(TOP_N)) {
            JsonObject item = new JsonObject();
            item.addProperty("rank", rank++);
            item.addProperty("user", entry.getKey());
            item.addProperty("value", entry.getValue());
            top.add(item);
        }
        root.add("topToday", top);

        statusJson = GSON.toJson(root);
    }

    private static JsonObject botSection(AIPlayerEntity bot) {
        JsonObject section = new JsonObject();
        section.addProperty("name", bot.getGameProfile().getName());
        section.addProperty("health", bot.getHealth());
        section.addProperty("maxHealth", bot.getMaxHealth());
        section.addProperty("food", bot.getHungerManager().getFoodLevel());
        section.addProperty("x", bot.getBlockX());
        section.addProperty("y", bot.getBlockY());
        section.addProperty("z", bot.getBlockZ());

        TaskStatus task = TaskManager.INSTANCE.status(bot);
        section.addProperty("task", task.name());
        section.addProperty("taskDesc", task.description());
        section.addProperty("taskState", task.state().name());
        section.addProperty("taskProgress", task.progress());

        // goal 链条:优先 GoalExecutor 的实际计划,没有再回退大脑 set_goal 的 memory(同面板 snapshot 逻辑)。
        BotMemory memory = BotMemoryStore.INSTANCE.of(bot.getUuid());
        boolean hasPlan = GoalExecutor.INSTANCE.hasActivePlan(bot);
        String goalTitle = hasPlan ? GoalExecutor.INSTANCE.activeGoalTitle(bot) : memory.goalTitle();
        List<String> goalSteps = hasPlan ? GoalExecutor.INSTANCE.activeGoalSteps(bot) : memory.goalSteps();
        int goalIndex = hasPlan ? GoalExecutor.INSTANCE.activeGoalCurrentIndex(bot) : memory.goalCurrentStepIndex();
        int goalTotal = hasPlan ? GoalExecutor.INSTANCE.activeGoalTotalSteps(bot) : memory.goalTotalSteps();
        String goalStep = goalIndex >= 0 && goalIndex < goalSteps.size()
                ? goalSteps.get(goalIndex) : memory.currentGoalStep().orElse("");
        section.addProperty("goalTitle", goalTitle == null ? "" : goalTitle);
        section.addProperty("goalStep", goalStep == null ? "" : goalStep);
        section.addProperty("goalIndex", goalIndex);
        section.addProperty("goalTotal", goalTotal);
        return section;
    }

    private record GiftRecord(String user, String gift, int count, int tier, long timestamp) {
    }
}
