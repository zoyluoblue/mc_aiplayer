package io.github.zoyluo.aibot;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.brain.ChatCaptureListener;
import io.github.zoyluo.aibot.command.AIBotCommand;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AIBotMod implements ModInitializer {
    public static final String MOD_ID = "aibot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("================================");
        LOGGER.info("  AIBot v{} loaded", getModVersion());
        AIBotConfig config = AIBotConfig.load();

        LOGGER.info("  Mode: M4 DeepSeek brain");
        LOGGER.info("================================");

        BrainCoordinator.INSTANCE.configure(config);
        ChatCaptureListener.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("[AIBot] Server started: {}", server.getServerMotd()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
                LOGGER.info("[AIBot] Server stopping"));
        ServerLifecycleEvents.SERVER_STOPPING.register(AIPlayerManager.INSTANCE::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BrainCoordinator.INSTANCE.shutdown());
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
