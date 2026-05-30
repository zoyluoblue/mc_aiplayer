package io.github.zoyluo.aibot.task;

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
        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(bot, targetBlock, 8, 4, 6);
        if (choice == null) {
            if (OreScan.isOreBlock(targetBlock)) {
                fail("no_exposed_ore:use_strip_mine:" + Registries.BLOCK.getId(targetBlock));
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
            pickupTicks = 120;
            phase = Phase.PICKING_UP;
            return;
        }
        if (bot.getActionPack().isMiningIdle() && elapsed % 200 == 0) {
            startMiningTarget(bot);
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
        HarvestCore.startMining(bot, targetPos);
        phase = Phase.MINING;
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
