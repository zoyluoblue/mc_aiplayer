package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
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
    // EXPLORE(定向走出去找):roam 的 28 格小步在真实地形上 8 方向乒乓、净位移≈0,survey 永远在同一片
    // 打转(real_wood seed=20260610 实测:找不到/找到不可达树时原地循环到 6001t 超时)。EXPLORE 以
    // 48/40/32 格大步跳点定向外推,最多 4 跳(~190 格);知识库记得资源点就朝记忆点走,否则罗盘盲探。
    private static final int EXPLORE_MAX_HOPS = 4;
    private static final double[] EXPLORE_HOP_DISTANCES = {48.0D, 40.0D, 32.0D};
    private static final int[] EXPLORE_DEFLECTIONS_DEG = {0, 45, -45, 90, -90}; // 偏角序列:先沿航向,再左右扇形
    private static final int EXPLORE_MOVE_LIMIT = 300;   // 单跳走 15s 还没到 → 弃跳回 SURVEY
    private static final int EXPLORE_SCAN_INTERVAL = 20; // 途中轻扫限频(1s 一次,16 格),见目标即收手
    private static final int EXPLORE_PATH_ATTEMPTS = 5;  // 单次选点最多实跑几次同步 A*(防单 tick 长卡)
    private static final int KNOWN_RESOURCE_RANGE = 192; // 知识库记忆点导向的最大距离
    private static final int GOTO_FAIL_EXCLUDE = 2;      // 同一目标 GOTO 连续走崩 N 次 → 工作记忆拉黑
    private static final int GOTO_STUCK_LIMIT = 80;      // R1:GOTO 朝树寻路时坐标连续这么久(4s)不动 → 判悬空/卡死,强制脱困

    private enum Phase {
        SURVEY,
        GOTO,
        HARVEST,
        PICKUP,
        DEPOSIT,
        ROAM,
        EXPLORE,
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
    // 高差容错:上一个 prospect 目标 + 走不到的目标黑名单。再次进入 prospect = 上一个目标没采成(若采成了
    // SURVEY 近处就接管了,不会再 prospect)→ 拉黑,换下一个;防"反复扫到同一个够不到的目标"死循环
    //(实测:草在 y66 山谷,bot 在 y86 崖顶,落脚点被 heightmap 顶到崖顶,395t 内反复扫同一丛草直到超时)。
    private BlockPos lastProspectFound;
    // 排除项收编进 EpisodeMemory(工作记忆):跨 replan 存活(goal 级生命周期)+TTL 复活,
    // 语义与原 static 黑名单一致,但与 ore_dig/roam 的同类机制统一为一处实现。
    private boolean surfaceTried; // B:地下找不到树时,上浮到地表重试一次的兜底标志
    private int roamCount;        // 卡步逃逸:已漫游换片的次数
    private BlockPos roamTarget;  // 漫游换片的落脚点(走过去,不 teleport)
    private int selfStuckTick;     // A:上次"采到新木"的 tick
    private int selfStuckCount;    // A:上次记录的已采数
    // EXPLORE 状态:跳数额度(采到新东西即复位)、当前航向(弧度)、当前跳点、本跳起始 tick、
    // 记忆点导向(知识库命中时朝它走,到场没货销账)、"自上次找到以来经历过探索"(控制 RESOURCE_FOUND 入流)、
    // 途中轻扫限频;另两个是不可达拉黑:上一个 GOTO 目标 + 同目标连续走崩计数。
    private int exploreHops;
    private double exploreHeading;
    private BlockPos exploreTarget;
    private int exploreHopStartTick;
    private BlockPos exploreHint;
    private boolean exploredSinceFind;
    private int lastExploreScanTick = -100;
    private BlockPos lastGotoTarget;
    private int gotoFailStreak;
    private boolean treeDigTried; // 当前目标是否已升级过挖掘接近(崖壁/下方树走不到时下沉掘进)
    private BlockPos gotoStuckPos; // R1:GOTO 上次记录的坐标(久不动判悬空死锁)
    private int gotoStuckTick;

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
        // 工作记忆:记录走过的轨迹(4 格去抖),roam 选点避开已搜过的区域(不再盲目转圈)。
        EpisodeMemory.INSTANCE.recordTrail(bot.getUuid(), bot.getBlockPos());
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
        if (phase != Phase.ROAM && phase != Phase.EXPLORE) {
            if (countSoFar != selfStuckCount) {
                selfStuckCount = countSoFar;
                selfStuckTick = elapsed;
                exploreHops = 0;          // 采到新东西=这片有产出,探索跳数额度重置
                exploredSinceFind = true; // 下次"探索后的发现"重新值得入流记忆
            } else if (elapsed - selfStuckTick > SELF_STUCK_LIMIT && escapeBarrenArea(bot)) {
                return; // 两者内部已设 phase=ROAM/EXPLORE(走过去换片/定向外探),移动中不再自检
            }
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case HARVEST -> harvest(bot);
            case PICKUP -> pickup(bot);
            case DEPOSIT -> deposit(bot);
            case ROAM -> roamMove(bot);
            case EXPLORE -> exploreMove(bot);
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
        // 又走到 prospect = 上一个 prospect 目标没采成(采成的话 SURVEY 近处早接管了)→ 拉黑换下一个,
        // 杜绝"反复扫到同一个够不到的目标"死循环。黑名单防膨胀:超 32 个清空重来(资源可能后来变得可达)。
        java.util.UUID botId = bot.getUuid();
        if (lastProspectFound != null) {
            EpisodeMemory.INSTANCE.exclude(botId, lastProspectFound, now, EpisodeMemory.TTL_UNREACHABLE);
            lastProspectFound = null;
        }
        var world = bot.getServerWorld();
        BlockPos found = OreProspector.nearest(world, bot.getBlockPos(), PROSPECT_RANGE,
                state -> harvestBlocks.contains(state.getBlock()),
                pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, now));
        if (found == null) {
            // 观测:静默 false 无法区分"96 格内真没该资源"和"扫描/黑名单 bug"(实测 21t 速死无从取证)。
            BotLog.action(bot, "gather_prospect_empty",
                    "item", targetItem, "range", PROSPECT_RANGE,
                    "blacklisted", EpisodeMemory.INSTANCE.excludedCount(botId));
            return false; // 排除项不清(带 TTL 的"真去不了"),交 roam 盲目换片兜底
        }
        // 落脚点以目标实际位置为锚:目标可能在山谷/低地——旧法用该列 heightmap 会把落脚点顶到崖顶,
        // 与目标差几十格高度,走过去也够不到(实测 found=y66 to=y86 死循环)。A* 自己解决下坡路线。
        BlockPos ground = standNearTarget(world, found);
        if (ground == null) {
            // 崖壁/高差树周边无可站点:纯步行无解,但挖掘接近能下沉/掘进过去(钻石 67% 失败的崖壁采木真因——
            // 实测 GOAL_UNREACHABLE×3 却从不触发兜底,因兜底原先只在 goToTarget,而崖壁失败走这条 prospect 路)。
            if (tryDigApproach(bot, found, "no_stand")) {
                return true;
            }
            BotLog.action(bot, "gather_prospect_unreachable",
                    "found", found.toShortString(), "why", "no_stand");
            EpisodeMemory.INSTANCE.exclude(botId, found, now, EpisodeMemory.TTL_UNREACHABLE);
            return false;
        }
        bot.getActionPack().stopAll();
        // 步行被拒 → 先升级挖掘接近(崖下/崖壁树掘进过去);挖掘也不通才拉黑换下一个。
        var pathResult = bot.getActionPack().startPathTo(ground);
        if (pathResult.isFailed()) {
            if (tryDigApproach(bot, found, pathResult.reason())) {
                return true;
            }
            BotLog.action(bot, "gather_prospect_unreachable",
                    "found", found.toShortString(), "why", pathResult.reason());
            EpisodeMemory.INSTANCE.exclude(botId, found, now, EpisodeMemory.TTL_UNREACHABLE);
            return false;
        }
        lastProspectFound = found.toImmutable();
        // 直接锁定这棵树走 GOTO,由 goToTarget 统一驱动"到达→采集"(含 dig-approach 崖壁/高差 + R1 悬空脱困)。
        // 不再走 ROAM-到落脚点-再重扫:重扫常丢这棵树(实测 555 elevated 云杉 prospected dist4 却回 SURVEY 扫不到
        // → expand → 43t no_resource 速死,3次 goal_failed)。GOTO 提交制:够不到就 dig/拉黑换树,不空转丢树。
        targetPos = found.toImmutable();
        lastGotoTarget = targetPos;
        treeDigTried = false;
        gotoFailStreak = 0;
        gotoStuckPos = null;          // 重置 R1 看门狗基准
        searchRadius = SEARCH_RADIUS;
        pickupMisses = 0;
        selfStuckTick = elapsed;
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(ground);
        BotLog.action(bot, "gather_prospected",
                "found", found.getX() + "," + found.getY() + "," + found.getZ(),
                "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                "item", Registries.ITEM.getId(targetItem).toString(),
                "dist", (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(found)));
        return true;
    }

    // 以目标为锚找落脚点:优先目标自身格(短草/树苗等非碰撞方块可直接站进),再四邻 ±1 层;
    // 都站不了(目标埋在实心里/悬空)退回该列地表(树干列:站树根旁)。
    private BlockPos standNearTarget(net.minecraft.server.world.ServerWorld world, BlockPos found) {
        if (Standability.isStandable(world, found)) {
            return found;
        }
        // R2:四邻纵向搜索 ±1 → ±3。崖壁/下方树常有 3-4 格高差(实测 seed20260610 bot在y77、树在y73,
        // ±1 搜不到落脚点→no_stand→prospect速死)。±3 覆盖常见崖差,显著提升"够到下方/崖壁树"的成功率。
        for (var dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            BlockPos side = found.offset(dir);
            for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3}) {
                BlockPos p = side.up(dy);
                if (Standability.isStandable(world, p)) {
                    return p;
                }
            }
        }
        return findGroundAt(world, found.getX(), found.getZ());
    }

    // 逃离无树片区(治 gather_timeout 死循环):roam 是局部换片(28-56格小步),在大片无树/恶劣地形里会
    // 反复跳同几个点跳不出去;explore 是定向远逃(160格)。先 roam 试两次(近处可能就有,省得乱跑),
    // 连续 2 次还没跳出(roamCount>=2)就改【优先 explore 远逃】,别再原地乒乓把时间烧到 gather_timeout。
    private boolean escapeBarrenArea(AIPlayerEntity bot) {
        if (roamCount >= 2) {
            return startExplore(bot) || roamToNewArea(bot);
        }
        return roamToNewArea(bot) || startExplore(bot);
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
        // 距离自适应:满距不行就减半再试(山顶/悬崖/水域环绕时 28 格外 8 方向可能全部寻路被拒,
        // 实测 21t 内 8 连拒直接 no_resource 速死——近处总有能走的点,先挪过去下轮再扩)。
        for (int dist = ROAM_DISTANCE; dist >= ROAM_DISTANCE / 4; dist /= 2) {
            // 轨迹避重只在满距档生效:roam 优先去没搜过的新区(治盲目转圈);减距档是"哪怕近点也得挪窝"
            // 的兜底,不再挑剔(全方向都在轨迹附近时总得选一个)。
            boolean avoidTrail = dist == ROAM_DISTANCE;
            for (int i = 0; i < dirs.length; i++) {
                int[] d = dirs[(start + i) % dirs.length];
                BlockPos ground = findGroundAt(world, feet.getX() + d[0] * dist, feet.getZ() + d[1] * dist);
                if (ground == null
                        || (avoidTrail && EpisodeMemory.INSTANCE.nearTrail(bot.getUuid(), ground, 10.0D))) {
                    continue;
                }
                // 拟人:走过去换片,不再 teleport 闪现(实测砍树时瞬移很出戏)。
                bot.getActionPack().stopAll();
                if (bot.getActionPack().startPathTo(ground).isFailed()) {
                    continue; // 寻路被拒 → 换个方向(不看结果就进 ROAM 会瞬退、白烧漫游次数)
                }
                roamTarget = ground;
                searchRadius = SEARCH_RADIUS;
                pickupMisses = 0;
                selfStuckTick = elapsed;
                phase = Phase.ROAM;
                BotLog.action(bot, "gather_roam",
                        "to", ground.getX() + "," + ground.getY() + "," + ground.getZ(),
                        "n", roamCount, "dist", dist);
                return true;
            }
        }
        return false;
    }

    // 在 (x,z) 列从高往低找第一个可站点(地表/林地)。
    private BlockPos findGroundAt(net.minecraft.server.world.ServerWorld world, int x, int z) {
        // 高度图取该列地表,跨任意海拔成立(原硬上限 y=110 会让 bot 站在 y>110 高地时漫游/落脚全失败,与 HuntTask 同源 bug)。
        // 树冠穿透(与 HuntTask.findGround 同款修复):原 MOTION_BLOCKING 顶面在森林落在树冠(高大云杉
        // 20+ 格,固定下穿赌不赢)。正解:MOTION_BLOCKING_NO_LEAVES 原生跳树叶,顶面=地形/树干,再下穿落地。
        int surfaceY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = surfaceY; y >= surfaceY - 24 && y > world.getBottomY() + 1; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (Standability.isStandable(world, p)) {
                return p;
            }
        }
        return null;
    }

    // 漫游中:走向新片落脚点;到达(3 格内)或走不动(寻路空闲)→ 回 SURVEY 在新片找树(途中 SURVEY 也会扫到沿路的树)。
    private void roamMove(AIPlayerEntity bot) {
        // 沾水即弃当前漫游路线(这条路把我们带进了水),让 NavSafetyNet 拖上岸后回 SURVEY 重选——
        // 别顶着岸壁反复半淹(与 HuntTask.roamMove 同款保护)。
        if (bot.isTouchingWater()) {
            roamTarget = null;
            searchRadius = SEARCH_RADIUS;
            phase = Phase.SURVEY;
            return;
        }
        // 起步宽限 20t:startPathTo 后 A* 异步计算需几个 tick,期间 executor 仍 idle——立即判"走不动"
        // 会瞬退回 SURVEY,prospect 拉黑机制连带把"还没出发"的好目标当"走不到"误杀(实测割草连环误杀至 no_resource)。
        boolean pathGaveUp = elapsed - selfStuckTick > 20 && bot.getActionPack().isPathExecutorIdle();
        if (roamTarget == null
                || bot.getBlockPos().getSquaredDistance(roamTarget) <= 9.0D
                || pathGaveUp
                || elapsed - selfStuckTick > ROAM_MOVE_LIMIT) { // 走太久没到(漫游目标在高处/不可达)→ 放弃回 SURVEY 重找近处可达资源
            roamTarget = null;
            searchRadius = SEARCH_RADIUS;
            phase = Phase.SURVEY;
        }
    }

    // EXPLORE 起跳:定航向(知识库 192 格内记得同类资源 → 朝记忆点;否则罗盘盲探,避开刚走过的轨迹),
    // 选一个 48/40/32 格外的跳点并寻路出发。跳数额度耗尽/选不出点返回 false(由 survey 的 fail 链定生死)。
    private boolean startExplore(AIPlayerEntity bot) {
        if (exploreHops >= EXPLORE_MAX_HOPS) {
            return false;
        }
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        exploreHint = null;
        boolean aimed = false;
        // 记忆导向:语义知识库(跨会话)里最近的同类资源点 → 直奔(哪怕中途轻扫先截胡也赚)。
        for (Block block : harvestBlocks) {
            var known = io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE.nearestResource(
                    bot.getUuid(), Registries.BLOCK.getId(block).toString(), feet, KNOWN_RESOURCE_RANGE);
            if (known.isPresent()) {
                exploreHint = known.get().pos();
                exploreHeading = Math.atan2(exploreHint.getZ() + 0.5D - bot.getZ(), exploreHint.getX() + 0.5D - bot.getX());
                aimed = true;
                break;
            }
        }
        if (!aimed) {
            // 盲探:8 个罗盘方向(45° 步)里取第一个"44 格外有地面落点且不在最近轨迹 16 格内"的航向。
            // 全不合格就沿用当前航向(默认 0/上一跳方向),交给 pickExploreWaypoint 的偏角扇形兜底。
            for (int i = 0; i < 8; i++) {
                double heading = Math.toRadians(i * 45.0D);
                int px = (int) Math.floor(bot.getX() + 44.0D * Math.cos(heading));
                int pz = (int) Math.floor(bot.getZ() + 44.0D * Math.sin(heading));
                BlockPos probe = findGroundAt(world, px, pz);
                if (probe == null || EpisodeMemory.INSTANCE.nearTrail(bot.getUuid(), probe, 16.0D)) {
                    continue;
                }
                exploreHeading = heading;
                break;
            }
        }
        BlockPos picked = pickExploreWaypoint(bot);
        if (picked == null) {
            exploreHeading += Math.PI / 2.0D; // 该航向扇形全选不出 → 旋 90° 再试一次
            picked = pickExploreWaypoint(bot);
        }
        if (picked == null) {
            exploreHops++; // 烧掉一跳额度:防"永远选不出点"时每 tick 重入,额度耗尽由 fail 链收尾
            return false;
        }
        exploreHops++;
        exploreTarget = picked;
        exploreHopStartTick = elapsed;
        exploredSinceFind = true;
        phase = Phase.EXPLORE;
        BotLog.action(bot, "gather_explore_hop",
                "hop", exploreHops,
                "to", picked.getX() + "," + picked.getY() + "," + picked.getZ(),
                "mode", exploreHint == null ? "blind" : "known");
        return true;
    }

    // 跳点选择(照 MoveTask.pickWaypoint 骨架):航向±偏角 {0,±45,±90} × 距离档 {48,40,32} 双层循环;
    // 候选取该列地表落脚点,须干列(湖面悬空格/浅滩水脚全排除);记忆导向模式额外要求候选距记忆点
    // 不比当前远 10%+(防偏角扇形越带越偏)。第一个 startPathTo 不失败的候选即采用(寻路成功即已出发);
    // 同步 A* 实跑封顶 EXPLORE_PATH_ATTEMPTS 次防单 tick 长卡。
    private BlockPos pickExploreWaypoint(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        double bx = bot.getX();
        double bz = bot.getZ();
        double maxHintDistSq = Double.MAX_VALUE;
        if (exploreHint != null) {
            double maxHintDist = Math.sqrt(exploreHint.getSquaredDistance(bot.getBlockPos())) * 1.10D;
            maxHintDistSq = maxHintDist * maxHintDist;
        }
        int pathAttempts = 0;
        for (int deg : EXPLORE_DEFLECTIONS_DEG) {
            double phi = exploreHeading + Math.toRadians(deg);
            double cos = Math.cos(phi);
            double sin = Math.sin(phi);
            for (double dist : EXPLORE_HOP_DISTANCES) {
                BlockPos candidate = findGroundAt(world, (int) Math.floor(bx + dist * cos), (int) Math.floor(bz + dist * sin));
                if (candidate == null || !isDryColumn(world, candidate)) {
                    continue;
                }
                if (exploreHint != null && candidate.getSquaredDistance(exploreHint) > maxHintDistSq) {
                    continue;
                }
                if (pathAttempts >= EXPLORE_PATH_ATTEMPTS) {
                    return null;
                }
                pathAttempts++;
                bot.getActionPack().stopAll();
                if (!bot.getActionPack().startPathTo(candidate).isFailed()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // 干列检查(从 MoveTask 复制):候选脚格及其向下 4 格全部无流体才算"干"。
    // MOTION_BLOCKING_NO_LEAVES 在湖面取到的是水面上方悬空格、在浅滩取到的脚格本身是水,
    // 两类都必须排除——否则跳点把 bot 直接引进水里,探索变送死。
    private static boolean isDryColumn(net.minecraft.server.world.ServerWorld world, BlockPos feet) {
        for (int i = 0; i <= 4; i++) {
            if (!world.getFluidState(feet.down(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // EXPLORE 推进:大步走向跳点,途中每秒轻扫;到点/泡水/超时/断线都收敛回 SURVEY——由 survey 决定
    // 下一步(近处有货接管采集,没货走 fail 链触发下一跳 startExplore),状态机单一出口不发散。
    private void exploreMove(AIPlayerEntity bot) {
        // ① 这一跳走太久没到(跳点其实难达/路线绕远)→ 弃跳回 SURVEY 重扫(扫描半径/限频复位)。
        if (elapsed - exploreHopStartTick > EXPLORE_MOVE_LIMIT) {
            bot.getActionPack().stopAll();
            exploreTarget = null;
            searchRadius = SEARCH_RADIUS;
            lastScanTick = -100;
            phase = Phase.SURVEY;
            return;
        }
        // ② 沾水即弃当前路线(与 roamMove 同款):NavSafetyNet 拖上岸后回 SURVEY 重选。
        if (bot.isTouchingWater()) {
            bot.getActionPack().stopAll();
            exploreTarget = null;
            searchRadius = SEARCH_RADIUS;
            phase = Phase.SURVEY;
            return;
        }
        // ③ 途中轻扫(每 EXPLORE_SCAN_INTERVAL tick,16 格):看到目标方块就收手,交回 SURVEY 精确采集。
        int now = bot.getServer().getTicks();
        if (now - lastExploreScanTick >= EXPLORE_SCAN_INTERVAL) {
            lastExploreScanTick = now;
            BlockPos seen = OreProspector.nearest(bot.getServerWorld(), bot.getBlockPos(), 16,
                    state -> harvestBlocks.contains(state.getBlock()));
            if (seen != null) {
                bot.getActionPack().stopAll();
                exploreTarget = null;
                searchRadius = SEARCH_RADIUS;
                phase = Phase.SURVEY;
                return;
            }
        }
        // ④ 到达跳点(≤3 格):记忆导向走到记忆点 16 格内却没被③拦下=旧情报没货 → 资源点销账,
        // 防下次 startExplore 又朝同一条旧情报奔;之后回 SURVEY(没货由 fail 链继续外探)。
        if (exploreTarget == null || bot.getBlockPos().getSquaredDistance(exploreTarget) <= 9.0D) {
            if (exploreHint != null && bot.getBlockPos().getSquaredDistance(exploreHint) <= 256.0D) {
                io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE.invalidateResource(bot.getUuid(), exploreHint);
                exploreHint = null;
            }
            bot.getActionPack().stopAll();
            exploreTarget = null;
            searchRadius = SEARCH_RADIUS;
            phase = Phase.SURVEY;
            return;
        }
        // ⑤ 半路断线(起步宽限 20t,刚 startPathTo 时 executor 可能仍 idle)→ 同航向重选一跳;
        // 旋 90° 仍选不出 → 回 SURVEY(本跳预算 EXPLORE_MOVE_LIMIT 对整跳含重选连续计)。
        if (elapsed - exploreHopStartTick > 20 && bot.getActionPack().isPathExecutorIdle()) {
            BlockPos repick = pickExploreWaypoint(bot);
            if (repick == null) {
                exploreHeading += Math.PI / 2.0D;
                repick = pickExploreWaypoint(bot);
            }
            if (repick == null) {
                bot.getActionPack().stopAll();
                exploreTarget = null;
                searchRadius = SEARCH_RADIUS;
                phase = Phase.SURVEY;
                return;
            }
            exploreTarget = repick;
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
        // 不可达拉黑生效点:被 goToTarget 拉黑(同目标连续走崩)的坐标从候选流里滤掉——survey 不再
        // 反复重锁同一棵不可达的树(real_wood 实测:重锁→GOTO 崩→重锁 乒乓到 6001t 超时)。
        java.util.UUID botId = bot.getUuid();
        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(bot, harvestBlocks, searchRadius, SEARCH_DOWN, SEARCH_UP,
                pos -> !EpisodeMemory.INSTANCE.isExcluded(botId, pos, now));
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
            // 这片 + 探测范围(96 格)都没树 → 逃离无树片区:roam 局部换片(28-56格)连跳 2 次没跳出 →
            // 优先 explore 远逃(160格)。治"roam 在无树/恶劣区反复跳同点、explore 过早耗尽、耗到 gather_timeout"
            //(实测日志 160048:gather_roam×16 在 4 个点间乒乓、explore 仅 4 跳;非 prospect 节流)。
            if (escapeBarrenArea(bot)) {
                surfaceTried = false; // 新区域重新允许"上浮兜底"
                return;
            }
            // 探索额度也烧完(4 跳 ~190 格都没找到)→ 用专属 reason 让大脑/玩家知道"已走出去找过"。
            fail(exploreHops >= EXPLORE_MAX_HOPS ? "no_resource_after_explore" : "no_resource_nearby");
            return;
        }
        targetPos = choice.pos();
        // 探索得手:RESOURCE_FOUND 入流(蒸馏成知识库资源点,下次同类需求直奔),只记"经历过探索后的首个发现",
        // 避免每次 survey 命中都刷流(8 格去重之外的噪声)。
        if (exploreHops > 0 && exploredSinceFind) {
            io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND, targetPos,
                    Registries.BLOCK.getId(bot.getServerWorld().getBlockState(targetPos).getBlock()).toString());
            exploredSinceFind = false;
            BotLog.action(bot, "gather_explore_found",
                    "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ(),
                    "hops", exploreHops);
        }
        if (choice.direct()) {
            startHarvest(bot);
            return;
        }
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(choice.stand());
    }

    // 挖掘接近:崖壁/高差树纯步行 GOAL_UNREACHABLE 时,改用 startDigPathTo 下沉/掘进过去(与挖矿够
    // 埋藏矿同一原语)。成功发起 → 设 targetPos=树、转 GOTO,由 goToTarget 统一驱动"到达→采集";
    // treeDigTried=true 防 goToTarget 立刻重发。挖掘也不通(罕见:基岩封死/越界)→ false,调用方拉黑换树。
    private boolean tryDigApproach(AIPlayerEntity bot, BlockPos tree, String why) {
        ActionResult dig = bot.getActionPack().startDigPathTo(tree);
        if (dig.isFailed()) {
            return false;
        }
        BotLog.action(bot, "gather_dig_approach",
                "to", tree.getX() + "," + tree.getY() + "," + tree.getZ(), "why", why);
        targetPos = tree.toImmutable();
        lastGotoTarget = targetPos;
        treeDigTried = true;
        gotoFailStreak = 0;
        phase = Phase.GOTO;
        return true;
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
        // R1 悬空/卡死兜底(治平原 gather_timeout):朝树寻路时,若 pathExecutor 卡死(它仍"以为"在走→非idle),
        // 下面整段自愈链(isPathExecutorIdle 分支)成死代码,bot 坐标原地不动直到 6001t 超时
        //(实测 GOTO on_ground=false 160秒0位移)。看门狗:坐标连续 GOTO_STUCK_LIMIT 不动 → 强制 stopAll 清 executor +
        // 拉黑该树 + 回 SURVEY 重选。正常寻路坐标在变、挖掘接近也在推进,均不触发(阈值 4s 远超单块挖掘)。
        BlockPos hereNow = bot.getBlockPos();
        if (hereNow.equals(gotoStuckPos)) {
            if (elapsed - gotoStuckTick >= GOTO_STUCK_LIMIT) {
                bot.getActionPack().stopAll();
                EpisodeMemory.INSTANCE.exclude(bot.getUuid(), targetPos,
                        bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
                BotLog.action(bot, "gather_goto_unstick",
                        "pos", hereNow.toShortString(), "on_ground", bot.isOnGround());
                gotoStuckPos = null;
                phase = Phase.SURVEY;
                return;
            }
        } else {
            gotoStuckPos = hereNow.toImmutable();
            gotoStuckTick = elapsed;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            if (!targetPos.equals(lastGotoTarget)) {
                gotoFailStreak = 0; // 换了新目标,失败计数重开
                treeDigTried = false;
                lastGotoTarget = targetPos;
            }
            // 崖壁/下方树走不到(纯步行 GOAL_UNREACHABLE,real 钻石 67% 失败的头号坎):升级挖掘接近——
            // 像挖矿够埋藏矿一样 startDigPathTo 下沉/掘进过去,而非直接拉黑换树(全是崖壁树时换无可换)。
            // 每个目标只升级一次;挖掘接近也到不了才拉黑(TTL 后复活)。这是"任何地形都能采到木"的关键。
            if (!treeDigTried) {
                treeDigTried = true;
                ActionResult dig = bot.getActionPack().startDigPathTo(targetPos);
                BotLog.action(bot, "gather_dig_approach",
                        "to", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ(),
                        "ok", !dig.isFailed());
                if (!dig.isFailed()) {
                    return; // 挖掘接近已发起,留在 GOTO 等它掘过去
                }
            }
            // 步行+挖掘接近都到不了 → 拉黑换树(survey posFilter 不再重锁;治乒乓死循环)。
            if (++gotoFailStreak >= GOTO_FAIL_EXCLUDE) {
                EpisodeMemory.INSTANCE.exclude(bot.getUuid(), targetPos, bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
                BotLog.action(bot, "gather_target_excluded",
                        "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ(),
                        "fails", gotoFailStreak);
            }
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
