package io.github.zoyluo.aibot.mining;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningCursorTest {
    @Test
    void roundTripsBranchAndBatchProgress() {
        MiningCursor cursor = new MiningCursor(
                MiningCursor.CURRENT_SCHEMA,
                new BlockPos(10, -59, 20),
                new BlockPos(58, -59, 20),
                3,
                7,
                19,
                192,
                4);

        assertEquals(cursor, MiningCursor.decode(cursor.encode()).orElseThrow());
    }

    @Test
    void rejectsFutureOrMalformedCheckpoints() {
        assertTrue(MiningCursor.decode(Map.of("schema", "999", "origin", "0,-59,0")).isEmpty());
        assertTrue(MiningCursor.decode(Map.of("schema", "1", "origin", "broken")).isEmpty());
    }

    @Test
    void preFirstTickCheckpointPreservesUnstartedDirectionSentinel() {
        MiningCursor beforeFirstTick = MiningCursor.initial(new BlockPos(10, -59, 20), 48);

        assertEquals(-1, beforeFirstTick.directionIndex());
        assertEquals("-1", beforeFirstTick.encode().get("direction"));
        assertEquals(beforeFirstTick, MiningCursor.decode(beforeFirstTick.encode()).orElseThrow());
    }

    @Test
    void clampsUntrustedNumericFields() {
        MiningCursor cursor = new MiningCursor(1, BlockPos.ORIGIN, BlockPos.ORIGIN,
                -5, -2, -3, 0, -4);

        assertEquals(3, cursor.directionIndex());
        assertEquals(0, cursor.legIndex());
        assertEquals(0, cursor.stepsLeft());
        assertEquals(1, cursor.legLength());
        assertEquals(0, cursor.completedBatches());
    }
}
