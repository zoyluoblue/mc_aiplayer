package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningInventoryCapacityTest {
    @Test
    void reportsMissingCountAndSlotsForFullMiningBackpack() {
        MiningInventoryCapacity.Report report = MiningInventoryCapacity.evaluate(
            "minecraft:cobblestone",
            5,
            2,
            0,
            64,
            Map.of("minecraft:dirt", 64, "minecraft:cobblestone", 62)
        );

        assertFalse(report.enough());
        assertEquals(3, report.missingCount());
        assertEquals(1, report.missingSlots());
        assertTrue(report.toFailureText("采集石头").contains("缺少 3 个物品容量"));
        assertTrue(report.toFailureText("采集石头").contains("至少需要 1 个空格"));
        assertTrue(report.toFailureText("采集石头").contains("minecraft:cobblestone=62"));
    }

    @Test
    void reportsEnoughCapacityWhenStackSpaceExists() {
        MiningInventoryCapacity.Report report = MiningInventoryCapacity.evaluate(
            "minecraft:raw_gold",
            1,
            4,
            0,
            64,
            Map.of("minecraft:raw_gold", 60)
        );

        assertTrue(report.enough());
        assertEquals(0, report.missingCount());
        assertEquals(0, report.missingSlots());
        assertTrue(report.toStatusText().contains("可容纳=4"));
    }

    @Test
    void remainingNeededUsesUnfinishedGoalOnly() {
        assertEquals(1, MiningInventoryCapacity.remainingNeeded(0, 64, 63));
        assertEquals(8, MiningInventoryCapacity.remainingNeeded(10, 24, 26));
        assertEquals(1, MiningInventoryCapacity.remainingNeeded(0, 3, 3));
    }
}
