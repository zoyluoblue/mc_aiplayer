package com.aiplayer.execution;

import net.minecraft.core.Direction;

public final class BranchMiningPattern {
    private final Direction mainDirection;
    private final int branchInterval;
    private final int branchLength;
    private int mainProgress;
    private int branchIndex;
    private boolean branchPending;
    private Side nextSide = Side.LEFT;

    private BranchMiningPattern(Direction mainDirection, int branchInterval, int branchLength) {
        this.mainDirection = mainDirection == null ? Direction.NORTH : mainDirection;
        this.branchInterval = Math.max(1, branchInterval);
        this.branchLength = Math.max(1, branchLength);
    }

    public static BranchMiningPattern start(Direction mainDirection, int branchInterval, int branchLength) {
        return new BranchMiningPattern(mainDirection, branchInterval, branchLength);
    }

    public Direction nextDirection() {
        if (branchPending) {
            return nextSide == Side.LEFT ? mainDirection.getCounterClockWise() : mainDirection.getClockWise();
        }
        return mainDirection;
    }

    public void recordMainAdvance() {
        mainProgress++;
        if (mainProgress > 0 && mainProgress % branchInterval == 0) {
            branchPending = true;
        }
    }

    public void recordBranchComplete() {
        if (branchPending) {
            branchIndex++;
            branchPending = false;
            nextSide = nextSide == Side.LEFT ? Side.RIGHT : Side.LEFT;
        }
    }

    public boolean isBranchTurn() {
        return branchPending;
    }

    public int branchLength() {
        return branchLength;
    }

    public int mainProgress() {
        return mainProgress;
    }

    public int branchIndex() {
        return branchIndex;
    }

    public String toLogText() {
        return "mainDirection=" + mainDirection.getName()
            + ", mainProgress=" + mainProgress
            + ", branchInterval=" + branchInterval
            + ", branchLength=" + branchLength
            + ", branchIndex=" + branchIndex
            + ", branchPending=" + branchPending
            + ", nextSide=" + nextSide.name().toLowerCase();
    }

    private enum Side {
        LEFT,
        RIGHT
    }
}
