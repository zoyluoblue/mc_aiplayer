package io.github.zoyluo.aibot.action;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks strict placement to vanilla reach and exact, physically visible support-face hits. */
class BuildActionVisibilitySourceTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/action/BuildAction.java");

    @Test
    void supportCenterDistanceCannotRejectAReachableFaceInset() throws IOException {
        String source = Files.readString(SOURCE);
        int place = source.indexOf("public static ActionResult placeBlock(");
        int placeAt = source.indexOf("public static ActionResult placeBlockAt(", place);
        assertTrue(place >= 0 && placeAt > place);
        String body = source.substring(place, placeAt);

        assertTrue(body.contains("player.canInteractWithBlockAt(against, 0.0D)"));
        assertTrue(body.contains("exactPlacementSampleRange("));
        assertFalse(body.contains("squaredDistanceTo(against.toCenterPos())"),
                "vanilla block-box reach must not be narrowed to support-center reach");
        assertTrue(body.contains("visibleSupportFaceHit(player, against, face, sampleRange)"));
        assertTrue(body.indexOf("visibleSupportFaceHit")
                        < body.indexOf("player.canInteractWithBlockAt"),
                "external support interaction checks require exact perception proof first");
        assertTrue(body.indexOf("visibleSupportFaceHit") < body.indexOf("getBlockState(destination)"),
                "destination reads require an exact visible support-face proof first");
    }

    @Test
    void placeAtDoesNotUseFaceCenterVisibilityOrDirectMutationInStrictMode()
            throws IOException {
        String source = Files.readString(SOURCE);
        int placeAt = source.indexOf("public static ActionResult placeBlockAt(");
        int exactSampler = source.indexOf("private static BlockHitResult visibleSupportFaceHit", placeAt);
        assertTrue(placeAt >= 0 && exactSampler > placeAt);
        String body = source.substring(placeAt, exactSampler);

        assertFalse(body.contains("ObservableWorldQuery.canObserveBlock"),
                "six face-center rays must not prevent the exact edge sampler from running");
        assertFalse(body.contains("getBlockState("),
                "placeBlockAt must not inspect an unproven adjacent support");
        assertTrue(body.contains("placeBlock(player, against, direction, Hand.MAIN_HAND)"));

        int strict = body.indexOf("OperatingProfile.STRICT_SURVIVAL");
        int failedReturn = body.indexOf("return lastFailure;", strict);
        int fallback = body.indexOf("directPlaceFallback(player, pos, Hand.MAIN_HAND)");
        assertTrue(strict >= 0 && failedReturn > strict && fallback > failedReturn,
                "strict mode must return before the direct world-mutation fallback");
    }

    @Test
    void exactPlacementSampleRangeUsesTheSmallerPhysicalBoundary() {
        assertEquals(1.0D, BuildAction.exactPlacementSampleRange(0, 4.5D));
        assertEquals(2.0D, BuildAction.exactPlacementSampleRange(2, 4.5D));
        assertEquals(4.5D, BuildAction.exactPlacementSampleRange(16, 4.5D));
    }

    @Test
    void realVanillaFailureSurvivesLaterInvisibleSupports() {
        ActionResult vanillaFailure = ActionResult.failed("interact_block_FAIL");
        ActionResult invisible = ActionResult.failed("support_face_not_visible");

        assertSame(vanillaFailure,
                BuildAction.preferPlacementFailure(vanillaFailure, invisible));
        assertSame(vanillaFailure,
                BuildAction.preferPlacementFailure(invisible, vanillaFailure));
    }
}
