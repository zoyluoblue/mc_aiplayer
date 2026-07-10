package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Predicate;

public final class StockpileTask extends AbstractTask {
    private static final int BASE_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;

    private enum Phase {
        FIND_BASE,
        FIND_CONTAINER,
        WALKING,
        TRANSFERRING,
        DONE
    }

    private final boolean allExceptTools;
    private Phase phase = Phase.FIND_BASE;
    private BlockPos basePos;
    private final List<BlockPos> containers = new ArrayList<>();
    private BlockPos containerPos;
    private int containerIndex;
    private int transferred;
    private final Set<BlockPos> depositedContainers = new LinkedHashSet<>();
    private String note = "";

    public StockpileTask(boolean allExceptTools) {
        this.allExceptTools = allExceptTools;
    }

    @Override
    public String name() {
        return "stockpile";
    }

    @Override
    public String describe() {
        return "Stockpiling transferred=" + transferred + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        if (containers.isEmpty()) {
            return transferred > 0 ? 0.5D : 0.0D;
        }
        return Math.min(0.95D, (double) Math.max(0, containerIndex) / containers.size());
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BASE;
        transferred = 0;
        containers.clear();
        containerIndex = 0;
        depositedContainers.clear();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1600) {
            fail("stockpile_timeout");
            return;
        }
        switch (phase) {
            case FIND_BASE -> findBase(bot);
            case FIND_CONTAINER -> findContainer(bot);
            case WALKING -> walk(bot);
            case TRANSFERRING -> transfer(bot);
            case DONE -> complete();
        }
    }

    private void findBase(AIPlayerEntity bot) {
        basePos = BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getServerWorld(), "base")
                .orElse(null);
        if (basePos == null) {
            fail("no_base");
            return;
        }
        phase = Phase.FIND_CONTAINER;
    }

    private void findContainer(AIPlayerEntity bot) {
        containers.clear();
        Item preferred = nextDepositItem(bot);
        BlockPos.stream(basePos.add(-BASE_RADIUS, -3, -BASE_RADIUS), basePos.add(BASE_RADIUS, 4, BASE_RADIUS))
                .map(BlockPos::toImmutable)
                .filter(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> ContainerAction.resolve(bot, pos).isPresent())
                .forEach(containers::add);
        containers.sort(Comparator
                .comparing((BlockPos pos) -> !containsItem(bot, pos, preferred))
                .thenComparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos())));
        if (containers.isEmpty()) {
            fail("no_base_container");
            return;
        }
        containerIndex = 0;
        selectNextContainer(bot);
    }

    private void selectNextContainer(AIPlayerEntity bot) {
        if (nextDepositItem(bot) == null) {
            phase = Phase.DONE;
            return;
        }
        if (containerIndex >= containers.size()) {
            fail(transferred > 0 ? "partial_stockpile_container_full" : "container_full");
            return;
        }
        containerPos = containers.get(containerIndex++);
        if (bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) <= REACH_SQUARED) {
            phase = Phase.TRANSFERRING;
            return;
        }
        BlockPos stand = adjacentStand(bot, containerPos);
        if (stand == null) {
            selectNextContainer(bot);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            selectNextContainer(bot);
            return;
        }
        phase = Phase.WALKING;
    }

    private void walk(AIPlayerEntity bot) {
        if (containerPos == null || ContainerAction.resolve(bot, containerPos).isEmpty()) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.TRANSFERRING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            selectNextContainer(bot);
        }
    }

    private void transfer(AIPlayerEntity bot) {
        if (containerPos == null
                || bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) > REACH_SQUARED
                || !io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, containerPos)) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        Inventory container = ContainerAction.resolve(bot, containerPos).orElse(null);
        if (container == null) {
            selectNextContainer(bot);
            return;
        }
        ContainerAction.TransferResult result = ContainerAction.depositOne(container, bot, depositFilter(), 64);
        if (result.movedAny()) {
            transferred += result.count();
            depositedContainers.add(containerPos.toImmutable());
            return;
        }
        if ("nothing_to_deposit".equals(result.reason())) {
            phase = Phase.DONE;
            return;
        }
        note = result.reason();
        selectNextContainer(bot);
    }

    private Predicate<ItemStack> depositFilter() {
        return allExceptTools ? stack -> !ContainerAction.isReservedTool(stack) : stack -> true;
    }

    private Item nextDepositItem(AIPlayerEntity bot) {
        for (ItemStack stack : bot.getInventory().main) {
            if (!stack.isEmpty() && depositFilter().test(stack)) {
                return stack.getItem();
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (!stack.isEmpty() && depositFilter().test(stack)) {
                return stack.getItem();
            }
        }
        return null;
    }

    private static boolean containsItem(AIPlayerEntity bot, BlockPos pos, Item item) {
        if (item == null) {
            return false;
        }
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

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos pos) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = pos.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate.toImmutable();
            }
        }
        return null;
    }

    public Set<BlockPos> depositedContainers() {
        return Set.copyOf(depositedContainers);
    }
}
