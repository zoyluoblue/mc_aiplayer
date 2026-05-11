package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class CraftItemAction extends BaseAction {
    private String itemName;
    private int quantity;
    private int ticksRunning;
    private BlockPos woodTarget;
    private int woodBreakTicks;
    private int moveTicksWithoutProgress;
    private double closestTargetDistanceSq;
    private final Set<BlockPos> rejectedTargets = new HashSet<>();
    private static final int CRAFT_DELAY = 20;
    private static final int MAX_TICKS = 12000;
    private static final int TARGET_MOVE_TIMEOUT_TICKS = 200;
    private static final int MAX_REJECTED_TARGETS = 12;

    public CraftItemAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        itemName = task.getStringParameter("item", "planks").toLowerCase(Locale.ROOT);
        quantity = task.getIntParameter("quantity", 1);
        ticksRunning = 0;
        woodBreakTicks = 0;
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
        rejectedTargets.clear();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("合成超时：" + itemName);
            return;
        }

        if (isWoodenDoor()) {
            tickWoodenDoorCraft();
            return;
        }

        if (ticksRunning < CRAFT_DELAY) {
            return;
        }

        boolean crafted = false;
        if (itemName.contains("plank") || itemName.contains("木板")) {
            crafted = aiPlayer.craftPlanksFromLogs(quantity);
        } else if (itemName.contains("stick") || itemName.contains("木棍")) {
            crafted = aiPlayer.craftSticks(quantity);
        } else if (itemName.contains("wooden_pickaxe") || itemName.contains("木镐")) {
            for (int i = 0; i < quantity; i++) {
                crafted = aiPlayer.craftWoodenPickaxe();
                if (!crafted) {
                    break;
                }
            }
        } else if (itemName.contains("wooden_axe") || itemName.contains("木斧")) {
            for (int i = 0; i < quantity; i++) {
                crafted = aiPlayer.craftWoodenAxe();
                if (!crafted) {
                    break;
                }
            }
        } else if (itemName.contains("stone_pickaxe") || itemName.contains("石镐")) {
            for (int i = 0; i < quantity; i++) {
                crafted = aiPlayer.craftStonePickaxe();
                if (!crafted) {
                    break;
                }
            }
        }

        result = crafted
            ? ActionResult.success("已合成 " + itemName)
            : ActionResult.failure("材料不足，无法合成 " + itemName);
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        if (isWoodenDoor()) {
            if (aiPlayer.getWoodenDoorCount() >= quantity) {
                return "制作木门：已完成";
            }
            return "制作木门：门 " + aiPlayer.getWoodenDoorCount() + "/" + quantity
                + "，木板 " + aiPlayer.getWoodenPlankCount() + "/" + requiredPlanksForDoor();
        }
        return "合成 " + quantity + " 个 " + itemName;
    }

    private void tickWoodenDoorCraft() {
        if (aiPlayer.getWoodenDoorCount() >= quantity) {
            result = ActionResult.success("已制作木门");
            return;
        }

        if (aiPlayer.craftWoodenDoor(quantity)) {
            result = ActionResult.success("已制作木门");
            return;
        }

        aiPlayer.craftPlanksFromLogs(requiredPlanksForDoor());
        if (aiPlayer.craftWoodenDoor(quantity)) {
            result = ActionResult.success("已制作木门");
            return;
        }

        gatherWoodForCrafting();
    }

    private void gatherWoodForCrafting() {
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("axe"));

        if (woodTarget == null || aiPlayer.level().getBlockState(woodTarget).isAir()) {
            Optional<BlockPos> found = SurvivalUtils.findNearestBlock(aiPlayer,
                (pos, state) -> !rejectedTargets.contains(pos) && SurvivalUtils.isLog(state.getBlock()),
                32,
                12);
            if (found.isEmpty()) {
                result = ActionResult.failure("缺少木板，附近找不到可砍的树");
                return;
            }
            woodTarget = found.get();
            woodBreakTicks = 0;
            resetTargetMovement();
        }

        if (!SurvivalUtils.moveNear(aiPlayer, woodTarget, 4.5D)) {
            woodBreakTicks = 0;
            trackMovementToTarget();
            return;
        }
        resetTargetMovement();

        aiPlayer.lookAtWorkTarget(woodTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        woodBreakTicks++;
        int breakDelay = aiPlayer.getBestToolStackFor("axe").isEmpty() ? 60 : 24;
        if (woodBreakTicks < breakDelay) {
            return;
        }

        boolean broken = SurvivalUtils.breakBlock(aiPlayer, woodTarget);
        if (!broken) {
            rejectCurrentTarget();
            return;
        }
        woodTarget = null;
        woodBreakTicks = 0;
        aiPlayer.craftPlanksFromLogs(requiredPlanksForDoor());
    }

    private boolean isWoodenDoor() {
        return itemName.contains("door") || itemName.contains("木门") || itemName.equals("门");
    }

    private int requiredPlanksForDoor() {
        int missingDoors = Math.max(0, quantity - aiPlayer.getWoodenDoorCount());
        int craftBatches = Math.max(1, (missingDoors + 2) / 3);
        return craftBatches * 6;
    }

    private void trackMovementToTarget() {
        if (woodTarget == null) {
            return;
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(woodTarget));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return;
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isDone()) {
            rejectCurrentTarget();
        }
    }

    private void rejectCurrentTarget() {
        if (woodTarget != null) {
            rejectedTargets.add(woodTarget);
        }
        woodTarget = null;
        woodBreakTicks = 0;
        resetTargetMovement();
        if (rejectedTargets.size() > MAX_REJECTED_TARGETS) {
            result = ActionResult.failure("附近的树暂时无法到达，无法继续制作木门");
        }
    }

    private void resetTargetMovement() {
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
    }
}
