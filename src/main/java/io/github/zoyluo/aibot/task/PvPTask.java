package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.EquipAction;
import io.github.zoyluo.aibot.action.InteractAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;

public final class PvPTask extends AbstractTask {
    private enum Phase {
        FIND,
        APPROACH,
        ATTACK,
        FINISH
    }

    private static final int TIMEOUT_TICKS = 600;
    private static final double ATTACK_RANGE = 3.0D;
    private static final double RE_APPROACH_RANGE = 3.75D;
    private static final int REPATH_TICKS = 20;

    private final String targetName;
    private Phase phase = Phase.FIND;
    private ServerPlayerEntity target;
    private int nextRepathTick;

    public PvPTask(String targetName) {
        this.targetName = targetName == null ? "" : targetName.trim();
    }

    @Override
    public String name() {
        return "pvp";
    }

    @Override
    public String describe() {
        return "Hunting " + targetName + " phase=" + phase;
    }

    @Override
    public double progress() {
        return switch (phase) {
            case FIND -> 0.1D;
            case APPROACH -> 0.4D;
            case ATTACK -> 0.7D;
            case FINISH -> 1.0D;
        };
    }

    @Override
    public boolean isWaiting() {
        return phase == Phase.FIND || phase == Phase.FINISH;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        phase = Phase.FIND;
        target = null;
        nextRepathTick = 0;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        if (elapsed > TIMEOUT_TICKS) {
            fail("pvp_timeout");
            return;
        }

        if (phase != Phase.FINISH) {
            ServerPlayerEntity resolved = resolveTarget(bot);
            if (resolved == null || resolved.getServerWorld() != bot.getServerWorld()) {
                if (phase == Phase.FIND) {
                    fail("target_not_found");
                } else {
                    complete();
                }
                return;
            }
            if (!resolved.isAlive() || resolved.getHealth() <= 0.0F) {
                complete();
                return;
            }
            target = resolved;
        }

        switch (phase) {
            case FIND -> find(bot);
            case APPROACH -> approach(bot);
            case ATTACK -> attack(bot);
            case FINISH -> finish();
        }
    }

    private void find(AIPlayerEntity bot) {
        EquipAction.equipBestWeapon(bot);
        phase = Phase.APPROACH;
        startPath(bot);
    }

    private void approach(AIPlayerEntity bot) {
        double distance = bot.distanceTo(target);
        if (distance <= ATTACK_RANGE) {
            bot.getActionPack().stopAll();
            phase = Phase.ATTACK;
            return;
        }
        if (elapsed >= nextRepathTick || bot.getActionPack().isPathExecutorIdle()) {
            startPath(bot);
            nextRepathTick = elapsed + REPATH_TICKS;
        }
    }

    private void attack(AIPlayerEntity bot) {
        double distance = bot.distanceTo(target);
        if (distance > RE_APPROACH_RANGE) {
            bot.getActionPack().stopAll();
            phase = Phase.APPROACH;
            startPath(bot);
            return;
        }
        CombatCore.lookAt(bot, target);
        if (bot.getAttackCooldownProgress(0.5F) >= 0.95F) {
            InteractAction.attackEntity(bot, target);
        }
    }

    private void finish() {
        complete();
    }

    private void startPath(AIPlayerEntity bot) {
        var result = bot.getActionPack().startPathTo(target.getBlockPos());
        if (result.isFailed()) {
            bot.getActionPack().startWalkTo(target.getPos());
        }
    }

    private ServerPlayerEntity resolveTarget(AIPlayerEntity bot) {
        return bot.getServer().getPlayerManager().getPlayer(targetName);
    }
}
