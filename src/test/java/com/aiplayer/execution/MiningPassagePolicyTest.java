package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningPassagePolicyTest {
    @Test
    void descendingStairClearsFeetBeforeHeadAndEntry() {
        MiningPassagePolicy.Decision decision = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_DOWN,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:stone",
            true
        ));

        assertEquals(MiningPassagePolicy.Action.DIG_FEET, decision.action());
        assertTrue(decision.reason().contains("clear_feet"));
    }

    @Test
    void descendingStairClearsHeadBeforeEntryAfterFeetIsOpen() {
        MiningPassagePolicy.Decision decision = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_DOWN,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:stone",
            true
        ));

        assertEquals(MiningPassagePolicy.Action.DIG_HEAD, decision.action());
    }

    @Test
    void descendingStairClearsEntryHeadAfterFeetAndHeadAreOpen() {
        MiningPassagePolicy.Decision decision = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_DOWN,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:stone",
            true
        ));

        assertEquals(MiningPassagePolicy.Action.DIG_ENTRY_HEAD, decision.action());
    }

    @Test
    void horizontalTunnelStillRequiresTwoHighSpace() {
        MiningPassagePolicy.Decision feetFirst = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_LEVEL,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:deepslate",
            false,
            true,
            null,
            "minecraft:deepslate",
            false,
            true,
            null,
            "minecraft:deepslate",
            true
        ));
        MiningPassagePolicy.Decision headSecond = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_LEVEL,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:deepslate",
            false,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:deepslate",
            true
        ));

        assertEquals(MiningPassagePolicy.Action.DIG_FEET, feetFirst.action());
        assertEquals(MiningPassagePolicy.Action.DIG_HEAD, headSecond.action());
    }

    @Test
    void horizontalTunnelRejectsUnsafeSupportBeforeDiggingBlockedFeet() {
        MiningPassagePolicy.Decision decision = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_LEVEL,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:stone",
            false,
            true,
            null,
            "minecraft:air",
            false
        ));

        assertEquals(MiningPassagePolicy.Action.BLOCKED, decision.action());
        assertTrue(decision.reason().contains("support"));
    }

    @Test
    void reportsSpecificBlockingPartForUnbreakablePassageBlocks() {
        MiningPassagePolicy.Decision feet = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_LEVEL,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:bedrock",
            false,
            false,
            null,
            "minecraft:deepslate",
            true
        ));
        MiningPassagePolicy.Decision head = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_LEVEL,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:bedrock",
            false,
            false,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:deepslate",
            true
        ));

        assertEquals(MiningPassagePolicy.Action.BLOCKED, feet.action());
        assertTrue(feet.reason().contains("feet=minecraft:bedrock"));
        assertEquals(MiningPassagePolicy.Action.BLOCKED, head.action());
        assertTrue(head.reason().contains("head=minecraft:bedrock"));
    }

    @Test
    void mapsClearanceActionsToExpectedStandRelativeBlocks() {
        BlockPos stand = new BlockPos(10, 40, -3);

        assertEquals(new BlockPos(10, 42, -3), MiningPassagePolicy.digTarget(
            stand,
            new MiningPassagePolicy.Decision(MiningPassagePolicy.Action.DIG_ENTRY_HEAD, "test")
        ));
        assertEquals(new BlockPos(10, 41, -3), MiningPassagePolicy.digTarget(
            stand,
            new MiningPassagePolicy.Decision(MiningPassagePolicy.Action.DIG_HEAD, "test")
        ));
        assertEquals(stand, MiningPassagePolicy.digTarget(
            stand,
            new MiningPassagePolicy.Decision(MiningPassagePolicy.Action.DIG_FEET, "test")
        ));
    }

    @Test
    void blocksUnsafeSupportAndDangerousDigTargets() {
        MiningPassagePolicy.Decision unsafeSupport = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_LEVEL,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:air",
            false
        ));
        MiningPassagePolicy.Decision lavaAhead = MiningPassagePolicy.nextClearance(new MiningPassagePolicy.Input(
            MiningPassagePolicy.MoveIntent.FORWARD_DOWN,
            "minecraft:stone",
            false,
            true,
            "lava",
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:air",
            true,
            true,
            null,
            "minecraft:stone",
            true
        ));

        assertEquals(MiningPassagePolicy.Action.BLOCKED, unsafeSupport.action());
        assertTrue(unsafeSupport.reason().contains("support"));
        assertEquals(MiningPassagePolicy.Action.BLOCKED, lavaAhead.action());
        assertTrue(lavaAhead.reason().contains("entry_head_danger"));
    }
}
