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
        // 走路朝向限速插值(≤45°/tick):瞬转 snap 是"机器人感"直接来源;45°/tick 任何方向 ≤4 tick 到位,
        // 对行走轨迹影响可忽略。战斗/挖掘瞄准仍走 lookAt/setYawPitch 瞬时路径,命中零回归。
        return setYawPitch(player, stepAngle(player.getYaw(), yaw, 45.0F), player.getPitch());
    }

    /**
     * 平滑注视(空闲看主人/说话看人用):yaw ≤45°/tick、pitch ≤30°/tick 插值逼近目标,
     * 支持微偏移(IdleLook 的"不死盯"随机视线)。只给活人观感调用点用,不进战斗/挖掘。
     */
    public static void lookAtSmooth(AIPlayerEntity player, Vec3d target, float yawOffset, float pitchOffset) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = MathHelper.wrapDegrees((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D) + yawOffset);
        float targetPitch = MathHelper.clamp((float) -Math.toDegrees(Math.atan2(dy, Math.max(1.0E-3D, horizontal))) + pitchOffset, -85.0F, 85.0F);
        setYawPitch(player,
                stepAngle(player.getYaw(), targetYaw, 45.0F),
                stepAngle(player.getPitch(), targetPitch, 30.0F));
    }

    private static float stepAngle(float current, float target, float maxStep) {
        float delta = MathHelper.wrapDegrees(target - current);
        if (Math.abs(delta) <= maxStep) {
            return MathHelper.wrapDegrees(target);
        }
        return MathHelper.wrapDegrees(current + Math.copySign(maxStep, delta));
    }
}
