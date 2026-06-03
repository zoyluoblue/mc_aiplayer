package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.pathfinding.Standability;
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
    // F1:近处(16格)找不到资源时自动扩大半径走远找(32→48),而不是立刻失败交还大脑乱试。
    private static final int MAX_SEARCH_RADIUS = 48;
    private static final int SEARCH_DOWN = 6;
    private static final int SEARCH_UP = 12;
    private static final int LARGE_SCAN_THROTTLE_TICKS = 10; // 大半径扫描限频,护 TPS

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
    private int pickupMisses; // 连续"砍了但没捡到掉落物"的次数,超限才判 pickup_timeout(避免一棵没捡到就整个采集失败)
    private boolean pickupSweepAttempted;
    private StockpileTask stockpileTask;
    private int searchRadius = SEARCH_RADIUS;
    private int lastScanTick = -100;
    private boolean surfaceTried; // B:地下找不到树时,上浮到地表重试一次的兜底标志

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

    // B:bot 在地下(头顶不见天)且附近找不到可达资源时,上浮到正上方最近的"露天可站点",再重试采集。
    // teleport 上浮(会清 fallDistance);已在露天则不动。是"集中采集"之外的兜底,极少触发。
    private boolean trySurface(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        if (world.isSkyVisible(feet)) {
            return false;
        }
        int top = world.getBottomY() + world.getHeight();
        for (int dy = 1; feet.getY() + dy < top - 1 && dy <= 80; dy++) {
            BlockPos candidate = feet.up(dy);
            if (Standability.isStandable(world, candidate) && world.isSkyVisible(candidate)) {
                bot.getActionPack().stopAll();
                bot.teleport(world, candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D,
                        java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
                BotLog.action(bot, "gather_surfaced",
                        "to", candidate.getX() + "," + candidate.getY() + "," + candidate.getZ());
                return true;
            }
        }
        return false;
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
        // F1:大半径扫描限频,避免每 tick 扫 48 格立方体拖 TPS。
        int now = bot.getServer().getTicks();
        if (searchRadius > SEARCH_RADIUS && now - lastScanTick < LARGE_SCAN_THROTTLE_TICKS) {
            return;
        }
        lastScanTick = now;
        HarvestCore.TargetChoice choice = HarvestCore.nearestReachableBlock(bot, harvestBlocks, searchRadius, SEARCH_DOWN, SEARCH_UP);
        if (choice == null) {
            // F1:近处没有 → 自动扩大半径走远找,而不是立刻失败交还大脑乱试/空手挖/求助。
            if (searchRadius < MAX_SEARCH_RADIUS) {
                searchRadius = Math.min(MAX_SEARCH_RADIUS, searchRadius * 2);
                BotLog.action(bot, "gather_expand_search",
                        "radius", searchRadius,
                        "item", Registries.ITEM.getId(targetItem).toString());
                return;
            }
            // B:扩到最大半径仍找不到可达资源 → 若 bot 在地下(头顶不见天),先上浮到地表再试一轮
            //(兜底;正常情况下"集中采集"已让木头在地表一次备足,不会走到这一步)。
            if (!surfaceTried && trySurface(bot)) {
                surfaceTried = true;
                searchRadius = SEARCH_RADIUS;
                return;
            }
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
            } else if (countSoFar > 0 && ++pickupMisses <= 3) {
                // 这棵掉落物没捡到(卡树叶/掉水/despawn),但之前已采过一些 → 别 fail 整个采集,回 SURVEY 砍下一棵补。
                BotLog.action(bot, "gather_pickup_miss", "have", countSoFar + "/" + targetCount, "miss", pickupMisses);
                phase = Phase.SURVEY;
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
