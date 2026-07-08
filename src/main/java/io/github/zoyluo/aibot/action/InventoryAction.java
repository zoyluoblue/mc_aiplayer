package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
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
        return OptionalInt.empty();
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
        return harmfulSlot; // 只剩有害食物时才吃(总比饿死强);都没有则 -1
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
        var inventory = player.getInventory();
        if (slot < 0 || slot >= inventory.size()) {
            return ActionResult.failed("slot_out_of_range");
        }
        ItemStack removed = wholeStack ? inventory.removeStack(slot) : inventory.removeStack(slot, 1);
        if (removed.isEmpty()) {
            return ActionResult.failed("empty_slot");
        }
        player.dropItem(removed, false, true);
        BotLog.action(player, "drop", "slot", slot, "whole_stack", wholeStack);
        return ActionResult.SUCCESS;
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
            if (have <= keepEach) continue;
            int toDrop = have - keepEach;
            int max = Math.max(1, new ItemStack(junk).getMaxCount());
            var inventory = player.getInventory();
            // Извлекаем реальные стаки из инвентаря, чтобы сохранить NBT/чары
            while (toDrop > 0) {
                var optSlot = findItem(player, junk);
                if (optSlot.isEmpty()) break;
                int slot = optSlot.getAsInt();
                ItemStack stack = inventory.getStack(slot);
                int chunk = Math.min(toDrop, Math.min(max, stack.getCount()));
                ItemStack drop = stack.split(chunk);
                player.dropItem(drop, false, true);
                toDrop -= chunk;
            }
            BotLog.action(player, "drop_junk", "item", junk, "count", have - keepEach);
            droppedAny = true;
        }
        return droppedAny;
    }

    private static void addStack(Map<String, Integer> summary, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        String key = stack.getItem().toString();
        summary.merge(key, stack.getCount(), Integer::sum);
    }
}
