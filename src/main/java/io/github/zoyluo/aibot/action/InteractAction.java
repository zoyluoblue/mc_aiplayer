package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

public final class InteractAction {
    private InteractAction() {
    }

    public static ActionResult attackEntity(AIPlayerEntity player, Entity target) {
        Vec3d targetCenter = target.getPos().add(0.0D, target.getHeight() * 0.5D, 0.0D);
        LookAction.lookAt(player, targetCenter);
        player.attack(target);
        player.swingHand(Hand.MAIN_HAND);
        player.resetLastAttackedTicks();
        player.updateLastActionTime();
        BotLog.action(player, "attack", "target_type", target.getType(), "target_id", target.getId());
        return ActionResult.SUCCESS;
    }

    public static ActionResult useItemOnEntity(AIPlayerEntity player, Entity target, Hand hand) {
        net.minecraft.util.ActionResult result = target.interact(player, hand);
        return result.isAccepted() ? ActionResult.SUCCESS : ActionResult.failed("interact_entity_" + result.getClass().getSimpleName());
    }

    /** 右键方块本体(开门/拉杆/按钮/撒骨粉):hit 打在目标格被点面的面心(与 BuildAction.placeBlock 语义一致),原版 onUse 语义(双开门同步/音效都对)。 */
    public static ActionResult useItemOnBlock(AIPlayerEntity player, net.minecraft.util.math.BlockPos pos, net.minecraft.util.math.Direction face, Hand hand) {
        LookAction.lookAtBlock(player, pos, face);
        Vec3d hitPos = Vec3d.ofCenter(pos).add(
                face.getOffsetX() * 0.5D, face.getOffsetY() * 0.5D, face.getOffsetZ() * 0.5D);
        net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(hitPos, face, pos, false);
        net.minecraft.util.ActionResult result = player.interactionManager.interactBlock(
                player,
                player.getServerWorld(),
                player.getStackInHand(hand),
                hand,
                hit);
        if (result.isAccepted()) {
            player.swingHand(hand);
            player.updateLastActionTime();
            return ActionResult.SUCCESS;
        }
        return ActionResult.failed("interact_block_" + result.getClass().getSimpleName());
    }

    public static ActionResult useItemInAir(AIPlayerEntity player, Hand hand) {
        net.minecraft.util.ActionResult result = player.interactionManager.interactItem(
                player,
                player.getServerWorld(),
                player.getStackInHand(hand),
                hand);
        return result.isAccepted() ? ActionResult.SUCCESS : ActionResult.failed("interact_item_" + result.getClass().getSimpleName());
    }
}
