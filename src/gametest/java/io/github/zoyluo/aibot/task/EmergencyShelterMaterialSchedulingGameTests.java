package io.github.zoyluo.aibot.task;

import io.github.zoyluo.aibot.action.InventoryAction;
import io.github.zoyluo.aibot.action.MaterialPalette;
import io.github.zoyluo.aibot.entity.AIPlayerEntity;
import io.github.zoyluo.aibot.manager.AIPlayerManager;
import io.github.zoyluo.aibot.mode.ObservableWorldQuery;
import io.github.zoyluo.aibot.pathfinding.Standability;
import io.github.zoyluo.aibot.runtime.TaskOrigin;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HuskEntity;
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
import java.util.Set;

/**
 * Live regressions for emergency-only wood material and safety-task replacement boundaries.
 */
public final class EmergencyShelterMaterialSchedulingGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterMaterialScheduling", tickLimit = 500)
    public void mixedWoodFallbackBuildsHoldsAndPhysicallyExitsWithDirtFirst(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 4);
        AIPlayerEntity bot = spawn(context, "ShelterMixedWoodGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 2));
        InventoryAction.giveItem(bot, new ItemStack(Items.BIRCH_LOG, 8));

        require(context, EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "mixed dirt/planks/logs inventory did not satisfy exact shelter admission");
        EmergencyShelterTask task = new EmergencyShelterTask();
        TaskManager.INSTANCE.assign(bot, task,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_mixed_wood_fallback"));

        int[] holdStartedElapsed = {-1};
        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            if (task.state() == TaskState.FAILED || task.state() == TaskState.CANCELLED) {
                context.throwGameTestException("mixed-wood shelter ended as "
                        + task.state() + ":" + task.failureReason() + " " + task.describe());
                return;
            }
            if (holdStartedElapsed[0] < 0 && task.describe().contains("phase=HOLD")) {
                holdStartedElapsed[0] = task.elapsedTicks();
                List<BlockPos> ownedEnvelope = shelterOwnedEnvelope(feet);
                require(context, countBlock(context, ownedEnvelope, Blocks.DIRT) == 2,
                        "lower-value dirt was not spent before emergency wood");
                require(context, countBlock(context, ownedEnvelope, Blocks.OAK_PLANKS) == 2,
                        "planks were not used before raw logs in the mixed fallback");
                require(context, countBlock(context, ownedEnvelope, Blocks.BIRCH_LOG) == 6,
                        "raw logs did not complete the mixed emergency enclosure");
                require(context, shelterShell(feet).stream()
                                .allMatch(pos -> isSealed(context, pos)),
                        "mixed emergency enclosure entered HOLD with a physical opening");
            }
            if (task.state() != TaskState.COMPLETED) {
                return;
            }
            require(context, holdStartedElapsed[0] >= 0
                            && task.elapsedTicks() - holdStartedElapsed[0] >= 100,
                    "mixed emergency enclosure skipped its bounded HOLD transaction");
            assertOwnedNorthExit(context, bot, feet);
            finish(context, bot, "ShelterMixedWoodGT");
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterMaterialScheduling", tickLimit = 30)
    public void emergencyWoodNeverAuthorizesPermanentMiningBarricade(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 4, 4));
        preparePlatform(context, feet, 3);
        AIPlayerEntity bot = spawn(context, "BarricadeWoodGuardGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.OAK_PLANKS, 8));
        InventoryAction.giveItem(bot, new ItemStack(Items.BIRCH_LOG, 8));
        BlockPos gateFeet = feet.east();

        require(context, MaterialPalette.countEmergencyShelterBlocks(bot) == 16
                        && EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "wood-only inventory was not admitted for its temporary emergency owner");
        require(context, MaterialPalette.countShelterBlocks(bot) == 0
                        && !MiningBarricadeTask.hasMaterialsForOpenGate(bot),
                "emergency-only wood leaked into the permanent barricade palette");

        MiningBarricadeTask barricade = new MiningBarricadeTask(feet, gateFeet);
        barricade.start(bot);
        require(context, barricade.state() == TaskState.FAILED
                        && "missing barricade_blocks required=2 available=0"
                        .equals(barricade.failureReason()),
                "wood-only barricade admission did not fail closed: "
                        + barricade.state() + ":" + barricade.failureReason());
        require(context, context.getWorld().getBlockState(gateFeet).isAir()
                        && context.getWorld().getBlockState(gateFeet.up()).isAir(),
                "failed wood-only barricade admission mutated its gate");
        require(context, InventoryAction.countItem(bot, Items.OAK_PLANKS) == 8
                        && InventoryAction.countItem(bot, Items.BIRCH_LOG) == 8,
                "failed permanent barricade admission consumed emergency wood");
        finish(context, bot, "BarricadeWoodGuardGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterMaterialScheduling", tickLimit = 80)
    public void oneBlockCannotDispatchDoomedShelterOrGrowPauseStack(TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 180, 4));
        prepareEscapeCorridor(context, feet);
        AIPlayerEntity bot = spawn(context, "ShelterOneBlockGateGT", feet);
        bot.setHealth(4.7F);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT));
        InventoryAction.giveItem(bot, new ItemStack(Items.WOODEN_SWORD));
        HoldingTask mission = new HoldingTask("one_block_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_one_block_admission"));
        HuskEntity hostile = spawnDisabledHusk(context, feet.east(3));

        require(context, EmergencyShelterTask.hasShelterBlock(bot)
                        && !EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "one-block fixture did not distinguish item presence from full admission");
        require(context, ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "one-block fixture hostile was not factually observable");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        Task firstSafety = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstSafety != null
                        && !(firstSafety instanceof EmergencyShelterTask),
                "one block dispatched a doomed emergency shelter");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "fallback safety did not preserve exactly one mission frame");
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "fallback hostile response was not owned by SAFETY");

        for (int attempt = 0; attempt < 8; attempt++) {
            DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
            require(context, !(TaskManager.INSTANCE.getActive(bot).orElse(null)
                            instanceof EmergencyShelterTask),
                    "repeated one-block scan dispatched a doomed shelter");
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == 1
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission,
                    "repeated one-block scan grew or replaced the mission pause frame");
        }
        require(context, InventoryAction.countItem(bot, Items.DIRT) == 1,
                "failed exact shelter admission consumed its only block");
        hostile.discard();
        finish(context, bot, "ShelterOneBlockGateGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterMaterialScheduling", tickLimit = 80)
    public void emergencyShelterSupersedesSafetyEvadeWithoutNestingPauseFrame(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 220, 4));
        prepareEscapeCorridor(context, feet);
        AIPlayerEntity bot = spawn(context, "ShelterSafetySwapGT", feet);
        InventoryAction.giveItem(bot, new ItemStack(Items.DIRT, 16));
        HoldingTask mission = new HoldingTask("safety_swap_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_shelter_safety_supersede"));
        EndermanEntity hostile = spawnProvokedEnderman(context, feet.east(8), bot);

        require(context, DangerWatcher.isActiveHostileThreat(bot, hostile)
                        && ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "safety-swap Enderman was not a factual active threat");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task firstSafety = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstSafety instanceof EvadeTask,
                "melee-forbidden hostile did not establish the active Evade fixture: "
                        + (firstSafety == null ? "idle" : firstSafety.name()));
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "Evade fixture was not assigned with SAFETY origin");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "Evade fixture did not preserve exactly one mission frame");

        bot.setHealth(4.7F);
        require(context, EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "adequate shelter inventory failed exact current-pose admission");
        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task replacement = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, replacement instanceof EmergencyShelterTask,
                "low-health emergency did not supersede active SAFETY Evade: "
                        + (replacement == null ? "idle" : replacement.name()));
        require(context, firstSafety.state() == TaskState.FAILED
                        && "aborted".equals(firstSafety.failureReason()),
                "superseded Evade did not publish the manager's abort terminal: "
                        + firstSafety.state() + ":" + firstSafety.failureReason());
        require(context, TaskManager.INSTANCE.activeOrigin(bot)
                        .map(TaskOrigin::safety).orElse(false),
                "replacement shelter lost SAFETY origin");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "SAFETY-to-SAFETY supersede nested another pause frame");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
        require(context, TaskManager.INSTANCE.getActive(bot).orElse(null) == replacement
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "active shelter scan replaced ownership or grew the pause stack");
        hostile.discard();
        finish(context, bot, "ShelterSafetySwapGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterMaterialScheduling", tickLimit = 80)
    public void genericThreatSupersedesNonDefenseSafetyWithoutNestingMission(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 240, 4));
        prepareEscapeCorridor(context, feet);
        AIPlayerEntity bot = spawn(context, "ThreatSafetySwapGT", feet);
        HoldingTask mission = new HoldingTask("threat_swap_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_generic_threat_safety_supersede"));
        TaskManager.INSTANCE.pauseFor(bot, "establish_mission_frame");
        HoldingTask safetyHolder = new HoldingTask("critical_hunt_holder");
        TaskManager.INSTANCE.assign(bot, safetyHolder,
                TaskOrigin.safety("critical_hunt_for_food"));
        EndermanEntity hostile = spawnProvokedEnderman(context, feet.east(8), bot);

        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "non-defense SAFETY fixture did not start over exactly one mission frame");
        require(context, !DangerWatcher.hasActiveHostileDefenseOwner(bot),
                "critical hunt SAFETY holder was misclassified as hostile defense");
        require(context, DangerWatcher.isActiveHostileThreat(bot, hostile)
                        && ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "generic replacement Enderman was not a factual active threat");

        DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);

        Task replacement = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, replacement instanceof EvadeTask,
                "generic hostile response did not replace non-defense SAFETY with Evade: "
                        + (replacement == null ? "idle" : replacement.name()));
        require(context, safetyHolder.state() == TaskState.FAILED
                        && "aborted".equals(safetyHolder.failureReason()),
                "replaced non-defense SAFETY holder did not publish an abort terminal: "
                        + safetyHolder.state() + ":" + safetyHolder.failureReason());
        require(context, DangerWatcher.hasActiveHostileDefenseOwner(bot),
                "replacement Evade was not recognized as a hostile-defense owner");
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "generic SAFETY-to-SAFETY replacement nested the non-defense holder");
        hostile.discard();
        finish(context, bot, "ThreatSafetySwapGT");
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterMaterialScheduling", tickLimit = 220)
    public void trappedFightBackReplacesNonDefenseSafetyWithoutNestingMission(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 260, 4));
        prepareIsolatedTrap(context, feet);
        AIPlayerEntity bot = spawn(context, "TrappedFightBackSwapGT", feet);
        bot.setHealth(18.0F);
        HoldingTask mission = new HoldingTask("trapped_fight_back_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_trapped_fight_back_safety_supersede"));
        HuskEntity hostile = spawnDisabledHusk(context, feet.east(3));

        require(context, EvadeTask.admitBestSurfaceEscapePath(
                        bot, hostile, hostile.getBlockPos(), 12) == null
                        && bot.getActionPack().isPathExecutorIdle(),
                "trapped fight-back fixture unexpectedly exposed an escape route");
        require(context, DangerWatcher.INSTANCE.scanBot(
                        context.getWorld().getServer(), bot),
                "first trapped fight-back scan was not handled");
        Task firstEvade = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, firstEvade instanceof EvadeTask,
                "first trapped response was not Evade: "
                        + (firstEvade == null ? "idle" : firstEvade.name()));
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "first trapped Evade did not preserve exactly one mission frame");

        HoldingTask[] safetyHolder = {null};
        long[] holderAssignedTick = {-1L};
        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            require(context, bot.isAlive() && bot.getBlockPos().equals(feet),
                    "trapped fight-back fixture moved or died before replacement");
            require(context, mission.state() == TaskState.PAUSED
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission,
                    "trapped fight-back fixture lost its mission cursor");

            if (safetyHolder[0] == null
                    && firstEvade.state() == TaskState.FAILED
                    && TaskManager.INSTANCE.getActive(bot).isEmpty()) {
                require(context, "no_valid_escape_route"
                                .equals(firstEvade.failureReason()),
                        "first trapped Evade ended for the wrong reason: "
                                + firstEvade.failureReason());
                safetyHolder[0] = new HoldingTask("critical_hunt_holder");
                TaskManager.INSTANCE.assign(bot, safetyHolder[0],
                        TaskOrigin.safety("critical_hunt_for_food"));
                holderAssignedTick[0] = context.getTick();
                require(context, !DangerWatcher.hasActiveHostileDefenseOwner(bot),
                        "non-defense SAFETY holder was classified as trapped protection");
            }
            if (safetyHolder[0] == null) {
                return;
            }

            bot.hurtTime = 5;
            DangerWatcher.INSTANCE.scanBot(context.getWorld().getServer(), bot);
            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            if (active instanceof CombatTask) {
                require(context, safetyHolder[0].state() == TaskState.FAILED
                                && "aborted".equals(safetyHolder[0].failureReason()),
                        "trapped_fight_back did not replace its non-defense SAFETY holder");
                require(context, DangerWatcher.hasActiveHostileDefenseOwner(bot),
                        "trapped_fight_back Combat was not recognized as hostile defense");
                require(context, mission.state() == TaskState.PAUSED
                                && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                                && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                        "trapped_fight_back nested SAFETY over the mission frame");
                hostile.discard();
                finish(context, bot, "TrappedFightBackSwapGT");
                return;
            }
            require(context, TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "waiting for trapped_fight_back grew the mission pause stack");
            if (context.getTick() - holderAssignedTick[0] > 120) {
                context.throwGameTestException(
                        "trapped_fight_back never replaced non-defense SAFETY: active="
                                + (active == null ? "idle" : active.name()));
            }
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE,
            batchId = "shelterMaterialScheduling", tickLimit = 260)
    public void criticalCreeperWithoutRouteOrMaterialsRetainsOneSafetyOwner(
            TestContext context) {
        BlockPos feet = context.getAbsolutePos(new BlockPos(4, 280, 4));
        prepareIsolatedTrap(context, feet);
        AIPlayerEntity bot = spawn(context, "CreeperBackoffOwnerGT", feet);
        bot.setHealth(4.7F);
        bot.getHungerManager().setFoodLevel(17);
        bot.getHungerManager().setSaturationLevel(0.0F);
        HoldingTask mission = new HoldingTask("creeper_backoff_mission");
        TaskManager.INSTANCE.assign(bot, mission,
                TaskOrigin.of(TaskOrigin.Kind.VERIFY,
                        "gametest_critical_creeper_backoff"));
        CreeperEntity hostile = spawnDisabledCreeper(context, feet.east(3));

        require(context, !EmergencyShelterTask.hasShelterBlock(bot)
                        && !EmergencyShelterTask.hasMaterialsForCurrentPose(bot),
                "critical backoff fixture unexpectedly carried shelter material");
        require(context, DangerWatcher.isActiveHostileThreat(bot, hostile)
                        && ObservableWorldQuery.canObserveEntity(bot, hostile)
                        && CombatCore.hasLineOfSight(bot, hostile),
                "critical backoff Creeper was not a factual HIGH hostile");
        require(context, EvadeTask.admitBestSurfaceEscapePath(
                        bot, hostile, hostile.getBlockPos(), 12) == null
                        && bot.getActionPack().isPathExecutorIdle(),
                "isolated trap unexpectedly exposed an effective escape landing");

        require(context, DangerWatcher.INSTANCE.scanBot(
                        context.getWorld().getServer(), bot),
                "first critical Creeper scan was not handled");
        Task first = TaskManager.INSTANCE.getActive(bot).orElse(null);
        require(context, first instanceof CreeperDefenseTask,
                "first critical Creeper response was not the dedicated owner: "
                        + (first == null ? "idle" : first.name()));
        require(context, mission.state() == TaskState.PAUSED
                        && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                        && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                "first Creeper defense did not preserve exactly one mission frame");

        long started = context.getTick();
        context.runAtEveryTick(() -> {
            context.getWorld().setTimeOfDay(1000L);
            bot.setHealth(4.7F);
            bot.getHungerManager().setFoodLevel(17);
            require(context, bot.isAlive() && bot.getBlockPos().equals(feet),
                    "critical backoff fixture moved or died before the fourth decision");
            require(context, mission.state() == TaskState.PAUSED
                            && TaskManager.INSTANCE.peekPaused(bot).orElse(null) == mission
                            && TaskManager.INSTANCE.pausedDepth(bot) == 1,
                    "critical backoff resumed, duplicated or replaced the mission frame");
            require(context, InventoryAction.countItem(bot, Items.DIRT) == 0
                            && !EmergencyShelterTask.hasShelterBlock(bot),
                    "critical backoff acquired or invented shelter material");

            Task active = TaskManager.INSTANCE.getActive(bot).orElse(null);
            require(context, active == first
                            && active instanceof CreeperDefenseTask
                            && active.state() == TaskState.RUNNING,
                    "route/material failure churned or released the Creeper owner: "
                            + (active == null ? "idle" : active.name() + "/" + active.state()));
            require(context, TaskManager.INSTANCE.activeOrigin(bot)
                            .map(TaskOrigin::safety).orElse(false),
                    "critical Creeper defense lost SAFETY ownership");

            if (context.getTick() - started >= 160) {
                hostile.discard();
                finish(context, bot, "CreeperBackoffOwnerGT");
            }
        });
    }

    private static List<BlockPos> shelterOwnedEnvelope(BlockPos feet) {
        List<BlockPos> result = new ArrayList<>(10);
        result.addAll(shelterShell(feet));
        result.add(feet.up(2).north());
        return List.copyOf(result);
    }

    private static List<BlockPos> shelterShell(BlockPos feet) {
        List<BlockPos> result = new ArrayList<>(9);
        result.add(feet.up(2));
        for (Direction direction : Direction.Type.HORIZONTAL) {
            result.add(feet.offset(direction));
            result.add(feet.up().offset(direction));
        }
        return List.copyOf(result);
    }

    private static long countBlock(TestContext context,
                                   List<BlockPos> positions,
                                   Block block) {
        return positions.stream()
                .filter(pos -> context.getWorld().getBlockState(pos).isOf(block))
                .count();
    }

    private static boolean isSealed(TestContext context, BlockPos pos) {
        var state = context.getWorld().getBlockState(pos);
        return !state.isReplaceable()
                && !state.getCollisionShape(context.getWorld(), pos).isEmpty();
    }

    private static void assertOwnedNorthExit(TestContext context,
                                             AIPlayerEntity bot,
                                             BlockPos shelterFeet) {
        BlockPos exit = shelterFeet.north();
        require(context, bot.getBlockPos().equals(exit),
                "shelter did not physically step through its planned north exit: "
                        + shelterFeet.toShortString() + " -> "
                        + bot.getBlockPos().toShortString());
        require(context, Standability.isStandable(context.getWorld(), exit),
                "opened shelter exit was not a standable landing");
        require(context, context.getWorld().getBlockState(exit).isAir()
                        && context.getWorld().getBlockState(exit.up()).isAir(),
                "shelter completed without reopening its owned two-block north doorway");
    }

    private static void preparePlatform(TestContext context, BlockPos feet, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                context.getWorld().setBlockState(
                        cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(
                        cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(
                        cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(
                        cell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static void prepareEscapeCorridor(TestContext context, BlockPos feet) {
        context.getWorld().setTimeOfDay(18000L);
        for (int dx = -24; dx <= 12; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cell = feet.add(dx, 0, dz);
                context.getWorld().setBlockState(
                        cell.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(
                        cell, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(
                        cell.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                context.getWorld().setBlockState(
                        cell.up(2), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
    }

    private static void prepareIsolatedTrap(TestContext context, BlockPos feet) {
        context.getWorld().setTimeOfDay(1000L);
        for (int dx = -18; dx <= 18; dx++) {
            for (int dz = -18; dz <= 18; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    context.getWorld().setBlockState(
                            feet.add(dx, dy, dz),
                            Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                }
            }
        }
        context.getWorld().setBlockState(
                feet.down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
        context.getWorld().setBlockState(
                feet.east(3).down(), Blocks.STONE.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static AIPlayerEntity spawn(TestContext context, String name, BlockPos feet) {
        AIPlayerEntity bot = AIPlayerManager.INSTANCE.spawn(
                        context.getWorld().getServer(), name, context.getWorld(),
                        Vec3d.ofBottomCenter(feet), 0.0F, 0.0F, GameMode.SURVIVAL)
                .orElseThrow(() -> new IllegalStateException("failed to spawn " + name));
        bot.teleport(context.getWorld(),
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                Set.of(), 0.0F, 0.0F, true);
        bot.setHealth(bot.getMaxHealth());
        bot.getHungerManager().setFoodLevel(20);
        bot.getHungerManager().setSaturationLevel(5.0F);
        return bot;
    }

    private static HuskEntity spawnDisabledHusk(TestContext context, BlockPos feet) {
        HuskEntity hostile = EntityType.HUSK.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (hostile == null) {
            throw new IllegalStateException("failed to create one-block shelter hostile");
        }
        hostile.setPersistent();
        hostile.setAiDisabled(true);
        hostile.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                90.0F, 0.0F);
        require(context, context.getWorld().spawnEntity(hostile),
                "failed to spawn one-block shelter hostile");
        return hostile;
    }

    private static EndermanEntity spawnProvokedEnderman(TestContext context,
                                                         BlockPos feet,
                                                         AIPlayerEntity target) {
        EndermanEntity hostile = EntityType.ENDERMAN.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (hostile == null) {
            throw new IllegalStateException("failed to create safety-swap Enderman");
        }
        hostile.setPersistent();
        hostile.setAiDisabled(true);
        hostile.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                90.0F, 0.0F);
        hostile.setAngerTime(600);
        hostile.setAngryAt(target.getUuid());
        hostile.setTarget(target);
        require(context, context.getWorld().spawnEntity(hostile),
                "failed to spawn safety-swap Enderman");
        return hostile;
    }

    private static CreeperEntity spawnDisabledCreeper(TestContext context, BlockPos feet) {
        CreeperEntity hostile = EntityType.CREEPER.create(
                context.getWorld(), SpawnReason.COMMAND);
        if (hostile == null) {
            throw new IllegalStateException("failed to create critical backoff Creeper");
        }
        hostile.setPersistent();
        hostile.setAiDisabled(true);
        hostile.refreshPositionAndAngles(
                feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                90.0F, 0.0F);
        require(context, context.getWorld().spawnEntity(hostile),
                "failed to spawn critical backoff Creeper");
        return hostile;
    }

    private static void finish(TestContext context, AIPlayerEntity bot, String name) {
        DangerWatcher.INSTANCE.clear(bot);
        TaskManager.INSTANCE.cancelIntentTasks(bot, "gametest_complete");
        AIPlayerManager.INSTANCE.despawn(bot.getServer(), name);
        context.complete();
    }

    private static void require(TestContext context, boolean condition, String message) {
        if (!condition) {
            context.throwGameTestException(message);
        }
    }

    private static final class HoldingTask extends AbstractTask {
        private final String taskName;

        private HoldingTask(String taskName) {
            this.taskName = taskName;
        }

        @Override
        public String name() {
            return taskName;
        }

        @Override
        public String describe() {
            return "Holding a resumable mission cursor";
        }

        @Override
        public double progress() {
            return 0.5D;
        }

        @Override
        protected void onStart(AIPlayerEntity bot) {
        }

        @Override
        protected void onTick(AIPlayerEntity bot) {
        }
    }
}
