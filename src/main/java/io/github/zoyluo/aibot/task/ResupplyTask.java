package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ResupplyTask extends AbstractTask {
    private static final int BASE_RADIUS = 8;
    private static final double REACH_SQUARED = 20.25D;
    private static final double LOW_DURABILITY_FRACTION = 0.10D;

    public enum Need {
        TOOL,
        FOOD
    }

    private enum Phase {
        FIND_BASE,
        FIND_CONTAINER,
        GOTO_BASE,
        WALKING,
        WITHDRAWING,
        CRAFTING,
        EATING,
        DONE
    }

    private final Need need;
    private final Item requestedItem;
    private final List<BlockPos> containers = new ArrayList<>();
    private Phase phase = Phase.FIND_BASE;
    private BlockPos basePos;
    private BlockPos containerPos;
    private int containerIndex;
    private CraftTask craftTask;
    private EatTask eatTask;
    private String note = "";

    public static ResupplyTask tool(Item item) {
        return new ResupplyTask(Need.TOOL, item);
    }

    public static ResupplyTask food() {
        return new ResupplyTask(Need.FOOD, null);
    }

    private ResupplyTask(Need need, Item requestedItem) {
        this.need = need;
        this.requestedItem = requestedItem;
    }

    @Override
    public String name() {
        return "resupply";
    }

    @Override
    public String describe() {
        String target = requestedItem == null ? need.name().toLowerCase(java.util.Locale.ROOT) : Registries.ITEM.getId(requestedItem).toString();
        return "Resupplying " + target + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return switch (phase) {
            case FIND_BASE -> 0.0D;
            case FIND_CONTAINER -> 0.15D;
            case GOTO_BASE -> 0.25D;
            case WALKING -> 0.35D;
            case WITHDRAWING -> 0.55D;
            case CRAFTING -> 0.75D;
            case EATING -> 0.9D;
            case DONE -> 1.0D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND_BASE;
        containers.clear();
        containerIndex = 0;
        craftTask = null;
        eatTask = null;
        note = "";
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1800) {
            fail("resupply_timeout");
            return;
        }
        switch (phase) {
            case FIND_BASE -> findBase(bot);
            case FIND_CONTAINER -> findContainer(bot);
            case GOTO_BASE -> goToBase(bot);
            case WALKING -> walk(bot);
            case WITHDRAWING -> withdraw(bot);
            case CRAFTING -> craft(bot);
            case EATING -> eat(bot);
            case DONE -> complete();
        }
    }

    private void findBase(AIPlayerEntity bot) {
        if (alreadySatisfied(bot)) {
            phase = afterSupplyPhase(bot);
            return;
        }
        basePos = BotMemoryStore.INSTANCE.of(bot.getUuid())
                .placeIn(bot.getServerWorld(), "base")
                .orElse(null);
        if (basePos == null) {
            // 没有基地(深处挖矿/野外远征):别死在 no_base——直接用背包料就地合(stone_pickaxe=圆石+棍+随身工作台;
            // iron_pickaxe=备用铁锭+棍)。深处磨穿镐时背包常有充足圆石/备料,合不出(缺料)CraftTask 自会 no_supply
            // 诚实失败。治 real_armor:挖26铁石镐磨穿→resupply→FIND_BASE→no_base 死锁(bot有78圆石+表却去找基地)。
            startCrafting(bot);
            return;
        }
        phase = Phase.FIND_CONTAINER;
    }

    private void findContainer(AIPlayerEntity bot) {
        containers.clear();
        BlockPos.stream(basePos.add(-BASE_RADIUS, -3, -BASE_RADIUS), basePos.add(BASE_RADIUS, 4, BASE_RADIUS))
                .map(BlockPos::toImmutable)
                .filter(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos))
                .filter(pos -> ContainerAction.resolve(bot, pos).isPresent())
                .forEach(containers::add);
        containers.sort(Comparator
                .comparing((BlockPos pos) -> !containsSupply(bot, pos))
                .thenComparingDouble(pos -> pos.getSquaredDistance(bot.getBlockPos())));
        containerIndex = 0;
        if (containers.isEmpty()) {
            walkToBaseOrCraft(bot);
            return;
        }
        selectNextContainer(bot);
    }

    private void selectNextContainer(AIPlayerEntity bot) {
        if (alreadySatisfied(bot)) {
            phase = afterSupplyPhase(bot);
            return;
        }
        if (containerIndex >= containers.size()) {
            walkToBaseOrCraft(bot);
            return;
        }
        containerPos = containers.get(containerIndex++);
        if (bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) <= REACH_SQUARED) {
            phase = Phase.WITHDRAWING;
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

    private void walkToBaseOrCraft(AIPlayerEntity bot) {
        if (bot.getEyePos().squaredDistanceTo(basePos.toCenterPos()) <= REACH_SQUARED) {
            startCrafting(bot);
            return;
        }
        BlockPos stand = adjacentStand(bot, basePos);
        if (stand == null) {
            stand = basePos;
        }
        ActionResult result = bot.getActionPack().startPathTo(stand);
        if (result.isFailed()) {
            note = result.reason();
            startCrafting(bot);
            return;
        }
        phase = Phase.GOTO_BASE;
    }

    private void goToBase(AIPlayerEntity bot) {
        if (bot.getEyePos().squaredDistanceTo(basePos.toCenterPos()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            startCrafting(bot);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            startCrafting(bot);
        }
    }

    private void walk(AIPlayerEntity bot) {
        if (containerPos == null || ContainerAction.resolve(bot, containerPos).isEmpty()) {
            phase = Phase.FIND_CONTAINER;
            return;
        }
        if (bot.getEyePos().squaredDistanceTo(containerPos.toCenterPos()) <= REACH_SQUARED) {
            bot.getActionPack().stopAll();
            phase = Phase.WITHDRAWING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            selectNextContainer(bot);
        }
    }

    private void withdraw(AIPlayerEntity bot) {
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
        boolean moved = switch (need) {
            case TOOL -> withdrawTool(container, bot);
            case FOOD -> withdrawFood(container, bot);
        };
        if (moved) {
            phase = afterSupplyPhase(bot);
            return;
        }
        selectNextContainer(bot);
    }

    private boolean withdrawTool(Inventory container, AIPlayerEntity bot) {
        if (requestedItem == null) {
            return false;
        }
        ContainerAction.TransferResult result = ContainerAction.withdrawOne(container, bot, requestedItem, 1);
        if (!result.movedAny()) {
            note = result.reason();
            return false;
        }
        return equipUsableTool(bot);
    }

    private boolean withdrawFood(Inventory container, AIPlayerEntity bot) {
        Item food = firstFood(container);
        if (food == null) {
            return false;
        }
        ContainerAction.TransferResult result = ContainerAction.withdrawOne(container, bot, food, 16);
        if (!result.movedAny()) {
            note = result.reason();
            return false;
        }
        return InventoryAction.findFoodSlot(bot) >= 0;
    }

    private void startCrafting(AIPlayerEntity bot) {
        Item craftTarget = need == Need.FOOD ? Items.BREAD : requestedItem;
        if (craftTarget == null) {
            fail("no_supply");
            return;
        }
        int desiredCount = need == Need.TOOL ? InventoryAction.countItem(bot, craftTarget) + 1 : 1;
        craftTask = new CraftTask(craftTarget, desiredCount);
        craftTask.start(bot);
        phase = Phase.CRAFTING;
    }

    private void craft(AIPlayerEntity bot) {
        if (craftTask == null) {
            startCrafting(bot);
            return;
        }
        craftTask.tick(bot);
        if (craftTask.state() == TaskState.COMPLETED) {
            craftTask = null;
            if (need == Need.TOOL && !equipUsableTool(bot)) {
                fail("no_supply");
                return;
            }
            phase = afterSupplyPhase(bot);
            return;
        }
        if (craftTask.state() == TaskState.FAILED) {
            String reason = craftTask.failureReason();
            fail(reason == null || reason.isBlank() ? "no_supply" : "no_supply: " + reason);
        }
    }

    private void eat(AIPlayerEntity bot) {
        if (bot.getHungerManager().getFoodLevel() >= 20) {
            phase = Phase.DONE;
            return;
        }
        if (eatTask == null) {
            if (InventoryAction.findFoodSlot(bot) < 0) {
                fail("no_supply");
                return;
            }
            eatTask = new EatTask();
            eatTask.start(bot);
        }
        eatTask.tick(bot);
        if (eatTask.state() == TaskState.COMPLETED) {
            phase = Phase.DONE;
            eatTask = null;
        } else if (eatTask.state() == TaskState.FAILED) {
            String reason = eatTask.failureReason();
            fail(reason == null || reason.isBlank() ? "no_supply" : reason);
        }
    }

    private Phase afterSupplyPhase(AIPlayerEntity bot) {
        if (need == Need.FOOD && bot.getHungerManager().getFoodLevel() < 20) {
            return Phase.EATING;
        }
        return Phase.DONE;
    }

    private boolean alreadySatisfied(AIPlayerEntity bot) {
        return switch (need) {
            case TOOL -> equipUsableTool(bot);
            case FOOD -> InventoryAction.findFoodSlot(bot) >= 0;
        };
    }

    private boolean equipUsableTool(AIPlayerEntity bot) {
        if (requestedItem == null) {
            return false;
        }
        int bestSlot = -1;
        int bestRemaining = -1;
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            ItemStack stack = bot.getInventory().main.get(slot);
            if (!stack.isOf(requestedItem) || !isUsable(stack)) {
                continue;
            }
            int remaining = stack.isDamageable() ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return false;
        }
        InventoryAction.equipFromSlot(bot, bestSlot);
        return true;
    }

    private boolean containsSupply(AIPlayerEntity bot, BlockPos pos) {
        Inventory inventory = ContainerAction.resolve(bot, pos).orElse(null);
        if (inventory == null) {
            return false;
        }
        return switch (need) {
            case TOOL -> requestedItem != null && containsItem(inventory, requestedItem);
            case FOOD -> firstFood(inventory) != null;
        };
    }

    private static boolean containsItem(Inventory inventory, Item item) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStack(slot).isOf(item)) {
                return true;
            }
        }
        return false;
    }

    private static Item firstFood(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) {
                return stack.getItem();
            }
        }
        return null;
    }

    private static boolean isUsable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (!stack.isDamageable()) {
            return true;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return true;
        }
        return stack.getMaxDamage() - stack.getDamage() > max * LOW_DURABILITY_FRACTION;
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
}
