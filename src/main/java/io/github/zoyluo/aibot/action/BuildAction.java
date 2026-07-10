package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class BuildAction {
    private BuildAction() {
    }

    public static ActionResult placeBlock(AIPlayerEntity player, BlockPos against, Direction face, Hand hand) {
        double reach = player.getBlockInteractionRange();
        if (player.getEyePos().squaredDistanceTo(against.toCenterPos()) > reach * reach
                || !player.canInteractWithBlockAt(against, 0.0D)) {
            return ActionResult.failed("support_out_of_reach_or_sight");
        }
        ItemStack stack = player.getStackInHand(hand);
        if (stack.isEmpty()) {
            BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.ERROR, player, "place_failed", "reason", "empty_hand");
            return ActionResult.failed("empty_hand");
        }
        var item = stack.getItem();

        LookAction.lookAtBlock(player, against, face);
        var lookedAt = player.raycast(reach, 1.0F, false);
        if (!(lookedAt instanceof BlockHitResult hit)
                || hit.getBlockPos() == null
                || !hit.getBlockPos().equals(against)
                || hit.getSide() != face) {
            return ActionResult.failed("support_face_not_visible");
        }
        BlockPos destination = against.offset(face);
        var before = player.getServerWorld().getBlockState(destination);
        net.minecraft.util.ActionResult result = player.interactionManager.interactBlock(
                player,
                player.getServerWorld(),
                stack,
                hand,
                hit);
        var after = player.getServerWorld().getBlockState(destination);
        if (result.isAccepted() && !after.equals(before)) {
            player.swingHand(hand);
            player.updateLastActionTime();
            AStarPathfinder.invalidateCache("block_place");
            BotLog.action(player, "place", "pos", LogFields.pos(destination), "face", face, "item", item);
            return ActionResult.SUCCESS;
        }
        String reason = result.isAccepted() ? "accepted_without_block_change" : result.getClass().getSimpleName();
        BotLog.warn(io.github.zoyluo.aibot.log.LogCategory.ERROR, player, "place_failed",
                "pos", LogFields.pos(destination), "reason", reason);
        return ActionResult.failed("interact_block_" + reason);
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        ActionResult lastFailure = ActionResult.failed("no_adjacent_block");
        BlockPos below = pos.down();
        if (ObservableWorldQuery.canObserveBlock(player, below)
                && !player.getServerWorld().getBlockState(below).isAir()) {
            ActionResult result = placeBlock(player, below, Direction.UP, Hand.MAIN_HAND);
            if (result.isSuccess()) {
                return result;
            }
            lastFailure = result;
        }

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.offset(direction.getOpposite());
            if (ObservableWorldQuery.canObserveBlock(player, against)
                    && !player.getServerWorld().getBlockState(against).isAir()) {
                ActionResult result = placeBlock(player, against, direction, Hand.MAIN_HAND);
                if (result.isSuccess()) {
                    return result;
                }
                lastFailure = result;
            }
        }
        if (AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL) {
            return lastFailure;
        }
        ActionResult fallback = directPlaceFallback(player, pos, Hand.MAIN_HAND);
        if (fallback.isSuccess()) {
            return fallback;
        }
        return lastFailure;
    }

    private static ActionResult directPlaceFallback(AIPlayerEntity player, BlockPos pos, Hand hand) {
        double reach = player.getBlockInteractionRange();
        if (player.getEyePos().squaredDistanceTo(pos.toCenterPos()) > reach * reach) {
            return ActionResult.failed("target_out_of_reach");
        }
        if (!ObservableWorldQuery.canObserveCell(player, pos)) {
            return ActionResult.failed("target_not_visible");
        }
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
        var placementState = blockItem.getBlock().getDefaultState();
        if (!placementState.canPlaceAt(player.getServerWorld(), pos)
                || !player.getServerWorld().canPlace(placementState, pos, ShapeContext.of(player))) {
            return ActionResult.failed("target_blocked_or_unsupported");
        }
        if (!player.getServerWorld().setBlockState(pos, placementState, 3)) {
            return ActionResult.failed("world_mutation_rejected");
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
