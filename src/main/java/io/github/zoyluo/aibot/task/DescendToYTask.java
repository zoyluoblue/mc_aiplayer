package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.BlockMiner;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.log.BotLog;
import net.minecraft.block.BlockState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * DESCEND_TO_Y(挖深层矿重构 P1):连续挖竖井**下到指定 Y 层**,然后交还 —— 专为"挖钻石/红石等深层矿前先到矿层"设计。
 *
 * 病根(实测):OreDigTask 把"下挖"和"找矿"耦合在一个 scan 限频循环里,从 Y=48 想挖到钻石层(Y<16)时
 * 反复"锁定斜下方够不到的矿→水平掘隧道→dist 卡死→no_progress",卡死 11 分钟。本任务把"下到矿层"独立出来:
 * 用共享 {@link BlockMiner} 连续挖脚下(不受任何限频),bot 无被动重力则主动 descendInto 下沉,
 * 遇岩浆硬停、遇水穿过(与 DigDownTask 一致),一路掘到 targetY。到层后由 GoalExecutor 接 MINE_ORE(此时矿在水平面近处)。
 *
 * 自包含状态机(G1,不自 assign),全程主线程(G2)。
 */
public final class DescendToYTask extends AbstractTask {
    private static final int MAX_ELAPSED = 4800;       // 4 分钟硬超时(最深挖 ~130 层足够)
    private static final int NO_PROGRESS_LIMIT = 200;  // 10s 没破任何块即失败(挖不动/卡住)
    private static final int MIN_Y = -60;
    private static final int MAX_LATERAL = 16; // 绕岩浆最多横移 16 格(防大岩浆湖里无限漂移)
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final int targetY;
    private final BlockMiner miner = new BlockMiner();
    private int lastProgressTick;
    private int lateralDetours; // 已横移绕岩浆/卡点的次数

    public DescendToYTask(int targetY) {
        this.targetY = targetY;
    }

    @Override
    public String name() {
        return "descend";
    }

    @Override
    public String describe() {
        return "Descend to Y=" + targetY;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : 0.5D;
    }

    @Override
    public boolean isWaiting() {
        // 下挖期 bot 站着挖,位置基本不变;视为 waiting 让 StuckWatcher 不误判,由本任务 NO_PROGRESS_LIMIT 看门狗兜底。
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        lastProgressTick = 0;
        lateralDetours = 0;
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        miner.cancel(bot);
        bot.getActionPack().stopAll();
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > MAX_ELAPSED) {
            fail("descend_timeout at_y=" + bot.getBlockPos().getY());
            return;
        }
        // 到达目标层 → 完成,交还(让 GoalExecutor 接 MINE_ORE 在本层找矿)。
        if (bot.getBlockPos().getY() <= targetY) {
            miner.cancel(bot);
            complete();
            return;
        }
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos below = feet.down();
        if (below.getY() <= MIN_Y) {
            fail("descend_reached_min_y");
            return;
        }
        // 卡住太久(挖不动/被挡)→ 先横移到相邻列绕过,四面不通才失败。
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = elapsed;
                return;
            }
            miner.cancel(bot);
            fail("descend_no_progress at_y=" + feet.getY());
            return;
        }

        // 推进当前挖掘(连续,不受任何限频)。
        BlockMiner.Status status = miner.tick(bot);
        if (status == BlockMiner.Status.MINING) {
            return;
        }
        if (status == BlockMiner.Status.DONE) {
            lastProgressTick = elapsed;
        }

        BlockState belowState = world.getBlockState(below);
        if (belowState.isAir()) {
            bot.getActionPack().descendInto(below); // bot 无被动重力,主动下沉穿过空气
            return;
        }
        // 岩浆(脚下或其正下方)致命 → 横移到相邻无岩浆列继续下挖(绕过岩浆湖);四面皆岩浆/绕太多次才硬停。
        if (belowState.getFluidState().isIn(FluidTags.LAVA)
                || world.getBlockState(below.down()).getFluidState().isIn(FluidTags.LAVA)) {
            if (lateralDetours < MAX_LATERAL && tryLateralDetour(bot, world, feet)) {
                lastProgressTick = elapsed;
                return;
            }
            miner.cancel(bot);
            fail("descend_blocked_lava at_y=" + below.getY());
            return;
        }
        if (belowState.getFluidState().isIn(FluidTags.WATER)) {
            bot.getActionPack().descendInto(below);
            return;
        }
        // 脚下实心 → 挖穿往下。
        miner.begin(bot, below);
        miner.tick(bot);
        if (lastProgressTick == 0) {
            lastProgressTick = elapsed; // 起步宽限
            BotLog.action(bot, "descend_started", "target_y", targetY, "from_y", feet.getY());
        }
    }

    // 竖井被岩浆(或卡点)挡住时,横移一格到"无岩浆、可下挖"的相邻列,绕过去继续下挖。
    // 挖开通往侧列的块(挨岩浆的块不挖,防溃浆淹没);通了就 teleport 平移过去(bot 无被动重力,与 descendInto 一致)。
    // 四面都不可行 → 返回 false,由调用方判失败(交规避层"困死撤离"兜底)。
    private boolean tryLateralDetour(AIPlayerEntity bot, ServerWorld world, BlockPos feet) {
        for (Direction dir : HORIZONTAL) {
            BlockPos side = feet.offset(dir);
            if (isLava(world, side) || isLava(world, side.up()) || isLava(world, side.down())) {
                continue; // 别往岩浆方向横移
            }
            BlockPos solid = firstSolid(world, side, side.up());
            if (solid != null) {
                if (adjacentLava(world, solid)) {
                    continue; // 要挖的块挨着岩浆,挖了会溃浆淹没,换方向
                }
                if (miner.target() == null || !miner.target().equals(solid)) {
                    miner.begin(bot, solid);
                }
                miner.tick(bot);
                return true; // 正在挖通往侧列的路(本 tick 算进展)
            }
            // 侧列已通(脚位+头位皆空)→ teleport 平移过去,下个 tick 在新列继续下挖。
            miner.cancel(bot);
            bot.teleport(world, side.getX() + 0.5D, side.getY(), side.getZ() + 0.5D,
                    java.util.Collections.emptySet(), bot.getYaw(), bot.getPitch(), true);
            lateralDetours++;
            BotLog.action(bot, "descend_lava_detour", "dir", dir.asString(), "at_y", side.getY());
            return true;
        }
        return false;
    }

    private static boolean isLava(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).getFluidState().isIn(FluidTags.LAVA);
    }

    private static boolean adjacentLava(ServerWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (isLava(world, pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private static BlockPos firstSolid(ServerWorld world, BlockPos a, BlockPos b) {
        if (!world.getBlockState(a).isAir()) {
            return a.toImmutable();
        }
        if (!world.getBlockState(b).isAir()) {
            return b.toImmutable();
        }
        return null;
    }
}
