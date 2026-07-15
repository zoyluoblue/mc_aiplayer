package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 种树苗:在身边草地/泥土上把背包里的 *_sapling 种下去(间距 ≥3 防长成连体树)。
 * 砍树后补种是"环保主播"人设的标准动作,树苗来源=砍树时树叶自然掉落。
 * best-effort:种下 ≥1 棵就算完成。
 */
public final class PlantSaplingTask extends AbstractTask {
    private static final int MAX_ELAPSED = 900;
    private static final int SCAN_RADIUS = 8;
    private static final double PLANT_RANGE = 4.0D;

    private final int target;
    private int planted;
    private BlockPos ground; // 当前选中的落点(种在它上面)

    public PlantSaplingTask(int target) {
        this.target = Math.max(1, Math.min(16, target));
    }

    @Override
    public String name() {
        return "plant_sapling";
    }

    @Override
    public String describe() {
        return "plant_sapling " + planted + "/" + target;
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : Math.min(0.95D, (double) planted / target);
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        planted = 0;
        ground = null;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (planted >= target) {
            bot.getActionPack().stopMovement();
            complete();
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            finishOrFail("plant_timeout");
            return;
        }
        int slot = saplingSlot(bot);
        if (slot < 0) {
            finishOrFail("no_sapling: 背包里没有树苗(砍树时打掉树叶会掉)");
            return;
        }
        ServerWorld world = bot.getServerWorld();
        if (ground == null || !isPlantSpot(world, ground)) {
            ground = findSpot(bot);
            if (ground == null) {
                finishOrFail("no_plant_spot: 附近没有空旷的草地/泥土");
                return;
            }
        }
        if (bot.getEyePos().distanceTo(ground.up().toCenterPos()) <= PLANT_RANGE) {
            bot.getActionPack().stopMovement();
            InventoryAction.equipFromSlot(bot, slot);
            ActionResult result = BuildAction.placeBlockAt(bot, ground.up());
            if (result.isSuccess()) {
                planted++;
            }
            ground = null; // 无论成败换下一个点(失败点多半有遮挡)
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            ActionResult path = bot.getActionPack().startPathTo(ground.up());
            if (path.isFailed()) {
                ground = null; // 走不过去,换点
            }
        }
    }

    /** 背包里第一格 *_sapling / *_propagule(红树)。 */
    private static int saplingSlot(AIPlayerEntity bot) {
        var main = bot.getInventory().main;
        for (int i = 0; i < main.size(); i++) {
            ItemStack stack = main.get(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) {
                continue;
            }
            String path = Registries.ITEM.getId(stack.getItem()).getPath();
            if (path.endsWith("_sapling") || path.endsWith("_propagule")) {
                return i;
            }
        }
        return -1;
    }

    /** 可种落点:草/泥土,上两格净空,且 5×5 内没有别的树苗/原木(间距,防连体树/树底再种)。 */
    private static boolean isPlantSpot(ServerWorld world, BlockPos floor) {
        Block block = world.getBlockState(floor).getBlock();
        if (block != Blocks.GRASS_BLOCK && block != Blocks.DIRT && block != Blocks.PODZOL) {
            return false;
        }
        if (!world.getBlockState(floor.up()).isReplaceable() || !world.getBlockState(floor.up(2)).isReplaceable()) {
            return false;
        }
        for (BlockPos nearby : BlockPos.iterate(floor.add(-2, 0, -2), floor.add(2, 2, 2))) {
            String path = Registries.BLOCK.getId(world.getBlockState(nearby).getBlock()).getPath();
            if (path.endsWith("_sapling") || path.endsWith("_log")) {
                return false;
            }
        }
        return true;
    }

    private BlockPos findSpot(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(feet.add(-SCAN_RADIUS, -2, -SCAN_RADIUS), feet.add(SCAN_RADIUS, 2, SCAN_RADIUS))) {
            if (!isPlantSpot(world, pos)) {
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

    private void finishOrFail(String reason) {
        if (planted > 0) {
            complete();
        } else {
            fail(reason);
        }
    }

    @Override
    protected void onAbort(AIPlayerEntity bot) {
        bot.getActionPack().stopAll();
    }
}
