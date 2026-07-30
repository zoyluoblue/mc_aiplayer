package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.mining.OreScan;
import io.github.zoyluo.aibot.mining.ToolTier;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
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
        int bestOffhandSlot = -1;
        ItemStack bestStack = currentStack;
        float bestScore = currentScore;
        int bestHandSafety = softBlockHandSafety(currentStack, state);

        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            // Only an empty hotbar slot can be selected as an executable empty hand. Empty storage
            // slots are not candidates because equipFromSlot deliberately rejects them.
            if (stack.isEmpty() && !PlayerInventory.isValidHotbarIndex(slot)) {
                continue;
            }
            float candidateScore = score(stack, state);
            int candidateHandSafety = softBlockHandSafety(stack, state);
            if (isBetterCandidate(candidateScore, candidateHandSafety,
                    bestScore, bestHandSafety)) {
                bestScore = candidateScore;
                bestHandSafety = candidateHandSafety;
                bestSlot = slot;
                bestOffhandSlot = -1;
                bestStack = stack;
            }
        }
        for (int slot = 0; slot < inventory.offHand.size(); slot++) {
            ItemStack stack = inventory.offHand.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float candidateScore = score(stack, state);
            int candidateHandSafety = softBlockHandSafety(stack, state);
            if (isBetterCandidate(candidateScore, candidateHandSafety,
                    bestScore, bestHandSafety)) {
                bestScore = candidateScore;
                bestHandSafety = candidateHandSafety;
                bestSlot = -1;
                bestOffhandSlot = slot;
                bestStack = stack;
            }
        }

        if (bestOffhandSlot >= 0) {
            int promoted = InventoryAction.promoteOffhandSlot(player, bestOffhandSlot)
                    .orElse(-1);
            int hotbar = promoted < 0 ? -1 : InventoryAction.equipFromSlot(player, promoted);
            ItemStack equipped = hotbar >= 0
                    ? player.getInventory().main.get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_best_tool", "slot", hotbar,
                    "tool", equipped.getItem(), "score", bestScore, "source", "offhand");
            return new Selection(hotbar >= 0, hotbar, equipped, bestScore);
        }
        if (bestSlot != currentSlot) {
            if (bestStack.isEmpty()) {
                boolean changed = InventoryAction.selectHotbar(player, bestSlot).isSuccess();
                BotLog.action(player, "equip_best_tool", "slot", bestSlot,
                        "tool", "empty_hand", "score", bestScore,
                        "reason", "preserve_soft_block_melee_durability");
                return new Selection(changed, bestSlot, ItemStack.EMPTY, bestScore);
            }
            int hotbar = InventoryAction.equipFromSlot(player, bestSlot);
            ItemStack equipped = hotbar >= 0 ? player.getInventory().main.get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_best_tool", "slot", hotbar, "tool", equipped.getItem(), "score", bestScore);
            return new Selection(true, hotbar, equipped, bestScore);
        }
        return new Selection(false, currentSlot, bestStack, bestScore);
    }

    /**
     * OreDig channel policy: use the lowest healthy pickaxe tier that can harvest the block, but
     * never go below stone for ordinary rock. Thus stone/deepslate consume renewable stone picks,
     * while diamond/redstone/gold automatically select iron and obsidian selects diamond. Other
     * BlockMiner users keep {@link #equipBestTool} unchanged.
     */
    public static Selection equipMiningChannelTool(AIPlayerEntity player, BlockState state) {
        if (!state.isToolRequired()) {
            return equipBestTool(player, state);
        }
        PlayerInventory inventory = player.getInventory();
        int currentSlot = inventory.selectedSlot;
        int minimumTier = channelMinimumTier(ToolTier.requiredPickaxeTier(state.getBlock()));
        int maximumTier = channelMaximumTier(minimumTier, OreScan.isOreBlock(state.getBlock()));
        int bestSlot = -1;
        int bestOffhandSlot = -1;
        int bestTier = Integer.MAX_VALUE;
        int bestRemaining = -1;
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            int tier = ToolTier.pickaxeTier(stack);
            if (tier < minimumTier || tier > maximumTier || !stack.isSuitableFor(state)
                    || (stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1)) {
                continue;
            }
            int remaining = stack.isDamageable()
                    ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
            if (tier < bestTier || (tier == bestTier && remaining > bestRemaining)) {
                bestTier = tier;
                bestRemaining = remaining;
                bestSlot = slot;
                bestOffhandSlot = -1;
            }
        }
        for (int slot = 0; slot < inventory.offHand.size(); slot++) {
            ItemStack stack = inventory.offHand.get(slot);
            int tier = ToolTier.pickaxeTier(stack);
            if (tier < minimumTier || tier > maximumTier || !stack.isSuitableFor(state)
                    || (stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1)) {
                continue;
            }
            int remaining = stack.isDamageable()
                    ? stack.getMaxDamage() - stack.getDamage() : Integer.MAX_VALUE;
            if (tier < bestTier || (tier == bestTier && remaining > bestRemaining)) {
                bestTier = tier;
                bestRemaining = remaining;
                bestSlot = -1;
                bestOffhandSlot = slot;
            }
        }
        if (bestSlot < 0 && bestOffhandSlot < 0) {
            // Ordinary branch rock is a renewable stone-tool channel. Falling back to an iron or
            // diamond pick silently consumes the finite mission tool until the target ore becomes
            // unharvestable; return an explicit absence so BlockMiner can fail and replan service.
            return new Selection(false, -1, ItemStack.EMPTY, 0.0F);
        }
        float policyScore = 1000.0F - bestTier * 10.0F;
        if (bestOffhandSlot >= 0) {
            int promoted = InventoryAction.promoteOffhandSlot(player, bestOffhandSlot)
                    .orElse(-1);
            int hotbar = promoted < 0 ? -1 : InventoryAction.equipFromSlot(player, promoted);
            ItemStack equipped = hotbar >= 0 ? inventory.main.get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_mining_channel_tool",
                    "slot", hotbar, "tool", equipped.getItem(), "tier", bestTier,
                    "source", "offhand");
            return new Selection(hotbar >= 0, hotbar, equipped, policyScore);
        }
        ItemStack bestStack = inventory.main.get(bestSlot);
        if (bestSlot != currentSlot) {
            int hotbar = InventoryAction.equipFromSlot(player, bestSlot);
            ItemStack equipped = hotbar >= 0 ? inventory.main.get(hotbar) : ItemStack.EMPTY;
            BotLog.action(player, "equip_mining_channel_tool",
                    "slot", hotbar, "tool", equipped.getItem(), "tier", bestTier);
            return new Selection(true, hotbar, equipped, policyScore);
        }
        return new Selection(false, currentSlot, bestStack, policyScore);
    }

    static int channelMinimumTier(int requiredTier) {
        return Math.max(ToolTier.STONE, requiredTier);
    }

    /** Exact tool requested when the channel policy has no usable candidate. */
    public static Item requiredMiningChannelTool(BlockState state) {
        int tier = channelMinimumTier(ToolTier.requiredPickaxeTier(state.getBlock()));
        if (tier >= ToolTier.DIAMOND) {
            return Items.DIAMOND_PICKAXE;
        }
        if (tier >= ToolTier.IRON) {
            return Items.IRON_PICKAXE;
        }
        return Items.STONE_PICKAXE;
    }

    static int channelMaximumTier(int minimumTier, boolean targetOre) {
        return !targetOre && minimumTier == ToolTier.STONE
                ? ToolTier.STONE : ToolTier.NETHERITE;
    }

    private static boolean isBetterCandidate(float candidateScore,
                                             int candidateHandSafety,
                                             float bestScore,
                                             int bestHandSafety) {
        return candidateScore > bestScore + 0.001F
                || Math.abs(candidateScore - bestScore) <= 0.001F
                && candidateHandSafety > bestHandSafety;
    }

    /**
     * A sword or axe that mines at bare-hand speed provides no benefit but still loses durability
     * for every broken block. On a speed tie prefer an executable empty hand, then a non-damageable
     * stack, then another damageable tool; a melee weapon is the last legal soft-block hand.
     */
    private static int softBlockHandSafety(ItemStack stack, BlockState state) {
        if (state.isToolRequired()) {
            return 0;
        }
        if (stack.isEmpty()) {
            return 3;
        }
        if (!stack.isDamageable()) {
            return 2;
        }
        return stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem ? 0 : 1;
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
