package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExecutorArrivalTest {
    @Test
    void horizontalToleranceNeverSkipsARequiredElevationChange() {
        BlockPos current = new BlockPos(68, 231, -1);

        assertTrue(PathExecutor.arrivedAt(current, current));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(67, 231, -1)));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(68, 232, -1)));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(67, 232, -1)));
        assertFalse(PathExecutor.arrivedAt(current, new BlockPos(67, 233, 0)));
    }
}
