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
import io.github.zoyluo.aibot.task.DescendToYTask;
import io.github.zoyluo.aibot.task.DigDownTask;
import io.github.zoyluo.aibot.task.OreDigTask;
import io.github.zoyluo.aibot.task.ContainerTask;
import io.github.zoyluo.aibot.task.CraftTask;
import io.github.zoyluo.aibot.task.FarmTask;
import io.github.zoyluo.aibot.task.IrrigateTask;
import io.github.zoyluo.aibot.task.MineTask;
import io.github.zoyluo.aibot.task.MoveTask;
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
import java.util.function.Consumer;
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
            "mine_buried_iron",
            "dig_down",
            "mine_exposed",
            "ore_dig_buried",
            "mine_iron_pocket",
            "mine_with_mob",
            "achieve_iron_ingot",
            "achieve_iron_pickaxe",
            "achieve_diamond",
            "achieve_armor",
            "achieve_workstation",
            "stockpile",
            "descend_to_ore",
            "move_dig_through",
            "farm_wheat_from_scratch",
            "nav_descend",
            "food",
            "food_full",
            "food_farm",
            "forage",
            "farm_irrigate",
            "cake");

    // 挖矿回归套件:一条命令 /aibot verify mining 跑完所有挖矿相关场景。
    private static final List<String> MINING_SUITE = List.of(
            "dig_down",
            "mine_exposed",
            "ore_dig_buried",
            "mine_to_iron",
            "mine_buried_iron",
            "mine_iron_pocket",
            "mine_with_mob",
            "mine_iron_from_scratch",
            "achieve_iron_ingot",
            "achieve_iron_pickaxe",
            "achieve_diamond");
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
                            builder.suggest("mining");
                            return builder.buildFuture();
                        })
                        .executes(context -> {
                            String feature = StringArgumentType.getString(context, "feature");
                            // "all"/"mining" 组别名在 start→expandFeatures 里展开;单用例直接传名。
                            return start(context.getSource(), List.of(feature));
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
            } else if ("mining".equals(feature)) {
                features.addAll(MINING_SUITE); // 挖矿回归套件别名
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
            case "mine_buried_iron" -> assignMineBuriedIron(bot);
            case "dig_down" -> assignDigDown(bot);
            case "mine_exposed" -> assignMineExposed(bot);
            case "ore_dig_buried" -> assignOreDigBuried(bot);
            case "mine_iron_pocket" -> assignMineIronPocket(bot);
            case "mine_with_mob" -> assignMineWithMob(bot);
            case "achieve_iron_ingot" -> assignAchieveIronIngot(bot);
            case "achieve_iron_pickaxe" -> assignAchieveIronPickaxe(bot);
            case "achieve_diamond" -> assignAchieveDiamond(bot);
            case "achieve_armor" -> assignAchieveArmor(bot);
            case "achieve_workstation" -> assignAchieveWorkstation(bot);
            case "stockpile" -> assignStockpile(bot);
            case "descend_to_ore" -> assignDescendToOre(bot);
            case "move_dig_through" -> assignMoveDigThrough(bot);
            case "farm_wheat_from_scratch" -> assignFarmWheatFromScratch(bot);
            case "nav_descend" -> assignNavDescend(bot);
            case "food" -> assignAchieveFood(bot);
            case "food_full" -> assignAchieveFoodFull(bot);
            case "food_farm" -> assignAchieveFoodFarm(bot);
            case "forage" -> assignForage(bot);
            case "farm_irrigate" -> assignFarmIrrigate(bot);
            case "cake" -> assignCake(bot);
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

    /**
     * REGRESSION(P1-a):MineTask 走 BlockMiner 挖一个**裸露**的指定方块。
     * 给石镐、正前方放一块裸露铁矿,断言挖到 raw_iron——验证 MineTask 的"找最近裸露块→挖"在新原语下正常。
     */
    private static Result assignMineExposed(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        BlockPos ore = bot.getBlockPos().offset(Direction.NORTH, 2);
        bot.getServerWorld().setBlockState(ore, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        return assignTask(bot, "mine_exposed", new MineTask(Blocks.IRON_ORE, 1), 800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
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
        return assignTask(bot, "mine_to_iron", new OreDigTask(java.util.Set.of(Blocks.IRON_ORE), 1),
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

    /**
     * REGRESSION:隔离"接近被埋矿"的定向通道逻辑(bbf8364 阶梯下降/防坠落)。给钻石镐排除工具/合成变量,
     * 把铁矿用 3 格石头墙封死(走路够不到,必须挖通道),断言能挖到 raw_iron 且不死。
     * 这条专测 OreSeek 的 APPROACH→digCorridorStep→MINE_ORE,不走 LLM,确定性可复现。
     */
    private static Result assignMineBuriedIron(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        // 北向 +3..+6 砌实心石墙(2 高)+ 铺底,bot 走到 +2 后必须挖通 3 格石头才够到 +6 的铁矿。
        for (int d = 3; d <= 6; d++) {
            BlockPos col = origin.offset(Direction.NORTH, d);
            world.setBlockState(col, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(col.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(col.down(), Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos ore = origin.offset(Direction.NORTH, 6);
        world.setBlockState(ore, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_buried_iron", "goal_submit_failed");
        }
        return Result.runningGoal("mine_buried_iron", 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    /**
     * REGRESSION(实测#9):DigDownTask 站着挖竖井取圆石。复现"地表 bot,脚下是表层土、相邻无裸露石头"
     * 的场景——旧实现会秒报 no_reachable / 反复重发 startMining 清零进度卡死。
     * 给木镐,脚下铺 2 层泥土再下是石头,bot 必须挖穿泥土到石层、采够 3 个圆石且不卡。
     */
    private static Result assignDigDown(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        // 脚下:y-1、y-2 铺泥土(表层土),y-3 起向下铺石头柱;模拟"草地下挖到石层"。
        world.setBlockState(origin.down(), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.down(2), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
        for (int dy = 3; dy <= 10; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        Task task = new DigDownTask(Blocks.STONE, 3);
        return assignTask(bot, "dig_down", task, 1200,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3);
    }

    /**
     * REGRESSION(实测#10):MINE_ORE 走 OreDigTask。给石镐、把铁矿用石头包埋(走路够不到,必须挖隧道接近),
     * 断言能挖到 raw_iron 不卡。专测 OreDigTask 的扫描→直挖隧道→挖脉,绕开 OreSeek 的 A* 接近 stall。
     */
    private static Result assignOreDigBuried(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        // 脚下 y-1..-4 实心石头,铁矿埋在 y-3 正下方稍偏:bot 必须竖直挖穿石头才够到。
        for (int dy = 1; dy <= 5; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlockState(origin.add(dx, -dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(origin.down(3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.down(4), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL); // 一条小脉,测泛洪
        Task task = new OreDigTask(java.util.Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), 2);
        return assignTask(bot, "ore_dig_buried", task, 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 2);
    }

    /**
     * REGRESSION(实测#8/#10):狭窄出生坑里空手"挖铁矿"全链。把 bot 围在 5x5 石墙小坑(模拟真实困境地形),
     * 坑里给一棵小树(原木)、脚下石层、深处铁矿;走 GoalExecutor 完整倒推,断言最终拿到 raw_iron 不卡不死。
     * 这是端到端冒烟:砍树→木镐→挖石→石镐→挖铁,全程 BlockMiner 原语。
     */
    private static Result assignMineIronPocket(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        // 5x5 石墙(4 高)围出狭窄坑,逼出"地形受限"变量。
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                    }
                }
            }
        }
        // 坑里一棵小树(2 段原木,叶子省略)。
        world.setBlockState(origin.offset(Direction.EAST), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.offset(Direction.EAST).up(), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        // 脚下石层 + 深处铁矿。
        for (int dy = 1; dy <= 8; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(origin.down(5), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_iron_pocket", "goal_submit_failed");
        }
        return Result.runningGoal("mine_iron_pocket", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    /**
     * REGRESSION(实测#7):挖矿中刷怪。给石镐、脚下石层埋铁矿,提交挖铁目标后立刻刷一只僵尸。
     * 断言目标**存活到完成**(挖到 raw_iron)——验证 DangerWatcher 暂停而非放弃目标、打完 resume 继续挖。
     */
    private static Result assignMineWithMob(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (int dy = 1; dy <= 6; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(origin.down(3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        ZombieEntity zombie = EntityType.ZOMBIE.create(world, SpawnReason.COMMAND);
        if (zombie != null) {
            zombie.refreshPositionAndAngles(bot.getX() + 2.0D, bot.getY(), bot.getZ() + 2.0D, 0.0F, 0.0F);
            world.spawnEntity(zombie);
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("mine_with_mob", "goal_submit_failed");
        }
        return Result.runningGoal("mine_with_mob", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1);
    }

    /**
     * REGRESSION(P2):achieve_goal 铁锭——空手→倒推→砍树→木镐→挖石→石镐→挖铁→熔炼→铁锭。
     * 全料齐备(树/石/铁矿)+ 一座熔炉 + 充足燃料就在身边,断言最终背包出现 iron_ingot。测熔炼链。
     */
    private static Result assignAchieveIronIngot(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (int dy = 0; dy < 8; dy++) {
            world.setBlockState(origin.offset(Direction.WEST, 2).up(dy), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (int dy = 1; dy <= 8; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(origin.down(4), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_INGOT, 1));
        if (!started) {
            return Result.fail("achieve_iron_ingot", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_iron_ingot", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1);
    }

    /**
     * REGRESSION(P2):achieve_goal 铁镐——空手→整条倒推含熔炼 3 铁锭→合成铁镐。最深的工具链。
     */
    private static Result assignAchieveIronPickaxe(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (int dy = 0; dy < 12; dy++) {
            world.setBlockState(origin.offset(Direction.WEST, 2).up(dy), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (int dy = 1; dy <= 10; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        // 3 个铁矿(铁镐需 3 铁锭)。
        world.setBlockState(origin.down(4), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.down(5), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.down(6), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_PICKAXE, 1));
        if (!started) {
            return Result.fail("achieve_iron_pickaxe", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_iron_pickaxe", 16000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_PICKAXE) >= 1);
    }

    /**
     * REGRESSION(食物链):空手 → Goal.Food 端到端。布置树(工具+燃料)+ 脚下石(熔炉)+ 5 头牛(猎物),
     * 感知择源应选打猎 → 砍树做工具 → 挖石做炉 → 打猎 → 烤肉,凑够 4 份熟食。验证感知择源/打猎/烤肉全链。
     */
    private static Result assignAchieveFood(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        // 聚焦"感知择源 → 打猎 → 烤肉"食物核心:给现成前置(熔炉+燃料+剑),不让 Goal.Food 倒推去挖石做炉
        //(dig_down 挖深井会把 bot 困在井底、追不到地表的牛——那是挖矿场景的 bug,单独修)。
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD, 1));
        // 5 头牛紧邻 bot(平整区内),免得追远卡路障
        for (int i = 0; i < 5; i++) {
            var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
            if (cow != null) {
                cow.refreshPositionAndAngles(origin.getX() + 1.5D, origin.getY(), origin.getZ() + (i - 2), 0.0F, 0.0F);
                world.spawnEntity(cow);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("food", "goal_submit_failed");
        }
        return Result.runningGoal("food", 8000,
                ignored -> bot.isAlive() && cookedFoodCount(bot) >= 4);
    }

    // 完整食物链(给现成石料/燃料/剑):做炉(craft furnace) → 打猎 → 烤。比 food(给现成炉)多覆盖一层"craft 熔炉"。
    // 不含挖石:dev 测试世界 bot 出生在 y6 黑暗地下(spawn snap 0,6,0),挖石阶梯会卡基岩 + 被蜘蛛围杀,
    // 那是地下挖矿的几何/导航问题、不是食物链逻辑,单独立项修(见 progress 笔记)。给 8 cobblestone → 直接 craft furnace。
    private static Result assignAchieveFoodFull(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD, 1));
        for (int i = 0; i < 6; i++) {
            var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
            if (cow != null) {
                cow.refreshPositionAndAngles(origin.getX() + 2.0D, origin.getY(), origin.getZ() + (i - 3), 0.0F, 0.0F);
                world.spawnEntity(cow);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("food_full", "goal_submit_failed");
        }
        return Result.runningGoal("food_full", 16000,
                ignored -> bot.isAlive() && cookedFoodCount(bot) >= 4);
    }

    private static int cookedFoodCount(AIPlayerEntity bot) {
        return InventoryAction.countItem(bot, Items.COOKED_BEEF)
                + InventoryAction.countItem(bot, Items.COOKED_PORKCHOP)
                + InventoryAction.countItem(bot, Items.COOKED_MUTTON)
                + InventoryAction.countItem(bot, Items.COOKED_CHICKEN)
                + InventoryAction.countItem(bot, Items.COOKED_RABBIT)
                + InventoryAction.countItem(bot, Items.BREAD)
                + InventoryAction.countItem(bot, Items.BAKED_POTATO);
    }

    // 食物链"种田做面包"分支端到端测试:无动物 + 有草 → Goal.Food 应走 ensureFoodTo 的种植链
    // (倒推锄头 → 割草/给种 → 开垦 → 播种 → 等熟 → 收割 → 合成面包),最终凑够 2 个面包。
    // 与 food/food_full(打猎→烤)互补,覆盖"没动物的地形靠种地自给"这条之前从未被测过的路径。
    // 故意不给锄头(给木板+工作台让其自己 craft),验证 GoalPlanner 在 Food→面包→小麦分支会倒推锄头(Fix B)。
    private static Result assignAchieveFoodFarm(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        // 1) 清掉附近动物+敌对生物:种植择源要"无动物"(否则误判有猎物→打猎),且避免骷髅抢占中止种田。
        clearNearbyMobs(world, origin);
        // 2) bot 周围半径 4 的地板(y-1)铺可开垦泥土(FARM 步 FarmTask 以 bot 为中心、半径 4 在此 till/plant)。
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(origin.add(dx, -1, dz), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // 3) 放几丛短草作"有草"信号(FOOD_GRASS_SCAN=32 内有短草即触发种植择源);放边缘泥土上,不占满农田。
        for (int dz = -1; dz <= 1; dz++) {
            world.setBlockState(origin.add(4, 0, dz), Blocks.SHORT_GRASS.getDefaultState(), Block.NOTIFY_ALL);
        }
        // 4) 给种子+木板+工作台,但不给锄头(锄头=tool 需工作台;面包/木棍不需)。验证倒推锄头(Fix B)。
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(2));
        if (!started) {
            return Result.fail("food_farm", "goal_submit_failed");
        }
        // perTick 每个服务端 tick 强制催熟 bot 周围小麦——无头测不能等自然随机刻生长(要数分钟、必超时),
        // bot 种下即熟,从而测"开垦→播种→收割→拾取(Fix A)→合成面包"整条逻辑链能否凑够 2 个面包。
        // (催熟必须放 perTick:assertion 仅在 task 完成时才调,FarmTask 等熟时无 task 完成→放 assertion 会死锁。)
        return Result.runningGoal("food_farm", 12000,
                tickBot -> forceGrowCrops(world, origin, 6, Blocks.WHEAT),
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.BREAD) >= 2);
    }

    // 把 center±radius 范围内未成熟的指定作物强制催熟到 maxAge(供无头测绕开自然生长等待)。
    private static void forceGrowCrops(ServerWorld world, BlockPos center, int radius, Block crop) {
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -1, -radius), center.add(radius, 2, radius))) {
            net.minecraft.block.BlockState st = world.getBlockState(pos);
            if (st.isOf(crop) && st.getBlock() instanceof net.minecraft.block.CropBlock cb && !cb.isMature(st)) {
                world.setBlockState(pos, cb.withAge(cb.getMaxAge()), Block.NOTIFY_LISTENERS);
            }
        }
    }

    // 觅食(野果)端到端测试:周围铺成熟甜浆果丛 → Goal.HaveItem(SWEET_BERRIES) 应走 gather 采到野果。
    // 覆盖"靠野果补充食物"这条途径(forage 工具实际就映射到 Goal.HaveItem(SWEET_BERRIES))。
    private static Result assignForage(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        // 北侧铺一片成熟(age3)甜浆果丛,下垫泥土防"无支撑"被方块更新打掉。浆果概率掉落,铺 15 丛远多于 target 4。
        net.minecraft.block.BlockState ripeBush = Blocks.SWEET_BERRY_BUSH.getDefaultState()
                .with(net.minecraft.state.property.Properties.AGE_3, 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                BlockPos ground = origin.add(dx, -1, -dz);
                world.setBlockState(ground, Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(ground.up(), ripeBush, Block.NOTIFY_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.SWEET_BERRIES, 4));
        if (!started) {
            return Result.fail("forage", "goal_submit_failed");
        }
        return Result.runningGoal("forage", 4000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.SWEET_BERRIES) >= 4);
    }

    // 无限水源/灌溉端到端测试:给 2 桶水 + 实心泥土地面 → IrrigateTask 挖 2×2 坑、对角放 2 桶水。
    // 断言:2×2 四格在 SETTLE 后全部变成水源(只放了 2 桶,另 2 格靠水流自动成源)——证明形成了
    // 可无限舀取/可灌溉的 2×2 无限水源。同时背包应变出 2 个空桶。
    private static Result assignFarmIrrigate(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        // floor 层(y-1)铺一片实心泥土,作挖坑的地面 + 2×2 坑四周的挡水墙。
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                world.setBlockState(origin.add(dx, -1, dz), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 2));
        BlockPos waterCenter = origin.add(2, -1, 0); // 在 floor 层挖 2×2 水池(bot 旁边)
        return assignTask(bot, "farm_irrigate", new IrrigateTask(waterCenter), 2400,
                ignored -> bot.isAlive()
                        && countWaterSources(world, waterCenter) >= 4
                        && InventoryAction.countItem(bot, Items.BUCKET) >= 2);
    }

    // 蛋糕合成链端到端测试:给 3 空桶 + 1 蛋 + 4 甘蔗 + 3 麦 + 工作台,旁边 spawn 3 头牛。
    // Goal.HaveItem(CAKE) 应:挤奶(MilkCowTask:空桶→牛奶桶×3) + 甘蔗→糖×2 + 蛋/麦现成 → 合成蛋糕。
    // 蛋为被动产物(鸡慢慢下),不自动生产、直接给(真实玩法需 bot 养鸡攒蛋,见 commit 说明)。
    private static Result assignCake(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin); // 先清(含历史污染的牛),再 spawn 干净的 3 头
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.EGG, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.SUGAR_CANE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        for (int i = 0; i < 3; i++) {
            var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
            if (cow != null) {
                cow.refreshPositionAndAngles(origin.getX() + 1.5D, origin.getY(), origin.getZ() + (i - 1), 0.0F, 0.0F);
                world.spawnEntity(cow);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.CAKE, 1));
        if (!started) {
            return Result.fail("cake", "goal_submit_failed");
        }
        return Result.runningGoal("cake", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.CAKE) >= 1);
    }

    // 数 center 处 2×2 四格里的水源数量。
    private static int countWaterSources(ServerWorld world, BlockPos center) {
        BlockPos[] cells = {center, center.east(), center.south(), center.east().south()};
        int n = 0;
        for (BlockPos p : cells) {
            if (io.github.zoyluo.aibot.action.FarmAction.isWaterSource(world, p)) {
                n++;
            }
        }
        return n;
    }

    // Phase1:装备目标。给足铁锭+木头(聚焦"做甲穿甲",省去挖 24 铁的耗时),achieve Goal.Armor 应做出 4 甲+剑并自动穿上。
    private static Result assignAchieveArmor(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 30));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (int dy = 0; dy < 6; dy++) {
            world.setBlockState(origin.offset(Direction.WEST, 2).up(dy), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Armor());
        if (!started) {
            return Result.fail("achieve_armor", "goal_submit_failed");
        }
        // 断言:做出并(自动)穿上铁胸甲 + 拥有铁剑 —— 代表 ensureArmor 全套生效。
        return Result.runningGoal("achieve_armor", 12000,
                ignored -> bot.isAlive()
                        && bot.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).isOf(Items.IRON_CHESTPLATE)
                        && (InventoryAction.countItem(bot, Items.IRON_SWORD) >= 1
                                || bot.getMainHandStack().isOf(Items.IRON_SWORD)));
    }

    // Phase2:基建目标。给足木板+圆石(聚焦"做三件套+摆放"),achieve Goal.Workstation 应在周围摆出工作台/熔炉/箱子。
    private static Result assignAchieveWorkstation(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Workstation());
        if (!started) {
            return Result.fail("achieve_workstation", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_workstation", 8000,
                ignored -> bot.isAlive()
                        && hasBlockNearby(bot, Blocks.CRAFTING_TABLE)
                        && hasBlockNearby(bot, Blocks.FURNACE)
                        && hasBlockNearby(bot, Blocks.CHEST));
    }

    private static boolean hasBlockNearby(AIPlayerEntity bot, Block block) {
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (BlockPos p : BlockPos.iterate(origin.add(-5, -3, -5), origin.add(5, 3, 5))) {
            if (world.getBlockState(p).isOf(block)) {
                return true;
            }
        }
        return false;
    }

    // Phase3:囤货目标。给石镐+石头柱,stockpile 应挖够 6 圆石(无箱子时 STOCKPILE best-effort 跳过,圆石留背包)。
    private static Result assignStockpile(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (int dy = 1; dy <= 12; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Stockpile(Items.COBBLESTONE, 6));
        if (!started) {
            return Result.fail("stockpile", "goal_submit_failed");
        }
        return Result.runningGoal("stockpile", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.COBBLESTONE) >= 6);
    }

    // 挖深层矿重构 P1:DescendToYTask 应连续挖竖井下到目标 Y(这是 Y=48 卡死的直接对策——先到矿层)。
    private static Result assignDescendToOre(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        int targetY = origin.getY() - 20;
        for (int dy = 1; dy <= 25; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        Task task = new DescendToYTask(targetY);
        return assignTask(bot, "descend_to_ore", task, 4000,
                ignored -> bot.isAlive() && bot.getBlockPos().getY() <= targetY);
    }

    // 挖掘式移动:bot 被水平石墙围住(头顶留空不窒息),目标在墙外。纯寻路走不通 → MoveTask 应降级挖开墙到达。
    private static Result assignMoveDigThrough(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            for (int dy = 0; dy <= 1; dy++) {
                world.setBlockState(origin.offset(direction).up(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos goal = origin.offset(Direction.EAST, 4);
        Task task = new MoveTask(bot, goal);
        return assignTask(bot, "move_dig_through", task, 4000,
                ignored -> bot.isAlive() && bot.getBlockPos().getSquaredDistance(goal) <= 9.0D);
    }

    /**
     * REGRESSION(P2):achieve_goal 钻石——给铁镐(隔离工具链),脚下石层埋钻石矿,断言挖到 diamond。
     * 测"金/红石/钻石/绿宝石需铁镐"这条新映射 + OreDig 挖高级矿。
     */
    private static Result assignAchieveDiamond(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (int dy = 1; dy <= 6; dy++) {
            world.setBlockState(origin.down(dy), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(origin.down(3), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 1));
        if (!started) {
            return Result.fail("achieve_diamond", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_diamond", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1);
    }

    /**
     * REGRESSION(P3):harvest_crop 小麦。给木锄,周围铺一排**成熟**小麦(age=7,免去等成长的不确定),
     * 走 GoalExecutor HarvestCrop 目标,断言收到 ≥3 个 wheat。测农业链:有锄→FARM 步→收割计数完成。
     */
    private static Result assignFarmWheatFromScratch(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_HOE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.WHEAT_SEEDS, 8));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin); // 清骷髅/牛:隔离收割逻辑,避免 y6 黑暗骷髅抢占中止目标(实测 aborted)
        net.minecraft.block.BlockState matureWheat =
                Blocks.WHEAT.getDefaultState().with(net.minecraft.state.property.Properties.AGE_7, 7);
        // 在 bot 北侧 floor 层(y-1)铺一片 3×3 成熟小麦(farmland + 成熟作物)。
        // 必须铺在 floor 层:原代码铺在 origin.y(bot 身体层)→ farmland 块挡在身体高度、小麦在头顶 y+1,
        // bot 走不过去也够不到、只收到 1~2 个 → 超时(实测 done=14 deposit_skipped)。3×3 全在 radius 4 内,
        // 远多于 target 3,容错。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = 1; dz <= 3; dz++) {
                BlockPos farmland = origin.add(dx, -1, -dz);
                world.setBlockState(farmland, Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(farmland.up(), matureWheat, Block.NOTIFY_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.HarvestCrop(Blocks.WHEAT, Items.WHEAT_SEEDS, Items.WHEAT, 3));
        if (!started) {
            return Result.fail("farm_wheat_from_scratch", "goal_submit_failed");
        }
        return Result.runningGoal("farm_wheat_from_scratch", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.WHEAT) >= 3);
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

    // 清掉 origin 周围 70 格的动物与敌对生物。两用:
    // (1) 动物——食物择源测试要"无动物"环境(否则 Goal.Food 误判有猎物→去打猎、测不到种植链;
    //     且 dev 世界被历史 food 场景 spawn 的牛污染、越积越多);
    // (2) 敌对——dev 测试世界 y6 黑暗有骷髅,长时间种田/挖矿途中被攻击会触发生存反射抢占、中止目标,
    //     使确定性回归测试 flaky(farm_wheat 实测因此 aborted)。清掉以隔离被测逻辑本身。
    private static void clearNearbyMobs(ServerWorld world, BlockPos origin) {
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(origin).expand(70.0D);
        world.getEntitiesByClass(net.minecraft.entity.passive.AnimalEntity.class, box, e -> true)
                .forEach(net.minecraft.entity.Entity::discard);
        world.getEntitiesByClass(net.minecraft.entity.mob.HostileEntity.class, box, e -> true)
                .forEach(net.minecraft.entity.Entity::discard);
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
            running.perTick().accept(bot); // 每 tick 执行场景的世界副作用(如催熟作物),先于下面的状态判定
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
                          Predicate<TaskStatus> assertion,
                          Consumer<AIPlayerEntity> perTick) {
        private static final Consumer<AIPlayerEntity> NO_TICK = bot -> {
        };

        private static Result pass(String feature, String detail) {
            return new Result(feature, true, detail, false, 0, false, ignored -> true, NO_TICK);
        }

        private static Result fail(String feature, String detail) {
            return new Result(feature, false, detail, false, 0, false, ignored -> false, NO_TICK);
        }

        private static Result running(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, false, assertion, NO_TICK);
        }

        private static Result runningGoal(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, assertion, NO_TICK);
        }

        // 带每-tick 副作用钩子的 runningGoal:perTick 在 pollActive 每个服务端 tick 都被调用(无论有无 task 完成),
        // 用于测试期持续操纵世界(如强制催熟作物,绕开自然随机刻生长的漫长等待)。assertion 仍是成功判定。
        private static Result runningGoal(String feature, int timeoutTicks,
                                          Consumer<AIPlayerEntity> perTick, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, assertion, perTick);
        }
    }
}
