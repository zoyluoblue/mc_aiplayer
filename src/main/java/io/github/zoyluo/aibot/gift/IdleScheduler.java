package io.github.zoyluo.aibot.gift;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 空闲自主找事(治"两个礼物之间 bot 罚站,画面是死的"——此前完全空闲时什么都不发生)。
 *
 * 形式(用户拍板"确定性池+偶尔唠嗑"):
 *  - 连续完全空闲 idleAfterSec(默认60s)后,从 pools["idle"] 随机抽一个动作执行,复用
 *    GiftDispatcher 的动作机制(say 词条=零 token 嘴上互动;wander/gather/mine/fish=小事干);
 *  - 每 idleChatterIntervalSec(默认300s,0=关)才允许一次大脑唠嗑(speak 一句就 finish,1 轮 API);
 *  - 空闲池任务失败**静默**(markSilentFailure):钓鱼没竿之类的失败绝不唤醒大脑烧 token;
 *  - 主人消息/礼物任务随时顶掉空闲任务(TaskManager.assign 先 abort,不产生失败记录)。
 *
 * 白名单排除 goal/build_house/brain/random:goal 失败链走 GoalExecutor 静默不了;random 递归不可控。
 * 夜间(!isDay)只抽 say——别摸黑出去送人头。
 */
public final class IdleScheduler {
    public static final IdleScheduler INSTANCE = new IdleScheduler();

    private static final Set<String> IDLE_ACTION_WHITELIST =
            Set.of("say", "gather", "mine", "wander", "fish", "come_here");
    private static final long CHATTER_FEED_GAP_MS = 10_000L;

    private long lastNonIdleMs = System.currentTimeMillis();
    private long lastPoolActionMs;
    private long lastChatterMs;
    private long lastEmptyPoolLogMs;

    private IdleScheduler() {
    }

    /**
     * 完全空闲判定(DanmakuService digest 共用):大脑不忙 + 无活跃/暂停任务 + 无待喂失败 +
     * 无确定性计划 + 无长期目标。follow 意图 ≤2s 会被 tickIdleRestore 变回 active 任务,
     * DangerWatcher 紧急态也都表现为 active 任务——第二条天然覆盖。
     */
    public static boolean fullyIdle(AIPlayerEntity bot) {
        return !BrainCoordinator.INSTANCE.status(bot).busy()
                && TaskManager.INSTANCE.getActive(bot).isEmpty()
                && !TaskManager.INSTANCE.hasPaused(bot)
                && TaskManager.INSTANCE.peekFailure(bot).isEmpty()
                && !io.github.zoyluo.aibot.goal.GoalExecutor.INSTANCE.hasActivePlan(bot)
                && !BotMemoryStore.INSTANCE.of(bot.getUuid()).hasActiveGoal();
    }

    /** END_SERVER_TICK 每 tick 调,内部 20 tick(1s)走一遍。 */
    public void tick(MinecraftServer server) {
        if (server.getTicks() % 20 != 0) {
            return;
        }
        GiftBridgeConfig config = GiftDispatcher.INSTANCE.config();
        AIPlayerEntity bot = resolveBot(config);
        long now = System.currentTimeMillis();
        if (bot == null || !fullyIdle(bot)) {
            lastNonIdleMs = now;
            return;
        }
        long idleMs = now - lastNonIdleMs;
        long idleAfterMs = config.idleAfterSec() * 1000L;
        if (idleMs < idleAfterMs) {
            return;
        }
        // 唠嗑优先(更稀有):到点且离上次全局喂脑(含弹幕)≥10s
        int chatterSec = config.idleChatterIntervalSec();
        if (chatterSec > 0
                && now - lastChatterMs >= chatterSec * 1000L
                && now - DanmakuService.INSTANCE.lastBrainFeedMs() >= CHATTER_FEED_GAP_MS) {
            lastChatterMs = now;
            lastNonIdleMs = now;
            BotLog.comm(bot, "idle_chatter_feed");
            BrainCoordinator.INSTANCE.handleMessage(bot, "system:idle",
                    "你现在完全空闲。结合 Current state 里看到的环境或你正在想的事,speak 一句有意思的话给观众(≤30字),然后立刻 finish。不开任务,不调其他工具。");
            return;
        }
        // 抽池干小事
        if (!config.idleEnabled() || now - lastPoolActionMs < idleAfterMs) {
            return;
        }
        List<GiftBridgeConfig.GiftAction> pool = config.pools().getOrDefault("idle", List.of());
        boolean day = bot.getServerWorld().isDay();
        List<GiftBridgeConfig.GiftAction> eligible = pool.stream()
                .filter(action -> action.type() != null && IDLE_ACTION_WHITELIST.contains(action.type().toLowerCase(java.util.Locale.ROOT)))
                .filter(action -> day || "say".equalsIgnoreCase(action.type())) // 夜间只嘴上互动,不出门送人头
                .toList();
        if (eligible.isEmpty()) {
            if (now - lastEmptyPoolLogMs > 300_000L) {
                lastEmptyPoolLogMs = now;
                BotLog.task(bot, "idle_pool_empty");
            }
            return;
        }
        GiftBridgeConfig.GiftAction picked = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
        lastPoolActionMs = now;
        lastNonIdleMs = now;
        String result = GiftDispatcher.INSTANCE.executeIdleAction(server, bot, picked);
        // 空闲任务失败静默:同 tick 主线程登记,失败绝不进 pendingFailure 唤醒大脑
        TaskManager.INSTANCE.getActive(bot).ifPresent(task -> TaskManager.INSTANCE.markSilentFailure(bot, task));
        BotLog.task(bot, "idle_pool_action", "type", picked.type(), "result", result);
    }

    public void clear() {
        lastNonIdleMs = System.currentTimeMillis();
        lastPoolActionMs = 0L;
        lastChatterMs = 0L;
    }

    private static AIPlayerEntity resolveBot(GiftBridgeConfig config) {
        Optional<AIPlayerEntity> byName = AIPlayerManager.INSTANCE.getByName(config.defaultBot());
        if (byName.isPresent()) {
            return byName.get();
        }
        var all = AIPlayerManager.INSTANCE.all();
        return all.size() == 1 ? all.iterator().next() : null;
    }
}
