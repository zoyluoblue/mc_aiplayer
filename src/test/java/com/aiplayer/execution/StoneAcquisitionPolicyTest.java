package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoneAcquisitionPolicyTest {
    @Test
    void directWorkableStoneIsPreferredBeforeReachableStand() {
        StoneAcquisitionPolicy.ScanBuilder builder = StoneAcquisitionPolicy.builder();

        builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
            new BlockPos(3, 64, 0),
            new BlockPos(3, 64, 1),
            StoneAcquisitionPolicy.Category.REACHABLE_STAND,
            9.0D,
            2
        ));
        builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
            new BlockPos(5, 64, 0),
            null,
            StoneAcquisitionPolicy.Category.DIRECT_WORKABLE,
            25.0D,
            1
        ));

        StoneAcquisitionPolicy.ScanResult result = builder.build();

        assertTrue(result.hasUsableTarget());
        assertEquals(StoneAcquisitionPolicy.Category.DIRECT_WORKABLE, result.selected().category());
        assertEquals("stone_direct", result.selected().targetReason());
    }

    @Test
    void nearestCandidateWinsWithinSameCategory() {
        StoneAcquisitionPolicy.ScanBuilder builder = StoneAcquisitionPolicy.builder();

        builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
            new BlockPos(2, 64, 0),
            null,
            StoneAcquisitionPolicy.Category.DIRECT_WORKABLE,
            4.0D,
            1
        ));
        builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
            new BlockPos(4, 64, 0),
            null,
            StoneAcquisitionPolicy.Category.DIRECT_WORKABLE,
            16.0D,
            3
        ));

        StoneAcquisitionPolicy.ScanResult result = builder.build();

        assertEquals(new BlockPos(2, 64, 0), result.selected().stonePos());
        assertEquals(2, result.directWorkable());
    }

    @Test
    void scanExplainsWhyStairDescentIsChosen() {
        StoneAcquisitionPolicy.ScanBuilder builder = StoneAcquisitionPolicy.builder();

        builder.recordCandidate(new StoneAcquisitionPolicy.Candidate(
            new BlockPos(8, 63, 1),
            null,
            StoneAcquisitionPolicy.Category.VISIBLE_UNREACHABLE,
            65.0D,
            1
        ));
        builder.recordRejected("previously_rejected:test");
        builder.recordPathCheck();
        builder.recordPathBudgetSkipped();

        StoneAcquisitionPolicy.ScanResult result = builder.build();

        assertFalse(result.hasUsableTarget());
        assertTrue(result.descentReason().contains("裸露石头"));
        assertTrue(result.toLogText().contains("decision=stair_descent"));
        assertTrue(result.toLogText().contains("visibleUnreachable=1"));
        assertTrue(result.toLogText().contains("pathBudgetSkipped=1"));
    }

    @Test
    void pathCheckOrderingUsesNearestCandidatesBeforeFarInsertionOrder() {
        List<StoneAcquisitionPolicy.Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            candidates.add(new StoneAcquisitionPolicy.Candidate(
                new BlockPos(100 + i, 64, 0),
                null,
                StoneAcquisitionPolicy.Category.VISIBLE_UNREACHABLE,
                10_000.0D + i,
                1
            ));
        }
        StoneAcquisitionPolicy.Candidate nearReachableCandidate = new StoneAcquisitionPolicy.Candidate(
            new BlockPos(2, 64, 0),
            null,
            StoneAcquisitionPolicy.Category.VISIBLE_UNREACHABLE,
            4.0D,
            1
        );
        candidates.add(nearReachableCandidate);

        List<StoneAcquisitionPolicy.Candidate> ordered = StoneAcquisitionPolicy.orderForPathChecks(candidates);

        assertEquals(nearReachableCandidate, ordered.get(0));
        assertTrue(ordered.subList(0, 32).contains(nearReachableCandidate));
    }
}
