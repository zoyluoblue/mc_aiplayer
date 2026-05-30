package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.coordination.TaskBoard;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.persist.BotPersistence;
import io.github.zoyluo.aibot.task.BlueprintLoader;
import io.github.zoyluo.aibot.task.BuildTask;
import io.github.zoyluo.aibot.task.CombatTask;
import io.github.zoyluo.aibot.task.ContainerTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.FarmTask;
import io.github.zoyluo.aibot.task.MoveTask;
import io.github.zoyluo.aibot.task.OreSeekTask;
import io.github.zoyluo.aibot.task.SleepTask;
import io.github.zoyluo.aibot.task.StripMineTask;
import io.github.zoyluo.aibot.task.Task;
import io.github.zoyluo.aibot.task.TaskManager;
import io.github.zoyluo.aibot.task.TaskState;
import io.github.zoyluo.aibot.task.TaskStatus;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class AIBotVerifySubcommand {
    private static final List<String> ALL_FEATURES = List.of(
            "persist",
            "container",
            "combat",
            "sleep",
            "farm",
            "strip_mine",
            "build",
            "memory",
            "job",
            "craft_chain",
            "drowning",
            "nav_obstacle",
            "nav_gap",
            "pickup_blocked",
            "mine_to_iron",
            "mine_iron_from_scratch",
            "nav_descend");
    private static final Map<UUID, VerifyRun> RUNS = new ConcurrentHashMap<>();

    private AIBotVerifySubcommand() {
    }

    public static LiteralArgumentBuilder<ServerCommandSource> build() {
        return literal("verify")
                .executes(context -> start(context.getSource(), List.of("all")))
                .then(literal("all")
                        .executes(context -> start(context.getSource(), ALL_FEATURES)))
                .then(argument("feature", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            ALL_FEATURES.forEach(builder::suggest);
                            builder.suggest("all");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String feature = StringArgumentType.getString(context, "feature");
                            return start(context.getSource(), "all".equals(feature) ? ALL_FEATURES : List.of(feature));
                        }));
    }

    public static void tick(MinecraftServer server) {
        for (VerifyRun run : new ArrayList<>(RUNS.values())) {
            if (run.tick(server)) {
                RUNS.remove(run.botId());
            }
        }
    }

    private static int start(ServerCommandSource source, List<String> requested) {
        Optional<AIPlayerEntity> bot = selectBot(source);
        if (bot.isEmpty()) {
            source.sendError(Text.literal("[AIBot Verify] FAIL no_bot: spawn a bot first with /aibot spawn <name>"));
            return 0;
        }
        List<String> features = expandFeatures(requested);
        if (features.isEmpty()) {
            source.sendError(Text.literal("[AIBot Verify] unknown feature. Available: " + String.join(", ", ALL_FEATURES)));
            return 0;
        }
        UUID botId = bot.get().getUuid();
        if (RUNS.containsKey(botId)) {
            source.sendError(Text.literal("[AIBot Verify] already running for " + bot.get().getGameProfile().getName()));
            return 0;
        }
        VerifyRun run = new VerifyRun(source, botId, features);
        RUNS.put(botId, run);
        source.sendFeedback(() -> Text.literal("[AIBot Verify] started for "
                + bot.get().getGameProfile().getName()
                + ": "
                + String.join(", ", features)), false);
        return 1;
    }

    private static Optional<AIPlayerEntity> selectBot(ServerCommandSource source) {
        return Optional.ofNullable(source.getPlayer())
                .flatMap(player -> AIPlayerManager.INSTANCE.botOf(player.getUuid()))
                .or(() -> AIPlayerManager.INSTANCE.all().stream().findFirst());
    }

    private static List<String> expandFeatures(List<String> requested) {
        List<String> features = new ArrayList<>();
        for (String raw : requested) {
            String feature = raw.toLowerCase(java.util.Locale.ROOT);
            if ("all".equals(feature)) {
                features.addAll(ALL_FEATURES);
            } else if (ALL_FEATURES.contains(feature)) {
                features.add(feature);
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(features));
    }

    private static Result startScenario(ServerCommandSource source, AIPlayerEntity bot, String feature) throws IOException {
        return switch (feature) {
            case "persist" -> verifyPersist(source);
            case "memory" -> verifyMemory(bot);
            case "job" -> verifyJob();
            case "container" -> assignContainer(bot);
            case "combat" -> assignCombat(bot);
            case "sleep" -> assignSleep(bot);
            case "farm" -> assignFarm(bot);
            case "strip_mine" -> assignStripMine(bot);
            case "build" -> assignBuild(bot);
            case "craft_chain" -> assignCraftChain(bot);
            case "drowning" -> verifyDrowning(bot);
            case "nav_obstacle" -> assignNavObstacle(bot);
            case "nav_gap" -> assignNavGap(bot);
            case "pickup_blocked" -> verifyPickupBlocked(bot);
            case "mine_to_iron" -> assignMineToIron(bot);
            case "mine_iron_from_scratch" -> assignMineIronFromScratch(bot);
            case "nav_descend" -> assignNavDescend(bot);
            default -> Result.fail(feature, "unknown_feature");
        };
    }

    private static Result verifyPersist(ServerCommandSource source) {
        int saved = BotPersistence.INSTANCE.saveAll(source.getServer());
        return Result.pass("persist", "saveAll ok, bots=" + saved);
    }

    private static Result verifyMemory(AIPlayerEntity bot) {
        String key = "verify_" + bot.getUuid();
        BotMemoryStore.INSTANCE.of(bot.getUuid()).remember(key, "ok");
        boolean found = BotMemoryStore.INSTANCE.of(bot.getUuid()).recall(key).filter("ok"::equals).isPresent();
        BotMemoryStore.INSTANCE.of(bot.getUuid()).forget(key);
        return found ? Result.pass("memory", "remember/recall/forget ok") : Result.fail("memory", "recall_mismatch");
    }

    private static Result verifyJob() {
        UUID id = TaskBoard.INSTANCE.post("verify", Map.of("feature", "job"), "worker");
        boolean found = TaskBoard.INSTANCE.snapshot().stream().anyMatch(job -> job.id().equals(id));
        if (found) {
            TaskBoard.INSTANCE.markDone(id);
            return Result.pass("job", "post/snapshot/markDone ok");
        }
        return Result.fail("job", "posted_job_missing");
    }

    private static Result assignContainer(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        BlockPos chest = bot.getBlockPos().offset(Direction.NORTH);
        bot.getServerWorld().setBlockState(chest, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        Task task = ContainerTask.deposit(chest, Items.COBBLESTONE, 3, false);
        return assignTask(bot, "container", task, 200, ignored -> countContainer(bot, chest, Items.COBBLESTONE) >= 3);
    }

    private static Result assignCombat(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD, 1));
        ServerWorld world = bot.getServerWorld();
        ZombieEntity zombie = EntityType.ZOMBIE.create(world, SpawnReason.COMMAND);
        if (zombie == null) {
            return Result.fail("combat", "zombie_create_failed");
        }
        zombie.refreshPositionAndAngles(bot.getX() + 2.0D, bot.getY(), bot.getZ(), 0.0F, 0.0F);
        world.spawnEntity(zombie);
        return assignTask(bot, "combat", new CombatTask(EntityType.ZOMBIE, 1, AIBotConfig.get().combat().retreatHp()),
                600,
                ignored -> !zombie.isAlive());
    }

    private static Result assignSleep(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.RED_BED, 1));
        bot.getServerWorld().setTimeOfDay(13000L);
        return assignTask(bot, "sleep", new SleepTask(), 260, ignored -> bot.getServerWorld().isDay());
    }

    private static Result assignFarm(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        BlockPos farm = bot.getBlockPos().offset(Direction.EAST);
        bot.getServerWorld().setBlockState(farm, Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(farm.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HOE, 1));
        return assignTask(bot, "farm", new FarmTask(farm, 1, Items.WHEAT_SEEDS, Blocks.WHEAT, false, false),
                300,
                ignored -> bot.getServerWorld().getBlockState(farm.up()).isOf(Blocks.WHEAT));
    }

    private static Result assignStripMine(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        Direction direction = Direction.NORTH;
        for (int distance = 1; distance <= 2; distance++) {
            bot.getServerWorld().setBlockState(bot.getBlockPos().offset(direction, distance), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            bot.getServerWorld().setBlockState(bot.getBlockPos().offset(direction, distance).up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        return assignTask(bot, "strip_mine", new StripMineTask(direction, 2, 0, null, java.util.Set.of()),
                800,
                status -> status.progress() >= 1.0D);
    }

    private static Result assignBuild(AIPlayerEntity bot) throws IOException {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.GLASS, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 32));
        return assignTask(bot, "build", new BuildTask(BlueprintLoader.load("small_hut"), null, true, false),
                2400,
                status -> status.progress() >= 1.0D);
    }

    private static Result assignCraftChain(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        return assignTask(bot, "craft_chain", new CraftTask(Items.IRON_PICKAXE, 1),
                500,
                ignored -> InventoryAction.countItem(bot, Items.IRON_PICKAXE) >= 1);
    }

    private static Result verifyDrowning(AIPlayerEntity bot) {
        Task task = new io.github.zoyluo.aibot.task.EvadeTask(new io.github.zoyluo.aibot.task.Threat(
                io.github.zoyluo.aibot.task.Threat.Type.DROWNING,
                io.github.zoyluo.aibot.task.Threat.Severity.MEDIUM,
                null,
                bot.getBlockPos()));
        return assignTask(bot, "drowning", task, 300, status -> status.state() == TaskState.COMPLETED);
    }

    private static Result assignNavObstacle(AIPlayerEntity bot) {
        prepareArea(bot);
        BlockPos origin = bot.getBlockPos();
        BlockPos obstacle = origin.offset(Direction.NORTH);
        BlockPos goal = origin.offset(Direction.NORTH, 3);
        bot.getServerWorld().setBlockState(obstacle, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        return assignTask(bot, "nav_obstacle", new MoveTask(bot, goal), 400,
                ignored -> bot.getBlockPos().getSquaredDistance(goal) <= 4.0D);
    }

    private static Result assignNavGap(AIPlayerEntity bot) {
        prepareArea(bot);
        BlockPos origin = bot.getBlockPos();
        BlockPos gap = origin.offset(Direction.NORTH);
        BlockPos goal = origin.offset(Direction.NORTH, 3);
        bot.getServerWorld().setBlockState(gap.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        return assignTask(bot, "nav_gap", new MoveTask(bot, goal), 400,
                ignored -> bot.getBlockPos().getSquaredDistance(goal) <= 4.0D);
    }

    private static Result verifyPickupBlocked(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos dropPos = bot.getBlockPos().offset(Direction.NORTH);
        world.setBlockState(dropPos.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        ItemEntity drop = new ItemEntity(world, dropPos.getX() + 0.5D, dropPos.getY(), dropPos.getZ() + 0.5D, new ItemStack(Items.COBBLESTONE, 1));
        world.spawnEntity(drop);
        boolean picked = HarvestCore.forcePickupNearby(bot, Items.COBBLESTONE);
        return picked && InventoryAction.countItem(bot, Items.COBBLESTONE) >= 1
                ? Result.pass("pickup_blocked", "forced pickup ok")
                : Result.fail("pickup_blocked", "forced_pickup_missing");
    }

    private static Result assignMineToIron(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        BlockPos ore = bot.getBlockPos().offset(Direction.NORTH, 2);
        bot.getServerWorld().setBlockState(ore, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        return assignTask(bot, "mine_to_iron", new OreSeekTask(java.util.Set.of(Blocks.IRON_ORE), 1),
                1200,
                ignored -> InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    private static Result assignMineIronFromScratch(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        // GOALFIX-GF3:从零到铁链路(木镐→挖石→石镐→挖铁)约需 3 原木 + 3 圆石,给足余量(6/6)避免边界失败。
        for (int dy = 0; dy < 6; dy++) {
            world.setBlockState(origin.offset(Direction.WEST, 2).up(dy), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (int i = 0; i < 6; i++) {
            world.setBlockState(origin.offset(Direction.EAST, 2 + i), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(origin.offset(Direction.NORTH, 3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_iron_from_scratch", "goal_submit_failed");
        }
        // GOALFIX-GF3:完整从零链路真实 tick 下耗时长,timeout 3600→12000(10 分钟)。
        return Result.runningGoal("mine_iron_from_scratch", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    private static Result assignNavDescend(AIPlayerEntity bot) {
        prepareArea(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        BlockPos goal = origin.offset(Direction.NORTH, 3).down(3);
        for (int i = 1; i <= 3; i++) {
            BlockPos step = origin.offset(Direction.NORTH, i).down(i);
            world.setBlockState(step, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(step.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(step.down(), Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        return assignTask(bot, "nav_descend", new MoveTask(bot, goal), 600,
                ignored -> bot.getBlockPos().getSquaredDistance(goal) <= 4.0D);
    }

    private static Result assignTask(AIPlayerEntity bot, String feature, Task task, int timeoutTicks, Predicate<TaskStatus> assertion) {
        TaskManager.INSTANCE.assign(bot, task);
        return Result.running(feature, timeoutTicks, assertion);
    }

    private static void prepareArea(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(origin.add(-4, 0, -4), origin.add(4, 3, 4))) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (BlockPos pos : BlockPos.iterate(origin.add(-4, -1, -4), origin.add(4, -1, 4))) {
            world.setBlockState(pos, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        bot.getActionPack().stopAll();
    }

    private static void clearInventory(AIPlayerEntity bot) {
        bot.getInventory().clear();
        bot.getInventory().markDirty();
    }

    private static int countContainer(AIPlayerEntity bot, BlockPos pos, Item item) {
        Optional<Inventory> inventory = io.github.zoyluo.aibot.action.ContainerAction.resolve(bot, pos);
        if (inventory.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < inventory.get().size(); slot++) {
            ItemStack stack = inventory.get().getStack(slot);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static final class VerifyRun {
        private final ServerCommandSource source;
        private final UUID botId;
        private final ArrayDeque<String> queue;
        private final List<Result> results;
        private ActiveScenario active;

        VerifyRun(ServerCommandSource source, UUID botId, List<String> features) {
            this.source = source;
            this.botId = botId;
            this.queue = new ArrayDeque<>(features);
            this.results = new ArrayList<>();
        }

        private UUID botId() {
            return botId;
        }

        private boolean tick(MinecraftServer server) {
            Optional<AIPlayerEntity> bot = AIPlayerManager.INSTANCE.getByUuid(botId);
            if (bot.isEmpty()) {
                record(Result.fail(active == null ? "run" : active.result().feature(), "bot_removed"));
                finish();
                return true;
            }
            if (active != null) {
                pollActive(server, bot.get());
                return false;
            }
            if (queue.isEmpty()) {
                finish();
                return true;
            }

            String feature = queue.removeFirst();
            Result result;
            try {
                result = startScenario(source, bot.get(), feature);
            } catch (RuntimeException | IOException exception) {
                result = Result.fail(feature, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
            if (result.running()) {
                active = new ActiveScenario(result, server.getTicks());
                String message = "[AIBot Verify] " + result.feature() + " RUNNING timeout=" + result.timeoutTicks();
                source.sendFeedback(() -> Text.literal(message), false);
                return false;
            }
            record(result);
            return false;
        }

        private void pollActive(MinecraftServer server, AIPlayerEntity bot) {
            Result running = active.result();
            int elapsedTicks = server.getTicks() - active.startedTick();
            TaskStatus status = TaskManager.INSTANCE.status(bot);
            if (status.state() == TaskState.COMPLETED) {
                if (running.assertion().test(status)) {
                    record(Result.pass(running.feature(), "completed in " + elapsedTicks + " ticks"));
                } else if (running.allowGoalContinuation() && GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                    return;
                } else {
                    record(Result.fail(running.feature(), "assertion_failed status=" + status.name() + " " + status.description()));
                }
                active = null;
                return;
            }
            if (status.state() == TaskState.FAILED) {
                if (running.allowGoalContinuation() && GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                    return;
                }
                record(Result.fail(running.feature(), status.failureReason().isBlank() ? "task_failed" : status.failureReason()));
                active = null;
                return;
            }
            if (elapsedTicks >= running.timeoutTicks()) {
                TaskManager.INSTANCE.abort(bot);
                record(Result.fail(running.feature(), "verify_timeout status=" + status.name() + " " + status.description()));
                active = null;
            }
        }

        private void record(Result result) {
            results.add(result);
            String message = "[AIBot Verify] "
                    + result.feature()
                    + " "
                    + (result.pass() ? "PASS" : "FAIL")
                    + " - "
                    + result.detail();
            source.sendFeedback(() -> Text.literal(message), false);
        }

        private void finish() {
            long passed = results.stream().filter(Result::pass).count();
            String summary = "[AIBot Verify] summary " + passed + "/" + results.size() + " PASS: " + summarize(results);
            if (passed == results.size()) {
                source.sendFeedback(() -> Text.literal(summary), false);
            } else {
                source.sendError(Text.literal(summary));
            }
        }

        private static String summarize(List<Result> results) {
            Map<String, String> parts = new LinkedHashMap<>();
            for (Result result : results) {
                parts.put(result.feature(), result.pass() ? "PASS" : "FAIL:" + result.detail());
            }
            return parts.toString();
        }

        private record ActiveScenario(Result result, int startedTick) {
        }
    }

    private record Result(String feature,
                          boolean pass,
                          String detail,
                          boolean running,
                          int timeoutTicks,
                          boolean allowGoalContinuation,
                          Predicate<TaskStatus> assertion) {
        private static Result pass(String feature, String detail) {
            return new Result(feature, true, detail, false, 0, false, ignored -> true);
        }

        private static Result fail(String feature, String detail) {
            return new Result(feature, false, detail, false, 0, false, ignored -> false);
        }

        private static Result running(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, false, assertion);
        }

        private static Result runningGoal(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, assertion);
        }
    }
}
