package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * OREDIG(实测#10):可靠的矿石采集,取代 GoalExecutor 的 MINE_ORE 步原本用的 OreSeekTask。
 *
 * OreSeek 的"扫描→A*接近→走廊兜底"接近逻辑在被石头包裹的矿上连续 stuck(#6/#8/#10)。
 * 本任务改用已被验证**永不卡死**的模式:**控制式直挖隧道 + 共享 {@link BlockMiner}**,绝不寻路/走路:
 *  - SCAN:用服务端全数据找最近目标矿(限频);找不到就向下挖一格换层再扫;
 *  - DIG:每 tick 只挖"朝矿方向的下一格"(水平或向下一格),BlockMiner 驱动一块一块成形,
 *    bot 自然跟进;矿进入伸手范围 → 直接挖它,并把相邻同脉矿一起挖净;
 *  - 全程无进展看门狗:超时没破任何块即干净失败,交 GoalExecutor。
 *
 * 自包含状态机(铁律 G1),不在内部 assign;全程主线程(G2)。
 */
public final class OreDigTask extends AbstractTask {
    private static final int MAX_ELAPSED = 9600;        // 8 分钟硬超时(整条矿可能要挖很久)
    private static final int NO_PROGRESS_LIMIT = 200;   // 10s 没破任何块 → 失败
    private static final int SCAN_INTERVAL = 10;
    private static final int SCAN_RADIUS = 24;
    private static final int PROSPECT_RANGE = 64;       // 探矿(大范围定位最近矿)半径——身边扫不到时启用
    private static final int PROSPECT_INTERVAL = 40;    // 探矿较贵(逐区块 section 扫),2s 一次
    private static final int VERTICAL_SCAN = 10;
    // 4.5^2:与 BlockMiner 内部验证一致(5.5 时边缘开挖被 miner 拒→FAILED→矿被误拉黑,geo_wall 实测
    // 锁定 2s 即弃)。历史 5.1 死区的前提已不存在——接近目标现在是矿正下方格,寻路会真走到贴脸位。
    private static final double REACH_SQUARED = 20.25D;
    private static final int MIN_Y = -60;
    private static final int VEIN_CAP = 64;
    private static final int PICKUP_GRACE_TICKS = 30;
    private static final int BONUS_CAP = 8;            // R3 顺路矿单任务上限:白捡是好,改行不行
    private static final int APPROACH_LIMIT = 80;       // P0:锁定矿超过此 tick 仍没靠近 → 判够不到,放弃换矿/下挖
    private static final int STRIP_SEGMENT = 48;        // 覆盖效率:扫描是全知 24 格球,巷道价值=移动覆盖;长段直线减少转向与重叠扫描
    private static final Direction[] STRIP_DIRS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final Set<Block> targetOres;
    private final Set<Item> targetDrops;
    private final int targetCount;
    private final BlockMiner miner = new BlockMiner();
    // 排除项收编进 EpisodeMemory(工作记忆,goal 级生命周期+TTL 复活):原实例 Set 在 replan 后丢失、
    // 又需一次性"特赦"补救;TTL 短排除(30s)语义更细腻——过期自然复活,无需特赦。
    private final Deque<BlockPos> veinQueue = new ArrayDeque<>();

    private int invBaseline;
    private int collected;
    private int lastProgressTick;
    private int lastScanTick = -SCAN_INTERVAL;
    private int lastProspectTick = -100;
    private int pickupGrace;
    private BlockPos targetOre;
    private double lastTargetDist = Double.MAX_VALUE; // P0:锁定矿的历史最近距离²(监控是否在接近)
    private int targetApproachTick;
    private int stripDirIndex = -1;   // 优化1:矿层水平找矿当前掘进方向(STRIP_DIRS 下标),-1=未开始
    private int stripStepsLeft;       // 优化1:当前隧道段剩余格数
    private int lastMinedTick = -100; // 挖掉矿本体的时刻:掉落实体在下 tick 才出现,挖完原地驻留捡取
    private BlockPos bonusOre;        // R3 顺路矿:reach 内的非目标矿,顺手一镐(单块,不追脉)
    private int bonusMined;           // 顺路预算计数(防喧宾夺主)
    private int lastBonusScanTick = -100;

    public OreDigTask(Set<Block> targetOres, int targetCount) {
        this.targetOres = targetOres == null || targetOres.isEmpty()
                ? OreScan.COMMON_ORES
                : OreScan.expandOreFamilies(targetOres);
        this.targetDrops = HarvestCore.expectedDropsFor(this.targetOres);
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "mine_ore";
    }

    @Override
    public String describe() {
        return "OreDig " + collected + "/" + targetCount
                + (targetOre == null ? " (scanning)" : " ->" + targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ());
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // 挖掘/下挖期 bot 基本站着挖,视为 waiting 让 StuckWatcher 不误判(它正是 #10 反复 abort 的元凶);
        // 由本任务自己的 NO_PROGRESS_LIMIT 看门狗负责卡死保护。
        return true;
    }

    // EpisodeMemory 薄包装:排除"够不到/挖空"的矿(TTL 30s 自动复活),goal 级生命周期跨 replan 存活。
    private void excludeOre(AIPlayerEntity bot, BlockPos pos) {
        EpisodeMemory.INSTANCE.exclude(bot.getUuid(), pos, bot.getServer().getTicks(), EpisodeMemory.TTL_SHORT);
    }

    private boolean oreExcluded(AIPlayerEntity bot, BlockPos pos) {
        return EpisodeMemory.INSTANCE.isExcluded(bot.getUuid(), pos, bot.getServer().getTicks());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        targetOre = null;
        // R6 入口地标:开挖处自动 mark(goto_place mine_entry 一步回来;玩家问'矿洞在哪'也答得出)。
        io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid())
                .markPlace("mine_entry", bot.getServerWorld(), bot.getBlockPos());
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        markMineFace(bot);
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    // R6/R7 作业面地标:任务结束处=下次续挖起点。矿种一并 remember,resume_mining 免问。
    private void markMineFace(AIPlayerEntity bot) {
        var mem = io.github.zoyluo.aibot.memory.BotMemoryStore.INSTANCE.of(bot.getUuid());
        mem.markPlace("mine_face", bot.getServerWorld(), bot.getBlockPos());
        String ores = targetOres.stream()
                .map(b -> net.minecraft.registry.Registries.BLOCK.getId(b).toString())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        mem.remember("mine_face_ores", ores);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("ore_dig_timeout collected=" + collected);
            return;
        }
        ServerWorld world = bot.getServerWorld();

        // 溺水熔断:湖底/水下矿会把 bot 勾进水里持续作业,air 耗尽淹死且任务全程无反应
        // (geo_lake 实测:天然湖底铁矿,air 278→0 致死)。MoveTask 的挖掘直行早有同款熔断,
        // 挖矿任务漏配。air<100(剩 5 秒)停手撤单,矿短排除(TTL 复活),交安全网上浮换气。
        if (bot.isSubmergedInWater() && bot.getAir() < 100) {
            if (targetOre != null) {
                excludeOre(bot, targetOre);
            }
            miner.cancel(bot);
            bot.getActionPack().stopAll();
            fail("ore_dig_drowning_abort");
            return;
        }

        // 工具闸:挖不动目标矿(无合格镐)立即失败,交 GoalExecutor 倒推补镐。
        if (!canHarvestAnyTarget(bot)) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetOres));
            return;
        }

        // P0 背包满自救:满了先丢低值占位(每种留 8 个),腾不出位才失败交编排——否则破了矿捡不起白挖。
        if (HarvestCore.isInventoryFull(bot) && !io.github.zoyluo.aibot.action.InventoryAction.dropJunk(bot, 8)) {
            fail("inventory_full");
            return;
        }
        // 收集计数:固定基线绝对增量(刚破矿的掉落物随后落袋会被算进来)。
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 3.0D, 3.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            BotLog.action(bot, "ore_dig_collected", "total", collected + "/" + targetCount);
            // P2 进度播报:挖到就说一声(面板可见),贵重矿尤其有"报喜"的真实感。
            io.github.zoyluo.aibot.brain.BotReporter.INSTANCE.onGoalMessage(bot,
                    "挖到了!" + io.github.zoyluo.aibot.craft.ItemNames.cn(targetDrops.iterator().next())
                    + " " + collected + "/" + targetCount);
        }
        if (collected >= targetCount) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline >= targetCount) {
                markMineFace(bot);
                complete();
            }
            return;
        }

        // 无进展看门狗:NO_PROGRESS_LIMIT 内没破任何块 → 干净失败。fail 前 dump 内部状态,
        // 供无头测试诊断"找到矿却无进展"到底卡在哪个环节(锁定丢失/接近失败/挖不动)。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            // (原"一次性特赦"已被 EpisodeMemory 的 TTL 短排除取代:30s 自动复活,比大赦更细腻。)
            BotLog.action(bot, "ore_dig_stall_dump",
                    "target", targetOre == null ? "none"
                            : targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ(),
                    "dist", targetOre == null ? "-"
                            : String.format("%.1f", Math.sqrt(bot.getEyePos().squaredDistanceTo(targetOre.toCenterPos()))),
                    "miner", miner.target() == null ? "idle"
                            : miner.target().getX() + "," + miner.target().getY() + "," + miner.target().getZ(),
                    "ignored", EpisodeMemory.INSTANCE.excludedCount(bot.getUuid()),
                    "vein_queue", veinQueue.size(),
                    "strip_left", stripStepsLeft);
            miner.cancel(bot);
            fail("ore_dig_no_progress collected=" + collected);
            return;
        }

        // 1) 先清相邻矿脉队列(挖到一块矿后,把同脉相邻矿一起挖净)。
        if (advanceVein(bot, world)) {
            return;
        }

        // R3 顺路矿(真实玩家肌肉记忆):赶路/掘进途中伸手可及处出现非目标矿——煤是燃料刚需、
        // 铁是工具通货,白送的不捡是傻。锁定目标矿的接近途中正是顺路高发段(geo_bonus 首验:
        // 原来只在'无锁定'分支扫,掘进全程锁着铁,顺路永不触发)。唯一不顺的时机:miner 正咬着
        // 目标矿(挖一半换目标清进度)。约束:单块不追脉、预算封顶、不计目标数。
        boolean bitingTarget = targetOre != null && miner.target() != null && miner.target().equals(targetOre);
        if (bonusOre == null && !bitingTarget && bonusMined < BONUS_CAP
                && bot.getServer().getTicks() - lastBonusScanTick >= SCAN_INTERVAL
                && !HarvestCore.isInventoryFull(bot)) {
            lastBonusScanTick = bot.getServer().getTicks();
            bonusOre = scanBonusOre(bot, world);
        }
        if (bonusOre != null) {
            if (!OreScan.isOreBlock(world.getBlockState(bonusOre).getBlock()) || !withinReach(bot, bonusOre)) {
                bonusOre = null; // 挖完(executor 顺手)或走远:放手,别为顺路矿回头
            } else {
                BlockMiner.Status st = miner.target() != null && miner.target().equals(bonusOre)
                        ? miner.tick(bot)
                        : beginMine(bot, bonusOre);
                targetApproachTick = elapsed; // 顺路一镐不算接近停滞,别让 APPROACH_LIMIT 误杀目标矿
                if (st == BlockMiner.Status.DONE) {
                    bonusMined++;
                    lastMinedTick = elapsed;
                    lastProgressTick = elapsed;
                    HarvestCore.forcePickupNearbyAnyOf(bot, null, 7.0D, 4.0D); // 捡一切:掉落不在 targetDrops 里
                    BotLog.action(bot, "ore_dig_bonus", "pos", bonusOre.toShortString(),
                            "total", bonusMined + "/" + BONUS_CAP);
                    bonusOre = null;
                } else if (st == BlockMiner.Status.FAILED) {
                    excludeOre(bot, bonusOre);
                    bonusOre = null;
                }
                return;
            }
        }

        // 2) 当前有锁定矿:可达就挖它(挖到后入脉队列),不可达就朝它挖一格隧道。
        if (targetOre != null) {
            if (!OreScan.isOre(world.getBlockState(targetOre), targetOres)) {
                // 矿没了——多数是寻路执行器接近时把"头位=矿"顺手挖掉了(approach 目标=矿正下方的设计),
                // 掉落已在地上:开驻留窗大半径捡(geo_wall 实测 mine_complete 由执行器打、不走 DONE 分支,
                // 不在这接驻留就 0 捡取白挖)。同脉排队照旧。
                lastMinedTick = elapsed;
                HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 7.0D, 4.0D);
                queueVeinAround(bot, world, targetOre);
                targetOre = null;
                return;
            }
            // P0:接近监控——朝矿挖了一阵仍没靠近(斜下方够不到等)→ 放弃该矿,别原地空转
            //(实测在 Y=48 反复锁定斜下方钻石、dist 卡死、no_progress 11 分钟的根因)。
            double dist2 = bot.getEyePos().squaredDistanceTo(targetOre.toCenterPos());
            if (dist2 < lastTargetDist - 0.25D) {
                lastTargetDist = dist2;
                targetApproachTick = elapsed;
                // 接近也是进展:远矿 DIG 接近一格一挖,16 格隧道就要 ~190t,只认"挖到矿"的
                // no_progress(200t)会把正常长接近误杀在半路(geo_rich 单跑实测 dist 16→停在 201t)。
                lastProgressTick = elapsed;
            } else if (elapsed - targetApproachTick > APPROACH_LIMIT) {
                excludeOre(bot, targetOre);
                BotLog.action(bot, "ore_dig_unreachable_skip",
                        "pos", targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ());
                targetOre = null;
                lastTargetDist = Double.MAX_VALUE;
                // 主动换目标是决策性进展:嵌深处的天然矿可能要连排除好几块才轮到可达矿/富区兜底,
                // 不刷进度的话 no_progress(200t)会在合理轮换中途误杀任务。真卡死(同矿反复
                // reject 不 skip)不会走到这,看门狗照常生效。
                lastProgressTick = elapsed;
                return;
            }
            // 挖掘锁定:已对这块矿开挖就继续挖完,不管当前是否仍在 reach 内——bot 站在阶梯上微移会让
            // dist 在 reach 边缘(4.5)来回抖,原逻辑 reach 内开挖→出 reach 切去挖隧道格→回 reach 重新开挖,
            // 挖掘进度每次清零永远挖不完(实测 mine_start 5 坐标轮换 1s 一换、石镐 2.5s 的矿 300t 零产出)。
            // bot 真走远时 BlockMiner 自身的失败判定会兜底(FAILED→ignored)。
            boolean miningTarget = miner.target() != null && miner.target().equals(targetOre);
            if (miningTarget || withinReach(bot, targetOre)) {
                // P0 封岩浆再挖(真实玩家标准操作):矿邻面贴岩浆,挖掉矿的瞬间岩浆涌入——烧 bot+烧掉落。
                // 独立封堵阶段:岩浆格可能比矿远一格,刚进 reach 时够不着 → 继续贴近(向矿正下走),
                // 够着了用低值方块替换岩浆源(一 tick 一格);没块可封才安全弃挖(命比矿值钱)。
                if (!miningTarget) {
                    BlockPos lava = adjacentDangerFluidOf(world, targetOre);
                    if (lava != null) {
                        var blockSlot = io.github.zoyluo.aibot.action.MaterialPalette.pickAnyBlockSlot(bot);
                        if (blockSlot.isEmpty()) {
                            BotLog.action(bot, "ore_dig_fluid_unsealable", "ore", targetOre.toShortString());
                            excludeOre(bot, targetOre);
                            targetOre = null;
                            return;
                        }
                        if (bot.getEyePos().squaredDistanceTo(lava.toCenterPos()) <= REACH_SQUARED) {
                            // 放置走主手:镐在手时对浆格交互被原版判 PASS 静默吞掉
                            //(geo_lava 实测 80 ticks 零放置日志,封堵纹丝不动直到接近超时弃矿)。
                            io.github.zoyluo.aibot.action.InventoryAction.equipFromSlot(bot, blockSlot.getAsInt());
                            var sealResult = io.github.zoyluo.aibot.action.BuildAction.placeBlockAt(bot, lava);
                            if (!sealResult.isFailed()) {
                                BotLog.action(bot, "ore_dig_fluid_seal", "sealed", lava.toShortString());
                                lastProgressTick = elapsed; // 封堵也是进展
                            } else {
                                BotLog.action(bot, "ore_dig_seal_fail",
                                        "lava", lava.toShortString(), "reason", sealResult.reason());
                            }
                        } else if (bot.getActionPack().isPathExecutorIdle()) {
                            bot.getActionPack().startDigPathTo(targetOre.down()); // 贴近到封得着
                        }
                        return;
                    }
                }
                BlockMiner.Status st = miningTarget
                        ? miner.tick(bot)
                        : beginMine(bot, targetOre);
                if (st == BlockMiner.Status.DONE) {
                    // 掉落捡取半径跟上 reach:寻路接近停在 reach 边缘(5.5)挖,掉落落在矿位、
                    // 超出每 tick 3 格被动捡取(旧贴脸直挖 1-2 格才没暴露);挖掉即定向大半径捡一把,
                    // 否则 collected 不涨、bot 被下一个目标拉走白挖(geo_wall 实测 mine_complete 后 0/1)。
                    lastMinedTick = elapsed;
                    HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 7.0D, 4.0D);
                    queueVeinAround(bot, world, targetOre);
                    targetOre = null;
                    lastProgressTick = elapsed;
                } else if (st == BlockMiner.Status.FAILED) {
                    excludeOre(bot, targetOre);
                    targetOre = null;
                }
                return;
            }
            // 不在 reach → 统一接近原语:挖掘感知寻路直达矿邻位(A* DIG 大预算,终点豁免允许
            // "挖开即站"的实心格)。任务私有的"朝矿挖一格隧道"几何特判(digTowardStep)就此退役——
            // 它在山体侧面矿上"挖了洞人不进洞"(实测 dist 卡 5.5 三连败),而寻路执行器
            // (PathExecutor.DIG_THROUGH)天然"挖完走进去"。寻路被拒(节流/无解)时这个 tick 空转,
            // 接近监控(APPROACH_LIMIT)兜底换矿。
            if (bot.getActionPack().isPathExecutorIdle()) {
                // 接近目标=矿的下方格:DIG 邻居只有水平四向,矿高一格时同层泛洪永远够不到终点
                //(geo_wall 实测 explored=8699 全图泛洪 TIMEOUT)。站位选矿正下,头位恰=矿格——
                // 执行器挖头位时顺手把矿挖掉,掉落物落脚边直接入袋,反而少走一步。
                // 例外:矿贴岩浆时不能让执行器顺手挖(挖头位=挖矿即涌浆)——接近目标改反岩浆侧水平邻位,
                // 站安全面、岩浆格在 reach 内,封堵阶段封完才开挖。
                var approach = bot.getActionPack().startDigPathTo(approachGoalFor(world, targetOre));
                if (approach.isFailed() && !"pathfinding_throttled".equals(approach.reason())) { // 观测:首败必打(节流不打)
                    BotLog.action(bot, "ore_dig_approach_rejected", "why", approach.reason(),
                            "target", targetOre.toShortString());
                    // A* 接近无解的兜底(real_diamond ore_dig 主因:深层 8-14 格全实心石,DIG-A* 超
                    // 24k 节点/50ms 预算 TIMEOUT → bot 原地不动 → dist 不缩 → 200t no_progress)。
                    // 退回控制式掘进 digTowardStep:朝矿一格一格挖脚头位+走进去(strip 同款可靠原语,
                    // 不吃 A* 预算),保证 dist 持续缩、不空转。只在 A* 真失败时触发——A* 能解的干净场景
                    // (geo_shaft/cave/deep 全 PASS)走不到这,零回归风险。
                    digTowardStep(bot, world, targetOre);
                }
            }
            return;
        }

        // 挖完驻留:掉落实体在破块的下一 tick 才落地,立刻奔赴新目标会永远错过(geo_wall 实测
        // mine_complete 后 0 捡取、collected 不涨白挖)。原地等 15t 持续大半径捡,捡到计数自然推进。
        if (elapsed - lastMinedTick < 15) {
            HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 7.0D, 4.0D);
            return;
        }

        // 3) 无锁定矿:扫描最近目标矿(限频)。
        int now = bot.getServer().getTicks();
        if (now - lastScanTick < SCAN_INTERVAL) {
            return;
        }
        lastScanTick = now;
        BlockPos found = nearestOre(bot, world);
        if (found != null) {
            targetOre = found;
            lastTargetDist = Double.MAX_VALUE;  // P0:新锁定矿,重置接近监控
            targetApproachTick = elapsed;
            // 情景记忆:资源发现入流 → 蒸馏成资源点(8 格去重),下次"附近有没有铁"先问知识库不瞎挖。
            io.github.zoyluo.aibot.memory.EpisodeLog.INSTANCE.record(bot,
                    io.github.zoyluo.aibot.memory.EpisodeLog.Type.RESOURCE_FOUND, found,
                    net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(found).getBlock()).toString());
            BotLog.action(bot, "ore_dig_found",
                    "pos", found.getX() + "," + found.getY() + "," + found.getZ(),
                    "dist", (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(found)),
                    "collected", collected + "/" + targetCount);
            return;
        }
        // 近处(24 格)无矿 → 大范围探矿(64 格,移植玩家 magic mod 的 HelmetOreLocator 扫描)定位最近矿脉,
        // 锁定后由上面的 digTowardStep 定向挖隧道过去。比盲目 strip 高效——能找到几十格外的钻石,不再"附近没矿就放弃"。
        BlockPos prospected = prospect(bot, world);
        if (prospected != null) {
            targetOre = prospected;
            lastTargetDist = Double.MAX_VALUE;
            targetApproachTick = elapsed;
            BotLog.action(bot, "ore_dig_prospected",
                    "pos", prospected.getX() + "," + prospected.getY() + "," + prospected.getZ(),
                    "dist", (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(prospected)));
            return;
        }
        // 探矿也没有 → 先问知识库富矿区(以前总在那挖到的地方,128 格内、≥3 点聚在 24 格):
        // 簇心是"资源点坐标"不是矿格——当 targetOre 用会被"矿没了"分支秒清成死循环(实测每秒
        // 重触发原地打转)。正确语义=导航去富区,人到了近距扫描自然接管;到了还没矿说明记忆过期,
        // 销掉这片资源点换下一策略。
        for (Block oreBlock : targetOres) {
            String oreId = net.minecraft.registry.Registries.BLOCK.getId(oreBlock).toString();
            var rich = io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE
                    .richZoneNear(bot.getUuid(), oreId, bot.getBlockPos(), 128, 3, 24);
            if (rich.isPresent() && !oreExcluded(bot, rich.get())) {
                BlockPos zone = rich.get();
                if (bot.getBlockPos().isWithinDistance(zone, 16)) {
                    io.github.zoyluo.aibot.memory.KnowledgeBase.INSTANCE
                            .invalidateResource(bot.getUuid(), zone);
                    BotLog.action(bot, "ore_dig_rich_zone_stale", "at", zone.toShortString());
                } else if (bot.getActionPack().isPathExecutorIdle()) {
                    // walk 优先(startPathTo 两阶段):富区常在百格级,大预算 DIG 单阶段 50ms 必
                    // TIMEOUT→每个冷却期重发一次失败寻路,原地风暴到超时(实测每秒 2 发零移动)。
                    bot.getActionPack().startPathTo(zone);
                    BotLog.action(bot, "ore_dig_rich_zone", "to", zone.toShortString());
                    lastProgressTick = elapsed; // 启程也是进展
                }
                return;
            }
        }
        // 水平 strip-mine 掘进暴露新矿面(每前进一格,下一轮 nearestOre 都会扫到隧道两侧新矿);
        // 一整段(STRIP_SEGMENT)挖完仍无矿 → 向下换一层 + 换个水平方向继续。比旧的"只垂直换层"找矿快得多
        //(实测:Y=16 这片铁矿稀疏,旧逻辑原地 201t 扫不到就 no_progress 失败 → goal 失败 → 大脑接管手动挖耗尽轮次)。
        if (stripStepsLeft <= 0) {
            if (stripDirIndex >= 0) {
                // 跳 4 层换平面:垂直扫描 VERTICAL_SCAN=±10,逐层下沉的扫描区 90% 重叠纯浪费;
                // 跳 4 仍有 6 层重叠余量不漏矿,贫瘠区迁移速度 ×4。
                for (int i = 0; i < 4; i++) {
                    digDownOneLayer(bot, world);
                }
            }
            stripDirIndex = (stripDirIndex + 1) % STRIP_DIRS.length;
            stripStepsLeft = STRIP_SEGMENT;
            return;
        }
        stripStepsLeft--;
        // 定距插火把(真实玩家 strip 标准操作):光照 <8 的巷道刷怪,不照明等于给自己挖刷怪走廊。
        // 每 10 格一支、光照不足才插、有火把才插——照明是增益不是前置,缺火把不阻塞掘进。
        if (stripStepsLeft % 10 == 0
                && world.getLightLevel(net.minecraft.world.LightType.BLOCK, bot.getBlockPos()) < 8) {
            var torchSlot = io.github.zoyluo.aibot.action.InventoryAction.findItem(bot, net.minecraft.item.Items.TORCH);
            // R5 火把自补:没火把但有煤有棍(挖矿常态——顺路煤+随身棍)就地合 4 支(2x2 免台,
            // 纯背包变换与 CraftTask 内核同式)。缺料不强求,照明始终是增益不是前置。
            if (torchSlot.isEmpty()) {
                var coal = io.github.zoyluo.aibot.action.InventoryAction.findItem(bot, net.minecraft.item.Items.COAL);
                var stick = io.github.zoyluo.aibot.action.InventoryAction.findItem(bot, net.minecraft.item.Items.STICK);
                if (coal.isPresent() && stick.isPresent()
                        && io.github.zoyluo.aibot.action.InventoryAction.removeItems(bot, net.minecraft.item.Items.COAL, 1)
                        && io.github.zoyluo.aibot.action.InventoryAction.removeItems(bot, net.minecraft.item.Items.STICK, 1)) {
                    io.github.zoyluo.aibot.action.InventoryAction.giveItem(bot,
                            new net.minecraft.item.ItemStack(net.minecraft.item.Items.TORCH, 4));
                    BotLog.action(bot, "ore_dig_torch_crafted", "count", 4);
                    torchSlot = io.github.zoyluo.aibot.action.InventoryAction.findItem(bot, net.minecraft.item.Items.TORCH);
                }
            }
            if (torchSlot.isPresent()) {
                io.github.zoyluo.aibot.action.InventoryAction.equipFromSlot(bot, torchSlot.getAsInt());
                if (!io.github.zoyluo.aibot.action.BuildAction.placeBlockAt(bot, bot.getBlockPos()).isFailed()) {
                    BotLog.action(bot, "ore_dig_torch", "pos", bot.getBlockPos().toShortString());
                }
            }
        }
        Direction dir = STRIP_DIRS[stripDirIndex];
        digTowardStep(bot, world, bot.getBlockPos().offset(dir, 2)); // 复用掘进原语:挖脚位+头位→走进去
    }

    // ── 矿脉:挖净已锁定矿周围的同脉相邻矿 ──
    private boolean advanceVein(AIPlayerEntity bot, ServerWorld world) {
        if (veinQueue.isEmpty()) {
            return false;
        }
        BlockPos v = veinQueue.peekFirst();
        if (v == null || !OreScan.isOre(world.getBlockState(v), targetOres)) {
            veinQueue.pollFirst();
            return true;
        }
        if (!withinReach(bot, v)) {
            // 脉里这块够不到 → 朝它挖一格(罕见,矿脉一般连续);够不到太久由看门狗兜底
            digTowardStep(bot, world, v);
            return true;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(v)
                ? miner.tick(bot)
                : beginMine(bot, v);
        if (st == BlockMiner.Status.DONE) {
            queueVeinAround(bot, world, v);
            veinQueue.pollFirst();
            lastProgressTick = elapsed;
        } else if (st == BlockMiner.Status.FAILED) {
            veinQueue.pollFirst();
        }
        return true;
    }

    private void queueVeinAround(AIPlayerEntity bot, ServerWorld world, BlockPos around) {
        for (BlockPos p : OreScan.veinFrom(world, around, targetOres, VEIN_CAP)) {
            if (!oreExcluded(bot, p)) {
                veinQueue.addLast(p.toImmutable());
            }
        }
    }

    // ── 朝目标挖一格隧道(只挖伸手可及的那一格,BlockMiner 驱动) ──
    private void digTowardStep(AIPlayerEntity bot, ServerWorld world, BlockPos goal) {
        BlockPos feet = bot.getBlockPos();
        BlockPos step = stepToward(feet, goal);
        if (step == null) {
            excludeOre(bot, goal);
            if (goal.equals(targetOre)) {
                targetOre = null;
            }
            return;
        }
        // 安全:目标格相邻有岩浆 → 放弃此矿。
        if (OreScan.adjacentHazard(world, step)) {
            excludeOre(bot, goal);
            if (goal.equals(targetOre)) {
                targetOre = null;
            }
            miner.cancel(bot);
            return;
        }
        // 要清出身位:挖 step(脚位)与 step.up()(头位)里第一个固体。
        BlockPos solid = firstSolid(world, step, step.up());
        if (solid == null) {
            // 该格已挖通(空气)→ 走进去占住这一格,把 bot 推进到隧道前沿,再继续朝矿挖。
            // 必须主动 walk:本任务不寻路,水平推进只能靠这一步,否则会站着不动直到看门狗失败。
            miner.cancel(bot);
            bot.getActionPack().startWalkTo(step.toCenterPos());
            // 走到新格也算进展(避免在"挖通一段后走过去"的几 tick 里被看门狗误杀)。
            if (bot.getBlockPos().equals(step)) {
                lastProgressTick = elapsed;
            }
            return;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(solid)
                ? miner.tick(bot)
                : beginMine(bot, solid);
        if (st == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed;
        } else if (st == BlockMiner.Status.FAILED) {
            excludeOre(bot, goal);
            if (goal.equals(targetOre)) {
                targetOre = null;
            }
        }
    }

    // 台阶式斜向下换层(拟人 + 安全):绝不直挖脚下——下方可能是水/岩浆,一镐捅穿就溺水/葬身岩浆。
    // 沿当前掘进方向斜前下方挖"下一级台阶"(ahead 头位 + next 脚位),遇水/岩浆就换斜下方向,
    // 像挖楼梯一样下到新平面(与 DescendToYTask / DigDownTask 台阶逻辑一致)。
    private void digDownOneLayer(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        if (feet.down().getY() <= MIN_Y) {
            fail("ore_dig_reached_min_y collected=" + collected);
            return;
        }
        Direction dir = safeStairDir(world, feet);
        if (dir == null) {
            // 四个斜下方向都被水/岩浆挡 → 硬停交还(规避层"困死撤离"兜底)。
            fail("ore_dig_blocked_lava collected=" + collected);
            return;
        }
        BlockPos ahead = feet.offset(dir);   // 下一级头位 (x+d, y)
        BlockPos next = ahead.down();         // 下一级站位 (x+d, y-1)
        BlockPos solid = firstSolid(world, next, ahead);
        if (solid != null) {
            BlockMiner.Status st = miner.target() != null && miner.target().equals(solid)
                    ? miner.tick(bot)
                    : beginMine(bot, solid);
            if (st == BlockMiner.Status.DONE) {
                lastProgressTick = elapsed;
            }
            return;
        }
        // 身位已通 → 斜下踏到下一级台阶(1 格微位移,非 roam 那种跨图闪现)。
        bot.getActionPack().descendInto(next);
        lastProgressTick = elapsed;
    }

    // 选一个"不挨水/岩浆"的斜下台阶方向:优先沿当前掘进方向(自然延续隧道),否则按 STRIP_DIRS 顺序找;
    // 四面斜下都被水/岩浆挡返回 null。
    private Direction safeStairDir(ServerWorld world, BlockPos feet) {
        int base = stripDirIndex < 0 ? 0 : stripDirIndex;
        for (int i = 0; i < STRIP_DIRS.length; i++) {
            Direction dir = STRIP_DIRS[(base + i) % STRIP_DIRS.length];
            BlockPos ahead = feet.offset(dir);
            BlockPos next = ahead.down();
            if (!isLava(world, next) && !isLava(world, next.down()) && !isLava(world, ahead)
                    && !isWater(world, next) && !isWater(world, next.down())) {
                return dir;
            }
        }
        return null;
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    // 接近落点:常规=矿正下(执行器顺手挖,少走一步);贴岩浆=反岩浆侧水平邻位(安全封堵位)。
    private static BlockPos approachGoalFor(ServerWorld world, BlockPos ore) {
        BlockPos lava = adjacentDangerFluidOf(world, ore);
        if (lava == null) {
            return ore.down();
        }
        int dx = Integer.compare(ore.getX(), lava.getX());
        int dz = Integer.compare(ore.getZ(), lava.getZ());
        Direction away = dx != 0 ? (dx > 0 ? Direction.EAST : Direction.WEST)
                : dz != 0 ? (dz > 0 ? Direction.SOUTH : Direction.NORTH)
                : Direction.NORTH; // 岩浆在正上/正下 → 任选水平面
        return ore.offset(away);
    }

    // 危险流体(岩浆|水)统一:水虽不烧人,但挖开矿的瞬间涌入会推走 bot 和掉落物、淹没巷道,
    // 接近监控反复超时弃矿(深层含水矿高发)。封堵语义与岩浆完全一致——放块替换源。
    private static BlockPos adjacentDangerFluidOf(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos side = pos.offset(d);
            var fs = world.getFluidState(side);
            if (fs.isIn(FluidTags.LAVA) || fs.isIn(FluidTags.WATER)) {
                return side;
            }
        }
        return null;
    }

    private BlockMiner.Status beginMine(AIPlayerEntity bot, BlockPos pos) {
        bot.getActionPack().stopMovement(); // 互斥:开挖矿本体即停掉接近寻路(执行器的 DIG_THROUGH 与 BlockMiner 不抢手)
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    // R3 顺路矿扫描:bot 周身 ±2(伸手范围)找任何"非目标、可挖、不贴危险流体"的矿。
    // 范围刻意小(5×5×5=125 格逐查,限频复用 SCAN_INTERVAL 节拍)——顺路的定义就是不绕路。
    private BlockPos scanBonusOre(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        for (BlockPos p : BlockPos.iterate(feet.add(-2, -1, -2), feet.add(2, 3, 2))) {
            Block b = world.getBlockState(p).getBlock();
            if (!OreScan.isOreBlock(b) || targetOres.contains(b)) {
                continue;
            }
            BlockPos pos = p.toImmutable();
            if (oreExcluded(bot, pos) || !withinReach(bot, pos)) {
                continue;
            }
            if (!ToolTier.canHarvestWithInventory(bot, world.getBlockState(pos))) {
                continue; // 挖不动的不顺(挖钻石路过绿宝石但只有石镐:别空手刨)
            }
            if (adjacentDangerFluidOf(world, pos) != null) {
                continue; // 贴浆/贴水的矿不顺路——顺路的代价必须是零
            }
            return pos;
        }
        return null;
    }

    // 探矿:近处扫不到矿时,在 PROSPECT_RANGE 大范围(只扫已加载区块)定位最近的目标矿;限频护 TPS。
    private BlockPos prospect(AIPlayerEntity bot, ServerWorld world) {
        int now = bot.getServer().getTicks();
        if (now - lastProspectTick < PROSPECT_INTERVAL) {
            return null;
        }
        lastProspectTick = now;
        // 拉黑过滤:不带 posFilter 时,unreachable_skip 刚排除的矿会被 prospect 原样再选——
        // skip→prospect→同矿→skip 死循环直到 no_progress(geo_rich 套跑实测 637,47,-11 五连)。
        return OreProspector.nearest(world, bot.getBlockPos(), PROSPECT_RANGE,
                state -> OreScan.isOre(state, targetOres),
                p -> !oreExcluded(bot, p));
    }

    private BlockPos nearestOre(AIPlayerEntity bot, ServerWorld world) {
        BlockPos origin = bot.getBlockPos();
        BlockPos min = origin.add(-SCAN_RADIUS, -VERTICAL_SCAN, -SCAN_RADIUS);
        BlockPos max = origin.add(SCAN_RADIUS, VERTICAL_SCAN, SCAN_RADIUS);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (oreExcluded(bot, pos) || !OreScan.isOre(world.getBlockState(pos), targetOres)) {
                continue;
            }
            double dist = origin.getSquaredDistance(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private boolean canHarvestAnyTarget(AIPlayerEntity bot) {
        for (Block ore : targetOres) {
            if (ToolTier.canHarvestWithInventory(bot, ore.getDefaultState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean withinReach(AIPlayerEntity bot, BlockPos pos) {
        return bot.getEyePos().squaredDistanceTo(pos.toCenterPos()) <= REACH_SQUARED;
    }

    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir()) {
            return a.toImmutable();
        }
        if (!world.getBlockState(b).isAir()) {
            return b.toImmutable();
        }
        return null;
    }

    // 朝目标的下一格:竖直优先(目标更低则下挖),否则较大的水平分量(避免对角穿墙角)。
    private static BlockPos stepToward(BlockPos from, BlockPos target) {
        int dy = target.getY() - from.getY();
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        if (dy < 0 && Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
            return from.down();
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return from.offset(dx > 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST);
        }
        if (dz != 0) {
            return from.offset(dz > 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH);
        }
        if (dy < 0) {
            return from.down();
        }
        if (dy > 0) {
            return from.up();
        }
        return null;
    }
}
