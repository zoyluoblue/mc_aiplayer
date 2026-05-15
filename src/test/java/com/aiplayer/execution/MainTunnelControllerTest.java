package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTunnelControllerTest {
    @Test
    void recordsSegmentAndTotalProgress() {
        MainTunnelController controller = MainTunnelController.start(Direction.EAST, 3, 2, new BlockPos(0, 12, 0));

        controller.recordAdvance(new BlockPos(1, 12, 0));
        controller.recordAdvance(new BlockPos(2, 12, 0));

        assertEquals(2, controller.segmentBlocks());
        assertEquals(2, controller.totalBlocks());
        assertFalse(controller.segmentComplete());
        assertEquals(new BlockPos(2, 12, 0), controller.lastSafeStand());
    }

    @Test
    void segmentCompletionRequestsRescan() {
        MainTunnelController controller = MainTunnelController.start(Direction.NORTH, 2, 1, BlockPos.ZERO);

        controller.recordAdvance(new BlockPos(0, 0, -1));
        controller.recordAdvance(new BlockPos(0, 0, -2));

        assertTrue(controller.segmentComplete());
        assertTrue(controller.shouldRescan());
        controller.resetRescanBudget("rescan");
        assertFalse(controller.segmentComplete());
        assertEquals(0, controller.segmentBlocks());
    }

    @Test
    void thirtyActionsRequestRescanEvenBeforeLongSegmentCompletes() {
        MainTunnelController controller = MainTunnelController.start(Direction.NORTH, 64, 1, BlockPos.ZERO);

        for (int i = 1; i <= 29; i++) {
            controller.recordAdvance(new BlockPos(0, 0, -i));
        }
        assertFalse(controller.shouldRescan());

        controller.recordAdvance(new BlockPos(0, 0, -30));

        assertTrue(controller.shouldRescan());
        assertFalse(controller.segmentComplete());
        assertTrue(controller.toLogText().contains("actionsSinceRescan=30"));
    }

    @Test
    void turnsClockwiseUntilLimit() {
        MainTunnelController controller = MainTunnelController.start(Direction.NORTH, 4, 1, BlockPos.ZERO);

        assertTrue(controller.turn("blocked"));
        assertEquals(Direction.EAST, controller.direction());
        assertEquals(1, controller.turns());
        assertFalse(controller.turn("blocked_again"));
        assertEquals(Direction.EAST, controller.direction());
    }
}
