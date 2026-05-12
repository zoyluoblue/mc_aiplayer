package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class CombatAction extends BaseAction {
    private String targetType;
    private LivingEntity target;
    private int ticksRunning;
    private int ticksStuck;
    private double lastX, lastZ;
    private static final int MAX_TICKS = 600;
    private static final double ATTACK_RANGE = 3.5;

    public CombatAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        targetType = task.getStringParameter("target");
        ticksRunning = 0;
        ticksStuck = 0;
        
        findTarget();
        
        if (target == null) {
            com.aiplayer.AiPlayerMod.warn("combat", "AiPlayer '{}' no targets nearby", aiPlayer.getAiPlayerName());
        }
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            aiPlayer.setSprinting(false);
            aiPlayer.getNavigation().stop();
            com.aiplayer.AiPlayerMod.info("combat", "AiPlayer '{}' combat complete", 
                aiPlayer.getAiPlayerName());
            result = ActionResult.success("Combat complete");
            return;
        }
        if (target == null || !target.isAlive() || target.isRemoved()) {
            if (ticksRunning % 20 == 0) {
                findTarget();
            }
            if (target == null) {
                return;
            }
        }
        
        double distance = aiPlayer.distanceTo(target);
        
        aiPlayer.setSprinting(true);
        aiPlayer.getNavigation().moveTo(target, SurvivalUtils.TASK_RUN_SPEED);
        
        double currentX = aiPlayer.getX();
        double currentZ = aiPlayer.getZ();
        if (Math.abs(currentX - lastX) < 0.1 && Math.abs(currentZ - lastZ) < 0.1) {
            ticksStuck++;
            
            if (ticksStuck > 80 && distance > ATTACK_RANGE) {
                result = ActionResult.failure("追击目标时被地形卡住");
                return;
            }
        } else {
            ticksStuck = 0;
        }
        lastX = currentX;
        lastZ = currentZ;
        
        aiPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("weapon"));
        aiPlayer.lookAtWorkTarget(target);
        if (distance <= ATTACK_RANGE) {
            aiPlayer.getNavigation().stop();
            aiPlayer.swingWorkHand(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (ticksRunning % 10 == 0) {
                if (aiPlayer.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    aiPlayer.doHurtTarget(serverLevel, target);
                    aiPlayer.damageBestTool("weapon", 1);
                }
            }
        }
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
        aiPlayer.setSprinting(false);
        target = null;
        com.aiplayer.AiPlayerMod.info("combat", "AiPlayer '{}' combat cancelled", 
            aiPlayer.getAiPlayerName());
    }

    @Override
    public String getDescription() {
        return "Attack " + targetType;
    }

    private void findTarget() {
        AABB searchBox = aiPlayer.getBoundingBox().inflate(32.0);
        List<Entity> entities = aiPlayer.level().getEntities(aiPlayer, searchBox);
        
        LivingEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (Entity entity : entities) {
            if (entity instanceof LivingEntity living && isValidTarget(living)) {
                double distance = aiPlayer.distanceTo(living);
                if (distance < nearestDistance) {
                    nearest = living;
                    nearestDistance = distance;
                }
            }
        }
        
        target = nearest;
        if (target != null) {
            com.aiplayer.AiPlayerMod.info("combat", "AiPlayer '{}' locked onto: {} at {}m", 
                aiPlayer.getAiPlayerName(), target.getType().toString(), (int)nearestDistance);
        }
    }

    private boolean isValidTarget(LivingEntity entity) {
        if (!entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        if (entity instanceof AiPlayerEntity || entity instanceof net.minecraft.world.entity.player.Player) {
            return false;
        }
        
        String targetLower = targetType.toLowerCase();
        if (targetLower.contains("mob") || targetLower.contains("hostile") || 
            targetLower.contains("monster") || targetLower.equals("any")) {
            return entity instanceof Monster;
        }
        String entityTypeName = entity.getType().toString().toLowerCase();
        return entityTypeName.contains(targetLower);
    }
}
