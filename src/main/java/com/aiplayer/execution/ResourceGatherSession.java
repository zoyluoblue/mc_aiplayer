package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

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
    private static final int MAX_SAFE_MINING_POINTS = 48;

    private final Map<String, ResourceState> states = new HashMap<>();
    private final Map<String, BlockPos> knownStations = new HashMap<>();

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

    public void rememberStation(String stationItem, BlockPos pos) {
        if (stationItem == null || stationItem.isBlank() || pos == null) {
            return;
        }
        knownStations.put(stationItem, pos.immutable());
    }

    public Optional<BlockPos> knownStation(String stationItem) {
        if (stationItem == null || stationItem.isBlank()) {
            return Optional.empty();
        }
        BlockPos pos = knownStations.get(stationItem);
        return pos == null ? Optional.empty() : Optional.of(pos.immutable());
    }

    public void forgetStation(String stationItem, BlockPos pos) {
        if (stationItem == null || stationItem.isBlank()) {
            return;
        }
        if (pos == null) {
            knownStations.remove(stationItem);
            return;
        }
        BlockPos current = knownStations.get(stationItem);
        if (pos.equals(current)) {
            knownStations.remove(stationItem);
        }
    }

    public void saveToNBT(CompoundTag tag) {
        if (tag == null) {
            return;
        }
        ListTag stateTags = new ListTag();
        for (Map.Entry<String, ResourceState> entry : states.entrySet()) {
            ResourceState state = entry.getValue();
            if (state.getFishboneSnapshot() == null) {
                continue;
            }
            CompoundTag stateTag = new CompoundTag();
            stateTag.putString("ResourceKey", entry.getKey());
            stateTag.put("Fishbone", saveFishboneSnapshot(state.getFishboneSnapshot()));
            stateTags.add(stateTag);
        }
        tag.put("States", stateTags);
        ListTag stationTags = new ListTag();
        for (Map.Entry<String, BlockPos> entry : knownStations.entrySet()) {
            BlockPos pos = entry.getValue();
            if (pos == null) {
                continue;
            }
            CompoundTag stationTag = new CompoundTag();
            stationTag.putString("Item", entry.getKey());
            stationTag.putInt("X", pos.getX());
            stationTag.putInt("Y", pos.getY());
            stationTag.putInt("Z", pos.getZ());
            stationTags.add(stationTag);
        }
        tag.put("KnownStations", stationTags);
    }

    public void loadFromNBT(CompoundTag tag) {
        states.clear();
        knownStations.clear();
        if (tag == null || !tag.contains("States")) {
            loadKnownStations(tag);
            return;
        }
        ListTag stateTags = tag.getList("States", 10);
        for (int i = 0; i < stateTags.size(); i++) {
            CompoundTag stateTag = stateTags.getCompound(i);
            String key = stateTag.getString("ResourceKey");
            if (key == null || key.isBlank() || !stateTag.contains("Fishbone")) {
                continue;
            }
            ResourceState state = states.computeIfAbsent(key, ResourceState::new);
            state.recordFishboneSnapshot(loadFishboneSnapshot(stateTag.getCompound("Fishbone")));
        }
        loadKnownStations(tag);
    }

    private void loadKnownStations(CompoundTag tag) {
        if (tag == null || !tag.contains("KnownStations")) {
            return;
        }
        ListTag stationTags = tag.getList("KnownStations", 10);
        for (int i = 0; i < stationTags.size(); i++) {
            CompoundTag stationTag = stationTags.getCompound(i);
            String item = stationTag.getString("Item");
            if (item == null || item.isBlank()) {
                continue;
            }
            knownStations.put(item, new BlockPos(
                stationTag.getInt("X"),
                stationTag.getInt("Y"),
                stationTag.getInt("Z")
            ));
        }
    }

    public static final class ResourceState {
        private final String resourceKey;
        private final Set<BlockPos> rejectedTargets = new HashSet<>();
        private final Map<BlockPos, RejectedTarget> rejectedTargetDetails = new HashMap<>();
        private final List<MiningWaypoint> miningWaypoints = new ArrayList<>();
        private final List<SafeMiningPoint> safeMiningPoints = new ArrayList<>();
        private BlockPos anchor;
        private BlockPos lastStandPos;
        private BlockPos lastSuccessPos;
        private BlockPos caveEntrance;
        private String lastResetReason = "new";
        private int successfulBreaks;
        private boolean downwardMode;
        private FishboneSnapshot fishboneSnapshot;

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

        public List<SafeMiningPoint> getSafeMiningPoints() {
            return List.copyOf(safeMiningPoints);
        }

        public boolean shouldReuseDownwardMode() {
            return downwardMode && (successfulBreaks > 0 || !miningWaypoints.isEmpty() || !safeMiningPoints.isEmpty());
        }

        public FishboneSnapshot getFishboneSnapshot() {
            return fishboneSnapshot;
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

        public void recordSafeMiningPoint(BlockPos standPos, String direction, String stage, int tick, String lastAction) {
            if (standPos == null) {
                return;
            }
            BlockPos safePos = standPos.immutable();
            String safeDirection = direction == null || direction.isBlank() ? "unknown" : direction;
            String safeStage = stage == null || stage.isBlank() ? "mining" : stage;
            String safeAction = lastAction == null || lastAction.isBlank() ? "progress" : lastAction;
            if (!safeMiningPoints.isEmpty()) {
                SafeMiningPoint last = safeMiningPoints.get(safeMiningPoints.size() - 1);
                if (last.pos().equals(safePos)) {
                    safeMiningPoints.set(safeMiningPoints.size() - 1,
                        new SafeMiningPoint(safePos, safePos.getY(), safeDirection, safeStage, Math.max(0, tick), safeAction));
                    return;
                }
            }
            safeMiningPoints.add(new SafeMiningPoint(safePos, safePos.getY(), safeDirection, safeStage, Math.max(0, tick), safeAction));
            while (safeMiningPoints.size() > MAX_SAFE_MINING_POINTS) {
                safeMiningPoints.remove(0);
            }
            recordProgress(safePos);
            downwardMode = true;
        }

        public Optional<SafeMiningPoint> nearestSafeMiningPoint(BlockPos currentPos) {
            return safeMiningPointsByDistance(currentPos).stream().findFirst();
        }

        public List<SafeMiningPoint> safeMiningPointsByDistance(BlockPos currentPos) {
            if (safeMiningPoints.isEmpty()) {
                return List.of();
            }
            BlockPos current = currentPos == null ? safeMiningPoints.get(safeMiningPoints.size() - 1).pos() : currentPos;
            return safeMiningPoints.stream()
                .sorted(Comparator
                    .comparingDouble((SafeMiningPoint point) -> point.pos().distSqr(current))
                    .thenComparing((SafeMiningPoint point) -> -point.tick()))
                .toList();
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

        public void recordFishboneSnapshot(FishboneSnapshot snapshot) {
            fishboneSnapshot = snapshot;
            BlockPos snapshotAnchor = snapshotAnchor(snapshot);
            if (snapshotAnchor != null) {
                anchor = snapshotAnchor.immutable();
                lastStandPos = anchor;
            }
            downwardMode = true;
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
            safeMiningPoints.clear();
            caveEntrance = null;
            fishboneSnapshot = null;
        }

        public boolean isValidFrom(BlockPos currentPos) {
            if (anchor == null || currentPos == null) {
                return true;
            }
            return anchor.distSqr(currentPos) <= MAX_REUSE_DISTANCE_SQ;
        }

        private BlockPos snapshotAnchor(FishboneSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            if (snapshot.mainTunnel() != null && snapshot.mainTunnel().lastSafeStand() != null) {
                return snapshot.mainTunnel().lastSafeStand();
            }
            return snapshot.branchReturnTarget();
        }
    }

    public record RejectedTarget(BlockPos pos, String reason, int tick) {
    }

    public record MiningWaypoint(BlockPos pos, int y, String mode, int tick) {
    }

    public record SafeMiningPoint(BlockPos pos, int y, String direction, String stage, int tick, String lastAction) {
        public String toLogText() {
            return "pos=" + pos.toShortString()
                + ",y=" + y
                + ",direction=" + direction
                + ",stage=" + stage
                + ",tick=" + tick
                + ",lastAction=" + lastAction;
        }
    }

    public record FishboneSnapshot(
        MainTunnelController.Snapshot mainTunnel,
        BranchMiningPattern.Snapshot branchPattern,
        String branchDirection,
        BlockPos branchReturnTarget,
        int branchTunnelBlocks,
        int branchTunnelTurns,
        int branchLayerShifts
    ) {
        public FishboneSnapshot {
            branchReturnTarget = branchReturnTarget == null ? null : branchReturnTarget.immutable();
            branchDirection = branchDirection == null ? "" : branchDirection;
        }

        public String toLogText() {
            return "branchDirection=" + (branchDirection.isBlank() ? "none" : branchDirection)
                + ", branchReturnTarget=" + (branchReturnTarget == null ? "none" : branchReturnTarget.toShortString())
                + ", branchTunnelBlocks=" + branchTunnelBlocks
                + ", branchTunnelTurns=" + branchTunnelTurns
                + ", branchLayerShifts=" + branchLayerShifts
                + ", mainTunnel={" + (mainTunnel == null ? "none" : mainTunnel.direction() + "/" + mainTunnel.segmentBlocks() + "/" + mainTunnel.totalBlocks()) + "}"
                + ", branchPattern={" + (branchPattern == null ? "none" : branchPattern.mainDirection() + "/" + branchPattern.mainProgress() + "/" + branchPattern.branchIndex()) + "}";
        }
    }

    private static CompoundTag saveFishboneSnapshot(FishboneSnapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        if (snapshot == null) {
            return tag;
        }
        tag.putString("BranchDirection", snapshot.branchDirection());
        if (snapshot.branchReturnTarget() != null) {
            tag.put("BranchReturnTarget", savePos(snapshot.branchReturnTarget()));
        }
        tag.putInt("BranchTunnelBlocks", snapshot.branchTunnelBlocks());
        tag.putInt("BranchTunnelTurns", snapshot.branchTunnelTurns());
        tag.putInt("BranchLayerShifts", snapshot.branchLayerShifts());
        if (snapshot.mainTunnel() != null) {
            tag.put("MainTunnel", saveMainTunnel(snapshot.mainTunnel()));
        }
        if (snapshot.branchPattern() != null) {
            tag.put("BranchPattern", saveBranchPattern(snapshot.branchPattern()));
        }
        return tag;
    }

    private static FishboneSnapshot loadFishboneSnapshot(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        return new FishboneSnapshot(
            tag.contains("MainTunnel") ? loadMainTunnel(tag.getCompound("MainTunnel")) : null,
            tag.contains("BranchPattern") ? loadBranchPattern(tag.getCompound("BranchPattern")) : null,
            tag.getString("BranchDirection"),
            tag.contains("BranchReturnTarget") ? loadPos(tag.getCompound("BranchReturnTarget")) : null,
            tag.getInt("BranchTunnelBlocks"),
            tag.getInt("BranchTunnelTurns"),
            tag.getInt("BranchLayerShifts")
        );
    }

    private static CompoundTag saveMainTunnel(MainTunnelController.Snapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Direction", snapshot.direction());
        tag.putInt("SegmentLength", snapshot.segmentLength());
        tag.putInt("MaxTurns", snapshot.maxTurns());
        tag.putInt("SegmentBlocks", snapshot.segmentBlocks());
        tag.putInt("TotalBlocks", snapshot.totalBlocks());
        tag.putInt("Turns", snapshot.turns());
        tag.putInt("ActionsSinceRescan", snapshot.actionsSinceRescan());
        if (snapshot.lastSafeStand() != null) {
            tag.put("LastSafeStand", savePos(snapshot.lastSafeStand()));
        }
        tag.putString("LastReason", snapshot.lastReason());
        return tag;
    }

    private static MainTunnelController.Snapshot loadMainTunnel(CompoundTag tag) {
        return new MainTunnelController.Snapshot(
            tag.getString("Direction"),
            tag.getInt("SegmentLength"),
            tag.getInt("MaxTurns"),
            tag.getInt("SegmentBlocks"),
            tag.getInt("TotalBlocks"),
            tag.getInt("Turns"),
            tag.getInt("ActionsSinceRescan"),
            tag.contains("LastSafeStand") ? loadPos(tag.getCompound("LastSafeStand")) : null,
            tag.getString("LastReason")
        );
    }

    private static CompoundTag saveBranchPattern(BranchMiningPattern.Snapshot snapshot) {
        CompoundTag tag = new CompoundTag();
        tag.putString("MainDirection", snapshot.mainDirection());
        tag.putInt("BranchInterval", snapshot.branchInterval());
        tag.putInt("BranchLength", snapshot.branchLength());
        tag.putInt("MainProgress", snapshot.mainProgress());
        tag.putInt("BranchIndex", snapshot.branchIndex());
        tag.putBoolean("BranchPending", snapshot.branchPending());
        tag.putString("NextSide", snapshot.nextSide());
        return tag;
    }

    private static BranchMiningPattern.Snapshot loadBranchPattern(CompoundTag tag) {
        return new BranchMiningPattern.Snapshot(
            tag.getString("MainDirection"),
            tag.getInt("BranchInterval"),
            tag.getInt("BranchLength"),
            tag.getInt("MainProgress"),
            tag.getInt("BranchIndex"),
            tag.getBoolean("BranchPending"),
            tag.getString("NextSide")
        );
    }

    private static CompoundTag savePos(BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        return tag;
    }

    private static BlockPos loadPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
    }
}
