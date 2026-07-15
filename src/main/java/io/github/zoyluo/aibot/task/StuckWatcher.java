package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StuckWatcher {
    public static final StuckWatcher INSTANCE = new StuckWatcher();

    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();

    private StuckWatcher() {
    }

    public void tick(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            tickBot(server, bot);
        }
    }

    public void tickBot(MinecraftServer server, AIPlayerEntity bot) {
        int now = server.getTicks();
        int window = AIBotConfig.get().watchdog().stuckWindowTicks();
        Optional<Task> active = TaskManager.INSTANCE.getActive(bot);
        if (active.isEmpty() || active.get().state() != TaskState.RUNNING || active.get().isWaiting()
                || bot.getActionPack().isNavigationWorkingStationary()) {
            samples.remove(bot.getUuid());
            return;
        }

        Task task = active.get();
        Sample current = new Sample(bot.getBlockPos().toImmutable(), task.progress(), inventoryTotal(bot), now);
        Sample previous = samples.get(bot.getUuid());
        if (previous == null || previous.changed(current)) {
            samples.put(bot.getUuid(), current);
            return;
        }

        if (now - previous.sinceTick() < window) {
            return;
        }

        String reason = "stuck:" + task.name();
        TaskManager.INSTANCE.abort(bot);
        TaskManager.INSTANCE.recordFailure(bot, task.name(), reason, now);
        samples.remove(bot.getUuid());
        BotLog.warn(LogCategory.TASK, bot, "task_stuck_aborted",
                "name", task.name(),
                "reason", reason,
                "window_ticks", window,
                "progress", task.progress(),
                "pos", current.pos().toShortString());
    }

    private static int inventoryTotal(AIPlayerEntity bot) {
        int total = 0;
        for (ItemStack stack : bot.getInventory().main) {
            total += stack.getCount();
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            total += stack.getCount();
        }
        return total;
    }

    private record Sample(BlockPos pos, double progress, int inventoryTotal, int sinceTick) {
        private boolean changed(Sample other) {
            return !pos.equals(other.pos)
                    || Math.abs(progress - other.progress) > 0.0001D
                    || inventoryTotal != other.inventoryTotal;
        }
    }
}
