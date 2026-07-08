package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import io.github.zoyluo.aibot.log.LogFields;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import net.minecraft.block.BlockState;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public final class MiningController {
    private static final int MAX_TICKS = 600;

    private final BlockPos pos;
    private final Direction face;
    private boolean started;
    private BlockState targetState;
    private int elapsed;

    public MiningController(BlockPos pos, Direction face) {
        this.pos = pos;
        this.face = face;
    }

    public ActionResult tick(ActionPack pack) {
        AIPlayerEntity player = pack.player();
        var world = player.getServerWorld();
        BlockState state = world.getBlockState(pos);

        // BUGFIX: блок стал air — abort серверный трекинг, return SUCCESS
        if (state.isAir() || (targetState != null && !state.equals(targetState))) {
            if (started) {
                abortInternal(player);
            } else {
                // Принудительно сбрасываем серверный трекинг
                player.interactionManager.processBlockBreakingAction(
                        pos, PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                        face, World.MAX_Y, -1);
            }
            world.setBlockBreakingInfo(player.getId(), pos, -1);
            AStarPathfinder.invalidateCache("block_break_or_change");
            started = false;
            return ActionResult.SUCCESS;
        }

        LookAction.lookAtBlock(player, pos, face);
        double reach = player.getAttributeValue(EntityAttributes.BLOCK_INTERACTION_RANGE);
        if (player.getEyePos().distanceTo(pos.toCenterPos()) > reach + 0.5D) {
            abortInternal(player);
            return ActionResult.failed("out_of_reach");
        }

        // START_DESTROY_BLOCK каждый тик (как ванильный клиент)
        if (!started) {
            ToolSelector.equipBestTool(player, state);
            BotLog.action(player, "mine_start", "pos", LogFields.pos(pos), "face", face);
            targetState = state;
            started = true;
        }

        player.interactionManager.processBlockBreakingAction(
                pos, PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                face, World.MAX_Y, -1);
        state.onBlockBreakStart(world, pos, player);
        world.setBlockBreakingInfo(player.getId(), pos,
                Math.min(9, (int) (world.getBlockState(pos).calcBlockBreakingDelta(player, world, pos) * 10.0F)));
        player.swingHand(Hand.MAIN_HAND);
        player.updateLastActionTime();

        elapsed++;
        if (elapsed > MAX_TICKS) {
            abortInternal(player);
            return ActionResult.failed("timeout");
        }
        return ActionResult.IN_PROGRESS;
    }

    public void abort(AIPlayerEntity player) {
        abortInternal(player);
        player.getServerWorld().setBlockBreakingInfo(player.getId(), pos, -1);
    }

    private void abortInternal(AIPlayerEntity player) {
        if (started) {
            player.interactionManager.processBlockBreakingAction(
                    pos,
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                    face,
                    World.MAX_Y,
                    -1);
        }
        started = false;
        targetState = null;
        elapsed = 0;
    }
}
