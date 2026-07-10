package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.auth.BotAuthorizationGate;
import io.github.zoyluo.aibot.auth.BotAuthorizationPolicy;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemory;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.runtime.IntentController;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.MoveTask;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Arrays;
import java.util.Optional;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotMemorySubcommand {
    private AIBotMemorySubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("memory")
                .then(argument("name", StringArgumentType.word())
                        .then(literal("remember")
                                .then(argument("key", StringArgumentType.word())
                                        .then(argument("value", StringArgumentType.greedyString())
                                                .executes(context -> remember(context.getSource(),
                                                        StringArgumentType.getString(context, "name"),
                                                        StringArgumentType.getString(context, "key"),
                                                        StringArgumentType.getString(context, "value"))))))
                        .then(literal("recall")
                                .then(argument("key", StringArgumentType.word())
                                        .executes(context -> recall(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "key")))))
                        .then(literal("forget")
                                .then(argument("key", StringArgumentType.word())
                                        .executes(context -> forget(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "key")))))
                        .then(literal("mark_place")
                                .then(argument("place", StringArgumentType.word())
                                        .executes(context -> markPlace(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "place")))))
                        .then(literal("set_base")
                                .executes(context -> markPlace(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        "base")))
                        .then(literal("goto_place")
                                .then(argument("place", StringArgumentType.word())
                                        .executes(context -> gotoPlace(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "place")))))
                        .then(literal("set_goal")
                                .then(argument("title", StringArgumentType.word())
                                        .then(argument("steps", StringArgumentType.greedyString())
                                                .executes(context -> setGoal(context.getSource(),
                                                        StringArgumentType.getString(context, "name"),
                                                        StringArgumentType.getString(context, "title"),
                                                        StringArgumentType.getString(context, "steps"))))))
                        .then(literal("advance_goal")
                                .executes(context -> advanceGoal(context.getSource(),
                                        StringArgumentType.getString(context, "name"),
                                        ""))
                                .then(argument("result", StringArgumentType.greedyString())
                                        .executes(context -> advanceGoal(context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                StringArgumentType.getString(context, "result")))))
                        .then(literal("goal_status")
                                .executes(context -> goalStatus(context.getSource(), StringArgumentType.getString(context, "name"))))
                        .then(literal("inject")
                                .executes(context -> inject(context.getSource(), StringArgumentType.getString(context, "name")))));
    }

    private static int remember(ServerCommandSource source, String name, String key, String value) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "remember");
        if (bot.isEmpty()) {
            return 0;
        }
        memory(bot.get()).remember(key, value);
        source.sendFeedback(() -> Text.literal("[AIBot] remembered " + key), false);
        return 1;
    }

    private static int recall(ServerCommandSource source, String name, String key) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.VIEW, "recall");
        if (bot.isEmpty()) {
            return 0;
        }
        String value = memory(bot.get()).recall(key).orElse("<missing>");
        source.sendFeedback(() -> Text.literal("[AIBot] " + key + " = " + value), false);
        return 1;
    }

    private static int forget(ServerCommandSource source, String name, String key) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "forget");
        if (bot.isEmpty()) {
            return 0;
        }
        boolean removed = memory(bot.get()).forget(key);
        source.sendFeedback(() -> Text.literal("[AIBot] forget " + key + " " + removed), false);
        return removed ? 1 : 0;
    }

    private static int markPlace(ServerCommandSource source, String name, String place) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "mark_place");
        if (bot.isEmpty()) {
            return 0;
        }
        memory(bot.get()).markPlace(place, bot.get().getServerWorld(), bot.get().getBlockPos());
        source.sendFeedback(() -> Text.literal("[AIBot] marked place " + place + " at " + bot.get().getBlockPos().toShortString()), false);
        return 1;
    }

    private static int gotoPlace(ServerCommandSource source, String name, String place) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "goto_place");
        if (bot.isEmpty()) {
            return 0;
        }
        Optional<BotMemory.Place> target = memory(bot.get()).place(place);
        if (target.isEmpty()) {
            source.sendError(Text.literal("[AIBot] unknown place: " + place));
            return 0;
        }
        if (!bot.get().getServerWorld().getRegistryKey().getValue().toString().equals(target.get().dimension())) {
            source.sendError(Text.literal("[AIBot] place is in another dimension: " + target.get().dimension()));
            return 0;
        }
        MoveTask task = new MoveTask(bot.get(), target.get().pos());
        IntentController.INSTANCE.replace(
                bot.get(),
                IntentController.ControlOrigin.PLAYER_COMMAND,
                "command_memory_goto:" + place,
                () -> {
                    io.github.zoyluo.aibot.task.TaskManager.INSTANCE.assign(bot.get(), task,
                            TaskOrigin.of(TaskOrigin.Kind.PLAYER_COMMAND, "command_memory_goto"));
                    return true;
                });
        source.sendFeedback(() -> Text.literal("[AIBot] moving to " + place), false);
        return 1;
    }

    private static int setGoal(ServerCommandSource source, String name, String title, String steps) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "set_goal");
        if (bot.isEmpty()) {
            return 0;
        }
        memory(bot.get()).setGoal(title, Arrays.asList(steps.split("\\|")));
        source.sendFeedback(() -> Text.literal("[AIBot] " + memory(bot.get()).goalStatus("")), false);
        return 1;
    }

    private static int advanceGoal(ServerCommandSource source, String name, String result) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.COMMAND, "advance_goal");
        if (bot.isEmpty()) {
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[AIBot] " + memory(bot.get()).advanceGoal(result)), false);
        return 1;
    }

    private static int goalStatus(ServerCommandSource source, String name) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.VIEW, "goal_status");
        if (bot.isEmpty()) {
            return 0;
        }
        source.sendFeedback(() -> Text.literal("[AIBot] " + memory(bot.get()).goalStatus("")), false);
        return 1;
    }

    private static int inject(ServerCommandSource source, String name) {
        Optional<AIPlayerEntity> bot = bot(source, name, BotAuthorizationPolicy.Operation.VIEW, "inject");
        if (bot.isEmpty()) {
            return 0;
        }
        String text = memory(bot.get()).inject();
        source.sendFeedback(() -> Text.literal("[AIBot] memory inject: " + (text.isBlank() ? "<empty>" : text)), false);
        return 1;
    }

    private static Optional<AIPlayerEntity> bot(ServerCommandSource source,
                                                String name,
                                                BotAuthorizationPolicy.Operation operation,
                                                String action) {
        return BotAuthorizationGate.INSTANCE.resolveAuthorized(
                source, name, operation, "command:memory_" + action);
    }

    private static BotMemory memory(AIPlayerEntity bot) {
        return BotMemoryStore.INSTANCE.of(bot.getUuid());
    }
}
