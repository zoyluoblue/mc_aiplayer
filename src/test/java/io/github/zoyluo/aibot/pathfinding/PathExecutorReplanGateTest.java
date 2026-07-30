package io.github.zoyluo.aibot.pathfinding;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathExecutorReplanGateTest {
    private static final Path SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/pathfinding/PathExecutor.java");
    private static final Path ACTION_PACK_SOURCE = Path.of(
            "src/main/java/io/github/zoyluo/aibot/action/ActionPack.java");

    @Test
    void unreachableExecutorCanReplanOnlyOnceUntilARealNodeAdvance() {
        PathExecutor.ReplanGate gate = new PathExecutor.ReplanGate();

        assertTrue(gate.tryAcquire(), "the first stuck event may attempt one internal replan");
        // A successful A* search only replaces the route; it does not call the reset method.
        assertFalse(gate.tryAcquire(),
                "the next timeout must escape to the caller instead of internally replanning again");

        gate.resetAfterNodeAdvance();
        assertTrue(gate.tryAcquire(), "committed node progress earns one new bounded replan");
        assertFalse(gate.tryAcquire(), "the renewed allowance is still single-use");
    }

    @Test
    void compatibilityConstructorIsFailClosedAndExplicitPolicyCannotEscalate() {
        PathExecutor compatibility = new PathExecutor(List.of(), BlockPos.ORIGIN);
        assertFalse(compatibility.replanCanPillar(),
                "legacy callers must not gain disposable pillars during a replan");
        assertFalse(compatibility.replanAllowDig(),
                "legacy callers must not gain block breaking during a replan");
        assertTrue(compatibility.protectedStoneLikeReserve() == 0,
                "legacy callers must retain the reserve=0 compatibility contract");

        PathExecutor ordinary = new PathExecutor(List.of(), BlockPos.ORIGIN, true, true);
        assertTrue(ordinary.replanCanPillar());
        assertTrue(ordinary.replanAllowDig());
        assertTrue(ordinary.protectedStoneLikeReserve() == 0);

        PathExecutor walkingOnly = new PathExecutor(List.of(), BlockPos.ORIGIN, false, false);
        assertFalse(walkingOnly.replanCanPillar());
        assertFalse(walkingOnly.replanAllowDig());

        PathExecutor scoped = new PathExecutor(
                List.of(), BlockPos.ORIGIN, true, true, 76);
        assertTrue(scoped.replanCanPillar());
        assertTrue(scoped.replanAllowDig());
        assertTrue(scoped.protectedStoneLikeReserve() == 76,
                "the executor must own the mission reserve for its entire lifetime");

        PathExecutor normalized = new PathExecutor(
                List.of(), BlockPos.ORIGIN, true, true, -1);
        assertTrue(normalized.protectedStoneLikeReserve() == 0,
                "negative external reserve input must normalize to the compatibility floor");
    }

    @Test
    void actionPackThreadsEachInitialMovementCeilingIntoItsExecutor() throws IOException {
        String source = Files.readString(ACTION_PACK_SOURCE);
        String dig = between(source, "public ActionResult startDigPathTo", "public ActionResult startPathTo");
        String routed = between(source,
                "private ActionResult startPathTo", "public BlockPos activePathGoal");

        assertTrue(dig.contains("PathExecutor.hasPlaceableBlock(player, reserve)"),
                "DIG planning must gate pillars with the caller's reserve");
        assertTrue(dig.contains(
                        "result.path(), resolvedGoal, canPillar, true, reserve"),
                "DIG execution and replanning must retain the planning reserve");
        assertTrue(routed.contains(
                        "result.path(), resolvedGoal, canPillar, allowDigFallback, reserve"),
                "ordinary and surface routes must persist their caller-specific reserve and ceiling");
        assertTrue(source.contains(
                        "return startPathTo(goal, false, false, 0);"),
                "surface and pickup paths must remain no-dig/no-pillar at startup and replan");
        assertTrue(source.contains("return startPathTo(goal, 0);"),
                "legacy ordinary paths must retain reserve=0 semantics");
        assertTrue(source.contains("return startDigPathTo(goal, 0);"),
                "legacy DIG paths must retain reserve=0 semantics");
    }

    @Test
    void executorWiresResetToAdvanceButNotSuccessfulPathSearch() throws IOException {
        String source = Files.readString(SOURCE);
        String advance = between(source, "private void advanceTo", "private int chooseWalkTargetIndex");
        String stuck = between(source, "private ActionResult handleStuck", "private void cleanup");
        String freshSuccess = between(stuck, "if (fresh.success())", "reason = reason +");

        assertTrue(advance.contains("replanGate.resetAfterNodeAdvance()"));
        assertTrue(stuck.contains("if (replanGate.tryAcquire())"));
        assertTrue(stuck.contains("hasPlaceableBlock(pack.player(), protectedStoneLikeReserve)"),
                "replan pillaring must require both the original policy and current material");
        assertTrue(stuck.contains("canPillar, replanAllowDig"),
                "replan A* must retain the executor's original digging ceiling");
        assertFalse(freshSuccess.contains("resetAfterNodeAdvance"),
                "finding a route must not restore the same executor's replan allowance");
        assertTrue(stuck.lastIndexOf("return ActionResult.failed(reason);")
                        > stuck.indexOf("if (replanGate.tryAcquire())"),
                "an exhausted gate must leave handleStuck through the terminal failure path");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start);
        assertTrue(start >= 0 && end > start,
                () -> "missing source markers: " + startMarker + " -> " + endMarker);
        return source.substring(start, end);
    }
}
