package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningRouteMemoryTest {
    @Test
    void recordsSafeMiningPointWithStageDirectionAndAction() {
        ResourceGatherSession.ResourceState state = new ResourceGatherSession().miningState(new BlockPos(0, 64, 0));

        state.recordSafeMiningPoint(new BlockPos(4, 58, 9), "east", "branch_tunnel", 120, "move_reached");

        assertEquals(1, state.getSafeMiningPoints().size());
        ResourceGatherSession.SafeMiningPoint point = state.getSafeMiningPoints().get(0);
        assertEquals(new BlockPos(4, 58, 9), point.pos());
        assertEquals(58, point.y());
        assertEquals("east", point.direction());
        assertEquals("branch_tunnel", point.stage());
        assertEquals(120, point.tick());
        assertEquals("move_reached", point.lastAction());
        assertTrue(state.shouldReuseDownwardMode());
    }

    @Test
    void updatesLatestPointWhenSamePositionIsRecordedAgain() {
        ResourceGatherSession.ResourceState state = new ResourceGatherSession().miningState(new BlockPos(0, 64, 0));
        BlockPos pos = new BlockPos(2, 40, 2);

        state.recordSafeMiningPoint(pos, "north", "descend", 10, "start");
        state.recordSafeMiningPoint(pos, "east", "return", 20, "recovered");

        assertEquals(1, state.getSafeMiningPoints().size());
        ResourceGatherSession.SafeMiningPoint point = state.getSafeMiningPoints().get(0);
        assertEquals("east", point.direction());
        assertEquals("return", point.stage());
        assertEquals(20, point.tick());
        assertEquals("recovered", point.lastAction());
    }

    @Test
    void choosesNearestSafeMiningPointForFallback() {
        ResourceGatherSession.ResourceState state = new ResourceGatherSession().miningState(new BlockPos(0, 64, 0));
        BlockPos near = new BlockPos(10, 32, 0);
        BlockPos far = new BlockPos(-40, 12, 0);
        state.recordSafeMiningPoint(far, "west", "branch", 10, "old");
        state.recordSafeMiningPoint(near, "east", "branch", 20, "new");

        ResourceGatherSession.SafeMiningPoint selected = state.nearestSafeMiningPoint(new BlockPos(12, 32, 0)).orElseThrow();

        assertEquals(near, selected.pos());
    }

    @Test
    void exposesSafeMiningPointsSortedByDistanceForFilteredFallbackSelection() {
        ResourceGatherSession.ResourceState state = new ResourceGatherSession().miningState(new BlockPos(0, 64, 0));
        BlockPos nearest = new BlockPos(1, 30, 0);
        BlockPos second = new BlockPos(4, 30, 0);
        BlockPos far = new BlockPos(20, 30, 0);
        state.recordSafeMiningPoint(far, "east", "main", 1, "move");
        state.recordSafeMiningPoint(second, "east", "main", 2, "move");
        state.recordSafeMiningPoint(nearest, "east", "main", 3, "move");

        assertEquals(nearest, state.safeMiningPointsByDistance(new BlockPos(0, 30, 0)).get(0).pos());
        assertEquals(second, state.safeMiningPointsByDistance(new BlockPos(0, 30, 0)).get(1).pos());
        assertEquals(far, state.safeMiningPointsByDistance(new BlockPos(0, 30, 0)).get(2).pos());
    }

    @Test
    void trimsOldSafeMiningPoints() {
        ResourceGatherSession.ResourceState state = new ResourceGatherSession().miningState(new BlockPos(0, 64, 0));

        for (int i = 0; i < 60; i++) {
            state.recordSafeMiningPoint(new BlockPos(i, 30, 0), "east", "main", i, "move");
        }

        assertEquals(48, state.getSafeMiningPoints().size());
        assertEquals(new BlockPos(12, 30, 0), state.getSafeMiningPoints().get(0).pos());
        assertEquals(new BlockPos(59, 30, 0), state.getSafeMiningPoints().get(47).pos());
    }

    @Test
    void savesAndLoadsFishboneMiningSnapshot() {
        ResourceGatherSession session = new ResourceGatherSession();
        ResourceGatherSession.ResourceState state = session.miningState(new BlockPos(0, 64, 0));
        MainTunnelController main = MainTunnelController.start(Direction.EAST, 32, 4, new BlockPos(0, 12, 0));
        main.recordAdvance(new BlockPos(1, 12, 0));
        BranchMiningPattern pattern = BranchMiningPattern.start(Direction.EAST, 3, 16);
        pattern.recordMainAdvance();
        pattern.recordMainAdvance();
        pattern.recordMainAdvance();

        state.recordFishboneSnapshot(new ResourceGatherSession.FishboneSnapshot(
            main.snapshot(),
            pattern.snapshot(),
            "north",
            new BlockPos(1, 12, 0),
            5,
            1,
            2
        ));

        CompoundTag tag = new CompoundTag();
        session.saveToNBT(tag);
        ResourceGatherSession loaded = new ResourceGatherSession();
        loaded.loadFromNBT(tag);

        ResourceGatherSession.FishboneSnapshot snapshot = loaded.miningState(new BlockPos(0, 64, 0)).getFishboneSnapshot();
        assertEquals("north", snapshot.branchDirection());
        assertEquals(new BlockPos(1, 12, 0), snapshot.branchReturnTarget());
        assertEquals(5, snapshot.branchTunnelBlocks());
        assertEquals(1, snapshot.branchTunnelTurns());
        assertEquals(2, snapshot.branchLayerShifts());
        assertEquals(1, snapshot.mainTunnel().totalBlocks());
        assertTrue(snapshot.branchPattern().branchPending());
    }

    @Test
    void restoredFishboneSnapshotUsesLatestSafeStandAsAnchor() {
        ResourceGatherSession session = new ResourceGatherSession();
        ResourceGatherSession.ResourceState state = session.miningState(new BlockPos(0, 64, 0));
        MainTunnelController main = MainTunnelController.start(Direction.EAST, 64, 4, new BlockPos(0, 12, 0));
        for (int i = 1; i <= 80; i++) {
            main.recordAdvance(new BlockPos(i, 12, 0));
        }

        state.recordFishboneSnapshot(new ResourceGatherSession.FishboneSnapshot(
            main.snapshot(),
            BranchMiningPattern.start(Direction.EAST, 3, 16).snapshot(),
            "",
            new BlockPos(1, 12, 0),
            0,
            0,
            0
        ));

        CompoundTag tag = new CompoundTag();
        session.saveToNBT(tag);
        ResourceGatherSession loaded = new ResourceGatherSession();
        loaded.loadFromNBT(tag);

        ResourceGatherSession.FishboneSnapshot snapshot = loaded.miningState(new BlockPos(81, 12, 0)).getFishboneSnapshot();
        assertNotNull(snapshot);
        assertEquals(new BlockPos(80, 12, 0), snapshot.mainTunnel().lastSafeStand());
        assertEquals(80, snapshot.mainTunnel().totalBlocks());
    }
}
