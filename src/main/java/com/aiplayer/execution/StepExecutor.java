package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.agent.MiningStrategyAdvice;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.execution.interaction.InteractionActionType;
import com.aiplayer.execution.interaction.InteractionFailureKey;
import com.aiplayer.execution.interaction.InteractionFailureMemory;
import com.aiplayer.execution.interaction.InteractionTarget;
import com.aiplayer.mining.ExposureTarget;
import com.aiplayer.mining.OreProspectResult;
import com.aiplayer.mining.OreProspectSelectionPolicy;
import com.aiplayer.mining.OreProspectTarget;
import com.aiplayer.mining.OreProspector;
import com.aiplayer.mining.StageMiningPlan;
import com.aiplayer.recipe.MaterialRequirement;
import com.aiplayer.recipe.MiningResource;
import com.aiplayer.recipe.SurvivalRecipeBook;
import com.aiplayer.snapshot.WorldSnapshot;
import com.aiplayer.util.SurvivalInteractionValidator;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class StepExecutor {
    private static final int MAX_STEP_TICKS = 6000;
    private static final int TARGET_MOVE_TIMEOUT_TICKS = 200;
    private static final int DESCENT_MOVE_TIMEOUT_TICKS = 120;
    private static final int MAX_CONSECUTIVE_BREAK_FAILURES = 3;
    private static final int ADJACENT_MINING_MOVE_TIMEOUT_TICKS = 40;
    private static final double ADJACENT_MINING_MOVE_REACHED_DISTANCE_SQ = 0.12D;
    private static final double ADJACENT_MINING_STEP_SPEED = 0.24D;
    private static final int CHEST_RADIUS = 12;
    private static final int STONE_DESCENT_MAX_BLOCKS = 32;
    private static final int ORE_DESCENT_MAX_BLOCKS = 112;
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
    private static final int DESCENT_DETOUR_SEGMENT_BLOCKS = 6;
    private static final int DESCENT_DETOUR_MAX_BLOCKS = 96;
    private static final int DESCENT_DETOUR_MAX_TURNS = 12;
    private static final int PROSPECT_SCAN_RADIUS = 96;
    private static final int PROSPECT_SCAN_VERTICAL_RADIUS = 96;
    private static final int PROSPECT_SCAN_INTERVAL_TICKS = 100;
    private static final int PROSPECT_SCAN_BLOCK_BUDGET = 600_000;
    private static final int GENERIC_BLOCK_PROSPECT_SCAN_RADIUS = 48;
    private static final int GENERIC_BLOCK_PROSPECT_VERTICAL_RADIUS = 32;
    private static final int GENERIC_BLOCK_PROSPECT_SCAN_BLOCK_BUDGET = 60_000;
    private static final int GENERIC_BLOCK_PROSPECT_MAX_RETRIES = 3;
    private static final int GUIDED_TUNNEL_MAX_BLOCKS = 180;
    private static final int PROSPECT_RESCAN_BLOCK_INTERVAL = 30;
    private static final int PROSPECT_RESCAN_DISTANCE_INTERVAL = 30;
    private static final int PROSPECT_RESCAN_ACTION_INTERVAL = 30;
    private static final int PROSPECT_ABOVE_LAYER_REPLAN_LIMIT = 3;
    private static final int TREE_NEAR_SEARCH_RADIUS = 32;
    private static final int TREE_NEAR_VERTICAL_RADIUS = 12;
    private static final int TREE_EXTENDED_SEARCH_RADIUS = 96;
    private static final int TREE_EXTENDED_VERTICAL_RADIUS = 24;
    private static final int TREE_SEARCH_INTERVAL_TICKS = 80;
    private static final int TREE_EXPLORE_STEP_BLOCKS = 24;
    private static final int TREE_EXPLORE_MAX_POINTS = 12;
    private static final int TREE_EXPLORE_STAND_SEARCH_RADIUS = 8;
    private static final int TREE_EXPLORE_STAND_VERTICAL_RADIUS = 5;
    private static final int TREE_EXPLORE_PATH_CHECK_LIMIT = 8;
    private static final int TREE_SCAN_BLOCK_BUDGET_PER_TICK = 12_000;
    private static final int TREE_SCAN_PATH_CANDIDATE_LIMIT = 16;
    private static final int TREE_SCAN_PATH_CHECKS_PER_TICK = 4;
    private static final int TREE_NO_REACHABLE_SCAN_LIMIT = 4;
    private static final int TREE_EXPLORE_FAILURE_LIMIT = 3;
    private static final int TREE_TARGET_NO_EFFECTIVE_PROGRESS_TICKS = 240;
    private static final int TREE_HARD_TIMEOUT_TICKS = 1600;
    private static final int INTERACTION_FAILURE_TTL_TICKS = 1200;
    private static final int STONE_DESCENT_START_SEARCH_RADIUS = 12;
    private static final int STONE_DESCENT_START_VERTICAL_RADIUS = 5;
    private static final int STONE_DESCENT_START_FAILURE_LIMIT = 3;
    private static final int STONE_SCAN_HORIZONTAL_RADIUS = 24;
    private static final int STONE_SCAN_VERTICAL_RADIUS = 8;
    private static final int STONE_SCAN_PATH_CHECK_LIMIT = 32;
    private static final int MIN_MINING_TOOL_DURABILITY = 12;
    private static final float LOW_MINING_HEALTH = 8.0F;
    private static final int INVENTORY_CRAFT_DELAY_TICKS = 10;
    private static final int STATION_CRAFT_DELAY_TICKS = 24;
    private static final int SMELTING_DELAY_TICKS = 80;
    private static final int STATUS_DETAIL_MAX_CHARS = 1600;
    private static final int STATUS_DETAIL_SECTION_MAX_CHARS = 360;
    private static final int SAFE_MINING_FALLBACK_MAX_UP_DELTA = 2;
    private static final int SAFE_MINING_FALLBACK_MAX_HORIZONTAL_DISTANCE = 18;

    private final AiPlayerEntity aiPlayer;
    private final ResourceGatherSession resourceGatherSession;
    private final GoalChecker goalChecker = new GoalChecker();
    private final MiningFailurePolicy miningFailurePolicy = new MiningFailurePolicy();
    private final MiningCandidateCooldown miningCandidateCooldown = new MiningCandidateCooldown();
    private final String taskId;
    private ExecutionStep activeStep;
    private ResourceGatherSession.ResourceState activeResourceState;
    private ResourceGatherSession.ResourceState sharedMiningState;
    private BlockPos targetPos;
    private Entity targetEntity;
    private BlockPos descentMoveTarget;
    private String descentMoveMode;
    private Direction descentDirection;
    private MiningResource.Profile activeMiningProfile;
    private int ticks;
    private int breakTicks;
    private int craftTicks;
    private int startCount;
    private int downwardDigBlocks;
    private int routeClearanceBlocks;
    private int downwardNoTargetAttempts;
    private int lastProgressCount;
    private int ticksWithoutItemProgress;
    private boolean downwardStoneMode;
    private int moveTicksWithoutProgress;
    private double closestTargetDistanceSq;
    private final Set<BlockPos> rejectedTargets = new HashSet<>();
    private final Set<BlockPos> exploredCaveEntrances = new HashSet<>();
    private final Set<BlockPos> failedSafeMiningPoints = new HashSet<>();
    private MiningRun miningRun;
    private MiningRoutePlan miningRoutePlan;
    private StairDescentController stairDescentController;
    private MainTunnelController mainTunnelController;
    private BranchMiningPattern branchMiningPattern;
    private Direction branchDirection;
    private int branchTunnelBlocks;
    private int branchTunnelTurns;
    private int branchLayerShifts;
    private Direction descentDetourDirection;
    private int descentDetourBlocks;
    private int descentDetourTotalBlocks;
    private int descentDetourTurns;
    private boolean miningSupplyCheckDone;
    private int nextDangerCheckTick;
    private int lastMiningProgressDecisionTick;
    private BlockPos lastEmbeddedMiningTarget;
    private int lastEmbeddedMiningTargetTick;
    private OreProspectResult lastProspectResult;
    private StageMiningPlan stageMiningPlan;
    private DirectMiningRoute directMiningRoute;
    private int lastDirectMiningRouteLogTick;
    private MiningTunnelStepper.Plan currentTunnelStep;
    private int nextProspectScanTick;
    private int guidedTunnelBlocks;
    private int guidedBlocksSinceProspect;
    private int guidedDistanceSinceProspect;
    private int guidedActionsSinceProspect;
    private int prospectAboveLayerReplans;
    private boolean prospectAboveLayerRecoveryActive;
    private String lastAboveLayerReason;
    private BlockPos lastAboveLayerOrePos;
    private BlockPos lastAboveLayerRouteTarget;
    private String pendingProspectRescanReason;
    private String pendingProspectRescanOldPlan;
    private String pendingProspectRescanOldScore;
    private BlockPos pendingProspectRescanOldOrePos;
    private boolean pendingProspectSoftRescan;
    private BlockPos lastGuidedProgressPos;
    private StairStepPhase stairStepPhase;
    private Direction stairStepDirection;
    private BlockPos stairEntryHeadTarget;
    private BlockPos stairHorizontalTarget;
    private BlockPos stairVerticalTarget;
    private BlockPos stairStandTarget;
    private BlockPos pendingSupportPlacement;
    private BlockPos pendingSupportStand;
    private BlockPos pendingSupportHorizontalTarget;
    private BlockPos pendingSupportVerticalTarget;
    private Block pendingSupportBlock;
    private BlockPos pendingOcclusionClearTarget;
    private BlockPos pendingOcclusionOriginalTarget;
    private String pendingOcclusionContext;
    private BlockPos lastFailedBreakTarget;
    private String lastFailedBreakReason;
    private int consecutiveBreakFailures;
    private BlockPos stoneDescentStartTarget;
    private StoneAcquisitionPolicy.ScanResult lastStoneAcquisitionScan;
    private final Set<BlockPos> rejectedStoneDescentStarts = new HashSet<>();
    private int stoneDescentStartFailures;
    private BlockPos treeSearchAnchor;
    private BlockPos treeExploreTarget;
    private BlockPos treeTargetStand;
    private InteractionTarget activeInteractionTarget;
    private BlockPos activeStationPos;
    private InteractionFailureSnapshot lastInteractionFailure;
    private final InteractionFailureMemory interactionFailures = new InteractionFailureMemory();
    private int treeExploreAttempts;
    private int treeNoReachableScans;
    private int treeExploreFailures;
    private int nextTreeSearchTick;
    private TreeSearchResult lastTreeSearchResult;
    private TreeScanState treeScanState;
    private final Set<BlockPos> rejectedTreeExploreTargets = new HashSet<>();
    private int lastTreeProgressTick;
    private int lastTreeEffectiveProgressTick;
    private int treeTargetStartTick;
    private int treeTargetEffectiveProgressTick;
    private BlockPos lastIgnoredMiningDangerPos;
    private String lastIgnoredMiningDangerReason;
    private int lastIgnoredMiningDangerTick;

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
            case "deposit_chest" -> tickDeposit(step);
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
        if (advice.returnToSafePoint()) {
            boolean accepted = returnToSafeMiningPoint("deepseek", advice.reason(), advice.message());
            AiPlayerMod.info("mining_strategy", "AiPlayer '{}' applied DeepSeek mining strategy: return_to_safe_point accepted={}, strategy={}, message={}",
                aiPlayer.getAiPlayerName(), accepted, advice.strategy(), advice.message());
            return;
        }
        if (advice.switchToStairDescent()) {
            downwardStoneMode = true;
            targetPos = null;
            descentMoveTarget = null;
            descentMoveMode = null;
            breakTicks = 0;
            resetMovement();
            if (activeResourceState != null) {
                activeResourceState.markDownwardMode();
            }
            AiPlayerMod.info("mining_strategy", "AiPlayer '{}' applied DeepSeek mining strategy: switch to stair descent, strategy={}, message={}",
                aiPlayer.getAiPlayerName(), advice.strategy(), advice.message());
        }
    }

    public String getStatusDetails() {
        List<String> details = new ArrayList<>();
        if (miningRun != null) {
            details.add("挖矿状态：" + compactStatusText(miningRun.statusFields(), STATUS_DETAIL_SECTION_MAX_CHARS));
            details.add("挖矿摘要：" + compactStatusText(miningRun.summary(), 640));
        }
        if (miningRoutePlan != null) {
            details.add("路线计划：" + compactStatusText(miningRoutePlan.toLogText(), STATUS_DETAIL_SECTION_MAX_CHARS));
        }
        if (directMiningRoute != null) {
            details.add("直达路线：" + compactStatusText(directMiningRoute.toLogText(), STATUS_DETAIL_SECTION_MAX_CHARS));
        }
        if (stairDescentController != null) {
            details.add("阶梯下探：" + compactStatusText(stairDescentController.toLogText(), STATUS_DETAIL_SECTION_MAX_CHARS));
        }
        if (mainTunnelController != null) {
            details.add("主矿道：" + compactStatusText(mainTunnelController.toLogText(), STATUS_DETAIL_SECTION_MAX_CHARS));
        }
        if (branchMiningPattern != null) {
            details.add("鱼骨分支：" + compactStatusText(branchMiningPattern.toLogText(), STATUS_DETAIL_SECTION_MAX_CHARS));
        }
        if (pendingSupportPlacement != null) {
            details.add("待补支撑：" + pendingSupportPlacement.toShortString()
                + "/" + blockId(pendingSupportBlock));
        }
        if (currentTunnelStep != null) {
            details.add("通道阶段：" + currentTunnelStep.displayName()
                + "，目标=" + (currentTunnelStep.target() == null ? "none" : currentTunnelStep.target().toShortString())
                + "，原因=" + currentTunnelStep.reason());
        }
        if (activeMiningProfile != null && activeMiningProfile.prospectable()) {
            details.add("探矿冷却：" + miningCandidateCooldown.summary(ticks));
            details.add("探矿重扫计数：" + prospectRescanProgressText());
            if (pendingProspectRescanReason != null) {
                details.add("最近重扫：" + pendingProspectRescanReason);
            }
            if (lastProspectResult != null) {
                details.add("探矿候选：候选=" + lastProspectResult.candidates()
                    + "，拒绝=" + lastProspectResult.rejected()
                    + "，选择=" + (lastProspectResult.orePos() == null ? "none" : lastProspectResult.orePos().toShortString()));
            }
        }
        ResourceGatherSession.SafeMiningPoint safePoint = latestSafeMiningPoint();
        if (safePoint != null) {
            details.add("最近安全点：" + compactStatusText(safePoint.toLogText(), STATUS_DETAIL_SECTION_MAX_CHARS));
        }
        return compactStatusText(String.join("\n", details), STATUS_DETAIL_MAX_CHARS);
    }

    private String compactStatusText(String text, int maxChars) {
        if (text == null || text.isBlank() || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars - 18)) + "...（已截断）";
    }

    private void startStep(ExecutionStep step) {
        activeStep = step;
        miningFailurePolicy.reset();
        miningRun = null;
        miningRoutePlan = null;
        stairDescentController = null;
        mainTunnelController = null;
        branchMiningPattern = null;
        targetPos = null;
        targetEntity = null;
        descentMoveTarget = null;
        descentMoveMode = null;
        descentDirection = null;
        activeMiningProfile = MiningResource.findByItemOrSource(step.getItem(), step.getResource()).orElse(null);
        ticks = 0;
        breakTicks = 0;
        craftTicks = 0;
        downwardDigBlocks = 0;
        routeClearanceBlocks = 0;
        downwardNoTargetAttempts = 0;
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
        descentDetourDirection = null;
        descentDetourBlocks = 0;
        descentDetourTotalBlocks = 0;
        descentDetourTurns = 0;
        miningSupplyCheckDone = false;
        nextDangerCheckTick = 0;
        lastMiningProgressDecisionTick = 0;
        lastEmbeddedMiningTarget = null;
        lastEmbeddedMiningTargetTick = 0;
        lastProspectResult = null;
        stageMiningPlan = null;
        directMiningRoute = null;
        lastDirectMiningRouteLogTick = 0;
        currentTunnelStep = null;
        nextProspectScanTick = 0;
        guidedTunnelBlocks = 0;
        guidedBlocksSinceProspect = 0;
        guidedDistanceSinceProspect = 0;
        guidedActionsSinceProspect = 0;
        prospectAboveLayerReplans = 0;
        clearAboveLayerRecovery();
        clearPendingProspectRescanLog();
        lastGuidedProgressPos = null;
        clearStairStepPlan();
        clearPendingSupportPlacement();
        clearPendingOcclusionClearance();
        clearBreakFailureMemory();
        stoneDescentStartTarget = null;
        rejectedStoneDescentStarts.clear();
        stoneDescentStartFailures = 0;
        treeSearchAnchor = null;
        treeExploreTarget = null;
        treeTargetStand = null;
        activeInteractionTarget = null;
        activeStationPos = null;
        lastInteractionFailure = null;
        interactionFailures.clear();
        treeExploreAttempts = 0;
        treeNoReachableScans = 0;
        treeExploreFailures = 0;
        nextTreeSearchTick = 0;
        lastTreeSearchResult = null;
        treeScanState = null;
        rejectedTreeExploreTargets.clear();
        lastTreeProgressTick = 0;
        lastTreeEffectiveProgressTick = 0;
        treeTargetStartTick = 0;
        treeTargetEffectiveProgressTick = 0;
        lastIgnoredMiningDangerPos = null;
        lastIgnoredMiningDangerReason = null;
        lastIgnoredMiningDangerTick = 0;
        rejectedTargets.clear();
        exploredCaveEntrances.clear();
        failedSafeMiningPoints.clear();
        miningCandidateCooldown.clear();
        activeResourceState = resolveResourceState(step);
        sharedMiningState = isMiningRunStep(step) ? resourceGatherSession.miningState(aiPlayer.blockPosition()) : null;
        if (activeResourceState != null) {
            if (!isMiningRunStep(step)) {
                rejectedTargets.addAll(activeResourceState.getRejectedTargets());
            }
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
            stairDescentController = StairDescentController.start(
                aiPlayer.blockPosition(),
                miningRoutePlan.targetY(),
                miningRoutePlan.mainDirection()
            );
            mainTunnelController = MainTunnelController.start(
                miningRoutePlan.mainDirection(),
                miningRoutePlan.branchSegmentLength(),
                miningRoutePlan.maxBranchTurns(),
                aiPlayer.blockPosition()
            );
            branchMiningPattern = BranchMiningPattern.start(
                miningRoutePlan.mainDirection(),
                3,
                miningRoutePlan.branchSegmentLength()
            );
            descentDirection = stairDescentController.direction();
            miningRun.recordRoutePlan(miningRoutePlan);
            recordSafeMiningPoint("route_start", "start");
            seedNearbyCaveEntrance(activeMiningProfile);
            reuseMiningWaypointIfUseful();
        }
    }

    private StepResult tickGatherLog(ExecutionStep step) {
        int currentCount = goalChecker.countItem(aiPlayer, step.getItem());
        if (currentCount >= startCount + step.getCount()) {
            return StepResult.success("已采集 " + step.getItem() + " x" + step.getCount());
        }
        if (currentCount > lastProgressCount) {
            lastProgressCount = currentCount;
            ticksWithoutItemProgress = 0;
            markTreeProgress("tree_inventory_increased");
        } else {
            ticksWithoutItemProgress++;
        }
        int effectiveProgressTick = Math.max(lastTreeEffectiveProgressTick, 0);
        if (ticks - effectiveProgressTick > TREE_HARD_TIMEOUT_TICKS) {
            TreeSearchResult scan = lastTreeSearchResult;
            String scanText = scan == null ? "无完整扫描结果" : scan.toSummaryText();
            return StepResult.terminalFailure("砍树阶段长时间没有进展；" + scanText
                + "；最后交互目标=" + (activeInteractionTarget == null ? "none" : activeInteractionTarget.summary())
                + "；最后交互失败=" + (lastInteractionFailure == null ? "none" : lastInteractionFailure.summary())
                + "；当前位置=" + aiPlayer.blockPosition().toShortString()
                + "；请把 AI 移动到树林附近，或给 AI 放入任意原木/木板");
        }
        if (targetPos != null
            && activeInteractionTarget != null
            && treeTargetStartTick > 0
            && ticks - Math.max(treeTargetStartTick, treeTargetEffectiveProgressTick) > TREE_TARGET_NO_EFFECTIVE_PROGRESS_TICKS) {
            rejectInteractionTarget(activeInteractionTarget, "tree_target_no_effective_progress");
            return StepResult.running("当前树木目标长时间没有真实进展，正在重新寻找");
        }
        Item targetItem = itemFromId(step.getItem());
        if (targetPos == null || !isCurrentTreeSelectionValid(targetPos, targetItem)) {
            StepResult searchResult = selectTreeTargetOrExplore(targetItem);
            if (searchResult != null) {
                return searchResult;
            }
        }
        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        InteractionTarget interactionTarget = activeInteractionTarget != null
            && activeInteractionTarget.targetBlock().equals(targetPos)
            ? activeInteractionTarget
            : treeInteractionTarget(targetPos, treeTargetStand, treeTargetStand == null ? "legacy_direct" : "legacy_stand");
        StepResult moveResult = moveToInteractionTarget(interactionTarget, step, "树木");
        if (moveResult != null) {
            return moveResult;
        }
        StepResult validationResult = validateTreeInteractionBeforeBreak(interactionTarget, targetItem);
        if (validationResult != null) {
            return validationResult;
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
            SurvivalInteractionValidator.ValidationResult validation =
                SurvivalInteractionValidator.canBreakBlock(aiPlayer, targetPos, interactionTarget.reachRange());
            rejectInteractionTarget(interactionTarget, "break_failed:" + (validation.valid() ? "destroy_failed" : validation.reason()));
            targetPos = null;
            treeTargetStand = null;
            activeInteractionTarget = null;
            treeTargetStartTick = 0;
            treeTargetEffectiveProgressTick = 0;
            return StepResult.running("重新寻找树木");
        }
        targetPos = null;
        treeTargetStand = null;
        activeInteractionTarget = null;
        treeTargetStartTick = 0;
        treeTargetEffectiveProgressTick = 0;
        lastTreeSearchResult = null;
        treeScanState = null;
        nextTreeSearchTick = 0;
        treeNoReachableScans = 0;
        treeExploreFailures = 0;
        markTreeProgress("tree_block_broken");
        breakTicks = 0;
        return StepResult.running("已砍下一块原木");
    }

    private StepResult validateTreeInteractionBeforeBreak(InteractionTarget interactionTarget, Item targetItem) {
        if (interactionTarget == null) {
            return StepResult.failure("缺少砍树交互目标");
        }
        BlockPos target = interactionTarget.targetBlock();
        if (!aiPlayer.level().hasChunkAt(target)) {
            rejectInteractionTarget(interactionTarget, "target_chunk_unloaded");
            return StepResult.running("树木所在区块未加载，正在重新寻找");
        }
        Block block = aiPlayer.level().getBlockState(target).getBlock();
        if (!isMatchingLog(block, targetItem)) {
            rejectInteractionTarget(interactionTarget, "target_changed:" + blockId(block));
            return StepResult.running("树木目标已变化，正在重新寻找");
        }
        if (interactionTarget.hasStandPos()
            && !aiPlayer.blockPosition().equals(interactionTarget.standPos())
            && !canBreakTreeDirectly(target)) {
            rejectInteractionTarget(interactionTarget, "stand_not_reached");
            return StepResult.running("尚未到达树木工作站位，正在重新寻找");
        }
        SurvivalInteractionValidator.ValidationResult validation =
            SurvivalInteractionValidator.canBreakBlock(aiPlayer, target, interactionTarget.reachRange());
        if (!validation.valid()) {
            rejectInteractionTarget(interactionTarget, validation.reason());
            return StepResult.running("树木目标暂时无法交互：" + validation.reason());
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(target));
        double reach = interactionTarget.reachRange();
        if (distanceSq > reach * reach) {
            rejectInteractionTarget(interactionTarget, "out_of_reach:" + String.format("%.2f", Math.sqrt(distanceSq)));
            return StepResult.running("树木目标超出交互距离，正在重新寻找");
        }
        String preferredTool = SurvivalUtils.preferredToolForBlock(block);
        if (preferredTool != null && !preferredTool.equals(interactionTarget.toolType())) {
            rejectInteractionTarget(interactionTarget, "tool_mismatch:" + preferredTool);
            return StepResult.running("树木工具规则变化，正在重新寻找");
        }
        return null;
    }

    private StepResult moveToInteractionTarget(InteractionTarget interactionTarget, ExecutionStep step, String resourceName) {
        if (interactionTarget == null) {
            return StepResult.failure("缺少交互目标，无法执行 " + resourceName);
        }
        if (!interactionTarget.hasStandPos()) {
            if (!SurvivalUtils.moveNear(aiPlayer, interactionTarget.targetBlock(), interactionTarget.reachRange())) {
                breakTicks = 0;
                double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(interactionTarget.targetBlock()));
                if (distanceSq + 0.5D < closestTargetDistanceSq) {
                    closestTargetDistanceSq = distanceSq;
                    moveTicksWithoutProgress = 0;
                    if ("树木".equals(resourceName)) {
                        markTreeProgress("tree_move_closer");
                    }
                    return StepResult.running("正在接近" + resourceName);
                }
                moveTicksWithoutProgress++;
                if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
                    rejectInteractionTarget(interactionTarget, "movement_stuck");
                    return StepResult.running(resourceName + "交互目标不可达，正在重新寻找");
                }
                return StepResult.running("正在接近" + resourceName);
            }
            resetMovement();
            if ("树木".equals(resourceName)) {
                markTreeProgress("tree_target_in_reach");
            }
            return null;
        }
        BlockPos standPos = interactionTarget.standPos();
        if ("树木".equals(resourceName) && canBreakTreeDirectly(interactionTarget.targetBlock())) {
            aiPlayer.getNavigation().stop();
            aiPlayer.setSprinting(false);
            resetMovement();
            markTreeProgress("tree_target_in_reach");
            return null;
        }
        if (!canStandAt(standPos)) {
            rejectInteractionTarget(interactionTarget, "stand_no_longer_valid");
            return StepResult.running(resourceName + "工作站位失效，正在重新寻找");
        }
        if (aiPlayer.blockPosition().equals(standPos)) {
            aiPlayer.getNavigation().stop();
            aiPlayer.setSprinting(false);
            resetMovement();
            return null;
        }
        aiPlayer.setSprinting(true);
        aiPlayer.getNavigation().moveTo(
            standPos.getX() + 0.5D,
            standPos.getY(),
            standPos.getZ() + 0.5D,
            SurvivalUtils.TASK_RUN_SPEED
        );
        breakTicks = 0;
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(standPos));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            if ("树木".equals(resourceName)) {
                markTreeProgress("tree_move_closer");
            }
            return StepResult.running("正在移动到" + resourceName + "工作站位");
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            rejectInteractionTarget(interactionTarget, "stand_unreachable");
            return StepResult.running(resourceName + "工作站位不可达，正在重新寻找");
        }
        return StepResult.running("正在移动到" + resourceName + "工作站位");
    }

    private void rejectInteractionTarget(InteractionTarget interactionTarget, String reason) {
        if (interactionTarget == null) {
            return;
        }
        double distance = aiPlayer.position().distanceTo(Vec3.atCenterOf(interactionTarget.targetBlock()));
        lastInteractionFailure = new InteractionFailureSnapshot(
            interactionTarget,
            reason,
            aiPlayer.blockPosition().immutable(),
            distance,
            ticks
        );
        interactionFailures.remember(interactionTarget, reason, ticks, INTERACTION_FAILURE_TTL_TICKS);
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' rejected interaction target: {}, actualPos={}, reason={}, expiresAtTick={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            interactionTarget.summary(),
            aiPlayer.blockPosition().toShortString(),
            reason,
            interactionFailures.expiresAt(interactionTarget, ticks));
        if (miningRun != null) {
            miningRun.recordTargetRejected(ticks, interactionTarget.targetBlock(), "interaction:" + reason);
            AiPlayerMod.info("mining", "[taskId={}] mining interaction rejected: ai={}, targetStep={}, interaction={}, actualPos={}, reason={}, expiresAtTick={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                activeStep == null ? "unknown" : activeStep.describe(),
                interactionTarget.summary(),
                aiPlayer.blockPosition().toShortString(),
                reason,
                interactionFailures.expiresAt(interactionTarget, ticks));
        }
        if (targetPos != null && targetPos.equals(interactionTarget.targetBlock())) {
            targetPos = null;
        }
        if (treeTargetStand != null && interactionTarget.hasStandPos() && treeTargetStand.equals(interactionTarget.standPos())) {
            treeTargetStand = null;
        }
        if (activeInteractionTarget != null && activeInteractionTarget.targetBlock().equals(interactionTarget.targetBlock())) {
            activeInteractionTarget = null;
        }
        lastTreeSearchResult = null;
        treeScanState = null;
        nextTreeSearchTick = 0;
        resetMovement();
        breakTicks = 0;
        treeTargetStartTick = 0;
        treeTargetEffectiveProgressTick = 0;
    }

    private StepResult selectTreeTargetOrExplore(Item targetItem) {
        if (ticks >= nextTreeSearchTick || lastTreeSearchResult == null || treeScanState != null) {
            StepResult scanResult = tickTreeSearchScan(targetItem);
            if (scanResult != null) {
                return scanResult;
            }
        }
        if (lastTreeSearchResult != null
            && lastTreeSearchResult.selected().isPresent()
            && isCurrentTreeSelectionValid(lastTreeSearchResult.selected().get(), targetItem)) {
            BlockPos selectedTarget = lastTreeSearchResult.selected().get();
            boolean newTarget = targetPos == null || !targetPos.equals(selectedTarget);
            targetPos = selectedTarget;
            treeTargetStand = lastTreeSearchResult.selectedStand().orElse(null);
            activeInteractionTarget = lastTreeSearchResult.selectedInteraction().orElseGet(
                () -> treeInteractionTarget(targetPos, treeTargetStand, treeTargetStand == null ? "legacy_selected" : "legacy_selected_stand")
            );
            if (newTarget || treeTargetStartTick == 0) {
                treeTargetStartTick = ticks;
                treeTargetEffectiveProgressTick = 0;
            }
            AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' selected interaction target for tree: {}",
                taskId,
                aiPlayer.getAiPlayerName(),
                activeInteractionTarget.summary());
            treeExploreTarget = null;
            markTreeProgress("tree_target_selected");
            resetMovement();
            return null;
        }
        if (lastTreeSearchResult != null && lastTreeSearchResult.selected().isPresent()) {
            lastTreeSearchResult = null;
            treeScanState = null;
            treeTargetStand = null;
            activeInteractionTarget = null;
            nextTreeSearchTick = 0;
            return StepResult.running("树木目标已变化，正在重新扫描");
        }
        return exploreForTreeTarget();
    }

    private boolean isCurrentTreeSelectionValid(BlockPos pos, Item targetItem) {
        return pos != null
            && !rejectedTargets.contains(pos)
            && isMatchingLog(aiPlayer.level().getBlockState(pos).getBlock(), targetItem);
    }

    private StepResult tickTreeSearchScan(Item targetItem) {
        if (treeScanState == null) {
            treeScanState = TreeScanState.start(aiPlayer, TREE_NEAR_SEARCH_RADIUS, TREE_NEAR_VERTICAL_RADIUS);
        }
        if (!scanTreeTargets(targetItem, treeScanState)) {
            return StepResult.running("正在扫描附近树木");
        }
        if (!checkTreePathCandidates(treeScanState)) {
            return StepResult.running("正在验证可到达的树木");
        }
        TreeSearchResult completed = finishTreeScan(treeScanState);
        logTreeSearchResult(completed);
        if (completed.selected().isEmpty() && completed.horizontalRadius() == TREE_NEAR_SEARCH_RADIUS) {
            treeScanState = TreeScanState.start(aiPlayer, TREE_EXTENDED_SEARCH_RADIUS, TREE_EXTENDED_VERTICAL_RADIUS);
            lastTreeSearchResult = completed;
            return StepResult.running("近处没有找到可砍树木，正在扩大搜索范围");
        }
        treeScanState = null;
        lastTreeSearchResult = completed;
        nextTreeSearchTick = ticks + TREE_SEARCH_INTERVAL_TICKS;
        if (completed.candidates() > 0 && completed.reachable() == 0) {
            treeNoReachableScans++;
            if (treeNoReachableScans >= TREE_NO_REACHABLE_SCAN_LIMIT) {
                return StepResult.terminalFailure("附近发现树木但找不到可站立砍树位置；" + completed.toSummaryText()
                    + "；请移动到树林平坦地面附近，或给 AI 放入任意原木/木板");
            }
        } else if (completed.reachable() > 0) {
            treeNoReachableScans = 0;
            markTreeProgress("tree_reachable_scan");
        }
        return null;
    }

    private boolean scanTreeTargets(Item targetItem, TreeScanState state) {
        Level level = aiPlayer.level();
        int scanned = 0;
        while (scanned < TREE_SCAN_BLOCK_BUDGET_PER_TICK && !state.isComplete()) {
            BlockPos pos = state.currentPos();
            state.advance();
            scanned++;
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            Block block = level.getBlockState(pos).getBlock();
            if (!isMatchingLog(block, targetItem)) {
                continue;
            }
            state.candidates++;
            if (rejectedTargets.contains(pos)) {
                state.reject("target_rejected");
                continue;
            }
            state.addPathCandidate(pos.immutable());
        }
        return state.isComplete();
    }

    private TreeSearchResult finishTreeScan(TreeScanState state) {
        return new TreeSearchResult(
            state.horizontalRadius,
            state.verticalRadius,
            state.candidates,
            state.reachable,
            state.rejected,
            state.pathChecked,
            state.selected,
            state.selectedStand,
            state.selectedInteractionTarget,
            Map.copyOf(state.rejectionReasons)
        );
    }

    private boolean checkTreePathCandidates(TreeScanState state) {
        int checkedThisTick = 0;
        while (checkedThisTick < TREE_SCAN_PATH_CHECKS_PER_TICK && state.pathCheckIndex < state.pathCandidates.size()) {
            BlockPos candidate = state.pathCandidates.get(state.pathCheckIndex++);
            state.pathChecked++;
            checkedThisTick++;
            TreeWorkTarget workTarget = resolveTreeWorkTarget(candidate);
            if (workTarget == null) {
                state.reject("no_reachable_stand");
                continue;
            }
            Optional<InteractionFailureKey> rejectedInteraction = interactionFailures.rejectionFor(workTarget.interactionTarget(), ticks);
            if (rejectedInteraction.isPresent()) {
                state.reject("interaction_rejected:" + rejectedInteraction.get().reason());
                continue;
            }
            state.reachable++;
            double score = workTarget.score(aiPlayer.blockPosition());
            if (isBetterTreeWorkTarget(workTarget, score, state)) {
                state.selectedScore = score;
                state.selected = workTarget.logPos();
                state.selectedStand = workTarget.standPos();
                state.selectedInteractionTarget = workTarget.interactionTarget();
            }
        }
        return state.pathCheckIndex >= state.pathCandidates.size();
    }

    private boolean isBetterTreeWorkTarget(TreeWorkTarget workTarget, double score, TreeScanState state) {
        if (state.selected == null) {
            return true;
        }
        return score < state.selectedScore;
    }

    private boolean isReachableTreeLog(BlockPos pos) {
        return resolveTreeWorkTarget(pos) != null;
    }

    private TreeWorkTarget resolveTreeWorkTarget(BlockPos logPos) {
        if (logPos == null) {
            return null;
        }
        if (canBreakTreeDirectly(logPos)) {
            BlockPos directStand = aiPlayer.blockPosition().immutable();
            return new TreeWorkTarget(
                logPos.immutable(),
                directStand,
                0.0D,
                "direct",
                treeInteractionTarget(logPos, null, "direct")
            );
        }
        Optional<BlockPos> stand = reachableTreeWorkStand(logPos);
        return stand.map(pos -> new TreeWorkTarget(
            logPos.immutable(),
            pos,
            verticalPenalty(logPos, pos),
            "stand",
            treeInteractionTarget(logPos, pos, "reachable_stand")
        )).orElse(null);
    }

    private InteractionTarget treeInteractionTarget(BlockPos logPos, BlockPos standPos, String reason) {
        return new InteractionTarget(
            logPos.immutable(),
            standPos == null ? null : standPos.immutable(),
            TreeTargetPolicy.SAFE_TREE_REACH,
            "axe",
            InteractionActionType.BREAK_BLOCK,
            reason
        );
    }

    private Optional<BlockPos> reachableTreeWorkStand(BlockPos logPos) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(logPos.offset(-4, -3, -4), logPos.offset(4, 2, 4))) {
            BlockPos stand = candidate.immutable();
            if (!aiPlayer.level().hasChunkAt(stand) || !canStandAt(stand)) {
                continue;
            }
            if (!TreeTargetPolicy.isWithinSafeReach(TreeTargetPolicy.standFeetToBlockCenterDistanceSq(stand, logPos))) {
                continue;
            }
            if (!canReachTreeFromStand(stand, logPos)) {
                continue;
            }
            if (aiPlayer.getNavigation().createPath(stand, 1) == null) {
                continue;
            }
            double score = stand.distSqr(aiPlayer.blockPosition()) + verticalPenalty(logPos, stand);
            if (score < bestScore) {
                bestScore = score;
                best = stand;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean canBreakTreeDirectly(BlockPos logPos) {
        return SurvivalInteractionValidator.canBreakBlock(aiPlayer, logPos, TreeTargetPolicy.SAFE_TREE_REACH).valid();
    }

    private boolean canReachTreeFromStand(BlockPos standPos, BlockPos logPos) {
        Vec3 eye = new Vec3(
            standPos.getX() + 0.5D,
            standPos.getY() + aiPlayer.getEyeHeight(),
            standPos.getZ() + 0.5D
        );
        return SurvivalInteractionValidator.hasClearReachFrom(aiPlayer, eye, logPos);
    }

    private double verticalPenalty(BlockPos logPos, BlockPos standPos) {
        return Math.abs(logPos.getY() - standPos.getY()) * 8.0D;
    }

    private void markTreeProgress(String reason) {
        lastTreeProgressTick = ticks;
        if (isEffectiveTreeProgress(reason)) {
            lastTreeEffectiveProgressTick = ticks;
            if (targetPos != null) {
                treeTargetEffectiveProgressTick = ticks;
            }
        }
        AiPlayerMod.debug("make_item", "[taskId={}] AiPlayer '{}' tree progress: reason={}, tick={}, pos={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            ticks,
            aiPlayer.blockPosition().toShortString());
    }

    private boolean isEffectiveTreeProgress(String reason) {
        return "tree_block_broken".equals(reason)
            || "tree_inventory_increased".equals(reason)
            || "tree_move_closer".equals(reason)
            || "tree_explore_point_reached".equals(reason);
    }

    private void logTreeSearchResult(TreeSearchResult scan) {
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' tree search scan: targetStep={}, radius={}, vertical={}, candidates={}, pathChecked={}, reachable={}, rejected={}, selected={}, stand={}, reasons={}, pos={}, exploreTarget={}, attempts={}, noReachableScans={}, exploreFailures={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            activeStep == null ? "unknown" : activeStep.describe(),
            scan.horizontalRadius(),
            scan.verticalRadius(),
            scan.candidates(),
            scan.pathChecked(),
            scan.reachable(),
            scan.rejected(),
            scan.selected().map(BlockPos::toShortString).orElse("none"),
            scan.selectedStand().map(BlockPos::toShortString).orElse("none"),
            scan.rejectionReasons(),
            aiPlayer.blockPosition().toShortString(),
            treeExploreTarget == null ? "none" : treeExploreTarget.toShortString(),
            treeExploreAttempts,
            treeNoReachableScans,
            treeExploreFailures);
    }

    private StepResult exploreForTreeTarget() {
        if (treeSearchAnchor == null) {
            treeSearchAnchor = aiPlayer.blockPosition().immutable();
        }
        if (treeExploreTarget == null
            || aiPlayer.blockPosition().closerThan(treeExploreTarget, 2.5D)
            || !canStandAt(treeExploreTarget)) {
            if (treeExploreTarget != null && aiPlayer.blockPosition().closerThan(treeExploreTarget, 2.5D)) {
                AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' reached tree exploration point: pos={}, attempts={}",
                    taskId,
                    aiPlayer.getAiPlayerName(),
                    treeExploreTarget.toShortString(),
                    treeExploreAttempts);
                nextTreeSearchTick = 0;
                markTreeProgress("tree_explore_point_reached");
            }
            treeExploreTarget = nextTreeExploreTarget();
            resetMovement();
        }
        if (treeExploreTarget == null) {
            if (treeExploreAttempts < TREE_EXPLORE_MAX_POINTS) {
                return StepResult.running("正在选择下一个树木搜索点");
            }
            TreeSearchResult scan = lastTreeSearchResult;
            String scanText = scan == null
                ? "尚未完成树木扫描"
                : scan.toSummaryText();
            return StepResult.terminalFailure("附近和探索路线都没有找到可砍的树；" + scanText
                + "；请把 AI 移动到树林附近，或给 AI 放入原木/木板/工具");
        }
        if (SurvivalUtils.moveNear(aiPlayer, treeExploreTarget, 2.5D)) {
            nextTreeSearchTick = 0;
            treeExploreTarget = null;
            resetMovement();
            markTreeProgress("tree_explore_point_reached");
            return StepResult.running("已到达树木搜索点，正在重新扫描树木");
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(treeExploreTarget));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            markTreeProgress("tree_explore_move_closer");
            return StepResult.running("正在向外搜索可砍树木");
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            treeExploreFailures++;
            rejectedTreeExploreTargets.add(treeExploreTarget.immutable());
            AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' tree exploration point unreachable: point={}, current={}, attempts={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                treeExploreTarget.toShortString(),
                aiPlayer.blockPosition().toShortString(),
                treeExploreAttempts);
            if (treeExploreFailures >= TREE_EXPLORE_FAILURE_LIMIT) {
                TreeSearchResult scan = lastTreeSearchResult;
                String scanText = scan == null ? "尚未完成树木扫描" : scan.toSummaryText();
                return StepResult.terminalFailure("树木探索点连续不可达；失败次数=" + treeExploreFailures
                    + "；" + scanText + "；请把 AI 移动到树林平坦地面附近，或给 AI 放入任意原木/木板");
            }
            treeExploreTarget = null;
            resetMovement();
            nextTreeSearchTick = 0;
            return StepResult.running("树木搜索点不可达，正在换一个方向搜索");
        }
        return StepResult.running("正在向外搜索可砍树木");
    }

    private BlockPos nextTreeExploreTarget() {
        if (treeExploreAttempts >= TREE_EXPLORE_MAX_POINTS) {
            return null;
        }
        Direction[] directions = orderedTreeExploreDirections();
        int attempt = treeExploreAttempts++;
        int ring = attempt / directions.length + 1;
        Direction direction = directions[attempt % directions.length];
        BlockPos rough = treeSearchAnchor.relative(direction, TREE_EXPLORE_STEP_BLOCKS * ring);
        Optional<BlockPos> stand = nearestReachableStandAround(rough, TREE_EXPLORE_STAND_SEARCH_RADIUS, TREE_EXPLORE_STAND_VERTICAL_RADIUS);
        if (stand.isPresent()) {
            AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' selected tree exploration point: point={}, rough={}, direction={}, ring={}, attempts={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                stand.get().toShortString(),
                rough.toShortString(),
                direction.getName(),
                ring,
                treeExploreAttempts);
            return stand.get();
        }
        return null;
    }

    private Direction[] orderedTreeExploreDirections() {
        Direction primary = horizontalDirectionFromYaw();
        return new Direction[] {
            primary,
            primary.getClockWise(),
            primary.getCounterClockWise(),
            primary.getOpposite()
        };
    }

    private Optional<BlockPos> nearestReachableStandAround(BlockPos rough, int horizontalRadius, int verticalRadius) {
        BlockPos current = aiPlayer.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(
            rough.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
            rough.offset(horizontalRadius, verticalRadius, horizontalRadius)
        )) {
            BlockPos pos = candidate.immutable();
            if (!aiPlayer.level().hasChunkAt(pos) || !canStandAt(pos) || isRejectedTreeExploreTarget(pos)) {
                continue;
            }
            double distance = pos.distSqr(current);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        int pathChecks = 0;
        if (best != null && aiPlayer.getNavigation().createPath(best, 1) != null) {
            return Optional.of(best);
        }
        for (BlockPos candidate : BlockPos.betweenClosed(
            rough.offset(-horizontalRadius, -verticalRadius, -horizontalRadius),
            rough.offset(horizontalRadius, verticalRadius, horizontalRadius)
        )) {
            if (pathChecks >= TREE_EXPLORE_PATH_CHECK_LIMIT) {
                break;
            }
            BlockPos pos = candidate.immutable();
            if (!aiPlayer.level().hasChunkAt(pos) || !canStandAt(pos) || isRejectedTreeExploreTarget(pos)) {
                continue;
            }
            pathChecks++;
            if (aiPlayer.getNavigation().createPath(pos, 1) != null) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    private boolean isRejectedTreeExploreTarget(BlockPos pos) {
        for (BlockPos rejected : rejectedTreeExploreTargets) {
            if (rejected.closerThan(pos, 4.0D)) {
                return true;
            }
        }
        return false;
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
            return miningTerminalFailure("stone_no_progress", "采集石头长时间没有进展，请移动到露天石头、矿洞或给 AI 放入圆石"
                + "；最后交互目标=" + (activeInteractionTarget == null ? "none" : activeInteractionTarget.summary())
                + "；最后交互失败=" + (lastInteractionFailure == null ? "none" : lastInteractionFailure.summary())
                + "；当前位置=" + aiPlayer.blockPosition().toShortString());
        }
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            setMiningState(MiningState.PREPARE_TOOLS, "ensure_pickaxe_for_stone");
            aiPlayer.craftWoodenPickaxe();
        }
        if (aiPlayer.getBestToolStackFor("pickaxe").isEmpty()) {
            return StepResult.failure("缺少镐，无法挖石头");
        }
        if (!ensureMiningInventorySpace(itemFromId(step.getItem()), "采集石头")) {
            return miningTerminalFailure("inventory_full", "AI 背包已满，无法继续采集石头，请先取出材料或给附近箱子留出存放流程");
        }
        StepResult occlusionClearance = tickPendingOcclusionClearance();
        if (occlusionClearance != null) {
            return occlusionClearance;
        }
        if (downwardStoneMode) {
            return tickDigDownForStone(step);
        }
        if (targetPos == null || !isStoneCandidate(targetPos, aiPlayer.level().getBlockState(targetPos).getBlock())) {
            Optional<InteractionTarget> found = findReachableStoneInteractionTarget();
            if (found.isEmpty()) {
                downwardStoneMode = true;
                if (activeResourceState != null) {
                    activeResourceState.markDownwardMode();
                }
                String scanText = lastStoneAcquisitionScan == null ? "stoneScan{not_run}" : lastStoneAcquisitionScan.toLogText();
                AiPlayerMod.debug("make_item", "AiPlayer '{}' enters downward stone search from {} with {}/{} {}, {}",
                    aiPlayer.getAiPlayerName(),
                    aiPlayer.blockPosition(),
                    currentCount - startCount,
                    step.getCount(),
                    step.getItem(),
                    scanText);
                AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' stone acquisition falls back to stair descent: step={}, {}",
                    taskId,
                    aiPlayer.getAiPlayerName(),
                    step.describe(),
                    scanText);
                return tickDigDownForStone(step);
            }
            activeInteractionTarget = found.get();
            targetPos = activeInteractionTarget.targetBlock();
            resetMovement();
            ticksWithoutItemProgress = 0;
            lastProgressCount = currentCount;
        }
        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        setMiningState(MiningState.TRAVEL_TO_ORE, "move_to_stone");
        InteractionTarget interactionTarget = activeInteractionTarget != null
            && activeInteractionTarget.targetBlock().equals(targetPos)
            ? activeInteractionTarget
            : stoneInteractionTarget(targetPos, null, "legacy_stone_direct");
        StepResult moveResult = moveToInteractionTarget(interactionTarget, step, "石头");
        if (moveResult != null) {
            return moveResult;
        }
        StepResult validationResult = validateStoneInteractionBeforeBreak(interactionTarget);
        if (validationResult != null) {
            return validationResult;
        }
        resetMovement();
        setMiningState(MiningState.MINING, "mine_stone");
        Block targetBlock = aiPlayer.level().getBlockState(targetPos).getBlock();
        recordMiningDigAttempt(targetPos, targetBlock);
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < 36) {
            return StepResult.running("正在挖石头");
        }
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(targetPos, "stone_visible");
        recordMiningDigResult(targetPos, targetBlock, breakResult);
        if (!breakResult.success()) {
            breakTicks = 0;
            if (queueOcclusionClearance(targetPos, breakResult, "stone_visible")) {
                return StepResult.running("石头被视线遮挡，先清理遮挡方块");
            }
            String reason = "break_failed:" + breakResult.reason();
            rejectInteractionTarget(interactionTarget, reason);
            if (registerBreakFailure(targetPos, breakResult.reason())) {
                rejectTarget(targetPos, reason);
            }
            targetPos = null;
            activeInteractionTarget = null;
            return StepResult.running("重新寻找石头");
        }
        if (activeResourceState != null) {
            activeResourceState.recordSuccess(targetPos, aiPlayer.blockPosition());
        }
        targetPos = null;
        activeInteractionTarget = null;
        breakTicks = 0;
        return StepResult.running("已挖下一块石头");
    }

    private StepResult tickDigDownForStone(ExecutionStep step) {
        setMiningState(MiningState.DESCEND, isProspectingForOre() ? "prospect_descent" : "stone_descent");
        int descentLimit = currentDescentLimit();
        if (downwardDigBlocks >= descentLimit) {
            return recoverableMiningFailure(
                MiningFailureType.DESCENT_LIMIT,
                "descent_limit",
                "向下搜索 " + descentLimit + " 个方块仍未找到可采集目标"
            );
        }
        if (!isProspectingForOre()) {
            StepResult startMove = ensureStoneDescentStart();
            if (startMove != null) {
                return startMove;
            }
        }

        if (descentMoveTarget != null) {
            String moveMode = descentMoveModeOr(isProspectingForOre() ? "prospect_move_lower" : "descent_move_target");
            StepResult moveResult = tickDescentMoveTarget(
                moveMode,
                "正在移动到可继续下挖的位置",
                "已到达可继续下挖的位置",
                "下方站位不可达，改为就地挖阶梯"
            );
            if (moveResult != null) {
                return moveResult;
            }
        }

        StepResult stairMove = moveToCompletedStairStep();
        if (stairMove != null) {
            return stairMove;
        }

        BlockPos digTarget = findDownwardDigTarget();
        if (digTarget == null) {
            StepResult supportResult = placePendingMiningSupport();
            if (supportResult != null) {
                return supportResult;
            }
            StepResult plannedMove = moveToCompletedStairStep();
            if (plannedMove != null) {
                return plannedMove;
            }
            if (!isProspectingForOre() && downwardNoTargetAttempts < STONE_DESCENT_START_FAILURE_LIMIT) {
                downwardNoTargetAttempts++;
                rejectCurrentStoneDescentStart("no_stair_target");
                return StepResult.running("当前起挖点无法继续形成阶梯，正在换开阔起挖点");
            }
            StepResult detourResult = tickDescentDetour(step);
            if (detourResult != null) {
                return detourResult;
            }
            return recoverableMiningFailure(
                MiningFailureType.NO_TARGET,
                "no_downward_target",
                "附近和向下搜索范围内没有找到可挖石头，请移动到矿洞、低处或给 AI 放入圆石；" + descentDetourSummary()
            );
        }
        downwardNoTargetAttempts = 0;
        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        String targetDanger = blockingMiningDanger(digTarget, "descent_target");
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, digTarget, "skip_descent_target");
            breakTicks = 0;
            return stopMiningTask("unsafe_descent_target:" + targetDanger,
                "下挖目标存在环境风险：" + targetDanger + "，位置 " + digTarget.toShortString());
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
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(digTarget, "stone_descent");
        if (!breakResult.success()) {
            breakTicks = 0;
            recordMiningDigResult(digTarget, block, breakResult);
            if (queueOcclusionClearance(digTarget, breakResult, "stone_descent")) {
                return StepResult.running("下挖目标被视线遮挡，先清理遮挡方块");
            }
            if (registerBreakFailure(digTarget, breakResult.reason())) {
                rejectTarget(digTarget, "descent_break_failed:" + breakResult.reason());
                rejectCurrentStairStep("descent_break_failed:" + breakResult.reason());
            }
            return StepResult.running("重新选择向下挖的位置");
        }
        descentDetourBlocks = 0;
        descentDetourTurns = 0;
        recordMiningDigResult(digTarget, block, breakResult);
        advanceStairStepAfterBreak(digTarget);
        recordGuidedProspectDigProgress();
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

    private StepResult moveToCompletedStairStep() {
        if (stairStepPhase == StairStepPhase.MOVE && stairStandTarget != null && canStandAt(stairStandTarget)) {
            setDescentMoveTarget(stairStandTarget, isProspectingForOre() ? "prospect_stair_step_move" : "stone_stair_step_move");
            return StepResult.running("阶梯横挖和下挖已完成，正在移动到下一阶");
        }
        if (stairStepPhase == StairStepPhase.MOVE && stairStandTarget != null && !canStandAt(stairStandTarget)) {
            rejectCurrentStairStep("stand_no_longer_valid");
            return StepResult.running("阶梯站位失效，正在换方向继续下挖");
        }
        return null;
    }

    private StepResult tickDescentDetour(ExecutionStep step) {
        if (!isMiningRunStep(step)
            || descentDetourTotalBlocks >= DESCENT_DETOUR_MAX_BLOCKS
            || descentDetourTurns >= DESCENT_DETOUR_MAX_TURNS) {
            return null;
        }
        if (descentDetourBlocks >= DESCENT_DETOUR_SEGMENT_BLOCKS) {
            rotateDescentDetourDirection("segment_complete");
            return StepResult.running("当前阶梯方向暂时无法继续下探，已水平绕行 "
                + DESCENT_DETOUR_SEGMENT_BLOCKS + " 格，正在换方向重新寻找下探入口");
        }

        Direction direction = descentDetourDirection == null
            ? (descentDirection == null ? horizontalDirectionFromYaw() : descentDirection)
            : descentDetourDirection;
        List<String> blocked = new ArrayList<>();
        for (int attempt = 0; attempt < 4; attempt++) {
            BlockPos nextStand = aiPlayer.blockPosition().relative(direction);
            if (canStandAt(nextStand) && !rejectedTargets.contains(nextStand)) {
                descentDetourDirection = direction;
                setDescentMoveTarget(nextStand, isProspectingForOre() ? "prospect_descent_detour_move" : "stone_descent_detour_move");
                markMiningMode(isProspectingForOre() ? "prospect_descent_detour" : "stone_descent_detour");
                return StepResult.running("阶梯下探暂时受阻，正在当前层水平前进后重新下探");
            }
            MiningTunnelStepper.Plan tunnelStep = miningTunnelStep(aiPlayer.blockPosition(), nextStand);
            if (queueSupportFromTunnelStep(tunnelStep, aiPlayer.blockPosition(), nextStand, "descent_detour")) {
                return StepResult.running("矿道前方缺少支撑，正在补支撑后继续水平绕行");
            }
            if (tunnelStep.blocked()) {
                blocked.add(direction.getName() + ":" + tunnelStep.reason());
                direction = direction.getClockWise();
                continue;
            }
            if (tunnelStep.needsClearance()) {
                descentDetourDirection = direction;
                return tickMiningRouteClearance(
                    tunnelStep.target(),
                    nextStand,
                    isProspectingForOre() ? "prospect_descent_detour" : "stone_descent_detour",
                    "descent_detour:" + tunnelStep.reason()
                );
            }
            blocked.add(direction.getName() + ":ready_but_not_standable");
            direction = direction.getClockWise();
        }
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' descent detour blocked: current={}, blocked={}, segmentMoves={}, totalMoves={}/{}, routeClearanceBlocks={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            aiPlayer.blockPosition().toShortString(),
            blocked,
            descentDetourBlocks,
            descentDetourTotalBlocks,
            DESCENT_DETOUR_MAX_BLOCKS,
            routeClearanceBlocks);
        rotateDescentDetourDirection("all_directions_blocked");
        if (descentDetourTurns >= DESCENT_DETOUR_MAX_TURNS) {
            return null;
        }
        return StepResult.running("当前层水平绕行也被阻挡，正在换方向继续寻找下探入口");
    }

    private StepResult validateStoneInteractionBeforeBreak(InteractionTarget interactionTarget) {
        if (interactionTarget == null) {
            return StepResult.failure("缺少石头交互目标");
        }
        BlockPos target = interactionTarget.targetBlock();
        if (!aiPlayer.level().hasChunkAt(target)) {
            rejectInteractionTarget(interactionTarget, "target_chunk_unloaded");
            return StepResult.running("石头所在区块未加载，正在重新寻找");
        }
        Block block = aiPlayer.level().getBlockState(target).getBlock();
        if (!SurvivalUtils.isStone(block) || !hasAirNeighbor(target)) {
            rejectInteractionTarget(interactionTarget, "target_changed:" + blockId(block));
            return StepResult.running("石头目标已变化，正在重新寻找");
        }
        if (interactionTarget.hasStandPos() && !aiPlayer.blockPosition().equals(interactionTarget.standPos())) {
            rejectInteractionTarget(interactionTarget, "stand_not_reached");
            return StepResult.running("尚未到达石头工作站位，正在重新寻找");
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(target));
        double reach = interactionTarget.reachRange();
        if (distanceSq > reach * reach) {
            rejectInteractionTarget(interactionTarget, "out_of_reach:" + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq)));
            return StepResult.running("石头目标超出交互距离，正在重新寻找");
        }
        String preferredTool = SurvivalUtils.preferredToolForBlock(block);
        if (preferredTool != null && !preferredTool.equals(interactionTarget.toolType())) {
            rejectInteractionTarget(interactionTarget, "tool_mismatch:" + preferredTool);
            return StepResult.running("石头工具规则变化，正在重新寻找");
        }
        return null;
    }

    private Optional<InteractionTarget> findReachableStoneInteractionTarget() {
        StoneAcquisitionPolicy.ScanResult scan = scanStoneAcquisitionTargets();
        lastStoneAcquisitionScan = scan;
        AiPlayerMod.debug("mining", "[taskId={}] AiPlayer '{}' stone acquisition scan: step={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            activeStep == null ? "unknown" : activeStep.describe(),
            scan.toLogText());
        if (!scan.hasUsableTarget()) {
            return Optional.empty();
        }
        StoneAcquisitionPolicy.Candidate selected = scan.selected();
        return Optional.of(stoneInteractionTarget(selected.stonePos(), selected.standPos(), selected.targetReason()));
    }

    private StoneAcquisitionPolicy.ScanResult scanStoneAcquisitionTargets() {
        StoneAcquisitionPolicy.ScanBuilder builder = StoneAcquisitionPolicy.builder();
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        int minY = Math.max(level.getMinY(), center.getY() - STONE_SCAN_VERTICAL_RADIUS);
        int maxY = Math.min(level.getMinY() + level.getHeight() - 1, center.getY() + STONE_SCAN_VERTICAL_RADIUS);
        List<StoneAcquisitionPolicy.Candidate> exposedCandidates = new ArrayList<>();
        for (int x = center.getX() - STONE_SCAN_HORIZONTAL_RADIUS; x <= center.getX() + STONE_SCAN_HORIZONTAL_RADIUS; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = center.getZ() - STONE_SCAN_HORIZONTAL_RADIUS; z <= center.getZ() + STONE_SCAN_HORIZONTAL_RADIUS; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.hasChunkAt(pos)) {
                        continue;
                    }
                    Block block = level.getBlockState(pos).getBlock();
                    if (!SurvivalUtils.isStone(block)) {
                        continue;
                    }
                    if (rejectedTargets.contains(pos)) {
                        builder.recordRejected("previously_rejected:" + rejectedTargetReason(pos));
                        continue;
                    }
                    int exposedAir = countAirNeighbors(pos);
                    double distance = pos.distSqr(center);
                    if (exposedAir <= 0) {
                        builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
                            pos,
                            null,
                            StoneAcquisitionPolicy.Category.NOT_VISIBLE,
                            distance,
                            exposedAir
                        ));
                        continue;
                    }
                    InteractionTarget directTarget = stoneInteractionTarget(pos, null, "stone_direct");
                    Optional<InteractionFailureKey> directRejected = interactionFailures.rejectionFor(directTarget, ticks);
                    if (directRejected.isPresent()) {
                        builder.recordRejected("interaction_rejected:" + directRejected.get().reason());
                        continue;
                    }
                    if (canWorkBlockDirectly(pos, 3.5D)) {
                        builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
                            pos,
                            null,
                            StoneAcquisitionPolicy.Category.DIRECT_WORKABLE,
                            distance,
                            exposedAir
                        ));
                        continue;
                    }
                    exposedCandidates.add(new StoneAcquisitionPolicy.Candidate(
                        pos,
                        null,
                        StoneAcquisitionPolicy.Category.VISIBLE_UNREACHABLE,
                        distance,
                        exposedAir
                    ));
                }
            }
        }
        for (StoneAcquisitionPolicy.Candidate candidate : StoneAcquisitionPolicy.orderForPathChecks(exposedCandidates)) {
            if (builder.pathChecks() >= STONE_SCAN_PATH_CHECK_LIMIT) {
                builder.recordPathBudgetSkipped();
                builder.recordCandidate(candidate);
                continue;
            }
            builder.recordPathCheck();
            Optional<BlockPos> stand = reachableWorkStand(candidate.stonePos());
            if (stand.isEmpty()) {
                builder.recordCandidate(candidate);
                continue;
            }
            InteractionTarget reachableTarget = stoneInteractionTarget(candidate.stonePos(), stand.get(), "stone_reachable_stand");
            Optional<InteractionFailureKey> reachableRejected = interactionFailures.rejectionFor(reachableTarget, ticks);
            if (reachableRejected.isPresent()) {
                builder.recordRejected("interaction_rejected:" + reachableRejected.get().reason());
                continue;
            }
            builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
                candidate.stonePos(),
                stand.get(),
                StoneAcquisitionPolicy.Category.REACHABLE_STAND,
                candidate.distanceSq(),
                candidate.exposedAir()
            ));
        }
        return builder.build();
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
        if (pos == null || Math.abs(pos.getY() - aiPlayer.blockPosition().getY()) > 2) {
            return false;
        }
        return reachableWorkStand(pos).isPresent();
    }

    private Optional<StoneWorkTarget> resolveStoneWorkTarget(BlockPos stonePos) {
        if (stonePos == null) {
            return Optional.empty();
        }
        if (canWorkBlockDirectly(stonePos, 3.5D)) {
            return Optional.of(new StoneWorkTarget(
                stonePos.immutable(),
                aiPlayer.blockPosition().immutable(),
                0.0D,
                stoneInteractionTarget(stonePos, null, "stone_direct")
            ));
        }
        return reachableWorkStand(stonePos).map(stand -> new StoneWorkTarget(
            stonePos.immutable(),
            stand,
            verticalPenalty(stonePos, stand),
            stoneInteractionTarget(stonePos, stand, "stone_reachable_stand")
        ));
    }

    private InteractionTarget stoneInteractionTarget(BlockPos stonePos, BlockPos standPos, String reason) {
        return new InteractionTarget(
            stonePos.immutable(),
            standPos == null ? null : standPos.immutable(),
            3.5D,
            "pickaxe",
            InteractionActionType.BREAK_BLOCK,
            reason
        );
    }

    private Optional<BlockPos> reachableWorkStand(BlockPos targetBlock) {
        if (targetBlock == null) {
            return Optional.empty();
        }
        for (Direction direction : horizontalDirections()) {
            BlockPos candidate = targetBlock.relative(direction);
            if (canStandAt(candidate) && aiPlayer.getNavigation().createPath(candidate, 1) != null) {
                return Optional.of(candidate.immutable());
            }
        }
        BlockPos below = targetBlock.below();
        if (canStandAt(below) && aiPlayer.getNavigation().createPath(below, 1) != null) {
            return Optional.of(below.immutable());
        }
        return Optional.empty();
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
            aiPlayer.setSprinting(true);
            aiPlayer.getNavigation().moveTo(targetEntity, SurvivalUtils.TASK_RUN_SPEED);
            return StepResult.running("正在接近 " + entityTypeId);
        }
        aiPlayer.getNavigation().stop();
        aiPlayer.setSprinting(false);
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
        List<String> targetBlockIds = gatherBlockTargetIds(step.getItem(), blockId);
        List<Block> targetBlocks = targetBlockIds.stream()
            .map(this::blockFromId)
            .filter(block -> block != Blocks.AIR)
            .toList();
        if (targetBlocks.isEmpty()) {
            return StepResult.failure("无效的基础资源方块：" + blockId);
        }
        Block block = targetBlocks.get(0);
        String requiredTool = SurvivalRecipeBook.requiredToolForBaseResource(step.getItem());
        if (requiredTool == null && SurvivalUtils.requiresPickaxe(block)) {
            requiredTool = "minecraft:wooden_pickaxe";
        }
        if (requiredTool != null && !ensureTool(requiredTool)) {
            return StepResult.failure("缺少工具，无法采集 " + step.getItem() + "，需要 " + requiredTool);
        }
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        if (targetPos == null || !isGatherBlockTargetValid(targetPos, targetBlocks)) {
            Optional<BlockPos> found = SurvivalUtils.findNearestBlock(aiPlayer,
                (pos, state) -> targetBlocks.contains(state.getBlock())
                    && !rejectedTargets.contains(pos)
                    && isGatherBlockTargetCollectible(pos),
                32,
                16);
            if (found.isEmpty()) {
                found = prospectGatherBlockTarget(step, targetBlockIds, requiredTool);
            }
            if (found.isEmpty()) {
                return StepResult.failure("附近和探矿范围内找不到可采集的 " + targetBlockIds);
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

    private List<String> gatherBlockTargetIds(String itemId, String blockId) {
        List<String> ids = new ArrayList<>();
        for (String candidate : SurvivalRecipeBook.equivalentMaterialItems(itemId)) {
            Block candidateBlock = blockFromItemId(candidate);
            if (candidateBlock != Blocks.AIR) {
                ids.add(blockId(candidateBlock));
            }
        }
        if (ids.isEmpty()) {
            ids.add(blockId);
        }
        return ids.stream().distinct().toList();
    }

    private boolean isGatherBlockTargetValid(BlockPos pos, List<Block> targetBlocks) {
        return pos != null
            && !rejectedTargets.contains(pos)
            && targetBlocks.contains(aiPlayer.level().getBlockState(pos).getBlock())
            && isGatherBlockTargetCollectible(pos);
    }

    private boolean isGatherBlockTargetCollectible(BlockPos pos) {
        return canWorkBlockDirectly(pos, 3.5D) || (hasAirNeighbor(pos) && hasPathToBlock(pos));
    }

    private Optional<BlockPos> prospectGatherBlockTarget(ExecutionStep step, List<String> blockIds, String requiredTool) {
        Level level = aiPlayer.level();
        OreProspectTarget target = OreProspectTarget.forBlocks(
            "block_source",
            step.getItem(),
            "基础方块 " + step.getItem(),
            requiredTool,
            "any",
            blockIds,
            level.getMinY(),
            level.getMinY() + level.getHeight() - 1
        );
        Set<BlockPos> skipped = new HashSet<>();
        OreProspectResult lastResult = null;
        for (int attempt = 0; attempt < GENERIC_BLOCK_PROSPECT_MAX_RETRIES; attempt++) {
            Set<BlockPos> rejected = new HashSet<>(rejectedTargets);
            rejected.addAll(skipped);
            lastResult = OreProspector.scan(
                aiPlayer,
                target,
                rejected,
                GENERIC_BLOCK_PROSPECT_SCAN_RADIUS,
                GENERIC_BLOCK_PROSPECT_VERTICAL_RADIUS,
                GENERIC_BLOCK_PROSPECT_SCAN_BLOCK_BUDGET
            );
            if (!lastResult.found()) {
                break;
            }
            BlockPos candidate = lastResult.orePos();
            if (isGatherBlockTargetCollectible(candidate)) {
                AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' block prospect result: step={}, {}",
                    taskId,
                    aiPlayer.getAiPlayerName(),
                    step.describe(),
                    lastResult.toLogText());
                return Optional.of(candidate);
            }
            skipped.add(candidate);
        }
        rejectedTargets.addAll(skipped);
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' block prospect result: step={}, skipped_not_collectible={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            step.describe(),
            skipped.size(),
            lastResult == null ? "未执行扫描" : lastResult.toLogText());
        return Optional.empty();
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
        MiningToolGate.Result toolGate = evaluateMiningToolGate(requiredTool);
        logMiningToolGate(profile, toolGate, "before_ensure");
        if (requiredTool != null && !toolGate.hasRequiredTier()) {
            setMiningState(MiningState.PREPARE_TOOLS, "ensure_tool:" + requiredTool);
        }
        if (requiredTool != null && !ensureTool(requiredTool)) {
            MiningToolGate.Result failedGate = evaluateMiningToolGate(requiredTool);
            logMiningToolGate(profile, failedGate, "missing_tool");
            return miningFailure("missing_tool", "缺少工具，无法挖 " + resourceName + "，需要 " + requiredTool
                + "；工具门禁=" + failedGate.toLogText());
        }
        toolGate = evaluateMiningToolGate(requiredTool);
        logMiningToolGate(profile, toolGate, "after_ensure");
        if (requiredTool != null && !toolGate.hasRequiredTier()) {
            return miningFailure("wrong_tool_tier", "工具等级不足，无法挖 " + resourceName + "，需要 " + requiredTool
                + "；工具门禁=" + toolGate.toLogText());
        }
        if (requiredTool != null && !toolGate.enoughDurability()) {
            setMiningState(MiningState.PREPARE_TOOLS, "replace_low_durability_tool:" + requiredTool);
            if (!ensureReplacementTool(requiredTool)) {
                setMiningState(MiningState.WAITING_FOR_PLAYER, "low_tool_durability:" + requiredTool);
                return miningFailure("low_tool_durability", "当前镐耐久低于 " + MIN_MINING_TOOL_DURABILITY + "，且暂时无法制作替换工具，先停止挖 "
                    + resourceName + "，避免挖到一半工具损坏；工具门禁=" + toolGate.toLogText());
            }
            toolGate = evaluateMiningToolGate(requiredTool);
            logMiningToolGate(profile, toolGate, "after_replace_low_durability");
            if (!toolGate.ready()) {
                setMiningState(MiningState.WAITING_FOR_PLAYER, "replacement_tool_not_ready:" + requiredTool);
                return miningFailure("replacement_tool_not_ready", "已尝试制作替换工具，但当前工具仍无法安全挖 "
                    + resourceName + "；工具门禁=" + toolGate.toLogText());
            }
        }
        prepareMiningSupplies(profile);
        if (!ensureMiningInventorySpace(itemFromId(step.getItem()), "采集" + resourceName)) {
            return miningTerminalFailure("inventory_full", "AI 背包已满，无法继续采集 " + resourceName + "，请先取出材料或在附近提供箱子");
        }
        StepResult occlusionClearance = tickPendingOcclusionClearance();
        if (occlusionClearance != null) {
            return occlusionClearance;
        }
        StepResult supportResult = placePendingMiningSupport();
        if (supportResult != null) {
            return supportResult;
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
            AiPlayerMod.debug("make_item", "AiPlayer '{}' stops {} target after no item progress: target={}, pos={}, ticksWithoutProgress={}",
                aiPlayer.getAiPlayerName(), resourceName, targetPos, aiPlayer.blockPosition(), ticksWithoutItemProgress);
            return stopMiningTask("no_item_progress",
                resourceName + " 目标长时间没有产出，目标 " + targetPos.toShortString()
                    + "；最后交互目标=" + (activeInteractionTarget == null ? "none" : activeInteractionTarget.summary())
                    + "；最后交互失败=" + (lastInteractionFailure == null ? "none" : lastInteractionFailure.summary()));
        }
        List<Block> blocks = miningBlocks(profile);
        if (targetPos == null || !isMiningCandidate(targetPos, aiPlayer.level().getBlockState(targetPos).getBlock(), blocks)) {
            Optional<InteractionTarget> found = findReachableMiningInteractionTarget(blocks, resourceName, profile.requiredTool());
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
            activeInteractionTarget = found.get();
            targetPos = activeInteractionTarget.targetBlock();
            if (miningRun != null) {
                miningRun.recordTargetSelected(ticks, targetPos, aiPlayer.level().getBlockState(targetPos).getBlock(), "visible_ore");
            }
            resetMovement();
            ticksWithoutItemProgress = 0;
            lastProgressCount = currentCount;
        }
        SurvivalUtils.equipBestToolForBlock(aiPlayer, aiPlayer.level().getBlockState(targetPos).getBlock());
        setMiningState(MiningState.TRAVEL_TO_ORE, "move_to_visible_ore");
        InteractionTarget interactionTarget = activeInteractionTarget != null
            && activeInteractionTarget.targetBlock().equals(targetPos)
            ? activeInteractionTarget
            : miningInteractionTarget(targetPos, null, profile.requiredTool(), "legacy_visible_ore");
        StepResult moveResult = moveToInteractionTarget(interactionTarget, step, resourceName);
        if (moveResult != null) {
            return moveResult;
        }
        resetMovement();
        setMiningState(MiningState.MINING, "mine_visible_ore");
        StepResult validationResult = validateMiningInteractionBeforeBreak(interactionTarget, blocks, profile.requiredTool(), resourceName, "visible_ore");
        if (validationResult != null) {
            return validationResult;
        }
        aiPlayer.lookAtWorkTarget(targetPos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        Block targetBlock = aiPlayer.level().getBlockState(targetPos).getBlock();
        if (!ensureMiningDropCapacity(targetBlock, "visible_ore:" + resourceName)) {
            return miningTerminalFailure("inventory_full_for_drop", "AI 背包无法完整容纳本次 " + resourceName
                + " 掉落，请先取出材料或提供箱子");
        }
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, targetBlock)) {
            return StepResult.running("正在挖 " + resourceName);
        }
        BlockPos minedPos = targetPos;
        int beforeTargetCount = miningTargetItemCount();
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(minedPos, "visible_ore");
        if (!breakResult.success()) {
            recordMiningDigResult(minedPos, targetBlock, breakResult);
            recordMiningDropProgress("visible_ore", minedPos, breakResult, beforeTargetCount);
            breakTicks = 0;
            if (queueOcclusionClearance(minedPos, breakResult, "visible_ore")) {
                return StepResult.running(resourceName + " 被视线遮挡，先清理遮挡方块");
            }
            rejectInteractionTarget(interactionTarget, "break_failed:" + breakResult.reason());
            if (registerBreakFailure(minedPos, breakResult.reason())) {
                rejectTarget(minedPos, "visible_ore_break_failed:" + breakResult.reason());
                targetPos = null;
                activeInteractionTarget = null;
            }
            return StepResult.running("重新寻找 " + resourceName);
        }
        recordMiningDigResult(minedPos, targetBlock, breakResult);
        MiningDropProgress dropProgress = recordMiningDropProgress("visible_ore", minedPos, breakResult, beforeTargetCount);
        if (activeResourceState != null) {
            activeResourceState.recordSuccess(minedPos, aiPlayer.blockPosition());
        }
        activeInteractionTarget = findAdjacentMiningInteractionTarget(
            minedPos,
            blocks,
            profile.requiredTool(),
            "vein_follow"
        ).orElse(null);
        targetPos = activeInteractionTarget == null ? null : activeInteractionTarget.targetBlock();
        if (targetPos != null && miningRun != null) {
            miningRun.recordTargetSelected(ticks, targetPos, aiPlayer.level().getBlockState(targetPos).getBlock(), "vein_follow");
        }
        breakTicks = 0;
        if (targetPos == null && profile.prospectable() && !dropProgress.complete()) {
            requestProspectRescan("target_quantity_remaining:" + dropProgress.remainingCount(), resourceName);
            return StepResult.running("已获得部分 " + resourceName + "，还差 "
                + dropProgress.remainingCount() + "，正在重新探矿");
        }
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
        if (stairDescentController != null) {
            return stairDescentController.shouldDescend(y - BRANCH_TARGET_Y_TOLERANCE);
        }
        return y > miningRoutePlan.targetY() + BRANCH_TARGET_Y_TOLERANCE;
    }

    private boolean shouldRequestHigherSearchArea(MiningResource.Profile profile) {
        return profile.prospectable()
            && profile.hasPreferredYRange()
            && aiPlayer.blockPosition().getY() < profile.preferredMinY();
    }

    private Optional<BlockPos> findReachableMiningTarget(List<Block> blocks) {
        return findReachableMiningInteractionTarget(blocks, "矿物", null)
            .map(InteractionTarget::targetBlock);
    }

    private Optional<InteractionTarget> findReachableMiningInteractionTarget(List<Block> blocks, String resourceName, String requiredTool) {
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
        return Optional.ofNullable(scan.selectedInteractionTarget);
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
                    InteractionTarget directTarget = miningInteractionTarget(pos, null, requiredToolForBlocks(blocks), "visible_ore_direct");
                    Optional<InteractionFailureKey> directRejected = interactionFailures.rejectionFor(directTarget, ticks);
                    if (directRejected.isPresent()) {
                        scan.reject("interaction_rejected:" + directRejected.get().reason());
                        continue;
                    }
                    if (canWorkBlockDirectly(pos, 3.5D)) {
                        scan.directlyWorkable++;
                        scan.selectDirect(pos, distance, exposedAir, directTarget);
                        continue;
                    }
                    Optional<StoneWorkTarget> workTarget = resolveMiningWorkTarget(pos, requiredToolForBlocks(blocks), "visible_ore_reachable_stand");
                    if (workTarget.isPresent()
                        && interactionFailures.rejectionFor(workTarget.get().interactionTarget(), ticks).isEmpty()) {
                        scan.pathReachable++;
                        scan.selectPath(pos, distance, exposedAir, workTarget.get().interactionTarget());
                    } else {
                        scan.reject("no_path");
                    }
                }
            }
        }
        return scan;
    }

    private StepResult validateMiningInteractionBeforeBreak(
        InteractionTarget interactionTarget,
        List<Block> targetBlocks,
        String requiredTool,
        String resourceName,
        String mode
    ) {
        if (interactionTarget == null) {
            return StepResult.failure("缺少" + resourceName + "交互目标");
        }
        BlockPos target = interactionTarget.targetBlock();
        if (!aiPlayer.level().hasChunkAt(target)) {
            rejectInteractionTarget(interactionTarget, "target_chunk_unloaded");
            return StepResult.running(resourceName + " 所在区块未加载，正在重新寻找");
        }
        Block block = aiPlayer.level().getBlockState(target).getBlock();
        if (!targetBlocks.contains(block) || !hasAirNeighbor(target)) {
            rejectInteractionTarget(interactionTarget, "target_changed:" + blockId(block));
            return StepResult.running(resourceName + " 目标已变化，正在重新寻找");
        }
        if (interactionTarget.hasStandPos() && !aiPlayer.blockPosition().equals(interactionTarget.standPos())) {
            rejectInteractionTarget(interactionTarget, "stand_not_reached");
            return StepResult.running("尚未到达" + resourceName + "工作站位，正在重新寻找");
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(target));
        double reach = interactionTarget.reachRange();
        if (distanceSq > reach * reach) {
            rejectInteractionTarget(interactionTarget, "out_of_reach:" + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq)));
            return StepResult.running(resourceName + " 目标超出交互距离，正在重新寻找");
        }
        String targetDanger = blockingMiningDanger(target, "visible_" + mode);
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, target, "skip_" + mode);
            breakTicks = 0;
            return stopMiningTask("unsafe_" + mode + ":" + targetDanger,
                resourceName + " 目标附近存在环境风险：" + targetDanger + "，位置 " + target.toShortString()
                    + "；交互目标=" + interactionTarget.summary());
        }
        if (requiredTool != null && !hasToolItem(requiredTool)) {
            rejectInteractionTarget(interactionTarget, "tool_missing:" + requiredTool);
            return StepResult.failure("缺少工具，无法挖 " + resourceName + "，需要 " + requiredTool);
        }
        String preferredTool = SurvivalUtils.preferredToolForBlock(block);
        if (preferredTool != null && !preferredTool.equals(interactionTarget.toolType())) {
            rejectInteractionTarget(interactionTarget, "tool_mismatch:" + preferredTool);
            return StepResult.running(resourceName + " 工具规则变化，正在重新寻找");
        }
        return null;
    }

    private Optional<StoneWorkTarget> resolveMiningWorkTarget(BlockPos orePos, String requiredTool, String reason) {
        if (orePos == null) {
            return Optional.empty();
        }
        if (canWorkBlockDirectly(orePos, 3.5D)) {
            return Optional.of(new StoneWorkTarget(
                orePos.immutable(),
                aiPlayer.blockPosition().immutable(),
                0.0D,
                miningInteractionTarget(orePos, null, requiredTool, reason + "_direct")
            ));
        }
        return reachableWorkStand(orePos).map(stand -> new StoneWorkTarget(
            orePos.immutable(),
            stand,
            verticalPenalty(orePos, stand),
            miningInteractionTarget(orePos, stand, requiredTool, reason)
        ));
    }

    private InteractionTarget miningInteractionTarget(BlockPos orePos, BlockPos standPos, String requiredTool, String reason) {
        return new InteractionTarget(
            orePos.immutable(),
            standPos == null ? null : standPos.immutable(),
            3.5D,
            toolTypeFromRequiredTool(requiredTool),
            InteractionActionType.BREAK_BLOCK,
            reason
        );
    }

    private String requiredToolForBlocks(List<Block> blocks) {
        if (activeMiningProfile != null && activeMiningProfile.requiredTool() != null) {
            return activeMiningProfile.requiredTool();
        }
        for (Block block : blocks) {
            String preferredTool = SurvivalUtils.preferredToolForBlock(block);
            if (preferredTool != null) {
                return preferredTool;
            }
        }
        return null;
    }

    private String toolTypeFromRequiredTool(String requiredTool) {
        if (requiredTool == null || requiredTool.isBlank()) {
            return "pickaxe";
        }
        if (requiredTool.contains("pickaxe")) {
            return "pickaxe";
        }
        if (requiredTool.contains("axe")) {
            return "axe";
        }
        if (requiredTool.contains("shovel")) {
            return "shovel";
        }
        return requiredTool;
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
            StepResult recoveryResult = handleAboveLayerRecoveryBlocked("prospect_timeout", oreName, "探矿已超过普通超时");
            if (recoveryResult != null && !recoveryResult.isRunning()) {
                return recoveryResult;
            }
            if (recoveryResult == null) {
                return miningTerminalFailure("prospect_timeout", "探矿超时：附近和矿道内仍未找到可挖的 " + oreName + " 矿，请移动到矿洞、低处或给 AI 放入对应原矿");
            }
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
        StepResult guidedResult = tickGuidedProspectMining(step, oreName);
        if (guidedResult != null) {
            return guidedResult;
        }
        StepResult progressDecision = maybeSwitchMiningStrategyForNoProgress(oreName);
        if (progressDecision != null) {
            return progressDecision;
        }
        if (shouldDescendToRouteTarget()) {
            StepResult digResult = tickDigDownForStone(step);
            if (digResult.isRunning()) {
                return StepResult.running("正在下探到 " + oreName + " 目标矿层 Y=" + miningRoutePlan.targetY()
                    + "：" + digResult.getMessage() + "；" + stairDescentLogText());
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

    private StepResult tickGuidedProspectMining(ExecutionStep step, String oreName) {
        if (activeMiningProfile == null || !activeMiningProfile.prospectable()) {
            return null;
        }
        updateGuidedMovementProgress();
        String refreshReason = prospectRefreshReason();
        if (refreshReason != null && !pendingProspectSoftRescan) {
            requestSoftProspectRescan(refreshReason, oreName);
        }
        StepResult softRescanResult = applyPendingSoftProspectRescan(oreName);
        if (softRescanResult != null) {
            return softRescanResult;
        }
        if (stageMiningPlan != null && !isCurrentProspectTargetValid()) {
            rejectCurrentProspectAndRescan("prospect_target_invalid", oreName);
            return StepResult.running("探矿目标已不存在或不再是可挖的 " + oreName + "，已重新探矿");
        }
        if (stageMiningPlan == null) {
            if (ticks < nextProspectScanTick) {
                return null;
            }
            nextProspectScanTick = ticks + PROSPECT_SCAN_INTERVAL_TICKS;
            OreProspectTarget prospectTarget = OreProspectTarget.fromProfile(
                activeMiningProfile,
                aiPlayer.level().getMinY(),
                aiPlayer.level().getMinY() + aiPlayer.level().getHeight() - 1
            );
            lastProspectResult = OreProspector.scan(
                aiPlayer,
                prospectTarget,
                prospectRejectedTargets(),
                PROSPECT_SCAN_RADIUS,
                PROSPECT_SCAN_VERTICAL_RADIUS,
                PROSPECT_SCAN_BLOCK_BUDGET
            );
            if (miningRun != null) {
                miningRun.recordProspectResult(lastProspectResult);
            }
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' prospect scan for {}: {}",
                taskId,
                aiPlayer.getAiPlayerName(),
                oreName,
                lastProspectResult.toLogText());
            if (!lastProspectResult.found()) {
                logProspectRescanSelection(oreName, null);
                StepResult recoveryResult = handleAboveLayerRecoveryProspectNotFound(oreName);
                if (recoveryResult != null) {
                    return recoveryResult;
                }
                return stopMiningTask("prospect_not_found",
                    "探矿模块在附近已加载区域没有找到 " + oreName + " 矿");
            }
            OreProspectSelectionPolicy.Rejection policyRejection = OreProspectSelectionPolicy.rejectionFor(
                prospectTarget,
                aiPlayer.blockPosition(),
                lastProspectResult.orePos(),
                lastProspectResult.classification()
            );
            if (policyRejection.rejected()) {
                rejectTarget(lastProspectResult.orePos(), policyRejection.reason());
                requestProspectRescan(policyRejection.reason(), oreName);
                return StepResult.running("探矿候选不适合当前层，已拒绝并重探："
                    + oreName + "，原因 " + policyRejection.reason()
                    + "，verticalDelta=" + policyRejection.verticalDelta());
            }
            ExposureTarget exposureTarget = ExposureTarget.create(aiPlayer.level(), aiPlayer.blockPosition(), lastProspectResult.orePos());
            if (!exposureTarget.valid()) {
                BlockPos rejectedOrePos = lastProspectResult.orePos();
                String reason = "no_exposure:" + exposureTarget.reason();
                cooldownProspectCandidate(rejectedOrePos, reason);
                requestProspectRescan(reason, oreName);
                return StepResult.running("探矿目标没有可用暴露面，已冷却当前候选并重新探矿："
                    + oreName + "，目标 " + rejectedOrePos.toShortString()
                    + "，原因 " + exposureTarget.reason());
            }
            stageMiningPlan = StageMiningPlan.create(prospectTarget, aiPlayer.blockPosition(), lastProspectResult.orePos(), exposureTarget, ticks);
            logProspectRescanSelection(oreName, lastProspectResult);
            clearAboveLayerRecovery();
            guidedTunnelBlocks = 0;
            resetGuidedProspectProgress();
            directMiningRoute = createDirectMiningRoute();
            logDirectMiningRoute("prospect_selected");
            descentDirection = directMiningRoute == null ? directionToward(stageMiningPlan.routeTarget()) : directMiningRoute.nextDirection();
            if (miningRun != null) {
                miningRun.recordStageMiningPlan(stageMiningPlan);
            }
        }
        return executeStageMiningPlan(step, oreName);
    }

    private DirectMiningRoute createDirectMiningRoute() {
        if (stageMiningPlan == null) {
            return null;
        }
        Direction preferred = descentDirection == null ? horizontalDirectionFromYaw() : descentDirection;
        return DirectMiningRoute.create(
            aiPlayer.blockPosition(),
            stageMiningPlan.orePos(),
            stageMiningPlan.routeTarget(),
            preferred
        );
    }

    private void refreshDirectMiningRoute(String reason) {
        directMiningRoute = createDirectMiningRoute();
        logDirectMiningRoute(reason);
    }

    private void logDirectMiningRoute(String reason) {
        if (directMiningRoute == null) {
            return;
        }
        if (!"prospect_selected".equals(reason) && ticks - lastDirectMiningRouteLogTick < 40) {
            return;
        }
        lastDirectMiningRouteLogTick = ticks;
        AiPlayerMod.info("mining", "[taskId={}] direct_route: ai={}, reason={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason == null || reason.isBlank() ? "unknown" : reason,
            directMiningRoute.toLogText());
    }

    private String prospectRefreshReason() {
        if (stageMiningPlan == null) {
            return null;
        }
        String qualityReason = prospectRouteQualityDegradedReason();
        if (qualityReason != null) {
            return qualityReason;
        }
        return ProspectRescanCounter.refreshReason(
            guidedBlocksSinceProspect,
            guidedDistanceSinceProspect,
            guidedActionsSinceProspect,
            PROSPECT_RESCAN_BLOCK_INTERVAL,
            PROSPECT_RESCAN_DISTANCE_INTERVAL,
            PROSPECT_RESCAN_ACTION_INTERVAL
        );
    }

    private String prospectRescanProgressText() {
        return ProspectRescanCounter.statusText(
            guidedBlocksSinceProspect,
            guidedDistanceSinceProspect,
            guidedActionsSinceProspect,
            PROSPECT_RESCAN_BLOCK_INTERVAL,
            PROSPECT_RESCAN_DISTANCE_INTERVAL,
            PROSPECT_RESCAN_ACTION_INTERVAL
        );
    }

    private String prospectRouteQualityDegradedReason() {
        if (stageMiningPlan == null) {
            return null;
        }
        BlockPos routeTarget = stageMiningPlan.routeTarget();
        BlockPos current = aiPlayer.blockPosition();
        int horizontal = Math.abs(routeTarget.getX() - current.getX()) + Math.abs(routeTarget.getZ() - current.getZ());
        int vertical = routeTarget.getY() - current.getY();
        if (vertical > stageMiningPlan.verticalDelta() + 2) {
            return "route_vertical_worse:old=" + stageMiningPlan.verticalDelta() + ",new=" + vertical;
        }
        if (horizontal > stageMiningPlan.horizontalDistance() + 6) {
            return "route_distance_worse:old=" + stageMiningPlan.horizontalDistance() + ",new=" + horizontal;
        }
        return null;
    }

    private void requestSoftProspectRescan(String reason, String oreName) {
        rememberPendingProspectRescan(reason);
        pendingProspectSoftRescan = true;
        nextProspectScanTick = 0;
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' requests soft prospect rescan for {}: reason={}, counters={}, pos={}, oldPlan={}, oldScore={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            oreName,
            reason,
            prospectRescanProgressText(),
            aiPlayer.blockPosition().toShortString(),
            pendingProspectRescanOldPlan,
            pendingProspectRescanOldScore);
    }

    private StepResult applyPendingSoftProspectRescan(String oreName) {
        if (!pendingProspectSoftRescan || stageMiningPlan == null) {
            return null;
        }
        if (ticks < nextProspectScanTick) {
            return null;
        }
        nextProspectScanTick = ticks + PROSPECT_SCAN_INTERVAL_TICKS;
        OreProspectTarget prospectTarget = OreProspectTarget.fromProfile(
            activeMiningProfile,
            aiPlayer.level().getMinY(),
            aiPlayer.level().getMinY() + aiPlayer.level().getHeight() - 1
        );
        OreProspectResult rescanResult = OreProspector.scan(
            aiPlayer,
            prospectTarget,
            prospectRejectedTargets(),
            PROSPECT_SCAN_RADIUS,
            PROSPECT_SCAN_VERTICAL_RADIUS,
            PROSPECT_SCAN_BLOCK_BUDGET
        );
        if (miningRun != null) {
            miningRun.recordProspectResult(rescanResult);
        }
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' soft prospect scan for {}: {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            oreName,
            rescanResult.toLogText());
        if (!rescanResult.found()) {
            logProspectRescanSelection(oreName, null);
            resetGuidedProspectProgress();
            return StepResult.running("本轮重探没有找到新矿点，继续当前挖矿路线：" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
        }
        OreProspectSelectionPolicy.Rejection policyRejection = OreProspectSelectionPolicy.rejectionFor(
            prospectTarget,
            aiPlayer.blockPosition(),
            rescanResult.orePos(),
            rescanResult.classification()
        );
        if (policyRejection.rejected()) {
            cooldownProspectCandidate(rescanResult.orePos(), "soft_rescan_rejected:" + policyRejection.reason());
            logProspectRescanSelection(oreName, rescanResult);
            resetGuidedProspectProgress();
            return StepResult.running("本轮重探候选不适合当前路线，继续当前矿点：" + policyRejection.reason());
        }
        ProspectRescanCounter.Binding binding = ProspectRescanCounter.binding(pendingProspectRescanOldOrePos, rescanResult.orePos());
        if (binding.keepCurrentRoute()) {
            lastProspectResult = rescanResult;
            logProspectRescanSelection(oreName, rescanResult);
            resetGuidedProspectProgress();
            return StepResult.running("重探仍选择当前矿点，保留当前路线继续推进：" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
        }
        ExposureTarget exposureTarget = ExposureTarget.create(aiPlayer.level(), aiPlayer.blockPosition(), rescanResult.orePos());
        if (!exposureTarget.valid()) {
            cooldownProspectCandidate(rescanResult.orePos(), "soft_rescan_no_exposure:" + exposureTarget.reason());
            logProspectRescanSelection(oreName, rescanResult);
            resetGuidedProspectProgress();
            return StepResult.running("本轮重探的新矿点没有可用暴露路线，继续当前矿点：" + exposureTarget.reason());
        }
        lastProspectResult = rescanResult;
        targetPos = null;
        descentMoveTarget = null;
        descentMoveMode = null;
        activeInteractionTarget = null;
        breakTicks = 0;
        clearStairStepPlan();
        resetMovement();
        stageMiningPlan = StageMiningPlan.create(prospectTarget, aiPlayer.blockPosition(), rescanResult.orePos(), exposureTarget, ticks);
        logProspectRescanSelection(oreName, rescanResult);
        clearAboveLayerRecovery();
        guidedTunnelBlocks = 0;
        resetGuidedProspectProgress();
        directMiningRoute = createDirectMiningRoute();
        logDirectMiningRoute("soft_rescan_rebind");
        descentDirection = directMiningRoute == null ? directionToward(stageMiningPlan.routeTarget()) : directMiningRoute.nextDirection();
        if (miningRun != null) {
            miningRun.recordStageMiningPlan(stageMiningPlan);
        }
        return StepResult.running("重探切换到新的矿点并重建直达路线：" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
    }

    private void requestProspectRescan(String reason, String oreName) {
        rememberPendingProspectRescan(reason);
        pendingProspectSoftRescan = false;
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' requests prospect rescan for {}: reason={}, blocksSince={}, distanceSince={}, actionsSince={}, pos={}, oldPlan={}, oldScore={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            oreName,
            reason,
            guidedBlocksSinceProspect,
            guidedDistanceSinceProspect,
            guidedActionsSinceProspect,
            aiPlayer.blockPosition().toShortString(),
            pendingProspectRescanOldPlan,
            pendingProspectRescanOldScore);
        stageMiningPlan = null;
        lastProspectResult = null;
        targetPos = null;
        descentMoveTarget = null;
        descentMoveMode = null;
        nextProspectScanTick = 0;
        guidedTunnelBlocks = 0;
        breakTicks = 0;
        clearStairStepPlan();
        resetMovement();
        resetGuidedProspectProgress();
    }

    private void rejectCurrentProspectAndRescan(String reason, String oreName) {
        if (stageMiningPlan != null) {
            BlockPos orePos = stageMiningPlan.orePos();
            rejectTarget(orePos, reason);
            if (activeInteractionTarget != null && activeInteractionTarget.targetBlock().equals(orePos)) {
                activeInteractionTarget = null;
            }
        }
        requestProspectRescan(reason, oreName);
    }

    private void rememberPendingProspectRescan(String reason) {
        pendingProspectRescanReason = reason == null || reason.isBlank() ? "unknown" : reason;
        pendingProspectRescanOldPlan = stageMiningPlan == null ? "none" : stageMiningPlan.toLogText();
        pendingProspectRescanOldScore = lastProspectResult == null || lastProspectResult.selectedScore() == null
            ? "none"
            : lastProspectResult.selectedScore().toLogText();
        pendingProspectRescanOldOrePos = stageMiningPlan == null ? null : stageMiningPlan.orePos().immutable();
    }

    private void logProspectRescanSelection(String oreName, OreProspectResult result) {
        if (pendingProspectRescanReason == null) {
            return;
        }
        BlockPos newOrePos = result == null ? null : result.orePos();
        boolean switched = pendingProspectRescanOldOrePos != null
            && newOrePos != null
            && !pendingProspectRescanOldOrePos.equals(newOrePos);
        String bindingDecision = ProspectRescanCounter.bindingDecision(pendingProspectRescanOldOrePos, newOrePos);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' prospect rescan selected for {}: reason={}, oldPlan={}, oldScore={}, newCandidate={}, newScore={}, switched={}, binding={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            oreName,
            pendingProspectRescanReason,
            pendingProspectRescanOldPlan,
            pendingProspectRescanOldScore,
            newOrePos == null ? "none" : newOrePos.toShortString(),
            result == null || result.selectedScore() == null ? "none" : result.selectedScore().toLogText(),
            switched,
            bindingDecision);
        clearPendingProspectRescanLog();
    }

    private void clearPendingProspectRescanLog() {
        pendingProspectRescanReason = null;
        pendingProspectRescanOldPlan = null;
        pendingProspectRescanOldScore = null;
        pendingProspectRescanOldOrePos = null;
        pendingProspectSoftRescan = false;
    }

    private void resetGuidedProspectProgress() {
        guidedBlocksSinceProspect = 0;
        guidedDistanceSinceProspect = 0;
        guidedActionsSinceProspect = 0;
        lastGuidedProgressPos = aiPlayer.blockPosition().immutable();
    }

    private void updateGuidedMovementProgress() {
        if (stageMiningPlan == null) {
            return;
        }
        BlockPos current = aiPlayer.blockPosition();
        if (lastGuidedProgressPos == null) {
            lastGuidedProgressPos = current.immutable();
            return;
        }
        int distance = manhattanDistance(lastGuidedProgressPos, current);
        if (distance <= 0) {
            return;
        }
        guidedDistanceSinceProspect += distance;
        lastGuidedProgressPos = current.immutable();
    }

    private void recordGuidedProspectDigProgress() {
        if (stageMiningPlan != null) {
            guidedBlocksSinceProspect++;
            guidedActionsSinceProspect++;
        }
    }

    private StepResult ensureStoneDescentStart() {
        BlockPos current = aiPlayer.blockPosition();
        if (isGoodStoneDescentStart(current)) {
            stoneDescentStartTarget = null;
            return null;
        }
        if (stoneDescentStartFailures >= STONE_DESCENT_START_FAILURE_LIMIT) {
            return null;
        }
        if (stoneDescentStartTarget == null
            || !isGoodStoneDescentStart(stoneDescentStartTarget)
            || rejectedStoneDescentStarts.contains(stoneDescentStartTarget)) {
            stoneDescentStartTarget = findStoneDescentStartTarget().orElse(null);
            resetMovement();
            if (stoneDescentStartTarget == null) {
                stoneDescentStartFailures = STONE_DESCENT_START_FAILURE_LIMIT;
                AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' could not find open stone descent start near {}, continuing local stair descent",
                    taskId,
                    aiPlayer.getAiPlayerName(),
                    current.toShortString());
                return null;
            }
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' selected stone descent start: current={}, start={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                current.toShortString(),
                stoneDescentStartTarget.toShortString());
        }
        if (aiPlayer.blockPosition().equals(stoneDescentStartTarget)) {
            stoneDescentStartTarget = null;
            resetMovement();
            return null;
        }
        setMiningState(MiningState.TRAVEL_TO_ORE, "move_to_stone_descent_start");
        aiPlayer.setSprinting(true);
        aiPlayer.getNavigation().moveTo(
            stoneDescentStartTarget.getX() + 0.5D,
            stoneDescentStartTarget.getY(),
            stoneDescentStartTarget.getZ() + 0.5D,
            SurvivalUtils.TASK_RUN_SPEED
        );
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(stoneDescentStartTarget));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return StepResult.running("正在移动到开阔起挖点");
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejected stone descent start: start={}, current={}, reason=movement_stuck",
                taskId,
                aiPlayer.getAiPlayerName(),
                stoneDescentStartTarget.toShortString(),
                aiPlayer.blockPosition().toShortString());
            rejectedStoneDescentStarts.add(stoneDescentStartTarget);
            stoneDescentStartTarget = null;
            stoneDescentStartFailures++;
            resetMovement();
            return StepResult.running("开阔起挖点不可达，正在换一个起挖点");
        }
        return StepResult.running("正在移动到开阔起挖点");
    }

    private Optional<BlockPos> findStoneDescentStartTarget() {
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(
                center.offset(-STONE_DESCENT_START_SEARCH_RADIUS, -STONE_DESCENT_START_VERTICAL_RADIUS, -STONE_DESCENT_START_SEARCH_RADIUS),
                center.offset(STONE_DESCENT_START_SEARCH_RADIUS, STONE_DESCENT_START_VERTICAL_RADIUS, STONE_DESCENT_START_SEARCH_RADIUS)
            )
            .map(BlockPos::immutable)
            .filter(aiPlayer.level()::hasChunkAt)
            .filter(pos -> !rejectedStoneDescentStarts.contains(pos))
            .filter(this::isGoodStoneDescentStart)
            .filter(pos -> aiPlayer.getNavigation().createPath(pos, 1) != null)
            .min(Comparator.comparingDouble(pos -> pos.distSqr(center)));
    }

    private boolean isGoodStoneDescentStart(BlockPos pos) {
        if (pos == null || !canStandAt(pos)) {
            return false;
        }
        Block support = aiPlayer.level().getBlockState(pos.below()).getBlock();
        if (isTreeBlock(support) || support == Blocks.GRAVEL || support == Blocks.SAND || support == Blocks.RED_SAND) {
            return false;
        }
        int treeBlocks = 0;
        for (BlockPos check : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 2, 1))) {
            Block block = aiPlayer.level().getBlockState(check).getBlock();
            if (isTreeBlock(block)) {
                treeBlocks++;
            }
        }
        if (treeBlocks > 1) {
            return false;
        }
        for (Direction direction : horizontalDirections()) {
            BlockPos nextStand = pos.relative(direction).below();
            if (isUnsafeStairStand(nextStand)) {
                continue;
            }
            BlockPos horizontalTarget = nextStand.above();
            BlockPos verticalTarget = nextStand;
            if (isAirBlock(horizontalTarget) && canStandAt(nextStand)) {
                return true;
            }
            if (!isAirBlock(horizontalTarget) && breakableStairBlock(horizontalTarget) != null) {
                return true;
            }
            if (isAirBlock(horizontalTarget) && !isAirBlock(verticalTarget) && breakableStairBlock(verticalTarget) != null) {
                return true;
            }
        }
        return false;
    }

    private void rejectCurrentStoneDescentStart(String reason) {
        BlockPos current = aiPlayer.blockPosition().immutable();
        rejectedStoneDescentStarts.add(current);
        stoneDescentStartFailures++;
        recordStairDescentFailure(reason);
        stoneDescentStartTarget = null;
        descentMoveTarget = null;
        descentMoveMode = null;
        clearStairStepPlan();
        resetMovement();
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejected stone descent start: start={}, reason={}, attempts={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            current.toShortString(),
            reason,
            stoneDescentStartFailures);
    }

    private void recordGuidedActionProgress(String action) {
        if (stageMiningPlan == null) {
            return;
        }
        guidedActionsSinceProspect++;
        AiPlayerMod.debug("mining", "[taskId={}] AiPlayer '{}' guided prospect action: action={}, blocksSince={}, distanceSince={}, actionsSince={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            action == null || action.isBlank() ? "unknown" : action,
            guidedBlocksSinceProspect,
            guidedDistanceSinceProspect,
            guidedActionsSinceProspect);
    }

    private int manhattanDistance(BlockPos first, BlockPos second) {
        return Math.abs(first.getX() - second.getX())
            + Math.abs(first.getY() - second.getY())
            + Math.abs(first.getZ() - second.getZ());
    }

    private boolean isCurrentProspectTargetValid() {
        if (stageMiningPlan == null || activeMiningProfile == null) {
            return false;
        }
        Block block = aiPlayer.level().getBlockState(stageMiningPlan.orePos()).getBlock();
        return miningBlocks(activeMiningProfile).contains(block) && !rejectedTargets.contains(stageMiningPlan.orePos());
    }

    private StepResult executeStageMiningPlan(ExecutionStep step, String oreName) {
        refreshDirectMiningRoute("execute_stage");
        BlockPos orePos = stageMiningPlan.orePos();
        BlockPos routeTarget = stageMiningPlan.routeTarget();
        int currentY = aiPlayer.blockPosition().getY();
        if (currentY > routeTarget.getY()) {
            stageMiningPlan = stageMiningPlan.withStage(StageMiningPlan.Stage.DESCEND, aiPlayer.blockPosition());
            if (miningRun != null) {
                miningRun.recordStageMiningPlan(stageMiningPlan);
            }
            descentDirection = directMiningRoute == null ? directionToward(routeTarget) : directMiningRoute.nextDirection();
            StepResult digResult = tickDigDownForStone(step);
            if (digResult.isRunning()) {
                return StepResult.running("按探矿坐标阶梯下挖到暴露面高度 " + oreName + "：" + digResult.getMessage()
                    + "；" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
            }
            return digResult;
        }
        if (currentY < routeTarget.getY() - 1) {
            return replanProspectTargetAboveCurrentLayer(
                "prospect_target_above_current_layer",
                oreName,
                orePos,
                routeTarget
            );
        }
        if (hasAirNeighbor(orePos) && canWorkBlockDirectly(orePos, 3.5D)) {
            stageMiningPlan = stageMiningPlan.withStage(StageMiningPlan.Stage.MINE, aiPlayer.blockPosition());
            return mineProspectOreTarget(orePos, oreName);
        }
        if (hasAirNeighbor(orePos) && hasPathToBlock(orePos)) {
            stageMiningPlan = stageMiningPlan.withStage(StageMiningPlan.Stage.APPROACH, aiPlayer.blockPosition());
            if (miningRun != null) {
                miningRun.recordStageMiningPlan(stageMiningPlan);
            }
            StepResult approachResult = moveToProspectWorkStand(orePos, oreName);
            if (approachResult != null) {
                return approachResult;
            }
        }

        stageMiningPlan = stageMiningPlan.withStage(StageMiningPlan.Stage.TUNNEL, aiPlayer.blockPosition());
        if (miningRun != null) {
            miningRun.recordStageMiningPlan(stageMiningPlan);
        }
        return tickGuidedTunnelTowardProspect(oreName);
    }

    private StepResult moveToProspectWorkStand(BlockPos orePos, String oreName) {
        Optional<StoneWorkTarget> workTarget = resolveMiningWorkTarget(
            orePos,
            activeMiningProfile == null ? null : activeMiningProfile.requiredTool(),
            "prospect_target"
        );
        if (workTarget.isEmpty()) {
            return null;
        }
        InteractionTarget interactionTarget = workTarget.get().interactionTarget();
        markMiningMode("prospect_approach");
        StepResult moveResult = moveToInteractionTarget(interactionTarget, activeStep, oreName);
        if (moveResult != null) {
            return StepResult.running("探矿目标已暴露，正在移动到可挖站位 "
                + (interactionTarget.standPos() == null ? "direct" : interactionTarget.standPos().toShortString())
                + "：" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
        }
        resetMovement();
        recordMiningWaypoint("prospect_approach");
        return mineProspectOreTarget(orePos, oreName);
    }

    private StepResult replanProspectTargetAboveCurrentLayer(
        String reason,
        String oreName,
        BlockPos orePos,
        BlockPos routeTarget
    ) {
        prospectAboveLayerReplans++;
        rememberAboveLayerRecovery(reason, orePos, routeTarget);
        if (orePos != null) {
            cooldownProspectCandidate(orePos, reason + ":route_above_current_layer");
        }
        String detail = "目标 " + (orePos == null ? "none" : orePos.toShortString())
            + "，路线目标 " + (routeTarget == null ? "none" : routeTarget.toShortString())
            + "，当前 " + aiPlayer.blockPosition().toShortString()
            + "，重探次数 " + prospectAboveLayerReplans + "/" + PROSPECT_ABOVE_LAYER_REPLAN_LIMIT;
        if (prospectAboveLayerReplans > PROSPECT_ABOVE_LAYER_REPLAN_LIMIT) {
            return stopMiningTask(reason,
                "探矿路线目标多次位于当前层上方，已拒绝多个候选后仍无法找到可执行路线："
                    + oreName + "，" + detail);
        }
        requestProspectRescan(reason, oreName);
        return StepResult.running("探矿路线目标位于当前层上方，已拒绝当前矿点并重新探矿：" + oreName + "，" + detail);
    }

    private StepResult handleAboveLayerRecoveryProspectNotFound(String oreName) {
        if (!prospectAboveLayerRecoveryActive) {
            return null;
        }
        prospectAboveLayerReplans++;
        String detail = aboveLayerRecoveryDetail();
        if (prospectAboveLayerReplans >= PROSPECT_ABOVE_LAYER_REPLAN_LIMIT) {
            return stopMiningTask(
                lastAboveLayerReason == null ? "prospect_target_above_current_layer" : lastAboveLayerReason,
                "探矿路线目标多次位于当前层上方，重探后仍没有找到替代矿点："
                    + oreName + "，" + detail);
        }
        AiPlayerMod.info("mining",
            "[taskId={}] AiPlayer '{}' above-layer recovery waits for next prospect scan: ore={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            oreName,
            detail);
        return StepResult.running("探矿路线目标位于当前层上方，重探暂未找到替代矿点，继续等待下一轮探矿："
            + oreName + "，" + detail);
    }

    private StepResult handleAboveLayerRecoveryBlocked(String reason, String oreName, String prefix) {
        if (!prospectAboveLayerRecoveryActive) {
            return null;
        }
        String detail = aboveLayerRecoveryDetail();
        if (prospectAboveLayerReplans >= PROSPECT_ABOVE_LAYER_REPLAN_LIMIT) {
            return stopMiningTask(reason,
                prefix + "，且上方目标恢复已达到上限，仍没有找到替代矿点："
                    + oreName + "，" + detail);
        }
        return StepResult.running(prefix + "，但上方目标恢复尚未达到上限，继续等待下一轮探矿："
            + oreName + "，" + detail);
    }

    private void rememberAboveLayerRecovery(String reason, BlockPos orePos, BlockPos routeTarget) {
        prospectAboveLayerRecoveryActive = true;
        lastAboveLayerReason = reason;
        lastAboveLayerOrePos = orePos == null ? null : orePos.immutable();
        lastAboveLayerRouteTarget = routeTarget == null ? null : routeTarget.immutable();
    }

    private void clearAboveLayerRecovery() {
        prospectAboveLayerRecoveryActive = false;
        lastAboveLayerReason = null;
        lastAboveLayerOrePos = null;
        lastAboveLayerRouteTarget = null;
    }

    private String aboveLayerRecoveryDetail() {
        return "上次目标 " + (lastAboveLayerOrePos == null ? "none" : lastAboveLayerOrePos.toShortString())
            + "，上次路线目标 " + (lastAboveLayerRouteTarget == null ? "none" : lastAboveLayerRouteTarget.toShortString())
            + "，当前 " + aiPlayer.blockPosition().toShortString()
            + "，重探次数 " + prospectAboveLayerReplans + "/" + PROSPECT_ABOVE_LAYER_REPLAN_LIMIT;
    }

    private StepResult tickGuidedTunnelTowardProspect(String oreName) {
        if (stageMiningPlan == null) {
            return null;
        }
        if (guidedTunnelBlocks >= GUIDED_TUNNEL_MAX_BLOCKS) {
            return stopMiningTask("guided_tunnel_limit",
                "探矿路线达到挖掘上限，无法继续接近 " + oreName + "，目标 " + stageMiningPlan.orePos().toShortString());
        }
        if (descentMoveTarget != null) {
            String moveMode = descentMoveModeOr("prospect_guided_move");
            StepResult moveResult = tickDescentMoveTarget(
                moveMode,
                "正在沿探矿路线前进",
                "已推进到探矿路线前沿",
                "探矿路线站位不可达，任务将结束"
            );
            if (moveResult != null) {
                return moveResult;
            }
        }

        BlockPos orePos = stageMiningPlan.orePos();
        BlockPos routeTarget = stageMiningPlan.routeTarget();
        refreshDirectMiningRoute("guided_tunnel");
        int currentY = aiPlayer.blockPosition().getY();
        if (currentY > routeTarget.getY()) {
            return StepResult.running("当前仍高于探矿目标高度，继续阶梯下挖 " + oreName);
        }
        if (currentY < routeTarget.getY() - 1) {
            return replanProspectTargetAboveCurrentLayer(
                "tunnel_target_above_current_layer",
                oreName,
                orePos,
                routeTarget
            );
        }
        Direction direction = directMiningRoute == null ? directionToward(routeTarget) : directMiningRoute.nextDirection();
        BlockPos nextStand = directMiningRoute == null ? aiPlayer.blockPosition().relative(direction) : directMiningRoute.nextStand();
        if (nextStand == null) {
            return replanProspectTargetAboveCurrentLayer(
                "direct_route_target_above_current_layer",
                oreName,
                orePos,
                routeTarget
            );
        }
        if (canStandAt(nextStand) && !rejectedTargets.contains(nextStand)) {
            setDescentMoveTarget(nextStand, "prospect_guided_step");
            return StepResult.running("正在靠近探矿目标：" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
        }

        TunnelTarget tunnelTarget = findGuidedTunnelDigTarget(nextStand, routeTarget, orePos);
        if (tunnelTarget.needsSupport()) {
            return StepResult.running("探矿路线前方缺少支撑，正在补支撑后继续接近目标；"
                + tunnelTarget.step().toLogText());
        }
        if (tunnelTarget.blocked()) {
            return stopMiningTask("guided_tunnel_blocked:" + tunnelTarget.reason(),
                "探矿路线通道阶段失败：" + tunnelTarget.step().displayName()
                    + "，原因 " + tunnelTarget.reason()
                    + "，目标 " + orePos.toShortString()
                    + "；" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
        }
        BlockPos digTarget = tunnelTarget.target();
        if (digTarget == null) {
            return stopMiningTask("guided_tunnel_no_target",
                "探矿路线通道阶段没有返回可清理方块，阶段 " + tunnelTarget.step().displayName()
                    + "，原因 " + tunnelTarget.reason()
                    + "，目标 " + orePos.toShortString());
        }
        if (tunnelTarget.mineOre() || digTarget.equals(orePos)) {
            return mineProspectOreTarget(orePos, oreName);
        }

        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        String targetDanger = blockingMiningDanger(digTarget, "guided_prospect_tunnel");
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, digTarget, "guided_prospect_tunnel");
            breakTicks = 0;
            return stopMiningTask("unsafe_guided_tunnel:" + targetDanger,
                "探矿路线存在环境风险：" + targetDanger + "，挖掘点 " + digTarget.toShortString());
        }

        recordMiningDigAttempt(digTarget, block);
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            return StepResult.running("正在按探矿指引挖向 " + oreName + "：" + stageMiningPlan.statusText(aiPlayer.blockPosition()));
        }
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(digTarget, "prospect_guided_tunnel");
        recordMiningDigResult(digTarget, block, breakResult);
        breakTicks = 0;
        if (!breakResult.success()) {
            if (queueOcclusionClearance(digTarget, breakResult, "prospect_guided_tunnel")) {
                return StepResult.running("探矿路线被视线遮挡，先清理遮挡方块");
            }
            if (registerBreakFailure(digTarget, breakResult.reason())) {
                rejectTarget(digTarget, "guided_break_failed:" + breakResult.reason());
                requestProspectRescan("guided_break_failed:" + breakResult.reason(), oreName);
                return StepResult.running("探矿路线方块连续破坏失败，正在重新探矿");
            }
            return StepResult.running("探矿路线方块破坏失败，正在重试或重新探矿");
        }
        guidedTunnelBlocks++;
        recordGuidedProspectDigProgress();
        downwardDigBlocks++;
        recordMiningWaypoint("prospect_guided_tunnel");
        recordCaveEntranceIfFound(digTarget);
        if (tunnelTarget.exposureClearance()) {
            stageMiningPlan = stageMiningPlan.withStage(StageMiningPlan.Stage.EXPOSE, aiPlayer.blockPosition());
            if (miningRun != null) {
                miningRun.recordStageMiningPlan(stageMiningPlan);
            }
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' exposed prospect ore neighbor: ore={}, cleared={}, route={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                orePos.toShortString(),
                digTarget.toShortString(),
                directMiningRoute == null ? "none" : directMiningRoute.toLogText());
            return StepResult.running("已清理矿点相邻遮挡方块 "
                + digTarget.toShortString() + "，继续挖取 " + oreName);
        }
        if (digTarget.equals(orePos)) {
            if (activeResourceState != null) {
                activeResourceState.recordSuccess(orePos, aiPlayer.blockPosition());
            }
            requestProspectRescan("ore_block_opened", oreName);
            return StepResult.running("已挖到探矿目标 " + oreName);
        }
        return StepResult.running("已推进探矿路线，继续接近 " + oreName);
    }

    private StepResult mineProspectOreTarget(BlockPos orePos, String oreName) {
        if (activeMiningProfile == null) {
            return null;
        }
        List<Block> targetBlocks = miningBlocks(activeMiningProfile);
        InteractionTarget interactionTarget = activeInteractionTarget != null
            && activeInteractionTarget.targetBlock().equals(orePos)
            ? activeInteractionTarget
            : resolveMiningWorkTarget(orePos, activeMiningProfile.requiredTool(), "prospect_target")
                .map(StoneWorkTarget::interactionTarget)
                .orElseGet(() -> miningInteractionTarget(orePos, null, activeMiningProfile.requiredTool(), "legacy_prospect_target"));
        activeInteractionTarget = interactionTarget;
        StepResult moveResult = moveToInteractionTarget(interactionTarget, activeStep, oreName);
        if (moveResult != null) {
            return moveResult;
        }
        StepResult validationResult = validateMiningInteractionBeforeBreak(
            interactionTarget,
            targetBlocks,
            activeMiningProfile.requiredTool(),
            oreName,
            "prospect_target"
        );
        if (validationResult != null) {
            return validationResult;
        }
        Block block = aiPlayer.level().getBlockState(orePos).getBlock();
        if (!ensureMiningDropCapacity(block, "prospect_target:" + oreName)) {
            return miningFailure("inventory_full_for_drop", "AI 背包无法完整容纳本次 " + oreName
                + " 掉落，请先取出材料或提供箱子");
        }
        recordMiningDigAttempt(orePos, block);
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        aiPlayer.lookAtWorkTarget(orePos);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            return StepResult.running("正在挖探矿发现的 " + oreName);
        }
        int beforeTargetCount = miningTargetItemCount();
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(orePos, "prospect_target");
        recordMiningDigResult(orePos, block, breakResult);
        breakTicks = 0;
        if (!breakResult.success()) {
            recordMiningDropProgress("prospect_target", orePos, breakResult, beforeTargetCount);
            if (queueOcclusionClearance(orePos, breakResult, "prospect_target")) {
                return StepResult.running("探矿目标被视线遮挡，先清理遮挡方块");
            }
            rejectInteractionTarget(interactionTarget, "break_failed:" + breakResult.reason());
            if (registerBreakFailure(orePos, breakResult.reason())) {
                cooldownProspectCandidate(orePos, "prospect_target_break_failed:" + breakResult.reason());
                requestProspectRescan("prospect_target_break_failed:" + breakResult.reason(), oreName);
                return StepResult.running("探矿目标连续破坏失败，正在重新探矿");
            }
            return StepResult.running("探矿目标破坏失败，正在重试");
        }
        MiningDropProgress dropProgress = recordMiningDropProgress("prospect_target", orePos, breakResult, beforeTargetCount);
        recordGuidedProspectDigProgress();
        if (activeResourceState != null) {
            activeResourceState.recordSuccess(orePos, aiPlayer.blockPosition());
        }
        activeInteractionTarget = findAdjacentMiningInteractionTarget(
            orePos,
            targetBlocks,
            activeMiningProfile.requiredTool(),
            "prospect_vein_follow"
        ).orElse(null);
        targetPos = activeInteractionTarget == null ? null : activeInteractionTarget.targetBlock();
        if (targetPos != null && miningRun != null) {
            miningRun.recordTargetSelected(ticks, targetPos, aiPlayer.level().getBlockState(targetPos).getBlock(), "prospect_vein_follow");
        }
        stageMiningPlan = null;
        guidedTunnelBlocks = 0;
        if (targetPos == null && !dropProgress.complete()) {
            requestProspectRescan("target_quantity_remaining:" + dropProgress.remainingCount(), oreName);
            return StepResult.running("已获得部分 " + oreName + "，还差 "
                + dropProgress.remainingCount() + "，正在继续探矿");
        }
        return StepResult.running("已挖下一块探矿发现的 " + oreName);
    }

    private TunnelTarget findGuidedTunnelDigTarget(BlockPos nextStand, BlockPos routeTarget, BlockPos orePos) {
        if (orePos != null && isOnProspectMiningLayer(orePos) && hasAirNeighbor(orePos) && canWorkBlockDirectly(orePos, 3.5D)) {
            return TunnelTarget.mineOre(orePos);
        }
        BlockPos exposureClearTarget = findProspectExposureClearTarget(orePos);
        if (exposureClearTarget != null) {
            return TunnelTarget.exposure(exposureClearTarget);
        }
        MiningTunnelStepper.Plan tunnelStep = miningTunnelStep(aiPlayer.blockPosition(), nextStand);
        if (queueSupportFromTunnelStep(tunnelStep, aiPlayer.blockPosition(), nextStand, "guided_tunnel")) {
            return TunnelTarget.support(tunnelStep);
        }
        if (tunnelStep.blocked()) {
            return TunnelTarget.blocked(tunnelStep);
        }
        if (tunnelStep.needsClearance()) {
            return TunnelTarget.clearance(tunnelStep);
        }
        if (orePos != null && isOnProspectMiningLayer(orePos) && hasAirNeighbor(orePos) && aiPlayer.blockPosition().closerThan(orePos, 5.0D)) {
            return TunnelTarget.mineOre(orePos);
        }
        return TunnelTarget.blocked(new MiningTunnelStepper.Plan(
            MiningTunnelStepper.Action.BLOCKED,
            nextStand,
            "no_clearance_or_workable_ore",
            null
        ));
    }

    private BlockPos findProspectExposureClearTarget(BlockPos orePos) {
        if (orePos == null || hasAirNeighbor(orePos) || !isOnProspectMiningLayer(orePos)) {
            return null;
        }
        if (!aiPlayer.blockPosition().closerThan(orePos, 5.0D)) {
            return null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        ExposureTarget exposureTarget = stageMiningPlan == null ? null : stageMiningPlan.exposureTarget();
        if (exposureTarget != null && exposureTarget.exposurePos() != null) {
            candidates.add(exposureTarget.exposurePos());
        }
        for (Direction direction : Direction.values()) {
            candidates.add(orePos.relative(direction));
        }
        return candidates.stream()
            .filter(aiPlayer.level()::hasChunkAt)
            .map(BlockPos::immutable)
            .distinct()
            .filter(pos -> !pos.equals(orePos))
            .filter(pos -> !aiPlayer.level().getBlockState(pos).isAir())
            .filter(pos -> breakableMiningPassageBlock(pos) != null)
            .filter(pos -> canWorkBlockDirectly(pos, 3.5D))
            .min(Comparator
                .comparingInt((BlockPos pos) -> exposureTarget != null && pos.equals(exposureTarget.exposurePos()) ? 0 : 1)
                .thenComparingInt(pos -> pos.getY() == orePos.getY() ? 0 : 1)
                .thenComparingDouble(pos -> pos.distSqr(aiPlayer.blockPosition())))
            .orElse(null);
    }

    private boolean isOnProspectMiningLayer(BlockPos orePos) {
        int currentY = aiPlayer.blockPosition().getY();
        return orePos != null && currentY <= orePos.getY() && currentY >= orePos.getY() - 1;
    }

    private Direction directionToward(BlockPos target) {
        if (target == null) {
            return descentDirection == null ? horizontalDirectionFromYaw() : descentDirection;
        }
        BlockPos current = aiPlayer.blockPosition();
        int dx = target.getX() - current.getX();
        int dz = target.getZ() - current.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return descentDirection == null ? horizontalDirectionFromYaw() : descentDirection;
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
                : branchMiningPattern != null ? branchMiningPattern.nextDirection()
                : mainTunnelController == null ? miningRoutePlan.mainDirection()
                : mainTunnelController.direction());
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' starts branch tunnel for {}: direction={}, pos={}, y={}, route={}, profile={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                oreName,
                branchDirection.getName(),
                aiPlayer.blockPosition().toShortString(),
                aiPlayer.blockPosition().getY(),
                miningRoutePlan == null ? "none" : miningRoutePlan.toLogText(),
                activeMiningProfile == null ? "none" : activeMiningProfile.preferredYText()
                    + ", pattern=" + (branchMiningPattern == null ? "none" : branchMiningPattern.toLogText()));
        }
        boolean sideBranchActive = isSideBranchTunnelActive();
        int segmentLength = sideBranchActive && branchMiningPattern != null
            ? branchMiningPattern.branchLength()
            : miningRoutePlan == null ? BRANCH_TUNNEL_SEGMENT_BLOCKS : miningRoutePlan.branchSegmentLength();
        if (sideBranchActive && branchTunnelBlocks >= segmentLength) {
            if (branchMiningPattern != null) {
                branchMiningPattern.recordBranchComplete();
                branchDirection = null;
                branchTunnelBlocks = 0;
                return StepResult.running("鱼骨支矿道段落完成，正在回到主方向继续寻找 " + oreName);
            }
        }
        if (!sideBranchActive && mainTunnelController != null && mainTunnelController.shouldRescan()) {
            requestProspectRescan("main_tunnel_segment_complete", oreName);
            mainTunnelController.resetRescanBudget("segment_complete");
            branchDirection = null;
            branchTunnelBlocks = 0;
            return StepResult.running("主矿道段落完成，正在重新探矿修正路线：" + mainTunnelController.toLogText());
        }
        if (branchMiningPattern == null && branchTunnelBlocks >= segmentLength) {
            if (rotateBranchDirection("segment_complete")) {
                return StepResult.running("当前矿道段落完成，正在换方向继续寻找 " + oreName);
            }
            return handleBranchExhausted(oreName, "segment_limit");
        }
        if (descentMoveTarget != null) {
            String moveMode = descentMoveModeOr("branch_tunnel");
            StepResult moveResult = tickDescentMoveTarget(
                moveMode,
                "正在沿分支矿道前进",
                "已推进到分支矿道前沿",
                "分支矿道站位不可达，任务将结束"
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
        TunnelTarget tunnelTarget = findBranchDigTarget(nextStand);
        if (tunnelTarget.needsSupport()) {
            return StepResult.running("分支矿道前方缺少支撑，正在补支撑后继续推进；"
                + tunnelTarget.step().toLogText());
        }
        if (tunnelTarget.blocked()) {
            return stopMiningTask("branch_tunnel_blocked:" + tunnelTarget.reason(),
                "分支矿道前方通道阶段失败：" + tunnelTarget.step().displayName()
                    + "，原因 " + tunnelTarget.reason()
                    + "，当前位置 " + aiPlayer.blockPosition().toShortString());
        }
        BlockPos digTarget = tunnelTarget.target();
        if (digTarget == null) {
            return stopMiningTask("branch_tunnel_no_clearance",
                "分支矿道前方没有需要清理的方块，也没有可移动站位，无法继续寻找 " + oreName
                    + "，当前位置 " + aiPlayer.blockPosition().toShortString());
        }
        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        String targetDanger = blockingMiningDanger(digTarget, "branch_tunnel");
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, digTarget, "branch_tunnel");
            return stopMiningTask("unsafe_branch_tunnel:" + targetDanger,
                "分支矿道前方存在环境风险：" + targetDanger + "，挖掘点 " + digTarget.toShortString());
        }
        recordMiningDigAttempt(digTarget, block);
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            String phaseText = tunnelTarget.step().displayName();
            return StepResult.running("正在开分支矿道寻找 " + oreName + "：" + phaseText);
        }
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(digTarget, "branch_tunnel");
        if (!breakResult.success()) {
            breakTicks = 0;
            recordMiningDigResult(digTarget, block, breakResult);
            if (queueOcclusionClearance(digTarget, breakResult, "branch_tunnel")) {
                return StepResult.running("分支矿道被视线遮挡，先清理遮挡方块");
            }
            if (registerBreakFailure(digTarget, breakResult.reason())) {
                rejectTarget(digTarget, "branch_break_failed:" + breakResult.reason());
                if (rotateBranchDirection("branch_break_failed:" + breakResult.reason())) {
                    return StepResult.running("分支矿道方块连续破坏失败，正在换方向继续寻找 " + oreName);
                }
                return handleBranchExhausted(oreName, "branch_break_failed:" + breakResult.reason());
            }
            return StepResult.running("分支矿道方块破坏失败，正在重试或换方向");
        }
        breakTicks = 0;
        recordMiningDigResult(digTarget, block, breakResult);
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
        int currentY = aiPlayer.blockPosition().getY();
        int nextTargetY = MiningHeightPolicy.nextLayerTarget(
            currentY,
            activeMiningProfile,
            branchLayerShifts + 1,
            BRANCH_LAYER_SHIFT_BLOCKS
        );
        if (nextTargetY >= currentY) {
            return false;
        }
        branchLayerShifts++;
        miningRoutePlan = miningRoutePlan.withTargetY(nextTargetY, "layer_shift:" + reason + "#" + branchLayerShifts);
        if (stairDescentController != null) {
            stairDescentController.retarget(nextTargetY);
        }
        if (miningRun != null) {
            miningRun.recordRoutePlan(miningRoutePlan);
        }
        branchDirection = null;
        branchTunnelBlocks = 0;
        branchTunnelTurns = 0;
        descentMoveTarget = null;
        descentMoveMode = null;
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
            if (miningRun != null) {
                miningRun.recordProgressDecision(ticks, "stop_no_progress", "no_target_item_progress:" + oreName);
            }
            ticksWithoutItemProgress = 0;
            return stopMiningTask("branch_no_progress", "当前分支长时间没有产出 " + oreName);
        }
        if (miningRun != null) {
            miningRun.recordProgressDecision(ticks, "stop_no_progress", "no_target_item_progress:" + oreName);
        }
        ticksWithoutItemProgress = 0;
        StepResult recoveryResult = handleAboveLayerRecoveryBlocked("prospect_no_progress", oreName, "当前探矿方向长时间没有产出");
        if (recoveryResult != null) {
            return recoveryResult;
        }
        return stopMiningTask("prospect_no_progress", "当前探矿方向长时间没有产出 " + oreName);
    }

    private TunnelTarget findBranchDigTarget(BlockPos nextStand) {
        if (isUnsafeTunnelSpace(nextStand)) {
            return TunnelTarget.blocked(new MiningTunnelStepper.Plan(
                MiningTunnelStepper.Action.BLOCKED,
                nextStand,
                "unsafe_tunnel_space",
                null
            ));
        }
        MiningTunnelStepper.Plan tunnelStep = miningTunnelStep(aiPlayer.blockPosition(), nextStand);
        if (queueSupportFromTunnelStep(tunnelStep, aiPlayer.blockPosition(), nextStand, "branch_tunnel")) {
            return TunnelTarget.support(tunnelStep);
        }
        if (tunnelStep.blocked()) {
            return TunnelTarget.blocked(tunnelStep);
        }
        if (!tunnelStep.needsClearance()) {
            return TunnelTarget.blocked(new MiningTunnelStepper.Plan(
                MiningTunnelStepper.Action.BLOCKED,
                nextStand,
                "no_clearance_needed",
                tunnelStep.simulation()
            ));
        }
        return TunnelTarget.clearance(tunnelStep);
    }

    private BlockPos breakableTunnelBlock(BlockPos pos) {
        return breakableMiningPassageBlock(pos);
    }

    private Optional<InteractionTarget> findAdjacentMiningInteractionTarget(
        BlockPos origin,
        List<Block> blocks,
        String requiredTool,
        String reason
    ) {
        if (origin == null || blocks == null || blocks.isEmpty()) {
            return Optional.empty();
        }
        for (Direction direction : Direction.values()) {
            BlockPos candidate = origin.relative(direction);
            Block block = aiPlayer.level().getBlockState(candidate).getBlock();
            if (rejectedTargets.contains(candidate)
                || !blocks.contains(block)
                || !hasAirNeighbor(candidate)) {
                continue;
            }
            Optional<StoneWorkTarget> workTarget = resolveMiningWorkTarget(candidate, requiredTool, reason);
            if (workTarget.isPresent()
                && interactionFailures.rejectionFor(workTarget.get().interactionTarget(), ticks).isEmpty()) {
                return Optional.of(workTarget.get().interactionTarget());
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
        if (mainTunnelController != null) {
            mainTunnelController.turn(reason);
            branchDirection = mainTunnelController.direction();
        } else {
            branchDirection = branchDirection == null ? horizontalDirectionFromYaw().getClockWise() : branchDirection.getClockWise();
        }
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
        StepResult stationResult = ensureCraftStationInteraction(step);
        if (stationResult != null) {
            return stationResult;
        }
        if (isSmeltingStep(step) && craftTicks == 0) {
            logSmeltingPreflight(step);
        }
        craftTicks++;
        int delay = craftDelay(step);
        if (craftTicks < delay) {
            return StepResult.running(craftProgressMessage(step));
        }
        boolean crafted = craftItem(step);
        if (crafted) {
            return StepResult.success("已合成 " + step.getItem());
        }
        if (isSmeltingStep(step)) {
            return StepResult.failure("材料不足，无法烧炼 " + step.getItem() + "；" + smeltingPreflightText(step));
        }
        return StepResult.failure("材料不足，无法合成 " + step.getItem());
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

    private boolean isSmeltingStep(ExecutionStep step) {
        return step != null && isSmeltingStation(step.getStation());
    }

    private boolean isSmeltingStation(String station) {
        return switch (station == null ? "" : station) {
            case "furnace", "blast_furnace", "smoker", "campfire" -> true;
            default -> false;
        };
    }

    private void logSmeltingPreflight(ExecutionStep step) {
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' smelting preflight: item={}, station={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            step.getItem(),
            step.getStation(),
            smeltingPreflightText(step));
    }

    private String smeltingPreflightText(ExecutionStep step) {
        Map<String, Integer> inventory = new TreeMap<>(aiPlayer.getInventorySnapshot());
        String stationItem = stationItemId(step.getStation());
        return "stationItem=" + stationItem
            + ", hasStationItem=" + (stationItem == null || "minecraft:air".equals(stationItem) || goalChecker.hasItem(aiPlayer, stationItem, 1))
            + ", fuelCount=" + SmeltingFuelPolicy.countFuel(inventory)
            + ", fuels=" + SmeltingFuelPolicy.fuelSummary(inventory)
            + ", backpack=" + inventory;
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
        String expectedStation = "craft_station".equals(step.getStep()) ? step.getStation() : null;
        return craftItemToCount(item, targetCount, new HashSet<>(), expectedStation);
    }

    private boolean craftItemToCount(String item, int targetCount, Set<String> stack) {
        return craftItemToCount(item, targetCount, stack, null);
    }

    private boolean craftItemToCount(String item, int targetCount, Set<String> stack, String expectedStation) {
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
                if (expectedStation != null && !expectedStation.equals(definition.station())) {
                    continue;
                }
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
        if (!hasCraftRequirements(definition)) {
            return false;
        }
        Item output = itemFromId(outputItem);
        if (output == Items.AIR) {
            return false;
        }
        if (definition.stationItem() != null && !ensureStationItem(definition.stationItem(), stack)) {
            return false;
        }
        if (!consumeCraftRequirements(definition)) {
            return false;
        }
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

    private boolean hasCraftRequirements(SurvivalRecipeBook.Definition definition) {
        if (!isSmeltingStation(definition.station())) {
            return hasRequirements(definition.requires());
        }
        return canAllocateSmeltingRequirements(definition.requires());
    }

    private boolean canAllocateSmeltingRequirements(java.util.List<MaterialRequirement> requirements) {
        Map<String, Integer> nonFuelInputs = new HashMap<>();
        int fuelNeeded = 0;
        for (MaterialRequirement requirement : requirements) {
            if (SmeltingFuelPolicy.isFuelRequirement(requirement.getItem())) {
                fuelNeeded += requirement.getCount();
                continue;
            }
            if (isAnyPlankRequirement(requirement.getItem())) {
                return false;
            }
            nonFuelInputs.merge(requirement.getItem(), requirement.getCount(), Integer::sum);
        }
        return SmeltingFuelPolicy.canAllocateFuelAfterInputs(aiPlayer.getInventorySnapshot(), nonFuelInputs, fuelNeeded);
    }

    private void consumeRequirements(java.util.List<MaterialRequirement> requirements) {
        for (MaterialRequirement requirement : requirements) {
            consumeRequirement(requirement);
        }
    }

    private boolean consumeCraftRequirements(SurvivalRecipeBook.Definition definition) {
        if (!isSmeltingStation(definition.station())) {
            consumeRequirements(definition.requires());
            return true;
        }
        return consumeSmeltingRequirements(definition.requires());
    }

    private boolean consumeSmeltingRequirements(java.util.List<MaterialRequirement> requirements) {
        int fuelNeeded = 0;
        for (MaterialRequirement requirement : requirements) {
            if (SmeltingFuelPolicy.isFuelRequirement(requirement.getItem())) {
                fuelNeeded += requirement.getCount();
                continue;
            }
            if (!consumeRequirementIfAvailable(requirement)) {
                return false;
            }
        }
        return consumeFuel(fuelNeeded);
    }

    private boolean consumeRequirementIfAvailable(MaterialRequirement requirement) {
        if (!hasRequirement(requirement)) {
            return false;
        }
        consumeRequirement(requirement);
        return true;
    }

    private boolean consumeFuel(int count) {
        int remaining = Math.max(0, count);
        for (String fuelId : SmeltingFuelPolicy.fuelItems()) {
            Item fuel = itemFromId(fuelId);
            while (remaining > 0 && fuel != Items.AIR && aiPlayer.consumeItem(fuel, 1)) {
                remaining--;
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return remaining <= 0;
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
        if (activeInteractionTarget == null
            || activeInteractionTarget.actionType() != InteractionActionType.OPEN_CONTAINER
            || !containerHasItem(activeInteractionTarget.targetBlock(), item)) {
            Optional<InteractionTarget> found = findNearestChestInteractionWithItem(item);
            if (found.isEmpty()) {
                return StepResult.failure("附近可访问箱子里没有 " + step.getItem());
            }
            activeInteractionTarget = found.get();
            targetPos = activeInteractionTarget.targetBlock();
            resetMovement();
        }
        StepResult moveResult = moveToInteractionTarget(activeInteractionTarget, step, "箱子");
        if (moveResult != null) {
            return moveResult;
        }
        StepResult validationResult = validateContainerOpenBeforeTransfer(activeInteractionTarget);
        if (validationResult != null) {
            return validationResult;
        }
        aiPlayer.lookAtWorkTarget(activeInteractionTarget.targetBlock());
        int remaining = startCount + step.getCount() - goalChecker.countItem(aiPlayer, step.getItem());
        int moved = transferFromChest(activeInteractionTarget.targetBlock(), item, remaining);
        if (moved <= 0) {
            rejectInteractionTarget(activeInteractionTarget, "withdraw_failed");
            return StepResult.running("无法从当前箱子取出 " + step.getItem() + "，正在换一个箱子");
        }
        return StepResult.running("已从箱子取出部分材料");
    }

    private StepResult tickDeposit(ExecutionStep step) {
        Item item = itemFromId(step.getItem());
        if (item == Items.AIR) {
            return StepResult.failure("箱子存放目标不是有效物品：" + step.getItem());
        }
        int targetRemaining = Math.max(0, startCount - step.getCount());
        int current = goalChecker.countItem(aiPlayer, step.getItem());
        if (current <= targetRemaining) {
            return StepResult.success("已向箱子放入 " + step.getItem());
        }
        if (current <= 0) {
            return StepResult.failure("AI 背包里没有可放入箱子的 " + step.getItem());
        }
        if (activeInteractionTarget == null
            || activeInteractionTarget.actionType() != InteractionActionType.OPEN_CONTAINER
            || !containerCanAccept(activeInteractionTarget.targetBlock(), item)) {
            Optional<InteractionTarget> found = findNearestChestInteractionForDeposit(item);
            if (found.isEmpty()) {
                return StepResult.failure("附近没有可存放 " + step.getItem() + " 的可访问箱子");
            }
            activeInteractionTarget = found.get();
            targetPos = activeInteractionTarget.targetBlock();
            resetMovement();
        }
        StepResult moveResult = moveToInteractionTarget(activeInteractionTarget, step, "箱子");
        if (moveResult != null) {
            return moveResult;
        }
        StepResult validationResult = validateContainerOpenBeforeTransfer(activeInteractionTarget);
        if (validationResult != null) {
            return validationResult;
        }
        aiPlayer.lookAtWorkTarget(activeInteractionTarget.targetBlock());
        int toMove = Math.max(0, current - targetRemaining);
        int moved = transferToChest(activeInteractionTarget.targetBlock(), item, toMove);
        if (moved <= 0) {
            rejectInteractionTarget(activeInteractionTarget, "deposit_failed");
            return StepResult.running("无法向当前箱子放入 " + step.getItem() + "，正在换一个箱子");
        }
        return StepResult.running("已向箱子放入部分材料");
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
        aiPlayer.setSprinting(true);
        aiPlayer.getNavigation().moveTo(owner, SurvivalUtils.TASK_RUN_SPEED);
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
            if (isMiningRunStep(activeStep)) {
                return stopMiningTask("movement_stuck",
                    resourceName + " 位置不可达，目标 " + targetPos.toShortString() + "，当前位置 " + aiPlayer.blockPosition().toShortString());
            }
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
        boolean adjacentMiningMove = isAdjacentMiningMoveTarget(aiPlayer.blockPosition(), moveTarget);
        if (adjacentMiningMove) {
            MiningTunnelStepper.Plan tunnelStep = miningTunnelStep(aiPlayer.blockPosition(), moveTarget);
            if (queueSupportFromTunnelStep(tunnelStep, aiPlayer.blockPosition(), moveTarget, mode)) {
                return StepResult.running("相邻矿道站位缺少支撑，正在补支撑后继续前进");
            }
            if (tunnelStep.blocked()) {
                AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejects blocked mining passage before move: mode={}, current={}, target={}, reason={}",
                    taskId,
                    aiPlayer.getAiPlayerName(),
                    mode,
                    aiPlayer.blockPosition().toShortString(),
                    moveTarget.toShortString(),
                    tunnelStep.reason());
                if (isMiningRunStep(activeStep)) {
                    rejectSafeMiningPointIfReturning(moveTarget, mode, "passage_blocked:" + tunnelStep.reason());
                    descentMoveTarget = null;
                    descentMoveMode = null;
                    resetMovement();
                    return recoverableMiningFailure(
                        MiningFailureType.STAND_INVALID,
                        "passage_blocked:" + mode,
                        "挖矿通道无法形成两格高空间，目标站位 " + moveTarget.toShortString() + "，阶段 "
                            + tunnelStep.displayName() + "，原因 " + tunnelStep.reason()
                    );
                }
                rejectTarget(moveTarget, tunnelStep.reason());
                descentMoveTarget = null;
                descentMoveMode = null;
                resetMovement();
                return StepResult.running(fallbackMessage);
            }
            if (tunnelStep.needsClearance()) {
                return tickMiningRouteClearance(tunnelStep.target(), moveTarget, mode, tunnelStep.reason());
            }
        }
        if (!canStandAt(moveTarget)) {
            if (isActiveStairStand(moveTarget)) {
                rejectCurrentStairStep("stand_no_longer_valid");
                descentMoveTarget = null;
                descentMoveMode = null;
                resetMovement();
                return StepResult.running("阶梯站位失效，正在换方向继续下挖");
            }
            if (isMiningRunStep(activeStep)) {
                rejectSafeMiningPointIfReturning(moveTarget, mode, "stand_no_longer_valid");
                descentMoveTarget = null;
                descentMoveMode = null;
                resetMovement();
                return recoverableMiningFailure(
                    MiningFailureType.STAND_INVALID,
                    "stand_no_longer_valid",
                    "挖矿站位已经不可站立，目标站位 " + moveTarget.toShortString()
                );
            }
            rejectTarget(moveTarget, "stand_no_longer_valid");
            descentMoveTarget = null;
            descentMoveMode = null;
            resetMovement();
            return null;
        }
        markMiningMode(mode);
        double footDistanceSq = aiPlayer.position().distanceToSqr(Vec3.atBottomCenterOf(moveTarget));
        if (hasReachedMiningMoveTarget(moveTarget, footDistanceSq, adjacentMiningMove)) {
            return completeDescentMoveTarget(mode, moveTarget, reachedMessage);
        }
        if (adjacentMiningMove) {
            StepResult adjacentResult = tickAdjacentMiningMoveTarget(moveTarget, mode, movingMessage, fallbackMessage, footDistanceSq);
            if (adjacentResult != null) {
                return adjacentResult;
            }
        }
        aiPlayer.setSprinting(true);
        aiPlayer.getNavigation().moveTo(
            moveTarget.getX() + 0.5D,
            moveTarget.getY(),
            moveTarget.getZ() + 0.5D,
            SurvivalUtils.TASK_RUN_SPEED
        );

        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(moveTarget));
        if (distanceSq + 0.5D < closestTargetDistanceSq) {
            closestTargetDistanceSq = distanceSq;
            moveTicksWithoutProgress = 0;
            return StepResult.running(movingMessage);
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > DESCENT_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' cannot reach descent move target: targetStep={}, pos={}, mode={}, distanceSq={}, noProgressTicks={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                activeStep == null ? "unknown" : activeStep.describe(),
                moveTarget.toShortString(),
                mode,
                String.format("%.2f", distanceSq),
                moveTicksWithoutProgress);
            descentMoveTarget = null;
            descentMoveMode = null;
            resetMovement();
            breakTicks = 0;
            if (isMiningRunStep(activeStep)) {
                rejectSafeMiningPointIfReturning(moveTarget, mode, "movement_stuck");
                return recoverableMiningFailure(
                    MiningFailureType.MOVE_STUCK,
                    "descent_move_stuck:" + mode,
                    "移动到挖矿站位失败，站位 " + moveTarget.toShortString() + "，模式 " + mode
                );
            }
            rejectTarget(moveTarget, "movement_stuck");
            return StepResult.running(fallbackMessage);
        }
        return StepResult.running(movingMessage);
    }

    private String descentMoveModeOr(String fallback) {
        return descentMoveMode == null || descentMoveMode.isBlank() ? fallback : descentMoveMode;
    }

    private boolean hasReachedMiningMoveTarget(BlockPos moveTarget, double footDistanceSq, boolean adjacentMiningMove) {
        return aiPlayer.blockPosition().equals(moveTarget)
            || (adjacentMiningMove && footDistanceSq <= ADJACENT_MINING_MOVE_REACHED_DISTANCE_SQ);
    }

    private StepResult completeDescentMoveTarget(String mode, BlockPos moveTarget, String reachedMessage) {
        aiPlayer.getNavigation().stop();
        aiPlayer.setDeltaMovement(Vec3.ZERO);
        aiPlayer.setSprinting(false);
        descentMoveTarget = null;
        descentMoveMode = null;
        resetMovement();
        recordMiningWaypoint(mode);
        recordDescentDetourProgressIfNeeded(mode, moveTarget);
        recordBranchTunnelAdvanceIfNeeded(mode, moveTarget);
        recordGuidedActionProgress("reach_move_target:" + mode);
        recordStairDescentStepIfNeeded(mode, moveTarget);
        if (isReturnSafePointMode(mode)) {
            failedSafeMiningPoints.remove(moveTarget);
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' reached safe mining fallback: pos={}, mode={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                moveTarget.toShortString(),
                mode);
        }
        if (stairStandTarget != null && moveTarget.equals(stairStandTarget)) {
            clearStairStepPlan();
        }
        return StepResult.running(reachedMessage);
    }

    private void recordBranchTunnelAdvanceIfNeeded(String mode, BlockPos moveTarget) {
        if (!"branch_tunnel_move".equals(mode) && !"branch_tunnel".equals(mode)) {
            return;
        }
        boolean mainDirectionMove = miningRoutePlan == null
            || branchDirection == null
            || branchDirection == miningRoutePlan.mainDirection();
        Direction progressDirection = branchDirection;
        if (mainDirectionMove) {
            branchTunnelBlocks = mainTunnelController == null ? branchTunnelBlocks + 1 : mainTunnelController.segmentBlocks() + 1;
        } else {
            branchTunnelBlocks++;
        }
        if (mainDirectionMove && mainTunnelController != null) {
            mainTunnelController.recordAdvance(moveTarget);
        }
        if (mainDirectionMove && branchMiningPattern != null) {
            branchMiningPattern.recordMainAdvance();
            if (branchMiningPattern.isBranchTurn()) {
                branchDirection = branchMiningPattern.nextDirection();
                branchTunnelBlocks = 0;
                AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' switches from main tunnel to fishbone branch: direction={}, pattern={}",
                    taskId,
                    aiPlayer.getAiPlayerName(),
                    branchDirection.getName(),
                    branchMiningPattern.toLogText());
            }
        }
        if (miningRun != null) {
            miningRun.recordBranchProgress(progressDirection, moveTarget == null ? aiPlayer.blockPosition().getY() : moveTarget.getY(),
                branchTunnelBlocks, "move_reached");
        }
    }

    private boolean isSideBranchTunnelActive() {
        return branchMiningPattern != null
            && branchMiningPattern.isBranchTurn()
            && miningRoutePlan != null
            && branchDirection != null
            && branchDirection != miningRoutePlan.mainDirection();
    }

    private StepResult tickAdjacentMiningMoveTarget(
        BlockPos moveTarget,
        String mode,
        String movingMessage,
        String fallbackMessage,
        double footDistanceSq
    ) {
        Vec3 target = Vec3.atBottomCenterOf(moveTarget);
        Vec3 current = aiPlayer.position();
        double dx = target.x - current.x;
        double dz = target.z - current.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > 0.02D) {
            aiPlayer.getNavigation().stop();
            aiPlayer.setSprinting(true);
            aiPlayer.getMoveControl().setWantedPosition(target.x, target.y, target.z, SurvivalUtils.TASK_RUN_SPEED);
            double speed = Math.min(ADJACENT_MINING_STEP_SPEED, horizontal);
            aiPlayer.setDeltaMovement(dx / horizontal * speed, aiPlayer.getDeltaMovement().y, dz / horizontal * speed);
        }
        if (footDistanceSq + 0.02D < closestTargetDistanceSq) {
            closestTargetDistanceSq = footDistanceSq;
            moveTicksWithoutProgress = 0;
            return StepResult.running(movingMessage);
        }
        moveTicksWithoutProgress++;
        if (moveTicksWithoutProgress > ADJACENT_MINING_MOVE_TIMEOUT_TICKS) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' cannot step into adjacent mining target: targetStep={}, pos={}, mode={}, footDistanceSq={}, noProgressTicks={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                activeStep == null ? "unknown" : activeStep.describe(),
                moveTarget.toShortString(),
                mode,
                String.format("%.2f", footDistanceSq),
                moveTicksWithoutProgress);
            descentMoveTarget = null;
            descentMoveMode = null;
            aiPlayer.getNavigation().stop();
            aiPlayer.setDeltaMovement(Vec3.ZERO);
            aiPlayer.setSprinting(false);
            resetMovement();
            breakTicks = 0;
            if (isMiningRunStep(activeStep)) {
                rejectSafeMiningPointIfReturning(moveTarget, mode, "adjacent_mining_step_stuck");
                return recoverableMiningFailure(
                    MiningFailureType.MOVE_STUCK,
                    "adjacent_mining_step_stuck:" + mode,
                    "相邻矿道站位无法进入，站位 " + moveTarget.toShortString() + "，模式 " + mode
                );
            }
            rejectTarget(moveTarget, "adjacent_mining_step_stuck");
            return StepResult.running(fallbackMessage);
        }
        return StepResult.running(movingMessage);
    }

    private void setDescentMoveTarget(BlockPos pos, String mode) {
        if (pos == null) {
            return;
        }
        descentMoveTarget = pos.immutable();
        descentMoveMode = mode;
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

    private void recordStairDescentStepIfNeeded(String mode, BlockPos stand) {
        if (stairDescentController == null || mode == null) {
            return;
        }
        if (!mode.contains("stair") && !mode.contains("move_lower") && !mode.contains("descent")) {
            return;
        }
        stairDescentController.recordStep(stand);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' stair descent step: step={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            activeStep == null ? "unknown" : activeStep.describe(),
            stairDescentController.toLogText());
    }

    private void recordDescentDetourProgressIfNeeded(String mode, BlockPos stand) {
        if (mode == null || !mode.contains("descent_detour")) {
            return;
        }
        descentDetourBlocks++;
        descentDetourTotalBlocks++;
        if (miningRun != null) {
            miningRun.recordBranchProgress(
                descentDetourDirection == null ? descentDirection : descentDetourDirection,
                stand == null ? aiPlayer.blockPosition().getY() : stand.getY(),
                descentDetourBlocks,
                "descent_detour"
            );
        }
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' descent detour progress: mode={}, stand={}, segmentMoves={}, totalMoves={}/{}, routeClearanceBlocks={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            mode,
            stand == null ? "unknown" : stand.toShortString(),
            descentDetourBlocks,
            descentDetourTotalBlocks,
            DESCENT_DETOUR_MAX_BLOCKS,
            routeClearanceBlocks);
    }

    private String descentDetourSummary() {
        return "水平绕行摘要={direction="
            + (descentDetourDirection == null ? "none" : descentDetourDirection.getName())
            + ", segmentMoves=" + descentDetourBlocks + "/" + DESCENT_DETOUR_SEGMENT_BLOCKS
            + ", totalMoves=" + descentDetourTotalBlocks + "/" + DESCENT_DETOUR_MAX_BLOCKS
            + ", turns=" + descentDetourTurns + "/" + DESCENT_DETOUR_MAX_TURNS
            + ", routeClearanceBlocks=" + routeClearanceBlocks
            + "}";
    }

    private void rotateDescentDetourDirection(String reason) {
        Direction current = descentDetourDirection == null
            ? (descentDirection == null ? horizontalDirectionFromYaw() : descentDirection)
            : descentDetourDirection;
        descentDetourDirection = current.getClockWise();
        descentDirection = descentDetourDirection;
        descentDetourBlocks = 0;
        descentDetourTurns++;
        recordStairDescentFailure("descent_detour:" + reason);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rotates descent detour: reason={}, nextDirection={}, turns={}, totalMoves={}/{}, routeClearanceBlocks={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason == null || reason.isBlank() ? "unknown" : reason,
            descentDetourDirection.getName(),
            descentDetourTurns,
            descentDetourTotalBlocks,
            DESCENT_DETOUR_MAX_BLOCKS,
            routeClearanceBlocks);
    }

    private String stairDescentLogText() {
        return stairDescentController == null ? "stairDescent=none" : "stairDescent={" + stairDescentController.toLogText() + "}";
    }

    private boolean isAdjacentMiningMoveTarget(BlockPos current, BlockPos target) {
        if (current == null || target == null) {
            return false;
        }
        int horizontal = Math.abs(current.getX() - target.getX()) + Math.abs(current.getZ() - target.getZ());
        int vertical = current.getY() - target.getY();
        return horizontal == 1 && vertical >= 0 && vertical <= 1;
    }

    private MiningPassagePolicy.Decision miningPassageDecision(BlockPos current, BlockPos stand) {
        return miningTunnelStep(current, stand).passageDecision();
    }

    private MiningTunnelStepper.Plan miningTunnelStep(BlockPos current, BlockPos stand) {
        currentTunnelStep = MiningTunnelStepper.from(miningMovementSimulation(current, stand));
        return currentTunnelStep;
    }

    private MiningMovementSimulator.Result miningMovementSimulation(BlockPos current, BlockPos stand) {
        if (current == null || stand == null) {
            return MiningMovementSimulator.Result.blocked(null, MiningPassagePolicy.MoveIntent.FORWARD_LEVEL, "passage_blocked:missing_position");
        }
        boolean descending = stand.getY() < current.getY();
        BlockPos entryHead = stand.above(2);
        BlockPos head = stand.above();
        BlockPos feet = stand;
        Block entryHeadBlock = aiPlayer.level().getBlockState(entryHead).getBlock();
        Block headBlock = aiPlayer.level().getBlockState(head).getBlock();
        Block feetBlock = aiPlayer.level().getBlockState(feet).getBlock();
        Block supportBlock = aiPlayer.level().getBlockState(stand.below()).getBlock();
        boolean entryHeadAir = !descending || isAirBlock(entryHead);
        boolean headAir = isAirBlock(head);
        boolean feetAir = isAirBlock(feet);
        return MiningMovementSimulator.simulate(new MiningMovementSimulator.Input(
            current,
            stand,
            new MiningMovementSimulator.BlockInfo(
                blockId(entryHeadBlock),
                entryHeadAir,
                entryHeadAir || breakableMiningPassageBlock(entryHead) != null,
                miningPassageDanger(entryHead, entryHeadAir, "passage_entry_head"),
                false
            ),
            new MiningMovementSimulator.BlockInfo(
                blockId(headBlock),
                headAir,
                headAir || breakableMiningPassageBlock(head) != null,
                miningPassageDanger(head, headAir, "passage_head"),
                false
            ),
            new MiningMovementSimulator.BlockInfo(
                blockId(feetBlock),
                feetAir,
                feetAir || breakableMiningPassageBlock(feet) != null,
                miningPassageDanger(feet, feetAir, "passage_feet"),
                false
            ),
            new MiningMovementSimulator.BlockInfo(
                blockId(supportBlock),
                false,
                false,
                null,
                isSafeMiningRouteSupport(stand)
            )
        ));
    }

    private boolean queueSupportFromSimulation(MiningMovementSimulator.Result simulation, BlockPos current, BlockPos stand, String reason) {
        if (simulation == null || simulation.action() != MiningMovementSimulator.Action.PLACE_SUPPORT || stand == null) {
            return false;
        }
        Direction direction = horizontalDirectionBetween(current, stand);
        return queueMiningSupportPlacement(
            stand,
            stand.above(),
            stand,
            direction,
            "movement_simulator:" + reason + ":" + simulation.reason()
        );
    }

    private Direction horizontalDirectionBetween(BlockPos current, BlockPos stand) {
        if (current == null || stand == null) {
            return horizontalDirectionFromYaw();
        }
        int dx = Integer.compare(stand.getX() - current.getX(), 0);
        int dz = Integer.compare(stand.getZ() - current.getZ(), 0);
        if (Math.abs(stand.getX() - current.getX()) >= Math.abs(stand.getZ() - current.getZ())) {
            if (dx > 0) {
                return Direction.EAST;
            }
            if (dx < 0) {
                return Direction.WEST;
            }
        }
        if (dz > 0) {
            return Direction.SOUTH;
        }
        if (dz < 0) {
            return Direction.NORTH;
        }
        return horizontalDirectionFromYaw();
    }

    private MiningPassagePolicy.MoveIntent miningMoveIntent(BlockPos current, BlockPos stand) {
        if (current != null && stand != null && stand.getY() < current.getY()) {
            return MiningPassagePolicy.MoveIntent.FORWARD_DOWN;
        }
        return MiningPassagePolicy.MoveIntent.FORWARD_LEVEL;
    }

    private BlockPos miningPassageDigTarget(BlockPos stand, MiningPassagePolicy.Decision decision) {
        return MiningPassagePolicy.digTarget(stand, decision);
    }

    private boolean queueSupportFromTunnelStep(
        MiningTunnelStepper.Plan tunnelStep,
        BlockPos current,
        BlockPos stand,
        String reason
    ) {
        return tunnelStep != null
            && tunnelStep.needsSupport()
            && queueSupportFromSimulation(tunnelStep.simulation(), current, stand, reason);
    }

    private StepResult tickMiningRouteClearance(BlockPos digTarget, BlockPos standTarget, String mode, String reason) {
        if (digTarget == null) {
            return null;
        }
        Block block = aiPlayer.level().getBlockState(digTarget).getBlock();
        String targetDanger = blockingMiningDanger(digTarget, "route_clearance:" + mode);
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, digTarget, "route_clearance:" + mode);
            breakTicks = 0;
            descentMoveTarget = null;
            descentMoveMode = null;
            resetMovement();
            if (isMiningRunStep(activeStep)) {
                return recoverableMiningFailure(
                    MiningFailureType.UNSAFE_TARGET,
                    "unsafe_route_clearance:" + targetDanger,
                    "挖矿通道存在环境风险：" + targetDanger + "，挖掘点 " + digTarget.toShortString()
                );
            }
            return stopMiningTask("unsafe_route_clearance:" + targetDanger,
                "挖矿通道存在环境风险：" + targetDanger + "，挖掘点 " + digTarget.toShortString());
        }
        recordMiningDigAttempt(digTarget, block);
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        if (ticks % 40 == 0) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' clears mining passage: mode={}, current={}, stand={}, digTarget={}, block={}, routeClearanceBlocks={}, reason={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                mode,
                aiPlayer.blockPosition().toShortString(),
                standTarget == null ? "unknown" : standTarget.toShortString(),
                digTarget.toShortString(),
                blockId(block),
                routeClearanceBlocks,
                reason);
        }
        aiPlayer.lookAtWorkTarget(digTarget);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            String phaseText = currentTunnelStep == null ? "清理通道" : currentTunnelStep.displayName();
            return StepResult.running("正在开出两格高挖矿通道：" + phaseText);
        }
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(digTarget, "route_clearance:" + mode);
        recordMiningDigResult(digTarget, block, breakResult);
        breakTicks = 0;
        if (!breakResult.success()) {
            if (queueOcclusionClearance(digTarget, breakResult, "route_clearance:" + mode)) {
                return StepResult.running("挖矿通道被视线遮挡，先清理遮挡方块");
            }
            if (isMiningRunStep(activeStep)) {
                descentMoveTarget = null;
                descentMoveMode = null;
                resetMovement();
                if (!registerBreakFailure(digTarget, breakResult.reason())) {
                    return StepResult.running("挖矿通道方块破坏失败，正在重试或换路线");
                }
                rejectTarget(digTarget, "route_clearance_break_failed:" + breakResult.reason());
                return recoverableMiningFailure(
                    MiningFailureType.MOVE_STUCK,
                    "route_clearance_break_failed:" + mode + ":" + breakResult.reason(),
                    "挖矿通道方块连续破坏失败，挖掘点 " + digTarget.toShortString()
                        + "，模式 " + mode + "，原因 " + breakResult.reason()
                );
            }
            return StepResult.running("挖矿通道方块破坏失败，正在重试");
        }
        advanceStairStepAfterBreak(digTarget);
        recordGuidedProspectDigProgress();
        routeClearanceBlocks++;
        if ("prospect_guided_move".equals(mode) || "prospect_guided_step".equals(mode)) {
            guidedTunnelBlocks++;
        }
        recordMiningWaypoint("route_clearance:" + mode);
        recordCaveEntranceIfFound(digTarget);
        return StepResult.running("已清出一格挖矿通道，准备继续前进");
    }

    private void rejectTarget(BlockPos pos) {
        rejectTarget(pos, "unknown");
    }

    private void rejectTarget(BlockPos pos, String reason) {
        if (pos == null) {
            return;
        }
        if (isCurrentProspectCandidate(pos)) {
            cooldownProspectCandidate(pos, reason);
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

    private Set<BlockPos> prospectRejectedTargets() {
        Set<BlockPos> rejected = new HashSet<>(rejectedTargets);
        rejected.addAll(miningCandidateCooldown.activePositions(ticks));
        return rejected;
    }

    private boolean isCurrentProspectCandidate(BlockPos pos) {
        if (pos == null || !isMiningRunStep(activeStep) || activeMiningProfile == null || !activeMiningProfile.prospectable()) {
            return false;
        }
        return (lastProspectResult != null && pos.equals(lastProspectResult.orePos()))
            || (stageMiningPlan != null && pos.equals(stageMiningPlan.orePos()));
    }

    private void cooldownProspectCandidate(BlockPos pos, String reason) {
        MiningCandidateCooldown.Entry entry = miningCandidateCooldown.reject(pos, reason, ticks);
        if (entry == null) {
            return;
        }
        if (miningRun != null) {
            miningRun.recordTargetRejected(ticks, pos, reason);
        }
        if (miningCandidateCooldown.shouldLog(pos, reason, ticks)) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' cools prospect candidate: pos={}, reason={}, expiresAtTick={}, cooldowns={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                pos.toShortString(),
                reason == null || reason.isBlank() ? "unknown" : reason,
                entry.expiresAtTick(),
                miningCandidateCooldown.summary(ticks));
        }
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

    private MiningToolGate.Result evaluateMiningToolGate(String requiredTool) {
        ItemStack bestPickaxe = aiPlayer.getBestToolStackFor("pickaxe", MIN_MINING_TOOL_DURABILITY);
        if (bestPickaxe.isEmpty()) {
            bestPickaxe = aiPlayer.getBestToolStackFor("pickaxe");
        }
        String currentBestTool = bestPickaxe.isEmpty() ? "minecraft:air" : itemKey(bestPickaxe.getItem());
        int remaining = toolRemainingDurability(bestPickaxe);
        return MiningToolGate.evaluate(requiredTool, currentBestTool, remaining, MIN_MINING_TOOL_DURABILITY);
    }

    private int toolRemainingDurability(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0;
        }
        if (!stack.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getDamageValue());
    }

    private void logMiningToolGate(MiningResource.Profile profile, MiningToolGate.Result result, String phase) {
        if (profile == null || result == null || profile.requiredTool() == null) {
            return;
        }
        if (ticks > 1 && ticks % 100 != 0 && result.ready()) {
            return;
        }
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' mining tool gate: resource={}, phase={}, {}, pos={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            profile.displayName(),
            phase == null || phase.isBlank() ? "unknown" : phase,
            result.toLogText(),
            aiPlayer.blockPosition().toShortString());
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
        if (snapshot.getWorld().getNearbyHostiles() > 0) {
            AiPlayerMod.debug("mining", "[taskId={}] AiPlayer '{}' keeps cave seed enabled for {} because hostile damage is disabled: nearbyHostiles={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                profile.displayName(),
                snapshot.getWorld().getNearbyHostiles());
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

    private boolean ensureMiningDropCapacity(Block block, String reason) {
        if (block == null || block == Blocks.AIR) {
            return true;
        }
        Item drop = SurvivalUtils.getSurvivalDrop(block.defaultBlockState());
        int dropCount = SurvivalUtils.getDropCount(block.defaultBlockState());
        if (drop == Items.AIR || dropCount <= 0 || aiPlayer.hasBackpackSpaceFor(drop, dropCount)) {
            return true;
        }
        setMiningState(MiningState.WAITING_FOR_PLAYER, "inventory_full_for_drop:" + reason);
        AiPlayerMod.warn("mining", "[taskId={}] AiPlayer '{}' mining inventory lacks drop capacity: reason={}, block={}, expectedDrop={}x{}, available={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            blockId(block),
            itemKey(drop),
            dropCount,
            aiPlayer.availableBackpackSpaceFor(drop),
            new TreeMap<>(aiPlayer.getInventorySnapshot()));
        return false;
    }

    private StepResult checkMiningDanger(String context) {
        if (ticks < nextDangerCheckTick) {
            return null;
        }
        nextDangerCheckTick = ticks + 40;
        WorldSnapshot snapshot = WorldSnapshot.capture(aiPlayer, "mining_danger_check");
        if (snapshot.getWorld().getNearbyHostiles() > 0) {
            AiPlayerMod.debug("mining", "[taskId={}] AiPlayer '{}' ignores nearby hostiles while mining because hostile damage is disabled: context={}, hostiles={}, pos={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                context,
                snapshot.getWorld().getNearbyHostiles(),
                aiPlayer.blockPosition().toShortString());
        }
        if (aiPlayer.getNavigation().isStuck()) {
            recordMiningDanger("navigation_stuck", aiPlayer.blockPosition(), context);
            if (isMiningRunStep(activeStep)) {
                return recoverableMiningFailure(
                    MiningFailureType.MOVE_STUCK,
                    "navigation_stuck:" + context,
                    "挖矿路径卡住，正在尝试回退到最近安全矿道点"
                );
            }
            return stopMiningTask("navigation_stuck", "挖矿路径卡住，无法继续执行当前任务");
        }
        return null;
    }

    private String dangerAroundDigTarget(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        if (pos.equals(aiPlayer.blockPosition().below())) {
            return "vertical_foot_dig";
        }
        return null;
    }

    private String blockingMiningDanger(BlockPos pos, String action) {
        String danger = dangerAroundDigTarget(pos);
        if (danger != null && isMiningRunStep(activeStep)) {
            recordIgnoredMiningDanger(danger, pos, action);
            return null;
        }
        return danger;
    }

    private String miningPassageDanger(BlockPos pos, boolean air, String action) {
        return air ? null : blockingMiningDanger(pos, action);
    }

    private void recordIgnoredMiningDanger(String reason, BlockPos pos, String action) {
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        boolean samePos = pos != null && pos.equals(lastIgnoredMiningDangerPos);
        boolean sameReason = safeReason.equals(lastIgnoredMiningDangerReason);
        if (samePos && sameReason && ticks - lastIgnoredMiningDangerTick < 40) {
            return;
        }
        lastIgnoredMiningDangerPos = pos == null ? null : pos.immutable();
        lastIgnoredMiningDangerReason = safeReason;
        lastIgnoredMiningDangerTick = ticks;
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' ignored_mining_danger: reason={}, pos={}, action={}, policy=invulnerable_mining",
            taskId,
            aiPlayer.getAiPlayerName(),
            safeReason,
            pos == null ? "unknown" : pos.toShortString(),
            action == null || action.isBlank() ? "unknown" : action);
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
        recordSafeMiningPoint(mode, "waypoint");
    }

    private void recordSafeMiningPoint(String stage, String lastAction) {
        BlockPos standPos = aiPlayer.blockPosition();
        if (!isSafeMiningMemoryStand(standPos)) {
            AiPlayerMod.debug("mining", "[taskId={}] AiPlayer '{}' skips unsafe mining memory point: pos={}, stage={}, action={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                standPos == null ? "unknown" : standPos.toShortString(),
                stage,
                lastAction);
            return;
        }
        String direction = miningMemoryDirection();
        if (activeResourceState != null) {
            activeResourceState.recordSafeMiningPoint(standPos, direction, stage, ticks, lastAction);
        }
        if (sharedMiningState != null) {
            sharedMiningState.recordSafeMiningPoint(standPos, direction, stage, ticks, lastAction);
        }
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' records safe mining point: pos={}, direction={}, stage={}, action={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            standPos.toShortString(),
            direction,
            stage,
            lastAction);
    }

    private String miningMemoryDirection() {
        Direction direction = branchDirection != null
            ? branchDirection
            : descentDirection != null ? descentDirection : horizontalDirectionFromYaw();
        return direction == null ? "unknown" : direction.getName();
    }

    private ResourceGatherSession.SafeMiningPoint latestSafeMiningPoint() {
        ResourceGatherSession.ResourceState state = sharedMiningState != null ? sharedMiningState : activeResourceState;
        if (state == null || state.getSafeMiningPoints().isEmpty()) {
            return null;
        }
        List<ResourceGatherSession.SafeMiningPoint> points = state.getSafeMiningPoints();
        return points.get(points.size() - 1);
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

    private void recordMiningDigResult(BlockPos digTarget, Block block, SurvivalUtils.BreakResult result) {
        if (miningRun != null && result != null) {
            miningRun.recordDigResult(
                ticks,
                currentMiningDigMode(),
                digTarget,
                block,
                result.success(),
                result.success() ? countAirNeighbors(digTarget) : 0,
                new TreeMap<>(aiPlayer.getInventorySnapshot()),
                result.reason(),
                result.blocker()
            );
        }
    }

    private SurvivalUtils.BreakResult breakBlockDetailed(BlockPos digTarget, String context) {
        SurvivalUtils.BreakResult result = SurvivalUtils.breakBlockDetailed(aiPlayer, digTarget);
        if (result.success()) {
            clearBreakFailureMemory();
        } else {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' block break failed: context={}, target={}, block={}, reason={}, blocker={}, consecutive={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                context == null || context.isBlank() ? currentMiningDigMode() : context,
                digTarget == null ? "none" : digTarget.toShortString(),
                blockId(result.block()),
                result.reason(),
                result.blockerText(),
                nextBreakFailureCount(digTarget, result.reason(), false));
        }
        return result;
    }

    private boolean queueOcclusionClearance(BlockPos originalTarget, SurvivalUtils.BreakResult result, String context) {
        if (result == null
            || result.success()
            || !"blocked_line_of_sight".equals(result.reason())
            || result.blocker() == null
            || result.blocker().equals(originalTarget)) {
            return false;
        }
        BlockPos blocker = result.blocker();
        String blockerDanger = blockingMiningDanger(blocker, "occlusion_queue");
        if (blockerDanger != null) {
            recordMiningDanger(blockerDanger, blocker, "occlusion_queue");
            rejectTarget(originalTarget, "occlusion_danger:" + blockerDanger + ":" + blocker.toShortString());
            return false;
        }
        if (breakableMiningPassageBlock(blocker) == null) {
            rejectTarget(originalTarget, "line_of_sight_blocked_by_unbreakable:" + blocker.toShortString());
            return false;
        }
        pendingOcclusionClearTarget = blocker.immutable();
        pendingOcclusionOriginalTarget = originalTarget == null ? null : originalTarget.immutable();
        pendingOcclusionContext = context == null || context.isBlank() ? currentMiningDigMode() : context;
        resetMovement();
        breakTicks = 0;
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' queues occlusion clearance: context={}, original={}, blocker={}, block={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            pendingOcclusionContext,
            pendingOcclusionOriginalTarget == null ? "none" : pendingOcclusionOriginalTarget.toShortString(),
            pendingOcclusionClearTarget.toShortString(),
            blockId(aiPlayer.level().getBlockState(pendingOcclusionClearTarget).getBlock()));
        return true;
    }

    private StepResult tickPendingOcclusionClearance() {
        if (pendingOcclusionClearTarget == null) {
            return null;
        }
        BlockPos target = pendingOcclusionClearTarget;
        if (aiPlayer.level().getBlockState(target).isAir()) {
            clearPendingOcclusionClearance();
            clearBreakFailureMemory();
            return StepResult.running("遮挡方块已消失，继续原挖矿路线");
        }
        Block block = aiPlayer.level().getBlockState(target).getBlock();
        if (breakableMiningPassageBlock(target) == null) {
            rejectTarget(pendingOcclusionOriginalTarget, "occlusion_unbreakable:" + target.toShortString());
            clearPendingOcclusionClearance();
            return StepResult.running("视线遮挡方块不可清理，正在换目标或换路线");
        }
        String targetDanger = blockingMiningDanger(target, "occlusion_clearance");
        if (targetDanger != null) {
            recordMiningDanger(targetDanger, target, "occlusion_clearance");
            rejectTarget(pendingOcclusionOriginalTarget, "occlusion_danger:" + targetDanger);
            clearPendingOcclusionClearance();
            return StepResult.running("视线遮挡方块存在风险，正在换目标或换路线");
        }
        if (!SurvivalInteractionValidator.isWithinReach(aiPlayer, target, 3.5D)) {
            double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(target));
            if (!SurvivalUtils.moveNear(aiPlayer, target, 3.5D)) {
                breakTicks = 0;
                if (distanceSq + 0.5D < closestTargetDistanceSq) {
                    closestTargetDistanceSq = distanceSq;
                    moveTicksWithoutProgress = 0;
                    return StepResult.running("正在接近视线遮挡方块");
                }
                moveTicksWithoutProgress++;
                if (moveTicksWithoutProgress > TARGET_MOVE_TIMEOUT_TICKS || aiPlayer.getNavigation().isStuck()) {
                    rejectTarget(pendingOcclusionOriginalTarget, "occlusion_unreachable:" + target.toShortString());
                    clearPendingOcclusionClearance();
                    resetMovement();
                    return StepResult.running("视线遮挡方块不可达，正在换目标或换路线");
                }
                return StepResult.running("正在接近视线遮挡方块");
            }
            resetMovement();
        }
        recordMiningDigAttempt(target, block);
        SurvivalUtils.equipBestToolForBlock(aiPlayer, block);
        aiPlayer.lookAtWorkTarget(target);
        aiPlayer.swingWorkHand(InteractionHand.MAIN_HAND);
        breakTicks++;
        if (breakTicks < SurvivalUtils.getBreakDelay(aiPlayer, block)) {
            return StepResult.running("正在清理挖矿视线遮挡");
        }
        SurvivalUtils.BreakResult breakResult = breakBlockDetailed(target, pendingOcclusionContext + ":occlusion");
        recordMiningDigResult(target, block, breakResult);
        breakTicks = 0;
        if (breakResult.success()) {
            clearPendingOcclusionClearance();
            return StepResult.running("已清理挖矿视线遮挡，继续原路线");
        }
        if (registerBreakFailure(target, breakResult.reason())) {
            rejectTarget(pendingOcclusionOriginalTarget, "occlusion_clear_failed:" + breakResult.reason());
            clearPendingOcclusionClearance();
            return StepResult.running("视线遮挡连续清理失败，正在换目标或换路线");
        }
        return StepResult.running("清理视线遮挡失败，正在重试或换路线");
    }

    private boolean registerBreakFailure(BlockPos target, String reason) {
        return nextBreakFailureCount(target, reason, true) >= MAX_CONSECUTIVE_BREAK_FAILURES;
    }

    private int nextBreakFailureCount(BlockPos target, String reason, boolean mutate) {
        String safeReason = reason == null || reason.isBlank() ? "break_failed" : reason;
        boolean same = target != null
            && target.equals(lastFailedBreakTarget)
            && safeReason.equals(lastFailedBreakReason);
        int next = same ? consecutiveBreakFailures + 1 : 1;
        if (mutate) {
            lastFailedBreakTarget = target == null ? null : target.immutable();
            lastFailedBreakReason = safeReason;
            consecutiveBreakFailures = next;
        }
        return next;
    }

    private void clearBreakFailureMemory() {
        lastFailedBreakTarget = null;
        lastFailedBreakReason = null;
        consecutiveBreakFailures = 0;
    }

    private void clearPendingOcclusionClearance() {
        pendingOcclusionClearTarget = null;
        pendingOcclusionOriginalTarget = null;
        pendingOcclusionContext = null;
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

    private StepResult recoverableMiningFailure(MiningFailureType type, String reason, String message) {
        MiningFailurePolicy.Decision decision = miningFailurePolicy.record(type, reason);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' mining failure review: {}, step={}, pos={}, summary={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            decision.toLogText(),
            activeStep == null ? "unknown" : activeStep.describe(),
            aiPlayer.blockPosition().toShortString(),
            miningFailurePolicy.summary());
        if (decision.terminal()) {
            return stopMiningTask(reason,
                message + "；恢复已达到上限，失败类型=" + decision.type().key()
                    + "，恢复动作=" + decision.action().key()
                    + "，失败摘要=" + miningFailurePolicy.summary());
        }
        applyMiningRecovery(decision);
        return StepResult.running(message + "；正在恢复：" + decision.action().key()
            + " (" + decision.count() + "/" + decision.limit() + ")");
    }

    private void applyMiningRecovery(MiningFailurePolicy.Decision decision) {
        if (decision == null) {
            return;
        }
        breakTicks = 0;
        targetPos = null;
        activeInteractionTarget = null;
        descentMoveTarget = null;
        descentMoveMode = null;
        clearStairStepPlan();
        resetMovement();
        if (tryReturnToSafeMiningPoint(decision)) {
            return;
        }
        switch (decision.action()) {
            case SWITCH_STAIR_DIRECTION -> rotateDescentDirection();
            case RESET_DESCENT_START -> resetDescentSearchForRecovery();
            case RESCAN_TARGET -> {
                if (activeMiningProfile != null) {
                    requestProspectRescan(decision.reason(), activeMiningProfile.displayName());
                }
            }
            case PREPARE_TOOL, ASK_PLAYER -> {
            }
        }
    }

    private boolean tryReturnToSafeMiningPoint(MiningFailurePolicy.Decision decision) {
        return returnToSafeMiningPoint(
            decision == null ? "unknown" : decision.action().key(),
            decision == null ? "unknown" : decision.reason(),
            decision == null ? "unknown" : decision.toLogText()
        );
    }

    private boolean returnToSafeMiningPoint(String actionKey, String reason, String source) {
        ResourceGatherSession.ResourceState state = sharedMiningState != null ? sharedMiningState : activeResourceState;
        if (state == null) {
            return false;
        }
        BlockPos current = aiPlayer.blockPosition();
        Map<String, Integer> rejectReasons = new TreeMap<>();
        Optional<ResourceGatherSession.SafeMiningPoint> safePoint = state.getSafeMiningPoints().stream()
            .filter(point -> {
                String rejection = safeMiningFallbackRejection(point, current);
                if (rejection != null) {
                    rejectReasons.merge(rejection, 1, Integer::sum);
                    return false;
                }
                return true;
            })
            .sorted(Comparator
                .comparingInt((ResourceGatherSession.SafeMiningPoint point) -> Math.max(0, point.y() - current.getY()))
                .thenComparingInt(point -> Math.abs(point.y() - current.getY()))
                .thenComparingDouble(point -> horizontalDistanceSq(point.pos(), current))
                .thenComparing((ResourceGatherSession.SafeMiningPoint point) -> -point.tick()))
            .findFirst();
        if (safePoint.isEmpty()) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' has no usable safe mining fallback: reason={}, knownSafePoints={}, rejected={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                reason == null || reason.isBlank() ? "unknown" : reason,
                state.getSafeMiningPoints().size(),
                rejectReasons);
            return false;
        }
        ResourceGatherSession.SafeMiningPoint point = safePoint.get();
        setDescentMoveTarget(point.pos(), "return_safe_point:" + (actionKey == null || actionKey.isBlank() ? "unknown" : actionKey));
        setMiningState(MiningState.RECOVERING, "return_safe_point:" + (reason == null || reason.isBlank() ? "unknown" : reason));
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' returns to safe mining point: {}, recovery={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            point.toLogText(),
            source == null || source.isBlank() ? "unknown" : source);
        return true;
    }

    private String safeMiningFallbackRejection(ResourceGatherSession.SafeMiningPoint point, BlockPos current) {
        if (point == null || point.pos() == null || current == null) {
            return "missing_point";
        }
        BlockPos pos = point.pos();
        if (pos.equals(current)) {
            return "current_position";
        }
        if (failedSafeMiningPoints.contains(pos)) {
            return "failed_this_run";
        }
        if (!isSafeMiningMemoryStand(pos)) {
            return "unsafe_or_stale";
        }
        int upDelta = point.y() - current.getY();
        if (upDelta > SAFE_MINING_FALLBACK_MAX_UP_DELTA) {
            return "above_current_route";
        }
        if (horizontalDistanceSq(pos, current) > SAFE_MINING_FALLBACK_MAX_HORIZONTAL_DISTANCE * SAFE_MINING_FALLBACK_MAX_HORIZONTAL_DISTANCE) {
            return "too_far";
        }
        if (aiPlayer.getNavigation().createPath(pos, 1) == null) {
            return "no_path";
        }
        return null;
    }

    private int horizontalDistanceSq(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private void rejectSafeMiningPointIfReturning(BlockPos pos, String mode, String reason) {
        if (!isReturnSafePointMode(mode) || pos == null) {
            return;
        }
        BlockPos failed = pos.immutable();
        failedSafeMiningPoints.add(failed);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejects safe mining fallback: pos={}, reason={}, failedSafePoints={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            failed.toShortString(),
            reason == null || reason.isBlank() ? "unknown" : reason,
            failedSafeMiningPoints.size());
    }

    private boolean isReturnSafePointMode(String mode) {
        return mode != null && mode.startsWith("return_safe_point");
    }

    private void rotateDescentDirection() {
        if (stairDescentController != null) {
            descentDirection = stairDescentController.recordFailure("rotate_direction");
        } else {
            Direction current = descentDirection == null ? horizontalDirectionFromYaw() : descentDirection;
            descentDirection = current.getClockWise();
        }
        downwardNoTargetAttempts = 0;
    }

    private void resetDescentSearchForRecovery() {
        rotateDescentDirection();
        downwardDigBlocks = 0;
        downwardNoTargetAttempts = 0;
        stoneDescentStartTarget = null;
        stoneDescentStartFailures = 0;
        if (stairDescentController != null && stairDescentController.shouldRelocateStart()) {
            stairDescentController.resetFailures();
        }
    }

    private void recordStairDescentFailure(String reason) {
        if (stairDescentController == null) {
            return;
        }
        descentDirection = stairDescentController.recordFailure(reason);
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' stair descent failure: reason={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason == null || reason.isBlank() ? "unknown" : reason,
            stairDescentController.toLogText());
    }

    private StepResult stopMiningTask(String reason, String message) {
        setMiningState(MiningState.RETURNING, "terminal_failure:" + reason);
        AiPlayerMod.warn("mining", "[taskId={}] AiPlayer '{}' stops mining task: reason={}, message={}, step={}, pos={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            message,
            activeStep == null ? "unknown" : activeStep.describe(),
            aiPlayer.blockPosition().toShortString(),
            new TreeMap<>(aiPlayer.getInventorySnapshot()));
        AiPlayerMod.debug("mining", "[taskId={}] AiPlayer '{}' mining stop debug: reason={}, targetPos={}, descentMoveTarget={}, stagePlan={}, miningRunSummary={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            reason,
            targetPos == null ? "none" : targetPos.toShortString(),
            descentMoveTarget == null ? "none" : descentMoveTarget.toShortString(),
            stageMiningPlan == null ? "none" : stageMiningPlan.toLogText(),
            miningRun == null ? "none" : miningRun.summary());
        return miningTerminalFailure(reason, message + "；任务已结束，AI 将回到玩家身边");
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

    private Optional<InteractionTarget> findNearestChestInteractionWithItem(Item item) {
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(
                center.offset(-CHEST_RADIUS, -4, -CHEST_RADIUS),
                center.offset(CHEST_RADIUS, 4, CHEST_RADIUS)
            )
            .map(BlockPos::immutable)
            .filter(level::hasChunkAt)
            .filter(pos -> containerHasItem(pos, item))
            .map(pos -> containerInteractionTarget(pos, "withdraw_chest"))
            .flatMap(Optional::stream)
            .filter(target -> interactionFailures.rejectionFor(target, ticks).isEmpty())
            .min(Comparator.comparingDouble(target -> target.targetBlock().distSqr(center)));
    }

    private Optional<InteractionTarget> findNearestChestInteractionForDeposit(Item item) {
        Level level = aiPlayer.level();
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(
                center.offset(-CHEST_RADIUS, -4, -CHEST_RADIUS),
                center.offset(CHEST_RADIUS, 4, CHEST_RADIUS)
            )
            .map(BlockPos::immutable)
            .filter(level::hasChunkAt)
            .filter(pos -> containerCanAccept(pos, item))
            .map(pos -> containerInteractionTarget(pos, "deposit_chest"))
            .flatMap(Optional::stream)
            .filter(target -> interactionFailures.rejectionFor(target, ticks).isEmpty())
            .min(Comparator.comparingDouble(target -> target.targetBlock().distSqr(center)));
    }

    private Optional<InteractionTarget> containerInteractionTarget(BlockPos containerPos, String reason) {
        if (containerPos == null) {
            return Optional.empty();
        }
        if (canWorkBlockDirectly(containerPos, 4.5D)) {
            return Optional.of(new InteractionTarget(
                containerPos.immutable(),
                null,
                4.5D,
                "hand",
                InteractionActionType.OPEN_CONTAINER,
                reason + "_direct"
            ));
        }
        Optional<BlockPos> stand = reachableWorkStand(containerPos);
        return stand.map(pos -> new InteractionTarget(
            containerPos.immutable(),
            pos,
            4.5D,
            "hand",
            InteractionActionType.OPEN_CONTAINER,
            reason
        ));
    }

    private StepResult validateContainerOpenBeforeTransfer(InteractionTarget containerTarget) {
        if (containerTarget == null) {
            return StepResult.failure("缺少箱子交互目标");
        }
        BlockEntity blockEntity = aiPlayer.level().getBlockEntity(containerTarget.targetBlock());
        if (!(blockEntity instanceof Container)) {
            rejectInteractionTarget(containerTarget, "container_missing");
            return StepResult.running("箱子已不存在，正在重新寻找");
        }
        if (containerTarget.hasStandPos() && !aiPlayer.blockPosition().equals(containerTarget.standPos())) {
            rejectInteractionTarget(containerTarget, "stand_not_reached");
            return StepResult.running("尚未到达箱子工作站位，正在重新寻找");
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(containerTarget.targetBlock()));
        if (distanceSq > containerTarget.reachRange() * containerTarget.reachRange()) {
            rejectInteractionTarget(containerTarget, "out_of_reach:" + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq)));
            return StepResult.running("箱子超出交互距离，正在重新寻找");
        }
        return null;
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

    private boolean containerCanAccept(BlockPos pos, Item item) {
        BlockEntity blockEntity = aiPlayer.level().getBlockEntity(pos);
        if (!(blockEntity instanceof Container container)) {
            return false;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) {
                return true;
            }
            if (stack.is(item) && stack.getCount() < stack.getMaxStackSize()) {
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

    private int transferToChest(BlockPos pos, Item item, int count) {
        BlockEntity blockEntity = aiPlayer.level().getBlockEntity(pos);
        if (!(blockEntity instanceof Container container) || item == Items.AIR || count <= 0) {
            return 0;
        }
        List<ItemStack> removed = aiPlayer.removeItemStacks(item, count);
        if (removed.isEmpty()) {
            return 0;
        }
        int moved = 0;
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack moving : removed) {
            ItemStack remaining = moving.copy();
            insertIntoContainer(container, remaining);
            moved += moving.getCount() - remaining.getCount();
            if (!remaining.isEmpty()) {
                leftovers.add(remaining);
            }
        }
        for (ItemStack leftover : leftovers) {
            aiPlayer.addItemStack(leftover);
        }
        if (moved > 0) {
            container.setChanged();
        }
        return moved;
    }

    private void insertIntoContainer(Container container, ItemStack moving) {
        for (int slot = 0; slot < container.getContainerSize() && !moving.isEmpty(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, moving)) {
                int inserted = Math.min(moving.getCount(), stack.getMaxStackSize() - stack.getCount());
                if (inserted > 0) {
                    stack.grow(inserted);
                    moving.shrink(inserted);
                }
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && !moving.isEmpty(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                int inserted = Math.min(moving.getCount(), moving.getMaxStackSize());
                container.setItem(slot, moving.copyWithCount(inserted));
                moving.shrink(inserted);
            }
        }
    }

    private boolean ensureStationItem(String stationItem, Set<String> stack) {
        Block stationBlock = blockFromItemId(stationItem);
        if (stationBlock == Blocks.AIR) {
            return true;
        }
        if (findStationInteractionTarget(stationBlock, "recursive_station_use").isPresent()) {
            return true;
        }
        if (!goalChecker.hasItem(aiPlayer, stationItem, 1) && !craftItemToCount(stationItem, 1, stack)) {
            return false;
        }
        AiPlayerMod.debug("make_item", "AiPlayer '{}' has station item {} but no reachable placed station yet; waiting for station interaction step",
            aiPlayer.getAiPlayerName(), stationItem);
        return false;
    }

    private StepResult ensureCraftStationInteraction(ExecutionStep step) {
        String station = step.getStation() == null ? "inventory" : step.getStation();
        if ("craft_station".equals(step.getStep()) && "inventory".equals(station)) {
            return StepResult.failure("craft_station 缺少工作站，拒绝绕过工作站交互：" + step.getItem());
        }
        if ("inventory".equals(station)) {
            return null;
        }
        Block stationBlock = stationBlock(station);
        if (stationBlock == Blocks.AIR) {
            return null;
        }
        InteractionTarget stationTarget = activeInteractionTarget != null
            && activeInteractionTarget.actionType() == InteractionActionType.USE_BLOCK
            && aiPlayer.level().getBlockState(activeInteractionTarget.targetBlock()).getBlock() == stationBlock
            ? activeInteractionTarget
            : findStationInteractionTarget(stationBlock, "use_" + station).orElse(null);
        if (stationTarget == null) {
            if (!goalChecker.hasItem(aiPlayer, stationItemId(station), 1)) {
                return StepResult.failure("缺少" + stationName(station) + "，无法处理 " + step.getItem());
            }
            StepResult placeResult = placeStationForUse(stationItemId(station), stationBlock, station);
            if (placeResult != null) {
                return placeResult;
            }
            stationTarget = findStationInteractionTarget(stationBlock, "use_" + station).orElse(null);
            if (stationTarget == null) {
                return StepResult.failure("无法找到可使用的" + stationName(station));
            }
        }
        activeInteractionTarget = stationTarget;
        StepResult moveResult = moveToInteractionTarget(stationTarget, step, stationName(station));
        if (moveResult != null) {
            return moveResult;
        }
        StepResult validationResult = validateStationUseBeforeCraft(stationTarget, stationBlock, station);
        if (validationResult != null) {
            return validationResult;
        }
        aiPlayer.lookAtWorkTarget(stationTarget.targetBlock());
        return null;
    }

    private StepResult placeStationForUse(String stationItem, Block stationBlock, String station) {
        Optional<InteractionTarget> target = findStationPlacementTarget(station);
        if (target.isEmpty()) {
            AiPlayerMod.debug("make_item", "AiPlayer '{}' has no valid placement position for station {}", aiPlayer.getAiPlayerName(), stationItem);
            return StepResult.failure("找不到可放置" + stationName(station) + "的位置");
        }
        InteractionTarget placeTarget = target.get();
        activeInteractionTarget = placeTarget;
        StepResult moveResult = moveToInteractionTarget(placeTarget, activeStep, stationName(station) + "放置点");
        if (moveResult != null) {
            return moveResult;
        }
        if (!validateStationPlacementTarget(placeTarget, stationBlock, stationItem, station)) {
            return StepResult.running(stationName(station) + "放置点失效，正在重新寻找");
        }
        if (!SurvivalUtils.placeBlock(aiPlayer, placeTarget.targetBlock(), stationBlock)) {
            rejectInteractionTarget(placeTarget, "place_failed");
            return StepResult.running(stationName(station) + "放置失败，正在重新寻找位置");
        }
        activeStationPos = placeTarget.targetBlock().immutable();
        activeInteractionTarget = null;
        craftTicks = 0;
        AiPlayerMod.info("make_item", "[taskId={}] AiPlayer '{}' placed station for crafting: station={}, pos={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            stationName(station),
            activeStationPos.toShortString());
        return StepResult.running("已放置" + stationName(station) + "，正在靠近使用");
    }

    private boolean validateStationPlacementTarget(InteractionTarget placeTarget, Block stationBlock, String stationItem, String station) {
        BlockPos target = placeTarget.targetBlock();
        if (!aiPlayer.level().getBlockState(target).isAir()) {
            rejectInteractionTarget(placeTarget, "target_not_empty:" + blockId(aiPlayer.level().getBlockState(target).getBlock()));
            return false;
        }
        if (!goalChecker.hasItem(aiPlayer, stationItem, 1)) {
            rejectInteractionTarget(placeTarget, "missing_station_item:" + stationItem);
            return false;
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(target));
        if (distanceSq > placeTarget.reachRange() * placeTarget.reachRange()) {
            rejectInteractionTarget(placeTarget, "out_of_reach:" + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq)));
            return false;
        }
        Block support = aiPlayer.level().getBlockState(target.below()).getBlock();
        if (support == Blocks.AIR || support == Blocks.WATER || support == Blocks.LAVA || support == Blocks.BEDROCK) {
            rejectInteractionTarget(placeTarget, "bad_support:" + blockId(support));
            return false;
        }
        return true;
    }

    private StepResult validateStationUseBeforeCraft(InteractionTarget stationTarget, Block stationBlock, String station) {
        Block block = aiPlayer.level().getBlockState(stationTarget.targetBlock()).getBlock();
        if (block != stationBlock) {
            rejectInteractionTarget(stationTarget, "station_changed:" + blockId(block));
            return StepResult.running(stationName(station) + "已变化，正在重新寻找");
        }
        if (stationTarget.hasStandPos() && !aiPlayer.blockPosition().equals(stationTarget.standPos())) {
            rejectInteractionTarget(stationTarget, "stand_not_reached");
            return StepResult.running("尚未到达" + stationName(station) + "工作站位，正在重新寻找");
        }
        double distanceSq = aiPlayer.position().distanceToSqr(Vec3.atCenterOf(stationTarget.targetBlock()));
        if (distanceSq > stationTarget.reachRange() * stationTarget.reachRange()) {
            rejectInteractionTarget(stationTarget, "out_of_reach:" + String.format(Locale.ROOT, "%.2f", Math.sqrt(distanceSq)));
            return StepResult.running(stationName(station) + "超出交互距离，正在重新寻找");
        }
        return null;
    }

    private Optional<InteractionTarget> findStationInteractionTarget(Block stationBlock, String reason) {
        if (activeStationPos != null && aiPlayer.level().getBlockState(activeStationPos).getBlock() == stationBlock) {
            Optional<InteractionTarget> target = stationInteractionTarget(activeStationPos, reason);
            if (target.isPresent() && interactionFailures.rejectionFor(target.get(), ticks).isEmpty()) {
                return target;
            }
        }
        BlockPos center = aiPlayer.blockPosition();
        return BlockPos.betweenClosedStream(center.offset(-8, -4, -8), center.offset(8, 4, 8))
            .map(BlockPos::immutable)
            .filter(aiPlayer.level()::hasChunkAt)
            .filter(pos -> aiPlayer.level().getBlockState(pos).getBlock() == stationBlock)
            .map(pos -> stationInteractionTarget(pos, reason))
            .flatMap(Optional::stream)
            .filter(target -> interactionFailures.rejectionFor(target, ticks).isEmpty())
            .min(Comparator.comparingDouble(target -> target.targetBlock().distSqr(center)));
    }

    private Optional<InteractionTarget> stationInteractionTarget(BlockPos stationPos, String reason) {
        if (stationPos == null) {
            return Optional.empty();
        }
        if (canWorkBlockDirectly(stationPos, 4.5D)) {
            return Optional.of(new InteractionTarget(
                stationPos.immutable(),
                null,
                4.5D,
                "hand",
                InteractionActionType.USE_BLOCK,
                reason + "_direct"
            ));
        }
        Optional<BlockPos> stand = reachableWorkStand(stationPos);
        return stand.map(pos -> new InteractionTarget(
            stationPos.immutable(),
            pos,
            4.5D,
            "hand",
            InteractionActionType.USE_BLOCK,
            reason
        ));
    }

    private Optional<InteractionTarget> findStationPlacementTarget(String station) {
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
            .map(pos -> new InteractionTarget(
                    pos,
                    null,
                    4.5D,
                    "hand",
                    InteractionActionType.PLACE_BLOCK,
                    "place_" + station
                )
            )
            .filter(target -> interactionFailures.rejectionFor(target, ticks).isEmpty())
            .min(Comparator.comparingDouble(target -> target.targetBlock().distSqr(center)));
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

    private Block stationBlock(String station) {
        return blockFromItemId(stationItemId(station));
    }

    private String stationItemId(String station) {
        return switch (station == null ? "inventory" : station) {
            case "crafting_table" -> "minecraft:crafting_table";
            case "furnace" -> "minecraft:furnace";
            case "blast_furnace" -> "minecraft:blast_furnace";
            case "smoker" -> "minecraft:smoker";
            case "campfire" -> "minecraft:campfire";
            case "stonecutter" -> "minecraft:stonecutter";
            case "smithing_table" -> "minecraft:smithing_table";
            default -> "minecraft:air";
        };
    }

    private boolean ensureTool(String itemId) {
        boolean available = hasToolItem(itemId) || craftItemToCount(itemId, 1, new HashSet<>());
        if (available) {
            equipRequiredTool(itemId);
        }
        return available;
    }

    private boolean ensureReplacementTool(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return true;
        }
        int targetCount = MiningToolGate.replacementTargetCount(goalChecker.countItem(aiPlayer, itemId));
        boolean available = craftItemToCount(itemId, targetCount, new HashSet<>());
        if (available) {
            equipRequiredTool(itemId);
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' prepared replacement tool: item={}, targetCount={}, backpack={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                itemId,
                targetCount,
                new TreeMap<>(aiPlayer.getInventorySnapshot()));
        }
        return available;
    }

    private void equipRequiredTool(String itemId) {
        if (itemId == null) {
            return;
        }
        if (itemId.endsWith("_pickaxe")) {
            ItemStack pickaxe = aiPlayer.getBestToolStackFor("pickaxe", MIN_MINING_TOOL_DURABILITY);
            aiPlayer.setItemInHand(InteractionHand.MAIN_HAND, pickaxe.isEmpty() ? aiPlayer.getBestToolStackFor("pickaxe") : pickaxe);
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
        SurvivalInteractionValidator.ValidationResult reach = SurvivalInteractionValidator.canBreakBlock(aiPlayer, pos);
        if (!reach.valid()) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejected direct block break: pos={}, reason={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                pos.toShortString(),
                reach.reason());
            return false;
        }
        Block block = aiPlayer.level().getBlockState(pos).getBlock();
        if (!SurvivalUtils.canHarvestBlock(aiPlayer, block)) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejected direct block break: pos={}, block={}, reason=wrong_tool_tier:{}",
                taskId,
                aiPlayer.getAiPlayerName(),
                pos.toShortString(),
                blockId(block),
                SurvivalUtils.requiredToolForDrop(block));
            return false;
        }
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
        BlockPos plannedTarget = currentStairDigTarget();
        if (plannedTarget != null) {
            return plannedTarget;
        }
        if (stairStepPhase == StairStepPhase.MOVE) {
            return null;
        }
        clearStairStepPlan();
        BlockPos center = aiPlayer.blockPosition();
        List<String> rejectedDirections = new ArrayList<>();
        if (stairDescentController == null) {
            stairDescentController = StairDescentController.start(
                center,
                miningRoutePlan == null ? center.getY() - 1 : miningRoutePlan.targetY(),
                descentDirection == null ? horizontalDirectionFromYaw() : descentDirection
            );
        }
        for (Direction direction : orderedDescentDirections()) {
            StairDescentController.StairStepTargets stairTargets = stairDescentController.preview(center, direction);
            BlockPos nextStand = stairTargets.stand();
            BlockPos entryHeadTarget = stairTargets.entryHead();
            BlockPos horizontalTarget = stairTargets.horizontal();
            BlockPos verticalTarget = stairTargets.vertical();
            String futureInvalidReason = futureStairStandInvalidReason(nextStand, horizontalTarget, verticalTarget);
            if (futureInvalidReason != null) {
                if (futureInvalidReason.startsWith("future_stand_invalid:support")
                    && queueMiningSupportPlacement(nextStand, horizontalTarget, verticalTarget, direction, futureInvalidReason)) {
                    return null;
                }
                recordRejectedStairDirection(direction, nextStand, futureInvalidReason);
                rejectedDirections.add(stairDirectionRejectionSummary(direction, nextStand, futureInvalidReason));
                continue;
            }
            MiningTunnelStepper.Plan tunnelStep = miningTunnelStep(center, nextStand);
            if (queueSupportFromTunnelStep(tunnelStep, center, nextStand, "stair_descent")) {
                return null;
            }
            if (tunnelStep.blocked()) {
                recordRejectedStairDirection(direction, nextStand, tunnelStep.reason());
                rejectedDirections.add(stairDirectionRejectionSummary(direction, nextStand, tunnelStep.reason()));
                continue;
            }
            StairStepPhase firstPhase = firstStairStepPhase(stairTargets);
            if (firstPhase != null) {
                descentDirection = direction;
                stairDescentController.acceptDirection(direction);
                stairStepDirection = direction;
                stairEntryHeadTarget = entryHeadTarget.immutable();
                stairHorizontalTarget = horizontalTarget.immutable();
                stairVerticalTarget = verticalTarget.immutable();
                stairStandTarget = nextStand.immutable();
                stairStepPhase = firstPhase;
                AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' selected stair mining step: phase={}, current={}, targets={}, passage={}",
                    taskId,
                    aiPlayer.getAiPlayerName(),
                    firstPhase,
                    center.toShortString(),
                    stairTargets.toLogText(),
                    tunnelStep.toLogText());
                if (firstPhase == StairStepPhase.MOVE) {
                    return null;
                }
                return currentStairDigTarget();
            }
        }
        if (!rejectedDirections.isEmpty()) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' stair direction rejection summary: current={}, rejected={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                center.toShortString(),
                String.join("; ", rejectedDirections));
        }
        return null;
    }

    private String stairDirectionRejectionSummary(Direction direction, BlockPos stand, String reason) {
        BlockPos entryHead = stand == null ? null : stand.above(2);
        BlockPos horizontal = stand == null ? null : stand.above();
        BlockPos vertical = stand;
        return "direction=" + (direction == null ? "unknown" : direction.getName())
            + ", stand=" + (stand == null ? "unknown" : stand.toShortString())
            + ", entryHead=" + (entryHead == null ? "unknown" : entryHead.toShortString() + "/" + blockId(aiPlayer.level().getBlockState(entryHead).getBlock()))
            + ", horizontal=" + (horizontal == null ? "unknown" : horizontal.toShortString() + "/" + blockId(aiPlayer.level().getBlockState(horizontal).getBlock()))
            + ", vertical=" + (vertical == null ? "unknown" : vertical.toShortString() + "/" + blockId(aiPlayer.level().getBlockState(vertical).getBlock()))
            + ", reason=" + reason;
    }

    private void recordRejectedStairDirection(Direction direction, BlockPos stand, String reason) {
        if (ticks % 40 != 0) {
            return;
        }
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' rejected stair direction: direction={}, stand={}, entryHead={}, horizontal={}, vertical={}, reason={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            direction == null ? "unknown" : direction.getName(),
            stand == null ? "unknown" : stand.toShortString(),
            stand == null ? "unknown" : stand.above(2).toShortString() + "/" + blockId(aiPlayer.level().getBlockState(stand.above(2)).getBlock()),
            stand == null ? "unknown" : stand.above().toShortString() + "/" + blockId(aiPlayer.level().getBlockState(stand.above()).getBlock()),
            stand == null ? "unknown" : stand.toShortString() + "/" + blockId(aiPlayer.level().getBlockState(stand).getBlock()),
            reason);
    }

    private BlockPos currentStairDigTarget() {
        if (stairStepPhase == null || stairStandTarget == null || stairStepDirection == null) {
            return null;
        }
        if (!isCurrentStairPlanAdjacent()) {
            clearStairStepPlan();
            return null;
        }
        if (stairStepPhase == StairStepPhase.CLEAR_ENTRY_HEAD) {
            if (stairEntryHeadTarget == null) {
                clearStairStepPlan();
                return null;
            }
            if (isAirBlock(stairEntryHeadTarget)) {
                refreshStairStepPhaseFromPassage();
                return currentStairDigTarget();
            }
            BlockPos target = breakableStairBlock(stairEntryHeadTarget);
            if (target != null) {
                return target;
            }
            clearStairStepPlan();
            return null;
        }
        if (stairStepPhase == StairStepPhase.CLEAR_HORIZONTAL) {
            if (stairHorizontalTarget == null) {
                clearStairStepPlan();
                return null;
            }
            if (isAirBlock(stairHorizontalTarget)) {
                refreshStairStepPhaseFromPassage();
                return currentStairDigTarget();
            }
            BlockPos target = breakableStairBlock(stairHorizontalTarget);
            if (target != null) {
                return target;
            }
            clearStairStepPlan();
            return null;
        }
        if (stairStepPhase == StairStepPhase.DIG_DOWN) {
            if (stairVerticalTarget == null) {
                clearStairStepPlan();
                return null;
            }
            if (isAirBlock(stairVerticalTarget)) {
                refreshStairStepPhaseFromPassage();
                return currentStairDigTarget();
            }
            BlockPos target = breakableStairBlock(stairVerticalTarget);
            if (target != null) {
                return target;
            }
            clearStairStepPlan();
            return null;
        }
        if (stairStepPhase == StairStepPhase.MOVE && !canStandAt(stairStandTarget)) {
            rejectCurrentStairStep("stand_no_longer_valid");
        }
        return null;
    }

    private boolean isActiveStairStand(BlockPos pos) {
        return pos != null && stairStandTarget != null && pos.equals(stairStandTarget);
    }

    private void rejectCurrentStairStep(String reason) {
        String safeReason = reason == null || reason.isBlank() ? "stair_step_rejected" : reason;
        Direction direction = stairStepDirection;
        BlockPos stand = stairStandTarget;
        if (stand != null) {
            rejectTarget(stand, safeReason);
        }
        recordStairDescentFailure(safeReason);
        recordRejectedStairDirection(direction, stand, safeReason);
        clearStairStepPlan();
    }

    private boolean isCurrentStairPlanAdjacent() {
        if (stairStepDirection == null || stairStandTarget == null) {
            return false;
        }
        BlockPos expectedStand = aiPlayer.blockPosition().relative(stairStepDirection).below();
        return expectedStand.equals(stairStandTarget);
    }

    private StairStepPhase firstStairStepPhase(StairDescentController.StairStepTargets targets) {
        if (targets == null) {
            return null;
        }
        StairDescentController.ClearancePhase phase = StairDescentController.clearancePhase(
            isAirBlock(targets.entryHead()),
            isAirBlock(targets.horizontal()),
            isAirBlock(targets.vertical())
        );
        return switch (phase) {
            case CLEAR_ENTRY_HEAD -> breakableStairBlock(targets.entryHead()) == null ? null : StairStepPhase.CLEAR_ENTRY_HEAD;
            case CLEAR_HORIZONTAL -> breakableStairBlock(targets.horizontal()) == null ? null : StairStepPhase.CLEAR_HORIZONTAL;
            case DIG_DOWN -> breakableStairBlock(targets.vertical()) == null ? null : StairStepPhase.DIG_DOWN;
            case MOVE -> StairStepPhase.MOVE;
        };
    }

    private void advanceStairStepAfterBreak(BlockPos digTarget) {
        if (digTarget == null || stairStepPhase == null) {
            return;
        }
        if (stairStepPhase == StairStepPhase.CLEAR_ENTRY_HEAD && digTarget.equals(stairEntryHeadTarget)) {
            refreshStairStepPhaseFromPassage();
        } else if (stairStepPhase == StairStepPhase.CLEAR_HORIZONTAL && digTarget.equals(stairHorizontalTarget)) {
            refreshStairStepPhaseFromPassage();
        } else if (stairStepPhase == StairStepPhase.DIG_DOWN && digTarget.equals(stairVerticalTarget)) {
            refreshStairStepPhaseFromPassage();
        }
    }

    private void refreshStairStepPhaseFromPassage() {
        if (stairStepDirection == null || stairEntryHeadTarget == null || stairHorizontalTarget == null
            || stairVerticalTarget == null || stairStandTarget == null) {
            clearStairStepPlan();
            return;
        }
        StairDescentController.StairStepTargets targets = new StairDescentController.StairStepTargets(
            stairStepDirection,
            stairEntryHeadTarget,
            stairHorizontalTarget,
            stairVerticalTarget,
            stairStandTarget
        );
        StairStepPhase refreshedPhase = firstStairStepPhase(targets);
        if (refreshedPhase == null) {
            rejectCurrentStairStep("stair_clearance_blocked");
            return;
        }
        stairStepPhase = refreshedPhase;
    }

    private void clearStairStepPlan() {
        stairStepPhase = null;
        stairStepDirection = null;
        stairEntryHeadTarget = null;
        stairHorizontalTarget = null;
        stairVerticalTarget = null;
        stairStandTarget = null;
    }

    private StepResult placePendingMiningSupport() {
        if (pendingSupportPlacement == null || pendingSupportBlock == null) {
            return null;
        }
        BlockPos pos = pendingSupportPlacement;
        Block block = pendingSupportBlock;
        if (!aiPlayer.level().hasChunkAt(pos)) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' support placement skipped: pos={}, reason=chunk_unloaded",
                taskId, aiPlayer.getAiPlayerName(), pos.toShortString());
            clearPendingSupportPlacement();
            return StepResult.running("支撑方块所在区块未加载，正在重新选择路线");
        }
        if (!isAirBlock(pos)) {
            clearPendingSupportPlacement();
            return StepResult.running("未来站位支撑已存在，继续下挖");
        }
        String danger = blockingMiningDanger(pos, "support_place");
        boolean reachable = canWorkBlockDirectly(pos, 4.5D);
        String futureInvalid = futureStairStandInvalidReasonWithSupport(
            pendingSupportStand,
            pendingSupportHorizontalTarget,
            pendingSupportVerticalTarget
        );
        if (danger != null || futureInvalid != null || !reachable || !hasSupportPlacementFace(pos)) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' support placement rejected: pos={}, block={}, danger={}, futureInvalid={}, reachable={}, hasFace={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                pos.toShortString(),
                blockId(block),
                danger == null ? "none" : danger,
                futureInvalid == null ? "none" : futureInvalid,
                reachable,
                hasSupportPlacementFace(pos));
            clearPendingSupportPlacement();
            return StepResult.running("未来站位无法安全补支撑，正在改走其他路线");
        }
        if (!SurvivalUtils.placeBlock(aiPlayer, pos, block)) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' support placement failed: pos={}, block={}, backpack={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                pos.toShortString(),
                blockId(block),
                new TreeMap<>(aiPlayer.getInventorySnapshot()));
            clearPendingSupportPlacement();
            return StepResult.running("缺少可用支撑方块，正在改走水平路线");
        }
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' placed mining support: pos={}, block={}, backpack={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            pos.toShortString(),
            blockId(block),
            new TreeMap<>(aiPlayer.getInventorySnapshot()));
        clearPendingSupportPlacement();
        markMiningMode("support_place");
        return StepResult.running("已补未来站位支撑，继续阶梯下挖");
    }

    private boolean queueMiningSupportPlacement(
        BlockPos stand,
        BlockPos horizontalTarget,
        BlockPos verticalTarget,
        Direction direction,
        String reason
    ) {
        BlockPos supportPos = stand == null ? null : stand.below();
        Block supportBlock = availableMiningSupportBlock();
        if (supportBlock == Blocks.AIR) {
            AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' cannot place missing stair support: pos={}, direction={}, reason={}, backpack={}",
                taskId,
                aiPlayer.getAiPlayerName(),
                supportPos == null ? "unknown" : supportPos.toShortString(),
                direction == null ? "unknown" : direction.getName(),
                reason,
                new TreeMap<>(aiPlayer.getInventorySnapshot()));
            return false;
        }
        if (supportPos == null
            || !isAirBlock(supportPos)
            || blockingMiningDanger(supportPos, "queue_support") != null
            || !canWorkBlockDirectly(supportPos, 4.5D)
            || !hasSupportPlacementFace(supportPos)
            || futureStairStandInvalidReasonWithSupport(stand, horizontalTarget, verticalTarget, supportBlock) != null) {
            return false;
        }
        pendingSupportPlacement = supportPos.immutable();
        pendingSupportStand = stand.immutable();
        pendingSupportHorizontalTarget = horizontalTarget.immutable();
        pendingSupportVerticalTarget = verticalTarget.immutable();
        pendingSupportBlock = supportBlock;
        AiPlayerMod.info("mining", "[taskId={}] AiPlayer '{}' queued mining support placement: pos={}, block={}, direction={}, reason={}",
            taskId,
            aiPlayer.getAiPlayerName(),
            pendingSupportPlacement.toShortString(),
            blockId(pendingSupportBlock),
            direction == null ? "unknown" : direction.getName(),
            reason);
        return true;
    }

    private Block availableMiningSupportBlock() {
        String supportBlockId = MiningSupportPolicy.chooseSupportBlock(aiPlayer.getInventorySnapshot());
        return supportBlockId == null ? Blocks.AIR : blockFromId(supportBlockId);
    }

    private boolean hasSupportPlacementFace(BlockPos pos) {
        return SurvivalInteractionValidator.canPlaceBlock(aiPlayer, pos).valid();
    }

    private String futureStairStandInvalidReasonWithSupport(BlockPos stand, BlockPos horizontalTarget, BlockPos verticalTarget) {
        return futureStairStandInvalidReasonWithSupport(
            stand,
            horizontalTarget,
            verticalTarget,
            pendingSupportBlock == null ? availableMiningSupportBlock() : pendingSupportBlock
        );
    }

    private String futureStairStandInvalidReasonWithSupport(
        BlockPos stand,
        BlockPos horizontalTarget,
        BlockPos verticalTarget,
        Block supportBlock
    ) {
        if (stand == null || horizontalTarget == null || verticalTarget == null || stand.getY() <= aiPlayer.level().getMinY()) {
            return "future_stand_invalid:out_of_world";
        }
        Level level = aiPlayer.level();
        Block horizontalBlock = level.getBlockState(horizontalTarget).getBlock();
        Block verticalBlock = level.getBlockState(verticalTarget).getBlock();
        boolean horizontalAir = level.getBlockState(horizontalTarget).isAir();
        boolean verticalAir = level.getBlockState(verticalTarget).isAir();
        boolean horizontalBreakable = horizontalAir || breakableStairBlock(horizontalTarget) != null;
        boolean verticalBreakable = verticalAir || breakableStairBlock(verticalTarget) != null;
        String horizontalDanger = miningPassageDanger(horizontalTarget, horizontalAir, "future_stair_horizontal");
        String verticalDanger = miningPassageDanger(verticalTarget, verticalAir, "future_stair_vertical");
        StairFutureStandValidator.Result result = StairFutureStandValidator.validate(new StairFutureStandValidator.Input(
            blockId(horizontalBlock),
            horizontalAir,
            horizontalBreakable,
            blockId(verticalBlock),
            verticalAir,
            verticalBreakable,
            blockId(supportBlock),
            true,
            horizontalDanger,
            verticalDanger
        ));
        return result.valid() ? null : result.reason();
    }

    private void clearPendingSupportPlacement() {
        pendingSupportPlacement = null;
        pendingSupportStand = null;
        pendingSupportHorizontalTarget = null;
        pendingSupportVerticalTarget = null;
        pendingSupportBlock = null;
    }

    private String futureStairStandInvalidReason(BlockPos stand, BlockPos horizontalTarget, BlockPos verticalTarget) {
        if (stand == null || stand.getY() <= aiPlayer.level().getMinY()) {
            return "future_stand_invalid:out_of_world";
        }
        Level level = aiPlayer.level();
        Block horizontalBlock = level.getBlockState(horizontalTarget).getBlock();
        Block verticalBlock = level.getBlockState(verticalTarget).getBlock();
        Block supportBlock = level.getBlockState(stand.below()).getBlock();
        boolean horizontalAir = level.getBlockState(horizontalTarget).isAir();
        boolean verticalAir = level.getBlockState(verticalTarget).isAir();
        boolean horizontalBreakable = horizontalAir || breakableStairBlock(horizontalTarget) != null;
        boolean verticalBreakable = verticalAir || breakableStairBlock(verticalTarget) != null;
        String horizontalDanger = miningPassageDanger(horizontalTarget, horizontalAir, "future_stair_horizontal");
        String verticalDanger = miningPassageDanger(verticalTarget, verticalAir, "future_stair_vertical");
        StairFutureStandValidator.Result result = StairFutureStandValidator.validate(new StairFutureStandValidator.Input(
            blockId(horizontalBlock),
            horizontalAir,
            horizontalBreakable,
            blockId(verticalBlock),
            verticalAir,
            verticalBreakable,
            blockId(supportBlock),
            isSafeStairSupport(stand),
            horizontalDanger,
            verticalDanger
        ));
        if (result.valid()) {
            return null;
        }
        return result.reason()
            + ", horizontalTarget=" + horizontalTarget.toShortString()
            + ", verticalTarget=" + verticalTarget.toShortString()
            + ", standTarget=" + stand.toShortString()
            + ", support=" + stand.below().toShortString() + "/" + blockId(supportBlock);
    }

    private boolean isSafeStairSupport(BlockPos standPos) {
        if (standPos == null || standPos.getY() <= aiPlayer.level().getMinY()) {
            return false;
        }
        Block support = aiPlayer.level().getBlockState(standPos.below()).getBlock();
        return support != Blocks.AIR
            && support != Blocks.BEDROCK
            && support != Blocks.WATER
            && support != Blocks.LAVA
            && support != Blocks.GRAVEL
            && support != Blocks.SAND
            && support != Blocks.RED_SAND;
    }

    private boolean isAirBlock(BlockPos pos) {
        return pos != null && aiPlayer.level().getBlockState(pos).isAir();
    }

    private boolean isTreeBlock(Block block) {
        return SurvivalUtils.isLog(block) || SurvivalUtils.isLeaves(block);
    }

    private boolean isUnsafeStairStand(BlockPos standPos) {
        if (standPos == null || standPos.getY() <= aiPlayer.level().getMinY()) {
            return true;
        }
        Block support = aiPlayer.level().getBlockState(standPos.below()).getBlock();
        return !isSafeMiningRouteSupport(standPos)
            || support == Blocks.GRAVEL
            || support == Blocks.SAND
            || support == Blocks.RED_SAND;
    }

    private BlockPos breakableStairBlock(BlockPos pos) {
        return breakableMiningPassageBlock(pos);
    }

    private BlockPos breakableMiningPassageBlock(BlockPos pos) {
        if (pos == null || pos.getY() <= aiPlayer.level().getMinY() || rejectedTargets.contains(pos)) {
            return null;
        }
        Block block = aiPlayer.level().getBlockState(pos).getBlock();
        if (aiPlayer.level().getBlockState(pos).isAir()
            || block == Blocks.BEDROCK || block == Blocks.WATER || block == Blocks.LAVA
            || block == Blocks.GRAVEL || block == Blocks.SAND || block == Blocks.RED_SAND) {
            return null;
        }
        if (!SurvivalUtils.canHarvestBlock(aiPlayer, block)) {
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

    private Direction[] horizontalDirections() {
        return new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
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

    private boolean canStandAt(BlockPos pos) {
        Level level = aiPlayer.level();
        return level.getBlockState(pos).isAir()
            && level.getBlockState(pos.above()).isAir()
            && isSafeMiningRouteSupport(pos);
    }

    private boolean isSafeMiningMemoryStand(BlockPos pos) {
        if (pos == null || pos.getY() <= aiPlayer.level().getMinY()) {
            return false;
        }
        if (!canStandAt(pos)) {
            return false;
        }
        Level level = aiPlayer.level();
        Block support = level.getBlockState(pos.below()).getBlock();
        if (support == Blocks.GRAVEL || support == Blocks.SAND || support == Blocks.RED_SAND) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            Block feetNeighbor = level.getBlockState(pos.relative(direction)).getBlock();
            Block headNeighbor = level.getBlockState(pos.above().relative(direction)).getBlock();
            if (feetNeighbor == Blocks.WATER || feetNeighbor == Blocks.LAVA
                || headNeighbor == Blocks.WATER || headNeighbor == Blocks.LAVA) {
                return false;
            }
        }
        return true;
    }

    private boolean isSafeMiningRouteSupport(BlockPos standPos) {
        if (standPos == null || standPos.getY() <= aiPlayer.level().getMinY()) {
            return false;
        }
        Level level = aiPlayer.level();
        Block support = level.getBlockState(standPos.below()).getBlock();
        return !level.getBlockState(standPos.below()).isAir()
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

    private int miningTargetItemCount() {
        return activeStep == null ? 0 : goalChecker.countItem(aiPlayer, activeStep.getItem());
    }

    private MiningDropProgress recordMiningDropProgress(
        String context,
        BlockPos pos,
        SurvivalUtils.BreakResult breakResult,
        int beforeTargetCount
    ) {
        String targetItem = activeStep == null ? "minecraft:air" : activeStep.getItem();
        int afterTargetCount = miningTargetItemCount();
        int targetTotal = startCount + (activeStep == null ? 0 : activeStep.getCount());
        MiningDropProgress progress = MiningDropProgress.analyze(
            targetItem,
            beforeTargetCount,
            afterTargetCount,
            targetTotal,
            itemKey(breakResult == null ? Items.AIR : breakResult.drop()),
            breakResult == null ? 0 : breakResult.dropCount(),
            breakResult == null ? 0 : breakResult.insertedCount(),
            breakResult == null ? 0 : breakResult.uncollectedCount(),
            breakResult != null && breakResult.success(),
            breakResult == null ? "missing_break_result" : breakResult.reason()
        );
        AiPlayerMod.info("mining", "[taskId={}] mining drop progress: ai={}, context={}, pos={}, {}",
            taskId,
            aiPlayer.getAiPlayerName(),
            context == null || context.isBlank() ? "unknown" : context,
            pos == null ? "none" : pos.toShortString(),
            progress.toLogText());
        if (breakResult != null && breakResult.success()) {
            trackItemProgress(afterTargetCount);
        }
        return progress;
    }

    private String blockId(Block block) {
        if (block == null) {
            return "minecraft:air";
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? "minecraft:air" : key.toString();
    }

    private void resetMovement() {
        moveTicksWithoutProgress = 0;
        closestTargetDistanceSq = Double.MAX_VALUE;
    }

    private enum StairStepPhase {
        CLEAR_ENTRY_HEAD,
        CLEAR_HORIZONTAL,
        DIG_DOWN,
        MOVE
    }

    private record TreeSearchResult(
        int horizontalRadius,
        int verticalRadius,
        int candidates,
        int reachable,
        int rejected,
        int pathChecked,
        BlockPos selectedPos,
        BlockPos selectedStandPos,
        InteractionTarget selectedInteractionTarget,
        Map<String, Integer> rejectionReasons
    ) {
        private Optional<BlockPos> selected() {
            return Optional.ofNullable(selectedPos);
        }

        private Optional<BlockPos> selectedStand() {
            return Optional.ofNullable(selectedStandPos);
        }

        private Optional<InteractionTarget> selectedInteraction() {
            return Optional.ofNullable(selectedInteractionTarget);
        }

        private String toSummaryText() {
            return "扫描半径=" + horizontalRadius
                + "，候选原木=" + candidates
                + "，路径检查=" + pathChecked
                + "，可达原木=" + reachable
                + "，已拒绝=" + rejected
                + "，拒绝原因=" + rejectionReasons
                + "，选择=" + (selectedPos == null ? "none" : selectedPos.toShortString())
                + "，站位=" + (selectedStandPos == null ? "none" : selectedStandPos.toShortString())
                + "，交互目标=" + (selectedInteractionTarget == null ? "none" : selectedInteractionTarget.summary());
        }
    }

    private record TreeWorkTarget(
        BlockPos logPos,
        BlockPos standPos,
        double penalty,
        String reason,
        InteractionTarget interactionTarget
    ) {
        private double score(BlockPos current) {
            return TreeTargetPolicy.workTargetScore(current, logPos, standPos) + penalty;
        }
    }

    private record StoneWorkTarget(
        BlockPos stonePos,
        BlockPos standPos,
        double penalty,
        InteractionTarget interactionTarget
    ) {
        private double score(BlockPos current) {
            return standPos.distSqr(current) + penalty;
        }
    }

    private record InteractionFailureSnapshot(
        InteractionTarget target,
        String reason,
        BlockPos actualPos,
        double distance,
        int tick
    ) {
        private String summary() {
            return target.summary()
                + ", actualPos=" + actualPos.toShortString()
                + ", distance=" + String.format(Locale.ROOT, "%.2f", distance)
                + ", reason=" + reason
                + ", tick=" + tick;
        }
    }

    private static final class TreeScanState {
        private final BlockPos center;
        private final int horizontalRadius;
        private final int verticalRadius;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final List<BlockPos> pathCandidates = new ArrayList<>();
        private int x;
        private int y;
        private int z;
        private int candidates;
        private int rejected;
        private int pathCheckIndex;
        private int pathChecked;
        private int reachable;
        private final Map<String, Integer> rejectionReasons = new TreeMap<>();
        private BlockPos selected;
        private BlockPos selectedStand;
        private InteractionTarget selectedInteractionTarget;
        private double selectedScore = Double.MAX_VALUE;

        private TreeScanState(AiPlayerEntity aiPlayer, int horizontalRadius, int verticalRadius) {
            this.center = aiPlayer.blockPosition().immutable();
            this.horizontalRadius = horizontalRadius;
            this.verticalRadius = verticalRadius;
            this.minX = center.getX() - horizontalRadius;
            this.maxX = center.getX() + horizontalRadius;
            this.minY = Math.max(aiPlayer.level().getMinY(), center.getY() - verticalRadius);
            this.maxY = Math.min(aiPlayer.level().getMinY() + aiPlayer.level().getHeight() - 1, center.getY() + verticalRadius);
            this.minZ = center.getZ() - horizontalRadius;
            this.maxZ = center.getZ() + horizontalRadius;
            this.x = minX;
            this.y = minY;
            this.z = minZ;
        }

        private static TreeScanState start(AiPlayerEntity aiPlayer, int horizontalRadius, int verticalRadius) {
            return new TreeScanState(aiPlayer, horizontalRadius, verticalRadius);
        }

        private BlockPos currentPos() {
            return new BlockPos(x, y, z);
        }

        private void advance() {
            z++;
            if (z <= maxZ) {
                return;
            }
            z = minZ;
            y++;
            if (y <= maxY) {
                return;
            }
            y = minY;
            x++;
        }

        private boolean isComplete() {
            return x > maxX;
        }

        private void addPathCandidate(BlockPos pos) {
            pathCandidates.add(pos);
            pathCandidates.sort(
                Comparator.comparingDouble((BlockPos candidate) -> TreeTargetPolicy.scanCandidateScore(center, candidate))
                    .thenComparingDouble(candidate -> candidate.distSqr(center))
            );
            if (pathCandidates.size() > TREE_SCAN_PATH_CANDIDATE_LIMIT) {
                pathCandidates.remove(pathCandidates.size() - 1);
            }
        }

        private void reject(String reason) {
            rejected++;
            rejectionReasons.merge(reason == null || reason.isBlank() ? "unknown" : reason, 1, Integer::sum);
        }
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
        private InteractionTarget selectedInteractionTarget;

        private void selectDirect(BlockPos pos, double distance, int exposedAir, InteractionTarget interactionTarget) {
            if (exposedAir > directSelectionExposure
                || (exposedAir == directSelectionExposure && distance < directSelectionDistance)) {
                directSelection = pos.immutable();
                directSelectionDistance = distance;
                directSelectionExposure = exposedAir;
                selectedInteractionTarget = interactionTarget;
            }
        }

        private void selectPath(BlockPos pos, double distance, int exposedAir, InteractionTarget interactionTarget) {
            if (exposedAir > pathSelectionExposure
                || (exposedAir == pathSelectionExposure && distance < pathSelectionDistance)) {
                pathSelection = pos.immutable();
                pathSelectionDistance = distance;
                pathSelectionExposure = exposedAir;
                if (directSelection == null) {
                    selectedInteractionTarget = interactionTarget;
                }
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

    private record TunnelTarget(
        BlockPos target,
        MiningTunnelStepper.Plan step,
        boolean mineOre,
        boolean needsSupport,
        boolean blocked,
        boolean exposureClearance
    ) {
        private TunnelTarget {
            target = target == null ? null : target.immutable();
        }

        static TunnelTarget mineOre(BlockPos target) {
            return new TunnelTarget(
                target,
                new MiningTunnelStepper.Plan(MiningTunnelStepper.Action.MOVE, target, "ore_workable", null),
                true,
                false,
                false,
                false
            );
        }

        static TunnelTarget exposure(BlockPos target) {
            return new TunnelTarget(
                target,
                new MiningTunnelStepper.Plan(MiningTunnelStepper.Action.CLEAR_HEAD, target, "expose_ore_neighbor", null),
                false,
                false,
                false,
                true
            );
        }

        static TunnelTarget clearance(MiningTunnelStepper.Plan step) {
            return new TunnelTarget(step == null ? null : step.target(), safeStep(step), false, false, false, false);
        }

        static TunnelTarget support(MiningTunnelStepper.Plan step) {
            return new TunnelTarget(step == null ? null : step.target(), safeStep(step), false, true, false, false);
        }

        static TunnelTarget blocked(MiningTunnelStepper.Plan step) {
            return new TunnelTarget(step == null ? null : step.target(), safeStep(step), false, false, true, false);
        }

        String reason() {
            return step.reason();
        }

        private static MiningTunnelStepper.Plan safeStep(MiningTunnelStepper.Plan step) {
            return step == null
                ? new MiningTunnelStepper.Plan(MiningTunnelStepper.Action.BLOCKED, null, "unknown", null)
                : step;
        }
    }
}
