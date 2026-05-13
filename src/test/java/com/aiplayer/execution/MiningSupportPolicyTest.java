package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MiningSupportPolicyTest {
    @Test
    void prefersCobblestoneBeforeLowerPriorityBlocks() {
        assertEquals("minecraft:cobblestone", MiningSupportPolicy.chooseSupportBlock(Map.of(
            "minecraft:dirt", 12,
            "minecraft:cobblestone", 1
        )));
    }

    @Test
    void fallsBackToPlanksWhenStoneBlocksAreUnavailable() {
        assertEquals("minecraft:spruce_planks", MiningSupportPolicy.chooseSupportBlock(Map.of(
            "minecraft:spruce_planks", 8
        )));
    }

    @Test
    void returnsNullWhenNoSupportedPlacementBlockExists() {
        assertNull(MiningSupportPolicy.chooseSupportBlock(Map.of(
            "minecraft:raw_iron", 3,
            "minecraft:stick", 2
        )));
    }
}
