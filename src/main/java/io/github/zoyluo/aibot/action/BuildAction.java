package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
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
            return ActionResult.failed("empty_hand");
        }

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
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed("interact_block_" + result.getClass().getSimpleName());
    }

    public static ActionResult placeBlockAt(AIPlayerEntity player, BlockPos pos) {
        BlockPos below = pos.down();
        if (!player.getServerWorld().getBlockState(below).isAir()) {
            return placeBlock(player, below, Direction.UP, Hand.MAIN_HAND);
        }

        for (Direction direction : Direction.values()) {
            BlockPos against = pos.offset(direction.getOpposite());
            if (!player.getServerWorld().getBlockState(against).isAir()) {
                return placeBlock(player, against, direction, Hand.MAIN_HAND);
            }
        }
        return ActionResult.failed("no_adjacent_block");
    }
}
