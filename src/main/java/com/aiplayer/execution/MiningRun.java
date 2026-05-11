package com.aiplayer.execution;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.entity.AiPlayerEntity;
import com.aiplayer.recipe.MiningResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class MiningRun {
    private final String taskId;
    private final String aiName;
    private final String target;
    private final String step;
    private final BlockPos startPos;
    private final int startY;
    private final String heightStrategy;
    private final String routeHint;
    private final TreeSet<Integer> visitedY = new TreeSet<>();
    private final TreeMap<String, Integer> rejectedReasons = new TreeMap<>();
    private final TreeMap<String, Integer> dangerReasons = new TreeMap<>();
    private final TreeMap<String, Integer> branchBlocksByDirection = new TreeMap<>();
    private final TreeSet<String> modes = new TreeSet<>();
    private final TreeSet<String> routePlans = new TreeSet<>();

    private int scans;
    private int totalCandidates;
    private int totalReachable;
    private int totalRejected;
    private int blocksDug;
    private int failedBreaks;
    private int cavesFound;
    private int progressDecisions;
    private BlockPos lastDigTarget;
    private MiningState state = MiningState.PREPARE_TOOLS;
    private boolean finished;

    private MiningRun(
        String taskId,
        AiPlayerEntity aiPlayer,
        ExecutionStep executionStep,
        MiningResource.Profile profile
    ) {
        this.taskId = taskId == null || taskId.isBlank() ? "task-unknown" : taskId;
        this.aiName = aiPlayer.getAiPlayerName();
        this.target = executionStep.describe();
        this.step = executionStep.getStep();
        this.startPos = aiPlayer.blockPosition().immutable();
        this.startY = startPos.getY();
        this.heightStrategy = profile == null ? "stone_or_local_descent" : profile.preferredYText();
        this.routeHint = profile == null ? "local_stone_search" : profile.routeHint();
        modes.add(this.step);
        visitedY.add(startY);
        AiPlayerMod.info("mining", "[taskId={}] mining run start: ai={}, target={}, startPos={}, startY={}, heightStrategy={}, routeHint={}, tool={}, backpack={}",
            this.taskId,
            aiName,
            target,
            startPos.toShortString(),
            startY,
            heightStrategy,
            routeHint,
            mainHandText(aiPlayer),
            new TreeMap<>(aiPlayer.getInventorySnapshot()));
        logState("start", aiPlayer.blockPosition());
    }

    public static MiningRun start(
        String taskId,
        AiPlayerEntity aiPlayer,
        ExecutionStep executionStep,
        MiningResource.Profile profile
    ) {
        return new MiningRun(taskId, aiPlayer, executionStep, profile);
    }

    public void markMode(String mode, BlockPos currentPos) {
        if (mode != null && !mode.isBlank()) {
            modes.add(mode);
            transitionTo(stateForMode(mode), "mode=" + mode, currentPos);
        }
        recordY(currentPos);
    }

    public void transitionTo(MiningState nextState, String reason, BlockPos currentPos) {
        if (nextState == null || nextState == state) {
            recordY(currentPos);
            return;
        }
        state = nextState;
        recordY(currentPos);
        logState(reason, currentPos);
    }

    static MiningState stateForMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MiningState.PREPARE_TOOLS;
        }
        if (mode.contains("cave")) {
            return MiningState.CAVE_SCAN;
        }
        if (mode.contains("branch")) {
            return MiningState.BRANCH_TUNNEL;
        }
        if (mode.contains("visible_ore") || mode.contains("vein_follow") || mode.contains("mine_target")) {
            return MiningState.MINING;
        }
        if (mode.contains("move") || mode.contains("waypoint")) {
            return MiningState.TRAVEL_TO_ORE;
        }
        if (mode.contains("descent") || mode.contains("prospect") || mode.contains("stone_stair") || mode.contains("ore_stair")) {
            return MiningState.DESCEND;
        }
        return MiningState.PREPARE_TOOLS;
    }

    public void recordScan(
        int tick,
        String mode,
        BlockPos center,
        int horizontalRadius,
        int verticalRadius,
        int candidates,
        int directlyWorkable,
        int reachable,
        int rejected,
        Map<String, Integer> rejectionReasons,
        BlockPos selected
    ) {
        scans++;
        totalCandidates += Math.max(0, candidates);
        totalReachable += Math.max(0, reachable);
        totalRejected += Math.max(0, rejected);
        mergeRejectedReasons(rejectionReasons);
        markMode(mode, center);
        AiPlayerMod.info("mining", "[taskId={}] mining scan: ai={}, target={}, tick={}, mode={}, center={}, radius={}x{}, candidates={}, direct={}, reachable={}, rejected={}, rejectionReasons={}, selected={}",
            taskId,
            aiName,
            target,
            tick,
            mode,
            positionText(center),
            horizontalRadius,
            verticalRadius,
            candidates,
            directlyWorkable,
            reachable,
            rejected,
            rejectionReasons == null || rejectionReasons.isEmpty() ? "{}" : new TreeMap<>(rejectionReasons),
            positionText(selected));
    }

    public void recordTargetSelected(int tick, BlockPos targetPos, Block block, String mode) {
        markMode(mode, targetPos);
        AiPlayerMod.info("mining", "[taskId={}] mining target selected: ai={}, target={}, tick={}, mode={}, pos={}, block={}",
            taskId,
            aiName,
            target,
            tick,
            mode,
            positionText(targetPos),
            blockName(block));
    }

    public void recordTargetRejected(int tick, BlockPos targetPos, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        rejectedReasons.merge(safeReason, 1, Integer::sum);
        AiPlayerMod.info("mining", "[taskId={}] mining target rejected: ai={}, target={}, tick={}, pos={}, reason={}, rejectedTotal={}",
            taskId,
            aiName,
            target,
            tick,
            positionText(targetPos),
            safeReason,
            rejectedReasons);
    }

    public void recordDigAttempt(
        int tick,
        String mode,
        BlockPos digTarget,
        Block block,
        BlockPos standPos,
        Direction direction,
        int dugBefore
    ) {
        markMode(mode, standPos);
        if (digTarget != null && digTarget.equals(lastDigTarget)) {
            return;
        }
        lastDigTarget = digTarget == null ? null : digTarget.immutable();
        AiPlayerMod.info("mining", "[taskId={}] mining dig attempt: ai={}, target={}, tick={}, mode={}, standPos={}, digTarget={}, block={}, direction={}, dugBefore={}",
            taskId,
            aiName,
            target,
            tick,
            mode,
            positionText(standPos),
            positionText(digTarget),
            blockName(block),
            direction == null ? "unknown" : direction.getName(),
            dugBefore);
    }

    public void recordDigResult(
        int tick,
        String mode,
        BlockPos digTarget,
        Block block,
        boolean success,
        int exposedAirNeighbors,
        Map<String, Integer> inventory
    ) {
        markMode(mode, digTarget);
        if (success) {
            blocksDug++;
            if (exposedAirNeighbors >= 3) {
                cavesFound++;
            }
        } else {
            failedBreaks++;
        }
        AiPlayerMod.info("mining", "[taskId={}] mining dig result: ai={}, target={}, tick={}, mode={}, pos={}, block={}, success={}, exposedAirNeighbors={}, blocksDug={}, failedBreaks={}, cavesFound={}, backpack={}",
            taskId,
            aiName,
            target,
            tick,
            mode,
            positionText(digTarget),
            blockName(block),
            success,
            exposedAirNeighbors,
            blocksDug,
            failedBreaks,
            cavesFound,
            inventory == null || inventory.isEmpty() ? "{}" : new TreeMap<>(inventory));
    }

    public void recordRoutePlan(MiningRoutePlan plan) {
        if (plan == null || !routePlans.add(plan.toLogText())) {
            return;
        }
        AiPlayerMod.info("mining", "[taskId={}] mining route plan: ai={}, target={}, {}",
            taskId,
            aiName,
            target,
            plan.toLogText());
    }

    public void recordBranchProgress(Direction direction, int y, int blocks, String reason) {
        String key = (direction == null ? "unknown" : direction.getName()) + "@Y" + y;
        branchBlocksByDirection.put(key, Math.max(branchBlocksByDirection.getOrDefault(key, 0), blocks));
        AiPlayerMod.info("mining", "[taskId={}] mining branch progress: ai={}, target={}, branch={}, blocks={}, reason={}, branches={}",
            taskId,
            aiName,
            target,
            key,
            blocks,
            reason == null || reason.isBlank() ? "unknown" : reason,
            branchBlocksByDirection);
    }

    public void recordDanger(int tick, String reason, BlockPos pos, String action) {
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        dangerReasons.merge(safeReason, 1, Integer::sum);
        AiPlayerMod.warn("mining", "[taskId={}] mining danger: ai={}, target={}, tick={}, reason={}, pos={}, action={}, dangers={}",
            taskId,
            aiName,
            target,
            tick,
            safeReason,
            positionText(pos),
            action == null || action.isBlank() ? "unknown" : action,
            dangerReasons);
    }

    public int progressScore() {
        return blocksDug * 3
            + totalReachable * 2
            + cavesFound * 4
            + scans
            - failedBreaks * 2
            - totalRejected
            - dangerReasons.values().stream().mapToInt(Integer::intValue).sum() * 2;
    }

    public void recordProgressDecision(int tick, String decision, String reason) {
        progressDecisions++;
        AiPlayerMod.info("mining", "[taskId={}] mining progress decision: ai={}, target={}, tick={}, score={}, decision={}, reason={}, decisions={}, scans={}, blocksDug={}, reachable={}, rejected={}, dangers={}",
            taskId,
            aiName,
            target,
            tick,
            progressScore(),
            decision == null || decision.isBlank() ? "continue" : decision,
            reason == null || reason.isBlank() ? "unknown" : reason,
            progressDecisions,
            scans,
            blocksDug,
            totalReachable,
            totalRejected,
            dangerReasons);
    }

    public String summary() {
        return "挖矿摘要：起点=" + startPos.toShortString()
            + "，起始Y=" + startY
            + "，当前状态=" + state.label()
            + "，高度策略=" + heightStrategy
            + "，路线=" + routeHint
            + "，模式=" + modes
            + "，路线计划=" + routePlans
            + "，分支=" + branchBlocksByDirection
            + "，访问Y=" + visitedYText()
            + "，扫描=" + scans
            + "，候选=" + totalCandidates
            + "，可达=" + totalReachable
            + "，拒绝=" + totalRejected
            + "，拒绝原因=" + rejectedReasons
            + "，危险=" + dangerReasons
            + "，评分=" + progressScore()
            + "，决策=" + progressDecisions
            + "，已挖方块=" + blocksDug
            + "，破坏失败=" + failedBreaks
            + "，疑似洞穴=" + cavesFound;
    }

    public void finish(String status, String message, AiPlayerEntity aiPlayer) {
        if (finished) {
            return;
        }
        finished = true;
        if (status != null && status.startsWith("success")) {
            transitionTo(MiningState.COMPLETED, status, aiPlayer.blockPosition());
        } else {
            transitionTo(MiningState.WAITING_FOR_PLAYER, status, aiPlayer.blockPosition());
        }
        recordY(aiPlayer.blockPosition());
        AiPlayerMod.info("mining", "[taskId={}] mining run finish: ai={}, status={}, target={}, endPos={}, endY={}, message={}, {}, backpack={}",
            taskId,
            aiName,
            status,
            target,
            aiPlayer.blockPosition().toShortString(),
            aiPlayer.blockPosition().getY(),
            message,
            summary(),
            new TreeMap<>(aiPlayer.getInventorySnapshot()));
    }

    private void logState(String reason, BlockPos currentPos) {
        AiPlayerMod.info("mining", "[taskId={}] mining state: ai={}, target={}, state={}, reason={}, pos={}, y={}, modes={}",
            taskId,
            aiName,
            target,
            state.label(),
            reason == null || reason.isBlank() ? "unknown" : reason,
            positionText(currentPos),
            currentPos == null ? "unknown" : currentPos.getY(),
            modes);
    }

    private void recordY(BlockPos pos) {
        if (pos != null) {
            visitedY.add(pos.getY());
        }
    }

    private void mergeRejectedReasons(Map<String, Integer> reasons) {
        if (reasons == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : reasons.entrySet()) {
            rejectedReasons.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    private String visitedYText() {
        if (visitedY.isEmpty()) {
            return "[]";
        }
        return visitedY.first() + ".." + visitedY.last() + " " + visitedY;
    }

    private static String mainHandText(AiPlayerEntity aiPlayer) {
        ItemStack stack = aiPlayer.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            return "minecraft:air";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String item = key == null ? stack.getItem().toString() : key.toString();
        return item + " x" + stack.getCount();
    }

    private static String blockName(Block block) {
        if (block == null) {
            return "minecraft:air";
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key == null ? block.toString() : key.toString();
    }

    private static String positionText(BlockPos pos) {
        return pos == null ? "none" : pos.toShortString();
    }
}
