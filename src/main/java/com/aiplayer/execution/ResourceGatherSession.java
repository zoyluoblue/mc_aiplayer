package com.aiplayer.execution;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ResourceGatherSession {
    private static final double MAX_REUSE_DISTANCE_SQ = 64.0D * 64.0D;

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

    public static final class ResourceState {
        private final String resourceKey;
        private final Set<BlockPos> rejectedTargets = new HashSet<>();
        private BlockPos anchor;
        private BlockPos lastStandPos;
        private BlockPos lastSuccessPos;
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

        public boolean shouldReuseDownwardMode() {
            return downwardMode && successfulBreaks > 0;
        }

        public Set<BlockPos> getRejectedTargets() {
            return Set.copyOf(rejectedTargets);
        }

        public void markDownwardMode() {
            downwardMode = true;
        }

        public void rejectTarget(BlockPos pos) {
            if (pos != null) {
                rejectedTargets.add(pos.immutable());
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

        public void reset(BlockPos currentPos, String reason) {
            anchor = currentPos == null ? null : currentPos.immutable();
            lastStandPos = anchor;
            lastSuccessPos = null;
            lastResetReason = reason;
            successfulBreaks = 0;
            downwardMode = false;
            rejectedTargets.clear();
        }

        public boolean isValidFrom(BlockPos currentPos) {
            if (anchor == null || currentPos == null) {
                return true;
            }
            return anchor.distSqr(currentPos) <= MAX_REUSE_DISTANCE_SQ;
        }
    }
}
