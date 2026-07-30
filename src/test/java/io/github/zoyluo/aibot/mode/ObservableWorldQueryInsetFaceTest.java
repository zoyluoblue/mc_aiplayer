package io.github.zoyluo.aibot.mode;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservableWorldQueryInsetFaceTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/mode/ObservableWorldQuery.java");

    @Test
    void insetEndpointUsesDeterministicTangentsInsideTheRequestedFace() {
        BlockPos pos = new BlockPos(10, 20, 30);

        assertEquals(new Vec3d(10.999D, 20.125D, 30.875D),
                ObservableWorldQuery.insetFaceEndpoint(
                        pos, Direction.EAST, -0.375D, 0.375D));
        assertEquals(new Vec3d(10.125D, 20.001D, 30.875D),
                ObservableWorldQuery.insetFaceEndpoint(
                        pos, Direction.DOWN, -0.375D, 0.375D));
        assertEquals(new Vec3d(10.125D, 20.875D, 30.999D),
                ObservableWorldQuery.insetFaceEndpoint(
                        pos, Direction.SOUTH, -0.375D, 0.375D));
    }

    @Test
    void insetObservationIsExplicitShortRangeAndFluidAware() throws IOException {
        String source = Files.readString(SOURCE);
        int ordinary = source.indexOf("public static boolean canObserveBlock(");
        int inset = source.indexOf("public static boolean canObserveBlockWithInsetFaces(");
        int facePolicy = source.indexOf("private static boolean canObserveFaceAfterPolicy", inset);
        assertTrue(ordinary >= 0 && inset > ordinary && facePolicy > inset);

        String ordinaryBody = source.substring(ordinary, inset);
        assertFalse(ordinaryBody.contains("FACE_SAMPLE_OFFSETS"),
                "ordinary block observation must retain its six-ray cost");
        assertFalse(ordinaryBody.contains("canObserveBlockWithInsetFaces"));

        String insetBody = source.substring(inset, facePolicy);
        assertTrue(insetBody.contains("Math.min("));
        assertTrue(insetBody.contains("AIBotConfig.get().perception().radius()"));
        assertTrue(insetBody.contains("bot.getBlockInteractionRange()"));
        assertTrue(insetBody.contains("RaycastContext.FluidHandling.ANY"));
        assertTrue(insetBody.contains("hit.getBlockPos().equals(pos)"));
        assertTrue(insetBody.contains("hit.getSide() == direction"));
        assertTrue(insetBody.contains("FACE_SAMPLE_OFFSETS"));
    }
}
