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
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

public final class MineTask extends AbstractTask {
    private enum Phase {
        SEARCHING,
        MOVING,
        MINING,
        PICKING_UP
    }

    private final Block targetBlock;
    private final int countNeeded;
    private final Set<Item> targetDrops;
    private final BlockMiner miner = new BlockMiner();
    private Phase phase = Phase.SEARCHING;
    private BlockPos targetPos;
    private int countSoFar;
    private int inventoryCountBeforeMining;
    private int pickupTicks;
    private boolean pickupSweepAttempted;
    private boolean directMiningTarget;

    public MineTask(Block targetBlock, int countNeeded) {
        this.targetBlock = targetBlock;
        this.countNeeded = Math.max(1, countNeeded);
        this.targetDrops = HarvestCore.expectedDropsFor(targetBlock);
    }

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String describe() {
        return "Mining " + Registries.BLOCK.getId(targetBlock) + " " + countSoFar + "/" + countNeeded + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / countNeeded);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SEARCHING;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("mine_timeout");
            return;
        }
        switch (phase) {
            case SEARCHING -> search(bot);
            case MOVING -> move(bot);
            case MINING -> mine(bot);
            case PICKING_UP -> pickup(bot);
        }
    }

    private void search(AIPlayerEntity bot) {
        // 半径 8 固定不扩是实测"附近没有就秒失败"的根源:递进 8→16→24 再认输(gather 同款翻倍思路)。
        // 扫描只在 SEARCHING 相位跑到结果为止,大半径是一次性成本,不是每 tick。
        HarvestCore.TargetChoice choice = null;
        for (int radius : new int[]{8, 16, 24}) {
            choice = HarvestCore.nearestReachableBlock(bot, targetBlock, radius, 4, 6);
            if (choice != null) {
                break;
            }
        }
        if (choice == null) {
            if (OreScan.isOreBlock(targetBlock)) {
                fail("no_exposed_ore:use_mine_ore:" + Registries.BLOCK.getId(targetBlock));
                return;
            }
            fail("no_reachable_target_block_in_range");
            return;
        }
        targetPos = choice.pos();
        directMiningTarget = choice.direct();
        if (directMiningTarget) {
            startMiningTarget(bot);
            return;
        }
        phase = Phase.MOVING;
        bot.getActionPack().startPathTo(choice.stand());
    }

    private void move(AIPlayerEntity bot) {
        if (targetPos == null || !bot.getServerWorld().getBlockState(targetPos).isOf(targetBlock)) {
            phase = Phase.SEARCHING;
            return;
        }
        if (HarvestCore.canReach(bot, targetPos)) {
            bot.getActionPack().stopAll();
            startMiningTarget(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            phase = Phase.SEARCHING;
        }
    }

    private void mine(AIPlayerEntity bot) {
        if (targetPos == null || !bot.getServerWorld().getBlockState(targetPos).isOf(targetBlock)) {
            miner.cancel(bot);
            pickupTicks = 120;
            phase = Phase.PICKING_UP;
            return;
        }
        // P1-a:挖掘走 BlockMiner(只在空闲发起、绝不重发清零进度);破块/超时进入拾取阶段。
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.DONE || status == BlockMiner.Status.FAILED) {
            pickupTicks = 120;
            phase = Phase.PICKING_UP;
        }
    }

    private void pickup(AIPlayerEntity bot) {
        HarvestCore.forcePickupNearbyAnyOf(bot, targetDrops);
        int collected = HarvestCore.countInventoryItems(bot, targetDrops) - inventoryCountBeforeMining;
        if (collected > 0) {
            BotLog.action(bot, "pickup_collected", "count", collected);
            countSoFar += collected;
            if (countSoFar >= countNeeded) {
                complete();
            } else {
                phase = Phase.SEARCHING;
            }
            return;
        }
        pickupTicks--;
        HarvestCore.chaseDropAnyOf(bot, targetDrops, 8.0D);
        if (pickupTicks <= 0) {
            if (!pickupSweepAttempted && HarvestCore.nearestDropAnyOf(bot, targetDrops, 8.0D).isPresent()) {
                pickupSweepAttempted = true;
                HarvestCore.sweepPickupAnyOf(bot, targetDrops, 8);
                pickupTicks = 60;
                return;
            }
            int partial = HarvestCore.countInventoryItems(bot, targetDrops) - inventoryCountBeforeMining;
            if (partial > 0) {
                BotLog.action(bot, "pickup_collected", "count", partial, "reason", "partial_pickup");
                countSoFar += partial;
                complete();
                return;
            }
            fail("pickup_timeout");
        }
    }

    private void startMiningTarget(AIPlayerEntity bot) {
        BlockState state = bot.getServerWorld().getBlockState(targetPos);
        if (!ToolTier.canHarvestWithInventory(bot, state)) {
            fail("need_better_tool:" + ToolTier.requiredPickaxeItemId(targetBlock));
            return;
        }
        // GOALFIX-GF2:挖前危险闸——目标相邻有岩浆时不破块(破块会引出岩浆烧死自己),安全失败让上层另想办法。
        if (lavaAdjacent(bot, targetPos)) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.TASK, bot, "mine_hazard_skip",
                    "pos", targetPos.getX() + "," + targetPos.getY() + "," + targetPos.getZ());
            fail("mine_hazard_lava");
            return;
        }
        inventoryCountBeforeMining = HarvestCore.countInventoryItems(bot, targetDrops);
        pickupSweepAttempted = false;
        miner.begin(bot, targetPos); // P1-a:BlockMiner 接管挖掘,mine() 阶段每 tick 推进
        phase = Phase.MINING;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    private static boolean lavaAdjacent(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (bot.getServerWorld().getFluidState(pos.offset(direction)).isIn(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }
}
