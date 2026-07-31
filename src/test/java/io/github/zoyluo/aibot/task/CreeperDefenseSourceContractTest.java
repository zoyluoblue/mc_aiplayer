package io.github.zoyluo.aibot.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the dedicated Creeper owner to strict-survival observation and authority boundaries. */
class CreeperDefenseSourceContractTest {
    private static final Path TASK = Path.of(
            "src/main/java/io/github/zoyluo/aibot/task/CreeperDefenseTask.java");
    private static final Path WATCHER = Path.of(
            "src/main/java/io/github/zoyluo/aibot/task/DangerWatcher.java");

    @Test
    void entityFactsStayBehindTheObservableSixteenBlockBoundary() throws IOException {
        String source = Files.readString(TASK);
        assertTrue(source.contains("private static final int CREEPER_SCAN_RANGE = 16;"));
        int scan = source.indexOf("private static List<VisibleCreeper>"
                + " observableCreeperSnapshots");
        int observable = source.indexOf(
                "entity -> ObservableWorldQuery.canObserveEntity(bot, entity)", scan);
        int fuse = source.indexOf("entity.getClientFuseTime(1.0F)", observable);
        int charged = source.indexOf("entity.isCharged()", fuse);
        assertTrue(scan >= 0 && observable > scan && fuse > observable && charged > fuse,
                "fuse/charged facts must be read only after exact entity observation");
        assertFalse(source.contains("getOtherEntities"),
                "wall admission must not discover hidden entity occupancy");
    }

    @Test
    void fastStepAndBarrierReadsProveTheirCellsFirst() throws IOException {
        String source = Files.readString(TASK);
        int fastStep = source.indexOf("private boolean tryFastStepAway");
        int feet = source.indexOf("ObservableWorldQuery.canObserveCell(bot, target)", fastStep);
        int head = source.indexOf(
                "ObservableWorldQuery.canObserveCell(bot, target.up())", feet);
        int support = source.indexOf(
                "ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, target.down())", head);
        int physicalStep = source.indexOf("FakePlayerMotion.stepToStandable", support);
        assertTrue(fastStep >= 0 && feet > fastStep && head > feet
                        && support > head && physicalStep > support,
                "feet, head and support must all be observable before standability reads");

        int barrier = source.indexOf(
                "private static boolean isObservablePhysicalBarrierCell");
        int observed = source.indexOf(
                "ObservableWorldQuery.canObserveBlockWithInsetFaces(bot, pos)", barrier);
        int state = source.indexOf("getBlockState(pos)", observed);
        assertTrue(barrier >= 0 && observed > barrier && state > observed,
                "owned barrier state must cross observation before world-state reads");
    }

    @Test
    void recentRiskAndOriginContractsCannotRegressToSingleSourceOrTaskTypes()
            throws IOException {
        String source = Files.readString(TASK);
        assertTrue(source.contains("private static final int RISK_MEMORY_TICKS = 100;"));
        assertTrue(source.contains("Map<UUID, RiskMemory> recentRisks"));
        assertTrue(source.contains("outward >= AWAY_PROGRESS_DISTANCE"),
                "stall progress must be projected away from the remembered source");

        String watcher = Files.readString(WATCHER);
        int creeperBranch = watcher.indexOf(
                "Optional<CreeperDefenseTask.ObservedCreeper> visibleCreeper");
        int healingBranch = watcher.indexOf("// A healing EatTask", creeperBranch);
        assertTrue(creeperBranch >= 0 && healingBranch > creeperBranch);
        String authority = watcher.substring(creeperBranch, healingBranch);
        assertTrue(authority.contains(".map(TaskOrigin::safety)"),
                "Creeper replacement must use TaskOrigin authority");
        assertFalse(authority.contains("current instanceof EvadeTask")
                        || authority.contains("current instanceof CombatTask")
                        || authority.contains("current instanceof EatTask"),
                "task classes must not decide whether user work is preserved");
    }
}
