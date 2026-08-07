package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.goal.GoalPlanner;
import io.github.zoyluo.aibot.goal.GoalResult;
import io.github.zoyluo.aibot.goal.GoalStep;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mining.MiningBudget;
import io.github.zoyluo.aibot.pathfinding.Standability;
import io.github.zoyluo.aibot.persist.MissionRecord;
import io.github.zoyluo.aibot.persist.MissionRuntimeRecord;
import io.github.zoyluo.aibot.persist.MissionSpec;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Restart contracts for the exact staircase hand-off owned by {@link DescendToYTask}. */
public final class DescendCheckpointGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "descendDeepBudgetLive", tickLimit = 9_000)
    public void fullDepthDeepslateDescentWithFiveStonePickaxesFitsItsPersistedWindow(
            TestContext context) {
        BlockPos relativeOrigin = context.getAbsolutePos(new BlockPos(4, 0, 80));
        BlockPos start = new BlockPos(relativeOrigin.getX(), 16, relativeOrigin.getZ());
        int targetY = -59;
        require(context, start.getY() - targetY == 75,
                "full-depth fixture did not span 75 levels: " + start + " -> " + targetY);

        context.getWorld().setBlockState(start,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(start.up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(start.down(),
                Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
        List<BlockPos> physicalBreaks = new java.util.ArrayList<>();
        BlockPos cursor = start;
        for (int level = 0; level < 75; level++) {
            BlockPos ahead = cursor.north();
            BlockPos landing = ahead.down();
            for (BlockPos breakPos : List.of(ahead, ahead.up(), landing)) {
                context.getWorld().setBlockState(breakPos,
                        Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
                physicalBreaks.add(breakPos.toImmutable());
            }
            context.getWorld().setBlockState(landing.down(),
                    Blocks.DEEPSLATE.getDefaultState(), Block.NOTIFY_ALL);
            cursor = landing;
        }
        BlockPos expectedLanding = cursor;
        require(context, expectedLanding.getY() == targetY,
                "full-depth fixture ended at the wrong Y: " + expectedLanding);

        String name = "DescendFullDepthGT";
        AIPlayerEntity bot = spawn(context, name, start);
        bot.setOnGround(true);
        for (int pick = 0; pick < 5; pick++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        DescendToYTask task = new DescendToYTask(targetY);
        task.start(bot);
        require(context, "8400".equals(task.checkpoint().get("budget_limit")),
                "Y=16 to Y=-59 did not receive the derived 8,400-tick window: "
                        + task.checkpoint());

        context.runAtEveryTick(() -> {
            // One task tick per real server tick is part of the contract. Replaying ActionPack in
            // the same server tick can make MiningController reach progress=1 before the server's
            // interaction manager has settled the break, which only burns the persisted budget.
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "full-depth Deepslate descent failed: " + task.failureReason()
                            + " checkpoint=" + task.checkpoint());
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            Map<String, String> terminal = task.checkpoint();
            int budgetUsed = Integer.parseInt(terminal.get("budget_used"));
            require(context, bot.getBlockPos().equals(expectedLanding),
                    "full-depth descent completed at the wrong landing: expected="
                            + expectedLanding.toShortString() + " actual="
                            + bot.getBlockPos().toShortString());
            require(context, budgetUsed > 4_800 && budgetUsed <= 8_400
                            && "8400".equals(terminal.get("budget_limit"))
                            && "false".equals(terminal.get("task_open")),
                    "full-depth descent violated its persisted dynamic budget: " + terminal);
            require(context, physicalBreaks.stream().allMatch(pos ->
                            !context.getWorld().getBlockState(pos).isOf(Blocks.DEEPSLATE)),
                    "full-depth descent completed without physically clearing every body block");
            finish(context, bot, name);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void exhaustedCheckpointFailsOnItsPersistedClock(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 4, 2));
        prepareLanding(context, start);
        AIPlayerEntity bot = spawn(context, "DescendBudgetRestoreGT", start);

        Map<String, String> checkpoint = freshCheckpoint(bot, start.getY() - 1);
        checkpoint = new LinkedHashMap<>(checkpoint);
        checkpoint.put("budget_used", "4800");
        checkpoint.put("last_progress_budget", "4800");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(start.getY() - 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.FAILED,
                "exhausted checkpoint received a fresh clock: " + restored.state());
        require(context, restored.failureReason().startsWith("descend_timeout"),
                "unexpected exhausted-checkpoint failure: " + restored.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "timeout restore moved before enforcing its persisted budget");
        finish(context, bot, "DescendBudgetRestoreGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void schemaFourRestartKeepsTheOriginalDepthWindowAtANewHeight(TestContext context) {
        BlockPos lane = context.getAbsolutePos(new BlockPos(3, 0, 12));
        BlockPos start = new BlockPos(lane.getX(), 16, lane.getZ());
        BlockPos restart = new BlockPos(lane.getX(), 0, lane.getZ());
        prepareLanding(context, start);
        prepareLanding(context, restart);
        prepareLanding(context, restart.north().down());
        String name = "DescendWindowRestoreGT";
        AIPlayerEntity bot = spawn(context, name, start);

        DescendToYTask first = new DescendToYTask(-59);
        first.start(bot);
        Map<String, String> checkpoint = new LinkedHashMap<>(first.checkpoint());
        checkpoint.put("budget_used", "5000");
        checkpoint.put("last_progress_budget", "5000");
        require(context, "8400".equals(checkpoint.get("budget_limit"))
                        && DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "dynamic restart fixture was invalid: " + checkpoint);
        first.cancel(bot, "gametest_restart");
        bot.teleport(context.getWorld(), restart.getX() + 0.5D, restart.getY(),
                restart.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);

        DescendToYTask restored = new DescendToYTask(-59, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        Map<String, String> afterRestart = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING,
                "schema-4 restart treated its live persisted clock as exhausted: "
                        + restored.failureReason());
        require(context, "8400".equals(afterRestart.get("budget_limit"))
                        && Integer.parseInt(afterRestart.get("budget_used")) > 5000,
                "schema-4 restart recomputed or refreshed the original budget: "
                        + afterRestart);

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void detourCheckpointSchemaRejectsInventedOrIncompleteEdgeHistory(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 7, 2));
        prepareLanding(context, start);
        AIPlayerEntity bot = spawn(context, "DescendEdgeSchemaGT", start);
        Map<String, String> current = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 5));

        Map<String, String> legacyIdle = new LinkedHashMap<>(current);
        legacyIdle.put("task_schema", "2");
        legacyIdle.remove("traversed_detour_edges");
        legacyIdle.remove("budget_limit");
        require(context, DescendToYTask.inspectCheckpoint(legacyIdle).isPresent(),
                "idle schema-2 checkpoint was not safely promoted");
        DescendToYTask promoted = new DescendToYTask(start.getY() - 5, legacyIdle);
        promoted.start(bot);
        require(context, "4".equals(promoted.checkpoint().get("task_schema"))
                        && "".equals(promoted.checkpoint().get("traversed_detour_edges"))
                        && "4800".equals(promoted.checkpoint().get("budget_limit")),
                "schema-2 promotion did not emit schema-4 edge and budget history: "
                        + promoted.checkpoint());
        promoted.cancel(bot, "gametest_schema_check");

        Map<String, String> schemaThree = new LinkedHashMap<>(current);
        schemaThree.put("task_schema", "3");
        schemaThree.remove("budget_limit");
        schemaThree.put("budget_used", "1000");
        schemaThree.put("last_progress_budget", "1000");
        require(context, DescendToYTask.inspectCheckpoint(schemaThree).isPresent(),
                "schema-3 edge checkpoint was not accepted for one-time budget promotion");
        DescendToYTask promotedBudget = new DescendToYTask(start.getY() - 5, schemaThree);
        promotedBudget.start(bot);
        require(context, "4".equals(promotedBudget.checkpoint().get("task_schema"))
                        && "5800".equals(promotedBudget.checkpoint().get("budget_limit")),
                "schema-3 promotion did not persist its dynamic budget: "
                        + promotedBudget.checkpoint());
        promotedBudget.cancel(bot, "gametest_schema_check");

        Map<String, String> tooSmallLimit = new LinkedHashMap<>(current);
        tooSmallLimit.put("budget_limit", "4799");
        require(context, DescendToYTask.inspectCheckpoint(tooSmallLimit).isEmpty(),
                "schema-4 checkpoint accepted a sub-minimum budget limit");
        Map<String, String> tooLargeLimit = new LinkedHashMap<>(current);
        tooLargeLimit.put("budget_limit", "40001");
        require(context, DescendToYTask.inspectCheckpoint(tooLargeLimit).isEmpty(),
                "schema-4 checkpoint accepted an over-cap budget limit");
        Map<String, String> usedPastTerminal = new LinkedHashMap<>(current);
        usedPastTerminal.put("budget_used", "4802");
        usedPastTerminal.put("last_progress_budget", "4802");
        require(context, DescendToYTask.inspectCheckpoint(usedPastTerminal).isEmpty(),
                "schema-4 checkpoint accepted budget usage past limit+1");

        Map<String, String> legacyActive = new LinkedHashMap<>(legacyIdle);
        legacyActive.put("lateral_detours", "1");
        legacyActive.put("detour_heading", "0");
        require(context, DescendToYTask.inspectCheckpoint(legacyActive).isEmpty(),
                "active schema-2 detour invented missing traversal history");

        Map<String, String> duplicate = new LinkedHashMap<>(current);
        String north = encodeEdge(start, start.north());
        duplicate.put("lateral_detours", "2");
        duplicate.put("detour_heading", "0");
        duplicate.put("traversed_detour_edges", north + ";" + north);
        require(context, DescendToYTask.inspectCheckpoint(duplicate).isEmpty(),
                "duplicate directed detour edge was accepted");

        Map<String, String> nonAdjacent = new LinkedHashMap<>(current);
        nonAdjacent.put("lateral_detours", "1");
        nonAdjacent.put("detour_heading", "0");
        nonAdjacent.put("traversed_detour_edges", encodeEdge(start, start.north(2)));
        require(context, DescendToYTask.inspectCheckpoint(nonAdjacent).isEmpty(),
                "non-adjacent directed detour edge was accepted");

        Map<String, String> wrongHeading = new LinkedHashMap<>(current);
        wrongHeading.put("lateral_detours", "1");
        wrongHeading.put("detour_heading", "1");
        wrongHeading.put("traversed_detour_edges", north);
        require(context, DescendToYTask.inspectCheckpoint(wrongHeading).isEmpty(),
                "checkpoint heading disagreed with its last factual detour edge");
        finish(context, bot, "DescendEdgeSchemaGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void edgeSeventeenCheckpointContinuesToANearbySupportedCaveRim(
            TestContext context) {
        BlockPos restart = context.getAbsolutePos(new BlockPos(8, 7, 8));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -7; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    context.getWorld().setBlockState(restart.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        for (int step = 0; step <= 4; step++) {
            prepareLanding(context, restart.north(step));
        }
        BlockPos lowerExit = restart.north(5).down();
        prepareLanding(context, lowerExit);

        String name = "DescendEdge17RestoreGT";
        AIPlayerEntity bot = spawn(context, name, restart);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, lowerExit.getY()));
        List<String> history = new java.util.ArrayList<>();
        BlockPos cursor = restart.south(17);
        for (int edge = 0; edge < 17; edge++) {
            BlockPos next = cursor.north();
            history.add(encodeEdge(cursor, next));
            cursor = next;
        }
        require(context, cursor.equals(restart),
                "edge-17 fixture history did not end at its factual restart pose");
        checkpoint.put("budget_used", "17");
        checkpoint.put("last_progress_budget", "17");
        checkpoint.put("stair_direction", "0");
        checkpoint.put("lateral_detours", "17");
        checkpoint.put("detour_heading", "0");
        String encodedHistory = String.join(";", history);
        checkpoint.put("traversed_detour_edges", encodedHistory);
        String budgetLimit = checkpoint.get("budget_limit");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "edge-17 checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(lowerExit.getY(), checkpoint);
        restored.start(bot);
        require(context, "17".equals(restored.checkpoint().get("lateral_detours"))
                        && encodedHistory.equals(
                        restored.checkpoint().get("traversed_detour_edges"))
                        && budgetLimit.equals(restored.checkpoint().get("budget_limit")),
                "restart refreshed the persisted edge debt: " + restored.checkpoint());
        int maxDebt = 17;
        for (int tick = 0; tick < 40 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
            maxDebt = Math.max(maxDebt,
                    Integer.parseInt(restored.checkpoint().get("lateral_detours")));
        }

        require(context, restored.state() == TaskState.COMPLETED,
                "edge-17 restart did not reach the supported rim: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(lowerExit),
                "edge-17 restart completed at the wrong landing: expected="
                        + lowerExit.toShortString() + " actual="
                        + bot.getBlockPos().toShortString());
        require(context, maxDebt == 21,
                "edge-17 restart refreshed or skipped its factual debt: max_debt=" + maxDebt);
        require(context, Integer.parseInt(restored.checkpoint().get("budget_used")) > 17
                        && budgetLimit.equals(restored.checkpoint().get("budget_limit")),
                "edge-17 restart refreshed or rewound its persisted clock: "
                        + restored.checkpoint());
        require(context, "0".equals(restored.checkpoint().get("lateral_detours"))
                        && "".equals(restored.checkpoint().get("traversed_detour_edges")),
                "confirmed lower landing retained the restored detour history: "
                        + restored.checkpoint());
        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void fullThirtyTwoEdgeCheckpointFailsClosedWithoutRefreshingOrReplaying(
            TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 7, 8));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    context.getWorld().setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        prepareLanding(context, start);
        String name = "DescendEdge32CapGT";
        AIPlayerEntity bot = spawn(context, name, start);
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 1));
        List<String> history = new java.util.ArrayList<>();
        BlockPos cursor = start.south(32);
        for (int edge = 0; edge < 32; edge++) {
            BlockPos next = cursor.north();
            history.add(encodeEdge(cursor, next));
            cursor = next;
        }
        require(context, cursor.equals(start),
                "edge-cap fixture history did not end at its factual restart pose");
        String encodedHistory = String.join(";", history);
        checkpoint.put("budget_used", "32");
        checkpoint.put("last_progress_budget", "32");
        checkpoint.put("stair_direction", "0");
        checkpoint.put("lateral_detours", "32");
        checkpoint.put("detour_heading", "0");
        checkpoint.put("traversed_detour_edges", encodedHistory);
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "full edge-cap checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(start.getY() - 1, checkpoint);
        restored.start(bot);
        for (int tick = 0; tick < 8 && restored.state() == TaskState.RUNNING; tick++) {
            restored.tick(bot);
        }

        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().startsWith("descend_no_safe_landing"),
                "full edge-cap checkpoint escaped its bounded terminal: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "full edge-cap checkpoint moved after exhaustion: "
                        + bot.getBlockPos().toShortString());
        require(context, "32".equals(restored.checkpoint().get("lateral_detours"))
                        && encodedHistory.equals(
                        restored.checkpoint().get("traversed_detour_edges")),
                "full edge-cap checkpoint refreshed or replayed history: "
                        + restored.checkpoint());
        finish(context, bot, name);
    }

    /**
     * A knockback between task ticks can leave the bot on a third cell that is neither the
     * pending landing origin nor its target. That is external displacement, not a broken safety
     * invariant: the descent must re-anchor on the factual pose and keep running instead of
     * terminating the whole mission as descend_landing_pose_drift.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void knockbackLandingDriftReanchorsInsteadOfFailingTheMission(
            TestContext context) {
        BlockPos floor = context.getAbsolutePos(new BlockPos(11, 7, 6));
        BlockPos origin = floor.east().up();
        BlockPos drifted = floor.west();
        prepareLanding(context, floor);
        prepareLanding(context, origin);
        prepareLanding(context, drifted);
        // Keep the next stair below the drifted pose solid so the recovered tick plans calmly.
        context.getWorld().setBlockState(drifted.north(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        String name = "DescendDriftGT";
        AIPlayerEntity bot = spawn(context, name, drifted);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, floor.getY() - 1));
        checkpoint.put("pending_landing_origin", encode(origin));
        checkpoint.put("pending_landing_target", encode(floor));
        checkpoint.put("pending_landing_direction", "3");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "drift fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(floor.getY() - 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.RUNNING,
                "single knockback drift terminated the descent: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, "none".equals(
                        restored.checkpoint().get("pending_landing_target")),
                "drift recovery retained the stale pending landing: "
                        + restored.checkpoint());
        restored.tick(bot);
        require(context, restored.state() == TaskState.RUNNING,
                "re-anchored descent could not continue from the drifted pose: "
                        + restored.state() + ":" + restored.failureReason());
        restored.cancel(bot, "gametest_drift_recovered");
        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void upperRetreatLandingBackOnTheObstacleFloorKeepsItsDetourDebt(
            TestContext context) {
        BlockPos floor = context.getAbsolutePos(new BlockPos(5, 7, 6));
        BlockPos south = floor.south();
        BlockPos upper = floor.east().up();
        BlockPos upperSouth = upper.south();
        prepareLanding(context, floor);
        BlockPos freshLowerLanding = floor.north().down();
        prepareLanding(context, freshLowerLanding);
        context.getWorld().setBlockState(floor.north(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        String name = "DescendFloorDebtGT";
        AIPlayerEntity bot = spawn(context, name, floor);
        // The descend tool gate typed-fails a pickless stair dig; this fixture tests detour
        // debt accounting, so provision the ordinary descent pick like a real mission would.
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, floor.getY() - 1));
        String detourHistory = String.join(";",
                encodeEdge(floor, south),
                encodeEdge(south, floor),
                encodeEdge(floor, upper),
                encodeEdge(upper, upperSouth),
                encodeEdge(upperSouth, upper));
        checkpoint.put("budget_used", "5");
        checkpoint.put("last_progress_budget", "5");
        checkpoint.put("lateral_detours", "5");
        checkpoint.put("detour_heading", "0");
        checkpoint.put("pending_landing_origin", encode(upper));
        checkpoint.put("pending_landing_target", encode(floor));
        checkpoint.put("pending_landing_direction", "3");
        checkpoint.put("traversed_detour_edges", detourHistory);
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "same-floor detour fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(floor.getY() - 1, checkpoint);
        restored.start(bot);
        restored.tick(bot);

        Map<String, String> afterLanding = restored.checkpoint();
        require(context, restored.state() == TaskState.RUNNING,
                "same-floor landing ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(floor),
                "same-floor landing fixture moved away before debt inspection: "
                        + bot.getBlockPos().toShortString());
        require(context, "5".equals(afterLanding.get("lateral_detours"))
                        && detourHistory.equals(afterLanding.get("traversed_detour_edges")),
                "upper retreat landing reset same-floor detour debt: " + afterLanding);

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 50)
    public void interruptedLandingAtOriginRejectsTheSameEdgeAfterRestore(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(6, 5, 2));
        prepareLanding(context, start);
        prepareLanding(context, start.north().down());
        prepareLanding(context, start.east().down());
        AIPlayerEntity bot = spawn(context, "DescendLandingRestoreGT", start);

        DescendToYTask first = new DescendToYTask(start.getY() - 1);
        first.start(bot);
        first.tick(bot);
        BlockPos northLanding = start.north().down();
        require(context, bot.getBlockPos().equals(northLanding),
                "fixture did not issue the initial north landing");
        Map<String, String> checkpoint = first.checkpoint();
        require(context, encode(start).equals(checkpoint.get("pending_landing_origin"))
                        && encode(northLanding).equals(checkpoint.get("pending_landing_target")),
                "fixture did not persist its unresolved landing: " + checkpoint);
        first.cancel(bot, "gametest_restart");
        bot.teleport(context.getWorld(), start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        DescendToYTask restored = new DescendToYTask(start.getY() - 1, checkpoint);
        restored.start(bot);
        Map<String, String> promoted = restored.checkpoint();
        require(context, "none".equals(promoted.get("pending_landing_origin"))
                        && "none".equals(promoted.get("pending_landing_target")),
                "restart retained an unobservable landing controller: " + promoted);
        require(context, encode(start).equals(promoted.get("rejected_landing_origin"))
                        && (Integer.parseInt(promoted.get("rejected_landing_directions")) & 1) != 0,
                "restart did not reject the interrupted north edge: " + promoted);

        restored.tick(bot);
        require(context, !bot.getBlockPos().equals(northLanding),
                "restored Descend retried the interrupted landing");
        finish(context, bot, "DescendLandingRestoreGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void traversedDetourEdgesSurviveRestartAndUnlockTheUpperSealRoute(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 7, 2));
        BlockPos south = start.south();
        BlockPos northSeal = start.north();
        BlockPos upperEscape = northSeal.up();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    context.getWorld().setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        prepareLanding(context, start);
        prepareLanding(context, south);
        context.getWorld().setBlockState(northSeal,
                Blocks.DIRT.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(upperEscape,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(upperEscape.up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = spawn(context, "DescendEdgeRestoreGT", start);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 5));
        checkpoint.put("budget_used", "2");
        checkpoint.put("last_progress_budget", "2");
        checkpoint.put("lateral_detours", "2");
        checkpoint.put("detour_heading", "0");
        checkpoint.put("owned_water_seals",
                encode(northSeal) + ",minecraft:dirt");
        checkpoint.put("traversed_detour_edges",
                encodeEdge(start, south) + ";" + encodeEdge(south, start));
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "fixture checkpoint was rejected: " + checkpoint);

        DescendToYTask restored = new DescendToYTask(start.getY() - 5, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.RUNNING,
                "restored detour ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(upperEscape),
                "restored detour replayed the visited south edge instead of using the upper "
                        + "water-seal route: " + bot.getBlockPos().toShortString());
        require(context, context.getWorld().getBlockState(northSeal).isOf(Blocks.DIRT),
                "upper-route escape mined its owned water seal");
        require(context, DescendToYTask.inspectCheckpoint(restored.checkpoint()).isPresent(),
                "upper-route move produced an invalid checkpoint: " + restored.checkpoint());
        restored.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendEdgeRestoreGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 50)
    public void unsupportedSolidDetourPreservesUpperRetreatAcrossRestart(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(14, 7, 2));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    context.getWorld().setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        context.getWorld().setBlockState(start.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos unsupportedBody = start.east();
        BlockPos upperRetreat = unsupportedBody.up();
        // The same-level EAST candidate is solid but has no floor. It is also the sole support for
        // the dry upper retreat. The old detour mined it before stepToStandable rejected the lower
        // landing, thereby destroying both escape options.
        context.getWorld().setBlockState(unsupportedBody,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos freshLanding = upperRetreat.north().down();
        context.getWorld().setBlockState(freshLanding.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DescendUpperRetreatRestoreGT";
        AIPlayerEntity bot = spawn(context, name, start);
        Map<String, String> initial = new LinkedHashMap<>(
                freshCheckpoint(bot, start.getY() - 5));
        // Start WEST so the upper EAST retreat's reverse is also the persisted staircase heading.
        // Without restoring the rejection bit, the next task would immediately descend WEST into
        // the lower cell it just escaped; NORTH remains the only fresh supported column.
        initial.put("stair_direction", "3");
        require(context, DescendToYTask.inspectCheckpoint(initial).isPresent(),
                "upper-retreat fixture checkpoint was invalid: " + initial);
        DescendToYTask first = new DescendToYTask(start.getY() - 5, initial);
        first.start(bot);
        first.tick(bot);

        require(context, first.state() == TaskState.RUNNING,
                "unsupported detour ended before its bounded upper retreat: "
                        + first.state() + ":" + first.failureReason());
        require(context, bot.getBlockPos().equals(upperRetreat),
                "unsupported solid candidate was not skipped for the safe upper retreat: "
                        + start.toShortString() + " -> " + bot.getBlockPos().toShortString());
        require(context, context.getWorld().getBlockState(unsupportedBody).isOf(Blocks.STONE),
                "Descend destroyed the only support for its upper retreat");

        Map<String, String> checkpoint = first.checkpoint();
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "upper-retreat checkpoint was invalid: " + checkpoint);
        require(context, encode(upperRetreat).equals(checkpoint.get("rejected_landing_origin"))
                        && (Integer.parseInt(checkpoint.get("rejected_landing_directions"))
                        & 1 << 3) != 0,
                "upper retreat did not durably reject the WEST reverse stair: " + checkpoint);

        first.cancel(bot, "gametest_restart");
        DescendToYTask restored = new DescendToYTask(start.getY() - 5, checkpoint);
        restored.start(bot);
        require(context, (Integer.parseInt(restored.checkpoint()
                        .get("rejected_landing_directions")) & 1 << 3) != 0,
                "restart discarded the WEST reverse rejection before the first tick: "
                        + restored.checkpoint());
        restored.tick(bot);
        require(context, restored.state() == TaskState.RUNNING,
                "restored upper retreat ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(upperRetreat),
                "restored Descend ignored WEST rejection instead of rotating in place: "
                        + upperRetreat.toShortString() + " -> " + bot.getBlockPos().toShortString());
        restored.tick(bot);
        require(context, bot.getBlockPos().equals(freshLanding),
                "restored Descend replayed the rejected stair instead of using the fresh column: "
                        + upperRetreat.toShortString() + " -> " + bot.getBlockPos().toShortString());
        require(context, !bot.getBlockPos().equals(start),
                "restored Descend immediately fell back into the escaped lower cell");

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void isolatedPillarBuildsOnePhysicalFloorBeforeDetourMovement(TestContext context) {
        String name = "DescendBridgeReceiptGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE + 1);
        DescendToYTask task = fixture.task();
        AIPlayerEntity bot = fixture.bot();

        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "isolated-pillar bridge ended on placement: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(fixture.origin()),
                "Descend moved in the same tick as support placement: "
                        + bot.getBlockPos().toShortString());
        require(context, context.getWorld().getBlockState(fixture.support())
                        .isOf(Blocks.COBBLESTONE),
                "Descend did not leave a factual bridge receipt");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE,
                "bridge did not consume exactly one block or spent the emergency reserve");
        require(context, "0".equals(task.checkpoint().get("lateral_detours"))
                        && task.checkpoint().get("traversed_detour_edges").isEmpty(),
                "placement incorrectly committed movement debt: " + task.checkpoint());

        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "bridge receipt was not accepted on the following tick: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(fixture.landing()),
                "Descend did not physically step onto its verified bridge: "
                        + bot.getBlockPos().toShortString());
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE,
                "receipt acknowledgement consumed a second support block");
        require(context, "1".equals(task.checkpoint().get("lateral_detours")),
                "verified bridge movement did not spend one detour edge: " + task.checkpoint());

        task.cancel(bot, "gametest_complete");
        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void bridgeWorldReceiptSurvivesRestartWithoutDuplicateMaterial(TestContext context) {
        String name = "DescendBridgeRestartGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE + 1);
        DescendToYTask first = fixture.task();
        AIPlayerEntity bot = fixture.bot();

        first.tick(bot);
        require(context, context.getWorld().getBlockState(fixture.support())
                        .isOf(Blocks.COBBLESTONE),
                "restart fixture did not place its bridge receipt");
        int blocksAfterPlacement = InventoryAction.countItem(bot, Items.COBBLESTONE);
        Map<String, String> checkpoint = first.checkpoint();
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "bridge placement produced an invalid checkpoint: " + checkpoint);
        first.cancel(bot, "gametest_restart");

        DescendToYTask restored = new DescendToYTask(
                fixture.origin().getY() - 5, checkpoint);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.RUNNING,
                "restored bridge receipt ended unexpectedly: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(fixture.landing()),
                "restored Descend did not consume the factual support receipt: "
                        + bot.getBlockPos().toShortString());
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == blocksAfterPlacement,
                "restart duplicated the support placement material");
        require(context, context.getWorld().getBlockState(fixture.support())
                        .isOf(Blocks.COBBLESTONE),
                "restart removed or replaced its factual support receipt");

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void isolatedPillarKeepsEmergencyReserveAndFailsClosed(TestContext context) {
        String name = "DescendBridgeReserveGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE);
        DescendToYTask task = fixture.task();
        AIPlayerEntity bot = fixture.bot();

        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "reserve-only isolated pillar did not fail closed: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(fixture.origin()),
                "reserve-only failure moved off the supported origin");
        require(context, context.getWorld().getBlockState(fixture.support()).isAir(),
                "reserve-only failure placed an unauthorized bridge");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE,
                "Descend spent the protected emergency stone reserve");

        finish(context, bot, name);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 30)
    public void visibleAdjacentLavaRejectsBridgePlacement(TestContext context) {
        String name = "DescendBridgeLavaGT";
        IsolatedDetourFixture fixture = isolatedDetourFixture(
                context, name, MiningBudget.EMERGENCY_STONE_LIKE + 1);
        BlockPos visibleLava = fixture.support().north();
        context.getWorld().setBlockState(visibleLava,
                Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
        // Keep NORTH as the only replaceable missing floor. The other candidates are dangerous
        // collision supports, so rejecting the visible adjacent lava cannot silently bridge in a
        // different direction and make the test pass for the wrong reason.
        context.getWorld().setBlockState(fixture.origin().east().down(),
                Blocks.MAGMA_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(fixture.origin().west().down(),
                Blocks.MAGMA_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(fixture.origin().south().down(),
                Blocks.MAGMA_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        DescendToYTask task = fixture.task();
        AIPlayerEntity bot = fixture.bot();
        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "lava-adjacent isolated pillar did not fail closed: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(fixture.origin()),
                "lava-adjacent rejection moved the bot");
        require(context, context.getWorld().getBlockState(fixture.support()).isAir(),
                "Descend bridged beside visible lava");
        require(context, context.getWorld().getBlockState(visibleLava).isOf(Blocks.LAVA),
                "lava-adjacent rejection mutated the hazard");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE)
                        == MiningBudget.EMERGENCY_STONE_LIKE + 1,
                "lava-adjacent rejection consumed bridge material");

        finish(context, bot, name);
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "descendSafetyPauseStrict",
            tickLimit = 40)
    public void settledLandingSurvivesSafetyTaskDisplacement(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 5, 3));
        BlockPos firstLanding = start.north().down();
        BlockPos displaced = firstLanding.east();
        BlockPos finalLanding = displaced.north().down();
        prepareLanding(context, start);
        prepareLanding(context, firstLanding);
        prepareLanding(context, displaced);
        prepareLanding(context, finalLanding);
        AIPlayerEntity bot = spawn(context, "DescendSafetyPauseGT", start);

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(firstLanding),
                "fixture did not issue the first physical landing");
        require(context, encode(firstLanding).equals(task.checkpoint().get("pending_landing_target")),
                "fixture did not retain the unresolved first landing");

        task.pause(bot);
        require(context, "none".equals(task.checkpoint().get("pending_landing_origin"))
                        && "none".equals(task.checkpoint().get("pending_landing_target")),
                "pause retained a dry factual landing debt: " + task.checkpoint());
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, displaced, "gametest_safety_displacement"),
                "fixture could not apply an ordinary safety-task displacement");

        task.resume(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "resumed Descend rejected the safety displacement: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(finalLanding),
                "resumed Descend did not continue from the new factual pose");
        task.tick(bot);
        require(context, task.state() == TaskState.COMPLETED,
                "resumed Descend did not hand off at the target layer: "
                        + task.state() + ":" + task.failureReason());
        finish(context, bot, "DescendSafetyPauseGT");
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "descendThreatPauseStrict",
            tickLimit = 30)
    public void threatPausePreservesRejectionAtTheSettledLanding(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 5, 3));
        BlockPos landing = start.north().down();
        prepareLanding(context, start);
        prepareLanding(context, landing);
        AIPlayerEntity bot = spawn(context, "DescendThreatPauseGT", start);

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(landing),
                "fixture did not issue the pending physical landing");

        task.avoidCurrentDescentDirection(bot, landing.east());
        Map<String, String> avoided = task.checkpoint();
        require(context, "none".equals(avoided.get("pending_landing_origin"))
                        && "none".equals(avoided.get("pending_landing_target")),
                "threat routing retained a settled landing debt: " + avoided);
        require(context, encode(landing).equals(avoided.get("rejected_landing_origin"))
                        && (Integer.parseInt(avoided.get("rejected_landing_directions")) & 1) != 0,
                "threat routing did not reject the current north stair: " + avoided);

        task.pause(bot);
        Map<String, String> paused = task.checkpoint();
        require(context, encode(landing).equals(paused.get("rejected_landing_origin"))
                        && (Integer.parseInt(paused.get("rejected_landing_directions")) & 1) != 0,
                "pause erased the threat rejection at the settled landing: " + paused);
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendThreatPauseGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 60)
    public void plannerOmittedDescentStillReplaysTheActiveCheckpoint(TestContext context) {
        BlockPos anchor = context.getAbsolutePos(new BlockPos(10, 2, 2));
        BlockPos mineFace = new BlockPos(anchor.getX(), -58, anchor.getZ());
        prepareLanding(context, mineFace);
        AIPlayerEntity bot = spawnPreparedMiner(context, "DescendMissionReplayGT", mineFace);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);
        require(context, GoalPlanner.plan(bot, goal).steps().stream()
                        .noneMatch(step -> step.kind() == GoalStep.Kind.DESCEND_TO_Y),
                "fixture planner still requested a fresh descent");

        Map<String, String> taskCheckpoint = new LinkedHashMap<>(freshCheckpoint(bot, -58));
        taskCheckpoint.put("budget_used", "17");
        taskCheckpoint.put("last_progress_budget", "12");
        UUID missionId = UUID.randomUUID();
        GoalExecutor.INSTANCE.restoreRuntime(bot, missionRuntime(
                missionId, goal, mineFace, GoalStep.Kind.DESCEND_TO_Y, taskCheckpoint));

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof DescendToYTask,
                "restore skipped Descend and started "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, restored.active() != null
                        && missionId.toString().equals(restored.active().missionId()),
                "descent restore changed Mission identity");
        Map<String, String> persisted = restored.active().checkpoint();
        require(context, "DESCEND_TO_Y".equals(persisted.get("task_kind"))
                        && "-58".equals(persisted.get("task.target_y"))
                        && "17".equals(persisted.get("task.budget_used"))
                        && "12".equals(persisted.get("task.last_progress_budget")),
                "descent restore changed its durable cursor: " + persisted);
        finish(context, bot, "DescendMissionReplayGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 60)
    public void committedDescentAtBudgetBoundaryIsAcknowledgedWithoutReplay(TestContext context) {
        BlockPos anchor = context.getAbsolutePos(new BlockPos(12, 2, 2));
        BlockPos mineFace = new BlockPos(anchor.getX(), -58, anchor.getZ());
        prepareLanding(context, mineFace);
        AIPlayerEntity bot = spawnPreparedMiner(context, "DescendCommittedRestoreGT", mineFace);
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);

        DescendToYTask completed = new DescendToYTask(-58);
        completed.start(bot);
        completed.tick(bot);
        require(context, completed.state() == TaskState.COMPLETED,
                "fixture descent did not commit at the hand-off layer");
        Map<String, String> taskCheckpoint = new LinkedHashMap<>(completed.checkpoint());
        taskCheckpoint.put("budget_used", "4800");
        taskCheckpoint.put("last_progress_budget", "4799");
        require(context, DescendToYTask.inspectCheckpoint(taskCheckpoint)
                        .map(metadata -> !metadata.transactionOpen()).orElse(false),
                "fixture did not encode a committed checkpoint: " + taskCheckpoint);

        GoalExecutor.INSTANCE.restoreRuntime(bot, missionRuntime(
                UUID.randomUUID(), goal, mineFace, GoalStep.Kind.DESCEND_TO_Y, taskCheckpoint));
        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active != null && !(active instanceof DescendToYTask),
                "committed Descend replayed and exposed the timeout crash window: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, GoalExecutor.INSTANCE.isActiveGoal(bot, goal),
                "committed Descend prevented the ore mission from continuing");
        MissionRuntimeRecord restored = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, restored.active() != null
                        && !"DESCEND_TO_Y".equals(restored.active().checkpoint().get("task_kind")),
                "committed Descend checkpoint was not acknowledged: "
                        + (restored.active() == null ? "none" : restored.active().checkpoint()));
        finish(context, bot, "DescendCommittedRestoreGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void satisfiedGoalRejectsMissingDescendCheckpoint(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(14, 4, 2));
        prepareLanding(context, start);
        AIPlayerEntity bot = spawn(context, "DescendMissingCheckpointGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND, 8));
        Goal goal = new Goal.MineOre(Set.of(Blocks.DIAMOND_ORE), 8);

        GoalExecutor.INSTANCE.restoreRuntime(bot, missionRuntime(
                UUID.randomUUID(), goal, start, GoalStep.Kind.DESCEND_TO_Y, Map.of()));
        GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        require(context, result != null
                        && "mission_restore_invalid_descend_checkpoint".equals(result.reason()),
                "missing Descend checkpoint bypassed fail-closed restore: "
                        + (result == null ? "no_result" : result.reason()));
        require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                "missing Descend checkpoint restored an active Mission");
        finish(context, bot, "DescendMissingCheckpointGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void descentLightingDoesNotConsumeToolServiceSticks(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(16, 4, 2));
        prepareLanding(context, start);
        prepareLanding(context, start.north().down());
        AIPlayerEntity bot = spawn(context, "DescendTorchReserveGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COAL));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "descent-lighting fixture ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.COAL) == 1,
                "descent lighting consumed incidental coal");
        require(context, InventoryAction.countItem(bot, Items.STICK) == 1,
                "descent lighting stole a tool-service stick");
        require(context, InventoryAction.countItem(bot, Items.TORCH) == 0,
                "descent lighting synthesized torches without a planned craft");

        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendTorchReserveGT");
    }

    private static Map<String, String> freshCheckpoint(AIPlayerEntity bot, int targetY) {
        DescendToYTask task = new DescendToYTask(targetY);
        task.start(bot);
        Map<String, String> checkpoint = task.checkpoint();
        task.cancel(bot, "gametest_checkpoint_fixture");
        return checkpoint;
    }

    private static MissionRuntimeRecord missionRuntime(UUID missionId,
                                                       Goal goal,
                                                       BlockPos origin,
                                                       GoalStep.Kind kind,
                                                       Map<String, String> taskCheckpoint) {
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("origin", encode(origin));
        checkpoint.put("started_tick", "0");
        checkpoint.put("revision", "0");
        checkpoint.put("task_kind", kind.name());
        taskCheckpoint.forEach((key, value) -> checkpoint.put("task." + key, value));
        return new MissionRuntimeRecord(
                new MissionRecord(missionId.toString(), MissionSpec.fromGoal(goal), checkpoint),
                List.of(), false);
    }

    private static AIPlayerEntity spawnPreparedMiner(TestContext context, String name, BlockPos pos) {
        AIPlayerEntity bot = spawn(context, name, pos);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 2));
        for (int i = 0; i < 5; i++) {
            InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        }
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_INGOT, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH, 32));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 16));
        return bot;
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos pos) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(pos), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        return bot;
    }

    private static void prepareLanding(TestContext context, BlockPos feet) {
        context.getWorld().setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String encodeEdge(BlockPos origin, BlockPos target) {
        return encode(origin) + "," + encode(target);
    }

    private static IsolatedDetourFixture isolatedDetourFixture(TestContext context,
                                                               String name,
                                                               int cobblestoneCount) {
        BlockPos origin = context.getAbsolutePos(new BlockPos(18, 8, 18));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    context.getWorld().setBlockState(origin.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        context.getWorld().setBlockState(origin.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        AIPlayerEntity bot = spawn(context, name, origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        if (cobblestoneCount > 0) {
            InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, cobblestoneCount));
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(
                freshCheckpoint(bot, origin.getY() - 5));
        checkpoint.put("budget_used", "1");
        checkpoint.put("last_progress_budget", "1");
        checkpoint.put("stair_direction", "0");
        checkpoint.put("rejected_landing_origin", encode(origin));
        checkpoint.put("rejected_landing_directions", "15");
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "isolated-pillar fixture checkpoint was invalid: " + checkpoint);
        DescendToYTask task = new DescendToYTask(origin.getY() - 5, checkpoint);
        task.start(bot);
        BlockPos landing = origin.north();
        return new IsolatedDetourFixture(
                bot, task, origin.toImmutable(), landing.toImmutable(), landing.down().toImmutable());
    }

    private record IsolatedDetourFixture(AIPlayerEntity bot,
                                         DescendToYTask task,
                                         BlockPos origin,
                                         BlockPos landing,
                                         BlockPos support) {
    }

    private static void finish(TestContext context, AIPlayerEntity bot, String name) {
        GoalExecutor.INSTANCE.clear(bot);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
