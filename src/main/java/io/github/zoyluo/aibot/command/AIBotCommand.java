package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.util.UUID;
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
                                .executes(context -> spawn(context.getSource(), StringArgumentType.getString(context, "name"), "worker"))
                                .then(argument("role", StringArgumentType.word())
                                        .executes(context -> spawn(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "role"))))))
                .then(literal("role")
                        .then(argument("name", StringArgumentType.word())
                                .then(argument("role", StringArgumentType.word())
                                        .executes(context -> role(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "role"))))))
                .then(literal("despawn")
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> despawn(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("list")
                        .executes(context -> list(context.getSource())))
                .then(AIBotBrainSubcommand.build())
                .then(AIBotLogSubcommand.build())
                .then(AIBotPersistSubcommand.build())
                .then(AIBotJobSubcommand.build())
                .then(AIBotMemorySubcommand.build())
                .then(AIBotObserveSubcommand.profile())
                .then(AIBotObserveSubcommand.replay())
                .then(AIBotObserveSubcommand.tps())
                .then(AIBotTestSubcommand.build(registryAccess))
                .then(AIBotTaskSubcommand.build())
                .then(AIBotVerifySubcommand.build())
                .then(AIBotDeplintSubcommand.build()));
    }

    private static int spawn(ServerCommandSource source, String name, String role) {
        ServerPlayerEntity executor = source.getPlayer();
        GameMode gameMode = executor == null ? GameMode.SURVIVAL : executor.interactionManager.getGameMode();
        UUID ownerUuid = executor == null ? null : executor.getUuid();
        if (ownerUuid != null && AIPlayerManager.INSTANCE.botOf(ownerUuid).isPresent()) {
            source.sendError(Text.literal("[AIBot] 你已经有一个 AI 助手了,请先 /aibot despawn <名字>"));
            return 0;
        }
        var rotation = source.getRotation();
        var spawned = AIPlayerManager.INSTANCE.spawn(
                source.getServer(),
                name,
                source.getWorld(),
                source.getPosition(),
                rotation.y,
                rotation.x,
                gameMode,
                ownerUuid);

        if (spawned.isPresent()) {
            AIPlayerManager.INSTANCE.setRole(spawned.get(), role);
            source.sendFeedback(() -> Text.literal("[AIBot] Spawned " + name + " role=" + AIPlayerManager.INSTANCE.role(spawned.get())), true);
            return 1;
        }

        source.sendError(Text.literal("[AIBot] 无法生成 " + name + " (名称已存在或已达到限制)"));
        return 0;
    }

    private static int role(ServerCommandSource source, String name, String role) {
        var bot = AIPlayerManager.INSTANCE.getByName(name);
        if (bot.isEmpty()) {
            source.sendError(Text.literal("[AIBot] No such bot: " + name));
            return 0;
        }
        AIPlayerManager.INSTANCE.setRole(bot.get(), role);
        source.sendFeedback(() -> Text.literal("[AIBot] " + name + " role=" + AIPlayerManager.INSTANCE.role(bot.get())), false);
        return 1;
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
                .map(player -> player.getGameProfile().getName() + "(" + AIPlayerManager.INSTANCE.role(player) + ")")
                .collect(Collectors.joining(", "));
        source.sendFeedback(() -> Text.literal("[AIBot] " + bots.size() + " bot(s): " + names), false);
        return bots.size();
    }
}
