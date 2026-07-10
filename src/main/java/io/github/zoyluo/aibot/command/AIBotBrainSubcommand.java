package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy;
import io.github.zoyluo.aibot.brain.BrainValidation;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogCategory;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.command.argument.MessageArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
                .then(literal("manual")
                        .then(botName()
                                .then(literal("on")
                                        .executes(context -> manual(context.getSource(), StringArgumentType.getString(context, "name"), true)))
                                .then(literal("off")
                                        .executes(context -> manual(context.getSource(), StringArgumentType.getString(context, "name"), false)))))
                .then(literal("say")
                        .then(botName()
                                .then(argument("text", MessageArgumentType.message())
                                        .executes(context -> say(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                MessageArgumentType.getMessage(context, "text").getString())))))
                .then(literal("validate")
                        .then(botName()
                                .then(literal("api-failure")
                                        .executes(context -> validateApiFailure(context.getSource(), StringArgumentType.getString(context, "name"))))
                                .then(literal("bad-tool-args")
                                        .executes(context -> validateBadToolArgs(context.getSource(), StringArgumentType.getString(context, "name"))))
                                .then(literal("bad-response")
                                        .executes(context -> validateBadResponse(context.getSource(), StringArgumentType.getString(context, "name"))))
                                .then(literal("tps")
                                        .then(argument("seconds", IntegerArgumentType.integer(3, 60))
                                                .then(argument("text", MessageArgumentType.message())
                                                        .executes(context -> validateTps(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "name"),
                                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                                MessageArgumentType.getMessage(context, "text").getString()))))))
                        .then(literal("api-failure")
                                .then(botName()
                                        .executes(context -> validateApiFailure(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(literal("bad-tool-args")
                                .then(botName()
                                        .executes(context -> validateBadToolArgs(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(literal("bad-response")
                                .then(botName()
                                        .executes(context -> validateBadResponse(context.getSource(), StringArgumentType.getString(context, "name")))))
                        .then(literal("tps")
                                .then(botName()
                                        .then(argument("seconds", IntegerArgumentType.integer(3, 60))
                                                .then(argument("text", MessageArgumentType.message())
                                                        .executes(context -> validateTps(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "name"),
                                                                IntegerArgumentType.getInteger(context, "seconds"),
                                                                MessageArgumentType.getMessage(context, "text").getString())))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int status(ServerCommandSource source, String name) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.VIEW, "command:brain_status");
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
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "command:brain_reset");
        if (bot.isEmpty()) {
            return 0;
        }
        RuntimeLifecycleCoordinator.INSTANCE.resetBot(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, "command_brain_reset");
        source.sendFeedback(() -> Text.literal("[AIBot] brain reset " + name), false);
        return 1;
    }

    private static int manual(ServerCommandSource source, String name, boolean enabled) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_manual");
        if (bot.isEmpty()) {
            return 0;
        }
        BrainCoordinator.INSTANCE.setManualMode(bot.get(), enabled);
        source.sendFeedback(() -> Text.literal("[AIBot] manual low-level tools " + (enabled ? "on" : "off") + " for " + name), false);
        return 1;
    }

    private static int say(ServerCommandSource source, String name, String text) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "command:brain_say");
        if (bot.isEmpty()) {
            return 0;
        }
        if (IntentController.INSTANCE.routePlayerControlPhrase(
                bot.get(), IntentController.ControlOrigin.PLAYER_COMMAND, text)) {
            return 1;
        }
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(bot.get(), source.getName(), text);
        if (queued) {
            source.sendFeedback(() -> Text.literal("[AIBot] brain request queued for " + name), false);
            return 1;
        }
        return 0;
    }

    private static int validateApiFailure(ServerCommandSource source, String name) {
        return reportValidation(source, getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate"), BrainValidation::apiFailure);
    }

    private static int validateBadToolArgs(ServerCommandSource source, String name) {
        return reportValidation(source, getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate"), BrainValidation::badToolArgs);
    }

    private static int validateBadResponse(ServerCommandSource source, String name) {
        return reportValidation(source, getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate"), BrainValidation::badResponse);
    }

    private static int validateTps(ServerCommandSource source, String name, int seconds, String text) {
        Optional<AIPlayerEntity> bot = getBot(source, name, BotAuthorizationPolicy.Operation.ADMIN, "command:brain_validate_tps");
        if (bot.isEmpty()) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        long startTicks = server.getTicks();
        long startNanos = System.nanoTime();
        boolean queued = BrainCoordinator.INSTANCE.handleMessage(bot.get(), source.getName(), text);
        CompletableFuture.delayedExecutor(seconds, TimeUnit.SECONDS).execute(() ->
                server.execute(() -> {
                    long elapsedTicks = server.getTicks() - startTicks;
                    double elapsedSeconds = Math.max(0.001D, (System.nanoTime() - startNanos) / 1_000_000_000.0D);
                    double tps = elapsedTicks / elapsedSeconds;
                    boolean ok = tps >= 19.0D;
                    BotLog.raw(LogCategory.API, ok ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN, bot.get(),
                            "tps_validation",
                            null,
                            "queued", queued,
                            "seconds", seconds,
                            "ticks", elapsedTicks,
                            "tps", String.format(java.util.Locale.ROOT, "%.2f", tps));
                    source.sendFeedback(() -> Text.literal("[AIBot] TPS validation "
                            + (ok ? "ok" : "failed")
                            + ": queued=" + queued
                            + ", ticks=" + elapsedTicks
                            + ", tps=" + String.format(java.util.Locale.ROOT, "%.2f", tps)), false);
                }));
        source.sendFeedback(() -> Text.literal("[AIBot] TPS validation started for " + name + " over " + seconds + "s"), false);
        return queued ? 1 : 0;
    }

    private static int reportValidation(ServerCommandSource source,
                                        Optional<AIPlayerEntity> bot,
                                        java.util.function.Function<AIPlayerEntity, BrainValidation.ValidationResult> validator) {
        if (bot.isEmpty()) {
            return 0;
        }
        BrainValidation.ValidationResult result = validator.apply(bot.get());
        if (result.ok()) {
            source.sendFeedback(() -> Text.literal("[AIBot] validation ok: " + result.scenario() + " -> " + result.message()), false);
            return 1;
        }
        source.sendError(Text.literal("[AIBot] validation failed: " + result.scenario() + " -> " + result.message()));
        return 0;
    }

    private static Optional<AIPlayerEntity> getBot(ServerCommandSource source,
                                                   String name,
                                                   BotAuthorizationPolicy.Operation operation,
                                                   String channel) {
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(source, name, operation, channel);
    }
}
