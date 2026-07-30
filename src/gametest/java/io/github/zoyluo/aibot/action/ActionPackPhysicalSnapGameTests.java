package io.github.zoyluo.aibot.action;

import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.pathfinding.AStarPathfinder;
import io.github.zoyluo.aibot.pathfinding.MoveType;
import io.github.zoyluo.aibot.pathfinding.PathExecutor;
import io.github.zoyluo.aibot.pathfinding.PathfindingResult;
import io.github.zoyluo.aibot.pathfinding.Standability;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.stat.Stats;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Strict-survival regression for a one-cell physical recovery from an invalid A* start. */
public final class ActionPackPhysicalSnapGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "physicalCenterReturnLive", tickLimit = 20)
    public void centerReturnClearsResidualWalkVelocityBeforeNextServerTick(
            TestContext context) {
        var world = context.getWorld();
        BlockPos anchor = context.getAbsolutePos(new BlockPos(3, 3, 3));
        world.setBlockState(anchor.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor.south(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor.south().up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "CenterReturnVelocityGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(anchor),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, anchor.getX() + 0.5D, anchor.getY(), anchor.getZ() + 0.95D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        bot.setVelocity(0.0D, 0.0D, 0.85D);

        require(context, FakePlayerMotion.returnToBlockCenter(
                        bot, anchor, "gametest_residual_walk_velocity"),
                "supported same-cell return was rejected");
        require(context, bot.getVelocity().lengthSquared() == 0.0D && bot.isOnGround(),
                "center return did not publish a stationary grounded pose");
        AtomicInteger observedTicks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            if (observedTicks.incrementAndGet() < 3) {
                return;
            }
            require(context, bot.getBlockPos().equals(anchor),
                    "residual walk velocity moved the centered player to "
                            + bot.getBlockPos().toShortString());
            require(context, bot.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(anchor))
                            < 1.0E-6D,
                    "centered player drifted before the next service transaction");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void standableBodySnapPreservesSafeOffsetAndRecentersRealCornerOverlap(
            TestContext context) {
        var world = context.getWorld();
        BlockPos anchor = context.getAbsolutePos(new BlockPos(7, 3, 7));
        world.setBlockState(anchor.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor.north(),
                Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor.north().west(),
                Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_ALL);

        String name = "BodySnapCornerGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(anchor),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));

        // A collision-free fractional pose is legitimate physical state and must not be
        // normalized merely because it is off centre.
        bot.teleport(world, anchor.getX() + 0.65D, anchor.getY(),
                anchor.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        Vec3d safeOffset = bot.getPos();
        require(context, FakePlayerMotion.isBlockCollisionFree(bot),
                "safe-offset fixture unexpectedly collided");
        require(context, bot.getActionPack().snapPlayerToNearestStandable(
                        "gametest_safe_fractional_pose"),
                "safe fractional stand was rejected");
        require(context, bot.getPos().squaredDistanceTo(safeOffset) < 1.0E-12D,
                "safe fractional stand was unnecessarily recentered");

        // Seed-3000 equivalent: the lower corner still floors to the standable anchor column, but
        // the player's width crosses north/west into raised full blocks.
        bot.teleport(world, anchor.getX(), anchor.getY(), anchor.getZ(),
                Set.of(), 0.0F, 0.0F, true);
        Standability.clearCache();
        require(context, Standability.isStandable(world, anchor),
                "corner fixture logical column was not standable");
        require(context, !FakePlayerMotion.isBlockCollisionFree(bot),
                "corner fixture did not produce a real body collision");
        require(context, bot.getActionPack().snapPlayerToNearestStandable(
                        "gametest_corner_overlap"),
                "standable corner overlap did not recover physically");
        Vec3d centered = Vec3d.ofBottomCenter(anchor);
        require(context, bot.getPos().squaredDistanceTo(centered) < 1.0E-12D
                        && FakePlayerMotion.isBlockCollisionFree(bot),
                "corner snap returned before reaching a collision-free centre");

        Vec3d after = bot.getPos();
        require(context, bot.getActionPack().snapPlayerToNearestStandable(
                        "gametest_corner_overlap_idempotent"),
                "already-cleared centre was rejected");
        require(context, bot.getPos().squaredDistanceTo(after) < 1.0E-12D,
                "idempotent snap moved an already-cleared centre");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void centerReturnRejectsLivingEntityOccupyingLanding(TestContext context) {
        var world = context.getWorld();
        BlockPos anchor = context.getAbsolutePos(new BlockPos(12, 3, 12));
        world.setBlockState(anchor.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(anchor.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "CenterOccupiedGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(anchor),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, anchor.getX() + 0.95D, anchor.getY(),
                anchor.getZ() + 0.95D, Set.of(), 0.0F, 0.0F, true);
        Vec3d before = bot.getPos();

        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create centre-occupying cow");
        cow.refreshPositionAndAngles(
                anchor.getX(), anchor.getY(), anchor.getZ(), 0.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn centre-occupying cow");
        require(context, !bot.getBoundingBox().intersects(cow.getBoundingBox()),
                "fixture cow already overlapped the off-centre bot");

        require(context, !FakePlayerMotion.returnToBlockCenter(
                        bot, anchor, "gametest_occupied_center"),
                "centre return entered a living entity");
        require(context, bot.getPos().squaredDistanceTo(before) < 1.0E-12D,
                "rejected occupied centre return still moved the bot");
        require(context, cow.isAlive(),
                "rejected centre return removed the occupying entity");

        cow.discard();
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void consecutiveHorizontalJumpsPublishEachVerifiedLanding(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 3, 3));
        BlockPos first = start.east().up();
        BlockPos second = first.east().up();
        BlockPos third = second.east().up();
        for (BlockPos feet : new BlockPos[]{start, first, second, third}) {
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "HorizontalJumpGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);

        require(context, FakePlayerMotion.jumpTo(bot, first, "gametest_first_natural_jump"),
                "first horizontal jump was rejected");
        require(context, bot.getBlockPos().equals(first) && bot.isOnGround(),
                "first verified landing was not published as grounded");
        // Reproduce the server tick that overwrites a clientless player's grounded flag even
        // though its collision box is still resting exactly on the verified first landing.
        bot.setOnGround(false);
        require(context, FakePlayerMotion.jumpTo(bot, second, "gametest_second_natural_jump"),
                "second supported jump was rejected because onGround was stale");
        require(context, bot.getBlockPos().equals(second) && bot.isOnGround(),
                "second verified landing was not published as grounded");

        // A standable block below is not enough: lift the same collision box 2.5 cm so the
        // vanilla support probe no longer touches it. This must remain a real airborne rejection.
        bot.teleport(world, second.getX() + 0.5D, second.getY() + 0.025D,
                second.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        bot.setOnGround(false);
        require(context, !FakePlayerMotion.jumpTo(bot, third, "gametest_airborne_jump_rejected"),
                "airborne fake player was accepted as a stale-grounded landing");
        require(context, bot.getBlockPos().equals(second)
                        && Math.abs(bot.getY() - (second.getY() + 0.025D)) < 1.0E-6D,
                "rejected airborne jump still changed the player pose");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void horizontalJumpRejectsLivingEntityOnLanding(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(9, 3, 3));
        BlockPos landing = start.east().up();
        world.setBlockState(start.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(start.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(landing.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "OccupiedJumpGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        var cow = EntityType.COW.create(world, SpawnReason.COMMAND);
        require(context, cow != null, "failed to create occupied-jump cow");
        cow.refreshPositionAndAngles(Vec3d.ofBottomCenter(landing), 0.0F, 0.0F);
        require(context, world.spawnEntity(cow), "failed to spawn occupied-jump cow");

        require(context, !FakePlayerMotion.jumpTo(bot, landing, "gametest_occupied_landing"),
                "horizontal jump entered a living entity's landing box");
        require(context, bot.getBlockPos().equals(start) && bot.isOnGround(),
                "rejected occupied jump changed the player pose");
        require(context, world.getBlockState(landing.down()).isOf(Blocks.STONE),
                "rejected occupied jump changed its natural support");

        cow.discard();
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void sameLevelPickupPrefersExactDropCellOverCurrentNeighbour(TestContext context) {
        var world = context.getWorld();
        BlockPos current = context.getAbsolutePos(new BlockPos(4, 5, 4));
        BlockPos drop = current.south();
        for (BlockPos feet : new BlockPos[]{current, drop}) {
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }

        String name = "PickupStandGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(current),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, current.getX() + 0.5D, current.getY(), current.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);

        require(context, HarvestCore.pickupStandPos(bot, drop).equals(drop),
                "same-level pickup did not select the exact drop cell");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "physicalPickupSupportLive", tickLimit = 160)
    public void edgePerchedObservedDropUsesPhysicalSupportInsteadOfOnGroundFlag(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 4, 4));
        BlockPos pickupStand = start.east();
        BlockPos unsupportedDropCell = pickupStand.east();
        for (int dx = 0; dx <= 1; dx++) {
            BlockPos feet = start.east(dx);
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(unsupportedDropCell.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(unsupportedDropCell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(unsupportedDropCell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "PickupEdgeSupportGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        Vec3d startPose = bot.getPos();
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF);

        // Reproduce the failed narrow-ridge loot pose: the 0.25-wide item is mostly over an
        // unsupported cell, with only 0.025 blocks of its AABB still resting over pickupStand's
        // west-side support. Vanilla has not published onGround, but ordinary collision can
        // support and collect it without entering the unsupported cell.
        ItemEntity drop = new ItemEntity(world,
                unsupportedDropCell.getX() + 0.10D,
                unsupportedDropCell.getY() + 0.24D,
                unsupportedDropCell.getZ() + 0.5D,
                new ItemStack(Items.BEEF));
        drop.setVelocity(Vec3d.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(false);
        drop.resetPickupDelay();
        require(context, world.spawnEntity(drop), "failed to spawn edge-perched beef");
        require(context, !drop.isOnGround(), "fixture unexpectedly published onGround");
        require(context, HarvestCore.isDropPhysicallySupported(bot, drop),
                "collision-supported edge drop was classified as airborne");

        context.runAtEveryTick(() -> {
            if (InventoryAction.countItem(bot, Items.BEEF) < 1) {
                HarvestCore.approachDropPhysically(bot, drop);
                return;
            }
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.BEEF)
                            > pickupBaseline,
                    "edge drop entered inventory without vanilla pickup credit");
            require(context, bot.getPos().squaredDistanceTo(startPose) > 0.01D,
                    "edge drop was collected without at least 0.1 blocks of physical movement");
            require(context, !bot.getBlockPos().equals(unsupportedDropCell),
                    "edge pickup entered the unsupported drop cell: "
                            + bot.getBlockPos().toShortString());
            require(context, world.getBlockState(unsupportedDropCell.down()).isAir(),
                    "edge pickup manufactured support beneath the drop");
            require(context, !drop.isAlive(), "picked beef entity remained in the world");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "physicalPickupSupportLive", tickLimit = 100)
    public void sameCellEdgeDropRequiresPhysicalNudgeBeforeVanillaPickup(TestContext context) {
        var world = context.getWorld();
        BlockPos stand = context.getAbsolutePos(new BlockPos(7, 4, 4));
        world.setBlockState(stand.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(stand, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(stand.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "PickupSameCellEdgeGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(stand),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        Vec3d startPose = bot.getPos();
        int pickupBaseline = bot.getStatHandler().getStat(Stats.PICKED_UP, Items.OAK_LOG);

        ItemEntity drop = new ItemEntity(world,
                stand.getX() + 0.96D,
                stand.getY() + 0.20D,
                stand.getZ() + 0.5D,
                new ItemStack(Items.OAK_LOG));
        drop.setVelocity(Vec3d.ZERO);
        drop.setNoGravity(true);
        drop.setOnGround(true);
        drop.setPickupDelayInfinite();
        require(context, world.spawnEntity(drop), "failed to spawn same-cell edge drop");
        require(context, bot.getBlockPos().equals(drop.getBlockPos()),
                "fixture did not place player and drop in the same block cell");
        require(context, !bot.getBoundingBox().intersects(drop.getBoundingBox()),
                "fixture edge drop already intersected the player");

        AtomicBoolean nudgeObserved = new AtomicBoolean();
        context.runAtEveryTick(() -> {
            if (InventoryAction.countItem(bot, Items.OAK_LOG) < 1) {
                HarvestCore.approachDropPhysically(bot, drop);
                if (!nudgeObserved.get()
                        && bot.getPos().squaredDistanceTo(startPose) > 0.01D) {
                    nudgeObserved.set(true);
                    drop.resetPickupDelay();
                }
                return;
            }
            require(context, bot.getBlockPos().equals(stand),
                    "same-cell pickup left its verified support cell");
            require(context, nudgeObserved.get(),
                    "same-cell edge drop was collected without a physical nudge");
            require(context, bot.getStatHandler().getStat(Stats.PICKED_UP, Items.OAK_LOG)
                            > pickupBaseline,
                    "same-cell edge pickup did not publish vanilla pickup credit");
            require(context, !drop.isAlive(), "picked same-cell edge drop remained alive");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "surfaceReplanFailClosed", tickLimit = 400)
    public void surfacePathReplanCannotEscalateIntoDigOrPillar(TestContext context) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(3, 4, 4));
        BlockPos blocked = start.east();
        BlockPos goal = blocked.east();
        for (BlockPos feet : new BlockPos[]{start, blocked, goal}) {
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            for (int dy = 0; dy <= 4; dy++) {
                world.setBlockState(feet.up(dy), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }

        String name = "SurfaceReplanPolicyGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(start),
                        270.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D,
                Set.of(), 270.0F, 0.0F, true);
        bot.setOnGround(true);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_PICKAXE));
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 4));

        PathfindingResult initial = new AStarPathfinder(
                world, start, goal, 3_000, 30L, false, false).findPath();
        require(context, initial.success(),
                "initial walking-only path was rejected: " + initial.reason());
        require(context, goal.equals(initial.resolvedGoal()),
                "initial surface endpoint was not exact");
        PathExecutor executor = new PathExecutor(initial.path(), goal, false, false);

        // Invalidate the already planned corridor after startup. A permissive internal replan can
        // escape this two-high stone wall by mining it or by spending dirt pillars; a walking-only
        // replan must instead fail closed and leave both the world and mission inventory untouched.
        world.setBlockState(blocked, Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(blocked.up(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        AStarPathfinder.invalidateCache("gametest_surface_replan_dynamic_wall");
        AtomicInteger ticks = new AtomicInteger();

        context.runAtEveryTick(() -> {
            require(context, world.getBlockState(blocked).isOf(Blocks.STONE)
                            && world.getBlockState(blocked.up()).isOf(Blocks.STONE),
                    "surface replan broke the dynamic wall");
            require(context, world.getBlockState(start).isAir()
                            && world.getBlockState(start.up()).isAir(),
                    "surface replan manufactured a pillar at the route origin");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 4,
                    "surface replan consumed disposable support material");
            require(context, !bot.getBlockPos().equals(goal),
                    "walking-only replan crossed an impassable dynamic wall");

            int elapsed = ticks.incrementAndGet();
            ActionResult result = executor.tick(bot.getActionPack());
            if (result.isInProgress()) {
                return;
            }
            require(context, result.isFailed(),
                    "walking-only replan unexpectedly completed through the wall");
            require(context, result.reason().contains("replan_failed"),
                    "fixture did not reach the bounded internal replan: " + result.reason());
            require(context, elapsed > 2,
                    "fixture never exercised the active route before failing");
            require(context, bot.getActionPack().isMiningIdle(),
                    "failed-closed surface replan left a mining action active");
            AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
            context.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void invalidStartUsesAdjacentPhysicalLandingBeforePrivilegedSnap(TestContext context) {
        var world = context.getWorld();
        BlockPos invalid = context.getAbsolutePos(new BlockPos(5, 6, 5));
        BlockPos landing = invalid.add(1, -1, 0);
        BlockPos goal = landing.add(2, 0, 0);

        for (int x = landing.getX(); x <= goal.getX(); x++) {
            BlockPos feet = new BlockPos(x, landing.getY(), landing.getZ());
            world.setBlockState(feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            world.setBlockState(feet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        world.setBlockState(invalid.down(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(invalid, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        world.setBlockState(invalid.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        String name = "PhysicalSnapGT";
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        world.getServer(), name, world, Vec3d.ofBottomCenter(invalid),
                        0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(world, invalid.getX() + 0.5D, invalid.getY(), invalid.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, false);

        require(context, !Standability.isStandable(world, invalid),
                "fixture start unexpectedly standable");
        ActionResult result = bot.getActionPack().startPathTo(goal);
        require(context, !result.isFailed(), "strict physical start recovery failed: " + result.reason());
        require(context, bot.getBlockPos().equals(landing),
                "recovery was not the adjacent physical landing: " + bot.getBlockPos().toShortString());
        require(context, Standability.isStandable(world, bot.getBlockPos()),
                "physical recovery ended on a non-standable cell");

        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "pathStoneReserve76", tickLimit = 200)
    public void reserve76Rejects76AndPillarSpends77thStone(TestContext context) {
        verifyStoneReserveBoundary(context, 76);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "pathStoneReserve37", tickLimit = 200)
    public void reserve37Rejects37AndPillarSpends38thStone(TestContext context) {
        verifyStoneReserveBoundary(context, 37);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "pathStoneReserve16", tickLimit = 200)
    public void reserve16Rejects16AndPillarSpends17thStone(TestContext context) {
        verifyStoneReserveBoundary(context, 16);
    }

    /**
     * Builds a two-high bedrock ring whose only route to the exact wall-top goal is one real
     * PILLAR_UP followed by an ordinary jump. This exercises reserve-aware ActionPack planning and
     * the PathExecutor's vanilla block placement, rather than only calling the palette selector.
     */
    private static void verifyStoneReserveBoundary(TestContext context, int reserve) {
        var world = context.getWorld();
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 3, 4));
        BlockPos goal = start.east().up(2);
        prepareOnePillarExit(world, start);

        String exactName = "R" + reserve + "Exact";
        AIPlayerEntity exact = spawnReserveBot(context, exactName, start);
        giveCobblestone(context, exact, reserve);
        require(context, !PathExecutor.hasPlaceableBlock(exact, reserve),
                "exact reserve exposed protected stone to initial planning: " + reserve);
        AStarPathfinder.invalidateCache("gametest_path_reserve_exact_" + reserve);
        ActionResult denied = exact.getActionPack().startPathTo(goal, reserve);
        require(context, denied.isFailed(),
                "exact reserve planned an escape that requires protected PILLAR_UP: " + reserve);
        require(context, InventoryAction.countItem(exact, Items.COBBLESTONE) == reserve,
                "failed exact-reserve planning changed mission inventory");
        require(context, world.getBlockState(start).isAir(),
                "failed exact-reserve planning placed a support block");
        AIPlayerManager.INSTANCE.despawn(exact.getServer(), exactName);

        prepareOnePillarExit(world, start);
        String surplusName = "R" + reserve + "Plus";
        AIPlayerEntity surplus = spawnReserveBot(context, surplusName, start);
        giveCobblestone(context, surplus, reserve + 1);
        require(context, PathExecutor.hasPlaceableBlock(surplus, reserve),
                "one surplus stone was not exposed to scoped path planning: " + reserve);

        AStarPathfinder.invalidateCache("gametest_path_reserve_surplus_" + reserve);
        PathfindingResult planned = new AStarPathfinder(
                world, start, goal, 3_000, 50L, true, false).findPath();
        require(context, planned.success() && goal.equals(planned.resolvedGoal()),
                "one-surplus fixture did not produce an exact path: " + planned.reason());
        long pillarNodes = planned.path().stream()
                .filter(node -> node.moveType() == MoveType.PILLAR_UP)
                .count();
        require(context, pillarNodes == 1,
                "fixture must plan exactly one real PILLAR_UP, got " + pillarNodes);

        ActionResult started = surplus.getActionPack().startPathTo(goal, reserve);
        require(context, started.isInProgress(),
                "reserve-aware ActionPack rejected one surplus stone: " + started.reason());
        AtomicInteger ticks = new AtomicInteger();
        context.runAtEveryTick(() -> {
            ticks.incrementAndGet();
            if (!surplus.getBlockPos().equals(goal)
                    || !surplus.getActionPack().isPathExecutorIdle()) {
                require(context, !surplus.getActionPack().isPathExecutorIdle()
                                || surplus.getBlockPos().equals(goal),
                        "reserve-aware path terminated before its exact goal at "
                                + surplus.getBlockPos().toShortString());
                return;
            }
            require(context, world.getBlockState(start).isOf(Blocks.COBBLESTONE),
                    "PILLAR_UP did not place the factual surplus support");
            require(context, InventoryAction.countItem(surplus, Items.COBBLESTONE) == reserve,
                    "PILLAR_UP crossed the protected reserve boundary: expected=" + reserve
                            + " actual=" + InventoryAction.countItem(surplus, Items.COBBLESTONE));
            require(context, ticks.get() > 1,
                    "path completed without executing the planned physical pillar");
            AIPlayerManager.INSTANCE.despawn(surplus.getServer(), surplusName);
            context.complete();
        });
    }

    private static void prepareOnePillarExit(net.minecraft.server.world.ServerWorld world,
                                             BlockPos start) {
        for (BlockPos pos : BlockPos.iterate(start.add(-3, 0, -3), start.add(3, 5, 3))) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (BlockPos pos : BlockPos.iterate(start.add(-3, -1, -3), start.add(3, -1, 3))) {
            world.setBlockState(pos, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) != 1 && Math.abs(dz) != 1) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    world.setBlockState(start.add(dx, dy, dz),
                            Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        Standability.clearCache();
    }

    private static AIPlayerEntity spawnReserveBot(TestContext context, String name,
                                                   BlockPos start) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(start), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), start.getX() + 0.5D, start.getY(),
                start.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        bot.setOnGround(true);
        bot.getInventory().clear();
        bot.getInventory().markDirty();
        return bot;
    }

    private static void giveCobblestone(TestContext context, AIPlayerEntity bot, int count) {
        int remaining = count;
        while (remaining > 0) {
            int stackSize = Math.min(remaining, Items.COBBLESTONE.getMaxCount());
            ActionResult given = InventoryAction.giveItem(
                    bot, new ItemStack(Items.COBBLESTONE, stackSize));
            require(context, given.isSuccess(),
                    "failed to prepare exact cobblestone count " + count);
            remaining -= stackSize;
        }
        require(context, InventoryAction.countItem(bot, Items.COBBLESTONE) == count,
                "fixture cobblestone count mismatch: expected=" + count);
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
