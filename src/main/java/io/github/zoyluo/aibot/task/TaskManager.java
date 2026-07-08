package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.observe.BotProfiler;
import io.github.zoyluo.aibot.observe.TpsGuard;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class TaskManager {
    public static final TaskManager INSTANCE = new TaskManager();

    private final Map<UUID, Task> active = new ConcurrentHashMap<>();
    private final Map<UUID, Task> paused = new ConcurrentHashMap<>();
    private final Map<UUID, TaskStatus> lastStatus = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> lastFailure = new ConcurrentHashMap<>();
    private final Map<UUID, FailureRecord> pendingFailure = new ConcurrentHashMap<>();

    private TaskManager() {
    }

    public void assign(AIPlayerEntity bot, Task task) {
        abort(bot);
        bot.getActionPack().stopAll();
        active.put(bot.getUuid(), task);
        task.start(bot);
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(bot.getUuid(), status);
        BotReporter.INSTANCE.onAssigned(bot, status);
        BotLog.task(bot, "task_assigned", "name", task.name(), "params", task.describe());
    }

    public void abort(AIPlayerEntity bot) {
        Task current = active.remove(bot.getUuid());
        if (current != null) {
            current.abort(bot);
            lastStatus.put(bot.getUuid(), TaskStatus.from(current));
            BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.from(current));
        }
    }

    /**
     * 把 bot 彻底复位到干净的空闲:停掉活跃/暂停任务、清失败记录与状态缓存,使 status() 返回 idle。
     * 供大脑在"反复失败已放弃"(max_turns)等场景善后调用——否则遗留任务 FAILED 后 lastStatus 会长期
     * 缓存 FAILED(面板/诊断一直显示卡死),pendingFailure 滞留也会让 idle-watcher 空转(实测发呆 13 分钟根因)。
     */
    public void resetToIdle(AIPlayerEntity bot) {
        UUID uuid = bot.getUuid();
        Task current = active.remove(uuid);
        if (current != null) {
            current.abort(bot);
        }
        Task pausedTask = paused.remove(uuid);
        if (pausedTask != null) {
            pausedTask.abort(bot);
        }
        lastFailure.remove(uuid);
        pendingFailure.remove(uuid);
        lastStatus.put(uuid, TaskStatus.idle());
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, TaskStatus.idle());
    }

    public Optional<Task> getActive(AIPlayerEntity bot) {
        return Optional.ofNullable(active.get(bot.getUuid()));
    }

    public boolean hasPaused(AIPlayerEntity bot) {
        return paused.containsKey(bot.getUuid());
    }

    public TaskStatus status(AIPlayerEntity bot) {
        Task current = active.get(bot.getUuid());
        if (current != null) {
            return TaskStatus.from(current);
        }
        Task pausedTask = paused.get(bot.getUuid());
        if (pausedTask != null) {
            return TaskStatus.from(pausedTask);
        }
        return lastStatus.getOrDefault(bot.getUuid(), TaskStatus.idle());
    }

    public void pauseFor(AIPlayerEntity bot, String why) {
        Task current = active.remove(bot.getUuid());
        if (current == null) {
            return;
        }
        // 单槽 paused 池已有暂存任务(必是最早被抢占的目标步)→ 不能被后续生存抢占覆盖驱逐。
        // 此刻的 current 必是生存反射任务(如 combat 被 shelter 二次抢占):它是反应式的,条件仍在
        // 会被 DangerWatcher 每 tick 重新派发,无需 resume。直接 abort 出局、保留目标步在池里,生存链
        // 打完才 resume 回目标——治嵌套抢占(mine_ore→combat→shelter)把目标步挤出单槽池 → tickBot 误判
        // 显式替换 → goal_abandoned(实测 real_diamond 13 怪围攻深洞 light=0,目标丢后又派 light_area 症状)。
        if (paused.containsKey(bot.getUuid())) {
            current.abort(bot);
            // lastStatus 写保留的池任务(目标步)而非刚 abort 的 current(FAILED):面板显示应是被守护的目标;
            // 也免"若将来新增无 assign 跟随的 pauseFor 调用点、面板卡 FAILED"的隐患(审查加固建议)。
            Task preserved = paused.get(bot.getUuid());
            if (preserved != null) {
                lastStatus.put(bot.getUuid(), TaskStatus.from(preserved));
            }
            BotLog.task(bot, "pause_preempt_abort", "name", current.name(), "why", why);
            return;
        }
        current.pause(bot);
        paused.put(bot.getUuid(), current);
        TaskStatus status = TaskStatus.from(current);
        lastStatus.put(bot.getUuid(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        BotLog.task(bot, "task_paused", "name", current.name(), "why", why);
    }

    public void resumeFromPause(AIPlayerEntity bot) {
        if (active.containsKey(bot.getUuid())) {
            return;
        }
        Task task = paused.remove(bot.getUuid());
        if (task == null) {
            return;
        }
        active.put(bot.getUuid(), task);
        task.resume(bot);
        TaskStatus status = TaskStatus.from(task);
        lastStatus.put(bot.getUuid(), status);
        BotReporter.INSTANCE.onStatus(bot.getServer(), bot, status);
        BotLog.task(bot, "task_resumed", "name", task.name());
    }

    public void tickAll(MinecraftServer server) {
        for (Map.Entry<UUID, Task> entry : new ArrayList<>(active.entrySet())) {
            UUID uuid = entry.getKey();
            Task task = entry.getValue();
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(uuid);
            if (bot.isEmpty()) {
                active.remove(uuid);
                paused.remove(uuid);
                continue;
            }
            AIPlayerEntity player = bot.get();
            if (!isCritical(task) && !TpsGuard.INSTANCE.shouldTickNonCriticalTask(server)) {
                BotProfiler.INSTANCE.record(player, "task_tick_skipped", 0L);
                continue;
            }
            // V1 统一生存层:任务 tick 前熔断检查——溺水/岩浆/着火/垂死作业一律叫停,
            // 失败原因透传给 goal 层 replan。任务私有熔断可以更早更聪明,但漏配时这里兜底。
            String breaker = SurvivalGuard.INSTANCE.check(player, task);
            if (breaker != null && task.state() == TaskState.RUNNING) {
                task.abort(player);
                if (task instanceof AbstractTask at) {
                    at.failureReason = breaker; // abort 默认 "aborted",改成可诊断的熔断理由
                }
                BotLog.danger(player, "survival_guard_abort", "task", task.name(), "why", breaker);
            }
            long started = System.nanoTime();
            try {
                task.tick(player);
            } finally {
                BotProfiler.INSTANCE.record(player, "task_tick", System.nanoTime() - started);
            }
            TaskStatus status = TaskStatus.from(task);
            lastStatus.put(uuid, status);
            BotReporter.INSTANCE.onStatus(server, player, status);
            if (task.state() == TaskState.COMPLETED) {
                player.getActionPack().stopAll(); // BUGFIX: остановить остаточное движение
                active.remove(uuid);
                lastFailure.remove(uuid);
                pendingFailure.remove(uuid);
                BotLog.task(player, "task_completed", "name", task.name(), "elapsed_ticks", task.elapsedTicks());
            } else if (task.state() == TaskState.FAILED) {
                player.getActionPack().stopAll(); // BUGFIX: остановить остаточное движение
                active.remove(uuid);
                recordFailure(player, task.name(), task.failureReason(), server.getTicks());
                BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, player, "task_failed",
                        "name", task.name(), "reason", task.failureReason(), "elapsed_ticks", task.elapsedTicks());
            }
        }
    }

    public void recordFailure(AIPlayerEntity bot, String name, String reason, int tick) {
        UUID uuid = bot.getUuid();
        FailureRecord previous = lastFailure.get(uuid);
        int count = previous != null && previous.name().equals(name) && previous.reason().equals(reason)
                ? previous.count() + 1
                : 1;
        FailureRecord record = new FailureRecord(name, reason, count, tick);
        lastFailure.put(uuid, record);
        pendingFailure.put(uuid, record);
    }

    public Optional<FailureRecord> peekFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.get(bot.getUuid()));
    }

    public Optional<FailureRecord> consumeFailure(AIPlayerEntity bot) {
        return Optional.ofNullable(pendingFailure.remove(bot.getUuid()));
    }

    public void onServerStopping(MinecraftServer server) {
        for (AIPlayerEntity bot : AIPlayerManager.INSTANCE.all()) {
            abort(bot);
        }
        active.clear();
        paused.clear();
        lastFailure.clear();
        pendingFailure.clear();
        BotLog.task(null, "tasks_cleared");
    }

    public void onBotDespawn(AIPlayerEntity bot) {
        abort(bot);
        paused.remove(bot.getUuid());
        lastStatus.remove(bot.getUuid());
        lastFailure.remove(bot.getUuid());
        pendingFailure.remove(bot.getUuid());
        BotReporter.INSTANCE.onCleared(bot);
    }

    public int activeCount() {
        return active.size();
    }

    private static boolean isCritical(Task task) {
        return task instanceof EvadeTask || task instanceof CombatTask || task instanceof EatTask || task instanceof ResupplyTask;
    }

    public record FailureRecord(String name, String reason, int count, int tick) {
    }
}
