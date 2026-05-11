package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.agent.MiningStrategyAdvice;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.recipe.MaterialRequirement;
import com.aiplayer.recipe.MiningResource;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.snapshot.WorldSnapshot;
import com.aiplayer.util.SurvivalUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class StepExecutor {
    private static final int MAX_STEP_TICKS = 6000;
    private static final int TARGET_MOVE_TIMEOUT_TICKS = 200;
    private static final int DESCENT_MOVE_TIMEOUT_TICKS = 120;
    private static final int CHEST_RADIUS = 12;
    private static final int STONE_DESCENT_MAX_BLOCKS = 32;
    private static final int ORE_DESCENT_MAX_BLOCKS = 112;
    private static final int DOWNWARD_DIG_REACH = 5;
    private static final int STONE_NO_PROGRESS_TIMEOUT_TICKS = 2400;
    private static final int ORE_PROSPECT_TIMEOUT_TICKS = 3600;
    private static final int ORE_TARGET_NO_PROGRESS_TIMEOUT_TICKS = 1200;
    private static final int ORE_SCAN_HORIZONTAL_RADIUS = 24;
    private static final int ORE_SCAN_VERTICAL_RADIUS = 16;
    private static final int BRANCH_TUNNEL_SEGMENT_BLOCKS = 32;
    private static final int BRANCH_TUNNEL_MAX_TURNS = 4;
    private static final int BRANCH_TARGET_Y_TOLERANCE = 4;
    private static final int BRANCH_LAYER_SHIFT_BLOCKS = 8;
    private static final int MAX_BRANCH_LAYER_SHIFTS = 4;
    private static final int MIN_MINING_TOOL_DURABILITY = 12;
    private static final int MAX_SAFE_MINING_HOSTILES = 3;
    private static final float LOW_MINING_HEALTH = 8.0F;
    private static final int INVENTORY_CRAFT_DELAY_TICKS = 10;
    private static final int STATION_CRAFT_DELAY_TICKS = 24;
    private static final int SMELTING_DELAY_TICKS = 80;

    private final AiPlayerEntity aiPlayer;
    private final ResourceGatherSession resourceGatherSession;
    private final GoalChecker goalChecker = new GoalChecker();
    private final String taskId;
    private ExecutionStep activeStep;
    private ResourceGatherSession.ResourceState activeResourceState;
    private ResourceGatherSession.ResourceState sharedMiningState;
    private BlockPos targetPos;
    private Entity targetEntity;
    private BlockPos descentMoveTarget;
    private Direction descentDirection;
    private MiningResource.Profile activeMiningProfile;
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
    private final Set<BlockPos> exploredCaveEntrances = new HashSet<>();
    private MiningRun miningRun;
    private MiningRoutePlan miningRoutePlan;
    private Direction branchDirection;
    private int branchTunnelBlocks;
    private int branchTunnelTurns;
    private int branchLayerShifts;
    private boolean miningSupplyCheckDone;
    private int nextDangerCheckTick;
    private int lastMiningProgressDecisionTick;
    private BlockPos lastEmbeddedMiningTarget;
    private int lastEmbeddedMiningTargetTick;

    public StepExecutor(AiPlayerEntity aiPlayer) {
        this(aiPlayer, new ResourceGatherSession(), "task-unknown");
    }

    public StepExecutor(AiPlayerEntity aiPlayer, ResourceGatherSession resourceGatherSession) {
        this(aiPlayer, resourceGatherSession, "task-unknown");
    }

    public StepExecutor(AiPlayerEntity aiPlayer, ResourceGatherSession resourceGatherSession, String taskId) {
        this.aiPlayer = aiPlayer;
        this.resourceGatherSession = resourceGatherSession == null ? new ResourceGatherSession() : resourceGatherSession;
        this.taskId = taskId == null || taskId.isBlank() ? "task-unknown" : taskId;
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

    public void applyMiningStrategyAdvice(MiningStrategyAdvice advice) {
        if (advice == null || !advice.accepted()) {
            return;
        }
        if (advice.rejectCurrentTarget() && targetPos != null) {
            rejectTarget(targetPos, "deepseek_strategy");
            targetPos = null;
            resetMovement();
            AiPlayerMod.info("mining_strategy", "AiPlayer '{}' applied DeepSeek mining strategy: reject current target, strategy={}, message={}",
                aiPlayer.getAiPlayerName(), advice.strategy(), advice.message());
        }
        if (advice.switchToStairDescent()) {
            downwardStoneMode = true;
            targetPos = null;
            descentMoveTarget = null;
            breakTicks = 0;
            resetMovement();
            if (activeResourceState != null) {
                activeResourceState.markDownwardMode();
            }
            AiPlayerMod.info("mining_strategy", "AiPlayer '{}' applied DeepSeek mining strategy: switch to stair descent, strategy={}, message={}",
                aiPlayer.getAiPlayerName(), advice.strategy(), advice.message());
        }
    }

    private void startStep(ExecutionStep step) {
        activeStep = step;
        miningRun = null;
        miningRoutePlan = null;
        targetPos = null;
        targetEntity = null;
        descentMoveTarget = null;
        descentDirection = null;
        activeMiningProfile = MiningResource.findByItemOrSource(step.getItem(), step.getResource()).orElse(null);
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
        branchDirection = null;
        branchTunnelBlocks = 0;
        branchTunnelTurns = 0;
        branchLayerShifts = 0;
        miningSupplyCheckDone = false;
        nextDangerCheckTick = 0;
        lastMiningProgressDecisionTick = 0;
        lastEmbeddedMiningTarget = null;
        lastEmbeddedMiningTargetTick = 0;
        rejectedTargets.clear();
        exploredCaveEntrances.clear();
        activeResourceState = resolveResourceState(step);
        sharedMiningState = isMiningRunStep(step) ? resourceGatherSession.miningState(aiPlayer.blockPosition()) : null;
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
        if (isMiningRunStep(step)) {
            miningRun = MiningRun.start(taskId, aiPlayer, step, activeMiningProfile);
            miningRoutePlan = MiningRoutePlan.create(
                aiPlayer.blockPosition(),
                activeMiningProfile,
                horizontalDirectionFromYaw(),
                BRANCH_TUNNEL_SEGMENT_BLOCKS,
                BRANCH_TUNNEL_MAX_TURNS
            );
            miningRun.recordRoutePlan(miningRoutePlan);
            seedNearbyCaveEntrance(activeMiningProfile);
            reuseMiningWaypointIfUseful();
        }
    }

    private StepResult tickGatherLog(ExecutionStep step) {
        if (goalChecker.countItem(aiPlayer, step.getItem()) >= startCount + step.getCount()) {
            return StepResult.success("已采集 " + step.getItem() + " x" + step.getCount());
        }
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
        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 4.5D)) {
            breakTicks = 0;
            return trackGatherMovement(step, "树木");
        }
        resetMovement();
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        int breakDelay = SurvivalUtils.getBreakDelay(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
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
        StepResult danger = checkMiningDanger("采集石头");
        if (danger != null) {
            return danger;
        }
        int currentCount = goalChecker.countItem(aiPlayer, step.getItem());
        if (currentCount >= startCount + step.getCount()) {
            return miningSuccess("已采集 " + step.getItem() + " x" + step.getCount());
        }
        trackItemProgress(currentCount);
        if (ticksWithoutItemProgress > STONE_NO_PROGRESS_TIMEOUT_TICKS) {
            return miningTerminalFailure("stone_no_progress", "采集石头长时间没有进展，请移动到露天石头、矿洞或给 AI 放入圆石");
        }
        setMiningState(MiningState.PREPARE_TOOLS, "ensure_pickaxe_for_stone");
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            aiPlayer.craftWoodenPickaxe();
        }
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            return StepResult.failure("缺少镐，无法挖石头");
        }
        if (!ensureMiningInventorySpace(itemFromId(step.getItem()), "采集石头")) {
            return miningTerminalFailure("inventory_full", "AI 背包已满，无法继续采集石头，请先取出材料或给附近箱子留出存放流程");
        }
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
        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        setMiningState(MiningState.TRAVEL_TO_ORE, "move_to_stone");
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 3.0D)) {
            breakTicks = 0;
            return trackGatherMovement(step, "石头");
        }
        resetMovement();
        setMiningState(MiningState.MINING, "mine_stone");
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
        setMiningState(MiningState.DESCEND, isProspectingForOre() ? "prospect_descent" : "stone_descent");
        int descentLimit = currentDescentLimit();
        if (downwardDigBlocks >= descentLimit) {
            return miningTerminalFailure("descent_limit", "向下搜索 " + descentLimit + " 个方块仍未找到可采集目标");
        }

        if (descentMoveTarget != null) {
            StepResult moveResult = tickDescentMoveTarget(
                isProspectingForOre() ? "prospect_move_lower" : "descent_move_target",
                "正在移动到可继续下挖的位置",
                "已到达可继续下挖的位置",
                "下方站位不可达，改为就地挖阶梯"
            );
            if (moveResult != null) {
                return moveResult;
            }
        }

        BlockPos digTarget = findDownwardDigTarget();
        if (digTarget == null) {
            Optional<BlockPos> lowerStandable = findLowerStandableTarget();
            if (lowerStandable.isPresent()) {
                setDescentMoveTarget(lowerStandable.get(), isProspectingForOre() ? "prospect_find_lower_stand" : "stone_find_lower_stand");
                return StepResult.running("正在寻找下方可站立位置继续挖石头");
            }
            return miningTerminalFailure("no_downward_target", "附近和向下搜索范围内没有找到可挖石头，请移动到矿洞、低处或给 AI 放入圆石");
        }
        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        String targetDanger = dangerAroundDigTarget(digTarget);
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, digTarget, "skip_descent_target");
            rejectTarget(digTarget, targetDanger);
            breakTicks = 0;
            return StepResult.running("下挖目标附近有危险：" + targetDanger + "，正在换位置");
        }
        recordMiningDigAttempt(digTarget, block);
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        if (ticks % 40 == 0) {
            AiPlayerMod.debug("make_item", "AiPlayer '{}' downward stone search target={} block={} dug={} noProgressTicks={}",
                aiPlayer.getAiPlayerName(), digTarget, block, downwardDigBlocks, ticksWithoutItemProgress);
        }
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            return StepResult.running("正在向下挖找石头");
        }
        if (!SurvivalUtils.breakBlock(aiPlayer, digTarget)) {
            breakTicks = 0;
            recordMiningDigResult(digTarget, block, false);
            return StepResult.running("重新选择向下挖的位置");
        }
        recordMiningDigResult(digTarget, block, true);
        breakTicks = 0;
        if (SurvivalUtils.isStone(block)) {
            if (activeResourceState != null) {
                activeResourceState.recordSuccess(digTarget, aiPlayer.blockPosition());
            }
            recordMiningWaypoint(isProspectingForOre() ? "ore_descent" : "stone_descent");
            if (isProspectingForOre()) {
                downwardDigBlocks++;
            }
        } else {
            if (activeResourceState != null) {
                activeResourceState.recordProgress(aiPlayer.blockPosition());
            }
            recordMiningWaypoint("clearing_descent");
            downwardDigBlocks++;
        }
        recordCaveEntranceIfFound(digTarget);
        return StepResult.running(SurvivalUtils.isStone(block) ? "已向下挖到石头" : "正在挖开覆盖层寻找石头");
    }

    private int currentDescentLimit() {
        if (isProspectingForOre() && activeMiningProfile.descentBudget() > 0) {
            return activeMiningProfile.descentBudget();
        }
        return isProspectingForOre() ? ORE_DESCENT_MAX_BLOCKS : STONE_DESCENT_MAX_BLOCKS;
    }

    private boolean isProspectingForOre() {
        return activeMiningProfile != null && activeMiningProfile.prospectable();
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
        if ("minecraft:cobblestone".equals(step.getItem()) || "stone".equals(step.getResource())) {
            return tickGatherStone(step);
        }
        Optional<MiningResource.Profile> miningResource = MiningResource.findByItemOrSource(step.getItem(), step.getResource());
        if (miningResource.isPresent()) {
            return tickGatherMiningResource(step, miningResource.get());
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
        aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("weapon"));
        aiPlayer.lookAtWorkTarget(targetEntity);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        if (breakTicks % 10 == 0 && targetEntity instanceof LivingEntity living && aiPlayer.level() instanceof ServerLevel serverLevel) {
            aiPlayer.doHurtTarget(serverLevel, living);
            aiPlayer.damageBestTool("weapon", 1);
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
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
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
        if (breakTicks < genericBreakDelay(aiPlayer.level().getBlockState(targetPos).getBlock())) {
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

    private StepResult tickGatherMiningResource(ExecutionStep step, MiningResource.Profile profile) {
        StepResult danger = checkMiningDanger("采集" + profile.displayName());
        if (danger != null) {
            return danger;
        }
        String resourceName = profile.displayName();
        int currentCount = goalChecker.countItem(aiPlayer, step.getItem());
        if (currentCount >= startCount + step.getCount()) {
            return miningSuccess("已采集 " + step.getItem() + " x" + step.getCount());
        }
        trackItemProgress(currentCount);
        String currentDimension = aiPlayer.level().dimension().location().toString();
        if (!profile.allowsDimension(currentDimension)) {
            setMiningState(MiningState.WAITING_FOR_PLAYER, "wrong_dimension:" + currentDimension);
            return miningTerminalFailure("wrong_dimension", resourceName + " 需要在 " + profile.dimension() + " 获取，当前维度是 " + currentDimension);
        }
        String requiredTool = profile.requiredTool();
        setMiningState(MiningState.PREPARE_TOOLS, "ensure_tool:" + requiredTool);
        if (requiredTool != null && !ensureTool(requiredTool)) {
            return miningFailure("missing_tool", "缺少工具，无法挖 " + resourceName + "，需要 " + requiredTool);
        }
        if (!hasEnoughMiningToolDurability(profile)) {
            setMiningState(MiningState.WAITING_FOR_PLAYER, "low_tool_durability:" + requiredTool);
            return miningFailure("low_tool_durability", "当前镐耐久低于 " + MIN_MINING_TOOL_DURABILITY + "，先停止挖 " + resourceName + "，避免挖到一半工具损坏");
        }
        prepareMiningSupplies(profile);
        if (!ensureMiningInventorySpace(itemFromId(step.getItem()), "采集" + resourceName)) {
            return miningTerminalFailure("inventory_full", "AI 背包已满，无法继续采集 " + resourceName + "，请先取出材料或在附近提供箱子");
        }
        if (shouldDescendTowardPreferredHeight(profile)) {
            setMiningState(MiningState.DESCEND, "preferred_height:" + profile.preferredYText());
            downwardStoneMode = true;
            if (activeResourceState != null) {
                activeResourceState.markDownwardMode();
            }
            if (ticks % 100 == 0) {
                AiPlayerMod.info("make_item", "AiPlayer '{}' descending for {} preferred Y range {} from y={} route={}",
                    aiPlayer.getAiPlayerName(), resourceName, profile.preferredYText(), aiPlayer.blockPosition().getY(), profile.routeHint());
            }
            markMiningMode("preferred_height_descent");
            return tickProspectForOre(step, resourceName);
        }
        if (targetPos != null && ticksWithoutItemProgress > ORE_TARGET_NO_PROGRESS_TIMEOUT_TICKS) {
            rejectTarget(targetPos, "no_item_progress");
            AiPlayerMod.debug("make_item", "AiPlayer '{}' rejected {} target after no item progress: target={}, pos={}, ticksWithoutProgress={}",
                aiPlayer.getAiPlayerName(), resourceName, targetPos, aiPlayer.blockPosition(), ticksWithoutItemProgress);
            targetPos = null;
            resetMovement();
            ticksWithoutItemProgress = 0;
            lastProgressCount = currentCount;
            return StepResult.running(resourceName + "长时间没有进展，正在换目标或进入探矿");
        }
        List<Block> blocks = miningBlocks(profile);
        if (targetPos == null || aiPlayer.level().getBlockState(targetPos).isAir()) {
            Optional<BlockPos> found = findReachableMiningTarget(blocks);
            if (found.isEmpty()) {
                if (profile.prospectable()) {
                    if (shouldRequestHigherSearchArea(profile)) {
                        setMiningState(MiningState.WAITING_FOR_PLAYER, "below_preferred_height");
                        return miningTerminalFailure("below_preferred_height", resourceName + " 推荐在 " + profile.preferredYText()
                            + " 高度或特殊环境搜索，当前 y=" + aiPlayer.blockPosition().getY()
                            + "，请移动到更合适区域后重试");
                    }
                    return tickProspectForOre(step, resourceName);
                }
                return miningTerminalFailure("not_prospectable_no_target", "附近找不到可采集的 " + resourceName);
            }
            targetPos = found.get();
            if (miningRun != null) {
                miningRun.recordTargetSelected(ticks, targetPos, aiPlayer.level().getBlockState(targetPos).getBlock(), "visible_ore");
            }
            resetMovement();
            ticksWithoutItemProgress = 0;
            lastProgressCount = currentCount;
        }
        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        setMiningState(MiningState.TRAVEL_TO_ORE, "move_to_visible_ore");
        if (!SurvivalUtils.moveNear(aiPlayer, targetPos, 3.0D)) {
            breakTicks = 0;
            return trackGatherMovement(step, resourceName);
        }
        resetMovement();
        setMiningState(MiningState.MINING, "mine_visible_ore");
        String targetDanger = dangerAroundDigTarget(targetPos);
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, targetPos, "skip_visible_ore");
            rejectTarget(targetPos, targetDanger);
            targetPos = null;
            breakTicks = 0;
            return StepResult.running(resourceName + " 附近有危险，正在换目标");
        }
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        Block targetBlock = aiPlayer.level().getBlockState(targetPos).getBlock();
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, targetBlock)) {
            return StepResult.running("正在挖 " + resourceName);
        }
        BlockPos minedPos = targetPos;
        if (!SurvivalUtils.breakBlock(aiPlayer, minedPos)) {
            if (miningRun != null) {
                miningRun.recordDigResult(ticks, "visible_ore", minedPos, targetBlock, false, 0, new TreeMap<>(aiPlayer.getInventorySnapshot()));
            }
            targetPos = null;
            return StepResult.running("重新寻找 " + resourceName);
        }
        if (miningRun != null) {
            miningRun.recordDigResult(ticks, "visible_ore", minedPos, targetBlock, true, countAirNeighbors(minedPos), new TreeMap<>(aiPlayer.getInventorySnapshot()));
        }
        if (activeResourceState != null) {
            activeResourceState.recordSuccess(minedPos, aiPlayer.blockPosition());
        }
        targetPos = findAdjacentMiningTarget(minedPos, blocks).orElse(null);
        if (targetPos != null && miningRun != null) {
            miningRun.recordTargetSelected(ticks, targetPos, aiPlayer.level().getBlockState(targetPos).getBlock(), "vein_follow");
        }
        breakTicks = 0;
        return StepResult.running("已挖下一块 " + resourceName);
    }

    private List<Block> miningBlocks(MiningResource.Profile profile) {
        return profile.blockIds().stream()
            .map(this::blockFromId)
            .filter(block -> block != Blocks.AIR)
            .toList();
    }

    private boolean shouldDescendTowardPreferredHeight(MiningResource.Profile profile) {
        return profile.prospectable()
            && profile.hasPreferredYRange()
            && profile.isAbovePreferredY(aiPlayer.blockPosition().getY());
    }

    private boolean shouldDescendToRouteTarget() {
        if (activeMiningProfile == null
            || miningRoutePlan == null
            || !activeMiningProfile.prospectable()
            || !activeMiningProfile.branchMinePreferred()) {
            return false;
        }
        int y = aiPlayer.blockPosition().getY();
        if (activeMiningProfile.surfaceAllowed() && activeMiningProfile.isWithinFallbackY(y)) {
            return false;
        }
        return y > miningRoutePlan.targetY() + BRANCH_TARGET_Y_TOLERANCE;
    }

    private boolean shouldRequestHigherSearchArea(MiningResource.Profile profile) {
        return profile.prospectable()
            && profile.hasPreferredYRange()
            && aiPlayer.blockPosition().getY() < profile.preferredMinY();
    }

    private Optional<BlockPos> findReachableMiningTarget(List<Block> blocks) {
        MiningTargetScan scan = scanMiningTargets(blocks);
        if (miningRun != null) {
            miningRun.recordScan(
                ticks,
                downwardStoneMode ? "prospecting_scan" : "visible_ore_scan",
                aiPlayer.blockPosition(),
                ORE_SCAN_HORIZONTAL_RADIUS,
                ORE_SCAN_VERTICAL_RADIUS,
                scan.candidates,
                scan.directlyWorkable,
                scan.reachable(),
                scan.rejected(),
                scan.rejectionReasons,
                scan.selected()
            );
        }
        if (scan.embeddedSelection != null) {
            lastEmbeddedMiningTarget = scan.embeddedSelection;
            lastEmbeddedMiningTargetTick = ticks;
            AiPlayerMod.info("mining", "[taskId={}] mining embedded ore hint: ai={}, targetStep={}, pos={}, distanceSq={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                activeStep == null ? "unknown" : activeStep.describe(),
                lastEmbeddedMiningTarget.toShortString(),
                Math.round(scan.embeddedSelectionDistance));
        }
        return Optional.ofNullable(scan.selected());
    }

    private boolean isMiningCandidate(BlockPos pos, Block block, List<Block> blocks) {
        return !rejectedTargets.contains(pos)
            && blocks.contains(block)
            && hasAirNeighbor(pos);
    }

    private MiningTargetScan scanMiningTargets(List<Block> blocks) {
        MiningTargetScan scan = new MiningTargetScan();
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        int minY = Math.max(level.getMinY(), center.getY() - ORE_SCAN_VERTICAL_RADIUS);
        int maxY = Math.min(level.getMinY() + level.getHeight() - 1, center.getY() + ORE_SCAN_VERTICAL_RADIUS);
        for (int x = center.getX() - ORE_SCAN_HORIZONTAL_RADIUS; x <= center.getX() + ORE_SCAN_HORIZONTAL_RADIUS; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = center.getZ() - ORE_SCAN_HORIZONTAL_RADIUS; z <= center.getZ() + ORE_SCAN_HORIZONTAL_RADIUS; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    Block block = level.getBlockState(pos).getBlock();
                    if (!blocks.contains(block)) {
                        continue;
                    }
                    scan.candidates++;
                    if (rejectedTargets.contains(pos)) {
                        scan.reject("previously_rejected:" + rejectedTargetReason(pos));
                        continue;
                    }
                    int exposedAir = countAirNeighbors(pos);
                    if (exposedAir <= 0) {
                        scan.selectEmbedded(pos, pos.distSqr(center));
                        scan.reject("no_air_neighbor");
                        continue;
                    }
                    double distance = pos.distSqr(center);
                    if (canWorkBlockDirectly(pos, 3.2D)) {
                        scan.directlyWorkable++;
                        scan.selectDirect(pos, distance, exposedAir);
                        continue;
                    }
                    if (hasPathToBlock(pos)) {
                        scan.pathReachable++;
                        scan.selectPath(pos, distance, exposedAir);
                    } else {
                        scan.reject("no_path");
                    }
                }
            }
        }
        return scan;
    }

    private String rejectedTargetReason(BlockPos pos) {
        if (activeResourceState == null || pos == null) {
            return "unknown";
        }
        ResourceGatherSession.RejectedTarget detail = activeResourceState.getRejectedTargetDetails().get(pos.immutable());
        return detail == null ? "unknown" : detail.reason();
    }

    private StepResult tickProspectForOre(ExecutionStep step, String oreName) {
        StepResult danger = checkMiningDanger("探矿" + oreName);
        if (danger != null) {
            return danger;
        }
        if (ticks > ORE_PROSPECT_TIMEOUT_TICKS) {
            return miningTerminalFailure("prospect_timeout", "探矿超时：附近和矿道内仍未找到可挖的 " + oreName + " 矿，请移动到矿洞、低处或给 AI 放入对应原矿");
        }
        setMiningState(MiningState.DESCEND, "prospecting:" + oreName);
        downwardStoneMode = true;
        if (activeResourceState != null) {
            activeResourceState.markDownwardMode();
        }
        if (ticks % 100 == 0) {
            AiPlayerMod.debug("make_item", "AiPlayer '{}' prospecting for {}: pos={}, ticks={}, rejectedTargets={}",
                aiPlayer.getAiPlayerName(), oreName, aiPlayer.blockPosition(), ticks, rejectedTargets.size());
        }
        markMiningMode("ore_prospecting");
        StepResult progressDecision = maybeSwitchMiningStrategyForNoProgress(oreName);
        if (progressDecision != null) {
            return progressDecision;
        }
        if (shouldDescendToRouteTarget()) {
            StepResult digResult = tickDigDownForStone(step);
            if (digResult.isRunning()) {
                return StepResult.running("正在下探到 " + oreName + " 目标矿层 Y=" + miningRoutePlan.targetY() + "：" + digResult.getMessage());
            }
            if (digResult.getStatus() == StepResult.Status.FAILURE || digResult.getStatus() == StepResult.Status.STUCK) {
                return miningTerminalFailure("route_descent_failed", "下探到目标矿层失败：" + digResult.getMessage());
            }
            return digResult;
        }
        StepResult caveResult = tickKnownCaveSearch(oreName);
        if (caveResult != null) {
            return caveResult;
        }
        if (shouldBranchMineForOre()) {
            return tickBranchMineForOre(step, oreName);
        }
        StepResult digResult = tickDigDownForStone(step);
        if (digResult.isRunning()) {
            return StepResult.running("正在探矿寻找 " + oreName + " 矿：" + digResult.getMessage());
        }
        if (digResult.getStatus() == StepResult.Status.FAILURE || digResult.getStatus() == StepResult.Status.STUCK) {
            return miningTerminalFailure("prospect_failed", "探矿失败：" + digResult.getMessage());
        }
        return digResult;
    }

    private StepResult tickKnownCaveSearch(String oreName) {
        BlockPos entrance = knownCaveEntrance().orElse(null);
        if (entrance == null || exploredCaveEntrances.contains(entrance)) {
            return null;
        }
        if (!canStandAt(entrance) || aiPlayer.getNavigation().createPath(entrance, 1) == null) {
            exploredCaveEntrances.add(entrance);
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' skips unsafe cave entrance for {}: entrance={}",
                taskId, aiPlayer.getAiPlayerName(), oreName, entrance.toShortString());
            return null;
        }
        if (!aiPlayer.blockPosition().closerThan(entrance, 2.0D)) {
            markMiningMode("cave_search_move");
            SurvivalUtils.moveNear(aiPlayer, entrance, 1.5D);
            return StepResult.running("正在进入已发现洞穴扫描 " + oreName);
        }
        exploredCaveEntrances.add(entrance);
        recordMiningWaypoint("cave_scan");
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' scans known cave entrance for {}: entrance={}, exploredCaves={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            oreName,
            entrance.toShortString(),
            exploredCaveEntrances.size());
        return StepResult.running("正在洞穴入口扫描 " + oreName);
    }

    private Optional<BlockPos> knownCaveEntrance() {
        if (activeResourceState != null && activeResourceState.getCaveEntrance() != null) {
            return Optional.of(activeResourceState.getCaveEntrance());
        }
        if (sharedMiningState != null && sharedMiningState.getCaveEntrance() != null) {
            return Optional.of(sharedMiningState.getCaveEntrance());
        }
        return Optional.empty();
    }

    private boolean shouldBranchMineForOre() {
        if (activeMiningProfile == null || !activeMiningProfile.branchMinePreferred()) {
            return false;
        }
        int y = aiPlayer.blockPosition().getY();
        if (miningRoutePlan == null) {
            return activeMiningProfile.isWithinPrimaryY(y) || activeMiningProfile.isWithinFallbackY(y);
        }
        return activeMiningProfile.isWithinPrimaryY(y)
            && y <= miningRoutePlan.targetY() + BRANCH_TARGET_Y_TOLERANCE;
    }

    private StepResult tickBranchMineForOre(ExecutionStep step, String oreName) {
        markMiningMode("branch_tunnel");
        if (branchDirection == null) {
            branchDirection = preferredBranchDirection().orElse(miningRoutePlan == null
                ? (descentDirection == null ? horizontalDirectionFromYaw() : descentDirection)
                : miningRoutePlan.mainDirection());
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' starts branch tunnel for {}: direction={}, pos={}, y={}, route={}, profile={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                oreName,
                branchDirection.getName(),
                aiPlayer.blockPosition().toShortString(),
                aiPlayer.blockPosition().getY(),
                miningRoutePlan == null ? "none" : miningRoutePlan.toLogText(),
                activeMiningProfile == null ? "none" : activeMiningProfile.preferredYText());
        }
        if (branchTunnelBlocks >= BRANCH_TUNNEL_SEGMENT_BLOCKS) {
            rotateBranchDirection("segment_complete");
            return StepResult.running("当前分支矿道已到段落上限，正在换方向继续搜索 " + oreName);
        }
        if (descentMoveTarget != null) {
            StepResult moveResult = tickDescentMoveTarget(
                "branch_tunnel",
                "正在沿分支矿道前进",
                "已推进到分支矿道前沿",
                "分支矿道站位不可达，正在换方向"
            );
            if (moveResult != null) {
                return moveResult;
            }
        }
        BlockPos nextStand = aiPlayer.blockPosition().relative(branchDirection);
        if (canStandAt(nextStand) && !rejectedTargets.contains(nextStand)) {
            setDescentMoveTarget(nextStand, "branch_tunnel_move");
            return StepResult.running("正在推进分支矿道站位");
        }
        BlockPos digTarget = findBranchDigTarget(nextStand);
        if (digTarget == null) {
            if (!rotateBranchDirection("blocked")) {
                return handleBranchExhausted(oreName, "blocked");
            }
            return StepResult.running("分支矿道受阻，正在换方向寻找 " + oreName);
        }
        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        String targetDanger = dangerAroundDigTarget(digTarget);
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, digTarget, "rotate_branch");
            rotateBranchDirection(targetDanger);
            return StepResult.running("分支矿道前方有危险，正在换方向");
        }
        recordMiningDigAttempt(digTarget, block);
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            return StepResult.running("正在开分支矿道寻找 " + oreName);
        }
        if (!SurvivalUtils.breakBlock(aiPlayer, digTarget)) {
            breakTicks = 0;
            recordMiningDigResult(digTarget, block, false);
            rotateBranchDirection("break_failed");
            return StepResult.running("分支矿道破坏失败，正在换方向");
        }
        breakTicks = 0;
        branchTunnelBlocks++;
        downwardDigBlocks++;
        recordMiningDigResult(digTarget, block, true);
        if (miningRun != null) {
            miningRun.recordBranchProgress(branchDirection, aiPlayer.blockPosition().getY(), branchTunnelBlocks, "dig_success");
        }
        recordMiningWaypoint("branch_tunnel");
        recordCaveEntranceIfFound(digTarget);
        return StepResult.running("已开出一格分支矿道寻找 " + oreName);
    }

    private Optional<Direction> preferredBranchDirection() {
        if (lastEmbeddedMiningTarget == null || ticks - lastEmbeddedMiningTargetTick > 200) {
            return Optional.empty();
        }
        BlockPos current = aiPlayer.blockPosition();
        if (Math.abs(lastEmbeddedMiningTarget.getY() - current.getY()) > 4) {
            return Optional.empty();
        }
        int dx = lastEmbeddedMiningTarget.getX() - current.getX();
        int dz = lastEmbeddedMiningTarget.getZ() - current.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return Optional.of(dx > 0 ? Direction.EAST : Direction.WEST);
        }
        if (dz != 0) {
            return Optional.of(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }
        return Optional.empty();
    }

    private StepResult handleBranchExhausted(String oreName, String reason) {
        if (shiftBranchLayer(reason)) {
            return StepResult.running("当前矿层分支不可用，正在换到 Y=" + miningRoutePlan.targetY() + " 继续寻找 " + oreName);
        }
        return miningTerminalFailure("branch_blocked", "分支矿道多层多方向都被危险方块、空气断层或不可破坏方块阻挡，无法继续寻找 " + oreName);
    }

    private boolean shiftBranchLayer(String reason) {
        if (activeMiningProfile == null || miningRoutePlan == null || branchLayerShifts >= MAX_BRANCH_LAYER_SHIFTS) {
            return false;
        }
        int minY = activeMiningProfile.primaryRange() == null
            ? activeMiningProfile.preferredMinY()
            : activeMiningProfile.primaryRange().minY();
        int currentY = aiPlayer.blockPosition().getY();
        int nextTargetY = Math.max(minY, currentY - BRANCH_LAYER_SHIFT_BLOCKS);
        if (nextTargetY >= currentY) {
            return false;
        }
        branchLayerShifts++;
        miningRoutePlan = miningRoutePlan.withTargetY(nextTargetY, "layer_shift:" + reason + "#" + branchLayerShifts);
        if (miningRun != null) {
            miningRun.recordRoutePlan(miningRoutePlan);
        }
        branchDirection = null;
        branchTunnelBlocks = 0;
        branchTunnelTurns = 0;
        descentMoveTarget = null;
        descentDirection = null;
        downwardStoneMode = true;
        resetMovement();
        markMiningMode("branch_layer_shift");
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' shifts branch mining layer: reason={}, targetY={}, shift={}/{}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            nextTargetY,
            branchLayerShifts,
            MAX_BRANCH_LAYER_SHIFTS);
        return true;
    }

    private StepResult maybeSwitchMiningStrategyForNoProgress(String oreName) {
        if (ticksWithoutItemProgress < 600 || ticks - lastMiningProgressDecisionTick < 600) {
            return null;
        }
        lastMiningProgressDecisionTick = ticks;
        if (shouldBranchMineForOre()) {
            boolean rotated = rotateBranchDirection("no_progress_score");
            if (miningRun != null) {
                miningRun.recordProgressDecision(ticks, rotated ? "switch_branch" : "branch_exhausted", "no_target_item_progress:" + oreName);
            }
            ticksWithoutItemProgress = 0;
            return StepResult.running(rotated
                ? "当前分支长时间没有产出，正在换分支继续寻找 " + oreName
                : "多个分支长时间没有产出，等待复盘或玩家协助");
        }
        descentDirection = null;
        if (miningRun != null) {
            miningRun.recordProgressDecision(ticks, "reset_descent_direction", "no_target_item_progress:" + oreName);
        }
        ticksWithoutItemProgress = 0;
        return StepResult.running("当前探矿方向长时间没有产出，正在换方向继续寻找 " + oreName);
    }

    private BlockPos findBranchDigTarget(BlockPos nextStand) {
        if (isUnsafeTunnelSpace(nextStand)) {
            return null;
        }
        BlockPos feetTarget = breakableTunnelBlock(nextStand);
        if (feetTarget != null) {
            return feetTarget;
        }
        return breakableTunnelBlock(nextStand.above());
    }

    private BlockPos breakableTunnelBlock(BlockPos pos) {
        if (pos == null || pos.getY() <= aiPlayer.level().getMinY() || rejectedTargets.contains(pos)) {
            return null;
        }
        Block block = aiPlayer.level().getBlockState(pos).getBlock();
        if (block == Blocks.AIR || block == Blocks.BEDROCK || block == Blocks.WATER || block == Blocks.LAVA
            || block == Blocks.GRAVEL || block == Blocks.SAND || block == Blocks.RED_SAND) {
            return null;
        }
        return pos;
    }

    private Optional<BlockPos> findAdjacentMiningTarget(BlockPos origin, List<Block> blocks) {
        if (origin == null || blocks == null || blocks.isEmpty()) {
            return Optional.empty();
        }
        for (Direction direction : Direction.values()) {
            BlockPos candidate = origin.relative(direction);
            Block block = aiPlayer.level().getBlockState(candidate).getBlock();
            if (!rejectedTargets.contains(candidate)
                && blocks.contains(block)
                && hasAirNeighbor(candidate)
                && (canWorkBlockDirectly(candidate, 3.2D) || hasPathToBlock(candidate))) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private boolean isUnsafeTunnelSpace(BlockPos pos) {
        if (pos == null) {
            return true;
        }
        Block block = aiPlayer.level().getBlockState(pos).getBlock();
        return block == Blocks.BEDROCK || block == Blocks.WATER || block == Blocks.LAVA
            || block == Blocks.GRAVEL || block == Blocks.SAND || block == Blocks.RED_SAND;
    }

    private boolean rotateBranchDirection(String reason) {
        if (branchTunnelTurns >= BRANCH_TUNNEL_MAX_TURNS) {
            if (miningRun != null) {
                miningRun.recordBranchProgress(branchDirection, aiPlayer.blockPosition().getY(), branchTunnelBlocks, "max_turns");
            }
            return false;
        }
        if (miningRun != null) {
            miningRun.recordBranchProgress(branchDirection, aiPlayer.blockPosition().getY(), branchTunnelBlocks, reason);
        }
        branchDirection = branchDirection == null ? horizontalDirectionFromYaw().getClockWise() : branchDirection.getClockWise();
        branchTunnelBlocks = 0;
        branchTunnelTurns++;
        resetMovement();
        markMiningMode("branch_turn");
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rotates branch tunnel: reason={}, nextDirection={}, turns={}/{}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            branchDirection.getName(),
            branchTunnelTurns,
            BRANCH_TUNNEL_MAX_TURNS);
        return true;
    }

    private StepResult tickCraft(ExecutionStep step) {
        craftTicks++;
        int delay = craftDelay(step);
        if (craftTicks < delay) {
            return StepResult.running(craftProgressMessage(step));
        }
        boolean crafted = craftItem(step);
        return crafted
            ? StepResult.success("已合成 " + step.getItem())
            : StepResult.failure("材料不足，无法合成 " + step.getItem());
    }

    private int craftDelay(ExecutionStep step) {
        return switch (step.getStation() == null ? "inventory" : step.getStation()) {
            case "furnace", "blast_furnace", "smoker", "campfire" -> SMELTING_DELAY_TICKS;
            case "inventory" -> INVENTORY_CRAFT_DELAY_TICKS;
            default -> STATION_CRAFT_DELAY_TICKS;
        };
    }

    private String craftProgressMessage(ExecutionStep step) {
        return switch (step.getStation() == null ? "inventory" : step.getStation()) {
            case "furnace", "blast_furnace", "smoker", "campfire" -> "正在使用" + stationName(step.getStation()) + "处理 " + step.getItem();
            case "inventory" -> "准备合成 " + step.getItem();
            default -> "正在使用" + stationName(step.getStation()) + "合成 " + step.getItem();
        };
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
            rejectTarget(targetPos, "movement_stuck");
            targetPos = null;
            resetMovement();
            return StepResult.running(resourceName + "位置不可达，正在换一个目标");
        }
        return StepResult.running("正在接近" + resourceName);
    }

    private StepResult tickDescentMoveTarget(
        String mode,
        String movingMessage,
        String reachedMessage,
        String fallbackMessage
    ) {
        if (descentMoveTarget == null) {
            return null;
        }
        BlockPos moveTarget = descentMoveTarget;
        if (!canStandAt(moveTarget)) {
            rejectTarget(moveTarget, "stand_no_longer_valid");
            descentMoveTarget = null;
            resetMovement();
            return null;
        }
        markMiningMode(mode);
        if (SurvivalUtils.moveNear(aiPlayer, moveTarget, 1.5D)) {
            descentMoveTarget = null;
            resetMovement();
            recordMiningWaypoint(mode);
            return StepResult.running(reachedMessage);
        }

        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(moveTarget));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return StepResult.running(movingMessage);
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > DESCENT_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            rejectTarget(moveTarget, "movement_stuck");
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejects descent move target: targetStep={}, pos={}, mode={}, distanceSq={}, noProgressTicks={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                activeStep == null ? "unknown" : activeStep.describe(),
                moveTarget.toShortString(),
                mode,
                String.format("%.2f", distanceSq),
                moveTicksWithoutProgress);
            descentMoveTarget = null;
            resetMovement();
            breakTicks = 0;
            return StepResult.running(fallbackMessage);
        }
        return StepResult.running(movingMessage);
    }

    private void setDescentMoveTarget(BlockPos pos, String mode) {
        if (pos == null) {
            return;
        }
        descentMoveTarget = pos.immutable();
        breakTicks = 0;
        resetMovement();
        markMiningMode(mode);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' selected descent move target: targetStep={}, pos={}, mode={}, currentPos={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            activeStep == null ? "unknown" : activeStep.describe(),
            descentMoveTarget.toShortString(),
            mode,
            aiPlayer.blockPosition().toShortString());
    }

    private void rejectTarget(BlockPos pos) {
        rejectTarget(pos, "unknown");
    }

    private void rejectTarget(BlockPos pos, String reason) {
        if (pos == null) {
            return;
        }
        rejectedTargets.add(pos.immutable());
        if (activeResourceState != null) {
            activeResourceState.rejectTarget(pos, reason, ticks);
        }
        if (miningRun != null) {
            miningRun.recordTargetRejected(ticks, pos, reason);
        }
        AiPlayerMod.debug("make_item", "AiPlayer '{}' rejected target: pos={}, reason={}, tick={}",
            aiPlayer.getAiPlayerName(), pos, reason, ticks);
    }

    private boolean isMiningRunStep(ExecutionStep step) {
        if (step == null) {
            return false;
        }
        return "gather_stone".equals(step.getStep())
            || ("gather".equals(step.getStep()) && activeMiningProfile != null);
    }

    private void markMiningMode(String mode) {
        if (miningRun != null) {
            miningRun.markMode(mode, aiPlayer.blockPosition());
        }
    }

    private void setMiningState(MiningState state, String reason) {
        if (miningRun != null) {
            miningRun.transitionTo(state, reason, aiPlayer.blockPosition());
        }
    }

    private boolean hasEnoughMiningToolDurability(MiningResource.Profile profile) {
        if (profile == null || profile.requiredTool() == null || !profile.requiredTool().contains("pickaxe")) {
            return true;
        }
        int remaining = aiPlayer.getBestToolRemainingDurability("pickaxe");
        return remaining == Integer.MAX_VALUE || remaining >= MIN_MINING_TOOL_DURABILITY;
    }

    private void prepareMiningSupplies(MiningResource.Profile profile) {
        if (miningSupplyCheckDone || profile == null || !profile.branchMinePreferred()) {
            return;
        }
        miningSupplyCheckDone = true;
        setMiningState(MiningState.SUPPLY, "deep_mining_preflight");
        boolean craftingReady = goalChecker.hasItem(aiPlayer, "minecraft:crafting_table", 1)
            || hasNearbyBlock(Blocks.CRAFTING_TABLE, 8)
            || craftItemToCount("minecraft:crafting_table", 1, new HashSet<>());
        boolean furnaceReady = goalChecker.hasItem(aiPlayer, "minecraft:furnace", 1)
            || hasNearbyBlock(Blocks.FURNACE, 8)
            || craftItemToCount("minecraft:furnace", 1, new HashSet<>());
        boolean fuelReady = goalChecker.hasItem(aiPlayer, "minecraft:coal", 1)
            || goalChecker.hasItem(aiPlayer, "minecraft:charcoal", 1);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' mining supplies checked: target={}, craftingReady={}, furnaceReady={}, fuelReady={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            activeStep == null ? "unknown" : activeStep.describe(),
            craftingReady,
            furnaceReady,
            fuelReady,
            new TreeMap<>(aiPlayer.getInventorySnapshot()));
    }

    private void seedNearbyCaveEntrance(MiningResource.Profile profile) {
        if (profile == null || !profile.cavePreferred()) {
            return;
        }
        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, "mining_cave_seed");
        if (snapshot.getWorld().getNearbyHostiles() > 3) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' skips cave seed for {}: nearbyHostiles={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                profile.displayName(),
                snapshot.getWorld().getNearbyHostiles());
            return;
        }
        WorldSnapshot.CaveSnapshot selected = snapshot.getNearbyCaves().stream()
            .filter(cave -> {
                boolean usable = cave.isReachable() && cave.getConnectedAir() >= 8 && cave.getExposedWalls() >= 2;
                if (!usable) {
                    AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejects cave seed for {}: pos={}, reachable={}, connectedAir={}, exposedWalls={}, visibleOres={}",
                        taskId,
                        aiPlayer.getAiPlayerName(),
                        profile.displayName(),
                        positionText(cave.getPosition()),
                        cave.isReachable(),
                        cave.getConnectedAir(),
                        cave.getExposedWalls(),
                        cave.getVisibleOres());
                }
                return usable;
            })
            .max(Comparator
                .comparingInt(WorldSnapshot.CaveSnapshot::getVisibleOres)
                .thenComparingInt(WorldSnapshot.CaveSnapshot::getExposedWalls)
                .thenComparingDouble(cave -> -cave.getDistance()))
            .orElse(null);
        if (selected == null) {
            return;
        }
        BlockPos entrance = blockPosFrom(selected.getPosition());
        if (activeResourceState != null) {
            activeResourceState.recordCaveEntrance(entrance, ticks);
        }
        if (sharedMiningState != null) {
            sharedMiningState.recordCaveEntrance(entrance, ticks);
        }
        markMiningMode("cave_entrance_seed");
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' seeded cave entrance for {}: entrance={}, connectedAir={}, exposedWalls={}, visibleOres={}, distance={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            profile.displayName(),
            entrance.toShortString(),
            selected.getConnectedAir(),
            selected.getExposedWalls(),
            selected.getVisibleOres(),
            selected.getDistance());
    }

    private BlockPos blockPosFrom(int[] position) {
        if (position == null || position.length < 3) {
            return aiPlayer.blockPosition().immutable();
        }
        return new BlockPos(position[0], position[1], position[2]);
    }

    private String positionText(int[] position) {
        if (position == null || position.length < 3) {
            return "unknown";
        }
        return position[0] + "," + position[1] + "," + position[2];
    }

    private boolean ensureMiningInventorySpace(Item item, String reason) {
        if (item == null || item == Items.AIR || aiPlayer.hasBackpackSpaceFor(item)) {
            return true;
        }
        setMiningState(MiningState.WAITING_FOR_PLAYER, "inventory_full:" + reason);
        AiPlayerMod.warn("mining", "[taskId={}] AiPlayer '{}' mining inventory full: reason={}, expectedDrop={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            itemKey(item),
            new TreeMap<>(aiPlayer.getInventorySnapshot()));
        return false;
    }

    private StepResult checkMiningDanger(String context) {
        if (aiPlayer.getHealth() <= LOW_MINING_HEALTH) {
            recordMiningDanger("low_health", aiPlayer.blockPosition(), context);
            return retreatToOwner("血量过低，正在回到玩家身边");
        }
        if (ticks < nextDangerCheckTick) {
            return null;
        }
        nextDangerCheckTick = ticks + 40;
        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, "mining_danger_check");
        if (snapshot.getWorld().getNearbyHostiles() > MAX_SAFE_MINING_HOSTILES) {
            recordMiningDanger("too_many_hostiles", aiPlayer.blockPosition(), context + ":hostiles=" + snapshot.getWorld().getNearbyHostiles());
            return retreatToOwner("附近敌对生物过多，暂停挖矿并回到玩家身边");
        }
        if (aiPlayer.getNavigation().isStuck()) {
            recordMiningDanger("navigation_stuck", aiPlayer.blockPosition(), context);
            setMiningState(MiningState.WAITING_FOR_PLAYER, "navigation_stuck");
            return StepResult.running("挖矿路径卡住，正在等待重新规划或玩家协助");
        }
        return null;
    }

    private StepResult retreatToOwner(String message) {
        setMiningState(MiningState.RETURNING, message);
        ServerPlayer owner = ownerPlayer();
        if (owner != null) {
            aiPlayer.getNavigation().moveTo(owner, 1.1D);
            return StepResult.running(message);
        }
        setMiningState(MiningState.WAITING_FOR_PLAYER, "owner_not_found");
        return StepResult.running(message + "，但当前找不到在线玩家位置");
    }

    private ServerPlayer ownerPlayer() {
        if (!(aiPlayer.level() instanceof ServerLevel serverLevel) || aiPlayer.getOwnerUuid() == null) {
            return null;
        }
        return serverLevel.getServer().getPlayerList().getPlayer(aiPlayer.getOwnerUuid());
    }

    private String dangerAroundDigTarget(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        for (Direction direction : Direction.values()) {
            Block block = aiPlayer.level().getBlockState(pos.relative(direction)).getBlock();
            if (block == Blocks.LAVA) {
                return "near_lava";
            }
            if (block == Blocks.WATER) {
                return "near_water";
            }
        }
        if (pos.equals(aiPlayer.blockPosition().below())) {
            return "vertical_foot_dig";
        }
        Block support = aiPlayer.level().getBlockState(pos.below()).getBlock();
        if (support == Blocks.AIR && pos.getY() < aiPlayer.blockPosition().getY() - 1) {
            return "fall_risk";
        }
        return null;
    }

    private void recordMiningDanger(String reason, BlockPos pos, String action) {
        if (miningRun != null) {
            miningRun.recordDanger(ticks, reason, pos, action);
        }
    }

    private void reuseMiningWaypointIfUseful() {
        if (sharedMiningState == null || !sharedMiningState.shouldReuseDownwardMode()) {
            return;
        }
        int preferredY = activeMiningProfile == null
            ? aiPlayer.blockPosition().getY()
            : activeMiningProfile.preferredMaxY();
        Optional<BlockPos> waypoint = sharedMiningState.nearestMiningWaypoint(aiPlayer.blockPosition(), preferredY)
            .filter(this::canStandAt)
            .filter(pos -> aiPlayer.getNavigation().createPath(pos, 1) != null);
        if (waypoint.isEmpty()) {
            return;
        }
        setDescentMoveTarget(waypoint.get(), "reuse_mining_waypoint");
        downwardStoneMode = true;
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' reuses mining waypoint: targetStep={}, waypoint={}, preferredY={}, caveEntrance={}, knownWaypoints={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            activeStep == null ? "unknown" : activeStep.describe(),
            descentMoveTarget.toShortString(),
            preferredY,
            sharedMiningState.getCaveEntrance() == null ? "none" : sharedMiningState.getCaveEntrance().toShortString(),
            sharedMiningState.getMiningWaypoints().size());
    }

    private void recordMiningWaypoint(String mode) {
        BlockPos standPos = aiPlayer.blockPosition();
        if (activeResourceState != null) {
            activeResourceState.recordMiningWaypoint(standPos, mode, ticks);
        }
        if (sharedMiningState != null) {
            sharedMiningState.recordMiningWaypoint(standPos, mode, ticks);
        }
        markMiningMode(mode);
    }

    private void recordCaveEntranceIfFound(BlockPos digTarget) {
        if (digTarget == null || countAirNeighbors(digTarget) < 3) {
            return;
        }
        Optional<BlockPos> caveEntrance = findAdjacentStandableAir(digTarget);
        if (caveEntrance.isEmpty()) {
            return;
        }
        BlockPos entrance = caveEntrance.get();
        if (activeResourceState != null) {
            activeResourceState.recordCaveEntrance(entrance, ticks);
        }
        if (sharedMiningState != null) {
            sharedMiningState.recordCaveEntrance(entrance, ticks);
        }
        markMiningMode("cave_entrance");
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' found cave entrance while mining: targetStep={}, entrance={}, from={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            activeStep == null ? "unknown" : activeStep.describe(),
            entrance.toShortString(),
            digTarget.toShortString());
    }

    private Optional<BlockPos> findAdjacentStandableAir(BlockPos origin) {
        for (Direction direction : Direction.values()) {
            BlockPos candidate = origin.relative(direction);
            if (canStandAt(candidate)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private void recordMiningDigAttempt(BlockPos digTarget, Block block) {
        if (miningRun != null) {
            miningRun.recordDigAttempt(
                ticks,
                currentMiningDigMode(),
                digTarget,
                block,
                aiPlayer.blockPosition(),
                descentDirection,
                downwardDigBlocks
            );
        }
    }

    private void recordMiningDigResult(BlockPos digTarget, Block block, boolean success) {
        if (miningRun != null) {
            miningRun.recordDigResult(
                ticks,
                currentMiningDigMode(),
                digTarget,
                block,
                success,
                success ? countAirNeighbors(digTarget) : 0,
                new TreeMap<>(aiPlayer.getInventorySnapshot())
            );
        }
    }

    private String currentMiningDigMode() {
        if (shouldBranchMineForOre() && branchDirection != null) {
            return "branch_tunnel";
        }
        return isProspectingForOre() ? "ore_prospect_stair" : "stone_stair";
    }

    private StepResult miningSuccess(String message) {
        finishMiningRun("success", message);
        return StepResult.success(message);
    }

    private StepResult miningFailure(String reason, String message) {
        finishMiningRun("failure:" + reason, message);
        return StepResult.failure(message);
    }

    private StepResult miningTerminalFailure(String reason, String message) {
        String fullMessage = miningRun == null ? message : message + "；" + miningRun.summary();
        finishMiningRun("terminal_failure:" + reason, message);
        return StepResult.terminalFailure(fullMessage);
    }

    private void finishMiningRun(String status, String message) {
        if (miningRun != null) {
            miningRun.finish(status, message, aiPlayer);
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
        if (stationBlock == Blocks.AIR) {
            return true;
        }
        if (hasNearbyBlock(stationBlock, 6)) {
            return true;
        }
        if (!goalChecker.hasItem(aiPlayer, stationItem, 1) && !craftItemToCount(stationItem, 1, stack)) {
            return false;
        }
        return placeStationBlock(stationItem, stationBlock);
    }

    private boolean placeStationBlock(String stationItem, Block stationBlock) {
        if (hasNearbyBlock(stationBlock, 6)) {
            return true;
        }
        Optional<BlockPos> target = findStationPlacementPos();
        if (target.isEmpty()) {
            AiPlayerMod.debug("make_item", "AiPlayer '{}' has no valid placement position for station {}", aiPlayer.getAiPlayerName(), stationItem);
            return false;
        }
        boolean placed = SurvivalUtils.placeBlock(aiPlayer, target.get(), stationBlock);
        if (placed) {
            AiPlayerMod.debug("make_item", "AiPlayer '{}' placed station {} at {}", aiPlayer.getAiPlayerName(), stationItem, target.get());
        }
        return placed;
    }

    private Optional<BlockPos> findStationPlacementPos() {
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(center.offset(-2, -1, -2), center.offset(2, 1, 2))
            .map(BlockPos::immutable)
            .filter(pos -> !pos.equals(center) && !pos.equals(center.above()))
            .filter(pos -> level.getBlockState(pos).isAir())
            .filter(pos -> {
                Block support = level.getBlockState(pos.below()).getBlock();
                return support != Blocks.AIR && support != Blocks.WATER && support != Blocks.LAVA && support != Blocks.BEDROCK;
            })
            .min(Comparator.comparingDouble(pos -> pos.distSqr(center)));
    }

    private String stationName(String station) {
        return switch (station == null ? "inventory" : station) {
            case "crafting_table" -> "工作台";
            case "furnace" -> "熔炉";
            case "blast_furnace" -> "高炉";
            case "smoker" -> "烟熏炉";
            case "campfire" -> "营火";
            case "stonecutter" -> "切石机";
            case "smithing_table" -> "锻造台";
            default -> "工作站";
        };
    }

    private boolean ensureTool(String itemId) {
        boolean available = hasToolItem(itemId) || craftItemToCount(itemId, 1, new HashSet<>());
        if (available) {
            equipRequiredTool(itemId);
        }
        return available;
    }

    private void equipRequiredTool(String itemId) {
        if (itemId == null) {
            return;
        }
        if (itemId.endsWith("_pickaxe")) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("pickaxe"));
        } else if (itemId.endsWith("_axe")) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("axe"));
        } else if (itemId.endsWith("_shovel")) {
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, aiPlayer.getBestToolStackFor("shovel"));
        }
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
        Block block = aiPlayer.level().getBlockState(pos).getBlock();
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        aiPlayer.lookAtWorkTarget(pos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        boolean destroyed = aiPlayer.level().destroyBlock(pos, false);
        if (destroyed) {
            SurvivalUtils.damageToolForBlock(aiPlayer, block);
            aiPlayer.addItem(targetItem, Math.max(1, dropCount));
        }
        return destroyed;
    }

    private int genericBreakDelay(Block block) {
        return SurvivalUtils.getBreakDelay(aiPlayer, block);
    }

    private boolean hasAirNeighbor(BlockPos pos) {
        return countAirNeighbors(pos) > 0;
    }

    private int countAirNeighbors(BlockPos pos) {
        if (pos == null) {
            return 0;
        }
        int count = 0;
        for (Direction direction : Direction.values()) {
            if (aiPlayer.level().getBlockState(pos.relative(direction)).isAir()) {
                count++;
            }
        }
        return count;
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
        if (block == Blocks.AIR || block == Blocks.BEDROCK || block == Blocks.WATER || block == Blocks.LAVA
            || block == Blocks.GRAVEL || block == Blocks.SAND || block == Blocks.RED_SAND) {
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
            .filter(pos -> !rejectedTargets.contains(pos))
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
        return hasUsablePickaxe(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE);
    }

    private boolean hasIronOrBetterPickaxe() {
        return hasUsablePickaxe(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE);
    }

    private boolean hasDiamondOrBetterPickaxe() {
        return hasUsablePickaxe(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE);
    }

    private boolean hasUsablePickaxe(Item... acceptedItems) {
        ItemStack tool = aiPlayer.getBestToolStackFor("pickaxe");
        if (tool.isEmpty()) {
            return false;
        }
        for (Item item : acceptedItems) {
            if (tool.is(item)) {
                return true;
            }
        }
        return false;
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

    private String itemKey(Item item) {
        if (item == null || item == Items.AIR) {
            return "minecraft:air";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? item.toString() : key.toString();
    }

    private void resetMovement() {
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
    }

    private static final class MiningTargetScan {
        private int candidates;
        private int directlyWorkable;
        private int pathReachable;
        private final Map<String, Integer> rejectionReasons = new HashMap<>();
        private BlockPos directSelection;
        private double directSelectionDistance = Double.MAX_VALUE;
        private int directSelectionExposure;
        private BlockPos pathSelection;
        private double pathSelectionDistance = Double.MAX_VALUE;
        private int pathSelectionExposure;
        private BlockPos embeddedSelection;
        private double embeddedSelectionDistance = Double.MAX_VALUE;

        private void selectDirect(BlockPos pos, double distance, int exposedAir) {
            if (exposedAir > directSelectionExposure
                || (exposedAir == directSelectionExposure && distance < directSelectionDistance)) {
                directSelection = pos.immutable();
                directSelectionDistance = distance;
                directSelectionExposure = exposedAir;
            }
        }

        private void selectPath(BlockPos pos, double distance, int exposedAir) {
            if (exposedAir > pathSelectionExposure
                || (exposedAir == pathSelectionExposure && distance < pathSelectionDistance)) {
                pathSelection = pos.immutable();
                pathSelectionDistance = distance;
                pathSelectionExposure = exposedAir;
            }
        }

        private void selectEmbedded(BlockPos pos, double distance) {
            if (distance < embeddedSelectionDistance) {
                embeddedSelection = pos.immutable();
                embeddedSelectionDistance = distance;
            }
        }

        private void reject(String reason) {
            rejectionReasons.merge(reason == null || reason.isBlank() ? "unknown" : reason, 1, Integer::sum);
        }

        private int reachable() {
            return directlyWorkable + pathReachable;
        }

        private int rejected() {
            int rejected = 0;
            for (int count : rejectionReasons.values()) {
                rejected += count;
            }
            return rejected;
        }

        private BlockPos selected() {
            return directSelection == null ? pathSelection : directSelection;
        }
    }
}
