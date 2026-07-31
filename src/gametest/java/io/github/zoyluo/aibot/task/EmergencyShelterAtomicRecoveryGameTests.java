package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.pathfinding.Standability;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Physical regressions for the shelter's ordered build and sealed healing transaction. */
public final class EmergencyShelterAtomicRecoveryGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterAnchorSettlement", tickLimit = 500)
    public void movingEdgeAnchorSettlesBeforeEnvelopePlacement(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterAnchorSettleGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        bot.teleport(context.getWorld(), feet.getX() + 0.78D, feet.getY(),
                feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        bot.setOnGround(true);
        bot.setVelocity(new Vec3d(0.18D, 0.0D, 0.0D));
        require(context, bot.getBlockPos().equals(feet)
                        && new net.minecraft.util.math.Box(feet.east())
                        .intersects(bot.getBoundingBox()),
                "moving-edge fixture did not overlap the east wall cell");

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_anchor_settlement"));
        require(context, Math.abs(bot.getX() - (feet.getX() + 0.5D)) < 1.0E-6D
                        && Math.abs(bot.getZ() - (feet.getZ() + 0.5D)) < 1.0E-6D
                        && bot.getVelocity().lengthSquared() <= 1.0E-8D,
                "shelter admission did not settle the moving edge pose");

        boolean[] eastWallSealed = {false};
        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("moving-edge shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe());
                return;
            }
            if (isSealed(context, feet.east()) && isSealed(context, feet.east().up())) {
                eastWallSealed[0] = true;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, eastWallSealed[0],
                    "settled shelter never sealed the formerly overlapping east wall");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterAnchorSettleGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterBuildSettlement", tickLimit = 30)
    public void buildTimeEdgeCorrectionPlacesWallInSameTick(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "ShelterBuildSettleGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        require(context, task.state() == TaskState.RUNNING,
                "build-time settlement fixture failed shelter admission");
        bot.teleport(context.getWorld(), feet.getX() + 0.5D, feet.getY(),
                feet.getZ() + 0.22D, Set.of(), 0.0F, 0.0F, false);
        bot.setOnGround(true);
        bot.setVelocity(Vec3d.ZERO);
        BlockPos northWall = feet.north();
        require(context, new Box(northWall).intersects(bot.getBoundingBox()),
                "build-time settlement fixture did not overlap its first wall");

        task.tick(bot);

        require(context, Math.abs(bot.getX() - (feet.getX() + 0.5D)) < 1.0E-6D
                        && Math.abs(bot.getZ() - (feet.getZ() + 0.5D)) < 1.0E-6D,
                "build-time edge correction did not return to the exact anchor center");
        require(context, isSealed(context, northWall),
                "successful edge correction ended the tick before wall placement");
        task.cancel(bot, "gametest_complete");
        finish(context, bot, "ShelterBuildSettleGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterWallBlocker", tickLimit = 600)
    public void persistentHostileGetsOneStrikeThenForcesPhysicalExit(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterWallBlockerGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD, 1));
        HuskEntity blocker = spawnHusk(context, feet.east());
        float blockerHealth = blocker.getHealth();

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_wall_blocker"));
        boolean[] struck = {false};
        float[] previousBlockerHealth = {blockerHealth};
        int[] damageEvents = {0};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (blocker.getHealth() < previousBlockerHealth[0]) {
                damageEvents[0]++;
                struck[0] = true;
                previousBlockerHealth[0] = blocker.getHealth();
            }
            require(context, damageEvents[0] <= 1,
                    "shelter repeatedly attacked a persistent wall blocker");
            require(context, blocker.isAlive(),
                    "shelter killed the blocker instead of releasing through another side");
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_wall_blocked_by_persistent_hostile"
                            .equals(task.failureReason()),
                    "persistent blocker produced the wrong terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, struck[0] && damageEvents[0] == 1,
                    "persistent blocker was not given exactly one physical clearance strike");
            require(context, !isSealed(context, feet.east()),
                    "shelter sealed the still-occupied east wall");
            assertPhysicalExit(context, bot, feet);
            blocker.discard();
            finish(context, bot, "ShelterWallBlockerGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterCenteredEntityGuard", tickLimit = 30)
    public void occupiedCenteredAabbRejectsSameCellCorrection(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "ShelterCenteredEntityGuardGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        bot.teleport(context.getWorld(), feet.getX() + 0.78D, feet.getY(),
                feet.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, false);
        double edgeX = bot.getX();
        BoatEntity occupant = EntityType.OAK_BOAT.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (occupant == null) {
            finish(context, bot, "ShelterCenteredEntityGuardGT");
            context.throwGameTestException("failed to create centered non-living occupant");
            return;
        }
        occupant.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                0.0F, 0.0F);
        require(context, context.getWorld().spawnEntity(occupant),
                "failed to spawn centered non-living occupant");

        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        require(context, task.state() == TaskState.FAILED
                        && "shelter_origin_not_centerable".equals(task.failureReason()),
                "occupied centered AABB did not reject shelter admission: "
                        + task.state() + ":" + task.failureReason());
        require(context, Math.abs(bot.getX() - edgeX) < 1.0E-6D,
                "center correction teleported through a collidable non-living entity");
        occupant.discard();
        finish(context, bot, "ShelterCenteredEntityGuardGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterAiPressure", tickLimit = 600)
    public void aiEnabledClosePressureUsesOneStrikeAndLowHealthBotSurvives(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterAiPressureGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD, 1));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_ai_pressure"));
        HuskEntity[] hostile = {null};
        float[] previousHostileHealth = {Float.NaN};
        int[] damageEvents = {0};
        int[] closePressureTicks = {0};
        boolean[] lowHealthInjected = {false};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (hostile[0] == null
                    && isSealed(context, feet.north())
                    && isSealed(context, feet.north().up())) {
                bot.setHealth(8.0F);
                lowHealthInjected[0] = true;
                hostile[0] = spawnAiHusk(context, feet.east(), bot);
                previousHostileHealth[0] = hostile[0].getHealth();
                require(context, !hostile[0].isAiDisabled(),
                        "close-pressure hostile unexpectedly had AI disabled");
                return;
            }
            if (hostile[0] != null) {
                hostile[0].setTarget(bot);
                if (hostile[0].getHealth() < previousHostileHealth[0]) {
                    damageEvents[0]++;
                    previousHostileHealth[0] = hostile[0].getHealth();
                }
                if (new Box(feet.east()).intersects(hostile[0].getBoundingBox())
                        || bot.squaredDistanceTo(hostile[0]) <= 2.25D) {
                    closePressureTicks[0]++;
                }
                require(context, damageEvents[0] <= 1,
                        "AI pressure caused repeated shelter attacks");
                require(context, hostile[0].isAlive(),
                        "AI pressure test relied on killing the hostile");
            }
            require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                    "low-health bot died under close shelter pressure");
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_wall_blocked_by_persistent_hostile"
                            .equals(task.failureReason()),
                    "AI pressure produced the wrong terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, lowHealthInjected[0]
                            && damageEvents[0] == 1
                            && closePressureTicks[0] >= 2,
                    "fixture did not prove sustained close pressure with one strike");
            require(context, bot.isAlive() && bot.getHealth() > 0.0F,
                    "low-health bot did not survive its physical release");
            assertPhysicalExit(context, bot, feet);
            hostile[0].discard();
            finish(context, bot, "ShelterAiPressureGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterForbiddenWallBlocker", tickLimit = 300)
    public void meleeForbiddenOccupiedEgressUsesAlternateOwnedExit(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterForbiddenBlockerGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));
        InventoryAction.giveItem(bot, new ItemStack(Items.STONE_SWORD, 1));
        CreeperEntity creeper = EntityType.CREEPER.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (creeper == null) {
            finish(context, bot, "ShelterForbiddenBlockerGT");
            context.throwGameTestException("failed to create forbidden wall blocker");
            return;
        }
        creeper.setPersistent();
        creeper.setAiDisabled(true);
        BlockPos blockedEgress = feet.north();
        creeper.refreshPositionAndAngles(
                blockedEgress.getX() + 0.5D, blockedEgress.getY(),
                blockedEgress.getZ() + 0.5D, 0.0F, 0.0F);
        require(context, context.getWorld().spawnEntity(creeper),
                "failed to spawn forbidden wall blocker");
        float creeperHealth = creeper.getHealth();

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_forbidden_wall_blocker"));
        boolean[] blockerReleasedAfterRejection = {false};
        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            require(context, creeper.getHealth() == creeperHealth,
                    "shelter performed forbidden melee against a Creeper");
            // OPEN_EXIT proves the adjacent Creeper was already classified as a forbidden
            // blocker and the alternate doorway transaction is committed. Release the fixture
            // now so the bot-wide DangerWatcher cannot start its own synchronous Creeper defense
            // after the shelter publishes the expected terminal in the same server tick.
            if (!blockerReleasedAfterRejection[0]
                    && task.state() == TaskState.RUNNING
                    && task.describe().contains("phase=OPEN_EXIT")) {
                blockerReleasedAfterRejection[0] = true;
                creeper.discard();
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_wall_blocked_by_melee_forbidden_hostile"
                            .equals(task.failureReason()),
                    "forbidden blocker produced the wrong terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, blockerReleasedAfterRejection[0],
                    "fixture never observed the committed alternate-exit transaction");
            require(context, bot.getBlockPos().equals(feet.east()),
                    "shelter entered the rejected Creeper egress instead of east: "
                            + bot.getBlockPos().toShortString());
            creeper.discard();
            finish(context, bot, "ShelterForbiddenBlockerGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterAtomicRecovery", tickLimit = 700)
    public void adjacentSecondShelterReusesResidualRoofWithoutBlockedSupportJump(
            TestContext context) {
        BlockPos firstFeet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, firstFeet, 5);
        AIPlayerEntity bot = spawn(context, "ShelterAdjacentGT", firstFeet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 40));

        EmergencyShelterTask first = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, first,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_adjacent_first"));
        EmergencyShelterTask[] second = {null};
        BlockPos[] secondFeet = {null};
        BlockPos[] inheritedCenterRoof = {null};
        BlockPos[] unnecessarySecondRim = {null};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (second[0] == null) {
                if (first.state() == TaskState.FAILED || first.state() == TaskState.CANCELLED) {
                    context.throwGameTestException("first shelter ended as " + first.state()
                            + ":" + first.failureReason() + " " + first.describe());
                    return;
                }
                BlockPos firstRoof = firstFeet.up(2);
                // Once OPEN_EXIT starts, the north head cell is intentionally mined while the
                // center roof remains. Only the BUILD/HOLD window can prove construction order.
                boolean envelopeOwned = first.describe().contains("phase=BUILD")
                        || first.describe().contains("phase=HOLD");
                if (envelopeOwned && isSealed(context, firstRoof)) {
                    require(context, isSealed(context, firstFeet.up().north()),
                            "center roof appeared before roofSupportBase");
                    require(context, isSealed(context, firstRoof.north()),
                            "center roof appeared before roofSupport");
                }
                if (first.state() != TaskState.COMPLETED) {
                    return;
                }

                secondFeet[0] = bot.getBlockPos().toImmutable();
                require(context, secondFeet[0].equals(firstFeet.north()),
                        "first shelter did not leave through its deterministic north doorway: "
                                + secondFeet[0].toShortString());
                inheritedCenterRoof[0] = secondFeet[0].up(2).toImmutable();
                unnecessarySecondRim[0] = inheritedCenterRoof[0].north().toImmutable();
                require(context, context.getWorld().getBlockState(inheritedCenterRoof[0])
                                .isOf(Blocks.DIRT),
                        "first shelter did not leave its rim as the adjacent center roof");
                require(context, context.getWorld().getBlockState(unnecessarySecondRim[0]).isAir(),
                        "adjacent fixture unexpectedly began with a second rim");

                second[0] = new EmergencyShelterTask();
                TaskManager.INSTANCE.assign(bot, second[0],
                        TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                                "gametest_shelter_adjacent_second"));
                return;
            }

            if (second[0].state() == TaskState.FAILED
                    || second[0].state() == TaskState.CANCELLED) {
                context.throwGameTestException("adjacent second shelter ended as "
                        + second[0].state() + ":" + second[0].failureReason()
                        + " " + second[0].describe());
                return;
            }
            if (second[0].state() != TaskState.COMPLETED) {
                return;
            }
            require(context, context.getWorld().getBlockState(inheritedCenterRoof[0])
                            .isOf(Blocks.DIRT),
                    "second shelter replaced or removed its inherited center roof");
            require(context, context.getWorld().getBlockState(unnecessarySecondRim[0]).isAir(),
                    "second shelter built an unnecessary rim despite an existing center roof");
            assertPhysicalExit(context, bot, secondFeet[0]);
            finish(context, bot, "ShelterAdjacentGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterAtomicRecovery", tickLimit = 2000)
    public void lowHealthShelterConsumesBackpackFoodAndHealsBeforeOpening(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        List<BlockPos> shell = shelterShell(feet);
        AIPlayerEntity bot = spawn(context, "ShelterSealedEatGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        InventoryAction.giveItem(bot, new ItemStack(Items.COOKED_BEEF, 2));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_sealed_eat"));
        boolean[] lowHealthInjected = {false};
        boolean[] consumedWhileSealed = {false};
        boolean[] healed = {false};
        boolean[] safeThresholdObserved = {false};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("sealed-eat shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe());
                return;
            }
            boolean sealed = shell.stream().allMatch(pos -> isSealed(context, pos));
            if (!lowHealthInjected[0] && sealed) {
                bot.setHealth(5.0F);
                bot.getHungerManager().setFoodLevel(12);
                bot.getHungerManager().setSaturationLevel(0.0F);
                lowHealthInjected[0] = true;
                return;
            }
            if (lowHealthInjected[0]) {
                int beef = InventoryAction.countItem(bot, Items.COOKED_BEEF);
                // Observe the atomic consumption edge once. After safe healing, OPEN_EXIT must
                // make the envelope non-sealed while the already-consumed count stays below two.
                if (beef < 2 && !consumedWhileSealed[0]) {
                    require(context, sealed,
                            "shelter opened before its physical food consumption completed");
                    consumedWhileSealed[0] = true;
                }
                if (bot.getHealth() > 5.0F) {
                    healed[0] = true;
                }
                if (bot.getHealth() >= 18.0F) {
                    safeThresholdObserved[0] = true;
                } else {
                    require(context, task.state() == TaskState.RUNNING && sealed,
                            "shelter opened or terminated below its safe exit threshold: hp="
                                    + bot.getHealth() + " state=" + task.state());
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, lowHealthInjected[0],
                    "sealed-eat fixture never reached the HOLD envelope");
            require(context, consumedWhileSealed[0],
                    "backpack food was not physically consumed while the shelter was sealed");
            require(context, healed[0] && safeThresholdObserved[0]
                            && bot.getHealth() >= 18.0F,
                    "shelter exited before physical healing reached 18 HP: hp="
                            + bot.getHealth());
            require(context, bot.getHungerManager().getFoodLevel() > 12,
                    "food use did not increase the hunger level");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterSealedEatGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterNightHoldIsolation", tickLimit = 1200)
    public void surfaceShelterStaysSealedUntilDaylight(TestContext context) {
        BlockPos feet = highSurfaceFeet(context);
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterNightHoldGT", feet);
        context.getWorld().setTimeOfDay(18000L);
        require(context, EmergencyShelterTask.isSurfaceShelterAnchor(bot, feet),
                "high terrain anchor was not classified as a surface shelter");
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_night_hold"));
        int[] sealedNightTicks = {0};
        boolean[] daylightReleased = {false};
        boolean[] firstDaylightTickHeld = {false};
        boolean[] nightInterruptedGrace = {false};
        boolean[] interruptedGraceReset = {false};

        context.runAtEveryTick(() -> {
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("night shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe());
                return;
            }
            if (!daylightReleased[0] && task.describe().contains("phase=HOLD")) {
                context.getWorld().setTimeOfDay(18000L);
                sealedNightTicks[0]++;
                require(context, shelterShell(feet).stream().allMatch(
                                pos -> isSealed(context, pos)),
                        "surface shelter opened during the night hold");
                if (sealedNightTicks[0] >= 140) {
                    daylightReleased[0] = true;
                    context.getWorld().setTimeOfDay(1000L);
                }
                return;
            }
            if (daylightReleased[0]) {
                context.getWorld().setTimeOfDay(1000L);
                int daylightTicks = describedInt(task, "daylight_ticks");
                if (nightInterruptedGrace[0] && daylightTicks == 0) {
                    interruptedGraceReset[0] = true;
                }
                if (task.state() == TaskState.RUNNING
                        && daylightTicks > 0
                        && daylightTicks < 100) {
                    require(context, task.describe().contains("phase=HOLD")
                                    && shelterShell(feet).stream().allMatch(
                                    pos -> isSealed(context, pos)),
                            "surface shelter opened before 100 consecutive daylight ticks: "
                                    + task.describe());
                    if (daylightTicks == 1) {
                        firstDaylightTickHeld[0] = true;
                    }
                    if (daylightTicks == 50 && !nightInterruptedGrace[0]) {
                        context.getWorld().setTimeOfDay(18000L);
                        nightInterruptedGrace[0] = true;
                    }
                }
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, daylightReleased[0] && sealedNightTicks[0] >= 140,
                    "surface shelter exited before proving a sustained night HOLD");
            require(context, firstDaylightTickHeld[0]
                            && nightInterruptedGrace[0]
                            && interruptedGraceReset[0]
                            && describedInt(task, "daylight_ticks") >= 100,
                    "surface shelter did not enforce/reset the consecutive daylight grace: "
                            + task.describe());
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterNightHoldGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterCanopyNightIsolation", tickLimit = 500)
    public void leafCanopyStillUsesSurfaceNightHold(TestContext context) {
        verifyOccludedSurfaceNightHold(
                context, Blocks.OAK_LEAVES, 4,
                "ShelterCanopyNightGT", "leaf canopy");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterOverhangNightIsolation", tickLimit = 500)
    public void shallowOverhangStillUsesSurfaceNightHold(TestContext context) {
        verifyOccludedSurfaceNightHold(
                context, Blocks.STONE, 5,
                "ShelterOverhangNightGT", "shallow overhang");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterObservationIsolation", tickLimit = 1000)
    public void observedHostileAtHeadPortIsResealedBeforeFootDoorOpens(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterObservationResealGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 24));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_observation_reseal"));
        HuskEntity[] hostile = {null};
        boolean[] resealObserved = {false};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("observation shelter ended as " + task.state()
                        + ":" + task.failureReason() + " " + task.describe());
                return;
            }
            if (hostile[0] == null && !resealObserved[0]
                    && task.describe().contains("phase=HOLD")) {
                BlockPos hostileFeet = feet.north(2);
                HuskEntity husk = EntityType.HUSK.create(
                        context.getWorld(), SpawnReason.COMMAND);
                if (husk == null) {
                    context.throwGameTestException(
                            "failed to create shelter observation hostile");
                    return;
                }
                husk.setPersistent();
                husk.setAiDisabled(true);
                husk.refreshPositionAndAngles(
                        hostileFeet.getX() + 0.5D, hostileFeet.getY(),
                        hostileFeet.getZ() + 0.5D, 0.0F, 0.0F);
                context.getWorld().spawnEntity(husk);
                hostile[0] = husk;
                return;
            }
            if (!resealObserved[0]
                    && task.describe().contains("phase=HOLD")
                    && task.describe().contains("observation_reseals=1")) {
                require(context, isSealed(context, feet.north())
                                && isSealed(context, feet.north().up()),
                        "hostile observation opened a passable foot doorway");
                resealObserved[0] = true;
                hostile[0].discard();
                hostile[0] = null;
                return;
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, resealObserved[0],
                    "shelter completed without physically resealing observed pressure");
            require(context, !bot.getBlockPos().equals(feet.north()),
                    "shelter reused the pressured north doorway instead of rotating egress");
            assertPhysicalExit(context, bot, feet);
            finish(context, bot, "ShelterObservationResealGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterAllPressureIsolation", tickLimit = 1400)
    public void fourSidedPressureForcesPhysicalExitOnlyAfterGlobalDeadline(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterAllPressureGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 32));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_all_pressure"));
        List<HuskEntity> hostiles = new ArrayList<>();
        boolean[] allDirectionsPressured = {false};
        boolean[] forcedSupportsRemoved = {false};
        int[] forcedUnsupportedTicks = {0};
        int[] forcedAtExitAge = {-1};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (hostiles.isEmpty()
                    && task.state() == TaskState.RUNNING
                    && task.describe().contains("phase=HOLD")) {
                for (Direction direction : Direction.Type.HORIZONTAL) {
                    hostiles.add(spawnHusk(context, feet.offset(direction, 2)));
                }
                return;
            }
            if (describedInt(task, "pressured_egress") == 4) {
                allDirectionsPressured[0] = true;
            }
            if (task.state() == TaskState.RUNNING) {
                if (task.describe().contains("force_pressure_exit=true")
                        && !forcedSupportsRemoved[0]) {
                    forcedAtExitAge[0] = describedInt(task, "exit_age");
                    for (Direction direction : Direction.Type.HORIZONTAL) {
                        context.getWorld().setBlockState(feet.offset(direction).down(),
                                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    }
                    forcedSupportsRemoved[0] = true;
                }
                if (forcedSupportsRemoved[0] && forcedUnsupportedTicks[0] < 80) {
                    forcedUnsupportedTicks[0]++;
                    require(context, task.state() == TaskState.RUNNING,
                            "forced exit became terminal with every landing unsupported");
                    if (forcedUnsupportedTicks[0] == 80) {
                        for (Direction direction : Direction.Type.HORIZONTAL) {
                            context.getWorld().setBlockState(feet.offset(direction).down(),
                                    Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
                if (!hostiles.isEmpty()
                        && !task.describe().contains("phase=STEP_OUT")) {
                    require(context, bot.getBlockPos().equals(feet),
                            "pressure rotation moved before a physical STEP_OUT: "
                                    + task.describe());
                }
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_exit_pressure_timeout".equals(task.failureReason()),
                    "four-sided pressure did not end with the typed timeout: "
                            + task.state() + ":" + task.failureReason());
            require(context, allDirectionsPressured[0]
                            && describedInt(task, "observation_reseals") >= 4
                            && forcedUnsupportedTicks[0] == 80
                            && forcedAtExitAge[0] >= 500,
                    "shelter forced an exit before rotating across all four pressured doors: "
                            + task.describe());
            assertPhysicalExit(context, bot, feet);
            hostiles.forEach(HuskEntity::discard);
            finish(context, bot, "ShelterAllPressureGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterObservationResealFailureIsolation", tickLimit = 1400)
    public void missingResealBlockCannotPublishTerminalInsideShelter(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterResealFailureGT", feet);
        // Ten blocks exactly fund the supported envelope. Once sealed, a full non-block inventory
        // prevents the mined wall drop from being collected, making reseal factually unavailable.
        InventoryAction.giveItem(bot, new ItemStack(Items.NETHERRACK, 10));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_reseal_failure"));
        HuskEntity[] hostile = {null};
        int[] sealedRetryTicks = {0};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (hostile[0] == null
                    && task.state() == TaskState.RUNNING
                    && task.describe().contains("phase=HOLD")) {
                require(context, InventoryAction.countItem(bot, Items.NETHERRACK) == 0,
                        "exact-material fixture retained a reseal block");
                for (int slot = 0; slot < bot.getInventory().main.size(); slot++) {
                    if (bot.getInventory().main.get(slot).isEmpty()) {
                        bot.getInventory().main.set(slot, new ItemStack(Items.STICK, 64));
                    }
                }
                bot.getInventory().markDirty();
                hostile[0] = spawnHusk(context, feet.north(2));
                return;
            }
            boolean observationOnly = context.getWorld().getBlockState(feet.north().up()).isAir()
                    && isSealed(context, feet.north());
            if (hostile[0] != null
                    && task.state() == TaskState.RUNNING && observationOnly
                    && !task.describe().contains("force_pressure_exit=true")) {
                sealedRetryTicks[0]++;
                require(context, bot.getBlockPos().equals(feet)
                                && !hasPassableEnvelopeSide(context, feet),
                        "failed reseal published movement or a passable door before deadline");
                return;
            }
            if (task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_exit_pressure_timeout".equals(task.failureReason()),
                    "failed reseal did not preserve the typed pressure timeout: "
                            + task.state() + ":" + task.failureReason());
            require(context, sealedRetryTicks[0] >= 20,
                    "fixture did not prove bounded head-open/foot-sealed reseal retries");
            assertPhysicalExit(context, bot, feet);
            if (hostile[0] != null) {
                hostile[0].discard();
            }
            finish(context, bot, "ShelterResealFailureGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterAtomicRecovery", tickLimit = 30)
    public void waterRescueAndBodyFluidRejectFixedShelterAdmission(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "ShelterWaterAdmissionGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        int dirtBefore = InventoryAction.countItem(bot, Items.DIRT);

        NavSafetyNet.INSTANCE.requestWaterRescue(bot);
        require(context, !EmergencyShelterTask.canStartAtCurrentPose(bot),
                "active water rescue admitted a fixed shelter anchor");
        EmergencyShelterTask task = new EmergencyShelterTask();
        task.start(bot);
        require(context, task.state() == TaskState.FAILED
                        && "shelter_origin_not_stable".equals(task.failureReason()),
                "water-rescue shelter start was not rejected: "
                        + task.state() + ":" + task.failureReason());
        require(context, InventoryAction.countItem(bot, Items.DIRT) == dirtBefore,
                "rejected water-rescue shelter consumed material");
        require(context, shelterShell(feet).stream().noneMatch(pos -> isSealed(context, pos)),
                "rejected water-rescue shelter mutated its enclosure");

        NavSafetyNet.INSTANCE.clear(bot);
        context.getWorld().setBlockState(feet.up(),
                Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
        require(context, !EmergencyShelterTask.canStartAtCurrentPose(bot),
                "head-column fluid admitted a fixed shelter anchor");
        context.getWorld().setBlockState(feet.up(),
                Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
        finish(context, bot, "ShelterWaterAdmissionGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterAtomicRecovery", tickLimit = 900)
    public void sealedShelterReopensOwnedDoorBeforeEnvironmentalFailure(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterWaterExitGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY, "gametest_shelter_water_exit"));
        boolean[] injected = {false};

        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (!injected[0]
                    && task.state() == TaskState.RUNNING
                    && task.describe().contains("phase=HOLD")) {
                require(context, shelterShell(feet).stream().allMatch(pos -> isSealed(context, pos)),
                        "water was injected before the shelter proved a sealed envelope");
                context.getWorld().setBlockState(feet,
                        Blocks.WATER.getDefaultState(), Block.NOTIFY_ALL);
                NavSafetyNet.INSTANCE.requestWaterRescue(bot);
                injected[0] = true;
                return;
            }
            if (!injected[0] && (task.state() == TaskState.FAILED
                    || task.state() == TaskState.CANCELLED
                    || task.state() == TaskState.COMPLETED)) {
                context.throwGameTestException("shelter ended before water injection: "
                        + task.state() + ":" + task.failureReason());
                return;
            }
            if (!injected[0] || task.state() == TaskState.RUNNING) {
                return;
            }
            require(context, task.state() == TaskState.FAILED
                            && "shelter_environmental_escape_required"
                            .equals(task.failureReason()),
                    "wet shelter did not publish its typed terminal: "
                            + task.state() + ":" + task.failureReason());
            require(context, hasPassableEnvelopeSide(context, feet),
                    "wet shelter failed while its owned enclosure remained sealed");
            NavSafetyNet.INSTANCE.clear(bot);
            finish(context, bot, "ShelterWaterExitGT");
        });
    }

    private static void verifyOccludedSurfaceNightHold(TestContext context,
                                                       Block overheadBlock,
                                                       int overheadHeight,
                                                       String botName,
                                                       String fixtureName) {
        BlockPos feet = highSurfaceFeet(context);
        preparePlatform(context, feet, 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                context.getWorld().setBlockState(
                        feet.add(dx, overheadHeight, dz),
                        overheadBlock.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        AIPlayerEntity bot = spawn(context, botName, feet);
        context.getWorld().setTimeOfDay(18000L);
        require(context,
                context.getWorld().getBlockState(feet.up(overheadHeight))
                        .isOf(overheadBlock),
                fixtureName + " did not install its overhead fixture");
        require(context, EmergencyShelterTask.isSurfaceShelterAnchor(bot, feet),
                fixtureName + " was not admitted by the nearby surface heightmap");
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 20));

        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_occluded_surface_night"));
        int[] sealedNightTicks = {0};
        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(18000L);
            if (task.state() != TaskState.RUNNING) {
                context.throwGameTestException(fixtureName + " shelter ended during night HOLD: "
                        + task.state() + ":" + task.failureReason());
                return;
            }
            if (!task.describe().contains("phase=HOLD")) {
                return;
            }
            sealedNightTicks[0]++;
            require(context, shelterShell(feet).stream().allMatch(pos -> isSealed(context, pos)),
                    fixtureName + " surface shelter opened during night HOLD");
            if (sealedNightTicks[0] >= 140) {
                finish(context, bot, botName);
            }
        });
    }

    private static HuskEntity spawnHusk(TestContext context, BlockPos feet) {
        HuskEntity husk = EntityType.HUSK.create(context.getWorld(), SpawnReason.COMMAND);
        if (husk == null) {
            throw new IllegalStateException("failed to create shelter pressure husk");
        }
        husk.setPersistent();
        husk.setAiDisabled(true);
        husk.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                0.0F, 0.0F);
        context.getWorld().spawnEntity(husk);
        return husk;
    }

    private static HuskEntity spawnAiHusk(TestContext context,
                                          BlockPos feet,
                                          AIPlayerEntity target) {
        HuskEntity husk = EntityType.HUSK.create(context.getWorld(), SpawnReason.COMMAND);
        if (husk == null) {
            throw new IllegalStateException("failed to create AI shelter pressure husk");
        }
        husk.setPersistent();
        husk.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                0.0F, 0.0F);
        husk.setTarget(target);
        if (!context.getWorld().spawnEntity(husk)) {
            throw new IllegalStateException("failed to spawn AI shelter pressure husk");
        }
        return husk;
    }

    private static int describedInt(EmergencyShelterTask task, String key) {
        String marker = key + "=";
        String description = task.describe();
        int start = description.indexOf(marker);
        if (start < 0) {
            return -1;
        }
        start += marker.length();
        int end = description.indexOf(' ', start);
        String value = end < 0 ? description.substring(start) : description.substring(start, end);
        return Integer.parseInt(value);
    }

    private static BlockPos highSurfaceFeet(TestContext context) {
        BlockPos template = context.getAbsolutePos(new BlockPos(4, 4, 4));
        return new BlockPos(template.getX(), 64, template.getZ());
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

    private static boolean hasPassableEnvelopeSide(TestContext context, BlockPos feet) {
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos side = feet.offset(direction);
            if (context.getWorld().getBlockState(side)
                    .getCollisionShape(context.getWorld(), side).isEmpty()
                    && context.getWorld().getBlockState(side.up())
                    .getCollisionShape(context.getWorld(), side.up()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void assertPhysicalExit(TestContext context,
                                           AIPlayerEntity bot,
                                           BlockPos shelterFeet) {
        BlockPos actual = bot.getBlockPos();
        int horizontal = Math.abs(actual.getX() - shelterFeet.getX())
                + Math.abs(actual.getZ() - shelterFeet.getZ());
        require(context, actual.getY() == shelterFeet.getY() && horizontal == 1,
                "terminal pose was not one adjacent exit step: "
                        + shelterFeet.toShortString() + " -> " + actual.toShortString());
        require(context, Standability.isStandable(context.getWorld(), actual),
                "terminal exit was not standable: " + actual.toShortString());
        require(context, context.getWorld().getBlockState(actual).isAir()
                        && context.getWorld().getBlockState(actual.up()).isAir(),
                "terminal exit did not leave a two-block opening");
    }

    private static void preparePlatform(TestContext context, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                context.getWorld().setBlockState(cell.down(),
                        Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell,
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell.up(),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(cell.up(2),
                        Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        context.getWorld().setTimeOfDay(1000L);
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(), feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
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
