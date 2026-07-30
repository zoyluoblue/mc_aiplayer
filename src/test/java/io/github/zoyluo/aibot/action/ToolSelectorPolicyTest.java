package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.mining.ToolTier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolSelectorPolicyTest {
    @Test
    void miningChannelUsesStoneFloorButPreservesHigherRequirements() {
        assertEquals(ToolTier.STONE, ToolSelector.channelMinimumTier(ToolTier.WOOD));
        assertEquals(ToolTier.STONE, ToolSelector.channelMinimumTier(ToolTier.STONE));
        assertEquals(ToolTier.IRON, ToolSelector.channelMinimumTier(ToolTier.IRON));
        assertEquals(ToolTier.DIAMOND, ToolSelector.channelMinimumTier(ToolTier.DIAMOND));
        assertEquals(ToolTier.STONE,
                ToolSelector.channelMaximumTier(ToolTier.STONE, false));
        assertEquals(ToolTier.NETHERITE,
                ToolSelector.channelMaximumTier(ToolTier.STONE, true));
        assertEquals(ToolTier.NETHERITE,
                ToolSelector.channelMaximumTier(ToolTier.IRON, false));
    }
}
