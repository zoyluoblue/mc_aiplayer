package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProspectRescanCounterTest {
    @Test
    void refreshReasonPrefersBlocksThenDistanceThenActions() {
        assertEquals("blocks_since_prospect:30", ProspectRescanCounter.refreshReason(30, 30, 30, 30, 30, 30));
        assertEquals("distance_since_prospect:30", ProspectRescanCounter.refreshReason(12, 30, 30, 30, 30, 30));
        assertEquals("actions_since_prospect:30", ProspectRescanCounter.refreshReason(12, 12, 30, 30, 30, 30));
        assertNull(ProspectRescanCounter.refreshReason(12, 12, 12, 30, 30, 30));
    }

    @Test
    void bindingDecisionExplainsSameOrNewCandidate() {
        BlockPos oldOre = new BlockPos(10, 24, 10);

        assertEquals(
            "same_candidate_keep_current_route",
            ProspectRescanCounter.bindingDecision(oldOre, oldOre)
        );
        assertEquals(
            "new_candidate_rebind",
            ProspectRescanCounter.bindingDecision(oldOre, new BlockPos(12, 24, 10))
        );
        assertEquals("initial_candidate", ProspectRescanCounter.bindingDecision(null, oldOre));
        assertEquals("no_candidate_keep_current_route", ProspectRescanCounter.bindingDecision(oldOre, null));
    }

    @Test
    void bindingCarriesRouteRebindSemantics() {
        BlockPos oldOre = new BlockPos(10, 24, 10);

        ProspectRescanCounter.Binding same = ProspectRescanCounter.binding(oldOre, oldOre);
        ProspectRescanCounter.Binding changed = ProspectRescanCounter.binding(oldOre, new BlockPos(12, 24, 10));
        ProspectRescanCounter.Binding missing = ProspectRescanCounter.binding(oldOre, null);

        assertEquals(false, same.rebindRoute());
        assertEquals(true, same.keepCurrentRoute());
        assertEquals(true, changed.rebindRoute());
        assertEquals(false, changed.keepCurrentRoute());
        assertEquals(false, missing.rebindRoute());
        assertEquals(true, missing.keepCurrentRoute());
    }

    @Test
    void bindingUsesRouteCostAndExposureForNewCandidates() {
        BlockPos oldOre = new BlockPos(10, 24, 10);
        BlockPos nearOre = new BlockPos(12, 24, 10);
        BlockPos farOre = new BlockPos(80, -16, 10);

        ProspectRescanCounter.Binding better = ProspectRescanCounter.binding(
            oldOre,
            nearOre,
            30,
            -8,
            18,
            -6,
            true
        );
        ProspectRescanCounter.Binding worse = ProspectRescanCounter.binding(
            oldOre,
            farOre,
            18,
            -4,
            70,
            -24,
            true
        );
        ProspectRescanCounter.Binding noExposure = ProspectRescanCounter.binding(
            oldOre,
            nearOre,
            18,
            -4,
            10,
            -2,
            false
        );

        assertEquals("new_candidate_better_route_rebind", better.decision());
        assertEquals(true, better.rebindRoute());
        assertEquals("new_candidate_costlier_keep_current_route", worse.decision());
        assertEquals(true, worse.keepCurrentRoute());
        assertEquals("new_candidate_no_exposure_keep_current_route", noExposure.decision());
        assertEquals(true, noExposure.keepCurrentRoute());
    }

    @Test
    void statusTextShowsAllCountersAgainstIntervals() {
        assertEquals(
            "blocks=3/30, distance=7/30, actions=11/30",
            ProspectRescanCounter.statusText(3, 7, 11, 30, 30, 30)
        );
    }

    @Test
    void remainingStatusTextShowsNextTrigger() {
        assertEquals(
            "remainingBlocks=20, remainingDistance=5, remainingActions=12, nextTrigger=distance",
            ProspectRescanCounter.remainingStatusText(10, 25, 18, 30, 30, 30)
        );
    }
}
