package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.DigNav;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class SmeltTask extends AbstractTask {
    private enum Phase {
        FINDING_FURNACE,
        WALKING_TO_FURNACE,
        PLACING_FURNACE,
        LOADING,
        SMELTING,
        COLLECTING
    }

    private static final Map<Item, Integer> FUEL_TICKS = new LinkedHashMap<>();
    private static final int BASE_FUEL_RADIUS = 8;

    static {
        FUEL_TICKS.put(Items.COAL, 1600);
        FUEL_TICKS.put(Items.CHARCOAL, 1600);
        FUEL_TICKS.put(Items.OAK_LOG, 300);
        FUEL_TICKS.put(Items.SPRUCE_LOG, 300);
        FUEL_TICKS.put(Items.BIRCH_LOG, 300);
        FUEL_TICKS.put(Items.JUNGLE_LOG, 300);
        FUEL_TICKS.put(Items.ACACIA_LOG, 300);
        FUEL_TICKS.put(Items.DARK_OAK_LOG, 300);
        FUEL_TICKS.put(Items.MANGROVE_LOG, 300);
        FUEL_TICKS.put(Items.CHERRY_LOG, 300);
        FUEL_TICKS.put(Items.OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.SPRUCE_PLANKS, 300);
        FUEL_TICKS.put(Items.BIRCH_PLANKS, 300);
        FUEL_TICKS.put(Items.JUNGLE_PLANKS, 300);
        FUEL_TICKS.put(Items.ACACIA_PLANKS, 300);
        FUEL_TICKS.put(Items.DARK_OAK_PLANKS, 300);
        FUEL_TICKS.put(Items.MANGROVE_PLANKS, 300);
        FUEL_TICKS.put(Items.CHERRY_PLANKS, 300);
        FUEL_TICKS.put(Items.STICK, 100);
    }

    private final Item input;
    private final Item output;
    private final int targetCount;
    private Phase phase = Phase.FINDING_FURNACE;
    private BlockPos furnacePos;
    private int collected;
    private final BlockMiner clearMiner = new BlockMiner(); // 被围放不下熔炉时,挖一格相邻方块腾位
    private boolean walkDigging; // 纯寻路到不了现有熔炉时,降级挖掘式朝熔炉挖过去(复用 clearMiner)

    public SmeltTask(Item input, Item output, int targetCount) {
        this.input = input;
        this.output = output;
        this.targetCount = Math.max(1, targetCount);
    }

    @Override
    public String name() {
        return "smelt";
    }

    @Override
    public String describe() {
        return "Smelting " + Registries.ITEM.getId(input) + " -> " + Registries.ITEM.getId(output)
                + " " + collected + "/" + targetCount + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) collected / targetCount);
    }

    @Override
    public boolean isWaiting() {
        // 挖掘式走向熔炉时 bot 站着挖、位置基本不变 → 视为 waiting,避免 StuckWatcher 误判(本任务总超时兜底)。
        return phase == Phase.SMELTING || (phase == Phase.WALKING_TO_FURNACE && walkDigging);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FINDING_FURNACE;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        clearMiner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 400 + targetCount * 260) {
            fail("smelt_timeout");
            return;
        }
        switch (phase) {
            case FINDING_FURNACE -> findFurnace(bot);
            case WALKING_TO_FURNACE -> walkToFurnace(bot);
            case PLACING_FURNACE -> placeFurnace(bot);
            case LOADING -> loadFurnace(bot);
            case SMELTING -> waitForOutput(bot);
            case COLLECTING -> collectOutput(bot);
        }
    }

    private void findFurnace(AIPlayerEntity bot) {
        if (!InventoryAction.hasItems(bot, input, 1)) {
            fail("missing " + Registries.ITEM.getId(input) + " x1");
            return;
        }
        furnacePos = nearestFurnace(bot).orElse(null);
        if (furnacePos == null) {
            if (InventoryAction.findItem(bot, Items.FURNACE).isEmpty()) {
                fail("missing minecraft:furnace");
                return;
            }
            phase = Phase.PLACING_FURNACE;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(furnacePos.toCenterPos()) <= 20.25D) {
            phase = Phase.LOADING;
            return;
        }
        BlockPos stand = adjacentStand(bot, furnacePos);
        if (stand == null) {
            fail("no_stand_position_for_furnace");
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        // 纯寻路到不了现有熔炉(地下被围/自挖隧道复杂,实测 GOAL_UNREACHABLE 会让整条目标 replan 回地表砍木,
        // bot 在深处回不去而卡死)→ 不失败,降级挖掘式朝熔炉挖过去。
        walkDigging = result.isFailed();
        phase = Phase.WALKING_TO_FURNACE;
    }

    private void walkToFurnace(AIPlayerEntity bot) {
        if (furnacePos == null || !bot.getServerWorld().getBlockState(furnacePos).isOf(Blocks.FURNACE)) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(furnacePos.toCenterPos()) <= 20.25D) {
            clearMiner.cancel(bot);
            bot.getActionPack().stopAll();
            phase = Phase.LOADING;
            return;
        }
        if (walkDigging) {
            // 挖掘式朝熔炉挖过去(地下被围也能到);朝向格挨岩浆受阻 → 回 FINDING 另选(LOADING 判定 4.5 格内即停,不会挖到熔炉本身)
            if (!DigNav.digStep(bot, clearMiner, furnacePos)) {
                walkDigging = false;
                phase = Phase.FINDING_FURNACE;
            }
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            // 纯寻路走不到 → 降级挖掘式,而非反复重找最终 smelt_timeout 失败触发 replan
            walkDigging = true;
        }
    }

    private void placeFurnace(AIPlayerEntity bot) {
        OptionalInt furnaceSlot = InventoryAction.findItem(bot, Items.FURNACE);
        if (furnaceSlot.isEmpty()) {
            fail("missing minecraft:furnace");
            return;
        }
        BlockPos pos = adjacentAir(bot);
        if (pos == null) {
            // 被围(四周方块)放不下 → 挖掉一个相邻可破坏方块腾位(bot 有镐就该自己清场,而非直接失败)。
            if (!clearSpaceForFurnace(bot)) {
                fail("no_place_for_furnace");
            }
            return; // 挖位中,下 tick adjacentAir 即可找到
        }
        InventoryAction.equipFromSlot(bot, furnaceSlot.getAsInt());
        ActionResult result = BuildAction.placeBlockAt(bot, pos);
        if (result.isFailed()) {
            fail("place_furnace_failed: " + result.reason());
            return;
        }
        furnacePos = pos;
        phase = Phase.LOADING;
    }

    private void loadFurnace(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            phase = Phase.FINDING_FURNACE;
            return;
        }
        ItemStack inputSlot = furnace.getStack(0);
        if (!inputSlot.isEmpty() && !inputSlot.isOf(input)) {
            fail("furnace_input_occupied: " + Registries.ITEM.getId(inputSlot.getItem()));
            return;
        }
        ItemStack outputSlot = furnace.getStack(2);
        if (!outputSlot.isEmpty() && !outputSlot.isOf(output)) {
            fail("unexpected_output: " + Registries.ITEM.getId(outputSlot.getItem()));
            return;
        }
        int outputQueued = outputSlot.isOf(output) ? outputSlot.getCount() : 0;
        int inputQueued = inputSlot.isOf(input) ? inputSlot.getCount() : 0;
        int remainingToQueue = targetCount - collected - outputQueued - inputQueued;
        int inputRoom = inputSlot.isEmpty() ? 64 : 64 - inputSlot.getCount();
        int inventoryInput = InventoryAction.countItem(bot, input);
        int inputToLoad = Math.min(Math.min(remainingToQueue, inputRoom), inventoryInput);
        if (remainingToQueue > 0 && inputToLoad <= 0 && inputQueued == 0) {
            fail("missing " + Registries.ITEM.getId(input) + " x" + remainingToQueue);
            return;
        }
        ItemStack fuelSlot = furnace.getStack(1);
        FuelChoice fuel = null;
        if (fuelSlot.isEmpty()) {
            int smeltsNeedingFuel = Math.max(1, inputQueued + Math.max(inputToLoad, 0));
            fuel = chooseFuel(bot, smeltsNeedingFuel);
            if (fuel == null) {
                fetchFuelFromBase(bot, smeltsNeedingFuel);
                fuel = chooseFuel(bot, smeltsNeedingFuel);
            }
            if (fuel == null) {
                fail("out_of_fuel");
                return;
            }
        }
        if (inputToLoad > 0) {
            if (!InventoryAction.removeItems(bot, input, inputToLoad)) {
                fail("missing " + Registries.ITEM.getId(input) + " x" + inputToLoad);
                return;
            }
            furnace.setStack(0, new ItemStack(input, inputSlot.getCount() + inputToLoad));
        }

        if (fuel != null) {
            if (!InventoryAction.removeItems(bot, fuel.item(), fuel.count())) {
                fail("out_of_fuel: " + Registries.ITEM.getId(fuel.item()));
                return;
            }
            furnace.setStack(1, new ItemStack(fuel.item(), fuel.count()));
        }
        furnace.markDirty();
        phase = Phase.SMELTING;
    }

    private void waitForOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getStack(2);
        if (!outputSlot.isEmpty() && !outputSlot.isOf(output)) {
            fail("unexpected_output: " + Registries.ITEM.getId(outputSlot.getItem()));
            return;
        }
        if (!outputSlot.isEmpty()) {
            phase = Phase.COLLECTING;
            return;
        }
        ItemStack inputSlot = furnace.getStack(0);
        ItemStack fuelSlot = furnace.getStack(1);
        if (collected < targetCount && (inputSlot.isEmpty() || fuelSlot.isEmpty())) {
            phase = Phase.LOADING;
        }
    }

    private void collectOutput(AIPlayerEntity bot) {
        AbstractFurnaceBlockEntity furnace = furnace(bot);
        if (furnace == null) {
            fail("furnace_missing");
            return;
        }
        ItemStack outputSlot = furnace.getStack(2);
        if (outputSlot.isEmpty()) {
            phase = Phase.SMELTING;
            return;
        }
        if (!outputSlot.isOf(output)) {
            fail("unexpected_output: " + Registries.ITEM.getId(outputSlot.getItem()));
            return;
        }
        int take = Math.min(targetCount - collected, outputSlot.getCount());
        ActionResult result = InventoryAction.giveItem(bot, new ItemStack(output, take));
        if (result.isFailed()) {
            fail(result.reason());
            return;
        }
        outputSlot.decrement(take);
        furnace.markDirty();
        collected += take;
        if (collected >= targetCount) {
            complete();
        } else {
            phase = Phase.LOADING;
        }
    }

    private AbstractFurnaceBlockEntity furnace(AIPlayerEntity bot) {
        if (furnacePos == null) {
            return null;
        }
        return bot.getServerWorld().getBlockEntity(furnacePos) instanceof AbstractFurnaceBlockEntity furnace ? furnace : null;
    }

    private static Optional<BlockPos> nearestFurnace(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(origin.add(-10, -3, -10), origin.add(10, 4, 10))
                .filter(pos -> bot.getServerWorld().getBlockState(pos).isOf(Blocks.FURNACE))
                .map(BlockPos::toImmutable)
                .min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)));
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    private static BlockPos adjacentAir(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = origin.offset(direction);
            if (bot.getServerWorld().getBlockState(candidate).isAir()) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    // 被围时:挖掉一个水平相邻的可破坏方块,腾出放熔炉的空位。返回 false=四周无可破坏方块(如基岩/流体)。
    private boolean clearSpaceForFurnace(AIPlayerEntity bot) {
        var world = bot.getServerWorld();
        BlockPos origin = bot.getBlockPos();
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = origin.offset(direction);
            var s = world.getBlockState(candidate);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.getHardness(world, candidate) < 0.0F
                    || world.getBlockEntity(candidate) != null) {
                continue;
            }
            BlockMiner.Status st = clearMiner.target() != null && clearMiner.target().equals(candidate)
                    ? clearMiner.tick(bot)
                    : beginClear(bot, candidate);
            return st != BlockMiner.Status.FAILED;
        }
        return false;
    }

    private BlockMiner.Status beginClear(AIPlayerEntity bot, BlockPos pos) {
        clearMiner.begin(bot, pos);
        return clearMiner.tick(bot);
    }

    private static FuelChoice chooseFuel(AIPlayerEntity bot, int smeltCount) {
        int ticksNeeded = smeltCount * 200;
        for (Map.Entry<Item, Integer> entry : FUEL_TICKS.entrySet()) {
            int available = InventoryAction.countItem(bot, entry.getKey());
            int needed = divideRoundUp(ticksNeeded, entry.getValue());
            if (available < needed) {
                continue;
            }
            return new FuelChoice(entry.getKey(), needed);
        }
        return null;
    }

    private static void fetchFuelFromBase(AIPlayerEntity bot, int smeltCount) {
        BlockPos base = BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getServerWorld(), "base")
                .orElse(null);
        if (base == null) {
            return;
        }
        for (Map.Entry<Item, Integer> entry : FUEL_TICKS.entrySet()) {
            Item fuel = entry.getKey();
            int needed = divideRoundUp(smeltCount * 200, entry.getValue());
            if (InventoryAction.countItem(bot, fuel) >= needed) {
                return;
            }
            for (BlockPos pos : fuelContainers(bot, base, fuel)) {
                Inventory container = ContainerAction.resolve(bot, pos).orElse(null);
                if (container == null) {
                    continue;
                }
                int missing = needed - InventoryAction.countItem(bot, fuel);
                if (missing <= 0) {
                    return;
                }
                ContainerAction.TransferResult result = ContainerAction.withdrawOne(container, bot, fuel, missing);
                if (result.movedAny() && InventoryAction.countItem(bot, fuel) >= needed) {
                    return;
                }
            }
        }
    }

    private static java.util.List<BlockPos> fuelContainers(AIPlayerEntity bot, BlockPos base, Item fuel) {
        return BlockPos.stream(base.add(-BASE_FUEL_RADIUS, -3, -BASE_FUEL_RADIUS), base.add(BASE_FUEL_RADIUS, 4, BASE_FUEL_RADIUS))
                .map(BlockPos::toImmutable)
                .filter(pos -> containsItem(bot, pos, fuel))
                .sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos())))
                .toList();
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

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private record FuelChoice(Item item, int count) {
    }
}
