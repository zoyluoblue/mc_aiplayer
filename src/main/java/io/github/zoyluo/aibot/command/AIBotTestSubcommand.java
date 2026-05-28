package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.MovementAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.command.argument.ItemStackArgument;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotTestSubcommand {
    private AIBotTestSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build(CommandRegistryAccess registryAccess) {
        return literal("test")
                .then(literal("look")
                        .then(botName()
                                .then(argument("x", DoubleArgumentType.doubleArg())
                                        .then(argument("y", DoubleArgumentType.doubleArg())
                                                .then(argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(AIBotTestSubcommand::look))))))
                .then(literal("moveto")
                        .then(botName()
                                .then(argument("x", DoubleArgumentType.doubleArg())
                                        .then(argument("y", DoubleArgumentType.doubleArg())
                                                .then(argument("z", DoubleArgumentType.doubleArg())
                                                        .executes(AIBotTestSubcommand::moveTo))))))
                .then(literal("stop")
                        .then(botName()
                                .executes(AIBotTestSubcommand::stop)))
                .then(literal("jump")
                        .then(botName()
                                .executes(AIBotTestSubcommand::jump)))
                .then(literal("mine")
                        .then(botName()
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(AIBotTestSubcommand::mine))))))
                .then(literal("place")
                        .then(botName()
                                .then(argument("x", IntegerArgumentType.integer())
                                        .then(argument("y", IntegerArgumentType.integer())
                                                .then(argument("z", IntegerArgumentType.integer())
                                                        .executes(AIBotTestSubcommand::place))))))
                .then(literal("give")
                        .then(botName()
                                .then(argument("item", ItemStackArgumentType.itemStack(registryAccess))
                                        .executes(context -> give(context, 64))
                                        .then(argument("count", IntegerArgumentType.integer(1, 64))
                                                .executes(context -> give(context, IntegerArgumentType.getInteger(context, "count")))))))
                .then(literal("select")
                        .then(botName()
                                .then(argument("slot", IntegerArgumentType.integer(0, 8))
                                        .executes(AIBotTestSubcommand::select))))
                .then(literal("inventory")
                        .then(botName()
                                .executes(AIBotTestSubcommand::inventory)))
                .then(literal("attack")
                        .then(botName()
                                .then(argument("target", EntityArgumentType.entity())
                                        .executes(AIBotTestSubcommand::attack))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<ServerCommandSource, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static int look(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Vec3d target = getVec3d(context);
        LookAction.lookAt(bot.get(), target);
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] look started"), false);
        return 1;
    }

    private static int moveTo(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Vec3d target = getVec3d(context);
        MovementAction.startWalkTo(bot.get(), target);
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] moveto started"), false);
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        MovementAction.stopAll(bot.get());
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] stopped"), false);
        return 1;
    }

    private static int jump(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        MovementAction.jumpOnce(bot.get());
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] jump queued"), false);
        return 1;
    }

    private static int mine(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        AIPlayerEntity player = bot.get();
        BlockPos pos = getBlockPos(context);
        MiningAction.startMining(player, pos, faceFromPlayer(player, pos));
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] mine started"), false);
        return 1;
    }

    private static int place(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        ActionResult result = BuildAction.placeBlockAt(bot.get(), getBlockPos(context));
        return sendResult(context.getSource(), "place", result);
    }

    private static int give(CommandContext<ServerCommandSource> context, int count) throws CommandSyntaxException {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        ItemStackArgument itemArgument = ItemStackArgumentType.getItemStackArgument(context, "item");
        ItemStack stack = itemArgument.createStack(count, false);
        return sendResult(context.getSource(), "give", InventoryAction.giveItem(bot.get(), stack));
    }

    private static int select(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        int slot = IntegerArgumentType.getInteger(context, "slot");
        return sendResult(context.getSource(), "select", InventoryAction.selectHotbar(bot.get(), slot));
    }

    private static int inventory(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Map<String, Integer> summary = InventoryAction.summarize(bot.get());
        String text = summary.isEmpty()
                ? "(empty)"
                : summary.entrySet().stream()
                .map(entry -> entry.getKey() + " x " + entry.getValue())
                .collect(Collectors.joining(", "));
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] inventory: " + text), false);
        return summary.size();
    }

    private static int attack(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        Entity target = EntityArgumentType.getEntity(context, "target");
        return sendResult(context.getSource(), "attack", InteractAction.attackEntity(bot.get(), target));
    }

    private static Optional<AIPlayerEntity> getBot(CommandContext<ServerCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByName(name);
        if (bot.isEmpty()) {
            context.getSource().sendError(Text.literal("[AIBot] No such bot: " + name));
        }
        return bot;
    }

    private static int sendResult(ServerCommandSource source, String action, ActionResult result) {
        if (result.isSuccess() || result.isInProgress()) {
            source.sendFeedback(() -> Text.literal("[AIBot] " + action + " " + result.status().name().toLowerCase()), false);
            return 1;
        }
        source.sendError(Text.literal("[AIBot] " + action + " failed: " + result.reason()));
        return 0;
    }

    private static Vec3d getVec3d(CommandContext<ServerCommandSource> context) {
        return new Vec3d(
                DoubleArgumentType.getDouble(context, "x"),
                DoubleArgumentType.getDouble(context, "y"),
                DoubleArgumentType.getDouble(context, "z"));
    }

    private static BlockPos getBlockPos(CommandContext<ServerCommandSource> context) {
        return new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    private static Direction faceFromPlayer(AIPlayerEntity player, BlockPos pos) {
        Vec3d fromBlockToEye = player.getEyePos().subtract(pos.toCenterPos());
        return Direction.getFacing(fromBlockToEye);
    }
}
