package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;

public class PathfindAction extends BaseAction {
    private BlockPos targetPos;
    private int ticksRunning;
    private static final int MAX_TICKS = 600;

    public PathfindAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);
        
        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;
        
        aiPlayer.setSprinting(true);
        aiPlayer.getNavigation().moveTo(x, y, z, SurvivalUtils.TASK_RUN_SPEED);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (aiPlayer.blockPosition().closerThan(targetPos, 2.0)) {
            aiPlayer.getNavigation().stop();
            aiPlayer.setSprinting(false);
            result = ActionResult.success("Reached target position");
            return;
        }
        
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Pathfinding timeout");
            return;
        }
        
        if (aiPlayer.getNavigation().isDone() && !aiPlayer.blockPosition().closerThan(targetPos, 2.0)) {
            aiPlayer.setSprinting(true);
            aiPlayer.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), SurvivalUtils.TASK_RUN_SPEED);
        }
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
        aiPlayer.setSprinting(false);
    }

    @Override
    public String getDescription() {
        return "Pathfind to " + targetPos;
    }
}
