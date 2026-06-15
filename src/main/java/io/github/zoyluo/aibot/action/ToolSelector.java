package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

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
            return 0.001F; // 即将断 → 别用,免得断在手里
        }
        // 不要求工具的块(土/砂/砾/原木等):保持原行为,按最快工具选(铲/斧最快),不影响。
        if (!state.isToolRequired()) {
            return speed;
        }
        // 要求工具但本工具档不够(挖不出掉落,如石镐挖钻石矿):兜底极低分,只在没别的选时勉强用。
        if (!stack.isSuitableFor(state)) {
            return Math.max(0.001F, speed * 0.01F);
        }
        // 要求工具且能挖:耐久保全策略——同样能挖的工具里,优先用【易补充】的石器(无限鹅卵石+耐久足),
        // 把稀缺的铁/钻镐耐久留给真正要求高档的矿(钻石/金/红石矿,石镐挖不动会落到上面的 !suitable 分支自然选铁)。
        // 治本:旧逻辑纯按速度选→有铁就拿铁挖石头/下潜上百格→铁镐磨穿→到钻石矿 need_better_tool(real_diamond 主回归)。
        // 分层:suitable 基础分(100)压倒一切;其上叠加 preservationRank(石>木/金>铁>钻)*10;speed 仅做同档微小 tiebreak。
        return 100.0F + preservationRank(stack) * 10.0F + Math.min(speed, 9.9F) * 0.1F;
    }

    // 耐久保全偏好:数值越大越优先使用。石器最优先(鹅卵石无限、断了 replan 秒补、耐久 131 够用);
    // 木/金次之(易补但耐久低);铁/钻最该保留(稀缺、做一把要挖矿+熔炼),留给石镐挖不动的高档矿。
    private static int preservationRank(ItemStack stack) {
        String path = Registries.ITEM.getId(stack.getItem()).getPath();
        if (path.startsWith("stone_")) {
            return 5;
        }
        if (path.startsWith("wooden_") || path.startsWith("golden_")) {
            return 4;
        }
        if (path.startsWith("iron_")) {
            return 2;
        }
        if (path.startsWith("diamond_")) {
            return 1;
        }
        if (path.startsWith("netherite_")) {
            return 0;
        }
        return 3; // 非分层材质工具:居中,不特别保留也不特别消耗
    }
}
