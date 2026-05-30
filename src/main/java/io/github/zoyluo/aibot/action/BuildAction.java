package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class BuildAction {
    private BuildAction() {
    }

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.ERROR, player, "place_failed", "reason", "empty_hand");
            return ActionResult.failed("empty_hand");
        }
        var item = stack.getItem();

        LookAction.lookAtBlock(player, against, face);
        Vec3d hitPos = Vec3d.ofCenter(against).add(
                face.getOffsetX() * 0.5D,
                face.getOffsetY() * 0.5D,
                face.getOffsetZ() * 0.5D);
        BlockHitResult hit = new BlockHitResult(hitPos, face, against, false);
        net.minecraft.util.ActionResult result = player.interactionManager.interactBlock(
                player,
                player.getServerWorld(),
                stack,
                hand,
                hit);
        if (result.isAccepted()) {
            player.swingHand(hand);
            player.updateLastActionTime();
            AStarPathfinder.invalidateCache("block_place");
            BotLog.action(player, "place", "pos", LogFields.pos(against.offset(face)), "face", face, "item", item);
            return ActionResult.SUCCESS;
        }
        BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.ERROR, player, "place_failed", "pos", LogFields.pos(against.offset(face)), "reason", result.getClass().getSimpleName());
        return ActionResult.failed("interact_block_" + result.getClass().getSimpleName());
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        BlockPos below = pos.down();
        if (!player.getServerWorld().getBlockState(below).isAir()) {
            ActionResult result = placeBlock(player, below, Direction.UP, Hand.MAIN_HAND);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = result;
        }

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.offset(direction.getOpposite());
            if (!player.getServerWorld().getBlockState(against).isAir()) {
                ActionResult result = placeBlock(player, against, direction, Hand.MAIN_HAND);
                if (result.isSuccess()) {
                    return result;
                }
                lastFailure = result;
            }
        }
        ActionResult fallback = directPlaceFallback(player, pos, Hand.MAIN_HAND);
        if (fallback.isSuccess()) {
            return fallback;
        }
        return lastFailure;
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player, BlockPos pos, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.failed("not_block_item");
        }
        var item = stack.getItem();
        if (!player.getServerWorld().getBlockState(pos).isAir()) {
            return ActionResult.failed("target_not_air");
        }
        player.getServerWorld().setBlockState(pos, blockItem.getBlock().getDefaultState(), 3);
        if (!player.getAbilities().creativeMode) {
            stack.decrement(1);
        }
        player.swingHand(hand);
        player.updateLastActionTime();
        AStarPathfinder.invalidateCache("block_place_fallback");
        BotLog.action(player, "place_fallback", "pos", LogFields.pos(pos), "item", item);
        return ActionResult.SUCCESS;
    }
}
