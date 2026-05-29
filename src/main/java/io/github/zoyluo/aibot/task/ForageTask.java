package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;

import java.util.Comparator;

public final class ForageTask extends AbstractTask {
    private enum Phase {
        SCANNING,
        APPROACHING,
        ATTACKING,
        PICKING_UP
    }

    private final EntityType<?> targetType;
    private final int countNeeded;
    private Phase phase = Phase.SCANNING;
    private LivingEntity currentTarget;
    private int countSoFar;
    private int pickupTicks;

    public ForageTask(EntityType<?> targetType, int countNeeded) {
        this.targetType = targetType;
        this.countNeeded = Math.max(1, countNeeded);
    }

    @Override
    public String name() {
        return "forage";
    }

    @Override
    public String describe() {
        return "Foraging " + targetType + " " + countSoFar + "/" + countNeeded + " phase=" + phase;
    }

    @Override
    public double progress() {
        return Math.min(1.0D, (double) countSoFar / countNeeded);
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.SCANNING;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > 2400) {
            fail("forage_timeout");
            return;
        }
        switch (phase) {
            case SCANNING -> scan(bot);
            case APPROACHING -> approach(bot);
            case ATTACKING -> attack(bot);
            case PICKING_UP -> pickup(bot);
        }
    }

    private void scan(AIPlayerEntity bot) {
        currentTarget = bot.getServerWorld()
                .getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(24.0D),
                        entity -> entity.isAlive() && entity.getType().equals(targetType) && entity != bot)
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)))
                .orElse(null);
        if (currentTarget == null) {
            fail("no_target_in_range");
            return;
        }
        phase = Phase.APPROACHING;
        bot.getActionPack().startPathTo(currentTarget.getBlockPos());
    }

    private void approach(AIPlayerEntity bot) {
        if (currentTarget == null || !currentTarget.isAlive()) {
            phase = Phase.SCANNING;
            return;
        }
        if (bot.distanceTo(currentTarget) < 3.0F) {
            bot.getActionPack().stopAll();
            phase = Phase.ATTACKING;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle()) {
            bot.getActionPack().startPathTo(currentTarget.getBlockPos());
        }
    }

    private void attack(AIPlayerEntity bot) {
        if (currentTarget == null || !currentTarget.isAlive()) {
            countSoFar++;
            pickupTicks = 80;
            phase = Phase.PICKING_UP;
            return;
        }
        if (elapsed % 16 == 0) {
            InteractAction.attackEntity(bot, currentTarget);
        }
    }

    private void pickup(AIPlayerEntity bot) {
        pickupTicks--;
        bot.getServerWorld()
                .getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(8.0D), entity -> !entity.getStack().isEmpty())
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceTo(bot)))
                .ifPresent(item -> {
                    if (bot.distanceTo(item) > 1.5F && bot.getActionPack().isPathExecutorIdle()) {
                        bot.getActionPack().startPathTo(item.getBlockPos());
                    }
                });
        if (pickupTicks > 0) {
            return;
        }
        if (countSoFar >= countNeeded) {
            complete();
        } else {
            currentTarget = null;
            phase = Phase.SCANNING;
        }
    }
}
