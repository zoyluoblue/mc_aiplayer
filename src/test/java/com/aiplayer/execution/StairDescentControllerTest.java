package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StairDescentControllerTest {
    @Test
    void descendsUntilTargetHeightIsReached() {
        StairDescentController controller = StairDescentController.start(new BlockPos(0, 64, 0), 32, Direction.EAST);

        assertTrue(controller.shouldDescend(33));
        assertFalse(controller.shouldDescend(32));
        assertEquals(Direction.EAST, controller.direction());
    }

    @Test
    void recordsSuccessfulStepsAndLastSafeStand() {
        StairDescentController controller = StairDescentController.start(new BlockPos(0, 64, 0), 32, Direction.NORTH);

        controller.recordFailure("blocked_front");
        controller.recordStep(new BlockPos(1, 63, 0));

        assertEquals(1, controller.successfulSteps());
        assertEquals(0, controller.failures());
        assertEquals(new BlockPos(1, 63, 0), controller.lastSafeStand());
        assertEquals("none", controller.lastFailureReason());
    }

    @Test
    void nextStepIsOneHorizontalAndOneDownStairShape() {
        StairDescentController controller = StairDescentController.start(new BlockPos(0, 64, 0), 32, Direction.EAST);

        StairDescentController.StairStepTargets targets = controller.nextStep(new BlockPos(0, 64, 0));

        assertEquals(Direction.EAST, targets.direction());
        assertEquals(new BlockPos(1, 65, 0), targets.entryHead());
        assertEquals(new BlockPos(1, 64, 0), targets.horizontal());
        assertEquals(new BlockPos(1, 63, 0), targets.vertical());
        assertEquals(new BlockPos(1, 63, 0), targets.stand());
    }

    @Test
    void previewCanEvaluateAlternateDirectionsWithoutRotatingController() {
        StairDescentController controller = StairDescentController.start(new BlockPos(0, 64, 0), 32, Direction.NORTH);

        StairDescentController.StairStepTargets preview = controller.preview(new BlockPos(0, 64, 0), Direction.WEST);

        assertEquals(Direction.WEST, preview.direction());
        assertEquals(new BlockPos(-1, 64, 0), preview.horizontal());
        assertEquals(new BlockPos(-1, 63, 0), preview.stand());
        assertEquals(Direction.NORTH, controller.direction());
    }

    @Test
    void acceptedPreviewDirectionBecomesControllerDirectionBeforeFailureRotation() {
        StairDescentController controller = StairDescentController.start(new BlockPos(0, 64, 0), 32, Direction.NORTH);

        controller.preview(new BlockPos(0, 64, 0), Direction.EAST);
        controller.acceptDirection(Direction.EAST);

        assertEquals(Direction.EAST, controller.direction());
        assertEquals(Direction.SOUTH, controller.recordFailure("accepted_direction_blocked"));
    }

    @Test
    void clearancePhaseClearsForwardSpaceBeforeLowerStep() {
        assertEquals(
            StairDescentController.ClearancePhase.CLEAR_HORIZONTAL,
            StairDescentController.clearancePhase(false, false, false)
        );
        assertEquals(
            StairDescentController.ClearancePhase.CLEAR_HORIZONTAL,
            StairDescentController.clearancePhase(true, false, false)
        );
        assertEquals(
            StairDescentController.ClearancePhase.CLEAR_ENTRY_HEAD,
            StairDescentController.clearancePhase(false, true, false)
        );
        assertEquals(
            StairDescentController.ClearancePhase.DIG_DOWN,
            StairDescentController.clearancePhase(true, true, false)
        );
        assertEquals(
            StairDescentController.ClearancePhase.MOVE,
            StairDescentController.clearancePhase(true, true, true)
        );
    }

    @Test
    void rotatesDirectionAndRequestsRelocationAfterRepeatedFailures() {
        StairDescentController controller = StairDescentController.start(new BlockPos(0, 64, 0), 32, Direction.NORTH);

        assertEquals(Direction.EAST, controller.recordFailure("blocked_1"));
        assertEquals(Direction.SOUTH, controller.recordFailure("blocked_2"));
        assertEquals(Direction.WEST, controller.recordFailure("blocked_3"));
        assertFalse(controller.shouldRelocateStart());
        assertEquals(Direction.NORTH, controller.recordFailure("blocked_4"));
        assertTrue(controller.shouldRelocateStart());
        assertEquals("blocked_4", controller.lastFailureReason());
    }

    @Test
    void retargetsAfterBranchLayerShift() {
        StairDescentController controller = StairDescentController.start(new BlockPos(0, 32, 0), 16, Direction.NORTH);
        controller.recordFailure("old_layer_blocked");

        controller.retarget(0);

        assertEquals(0, controller.targetY());
        assertTrue(controller.shouldDescend(5));
        assertFalse(controller.shouldDescend(0));
        assertEquals(0, controller.failures());
        assertEquals("none", controller.lastFailureReason());
    }
}
