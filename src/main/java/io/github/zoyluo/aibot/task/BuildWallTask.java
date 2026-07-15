package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * 砌一道墙:从 bot 面前那格起,沿指定方向砌 length 列 × height 高的直墙(挡怪/围地/圈羊)。
 * 逐列推进:bot 走在墙线侧面一格,列内从下往上放;该列被地形占满就跳过;列预算超时跳过,
 * 不为一列卡死全局。选料复用 BridgeTask.fillerSlot(木板/圆石/泥土,避沙砾)。
 */
public final class BuildWallTask extends AbstractTask {
    private static final int MAX_ELAPSED = 2400;      // ~120s
    private static final int COLUMN_BUDGET = 200;     // 单列 10s 干不完(走不到/放不上)→ 跳过
    private static final int PLACE_FAIL_LIMIT = 12;
    private static final double PLACE_RANGE = 4.0D;

    private final Direction direction;
    private final int length;
    private final int height;

    private List<BlockPos> columns; // 每列最底格(bot 起始脚高)
    private int col;
    private int colStart;
    private int placed;
    private int placeRetry;
    private int placeDelayTicks;

    public BuildWallTask(Direction direction, int length, int height) {
        this.direction = direction;
        this.length = Math.max(1, Math.min(16, length));
        this.height = Math.max(1, Math.min(3, height));
    }

    @Override
    public String name() {
        return "build_wall";
    }

    @Override
    public String describe() {
        return "build_wall " + direction.asString() + " col=" + col + "/" + length + " placed=" + placed;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, col / (double) length);
    }

    @Override
    public boolean isWaiting() {
        return true; // 施工站桩正常,卡死由列预算自兜
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        BlockPos feet = bot.getBlockPos();
        columns = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            columns.add(feet.offset(direction, 1 + i));
        }
        col = 0;
        colStart = 0;
        placed = 0;
        placeRetry = 0;
        placeDelayTicks = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (col >= length) {
            bot.getActionPack().stopMovement();
            finishOrFail("wall_done");
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            finishOrFail("wall_timeout");
            return;
        }
        if (placeDelayTicks > 0) {
            placeDelayTicks--;
            return;
        }
        if (elapsed - colStart > COLUMN_BUDGET) {
            nextColumn(); // 这列耗太久(走不到/被挡),跳过保全局
            return;
        }

        ServerWorld world = bot.getServerWorld();
        BlockPos base = columns.get(col);

        // 找该列最低的待填格;全实心 → 列完工。
        BlockPos cell = null;
        for (int h = 0; h < height; h++) {
            BlockPos candidate = base.up(h);
            if (world.getBlockState(candidate).isReplaceable()) {
                cell = candidate;
                break;
            }
        }
        if (cell == null) {
            nextColumn();
            return;
        }
        // bot 自己站在墙线的任何一层(含 height=3 的顶层)都会把块放进自己身体 → 先侧移再砌。
        for (int h = 0; h < height; h++) {
            if (bot.getBlockPos().equals(base.up(h))) {
                stepAside(bot, base);
                return;
            }
        }

        if (bot.getEyePos().distanceTo(cell.toCenterPos()) <= PLACE_RANGE) {
            bot.getActionPack().stopMovement();
            OptionalInt slot = BridgeTask.fillerSlot(bot);
            if (slot.isEmpty()) {
                finishOrFail("wall_no_blocks: 背包没有可砌方块(木板/圆石/泥土)");
                return;
            }
            InventoryAction.equipFromSlot(bot, slot.getAsInt());
            LookAction.lookAtBlock(bot, cell, Direction.UP);
            ActionResult result = BuildAction.placeBlockAt(bot, cell);
            if (result.isSuccess()) {
                placed++;
                placeRetry = 0;
                placeDelayTicks = 2;
                return;
            }
            placeRetry++;
            if (placeRetry > PLACE_FAIL_LIMIT) {
                nextColumn(); // 这格死活放不上(视线/依托),跳过该列
            }
            return;
        }
        // 走到列旁:优先墙线两侧,同高或差一格能站就去。
        BlockPos stand = standCellNear(world, base);
        if (stand == null) {
            nextColumn();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
    }

    private void nextColumn() {
        col++;
        colStart = elapsed;
        placeRetry = 0;
    }

    private void stepAside(AIPlayerEntity bot, BlockPos base) {
        BlockPos aside = standCellNear(bot.getServerWorld(), base);
        if (aside == null) {
            nextColumn();
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(aside);
        }
    }

    /** 列旁可站格:左右两侧同高/上一格/下一格,先近后远。 */
    private BlockPos standCellNear(ServerWorld world, BlockPos base) {
        Direction side = direction.rotateYClockwise();
        for (Direction d : new Direction[]{side, side.getOpposite()}) {
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos candidate = base.offset(d).up(dy);
                if (Standability.isStandable(world, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void finishOrFail(String reason) {
        if (placed > 0 || "wall_done".equals(reason)) {
            complete();
        } else {
            fail(reason);
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
