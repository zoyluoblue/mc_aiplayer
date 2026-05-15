package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class StairDescentController {
    private static final int RELOCATE_FAILURE_LIMIT = 4;

    public enum ClearancePhase {
        CLEAR_ENTRY_HEAD,
        CLEAR_HORIZONTAL,
        DIG_DOWN,
        MOVE
    }

    private int targetY;
    private Direction direction;
    private int successfulSteps;
    private int failures;
    private BlockPos lastSafeStand;
    private String lastFailureReason = "none";

    private StairDescentController(BlockPos start, int targetY, Direction direction) {
        this.targetY = targetY;
        this.direction = direction == null ? Direction.NORTH : direction;
        this.lastSafeStand = start == null ? BlockPos.ZERO : start.immutable();
    }

    public static StairDescentController start(BlockPos start, int targetY, Direction direction) {
        return new StairDescentController(start, targetY, direction);
    }

    public boolean shouldDescend(int currentY) {
        return currentY > targetY;
    }

    public void retarget(int newTargetY) {
        targetY = newTargetY;
        failures = 0;
        lastFailureReason = "none";
    }

    public void recordStep(BlockPos stand) {
        successfulSteps++;
        failures = 0;
        lastFailureReason = "none";
        if (stand != null) {
            lastSafeStand = stand.immutable();
        }
    }

    public Direction recordFailure(String reason) {
        failures++;
        lastFailureReason = reason == null || reason.isBlank() ? "unknown" : reason;
        direction = direction.getClockWise();
        return direction;
    }

    public void acceptDirection(Direction acceptedDirection) {
        if (acceptedDirection != null) {
            direction = acceptedDirection;
        }
    }

    public StairStepTargets nextStep(BlockPos current) {
        BlockPos start = current == null ? lastSafeStand : current;
        BlockPos stand = start.relative(direction).below();
        return new StairStepTargets(
            direction,
            stand.above(2),
            stand.above(),
            stand,
            stand
        );
    }

    public StairStepTargets preview(BlockPos current, Direction candidateDirection) {
        Direction candidate = candidateDirection == null ? direction : candidateDirection;
        BlockPos start = current == null ? lastSafeStand : current;
        BlockPos stand = start.relative(candidate).below();
        return new StairStepTargets(
            candidate,
            stand.above(2),
            stand.above(),
            stand,
            stand
        );
    }

    public static ClearancePhase clearancePhase(boolean entryHeadAir, boolean horizontalAir, boolean verticalAir) {
        if (!horizontalAir) {
            return ClearancePhase.CLEAR_HORIZONTAL;
        }
        if (!entryHeadAir) {
            return ClearancePhase.CLEAR_ENTRY_HEAD;
        }
        if (!verticalAir) {
            return ClearancePhase.DIG_DOWN;
        }
        return ClearancePhase.MOVE;
    }

    public boolean shouldRelocateStart() {
        return failures >= RELOCATE_FAILURE_LIMIT;
    }

    public void resetFailures() {
        failures = 0;
        lastFailureReason = "none";
    }

    public int targetY() {
        return targetY;
    }

    public Direction direction() {
        return direction;
    }

    public int successfulSteps() {
        return successfulSteps;
    }

    public int failures() {
        return failures;
    }

    public BlockPos lastSafeStand() {
        return lastSafeStand;
    }

    public String lastFailureReason() {
        return lastFailureReason;
    }

    public String toLogText() {
        return "targetY=" + targetY
            + ", direction=" + direction.getName()
            + ", successfulSteps=" + successfulSteps
            + ", failures=" + failures
            + ", lastSafeStand=" + (lastSafeStand == null ? "none" : lastSafeStand.toShortString())
            + ", lastFailure=" + lastFailureReason;
    }

    public record StairStepTargets(
        Direction direction,
        BlockPos entryHead,
        BlockPos horizontal,
        BlockPos vertical,
        BlockPos stand
    ) {
        public StairStepTargets {
            direction = direction == null ? Direction.NORTH : direction;
            entryHead = entryHead == null ? BlockPos.ZERO : entryHead.immutable();
            horizontal = horizontal == null ? BlockPos.ZERO : horizontal.immutable();
            vertical = vertical == null ? BlockPos.ZERO : vertical.immutable();
            stand = stand == null ? BlockPos.ZERO : stand.immutable();
        }

        public String toLogText() {
            return "direction=" + direction.getName()
                + ", entryHead=" + entryHead.toShortString()
                + ", horizontal=" + horizontal.toShortString()
                + ", vertical=" + vertical.toShortString()
                + ", stand=" + stand.toShortString();
        }
    }
}
