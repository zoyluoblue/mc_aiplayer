package com.aiplayer.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningDropProgressTest {
    @Test
    void marksTargetCompleteWhenInventoryReachesTargetTotal() {
        MiningDropProgress progress = MiningDropProgress.analyze(
            "minecraft:raw_gold",
            1,
            2,
            2,
            "minecraft:raw_gold",
            1,
            1,
            0,
            true,
            null
        );

        assertTrue(progress.complete());
        assertEquals(1, progress.gainedCount());
        assertEquals(0, progress.remainingCount());
        assertEquals("target_item_complete", progress.reason());
    }

    @Test
    void keepsMiningWhenTargetItemIncreasesButQuantityIsShort() {
        MiningDropProgress progress = MiningDropProgress.analyze(
            "minecraft:redstone",
            0,
            4,
            9,
            "minecraft:redstone",
            4,
            4,
            0,
            true,
            null
        );

        assertFalse(progress.complete());
        assertEquals(4, progress.gainedCount());
        assertEquals(5, progress.remainingCount());
        assertEquals("target_item_increased", progress.reason());
    }

    @Test
    void ExplainsRawOreDropWhenTargetIsSmeltedItem() {
        MiningDropProgress progress = MiningDropProgress.analyze(
            "minecraft:gold_ingot",
            0,
            0,
            1,
            "minecraft:raw_gold",
            1,
            1,
            0,
            true,
            null
        );

        assertFalse(progress.complete());
        assertEquals("drop_not_target:minecraft:raw_gold", progress.reason());
    }

    @Test
    void includesBreakFailureReasonWhenBlockWasNotDestroyed() {
        MiningDropProgress progress = MiningDropProgress.analyze(
            "minecraft:diamond",
            0,
            0,
            1,
            "minecraft:air",
            0,
            0,
            0,
            false,
            "wrong_tool_tier:minecraft:iron_pickaxe"
        );

        assertFalse(progress.complete());
        assertEquals("break_failed:wrong_tool_tier:minecraft:iron_pickaxe", progress.reason());
    }

    @Test
    void reportsUncollectedDropWhenInventoryCouldNotAcceptEverything() {
        MiningDropProgress progress = MiningDropProgress.analyze(
            "minecraft:redstone",
            0,
            1,
            4,
            "minecraft:redstone",
            4,
            1,
            3,
            true,
            null
        );

        assertFalse(progress.complete());
        assertEquals(1, progress.insertedCount());
        assertEquals(3, progress.uncollectedCount());
        assertEquals("drop_uncollected:3", progress.reason());
    }
}
