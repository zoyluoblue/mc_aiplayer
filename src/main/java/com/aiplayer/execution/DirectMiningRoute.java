package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record DirectMiningRoute(
    BlockPos current,
    BlockPos orePos,
    BlockPos targetStand,
    Direction preferredDirection,
    Direction nextDirection,
    BlockPos nextStand,
    int horizontalDistance,
    int verticalDelta,
    int estimatedSteps,
    String reason
) {
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
        Direction.NORTH,
        Direction.SOUTH,
        Direction.WEST,
        Direction.EAST
    };

    public DirectMiningRoute {
        current = current == null ? BlockPos.ZERO : current.immutable();
        orePos = orePos == null ? null : orePos.immutable();
        targetStand = targetStand == null ? null : targetStand.immutable();
        preferredDirection = preferredDirection == null ? Direction.NORTH : preferredDirection;
        nextStand = nextStand == null ? null : nextStand.immutable();
        reason = reason == null || reason.isBlank() ? "unknown" : reason;
    }

    public static DirectMiningRoute create(
        BlockPos current,
        BlockPos orePos,
        BlockPos routeTarget,
        Direction preferredDirection
    ) {
        BlockPos safeCurrent = current == null ? BlockPos.ZERO : current.immutable();
        Direction preferred = preferredDirection == null ? Direction.NORTH : preferredDirection;
        if (orePos == null) {
            return new DirectMiningRoute(safeCurrent, null, null, preferred, preferred, null, 0, 0, 0, "missing_ore");
        }
        BlockPos targetStand = chooseTargetStand(safeCurrent, orePos, routeTarget, preferred);
        int dx = Math.abs(targetStand.getX() - safeCurrent.getX());
        int dz = Math.abs(targetStand.getZ() - safeCurrent.getZ());
        int verticalDelta = targetStand.getY() - safeCurrent.getY();
        int horizontalDistance = dx + dz;
        Direction nextDirection = nextDirection(safeCurrent, targetStand, preferred);
        BlockPos nextStand = nextStand(safeCurrent, targetStand, nextDirection);
        String reason = reason(safeCurrent, targetStand, horizontalDistance, verticalDelta);
        int estimatedSteps = horizontalDistance + Math.abs(verticalDelta);
        return new DirectMiningRoute(
            safeCurrent,
            orePos.immutable(),
            targetStand,
            preferred,
            nextDirection,
            nextStand,
            horizontalDistance,
            verticalDelta,
            estimatedSteps,
            reason
        );
    }

    public boolean arrived() {
        return targetStand != null && current.equals(targetStand);
    }

    public boolean targetAboveCurrentLayer() {
        return verticalDelta > 1;
    }

    public boolean needsDescent() {
        return verticalDelta < 0;
    }

    public boolean withinNearField(int maxSteps) {
        return maxSteps >= 0 && estimatedSteps <= maxSteps;
    }

    public String routeStage() {
        if (targetStand == null || nextStand == null && targetAboveCurrentLayer()) {
            return "REPROSPECT";
        }
        if (arrived()) {
            return "EXPOSE_OR_MINE";
        }
        if (needsDescent()) {
            return "DESCEND";
        }
        if (horizontalDistance > 0) {
            return "TUNNEL";
        }
        return "APPROACH";
    }

    public String currentStepText() {
        if (targetStand == null) {
            return "missing_target_stand";
        }
        if (targetAboveCurrentLayer()) {
            return "reprospect_target_above";
        }
        if (arrived()) {
            return "expose_or_mine_ore";
        }
        if (nextStand == null) {
            return "rebuild_route";
        }
        if (needsDescent()) {
            return "clear_forward_and_down_to_" + nextStand.toShortString();
        }
        return "clear_forward_to_" + nextStand.toShortString();
    }

    public String statusText() {
        return "路线阶段=" + MiningStatusText.routeStage(routeStage())
            + "，当前矿点=" + (orePos == null ? "none" : orePos.toShortString())
            + "，目标站位=" + (targetStand == null ? "none" : targetStand.toShortString())
            + "，当前步=" + MiningStatusText.routeStep(currentStepText())
            + "，下一方向=" + MiningStatusText.direction(nextDirection)
            + "，下一站位=" + (nextStand == null ? "none" : nextStand.toShortString())
            + "，剩余水平=" + horizontalDistance
            + "，垂直差=" + verticalDelta
            + "，预计步数=" + estimatedSteps;
    }

    public String toLogText() {
        return "current=" + current.toShortString()
            + ", orePos=" + (orePos == null ? "none" : orePos.toShortString())
            + ", targetStand=" + (targetStand == null ? "none" : targetStand.toShortString())
            + ", routeStage=" + routeStage()
            + ", currentStep=" + currentStepText()
            + ", nextDirection=" + nextDirection.getName()
            + ", nextStand=" + (nextStand == null ? "none" : nextStand.toShortString())
            + ", horizontalDistance=" + horizontalDistance
            + ", verticalDelta=" + verticalDelta
            + ", estimatedSteps=" + estimatedSteps
            + ", reason=" + reason;
    }

    public static List<BlockPos> candidateStands(BlockPos current, BlockPos orePos, BlockPos routeTarget, Direction preferredDirection) {
        if (orePos == null) {
            return List.of();
        }
        BlockPos safeCurrent = current == null ? BlockPos.ZERO : current.immutable();
        Direction preferred = preferredDirection == null ? Direction.NORTH : preferredDirection;
        List<BlockPos> candidates = new ArrayList<>();
        if (isHorizontalOreNeighbor(orePos, routeTarget)) {
            candidates.add(routeTarget.immutable());
        }
        if (isAboveOreNeighbor(orePos, routeTarget)) {
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                candidates.add(routeTarget.relative(direction).immutable());
            }
        }
        for (Direction direction : HORIZONTAL_DIRECTIONS) {
            candidates.add(orePos.relative(direction).immutable());
        }
        return candidates.stream()
            .distinct()
            .sorted(Comparator
                .comparingInt((BlockPos pos) -> routeTargetPriority(orePos, routeTarget, pos))
                .thenComparingInt(pos -> Math.abs(pos.getY() - safeCurrent.getY()))
                .thenComparingInt(pos -> Math.abs(pos.getX() - safeCurrent.getX()) + Math.abs(pos.getZ() - safeCurrent.getZ()))
                .thenComparingInt(pos -> directionPenalty(orePos, pos, preferred))
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ))
            .toList();
    }

    private static BlockPos chooseTargetStand(BlockPos current, BlockPos orePos, BlockPos routeTarget, Direction preferredDirection) {
        List<BlockPos> candidates = candidateStands(current, orePos, routeTarget, preferredDirection);
        return candidates.isEmpty() ? orePos.immutable() : candidates.getFirst();
    }

    private static Direction nextDirection(BlockPos current, BlockPos targetStand, Direction preferredDirection) {
        if (current.equals(targetStand)) {
            return preferredDirection;
        }
        int dx = targetStand.getX() - current.getX();
        int dz = targetStand.getZ() - current.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return preferredDirection;
    }

    private static BlockPos nextStand(BlockPos current, BlockPos targetStand, Direction nextDirection) {
        int verticalDelta = targetStand.getY() - current.getY();
        if (verticalDelta > 1) {
            return null;
        }
        if (current.equals(targetStand)) {
            return current;
        }
        BlockPos horizontal = current.relative(nextDirection);
        if (verticalDelta < 0) {
            return horizontal.below();
        }
        return horizontal;
    }

    private static String reason(BlockPos current, BlockPos targetStand, int horizontalDistance, int verticalDelta) {
        if (current.equals(targetStand)) {
            return "arrived";
        }
        if (verticalDelta > 1) {
            return "target_above_current_layer";
        }
        if (verticalDelta < 0) {
            return "direct_descent";
        }
        if (horizontalDistance > 0) {
            return "direct_horizontal";
        }
        return "direct_ready";
    }

    private static int directionPenalty(BlockPos orePos, BlockPos candidate, Direction preferredDirection) {
        Direction direction = directionFromOre(orePos, candidate);
        return direction == preferredDirection ? 0 : 1;
    }

    private static Direction directionFromOre(BlockPos orePos, BlockPos candidate) {
        int dx = candidate.getX() - orePos.getX();
        int dz = candidate.getZ() - orePos.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.NORTH;
    }

    private static int routeTargetPriority(BlockPos orePos, BlockPos routeTarget, BlockPos candidate) {
        if (routeTarget == null || candidate == null) {
            return 1;
        }
        if (candidate.equals(routeTarget)) {
            return 0;
        }
        if (isAboveOreNeighbor(orePos, routeTarget) && isHorizontalNeighbor(routeTarget, candidate)) {
            return 0;
        }
        return 1;
    }

    private static boolean isHorizontalNeighbor(BlockPos origin, BlockPos candidate) {
        if (origin == null || candidate == null || origin.getY() != candidate.getY()) {
            return false;
        }
        int dx = Math.abs(candidate.getX() - origin.getX());
        int dz = Math.abs(candidate.getZ() - origin.getZ());
        return dx + dz == 1;
    }

    private static boolean isHorizontalOreNeighbor(BlockPos orePos, BlockPos candidate) {
        return isHorizontalNeighbor(orePos, candidate);
    }

    private static boolean isVerticalOreNeighbor(BlockPos orePos, BlockPos candidate) {
        if (orePos == null || candidate == null || orePos.getX() != candidate.getX() || orePos.getZ() != candidate.getZ()) {
            return false;
        }
        return Math.abs(candidate.getY() - orePos.getY()) == 1;
    }

    private static boolean isAboveOreNeighbor(BlockPos orePos, BlockPos candidate) {
        return isVerticalOreNeighbor(orePos, candidate) && candidate.getY() > orePos.getY();
    }
}
