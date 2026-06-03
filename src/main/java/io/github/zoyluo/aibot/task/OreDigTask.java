package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

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
    private static final int VERTICAL_SCAN = 10;
    private static final double REACH_SQUARED = 20.25D; // 4.5^2,与 OreSeek 一致
    private static final int MIN_Y = -60;
    private static final int VEIN_CAP = 64;
    private static final int PICKUP_GRACE_TICKS = 30;
    private static final int APPROACH_LIMIT = 80;       // P0:锁定矿超过此 tick 仍没靠近 → 判够不到,放弃换矿/下挖

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
    private int pickupGrace;
    private BlockPos targetOre;
    private double lastTargetDist = Double.MAX_VALUE; // P0:锁定矿的历史最近距离²(监控是否在接近)
    private int targetApproachTick;

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

        // 无进展看门狗:NO_PROGRESS_LIMIT 内没破任何块 → 干净失败。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
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
        // 附近没矿:向下挖一格换层再扫(挖脚下,穿过任何固体)。
        digDownOneLayer(bot, world);
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

    // 向下挖一格换层(脚下任何固体都挖穿;岩浆/基岩则失败)。
    private void digDownOneLayer(AIPlayerEntity bot, ServerWorld world) {
        BlockPos below = bot.getBlockPos().down();
        if (below.getY() <= MIN_Y) {
            fail("ore_dig_reached_min_y collected=" + collected);
            return;
        }
        BlockState s = world.getBlockState(below);
        if (s.isAir()) {
            // bot 无被动重力(ServerPlayerEntity 服务端不跑 travel(),fake player 没客户端驱动)——
            // 挖空脚下不会自动下落(与 DigDownTask 同根因)。主动下沉到刚挖空的格子,
            // 否则竖直换层会站着空转直到看门狗失败(ore_dig_buried 矿在正下方时必卡)。
            bot.getActionPack().descendInto(below);
            return;
        }
        // 岩浆致命 → 硬停;水不致命 → 下沉穿过继续(与 DigDownTask 一致,地下水脉常见,旧逻辑遇水即 fail)。
        if (s.getFluidState().isIn(FluidTags.LAVA)
                || world.getBlockState(below.down()).getFluidState().isIn(FluidTags.LAVA)) {
            fail("ore_dig_blocked_lava collected=" + collected);
            return;
        }
        if (s.getFluidState().isIn(FluidTags.WATER)) {
            bot.getActionPack().descendInto(below);
            return;
        }
        BlockMiner.Status st = miner.target() != null && miner.target().equals(below)
                ? miner.tick(bot)
                : beginMine(bot, below);
        if (st == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed;
        }
    }

    private BlockMiner.Status beginMine(AIPlayerEntity bot, BlockPos pos) {
        miner.begin(bot, pos);
        return miner.tick(bot);
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
