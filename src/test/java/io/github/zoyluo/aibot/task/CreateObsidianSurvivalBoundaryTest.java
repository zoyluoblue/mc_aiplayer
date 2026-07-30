package io.github.zoyluo.aibot.task;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the obsidian pipeline to observable, vanilla player interactions. */
class CreateObsidianSurvivalBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java/io/github/zoyluo/aibot");

    @Test
    void createObsidianNeverSynthesizesBlocksFluidsOrBucketInventory() throws IOException {
        String task = read("task/CreateObsidianTask.java");

        assertFalse(task.contains("setBlockState("));
        assertFalse(task.contains("breakBlock("));
        assertFalse(task.contains("InventoryAction.removeItems"));
        assertFalse(task.contains("InventoryAction.giveItem"));
        assertFalse(task.contains("FarmAction.placeWater"));
        assertFalse(task.contains("FarmAction.fillBucket"));

        assertTrue(task.contains("BucketAction.fillWaterSource"));
        assertTrue(task.contains("BucketAction.placeWater"));
        assertTrue(task.contains("ObservableWorldQuery.canObserveBlock"));
        assertTrue(task.contains("ObservableWorldQuery.canObserveCell"));
        assertTrue(task.contains("nearestObservableCell"),
                "fluid and pit-floor targets must be read only after a visible-cell ray");
        assertTrue(task.contains("obsidianStandHint"));
        assertTrue(task.contains("create_obsidian_return_to_view_failed"),
                "a known formed block must be re-approached before temporary occlusion rejects it");
        assertTrue(task.contains("bot.getBlockPos().getY() == target.getY()"),
                "the horizontal walk controller must not claim a vertical final step");
        assertTrue(task.contains("elevatedPourStand"),
                "a dry observation ledge above the pool is a valid close work pose");
        assertTrue(task.contains("pickupPos"));
        assertTrue(task.contains("pending_pickup_pos"));
        assertTrue(task.contains("pending_pickup_inventory"));
        assertTrue(task.contains("active_break_pos"));
        assertTrue(task.contains("active_break_inventory"),
                "a restart between block break and pickup must retain the pre-break inventory ledger");
        assertTrue(task.contains("create_obsidian_pickup_confirmed"),
                "a broken block must remain pending until vanilla inventory gain is confirmed");
        assertTrue(task.contains("Phase.PROTECT_PICKUP"));
        assertTrue(task.contains("create_obsidian_pickup_protected"),
                "the opened cell must receive real water before hidden side lava can burn its drop");
        assertTrue(task.contains("create_obsidian_pickup_protection_unavailable"),
                "lack of a clickable support face must preserve the live physical pickup ledger");
        assertTrue(task.contains("create_obsidian_pickup_path_failed"),
                "strict pickup must physically approach the observed break position");
        assertTrue(task.contains("stepTowardPickupCell"));
        assertTrue(task.contains("PICKUP_MICROSTEP_RANGE = 3"),
                "pickup endpoint recovery must remain a tightly bounded adjacent adapter");
        assertTrue(task.contains("obsidian_pickup_collision_align"));
        assertTrue(task.contains("isSafePickupCollisionCell"),
                "exact pickup microsteps must validate their final collision cell");
        assertTrue(task.contains("create_obsidian_pickup_no_progress_endpoint"));
        assertTrue(task.contains("!Standability.isStandable(bot.getServerWorld(), target)"),
                "pickup pathing must reject a raw non-standable endpoint before A* can snap it");
        assertTrue(task.contains("resolved.equals(current)"),
                "pickup pathing must reject a resolved no-op endpoint");
        assertTrue(task.contains("Phase.RETURN_TO_RIM"));
        assertTrue(task.contains("return_rim"),
                "restart recovery must preserve the dry rim before clearing work targets");
        assertTrue(task.contains("durableReturnRim"));
        assertTrue(task.contains("lostSupport"),
                "a pose supported by the just-mined block must not become the return waypoint");
        assertTrue(task.contains("Phase.PROTECT_OBSIDIAN"));
        assertTrue(task.contains("create_obsidian_protection_placed"),
                "every existing pool block must be water-protected before its drop is exposed");
        assertTrue(task.contains("PourPlan topProtection = new PourPlan(lava, Direction.UP)"),
                "existing obsidian must support the vanilla top-face water protection pattern");
        assertTrue(task.contains("PROTECTION_SPREAD_TICKS"),
                "protection water must receive scheduled fluid ticks before source recovery");
        assertTrue(task.contains("protectionPrepared = true"),
                "the exact washed target must remain eligible for dry mining after drainage");
        assertTrue(task.contains("obsidianStandHint = standPos.toImmutable()"),
                "source recovery must retain the stand selected for the actual pour geometry");
        assertTrue(task.contains("create_obsidian_recover_before_reset"),
                "a target reset must first discharge the live water-source obligation");
        assertTrue(task.contains("waterBucketBaseline"),
                "source recovery must restore the pre-pour water-bucket count");
        assertTrue(task.contains("create_obsidian_invalid_checkpoint"));
        assertTrue(task.contains("restoredCheckpoint.resumePhase() == Phase.RECOVER_WATER"),
                "restart must repay a live placed-source obligation before any saved phase");
        assertTrue(task.contains("budget_used"));
        assertTrue(task.contains("phase_started"));
        assertTrue(task.contains("controlsNearbyLava"));
        String danger = read("task/DangerWatcher.java");
        assertTrue(danger.contains("obsidianTask.controlsNearbyLava"),
                "controlled dry pool work must not be replaced by an impossible generic evade");
        assertTrue(task.contains("FakePlayerMotion.stepTo"));
        assertTrue(task.contains("obsidian_surface"),
                "a fake player must physically rise one validated cell before recovering the source");
        assertTrue(task.contains("getFluidState(obsidianStandHint).isEmpty()"),
                "source recovery must not oscillate back into a flooded remembered rim");
        assertTrue(task.contains("create_obsidian_water_recovery_failed"),
                "a live unrecovered source must not be forgotten as if it had drained");
        assertTrue(task.contains("isCurrentDirectMinePose"));
        assertTrue(task.contains("reuse_current"),
                "an already safe reachable rim pose must win over an empty mined hole");
        int spread = task.indexOf("protectionPrepared = true");
        int recover = task.indexOf("enter(Phase.RECOVER_WATER)", spread);
        int dryMine = task.indexOf("enter(Phase.MINE)", recover);
        assertTrue(spread >= 0 && recover > spread && dryMine > recover,
                "protection must spread, recover and drain before sustained dry mining");
        assertTrue(task.contains("create_obsidian_no_water_source"));
        assertFalse(task.contains("fail(\"create_obsidian_no_lava"),
                "an empty current LOS must enter physical prospecting instead of proving no lava");
        assertTrue(task.contains("beginSearch(bot, \"no_observable_lava\")"));
        assertTrue(task.contains("Phase.RETURN_TO_SCAN_FACE")
                        && task.contains("scan_resume_face")
                        && task.contains("returnToScanFace(bot)"),
                "a restored SCAN must return to its own durable survey pose before reading targets");
        assertTrue(task.contains("Phase.RETURN_TO_SEARCH_FACE"));
        assertTrue(task.contains("Phase.SEARCH"));
        assertTrue(task.contains("searchCursor.advanceTo(current)"),
                "only measured physical displacement may consume the branch cursor");
        int nextLeg = task.indexOf("searchCursor = searchCursor.beginNextLeg()");
        int nextLegLog = task.indexOf("BotLog.action(bot, \"create_obsidian_search_leg\"", nextLeg);
        assertTrue(nextLeg >= 0 && nextLegLog > nextLeg
                        && !task.substring(nextLeg, nextLegLog).contains("lastProgressTick"),
                "choosing a new search direction is not physical progress and must not reset the watchdog");
        assertTrue(task.contains("miner.begin(bot, pos, true)"),
                "ordinary branch excavation must use the stone/iron mining channel");
        assertTrue(task.contains("tier > ToolTier.STONE"),
                "pool prospecting must replan when stone picks are exhausted, not consume iron");
        assertTrue(task.contains("create_obsidian_search_preserve_mission_tool"),
                "branch excavation must preserve both iron and diamond mission tools");
        assertTrue(task.contains("create_obsidian_search_torch"));
        assertTrue(task.contains("combinedSearchLight"),
                "SEARCH lighting must account for both block and sky light");
        assertTrue(task.contains("create_obsidian_search_lighting_disabled")
                        && task.contains("create_obsidian_search_missing_torch"),
                "a dark branch must fail closed when physical lighting is unavailable");
        assertTrue(task.contains("blockedAllDirections()"));
        assertTrue(task.contains("create_obsidian_search_enclosed"));
        int enclosedGate = task.indexOf("if (searchCursor.blockedAllDirections())");
        int lightingGate = task.indexOf("if (!searchLightingReady(bot))", enclosedGate);
        assertTrue(enclosedGate >= 0 && lightingGate > enclosedGate,
                "factual four-direction closure must outrank missing-lighting failures");
        String executor = read("goal/GoalExecutor.java");
        int failureHandler = executor.indexOf("private void handleStepFailure");
        int enclosedTerminal = executor.indexOf(
                "reason.startsWith(\"create_obsidian_search_enclosed\")", failureHandler);
        int replan = executor.indexOf("GoalPlanner.GoalPlan fresh", failureHandler);
        assertTrue(enclosedTerminal > failureHandler && replan > enclosedTerminal,
                "a durable four-direction closure must terminate before generic replanning");
        assertTrue(task.contains("implements CheckpointableTask"));
        assertTrue(task.contains("searchCursor.markScanned()"),
                "LOS surveys must be tied to a newly reached or topology-changed work face");
        assertTrue(task.contains("need_better_tool:"));
    }

    @Test
    void bucketAdapterUsesTheVanillaItemInteractionEntryPoint() throws IOException {
        String action = read("action/BucketAction.java");

        assertFalse(action.contains("setBlockState("));
        assertFalse(action.contains("InventoryAction.removeItems"));
        assertFalse(action.contains("InventoryAction.giveItem"));
        assertTrue(action.contains("InteractAction.useItemInAir"));
        assertTrue(action.contains("RaycastContext.FluidHandling.SOURCE_ONLY"));
        assertTrue(action.contains("raycastWaterSource(bot)"));
        assertTrue(action.contains("ObservableWorldQuery.canObserveCell"));
        assertTrue(action.contains("source-only bucket ray is the perception boundary"));
        assertTrue(action.contains("waterAfter > waterBefore"),
                "filling must be verified from the vanilla inventory exchange");
        assertTrue(action.contains("waterAfter < waterBefore && emptyAfter > emptyBefore"),
                "pouring must be verified from the vanilla inventory exchange");
    }

    @Test
    void pourPlanDestinationIsAlwaysTheClickedSupportFaceNeighbor() {
        BlockPos support = new BlockPos(7, 23, -4);
        for (Direction face : Direction.values()) {
            CreateObsidianTask.PourPlan plan = new CreateObsidianTask.PourPlan(support, face);
            assertEquals(support.offset(face), plan.destination());
        }
    }

    private static String read(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }
}
