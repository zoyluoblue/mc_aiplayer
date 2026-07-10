package io.github.zoyluo.aibot.runtime;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.brain.BotReporter;
import io.github.zoyluo.aibot.brain.BotRuntimeOptions;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.coordination.IdleCoordinator;
import io.github.zoyluo.aibot.coordination.TaskBoard;
import io.github.zoyluo.aibot.craft.RuntimeRecipeIndex;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.BotLogWriter;
import io.github.zoyluo.aibot.log.DiagnosticLogger;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.memory.EpisodeLog;
import io.github.zoyluo.aibot.memory.KnowledgeBase;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.network.AIBotServerNetworking;
import io.github.zoyluo.aibot.observe.BotProfiler;
import io.github.zoyluo.aibot.observe.ReplayRecorder;
import io.github.zoyluo.aibot.observe.TpsGuard;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.task.DangerWatcher;
import io.github.zoyluo.aibot.task.EpisodeMemory;
import io.github.zoyluo.aibot.task.NavSafetyNet;
import io.github.zoyluo.aibot.task.StuckWatcher;
import io.github.zoyluo.aibot.task.TaskManager;
import net.minecraft.server.MinecraftServer;

/** Single ordering authority for world start/stop and Bot reset/death/despawn cleanup. */
public final class RuntimeLifecycleCoordinator {
    public static final RuntimeLifecycleCoordinator INSTANCE = new RuntimeLifecycleCoordinator();

    private RuntimeLifecycleCoordinator() {
    }

    public void onServerStarted(MinecraftServer server, AIBotConfig config) {
        clearWorldRuntime();
        BotLogWriter.INSTANCE.start(config);
        BrainCoordinator.INSTANCE.configure(config);
        BotPersistence.INSTANCE.resumeWrites();
        RuntimeRecipeIndex.rebuild(server);
        KnowledgeBase.INSTANCE.attachServer(server);
        int restored = BotPersistence.INSTANCE.loadAndRespawn(server);
        BotLog.lifecycle("server_runtime_ready", "restored_bots", restored,
                "runtime_session", TaskBoard.INSTANCE.runtimeSessionId());
    }

    public void onServerStopping(MinecraftServer server) {
        BotLog.lifecycle("server_stopping");
        BotPersistence.INSTANCE.freezeWrites();
        int persisted = BotPersistence.INSTANCE.saveAll(server);
        AIPlayerManager.INSTANCE.onServerStopping(server);
        BrainCoordinator.INSTANCE.shutdown();
        clearWorldRuntime();
        KnowledgeBase.INSTANCE.detachServer();
        RuntimeRecipeIndex.clear();
        BotLog.lifecycle("server_runtime_stopped", "persisted_bots", persisted);
        BotLogWriter.INSTANCE.shutdown(3000);
    }

    public void resetBot(AIPlayerEntity bot, IntentController.ControlOrigin origin, String reason) {
        IntentController.INSTANCE.cancelAll(bot, origin, reason);
        BrainCoordinator.INSTANCE.reset(bot);
        GoalExecutor.INSTANCE.unload(bot);
        TaskManager.INSTANCE.resetToIdle(bot);
        clearTransient(bot);
        BotLog.lifecycle(bot, "bot_runtime_reset", "reason", reason);
    }

    public void onBotDeath(AIPlayerEntity bot) {
        BrainCoordinator.INSTANCE.invalidateDecision(bot, "bot_death");
        BrainCoordinator.INSTANCE.clearIntentWakeSources(bot);
        GoalExecutor.INSTANCE.failCurrent(bot, "bot_died");
        GoalExecutor.INSTANCE.clearQueue(bot);
        BotMemoryStore.INSTANCE.of(bot.getUuid()).clearGoal();
        IdleCoordinator.INSTANCE.cancelClaimedJob(bot, "bot_died");
        TaskManager.INSTANCE.cancelIntentTasks(bot, "bot_died");
        bot.getActionPack().stopAll();
        BrainCoordinator.INSTANCE.reset(bot);
        clearTransient(bot);
        BotLog.lifecycle(bot, "bot_runtime_death_reset");
    }

    /** Explicit despawn is deletion: publish cancellation first, then forget every cached projection. */
    public void deleteBot(AIPlayerEntity bot) {
        IntentController.INSTANCE.cancelAll(bot, IntentController.ControlOrigin.SYSTEM, "bot_despawn");
        BrainCoordinator.INSTANCE.reset(bot);
        IdleCoordinator.INSTANCE.onBotRemoved(bot);
        TaskManager.INSTANCE.onBotDespawn(bot);
        GoalExecutor.INSTANCE.unload(bot);
        forgetBot(bot);
    }

    /** Server stop is unload, not deletion; the already-captured Mission must not receive CANCELLED. */
    public void unloadBot(AIPlayerEntity bot) {
        BrainCoordinator.INSTANCE.invalidateDecision(bot, "server_unload");
        BrainCoordinator.INSTANCE.reset(bot);
        GoalExecutor.INSTANCE.unload(bot);
        IdleCoordinator.INSTANCE.onBotUnloaded(bot);
        TaskManager.INSTANCE.onBotDespawn(bot);
        bot.getActionPack().stopAll();
        forgetBot(bot);
    }

    private static void clearTransient(AIPlayerEntity bot) {
        StuckWatcher.INSTANCE.reset(bot);
        DangerWatcher.INSTANCE.clear(bot);
        NavSafetyNet.INSTANCE.clear(bot);
        EpisodeMemory.INSTANCE.reset(bot.getUuid());
        BotReporter.INSTANCE.onCleared(bot);
        DiagnosticLogger.INSTANCE.clear(bot);
        CapabilityRuntime.clear(bot);
    }

    private static void forgetBot(AIPlayerEntity bot) {
        clearTransient(bot);
        BotRuntimeOptions.INSTANCE.clear(bot);
        BotMemoryStore.INSTANCE.remove(bot.getUuid());
        EpisodeLog.INSTANCE.clearFor(bot.getUuid());
        KnowledgeBase.INSTANCE.forget(bot.getUuid());
        ReplayRecorder.INSTANCE.clear(bot.getUuid());
        BotProfiler.INSTANCE.clear(bot.getUuid());
        AIBotServerNetworking.INSTANCE.clearBot(bot.getUuid());
    }

    private static void clearWorldRuntime() {
        GoalExecutor.INSTANCE.clearAllRuntime();
        TaskManager.INSTANCE.clearAllRuntime();
        IdleCoordinator.INSTANCE.clearAllRuntime();
        TaskBoard.INSTANCE.clear();
        DangerWatcher.INSTANCE.clearAll();
        NavSafetyNet.INSTANCE.clearAll();
        StuckWatcher.INSTANCE.clearAll();
        EpisodeMemory.INSTANCE.clearAll();
        EpisodeLog.INSTANCE.clearAll();
        BotMemoryStore.INSTANCE.clear();
        BotRuntimeOptions.INSTANCE.clearAll();
        BotReporter.INSTANCE.clearAll();
        DiagnosticLogger.INSTANCE.clearAll();
        ReplayRecorder.INSTANCE.clearAll();
        BotProfiler.INSTANCE.clearAll();
        AIBotServerNetworking.INSTANCE.clear();
        CapabilityRuntime.clearAll();
        TpsGuard.INSTANCE.reset();
        AStarPathfinder.invalidateCache("runtime_world_boundary");
    }
}
