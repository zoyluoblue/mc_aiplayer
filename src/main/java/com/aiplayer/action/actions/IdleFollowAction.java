package com.aiplayer.action.actions;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class IdleFollowAction extends BaseAction {
    private Player targetPlayer;
    private int ticksSincePlayerSearch;
    private static final int PLAYER_SEARCH_INTERVAL = 100;
    private static final double FOLLOW_DISTANCE = 4.0;
    private static final double MIN_DISTANCE = 2.5;

    public IdleFollowAction(AiPlayerEntity aiPlayer) {
        super(aiPlayer, new Task("idle_follow", new HashMap<>()));
    }

    @Override
    protected void onStart() {
        ticksSincePlayerSearch = 0;
        findNearestPlayer();
        
        if (targetPlayer == null) {
            AiPlayerMod.debug("player", "AiPlayer '{}' has no player to follow (idle)", aiPlayer.getAiPlayerName());
        }
    }

    @Override
    protected void onTick() {
        ticksSincePlayerSearch++;
        if (ticksSincePlayerSearch >= PLAYER_SEARCH_INTERVAL) {
            findNearestPlayer();
            ticksSincePlayerSearch = 0;
        }
        
        if (targetPlayer == null || !targetPlayer.isAlive() || targetPlayer.isRemoved()) {
            findNearestPlayer();
            if (targetPlayer == null) {
                aiPlayer.getNavigation().stop();
                return;
            }
        }
        double distance = aiPlayer.distanceTo(targetPlayer);
        if (distance > FOLLOW_DISTANCE) {
            aiPlayer.setSprinting(true);
            aiPlayer.getNavigation().moveTo(targetPlayer, SurvivalUtils.TASK_RUN_SPEED);
        } else if (distance < MIN_DISTANCE) {
            aiPlayer.getNavigation().stop();
            aiPlayer.setSprinting(false);
        } else {
            if (!aiPlayer.getNavigation().isDone()) {
                aiPlayer.getNavigation().stop();
            }
            aiPlayer.setSprinting(false);
        }
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
        aiPlayer.setSprinting(false);
    }

    @Override
    public String getDescription() {
        return "Following player (idle)";
    }

        private void findNearestPlayer() {
        List<? extends Player> players = aiPlayer.level().players();
        
        if (players.isEmpty()) {
            targetPlayer = null;
            return;
        }

        UUID ownerUuid = aiPlayer.getOwnerUuid();
        if (ownerUuid != null) {
            for (Player player : players) {
                if (player.getUUID().equals(ownerUuid) && player.isAlive() && !player.isRemoved() && !player.isSpectator()) {
                    targetPlayer = player;
                    return;
                }
            }
        }
        
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            
            double distance = aiPlayer.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        
        if (nearest != targetPlayer && nearest != null) {
            AiPlayerMod.debug("player", "AiPlayer '{}' now following {} (idle)", 
                aiPlayer.getAiPlayerName(), nearest.getName().getString());
        }
        
        targetPlayer = nearest;
    }
}
