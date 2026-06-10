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
    private static final double REACH_SQUARED = 20.25D; // 4.5^2,与 OreSeek 一致
    private static final int MIN_Y = -60;
    private static final int VEIN_CAP = 64;
    private static final int PICKUP_GRACE_TICKS = 30;
    private static final int APPROACH_LIMIT = 80;       // P0:锁定矿超过此 tick 仍没靠近 → 判够不到,放弃换矿/下挖
    private static final int STRIP_SEGMENT = 16;        // S(优化1):矿层扫不到矿时,水平掘进的隧道段长(暴露两侧矿面)
    private static final Direction[] STRIP_DIRS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final Set<Block> targetOres;
    private final Set<Item> targetDrops;
    private final int targetCount;
    private final BlockMiner miner = new BlockMiner();
    private final Set<BlockPos> ignored = new HashSet<>();
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

    @Override
    protected void onStart(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        targetOre = null;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("ore_dig_timeout collected=" + collected);
            return;
        }
        ServerWorld world = bot.getServerWorld();

        // 工具闸:挖不动目标矿(无合格镐)立即失败,交 GoalExecutor 倒推补镐。
        if (!canHarvestAnyTarget(bot)) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetOres));
            return;
        }

        // 收集计数:固定基线绝对增量(刚破矿的掉落物随后落袋会被算进来)。
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 3.0D, 3.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            BotLog.action(bot, "ore_dig_collected", "total", collected + "/" + targetCount);
        }
        if (collected >= targetCount) {
            miner.cancel(bot);
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline >= targetCount) {
                complete();
            }
            return;
        }

        // 无进展看门狗:NO_PROGRESS_LIMIT 内没破任何块 → 干净失败。fail 前 dump 内部状态,
        // 供无头测试诊断"找到矿却无进展"到底卡在哪个环节(锁定丢失/接近失败/挖不动)。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            BotLog.action(bot, "ore_dig_stall_dump",
                    "target", targetOre == null ? "none"
                            : targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ(),
                    "dist", targetOre == null ? "-"
                            : String.format("%.1f", Math.sqrt(bot.getEyePos().squaredDistanceTo(targetOre.toCenterPos()))),
                    "miner", miner.target() == null ? "idle"
                            : miner.target().getX() + "," + miner.target().getY() + "," + miner.target().getZ(),
                    "ignored", ignored.size(),
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

        // 2) 当前有锁定矿:可达就挖它(挖到后入脉队列),不可达就朝它挖一格隧道。
        if (targetOre != null) {
            if (!OreScan.isOre(world.getBlockState(targetOre), targetOres)) {
                // 矿没了(已被挖/被改)→ 把它周围同脉矿排队,然后回扫描
                queueVeinAround(world, targetOre);
                targetOre = null;
                return;
            }
            // P0:接近监控——朝矿挖了一阵仍没靠近(斜下方够不到等)→ 放弃该矿,别原地空转
            //(实测在 Y=48 反复锁定斜下方钻石、dist 卡死、no_progress 11 分钟的根因)。
            double dist2 = bot.getEyePos().squaredDistanceTo(targetOre.toCenterPos());
            if (dist2 < lastTargetDist - 0.25D) {
                lastTargetDist = dist2;
                targetApproachTick = elapsed;
            } else if (elapsed - targetApproachTick > APPROACH_LIMIT) {
                ignored.add(targetOre);
                BotLog.action(bot, "ore_dig_unreachable_skip",
                        "pos", targetOre.getX() + "," + targetOre.getY() + "," + targetOre.getZ());
                targetOre = null;
                lastTargetDist = Double.MAX_VALUE;
                return;
            }
            if (withinReach(bot, targetOre)) {
                BlockMiner.Status st = miner.target() != null && miner.target().equals(targetOre)
                        ? miner.tick(bot)
                        : beginMine(bot, targetOre);
                if (st == BlockMiner.Status.DONE) {
                    queueVeinAround(world, targetOre);
                    targetOre = null;
                    lastProgressTick = elapsed;
                } else if (st == BlockMiner.Status.FAILED) {
                    ignored.add(targetOre);
                    targetOre = null;
                }
                return;
            }
            // 不可达 → 朝矿挖一格隧道(BlockMiner 驱动,绝不寻路)。
            digTowardStep(bot, world, targetOre);
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
        // 探矿也没有 → 水平 strip-mine 掘进暴露新矿面(每前进一格,下一轮 nearestOre 都会扫到隧道两侧新矿);
        // 一整段(STRIP_SEGMENT)挖完仍无矿 → 向下换一层 + 换个水平方向继续。比旧的"只垂直换层"找矿快得多
        //(实测:Y=16 这片铁矿稀疏,旧逻辑原地 201t 扫不到就 no_progress 失败 → goal 失败 → 大脑接管手动挖耗尽轮次)。
        if (stripStepsLeft <= 0) {
            if (stripDirIndex >= 0) {
                digDownOneLayer(bot, world); // 一段挖完,顺带下沉一层换新平面
            }
            stripDirIndex = (stripDirIndex + 1) % STRIP_DIRS.length;
            stripStepsLeft = STRIP_SEGMENT;
            return;
        }
        stripStepsLeft--;
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
            queueVeinAround(world, v);
            veinQueue.pollFirst();
            lastProgressTick = elapsed;
        } else if (st == BlockMiner.Status.FAILED) {
            veinQueue.pollFirst();
        }
        return true;
    }

    private void queueVeinAround(ServerWorld world, BlockPos around) {
        for (BlockPos p : OreScan.veinFrom(world, around, targetOres, VEIN_CAP)) {
            if (!ignored.contains(p)) {
                veinQueue.addLast(p.toImmutable());
            }
        }
    }

    // ── 朝目标挖一格隧道(只挖伸手可及的那一格,BlockMiner 驱动) ──
    private void digTowardStep(AIPlayerEntity bot, ServerWorld world, BlockPos goal) {
        BlockPos feet = bot.getBlockPos();
        BlockPos step = stepToward(feet, goal);
        if (step == null) {
            ignored.add(goal);
            if (goal.equals(targetOre)) {
                targetOre = null;
            }
            return;
        }
        // 安全:目标格相邻有岩浆 → 放弃此矿。
        if (OreScan.adjacentHazard(world, step)) {
            ignored.add(goal);
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
            ignored.add(goal);
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

    private BlockMiner.Status beginMine(AIPlayerEntity bot, BlockPos pos) {
        miner.begin(bot, pos);
        return miner.tick(bot);
    }

    // 探矿:近处扫不到矿时,在 PROSPECT_RANGE 大范围(只扫已加载区块)定位最近的目标矿;限频护 TPS。
    private BlockPos prospect(AIPlayerEntity bot, ServerWorld world) {
        int now = bot.getServer().getTicks();
        if (now - lastProspectTick < PROSPECT_INTERVAL) {
            return null;
        }
        lastProspectTick = now;
        return OreProspector.nearest(world, bot.getBlockPos(), targetOres, PROSPECT_RANGE);
    }

    private BlockPos nearestOre(AIPlayerEntity bot, ServerWorld world) {
        BlockPos origin = bot.getBlockPos();
        BlockPos min = origin.add(-SCAN_RADIUS, -VERTICAL_SCAN, -SCAN_RADIUS);
        BlockPos max = origin.add(SCAN_RADIUS, VERTICAL_SCAN, SCAN_RADIUS);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (ignored.contains(pos) || !OreScan.isOre(world.getBlockState(pos), targetOres)) {
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
