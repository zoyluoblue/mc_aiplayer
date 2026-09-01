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
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.stat.Stats;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Proves strict hunting can cross an initially empty perception region and collect physical loot. */
public final class HuntCrossRegionGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntPickupCheckpointRestore", tickLimit = 200)
    public void restoredPickupCollectsTheSameBoundDrop(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        for (int dx = -2; dx <= 4; dx++) {
            BlockPos feet = start.add(dx, 0, 0);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        String name = "HuntPickupRestoreGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        ItemEntity drop = new ItemEntity(world,
                start.getX() + 2.5D, start.getY() + 0.1D, start.getZ() + 0.5D,
                new ItemStack(Items.BEEF, 2));
        require(context, world.spawnEntity(drop), "failed to spawn bound beef");
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF);
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.getRegistryKey().getValue().toString(),
                start.getX(), start.getY(), start.getZ());
        Map<String, String> checkpoint = pickupCheckpoint(
                world.getRegistryKey().getValue().toString(), start, start,
                world.getTime(), drop.getUuid(), 2);
        HuntTask task = new HuntTask(1, true, cursor, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_pickup_restore"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED) {
                context.throwGameTestException(
                        "restored pickup failed: " + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 2,
                    "restored task did not collect all bound raw units");
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF)
                            >= pickupBaseline + 2,
                    "restored raw units bypassed vanilla pickup stats");
            require(context, "CLOSED_COLLECTED".equals(
                            task.checkpoint().get("transaction_state")),
                    "OPEN checkpoint was not covered by a CLOSED receipt");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntQuotaMismatchSettlement", tickLimit = 200)
    public void replanShrunkQuotaStillSettlesOpenPickupDebt(TestContext context) {
        // A mid-mission replan credits the 2 collected raw meat and re-issues the remainder
        // (4 -> 2). The successor task must settle the OPEN transaction instead of dying at
        // tick 0 with hunt_pickup_invalid_checkpoint.
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        for (int dx = -2; dx <= 4; dx++) {
            BlockPos feet = start.add(dx, 0, 0);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        String name = "HuntQuotaMismatchGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        ItemEntity drop = new ItemEntity(world,
                start.getX() + 2.5D, start.getY() + 0.1D, start.getZ() + 0.5D,
                new ItemStack(Items.BEEF, 2));
        require(context, world.spawnEntity(drop), "failed to spawn bound beef");
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.getRegistryKey().getValue().toString(),
                start.getX(), start.getY(), start.getZ());
        Map<String, String> checkpoint = pickupCheckpoint(
                world.getRegistryKey().getValue().toString(), start, start,
                world.getTime(), drop.getUuid(), 2, 4);
        HuntTask task = new HuntTask(2, true, cursor, checkpoint);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_quota_mismatch"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED) {
                context.throwGameTestException(
                        "quota-mismatched restore failed: " + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 2,
                    "settlement did not collect the bound raw units");
            require(context, "CLOSED_COLLECTED".equals(
                            task.checkpoint().get("transaction_state")),
                    "OPEN checkpoint was not covered by a CLOSED receipt");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntClosedReceiptFreshStart", tickLimit = 200)
    public void closedReceiptDoesNotPoisonSuccessorHunt(TestContext context) {
        // A hunt that already settled its pickup can still fail later (for example
        // hunt_no_progress on the next prey) and export a CLOSED_COLLECTED receipt. The
        // successor hunt owes that transaction nothing and must start fresh: it must run as a
        // normal acquisition instead of failing at tick 0. A live kill is deliberately NOT
        // asserted here - under CI load the approach itself is timing-sensitive terrain, and
        // the regression this pins is the restore decision, not the hunt's success.
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        for (int x = -2; x <= 6; x++) {
            for (int z = -2; z <= 6; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        String name = "HuntClosedReceiptGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.getRegistryKey().getValue().toString(),
                start.getX(), start.getY(), start.getZ());
        Map<String, String> closed = new LinkedHashMap<>(pickupCheckpoint(
                world.getRegistryKey().getValue().toString(), start, start,
                world.getTime(), UUID.fromString("00000000-0000-0000-0000-000000000098"), 2));
        closed.put("transaction_state", "CLOSED_COLLECTED");
        HuntTask task = new HuntTask(1, true, cursor, Map.copyOf(closed));
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_closed_receipt"));

        context.runAtTick(3, () -> {
            require(context, !"hunt_pickup_invalid_checkpoint".equals(task.failureReason()),
                    "closed receipt poisoned the successor hunt: " + task.failureReason());
            require(context, task.state() == TaskState.RUNNING,
                    "fresh hunt after a closed receipt ended early as " + task.state()
                            + ":" + task.failureReason());
            require(context, task.describe().contains("phase=ACQUIRE")
                            || task.describe().contains("phase=ROAM"),
                    "successor hunt did not resume acquisition: " + task.describe());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntDistantPreySight", tickLimit = 1600)
    public void distantPreyIsHuntedAcrossOpenGround(TestContext context) {
        // Surface prey sight must align with SEARCH_RANGE: a real player sees a cow 40+ blocks
        // away on open ground. With only the interaction-scale perception radius the search
        // box was 64 wide but visibility died at 16, so scattered herds were invisible.
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        for (int dx = -2; dx <= 48; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create cow");
        cow.setAiDisabled(true);
        cow.refreshPositionAndAngles(
                start.getX() + 44.5D, start.getY(), start.getZ() + 0.5D, 270.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn cow");

        String name = "HuntDistantPreyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.getRegistryKey().getValue().toString(),
                start.getX(), start.getY(), start.getZ());
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_distant_prey"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED) {
                context.throwGameTestException(
                        "distant prey hunt failed: " + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.BEEF) >= 1,
                    "distant prey hunt collected no raw meat");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntDistantPreySight", tickLimit = 40)
    public void distantPreySightWidensRangeButStillRequiresLineOfSight(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        for (int dx = -48; dx <= 48; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // Full-height wall on the east side: the cow behind it must stay invisible even at
        // prey-sight range, or terrain would stop hiding herds.
        for (int dy = 0; dy < 4; dy++) {
            for (int dz = -2; dz <= 2; dz++) {
                world.setBlockState(start.add(8, dy, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        var openCow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, openCow != null, "failed to create open cow");
        openCow.setAiDisabled(true);
        openCow.refreshPositionAndAngles(
                start.getX() - 40.5D, start.getY(), start.getZ() + 0.5D, 90.0F, 0.0F);
        require(context, world.spawnEntity(openCow), "failed to spawn open cow");
        var walledCow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, walledCow != null, "failed to create walled cow");
        walledCow.setAiDisabled(true);
        walledCow.refreshPositionAndAngles(
                start.getX() + 40.5D, start.getY(), start.getZ() + 0.5D, 270.0F, 0.0F);
        require(context, world.spawnEntity(walledCow), "failed to spawn walled cow");

        String name = "HuntPreySightGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        context.runAtTick(1, () -> {
            require(context, io.github.zoyluo.aibot.mode.ObservableWorldQuery
                            .canObserveEntityWithin(bot, openCow, 64),
                    "open-ground prey at 40 blocks was not visible at prey-sight range");
            require(context, !io.github.zoyluo.aibot.mode.ObservableWorldQuery
                            .canObserveEntity(bot, openCow),
                    "base entity observation widened beyond the configured perception radius");
            require(context, !io.github.zoyluo.aibot.mode.ObservableWorldQuery
                            .canObserveEntityWithin(bot, walledCow, 64),
                    "terrain stopped hiding prey at prey-sight range");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntPickupDeadlineRestore", tickLimit = 320)
    public void nearDeadlineRestoreDoesNotRefreshBoundDebt(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        String name = "HuntPickupDeadlineGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        cursor.setSurfaceAnchorIfAbsent(
                world.getRegistryKey().getValue().toString(),
                start.getX(), start.getY(), start.getZ());
        AtomicReference<HuntTask> assignedTask = new AtomicReference<>();
        AtomicInteger assignedTick = new AtomicInteger(-1);

        context.runAtEveryTick(() -> {
            HuntTask task = assignedTask.get();
            if (task == null) {
                if (world.getTime() < 239L) {
                    return;
                }
                Map<String, String> checkpoint = pickupCheckpoint(
                        world.getRegistryKey().getValue().toString(), start, start,
                        world.getTime() - 239L,
                        UUID.fromString("00000000-0000-0000-0000-000000000099"), 1);
                task = new HuntTask(1, true, cursor, checkpoint);
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(
                                TaskOrigin.Kind.VERIFY,
                                "gametest_hunt_pickup_deadline"));
                assignedTask.set(task);
                assignedTick.set(world.getServer().getTicks());
                return;
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "missing bound UUID did not remain a physical debt: "
                            + task.failureReason());
            require(context, world.getServer().getTicks() - assignedTick.get() <= 3,
                    "restored pickup received a fresh recovery deadline");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

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

        HuntTask task = anchoredHunt(bot, 1);
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
        // A low dedicated layer (like the distant-prey strip): the previous 40-up elevated ridge
        // could not prove long no-dig/no-pillar approach routes, and prey sight now requires the
        // initial approach to be provable from wherever the hunt first sees the cow.
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 5, -112));

        // An 11-degree, three-cell-wide reversible ridge leads to a small terminal pickup pad. The
        // compass-fan rotation geometry itself is pinned by the static asserts below on the pure
        // rotatedRoamColumn function; prey sight ends the old "invisible at 20 blocks" premise, so
        // the live portion now proves a direct hunt across the narrow diagonal ridge. The pad
        // catches vanilla's randomized item launch after the kill without widening the corridor.
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
        // ... hunt across the narrow diagonal ridge. The cow sits at close range (the historical
        // post-roam end state of this fixture): the surface-route proof cannot span this
        // zigzag strip at range, so prey sight must not be asked to approach across it.
        int cowX = 9;
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

        HuntTask task = anchoredHunt(bot, 1);
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

        HuntTask task = anchoredHunt(bot, 1);
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

        HuntTask task = anchoredHunt(bot, 1);
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

        HuntTask task = anchoredHunt(bot, 1);
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
            batchId = "huntFreshSurfaceAnchorPositive", tickLimit = 100)
    public void freshHuntAcceptsFactualHighSurfaceAndStartsAcquiring(TestContext context) {
        var world = context.getWorld();
        BlockPos template = context.getAbsolutePos(new BlockPos(8, 5, -368));
        BlockPos start = new BlockPos(template.getX(), 64, template.getZ());
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos feet = start.add(dx, 0, dz);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        require(context, start.getY() >= 32,
                "fresh surface fixture unexpectedly started below the planner boundary");
        require(context, world.getTopY(
                        net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                        start.getX(), start.getZ()) == start.getY(),
                "fresh surface fixture did not publish a factual terrain height");

        String name = "HuntFreshSurfaceAnchorGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.getRegistryKey().getValue().toString();
        require(context, cursor.surfaceAnchor(dimension).isEmpty(),
                "fresh surface cursor unexpectedly contained a preset anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_fresh_surface_anchor"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("fresh surface hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            HuntSearchCursor.SurfaceAnchor anchor =
                    cursor.surfaceAnchor(dimension).orElse(null);
            require(context, anchor != null,
                    "fresh surface hunt did not establish its own anchor");
            require(context, anchor.x() == start.getX()
                            && anchor.y() == start.getY()
                            && anchor.z() == start.getZ(),
                    "fresh surface hunt established the wrong anchor");
            String description = task.describe();
            if (!description.contains("phase=ACQUIRE")
                    && !description.contains("phase=ROAM")) {
                return;
            }
            require(context, task.state() == TaskState.RUNNING,
                    "fresh surface hunt did not continue into acquisition");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntFreshDeepAnchorStrict", tickLimit = 100)
    public void freshHuntRejectsSkyVisibleDeepMineAsSurfaceAnchor(TestContext context) {
        var world = context.getWorld();
        // Build the sky-visible-deep geometry explicitly instead of trusting ambient terrain
        // 200 blocks out: batch placement varies per run, and that spot sometimes lands in an
        // unticked chunk whose sky light never propagates. A pad beside this structure (always
        // inside the ticking area) below the planner's Y=32 boundary is sky visible yet deep,
        // which is exactly the fact this test pins.
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 0, 8)).withY(26);
        for (int y = start.getY() - 1; y <= 80; y++) {
            world.setBlockState(new BlockPos(start.getX(), y, start.getZ()),
                    y == start.getY() - 1
                            ? Blocks.STONE.getDefaultState()
                            : Blocks.AIR.getDefaultState(),
                    Block.NOTIFY_ALL);
        }
        require(context, start.getY() < 32,
                "deep-anchor fixture unexpectedly started above the planner boundary");
        // Sky light only propagates on the next server tick, so assert and spawn from the
        // tick callback rather than during fixture setup.
        String name = "HuntFreshDeepAnchorGT";
        AtomicReference<HuntTask> taskRef = new AtomicReference<>();
        AtomicReference<AIPlayerEntity> botRef = new AtomicReference<>();
        context.runAtEveryTick(() -> {
            if (taskRef.get() == null) {
                require(context, world.isSkyVisible(start),
                        "deep-anchor fixture must prove sky visibility alone is insufficient");
                AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                                world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                                0.0F, 0.0F, GameMode.SURVIVAL)
                        .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
                bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                        Set.of(), 0.0F, 0.0F, true);
                HuntTask task = new HuntTask(1, true);
                TaskManager.INSTANCE.assign(bot, task,
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_fresh_deep_anchor"));
                botRef.set(bot);
                taskRef.set(task);
                return;
            }
            HuntTask task = taskRef.get();
            AIPlayerEntity bot = botRef.get();
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "fresh deep hunt ended as " + task.state());
            require(context, task.failureReason().startsWith("hunt_surface_anchor_unavailable"),
                    "fresh deep hunt produced wrong failure: " + task.failureReason());
            require(context, bot.getBlockPos().equals(start),
                    "rejected deep hunt moved before establishing a surface fact: "
                            + bot.getBlockPos().toShortString());
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntQuotaSurfaceReturnStrict", tickLimit = 700)
    public void satisfiedQuotaReturnsToSurfaceBeforePublishingCompletion(TestContext context) {
        var world = context.getWorld();
        BlockPos deep = context.getAbsolutePos(new BlockPos(8, 5, -240));
        BlockPos anchor = deep.add(18, 18, 0);
        for (int step = 0; step <= 18; step++) {
            BlockPos feet = deep.add(step, step, 0);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "HuntQuotaSurfaceReturnGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(deep),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, deep.getX() + 0.5D, deep.getY(), deep.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.getRegistryKey().getValue().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension, anchor.getX(), anchor.getY(), anchor.getZ()),
                "failed to establish quota-return surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_quota_surface_return"));
        InventoryAction.giveItem(bot, new ItemStack(Items.CHICKEN));

        AtomicBoolean sawReturnDebt = new AtomicBoolean();
        AtomicBoolean movedPhysically = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            sawReturnDebt.compareAndSet(false, task.describe().contains("phase=RETURN_SURFACE"));
            movedPhysically.compareAndSet(false, !bot.getBlockPos().equals(deep));
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("quota-return hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sawReturnDebt.get() && movedPhysically.get(),
                    "satisfied quota skipped its physical surface return");
            require(context, bot.getBlockPos().getSquaredDistance(anchor) <= 4.0D,
                    "quota completed away from its surface anchor: "
                            + bot.getBlockPos().toShortString());
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) == 1,
                    "quota-return fixture lost its physical raw meat");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntSurfaceFloorPreyLive", tickLimit = 500)
    public void visiblePreyBelowMissionSurfaceFloorIsNeverPursued(TestContext context) {
        var world = context.getWorld();
        // The bot has already walked down to the last legal level of a persisted surface
        // expedition. The chicken is locally exposed and visible just beyond that boundary, but
        // it is 17 blocks below the mission-owned anchor and must therefore remain off-limits.
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 55, -224));
        BlockPos missionAnchor = start.up(16);
        int surfaceFloorY = missionAnchor.getY() - 16;
        for (int x = -3; x <= 1; x++) {
            for (int z = -3; z <= 3; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos unsafePreyCell = start.add(4, -1, 0);
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos feet = unsafePreyCell.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        var chicken = EntityType.CHICKEN.create(world, SpawnReason.COMMAND);
        require(context, chicken != null, "failed to create below-floor chicken");
        chicken.setAiDisabled(true);
        chicken.setHealth(1.0F);
        chicken.refreshPositionAndAngles(
                unsafePreyCell.getX() + 0.5D, unsafePreyCell.getY(),
                unsafePreyCell.getZ() + 0.5D, 180.0F, 0.0F);
        require(context, world.spawnEntity(chicken), "failed to spawn below-floor chicken");

        String name = "HuntSurfaceFloorGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        require(context,
                io.github.zoyluo.aibot.mode.ObservableWorldQuery.canObserveEntity(bot, chicken),
                "below-floor chicken was not visible from the legal cliff ledge");
        require(context, unsafePreyCell.getY() < surfaceFloorY,
                "fixture chicken did not cross the mission surface floor");

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.getRegistryKey().getValue().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension,
                        missionAnchor.getX(), missionAnchor.getY(), missionAnchor.getZ()),
                "failed to establish shared hunt surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        int chickenKillBaseline = bot.getStatHandler().getStat(
                Stats.KILLED, EntityType.CHICKEN);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_surface_floor_prey"));
        AtomicInteger minimumY = new AtomicInteger(bot.getBlockPos().getY());
        AtomicInteger observedTicks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            minimumY.accumulateAndGet(bot.getBlockPos().getY(), Math::min);
            observedTicks.incrementAndGet();
            // Assert on positive kill evidence, not on the captured entity reference: this
            // offset arena stays loaded only through the fake player's chunk tickets, and a
            // transient unload replaces the chicken instance, making a stale isAlive() read
            // false without any kill having happened.
            require(context, bot.getStatHandler().getStat(
                            Stats.KILLED, EntityType.CHICKEN) == chickenKillBaseline,
                    "hunt killed prey below the mission surface floor");
            require(context, minimumY.get() >= surfaceFloorY,
                    "hunt descended below the mission surface floor: minY=" + minimumY.get()
                            + " floorY=" + surfaceFloorY);
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("below-floor hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() == TaskState.FAILED) {
                require(context, !task.failureReason().isBlank(),
                        "below-floor hunt failed without a typed reason");
            } else if (observedTicks.get() < 240) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) == 0,
                    "below-floor chicken entered inventory");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntOneWayDropDebtLive", tickLimit = 700)
    public void killedPreyDropInOneWayPitFailsWithoutFollowingIt(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 70, -272));
        for (int x = -4; x <= 5; x++) {
            for (int z = -4; z <= 4; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        BlockPos killCell = start.east(3);
        BlockPos observationLedge = start.east(5);
        BlockPos pitCell = start.add(10, -13, 0);
        for (int y = pitCell.getY(); y <= start.getY() + 1; y++) {
            world.setBlockState(
                    new BlockPos(pitCell.getX(), y, pitCell.getZ()),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos feet = pitCell.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        require(context, !HuntTask.hasWalkableReturnRoute(world, pitCell, killCell),
                "deep pickup pit unexpectedly had a walkable return route");

        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create one-way-drop cow");
        cow.setAiDisabled(true);
        cow.setHealth(1.0F);
        cow.refreshPositionAndAngles(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn one-way-drop cow");

        String name = "HuntOneWayDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.getRegistryKey().getValue().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension, start.getX(), start.getY(), start.getZ()),
                "failed to establish one-way-drop surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_one_way_drop_debt"));
        AtomicReference<ItemEntity> trappedBeef = new AtomicReference<>();
        AtomicBoolean dropWasObserved = new AtomicBoolean();
        AtomicBoolean fixtureMovedBotToLedge = new AtomicBoolean();
        AtomicInteger minimumY = new AtomicInteger(start.getY());
        context.runAtEveryTick(() -> {
            minimumY.accumulateAndGet(bot.getBlockPos().getY(), Math::min);
            if (!cow.isAlive() && trappedBeef.get() == null) {
                ItemEntity beef = world.getEntitiesByClass(
                                ItemEntity.class, new Box(killCell).expand(3.0D),
                                entity -> entity.getStack().isOf(Items.BEEF))
                        .stream().findFirst().orElse(null);
                if (beef != null) {
                    beef.setPosition(
                            pitCell.getX() + 0.5D, pitCell.getY(), pitCell.getZ() + 0.5D);
                    beef.setVelocity(Vec3d.ZERO);
                    trappedBeef.set(beef);
                    bot.getActionPack().stopAll();
                    bot.teleport(world,
                            observationLedge.getX() + 0.5D, observationLedge.getY(),
                            observationLedge.getZ() + 0.5D,
                            Set.of(), bot.getYaw(), bot.getPitch(), true);
                    fixtureMovedBotToLedge.set(true);
                }
            }
            ItemEntity beef = trappedBeef.get();
            if (beef != null
                    && io.github.zoyluo.aibot.mode.ObservableWorldQuery
                    .canObserveEntity(bot, beef)) {
                dropWasObserved.set(true);
            }
            require(context, minimumY.get() >= start.getY() - 1,
                    "hunt followed meat into the one-way pit: minY=" + minimumY.get());
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("one-way-drop hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "one-way drop produced the wrong typed failure: " + task.failureReason());
            require(context, trappedBeef.get() != null && trappedBeef.get().isAlive(),
                    "fixture never retained physical beef in the one-way pit");
            require(context, fixtureMovedBotToLedge.get(),
                    "fixture never moved the bot to its safe observation ledge");
            require(context, dropWasObserved.get(),
                    "deep beef was never visibly observed by the hunt task");
            require(context, InventoryAction.countItem(bot, Items.BEEF) == 0,
                    "one-way beef entered inventory");
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF)
                            == pickupBaseline,
                    "one-way beef changed vanilla pickup statistics");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntMovingPreyRetargetLive", tickLimit = 1200)
    public void movingPreyIsRetargetedOnSafeSurfaceAndPhysicallyCollected(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 80, -320));
        int arenaRadius = 15;
        for (int x = -arenaRadius; x <= arenaRadius; x++) {
            for (int z = -arenaRadius; z <= arenaRadius; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                if (Math.abs(x) == arenaRadius || Math.abs(z) == arenaRadius) {
                    world.setBlockState(feet, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                    world.setBlockState(feet.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }

        BlockPos initialPreyCell = start.east(6);
        BlockPos movedPreyCell = start.add(-6, 0, 4);
        int surfaceFloorY = start.getY() - 16;
        require(context, HuntTask.hasRoundTripSurfaceRoute(
                        world, start, initialPreyCell, surfaceFloorY),
                "initial moving-prey cell was not safely reversible");
        require(context, HuntTask.hasRoundTripSurfaceRoute(
                        world, start, movedPreyCell, surfaceFloorY),
                "relocated moving-prey cell was not safely reversible");

        var chicken = EntityType.CHICKEN.create(world, SpawnReason.COMMAND);
        require(context, chicken != null, "failed to create moving chicken");
        chicken.setAiDisabled(false);
        chicken.setHealth(1.0F);
        chicken.refreshPositionAndAngles(
                initialPreyCell.getX() + 0.5D, initialPreyCell.getY(),
                initialPreyCell.getZ() + 0.5D, 180.0F, 0.0F);
        require(context, world.spawnEntity(chicken), "failed to spawn moving chicken");
        require(context, !chicken.isAiDisabled(),
                "moving-prey fixture accidentally disabled chicken AI");

        String name = "HuntMovingPreyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.CHICKEN);

        HuntSearchCursor cursor = HuntSearchCursor.initial();
        String dimension = world.getRegistryKey().getValue().toString();
        require(context, cursor.setSurfaceAnchorIfAbsent(
                        dimension, start.getX(), start.getY(), start.getZ()),
                "failed to establish moving-prey surface anchor");
        HuntTask task = new HuntTask(1, true, cursor);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_moving_prey_retarget"));
        AtomicBoolean preyRelocated = new AtomicBoolean();
        AtomicReference<BlockPos> botAtRelocation = new AtomicReference<>();
        AtomicBoolean relocatedMeleeEnvelopeReached = new AtomicBoolean();
        AtomicInteger maximumPostRelocationTravelSquared = new AtomicInteger();
        AtomicInteger minimumY = new AtomicInteger(start.getY());
        context.runAtEveryTick(() -> {
            minimumY.accumulateAndGet(bot.getBlockPos().getY(), Math::min);
            require(context, minimumY.get() >= surfaceFloorY,
                    "moving-prey hunt crossed its mission floor: minY=" + minimumY.get());
            require(context,
                    Math.abs(bot.getBlockPos().getX() - start.getX()) < arenaRadius
                            && Math.abs(bot.getBlockPos().getZ() - start.getZ()) < arenaRadius,
                    "moving-prey hunt left the bounded arena: "
                            + bot.getBlockPos().toShortString());
            if (chicken.isAlive()) {
                require(context,
                        Math.abs(chicken.getBlockPos().getX() - start.getX()) < arenaRadius
                                && Math.abs(chicken.getBlockPos().getZ() - start.getZ())
                                < arenaRadius,
                        "AI-enabled chicken escaped the bounded arena: "
                                + chicken.getBlockPos().toShortString());
            }

            if (!preyRelocated.get() && task.describe().contains("phase=APPROACH")) {
                BlockPos relocationOrigin = bot.getBlockPos().toImmutable();
                chicken.refreshPositionAndAngles(
                        movedPreyCell.getX() + 0.5D, movedPreyCell.getY(),
                        movedPreyCell.getZ() + 0.5D, chicken.getYaw(), chicken.getPitch());
                chicken.setVelocity(Vec3d.ZERO);
                require(context, !chicken.isAiDisabled(),
                        "relocating the chicken disabled its AI");
                require(context, chicken.getBlockPos().getSquaredDistance(initialPreyCell) >= 100.0D,
                        "fixture did not force the chicken far enough to require reselection");
                require(context, chicken.getBlockPos().getY() >= surfaceFloorY
                                && HuntTask.hasRoundTripSurfaceRoute(
                                world, relocationOrigin, chicken.getBlockPos(), surfaceFloorY),
                        "forced chicken destination was not safely reversible");
                botAtRelocation.set(relocationOrigin);
                preyRelocated.set(true);
                return;
            }

            if (preyRelocated.get()) {
                int traveledSquared = (int) Math.floor(
                        bot.getBlockPos().getSquaredDistance(botAtRelocation.get()));
                maximumPostRelocationTravelSquared.accumulateAndGet(
                        traveledSquared, Math::max);
            }
            if (preyRelocated.get()
                    && !relocatedMeleeEnvelopeReached.get()
                    && chicken.isAlive()
                    && bot.distanceTo(chicken) <= CombatCore.ATTACK_RANGE) {
                require(context, maximumPostRelocationTravelSquared.get() >= 9,
                        "hunt entered melee without physically traveling toward relocated prey");
                require(context, bot.getBlockPos().getY() >= surfaceFloorY
                                && chicken.getBlockPos().getY() >= surfaceFloorY
                                && HuntTask.hasRoundTripSurfaceRoute(
                                world, botAtRelocation.get(),
                                bot.getBlockPos(), surfaceFloorY),
                        "relocated melee envelope was not reached on reversible safe surface");
                relocatedMeleeEnvelopeReached.set(true);
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("moving-prey hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, preyRelocated.get() && relocatedMeleeEnvelopeReached.get(),
                    "hunt completed without physically reaching relocated prey on safe surface");
            require(context, !chicken.isAlive(),
                    "moving chicken remained alive after hunt completion");
            require(context, InventoryAction.countItem(bot, Items.CHICKEN) >= 1,
                    "moving-prey hunt completed without raw chicken");
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.CHICKEN)
                            > pickupBaseline,
                    "moving-prey meat did not enter through vanilla pickup statistics");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntPickupStatCompetitionLive", tickLimit = 900)
    public void vanillaPickupStatSettlesDebtAfterInventoryMeatIsConsumed(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 70, -368));
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        BlockPos killCell = start.east(4);
        BlockPos pickupCell = start.west(4);
        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create pickup-stat cow");
        cow.setAiDisabled(true);
        cow.setHealth(1.0F);
        cow.refreshPositionAndAngles(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn pickup-stat cow");

        String name = "HuntPickupStatGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF);
        int inventoryBaseline = InventoryAction.countItem(bot, Items.BEEF);

        HuntTask task = anchoredHunt(bot, 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_pickup_stat_competition"));
        AtomicBoolean pickupPaused = new AtomicBoolean();
        AtomicBoolean vanillaPickupObserved = new AtomicBoolean();
        AtomicBoolean inventoryReturnedToBaseline = new AtomicBoolean();
        AtomicBoolean acquireObserved = new AtomicBoolean();
        AtomicInteger ticksAfterResume = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("pickup-stat hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }

            if (!pickupPaused.get()
                    && !cow.isAlive()
                    && task.describe().contains("phase=PICKUP")) {
                require(context,
                        bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF)
                                == pickupBaseline,
                        "fixture missed the pre-pickup pause boundary");
                ItemEntity beef = HarvestCore.nearestDropAnyOf(
                        bot, Set.of(Items.BEEF), 16).orElse(null);
                if (beef == null) {
                    return;
                }
                beef.setPosition(
                        pickupCell.getX() + 0.5D, pickupCell.getY(),
                        pickupCell.getZ() + 0.5D);
                beef.setVelocity(Vec3d.ZERO);
                task.pause(bot);
                require(context, task.state() == TaskState.PAUSED,
                        "pickup debt did not pause before physical collection");
                pickupPaused.set(true);
            }

            if (pickupPaused.get() && !vanillaPickupObserved.get()) {
                require(context, task.state() == TaskState.PAUSED,
                        "Hunt tick ran before the pickup competition was injected");
                int pickedUp = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF);
                if (pickedUp == pickupBaseline) {
                    ItemEntity beef = HarvestCore.nearestDropAnyOf(
                            bot, Set.of(Items.BEEF), 16).orElse(null);
                    if (beef != null
                            && bot.getActionPack().isPathExecutorIdle()
                            && bot.getActionPack().isWalkToIdle()) {
                        HarvestCore.approachDropPhysically(bot, beef);
                    }
                    return;
                }
                int physicalMeat = InventoryAction.countItem(bot, Items.BEEF);
                require(context, !cow.isAlive() && physicalMeat > inventoryBaseline,
                        "pickup statistic advanced without physical cow death and inventory meat");
                vanillaPickupObserved.set(true);
                require(context, InventoryAction.removeItems(
                                bot, Items.BEEF, physicalMeat - inventoryBaseline),
                        "fixture failed to consume the physically picked-up beef");
                require(context, InventoryAction.countItem(bot, Items.BEEF) == inventoryBaseline,
                        "inventory delta did not return to its exact pre-hunt baseline");
                inventoryReturnedToBaseline.set(true);
                task.resume(bot);
                require(context, task.state() == TaskState.RUNNING,
                        "pickup debt did not resume after the competing consumption");
                return;
            }

            if (!inventoryReturnedToBaseline.get()) {
                return;
            }
            ticksAfterResume.incrementAndGet();
            require(context,
                    bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF) > pickupBaseline,
                    "vanilla pickup evidence disappeared after inventory consumption");
            require(context, InventoryAction.countItem(bot, Items.BEEF) == inventoryBaseline,
                    "fixture unexpectedly restored an inventory delta");
            require(context, !task.describe().contains("phase=APPROACH")
                            && !task.describe().contains("phase=STRIKE"),
                    "hunt tried to pursue or strike the already-dead cow again: "
                            + task.describe());
            if (!task.describe().contains("phase=ACQUIRE")) {
                require(context, ticksAfterResume.get() <= 250,
                        "pickup statistic did not settle the bounded debt: " + task.describe());
                return;
            }

            acquireObserved.set(true);
            require(context, task.state() == TaskState.RUNNING
                            && !task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "pickup debt did not leave PICKUP cleanly: "
                            + task.state() + ":" + task.failureReason());
            require(context, vanillaPickupObserved.get()
                            && inventoryReturnedToBaseline.get()
                            && acquireObserved.get(),
                    "fixture did not prove the pickup-stat competition");
            task.cancel(bot, "gametest_pickup_stat_debt_settled");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntExternalDeathCreditStrict", tickLimit = 500)
    public void externallyKilledTargetNeverCreatesPickupDebt(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 70, -400));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        BlockPos preyCell = start.east(6);
        var chicken = EntityType.CHICKEN.create(world, SpawnReason.COMMAND);
        require(context, chicken != null, "failed to create external-death chicken");
        chicken.setAiDisabled(true);
        chicken.refreshPositionAndAngles(
                preyCell.getX() + 0.5D, preyCell.getY(), preyCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(chicken), "failed to spawn external-death chicken");

        String name = "HuntExternalDeathGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int killBaseline = bot.getStatHandler().getStat(Stats.KILLED, EntityType.CHICKEN);

        HuntTask task = anchoredHunt(bot, 64);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_external_death_credit"));
        AtomicBoolean externallyKilled = new AtomicBoolean();
        AtomicBoolean pickupDebtObserved = new AtomicBoolean();
        AtomicInteger ticksAfterDeath = new AtomicInteger();
        context.runAtEveryTick(() -> {
            pickupDebtObserved.compareAndSet(
                    false, task.describe().contains("phase=PICKUP"));
            if (!externallyKilled.get() && task.describe().contains("phase=APPROACH")) {
                require(context,
                        chicken.damage(world, world.getDamageSources().generic(), 1000.0F),
                        "fixture failed to kill chicken without player credit");
                externallyKilled.set(true);
                return;
            }
            if (!externallyKilled.get()) {
                return;
            }
            ticksAfterDeath.incrementAndGet();
            require(context,
                    bot.getStatHandler().getStat(Stats.KILLED, EntityType.CHICKEN)
                            == killBaseline,
                    "external death unexpectedly credited the hunting bot");
            require(context, !pickupDebtObserved.get(),
                    "external target death created a PICKUP debt");
            if (task.state() == TaskState.FAILED || task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("external-death hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (ticksAfterDeath.get() < 5
                    || (task.describe().contains("phase=APPROACH")
                    || task.describe().contains("phase=STRIKE"))) {
                return;
            }
            task.cancel(bot, "gametest_external_death_reacquired");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntOldDropIsolationStrict", tickLimit = 1000)
    public void oldNearbyRawDropCannotPoisonFreshKillTransaction(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 70, -432));
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        BlockPos killCell = start.east(5);
        String name = "HuntOldDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF);
        int killBaseline = bot.getStatHandler().getStat(Stats.KILLED, EntityType.COW);
        int inventoryBaseline = InventoryAction.countItem(bot, Items.BEEF);
        Box arena = new Box(killCell).expand(6.0D);

        // This arena lives hundreds of blocks from the test structure and stays loaded only by
        // the fake player's own chunk tickets. Spawn the fixture entities after those tickets
        // settle, and assert through fresh world queries: a transient unload/reload replaces the
        // entity instances, so captured Java references can report a false !isAlive().
        context.runAtTick(40, () -> {
            ItemEntity oldBeef = new ItemEntity(
                    world,
                    killCell.getX() + 0.5D,
                    killCell.getY(),
                    killCell.getZ() + 2.5D,
                    new ItemStack(Items.BEEF));
            oldBeef.setVelocity(Vec3d.ZERO);
            oldBeef.setPickupDelayInfinite();
            oldBeef.setNeverDespawn();
            require(context, world.spawnEntity(oldBeef), "failed to spawn old beef");

            var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
            require(context, cow != null, "failed to create old-drop cow");
            cow.setAiDisabled(true);
            cow.setHealth(1.0F);
            cow.refreshPositionAndAngles(
                    killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                    180.0F, 0.0F);
            require(context, world.spawnEntity(cow), "failed to spawn old-drop cow");
        });

        AtomicReference<HuntTask> taskRef = new AtomicReference<>();
        context.runAtTick(80, () -> {
            ItemEntity oldBeef = onlyOldBeef(world, arena);
            require(context, oldBeef != null && oldBeef.getItemAge() < 0,
                    "old beef lost its non-fresh age marker");
            HuntTask task = anchoredHunt(bot, 64);
            taskRef.set(task);
            TaskManager.INSTANCE.assign(bot, task,
                    TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_old_drop_isolation"));
        });

        AtomicBoolean pickupObserved = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            HuntTask task = taskRef.get();
            if (task == null) {
                return;
            }
            pickupObserved.compareAndSet(
                    false, task.describe().contains("phase=PICKUP"));
            if (task.state() == TaskState.FAILED || task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("old-drop hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (!pickupObserved.get() || !task.describe().contains("phase=ACQUIRE")) {
                return;
            }
            require(context, world.getEntitiesByClass(
                            net.minecraft.entity.passive.CowEntity.class, arena,
                            Entity::isAlive).isEmpty(),
                    "old-drop fixture reached ACQUIRE before the cow died");
            ItemEntity oldBeef = onlyOldBeef(world, arena);
            require(context, oldBeef != null,
                    "unrelated old beef was consumed or mutated");
            require(context,
                    bot.getStatHandler().getStat(Stats.KILLED, EntityType.COW) > killBaseline,
                    "fresh cow kill lacked bot kill credit");
            require(context,
                    bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF) > pickupBaseline
                            && InventoryAction.countItem(bot, Items.BEEF) > inventoryBaseline,
                    "fresh cow beef was not physically collected");
            task.cancel(bot, "gametest_old_drop_ignored");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "huntCookedDropNoRawStrict", tickLimit = 1000)
    public void creditedFireAspectKillWithoutRawMeatReturnsToAcquire(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 70, -464));
        for (int x = -9; x <= 9; x++) {
            for (int z = -9; z <= 9; z++) {
                BlockPos feet = start.add(x, 0, z);
                world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        BlockPos killCell = start.east(5);
        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create Fire Aspect cow");
        cow.setAiDisabled(true);
        cow.setHealth(1.0F);
        cow.refreshPositionAndAngles(
                killCell.getX() + 0.5D, killCell.getY(), killCell.getZ() + 0.5D,
                180.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn Fire Aspect cow");

        String name = "HuntCookedDropGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        ItemStack fireSword = new ItemStack(Items.DIAMOND_SWORD);
        var enchantmentRegistry =
                world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
        var fireAspect = enchantmentRegistry.getEntry(
                        Enchantments.FIRE_ASPECT.getValue())
                .orElseThrow(() -> new IllegalStateException("missing Fire Aspect registry entry"));
        fireSword.addEnchantment(fireAspect, 1);
        InventoryAction.giveItem(bot, fireSword);
        int killBaseline = bot.getStatHandler().getStat(Stats.KILLED, EntityType.COW);
        int rawPickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF);
        int cookedPickupBaseline =
                bot.getStatHandler().getStat(Stats.PICKED_UP, Items.COOKED_BEEF);

        HuntTask task = anchoredHunt(bot, 64);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_hunt_cooked_drop_no_raw"));
        AtomicBoolean pickupObserved = new AtomicBoolean();
        AtomicBoolean cookedDropObserved = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            pickupObserved.compareAndSet(
                    false, task.describe().contains("phase=PICKUP"));
            cookedDropObserved.compareAndSet(false,
                    InventoryAction.countItem(bot, Items.COOKED_BEEF) > 0
                            || bot.getStatHandler().getStat(
                            Stats.PICKED_UP, Items.COOKED_BEEF) > cookedPickupBaseline
                            || !world.getEntitiesByClass(
                                    ItemEntity.class,
                                    new Box(killCell).expand(4.0D),
                                    item -> item.getStack().isOf(Items.COOKED_BEEF))
                            .isEmpty());
            if (task.state() == TaskState.FAILED || task.state() == TaskState.COMPLETED
                    || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("cooked-drop hunt ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (!pickupObserved.get() || !task.describe().contains("phase=ACQUIRE")) {
                return;
            }
            require(context, !cow.isAlive() && cookedDropObserved.get(),
                    "Fire Aspect fixture did not produce factual cooked beef");
            require(context,
                    bot.getStatHandler().getStat(Stats.KILLED, EntityType.COW) > killBaseline,
                    "Fire Aspect cow kill lacked bot credit");
            require(context,
                    bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF)
                            == rawPickupBaseline,
                    "Fire Aspect fixture unexpectedly produced raw beef pickup");
            require(context, !task.failureReason().startsWith("hunt_drop_unrecovered"),
                    "zero-raw credited kill became a false pickup debt");
            task.cancel(bot, "gametest_cooked_drop_reacquired");
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

        HuntTask task = anchoredHunt(bot, 1);
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

    /**
     * Re-queries the pickup-proof marker beef instead of trusting a captured reference: the
     * offset arena's chunks can transiently unload, and a reloaded {@link ItemEntity} is a new
     * instance while the marker's infinite pickup delay and never-despawn age survive in NBT.
     */
    private static ItemEntity onlyOldBeef(
            net.minecraft.server.world.ServerWorld world, Box arena) {
        var marked = world.getEntitiesByClass(
                ItemEntity.class, arena,
                entity -> entity.getStack().isOf(Items.BEEF) && entity.cannotPickup());
        return marked.size() == 1 ? marked.get(0) : null;
    }

    private static HuntTask anchoredHunt(AIPlayerEntity bot, int targetMeat) {
        HuntSearchCursor cursor = HuntSearchCursor.initial();
        BlockPos anchor = bot.getBlockPos();
        boolean established = cursor.setSurfaceAnchorIfAbsent(
                bot.getServerWorld().getRegistryKey().getValue().toString(),
                anchor.getX(), anchor.getY(), anchor.getZ());
        if (!established) {
            throw new IllegalStateException("failed to establish Hunt GameTest surface anchor");
        }
        return new HuntTask(targetMeat, true, cursor);
    }

    private static Map<String, String> pickupCheckpoint(
            String dimension, BlockPos origin, BlockPos anchor,
            long startedWorldTime, UUID dropId, int units) {
        return pickupCheckpoint(dimension, origin, anchor, startedWorldTime, dropId, units, 1);
    }

    private static Map<String, String> pickupCheckpoint(
            String dimension, BlockPos origin, BlockPos anchor,
            long startedWorldTime, UUID dropId, int units, int targetCount) {
        Map<String, String> checkpoint = new LinkedHashMap<>();
        checkpoint.put("task_schema", "1");
        checkpoint.put("cursor_kind", "hunt_pickup");
        checkpoint.put("transaction_state", "OPEN");
        checkpoint.put("target_count", String.valueOf(targetCount));
        checkpoint.put("require_full_quota", "true");
        checkpoint.put("dimension", dimension);
        checkpoint.put("expected_raw_item", "minecraft:beef");
        checkpoint.put("pickup_origin",
                origin.getX() + "," + origin.getY() + "," + origin.getZ());
        checkpoint.put("pickup_return_anchor",
                anchor.getX() + "," + anchor.getY() + "," + anchor.getZ());
        checkpoint.put("inventory_baseline", "0");
        checkpoint.put("pickup_stat_baseline", "0");
        checkpoint.put("aux_inventory_baseline", "0");
        checkpoint.put("aux_pickup_stat_baseline", "0");
        checkpoint.put("pickup_started_world_time", String.valueOf(startedWorldTime));
        checkpoint.put("bound_drop_units", dropId + "=" + units);
        return Map.copyOf(checkpoint);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
