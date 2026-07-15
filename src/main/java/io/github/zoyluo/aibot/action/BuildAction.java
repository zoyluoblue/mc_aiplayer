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
        var world = player.getServerWorld();
        if (!world.getBlockState(pos).isReplaceable()) {
            return ActionResult.failed("target_not_replaceable");
        }
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        Direction[] placementFaces = {
                Direction.UP, Direction.NORTH, Direction.SOUTH,
                Direction.EAST, Direction.WEST, Direction.DOWN};
        for (Direction direction : placementFaces) {
            BlockPos against = pos.offset(direction.getOpposite());
            var anchor = world.getBlockState(against);
            // Fluids and plants are non-air but replaceable. Clicking them makes ItemPlacementContext
            // replace the anchor itself, silently placing one layer below/aside the requested target.
            if (!anchor.isReplaceable() && !anchor.getCollisionShape(world, against).isEmpty()) {
                ActionResult result = placeBlock(player, against, direction, Hand.MAIN_HAND);
                if (result.isSuccess() && !world.getBlockState(pos).isReplaceable()) {
                    return result;
                }
                lastFailure = result.isSuccess()
                        ? ActionResult.failed("accepted_without_target_block")
                        : result;
            }
        }
        ActionResult fallback = directPlaceFallback(player, pos, Hand.MAIN_HAND);
        if (fallback.isSuccess() && !world.getBlockState(pos).isReplaceable()) {
            return fallback;
        }
        return fallback.isSuccess() ? ActionResult.failed("fallback_without_target_block") : lastFailure;
    }

    /**
     * Server-authoritative exact placement for tower/scaffold mechanics. Unlike normal placement
     * it never derives the destination from the current view ray, so a jumping fake player cannot
     * accidentally put the block in front of itself. Callers must still verify the target became
     * a solid support before treating the action as progress.
     */
    public static ActionResult placeBlockAtExactly(AIPlayerEntity player, BlockPos pos) {
        ActionResult result = directPlaceFallback(player, pos, Hand.MAIN_HAND);
        if (result.isSuccess() && !player.getServerWorld().getBlockState(pos).isReplaceable()) {
            return result;
        }
        return result.isSuccess() ? ActionResult.failed("exact_place_without_target_block") : result;
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player, BlockPos pos, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return ActionResult.failed("not_block_item");
        }
        var item = stack.getItem();
        var existing = player.getServerWorld().getBlockState(pos);
        // 可替换格(流体源/草丛等)放行:封岩浆就是对浆格直接放块,原版玩家合法操作。
        if (!existing.isAir() && !existing.isReplaceable()) {
            return ActionResult.failed("target_not_air");
        }
        if (!player.getServerWorld().setBlockState(pos, blockItem.getBlock().getDefaultState(), 3)) {
            return ActionResult.failed("set_block_state_rejected");
        }
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
