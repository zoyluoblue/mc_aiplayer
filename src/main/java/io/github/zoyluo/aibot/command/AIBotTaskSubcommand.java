package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.task.BreedTask;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.CombatTask;
import io.github.zoyluo.aibot.task.ContainerTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.EatTask;
import io.github.zoyluo.aibot.task.FarmTask;
import net.minecraft.item.Items;
import io.github.zoyluo.aibot.task.GatherQuotaTask;
import io.github.zoyluo.aibot.task.LightAreaTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.SleepTask;
import io.github.zoyluo.aibot.task.SmeltTask;
import io.github.zoyluo.aibot.task.StockpileTask;
import io.github.zoyluo.aibot.task.OreDigTask;
import io.github.zoyluo.aibot.task.StripMineTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.block.Block;
import net.minecraft.command.argument.IdentifierArgumentType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotTaskSubcommand {
    private AIBotTaskSubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("task")
                .then(literal("assign")
                        .then(botName()
                                .then(literal("move")
                                        .then(blockPosArgs(AIBotTaskSubcommand::assignMove)))
                                .then(literal("forage")
                                        .executes(context -> assignForage(context, 4))
                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                .executes(context -> assignForage(context, IntegerArgumentType.getInteger(context, "count")))))
                                .then(literal("attack")
                                        .then(argument("entity_type", IdentifierArgumentType.identifier())
                                                .executes(context -> assignAttack(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignAttack(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("mine")
                                        .then(argument("block", IdentifierArgumentType.identifier())
                                                .executes(context -> assignMine(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignMine(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("gather")
                                        .then(argument("item", IdentifierArgumentType.identifier())
                                                .executes(context -> assignGather(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignGather(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("strip_mine")
                                        .then(argument("direction", StringArgumentType.word())
                                                .executes(context -> assignStripMine(context, 16, 4, null))
                                                .then(argument("length", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignStripMine(context, IntegerArgumentType.getInteger(context, "length"), 4, null))
                                                        .then(argument("spacing", IntegerArgumentType.integer(0))
                                                                .executes(context -> assignStripMine(context,
                                                                        IntegerArgumentType.getInteger(context, "length"),
                                                                        IntegerArgumentType.getInteger(context, "spacing"),
                                                                        null))
                                                                .then(literal("depot")
                                                                        .then(blockPosArgs(context -> assignStripMine(context,
                                                                                IntegerArgumentType.getInteger(context, "length"),
                                                                                IntegerArgumentType.getInteger(context, "spacing"),
                                                                                getBlockPos(context)))))))))
                                .then(literal("mine_vein")
                                        .executes(context -> assignMineVein(context, null))
                                        .then(argument("ore", IdentifierArgumentType.identifier())
                                                .executes(context -> assignMineVein(context, Registries.BLOCK.get(IdentifierArgumentType.getIdentifier(context, "ore"))))))
                                .then(literal("craft")
                                        .then(argument("item", IdentifierArgumentType.identifier())
                                                .executes(context -> assignCraft(context, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignCraft(context, IntegerArgumentType.getInteger(context, "count"))))))
                                .then(literal("eat")
                                        .executes(AIBotTaskSubcommand::assignEat))
                                .then(literal("sleep")
                                        .executes(AIBotTaskSubcommand::assignSleep))
                                .then(literal("light_area")
                                        .executes(context -> assignLightArea(context, 8, 8))
                                        .then(argument("radius", IntegerArgumentType.integer(2))
                                                .executes(context -> assignLightArea(context, IntegerArgumentType.getInteger(context, "radius"), 8))
                                                .then(argument("max_torches", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignLightArea(context,
                                                                IntegerArgumentType.getInteger(context, "radius"),
                                                                IntegerArgumentType.getInteger(context, "max_torches"))))))
                                .then(literal("farm")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .then(argument("radius", IntegerArgumentType.integer(1))
                                                                        .then(argument("crop", IdentifierArgumentType.identifier())
                                                                                .executes(context -> assignFarm(context, IntegerArgumentType.getInteger(context, "radius"), false))
                                                                                .then(literal("keep_tending")
                                                                                        .executes(context -> assignFarm(context, IntegerArgumentType.getInteger(context, "radius"), true)))))))))
                                .then(literal("harvest")
                                        .then(argument("x", IntegerArgumentType.integer())
                                                .then(argument("y", IntegerArgumentType.integer())
                                                        .then(argument("z", IntegerArgumentType.integer())
                                                                .then(argument("radius", IntegerArgumentType.integer(1))
                                                                        .then(argument("crop", IdentifierArgumentType.identifier())
                                                                                .executes(context -> assignHarvest(context, IntegerArgumentType.getInteger(context, "radius")))))))))
                                .then(literal("breed")
                                        .then(argument("entity_type", IdentifierArgumentType.identifier())
                                                .executes(context -> assignBreed(context, 1))
                                                .then(argument("pairs", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignBreed(context, IntegerArgumentType.getInteger(context, "pairs"))))))
                                .then(literal("smelt")
                                        .then(argument("input_item", IdentifierArgumentType.identifier())
                                                .then(argument("output_item", IdentifierArgumentType.identifier())
                                                        .executes(context -> assignSmelt(context, 1))
                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                .executes(context -> assignSmelt(context, IntegerArgumentType.getInteger(context, "count")))))))
                                .then(literal("deposit")
                                        .executes(context -> assignDeposit(context, null, 0, false, null))
                                        .then(literal("all_except_tools")
                                                .executes(context -> assignDeposit(context, null, 0, true, null)))
                                        .then(literal("item")
                                                .then(argument("item", IdentifierArgumentType.identifier())
                                                        .executes(context -> assignDeposit(context, requiredItem(context, "item"), 0, false, null))
                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                .executes(context -> assignDeposit(context, requiredItem(context, "item"), IntegerArgumentType.getInteger(context, "count"), false, null)))))
                                        .then(literal("at")
                                                .then(blockPosArgs(context -> assignDepositAt(context, null, 0, false)))
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .then(literal("all_except_tools")
                                                                                .executes(context -> assignDeposit(context, null, 0, true, getBlockPos(context))))
                                                                        .then(literal("item")
                                                                                .then(argument("item", IdentifierArgumentType.identifier())
                                                                                        .executes(context -> assignDeposit(context, requiredItem(context, "item"), 0, false, getBlockPos(context)))
                                                                                        .then(argument("count", IntegerArgumentType.integer(1))
                                                                                                .executes(context -> assignDeposit(context, requiredItem(context, "item"), IntegerArgumentType.getInteger(context, "count"), false, getBlockPos(context)))))))))))
                                .then(literal("stockpile")
                                        .executes(context -> assignStockpile(context, true))
                                        .then(literal("include_tools")
                                                .executes(context -> assignStockpile(context, false))))
                                .then(literal("withdraw")
                                        .then(argument("item", IdentifierArgumentType.identifier())
                                                .executes(context -> assignWithdraw(context, null, 1))
                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> assignWithdraw(context, null, IntegerArgumentType.getInteger(context, "count")))))
                                        .then(literal("at")
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .then(argument("item", IdentifierArgumentType.identifier())
                                                                                .executes(context -> assignWithdraw(context, getBlockPos(context), 1))
                                                                                .then(argument("count", IntegerArgumentType.integer(1))
                                                                                        .executes(context -> assignWithdraw(context, getBlockPos(context), IntegerArgumentType.getInteger(context, "count"))))))))))
                                .then(literal("build")
                                        .then(argument("blueprint", StringArgumentType.word())
                                                .then(argument("x", IntegerArgumentType.integer())
                                                        .then(argument("y", IntegerArgumentType.integer())
                                                                .then(argument("z", IntegerArgumentType.integer())
                                                                        .executes(context -> assignBuild(context, false, false))
                                                                        .then(literal("flatten")
                                                                                .executes(context -> assignBuild(context, false, true))))))
                                                .then(literal("auto_site")
                                                        .executes(context -> assignBuild(context, true, false))
                                                        .then(literal("flatten")
                                                                .executes(context -> assignBuild(context, true, true))))))))
                .then(literal("status")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::status)))
                .then(literal("abort")
                        .then(botName()
                                .executes(AIBotTaskSubcommand::abort)));
    }

    private static RequiredArgumentBuilder<ServerCommandSource, String> botName() {
        return argument("name", StringArgumentType.word());
    }

    private static RequiredArgumentBuilder<ServerCommandSource, Integer> blockPosArgs(Command<ServerCommandSource> command) {
        return argument("x", IntegerArgumentType.integer())
                .then(argument("y", IntegerArgumentType.integer())
                        .then(argument("z", IntegerArgumentType.integer())
                                .executes(command)));
    }

    private static int assignMove(CommandContext<ServerCommandSource> context) {
        return assign(context, bot -> new MoveTask(bot, getBlockPos(context)));
    }

    private static int assignForage(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> new GatherQuotaTask(Items.SWEET_BERRIES, count));
    }

    private static int assignAttack(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> new CombatTask(
                Registries.ENTITY_TYPE.get(IdentifierArgumentType.getIdentifier(context, "entity_type")),
                count,
                io.github.zoyluo.aibot.AIBotConfig.get().combat().retreatHp()));
    }

    private static int assignMine(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> {
            Block block = Registries.BLOCK.get(IdentifierArgumentType.getIdentifier(context, "block"));
            return OreScan.isOreBlock(block) ? new OreDigTask(OreScan.oreFamily(block), count) : new MineTask(block, count);
        });
    }

    private static int assignGather(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> new GatherQuotaTask(
                Registries.ITEM.get(IdentifierArgumentType.getIdentifier(context, "item")),
                count));
    }

    private static int assignStripMine(CommandContext<ServerCommandSource> context, int length, int spacing, BlockPos depot) {
        return assign(context, bot -> new StripMineTask(direction(context), length, spacing, depot, Set.of()));
    }

    private static int assignMineVein(CommandContext<ServerCommandSource> context, Block ore) {
        return assign(context, bot -> StripMineTask.mineNearbyVein(ore == null ? Set.of() : Set.of(ore)));
    }

    private static int assignCraft(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> new CraftTask(requiredItem(context, "item"), count));
    }

    private static int assignEat(CommandContext<ServerCommandSource> context) {
        return assign(context, bot -> new EatTask());
    }

    private static int assignSleep(CommandContext<ServerCommandSource> context) {
        return assign(context, bot -> new SleepTask());
    }

    private static int assignLightArea(CommandContext<ServerCommandSource> context, int radius, int maxTorches) {
        return assign(context, bot -> new LightAreaTask(radius, maxTorches));
    }

    private static int assignFarm(CommandContext<ServerCommandSource> context, int radius, boolean keepTending) {
        return assign(context, bot -> {
            FarmAction.CropSpec spec = cropSpec(context);
            return new FarmTask(getBlockPos(context), radius, spec.seed(), spec.crop(), keepTending, false);
        });
    }

    private static int assignHarvest(CommandContext<ServerCommandSource> context, int radius) {
        return assign(context, bot -> {
            FarmAction.CropSpec spec = cropSpec(context);
            return new FarmTask(getBlockPos(context), radius, spec.seed(), spec.crop(), false, true);
        });
    }

    private static int assignBreed(CommandContext<ServerCommandSource> context, int pairs) {
        return assign(context, bot -> new BreedTask(
                Registries.ENTITY_TYPE.get(IdentifierArgumentType.getIdentifier(context, "entity_type")),
                pairs));
    }

    private static int assignSmelt(CommandContext<ServerCommandSource> context, int count) {
        return assign(context, bot -> new SmeltTask(
                requiredItem(context, "input_item"),
                requiredItem(context, "output_item"),
                count));
    }

    private static int assignDepositAt(CommandContext<ServerCommandSource> context, Item item, int count, boolean allExceptTools) {
        return assignDeposit(context, item, count, allExceptTools, getBlockPos(context));
    }

    private static int assignDeposit(CommandContext<ServerCommandSource> context,
                                     Item item,
                                     int count,
                                     boolean allExceptTools,
                                     BlockPos pos) {
        return assign(context, bot -> ContainerTask.deposit(pos, item, count, allExceptTools));
    }

    private static int assignWithdraw(CommandContext<ServerCommandSource> context, BlockPos pos, int count) {
        return assign(context, bot -> ContainerTask.withdraw(pos, requiredItem(context, "item"), count));
    }

    private static int assignStockpile(CommandContext<ServerCommandSource> context, boolean allExceptTools) {
        return assign(context, bot -> new StockpileTask(allExceptTools));
    }

    private static int assignBuild(CommandContext<ServerCommandSource> context, boolean autoSite, boolean flatten) {
        return assign(context, bot -> {
            try {
                return new BuildTask(
                        BlueprintLoader.load(StringArgumentType.getString(context, "blueprint")),
                        autoSite ? null : getBlockPos(context),
                        autoSite,
                        flatten);
            } catch (IOException exception) {
                throw new IllegalArgumentException(exception.getMessage(), exception);
            }
        });
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        TaskStatus status = TaskManager.INSTANCE.status(bot.get());
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] task "
                + status.name()
                + " state=" + status.state()
                + " progress=" + String.format(java.util.Locale.ROOT, "%.2f", status.progress())
                + " elapsed=" + status.elapsedTicks()
                + " desc=" + status.description()
                + (status.failureReason().isBlank() ? "" : " reason=" + status.failureReason())), false);
        return 1;
    }

    private static int abort(CommandContext<ServerCommandSource> context) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        TaskManager.INSTANCE.abort(bot.get());
        context.getSource().sendFeedback(() -> Text.literal("[AIBot] task aborted"), false);
        return 1;
    }

    private static int assign(CommandContext<ServerCommandSource> context, TaskFactory factory) {
        Optional<AIPlayerEntity> bot = getBot(context);
        if (bot.isEmpty()) {
            return 0;
        }
        try {
            Task task = factory.create(bot.get());
            TaskManager.INSTANCE.assign(bot.get(), task);
            context.getSource().sendFeedback(() -> Text.literal("[AIBot] task assigned: " + task.name()), false);
            return 1;
        } catch (RuntimeException exception) {
            context.getSource().sendError(Text.literal("[AIBot] task assign failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static Optional<AIPlayerEntity> getBot(CommandContext<ServerCommandSource> context) {
        String name = StringArgumentType.getString(context, "name");
        Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByName(name);
        if (bot.isEmpty()) {
            context.getSource().sendError(Text.literal("[AIBot] No such bot: " + name));
        }
        return bot;
    }

    private static BlockPos getBlockPos(CommandContext<ServerCommandSource> context) {
        return new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z"));
    }

    private static Item requiredItem(CommandContext<ServerCommandSource> context, String name) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, name);
        return Registries.ITEM.getOptionalValue(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown_item: " + id));
    }

    private static FarmAction.CropSpec cropSpec(CommandContext<ServerCommandSource> context) {
        Identifier id = IdentifierArgumentType.getIdentifier(context, "crop");
        return FarmAction.cropSpec(id.toString());
    }

    private static Direction direction(CommandContext<ServerCommandSource> context) {
        String value = StringArgumentType.getString(context, "direction").toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            case "down", "d", "up", "u" -> Direction.DOWN;
            default -> throw new IllegalArgumentException("unknown_direction: " + value);
        };
    }

    @FunctionalInterface
    private interface TaskFactory {
        Task create(AIPlayerEntity bot);
    }
}
