package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 建一个 2×2 无限水源(灌溉 / 取水用)。在 center 所在的地面挖出 1 深的 2×2 坑,
 * 在对角两格各倒 1 桶水;另外两格各与 2 个源正交相邻 → 经几 tick 水流自动变成源 → 4 格全是源。
 * 这样的 2×2 全源水池是"无限水源":舀走任意一格,其余 3 格会把它回填成源;同时灌溉周围 4 格内的耕地。
 * 需要背包 ≥2 个 WATER_BUCKET(倒完变 2 个空 BUCKET)。
 */
public final class IrrigateTask extends AbstractTask {
    private enum Phase {GOTO, DIG, PLACE, SETTLE, DONE}

    private static final int SETTLE_TICKS = 20; // 放水后等水流扩散、两空格转为源
    private final BlockPos center;
    private final List<BlockPos> cells = new ArrayList<>(); // 2×2 的 4 格(同一 y 层)
    private Phase phase = Phase.GOTO;
    private int settle;
    private String note = "";

    public IrrigateTask(BlockPos center) {
        this.center = center.toImmutable();
    }

    @Override
    public String name() {
        return "irrigate";
    }

    @Override
    public String describe() {
        return "irrigate center=" + center.getX() + "," + center.getY() + "," + center.getZ()
                + " phase=" + phase + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        return switch (phase) {
            case GOTO -> 0.1D;
            case DIG -> 0.4D;
            case PLACE -> 0.7D;
            case SETTLE -> 0.9D;
            case DONE -> 1.0D;
        };
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        cells.clear();
        cells.add(center);
        cells.add(center.offset(Direction.EAST));
        cells.add(center.offset(Direction.SOUTH));
        cells.add(center.offset(Direction.EAST).offset(Direction.SOUTH));
        phase = Phase.GOTO;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("irrigate_timeout phase=" + phase);
            return;
        }
        switch (phase) {
            case GOTO -> goToCenter(bot);
            case DIG -> dig(bot);
            case PLACE -> place(bot);
            case SETTLE -> settle();
            case DONE -> complete();
        }
    }

    private void goToCenter(AIPlayerEntity bot) {
        if (bot.getEyePos().distanceTo(center.toCenterPos()) <= 4.5D) {
            bot.getActionPack().stopAll();
            phase = Phase.DIG;
            return;
        }
        BlockPos stand = adjacentStand(bot, center);
        if (stand == null) {
            note = "unreachable"; // setBlockState 不依赖精确站位,够不到也就地施工
            phase = Phase.DIG;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(stand);
        }
    }

    private void dig(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        for (BlockPos cell : cells) {
            // 坑底必须实心(否则水往下漏);坑内清空成空气以容水。四周由现有地面充当挡水墙。
            BlockState below = world.getBlockState(cell.down());
            if (below.isAir() || !world.getFluidState(cell.down()).isEmpty()) {
                world.setBlockState(cell.down(), Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
            }
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        phase = Phase.PLACE;
    }

    private void place(AIPlayerEntity bot) {
        // 对角两格放水(cells[0] 与 cells[3]);另两格(cells[1]/[2])各邻 2 源,SETTLE 后自动成源。
        ActionResult a = FarmAction.placeWater(bot, cells.get(0));
        if (a.isFailed()) {
            fail("place_water_failed:" + a.reason());
            return;
        }
        ActionResult b = FarmAction.placeWater(bot, cells.get(3));
        if (b.isFailed()) {
            fail("place_water_failed:" + b.reason());
            return;
        }
        phase = Phase.SETTLE;
    }

    private void settle() {
        settle++;
        if (settle >= SETTLE_TICKS) {
            phase = Phase.DONE;
        }
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(d).up();
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
