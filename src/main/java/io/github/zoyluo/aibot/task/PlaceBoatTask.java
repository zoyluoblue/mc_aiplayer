package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;

/**
 * 下水放船并坐上去:背包要有船(craft minecraft:oak_boat=5 木板)。找身边水面,走到岸边,
 * 对着水面把船放下去(BoatItem.use 自带视线落水判定),再坐进去。下船用 dismount。
 * 划船移动交给后续 move/follow(乘船状态下的走位控制由原版载具逻辑兜)。
 */
public final class PlaceBoatTask extends AbstractTask {
    private static final int MAX_ELAPSED = 600;   // ~30s
    private static final int USE_INTERVAL = 10;
    private static final int USE_LIMIT = 6;
    private static final int SEARCH_RADIUS = 6;
    private static final double THROW_RANGE = 3.0D;

    private BlockPos water;
    private int useCooldown;
    private int useAttempts;
    private boolean thrown;
    private int thrownAt; // thrown 置位时的 elapsed,重抛窗口以它为锚(别用全局取模,相位漂)

    @Override
    public String name() {
        return "place_boat";
    }

    @Override
    public String describe() {
        return "place_boat " + (thrown ? "boarding" : "placing");
    }

    @Override
    public double progress() {
        return state == TaskState.COMPLETED ? 1.0D : thrown ? 0.7D : 0.3D;
    }

    @Override
    public boolean isWaiting() {
        return true;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        water = null;
        useCooldown = 0;
        useAttempts = 0;
        thrown = false;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (bot.hasVehicle()) {
            complete();
            return;
        }
        if (elapsed > MAX_ELAPSED) {
            fail("boat_timeout");
            return;
        }
        // 放出去了 → 找刚出生的船坐进去。
        if (thrown) {
            Entity boat = nearestBoat(bot);
            if (boat != null && bot.distanceTo(boat) <= 3.0D) {
                bot.getActionPack().stopMovement();
                bot.startRiding(boat, true);
                return;
            }
            if (boat != null && bot.getActionPack().isPathExecutorIdle()) {
                bot.getActionPack().startWalkTo(boat.getPos());
            }
            if (boat == null && elapsed - thrownAt > 40) {
                thrown = false; // 抛出 2s 还没见到船(落水失败),重放
            }
            return;
        }

        if (boatSlot(bot) < 0) {
            fail("no_boat_item: 背包没有船,先 craft minecraft:oak_boat(5 木板)");
            return;
        }
        if (water == null || !isOpenWater(bot.getServerWorld(), water)) {
            water = findWater(bot);
            if (water == null) {
                fail("no_water_nearby: 附近 " + SEARCH_RADIUS + " 格内没有开阔水面");
                return;
            }
        }
        double dist = Math.sqrt(bot.getEyePos().squaredDistanceTo(water.toCenterPos()));
        if (dist > THROW_RANGE) {
            if (bot.getActionPack().isPathExecutorIdle()) {
                bot.getActionPack().startPathTo(water.up());
            }
            return;
        }
        bot.getActionPack().stopMovement();
        InventoryAction.equipFromSlot(bot, boatSlot(bot));
        LookAction.lookAt(bot, water.toCenterPos());
        if (useCooldown > 0) {
            useCooldown--;
            return;
        }
        useAttempts++;
        if (useAttempts > USE_LIMIT) {
            fail("boat_place_failed: 视线落不到水面(岸太高?走近点再试)");
            return;
        }
        ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
        useCooldown = USE_INTERVAL;
        if (result.isSuccess()) {
            thrown = true;
            thrownAt = elapsed;
        }
    }

    private static int boatSlot(AIPlayerEntity bot) {
        var main = bot.getInventory().main;
        for (int i = 0; i < main.size(); i++) {
            ItemStack stack = main.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            String path = Registries.ITEM.getId(stack.getItem()).getPath();
            if (path.endsWith("_boat") || path.endsWith("_raft")) {
                return i;
            }
        }
        return -1;
    }

    /** 水面格:本格是水、上方一格开阔(船要浮出水面)。 */
    private static boolean isOpenWater(ServerWorld world, BlockPos pos) {
        return world.getFluidState(pos).isIn(FluidTags.WATER)
                && world.getBlockState(pos.up()).isReplaceable();
    }

    private BlockPos findWater(AIPlayerEntity bot) {
        ServerWorld world = bot.getServerWorld();
        BlockPos feet = bot.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(feet.add(-SEARCH_RADIUS, -3, -SEARCH_RADIUS),
                feet.add(SEARCH_RADIUS, 1, SEARCH_RADIUS))) {
            if (!isOpenWater(world, pos)) {
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

    private Entity nearestBoat(AIPlayerEntity bot) {
        return bot.getServerWorld()
                .getOtherEntities(bot, bot.getBoundingBox().expand(6.0D), entity -> {
                    if (!entity.isAlive() || entity.hasPassengers()) {
                        return false;
                    }
                    String path = Registries.ENTITY_TYPE.getId(entity.getType()).getPath();
                    return path.contains("boat") || path.contains("raft");
                })
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo))
                .orElse(null);
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
