package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.MiningAction;
import io.github.zoyluo.aibot.action.ToolSelector;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * DIGDOWN:就近垂直下挖,采集 N 个目标方块(如圆石)。专为 GoalExecutor 的 MINE 步设计。
 *
 * 与 OreSeek 的根本区别:它**不定位、不寻路、不走路**——bot 站着不动,只挖**伸手可及**的方块
 * (优先脚下直挖,坠落格有空洞/岩浆时改挖水平相邻),因此永远不会"够不到/走不过去"而空转卡死。
 * 这正是实测#8(OreSeek 锁定垂直够不到的石头反复空转 stuck)的对症方案。
 *
 * 自包含状态机(铁律 G1),不在内部 assign;全程主线程(G2)。
 */
public final class DigDownTask extends AbstractTask {
    private static final int MAX_ELAPSED = 2400;        // 2 分钟硬超时
    private static final int NO_PROGRESS_LIMIT = 300;   // 15s 无进展即失败(看门狗)
    private static final int PICKUP_GRACE_TICKS = 40;   // 采够后多等一会儿确保掉落物落袋

    private final net.minecraft.block.Block targetBlock;
    private final Set<Item> targetDrops;
    private final int targetCount;

    private int invBaseline;
    private int collected;
    private int lastProgressTick;
    private int pickupGrace;
    private BlockPos currentDig;

    public DigDownTask(net.minecraft.block.Block targetBlock, int targetCount) {
        this.targetBlock = targetBlock;
        this.targetDrops = HarvestCore.expectedDropsFor(Set.of(targetBlock));
        this.targetCount = Math.max(1, targetCount);
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
        // 挖掘期视为 waiting,避免 StuckWatcher 因 bot 站着不动(正常下挖就是站着挖)误判 stuck。
        // 本任务自带 NO_PROGRESS_LIMIT 看门狗负责卡死保护。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        invBaseline = HarvestCore.countInventoryItems(bot, targetDrops);
        collected = 0;
        lastProgressTick = 0;
        pickupGrace = 0;
        currentDig = null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("dig_down_timeout collected=" + collected);
            return;
        }
        // 工具闸:挖不动目标(无合格镐)直接失败,交 GoalExecutor 倒推补镐。
        ServerWorld world = bot.getServerWorld();
        if (!ToolTier.canHarvestWithInventory(bot, targetBlock.getDefaultState())) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return;
        }

        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops, 2.0D, 2.0D);
        int total = Math.max(0, HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline);
        if (total > collected) {
            collected = total;
            lastProgressTick = elapsed;
            BotLog.action(bot, "dig_down_collected", "total", collected + "/" + targetCount);
        }
        if (collected >= targetCount) {
            // 采够:多等 PICKUP_GRACE_TICKS 让最后的掉落物落袋,再扫一次。
            HarvestCore.sweepPickupAnyOf(bot, targetDrops, 16);
            if (pickupGrace++ >= PICKUP_GRACE_TICKS
                    || HarvestCore.countInventoryItems(bot, targetDrops) - invBaseline >= targetCount) {
                complete();
            }
            return;
        }

        // 无进展看门狗:15s 既没采到也没破块 → 失败(干净退出,不空转)。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            fail("dig_down_no_progress collected=" + collected);
            return;
        }

        // 正在挖的块还没破 → 继续(每 20 tick 重发一次开挖指令,防被其它系统打断后僵住)。
        if (currentDig != null) {
            if (!world.getBlockState(currentDig).isOf(targetBlock)) {
                // 已破块 = 取得进展,刷新看门狗;掉落物由上面的 forcePickup 收。
                currentDig = null;
                lastProgressTick = elapsed;
            } else {
                if (elapsed % 20 == 0) {
                    mineAt(bot, world, currentDig);
                }
                return;
            }
        }

        // 选下一个"伸手可及"的目标块:优先脚下直挖(坠落安全才挖),否则挖水平相邻。
        BlockPos next = pickNextDig(bot, world);
        if (next == null) {
            fail("dig_down_no_reachable_block");
            return;
        }
        currentDig = next;
        mineAt(bot, world, next);
        lastProgressTick = elapsed; // 开挖也算进展,避免看门狗在长挖掘中误杀
    }

    private void mineAt(AIPlayerEntity bot, ServerWorld world, BlockPos pos) {
        ToolSelector.equipBestTool(bot, world.getBlockState(pos));
        MiningAction.startMining(bot, pos, Direction.getFacing(bot.getEyePos().subtract(pos.toCenterPos())));
    }

    /** 选一个站着够得到、且挖了安全的目标方块。优先脚下,其次水平四邻(脚位/头位)。 */
    private BlockPos pickNextDig(AIPlayerEntity bot, ServerWorld world) {
        BlockPos feet = bot.getBlockPos();
        // 1) 脚下直挖:仅当落点安全(下两格不是空洞/岩浆,避免摔进洞或岩浆)。
        BlockPos below = feet.down();
        if (isTarget(world, below) && safeToDropInto(world, below.down())) {
            return below;
        }
        // 2) 水平四邻(脚位与头位):站着伸手可及,不需要移动。
        for (BlockPos p : new BlockPos[]{
                feet.north(), feet.south(), feet.east(), feet.west(),
                feet.up().north(), feet.up().south(), feet.up().east(), feet.up().west()}) {
            if (isTarget(world, p)) {
                return p.toImmutable();
            }
        }
        // 3) 脚下直挖兜底:即使下方未知,也允许挖(1 格坠落安全),除非正下方再下是岩浆。
        if (isTarget(world, below) && !isLava(world, below.down())) {
            return below;
        }
        return null;
    }

    private boolean isTarget(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isOf(targetBlock);
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    // 落点安全:该格是实心(站得住)或至少不是岩浆;空气(深洞)视为不安全。
    private static boolean safeToDropInto(ServerWorld world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        if (s.getFluidState().isIn(FluidTags.LAVA)) {
            return false;
        }
        return !s.isAir();
    }
}
