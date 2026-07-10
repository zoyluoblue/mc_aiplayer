package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.FarmAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.mining.OreProspector;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * 村庄/野外收菜:大范围扫描成熟作物(小麦/胡萝卜/马铃薯/甜菜)、走过去收割、捡起掉落,直到收够 target 个产出。
 * 与 FarmTask(在固定区域开垦自种)不同——本任务找的是世界里已存在的成熟作物田(典型即村庄农田),不种、只收。
 * best-effort:扫不到成熟作物/久无进展时,收到 ≥1 个就完成、一个没收到才失败。
 * 行为边界:只破坏并捡取作物本身(村民会自行补种,vanilla 不掉声望);不开村民箱子、不抢交易物——那不属于"收菜"。
 */
public final class RaidCropsTask extends AbstractTask {
    private enum Phase {SCAN, GOTO, HARVEST, DONE}

    private static final int SCAN_RADIUS = 64;
    private static final double REACH = 4.5D;
    private static final int NO_PROGRESS_LIMIT = 1200;
    // 收割掉落要捡的产出(各作物的产出 + 副产种子)。
    private static final Set<Item> CROP_DROPS = Set.of(
            Items.WHEAT, Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BEETROOT_SEEDS);

    private final int target;
    private int harvested;
    private int lastProgressTick;
    private BlockPos current;
    private Phase phase = Phase.SCAN;
    private String note = "";

    public RaidCropsTask(int target) {
        this.target = Math.max(1, target);
    }

    @Override
    public String name() {
        return "raid_crops";
    }

    @Override
    public String describe() {
        return "raid_crops " + harvested + "/" + target + " phase=" + phase
                + (current == null ? "" : " at=" + current.getX() + "," + current.getY() + "," + current.getZ())
                + (note.isBlank() ? "" : " note=" + note);
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) harvested / target);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        harvested = 0;
        lastProgressTick = 0;
        phase = Phase.SCAN;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (harvested >= target) {
            complete();
            return;
        }
        if (elapsed - lastProgressTick > NO_PROGRESS_LIMIT) {
            finishOrFail("raid_no_progress");
            return;
        }
        switch (phase) {
            case SCAN -> scan(bot);
            case GOTO -> goTo(bot);
            case HARVEST -> harvest(bot);
            case DONE -> complete();
        }
    }

    private void scan(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos found = OreProspector.nearest(bot, SCAN_RADIUS, RaidCropsTask::isMatureCrop);
        if (found == null) {
            finishOrFail("no_mature_crops");
            return;
        }
        current = found;
        phase = Phase.GOTO;
    }

    private void goTo(AIPlayerEntity bot) {
        if (current == null || !isMatureCrop(bot.getServerWorld().getBlockState(current))) {
            phase = Phase.SCAN; // 目标没了(被吃/已收),重扫
            return;
        }
        if (bot.getEyePos().distanceTo(current.toCenterPos()) <= REACH) {
            bot.getActionPack().stopAll();
            phase = Phase.HARVEST;
            return;
        }
        BlockPos stand = adjacentStand(bot, current);
        if (stand == null) {
            note = "unreachable " + current;
            current = null;
            phase = Phase.SCAN;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult path = bot.getActionPack().startPathTo(stand);
            if (path.isFailed()) {
                current = null;
                phase = Phase.SCAN;
            }
        }
    }

    private void harvest(AIPlayerEntity bot) {
        ActionResult result = FarmAction.harvest(bot, current);
        if (result.isSuccess()) {
            // 收割掉落是地上 ItemEntity,reach 距离收割够不到自动拾取 → 强制捡(同 FarmTask/dig_down 修法)。
            HarvestCore.forcePickupNearbyAnyOf(bot, CROP_DROPS, 5.0D, 4.0D);
            harvested++;
            lastProgressTick = elapsed;
        } else {
            note = result.reason();
        }
        current = null;
        phase = Phase.SCAN;
    }

    private static boolean isMatureCrop(net.minecraft.block.BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMature(state);
    }

    private static BlockPos adjacentStand(AIPlayerEntity bot, BlockPos target) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(d);
            if (Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void finishOrFail(String reason) {
        if (harvested > 0) {
            complete();
        } else {
            fail(reason);
        }
    }
}
