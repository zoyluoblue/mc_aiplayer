package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.recipe.MaterialRequirement;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class StepExecutor {
    private static final int MAX_STEP_TICKS = 6000;
    private static final int TARGET_MOVE_TIMEOUT_TICKS = 200;
    private static final int CHEST_RADIUS = 12;
    private static final int STONE_DESCENT_MAX_BLOCKS = 32;
    private static final int DOWNWARD_DIG_REACH = 5;
    private static final int STONE_NO_PROGRESS_TIMEOUT_TICKS = 2400;
    private static final int ORE_PROSPECT_TIMEOUT_TICKS = 3600;
    private static final int ORE_TARGET_NO_PROGRESS_TIMEOUT_TICKS = 1200;
    private static final int ORE_SCAN_HORIZONTAL_RADIUS = 24;
    private static final int ORE_SCAN_VERTICAL_RADIUS = 16;

    private final AiPlayerEntity aiPlayer;
    private final ResourceGatherSession resourceGatherSession;
    private final GoalChecker goalChecker = new GoalChecker();
    private ExecutionStep activeStep;
    private ResourceGatherSession.ResourceState activeResourceState;
    private BlockPos targetPos;
    private Entity targetEntity;
    private BlockPos descentMoveTarget;
    private Direction descentDirection;
    private int ticks;
    private int breakTicks;
    private int craftTicks;
    private int startCount;
    private int downwardDigBlocks;
    private int lastProgressCount;
    private int ticksWithoutItemProgress;
    private boolean downwardStoneMode;
    private int moveTicksWithoutProgress;
    private double closestTargetDistanceSq;
    private final Set<BlockPos> rejectedTargets = new HashSet<>();

    public StepExecutor(AiPlayerEntity aiPlayer) {
        this(aiPlayer, new ResourceGatherSession());
    }

    public StepExecutor(AiPlayerEntity aiPlayer, ResourceGatherSession resourceGatherSession) {
        this.aiPlayer = aiPlayer;
        this.resourceGatherSession = resourceGatherSession == null ? new ResourceGatherSession() : resourceGatherSession;
    }

    public StepResult tick(ExecutionStep step) {
        if (step == null) {
            return StepResult.failure("没有可执行 step");
        }
        if (activeStep != step) {
            startStep(step);
        }
        ticks++;
        if (ticks > MAX_STEP_TICKS) {
            return StepResult.stuck("step 超时：" + step.describe());
        }

        return switch (step.getStep()) {
            case "gather_tree" -> tickGatherLog(step);
            case "gather_stone" -> tickGatherStone(step);
            case "gather" -> tickGather(step);
            case "craft_inventory", "craft_station" -> tickCraft(step);
            case "fill_water" -> tickFillWater(step);
            case "withdraw_chest" -> tickWithdraw(step);
            case "return_to_owner" -> tickReturnToOwner();
            default -> StepResult.failure("暂不支持执行 step：" + step.getStep());
        };
    }

    public void cancel() {
        aiPlayer.getNavigation().stop();
    }

    private void startStep(ExecutionStep step) {
        activeStep = step;
        targetPos = null;
        targetEntity = null;
        descentMoveTarget = null;
        descentDirection = null;
        ticks = 0;
        breakTicks = 0;
        craftTicks = 0;
        downwardDigBlocks = 0;
        startCount = goalChecker.countItem(aiPlayer, step.getItem());
        lastProgressCount = startCount;
        ticksWithoutItemProgress = 0;
        downwardStoneMode = false;
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
        rejectedTargets.clear();
        activeResourceState = resolveResourceState(step);
        if (activeResourceState != null) {
            rejectedTargets.addAll(activeResourceState.getRejectedTargets());
            if ("stone".equals(activeResourceState.getResourceKey()) && activeResourceState.shouldReuseDownwardMode()) {
                downwardStoneMode = true;
                AiPlayerMod.debug("make_item", "AiPlayer '{}' reusing resource session: resource={}, anchor={}, lastStand={}, lastSuccess={}, successCount={}",
                    aiPlayer.getAiPlayerName(),
                    activeResourceState.getResourceKey(),
                    activeResourceState.getAnchor(),
                    activeResourceState.getLastStandPos(),
                    activeResourceState.getLastSuccessPos(),
                    activeResourceState.getSuccessfulBreaks());
            }
        }
    }

    private StepResult tickGatherLog(ExecutionStep step) {
        if (goalChecker.countItem(aiPlayer, step.getItem()) >= startCount + step.getCount()) {
            return StepResult.success("已采集 " + step.getItem() + " x" + step.getCount());
        }
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("axe"));
        Item targetItem = itemFromId(step.getItem());
        if (targetPos == null || aiPlayer.level().getBlockState(targetPos).isAir()) {
            Optional<BlockPos> found = SurvivalUtils.findNearestBlock(aiPlayer,
                (pos, state) -> !rejectedTargets.contains(pos) && isMatchingLog(state.getBlock(), targetItem),
                32,
                12);
            if (found.isEmpty()) {
                return StepResult.failure("附近找不到可砍的树");
            }
            targetPos = found.get();
            resetMovement();
        }
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 4.5D)) {
            breakTicks = 0;
            return trackGatherMovement(step, "树木");
        }
        resetMovement();
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        int breakDelay = aiPlayer.getBestToolStackFor("axe").isEmpty() ? 60 : 24;
        if (breakTicks < breakDelay) {
            return StepResult.running("正在砍树");
        }
        if (!SurvivalUtils.breakBlock(aiPlayer, targetPos)) {
            targetPos = null;
            return StepResult.running("重新寻找树木");
        }
        targetPos = null;
        breakTicks = 0;
        return StepResult.running("已砍下一块原木");
    }

    private StepResult tickGatherStone(ExecutionStep step) {
        int currentCount = goalChecker.countItem(aiPlayer, step.getItem());
        if (currentCount >= startCount + step.getCount()) {
            return StepResult.success("已采集 " + step.getItem() + " x" + step.getCount());
        }
        trackItemProgress(currentCount);
        if (ticksWithoutItemProgress > STONE_NO_PROGRESS_TIMEOUT_TICKS) {
            return StepResult.terminalFailure("采集石头长时间没有进展，请移动到露天石头、矿洞或给 AI 放入圆石");
        }
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            aiPlayer.craftWoodenPickaxe();
        }
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            return StepResult.failure("缺少镐，无法挖石头");
        }
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        if (downwardStoneMode) {
            return tickDigDownForStone(step);
        }
        if (targetPos == null || aiPlayer.level().getBlockState(targetPos).isAir()) {
            Optional<BlockPos> found = findReachableStoneTarget();
            if (found.isEmpty()) {
                downwardStoneMode = true;
                if (activeResourceState != null) {
                    activeResourceState.markDownwardMode();
                }
                AiPlayerMod.debug("make_item", "AiPlayer '{}' enters downward stone search from {} with {}/{} {}",
                    aiPlayer.getAiPlayerName(),
                    aiPlayer.blockPosition(),
                    currentCount - startCount,
                    step.getCount(),
                    step.getItem());
                return tickDigDownForStone(step);
            }
            targetPos = found.get();
            resetMovement();
            ticksWithoutItemProgress = 0;
            lastProgressCount = currentCount;
        }
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 3.0D)) {
            breakTicks = 0;
            return trackGatherMovement(step, "石头");
        }
        resetMovement();
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < 36) {
            return StepResult.running("正在挖石头");
        }
        if (!SurvivalUtils.breakBlock(aiPlayer, targetPos)) {
            targetPos = null;
            return StepResult.running("重新寻找石头");
        }
        if (activeResourceState != null) {
            activeResourceState.recordSuccess(targetPos, aiPlayer.blockPosition());
        }
        targetPos = null;
        breakTicks = 0;
        return StepResult.running("已挖下一块石头");
    }

    private StepResult tickDigDownForStone(ExecutionStep step) {
        if (downwardDigBlocks >= STONE_DESCENT_MAX_BLOCKS) {
            return StepResult.terminalFailure("向下搜索 " + STONE_DESCENT_MAX_BLOCKS + " 个方块仍未找到可采集石头");
        }

        if (descentMoveTarget != null) {
            if (!canStandAt(descentMoveTarget)) {
                descentMoveTarget = null;
            } else if (!SurvivalUtils.moveNear(aiPlayer, descentMoveTarget, 1.5D)) {
                return StepResult.running("正在移动到可继续下挖的位置");
            } else {
                descentMoveTarget = null;
                resetMovement();
                return StepResult.running("已到达可继续下挖的位置");
            }
        }

        Optional<BlockPos> lowerStandableBeforeDig = findLowerStandableTarget();
        if (lowerStandableBeforeDig.isPresent()) {
            descentMoveTarget = lowerStandableBeforeDig.get();
            resetMovement();
            return StepResult.running("正在移动到阶梯下方的可站立位置");
        }

        BlockPos digTarget = findDownwardDigTarget();
        if (digTarget == null) {
            Optional<BlockPos> lowerStandable = findLowerStandableTarget();
            if (lowerStandable.isPresent()) {
                descentMoveTarget = lowerStandable.get();
                resetMovement();
                return StepResult.running("正在寻找下方可站立位置继续挖石头");
            }
            return StepResult.terminalFailure("附近和向下搜索范围内没有找到可挖石头，请移动到矿洞、低处或给 AI 放入圆石");
        }
        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        if (SurvivalUtils.requiresPickaxe(block)) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        }
        if (ticks % 40 == 0) {
            AiPlayerMod.debug("make_item", "AiPlayer '{}' downward stone search target={} block={} dug={} noProgressTicks={}",
                aiPlayer.getAiPlayerName(), digTarget, block, downwardDigBlocks, ticksWithoutItemProgress);
        }
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < (SurvivalUtils.requiresPickaxe(block) ? 36 : 20)) {
            return StepResult.running("正在向下挖找石头");
        }
        if (!SurvivalUtils.breakBlock(aiPlayer, digTarget)) {
            breakTicks = 0;
            return StepResult.running("重新选择向下挖的位置");
        }
        breakTicks = 0;
        if (SurvivalUtils.isStone(block)) {
            if (activeResourceState != null) {
                activeResourceState.recordSuccess(digTarget, aiPlayer.blockPosition());
            }
        } else {
            if (activeResourceState != null) {
                activeResourceState.recordProgress(aiPlayer.blockPosition());
            }
            downwardDigBlocks++;
        }
        return StepResult.running(SurvivalUtils.isStone(block) ? "已向下挖到石头" : "正在挖开覆盖层寻找石头");
    }

    private Optional<BlockPos> findReachableStoneTarget() {
        Optional<BlockPos> direct = SurvivalUtils.findNearestBlock(aiPlayer,
            (pos, state) -> isStoneCandidate(pos, state.getBlock()) && canWorkBlockDirectly(pos, 3.2D),
            24,
            8);
        if (direct.isPresent()) {
            return direct;
        }
        return SurvivalUtils.findNearestBlock(aiPlayer,
            (pos, state) -> isStoneCandidate(pos, state.getBlock()) && hasPathToBlock(pos),
            24,
            8);
    }

    private boolean isStoneCandidate(BlockPos pos, Block block) {
        return !rejectedTargets.contains(pos)
            && SurvivalUtils.isStone(block)
            && hasAirNeighbor(pos);
    }

    private boolean canWorkBlockDirectly(BlockPos pos, double range) {
        return aiPlayer.position().distanceToSqr(Vec3.atCenterOf(pos)) <= range * range;
    }

    private boolean hasPathToBlock(BlockPos pos) {
        return aiPlayer.getNavigation().createPath(pos, 1) != null;
    }

    private void trackItemProgress(int currentCount) {
        if (currentCount > lastProgressCount) {
            lastProgressCount = currentCount;
            ticksWithoutItemProgress = 0;
            if (activeResourceState != null) {
                activeResourceState.recordProgress(aiPlayer.blockPosition());
            }
            return;
        }
        ticksWithoutItemProgress++;
    }

    private StepResult tickGather(ExecutionStep step) {
        if ("minecraft:raw_iron".equals(step.getItem()) || "iron_ore".equals(step.getResource())) {
            return tickGatherOre(step, "iron", Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, "stone");
        }
        if ("minecraft:raw_copper".equals(step.getItem()) || "copper_ore".equals(step.getResource())) {
            return tickGatherOre(step, "copper", Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, "stone");
        }
        if ("minecraft:raw_gold".equals(step.getItem()) || "gold_ore".equals(step.getResource())) {
            return tickGatherOre(step, "gold", Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, "iron");
        }
        if ("minecraft:diamond".equals(step.getItem()) || "diamond_ore".equals(step.getResource())) {
            return tickGatherOre(step, "diamond", Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, "iron");
        }
        if ("minecraft:redstone".equals(step.getItem()) || "redstone_ore".equals(step.getResource())) {
            return tickGatherOre(step, "redstone", Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, "iron");
        }
        if ("minecraft:lapis_lazuli".equals(step.getItem()) || "lapis_ore".equals(step.getResource())) {
            return tickGatherOre(step, "lapis", Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, "stone");
        }
        if ("minecraft:emerald".equals(step.getItem()) || "emerald_ore".equals(step.getResource())) {
            return tickGatherOre(step, "emerald", Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, "iron");
        }
        if ("minecraft:coal".equals(step.getItem()) || "coal_ore".equals(step.getResource())) {
            return tickGatherOre(step, "coal", Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, "wood");
        }
        if ("minecraft:cobblestone".equals(step.getItem()) || "stone".equals(step.getResource())) {
            return tickGatherStone(step);
        }
        if (step.getResource() != null && step.getResource().startsWith("block:")) {
            return tickGatherBlock(step, step.getResource().substring("block:".length()));
        }
        if (step.getResource() != null && step.getResource().startsWith("mob:")) {
            return tickGatherMob(step, step.getResource().substring("mob:".length()));
        }
        return StepResult.failure("暂不支持采集资源：" + step.getResource() + " -> " + step.getItem());
    }

    private StepResult tickGatherMob(ExecutionStep step, String entityTypeId) {
        if (goalChecker.countItem(aiPlayer, step.getItem()) >= startCount + step.getCount()) {
            return StepResult.success("已收集 " + step.getItem() + " x" + step.getCount());
        }
        Item output = itemFromId(step.getItem());
        if (output == Items.AIR) {
            return StepResult.failure("无效的生物掉落物：" + step.getItem());
        }
        if (targetEntity == null || !targetEntity.isAlive() || targetEntity.isRemoved() || !isEntityType(targetEntity, entityTypeId)) {
            targetEntity = findNearestEntity(entityTypeId).orElse(null);
            breakTicks = 0;
            if (targetEntity == null) {
                return StepResult.failure("附近找不到可收集的生物：" + entityTypeId);
            }
        }
        if (aiPlayer.distanceToSqr(targetEntity) > 9.0D) {
            aiPlayer.getNavigation().moveTo(targetEntity, 1.1D);
            return StepResult.running("正在接近 " + entityTypeId);
        }
        aiPlayer.getNavigation().stop();
        aiPlayer.getLookControl().setLookAt(targetEntity);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        if (targetEntity instanceof LivingEntity living && aiPlayer.level() instanceof ServerLevel serverLevel) {
            aiPlayer.doHurtTarget(serverLevel, living);
        }
        breakTicks++;
        if (breakTicks < 20) {
            return StepResult.running("正在获取 " + entityTypeId + " 掉落物");
        }
        targetEntity.discard();
        aiPlayer.addItem(output, SurvivalRecipeBook.sourceDropCount(step.getItem(), step.getResource()));
        targetEntity = null;
        breakTicks = 0;
        return StepResult.running("已获得一份 " + step.getItem());
    }

    private StepResult tickGatherBlock(ExecutionStep step, String blockId) {
        if (goalChecker.countItem(aiPlayer, step.getItem()) >= startCount + step.getCount()) {
            return StepResult.success("已采集 " + step.getItem() + " x" + step.getCount());
        }
        Block block = blockFromId(blockId);
        if (block == Blocks.AIR) {
            return StepResult.failure("无效的基础资源方块：" + blockId);
        }
        String requiredTool = SurvivalRecipeBook.requiredToolForBaseResource(step.getItem());
        if (requiredTool == null && SurvivalUtils.requiresPickaxe(block)) {
            requiredTool = "minecraft:wooden_pickaxe";
        }
        if (requiredTool != null && !ensureTool(requiredTool)) {
            return StepResult.failure("缺少工具，无法采集 " + step.getItem() + "，需要 " + requiredTool);
        }
        if (SurvivalUtils.requiresPickaxe(block)) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        } else if (SurvivalUtils.isLog(block)) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("axe"));
        } else {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        if (targetPos == null || aiPlayer.level().getBlockState(targetPos).isAir()) {
            Optional<BlockPos> found = SurvivalUtils.findNearestBlock(aiPlayer,
                state -> state.getBlock() == block,
                32,
                16);
            if (found.isEmpty()) {
                return StepResult.failure("附近找不到可采集的 " + blockId);
            }
            targetPos = found.get();
            resetMovement();
        }
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 3.5D)) {
            breakTicks = 0;
            return trackMovement(step);
        }
        resetMovement();
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < genericBreakDelay(block)) {
            return StepResult.running("正在采集 " + blockId);
        }
        if (!breakBlockForTarget(targetPos, itemFromId(step.getItem()), SurvivalRecipeBook.sourceDropCount(step.getItem(), step.getResource()))) {
            targetPos = null;
            return StepResult.running("重新寻找 " + blockId);
        }
        targetPos = null;
        breakTicks = 0;
        return StepResult.running("已采集一处 " + blockId);
    }

    private StepResult tickGatherOre(ExecutionStep step, String oreName, Block ore, Block deepslateOre, String requiredPickaxe) {
        int currentCount = goalChecker.countItem(aiPlayer, step.getItem());
        if (currentCount >= startCount + step.getCount()) {
            return StepResult.success("已采集 " + step.getItem() + " x" + step.getCount());
        }
        trackItemProgress(currentCount);
        if ("stone".equals(requiredPickaxe) && !hasStoneOrBetterPickaxe()) {
            aiPlayer.craftStonePickaxe();
        } else if ("wood".equals(requiredPickaxe) && aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            aiPlayer.craftWoodenPickaxe();
        }
        if ("stone".equals(requiredPickaxe) && !hasStoneOrBetterPickaxe()) {
            return StepResult.failure("缺少石镐，无法挖 " + oreName);
        }
        if ("iron".equals(requiredPickaxe) && !hasIronOrBetterPickaxe()) {
            return StepResult.failure("缺少铁镐，无法挖 " + oreName);
        }
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            return StepResult.failure("缺少镐，无法挖 " + oreName);
        }
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        if (targetPos != null && ticksWithoutItemProgress > ORE_TARGET_NO_PROGRESS_TIMEOUT_TICKS) {
            rejectTarget(targetPos);
            AiPlayerMod.debug("make_item", "AiPlayer '{}' rejected {} ore target after no item progress: target={}, pos={}, ticksWithoutProgress={}",
                aiPlayer.getAiPlayerName(), oreName, targetPos, aiPlayer.blockPosition(), ticksWithoutItemProgress);
            targetPos = null;
            resetMovement();
            ticksWithoutItemProgress = 0;
            lastProgressCount = currentCount;
            return StepResult.running(oreName + "矿长时间没有进展，正在换目标或进入探矿");
        }
        if (targetPos == null || aiPlayer.level().getBlockState(targetPos).isAir()) {
            Optional<BlockPos> found = findReachableOreTarget(ore, deepslateOre);
            if (found.isEmpty()) {
                return tickProspectForOre(step, oreName);
            }
            targetPos = found.get();
            resetMovement();
            ticksWithoutItemProgress = 0;
            lastProgressCount = currentCount;
        }
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 3.0D)) {
            breakTicks = 0;
            return trackGatherMovement(step, oreName + "矿");
        }
        resetMovement();
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < 44) {
            return StepResult.running("正在挖 " + oreName);
        }
        if (!SurvivalUtils.breakBlock(aiPlayer, targetPos)) {
            targetPos = null;
            return StepResult.running("重新寻找 " + oreName);
        }
        if (activeResourceState != null) {
            activeResourceState.recordSuccess(targetPos, aiPlayer.blockPosition());
        }
        targetPos = null;
        breakTicks = 0;
        return StepResult.running("已挖下一块 " + oreName);
    }

    private Optional<BlockPos> findReachableOreTarget(Block ore, Block deepslateOre) {
        Optional<BlockPos> direct = SurvivalUtils.findNearestBlock(aiPlayer,
            (pos, state) -> isOreCandidate(pos, state.getBlock(), ore, deepslateOre) && canWorkBlockDirectly(pos, 3.2D),
            ORE_SCAN_HORIZONTAL_RADIUS,
            ORE_SCAN_VERTICAL_RADIUS);
        if (direct.isPresent()) {
            return direct;
        }
        return SurvivalUtils.findNearestBlock(aiPlayer,
            (pos, state) -> isOreCandidate(pos, state.getBlock(), ore, deepslateOre) && hasPathToBlock(pos),
            ORE_SCAN_HORIZONTAL_RADIUS,
            ORE_SCAN_VERTICAL_RADIUS);
    }

    private boolean isOreCandidate(BlockPos pos, Block block, Block ore, Block deepslateOre) {
        return !rejectedTargets.contains(pos)
            && (block == ore || block == deepslateOre)
            && hasAirNeighbor(pos);
    }

    private StepResult tickProspectForOre(ExecutionStep step, String oreName) {
        if (ticks > ORE_PROSPECT_TIMEOUT_TICKS) {
            return StepResult.terminalFailure("探矿超时：附近和矿道内仍未找到可挖的 " + oreName + " 矿，请移动到矿洞、低处或给 AI 放入对应原矿");
        }
        downwardStoneMode = true;
        if (activeResourceState != null) {
            activeResourceState.markDownwardMode();
        }
        if (ticks % 100 == 0) {
            AiPlayerMod.debug("make_item", "AiPlayer '{}' prospecting for {}: pos={}, ticks={}, rejectedTargets={}",
                aiPlayer.getAiPlayerName(), oreName, aiPlayer.blockPosition(), ticks, rejectedTargets.size());
        }
        StepResult digResult = tickDigDownForStone(step);
        if (digResult.isRunning()) {
            return StepResult.running("正在探矿寻找 " + oreName + " 矿：" + digResult.getMessage());
        }
        if (digResult.getStatus() == StepResult.Status.FAILURE || digResult.getStatus() == StepResult.Status.STUCK) {
            return StepResult.terminalFailure("探矿失败：" + digResult.getMessage());
        }
        return digResult;
    }

    private StepResult tickCraft(ExecutionStep step) {
        craftTicks++;
        if (craftTicks < 10) {
            return StepResult.running("准备合成 " + step.getItem());
        }
        boolean crafted = craftItem(step);
        return crafted
            ? StepResult.success("已合成 " + step.getItem())
            : StepResult.failure("材料不足，无法合成 " + step.getItem());
    }

    private StepResult tickFillWater(ExecutionStep step) {
        if (goalChecker.countItem(aiPlayer, step.getItem()) >= startCount + step.getCount()) {
            return StepResult.success("已装水 " + step.getItem() + " x" + step.getCount());
        }
        if (!"minecraft:water_bucket".equals(step.getItem())) {
            return StepResult.failure("暂不支持装取：" + step.getItem());
        }
        if (!aiPlayer.hasItem(Items.BUCKET, 1)) {
            return StepResult.failure("缺少空桶，无法打水");
        }
        if (targetPos == null || !isWaterSource(targetPos)) {
            Optional<BlockPos> found = SurvivalUtils.findNearestBlock(aiPlayer,
                (pos, state) -> state.getBlock() == Blocks.WATER && state.getFluidState().isSource(),
                32,
                8);
            if (found.isEmpty()) {
                return StepResult.failure("附近找不到可打水的水源");
            }
            targetPos = found.get();
            resetMovement();
        }
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 3.0D)) {
            return trackMovement(step);
        }
        resetMovement();
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        craftTicks++;
        if (craftTicks < 20) {
            return StepResult.running("正在打水");
        }
        if (!aiPlayer.consumeItem(Items.BUCKET, 1)) {
            return StepResult.failure("空桶不足，无法打水");
        }
        aiPlayer.addItem(Items.WATER_BUCKET, 1);
        craftTicks = 0;
        return StepResult.running("已装满一桶水");
    }

    private boolean craftItem(ExecutionStep step) {
        String item = step.getItem();
        int targetCount = startCount + step.getCount();
        return craftItemToCount(item, targetCount, new HashSet<>());
    }

    private boolean craftItemToCount(String item, int targetCount, Set<String> stack) {
        if (goalChecker.countItem(aiPlayer, item) >= targetCount) {
            return true;
        }
        if (SurvivalRecipeBook.PLANK_TO_LOG.containsKey(item)) {
            return craftPlanks(item, targetCount);
        }
        if (!stack.add(item)) {
            return false;
        }
        List<SurvivalRecipeBook.Definition> definitions = SurvivalRecipeBook.findAll(aiPlayer, item);
        if (definitions.isEmpty()) {
            stack.remove(item);
            return false;
        }
        while (goalChecker.countItem(aiPlayer, item) < targetCount) {
            boolean crafted = false;
            for (SurvivalRecipeBook.Definition definition : definitions) {
                if (craftDefinitionBatch(definition, stack)) {
                    crafted = true;
                    break;
                }
            }
            if (!crafted) {
                stack.remove(item);
                return false;
            }
        }
        stack.remove(item);
        return true;
    }

    private boolean craftDefinitionBatch(SurvivalRecipeBook.Definition definition, Set<String> stack) {
        String outputItem = definition.output().getItem();
        if (SurvivalRecipeBook.PLANK_TO_LOG.containsKey(outputItem)) {
            return craftPlanks(outputItem, goalChecker.countItem(aiPlayer, outputItem) + definition.output().getCount());
        }
        if (!hasRequirements(definition.requires())) {
            return false;
        }
        Item output = itemFromId(outputItem);
        if (output == Items.AIR) {
            return false;
        }
        if (definition.stationItem() != null && !ensureStationItem(definition.stationItem(), stack)) {
            return false;
        }
        consumeRequirements(definition.requires());
        aiPlayer.addItem(output, definition.output().getCount());
        return true;
    }

    private boolean craftPlanks(String plankItem, int targetCount) {
        Item output = itemFromId(plankItem);
        if (output == Items.AIR) {
            return false;
        }
        while (goalChecker.countItem(aiPlayer, plankItem) < targetCount) {
            String logId = SurvivalRecipeBook.PLANK_TO_LOG.get(plankItem);
            Item log = itemFromId(logId);
            if (log == Items.AIR || !aiPlayer.consumeItem(log, 1)) {
                if ("minecraft:oak_planks".equals(plankItem) && aiPlayer.craftPlanksFromLogs(targetCount)) {
                    return true;
                }
                return false;
            }
            aiPlayer.addItem(output, 4);
        }
        return true;
    }

    private boolean hasRequirements(java.util.List<MaterialRequirement> requirements) {
        for (MaterialRequirement requirement : requirements) {
            if (!hasRequirement(requirement)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasRequirement(MaterialRequirement requirement) {
        if (isAnyPlankRequirement(requirement.getItem())) {
            return aiPlayer.getWoodenPlankCount() >= requirement.getCount();
        }
        Item item = itemFromId(requirement.getItem());
        return item != Items.AIR && aiPlayer.hasItem(item, requirement.getCount());
    }

    private void consumeRequirements(java.util.List<MaterialRequirement> requirements) {
        for (MaterialRequirement requirement : requirements) {
            consumeRequirement(requirement);
        }
    }

    private void consumeRequirement(MaterialRequirement requirement) {
        if (isAnyPlankRequirement(requirement.getItem())) {
            consumeAnyPlanks(requirement.getCount());
            return;
        }
        aiPlayer.consumeItem(itemFromId(requirement.getItem()), requirement.getCount());
    }

    private boolean consumeAnyPlanks(int count) {
        int remaining = Math.max(0, count);
        for (String plankId : SurvivalRecipeBook.PLANK_TO_LOG.keySet()) {
            Item plank = itemFromId(plankId);
            int available = goalChecker.countItem(aiPlayer, plankId);
            int taken = Math.min(available, remaining);
            if (taken > 0) {
                aiPlayer.consumeItem(plank, taken);
                remaining -= taken;
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
    }

    private boolean isAnyPlankRequirement(String item) {
        return SurvivalRecipeBook.isGenericWoodPlankRequirement(item);
    }

    private StepResult tickWithdraw(ExecutionStep step) {
        Item item = itemFromId(step.getItem());
        if (item == Items.AIR) {
            return StepResult.failure("箱子取物目标不是有效物品：" + step.getItem());
        }
        if (goalChecker.countItem(aiPlayer, step.getItem()) >= startCount + step.getCount()) {
            return StepResult.success("已从箱子取出 " + step.getItem());
        }
        if (targetPos == null || !containerHasItem(targetPos, item)) {
            Optional<BlockPos> found = findNearestChestWithItem(item);
            if (found.isEmpty()) {
                return StepResult.failure("附近可访问箱子里没有 " + step.getItem());
            }
            targetPos = found.get();
            resetMovement();
        }
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 4.5D)) {
            return trackMovement(step);
        }
        int remaining = startCount + step.getCount() - goalChecker.countItem(aiPlayer, step.getItem());
        int moved = transferFromChest(targetPos, item, remaining);
        return moved > 0 ? StepResult.running("已从箱子取出部分材料") : StepResult.failure("无法从箱子取出 " + step.getItem());
    }

    private StepResult tickReturnToOwner() {
        Player owner = aiPlayer.level()
            .players()
            .stream()
            .filter(player -> aiPlayer.isOwnedBy(player.getUUID()))
            .findFirst()
            .orElse(null);
        if (owner == null) {
            return StepResult.failure("找不到拥有者玩家");
        }
        if (aiPlayer.distanceToSqr(owner) <= 9.0D) {
            aiPlayer.getNavigation().stop();
            return StepResult.success("已回到玩家身边");
        }
        aiPlayer.getNavigation().moveTo(owner, 1.0D);
        return StepResult.running("正在回到玩家身边");
    }

    private StepResult trackMovement(ExecutionStep step) {
        if (targetPos == null) {
            return StepResult.running("寻找目标");
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(targetPos));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return StepResult.running("正在接近目标");
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            targetPos = null;
            return StepResult.stuck("前往 step 目标时卡住：" + step.describe());
        }
        return StepResult.running("正在接近目标");
    }

    private StepResult trackGatherMovement(ExecutionStep step, String resourceName) {
        if (targetPos == null) {
            return StepResult.running("寻找" + resourceName);
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(targetPos));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return StepResult.running("正在接近" + resourceName);
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            rejectTarget(targetPos);
            targetPos = null;
            resetMovement();
            return StepResult.running(resourceName + "位置不可达，正在换一个目标");
        }
        return StepResult.running("正在接近" + resourceName);
    }

    private void rejectTarget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        rejectedTargets.add(pos.immutable());
        if (activeResourceState != null) {
            activeResourceState.rejectTarget(pos);
        }
    }

    private ResourceGatherSession.ResourceState resolveResourceState(ExecutionStep step) {
        String resourceKey = resourceKey(step);
        if (resourceKey == null) {
            return null;
        }
        return resourceGatherSession.stateFor(resourceKey, aiPlayer.blockPosition());
    }

    private String resourceKey(ExecutionStep step) {
        if ("gather_stone".equals(step.getStep())) {
            return "stone";
        }
        if ("gather".equals(step.getStep()) && step.getResource() != null) {
            return step.getResource();
        }
        return null;
    }

    private Optional<BlockPos> findNearestChestWithItem(Item item) {
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(
                center.offset(-CHEST_RADIUS, -4, -CHEST_RADIUS),
                center.offset(CHEST_RADIUS, 4, CHEST_RADIUS)
            )
            .map(BlockPos::immutable)
            .filter(pos -> containerHasItem(pos, item))
            .sorted(Comparator.comparingDouble(pos -> pos.distSqr(center)))
            .findFirst();
    }

    private boolean containerHasItem(BlockPos pos, Item item) {
        BlockEntity blockEntity = aiPlayer.level().getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return false;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    private int transferFromChest(BlockPos pos, Item item, int count) {
        BlockEntity blockEntity = aiPlayer.level().getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return 0;
        }
        int remaining = Math.max(0, count);
        int moved = 0;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int requested = Math.min(stack.getCount(), remaining);
            int inserted = aiPlayer.addItem(item, requested);
            if (inserted <= 0) {
                break;
            }
            stack.shrink(inserted);
            if (stack.isEmpty()) {
                container.setItem(slot, ItemStack.EMPTY);
            }
            remaining -= inserted;
            moved += inserted;
        }
        if (moved > 0) {
            container.setChanged();
        }
        return moved;
    }

    private boolean ensureStationItem(String stationItem, Set<String> stack) {
        Block stationBlock = blockFromItemId(stationItem);
        if (goalChecker.hasItem(aiPlayer, stationItem, 1) || (stationBlock != Blocks.AIR && hasNearbyBlock(stationBlock, 6))) {
            return true;
        }
        return craftItemToCount(stationItem, 1, stack);
    }

    private boolean ensureTool(String itemId) {
        if (hasToolItem(itemId)) {
            return true;
        }
        return craftItemToCount(itemId, 1, new HashSet<>());
    }

    private boolean hasToolItem(String itemId) {
        return switch (itemId) {
            case "minecraft:wooden_pickaxe" -> !aiPlayer.getBestToolStackFor("pickaxe").isEmpty();
            case "minecraft:stone_pickaxe" -> hasStoneOrBetterPickaxe();
            case "minecraft:iron_pickaxe" -> hasIronOrBetterPickaxe();
            case "minecraft:diamond_pickaxe" -> hasDiamondOrBetterPickaxe();
            default -> goalChecker.hasItem(aiPlayer, itemId, 1);
        };
    }

    private boolean breakBlockForTarget(BlockPos pos, Item targetItem, int dropCount) {
        if (targetItem == Items.AIR) {
            return SurvivalUtils.breakBlock(aiPlayer, pos);
        }
        if (aiPlayer.level().getBlockState(pos).isAir() || aiPlayer.level().getBlockState(pos).getBlock() == Blocks.BEDROCK) {
            return false;
        }
        aiPlayer.lookAtWorkTarget(pos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        boolean destroyed = aiPlayer.level().destroyBlock(pos, false);
        if (destroyed) {
            aiPlayer.addItem(targetItem, Math.max(1, dropCount));
        }
        return destroyed;
    }

    private int genericBreakDelay(Block block) {
        if (SurvivalUtils.requiresPickaxe(block)) {
            return aiPlayer.getBestToolStackFor("pickaxe").isEmpty() ? 100 : 40;
        }
        if (SurvivalUtils.isLog(block)) {
            return aiPlayer.getBestToolStackFor("axe").isEmpty() ? 60 : 24;
        }
        return 24;
    }

    private boolean hasAirNeighbor(BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (aiPlayer.level().getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findDownwardDigTarget() {
        BlockPos center = aiPlayer.blockPosition();
        Direction[] directions = orderedDescentDirections();
        for (int depth = 0; depth < DOWNWARD_DIG_REACH; depth++) {
            for (Direction direction : directions) {
                BlockPos base = center.relative(direction).below(depth);
                BlockPos headClearance = base;
                BlockPos stepDown = base.below();
                BlockPos clearanceTarget = breakableStairBlock(headClearance);
                if (clearanceTarget != null) {
                    descentDirection = direction;
                    return clearanceTarget;
                }
                BlockPos stepTarget = breakableStairBlock(stepDown);
                if (stepTarget != null) {
                    descentDirection = direction;
                    return stepTarget;
                }
            }
        }
        return null;
    }

    private BlockPos breakableStairBlock(BlockPos pos) {
        if (pos.getY() <= aiPlayer.level().getMinY() || rejectedTargets.contains(pos)) {
            return null;
        }
        Block block = aiPlayer.level().getBlockState(pos).getBlock();
        if (block == Blocks.AIR || block == Blocks.BEDROCK || block == Blocks.WATER || block == Blocks.LAVA) {
            return null;
        }
        return pos;
    }

    private Direction[] orderedDescentDirections() {
        Direction primary = descentDirection == null ? horizontalDirectionFromYaw() : descentDirection;
        Direction clockwise = primary.getClockWise();
        Direction opposite = primary.getOpposite();
        Direction counterClockwise = primary.getCounterClockWise();
        return new Direction[] {primary, clockwise, opposite, counterClockwise};
    }

    private Direction horizontalDirectionFromYaw() {
        float yaw = aiPlayer.getYRot();
        int index = Math.floorMod(Math.round(yaw / 90.0F), 4);
        return switch (index) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    private Optional<BlockPos> findLowerStandableTarget() {
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(
                center.offset(-8, -12, -8),
                center.offset(8, -1, 8)
            )
            .map(BlockPos::immutable)
            .filter(this::canStandAt)
            .filter(pos -> aiPlayer.getNavigation().createPath(pos, 1) != null)
            .min(Comparator.comparingDouble(pos -> pos.distSqr(center)));
    }

    private boolean canStandAt(BlockPos pos) {
        Level level = aiPlayer.level();
        Block block = level.getBlockState(pos).getBlock();
        Block headBlock = level.getBlockState(pos.above()).getBlock();
        Block support = level.getBlockState(pos.below()).getBlock();
        return block == Blocks.AIR
            && headBlock == Blocks.AIR
            && support != Blocks.AIR
            && support != Blocks.BEDROCK
            && support != Blocks.WATER
            && support != Blocks.LAVA;
    }

    private boolean hasNearbyBlock(Block block, int radius) {
        return SurvivalUtils.findNearestBlock(aiPlayer, state -> state.getBlock() == block, radius, 4).isPresent();
    }

    private Optional<Entity> findNearestEntity(String entityTypeId) {
        AABB searchBox = aiPlayer.getBoundingBox().inflate(32.0D, 12.0D, 32.0D);
        return aiPlayer.level()
            .getEntities(aiPlayer, searchBox, entity -> isEntityType(entity, entityTypeId))
            .stream()
            .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(aiPlayer)));
    }

    private boolean isEntityType(Entity entity, String entityTypeId) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null && key.toString().equals(entityTypeId);
    }

    private boolean isWaterSource(BlockPos pos) {
        return aiPlayer.level().getBlockState(pos).getBlock() == Blocks.WATER
            && aiPlayer.level().getBlockState(pos).getFluidState().isSource();
    }

    private boolean hasStoneOrBetterPickaxe() {
        return aiPlayer.hasItem(Items.NETHERITE_PICKAXE, 1)
            || aiPlayer.hasItem(Items.DIAMOND_PICKAXE, 1)
            || aiPlayer.hasItem(Items.IRON_PICKAXE, 1)
            || aiPlayer.hasItem(Items.STONE_PICKAXE, 1);
    }

    private boolean hasIronOrBetterPickaxe() {
        return aiPlayer.hasItem(Items.NETHERITE_PICKAXE, 1)
            || aiPlayer.hasItem(Items.DIAMOND_PICKAXE, 1)
            || aiPlayer.hasItem(Items.IRON_PICKAXE, 1);
    }

    private boolean hasDiamondOrBetterPickaxe() {
        return aiPlayer.hasItem(Items.NETHERITE_PICKAXE, 1)
            || aiPlayer.hasItem(Items.DIAMOND_PICKAXE, 1);
    }

    private boolean isMatchingLog(Block block, Item targetItem) {
        if (!SurvivalUtils.isLog(block)) {
            return false;
        }
        if (targetItem == Items.AIR || targetItem == Items.OAK_LOG) {
            return true;
        }
        return block.asItem() == targetItem;
    }

    private Item itemFromId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return Items.AIR;
        }
        try {
            return BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(itemId));
        } catch (RuntimeException e) {
            return Items.AIR;
        }
    }

    private Block blockFromId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return Blocks.AIR;
        }
        try {
            return BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse(blockId));
        } catch (RuntimeException e) {
            return Blocks.AIR;
        }
    }

    private Block blockFromItemId(String itemId) {
        Item item = itemFromId(itemId);
        return item == Items.AIR ? Blocks.AIR : Block.byItem(item);
    }

    private void resetMovement() {
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
    }
}
