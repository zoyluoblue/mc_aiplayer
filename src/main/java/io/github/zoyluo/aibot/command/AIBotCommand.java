package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotCommand {
    private AIBotCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(literal("aibot")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("spawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("despawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> despawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("list")
                        .executes(context -> list(context.getSource())))
                .then(AIBotBrainSubcommand.build())
                .then(AIBotTestSubcommand.build(registryAccess)));
    }

    private static int spawn(ServerCommandSource source, String name) {
        ServerPlayerEntity executor = source.getPlayer();
        GameMode gameMode = executor == null ? GameMode.SURVIVAL : executor.interactionManager.getGameMode();
        var rotation = source.getRotation();
        var spawned = AIPlayerManager.INSTANCE.spawn(
                source.getServer(),
                name,
                source.getWorld(),
                source.getPosition(),
                rotation.y,
                rotation.x,
                gameMode);

        if (spawned.isPresent()) {
            source.sendFeedback(() -> Text.literal("[AIBot] Spawned " + name), true);
            return 1;
        }

        source.sendError(Text.literal("[AIBot] Failed to spawn " + name + " (already exists?)"));
        return 0;
    }

    private static int despawn(ServerCommandSource source, String name) {
        boolean removed = AIPlayerManager.INSTANCE.despawn(source.getServer(), name);
        if (removed) {
            source.sendFeedback(() -> Text.literal("[AIBot] Despawned " + name), true);
            return 1;
        }

        source.sendError(Text.literal("[AIBot] No such bot: " + name));
        return 0;
    }

    private static int list(ServerCommandSource source) {
        var bots = AIPlayerManager.INSTANCE.all();
        String names = bots.stream()
                .map(player -> player.getGameProfile().getName())
                .collect(Collectors.joining(", "));
        source.sendFeedback(() -> Text.literal("[AIBot] " + bots.size() + " bot(s): " + names), false);
        return bots.size();
    }
}
