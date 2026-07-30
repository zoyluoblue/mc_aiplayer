package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.HarvestCore;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Proves strict hunting can cross an initially empty perception region and collect physical loot. */
public final class HuntCrossRegionGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntTargetReloadLive", tickLimit = 1200)
    public void unloadedTargetIsReacquiredInsteadOfInventingPickupDebt(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -152));
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 12; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        var original = EntityType.CHICKEN.create(world, SpawnReason.COMMAND);
        require(context, original != null, "failed to create original chicken");
        original.setAiDisabled(true);
        original.refreshPositionAndAngles(
                start.getX() + 0.5D, start.getY(), start.getZ() + 5.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(original), "failed to spawn original chicken");

        String name = "HuntTargetReloadGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.CHICKEN);

        HuntTask task = new HuntTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_target_reload"));
        AtomicBoolean unloaded = new AtomicBoolean();
        AtomicBoolean sawReacquire = new AtomicBoolean();
        AtomicReference<net.minecraft.entity.passive.ChickenEntity> replacement =
                new AtomicReference<>();

        context.runAtEveryTick(() -> {
            String description = task.describe();
            if (!unloaded.get() && description.contains("phase=APPROACH")) {
                Vec3d preyPos = original.getPos();
                original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                var reloaded = EntityType.CHICKEN.create(world, SpawnReason.COMMAND);
                require(context, reloaded != null, "failed to recreate unloaded chicken");
                reloaded.setAiDisabled(true);
                reloaded.setHealth(1.0F);
                reloaded.refreshPositionAndAngles(
                        preyPos.x, preyPos.y, preyPos.z, 180.0F, 0.0F);
                require(context, world.spawnEntity(reloaded), "failed to spawn reloaded chicken");
                replacement.set(reloaded);
                unloaded.set(true);
                return;
            }
            if (unloaded.get() && description.contains("phase=ACQUIRE")) {
                sawReacquire.set(true);
            }
            if (description.contains("phase=PICKUP")) {
                require(context, world.getEntitiesByClass(
                                net.minecraft.entity.passive.ChickenEntity.class,
                                new Box(start).expand(16.0D), chicken -> chicken.isAlive()).isEmpty(),
                        "hunt opened pickup debt while the reloaded chicken was still alive");
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("target-reload hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, unloaded.get() && sawReacquire.get(),
                    "fixture did not exercise target unload followed by reacquisition");
            require(context, replacement.get() != null && !replacement.get().isAlive(),
                    "reloaded chicken remained alive after hunt completion");
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) >= 1,
                    "reloaded hunt completed without raw chicken");
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.CHICKEN)
                            > pickupBaseline,
                    "reloaded hunt did not collect meat through vanilla pickup");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntRotatedRetryLive", tickLimit = 1400)
    public void rejectedCompassFanRotatesOntoReversibleRidge(TestContext context) {
        var world = context.getWorld();
        // This fixture extends well beyond the 15x15 template. Keep it on a dedicated elevated
        // layer so concurrently placed GameTests cannot overwrite the ridge or raise the spawn.
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 40, -112));

        // An 11-degree, three-cell-wide reversible ridge leads to a small terminal pickup pad. The
        // initial 0/45/90-degree compass fan has no standable candidate at 8/16/32 blocks; retry #1
        // must rotate its geometry to find the ridge. The pad catches vanilla's randomized item
        // launch after the kill without widening the discovery corridor or testing an impossible
        // recovery from the natural floor six blocks below. A cow at 20 blocks remains outside
        // strict perception until the bot has physically advanced along the accepted surface path.
        for (int x = 0; x <= 36; x++) {
            int z = (int) Math.round(x * Math.tan(Math.toRadians(11.0D)));
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos feet = start.add(x, 0, z + dz);
                world.setBlockState(
                        feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        BlockPos firstCompass = HuntTask.rotatedRoamColumn(start, 1, 0, 32, 0);
        BlockPos rotatedRetry = HuntTask.rotatedRoamColumn(start, 1, 0, 32, 1);
        require(context, firstCompass.equals(start.add(32, 0, 0)),
                "serial-zero roam geometry changed: " + firstCompass.toShortString());
        require(context, !world.getBlockState(firstCompass.down()).isOf(Blocks.STONE),
                "initial compass fan unexpectedly intersected the widened ridge");
        require(context, !rotatedRetry.equals(firstCompass)
                        && world.getBlockState(rotatedRetry.down()).isOf(Blocks.STONE),
                "retry fan did not rotate onto the reversible ridge: " + rotatedRetry.toShortString());

        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create rotated-retry cow");
        cow.setAiDisabled(true);
        cow.setHealth(1.0F);
        int cowX = 20;
        int cowZ = (int) Math.round(cowX * Math.tan(Math.toRadians(11.0D)));
        BlockPos cowFeet = start.add(cowX, 0, cowZ);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos pickupCell = cowFeet.add(dx, 0, dz);
                world.setBlockState(pickupCell.down(),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(pickupCell,
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(pickupCell.up(),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        cow.refreshPositionAndAngles(
                cowFeet.getX() + 0.5D, cowFeet.getY(), cowFeet.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn rotated-retry cow");

        String name = "HuntRotatedRetryGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        require(context, bot.getBlockPos().equals(start),
                "rotated-retry fixture spawn drifted off its isolated ridge: "
                        + bot.getBlockPos().toShortString());
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = new HuntTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_rotated_retry"));
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("rotated-retry hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 1,
                    "rotated-retry hunt completed without physical beef pickup");
            require(context, bot.getBlockPos().getX() >= start.getX() + 4,
                    "hunt never physically advanced onto the retry ridge: "
                            + bot.getBlockPos().toShortString());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntHiddenDropLive", tickLimit = 900)
    public void rememberedKillCellRoutesAroundNewOccludingWall(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -40));
        for (int x = -7; x <= 7; x++) {
            for (int z = -4; z <= 12; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create hidden-drop cow");
        cow.setAiDisabled(true);
        cow.setHealth(1.0F);
        BlockPos killCell = start.south(6);
        cow.refreshPositionAndAngles(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn hidden-drop cow");

        String name = "HuntHiddenDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = new HuntTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_hidden_drop"));
        AtomicBoolean wallBuilt = new AtomicBoolean();
        AtomicBoolean dropWasOccluded = new AtomicBoolean();

        context.runAtEveryTick(() -> {
            if (!cow.isAlive() && !wallBuilt.get()) {
                ItemEntity beef = world.getEntitiesByClass(
                                ItemEntity.class, new Box(killCell).expand(3.0D),
                                entity -> entity.getStack().isOf(Items.BEEF))
                        .stream().findFirst().orElse(null);
                if (beef != null) {
                    BlockPos wall = killCell.north();
                    for (int dx = -2; dx <= 2; dx++) {
                        world.setBlockState(wall.add(dx, 0, 0),
                                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        world.setBlockState(wall.add(dx, 1, 0),
                                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                    }
                    beef.setVelocity(Vec3d.ZERO);
                    wallBuilt.set(true);
                    dropWasOccluded.set(!bot.canSee(beef));
                    require(context, dropWasOccluded.get(),
                            "fixture failed to occlude the post-kill beef");
                }
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("hidden-drop hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, wallBuilt.get() && dropWasOccluded.get(),
                    "hunt completed without exercising remembered-cell recovery");
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 1,
                    "hidden beef never entered inventory physically");
            BlockPos wall = killCell.north();
            for (int dx = -2; dx <= 2; dx++) {
                require(context, world.getBlockState(wall.add(dx, 0, 0)).isOf(Blocks.STONE)
                                && world.getBlockState(wall.add(dx, 1, 0)).isOf(Blocks.STONE),
                        "hidden-drop route dug through its occluding wall");
            }
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntSplitLootRecoveryLive", tickLimit = 700)
    public void observedWoolPickupTriggersPhysicalRecoveryOfMissedMutton(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -176));
        for (int x = -6; x <= 10; x++) {
            for (int z = -6; z <= 6; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        var sheep = EntityType.SHEEP.create(world, SpawnReason.COMMAND);
        require(context, sheep != null, "failed to create split-loot sheep");
        sheep.setAiDisabled(true);
        sheep.setHealth(1.0F);
        BlockPos killCell = start.east(4);
        sheep.refreshPositionAndAngles(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(sheep), "failed to spawn split-loot sheep");

        String name = "HuntSplitLootGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = new HuntTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_split_loot"));
        AtomicReference<ItemEntity> heldMutton = new AtomicReference<>();
        AtomicBoolean woolArrivedFirst = new AtomicBoolean();
        AtomicBoolean muttonReleased = new AtomicBoolean();
        BlockPos releaseCell = killCell.east();
        int pickedMuttonBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.MUTTON);
        context.runAtEveryTick(() -> {
            if (!sheep.isAlive() && heldMutton.get() == null) {
                ItemEntity mutton = world.getEntitiesByClass(
                                ItemEntity.class, new Box(killCell).expand(3.0D),
                                entity -> entity.getStack().isOf(Items.MUTTON))
                        .stream().findFirst().orElse(null);
                if (mutton != null) {
                    mutton.setPickupDelayInfinite();
                    mutton.setPosition(
                            releaseCell.getX() + 0.5D, releaseCell.getY(), releaseCell.getZ() + 0.5D);
                    mutton.setVelocity(Vec3d.ZERO);
                    heldMutton.set(mutton);
                }
            }
            if (heldMutton.get() != null
                    && InventoryAction.countItem(bot, Items.WHITE_WOOL) > 0
                    && InventoryAction.countItem(bot, Items.MUTTON) == 0) {
                woolArrivedFirst.set(true);
                if (!muttonReleased.get()) {
                    heldMutton.get().resetPickupDelay();
                    muttonReleased.set(true);
                }
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("split-loot hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, woolArrivedFirst.get() && muttonReleased.get(),
                    "fixture did not separate the real wool and mutton pickups");
            require(context, InventoryAction.countItem(bot, Items.MUTTON) >= 1,
                    "missed mutton never entered inventory through physical pickup");
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.MUTTON)
                            > pickedMuttonBaseline,
                    "mutton inventory changed without a vanilla physical pickup statistic");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void surfaceRoamRejectsOneWayDropPocket(TestContext context) {
        var world = context.getWorld();
        BlockPos origin = context.getAbsolutePos(new BlockPos(4, 8, 4));
        BlockPos pocket = origin.add(4, -3, 0);
        world.setBlockState(origin.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(origin.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(pocket.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(pocket, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(pocket.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        require(context, !HuntTask.hasWalkableReturnRoute(world, pocket, origin),
                "one-way drop pocket was accepted as reusable surface exploration");

        BlockPos flat = origin.east();
        world.setBlockState(flat.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(flat, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(flat.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        require(context, HuntTask.hasWalkableReturnRoute(world, flat, origin),
                "adjacent reversible surface waypoint was rejected");
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntWetPreyRejectionLive", tickLimit = 1000)
    public void waterRescueDoesNotImmediatelyRetargetSamePrey(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -144));
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        var sheep = EntityType.SHEEP.create(world, SpawnReason.COMMAND);
        require(context, sheep != null, "failed to create wet-prey fixture sheep");
        sheep.setAiDisabled(true);
        sheep.setHealth(1.0F);
        sheep.refreshPositionAndAngles(
                start.getX() + 4.5D, start.getY(), start.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(sheep), "failed to spawn wet-prey fixture sheep");

        String name = "HuntWetPreyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));

        HuntTask task = new HuntTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_wet_prey_rejection"));
        BlockPos wetCell = start.south(2);
        AtomicBoolean injected = new AtomicBoolean();
        AtomicBoolean rejectionObserved = new AtomicBoolean();
        AtomicInteger dryTicksAfterRescue = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (!injected.get() && task.describe().contains("phase=APPROACH")) {
                world.setBlockState(wetCell, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                bot.teleport(world,
                        wetCell.getX() + 0.5D, wetCell.getY(), wetCell.getZ() + 0.5D,
                        Set.of(), bot.getYaw(), bot.getPitch(), true);
                injected.set(true);
                return;
            }
            if (injected.get() && task.isWetPreyTemporarilyRejected(sheep.getUuid())) {
                rejectionObserved.set(true);
                // Let the shared rescue own the handoff, then remove the artificial source so the
                // deterministic fixture cannot spread water across the otherwise dry arena.
                world.setBlockState(wetCell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            if (rejectionObserved.get() && !NavSafetyNet.INSTANCE.isWaterRescueActive(bot)
                    && !bot.isTouchingWater()) {
                dryTicksAfterRescue.incrementAndGet();
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("wet-prey hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (dryTicksAfterRescue.get() < 40) {
                return;
            }
            require(context, injected.get() && rejectionObserved.get(),
                    "fixture never exercised the Hunt-to-NavSafetyNet water handoff");
            require(context, task.isWetPreyTemporarilyRejected(sheep.getUuid()),
                    "wet prey UUID was not retained through dry-ground recovery");
            require(context, sheep.isAlive(),
                    "hunt immediately retargeted and killed the same sheep after water rescue");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntCrossRegionLive", tickLimit = 2000)
    public void boundedHuntWalksToPreyOutsideInitialPerception(TestContext context) {
        var world = context.getWorld();
        // GameTest lays every structure on the positive-Z grid before executing batches. Reserve a
        // negative-Z lane for this longer live fixture so its prey/corridor cannot overlap another
        // template even when that neighboring test has not started yet. Extend the lane east: that
        // is the first standable destination in HuntTask's deterministic compass fan. A north/south
        // lane let the bot accept an unrelated barrier foundation to the east and made the proof
        // depend on the placement/order of every other GameTest.
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -72));
        for (int x = -4; x <= 36; x++) {
            for (int z = -5; z <= 5; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        var chicken = EntityType.CHICKEN.create(world, SpawnReason.COMMAND);
        if (chicken == null) {
            context.throwGameTestException("failed to create chicken");
            return;
        }
        chicken.setAiDisabled(true);
        // This fixture owns cross-region discovery and physical pickup, not repeated combat cadence
        // (the seed evidence and planner tests cover multi-kill batches). An adult chicken always
        // drops one raw chicken, removing the cow-loot variance from this navigation contract.
        chicken.setHealth(1.0F);
        // Default strict perception is 16 blocks. Keep the prey outside that boundary while
        // minimizing writes beyond EMPTY_STRUCTURE; the unique batch prevents live-test overlap.
        chicken.refreshPositionAndAngles(
                start.getX() + 20.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        require(context, world.spawnEntity(chicken), "failed to spawn cross-region chicken");

        String name = "HuntCrossRegionGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.CHICKEN);

        HuntTask task = new HuntTask(1, true);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_cross_region"));
        context.runAtEveryTick(() -> {
            if (task.describe().contains("phase=PICKUP")) {
                require(context, world.getEntitiesByClass(
                                net.minecraft.entity.passive.ChickenEntity.class,
                                new Box(start).expand(32.0D), prey -> prey.isAlive()).isEmpty(),
                        "cross-region hunt opened pickup debt while its chicken was still alive");
            }
            if (InventoryAction.countItem(bot, Items.CHICKEN) == 0
                    && HarvestCore.nearestDropAnyOf(bot, Set.of(Items.CHICKEN), 16).isPresent()) {
                String description = task.describe();
                require(context, description.contains("phase=STRIKE")
                                || description.contains("phase=PICKUP"),
                        "hunt abandoned an observed meat drop for a new roam: " + task.describe());
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("cross-region hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            int meat = InventoryAction.countItem(bot, Items.CHICKEN);
            require(context, meat >= 1, "hunt completed without physical pickup: " + meat);
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.CHICKEN)
                            > pickupBaseline,
                    "hunt meat did not enter through vanilla pickup statistics");
            require(context, bot.getBlockPos().getX() >= start.getX() + 12,
                    "hunt never crossed the initial perception region: " + bot.getBlockPos().toShortString());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
