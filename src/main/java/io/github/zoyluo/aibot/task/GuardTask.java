package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public final class GuardTask extends AbstractTask {
    private static final double GUARD_RADIUS = 10.0D;
    private static final double RETURN_DISTANCE = 5.0D;
    private static final int TIMEOUT_TICKS = 12000;

    private enum Phase {
        WATCH,
        APPROACH,
        STRIKE,
        REPOSITION,
        RETURN
    }

    private final String targetPlayerName;
    private final BlockPos fixedPoint;
    private Phase phase = Phase.WATCH;
    private LivingEntity target;
    private BlockPos guardPoint;
    private int repositionTicks;
    private boolean waiting;

    public GuardTask(BlockPos point, String targetPlayerName) {
        this.fixedPoint = point == null ? null : point.toImmutable();
        this.targetPlayerName = targetPlayerName == null ? "" : targetPlayerName.trim();
    }

    public static GuardTask point(BlockPos point) {
        return new GuardTask(point, "");
    }

    public static GuardTask player(String playerName) {
        return new GuardTask(null, playerName);
    }

    @Override
    public String name() {
        return "guard";
    }

    @Override
    public String describe() {
        return "Guarding " + compact(currentGuardPoint()) + " phase=" + phase + (waiting ? " waiting" : "");
    }

    @Override
    public double progress() {
        return phase == Phase.WATCH ? 0.5D : 0.75D;
    }

    @Override
    public boolean isWaiting() {
        return waiting;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        guardPoint = fixedPoint == null ? bot.getBlockPos().toImmutable() : fixedPoint;
        CombatCore.equipMelee(bot);
        phase = Phase.WATCH;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > TIMEOUT_TICKS) {
            fail("timeout");
            return;
        }

        BlockPos point = resolveGuardPoint(bot);
        if (point == null) {
            bot.getActionPack().stopAll();
            waiting = true;
            if (elapsed % 200 == 1) {
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "守护目标不在线或不在同一维度,我先原地警戒。");
            }
            return;
        }
        guardPoint = point;
        waiting = false;
        switch (phase) {
            case WATCH -> watch(bot);
            case APPROACH -> approach(bot);
            case STRIKE -> strike(bot);
            case REPOSITION -> reposition(bot);
            case RETURN -> returnToGuardPoint(bot);
        }
    }

    private void watch(AIPlayerEntity bot) {
        target = CombatCore.nearestHostileAround(bot, guardPoint, GUARD_RADIUS).orElse(null);
        if (target != null) {
            CombatCore.equipMelee(bot);
            phase = Phase.APPROACH;
            CombatCore.startApproach(bot, target);
            return;
        }
        if (bot.getBlockPos().getSquaredDistance(guardPoint) > RETURN_DISTANCE * RETURN_DISTANCE) {
            phase = Phase.RETURN;
            bot.getActionPack().startPathTo(guardPoint);
            return;
        }
        bot.getActionPack().stopMovement();
        waiting = true;
    }

    private void approach(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            target = null;
            phase = Phase.RETURN;
            return;
        }
        CombatCore.lookAt(bot, target);
        if (CombatCore.inMeleeRange(bot, target)) {
            bot.getActionPack().stopAll();
            phase = Phase.STRIKE;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            CombatCore.startApproach(bot, target);
        }
    }

    private void strike(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            target = null;
            phase = Phase.RETURN;
            return;
        }
        if (bot.distanceTo(target) > CombatCore.ATTACK_RANGE + 0.75F) {
            phase = Phase.APPROACH;
            CombatCore.startApproach(bot, target);
            return;
        }
        if (CombatCore.strikeIfReady(bot, target)) {
            repositionTicks = 8;
            phase = Phase.REPOSITION;
        }
    }

    private void reposition(AIPlayerEntity bot) {
        if (target == null || !target.isAlive()) {
            bot.getActionPack().stopMovement();
            target = null;
            phase = Phase.RETURN;
            return;
        }
        CombatCore.lookAt(bot, target);
        bot.getActionPack().setStrafing(elapsed % 40 < 20 ? 0.45F : -0.45F);
        repositionTicks--;
        if (repositionTicks <= 0) {
            bot.getActionPack().stopMovement();
            phase = Phase.STRIKE;
        }
    }

    private void returnToGuardPoint(AIPlayerEntity bot) {
        if (bot.getBlockPos().getSquaredDistance(guardPoint) <= 4.0D) {
            bot.getActionPack().stopAll();
            phase = Phase.WATCH;
            return;
        }
        if (bot.getActionPack().isPathExecutorIdle() && elapsed > 10) {
            bot.getActionPack().startPathTo(guardPoint);
        }
    }

    private BlockPos resolveGuardPoint(AIPlayerEntity bot) {
        if (!targetPlayerName.isBlank()) {
            ServerPlayerEntity player = bot.getServer().getPlayerManager().getPlayer(targetPlayerName);
            return player != null && player.getServerWorld() == bot.getServerWorld() ? player.getBlockPos().toImmutable() : null;
        }
        if (fixedPoint != null) {
            return fixedPoint;
        }
        Optional<ServerPlayerEntity> owner = AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.getServer().getPlayerManager().getPlayer(uuid));
        return owner.filter(player -> player.getServerWorld() == bot.getServerWorld())
                .map(player -> player.getBlockPos().toImmutable())
                .orElse(guardPoint);
    }

    private BlockPos currentGuardPoint() {
        return guardPoint == null ? fixedPoint : guardPoint;
    }

    private static String compact(BlockPos pos) {
        return pos == null ? "owner" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
