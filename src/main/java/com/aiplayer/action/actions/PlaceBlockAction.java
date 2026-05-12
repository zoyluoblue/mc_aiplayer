package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.AiPlayerMod;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.interaction.BlockPlacementRules;
import com.aiplayer.execution.interaction.InteractionActionType;
import com.aiplayer.execution.interaction.InteractionFailureKey;
import com.aiplayer.execution.interaction.InteractionFailureMemory;
import com.aiplayer.execution.interaction.InteractionTarget;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class PlaceBlockAction extends BaseAction {
    private Block blockToPlace;
    private BlockPos targetPos;
    private InteractionTarget activePlacementTarget;
    private final InteractionFailureMemory placementFailures = new InteractionFailureMemory();
    private int ticksRunning;
    private int actionTicks;
    private int moveTicksWithoutProgress;
    private double closestTargetDistanceSq;
    private static final int MAX_TICKS = 200;
    private static final int MOVE_STUCK_TICKS = 120;
    private static final int FAILURE_TTL_TICKS = 1200;
    private static final int PLACE_DELAY_TICKS = 8;
    private static final double PLACE_REACH = 4.5D;

    public PlaceBlockAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        int x = task.getIntParameter("x", 0);
        int y = task.getIntParameter("y", 0);
        int z = task.getIntParameter("z", 0);
        
        targetPos = new BlockPos(x, y, z);
        ticksRunning = 0;
        actionTicks = 0;
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
        
        blockToPlace = parseBlock(blockName);
        
        if (blockToPlace == null || blockToPlace == Blocks.AIR) {
            result = ActionResult.failure("无效的方块类型：" + blockName);
            return;
        }
        Item item = SurvivalUtils.getPlacementItem(blockToPlace);
        if (!aiPlayer.hasItem(item, 1)) {
            result = ActionResult.failure("背包里没有可放置的 " + blockToPlace.getName().getString());
            return;
        }
        activePlacementTarget = placementTarget(targetPos, "place_direct");
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("放置方块超时");
            return;
        }

        if (activePlacementTarget == null) {
            activePlacementTarget = placementTarget(targetPos, "place_direct");
        }

        InteractionFailureKey rejected = placementFailures.rejectionFor(activePlacementTarget, ticksRunning).orElse(null);
        if (rejected != null) {
            result = ActionResult.failure("放置方块失败：" + rejected.summary());
            return;
        }

        if (!moveToPlacementTarget(activePlacementTarget)) {
            return;
        }

        String invalidReason = BlockPlacementRules.validate(aiPlayer, activePlacementTarget, blockToPlace);
        if (invalidReason != null) {
            rejectPlacementTarget(activePlacementTarget, invalidReason);
            result = ActionResult.failure("放置方块失败：" + invalidReason + "；" + activePlacementTarget.summary());
            return;
        }

        aiPlayer.lookAtWorkTarget(activePlacementTarget.targetBlock());
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        actionTicks++;
        if (actionTicks < PLACE_DELAY_TICKS) {
            return;
        }

        if (!SurvivalUtils.placeBlock(aiPlayer, targetPos, blockToPlace)) {
            rejectPlacementTarget(activePlacementTarget, "place_failed");
            result = ActionResult.failure("放置方块失败：place_failed；" + activePlacementTarget.summary());
            return;
        }
        if (aiPlayer.level().getBlockState(targetPos).getBlock() != blockToPlace) {
            rejectPlacementTarget(activePlacementTarget, "world_state_mismatch");
            result = ActionResult.failure("放置方块失败：世界方块未变为目标方块；" + activePlacementTarget.summary());
            return;
        }
        result = ActionResult.success("已放置 " + blockToPlace.getName().getString());
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "放置 " + (blockToPlace == null ? "方块" : blockToPlace.getName().getString()) + " 到 " + targetPos;
    }

    private InteractionTarget placementTarget(BlockPos pos, String reason) {
        return new InteractionTarget(
            pos.immutable(),
            null,
            PLACE_REACH,
            "hand",
            InteractionActionType.PLACE_BLOCK,
            reason
        );
    }

    private boolean moveToPlacementTarget(InteractionTarget target) {
        if (SurvivalUtils.moveNear(aiPlayer, target.targetBlock(), target.reachRange())) {
            aiPlayer.getNavigation().stop();
            aiPlayer.setSprinting(false);
            resetMovement();
            return true;
        }
        aiPlayer.lookAtWorkTarget(target.targetBlock());
        actionTicks = 0;
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(target.targetBlock()));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return false;
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > MOVE_STUCK_TICKS || aiPlayer.getNavigation().isStuck()) {
            rejectPlacementTarget(target, "movement_stuck");
            result = ActionResult.failure("放置方块失败：目标不可达；" + target.summary());
        }
        return false;
    }

    private void resetMovement() {
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
    }

    private void rejectPlacementTarget(InteractionTarget target, String reason) {
        placementFailures.remember(target, reason, ticksRunning, FAILURE_TTL_TICKS);
        AiPlayerMod.info("actions", "AiPlayer '{}' placement target rejected: {}; reason={}",
            aiPlayer.getAiPlayerName(),
            target.summary(),
            reason);
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        ResourceLocation resourceLocation = ResourceLocation.parse(blockName);
        return BuiltInRegistries.BLOCK.getValue(resourceLocation);
    }
}
