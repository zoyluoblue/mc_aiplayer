package com.aiplayer.execution;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchMiningPatternTest {
    @Test
    void advancesMainTunnelUntilBranchInterval() {
        BranchMiningPattern pattern = BranchMiningPattern.start(Direction.NORTH, 3, 24);

        assertEquals(Direction.NORTH, pattern.nextDirection());
        pattern.recordMainAdvance();
        pattern.recordMainAdvance();

        assertFalse(pattern.isBranchTurn());
        assertEquals(Direction.NORTH, pattern.nextDirection());
    }

    @Test
    void alternatesLeftAndRightBranchesAtInterval() {
        BranchMiningPattern pattern = BranchMiningPattern.start(Direction.NORTH, 3, 24);

        pattern.recordMainAdvance();
        pattern.recordMainAdvance();
        pattern.recordMainAdvance();

        assertTrue(pattern.isBranchTurn());
        assertEquals(Direction.WEST, pattern.nextDirection());
        pattern.recordBranchComplete();
        assertEquals(1, pattern.branchIndex());
        assertFalse(pattern.isBranchTurn());
        assertEquals(Direction.NORTH, pattern.nextDirection());
        pattern.recordMainAdvance();
        pattern.recordMainAdvance();
        pattern.recordMainAdvance();
        assertTrue(pattern.isBranchTurn());
        assertEquals(Direction.EAST, pattern.nextDirection());
    }

    @Test
    void keepsConfiguredBranchLength() {
        BranchMiningPattern pattern = BranchMiningPattern.start(Direction.EAST, 3, 32);

        assertEquals(32, pattern.branchLength());
    }
}
