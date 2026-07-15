package io.github.zoyluo.aibot;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.brain.ChatCaptureListener;
import io.github.zoyluo.aibot.command.AIBotCommand;
import io.github.zoyluo.aibot.command.AIBotVerifySubcommand;
import io.github.zoyluo.aibot.gift.GiftCelebrator;
import io.github.zoyluo.aibot.gift.GiftDispatcher;
import io.github.zoyluo.aibot.gift.GiftHttpBridge;
import io.github.zoyluo.aibot.gift.GiftLedger;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.BotLogWriter;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.network.AIBotServerNetworking;
import io.github.zoyluo.aibot.network.payload.AIPayloads;
import io.github.zoyluo.aibot.observe.TpsGuard;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.task.BotTickCoordinator;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AIBotMod implements ModInitializer {
    public static final String MOD_ID = "aibot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        AIBotConfig config = AIBotConfig.load();
        BotLogWriter.INSTANCE.start(config);
        io.github.zoyluo.aibot.log.ConversationLogger.INSTANCE.start(config);
        GiftDispatcher.INSTANCE.reload();
        BotLog.lifecycle("mod_loaded", "version", getModVersion());
        BotLog.config("config_loaded",
                "deepseek_model", config.deepseek().model(),
                "perception_radius", config.perception().radius(),
                "nav_lookahead", config.nav().lookahead(),
                "pickup_force_radius", config.pickup().forceRadiusH(),
                "logging_enabled", config.logging().enabled());

        LOGGER.info("================================");
        LOGGER.info("  AIBot v{} loaded", getModVersion());
        LOGGER.info("  Mode: M6 structured logs over M5 tasks");
        LOGGER.info("================================");

        BrainCoordinator.INSTANCE.configure(config);
        ChatCaptureListener.register();
        io.github.zoyluo.aibot.brain.OwnerEventListener.INSTANCE.register();
        AIPayloads.register();
        AIBotServerNetworking.INSTANCE.register();
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                AIBotServerNetworking.INSTANCE.clearTargetMarker(player, "切换维度，标记已清除"));

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            BotLog.lifecycle("server_started", "motd", server.getServerMotd());
            io.github.zoyluo.aibot.craft.RuntimeRecipeIndex.rebuild(server);
            io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE.attachServer(server);
            int restored = BotPersistence.INSTANCE.loadAndRespawn(server);
            if (restored > 0) {
                BotLog.lifecycle("bot_persist_restored", "count", restored);
            }
            GiftHttpBridge.INSTANCE.start(server);
            GiftLedger.INSTANCE.load();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                BotLog.lifecycle("server_stopping"));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GiftHttpBridge.INSTANCE.stop());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GiftCelebrator.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.brain.OwnerEventListener.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.action.IdleLookController.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.gift.DanmakuService.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.gift.IdleScheduler.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.camera.CameraMirror.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.overlay.OverlayService.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GiftLedger.INSTANCE.saveNow());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> AIBotServerNetworking.INSTANCE.clear());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.marker.TargetMarkerService.INSTANCE.clearAll());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BotPersistence.INSTANCE.saveAll(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(AIPlayerManager.INSTANCE::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BrainCoordinator.INSTANCE.shutdown());
        ServerLifecycleEvents.SERVER_STOPPING.register(TaskManager.INSTANCE::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> io.github.zoyluo.aibot.log.ConversationLogger.INSTANCE.shutdown());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BotLogWriter.INSTANCE.shutdown(3000));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TpsGuard.INSTANCE.tick(server);
            AIPlayerManager.INSTANCE.tickRespawns(server);
            TaskManager.INSTANCE.tickAll(server);
            BotTickCoordinator.INSTANCE.tick(server);
            AIBotVerifySubcommand.tick(server);
            AIBotServerNetworking.INSTANCE.tick(server);
            GiftCelebrator.INSTANCE.tick(server);
            GiftLedger.INSTANCE.tick(server);
            io.github.zoyluo.aibot.gift.DanmakuService.INSTANCE.tick(server);
            io.github.zoyluo.aibot.gift.IdleScheduler.INSTANCE.tick(server);
            io.github.zoyluo.aibot.camera.CameraMirror.INSTANCE.tick(server);
            if (server.getTicks() % 20 == 0) {
                io.github.zoyluo.aibot.overlay.OverlayService.INSTANCE.refresh(server);
            }
            io.github.zoyluo.aibot.log.DiagnosticLogger.INSTANCE.tick(server);
            if (server.getTicks() > 0 && server.getTicks() % 6000 == 0) {
                BotPersistence.INSTANCE.saveAllAsync(server);
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                AIBotCommand.register(dispatcher, registryAccess));
    }

    private static String getModVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
