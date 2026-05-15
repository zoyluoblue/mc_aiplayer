package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningTunnelStepperTest {
    @Test
    void genericTunnelStepperMapsForwardHeadEntryLowerStepAndMovePhases() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 63, 0);

        MiningTunnelStepper.Plan forwardFeet = plan(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningTunnelStepper.Plan entryHead = plan(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningTunnelStepper.Plan lowerStep = plan(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningTunnelStepper.Plan move = plan(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );

        assertEquals(MiningTunnelStepper.Action.CLEAR_HEAD, forwardFeet.action());
        assertEquals("clear_head", forwardFeet.phaseName());
        assertTrue(forwardFeet.needsClearance());
        assertEquals(MiningTunnelStepper.Action.CLEAR_ENTRY_HEAD, entryHead.action());
        assertEquals(MiningTunnelStepper.Action.CLEAR_FEET, lowerStep.action());
        assertEquals(MiningTunnelStepper.Action.MOVE, move.action());
        assertTrue(move.readyToMove());
    }

    @Test
    void mapsSupportAndBlockedPhases() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 64, 0);

        MiningTunnelStepper.Plan support = plan(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty()
        );
        MiningTunnelStepper.Plan blocked = plan(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.unbreakable("minecraft:bedrock"),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );

        assertEquals(MiningTunnelStepper.Action.PLACE_SUPPORT, support.action());
        assertTrue(support.needsSupport());
        assertEquals(MiningTunnelStepper.Action.BLOCKED, blocked.action());
        assertTrue(blocked.blocked());
    }

    private static MiningTunnelStepper.Plan plan(
        BlockPos current,
        BlockPos stand,
        MiningMovementSimulator.BlockInfo entryHead,
        MiningMovementSimulator.BlockInfo head,
        MiningMovementSimulator.BlockInfo feet,
        MiningMovementSimulator.BlockInfo support
    ) {
        return MiningTunnelStepper.from(MiningMovementSimulator.simulate(new MiningMovementSimulator.Input(
            current,
            stand,
            entryHead,
            head,
            feet,
            support
        )));
    }
}
