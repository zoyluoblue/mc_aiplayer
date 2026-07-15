package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.brain.BrainCoordinator;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public final class FollowTask extends AbstractTask {
    private static final double STOP_DISTANCE = 3.0D;
    private static final double START_DISTANCE = 4.5D;
    private static final int REPATH_TICKS = 40;

    private final String targetName;
    private int nextRepathTick;
    private boolean waiting;

    public FollowTask(String targetName) {
        this.targetName = targetName == null ? "" : targetName.trim();
    }

    @Override
    public String name() {
        return "follow";
    }

    @Override
    public String describe() {
        return "Following " + (targetName.isBlank() ? "owner" : targetName) + (waiting ? " waiting" : "");
    }

    @Override
    public double progress() {
        return waiting ? 0.0D : 0.5D;
    }

    @Override
    public boolean isWaiting() {
        return waiting;
    }

    @Override
    protected void onStart(AIPlayerEntity bot) {
        nextRepathTick = 0;
        waiting = false;
    }

    @Override
    protected void onTick(AIPlayerEntity bot) {
        ServerPlayerEntity target = target(bot).orElse(null);
        if (target == null || target.getServerWorld() != bot.getServerWorld()) {
            bot.getActionPack().stopAll();
            waiting = true;
            if (elapsed % 200 == 1) {
                BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", "目标玩家不在线或不在同一维度,我先原地等。");
            }
            return;
        }
        double distance = bot.distanceTo(target);
        boolean visible = CombatCore.hasLineOfSight(bot, target);
        if (distance <= STOP_DISTANCE && visible) {
            bot.getActionPack().stopAll();
            waiting = true;
            return;
        }
        waiting = false;
        BlockPos targetPos = target.getBlockPos();
        BlockPos activeGoal = bot.getActionPack().activePathGoal();
        boolean targetMoved = activeGoal == null || activeGoal.getSquaredDistance(targetPos) > 4.0D;
        if ((distance >= START_DISTANCE || !visible) && elapsed >= nextRepathTick
                && (bot.getActionPack().isPathExecutorIdle() || targetMoved)) {
            bot.getActionPack().startPathTo(target.getBlockPos());
            nextRepathTick = elapsed + REPATH_TICKS;
        }
    }

    private Optional<ServerPlayerEntity> target(AIPlayerEntity bot) {
        if (!targetName.isBlank()) {
            return Optional.ofNullable(bot.getServer().getPlayerManager().getPlayer(targetName));
        }
        return AIPlayerManager.INSTANCE.ownerOf(bot)
                .map(uuid -> bot.getServer().getPlayerManager().getPlayer(uuid));
    }
}
