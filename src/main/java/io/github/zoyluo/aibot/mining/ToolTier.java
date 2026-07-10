package io.github.zoyluo.aibot.mining;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

import java.util.Set;

public final class ToolTier {
    public static final int NONE = 0;
    public static final int WOOD = 1;
    public static final int STONE = 2;
    public static final int IRON = 3;
    public static final int DIAMOND = 4;
    public static final int NETHERITE = 5;

    private ToolTier() {
    }

    public static int requiredPickaxeTier(Set<Block> blocks) {
        int required = NONE;
        if (blocks == null) {
            return required;
        }
        for (Block block : blocks) {
            required = Math.max(required, requiredPickaxeTier(block));
        }
        return required;
    }

    public static int requiredPickaxeTier(Block block) {
        // 数据驱动优先(模组兼容):vanilla 的工具分级 tag 是权威来源,模组矿物按惯例给自己打这些 tag
        //(暮色森林等模组的新矿无需改代码即正确分级)。tag 未命中再走下面的手写表兜底(保持历史行为)。
        var state = block.getDefaultState();
        if (state.isIn(net.minecraft.registry.tag.BlockTags.NEEDS_DIAMOND_TOOL)) {
            return DIAMOND;
        }
        if (state.isIn(net.minecraft.registry.tag.BlockTags.NEEDS_IRON_TOOL)) {
            return IRON;
        }
        if (state.isIn(net.minecraft.registry.tag.BlockTags.NEEDS_STONE_TOOL)) {
            return STONE;
        }
        // 黑曜石/哭泣的黑曜石/远古残骸:需钻石镐(否则破坏无掉落)。
        if (block == Blocks.OBSIDIAN || block == Blocks.CRYING_OBSIDIAN || block == Blocks.ANCIENT_DEBRIS) {
            return DIAMOND;
        }
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE
                || block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE
                || block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE
                || block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return IRON;
        }
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE
                || block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE
                || block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return STONE;
        }
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE
                || block == Blocks.STONE || block == Blocks.DEEPSLATE
                || block == Blocks.COBBLESTONE || block == Blocks.COBBLED_DEEPSLATE) {
            return WOOD;
        }
        return OreScan.isOreBlock(block) ? STONE : NONE;
    }

    public static Item requiredPickaxeItem(Set<Block> blocks) {
        return pickaxeItem(requiredPickaxeTier(blocks));
    }

    public static Item requiredPickaxeItem(Block block) {
        return pickaxeItem(requiredPickaxeTier(block));
    }

    public static String requiredPickaxeItemId(Set<Block> blocks) {
        return Registries.ITEM.getId(requiredPickaxeItem(blocks)).toString();
    }

    public static String requiredPickaxeItemId(Block block) {
        return Registries.ITEM.getId(requiredPickaxeItem(block)).toString();
    }

    public static int bestPickaxeTier(AIPlayerEntity bot) {
        int best = tierOf(bot.getMainHandStack());
        for (ItemStack stack : bot.getInventory().main) {
            best = Math.max(best, tierOf(stack));
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            best = Math.max(best, tierOf(stack));
        }
        return best;
    }

    public static boolean hasRequiredPickaxe(AIPlayerEntity bot, Set<Block> blocks) {
        return bestPickaxeTier(bot) >= requiredPickaxeTier(blocks);
    }

    public static boolean canHarvestWithInventory(AIPlayerEntity bot, BlockState state) {
        if (!state.isToolRequired()) {
            return true;
        }
        for (ItemStack stack : bot.getInventory().main) {
            if (!stack.isEmpty() && stack.isSuitableFor(state) && !nearlyBroken(stack)) {
                return true;
            }
        }
        for (ItemStack stack : bot.getInventory().offHand) {
            if (!stack.isEmpty() && stack.isSuitableFor(state) && !nearlyBroken(stack)) {
                return true;
            }
        }
        return false;
    }

    // 耐久闸:剩 1 耐久的镐视作没有——挖矿半路镐碎了才发现是最差时机(空手对深层矿干瞪眼),
    // 提前一格触发 need_better_tool,GoalExecutor 既有 replan 链就地取材补新镐。与 ToolSelector
    // 的"近耗尽评分 0.001"同一阈值,两层口径一致。
    private static boolean nearlyBroken(ItemStack stack) {
        return stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1;
    }

    private static Item pickaxeItem(int tier) {
        if (tier >= DIAMOND) {
            return Items.DIAMOND_PICKAXE; // 黑曜石级:漏这档时 need_better_tool 误报 iron,规划补错镐
        }
        if (tier >= IRON) {
            return Items.IRON_PICKAXE;
        }
        if (tier >= STONE) {
            return Items.STONE_PICKAXE;
        }
        if (tier >= WOOD) {
            return Items.WOODEN_PICKAXE;
        }
        return Items.AIR;
    }

    private static int tierOf(ItemStack stack) {
        if (stack.isEmpty() || nearlyBroken(stack)) {
            return NONE;
        }
        Item item = stack.getItem();
        if (item == Items.NETHERITE_PICKAXE) {
            return NETHERITE;
        }
        if (item == Items.DIAMOND_PICKAXE) {
            return DIAMOND;
        }
        if (item == Items.IRON_PICKAXE) {
            return IRON;
        }
        if (item == Items.STONE_PICKAXE) {
            return STONE;
        }
        if (item == Items.WOODEN_PICKAXE || item == Items.GOLDEN_PICKAXE) {
            return WOOD;
        }
        return NONE;
    }
}
