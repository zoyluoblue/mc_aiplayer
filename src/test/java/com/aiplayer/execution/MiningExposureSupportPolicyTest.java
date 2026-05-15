package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningExposureSupportPolicyTest {
    @Test
    void airSupportAtHorizontalOreNeighborWouldSealExposure() {
        BlockPos ore = new BlockPos(8, 66, 8);
        BlockPos openedNeighbor = new BlockPos(7, 66, 8);

        assertTrue(MiningExposureSupportPolicy.supportWouldSealOreExposure(openedNeighbor, ore, true));
    }

    @Test
    void solidSupportDoesNotSealNewExposure() {
        BlockPos ore = new BlockPos(8, 66, 8);
        BlockPos solidNeighbor = new BlockPos(7, 66, 8);

        assertFalse(MiningExposureSupportPolicy.supportWouldSealOreExposure(solidNeighbor, ore, false));
    }

    @Test
    void VerticalNeighborIsNotAStandableExposureCell() {
        BlockPos ore = new BlockPos(8, 66, 8);
        BlockPos aboveOre = new BlockPos(8, 67, 8);

        assertFalse(MiningExposureSupportPolicy.supportWouldSealOreExposure(aboveOre, ore, true));
    }
}
