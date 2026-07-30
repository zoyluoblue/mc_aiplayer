package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.craft.RecipeRegistry;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

public final class MaterialPalette {
    public static final Map<String, List<Item>> GROUPS = Map.of(
            "planks", RecipeRegistry.PLANKS,
            "logs", RecipeRegistry.LOGS,
            "stone_like", List.of(Items.COBBLESTONE, Items.STONE, Items.STONE_BRICKS, Items.COBBLED_DEEPSLATE, Items.DEEPSLATE_BRICKS),
            "dirt_like", List.of(Items.DIRT, Items.GRASS_BLOCK, Items.COARSE_DIRT),
            "glass", List.of(Items.GLASS, Items.WHITE_STAINED_GLASS, Items.LIGHT_GRAY_STAINED_GLASS));
    private static final List<Item> SACRIFICIAL_BLOCKS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRASS_BLOCK,
            Items.PODZOL,
            Items.MYCELIUM,
            Items.MUD,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.STONE,
            Items.DEEPSLATE,
            Items.TUFF,
            Items.ANDESITE,
            Items.DIORITE,
            Items.GRANITE,
            Items.CALCITE,
            Items.BLACKSTONE,
            Items.NETHERRACK);

    /**
     * Ordered full-height, non-falling supports that are safe to stand on after a pillar jump.
     *
     * <p>This is deliberately narrower than {@link #SACRIFICIAL_BLOCKS}: a block such as mud can
     * seal a fluid cell, but its lowered collision surface cannot provide the exact one-block rise
     * assumed by pathfinding. Dirt-like blocks are spent first; stone and the bootstrap cobble
     * outputs are retained until every cheaper stable support is exhausted.</p>
     */
    private static final List<Item> PATH_SUPPORT_BLOCKS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRASS_BLOCK,
            Items.PODZOL,
            Items.MYCELIUM,
            Items.NETHERRACK,
            Items.TUFF,
            Items.ANDESITE,
            Items.DIORITE,
            Items.GRANITE,
            Items.CALCITE,
            Items.BLACKSTONE,
            Items.DEEPSLATE,
            Items.STONE,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE);
    private static final List<Item> SHELTER_EASY_BLOCKS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.PODZOL,
            Items.NETHERRACK);
    private static final List<Item> SHELTER_TOOL_BLOCKS = List.of(
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE);

    private MaterialPalette() {
    }

    public static OptionalInt pickSlot(AIPlayerEntity bot, String palette) {
        if (palette == null || palette.isBlank()) {
            return OptionalInt.empty();
        }
        List<Item> items = GROUPS.get(palette);
        if (items == null || items.isEmpty()) {
            return OptionalInt.empty();
        }
        for (Item item : items) {
            OptionalInt slot = InventoryAction.findItem(bot, item);
            if (slot.isPresent()) {
                return slot;
            }
        }
        return OptionalInt.empty();
    }

    public static OptionalInt pickAnyBlockSlot(AIPlayerEntity bot) {
        for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
            if (bot.getInventory().main.get(slot).getItem() instanceof BlockItem) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Selects a low-value solid block for an irreversible safety action such as sealing fluid.
     * Workstations, containers, ores, logs, planks and mission outputs are deliberately excluded:
     * surviving one ingress must not destroy the only crafting table or the fuel/tool material
     * needed to finish the expedition.
     */
    public static OptionalInt pickSacrificialBlockSlot(AIPlayerEntity bot) {
        return pickSacrificialBlockSlot(bot, 0);
    }

    /**
     * Selects a sacrificial block without spending the final protected mining-stone reserve.
     * Dirt and non-reserve stone variants remain preferred and may be spent freely; cobblestone,
     * cobbled deepslate and blackstone are eligible only when one placement leaves the requested
     * reserve intact.
     */
    public static OptionalInt pickSacrificialBlockSlot(AIPlayerEntity bot,
                                                       int protectedStoneLikeReserve) {
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int protectedStoneLike = protectedMiningStoneLikeCount(bot);
        for (Item item : SACRIFICIAL_BLOCKS) {
            if (isProtectedMiningStoneLike(item) && protectedStoneLike <= reserve) {
                continue;
            }
            OptionalInt slot = InventoryAction.findItem(bot, item);
            if (slot.isPresent()) {
                return slot;
            }
        }
        return OptionalInt.empty();
    }

    /** Registry-stable allowlist used when validating persisted safety-wall ownership. */
    public static boolean isSacrificialBlock(Block block) {
        if (block == null) {
            return false;
        }
        for (Item item : SACRIFICIAL_BLOCKS) {
            if (item instanceof BlockItem blockItem && blockItem.getBlock() == block) {
                return true;
            }
        }
        return false;
    }

    /** Selects a stable full-height block for persistent path infrastructure such as pillars. */
    public static OptionalInt pickPathSupportBlockSlot(AIPlayerEntity bot) {
        return pickPathSupportBlockSlot(bot, 0);
    }

    /** Selects path support without spending the final protected mining-tool stone reserve. */
    public static OptionalInt pickPathSupportBlockSlot(AIPlayerEntity bot,
                                                       int protectedStoneLikeReserve) {
        int reserve = Math.max(0, protectedStoneLikeReserve);
        int protectedStoneLike = protectedMiningStoneLikeCount(bot);
        for (Item item : PATH_SUPPORT_BLOCKS) {
            if (isProtectedMiningStoneLike(item) && protectedStoneLike <= reserve) {
                continue;
            }
            OptionalInt slot = InventoryAction.findItem(bot, item);
            if (slot.isPresent()) {
                return slot;
            }
        }
        return OptionalInt.empty();
    }

    private static boolean isProtectedMiningStoneLike(Item item) {
        return item == Items.COBBLESTONE
                || item == Items.COBBLED_DEEPSLATE
                || item == Items.BLACKSTONE;
    }

    private static int protectedMiningStoneLikeCount(AIPlayerEntity bot) {
        return InventoryAction.countItem(bot, Items.COBBLESTONE)
                + InventoryAction.countItem(bot, Items.COBBLED_DEEPSLATE)
                + InventoryAction.countItem(bot, Items.BLACKSTONE);
    }

    /**
     * Selects a low-value shelter wall that the same bot can physically reopen later. Block-entity
     * workstations and valuable mission outputs are excluded; tool-required stone is eligible only
     * while a healthy suitable tool is already present.
     */
    public static OptionalInt pickShelterBlockSlot(AIPlayerEntity bot) {
        for (Item item : SHELTER_EASY_BLOCKS) {
            OptionalInt slot = InventoryAction.findItem(bot, item);
            if (slot.isPresent()) {
                return slot;
            }
        }
        for (Item item : SHELTER_TOOL_BLOCKS) {
            if (hasHealthySuitableTool(bot, item)) {
                OptionalInt slot = InventoryAction.findItem(bot, item);
                if (slot.isPresent()) {
                    return slot;
                }
            }
        }
        return OptionalInt.empty();
    }

    public static int countShelterBlocks(AIPlayerEntity bot) {
        int total = 0;
        for (Item item : SHELTER_EASY_BLOCKS) {
            total += InventoryAction.countItem(bot, item);
        }
        for (Item item : SHELTER_TOOL_BLOCKS) {
            if (hasHealthySuitableTool(bot, item)) {
                total += InventoryAction.countItem(bot, item);
            }
        }
        return total;
    }

    private static boolean hasHealthySuitableTool(AIPlayerEntity bot, Item blockItem) {
        if (!(blockItem instanceof BlockItem item)) {
            return false;
        }
        BlockState state = item.getBlock().getDefaultState();
        for (var stack : bot.getInventory().main) {
            if (!stack.isEmpty() && stack.isSuitableFor(state)
                    && (!stack.isDamageable() || stack.getDamage() < stack.getMaxDamage() - 1)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isKnown(String palette) {
        return palette != null && GROUPS.containsKey(palette);
    }

    public static boolean matchesBlock(BlockState state, String palette) {
        List<Item> items = GROUPS.get(palette);
        if (items == null) {
            return false;
        }
        for (Item item : items) {
            if (item instanceof BlockItem blockItem && state.isOf(blockItem.getBlock())) {
                return true;
            }
        }
        return false;
    }
}
