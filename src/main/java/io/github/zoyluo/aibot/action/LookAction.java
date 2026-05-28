package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class LookAction {
    private LookAction() {
    }

    public static ActionResult setYawPitch(AIPlayerEntity player, float yaw, float pitch) {
        float clampedPitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(clampedPitch);
        return ActionResult.SUCCESS;
    }

    public static ActionResult lookAt(AIPlayerEntity player, Vec3d target) {
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target);
        player.setHeadYaw(player.getYaw());
        player.setBodyYaw(player.getYaw());
        return ActionResult.SUCCESS;
    }

    public static ActionResult lookAtBlock(AIPlayerEntity player, BlockPos pos, Direction face) {
        Vec3d target = Vec3d.ofCenter(pos).add(
                face.getOffsetX() * 0.5D,
                face.getOffsetY() * 0.5D,
                face.getOffsetZ() * 0.5D);
        return lookAt(player, target);
    }

    static ActionResult lookHorizontallyAt(AIPlayerEntity player, Vec3d target) {
        Vec3d current = player.getPos();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        float yaw = MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D));
        return setYawPitch(player, yaw, player.getPitch());
    }
}
