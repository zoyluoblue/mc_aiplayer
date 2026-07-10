package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.BuildAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.LightType;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.Queue;

public final class LightAreaTask extends AbstractTask {
    private enum Phase {
        SCAN,
        WALK,
        PLACE,
        DONE
    }

    private final int radius;
    private final int maxTorches;
    private final Queue<BlockPos> targets = new LinkedList<>();
    private Phase phase = Phase.SCAN;
    private BlockPos target;
    private BlockPos standPos;
    private int placed;

    public LightAreaTask(int radius, int maxTorches) {
        this.radius = Math.max(2, radius);
        this.maxTorches = Math.max(1, maxTorches);
    }

    @Override
    public String name() {
        return "light_area";
    }

    @Override
    public String describe() {
        return "Lighting radius=" + radius + " placed=" + placed + "/" + maxTorches + " phase=" + phase;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        return Math.min(0.95D, (double) placed / maxTorches);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SCAN;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 1800) {
            fail("light_area_timeout");
            return;
        }
        if (InventoryAction.countItem(bot, Items.TORCH) <= 0) {
            if (placed > 0) {
                complete();
            } else {
                fail("missing minecraft:torch x1");
            }
            return;
        }
        switch (phase) {
            case SCAN -> scan(bot);
            case WALK -> walk(bot);
            case PLACE -> place(bot);
            case DONE -> complete();
        }
    }

    private void scan(AIPlayerEntity bot) {
        targets.clear();
        BlockPos origin = bot.getBlockPos();
        int threshold = AIBotConfig.get().night().torchLightThreshold();
        BlockPos.stream(origin.add(-radius, -2, -radius), origin.add(radius, 3, radius))
                .map(BlockPos::toImmutable)
                .filter(pos -> io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveBlock(bot, pos.down()))
                .filter(pos -> canPlaceTorchAt(bot, pos, threshold))
                .sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(origin)))
                .limit(maxTorches)
                .forEach(targets::add);
        phase = targets.isEmpty() ? Phase.DONE : Phase.WALK;
    }

    private void walk(AIPlayerEntity bot) {
        if (target == null || !canPlaceTorchAt(bot, target, AIBotConfig.get().night().torchLightThreshold())) {
            target = targets.poll();
            standPos = null;
        }
        if (target == null) {
            phase = Phase.DONE;
            return;
        }
        if (bot.getEyePos().distanceTo(target.toCenterPos()) <= 4.0D) {
            bot.getActionPack().stopAll();
            phase = Phase.PLACE;
            return;
        }
        if (standPos == null) {
            standPos = adjacentStandPos(bot, target);
        }
        if (standPos == null) {
            target = null;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(standPos);
        }
    }

    private void place(AIPlayerEntity bot) {
        int slot = InventoryAction.findItem(bot, Items.TORCH).orElse(-1);
        if (slot < 0) {
            fail("missing minecraft:torch x1");
            return;
        }
        if (InventoryAction.equipFromSlot(bot, slot) < 0) {
            fail("cannot_equip_torch");
            return;
        }
        ActionResult result = BuildAction.placeBlockAt(bot, target);
        if (result.isSuccess()) {
            placed++;
        }
        target = null;
        if (placed >= maxTorches) {
            complete();
        } else {
            phase = targets.isEmpty() ? Phase.SCAN : Phase.WALK;
        }
    }

    private static boolean canPlaceTorchAt(AIPlayerEntity bot, BlockPos pos, int threshold) {
        var world = bot.getServerWorld();
        BlockPos botFeet = bot.getBlockPos();
        if (pos.equals(botFeet) || pos.equals(botFeet.up())) {
            return false;
        }
        return world.getBlockState(pos).isAir()
                && !world.getBlockState(pos.down()).isAir()
                && world.getLightLevel(LightType.BLOCK, pos) < threshold;
    }

    private static BlockPos adjacentStandPos(AIPlayerEntity bot, BlockPos target) {
        if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), target)) {
            return target;
        }
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos candidate = target.offset(direction);
            if (io.github.zoyluo.aibot.pathfinding.Standability.isStandable(bot.getServerWorld(), candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
