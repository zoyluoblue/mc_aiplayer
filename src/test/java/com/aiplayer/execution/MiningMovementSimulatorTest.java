package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningMovementSimulatorTest {
    @Test
    void horizontalMoveClearsFeetThenHeadBeforeMoving() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 64, 0);

        MiningMovementSimulator.Result feet = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningMovementSimulator.Result head = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningMovementSimulator.Result move = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );

        assertEquals(MiningMovementSimulator.Action.DIG_FEET, feet.action());
        assertEquals(stand, feet.target());
        assertEquals(MiningMovementSimulator.Action.DIG_HEAD, head.action());
        assertEquals(stand.above(), head.target());
        assertEquals(MiningMovementSimulator.Action.MOVE, move.action());
        assertTrue(move.readyToMove());
    }

    @Test
    void descendingStairClearsForwardSpaceBeforeLowerStepAndMoving() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 63, 0);

        MiningMovementSimulator.Result forwardFeet = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningMovementSimulator.Result entryHead = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningMovementSimulator.Result lowerStep = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningMovementSimulator.Result move = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );

        assertEquals(MiningPassagePolicy.MoveIntent.FORWARD_DOWN, forwardFeet.intent());
        assertEquals(MiningMovementSimulator.Action.DIG_HEAD, forwardFeet.action());
        assertEquals(stand.above(), forwardFeet.target());
        assertEquals(MiningMovementSimulator.Action.DIG_ENTRY_HEAD, entryHead.action());
        assertEquals(stand.above(2), entryHead.target());
        assertEquals(MiningMovementSimulator.Action.DIG_FEET, lowerStep.action());
        assertEquals(stand, lowerStep.target());
        assertEquals(MiningMovementSimulator.Action.MOVE, move.action());
    }

    @Test
    void missingSupportRequestsSupportPlacementInsteadOfGenericBlock() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 64, 0);

        MiningMovementSimulator.Result result = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty()
        );

        assertEquals(MiningMovementSimulator.Action.PLACE_SUPPORT, result.action());
        assertEquals(stand.below(), result.target());
        assertTrue(result.reason().contains("support"));
    }

    @Test
    void dangerBlocksMovementBeforeDigging() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 63, 0);

        MiningMovementSimulator.Result result = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.danger("minecraft:lava", "lava"),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );

        assertEquals(MiningMovementSimulator.Action.BLOCKED, result.action());
        assertTrue(result.reason().contains("entry_head_danger"));
    }

    @Test
    void descendingStairCanClearFallingBlocksWhenSupportIsStable() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 63, 0);

        MiningMovementSimulator.Result head = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.block("minecraft:gravel"),
            MiningMovementSimulator.BlockInfo.block("minecraft:sand"),
            MiningMovementSimulator.BlockInfo.support("minecraft:gravel", true)
        );
        MiningMovementSimulator.Result feet = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.block("minecraft:sand"),
            MiningMovementSimulator.BlockInfo.support("minecraft:gravel", true)
        );

        assertEquals(MiningMovementSimulator.Action.DIG_HEAD, head.action());
        assertEquals(stand.above(), head.target());
        assertEquals(MiningMovementSimulator.Action.DIG_FEET, feet.action());
        assertEquals(stand, feet.target());
    }

    private static MiningMovementSimulator.Result simulate(
        BlockPos current,
        BlockPos stand,
        MiningMovementSimulator.BlockInfo entryHead,
        MiningMovementSimulator.BlockInfo head,
        MiningMovementSimulator.BlockInfo feet,
        MiningMovementSimulator.BlockInfo support
    ) {
        return MiningMovementSimulator.simulate(new MiningMovementSimulator.Input(
            current,
            stand,
            entryHead,
            head,
            feet,
            support
        ));
    }
}
