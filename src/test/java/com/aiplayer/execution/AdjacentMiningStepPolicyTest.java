package com.aiplayer.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdjacentMiningStepPolicyTest {
    @Test
    void descendingStepUsesHorizontalProgressBeforeLanding() {
        BlockPos current = new BlockPos(-10, 50, -24);
        BlockPos target = new BlockPos(-10, 49, -25);
        Vec3 start = new Vec3(-9.5D, 50.0D, -23.5D);
        Vec3 closer = new Vec3(-9.5D, 50.0D, -24.9D);

        assertTrue(AdjacentMiningStepPolicy.progressDistanceSq(current, closer, target)
            < AdjacentMiningStepPolicy.progressDistanceSq(current, start, target));
        assertFalse(AdjacentMiningStepPolicy.reached(current, closer, target));
    }

    @Test
    void descendingStepIsReachedNearTargetAfterDropping() {
        BlockPos current = new BlockPos(-10, 50, -24);
        BlockPos target = new BlockPos(-10, 49, -25);
        Vec3 landed = new Vec3(-9.55D, 49.45D, -24.55D);

        assertTrue(AdjacentMiningStepPolicy.reached(current, landed, target));
    }

    @Test
    void descendingStepDoesNotReachFromSourceBlockEdge() {
        BlockPos current = new BlockPos(-10, 50, -24);
        BlockPos target = new BlockPos(-10, 49, -25);
        Vec3 sourceEdge = new Vec3(-9.50D, 49.60D, -24.02D);

        assertFalse(AdjacentMiningStepPolicy.reached(current, sourceEdge, target));
    }

    @Test
    void descendingStepContinuesDroppingAfterEnteringTargetColumn() {
        BlockPos current = new BlockPos(-10, 50, -25);
        BlockPos target = new BlockPos(-10, 49, -25);
        Vec3 aboveTarget = new Vec3(-9.55D, 50.0D, -24.55D);

        assertFalse(AdjacentMiningStepPolicy.reached(current, aboveTarget, target));
        assertTrue(AdjacentMiningStepPolicy.stepVelocity(current, aboveTarget, Vec3.ZERO, target).y < 0.0D);
    }

    @Test
    void descendingStepDoesNotReachOnlyBecauseBlockPositionChanged() {
        BlockPos target = new BlockPos(-10, 49, -25);
        Vec3 stillTooHigh = new Vec3(-9.55D, 49.9D, -24.55D);

        assertFalse(AdjacentMiningStepPolicy.reached(target, stillTooHigh, target, true));
    }

    @Test
    void descendingStepStillDropsWhenBlockPositionAlreadyTarget() {
        BlockPos target = new BlockPos(-10, 49, -25);
        Vec3 stillTooHigh = new Vec3(-9.55D, 49.9D, -24.55D);

        assertTrue(AdjacentMiningStepPolicy.stepVelocity(target, stillTooHigh, Vec3.ZERO, target, true).y < 0.0D);
    }

    @Test
    void descendingStepKeepsWantedYLevelUntilCloseEnoughToDrop() {
        BlockPos current = new BlockPos(-10, 50, -24);
        BlockPos target = new BlockPos(-10, 49, -25);
        Vec3 position = new Vec3(-9.5D, 50.0D, -23.5D);

        Vec3 wanted = AdjacentMiningStepPolicy.wantedPosition(current, position, target);
        Vec3 farVelocity = AdjacentMiningStepPolicy.stepVelocity(current, position, Vec3.ZERO, target);
        Vec3 nearVelocity = AdjacentMiningStepPolicy.stepVelocity(
            current,
            new Vec3(-9.55D, 50.0D, -24.55D),
            Vec3.ZERO,
            target
        );

        assertEquals(position.y, wanted.y);
        assertEquals(0.0D, farVelocity.y);
        assertTrue(nearVelocity.y < 0.0D);
    }

    @Test
    void oneStepTargetAcceptsOnlyCurrentAdjacentStep() {
        BlockPos current = new BlockPos(-442, 110, -301);
        BlockPos nextStair = new BlockPos(-442, 109, -302);
        BlockPos staleAfterEntityMoved = new BlockPos(-442, 109, -302);

        assertTrue(AdjacentMiningStepPolicy.isOneStepTarget(current, nextStair));
        assertFalse(AdjacentMiningStepPolicy.isOneStepTarget(new BlockPos(-442, 112, -301), staleAfterEntityMoved));
    }
}
