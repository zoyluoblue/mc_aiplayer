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
    void statusTextShowsAllCountersAgainstIntervals() {
        assertEquals(
            "blocks=3/30, distance=7/30, actions=11/30",
            ProspectRescanCounter.statusText(3, 7, 11, 30, 30, 30)
        );
    }
}
