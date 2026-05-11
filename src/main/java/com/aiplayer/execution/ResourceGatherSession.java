package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ResourceGatherSession {
    private static final double MAX_REUSE_DISTANCE_SQ = 64.0D * 64.0D;
    private static final int MAX_MINING_WAYPOINTS = 32;

    private final Map<String, ResourceState> states = new HashMap<>();

    public ResourceState stateFor(String resourceKey, BlockPos currentPos) {
        ResourceState state = states.computeIfAbsent(resourceKey, ResourceState::new);
        if (!state.isValidFrom(currentPos)) {
            state.reset(currentPos, "distance");
        } else if (state.getAnchor() == null) {
            state.reset(currentPos, "start");
        }
        return state;
    }

    public ResourceState miningState(BlockPos currentPos) {
        return stateFor("mining", currentPos);
    }

    public static final class ResourceState {
        private final String resourceKey;
        private final Set<BlockPos> rejectedTargets = new HashSet<>();
        private final Map<BlockPos, RejectedTarget> rejectedTargetDetails = new HashMap<>();
        private final List<MiningWaypoint> miningWaypoints = new ArrayList<>();
        private BlockPos anchor;
        private BlockPos lastStandPos;
        private BlockPos lastSuccessPos;
        private BlockPos caveEntrance;
        private String lastResetReason = "new";
        private int successfulBreaks;
        private boolean downwardMode;

        private ResourceState(String resourceKey) {
            this.resourceKey = resourceKey;
        }

        public String getResourceKey() {
            return resourceKey;
        }

        public BlockPos getAnchor() {
            return anchor;
        }

        public BlockPos getLastStandPos() {
            return lastStandPos;
        }

        public BlockPos getLastSuccessPos() {
            return lastSuccessPos;
        }

        public String getLastResetReason() {
            return lastResetReason;
        }

        public int getSuccessfulBreaks() {
            return successfulBreaks;
        }

        public BlockPos getCaveEntrance() {
            return caveEntrance;
        }

        public List<MiningWaypoint> getMiningWaypoints() {
            return List.copyOf(miningWaypoints);
        }

        public boolean shouldReuseDownwardMode() {
            return downwardMode && (successfulBreaks > 0 || !miningWaypoints.isEmpty());
        }

        public Set<BlockPos> getRejectedTargets() {
            return Set.copyOf(rejectedTargets);
        }

        public Map<BlockPos, RejectedTarget> getRejectedTargetDetails() {
            return Map.copyOf(rejectedTargetDetails);
        }

        public void markDownwardMode() {
            downwardMode = true;
        }

        public void rejectTarget(BlockPos pos) {
            rejectTarget(pos, "unknown", 0);
        }

        public void rejectTarget(BlockPos pos, String reason, int tick) {
            if (pos != null) {
                BlockPos immutable = pos.immutable();
                rejectedTargets.add(immutable);
                rejectedTargetDetails.put(immutable, new RejectedTarget(immutable, reason == null ? "unknown" : reason, Math.max(0, tick)));
            }
        }

        public void recordProgress(BlockPos standPos) {
            if (standPos != null) {
                lastStandPos = standPos.immutable();
            }
        }

        public void recordSuccess(BlockPos targetPos, BlockPos standPos) {
            if (anchor == null && standPos != null) {
                anchor = standPos.immutable();
            }
            if (targetPos != null) {
                lastSuccessPos = targetPos.immutable();
            }
            if (standPos != null) {
                lastStandPos = standPos.immutable();
            }
            successfulBreaks++;
        }

        public void recordMiningWaypoint(BlockPos standPos, String mode, int tick) {
            if (standPos == null) {
                return;
            }
            BlockPos waypointPos = standPos.immutable();
            if (!miningWaypoints.isEmpty()) {
                MiningWaypoint last = miningWaypoints.get(miningWaypoints.size() - 1);
                if (Math.abs(last.pos().getY() - waypointPos.getY()) < 4 && last.pos().distSqr(waypointPos) < 16.0D) {
                    return;
                }
            }
            miningWaypoints.add(new MiningWaypoint(waypointPos, waypointPos.getY(), mode == null ? "mining" : mode, Math.max(0, tick)));
            while (miningWaypoints.size() > MAX_MINING_WAYPOINTS) {
                miningWaypoints.remove(0);
            }
            recordProgress(waypointPos);
            downwardMode = true;
        }

        public Optional<BlockPos> nearestMiningWaypoint(BlockPos currentPos, int preferredY) {
            if (miningWaypoints.isEmpty()) {
                return Optional.empty();
            }
            BlockPos current = currentPos == null ? miningWaypoints.get(miningWaypoints.size() - 1).pos() : currentPos;
            return miningWaypoints.stream()
                .min(Comparator
                    .comparingInt((MiningWaypoint waypoint) -> Math.abs(waypoint.y() - preferredY))
                    .thenComparingDouble(waypoint -> waypoint.pos().distSqr(current)))
                .map(MiningWaypoint::pos);
        }

        public void recordCaveEntrance(BlockPos pos, int tick) {
            if (pos == null) {
                return;
            }
            caveEntrance = pos.immutable();
            recordMiningWaypoint(caveEntrance, "cave_entrance", tick);
        }

        public void reset(BlockPos currentPos, String reason) {
            anchor = currentPos == null ? null : currentPos.immutable();
            lastStandPos = anchor;
            lastSuccessPos = null;
            lastResetReason = reason;
            successfulBreaks = 0;
            downwardMode = false;
            rejectedTargets.clear();
            rejectedTargetDetails.clear();
            miningWaypoints.clear();
            caveEntrance = null;
        }

        public boolean isValidFrom(BlockPos currentPos) {
            if (anchor == null || currentPos == null) {
                return true;
            }
            return anchor.distSqr(currentPos) <= MAX_REUSE_DISTANCE_SQ;
        }
    }

    public record RejectedTarget(BlockPos pos, String reason, int tick) {
    }

    public record MiningWaypoint(BlockPos pos, int y, String mode, int tick) {
    }
}
