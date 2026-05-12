package com.aiplayer.execution.interaction;

import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class BlockPlacementRules {
    private BlockPlacementRules() {
    }

    public static boolean canReplace(BlockState state) {
        return state.isAir()
            || state.liquid()
            || state.getBlock() == Blocks.SHORT_GRASS
            || state.getBlock() == Blocks.TALL_GRASS;
    }

    public static String validate(AiPlayerEntity aiPlayer, InteractionTarget target, Block block) {
        if (target == null) {
            return "missing_interaction_target";
        }
        if (block == null || block == Blocks.AIR) {
            return "invalid_block";
        }
        if (target.actionType() != InteractionActionType.PLACE_BLOCK) {
            return "wrong_action:" + target.actionType();
        }
        BlockPos pos = target.targetBlock();
        BlockState current = aiPlayer.level().getBlockState(pos);
        if (!canReplace(current)) {
            return "target_not_replaceable:" + blockId(current.getBlock());
        }
        Item item = SurvivalUtils.getPlacementItem(block);
        if (item == Items.AIR || !aiPlayer.hasItem(item, 1)) {
            return "missing_item:" + itemId(item);
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(pos));
        if (distanceSq > target.reachRange() * target.reachRange()) {
            return "out_of_reach:" + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq));
        }
        if (!hasPlacementSupport(aiPlayer.level(), pos)) {
            return "no_adjacent_support";
        }
        return null;
    }

    public static boolean hasPlacementSupport(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (!neighbor.isAir() && !neighbor.liquid() && neighbor.isSolid()) {
                return true;
            }
        }
        return false;
    }

    private static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String itemId(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }
}
