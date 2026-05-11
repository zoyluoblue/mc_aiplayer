package com.aiplayer.util;

import com.aiplayer.entity.AiPlayerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class SurvivalUtils {
    private SurvivalUtils() {
    }

    public static Optional<BlockPos> findNearestBlock(AiPlayerEntity aiPlayer, Predicate<BlockState> predicate, int horizontalRadius, int verticalRadius) {
        BlockPos center = aiPlayer.blockPosition();
        Level level = aiPlayer.level();
        return BlockPos.betweenClosedStream(
                center.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
                center.offset(horizontalRadius, verticalRadius, horizontalRadius)
            )
            .map(BlockPos::immutable)
            .filter(pos -> predicate.test(level.getBlockState(pos)))
            .min(Comparator.comparingDouble(pos -> pos.distSqr(center)));
    }

    public static Optional<BlockPos> findNearestBlock(AiPlayerEntity aiPlayer, BiPredicate<BlockPos, BlockState> predicate, int horizontalRadius, int verticalRadius) {
        BlockPos center = aiPlayer.blockPosition();
        Level level = aiPlayer.level();
        return BlockPos.betweenClosedStream(
                center.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
                center.offset(horizontalRadius, verticalRadius, horizontalRadius)
            )
            .map(BlockPos::immutable)
            .filter(pos -> predicate.test(pos, level.getBlockState(pos)))
            .min(Comparator.comparingDouble(pos -> pos.distSqr(center)));
    }

    public static boolean moveNear(AiPlayerEntity aiPlayer, BlockPos pos, double range) {
        if (aiPlayer.blockPosition().closerThan(pos, range)) {
            aiPlayer.getNavigation().stop();
            return true;
        }
        aiPlayer.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
        return false;
    }

    public static boolean breakBlock(AiPlayerEntity aiPlayer, BlockPos pos) {
        BlockState state = aiPlayer.level().getBlockState(pos);
        if (state.isAir() || state.getBlock() == Blocks.BEDROCK) {
            return false;
        }
        Item drop = getSurvivalDrop(state);
        aiPlayer.lookAtWorkTarget(pos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        boolean destroyed = aiPlayer.level().destroyBlock(pos, false);
        if (destroyed && drop != Items.AIR) {
            aiPlayer.addItem(drop, getDropCount(state));
        }
        return destroyed;
    }

    public static boolean placeBlock(AiPlayerEntity aiPlayer, BlockPos pos, Block block) {
        if (block == Blocks.AIR) {
            return true;
        }
        Item item = getPlacementItem(block);
        if (item == Items.AIR || !aiPlayer.consumeItem(item, 1)) {
            return false;
        }
        aiPlayer.lookAtWorkTarget(pos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        aiPlayer.level().setBlock(pos, block.defaultBlockState(), 3);
        return true;
    }

    public static Item getPlacementItem(Block block) {
        Item item = block.asItem();
        return item == null ? Items.AIR : item;
    }

    public static Item getSurvivalDrop(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.STONE || block == Blocks.DEEPSLATE) {
            return Items.COBBLESTONE;
        }
        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            return Items.COAL;
        }
        if (block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE) {
            return Items.RAW_IRON;
        }
        if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE) {
            return Items.RAW_COPPER;
        }
        if (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE) {
            return Items.RAW_GOLD;
        }
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            return Items.DIAMOND;
        }
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return Items.REDSTONE;
        }
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return Items.LAPIS_LAZULI;
        }
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return Items.EMERALD;
        }
        Item item = block.asItem();
        return item == null ? Items.AIR : item;
    }

    public static int getDropCount(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return 4;
        }
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return 4;
        }
        return 1;
    }

    public static boolean isLog(Block block) {
        return block == Blocks.OAK_LOG || block == Blocks.SPRUCE_LOG || block == Blocks.BIRCH_LOG ||
            block == Blocks.JUNGLE_LOG || block == Blocks.ACACIA_LOG || block == Blocks.DARK_OAK_LOG ||
            block == Blocks.MANGROVE_LOG || block == Blocks.CHERRY_LOG;
    }

    public static boolean isStone(Block block) {
        return block == Blocks.STONE || block == Blocks.DEEPSLATE || block == Blocks.COBBLESTONE;
    }

    public static boolean isOre(Block block) {
        return block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE ||
            block == Blocks.IRON_ORE || block == Blocks.DEEPSLATE_IRON_ORE ||
            block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE ||
            block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE ||
            block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE ||
            block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE ||
            block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE ||
            block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE;
    }

    public static boolean requiresPickaxe(Block block) {
        return isStone(block) || isOre(block);
    }
}
