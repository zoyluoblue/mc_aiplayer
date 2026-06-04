package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashSet;
import java.util.Set;

/**
 * DIGDOWN:站着挖竖井,采集 N 个目标方块(如圆石)。专为 GoalExecutor 的 MINE 步设计。
 *
 * 它**不定位特定方块、不寻路、不走路**——只挖脚下的方块柱往下掘进:
 *  - 脚下若已是目标块(石头)→ 挖它,计入产出;
 *  - 脚下是泥/草/沙等非目标块 → 也挖掉穿过去(地表 bot 必须先穿过表层土才到石层),
 *    这正是实测#9 "站在草地上、相邻没石头就秒 no_reachable" 的对症修复;
 *  - 挖一格、bot 自然下落一格、再挖下一格,如此往下,直到采够。
 *
 * 安全:开挖每格前查正下方两格是否岩浆/深渊;碰到流体或挖到基岩层失败,交 GoalExecutor 处理。
 * 挖掘走共享原语 {@link BlockMiner}(只在空闲时发起、绝不中途重发清零进度、正确 face)。
 *
 * 自包含状态机(铁律 G1),不在内部 assign;全程主线程(G2)。
 */
public final class DigDownTask extends AbstractTask {
    private static final int MAX_ELAPSED = 2400;        // 2 分钟硬超时
    private static final int NO_PROGRESS_LIMIT = 200;   // 10s 无进展(没破任何块)即失败
    private static final int PICKUP_GRACE_TICKS = 30;   // 采够后多等一会儿确保掉落物落袋
    private static final int MIN_Y = -60;               // 别挖穿到基岩以下

    private final Block targetBlock;
    private final Set<Item> targetDrops;
    private final int targetCount;
    private final BlockMiner miner = new BlockMiner();
    private static final Direction[] HDIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private int hdirIndex; // 撞基岩后水平掘进的当前方向

    private int invBaseline;
    private int collected;
    private int lastProgressTick;
    private int pickupGrace;

    public DigDownTask(Block targetBlock, int targetCount) {
        this.targetBlock = targetBlock;
        this.targetCount = Math.max(1, targetCount);
        Set<Item> drops = new HashSet<>(HarvestCore.expectedDropsFor(Set.of(targetBlock)));
        if (targetBlock == Blocks.STONE) {
            // 深层(Y<0)全是深板岩(挖了掉 cobbled_deepslate)、远古遗迹是黑石——都算"石料",
            // 否则深层永远凑不够 cobblestone、做不了熔炉(实测 Y=-59 死循环根因)。
            drops.add(Items.COBBLED_DEEPSLATE);
            drops.add(Items.BLACKSTONE);
        }
        this.targetDrops = drops;
    }

    @Override
    public String name() {
        return "dig_down";
    }

    @Override
    public String describe() {
        return "DigDown " + net.minecraft.registry.Registries.BLOCK.getId(targetBlock).getPath()
                + " " + collected + "/" + targetCount;
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
        // 下挖期 bot 站着挖,位置基本不变;视为 waiting 让 StuckWatcher 不误判,
        // 由本任务自己的 NO_PROGRESS_LIMIT 看门狗负责卡死保护。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("dig_down_timeout collected=" + collected);
            return;
        }
        ServerWorld world = bot.getServerWorld();

        // 工具闸:挖不动目标(无合格镐)直接失败,交 GoalExecutor 倒推补镐。
        if (!ToolTier.canHarvestWithInventory(bot, targetBlock.getDefaultState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return;
        }

        // 收集计数:绝对增量(固定基线),刚破的块的掉落物随后落袋会被算进来。
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 2.5D, 2.5D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            BotLog.action(bot, "dig_down_collected", "total", collected + "/" + targetCount);
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

        // 无进展看门狗:NO_PROGRESS_LIMIT 内没破任何块 → 干净失败,不空转。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            miner.cancel(bot);
            fail("dig_down_no_progress collected=" + collected);
            return;
        }

        // 推进当前挖掘。
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return; // 正在挖,等它破/超时
        }
        if (status == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed; // 破了一格 = 进展(无论是石头还是表层土)
        }
        // DONE / FAILED / IDLE → 决定下一格(脚下柱)。

        BlockPos feet = bot.getBlockPos();
        BlockPos below = feet.down();
        if (below.getY() <= MIN_Y) {
            // 到基岩上方、向下已无空间 → 转水平掘进继续挖石料(实测 Y=-59 时第一步 below 就 <= MIN_Y,
            // 旧逻辑直接 fail collected=0 → MINE stone 失败 → goal replan 死循环)。
            digHorizontal(bot, world, feet);
            return;
        }
        BlockState belowState = world.getBlockState(below);

        // 脚下已空(刚挖空这一格)→ 主动下沉一格。
        // 关键(实测卡死根因):bot 是 ServerPlayerEntity,服务端**不跑 travel()**(真实玩家的移动/重力由
        // 客户端驱动,fake player 没有客户端),所以挖空脚下**不会被动下落**——dig_down 全程 y 恒定、
        // below 永远是 air、看门狗 200t 后判 no_progress 卡死(diag 7 连拍 pos 一字不变即铁证)。
        // 这里主动把 bot 沉进刚挖空的格子继续掘进;下沉后刚破块的掉落物正好落入拾取半径,一并修掉 collected=0。
        if (belowState.isAir()) {
            bot.getActionPack().descendInto(below);
            return;
        }
        // 岩浆(脚下或其正下方)致命、不可穿 → 硬停交还。
        if (belowState.getFluidState().isIn(FluidTags.LAVA)
                || world.getBlockState(below.down()).getFluidState().isIn(FluidTags.LAVA)) {
            fail("dig_down_blocked_lava collected=" + collected);
            return;
        }
        // 水不致命:当作可穿过,下沉穿过水柱继续找下方固体。地下水脉极常见,旧逻辑"遇水即 fail"
        // 是挖矿失败的主要来源(实测 15 次 dig/ore_dig_blocked_fluid);溺水有 NavSafetyNet 兜底上浮。
        if (belowState.getFluidState().isIn(FluidTags.WATER)) {
            bot.getActionPack().descendInto(below);
            return;
        }
        // 脚下是实心方块(石头/泥土/任何固体)→ 挖它,穿过去往下。
        miner.begin(bot, below);
        miner.tick(bot); // 立即发起本格挖掘,不浪费一 tick
    }

    // 撞基岩(向下到底)后转水平掘进:沿一个方向逐格挖石料,挖通就走进去换列继续。深层全深板岩,
    // 配合构造里把 cobbled_deepslate 计入 targetDrops,即可在 Y<0 凑够做熔炉的石料,不再死循环。
    private void digHorizontal(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        for (int tries = 0; tries < HDIRS.length; tries++) {
            Direction dir = HDIRS[hdirIndex];
            BlockPos side = feet.offset(dir);
            if (isLava(world, side) || isLava(world, side.up()) || isLava(world, side.down())) {
                hdirIndex = (hdirIndex + 1) % HDIRS.length; // 这个方向挨岩浆,换一个
                continue;
            }
            BlockPos solid = firstSolid(world, side, side.up());
            if (solid != null) {
                if (miner.target() == null || !miner.target().equals(solid)) {
                    miner.begin(bot, solid); // 由 onTick 顶部的 miner.tick 推进
                }
                return;
            }
            // side 脚位+头位皆空 → 走进去换列,继续水平挖
            miner.cancel(bot);
            bot.getActionPack().startWalkTo(side.toCenterPos());
            return;
        }
        fail("dig_down_walled collected=" + collected); // 四面皆岩浆,交还安全网
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
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
}
