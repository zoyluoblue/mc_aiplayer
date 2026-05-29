package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.util.math.BlockPos;

public final class MoveTask extends AbstractTask {
    private final BlockPos goal;
    private final double startDistance;

    public MoveTask(BlockPos start, BlockPos goal) {
        this.goal = goal.toImmutable();
        this.startDistance = Math.sqrt(start.getSquaredDistance(goal));
    }

    public MoveTask(AIPlayerEntity bot, BlockPos goal) {
        this(bot.getBlockPos(), goal);
    }

    @Override
    public String name() {
        return "move";
    }

    @Override
    public String describe() {
        return "Walking to " + compact(goal);
    }

    @Override
    public double progress() {
        if (startDistance <= 0.1D || state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, elapsed / Math.max(20.0D, startDistance * 12.0D));
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            fail(result.reason());
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.getBlockPos().getSquaredDistance(goal) <= 2.25D) {
            complete();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 5) {
            fail("did_not_reach");
            return;
        }
        if (elapsed > 1200) {
            fail("move_timeout");
        }
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            fail(result.reason());
        }
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
