package io.github.zoyluo.aibot.mode;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;

/** Validated adjacent fake-client step; distinct from privileged long-distance teleportation. */
public final class FakePlayerMotion {
    private FakePlayerMotion() {
    }

    public static boolean stepTo(AIPlayerEntity bot, BlockPos target, String reason) {
        BlockPos from = bot.getBlockPos();
        int dx = Math.abs(target.getX() - from.getX());
        int dy = Math.abs(target.getY() - from.getY());
        int dz = Math.abs(target.getZ() - from.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        if (dx > 1 || dy > 1 || dz > 1 || changedAxes == 0 || changedAxes > 2) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", reason, "from", from, "to", target);
            return false;
        }
        var world = bot.getServerWorld();
        if (!world.getBlockState(target).getCollisionShape(world, target).isEmpty()
                || !world.getBlockState(target.up()).getCollisionShape(world, target.up()).isEmpty()) {
            BotLog.action(bot, "fake_player_step_rejected", "reason", "blocked:" + reason,
                    "from", from, "to", target);
            return false;
        }
        bot.getActionPack().stopMovement();
        bot.teleport(world, target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
                Collections.emptySet(), bot.getYaw(), bot.getPitch(), false);
        BotLog.action(bot, "fake_player_step", "reason", reason, "from", from, "to", target);
        return true;
    }
}
