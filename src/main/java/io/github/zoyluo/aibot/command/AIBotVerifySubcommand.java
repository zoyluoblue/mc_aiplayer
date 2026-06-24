package io.github.zoyluo.aibot.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.brain.BrainCoordinator;
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
import io.github.zoyluo.aibot.task.RaidCropsTask;
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
            "achieve_gold_ingot",
            "achieve_obsidian",
            "achieve_iron_pickaxe",
            "achieve_diamond",
            "iron_extreme",
            "diamond_extreme",
            "food_extreme",
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
            "cake",
            "village_harvest",
            "real_wood",
            "real_food",
            "real_wheat",
            "real_iron",
            "real_diamond",
            "real_armor",
            "real_build",
            "real_obsidian",
            "real_nav_far",
            "nav_pillar_out",
            "nav_buried_escape",
            "nav_unreachable",
            "goal_queue",
            "goal_build_auto",
            "goal_build_custom",
            "msg_keep_goal",
            "tool_dispatch",
            "knowledge_smoke",
            "craft_runtime",
            "geo_vertical",
            "geo_slope",
            "geo_overhang",
            "geo_wall",
            "geo_pocket",
            "geo_deep",
            "geo_lava",
            "geo_gravel",
            "geo_fullinv",
            "geo_rich", "geo_water", "geo_recover", "geo_bonus", "geo_stockpile", "geo_resume", "geo_shaft", "geo_cave", "geo_diamond_lava", "geo_obsidian_make", "geo_cliff_tree", "geo_night_swarm", "geo_replay_ore",
            "geo_flow", "geo_lake", "geo_guard", "explore_wood");

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
            "achieve_gold_ingot",
            "achieve_obsidian",
            "achieve_iron_pickaxe",
            "achieve_diamond",
            "geo_recover",
            "geo_stockpile",
            "geo_resume",
            "geo_guard");

    // 食物回归套件:一条命令 /aibot verify food_suite 跑完所有食物/种田相关场景。
    // 覆盖五条食物途径:打猎+烤(food/food_full)、种田做面包(food_farm)、觅食(forage)、
    // 无限水源灌溉(farm_irrigate)、合成蛋糕(cake)、村庄收菜(village_harvest),外加种田基元(farm/farm_wheat)。
    private static final List<String> FOOD_SUITE = List.of(
            "food",
            "food_full",
            "farm",
            "farm_wheat_from_scratch",
            "food_farm",
            "forage",
            "farm_irrigate",
            "cake",
            "village_harvest");

    // 矿物材料回归套件:一条命令 /aibot verify material_suite 跑完四种目标矿:铁锭/金锭/钻石/黑曜石。
    private static final List<String> MATERIAL_SUITE = List.of(
            "achieve_iron_ingot",
            "achieve_gold_ingot",
            "achieve_diamond",
            "achieve_obsidian");

    // 极端环境回归套件:矿物/食物在"怪物围攻 + 深暗"下仍要完成。/aibot verify extreme_suite
    private static final List<String> EXTREME_SUITE = List.of(
            "iron_extreme",
            "diamond_extreme",
            "food_extreme");

    // 地形矩阵套件(②):同一挖矿任务 × 六种几何,统一接近原语的考场。/aibot verify geo_suite
    private static final List<String> GEO_SUITE = List.of(
            "geo_vertical", "geo_slope", "geo_overhang", "geo_wall", "geo_pocket", "geo_deep",
            "geo_lava", "geo_gravel", "geo_fullinv", "geo_rich", "geo_water", "geo_bonus",
            "geo_flow", "geo_lake");

    // 贴近实操套件:自然世界、空背包、零给予,从零完成目标。/aibot verify real_suite
    // 失败 = 自动化与实操的真实差距,逐个修复;real_obsidian 预期 FAIL(浇水造黑曜石能力未实现)。
    private static final List<String> REAL_SUITE = List.of(
            "real_wood",
            "real_food",
            "real_wheat",
            "real_iron",
            "real_diamond",
            "real_armor",
            "real_obsidian");

    // 寻路容错专项套件:/aibot verify nav_suite。四条各钉一种实操高频故障形态:
    // 自然地形长距离绕行(real_nav_far)、被困搭柱翻墙(nav_pillar_out)、活埋窒息脱困(nav_buried_escape)、
    // 不可达目标快速认输(nav_unreachable)。前三条测"会自救",最后一条测"会认输"——
    // 空转不报错比干净失败更伤:实操里 bot 看着在干活,实际原地打转浪费整局。
    private static final List<String> NAV_SUITE = List.of(
            "real_nav_far",
            "nav_pillar_out",
            "nav_buried_escape",
            "nav_unreachable");

    // R2 LLM 全链层套件:中文口语指令走真实 DeepSeek 大脑(意图解析→选工具→参数化→执行),
    // 与玩家聊天 @bot 完全同一代码路径(BrainCoordinator.handleMessage)。烧真 API 钱:
    // 故意不进 ALL_FEATURES(verify all 不应偷偷计费),必须显式 /aibot verify llm_suite(或单点名),
    // 且 WITH_LLM=1 跑(test 脚本默认 unset DEEPSEEK_API_KEY 隔离大脑)。
    private static final List<String> LLM_SUITE = List.of(
            "llm_move",
            "llm_food",
            "llm_iron",
            "llm_diamond");

    // 对话式助手层套件:/aibot verify assistant_suite。验证助手层四块新地基(此前只编译过、零运行验证):
    // P0 目标队列(连续吩咐自动排队接续)、P1 Goal.Build 自动备料(只给原木自己算料合成)、
    // P3 参数化蓝图(custom:WxDxH:material)、P2 玩家消息不清进行中目标(打断保留语义)。
    // 全部确定性实验室场景,不走 LLM、不烧 API(大脑驱动的全链由 llm_suite 单独覆盖)。
    private static final List<String> ASSISTANT_SUITE = List.of(
            "goal_queue",
            "goal_build_auto",
            "goal_build_custom",
            "msg_keep_goal");
    private static final Map<UUID, VerifyRun> RUNS = new ConcurrentHashMap<>();
    // 场景空间隔离计数:每场景在 x 方向轮转到新地块,防套件内场景互染(prepareArea 注释详述)。
    private static int scenarioSlot = 0;

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
                            builder.suggest("food_suite");
                            builder.suggest("real_suite");
                            builder.suggest("nav_suite");
                            builder.suggest("llm_suite");
                            builder.suggest("assistant_suite");
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
            if (feature.contains("+")) {
                // 加号组合:任意场景/套件串成一跑(诊断'单跑 PASS 套跑 FAIL'的顺序污染对最常用;
                // Brigadier word() 字符集不含逗号,故用 +)。
                features.addAll(expandFeatures(java.util.Arrays.asList(feature.split("\\+"))));
            } else if ("all".equals(feature)) {
                features.addAll(ALL_FEATURES);
            } else if ("mining".equals(feature)) {
                features.addAll(MINING_SUITE); // 挖矿回归套件别名
            } else if ("food_suite".equals(feature)) {
                features.addAll(FOOD_SUITE); // 食物回归套件别名
            } else if ("material_suite".equals(feature)) {
                features.addAll(MATERIAL_SUITE); // 矿物材料回归套件别名
            } else if ("extreme_suite".equals(feature)) {
                features.addAll(EXTREME_SUITE); // 极端环境回归套件别名
            } else if ("real_suite".equals(feature)) {
                features.addAll(REAL_SUITE); // 贴近实操套件别名
            } else if ("geo_suite".equals(feature)) {
                features.addAll(GEO_SUITE); // 地形矩阵套件别名
            } else if ("nav_suite".equals(feature)) {
                features.addAll(NAV_SUITE); // 寻路容错专项套件别名
            } else if ("llm_suite".equals(feature)) {
                features.addAll(LLM_SUITE); // R2 LLM 全链层套件别名(真实 DeepSeek,计费,需 WITH_LLM=1)
            } else if ("assistant_suite".equals(feature)) {
                features.addAll(ASSISTANT_SUITE); // 对话式助手层套件别名(P0 队列/P1 自动备料/P3 参数化/P2 打断保留)
            } else if (ALL_FEATURES.contains(feature) || LLM_SUITE.contains(feature)
                       || "real_diamond3".equals(feature)) {
                // llm_* 故意不进 ALL_FEATURES(verify all 不烧 API 钱),但允许单点名跑(单跑最省钱)。
                features.add(feature);
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(features));
    }

    private static Result startScenario(ServerCommandSource source, AIPlayerEntity bot, String feature) throws IOException {
        // 场景开始前统一清执行状态:上一场景断言满足判 PASS 时 goal 可能仍有剩余步骤在跑
        //(runningGoal 的 assertion≠goal 完成),活跃 plan 会拒掉本场景的 submit(实测 forage
        // goal_submit_failed)或把残余任务泄进来。每场景从干净执行状态开跑,一处治所有场景间泄漏。
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.abort(bot);
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
            case "achieve_gold_ingot" -> assignAchieveGoldIngot(bot);
            case "achieve_obsidian" -> assignAchieveObsidian(bot);
            case "iron_extreme" -> assignIronExtreme(bot);
            case "diamond_extreme" -> assignDiamondExtreme(bot);
            case "food_extreme" -> assignFoodExtreme(bot);
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
            case "village_harvest" -> assignVillageHarvest(bot);
            case "real_wood" -> assignRealWood(bot);
            case "real_food" -> assignRealFood(bot);
            case "real_wheat" -> assignRealWheat(bot);
            case "real_iron" -> assignRealIron(bot);
            case "real_diamond" -> assignRealDiamond(bot);
            case "real_diamond3" -> assignRealDiamond3(bot);
            case "real_armor" -> assignRealArmor(bot);
            case "real_build" -> assignRealBuild(bot);
            case "real_obsidian" -> assignRealObsidian(bot);
            case "llm_move" -> assignLlmMove(bot);
            case "llm_food" -> assignLlmFood(bot);
            case "llm_iron" -> assignLlmIron(bot);
            case "llm_diamond" -> assignLlmDiamond(bot);
            case "real_nav_far" -> assignRealNavFar(bot);
            case "nav_pillar_out" -> assignNavPillarOut(bot);
            case "nav_buried_escape" -> assignNavBuriedEscape(bot);
            case "nav_unreachable" -> assignNavUnreachable(bot);
            case "goal_queue" -> assignGoalQueue(bot);
            case "goal_build_auto" -> assignGoalBuildAuto(bot);
            case "goal_build_custom" -> assignGoalBuildCustom(bot);
            case "msg_keep_goal" -> assignMsgKeepGoal(bot);
            case "tool_dispatch" -> assignToolDispatch(bot);
            case "knowledge_smoke" -> assignKnowledgeSmoke(bot);
            case "craft_runtime" -> assignCraftRuntime(bot);
            case "geo_vertical" -> assignMineGeo(bot, "vertical");
            case "geo_slope" -> assignMineGeo(bot, "slope");
            case "geo_overhang" -> assignMineGeo(bot, "overhang");
            case "geo_wall" -> assignMineGeo(bot, "wall");
            case "geo_pocket" -> assignMineGeo(bot, "pocket");
            case "geo_deep" -> assignMineGeo(bot, "deep");
            case "geo_lava" -> assignMineGeo(bot, "lava");
            case "geo_gravel" -> assignMineGeo(bot, "gravel");
            case "geo_fullinv" -> assignMineGeo(bot, "fullinv");
            case "geo_rich" -> assignGeoRich(bot);
            case "geo_water" -> assignMineGeo(bot, "water");
            case "geo_shaft" -> assignMineGeo(bot, "shaft");
            case "geo_cave" -> assignMineGeo(bot, "cave");
            case "geo_diamond_lava" -> assignGeoDiamondLava(bot);
            case "geo_replay_ore" -> assignGeoReplayOre(bot);
            case "geo_obsidian_make" -> assignGeoObsidianMake(bot);
            case "geo_cliff_tree" -> assignGeoCliffTree(bot);
            case "geo_night_swarm" -> assignGeoNightSwarm(bot);
            case "geo_flow" -> assignMineGeo(bot, "flow");
            case "geo_lake" -> assignMineGeo(bot, "lake");
            case "geo_recover" -> assignGeoRecover(bot);
            case "geo_bonus" -> assignGeoBonus(bot);
            case "geo_stockpile" -> assignGeoStockpile(bot);
            case "geo_resume" -> assignGeoResume(bot);
            case "geo_guard" -> assignGeoGuard(bot);
            case "explore_wood" -> assignExploreWood(bot);
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
        // small_hut 实测需 114 板(地板25+墙66-门2+顶25),原 64 板建到一半料尽 missing_material
        //(BuildTask 只拿成品不合成;自动备料是 Goal.Build 链的事,本场景测纯建造)。
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 128));
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
        clearNearbyMobs(world, origin); // 从零链 bot 无装备,y6 怪海会围杀(实测 aborted=被僵尸打死)
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
        clearNearbyMobs(world, origin); // y6 怪海会把无装备的 bot 打死(重生背包清空→任务必败)
        // 坑里一棵小树(4 段原木——从零链需 工作台4+木镐3+棍2=9 板,2 段原木只出 8 板差 1,实测 need:oak_planks x1)。
        for (int dy = 0; dy < 4; dy++) {
            world.setBlockState(origin.offset(Direction.EAST).up(dy), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
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
        // 清掉 y6 环境怪海(会把 bot 围杀,重生背包清空→工具闸报缺镐),只留下面受控 spawn 的 1 只——
        // 本场景测的是"带 1 怪挖矿"的战斗抢占/恢复,不是怪海生存。穿甲提高确定性。
        clearNearbyMobs(world, origin);
        giveDeepMineKit(bot);
        io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot);
        fillStoneCube(world, origin, 4, 8);
        world.setBlockState(origin.down(3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        ZombieEntity zombie = EntityType.ZOMBIE.create(world, SpawnReason.COMMAND);
        if (zombie != null) {
            zombie.setPersistent();
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
    // 铁锭:实心石区里埋铁矿,给石镐+熔炉+煤 → 挖铁矿→熔炼→铁锭(聚焦矿+熔,工具/炉链由其它场景测)。
    private static Result assignAchieveIronIngot(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 10);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 4));
        world.setBlockState(origin.down(3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_INGOT, 1));
        if (!started) {
            return Result.fail("achieve_iron_ingot", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_iron_ingot", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1);
    }

    // 金锭(深层矿,需铁镐):传送到金矿层(-16)、脚下埋金矿,给铁镐+熔炉+深矿安全装+供给 → 挖金矿→熔炼→金锭。
    private static Result assignAchieveGoldIngot(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -16);
        ServerWorld world = bot.getServerWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(origin.add(dx, -3, dz), Blocks.GOLD_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.GOLD_INGOT, 1));
        if (!started) {
            return Result.fail("achieve_gold_ingot", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_gold_ingot", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.GOLD_INGOT) >= 1);
    }

    // 黑曜石:实心石区里埋一层黑曜石,给钻石镐 → DigDownTask 下挖撞到黑曜石层、挖 1 块(黑曜石挖得慢)。
    private static Result assignAchieveObsidian(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 10);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        // 在 down(2..3) 铺一层 5×5 黑曜石,保证下挖阶梯无论朝哪都会撞到(只需挖到 1 块即达标)。
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlockState(origin.add(dx, -2, dz), Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(dx, -3, dz), Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OBSIDIAN, 1));
        if (!started) {
            return Result.fail("achieve_obsidian", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_obsidian", 8000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OBSIDIAN) >= 1);
    }

    // 极端环境①:铁锭 + 怪物围攻。穿甲 + 2 僵尸,bot 要边打边挖铁→熔炼。验证战斗 pauseFor/resume 不丢任务。
    private static Result assignIronExtreme(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 10);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 4));
        giveDeepMineKit(bot);
        io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot);
        world.setBlockState(origin.down(3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        spawnHostiles(world, origin, 2);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_INGOT, 1));
        if (!started) {
            return Result.fail("iron_extreme", "goal_submit_failed");
        }
        return Result.runningGoal("iron_extreme", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1);
    }

    // 极端环境②:钻石(深层 -59,黑暗)+ 怪物围攻。深 + 暗 + 2 僵尸三重极端,bot 要边打边挖钻石。
    private static Result assignDiamondExtreme(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59);
        ServerWorld world = bot.getServerWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(origin.add(dx, -2, dz), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        spawnHostiles(world, origin, 2);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 1));
        if (!started) {
            return Result.fail("diamond_extreme", "goal_submit_failed");
        }
        return Result.runningGoal("diamond_extreme", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1);
    }

    // 极端环境③:收集食物(打猎+烤)+ 怪物围攻。穿甲 + 2 僵尸,bot 要边打边猎边烤够 4 熟食。
    private static Result assignFoodExtreme(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        giveDeepMineKit(bot); // 含铁剑(打猎+打怪两用)+ 甲
        io.github.zoyluo.aibot.action.EquipAction.equipBestArmor(bot);
        for (int i = 0; i < 6; i++) {
            var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
            if (cow != null) {
                cow.refreshPositionAndAngles(origin.getX() + 2.0D, origin.getY(), origin.getZ() + (i - 3), 0.0F, 0.0F);
                world.spawnEntity(cow);
            }
        }
        spawnHostiles(world, origin, 2);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("food_extreme", "goal_submit_failed");
        }
        return Result.runningGoal("food_extreme", 12000,
                ignored -> bot.isAlive() && cookedFoodCount(bot) >= 4);
    }

    /**
     * REGRESSION(P2):achieve_goal 铁镐——空手→整条倒推含熔炼 3 铁锭→合成铁镐。最深的工具链。
     */
    private static Result assignAchieveIronPickaxe(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin); // 全链早期无装备,清 y6 怪海
        // 两列 24 木:from_scratch 链含熔炼,燃料烧原木+craft 整木换板的执行漂移可能吃掉补采余量,
        // 单列 12 木被第一轮砍光后 replan 补采就 no_resource(套跑实测)。树管够,漂移全兜住。
        for (int dy = 0; dy < 12; dy++) {
            world.setBlockState(origin.offset(Direction.WEST, 2).up(dy), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(origin.offset(Direction.WEST, 2).offset(Direction.NORTH, 2).up(dy), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        // 实心石区替代 1 列石柱:挖石/挖铁任务是斜挖阶梯,1 列柱第一步就走出柱外掉进残留坑(no_resource 元凶)。
        fillStoneCube(world, origin, 4, 10);
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
                + InventoryAction.countItem(bot, Items.BAKED_POTATO)
                // 浆果按 2:1 折算(与 GoalPlanner.ensureFoodTo 的荒芜兜底源一致):
                // 针叶林等无动物世界 Food 目标走"采浆果直接吃",断言口径必须同步,否则达成也判 FAIL。
                + InventoryAction.countItem(bot, Items.SWEET_BERRIES) / 2;
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
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(1));
        if (!started) {
            return Result.fail("food_farm", "goal_submit_failed");
        }
        // perTick 每个服务端 tick 强制催熟 bot 周围小麦——无头测不能等自然随机刻生长(要数分钟、必超时),
        // bot 种下即熟,从而测"开垦→播种→收割→拾取(Fix A)→合成面包"整条逻辑链能否凑够 2 个面包。
        // (催熟必须放 perTick:assertion 仅在 task 完成时才调,FarmTask 等熟时无 task 完成→放 assertion 会死锁。)
        return Result.runningGoal("food_farm", 12000,
                tickBot -> forceGrowCrops(world, origin, 6, Blocks.WHEAT),
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.BREAD) >= 1);
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

    // 村庄收菜端到端测试:开一条可行走走廊,尽头摆一片成熟作物田(模拟村庄农田,距 bot ~12 格)。
    // RaidCropsTask 应:大范围扫到作物田 → 走过去 → 收割 → 捡起,凑够 4 个产出。覆盖"找现成作物田收菜"
    //(与 FarmTask 自种自收互补)。走廊是必须的:dev 世界 y6 地下全是石头,不开路 bot 无法走到远处的田。
    private static Result assignVillageHarvest(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        // 走廊:floor 铺土、上方清 3 高,从 bot 一直通到田。
        for (int x = 0; x <= 16; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlockState(origin.add(x, -1, z), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
                for (int y = 0; y <= 2; y++) {
                    world.setBlockState(origin.add(x, y, z), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        // 成熟作物田(小麦/胡萝卜/马铃薯混种,都是 CropBlock):x 10..14 × z -1..1 = 15 株,远多于 target 4。
        net.minecraft.block.BlockState[] crops = {
                Blocks.WHEAT.getDefaultState().with(net.minecraft.state.property.Properties.AGE_7, 7),
                Blocks.CARROTS.getDefaultState().with(net.minecraft.state.property.Properties.AGE_7, 7),
                Blocks.POTATOES.getDefaultState().with(net.minecraft.state.property.Properties.AGE_7, 7)};
        int i = 0;
        for (int x = 10; x <= 14; x++) {
            for (int z = -1; z <= 1; z++) {
                world.setBlockState(origin.add(x, -1, z), Blocks.FARMLAND.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(x, 0, z), crops[i++ % crops.length], Block.NOTIFY_ALL);
            }
        }
        return assignTask(bot, "village_harvest", new RaidCropsTask(4), 4000,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.WHEAT)
                        + InventoryAction.countItem(bot, Items.CARROT)
                        + InventoryAction.countItem(bot, Items.POTATO) >= 4);
    }

    // ==================== 贴近实操层(realistic) ====================
    // 与人造理想场景相反:自然生成世界(固定 seed)、空背包、不清怪、不给装备、不铺方块、不传送——
    // 测"真实条件下从零完成目标"。这层的失败清单 = 自动化与实操的差距清单,逐个修。
    // 注意:断言只代表"拿到结果",不代表过程不蠢(绕路/卡顿观感仍需实操确认)。

    private static BlockPos prepareRealistic(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        world.setTimeOfDay(1000L); // 同 prepareArea:白天开局,隔离夜间反射 flaky
        bot.getActionPack().stopAll();
        clearInventory(bot); // 实操开局=空背包;其余一概不动(不清怪/不铺/不给)
        // real_wheat 会调 randomTickSpeed,这里统一复位,避免场景间泄漏
        world.getGameRules().get(net.minecraft.world.GameRules.RANDOM_TICK_SPEED).set(3, world.getServer());
        surfaceTeleport(bot);
        return bot.getBlockPos();
    }

    // 场景锚点列下方 12 格内无水(挖石阶梯斜下挖,湖上/含水层地块会把 bot 挖进水里泡死——
    // 实测 dig_down stall dump 四面全 water)。
    private static boolean dryColumn(ServerWorld world, BlockPos top) {
        for (int dy = 0; dy <= 12; dy++) {
            if (!world.getFluidState(top.down(dy)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // 出生点在洞/地下时提到自然地表(实操玩家在地表活动);已在地表则原地不动。
    // 围墙/活埋类场景必须先地表化:在 y6 黑暗地下摆围墙会触发 DangerWatcher"困死陷阱"保命传送
    // (dark_trap_escape),把被测的真实逃生(搭柱/挖墙)直接顶掉(实测 nav_pillar_out aborted)。
    private static void surfaceTeleport(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos at = bot.getBlockPos();
        if (world.isSkyVisible(at)) {
            return;
        }
        int topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, at.getX(), at.getZ());
        bot.teleport(world, at.getX() + 0.5D, topY, at.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
        bot.getActionPack().stopAll();
    }

    // 读 bot 的累计死亡统计(ServerStatHandler 跨重生持续累加,不随重生清零)。real_* 零死亡断言的基线用:
    // 实操里死亡重生 = 掉装备/丢位置/进度报废的重大事故,哪怕重生后把目标补齐也不能算过——
    // 只看 isAlive() 抓不到"死过又活了"的情况,必须对比死亡计数。
    private static int deathCount(AIPlayerEntity bot) {
        return bot.getStatHandler().getStat(net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(net.minecraft.stat.Stats.DEATHS));
    }

    // 拥有某装备:背包里有 或 已穿在任意装备槽。用于 armor 断言——避免"合好甲→自动穿甲"的 1-tick 竞态
    // (runningGoal 在目标完成那刻检断言,穿甲在同 burst 下一拍生效,否则误判'胸甲没穿' FAIL)。
    private static boolean hasGear(AIPlayerEntity bot, Item item) {
        if (InventoryAction.countItem(bot, item) >= 1) {
            return true;
        }
        for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
            if (bot.getEquippedStack(slot).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    // 实操:砍 8 根原木(自然找树;接受任意树种)。
    private static Result assignRealWood(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // 零死亡红线:场景内死过一次即 FAIL(见 deathCount 注释)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_LOG, 8));
        if (!started) {
            return Result.fail("real_wood", "goal_submit_failed");
        }
        java.util.Set<Item> logs = java.util.Set.copyOf(io.github.zoyluo.aibot.craft.RecipeRegistry.LOGS);
        return Result.runningGoal("real_wood", 8000,
                ignored -> bot.isAlive()
                        && io.github.zoyluo.aibot.action.HarvestCore.countInventoryItems(bot, logs) >= 8
                        && deathCount(bot) == deathBase);
    }

    // 实操:从零搞 4 个熟食(自己感知周围择源:打猎/种植;自己做炉凑燃料)。
    private static Result assignRealFood(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(实操死一次=大事故)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Food(4));
        if (!started) {
            return Result.fail("real_food", "goal_submit_failed");
        }
        return Result.runningGoal("real_food", 16000,
                ignored -> bot.isAlive() && cookedFoodCount(bot) >= 4
                        && deathCount(bot) == deathBase);
    }

    // 实操:从零种麦做 2 个面包(割草取种→锄→开垦→种→等熟→收→合成)。
    // 唯一的让步:randomTickSpeed 3→40(加速生长 ~13x)。生长路径真实走过,只是时间加速——
    // 不加速的话自然熟要 20+ 分钟,套件没法跑;这与 perTick 魔法催熟(food_farm)不同档。
    private static Result assignRealWheat(AIPlayerEntity bot) {
        prepareRealistic(bot);
        ServerWorld world = bot.getServerWorld();
        world.getGameRules().get(net.minecraft.world.GameRules.RANDOM_TICK_SPEED).set(40, world.getServer());
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(实操死一次=大事故)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.BREAD, 2));
        if (!started) {
            return Result.fail("real_wheat", "goal_submit_failed");
        }
        return Result.runningGoal("real_wheat", 16000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.BREAD) >= 2
                        && deathCount(bot) == deathBase);
    }

    // 实操:从零一块铁锭(砍树→木镐→挖石→石镐→找铁矿→挖→做炉→熔炼)。自然地形大考。
    private static Result assignRealIron(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(实操死一次=大事故)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.IRON_INGOT, 1));
        if (!started) {
            return Result.fail("real_iron", "goal_submit_failed");
        }
        return Result.runningGoal("real_iron", 24000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 实操:从零一颗钻石(完整工具链 + 真实下挖到 -59 找矿,会遇洞穴/岩浆/黑暗)。
    private static Result assignRealDiamond(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(实操死一次=大事故)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 1));
        if (!started) {
            return Result.fail("real_diamond", "goal_submit_failed");
        }
        return Result.runningGoal("real_diamond", 36000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 真实应用终极(钻石≥3):从零真实地形挖到 3 颗钻石——用户目标的回归化身。3 颗逼出"连续多矿脉
    // 定位+重复下潜",timeout 36000t 给足全链,零死亡红线。reliability.sh 跑它做修复前后成功率对照。
    // 只许单点名/脚本跑(不进 ALL_FEATURES:36000t 会拖垮 verify all)。
    private static Result assignRealDiamond3(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 3));
        if (!started) {
            return Result.fail("real_diamond3", "goal_submit_failed");
        }
        return Result.runningGoal("real_diamond3", 36000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 3
                        && deathCount(bot) == deathBase);
    }

    // 从零做整套铁甲(真实地形,不预置任何材料——区别于 achieve_armor 的预置 30 铁只测合甲)。
    // 链路:木→工具→挖铁×24+→熔炼→合 4 甲+剑→穿。比钻石浅(铁在 Y16-48,石镐够,无 Y-59 岩浆),但量大。
    // 断言:整套四件铁甲全部穿上 + 存活 + 零死亡(死一次=大事故,与 real_diamond 同红线)。
    private static Result assignRealArmor(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.Armor());
        if (!started) {
            return Result.fail("real_armor", "goal_submit_failed");
        }
        return Result.runningGoal("real_armor", 36000,
                ignored -> bot.isAlive() && deathCount(bot) == deathBase
                        && hasGear(bot, Items.IRON_HELMET)
                        && hasGear(bot, Items.IRON_CHESTPLATE)
                        && hasGear(bot, Items.IRON_LEGGINGS)
                        && hasGear(bot, Items.IRON_BOOTS)
                        && hasGear(bot, Items.IRON_SWORD)); // 铁套+铁剑:整套四件甲 + 铁剑都到手(Goal.Armor full 本就含剑)
    }

    // 建筑深化·真实地形建房:备足木板(隔离"建造"本体——选址 autoSite + 整地 + 逐格落成),不测备料。
    // 真实地形(斜坡/起伏/水边)是 lab 平整画布测不到的:autoSite 选址 + flatten 整地能否干净落成一栋房。
    // 断言:origin±24、木板族方块 ≥80(small_hut 全房 112,留余量)+ 存活 + 零死亡。先暴露真实地形建造短板。
    private static Result assignRealBuild(AIPlayerEntity bot) {
        prepareRealistic(bot);
        clearInventory(bot);
        // 备足多树种木板(蓝图按当地树种自适应,只给 oak 会 need birch/spruce_log)+ 整地填料(dirt/cobble),
        // 真正隔离"建造"本体(选址+整地+落成),不测备料/采木。
        for (Item p : io.github.zoyluo.aibot.craft.RecipeRegistry.PLANKS) {
            InventoryAction.giveItem(bot, new ItemStack(p, 128));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 256));        // FLATTEN 挖高填低的填料
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 128));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        ServerWorld world = bot.getServerWorld();
        final int deathBase = deathCount(bot);
        java.util.Set<Block> plankBlocks = new java.util.HashSet<>();
        for (Item planks : io.github.zoyluo.aibot.craft.RecipeRegistry.PLANKS) {
            Block block = Block.getBlockFromItem(planks);
            if (block != Blocks.AIR) {
                plankBlocks.add(block);
            }
        }
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.Build("small_hut"))) {
            return Result.fail("real_build", "goal_submit_failed");
        }
        // 计数锚点对准真实建房点:断言在 BuildTask 完成时评估(见 pollActive),此刻 bot 就在建好的 hut 处;
        // SiteFinder 自动选址可能远离/低于出生点(origin),故绕 bot 当前位对称扫描(±10 水平、上下 -6..8),
        // 而非以出生点为锚的 "only above"——治"建满 116/116 零死亡却被原点计数漏掉"的断言锚点局限。
        return Result.runningGoal("real_build", 20000,
                ignored -> bot.isAlive() && deathCount(bot) == deathBase
                        && countNearbyBlocks(world, bot.getBlockPos(), 10, -6, 8, plankBlocks) >= 80);
    }

    // snapshot 落地辅助:按相对坐标放一块默认态方块(配 /aibot snapshot 导出的 setRel 行)。
    static void setRel(ServerWorld world, BlockPos origin, int dx, int dy, int dz, String id) {
        net.minecraft.block.Block block = net.minecraft.registry.Registries.BLOCK
                .getOptionalValue(net.minecraft.util.Identifier.of(id)).orElse(null);
        if (block == null) {
            io.github.zoyluo.aibot.log.BotLog.config("snapshot_unknown_block", "id", id);
            return;
        }
        world.setBlockState(origin.add(dx, dy, dz), block.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    // 实操:从零一块黑曜石。自然世界黑曜石须"找岩浆湖+浇水"——bot 目前没有这个能力,
    // 本场景预期 FAIL,留作能力缺失的存证与修复目标(修好后转绿)。
    private static Result assignRealObsidian(AIPlayerEntity bot) {
        prepareRealistic(bot);
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(实操死一次=大事故)
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OBSIDIAN, 1));
        if (!started) {
            return Result.fail("real_obsidian", "goal_submit_failed");
        }
        return Result.runningGoal("real_obsidian", 12000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OBSIDIAN) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 实操:自然地形长距离导航——目标=东边 120 格的自然地表点(中途可能有湖/崖/密林,考验绕行与容错)。
    // 不验证路径漂不漂亮,只验证"能到":距目标 ≤3 格即过。这是所有 real_*"走过去干活"的共同前置能力,
    // 单拎出来测,导航挂了能直接定位是"走路"问题而不是采集/合成问题。
    private static Result assignRealNavFar(AIPlayerEntity bot) {
        BlockPos start = prepareRealistic(bot);
        ServerWorld world = bot.getServerWorld();
        int gx = start.getX() + 120;
        int gz = start.getZ();
        // 用 MOTION_BLOCKING 堆叠图取自然地表落脚 y(含树叶/水面),与"玩家肉眼选个地表点"一致
        int gy = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, gx, gz);
        BlockPos goal = new BlockPos(gx, gy, gz);
        return assignTask(bot, "real_nav_far", new MoveTask(bot, goal), 6000,
                ignored -> bot.isAlive() && bot.getBlockPos().getSquaredDistance(goal) <= 9.0D);
    }

    // ==================== R2 LLM 全链层(llm_*) ====================
    // 这层测"中文口语指令 → DeepSeek 意图解析 → 工具选择 → 参数化 → 执行"的完整实操链路:
    // 入口与玩家在聊天里 @bot 说话完全相同(BrainCoordinator.handleMessage,server 线程进、
    // 异步调 DeepSeek、响应回 server 线程执行工具)。必须 WITH_LLM=1 跑——test 脚本默认
    // unset DEEPSEEK_API_KEY 隔离大脑,防确定性套件偷偷计费;这也是 llm_* 不进 ALL_FEATURES 的原因。
    // 判定一律用 patient 模式(见 pollActive):大脑是会话式驱动,会连续派发多个任务、失败换法重试、
    // 任务间空闲思考——单任务 COMPLETED/FAILED 都不是场景终局,只认"世界状态断言达成"或超时。
    // 开局与 real_* 同一标准:prepareRealistic 自然世界零给予 + deathBase 零死亡红线。

    /**
     * llm_* 公共开局:复位大脑会话/目标计划/遗留任务 → prepareRealistic(自然世界零给予)→
     * 中文指令经 handleMessage 递给大脑(与玩家聊天 @bot 同一入口)。
     * 复位原因:上一个 llm 场景断言达成时大脑往往仍在续航思考(busy),busy 下 handleMessage
     * 拒收新消息返回 false,不复位会套件串台误判;resetToIdle 顺带清掉遗留失败记录,
     * 防新会话开局就被注入上一场景的"上一个任务失败"。
     * 前置查 key:key 缺失时 handleMessage 照样返回 true(异步请求才报 deepseek_api_key_missing),
     * 不查就得干等满 timeout 才 FAIL。返回 null=指令已提交;非 null=应立即记录的 FAIL。
     */
    private static Result startLlmScenario(AIPlayerEntity bot, String feature, String instruction) {
        BrainCoordinator.INSTANCE.reset(bot);
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.resetToIdle(bot);
        prepareRealistic(bot);
        if (AIBotConfig.get().deepseek().apiKey().isBlank()
                || !BrainCoordinator.INSTANCE.handleMessage(bot, "Tester", instruction)) {
            return Result.fail(feature, "brain_rejected_or_not_configured (run WITH_LLM=1)");
        }
        return null;
    }

    // 实操(LLM):口语化移动指令 → 大脑应解析出"去 (120, z=0) 附近"的意图并选移动类工具(move_to 等)。
    // 断言只看世界结果:bot 与 (120, 0) 的水平距离 ≤ 8 格(忽略 y,落脚高度由自然地形决定)且零死亡。
    private static Result assignLlmMove(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_move", "走到坐标 x=120 z=0 附近去");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(照抄 real_* 标准)
        return Result.runningPatient("llm_move", 6000,
                ignored -> {
                    double dx = bot.getX() - 120.0D;
                    double dz = bot.getZ();
                    return bot.isAlive() && dx * dx + dz * dz <= 64.0D && deathCount(bot) == deathBase;
                });
    }

    // 实操(LLM):口语化食物指令 → 大脑应解析"至少 4 个熟食"的意图与数量参数,自主择源
    // (打猎+烤/种田做面包/觅食),与 real_food 同一世界标准但驱动方是真实大脑而非直接 submit Goal。
    private static Result assignLlmFood(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_food", "去搞点吃的回来,至少弄到 4 个熟食");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(照抄 real_* 标准)
        return Result.runningPatient("llm_food", 16000,
                ignored -> bot.isAlive() && cookedFoodCount(bot) >= 4 && deathCount(bot) == deathBase);
    }

    // 实操(LLM):口语化矿物指令 → 大脑应把"挖一块铁锭"映射到 achieve_goal/mine_ore 全链
    // (砍树→木镐→挖石→石镐→找铁→挖→做炉→熔炼),与 real_iron 同一世界标准。
    private static Result assignLlmIron(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_iron", "帮我挖一块铁锭回来");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot); // 零死亡红线:死亡重生也判 FAIL(照抄 real_* 标准)
        return Result.runningPatient("llm_iron", 24000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 实操(LLM)·旗舰:口语化钻石指令 → 大脑应跑通整条深链(铁镐前置→下潜深层→地下探矿→挖钻石)。
    // 这是真实地形最深的缺口(campaign 实测 real_diamond 两 seed no_resource):地表觅食已被 EXPLORE
    // 解决,地下定向探矿尚缺。用真实对话层验收,卡点即下一个该修的能力(数据驱动,不靠猜)。
    // 超时给足(地下找钻石本就慢);零死亡红线照旧——深层岩浆/坠落/怪一次失误都判 FAIL,逼出真实鲁棒性。
    private static Result assignLlmDiamond(AIPlayerEntity bot) {
        Result rejected = startLlmScenario(bot, "llm_diamond", "帮我挖一颗钻石回来");
        if (rejected != null) {
            return rejected;
        }
        final int deathBase = deathCount(bot);
        return Result.runningPatient("llm_diamond", 48000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1
                        && deathCount(bot) == deathBase);
    }

    // ==================== 对话式助手层(assistant_suite) ====================
    // 验证助手层四块新地基(此前只编译过、零运行验证):P0 目标队列 / P1 Goal.Build 自动备料 /
    // P3 参数化蓝图 / P2 玩家消息保留进行中目标。全部确定性实验室场景(prepareArea 人造平台),
    // 不走 LLM、不烧 API——测的是助手层的执行根基,大脑驱动的全链由 llm_suite 单独覆盖。

    /**
     * P0 目标队列端到端:连续 submit 两个目标——第一个(木棍×4)立即开工;第二个(工作台×1)在
     * 活跃目标存在时应走 GoalExecutor.goalQueue **入队**且返回 true(返回 false=队列回归,立即 FAIL);
     * 第一个完成后 advanceQueue 自动出队衔接执行第二个。4 原木够两条链:棍链耗 1 木(→4 板,2 板成 4 棍),
     * 台链再耗 1 木(剩 2 板补 4 板→工作台)。断言两个目标的产物**同时到手**(木棍≥4 且工作台≥1)
     * 且零死亡——只有第二个目标真被接续执行了,断言才可能成立。
     */
    private static Result assignGoalQueue(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 4));
        final int deathBase = deathCount(bot); // 零死亡红线(照抄 real_* 标准)
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.STICK, 4))) {
            return Result.fail("goal_queue", "goal_submit_failed");
        }
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.CRAFTING_TABLE, 1))) {
            return Result.fail("goal_queue", "second_submit_rejected"); // P0 回归:第二目标应入队返回 true
        }
        return Result.runningGoal("goal_queue", 4800,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.STICK) >= 4
                        && InventoryAction.countItem(bot, Items.CRAFTING_TABLE) >= 1
                        && deathCount(bot) == deathBase);
    }

    /**
     * P1 Goal.Build 自动备料端到端:只给 32 原木、零木板——ensureBuild 必须自己按蓝图逐格统计出
     * 需 114 块木板(small_hut 实测口径,见 assignBuild 注释),倒推出"原木→木板"CRAFT 步并全部合成,
     * 再由 BUILD 步把房盖起来(纯建造已由 build 场景覆盖,本场景钉的是备料链)。
     * 断言:origin ±14、y∈[origin.y, origin.y+8] 的木板家族方块 ≥80(全房 112 块,留余量)且零死亡。
     * above 口径理由见 countNearbyBlocksAbove(木板虽不与石地板同族,两个建房场景统一口径更稳)。
     */
    private static Result assignGoalBuildAuto(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG, 32)); // 只给原木:114 板必须自己算出来并合成
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        final int deathBase = deathCount(bot);
        java.util.Set<Block> plankBlocks = new java.util.HashSet<>();
        for (Item planks : io.github.zoyluo.aibot.craft.RecipeRegistry.PLANKS) {
            Block block = Block.getBlockFromItem(planks);
            if (block != Blocks.AIR) {
                plankBlocks.add(block);
            }
        }
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.Build("small_hut"))) {
            return Result.fail("goal_build_auto", "goal_submit_failed");
        }
        return Result.runningGoal("goal_build_auto", 9600,
                ignored -> bot.isAlive()
                        && countNearbyBlocksAbove(world, origin, 14, plankBlocks) >= 80
                        && deathCount(bot) == deathBase);
    }

    /**
     * P3 参数化蓝图端到端:Goal.Build("custom:5x4x3:stone_like") 不读蓝图文件,由
     * BlueprintSchema.parametricHouse 按规格生成(外径 5×4、墙净高 3:地板20+墙42-门2+顶20=80 格,
     * palette=stone_like)。给足 128 圆石(备料链判"已满足"零采集,聚焦参数化几何+palette 建造)。
     * 断言:±14、y∈[origin.y, origin.y+8] 的 stone_like 建材(圆石/石头/石砖)≥40(半房即过,容忍
     * 个别格缺失)且零死亡。必须 above 口径:实验室平台地板(y-1 圆石、其下 16 层实心石)与建材同族,
     * 数进去会把"没盖房"误判成 PASS——房子地板层恰落在 origin.y(SiteFinder 锚在可站立脚位),零损失。
     */
    private static Result assignGoalBuildCustom(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 128));
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        final int deathBase = deathCount(bot);
        java.util.Set<Block> stoneLike = java.util.Set.of(Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS);
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.Build("custom:5x4x3:stone_like"))) {
            return Result.fail("goal_build_custom", "goal_submit_failed");
        }
        return Result.runningGoal("goal_build_custom", 7200,
                ignored -> bot.isAlive()
                        && countNearbyBlocksAbove(world, origin, 14, stoneLike) >= 40
                        && deathCount(bot) == deathBase);
    }

    /**
     * P2 打断保留目标(机制层,不走 LLM 决策):提交挖圆石目标(平台下全是人造石,DigDownTask 要挖
     * 几百 tick),趁执行中用 BrainCoordinator.handleMessage 模拟玩家闲聊——与玩家聊天 @bot 完全同一入口。
     * P2 语义:有活跃 plan 时新消息**不清目标**只解 busy(旧行为"新消息=重定向,清目标"会把正在挖的
     * 目标直接杀掉)。消息后立即断言 hasActivePlan 仍为 true(false=P2 回归);再以"目标照常完成"
     * (圆石≥6 且零死亡)收尾——保留语义不只是没清,还得真的继续干完。无 DEEPSEEK key 时 handleMessage
     * 异步才报 key 缺失,同步路径照走,不影响本验证。
     */
    // ==================== 地形矩阵(②):同一挖矿任务 × 多种几何 ====================
    // 让"地形组合爆炸"发生在无头测试里而不是玩家存档里——实操撞到的"山体侧面矿挖洞不进洞"
    // 本该是矩阵第二行(slope)。全部断言:统一接近原语(挖掘感知寻路)能走/挖到矿并采到 1 个 raw_iron。
    private static Result assignMineGeo(AIPlayerEntity bot, String geo) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        switch (geo) {
            // 垂直埋矿:脚下 3 格(旧实验室基线)
            case "vertical" -> world.setBlockState(origin.down(3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
            // 山体侧面:8 格外堆一座 6 高石坡,矿嵌在坡面里(实操三连败的复刻)
            case "slope" -> {
                for (int dx = 0; dx <= 6; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = 0; dy <= dx; dy++) {
                            world.setBlockState(origin.add(8 + dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                world.setBlockState(origin.add(11, 3, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
            // 悬空头顶:矿在头顶 5 格的石板底面(要搭柱/挖不到就垫脚——考验垂直接近)
            case "overhang" -> {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        world.setBlockState(origin.add(dx, 5, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                    }
                }
                world.setBlockState(origin.add(0, 5, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16)); // 垫脚材料
            }
            // 隔墙:bot 与矿之间 3 厚石墙(必须穿墙)
            case "wall" -> {
                for (int dx = 3; dx <= 5; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                world.setBlockState(origin.add(7, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
            // 全包裹口袋:矿在 6 格外完全嵌进实心石立方中心(终点豁免的直接考题)
            case "pocket" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                world.setBlockState(origin.add(7, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
            // 深层斜下:矿在斜下方 6 格(深板岩铁矿,考验斜向下挖)。火把给足:strip 巷道定距照明
            // (ore_dig_torch)在这行顺带验证——黑暗巷道里每 10 格该见一支。
            case "deep" -> {
                world.setBlockState(origin.add(4, -6, 4), Blocks.DEEPSLATE_IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 16));
            }
            // P0 验证·岩浆邻接:全包裹矿的东面贴一格岩浆源——预检该让 A* 从北/南/上等安全面进,
            // 挖错面=岩浆涌入烧死(零死亡断言抓)。
            case "lava" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                world.setBlockState(origin.add(7, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(8, 1, 0), Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL); // 矿东面贴岩浆
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8)); // 封堵材料(真实玩家身上总有圆石)
            }
            // R9 流动水贴矿(瀑布脚):水源悬在矿邻位上方 2 格,自然流下漫过矿东邻——流动水(非源)
            // 的 fluidState 同样 isIn(WATER),封堵/侧位链路应当一致生效;封掉流经格即可安全开挖。
            case "flow" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                world.setBlockState(origin.add(7, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(8, 1, 0), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(8, 2, 0), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(8, 3, 0), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL); // 高处源,流抵矿邻
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
            }
            // R9 暗湖破壁:通往矿的 z=0/1 直线壁后藏水箱(挖穿即涌),z 负侧留干路——
            // 预检(digEnterable 流体邻位拒)应让 A* 自动绕干路,湖是绕出来的不是堵出来的。
            case "lake" -> {
                // (统一画布天然零矿零湖,人造水箱的破壁涌水考题不再被天然湖截胡。)
                for (int dx = 3; dx <= 9; dx++) {
                    for (int dz = -4; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                // 水箱:x=5..6, z=2..3 贴墙侧位(四周石壁已就位)。考题=主路侧旁有暗湖,
                // 掘进路过不被勾引/封堵不乱触发——z=0 直线仍可达矿。原 z=0..1 正压主路:
                // 预检"邻位无流体"会把 z=-1 贴箱列也全拒,A* 需绕 z=-2,80t 接近闸内
                // 常走不完(轮3b 实测 silent skip),考题失真为"绕行竞速"。
                for (int dx = 5; dx <= 6; dx++) {
                    for (int dz = 2; dz <= 3; dz++) {
                        for (int dy = 1; dy <= 2; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                world.setBlockState(origin.add(9, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
            }
            // 含水矿:与 lava 行同构,流体换水源——挖开瞬间水涌入会推走 bot/掉落物淹巷道,
            // 统一危险流体封堵(ore_dig_fluid_seal)该先封水再挖。
            case "water" -> {
                for (int dx = 4; dx <= 9; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int dy = -1; dy <= 4; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                world.setBlockState(origin.add(7, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(8, 1, 0), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL); // 矿东面贴水源
                InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
            }
            // P0 验证·沙砾顶:穿墙必经段头顶悬 3 格沙砾柱(z=0 直线),预检该绕 z±1 安全列穿——
            // 直线穿=塌方砸头窒息(零死亡断言抓)。
            case "gravel" -> {
                for (int dx = 3; dx <= 5; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        for (int dy = 0; dy <= 3; dy++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                for (int dx = 3; dx <= 5; dx++) {
                    for (int dy = 4; dy <= 6; dy++) {
                        world.setBlockState(origin.add(dx, dy, 0), Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
                    }
                }
                world.setBlockState(origin.add(7, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
            // P0 验证·背包满:36 格塞满圆石开局——dropJunk 应腾位让矿捡得起(否则白挖到超时)。
            case "fullinv" -> {
                for (int i = 0; i < 36; i++) {
                    InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
                }
                world.setBlockState(origin.down(3), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
            // 竖井旁下沉矿(real_iron seed777 确定性复现):矿在下方5/横向10 的实心石里,
            // 身旁 2 格留一条到底开放竖井(模拟 bot 刚挖完 dig_down 的遗留井)。real 实测:
            // bot 破14块全在出发 Y 层横向掘进、不下沉到矿的 Y → no_progress。geo_deep(纯实心斜下)
            // 却 PASS,差异就在这条混合地形的开放井。修好接近器下沉,此场景与 real_iron 同时转绿。
            case "shaft" -> {
                world.setBlockState(origin.add(10, -5, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy >= -8; dy--) {
                    world.setBlockState(origin.add(2, dy, 0), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
            // 空腔下沉矿(real_iron seed777 二号假设):bot 与下沉矿之间横亘一个 5x4x5 洞穴。
            // 假设:接近器 A* 见空腔走 WALK 进洞,落在洞底后从错误角度面对矿,横向掘进卡死。
            // geo_shaft(纯实心)PASS 而本场景若红 → 空气缺口即真因。
            case "cave" -> {
                world.setBlockState(origin.add(10, -5, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dx = 3; dx <= 7; dx++) {
                    for (int dy = -1; dy >= -4; dy--) {
                        for (int dz = -2; dz <= 2; dz++) {
                            world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
            }
            default -> {
                return Result.fail("geo_" + geo, "unknown_geometry");
            }
        }
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_" + geo, "goal_submit_failed");
        }
        return Result.runningGoal("geo_" + geo, 3600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1
                        && deathCount(bot) == deathBase);
    }

    // P1 富矿区导向:近处(64 格)无矿,但知识库记得 80 格外的富矿簇(预热 3 个资源点)——
    // prospect 兜底应直奔富区而非盲目掘进。断言:拿到 80 格外埋的真矿。
    private static Result assignGeoRich(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // (高空前提已由统一画布满足:prospect 64 球内天然零矿,富区导向考题不再被截胡。)
        // 富区:80 格外,3 个资源点记忆(20 格内成簇)+真矿一块;沿途铺石走廊保通(贫瘠带,64 格内无矿)
        BlockPos rich = origin.add(80, 0, 0);
        for (int dx = 0; dx <= 82; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlockState(origin.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(rich, Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        // 平台铺完才上人(先铺后传,防坠落窗口)
        bot.teleport(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
        bot.fallDistance = 0.0F;
        io.github.zoyluo.aibot.memory.EpisodeLog log = io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE;
        log.record(bot, io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND, rich.add(0, 0, 10), "minecraft:iron_ore");
        log.record(bot, io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND, rich.add(10, 0, 0), "minecraft:iron_ore");
        log.record(bot, io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND, rich.add(0, 0, -10), "minecraft:iron_ore");
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_rich", "goal_submit_failed");
        }
        return Result.runningGoal("geo_rich", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 顺路矿(R3):目标铁矿 6 格外,通往它的隧道壁上嵌 2 块煤矿(±1 伸手位)——断言铁照采、
    // 煤顺手白捡(ore_dig_bonus),改行追脉/绕路都算输(预算与 reach 约束兜着)。
    private static Result assignGeoBonus(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 6, 8);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // 目标铁矿:东 6 格同层
        world.setBlockState(origin.add(6, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        // 顺路煤:通道两壁(挖隧道经过时进入 ±2 扫描窗)
        world.setBlockState(origin.add(2, 1, 1), Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.add(4, 0, -1), Blocks.COAL_ORE.getDefaultState(), Block.NOTIFY_ALL);
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_bonus", "goal_submit_failed");
        }
        return Result.runningGoal("geo_bonus", 3600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 1
                        && InventoryAction.countItem(bot, Items.COAL) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 挖矿归仓(R4):base 旁放箱,目标=挖 1 铁并入库。两连 goal(MineOre→queue Stockpile),
    // 断言箱内 RAW_IRON≥1(不是背包——拿在手里不算归仓)。
    private static Result assignGeoStockpile(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        fillStoneCube(world, origin, 4, 8);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        world.setBlockState(origin.add(5, 1, 0), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        // 基地:脚边 mark + 箱子
        io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid())
                .markPlace("base", world, origin);
        BlockPos chest = origin.add(-2, 0, 0);
        world.setBlockState(chest, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 1));
        if (!started) {
            return Result.fail("geo_stockpile", "goal_submit_failed");
        }
        GoalExecutor.INSTANCE.submit(bot, new Goal.Stockpile(Items.RAW_IRON, 1));
        return Result.running("geo_stockpile", 4800, ignored -> {
            if (!bot.isAlive()) {
                return false;
            }
            var inv = io.github.zoyluo.aibot.action.ContainerAction.resolve(bot, chest).orElse(null);
            if (inv == null) {
                return false;
            }
            for (int i = 0; i < inv.size(); i++) {
                if (inv.getStack(i).isOf(Items.RAW_IRON)) {
                    return true;
                }
            }
            return false;
        });
    }

    // 续挖(R6/R7):预置 mine_face 地标+矿种记忆于 35 格外(矿就埋在作业面旁),复刻 resume_mining
    // 工具体(MoveTask 回面+MineOre 排队),断言走回去挖到矿——验证地标记忆与任务/目标衔接语义。
    private static Result assignGeoResume(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // 作业面:东 35 格,走廊保通,面旁嵌 2 铁矿
        BlockPos face = origin.add(35, 0, 0);
        for (int dx = 0; dx <= 37; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlockState(origin.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        // 画布规则:矿至少 y+1——放地面层(y+0)时 approach goal=矿.down 落到唯一石板层之下的
        // 虚空,支撑检查全拒 → TIMEOUT 连环 skip(轮4b 实测)。垫一格石头当矿座。
        world.setBlockState(face.add(1, 0, 1), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(face.add(1, 0, -1), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(face.add(1, 1, 1), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(face.add(1, 1, -1), Blocks.IRON_ORE.getDefaultState(), Block.NOTIFY_ALL);
        var mem = io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid());
        mem.markPlace("mine_face", world, face);
        mem.remember("mine_face_ores", "minecraft:iron_ore");
        // 复刻 resume_mining 工具体
        TaskManager.INSTANCE.assign(bot, new io.github.zoyluo.aibot.task.MoveTask(bot, face));
        GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.IRON_ORE), 2));
        final int deathBase = deathCount(bot);
        return Result.running("geo_resume", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.RAW_IRON) >= 2
                        && deathCount(bot) == deathBase);
    }

    // 统一生存层(二期 V1):水底站桩(HoldTask 无任何私有熔断——兜底语义的最严考题),
    // air 耗向阈值时 SurvivalGuard 该斩任务,NavSafetyNet 上浮,bot 活命。断言:任务以
    // guard_drowning 终止+bot 存活——"任何任务都不能比统一层做得更差"。
    private static Result assignGeoGuard(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos(); // 统一画布已是高空,天然水体零干扰
        // 石壁水井:内腔 1x1、深 4,bot 沉底,头顶 3 格水(上不来才算真淹)
        for (int dy = -1; dy <= 4; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlockState(origin.add(dx, dy, dz),
                            (dx == 0 && dz == 0 && dy >= 0 && dy <= 3)
                                    ? Blocks.WATER.getDefaultState()
                                    : Blocks.STONE.getDefaultState(),
                            Block.NOTIFY_ALL);
                }
            }
        }
        bot.teleport(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
        bot.fallDistance = 0.0F;
        bot.setAir(120); // 压缩等待:从 120 起跌,~1 秒到阈值(全程 300 要白等 10 秒)
        TaskManager.INSTANCE.assign(bot, new io.github.zoyluo.aibot.task.HoldTask());
        // 反向场景:guard 斩任务=干净 FAILED 即 PASS(detail 自带失败原因可核对是 guard_drowning);
        // 任务傻跑到超时(=统一层没兜住)才是 FAIL。
        return Result.runningExpectCleanFail("geo_guard", 1200);
    }

    // 资源探索(EXPLORE):近处(48 格 survey + 96 格 prospect)零树,唯一的树簇在 120 格外走廊
    // 尽头——roam 的 28 格乒乓够不着,必须靠 EXPLORE 定向大步走出去才找得到。画布天然零树
    //(prepareArea 统一画布),无需清场。断言:存活且原木族(白桦/橡木)计数 ≥4。
    private static Result assignExploreWood(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        // 石板走廊通到 120 格外(dy-1 铺石、dy0..2 清空,与 geo_rich 走廊同构):保证树簇物理可达。
        for (int dx = 0; dx <= 124; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlockState(origin.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        // 树簇:3 根 2 格高白桦柱(合计 6 logs,凑 4 根目标有余量;走廊已清空,后立柱不被覆盖)。
        for (BlockPos base : new BlockPos[]{origin.add(120, 0, 0), origin.add(121, 0, 1), origin.add(121, 0, -1)}) {
            world.setBlockState(base, Blocks.BIRCH_LOG.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(base.up(), Blocks.BIRCH_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        // HaveItem(OAK_LOG) 对原木族宽容:GatherQuotaTask.acceptItemsFor 对 LOGS 族返回整族
        //(任意树种计数),白桦照样推进进度——所以采的是 BIRCH、目标写 OAK 也能完成。
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_LOG, 4));
        if (!started) {
            return Result.fail("explore_wood", "goal_submit_failed");
        }
        return Result.runningGoal("explore_wood", 6000,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.BIRCH_LOG) + InventoryAction.countItem(bot, Items.OAK_LOG) >= 4);
    }

    // 死亡找回(R1):带高辨识物资被一击致死,断言重生反射自动跑尸、despawn 前把铁锭捡回背包。
    // 灯下黑校验项:掉落确实生成(本 mod respawn 不恢复背包,原版 dropInventory 掉在死亡点)。
    private static Result assignGeoRecover(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 5));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        // 一击致死:走原版死亡流程(掉落生成);setHealth(0) 不触发 onDeath 掉落,必须走 damage。
        bot.damage(world, world.getDamageSources().generic(), 1000.0F);
        return Result.running("geo_recover", 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.IRON_INGOT) >= 5);
    }

    // 运行时配方索引端到端:OAK_TRAPDOOR 不在手写表(grep 确认),只能靠 RuntimeRecipeIndex 从
    // RecipeManager 学来的配方(6 板)倒推合成——模组物品走同一路径,这里用 vanilla 表外物品代证。
    private static Result assignCraftRuntime(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_TRAPDOOR, 1));
        if (!started) {
            return Result.fail("craft_runtime", "goal_submit_failed(运行时索引未让规划器认识活板门)");
        }
        return Result.runningGoal("craft_runtime", 2400,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OAK_TRAPDOOR) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 记忆/知识子系统 API 冒烟(同步,一次判定):情景流入死亡×2 同点 → 蒸馏出危险区(单次不立牌);
    // 资源发现×2 同点 → 去重只记一条;落盘文件存在。纯 API 行为,不跑任务。
    private static Result assignKnowledgeSmoke(AIPlayerEntity bot) {
        io.github.zoyluo.aibot.memory.KnowledgeBase kb = io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE;
        io.github.zoyluo.aibot.memory.EpisodeLog log = io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE;
        BlockPos spot = bot.getBlockPos().add(1000, 0, 1000); // 远离实际活动区,不污染后续场景
        int dangersBefore = kb.dangerCount(bot.getUuid());
        log.record(bot, io.github.zoyluo.aibot.memory.EpisodeLog.Type.DEATH, spot, "smoke_test");
        if (kb.dangerCount(bot.getUuid()) != dangersBefore) {
            return Result.fail("knowledge_smoke", "single_death_created_zone(应两次才立牌)");
        }
        log.record(bot, io.github.zoyluo.aibot.memory.EpisodeLog.Type.DEATH, spot.add(3, 0, 3), "smoke_test");
        if (!kb.isDanger(bot.getUuid(), spot)) {
            return Result.fail("knowledge_smoke", "two_deaths_no_zone(聚类蒸馏未生效)");
        }
        int resBefore = kb.resourceCount(bot.getUuid());
        log.record(bot, io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND, spot.add(50, 0, 0), "minecraft:iron_ore");
        log.record(bot, io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND, spot.add(52, 0, 2), "minecraft:iron_ore");
        if (kb.resourceCount(bot.getUuid()) != resBefore + 1) {
            return Result.fail("knowledge_smoke", "resource_dedup_failed(8 格内同矿应去重)");
        }
        if (kb.nearestResource(bot.getUuid(), "minecraft:iron_ore", spot.add(40, 0, 0), 96).isEmpty()) {
            return Result.fail("knowledge_smoke", "nearest_resource_miss");
        }
        return Result.pass("knowledge_smoke", "distill+dedup+query ok, dangers=" + kb.dangerCount(bot.getUuid())
                + " resources=" + kb.resourceCount(bot.getUuid()));
    }

    // L1 接线回归(不烧 key):直接喂工具调用给各高层工具 handler,断言"选对工具+传对参 → 映射到对的 Goal 且提交成功
    // (goal_assigned)"。只测接线/参数/映射,不实际执行(每个提交完即 clear)。把这条链锁成确定性回归,防以后改坏。
    // 不替代 llm_*(那验真 DeepSeek 选不选得对、烧 key);本测验的是【选对之后接线对不对】。
    private static Result assignToolDispatch(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.abort(bot);
        io.github.zoyluo.aibot.brain.ToolRegistry reg = new io.github.zoyluo.aibot.brain.ToolRegistry();
        record Case(String tool, String args) {
        }
        java.util.List<Case> cases = java.util.List.of(
                new Case("mine_ore", "{\"ore\":\"minecraft:iron_ore\",\"count\":3}"),
                new Case("achieve_goal", "{\"item\":\"minecraft:iron_pickaxe\"}"),
                new Case("harvest_crop", "{\"crop\":\"wheat\"}"),
                new Case("provision_food", "{\"count\":4}"),
                new Case("forage", "{}"),
                new Case("achieve_armor", "{}"),
                new Case("achieve_workstation", "{}"),
                new Case("stockpile", "{\"item\":\"minecraft:cobblestone\",\"count\":10}"),
                new Case("build_house", "{\"width\":7,\"material\":\"stone_like\"}"),
                new Case("build_house", "{}"));
        StringBuilder fails = new StringBuilder();
        int ok = 0;
        for (Case c : cases) {
            io.github.zoyluo.aibot.brain.ToolDefinition def = reg.get(c.tool()).orElse(null);
            if (def == null) {
                fails.append(c.tool()).append(":unregistered; ");
                continue;
            }
            io.github.zoyluo.aibot.brain.ToolDefinition.ToolResult r;
            try {
                com.google.gson.JsonObject args = com.google.gson.JsonParser.parseString(c.args()).getAsJsonObject();
                r = def.handler().invoke(bot, args);
            } catch (RuntimeException e) {
                fails.append(c.tool()).append(":threw(").append(e.getClass().getSimpleName()).append("); ");
                GoalExecutor.INSTANCE.clear(bot);
                TaskManager.INSTANCE.abort(bot);
                continue;
            }
            if (r != null && r.ok() && r.message() != null && r.message().contains("goal_assigned")) {
                ok++;
            } else {
                fails.append(c.tool()).append("=").append(r == null ? "null" : r.message()).append("; ");
            }
            GoalExecutor.INSTANCE.clear(bot); // 只测接线:清掉刚提交的目标,不实际执行
            TaskManager.INSTANCE.abort(bot);
        }
        return fails.length() == 0
                ? Result.pass("tool_dispatch", ok + "/" + cases.size() + " 高层工具→Goal 接线/参数映射全通")
                : Result.fail("tool_dispatch", ok + "/" + cases.size() + " ok; FAIL: " + fails.toString().trim());
    }

    private static Result assignMsgKeepGoal(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));
        final int deathBase = deathCount(bot);
        if (!GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.COBBLESTONE, 6))) {
            return Result.fail("msg_keep_goal", "goal_submit_failed");
        }
        BrainCoordinator.INSTANCE.handleMessage(bot, "Tester", "你在干嘛呢");
        if (!GoalExecutor.INSTANCE.hasActivePlan(bot)) {
            return Result.fail("msg_keep_goal", "goal_cleared_by_message"); // P2 回归:玩家消息把进行中目标清了
        }
        return Result.runningGoal("msg_keep_goal", 2400,
                ignored -> bot.isAlive()
                        && InventoryAction.countItem(bot, Items.COBBLESTONE) >= 6
                        && deathCount(bot) == deathBase);
    }

    // 数 center 水平 ±r、竖直 [center.y, center.y+8] 范围内属于 targets 任一方块的格数(建房断言用)。
    // 故意只数 center.y 及以上(above 口径):prepareArea 实验室平台的地板/地基(y-1 圆石、其下 16 层
    // 实心石)与 stone_like 建材同族,数进去会把"没盖房"误判成达标;房子地板层恰好落在锚点脚位 y
    // (=origin.y,SiteFinder 选址取可站立格),above 口径对建筑本体零损失。
    private static int countNearbyBlocksAbove(ServerWorld world, BlockPos center, int r, java.util.Set<Block> targets) {
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = 0; dy <= 8; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (targets.contains(world.getBlockState(center.add(dx, dy, dz)).getBlock())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    // 对称垂直扫描(center 上下 yLo..yHi):real_build 专用——蓝图 SiteFinder 自动选址,房子可能落在出生点
    // 远处/下方(实测建在 265,63,587 而出生点在别处),以出生点为锚的 "only above" 数不到建好的房 → 建满
    // 116/116 零死亡却误判 assertion_failed。real 地形下方是土/石不与 plank 同族,绕"真实建房点"(完工时 bot
    // 就在房里)对称扫描只数真木板、无假平台问题(不能用于 lab 建房场景:那里平台地板与建材同族,见
    // countNearbyBlocksAbove 注释)。仍要求 ≥80 块真木板+零死亡+存活,只是把计数锚点对准真实建房点。
    private static int countNearbyBlocks(ServerWorld world, BlockPos center, int r, int yLo, int yHi,
                                         java.util.Set<Block> targets) {
        int count = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = yLo; dy <= yHi; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (targets.contains(world.getBlockState(center.add(dx, dy, dz)).getBlock())) {
                        count++;
                    }
                }
            }
        }
        return count;
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
        return Result.runningGoal("achieve_armor", 16000,
                ignored -> bot.isAlive()
                        && hasGear(bot, Items.IRON_CHESTPLATE)
                        && hasGear(bot, Items.IRON_SWORD));
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
    // 夜间怪海保命(A 前沿,确定性复现 real_diamond 死亡螺旋):夜晚+低血(8)+3 僵尸围攻+给圆石无武器
    // (逼 shelter 非 combat)。断言:bot 把自己封进墙里(头部四面非空)且存活——保命筑墙成功才达成;
    // 中途被打死则 deathCount 变,断言永不成立→超时 FAIL。验"濒死无视冷却立即筑墙"是否真救命。
    private static Result assignGeoNightSwarm(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        world.setTimeOfDay(13000L); // 夜:刷的僵尸不被晒死,持续围攻
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 32)); // 筑墙料(无武器逼 shelter)
        bot.setHealth(8.0F); // ≤ EMERGENCY_SHELTER_HP,触发保命筑墙
        for (int i = 0; i < 3; i++) {
            net.minecraft.entity.mob.ZombieEntity z = EntityType.ZOMBIE.create(world, SpawnReason.COMMAND);
            if (z != null) {
                z.setPersistent();
                double ang = i * 2.094D;
                z.refreshPositionAndAngles(bot.getX() + 2.0D * Math.cos(ang), bot.getY(),
                        bot.getZ() + 2.0D * Math.sin(ang), 0.0F, 0.0F);
                world.spawnEntity(z);
            }
        }
        TaskManager.INSTANCE.assign(bot, new io.github.zoyluo.aibot.task.HoldTask());
        final int deathBase = deathCount(bot);
        return Result.running("geo_night_swarm", 600, ignored -> {
            if (!bot.isAlive() || deathCount(bot) != deathBase) {
                return false; // 被打死=没救成
            }
            BlockPos h = bot.getBlockPos().up();
            int walls = 0;
            for (Direction d : Direction.Type.HORIZONTAL) {
                if (!world.getBlockState(h.offset(d)).isAir()) {
                    walls++;
                }
            }
            return walls >= 4; // 头部四面封住=保命筑墙成功
        });
    }

    // 崖壁采木(钻石 67% 失败的头号坎,确定性复现):    // 崖壁采木(钻石 67% 失败的头号坎,确定性复现):bot 在画布平台,树长在东侧一道**陡坑**底部
    // (与平台间隔一道 6 格垂直落差,纯步行 GOAL_UNREACHABLE)。断言 bot 升级挖掘接近、下沉够到、
    // 采足 3 木、零死亡。这是"任何地形都能采到木"→"任何地形都能挖钻石"的第一关。
    private static Result assignGeoCliffTree(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        // 东侧 dx 4..10 挖一道陡坑:坑口 y0 起向下清 6 格成竖壁,坑底 y-7 铺石地。
        for (int dx = 4; dx <= 10; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy >= -6; dy--) {
                    world.setBlockState(origin.add(dx, dy, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
                world.setBlockState(origin.add(dx, -7, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        // 坑底种 2 棵 4 高橡木(共 8 段),bot 必须下到坑底才够得到。
        for (int dy = -6; dy <= -3; dy++) {
            world.setBlockState(origin.add(7, dy, -1), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(origin.add(8, dy, 1), Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1)); // 给镐(挖掘接近要破石)
        final int target = 3;
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.OAK_LOG, target));
        if (!started) {
            return Result.fail("geo_cliff_tree", "goal_submit_failed");
        }
        return Result.runningGoal("geo_cliff_tree", 6000,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OAK_LOG) >= target
                        && deathCount(bot) == deathBase);
    }

    // 造黑曜石(新能力 L1):画布东侧 5×5 岩浆源池 + 钻石镐 + 4 水桶,**不预放黑曜石**(区别于
    // achieve_obsidian 作弊预放)。断言 bot 自主"水浇岩浆现造"+挖到 ≥4 块、零死亡。通了再调 15 压测。
    private static Result assignGeoObsidianMake(AIPlayerEntity bot) {
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        // 间隔摆的孤立岩浆源(每个隔 3 格,互不接触):证"造+挖"主能力,隔离掉多格连续岩浆的流体交互
        // (相邻源转黑曜石会触发邻格流体重算→扫不到,那是独立硬问题,真实洞底岩浆多为分散点/窄流)。
        int[] xs = {4, 7, 10, 13};
        for (int dx : xs) {
            world.setBlockState(origin.add(dx, 0, 0), Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.WATER_BUCKET, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        final int target = 3;
        final int deathBase = deathCount(bot);
        // 隔离验证新能力本体:直接派 CreateObsidianTask(绕开 planner 的备桶链——那条会在画布上触发
        // wood→iron 采集、把 bot 引到岩浆池边踩进去;planner 接线另由 Goal 级场景验)。
        return assignTask(bot, "geo_obsidian_make",
                new io.github.zoyluo.aibot.task.CreateObsidianTask(target), 9600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.OBSIDIAN) >= target
                        && deathCount(bot) == deathBase);
    }

    // 深层接近抖动·确定性复现(回放 seed777 捕获的确切失败几何,ore_dig_region 快照还原):
    // bot 在自挖空气隧道里(B 周围有空腔),钻石脉嵌 +X/+Z 实心石 5-6 格、目标 T 在最远端——
    // "开放隧道在侧+矿在前方实心"诱发 A* 在隧道/挖掘面间抖动(破块却不缩 dist→no_progress)。
    // 7 层 ASCII 按 X 段(|)、Z 字符排布;#实心 .空气 O矿 T目标。bot 站 B,给铁镐+深挖套件。
    private static final String[] REPLAY_ROWS_Y = { // index 0 = y+4 .. 6 = y-2 (相对 bot)
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // +4
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // +3
        "#############|#############|#############|#############|####.########|###...#######|###OO########|#############|#############|#############|#############|#############", // +2
        "#.###########|#...#########|###.#########|###.#########|####..#######|##....#######|##OOO########|##OO#########|#########T###|#############|#############|#############", // +1
        "#.###########|#...#########|###.#########|###B#########|#############|###..########|##OOO########|##O##########|#############|#############|#############|#############", // 0 (bot)
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // -1
        "#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############|#############", // -2
    };

    private static Result assignGeoReplayOre(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59); // 深层环境(Y-59 带),bot 落在 origin
        ServerWorld world = bot.getServerWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        // 还原网格:相对 bot(B)。dxMin=-3(seg0 是 X-3),dz 从 -3(char0)起;dy: 行0=+4 .. 行6=-2。
        for (int yi = 0; yi < REPLAY_ROWS_Y.length; yi++) {
            int dy = 4 - yi;
            String[] segs = REPLAY_ROWS_Y[yi].split("\\|");
            for (int xi = 0; xi < segs.length; xi++) {
                int dx = xi - 3; // seg3 = bot 的 X
                String seg = segs[xi];
                for (int zi = 0; zi < seg.length(); zi++) {
                    int dz = zi - 3; // char3 = bot 的 Z
                    char c = seg.charAt(zi);
                    BlockPos pos = origin.add(dx, dy, dz);
                    if (c == '#') {
                        world.setBlockState(pos, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                    } else if (c == '.' || c == 'B') {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                    } else if (c == 'O' || c == 'T') {
                        world.setBlockState(pos, Blocks.DEEPSLATE_DIAMOND_ORE.getDefaultState(), Block.NOTIFY_LISTENERS);
                    }
                }
            }
        }
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot,
                new Goal.MineOre(java.util.Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), 1));
        if (!started) {
            return Result.fail("geo_replay_ore", "goal_submit_failed");
        }
        return Result.runningGoal("geo_replay_ore", 4800,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 1
                        && deathCount(bot) == deathBase);
    }

    // 钻石≥3·深层岩浆(真实应用 L1):钻石带(Y-59)本就岩浆密布。    // 钻石≥3·深层岩浆(真实应用 L1):钻石带(Y-59)本就岩浆密布。3 块钻矿各贴一格岩浆源,
    // 逼出"深层岩浆 survival + 多目标连采"——钻石真实失败的头号嫌疑。给铁镐+深挖套件+补给(同
    // achieve_diamond 标准:不给钻石,镐是铁的,真去挖)。断言 ≥3 钻且零死亡(深层死一次=真事故)。
    private static Result assignGeoDiamondLava(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59);
        ServerWorld world = bot.getServerWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        // 3 块钻矿散布(隔开,逼真正的"采完一块奔下一块"),每块东侧贴岩浆源
        int[][] spots = {{3, -1, 0}, {-3, -1, 2}, {0, -2, -3}};
        for (int[] s : spots) {
            BlockPos ore = origin.add(s[0], s[1], s[2]);
            world.setBlockState(ore, Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(ore.east(), Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        }
        final int deathBase = deathCount(bot);
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 3));
        if (!started) {
            return Result.fail("geo_diamond_lava", "goal_submit_failed");
        }
        return Result.runningGoal("geo_diamond_lava", 9600,
                ignored -> bot.isAlive() && InventoryAction.countItem(bot, Items.DIAMOND) >= 3
                        && deathCount(bot) == deathBase);
    }

    // 钻石(深层矿,需铁镐):传送到钻石矿层(-59)、脚下埋钻石矿,给铁镐+深矿安全装+供给 → 挖钻石矿得钻石。
    private static Result assignAchieveDiamond(AIPlayerEntity bot) {
        clearInventory(bot);
        BlockPos origin = prepareDeepArea(bot, -59);
        ServerWorld world = bot.getServerWorld();
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        giveDeepMineKit(bot);
        giveDeepMineSupplies(bot);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(origin.add(dx, -2, dz), Blocks.DIAMOND_ORE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        boolean started = GoalExecutor.INSTANCE.submit(bot, new Goal.HaveItem(Items.DIAMOND, 1));
        if (!started) {
            return Result.fail("achieve_diamond", "goal_submit_failed");
        }
        return Result.runningGoal("achieve_diamond", 8000,
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

    /**
     * 寻路容错①:被困逃生。4 高环形石墙把 bot 围死(水平绕路不存在),手里只有 32 泥土——
     * MoveTask 要么搭柱翻墙、要么徒手挖穿墙,哪条活路都行,断言最终站到墙外目标点。
     * 这是实操"掉坑/被地形圈死"的最小复现:寻路必须把"垫方块/拆方块"当合法走法,纯平面 A* 会判死路空转。
     */
    private static Result assignNavPillarOut(AIPlayerEntity bot) {
        surfaceTeleport(bot); // 必须地表化:y6 黑暗地下摆围墙会触发 dark_trap_escape 保命传送顶掉被测逃生(实测 aborted)
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin); // 无装备 bot 被圈在墙内,y6 怪海进来就是死局;清掉隔离被测的逃生逻辑
        // 先把活动空间清大:墙顶(y+3)之上还要 2 格头部空间才翻得过去,墙外到目标也要有落脚地——
        // dev 世界 y6 四周是原生石头,不清的话测的就不是"会不会自救"而是"被地形捉弄"。
        for (BlockPos pos : BlockPos.iterate(origin.add(-6, 0, -6), origin.add(10, 6, 6))) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (BlockPos pos : BlockPos.iterate(origin.add(-6, -1, -6), origin.add(10, -1, 6))) {
            world.setBlockState(pos, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        // 5×5 环形石墙(高 4)围死 bot:内圈 3×3 留空气,|dx|==2 或 |dz|==2 的一圈砌 STONE。
        for (int dy = 0; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        world.setBlockState(origin.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                    }
                }
            }
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 32)); // 搭柱材料管够;不给镐——徒手挖墙也算一条活路
        BlockPos goal = origin.offset(Direction.EAST, 8);
        return assignTask(bot, "nav_pillar_out", new MoveTask(bot, goal), 2400,
                ignored -> bot.isAlive() && bot.getBlockPos().getSquaredDistance(goal) <= 9.0D);
    }

    /**
     * 寻路容错②:活埋脱困。把 bot 脚位+头位直接灌成 STONE(模拟塌方/被挤进墙体),bot 正在窒息掉血。
     * 提交一个普通 MoveTask,NavSafetyNet 的窒息脱困应抢先把身位方块拆掉、人挖出来再走。
     * 断言完成时 bot 活着且脚位+头位都无碰撞体——必须真挖出来,不能只看任务状态糊弄
     * (任务可能在身体仍卡在方块里磨血时就被判完成)。
     */
    private static Result assignNavBuriedEscape(AIPlayerEntity bot) {
        surfaceTeleport(bot); // 地表化,防 y6 黑暗触发 dark_trap_escape 保命传送干扰被测脱困
        prepareArea(bot);
        clearInventory(bot);
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin); // 脱困后 bot 残血,y6 怪海一箭就翻车;清掉保证测的是脱困本身
        world.setBlockState(origin, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        return assignTask(bot, "nav_buried_escape", new MoveTask(bot, origin.north(5)), 1200,
                ignored -> bot.isAlive() && bodyFree(bot));
    }

    // bot 脚位+头位是否都无碰撞体(=没卡在方块里)。活埋脱困的核检条件:挖出来才算真脱困。
    private static boolean bodyFree(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos head = feet.up();
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
                && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }

    /**
     * 寻路容错③(反向场景):不可达目标要"快速认输"。目标在头顶 80 格高空、背包全空(连搭柱的方块都没有),
     * 物理上不可能到达——期望 MoveTask 在 2400t 内**干净 FAILED**(算 PASS);COMPLETED 或超时仍 RUNNING 都判 FAIL。
     * 空转是实操里最隐蔽的故障形态:bot 看着在干活,实际原地打转浪费整局,比干脆报错难发现得多。
     */
    private static Result assignNavUnreachable(AIPlayerEntity bot) {
        surfaceTeleport(bot); // 地表化,防黑暗反射干扰"干净认输"判定
        prepareArea(bot);
        clearInventory(bot);
        clearNearbyMobs(bot.getServerWorld(), bot.getBlockPos()); // 防怪把 bot 打死造成"假干净失败"(死亡中止≠主动认输)
        // 目标放到世界高度上限之外:resolveEndpoint 会把"够不到的目标"降级到附近可站点(这是导航的
        // 容错 feature)——up(80) 在开阔地表会被降级成脚下、1t 假完成(实测 should_have_failed)。
        // 超出 build limit 的点周围不存在任何可站点,降级也无解,才能逼出"干净认输"路径。
        ServerWorld unreachableWorld = bot.getServerWorld();
        int topLimit = unreachableWorld.getBottomY() + unreachableWorld.getHeight();
        BlockPos goal = new BlockPos(bot.getBlockPos().getX(), topLimit + 10, bot.getBlockPos().getZ());
        // 不走 assignTask(它只会包出常规 running 语义):直接 assign + 反向 Result,语义是"应当失败"。
        TaskManager.INSTANCE.assign(bot, new MoveTask(bot, goal));
        return Result.runningExpectCleanFail("nav_unreachable", 2400);
    }

    private static Result assignTask(AIPlayerEntity bot, String feature, Task task, int timeoutTicks, Predicate<TaskStatus> assertion) {
        TaskManager.INSTANCE.assign(bot, task);
        return Result.running(feature, timeoutTicks, assertion);
    }

    private static void prepareArea(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        world.setTimeOfDay(1000L); // 设白天:套件后段入夜,夜间睡觉反射抢占场景任务(实测 farm_irrigate 偶发 aborted)
        // 套件里多场景顺序跑,bot 位置会从上个场景带过来(打猎走远等)→ 假设"干净出生点"的场景会错位
        //(food_suite 实测:farm_wheat 时 bot 漂到 9,-2,预置成熟麦没 survey 到、被当空地种 → FAIL)。
        // 开头复位到固定原点保证确定性;y 取世界原点的自然地表——原来硬编码 y=6(旧测试世界出生点),
        // 换自然世界后 y6 是黑暗地下,把所有场景传进地下:黑暗触发 DangerWatcher 困死保命传送
        // (dark_trap_escape)中止被测任务(实测 nav_pillar_out 连续两轮 aborted 的真根因)。
        bot.getActionPack().stopAll();
        // 场景空间隔离:每场景换一片新地(x 方向 64 格步进轮转)。同一锚点连跑 13 场景,前面挖矿/爆破
        // 把地基啃成烂地,后场景的挖矿阶梯走出 fillStoneCube 范围就掉进残局 → ore_dig_no_progress
        // 集中爆发(实测 mining 套件 6 场景 FAIL,同场景在 material_suite 单跑却全绿——互染实锤)。
        scenarioSlot++;
        int baseX = (scenarioSlot % 32) * 64;
        // V3 统一高空画布:场景一律建在 y=232 虚空层固定列——anchor 搜索/heightmap/拒残骸锚
        // 全部退役。一期实测:天然矿截胡(geo_rich 三轮)、天然湖勾引淹死(geo_lake)、残骸列坠落
        // (geo_resume)、虚空列(obsidian 案),这一整类"地形抽卡"假阳性吃掉 ~60% 调试时间——
        // 画布脚下 16 格人造石、四周虚空,天然干扰物理归零。真实地形考验仍由 real_suite 专职。
        // y=232:顶上余 88 格(232+清空 8+场景结构),不碰 320 世界上限。
        BlockPos origin = new BlockPos(baseX, 232, 0);
        // 实验室化:轮转地块的天然地形(湖/坡/洞/沙)让确定性回归变抽卡——同一场景红绿每轮洗牌
        //(实测挖石族在湖边泡死、矿场景 need_planks/no_progress 轮换)。场景区整体替换为人造平台:
        // floor 之下 16 格实心石(挖矿/下挖全程吃人造石,不穿进天然含水层),上方 8 格清空。
        // 理想化场景跑"实验室",真实地形考验由 real_suite(SEED 多地形)负责——分层职责明确。
        for (BlockPos pos : BlockPos.iterate(origin.add(-16, -16, -16), origin.add(16, -1, 16))) {
            world.setBlockState(pos, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        for (BlockPos pos : BlockPos.iterate(origin.add(-16, 0, -16), origin.add(16, 8, 16))) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
        for (BlockPos pos : BlockPos.iterate(origin.add(-4, -1, -4), origin.add(4, -1, 4))) {
            world.setBlockState(pos, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        // 护栏:画布四周是虚空,走动型场景(打猎/觅食/探索)会把 bot 带出边沿摔落天然层
        // (food_full 实测 y211→65,坠落中放熔炉连环 no_place)。2 格石墙圈住;走廊型场景
        // 自己的清空 setBlockState 会在墙上拆出门洞,互不妨碍。
        for (BlockPos pos : BlockPos.iterate(origin.add(-16, 0, -16), origin.add(16, 1, 16))) {
            if (Math.abs(pos.getX() - origin.getX()) == 16 || Math.abs(pos.getZ() - origin.getZ()) == 16) {
                world.setBlockState(pos, Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        }
        // 先铺平台后传送:反过来 bot 会在未铺区里有 1+ tick 坠落窗口(虚空列直接自由落体)。
        bot.teleport(world, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
        bot.fallDistance = 0.0F;
        // 记忆层隔离:情景流+语义知识跨场景互染(前面挖矿场景的资源点把 geo_rich 富区导向拐去废区,
        // 残留情景让蒸馏去重拦截预热点)。确定性测试逐场景清;真实使用不走这里,知识照常持久。
        io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.clearFor(bot.getUuid());
        io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE.resetFor(bot.getUuid());
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

    // 在 origin 下方填一个实心石头立方(横向 ±hr,竖向 down 1..depth)。给挖矿任务确定性的实心环境:
    // 覆盖套件里上个场景挖出的坑/残留方块,也避免"挖矿任务斜挖出 1 列石柱掉进未铺地形"。矿石随后嵌进来。
    private static void fillStoneCube(ServerWorld world, BlockPos origin, int hr, int depth) {
        for (int dx = -hr; dx <= hr; dx++) {
            for (int dz = -hr; dz <= hr; dz++) {
                for (int dy = 1; dy <= depth; dy++) {
                    world.setBlockState(origin.add(dx, -dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    // 给 bot 一套深矿安全装(头胸甲+铁剑+盾),满足 ensureMineOre 对 tier≥IRON 矿(金/钻石)的护甲前置,
    // 让材料测试聚焦"挖矿→熔炼→锭"本身,不被"先凑甲"链拖入(凑甲由 achieve_armor 单独测)。
    private static void giveDeepMineKit(AIPlayerEntity bot) {
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_HELMET, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_CHESTPLATE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_SWORD, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.SHIELD, 1));
    }

    // 深矿测试前置:金/钻石规划器必下发"下挖到 Y=深层矿层"步(金 -16/钻 -59)。与其让 bot 从 y6 真挖 60+ 格
    // (慢+地形/岩浆不可控),不如直接把 bot 传送到矿层、在那儿清出+围好实心石立方:descend 步因 bot 已达深度而空过,
    // 测试聚焦"在矿层找矿→挖→(熔炼)"。再给齐口粮/火把/护甲跳过深矿的食物/照明前置。返回深层原点。
    private static BlockPos prepareDeepArea(AIPlayerEntity bot, int depthY) {
        ServerWorld world = bot.getServerWorld();
        bot.getActionPack().stopAll();
        bot.teleport(world, 0.5D, depthY, 0.5D, java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
        BlockPos origin = bot.getBlockPos();
        clearNearbyMobs(world, origin);
        for (BlockPos pos : BlockPos.iterate(origin.add(-4, 0, -4), origin.add(4, 3, 4))) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        fillStoneCube(world, origin, 6, 8); // 下方(含 floor y-1)实心石
        for (int dy = 0; dy <= 4; dy++) {   // 四周竖墙挡深层岩浆/虚空/未知地形
            for (int d = -6; d <= 6; d++) {
                world.setBlockState(origin.add(d, dy, -6), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(d, dy, 6), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(-6, dy, d), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(origin.add(6, dy, d), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        return origin;
    }

    // 深矿测试的口粮/照明/工作台前置(跳过深矿规划里的打猎/烤/火把/挖煤链,聚焦挖矿本身)。
    private static void giveDeepMineSupplies(AIPlayerEntity bot) {
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
    }

    // 极端环境:在 bot 周围 spawn count 只僵尸(战斗阈值 maxEnemiesToFight=2,故默认 2 只——bot 会迎战而非逃)。
    // 测"边打边干":生存反射 pauseFor 战斗、打完 resume 原任务,任务仍要完成。
    private static void spawnHostiles(ServerWorld world, BlockPos origin, int count) {
        for (int i = 0; i < count; i++) {
            ZombieEntity zombie = EntityType.ZOMBIE.create(world, SpawnReason.COMMAND);
            if (zombie != null) {
                zombie.setPersistent(); // 防自然消失
                double side = (i % 2 == 0) ? 2.5D : -2.5D;
                zombie.refreshPositionAndAngles(origin.getX() + side, origin.getY(), origin.getZ() + (i - count / 2), 0.0F, 0.0F);
                world.spawnEntity(zombie);
            }
        }
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
            // patient(LLM 会话式)判定:大脑驱动下 bot 会连续派发多个任务、失败换法重试、任务间空闲思考,
            // 单任务 COMPLETED(断言尚未满足)/FAILED(大脑还会救)都不是场景终局——下面的常规终局判定
            // 对 LLM 流程全是误判,必须在它们之前整段接管。只做两件事:每 tick 测世界状态断言
            // (不管任务状态,含 idle/RUNNING),达成即 PASS;超时则 abort 任务并 FAIL(detail 带最后任务状态)。
            if (running.patient()) {
                if (running.assertion().test(status)) {
                    record(Result.pass(running.feature(), "completed in " + elapsedTicks + " ticks"));
                    active = null;
                    return;
                }
                if (elapsedTicks >= running.timeoutTicks()) {
                    GoalExecutor.INSTANCE.clear(bot); // 先清 goal:abort 任务会触发其 replan 复活,跨场景泄漏(实测污染后续 3 场景)
                    TaskManager.INSTANCE.abort(bot);
                    record(Result.fail(running.feature(), "verify_timeout status=" + status.name() + " " + status.description()));
                    active = null;
                }
                return;
            }
            if (status.state() == TaskState.COMPLETED) {
                if (running.expectFail()) {
                    // 反向场景:任务"完成"了反而是错——说明场景前提没立住(目标其实可达),记 FAIL 提示人工复查布景。
                    record(Result.fail(running.feature(), "should_have_failed: completed in " + elapsedTicks + " ticks"));
                    active = null;
                    return;
                }
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
                if (running.expectFail()) {
                    // 反向场景的 PASS:超时前干净报了失败(而非空转到永远)。detail 带失败原因+耗时,便于核对失败得"对不对"。
                    record(Result.pass(running.feature(), "clean fail in " + elapsedTicks + " ticks: "
                            + (status.failureReason().isBlank() ? "task_failed" : status.failureReason())));
                    active = null;
                    return;
                }
                if (running.allowGoalContinuation() && GoalExecutor.INSTANCE.hasActivePlan(bot)) {
                    return;
                }
                record(Result.fail(running.feature(), status.failureReason().isBlank() ? "task_failed" : status.failureReason()));
                active = null;
                return;
            }
            if (elapsedTicks >= running.timeoutTicks()) {
                GoalExecutor.INSTANCE.clear(bot); // 先清 goal 再 abort,杜绝 replan 复活跨场景泄漏(同上)
                TaskManager.INSTANCE.abort(bot);
                // expectFail 场景超时 = 任务既没完成也没认输、一直空转——这正是反向场景要钉死的故障形态,换专属前缀好认。
                record(Result.fail(running.feature(), (running.expectFail() ? "no_clean_fail_before_timeout" : "verify_timeout")
                        + " status=" + status.name() + " " + status.description()));
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
                          boolean expectFail,
                          boolean patient,
                          Predicate<TaskStatus> assertion,
                          Consumer<AIPlayerEntity> perTick) {
        private static final Consumer<AIPlayerEntity> NO_TICK = bot -> {
        };

        private static Result pass(String feature, String detail) {
            return new Result(feature, true, detail, false, 0, false, false, false, ignored -> true, NO_TICK);
        }

        private static Result fail(String feature, String detail) {
            return new Result(feature, false, detail, false, 0, false, false, false, ignored -> false, NO_TICK);
        }

        private static Result running(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, false, false, false, assertion, NO_TICK);
        }

        private static Result runningGoal(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, false, false, assertion, NO_TICK);
        }

        // 带每-tick 副作用钩子的 runningGoal:perTick 在 pollActive 每个服务端 tick 都被调用(无论有无 task 完成),
        // 用于测试期持续操纵世界(如强制催熟作物,绕开自然随机刻生长的漫长等待)。assertion 仍是成功判定。
        private static Result runningGoal(String feature, int timeoutTicks,
                                          Consumer<AIPlayerEntity> perTick, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, true, false, false, assertion, perTick);
        }

        // 反向场景工厂:期望任务在 timeoutTicks 内**干净 FAILED**——这才算 PASS(detail 带失败原因+耗时);
        // COMPLETED 或超时仍 RUNNING 都记 FAIL。用来钉死"不可达目标必须快速认输"的容错契约,
        // 防止寻路退化成无限重试空转(实操里空转比报错伤得多:看着在干活,实际整局假死)。
        // assertion 在 expectFail 语义下不参与判定,占位恒 false 防误用。
        private static Result runningExpectCleanFail(String feature, int timeoutTicks) {
            return new Result(feature, false, "running", true, timeoutTicks, false, true, false, ignored -> false, NO_TICK);
        }

        // patient(耐心)工厂:R2 LLM 全链层专用。大脑会话式驱动下单任务 COMPLETED/FAILED 都不是终局
        // (会连续派发任务/失败重试/空闲思考),pollActive 对 patient 跳过全部终局判定,
        // 只认"世界状态断言达成"(PASS,completed in X ticks)或超时(abort+FAIL,detail 带最后任务状态)。
        private static Result runningPatient(String feature, int timeoutTicks, Predicate<TaskStatus> assertion) {
            return new Result(feature, false, "running", true, timeoutTicks, false, false, true, assertion, NO_TICK);
        }
    }
}
