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
    void descendingStairClearsFeetHeadAndEntryHeadBeforeMoving() {
        BlockPos current = new BlockPos(0, 64, 0);
        BlockPos stand = new BlockPos(1, 63, 0);

        MiningMovementSimulator.Result feet = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningMovementSimulator.Result head = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.empty(),
            MiningMovementSimulator.BlockInfo.support("minecraft:stone", true)
        );
        MiningMovementSimulator.Result entry = simulate(
            current,
            stand,
            MiningMovementSimulator.BlockInfo.block("minecraft:stone"),
            MiningMovementSimulator.BlockInfo.empty(),
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

        assertEquals(MiningPassagePolicy.MoveIntent.FORWARD_DOWN, feet.intent());
        assertEquals(MiningMovementSimulator.Action.DIG_FEET, feet.action());
        assertEquals(MiningMovementSimulator.Action.DIG_HEAD, head.action());
        assertEquals(MiningMovementSimulator.Action.DIG_ENTRY_HEAD, entry.action());
        assertEquals(stand.above(2), entry.target());
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
