package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.AIBotConfig;
import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.CapabilityRuntime;
import io.github.zoyluo.aibot.mode.FakePlayerMotion;
import io.github.zoyluo.aibot.mode.OperatingProfile;
import io.github.zoyluo.aibot.mode.PrivilegedCapability;
import io.github.zoyluo.aibot.pathfinding.Standability;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic regressions for the bounded underground safety contracts. */
public final class UndergroundSafetyGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void emergencyShelterRejectsAnUnstableOriginBeforeWorldMutation(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterOriginGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        context.getWorld().setBlockState(feet.down(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        bot.setOnGround(false);

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_unstable_origin"));

        require(context, task.state() == TaskState.FAILED
                        && "shelter_origin_not_stable".equals(task.failureReason()),
                "unstable shelter origin was accepted: "
                        + task.state() + ":" + task.failureReason());
        require(context, shell.stream().allMatch(pos -> context.getWorld().getBlockState(pos).isAir()),
                "unstable shelter preflight mutated the world");
        require(context, InventoryAction.countItem(bot, Items.DIRT) == 16,
                "unstable shelter preflight consumed material");
        finish(context, bot, "ShelterOriginGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 400)
    public void emergencyShelterCannotCompleteBeforeRoofAndAllSidesArePhysicallySealed(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        List<BlockPos> shell = shelterShell(feet);
        BlockPos roof = feet.up(2);
        require(context, shell.stream().allMatch(pos -> context.getWorld().getBlockState(pos).isAir()),
                "shelter fixture was not initially open");
        require(context, Direction.stream().noneMatch(direction ->
                        !context.getWorld().getBlockState(roof.offset(direction)).isAir()),
                "roof fixture unexpectedly had placement support");

        AIPlayerEntity bot = spawn(context, "ShelterSealGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_seal"));

        int[] sealedAt = {-1};
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe());
            }
            if (sealedAt[0] < 0 && shell.stream().allMatch(pos -> isSealed(context, pos))) {
                sealedAt[0] = task.elapsedTicks();
                require(context, task.state() == TaskState.RUNNING,
                        "shelter published terminal state at the first sealed tick");
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, sealedAt[0] >= 0, "shelter exited without first proving a sealed envelope");
            require(context, task.elapsedTicks() - sealedAt[0] >= 100,
                    "shelter did not hold its sealed envelope for 100 ticks");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterSealGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 500)
    public void emergencyShelterBuildsAFoundationFromASingleSupportedLanding(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -1; dy <= 3; dy++) {
                    context.getWorld().setBlockState(feet.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        context.getWorld().setBlockState(feet.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        List<BlockPos> foundations = new ArrayList<>(4);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            foundations.add(feet.down().offset(direction).toImmutable());
        }
        require(context, foundations.stream().allMatch(pos -> context.getWorld().getBlockState(pos).isAir()),
                "single-pillar fixture unexpectedly had a side foundation");

        AIPlayerEntity bot = spawn(context, "ShelterPillarGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 24));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_single_pillar"));

        int[] sealedAt = {-1};
        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("single-pillar shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe());
            }
            if (task.state() != TaskState.COMPLETED) {
                if (sealedAt[0] < 0 && shelterShell(feet).stream()
                        .allMatch(pos -> isSealed(context, pos))) {
                    sealedAt[0] = task.elapsedTicks();
                }
                return;
            }
            for (BlockPos cell : foundations) {
                require(context, isSealed(context, cell),
                        "single-pillar shelter omitted foundation " + cell.toShortString());
            }
            require(context, sealedAt[0] >= 0, "single-pillar shelter never proved a full seal");
            require(context, task.elapsedTicks() - sealedAt[0] >= 100,
                    "single-pillar shelter skipped its hold interval");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterPillarGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 20)
    public void emergencyShelterRejectsInsufficientFoundationBudgetBeforeWorldMutation(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clearVolume(context, feet, 3);
        context.getWorld().setBlockState(feet.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        List<BlockPos> required = new ArrayList<>(14);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            required.add(feet.down().offset(direction));
        }
        required.add(feet.up(2).north());
        required.addAll(shelterShell(feet));
        require(context, required.stream().distinct().count() == 14L,
                "shelter preflight fixture did not contain fourteen distinct placements");

        AIPlayerEntity bot = spawn(context, "ShelterBudgetGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 13));
        InventoryAction.giveItem(bot, new ItemStack(Items.CRAFTING_TABLE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.FURNACE, 1));
        InventoryAction.giveItem(bot, new ItemStack(Items.OBSIDIAN, 1));
        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);

        require(context, task.state() == TaskState.FAILED,
                "thirteen-block shelter budget was not rejected before execution");
        require(context, task.failureReason().equals("missing shelter_blocks required=14 available=13"),
                "unexpected shelter preflight reason: " + task.failureReason());
        require(context, required.stream().allMatch(pos -> context.getWorld().getBlockState(pos).isAir()),
                "shelter preflight mutated the world before rejecting its budget");
        int remaining = bot.getInventory().main.stream()
                .filter(stack -> stack.isOf(Items.DIRT))
                .mapToInt(ItemStack::getCount)
                .sum();
        require(context, remaining == 13,
                "shelter preflight consumed blocks: remaining=" + remaining);
        require(context, InventoryAction.countItem(bot, Items.CRAFTING_TABLE) == 1
                        && InventoryAction.countItem(bot, Items.FURNACE) == 1
                        && InventoryAction.countItem(bot, Items.OBSIDIAN) == 1,
                "shelter palette consumed a workstation or mission output");
        finish(context, bot, "ShelterBudgetGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 300)
    public void partialShelterFailureOpensOwnedDoorwayBeforePublishingFailure(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        BlockPos doorwayFeet = feet.north();
        BlockPos doorwayHead = doorwayFeet.up();

        AIPlayerEntity bot = spawn(context, "ShelterFailureExitGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_failure_exit"));

        boolean[] inventoryDrained = {false};
        context.runAtEveryTick(() -> {
            if (!inventoryDrained[0]
                    && isSealed(context, doorwayFeet)
                    && isSealed(context, doorwayHead)
                    && shelterShell(feet).stream().anyMatch(pos -> !isSealed(context, pos))) {
                int dirt = InventoryAction.countItem(bot, Items.DIRT);
                require(context, dirt > 0 && InventoryAction.removeItems(bot, Items.DIRT, dirt),
                        "failed to trigger deterministic partial-build exhaustion");
                inventoryDrained[0] = true;
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, inventoryDrained[0], "fixture never reached a partial owned doorway");
            require(context, task.state() == TaskState.FAILED,
                    "partial shelter ended as " + task.state());
            require(context, task.failureReason().equals("missing shelter_block"),
                    "unexpected partial shelter failure: " + task.failureReason());
            require(context, context.getWorld().getBlockState(doorwayFeet).isAir()
                            && context.getWorld().getBlockState(doorwayHead).isAir(),
                    "failure published before its owned doorway was physically mined");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterFailureExitGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 120)
    public void displacedPartialShelterReleasesItsStaleAnchorWithoutSpinning(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterDisplacedGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_displaced"));

        boolean[] displaced = {false};
        int[] displacedAt = {-1};
        context.runAtEveryTick(() -> {
            if (!displaced[0]
                    && task.state() == TaskState.RUNNING
                    && shelterShell(feet).stream().anyMatch(pos -> isSealed(context, pos))) {
                BlockPos safeOutside = feet.east(3);
                bot.teleport(context.getWorld(), safeOutside.getX() + 0.5D, safeOutside.getY(),
                        safeOutside.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
                bot.setOnGround(true);
                displaced[0] = true;
                displacedAt[0] = task.elapsedTicks();
                return;
            }
            if (!displaced[0] || task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_anchor_displaced".equals(task.failureReason()),
                    "displaced shelter ended unexpectedly: "
                            + task.state() + ":" + task.failureReason());
            require(context, task.elapsedTicks() - displacedAt[0] <= 2,
                    "displaced shelter spun instead of releasing its anchor: elapsed="
                            + (task.elapsedTicks() - displacedAt[0]));
            require(context, Standability.isStandable(context.getWorld(), bot.getBlockPos()),
                    "displaced shelter released an unsafe pose: " + bot.getBlockPos().toShortString());
            finish(context, bot, "ShelterDisplacedGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 40)
    public void shelterMaterialLossBeforeFirstPlacementFailsWithoutInventingExitDebt(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clearVolume(context, feet, 3);
        context.getWorld().setBlockState(feet.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "ShelterPrebuildLossGT", feet);
        BlockPos taskStart = bot.getBlockPos().toImmutable();
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 14));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_prebuild_loss"));
        require(context, InventoryAction.removeItems(bot, Items.DIRT, 14),
                "failed to remove shelter material after the successful preflight");

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && task.failureReason().equals("missing shelter_block"),
                    "prebuild material loss ended unexpectedly: "
                            + task.state() + ":" + task.failureReason());
            BlockPos actual = bot.getBlockPos();
            require(context, actual.getX() == taskStart.getX()
                            && actual.getZ() == taskStart.getZ(),
                    "open prebuild failure invented an unsupported exit step");
            require(context, Math.abs(actual.getY() - taskStart.getY()) <= 1,
                    "prebuild fixture suffered unexpected vertical displacement");
            require(context, shelterShell(feet).stream()
                            .allMatch(pos -> context.getWorld().getBlockState(pos).isAir()),
                    "prebuild material loss changed the shelter envelope");
            finish(context, bot, "ShelterPrebuildLossGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 400)
    public void shelterExitNeverMinesPreexistingWorldBlocks(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        BlockPos preexistingFeet = feet.north();
        BlockPos preexistingHead = preexistingFeet.up();
        context.getWorld().setBlockState(preexistingFeet,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(preexistingHead,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "ShelterOwnershipGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_ownership"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("ownership shelter ended as " + task.state()
                        + ":" + task.failureReason());
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, context.getWorld().getBlockState(preexistingFeet).isOf(Blocks.STONE)
                            && context.getWorld().getBlockState(preexistingHead).isOf(Blocks.STONE),
                    "shelter exit mined a preexisting world block");
            require(context, bot.getBlockPos().equals(feet.east()),
                    "shelter did not choose the first ownable doorway away from preexisting stone: "
                            + bot.getBlockPos().toShortString());
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterOwnershipGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 400)
    public void shelterSkipsFoundationHiddenBelowAPreexistingTunnelWall(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        BlockPos northFeet = feet.north();
        BlockPos northHead = northFeet.up();
        BlockPos impossibleFoundation = northFeet.down();
        context.getWorld().setBlockState(northFeet,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(northHead,
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(impossibleFoundation,
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "ShelterTunnelWallGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_tunnel_wall"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.COMPLETED,
                    "natural-wall shelter ended as " + task.state() + ":" + task.failureReason());
            require(context, context.getWorld().getBlockState(northFeet).isOf(Blocks.STONE)
                            && context.getWorld().getBlockState(northHead).isOf(Blocks.STONE),
                    "shelter changed its preexisting tunnel wall");
            require(context, context.getWorld().getBlockState(impossibleFoundation).isAir(),
                    "shelter built a useless foundation below a sealed natural wall");
            assertPhysicalShelterExit(context, bot, feet);
            finish(context, bot, "ShelterTunnelWallGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterLifecycleLive", tickLimit = 400)
    public void shelterOpensItsOwnedDoorBeforeFailingWhenExitSupportDisappears(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        clearVolume(context, feet, 3);
        context.getWorld().setBlockState(feet.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST)) {
            context.getWorld().setBlockState(feet.offset(direction),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(feet.up().offset(direction),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        BlockPos doorwayFeet = feet.east();
        BlockPos doorwayHead = doorwayFeet.up();
        BlockPos doorwaySupport = doorwayFeet.down();

        AIPlayerEntity bot = spawn(context, "ShelterLostExitSupportGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_lost_exit_support"));

        boolean[] removedSupport = {false};
        context.runAtEveryTick(() -> {
            if (!removedSupport[0]
                    && isSealed(context, doorwayFeet)
                    && isSealed(context, doorwayHead)) {
                require(context, context.getWorld().getBlockState(doorwaySupport).isOf(Blocks.DIRT),
                        "fixture never observed the owned exit foundation");
                context.getWorld().setBlockState(doorwaySupport,
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                removedSupport[0] = true;
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, removedSupport[0], "fixture never removed the exit support");
            require(context, task.state() == TaskState.FAILED,
                    "unsupported-exit shelter ended as " + task.state());
            require(context, task.failureReason().equals("shelter_exit_not_standable"),
                    "unexpected unsupported-exit reason: " + task.failureReason());
            require(context, bot.getBlockPos().equals(feet),
                    "unsupported exit invented a ravine step: " + bot.getBlockPos().toShortString());
            require(context, context.getWorld().getBlockState(doorwayFeet).isAir()
                            && context.getWorld().getBlockState(doorwayHead).isAir(),
                    "shelter failed before reopening its owned unsupported doorway");
            for (Direction direction : List.of(Direction.NORTH, Direction.SOUTH, Direction.WEST)) {
                require(context, context.getWorld().getBlockState(feet.offset(direction)).isOf(Blocks.STONE)
                                && context.getWorld().getBlockState(feet.up().offset(direction)).isOf(Blocks.STONE),
                        "shelter mined a preexisting tunnel wall at " + direction);
            }
            finish(context, bot, "ShelterLostExitSupportGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void standableStepRejectsUnsupportedAndWaterCells(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 5, 12));
        clearVolume(context, start, 3);
        context.getWorld().setBlockState(start.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos unsupported = start.north();
        BlockPos water = start.east();
        context.getWorld().setBlockState(water.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(water,
                Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "StandableStepGT", start);
        require(context, !FakePlayerMotion.stepToStandable(bot, unsupported, "gametest_unsupported"),
                "standable step entered an unsupported air cell");
        require(context, bot.getBlockPos().equals(start),
                "unsupported step moved the bot: " + bot.getBlockPos().toShortString());
        require(context, !FakePlayerMotion.stepToStandable(bot, water, "gametest_water"),
                "standable step entered a water cell");
        require(context, bot.getBlockPos().equals(start),
                "water step moved the bot: " + bot.getBlockPos().toShortString());
        finish(context, bot, "StandableStepGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void adjacentFakePlayerStepRejectsAnEntityOccupiedLanding(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(12, 5, 12));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "OccupiedStepGT", start);
        BlockPos occupied = start.east();
        ZombieEntity zombie = EntityType.ZOMBIE.create(context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            finish(context, bot, "OccupiedStepGT");
            context.throwGameTestException("failed to create occupied landing fixture");
            return;
        }
        zombie.setPersistent();
        zombie.refreshPositionAndAngles(occupied.getX() + 0.5D, occupied.getY(),
                occupied.getZ() + 0.5D, 0.0F, 0.0F);
        require(context, context.getWorld().spawnEntity(zombie),
                "failed to spawn occupied landing fixture");

        require(context, !FakePlayerMotion.stepToStandable(bot, occupied, "gametest_occupied"),
                "fake-player step entered an entity-occupied landing");
        require(context, bot.getBlockPos().equals(start),
                "occupied landing moved the bot: " + bot.getBlockPos().toShortString());

        zombie.discard();
        finish(context, bot, "OccupiedStepGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void descendLateralDetourRejectsUnsupportedAirShaft(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 8, 20));
        clearVolume(context, start, 4);
        context.getWorld().setBlockState(start.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos safeEast = start.east();
        context.getWorld().setBlockState(safeEast.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DescendDetourGT", start);
        DescendToYTask task = new DescendToYTask(start.getY() - 5);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING,
                "descend detour ended unexpectedly: " + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(safeEast),
                "descend detour did not skip the unsupported north shaft: "
                        + start.toShortString() + " -> " + bot.getBlockPos().toShortString());
        require(context, bot.getBlockPos().getY() >= start.getY() - 1,
                "descend detour fell below its verified landing");
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendDetourGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void descendLateralDetourKeepsHeadingInsteadOfReversingInTwoCellLoop(
            TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(12, 8, 20));
        clearVolume(context, start, 4);
        BlockPos north = start.north();
        BlockPos westExit = north.west();
        for (BlockPos landing : List.of(start, north, westExit)) {
            context.getWorld().setBlockState(landing.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DescendHeadingGT", start);
        DescendToYTask task = new DescendToYTask(start.getY() - 5);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(north),
                "first lateral move did not enter the north corridor: "
                        + start.toShortString() + " -> " + bot.getBlockPos().toShortString());

        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING,
                "heading-aware detour ended unexpectedly: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().equals(westExit),
                "detour reversed south instead of trying the west exit: "
                        + north.toShortString() + " -> " + bot.getBlockPos().toShortString());
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendHeadingGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void descendLateralDetourNeverReplaysATraversedDirectedEdge(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(18, 8, 20));
        clearVolume(context, start, 4);
        BlockPos north = start.north();
        for (BlockPos landing : List.of(start, north)) {
            context.getWorld().setBlockState(landing.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DescendEdgeLoopGT", start);
        DescendToYTask task = new DescendToYTask(start.getY() - 5);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(north),
                "fixture did not traverse A->B: " + bot.getBlockPos().toShortString());

        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING && bot.getBlockPos().equals(start),
                "fixture did not permit the one necessary B->A backtrack: "
                        + task.state() + ":" + task.failureReason()
                        + " at=" + bot.getBlockPos().toShortString());
        Map<String, String> afterBacktrack = task.checkpoint();
        require(context, "2".equals(afterBacktrack.get("lateral_detours"))
                        && afterBacktrack.getOrDefault("traversed_detour_edges", "")
                        .split(";", -1).length == 2,
                "directed detour edges were not durably recorded: " + afterBacktrack);

        task.cancel(bot, "gametest_restart");
        DescendToYTask restored = new DescendToYTask(start.getY() - 5, afterBacktrack);
        restored.start(bot);
        restored.tick(bot);
        require(context, restored.state() == TaskState.FAILED
                        && restored.failureReason().startsWith("descend_no_safe_landing"),
                "restored two-cell detour did not fail closed after exhausting fresh edges: "
                        + restored.state() + ":" + restored.failureReason());
        require(context, bot.getBlockPos().equals(start),
                "restored Descend replayed A->B after A->B->A: "
                        + bot.getBlockPos().toShortString());
        require(context, "2".equals(restored.checkpoint().get("lateral_detours")),
                "restored two-cell loop consumed the full lateral budget: "
                        + restored.checkpoint());
        finish(context, bot, "DescendEdgeLoopGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "descendCollapsedDetourLive", tickLimit = 180)
    public void descendRollsBackCollapsedSameLevelDetourAndRetriesFromOrigin(
            TestContext context) {
        BlockPos origin = context.getAbsolutePos(new BlockPos(24, 10, 20));
        clearVolume(context, origin, 4);
        BlockPos detour = origin.north();
        for (BlockPos landing : List.of(origin, detour)) {
            context.getWorld().setBlockState(landing.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }

        AIPlayerEntity bot = spawn(context, "DescendDetourRollbackGT", origin);
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SHOVEL));
        DescendToYTask task = new DescendToYTask(origin.getY() - 5);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(detour),
                "fixture did not issue its same-level detour: " + bot.getBlockPos());
        require(context, "1".equals(task.checkpoint().get("lateral_detours")),
                "issued detour was not recorded: " + task.checkpoint());

        float healthBefore = bot.getHealth();
        context.getWorld().setBlockState(detour,
                Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        task.tick(bot);

        require(context, task.state() == TaskState.RUNNING && bot.getBlockPos().equals(origin),
                "collapsed detour did not physically retreat to its factual origin: "
                        + task.state() + ":" + task.failureReason()
                        + " at=" + bot.getBlockPos().toShortString());
        Map<String, String> rolledBack = task.checkpoint();
        require(context, "0".equals(rolledBack.get("lateral_detours"))
                        && rolledBack.getOrDefault("traversed_detour_edges", "").isEmpty(),
                "collapsed detour retained uncommitted graph debt: " + rolledBack);
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "detour rollback lost health before retreat");

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                task.tick(bot);
            }
            require(context, task.state() != TaskState.FAILED,
                    "detour retry failed after rollback: " + task.failureReason());
            if (!bot.getBlockPos().equals(detour)) {
                return;
            }
            Map<String, String> retried = task.checkpoint();
            require(context, "1".equals(retried.get("lateral_detours"))
                            && !retried.getOrDefault("traversed_detour_edges", "").isEmpty(),
                    "cleared detour was not retried transactionally: " + retried);
            require(context, context.getWorld().getBlockState(detour).isAir(),
                    "retry entered the detour before physically clearing gravel");
            require(context, bot.getHealth() == healthBefore,
                    "bot lost health while clearing and retrying the detour");
            task.cancel(bot, "gametest_complete");
            finish(context, bot, "DescendDetourRollbackGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "descendCollapsedUpperDetourRestore", tickLimit = 80)
    public void descendRestoredUpperDetourRetreatsDownToPersistedOrigin(
            TestContext context) {
        BlockPos origin = context.getAbsolutePos(new BlockPos(30, 9, 20));
        clearVolume(context, origin, 4);
        context.getWorld().setBlockState(origin.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos upperDetour = origin.north().up();
        // The solid north cell supports only the upper retreat; its own lower support remains air,
        // so the same-level phase cannot consume the candidate first.
        context.getWorld().setBlockState(origin.north(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DescendUpperRollbackGT", origin);
        DescendToYTask first = new DescendToYTask(origin.getY() - 5);
        first.start(bot);
        first.tick(bot);
        require(context, bot.getBlockPos().equals(upperDetour),
                "fixture did not issue the upper detour: " + bot.getBlockPos());
        Map<String, String> checkpoint = first.checkpoint();
        require(context, "1".equals(checkpoint.get("lateral_detours"))
                        && !checkpoint.getOrDefault("traversed_detour_edges", "").isEmpty(),
                "upper detour checkpoint did not retain its edge: " + checkpoint);
        first.cancel(bot, "gametest_restart");

        context.getWorld().setBlockState(upperDetour,
                Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        float healthBefore = bot.getHealth();
        DescendToYTask restored = new DescendToYTask(origin.getY() - 5, checkpoint);
        restored.start(bot);
        restored.tick(bot);

        require(context, restored.state() == TaskState.RUNNING
                        && bot.getBlockPos().equals(origin),
                "restored upper detour did not take the exact diagonal-down retreat: "
                        + restored.state() + ":" + restored.failureReason()
                        + " at=" + bot.getBlockPos().toShortString());
        Map<String, String> rolledBack = restored.checkpoint();
        require(context, "0".equals(rolledBack.get("lateral_detours"))
                        && rolledBack.getOrDefault("traversed_detour_edges", "").isEmpty(),
                "restored upper collapse retained graph debt: " + rolledBack);
        require(context, !NavSafetyNet.INSTANCE.isWaterRescueActive(bot),
                "restored physical retreat incorrectly delegated to NavSafetyNet");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "restored upper retreat lost health");

        restored.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendUpperRollbackGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 60)
    public void descendLateralBudgetResetsOnlyAfterAConfirmedLowerLanding(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 12, 20));
        List<BlockPos> upperCorridor = new ArrayList<>();
        for (int dz = 0; dz <= 23; dz++) {
            upperCorridor.add(start.north(dz));
        }
        BlockPos upperLanding = upperCorridor.get(upperCorridor.size() - 1).east().down();
        BlockPos lowerOne = upperLanding.east();
        BlockPos lowerTwo = lowerOne.east();
        BlockPos targetLanding = lowerTwo.south().down();
        List<BlockPos> standingCells = new ArrayList<>(upperCorridor);
        standingCells.add(upperLanding);
        standingCells.add(lowerOne);
        standingCells.add(lowerTwo);
        standingCells.add(targetLanding);
        for (BlockPos standing : standingCells) {
            context.getWorld().setBlockState(standing, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(standing.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(standing.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        }
        // The two real lower landings must be air with separate solid support. Flat corridor cells
        // intentionally use their y-1 stone as current-level footing, which makes diagonal descent
        // unsupported and forces physical lateral movement.
        context.getWorld().setBlockState(upperLanding, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(targetLanding, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DescBudgetGT", start);
        DescendToYTask task = new DescendToYTask(targetLanding.getY());
        task.start(bot);
        int maxSameLayerDebt = 0;
        for (int tick = 0; tick < 55 && task.state() == TaskState.RUNNING; tick++) {
            task.tick(bot);
            maxSameLayerDebt = Math.max(maxSameLayerDebt,
                    Integer.parseInt(task.checkpoint().get("lateral_detours")));
        }

        require(context, task.state() == TaskState.COMPLETED,
                "cross-layer detour budget did not complete: "
                        + task.state() + ":" + task.failureReason()
                        + " at=" + bot.getBlockPos().toShortString());
        require(context, bot.getBlockPos().equals(targetLanding),
                "cross-layer detour ended at the wrong landing: expected="
                        + targetLanding.toShortString() + " actual=" + bot.getBlockPos().toShortString());
        require(context, maxSameLayerDebt == 23,
                "long cave-roof fixture did not cross its 23-edge supported rim: max_debt="
                        + maxSameLayerDebt);
        require(context, "0".equals(task.checkpoint().get("lateral_detours"))
                        && "".equals(task.checkpoint().get("traversed_detour_edges")),
                "confirmed lower landing retained stale detour history: " + task.checkpoint());
        finish(context, bot, "DescBudgetGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void descendNeverCompletesBelowTarget(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(20, 12, 20));
        clearVolume(context, start, 10);
        context.getWorld().setBlockState(start.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        int targetY = start.getY() - 3;
        BlockPos overshot = new BlockPos(start.getX(), targetY - 5, start.getZ());
        context.getWorld().setBlockState(overshot.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DescendOvershootGT", start);
        DescendToYTask task = new DescendToYTask(targetY);
        task.start(bot);
        bot.teleport(context.getWorld(), overshot.getX() + 0.5D, overshot.getY(),
                overshot.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true);
        task.tick(bot);

        require(context, task.state() == TaskState.FAILED,
                "descend completed or continued below its target: " + task.state());
        require(context, task.failureReason().startsWith("descend_overshoot_unrecoverable"),
                "unexpected overshoot reason: " + task.failureReason());
        require(context, bot.getActionPack().isPathExecutorIdle(),
                "overshot descend left a path action active");
        finish(context, bot, "DescendOvershootGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void strictSuffocationDenialFallsBackToAdjacentPhysicalExit(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(12, 12, 12));
        clearVolume(context, start, 4);
        preparePlatform(context, start, 3);
        AIPlayerEntity bot = spawn(context, "StrictSuffocationGT", start);
        bot.setHealth(bot.getMaxHealth());
        var staleRoute = bot.getActionPack().startSurfacePathTo(start.east(2));
        require(context, !staleRoute.isFailed() && !bot.getActionPack().isPathExecutorIdle(),
                "fixture did not open the route that must be retired after suffocation recovery");
        BlockPos blockedHead = start.up();
        context.getWorld().setBlockState(
                blockedHead, Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        require(context, !CapabilityRuntime.decide(
                        bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                        "strict_suffocation_gametest").allowed(),
                "strict_survival unexpectedly allowed emergency teleport");
        require(context, Standability.isStandable(context.getWorld(), start.up(2)),
                "fixture must expose the upward standable candidate that triggers denied teleport");
        float healthBefore = bot.getHealth();

        boolean handled = NavSafetyNet.INSTANCE.tickBot(context.getWorld().getServer(), bot);

        BlockPos after = bot.getBlockPos();
        int horizontal = Math.abs(after.getX() - start.getX())
                + Math.abs(after.getZ() - start.getZ());
        require(context, handled,
                "strict suffocation denial did not fall through to physical recovery");
        require(context, after.getY() == start.getY() && horizontal == 1,
                "suffocation recovery was not one adjacent physical step: "
                        + start.toShortString() + " -> " + after.toShortString());
        require(context, Standability.isStandable(context.getWorld(), after),
                "physical suffocation exit was not standable: " + after.toShortString());
        require(context, context.getWorld().getBlockState(blockedHead).isOf(Blocks.GRAVEL),
                "physical suffocation exit silently removed the obstruction");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "bot took damage before the adjacent suffocation exit");
        require(context, bot.getActionPack().isPathExecutorIdle()
                        && bot.getActionPack().isWalkToIdle()
                        && bot.getActionPack().isMiningIdle(),
                "suffocation recovery left the stale route able to replay its blocked edge");

        NavSafetyNet.INSTANCE.clear(bot);
        finish(context, bot, "StrictSuffocationGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void descendPhysicallyRetreatsWhenGravelReoccupiesItsHeadInStrictSurvival(
            TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(20, 12, 12));
        clearVolume(context, start, 4);
        BlockPos buriedLanding = start.north().down();
        BlockPos alternateLanding = start.east().down();
        for (BlockPos landing : List.of(start, buriedLanding, alternateLanding)) {
            context.getWorld().setBlockState(landing.down(),
                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(landing,
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            context.getWorld().setBlockState(landing.up(),
                    Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        }
        // Keep the following north stair solid so the second task tick confirms the first landing
        // without immediately descending a second level.
        context.getWorld().setBlockState(buriedLanding.north(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(buriedLanding.north().down(2),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DescendGravelGT", start);
        bot.setHealth(bot.getMaxHealth());
        bot.setOnGround(true);
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                        "descend_gravel_gametest").allowed(),
                "strict_survival unexpectedly allowed emergency teleport");

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        task.start(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(buriedLanding),
                "fixture did not enter its first factual stair: "
                        + start.toShortString() + " -> " + bot.getBlockPos().toShortString());
        task.tick(bot);
        require(context, task.state() == TaskState.RUNNING
                        && bot.getBlockPos().equals(buriedLanding),
                "fixture did not hold the confirmed landing before collapse: "
                        + task.state() + ":" + task.failureReason());

        BlockPos reoccupiedHead = buriedLanding.up();
        context.getWorld().setBlockState(reoccupiedHead,
                Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
        Standability.clearCache();
        require(context, !context.getWorld().getBlockState(reoccupiedHead)
                        .getCollisionShape(context.getWorld(), reoccupiedHead).isEmpty(),
                "gravel fixture did not reoccupy the bot's head cell");
        float healthBefore = bot.getHealth();
        BlockPos before = bot.getBlockPos().toImmutable();

        task.tick(bot);

        BlockPos after = bot.getBlockPos();
        int dx = Math.abs(after.getX() - before.getX());
        int dy = Math.abs(after.getY() - before.getY());
        int dz = Math.abs(after.getZ() - before.getZ());
        int changedAxes = (dx == 0 ? 0 : 1) + (dy == 0 ? 0 : 1) + (dz == 0 ? 0 : 1);
        require(context, after.equals(start),
                "descend did not retreat to its previous factual landing: "
                        + before.toShortString() + " -> " + after.toShortString());
        require(context, dx <= 1 && dy <= 1 && dz <= 1 && changedAxes == 2,
                "blocked-body recovery used a non-adjacent movement: " + before + " -> " + after);
        require(context, context.getWorld().getBlockState(reoccupiedHead).isOf(Blocks.GRAVEL),
                "retreat fixture was silently cleared instead of exited physically");
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "bot took suffocation damage before physical retreat");
        require(context, task.state() == TaskState.RUNNING,
                "descend ended during recoverable gravel collapse: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getActionPack().isMiningIdle(),
                "blocked-body retreat left the abandoned stair miner active");

        // The collapsed NORTH edge is durable for this origin. One tick rotates, the next enters
        // the prepared EAST stair; blindly retrying NORTH would bury the bot again.
        task.tick(bot);
        task.tick(bot);
        require(context, bot.getBlockPos().equals(alternateLanding),
                "descend retried the collapsed edge instead of rotating to the safe stair: "
                        + bot.getBlockPos().toShortString());
        require(context, bot.isAlive() && bot.getHealth() == healthBefore,
                "bot lost health after rotating away from the collapsed stair");

        task.cancel(bot, "gametest_complete");
        finish(context, bot, "DescendGravelGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 40)
    public void descendMinesItsOccupiedHeadWhenNoPhysicalRetreatLandingExists(
            TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(20, 12, 4));
        clearVolume(context, start, 4);
        context.getWorld().setBlockState(start.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        BlockPos occupiedHead = start.up();
        context.getWorld().setBlockState(occupiedHead,
                Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DescendClearHeadGT", start);
        bot.setHealth(bot.getMaxHealth());
        bot.setOnGround(true);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIAMOND_SHOVEL));
        require(context, AIBotConfig.get().profile() == OperatingProfile.STRICT_SURVIVAL,
                "GameTest must run under strict_survival, got " + AIBotConfig.get().profile());
        require(context, !CapabilityRuntime.decide(bot, PrivilegedCapability.EMERGENCY_TELEPORT,
                        "descend_clear_head_gametest").allowed(),
                "strict_survival unexpectedly allowed emergency teleport");

        DescendToYTask task = new DescendToYTask(start.getY() - 2);
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_descend_clear_head"));
        context.runAtEveryTick(() -> {
            require(context, bot.isAlive() && bot.getHealth() == bot.getMaxHealth(),
                    "bot took damage while clearing its occupied head cell");
            require(context, bot.getBlockPos().equals(start),
                    "head-clear fallback moved without a factual retreat landing: "
                            + start.toShortString() + " -> " + bot.getBlockPos().toShortString());
            if (!context.getWorld().getBlockState(occupiedHead).isAir()) {
                return;
            }
            require(context, bot.getActionPack().isPathExecutorIdle(),
                    "head-clear fallback started a navigation path");
            finish(context, bot, "DescendClearHeadGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 20)
    public void defensiveCombatDoesNotDescendTowardAHostileBelowItsAnchorFloor(TestContext context) {
        BlockPos anchor = context.getAbsolutePos(new BlockPos(4, 8, 4));
        preparePlatform(context, anchor, 2);
        BlockPos hostileFeet = anchor.add(2, -3, 0);
        context.getWorld().setBlockState(hostileFeet.down(),
                Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(hostileFeet, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(hostileFeet.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);

        AIPlayerEntity bot = spawn(context, "DefCombatGT", anchor);
        ZombieEntity zombie = EntityType.ZOMBIE.create(context.getWorld(), SpawnReason.COMMAND);
        if (zombie == null) {
            finish(context, bot, "DefCombatGT");
            context.throwGameTestException("failed to create defensive hostile fixture");
            return;
        }
        zombie.setPersistent();
        zombie.refreshPositionAndAngles(hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                hostileFeet.getZ() + 0.5D, 0.0F, 0.0F);
        context.getWorld().spawnEntity(zombie);

        CombatTask task = CombatTask.defensive(zombie, 6.0F, anchor);
        task.start(bot);
        task.tick(bot);

        require(context, task.state() == TaskState.COMPLETED,
                "defensive combat did not cleanly disengage from the lower hostile: "
                        + task.state() + ":" + task.failureReason());
        require(context, bot.getBlockPos().getY() == anchor.getY(),
                "defensive combat descended below its anchor: "
                        + anchor.toShortString() + " -> " + bot.getBlockPos().toShortString());
        require(context, bot.getActionPack().isPathExecutorIdle(),
                "defensive combat started a path toward the lower hostile");

        zombie.discard();
        finish(context, bot, "DefCombatGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void unreachableDropRecoveryTypedFailsWithinItsNoProgressBudget(TestContext context) {
        BlockPos start = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, start, 2);
        AIPlayerEntity bot = spawn(context, "RecoverRouteGT", start);
        int worldTop = context.getWorld().getBottomY() + context.getWorld().getHeight();
        BlockPos unreachable = new BlockPos(start.getX(), worldTop + 10, start.getZ());
        RecoverDropsTask task = new RecoverDropsTask(
                unreachable, context.getWorld().getServer().getTicks());
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_recover_unreachable"));

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.RUNNING) {
                require(context, task.elapsedTicks() <= 120,
                        "unreachable recovery remained running beyond 120 ticks");
                return;
            }
            require(context, task.state() == TaskState.FAILED,
                    "unreachable recovery ended as " + task.state());
            require(context, task.failureReason().startsWith("recover_route_unreachable:"),
                    "unexpected unreachable recovery reason: " + task.failureReason());
            require(context, task.elapsedTicks() <= 120,
                    "typed route failure exceeded 120 ticks: " + task.elapsedTicks());
            require(context, bot.getBlockPos().equals(start),
                    "unreachable recovery moved away from its start: "
                            + start.toShortString() + " -> " + bot.getBlockPos().toShortString());
            finish(context, bot, "RecoverRouteGT");
        });
    }

    private static List<BlockPos> shelterShell(BlockPos feet) {
        List<BlockPos> shell = new ArrayList<>(9);
        shell.add(feet.up(2));
        for (Direction direction : Direction.Type.HORIZONTAL) {
            shell.add(feet.offset(direction));
            shell.add(feet.up().offset(direction));
        }
        return List.copyOf(shell);
    }

    private static boolean isSealed(TestContext context, BlockPos pos) {
        var state = context.getWorld().getBlockState(pos);
        return !state.isReplaceable()
                && !state.getCollisionShape(context.getWorld(), pos).isEmpty();
    }

    private static void assertPhysicalShelterExit(TestContext context,
                                                  AIPlayerEntity bot,
                                                  BlockPos shelterFeet) {
        BlockPos actual = bot.getBlockPos();
        int horizontal = Math.abs(actual.getX() - shelterFeet.getX())
                + Math.abs(actual.getZ() - shelterFeet.getZ());
        require(context, actual.getY() == shelterFeet.getY() && horizontal == 1,
                "shelter terminal pose was not one physical adjacent exit step: "
                        + shelterFeet.toShortString() + " -> " + actual.toShortString());
        require(context, Standability.isStandable(context.getWorld(), actual),
                "shelter terminal exit was not standable: " + actual.toShortString());
        require(context, context.getWorld().getBlockState(actual).isAir()
                        && context.getWorld().getBlockState(actual.up()).isAir(),
                "shelter terminal exit did not leave a two-block opening");
    }

    private static void preparePlatform(TestContext context, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                context.getWorld().setBlockState(cell.down(),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static void clearVolume(TestContext context, BlockPos center, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    context.getWorld().setBlockState(center.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        return bot;
    }

    private static void finish(TestContext context, AIPlayerEntity bot, String name) {
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        DangerWatcher.INSTANCE.clear(bot);
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }
}
