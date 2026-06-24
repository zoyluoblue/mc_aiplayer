package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FarmTask extends AbstractTask {
    private enum Phase {
        SURVEY,
        GOTO,
        TILL,
        PLANT,
        HARVEST,
        NEXT,
        DEPOSIT,
        DEPOSIT_GOTO,
        DEPOSIT_TRANSFER,
        DONE
    }

    private static final int DEPOSIT_RADIUS = 8;
    private static final int DEPOSIT_INTERVAL_ACTIONS = 16;
    private static final double REACH_SQUARED = 20.25D;

    private final BlockPos areaCenter;
    private final int radius;
    private final Item seed;
    private final Block crop;
    private final boolean keepTending;
    private final boolean harvestOnly;
    // P3:数量受限模式(GoalExecutor FARM 步用)。produceItem!=null 时,收到 targetHarvest 个产出即 complete。
    private final Item produceItem;
    private final int targetHarvest;
    private int produceBaseline;
    private final List<FarmTarget> targets = new ArrayList<>();
    private final List<BlockPos> depositContainers = new ArrayList<>();
    private Phase phase = Phase.SURVEY;
    private FarmTarget current;
    private BlockPos basePos;
    private BlockPos depositContainerPos;
    private int depositContainerIndex;
    private int completedActions;
    private int lastDepositActionCount;
    private int waitTicks;
    private boolean waitingForMaturity; // 种完后留在原地等作物自然成熟(数量受限模式),非卡死
    private String note = "";

    public FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending, boolean harvestOnly) {
        this(areaCenter, radius, seed, crop, keepTending, harvestOnly, null, 0);
    }

    /** P3:数量受限构造——produceItem 收到 targetHarvest 个即完成(供 GoalExecutor FARM 步)。 */
    public FarmTask(BlockPos areaCenter, int radius, Item seed, Block crop, boolean keepTending,
                    boolean harvestOnly, Item produceItem, int targetHarvest) {
        this.areaCenter = areaCenter.toImmutable();
        this.radius = Math.max(1, radius);
        this.seed = seed;
        this.crop = crop;
        this.keepTending = keepTending;
        this.harvestOnly = harvestOnly;
        this.produceItem = produceItem;
        this.targetHarvest = Math.max(0, targetHarvest);
    }

    @Override
    public String name() {
        return harvestOnly ? "harvest" : "farm";
    }

    @Override
    public String describe() {
        return name() + " crop=" + crop + " center=" + compact(areaCenter) + " radius=" + radius
                + " done=" + completedActions + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (keepTending) {
            return Math.min(0.95D, completedActions / 16.0D);
        }
        int total = completedActions + targets.size() + (current == null ? 0 : 1);
        return total == 0 ? 0.0D : Math.min(0.95D, (double) completedActions / total);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SURVEY;
        lastDepositActionCount = completedActions;
        waitingForMaturity = false;
        produceBaseline = produceItem == null ? 0 : InventoryAction.countItem(bot, produceItem);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        // P3:数量受限模式——收够目标产出即完成(优先于其它阶段判断)。
        if (produceItem != null
                && InventoryAction.countItem(bot, produceItem) - produceBaseline >= targetHarvest) {
            complete();
            return;
        }
        if (!keepTending && produceItem == null && elapsed > 2400) {
            // 数量受限模式(produceItem!=null)要等作物自然成熟,走下面 12000t 配额超时,不受这条 2400t 短超时制约。
            fail("farm_timeout");
            return;
        }
        // P3:数量受限模式有自己的硬超时(等作物成熟要时间,但不能无限),复用 keepTending 的巡逻逻辑。
        if (produceItem != null && elapsed > 12000) {
            fail("farm_quota_timeout collected="
                    + (InventoryAction.countItem(bot, produceItem) - produceBaseline) + "/" + targetHarvest);
            return;
        }
        switch (phase) {
            case SURVEY -> survey(bot);
            case GOTO -> goToTarget(bot);
            case TILL -> till(bot);
            case PLANT -> plant(bot);
            case HARVEST -> harvest(bot);
            case NEXT -> next(bot);
            case DEPOSIT -> prepareDeposit(bot);
            case DEPOSIT_GOTO -> goToDepositContainer(bot);
            case DEPOSIT_TRANSFER -> depositTransfer(bot);
            case DONE -> done(bot);
        }
    }

    private void survey(AIPlayerEntity bot) {
        targets.clear();
        current = null;
        ServerWorld world = bot.getServerWorld();
        boolean hasSeeds = !harvestOnly && InventoryAction.countItem(bot, seed) > 0;
        BlockPos.stream(areaCenter.add(-radius, -1, -radius), areaCenter.add(radius, 1, radius))
                .map(BlockPos::toImmutable)
                .forEach(pos -> addTargetIfUseful(world, pos, hasSeeds));
        targets.sort(Comparator.comparingDouble(pos -> pos.ground().getSquaredDistance(bot.getBlockPos())));
        if (targets.isEmpty()) {
            if (!harvestOnly && InventoryAction.countItem(bot, seed) <= 0 && completedActions == 0) {
                fail("missing " + seed + " x1");
                return;
            }
            // 等熟(真实地形种田做面包命门):种了但还没熟的作物 + 数量受限还没凑够产出 → 别 DONE,
            // 留在原地等作物自然成熟(配合 random tick),熟了下次 survey 即生成 HARVEST target 去收。
            // 旧逻辑种完 targets 空即 DONE、从不等熟(real_wheat 实测 till/plant 6-12 次但 harvest=0);
            // lab food_farm 靠 perTick 强制催熟才过。超时由上面 12000t 配额兜底,熟不了也不会无限等。
            boolean needMore = produceItem != null
                    && InventoryAction.countItem(bot, produceItem) - produceBaseline < targetHarvest;
            if (!harvestOnly && needMore && hasImmatureCrops(world)) {
                waitingForMaturity = true;
                return; // 留在 SURVEY,下 tick 继续等熟(不切 DONE);isWaiting() 期间豁免 StuckWatcher
            }
            waitingForMaturity = false;
            if (keepTending && hasDepositItems(bot)) {
                phase = Phase.DEPOSIT;
            } else {
                phase = Phase.DONE;
            }
            return;
        }
        waitingForMaturity = false;
        phase = Phase.NEXT;
    }

    private void addTargetIfUseful(ServerWorld world, BlockPos ground, boolean hasSeeds) {
        BlockPos cropPos = ground.up();
        if (world.getBlockState(cropPos).isOf(crop) && FarmAction.isMature(world, cropPos)) {
            targets.add(new FarmTarget(ground, TargetAction.HARVEST));
            return;
        }
        if (harvestOnly || world.getBlockState(cropPos).isOf(crop) || !world.getBlockState(cropPos).isAir()) {
            return;
        }
        if (!hasSeeds) {
            return;
        }
        if (world.getBlockState(ground).isOf(Blocks.FARMLAND)) {
            targets.add(new FarmTarget(ground, TargetAction.PLANT));
            return;
        }
        if (FarmAction.isTillable(world.getBlockState(ground))) {
            targets.add(new FarmTarget(ground, TargetAction.TILL_PLANT));
        }
    }

    private void next(AIPlayerEntity bot) {
        if (keepTending && completedActions - lastDepositActionCount >= DEPOSIT_INTERVAL_ACTIONS
                && hasDepositItems(bot)) {
            phase = Phase.DEPOSIT;
            return;
        }
        current = targets.isEmpty() ? null : targets.remove(0);
        if (current == null) {
            phase = Phase.SURVEY;
            return;
        }
        phase = Phase.GOTO;
        goToTarget(bot);
    }

    private void goToTarget(AIPlayerEntity bot) {
        if (current == null) {
            phase = Phase.NEXT;
            return;
        }
        BlockPos focus = current.action() == TargetAction.HARVEST ? current.ground().up() : current.ground();
        if (bot.getEyePos().distanceTo(focus.toCenterPos()) <= 4.5D) {
            bot.getActionPack().stopAll();
            phase = switch (current.action()) {
                case HARVEST -> Phase.HARVEST;
                case PLANT -> Phase.PLANT;
                case TILL_PLANT -> Phase.TILL;
            };
            return;
        }
        BlockPos stand = adjacentStandPos(bot, current.ground());
        if (stand == null) {
            note = "unreachable " + compact(current.ground());
            phase = Phase.NEXT;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
    }

    private void till(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.till(bot, current.ground());
        if (result.isFailed()) {
            note = result.reason();
            phase = Phase.NEXT;
            return;
        }
        phase = Phase.PLANT;
    }

    private void plant(AIPlayerEntity bot) {
        if (InventoryAction.countItem(bot, seed) <= 0) {
            note = "plant_skipped:missing " + seed + " x1";
            phase = Phase.NEXT;
            return;
        }
        ActionResult result = FarmAction.plant(bot, current.ground(), seed, crop);
        if (result.isFailed()) {
            note = result.reason();
        } else {
            completedActions++;
        }
        phase = Phase.NEXT;
    }

    private void prepareDeposit(AIPlayerEntity bot) {
        Item item = nextDepositItem(bot);
        if (item == null) {
            finishDeposit();
            return;
        }
        basePos = BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getServerWorld(), "base")
                .orElse(null);
        if (basePos == null) {
            note = "deposit_skipped:no_base";
            finishDeposit();
            return;
        }
        depositContainers.clear();
        BlockPos.stream(basePos.add(-DEPOSIT_RADIUS, -3, -DEPOSIT_RADIUS), basePos.add(DEPOSIT_RADIUS, 4, DEPOSIT_RADIUS))
                .map(BlockPos::toImmutable)
                .filter(pos -> ContainerAction.resolve(bot, pos).isPresent())
                .forEach(depositContainers::add);
        depositContainers.sort(Comparator
                .comparing((BlockPos pos) -> !containsItem(bot, pos, item))
                .thenComparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos())));
        depositContainerIndex = 0;
        selectDepositContainer(bot);
    }

    private void selectDepositContainer(AIPlayerEntity bot) {
        if (nextDepositItem(bot) == null) {
            finishDeposit();
            return;
        }
        if (depositContainerIndex >= depositContainers.size()) {
            note = "deposit_skipped:no_base_container";
            finishDeposit();
            return;
        }
        depositContainerPos = depositContainers.get(depositContainerIndex++);
        if (bot.getEyePos().squaredDistanceTo(depositContainerPos.toCenterPos()) <= REACH_SQUARED) {
            phase = Phase.DEPOSIT_TRANSFER;
            return;
        }
        BlockPos stand = adjacentStandPos(bot, depositContainerPos.down());
        if (stand == null) {
            selectDepositContainer(bot);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            selectDepositContainer(bot);
            return;
        }
        phase = Phase.DEPOSIT_GOTO;
    }

    private void goToDepositContainer(AIPlayerEntity bot) {
        if (depositContainerPos == null || ContainerAction.resolve(bot, depositContainerPos).isEmpty()) {
            phase = Phase.DEPOSIT;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(depositContainerPos.toCenterPos()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.DEPOSIT_TRANSFER;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            selectDepositContainer(bot);
        }
    }

    private void depositTransfer(AIPlayerEntity bot) {
        Inventory container = ContainerAction.resolve(bot, depositContainerPos).orElse(null);
        if (container == null) {
            selectDepositContainer(bot);
            return;
        }
        Item item = nextDepositItem(bot);
        if (item == null) {
            finishDeposit();
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.depositOne(container, bot, stack -> stack.isOf(item), maxDepositCount(bot, item));
        if (result.movedAny()) {
            note = "deposited " + item + " x" + result.count();
            return;
        }
        selectDepositContainer(bot);
    }

    private void finishDeposit() {
        lastDepositActionCount = completedActions;
        phase = Phase.DONE;
    }

    private void harvest(AIPlayerEntity bot) {
        ActionResult result = FarmAction.harvest(bot, current.ground().up());
        if (result.isFailed()) {
            note = result.reason();
            phase = Phase.NEXT;
            return;
        }
        completedActions++;
        // 收割产出/种子是地上的 ItemEntity(FarmAction.harvest 用 breakBlock dropStacks=true 掉落,不直接入包)。
        // bot 在 reach 距离(≤4.5 格)收割,脚下 1 格外的掉落 vanilla 自动拾取够不到 → 必须强制拾取,
        // 否则 countItem(produce) 永不增、收割/种田目标永不完成(farm_wheat_from_scratch 实测超时、背包 0 小麦)。
        // forcePickup 绕过掉落物的 10-tick 拾取延迟,本 tick 即可入包(与 DigDownTask 同款修法)。
        HarvestCore.forcePickupNearbyAnyOf(bot, java.util.Set.of(harvestItem(), seed), 5.0D, 4.0D);
        if (!harvestOnly && InventoryAction.countItem(bot, seed) > 0) {
            ActionResult plantResult = FarmAction.plant(bot, current.ground(), seed, crop);
            if (plantResult.isFailed()) {
                note = "replant_failed:" + plantResult.reason();
            }
        } else if (!harvestOnly) {
            note = "replant_skipped:missing " + seed + " x1";
        }
        phase = Phase.NEXT;
    }

    private void done(AIPlayerEntity bot) {
        if (keepTending) {
            waitTicks++;
            if (waitTicks >= 100) {
                waitTicks = 0;
                phase = Phase.SURVEY;
            }
            return;
        }
        complete();
    }

    @Override
    public boolean isWaiting() {
        // 等熟期间 bot 站在田边不动是正常作业(等作物长熟),豁免 StuckWatcher 误杀;卡死交 12000t 配额超时兜底。
        return (keepTending && phase == Phase.DONE) || waitingForMaturity;
    }

    // 区内是否有"已种但还没熟"的本作物——有就值得留下等熟,别种完就走(治 real_wheat harvest=0)。
    private boolean hasImmatureCrops(ServerWorld world) {
        return BlockPos.stream(areaCenter.add(-radius, -1, -radius), areaCenter.add(radius, 1, radius))
                .anyMatch(ground -> {
                    BlockPos cropPos = ground.up();
                    return world.getBlockState(cropPos).isOf(crop) && !FarmAction.isMature(world, cropPos);
                });
    }

    private boolean hasDepositItems(AIPlayerEntity bot) {
        return nextDepositItem(bot) != null;
    }

    private Item nextDepositItem(AIPlayerEntity bot) {
        Item harvest = harvestItem();
        if (harvest != seed && InventoryAction.countItem(bot, harvest) > 0) {
            return harvest;
        }
        if (InventoryAction.countItem(bot, seed) > seedReserve()) {
            return seed;
        }
        return null;
    }

    private int maxDepositCount(AIPlayerEntity bot, Item item) {
        int count = InventoryAction.countItem(bot, item);
        if (item == seed) {
            return Math.max(0, count - seedReserve());
        }
        return count;
    }

    private int seedReserve() {
        int area = (radius * 2 + 1) * (radius * 2 + 1);
        return Math.max(8, area);
    }

    private Item harvestItem() {
        if (crop == Blocks.WHEAT) {
            return Items.WHEAT;
        }
        if (crop == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (crop == Blocks.POTATOES) {
            return Items.POTATO;
        }
        return seed;
    }

    private static boolean containsItem(AIPlayerEntity bot, BlockPos pos, Item item) {
        Inventory inventory = ContainerAction.resolve(bot, pos).orElse(null);
        if (inventory == null) {
            return false;
        }
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction).up();
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private enum TargetAction {
        TILL_PLANT,
        PLANT,
        HARVEST
    }

    private record FarmTarget(BlockPos ground, TargetAction action) {
    }
}
