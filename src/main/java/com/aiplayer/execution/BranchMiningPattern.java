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

    private BranchMiningPattern(Snapshot snapshot) {
        this.mainDirection = directionFromName(snapshot == null ? null : snapshot.mainDirection());
        this.branchInterval = Math.max(1, snapshot == null ? 1 : snapshot.branchInterval());
        this.branchLength = Math.max(1, snapshot == null ? 1 : snapshot.branchLength());
        this.mainProgress = Math.max(0, snapshot == null ? 0 : snapshot.mainProgress());
        this.branchIndex = Math.max(0, snapshot == null ? 0 : snapshot.branchIndex());
        this.branchPending = snapshot != null && snapshot.branchPending();
        this.nextSide = Side.from(snapshot == null ? null : snapshot.nextSide());
    }

    public static BranchMiningPattern start(Direction mainDirection, int branchInterval, int branchLength) {
        return new BranchMiningPattern(mainDirection, branchInterval, branchLength);
    }

    public static BranchMiningPattern restore(Snapshot snapshot) {
        return new BranchMiningPattern(snapshot);
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

    public Direction mainDirection() {
        return mainDirection;
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

    public Snapshot snapshot() {
        return new Snapshot(
            mainDirection.getName(),
            branchInterval,
            branchLength,
            mainProgress,
            branchIndex,
            branchPending,
            nextSide.name().toLowerCase()
        );
    }

    private static Direction directionFromName(String name) {
        if (name == null || name.isBlank()) {
            return Direction.NORTH;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (direction.getName().equals(name)) {
                return direction;
            }
        }
        return Direction.NORTH;
    }

    public record Snapshot(
        String mainDirection,
        int branchInterval,
        int branchLength,
        int mainProgress,
        int branchIndex,
        boolean branchPending,
        String nextSide
    ) {
    }

    private enum Side {
        LEFT,
        RIGHT;

        static Side from(String name) {
            if ("right".equalsIgnoreCase(name)) {
                return RIGHT;
            }
            return LEFT;
        }
    }
}
