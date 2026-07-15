package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * 巡逻:绕出发点四个罗盘方向角点走圈,走满 laps 圈收工(守家/看场子/直播来回走位)。
 * 角点按地表高度落位(高差 >8 视为进洞/上山,退回中心高度);单腿 15s 走不到就跳下一个角,
 * 绝不为一个死角卡死全局。巡逻中被怪打断交给 DangerWatcher/主人指令顶掉,不自带战斗。
 */
public final class PatrolTask extends AbstractTask {
    private static final int MAX_ELAPSED = 6000;  // ~5min 硬顶
    private static final int LEG_BUDGET = 300;    // 单腿 15s
    private static final double ARRIVE_DIST = 2.5D;

    private final int radius;
    private final int lapsWanted;

    private BlockPos[] corners;
    private int idx;
    private int lapsDone;
    private int legStart;

    public PatrolTask(int radius, int laps) {
        this.radius = Math.max(4, Math.min(16, radius));
        this.lapsWanted = Math.max(1, Math.min(10, laps));
    }

    @Override
    public String name() {
        return "patrol";
    }

    @Override
    public String describe() {
        return "patrol r=" + radius + " lap=" + lapsDone + "/" + lapsWanted;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) lapsDone / lapsWanted);
    }

    @Override
    public boolean isWaiting() {
        return true; // 走圈由 path executor 驱动,停滞由单腿预算自兜
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos center = bot.getBlockPos();
        corners = new BlockPos[]{
                cornerAt(world, center, radius, 0),
                cornerAt(world, center, 0, radius),
                cornerAt(world, center, -radius, 0),
                cornerAt(world, center, 0, -radius)};
        idx = 0;
        lapsDone = 0;
        legStart = 0;
    }

    /** 角点取地表可站高度;与中心高差 >8(山崖/矿洞上方)退回中心高度,让寻路自己解。 */
    private static BlockPos cornerAt(ServerWorld world, BlockPos center, int dx, int dz) {
        int x = center.getX() + dx;
        int z = center.getZ() + dz;
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - center.getY()) > 8) {
            y = center.getY();
        }
        return new BlockPos(x, y, z);
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (lapsDone >= lapsWanted) {
            bot.getActionPack().stopMovement();
            complete();
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            if (lapsDone > 0) {
                complete();
            } else {
                fail("patrol_timeout");
            }
            return;
        }
        BlockPos target = corners[idx];
        BlockPos feet = bot.getBlockPos();
        double dx = target.getX() - feet.getX();
        double dz = target.getZ() - feet.getZ();
        boolean arrived = dx * dx + dz * dz <= ARRIVE_DIST * ARRIVE_DIST;
        if (arrived || elapsed - legStart > LEG_BUDGET) {
            idx = (idx + 1) % corners.length;
            if (idx == 0) {
                lapsDone++;
            }
            legStart = elapsed;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(target);
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        bot.getActionPack().stopAll(); // stopMovement 清不掉 pathExecutor,被抢占时会被旧路径拖着走
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
