package io.github.zoyluo.aibot.mode;

import io.github.zoyluo.aibot.goal.StructureVerifier;
import io.github.zoyluo.aibot.task.BlueprintSchema;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks down the small, reviewed set of production adapters that may invoke privileged primitives. */
class PrivilegedBoundarySourceTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");
    private static final Pattern DIRECT_TELEPORT = Pattern.compile("\\.teleport\\s*\\(");

    @Test
    void directTeleportsStayInsideReviewedAdapters() throws IOException {
        Set<String> expected = Set.of(
                "action/ActionPack.java",
                "manager/AIPlayerManager.java",
                "mode/FakePlayerMotion.java",
                "network/AIBotServerNetworking.java",
                "task/DangerWatcher.java",
                "task/GatherQuotaTask.java",
                "task/HuntTask.java",
                "task/NavSafetyNet.java");
        Map<String, String> matches = matchingSources(DIRECT_TELEPORT);
        assertEquals(expected, matches.keySet(),
                "A new direct teleport requires an explicit capability or lifecycle-adapter review");

        for (Map.Entry<String, String> entry : matches.entrySet()) {
            if (entry.getKey().equals("manager/AIPlayerManager.java")
                    || entry.getKey().equals("mode/FakePlayerMotion.java")) {
                continue;
            }
            assertTrue(entry.getValue().contains("CapabilityRuntime"), entry.getKey());
            assertTrue(entry.getValue().contains("EMERGENCY_TELEPORT")
                    || entry.getValue().contains("MANUAL_TELEPORT"), entry.getKey());
        }

        String actionPack = read("action/ActionPack.java");
        int snapMethod = actionPack.indexOf("boolean snapPlayerToNearestStandable");
        int currentStandable = actionPack.indexOf("Standability.isStandable(world, current)", snapMethod);
        int emergencyGate = actionPack.indexOf("CapabilityRuntime.decide", currentStandable);
        assertTrue(snapMethod >= 0 && currentStandable > snapMethod && emergencyGate > currentStandable,
                "ordinary pathfinding from a valid start must run before the emergency-teleport gate");
    }

    @Test
    void digDownRelocationUsesObservedFailureInsteadOfHiddenColumnScan() throws IOException {
        String digDown = read("task/DigDownTask.java");
        assertFalse(digDown.contains("dryColumn("),
                "DigDown must not pre-scan buried fluid columns in strict survival");

        int onStart = digDown.indexOf("protected void onStart");
        int relocation = digDown.indexOf("private void prepareEntryRelocation", onStart);
        int descent = digDown.indexOf("private void initializeFreshDescent", relocation);
        assertTrue(onStart >= 0 && relocation > onStart && descent > relocation);
        String entrySelection = digDown.substring(onStart, descent);
        assertTrue(entrySelection.contains("EpisodeMemory.INSTANCE.isExcluded"),
                "entry relocation must be gated by an observed episode failure");
        assertTrue(entrySelection.contains("Standability.isStandable"),
                "replacement entries still require a factual surface landing");
        assertFalse(entrySelection.contains("getFluidState"),
                "entry selection must not inspect buried fluid state");

        int remember = digDown.indexOf("private void rememberUnusableEntry");
        int beginReturn = digDown.indexOf("private void beginReturn", remember);
        assertTrue(remember >= 0 && beginReturn > remember);
        String failureMemory = digDown.substring(remember, beginReturn);
        assertTrue(failureMemory.contains("ReturnOutcome.WALLED"));
        assertTrue(failureMemory.contains("ReturnOutcome.NO_PROGRESS")
                        && failureMemory.contains("horizontalMode"),
                "only an observed horizontal no-progress may retire the local entry");
        assertTrue(failureMemory.contains("EpisodeMemory.INSTANCE.exclude"));
    }

    @Test
    void resourceAndEntityDiscoveryUsesObservableBoundary() throws IOException {
        for (String relative : Set.of(
                "action/HarvestCore.java",
                "brain/ToolRegistry.java",
                "goal/GoalSnapshotCollector.java",
                "log/DiagnosticLogger.java",
                "mining/OreProspector.java",
                "mining/OreScan.java",
                "perception/PerceptionCollector.java",
                "task/ContainerTask.java",
                "task/CraftTask.java",
                "task/DangerWatcher.java",
                "task/FarmTask.java",
                "task/FishTask.java",
                "task/OreDigTask.java",
                "task/RecoverDropsTask.java",
                "task/ResupplyTask.java",
                "task/SiteFinder.java",
                "task/SleepTask.java",
                "task/SmeltTask.java",
                "task/StockpileTask.java",
                "task/StripMineTask.java")) {
            assertTrue(read(relative).contains("ObservableWorldQuery"), relative);
        }

        String prospector = read("mining/OreProspector.java");
        assertTrue(prospector.contains("nearestObservable"));
        assertTrue(prospector.contains("private static BlockPos nearestRaw"));
        assertTrue(prospector.indexOf("ObservableWorldQuery.canObserveBlock(bot, pos)")
                < prospector.indexOf("BlockState state = world.getBlockState(pos)"));

        String oreScan = read("mining/OreScan.java");
        assertFalse(oreScan.contains("veinFrom(World"), "raw world-only vein scans must not be public");
        assertTrue(oreScan.contains("veinFrom(AIPlayerEntity"));
    }

    @Test
    void directMutationFallbacksKeepReachAndVisibilityGuards() throws IOException {
        String build = read("action/BuildAction.java");
        assertTrue(build.contains("target_out_of_reach"));
        assertTrue(build.contains("target_not_visible"));
        assertFalse(build.contains("ObservableWorldQuery.canObserveBlock"),
                "face-center observation must not pre-empt the exact inset click sampler");
        assertTrue(build.contains("ObservableWorldQuery.canObserveCell"));
        assertTrue(build.contains("canPlace(placementState, pos, ShapeContext.of(player))"),
                "direct placement fallback must respect entity/world collision");
        assertTrue(build.contains("player.raycast(sampleRange, 1.0F, false)"),
                "exact placement rays must use the perception-and-interaction bounded range");
        assertTrue(build.contains("hit.getSide() != face"));
        assertTrue(build.contains("OperatingProfile.STRICT_SURVIVAL"),
                "strict mode must not use direct setBlockState placement fallback");

        String buildTask = read("task/BuildTask.java");
        assertTrue(buildTask.contains("StructureVerifier.verify"));
        assertTrue(buildTask.contains("structure_incomplete"),
                "a best-effort blueprint must not report completion without exact verification");
        assertEquals(2, occurrences(buildTask, "isObservableStandable(bot, candidate)"),
                "both work-pose scans must cross the observable-world boundary");
        assertEquals(1, occurrences(buildTask, "Standability.isStandable"),
                "raw standability must stay inside the observable work-pose adapter");
        assertFalse(StructureVerifier.matches(
                        null,
                        BlockPos.ORIGIN,
                        new BlueprintSchema.BlockPlacement(0, 0, 0, "invalid id", null)),
                "invalid blueprint IDs must fail closed before touching world state");

        String container = read("task/ContainerTask.java");
        assertTrue(container.contains("squaredDistanceTo(containerPos.toCenterPos()) > REACH_SQUARED"));
        assertTrue(container.contains("ObservableWorldQuery.canObserveBlock(bot, containerPos)"));

        assertTrue(matchingSources(Pattern.compile("setTimeOfDay\\s*\\(")).isEmpty(),
                "sleep tasks must respect the server's vanilla sleep quorum");
    }

    @Test
    void miningServiceDefersRememberedDepotReadsUntilObservableReach() throws IOException {
        String service = read("task/MiningServiceTask.java");

        int onStart = service.indexOf("protected void onStart");
        int onTick = service.indexOf("protected void onTick", onStart);
        assertTrue(onStart >= 0 && onTick > onStart);
        assertFalse(service.substring(onStart, onTick).contains("ContainerAction.resolve"),
                "restoring a remembered depot must not read a remote container");

        int resolveDepot = service.indexOf("private BlockPos resolveDepot");
        int approach = service.indexOf("private ActionResult startDepotApproach", resolveDepot);
        assertTrue(resolveDepot >= 0 && approach > resolveDepot);
        String resolver = service.substring(resolveDepot, approach);
        assertFalse(resolver.contains("ContainerAction.resolve"));
        assertFalse(resolver.contains("nearestContainerNear"));

        int walkToDepot = service.indexOf("private void walkToDepot");
        int deposit = service.indexOf("private void deposit", walkToDepot);
        String approachBody = service.substring(walkToDepot, deposit);
        assertTrue(approachBody.indexOf("canInteractWithDepot(bot, depot)")
                        < approachBody.indexOf("ContainerAction.resolve(bot, depot)"),
                "the remembered depot must cross the reach/visibility guard before resolution");
        int serviceTool = service.indexOf("private void serviceTool", deposit);
        String depositBody = service.substring(deposit, serviceTool);
        assertTrue(depositBody.indexOf("canInteractWithDepot(bot, depot)")
                        < depositBody.indexOf("ContainerAction.resolve(bot, depot)"),
                "container transfer must re-check reach and visibility");

        int interactionGuard = service.indexOf("private static boolean canInteractWithDepot");
        assertTrue(interactionGuard >= 0);
        String guard = service.substring(interactionGuard);
        assertTrue(guard.contains("squaredDistanceTo(pos.toCenterPos()) <= REACH_SQUARED"));
        assertTrue(guard.contains("ObservableWorldQuery.canObserveCell(bot, pos)"));
    }

    @Test
    void productionSourceSetDoesNotContainVerificationHarness() throws IOException {
        assertFalse(Files.exists(MAIN.resolve("command/AIBotTestSubcommand.java")));
        assertFalse(Files.exists(MAIN.resolve("command/AIBotVerifySubcommand.java")));
    }

    private static Map<String, String> matchingSources(Pattern pattern) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (var paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(path);
                if (pattern.matcher(source).find()) {
                    result.put(MAIN.relativize(path).toString().replace('\\', '/'), source);
                }
            }
        }
        return result;
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    private static int occurrences(String source, String needle) {
        return source.split(Pattern.quote(needle), -1).length - 1;
    }
}
