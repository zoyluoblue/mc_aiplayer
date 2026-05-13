package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TreeTargetPolicyTest {
    @Test
    void scanCandidatesPreferNearbyStableTreesOverFarLowTrees() {
        BlockPos current = new BlockPos(-4, 118, 3);
        BlockPos farLowTree = new BlockPos(-99, 96, 62);
        BlockPos nearerTree = new BlockPos(-22, 112, 18);

        assertTrue(
            TreeTargetPolicy.scanCandidateScore(current, nearerTree)
                < TreeTargetPolicy.scanCandidateScore(current, farLowTree)
        );
    }

    @Test
    void workTargetsPenalizeLargeDescentToStand() {
        BlockPos current = new BlockPos(-4, 118, 3);
        BlockPos farLog = new BlockPos(-99, 96, 62);
        BlockPos farStand = new BlockPos(-96, 97, 59);
        BlockPos nearbyLog = new BlockPos(-22, 112, 18);
        BlockPos nearbyStand = new BlockPos(-20, 113, 17);

        assertTrue(
            TreeTargetPolicy.workTargetScore(current, nearbyLog, nearbyStand)
                < TreeTargetPolicy.workTargetScore(current, farLog, farStand)
        );
    }

    @Test
    void safeTreeReachRejectsBorderlineVanillaReach() {
        assertTrue(TreeTargetPolicy.isWithinSafeReach(4.0D * 4.0D));
        assertTrue(!TreeTargetPolicy.isWithinSafeReach(4.55D * 4.55D));
    }

    @Test
    void standReachUsesEntityFeetHeightModel() {
        BlockPos stand = new BlockPos(0, 64, 0);
        BlockPos sameLayerFourBlocksAway = new BlockPos(4, 64, 0);
        BlockPos stableThreeBlocksAway = new BlockPos(3, 64, 0);

        assertTrue(!TreeTargetPolicy.isWithinSafeReach(
            TreeTargetPolicy.standFeetToBlockCenterDistanceSq(stand, sameLayerFourBlocksAway)
        ));
        assertTrue(TreeTargetPolicy.isWithinSafeReach(
            TreeTargetPolicy.standFeetToBlockCenterDistanceSq(stand, stableThreeBlocksAway)
        ));
    }
}
