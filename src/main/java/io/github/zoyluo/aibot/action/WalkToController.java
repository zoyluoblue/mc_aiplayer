package io.github.zoyluo.aibot.action;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WalkToController {
    private static final double ARRIVAL_THRESHOLD = 0.6D;
    private static final int MAX_TICKS = 200;
    private static final int MAX_STUCK_TICKS = 40;

    private final Vec3d target;
    private Vec3d lastPos;
    private int stuckTicks;
    private int elapsed;

    public WalkToController(Vec3d target) {
        this.target = target;
    }

    public ActionResult tick(ActionPack pack) {
        elapsed++;
        if (elapsed > MAX_TICKS) {
            pack.stopMovement();
            return ActionResult.failed("timeout");
        }

        var player = pack.player();
        Vec3d current = player.getPos();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance <= ARRIVAL_THRESHOLD) {
            pack.stopMovement();
            return ActionResult.SUCCESS;
        }

        LookAction.lookHorizontallyAt(player, target);
        pack.setForward(1.0F);
        pack.setJumping(shouldJump(player.getBlockPos(), Direction.fromRotation(player.getYaw()), player.getServerWorld()));

        if (lastPos != null && current.distanceTo(lastPos) < 0.04D) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = current;

        if (stuckTicks > MAX_STUCK_TICKS) {
            pack.stopMovement();
            return ActionResult.failed("stuck");
        }
        return ActionResult.IN_PROGRESS;
    }

    private static boolean shouldJump(BlockPos playerPos, Direction direction, net.minecraft.server.world.ServerWorld world) {
        BlockPos front = playerPos.offset(direction);
        BlockState frontState = world.getBlockState(front);
        if (frontState.isAir()) {
            return false;
        }

        BlockState aboveFront = world.getBlockState(front.up());
        BlockState abovePlayer = world.getBlockState(playerPos.up());
        return aboveFront.isAir() && abovePlayer.isAir() && frontState.getCollisionShape(world, front).getMax(Direction.Axis.Y) <= 1.0D;
    }
}
