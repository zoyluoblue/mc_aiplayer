package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;

/**
 * 推平场地:把 bot 脚面高度往上 3 格内、半径 radius 的方块全部挖掉(盖房前平地/拆土包)。
 * 只削"高出地面"的部分,不往下挖坑;流体和基岩级方块(硬度<0)跳过;挖崩的掉落物顺手吸走。
 * 单块交给 BlockMiner(自动选镐、同块不重发防进度清零),挖不动的块拉黑跳过不卡全局。
 */
public final class FlattenAreaTask extends AbstractTask {
    private static final int MAX_ELAPSED = 2400;      // ~120s
    private static final int APPROACH_BUDGET = 100;   // 对同一块走位 5s 走不进 → 拉黑
    private static final int CLEAR_HEIGHT = 3;
    private static final double MINE_RANGE = 4.0D;

    private final int radius;

    private final BlockMiner miner = new BlockMiner();
    private final Set<BlockPos> blacklist = new HashSet<>();
    private BlockPos current;
    private int approachStart;
    private int floorY;
    private int cleared;
    private int totalEstimate;

    public FlattenAreaTask(int radius) {
        this.radius = Math.max(1, Math.min(4, radius));
    }

    @Override
    public String name() {
        return "flatten_area";
    }

    @Override
    public String describe() {
        return "flatten_area r=" + radius + " cleared=" + cleared;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return totalEstimate <= 0 ? 0.0D : Math.min(0.95D, cleared / (double) totalEstimate);
    }

    @Override
    public boolean isWaiting() {
        return true; // 站桩挖掘正常
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        floorY = bot.getBlockPos().getY();
        cleared = 0;
        current = null;
        approachStart = 0;
        totalEstimate = countTargets(bot);
        if (totalEstimate == 0) {
            complete(); // 本来就平,直接收工
        }
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            finishOrFail("flatten_timeout");
            return;
        }
        HarvestCore.forcePickupNearbyAnyOf(bot, null, 4, 2);

        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return;
        }
        if (status == BlockMiner.Status.DONE) {
            cleared++;
            current = null;
        } else if (status == BlockMiner.Status.FAILED) {
            if (current != null) {
                blacklist.add(current);
            }
            current = null;
        }

        if (current == null) {
            current = nextTarget(bot);
            if (current == null) {
                bot.getActionPack().stopMovement();
                complete();
                return;
            }
            approachStart = elapsed;
        }
        if (bot.getEyePos().distanceTo(current.toCenterPos()) > MINE_RANGE) {
            if (elapsed - approachStart > APPROACH_BUDGET) {
                blacklist.add(current);
                current = null;
                return;
            }
            if (bot.getActionPack().isPathExecutorIdle()) {
                bot.getActionPack().startPathTo(current);
            }
            return;
        }
        bot.getActionPack().stopMovement();
        miner.begin(bot, current);
    }

    /** 待挖格:半径内、脚面高度起往上 CLEAR_HEIGHT 格、实心、非流体、可破坏、未拉黑;近的优先。 */
    private BlockPos nextTarget(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(
                new BlockPos(feet.getX() - radius, floorY, feet.getZ() - radius),
                new BlockPos(feet.getX() + radius, floorY + CLEAR_HEIGHT - 1, feet.getZ() + radius))) {
            if (blacklist.contains(pos) || !isMineable(world, pos)) {
                continue;
            }
            double dist = pos.getSquaredDistance(feet);
            if (dist < bestDist) {
                bestDist = dist;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private static boolean isMineable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.getHardness(world, pos) >= 0.0F; // 基岩等 -1 不可破坏
    }

    private int countTargets(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        int count = 0;
        for (BlockPos pos : BlockPos.iterate(
                new BlockPos(feet.getX() - radius, floorY, feet.getZ() - radius),
                new BlockPos(feet.getX() + radius, floorY + CLEAR_HEIGHT - 1, feet.getZ() + radius))) {
            if (isMineable(world, pos)) {
                count++;
            }
        }
        return count;
    }

    private void finishOrFail(String reason) {
        if (cleared > 0) {
            complete();
        } else {
            fail(reason);
        }
    }

    @Override
    protected void onPause(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll(); // stopMovement 清不掉 pathExecutor,被抢占时会被旧路径拖着走
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }
}
