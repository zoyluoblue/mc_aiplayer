package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.camera.CameraMirror;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.Collections;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotCameraSubcommand {
    private AIBotCameraSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("camera")
                .then(literal("bind")
                        .then(argument("camera", EntityArgumentType.player())
                                .then(argument("bot", StringArgumentType.word())
                                        .executes(context -> bind(
                                                context.getSource(),
                                                EntityArgumentType.getPlayer(context, "camera"),
                                                StringArgumentType.getString(context, "bot"))))))
                .then(literal("release")
                        .then(argument("camera", EntityArgumentType.player())
                                .executes(context -> release(
                                        context.getSource(),
                                        EntityArgumentType.getPlayer(context, "camera")))))
                .then(literal("status")
                        .then(argument("camera", EntityArgumentType.player())
                                .executes(context -> status(
                                        context.getSource(),
                                        EntityArgumentType.getPlayer(context, "camera")))));
    }

    private static int bind(ServerCommandSource source, ServerPlayerEntity camera, String botName) {
        if (camera instanceof AIPlayerEntity) {
            source.sendError(Text.literal("[AIBot] camera must be a real spectator player, not an AI bot"));
            return 0;
        }
        if (camera.interactionManager.getGameMode() != GameMode.SPECTATOR) {
            source.sendError(Text.literal("[AIBot] camera player must already be in spectator mode"));
            return 0;
        }

        Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByName(botName);
        if (bot.isEmpty()) {
            source.sendError(Text.literal("[AIBot] No such bot: " + botName));
            return 0;
        }

        camera.teleport(bot.get().getServerWorld(),
                bot.get().getX(),
                bot.get().getY(),
                bot.get().getZ(),
                Collections.emptySet(),
                bot.get().getYaw(),
                bot.get().getPitch(),
                true);
        camera.setCameraEntity(bot.get());
        CameraMirror.INSTANCE.bind(camera, bot.get().getGameProfile().getName());
        source.sendFeedback(() -> Text.literal("[AIBot] camera "
                + camera.getGameProfile().getName()
                + " now spectates "
                + bot.get().getGameProfile().getName()
                + " (hand mirror on, auto-rebind on respawn)"), true);
        return 1;
    }

    private static int release(ServerCommandSource source, ServerPlayerEntity camera) {
        CameraMirror.INSTANCE.release(camera);
        camera.setCameraEntity(camera);
        source.sendFeedback(() -> Text.literal("[AIBot] camera "
                + camera.getGameProfile().getName()
                + " released"), true);
        return 1;
    }

    private static int status(ServerCommandSource source, ServerPlayerEntity camera) {
        Entity target = camera.getCameraEntity();
        String targetName = target == null || target == camera
                ? "self"
                : target.getName().getString();
        String mirror = CameraMirror.INSTANCE.boundBot(camera).orElse("none");
        source.sendFeedback(() -> Text.literal("[AIBot] camera "
                + camera.getGameProfile().getName()
                + " target="
                + targetName
                + ", mode="
                + camera.interactionManager.getGameMode().getName()
                + ", mirror="
                + mirror), false);
        return 1;
    }
}
