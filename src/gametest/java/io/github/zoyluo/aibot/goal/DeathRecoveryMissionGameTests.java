package io.github.zoyluo.aibot.goal;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.persist.MissionRuntimeRecord;
import io.github.zoyluo.aibot.runtime.RuntimeLifecycleCoordinator;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import io.github.zoyluo.aibot.task.RecoverDropsTask;
import io.github.zoyluo.aibot.task.TaskManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.UUID;

/** Deterministic death suspension coverage for active and queued mining missions. */
public final class DeathRecoveryMissionGameTests implements FabricGameTest {
    private static final int SUSPENDED_ASSERT_TICK = 10;
    private static final int RESUMED_ASSERT_TICK = 115;
    private static final int ACTIVE_COMPLETE_ASSERT_TICK = 135;
    private static final int ALL_COMPLETE_ASSERT_TICK = 155;

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void mineOreSurvivesDeathRecoveryAndPreservesQueuedHaveItem(TestContext context) {
        runScenario(
                context,
                "DeathMineGT",
                new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1),
                Items.RAW_IRON,
                new Goal.HaveItem(Items.SWEET_BERRIES, 1),
                Items.SWEET_BERRIES);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 200)
    public void haveItemSurvivesDeathRecoveryAndPreservesQueuedMineOre(TestContext context) {
        runScenario(
                context,
                "DeathItemGT",
                new Goal.HaveItem(Items.SWEET_BERRIES, 1),
                Items.SWEET_BERRIES,
                new Goal.MineOre(Set.of(Blocks.IRON_ORE), 1),
                Items.RAW_IRON);
    }

    private static void runScenario(TestContext context,
                                    String botName,
                                    Goal activeGoal,
                                    Item activeReward,
                                    Goal queuedGoal,
                                    Item queuedReward) {
        Probe probe = startSuspendedMission(context, botName, activeGoal, queuedGoal);

        context.runAtTick(SUSPENDED_ASSERT_TICK, () -> assertSuspended(probe));
        context.runAtTick(RESUMED_ASSERT_TICK, () -> {
            assertResumed(probe);
            InventoryAction.giveItem(probe.bot(), new ItemStack(activeReward, 1));
        });
        context.runAtTick(ACTIVE_COMPLETE_ASSERT_TICK, () -> {
            assertActiveMissionCompletedAndQueuePromoted(probe);
            InventoryAction.giveItem(probe.bot(), new ItemStack(queuedReward, 1));
        });
        context.runAtTick(ALL_COMPLETE_ASSERT_TICK, () -> {
            assertAllCompleted(probe);
            cleanup(probe);
            context.complete();
        });
    }

    private static Probe startSuspendedMission(TestContext context,
                                               String botName,
                                               Goal activeGoal,
                                               Goal queuedGoal) {
        var world = context.getWorld();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 2, 1));
        prepareCell(world, cell);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), botName, world, Vec3d.ofBottomCenter(cell),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + botName));
        bot.teleport(world, cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setVelocity(Vec3d.ZERO);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE, 1));

        long resultBaseline = GoalExecutor.INSTANCE.lastResult(bot).map(GoalResult::sequence).orElse(0L);
        require(context, GoalExecutor.INSTANCE.submit(bot, activeGoal), "active goal setup failed: " + activeGoal);
        require(context, GoalExecutor.INSTANCE.submit(bot, queuedGoal), "queued goal setup failed: " + queuedGoal);
        MissionRuntimeRecord initial = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, initial.active() != null, "active mission was not captured");
        UUID missionId = UUID.fromString(initial.active().missionId());
        require(context, initial.queue().size() == 1, "queue was not established before death");

        RuntimeLifecycleCoordinator.INSTANCE.onBotDeath(bot);
        MissionRuntimeRecord suspended = GoalExecutor.INSTANCE.captureRuntime(bot);
        require(context, suspended.active() != null
                        && missionId.toString().equals(suspended.active().missionId()),
                "death suspension changed mission identity");
        require(context, suspended.queue().size() == 1, "death suspension dropped queued mission");
        require(context, GoalExecutor.INSTANCE.resultAfter(bot, resultBaseline).isEmpty(),
                "death suspension published a terminal result");

        TaskManager.INSTANCE.assign(bot,
                new RecoverDropsTask(bot.getBlockPos(), bot.getServer().getTicks()),
                TaskOrigin.safety("gametest_death_recovery"));
        return new Probe(context, botName, bot, activeGoal, queuedGoal, missionId, resultBaseline);
    }

    private static void assertSuspended(Probe probe) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(probe.bot());
        require(probe, GoalExecutor.INSTANCE.hasActivePlan(probe.bot()), "suspended mission is no longer visible");
        require(probe, runtime.active() != null
                        && probe.missionId().toString().equals(runtime.active().missionId()),
                "suspended missionId changed");
        require(probe, runtime.queue().size() == 1, "suspended queue was lost");
        require(probe, TaskManager.INSTANCE.getActive(probe.bot())
                        .filter(RecoverDropsTask.class::isInstance).isPresent(),
                "RecoverDropsTask no longer owns the safety slot");
        require(probe, TaskManager.INSTANCE.activeOrigin(probe.bot()).map(TaskOrigin::safety).orElse(false),
                "recovery task does not have SAFETY origin");
        require(probe, GoalExecutor.INSTANCE.resultAfter(probe.bot(), probe.resultBaseline()).isEmpty(),
                "suspended mission published FAILED/CANCELLED");
    }

    private static void assertResumed(Probe probe) {
        MissionRuntimeRecord runtime = GoalExecutor.INSTANCE.captureRuntime(probe.bot());
        require(probe, runtime.active() != null
                        && probe.missionId().toString().equals(runtime.active().missionId()),
                "resumed mission did not keep its missionId");
        require(probe, GoalExecutor.INSTANCE.isActiveGoal(probe.bot(), probe.activeGoal()),
                "original goal was not restored after recovery");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 1,
                "queued mission was not restored after recovery");
        require(probe, TaskManager.INSTANCE.activeOrigin(probe.bot())
                        .map(origin -> origin.kind() == TaskOrigin.Kind.MISSION
                                && probe.missionId().equals(origin.missionId()))
                        .orElse(false),
                "restored task is not owned by the original mission");
        require(probe, GoalExecutor.INSTANCE.resultAfter(probe.bot(), probe.resultBaseline()).isEmpty(),
                "mission published a terminal result before resumed work completed");
    }

    private static void assertActiveMissionCompletedAndQueuePromoted(Probe probe) {
        GoalResult result = GoalExecutor.INSTANCE.lastResult(probe.bot())
                .orElseThrow(() -> failure(probe, "missing result for resumed mission"));
        require(probe, result.sequence() > probe.resultBaseline(), "result sequence did not advance");
        require(probe, result.missionId().equals(probe.missionId()), "completed result changed missionId");
        require(probe, result.goal().equals(probe.activeGoal()), "wrong goal completed after recovery");
        require(probe, result.status() == GoalResult.Status.COMPLETED,
                "resumed mission ended as " + result.status() + ": " + result.reason());
        require(probe, GoalExecutor.INSTANCE.isActiveGoal(probe.bot(), probe.queuedGoal()),
                "preserved queued mission was not promoted");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 0,
                "promoted queue still contains a duplicate mission");
    }

    private static void assertAllCompleted(Probe probe) {
        GoalResult result = GoalExecutor.INSTANCE.lastResult(probe.bot())
                .orElseThrow(() -> failure(probe, "missing result for preserved queued mission"));
        require(probe, result.goal().equals(probe.queuedGoal()), "wrong queued goal completed");
        require(probe, result.status() == GoalResult.Status.COMPLETED,
                "queued mission ended as " + result.status() + ": " + result.reason());
        require(probe, !GoalExecutor.INSTANCE.hasActivePlan(probe.bot()), "mission remained active after completion");
        require(probe, GoalExecutor.INSTANCE.queuedGoalCount(probe.bot()) == 0, "queue was not drained");
    }

    private static void prepareCell(net.minecraft.server.world.ServerWorld world, BlockPos center) {
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlockState(center.add(dx, dy, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
        world.setBlockState(center, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        world.setBlockState(center.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    private static void cleanup(Probe probe) {
        AIPlayerManager.INSTANCE.despawn(probe.bot().getServer(), probe.botName());
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static void require(Probe probe, boolean condition, String message) {
        if (!condition) {
            cleanup(probe);
            probe.context().throwGameTestException(message);
        }
    }

    private static RuntimeException failure(Probe probe, String message) {
        cleanup(probe);
        return new IllegalStateException(message);
    }

    private record Probe(TestContext context,
                         String botName,
                         AIPlayerEntity bot,
                         Goal activeGoal,
                         Goal queuedGoal,
                         UUID missionId,
                         long resultBaseline) {
    }
}
