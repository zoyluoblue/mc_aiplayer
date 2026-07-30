package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.pathfinding.MoveType;
import io.github.zoyluo.aibot.pathfinding.Node;
import io.github.zoyluo.aibot.pathfinding.PathExecutor;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.goal.GoalResult;
import io.github.zoyluo.aibot.goal.GoalStep;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.persist.MissionRecord;
import io.github.zoyluo.aibot.persist.MissionRuntimeRecord;
import io.github.zoyluo.aibot.persist.MissionSpec;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Strict-survival regression for the stone bootstrap's factual staircase return. */
public final class DigDownReturnGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 900)
    public void unsupportedNaturalSlopeRotatesToSupportedStoneStair(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(6, 6, -28));
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        // Default NORTH is a natural air slope ending above a void: next and next.down have no
        // support. EAST is a conventional mineable stone staircase. The task must rotate instead
        // of retrying the rejected NORTH landing until its no-progress deadline.
        BlockPos northNext = start.north().down();
        world.setBlockState(start.north(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(northNext, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(northNext.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (int dx = 1; dx <= 5; dx++) {
            for (int dy = -6; dy <= 0; dy++) {
                world.setBlockState(start.add(dx, dy, 0),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
            world.setBlockState(start.add(dx, 1, 0), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(start.add(dx, 2, 0), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        require(context, !DigDownTask.isViableStairDirection(
                        world, start, net.minecraft.util.math.Direction.NORTH),
                "fixture's unsupported north stair was accepted");
        require(context, DigDownTask.isViableStairDirection(
                        world, start, net.minecraft.util.math.Direction.EAST),
                "fixture's supported east stair was rejected");

        AIPlayerEntity bot = spawn(context, "DigDownRotateGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_rotate_stair"));
        AtomicBoolean usedEastStair = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (bot.getBlockPos().getX() > start.getX()) {
                usedEastStair.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("rotating DigDown ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, usedEastStair.get(),
                    "DigDown never used the supported alternative staircase");
            require(context, bot.getBlockPos().equals(start),
                    "rotating DigDown did not return to its exact origin");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3,
                    "rotating DigDown did not collect the stone quota");
            finish(context, bot, "DigDownRotateGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 900)
    public void movedDescendCheckpointStartsFreshAtCurrentPose(TestContext context) {
        var world = context.getWorld();
        BlockPos oldStart = context.getAbsolutePos(new BlockPos(3, 6, -48));
        List<BlockPos> oldTrail = List.of(
                oldStart,
                oldStart.add(0, -1, -1),
                oldStart.add(0, -2, -2));
        BlockPos current = oldStart.add(12, 0, 0);
        for (int dx = -2; dx <= 5; dx++) {
            for (int dz = -5; dz <= 2; dz++) {
                for (int dy = -7; dy <= -1; dy++) {
                    world.setBlockState(current.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(current.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        Map<String, String> stale = new DigDownTask.DigDownCheckpoint(
                1, "minecraft:stone", 3, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                oldStart, oldTrail.getLast().getY(),
                0, 0, 203, 0, 0, 0, 0, false,
                null, 0, oldTrail,
                -1, 0, -20, false).encode();
        AIPlayerEntity bot = spawn(context, "DigDownMovedCheckpointGT", current);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3, stale);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_moved_checkpoint"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("moved-checkpoint DigDown ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getBlockPos().equals(current),
                    "fresh DigDown returned to the stale checkpoint origin: "
                            + bot.getBlockPos().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3,
                    "fresh DigDown did not satisfy the stone quota");
            finish(context, bot, "DigDownMovedCheckpointGT");
        });
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownSafetyPauseReturnStrict",
            tickLimit = 320)
    public void safetyPauseRejoinsTrustedTailAndFailsOnlyAfterExactReturn(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos middle = start.east();
        BlockPos pauseAnchor = middle.east();
        BlockPos displaced = pauseAnchor.east(5);
        BlockPos forbiddenRemoteMine = displaced.north();
        preparePlatform(context, start, 12);
        context.getWorld().setBlockState(forbiddenRemoteMine,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownSafetyPauseGT", middle);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 2));
        DigDownTask task = new DigDownTask(Blocks.STONE, 8,
                descentCheckpoint(start, List.of(start, middle), 8, 2));
        task.start(bot);
        require(context, task.state() == TaskState.RUNNING,
                "fixture DESCEND task did not start: " + task.failureReason());
        require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                        bot, pauseAnchor, "gametest_dig_down_pause_anchor"),
                "fixture could not settle the adjacent pause anchor");

        task.pause(bot);
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, task.state() == TaskState.PAUSED && paused != null,
                "pause did not publish a valid checkpoint: " + task.checkpoint());
        require(context, paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "pause did not convert DESCEND into durable safety RETURN: " + task.checkpoint());
        require(context, paused.trail().equals(List.of(start, middle, pauseAnchor)),
                "pause did not settle the adjacent factual cell: " + paused.trail());
        require(context, paused.returnTrailIndex() == paused.trail().size() - 1,
                "paused return would skip the last trusted cell: " + paused.returnTrailIndex());

        for (int step = 1; step <= 5; step++) {
            BlockPos safetyStep = pauseAnchor.east(step);
            require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                            bot, safetyStep, "gametest_dig_down_safety_displacement"),
                    "fixture could not apply safety displacement step " + step);
        }
        require(context, bot.getBlockPos().equals(displaced),
                "fixture did not end at the non-adjacent safety pose");
        task.resume(bot);

        AtomicBoolean rejoinedPauseAnchor = new AtomicBoolean();
        AtomicBoolean followedOlderTrail = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            BlockPos before = bot.getBlockPos();
            if (before.equals(pauseAnchor)) {
                rejoinedPauseAnchor.set(true);
            }
            if (rejoinedPauseAnchor.get() && before.equals(middle)) {
                followedOlderTrail.set(true);
            }

            task.tick(bot);

            BlockPos after = bot.getBlockPos();
            if (after.equals(pauseAnchor)) {
                rejoinedPauseAnchor.set(true);
            }
            if (rejoinedPauseAnchor.get() && after.equals(middle)) {
                followedOlderTrail.set(true);
            }
            require(context, context.getWorld().getBlockState(forbiddenRemoteMine).isOf(Blocks.STONE),
                    "resumed DigDown mined at the displaced safety pose");
            if (!after.equals(start)) {
                require(context, task.state() == TaskState.RUNNING,
                        "interruption failure escaped before exact return: "
                                + task.state() + ":" + task.failureReason());
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }

            require(context, task.state() == TaskState.FAILED,
                    "interrupted shortfall ended as " + task.state());
            require(context, task.failureReason().equals("dig_down_safety_interrupted collected=2"),
                    "interrupted return lost its typed outcome: " + task.failureReason());
            require(context, after.equals(start),
                    "interrupted DigDown failed away from its exact origin: "
                            + after.toShortString());
            require(context, rejoinedPauseAnchor.get(),
                    "return skipped the last trusted pause anchor");
            require(context, followedOlderTrail.get(),
                    "return did not unwind the older factual trail after rejoining");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 2,
                    "return changed the partial physical delivery");
            finish(context, bot, "DigDownSafetyPauseGT");
        });
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownSafetyReplanRelocationStrict",
            tickLimit = 500)
    public void safetyInterruptedGoalReplanQuarantinesOldEntryAndRelocatesPhysically(
            TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 7, 3));
        BlockPos middle = start.north().down();
        BlockPos tail = middle.north().down();
        BlockPos oldFrontier = tail.north();
        BlockPos nextEntry = start.east(4);

        // Model the already-open factual stair from the evidence run. The only replacement surface
        // column is four blocks east, so the successor must visibly walk there; without quarantine
        // its default NORTH descent immediately steps back into middle and replays this same tunnel.
        for (BlockPos landing : List.of(start, middle, tail)) {
            world.setBlockState(landing.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(landing, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(landing.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(oldFrontier, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (int step = 0; step <= 4; step++) {
            BlockPos surface = start.east(step);
            world.setBlockState(surface.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(surface, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(surface.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "DigDownSafetyReplanGT";
        AIPlayerEntity bot = spawn(context, name, tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival");

        Goal goal = new Goal.HaveItem(Items.COBBLESTONE, 8);
        Map<String, String> taskCheckpoint = strictDescentCheckpoint(
                start, List.of(start, middle, tail), 8, 2);
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(start));
        missionCheckpoint.put("started_tick", String.valueOf(bot.getServer().getTicks()));
        missionCheckpoint.put("revision", "0");
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINE.name());
        taskCheckpoint.forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(),
                        MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false));

        Task restored = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, restored instanceof DigDownTask,
                "fixture did not restore the interrupted MINE cursor: "
                        + (restored == null ? "none" : restored.getClass().getSimpleName()));
        DigDownTask first = (DigDownTask) restored;
        require(context, first.state() == TaskState.RUNNING && bot.getBlockPos().equals(tail),
                "restored DigDown did not own the exact old trail tail");

        TaskManager.INSTANCE.pauseFor(bot, "gametest_hostile_interrupt");
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(first.checkpoint()).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "pause did not publish the typed safety return debt: " + first.checkpoint());
        require(context, !EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUuid(), start, bot.getServer().getTicks()),
                "safety entry TTL started before its exact return debt was paid");
        TaskManager.INSTANCE.resumeFromPause(bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == first
                        && first.state() == TaskState.RUNNING,
                "fixture could not resume the interrupted return owner");

        AtomicReference<DigDownTask> successor = new AtomicReference<>();
        AtomicReference<Integer> replacementArrivalTick = new AtomicReference<>();
        AtomicBoolean observedExactFailedReturn = new AtomicBoolean();
        AtomicBoolean observedPhysicalSurfaceStep = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (first.state() == TaskState.FAILED) {
                observedExactFailedReturn.set(true);
                require(context, bot.getBlockPos().equals(start)
                                || successor.get() != null,
                        "safety interruption failed away from the exact old entry: "
                                + bot.getBlockPos().toShortString());
                require(context, first.failureReason().equals(
                                "dig_down_safety_interrupted collected=2"),
                        "safety return lost its typed reason: " + first.failureReason());
                require(context, EpisodeMemory.INSTANCE.isExcluded(
                                bot.getUuid(), start, bot.getServer().getTicks()),
                        "exact safety settlement did not quarantine the old entry");
            } else {
                require(context, first.state() == TaskState.RUNNING,
                        "original return owner ended unexpectedly as " + first.state());
            }

            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (successor.get() == null && active instanceof DigDownTask next && next != first) {
                require(context, observedExactFailedReturn.get(),
                        "fresh DigDown appeared before the typed return failure settled");
                successor.set(next);
                require(context, next.checkpoint().isEmpty(),
                        "successor opened a mining transaction before physical relocation");
                MissionRuntimeRecord replanned = GoalExecutor.INSTANCE.captureRuntime(bot);
                require(context, replanned.active() != null
                                && "1".equals(replanned.active().checkpoint().get(
                                "lifetime_replans")),
                        "typed interruption did not spend exactly one real replan");
            }

            DigDownTask next = successor.get();
            if (next == null) {
                return;
            }
            BlockPos current = bot.getBlockPos();
            require(context, !current.equals(middle) && !current.equals(tail),
                    "successor re-entered the quarantined factual trail at "
                            + current.toShortString());
            if (current.getY() == start.getY()
                    && current.getZ() == start.getZ()
                    && current.getX() > start.getX()
                    && current.getX() < nextEntry.getX()) {
                observedPhysicalSurfaceStep.set(true);
            }

            Map<String, String> checkpoint = next.checkpoint();
            if (checkpoint.isEmpty()) {
                if (current.equals(nextEntry)) {
                    int now = bot.getServer().getTicks();
                    replacementArrivalTick.compareAndSet(null, now);
                    // Path execution can land after this task's tick, so exactly this arrival
                    // observation may still see the empty relocation cursor. The next observation
                    // must see initializeFreshDescent's exact start_pos anchor.
                    require(context, now == replacementArrivalTick.get(),
                            "successor did not anchor on the tick after replacement arrival");
                    require(context, world.getBlockState(oldFrontier).isOf(Blocks.STONE),
                            "successor mutated the old tunnel while awaiting replacement anchoring");
                } else {
                    require(context, replacementArrivalTick.get() == null,
                            "successor left the replacement entry before anchoring");
                }
                return;
            }
            require(context, encode(nextEntry).equals(checkpoint.get("start_pos")),
                    "successor anchored anywhere other than the physical replacement entry: "
                            + checkpoint.get("start_pos"));
            require(context, current.equals(nextEntry),
                    "replacement checkpoint appeared before exact arrival: "
                            + current.toShortString());
            require(context, observedPhysicalSurfaceStep.get(),
                    "successor reached the replacement entry without observable surface travel");
            require(context, world.getBlockState(oldFrontier).isOf(Blocks.STONE),
                    "successor mutated the old tunnel frontier before relocating");
            require(context, GoalExecutor.INSTANCE.isActiveGoal(bot, goal),
                    "remaining cobblestone goal terminated during safe relocation");
            finish(context, bot, name);
        });
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownPausedRestartReturnStrict",
            tickLimit = 260)
    public void pausedCheckpointRestartKeepsOldEntryReturnDebt(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(4);
        preparePlatform(context, start, 10);

        AIPlayerEntity bot = spawn(context, "DigDownPausedRestartGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE));
        DigDownTask pausedTask = new DigDownTask(Blocks.STONE, 8,
                descentCheckpoint(start, List.of(start, tail), 8, 1));
        pausedTask.start(bot);
        pausedTask.pause(bot);
        Map<String, String> pausedCheckpoint = pausedTask.checkpoint();
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(pausedCheckpoint).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "paused snapshot did not own durable RETURN debt: " + pausedCheckpoint);
        pausedTask.cancel(bot, "simulate_process_restart");

        for (int step = 1; step <= 4; step++) {
            require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                            bot, tail.east(step), "gametest_dig_down_restart_displacement"),
                    "fixture could not apply restart displacement step " + step);
        }
        DigDownTask restoredTask = new DigDownTask(Blocks.STONE, 8, pausedCheckpoint);
        restoredTask.start(bot);
        DigDownTask.DigDownCheckpoint restored = DigDownTask.DigDownCheckpoint
                .decode(restoredTask.checkpoint()).orElse(null);
        require(context, restoredTask.state() == TaskState.RUNNING && restored != null,
                "restarted DigDown rejected the paused checkpoint: "
                        + restoredTask.failureReason());
        require(context, restored.phase() == DigDownTask.Phase.RETURN
                        && restored.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "restart downgraded interrupted RETURN into fresh DESCEND: "
                        + restoredTask.checkpoint());
        require(context, restored.startPos().equals(start)
                        && restored.trail().equals(List.of(start, tail))
                        && restored.returnTrailIndex() == 1,
                "restart lost the old entry/tail return debt: " + restoredTask.checkpoint());

        AtomicBoolean rejoinedTail = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (bot.getBlockPos().equals(tail)) {
                rejoinedTail.set(true);
            }
            restoredTask.tick(bot);
            if (bot.getBlockPos().equals(tail)) {
                rejoinedTail.set(true);
            }
            if (!bot.getBlockPos().equals(start)) {
                require(context, restoredTask.state() == TaskState.RUNNING,
                        "restored interruption failed before exact return: "
                                + restoredTask.state() + ":" + restoredTask.failureReason());
            }
            if (restoredTask.state() == TaskState.RUNNING) {
                return;
            }
            require(context, restoredTask.state() == TaskState.FAILED
                            && restoredTask.failureReason().equals(
                            "dig_down_safety_interrupted collected=1"),
                    "restored return lost its typed terminal outcome: "
                            + restoredTask.state() + ":" + restoredTask.failureReason());
            require(context, rejoinedTail.get() && bot.getBlockPos().equals(start),
                    "restored task did not repay the old factual trail and exact entry");
            finish(context, bot, "DigDownPausedRestartGT");
        });
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownReturnRepauseStrict",
            tickLimit = 220)
    public void returnPauseReanchorsTheCurrentFactualCell(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos middle = start.east();
        BlockPos tail = middle.east();
        BlockPos displaced = middle.east(4);
        preparePlatform(context, start, 10);

        AIPlayerEntity bot = spawn(context, "DigDownReturnRepauseGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE));
        Map<String, String> returnDebt = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 8, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                start, start.getY(), 0, 1, 200, 190, 0, 0, 0, false,
                null, 0, List.of(start, middle, tail),
                1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 8, returnDebt);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(middle),
                "fixture did not settle the next factual return cell");
        DigDownTask.DigDownCheckpoint beforePause = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, beforePause != null && beforePause.returnTrailIndex() == 0,
                "fixture did not advance the return cursor before the second pause");

        task.pause(bot);
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED
                        && paused.returnTrailIndex() == 1,
                "RETURN pause did not restore the current factual cell as its first waypoint: "
                        + task.checkpoint());

        for (int step = 1; step <= 4; step++) {
            require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                            bot, middle.east(step), "gametest_dig_down_return_repause_displacement"),
                    "fixture could not apply RETURN displacement step " + step);
        }
        require(context, bot.getBlockPos().equals(displaced),
                "fixture did not end away from the repaused return trail");
        task.resume(bot);

        AtomicBoolean rejoinedMiddle = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (bot.getBlockPos().equals(middle)) {
                rejoinedMiddle.set(true);
            }
            task.tick(bot);
            if (bot.getBlockPos().equals(middle)) {
                rejoinedMiddle.set(true);
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && task.failureReason().equals(
                            "dig_down_safety_interrupted collected=1"),
                    "repaused return lost its terminal safety outcome: "
                            + task.state() + ":" + task.failureReason());
            require(context, rejoinedMiddle.get() && bot.getBlockPos().equals(start),
                    "repaused return skipped the current factual cell or exact origin");
            finish(context, bot, "DigDownReturnRepauseGT");
        });
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownDisconnectedDescentStrict",
            tickLimit = 40)
    public void disconnectedDescentImmediatelyBecomesSafetyReturn(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(4);
        BlockPos forbiddenRemoteMine = displaced.north();
        preparePlatform(context, start, 10);
        context.getWorld().setBlockState(forbiddenRemoteMine,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownDisconnectedGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE));
        DigDownTask task = new DigDownTask(Blocks.STONE, 8,
                descentCheckpoint(start, List.of(start, tail), 8, 1));
        task.start(bot);
        for (int step = 1; step <= 4; step++) {
            require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                            bot, tail.east(step), "gametest_dig_down_unexpected_displacement"),
                    "fixture could not apply unexpected displacement step " + step);
        }

        task.tick(bot);
        DigDownTask.DigDownCheckpoint interrupted = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, task.state() == TaskState.RUNNING && interrupted != null,
                "disconnected DESCEND failed before opening return debt: "
                        + task.state() + ":" + task.failureReason());
        require(context, interrupted.phase() == DigDownTask.Phase.RETURN
                        && interrupted.returnOutcome()
                        == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED,
                "disconnected DESCEND continued as mining work: " + task.checkpoint());
        require(context, interrupted.trail().equals(List.of(start, tail))
                        && interrupted.returnTrailIndex() == 1,
                "disconnected pose was forged into the factual trail: " + task.checkpoint());
        require(context, context.getWorld().getBlockState(forbiddenRemoteMine).isOf(Blocks.STONE),
                "disconnected DESCEND mined at the untrusted remote pose");
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DigDownDisconnectedGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 800)
    public void minedStoneReturnsAlongRecordedStaircaseBeforeCompleting(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(6, 5, 8));
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -8; dz <= 4; dz++) {
                for (int dy = -8; dy <= -1; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }

        String name = "DigDownReturnGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        180.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 180.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));

        DigDownTask task = new DigDownTask(Blocks.STONE, 3);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_return"));
        AtomicBoolean descended = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (bot.getBlockPos().getY() < start.getY()) {
                descended.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("DigDown return task ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, descended.get(), "fixture never exercised a lower staircase cell");
            require(context, bot.getBlockPos().equals(start),
                    "DigDown completed away from its exact surface origin: start=" + start.toShortString()
                            + " end=" + bot.getBlockPos().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 3,
                    "DigDown returned without the requested physical stone drops");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownEntryRelocationStrict", tickLimit = 900)
    public void rememberedWalledEntryPhysicallyRelocatesBeforeMining(TestContext context) {
        var world = context.getWorld();
        BlockPos failedEntry = context.getAbsolutePos(new BlockPos(1, 6, 3));
        BlockPos nextEntry = context.getAbsolutePos(new BlockPos(5, 6, 3));
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) {
                for (int y = 1; y <= 5; y++) {
                    world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (int y = 6; y <= 7; y++) {
                    world.setBlockState(context.getAbsolutePos(new BlockPos(x, y, z)),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        // Buried water is intentionally unobservable here. Relocation is triggered solely by the
        // prior factual WALLED memory below, never by scanning this hidden block.
        world.setBlockState(failedEntry.down(2), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownEntryRelocationGT", failedEntry);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival");
        require(context, !CapabilityRuntime.decide(bot,
                        PrivilegedCapability.EMERGENCY_TELEPORT,
                        "dig_down_entry_relocation_gametest").allowed(),
                "strict GameTest unexpectedly allowed emergency teleport");
        EpisodeMemory.INSTANCE.exclude(bot.getUuid(), failedEntry,
                bot.getServer().getTicks(), EpisodeMemory.TTL_UNREACHABLE);
        require(context, EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUuid(), failedEntry, bot.getServer().getTicks()),
                "fixture did not remember the observed WALLED entry");

        DigDownTask task = new DigDownTask(Blocks.STONE, 1);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_entry_relocation"));
        require(context, bot.getBlockPos().equals(failedEntry),
                "DigDown teleported while starting entry relocation");
        require(context, task.checkpoint().isEmpty(),
                "entry relocation started a mining transaction before arrival");

        AtomicBoolean observedPhysicalStep = new AtomicBoolean();
        AtomicBoolean reachedNextEntry = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            BlockPos current = bot.getBlockPos();
            if (current.getY() == failedEntry.getY()
                    && current.getX() > failedEntry.getX()
                    && current.getX() < nextEntry.getX()
                    && current.getZ() == failedEntry.getZ()) {
                observedPhysicalStep.set(true);
            }
            if (current.equals(nextEntry)) {
                reachedNextEntry.set(true);
            }
            if (!reachedNextEntry.get()) {
                require(context, task.checkpoint().isEmpty(),
                        "mining checkpoint appeared before reaching the replacement entry");
            }
            if (!task.checkpoint().isEmpty()) {
                require(context, encode(nextEntry).equals(task.checkpoint().get("start_pos")),
                        "mining transaction anchored somewhere other than the replacement entry");
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("entry relocation DigDown ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, observedPhysicalStep.get(),
                    "DigDown reached the replacement entry without observable surface movement");
            require(context, reachedNextEntry.get(),
                    "DigDown never reached the replacement entry");
            require(context, bot.getBlockPos().equals(nextEntry),
                    "DigDown did not return exactly to its replacement entry: "
                            + bot.getBlockPos().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 1,
                    "DigDown returned without a physical stone drop");
            require(context, world.getBlockState(failedEntry.down(2)).isOf(Blocks.WATER),
                    "DigDown inspected by mutating the hidden fixture block");
            finish(context, bot, "DigDownEntryRelocationGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 300)
    public void satisfiedMissionStillRestoresReturnDebtBeforeCompleting(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 8);
        BlockPos far = start.add(4, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownMissionReturnGT", far);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));

        List<BlockPos> trail = List.of(start, start.add(1, 0, 0), start.add(2, 0, 0),
                start.add(3, 0, 0), far);
        Map<String, String> taskCheckpoint = returnCheckpoint(start, trail, 3, 0, false);
        Goal goal = new Goal.HaveItem(Items.COBBLESTONE, 3);
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(start));
        missionCheckpoint.put("started_tick", String.valueOf(bot.getServer().getTicks()));
        missionCheckpoint.put("revision", "0");
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINE.name());
        taskCheckpoint.forEach((key, value) -> missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(), MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, active instanceof DigDownTask,
                "satisfied restore skipped return debt: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, active.state() == TaskState.RUNNING,
                "restored DigDown did not start: " + active.state() + ":" + active.failureReason());
        require(context, !bot.getBlockPos().equals(start), "fixture did not start away from the origin");

        context.runAtEveryTick(() -> {
            if (active.state() == TaskState.FAILED || active.state() == TaskState.CANCELLED) {
                context.throwGameTestException("restored return failed: " + active.failureReason());
            }
            if (GoalExecutor.INSTANCE.isActiveGoal(bot, goal)) {
                require(context, active.state() != TaskState.COMPLETED || bot.getBlockPos().equals(start),
                        "same-Y return published completion away from start");
                return;
            }
            require(context, bot.getBlockPos().equals(start),
                    "Mission settled before exact return: " + bot.getBlockPos().toShortString());
            finish(context, bot, "DigDownMissionReturnGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void exhaustedReturnBudgetFailsOnItsOwnClock(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 5);
        BlockPos far = start.add(2, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownBudgetGT", far);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        List<BlockPos> trail = List.of(start, start.add(1, 0, 0), far);
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, trail, 1, 600, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_return_budget"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "exhausted return budget ended as " + task.state());
            require(context, task.failureReason().startsWith("dig_down_return_failed"),
                    "unexpected return budget reason: " + task.failureReason());
            require(context, bot.getBlockPos().equals(far),
                    "budget exhaustion moved the bot before failing");
            finish(context, bot, "DigDownBudgetGT");
        });
    }

    @GameTest(
            templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "digDownSafetyRecoveryBudgetStrict",
            tickLimit = 2600)
    public void safetyDisplacementCanDigBackAfterLegacyReturnLimit(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 24, 3));
        List<BlockPos> trail = new java.util.ArrayList<>();
        for (int step = 0; step <= 18; step++) {
            BlockPos feet = start.add(step, -step, 0);
            trail.add(feet);
            context.getWorld().setBlockState(feet.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(feet,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(feet.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos tail = trail.getLast();
        int displacement = 16;
        BlockPos displaced = tail.south(displacement);
        for (int step = 1; step <= displacement; step++) {
            BlockPos corridor = tail.south(step);
            context.getWorld().setBlockState(corridor.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(corridor,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(corridor.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(corridor.up(2),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DigDownSafetyRecoveryGT", tail);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask interrupted = new DigDownTask(Blocks.STONE, 3,
                descentCheckpoint(start, trail, 3, 3));
        interrupted.start(bot);
        require(context, interrupted.state() == TaskState.RUNNING,
                "fixture DESCEND task did not start: " + interrupted.failureReason());
        interrupted.pause(bot);
        Map<String, String> rawPausedCheckpoint = interrupted.checkpoint();
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(rawPausedCheckpoint).orElse(null);
        require(context, paused != null
                        && paused.phase() == DigDownTask.Phase.RETURN
                        && paused.returnOutcome() == DigDownTask.ReturnOutcome.SAFETY_INTERRUPTED
                        && paused.returnTrailIndex() == trail.size() - 1
                        && paused.returnBudgetUsed() == 0
                        && paused.lastReturnProgressBudget() == 0
                        && paused.returnBestDistanceSquared() == 0L
                        && paused.returnSafetyRecovery(),
                "pause did not publish the raw pre-displacement safety checkpoint: "
                        + rawPausedCheckpoint);

        for (int step = 1; step <= displacement; step++) {
            require(context, io.github.zoyluo.aibot.mode.FakePlayerMotion.stepToStandable(
                            bot, tail.south(step), "gametest_dig_down_restart_displacement"),
                    "fixture could not apply safety displacement step " + step);
        }
        require(context, bot.getBlockPos().equals(displaced),
                "fixture did not end at the displaced restart pose");
        for (int step = 1; step < displacement; step++) {
            BlockPos wall = tail.south(step);
            context.getWorld().setBlockState(wall,
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(wall.up(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        interrupted.cancel(bot, "simulate_paused_process_restart");

        DigDownTask task = new DigDownTask(Blocks.STONE, 3, rawPausedCheckpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_safety_recovery"));
        DigDownTask.DigDownCheckpoint restored = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, restored != null
                        && restored.returnBudgetUsed() == 0
                        && restored.lastReturnProgressBudget() == 0
                        && restored.returnProgressWaypointIndex() == trail.size() - 1
                        && restored.returnBestDistanceSquared() == squaredDistance(displaced, tail),
                "restart did not rebase only the physical return distance: " + task.checkpoint());
        AtomicBoolean crossedLegacyLimit = new AtomicBoolean();
        AtomicBoolean crossedLegacyLimitBeforeRejoin = new AtomicBoolean();
        AtomicBoolean physicallyDugRejoin = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            if (live != null && live.returnBudgetUsed() > 600) {
                crossedLegacyLimit.set(true);
                if (!bot.getBlockPos().equals(tail)) {
                    crossedLegacyLimitBeforeRejoin.set(true);
                }
            }
            if (context.getWorld().getBlockState(tail.south(1)).isAir()
                    || context.getWorld().getBlockState(tail.south(1).up()).isAir()) {
                physicallyDugRejoin.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("safety-expanded return ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, crossedLegacyLimit.get(),
                    "safety recovery never crossed the old 600-tick return boundary");
            require(context, crossedLegacyLimitBeforeRejoin.get(),
                    "safety recovery crossed 600 ticks only after rejoining the factual tail");
            require(context, physicallyDugRejoin.get(),
                    "safety recovery did not physically dig through the displaced rejoin wall");
            require(context, bot.getBlockPos().equals(start),
                    "safety recovery completed away from the exact mine entry: "
                            + bot.getBlockPos().toShortString());
            finish(context, bot, "DigDownSafetyRecoveryGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void safetyReturnHardCapSurvivesPauseAndResume(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(6);
        preparePlatform(context, start, 12);
        AIPlayerEntity bot = spawn(context, "DigDownSafetyHardCapGT", displaced);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                safetyReturnCheckpoint(start, List.of(start, tail), 1,
                        2399, 2399, squaredDistance(displaced, tail)));
        task.start(bot);
        task.pause(bot);
        DigDownTask.DigDownCheckpoint paused = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, paused != null
                        && paused.returnBudgetUsed() == 2399
                        && paused.lastReturnProgressBudget() == 2399
                        && paused.returnSafetyRecovery(),
                "pause did not persist a fresh stall lease without resetting the hard clock");
        task.resume(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "safety return rejected its inclusive 2400-tick boundary");
        DigDownTask.DigDownCheckpoint atBoundary = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, atBoundary != null && atBoundary.returnBudgetUsed() == 2400,
                "pause/resume reset or corrupted the persisted return hard clock");

        task.pause(bot);
        task.resume(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith(
                        "dig_down_return_failed:hard_limit"),
                "safety return did not fail closed beyond 2400 ticks: "
                        + task.state() + ":" + task.failureReason());
        require(context, !bot.getBlockPos().equals(start),
                "hard-cap fixture unexpectedly settled the exact return debt");
        finish(context, bot, "DigDownSafetyHardCapGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void safetyReturnStallLeaseFailsBeforeHardCap(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos tail = start.east();
        BlockPos displaced = tail.east(6);
        preparePlatform(context, start, 12);
        AIPlayerEntity bot = spawn(context, "DigDownSafetyStallGT", displaced);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                safetyReturnCheckpoint(start, List.of(start, tail), 1,
                        600, 0, 0L));
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("dig_down_return_failed:stalled"),
                "stalled safety return escaped its 600-tick no-progress lease: "
                        + task.state() + ":" + task.failureReason());
        require(context, !bot.getBlockPos().equals(start),
                "stall fixture unexpectedly settled the exact return debt");
        finish(context, bot, "DigDownSafetyStallGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 240)
    public void timeoutWithRequestedNetDeliveryCompletesOnlyAfterExactReturn(TestContext context) {
        BlockPos bottom = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos middle = bottom.east().up();
        BlockPos start = middle.east().up();
        for (BlockPos feet : List.of(bottom, middle, start)) {
            context.getWorld().setBlockState(feet.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(feet,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(feet.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerEntity bot = spawn(context, "DigDownTimeoutNetGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 6));
        List<BlockPos> trail = List.of(start, middle, bottom);
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 6, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.TIMEOUT,
                start, bottom.getY(), 0, 6, 2400, 2399, 0, 0, 0, false,
                null, 0, trail, 1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 6, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_timeout_net_return"));
        java.util.concurrent.atomic.AtomicBoolean moved =
                new java.util.concurrent.atomic.AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (!bot.getBlockPos().equals(bottom)) {
                moved.set(true);
            }
            if (!bot.getBlockPos().equals(start)) {
                require(context, task.state() == TaskState.RUNNING,
                        "net delivery forgave the outstanding exact-return debt: "
                                + task.state() + ":" + task.failureReason());
                return;
            }
            // GameTest callbacks can observe the physical micro-step before TaskManager's next
            // tick settles the exact-return terminal state.
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "exact return did not promote satisfied timeout to completion: "
                            + task.state() + ":" + task.failureReason());
            require(context, moved.get(), "fixture never exercised a physical return step");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) >= 6,
                    "completion lost the requested net stone delivery");
            finish(context, bot, "DigDownTimeoutNetGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void restoredLargeQuotaContinuesPastLegacyBudgetAndKeepsNetDeliveryStrict(
            TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 4, 3));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= -1; dy++) {
                    context.getWorld().setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    context.getWorld().setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        AIPlayerEntity bot = spawn(context, "DigDownScaledRestoreGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 61));
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 64, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 61, 2400, 2400, 0, 0, 0, false,
                null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 64, checkpoint);
        task.start(bot);

        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "restored 64-block batch timed out at the legacy 2400-tick boundary: "
                        + task.failureReason());
        Map<String, String> resumed = task.checkpoint();
        require(context, "2401".equals(resumed.get("work_budget_used"))
                        && DigDownTask.Phase.DESCEND.name().equals(resumed.get("phase")),
                "continued large-batch checkpoint was not durable past tick 2400: " + resumed);

        // The restored batch still owns an incremental 64-block delivery. Only the remaining
        // physical inventory gain may complete it; scaling the clock must not forgive 61/64.
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "net delivery completed before the exact-return phase was settled");
        task.tick(bot);
        require(context, task.state() == TaskState.COMPLETED,
                "restored large batch did not complete after exact 64/64 delivery and return: "
                        + task.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "restored large batch completed away from its exact entry");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 64,
                "restored large batch changed the strict net-delivery target");
        finish(context, bot, "DigDownScaledRestoreGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 240)
    public void horizontalOpenCorridorAdvancesFactuallyAndNeverBacktracks(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(6, 5, 18));
        int openCells = 12;
        for (int distance = 0; distance <= openCells; distance++) {
            BlockPos feet = start.north(distance);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerEntity bot = spawn(context, "DigDownHorizontalCorridorGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 1, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 0, 0, 0, 0, 0, 0, true,
                null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 1, checkpoint);
        task.start(bot);

        for (int distance = 1; distance <= openCells; distance++) {
            task.tick(bot);
            BlockPos expected = start.north(distance);
            require(context, task.state() == TaskState.RUNNING,
                    "horizontal corridor task ended before the first solid boundary: "
                            + task.state() + ":" + task.failureReason());
            require(context, bot.getBlockPos().equals(expected),
                    "horizontal corridor did not advance exactly one factual cell on step "
                            + distance + ": expected=" + expected.toShortString()
                            + " actual=" + bot.getBlockPos().toShortString());
            DigDownTask.DigDownCheckpoint advanced = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            require(context, advanced != null
                            && advanced.trail().size() == distance + 1
                            && advanced.trail().getLast().equals(expected),
                    "horizontal corridor step was not durably appended to the return trail: "
                            + task.checkpoint());
        }
        // NORTH/EAST/WEST beyond the endpoint are unsupported; SOUTH is the already recorded
        // corridor. A mining frontier must not reinterpret that return trail as fresh work.
        task.tick(bot);
        require(context, bot.getBlockPos().equals(start.north(openCells)),
                "horizontal endpoint backtracked into its already recorded return trail");
        require(context, task.state() == TaskState.RUNNING,
                "horizontal endpoint exposed failure during its physical pickup settle tick");
        DigDownTask.DigDownCheckpoint settling = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, settling != null
                        && settling.phase() == DigDownTask.Phase.DESCEND
                        && settling.trail().size() == openCells + 1,
                "horizontal endpoint did not preserve DESCEND during pickup settlement: "
                        + task.checkpoint());
        for (int settleTick = 0; settleTick < 40
                && DigDownTask.DigDownCheckpoint.decode(task.checkpoint())
                .map(checkpointValue -> checkpointValue.phase() == DigDownTask.Phase.DESCEND)
                .orElse(false); settleTick++) {
            task.tick(bot);
        }
        DigDownTask.DigDownCheckpoint returning = DigDownTask.DigDownCheckpoint
                .decode(task.checkpoint()).orElse(null);
        require(context, returning != null
                        && returning.phase() == DigDownTask.Phase.RETURN
                        && returning.returnOutcome() == DigDownTask.ReturnOutcome.WALLED
                        && returning.trail().size() == openCells + 1,
                "closed horizontal frontier did not preserve a typed exact-return debt: "
                        + task.checkpoint());

        for (int i = 0; i < openCells + 3 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
        }
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("dig_down_walled"),
                "closed horizontal frontier did not settle as WALLED after exact return: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "closed horizontal frontier failed away from its exact origin: "
                        + bot.getBlockPos().toShortString());
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), "DigDownHorizontalCorridorGT");

        BlockPos frontierStart = context.getAbsolutePos(new BlockPos(8, 5, 8));
        BlockPos frontier = frontierStart.north();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    world.setBlockState(frontierStart.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(frontierStart.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(frontier.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(frontier, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity frontierBot = spawn(
                context, "DigDownHorizontalFrontierGT", frontierStart);
        InventoryAction.giveItem(frontierBot, new ItemStack(Items.WOODEN_PICKAXE));
        Map<String, String> frontierCheckpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 1, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                frontierStart, frontierStart.getY(), 0, 0, 0, 0, 0, 0, 0, true,
                null, 0, List.of(frontierStart), -1, 0, -20, false).encode();
        DigDownTask frontierTask = new DigDownTask(
                Blocks.STONE, 1, frontierCheckpoint);
        frontierTask.start(frontierBot);
        AtomicBoolean frontierBroken = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (frontierTask.state() == TaskState.RUNNING) {
                frontierTask.tick(frontierBot);
            }
            if (world.getBlockState(frontier).isAir()) {
                frontierBroken.set(true);
            }
            if (frontierTask.state() == TaskState.FAILED
                    || frontierTask.state() == TaskState.CANCELLED) {
                context.throwGameTestException("supported horizontal frontier ended as "
                        + frontierTask.state() + ":" + frontierTask.failureReason()
                        + " checkpoint=" + frontierTask.checkpoint());
            }
            if (frontierTask.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, frontierBroken.get(),
                    "horizontal task completed without physically mining the stone frontier");
            require(context, InventoryAction.countItem(frontierBot, Items.COBBLESTONE) == 1,
                    "horizontal task did not physically collect its exact stone delivery");
            require(context, frontierBot.getBlockPos().equals(frontierStart),
                    "horizontal stone delivery completed away from its exact origin");
            AIPlayerManager.INSTANCE.despawn(
                    frontierBot.getServer(), "DigDownHorizontalFrontierGT");
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 180)
    public void nearBudgetHorizontalPickupDebtSurvivesRestartAndSettlesBeforeTimeout(
            TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(6, 5, 6));
        BlockPos frontier = start.north();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -3; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(frontier.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(frontier, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownNearBudgetSettleGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERITE_PICKAXE));
        int maxBudget = DigDownTask.maxWorkBudgetForTarget("minecraft:stone", 1);
        Map<String, String> nearBudget = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 1, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 0, maxBudget - 8, maxBudget - 8,
                0, 0, 0, true, null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask initial = new DigDownTask(Blocks.STONE, 1, nearBudget);
        initial.start(bot);

        AtomicReference<DigDownTask> active = new AtomicReference<>(initial);
        AtomicReference<ItemEntity> delayedDrop = new AtomicReference<>();
        AtomicBoolean pickupReleased = new AtomicBoolean();
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicBoolean crossedHardBoundary = new AtomicBoolean();
        AtomicBoolean frontierBroken = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            DigDownTask task = active.get();
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (world.getBlockState(frontier).isAir()) {
                frontierBroken.set(true);
            }
            if (!pickupReleased.get() && delayedDrop.get() == null) {
                world.getEntitiesByClass(ItemEntity.class, bot.getBoundingBox().expand(4.0D),
                                drop -> drop.isAlive() && drop.getStack().isOf(Items.COBBLESTONE))
                        .stream().findFirst().ifPresent(drop -> {
                            drop.setPickupDelayInfinite();
                            delayedDrop.set(drop);
                        });
            }

            DigDownTask.DigDownCheckpoint live = DigDownTask.DigDownCheckpoint
                    .decode(task.checkpoint()).orElse(null);
            if (!restarted.get() && live != null
                    && live.phase() == DigDownTask.Phase.DESCEND
                    && live.pickupGrace() > 0) {
                require(context, live.horizontalMode(),
                        "pickup settlement debt was not tied to horizontal mode");
                require(context, live.workBudgetUsed() <= maxBudget
                                && live.workBudgetUsed() >= maxBudget - 30,
                        "settlement debt was not durably bounded near the hard budget: "
                                + task.checkpoint());
                Map<String, String> saved = task.checkpoint();
                task.abort(bot);
                DigDownTask restored = new DigDownTask(Blocks.STONE, 1, saved);
                restored.start(bot);
                active.set(restored);
                restarted.set(true);
                return;
            }

            if (restarted.get() && task == active.get() && live != null
                    && live.phase() == DigDownTask.Phase.DESCEND
                    && live.pickupGrace() > 0
                    && live.workBudgetUsed() == maxBudget) {
                crossedHardBoundary.set(true);
                ItemEntity held = delayedDrop.getAndSet(null);
                if (held != null && held.isAlive()) {
                    held.resetPickupDelay();
                }
                pickupReleased.set(true);
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("near-budget settlement ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get(),
                    "fixture never restarted an active horizontal settlement debt");
            require(context, crossedHardBoundary.get(),
                    "restored settlement did not remain live at the hard work boundary");
            require(context, frontierBroken.get(),
                    "near-budget task completed without physically breaking the frontier");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 1,
                    "settlement did not deliver the exact physical cobblestone drop");
            require(context, bot.getBlockPos().equals(start),
                    "settled batch completed away from its exact entry: "
                            + bot.getBlockPos().toShortString());
            finish(context, bot, "DigDownNearBudgetSettleGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 240)
    public void unsafeRecordedLandingIsSkippedAndReturnStillCompletes(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 5);
        BlockPos unsafe = start.add(1, 0, 0);
        BlockPos end = start.add(2, 0, 0);
        context.getWorld().setBlockState(unsafe.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = spawn(context, "DigDownUnsafeReturnGT", end);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, List.of(start, unsafe, end), 1, 0, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_unsafe_return"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("unsafe return failed: "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, !bot.getBlockPos().equals(unsafe),
                    "return completed on an unsupported landing");
            require(context, bot.getBlockPos().equals(start),
                    "return skipped the stale waypoint but did not settle exact origin: "
                            + bot.getBlockPos().toShortString());
            finish(context, bot, "DigDownUnsafeReturnGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 240)
    public void unsupportedAscendingWaypointGetsPhysicalSupportBeforeExactReturn(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(6, 6, 6));
        BlockPos unsupported = start.west();
        BlockPos bottom = unsupported.west().down();
        for (BlockPos body : List.of(start, start.up(), unsupported, unsupported.up(),
                bottom, bottom.up())) {
            context.getWorld().setBlockState(body, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        context.getWorld().setBlockState(bottom.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // The ascending intermediate loses its Y-1 support. A deeper solid remains available as
        // the vanilla placement face for the single adjacent repair optimization.
        context.getWorld().setBlockState(unsupported.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(unsupported.down(2),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownSupportRepairGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, List.of(start, unsupported, bottom), 1, 0, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_support_repair"));

        AtomicBoolean repaired = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (!context.getWorld().getBlockState(unsupported.down()).isAir()) {
                repaired.set(true);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("support-repair return failed: "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, repaired.get(),
                    "DigDown skipped the unsupported ascending waypoint instead of repairing it");
            require(context, bot.getBlockPos().equals(start),
                    "support-repair return did not settle the exact origin: "
                            + bot.getBlockPos().toShortString());
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 3,
                    "support repair did not pay exactly one physical cobblestone");
            finish(context, bot, "DigDownSupportRepairGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 320)
    public void unsupportedExactEntryUsesTwoPhysicalPillarsInsteadOfSnapping(TestContext context) {
        BlockPos bottom = context.getAbsolutePos(new BlockPos(6, 4, 6));
        BlockPos middle = bottom.up();
        BlockPos start = bottom.up(2);
        for (int dy = 0; dy <= 3; dy++) {
            context.getWorld().setBlockState(bottom.up(dy),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        context.getWorld().setBlockState(bottom.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DigDownExactPillarsGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        DigDownTask task = new DigDownTask(Blocks.STONE, 3,
                returnCheckpoint(start, List.of(start, middle, bottom), 1, 0, false));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_exact_pillars"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("two-pillar exact return failed: "
                        + task.state() + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, bot.getBlockPos().equals(start),
                    "two-pillar return snapped below the exact entry: "
                            + bot.getBlockPos().toShortString());
            require(context, context.getWorld().getBlockState(bottom).isOf(Blocks.DIRT)
                            && context.getWorld().getBlockState(middle).isOf(Blocks.DIRT),
                    "exact return did not place both physical dirt pillars");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                            "exact return did not spend exactly two dirt supports");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 3,
                    "pillar repair consumed the requested net cobblestone delivery");
            finish(context, bot, "DigDownExactPillarsGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void restoredDescentFailureReturnsBeforePublishingFailure(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 5);
        BlockPos middle = start.add(1, 0, 0);
        BlockPos end = start.add(2, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownFailureReturnGT", end);
        List<BlockPos> trail = List.of(start, middle, end);
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 8, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.TIMEOUT,
                start, start.getY(), 0, 2, 2400, 2399, 0, 0, 0, false,
                null, 0, trail, 1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 8, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_dig_down_failure_return"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                require(context, !task.failureReason().startsWith("dig_down_timeout"),
                        "failure leaked before exact return completed");
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "failure recovery ended as " + task.state());
            require(context, task.failureReason().equals("dig_down_timeout collected=2"),
                    "failure recovery lost its typed terminal outcome: " + task.failureReason());
            require(context, bot.getBlockPos().equals(start),
                    "failure was published below/away from the original entry: "
                            + bot.getBlockPos().toShortString());
            finish(context, bot, "DigDownFailureReturnGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void promotedSchema2ReturnClearsLocalWaterSealOwnership(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 3);
        BlockPos end = start.add(1, 0, 0);
        AIPlayerEntity bot = spawn(context, "DigDownPromotedReturnGT", end);
        List<BlockPos> trail = List.of(start, end);
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                2, "minecraft:stone", 3, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 3, 200, 190, 0, 0, 0, false,
                end, 1, trail, -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 3, checkpoint);
        task.start(bot);

        Map<String, String> promoted = task.checkpoint();
        DigDownTask.DigDownCheckpoint decoded =
                DigDownTask.DigDownCheckpoint.decode(promoted).orElse(null);
        require(context, decoded != null && decoded.phase() == DigDownTask.Phase.RETURN,
                "satisfied DESCEND checkpoint was not promoted to a durable return: " + promoted);
        require(context, decoded.rejectedLandingOrigin() == null
                        && decoded.rejectedLandingDirections() == 0,
                "promoted return retained stale water-seal direction ownership: " + promoted);
        task.cancel(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), "DigDownPromotedReturnGT");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void missingMineCheckpointIsRejectedEvenWhenGoalIsSatisfied(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownMissingCheckpointGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        Goal goal = new Goal.HaveItem(Items.COBBLESTONE, 3);
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(start));
        missionCheckpoint.put("started_tick", String.valueOf(bot.getServer().getTicks()));
        missionCheckpoint.put("revision", "0");
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINE.name());

        GoalExecutor.INSTANCE.restoreRuntime(bot, new MissionRuntimeRecord(
                new MissionRecord(UUID.randomUUID().toString(),
                        MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false));
        GoalResult result = GoalExecutor.INSTANCE.lastResult(bot).orElse(null);
        require(context, result != null
                        && "mission_restore_invalid_dig_down_checkpoint".equals(result.reason()),
                "missing MINE cursor bypassed fail-closed restore: "
                        + (result == null ? "no_result" : result.reason()));
        require(context, !GoalExecutor.INSTANCE.hasActivePlan(bot),
                "missing MINE cursor restored an active mission");
        finish(context, bot, "DigDownMissingCheckpointGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void exactReturnRejectsGrossCollectionWithNetDeliveryShortfall(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownNetDeliveryGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 2));
        Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, start.getY(), 0, 7, 200, 190, 0, 0, 0, false,
                null, 0, List.of(start), -1, 0, -20, false).encode();
        DigDownTask task = new DigDownTask(Blocks.STONE, 3, checkpoint);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED,
                "gross collection incorrectly completed with only two delivered blocks");
        require(context, task.failureReason().equals(
                        "dig_down_net_delivery_shortfall:have=2:required=3"),
                "wrong net-delivery failure: " + task.failureReason());
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), "DigDownNetDeliveryGT");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 240)
    public void grossReservePaysTwoPillarsAndStillDeliversRequestedStone(TestContext context) {
        BlockPos bottom = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos start = bottom.up(2);
        context.getWorld().setBlockState(bottom.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (int dy = 0; dy <= 4; dy++) {
            context.getWorld().setBlockState(bottom.up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        AIPlayerEntity bot = spawn(context, "DigDownNetPillarsGT", bottom);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 5));
        Node origin = new Node(bottom, 0.0D, 2.0D, MoveType.WALK, null);
        Node middle = new Node(bottom.up(), 1.0D, 1.0D, MoveType.PILLAR_UP, origin);
        Node upper = new Node(start, 2.0D, 0.0D, MoveType.PILLAR_UP, middle);
        PathExecutor executor = new PathExecutor(List.of(origin, middle, upper), start);

        context.runAtEveryTick(() -> {
            if (!bot.getBlockPos().equals(start)) {
                var result = executor.tick(bot.getActionPack());
                if (result.isFailed()) {
                    context.throwGameTestException("two-pillar return failed: " + result);
                }
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 3,
                    "two physical pillar repairs did not preserve the requested net delivery");
            require(context, context.getWorld().getBlockState(bottom).isOf(Blocks.COBBLESTONE)
                            && context.getWorld().getBlockState(bottom.up()).isOf(Blocks.COBBLESTONE),
                    "fixture did not pay two factual cobblestone pillar repairs");

            // The same gross reserve now arrives at the exact DigDown origin. Completion must use
            // the three delivered blocks, not the historical gross count of five.
            Map<String, String> checkpoint = new DigDownTask.DigDownCheckpoint(
                    3, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                    DigDownTask.ReturnOutcome.COMPLETE,
                    start, start.getY(), 0, 5, 200, 190, 0, 0, 0, false,
                    null, 0, List.of(start), -1, 0, -20, false).encode();
            DigDownTask task = new DigDownTask(Blocks.STONE, 3, checkpoint);
            task.start(bot);
            task.tick(bot);
            require(context, task.state() == TaskState.COMPLETED,
                    "exact return rejected the requested net delivery: " + task.failureReason());
            finish(context, bot, "DigDownNetPillarsGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void pillarRepairSpendsDirtBeforeMissionCobblestone(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "DigDownPillarMaterialGT", start);
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 6));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        Node origin = new Node(start, 0.0D, 1.0D, MoveType.WALK, null);
        Node upper = new Node(start.up(), 1.0D, 0.0D, MoveType.PILLAR_UP, origin);
        PathExecutor executor = new PathExecutor(List.of(origin, upper), upper.pos());

        context.runAtEveryTick(() -> {
            var result = executor.tick(bot.getActionPack());
            if (result.isFailed()) {
                context.throwGameTestException("fixture pillar failed: " + result);
            }
            if (!bot.getBlockPos().equals(start.up())) {
                return;
            }
            require(context, context.getWorld().getBlockState(start).isOf(Blocks.DIRT),
                    "pillar repair did not place the preferred disposable dirt");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 1,
                    "pillar repair consumed the wrong dirt count");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 6,
                    "pillar repair spent mission cobblestone while dirt was available");

            executor.abort(bot.getActionPack());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), "DigDownPillarMaterialGT");
            context.complete();
        });
    }

    private static Map<String, String> returnCheckpoint(BlockPos start,
                                                         List<BlockPos> trail,
                                                         int returnIndex,
                                                         int returnBudget,
                                                         boolean fallback) {
        return new DigDownTask.DigDownCheckpoint(
                1, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, trail.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                0, 3, 200, 190, 0, 0, 0, false,
                null, 0, trail,
                returnIndex, returnBudget, -20, fallback).encode();
    }

    private static Map<String, String> safetyReturnCheckpoint(BlockPos start,
                                                               List<BlockPos> trail,
                                                               int returnIndex,
                                                               int returnBudget,
                                                               int lastProgressBudget,
                                                               long bestDistanceSquared) {
        int targetY = trail.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        return new DigDownTask.DigDownCheckpoint(
                4, "minecraft:stone", 3, DigDownTask.Phase.RETURN,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, targetY, 0, 3, 200, 190, 0,
                0, 0, false, null, 0, trail,
                returnIndex, returnBudget, -20, false,
                lastProgressBudget, returnIndex, bestDistanceSquared, true).encode();
    }

    private static long squaredDistance(BlockPos from, BlockPos to) {
        long dx = (long) from.getX() - to.getX();
        long dy = (long) from.getY() - to.getY();
        long dz = (long) from.getZ() - to.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Map<String, String> descentCheckpoint(BlockPos start,
                                                          List<BlockPos> trail,
                                                          int targetCount,
                                                          int collected) {
        return new DigDownTask.DigDownCheckpoint(
                3, "minecraft:stone", targetCount, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, trail.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                0, collected, 200, 190, 0, 0, 0, false,
                null, 0, trail, -1, 0, -20, false).encode();
    }

    private static Map<String, String> strictDescentCheckpoint(BlockPos start,
                                                                List<BlockPos> trail,
                                                                int targetCount,
                                                                int collected) {
        return new DigDownTask.DigDownCheckpoint(
                4, "minecraft:stone", targetCount, DigDownTask.Phase.DESCEND,
                DigDownTask.ReturnOutcome.COMPLETE,
                start, trail.stream().mapToInt(BlockPos::getY).min().orElseThrow(),
                0, collected, 200, 190, 0, 0, 0, false,
                null, 0, trail, -1, 0, -20, false,
                0, -1, -1L, false).encode();
    }

    private static void preparePlatform(TestContext context, BlockPos start, int radius) {
        for (int dx = -1; dx <= radius; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                context.getWorld().setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    context.getWorld().setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos pos) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(), Vec3d.ofBottomCenter(pos),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static void finish(TestContext context, AIPlayerEntity bot, String name) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        GoalExecutor.INSTANCE.unload(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
