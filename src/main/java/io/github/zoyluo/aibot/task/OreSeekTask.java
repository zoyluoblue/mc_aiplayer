package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.ToolSelector;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * M-ORESEEK:矿石定位驱动的定向开采。
 *
 * 与 StripMineTask 的"鱼骨盲挖"相反——本任务利用服务端拥有完整方块数据这一事实,
 * 直接扫描定位最近的目标矿,朝它挖一条直达通道,挖净矿脉,再找下一处;附近真没矿
 * 才斜楼梯下探一层重新扫描。这解决了"旁边有矿却不挖、一路往下"的核心问题。
 *
 * 自包含状态机(铁律 G1),不在内部 assign 子任务;全程主线程(G2)。
 */
public final class OreSeekTask extends AbstractTask {
    private enum Phase {
        SCAN,
        APPROACH,
        MINE_ORE,
        MINE_VEIN,
        DESCEND,
        RETURN,
        DONE
    }

    private static final int SCAN_INTERVAL = 10;       // 扫描限频(tick)
    private static final int BASE_SCAN_RADIUS = 16;
    private static final int MAX_SCAN_RADIUS = 32;
    private static final int VERTICAL_SCAN = 8;
    private static final int VEIN_CAP = 64;
    private static final double REACH_SQUARED = 20.25D; // 4.5^2
    private static final int APPROACH_TIMEOUT_TICKS = 600; // MINE-DIG fix:挖斜通道接近被埋矿需要时间,15s→30s
    private static final int DESCEND_STEPS = 4;
    private static final double TOOL_DURABILITY_FLOOR = 0.10D;
    private static final int FREE_SLOTS_RETURN = 2;
    // FREEZE fix:无进展看门狗——超过此 tick 数(20s)既没采到东西也没真正破块,就判定卡死并失败,
    // 把"发呆几小时"转成干净失败让 GoalExecutor 接手,绝不再无限空转。
    private static final int NO_PROGRESS_LIMIT = 400;

    private final Set<Block> targetOres;
    private final Set<Item> targetDrops;
    private final int targetCount;
    // MINE-DIG:true=矿石模式(挖净整条矿脉);false=定向挖掘模式(逐块挖目标方块,如挖石头做圆石,
    // 不泛洪整片,避免"挖3块石头变挖64块"。供 GoalExecutor 的 MINE 步定向下挖到埋藏方块用)。
    private final boolean veinMode;

    private Phase phase = Phase.SCAN;
    private BlockPos entryPos;
    private int scanRadius = BASE_SCAN_RADIUS;
    private boolean expandedThisLayer;
    private int lastScanTick = -SCAN_INTERVAL;

    private BlockPos targetOre;
    private int approachStartTick;
    private boolean miningStarted;
    private boolean toolGateChecked;
    private int invBeforeMining;
    // MINE-DIG fix:开工时背包里目标掉落物的固定基线。collected 用"当前背包 - 基线"的绝对增量计,
    // 避免每挖一块就重设基线导致掉落物飞行延迟把 gained 永远算成 0(定向挖石死循环的根因)。
    private int invBaseline;
    private int collected;
    private int veinPickupTicks;
    // FREEZE fix:最近一次"取得进展"(采到东西 或 真正破掉一块)的 elapsed;用于无进展看门狗。
    private int lastProgressTick;
    private boolean minedAnyBlock;

    private final Deque<BlockPos> veinQueue = new ArrayDeque<>();
    private BlockPos currentVeinBlock;
    private final Set<BlockPos> ignored = new HashSet<>();   // 够不到/被岩浆挡的矿,跳过
    private final Deque<BlockPos> descendSteps = new ArrayDeque<>();
    private BlockPos currentDescendBlock;

    public OreSeekTask(Set<Block> targetOres, int targetCount) {
        this(targetOres, targetCount, true);
    }

    /**
     * MINE-DIG:定向挖掘任意目标方块(逐块,不泛洪矿脉)。用于 GoalExecutor 的 MINE 步——
     * 例如"挖 3 块石头做圆石":地表 bot 找不到裸露石头时,本任务会定向往下挖到埋藏的石层并逐块挖取。
     */
    public static OreSeekTask digBlocks(Set<Block> blocks, int count) {
        return new OreSeekTask(blocks, count, false);
    }

    private OreSeekTask(Set<Block> targetOres, int targetCount, boolean veinMode) {
        Set<Block> resolved = targetOres == null || targetOres.isEmpty()
                ? OreScan.COMMON_ORES
                : (veinMode ? OreScan.expandOreFamilies(targetOres) : Set.copyOf(targetOres));
        this.targetOres = resolved;
        this.targetDrops = HarvestCore.expectedDropsFor(resolved);
        this.targetCount = Math.max(1, targetCount);
        this.veinMode = veinMode;
    }

    @Override
    public String name() {
        return "mine_ore";
    }

    @Override
    public String describe() {
        return "OreSeek " + collected + "/" + targetCount + " phase=" + phase
                + (targetOre == null ? "" : " target=" + shortPos(targetOre))
                + " ores=" + targetOres.stream().map(b -> Registries.BLOCK.getId(b).getPath()).sorted().collect(Collectors.joining(","));
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
        return phase == Phase.MINE_ORE || phase == Phase.MINE_VEIN || phase == Phase.DESCEND;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SCAN;
        entryPos = bot.getBlockPos().toImmutable();
        toolGateChecked = false;
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        lastProgressTick = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 12000) {
            fail("oreseek_timeout collected=" + collected);
            return;
        }
        // FREEZE fix:无进展看门狗。RETURN/DONE 是收尾阶段不算卡死;其余阶段超时无进展即失败,
        // 避免 SCAN↔APPROACH 找到"幻影方块"却永远不挖的死循环把 bot 冻住几小时。
        if (phase != Phase.RETURN && phase != Phase.DONE
                && elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            fail("oreseek_no_progress collected=" + collected + " phase=" + phase);
            return;
        }
        switch (phase) {
            case SCAN -> scan(bot);
            case APPROACH -> approach(bot);
            case MINE_ORE -> mineOre(bot);
            case MINE_VEIN -> mineVein(bot);
            case DESCEND -> descend(bot);
            case RETURN -> returnHome(bot);
            case DONE -> complete();
        }
    }

    // ───────────── SCAN ─────────────
    private void scan(AIPlayerEntity bot) {
        if (!ensureToolGate(bot)) {
            return;
        }
        if (collected >= targetCount) {
            beginReturn(bot, "target_reached");
            return;
        }
        if (toolAboutToBreak(bot) || freeSlots(bot) < FREE_SLOTS_RETURN) {
            beginReturn(bot, "inventory_or_tool");
            return;
        }
        int now = bot.getServer().getTicks();
        if (now - lastScanTick < SCAN_INTERVAL) {
            return;
        }
        lastScanTick = now;

        BlockPos found = nearestOre(bot);
        if (found != null) {
            targetOre = found;
            approachStartTick = elapsed;
            miningStarted = false;
            BotLog.action(bot, "oreseek_found", "pos", shortPos(found),
                    "dist", (int) Math.sqrt(bot.getBlockPos().getSquaredDistance(found)),
                    "collected", collected + "/" + targetCount);
            phase = Phase.APPROACH;
            return;
        }
        // 附近无矿:先扩大半径一次,再下探换层
        if (!expandedThisLayer && scanRadius < MAX_SCAN_RADIUS) {
            scanRadius = Math.min(MAX_SCAN_RADIUS, scanRadius * 2);
            expandedThisLayer = true;
            BotLog.action(bot, "oreseek_expand", "radius", scanRadius);
            return;
        }
        ServerWorld world = bot.getServerWorld();
        int preferredY = OreScan.preferredMiningY(targetOres);
        if (bot.getBlockY() > preferredY + 2) {
            beginDescend(bot);
            return;
        }
        if (collected > 0) {
            beginReturn(bot, "no_more_ore");
        } else {
            fail("no_ore_found radius=" + scanRadius + " y=" + bot.getBlockY());
        }
    }

    private BlockPos nearestOre(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        BlockPos min = origin.add(-scanRadius, -VERTICAL_SCAN, -scanRadius);
        BlockPos max = origin.add(scanRadius, VERTICAL_SCAN, scanRadius);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (ignored.contains(pos)) {
                continue;
            }
            if (!OreScan.isOre(world.getBlockState(pos), targetOres)) {
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

    // ───────────── APPROACH ─────────────
    private void approach(AIPlayerEntity bot) {
        if (!ensureToolGate(bot)) {
            return;
        }
        if (targetOre == null || !OreScan.isOre(bot.getServerWorld().getBlockState(targetOre), targetOres)) {
            // 目标已不在(被别的进程改了)→ 重新扫描
            phase = Phase.SCAN;
            return;
        }
        if (canReach(bot, targetOre)) {
            bot.getActionPack().stopAll();
            phase = Phase.MINE_ORE;
            miningStarted = false;
            invBeforeMining = HarvestCore.countInventoryItems(bot, targetDrops);
            return;
        }
        if (elapsed - approachStartTick > APPROACH_TIMEOUT_TICKS) {
            // 走不到/挖不到:放弃此矿,跳过,找下一个
            ignored.add(targetOre);
            targetOre = null;
            phase = Phase.SCAN;
            return;
        }
        // 优先用 A*(可走+可挖穿软石);A* idle 时退回"定向走廊"逐格挖向矿
        if (bot.getActionPack().isPathExecutorIdle() && bot.getActionPack().isMiningIdle()) {
            BlockPos stand = adjacentStand(bot, targetOre);
            ActionResult path = stand == null ? ActionResult.failed("no_stand") : bot.getActionPack().startPathTo(stand);
            if (path == null || path.isFailed()) {
                digCorridorStep(bot);
            }
        }
    }

    // 定向走廊:朝 targetOre 方向逐格挖 2 高隧道推进(A* 到不了矿时的兜底)
    private void digCorridorStep(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        // MINE-DIG fix:防坠落闸——bot 悬空/坠落时 feet 坐标飘忽,据此算方向会把通道挖偏。
        // 等落地站稳再挖下一格,保证定向通道一格一格成形。
        if (!bot.isOnGround()) {
            return;
        }
        BlockPos feet = bot.getBlockPos();
        BlockPos step = stepToward(feet, targetOre);
        if (step == null || step.equals(feet)) {
            ignored.add(targetOre);
            targetOre = null;
            phase = Phase.SCAN;
            return;
        }
        // 安全闸:下一格或其相邻有岩浆 → 放弃此矿
        if (OreScan.adjacentHazard(world, step) || OreScan.adjacentHazard(world, step.up())) {
            ignored.add(targetOre);
            targetOre = null;
            bot.getActionPack().stopAll();
            phase = Phase.SCAN;
            return;
        }
        // 挖 step(脚位)+ step.up()(头位),清出身位,然后走过去
        BlockPos toMine = firstSolid(world, step, step.up());
        if (toMine != null) {
            if (unsafeToMine(bot, world, toMine)) {
                ignored.add(targetOre);
                targetOre = null;
                phase = Phase.SCAN;
                return;
            }
            if (bot.getActionPack().isMiningIdle() && canReach(bot, toMine)) {
                ToolSelector.equipBestTool(bot, world.getBlockState(toMine));
                MiningAction.startMining(bot, toMine, Direction.getFacing(bot.getEyePos().subtract(toMine.toCenterPos())));
                minedAnyBlock = true;
                lastProgressTick = elapsed; // FREEZE fix:挖通道也算进展,正常掘进时看门狗不误杀
            } else if (!canReach(bot, toMine)) {
                bot.getActionPack().startWalkTo(step.toCenterPos());
            }
            return;
        }
        // step 已通,走进去
        bot.getActionPack().startWalkTo(step.toCenterPos());
    }

    // ───────────── MINE_ORE → MINE_VEIN ─────────────
    private void mineOre(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        if (targetOre == null) {
            phase = Phase.SCAN;
            return;
        }
        if (world.getBlockState(targetOre).isAir()) {
            // FREEZE fix:区分"我真的把它挖掉了" vs "幻影"(SCAN 把它当目标块,但进 MINE_ORE 时已是空气,
            // 且我们从没对它开过挖)。前者→正常去取脉/结算;后者→必须加入 ignored 不再重复锁定,
            // 否则 SCAN 会一直重找同一片空气格,导致永不破块的死循环(实测发呆几小时的真凶)。
            if (miningStarted) {
                collectVeinFrom(bot, targetOre);
                veinPickupTicks = 100;
                phase = Phase.MINE_VEIN;
            } else {
                ignored.add(targetOre);
                targetOre = null;
                phase = Phase.SCAN;
            }
            return;
        }
        if (OreScan.adjacentHazard(world, targetOre)) {
            ignored.add(targetOre);
            targetOre = null;
            phase = Phase.SCAN;
            return;
        }
        if (unsafeToMine(bot, world, targetOre)) {
            ignored.add(targetOre);
            targetOre = null;
            phase = Phase.SCAN;
            return;
        }
        if (!canReach(bot, targetOre)) {
            phase = Phase.APPROACH;
            return;
        }
        if (bot.getActionPack().isMiningIdle()) {
            BlockState state = world.getBlockState(targetOre);
            ToolSelector.equipBestTool(bot, state);
            ActionResult result = MiningAction.startMining(bot, targetOre,
                    Direction.getFacing(bot.getEyePos().subtract(targetOre.toCenterPos())));
            if (result.isFailed()) {
                ignored.add(targetOre);
                targetOre = null;
                phase = Phase.SCAN;
            } else {
                // FREEZE fix:真正对目标块开挖了 → 标记,使上面的 air 分支识别为"已挖掉"而非幻影,
                // 并刷新无进展看门狗。
                miningStarted = true;
                minedAnyBlock = true;
                lastProgressTick = elapsed;
            }
        }
    }

    private void collectVeinFrom(AIPlayerEntity bot, BlockPos seed) {
        veinQueue.clear();
        // MINE-DIG:定向挖掘模式不泛洪——只挖被定位的单块,然后 SCAN 重新找下一块最近目标。
        if (!veinMode) {
            return;
        }
        if (seed != null) {
            // veinFrom 需要种子仍是矿;此时已挖空,改为扫种子周围找同脉相邻矿
            for (Direction dir : Direction.values()) {
                BlockPos n = seed.offset(dir);
                if (OreScan.isOre(bot.getServerWorld().getBlockState(n), targetOres)) {
                    veinQueue.addAll(OreScan.veinFrom(bot.getServerWorld(), n, targetOres, VEIN_CAP));
                    break;
                }
            }
        }
    }

    private void mineVein(AIPlayerEntity bot) {
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops);
        HarvestCore.chaseDropAnyOf(bot, targetDrops, 6.0D); // 持续吸附目标掉落物
        ServerWorld world = bot.getServerWorld();
        if (currentVeinBlock == null) {
            currentVeinBlock = veinQueue.pollFirst();
            if (currentVeinBlock == null) {
                // 矿脉挖完:结算拾取增量
                HarvestCore.sweepPickupAnyOf(bot, targetDrops, 12);
                // MINE-DIG fix:绝对增量结算(当前背包 - 开工基线)。刚挖的掉落物可能还在飞,
                // total 暂时不增→留在原地等它落袋再结算,不会像旧的每轮基线那样把 gained 永远算成 0。
                int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
                if (total <= collected && veinPickupTicks-- > 0 && HarvestCore.nearestDropAnyOf(bot, targetDrops, 6.0D).isPresent()) {
                    return;
                }
                int gained = Math.max(0, total - collected);
                collected = Math.max(collected, total);
                if (gained > 0) {
                    lastProgressTick = elapsed; // FREEZE fix:真采到东西=进展,刷新看门狗
                }
                targetOre = null;
                BotLog.action(bot, "oreseek_collected", "gained", gained, "total", collected + "/" + targetCount);
                phase = Phase.SCAN;
                return;
            }
            miningStarted = false;
        }
        if (world.getBlockState(currentVeinBlock).isAir()) {
            currentVeinBlock = null;
            return;
        }
        if (unsafeToMine(bot, world, currentVeinBlock)) {
            currentVeinBlock = null; // 跳过危险矿块
            return;
        }
        if (!canReach(bot, currentVeinBlock)) {
            BlockPos stand = adjacentStand(bot, currentVeinBlock);
            if (stand != null && bot.getActionPack().isPathExecutorIdle()) {
                bot.getActionPack().startWalkTo(stand.toCenterPos());
            }
            return;
        }
        if (bot.getActionPack().isMiningIdle()) {
            ToolSelector.equipBestTool(bot, world.getBlockState(currentVeinBlock));
            MiningAction.startMining(bot, currentVeinBlock,
                    Direction.getFacing(bot.getEyePos().subtract(currentVeinBlock.toCenterPos())));
            minedAnyBlock = true;
            lastProgressTick = elapsed; // FREEZE fix:挖脉也算进展
        }
    }

    // ───────────── DESCEND(斜楼梯) ─────────────
    private void beginDescend(AIPlayerEntity bot) {
        descendSteps.clear();
        currentDescendBlock = null;
        Direction dir = bot.getHorizontalFacing();
        BlockPos cursor = bot.getBlockPos();
        for (int i = 0; i < DESCEND_STEPS; i++) {
            cursor = cursor.offset(dir).down();
            descendSteps.addLast(cursor.toImmutable());
            descendSteps.addLast(cursor.up().toImmutable()); // 头位
        }
        expandedThisLayer = false;
        scanRadius = BASE_SCAN_RADIUS;
        BotLog.action(bot, "oreseek_descend", "dir", dir, "from_y", bot.getBlockY());
        phase = Phase.DESCEND;
    }

    private void descend(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        if (currentDescendBlock == null) {
            currentDescendBlock = descendSteps.pollFirst();
            if (currentDescendBlock == null) {
                phase = Phase.SCAN; // 这段斜梯挖完,回去扫矿
                return;
            }
            miningStarted = false;
        }
        if (world.getBlockState(currentDescendBlock).isAir()) {
            boolean completedPair = descendSteps.size() % 2 == 0;
            currentDescendBlock = null;
            if (completedPair) {
                phase = Phase.SCAN;
            }
            return;
        }
        if (unsafeToMine(bot, world, currentDescendBlock)) {
            fail("descend_hazard: " + shortPos(currentDescendBlock));
            return;
        }
        if (!canReach(bot, currentDescendBlock)) {
            // 走向斜梯当前格的上一级站位
            BlockPos stand = currentDescendBlock.up(); // 头位上方通常可站到的前一格
            if (bot.getActionPack().isPathExecutorIdle()) {
                bot.getActionPack().startWalkTo(currentDescendBlock.toCenterPos());
            }
            return;
        }
        if (bot.getActionPack().isMiningIdle()) {
            ToolSelector.equipBestTool(bot, world.getBlockState(currentDescendBlock));
            MiningAction.startMining(bot, currentDescendBlock,
                    Direction.getFacing(bot.getEyePos().subtract(currentDescendBlock.toCenterPos())));
            minedAnyBlock = true;
            lastProgressTick = elapsed; // FREEZE fix:下探掘进也算进展
        }
    }

    // ───────────── RETURN ─────────────
    private void beginReturn(AIPlayerEntity bot, String why) {
        BotLog.action(bot, "oreseek_return", "why", why, "collected", collected + "/" + targetCount);
        if (entryPos != null) {
            bot.getActionPack().startPathTo(entryPos);
        }
        phase = Phase.RETURN;
    }

    private void returnHome(AIPlayerEntity bot) {
        if (entryPos == null || bot.getBlockPos().getSquaredDistance(entryPos) <= 9.0D) {
            phase = Phase.DONE;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult result = bot.getActionPack().startPathTo(entryPos);
            if (result == null || result.isFailed()) {
                phase = Phase.DONE; // 回不去也算完成(已采到矿),交大脑决定
            }
        }
    }

    // ───────────── helpers ─────────────
    private static boolean canReach(AIPlayerEntity bot, BlockPos target) {
        return bot.getEyePos().squaredDistanceTo(target.toCenterPos()) <= REACH_SQUARED;
    }

    private boolean ensureToolGate(AIPlayerEntity bot) {
        int requiredTier = ToolTier.requiredPickaxeTier(targetOres);
        int bestTier = ToolTier.bestPickaxeTier(bot);
        if (bestTier < requiredTier) {
            String needed = ToolTier.requiredPickaxeItemId(targetOres);
            BotLog.action(bot, "oreseek_tool_gate",
                    "result", "fail",
                    "required", needed,
                    "best_tier", bestTier);
            fail("need_better_tool:" + needed);
            return false;
        }
        if (!toolGateChecked) {
            BotLog.action(bot, "oreseek_tool_gate",
                    "result", "pass",
                    "required_tier", requiredTier,
                    "best_tier", bestTier);
            toolGateChecked = true;
        }
        return true;
    }

    private static boolean unsafeToMine(AIPlayerEntity bot, ServerWorld world, BlockPos pos) {
        if (OreScan.adjacentHazard(world, pos)) {
            return true;
        }
        BlockPos feet = bot.getBlockPos();
        if (!pos.equals(feet.down())) {
            return false;
        }
        BlockPos landing = pos.down();
        return world.getBlockState(landing).isAir() || OreScan.adjacentHazard(world, landing);
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        Standability.clearCache();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(dir);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate.toImmutable();
            }
        }
        if (Standability.isStandable(bot.getServerWorld(), target.up())) {
            return target.up().toImmutable();
        }
        return null;
    }

    // 朝 target 的下一格(优先竖直分量,再较大的水平分量;避免对角穿墙角)
    // MINE-DIG fix:稳定阶梯式逼近。目标在下方时走"前方下台阶"(水平+down),而不是垂直挖脚下——
    // 后者会让 bot 自由坠落、feet 漂移、通道挖偏(实测一路掉到 Y12 还够不到 dist=8 的铁矿)。
    private static BlockPos stepToward(BlockPos from, BlockPos target) {
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        int dy = target.getY() - from.getY();
        Direction horiz = null;
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            horiz = dx > 0 ? Direction.EAST : Direction.WEST;
        } else if (dz != 0) {
            horiz = dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        if (horiz != null) {
            BlockPos ahead = from.offset(horiz);
            // 目标更低 → 下台阶(前方再低一格),bot 走下去而非坠落;否则平推。
            return dy < 0 ? ahead.down() : ahead;
        }
        // 水平已对齐,只剩纯垂直分量。
        if (dy < 0) {
            return from.down();
        }
        if (dy > 0) {
            return from.up();
        }
        return from;
    }

    private static BlockPos firstSolid(ServerWorld world, BlockPos... candidates) {
        for (BlockPos pos : candidates) {
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && state.getCollisionShape(world, pos).getMax(Direction.Axis.Y) > 0.0D) {
                return pos.toImmutable();
            }
        }
        return null;
    }

    private static boolean toolAboutToBreak(AIPlayerEntity bot) {
        var main = bot.getMainHandStack();
        if (main.isEmpty() || !main.isDamageable()) {
            return false;
        }
        int remaining = main.getMaxDamage() - main.getDamage();
        return remaining <= Math.max(1, (int) (main.getMaxDamage() * TOOL_DURABILITY_FLOOR));
    }

    private static int freeSlots(AIPlayerEntity bot) {
        int free = 0;
        for (var stack : bot.getInventory().main) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static String shortPos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
