package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.DigNav;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.util.math.BlockPos;

public final class MoveTask extends AbstractTask {
    private static final int DIG_NO_PROGRESS_LIMIT = 200; // 挖掘式直行 10s 没破块/没迈步 → 放弃
    private static final int DIG_MAX_ELAPSED = 2400;
    private static final double ARRIVE_SQUARED = 4.0D;     // 挖掘式到 goal 2 格内即视为到达

    private final BlockPos goal;
    private final double startDistance;
    private BlockPos resolvedGoal;
    private boolean digging;                               // 纯寻路走不通 → 降级为挖掘式直行
    private final BlockMiner miner = new BlockMiner();
    private int digLastProgressTick;

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
        return (digging ? "Digging to " : "Walking to ") + compact(goal);
    }

    @Override
    public double progress() {
        if (startDistance <= 0.1D || state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, elapsed / Math.max(20.0D, startDistance * 12.0D));
    }

    @Override
    public boolean isWaiting() {
        // 挖掘式直行时 bot 站着挖、位置基本不变 → 视为 waiting,让 StuckWatcher 不误判(由本任务看门狗兜底)。
        return digging;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        startWalkOrDig(bot);
    }

    @Override
    protected void onResume(AIPlayerEntity bot) {
        digging = false;
        startWalkOrDig(bot);
    }

    private void startWalkOrDig(AIPlayerEntity bot) {
        ActionResult result = bot.getActionPack().startPathTo(goal);
        if (result.isFailed()) {
            beginDigging(bot, result.reason()); // 纯寻路一开始就失败(被墙/SEARCH_LIMIT)→ 直接挖掘式直行
            return;
        }
        resolvedGoal = bot.getActionPack().activePathGoal();
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.getBlockPos().getSquaredDistance(currentGoal()) <= 2.25D
                || (digging && bot.getBlockPos().getSquaredDistance(goal) <= ARRIVE_SQUARED)) {
            miner.cancel(bot);
            complete();
            return;
        }
        if (digging) {
            digTick(bot);
            return;
        }
        // 纯寻路模式:寻路执行器空闲(到不了)→ 降级挖掘式直行,而不是直接 did_not_reach 卡死。
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 5) {
            beginDigging(bot, "path_idle");
            return;
        }
        if (elapsed > 1200) {
            fail("move_timeout");
        }
    }

    private void beginDigging(AIPlayerEntity bot, String reason) {
        digging = true;
        digLastProgressTick = elapsed;
        bot.getActionPack().stopAll(); // 清掉寻路状态,改由 DigNav 驱动
        BotLog.action(bot, "move_dig_fallback", "goal", compact(goal), "reason", reason);
    }

    private void digTick(AIPlayerEntity bot) {
        if (elapsed > DIG_MAX_ELAPSED) {
            miner.cancel(bot);
            fail("move_dig_timeout");
            return;
        }
        if (elapsed - digLastProgressTick > DIG_NO_PROGRESS_LIMIT) {
            miner.cancel(bot);
            fail("move_dig_no_progress"); // 挖不动/受阻(如四面岩浆)→ 交还,交生存层/大脑
            return;
        }
        if (DigNav.digStep(bot, miner, goal)) {
            digLastProgressTick = elapsed;
        }
    }

    private BlockPos currentGoal() {
        return resolvedGoal == null ? goal : resolvedGoal;
    }

    private static String compact(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
