package com.aiplayer;

import com.mojang.logging.LogUtils;
import com.aiplayer.command.AiPlayerCommands;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.entity.AiPlayerManager;
import com.aiplayer.event.ServerEventHandler;
import com.aiplayer.util.CategorizedLogAppender;
import com.aiplayer.util.CategorizedLogWriter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;

import net.minecraft.core.Registry;

public class AiPlayerMod implements ModInitializer {
    public static final String MODID = "aiplayer";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<EntityType<?>> AI_PLAYER_ENTITY_KEY =
        ResourceKey.create(Registries.ENTITY_TYPE, id("aiplayer"));

    public static final EntityType<AiPlayerEntity> AI_PLAYER_ENTITY = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        id("aiplayer"),
        EntityType.Builder.of(AiPlayerEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build(AI_PLAYER_ENTITY_KEY)
    );

    private static AiPlayerManager aiPlayerManager;

    @Override
    public void onInitialize() {
        CategorizedLogAppender.install();
        AiPlayerConfig.load();

        FabricDefaultAttributeRegistry.register(AI_PLAYER_ENTITY, AiPlayerEntity.createAttributes());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            AiPlayerCommands.register(dispatcher));
        ServerEventHandler.register();

        aiPlayerManager = new AiPlayerManager();
        ServerTickEvents.END_WORLD_TICK.register(level -> aiPlayerManager.tick(level));

        info("system", "AiPlayer Fabric initialized");
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    public static void info(String category, String template, Object... args) {
        logWithCategory(category, () -> LOGGER.info(template, args));
    }

    public static void debug(String category, String template, Object... args) {
        logWithCategory(category, () -> LOGGER.debug(template, args));
    }

    public static void warn(String category, String template, Object... args) {
        logWithCategory(category, () -> LOGGER.warn(template, args));
    }

    public static void error(String category, String template, Object... args) {
        logWithCategory(category, () -> LOGGER.error(template, args));
    }

    private static void logWithCategory(String category, LogCall logCall) {
        String previousCategory = ThreadContext.get(CategorizedLogWriter.CATEGORY_CONTEXT_KEY);
        ThreadContext.put(CategorizedLogWriter.CATEGORY_CONTEXT_KEY, category);
        try {
            logCall.log();
        } finally {
            if (previousCategory == null) {
                ThreadContext.remove(CategorizedLogWriter.CATEGORY_CONTEXT_KEY);
            } else {
                ThreadContext.put(CategorizedLogWriter.CATEGORY_CONTEXT_KEY, previousCategory);
            }
        }
    }

    public static AiPlayerManager getAiPlayerManager() {
        return aiPlayerManager;
    }

    @FunctionalInterface
    private interface LogCall {
        void log();
    }
}
