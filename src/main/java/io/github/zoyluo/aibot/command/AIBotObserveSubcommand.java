package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.observe.BotProfiler;
import io.github.zoyluo.aibot.observe.ReplayRecorder;
import io.github.zoyluo.aibot.observe.TpsGuard;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotObserveSubcommand {
    private AIBotObserveSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> profile() {
        return literal("profile")
                .then(botName().executes(context -> profile(
                        context.getSource(),
                        StringArgumentType.getString(context, "name"))));
    }

    public static LiteralArgumentBuilder<ServerCommandSource> replay() {
        return literal("replay")
                .then(botName()
                        .executes(context -> replay(
                                context.getSource(),
                                StringArgumentType.getString(context, "name"),
                                10))
                        .then(argument("count", IntegerArgumentType.integer(1, 50))
                                .executes(context -> replay(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        IntegerArgumentType.getInteger(context, "count")))));
    }

    public static LiteralArgumentBuilder<ServerCommandSource> tps() {
        return literal("tps")
                .executes(context -> tps(context.getSource()));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int profile(ServerCommandSource source, String name) {
        Optional<AIPlayerEntity> bot = getBot(source, name);
        if (bot.isEmpty()) {
            return 0;
        }
        Map<String, BotProfiler.Stat> stats = BotProfiler.INSTANCE.snapshot(bot.get().getUuid());
        if (stats.isEmpty()) {
            source.sendFeedback(() -> Text.literal("[AIBot] profile " + name + ": <empty>"), false);
            return 1;
        }
        StringBuilder builder = new StringBuilder("[AIBot] profile ").append(name).append(":");
        for (Map.Entry<String, BotProfiler.Stat> entry : stats.entrySet()) {
            BotProfiler.Stat stat = entry.getValue();
            builder.append("\n- ").append(entry.getKey())
                    .append(" count=").append(stat.count())
                    .append(" avg=").append(format(stat.avgMs())).append("ms")
                    .append(" p95=").append(format(stat.p95Ms())).append("ms")
                    .append(" max=").append(format(stat.maxMs())).append("ms");
        }
        source.sendFeedback(() -> Text.literal(builder.toString()), false);
        return 1;
    }

    private static int replay(ServerCommandSource source, String name, int count) {
        Optional<AIPlayerEntity> bot = getBot(source, name);
        if (bot.isEmpty()) {
            return 0;
        }
        List<ReplayRecorder.ReplayEvent> events = ReplayRecorder.INSTANCE.tail(bot.get().getUuid(), count);
        if (events.isEmpty()) {
            source.sendFeedback(() -> Text.literal("[AIBot] replay " + name + ": <empty>"), false);
            return 1;
        }
        StringBuilder builder = new StringBuilder("[AIBot] replay ").append(name).append(" last ").append(events.size()).append(":");
        for (ReplayRecorder.ReplayEvent event : events) {
            builder.append("\n- ").append(event.summary());
        }
        source.sendFeedback(() -> Text.literal(builder.toString()), false);
        return 1;
    }

    private static int tps(ServerCommandSource source) {
        if (!BotAuthorizationGate.INSTANCE.requireGlobalAdmin(source, "command:tps")) {
            return 0;
        }
        TpsGuard.Snapshot snapshot = TpsGuard.INSTANCE.snapshot(source.getServer());
        source.sendFeedback(() -> Text.literal("[AIBot] tps estimated="
                + format(snapshot.estimatedTps())
                + " avg_tick_ms=" + format(snapshot.averageTickMs())
                + " degraded=" + snapshot.degraded()
                + " continuation_delay_s=" + snapshot.continuationDelaySeconds()
                + " scan_interval=" + snapshot.scanInterval()), false);
        return 1;
    }

    private static Optional<AIPlayerEntity> getBot(ServerCommandSource source, String name) {
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, BotAuthorizationPolicy.Operation.VIEW, "command:observe");
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }
}
