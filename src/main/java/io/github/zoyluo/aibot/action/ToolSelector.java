package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

public final class ToolSelector {
    private ToolSelector() {
    }

    public record Selection(boolean changed, int slot, ItemStack stack, float score) {
        public String describe() {
            if (slot < 0 || stack.isEmpty()) {
                return "no_tool";
            }
            return stack.getItem() + " slot=" + slot + " score=" + score + " changed=" + changed;
        }
    }

    public static Selection equipBestTool(AIPlayerEntity player, BlockState state) {
        PlayerInventory inventory = player.getInventory();
        int currentSlot = inventory.selectedSlot;
        ItemStack currentStack = inventory.main.get(currentSlot);
        float currentScore = score(currentStack, state);
        int bestSlot = currentSlot;
        ItemStack bestStack = currentStack;
        float bestScore = currentScore;

        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float candidateScore = score(stack, state);
            if (candidateScore > bestScore + 0.001F) {
                bestScore = candidateScore;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        if (bestSlot != currentSlot) {
            int hotbar = InventoryAction.equipFromSlot(player, bestSlot);
            ItemStack equipped = hotbar >= 0 ? player.getInventory().main.get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_best_tool", "slot", hotbar, "tool", equipped.getItem(), "score", bestScore);
            return new Selection(true, hotbar, equipped, bestScore);
        }
        return new Selection(false, currentSlot, bestStack, bestScore);
    }

    private static float score(ItemStack stack, BlockState state) {
        if (stack.isEmpty()) {
            return state.isToolRequired() ? 0.001F : 1.0F;
        }
        float speed = stack.getMiningSpeedMultiplier(state);
        if (stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1) {
            return 0.001F;
        }
        if (state.isToolRequired() && !stack.isSuitableFor(state)) {
            return Math.max(0.001F, speed * 0.01F);
        }
        return speed;
    }
}
