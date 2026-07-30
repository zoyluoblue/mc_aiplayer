package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Live proof that a clientless fake player leaves shallow water through adjacent physical motion. */
public final class SurfaceWaterRecoveryGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 80)
    public void proactiveRescueStepsOntoDryGround(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -32));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        world.setBlockState(start, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        if (Standability.isStandable(world, start)) {
            context.throwGameTestException("water cell must not be an ordinary A* stand position");
        }

        String name = "SurfaceWaterRecoveryGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);

        context.runAtEveryTick(() -> {
            if (bot.getBlockPos().equals(start)
                    || bot.isTouchingWater()
                    || !Standability.isStandable(world, bot.getBlockPos())
                    || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                return;
            }
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 40)
    public void connectedShoreBeatsAnUnneededVerticalAirStroke(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 8, -106));

        // Seal a compact vertical shaft. A real dry landing is connected through the water cell
        // below the bot, while the water column above remains open. Connected-shore BFS must keep
        // priority over the low-air fallback; otherwise a harmless full-air rescue abandons a
        // proved route and starts climbing without making progress toward dry footing.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 4; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        for (int dy = -1; dy <= 2; dy++) {
            world.setBlockState(start.up(dy), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos lowerShore = start.down().east();
        world.setBlockState(lowerShore, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lowerShore.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lowerShore.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "WaterMonotonicAscentGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY() + 0.125D,
                start.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        bot.setAir(300);
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);

        require(context, NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                "water rescue did not take control");
        require(context, bot.getBlockPos().equals(lowerShore),
                "water rescue ignored the connected lower-shore route: "
                        + bot.getBlockPos().toShortString());
        require(context, bot.isAlive() && bot.getHealth() == bot.getMaxHealth(),
                "monotonic ascent lost health");

        NavSafetyNet.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 40)
    public void emergencyVerticalStepRequiresLowAir(TestContext context) {
        WaterShaftFixture fixture = sealedWaterShaftFixture(context, -126);
        AIPlayerEntity bot = fixture.bot();
        bot.setAir(300);
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);

        require(context, NavSafetyNet.INSTANCE.tickBot(context.getWorld().getServer(), bot),
                "full-air rescue did not take control");
        require(context, bot.getBlockPos().equals(fixture.lower()),
                "full-air rescue used the emergency vertical step: "
                        + bot.getBlockPos().toShortString());
        bot.setAir(100);

        require(context, NavSafetyNet.INSTANCE.tickBot(context.getWorld().getServer(), bot),
                "low-air rescue did not take control");
        require(context, bot.getBlockPos().equals(fixture.lower().up()),
                "low-air rescue failed to take the physical upward water step: "
                        + bot.getBlockPos().toShortString());

        NavSafetyNet.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 160)
    public void rescueRoutesAroundAWallEvenWhenTheFirstStepMovesAwayFromShore(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -38));
        // Seal the local volume, then carve a U-shaped two-block-deep water route. The only dry
        // landing is geometrically close behind NORTH, but NORTH itself is a wall. Reaching it
        // requires EAST as the first step, which the old greedy distance check permanently
        // rejected.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 0, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 2, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        List<BlockPos> waterRoute = List.of(
                start, start.east(), start.east().north(), start.east().north(2));
        for (BlockPos cell : waterRoute) {
            world.setBlockState(cell, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        // Keep only the initial eye cell submerged. A waterlogged plant supplies real water
        // without turning the whole upper route into spreading sources that would flood the dry
        // endpoint before the rescue reaches it.
        world.setBlockState(start.up(), Blocks.SEAGRASS.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos shore = start.north(2).up();
        world.setBlockState(shore, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(shore.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "WaterWallDetourGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setAir(260);
        AtomicReference<BlockPos> previous = new AtomicReference<>(start.toImmutable());
        AtomicBoolean sawRequiredDetour = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            BlockPos now = bot.getBlockPos();
            BlockPos before = previous.getAndSet(now.toImmutable());
            if (!now.equals(before)) {
                int dx = Math.abs(now.getX() - before.getX());
                int dy = Math.abs(now.getY() - before.getY());
                int dz = Math.abs(now.getZ() - before.getZ());
                int changed = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
                require(context, dx <= 1 && dy <= 1 && dz <= 1 && changed <= 2,
                        "water rescue used a non-adjacent movement: " + before + " -> " + now);
            }
            if (now.equals(start.east())) {
                sawRequiredDetour.set(true);
            }
            if (!now.equals(shore) || bot.isTouchingWater()
                    || NavSafetyNet.INSTANCE.isWaterRescueActive(bot)) {
                return;
            }
            require(context, sawRequiredDetour.get(),
                    "rescue reached the blocked shore without taking the physical EAST detour");
            require(context, bot.isAlive() && bot.getHealth() == bot.getMaxHealth(),
                    "rescue lost health before reaching the dry landing");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 160)
    public void descendSealsIngressAndHandsOffADryOreLayer(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -44));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // A side source feeds a flowing-water work cell after the staircase has opened it. The
        // target Y is already reached: Descend must not report success while the next OreDig would
        // still start submerged.
        world.setBlockState(start.east(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start,
                Blocks.WATER.getDefaultState().with(FluidBlock.LEVEL, 1), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DescendWaterSealGT";
        BlockPos drySpawn = start.west(2);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(drySpawn),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 8));
        DescendToYTask task = new DescendToYTask(start.getY());
        // Drive the first task tick synchronously, before the global safety net can relocate the
        // bot. This proves Descend itself seals the newly opened ingress instead of merely
        // completing wet and relying on a later rescue tick to hide the bad handoff.
        task.start(bot);
        task.tick(bot);
        if (task.state() != TaskState.RUNNING) {
            context.throwGameTestException("Descend completed before sealing its wet target layer"
                    + " bot=" + bot.getBlockPos().toShortString()
                    + " expected=" + start.toShortString()
                    + " feet=" + world.getBlockState(bot.getBlockPos()).getBlock()
                    + " east=" + world.getBlockState(start.east()).getBlock()
                    + " east_fluid=" + world.getFluidState(start.east()));
        }
        if (!world.getBlockState(start.east()).isOf(Blocks.COBBLESTONE)) {
            context.throwGameTestException("side ingress was not physically sealed");
        }

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("water handoff ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            if (bot.isSubmergedInWater() || bot.isTouchingWater()) {
                context.throwGameTestException("Descend completed on a wet ore-layer handoff");
            }
            if (!Standability.isStandable(world, bot.getBlockPos())) {
                context.throwGameTestException("Descend completed without dry footing at "
                        + bot.getBlockPos().toShortString());
            }
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 100)
    public void descendDoesNotRetrySafetyRejectedLanding(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 6, -56));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos rejected = start.north().down();
        BlockPos alternate = start.east().down();
        world.setBlockState(rejected.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(alternate.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DescendRejectedLandingGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(rejected),
                "fixture did not exercise the initial north landing");

        // Reproduce the production ordering explicitly: a dynamic footing change makes the just
        // accepted landing unsafe, SafetyNet returns the bot to its origin, then TaskManager gets
        // the next tick before SafetyNet can release rescue ownership.
        world.setBlockState(rejected.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        require(context, FakePlayerMotion.stepTo(bot, start, "gametest_rejected_landing_return"),
                "fixture could not return the bot to the dry landing origin");
        task.tick(bot);
        require(context, bot.getBlockPos().equals(start),
                "Descend immediately retried the SafetyNet-rejected landing");
        require(context, NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                "task incorrectly cleared SafetyNet ownership");

        require(context, !NavSafetyNet.INSTANCE.tickBot(world.getServer(), bot),
                "dry origin should release rescue without another movement");
        require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                "SafetyNet did not release rescue at the dry origin");
        task.tick(bot);
        require(context, bot.getBlockPos().equals(alternate),
                "Descend did not rotate to the safe alternate landing: "
                        + bot.getBlockPos().toShortString());
        task.tick(bot);
        require(context, task.state() == TaskState.COMPLETED,
                "Descend did not complete from the alternate dry landing: "
                        + task.state() + ":" + task.failureReason());
        require(context, Standability.isStandable(world, alternate),
                "alternate handoff is not physically standable");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 80)
    public void descendRelocatesFromAShorelineDeadStarBeforeMutating(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 6, -50));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.SAND.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.down(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        // Reproduce the seed-3000 shoreline topology: NORTH/WEST have no lower support while
        // EAST/SOUTH are water. The cardinal same-level fallback is therefore also invalid.
        world.setBlockState(start.east().down(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south().down(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        BlockPos staging = start.south().west();
        world.setBlockState(staging.down(), Blocks.SAND.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(staging.down(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos landing = staging.west().down();
        world.setBlockState(landing.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DescendFreshEntryRelocationGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.TORCH));
        int torchesBefore = InventoryAction.countItem(bot, Items.TORCH);

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "fresh-entry relocation ended Descend: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(staging),
                "Descend did not take the safe diagonal staging step: "
                        + bot.getBlockPos().toShortString());
        require(context, InventoryAction.countItem(bot, Items.TORCH) == torchesBefore,
                "Descend mutated inventory before completing fresh-entry relocation");
        require(context, world.getBlockState(start).isAir()
                        && world.getBlockState(staging).isAir()
                        && world.getBlockState(landing).isAir()
                        && world.getBlockState(start.down()).isOf(Blocks.SAND)
                        && world.getBlockState(staging.down()).isOf(Blocks.SAND)
                        && world.getBlockState(landing.down()).isOf(Blocks.STONE),
                "Descend mutated shoreline blocks before relocation");

        Map<String, String> checkpoint = task.checkpoint();
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "relocated Descend did not publish a valid checkpoint");
        task.cancel(bot, "gametest_restart_after_entry_relocation");
        DescendToYTask restored = new DescendToYTask(start.getY() - 1, checkpoint);
        restored.start(bot);
        int[] restoredTicks = {0};
        context.runAtEveryTick(() -> {
            if (restored.state() == TaskState.RUNNING) {
                restored.tick(bot);
                restoredTicks[0]++;
            }
            if (restored.state() == TaskState.RUNNING && restoredTicks[0] < 6) {
                return;
            }
            require(context, restored.state() == TaskState.COMPLETED,
                    "restarted Descend did not complete from the relocated staging: "
                            + restored.state() + ":" + restored.failureReason());
            require(context, restoredTicks[0] >= 2,
                    "restart proof did not cross two physical server ticks");
            require(context, bot.getBlockPos().equals(landing),
                    "restarted Descend did not use the checkpointed WEST stair: "
                            + bot.getBlockPos().toShortString());
            require(context, world.getBlockState(start.down()).isOf(Blocks.SAND)
                            && world.getBlockState(start.down(2)).isOf(Blocks.STONE)
                            && world.getBlockState(staging.down()).isOf(Blocks.SAND)
                            && world.getBlockState(staging.down(2)).isOf(Blocks.STONE)
                            && world.getBlockState(landing.down()).isOf(Blocks.STONE),
                    "restarted Descend damaged verified supports");

            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 40)
    public void descendFreshEntryRelocationCannotCutADiagonalCorner(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 6, -62));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.SAND.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.down(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east().down(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south().down(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        BlockPos staging = start.south().west();
        world.setBlockState(staging.down(), Blocks.SAND.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(staging.down(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos landing = staging.west().down();
        world.setBlockState(landing.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos blockedCorner = start.west();
        // MAGMA is a full collision corner and also prevents the ordinary upper-detour fallback
        // from treating the wall top as a safe support. The first tick therefore isolates the
        // diagonal corner rule instead of exercising an unrelated one-block retreat.
        world.setBlockState(blockedCorner, Blocks.MAGMA_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DescendFreshEntryCornerGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "blocked diagonal corner did not retain fail-closed descent: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "Descend cut across an occupied diagonal corner: "
                        + bot.getBlockPos().toShortString());
        require(context, world.getBlockState(blockedCorner).isOf(Blocks.MAGMA_BLOCK)
                        && world.getBlockState(staging.down()).isOf(Blocks.SAND)
                        && world.getBlockState(landing.down()).isOf(Blocks.STONE),
                "failed diagonal preflight mutated the corner fixture");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 120)
    public void descendNeverMinesTheWaterSealItJustPlaced(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 6, -68));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos northLanding = start.north().down();
        BlockPos alternate = start.east().down();
        world.setBlockState(northLanding.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(alternate.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos ingress = start.north().up();
        // Give vanilla placement a real adjacent face, matching an aquifer source embedded in a
        // stone wall.  A source floating in the all-air fixture cannot be sealed by player use.
        world.setBlockState(ingress.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ingress, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DescendSealOwnershipGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                "Descend did not physically seal the lateral water source");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);

        // The old loop selected NORTH again, mined this cobblestone, let water refill it and
        // repeated until all portable blocks were gone.  The sealed direction must instead stay
        // rejected while Descend rotates to EAST.
        for (int i = 0; i < 12 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
            require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                    "Descend mined its own water seal on tick " + i);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                    "Descend consumed another block after sealing one ingress");
        }
        require(context, task.state() == TaskState.COMPLETED,
                "Descend did not finish through the alternate dry stair: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(alternate),
                "Descend failed to rotate away from the sealed north stair");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 60)
    public void descendHorizontalFallbackNeverMinesItsOwnedWaterSeal(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 6, -76));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos ingress = start.north().up();
        // No lower landing has support, so after sealing NORTH the task must exhaust its stair
        // choices and enter horizontal fallback.  The old fallback then selected this exact
        // cobblestone as its first obstruction and reopened the water forever.
        world.setBlockState(ingress.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ingress, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DescendHorizontalSealGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));

        DescendToYTask task = new DescendToYTask(start.getY() - 1);
        task.start(bot);
        task.tick(bot);
        require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                "fixture did not establish the Descend-owned water seal");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);
        Map<String, String> checkpoint = task.checkpoint();
        require(context, DescendToYTask.inspectCheckpoint(checkpoint).isPresent(),
                "Descend did not publish a valid owned-seal checkpoint");
        task.cancel(bot, "gametest_restart");
        task = new DescendToYTask(start.getY() - 1, checkpoint);
        task.start(bot);

        for (int i = 0; i < 20 && task.state() == TaskState.RUNNING; i++) {
            task.tick(bot);
            require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                    "Descend horizontal fallback mined its owned water seal on tick " + i);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                    "Descend consumed another block after its fallback reopened the seal");
        }
        require(context, task.state() == TaskState.FAILED
                        && task.failureReason().startsWith("descend_no_safe_landing"),
                "sealed unsupported descent did not fail closed: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "Descend left its supported origin while every landing was unsupported");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 80)
    public void digDownPreservesItsWaterSealAndProtectedWorkstation(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 6, -80));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos eastLanding = start.east().down();
        world.setBlockState(eastLanding.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos ingress = start.north().up();
        world.setBlockState(ingress.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ingress, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DigDownSealOwnershipGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        // The protected workstation deliberately occupies the first inventory slot. Emergency
        // sealing must choose the disposable cobblestone instead of the first arbitrary BlockItem.
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));

        DigDownTask task = new DigDownTask(Blocks.STONE, 3);
        task.start(bot);
        task.tick(bot);
        require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                "DigDown did not physically seal the lateral water source");
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                "DigDown consumed the protected crafting table as a water seal");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);

        for (int i = 0; i < 12 && bot.getBlockPos().equals(start); i++) {
            task.tick(bot);
            require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                    "DigDown mined its own water seal on tick " + i);
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                    "DigDown consumed another emergency block after sealing one ingress");
            require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                    "DigDown lost the protected crafting table after sealing");
        }
        require(context, bot.getBlockPos().equals(eastLanding),
                "DigDown did not rotate onto the dry east stair: "
                        + bot.getBlockPos().toShortString());

        task.cancel(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceWaterRecoveryLive", tickLimit = 40)
    public void horizontalFallbackNeverMinesTheOwnedWaterSeal(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 6, -94));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos ingress = start.north();
        // NORTH can support a same-level horizontal move, but has no lower stair support. The
        // other three directions are unsupported. Once NORTH is sealed/rejected, the horizontal
        // fallback must fail closed without treating its own cobblestone wall as mineable stone.
        world.setBlockState(ingress.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(ingress, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "DigDownHorizontalSealGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_PICKAXE));

        DigDownTask[] active = {new DigDownTask(Blocks.STONE, 3)};
        active[0].start(bot);
        active[0].tick(bot);
        require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                "fixture did not establish an owned water seal");
        int blocksAfterSeal = InventoryAction.countItem(bot, Items.COBBLESTONE);
        boolean[] restartedSettleDebt = {false};

        for (int settleTick = 0; settleTick < 40
                && active[0].state() == TaskState.RUNNING; settleTick++) {
            active[0].tick(bot);
            Map<String, String> saved = active[0].checkpoint();
            DigDownTask.DigDownCheckpoint live =
                    DigDownTask.DigDownCheckpoint.decode(saved).orElse(null);
            if (!restartedSettleDebt[0] && live != null
                    && live.phase() == DigDownTask.Phase.DESCEND
                    && live.pickupGrace() > 0) {
                require(context, !live.horizontalMode(),
                        "fixture unexpectedly latched horizontal mode before stair fallback");
                active[0].abort(bot);
                active[0] = new DigDownTask(Blocks.STONE, 3, saved);
                active[0].start(bot);
                restartedSettleDebt[0] = true;
            }
        }
        require(context, restartedSettleDebt[0],
                "fixture never checkpointed the armed stair-to-horizontal settle debt");
        require(context, active[0].state() == TaskState.FAILED
                        && active[0].failureReason().startsWith("dig_down_walled"),
                "fully rejected origin did not fail closed: "
                        + active[0].state() + ":" + active[0].failureReason());
        require(context, EpisodeMemory.INSTANCE.isExcluded(
                        bot.getUuid(), start, bot.getServer().getTicks()),
                "observed walled entry was not retained for the next physical relocation");
        require(context, world.getBlockState(ingress).isOf(Blocks.COBBLESTONE),
                "horizontal fallback mined its owned water seal");
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == blocksAfterSeal,
                "horizontal fallback consumed another emergency block");
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1,
                "horizontal fallback consumed the protected workstation");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    private static WaterShaftFixture sealedWaterShaftFixture(TestContext context, int z) {
        var world = context.getWorld();
        BlockPos lower = context.getAbsolutePos(new BlockPos(8, 24, z));

        // Fill the complete local rescue window, then carve only a two-cell water shaft. There is
        // deliberately no dry standable target for either connected-shore BFS or the legacy shore
        // search. The first tick therefore isolates the full-air gate; changing only the oxygen
        // level on the second tick proves that the adjacent emergency ascent remains available.
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                for (int dy = -16; dy <= 16; dy++) {
                    world.setBlockState(lower.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(lower, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lower.up(), Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(lower.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();

        String name = "WaterShaftGT" + Math.abs(z);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(lower),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, lower.getX() + 0.5D, lower.getY() + 0.125D,
                lower.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        return new WaterShaftFixture(name, bot, lower.toImmutable());
    }

    private record WaterShaftFixture(String name, AIPlayerEntity bot, BlockPos lower) {
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
