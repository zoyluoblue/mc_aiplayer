package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
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

    private enum Phase {
        SURVEY,
        GOTO,
        HARVEST,
        PICKUP,
        DEPOSIT,
        DONE
    }

    private final Item targetItem;
    private final int targetCount;
    // Fix C:目标是原木时,接受/采集**任意**树种(生物群系不一定有橡木)。进度按整族原木总数计。
    private final Set<Item> acceptItems;
    private final Set<Block> harvestBlocks;
    private Phase phase = Phase.SURVEY;
    private BlockPos targetPos;
    private int countSoFar;
    private int countBeforeHarvest;
    private int pickupTicks;
    private boolean pickupSweepAttempted;
    private StockpileTask stockpileTask;

    public GatherQuotaTask(Item targetItem, int targetCount) {
        this.targetItem = targetItem;
        this.targetCount = Math.max(1, targetCount);
        this.acceptItems = acceptItemsFor(targetItem);
        this.harvestBlocks = harvestBlocksFor(this.acceptItems);
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
    protected void onStart(AIPlayerEntity bot) {
        countSoFar = countAccepted(bot);
        phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
        stockpileTask = null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        countSoFar = countAccepted(bot);
        if (countSoFar >= targetCount) {
            phase = Phase.DONE;
        }
        if (elapsed > 6000) {
            fail("gather_timeout");
            return;
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case HARVEST -> harvest(bot);
            case PICKUP -> pickup(bot);
            case DEPOSIT -> deposit(bot);
            case DONE -> complete();
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
        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(bot, harvestBlocks, SEARCH_RADIUS, 4, 8);
        if (choice == null) {
            fail("no_resource_nearby");
            return;
        }
        targetPos = choice.pos();
        if (choice.direct()) {
            startHarvest(bot);
            return;
        }
        phase = Phase.GOTO;
        bot.getActionPack().startPathTo(choice.stand());
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
        if (bot.getActionPack().isPathExecutorIdle()) {
            phase = Phase.SURVEY;
        }
    }

    private void harvest(AIPlayerEntity bot) {
        if (targetPos == null || !isHarvestBlock(bot, targetPos)) {
            pickupTicks = 120;
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
                phase = countSoFar >= targetCount ? Phase.DONE : Phase.SURVEY;
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

    private static Set<Item> acceptItemsFor(Item item) {
        // 原木:接受任意树种(配方下游 planks/stick/工具都接受任意 planks 家族)。
        if (RecipeRegistry.LOGS.contains(item)) {
            return Set.copyOf(RecipeRegistry.LOGS);
        }
        return Set.of(item);
    }

    private static Set<Block> harvestBlocksFor(Set<Item> items) {
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
