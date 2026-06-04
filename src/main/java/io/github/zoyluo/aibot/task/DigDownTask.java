package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.Block;
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
    private int hdirIndex;    // 撞基岩后水平掘进的当前方向
    private int stairDirIndex; // 台阶斜下的当前水平方向(HDIRS 下标)

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
        if (feet.down().getY() <= MIN_Y) {
            // 到基岩上方、向下已无空间 → 转水平掘进继续挖石料(实测 Y=-59 时第一步 below 就 <= MIN_Y,
            // 旧逻辑直接 fail collected=0 → MINE stone 失败 → goal replan 死循环)。
            digHorizontal(bot, world, feet);
            return;
        }

        // 台阶式斜向下挖(拟人 + 安全):绝不直挖脚下——下方可能是水/岩浆,一镐捅穿就溺水/葬身岩浆。
        // 改挖"下一级台阶"(斜前下方:ahead 头位 + next 脚位),深层这两格通常都是石料,顺带计入 collected;
        // 下一级或其踏面是水/岩浆就换斜下方向绕,像挖楼梯一样一级一级斜下(与 DescendToYTask 台阶逻辑一致)。
        Direction dir = HDIRS[stairDirIndex];
        BlockPos ahead = feet.offset(dir);   // 下一级头位 (x+d, y)
        BlockPos next = ahead.down();         // 下一级站位 (x+d, y-1)
        if (isLava(world, next) || isLava(world, next.down()) || isLava(world, ahead)
                || isWater(world, next) || isWater(world, next.down())) {
            if (rotateStair(world, feet)) {
                return; // 换了个不挨水/岩浆的斜下方向
            }
            // 四个斜下方向都被水/岩浆挡 → 转水平掘进(此层还能继续凑石料),实在不行那里再判失败。
            digHorizontal(bot, world, feet);
            return;
        }
        // 清出下一级身位:next(脚位) + ahead(头位),挖第一个固体(石料,随后落袋计入 collected)。
        BlockPos solid = firstSolid(world, next, ahead);
        if (solid != null) {
            miner.begin(bot, solid);
            miner.tick(bot); // 立即发起本格挖掘,不浪费一 tick
            return;
        }
        // 身位已通 → 斜下踏到下一级台阶。bot 无被动重力,仍需主动移一格;斜向 1 格微位移=踏下一级楼梯,
        // 不是 roam 那种跨图大范围闪现(下沉后刚破块的掉落物正好落入拾取半径,一并修掉 collected=0)。
        bot.getActionPack().descendInto(next);
    }

    private static boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.WATER);
    }

    // 台阶斜下:换到下一个"不挨水/岩浆"的斜下方向;四面都不行返回 false(交 digHorizontal 兜底)。
    private boolean rotateStair(ServerWorld world, BlockPos feet) {
        for (int i = 0; i < HDIRS.length; i++) {
            stairDirIndex = (stairDirIndex + 1) % HDIRS.length;
            BlockPos ahead = feet.offset(HDIRS[stairDirIndex]);
            BlockPos next = ahead.down();
            if (!isLava(world, next) && !isLava(world, next.down()) && !isLava(world, ahead)
                    && !isWater(world, next) && !isWater(world, next.down())) {
                return true;
            }
        }
        return false;
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
