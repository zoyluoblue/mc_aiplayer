package com.aiplayer.execution;

import com.aiplayer.recipe.MiningResource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningHeightPolicyTest {
    @Test
    void defaultsToZeroWhenNoSpecificMiningResourceExists() {
        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(73, null);

        assertEquals(0, decision.targetY());
        assertEquals("default_y0_no_resource", decision.reason());
        assertEquals("any", decision.resourceKey());
    }

    @Test
    void targetsGoldPrimaryRangeInsteadOfAlwaysUsingZero() {
        MiningResource.Profile gold = MiningResource.findByMineTarget("gold").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(64, gold);

        assertEquals(-16, decision.targetY());
        assertEquals(-64, decision.minY());
        assertEquals(32, decision.maxY());
        assertEquals("target_primary_midpoint", decision.reason());
        assertTrue(decision.rangeText().contains("regular_underground_gold"));
    }

    @Test
    void keepsCurrentHeightWhenAlreadyInsideTargetRange() {
        MiningResource.Profile diamond = MiningResource.findByMineTarget("diamond").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(0, diamond);

        assertEquals(0, decision.targetY());
        assertTrue(decision.currentInTargetRange());
        assertEquals("current_in_primary_range", decision.reason());
    }

    @Test
    void sendsDeepOreDownToPrimaryMidpointWhenAboveTargetRange() {
        MiningResource.Profile diamond = MiningResource.findByMineTarget("diamond").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(64, diamond);

        assertEquals(-24, decision.targetY());
        assertEquals("target_primary_midpoint", decision.reason());
        assertTrue(decision.rangeText().contains("deep_diamond_branch_mine"));
    }

    @Test
    void surfaceResourcesCanStayInTheirValidBand() {
        MiningResource.Profile coal = MiningResource.findByMineTarget("coal").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(90, coal);

        assertEquals(90, decision.targetY());
        assertEquals("current_in_primary_range", decision.reason());
    }

    @Test
    void surfaceAllowedFallbackCanKeepHighSurfaceIronSearch() {
        MiningResource.Profile iron = MiningResource.findByMineTarget("iron").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(90, iron);

        assertEquals(90, decision.targetY());
        assertTrue(decision.currentInTargetRange());
        assertEquals("current_in_surface_fallback_range", decision.reason());
        assertTrue(decision.rangeText().contains("mountain_surface_iron"));
    }

    @Test
    void redstoneUsesDeepBranchRange() {
        MiningResource.Profile redstone = MiningResource.findByMineTarget("redstone").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(70, redstone);

        assertEquals(-24, decision.targetY());
        assertTrue(decision.rangeText().contains("deep_redstone_branch_mine"));
    }

    @Test
    void lapisUsesMidDepthBand() {
        MiningResource.Profile lapis = MiningResource.findByMineTarget("lapis").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(90, lapis);

        assertEquals(0, decision.targetY());
        assertTrue(decision.rangeText().contains("mid_depth_lapis"));
    }

    @Test
    void emeraldKeepsMountainBandWhenAlreadyValid() {
        MiningResource.Profile emerald = MiningResource.findByMineTarget("emerald").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(120, emerald);

        assertEquals(120, decision.targetY());
        assertEquals("current_in_primary_range", decision.reason());
        assertTrue(decision.rangeText().contains("mountain_emerald"));
    }

    @Test
    void ancientDebrisUsesNetherLowBranchMineBand() {
        MiningResource.Profile ancientDebris = MiningResource.findByMineTarget("ancient_debris").orElseThrow();

        MiningHeightPolicy.Decision decision = MiningHeightPolicy.decide(80, ancientDebris);

        assertEquals(15, decision.targetY());
        assertTrue(decision.rangeText().contains("nether_low_branch_mine"));
    }

    @Test
    void nextLayerTargetStaysInsidePrimaryRange() {
        MiningResource.Profile diamond = MiningResource.findByMineTarget("diamond").orElseThrow();

        assertEquals(-8, MiningHeightPolicy.nextLayerTarget(0, diamond, 1, 8));
        assertEquals(-64, MiningHeightPolicy.nextLayerTarget(-60, diamond, 2, 8));
    }

    @Test
    void nextLayerTargetUsesDefaultYForUnknownProfile() {
        assertEquals(0, MiningHeightPolicy.nextLayerTarget(73, null, 1, 8));
    }

    @Test
    void nextLayerTargetUsesMidpointWhenAbovePrimaryRange() {
        MiningResource.Profile gold = MiningResource.findByMineTarget("gold").orElseThrow();

        assertEquals(-16, MiningHeightPolicy.nextLayerTarget(80, gold, 1, 8));
    }

    @Test
    void nextLayerTargetPreservesIronMountainFallbackBand() {
        MiningResource.Profile iron = MiningResource.findByMineTarget("iron").orElseThrow();

        assertEquals(82, MiningHeightPolicy.nextLayerTarget(90, iron, 1, 8));
        assertEquals(80, MiningHeightPolicy.nextLayerTarget(82, iron, 1, 8));
    }

    @Test
    void nextLayerTargetClampsAncientDebrisToNetherLowBand() {
        MiningResource.Profile ancientDebris = MiningResource.findByMineTarget("ancient_debris").orElseThrow();

        assertEquals(14, MiningHeightPolicy.nextLayerTarget(22, ancientDebris, 1, 8));
        assertEquals(8, MiningHeightPolicy.nextLayerTarget(10, ancientDebris, 2, 8));
    }

    @Test
    void routePlanLogsHeightDecision() {
        MiningResource.Profile gold = MiningResource.findByMineTarget("gold").orElseThrow();

        MiningRoutePlan plan = MiningRoutePlan.create(new BlockPos(0, 64, 0), gold, Direction.EAST, 32, 4);

        assertEquals(-16, plan.targetY());
        assertTrue(plan.toLogText().contains("height={resource=raw_gold"));
        assertTrue(plan.toLogText().contains("reason=target_primary_midpoint"));
    }
}
