package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

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
        boolean inserted = player.getInventory().insertStack(stack);
        player.getInventory().markDirty();
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
        return ActionResult.SUCCESS;
    }

    private static void addStack(Map<String, Integer> summary, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        String key = stack.getItem().toString();
        summary.merge(key, stack.getCount(), Integer::sum);
    }
}
