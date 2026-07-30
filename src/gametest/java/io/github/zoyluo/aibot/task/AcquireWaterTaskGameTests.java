package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Live proof that surface water is reached physically, filled through vanilla, and restartable. */
public final class AcquireWaterTaskGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAcquisitionLive", tickLimit = 600)
    public void visibleWaterBeyondInitialViewSurvivesCheckpointRestart(TestContext context) {
        WaterFixture fixture = spawnWaterSeeker(context, "WaterAcquireGT");
        AIPlayerEntity bot = fixture.bot();
        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(fixture.start()));
        AtomicBoolean restarted = new AtomicBoolean();
        active.get().start(bot);

        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();
            if (!restarted.get() && Integer.parseInt(checkpoint.get("issued")) >= 1) {
                String direction = checkpoint.get("direction");
                String legLength = checkpoint.get("leg_length");
                int used = Integer.parseInt(checkpoint.get("budget_used"));
                task.cancel(bot, "gametest_restart_boundary");

                AcquireWaterTask restored = new AcquireWaterTask(fixture.start(), checkpoint);
                restored.start(bot);
                Map<String, String> after = restored.checkpoint();
                require(context, restored.state() == TaskState.RUNNING,
                        "valid water checkpoint did not restart");
                require(context, direction.equals(after.get("direction"))
                                && legLength.equals(after.get("leg_length"))
                                && encode(fixture.start().east(12)).equals(
                                after.get("waypoint"))
                                && Integer.parseInt(after.get("budget_used")) >= used,
                        "water cursor was not restored to its nominal waypoint: before="
                                + checkpoint + " after=" + after);
                active.set(restored);
                restarted.set(true);
                return;
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("water task failed: " + task.failureReason()
                        + " checkpoint=" + checkpoint);
                return;
            }
            if (task.state() == TaskState.COMPLETED) {
                require(context, restarted.get(), "task completed without exercising restart");
                require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                        "vanilla fill did not produce exactly one water bucket");
                require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0,
                        "empty bucket was not consumed by vanilla interaction");
                require(context, bot.getBlockPos().getSquaredDistance(fixture.start()) >= 9.0D,
                        "bot acquired out-of-view water without physical travel");
                require(context, !bot.getServerWorld().getFluidState(fixture.water()).isIn(FluidTags.WATER),
                        "source block was not drained by the bucket interaction");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
                context.complete();
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterApproachExactStandStrict", tickLimit = 200)
    public void approachDescendsToExactReachableStandBeforeFilling(TestContext context) {
        var world = context.getWorld();
        BlockPos stand = context.getAbsolutePos(new BlockPos(8, 6, 8));
        BlockPos start = stand.east().up();
        BlockPos source = stand.south(3).down(2);

        // P=start is loosely "near" W=stand (distance squared 2), but P's eye is outside the
        // bucket reach of Q=source. W is within reach. This is the seed-3000 failure geometry:
        // the old APPROACH predicate stopped its live path before completing the one-block drop.
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 5; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    world.setBlockState(stand.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(stand.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = 0; dz <= 5; dz++) {
                world.setBlockState(source.add(dx, -1, dz - 3),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        world.setBlockState(source, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterExactStandGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "water_exact_stand_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }
        double startReachSquared = bot.getEyePos().squaredDistanceTo(source.toCenterPos());
        double exactReachSquared = Vec3d.ofBottomCenter(stand).add(0.0D, 1.62D, 0.0D)
                .squaredDistanceTo(source.toCenterPos());
        double interactionReachSquared = bot.getBlockInteractionRange()
                * bot.getBlockInteractionRange();
        require(context, start.getSquaredDistance(stand) <= 4.0D
                        && startReachSquared > interactionReachSquared
                        && exactReachSquared < interactionReachSquared,
                "fixture did not isolate loose arrival from exact interaction reach: start="
                        + startReachSquared + " exact=" + exactReachSquared
                        + " reach=" + interactionReachSquared);

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(stand, approachCheckpoint(stand, source, stand)));
        active.get().start(bot);
        active.get().tick(bot);
        Map<String, String> beforeRestart = active.get().checkpoint();
        require(context, active.get().state() == TaskState.RUNNING
                        && "APPROACH".equals(beforeRestart.get("phase"))
                        && encode(source).equals(beforeRestart.get("water_source"))
                        && encode(stand).equals(beforeRestart.get("water_stand")),
                "loose arrival rejected the reachable source before exact travel: "
                        + beforeRestart);
        require(context, stand.equals(bot.getActionPack().activePathGoal()),
                "APPROACH did not retain the exact stand as its live path goal: "
                        + bot.getActionPack().activePathGoal());
        require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1
                        && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0
                        && world.getFluidState(source).isIn(FluidTags.WATER),
                "loose arrival mutated the bucket or source before exact travel");

        // Restart at the vulnerable boundary: the exact target must survive and be retried from
        // the factual high cell, rather than being downgraded to a rejected SEARCH source.
        active.get().cancel(bot, "gametest_exact_stand_restart");
        AcquireWaterTask restored = new AcquireWaterTask(stand, beforeRestart);
        restored.start(bot);
        require(context, restored.state() == TaskState.RUNNING
                        && "APPROACH".equals(restored.checkpoint().get("phase")),
                "exact-stand APPROACH checkpoint did not restart: " + restored.failureReason());
        active.set(restored);

        AtomicReference<BlockPos> previous = new AtomicReference<>(start.toImmutable());
        AtomicBoolean reachedExactStand = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            BlockPos now = bot.getBlockPos().toImmutable();
            BlockPos before = previous.get();
            if (!now.equals(before)) {
                int horizontal = Math.abs(now.getX() - before.getX())
                        + Math.abs(now.getZ() - before.getZ());
                int vertical = now.getY() - before.getY();
                require(context, horizontal <= 1 && Math.abs(vertical) <= 1
                                && (horizontal > 0 || vertical != 0),
                        "water approach used non-adjacent movement: " + before + " -> " + now);
            }
            if (now.equals(stand)) {
                reachedExactStand.set(true);
            } else {
                require(context, task.state() == TaskState.RUNNING
                                && "APPROACH".equals(task.checkpoint().get("phase"))
                                && world.getFluidState(source).isIn(FluidTags.WATER)
                                && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0,
                        "APPROACH rejected or filled before exact stand: pos=" + now
                                + " checkpoint=" + task.checkpoint());
            }

            task.tick(bot);
            previous.set(bot.getBlockPos().toImmutable());
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("exact-stand water approach failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, reachedExactStand.get() && bot.getBlockPos().equals(stand),
                    "bucket filled without physically reaching the exact stand");
            require(context, bot.getEyePos().squaredDistanceTo(source.toCenterPos())
                            <= interactionReachSquared,
                    "bucket filled outside the bot's current interaction reach");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                    "exact stand did not perform one vanilla bucket exchange");
            require(context, world.getFluidState(source).isEmpty(),
                    "exact stand did not physically drain the source block");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentOpenCaveLive", tickLimit = 600)
    public void openCaveAscentPlacesOneVisibleSupportAndSurvivesRestart(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(18, 4, 18));
        BlockPos surfaceAnchor = start.up();

        // Reproduce the seed-3000 mechanism: a carved stair emerges into a dry chamber whose four
        // adjacent same-level cells are air. Every diagonal-up landing therefore lacks an existing
        // support even though a normal player can place one against the observable stone floor.
        for (int dx = -7; dx <= 7; dx++) {
            for (int dz = -7; dz <= 7; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 0, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 1; dy <= 4; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(start.add(dx, 0, dz),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        // Recess the source into the surrounding surface floor. It is observable from the proven
        // exit without adding a separate long-range search/path fixture to this ascent test.
        // Leave start.north(2) as the first already-supported outward surface cell. The placed
        // start.north support is an isolated landing by itself and must not authorize SEARCH.
        BlockPos water = start.north(3);
        world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterOpenCaveGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT));

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(surfaceAnchor));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicReference<BlockPos> placedSupport = new AtomicReference<>();
        active.get().start(bot);
        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);

            if (placedSupport.get() == null) {
                for (Direction direction : Direction.Type.HORIZONTAL) {
                    BlockPos candidate = start.offset(direction);
                    if (world.getBlockState(candidate).isOf(Blocks.DIRT)) {
                        placedSupport.set(candidate.toImmutable());
                        break;
                    }
                }
            }
            if (placedSupport.get() != null && !restarted.get()
                    && bot.getBlockPos().equals(start)
                    && task.state() == TaskState.RUNNING) {
                Map<String, String> checkpoint = task.checkpoint();
                int used = Integer.parseInt(checkpoint.get("budget_used"));
                task.cancel(bot, "gametest_support_restart");
                AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING,
                        "support placement checkpoint did not restart");
                require(context, Integer.parseInt(restored.checkpoint().get("budget_used")) >= used,
                        "support placement restart reset the expedition budget");
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("open-cave water return failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get(),
                    "open-cave ascent completed without restart boundary coverage");
            require(context, placedSupport.get() != null
                            && world.getBlockState(placedSupport.get()).isOf(Blocks.DIRT),
                    "open-cave ascent did not leave one factual support block");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0,
                    "open-cave ascent did not pay exactly one support block");
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                    "support-assisted ascent did not physically fill the bucket");
            require(context, bot.getBlockPos().getY() >= surfaceAnchor.getY(),
                    "support-assisted ascent filled water below the proven surface exit");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentOpenCaveLive", tickLimit = 300)
    public void multiLevelOpenCaveBuildsVanillaFoundationBridge(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(18, 4, 26));
        BlockPos surfaceAnchor = start.up(2);

        // The first rise has the chamber floor as a placement base. After the bot stands on that
        // one-block support, the second rise is an isolated-pillar case: both the next landing
        // support and its base are air. A real player must sneak-bridge the base first and then
        // place the landing support on top; direct placement is forbidden in strict survival.
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 5; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }

        String name = "WaterFoundationBridgeGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 4));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        context.runAtEveryTick(() -> {
            task.tick(bot);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("multi-level open-cave ascent failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
                return;
            }
            if (bot.getBlockPos().getY() < surfaceAnchor.getY()) {
                return;
            }

            BlockPos firstSupport = start.north();
            BlockPos bridgedFoundation = start;
            BlockPos secondSupport = start.up();
            require(context, world.getBlockState(firstSupport).isOf(Blocks.COBBLESTONE),
                    "first supported rise was not paid with a physical block");
            require(context, world.getBlockState(bridgedFoundation).isOf(Blocks.COBBLESTONE),
                    "isolated second rise did not sneak-bridge its foundation");
            require(context, world.getBlockState(secondSupport).isOf(Blocks.COBBLESTONE),
                    "isolated second rise did not place its landing support");
            require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 1,
                    "foundation bridge did not consume exactly three blocks");
            require(context, bot.getBlockPos().equals(surfaceAnchor),
                    "foundation bridge used an unexpected final landing: " + bot.getBlockPos());
            task.cancel(bot, "gametest_foundation_bridge_complete");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentShallowFluidLive", tickLimit = 40)
    public void shallowWaterAtFeetHandsReturnToPhysicalRescueBeforeAscentInspection(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        BlockPos surfaceAnchor = start.up(4);

        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            world.setBlockState(start.offset(direction),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(start.offset(direction).up(),
                    Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "WaterShallowFluidGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "shallow-water return stopped before rescue: " + task.failureReason());
        require(context, !bot.isSubmergedInWater()
                        && world.getFluidState(start).isIn(FluidTags.WATER),
                "fixture did not isolate feet-only water contact");
        require(context, NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                "feet-only water did not hand movement to the physical rescue controller");
        require(context, bot.getInventory().main.stream()
                        .filter(stack -> stack.isOf(Items.STONE_PICKAXE))
                        .allMatch(stack -> stack.getDamage() == 0),
                "ascent mined a hidden direction before handing off shallow water");
        NavSafetyNet.INSTANCE.clear(bot);
        task.cancel(bot, "gametest_shallow_water_handoff_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterReturnVisibleAquiferStrict", tickLimit = 40)
    public void dryReturnCollectsVisibleReachablePlainWaterWithoutMintingSurfaceProof(
            TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        BlockPos surfaceAnchor = start.up(8);
        BlockPos source = start.north().up(2);

        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(source.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(source, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterReturnAquiferGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.COMPLETED,
                "visible return aquifer did not complete: " + task.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "return aquifer moved before using the reachable source: " + bot.getBlockPos());
        require(context, InventoryAction.countItem(bot, Items.BUCKET) == 0
                        && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                "return aquifer did not perform exactly one vanilla bucket exchange");
        require(context, world.getFluidState(source).isEmpty(),
                "return aquifer source was not physically removed by the bucket interaction");
        Map<String, String> checkpoint = task.checkpoint();
        require(context, "DONE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit"))
                        && !checkpoint.containsKey("water_source")
                        && !checkpoint.containsKey("water_stand"),
                "direct return completion minted surface/approach authority: " + checkpoint);

        AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
        restored.start(bot);
        require(context, restored.state() == TaskState.COMPLETED,
                "completed return aquifer did not restore from the physical water bucket: "
                        + restored.failureReason());
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterReturnHiddenAquiferStrict", tickLimit = 40)
    public void returnDoesNotReadOrCollectAnOccludedPlainWaterSource(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        BlockPos surfaceAnchor = start.up(8);
        BlockPos screen = start.north().up();
        BlockPos source = start.north(2).up();

        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(screen, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(source.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(source, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterReturnHiddenAquiferGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);

        Map<String, String> checkpoint = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING
                        && "RETURN_SURFACE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit")),
                "occluded source changed the return phase: " + checkpoint);
        require(context, !checkpoint.containsKey("water_source")
                        && !checkpoint.containsKey("water_stand"),
                "occluded source minted an approach target: " + checkpoint);
        require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1
                        && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0
                        && world.getFluidState(source).isIn(FluidTags.WATER),
                "occluded source was read or collected through its solid screen");
        task.cancel(bot, "gametest_hidden_return_aquifer_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentFluidRelocationLive", tickLimit = 300)
    public void visibleFluidCeilingForcesSameLevelRelocationBeforeAscent(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(18, 4, 34));
        BlockPos relocation = start.north();
        BlockPos surfaceAnchor = relocation.east().up();

        for (int step = 0; step <= 2; step++) {
            BlockPos corridor = start.north(step);
            world.setBlockState(corridor.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(corridor,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(corridor.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(corridor.up(2),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            for (Direction direction : List.of(Direction.EAST, Direction.WEST)) {
                world.setBlockState(corridor.offset(direction),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(corridor.up().offset(direction),
                        Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // The natural riser is sideways from the relocation landing. This prevents the ordinary
        // walk controller from auto-jumping a stair before the relocation checkpoint is observed.
        world.setBlockState(surfaceAnchor.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(surfaceAnchor,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(surfaceAnchor.up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        // Keep one initial solid support face visible so the task first chooses an ascent candidate
        // and physically exposes the ceiling. Its head cell stays open; a two-block bedrock wall
        // would occlude the support and turn the fixture into an immediate relocation test.
        world.setBlockState(start.east(),
                Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.east().up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south(),
                Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south().up(),
                Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);

        BlockPos visibleCeiling = start.up(2);
        BlockPos fluidCeiling = start.up(3);
        world.setBlockState(visibleCeiling,
                // Glass is a real colliding occluder but drops no cobblestone into neighbouring
                // GameTest fixtures after it is mined.
                Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(fluidCeiling,
                Blocks.OAK_LEAVES.getDefaultState()
                        .with(Properties.PERSISTENT, true)
                        .with(Properties.WATERLOGGED, true),
                Block.NOTIFY_ALL);

        String name = "WaterFluidRelocationGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        AtomicReference<BlockPos> previous = new AtomicReference<>(start.toImmutable());
        AtomicBoolean exposedFluidCeiling = new AtomicBoolean();
        AtomicBoolean sawSameLevelRelocation = new AtomicBoolean();
        AtomicInteger movementCount = new AtomicInteger();
        task.start(bot);
        context.runAtEveryTick(() -> {
            task.tick(bot);
            require(context, world.getBlockState(fluidCeiling).isOf(Blocks.OAK_LEAVES)
                            && world.getFluidState(fluidCeiling).isIn(FluidTags.WATER),
                    "ascent mined or drained the visible fluid ceiling");
            if (world.getBlockState(visibleCeiling).isAir()) {
                exposedFluidCeiling.set(true);
            }

            BlockPos now = bot.getBlockPos();
            BlockPos before = previous.getAndSet(now.toImmutable());
            if (!now.equals(before)) {
                require(context, exposedFluidCeiling.get(),
                        "ascent moved before physically exposing the hidden fluid ceiling");
                int horizontal = Math.abs(now.getX() - before.getX())
                        + Math.abs(now.getZ() - before.getZ());
                int vertical = now.getY() - before.getY();
                require(context, horizontal == 1 && (vertical == 0 || vertical == 1),
                        "water-ceiling recovery used non-adjacent movement: " + before + " -> " + now);
                int movement = movementCount.getAndIncrement();
                if (movement == 0) {
                    require(context, vertical == 0,
                            "first recovery movement climbed before relocating: " + before + " -> " + now);
                    require(context, before.equals(start) && now.equals(relocation),
                            "first recovery movement did not use the only dry same-level route: " + now);
                    sawSameLevelRelocation.set(true);
                } else if (movement == 1) {
                    require(context, before.equals(relocation) && now.equals(surfaceAnchor)
                                    && vertical == 1,
                            "relocation did not immediately use the natural side riser: "
                                    + before + " -> " + now);
                } else {
                    context.throwGameTestException(
                            "fluid recovery made an unexpected extra movement: " + before + " -> " + now);
                }
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("fluid-ceiling recovery failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
                return;
            }
            if (now.getY() <= start.getY()) {
                return;
            }
            require(context, sawSameLevelRelocation.get(),
                    "ascent rose without first making a physical same-level relocation");
            require(context, exposedFluidCeiling.get(),
                    "ascent never physically exposed the waterlogged ceiling");
            require(context, movementCount.get() == 2,
                    "fluid recovery did not follow the exact start-relocate-rise sequence");
            require(context, now.equals(surfaceAnchor),
                    "ascent did not use the natural supported landing: " + now + " expected=" + surfaceAnchor);
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1,
                    "relocation consumed the empty bucket before reaching the surface");
            task.cancel(bot, "gametest_fluid_relocation_complete");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentCarvedRelocationStrict", tickLimit = 400)
    public void sharedFluidArcCarvesDrySameLevelPocketBeforeAscent(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(42, 4, 42));
        BlockPos relocation = start.east();
        BlockPos surfaceAnchor = relocation.east().up();
        BlockPos surfaceEdge = surfaceAnchor.east();

        // Surround the start with ordinary mine rock. There is no pre-open same-level landing:
        // after the shared ceiling exposes water, the only forward recovery is to mine the two
        // east relocation cells and prove their support before walking into the pocket.
        for (int dx = -2; dx <= 3; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        BlockPos sharedArc = start.up(2);
        BlockPos fluidCeiling = start.up(3);
        world.setBlockState(sharedArc, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(fluidCeiling,
                Blocks.OAK_LEAVES.getDefaultState()
                        .with(Properties.PERSISTENT, true)
                        .with(Properties.WATERLOGGED, true),
                Block.NOTIFY_ALL);

        // Once the east pocket has been physically carved, it has one normal supported riser and
        // a reusable surface edge. These cells are deliberately not reachable from the start until
        // both relocation obstructions have been removed.
        world.setBlockState(relocation.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos open : List.of(
                surfaceAnchor, surfaceAnchor.up(), surfaceEdge, surfaceEdge.up(), surfaceEdge.up(2))) {
            world.setBlockState(open, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "WaterCarvedRelocationGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(surfaceAnchor));
        AtomicReference<BlockPos> previous = new AtomicReference<>(start.toImmutable());
        AtomicBoolean exposedFluidCeiling = new AtomicBoolean();
        AtomicBoolean sawHeadClearedBeforeFeet = new AtomicBoolean();
        AtomicBoolean restartedAfterHead = new AtomicBoolean();
        AtomicInteger movementCount = new AtomicInteger();
        active.get().start(bot);
        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);

            require(context, world.getBlockState(fluidCeiling).isOf(Blocks.OAK_LEAVES)
                            && world.getFluidState(fluidCeiling).isIn(FluidTags.WATER),
                    "carved relocation mined or drained the waterlogged ceiling");
            require(context, !bot.isTouchingWater()
                            && world.getFluidState(bot.getBlockPos()).isEmpty()
                            && world.getFluidState(bot.getBlockPos().up()).isEmpty(),
                    "carved relocation entered water instead of routing around it");
            require(context, bot.getHealth() == bot.getMaxHealth()
                            && bot.getHungerManager().getFoodLevel() == 20,
                    "carved relocation lost strict-survival health or food");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0,
                    "carved relocation mutated the bucket before surface search");

            if (world.getBlockState(sharedArc).isAir()) {
                exposedFluidCeiling.set(true);
            }
            if (world.getBlockState(relocation.up()).isAir()
                    && !world.getBlockState(relocation).isAir()) {
                sawHeadClearedBeforeFeet.set(true);
                if (!restartedAfterHead.get()) {
                    Map<String, String> checkpoint = task.checkpoint();
                    int budgetBefore = Integer.parseInt(checkpoint.get("budget_used"));
                    require(context, "RETURN_SURFACE".equals(checkpoint.get("phase"))
                                    && "false".equals(checkpoint.get("surface_exit")),
                            "head-only carve checkpoint escaped the return-surface invariant");
                    task.cancel(bot, "gametest_carved_relocation_restart");
                    AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
                    restored.start(bot);
                    Map<String, String> after = restored.checkpoint();
                    require(context, restored.state() == TaskState.RUNNING
                                    && "RETURN_SURFACE".equals(after.get("phase"))
                                    && "false".equals(after.get("surface_exit"))
                                    && Integer.parseInt(after.get("budget_used")) >= budgetBefore,
                            "head-only carve checkpoint did not restart monotonically: " + after);
                    active.set(restored);
                    task = restored;
                    restartedAfterHead.set(true);
                }
            }
            if (world.getBlockState(relocation).isAir()) {
                require(context, sawHeadClearedBeforeFeet.get(),
                        "same-level pocket mined feet before exposing and rechecking its head");
            }

            BlockPos now = bot.getBlockPos();
            BlockPos before = previous.getAndSet(now.toImmutable());
            if (!now.equals(before)) {
                require(context, exposedFluidCeiling.get(),
                        "carved relocation moved before physically exposing the shared fluid arc");
                int horizontal = Math.abs(now.getX() - before.getX())
                        + Math.abs(now.getZ() - before.getZ());
                int vertical = now.getY() - before.getY();
                require(context, horizontal == 1 && (vertical == 0 || vertical == 1),
                        "carved relocation used non-adjacent movement: " + before + " -> " + now);
                int movement = movementCount.getAndIncrement();
                if (movement == 0) {
                    require(context, before.equals(start) && now.equals(relocation) && vertical == 0,
                            "first recovery movement did not enter the dry carved pocket: " + now);
                    require(context, world.getBlockState(relocation).isAir()
                                    && world.getBlockState(relocation.up()).isAir(),
                            "bot entered the relocation before both physical obstructions cleared");
                } else if (movement == 1) {
                    require(context, before.equals(relocation)
                                    && now.equals(surfaceAnchor) && vertical == 1,
                            "carved pocket did not continue through the supported riser: "
                                    + before + " -> " + now);
                } else {
                    context.throwGameTestException(
                            "carved relocation made an unexpected extra movement: "
                                    + before + " -> " + now);
                }
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("carved fluid relocation failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
                return;
            }
            if (!now.equals(surfaceAnchor)) {
                return;
            }
            require(context, movementCount.get() == 2 && sawHeadClearedBeforeFeet.get(),
                    "carved relocation did not complete the exact carve-walk-rise sequence");
            require(context, restartedAfterHead.get(),
                    "carved relocation completed without crossing the head-only restart boundary");
            task.cancel(bot, "gametest_carved_fluid_relocation_complete");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentPauseRelocationStrict", tickLimit = 500)
    public void reachedFluidRelocationSurvivesPauseAndSafetyDisplacement(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(18, 4, 18));
        BlockPos relocation = start.east();
        BlockPos safetyLanding = relocation.north();
        BlockPos surfaceAnchor = relocation.east().up();

        // The two waterlogged ceilings make ascent from start and safetyLanding unsafe. The first
        // is exposed by normal return work; the second is already visible from the safety landing,
        // so the first resumed task tick must reselect immediately instead of spending a generic
        // retry interval discovering the same local hazard. Both cells have exactly one dry
        // same-level exit: relocation. The supported east riser is usable only after that
        // relocation has been reached again.
        for (BlockPos cell : List.of(start, relocation, safetyLanding)) {
            world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos riserSupport = surfaceAnchor.down();
        world.setBlockState(riserSupport, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(surfaceAnchor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(surfaceAnchor.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos wall : List.of(
                start.west(), start.north(), start.south(),
                safetyLanding.north(), safetyLanding.east(), safetyLanding.west(),
                relocation.south())) {
            world.setBlockState(wall, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(wall.up(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }

        BlockPos startCeiling = start.up(2);
        BlockPos startWater = start.up(3);
        BlockPos safetyCeiling = safetyLanding.up(2);
        BlockPos safetyWater = safetyLanding.up(3);
        world.setBlockState(startCeiling, Blocks.GLASS.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(safetyCeiling, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos water : List.of(startWater, safetyWater)) {
            world.setBlockState(water,
                    Blocks.OAK_LEAVES.getDefaultState()
                            .with(Properties.PERSISTENT, true)
                            .with(Properties.WATERLOGGED, true),
                    Block.NOTIFY_ALL);
        }

        String name = "WaterPauseRelocationGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        for (PrivilegedCapability capability : PrivilegedCapability.values()) {
            require(context, !CapabilityRuntime.decide(
                            bot, capability, "water_pause_relocation_gametest").allowed(),
                    "strict_survival unexpectedly allowed " + capability);
        }

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        AtomicInteger stage = new AtomicInteger();
        AtomicReference<BlockPos> previous = new AtomicReference<>(start.toImmutable());
        AtomicBoolean retriedRelocation = new AtomicBoolean();
        task.start(bot);
        context.runAtEveryTick(() -> {
            require(context, world.getBlockState(startWater).isOf(Blocks.OAK_LEAVES)
                            && world.getFluidState(startWater).isIn(FluidTags.WATER)
                            && world.getBlockState(safetyWater).isOf(Blocks.OAK_LEAVES)
                            && world.getFluidState(safetyWater).isIn(FluidTags.WATER),
                    "pause/resume ascent mined or drained a waterlogged ceiling");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1
                            && InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0,
                    "pause/resume ascent mutated the bucket before surface search");

            BlockPos now = bot.getBlockPos();
            BlockPos before = previous.get();
            if (!now.equals(before)) {
                int horizontal = Math.abs(now.getX() - before.getX())
                        + Math.abs(now.getZ() - before.getZ());
                int vertical = now.getY() - before.getY();
                require(context, horizontal == 1 && (vertical == 0 || vertical == 1),
                        "pause relocation used non-adjacent movement: " + before + " -> " + now);
            }

            if (stage.get() == 0 && now.equals(relocation)) {
                require(context, before.equals(start),
                        "initial relocation did not use the unique dry exit: " + before + " -> " + now);
                require(context, world.getBlockState(startCeiling).isAir(),
                        "initial relocation moved before exposing the fluid ceiling");
                // Pause before the next AcquireWaterTask tick can settle the already reached target.
                task.pause(bot);
                require(context, task.state() == TaskState.PAUSED,
                        "reached relocation did not pause cleanly");
                var safetyMove = bot.getActionPack().startSurfacePathTo(safetyLanding);
                require(context, !safetyMove.isFailed(),
                        "fixture safety displacement was rejected: " + safetyMove.reason());
                stage.set(1);
                previous.set(now.toImmutable());
                return;
            }

            if (stage.get() == 1) {
                require(context, task.state() == TaskState.PAUSED,
                        "mission task ran while safety displacement owned movement");
                if (now.equals(safetyLanding)) {
                    require(context, before.equals(relocation),
                            "safety task did not make the expected adjacent displacement: "
                                    + before + " -> " + now);
                    bot.getActionPack().stopAll();
                    task.resume(bot);
                    require(context, task.state() == TaskState.RUNNING,
                            "mission task did not resume after safety displacement");
                    int beforeResumeTick = task.elapsedTicks();
                    task.tick(bot);
                    require(context, task.elapsedTicks() == beforeResumeTick + 1,
                            "resume did not execute exactly one immediate mission tick");
                    require(context, relocation.equals(bot.getActionPack().activePathGoal()),
                            "first tick after displacement did not immediately retry the factual "
                                    + "relocation: goal=" + bot.getActionPack().activePathGoal());
                    stage.set(2);
                }
                previous.set(now.toImmutable());
                return;
            }

            if (stage.get() == 2 && !now.equals(before)) {
                if (!retriedRelocation.get()) {
                    require(context, before.equals(safetyLanding) && now.equals(relocation)
                                    && now.getY() == before.getY(),
                            "resume did not retry the unique dry relocation: " + before + " -> " + now);
                    retriedRelocation.set(true);
                } else {
                    require(context, before.equals(relocation) && now.equals(surfaceAnchor)
                                    && now.getY() == before.getY() + 1,
                            "resumed relocation did not continue up the safe riser: "
                                    + before + " -> " + now);
                }
            }
            previous.set(now.toImmutable());

            if (now.equals(surfaceAnchor)) {
                require(context, retriedRelocation.get(),
                        "ascent reached the riser without retrying the interrupted relocation");
                task.cancel(bot, "gametest_pause_relocation_complete");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
                return;
            }

            task.tick(bot);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("pause relocation recovery failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentPauseSettlementStrict", tickLimit = 200)
    public void reachedRelocationPauseWithoutDisplacementSettlesBeforeResume(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(18, 4, 18));
        BlockPos relocation = start.east();
        BlockPos surfaceAnchor = relocation.east().up();

        for (BlockPos cell : List.of(start, relocation)) {
            world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        // An unbreakable common jump arc makes every rise from start invalid, leaving relocation
        // as the only dry same-level progress. Once there, the supported east riser is unique.
        world.setBlockState(start.up(2), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        for (BlockPos wall : List.of(start.west(), start.north(), start.south())) {
            world.setBlockState(wall, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(wall.up(), Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(surfaceAnchor.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(surfaceAnchor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(surfaceAnchor.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(relocation.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterPauseSettlementGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        AtomicInteger stage = new AtomicInteger();
        AtomicReference<BlockPos> previous = new AtomicReference<>(start.toImmutable());
        task.start(bot);
        context.runAtEveryTick(() -> {
            BlockPos now = bot.getBlockPos();
            BlockPos before = previous.get();
            if (stage.get() == 0 && now.equals(relocation)) {
                require(context, before.equals(start),
                        "initial settlement relocation was not the unique dry step: "
                                + before + " -> " + now);
                task.pause(bot);
                require(context, task.state() == TaskState.PAUSED,
                        "reached relocation did not enter PAUSED");
                task.resume(bot);
                require(context, task.state() == TaskState.RUNNING,
                        "undisplaced relocation did not resume");
                int beforeResumeTick = task.elapsedTicks();
                task.tick(bot);
                require(context, task.elapsedTicks() == beforeResumeTick + 1,
                        "undisplaced resume did not execute exactly one task tick");
                require(context, surfaceAnchor.equals(bot.getActionPack().activePathGoal()),
                        "settled relocation was retried instead of continuing up the riser: goal="
                                + bot.getActionPack().activePathGoal());
                stage.set(1);
                previous.set(now.toImmutable());
                return;
            }

            if (stage.get() == 1 && now.equals(surfaceAnchor)) {
                require(context, before.equals(relocation),
                        "settled ascent skipped the adjacent riser: " + before + " -> " + now);
                task.cancel(bot, "gametest_pause_settlement_complete");
                AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
                context.complete();
                return;
            }

            previous.set(now.toImmutable());
            task.tick(bot);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("undisplaced pause settlement failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchPauseCooldownStrict", tickLimit = 80)
    public void displacedSearchRetriesOnItsFirstResumeTick(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterSearchPauseCooldownGT", false);
        AIPlayerEntity bot = fixture.bot();
        BlockPos waypoint = fixture.start().east(12);
        BlockPos safetyLanding = fixture.start().north();
        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), searchCheckpoint(
                fixture.start(), waypoint, 380, 0, 1, 0));
        task.start(bot);
        task.tick(bot);
        require(context, bot.getActionPack().activePathGoal() != null,
                "fixture did not seed a live SEARCH path retry");
        task.pause(bot);
        require(context, task.state() == TaskState.PAUSED,
                "SEARCH path did not pause before safety displacement");
        var safetyMove = bot.getActionPack().startSurfacePathTo(safetyLanding);
        require(context, !safetyMove.isFailed(),
                "fixture safety displacement was rejected: " + safetyMove.reason());

        context.runAtEveryTick(() -> {
            require(context, task.state() == TaskState.PAUSED,
                    "SEARCH task ran while safety movement owned the bot");
            if (!bot.getBlockPos().equals(safetyLanding)) {
                return;
            }
            bot.getActionPack().stopAll();
            task.resume(bot);
            int beforeResumeTick = task.elapsedTicks();
            task.tick(bot);
            require(context, task.elapsedTicks() == beforeResumeTick + 1,
                    "SEARCH resume did not execute exactly one immediate task tick");
            require(context, bot.getActionPack().activePathGoal() != null
                            && !safetyLanding.equals(bot.getActionPack().activePathGoal()),
                    "first SEARCH tick inherited the pre-safety retry cooldown: goal="
                            + bot.getActionPack().activePathGoal());
            require(context, "0".equals(task.checkpoint().get("path_attempts")),
                    "displaced SEARCH retained position-dependent path failures: "
                            + task.checkpoint());
            task.cancel(bot, "gametest_search_pause_cooldown_complete");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), fixture.name());
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterReturnTeardownStrict", tickLimit = 40)
    public void surfaceTransitionCancelsPausedAscentCraftAndMotion(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(18, 4, 18));
        BlockPos surfaceAnchor = start.up(2);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        BlockPos exit = start.east(4).up(2);
        BlockPos skyEdge = exit.east();
        for (BlockPos cell : List.of(exit, skyEdge)) {
            world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "WaterReturnTeardownGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        ItemStack exhausted = new ItemStack(Items.STONE_PICKAXE);
        exhausted.setDamage(exhausted.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, exhausted);

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);
        require(context, task.hasReturnSurfaceWorkForTesting(),
                "fixture did not start the local ascent-tool craft");
        task.pause(bot);
        require(context, task.hasReturnSurfaceWorkForTesting(),
                "pause incorrectly discarded the resumable ascent-tool craft");
        bot.teleport(world, exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        task.resume(bot);
        require(context, task.hasReturnSurfaceWorkForTesting(),
                "resume lost the paused craft before the phase boundary could settle it");
        task.tick(bot);

        require(context, "SEARCH".equals(task.checkpoint().get("phase"))
                        && "true".equals(task.checkpoint().get("surface_exit"))
                        && encode(exit).equals(task.checkpoint().get("search_origin")),
                "surface displacement did not publish the factual SEARCH boundary: "
                        + task.checkpoint());
        require(context, !task.hasReturnSurfaceWorkForTesting(),
                "SEARCH retained orphan RETURN_SURFACE mining/crafting/relocation work");
        require(context, bot.getActionPack().isPathExecutorIdle(),
                "SEARCH transition retained RETURN_SURFACE movement ownership");
        task.cancel(bot, "gametest_return_teardown_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAcquisitionLive", tickLimit = 1400)
    public void deepMineCarvesRestartableStairBackToSurfaceWater(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(32, 4, 32));
        BlockPos surfaceAnchor = start.up(8);

        // A solid mound reproduces the post-iron state: the bot is in a two-high chamber eight
        // blocks below its original surface anchor, so a plain walk path cannot reach the water.
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 7; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (int dy = 8; dy <= 10; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        // Recess the source into the stone floor so it stays a single still source and remains
        // reachable from the open surface without creating a spreading-water rescue shortcut.
        BlockPos water = surfaceAnchor.east().down();
        world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterMineReturnGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(surfaceAnchor));
        AtomicReference<BlockPos> previous = new AtomicReference<>(start.toImmutable());
        AtomicBoolean restarted = new AtomicBoolean();
        active.get().start(bot);
        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);
            BlockPos now = bot.getBlockPos();
            BlockPos before = previous.getAndSet(now.toImmutable());
            require(context, Math.abs(now.getX() - before.getX()) <= 1
                            && Math.abs(now.getY() - before.getY()) <= 1
                            && Math.abs(now.getZ() - before.getZ()) <= 1,
                    "surface return used a non-adjacent movement: " + before + " -> " + now);

            if (!restarted.get() && now.getY() >= start.getY() + 2
                    && task.state() == TaskState.RUNNING) {
                Map<String, String> checkpoint = task.checkpoint();
                int used = Integer.parseInt(checkpoint.get("budget_used"));
                task.cancel(bot, "gametest_ascent_restart");
                AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING,
                        "deep return checkpoint did not restart");
                require(context, Integer.parseInt(restored.checkpoint().get("budget_used")) >= used,
                        "deep return restart reset its elapsed budget");
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("deep water return failed: " + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, restarted.get(), "deep return completed without restart coverage");
            require(context, bot.getEyePos().squaredDistanceTo(water.toCenterPos())
                            <= bot.getBlockInteractionRange() * bot.getBlockInteractionRange(),
                    "deep return filled water outside vanilla interaction reach: bot="
                            + now + " source=" + water);
            require(context, "DONE".equals(task.checkpoint().get("phase"))
                            && "false".equals(task.checkpoint().get("surface_exit")),
                    "reachable return water minted a false surface-search latch: "
                            + task.checkpoint());
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                    "deep return did not end with one water bucket");
            require(context, !world.getFluidState(water).isIn(FluidTags.WATER),
                    "deep return did not drain the physical source");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentToolCraftLive", tickLimit = 500)
    public void deepMineLocallyCraftsAStonePickWhenAscentToolsAreExhausted(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(32, 4, 20));
        BlockPos surfaceAnchor = start.up(2);
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (int dy = 2; dy <= 4; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos copperObstruction = start.north().up();
        world.setBlockState(copperObstruction,
                Blocks.COPPER_ORE.getDefaultState(), Block.NOTIFY_ALL);
        // Keep the source physically recessed and contained. Placing it beside the shaft lets
        // flowing water invalidate every ascent candidate, so the bot can water-rescue around the
        // copper instead of proving that the replenished pick actually clears the obstruction.
        BlockPos water = surfaceAnchor.east(2).down();
        world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterToolCraftGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE));
        InventoryAction.giveItem(bot, new ItemStack(Items.COBBLESTONE, 3));
        InventoryAction.giveItem(bot, new ItemStack(Items.STICK, 2));
        for (int i = 0; i < 2; i++) {
            ItemStack exhausted = new ItemStack(Items.STONE_PICKAXE);
            exhausted.setDamage(exhausted.getMaxDamage() - 1);
            InventoryAction.giveItem(bot, exhausted);
        }

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        AtomicBoolean crafted = new AtomicBoolean();
        task.start(bot);
        context.runAtEveryTick(() -> {
            task.tick(bot);
            if (InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 3) {
                crafted.set(true);
                require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == 0
                                && InventoryAction.countItem(bot, Items.STICK) == 0,
                        "ascent pick was not paid for with three cobblestone and two sticks");
            }
            if (!crafted.get() && task.elapsedTicks() > 20) {
                context.throwGameTestException(
                        "ascent did not replenish exhausted picks before mining the copper obstruction");
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("tool-depleted water return failed: "
                        + task.failureReason() + " checkpoint=" + task.checkpoint());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, crafted.get(), "water return completed without exercising local tool craft");
            require(context, world.getBlockState(copperObstruction).isAir(),
                    "freshly crafted stone pick did not physically clear the copper obstruction");
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                    "tool-replenished return did not physically fill the bucket");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAscentToolMissingLive", tickLimit = 100)
    public void deepMineFailsFastWithoutStonePickMaterialsAndPreservesIron(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(48, 4, 20));
        BlockPos surfaceAnchor = start.up(2);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                }
                for (int dy = 2; dy <= 3; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        // Leave exactly one safe ascent support so the tested obstruction is deterministic.
        world.setBlockState(start.east(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.south(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.west(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos copperObstruction = start.north().up();
        world.setBlockState(copperObstruction,
                Blocks.COPPER_ORE.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterToolMissingGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.IRON_PICKAXE));
        ItemStack exhausted = new ItemStack(Items.STONE_PICKAXE);
        exhausted.setDamage(exhausted.getMaxDamage() - 1);
        InventoryAction.giveItem(bot, exhausted);

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        context.runAtEveryTick(() -> {
            task.tick(bot);
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("missing-material ascent did not fail: " + task.state());
                return;
            }
            if (task.state() != TaskState.FAILED) {
                if (task.elapsedTicks() > 20) {
                    context.throwGameTestException(
                            "missing-material ascent did not return a typed failure within 20 ticks");
                }
                return;
            }
            require(context, task.failureReason().startsWith(
                            "acquire_water_ascent_tool_unavailable:minecraft:stone_pickaxe:"),
                    "unexpected missing-tool reason: " + task.failureReason());
            require(context, world.getBlockState(copperObstruction).isOf(Blocks.COPPER_ORE),
                    "missing stone-pick materials still changed the copper obstruction");
            require(context, InventoryAction.countItem(bot, Items.BUCKET) == 1,
                    "missing-tool failure consumed the empty bucket");
            require(context, InventoryAction.countItem(bot, Items.IRON_PICKAXE) == 1,
                    "return ascent spent the reserved iron pick on a low-tier obstruction");
            require(context, InventoryAction.countItem(bot, Items.STONE_PICKAXE) == 1,
                    "missing-tool failure changed the exhausted stone pick");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterAcquisitionLive", tickLimit = 300)
    public void skyVisibleExitStartsSearchWithoutReenteringTheMinedStair(TestContext context) {
        var world = context.getWorld();
        BlockPos exit = context.getAbsolutePos(new BlockPos(32, 10, 32));
        BlockPos surfaceAnchor = exit.west(4).down(4);

        // Open platform around the real exit.  The old anchor is connected by a perfectly valid
        // descending stair, reproducing the seed-3000 failure: a generic path back to the anchor
        // eagerly re-entered that stair after the bot had already reached open sky.
        for (int dx = -5; dx <= 18; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(exit.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(exit.add(dx, 0, dz),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(exit.add(dx, 1, dz),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        for (int step = 1; step <= 4; step++) {
            BlockPos stair = exit.west(step).down(step);
            world.setBlockState(stair.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(stair, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(stair.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        // The source is outside the initial observation and hidden by a one-block rim. The first
        // +12 waypoint must therefore be derived from the checkpointed exit, not the old anchor.
        // This covers the non-ideal branch that failed in the real seed: no water is beside exit.
        BlockPos water = exit.east(13);
        world.setBlockState(water.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water.south(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterSurfaceLatchGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(exit),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(surfaceAnchor));
        active.get().start(bot);
        AtomicBoolean observedExitCheckpoint = new AtomicBoolean();
        AtomicBoolean restarted = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();
            if ("true".equals(checkpoint.get("surface_exit"))) {
                observedExitCheckpoint.set(true);
                require(context, encode(exit).equals(checkpoint.get("search_origin")),
                        "surface search was not rebased at the physical exit: " + checkpoint);
            }
            require(context, bot.getBlockPos().getY() >= exit.getY(),
                    "surface exit was abandoned for the old stair: " + bot.getBlockPos());
            if (!restarted.get() && "true".equals(checkpoint.get("surface_exit"))) {
                int used = Integer.parseInt(checkpoint.get("budget_used"));
                task.cancel(bot, "gametest_surface_exit_restart");
                AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
                restored.start(bot);
                Map<String, String> after = restored.checkpoint();
                require(context, restored.state() == TaskState.RUNNING,
                        "surface-exit checkpoint did not restart");
                require(context, "SEARCH".equals(after.get("phase"))
                                && "true".equals(after.get("surface_exit"))
                                && encode(exit).equals(after.get("search_origin"))
                                && Integer.parseInt(after.get("budget_used")) >= used,
                        "surface-exit latch changed across restart: before=" + checkpoint
                                + " after=" + after);
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("surface-exit latch failed: " + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1,
                    "surface-exit search did not physically fill the bucket");
            require(context, observedExitCheckpoint.get(),
                    "surface-exit latch was never persisted");
            require(context, restarted.get(),
                    "surface-exit search completed without checkpoint restart coverage");
            require(context, "DONE".equals(task.checkpoint().get("phase")),
                    "surface-exit completion did not persist the terminal phase");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterOverhangExitStrict", tickLimit = 40)
    public void dryOverhangFootingUsesItsObservableSkyEdgeAsSurfaceExit(TestContext context) {
        var world = context.getWorld();
        // Keep every mutated cell inside the tiny EMPTY_STRUCTURE footprint. A far local offset
        // can overlap a concurrently placed GameTest structure and make the roof nondeterministic.
        BlockPos exit = context.getAbsolutePos(new BlockPos(1, 4, 1));
        BlockPos skyEdge = exit.east();
        BlockPos surfaceAnchor = exit.west(8).down(3);

        world.setBlockState(exit.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(exit, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(exit.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        // One remaining shelter/terrain block keeps the bot's own column non-sky-visible while the
        // adjacent dry edge is a factual, observable route onto the open surface.
        world.setBlockState(exit.up(2), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(skyEdge.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(skyEdge, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(skyEdge.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterOverhangExitGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(exit),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));

        // Sky light/heightmap propagation is ticked. Assert the roof only after the world has had
        // time to publish the block update; synchronous checks are order-dependent in full suites.
        context.runAtTick(2, () -> {
            require(context, !world.isSkyVisible(exit),
                    "fixture footing unexpectedly sees sky");
            require(context, world.isSkyVisible(skyEdge),
                    "fixture outward edge does not see sky");

            Map<String, String> exhaustedAnchorRoute = new LinkedHashMap<>(
                    returnSurfaceCheckpoint(surfaceAnchor, 200, 3));
            AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor, exhaustedAnchorRoute);
            task.start(bot);
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();
            require(context, task.state() == TaskState.RUNNING,
                    "overhang exit stopped unexpectedly: " + task.failureReason());
            require(context, "SEARCH".equals(checkpoint.get("phase"))
                            && "true".equals(checkpoint.get("surface_exit"))
                            && encode(exit).equals(checkpoint.get("search_origin"))
                            && "0".equals(checkpoint.get("path_attempts")),
                    "observable sky edge did not publish one durable surface exit: " + checkpoint);

            task.cancel(bot, "gametest_overhang_exit_restart");
            AcquireWaterTask restored = new AcquireWaterTask(surfaceAnchor, checkpoint);
            restored.start(bot);
            Map<String, String> after = restored.checkpoint();
            require(context, restored.state() == TaskState.RUNNING
                            && "SEARCH".equals(after.get("phase"))
                            && "true".equals(after.get("surface_exit"))
                            && encode(exit).equals(after.get("search_origin"))
                            && "0".equals(after.get("path_attempts")),
                    "overhang surface latch changed across restart: before=" + checkpoint
                            + " after=" + after);
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchLivenessLive", tickLimit = 100)
    public void oscillationCannotResetWaypointBudgetAcrossRestart(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterOscillationBudgetGT", false);
        AIPlayerEntity bot = fixture.bot();
        BlockPos waypoint = fixture.start().east(12);
        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(new AcquireWaterTask(
                fixture.start(), searchCheckpoint(fixture.start(), waypoint,
                380, 0, 1, 0)));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicInteger ticks = new AtomicInteger();
        active.get().start(bot);

        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            int tick = ticks.getAndIncrement();
            BlockPos forced = (tick & 1) == 0 ? fixture.start() : fixture.start().north();
            bot.getActionPack().stopAll();
            bot.teleport(bot.getServerWorld(),
                    forced.getX() + 0.5D, forced.getY(), forced.getZ() + 0.5D,
                    Set.of(), 0.0F, 0.0F, true);
            task.tick(bot);
            Map<String, String> checkpoint = task.checkpoint();

            if (!restarted.get()
                    && Integer.parseInt(checkpoint.get("budget_used")) >= 390
                    && checkpoint.containsKey("waypoint")) {
                require(context, "0".equals(checkpoint.get("waypoint_started_budget")),
                        "movement renewed the absolute waypoint deadline before restart: " + checkpoint);
                task.cancel(bot, "gametest_waypoint_budget_restart");
                AcquireWaterTask restored = new AcquireWaterTask(fixture.start(), checkpoint);
                restored.start(bot);
                require(context, restored.state() == TaskState.RUNNING,
                        "valid liveness checkpoint did not restart: " + restored.failureReason());
                require(context, "0".equals(restored.checkpoint().get("waypoint_started_budget")),
                        "restart renewed the absolute waypoint deadline");
                active.set(restored);
                restarted.set(true);
                return;
            }

            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("oscillation budget ended unexpectedly: "
                        + task.state() + ":" + task.failureReason() + " checkpoint=" + checkpoint);
                return;
            }
            if (encode(waypoint).equals(checkpoint.get("waypoint"))) {
                if (Integer.parseInt(checkpoint.get("budget_used")) > 400) {
                    context.throwGameTestException(
                            "oscillation extended the waypoint beyond its absolute budget: " + checkpoint);
                }
                return;
            }
            require(context, restarted.get(), "waypoint expired without exercising restart");
            require(context, "1".equals(checkpoint.get("issued"))
                            && "0".equals(checkpoint.get("reached")),
                    "unreached waypoint was reported as an observation: " + checkpoint);
            require(context, Integer.parseInt(checkpoint.get("budget_used")) <= 400,
                    "waypoint deadline was renewed by oscillation/restart: " + checkpoint);
            cleanupDry(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchExpansionLive", tickLimit = 600)
    public void searchContinuesPastHundredAndPhysicallyFillsAfterRestart(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(36, 4, 36));
        for (int lateral = -3; lateral <= 3; lateral++) {
            for (int distance = -3; distance <= 31; distance++) {
                BlockPos cell = start.add(lateral, 0, -distance);
                world.setBlockState(cell.down(),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(cell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // The first expanded waypoint is 12 blocks north. Keep the well beyond the 16-block
        // perception radius so the task must publish and physically traverse waypoint 101 first.
        // Point 102 is 24 blocks north; make its exact resolved goal the south rim of the well,
        // matching the production contract that an observation post may be one block above the
        // nominal fixed-Y waypoint. From point 101 the rim still occludes the source.
        BlockPos water = start.north(25);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            world.setBlockState(water.offset(direction),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        String name = "WaterPastHundredGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        // Exact cursor from the 2026-07-17 seed-3000 artifact.  At point 100 the old code failed
        // here; schema 4 must issue point 101 at grid=(-5,4), 12 physical blocks north.
        BlockPos searchOrigin = start.add(60, 0, -60);
        Map<String, String> boundary = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, start, 10_114, 10_114, 100, 81));
        boundary.put("direction", "3");
        boundary.put("leg_length", "10");
        boundary.put("step_in_leg", "0");
        boundary.put("repeated_legs", "1");
        boundary.put("grid_x", "-5");
        boundary.put("grid_z", "5");
        boundary.remove("waypoint");

        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(searchOrigin, boundary));
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicBoolean crossedHundred = new AtomicBoolean();
        AtomicInteger farthestNorth = new AtomicInteger();
        active.get().start(bot);
        require(context, active.get().state() == TaskState.RUNNING,
                "schema-4 point-100 boundary was treated as the legacy terminal");

        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            task.tick(bot);
            farthestNorth.accumulateAndGet(start.getZ() - bot.getBlockPos().getZ(), Math::max);
            Map<String, String> checkpoint = task.checkpoint();
            int issued = Integer.parseInt(checkpoint.get("issued"));
            crossedHundred.set(crossedHundred.get() || issued > 100);

            if (!restarted.get() && issued == 101) {
                require(context, "-5".equals(checkpoint.get("grid_x"))
                                && "4".equals(checkpoint.get("grid_z"))
                                && encode(start.north(12)).equals(checkpoint.get("waypoint")),
                        "point 101 did not continue the factual seed-3000 cursor: " + checkpoint);
                require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 0,
                        "water was acquired before the expanded route moved");
                task.cancel(bot, "gametest_point_101_restart");
                AcquireWaterTask restored = new AcquireWaterTask(searchOrigin, checkpoint);
                restored.start(bot);
                Map<String, String> after = restored.checkpoint();
                require(context, restored.state() == TaskState.RUNNING
                                && "101".equals(after.get("issued"))
                                && "-5".equals(after.get("grid_x"))
                                && "4".equals(after.get("grid_z"))
                                && java.util.Objects.equals(
                                checkpoint.get("waypoint"), after.get("waypoint"))
                                && Integer.parseInt(after.get("budget_used"))
                                >= Integer.parseInt(checkpoint.get("budget_used")),
                        "point-101 restart changed cursor or budget authority: before="
                                + checkpoint + " after=" + after);
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("expanded water search failed: "
                        + task.failureReason() + " checkpoint=" + checkpoint);
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, crossedHundred.get() && restarted.get(),
                    "expanded search completed without point-101 restart coverage");
            require(context, farthestNorth.get() >= 24,
                    "bot did not physically traverse to the distant well: north="
                            + farthestNorth.get());
            require(context, InventoryAction.countItem(bot, Items.WATER_BUCKET) == 1
                            && InventoryAction.countItem(bot, Items.BUCKET) == 0,
                    "expanded route did not complete the vanilla bucket exchange");
            require(context, world.getFluidState(water).isEmpty(),
                    "expanded route did not drain the factual source block");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchExpansionLive", tickLimit = 20)
    public void legacyHundredTerminalStaysTerminalAcrossSchemaFourRestore(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterLegacyHundredGT", false);
        Map<String, String> legacy = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), fixture.start(), 10_114, 10_114, 100, 81));
        legacy.put("schema", "3");
        legacy.put("direction", "3");
        legacy.put("leg_length", "10");
        legacy.put("step_in_leg", "0");
        legacy.put("repeated_legs", "1");
        legacy.put("grid_x", "-5");
        legacy.put("grid_z", "5");
        legacy.remove("waypoint");
        legacy.remove("waypoint_limit");
        legacy.remove("budget_limit");

        AcquireWaterTask first = new AcquireWaterTask(fixture.start(), legacy);
        first.start(fixture.bot());
        Map<String, String> migrated = first.checkpoint();
        require(context, first.state() == TaskState.FAILED
                        && first.failureReason().startsWith("acquire_water_search_exhausted")
                        && "4".equals(migrated.get("schema"))
                        && "100".equals(migrated.get("waypoint_limit"))
                        && "12000".equals(migrated.get("budget_limit"))
                        && "100".equals(migrated.get("issued"))
                        && !migrated.containsKey("waypoint"),
                "legacy point-100 terminal gained new search authority: "
                        + first.state() + ":" + first.failureReason()
                        + " checkpoint=" + migrated);

        AcquireWaterTask second = new AcquireWaterTask(fixture.start(), migrated);
        second.start(fixture.bot());
        require(context, second.state() == TaskState.FAILED
                        && second.failureReason().startsWith("acquire_water_search_exhausted")
                        && "100".equals(second.checkpoint().get("waypoint_limit")),
                "migrated legacy point-100 terminal restarted as a fresh expedition");
        cleanupDry(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchExpansionLive", tickLimit = 20)
    public void currentCursorEndsAfterCompleteSeventhRing(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterSeventhRingGT", false);
        // Cursor 223 is grid=(6,-7). Rebase it onto the physical dry fixture so issuing point 224
        // asks for one ordinary 12-block eastward path instead of a synthetic long-distance jump.
        BlockPos searchOrigin = fixture.start().add(-72, 0, 84);
        Map<String, String> cursor223 = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, fixture.start(), 200, 180, 223, 200));
        cursor223.put("direction", "0");
        cursor223.put("leg_length", "15");
        cursor223.put("step_in_leg", "13");
        cursor223.put("repeated_legs", "0");
        cursor223.put("grid_x", "6");
        cursor223.put("grid_z", "-7");
        cursor223.remove("waypoint");

        AcquireWaterTask active = new AcquireWaterTask(searchOrigin, cursor223);
        active.start(fixture.bot());
        active.tick(fixture.bot());
        Map<String, String> point224 = active.checkpoint();
        require(context, active.state() == TaskState.RUNNING
                        && "224".equals(point224.get("issued"))
                        && "7".equals(point224.get("grid_x"))
                        && "-7".equals(point224.get("grid_z"))
                        && "0".equals(point224.get("direction"))
                        && "15".equals(point224.get("leg_length"))
                        && "14".equals(point224.get("step_in_leg"))
                        && encode(fixture.start().east(12)).equals(point224.get("waypoint")),
                "point 224 did not close the complete seventh ring: " + point224);

        active.cancel(fixture.bot(), "gametest_seventh_ring_terminal");
        Map<String, String> terminal = new LinkedHashMap<>(point224);
        terminal.remove("waypoint");
        terminal.put("path_attempts", "0");
        AcquireWaterTask exhausted = new AcquireWaterTask(searchOrigin, terminal);
        exhausted.start(fixture.bot());
        require(context, exhausted.state() == TaskState.FAILED
                        && exhausted.failureReason().startsWith(
                        "acquire_water_search_exhausted")
                        && "224".equals(exhausted.checkpoint().get("issued")),
                "current cursor issued a point beyond its complete seventh ring");
        cleanupDry(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchLivenessLive", tickLimit = 20)
    public void restoredHardBudgetRemainsTypedAndDecoderValid(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterHardBudgetGT", false);
        BlockPos waypoint = fixture.start().east(12);
        AcquireWaterTask exhausted = new AcquireWaterTask(fixture.start(), searchCheckpoint(
                fixture.start(), waypoint, 30_000, 29_600, 1, 0));
        exhausted.start(fixture.bot());

        require(context, exhausted.state() == TaskState.FAILED,
                "restored hard budget received a fresh running window");
        require(context, exhausted.failureReason().startsWith("acquire_water_timeout"),
                "unexpected restored hard-budget failure: " + exhausted.failureReason());
        Map<String, String> terminal = exhausted.checkpoint();
        require(context, "30000".equals(terminal.get("budget_used"))
                        && "29600".equals(terminal.get("waypoint_started_budget"))
                        && "30000".equals(terminal.get("budget_limit"))
                        && "224".equals(terminal.get("waypoint_limit")),
                "terminal budget was not clamped into its own decoder domain: " + terminal);

        AcquireWaterTask restoredAgain = new AcquireWaterTask(fixture.start(), terminal);
        restoredAgain.start(fixture.bot());
        require(context, restoredAgain.state() == TaskState.FAILED
                        && restoredAgain.failureReason().startsWith("acquire_water_timeout"),
                "terminal checkpoint became invalid/fresh on second restore: "
                        + restoredAgain.state() + ":" + restoredAgain.failureReason());
        require(context, "30000".equals(restoredAgain.checkpoint().get("budget_used")),
                "second terminal restore lost the exhausted hard budget");

        // Schema 3 owned the former 12,000-tick authority. Upgrading the binary must not grant its
        // already exhausted checkpoint the current 30,000-tick window.
        Map<String, String> previous = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), waypoint, 12_000, 11_600, 1, 0));
        previous.put("schema", "3");
        previous.remove("waypoint_limit");
        previous.remove("budget_limit");
        AcquireWaterTask previousTerminal = new AcquireWaterTask(fixture.start(), previous);
        previousTerminal.start(fixture.bot());
        Map<String, String> previousMigrated = previousTerminal.checkpoint();
        require(context, previousTerminal.state() == TaskState.FAILED
                        && previousTerminal.failureReason().startsWith("acquire_water_timeout")
                        && "4".equals(previousMigrated.get("schema"))
                        && "12000".equals(previousMigrated.get("budget_used"))
                        && "12000".equals(previousMigrated.get("budget_limit"))
                        && "100".equals(previousMigrated.get("waypoint_limit")),
                "schema-3 hard terminal gained current authority: "
                        + previousTerminal.state() + ":" + previousTerminal.failureReason()
                        + " checkpoint=" + previousMigrated);
        AcquireWaterTask previousAgain = new AcquireWaterTask(
                fixture.start(), previousMigrated);
        previousAgain.start(fixture.bot());
        require(context, previousAgain.state() == TaskState.FAILED
                        && previousAgain.failureReason().startsWith("acquire_water_timeout")
                        && "12000".equals(previousAgain.checkpoint().get("budget_limit")),
                "migrated schema-3 hard terminal restarted with fresh authority");

        // Schema 2 wrote the first exhausted task at LEGACY_MAX_ELAPSED+1. Migrate that exact
        // historical boundary once, retaining its authority inside a schema-4 checkpoint.
        Map<String, String> legacy = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), waypoint, 12_000, 12_000, 1, 0));
        legacy.put("schema", "2");
        legacy.put("budget_used", "12001");
        legacy.put("visited", legacy.remove("issued"));
        legacy.remove("reached");
        legacy.remove("waypoint_limit");
        legacy.remove("budget_limit");
        legacy.remove("waypoint_started_budget");
        legacy.remove("consecutive_unreachable");
        AcquireWaterTask migrated = new AcquireWaterTask(fixture.start(), legacy);
        migrated.start(fixture.bot());
        require(context, migrated.state() == TaskState.FAILED
                        && migrated.failureReason().startsWith("acquire_water_timeout")
                        && "4".equals(migrated.checkpoint().get("schema"))
                        && "true".equals(migrated.checkpoint().get("surface_exit"))
                        && "12000".equals(migrated.checkpoint().get("budget_used"))
                        && "12000".equals(migrated.checkpoint().get("budget_limit"))
                        && "100".equals(migrated.checkpoint().get("waypoint_limit")),
                "legacy MAX+1 terminal checkpoint did not migrate fail-closed: "
                        + migrated.state() + ":" + migrated.failureReason()
                        + " checkpoint=" + migrated.checkpoint());
        cleanupDry(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchLivenessLive", tickLimit = 240)
    public void sealedSurfaceFailsTypedWithoutBurningCursor(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterSealedRouteGT", true);
        BlockPos firstWaypoint = fixture.start().east(12);
        AtomicReference<AcquireWaterTask> active = new AtomicReference<>(
                new AcquireWaterTask(fixture.start(), searchCheckpoint(
                        fixture.start(), firstWaypoint, 0, 0, 1, 0)));
        AtomicBoolean restarted = new AtomicBoolean();
        active.get().start(fixture.bot());

        context.runAtEveryTick(() -> {
            AcquireWaterTask task = active.get();
            task.tick(fixture.bot());
            Map<String, String> checkpoint = task.checkpoint();
            if (!restarted.get() && task.state() == TaskState.RUNNING
                    && "1".equals(checkpoint.get("consecutive_unreachable"))) {
                String anchor = checkpoint.get("unreachable_anchor");
                task.cancel(fixture.bot(), "gametest_unreachable_restart");
                AcquireWaterTask restored = new AcquireWaterTask(fixture.start(), checkpoint);
                restored.start(fixture.bot());
                Map<String, String> after = restored.checkpoint();
                require(context, restored.state() == TaskState.RUNNING
                                && "1".equals(after.get("consecutive_unreachable"))
                                && java.util.Objects.equals(anchor, after.get("unreachable_anchor")),
                        "restart erased the same-area unreachable ledger: before="
                                + checkpoint + " after=" + after);
                active.set(restored);
                restarted.set(true);
                return;
            }
            if (task.state() == TaskState.COMPLETED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("sealed route ended unexpectedly: " + task.state());
                return;
            }
            if (task.state() != TaskState.FAILED) {
                return;
            }
            require(context, task.failureReason().startsWith(
                            "acquire_water_no_reachable_surface_route"),
                    "sealed route returned the wrong typed failure: " + task.failureReason());
            require(context, restarted.get(),
                    "sealed route failed without checkpoint restart coverage");
            require(context, Integer.parseInt(checkpoint.get("issued")) <= 3
                            && "0".equals(checkpoint.get("reached"))
                            && "3".equals(checkpoint.get("consecutive_unreachable")),
                    "sealed route burned cursor or invented observations: " + checkpoint);
            require(context, fixture.bot().getBlockPos().equals(fixture.start()),
                    "sealed route moved out of its physical cage: " + fixture.bot().getBlockPos());
            require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                    "sealed-route failure consumed the empty bucket");

            AcquireWaterTask terminalRestore = new AcquireWaterTask(fixture.start(), checkpoint);
            terminalRestore.start(fixture.bot());
            require(context, terminalRestore.state() == TaskState.FAILED
                            && terminalRestore.failureReason().startsWith(
                            "acquire_water_no_reachable_surface_route"),
                    "terminal unreachable ledger restarted as fresh search");
            cleanupDry(context, fixture);
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "waterSearchLivenessLive", tickLimit = 120)
    public void blockedSurfaceSectorClearsOnlyLocalLedgerAcrossRestart(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        BlockPos blockedGoal = start.east(2);
        // Keep the fixture inside EMPTY_STRUCTURE's 8x8 footprint.  The bot has one factual
        // three-cell west runway, while the next
        // semantically valid spiral waypoint is a standable cell enclosed by a two-high ring.
        // This forces GOAL_UNREACHABLE without rewriting terrain across neighboring structures.
        for (int distance = 0; distance <= 3; distance++) {
            BlockPos cell = start.west(distance);
            world.setBlockState(cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(cell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(blockedGoal.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(blockedGoal, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(blockedGoal.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(blockedGoal.up(2),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            world.setBlockState(blockedGoal.offset(direction),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(blockedGoal.offset(direction).up(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "WaterBlockedSectorGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));

        // Waypoint eight in the square spiral is grid=(1,-1).  Offset the persisted origin so
        // that this real cursor state resolves to the compact enclosed goal above.
        BlockPos searchOrigin = blockedGoal.add(-12, 0, 12);
        Map<String, String> impossible = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, blockedGoal, 100, 80, 6, 5));
        impossible.put("direction", "0");
        impossible.put("leg_length", "3");
        impossible.put("step_in_leg", "2");
        impossible.put("repeated_legs", "0");
        impossible.put("grid_x", "1");
        impossible.put("grid_z", "-1");
        impossible.put("path_attempts", "3");
        impossible.put("consecutive_unreachable", "3");
        impossible.put("unreachable_anchor", encode(start));
        AcquireWaterTask rejected = new AcquireWaterTask(searchOrigin, impossible);
        rejected.start(bot);
        require(context, rejected.state() == TaskState.FAILED
                        && "acquire_water_invalid_checkpoint".equals(rejected.failureReason())
                        && rejected.checkpoint().isEmpty(),
                "decoder accepted an impossible unreachable ledger");

        Map<String, String> terminal = new LinkedHashMap<>(impossible);
        terminal.put("issued", "8");
        terminal.put("reached", "5");
        AcquireWaterTask recoveredRestore = new AcquireWaterTask(searchOrigin, terminal);
        recoveredRestore.start(bot);
        Map<String, String> recovered = recoveredRestore.checkpoint();
        require(context, recoveredRestore.state() == TaskState.RUNNING
                        && "100".equals(recovered.get("budget_used"))
                        && "8".equals(recovered.get("issued"))
                        && "5".equals(recovered.get("reached"))
                        && "0".equals(recovered.get("direction"))
                        && "3".equals(recovered.get("leg_length"))
                        && "2".equals(recovered.get("step_in_leg"))
                        && "1".equals(recovered.get("grid_x"))
                        && "-1".equals(recovered.get("grid_z"))
                        && "0".equals(recovered.get("path_attempts"))
                        && "0".equals(recovered.get("consecutive_unreachable"))
                        && !recovered.containsKey("waypoint")
                        && !recovered.containsKey("unreachable_anchor"),
                "restored blocked sector changed durable cursor/budget authority: "
                        + recoveredRestore.state() + ":" + recoveredRestore.failureReason()
                        + " checkpoint=" + recovered);
        recoveredRestore.cancel(bot, "gametest_recovered_sector_second_restore");
        AcquireWaterTask recoveredAgain = new AcquireWaterTask(searchOrigin, recovered);
        recoveredAgain.start(bot);
        require(context, recoveredAgain.state() == TaskState.RUNNING
                        && recovered.equals(recoveredAgain.checkpoint()),
                "recovered sector flipped across a second restore: "
                        + recoveredAgain.state() + ":" + recoveredAgain.failureReason());
        recoveredAgain.cancel(bot, "gametest_recovered_sector_live_boundary");

        // A legitimate live state after waypoints six and seven both failed and the enclosed
        // eighth goal has failed A* twice.  Its third failed attempt must clear only the local
        // consecutive ledger and preserve every durable expedition bound.
        Map<String, String> cursor = new LinkedHashMap<>(searchCheckpoint(
                searchOrigin, blockedGoal, 120, 100, 8, 5));
        cursor.put("direction", "0");
        cursor.put("leg_length", "3");
        cursor.put("step_in_leg", "2");
        cursor.put("repeated_legs", "0");
        cursor.put("grid_x", "1");
        cursor.put("grid_z", "-1");
        cursor.put("path_attempts", "2");
        cursor.put("consecutive_unreachable", "2");
        cursor.put("unreachable_anchor", encode(start));

        AcquireWaterTask active = new AcquireWaterTask(searchOrigin, cursor);
        active.start(bot);
        context.runAtEveryTick(() -> {
            active.tick(bot);
            Map<String, String> checkpoint = active.checkpoint();
            if (active.state() == TaskState.FAILED || active.state() == TaskState.CANCELLED) {
                context.throwGameTestException("blocked sector ended the surface search: "
                        + active.state() + ":" + active.failureReason()
                        + " checkpoint=" + checkpoint);
                return;
            }
            if (!"8".equals(checkpoint.get("issued"))
                    || !"0".equals(checkpoint.get("consecutive_unreachable"))
                    || checkpoint.containsKey("waypoint")) {
                return;
            }
            require(context, "5".equals(checkpoint.get("reached"))
                            && "0".equals(checkpoint.get("direction"))
                            && "3".equals(checkpoint.get("leg_length"))
                            && "2".equals(checkpoint.get("step_in_leg"))
                            && "1".equals(checkpoint.get("grid_x"))
                            && "-1".equals(checkpoint.get("grid_z"))
                            && Integer.parseInt(checkpoint.get("budget_used")) >= 120
                            && !checkpoint.containsKey("unreachable_anchor")
                            && bot.getBlockPos().equals(start),
                    "live blocked-sector recovery changed cursor, budget, or position: "
                            + checkpoint + " pos=" + bot.getBlockPos());
            int used = Integer.parseInt(checkpoint.get("budget_used"));
            active.cancel(bot, "gametest_blocked_sector_restart");
            AcquireWaterTask restored = new AcquireWaterTask(searchOrigin, checkpoint);
            restored.start(bot);
            Map<String, String> after = restored.checkpoint();
            require(context, restored.state() == TaskState.RUNNING
                            && "8".equals(after.get("issued"))
                            && "5".equals(after.get("reached"))
                            && "1".equals(after.get("grid_x"))
                            && "-1".equals(after.get("grid_z"))
                            && Integer.parseInt(after.get("budget_used")) >= used,
                    "live blocked-sector recovery did not survive restart: before="
                            + checkpoint + " after=" + after);
            restored.cancel(bot, "gametest_blocked_sector_complete");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void deepSkyLitRavineDoesNotCountAsSurfaceExit(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(8, 4, 8));
        BlockPos surfaceAnchor = start.up(20);
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            world.setBlockState(start.offset(direction),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "WaterDeepSkylightGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, world.isSkyVisible(start), "fixture is not sky visible");

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);
        Map<String, String> checkpoint = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING,
                "deep skylight task stopped unexpectedly: " + task.failureReason());
        require(context, "RETURN_SURFACE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit")),
                "deep skylight was incorrectly latched as surface: " + checkpoint);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void nearAnchorSkyPocketWithoutLateralEgressKeepsAscending(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(20, 8, 20));
        // Exact anchor proximity exercises the historical bypass branch: it used to enter SEARCH
        // here without proving dry footing or a reusable lateral/uphill surface edge.
        BlockPos surfaceAnchor = start;

        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos wall = start.offset(direction);
            world.setBlockState(wall.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(wall, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(wall.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "WaterSkyPocketGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_PICKAXE));
        require(context, world.isSkyVisible(start), "stair pocket is not sky visible");

        AcquireWaterTask task = new AcquireWaterTask(surfaceAnchor);
        task.start(bot);
        task.tick(bot);
        Map<String, String> checkpoint = task.checkpoint();
        require(context, task.state() == TaskState.RUNNING,
                "sky pocket stopped unexpectedly: " + task.failureReason());
        require(context, "RETURN_SURFACE".equals(checkpoint.get("phase"))
                        && "false".equals(checkpoint.get("surface_exit")),
                "one-cell sky pocket was incorrectly latched as reusable surface: " + checkpoint);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void malformedCheckpointFailsClosed(TestContext context) {
        WaterFixture fixture = spawnWaterSeeker(context, "WaterCheckpointGT");
        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), Map.of(
                "schema", "999",
                "surface_anchor", encode(fixture.start())));
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "malformed checkpoint must fail instead of restarting a fresh search");
        require(context, "acquire_water_invalid_checkpoint".equals(task.failureReason()),
                "unexpected malformed-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "invalid water restore published an invented successor checkpoint");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                "failed restore mutated the inventory");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void schemaThreeSearchWithoutSurfaceExitFailsClosed(TestContext context) {
        WaterFixture fixture = spawnWaterSeeker(context, "WaterFalseSurfaceLatchGT");
        Map<String, String> forged = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), fixture.start().east(12), 20, 10, 1, 0));
        forged.put("schema", "3");
        forged.remove("waypoint_limit");
        forged.remove("budget_limit");
        forged.put("surface_exit", "false");

        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), forged);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "schema-3 SEARCH with a false surface latch must fail closed");
        require(context, "acquire_water_invalid_checkpoint".equals(task.failureReason()),
                "unexpected contradictory-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "contradictory water restore published an invented successor checkpoint");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                "failed contradictory restore mutated the empty bucket");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void schemaTwoSearchWithoutSurfaceExitFailsClosed(TestContext context) {
        WaterFixture fixture = spawnWaterSeeker(context, "WaterLegacyFalseSurfaceLatchGT");
        Map<String, String> legacy = new LinkedHashMap<>(searchCheckpoint(
                fixture.start(), fixture.start().east(12), 20, 10, 1, 0));
        legacy.put("schema", "2");
        legacy.put("surface_exit", "false");
        legacy.put("visited", legacy.remove("issued"));
        legacy.remove("reached");
        legacy.remove("waypoint_limit");
        legacy.remove("budget_limit");
        legacy.remove("waypoint_started_budget");
        legacy.remove("consecutive_unreachable");

        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), legacy);
        task.start(fixture.bot());

        require(context, task.state() == TaskState.FAILED,
                "schema-2 SEARCH with a false surface latch must fail closed");
        require(context, "acquire_water_invalid_checkpoint".equals(task.failureReason()),
                "unexpected legacy contradictory-checkpoint reason: " + task.failureReason());
        require(context, task.checkpoint().isEmpty(),
                "legacy false-latch restore published an invented migrated checkpoint");
        require(context, InventoryAction.countItem(fixture.bot(), Items.BUCKET) == 1,
                "failed legacy restore mutated the empty bucket");
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void checkpointCursorAndAuthorityForgeryFailsClosed(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterCursorForgeryGT", false);
        BlockPos anchor = fixture.start();
        Map<String, String> valid = searchCheckpoint(
                anchor, anchor.east(12), 100, 80, 1, 0);

        Map<String, String> minGrid = new LinkedHashMap<>(valid);
        minGrid.put("grid_x", String.valueOf(Integer.MIN_VALUE));
        requireInvalidWaterCheckpoint(context, fixture, minGrid, "minimum integer grid");

        Map<String, String> mismatchedCursor = new LinkedHashMap<>(valid);
        mismatchedCursor.put("issued", "2");
        requireInvalidWaterCheckpoint(context, fixture, mismatchedCursor,
                "issued/cursor mismatch");

        Map<String, String> zeroWithWaypoint = new LinkedHashMap<>(valid);
        zeroWithWaypoint.put("issued", "0");
        zeroWithWaypoint.put("reached", "0");
        putSearchCursor(zeroWithWaypoint, 0);
        requireInvalidWaterCheckpoint(context, fixture, zeroWithWaypoint,
                "issued-zero pending waypoint");

        Map<String, String> malformedWaypoint = new LinkedHashMap<>(valid);
        malformedWaypoint.put("waypoint", "not,a,position");
        requireInvalidWaterCheckpoint(context, fixture, malformedWaypoint,
                "malformed pending waypoint");

        Map<String, String> legacyWaypointsWithCurrentBudget = new LinkedHashMap<>(valid);
        legacyWaypointsWithCurrentBudget.put("waypoint_limit", "100");
        requireInvalidWaterCheckpoint(context, fixture, legacyWaypointsWithCurrentBudget,
                "legacy waypoints/current budget hybrid");

        Map<String, String> currentWaypointsWithLegacyBudget = new LinkedHashMap<>(valid);
        currentWaypointsWithLegacyBudget.put("budget_limit", "12000");
        requireInvalidWaterCheckpoint(context, fixture, currentWaypointsWithLegacyBudget,
                "current waypoints/legacy budget hybrid");

        // A syntactically valid process-local resolved goal is not trusted as durable authority.
        // The restore remains valid, but its pending goal must be canonicalized from the cursor.
        Map<String, String> arbitraryResolvedGoal = new LinkedHashMap<>(valid);
        arbitraryResolvedGoal.put("waypoint", encode(anchor.add(73, 4, -51)));
        AcquireWaterTask canonical = new AcquireWaterTask(anchor, arbitraryResolvedGoal);
        canonical.start(fixture.bot());
        require(context, canonical.state() == TaskState.RUNNING
                        && encode(anchor.east(12)).equals(
                        canonical.checkpoint().get("waypoint")),
                "pending waypoint coordinates were trusted instead of rebuilt: "
                        + canonical.state() + ":" + canonical.failureReason()
                        + " checkpoint=" + canonical.checkpoint());
        canonical.cancel(fixture.bot(), "gametest_cursor_canonicalized");
        cleanupDry(context, fixture);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void legacyRunningAndSchemaTwoTerminalRetainOldAuthority(TestContext context) {
        DryFixture fixture = spawnDryWaterSeeker(context, "WaterLegacyAuthorityGT", false);
        BlockPos anchor = fixture.start();

        Map<String, String> schemaThree = new LinkedHashMap<>(searchCheckpoint(
                anchor, anchor.add(91, 3, -47), 100, 80, 1, 0));
        schemaThree.put("schema", "3");
        schemaThree.remove("waypoint_limit");
        schemaThree.remove("budget_limit");
        AcquireWaterTask previousRunning = new AcquireWaterTask(anchor, schemaThree);
        previousRunning.start(fixture.bot());
        Map<String, String> previousMigrated = previousRunning.checkpoint();
        require(context, previousRunning.state() == TaskState.RUNNING
                        && "4".equals(previousMigrated.get("schema"))
                        && "100".equals(previousMigrated.get("waypoint_limit"))
                        && "12000".equals(previousMigrated.get("budget_limit"))
                        && encode(anchor.east(12)).equals(previousMigrated.get("waypoint")),
                "schema-3 running checkpoint lost its old authority or canonical cursor: "
                        + previousRunning.state() + ":" + previousRunning.failureReason()
                        + " checkpoint=" + previousMigrated);
        previousRunning.cancel(fixture.bot(), "gametest_schema_three_running_checked");

        Map<String, String> schemaTwoRunning = new LinkedHashMap<>(searchCheckpoint(
                anchor, anchor.add(-45, 2, 63), 100, 80, 1, 0));
        schemaTwoRunning.put("schema", "2");
        schemaTwoRunning.put("visited", schemaTwoRunning.remove("issued"));
        schemaTwoRunning.remove("reached");
        schemaTwoRunning.remove("waypoint_limit");
        schemaTwoRunning.remove("budget_limit");
        schemaTwoRunning.remove("waypoint_started_budget");
        schemaTwoRunning.remove("consecutive_unreachable");
        AcquireWaterTask legacyRunning = new AcquireWaterTask(anchor, schemaTwoRunning);
        legacyRunning.start(fixture.bot());
        Map<String, String> legacyMigrated = legacyRunning.checkpoint();
        require(context, legacyRunning.state() == TaskState.RUNNING
                        && "4".equals(legacyMigrated.get("schema"))
                        && "100".equals(legacyMigrated.get("waypoint_limit"))
                        && "12000".equals(legacyMigrated.get("budget_limit"))
                        && "0".equals(legacyMigrated.get("reached"))
                        && encode(anchor.east(12)).equals(legacyMigrated.get("waypoint")),
                "schema-2 running checkpoint lost its old authority or canonical cursor: "
                        + legacyRunning.state() + ":" + legacyRunning.failureReason()
                        + " checkpoint=" + legacyMigrated);
        legacyRunning.cancel(fixture.bot(), "gametest_schema_two_running_checked");

        Map<String, String> schemaTwoTerminal = new LinkedHashMap<>(searchCheckpoint(
                anchor, anchor, 500, 500, 100, 0));
        schemaTwoTerminal.put("schema", "2");
        schemaTwoTerminal.put("visited", schemaTwoTerminal.remove("issued"));
        schemaTwoTerminal.remove("reached");
        schemaTwoTerminal.remove("waypoint");
        schemaTwoTerminal.remove("waypoint_limit");
        schemaTwoTerminal.remove("budget_limit");
        schemaTwoTerminal.remove("waypoint_started_budget");
        schemaTwoTerminal.remove("consecutive_unreachable");
        AcquireWaterTask legacyTerminal = new AcquireWaterTask(anchor, schemaTwoTerminal);
        legacyTerminal.start(fixture.bot());
        Map<String, String> terminalMigrated = legacyTerminal.checkpoint();
        require(context, legacyTerminal.state() == TaskState.FAILED
                        && legacyTerminal.failureReason().startsWith(
                        "acquire_water_search_exhausted")
                        && "100".equals(terminalMigrated.get("issued"))
                        && "100".equals(terminalMigrated.get("waypoint_limit"))
                        && "12000".equals(terminalMigrated.get("budget_limit"))
                        && !terminalMigrated.containsKey("waypoint"),
                "schema-2 terminal checkpoint was revived or rejected during migration: "
                        + legacyTerminal.state() + ":" + legacyTerminal.failureReason()
                        + " checkpoint=" + terminalMigrated);

        AcquireWaterTask terminalAgain = new AcquireWaterTask(anchor, terminalMigrated);
        terminalAgain.start(fixture.bot());
        require(context, terminalAgain.state() == TaskState.FAILED
                        && terminalAgain.failureReason().startsWith(
                        "acquire_water_search_exhausted")
                        && "100".equals(terminalAgain.checkpoint().get("waypoint_limit")),
                "migrated schema-2 terminal checkpoint restarted as running");
        cleanupDry(context, fixture);
    }

    private static void requireInvalidWaterCheckpoint(TestContext context,
                                                      DryFixture fixture,
                                                      Map<String, String> checkpoint,
                                                      String label) {
        AcquireWaterTask task = new AcquireWaterTask(fixture.start(), checkpoint);
        task.start(fixture.bot());
        require(context, task.state() == TaskState.FAILED
                        && "acquire_water_invalid_checkpoint".equals(task.failureReason())
                        && task.checkpoint().isEmpty(),
                label + " checkpoint did not fail closed: "
                        + task.state() + ":" + task.failureReason()
                        + " checkpoint=" + task.checkpoint());
    }

    private static Map<String, String> searchCheckpoint(BlockPos anchor,
                                                         BlockPos waypoint,
                                                         int budgetUsed,
                                                         int waypointStartedBudget,
                                                         int issued,
                                                         int reached) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "4");
        values.put("phase", "SEARCH");
        values.put("surface_anchor", encode(anchor));
        values.put("search_origin", encode(anchor));
        values.put("surface_exit", "true");
        putSearchCursor(values, issued);
        values.put("issued", String.valueOf(issued));
        values.put("reached", String.valueOf(reached));
        values.put("waypoint_limit", "224");
        values.put("budget_limit", "30000");
        values.put("path_attempts", "0");
        values.put("budget_used", String.valueOf(budgetUsed));
        values.put("phase_started", "0");
        values.put("waypoint_started_budget", String.valueOf(waypointStartedBudget));
        values.put("consecutive_unreachable", "0");
        values.put("waypoint", encode(waypoint));
        return Map.copyOf(values);
    }

    private static void putSearchCursor(Map<String, String> values, int issued) {
        int direction = 0;
        int legLength = 1;
        int stepInLeg = 0;
        int repeatedLegs = 0;
        int gridX = 0;
        int gridZ = 0;
        for (int index = 0; index < issued; index++) {
            switch (direction) {
                case 0 -> gridX++;
                case 1 -> gridZ++;
                case 2 -> gridX--;
                default -> gridZ--;
            }
            stepInLeg++;
            if (stepInLeg >= legLength) {
                stepInLeg = 0;
                direction = (direction + 1) & 3;
                repeatedLegs++;
                if (repeatedLegs >= 2) {
                    repeatedLegs = 0;
                    legLength++;
                }
            }
        }
        values.put("direction", String.valueOf(direction));
        values.put("leg_length", String.valueOf(legLength));
        values.put("step_in_leg", String.valueOf(stepInLeg));
        values.put("repeated_legs", String.valueOf(repeatedLegs));
        values.put("grid_x", String.valueOf(gridX));
        values.put("grid_z", String.valueOf(gridZ));
    }

    private static Map<String, String> approachCheckpoint(BlockPos anchor,
                                                           BlockPos source,
                                                           BlockPos stand) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "4");
        values.put("phase", "APPROACH");
        values.put("surface_anchor", encode(anchor));
        values.put("search_origin", encode(anchor));
        values.put("surface_exit", "true");
        values.put("direction", "0");
        values.put("leg_length", "1");
        values.put("step_in_leg", "0");
        values.put("repeated_legs", "0");
        values.put("grid_x", "0");
        values.put("grid_z", "0");
        values.put("issued", "0");
        values.put("reached", "0");
        values.put("waypoint_limit", "224");
        values.put("budget_limit", "30000");
        values.put("path_attempts", "0");
        values.put("budget_used", "0");
        values.put("phase_started", "0");
        values.put("waypoint_started_budget", "0");
        values.put("consecutive_unreachable", "0");
        values.put("water_source", encode(source));
        values.put("water_stand", encode(stand));
        return Map.copyOf(values);
    }

    private static Map<String, String> returnSurfaceCheckpoint(BlockPos anchor,
                                                                int budgetUsed,
                                                                int pathAttempts) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", "4");
        values.put("phase", "RETURN_SURFACE");
        values.put("surface_anchor", encode(anchor));
        values.put("search_origin", encode(anchor));
        values.put("surface_exit", "false");
        values.put("direction", "0");
        values.put("leg_length", "1");
        values.put("step_in_leg", "0");
        values.put("repeated_legs", "0");
        values.put("grid_x", "0");
        values.put("grid_z", "0");
        values.put("issued", "0");
        values.put("reached", "0");
        values.put("waypoint_limit", "224");
        values.put("budget_limit", "30000");
        values.put("path_attempts", String.valueOf(pathAttempts));
        values.put("budget_used", String.valueOf(budgetUsed));
        values.put("phase_started", "0");
        values.put("waypoint_started_budget", "0");
        values.put("consecutive_unreachable", "0");
        return Map.copyOf(values);
    }

    private static DryFixture spawnDryWaterSeeker(TestContext context,
                                                   String name,
                                                   boolean sealed) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(24, 4, 24));
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                world.setBlockState(start.add(dx, -1, dz),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                for (int dy = 0; dy <= 2; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        if (sealed) {
            // A 3x3 interior proves that one adjacent standable cell is not enough to attest a
            // reusable surface route.  The roof removes open-sky authority and the radius-two
            // double wall keeps the whole locally walkable room physically sealed.
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        world.setBlockState(start.add(dx, 0, dz),
                                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        world.setBlockState(start.add(dx, 1, dz),
                                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                    } else {
                        world.setBlockState(start.add(dx, 2, dz),
                                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                    }
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
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        return new DryFixture(name, bot, start.toImmutable());
    }

    private static void cleanupDry(TestContext context, DryFixture fixture) {
        AIPlayerManager.INSTANCE.despawn(fixture.bot().getServer(), fixture.name());
        context.complete();
    }

    private static WaterFixture spawnWaterSeeker(TestContext context, String name) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(2, 2, 2));
        for (int dx = -3; dx <= 16; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(start.add(dx, -1, dz), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 0, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 1, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                world.setBlockState(start.add(dx, 2, dz), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        // A one-cell surface well is surrounded by a solid rim, so it cannot spread into the
        // walking lane. The west rim is the first +12 waypoint: from spawn it occludes the source;
        // after the bot physically climbs onto it, the source is visible and within bucket reach.
        BlockPos water = start.add(13, 0, 0);
        world.setBlockState(water.west(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water.east(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water.north(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water.south(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(water, Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        -90.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), -90.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        InventoryAction.giveItem(bot, new ItemStack(Items.BUCKET));
        return new WaterFixture(name, bot, start.toImmutable(), water.toImmutable());
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static String encode(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record WaterFixture(String name, AIPlayerEntity bot, BlockPos start, BlockPos water) {
    }

    private record DryFixture(String name, AIPlayerEntity bot, BlockPos start) {
    }
}
