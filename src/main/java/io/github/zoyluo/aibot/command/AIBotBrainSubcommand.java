package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.command.argument.MessageArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotBrainSubcommand {
    private AIBotBrainSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("brain")
                .then(literal("status")
                        .then(botName()
                                .executes(context -> status(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("reset")
                        .then(botName()
                                .executes(context -> reset(context.getSource(), StringArgumentType.getString(context, "name")))))
                .then(literal("say")
                        .then(botName()
                                .then(argument("text", MessageArgumentType.message())
                                        .executes(context -> say(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                MessageArgumentType.getMessage(context, "text").getString())))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int status(ServerCommandSource source, String name) {
        Optional<AIPlayerEntity> bot = getBot(source, name);
        if (bot.isEmpty()) {
            return 0;
        }
        BrainCoordinator.BrainStatus status = BrainCoordinator.INSTANCE.status(bot.get());
        source.sendFeedback(() -> Text.literal("[AIBot] brain status " + name
                + ": busy=" + status.busy()
                + ", history=" + status.historySize()
                + ", prompt_tokens=" + status.promptTokens()
                + ", completion_tokens=" + status.completionTokens()
                + ", cache_hit_tokens=" + status.cacheHitTokens()), false);
        return 1;
    }

    private static int reset(ServerCommandSource source, String name) {
        Optional<AIPlayerEntity> bot = getBot(source, name);
        if (bot.isEmpty()) {
            return 0;
        }
        BrainCoordinator.INSTANCE.reset(bot.get());
        source.sendFeedback(() -> Text.literal("[AIBot] brain reset " + name), false);
        return 1;
    }

    private static int say(ServerCommandSource source, String name, String text) {
        Optional<AIPlayerEntity> bot = getBot(source, name);
        if (bot.isEmpty()) {
            return 0;
        }
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(bot.get(), source.getName(), text);
        if (queued) {
            source.sendFeedback(() -> Text.literal("[AIBot] brain request queued for " + name), false);
            return 1;
        }
        return 0;
    }

    private static Optional<AIPlayerEntity> getBot(ServerCommandSource source, String name) {
        Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByName(name);
        if (bot.isEmpty()) {
            source.sendError(Text.literal("[AIBot] No such bot: " + name));
        }
        return bot;
    }
}
