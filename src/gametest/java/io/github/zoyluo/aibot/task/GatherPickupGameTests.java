package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Strict-survival regressions for Gather's physical drop transaction. */
public final class GatherPickupGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "gatherPickupLive", tickLimit = 500)
    public void vanillaPickupStatSurvivesConcurrentLogConsumption(TestContext context) {
        Fixture fixture = fixture(context, "GatherPickupStatGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        BlockPos first = fixture.start().east(3);
        bot.getServerWorld().setBlockState(first, Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_LOG));
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.OAK_LOG);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 2);
        task.start(bot);
        AtomicBoolean consumed = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (!consumed.get() && task.describe().contains("phase=PICKUP")) {
                require(context, InventoryAction.removeItems(bot, Items.OAK_LOG, 1),
                        "failed to simulate concurrent resupply consumption");
                consumed.set(true);
            }
            boolean pickupRecorded = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.OAK_LOG)
                    > pickupBaseline;
            boolean atomicPhaseResolved = !task.describe().contains("phase=PICKUP")
                    && !task.describe().contains("phase=HARVEST");
            if (!consumed.get() || !pickupRecorded || !atomicPhaseResolved) {
                return;
            }
            require(context, task.state() == TaskState.RUNNING,
                    "net-zero pickup was mistaken for terminal gather state: " + task.describe());
            require(context, InventoryAction.countItem(bot, Items.OAK_LOG) == 1,
                    "physical pickup did not replace the concurrently consumed log");
            finish(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "gatherPickupLive", tickLimit = 700)
    public void realMissRetriesNearbyResourceBeforeRegionalRoam(TestContext context) {
        Fixture fixture = fixture(context, "GatherMissRetryGT", new BlockPos(2, 2, 2), 5);
        AIPlayerEntity bot = fixture.bot();
        BlockPos first = fixture.start().east(2);
        BlockPos second = fixture.start().east(5);
        bot.getServerWorld().setBlockState(first, Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);
        bot.getServerWorld().setBlockState(second, Blocks.OAK_LOG.getDefaultState(), Block.NOTIFY_ALL);

        GatherQuotaTask task = new GatherQuotaTask(Items.OAK_LOG, 1);
        task.start(bot);
        AtomicBoolean discarded = new AtomicBoolean();
        AtomicBoolean revisitedBreakCell = new AtomicBoolean();
        AtomicBoolean localRetryHarvestStarted = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            tickOrFail(context, task, bot);
            if (!discarded.get() && task.describe().contains("phase=PICKUP")) {
                ItemEntity drop = nearestOakDrop(bot, first, 3.0D);
                require(context, drop != null, "first harvest produced no removable test drop");
                drop.discard();
                discarded.set(true);
            }
            if (discarded.get() && bot.getBlockPos().equals(first)) {
                revisitedBreakCell.set(true);
            }
            if (discarded.get()
                    && task.describe().contains("phase=HARVEST")
                    && bot.getServerWorld().getBlockState(second).isOf(Blocks.OAK_LOG)) {
                localRetryHarvestStarted.set(true);
            }
            if (discarded.get() && !localRetryHarvestStarted.get()
                    && task.state() == TaskState.RUNNING) {
                require(context, !task.describe().contains("phase=ROAM")
                                && !task.describe().contains("phase=EXPLORE"),
                        "one real pickup miss bypassed the local retry budget: " + task.describe());
            }
            if (!localRetryHarvestStarted.get()
                    || !bot.getServerWorld().getBlockState(second).isAir()) {
                return;
            }
            require(context, discarded.get(), "real-miss fixture never activated");
            require(context, revisitedBreakCell.get(),
                    "invisible drop did not fall back to its remembered break coordinate");
            require(context, bot.getBlockPos().getSquaredDistance(fixture.start()) < 20.0D * 20.0D,
                    "gather escaped the local test area before retrying the nearby log");
            require(context, !task.describe().contains("phase=ROAM")
                            && !task.describe().contains("phase=EXPLORE"),
                    "local retry entered regional roaming before its second harvest settled");
            finish(context, fixture);
        });
    }

    private static Fixture fixture(TestContext context, String name, BlockPos relativeStart, int east) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(relativeStart);
        // Keep every mutation inside FabricGameTest.EMPTY_STRUCTURE (8x8). Tests from other
        // batches can overlap in wall-clock time; writing a long runway beyond the template lets
        // a later fixture erase this floor and turns a pickup assertion into a random void fall.
        for (int dx = -2; dx <= east; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return new Fixture(bot, start, name);
    }

    private static ItemEntity nearestOakDrop(AIPlayerEntity bot, BlockPos center, double radius) {
        return bot.getServerWorld().getEntitiesByClass(
                        ItemEntity.class, new Box(center).expand(radius),
                        entity -> entity.getStack().isOf(Items.OAK_LOG))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static void tickOrFail(TestContext context, GatherQuotaTask task, AIPlayerEntity bot) {
        if (task.state() == TaskState.RUNNING) {
            task.tick(bot);
        }
        if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
            context.throwGameTestException("gather ended as " + task.state() + ":" + task.failureReason());
        }
    }

    private static void finish(TestContext context, Fixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private record Fixture(AIPlayerEntity bot, BlockPos start, String name) {
    }
}
