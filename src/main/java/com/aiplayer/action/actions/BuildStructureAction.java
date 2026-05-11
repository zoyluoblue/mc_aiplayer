package com.aiplayer.action.actions;

import com.aiplayer.action.ActionResult;
import com.aiplayer.action.Task;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.structure.BlockPlacement;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class BuildStructureAction extends BaseAction {
    private String structureType;
    private List<BlockPlacement> buildPlan;
    private int currentBlockIndex;
    private int ticksRunning;
    private int actionTicks;
    private BlockPos woodTarget;
    private int woodBreakTicks;
    private static final int MAX_TICKS = 120000;
    private static final int PLACE_DELAY = 10;

    public BuildStructureAction(AiPlayerEntity aiPlayer, Task task) {
        super(aiPlayer, task);
    }

    @Override
    protected void onStart() {
        structureType = task.getStringParameter("structure", "house").toLowerCase();
        currentBlockIndex = 0;
        ticksRunning = 0;
        actionTicks = 0;
        woodBreakTicks = 0;

        int width = Math.min(Math.max(task.getIntParameter("width", 5), 5), 7);
        int height = Math.min(Math.max(task.getIntParameter("height", 3), 3), 4);
        int depth = Math.min(Math.max(task.getIntParameter("depth", 5), 5), 7);
        BlockPos start = findBuildStart(width, depth);
        if (start == null) {
            result = ActionResult.failure("附近找不到适合建造的小块平地");
            return;
        }

        buildPlan = createSurvivalShelter(start, width, height, depth);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("建造超时");
            return;
        }

        if (!hasRequiredMaterials()) {
            gatherWoodTick();
            return;
        }

        if (currentBlockIndex >= buildPlan.size()) {
            result = ActionResult.success("已像生存玩家一样完成 " + structureType);
            return;
        }

        BlockPlacement placement = buildPlan.get(currentBlockIndex);
        BlockState currentState = aiPlayer.level().getBlockState(placement.pos);
        if (!currentState.isAir() && currentState.getBlock() == placement.block) {
            currentBlockIndex++;
            return;
        }
        if (!currentState.isAir() && currentState.getBlock() != Blocks.SHORT_GRASS && currentState.getBlock() != Blocks.TALL_GRASS) {
            currentBlockIndex++;
            return;
        }

        if (!SurvivalUtils.moveNear(aiPlayer, placement.pos, 4.0)) {
            actionTicks = 0;
            return;
        }

        aiPlayer.lookAtWorkTarget(placement.pos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        actionTicks++;
        if (actionTicks < PLACE_DELAY) {
            return;
        }

        if (SurvivalUtils.placeBlock(aiPlayer, placement.pos, placement.block)) {
            currentBlockIndex++;
            actionTicks = 0;
        } else {
            actionTicks = 0;
        }
    }

    @Override
    protected void onCancel() {
        aiPlayer.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "生存建造 " + structureType + "：" + currentBlockIndex + "/" + (buildPlan != null ? buildPlan.size() : 0);
    }

    private boolean hasRequiredMaterials() {
        int neededPlanks = 0;
        for (int i = currentBlockIndex; i < buildPlan.size(); i++) {
            if (buildPlan.get(i).block == Blocks.OAK_PLANKS) {
                neededPlanks++;
            }
        }
        aiPlayer.craftPlanksFromLogs(neededPlanks);
        return aiPlayer.getItemCount(Items.OAK_PLANKS) >= neededPlanks;
    }

    private void gatherWoodTick() {
        aiPlayer.craftPlanksFromLogs(requiredRemainingPlanks());
        if (hasRequiredMaterials()) {
            return;
        }

        aiPlayer.craftWoodenAxe();
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("axe"));

        if (woodTarget == null || aiPlayer.level().getBlockState(woodTarget).isAir()) {
            Optional<BlockPos> found = SurvivalUtils.findNearestBlock(aiPlayer, state -> SurvivalUtils.isLog(state.getBlock()), 32, 12);
            if (found.isEmpty()) {
                result = ActionResult.failure("附近找不到树木，无法从零开始收集木材");
                return;
            }
            woodTarget = found.get();
            woodBreakTicks = 0;
        }

        if (!SurvivalUtils.moveNear(aiPlayer, woodTarget, 3.0)) {
            woodBreakTicks = 0;
            return;
        }

        aiPlayer.lookAtWorkTarget(woodTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        woodBreakTicks++;
        int delay = aiPlayer.getBestToolStackFor("axe").isEmpty() ? 60 : 24;
        if (woodBreakTicks < delay) {
            return;
        }

        SurvivalUtils.breakBlock(aiPlayer, woodTarget);
        aiPlayer.craftPlanksFromLogs(requiredRemainingPlanks());
        woodTarget = null;
        woodBreakTicks = 0;
    }

    private int requiredRemainingPlanks() {
        int needed = 0;
        if (buildPlan == null) {
            return 0;
        }
        for (int i = currentBlockIndex; i < buildPlan.size(); i++) {
            if (buildPlan.get(i).block == Blocks.OAK_PLANKS) {
                needed++;
            }
        }
        return needed;
    }

    private BlockPos findBuildStart(int width, int depth) {
        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        BlockPos origin = nearestPlayer == null ? aiPlayer.blockPosition() : nearestPlayer.blockPosition();
        net.minecraft.world.phys.Vec3 look = nearestPlayer == null ? aiPlayer.getLookAngle() : nearestPlayer.getLookAngle();
        BlockPos preferred = origin.offset((int) Math.round(look.x * 8), 0, (int) Math.round(look.z * 8));
        for (int radius = 0; radius <= 12; radius += 3) {
            for (int dx = -radius; dx <= radius; dx += 3) {
                for (int dz = -radius; dz <= radius; dz += 3) {
                    BlockPos candidate = findGround(preferred.offset(dx, 0, dz));
                    if (candidate != null && isAreaUsable(candidate, width, depth)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos findGround(BlockPos pos) {
        for (int y = 8; y >= -8; y--) {
            BlockPos check = pos.offset(0, y, 0);
            if (aiPlayer.level().getBlockState(check).isAir() && aiPlayer.level().getBlockState(check.below()).isSolid()) {
                return check;
            }
        }
        return null;
    }

    private boolean isAreaUsable(BlockPos start, int width, int depth) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                BlockPos floor = start.offset(x, -1, z);
                BlockPos space = start.offset(x, 0, z);
                if (!aiPlayer.level().getBlockState(floor).isSolid()) {
                    return false;
                }
                Block block = aiPlayer.level().getBlockState(space).getBlock();
                if (!aiPlayer.level().getBlockState(space).isAir() && block != Blocks.SHORT_GRASS && block != Blocks.TALL_GRASS) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<BlockPlacement> createSurvivalShelter(BlockPos start, int width, int height, int depth) {
        List<BlockPlacement> blocks = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, 0, z), Blocks.OAK_PLANKS));
            }
        }
        for (int y = 1; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                if (!(x == width / 2 && y <= 2)) {
                    blocks.add(new BlockPlacement(start.offset(x, y, 0), Blocks.OAK_PLANKS));
                }
                blocks.add(new BlockPlacement(start.offset(x, y, depth - 1), Blocks.OAK_PLANKS));
            }
            for (int z = 1; z < depth - 1; z++) {
                blocks.add(new BlockPlacement(start.offset(0, y, z), Blocks.OAK_PLANKS));
                blocks.add(new BlockPlacement(start.offset(width - 1, y, z), Blocks.OAK_PLANKS));
            }
        }
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new BlockPlacement(start.offset(x, height + 1, z), Blocks.OAK_PLANKS));
            }
        }
        return blocks;
    }

    private net.minecraft.world.entity.player.Player findNearestPlayer() {
        java.util.List<? extends net.minecraft.world.entity.player.Player> players = aiPlayer.level().players();
        net.minecraft.world.entity.player.Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (net.minecraft.world.entity.player.Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            double distance = aiPlayer.distanceTo(player);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }
}
