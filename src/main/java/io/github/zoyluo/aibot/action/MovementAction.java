package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.math.Vec3d;

public final class MovementAction {
    private MovementAction() {
    }

    public static ActionResult setForward(AIPlayerEntity player, float value) {
        player.getActionPack().setForward(value);
        return ActionResult.SUCCESS;
    }

    public static ActionResult setStrafing(AIPlayerEntity player, float value) {
        player.getActionPack().setStrafing(value);
        return ActionResult.SUCCESS;
    }

    public static ActionResult setSneaking(AIPlayerEntity player, boolean sneaking) {
        player.getActionPack().setSneaking(sneaking);
        return ActionResult.SUCCESS;
    }

    public static ActionResult setSprinting(AIPlayerEntity player, boolean sprinting) {
        player.getActionPack().setSprinting(sprinting);
        return ActionResult.SUCCESS;
    }

    public static ActionResult startJump(AIPlayerEntity player) {
        player.getActionPack().setJumping(true);
        return ActionResult.SUCCESS;
    }

    public static ActionResult jumpOnce(AIPlayerEntity player) {
        player.getActionPack().jumpOnce();
        return ActionResult.SUCCESS;
    }

    public static ActionResult startWalkTo(AIPlayerEntity player, Vec3d target) {
        return player.getActionPack().startWalkTo(target);
    }

    public static ActionResult stopAll(AIPlayerEntity player) {
        player.getActionPack().stopAll();
        return ActionResult.SUCCESS;
    }
}
