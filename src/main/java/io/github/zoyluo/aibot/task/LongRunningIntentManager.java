package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LongRunningIntentManager {
    public static final LongRunningIntentManager INSTANCE = new LongRunningIntentManager();

    private static final int RESTORE_THROTTLE_TICKS = 40;

    private final Map<UUID, FollowIntent> followIntents = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> nextRestoreTick = new ConcurrentHashMap<>();

    private LongRunningIntentManager() {
    }

    public void setFollow(AIPlayerEntity bot, String playerName) {
        followIntents.put(bot.getUuid(), new FollowIntent(playerName == null ? "" : playerName.trim()));
        BotLog.task(bot, "long_intent_set", "kind", "follow", "target", playerName == null ? "" : playerName.trim());
    }

    public void clear(AIPlayerEntity bot) {
        followIntents.remove(bot.getUuid());
        nextRestoreTick.remove(bot.getUuid());
        BotLog.task(bot, "long_intent_cleared");
    }

    public void clear(UUID botId) {
        followIntents.remove(botId);
        nextRestoreTick.remove(botId);
    }

    /**
     * 空闲恢复(P0/P1 修复):follow 意图的重建统一在"确认空闲"时做,而不是任务完成回调里。
     * 旧机制(TaskManager.tickAll 在任意任务 COMPLETED/FAILED 后立刻塞回 FollowTask)有两个病灶:
     *  - P0:goal 的步骤任务一完成,FollowTask 抢先占住 active 槽 → GoalExecutor 下一 tick 误判
     *    foreign_task_assigned → 整个目标在第一步后被静默放弃(直播场景:灯牌 follow + 跑车
     *    build_house 连送必踩)。
     *  - P1:FollowTask 自己被生存层熔断(FAILED)时,防递归规则跳过恢复 → 意图僵死,Bob 发呆,
     *    直到碰巧有别的任务完成才复活。
     * 空闲门槛:无活跃任务、无暂停任务(生存抢占中)、无进行中的 goal 计划,且目标玩家在线
     * (不在线就静默等,免得 FollowTask 空转刷"原地等"面板消息)。由 BotTickCoordinator 每 tick 调用,
     * 内部 40 tick 节流。
     */
    public void tickIdleRestore(MinecraftServer server, AIPlayerEntity bot) {
        FollowIntent follow = followIntents.get(bot.getUuid());
        if (follow == null) {
            return;
        }
        int now = server.getTicks();
        if (now < nextRestoreTick.getOrDefault(bot.getUuid(), 0)) {
            return;
        }
        nextRestoreTick.put(bot.getUuid(), now + RESTORE_THROTTLE_TICKS);
        if (TaskManager.INSTANCE.getActive(bot).isPresent()
                || TaskManager.INSTANCE.hasPaused(bot)
                || GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return;
        }
        if (!targetOnline(bot, follow.playerName())) {
            return;
        }
        Task task = new FollowTask(follow.playerName());
        TaskManager.INSTANCE.assign(bot, task);
        BotLog.task(bot, "long_intent_restored", "name", task.name(), "params", task.describe());
    }

    private static boolean targetOnline(AIPlayerEntity bot, String playerName) {
        if (!playerName.isBlank()) {
            return bot.getServer().getPlayerManager().getPlayer(playerName) != null;
        }
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.getServer().getPlayerManager().getPlayer(uuid) != null)
                .orElse(false);
    }

    private record FollowIntent(String playerName) {
    }
}
