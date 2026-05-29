package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.ActionResult;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.LookAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.Optional;

public final class FishTask extends AbstractTask {
    private enum Phase {
        FIND_WATER,
        MOVE_TO_WATER,
        CAST,
        WAIT_BITE,
        REEL,
        COLLECT
    }

    private static final int SEARCH_RADIUS = 12;
    private static final int WAIT_BITE_FALLBACK_TICKS = 600;
    private static final int COLLECT_TICKS = 80;
    private static final int DEFAULT_MAX_TICKS = 6000;

    private final int maxCatches;
    private final int maxTicks;

    private Phase phase = Phase.FIND_WATER;
    private BlockPos waterPos;
    private BlockPos standPos;
    private int phaseTicks;
    private int catches;
    private int inventoryBeforeReel;

    public FishTask(int maxCatches, int maxTicks) {
        this.maxCatches = Math.max(1, maxCatches);
        this.maxTicks = Math.max(200, maxTicks);
    }

    public FishTask(int maxCatches) {
        this(maxCatches, DEFAULT_MAX_TICKS);
    }

    @Override
    public String name() {
        return "fish";
    }

    @Override
    public String describe() {
        return "Fishing " + catches + "/" + maxCatches;
    }

    @Override
    public double progress() {
        if (state == TaskState.COMPLETED) {
            return 1.0D;
        }
        double catchProgress = Math.min(0.95D, catches / (double) maxCatches);
        double timeProgress = Math.min(0.90D, elapsed / (double) maxTicks);
        return Math.max(catchProgress, timeProgress * 0.25D);
    }

    @Override
    public boolean isWaiting() {
        return phase == Phase.WAIT_BITE;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        phase = Phase.FIND_WATER;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > maxTicks) {
            if (catches > 0) {
                complete();
            } else {
                fail("fish_timeout");
            }
            return;
        }
        switch (phase) {
            case FIND_WATER -> findWater(bot);
            case MOVE_TO_WATER -> moveToWater(bot);
            case CAST -> cast(bot);
            case WAIT_BITE -> waitBite(bot);
            case REEL -> reel(bot);
            case COLLECT -> collect(bot);
        }
    }

    private void findWater(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        WaterChoice choice = nearestWater(bot).orElse(null);
        if (choice == null) {
            fail("no_water_nearby");
            return;
        }
        waterPos = choice.water();
        standPos = choice.stand();
        if (bot.getBlockPos().getSquaredDistance(standPos) <= 2.25D) {
            bot.getActionPack().stopMovement();
            transition(Phase.CAST);
            return;
        }
        ActionResult result = bot.getActionPack().startPathTo(standPos);
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(Vec3d.ofCenter(standPos));
        }
        transition(Phase.MOVE_TO_WATER);
    }

    private void moveToWater(AIPlayerEntity bot) {
        if (bot.getBlockPos().getSquaredDistance(standPos) <= 2.25D) {
            bot.getActionPack().stopAll();
            transition(Phase.CAST);
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && phaseTicks > 20) {
            ActionResult result = bot.getActionPack().startPathTo(standPos);
            if (result.isFailed()) {
                bot.getActionPack().startWalkTo(Vec3d.ofCenter(standPos));
            }
        }
        phaseTicks++;
    }

    private void cast(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        bot.getActionPack().stopMovement();
        LookAction.lookAt(bot, waterPos.toCenterPos().add(0.0D, 0.15D, 0.0D));
        if (currentHook(bot).isPresent()) {
            transition(Phase.WAIT_BITE);
            return;
        }
        ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
        if (result.isFailed()) {
            fail("cast_failed:" + result.reason());
            return;
        }
        transition(Phase.WAIT_BITE);
    }

    private void waitBite(AIPlayerEntity bot) {
        phaseTicks++;
        currentHook(bot).ifPresentOrElse(
                hook -> LookAction.lookAt(bot, hook.getPos()),
                () -> LookAction.lookAt(bot, waterPos.toCenterPos().add(0.0D, 0.15D, 0.0D)));
        Optional<FishingBobberEntity> hook = currentHook(bot);
        if (hook.isEmpty()) {
            if (phaseTicks > 30) {
                transition(Phase.CAST);
            }
            return;
        }
        if (hasBite(hook.get()) || phaseTicks >= WAIT_BITE_FALLBACK_TICKS) {
            transition(Phase.REEL);
        }
    }

    private void reel(AIPlayerEntity bot) {
        if (!equipRod(bot)) {
            fail("need_fishing_rod");
            return;
        }
        currentHook(bot).ifPresent(hook -> LookAction.lookAt(bot, hook.getPos()));
        inventoryBeforeReel = HarvestCore.totalInventoryCount(bot);
        ActionResult result = InteractAction.useItemInAir(bot, Hand.MAIN_HAND);
        if (result.isFailed()) {
            fail("reel_failed:" + result.reason());
            return;
        }
        transition(Phase.COLLECT);
    }

    private void collect(AIPlayerEntity bot) {
        phaseTicks++;
        HarvestCore.chaseDrop(bot, null, 8.0D);
        if (phaseTicks < COLLECT_TICKS) {
            return;
        }
        if (HarvestCore.totalInventoryCount(bot) > inventoryBeforeReel) {
            catches++;
        }
        if (catches >= maxCatches) {
            bot.getActionPack().stopAll();
            complete();
            return;
        }
        transition(Phase.FIND_WATER);
    }

    private boolean equipRod(AIPlayerEntity bot) {
        return InventoryAction.findItem(bot, Items.FISHING_ROD)
                .stream()
                .anyMatch(slot -> InventoryAction.equipFromSlot(bot, slot) >= 0);
    }

    private Optional<WaterChoice> nearestWater(AIPlayerEntity bot) {
        BlockPos origin = bot.getBlockPos();
        return BlockPos.stream(
                        origin.add(-SEARCH_RADIUS, -2, -SEARCH_RADIUS),
                        origin.add(SEARCH_RADIUS, 3, SEARCH_RADIUS))
                .filter(pos -> bot.getServerWorld().getFluidState(pos).isIn(FluidTags.WATER))
                .map(BlockPos::toImmutable)
                .map(pos -> waterChoice(bot, pos))
                .filter(choice -> choice != null)
                .min(Comparator.comparingDouble(choice -> choice.water().getSquaredDistance(origin)));
    }

    private WaterChoice waterChoice(AIPlayerEntity bot, BlockPos water) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos stand = water.offset(direction);
            if (Standability.isStandable(bot.getServerWorld(), stand)) {
                return new WaterChoice(water, stand);
            }
        }
        return null;
    }

    private Optional<FishingBobberEntity> currentHook(AIPlayerEntity bot) {
        if (bot.fishHook != null && bot.fishHook.isAlive()) {
            return Optional.of(bot.fishHook);
        }
        Box box = bot.getBoundingBox().expand(32.0D);
        return bot.getServerWorld()
                .getEntitiesByClass(FishingBobberEntity.class, box,
                        hook -> hook.isAlive() && hook.getPlayerOwner() == bot)
                .stream()
                .min(Comparator.comparingDouble(bot::distanceTo));
    }

    private boolean hasBite(FishingBobberEntity hook) {
        if (hook.getHookedEntity() != null) {
            return true;
        }
        return booleanField(hook, "caughtFish", "field_23232")
                || intField(hook, "hookCountdown", "field_7173") > 0;
    }

    private static boolean booleanField(Object target, String named, String intermediary) {
        Object value = fieldValue(target, named, intermediary);
        return value instanceof Boolean bool && bool;
    }

    private static int intField(Object target, String named, String intermediary) {
        Object value = fieldValue(target, named, intermediary);
        return value instanceof Integer integer ? integer : 0;
    }

    private static Object fieldValue(Object target, String named, String intermediary) {
        Class<?> type = target.getClass();
        for (String fieldName : new String[]{named, intermediary}) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignored) {
                // Try the next runtime name. Yarn dev runs use named fields, remapped jars use intermediary names.
            }
        }
        return null;
    }

    private void transition(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private record WaterChoice(BlockPos water, BlockPos stand) {
    }
}
