package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashSet;
import java.util.Set;

public final class GatherQuotaTask extends AbstractTask {
    private static final int SEARCH_RADIUS = 16;
    // F1:近处(16格)找不到资源时自动扩大半径走远找(32→48),而不是立刻失败交还大脑乱试。
    private static final int MAX_SEARCH_RADIUS = 48;
    private static final int SEARCH_DOWN = 6;
    private static final int SEARCH_UP = 12;
    private static final int LARGE_SCAN_THROTTLE_TICKS = 10; // 大半径扫描限频,护 TPS
    private static final int MAX_PICKUP_MISSES = 5;  // 连续采不到的容忍棵数,超了才漫游换片
    private static final int MAX_ROAMS = 8;          // 卡步逃逸:最多漫游换片次数(找树/换片共用,~224 格),再不行才 fail
    private static final int ROAM_DISTANCE = 28;     // 每次漫游的水平距离
    private static final int SELF_STUCK_LIMIT = 160; // A:采集自卡死阈值(自管看门狗,见 isWaiting 说明)
    private static final int ROAM_MOVE_LIMIT = 100;  // 漫游走 5s 还没到落脚点(目标在高处/不可达、爬坡卡)→ 放弃回 SURVEY
    // 治无树兜底:48 格 + 上浮都找不到资源时,用 OreProspector palette 扫描大范围(96 格)定位最近的目标方块
    //(如原木),再寻路走过去。专治"无树高原/恶劣地形"——roam 只在同片横移跨不出高原,prospect 能直接锁定山脚/远处的树。
    private static final int PROSPECT_RANGE = 96;
    private static final int PROSPECT_INTERVAL = 40; // 大范围扫描限频(2s 一次),护 TPS

    private enum Phase {
        SURVEY,
        GOTO,
        HARVEST,
        PICKUP,
        DEPOSIT,
        ROAM,
        DONE
    }

    private final Item targetItem;
    private final int targetCount;
    // Fix C:目标是原木时,接受/采集**任意**树种(生物群系不一定有橡木)。进度按整族原木总数计。
    private final Set<Item> acceptItems;
    private final Set<Block> harvestBlocks;
    private final boolean probabilisticDrop; // 破坏掉落是概率/部分的(草→种子、浆果丛→浆果):掉 0 是常态,不算"采不到"
    private Phase phase = Phase.SURVEY;
    private BlockPos targetPos;
    private int countSoFar;
    private int countBeforeHarvest;
    private int pickupTicks;
    private int pickupMisses; // 连续"砍了但没捡到掉落物"的次数,超限才判 pickup_timeout(避免一棵没捡到就整个采集失败)
    private boolean pickupSweepAttempted;
    private StockpileTask stockpileTask;
    private int searchRadius = SEARCH_RADIUS;
    private int lastScanTick = -100;
    private int lastProspectTick = -100; // 治无树兜底:上次大范围探树的 tick(限频)
    private boolean surfaceTried; // B:地下找不到树时,上浮到地表重试一次的兜底标志
    private int roamCount;        // 卡步逃逸:已漫游换片的次数
    private BlockPos roamTarget;  // 漫游换片的落脚点(走过去,不 teleport)
    private int selfStuckTick;     // A:上次"采到新木"的 tick
    private int selfStuckCount;    // A:上次记录的已采数

    public GatherQuotaTask(Item targetItem, int targetCount) {
        this.targetItem = targetItem;
        this.targetCount = Math.max(1, targetCount);
        this.acceptItems = acceptItemsFor(targetItem);
        this.harvestBlocks = harvestBlocksFor(this.acceptItems);
        this.probabilisticDrop = harvestBlocks.contains(Blocks.SHORT_GRASS)
                || harvestBlocks.contains(Blocks.SWEET_BERRY_BUSH);
    }

    @Override
    public String name() {
        return "gather";
    }

    @Override
    public String describe() {
        return "Gathering " + Registries.ITEM.getId(targetItem) + " " + countSoFar + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // 采集自管看门狗,不交给 StuckWatcher 那个"200t 内 pos+progress+inv 没变就 abort"的粗粒度监控——
        // 它在无树高原会误杀正在远程找树/漫游的采集(实测 stuck:gather progress=0:bot 还在努力 roam/探树
        // 就被 200t abort,直接拖垮整个挖钻石目标 → 大脑接管乱挖致死)。本任务自带三层兜底足够:
        // ① self-stuck(SELF_STUCK_LIMIT 内没采到新木 → 漫游换片);② roam 最多 MAX_ROAMS 次;③ gather_timeout(6000t)。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        countSoFar = countAccepted(bot);
        phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
        stockpileTask = null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        countSoFar = countAccepted(bot);
        if (countSoFar >= targetCount) {
            phase = Phase.DONE;
        }
        if (elapsed > 6000) {
            fail("gather_timeout");
            return;
        }
        // A:采集自愈——只看"是否采到新木"(count 是否增长),不看位置。GOTO 缓慢挪动但久采不到(走不到树/
        // 砍不动)也算卡 → 及时漫游换片,而非缓慢耗到 gather_timeout(实测:采到 5 根后走不到剩下的、5min 超时被秒)。
        if (phase != Phase.ROAM) {
            if (countSoFar != selfStuckCount) {
                selfStuckCount = countSoFar;
                selfStuckTick = elapsed;
            } else if (elapsed - selfStuckTick > SELF_STUCK_LIMIT && roamToNewArea(bot)) {
                return; // roamToNewArea 内已设 phase=ROAM(走过去换片),漫游中不再自检
            }
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case HARVEST -> harvest(bot);
            case PICKUP -> pickup(bot);
            case DEPOSIT -> deposit(bot);
            case ROAM -> roamMove(bot);
            case DONE -> complete();
        }
    }

    // B:bot 在地下(头顶不见天)且附近找不到可达资源时,上浮到正上方最近的"露天可站点",再重试采集。
    // teleport 上浮(会清 fallDistance);已在露天则不动。是"集中采集"之外的兜底,极少触发。
    private boolean trySurface(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        if (world.isSkyVisible(feet)) {
            return false;
        }
        int top = world.getBottomY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 80; dy++) {
            BlockPos candidate = feet.up(dy);
            if (Standability.isStandable(world, candidate) && world.isSkyVisible(candidate)) {
                bot.getActionPack().stopAll();
                bot.teleport(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                        java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                BotLog.action(bot, "gather_surfaced",
                        "to", candidate.getX() + "," + candidate.getY() + "," + candidate.getZ());
                return true;
            }
        }
        return false;
    }

    // 治无树兜底:大范围 palette 扫描(PROSPECT_RANGE)定位最近的目标方块(如原木),寻路到该列地表落脚点走过去;
    // 到达后由 SURVEY 近处(16 格)接管精确采集。限频(PROSPECT_INTERVAL)护 TPS。
    // 本次没结果(限频未到 / 范围内真没该资源)返回 false,交 roam 盲目换片兜底(可走到探测范围外)。
    private boolean prospectAndApproach(AIPlayerEntity bot) {
        int now = bot.getServer().getTicks();
        if (now - lastProspectTick < PROSPECT_INTERVAL) {
            return false;
        }
        lastProspectTick = now;
        var world = bot.getServerWorld();
        BlockPos found = OreProspector.nearest(world, bot.getBlockPos(), PROSPECT_RANGE,
                state -> harvestBlocks.contains(state.getBlock()));
        if (found == null) {
            return false; // 96 格内真没该资源 → 交 roam 盲目换片(可走到更远的片)
        }
        BlockPos ground = findGroundAt(world, found.getX(), found.getZ()); // 目标所在列的地表落脚点
        if (ground == null) {
            return false;
        }
        bot.getActionPack().stopAll();
        bot.getActionPack().startPathTo(ground);
        roamTarget = ground;
        searchRadius = SEARCH_RADIUS;
        pickupMisses = 0;
        selfStuckTick = elapsed;
        phase = Phase.ROAM; // 复用 ROAM 走过去;到达后 roamMove 回 SURVEY,在新片近处采集
        BotLog.action(bot, "gather_prospected",
                "found", found.getX() + "," + found.getY() + "," + found.getZ(),
                "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                "item", Registries.ITEM.getId(targetItem).toString(),
                "dist", (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(found)));
        return true;
    }

    // 卡步逃逸:同一片连续多棵采不到 → 走到 ROAM_DISTANCE 外的露天地表换片林子重试(走过去,不 teleport),
    // 而非原地死磕被秒。最多 MAX_ROAMS 次,再不行才 fail 交大脑/玩家。
    private boolean roamToNewArea(AIPlayerEntity bot) {
        if (++roamCount > MAX_ROAMS) {
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        int start = Math.floorMod(roamCount, dirs.length);
        for (int i = 0; i < dirs.length; i++) {
            int[] d = dirs[(start + i) % dirs.length];
            BlockPos ground = findGroundAt(world, feet.getX() + d[0] * ROAM_DISTANCE, feet.getZ() + d[1] * ROAM_DISTANCE);
            if (ground != null) {
                // 拟人:走过去换片,不再 teleport 闪现(实测砍树时瞬移很出戏)。
                bot.getActionPack().stopAll();
                bot.getActionPack().startPathTo(ground);
                roamTarget = ground;
                searchRadius = SEARCH_RADIUS;
                pickupMisses = 0;
                selfStuckTick = elapsed;
                phase = Phase.ROAM;
                BotLog.action(bot, "gather_roam",
                        "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(), "n", roamCount);
                return true;
            }
        }
        return false;
    }

    // 在 (x,z) 列从高往低找第一个露天可站点(地表)。
    private BlockPos findGroundAt(net.minecraft.server.world.ServerWorld world, int x, int z) {
        // 高度图取该列地表,跨任意海拔成立(原硬上限 y=110 会让 bot 站在 y>110 高地时漫游/落脚全失败,与 HuntTask 同源 bug)。
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
        for (int y = surfaceY; y >= surfaceY - 6 && y > world.getBottomY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (Standability.isStandable(world, p) && world.isSkyVisible(p)) {
                return p;
            }
        }
        return null;
    }

    // 漫游中:走向新片落脚点;到达(3 格内)或走不动(寻路空闲)→ 回 SURVEY 在新片找树(途中 SURVEY 也会扫到沿路的树)。
    private void roamMove(AIPlayerEntity bot) {
        if (roamTarget == null
                || bot.getBlockPos().getSquaredDistance(roamTarget) <= 9.0D
                || bot.getActionPack().isPathExecutorIdle()
                || elapsed - selfStuckTick > ROAM_MOVE_LIMIT) { // 走太久没到(漫游目标在高处/不可达)→ 放弃回 SURVEY 重找近处可达资源
            roamTarget = null;
            searchRadius = SEARCH_RADIUS;
            phase = Phase.SURVEY;
        }
    }

    private void survey(AIPlayerEntity bot) {
        if (harvestBlocks.isEmpty()) {
            fail("unsupported_resource_type");
            return;
        }
        if (HarvestCore.isInventoryFull(bot) && countSoFar < targetCount) {
            phase = Phase.DEPOSIT;
            return;
        }
        // F1:大半径扫描限频,避免每 tick 扫 48 格立方体拖 TPS。
        int now = bot.getServer().getTicks();
        if (searchRadius > SEARCH_RADIUS && now - lastScanTick < LARGE_SCAN_THROTTLE_TICKS) {
            return;
        }
        lastScanTick = now;
        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(bot, harvestBlocks, searchRadius, SEARCH_DOWN, SEARCH_UP);
        if (choice == null) {
            // F1:近处没有 → 自动扩大半径走远找,而不是立刻失败交还大脑乱试/空手挖/求助。
            if (searchRadius < MAX_SEARCH_RADIUS) {
                searchRadius = Math.min(MAX_SEARCH_RADIUS, searchRadius * 2);
                BotLog.action(bot, "gather_expand_search",
                        "radius", searchRadius,
                        "item", Registries.ITEM.getId(targetItem).toString());
                return;
            }
            // B:扩到最大半径仍找不到可达资源 → 若 bot 在地下(头顶不见天),先上浮到地表再试一轮
            //(兜底;正常情况下"集中采集"已让木头在地表一次备足,不会走到这一步)。
            if (!surfaceTried && trySurface(bot)) {
                surfaceTried = true;
                searchRadius = SEARCH_RADIUS;
                return;
            }
            // 治无树兜底:近处(48 格)+ 上浮都找不到 → 大范围 palette 扫描(96 格)锁定最近的目标方块(如原木),
            // 寻路走过去。专治无树高原/恶劣地形——盲目 roam 只在同片横移、跨不出高原,prospect 能直接定位山脚/远处的树。
            if (prospectAndApproach(bot)) {
                return;
            }
            // 这片 + 探测范围(96 格)都没树 → 漫游盲目换片(可走到探测范围外的新片),再不行才 fail 交大脑/玩家。
            if (roamToNewArea(bot)) {
                surfaceTried = false; // 新区域重新允许"上浮兜底"
                return;
            }
            fail("no_resource_nearby");
            return;
        }
        targetPos = choice.pos();
        if (choice.direct()) {
            startHarvest(bot);
            return;
        }
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(choice.stand());
    }

    private void goToTarget(AIPlayerEntity bot) {
        if (targetPos == null || !isHarvestBlock(bot, targetPos)) {
            phase = Phase.SURVEY;
            return;
        }
        if (HarvestCore.canReach(bot, targetPos)) {
            bot.getActionPack().stopAll();
            startHarvest(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            phase = Phase.SURVEY;
        }
    }

    private void harvest(AIPlayerEntity bot) {
        if (targetPos == null || !isHarvestBlock(bot, targetPos)) {
            bot.getActionPack().stopAll(); // 砍倒后停稳,别带移动惯性漂离掉落物(实测砍完从树位漂走→捡不到)
            pickupTicks = probabilisticDrop ? 30 : 120; // 概率掉落资源(种子/浆果)掉脚边、捡得快,少等
            phase = Phase.PICKUP;
            return;
        }
        if (bot.getActionPack().isMiningIdle() && elapsed % 200 == 0) {
            startHarvest(bot);
        }
    }

    private void pickup(AIPlayerEntity bot) {
        HarvestCore.forcePickupNearbyAnyOf(bot, acceptItems);
        countSoFar = countAccepted(bot);
        if (countSoFar > countBeforeHarvest) {
            phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
            return;
        }
        pickupTicks--;
        HarvestCore.chaseDropAnyOf(bot, acceptItems, 8.0D);
        if (pickupTicks <= 0) {
            if (!pickupSweepAttempted && HarvestCore.nearestDropAnyOf(bot, acceptItems, 8.0D).isPresent()) {
                pickupSweepAttempted = true;
                HarvestCore.sweepPickupAnyOf(bot, acceptItems, 8);
                pickupTicks = 60;
                return;
            }
            countSoFar = countAccepted(bot);
            if (countSoFar > countBeforeHarvest) {
                pickupMisses = 0;
                phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
            } else if (probabilisticDrop) {
                // 概率掉落(割草取种子/采浆果丛):这次破坏没掉是常态,不算"采不到",回 SURVEY 继续采下一个;
                // 靠 survey 找不到方块(→roam)与 gather_timeout(6000t)兜底,避免被 pickup_miss 误判超时
                //(实测:割草取种子 pickup_timeout、只采到 1 个就失败)。
                phase = Phase.SURVEY;
            } else if (++pickupMisses <= MAX_PICKUP_MISSES) {
                // 没捡到(高处掉落卡叶/够不到)→ 别 fail,回 SURVEY 换棵再砍。关键修:去掉旧的"countSoFar>0"
                // 限制——实测树稀疏区第一棵就捡不到→countSoFar=0 直接 fail→大脑重规划同样计划→死循环 19 分钟被秒。
                BotLog.action(bot, "gather_pickup_miss", "have", countSoFar + "/" + targetCount, "miss", pickupMisses);
                phase = Phase.SURVEY;
            } else if (roamToNewArea(bot)) {
                // 同一片连续多棵都采不到 → 漫游(走过去)换片重试(roamToNewArea 内已设 phase=ROAM)。
            } else {
                fail("pickup_timeout");
            }
        }
    }

    private void deposit(AIPlayerEntity bot) {
        if (stockpileTask == null) {
            bot.getActionPack().stopAll();
            stockpileTask = new StockpileTask(true);
            stockpileTask.start(bot);
        }
        stockpileTask.tick(bot);
        if (stockpileTask.state() == TaskState.COMPLETED) {
            stockpileTask = null;
            phase = Phase.SURVEY;
            return;
        }
        if (stockpileTask.state() == TaskState.FAILED) {
            String reason = stockpileTask.failureReason();
            stockpileTask = null;
            fail(reason == null || reason.isBlank() ? "inventory_full" : reason);
        }
    }

    private void startHarvest(AIPlayerEntity bot) {
        countBeforeHarvest = countAccepted(bot);
        pickupSweepAttempted = false;
        HarvestCore.startMining(bot, targetPos);
        phase = Phase.HARVEST;
    }

    private int countAccepted(AIPlayerEntity bot) {
        return HarvestCore.countInventoryItems(bot, acceptItems);
    }

    private boolean isHarvestBlock(AIPlayerEntity bot, BlockPos pos) {
        return harvestBlocks.contains(bot.getServerWorld().getBlockState(pos).getBlock());
    }

    // 觅食野食族:浆果 / 西瓜片(都是野生即取、可直接吃);采任一凑数(哪个近采哪个)。
    private static final Set<Item> FORAGE_FOODS = Set.of(Items.SWEET_BERRIES, Items.MELON_SLICE);

    private static Set<Item> acceptItemsFor(Item item) {
        // 原木:接受任意树种(配方下游 planks/stick/工具都接受任意 planks 家族)。
        if (RecipeRegistry.LOGS.contains(item)) {
            return Set.copyOf(RecipeRegistry.LOGS);
        }
        if (FORAGE_FOODS.contains(item)) {
            return FORAGE_FOODS; // 觅食:接受任意野食,采到哪种都算数
        }
        return Set.of(item);
    }

    private static Set<Block> harvestBlocksFor(Set<Item> items) {
        if (items.contains(Items.WHEAT_SEEDS)) {
            // 割草取小麦种子:破坏短草/高草/蕨,概率掉 wheat_seeds(早期种田的种子来源)。
            return Set.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN);
        }
        if (items.contains(Items.SWEET_BERRIES) || items.contains(Items.MELON_SLICE)) {
            // 觅食:破坏甜浆果丛(成熟掉浆果)/ 西瓜(掉西瓜片),取野生即取食物。
            return Set.of(Blocks.SWEET_BERRY_BUSH, Blocks.MELON);
        }
        LinkedHashSet<Block> blocks = new LinkedHashSet<>();
        for (Item item : items) {
            Block block = harvestBlockFor(item);
            if (block != null) {
                blocks.add(block);
            }
        }
        return Set.copyOf(blocks);
    }

    private static Block harvestBlockFor(Item item) {
        if (item == Items.COBBLESTONE) {
            return Blocks.STONE;
        }
        if (item instanceof BlockItem blockItem) {
            return blockItem.getBlock();
        }
        return null;
    }
}
