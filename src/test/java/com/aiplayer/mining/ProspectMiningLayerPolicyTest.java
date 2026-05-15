package com.aiplayer.mining;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProspectMiningLayerPolicyTest {
    @Test
    void allowsMiningFromSameLayerOneBelowOrOneAbove() {
        BlockPos ore = new BlockPos(-780, 58, 0);

        assertTrue(ProspectMiningLayerPolicy.canWorkOreFromCurrentLayer(58, ore));
        assertTrue(ProspectMiningLayerPolicy.canWorkOreFromCurrentLayer(57, ore));
        assertTrue(ProspectMiningLayerPolicy.canWorkOreFromCurrentLayer(59, ore));
    }

    @Test
    void rejectsLayersTooFarFromOre() {
        BlockPos ore = new BlockPos(-780, 58, 0);

        assertFalse(ProspectMiningLayerPolicy.canWorkOreFromCurrentLayer(56, ore));
        assertFalse(ProspectMiningLayerPolicy.canWorkOreFromCurrentLayer(60, ore));
        assertFalse(ProspectMiningLayerPolicy.canWorkOreFromCurrentLayer(58, null));
    }
}
