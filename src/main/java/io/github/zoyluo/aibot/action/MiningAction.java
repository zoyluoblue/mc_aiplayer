package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public final class MiningAction {
    private MiningAction() {
    }

    public static ActionResult startMining(AIPlayerEntity player, BlockPos pos, Direction face) {
        return player.getActionPack().startMining(pos, face);
    }

    public static ActionResult stopMining(AIPlayerEntity player) {
        player.getActionPack().stopMining();
        return ActionResult.SUCCESS;
    }

    public static ActionResult mineOnceInstant(AIPlayerEntity player, BlockPos pos, Direction face) {
        player.interactionManager.processBlockBreakingAction(
                pos,
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                face,
                World.MAX_Y,
                -1);
        // BUGFIX: survival mode requires STOP to trigger block break
        player.interactionManager.processBlockBreakingAction(
                pos,
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
                face,
                World.MAX_Y,
                -1);
        return ActionResult.SUCCESS;
    }
}
