package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class MainTunnelController {
    private static final int DEFAULT_RESCAN_INTERVAL = 30;

    private Direction direction;
    private final int segmentLength;
    private final int maxTurns;
    private int segmentBlocks;
    private int totalBlocks;
    private int turns;
    private int actionsSinceRescan;
    private BlockPos lastSafeStand;
    private String lastReason = "start";

    private MainTunnelController(Direction direction, int segmentLength, int maxTurns, BlockPos start) {
        this.direction = direction == null ? Direction.NORTH : direction;
        this.segmentLength = Math.max(1, segmentLength);
        this.maxTurns = Math.max(0, maxTurns);
        this.lastSafeStand = start == null ? BlockPos.ZERO : start.immutable();
    }

    private MainTunnelController(Snapshot snapshot) {
        this.direction = directionFromName(snapshot == null ? null : snapshot.direction());
        this.segmentLength = Math.max(1, snapshot == null ? 1 : snapshot.segmentLength());
        this.maxTurns = Math.max(0, snapshot == null ? 0 : snapshot.maxTurns());
        this.segmentBlocks = Math.max(0, snapshot == null ? 0 : snapshot.segmentBlocks());
        this.totalBlocks = Math.max(0, snapshot == null ? 0 : snapshot.totalBlocks());
        this.turns = Math.max(0, snapshot == null ? 0 : snapshot.turns());
        this.actionsSinceRescan = Math.max(0, snapshot == null ? 0 : snapshot.actionsSinceRescan());
        this.lastSafeStand = snapshot == null || snapshot.lastSafeStand() == null ? BlockPos.ZERO : snapshot.lastSafeStand().immutable();
        this.lastReason = snapshot == null || snapshot.lastReason() == null || snapshot.lastReason().isBlank()
            ? "restore"
            : snapshot.lastReason();
    }

    public static MainTunnelController start(Direction direction, int segmentLength, int maxTurns, BlockPos start) {
        return new MainTunnelController(direction, segmentLength, maxTurns, start);
    }

    public static MainTunnelController restore(Snapshot snapshot) {
        return new MainTunnelController(snapshot);
    }

    public void recordAdvance(BlockPos stand) {
        segmentBlocks++;
        totalBlocks++;
        actionsSinceRescan++;
        lastReason = "advance";
        if (stand != null) {
            lastSafeStand = stand.immutable();
        }
    }

    public boolean segmentComplete() {
        return segmentBlocks >= segmentLength;
    }

    public boolean shouldRescan() {
        return actionsSinceRescan >= DEFAULT_RESCAN_INTERVAL || segmentComplete();
    }

    public void resetRescanBudget(String reason) {
        actionsSinceRescan = 0;
        segmentBlocks = 0;
        lastReason = reason == null || reason.isBlank() ? "rescan" : reason;
    }

    public boolean turn(String reason) {
        if (turns >= maxTurns) {
            lastReason = reason == null || reason.isBlank() ? "turn_limit" : reason;
            return false;
        }
        direction = direction.getClockWise();
        segmentBlocks = 0;
        turns++;
        actionsSinceRescan++;
        lastReason = reason == null || reason.isBlank() ? "turn" : reason;
        return true;
    }

    public Direction direction() {
        return direction;
    }

    public int segmentBlocks() {
        return segmentBlocks;
    }

    public int totalBlocks() {
        return totalBlocks;
    }

    public int turns() {
        return turns;
    }

    public BlockPos lastSafeStand() {
        return lastSafeStand;
    }

    public String toLogText() {
        return "direction=" + direction.getName()
            + ", segmentBlocks=" + segmentBlocks + "/" + segmentLength
            + ", totalBlocks=" + totalBlocks
            + ", turns=" + turns + "/" + maxTurns
            + ", actionsSinceRescan=" + actionsSinceRescan
            + ", lastSafeStand=" + (lastSafeStand == null ? "none" : lastSafeStand.toShortString())
            + ", reason=" + lastReason;
    }

    public Snapshot snapshot() {
        return new Snapshot(
            direction.getName(),
            segmentLength,
            maxTurns,
            segmentBlocks,
            totalBlocks,
            turns,
            actionsSinceRescan,
            lastSafeStand,
            lastReason
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
        String direction,
        int segmentLength,
        int maxTurns,
        int segmentBlocks,
        int totalBlocks,
        int turns,
        int actionsSinceRescan,
        BlockPos lastSafeStand,
        String lastReason
    ) {
        public Snapshot {
            lastSafeStand = lastSafeStand == null ? null : lastSafeStand.immutable();
        }
    }
}
