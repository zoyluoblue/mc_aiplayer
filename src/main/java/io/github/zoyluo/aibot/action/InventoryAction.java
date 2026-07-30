package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.collection.DefaultedList;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

public final class InventoryAction {
    private InventoryAction() {
    }

    public static ActionResult selectHotbar(AIPlayerEntity player, int slot) {
        if (!PlayerInventory.isValidHotbarIndex(slot)) {
            return ActionResult.failed("slot_out_of_range");
        }
        player.getInventory().selectedSlot = slot;
        BotLog.action(player, "select_slot", "slot", slot);
        return ActionResult.SUCCESS;
    }

    public static OptionalInt findItem(AIPlayerEntity player, Item item) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            if (inventory.main.get(slot).isOf(item)) {
                return OptionalInt.of(slot);
            }
        }
        for (int slot = 0; slot < inventory.offHand.size(); slot++) {
            if (inventory.offHand.get(slot).isOf(item)) {
                return promoteOffhandSlot(player, slot);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Moves one offhand stack into an addressable main-inventory slot without deleting the stack
     * displaced from a full inventory. Callers can then use the ordinary equip/select path.
     */
    public static OptionalInt promoteOffhandSlot(AIPlayerEntity player, int offhandSlot) {
        PlayerInventory inventory = player.getInventory();
        if (offhandSlot < 0 || offhandSlot >= inventory.offHand.size()) {
            return OptionalInt.empty();
        }
        ItemStack moving = inventory.offHand.get(offhandSlot);
        if (moving.isEmpty()) {
            return OptionalInt.empty();
        }
        int destination = firstEmptyMain(inventory);
        if (destination < 0) {
            destination = inventory.selectedSlot;
        }
        ItemStack displaced = inventory.main.get(destination);
        inventory.main.set(destination, moving);
        inventory.offHand.set(offhandSlot, displaced);
        inventory.markDirty();
        BotLog.action(player, "promote_offhand",
                "offhand_slot", offhandSlot,
                "main_slot", destination,
                "item", moving.getItem(),
                "swapped", !displaced.isEmpty());
        return OptionalInt.of(destination);
    }

    public static int countItem(AIPlayerEntity player, Item item) {
        int count = 0;
        var inventory = player.getInventory();
        for (ItemStack stack : inventory.main) {
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offHand) {
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int equipFromSlot(AIPlayerEntity player, int sourceSlot) {
        PlayerInventory inventory = player.getInventory();
        if (sourceSlot < 0 || sourceSlot >= inventory.main.size() || inventory.main.get(sourceSlot).isEmpty()) {
            return -1;
        }
        if (PlayerInventory.isValidHotbarIndex(sourceSlot)) {
            if (inventory.selectedSlot == sourceSlot) {
                return sourceSlot;
            }
            inventory.selectedSlot = sourceSlot;
            inventory.markDirty();
            BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", sourceSlot);
            return sourceSlot;
        }
        int hotbar = firstEmptyHotbar(inventory);
        if (hotbar < 0) {
            hotbar = inventory.selectedSlot;
        }
        ItemStack moving = inventory.main.get(sourceSlot);
        ItemStack inHotbar = inventory.main.get(hotbar);
        inventory.main.set(hotbar, moving);
        inventory.main.set(sourceSlot, inHotbar);
        inventory.selectedSlot = hotbar;
        inventory.markDirty();
        BotLog.action(player, "equip_slot", "source_slot", sourceSlot, "hotbar_slot", hotbar);
        return hotbar;
    }

    public static int firstEmptyHotbar(PlayerInventory inventory) {
        for (int slot = 0; slot <= 8; slot++) {
            if (inventory.main.get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int firstEmptyMain(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            if (inventory.main.get(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean hasItems(AIPlayerEntity player, Item item, int count) {
        return countItem(player, item) >= count;
    }

    public static boolean removeItems(AIPlayerEntity player, Item item, int count) {
        if (count <= 0) {
            return true;
        }
        if (countItem(player, item) < count) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        int remaining = removeFromList(inventory.main, item, count);
        if (remaining > 0) {
            remaining = removeFromList(inventory.offHand, item, remaining);
        }
        inventory.markDirty();
        BotLog.action(player, "remove_items", "item", item, "count", count);
        return remaining == 0;
    }

    public static int removeFromList(DefaultedList<ItemStack> list, Item item, int remaining) {
        for (int slot = 0; slot < list.size() && remaining > 0; slot++) {
            ItemStack stack = list.get(slot);
            if (!stack.isOf(item)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.decrement(take);
            remaining -= take;
        }
        return remaining;
    }

    // 有害/有副作用的食物:生鸡肉(30% 中毒)、腐肉、河豚、蜘蛛眼、毒马铃薯——只在没别的可吃时才退而求其次。
    private static final java.util.Set<Item> HARMFUL_FOODS = java.util.Set.of(
            Items.CHICKEN, Items.ROTTEN_FLESH, Items.PUFFERFISH, Items.SPIDER_EYE, Items.POISONOUS_POTATO);

    public static int findFoodSlot(AIPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        int harmfulSlot = -1;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.isEmpty() || !stack.contains(DataComponentTypes.FOOD)) {
                continue;
            }
            if (HARMFUL_FOODS.contains(stack.getItem())) {
                if (harmfulSlot < 0) {
                    harmfulSlot = slot; // 记下作为最后兜底
                }
                continue;
            }
            return slot; // 优先安全食物(熟肉/面包/生牛猪羊)
        }
        int harmfulOffhandSlot = -1;
        for (int slot = 0; slot < inventory.offHand.size(); slot++) {
            ItemStack stack = inventory.offHand.get(slot);
            if (stack.isEmpty() || !stack.contains(DataComponentTypes.FOOD)) {
                continue;
            }
            if (HARMFUL_FOODS.contains(stack.getItem())) {
                if (harmfulOffhandSlot < 0) {
                    harmfulOffhandSlot = slot;
                }
                continue;
            }
            return promoteOffhandSlot(player, slot).orElse(-1);
        }
        if (harmfulSlot >= 0) {
            return harmfulSlot;
        }
        return harmfulOffhandSlot < 0
                ? -1 : promoteOffhandSlot(player, harmfulOffhandSlot).orElse(-1);
    }

    public static Map<String, Integer> summarize(AIPlayerEntity player) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (ItemStack stack : inventory.main) {
            addStack(summary, stack);
        }
        for (ItemStack stack : inventory.offHand) {
            addStack(summary, stack);
        }
        return summary;
    }

    public static ActionResult giveItem(AIPlayerEntity player, ItemStack stack) {
        Item item = stack.getItem();
        int count = stack.getCount();
        boolean inserted = player.getInventory().insertStack(stack);
        player.getInventory().markDirty();
        BotLog.action(player, "give", "item", item, "count", count, "inserted_ok", inserted);
        return inserted ? ActionResult.SUCCESS : ActionResult.failed("inventory_full");
    }

    public static ActionResult dropSlot(AIPlayerEntity player, int slot, boolean wholeStack) {
        return dropSlotEntity(player, slot, wholeStack).isPresent()
                ? ActionResult.SUCCESS : ActionResult.failed("drop_entity_not_created");
    }

    /**
     * Drops through the ordinary survival-player path and returns the actual world entity.  Callers
     * that must prove physical containment (rather than merely freeing an inventory slot) can keep
     * the UUID and wait until the entity reaches its factual destination.  A rejected spawn is
     * rolled back into the inventory instead of turning a failed drop into direct item deletion.
     */
    public static java.util.Optional<ItemEntity> dropSlotEntity(
            AIPlayerEntity player, int slot, boolean wholeStack) {
        var inventory = player.getInventory();
        int count = slot >= 0 && slot < inventory.size()
                ? wholeStack ? inventory.getStack(slot).getCount() : 1 : 0;
        return dropSlotEntity(player, slot, count);
    }

    /** Drops an exact positive count from one slot through the same vanilla entity path. */
    public static java.util.Optional<ItemEntity> dropSlotEntity(
            AIPlayerEntity player, int slot, int requestedCount) {
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.size() || requestedCount <= 0) {
            return java.util.Optional.empty();
        }
        int count = Math.min(requestedCount, inventory.getStack(slot).getCount());
        ItemStack removed = inventory.removeStack(slot, count);
        if (removed.isEmpty()) {
            return java.util.Optional.empty();
        }
        ItemEntity entity = player.dropItem(removed, false, true);
        if (entity == null) {
            inventory.insertStack(removed);
            inventory.markDirty();
            return java.util.Optional.empty();
        }
        BotLog.action(player, "drop", "slot", slot, "count", count,
                "whole_stack", inventory.getStack(slot).isEmpty());
        return java.util.Optional.of(entity);
    }

    // P0 背包满自救:丢低值占位方块(圆石/泥土/砂砾族),每种保留 keepEach 个(搭路垫脚仍够用)。
    // 挖矿挖到背包满="破了块捡不起→计数不涨→白挖到超时"(挖掘类任务的隐形杀手)。
    private static final net.minecraft.item.Item[] JUNK_ITEMS = {
            net.minecraft.item.Items.COBBLESTONE, net.minecraft.item.Items.COBBLED_DEEPSLATE,
            net.minecraft.item.Items.DIRT, net.minecraft.item.Items.GRAVEL, net.minecraft.item.Items.SAND,
            net.minecraft.item.Items.DIORITE, net.minecraft.item.Items.ANDESITE,
            net.minecraft.item.Items.GRANITE, net.minecraft.item.Items.TUFF};

    public static boolean dropJunk(AIPlayerEntity player, int keepEach) {
        boolean droppedAny = false;
        for (net.minecraft.item.Item junk : JUNK_ITEMS) {
            int have = countItem(player, junk);
            if (have > keepEach && removeItems(player, junk, have - keepEach)) {
                // 必须按最大堆叠分堆扔:单个 ItemStack/ItemEntity 的 count 上限 99,
                // 一次性扔 2232 个 → 存档 ItemStack.toNbt 抛 "range [1;99]" → server 崩(实测 geo_flow 崩服根因)。
                int toDrop = have - keepEach;
                int max = Math.max(1, new ItemStack(junk).getMaxCount());
                while (toDrop > 0) {
                    int chunk = Math.min(toDrop, max);
                    player.dropItem(new ItemStack(junk, chunk), false, true);
                    toDrop -= chunk;
                }
                BotLog.action(player, "drop_junk", "item", junk, "count", have - keepEach);
                droppedAny = true;
            }
        }
        return droppedAny;
    }

    /**
     * Drops whole low-value stacks until the requested number of main-inventory slots is free.
     * Stone-like stacks are considered only after other junk and never reduce the carried
     * emergency pool below the requested reserve (or sixteen blocks, whichever is greater).
     * Every removal goes through {@link #dropSlot}, so the items remain ordinary world drops with
     * vanilla pickup delay instead of being deleted.
     *
     * @return number of inventory stacks dropped
     */
    public static int dropJunkUntilFreeSlots(AIPlayerEntity player,
                                             int requiredFreeSlots,
                                             int emergencyStoneLikeReserve) {
        int required = Math.max(0,
                Math.min(requiredFreeSlots, player.getInventory().main.size()));
        if (freeMainSlots(player) >= required) {
            return 0;
        }
        int stoneLike = stoneLikeCount(player);
        int protectedStoneLike = Math.min(stoneLike,
                Math.max(16, emergencyStoneLikeReserve));
        int droppedStacks = 0;
        for (int pass = 0; pass < 2 && freeMainSlots(player) < required; pass++) {
            for (int slot = 0;
                 slot < player.getInventory().main.size() && freeMainSlots(player) < required;
                 slot++) {
                ItemStack stack = player.getInventory().main.get(slot);
                if (stack.isEmpty() || !isJunk(stack.getItem())) {
                    continue;
                }
                boolean emergencyBlock = isStoneLike(stack.getItem());
                if (emergencyBlock != (pass == 1)) {
                    continue;
                }
                if (emergencyBlock && stoneLike - stack.getCount() < protectedStoneLike) {
                    continue;
                }
                int count = stack.getCount();
                if (!dropSlot(player, slot, true).isFailed()) {
                    droppedStacks++;
                    if (emergencyBlock) {
                        stoneLike -= count;
                    }
                }
            }
        }
        if (droppedStacks > 0) {
            BotLog.action(player, "drop_junk_for_slots",
                    "stacks", droppedStacks,
                    "free", freeMainSlots(player),
                    "required", required,
                    "stone_like", stoneLike,
                    "stone_reserve", protectedStoneLike);
        }
        return droppedStacks;
    }

    private static boolean isJunk(Item item) {
        if (isStoneLike(item)) {
            return true;
        }
        for (Item junk : JUNK_ITEMS) {
            if (item == junk) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStoneLike(Item item) {
        return item == Items.COBBLESTONE
                || item == Items.COBBLED_DEEPSLATE
                || item == Items.BLACKSTONE;
    }

    private static int stoneLikeCount(AIPlayerEntity player) {
        return countItem(player, Items.COBBLESTONE)
                + countItem(player, Items.COBBLED_DEEPSLATE)
                + countItem(player, Items.BLACKSTONE);
    }

    private static int freeMainSlots(AIPlayerEntity player) {
        int free = 0;
        for (ItemStack stack : player.getInventory().main) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static void addStack(Map<String, Integer> summary, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        String key = stack.getItem().toString();
        summary.merge(key, stack.getCount(), Integer::sum);
    }
}
