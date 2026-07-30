package io.github.zoyluo.aibot.task;

import com.google.gson.Gson;
import io.github.zoyluo.aibot.action.ContainerAction;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.goal.Goal;
import io.github.zoyluo.aibot.goal.GoalExecutor;
import io.github.zoyluo.aibot.goal.GoalPlanner;
import io.github.zoyluo.aibot.goal.GoalStep;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.memory.BotMemoryStore;
import io.github.zoyluo.aibot.mining.MiningEvidenceAudit;
import io.github.zoyluo.aibot.mining.MiningCursor;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.persist.MissionRecord;
import io.github.zoyluo.aibot.persist.MissionRuntimeRecord;
import io.github.zoyluo.aibot.persist.MissionSpec;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.LightType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Mission-level restart boundaries for the 32-block obsidian expedition. */
public final class CreateObsidianMissionRecoveryGameTests implements FabricGameTest {
    private static final int TARGET = 32;
    private static final int TARGET_BUDGET = 76800;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void fifteenOfThirtyTwoRestoresMakeObsidianWithoutDroppingCheckpoint(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMission15GT", 15, true);
        Map<String, String> taskCheckpoint = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SCAN, 15, null);

        restore(context, fixture, taskCheckpoint);

        assertRunningMakeObsidian(context, fixture, taskCheckpoint);
        assertTaskCheckpoint(context, fixture, "inventory_baseline", "0");
        assertTaskCheckpoint(context, fixture, "collected", "15");
        assertTaskCheckpoint(context, fixture, "target_count", "32");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void placedWaterDebtRestoresRecoveryBeforeAcquireWater(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMissionWaterGT", 15, false);
        BlockPos waterSource = fixture.start().east(2);
        fixture.bot().getServerWorld().setBlockState(
                waterSource, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        Map<String, String> taskCheckpoint = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.RECOVER_WATER, 15, waterSource);

        restore(context, fixture, taskCheckpoint);

        assertRunningMakeObsidian(context, fixture, taskCheckpoint);
        require(context, fixture.bot().getServerWorld().getFluidState(waterSource).isOf(Fluids.WATER)
                        && fixture.bot().getServerWorld().getFluidState(waterSource).isStill(),
                "fixture water_source is not live at restore");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1
                        && InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 0,
                "fixture must represent one placed bucket: empty=1 water=0");
        assertTaskCheckpoint(context, fixture, "phase", "RECOVER_WATER");
        assertTaskCheckpoint(context, fixture, "water_source", encode(waterSource));
        assertTaskCheckpoint(context, fixture, "water_bucket_baseline", "1");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void thirtyOneOfThirtyTwoDoesNotCompleteOrReplaceCheckpoint(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMission31GT", 31, true);
        Map<String, String> taskCheckpoint = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SCAN, 31, null);

        restore(context, fixture, taskCheckpoint);

        assertRunningMakeObsidian(context, fixture, taskCheckpoint);
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot()).isEmpty(),
                "31/32 restore published a false terminal result");
        require(context, GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "31/32 restore discarded the active mission");
        assertTaskCheckpoint(context, fixture, "inventory_baseline", "0");
        assertTaskCheckpoint(context, fixture, "collected", "31");
        assertTaskCheckpoint(context, fixture, "target_count", "32");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 160)
    public void eightBlockBoundaryRunsObsidianPolicyThenResumesOriginalThirtyTwo(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianBoundary8GT", 8, true);
        Map<String, String> pending = pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8);

        restore(context, fixture, pending);
        AtomicBoolean serviceSeen = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
            if (active instanceof MiningServiceTask) {
                serviceSeen.set(true);
                MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
                Map<String, String> checkpoint = captured.active() == null
                        ? Map.of() : captured.active().checkpoint();
                require(context, GoalStep.Kind.MINING_SERVICE.name().equals(
                                checkpoint.get("task_kind"))
                                && "OBSIDIAN_8".equals(
                                checkpoint.get("task.service_profile")),
                        "boundary service lost OBSIDIAN_8 policy: " + checkpoint);
                require(context, "8".equals(
                                checkpoint.get("obsidian.pending_service_boundary"))
                                && "32".equals(checkpoint.get("obsidian.target_count"))
                                && "0".equals(checkpoint.get("obsidian.inventory_baseline")),
                        "dual namespace lost the original obsidian transaction: " + checkpoint);
                return;
            }
            if (!serviceSeen.get() || !(active instanceof CreateObsidianTask)) {
                return;
            }
            MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
            Map<String, String> checkpoint = captured.active() == null
                    ? Map.of() : captured.active().checkpoint();
            require(context, GoalStep.Kind.MAKE_OBSIDIAN.name().equals(
                            checkpoint.get("task_kind"))
                            && "32".equals(checkpoint.get("task.target_count"))
                            && "0".equals(checkpoint.get("task.inventory_baseline")),
                    "service resumed a remaining-count task instead of original target 32: "
                            + checkpoint);
            require(context, "8".equals(checkpoint.get("obsidian.serviced_collected"))
                            && "0".equals(
                            checkpoint.get("obsidian.pending_service_boundary")),
                    "service acknowledgement was not committed exactly once: " + checkpoint);
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void doneServiceCheckpointAcknowledgesBoundaryWithoutReplay(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceDoneGT", 8, true);
        Map<String, String> pending = pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8);

        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "fixture service did not reach DONE: " + committed.state()
                        + ":" + committed.failureReason());

        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        committed.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        pending.forEach((key, value) ->
                missionCheckpoint.put("obsidian." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "DONE service was replayed instead of acknowledged: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "8".equals(checkpoint.get("obsidian.serviced_collected"))
                        && "0".equals(checkpoint.get("obsidian.pending_service_boundary"))
                        && "32".equals(checkpoint.get("obsidian.target_count")),
                "DONE restore lost or repeated the service acknowledgement: " + checkpoint);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void doneServiceCheckpointAlreadyAcknowledgedResumesWithoutReplay(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceAlreadyAckGT", 8, true);
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "already-ACKed service fixture did not commit");

        restoreService(fixture, committed.checkpoint(), acknowledged);

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "already-ACKed DONE service was replayed: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, List.of(GoalStep.makeObsidian(32).describe())
                        .equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "already-ACKed restore retained service or preflight steps: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, GoalStep.Kind.MAKE_OBSIDIAN.name().equals(checkpoint.get("task_kind"))
                        && "8".equals(checkpoint.get("obsidian.serviced_collected"))
                        && "0".equals(checkpoint.get("obsidian.pending_service_boundary"))
                        && "32".equals(checkpoint.get("obsidian.target_count"))
                        && !checkpoint.containsKey("task.service_profile"),
                "already-ACKed restore lost its idempotent transaction identity: " + checkpoint);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void doneBoundaryEightCannotAcknowledgePendingBoundarySixteen(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianWrongBoundaryGT", 16, true);
        MiningServiceTask staleBoundary = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        staleBoundary.start(fixture.bot());
        for (int tick = 0; tick < 20 && staleBoundary.state() == TaskState.RUNNING; tick++) {
            staleBoundary.tick(fixture.bot());
        }
        require(context, staleBoundary.state() == TaskState.COMPLETED,
                "stale boundary fixture did not commit");

        Map<String, String> pending16 = pendingBoundaryCheckpoint(
                fixture.start(), 16, 8, 16);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        staleBoundary.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        pending16.forEach((key, value) ->
                missionCheckpoint.put("obsidian." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "wrong-boundary DONE service remained active");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "wrong-boundary DONE service did not fail closed");
        require(context, "16".equals(pending16.get("pending_service_boundary")),
                "rejected service mutated the factual pending boundary");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void staleTargetThirtyTwoBoundaryCannotImpersonateFreshRemainingEight(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianBoundaryWrongTargetGT", 8, true);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 16);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, fresh.success()
                        && fresh.steps().stream().anyMatch(step -> step.isObsidianPreflight()
                        && step.obsidianTransactionTarget() == 8)
                        && fresh.steps().stream().anyMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN
                                && step.count() == 8),
                "stale-boundary fixture did not prove fresh remaining target8: " + fresh.steps());
        Map<String, String> pending = pendingBoundaryCheckpoint(
                fixture.start(), 8, 0, 8);
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        stale.start(fixture.bot());
        require(context, stale.state() == TaskState.RUNNING,
                "stale target32 boundary fixture did not remain resumable");

        restoreService(fixture, goal, stale.checkpoint(), pending);

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "target32 boundary replaced the factual fresh remaining target8");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale boundary target did not fail closed");
        require(context, "8".equals(pending.get("pending_service_boundary")),
                "rejected stale target mutated the factual pending boundary");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void staleActiveMakeTargetCannotImpersonateFreshRemainingEight(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMakeWrongTargetGT", 8, true);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, 16);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, fresh.success()
                        && fresh.steps().stream().anyMatch(step -> step.isObsidianPreflight()
                        && step.obsidianTransactionTarget() == 8),
                "stale-MAKE fixture did not prove fresh remaining target8: " + fresh.steps());
        Map<String, String> staleTransaction = taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SCAN, 8, null);

        restoreMake(fixture, goal, staleTransaction);

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "active MAKE target32 replaced the factual fresh remaining target8");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale active MAKE target did not fail closed");
        require(context, "8".equals(staleTransaction.get("collected"))
                        && "32".equals(staleTransaction.get("target_count")),
                "rejected active MAKE mutated its factual transaction checkpoint");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void committedMakeDoneAdvancesToStockpileWithoutReplay(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianMakeDoneStockpileGT", TARGET, true);
        Goal goal = new Goal.Stockpile(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, fresh.success()
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN)
                        && fresh.steps().stream().anyMatch(
                        step -> step.kind() == GoalStep.Kind.STOCKPILE),
                "terminal-MAKE fixture did not prove a stockpile-only fresh tail: "
                        + fresh.steps());
        Map<String, String> terminal = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.DONE, TARGET, null));
        // The final batch is intentionally not followed by an inter-batch service boundary.
        terminal.put("serviced_collected", String.valueOf(TARGET - 8));
        require(context, CreateObsidianTask.inspectCheckpoint(terminal).isPresent(),
                "terminal MAKE fixture is not a valid production checkpoint");

        restoreMake(fixture, goal, Map.copyOf(terminal));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "committed MAKE lost the remaining stockpile tail");
        require(context, active instanceof StockpileTask,
                "committed MAKE was replayed instead of advancing to STOCKPILE: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        require(context, captured.active() != null
                        && captured.active().checkpoint().keySet().stream()
                        .noneMatch(key -> key.startsWith("obsidian.")),
                "committed terminal MAKE remained as a live transaction namespace");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void interruptedBoundaryServiceRestoresOnlyServiceThenOriginalMake(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceRunningGT", 8, true);
        Map<String, String> pending = pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8);
        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32,
                MiningCursor.initial(fixture.start(), 48));
        interrupted.start(fixture.bot());
        require(context, interrupted.state() == TaskState.RUNNING,
                "boundary service fixture did not remain resumable");
        Map<String, String> serviceCheckpoint = interrupted.checkpoint();
        BlockPos displaced = fixture.start().east();
        fixture.bot().teleport(
                fixture.bot().getServerWorld(),
                displaced.getX() + 0.5D, displaced.getY(), displaced.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        restoreService(fixture, serviceCheckpoint, pending);

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask
                        && active.state() == TaskState.RUNNING,
                "running boundary service was not restored first from its persisted cursor");
        List<String> expected = List.of(
                GoalStep.obsidianService(8, 32).describe(),
                GoalStep.makeObsidian(32).describe());
        require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "running boundary restore retained stale preparation or preflight steps: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "8".equals(checkpoint.get("task.service_boundary"))
                        && "32".equals(checkpoint.get("task.service_target_count"))
                        && "8".equals(checkpoint.get("obsidian.pending_service_boundary")),
                "running boundary restore lost its exact dual identity: profile="
                        + checkpoint.get("task.service_profile")
                        + " boundary=" + checkpoint.get("task.service_boundary")
                        + " target=" + checkpoint.get("task.service_target_count")
                        + " pending=" + checkpoint.get("obsidian.pending_service_boundary"));
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void runningBoundaryRestorePreservesUnrelatedBuildMaterialPrefix(TestContext context) {
        String blueprint = "obsidian_restore_mixed_"
                + UUID.randomUUID().toString().replace("-", "");
        Path blueprintPath = writeMixedObsidianBlueprint(blueprint);
        Fixture fixture = spawnPreparedBot(context, "ObsidianBuildRestoreGT", 8, true);
        try {
            Goal goal = new Goal.Build(blueprint);
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
            require(context, fresh.success()
                            && fresh.steps().stream().anyMatch(
                            step -> step.kind() == GoalStep.Kind.GATHER)
                            && fresh.steps().stream().anyMatch(
                            step -> step.kind() == GoalStep.Kind.CRAFT)
                            && fresh.steps().stream().anyMatch(
                            step -> step.kind() == GoalStep.Kind.BUILD)
                            && fresh.steps().stream().anyMatch(
                            step -> step.isObsidianPreflight()
                                    && step.obsidianTransactionTarget() == 24),
                    "mixed build fixture did not expose unrelated preparation around obsidian: "
                            + fresh.steps());
            List<String> preserved = fresh.steps().stream()
                    .filter(step -> step.kind() != GoalStep.Kind.MAKE_OBSIDIAN
                            && !step.isObsidianPreflight())
                    .map(GoalStep::describe)
                    .toList();
            Map<String, String> pending = pendingBoundaryCheckpoint(
                    fixture.start(), 8, 0, 8);
            MiningServiceTask interrupted = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                    fixture.missionId().toString(), 32);
            interrupted.start(fixture.bot());

            restoreService(fixture, goal, interrupted.checkpoint(), pending);

            List<String> expected = new ArrayList<>();
            expected.add(GoalStep.obsidianService(8, 32).describe());
            expected.add(GoalStep.makeObsidian(32).describe());
            expected.addAll(preserved);
            require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                    "boundary rebuild discarded unrelated Build preparation: expected="
                            + expected + " actual="
                            + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        } finally {
            deleteBlueprint(blueprintPath);
        }
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void runningBoundaryServiceCannotRestoreAfterBoundaryWasAcknowledged(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianServiceOrphanGT", 8, true);
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                fixture.missionId().toString(), 32);
        interrupted.start(fixture.bot());

        restoreService(fixture, interrupted.checkpoint(), acknowledged);

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "running service without a pending boundary remained active");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "orphaned running boundary service did not fail closed");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void interruptedPreflightReplacesTheFreshPlannerCopyWithoutBoundaryAck(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightRestoreGT", 0, true);

        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        interrupted.start(fixture.bot());
        require(context, interrupted.state() == TaskState.RUNNING,
                "preflight fixture did not enter a resumable state");

        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        interrupted.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask
                        && active.state() == TaskState.RUNNING,
                "interrupted preflight was not replayed first: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        long preflights = GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()).stream()
                .filter(GoalStep.obsidianPreflight().describe()::equals)
                .count();
        require(context, preflights == 1,
                "restore retained a duplicate obsidian preflight: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "OBSIDIAN_PREFLIGHT".equals(
                        checkpoint.get("task.service_profile")),
                "restored service lost its preflight identity: " + checkpoint);
        require(context, checkpoint.keySet().stream().noneMatch(key -> key.startsWith("obsidian.")),
                "preflight fabricated an obsidian boundary transaction: " + checkpoint);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void runningPreflightUsesCheckpointIdentityWhenFreshTargetIsUnknown(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightUnknownGT", 0, true);
        BlockPos depotPos = movePreflightReadinessToDepot(context, fixture);

        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        interrupted.start(fixture.bot());
        Map<String, String> checkpoint = interrupted.checkpoint();
        require(context, encode(depotPos).equals(checkpoint.get("depot")),
                "unknown-target fixture did not persist its factual depot: " + checkpoint);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, !fresh.success()
                        && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                "fixture unexpectedly proved a fresh preflight target: " + fresh.steps());

        restoreService(fixture, checkpoint, Map.of());

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask
                        && active.state() == TaskState.RUNNING,
                "schema-4 preflight was rejected only because fresh target was unknown");
        List<String> expected = List.of(
                GoalStep.obsidianPreflight(32).describe(),
                GoalStep.makeObsidian(32).describe());
        require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "unknown-target restore did not retain checkpoint target32: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> restored = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "32".equals(restored.get("task.service_target_count"))
                        && fixture.missionId().toString().equals(
                        restored.get("task.service_mission_id")),
                "unknown-target restore weakened the checkpoint identity: " + restored);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void committedPreflightUsesCheckpointIdentityWhenFreshTargetIsUnknown(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightDoneUnknownGT", 0, true);
        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "unknown-target DONE fixture did not commit");
        Map<String, String> checkpoint = committed.checkpoint();
        movePreflightReadinessToDepot(context, fixture);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, !fresh.success()
                        && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                "DONE fixture unexpectedly proved a fresh preflight target: " + fresh.steps());

        restoreService(fixture, checkpoint, Map.of());

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "committed schema-4 preflight was not advanced to original MAKE when fresh target was unknown: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, List.of(GoalStep.makeObsidian(32).describe())
                        .equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "unknown-target DONE restore replayed preflight or changed target32: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> restored = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, GoalStep.Kind.MAKE_OBSIDIAN.name().equals(restored.get("task_kind"))
                        && !restored.containsKey("task.service_profile"),
                "committed unknown-target preflight identity survived after hand-off: " + restored);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void stalePreflightCannotUseUnknownFreshTargetAuthority(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightUnknownStaleGT", 0, true);
        movePreflightReadinessToDepot(context, fixture);
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
        require(context, !fresh.success()
                        && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                        && fresh.steps().stream().noneMatch(
                        step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                "stale-target fixture unexpectedly proved a fresh identity: " + fresh.steps());
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(16), 0,
                fixture.missionId().toString(), 16);
        stale.start(fixture.bot());

        restoreService(fixture, stale.checkpoint(), Map.of());

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "unknown fresh plan let target16 impersonate remaining target32");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale unknown-target preflight did not fail closed");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void compoundBuildCannotUseUnknownPreflightAuthority(TestContext context) {
        String blueprint = "obsidian_unknown_build_"
                + UUID.randomUUID().toString().replace("-", "");
        Path blueprintPath = writeMixedObsidianBlueprint(blueprint);
        Fixture fixture = spawnPreparedBot(context, "ObsidianBuildUnknownGT", 8, true);
        try {
            movePreflightReadinessToDepot(context, fixture);
            Goal goal = new Goal.Build(blueprint);
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
            require(context, !fresh.success()
                            && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                            && fresh.steps().stream().noneMatch(
                            step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                    "compound fixture unexpectedly proved a fresh obsidian identity: "
                            + fresh.steps());
            Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                    pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
            MiningServiceTask interrupted = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    MiningServiceTask.ServicePolicy.obsidianPreflight(24), 0,
                    fixture.missionId().toString(), 24);
            interrupted.start(fixture.bot());

            restoreService(fixture, goal, interrupted.checkpoint(), acknowledged);

            require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                    "compound Build used an unknown fresh target as preflight authority");
            require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                            .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                    .equals(result.reason()))
                            .orElse(false),
                    "compound Build unknown preflight did not fail closed");
        } finally {
            deleteBlueprint(blueprintPath);
        }
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void compoundBuildWithFailedFreshPlanCannotReplayOnlyBoundaryAndMake(TestContext context) {
        String blueprint = "obsidian_unknown_boundary_build_"
                + UUID.randomUUID().toString().replace("-", "");
        Path blueprintPath = writeMixedObsidianBlueprint(blueprint);
        Fixture fixture = spawnPreparedBot(context, "ObsidianBuildBoundaryUnknownGT", 8, true);
        try {
            movePreflightReadinessToDepot(context, fixture);
            Goal goal = new Goal.Build(blueprint);
            GoalPlanner.GoalPlan fresh = GoalPlanner.plan(fixture.bot(), goal);
            require(context, !fresh.success()
                            && fresh.steps().stream().noneMatch(GoalStep::isObsidianPreflight)
                            && fresh.steps().stream().noneMatch(
                            step -> step.kind() == GoalStep.Kind.MAKE_OBSIDIAN),
                    "compound boundary fixture unexpectedly retained a rebuildable tail: "
                            + fresh.steps());
            Map<String, String> pending = pendingBoundaryCheckpoint(
                    fixture.start(), 8, 0, 8);
            MiningServiceTask interrupted = new MiningServiceTask(
                    Set.of(Blocks.OBSIDIAN), Map.of(),
                    MiningServiceTask.ServicePolicy.obsidian8(32, 8), 8,
                    fixture.missionId().toString(), 32);
            interrupted.start(fixture.bot());
            require(context, interrupted.state() == TaskState.RUNNING,
                    "compound boundary fixture did not remain resumable");

            restoreService(fixture, goal, interrupted.checkpoint(), pending);

            require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                    "compound restore scheduled service -> MAKE after losing the Build tail");
            require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                            .map(result -> "mission_restore_compound_obsidian_tail_unavailable"
                                    .equals(result.reason()))
                            .orElse(false),
                    "compound boundary restore did not fail closed on its unavailable tail");
            require(context, "8".equals(pending.get("pending_service_boundary")),
                    "rejected compound restore mutated the factual pending boundary");
        } finally {
            deleteBlueprint(blueprintPath);
        }
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void runningPreflightWithStaleTargetCannotReplaceFreshTarget(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightWrongTargetGT", 0, true);
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(16), 0,
                fixture.missionId().toString(), 16);
        stale.start(fixture.bot());

        restoreService(fixture, stale.checkpoint(), Map.of());

        require(context, !GoalExecutor.INSTANCE.hasActivePlan(fixture.bot()),
                "running target16 preflight replaced fresh target32");
        require(context, GoalExecutor.INSTANCE.lastResult(fixture.bot())
                        .map(result -> "mission_restore_incompatible_obsidian_service_checkpoint"
                                .equals(result.reason()))
                        .orElse(false),
                "stale running preflight target did not fail closed");
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void committedStalePreflightCannotSkipFreshTargetPreflight(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightStaleDoneGT", 0, true);
        // Keep the fresh target32 plan at its preflight boundary. Without carried torches or the
        // required expedition weapon, the stricter readiness contract legitimately prepends an
        // acquisition branch, which is unrelated to the stale committed service identity under test.
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.TORCH, 8));
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.STONE_SWORD));
        GoalPlanner.GoalPlan fresh = GoalPlanner.plan(
                fixture.bot(), new Goal.HaveItem(Items.OBSIDIAN, TARGET));
        require(context, fresh.success()
                        && fresh.steps().stream().anyMatch(step -> step.isObsidianPreflight()
                        && step.obsidianTransactionTarget() == TARGET),
                "stale DONE fixture did not establish a fresh target32 preflight: "
                        + fresh.steps() + " unresolved=" + fresh.unresolved());
        MiningServiceTask stale = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(16), 0,
                fixture.missionId().toString(), 16);
        stale.start(fixture.bot());
        for (int tick = 0; tick < 20 && stale.state() == TaskState.RUNNING; tick++) {
            stale.tick(fixture.bot());
        }
        require(context, stale.state() == TaskState.COMPLETED,
                "stale preflight fixture did not commit");

        restoreService(fixture, stale.checkpoint(), Map.of());

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask,
                "stale DONE preflight skipped the fresh target32 preflight");
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        Map<String, String> checkpoint = captured.active() == null
                ? Map.of() : captured.active().checkpoint();
        require(context, "OBSIDIAN_PREFLIGHT".equals(
                        checkpoint.get("task.service_profile"))
                        && "32".equals(checkpoint.get("task.service_target_count")),
                "fresh preflight did not retain target32 after stale DONE restore: "
                        + checkpoint);
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void remainingTargetPreflightCanRepairAnAcknowledgedTransaction(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightRemainingGT", 8, true);
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        MiningServiceTask interrupted = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(24), 0,
                fixture.missionId().toString(), 24);
        interrupted.start(fixture.bot());

        restoreService(fixture, interrupted.checkpoint(), acknowledged);

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof MiningServiceTask,
                "remaining target24 repair preflight was rejected");
        List<String> expected = List.of(
                GoalStep.obsidianPreflight(24).describe(),
                GoalStep.makeObsidian(32).describe());
        require(context, expected.equals(GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot())),
                "repair preflight did not preserve the original target32 transaction: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void committedPreflightIsNotReplayedAfterCrashWindowRestore(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPreflightDoneGT", 0, true);
        MiningServiceTask committed = new MiningServiceTask(
                Set.of(Blocks.OBSIDIAN), Map.of(),
                MiningServiceTask.ServicePolicy.obsidianPreflight(32), 0,
                fixture.missionId().toString(), 32);
        committed.start(fixture.bot());
        for (int tick = 0; tick < 20 && committed.state() == TaskState.RUNNING; tick++) {
            committed.tick(fixture.bot());
        }
        require(context, committed.state() == TaskState.COMPLETED,
                "preflight crash-window fixture did not commit");

        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        committed.checkpoint().forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));

        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "committed preflight was replayed instead of advancing to MAKE_OBSIDIAN: "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()).stream()
                        .noneMatch(GoalStep.obsidianPreflight().describe()::equals),
                "committed preflight remained in the restored plan: "
                        + GoalExecutor.INSTANCE.activeGoalSteps(fixture.bot()));
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void searchPreservesHigherTierOreAndClosesTheStoneOnlyLeg(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchGoldGT", 0, true);
        BlockPos gold = fixture.start().north().up();
        fixture.bot().getServerWorld().setBlockState(
                gold, Blocks.GOLD_ORE.getDefaultState(), Block.NOTIFY_ALL);
        require(context, ObservableWorldQuery.canObserveCell(fixture.bot(), gold),
                "fixture head-level gold must be directly observable");
        require(context, !ObservableWorldQuery.canObserveCell(
                        fixture.bot(), fixture.start().north()),
                "fixture must lock the head-wall occlusion of the feet cell");
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).beginNextLeg().encode()
                .forEach(checkpoint::put);
        int stoneDamageBefore = fixture.bot().getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            require(context, task.state() == TaskState.RUNNING,
                    "higher-tier search obstruction ended the obsidian task: "
                            + task.failureReason());
            require(context,
                    fixture.bot().getServerWorld().getBlockState(gold).isOf(Blocks.GOLD_ORE),
                    "stone-only obsidian search destroyed the finite gold obstruction");
            if (!"0".equals(task.checkpoint().get("steps_left"))) {
                if (ticks.incrementAndGet() > 10) {
                    context.throwGameTestException(
                            "gold-facing search leg was not durably closed: "
                                    + task.checkpoint());
                }
                return;
            }
            require(context, "0".equals(task.checkpoint().get("direction")),
                    "gold obstruction changed the wrong search leg: " + task.checkpoint());
            int stoneDamageAfter = fixture.bot().getInventory().main.stream()
                    .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                    .mapToInt(ItemStack::getDamage)
                    .sum();
            require(context, stoneDamageAfter == stoneDamageBefore,
                    "gold reroute consumed stone-pick durability");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 220)
    public void restoredScanReturnsToDedicatedRimBeforeBindingDisplacedObsidian(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianScanResumeGT", 0, true);
        var world = fixture.bot().getServerWorld();
        BlockPos cursorFace = fixture.start();
        BlockPos scanFace = fixture.start().east(2);
        BlockPos displaced = fixture.start().east(5);
        for (int dx = 0; dx <= 5; dx++) {
            BlockPos feet = fixture.start().east(dx);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos displacedObsidian = displaced.north();
        world.setBlockState(displacedObsidian.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(displacedObsidian,
                Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(displacedObsidian.up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        fixture.bot().teleport(world,
                displaced.getX() + 0.5D, displaced.getY(), displaced.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                cursorFace, CreateObsidianTask.Phase.SCAN, 0, null));
        checkpoint.put("scan_resume_face", encode(scanFace));
        ObsidianSearchCursor.initial(cursorFace, 12).encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());

        require(context, CreateObsidianTask.Phase.RETURN_TO_SCAN_FACE.name()
                        .equals(task.checkpoint().get("phase")),
                "displaced SCAN restore did not enter its dedicated return phase: "
                        + task.checkpoint());
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("scan resume failed before its durable face: "
                        + task.failureReason());
            }
            Map<String, String> live = task.checkpoint();
            require(context, !live.containsKey("obsidian")
                            && world.getBlockState(displacedObsidian).isOf(Blocks.OBSIDIAN),
                    "displaced restore bound or mutated a target before returning: " + live);
            if (!fixture.bot().getBlockPos().equals(scanFace)
                    || !CreateObsidianTask.Phase.SCAN.name().equals(live.get("phase"))) {
                return;
            }
            require(context, !fixture.bot().getBlockPos().equals(cursorFace),
                    "SCAN resume reused the corridor cursor instead of the dedicated rim");
            require(context, encode(scanFace).equals(live.get("scan_resume_face")),
                    "SCAN resume identity changed during physical return");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void auditScanDefersPreExistingObsidianAndBindsOnlyWaterBackedCell(
            TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianAuditGateGT", 0, true);
        var world = fixture.bot().getServerWorld();
        BlockPos preExisting = fixture.start().north();
        BlockPos waterBacked = fixture.start().east(2);
        world.setBlockState(preExisting, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(waterBacked, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);

        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        MiningEvidenceAudit.recordWaterPlacement(fixture.bot(), Set.of(waterBacked));
        require(context, MiningEvidenceAudit.recordLavaToObsidian(fixture.bot(), waterBacked),
                "fixture could not commit its exact water-backed conversion");

        CreateObsidianTask task = new CreateObsidianTask(
                TARGET, taskCheckpoint(fixture.start(), CreateObsidianTask.Phase.SCAN, 0, null));
        task.start(fixture.bot());
        task.tick(fixture.bot());
        Map<String, String> live = task.checkpoint();

        require(context, task.state() == TaskState.RUNNING,
                "audit-gated scan ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, encode(waterBacked).equals(live.get("obsidian")),
                "audit-gated scan bound a cell without exact conversion provenance: " + live);
        require(context, world.getBlockState(preExisting).isOf(Blocks.OBSIDIAN),
                "audit-gated scan mutated the pre-existing obsidian cell");
        require(context, MiningEvidenceAudit.snapshot(fixture.bot())
                        .map(snapshot -> snapshot.lavaConversions() == 1
                                && snapshot.obsidianBreaks() == 0
                                && snapshot.obsidianPhysicalPickups() == 0)
                        .orElse(false),
                "audit-gated scan advanced beyond its one exact conversion");

        task.cancel(fixture.bot(), "gametest_complete");
        MiningEvidenceAudit.clear(fixture.bot());
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void auditedServiceRestartDoesNotPromoteUnrelatedInventoryObsidian(
            TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianAuditServiceRestartGT", 0, true);
        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        for (int index = 0; index < 8; index++) {
            BlockPos exact = fixture.start().add(index + 2, -1, 0);
            MiningEvidenceAudit.recordWaterPlacement(fixture.bot(), Set.of(exact));
            require(context, MiningEvidenceAudit.recordLavaToObsidian(fixture.bot(), exact)
                            && MiningEvidenceAudit.recordObsidianBreak(fixture.bot(), exact)
                            && MiningEvidenceAudit.recordObsidianPickup(fixture.bot(), exact, 2),
                    "fixture could not commit exact audited pickup " + index);
        }
        // Nine physical inventory items but only eight exact-cell transactions. The ninth item is
        // deliberately unrelated and must not become mission progress when service replaces the
        // task and restores its acknowledged boundary checkpoint.
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.OBSIDIAN, 9));
        Map<String, String> acknowledged = CreateObsidianTask.acknowledgeServiceBoundary(
                pendingBoundaryCheckpoint(fixture.start(), 8, 0, 8)).orElseThrow();
        CreateObsidianTask restored = new CreateObsidianTask(TARGET, acknowledged);
        restored.start(fixture.bot());

        require(context, restored.state() == TaskState.RUNNING,
                "audited service restore failed at start: " + restored.failureReason());
        require(context, "8".equals(restored.checkpoint().get("collected"))
                        && "8".equals(restored.checkpoint().get("serviced_collected")),
                "service restore promoted unrelated inventory obsidian: "
                        + restored.checkpoint());
        restored.tick(fixture.bot());
        require(context, restored.state() == TaskState.RUNNING
                        && "8".equals(restored.checkpoint().get("collected")),
                "first restored tick rejected or promoted the exact audit ledger: "
                        + restored.state() + ":" + restored.failureReason()
                        + " checkpoint=" + restored.checkpoint());

        restored.cancel(fixture.bot(), "gametest_complete");
        MiningEvidenceAudit.clear(fixture.bot());
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void strictAuditSessionLossAndReplacementMismatchFailBeforeAnotherAction(
            TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianAuditSessionGT", 0, true);
        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        CreateObsidianTask live = new CreateObsidianTask(TARGET);
        live.start(fixture.bot());
        Map<String, String> boundCheckpoint = live.checkpoint();
        require(context, live.state() == TaskState.RUNNING
                        && boundCheckpoint.containsKey("audit_session"),
                "fresh strict task did not persist its audit session identity: "
                        + boundCheckpoint);

        MiningEvidenceAudit.clear(fixture.bot());
        live.tick(fixture.bot());
        require(context, live.state() == TaskState.FAILED
                        && "create_obsidian_audit_session_missing_or_mismatched"
                        .equals(live.failureReason()),
                "live task silently downgraded after its strict session disappeared: "
                        + live.state() + ":" + live.failureReason());

        MiningEvidenceAudit.begin(fixture.bot(), MiningEvidenceAudit.Target.OBSIDIAN);
        CreateObsidianTask replacement = new CreateObsidianTask(TARGET, boundCheckpoint);
        replacement.start(fixture.bot());
        require(context, replacement.state() == TaskState.FAILED
                        && "create_obsidian_audit_session_missing_or_mismatched"
                        .equals(replacement.failureReason()),
                "replacement accepted a checkpoint from a different strict session: "
                        + replacement.state() + ":" + replacement.failureReason());

        MiningEvidenceAudit.clear(fixture.bot());
        finish(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void searchPhysicallyLightsTheDarkTrailBehindItsReachedFace(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchTorchGT", 0, true);
        var world = fixture.bot().getServerWorld();
        BlockPos next = fixture.start().north();
        for (BlockPos cell : List.of(fixture.start(), next)) {
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            for (net.minecraft.util.math.Direction side : List.of(
                    net.minecraft.util.math.Direction.EAST,
                    net.minecraft.util.math.Direction.WEST)) {
                world.setBlockState(cell.offset(side),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.offset(side).up(),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        for (BlockPos end : List.of(fixture.start().south(), next.north())) {
            world.setBlockState(end, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(end.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.TORCH));
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).beginNextLeg().encode()
                .forEach(checkpoint::put);
        AtomicReference<CreateObsidianTask> taskRef = new AtomicReference<>();
        AtomicBoolean litBeforeMove = new AtomicBoolean();
        context.runAtTick(1, () -> {
            require(context, !world.isSkyVisible(fixture.start()) && !world.isSkyVisible(next),
                    "dark search fixture still exposed its corridor to the sky");
            require(context, world.getLightLevel(LightType.BLOCK, fixture.start()) < 8
                            && world.getLightLevel(LightType.SKY, fixture.start()) < 8,
                    "dark search fixture was not actually dark");
            CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
            task.start(fixture.bot());
            taskRef.set(task);
        });

        context.runAtEveryTick(() -> {
            CreateObsidianTask task = taskRef.get();
            if (task == null) {
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("dark search trail failed before lighting: "
                        + task.failureReason());
            }
            if (fixture.bot().getServerWorld().getBlockState(fixture.start()).isOf(Blocks.TORCH)
                    && fixture.bot().getBlockPos().equals(fixture.start())) {
                litBeforeMove.set(true);
            }
            if (!fixture.bot().getServerWorld().getBlockState(fixture.start()).isOf(Blocks.TORCH)) {
                return;
            }
            if (!fixture.bot().getBlockPos().equals(fixture.start().north())) {
                return;
            }
            require(context, litBeforeMove.get(),
                    "dark SEARCH moved or mined before physically lighting its initial face");
            require(context, fixture.bot().getBlockPos().equals(fixture.start().north()),
                    "search torch appeared without a physical one-cell advance");
            require(context, InventoryAction.countItem(fixture.bot(), Items.TORCH) == 0,
                    "search lighting did not consume exactly one physical torch");
            require(context, fixture.start().north().equals(
                            ObsidianSearchCursor.decode(task.checkpoint()).orElseThrow().face()),
                    "torch placement fabricated or lost the durable search face");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 100)
    public void threeBlockedDirectionsClearOnlyAfterTheOpenFaceIsReached(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchOneExitGT", 0, true);
        for (net.minecraft.util.math.Direction direction : List.of(
                net.minecraft.util.math.Direction.NORTH,
                net.minecraft.util.math.Direction.EAST,
                net.minecraft.util.math.Direction.SOUTH)) {
            fixture.bot().getServerWorld().setBlockState(
                    fixture.start().offset(direction).up(),
                    Blocks.GOLD_ORE.getDefaultState(), Block.NOTIFY_ALL);
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("one-exit search failed before moving: "
                        + task.failureReason());
            }
            if (!fixture.bot().getBlockPos().equals(fixture.start().west())) {
                return;
            }
            require(context, "0".equals(task.checkpoint().get("blocked_directions")),
                    "physical movement did not clear the prior blocked sweep: " + task.checkpoint());
            require(context, "1".equals(task.checkpoint().get("topology_epoch")),
                    "one factual face advance did not increment topology exactly once");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void skyLitSearchDoesNotSpendTheUndergroundTorchReserve(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchSkyGT", 0, true);
        require(context, fixture.bot().getServerWorld().isSkyVisible(fixture.start()),
                "sky-light fixture unexpectedly started underground");
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.TORCH));
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).beginNextLeg().encode()
                .forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(fixture.bot());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("sky-lit search failed: " + task.failureReason());
            }
            if (!fixture.bot().getBlockPos().equals(fixture.start().north())) {
                return;
            }
            require(context, !fixture.bot().getServerWorld().getBlockState(
                            fixture.start()).isOf(Blocks.TORCH),
                    "sky-lit search placed an unnecessary underground torch");
            require(context, InventoryAction.countItem(fixture.bot(), Items.TORCH) == 1,
                    "sky-lit search consumed its torch reserve");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 80)
    public void fourClosedSearchDirectionsFailTypedWithoutRotatingAsProgress(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianSearchClosedGT", 0, true);
        InventoryAction.giveItem(fixture.bot(), new ItemStack(Items.IRON_PICKAXE));
        List<net.minecraft.util.math.Direction> blockedDirections = new ArrayList<>();
        for (net.minecraft.util.math.Direction direction
                : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            blockedDirections.add(direction);
        }
        for (int index = 0; index < blockedDirections.size(); index++) {
            fixture.bot().getServerWorld().setBlockState(
                    fixture.start().offset(blockedDirections.get(index)),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
            fixture.bot().getServerWorld().setBlockState(
                    fixture.start().offset(blockedDirections.get(index)).up(),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                fixture.start(), CreateObsidianTask.Phase.SEARCH, 0, null));
        ObsidianSearchCursor.initial(fixture.start(), 12).encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());
        int toolDamageBefore = fixture.bot().getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.STONE_PICKAXE)
                        || stack.isOf(Items.IRON_PICKAXE)
                        || stack.isOf(Items.DIAMOND_PICKAXE))
                .mapToInt(ItemStack::getDamage)
                .sum();

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                // GameTests run in parallel and several mining fixtures deliberately tunnel
                // beyond their tiny empty templates. Reassert this test-owned one-cell shell
                // before every task tick so a neighbouring fixture cannot turn a factual closed
                // direction into ordinary mineable stone midway through the four-face sweep.
                for (net.minecraft.util.math.Direction direction : blockedDirections) {
                    fixture.bot().getServerWorld().setBlockState(
                            fixture.start().offset(direction),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                    fixture.bot().getServerWorld().setBlockState(
                            fixture.start().offset(direction).up(),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                }
                task.tick(fixture.bot());
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "four-direction closure ended as " + task.state());
            require(context, task.failureReason().startsWith("create_obsidian_search_enclosed"),
                    "four-direction closure did not expose a typed terminal reason: "
                            + task.failureReason());
            require(context, "15".equals(task.checkpoint().get("blocked_directions")),
                    "closed-direction mask was not durably persisted: " + task.checkpoint());
            require(context, fixture.bot().getBlockPos().equals(fixture.start()),
                    "closed-direction sweep moved away from its factual work face");
            require(context, "0".equals(task.checkpoint().get("topology_epoch"))
                            && "390".equals(task.checkpoint().get("last_progress")),
                    "pure blocked rotation was recorded as topology/progress: " + task.checkpoint());
            int toolDamageAfter = fixture.bot().getInventory().main.stream()
                    .filter(stack -> stack.isOf(Items.STONE_PICKAXE)
                            || stack.isOf(Items.IRON_PICKAXE)
                            || stack.isOf(Items.DIAMOND_PICKAXE))
                    .mapToInt(ItemStack::getDamage)
                    .sum();
            require(context, toolDamageAfter == toolDamageBefore,
                    "closed-direction sweep spent a reserved mining tool");
            for (int index = 0; index < blockedDirections.size(); index++) {
                require(context, fixture.bot().getServerWorld().getBlockState(
                                fixture.start().offset(blockedDirections.get(index)))
                                .isOf(Blocks.BEDROCK),
                        "closed search destroyed its feet-level factual blocker");
                require(context, fixture.bot().getServerWorld().getBlockState(
                                fixture.start().offset(blockedDirections.get(index)).up())
                                .isOf(Blocks.BEDROCK),
                        "closed search destroyed its head-level factual blocker");
            }
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void darkRestoredClosedMaskOutranksMissingTorch(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianDarkClosedGT", 0, true);
        var world = fixture.bot().getServerWorld();
        BlockPos start = fixture.start();
        // A single centre roof still admits propagated skylight from the four upper corners and
        // made the strict no-torch precondition depend on parallel fixture placement. Own a small
        // complete roof instead; the production branch under test remains mask=15 ordering.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(start.add(dx, 2, dz),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        for (net.minecraft.util.math.Direction direction
                : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            world.setBlockState(start.offset(direction),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(start.offset(direction).up(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        ObsidianSearchCursor cursor = ObsidianSearchCursor.initial(start, 12);
        for (int direction = 0; direction < 4; direction++) {
            cursor = cursor.beginNextLeg().skipBlockedLeg();
        }
        Map<String, String> checkpoint = new LinkedHashMap<>(taskCheckpoint(
                start, CreateObsidianTask.Phase.SEARCH, 0, null));
        cursor.encode().forEach(checkpoint::put);
        CreateObsidianTask task = new CreateObsidianTask(TARGET, Map.copyOf(checkpoint));
        task.start(fixture.bot());

        context.runAtTick(1, () -> {
            // Long-running mining GameTests share the world and can cross the small empty-template
            // spacing. Reassert this fixture's owned shell in the same tick as the light check so
            // an unrelated tunnel cannot transiently turn a factual dark restore into skylight.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.setBlockState(start.add(dx, 2, dz),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
            for (net.minecraft.util.math.Direction direction
                    : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
                world.setBlockState(start.offset(direction),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.offset(direction).up(),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
            require(context, world.getLightLevel(LightType.BLOCK, start) < 8
                            && world.getLightLevel(LightType.SKY, start) < 8
                            && InventoryAction.countItem(fixture.bot(), Items.TORCH) == 0,
                    "closed-mask fixture is not a dark no-torch restore");
            task.tick(fixture.bot());
            require(context, task.state() == TaskState.FAILED,
                    "persisted mask=15 did not fail terminally");
            require(context, task.failureReason().startsWith("create_obsidian_search_enclosed"),
                    "missing torch obscured the factual enclosure: " + task.failureReason());
            require(context, fixture.bot().getBlockPos().equals(start)
                            && "15".equals(task.checkpoint().get("blocked_directions")),
                    "terminal enclosed restore moved or lost its factual mask");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void pickupMicrostepsCloseTwoCellGapWithoutAcceptingAdjacentPathSnap(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPickupMicroGT", 0, true);
        BlockPos start = fixture.start();
        BlockPos target = start.add(2, 0, 1);

        require(context, CreateObsidianTask.stepTowardPickupCell(fixture.bot(), target),
                "first bounded pickup microstep was not issued");
        require(context, fixture.bot().getBlockPos().equals(start.east()),
                "first pickup microstep skipped the exact adjacent transit cell: "
                        + fixture.bot().getBlockPos().toShortString());

        context.runAtTick(1, () -> {
            require(context, CreateObsidianTask.stepTowardPickupCell(fixture.bot(), target),
                    "final bounded pickup collision step was not issued");
            require(context, fixture.bot().getBlockPos().equals(target),
                    "pickup microsteps stopped beside the factual drop cell: "
                            + fixture.bot().getBlockPos().toShortString());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 120)
    public void pickupCheckpointPhysicallyCollectsSurvivingItemEntity(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPickupEntityGT", 0, true);
        BlockPos target = fixture.start().add(2, 0, 1);
        ItemEntity drop = new ItemEntity(fixture.bot().getServerWorld(),
                target.getX() + 0.5D, target.getY() + 0.1D, target.getZ() + 0.5D,
                new ItemStack(Items.OBSIDIAN));
        fixture.bot().getServerWorld().spawnEntity(drop);

        CreateObsidianTask task = new CreateObsidianTask(1,
                pickupCheckpoint(fixture.start(), target));
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_pickup_entity"));
        AtomicBoolean collisionPickupObserved = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("obsidian pickup task ended as " + task.state()
                        + ":" + task.failureReason() + " checkpoint=" + task.checkpoint());
            }
            int obsidian = InventoryAction.countItem(fixture.bot(), Items.OBSIDIAN);
            if (obsidian > 0 && !collisionPickupObserved.get()) {
                require(context, fixture.bot().getPos().squaredDistanceTo(target.toCenterPos()) <= 4.0D,
                        "obsidian entered inventory away from the surviving ItemEntity");
                collisionPickupObserved.set(true);
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, collisionPickupObserved.get(),
                        "pickup task completed without an observed collision pickup");
                require(context, obsidian == 1 && !drop.isAlive(),
                        "physical obsidian drop was not consumed exactly once");
                require(context, !task.checkpoint().containsKey("pending_pickup_pos")
                                && !task.checkpoint().containsKey("pending_pickup_last_seen_pos"),
                        "completed pickup retained a stale pending ledger: " + task.checkpoint());
                finish(context, fixture);
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "obsidianActiveBreakAirRestoreLive", tickLimit = 40)
    public void airAtRestoredActiveBreakRebuildsProtectedPickupTransaction(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianActiveBreakAirGT", 1, true);
        BlockPos target = fixture.start().east();
        require(context, fixture.bot().getServerWorld().getBlockState(target).isAir(),
                "active-break restore fixture must begin after the physical block break");

        CreateObsidianTask task = new CreateObsidianTask(1,
                activeBreakCheckpoint(fixture.start(), target, fixture.start()));
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_active_break_air"));

        context.runAtTick(8, () -> {
            Map<String, String> live = task.checkpoint();
            require(context, task.state() == TaskState.RUNNING,
                    "AIR restore did not retain a live pickup transaction: "
                            + task.state() + ":" + task.failureReason());
            require(context, InventoryAction.countItem(fixture.bot(), Items.OBSIDIAN) == 1,
                    "fixture must model a break whose drop crossed the crash boundary");
            require(context, CreateObsidianTask.Phase.PROTECT_PICKUP.name()
                            .equals(live.get("phase"))
                            && encode(target).equals(live.get("pending_pickup_pos"))
                            && !live.containsKey("active_break_pos"),
                    "AIR restore did not atomically promote active break to pickup: " + live);
            String source = live.get("water_source");
            require(context, source != null
                            && InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1
                            && InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 0,
                    "AIR restore did not place the post-break protection source: " + live);
            CreateObsidianTask.ObsidianCheckpoint decoded =
                    CreateObsidianTask.ObsidianCheckpoint.decode(live, 1, 24000)
                            .orElseThrow(() -> new IllegalStateException(
                                    "live AIR restore checkpoint failed its codec: " + live));
            require(context, target.equals(decoded.pickupLastSeenPos()),
                    "promoted pickup lost its factual last-seen fallback: " + live);
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "obsidianActiveBreakLiveSourceRestore", tickLimit = 40)
    public void airAtRestoredActiveBreakRetainsLiveSourceForFreshProtectionWindow(
            TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianActiveBreakWaterGT", 0, false);
        BlockPos target = fixture.start().east();
        fixture.bot().getServerWorld().setBlockState(
                target, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        Map<String, String> checkpoint = new LinkedHashMap<>(
                activeBreakCheckpoint(fixture.start(), target, fixture.start()));
        checkpoint.put("water_source", encode(target));
        checkpoint.put("water_bucket_baseline", "1");
        Map<String, String> sealed = Map.copyOf(checkpoint);
        require(context, CreateObsidianTask.ObsidianCheckpoint.decode(
                        sealed, 1, 24000).isPresent(),
                "live-source active-break fixture failed the production codec");

        CreateObsidianTask task = new CreateObsidianTask(1, sealed);
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_obsidian_active_break_live_source"));

        context.runAtTick(8, () -> {
            Map<String, String> live = task.checkpoint();
            require(context, task.state() == TaskState.RUNNING
                            && CreateObsidianTask.Phase.PROTECT_PICKUP.name()
                            .equals(live.get("phase")),
                    "AIR promotion reclaimed its live source before protection: " + live);
            require(context, encode(target).equals(live.get("water_source"))
                            && fixture.bot().getServerWorld().getFluidState(target).isStill()
                            && InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1
                            && InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 0,
                    "fresh post-break protection did not retain the committed source: " + live);
            require(context, Integer.parseInt(live.get("budget_used"))
                            - Integer.parseInt(live.get("phase_started")) < 20,
                    "fixture observed the source after its fresh protection window expired");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "obsidianLastDurabilitySettlementLive", tickLimit = 800)
    public void rawTwoPickSettlesFinalBreakAndPhysicalPickupAtRawOne(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianLastDurabilityGT", 0, true);
        BlockPos target = fixture.start().east();
        fixture.bot().getServerWorld().setBlockState(
                target, Blocks.OBSIDIAN.getDefaultState(), Block.NOTIFY_ALL);

        int diamondSlot = InventoryAction.findItem(fixture.bot(), Items.DIAMOND_PICKAXE)
                .orElseThrow(() -> new IllegalStateException("fixture missing diamond pickaxe"));
        ItemStack diamond = fixture.bot().getInventory().main.get(diamondSlot);
        diamond.setDamage(diamond.getMaxDamage() - 2);

        Map<String, String> checkpoint = activeBreakCheckpoint(
                fixture.start(), target, fixture.start());
        CreateObsidianTask task = new CreateObsidianTask(1, checkpoint);
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_last_durability"));
        AtomicBoolean collisionPickupObserved = new AtomicBoolean();
        AtomicBoolean protectedSourceObserved = new AtomicBoolean();
        AtomicBoolean drainWindowObserved = new AtomicBoolean();
        AtomicBoolean drainWindowCompleted = new AtomicBoolean();
        AtomicBoolean edgeImpulseInjected = new AtomicBoolean();
        AtomicBoolean shiftedLastSeenObserved = new AtomicBoolean();
        AtomicBoolean rimReturnObserved = new AtomicBoolean();
        AtomicInteger protectionStartedBudget = new AtomicInteger(-1);
        AtomicInteger drainStartedBudget = new AtomicInteger(-1);
        AtomicReference<Vec3d> lastObservedDropPosition = new AtomicReference<>();

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("last-durability obsidian task ended as "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
            }
            int obsidian = InventoryAction.countItem(fixture.bot(), Items.OBSIDIAN);
            Map<String, String> live = task.checkpoint();
            if (CreateObsidianTask.Phase.PROTECT_PICKUP.name().equals(live.get("phase"))
                    && live.containsKey("water_source")) {
                protectedSourceObserved.set(true);
                protectionStartedBudget.compareAndSet(
                        -1, Integer.parseInt(live.get("phase_started")));
            }
            if (CreateObsidianTask.Phase.WAIT_DRAIN.name().equals(live.get("phase"))) {
                require(context, protectedSourceObserved.get(),
                        "pickup drain window began before post-break water protection");
                int budget = Integer.parseInt(live.get("budget_used"));
                require(context, budget - protectionStartedBudget.get() >= 20,
                        "post-break source was recovered before its 20-tick spread window");
                drainWindowObserved.set(true);
                drainStartedBudget.compareAndSet(
                        -1, Integer.parseInt(live.get("phase_started")));
            }
            if (CreateObsidianTask.Phase.PICKUP.name().equals(live.get("phase"))
                    && drainStartedBudget.get() >= 0) {
                require(context, Integer.parseInt(live.get("budget_used"))
                                - drainStartedBudget.get() >= 60,
                        "physical pickup resumed before the 60-tick drain window");
                drainWindowCompleted.set(true);
            }
            String lastSeen = live.get("pending_pickup_last_seen_pos");
            if (lastSeen != null && !encode(target).equals(lastSeen)) {
                shiftedLastSeenObserved.set(true);
            }
            if (CreateObsidianTask.Phase.RETURN_TO_RIM.name().equals(live.get("phase"))) {
                rimReturnObserved.set(true);
            }
            fixture.bot().getServerWorld().getEntitiesByClass(
                            ItemEntity.class, new Box(target).expand(8.0D, 4.0D, 8.0D),
                            entity -> entity.isAlive()
                                    && entity.getStack().isOf(Items.OBSIDIAN)
                                    && ObservableWorldQuery.canObserveEntity(fixture.bot(), entity))
                    .stream()
                    .min(java.util.Comparator.comparingDouble(
                            entity -> entity.getPos().squaredDistanceTo(target.toCenterPos())))
                    .ifPresent(entity -> {
                        lastObservedDropPosition.set(entity.getPos());
                        if (CreateObsidianTask.Phase.PROTECT_PICKUP.name()
                                .equals(live.get("phase"))
                                && live.containsKey("water_source")
                                && edgeImpulseInjected.compareAndSet(false, true)) {
                            // Make the old intermittent water-wash failure deterministic: the drop
                            // crosses the east lip and lands three blocks lower, where the platform
                            // occludes it until the durable last-seen route reaches that edge.
                            entity.setVelocity(0.12D, entity.getVelocity().y, 0.0D);
                        }
                    });
            if (obsidian > 0 && !collisionPickupObserved.get()) {
                Vec3d dropPosition = lastObservedDropPosition.get();
                require(context, dropPosition != null
                                && fixture.bot().getPos().squaredDistanceTo(dropPosition) <= 4.0D,
                        "final obsidian entered inventory away from the last observed ItemEntity");
                collisionPickupObserved.set(true);
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, collisionPickupObserved.get() && obsidian == 1,
                    "raw-one settlement did not physically collect the final obsidian");
            require(context, fixture.bot().getServerWorld().getBlockState(target).isAir(),
                    "final obsidian block was not physically mined");
            require(context, diamond.getMaxDamage() - diamond.getDamage() == 1,
                    "final break did not consume raw-2 pick to raw-1");
            require(context, InventoryAction.countItem(fixture.bot(), Items.WATER_BUCKET) == 1
                            && InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 0,
                    "pickup protection did not recover the reusable water source");
            require(context, protectedSourceObserved.get() && drainWindowObserved.get()
                            && drainWindowCompleted.get(),
                    "final pickup skipped its protection or drain safety phase");
            require(context, edgeImpulseInjected.get() && shiftedLastSeenObserved.get(),
                    "adversarial water wash never advanced the durable last-seen pickup cell");
            require(context, rimReturnObserved.get()
                            && fixture.bot().getBlockPos().equals(fixture.start()),
                    "final completion skipped its durable dry-rim return debt");
            require(context, !task.checkpoint().containsKey("active_break_pos")
                            && !task.checkpoint().containsKey("pending_pickup_pos")
                            && !task.checkpoint().containsKey("pending_pickup_last_seen_pos"),
                    "completed final break retained a transaction debt: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void unsafePickupEndpointIsRejectedWithoutDiscardingLedger(TestContext context) {
        Fixture fixture = spawnPreparedBot(context, "ObsidianPickupGuardGT", 0, true);
        BlockPos target = fixture.start().add(2, 0, 1);
        BlockPos[] unsupported = {
                target, target.north(), target.east(), target.south(), target.west()
        };
        for (BlockPos cell : unsupported) {
            fixture.bot().getServerWorld().setBlockState(
                    cell.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        CreateObsidianTask task = new CreateObsidianTask(1,
                pickupCheckpoint(fixture.start(), target));
        TaskManager.INSTANCE.assign(fixture.bot(), task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_obsidian_pickup_guard"));

        context.runAtTick(12, () -> {
            require(context, task.state() == TaskState.RUNNING,
                    "unsafe pickup endpoint prematurely terminated the transaction: "
                            + task.state() + ":" + task.failureReason());
            require(context, !fixture.bot().getBlockPos().equals(target),
                    "pickup guard moved the bot into an unsupported drop cell");
            require(context, fixture.bot().getActionPack().activePathGoal() == null
                            || !fixture.bot().getActionPack().activePathGoal()
                            .equals(fixture.bot().getBlockPos()),
                    "pickup guard accepted a path snapped to the current cell");
            require(context, encode(target).equals(task.checkpoint().get("pending_pickup_pos")),
                    "unsafe pickup endpoint discarded the durable ledger: " + task.checkpoint());
            finish(context, fixture);
        });
    }

    private static void restore(TestContext context,
                                Fixture fixture,
                                Map<String, String> taskCheckpoint) {
        require(context, CreateObsidianTask.ObsidianCheckpoint
                        .decode(taskCheckpoint, TARGET, TARGET_BUDGET).isPresent(),
                "fixture checkpoint must satisfy the production codec");
        Goal goal = new Goal.HaveItem(Items.OBSIDIAN, TARGET);
        restoreMake(fixture, goal, taskCheckpoint);
        require(context, GoalExecutor.INSTANCE.isActiveGoal(fixture.bot(), goal),
                "restored obsidian goal is not active");
    }

    private static void restoreMake(Fixture fixture,
                                    Goal goal,
                                    Map<String, String> taskCheckpoint) {
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MAKE_OBSIDIAN.name());
        taskCheckpoint.forEach((key, value) -> missionCheckpoint.put("task." + key, value));
        MissionRuntimeRecord runtime = new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal), missionCheckpoint),
                List.of(), false);

        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), runtime);
    }

    private static void restoreService(Fixture fixture,
                                       Map<String, String> serviceCheckpoint,
                                       Map<String, String> obsidianCheckpoint) {
        restoreService(fixture, new Goal.HaveItem(Items.OBSIDIAN, TARGET),
                serviceCheckpoint, obsidianCheckpoint);
    }

    private static void restoreService(Fixture fixture,
                                       Goal goal,
                                       Map<String, String> serviceCheckpoint,
                                       Map<String, String> obsidianCheckpoint) {
        Map<String, String> missionCheckpoint = baseMissionCheckpoint(fixture);
        missionCheckpoint.put("task_kind", GoalStep.Kind.MINING_SERVICE.name());
        serviceCheckpoint.forEach((key, value) ->
                missionCheckpoint.put("task." + key, value));
        obsidianCheckpoint.forEach((key, value) ->
                missionCheckpoint.put("obsidian." + key, value));
        GoalExecutor.INSTANCE.restoreRuntime(fixture.bot(), new MissionRuntimeRecord(
                new MissionRecord(fixture.missionId().toString(), MissionSpec.fromGoal(goal),
                        missionCheckpoint), List.of(), false));
    }

    private static Path writeMixedObsidianBlueprint(String name) {
        Path path = FabricLoader.getInstance().getGameDir()
                .resolve("blueprints").resolve(name + ".json");
        List<BlueprintSchema.BlockPlacement> placements = new ArrayList<>();
        placements.add(new BlueprintSchema.BlockPlacement(
                0, 0, 0, "minecraft:oak_planks"));
        for (int x = 1; x <= TARGET; x++) {
            placements.add(new BlueprintSchema.BlockPlacement(
                    x, 0, 0, "minecraft:obsidian"));
        }
        BlueprintSchema schema = new BlueprintSchema(
                name, TARGET + 1, 1, 1, List.copyOf(placements), List.of());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new Gson().toJson(schema));
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to write mixed obsidian blueprint", exception);
        }
    }

    private static void deleteBlueprint(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete mixed obsidian blueprint", exception);
        }
    }

    private static BlockPos movePreflightReadinessToDepot(TestContext context, Fixture fixture) {
        require(context, InventoryAction.removeItems(fixture.bot(), Items.COOKED_BEEF, 24)
                        && InventoryAction.removeItems(fixture.bot(), Items.STONE_PICKAXE, 4),
                "unknown-target fixture did not remove carried readiness supplies");
        BlockPos depotPos = fixture.start().east(2);
        fixture.bot().getServerWorld().setBlockState(
                depotPos, Blocks.CHEST.getDefaultState(), Block.NOTIFY_ALL);
        Inventory depot = ContainerAction.resolve(fixture.bot(), depotPos).orElseThrow();
        depot.setStack(0, new ItemStack(Items.COOKED_BEEF, 8));
        for (int slot = 1; slot <= 4; slot++) {
            depot.setStack(slot, new ItemStack(Items.STONE_PICKAXE));
        }
        depot.markDirty();
        BotMemoryStore.INSTANCE.of(fixture.bot().getUuid()).markPlace(
                "depot", fixture.bot().getServerWorld(), depotPos);
        return depotPos.toImmutable();
    }

    private static Map<String, String> baseMissionCheckpoint(Fixture fixture) {
        Map<String, String> missionCheckpoint = new LinkedHashMap<>();
        missionCheckpoint.put("origin", encode(fixture.start()));
        missionCheckpoint.put("started_tick",
                String.valueOf(fixture.bot().getServer().getTicks()));
        missionCheckpoint.put("revision", "0");
        return missionCheckpoint;
    }

    private static void assertRunningMakeObsidian(TestContext context,
                                                   Fixture fixture,
                                                   Map<String, String> expectedCheckpoint) {
        Task active = TaskManager.INSTANCE.getActive(fixture.bot()).orElse(null);
        require(context, active instanceof CreateObsidianTask,
                "first restored task must remain MAKE_OBSIDIAN, got "
                        + (active == null ? "none" : active.getClass().getSimpleName()));
        require(context, active.state() == TaskState.RUNNING,
                "restored MAKE_OBSIDIAN is not running: " + active.state() + ":" + active.failureReason());
        require(context, !"create_obsidian_invalid_checkpoint".equals(active.failureReason()),
                "valid Mission checkpoint was rejected");

        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        require(context, captured.active() != null, "restored Mission disappeared during capture");
        require(context, fixture.missionId().toString().equals(captured.active().missionId()),
                "restore changed Mission identity");
        require(context, GoalStep.Kind.MAKE_OBSIDIAN.name()
                        .equals(captured.active().checkpoint().get("task_kind")),
                "restore replaced MAKE_OBSIDIAN checkpoint kind: " + captured.active().checkpoint());
        for (Map.Entry<String, String> entry : expectedCheckpoint.entrySet()) {
            String actual = captured.active().checkpoint().get("task." + entry.getKey());
            require(context, entry.getValue().equals(actual),
                    "restore changed task checkpoint key " + entry.getKey()
                            + ": expected=" + entry.getValue() + " actual=" + actual);
        }
    }

    private static void assertTaskCheckpoint(TestContext context,
                                             Fixture fixture,
                                             String key,
                                             String expected) {
        MissionRuntimeRecord captured = GoalExecutor.INSTANCE.captureRuntime(fixture.bot());
        String actual = captured.active() == null
                ? null : captured.active().checkpoint().get("task." + key);
        require(context, expected.equals(actual),
                "unexpected restored checkpoint " + key + ": expected=" + expected + " actual=" + actual);
    }

    private static Map<String, String> taskCheckpoint(BlockPos origin,
                                                       CreateObsidianTask.Phase phase,
                                                       int collected,
                                                       BlockPos waterSource) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).withProduced(collected).encode());
        values.put("task_schema", "3");
        values.put("target_count", String.valueOf(TARGET));
        values.put("phase", phase.name());
        values.put("scan_resume_face", encode(origin));
        values.put("inventory_baseline", "0");
        values.put("collected", String.valueOf(collected));
        values.put("serviced_collected", String.valueOf(collected / 8 * 8));
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "400");
        values.put("phase_started", "350");
        values.put("last_progress", "390");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", waterSource == null ? "-1" : "1");
        values.put("pending_pickup_inventory", "-1");
        values.put("pickup_gain_budget", "-1");
        values.put("active_break_inventory", "-1");
        values.put("protection_prepared", "false");
        if (waterSource != null) {
            values.put("water_source", encode(waterSource));
        }
        return Map.copyOf(values);
    }

    private static Map<String, String> pickupCheckpoint(BlockPos origin, BlockPos pickupPos) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).encode());
        values.put("task_schema", "3");
        values.put("target_count", "1");
        values.put("phase", CreateObsidianTask.Phase.PICKUP.name());
        values.put("scan_resume_face", encode(origin));
        values.put("inventory_baseline", "0");
        values.put("collected", "0");
        values.put("serviced_collected", "0");
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "20");
        values.put("phase_started", "20");
        values.put("last_progress", "20");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", "-1");
        values.put("pending_pickup_pos", encode(pickupPos));
        values.put("pending_pickup_inventory", "0");
        values.put("pickup_gain_budget", "-1");
        values.put("return_rim", encode(origin));
        values.put("active_break_inventory", "-1");
        values.put("protection_prepared", "false");
        Map<String, String> checkpoint = Map.copyOf(values);
        if (CreateObsidianTask.ObsidianCheckpoint.decode(checkpoint, 1, 24000).isEmpty()) {
            throw new IllegalStateException("invalid pickup checkpoint fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Map<String, String> activeBreakCheckpoint(BlockPos origin,
                                                              BlockPos obsidian,
                                                              BlockPos stand) {
        Map<String, String> values = new LinkedHashMap<>(
                ObsidianSearchCursor.initial(origin, 12).encode());
        values.put("task_schema", "3");
        values.put("target_count", "1");
        values.put("phase", CreateObsidianTask.Phase.MINE.name());
        values.put("scan_resume_face", encode(origin));
        values.put("inventory_baseline", "0");
        values.put("collected", "0");
        values.put("serviced_collected", "0");
        values.put("pending_service_boundary", "0");
        values.put("budget_used", "20");
        values.put("phase_started", "20");
        values.put("last_progress", "20");
        values.put("pickup_grace", "0");
        values.put("water_bucket_baseline", "-1");
        values.put("pending_pickup_inventory", "-1");
        values.put("pickup_gain_budget", "-1");
        values.put("active_break_inventory", "0");
        values.put("protection_prepared", "false");
        values.put("obsidian", encode(obsidian));
        values.put("stand", encode(stand));
        values.put("active_break_pos", encode(obsidian));
        Map<String, String> checkpoint = Map.copyOf(values);
        if (CreateObsidianTask.ObsidianCheckpoint.decode(checkpoint, 1, 24000).isEmpty()) {
            throw new IllegalStateException("invalid active-break fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Map<String, String> pendingBoundaryCheckpoint(BlockPos origin,
                                                                  int collected,
                                                                  int serviced,
                                                                  int pending) {
        Map<String, String> values = new LinkedHashMap<>(taskCheckpoint(
                origin, CreateObsidianTask.Phase.SERVICE_BOUNDARY, collected, null));
        values.put("serviced_collected", String.valueOf(serviced));
        values.put("pending_service_boundary", String.valueOf(pending));
        Map<String, String> checkpoint = Map.copyOf(values);
        if (CreateObsidianTask.ObsidianCheckpoint.decode(
                checkpoint, TARGET, TARGET_BUDGET).isEmpty()) {
            throw new IllegalStateException("invalid service-boundary fixture: " + checkpoint);
        }
        return checkpoint;
    }

    private static Fixture spawnPreparedBot(TestContext context,
                                            String name,
                                            int obsidian,
                                            boolean hasWaterBucket) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        // EMPTY_STRUCTURE is 8x8. Keep every owned block inside relative x/z 0..7 so concurrent
        // GameTest batches cannot erase this platform while a long pickup transaction is running.
        for (int dx = -2; dx <= 5; dx++) {
            for (int dz = -2; dz <= 5; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);

        InventoryAction.giveItem(bot, new ItemStack(Items.OBSIDIAN, obsidian));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 24));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 64));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 12));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 40));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(hasWaterBucket ? Items.WATER_BUCKET : Items.BUCKET));
        return new Fixture(name, bot, start.toImmutable(), UUID.randomUUID());
    }

    private static void finish(TestContext context, Fixture fixture) {
        TaskManager.INSTANCE.cancelIntentTasks(fixture.bot(), "gametest_complete");
        GoalExecutor.INSTANCE.unload(fixture.bot());
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record Fixture(String name, AIPlayerEntity bot, BlockPos start, UUID missionId) {
    }
}
